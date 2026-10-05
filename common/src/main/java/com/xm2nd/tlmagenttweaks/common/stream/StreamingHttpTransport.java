package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolResponseDecoder;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 纯 JDK 流式 HTTP 状态机：流式优先、断流非流式回退、指定 HTTP 状态码重试。
 * <p>
 * 一个 {@link Session} 对应一次逻辑模型请求，其内部至多发生
 * {@code 1 + maxRetries + 1} 次实际 HTTP 尝试：
 * <ul>
 *   <li><b>状态码重试</b>：429 / 500 / 502 / 503 / 504，最多 {@code maxRetries} 次，
 *       每次固定等待 {@code retryDelay}（忽略 {@code Retry-After}，不做指数退避与抖动）；</li>
 *   <li><b>非流式回退</b>：仅在服务端明确不支持 {@code stream}/{@code stream_options}，
 *       或流式读取中途失败时发生一次；此后所有尝试都保持 {@code stream:false}；</li>
 *   <li>两者各自计数、互不占用对方额度；断流不会重复消耗状态码重试次数；</li>
 *   <li>额度耗尽后，最后一次的错误响应（状态码 + 截断后的正文）原样交回调用方。</li>
 * </ul>
 * 冻结请求体只解一次，全部尝试复用同一份正文、模型、消息、工具结果与鉴权头；
 * 重试不经过 {@code LLMClient.chat}，因此不会重新构造历史、不会重复执行工具。
 * <p>
 * 只有整轮（含全部重试与回退）最终确定后才提交结果：部分尝试不会进入统计入口，
 * 也只有 2xx 完整响应才触发 {@code onAggregated} 与成功终态。
 * 每个尝试独占连接、响应流与计时器，超时、取消与迟到的旧尝试都不会污染后续尝试。
 */
public final class StreamingHttpTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingHttpTransport.class);
    private static final Duration DEFAULT_PHASE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_PHASE_TIMEOUT = Duration.ofSeconds(90);
    private static final int READ_BUFFER_BYTES = 8192;
    /** 交回调用方的错误正文上限，避免整个错误页进入日志与聊天栏。 */
    private static final int MAX_ERROR_BODY_CHARS = 4096;

    private static final ExecutorService READER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tlm-stream-reader");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 读 body 硬截止计时器：JDK {@code HttpRequest.timeout} 只覆盖“收到响应头”，
     * 无法中断已获得的 {@link InputStream} 阻塞读取，因此必须由独立计时器强制
     * 关闭流并让本次尝试作废。
     */
    private static final ScheduledExecutorService DEADLINE = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "tlm-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            READER.shutdownNow();
            DEADLINE.shutdownNow();
        }, "tlm-stream-shutdown"));
    }

    private final Duration phaseTimeout;
    private final SiteThrottleScheduler gate;

    public StreamingHttpTransport() {
        this(null, null);
    }

    public StreamingHttpTransport(Duration phaseTimeout) {
        this(phaseTimeout, null);
    }

    public StreamingHttpTransport(Duration phaseTimeout, SiteThrottleScheduler gate) {
        this.phaseTimeout = phaseTimeout;
        this.gate = gate;
    }

    /** 站点调度器；服务端停止时由生命周期统一丢弃排队与延迟发送任务。 */
    public SiteThrottleScheduler gate() {
        return gate;
    }

    public static FrozenRequest freeze(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow(
                () -> new IllegalArgumentException("Streaming request must have a body"));
        return new FrozenRequest(request, readPublisher(publisher));
    }

    /**
     * 建立一次逻辑请求的状态机（尚未发送），并把发送时机交给调用方：
     * 调用方可以先登记取消句柄，再调用 {@link Call#start()}，避免登记前的取消竞态。
     */
    public Call prepare(HttpClient client, FrozenRequest frozen, SiteProtocol protocol,
                        ProtocolStreamRequestListener listener, String siteId,
                        ConversationBudget budget, RequestRetryPolicy policy,
                        AttemptListener attemptListener) {
        Session session = new Session(frozen, protocol, listener, resolveTimeout(frozen.request()));
        session.client = client;
        session.siteId = siteId == null ? SendGate.DEFAULT_SITE : siteId;
        session.budget = budget;
        session.policy = policy == null ? RequestRetryPolicy.DISABLED : policy;
        session.attemptListener = attemptListener == null ? AttemptListener.NO_OP : attemptListener;
        return new Call(session);
    }

    /** 旧版入口：立即发送，不带预算、重试与站点调度（保留 #8 行为）。 */
    public Call sendCall(HttpClient client, FrozenRequest frozen, SiteProtocol protocol,
                         ProtocolStreamRequestListener listener) {
        Call call = prepare(client, frozen, protocol, listener, null, null, null, null);
        call.start();
        return call;
    }

    /** 旧版入口：立即发送并返回最终结果。 */
    public CompletableFuture<HttpResponse<String>> send(HttpClient client, FrozenRequest frozen,
                                                        SiteProtocol protocol,
                                                        ProtocolStreamRequestListener listener) {
        return sendCall(client, frozen, protocol, listener).future();
    }

    /** 一次性读完请求体，使重试与回退都能复用同一份字节。 */
    private static byte[] readPublisher(HttpRequest.BodyPublisher publisher) {
        int initial = publisher.contentLength() > 0 && publisher.contentLength() < Integer.MAX_VALUE
                ? (int) publisher.contentLength() : 256;
        ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
        CompletableFuture<Throwable> finished = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.write(chunk, 0, chunk.length);
            }

            @Override
            public void onError(Throwable throwable) {
                finished.complete(throwable);
            }

            @Override
            public void onComplete() {
                finished.complete(null);
            }
        });
        try {
            Throwable failure = finished.get(30, TimeUnit.SECONDS);
            if (failure != null) {
                throw new UncheckedIOException(new IOException("Request body publisher failed", failure));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Interrupted while reading request body", e));
        } catch (ExecutionException | TimeoutException e) {
            throw new UncheckedIOException(new IOException("Failed to read request body", e));
        }
        return out.toByteArray();
    }

    private Duration resolveTimeout(HttpRequest request) {
        Duration configured = phaseTimeout;
        if (configured == null) {
            configured = request.timeout().orElse(DEFAULT_PHASE_TIMEOUT);
        }
        if (configured.isNegative() || configured.isZero()) {
            configured = DEFAULT_PHASE_TIMEOUT;
        }
        return configured.compareTo(MAX_PHASE_TIMEOUT) > 0 ? MAX_PHASE_TIMEOUT : configured;
    }

    public static final class FrozenRequest {
        private final HttpRequest request;
        private final byte[] body;

        private FrozenRequest(HttpRequest request, byte[] body) {
            this.request = request;
            this.body = body;
        }

        public HttpRequest request() {
            return request;
        }

        public byte[] body() {
            return body.clone();
        }
    }

    /** 一次逻辑请求的可撤销句柄。 */
    public final class Call {
        private final Session session;
        private final AtomicBoolean started = new AtomicBoolean();

        private Call(Session session) {
            this.session = session;
        }

        public CompletableFuture<HttpResponse<String>> future() {
            return session.result;
        }

        /** 开始发送（可在登记取消句柄之后调用）。 */
        public void start() {
            if (started.compareAndSet(false, true)) {
                session.start();
            }
        }

        public void cancel() {
            session.cancel();
        }

        public Session session() {
            return session;
        }
    }

    /** 尝试时序的只读观察者：让上层同步等待气泡与站点排队状态。 */
    public interface AttemptListener {
        AttemptListener NO_OP = new AttemptListener() {
            @Override
            public void onScheduled(Stage stage, boolean firstAttempt, int retryOrdinal,
                                    boolean rateLimited, boolean fallback) {
            }

            @Override
            public void onSendStart(boolean firstAttempt) {
            }
        };

        /**
         * 本次尝试进入等待。
         *
         * @param stage        等待类型
         * @param firstAttempt 是否为整轮请求的第一次发送（首次发送不等待）
         * @param retryOrdinal 已消耗的状态码重试次数（1 起；站点排队场景为 0）
         * @param rateLimited  上一次尝试是否因 429 被限流
         * @param fallback     本次尝试是否为流式中断后的首次非流式重发
         *                     （与状态码重试共用等待，但提示文案不同）
         */
        void onScheduled(Stage stage, boolean firstAttempt, int retryOrdinal, boolean rateLimited,
                         boolean fallback);

        /** 等待结束，即将真正发出。 */
        void onSendStart(boolean firstAttempt);

        enum Stage {
            /** 本次请求自身的固定重试等待。 */
            RETRY_WAIT,
            /** 站点冷却或积压导致的排队。 */
            SITE_QUEUE
        }
    }

    /** 一次逻辑模型请求的状态机。 */
    public final class Session {
        private final FrozenRequest frozen;
        private final SiteProtocol protocol;
        private final ProtocolStreamRequestListener listener;
        private final Duration timeout;
        private final String requestId = java.util.UUID.randomUUID().toString();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Attempt> activeAttempt = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final CompletableFuture<HttpResponse<String>> result = new CompletableFuture<>();
        private final List<SendGate.Handle> queueHandles = new CopyOnWriteArrayList<>();
        private final AtomicInteger statusRetries = new AtomicInteger();
        /** statusRetries 已在 wait 中就绪时置位，保证真正的发送只消耗一次额度。 */
        private final AtomicBoolean retryReserved = new AtomicBoolean();

        private volatile HttpClient client;
        private volatile String siteId = SendGate.DEFAULT_SITE;
        private volatile ConversationBudget budget;
        private volatile RequestRetryPolicy policy = RequestRetryPolicy.DISABLED;
        private volatile AttemptListener attemptListener = AttemptListener.NO_OP;
        /** 一旦改用非流式，本标志保持为 true，任何后续尝试都不再切回流式。 */
        private volatile boolean usedFallback;
        /** 本轮已提交的发送次数（含首次）。 */
        private volatile int dispatchCount;
        /** 上一次尝试是否因 429 被限流，用于重试等待文案。 */
        private volatile boolean rateLimited;
        /** 本轮最近一次提交的尝试是否为流式，用于区分回退与已回退后的状态码重试。 */
        private volatile boolean streamUsed = true;

        private Session(FrozenRequest frozen, SiteProtocol protocol,
                        ProtocolStreamRequestListener listener, Duration timeout) {
            this.frozen = frozen;
            this.protocol = protocol;
            this.listener = listener;
            this.timeout = timeout;
        }

        /** 已消耗的状态码重试次数。 */
        public int statusRetriesUsed() {
            return statusRetries.get();
        }

        public int dispatchCount() {
            return dispatchCount;
        }

        public boolean usedFallback() {
            return usedFallback;
        }

        private void start() {
            result.whenComplete((response, failure) -> {
                if (result.isCancelled()) {
                    cancel();
                }
            });
            if (isFinished()) {
                return;
            }
            emit(StreamEventType.STARTED, "");
            attempt(true);
        }

        /** 提交一次尝试：先经过发送闸门（重试等待 + 站点冷却 + 积压间隔），再真正发出。 */
        private void attempt(boolean streaming) {
            if (isFinished()) {
                return;
            }
            byte[] body;
            try {
                body = streaming
                        ? addStreamFields(protocol, frozen.body())
                        : removeStreamFields(protocol, frozen.body());
            } catch (ProtocolException e) {
                finish(null, e);
                return;
            }
            dispatch(rebuild(body), streaming);
        }

        private void dispatch(HttpRequest request, boolean streaming) {
            if (isFinished()) {
                return;
            }
            int ordinal = dispatchCount;
            boolean firstAttempt = ordinal == 0;
            // 与状态码重试区分：仅流式中断后的首次非流式重发才是回退。
            boolean fallback = !firstAttempt && !streaming && !snapshotStreaming();
            if (gate == null || !policy.enabled()) {
                // 未接入站点调度、或本轮关闭重试：保留 #8 原行为。
                beginDispatch(streaming);
                attemptListener.onSendStart(firstAttempt);
                exchange(request, streaming);
                return;
            }
            long wait = firstAttempt ? 0L : policy.retryDelayNanos();
            if (wait > 0L) {
                notifyScheduled(AttemptListener.Stage.RETRY_WAIT, firstAttempt, ordinal, rateLimited, fallback);
            }
            SendGate.Handle handle = gate.submit(siteId, wait, new SendGate.Send() {
                @Override
                public void onGranted() {
                    if (isFinished()) {
                        return;
                    }
                    beginDispatch(streaming);
                    attemptListener.onSendStart(firstAttempt);
                    exchange(request, streaming);
                }

                @Override
                public void onQueued() {
                    if (isFinished()) {
                        return;
                    }
                    notifyScheduled(AttemptListener.Stage.SITE_QUEUE, firstAttempt, ordinal, false, fallback);
                }

                @Override
                public void onDiscarded() {
                    // 服务端停止：按取消语义收口，不触发业务失败回调。
                    cancel();
                }
            });
            queueHandles.add(handle);
        }

        private void beginDispatch(boolean streaming) {
            dispatchCount++;
            rateLimited = false;
            streamUsed = streaming;
        }

        /** 本轮最近一次提交的尝试是否为流式：用于区分回退与已经回退后的状态码重试。 */
        private boolean snapshotStreaming() {
            return streamUsed;
        }

        private void notifyScheduled(AttemptListener.Stage stage, boolean firstAttempt, int ordinal,
                                     boolean limited, boolean fallback) {
            try {
                attemptListener.onScheduled(stage, firstAttempt, ordinal, limited, fallback);
            } catch (Throwable throwable) {
                LOGGER.warn("请求等待通知抛出异常（已隔离）", throwable);
            }
        }

        /** 每次尝试独占连接、响应流和计时器；旧尝试不能关闭后续尝试的资源。 */
        private void exchange(HttpRequest request, boolean streaming) {
            if (isFinished()) {
                return;
            }
            Attempt current = new Attempt();
            current.request = request;
            current.retryOnTransportFailure = streaming;
            activeAttempt.set(current);
            long deadline = System.nanoTime() + attemptTimeout().toNanos();
            try {
                synchronized (current) {
                    if (current.finished) {
                        return;
                    }
                    current.send = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
                    // 即便取消先于响应头到达，迟到的响应流也必须释放。
                    current.send.whenComplete((head, error) -> {
                        if (head != null) {
                            current.registerStream(head.body());
                        }
                    });
                    current.guard = DEADLINE.schedule(() -> {
                        ProtocolException failure = new ProtocolException(
                                "LLM request phase exceeded deadline " + attemptTimeout());
                        current.finish(null, failure);
                    }, Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            } catch (Throwable error) {
                current.finish(null, error);
                return;
            }
            READER.execute(() -> run(current, streaming, deadline));
        }

        private void run(Attempt current, boolean streaming, long deadline) {
            boolean streamPhase = streaming;
            try {
                HttpResponse<InputStream> head = current.send.join();
                current.registerStream(head.body());
                current.ensureActive();
                int status = head.statusCode();
                if (!isSuccessful(status)) {
                    current.retryOnTransportFailure = false;
                    String errorBody = readAll(head.body(), deadline, current);
                    onErrorStatus(current, streaming, head, errorBody);
                    return;
                }
                ContentKind kind = streaming ? contentKind(head.headers()) : ContentKind.JSON;
                streamPhase = streaming;
                String raw = kind == ContentKind.JSON
                        ? readAll(head.body(), deadline, current)
                        : readStream(head.body(), deadline, current);
                current.ensureActive();
                JsonObject aggregated = JsonParser.parseString(raw).getAsJsonObject();
                // 三协议都必须完整解码成功后才交给 TLM；不能把聚合错误留到重试范围之外。
                ProtocolResponseDecoder.decode(protocol, aggregated);
                HttpResponse<String> response = HttpStringResponse.of(head, raw);
                current.finish(response, null);
                finish(response, null);
            } catch (Throwable throwable) {
                Throwable cause = unwrap(throwable);
                current.finish(null, cause);
                if (streamPhase) {
                    // 流式阶段断开、超时或缺终止标志：唯一允许触发非流式回退的传输类失败。
                    fallback(cause);
                } else {
                    finish(null, cause);
                }
            }
        }

        /** HTTP 错误状态码统一出口：限流冷却 → 状态码重试 → 非流式回退 → 原样交回。 */
        private void onErrorStatus(Attempt current, boolean streaming,
                                   HttpResponse<InputStream> head, String errorBody) {
            int status = head.statusCode();
            if (status == 429) {
                rateLimited = true;
                if (gate != null && policy.enabled()) {
                    // 站点冷却：推迟该站点尚未发出的请求；不取消已经发出的请求。
                    gate.throttle(siteId, policy.retryDelay());
                }
            }
            current.finish(HttpStringResponse.of(head, errorBody), null);
            if (isFinished()) {
                return;
            }
            if (streaming && shouldRetryWithoutStream(status, errorBody)) {
                fallback(new StreamUnsupportedException(status, errorBody));
                return;
            }
            if (policy.enabled() && isRetryableStatus(status)) {
                reserveRetry();
                if (!isBudgetExpired() && statusRetries.get() <= policy.maxRetries()) {
                    // 保持当前模式重试：已经回退过就一直是非流式。
                    attempt(streaming);
                    return;
                }
            }
            finish(HttpStringResponse.of(head, errorBody, truncate(errorBody)), null);
        }
        /** 流式故障出口：额度允许时改用非流式重发一次，否则上报失败。 */
        private void fallback(Throwable cause) {
            if (isFinished()) {
                return;
            }
            if (!usedFallback) {
                usedFallback = true;
                emit(StreamEventType.FALLBACK, "");
                attempt(false);
                return;
            }
            finish(null, cause);
        }

        private void cancel() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelled.set(true);
            Attempt current = activeAttempt.getAndSet(null);
            if (current != null) {
                current.finish(null, new CancelledException());
            }
            releaseQueueHandles();
            result.completeExceptionally(new CancelledException());
            emit(StreamEventType.CANCELLED, "");
        }

        private void releaseQueueHandles() {
            for (SendGate.Handle handle : queueHandles) {
                handle.cancel();
            }
            queueHandles.clear();
        }

        /**
         * 预留一次状态码重试额度。
         * <p>
         * 在等待开始时预留，因此“取消排队中的重试”不会白白消耗一次额度，
         * 也不会出现同一次尝试被计两次的情况。
         */
        private void reserveRetry() {
            if (retryReserved.compareAndSet(false, true)) {
                statusRetries.incrementAndGet();
                retryReserved.set(false);
            }
        }

        private void finish(HttpResponse<String> response, Throwable failure) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            releaseQueueHandles();
            if (failure != null) {
                result.completeExceptionally(failure);
                fail(failure);
                return;
            }
            if (response != null && isSuccessful(response.statusCode())) {
                // 整轮（含全部重试与回退）确定成功后才提交，部分响应不会进入统计或历史。
                notifyAggregated(JsonParser.parseString(response.body()).getAsJsonObject());
            }
            result.complete(response);
            emitTerminal(response);
        }

        private boolean isFinished() {
            return cancelled.get() || terminal.get();
        }

        /** 单次尝试超时：不得超过整轮剩余时间。 */
        private Duration attemptTimeout() {
            ConversationBudget current = budget;
            if (current == null) {
                return timeout;
            }
            long remaining = current.deadlineNanos() - System.nanoTime();
            if (remaining <= 0L) {
                return Duration.ofNanos(1L);
            }
            Duration remainingDuration = Duration.ofNanos(remaining);
            return remainingDuration.compareTo(timeout) < 0 ? remainingDuration : timeout;
        }

        private boolean isBudgetExpired() {
            ConversationBudget current = budget;
            if (current == null) {
                return false;
            }
            if (System.nanoTime() >= current.deadlineNanos()) {
                current.markExpired();
                return true;
            }
            return false;
        }

        /** 消费 SSE：终止标志到达即停止读取（keep-alive 连接不必等 EOF）。 */
        private String readStream(InputStream stream, long deadline, Attempt current) throws Exception {
            ProtocolStreamRequestListener guardedListener = new ProtocolStreamRequestListener() {
                @Override
                public void onEvent(String id, StreamEventType type, String text) {
                    synchronized (current) {
                        current.ensureActive();
                        emit(type, text);
                    }
                }
            };
            ProtocolStreamAggregator aggregator = newAggregator(protocol, guardedListener, requestId);
            SseDecoder decoder = new SseDecoder();
            try (stream) {
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                while (true) {
                    ensureWithinDeadline(deadline);
                    int read = stream.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    for (SseEvent event : decoder.feed(buffer, 0, read)) {
                        current.ensureActive();
                        aggregator.accept(event.data());
                        if (aggregator.complete()) {
                            break;
                        }
                    }
                    if (aggregator.complete()) {
                        break;
                    }
                }
                if (!aggregator.complete()) {
                    for (SseEvent event : decoder.finish()) {
                        current.ensureActive();
                        aggregator.accept(event.data());
                    }
                }
                return aggregator.finish().toString();
            }
        }

        private String readAll(InputStream stream, long deadline, Attempt current) throws Exception {
            try (stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                while (true) {
                    ensureWithinDeadline(deadline);
                    int read = stream.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    current.ensureActive();
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }

        private HttpRequest rebuild(byte[] body) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(frozen.request().uri());
            builder.method(frozen.request().method(), HttpRequest.BodyPublishers.ofByteArray(body));
            frozen.request().version().ifPresent(builder::version);
            builder.expectContinue(frozen.request().expectContinue());
            frozen.request().timeout().ifPresent(builder::timeout);
            frozen.request().headers().map().forEach((name, values) -> {
                for (String value : values) {
                    builder.header(name, value);
                }
            });
            return builder.build();
        }

        private void emit(StreamEventType type, String text) {
            if (listener == null) {
                return;
            }
            try {
                listener.onEvent(requestId, type, text);
            } catch (Throwable throwable) {
                LOGGER.warn("流式增量监听器抛出异常（已隔离）", throwable);
            }
        }

        private void notifyAggregated(JsonObject aggregated) {
            if (listener == null) {
                return;
            }
            try {
                listener.onAggregated(requestId, protocol, aggregated);
            } catch (Throwable throwable) {
                LOGGER.warn("流式聚合监听器抛出异常（已隔离）", throwable);
            }
        }

        private void fail(Throwable throwable) {
            emit(StreamEventType.FAILED, throwable == null ? "" : throwable.getMessage());
        }

        private void complete() {
            emit(StreamEventType.COMPLETED, "");
        }

        /** 终态事件只发一次：2xx 成功记为 COMPLETED，其余（含透传的 HTTP 错误）记为 FAILED。 */
        private void emitTerminal(HttpResponse<String> response) {
            if (response != null && isSuccessful(response.statusCode())) {
                complete();
            } else {
                fail(null);
            }
        }

        /** 尝试级资源所有权；终结必须先关闭本次资源，再完成 future。 */
        private final class Attempt {
            private final CompletableFuture<HttpResponse<String>> future = new CompletableFuture<>();
            private CompletableFuture<HttpResponse<InputStream>> send;
            private InputStream stream;
            private ScheduledFuture<?> guard;
            private HttpRequest request;
            private boolean finished;
            private volatile boolean retryOnTransportFailure;

            synchronized void ensureActive() {
                if (finished || cancelled.get()) {
                    throw new CancelledException();
                }
            }

            synchronized void registerStream(InputStream body) {
                if (finished || cancelled.get()) {
                    closeQuietly(body);
                } else {
                    stream = body;
                }
            }

            void finish(HttpResponse<String> response, Throwable failure) {
                synchronized (this) {
                    if (finished) {
                        return;
                    }
                    finished = true;
                    if (guard != null) {
                        guard.cancel(false);
                    }
                    if (send != null && !send.isDone()) {
                        send.cancel(true);
                    }
                    closeQuietly(stream);
                    stream = null;
                }
                activeAttempt.compareAndSet(this, null);
                if (failure == null) {
                    future.complete(response);
                } else {
                    future.completeExceptionally(failure);
                }
            }
        }
    }

    private static ProtocolStreamAggregator newAggregator(SiteProtocol protocol,
                                                          ProtocolStreamRequestListener listener,
                                                          String requestId) {
        StreamEventListener bridge = listener == null ? null
                : event -> {
                    // 只读增量监听者的异常必须隔离，不能中断聚合或触发回退。
                    try {
                        listener.onEvent(requestId, event.type(), event.text());
                    } catch (Throwable throwable) {
                        LOGGER.warn("流式增量监听器抛出异常（已隔离）", throwable);
                    }
                };
        return switch (protocol) {
            case CHAT_COMPLETIONS -> new ChatCompletionsStreamAggregator(bridge);
            case RESPONSES -> new ResponsesStreamAggregator(bridge);
            case ANTHROPIC_MESSAGES -> new AnthropicStreamAggregator(bridge);
        };
    }

    enum ContentKind {
        SSE,
        JSON
    }

    static ContentKind contentKind(HttpHeaders headers) {
        String contentType = headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("text/event-stream")) {
            return ContentKind.SSE;
        }
        if (contentType.contains("application/json") || contentType.contains("+json")) {
            return ContentKind.JSON;
        }
        // 未声明或未知类型时按 SSE 处理，保持既有行为。
        return ContentKind.SSE;
    }

    /** 允许做一次非流式回退之外，指定可重试的 HTTP 状态码。 */
    static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    /**
     * 仅当服务端明确表示 stream/stream_options 不受支持时才允许非流式回退。
     * 鉴权、限流、普通参数错误与服务端错误均原样透传。
     */
    static boolean shouldRetryWithoutStream(int status, String body) {
        if (status == 401 || status == 403 || status == 404 || status == 429) {
            return false;
        }
        if (status != 400 && status != 415 && status != 422) {
            return false;
        }
        String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return text.matches("(?s).*\\bstream(?:ing|_options)?\\b.*")
                && (text.contains("not support") || text.contains("unsupported")
                || text.contains("unknown") || text.contains("unrecognized") || text.contains("invalid")
                || text.contains("cannot") || text.contains("does not support"));
    }

    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_ERROR_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_ERROR_BODY_CHARS);
    }

    private static void ensureWithinDeadline(long deadline) throws IOException {
        if (System.nanoTime() - deadline >= 0) {
            throw new IOException("Streaming read deadline exceeded");
        }
    }

    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    static byte[] addStreamFields(SiteProtocol protocol, byte[] body) throws ProtocolException {
        JsonObject json = parseBody(body);
        json.addProperty("stream", true);
        if (protocol == SiteProtocol.CHAT_COMPLETIONS) {
            JsonObject options = json.has("stream_options") && json.get("stream_options").isJsonObject()
                    ? json.getAsJsonObject("stream_options") : new JsonObject();
            options.addProperty("include_usage", true);
            json.add("stream_options", options);
        }
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] removeStreamFields(SiteProtocol protocol, byte[] body) throws ProtocolException {
        JsonObject json = parseBody(body);
        json.addProperty("stream", false);
        if (protocol == SiteProtocol.CHAT_COMPLETIONS) {
            json.remove("stream_options");
        }
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject parseBody(byte[] body) throws ProtocolException {
        try {
            JsonElement element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new ProtocolException("Encoded request body is not a JSON object");
            }
            return element.getAsJsonObject();
        } catch (ProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProtocolException("Encoded request body is not valid JSON", e);
        }
    }

    /**
     * 归一化各层 future 包装出的异常。
     * <p>
     * 取消在内部以 {@link CancellationException} 形式从被中断的 future 冒泡（如截止时间到期时
     * 作废 {@code sendAsync}），但对外必须表现为普通失败：直接暴露取消异常会让调用方误以为
     * 是自己取消了 future，上层的失败回调也会拿到一个没有语义的 cause。
     */
    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CancellationException) {
                return new CancelledException();
            }
            if ((current instanceof CompletionException || current instanceof ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            return current;
        }
        return throwable;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭失败无需上报
        }
    }

    public static final class CancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class StreamUnsupportedException extends RuntimeException {
        private StreamUnsupportedException(int status, String body) {
            super("stream unsupported: HTTP " + status + " " + body);
        }
    }
}

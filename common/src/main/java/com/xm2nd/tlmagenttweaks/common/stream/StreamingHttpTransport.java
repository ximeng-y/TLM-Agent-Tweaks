package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolResponseDecoder;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 纯 JDK 流式 HTTP 状态机：流式优先、断流非流式回退、指定 HTTP 状态码重试。
 * <p>
 * 本类只承载状态转换与资源归属（{@link Session}、{@code Attempt}、{@link Call}）；
 * 请求体编解码在 {@link StreamBodyCodec}，正文读取与聚合器装配在 {@link StreamBodyReader}，
 * 尝试判定规则在 {@link HttpRetryRules}，共享线程设施在 {@link TransportExecutors}。
 * <p>
 * 一个 {@link Session} 对应一次逻辑模型请求，其内部至多发生
 * {@code 1 + 1 + maxRetries + 1} 次实际 HTTP 尝试，三条路径各自计数、互不占用对方额度：
 * <ul>
 *   <li><b>传输重试</b>：流式请求在**收到响应头之前**失败（连接刚建立就断开、网关闪断、
 *       单次尝试超时）时，用同一份冻结请求体保持 {@code stream:true} 重发一次。
 *       这条路径受 {@code retry.enabled} 控制，关闭时不重发、直接落到非流式回退；</li>
 *   <li><b>状态码重试</b>：429 / 500 / 502 / 503 / 504，最多 {@code maxRetries} 次，
 *       每次固定等待 {@code retryDelay}（忽略 {@code Retry-After}，不做指数退避与抖动）；</li>
 *   <li><b>非流式回退</b>：服务端明确不支持 {@code stream}/{@code stream_options}、
 *       流式读取中途失败（已收到响应头、SSE 中断/超时/缺终止标志、分片聚合失败），
 *       或响应头前失败且传输重试额度已用尽时，发生一次；此后所有尝试都保持 {@code stream:false}；</li>
 *   <li>整轮预算已耗尽时直接失败，不再消耗任何额度；状态码重试额度耗尽后，最后一次的错误
 *       响应（状态码 + 截断后的正文）原样交回调用方。</li>
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
        session.siteId = siteId == null ? SiteThrottleScheduler.DEFAULT_SITE : siteId;
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

        /** 所属状态机（测试断言诊断状态用）。 */
        public Session session() {
            return session;
        }
    }

    /** 尝试时序的只读观察者：让上层同步等待气泡与站点排队状态。 */
    public interface AttemptListener {
        AttemptListener NO_OP = new AttemptListener() {
            @Override
            public void onScheduled(Stage stage, boolean firstAttempt, int retryOrdinal,
                                    boolean rateLimited, Cause cause) {
            }

            @Override
            public void onSendStart(boolean firstAttempt, boolean fallback) {
            }
        };

        /**
         * 本次尝试进入等待。
         *
         * @param stage        等待类型
         * @param firstAttempt 是否为整轮请求的第一次发送（首次发送不等待）
         * @param retryOrdinal 已消耗的状态码重试次数（1 起；站点排队场景为 0）
         * @param rateLimited  上一次尝试是否因 429 被限流（状态而不是起因，不能并入 {@code cause}）
         * @param cause        本次尝试的起因；首次发送为 {@code null}（此时只可能出现站点排队提示）。
         *                     关闭重试时的静默回退不走本方法，因此不会在这里产生任何提示
         */
        void onScheduled(Stage stage, boolean firstAttempt, int retryOrdinal, boolean rateLimited,
                         Cause cause);

        /**
         * 等待结束，即将真正发出。
         *
         * @param firstAttempt 是否为整轮请求的第一次发送
         * @param fallback     本次真正发出的是否为非流式回退（其余尝试都是流式）
         */
        void onSendStart(boolean firstAttempt, boolean fallback);

        enum Stage {
            /** 本次请求自身的固定重试等待。 */
            RETRY_WAIT,
            /** 站点冷却或同站点发送间隔导致的排队。 */
            SITE_QUEUE
        }

        /** 本次尝试的起因：区分提示文案，也决定展示层如何处理思考链。 */
        enum Cause {
            /** 状态码重试（429 / 5xx）：保持当前模式重发。 */
            RETRY,
            /** 响应头前失败：保持流式重发。 */
            TRANSPORT_RETRY,
            /** 流式读取中途失败：改用非流式重发。 */
            FALLBACK
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
        private final List<SiteThrottleScheduler.Handle> queueHandles = new CopyOnWriteArrayList<>();
        private final AtomicInteger statusRetries = new AtomicInteger();
        /** 仅用于并发决策去重：立即复位，不代表存在跨越等待期的持续预留。 */
        private final AtomicBoolean retryReserved = new AtomicBoolean();
        /** 响应头前断连的流式重发是否已用掉；单点写入，但取消与状态码分支也会读。 */
        private final AtomicBoolean transportRetried = new AtomicBoolean();

        private volatile HttpClient client;
        private volatile String siteId = SiteThrottleScheduler.DEFAULT_SITE;
        private volatile ConversationBudget budget;
        private volatile RequestRetryPolicy policy = RequestRetryPolicy.DISABLED;
        private volatile AttemptListener attemptListener = AttemptListener.NO_OP;
        /** 一旦改用非流式，本标志保持为 true，任何后续尝试都不再切回流式。 */
        private volatile boolean usedFallback;
        /** 本轮已提交的发送次数（含首次）。 */
        private volatile int dispatchCount;
        /** 上一次尝试是否因 429 被限流，用于重试等待文案。 */
        private volatile boolean rateLimited;

        private Session(FrozenRequest frozen, SiteProtocol protocol,
                        ProtocolStreamRequestListener listener, Duration timeout) {
            this.frozen = frozen;
            this.protocol = protocol;
            this.listener = listener;
            this.timeout = timeout;
        }

        /** 本轮是否已改用非流式回退（测试断言用）。 */
        public boolean usedFallback() {
            return usedFallback;
        }

        /** 响应头前断连的流式重发是否已用掉（测试断言用）。 */
        public boolean transportRetried() {
            return transportRetried.get();
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
            attempt(true, null);
        }

        /** 提交一次尝试：先经过发送闸门（重试等待 + 站点冷却 + 同站点发送间隔），再真正发出。 */
        private void attempt(boolean streaming, AttemptListener.Cause cause) {
            if (isFinished()) {
                return;
            }
            byte[] body;
            try {
                body = streaming
                        ? StreamBodyCodec.addStreamFields(protocol, frozen.body())
                        : StreamBodyCodec.removeStreamFields(protocol, frozen.body());
            } catch (ProtocolException e) {
                finish(null, e);
                return;
            }
            dispatch(rebuild(body), streaming, cause);
        }

        private void dispatch(HttpRequest request, boolean streaming, AttemptListener.Cause cause) {
            if (isFinished()) {
                return;
            }
            int ordinal = statusRetries.get();
            boolean firstAttempt = dispatchCount == 0;
            if (gate == null || !policy.enabled()) {
                // 未接入站点调度、或本轮关闭重试：保留 #8 原行为。
                beginDispatch();
                attemptListener.onSendStart(firstAttempt, !streaming);
                exchange(request, streaming);
                return;
            }
            long wait = firstAttempt ? 0L : policy.retryDelayNanos();
            if (wait > 0L) {
                notifyScheduled(AttemptListener.Stage.RETRY_WAIT, firstAttempt, ordinal, rateLimited, cause);
            }
            SiteThrottleScheduler.Handle handle = gate.submit(siteId, wait, new SiteThrottleScheduler.Send() {
                @Override
                public void onGranted() {
                    if (isFinished()) {
                        return;
                    }
                    beginDispatch();
                    attemptListener.onSendStart(firstAttempt, !streaming);
                    exchange(request, streaming);
                }

                @Override
                public void onQueued() {
                    if (isFinished()) {
                        return;
                    }
                    notifyScheduled(AttemptListener.Stage.SITE_QUEUE, firstAttempt, ordinal, false, cause);
                }

                @Override
                public void onDiscarded() {
                    // 服务端停止：按取消语义收口，不触发业务失败回调。
                    cancel();
                }
            });
            queueHandles.add(handle);
        }

        private void beginDispatch() {
            dispatchCount++;
            rateLimited = false;
        }

        private void notifyScheduled(AttemptListener.Stage stage, boolean firstAttempt, int ordinal,
                                     boolean limited, AttemptListener.Cause cause) {
            try {
                attemptListener.onScheduled(stage, firstAttempt, ordinal, limited, cause);
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
                    // 截止计时器是在途尝试的超时出口：它只把超时记在本次尝试上，
                    // 不在这里判成败——真正的收口统一发生在读取线程捕获到失败之后。
                    current.guard = TransportExecutors.DEADLINE.schedule(() -> {
                        ProtocolException timeout = new ProtocolException(
                                "LLM request phase exceeded deadline " + attemptTimeout());
                        synchronized (current) {
                            if (current.finished) {
                                return;
                            }
                            current.timedOut = true;
                        }
                        current.abort(timeout);
                    }, Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            } catch (Throwable error) {
                current.finish(null, error);
                return;
            }
            TransportExecutors.READER.execute(() -> run(current, streaming, deadline));
        }

        private void run(Attempt current, boolean streaming, long deadline) {
            // 两个事实互相独立，不能用一个布尔量代替：
            //   bodyPhase 已收到响应头——此前的失败走传输重发，之后的失败走非流式回退；
            //   bodyRead  正文已完整取回——此后的失败只剩解码，属于协议问题，不重发也不回退。
            // 「完整取回」不含零字节正文：那种情况下面根本没有可解码的协议内容。
            // 判据刻意不看响应头声明的内容类型：流式端点返回 application/json 时正文同样可能
            // 读到一半卡死，那是传输失败，与「完整取回但解码失败」是两回事。
            boolean bodyPhase = false;
            boolean bodyRead = false;
            StreamBodyReader.AttemptGuard guard = new StreamBodyReader.AttemptGuard() {
                @Override
                public void ensureActive() {
                    current.ensureActive();
                }

                @Override
                public void onEvent(StreamEventType type, String text) {
                    synchronized (current) {
                        current.ensureActive();
                        emit(type, text);
                    }
                }
            };
            try {
                HttpResponse<InputStream> head = current.send.join();
                bodyPhase = true;
                current.registerStream(head.body());
                current.ensureActive();
                int status = head.statusCode();
                if (!HttpRetryRules.isSuccessful(status)) {
                    // 错误正文只用于提示、日志与回退判定，读取有硬上限；成功正文仍须完整。
                    String errorBody = StreamBodyReader.readAll(head.body(), deadline, guard,
                            HttpRetryRules.MAX_ERROR_BODY_CHARS);
                    onErrorStatus(current, streaming, head, errorBody);
                    return;
                }
                StreamBodyReader.ContentKind kind = streaming
                        ? StreamBodyReader.contentKind(head.headers())
                        : StreamBodyReader.ContentKind.JSON;
                String raw = kind == StreamBodyReader.ContentKind.JSON
                        ? StreamBodyReader.readAll(head.body(), deadline, guard)
                        : StreamBodyReader.readStream(head.body(), deadline, protocol, guard);
                if (kind == StreamBodyReader.ContentKind.JSON && raw.isEmpty()) {
                    // 零字节正文不能算完整取回：Content-Length 缺失或被代理改写成 0、网关在
                    // 上游失败时回 200 空体都会走到这里，与「正文读到一半断开」是同一类失败。
                    throw new IOException("Response headers received but body is empty");
                }
                bodyRead = true;
                current.ensureActive();
                JsonObject aggregated = JsonParser.parseString(raw).getAsJsonObject();
                // 三协议都必须完整解码成功后才交给 TLM；不能把聚合错误留到重试范围之外。
                ProtocolResponseDecoder.decode(protocol, aggregated);
                HttpResponse<String> response = HttpStringResponse.of(head, raw);
                current.finish(response, null);
                finish(response, null);
            } catch (Throwable throwable) {
                Throwable cause = current.reason(unwrap(throwable));
                current.finish(null, cause);
                if (bodyPhase && !bodyRead && !(cause instanceof StreamServiceException)) {
                    // 响应头已到、正文没读完：连接中断、读 body 超时、缺少终止标志或分片聚合
                    // 失败，都是传输类失败，允许一次非流式回退。
                    fallback(AttemptListener.Cause.FALLBACK, cause);
                } else if (isPreHeaderTransportFailure(current, cause, streaming, bodyPhase)) {
                    retryStreamingTransport(cause);
                } else {
                    finish(null, cause);
                }
            }
        }

        /**
         * 是否为「响应头之前就失败的传输失败」：流式请求连响应头都没拿到就失败。
         * <p>
         * 判定不依赖异常类型，而是看**本次尝试自身**记录的事实：
         * <ul>
         *   <li>是否已经拿到响应头——没有才是响应头前失败；</li>
         *   <li>{@code Attempt.timedOut}——截止计时器作废在途尝试时，会取消 {@code sendAsync}，
         *       {@code send.join()} 随之抛出没有语义的 {@link CancellationException}，
         *       原始超时原因被顶替。只看异常类型会把最常见的一种情形（服务端在响应头前挂起
         *       直到超时）误判成「调用方取消」而放弃重发；</li>
         *   <li>是否由本方法之外主动取消——调用方取消与资源释放走 {@link #cancel()}，
         *       它们不是失败，不许重发。</li>
         * </ul>
         * 这张表必须按此顺序判定：截止计时器会用取消来终止连接，因此超时必然伴随
         * {@link CancelledException}，超时标记必须先于取消判定生效。
         */
        private boolean isPreHeaderTransportFailure(Attempt current, Throwable cause, boolean streaming,
                                                    boolean bodyPhase) {
            if (!streaming || bodyPhase || cause instanceof StreamServiceException) {
                return false;
            }
            if (current.timedOut) {
                return true;
            }
            return !(cause instanceof CancelledException) && !cancelled.get() && !terminal.get();
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
            if (streaming && HttpRetryRules.shouldRetryWithoutStream(status, errorBody)) {
                fallback(AttemptListener.Cause.FALLBACK, new StreamUnsupportedException(status, errorBody));
                return;
            }
            if (policy.enabled() && HttpRetryRules.isRetryableStatus(status)) {
                reserveRetry();
                if (!isBudgetExpired() && statusRetries.get() <= policy.maxRetries()) {
                    // 保持当前模式重试：已经回退过就一直是非流式。
                    attempt(streaming, AttemptListener.Cause.RETRY);
                    return;
                }
            }
            finish(HttpStringResponse.of(head, errorBody, HttpRetryRules.truncate(errorBody)), null);
        }

        /**
         * 响应头前失败出口：额度允许时保持流式重发一次，否则改用非流式回退。
         * <p>
         * 额度用尽后仍然回退一次：已建立连接、服务端只是不对流式请求回响应
         * （流式端点挂起、网关对 SSE 挂起）同样落在本路径，此时普通请求常常是通的，
         * 回退能拿回一个成功响应；只有真正连不上（连接被拒、RST）时那次回退才纯属浪费，
         * 代价与实际收益相抵。
         * <p>
         * 关闭重试时保持 #8 行为：没有等待、没有提示，静默改用非流式重发一次
         * （重发时机依赖站点闸门，闸门关闭后无处等待，故不引入新的气泡文案）。
         */
        private void retryStreamingTransport(Throwable cause) {
            if (isFinished()) {
                return;
            }
            if (isBudgetExpired()) {
                // 整轮预算已耗尽：再发一次只会立刻超时，直接按失败收口。
                finish(null, cause);
                return;
            }
            if (!policy.enabled()) {
                fallback(null, cause);
                return;
            }
            if (transportRetried.compareAndSet(false, true)) {
                // 必须经由 attempt(...) 才能复用站点闸门：等待、冷却与排队提示都在那里。
                attempt(true, AttemptListener.Cause.TRANSPORT_RETRY);
                return;
            }
            fallback(AttemptListener.Cause.FALLBACK, cause);
        }

        /**
         * 流式故障出口：额度允许时改用非流式重发一次，否则上报失败。
         *
         * @param cause 本次重发的起因；{@code null} 表示静默重发（关闭重试时）不作任何提示
         */
        private void fallback(AttemptListener.Cause cause, Throwable failure) {
            if (isFinished()) {
                return;
            }
            if (!usedFallback) {
                usedFallback = true;
                emit(StreamEventType.FALLBACK, "");
                attempt(false, cause);
                return;
            }
            finish(null, failure);
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
            for (SiteThrottleScheduler.Handle handle : queueHandles) {
                handle.cancel();
            }
            queueHandles.clear();
        }

        /**
         * 状态码重试额度计数：在决定重试的当下立即计入 {@code statusRetries}，
         * 其后的等待、排队与取消不再回退该计数（取消排队中的重试不返还额度）。
         * <p>
         * {@code retryReserved} 只保证同一时刻的并发决策至多计一次，
         * 立即复位，不构成持续预留。
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
            if (response != null && HttpRetryRules.isSuccessful(response.statusCode())) {
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
            if (response != null && HttpRetryRules.isSuccessful(response.statusCode())) {
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
            private boolean finished;
            /** 本次尝试是否被截止计时器作废：判定响应头前失败时不能只看异常类型，见其调用点。 */
            private volatile boolean timedOut;
            /** 截止计时器给出的原始失败原因；作废以取消连接的方式终止，原因必须显式留存。 */
            private Throwable failure;

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

            /**
             * 作废在途尝试但**不**宣布终态：截止计时器让读取线程自己去收口，
             * 因此延迟的读取结果不会丢失，失败原因也仍由读取线程唯一地决定。
             */
            synchronized void abort(Throwable cause) {
                if (finished) {
                    return;
                }
                failure = cause;
                if (guard != null) {
                    guard.cancel(false);
                }
                closeQuietly(stream);
                stream = null;
                if (send != null && !send.isDone()) {
                    send.cancel(true);
                }
            }

            /** 对外可见的失败原因：截止计时器留存的原因优先于被顶替的取消异常。 */
            synchronized Throwable reason(Throwable fallbackCause) {
                return failure != null ? failure : fallbackCause;
            }

            void finish(HttpResponse<String> response, Throwable cause) {
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
                if (cause == null) {
                    future.complete(response);
                } else {
                    future.completeExceptionally(cause);
                }
            }
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

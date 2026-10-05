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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 纯 JDK 流式 HTTP 状态机。
 * <p>
 * 首次请求优先流式；只有两种情况才用同一份冻结请求体做一次非流式重试（至多两次
 * HTTP）：服务端明确表示不支持 {@code stream}/{@code stream_options}，或流式读取
 * 中途失败（断流、超时、缺终止标志、聚合失败）。401/403/404/429 与普通
 * 400/422/5xx 原样透传，不盲目重试。响应体读取使用可关闭的
 * {@link InputStream}，并同时施加“接收响应头”与“读完响应体”的硬截止时间。
 * <p>
 * 流式成功且服务端返回 JSON Content-Type 时可直接按协议解码，无需等待或重试。
 * 协议终止标志（[DONE]/response.completed/message_stop）到达后立即收口，不等待
 * keep-alive 连接的 EOF。每个 attempt 有独立有效性标记，超时/取消后的旧 reader
 * 不会污染回退路径或提交元数据。
 */
public final class StreamingHttpTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingHttpTransport.class);
    private static final Duration DEFAULT_PHASE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_PHASE_TIMEOUT = Duration.ofSeconds(90);
    private static final int READ_BUFFER_BYTES = 8192;

    private static final ExecutorService READER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tlm-stream-reader");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 读 body 硬截止计时器：JDK {@code HttpRequest.timeout} 只覆盖“收到响应头”，
     * 无法中断已获得的 {@link InputStream} 阻塞读取，因此必须由独立计时器强制
     * 关闭流并让本次 attempt 作废。
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

    public StreamingHttpTransport() {
        this(null);
    }

    public StreamingHttpTransport(Duration phaseTimeout) {
        this.phaseTimeout = phaseTimeout;
    }

    public static FrozenRequest freeze(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow(
                () -> new IllegalArgumentException("Streaming request must have a body"));
        return new FrozenRequest(request, readPublisher(publisher));
    }

    public CompletableFuture<HttpResponse<String>> send(HttpClient client, FrozenRequest frozen,
                                                        SiteProtocol protocol,
                                                        ProtocolStreamRequestListener listener) {
        return sendCall(client, frozen, protocol, listener).future();
    }

    public Call sendCall(HttpClient client, FrozenRequest frozen, SiteProtocol protocol,
                         ProtocolStreamRequestListener listener) {
        Session session = new Session(frozen, protocol, listener, resolveTimeout(frozen.request()));
        return new Call(session.start(client), session::cancel);
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

    public static final class Call {
        private final CompletableFuture<HttpResponse<String>> future;
        private final Runnable cancel;

        private Call(CompletableFuture<HttpResponse<String>> future, Runnable cancel) {
            this.future = future;
            this.cancel = cancel;
        }

        public CompletableFuture<HttpResponse<String>> future() {
            return future;
        }

        public void cancel() {
            cancel.run();
        }
    }

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

    private final class Session {
        private final FrozenRequest frozen;
        private final SiteProtocol protocol;
        private final ProtocolStreamRequestListener listener;
        private final Duration timeout;
        private final String requestId = java.util.UUID.randomUUID().toString();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Attempt> activeAttempt = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final CompletableFuture<HttpResponse<String>> result = new CompletableFuture<>();
        private boolean usedFallback;

        private Session(FrozenRequest frozen, SiteProtocol protocol,
                        ProtocolStreamRequestListener listener, Duration timeout) {
            this.frozen = frozen;
            this.protocol = protocol;
            this.listener = listener;
            this.timeout = timeout;
        }

        CompletableFuture<HttpResponse<String>> start(HttpClient client) {
            emit(StreamEventType.STARTED, "");
            result.whenComplete((response, failure) -> {
                if (result.isCancelled()) {
                    cancel();
                }
            });
            attemptStream(client).whenComplete((response, throwable) -> {
                if (cancelled.get()) {
                    return;
                }
                if (throwable == null) {
                    finish(response, null);
                    return;
                }
                Throwable cause = unwrap(throwable);
                // 只有“明确不支持 stream/stream_options”或“流式读取/聚合中途失败”
                // 才允许同 payload 的非流式重试；HTTP 错误响应按原样透传。
                if (!(cause instanceof StreamUnsupportedException) && !(cause instanceof StreamBrokenException)) {
                    finish(null, cause);
                    return;
                }
                if (cancelled.get()) {
                    return;
                }
                emit(StreamEventType.FALLBACK, "");
                usedFallback = true;
                attemptFallback(client).whenComplete((fallback, fallbackThrowable) -> {
                    if (cancelled.get()) {
                        return;
                    }
                    if (fallbackThrowable != null) {
                        Throwable fallbackCause = unwrap(fallbackThrowable);
                        finish(null, fallbackCause);
                        return;
                    }
                    finish(fallback, null);
                });
            });
            return result;
        }

        private CompletableFuture<HttpResponse<String>> attemptStream(HttpClient client) {
            if (cancelled.get()) {
                return CompletableFuture.failedFuture(new ProtocolException("Streaming request cancelled"));
            }
            byte[] body;
            try {
                body = addStreamFields(protocol, frozen.body());
            } catch (ProtocolException e) {
                return CompletableFuture.failedFuture(e);
            }
            return exchange(client, rebuild(body), true);
        }

        private CompletableFuture<HttpResponse<String>> attemptFallback(HttpClient client) {
            if (cancelled.get()) {
                return CompletableFuture.failedFuture(new ProtocolException("Streaming request cancelled"));
            }
            byte[] body;
            try {
                body = removeStreamFields(protocol, frozen.body());
            } catch (ProtocolException e) {
                return CompletableFuture.failedFuture(e);
            }
            return exchange(client, rebuild(body), false);
        }

        /**
         * 每次尝试独占连接、响应流和计时器；旧尝试只能关闭自己的资源，不能关闭回退请求。
         */
        private CompletableFuture<HttpResponse<String>> exchange(HttpClient client, HttpRequest request,
                                                                 boolean streaming) {
            Attempt current = new Attempt();
            current.retryOnTransportFailure = streaming;
            activeAttempt.set(current);
            if (cancelled.get()) {
                current.finish(null, new CancelledException());
                return current.future;
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                synchronized (current) {
                    if (current.finished) {
                        return current.future;
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
                                "LLM request phase exceeded deadline " + timeout);
                        current.finish(null, current.retryOnTransportFailure
                                ? new StreamBrokenException(failure) : failure);
                    },
                            Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            } catch (Throwable error) {
                current.finish(null, error);
                return current.future;
            }
            READER.execute(() -> {
                // 在收到响应头前连接被重置，同样属于首次流式请求的传输失败。
                boolean streamPhase = streaming;
                try {
                    HttpResponse<InputStream> head = current.send.join();
                    current.registerStream(head.body());
                    current.ensureActive();
                    if (!isSuccessful(head.statusCode())) {
                        // 普通 HTTP 错误（含 401/403/404/429、参数错误、5xx）原样透传，
                        // 只有明确表示 stream 不受支持时才允许回退。
                        streamPhase = false;
                        current.retryOnTransportFailure = false;
                        String errorBody = readAll(head.body(), deadline, current);
                        if (streaming && shouldRetryWithoutStream(head.statusCode(), errorBody)) {
                            throw new StreamUnsupportedException(head.statusCode(), errorBody);
                        }
                        current.finish(HttpStringResponse.of(head, errorBody), null);
                        return;
                    }
                    ContentKind kind = streaming ? contentKind(head.headers()) : ContentKind.JSON;
                    streamPhase = streaming;
                    String raw = kind == ContentKind.JSON
                            ? readAll(head.body(), deadline, current)
                            : readStream(head.body(), deadline, current);
                    current.ensureActive();
                    JsonObject aggregated = JsonParser.parseString(raw).getAsJsonObject();
                    // 三协议都必须完整解码成功后才交给 TLM；不能把聚合错误留到回退范围之外。
                    ProtocolResponseDecoder.decode(protocol, aggregated);
                    current.finish(HttpStringResponse.of(head, raw), null);
                } catch (Throwable throwable) {
                    Throwable cause = unwrap(throwable);
                    current.finish(null, streamPhase && !(cause instanceof StreamServiceException)
                            ? new StreamBrokenException(cause) : cause);
                }
            });
            return current.future;
        }

        /**
         * 消费 SSE：终止标志到达即停止读取（keep-alive 连接不必等 EOF）。
         */
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

        void cancel() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelled.set(true);
            Attempt current = activeAttempt.getAndSet(null);
            if (current != null) {
                current.finish(null, new CancelledException());
            }
            result.completeExceptionally(new CancelledException());
            emit(StreamEventType.CANCELLED, "");
        }

        private void finish(HttpResponse<String> response, Throwable failure) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            if (failure != null) {
                result.completeExceptionally(failure);
                fail(failure);
            } else {
                if (!usedFallback && response != null && isSuccessful(response.statusCode())) {
                    notifyAggregated(JsonParser.parseString(response.body()).getAsJsonObject());
                }
                result.complete(response);
                emitTerminal(response);
            }
        }

        /** 尝试级资源所有权；终结必须先关闭本次资源，再完成 future 触发下一次尝试。 */
        private final class Attempt {
            private final CompletableFuture<HttpResponse<String>> future = new CompletableFuture<>();
            private CompletableFuture<HttpResponse<InputStream>> send;
            private InputStream stream;
            private ScheduledFuture<?> guard;
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

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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

    /**
     * 流式阶段（已成功收到响应头且按 SSE 读取）断开、超时或缺终止标志时抛出，
     * 作为唯一允许触发一次非流式回退的“传输类”失败。
     */
    private static final class StreamBrokenException extends RuntimeException {
        private StreamBrokenException(Throwable cause) {
            super(cause == null ? "stream interrupted" : cause.getMessage(), cause);
        }
    }
}

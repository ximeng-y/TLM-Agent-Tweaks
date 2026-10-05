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
import java.util.concurrent.atomic.AtomicInteger;
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
        private final AtomicInteger attempt = new AtomicInteger();
        private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> activeSend = new AtomicReference<>();
        private final AtomicReference<InputStream> activeStream = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<HttpResponse<String>>> activeOuter = new AtomicReference<>();

        private Session(FrozenRequest frozen, SiteProtocol protocol,
                        ProtocolStreamRequestListener listener, Duration timeout) {
            this.frozen = frozen;
            this.protocol = protocol;
            this.listener = listener;
            this.timeout = timeout;
        }

        CompletableFuture<HttpResponse<String>> start(HttpClient client) {
            emit(StreamEventType.STARTED, "");
            CompletableFuture<HttpResponse<String>> result = new CompletableFuture<>();
            attemptStream(client).whenComplete((response, throwable) -> {
                if (cancelled.get()) {
                    result.completeExceptionally(new ProtocolException("Streaming request cancelled"));
                    return;
                }
                if (throwable == null) {
                    result.complete(response);
                    emitTerminal(response);
                    return;
                }
                Throwable cause = unwrap(throwable);
                // 只有“明确不支持 stream/stream_options”或“流式读取/聚合中途失败”
                // 才允许同 payload 的非流式重试；HTTP 错误响应按原样透传。
                if (!(cause instanceof StreamUnsupportedException) && !(cause instanceof StreamBrokenException)) {
                    fail(cause);
                    result.completeExceptionally(cause);
                    return;
                }
                if (cancelled.get()) {
                    result.completeExceptionally(new ProtocolException("Streaming request cancelled"));
                    return;
                }
                emit(StreamEventType.FALLBACK, "");
                attemptFallback(client).whenComplete((fallback, fallbackThrowable) -> {
                    if (cancelled.get()) {
                        result.completeExceptionally(new ProtocolException("Streaming request cancelled"));
                        return;
                    }
                    if (fallbackThrowable != null) {
                        Throwable fallbackCause = unwrap(fallbackThrowable);
                        fail(fallbackCause);
                        result.completeExceptionally(fallbackCause);
                        return;
                    }
                    result.complete(fallback);
                    emitTerminal(fallback);
                });
            });
            return result;
        }

        private CompletableFuture<HttpResponse<String>> attemptStream(HttpClient client) {
            int token = attempt.incrementAndGet();
            if (cancelled.get()) {
                return CompletableFuture.failedFuture(new ProtocolException("Streaming request cancelled"));
            }
            byte[] body;
            try {
                body = addStreamFields(protocol, frozen.body());
            } catch (ProtocolException e) {
                return CompletableFuture.failedFuture(e);
            }
            return exchange(client, rebuild(body), token);
        }

        private CompletableFuture<HttpResponse<String>> attemptFallback(HttpClient client) {
            int token = attempt.incrementAndGet();
            if (cancelled.get()) {
                return CompletableFuture.failedFuture(new ProtocolException("Streaming request cancelled"));
            }
            byte[] body;
            try {
                body = removeStreamFields(protocol, frozen.body());
            } catch (ProtocolException e) {
                return CompletableFuture.failedFuture(e);
            }
            return exchange(client, rebuild(body), token);
        }

        /**
         * 单次 HTTP attempt。token 标记该 attempt 是否仍为当前有效路径；旧 reader
         * 即使稍后完成，也会因 token 不符而被拒绝，不会发事件或提交结果。
         */
        private CompletableFuture<HttpResponse<String>> exchange(HttpClient client, HttpRequest request,
                                                                 int token) {
            CompletableFuture<HttpResponse<String>> outer = new CompletableFuture<>();
            activeOuter.set(outer);
            long deadline = System.nanoTime() + timeout.toNanos();
            CompletableFuture<HttpResponse<InputStream>> send = client.sendAsync(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            activeSend.set(send);
            // 读 body 的硬截止：关闭已到响应头但阻塞的流，并让本次 attempt 以
            // “流式中断”收口（交由上层做一次非流式回退）。
            ScheduledFuture<?> guard = DEADLINE.schedule(() -> {
                // 先作废本次 attempt 并关闭流，再完成 outer：outer 的完成会同步触发
                // 上层回退，若此时才 cancelActive 会误关掉新回退请求的连接。
                attempt.incrementAndGet();
                cancelActive();
                outer.completeExceptionally(new StreamBrokenException(
                        new ProtocolException("LLM request phase exceeded deadline " + timeout)));
            }, Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            READER.execute(() -> {
                boolean streamPhase = false;
                try {
                    HttpResponse<InputStream> head = send.join();
                    // 立刻登记 body 流，保证取消/超时能关闭“已到响应头但尚未进入读取阶段”的流。
                    activeStream.set(head.body());
                    if (!isCurrent(token) || cancelled.get()) {
                        closeQuietly(head.body());
                        throw new CancelledException();
                    }
                    if (!isSuccessful(head.statusCode())) {
                        // 普通 HTTP 错误（含 401/403/404/429、参数错误、5xx）原样透传，
                        // 只有明确表示 stream 不受支持时才允许回退。
                        String errorBody = readAll(head.body(), deadline, token);
                        if (shouldRetryWithoutStream(head.statusCode(), errorBody)) {
                            throw new StreamUnsupportedException(head.statusCode(), errorBody);
                        }
                        outer.complete(HttpStringResponse.of(head, errorBody));
                        return;
                    }
                    ContentKind kind = contentKind(head.headers());
                    streamPhase = kind == ContentKind.SSE;
                    String raw = kind == ContentKind.JSON
                            ? readAll(head.body(), deadline, token)
                            : readStream(head.body(), deadline, token);
                    if (!isCurrent(token) || cancelled.get()) {
                        throw new CancelledException();
                    }
                    JsonObject aggregated = JsonParser.parseString(raw).getAsJsonObject();
                    // 纯 JSON 响应先按协议校验，确认可解码才算一次成功，避免无谓回退。
                    if (kind == ContentKind.JSON) {
                        ProtocolResponseDecoder.decode(protocol, aggregated);
                    }
                    // 仅在当前 attempt 仍然有效时才提交聚合结果，旧 reader 不得污染回退后的元数据。
                    notifyAggregated(aggregated);
                    outer.complete(HttpStringResponse.of(head, raw));
                } catch (Throwable throwable) {
                    Throwable cause = unwrap(throwable);
                    // attempt 已被超时/取消/回退作废时，终态由作废方负责，旧 reader
                    // 不得用 CancellationException 覆盖掉可回退的错误分类。
                    if (!isCurrent(token) || cancelled.get()) {
                        return;
                    }
                    // 流式阶段（成功响应头 + SSE）的中断/聚合失败允许回退一次；
                    // HTTP 错误响应或纯 JSON 读取失败保持原样。
                    outer.completeExceptionally(streamPhase ? new StreamBrokenException(cause) : cause);
                } finally {
                    guard.cancel(false);
                    activeSend.compareAndSet(send, null);
                    activeOuter.compareAndSet(outer, null);
                }
            });
            return outer;
        }

        /**
         * 消费 SSE：终止标志到达即停止读取（keep-alive 连接不必等 EOF）。
         */
        private String readStream(InputStream stream, long deadline, int token) throws Exception {
            ProtocolStreamAggregator aggregator = newAggregator(protocol, listener, requestId);
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
                        if (!isCurrent(token)) {
                            throw new CancelledException();
                        }
                        aggregator.accept(event.data());
                    }
                    if (aggregator.complete()) {
                        break;
                    }
                }
                if (!aggregator.complete()) {
                    for (SseEvent event : decoder.finish()) {
                        aggregator.accept(event.data());
                    }
                }
                return aggregator.finish().toString();
            } finally {
                activeStream.compareAndSet(stream, null);
            }
        }

        private String readAll(InputStream stream, long deadline, int token) throws Exception {
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
                    if (!isCurrent(token)) {
                        throw new CancelledException();
                    }
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            } finally {
                activeStream.compareAndSet(stream, null);
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
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            // 先摘取当前 attempt 的 future，避免 reader 的 finally 抢先把它清空，
            // 导致取消后调用方 future 永远不完成。
            CompletableFuture<HttpResponse<String>> outer = activeOuter.getAndSet(null);
            attempt.incrementAndGet();
            cancelActive();
            if (outer != null) {
                outer.completeExceptionally(new CancelledException());
            }
            emit(StreamEventType.CANCELLED, "");
        }

        private void cancelActive() {
            CompletableFuture<HttpResponse<InputStream>> send = activeSend.getAndSet(null);
            if (send != null) {
                send.cancel(true);
            }
            closeQuietly(activeStream.getAndSet(null));
        }

        private boolean isCurrent(int token) {
            return attempt.get() == token;
        }

        private HttpRequest rebuild(byte[] body) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(frozen.request().uri());
            builder.method(frozen.request().method(), HttpRequest.BodyPublishers.ofByteArray(body));
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
        return text.contains("stream") && (text.contains("not support") || text.contains("unsupported")
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
        json.remove("stream");
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

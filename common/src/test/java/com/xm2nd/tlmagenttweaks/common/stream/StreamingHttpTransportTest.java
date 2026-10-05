package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xm2nd.tlmagenttweaks.common.protocol.DecodedResponse;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolMetadataStore;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolRequestEncoder;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolResponseDecoder;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingHttpTransportTest {

    private HttpServer server;
    private String base;
    private final List<String> streamBodies = new CopyOnWriteArrayList<>();
    private final List<String> nonStreamBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger streamCalls = new AtomicInteger();
    private final AtomicInteger nonStreamCalls = new AtomicInteger();
    private final java.util.Map<String, String> scenarios = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        scenarios.clear();
        server.createContext("/stream", exchange -> {
            streamCalls.incrementAndGet();
            String body = readBody(exchange);
            streamBodies.add(body);
            String scenario = scenarios.getOrDefault(exchange.getRequestURI().getPath(), chatSse());
            boolean streaming = isStreamingRequest(body);
            if (scenario.startsWith("FAIL_")) {
                if (scenario.startsWith("FAIL_FALLBACK")) {
                    // 流式半截（无 [DONE]）触发回退；非流式回退请求返回 500。
                    if (!streaming) {
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }
                    byte[] broken = ("data: {\"id\":\"id-1\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                    exchange.sendResponseHeaders(200, broken.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(broken);
                    }
                    return;
                }
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            String payload = streaming
                    ? scenario
                    : "{\"id\":\"fallback\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"fallback\"},"
                    + "\"finish_reason\":\"stop\"}]}";
            // 非流式回退请求返回真正的 application/json，与真实服务端一致。
            exchange.getResponseHeaders().add("Content-Type",
                    streaming ? "text/event-stream; charset=utf-8" : "application/json");
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/nonstream", exchange -> {
            nonStreamCalls.incrementAndGet();
            nonStreamBodies.add(readBody(exchange));
            chatJson(exchange, false);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void headerDisconnectRetriesSamePayloadWithoutStreaming() throws Exception {
        server.createContext("/header-reset", exchange -> {
            String body = readBody(exchange);
            if (isStreamingRequest(body)) {
                streamCalls.incrementAndGet();
                exchange.close();
            } else {
                nonStreamCalls.incrementAndGet();
                chatJson(exchange, false);
            }
        });
        HttpResponse<String> response = new StreamingHttpTransport().send(client(),
                StreamingHttpTransport.freeze(request("/header-reset")), SiteProtocol.CHAT_COMPLETIONS,
                null).get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(streamCalls.get() >= 1);
        assertEquals(1, nonStreamCalls.get());
    }

    @Test
    void completedStreamDoesNotWaitForConnectionCloseOrEmitCancellationLater() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<StreamEventType> events = new CopyOnWriteArrayList<>();
        server.createContext("/keepalive", exchange -> {
            readBody(exchange);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(chatSse().getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            StreamingHttpTransport.Call call = new StreamingHttpTransport(Duration.ofSeconds(2))
                    .sendCall(client(), StreamingHttpTransport.freeze(request("/keepalive")),
                            SiteProtocol.CHAT_COMPLETIONS, new ProtocolStreamRequestListener() {
                                @Override
                                public void onEvent(String id, StreamEventType type, String text) {
                                    events.add(type);
                                }
                            });
            assertEquals(200, call.future().get(1, TimeUnit.SECONDS).statusCode());
            call.cancel();
            assertFalse(events.contains(StreamEventType.FALLBACK));
            assertFalse(events.contains(StreamEventType.CANCELLED));
        } finally {
            release.countDown();
        }
    }

    @Test
    void timedOutReaderCannotCloseReplacementRequest() throws Exception {
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        // HttpServer 已启动时不能更换 executor，使用独立服务避免旧阻塞 handler 串行阻挡回退。
        HttpServer parallel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        parallel.setExecutor(executor);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fallbacks = new AtomicInteger();
        parallel.createContext("/", exchange -> {
            String body = readBody(exchange);
            if (!isStreamingRequest(body)) {
                fallbacks.incrementAndGet();
                chatJson(exchange, false);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        parallel.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + parallel.getAddress().getPort() + "/"))
                    .POST(HttpRequest.BodyPublishers.ofString(chatRequestBody())).build();
            HttpResponse<String> response = new StreamingHttpTransport(Duration.ofMillis(500))
                    .send(client(), StreamingHttpTransport.freeze(request), SiteProtocol.CHAT_COMPLETIONS,
                            null).get(3, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertEquals(1, fallbacks.get());
        } finally {
            release.countDown();
            parallel.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedToolNameFragmentsAreNotDeduplicated() throws Exception {
        ChatCompletionsStreamAggregator aggregator = new ChatCompletionsStreamAggregator(null);
        aggregator.accept("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"x",
                "function":{"name":"foo","arguments":"{"}}]}}]}
                """);
        aggregator.accept("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,
                "function":{"name":"foo","arguments":"}"}}]},"finish_reason":"tool_calls"}]}
                """);
        aggregator.accept("[DONE]");
        assertEquals("foofoo", aggregator.finish().getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .getAsJsonObject("function").get("name").getAsString());
    }

    @Test
    void completedButInvalidToolArgumentsTriggerFallback() throws Exception {
        String scenario = """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"x","function":{"name":"tool","arguments":"{"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        JsonObject response = run(SiteProtocol.CHAT_COMPLETIONS, "/stream", scenario, null);
        assertEquals("fallback", response.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString());
        assertEquals(2, streamCalls.get());
    }

    @Test
    void streamsAndAggregatesChatCompletionsIncludingUsage() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        JsonObject raw = run(SiteProtocol.CHAT_COMPLETIONS, "/stream", chatSse(),
                (requestId, type, text) -> events.add(type.name()));
        assertEquals(1, streamCalls.get());
        assertEquals(0, nonStreamCalls.get());
        assertTrue(events.contains(StreamEventType.TEXT_DELTA.name()));
        assertTrue(events.contains(StreamEventType.REASONING_DELTA.name()));
        assertEquals("id-1", raw.get("id").getAsString());
        JsonObject message = raw.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message");
        assertEquals("你好", message.get("content").getAsString());
        assertEquals(11, raw.getAsJsonObject("usage").get("total_tokens").getAsInt());
        assertFalse(raw.get("stream").getAsBoolean());
    }

    @Test
    void streamOptionsOnlyAddedForChatCompletions() throws Exception {
        run(SiteProtocol.CHAT_COMPLETIONS, "/stream", chatSse(), null);
        JsonObject chatRequest = parse(streamBodies.get(0));
        assertEquals(true, chatRequest.get("stream").getAsBoolean());
        assertEquals(true, chatRequest.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());

        streamBodies.clear();
        run(SiteProtocol.ANTHROPIC_MESSAGES, "/stream", anthropicSse(), null);
        JsonObject anthropicRequest = parse(streamBodies.get(0));
        assertEquals(true, anthropicRequest.get("stream").getAsBoolean());
        assertFalse(anthropicRequest.has("stream_options"));
    }

    @Test
    void aggregatesResponsesFunctionCallDeltasAndReusesMetadata() throws Exception {
        JsonObject raw = run(SiteProtocol.RESPONSES, "/stream", responsesSse(), null);
        DecodedResponse decoded = ProtocolResponseDecoder.decode(SiteProtocol.RESPONSES, raw);
        JsonObject message = decoded.body().getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message");
        assertEquals("你好", message.get("content").getAsString());
        JsonObject toolCall = message.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call-1", toolCall.get("id").getAsString());
        assertEquals("{\"city\":\"上海\"}", toolCall.getAsJsonObject("function").get("arguments").getAsString());
        assertNotNull(decoded.metadata());
        assertEquals(1, decoded.metadata().getAsJsonObject().getAsJsonArray("calls").size());

        ProtocolMetadataStore store = new ProtocolMetadataStore();
        store.put(new Object(), "unused", decoded.metadata());
        // 用真实工具历史重新编码，确认不可丢失的 function_call 项已保留。
        JsonObject canonical = new JsonObject();
        canonical.addProperty("model", "gpt-5");
        var messages = new com.google.gson.JsonArray();
        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        assistant.addProperty("content", "你好");
        assistant.add("tool_calls", message.getAsJsonArray("tool_calls").deepCopy());
        messages.add(assistant);
        JsonObject tool = new JsonObject();
        tool.addProperty("role", "tool");
        tool.addProperty("tool_call_id", "call-1");
        tool.addProperty("content", "ok");
        messages.add(tool);
        canonical.add("messages", messages);
        JsonObject encoded = ProtocolRequestEncoder.encode(SiteProtocol.RESPONSES, canonical, store,
                new Object());
        assertTrue(encoded.toString().contains("function_call_output"));
    }

    @Test
    void aggregatesAnthropicCumulativeUsageAndThinkingSignature() throws Exception {
        JsonObject raw = run(SiteProtocol.ANTHROPIC_MESSAGES, "/stream", anthropicSse(), null);
        assertEquals("msg-1", raw.get("id").getAsString());
        assertEquals(12, raw.getAsJsonObject("usage").get("input_tokens").getAsInt());
        assertEquals(30, raw.getAsJsonObject("usage").get("output_tokens").getAsInt());
        var content = raw.getAsJsonArray("content");
        assertEquals("你好", content.get(0).getAsJsonObject().get("text").getAsString());
        JsonObject thinking = content.get(1).getAsJsonObject();
        assertEquals("sig", thinking.get("signature").getAsString());
        JsonObject toolUse = content.get(2).getAsJsonObject();
        assertEquals("get_weather", toolUse.get("name").getAsString());
        assertEquals("{\"city\":\"上海\"}", toolUse.get("input").toString());
    }

    @Test
    void fallsBackToNonStreamWhenStreamEndsEarly() throws Exception {
        String scenario = "data: {\"id\":\"id-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n";
        JsonObject raw = run(SiteProtocol.CHAT_COMPLETIONS, "/stream", scenario, null);
        assertEquals(2, streamCalls.get());
        assertEquals("fallback", raw.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString());
        assertEquals(true, parse(streamBodies.get(0)).get("stream").getAsBoolean());
        JsonObject fallback = parse(streamBodies.get(1));
        assertFalse(fallback.has("stream"));
        assertFalse(fallback.has("stream_options"));
    }

    @Test
    void streamFailureWithFailedFallbackFailsOnce() throws Exception {
        // 流式半截触发回退，回退请求返回 500：只上报一次最终失败，且只发生两次 HTTP。
        HttpResponse<String> response = runFuture(SiteProtocol.CHAT_COMPLETIONS, "/stream", "FAIL_FALLBACK_500")
                .get(10, TimeUnit.SECONDS);
        assertEquals(500, response.statusCode());
        assertEquals(2, streamCalls.get());
    }

    @Test
    void httpServerErrorWithoutStreamHintIsNotRetried() throws Exception {
        // 普通 500（无 stream 不支持提示）必须原样透传，不得回退。
        HttpResponse<String> response = runFuture(SiteProtocol.CHAT_COMPLETIONS, "/stream", "FAIL_BOOM")
                .get(10, TimeUnit.SECONDS);
        assertEquals(500, response.statusCode());
        assertEquals(1, streamCalls.get());
        assertEquals(0, nonStreamCalls.get());
    }

    @Test
    void authenticationErrorsAreNotRetried() throws Exception {
        server.removeContext("/stream");
        server.createContext("/stream", exchange -> {
            streamCalls.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        HttpResponse<String> response = runResponse(SiteProtocol.CHAT_COMPLETIONS, "/stream", "");
        assertEquals(401, response.statusCode());
        assertEquals(1, streamCalls.get());
        assertEquals(0, nonStreamCalls.get());
    }

    @Test
    void rateLimitErrorsAreNotRetried() throws Exception {
        server.removeContext("/stream");
        server.createContext("/stream", exchange -> {
            streamCalls.incrementAndGet();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        HttpResponse<String> response = runResponse(SiteProtocol.CHAT_COMPLETIONS, "/stream", "");
        assertEquals(429, response.statusCode());
        assertEquals(0, nonStreamCalls.get());
    }

    @Test
    void plainJsonResponseFromStreamEndpointIsHandled() throws Exception {
        server.removeContext("/stream");
        server.createContext("/stream", exchange -> {
            streamCalls.incrementAndGet();
            chatJson(exchange, false);
        });
        JsonObject raw = run(SiteProtocol.CHAT_COMPLETIONS, "/stream", "", null);
        assertEquals("ok", raw.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString());
        assertEquals(0, nonStreamCalls.get());
    }

    @Test
    void listenerExceptionsAreIsolated() throws Exception {
        JsonObject raw = run(SiteProtocol.CHAT_COMPLETIONS, "/stream", chatSse(),
                (requestId, type, text) -> {
                    throw new IllegalStateException("listener boom");
                });
        assertNotNull(raw);
        assertEquals(0, nonStreamCalls.get());
    }

    @Test
    void cancelStopsWithoutFallback() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        server.removeContext("/stream");
        server.createContext("/stream", exchange -> {
            streamCalls.incrementAndGet();
            readBody(exchange);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(("data: {\"id\":\"id-1\",\"choices\":[{\"index\":0,\"delta\":{"
                    + "\"content\":\"a\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            started.countDown();
            try {
                block.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });
        StreamingHttpTransport transport = new StreamingHttpTransport(Duration.ofSeconds(30));
        HttpRequest request = request("/stream");
        StreamingHttpTransport.Call call = transport.sendCall(client(), StreamingHttpTransport.freeze(request),
                SiteProtocol.CHAT_COMPLETIONS, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        call.cancel();
        assertThrows(ExecutionException.class, () -> call.future().get(5, TimeUnit.SECONDS));
        assertEquals(0, nonStreamCalls.get());
        block.countDown();
    }

    @Test
    void deadlineClosesStalledBody() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        server.removeContext("/stream");
        server.createContext("/stream", exchange -> {
            readBody(exchange);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("data: {\"id\":\"id-1\"}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            entered.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                out.close();
            } catch (IOException ignored) {
                // 客户端已关闭
            }
        });
        StreamingHttpTransport transport = new StreamingHttpTransport(Duration.ofMillis(400));
        long start = System.nanoTime();
        ExecutionException error = assertThrows(ExecutionException.class, () ->
                transport.send(client(), StreamingHttpTransport.freeze(request("/stream")),
                        SiteProtocol.CHAT_COMPLETIONS, null).get(5, TimeUnit.SECONDS));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(elapsedMillis < 4000, "应当由截止时间而不是服务端 5 秒结束： " + elapsedMillis);
        assertNotNull(error.getCause());
    }

    @Test
    void utf8SplitAcrossByteChunksAndCrLineEndings() {
        SseDecoder decoder = new SseDecoder();
        byte[] bytes = ("data: {\"text\":\"你好\"}\r\rdata: second\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        List<SseEvent> events = new ArrayList<>();
        for (byte value : bytes) {
            events.addAll(decoder.feed(new byte[]{value}, 0, 1));
        }
        assertEquals(2, events.size());
        assertEquals("{\"text\":\"你好\"}", events.get(0).data());
        assertEquals("second", events.get(1).data());
    }

    @Test
    void incompleteTailIsNotDispatchedAsEvent() {
        SseDecoder decoder = new SseDecoder();
        assertTrue(decoder.feed("data: {\"a\":1}\n").isEmpty());
        assertTrue(decoder.finish().isEmpty());
    }

    @Test
    void multiLineDataIsJoinedWithNewline() {
        SseDecoder decoder = new SseDecoder();
        List<SseEvent> events = decoder.feed("data: line1\ndata: line2\n\n");
        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.get(0).data());
    }

    @Test
    void interleavedToolCallDeltasAreAggregatedByIndex() throws Exception {
        String scenario = "data: {\"id\":\"id-2\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call-a\",\"type\":\"function\","
                + "\"function\":{\"name\":\"alpha\",\"arguments\":\"{\\\"x\\\":\"}}]},"
                + "\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"id-2\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,"
                + "\"id\":\"call-b\",\"type\":\"function\",\"function\":{\"name\":\"beta\","
                + "\"arguments\":\"{\\\"y\\\":\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"id-2\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"1}\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"id-2\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,"
                + "\"function\":{\"arguments\":\"2}\"}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"id-2\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
        JsonObject raw = run(SiteProtocol.CHAT_COMPLETIONS, "/stream", scenario, null);
        var toolCalls = raw.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").getAsJsonArray("tool_calls");
        assertEquals(2, toolCalls.size());
        assertEquals("call-a", toolCalls.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("{\"x\":1}", toolCalls.get(0).getAsJsonObject()
                .getAsJsonObject("function").get("arguments").getAsString());
        assertEquals("call-b", toolCalls.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals("{\"y\":2}", toolCalls.get(1).getAsJsonObject()
                .getAsJsonObject("function").get("arguments").getAsString());
    }

    @Test
    void corruptChunkFallsBackToNonStream() throws Exception {
        JsonObject raw = run(SiteProtocol.CHAT_COMPLETIONS, "/stream", "data: {not-json}\n\n", null);
        assertEquals("fallback", raw.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString());
        assertEquals(2, streamCalls.get());
        assertEquals(0, nonStreamCalls.get());
    }

    private JsonObject run(SiteProtocol protocol, String path, String streamBody,
                           EventSink sink) throws Exception {
        HttpResponse<String> response = runResponse(protocol, path, streamBody, sink);
        assertEquals(200, response.statusCode());
        String raw = response.body();
        // 校验聚合结果可被协议解码器消费，但返回“原始协议对象”供各协议断言，
        // 避免把规范化后的 chat.completion 形状误当作原始响应再次解码。
        ProtocolResponseDecoder.decode(protocol, raw);
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private HttpResponse<String> runResponse(SiteProtocol protocol, String path, String streamBody)
            throws Exception {
        return runResponse(protocol, path, streamBody, null);
    }

    private HttpResponse<String> runResponse(SiteProtocol protocol, String path, String streamBody,
                                             EventSink sink) throws Exception {
        String scenario = streamBody == null || streamBody.isEmpty() ? chatSse() : streamBody;
        String requestPath = uniqueScenarioPath(scenario);
        ProtocolStreamRequestListener listener = sink == null ? null
                : new ProtocolStreamRequestListener() {
            @Override
            public void onEvent(String requestId, StreamEventType type, String text) {
                sink.onEvent(requestId, type, text);
            }
        };
        HttpRequest request = request(requestPath);
        return new StreamingHttpTransport().send(client(), StreamingHttpTransport.freeze(request),
                protocol, listener).get(10, TimeUnit.SECONDS);
    }

    private java.util.concurrent.CompletableFuture<HttpResponse<String>> runFuture(
            SiteProtocol protocol, String path, String scenario) throws Exception {
        String requestPath = uniqueScenarioPath(scenario);
        return new StreamingHttpTransport().send(client(),
                StreamingHttpTransport.freeze(request(requestPath)), protocol, null);
    }

    private String uniqueScenarioPath(String scenario) {
        String path = "/stream/" + java.util.UUID.randomUUID();
        scenarios.put(path, scenario == null || scenario.isEmpty() ? chatSse() : scenario);
        return path;
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer test-key")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(chatRequestBody(), StandardCharsets.UTF_8))
                .build();
    }

    private HttpClient client() {
        return HttpClient.newHttpClient();
    }

    private static String chatRequestBody() {
        return "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}";
    }

    private static String chatSse() {
        return "data: {\"id\":\"id-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你\"},"
                + "\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"id-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\","
                + "\"reasoning_content\":\"想\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"id-1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"id\":\"id-1\",\"choices\":[],\"usage\":{\"prompt_tokens\":5,"
                + "\"completion_tokens\":6,\"total_tokens\":11}}\n\n"
                + "data: [DONE]\n\n";
    }

    private static String responsesSse() {
        return "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\",\"model\":\"m\","
                + "\"created_at\":1}}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"你好\"}\n\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,"
                + "\"item_id\":\"fc-1\",\"call_id\":\"call-1\",\"name\":\"get_weather\",\"delta\":\"{\\\"city\\\":\"}\n\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,"
                + "\"item_id\":\"fc-1\",\"delta\":\"\\\"上海\\\"}\"}\n\n"
                + "data: {\"type\":\"response.output_text.done\",\"output_index\":0,\"text\":\"你好\"}\n\n"
                + "data: {\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"type\":"
                + "\"function_call\",\"id\":\"fc-1\",\"call_id\":\"call-1\",\"name\":\"get_weather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"上海\\\"}\"}}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-1\",\"status\":\"completed\","
                + "\"model\":\"m\",\"created_at\":1,\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"你好\"}]},"
                + "{\"type\":\"function_call\",\"id\":\"fc-1\",\"call_id\":\"call-1\",\"name\":\"get_weather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"上海\\\"}\"}]}}\n\n";
    }

    private static String anthropicSse() {
        return "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude\",\"content\":[],\"usage\":{\"input_tokens\":12,"
                + "\"output_tokens\":1,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}}\n\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\","
                + "\"text\":\"\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"你好\"}}\n\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"thinking\","
                + "\"thinking\":\"\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"thinking_delta\","
                + "\"thinking\":\"想\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"signature_delta\","
                + "\"signature\":\"sig\"}}\n\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
                + "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\","
                + "\"id\":\"tool-1\",\"name\":\"get_weather\",\"input\":{}}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"{\\\"city\\\":\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"\\\"上海\\\"}\"}}\n\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                + "\"usage\":{\"output_tokens\":30}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
    }

    private static JsonObject parse(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static boolean isStreamingRequest(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.has("stream") && json.get("stream").getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void send(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void chatJson(HttpExchange exchange, boolean stream) throws IOException {
        String body = "{\"id\":\"id-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,"
                + "\"total_tokens\":2}}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @FunctionalInterface
    private interface EventSink {
        void onEvent(String requestId, StreamEventType type, String text);
    }
}

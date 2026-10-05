package com.xm2nd.tlmagenttweaks.common.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.xm2nd.tlmagenttweaks.common.TokenUsageParser;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolAdaptersTest {

    private static final ProtocolConversationScope SCOPE =
            new ProtocolConversationScope("maid-1", "site-1", SiteProtocol.RESPONSES);

    @Test
    void resolvesExactAndBaseUrls() throws Exception {
        String exact = "https://example.test/custom/submit?x=1";
        assertEquals(URI.create(exact), ProtocolUrls.resolve(
                SiteProtocol.CHAT_COMPLETIONS, UrlMode.EXACT, exact));

        assertEquals(URI.create("https://api.openai.com/v1/chat/completions"),
                ProtocolUrls.resolve(SiteProtocol.CHAT_COMPLETIONS, UrlMode.BASE, "https://api.openai.com"));
        assertEquals(URI.create("https://api.openai.com/v1/chat/completions"),
                ProtocolUrls.resolve(SiteProtocol.CHAT_COMPLETIONS, UrlMode.BASE, "https://api.openai.com/v1/"));
        assertEquals(URI.create("https://api.openai.com/v1/responses"),
                ProtocolUrls.resolve(SiteProtocol.RESPONSES, UrlMode.BASE, "https://api.openai.com/v1"));
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"),
                ProtocolUrls.resolve(SiteProtocol.ANTHROPIC_MESSAGES, UrlMode.BASE, "https://api.anthropic.com"));
        assertEquals(URI.create("https://api.openai.com/v1/responses"),
                ProtocolUrls.resolve(SiteProtocol.RESPONSES, UrlMode.BASE, "https://api.openai.com/v1/responses"));
        assertThrows(ProtocolException.class,
                () -> ProtocolUrls.resolve(SiteProtocol.RESPONSES, UrlMode.BASE, "not-a-url"));
    }

    @Test
    void responsesToolLoopReplaysOpaqueItemsAcrossCallbacks() throws Exception {
        ProtocolMetadataStore store = new ProtocolMetadataStore();
        JsonObject canonical = canonicalWithToolHistory();
        seedReplaySafeMetadata(store, SiteProtocol.RESPONSES, canonical);

        JsonObject firstRequest = ProtocolRequestEncoder.encode(
                SiteProtocol.RESPONSES, canonical, store, SCOPE);
        assertTrue(firstRequest.has("input"));
        assertTrue(firstRequest.getAsJsonArray("input").toString().contains("function_call_output"));
        assertEquals("{\"city\":\"Shanghai\"}",
                firstRequest.getAsJsonArray("input").get(2).getAsJsonObject().get("arguments").getAsString());

        String response = """
                {
                  "id": "resp_1",
                  "object": "response",
                  "created_at": 123,
                  "status": "completed",
                  "model": "gpt",
                  "output": [
                    {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc"},
                    {"type":"function_call","id":"fc_1","call_id":"call_next",
                     "name":"get_weather","arguments":"{\\"city\\":\\"Beijing\\"}"}
                  ],
                  "usage": {
                    "input_tokens": 1000,
                    "output_tokens": 200,
                    "total_tokens": 1200,
                    "input_tokens_details": {"cached_tokens": 600}
                  }
                }
                """;
        DecodedResponse decoded = ProtocolResponseDecoder.decode(SiteProtocol.RESPONSES, response);
        JsonObject normalized = decoded.body();
        assertEquals("chat.completion", normalized.get("object").getAsString());
        JsonObject toolCall = normalized.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_next", toolCall.get("id").getAsString());
        assertEquals("get_weather", toolCall.getAsJsonObject("function").get("name").getAsString());
        assertEquals("{\"city\":\"Beijing\"}",
                toolCall.getAsJsonObject("function").get("arguments").getAsString());
        assertNotNull(decoded.metadataKey());
        store.put(SCOPE, decoded.metadataKey(), decoded.metadata());

        JsonObject continued = canonicalWithHistory(toolCall);
        JsonObject secondRequest = ProtocolRequestEncoder.encode(
                SiteProtocol.RESPONSES, continued, store, SCOPE);
        JsonArray input = secondRequest.getAsJsonArray("input");
        assertEquals("reasoning", input.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("function_call", input.get(3).getAsJsonObject().get("type").getAsString());
        assertEquals("call_next", input.get(3).getAsJsonObject().get("call_id").getAsString());

        // 其他女仆/站点作用域不能读取该 opaque 项。
        ProtocolConversationScope otherScope = new ProtocolConversationScope(
                "maid-2", "site-1", SiteProtocol.RESPONSES);
        assertThrows(ProtocolException.class, () -> ProtocolRequestEncoder.encode(
                SiteProtocol.RESPONSES, continued, store, otherScope));
    }

    @Test
    void anthropicToolLoopReplaysThinkingBlocks() throws Exception {
        ProtocolMetadataStore store = new ProtocolMetadataStore();
        JsonObject canonical = canonicalWithToolHistory();
        seedReplaySafeMetadata(store, SiteProtocol.ANTHROPIC_MESSAGES, canonical);
        JsonObject firstRequest = ProtocolRequestEncoder.encode(
                SiteProtocol.ANTHROPIC_MESSAGES, canonical, store, SCOPE);
        assertTrue(firstRequest.has("system"));
        assertTrue(firstRequest.getAsJsonArray("messages").toString().contains("tool_result"));

        String response = """
                {
                  "id":"msg_1",
                  "type":"message",
                  "role":"assistant",
                  "model":"claude",
                  "content":[
                    {"type":"thinking","thinking":"opaque","signature":"sig"},
                    {"type":"text","text":"hello"},
                    {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Beijing"}}
                  ],
                  "stop_reason":"tool_use",
                  "usage":{
                    "input_tokens":400,
                    "output_tokens":50,
                    "cache_read_input_tokens":300,
                    "cache_creation_input_tokens":100
                  }
                }
                """;
        DecodedResponse decoded = ProtocolResponseDecoder.decode(SiteProtocol.ANTHROPIC_MESSAGES, response);
        JsonObject normalized = decoded.body();
        JsonObject toolCall = normalized.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("toolu_1", toolCall.get("id").getAsString());
        assertEquals("{\"city\":\"Beijing\"}",
                toolCall.getAsJsonObject("function").get("arguments").getAsString());
        assertNotNull(decoded.metadataKey());
        store.put(SCOPE, decoded.metadataKey(), decoded.metadata());

        JsonObject secondRequest = ProtocolRequestEncoder.encode(
                SiteProtocol.ANTHROPIC_MESSAGES, canonicalWithHistory(toolCall), store, SCOPE);
        JsonArray messages = secondRequest.getAsJsonArray("messages");
        JsonArray assistantContent = messages.get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals(2, assistantContent.size());
        assertEquals("thinking", assistantContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("sig", assistantContent.get(0).getAsJsonObject().get("signature").getAsString());
        assertEquals("tool_use", assistantContent.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("toolu_1", assistantContent.get(1).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void opaqueMetadataEvictionFailsClosed() throws Exception {
        ProtocolMetadataStore store = new ProtocolMetadataStore(1, 1);
        ProtocolConversationScope scope = new ProtocolConversationScope(
                "maid-1", "site-1", SiteProtocol.RESPONSES);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("opaqueRequired", true);
        metadata.add("prefix", new JsonArray());
        metadata.add("calls", new JsonArray());
        store.put(scope, "call_1", metadata);
        store.put(scope, "call_2", new JsonObject());

        assertTrue(store.isReplayUncertain(scope));
        assertThrows(ProtocolException.class, () -> ProtocolRequestEncoder.encode(
                SiteProtocol.RESPONSES, canonicalWithToolHistory(), store, scope));
    }

    @Test
    void opaqueMetadataRoundtripsThroughPersistenceSnapshot() throws Exception {
        DecodedResponse decoded = ProtocolResponseDecoder.decode(SiteProtocol.RESPONSES, """
                {
                  "id": "resp_persisted",
                  "object": "response",
                  "status": "completed",
                  "model": "gpt",
                  "output": [
                    {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc"},
                    {"type":"function_call","id":"fc_1","call_id":"call_next",
                     "name":"get_weather","arguments":"{\\"city\\":\\"Beijing\\"}"}
                  ]
                }
                """);
        ProtocolMetadataStore original = new ProtocolMetadataStore(2, 2);
        original.put(SCOPE, decoded.metadataKey(), decoded.metadata());

        ProtocolMetadataStore restored = new ProtocolMetadataStore(2, 2);
        restored.load(original.toJson());

        JsonObject toolCall = decoded.body().getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        JsonObject restoredRequest = ProtocolRequestEncoder.encode(
                SiteProtocol.RESPONSES, canonicalWithHistory(toolCall), restored, SCOPE);
        JsonArray input = restoredRequest.getAsJsonArray("input");
        assertEquals("reasoning", input.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("function_call", input.get(3).getAsJsonObject().get("type").getAsString());
        assertEquals(1, restored.size());
    }

    @Test
    void opaqueMetadataMarkerSurvivesRestartAndMissingHistoryFailsClosed() throws Exception {
        ProtocolMetadataStore original = new ProtocolMetadataStore();
        JsonObject metadata = new JsonObject();
        metadata.addProperty("opaqueRequired", true);
        metadata.add("prefix", new JsonArray());
        metadata.add("calls", new JsonArray());
        original.put(SCOPE, "call_next", metadata);

        ProtocolMetadataStore restored = new ProtocolMetadataStore();
        restored.load(original.toJson());

        assertTrue(restored.isReplayUncertain(SCOPE));
        assertThrows(ProtocolException.class, () -> ProtocolRequestEncoder.encode(
                SiteProtocol.RESPONSES, canonicalWithToolHistory(), restored, SCOPE));
    }

    @Test
    void usageAndErrorPayloadsAreNormalizedOrRejected() throws Exception {
        String responses = """
                {"id":"r","object":"response","status":"completed","model":"m","output":[],
                 "usage":{"input_tokens":1000,"output_tokens":200,"total_tokens":1200,
                 "input_tokens_details":{"cached_tokens":600}}}
                """;
        JsonObject normalizedResponses = ProtocolResponseDecoder.decode(
                SiteProtocol.RESPONSES, responses).body();
        assertEquals(1000, normalizedResponses.getAsJsonObject("usage").get("prompt_tokens").getAsLong());
        assertEquals(600, normalizedResponses.getAsJsonObject("usage")
                .getAsJsonObject("prompt_tokens_details").get("cached_tokens").getAsLong());

        String anthropic = """
                {"id":"a","type":"message","role":"assistant","model":"m","content":[],
                 "usage":{"input_tokens":400,"output_tokens":50,
                 "cache_read_input_tokens":300,"cache_creation_input_tokens":100}}
                """;
        JsonObject normalizedAnthropic = ProtocolResponseDecoder.decode(
                SiteProtocol.ANTHROPIC_MESSAGES, anthropic).body();
        assertEquals(700, normalizedAnthropic.getAsJsonObject("usage").get("prompt_tokens").getAsLong());
        assertEquals(850, normalizedAnthropic.getAsJsonObject("usage").get("total_tokens").getAsLong());
        assertEquals(400, TokenUsageParser.parse(normalizedAnthropic.toString()).inputOnly());
        assertEquals(50, normalizedAnthropic.getAsJsonObject("usage").get("completion_tokens").getAsLong());

        assertThrows(ProtocolException.class, () -> ProtocolResponseDecoder.decode(
                SiteProtocol.RESPONSES, "{\"error\":{\"message\":\"bad request\"}}"));
        assertThrows(ProtocolException.class, () -> ProtocolResponseDecoder.decode(
                SiteProtocol.RESPONSES, """
                        {"id":"r","object":"response","status":"incomplete","model":"m",
                         "output":[{"type":"function_call","id":"fc","call_id":"call_1",
                         "name":"get_weather","arguments":"{\\"city\\":\\"Beijing\\"}"}]}
                        """));
        assertThrows(ProtocolException.class, () -> ProtocolResponseDecoder.decode(
                SiteProtocol.RESPONSES, """
                        {"id":"r","object":"response","status":"in_progress","model":"m","output":[]}
                        """));
        assertThrows(ProtocolException.class, () -> ProtocolResponseDecoder.decode(
                SiteProtocol.RESPONSES, """
                        {"id":"r","object":"response","status":"queued","model":"m","output":[]}
                        """));
        assertThrows(ProtocolException.class, () -> ProtocolResponseDecoder.decode(
                SiteProtocol.ANTHROPIC_MESSAGES, """
                        {"id":"a","type":"message","role":"assistant","model":"m",
                         "content":[{"type":"tool_use","id":"toolu_1","name":"get_weather",
                         "input":{"city":"Beijing"}}],"stop_reason":"max_tokens"}
                        """));
        assertThrows(ProtocolException.class, () -> ProtocolResponseDecoder.decode(
                SiteProtocol.ANTHROPIC_MESSAGES, """
                        {"id":"a","type":"message","role":"assistant","model":"m",
                         "content":[{"type":"text","text":"truncated"}],"stop_reason":"max_tokens"}
                        """));
        assertThrows(ProtocolException.class, () -> ProtocolRequestEncoder.encode(
                SiteProtocol.RESPONSES,
                JsonParser.parseString("""
                        {"model":"m","messages":[],"thinking":{"type":"disabled"}}
                        """).getAsJsonObject(), new ProtocolMetadataStore(), SCOPE));
    }

    @Test
    void httpMockPreservesTransportErrorAndResolvedPath() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI uri = ProtocolUrls.resolve(SiteProtocol.RESPONSES, UrlMode.BASE,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            ProtocolMetadataStore store = new ProtocolMetadataStore();
            JsonObject canonical = canonicalWithToolHistory();
            seedReplaySafeMetadata(store, SiteProtocol.RESPONSES, canonical);
            JsonObject request = ProtocolRequestEncoder.encode(
                    SiteProtocol.RESPONSES, canonical, store, SCOPE);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer unit-test")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(httpRequest, HttpResponse.BodyHandlers.ofString());

            assertEquals(429, response.statusCode());
            assertEquals("{\"error\":{\"message\":\"rate limited\"}}", response.body());
            assertEquals("/v1/responses", uri.getPath());
            assertEquals("unit-test", receivedAuthorization.get().substring("Bearer ".length()));
            assertTrue(receivedBody.get().contains("\"model\":\"gpt\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void protocolConfigJsonRoundtripKeepsLegacyDefaults() throws Exception {
        ProtocolSiteConfig legacy = ProtocolSiteConfig.fromJson(new JsonObject());
        assertEquals(SiteProtocol.CHAT_COMPLETIONS, legacy.protocol());
        assertEquals(UrlMode.EXACT, legacy.urlMode());

        JsonObject json = new JsonObject();
        new ProtocolSiteConfig(SiteProtocol.ANTHROPIC_MESSAGES, UrlMode.BASE).writeTo(json);
        ProtocolSiteConfig parsed = ProtocolSiteConfig.fromJson(json);
        assertEquals(SiteProtocol.ANTHROPIC_MESSAGES, parsed.protocol());
        assertEquals(UrlMode.BASE, parsed.urlMode());
    }

    private static void seedReplaySafeMetadata(ProtocolMetadataStore store, SiteProtocol protocol,
                                               JsonObject canonical) throws ProtocolException {
        JsonArray toolCalls = null;
        for (var element : canonical.getAsJsonArray("messages")) {
            JsonObject message = element.getAsJsonObject();
            if ("assistant".equals(message.get("role").getAsString()) && message.has("tool_calls")) {
                toolCalls = message.getAsJsonArray("tool_calls");
                break;
            }
        }
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new ProtocolException("Test history has no assistant tool calls");
        }
        String key = ProtocolMetadataKey.forToolCalls(protocol, toolCalls);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("opaqueRequired", false);
        metadata.add("prefix", new JsonArray());
        metadata.add("calls", new JsonArray());
        store.put(SCOPE, key, metadata);
    }

    private static JsonObject canonicalWithToolHistory() throws ProtocolException {
        return JsonParser.parseString("""
                {
                  "model":"gpt",
                  "messages":[
                    {"role":"system","content":"system"},
                    {"role":"user","content":"hello"},
                    {"role":"assistant","content":"","tool_calls":[{
                      "id":"call_old","type":"function",
                      "function":{"name":"get_weather","arguments":"{\\"city\\":\\"Shanghai\\"}"}
                    }]},
                    {"role":"tool","tool_call_id":"call_old","content":"{\\"temp\\":22}"}
                  ],
                  "tools":[{"type":"function","function":{"name":"get_weather",
                    "description":"weather","parameters":{"type":"object","properties":{}}}}],
                  "response_format":{"type":"text"}
                }
                """).getAsJsonObject();
    }

    private static JsonObject canonicalWithHistory(JsonObject toolCall) {
        JsonObject canonical = JsonParser.parseString("""
                {
                  "model":"gpt",
                  "messages":[
                    {"role":"system","content":"system"},
                    {"role":"user","content":"hello"},
                    {"role":"assistant","content":"","tool_calls":[]},
                    {"role":"tool","tool_call_id":"call_next","content":"{\\"temp\\":23}"}
                  ],
                  "tools":[{"type":"function","function":{"name":"get_weather",
                    "description":"weather","parameters":{"type":"object","properties":{}}}}],
                  "response_format":{"type":"text"}
                }
                """).getAsJsonObject();
        JsonArray toolCalls = new JsonArray();
        toolCalls.add(toolCall);
        canonical.getAsJsonArray("messages").get(2).getAsJsonObject().add("tool_calls", toolCalls);
        return canonical;
    }
}

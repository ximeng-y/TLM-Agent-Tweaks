package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;

import java.nio.charset.StandardCharsets;

/**
 * 请求体的流式/非流式编码：在冻结请求的字节上增删 {@code stream} 字段。
 * 纯函数、无状态；同一份冻结正文按模式改写后交给传输层重发。
 */
final class StreamBodyCodec {

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

    private StreamBodyCodec() {
    }
}

package com.xm2nd.tlmagenttweaks.common.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 将 Chat Completions、Responses、Anthropic Messages 的完整非流式响应
 * 统一成 TLM 的 {@code chat.completion} JSON 形态。
 */
public final class ProtocolResponseDecoder {
    private static final Gson GSON = new Gson();

    private ProtocolResponseDecoder() {
    }

    public static DecodedResponse decode(SiteProtocol protocol, String rawJson) throws ProtocolException {
        if (rawJson == null || rawJson.isBlank()) {
            throw new ProtocolException("Response body must not be empty");
        }
        try {
            return decode(protocol, JsonParser.parseString(rawJson).getAsJsonObject());
        } catch (ProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProtocolException("Response body is not a JSON object", e);
        }
    }

    public static DecodedResponse decode(SiteProtocol protocol, JsonObject raw) throws ProtocolException {
        if (protocol == null || raw == null) {
            throw new ProtocolException("Protocol and response body must not be null");
        }
        rejectErrorPayload(raw);
        return switch (protocol) {
            case CHAT_COMPLETIONS -> decodeChatCompletions(raw);
            case RESPONSES -> decodeResponses(raw);
            case ANTHROPIC_MESSAGES -> decodeAnthropic(raw);
        };
    }

    private static DecodedResponse decodeChatCompletions(JsonObject raw) throws ProtocolException {
        requiredArray(raw, "choices");
        return new DecodedResponse(raw.deepCopy(), null, null);
    }

    private static DecodedResponse decodeResponses(JsonObject raw) throws ProtocolException {
        String status = optionalString(raw, "status");
        if (status != null && !"completed".equals(status)) {
            throw new ProtocolException("Responses request is not complete, status: " + status);
        }

        StringBuilder text = new StringBuilder();
        JsonArray toolCalls = new JsonArray();
        JsonArray metadataPrefix = new JsonArray();
        JsonArray metadataCalls = new JsonArray();
        JsonArray output = requiredArray(raw, "output");
        for (JsonElement element : output) {
            JsonObject item = asObject(element, "output[]");
            String type = requiredString(item, "type");
            switch (type) {
                case "message" -> text.append(responsesMessageText(item));
                case "reasoning" -> metadataPrefix.add(item.deepCopy());
                case "function_call" -> {
                    toolCalls.add(responsesToolCall(item));
                    metadataCalls.add(item.deepCopy());
                }
                default -> throw new ProtocolException("Unsupported Responses output item: " + type);
            }
        }

        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", assistantMessage(text.toString(), toolCalls));
        // Responses 分支只接受 status=completed，不存在长度终止形态；
        // 工具调用与普通结束按 Chat Completions 语义区分。
        choice.addProperty("finish_reason", toolCalls.isEmpty() ? "stop" : "tool_calls");

        JsonObject body = chatCompletionEnvelope(raw, "created_at", choice);
        JsonObject usage = responsesUsage(raw);
        if (usage != null) {
            body.add("usage", usage);
        }

        String metadataKey = ProtocolMetadataKey.forToolCalls(SiteProtocol.RESPONSES, toolCalls);
        JsonObject metadata = null;
        if (metadataKey != null) {
            metadata = new JsonObject();
            metadata.add("prefix", metadataPrefix);
            metadata.add("calls", metadataCalls);
            metadata.addProperty("opaqueRequired", !metadataPrefix.isEmpty());
        }
        return new DecodedResponse(body, metadataKey, metadata);
    }

    private static String responsesMessageText(JsonObject message) throws ProtocolException {
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            throw new ProtocolException("Responses message content must be a string or array");
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement element : content.getAsJsonArray()) {
            JsonObject part = asObject(element, "output[].content[]");
            String type = requiredString(part, "type");
            switch (type) {
                case "output_text", "text" -> text.append(requiredStringAllowEmpty(part, "text"));
                case "refusal" -> text.append(requiredStringAllowEmpty(part, "refusal"));
                default -> throw new ProtocolException("Unsupported Responses message content: " + type);
            }
        }
        return text.toString();
    }

    private static JsonObject responsesToolCall(JsonObject item) throws ProtocolException {
        JsonObject function = new JsonObject();
        function.addProperty("name", requiredString(item, "name"));
        function.addProperty("arguments", requiredStringAllowEmpty(item, "arguments"));

        JsonObject toolCall = new JsonObject();
        String callId = optionalString(item, "call_id");
        if (callId == null || callId.isEmpty()) {
            throw new ProtocolException("Responses function_call is missing 'call_id'");
        }
        toolCall.addProperty("id", callId);
        toolCall.addProperty("type", "function");
        toolCall.add("function", function);
        return toolCall;
    }

    private static DecodedResponse decodeAnthropic(JsonObject raw) throws ProtocolException {
        String type = optionalString(raw, "type");
        if (type != null && !"message".equals(type)) {
            throw new ProtocolException("Unsupported Anthropic response type: " + type);
        }

        StringBuilder text = new StringBuilder();
        JsonArray toolCalls = new JsonArray();
        JsonArray metadataPrefix = new JsonArray();
        JsonArray metadataCalls = new JsonArray();
        JsonArray content = requiredArray(raw, "content");
        String stopReason = optionalString(raw, "stop_reason");
        if ("max_tokens".equals(stopReason)) {
            throw new ProtocolException("Anthropic response stopped at max_tokens and may be incomplete");
        }
        for (JsonElement element : content) {
            JsonObject block = asObject(element, "content[]");
            String blockType = requiredString(block, "type");
            switch (blockType) {
                case "text" -> text.append(requiredStringAllowEmpty(block, "text"));
                case "thinking", "redacted_thinking" -> metadataPrefix.add(block.deepCopy());
                case "tool_use" -> {
                    toolCalls.add(anthropicToolCall(block));
                    metadataCalls.add(block.deepCopy());
                }
                default -> throw new ProtocolException("Unsupported Anthropic content block: " + blockType);
            }
        }

        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", assistantMessage(text.toString(), toolCalls));
        if (stopReason != null) {
            choice.addProperty("finish_reason", anthropicFinishReason(stopReason));
        }

        JsonObject body = chatCompletionEnvelope(raw, "created", choice);
        JsonObject usage = anthropicUsage(raw);
        if (usage != null) {
            body.add("usage", usage);
        }

        String metadataKey = ProtocolMetadataKey.forToolCalls(SiteProtocol.ANTHROPIC_MESSAGES, toolCalls);
        JsonObject metadata = null;
        if (metadataKey != null) {
            metadata = new JsonObject();
            metadata.add("prefix", metadataPrefix);
            metadata.add("calls", metadataCalls);
            metadata.addProperty("opaqueRequired", !metadataPrefix.isEmpty());
        }
        return new DecodedResponse(body, metadataKey, metadata);
    }

    /**
     * Anthropic {@code stop_reason} 到 Chat Completions {@code finish_reason} 的统一映射；
     * 未知值保持透传。{@code max_tokens} 在解码入口已按不完整响应拒绝，不会到达此处。
     */
    private static String anthropicFinishReason(String stopReason) {
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            default -> stopReason;
        };
    }

    private static JsonObject anthropicToolCall(JsonObject block) throws ProtocolException {
        String id = requiredString(block, "id");
        String name = requiredString(block, "name");
        JsonObject input = requiredObject(block, "input");
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", GSON.toJson(input));

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("id", id);
        toolCall.addProperty("type", "function");
        toolCall.add("function", function);
        return toolCall;
    }

    private static JsonObject chatCompletionEnvelope(JsonObject raw, String createdField, JsonObject choice) {
        JsonObject body = new JsonObject();
        copyString(raw, body, "id", "id");
        body.addProperty("object", "chat.completion");
        JsonElement created = raw.get(createdField);
        if (created != null && created.isJsonPrimitive() && created.getAsJsonPrimitive().isNumber()) {
            body.addProperty("created", created.getAsLong());
        }
        copyString(raw, body, "model", "model");
        JsonArray choices = new JsonArray();
        choices.add(choice);
        body.add("choices", choices);
        return body;
    }

    private static JsonObject assistantMessage(String text, JsonArray toolCalls) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", text == null ? "" : text);
        if (!toolCalls.isEmpty()) {
            message.add("tool_calls", toolCalls.deepCopy());
        }
        return message;
    }

    private static JsonObject responsesUsage(JsonObject raw) throws ProtocolException {
        JsonObject usage = optionalObject(raw, "usage");
        if (usage == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        boolean present = false;
        long input = hasNumber(usage, "input_tokens") ? numeric(usage, "input_tokens") : -1;
        long output = hasNumber(usage, "output_tokens") ? numeric(usage, "output_tokens") : -1;
        if (input >= 0) {
            out.addProperty("prompt_tokens", input);
            present = true;
        }
        if (output >= 0) {
            out.addProperty("completion_tokens", output);
            present = true;
        }
        if (hasNumber(usage, "total_tokens")) {
            out.addProperty("total_tokens", numeric(usage, "total_tokens"));
            present = true;
        } else if (input >= 0 && output >= 0) {
            // Responses 的 input_tokens 已包含缓存命中部分，可与 output_tokens 组成真实总量。
            out.addProperty("total_tokens", input + output);
        }
        JsonObject details = optionalObject(usage, "input_tokens_details");
        if (details != null && hasNumber(details, "cached_tokens")) {
            JsonObject promptDetails = new JsonObject();
            promptDetails.addProperty("cached_tokens", numeric(details, "cached_tokens"));
            out.add("prompt_tokens_details", promptDetails);
            present = true;
        }
        return present ? out : null;
    }

    private static JsonObject anthropicUsage(JsonObject raw) throws ProtocolException {
        JsonObject usage = optionalObject(raw, "usage");
        if (usage == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        boolean present = false;
        long freshInput = hasNumber(usage, "input_tokens") ? numeric(usage, "input_tokens") : -1;
        long cacheRead = hasNumber(usage, "cache_read_input_tokens")
                ? numeric(usage, "cache_read_input_tokens") : -1;
        long cacheWrite = hasNumber(usage, "cache_creation_input_tokens")
                ? numeric(usage, "cache_creation_input_tokens") : -1;
        long output = hasNumber(usage, "output_tokens") ? numeric(usage, "output_tokens") : -1;

        if (freshInput >= 0 || cacheRead >= 0) {
            long promptTokens = Math.max(0, freshInput) + Math.max(0, cacheRead);
            out.addProperty("prompt_tokens", promptTokens);
            present = true;
        }
        if (cacheRead >= 0) {
            JsonObject promptDetails = new JsonObject();
            promptDetails.addProperty("cached_tokens", cacheRead);
            out.add("prompt_tokens_details", promptDetails);
            present = true;
        }
        if (cacheWrite >= 0) {
            out.addProperty("cache_creation_input_tokens", cacheWrite);
            present = true;
        }
        if (output >= 0) {
            out.addProperty("completion_tokens", output);
            present = true;
        }
        if (freshInput >= 0 && output >= 0) {
            // Anthropic 的 input_tokens 不含缓存读/写，总量需显式加回缓存分量。
            out.addProperty("total_tokens",
                    Math.max(0, freshInput) + Math.max(0, cacheRead)
                            + Math.max(0, cacheWrite) + output);
            present = true;
        }
        return present ? out : null;
    }

    private static void rejectErrorPayload(JsonObject raw) throws ProtocolException {
        JsonElement error = raw.get("error");
        if (error != null && !error.isJsonNull()) {
            String message = "Protocol returned an error payload";
            if (error.isJsonObject()) {
                String detail = optionalString(error.getAsJsonObject(), "message");
                if (detail != null && !detail.isBlank()) {
                    message += ": " + detail;
                }
            }
            throw new ProtocolException(message);
        }
    }

    private static void copyString(JsonObject from, JsonObject to, String fromField, String toField) {
        JsonElement value = from.get(fromField);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            to.addProperty(toField, value.getAsString());
        }
    }

    private static boolean hasNumber(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static long numeric(JsonObject object, String field) {
        return object.get(field).getAsLong();
    }

    private static String requiredString(JsonObject object, String field) throws ProtocolException {
        String value = optionalString(object, field);
        if (value == null || value.isEmpty()) {
            throw new ProtocolException("Field '" + field + "' is required");
        }
        return value;
    }

    private static String requiredStringAllowEmpty(JsonObject object, String field) throws ProtocolException {
        String value = optionalString(object, field);
        if (value == null) {
            throw new ProtocolException("Field '" + field + "' is required");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolException("Field '" + field + "' must be a string");
        }
        return element.getAsString();
    }

    private static JsonArray requiredArray(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            throw new ProtocolException("Field '" + field + "' is required and must be an array");
        }
        return element.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonObject object, String field) throws ProtocolException {
        JsonObject value = optionalObject(object, field);
        if (value == null) {
            throw new ProtocolException("Field '" + field + "' is required and must be an object");
        }
        return value;
    }

    private static JsonObject optionalObject(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw new ProtocolException("Field '" + field + "' must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonObject asObject(JsonElement element, String field) throws ProtocolException {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            throw new ProtocolException("Field '" + field + "' must be an object");
        }
        return element.getAsJsonObject();
    }
}

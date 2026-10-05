package com.xm2nd.tlmagenttweaks.common.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 TLM 已构造好的 Chat Completion 请求转换为目标站点协议。
 * <p>
 * 输入始终使用 TLM 现有的 {@code ChatCompletion} JSON 形态，因此工具筛选、
 * 额外参数和消息组装仍由 TLM 原逻辑负责。
 */
public final class ProtocolRequestEncoder {
    private static final Gson GSON = new Gson();
    public static final int DEFAULT_ANTHROPIC_MAX_TOKENS = 4096;

    private ProtocolRequestEncoder() {
    }

    public static String encodeToString(SiteProtocol protocol, JsonObject canonicalChatCompletion,
                                        ProtocolMetadataStore metadataStore, Object conversationScope)
            throws ProtocolException {
        return GSON.toJson(encode(protocol, canonicalChatCompletion, metadataStore, conversationScope));
    }

    public static JsonObject encode(SiteProtocol protocol, JsonObject canonicalChatCompletion,
                                    ProtocolMetadataStore metadataStore, Object conversationScope)
            throws ProtocolException {
        if (protocol == null || canonicalChatCompletion == null) {
            throw new ProtocolException("Protocol and canonical request must not be null");
        }
        return switch (protocol) {
            case CHAT_COMPLETIONS -> canonicalChatCompletion.deepCopy();
            case RESPONSES -> encodeResponses(canonicalChatCompletion, metadataStore, conversationScope);
            case ANTHROPIC_MESSAGES -> encodeAnthropic(canonicalChatCompletion, metadataStore, conversationScope);
        };
    }

    private static JsonObject encodeResponses(JsonObject canonical, ProtocolMetadataStore metadataStore,
                                              Object conversationScope) throws ProtocolException {
        JsonObject out = new JsonObject();
        out.addProperty("model", requiredString(canonical, "model"));
        out.addProperty("store", false);
        JsonArray include = new JsonArray();
        include.add("reasoning.encrypted_content");
        out.add("include", include);

        JsonArray input = new JsonArray();
        JsonArray messages = requiredArray(canonical, "messages");
        for (JsonElement element : messages) {
            JsonObject message = asObject(element, "messages[]");
            String role = requiredString(message, "role");
            String content = optionalString(message, "content");
            switch (role) {
                case "system", "developer", "user" -> input.add(messageItem(role, "input_text", content));
                case "assistant" -> appendResponsesAssistant(input, message, metadataStore, conversationScope);
                case "tool" -> input.add(responsesToolOutput(message, content));
                default -> throw new ProtocolException("Unsupported message role for Responses: " + role);
            }
        }
        out.add("input", input);

        if (canonical.has("tools")) {
            out.add("tools", responsesTools(requiredArray(canonical, "tools")));
        }
        addResponsesTextFormat(canonical, out);
        rejectUnsupportedCanonicalFields(canonical, "Responses");
        return out;
    }

    private static void appendResponsesAssistant(JsonArray input, JsonObject message,
                                                 ProtocolMetadataStore metadataStore,
                                                 Object conversationScope) throws ProtocolException {
        JsonArray toolCalls = optionalArray(message, "tool_calls");
        String metadataKey = ProtocolMetadataKey.forToolCalls(SiteProtocol.RESPONSES, toolCalls);
        JsonElement metadata = metadataStore == null || conversationScope == null || metadataKey == null
                ? null : metadataStore.get(conversationScope, metadataKey);
        requireMetadataOrReplaySafe(metadataStore, conversationScope, metadataKey, metadata, "Responses");
        JsonArray prefix = arrayField(metadata, "prefix");
        JsonArray exactCalls = arrayField(metadata, "calls");

        if (prefix != null) {
            input.addAll(prefix.deepCopy());
        }
        String content = optionalString(message, "content");
        if (content != null && !content.isEmpty()) {
            input.add(messageItem("assistant", "output_text", content));
        }
        if (exactCalls != null && !exactCalls.isEmpty()) {
            input.addAll(exactCalls.deepCopy());
            return;
        }
        if (toolCalls != null) {
            for (JsonElement element : toolCalls) {
                JsonObject toolCall = asObject(element, "assistant.tool_calls[]");
                JsonObject function = requiredObject(toolCall, "function");
                JsonObject functionCall = new JsonObject();
                functionCall.addProperty("type", "function_call");
                functionCall.addProperty("call_id", requiredString(toolCall, "id"));
                functionCall.addProperty("name", requiredString(function, "name"));
                functionCall.addProperty("arguments", requiredString(function, "arguments"));
                input.add(functionCall);
            }
        }
    }

    private static JsonObject responsesToolOutput(JsonObject message, String content) throws ProtocolException {
        JsonObject output = new JsonObject();
        output.addProperty("type", "function_call_output");
        output.addProperty("call_id", requiredString(message, "tool_call_id"));
        output.addProperty("output", content == null ? "" : content);
        return output;
    }

    private static JsonArray responsesTools(JsonArray tools) throws ProtocolException {
        JsonArray out = new JsonArray();
        for (JsonElement element : tools) {
            JsonObject wrapper = asObject(element, "tools[]");
            String type = optionalString(wrapper, "type");
            if (type != null && !"function".equals(type)) {
                throw new ProtocolException("Responses only supports function tools");
            }
            JsonObject function = requiredObject(wrapper, "function");
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.addProperty("name", requiredString(function, "name"));
            if (function.has("description")) {
                tool.add("description", copyElement(function.get("description"), "tools[].function.description"));
            }
            tool.add("parameters", copyObject(function.get("parameters"), "tools[].function.parameters"));
            if (function.has("strict")) {
                tool.add("strict", copyElement(function.get("strict"), "tools[].function.strict"));
            }
            out.add(tool);
        }
        return out;
    }

    private static void addResponsesTextFormat(JsonObject canonical, JsonObject out) throws ProtocolException {
        JsonElement responseFormat = canonical.get("response_format");
        if (responseFormat == null || responseFormat.isJsonNull()) {
            return;
        }
        JsonObject format = asObject(responseFormat, "response_format");
        String type = optionalString(format, "type");
        if (!"text".equals(type)) {
            throw new ProtocolException("Unsupported Responses response_format: " + type);
        }
        JsonObject text = new JsonObject();
        JsonObject textFormat = new JsonObject();
        textFormat.addProperty("type", "text");
        text.add("format", textFormat);
        out.add("text", text);
    }

    private static JsonObject encodeAnthropic(JsonObject canonical, ProtocolMetadataStore metadataStore,
                                              Object conversationScope) throws ProtocolException {
        JsonObject out = new JsonObject();
        out.addProperty("model", requiredString(canonical, "model"));
        out.addProperty("max_tokens", DEFAULT_ANTHROPIC_MAX_TOKENS);

        JsonArray messages = requiredArray(canonical, "messages");
        String system = collectAnthropicSystem(messages);
        if (!system.isBlank()) {
            out.addProperty("system", system);
        }

        JsonArray anthropicMessages = new JsonArray();
        int index = 0;
        while (index < messages.size()) {
            JsonObject message = asObject(messages.get(index), "messages[]");
            String role = requiredString(message, "role");
            if ("system".equals(role) || "developer".equals(role)) {
                index++;
                continue;
            }
            if ("user".equals(role)) {
                JsonArray blocks = new JsonArray();
                while (index < messages.size()) {
                    JsonObject current = asObject(messages.get(index), "messages[]");
                    if (!"user".equals(requiredString(current, "role"))) {
                        break;
                    }
                    String content = optionalString(current, "content");
                    if (content != null && !content.isEmpty()) {
                        blocks.add(textBlock(content));
                    }
                    index++;
                }
                if (!blocks.isEmpty()) {
                    anthropicMessages.add(anthropicMessage("user", blocks));
                }
                continue;
            }
            if ("tool".equals(role)) {
                JsonArray blocks = new JsonArray();
                while (index < messages.size()) {
                    JsonObject current = asObject(messages.get(index), "messages[]");
                    if (!"tool".equals(requiredString(current, "role"))) {
                        break;
                    }
                    blocks.add(anthropicToolResult(current));
                    index++;
                }
                anthropicMessages.add(anthropicMessage("user", blocks));
                continue;
            }
            if ("assistant".equals(role)) {
                JsonObject assistant = encodeAnthropicAssistant(message, metadataStore, conversationScope);
                if (assistant != null) {
                    anthropicMessages.add(assistant);
                }
                index++;
                continue;
            }
            throw new ProtocolException("Unsupported message role for Anthropic: " + role);
        }

        if (anthropicMessages.isEmpty()) {
            throw new ProtocolException("Anthropic request has no messages");
        }
        out.add("messages", anthropicMessages);
        if (canonical.has("tools")) {
            out.add("tools", anthropicTools(requiredArray(canonical, "tools")));
        }
        rejectUnsupportedCanonicalFields(canonical, "Anthropic");
        return out;
    }

    private static String collectAnthropicSystem(JsonArray messages) throws ProtocolException {
        List<String> systems = new ArrayList<>();
        for (JsonElement element : messages) {
            JsonObject message = asObject(element, "messages[]");
            String role = requiredString(message, "role");
            if (!"system".equals(role) && !"developer".equals(role)) {
                continue;
            }
            String content = optionalString(message, "content");
            if (content != null && !content.isEmpty()) {
                systems.add(content);
            }
        }
        return String.join("\n\n", systems);
    }

    private static JsonObject encodeAnthropicAssistant(JsonObject message, ProtocolMetadataStore metadataStore,
                                                       Object conversationScope) throws ProtocolException {
        JsonArray blocks = new JsonArray();
        JsonArray toolCalls = optionalArray(message, "tool_calls");
        String metadataKey = ProtocolMetadataKey.forToolCalls(SiteProtocol.ANTHROPIC_MESSAGES, toolCalls);
        JsonElement metadata = metadataStore == null || conversationScope == null || metadataKey == null
                ? null : metadataStore.get(conversationScope, metadataKey);
        requireMetadataOrReplaySafe(metadataStore, conversationScope, metadataKey, metadata, "Anthropic");
        JsonArray prefix = arrayField(metadata, "prefix");
        JsonArray exactCalls = arrayField(metadata, "calls");

        if (prefix != null) {
            blocks.addAll(prefix.deepCopy());
        }
        String content = optionalString(message, "content");
        if (content != null && !content.isEmpty()) {
            blocks.add(textBlock(content));
        }
        if (exactCalls != null && !exactCalls.isEmpty()) {
            blocks.addAll(exactCalls.deepCopy());
        } else if (toolCalls != null) {
            for (JsonElement element : toolCalls) {
                JsonObject toolCall = asObject(element, "assistant.tool_calls[]");
                JsonObject function = requiredObject(toolCall, "function");
                JsonObject toolUse = new JsonObject();
                toolUse.addProperty("type", "tool_use");
                toolUse.addProperty("id", requiredString(toolCall, "id"));
                toolUse.addProperty("name", requiredString(function, "name"));
                toolUse.add("input", parseToolArguments(requiredString(function, "arguments")));
                blocks.add(toolUse);
            }
        }
        return blocks.isEmpty() ? null : anthropicMessage("assistant", blocks);
    }

    private static JsonObject anthropicToolResult(JsonObject message) throws ProtocolException {
        JsonObject result = new JsonObject();
        result.addProperty("type", "tool_result");
        result.addProperty("tool_use_id", requiredString(message, "tool_call_id"));
        String content = optionalString(message, "content");
        JsonArray text = new JsonArray();
        text.add(textBlock(content == null ? "" : content));
        result.add("content", text);
        return result;
    }

    private static JsonArray anthropicTools(JsonArray tools) throws ProtocolException {
        JsonArray out = new JsonArray();
        for (JsonElement element : tools) {
            JsonObject wrapper = asObject(element, "tools[]");
            String type = optionalString(wrapper, "type");
            if (type != null && !"function".equals(type)) {
                throw new ProtocolException("Anthropic only supports function tools");
            }
            JsonObject function = requiredObject(wrapper, "function");
            JsonObject tool = new JsonObject();
            tool.addProperty("name", requiredString(function, "name"));
            if (function.has("description")) {
                tool.add("description", copyElement(function.get("description"), "tools[].function.description"));
            }
            tool.add("input_schema", copyObject(function.get("parameters"), "tools[].function.parameters"));
            out.add(tool);
        }
        return out;
    }

    private static JsonObject parseToolArguments(String arguments) throws ProtocolException {
        try {
            JsonElement parsed = JsonParser.parseString(arguments);
            if (!parsed.isJsonObject()) {
                throw new ProtocolException("Anthropic tool arguments must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (ProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProtocolException("Anthropic tool arguments are not valid JSON", e);
        }
    }

    private static JsonObject messageItem(String role, String contentType, String content) {
        JsonObject item = new JsonObject();
        item.addProperty("role", role);
        JsonArray contentArray = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", contentType);
        text.addProperty("text", content == null ? "" : content);
        contentArray.add(text);
        item.add("content", contentArray);
        return item;
    }

    private static JsonObject anthropicMessage(String role, JsonArray content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", content);
        return message;
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static void rejectUnsupportedCanonicalFields(JsonObject canonical, String protocolName)
            throws ProtocolException {
        for (String field : canonical.keySet()) {
            // TLM 的 hasThinkingField 会生成 Chat 专用 thinking 扩展。
            // 目标协议重新构造请求，不透传该站点遗留参数，也不猜测模型支持的推理设置。
            if (field.equals("thinking")) {
                continue;
            }
            if (!field.equals("model") && !field.equals("messages")
                    && !field.equals("tools") && !field.equals("response_format")) {
                throw new ProtocolException("Unsupported Chat Completion field for " + protocolName + ": " + field);
            }
        }
    }

    private static void requireMetadataOrReplaySafe(ProtocolMetadataStore metadataStore, Object conversationScope,
                                                    String metadataKey, JsonElement metadata, String protocolName)
            throws ProtocolException {
        if (metadataKey == null || metadata != null || metadataStore == null || conversationScope == null) {
            return;
        }
        if (metadataStore.isReplayUncertain(conversationScope)) {
            throw new ProtocolException(protocolName
                    + " opaque metadata for historical tool calls is no longer available; refusing incomplete replay");
        }
    }

    private static String requiredString(JsonObject object, String field) throws ProtocolException {
        String value = optionalString(object, field);
        if (value == null || value.isEmpty()) {
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
        JsonArray array = optionalArray(object, field);
        if (array == null) {
            throw new ProtocolException("Field '" + field + "' is required and must be an array");
        }
        return array;
    }

    private static JsonArray optionalArray(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonArray()) {
            throw new ProtocolException("Field '" + field + "' must be an array");
        }
        return element.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonObject object, String field) throws ProtocolException {
        return asObject(object.get(field), field);
    }

    private static JsonObject asObject(JsonElement element, String field) throws ProtocolException {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            throw new ProtocolException("Field '" + field + "' must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonElement copyElement(JsonElement element, String field) throws ProtocolException {
        if (element == null || element.isJsonNull()) {
            throw new ProtocolException("Field '" + field + "' must not be null");
        }
        return element.deepCopy();
    }

    private static JsonObject copyObject(JsonElement element, String field) throws ProtocolException {
        return asObject(copyElement(element, field), field);
    }

    private static JsonArray arrayField(JsonElement element, String field) throws ProtocolException {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonElement value = element.getAsJsonObject().get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonArray()) {
            throw new ProtocolException("Metadata field '" + field + "' must be an array");
        }
        return value.getAsJsonArray();
    }
}

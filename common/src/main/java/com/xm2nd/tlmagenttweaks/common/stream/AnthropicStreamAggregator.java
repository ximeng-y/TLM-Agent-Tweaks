package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic Messages 流式聚合器。
 * <p>
 * 保留 thinking/signature/redacted_thinking 供原始历史回传，但只把公开的
 * thinking_delta 作为思考增量展示；{@code message_delta.usage} 为累计值，直接覆盖。
 */
public final class AnthropicStreamAggregator implements ProtocolStreamAggregator {
    private final StreamEventListener listener;

    private String id;
    private String model;
    private String role = "assistant";
    private String stopReason;
    private JsonObject usage;
    private boolean started;
    private boolean stopped;
    private final Map<Integer, BlockState> blocks = new LinkedHashMap<>();

    public AnthropicStreamAggregator(StreamEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void accept(String data) throws ProtocolException {
        JsonObject event = StreamJson.parseObject(data, "Anthropic");
        String type = StreamJson.requiredString(event, "type");
        switch (type) {
            case "message_start" -> acceptMessageStart(event);
            case "content_block_start" -> acceptBlockStart(event);
            case "content_block_delta" -> acceptBlockDelta(event);
            case "content_block_stop" -> acceptBlockStop(event);
            case "message_delta" -> acceptMessageDelta(event);
            case "message_stop" -> stopped = true;
            case "ping" -> {
                // 心跳忽略
            }
            case "error" -> throw new StreamServiceException("Anthropic stream error: "
                    + errorMessage(event.get("error")));
            default -> {
                // 未知事件对核心内容无影响；未知内容块在 start 阶段会显式失败。
            }
        }
    }

    private void acceptMessageStart(JsonObject event) throws ProtocolException {
        JsonObject message = StreamJson.requiredObject(event, "message");
        started = true;
        id = StreamJson.optionalString(message, "id");
        model = StreamJson.optionalString(message, "model");
        role = StreamJson.stringOr(message, "role", role);
        String stop = StreamJson.optionalString(message, "stop_reason");
        if (stop != null && !stop.isEmpty()) {
            stopReason = stop;
        }
        JsonObject initialUsage = StreamJson.optionalObject(message, "usage");
        if (initialUsage != null) {
            usage = initialUsage.deepCopy();
        }
    }

    private void acceptBlockStart(JsonObject event) throws ProtocolException {
        int index = StreamJson.intOr(event, "index", blocks.size());
        JsonObject block = StreamJson.requiredObject(event, "content_block");
        String type = StreamJson.requiredString(block, "type");
        BlockState state = new BlockState(type);
        switch (type) {
            case "text" -> state.text = StreamJson.stringOr(block, "text", "");
            case "thinking" -> state.text = StreamJson.stringOr(block, "thinking", "");
            case "redacted_thinking" -> state.data = StreamJson.stringOr(block, "data", "");
            case "tool_use" -> {
                state.toolId = StreamJson.stringOr(block, "id", null);
                state.toolName = StreamJson.stringOr(block, "name", null);
                JsonElement input = block.get("input");
                if (input != null && input.isJsonObject() && input.getAsJsonObject().size() > 0) {
                    state.json.setLength(0);
                    state.json.append(input.toString());
                }
            }
            default -> throw new ProtocolException("Unsupported Anthropic content block in stream: " + type);
        }
        blocks.put(index, state);
    }

    private void acceptBlockDelta(JsonObject event) throws ProtocolException {
        int index = StreamJson.intOr(event, "index", -1);
        BlockState state = blocks.get(index);
        if (state == null) {
            throw new ProtocolException("Anthropic content_block_delta without matching start");
        }
        JsonObject delta = StreamJson.requiredObject(event, "delta");
        String type = StreamJson.requiredString(delta, "type");
        switch (type) {
            case "text_delta" -> {
                String text = StreamJson.stringOr(delta, "text", "");
                state.text += text;
                emit(StreamEventType.TEXT_DELTA, text);
            }
            case "thinking_delta" -> {
                String thinking = StreamJson.stringOr(delta, "thinking", "");
                state.text += thinking;
                emit(StreamEventType.REASONING_DELTA, thinking);
            }
            case "signature_delta" -> state.signature += StreamJson.stringOr(delta, "signature", "");
            case "input_json_delta" -> state.json.append(StreamJson.stringOr(delta, "partial_json", ""));
            default -> throw new ProtocolException("Unsupported Anthropic content_block_delta: " + type);
        }
    }

    private void acceptBlockStop(JsonObject event) throws ProtocolException {
        int index = StreamJson.intOr(event, "index", -1);
        if (!blocks.containsKey(index)) {
            throw new ProtocolException("Anthropic content_block_stop without matching start");
        }
    }

    private void acceptMessageDelta(JsonObject event) throws ProtocolException {
        JsonObject delta = StreamJson.requiredObject(event, "delta");
        String stop = StreamJson.optionalString(delta, "stop_reason");
        if (stop != null && !stop.isEmpty()) {
            stopReason = stop;
        }
        JsonObject deltaUsage = StreamJson.optionalObject(event, "usage");
        if (deltaUsage != null) {
            // message_delta.usage 对出现的字段是累计值（直接覆盖、不相加），
            // 但通常只带 output_tokens，input_tokens 等仍来自 message_start，须按字段合并。
            if (usage == null) {
                usage = new JsonObject();
            }
            for (String field : deltaUsage.keySet()) {
                usage.add(field, deltaUsage.get(field).deepCopy());
            }
        }
    }

    @Override
    public boolean complete() {
        return stopped;
    }

    @Override
    public JsonObject finish() throws ProtocolException {
        if (!started) {
            throw new ProtocolException("Anthropic stream ended without message_start");
        }
        if (!stopped) {
            throw new ProtocolException("Anthropic stream ended without message_stop");
        }
        JsonArray content = new JsonArray();
        for (BlockState state : blocks.values()) {
            switch (state.type) {
                case "text" -> {
                    JsonObject block = new JsonObject();
                    block.addProperty("type", "text");
                    block.addProperty("text", state.text);
                    content.add(block);
                }
                case "thinking" -> {
                    JsonObject block = new JsonObject();
                    block.addProperty("type", "thinking");
                    block.addProperty("thinking", state.text);
                    if (!state.signature.isEmpty()) {
                        block.addProperty("signature", state.signature);
                    }
                    content.add(block);
                }
                case "redacted_thinking" -> {
                    JsonObject block = new JsonObject();
                    block.addProperty("type", "redacted_thinking");
                    block.addProperty("data", state.data);
                    content.add(block);
                }
                case "tool_use" -> {
                    if (state.toolId == null || state.toolName == null) {
                        throw new ProtocolException("Anthropic tool_use block is incomplete");
                    }
                    JsonObject block = new JsonObject();
                    block.addProperty("type", "tool_use");
                    block.addProperty("id", state.toolId);
                    block.addProperty("name", state.toolName);
                    block.add("input", parseToolInput(state));
                    content.add(block);
                }
                default -> throw new ProtocolException("Unsupported Anthropic content block: " + state.type);
            }
        }
        JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        if (id != null) {
            message.addProperty("id", id);
        }
        message.addProperty("role", role);
        message.add("content", content);
        if (model != null) {
            message.addProperty("model", model);
        }
        if (stopReason != null) {
            message.addProperty("stop_reason", stopReason);
        }
        if (usage != null) {
            message.add("usage", usage);
        }
        return message;
    }

    private static JsonObject parseToolInput(BlockState state) throws ProtocolException {
        String json = state.json.toString();
        if (json.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new ProtocolException("Anthropic tool_use input is not a JSON object");
            }
            return element.getAsJsonObject();
        } catch (ProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProtocolException("Anthropic tool_use input is not valid JSON", e);
        }
    }

    private void emit(StreamEventType type, String delta) {
        if (listener != null && delta != null && !delta.isEmpty()) {
            listener.onEvent(StreamEvent.text(null, type, delta));
        }
    }

    private static String errorMessage(JsonElement error) {
        if (error != null && error.isJsonObject()) {
            JsonElement message = error.getAsJsonObject().get("message");
            if (message != null && message.isJsonPrimitive()) {
                return message.getAsString();
            }
        }
        return error == null ? "unknown" : error.toString();
    }

    private static final class BlockState {
        private final String type;
        private String text = "";
        private String signature = "";
        private String data = "";
        private String toolId;
        private String toolName;
        private final StringBuilder json = new StringBuilder();

        private BlockState(String type) {
            this.type = type;
        }
    }
}

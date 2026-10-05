package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat Completions 流式聚合器。
 * <p>
 * 按 choice index / tool_calls index 聚合正文与工具参数分片，usage 可出现在最后的
 * {@code choices=[]} 块中；{@code [DONE]} 与 finish_reason 缺一不可。
 */
public final class ChatCompletionsStreamAggregator implements ProtocolStreamAggregator {
    private final StreamEventListener listener;

    private String id;
    private String model;
    private long created;
    private JsonObject usage;
    private boolean done;
    private final Map<Integer, ChoiceState> choices = new LinkedHashMap<>();

    public ChatCompletionsStreamAggregator(StreamEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void accept(String data) throws ProtocolException {
        if ("[DONE]".equals(data)) {
            done = true;
            return;
        }
        JsonObject chunk = StreamJson.parseObject(data, "Chat Completions");
        if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
            throw new ProtocolException("Chat Completions stream returned an error chunk: "
                    + errorMessage(chunk.get("error")));
        }
        if (id == null) {
            id = StreamJson.optionalString(chunk, "id");
        }
        if (model == null) {
            model = StreamJson.optionalString(chunk, "model");
        }
        if (chunk.has("created") && chunk.get("created").isJsonPrimitive()
                && chunk.get("created").getAsJsonPrimitive().isNumber()) {
            created = chunk.get("created").getAsLong();
        }
        JsonObject usageChunk = StreamJson.optionalObject(chunk, "usage");
        if (usageChunk != null) {
            usage = usageChunk.deepCopy();
        }
        JsonArray choiceArray = StreamJson.optionalArray(chunk, "choices");
        if (choiceArray == null) {
            return;
        }
        for (JsonElement element : choiceArray) {
            if (!element.isJsonObject()) {
                throw new ProtocolException("Chat Completions choice must be an object");
            }
            acceptChoice(element.getAsJsonObject());
        }
    }

    private void acceptChoice(JsonObject choice) throws ProtocolException {
        int index = StreamJson.intOr(choice, "index", 0);
        ChoiceState state = choices.computeIfAbsent(index, ignored -> new ChoiceState());
        String finishReason = StreamJson.optionalString(choice, "finish_reason");
        if (finishReason != null && !finishReason.isEmpty()) {
            state.finishReason = finishReason;
        }
        JsonObject delta = StreamJson.optionalObject(choice, "delta");
        if (delta == null) {
            delta = StreamJson.optionalObject(choice, "message");
        }
        if (delta == null) {
            return;
        }
        String role = StreamJson.optionalString(delta, "role");
        if (role != null && !role.isEmpty()) {
            state.role = role;
        }
        String content = StreamJson.optionalString(delta, "content");
        if (content != null && !content.isEmpty()) {
            state.content.append(content);
            emit(StreamEventType.TEXT_DELTA, content);
        }
        String reasoning = firstNonEmpty(
                StreamJson.optionalString(delta, "reasoning_content"),
                StreamJson.optionalString(delta, "reasoning"));
        if (reasoning != null && !reasoning.isEmpty()) {
            emit(StreamEventType.REASONING_DELTA, reasoning);
        }
        JsonArray toolCalls = StreamJson.optionalArray(delta, "tool_calls");
        if (toolCalls != null) {
            for (JsonElement element : toolCalls) {
                if (!element.isJsonObject()) {
                    throw new ProtocolException("Chat Completions tool_call delta must be an object");
                }
                acceptToolCall(state, element.getAsJsonObject());
            }
        }
    }

    private void acceptToolCall(ChoiceState state, JsonObject delta) throws ProtocolException {
        int index = StreamJson.intOr(delta, "index", state.toolCalls.size());
        String callId = StreamJson.optionalString(delta, "id");
        ToolCallState call = callId != null && !callId.isEmpty() ? state.byId.get(callId) : null;
        if (call == null) {
            call = state.byIndex.get(index);
        }
        if (call == null) {
            call = new ToolCallState();
            state.toolCalls.add(call);
        }
        if (callId != null && !callId.isEmpty()) {
            if (call.id == null) {
                call.id = callId;
            }
            state.byId.put(callId, call);
        }
        state.byIndex.put(index, call);
        JsonObject function = StreamJson.optionalObject(delta, "function");
        if (function == null) {
            return;
        }
        String name = StreamJson.optionalString(function, "name");
        if (name != null && !name.isEmpty()) {
            if (call.name == null) {
                call.name = name;
            } else if (!call.name.endsWith(name)) {
                call.name += name;
            }
        }
        String arguments = StreamJson.optionalString(function, "arguments");
        if (arguments != null) {
            call.arguments.append(arguments);
        }
    }

    @Override
    public boolean complete() {
        return done;
    }

    @Override
    public JsonObject finish() throws ProtocolException {
        if (!done) {
            throw new ProtocolException("Chat Completions stream ended without [DONE]");
        }
        if (choices.isEmpty()) {
            throw new ProtocolException("Chat Completions stream produced no choices");
        }
        JsonArray outputChoices = new JsonArray();
        for (Map.Entry<Integer, ChoiceState> entry : choices.entrySet()) {
            ChoiceState state = entry.getValue();
            if (state.finishReason == null) {
                throw new ProtocolException("Chat Completions stream ended without finish_reason");
            }
            JsonObject choice = new JsonObject();
            choice.addProperty("index", entry.getKey());
            JsonObject message = new JsonObject();
            message.addProperty("role", state.role == null ? "assistant" : state.role);
            message.addProperty("content", state.content.toString());
            if (!state.toolCalls.isEmpty()) {
                JsonArray toolCalls = new JsonArray();
                for (ToolCallState call : state.toolCalls) {
                    if (call.id == null || call.id.isEmpty() || call.name == null || call.name.isEmpty()) {
                        throw new ProtocolException("Chat Completions tool call is incomplete");
                    }
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name);
                    function.addProperty("arguments", call.arguments.toString());
                    JsonObject toolCall = new JsonObject();
                    toolCall.addProperty("id", call.id);
                    toolCall.addProperty("type", "function");
                    toolCall.add("function", function);
                    toolCalls.add(toolCall);
                }
                message.add("tool_calls", toolCalls);
            }
            choice.add("message", message);
            choice.addProperty("finish_reason", state.finishReason);
            outputChoices.add(choice);
        }
        JsonObject body = new JsonObject();
        if (id != null) {
            body.addProperty("id", id);
        }
        body.addProperty("object", "chat.completion");
        body.addProperty("created", created);
        if (model != null) {
            body.addProperty("model", model);
        }
        // 明确标记为“非流式完成体”，与真实流式分片对象区分；上层归一化保持不变。
        body.addProperty("stream", false);
        body.add("choices", outputChoices);
        if (usage != null) {
            body.add("usage", usage);
        }
        return body;
    }

    private void emit(StreamEventType type, String delta) {
        if (listener != null && delta != null && !delta.isEmpty()) {
            listener.onEvent(StreamEvent.text(null, type, delta));
        }
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return second;
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

    private static final class ChoiceState {
        private final StringBuilder content = new StringBuilder();
        private final List<ToolCallState> toolCalls = new ArrayList<>();
        private final Map<Integer, ToolCallState> byIndex = new LinkedHashMap<>();
        private final Map<String, ToolCallState> byId = new LinkedHashMap<>();
        private String role;
        private String finishReason;
    }

    private static final class ToolCallState {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}

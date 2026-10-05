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
 * Responses 流式聚合器。
 * <p>
 * 增量仅用于展示；最终优先采用 {@code response.completed} 事件中的完整 response，
 * 缺失时用增量重建。{@code response.failed}/{@code response.incomplete} 与流内
 * {@code error} 一律视为失败，绝不伪装成完整成功。
 */
public final class ResponsesStreamAggregator implements ProtocolStreamAggregator {
    private final StreamEventListener listener;

    private String id;
    private String model;
    private long createdAt;
    private String status;
    private JsonObject completedResponse;
    private JsonObject usage;
    private JsonObject error;
    private final Map<Integer, OutputItem> items = new LinkedHashMap<>();

    public ResponsesStreamAggregator(StreamEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void accept(String data) throws ProtocolException {
        JsonObject event = StreamJson.parseObject(data, "Responses");
        String type = StreamJson.requiredString(event, "type");
        switch (type) {
            case "response.created", "response.in_progress" -> acceptEnvelope(event);
            case "response.output_text.delta" -> acceptTextDelta(event);
            case "response.reasoning_summary_text.delta" -> acceptReasoningDelta(event, "summary_text");
            case "response.reasoning_text.delta" -> acceptReasoningDelta(event, "reasoning_text");
            case "response.function_call_arguments.delta" -> acceptFunctionDelta(event);
            case "response.function_call_arguments.done" -> acceptFunctionDone(event);
            case "response.output_item.done" -> acceptOutputItemDone(event);
            case "response.completed" -> {
                completedResponse = requiredResponse(event);
                status = StreamJson.stringOr(completedResponse, "status", "completed");
                captureUsage(completedResponse);
            }
            case "response.incomplete" -> {
                JsonObject response = requiredResponse(event);
                status = StreamJson.stringOr(response, "status", "incomplete");
                throw new ProtocolException("Responses stream ended incomplete: "
                        + StreamJson.stringOr(response, "incomplete_details", "no details"));
            }
            case "response.failed" -> {
                JsonObject response = requiredResponse(event);
                status = StreamJson.stringOr(response, "status", "failed");
                throw new ProtocolException("Responses stream failed: " + errorMessage(response.get("error")));
            }
            case "error" -> {
                error = event.deepCopy();
                throw new ProtocolException("Responses stream error event: " + errorMessage(event.get("error")));
            }
            default -> {
                // 未知事件（含 annotations、reasoning summary part 等）不参与核心聚合。
            }
        }
    }

    private void acceptEnvelope(JsonObject event) throws ProtocolException {
        JsonObject response = StreamJson.optionalObject(event, "response");
        if (response == null) {
            return;
        }
        if (id == null) {
            id = StreamJson.optionalString(response, "id");
        }
        if (model == null) {
            model = StreamJson.optionalString(response, "model");
        }
        if (response.has("created_at") && response.get("created_at").isJsonPrimitive()
                && response.get("created_at").getAsJsonPrimitive().isNumber()) {
            createdAt = response.get("created_at").getAsLong();
        }
    }

    private void acceptTextDelta(JsonObject event) throws ProtocolException {
        int index = StreamJson.intOr(event, "output_index", items.size());
        String delta = StreamJson.stringOr(event, "delta", "");
        if (delta.isEmpty()) {
            return;
        }
        OutputItem item = messageItem(index);
        item.text.append(delta);
        emit(StreamEventType.TEXT_DELTA, delta);
    }

    private void acceptReasoningDelta(JsonObject event, String part) throws ProtocolException {
        int index = StreamJson.intOr(event, "output_index", -1);
        String delta = StreamJson.stringOr(event, "delta", "");
        if (index < 0 || delta.isEmpty()) {
            return;
        }
        OutputItem item = items.computeIfAbsent(index, ignored -> new OutputItem("reasoning"));
        item.reasoning.append(delta);
        emit(StreamEventType.REASONING_DELTA, delta);
    }

    private void acceptFunctionDelta(JsonObject event) throws ProtocolException {
        OutputItem item = functionItem(event);
        item.arguments.append(StreamJson.stringOr(event, "delta", ""));
        captureFunctionId(item, event);
    }

    private void acceptFunctionDone(JsonObject event) throws ProtocolException {
        OutputItem item = functionItem(event);
        String arguments = StreamJson.optionalString(event, "arguments");
        if (arguments != null) {
            item.arguments.setLength(0);
            item.arguments.append(arguments);
        }
        captureFunctionId(item, event);
    }

    private void acceptOutputItemDone(JsonObject event) throws ProtocolException {
        int index = StreamJson.intOr(event, "output_index", items.size());
        JsonObject item = StreamJson.requiredObject(event, "item");
        String type = StreamJson.requiredString(item, "type");
        if ("function_call".equals(type)) {
            OutputItem state = items.computeIfAbsent(index, ignored -> new OutputItem("function_call"));
            state.callId = StreamJson.stringOr(item, "call_id", state.callId);
            state.name = StreamJson.stringOr(item, "name", state.name);
            state.arguments.setLength(0);
            state.arguments.append(StreamJson.stringOr(item, "arguments", ""));
            state.doneItem = item.deepCopy();
        }
    }

    private OutputItem functionItem(JsonObject event) throws ProtocolException {
        int index = StreamJson.intOr(event, "output_index", items.size());
        String itemId = StreamJson.optionalString(event, "item_id");
        OutputItem item = items.get(index);
        if (item == null || !"function_call".equals(item.kind)) {
            item = new OutputItem("function_call");
            items.put(index, item);
        }
        if (itemId != null && !itemId.isEmpty()) {
            item.itemId = itemId;
        }
        return item;
    }

    private void captureFunctionId(OutputItem item, JsonObject event) throws ProtocolException {
        String callId = StreamJson.optionalString(event, "call_id");
        if (callId != null && !callId.isEmpty()) {
            item.callId = callId;
        }
        String name = StreamJson.optionalString(event, "name");
        if (name != null && !name.isEmpty()) {
            item.name = name;
        }
    }

    private OutputItem messageItem(int index) {
        OutputItem item = items.get(index);
        if (item == null || !"message".equals(item.kind)) {
            item = new OutputItem("message");
            items.put(index, item);
        }
        return item;
    }

    private JsonObject requiredResponse(JsonObject event) throws ProtocolException {
        JsonObject response = StreamJson.optionalObject(event, "response");
        if (response == null) {
            throw new ProtocolException("Responses lifecycle event is missing 'response'");
        }
        return response;
    }

    private void captureUsage(JsonObject response) throws ProtocolException {
        JsonObject captured = StreamJson.optionalObject(response, "usage");
        if (captured != null) {
            usage = captured.deepCopy();
        }
    }

    @Override
    public boolean complete() {
        return completedResponse != null;
    }

    @Override
    public JsonObject finish() throws ProtocolException {
        if (completedResponse != null) {
            JsonObject completed = completedResponse.deepCopy();
            if (StreamJson.optionalArray(completed, "output") == null) {
                throw new ProtocolException("Responses completed event has no output");
            }
            String completedStatus = StreamJson.optionalString(completed, "status");
            if (completedStatus != null && !"completed".equals(completedStatus)) {
                throw new ProtocolException("Responses response is not completed: " + completedStatus);
            }
            return completed;
        }
        if ("incomplete".equals(status)) {
            throw new ProtocolException("Responses stream ended incomplete");
        }
        if (error != null) {
            throw new ProtocolException("Responses stream ended with an error event");
        }
        // 终止标志缺失时不能仅凭增量猜成功。
        throw new ProtocolException("Responses stream ended without response.completed");
    }

    /**
     * 供本该由 completed 事件提供、但部分服务端提前结束的实现使用：用增量重建 response。
     */
    JsonObject reconstruct() {
        JsonObject response = new JsonObject();
        if (id != null) {
            response.addProperty("id", id);
        }
        if (model != null) {
            response.addProperty("model", model);
        }
        response.addProperty("created_at", createdAt);
        response.addProperty("status", "completed");
        JsonArray output = new JsonArray();
        for (OutputItem item : items.values()) {
            switch (item.kind) {
                case "message" -> {
                    JsonObject message = new JsonObject();
                    message.addProperty("type", "message");
                    message.addProperty("role", "assistant");
                    JsonArray content = new JsonArray();
                    JsonObject text = new JsonObject();
                    text.addProperty("type", "output_text");
                    text.addProperty("text", item.text.toString());
                    content.add(text);
                    message.add("content", content);
                    output.add(message);
                }
                case "reasoning" -> {
                    JsonObject reasoning = new JsonObject();
                    reasoning.addProperty("type", "reasoning");
                    JsonArray summary = new JsonArray();
                    JsonObject summaryPart = new JsonObject();
                    summaryPart.addProperty("type", "summary_text");
                    summaryPart.addProperty("text", item.reasoning.toString());
                    summary.add(summaryPart);
                    reasoning.add("summary", summary);
                    JsonArray content = new JsonArray();
                    JsonObject reasoningPart = new JsonObject();
                    reasoningPart.addProperty("type", "reasoning_text");
                    reasoningPart.addProperty("text", item.reasoning.toString());
                    content.add(reasoningPart);
                    reasoning.add("content", content);
                    output.add(reasoning);
                }
                case "function_call" -> {
                    JsonObject call = item.doneItem == null ? new JsonObject() : item.doneItem.deepCopy();
                    call.addProperty("type", "function_call");
                    if (item.callId != null) {
                        call.addProperty("call_id", item.callId);
                    }
                    if (item.itemId != null) {
                        call.addProperty("id", item.itemId);
                    }
                    if (item.name != null) {
                        call.addProperty("name", item.name);
                    }
                    call.addProperty("arguments", item.arguments.toString());
                    output.add(call);
                }
                default -> {
                    // 不会出现
                }
            }
        }
        response.add("output", output);
        if (usage != null) {
            response.add("usage", usage.deepCopy());
        }
        return response;
    }

    private void emit(StreamEventType type, String delta) {
        if (listener != null && delta != null && !delta.isEmpty()) {
            listener.onEvent(StreamEvent.text(null, type, delta));
        }
    }

    private static String errorMessage(JsonElement error) {
        if (error != null && error.isJsonObject()) {
            JsonObject object = error.getAsJsonObject();
            for (String field : new String[]{"message", "code", "type"}) {
                JsonElement value = object.get(field);
                if (value != null && value.isJsonPrimitive()) {
                    return value.getAsString();
                }
            }
        }
        if (error != null && error.isJsonPrimitive()) {
            return error.getAsString();
        }
        return "unknown";
    }

    private static final class OutputItem {
        private final String kind;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private String callId;
        private String name;
        private String itemId;
        private JsonObject doneItem;

        private OutputItem(String kind) {
            this.kind = kind;
        }
    }
}

package com.xm2nd.tlmagenttweaks.common.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 规范化后的 Chat Completion 响应，以及需要按工具批次回填的协议私有元数据。
 */
public record DecodedResponse(JsonObject body, String metadataKey, JsonElement metadata) {
}

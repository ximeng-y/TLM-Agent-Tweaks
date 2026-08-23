package com.xm2nd.tlmagenttweaks.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 解析 OpenAI 兼容响应体中的 usage 字段。
 * <p>
 * 兼容多家缓存计费字段的口径：
 * <ul>
 *   <li>缓存命中：{@code usage.prompt_tokens_details.cached_tokens}（OpenAI 系）
 *       或 {@code usage.prompt_cache_hit_tokens}（DeepSeek），取二者最大值</li>
 *   <li>缓存写入：{@code usage.cache_creation_input_tokens}（Anthropic 系代理，无则 0）</li>
 *   <li>输入（去缓存命中部分）：prompt_tokens − 缓存命中，下限 0</li>
 * </ul>
 */
public final class TokenUsageParser {

    public record UsageStats(long promptTokens, long completionTokens, long cacheRead, long cacheWrite) {
        /** 输入 = prompt − 缓存命中（下限 0） */
        public long inputOnly() {
            return Math.max(0, promptTokens - cacheRead);
        }
    }

    private TokenUsageParser() {
    }

    /**
     * 解析响应体；无 usage 或解析失败返回 null（静默跳过，绝不抛异常）。
     */
    public static UsageStats parse(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonElement usageElement = root.get("usage");
            if (usageElement == null || !usageElement.isJsonObject()) {
                return null;
            }
            JsonObject usage = usageElement.getAsJsonObject();
            long prompt = getAsLong(usage, "prompt_tokens");
            long completion = getAsLong(usage, "completion_tokens");
            if (prompt <= 0 && completion <= 0) {
                return null;
            }
            long cacheRead = getAsLong(usage, "prompt_cache_hit_tokens");
            JsonObject details = usage.getAsJsonObject("prompt_tokens_details");
            if (details != null) {
                cacheRead = Math.max(cacheRead, getAsLong(details, "cached_tokens"));
            }
            long cacheWrite = getAsLong(usage, "cache_creation_input_tokens");
            return new UsageStats(prompt, completion, cacheRead, cacheWrite);
        } catch (Exception e) {
            return null;
        }
    }

    private static long getAsLong(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsLong() : 0;
    }
}

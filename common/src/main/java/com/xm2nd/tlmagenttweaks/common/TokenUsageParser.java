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
            long prompt;
            long completion = getAsLong(usage, "completion_tokens");
            long cacheRead;
            long cacheWrite;

            if (usage.has("prompt_tokens")) {
                // Chat Completions（含本 mod 归一化后的 Responses/Anthropic 形态）
                prompt = getAsLong(usage, "prompt_tokens");
                cacheRead = Math.max(getAsLong(usage, "prompt_cache_hit_tokens"),
                        getNestedLong(usage, "prompt_tokens_details", "cached_tokens"));
                cacheWrite = getAsLong(usage, "cache_creation_input_tokens");
            } else if (usage.has("cache_read_input_tokens") || usage.has("cache_creation_input_tokens")) {
                // Anthropic 原始 usage：input_tokens 是不含缓存读写的增量输入
                long freshInput = getAsLong(usage, "input_tokens");
                cacheRead = getAsLong(usage, "cache_read_input_tokens");
                cacheWrite = getAsLong(usage, "cache_creation_input_tokens");
                prompt = freshInput + cacheRead;
                completion = getAsLong(usage, "output_tokens");
            } else {
                // OpenAI Responses 原始 usage：input_tokens 已包含 cached_tokens
                prompt = getAsLong(usage, "input_tokens");
                completion = getAsLong(usage, "output_tokens");
                cacheRead = getNestedLong(usage, "input_tokens_details", "cached_tokens");
                cacheWrite = 0;
            }
            if (prompt <= 0 && completion <= 0) {
                return null;
            }
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

    private static long getNestedLong(JsonObject obj, String objectKey, String valueKey) {
        JsonElement element = obj.get(objectKey);
        if (element == null || !element.isJsonObject()) {
            return 0;
        }
        return getAsLong(element.getAsJsonObject(), valueKey);
    }
}

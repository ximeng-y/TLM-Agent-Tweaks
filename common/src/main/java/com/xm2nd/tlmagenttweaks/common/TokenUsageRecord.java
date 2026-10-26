package com.xm2nd.tlmagenttweaks.common;

import com.google.gson.annotations.SerializedName;

/**
 * 聚合后的 token 用量行：按 (天, 玩家, 站点, 模型) 聚合一次 LLM 请求的用量。
 * <p>
 * 四类 token 口径：
 * <ul>
 *   <li>输入 inputTokens = prompt_tokens − 缓存命中（下限 0）</li>
 *   <li>输出 outputTokens = completion_tokens</li>
 *   <li>缓存命中 cacheReadTokens = prompt_tokens_details.cached_tokens 或 prompt_cache_hit_tokens</li>
 *   <li>缓存写入 cacheWriteTokens = cache_creation_input_tokens（无则 0）</li>
 * </ul>
 */
public class TokenUsageRecord {
    /** epochDay（LocalDate.ofEpochDay 可还原日期） */
    public int day;
    public String ownerUuid;
    public String ownerName;
    public String siteId;
    public String model;
    public long inputTokens;
    public long outputTokens;
    public long cacheReadTokens;
    public long cacheWriteTokens;
    public long requestCount;

    public TokenUsageRecord() {
        // Gson 反序列化用
    }

    public TokenUsageRecord(int day, String ownerUuid, String ownerName, String siteId, String model) {
        this.day = day;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.siteId = siteId;
        this.model = model;
    }

    /** 复制全部字段的行快照；结果不引用原行对象，供落盘快照在锁外安全序列化 */
    public TokenUsageRecord copy() {
        TokenUsageRecord copy = new TokenUsageRecord(day, ownerUuid, ownerName, siteId, model);
        copy.inputTokens = inputTokens;
        copy.outputTokens = outputTokens;
        copy.cacheReadTokens = cacheReadTokens;
        copy.cacheWriteTokens = cacheWriteTokens;
        copy.requestCount = requestCount;
        return copy;
    }

    public void add(long input, long output, long cacheRead, long cacheWrite) {
        this.inputTokens += input;
        this.outputTokens += output;
        this.cacheReadTokens += cacheRead;
        this.cacheWriteTokens += cacheWrite;
        this.requestCount += 1;
    }

    public long promptTokens() {
        return inputTokens + cacheReadTokens;
    }

    public long totalTokens() {
        return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }
}

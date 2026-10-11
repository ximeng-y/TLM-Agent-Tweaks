package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import net.minecraft.network.chat.Component;

/**
 * 重试期间的等待气泡文案。
 * <p>
 * 只负责替换等待气泡的副文本，不新增网络包或客户端渲染器：
 * TLM 的 {@code refreshWaitingChatBubble} 已经封装气泡替换，并通过实体同步数据下发。
 * <p>
 * 调用方（{@link RetryNotifier}）必须已切到服务端主线程：TLM 对非主线程调用直接抛异常。
 */
public final class RetryBubble {

    /** 状态码重试等待。占位符依次为：等待秒数、第几次、最多几次。 */
    static final String RETRY = "chat.tlm_agent_tweaks.retry";

    /** 429 限流导致的等待，与普通失败区分，提示玩家是额度/频率问题。 */
    static final String RATE_LIMITED = "chat.tlm_agent_tweaks.retry.rate_limited";

    /** 流式断流改用非流式重发；不占用状态码重试次数，因此不显示次数。 */
    static final String FALLBACK = "chat.tlm_agent_tweaks.retry.fallback";

    /** 非流式回退已经真正发出，正在等待响应。 */
    static final String FALLBACK_SENT = "chat.tlm_agent_tweaks.retry.fallback.sent";

    /** 响应头前断连，保持流式重发。占位符：等待秒数。 */
    static final String TRANSPORT_RETRY = "chat.tlm_agent_tweaks.retry.transport";

    /** 站点冷却/积压导致的排队。占位符依次为：已重试次数、最多几次。 */
    static final String SITE_QUEUE = "chat.tlm_agent_tweaks.retry.site_queue";

    /** 排队提示中不显示重试次数的变体：首次发送，或本次等待的起因不在状态码重试额度内。 */
    static final String SITE_QUEUE_FIRST = "chat.tlm_agent_tweaks.retry.site_queue.first";

    private RetryBubble() {
    }

    /** 状态码重试等待（429 限流走独立文案）。 */
    static Component retry(int ordinal, int max, boolean rateLimited) {
        long seconds = Math.max(1L, RetrySettings.RETRY_DELAY.toSeconds());
        return Component.translatable(rateLimited ? RATE_LIMITED : RETRY, seconds, ordinal, max);
    }

    /** 流式断流后的非流式重发等待。 */
    static Component fallback() {
        return Component.translatable(FALLBACK);
    }

    /** 非流式回退已经发出：等待响应期间持续显示，直到本轮结束。 */
    static Component fallbackSent() {
        return Component.translatable(FALLBACK_SENT);
    }

    /** 响应头前断连、保持流式重发。 */
    static Component transportRetry() {
        long seconds = Math.max(1L, RetrySettings.RETRY_DELAY.toSeconds());
        return Component.translatable(TRANSPORT_RETRY, seconds);
    }

    /**
     * 已经在站点队列中排队：区分「首次发送就在排队」和「重试过了仍在排队」。
     *
     * @param ordinal 已消耗的状态码重试次数；传输重试与回退不占该额度，
     *                此时传 0 走不显示次数的变体，避免出现「已重试 0/2 次」
     */
    static Component siteQueue(int ordinal, int max, boolean firstAttempt) {
        return firstAttempt || ordinal <= 0
                ? Component.translatable(SITE_QUEUE_FIRST)
                : Component.translatable(SITE_QUEUE, ordinal, max);
    }

    /**
     * 替换等待气泡副文本。
     *
     * @param callback 目标回调
     * @param text     新的副文本
     */
    static void apply(LLMCallback callback, Component text) {
        if (callback == null || text == null) {
            return;
        }
        try {
            callback.refreshWaitingChatBubble(text);
        } catch (Throwable ignored) {
            // 气泡只是提示：任何失败都不得影响请求本身
        }
    }
}

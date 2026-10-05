package com.xm2nd.tlmagenttweaks.common.stream;

/**
 * 一次逻辑对话（含工具续接轮次）共享的总时间预算。
 * <p>
 * 使用 {@link System#nanoTime()} 作为时间基准，跨线程读取；预算只能被标记为耗尽一次，
 * 标记后调用方必须拒绝该对话后续的模型请求，而不是重置预算。
 */
public interface ConversationBudget {

    /** 绝对截止时刻（{@link System#nanoTime()} 基准）。 */
    long deadlineNanos();

    /** 是否已耗尽。 */
    boolean isExpired();

    /** 标记为已耗尽（幂等）。 */
    void markExpired();
}

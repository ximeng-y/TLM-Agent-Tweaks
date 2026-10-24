package com.xm2nd.tlmagenttweaks.compress;

/**
 * 由 {@code HistorySummaryResultMixin} 实现到 TLM HistorySummaryManager 上的结果读取接口，
 * 供手动压缩的 afterSummary 回调获取本次摘要压缩的真实结果。
 */
public interface HistorySummaryResultAccess {

    /**
     * 读取最近一次 {@code completeHistorySummary} 的真实返回值。
     *
     * @return TRUE/FALSE 为该方法实际返回结果；
     *         null 表示本次回调没有走到该方法（网络失败或 LLM 返回空白摘要）
     */
    Boolean tlmAgentTweaks$peekLastSummaryResult();

    /** 发起手动压缩前清除上一轮记录，避免读到陈旧结果。 */
    void tlmAgentTweaks$resetLastSummaryResult();
}

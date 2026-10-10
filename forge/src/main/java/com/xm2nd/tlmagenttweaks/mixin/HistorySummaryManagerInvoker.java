package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 TLM 的私有压缩调度方法。
 * <p>
 * 公开的 {@code tryCompressBeforeChat} 内部硬编码读取 TLM 自己的自动压缩阈值配置，
 * 手动压缩需要用本项目自定义阈值，因此直接调用跳过阈值判断的 {@code tryScheduleHistorySummary}。
 */
@Mixin(value = HistorySummaryManager.class, remap = false)
public interface HistorySummaryManagerInvoker {

    @Invoker("tryScheduleHistorySummary")
    boolean tlmAgentTweaks$invokeTrySchedule(Runnable afterSummary);
}

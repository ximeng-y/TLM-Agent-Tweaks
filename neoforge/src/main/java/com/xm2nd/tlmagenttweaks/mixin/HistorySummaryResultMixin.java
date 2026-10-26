package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.xm2nd.tlmagenttweaks.compress.HistorySummaryResultAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 捕获 TLM 摘要完成的真实结果。
 * <p>
 * {@code completeHistorySummary} 的返回值是压缩是否真正生效的唯一权威信号：
 * 只有返回 true 的路径会写入摘要并清零 lastChatTokenUsage。但 TLM 的 afterSummary
 * 回调是无参 Runnable，结果无法直接带出；此前以 lastChatTokenUsage == 0 间接推断，
 * 会在压缩在途期间清空 AI 数据时误报成功（清空同样把 token 归零）。
 * <p>
 * 本 mixin 把返回值暂存在 manager 实例上，由手动压缩回调在发起前重置、回调后读取。
 * 记录按 manager 实例隔离；TLM 的 historySummaryRunning 守卫保证同一 manager
 * 同时只有一个摘要任务在途。网络失败（onFailure）与空白摘要路径不经过
 * completeHistorySummary，记录保持 null，同样可区分。
 */
@Mixin(value = HistorySummaryManager.class, remap = false)
public abstract class HistorySummaryResultMixin implements HistorySummaryResultAccess {

    @Unique
    private Boolean tlmAgentTweaks$lastSummaryResult;

    @Inject(method = "completeHistorySummary", at = @At("RETURN"))
    private void tlmAgentTweaks$captureResult(String summary, List<LLMMessage> snapshot,
                                              CallbackInfoReturnable<Boolean> cir) {
        tlmAgentTweaks$lastSummaryResult = cir.getReturnValue();
    }

    @Override
    public Boolean tlmAgentTweaks$peekLastSummaryResult() {
        return tlmAgentTweaks$lastSummaryResult;
    }

    @Override
    public void tlmAgentTweaks$resetLastSummaryResult() {
        tlmAgentTweaks$lastSummaryResult = null;
    }
}

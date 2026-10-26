package com.xm2nd.tlmagenttweaks.compress;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.Config;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.mixin.HistorySummaryManagerInvoker;
import com.xm2nd.tlmagenttweaks.mixin.HistorySummaryResultMixin;
import com.xm2nd.tlmagenttweaks.network.CompressHistoryResultPayload;
import com.xm2nd.tlmagenttweaks.network.CompressHistoryStatePayload;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.apache.commons.lang3.StringUtils;

/**
 * 手动压缩聊天记录（服务端逻辑）。
 * <p>
 * 复用 TLM 原生压缩（LLM 总结历史），但阈值判断使用本项目的 manualThresholdK 配置。
 * 依序校验并给出可读失败原因，全部通过后经 Invoker 调度压缩任务。
 * afterSummary 在成功与失败时均回调（服务端主线程），结果依据
 * {@link HistorySummaryResultMixin} 捕获的 completeHistorySummary 真实返回值判定，
 * 不再以 lastChatTokenUsage == 0 间接推断（清空 AI 数据同样会把 token 归零）。
 */
public final class ManualCompressService {

    /**
     * 复制自 TLM 1.5.3 的包私有/私有常量（HistorySummaryManager.SUMMARY_KEEP_RECENT_COUNT、
     * HistorySummaryPrompts.MIN_MESSAGES_TO_COMPRESS），无法跨包引用故在此复制。
     * 升级 TLM 时需核对参考源码（Self-Talk 仓库 .XMTEMP 下 tlm-extract 与 tlm-1.20.1）
     * 中这两个值是否变化。此处预检仅用于给出友好提示，真正的调度仍由 TLM 原生逻辑兜底校验。
     */
    private static final int SUMMARY_KEEP_RECENT_COUNT = 32;
    private static final int MIN_MESSAGES_TO_COMPRESS = 4;

    private ManualCompressService() {
    }

    public static void handleStateRequest(ServerPlayer player, int entityId) {
        if (!(player.serverLevel().getEntity(entityId) instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            TweakPackets.sendToPlayer(player,
                    new CompressHistoryStatePayload(entityId, false, new CompoundTag()));
            return;
        }
        MaidAIChatManager manager = maid.getAiChatManager();
        boolean running = manager.historySummaryRunning;
        TweakPackets.sendToPlayer(player, new CompressHistoryStatePayload(entityId, running,
                running ? new CompoundTag() : manager.writeToTag(new CompoundTag())));
    }

    public static void handleRequest(ServerPlayer player, int entityId) {
        if (!(player.serverLevel().getEntity(entityId) instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            reply(player, false, "not_owner", 0, 0, entityId, null);
            return;
        }
        MaidAIChatManager manager = maid.getAiChatManager();

        int threshold = Config.MANUAL_COMPRESS_THRESHOLD_K.get() * 1024;
        if (manager.getLastChatTokenUsage() < threshold) {
            reply(player, false, "below_threshold", manager.getLastChatTokenUsage(), threshold / 1024, entityId, null);
            return;
        }
        if (manager.historySummaryRunning) {
            reply(player, false, "busy", 0, 0, entityId, null);
            return;
        }
        int compressible = Math.max(0, manager.getHistory().size() - SUMMARY_KEEP_RECENT_COUNT);
        if (compressible < MIN_MESSAGES_TO_COMPRESS) {
            reply(player, false, "not_enough_messages", compressible, MIN_MESSAGES_TO_COMPRESS, entityId, null);
            return;
        }
        LLMSite site = manager.getLLMSite();
        if (site == null || !site.enabled() || StringUtils.isBlank(manager.getLLMModel())) {
            reply(player, false, "site_invalid", 0, 0, entityId, null);
            return;
        }

        // 发起前清除上一轮摘要结果记录，回调时读到的即本次压缩的真实结果
        HistorySummaryResultAccess summaryResult = (HistorySummaryResultAccess) manager.getHistorySummaryManager();
        summaryResult.tlmAgentTweaks$resetLastSummaryResult();
        boolean scheduled = ((HistorySummaryManagerInvoker) manager.getHistorySummaryManager())
                .tlmAgentTweaks$invokeTrySchedule(() -> {
                    // TRUE：摘要已写入并清理旧消息；FALSE：TLM 拒绝（含在途期间数据被清空）；
                    // null：网络失败或 LLM 返回空白摘要，未走到 completeHistorySummary
                    Boolean result = summaryResult.tlmAgentTweaks$peekLastSummaryResult();
                    boolean ok = Boolean.TRUE.equals(result);
                    CompoundTag aiData = ok ? manager.writeToTag(new CompoundTag()) : new CompoundTag();
                    TweakPackets.sendToPlayer(player, new CompressHistoryResultPayload(
                            ok, ok ? "" : reasonKey(manager, result), 0, 0, entityId, aiData));
                });
        if (!scheduled) {
            TlmAgentTweaks.LOGGER.warn("Manual compress scheduling unexpectedly refused, maid {}, player {}",
                    maid.getUUID(), player.getGameProfile().getName());
            reply(player, false, "compress_failed", 0, 0, entityId, null);
        }
    }

    /**
     * 归类失败原因：completeHistorySummary 明确拒绝（FALSE）且历史与摘要均已被清空，
     * 说明压缩在途期间 AI 数据被清空、本次压缩失效；其余情况（网络失败、空白摘要、
     * 历史尾部变化被拒等）统一按普通压缩失败提示。
     */
    private static String reasonKey(MaidAIChatManager manager, Boolean result) {
        if (Boolean.FALSE.equals(result)
                && manager.getHistory().size() == 0
                && !manager.hasCompressedSummary()) {
            return "message.tlm_agent_tweaks.compress.fail.invalidated";
        }
        return "message.tlm_agent_tweaks.compress.fail.compress_failed";
    }

    private static void reply(ServerPlayer player, boolean ok, String reasonKey, long argA, long argB,
                              int entityId, CompoundTag aiData) {
        TweakPackets.sendToPlayer(player, new CompressHistoryResultPayload(
                ok, ok ? "" : "message.tlm_agent_tweaks.compress.fail." + reasonKey,
                argA, argB, entityId, aiData == null ? new CompoundTag() : aiData));
    }
}

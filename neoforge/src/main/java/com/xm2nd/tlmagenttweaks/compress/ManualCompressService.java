package com.xm2nd.tlmagenttweaks.compress;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.Config;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.mixin.HistorySummaryManagerInvoker;
import com.xm2nd.tlmagenttweaks.network.CompressHistoryResultPayload;
import com.xm2nd.tlmagenttweaks.network.CompressHistoryStatePayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.apache.commons.lang3.StringUtils;

/**
 * 手动压缩聊天记录（服务端逻辑）。
 * <p>
 * 复用 TLM 原生压缩（LLM 总结历史），但阈值判断使用本项目的 manualThresholdK 配置。
 * 依序校验并给出可读失败原因，全部通过后经 Invoker 调度压缩任务；
 * afterSummary 在成功与失败时均回调（服务端主线程），成功判定依据
 * lastChatTokenUsage 被压缩流程清零。
 */
public final class ManualCompressService {

    /**
     * 与 TLM HistorySummaryManager 的常量保持一致（此处预检仅用于给出友好提示，
     * 真正的调度仍由 TLM 原生逻辑兜底校验）。
     */
    private static final int SUMMARY_KEEP_RECENT_COUNT = 32;
    private static final int MIN_MESSAGES_TO_COMPRESS = 4;

    private ManualCompressService() {
    }

    public static void handleStateRequest(ServerPlayer player, int entityId) {
        if (!(player.serverLevel().getEntity(entityId) instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            PacketDistributor.sendToPlayer(player,
                    new CompressHistoryStatePayload(entityId, false, new CompoundTag()));
            return;
        }
        MaidAIChatManager manager = maid.getAiChatManager();
        boolean running = manager.historySummaryRunning;
        PacketDistributor.sendToPlayer(player, new CompressHistoryStatePayload(entityId, running,
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

        boolean scheduled = ((HistorySummaryManagerInvoker) manager.getHistorySummaryManager())
                .tlmAgentTweaks$invokeTrySchedule(() -> {
                    // 压缩成功时 TLM 会把 lastChatTokenUsage 清零，据此判定结果
                    boolean ok = manager.getLastChatTokenUsage() == 0;
                    CompoundTag aiData = ok ? manager.writeToTag(new CompoundTag()) : new CompoundTag();
                    PacketDistributor.sendToPlayer(player,
                            new CompressHistoryResultPayload(ok, ok ? "" : "message.tlm_agent_tweaks.compress.fail.compress_failed",
                                    0, 0, entityId, aiData));
                });
        if (!scheduled) {
            TlmAgentTweaks.LOGGER.warn("Manual compress scheduling unexpectedly refused, maid {}, player {}",
                    maid.getUUID(), player.getGameProfile().getName());
            reply(player, false, "compress_failed", 0, 0, entityId, null);
        }
    }

    private static void reply(ServerPlayer player, boolean ok, String reasonKey, long argA, long argB,
                              int entityId, CompoundTag aiData) {
        PacketDistributor.sendToPlayer(player, new CompressHistoryResultPayload(
                ok, ok ? "" : "message.tlm_agent_tweaks.compress.fail." + reasonKey,
                argA, argB, entityId, aiData == null ? new CompoundTag() : aiData));
    }
}

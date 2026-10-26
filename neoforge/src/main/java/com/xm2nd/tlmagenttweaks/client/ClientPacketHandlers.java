package com.xm2nd.tlmagenttweaks.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.client.thinking.ThinkingDisplayCache;
import com.xm2nd.tlmagenttweaks.network.CompressHistoryResultPayload;
import com.xm2nd.tlmagenttweaks.network.CompressHistoryStatePayload;
import com.xm2nd.tlmagenttweaks.network.PriceDeniedPayload;
import com.xm2nd.tlmagenttweaks.network.PriceTablePayload;
import com.xm2nd.tlmagenttweaks.network.ThinkingConfigPayload;
import com.xm2nd.tlmagenttweaks.network.ThinkingDisplayPayload;
import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端网络包处理（仅客户端加载）。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {
    }

    public static void onCompressResult(CompressHistoryResultPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (!payload.aiData().isEmpty() && mc.level != null
                && mc.level.getEntity(payload.entityId()) instanceof EntityMaid maid) {
            maid.getAiChatManager().readFromTag(payload.aiData());
        }
        if (payload.success()) {
            player.displayClientMessage(Component.translatable("message.tlm_agent_tweaks.compress.success"), true);
        } else if (!payload.reasonKey().isEmpty()) {
            player.displayClientMessage(
                    Component.translatable(payload.reasonKey(), payload.argA(), payload.argB()), false);
        }
        if (mc.screen instanceof HistoryCompressionView view) {
            // busy 表示原任务仍在运行，不应因这次请求被拒绝而恢复按钮。
            view.tlmAgentTweaks$updateCompression(payload.entityId(),
                    "message.tlm_agent_tweaks.compress.fail.busy".equals(payload.reasonKey()));
        }
    }

    public static void onCompressState(CompressHistoryStatePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (!payload.aiData().isEmpty()
                && mc.level.getEntity(payload.entityId()) instanceof EntityMaid maid) {
            maid.getAiChatManager().readFromTag(payload.aiData());
        }
        if (mc.screen instanceof HistoryCompressionView view) {
            view.tlmAgentTweaks$updateCompression(payload.entityId(), payload.running());
        }
    }

    public static void onStatsData(TokenStatsDataPayload payload) {
        ClientStatsCache.accept(payload);
    }

    public static void onPriceTable(PriceTablePayload payload) {
        ClientPriceCache.accept(payload);
    }

    public static void onPriceDenied(PriceDeniedPayload payload) {
        ClientPriceCache.markDenied();
    }

    public static void onThinkingDisplay(ThinkingDisplayPayload payload) {
        ThinkingDisplayCache.accept(payload);
    }

    public static void onThinkingConfig(ThinkingConfigPayload payload) {
        ClientThinkingConfigCache.accept(payload.configs());
        ClientThinkingConfigCache.acceptCurrent(payload.current());
    }
}

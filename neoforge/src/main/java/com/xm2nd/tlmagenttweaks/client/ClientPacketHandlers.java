package com.xm2nd.tlmagenttweaks.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.HistoryAIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.network.CompressHistoryResultPayload;
import com.xm2nd.tlmagenttweaks.network.PriceTablePayload;
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
        // setScreen 同一实例会重新 init，历史屏借此刷新摘要与按钮状态
        if (mc.screen instanceof HistoryAIChatScreen) {
            mc.setScreen(mc.screen);
        }
    }

    public static void onStatsData(TokenStatsDataPayload payload) {
        ClientStatsCache.accept(payload);
    }

    public static void onPriceTable(PriceTablePayload payload) {
        ClientPriceCache.accept(payload);
    }
}

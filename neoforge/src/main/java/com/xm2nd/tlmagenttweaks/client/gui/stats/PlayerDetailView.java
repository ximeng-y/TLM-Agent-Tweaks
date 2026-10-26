package com.xm2nd.tlmagenttweaks.client.gui.stats;

import com.xm2nd.tlmagenttweaks.client.gui.NumUtil;
import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 玩家明细视图：四类 token、缓存命中率、双币种成本。
 */
public final class PlayerDetailView {

    private PlayerDetailView() {
    }

    public static void render(GuiGraphics graphics, Font font, int width, int height,
                              TokenStatsDataPayload.PlayerDetailDto detail, boolean loading) {
        if (detail == null) {
            if (!loading) {
                graphics.drawCenteredString(font,
                        Component.translatable("gui.tlm_agent_tweaks.stats.empty"),
                        width / 2, height / 2, 0x999999);
            }
            return;
        }
        int cx = width / 2;
        int y = height / 2 - 40;
        int left = cx - 110;
        int startY = y + 34;
        int line = 14;
        graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.player.input"), left, startY, 0xFFFFFF);
        graphics.drawString(font, NumUtil.formatTokens(detail.input()), left + 220, startY, 0x4FC3F7);
        graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.player.output"), left, startY + line, 0xFFFFFF);
        graphics.drawString(font, NumUtil.formatTokens(detail.output()), left + 220, startY + line, 0x81C784);
        graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.player.cache_read"), left, startY + line * 2, 0xFFFFFF);
        graphics.drawString(font, NumUtil.formatTokens(detail.cacheRead()), left + 220, startY + line * 2, 0xFFB74D);
        graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.player.cache_write"), left, startY + line * 3, 0xFFFFFF);
        graphics.drawString(font, NumUtil.formatTokens(detail.cacheWrite()), left + 220, startY + line * 3, 0xBA68C8);
        graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.player.requests"), left, startY + line * 4, 0xFFFFFF);
        graphics.drawString(font, String.valueOf(detail.requests()), left + 220, startY + line * 4, 0xCCCCCC);
        graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.player.hit_rate"), left, startY + line * 5, 0xFFFFFF);
        graphics.drawString(font, NumUtil.formatPercent(detail.cacheHitRate()), left + 220, startY + line * 5, 0xFFD54F);

        int costY = startY + line * 6 + 6;
        if (detail.anyCnyPrice()) {
            graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.cost.cny"), left, costY, 0xFFFFFF);
            graphics.drawString(font, "¥" + NumUtil.formatCost(detail.costCny()), left + 220, costY, StatsCharts.COLOR_CNY);
        }
        if (detail.anyUsdPrice()) {
            graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.cost.usd"), left, costY + line, 0xFFFFFF);
            graphics.drawString(font, "$" + NumUtil.formatCost(detail.costUsd()), left + 220, costY + line, StatsCharts.COLOR_USD);
        }
    }
}

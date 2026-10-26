package com.xm2nd.tlmagenttweaks.client.gui.stats;

import com.xm2nd.tlmagenttweaks.client.gui.NumUtil;
import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 玩家排行视图：一玩家一行 + 四色横向堆叠柱 + 行滚动。
 * 布局常量集中在这里，点击命中与滚动换算复用同一套坐标。
 */
public final class RankingView {

    public static final int ROW_HEIGHT = 22;
    private static final int LEFT = 20;
    private static final int TOP = 62;

    private int scroll;

    /** 滚轮滚动一行（原逻辑：向上滚为正即向上翻）。 */
    public void scrolled(double scrollY) {
        this.scroll = Math.max(0, this.scroll - (int) Math.signum(scrollY));
    }

    /** 重置滚动位置（切页签时）。 */
    public void resetScroll() {
        this.scroll = 0;
    }

    /** 点击命中测试：返回命中的排行行号（含滚动偏移），未命中返回 -1。 */
    public int rowAt(int mouseX, int mouseY, int width, int height, int rowCount) {
        int right = width - 20;
        int bottom = height - 30;
        if (mouseX < LEFT || mouseX > right || mouseY < TOP || mouseY >= bottom) {
            return -1;
        }
        int row = (int) ((mouseY - TOP) / ROW_HEIGHT) + this.scroll;
        return row >= 0 && row < rowCount ? row : -1;
    }

    public void render(GuiGraphics graphics, Font font, int width, int height, int mouseX, int mouseY,
                       List<TokenStatsDataPayload.RankEntry> ranking, boolean loading,
                       Consumer<Component> tooltip) {
        StatsCharts.renderLegend(graphics, font, width, 48);

        if (ranking.isEmpty()) {
            if (!loading) {
                graphics.drawCenteredString(font,
                        Component.translatable("gui.tlm_agent_tweaks.stats.empty"),
                        width / 2, height / 2, 0x999999);
            }
            return;
        }

        int left = LEFT;
        int right = width - 20;
        int top = TOP;
        int bottom = height - 30;
        int visible = Math.max(1, (bottom - top) / ROW_HEIGHT);
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, ranking.size() - visible)));

        long maxTotal = 1;
        for (TokenStatsDataPayload.RankEntry entry : ranking) {
            maxTotal = Math.max(maxTotal, entry.total());
        }

        graphics.enableScissor(left - 4, top - 4, right + 4, bottom + 4);
        for (int i = 0; i < visible && this.scroll + i < ranking.size(); i++) {
            TokenStatsDataPayload.RankEntry entry = ranking.get(this.scroll + i);
            int y = top + i * ROW_HEIGHT;
            String name = entry.name() == null || entry.name().isBlank() ? entry.uuid() : entry.name();
            graphics.drawString(font, name, left, y, 0xFFFFFF);
            String total = NumUtil.formatTokens(entry.total());
            graphics.drawString(font, total, right - font.width(total), y, 0xCCCCCC);
            StatsCharts.drawStackedBarH(graphics, left + 90, y + 9, right - left - 90 - 50, 6,
                    entry.input(), entry.output(), entry.cacheRead(), entry.cacheWrite(), maxTotal,
                    mouseX, mouseY, name, tooltip);
        }
        graphics.disableScissor();
    }
}

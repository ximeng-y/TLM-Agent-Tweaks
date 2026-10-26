package com.xm2nd.tlmagenttweaks.client.gui.stats;

import com.xm2nd.tlmagenttweaks.common.GranularityUtil;
import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * 统计页面共享的图表绘制原语：图例、横向/竖向堆叠柱、命中即悬停的色块、成本折线与日期标签。
 * 只负责画，不含数据请求与页签状态；tooltip 命中时交给页面统一渲染。
 */
public final class StatsCharts {

    // 四类 token 的固定配色（ARGB）
    public static final int COLOR_INPUT = 0xFF4FC3F7;
    public static final int COLOR_OUTPUT = 0xFF81C784;
    public static final int COLOR_CACHE_READ = 0xFFFFB74D;
    public static final int COLOR_CACHE_WRITE = 0xFFBA68C8;
    // 成本折线配色（与柱色区分开）
    public static final int COLOR_CNY = 0xFFFF5252;
    public static final int COLOR_USD = 0xFFFFD740;

    private StatsCharts() {
    }

    /** 四色图例（排行与服务器视图共用） */
    public static void renderLegend(GuiGraphics graphics, Font font, int width, int y) {
        int x = width / 2 - 130;
        int[] colors = {COLOR_INPUT, COLOR_OUTPUT, COLOR_CACHE_READ, COLOR_CACHE_WRITE};
        String[] keys = {
                "gui.tlm_agent_tweaks.stats.legend.input",
                "gui.tlm_agent_tweaks.stats.legend.output",
                "gui.tlm_agent_tweaks.stats.legend.cache_read",
                "gui.tlm_agent_tweaks.stats.legend.cache_write"
        };
        for (int i = 0; i < colors.length; i++) {
            graphics.fill(x, y, x + 8, y + 6, colors[i]);
            graphics.drawString(font, Component.translatable(keys[i]), x + 11, y - 1, 0xCCCCCC);
            x += 70;
        }
    }

    /** 横向四色堆叠条 */
    public static void drawStackedBarH(GuiGraphics graphics, int x, int y, int maxWidth, int height,
                                       long input, long output, long cacheRead, long cacheWrite, long maxTotal,
                                       int mouseX, int mouseY, String title, Consumer<Component> tooltip) {
        long total = input + output + cacheRead + cacheWrite;
        if (total <= 0 || maxWidth <= 0 || maxTotal <= 0) {
            return;
        }
        int used = (int) Math.round(maxWidth * (total / (double) maxTotal));
        if (used <= 0) {
            return;
        }
        int b1 = x + (int) Math.round(used * (input / (double) total));
        int b2 = x + (int) Math.round(used * ((input + output) / (double) total));
        int b3 = x + (int) Math.round(used * ((input + output + cacheRead) / (double) total));
        int b4 = x + used;
        drawSegment(graphics, x, y, b1, y + height, COLOR_INPUT,
                title, "gui.tlm_agent_tweaks.stats.legend.input", input, mouseX, mouseY, tooltip);
        drawSegment(graphics, b1, y, b2, y + height, COLOR_OUTPUT,
                title, "gui.tlm_agent_tweaks.stats.legend.output", output, mouseX, mouseY, tooltip);
        drawSegment(graphics, b2, y, b3, y + height, COLOR_CACHE_READ,
                title, "gui.tlm_agent_tweaks.stats.legend.cache_read", cacheRead, mouseX, mouseY, tooltip);
        drawSegment(graphics, b3, y, b4, y + height, COLOR_CACHE_WRITE,
                title, "gui.tlm_agent_tweaks.stats.legend.cache_write", cacheWrite, mouseX, mouseY, tooltip);
    }

    /** 绘制一个真实可见色块，并使用完全相同的半开矩形做命中。 */
    public static void drawSegment(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color,
                                   String title, String typeKey, long value, int mouseX, int mouseY,
                                   Consumer<Component> tooltip) {
        if (x1 >= x2 || y1 >= y2) {
            return;
        }
        graphics.fill(x1, y1, x2, y2, color);
        if (mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2) {
            tooltip.accept(Component.literal(title)
                    .append("\n")
                    .append(Component.translatable(typeKey))
                    .append(": ")
                    .append(Long.toString(value)));
        }
    }

    /** 使用桶的真实起止日期；单日桶只显示一天。 */
    public static String bucketRangeLabel(TokenStatsDataPayload.BucketDto bucket) {
        LocalDate start = LocalDate.ofEpochDay(bucket.startDay());
        LocalDate end = LocalDate.ofEpochDay(bucket.endDay());
        if (bucket.unitOrdinal() == GranularityUtil.BucketUnit.DAY.ordinal() || start.equals(end)) {
            return start.toString();
        }
        return start + " ~ " + end;
    }

    /** 柱段高度换算 */
    public static int segHeight(long value, int chartHeight, long maxTotal) {
        return (int) Math.round(chartHeight * (value / (double) maxTotal));
    }

    /** 逐像素画折线段（图表规模小，性能可接受） */
    public static void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps == 0) {
            graphics.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int px = x1 + (x2 - x1) * i / steps;
            int py = y1 + (y2 - y1) * i / steps;
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }
}

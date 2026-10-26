package com.xm2nd.tlmagenttweaks.client.gui.stats;

import com.xm2nd.tlmagenttweaks.common.GranularityUtil;
import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * 服务器总览视图：四色竖向堆叠柱 + 双币种成本折线 + 日期标签。
 */
public final class ServerView {

    private ServerView() {
    }

    public static void render(GuiGraphics graphics, Font font, int width, int height,
                              int mouseX, int mouseY, List<TokenStatsDataPayload.BucketDto> buckets,
                              boolean loading, Consumer<Component> tooltip) {
        StatsCharts.renderLegend(graphics, font, width, 74);

        if (buckets.isEmpty()) {
            if (!loading) {
                graphics.drawCenteredString(font,
                        Component.translatable("gui.tlm_agent_tweaks.stats.empty"),
                        width / 2, height / 2, 0x999999);
            }
            return;
        }

        int left = 30;
        int right = width - 30;
        int top = 92;
        int bottom = height - 34;

        long maxTotal = 1;
        for (TokenStatsDataPayload.BucketDto bucket : buckets) {
            maxTotal = Math.max(maxTotal, bucket.total());
        }
        // 成本折线的量纲（有任一货币价格才画线）
        boolean drawCny = buckets.stream().anyMatch(TokenStatsDataPayload.BucketDto::anyCnyPrice);
        boolean drawUsd = buckets.stream().anyMatch(TokenStatsDataPayload.BucketDto::anyUsdPrice);
        double maxCost = 0;
        for (TokenStatsDataPayload.BucketDto bucket : buckets) {
            if (drawCny) {
                maxCost = Math.max(maxCost, bucket.costCny());
            }
            if (drawUsd) {
                maxCost = Math.max(maxCost, bucket.costUsd());
            }
        }
        if (maxCost <= 0) {
            maxCost = 1;
        }

        // 竖向堆叠柱 + 折线点
        int chartWidth = right - left;
        int chartHeight = bottom - top;
        int barCount = buckets.size();
        int pitch = Math.max(3, chartWidth / Math.max(1, barCount));
        int barWidth = Math.max(1, pitch - 1);

        boolean monthUnit = !buckets.isEmpty()
                && buckets.get(0).unitOrdinal() == GranularityUtil.BucketUnit.MONTH.ordinal();
        for (int i = 0; i < barCount; i++) {
            TokenStatsDataPayload.BucketDto bucket = buckets.get(i);
            int barX = left + i * pitch + (pitch - barWidth) / 2;
            long total = bucket.total();
            if (total > 0) {
                int barBottom = bottom;
                int y1 = barBottom - StatsCharts.segHeight(bucket.input(), chartHeight, maxTotal);
                int y2 = barBottom - StatsCharts.segHeight(bucket.input() + bucket.output(), chartHeight, maxTotal);
                int y3 = barBottom - StatsCharts.segHeight(
                        bucket.input() + bucket.output() + bucket.cacheRead(), chartHeight, maxTotal);
                int y4 = barBottom - StatsCharts.segHeight(total, chartHeight, maxTotal);
                String title = StatsCharts.bucketRangeLabel(bucket);
                StatsCharts.drawSegment(graphics, barX, y1, barX + barWidth, barBottom, StatsCharts.COLOR_INPUT,
                        title, "gui.tlm_agent_tweaks.stats.legend.input", bucket.input(), mouseX, mouseY, tooltip);
                StatsCharts.drawSegment(graphics, barX, y2, barX + barWidth, y1, StatsCharts.COLOR_OUTPUT,
                        title, "gui.tlm_agent_tweaks.stats.legend.output", bucket.output(), mouseX, mouseY, tooltip);
                StatsCharts.drawSegment(graphics, barX, y3, barX + barWidth, y2, StatsCharts.COLOR_CACHE_READ,
                        title, "gui.tlm_agent_tweaks.stats.legend.cache_read", bucket.cacheRead(), mouseX, mouseY, tooltip);
                StatsCharts.drawSegment(graphics, barX, y4, barX + barWidth, y3, StatsCharts.COLOR_CACHE_WRITE,
                        title, "gui.tlm_agent_tweaks.stats.legend.cache_write", bucket.cacheWrite(), mouseX, mouseY, tooltip);
            }
            // 折线点（画在柱上，取桶中心）
            int centerX = barX + barWidth / 2;
            if (drawCny) {
                int py = bottom - (int) Math.round(chartHeight * (bucket.costCny() / maxCost));
                graphics.fill(centerX - 1, py - 1, centerX + 2, py + 2, StatsCharts.COLOR_CNY);
                if (i > 0 && buckets.get(i - 1).anyCnyPrice()) {
                    int prevX = left + (i - 1) * pitch + (pitch - barWidth) / 2 + barWidth / 2;
                    int prevY = bottom - (int) Math.round(chartHeight * (buckets.get(i - 1).costCny() / maxCost));
                    StatsCharts.drawLine(graphics, prevX, prevY, centerX, py, StatsCharts.COLOR_CNY);
                }
            }
            if (drawUsd) {
                int py = bottom - (int) Math.round(chartHeight * (bucket.costUsd() / maxCost));
                graphics.fill(centerX - 1, py - 1, centerX + 2, py + 2, StatsCharts.COLOR_USD);
                if (i > 0 && buckets.get(i - 1).anyUsdPrice()) {
                    int prevX = left + (i - 1) * pitch + (pitch - barWidth) / 2 + barWidth / 2;
                    int prevY = bottom - (int) Math.round(chartHeight * (buckets.get(i - 1).costUsd() / maxCost));
                    StatsCharts.drawLine(graphics, prevX, prevY, centerX, py, StatsCharts.COLOR_USD);
                }
            }
            // 日期标签：仅画首、尾（及中间若干，视宽度）
            int labelStep = Math.max(1, 180 / Math.max(1, pitch));
            if (i == 0 || i == barCount - 1 || i % labelStep == 0) {
                LocalDate date = LocalDate.ofEpochDay(bucket.startDay());
                String label = monthUnit
                        ? date.getYear() + "-" + date.getMonthValue()
                        : date.getMonthValue() + "/" + date.getDayOfMonth();
                graphics.drawCenteredString(font, label, centerX, bottom + 3, 0x888888);
            }
        }

        // 成本线图例
        int legendX = left;
        if (drawCny) {
            graphics.fill(legendX, 78, legendX + 8, 84, StatsCharts.COLOR_CNY);
            graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.cost.cny"), legendX + 11, 78, 0xCCCCCC);
            legendX += 60;
        }
        if (drawUsd) {
            graphics.fill(legendX, 78, legendX + 8, 84, StatsCharts.COLOR_USD);
            graphics.drawString(font, Component.translatable("gui.tlm_agent_tweaks.stats.cost.usd"), legendX + 11, 78, 0xCCCCCC);
        }
    }
}

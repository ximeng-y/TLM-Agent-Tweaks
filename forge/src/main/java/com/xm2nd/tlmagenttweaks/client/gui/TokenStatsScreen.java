package com.xm2nd.tlmagenttweaks.client.gui;

import com.xm2nd.tlmagenttweaks.client.ClientStatsCache;
import com.xm2nd.tlmagenttweaks.common.GranularityUtil;
import com.xm2nd.tlmagenttweaks.network.RequestTokenStatsPayload;
import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.time.LocalDate;
import java.util.List;

/**
 * token 消耗详情页（仅管理员可拉到数据）：三视图。
 * <ol>
 *   <li>玩家排行：一玩家一行 + 四色横向堆叠柱</li>
 *   <li>玩家明细：四类 token、缓存命中率、双币种成本</li>
 *   <li>服务器总览：时间窗口选择 + 四色竖向堆叠柱 + 双币种成本折线</li>
 * </ol>
 * 全部用 GuiGraphics 自绘，无贴图依赖。
 */
@OnlyIn(Dist.CLIENT)
public class TokenStatsScreen extends Screen {

    // 四类 token 的固定配色（ARGB）
    private static final int COLOR_INPUT = 0xFF4FC3F7;
    private static final int COLOR_OUTPUT = 0xFF81C784;
    private static final int COLOR_CACHE_READ = 0xFFFFB74D;
    private static final int COLOR_CACHE_WRITE = 0xFFBA68C8;
    // 成本折线配色（与柱色区分开）
    private static final int COLOR_CNY = 0xFFFF5252;
    private static final int COLOR_USD = 0xFFFFD740;

    private static final int[] WINDOW_OPTIONS = GranularityUtil.WINDOWS;
    private static final String[] WINDOW_LABELS =
            {"1d", "3d", "7d", "14d", "30d", "60d", "90d", "180d", "365d", "all"};

    private static final int TAB_RANKING = RequestTokenStatsPayload.VIEW_RANKING;
    private static final int TAB_PLAYER = RequestTokenStatsPayload.VIEW_PLAYER_DETAIL;
    private static final int TAB_SERVER = RequestTokenStatsPayload.VIEW_SERVER;

    private final Screen parent;
    private int tab = TAB_RANKING;
    private int windowIndex = 4; // 默认 30d

    // 渲染快照（volatile 读取一次后局部使用）
    private volatile List<TokenStatsDataPayload.RankEntry> ranking = List.of();
    private volatile TokenStatsDataPayload.PlayerDetailDto detail;
    private volatile List<TokenStatsDataPayload.BucketDto> buckets = List.of();
    private volatile boolean loading;
    private volatile boolean denied;
    private Component hoverTooltip;

    private long consumedVersion = -1;
    private int rankingScroll;
    private String selectedPlayerUuid = "";
    private TokenStatsDataPayload.PlayerDetailDto consumedDetail;
    private Button playerSelector;
    private boolean playerMenuOpen;
    private int playerMenuScroll;
    /** 首次 init 时按当前页签发数据请求；rebuildWidgets 重入 init 时不再重复请求 */
    private boolean initialLoadDone;

    public TokenStatsScreen() {
        this(null);
    }

    public TokenStatsScreen(Screen parent) {
        super(Component.translatable("gui.tlm_agent_tweaks.stats.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.playerSelector = null;
        this.playerMenuOpen = false;
        int top = 24;
        int w = 80;
        int gap = 4;
        int total = w * 3 + gap * 2;
        int left = this.width / 2 - total / 2;
        this.addRenderableWidget(tabButton(left, top, TAB_RANKING, "gui.tlm_agent_tweaks.stats.tab.ranking"));
        this.addRenderableWidget(tabButton(left + w + gap, top, TAB_PLAYER, "gui.tlm_agent_tweaks.stats.tab.player"));
        this.addRenderableWidget(tabButton(left + (w + gap) * 2, top, TAB_SERVER, "gui.tlm_agent_tweaks.stats.tab.server"));

        // 返回（右上角）
        this.addRenderableWidget(Button.builder(Component.translatable("gui.tlm_agent_tweaks.stats.back"), b -> this.onClose())
                .bounds(this.width - 60, 6, 50, 16).build());

        if (this.tab == TAB_PLAYER) {
            int cy = this.height / 2 - 40;
            this.playerSelector = this.addRenderableWidget(Button.builder(playerSelectionLabel(), b -> {
                this.playerMenuOpen = !this.playerMenuOpen;
                if (this.playerMenuOpen) {
                    int index = 0;
                    for (int i = 0; i < this.ranking.size(); i++) {
                        if (this.ranking.get(i).uuid().equals(this.selectedPlayerUuid)) {
                            index = i;
                            break;
                        }
                    }
                    this.playerMenuScroll = Math.min(index, Math.max(0, this.ranking.size() - playerMenuRows()));
                }
            }).bounds(this.width / 2 - 110, cy, 220, 20).build());
            this.playerSelector.active = !this.ranking.isEmpty();
        } else if (this.tab == TAB_SERVER) {
            this.addRenderableWidget(Button.builder(
                            Component.translatable("gui.tlm_agent_tweaks.stats.server.window", WINDOW_LABELS[this.windowIndex]),
                            b -> {
                                this.windowIndex = (this.windowIndex + 1) % WINDOW_OPTIONS.length;
                                requestServer();
                            })
                    .bounds(this.width / 2 - 55, 48, 110, 18).build());
        }

        // 首次进入即按当前页签发数据请求（此后切页签由 switchTab 触发）
        if (!this.initialLoadDone) {
            this.initialLoadDone = true;
            this.refreshRequest();
        }
    }

    private Button tabButton(int x, int y, int view, String key) {
        Button button = Button.builder(Component.translatable(key), b -> switchTab(view))
                .bounds(x, y, 80, 18).build();
        button.active = this.tab != view;
        return button;
    }

    private void switchTab(int view) {
        this.tab = view;
        this.rankingScroll = 0;
        this.refreshRequest();
        this.rebuildWidgets();
    }

    /** 按当前页签发请求 */
    private void refreshRequest() {
        switch (this.tab) {
            case TAB_RANKING -> TweakPackets.sendToServer(
                    new RequestTokenStatsPayload(TAB_RANKING, "", 0));
            case TAB_PLAYER -> {
                // 排行数据用于玩家下拉列表，以 UUID 保持选中对象。
                TweakPackets.sendToServer(new RequestTokenStatsPayload(TAB_RANKING, "", 0));
                List<TokenStatsDataPayload.RankEntry> list = this.ranking;
                if (!list.isEmpty()) {
                    requestDetail(list.stream().anyMatch(p -> p.uuid().equals(this.selectedPlayerUuid))
                            ? this.selectedPlayerUuid : list.get(0).uuid());
                } else {
                    this.loading = true;
                }
            }
            case TAB_SERVER -> requestServer();
            default -> {
            }
        }
    }

    private void requestDetail(String uuid) {
        this.selectedPlayerUuid = uuid;
        this.detail = null;
        this.consumedDetail = ClientStatsCache.detail();
        this.loading = true;
        if (this.playerSelector != null) {
            this.playerSelector.setMessage(playerSelectionLabel());
        }
        TweakPackets.sendToServer(new RequestTokenStatsPayload(TAB_PLAYER, uuid, 0));
    }

    private void requestServer() {
        this.buckets = List.of();
        this.hoverTooltip = null;
        this.loading = true;
        TweakPackets.sendToServer(new RequestTokenStatsPayload(TAB_SERVER, "", WINDOW_OPTIONS[this.windowIndex]));
        this.rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.hoverTooltip = null;
        super.render(graphics, mouseX, mouseY, partialTick);
        pollCache();

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        if (this.denied) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.tlm_agent_tweaks.stats.denied"),
                    this.width / 2, this.height / 2, 0xFF5555);
            return;
        }
        if (this.loading) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.tlm_agent_tweaks.stats.loading"),
                    this.width / 2, this.height / 2, 0xAAAAAA);
        }

        switch (this.tab) {
            case TAB_RANKING -> renderRanking(graphics, mouseX, mouseY);
            case TAB_PLAYER -> renderPlayerDetail(graphics);
            case TAB_SERVER -> renderServer(graphics, mouseX, mouseY);
            default -> {
            }
        }
        renderPlayerMenu(graphics, mouseX, mouseY);
        if (this.hoverTooltip != null) {
            graphics.renderTooltip(this.font,
                    this.font.split(this.hoverTooltip, Math.min(260, Math.max(1, this.width - 16))),
                    mouseX, mouseY);
        }
    }

    /** 消费服务端下发的最新数据 */
    private void pollCache() {
        long version = ClientStatsCache.version();
        if (version == this.consumedVersion) {
            return;
        }
        this.consumedVersion = version;
        if (ClientStatsCache.denied()) {
            this.denied = true;
            this.loading = false;
            return;
        }
        this.denied = false;
        int lastView = ClientStatsCache.lastViewType();
        // 一帧内可能先后收到排行和明细，不能只消费 lastView 而遗漏排行响应。
        List<TokenStatsDataPayload.RankEntry> cachedRanking = ClientStatsCache.ranking();
        if (this.ranking != cachedRanking || lastView == TAB_RANKING) {
            this.ranking = cachedRanking;
            if (this.tab == TAB_PLAYER) {
                if (this.ranking.isEmpty()) {
                    this.selectedPlayerUuid = "";
                    this.detail = null;
                    this.loading = false;
                } else if (this.ranking.stream().noneMatch(p -> p.uuid().equals(this.selectedPlayerUuid))) {
                    requestDetail(this.ranking.get(0).uuid());
                }
                this.playerMenuOpen = false;
                if (this.playerSelector != null) {
                    this.playerSelector.active = !this.ranking.isEmpty();
                    this.playerSelector.setMessage(playerSelectionLabel());
                }
            }
        }
        TokenStatsDataPayload.PlayerDetailDto cachedDetail = ClientStatsCache.detail();
        if (cachedDetail != this.consumedDetail) {
            this.consumedDetail = cachedDetail;
            if (cachedDetail != null && cachedDetail.uuid().equals(this.selectedPlayerUuid)) {
                this.detail = cachedDetail;
                this.loading = false;
            }
        }
        if (lastView == TAB_SERVER) {
            if (ClientStatsCache.serverWindowDays() == WINDOW_OPTIONS[this.windowIndex]) {
                this.buckets = ClientStatsCache.buckets();
                this.loading = false;
            }
        }
        // 排行响应也让玩家页签的等待结束（但明细未到时仍显示加载）
        if (this.tab == TAB_RANKING && lastView == TAB_RANKING) {
            this.loading = false;
        }
    }

    // ---------- 排行视图 ----------

    private static final int RANKING_ROW_HEIGHT = 22;

    private void renderRanking(GuiGraphics graphics, int mouseX, int mouseY) {
        List<TokenStatsDataPayload.RankEntry> list = this.ranking;
        renderLegend(graphics, 48);

        if (list.isEmpty()) {
            if (!this.loading) {
                graphics.drawCenteredString(this.font,
                        Component.translatable("gui.tlm_agent_tweaks.stats.empty"),
                        this.width / 2, this.height / 2, 0x999999);
            }
            return;
        }

        int left = 20;
        int right = this.width - 20;
        int top = 62;
        int bottom = this.height - 30;
        int visible = Math.max(1, (bottom - top) / RANKING_ROW_HEIGHT);
        this.rankingScroll = Math.max(0, Math.min(this.rankingScroll, Math.max(0, list.size() - visible)));

        long maxTotal = 1;
        for (TokenStatsDataPayload.RankEntry entry : list) {
            maxTotal = Math.max(maxTotal, entry.total());
        }

        graphics.enableScissor(left - 4, top - 4, right + 4, bottom + 4);
        for (int i = 0; i < visible && this.rankingScroll + i < list.size(); i++) {
            TokenStatsDataPayload.RankEntry entry = list.get(this.rankingScroll + i);
            int y = top + i * RANKING_ROW_HEIGHT;
            String name = entry.name() == null || entry.name().isBlank() ? entry.uuid() : entry.name();
            graphics.drawString(this.font, name, left, y, 0xFFFFFF);
            graphics.drawString(this.font, NumUtil.formatTokens(entry.total()), right - this.font.width(NumUtil.formatTokens(entry.total())), y, 0xCCCCCC);
            drawStackedBarH(graphics, left + 90, y + 9, right - left - 90 - 50, 6,
                    entry.input(), entry.output(), entry.cacheRead(), entry.cacheWrite(), maxTotal,
                    mouseX, mouseY, name);
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.playerMenuOpen && this.playerSelector != null) {
            int top = playerMenuTop();
            int rows = playerMenuRows();
            if (button == 0 && mouseX >= this.playerSelector.getX()
                    && mouseX < this.playerSelector.getX() + this.playerSelector.getWidth()
                    && mouseY >= top && mouseY < top + rows * 20) {
                int index = this.playerMenuScroll + (int) ((mouseY - top) / 20);
                requestDetail(this.ranking.get(index).uuid());
            }
            this.playerMenuOpen = false;
            return true;
        }
        // 记录点击位置对应的排行行，render 中换算（简化处理：直接在点击时计算行号）
        if (this.tab == TAB_RANKING && button == 0) {
            List<TokenStatsDataPayload.RankEntry> list = this.ranking;
            int left = 20;
            int right = this.width - 20;
            int top = 62;
            int bottom = this.height - 30;
            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY < bottom) {
                int row = (int) ((mouseY - top) / RANKING_ROW_HEIGHT) + this.rankingScroll;
                if (row >= 0 && row < list.size()) {
                    this.tab = TAB_PLAYER;
                    requestDetail(list.get(row).uuid());
                    this.rebuildWidgets();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ---------- 玩家明细视图 ----------

    private void renderPlayerDetail(GuiGraphics graphics) {
        TokenStatsDataPayload.PlayerDetailDto dto = this.detail;
        if (dto == null) {
            if (!this.loading) {
                graphics.drawCenteredString(this.font,
                        Component.translatable("gui.tlm_agent_tweaks.stats.empty"),
                        this.width / 2, this.height / 2, 0x999999);
            }
            return;
        }
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        int left = cx - 110;
        int startY = y + 34;
        int line = 14;
        graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.player.input"), left, startY, 0xFFFFFF);
        graphics.drawString(this.font, NumUtil.formatTokens(dto.input()), left + 220, startY, 0x4FC3F7);
        graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.player.output"), left, startY + line, 0xFFFFFF);
        graphics.drawString(this.font, NumUtil.formatTokens(dto.output()), left + 220, startY + line, 0x81C784);
        graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.player.cache_read"), left, startY + line * 2, 0xFFFFFF);
        graphics.drawString(this.font, NumUtil.formatTokens(dto.cacheRead()), left + 220, startY + line * 2, 0xFFB74D);
        graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.player.cache_write"), left, startY + line * 3, 0xFFFFFF);
        graphics.drawString(this.font, NumUtil.formatTokens(dto.cacheWrite()), left + 220, startY + line * 3, 0xBA68C8);
        graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.player.requests"), left, startY + line * 4, 0xFFFFFF);
        graphics.drawString(this.font, String.valueOf(dto.requests()), left + 220, startY + line * 4, 0xCCCCCC);
        graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.player.hit_rate"), left, startY + line * 5, 0xFFFFFF);
        graphics.drawString(this.font, NumUtil.formatPercent(dto.cacheHitRate()), left + 220, startY + line * 5, 0xFFD54F);

        int costY = startY + line * 6 + 6;
        if (dto.anyCnyPrice()) {
            graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.cost.cny"), left, costY, 0xFFFFFF);
            graphics.drawString(this.font, "¥" + NumUtil.formatCost(dto.costCny()), left + 220, costY, COLOR_CNY);
        }
        if (dto.anyUsdPrice()) {
            graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.cost.usd"), left, costY + line, 0xFFFFFF);
            graphics.drawString(this.font, "$" + NumUtil.formatCost(dto.costUsd()), left + 220, costY + line, COLOR_USD);
        }
    }

    // ---------- 服务器总览视图 ----------

    private void renderServer(GuiGraphics graphics, int mouseX, int mouseY) {
        renderLegend(graphics, 74);

        List<TokenStatsDataPayload.BucketDto> list = this.buckets;
        if (list.isEmpty()) {
            if (!this.loading) {
                graphics.drawCenteredString(this.font,
                        Component.translatable("gui.tlm_agent_tweaks.stats.empty"),
                        this.width / 2, this.height / 2, 0x999999);
            }
            return;
        }

        int left = 30;
        int right = this.width - 30;
        int top = 92;
        int bottom = this.height - 34;

        long maxTotal = 1;
        for (TokenStatsDataPayload.BucketDto bucket : list) {
            maxTotal = Math.max(maxTotal, bucket.total());
        }
        // 成本折线的量纲（有任一货币价格才画线）
        boolean drawCny = list.stream().anyMatch(TokenStatsDataPayload.BucketDto::anyCnyPrice);
        boolean drawUsd = list.stream().anyMatch(TokenStatsDataPayload.BucketDto::anyUsdPrice);
        double maxCost = 0;
        for (TokenStatsDataPayload.BucketDto bucket : list) {
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
        int barCount = list.size();
        int pitch = Math.max(3, chartWidth / Math.max(1, barCount));
        int barWidth = Math.max(1, pitch - 1);

        boolean monthUnit = !list.isEmpty() && list.get(0).unitOrdinal() == GranularityUtil.BucketUnit.MONTH.ordinal();
        for (int i = 0; i < barCount; i++) {
            TokenStatsDataPayload.BucketDto bucket = list.get(i);
            int barX = left + i * pitch + (pitch - barWidth) / 2;
            long total = bucket.total();
            if (total > 0) {
                int barBottom = bottom;
                int y1 = barBottom - segHeight(bucket.input(), chartHeight, maxTotal);
                int y2 = barBottom - segHeight(bucket.input() + bucket.output(), chartHeight, maxTotal);
                int y3 = barBottom - segHeight(bucket.input() + bucket.output() + bucket.cacheRead(),
                        chartHeight, maxTotal);
                int y4 = barBottom - segHeight(total, chartHeight, maxTotal);
                String title = bucketRangeLabel(bucket);
                drawTokenSegment(graphics, barX, y1, barX + barWidth, barBottom, COLOR_INPUT,
                        title, "gui.tlm_agent_tweaks.stats.legend.input", bucket.input(), mouseX, mouseY);
                drawTokenSegment(graphics, barX, y2, barX + barWidth, y1, COLOR_OUTPUT,
                        title, "gui.tlm_agent_tweaks.stats.legend.output", bucket.output(), mouseX, mouseY);
                drawTokenSegment(graphics, barX, y3, barX + barWidth, y2, COLOR_CACHE_READ,
                        title, "gui.tlm_agent_tweaks.stats.legend.cache_read", bucket.cacheRead(), mouseX, mouseY);
                drawTokenSegment(graphics, barX, y4, barX + barWidth, y3, COLOR_CACHE_WRITE,
                        title, "gui.tlm_agent_tweaks.stats.legend.cache_write", bucket.cacheWrite(), mouseX, mouseY);
            }
            // 折线点（画在柱上，取桶中心）
            int centerX = barX + barWidth / 2;
            if (drawCny) {
                int py = bottom - (int) Math.round(chartHeight * (bucket.costCny() / maxCost));
                graphics.fill(centerX - 1, py - 1, centerX + 2, py + 2, COLOR_CNY);
                if (i > 0 && list.get(i - 1).anyCnyPrice()) {
                    int prevX = left + (i - 1) * pitch + (pitch - barWidth) / 2 + barWidth / 2;
                    int prevY = bottom - (int) Math.round(chartHeight * (list.get(i - 1).costCny() / maxCost));
                    drawLine(graphics, prevX, prevY, centerX, py, COLOR_CNY);
                }
            }
            if (drawUsd) {
                int py = bottom - (int) Math.round(chartHeight * (bucket.costUsd() / maxCost));
                graphics.fill(centerX - 1, py - 1, centerX + 2, py + 2, COLOR_USD);
                if (i > 0 && list.get(i - 1).anyUsdPrice()) {
                    int prevX = left + (i - 1) * pitch + (pitch - barWidth) / 2 + barWidth / 2;
                    int prevY = bottom - (int) Math.round(chartHeight * (list.get(i - 1).costUsd() / maxCost));
                    drawLine(graphics, prevX, prevY, centerX, py, COLOR_USD);
                }
            }
            // 日期标签：仅画首、尾（及中间若干，视宽度）
            int labelStep = Math.max(1, 180 / Math.max(1, pitch));
            if (i == 0 || i == barCount - 1 || i % labelStep == 0) {
                LocalDate date = LocalDate.ofEpochDay(bucket.startDay());
                String label = monthUnit
                        ? date.getYear() + "-" + date.getMonthValue()
                        : date.getMonthValue() + "/" + date.getDayOfMonth();
                graphics.drawCenteredString(this.font, label, centerX, bottom + 3, 0x888888);
            }
        }

        // 成本线图例
        int legendX = left;
        if (drawCny) {
            graphics.fill(legendX, 78, legendX + 8, 84, COLOR_CNY);
            graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.cost.cny"), legendX + 11, 78, 0xCCCCCC);
            legendX += 60;
        }
        if (drawUsd) {
            graphics.fill(legendX, 78, legendX + 8, 84, COLOR_USD);
            graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.stats.cost.usd"), legendX + 11, 78, 0xCCCCCC);
        }
    }

    /** 四色图例（排行与服务器视图共用） */
    private void renderLegend(GuiGraphics graphics, int y) {
        int x = this.width / 2 - 130;
        int[][] colors = {{COLOR_INPUT, 0}, {COLOR_OUTPUT, 0}, {COLOR_CACHE_READ, 0}, {COLOR_CACHE_WRITE, 0}};
        String[] keys = {
                "gui.tlm_agent_tweaks.stats.legend.input",
                "gui.tlm_agent_tweaks.stats.legend.output",
                "gui.tlm_agent_tweaks.stats.legend.cache_read",
                "gui.tlm_agent_tweaks.stats.legend.cache_write"
        };
        for (int i = 0; i < colors.length; i++) {
            graphics.fill(x, y, x + 8, y + 6, colors[i][0]);
            graphics.drawString(this.font, Component.translatable(keys[i]), x + 11, y - 1, 0xCCCCCC);
            x += 70;
        }
    }

    /** 横向四色堆叠条 */
    private void drawStackedBarH(GuiGraphics graphics, int x, int y, int maxWidth, int height,
                                 long input, long output, long cacheRead, long cacheWrite, long maxTotal,
                                 int mouseX, int mouseY, String title) {
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
        drawTokenSegment(graphics, x, y, b1, y + height, COLOR_INPUT,
                title, "gui.tlm_agent_tweaks.stats.legend.input", input, mouseX, mouseY);
        drawTokenSegment(graphics, b1, y, b2, y + height, COLOR_OUTPUT,
                title, "gui.tlm_agent_tweaks.stats.legend.output", output, mouseX, mouseY);
        drawTokenSegment(graphics, b2, y, b3, y + height, COLOR_CACHE_READ,
                title, "gui.tlm_agent_tweaks.stats.legend.cache_read", cacheRead, mouseX, mouseY);
        drawTokenSegment(graphics, b3, y, b4, y + height, COLOR_CACHE_WRITE,
                title, "gui.tlm_agent_tweaks.stats.legend.cache_write", cacheWrite, mouseX, mouseY);
    }

    /** 绘制一个真实可见色块，并使用完全相同的半开矩形做命中。 */
    private void drawTokenSegment(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color,
                                  String title, String typeKey, long value, int mouseX, int mouseY) {
        if (x1 >= x2 || y1 >= y2) {
            return;
        }
        graphics.fill(x1, y1, x2, y2, color);
        if (mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2) {
            this.hoverTooltip = Component.literal(title)
                    .append("\n")
                    .append(Component.translatable(typeKey))
                    .append(": ")
                    .append(Long.toString(value));
        }
    }

    /** 使用桶的真实起止日期；单日桶只显示一天。 */
    private static String bucketRangeLabel(TokenStatsDataPayload.BucketDto bucket) {
        LocalDate start = LocalDate.ofEpochDay(bucket.startDay());
        LocalDate end = LocalDate.ofEpochDay(bucket.endDay());
        if (bucket.unitOrdinal() == GranularityUtil.BucketUnit.DAY.ordinal() || start.equals(end)) {
            return start.toString();
        }
        return start + " ~ " + end;
    }

    /** 柱段高度换算 */
    private static int segHeight(long value, int chartHeight, long maxTotal) {
        return (int) Math.round(chartHeight * (value / (double) maxTotal));
    }

    /** 逐像素画折线段（图表规模小，性能可接受） */
    private static void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (this.playerMenuOpen) {
            this.playerMenuScroll = Math.max(0, Math.min(this.ranking.size() - playerMenuRows(),
                    this.playerMenuScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (this.tab == TAB_RANKING) {
            this.rankingScroll = Math.max(0, this.rankingScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    private Component playerSelectionLabel() {
        for (TokenStatsDataPayload.RankEntry player : this.ranking) {
            if (player.uuid().equals(this.selectedPlayerUuid)) {
                return Component.literal(player.name() + " ▾");
            }
        }
        return Component.translatable("gui.tlm_agent_tweaks.stats.empty");
    }

    private int playerMenuTop() {
        return this.playerSelector.getY() + this.playerSelector.getHeight() + 2;
    }

    private int playerMenuRows() {
        return Math.min(this.ranking.size(), Math.max(1, Math.min(8, (this.height - playerMenuTop() - 8) / 20)));
    }

    private void renderPlayerMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!this.playerMenuOpen || this.playerSelector == null || this.ranking.isEmpty()) {
            return;
        }
        int left = this.playerSelector.getX();
        int right = left + this.playerSelector.getWidth();
        int top = playerMenuTop();
        int rows = playerMenuRows();
        graphics.flush();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 350);
            graphics.fill(left, top, right, top + rows * 20, 0xFF202020);
            for (int i = 0; i < rows; i++) {
                var player = this.ranking.get(this.playerMenuScroll + i);
                int y = top + i * 20;
                boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + 20;
                if (hover || player.uuid().equals(this.selectedPlayerUuid)) {
                    graphics.fill(left, y, right, y + 20, hover ? 0xFF555555 : 0xFF383838);
                }
                graphics.drawString(this.font, this.font.plainSubstrByWidth(player.name(), right - left - 12),
                        left + 6, y + 6, 0xFFFFFF);
            }
            if (this.ranking.size() > rows) {
                int thumbHeight = Math.max(4, rows * 20 * rows / this.ranking.size());
                int thumbTop = top + (rows * 20 - thumbHeight) * this.playerMenuScroll / (this.ranking.size() - rows);
                graphics.fill(right - 2, thumbTop, right, thumbTop + thumbHeight, 0xFFAAAAAA);
            }
            graphics.flush();
        } finally {
            graphics.pose().popPose();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && this.playerMenuOpen) {
            this.playerMenuOpen = false;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

package com.xm2nd.tlmagenttweaks.client.gui;

import com.xm2nd.tlmagenttweaks.client.ClientStatsCache;
import com.xm2nd.tlmagenttweaks.client.gui.stats.PlayerDetailView;
import com.xm2nd.tlmagenttweaks.client.gui.stats.PlayerDropdown;
import com.xm2nd.tlmagenttweaks.client.gui.stats.RankingView;
import com.xm2nd.tlmagenttweaks.client.gui.stats.ServerView;
import com.xm2nd.tlmagenttweaks.common.GranularityUtil;
import com.xm2nd.tlmagenttweaks.network.RequestTokenStatsPayload;
import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * token 消耗详情页（仅管理员可拉到数据）：三视图。
 * <ol>
 *   <li>玩家排行：一玩家一行 + 四色横向堆叠柱</li>
 *   <li>玩家明细：四类 token、缓存命中率、双币种成本</li>
 *   <li>服务器总览：时间窗口选择 + 四色竖向堆叠柱 + 双币种成本折线</li>
 * </ol>
 * 本类负责页签状态、数据请求与消费；各视图的绘制在
 * {@link RankingView} / {@link PlayerDetailView} / {@link ServerView}，
 * 共享图表原语在 {@link com.xm2nd.tlmagenttweaks.client.gui.stats.StatsCharts}，
 * 玩家下拉浮层在 {@link PlayerDropdown}。全部 GuiGraphics 自绘，无贴图依赖。
 */
@OnlyIn(Dist.CLIENT)
public class TokenStatsScreen extends Screen {

    private static final int[] WINDOW_OPTIONS = GranularityUtil.WINDOWS;
    private static final String[] WINDOW_LABELS =
            {"1d", "3d", "7d", "14d", "30d", "60d", "90d", "180d", "365d", "all"};

    private static final int TAB_RANKING = RequestTokenStatsPayload.VIEW_RANKING;
    private static final int TAB_PLAYER = RequestTokenStatsPayload.VIEW_PLAYER_DETAIL;
    private static final int TAB_SERVER = RequestTokenStatsPayload.VIEW_SERVER;

    private final Screen parent;
    private final RankingView rankingView = new RankingView();
    private final PlayerDropdown playerDropdown = new PlayerDropdown();
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
    private String selectedPlayerUuid = "";
    private TokenStatsDataPayload.PlayerDetailDto consumedDetail;
    private Button playerSelector;
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
        this.playerDropdown.close();
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
                int index = 0;
                List<TokenStatsDataPayload.RankEntry> list = this.ranking;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).uuid().equals(this.selectedPlayerUuid)) {
                        index = i;
                        break;
                    }
                }
                this.playerDropdown.toggle(index, list.size(), this.playerSelector, this.height);
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
        this.rankingView.resetScroll();
        this.refreshRequest();
        this.rebuildWidgets();
    }

    /** 按当前页签发请求 */
    private void refreshRequest() {
        switch (this.tab) {
            case TAB_RANKING -> PacketDistributor.sendToServer(
                    new RequestTokenStatsPayload(TAB_RANKING, "", 0));
            case TAB_PLAYER -> {
                // 排行数据用于玩家下拉列表，以 UUID 保持选中对象。
                PacketDistributor.sendToServer(new RequestTokenStatsPayload(TAB_RANKING, "", 0));
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
        PacketDistributor.sendToServer(new RequestTokenStatsPayload(TAB_PLAYER, uuid, 0));
    }

    private void requestServer() {
        this.buckets = List.of();
        this.hoverTooltip = null;
        this.loading = true;
        PacketDistributor.sendToServer(new RequestTokenStatsPayload(TAB_SERVER, "", WINDOW_OPTIONS[this.windowIndex]));
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
            case TAB_RANKING -> this.rankingView.render(graphics, this.font, this.width, this.height,
                    mouseX, mouseY, this.ranking, this.loading, this::recordTooltip);
            case TAB_PLAYER -> PlayerDetailView.render(graphics, this.font, this.width, this.height,
                    this.detail, this.loading);
            case TAB_SERVER -> ServerView.render(graphics, this.font, this.width, this.height,
                    mouseX, mouseY, this.buckets, this.loading, this::recordTooltip);
            default -> {
            }
        }
        this.playerDropdown.render(graphics, this.font, this.height, mouseX, mouseY,
                this.playerSelector, this.ranking, this.selectedPlayerUuid);
        if (this.hoverTooltip != null) {
            graphics.renderTooltip(this.font,
                    this.font.split(this.hoverTooltip, Math.min(260, Math.max(1, this.width - 16))),
                    mouseX, mouseY);
        }
    }

    /** 视图绘制命中悬停时登记 tooltip，由页面统一渲染。 */
    private void recordTooltip(Component tooltip) {
        this.hoverTooltip = tooltip;
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
                this.playerDropdown.close();
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.playerDropdown.isOpen() && this.playerSelector != null) {
            // 开着的下拉浮层吞掉本次点击：点中菜单行则选中该玩家，随后一律关闭。
            this.playerDropdown.clicked(mouseX, mouseY, button, this.ranking, this.playerSelector,
                    this.height, this::requestDetail);
            return true;
        }
        if (this.tab == TAB_RANKING && button == 0) {
            int row = this.rankingView.rowAt((int) mouseX, (int) mouseY, this.width, this.height,
                    this.ranking.size());
            if (row >= 0) {
                this.tab = TAB_PLAYER;
                requestDetail(this.ranking.get(row).uuid());
                this.rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.playerDropdown.isOpen() && this.playerSelector != null) {
            return this.playerDropdown.scrolled(scrollY, this.ranking.size(), this.playerSelector, this.height);
        }
        if (this.tab == TAB_RANKING) {
            this.rankingView.scrolled(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private Component playerSelectionLabel() {
        for (TokenStatsDataPayload.RankEntry player : this.ranking) {
            if (player.uuid().equals(this.selectedPlayerUuid)) {
                return Component.literal(player.name() + " ▾");
            }
        }
        return Component.translatable("gui.tlm_agent_tweaks.stats.empty");
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && this.playerDropdown.isOpen()) {
            this.playerDropdown.close();
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

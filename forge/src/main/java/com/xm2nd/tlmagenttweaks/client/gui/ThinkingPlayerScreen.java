package com.xm2nd.tlmagenttweaks.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.client.ClientThinkingConfigCache;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLevel;
import com.xm2nd.tlmagenttweaks.network.SaveThinkingChoicePayload;
import com.xm2nd.tlmagenttweaks.network.ThinkingConfigPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 玩家侧思考选择屏：点击可配置的模型名后展开的「思考开关 → 思考强度」两级选择。
 * <p>
 * 管理员未允许开关思考时不显示开关区，直接选择思考强度（issue #3 的例外分支）；
 * 确认后把决定发往服务端（服务端会再次校验配置范围），模型本身的选中已在
 * 点击模型名时由 TLM 原逻辑完成。
 */
@OnlyIn(Dist.CLIENT)
public class ThinkingPlayerScreen extends Screen {

    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_HEIGHT = 200;
    private static final int TITLE_HEIGHT = 20;
    private static final int ROW_HEIGHT = 18;

    private final Screen parent;
    private final EntityMaid maid;
    private final String siteId;
    private final String modelId;
    private final ModelThinkingConfig config;

    private boolean thinkingEnabled = true;
    private ThinkingLevel selectedLevel = ThinkingLevel.DEFAULT;

    private final List<FlatColorButton> levelButtons = new ArrayList<>();

    private FlatColorButton onButton;
    private FlatColorButton offButton;
    private int panelLeft;
    private int panelRight;
    private int panelTop;
    private int panelBottom;

    public ThinkingPlayerScreen(Screen parent, EntityMaid maid, String siteId, String modelId,
                                ModelThinkingConfig config) {
        super(Component.translatable("gui.tlm_agent_tweaks.player_thinking.title"));
        this.parent = parent;
        this.maid = maid;
        this.siteId = siteId == null ? "" : siteId;
        this.modelId = modelId == null ? "" : modelId;
        this.config = config == null ? ModelThinkingConfig.unconfigured() : config;
        // 默认开（issue #3）；管理员不允许开关思考时本就不显示开关区，思考隐含开启
        this.thinkingEnabled = true;
        // 初始强度：允许 default 时取 default，否则按枚举顺序取第一个被允许的档位，
        // 不依赖 Set.copyOf 的迭代顺序
        this.selectedLevel = initialLevel(this.config);
        // 回显女仆已存的思考选择（随配置快照同步）：站点/模型与当前不一致时不回显；
        // 不允许开关时始终保持隐含开启，旧的「关闭」记录不得把页面显示为关闭；
        // 已存强度仍被允许时保留，未知或已被移除时保持上面选出的合法初始值
        ThinkingConfigPayload.CurrentChoice stored = ClientThinkingConfigCache.current(this.maid.getId());
        if (stored != null && stored.siteId().equals(this.siteId) && stored.modelId().equals(this.modelId)) {
            this.thinkingEnabled = !this.config.toggleAllowed() || stored.enabled();
            ThinkingLevel storedLevel = ThinkingLevel.fromId(stored.levelId());
            if (this.thinkingEnabled && this.config.allows(storedLevel)) {
                this.selectedLevel = storedLevel;
            }
        }
    }

    /** 初始强度：允许 default 时取 default，否则按 ThinkingLevel 枚举顺序取第一个被允许的档位。 */
    private static ThinkingLevel initialLevel(ModelThinkingConfig config) {
        if (config.allows(ThinkingLevel.DEFAULT)) {
            return ThinkingLevel.DEFAULT;
        }
        for (ThinkingLevel level : ThinkingLevel.values()) {
            if (config.allows(level)) {
                return level;
            }
        }
        return ThinkingLevel.DEFAULT;
    }

    /** 强度区是否有实际选择意义：思考开启且允许集合不是单独的 {default}。 */
    private boolean showLevelSection() {
        return this.thinkingEnabled && !this.config.levels().equals(Set.of(ThinkingLevel.DEFAULT));
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.levelButtons.clear();

        int panelWidth = Math.min(PANEL_WIDTH, Math.max(180, this.width - 24));
        int contentHeight = contentHeight();
        int panelHeight = Math.min(PANEL_HEIGHT + Math.max(0, contentHeight - 120),
                Math.max(150, this.height - 24));
        this.panelLeft = (this.width - panelWidth) / 2;
        this.panelRight = this.panelLeft + panelWidth;
        this.panelTop = Math.max(6, (this.height - panelHeight) / 2);
        this.panelBottom = this.panelTop + panelHeight;

        int contentLeft = this.panelLeft + 12;
        int contentWidth = panelWidth - 24;
        int y = this.panelTop + TITLE_HEIGHT + 10;

        if (this.config.toggleAllowed()) {
            this.onButton = this.addRenderableWidget(new FlatColorButton(
                    contentLeft, y, contentWidth / 2 - 6, ROW_HEIGHT,
                    Component.translatable("gui.tlm_agent_tweaks.player_thinking.on"),
                    b -> this.selectEnabled(true)));
            this.offButton = this.addRenderableWidget(new FlatColorButton(
                    contentLeft + contentWidth / 2 + 6, y, contentWidth / 2 - 6, ROW_HEIGHT,
                    Component.translatable("gui.tlm_agent_tweaks.player_thinking.off"),
                    b -> this.selectEnabled(false)));
            y += ROW_HEIGHT + 12;
        }

        if (showLevelSection()) {
            for (ThinkingLevel level : this.config.levels()) {
                FlatColorButton button = this.addRenderableWidget(new FlatColorButton(
                        contentLeft, y, contentWidth, ROW_HEIGHT,
                        Component.translatable("gui.tlm_agent_tweaks.thinking.level." + level.id()),
                        b -> this.selectLevel(level)));
                this.levelButtons.add(button);
                y += ROW_HEIGHT + 2;
            }
        }

        this.addRenderableWidget(new FlatColorButton(
                this.panelLeft + 12, this.panelBottom - 26, 60, 16,
                Component.translatable("gui.tlm_agent_tweaks.thinking.cancel"), b -> this.onClose()));
        this.addRenderableWidget(new FlatColorButton(
                this.panelRight - 72, this.panelBottom - 26, 60, 16,
                Component.translatable("gui.tlm_agent_tweaks.thinking.confirm"), b -> this.confirm()));

        refreshSelection();
    }

    private int contentHeight() {
        int rows = (this.config.toggleAllowed() ? 2 : 1) + Math.max(0, this.config.levels().size() - 1);
        return TITLE_HEIGHT + 14 + rows * (ROW_HEIGHT + 2) + 34;
    }

    private void selectEnabled(boolean enabled) {
        this.thinkingEnabled = enabled;
        this.init(this.minecraft, this.width, this.height);
    }

    private void selectLevel(ThinkingLevel level) {
        this.selectedLevel = level;
        refreshSelection();
    }

    private void refreshSelection() {
        if (this.onButton != null) {
            this.onButton.setSelect(this.thinkingEnabled);
        }
        if (this.offButton != null) {
            this.offButton.setSelect(!this.thinkingEnabled);
        }
        for (int i = 0; i < this.levelButtons.size(); i++) {
            // 行序与 config.levels() 一致，按下标还原级别做高亮
            ThinkingLevel level = this.config.levels().stream().skip(i).findFirst().orElse(null);
            this.levelButtons.get(i).setSelect(level != null && level == this.selectedLevel);
        }
    }

    private void confirm() {
        ThinkingLevel level = this.thinkingEnabled ? this.selectedLevel : ThinkingLevel.DEFAULT;
        TweakPackets.sendToServer(new SaveThinkingChoicePayload(
                this.maid.getId(), this.siteId, this.modelId, this.thinkingEnabled, level.id()));
        // 本地同步更新缓存：同一界面会话内再次打开选择屏时按最新选择回显，下次快照到达后仍以服务端为准
        ClientThinkingConfigCache.acceptCurrent(new ThinkingConfigPayload.CurrentChoice(
                this.maid.getId(), this.siteId, this.modelId, this.thinkingEnabled, level.id()));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.fillGradient(0, 0, this.width, this.height, 0x60101010, 0x60101010);

        graphics.fillGradient(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom,
                0xE0202020, 0xE0202020);
        graphics.fillGradient(this.panelLeft, this.panelTop, this.panelRight, this.panelTop + TITLE_HEIGHT,
                0xFF3A3A55, 0xFF3A3A55);
        graphics.drawString(this.font, truncate(this.modelId), this.panelLeft + 8, this.panelTop + 6, 0xFFF3EFE0);

        int contentLeft = this.panelLeft + 12;
        int y = this.panelTop + TITLE_HEIGHT + 10;
        if (this.config.toggleAllowed()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.tlm_agent_tweaks.player_thinking.switch_label"),
                    contentLeft, y - 9, 0xFF777777);
            y += ROW_HEIGHT + 12;
        }
        if (showLevelSection()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.tlm_agent_tweaks.player_thinking.level_label"),
                    contentLeft, y - 9, 0xFF777777);
        }

        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private String truncate(String text) {
        int maxWidth = this.panelRight - this.panelLeft - 16;
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = result + String.valueOf(text.charAt(i)) + suffix;
            if (this.font.width(candidate) > maxWidth) {
                break;
            }
            result.append(text.charAt(i));
        }
        return result + suffix;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

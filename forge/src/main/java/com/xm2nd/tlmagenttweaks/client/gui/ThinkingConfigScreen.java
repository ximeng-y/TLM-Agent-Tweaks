package com.xm2nd.tlmagenttweaks.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLevel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 单模型思考配置弹窗（站点编辑器的管理员入口）。
 * <p>
 * 左侧勾选「可开关思考模式」，右侧勾选支持的思考强度（默认仅 default）；
 * 确认后经回调写回站点编辑器 Mixin 的暂存表，随 buildSite 一起持久化。
 */
@OnlyIn(Dist.CLIENT)
public class ThinkingConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 264;
    private static final int PANEL_HEIGHT = 210;
    private static final int TITLE_HEIGHT = 20;
    private static final int LEVEL_ROW_HEIGHT = 20;

    private final Screen parent;
    private final String model;
    private final Consumer<ModelThinkingConfig> onApply;

    private Checkbox toggleCheckbox;
    private Checkbox[] levelBoxes = new Checkbox[ThinkingLevel.values().length];
    private ThinkingLevel[] boxLevels = ThinkingLevel.values();

    private int panelLeft;
    private int panelRight;
    private int panelTop;
    private int panelBottom;

    public ThinkingConfigScreen(Screen parent, String model, ModelThinkingConfig current,
                                Consumer<ModelThinkingConfig> onApply) {
        super(Component.translatable("gui.tlm_agent_tweaks.thinking.title"));
        this.parent = parent;
        this.model = model == null ? "" : model;
        ModelThinkingConfig config = current == null ? ModelThinkingConfig.unconfigured() : current;
        this.onApply = onApply;
        this.initialToggle = config.toggleAllowed();
        this.initialLevels = config.levels();
    }

    private final boolean initialToggle;
    private final Set<ThinkingLevel> initialLevels;

    @Override
    protected void init() {
        this.clearWidgets();

        int panelWidth = Math.min(PANEL_WIDTH, Math.max(220, this.width - 24));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(160, this.height - 24));
        this.panelLeft = (this.width - panelWidth) / 2;
        this.panelRight = this.panelLeft + panelWidth;
        this.panelTop = Math.max(6, (this.height - panelHeight) / 2);
        this.panelBottom = this.panelTop + panelHeight;

        int contentLeft = this.panelLeft + 12;
        int columnLeft = this.panelLeft + panelWidth / 2 + 4;

        this.toggleCheckbox = this.addRenderableWidget(new Checkbox(
                contentLeft, this.panelTop + TITLE_HEIGHT + 16, panelWidth / 2 - 16, 16,
                Component.translatable("gui.tlm_agent_tweaks.thinking.toggle_allowed"), this.initialToggle));

        ThinkingLevel[] levels = ThinkingLevel.values();
        for (int i = 0; i < levels.length; i++) {
            ThinkingLevel level = levels[i];
            this.levelBoxes[i] = this.addRenderableWidget(new Checkbox(
                    columnLeft, this.panelTop + TITLE_HEIGHT + 4 + i * LEVEL_ROW_HEIGHT,
                    panelWidth / 2 - 16, 14,
                    Component.translatable("gui.tlm_agent_tweaks.thinking.level." + level.id()),
                    this.initialLevels.contains(level)));
        }

        this.addRenderableWidget(new FlatColorButton(
                this.panelRight - 104, this.panelBottom - 26, 48, 16,
                Component.translatable("gui.tlm_agent_tweaks.thinking.cancel"), b -> this.onClose()));
        this.addRenderableWidget(new FlatColorButton(
                this.panelRight - 52, this.panelBottom - 26, 48, 16,
                Component.translatable("gui.tlm_agent_tweaks.thinking.confirm"), b -> this.confirm()));
    }

    private void confirm() {
        boolean toggleAllowed = this.toggleCheckbox != null && this.toggleCheckbox.selected();
        Set<ThinkingLevel> levels = new LinkedHashSet<>();
        for (int i = 0; i < this.levelBoxes.length; i++) {
            Checkbox box = this.levelBoxes[i];
            if (box != null && box.selected()) {
                levels.add(this.boxLevels[i]);
            }
        }
        if (levels.isEmpty()) {
            levels.add(ThinkingLevel.DEFAULT);
        }
        if (this.onApply != null) {
            this.onApply.accept(new ModelThinkingConfig(toggleAllowed, levels));
        }
        this.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        // 灰暗遮罩突出弹窗可读性（1.20.1 无原生模糊 API，用遮罩近似）
        graphics.fillGradient(0, 0, this.width, this.height, 0x60101010, 0x60101010);

        graphics.fillGradient(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom,
                0xE0202020, 0xE0202020);
        // 标题条与正文色差区分
        graphics.fillGradient(this.panelLeft, this.panelTop, this.panelRight, this.panelTop + TITLE_HEIGHT,
                0xFF3A3A55, 0xFF3A3A55);

        graphics.drawString(this.font, truncate(this.model), this.panelLeft + 8,
                this.panelTop + 6, 0xFFF3EFE0);

        graphics.drawString(this.font,
                Component.translatable("gui.tlm_agent_tweaks.thinking.levels_label"),
                this.panelLeft + (this.panelRight - this.panelLeft) / 2 + 4,
                this.panelTop + TITLE_HEIGHT - 12, 0xFF777777);

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

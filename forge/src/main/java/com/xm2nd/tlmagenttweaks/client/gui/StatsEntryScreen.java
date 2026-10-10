package com.xm2nd.tlmagenttweaks.client.gui;

import com.xm2nd.tlmagenttweaks.client.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Agent Tweaks 设置页：承载 Token 统计入口，并在新版 Self-Talk 存在时
 * 提供统一的左上角页面导航。
 */
@OnlyIn(Dist.CLIENT)
public class StatsEntryScreen extends Screen {

    private final Screen parent;

    public StatsEntryScreen(Screen parent) {
        super(Component.translatable("gui.tlm_agent_tweaks.stats_entry.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 与新版 Self-Talk 合装时，统一页签由本页自行注册
        SettingsNavigationController.addTweaksPageTabs(this::addRenderableWidget);

        int cx = this.width / 2;
        int cy = this.height / 2;
        // 三行自上而下：思考链开关、Token 统计、返回
        this.addRenderableWidget(Button.builder(thinkingLabel(), b -> {
                    ClientConfig.setShowReasoning(!ClientConfig.showReasoning());
                    b.setMessage(thinkingLabel());
                })
                .tooltip(Tooltip.create(
                        Component.translatable("gui.tlm_agent_tweaks.button.thinking.tooltip")))
                .bounds(cx - 100, cy - 36, 200, 20).build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlm_agent_tweaks.button.token_stats"),
                        b -> this.minecraft.setScreen(new TokenStatsScreen(this)))
                .bounds(cx - 100, cy - 10, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.tlm_agent_tweaks.stats.back"),
                        b -> this.onClose())
                .bounds(cx - 100, cy + 16, 200, 20).build());
    }

    /** 每次 init 都读取当前配置，页面重开或 resize 不会恢复默认值。 */
    private static Component thinkingLabel() {
        return Component.translatable(ClientConfig.showReasoning()
                ? "gui.tlm_agent_tweaks.button.thinking_on"
                : "gui.tlm_agent_tweaks.button.thinking_off");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
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

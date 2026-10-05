package com.xm2nd.tlmagenttweaks.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

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
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlm_agent_tweaks.button.token_stats"),
                        b -> this.minecraft.setScreen(new TokenStatsScreen(this)))
                .bounds(cx - 100, cy - 10, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.tlm_agent_tweaks.stats.back"),
                        b -> this.onClose())
                .bounds(cx - 100, cy + 16, 200, 20).build());
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

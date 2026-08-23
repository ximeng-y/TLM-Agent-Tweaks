package com.xm2nd.tlmagenttweaks.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 统计入口兜底界面：仅在未安装 Self-Talk 时由 💬 按钮打开。
 * （安装 Self-Talk 时统计入口按钮由条件 mixin 直接注入其设置屏）
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

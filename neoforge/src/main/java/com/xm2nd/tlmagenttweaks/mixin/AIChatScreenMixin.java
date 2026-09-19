package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.gui.StatsEntryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 在 AI 聊天输入界面左侧按钮组末尾追加 💬 按钮（与 Self-Talk 的按钮同位置）。
 * <p>
 * 兼容策略：装了 maid_self_talk 时它自己会注册同位置的 💬 按钮，此处让位不注册，
 * 双方按钮互斥注册，绝不重叠。
 */
@Mixin(AIChatScreen.class)
public abstract class AIChatScreenMixin {

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addStatsEntryButton(CallbackInfo ci) {
        if (ModList.get().isLoaded("maid_self_talk")) {
            // Self-Talk 已注册同位置的 💬 按钮，统计入口由其设置屏的条件 mixin 提供
            return;
        }
        AIChatScreen screen = (AIChatScreen) (Object) this;
        // 与 AIChatScreen.init 相同的布局参数：左组 3 个按钮之后追加第 4 个
        int size = 18;
        int inputX = screen.width / 2 - 165;
        int inputY = screen.height / 2 + 58;
        int leftX = inputX - 8 + 3 * (size + 2);
        int y = inputY - 28;

        FlatColorButton button = new FlatColorButton(leftX, y, size, size,
                Component.literal("💬"), b ->
                Minecraft.getInstance().setScreen(new StatsEntryScreen(screen)))
                .setTooltips("gui.tlm_agent_tweaks.button.stats_entry.tip");
        tlmAgentTweaks$addWidgetReflect(screen, button);
    }

    /**
     * Screen.addRenderableWidget 为 protected 且定义在父类，
     * mixin 的 @Shadow 对继承方法解析不稳定，此处直接反射调用。
     * 方法名带前缀避免与同样注入 AIChatScreen 的 Self-Talk mixin
     * 的私有方法在合并时发生同名冲突。
     */
    private static void tlmAgentTweaks$addWidgetReflect(Screen screen, AbstractWidget widget) {
        try {
            Method method = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            method.setAccessible(true);
            method.invoke(screen, widget);
        } catch (ReflectiveOperationException e) {
            TlmAgentTweaks.LOGGER.warn("Failed to add stats entry button to AIChatScreen", e);
        }
    }
}

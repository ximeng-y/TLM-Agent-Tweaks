package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.gui.SelfTalkNavigationBridge;
import com.xm2nd.tlmagenttweaks.client.gui.SettingsNavigationController;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 在 AI 聊天输入界面左侧按钮组末尾追加 Agent Tweaks 入口。
 * <p>
 * Self-Talk 已安装时保留其第 4 槽，本 mod 使用第 5 槽提供直接进入 Tweaks 的入口；
 * 接入设置导航桥后，两个入口仍共用页面切换导航。
 */
@Mixin(AIChatScreen.class)
public abstract class AIChatScreenMixin {

    @Shadow
    @Final
    private EntityMaid maid;

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addTweaksEntryButton(CallbackInfo ci) {
        boolean selfTalkLoaded = ModList.get().isLoaded("maid_self_talk");
        AIChatScreen screen = (AIChatScreen) (Object) this;
        // 与 AIChatScreen.init 相同的布局参数：单装用第 4 槽，兼装 Self-Talk 用第 5 槽
        int size = 18;
        int inputX = screen.width / 2 - 165;
        int inputY = screen.height / 2 + 58;
        int slot = selfTalkLoaded ? 4 : 3;
        int leftX = inputX - 8 + slot * (size + 2);
        int y = inputY - 28;
        String tooltipKey = selfTalkLoaded && !SelfTalkNavigationBridge.isAvailable()
                ? "gui.tlm_agent_tweaks.button.legacy_settings_entry.tip"
                : "gui.tlm_agent_tweaks.button.settings_entry.tip";

        FlatColorButton button = new FlatColorButton(leftX, y, size, size,
                Component.literal("🧩"), b ->
                SettingsNavigationController.openTweaks(this.maid, screen))
                .setTooltips(tooltipKey);
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

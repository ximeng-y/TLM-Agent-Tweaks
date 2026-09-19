package com.xm2nd.tlmagenttweaks.mixin.compat;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.gui.TokenStatsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 在 Self-Talk 的玩家设置屏（💬 按钮进入）底部追加「Token 统计」入口按钮。
 * <p>
 * 目标类属于 maid_self_talk：字符串目标引用 + {@link Pseudo}（目标类不存在时
 * 自动跳过本 mixin），编译期与运行期均零依赖 Self-Talk。
 * 布局：8 个开关按钮占 colTop ~ colTop+184（colTop = height/2-92，矮屏钳制为 30），
 * 状态提示文字在 height/2+100 居中，本按钮放 height/2+112 避免盖住提示。
 */
@Pseudo
@Mixin(targets = "com.maidmod.selftalk.client.SelfTalkPlayerSettingsScreen", remap = false)
public abstract class SelfTalkPlayerSettingsScreenMixin {

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addStatsButton(CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        Button button = Button.builder(
                        Component.translatable("gui.tlm_agent_tweaks.button.token_stats"),
                        b -> Minecraft.getInstance().setScreen(new TokenStatsScreen()))
                .bounds(screen.width / 2 - 100, screen.height / 2 + 112, 200, 20)
                .build();
        try {
            Method method = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            method.setAccessible(true);
            method.invoke(screen, button);
        } catch (ReflectiveOperationException e) {
            TlmAgentTweaks.LOGGER.warn("Failed to add token stats button to SelfTalkPlayerSettingsScreen", e);
        }
    }
}

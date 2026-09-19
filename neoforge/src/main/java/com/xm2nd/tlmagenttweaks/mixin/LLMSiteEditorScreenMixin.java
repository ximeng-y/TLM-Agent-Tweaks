package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.LLMSiteEditorScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.gui.PriceConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 在 TLM 的 LLM 站点编辑器底部按钮行（添加模型与保存之间）追加「价格」按钮，
 * 打开本项目的价格编辑屏。
 * <p>
 * 编辑屏中的模型列表从管理员配置的可用站点表（ClientAvailableSitesSync）读取，
 * 与 LLM 模型选择弹窗同源；新建未保存的站点无法立即设价。
 */
@Mixin(LLMSiteEditorScreen.class)
public abstract class LLMSiteEditorScreenMixin {

    @Shadow
    @Final
    private LLMSite sourceSite;

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addPriceButton(CallbackInfo ci) {
        LLMSiteEditorScreen screen = (LLMSiteEditorScreen) (Object) this;
        // 与 LLMSiteEditorScreen 相同的布局参数：面板 400×230 居中，
        // 底部按钮行 y = startY + 206；「添加模型」止于 startX+108，「保存」起于 startX+200
        int startX = (screen.width - 400) / 2;
        int bottomY = (screen.height - 230) / 2 + 206;

        FlatColorButton button = new FlatColorButton(startX + 112, bottomY, 84, 20,
                net.minecraft.network.chat.Component.translatable("gui.tlm_agent_tweaks.button.price"),
                b -> Minecraft.getInstance().setScreen(new PriceConfigScreen(this.sourceSite.id(), screen)));
        addRenderableWidgetReflect(screen, button);
    }

    private static void addRenderableWidgetReflect(Screen screen, AbstractWidget widget) {
        try {
            Method method = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            method.setAccessible(true);
            method.invoke(screen, widget);
        } catch (ReflectiveOperationException e) {
            TlmAgentTweaks.LOGGER.warn("Failed to add price button to LLMSiteEditorScreen", e);
        }
    }
}

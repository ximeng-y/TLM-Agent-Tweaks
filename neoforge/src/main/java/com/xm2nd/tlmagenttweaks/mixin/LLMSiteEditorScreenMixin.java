package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.LLMSiteEditorScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.gui.LLMSiteProtocolScreen;
import com.xm2nd.tlmagenttweaks.client.gui.PriceConfigScreen;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.protocol.UrlMode;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolBridge;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolLLMSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * 在 TLM 的 LLM 站点编辑器底部拆分出价格和协议两个入口。
 * <p>
 * 原 84px 价格槽改为两个 40px 按钮，协议按钮打开独立设置屏；
 * 站点编辑面板本身不增加任何越界控件，原始输入框、模型区和按钮布局保持不变。
 */
@Mixin(LLMSiteEditorScreen.class)
public abstract class LLMSiteEditorScreenMixin {

    @Shadow
    @Final
    private LLMSite sourceSite;

    @Shadow
    @Final
    private boolean createMode;

    @Shadow
    private EditBox urlInput;

    @Unique
    private SiteProtocol tlmAgentTweaks$protocol;

    @Unique
    private UrlMode tlmAgentTweaks$urlMode;

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addSiteButtons(CallbackInfo ci) {
        LLMSiteEditorScreen screen = (LLMSiteEditorScreen) (Object) this;
        this.tlmAgentTweaks$ensureProtocolState();

        // 原底部按钮行 y = startY + 206；112..196 原本是单个 84px 价格按钮。
        int startX = (screen.width - 400) / 2;
        int bottomY = (screen.height - 230) / 2 + 206;

        FlatColorButton priceButton = new FlatColorButton(startX + 112, bottomY, 40, 20,
                Component.translatable("gui.tlm_agent_tweaks.button.price"),
                b -> Minecraft.getInstance().setScreen(new PriceConfigScreen(this.sourceSite.id(), screen)));
        FlatColorButton protocolButton = new FlatColorButton(startX + 156, bottomY, 40, 20,
                Component.translatable("gui.tlm_agent_tweaks.button.protocol"),
                b -> Minecraft.getInstance().setScreen(new LLMSiteProtocolScreen(
                        screen,
                        this.sourceSite.id(),
                        this::tlmAgentTweaks$currentUrl,
                        this.tlmAgentTweaks$protocol,
                        this.tlmAgentTweaks$urlMode,
                        (protocol, urlMode) -> {
                            this.tlmAgentTweaks$protocol = protocol;
                            this.tlmAgentTweaks$urlMode = urlMode;
                        })));

        addRenderableWidgetReflect(screen, priceButton);
        addRenderableWidgetReflect(screen, protocolButton);
    }

    @Inject(method = "buildSite", at = @At("RETURN"), cancellable = true, remap = false)
    private void tlmAgentTweaks$applyProtocolConfig(CallbackInfoReturnable<LLMSite> cir) {
        LLMSite built = cir.getReturnValue();
        if (built instanceof LLMOpenAISite openAiSite) {
            cir.setReturnValue(ProtocolBridge.withProtocol(openAiSite,
                    this.tlmAgentTweaks$protocol, this.tlmAgentTweaks$urlMode));
        }
    }

    @Unique
    private void tlmAgentTweaks$ensureProtocolState() {
        if (this.tlmAgentTweaks$protocol != null && this.tlmAgentTweaks$urlMode != null) {
            return;
        }
        if (this.sourceSite instanceof ProtocolLLMSite protocolSite) {
            this.tlmAgentTweaks$protocol = protocolSite.protocol();
            this.tlmAgentTweaks$urlMode = protocolSite.urlMode();
        } else if (this.createMode) {
            this.tlmAgentTweaks$protocol = SiteProtocol.CHAT_COMPLETIONS;
            this.tlmAgentTweaks$urlMode = UrlMode.BASE;
        } else {
            // 旧站点没有这两个字段，保持原 OpenAI 完整 URL 语义。
            this.tlmAgentTweaks$protocol = SiteProtocol.CHAT_COMPLETIONS;
            this.tlmAgentTweaks$urlMode = UrlMode.EXACT;
        }
    }

    @Unique
    private String tlmAgentTweaks$currentUrl() {
        if (this.urlInput != null && this.urlInput.getValue() != null && !this.urlInput.getValue().isBlank()) {
            return this.urlInput.getValue();
        }
        return this.sourceSite == null ? "" : this.sourceSite.url();
    }

    private static void addRenderableWidgetReflect(Screen screen, AbstractWidget widget) {
        try {
            Method method = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            method.setAccessible(true);
            method.invoke(screen, widget);
        } catch (ReflectiveOperationException e) {
            TlmAgentTweaks.LOGGER.warn("Failed to add widget to LLMSiteEditorScreen", e);
        }
    }
}

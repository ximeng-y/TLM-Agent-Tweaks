package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.LLMSiteEditorScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.gui.PriceConfigScreen;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.protocol.UrlMode;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolBridge;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolLLMSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 在 TLM 的 LLM 站点编辑器上追加「价格」按钮和协议/URL 模式选择控件。
 * <p>
 * 编辑屏中的模型列表从管理员配置的可用站点表（ClientAvailableSitesSync）读取，
 * 与 LLM 模型选择弹窗同源；新建未保存的站点无法立即设价。
 * 协议控件位于 400×230 面板下方，不覆盖 TLM 原控件；选择值在窗口 resize 后保持。
 */
@Mixin(LLMSiteEditorScreen.class)
public abstract class LLMSiteEditorScreenMixin {

    @Shadow
    @Final
    private LLMSite sourceSite;

    @Unique
    private SiteProtocol tlmAgentTweaks$protocol;

    @Unique
    private UrlMode tlmAgentTweaks$urlMode;

    @Unique
    private FlatColorButton tlmAgentTweaks$protocolButton;

    @Unique
    private FlatColorButton tlmAgentTweaks$urlModeButton;

    @Unique
    private final List<FlatColorButton> tlmAgentTweaks$protocolOptions = new ArrayList<>();

    @Unique
    private final List<FlatColorButton> tlmAgentTweaks$urlModeOptions = new ArrayList<>();

    @Unique
    private boolean tlmAgentTweaks$protocolMenuOpen;

    @Unique
    private boolean tlmAgentTweaks$urlModeMenuOpen;

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addPriceButton(CallbackInfo ci) {
        LLMSiteEditorScreen screen = (LLMSiteEditorScreen) (Object) this;
        this.tlmAgentTweaks$ensureProtocolState();

        // 与 LLMSiteEditorScreen 相同的布局参数：面板 400×230 居中，
        // 底部按钮行 y = startY + 206；「添加模型」止于 startX+108，「保存」起于 startX+200
        int startX = (screen.width - 400) / 2;
        int startY = (screen.height - 230) / 2;
        int bottomY = startY + 206;

        FlatColorButton button = new FlatColorButton(startX + 112, bottomY, 84, 20,
                net.minecraft.network.chat.Component.translatable("gui.tlm_agent_tweaks.button.price"),
                b -> Minecraft.getInstance().setScreen(new PriceConfigScreen(this.sourceSite.id(), screen)));
        addRenderableWidgetReflect(screen, button);

        // 在 400×230 面板下方新增协议选择行，不覆盖 TLM 原有输入框、模型区或底部按钮。
        this.tlmAgentTweaks$addProtocolControls(screen, startX, startY);
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
        } else {
            // 旧站点没有这两个字段，保持原 OpenAI 完整 URL 语义。
            this.tlmAgentTweaks$protocol = SiteProtocol.CHAT_COMPLETIONS;
            this.tlmAgentTweaks$urlMode = UrlMode.EXACT;
        }
    }

    @Unique
    private void tlmAgentTweaks$addProtocolControls(LLMSiteEditorScreen screen, int startX, int startY) {
        this.tlmAgentTweaks$protocolOptions.clear();
        this.tlmAgentTweaks$urlModeOptions.clear();
        this.tlmAgentTweaks$protocolMenuOpen = false;
        this.tlmAgentTweaks$urlModeMenuOpen = false;

        int rowY = startY + 230 + 4;
        this.tlmAgentTweaks$protocolButton = new FlatColorButton(startX + 12, rowY, 180, 20,
                this.tlmAgentTweaks$protocolLabel(), b -> this.tlmAgentTweaks$toggleProtocolMenu());
        this.tlmAgentTweaks$urlModeButton = new FlatColorButton(startX + 208, rowY, 180, 20,
                this.tlmAgentTweaks$urlModeLabel(), b -> this.tlmAgentTweaks$toggleUrlModeMenu());
        addRenderableWidgetReflect(screen, this.tlmAgentTweaks$protocolButton);
        addRenderableWidgetReflect(screen, this.tlmAgentTweaks$urlModeButton);

        for (SiteProtocol protocol : SiteProtocol.values()) {
            FlatColorButton option = new FlatColorButton(startX + 12, rowY + 22
                    + this.tlmAgentTweaks$protocolOptions.size() * 20, 180, 20,
                    this.tlmAgentTweaks$protocolName(protocol), b -> this.tlmAgentTweaks$selectProtocol(protocol));
            option.visible = false;
            this.tlmAgentTweaks$protocolOptions.add(option);
            addRenderableWidgetReflect(screen, option);
        }
        for (UrlMode mode : UrlMode.values()) {
            FlatColorButton option = new FlatColorButton(startX + 208, rowY + 22
                    + this.tlmAgentTweaks$urlModeOptions.size() * 20, 180, 20,
                    this.tlmAgentTweaks$urlModeName(mode), b -> this.tlmAgentTweaks$selectUrlMode(mode));
            option.visible = false;
            this.tlmAgentTweaks$urlModeOptions.add(option);
            addRenderableWidgetReflect(screen, option);
        }
        this.tlmAgentTweaks$updateProtocolMenuVisibility();
    }

    @Unique
    private void tlmAgentTweaks$toggleProtocolMenu() {
        this.tlmAgentTweaks$protocolMenuOpen = !this.tlmAgentTweaks$protocolMenuOpen;
        this.tlmAgentTweaks$urlModeMenuOpen = false;
        this.tlmAgentTweaks$updateProtocolMenuVisibility();
    }

    @Unique
    private void tlmAgentTweaks$toggleUrlModeMenu() {
        this.tlmAgentTweaks$urlModeMenuOpen = !this.tlmAgentTweaks$urlModeMenuOpen;
        this.tlmAgentTweaks$protocolMenuOpen = false;
        this.tlmAgentTweaks$updateProtocolMenuVisibility();
    }

    @Unique
    private void tlmAgentTweaks$selectProtocol(SiteProtocol protocol) {
        this.tlmAgentTweaks$protocol = protocol;
        this.tlmAgentTweaks$protocolMenuOpen = false;
        this.tlmAgentTweaks$protocolButton.setMessage(this.tlmAgentTweaks$protocolLabel());
        this.tlmAgentTweaks$updateProtocolMenuVisibility();
    }

    @Unique
    private void tlmAgentTweaks$selectUrlMode(UrlMode mode) {
        this.tlmAgentTweaks$urlMode = mode;
        this.tlmAgentTweaks$urlModeMenuOpen = false;
        this.tlmAgentTweaks$urlModeButton.setMessage(this.tlmAgentTweaks$urlModeLabel());
        this.tlmAgentTweaks$updateProtocolMenuVisibility();
    }

    @Unique
    private void tlmAgentTweaks$updateProtocolMenuVisibility() {
        for (FlatColorButton option : this.tlmAgentTweaks$protocolOptions) {
            option.visible = this.tlmAgentTweaks$protocolMenuOpen;
        }
        for (FlatColorButton option : this.tlmAgentTweaks$urlModeOptions) {
            option.visible = this.tlmAgentTweaks$urlModeMenuOpen;
        }
    }

    @Unique
    private Component tlmAgentTweaks$protocolLabel() {
        return Component.translatable("gui.tlm_agent_tweaks.llm.protocol",
                this.tlmAgentTweaks$protocolName(this.tlmAgentTweaks$protocol));
    }

    @Unique
    private Component tlmAgentTweaks$urlModeLabel() {
        return Component.translatable("gui.tlm_agent_tweaks.llm.url_mode",
                this.tlmAgentTweaks$urlModeName(this.tlmAgentTweaks$urlMode));
    }

    @Unique
    private Component tlmAgentTweaks$protocolName(SiteProtocol protocol) {
        return switch (protocol) {
            case CHAT_COMPLETIONS -> Component.translatable(
                    "gui.tlm_agent_tweaks.llm.protocol.chat_completions");
            case RESPONSES -> Component.translatable("gui.tlm_agent_tweaks.llm.protocol.responses");
            case ANTHROPIC_MESSAGES -> Component.translatable(
                    "gui.tlm_agent_tweaks.llm.protocol.anthropic_messages");
        };
    }

    @Unique
    private Component tlmAgentTweaks$urlModeName(UrlMode mode) {
        return switch (mode) {
            case EXACT -> Component.translatable("gui.tlm_agent_tweaks.llm.url_mode.exact");
            case BASE -> Component.translatable("gui.tlm_agent_tweaks.llm.url_mode.base");
        };
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

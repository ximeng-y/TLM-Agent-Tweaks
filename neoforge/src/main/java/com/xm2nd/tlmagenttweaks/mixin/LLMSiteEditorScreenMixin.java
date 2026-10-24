package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.LLMSiteEditorScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.gui.LLMSiteProtocolScreen;
import com.xm2nd.tlmagenttweaks.client.gui.PriceConfigScreen;
import com.xm2nd.tlmagenttweaks.client.gui.ThinkingConfigScreen;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.protocol.UrlMode;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 TLM 的 LLM 站点编辑器中注入站点协议、单模型价格与思考配置三个入口。
 * <p>
 * 协议按钮放在 URL 输入框右侧（缩短 URL 宽度，不越出面板）；价格与思考配置按钮放在
 * 每行模型删除按钮左侧，不再占用底部原生按钮行。原始输入框、模型区、滚动、
 * 添加/保存/返回按钮布局保持不变。
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
    private EditBox siteIdInput;

    @Shadow
    private EditBox urlInput;

    @Unique
    private SiteProtocol tlmAgentTweaks$protocol;

    @Unique
    private UrlMode tlmAgentTweaks$urlMode;

    /** 每模型思考配置暂存：以模型名文本为键，随 buildSite 一起写回站点。 */
    @Unique
    private final Map<String, ModelThinkingConfig> tlmAgentTweaks$thinkingConfigs = new HashMap<>();

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addSiteButtons(CallbackInfo ci) {
        LLMSiteEditorScreen screen = (LLMSiteEditorScreen) (Object) this;
        this.tlmAgentTweaks$ensureProtocolState();

        // TLM 面板：400x230，left = startX+12，contentWidth = 376
        int startX = (screen.width - 400) / 2;
        int startY = (screen.height - 230) / 2;
        int rowLeft = startX + 12;
        int modelRight = startX + 388;

        // ---- URL 右侧协议按钮：缩短 URL 输入框，按钮落在原 URL 槽右侧边界内 ----
        String protocolLabel = Component.translatable("gui.tlm_agent_tweaks.button.protocol").getString();
        int protocolButtonWidth = Math.max(36, Minecraft.getInstance().font.width(protocolLabel) + 12);
        int urlGap = 5;
        int originalUrlWidth = 244;
        int newUrlWidth = Math.max(96, originalUrlWidth - urlGap - protocolButtonWidth);
        if (this.urlInput != null) {
            // addInput 把内宽再减 12，背景按 innerWidth+12 绘制，右边界 = 参数宽度
            this.urlInput.setWidth(newUrlWidth - 12);
        }
        FlatColorButton protocolButton = new FlatColorButton(
                startX + 144 + newUrlWidth + urlGap, startY + 36, protocolButtonWidth, 18,
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
        addRenderableWidgetReflect(screen, protocolButton);

        // ---- 每行模型价格按钮：只处理本次 init 重建出的可见模型行，不访问私有 ModelRow ----
        List<GuiEventListener> snapshot = new ArrayList<>(screen.children());
        for (GuiEventListener listener : snapshot) {
            if (!(listener instanceof EditBox box)) {
                continue;
            }
            // 模型行 nameBox 位于 modelArea 内（y = rowY+2），站点/URL/秘钥输入框的 y 更靠上
            int boxY = box.getY();
            if (boxY < startY + 100 || boxY > startY + 195 || box.getX() < rowLeft || box.getX() > modelRight) {
                continue;
            }
            int rowY = boxY - 2;

            String priceLabel = Component.translatable("gui.tlm_agent_tweaks.button.price").getString();
            int priceButtonWidth = Math.max(36, Minecraft.getInstance().font.width(priceLabel) + 10);
            // delete 在 right-24，宽 18；价格按钮放在 delete 左侧
            int priceX = modelRight - 24 - 2 - priceButtonWidth;

            // 原生 reasoning 按钮：right-86 处 60x18，存在时左移到价格按钮左侧
            FlatColorButton reasoningButton = null;
            for (GuiEventListener candidate : snapshot) {
                if (candidate instanceof FlatColorButton button
                        && button.getX() == modelRight - 86 && button.getY() == rowY - 4
                        && button.getWidth() == 60 && button.getHeight() == 18) {
                    reasoningButton = button;
                    break;
                }
            }
            if (reasoningButton != null) {
                reasoningButton.setX(priceX - 2 - 60);
            }
            // 思考配置正方形按钮：放在价格/推理按钮链的最左侧
            int anchorLeft = reasoningButton != null ? reasoningButton.getX() : priceX;
            int squareX = anchorLeft - 2 - 18;

            // 据此缩短模型名输入框：真实起点是 rowLeft+6（box.getX()），按该起点算宽，
            // 保证输入框右缘停在按钮左侧 2px，不覆盖价格/思考按钮
            int nameEnd = squareX - 2;
            box.setWidth(Math.max(40, nameEnd - box.getX()));

            final EditBox modelBox = box;
            FlatColorButton priceButton = new FlatColorButton(
                    priceX, rowY - 4, priceButtonWidth, 18,
                    Component.translatable("gui.tlm_agent_tweaks.button.price"),
                    b -> Minecraft.getInstance().setScreen(new PriceConfigScreen(
                            this.siteIdInput == null ? "" : this.siteIdInput.getValue().trim(),
                            modelBox.getValue().trim(), screen)));
            addRenderableWidgetReflect(screen, priceButton);

            FlatColorButton thinkingButton = new FlatColorButton(
                    squareX, rowY - 4, 18, 18,
                    Component.literal("🧠"),
                    b -> Minecraft.getInstance().setScreen(new ThinkingConfigScreen(
                            screen,
                            modelBox.getValue().trim(),
                            this.tlmAgentTweaks$thinkingConfigs.get(modelBox.getValue().trim()),
                            config -> this.tlmAgentTweaks$thinkingConfigs.put(
                                    modelBox.getValue().trim(), config))))
                    .setTooltips("gui.tlm_agent_tweaks.button.thinking.tip");
            addRenderableWidgetReflect(screen, thinkingButton);
        }
    }

    @Inject(method = "buildSite", at = @At("RETURN"), cancellable = true, remap = false)
    private void tlmAgentTweaks$applyProtocolConfig(CallbackInfoReturnable<LLMSite> cir) {
        LLMSite built = cir.getReturnValue();
        if (built instanceof LLMOpenAISite openAiSite) {
            cir.setReturnValue(ProtocolBridge.withProtocol(openAiSite,
                    this.tlmAgentTweaks$protocol, this.tlmAgentTweaks$urlMode,
                    Map.copyOf(this.tlmAgentTweaks$thinkingConfigs)));
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
            this.tlmAgentTweaks$thinkingConfigs.clear();
            this.tlmAgentTweaks$thinkingConfigs.putAll(protocolSite.thinkingConfigs());
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

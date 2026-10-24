package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatSerializable;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.ClientThinkingConfigCache;
import com.xm2nd.tlmagenttweaks.client.gui.SelfTalkNavigationBridge;
import com.xm2nd.tlmagenttweaks.client.gui.SettingsNavigationController;
import com.xm2nd.tlmagenttweaks.client.gui.ThinkingPlayerScreen;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.network.RequestThinkingConfigPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 在 AI 聊天输入界面左侧按钮组末尾追加 Agent Tweaks 入口。
 * <p>
 * Self-Talk 已安装时保留其第 4 槽，本 mod 使用第 5 槽提供直接进入 Tweaks 的入口；
 * 接入设置导航桥后，两个入口仍共用页面切换导航。
 * <p>
 * 另外承担思考选择的展开（issue #3）：打开界面时向服务端请求每模型思考配置快照；
 * 玩家在下拉框中点到「管理员配置过思考项」的模型时，在 TLM 原选中逻辑完成后
 * 弹出「思考开关 → 思考强度」选择屏；未配置思考项的模型行为与原来完全一致。
 */
@Mixin(AIChatScreen.class)
public abstract class AIChatScreenMixin {

    @Shadow
    @Final
    private EntityMaid maid;

    /** applyPopupSelection 的 LLM 分支标记：llmModel 字段写入只发生在该分支。 */
    @Unique
    private boolean tlmAgentTweaks$llmModelClicked;

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

        // 每次打开聊天界面刷新一次思考配置快照；服务端站点配置变更后重开即生效
        PacketDistributor.sendToServer(new RequestThinkingConfigPayload());
    }

    /**
     * 标记本次 applyPopupSelection 走到了 LLM 分支，并照原样完成赋值。
     * TTS/LANGUAGE 分支不写 llmModel，因此不会误弹思考选择。
     */
    @Redirect(method = "applyPopupSelection", at = @At(value = "FIELD",
            target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatSerializable;"
                    + "llmModel:Ljava/lang/String;", opcode = Opcodes.PUTFIELD),
            remap = false)
    private void tlmAgentTweaks$markLlmModelWrite(MaidAIChatSerializable manager, String value) {
        this.tlmAgentTweaks$llmModelClicked = true;
        manager.llmModel = value;
    }

    /**
     * TLM 原逻辑（写 manager、ensureValidSelections、发 SaveMaidAIDataPackage、关弹窗）
     * 全部完成后再展开思考选择，模型选中本身不受影响。
     */
    @Inject(method = "applyPopupSelection", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$openThinkingChooser(CallbackInfo ci) {
        if (!this.tlmAgentTweaks$llmModelClicked) {
            return;
        }
        this.tlmAgentTweaks$llmModelClicked = false;
        MaidAIChatManager manager = this.maid.getAiChatManager();
        ModelThinkingConfig config = ClientThinkingConfigCache.get(manager.llmSite, manager.llmModel);
        if (config == null || !config.configured()) {
            return;
        }
        Minecraft.getInstance().setScreen(new ThinkingPlayerScreen(
                (AIChatScreen) (Object) this, this.maid,
                manager.llmSite, manager.llmModel, config));
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

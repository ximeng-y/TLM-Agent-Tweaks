package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.client.ClientThinkingConfigCache;
import com.xm2nd.tlmagenttweaks.client.gui.SelfTalkNavigationBridge;
import com.xm2nd.tlmagenttweaks.client.gui.SettingsNavigationController;
import com.xm2nd.tlmagenttweaks.client.gui.ThinkingPlayerScreen;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.network.RequestThinkingConfigPayload;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 AI 聊天输入界面左侧按钮组末尾追加 Agent Tweaks 入口。
 * <p>
 * Self-Talk 已安装时保留其第 4 槽，本 mod 使用第 5 槽提供直接进入 Tweaks 的入口；
 * 接入设置导航桥后，两个入口仍共用页面切换导航。
 * <p>
 * 注入点选 TLM 自有私有方法 {@code addLeftButtons}（而非原版 {@code init}）：
 * Forge 1.20.1 生产环境会把覆写原版的方法重命名为 SRG 名（{@code init} → {@code m_7856_}），
 * TLM 自有方法名则保持可读，因此注入点在有/无重映射表时都成立。
 * <p>
 * 本类继承 {@link Screen}（目标类的父类）：mixin 合并时父类不参与合并，仅用于让
 * {@code this.addRenderableWidget(...)} 满足 Java 的 protected 访问规则；字节码里的方法引用
 * 会随 reobf 正确重映射为生产名，而字符串反射在生产环境拿不到原版方法名、会静默失败。
 * mixin 类不会被实例化，构造器仅满足语法。
 * <p>
 * 另外承担思考选择的展开（issue #3）：{@code addLeftButtons} 尾部向服务端请求每模型
 * 思考配置快照；玩家在下拉框中点到「管理员配置过思考项」的模型时，在 TLM 原选中
 * 逻辑完成后弹出「思考开关 → 思考强度」选择屏；未配置思考项的模型行为与原来完全一致。
 */
@Mixin(AIChatScreen.class)
public abstract class AIChatScreenMixin extends Screen {

    @Shadow(remap = false)
    @Final
    private EntityMaid maid;

    /** applyPopupSelection 的 LLM 分支标记：llmModel 字段写入只发生在该分支。 */
    @Unique
    private boolean tlmAgentTweaks$llmModelClicked;

    protected AIChatScreenMixin() {
        super(Component.empty());
    }

    /**
     * {@code addLeftButtons} 方法体会把 {@code leftX} 参数逐步推进（每个按钮后
     * {@code leftX += size + gap}），因此 TAIL 处的 {@code leftX} 已是第 3 个按钮的位置，
     * 第 4 个按钮只需再前进一组间距。
     */
    @Inject(method = "addLeftButtons", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addTweaksEntryButton(int leftX, int y, int size, int gap, CallbackInfo ci) {
        boolean selfTalkLoaded = ModList.get().isLoaded("maid_self_talk");
        // 单装时占第 4 槽；Self-Talk 已占用第 4 槽，本 mod 用第 5 槽
        int slot = selfTalkLoaded ? 2 : 1;
        int x = leftX + slot * (size + gap);
        String tooltipKey = selfTalkLoaded && !SelfTalkNavigationBridge.isAvailable()
                ? "gui.tlm_agent_tweaks.button.legacy_settings_entry.tip"
                : "gui.tlm_agent_tweaks.button.settings_entry.tip";

        FlatColorButton button = new FlatColorButton(x, y, size, size,
                Component.literal("🧩"), b ->
                SettingsNavigationController.openTweaks(this.maid, (AIChatScreen) (Object) this))
                .setTooltips(tooltipKey);
        this.addRenderableWidget(button);

        // 每次构建聊天界面（init 会重建 addLeftButtons）刷新一次思考配置快照
        // （附带当前女仆，服务端回带其已存思考选择）
        TweakPackets.sendToServer(new RequestThinkingConfigPayload(this.maid.getId()));
    }

    /**
     * 标记本次 applyPopupSelection 走到了 LLM 分支，并照原样完成赋值。
     * TTS/LANGUAGE 分支不写 llmModel，因此不会误弹思考选择。
     * <p>
     * target 的 owner 必须写 {@code MaidAIChatManager}（接收者静态类型）而非字段声明类
     * {@code MaidAIChatSerializable}：javac 对继承字段的访问以接收者静态类型生成字段指令
     * owner，写声明类会导致 mixin 匹配不到指令、应用失败，打开 AI 聊天界面即崩溃。
     */
    @Redirect(method = "applyPopupSelection", at = @At(value = "FIELD",
            target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;"
                    + "llmModel:Ljava/lang/String;", opcode = Opcodes.PUTFIELD),
            remap = false)
    private void tlmAgentTweaks$markLlmModelWrite(MaidAIChatManager manager, String value) {
        this.tlmAgentTweaks$llmModelClicked = true;
        manager.llmModel = value;
    }

    /**
     * TLM 原逻辑（写 manager、ensureValidSelections、发 SaveMaidAIDataMessage、关弹窗）
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
}

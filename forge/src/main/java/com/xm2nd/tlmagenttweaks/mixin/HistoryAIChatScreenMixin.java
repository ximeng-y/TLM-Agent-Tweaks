package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.HistoryAIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.client.HistoryCompressionView;
import com.xm2nd.tlmagenttweaks.network.RequestCompressHistoryPayload;
import com.xm2nd.tlmagenttweaks.network.RequestCompressStatePayload;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import net.minecraft.client.gui.GuiGraphics;
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

import java.util.List;

/**
 * 为历史屏预留压缩按钮空间，并以服务端实际任务状态控制按钮。
 * <p>
 * 注入点均选 TLM 自有方法（{@code addButtons} / {@code transformMessage} /
 * {@code getSummaryPanelHeight}）：生产环境会把覆写原版的方法重命名为 SRG 名，
 * TLM 自有方法名保持可读。原版 {@code init} 的 HEAD 逻辑改挂到 {@code addButtons} 之前，
 * 由 {@code transformMessage} 承担刷新路径上的状态重置。
 * <p>
 * 本类继承 {@link Screen} 以便直接调用 protected 的 {@code addRenderableWidget}。
 */
@Mixin(HistoryAIChatScreen.class)
public abstract class HistoryAIChatScreenMixin extends Screen implements HistoryCompressionView {
    /** 轮询间隔（帧数）；改用 render 驱动后按帧计数节流，避免每帧发包。 */
    @Unique
    private static final int tlmAgentTweaks$POLL_INTERVAL = 20;

    @Shadow(remap = false) @Final private EntityMaid maid;
    @Shadow(remap = false) @Final private List<LLMMessage> history;
    @Shadow(remap = false) private String summaryText;
    @Shadow(remap = false) private void transformMessage() {}
    @Shadow(remap = false) private int getClearButtonY() { return 0; }
    @Shadow(remap = false) private int getRightColumnLeft() { return 0; }

    @Unique private FlatColorButton tlmAgentTweaks$compressButton;
    @Unique private boolean tlmAgentTweaks$running = true;
    @Unique private boolean tlmAgentTweaks$refreshing;
    @Unique private int tlmAgentTweaks$pollTicks;

    protected HistoryAIChatScreenMixin() {
        super(Component.empty());
    }

    /**
     * 页面重建时重新开始轮询：外层刷新（{@code tlmAgentTweaks$refreshing}）期间不重置，
     * 否则刷新会把正在进行的任务状态当成新任务。
     */
    @Inject(method = "transformMessage", at = @At("HEAD"), remap = false)
    private void tlmAgentTweaks$prepareStateQuery(CallbackInfo ci) {
        if (!this.tlmAgentTweaks$refreshing) {
            this.tlmAgentTweaks$running = true;
            this.tlmAgentTweaks$pollTicks = 0;
        }
    }

    @Inject(method = "getSummaryPanelHeight", at = @At("RETURN"), cancellable = true, remap = false)
    private void tlmAgentTweaks$reserveButtonSpace(CallbackInfoReturnable<Integer> cir) {
        // 比原版多一个 20px 按钮和 6px 间距，保留原有摘要到按钮的 12px 留白。
        cir.setReturnValue(Math.max(0, Math.min(cir.getReturnValue() - 26,
                this.getClearButtonY() - 26 - 12 - 24)));
    }

    @Inject(method = "addButtons", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addCompressButton(CallbackInfo ci) {
        this.tlmAgentTweaks$compressButton = this.addRenderableWidget(new FlatColorButton(
                this.getRightColumnLeft(), this.getClearButtonY() - 26, 120, 20,
                Component.empty(), button -> {
                    this.tlmAgentTweaks$running = true;
                    this.tlmAgentTweaks$pollTicks = 0;
                    this.tlmAgentTweaks$updateButton();
                    TweakPackets.sendToServer(new RequestCompressHistoryPayload(this.maid.getId()));
                }));
        this.tlmAgentTweaks$updateButton();
        if (!this.tlmAgentTweaks$refreshing) {
            TweakPackets.sendToServer(new RequestCompressStatePayload(this.maid.getId()));
        }
    }

    /**
     * 轮询挂在 TLM 自有方法 {@code renderSummaryPanel} 的 TAIL，而不是新增 {@code tick()} 覆写，
     * 也不注入原版 {@code render}：
     * <ul>
     *   <li>1.20.1 的 {@code HistoryAIChatScreen} 没有 {@code tick()} 覆写，新增的方法无法保证
     *       在生产环境的调用链上被命中；</li>
     *   <li>原版 {@code render} 在生产环境叫 SRG 名，且该项目对 TLM 类的方法名没有重映射来源
     *       （{@code remap = true} 会报找不到映射），只能用 TLM 自有方法名；</li>
     *   <li>{@code renderSummaryPanel} 是 TLM 自有方法、在 {@code render} 内无条件调用，逐帧必走。</li>
     * </ul>
     */
    @Inject(method = "renderSummaryPanel", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$pollCompressState(GuiGraphics graphics, CallbackInfo ci) {
        // 页面关闭后不轮询；重新打开会重新查询，不把 Screen 实例当作任务状态来源。
        if (this.tlmAgentTweaks$running && ++this.tlmAgentTweaks$pollTicks >= tlmAgentTweaks$POLL_INTERVAL) {
            this.tlmAgentTweaks$pollTicks = 0;
            TweakPackets.sendToServer(new RequestCompressStatePayload(this.maid.getId()));
        }
    }

    @Override
    public void tlmAgentTweaks$updateCompression(int entityId, boolean running) {
        if (this.maid.getId() != entityId) {
            return;
        }
        this.tlmAgentTweaks$running = running;
        this.tlmAgentTweaks$updateButton();
        if (!running) {
            // 原版只在构造函数读取历史，单纯 setScreen/init 不会更新这两个快照。
            this.summaryText = this.maid.getAiChatManager().getCompressedSummary();
            this.history.clear();
            this.tlmAgentTweaks$refreshing = true;
            try {
                this.transformMessage();
                // rebuildWidgets 会重新走 init → addButtons，从而重建按钮与继续轮询
                this.rebuildWidgets();
            } finally {
                this.tlmAgentTweaks$refreshing = false;
            }
        }
    }

    @Unique
    private void tlmAgentTweaks$updateButton() {
        if (this.tlmAgentTweaks$compressButton != null) {
            this.tlmAgentTweaks$compressButton.active = !this.tlmAgentTweaks$running;
            this.tlmAgentTweaks$compressButton.setMessage(Component.translatable(this.tlmAgentTweaks$running
                    ? "gui.tlm_agent_tweaks.button.compress_history.pending"
                    : "gui.tlm_agent_tweaks.button.compress_history"));
        }
    }
}

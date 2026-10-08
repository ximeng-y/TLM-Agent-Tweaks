package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.HistoryAIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.client.HistoryCompressionView;
import com.xm2nd.tlmagenttweaks.network.RequestCompressHistoryPayload;
import com.xm2nd.tlmagenttweaks.network.RequestCompressStatePayload;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 为历史屏预留压缩按钮空间，并以服务端实际任务状态控制按钮。 */
@Mixin(HistoryAIChatScreen.class)
public abstract class HistoryAIChatScreenMixin extends Screen implements HistoryCompressionView {
    @Shadow(remap = false) @Final private EntityMaid maid;
    @Shadow(remap = false) @Final private List<LLMMessage> history;
    @Shadow(remap = false) private String summaryText;
    @Shadow(remap = false) protected abstract void init();
    @Shadow(remap = false) private void transformMessage() {}
    @Shadow(remap = false) private int getClearButtonY() { return 0; }
    @Shadow(remap = false) private int getRightColumnLeft() { return 0; }

    @Unique private FlatColorButton tlmAgentTweaks$compressButton;
    @Unique private boolean tlmAgentTweaks$running = true;
    @Unique private boolean tlmAgentTweaks$refreshing;
    @Unique private int tlmAgentTweaks$pollTicks;

    protected HistoryAIChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"), remap = false)
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
                    PacketDistributor.sendToServer(new RequestCompressHistoryPayload(this.maid.getId()));
                }));
        this.tlmAgentTweaks$updateButton();
        if (!this.tlmAgentTweaks$refreshing) {
            PacketDistributor.sendToServer(new RequestCompressStatePayload(this.maid.getId()));
        }
    }

    @Override
    public void tick() {
        super.tick();
        // 页面关闭后不轮询；重新打开会重新查询，不把 Screen 实例当作任务状态来源。
        if (this.tlmAgentTweaks$running && ++this.tlmAgentTweaks$pollTicks >= 20) {
            this.tlmAgentTweaks$pollTicks = 0;
            PacketDistributor.sendToServer(new RequestCompressStatePayload(this.maid.getId()));
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
            this.transformMessage();
            this.tlmAgentTweaks$refreshing = true;
            try {
                this.init();
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

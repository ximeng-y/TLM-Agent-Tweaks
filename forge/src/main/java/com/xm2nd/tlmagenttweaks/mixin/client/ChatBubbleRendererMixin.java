package com.xm2nd.tlmagenttweaks.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.ChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.EntityGraphics;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.IChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.implement.WaitingChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.client.ClientConfig;
import com.xm2nd.tlmagenttweaks.client.thinking.ThinkingBubbleRenderer;
import com.xm2nd.tlmagenttweaks.client.thinking.ThinkingDisplayCache;
import com.xm2nd.tlmagenttweaks.network.ThinkingDisplayPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 把等待气泡的 renderer 在**本次绘制**中替换为思考链 renderer。
 * <p>
 * 统一重定向 {@code render} 内三处 {@code IChatBubbleData.getRenderer(position)}：
 * 先取原 renderer，仅在「开关开启 + 阶段为思考 + 是等待气泡数据 + 缓存快照对应的正是这个
 * 气泡对象」时返回替代 renderer，其余情况原样返回。
 * <p>
 * 替代 renderer 保存在按气泡数据对象区分的绘制缓存里，**不写回
 * {@code WaitingChatBubbleData} 的内部缓存**，因此工具阶段、开关关闭与状态结束时
 * 原 renderer 自然恢复；由于同一帧内 {@code getWidth}/{@code getHeight}/{@code render}
 * 拿到的是同一个实例，替代 renderer 的宽高自然参与原有背景与堆叠计算，
 * 无需另行修改外层背景算法或用全局临时上下文干预。
 */
@Mixin(value = ChatBubbleRenderer.class, remap = false)
public class ChatBubbleRendererMixin {

    /** 本次绘制中用到的替代 renderer，按气泡数据对象区分（只在渲染线程访问，逐帧重建）。 */
    @Unique
    private final Map<Object, ThinkingBubbleRenderer> tlmAgentTweaks$thinkingRenderers = new IdentityHashMap<>();

    @Inject(method = "render", at = @At("HEAD"))
    private void tlmAgentTweaks$beginFrame(EntityGraphics graphics, CallbackInfo ci) {
        this.tlmAgentTweaks$thinkingRenderers.clear();
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/github/tartaricacid/touhoulittlemaid/entity/chatbubble/IChatBubbleData;"
                    + "getRenderer(Lcom/github/tartaricacid/touhoulittlemaid/client/renderer/entity/"
                    + "chatbubble/IChatBubbleRenderer$Position;)"
                    + "Lcom/github/tartaricacid/touhoulittlemaid/client/renderer/entity/chatbubble/IChatBubbleRenderer;"))
    private IChatBubbleRenderer tlmAgentTweaks$thinkingRenderer(IChatBubbleData data,
                                                                IChatBubbleRenderer.Position position,
                                                                EntityGraphics graphics) {
        IChatBubbleRenderer original = data.getRenderer(position);
        if (!(original instanceof WaitingChatBubbleRenderer waiting)) {
            return original;
        }
        EntityMaid maid = graphics.getMaid();
        if (maid == null || !ClientConfig.showReasoning()) {
            return original;
        }
        ThinkingDisplayCache.Entry entry = ThinkingDisplayCache.currentFor(maid, data);
        if (entry == null || entry.phase() != ThinkingDisplayPayload.PHASE_THINKING) {
            return original;
        }
        ThinkingBubbleRenderer replacement = this.tlmAgentTweaks$thinkingRenderers.computeIfAbsent(data,
                ignored -> new ThinkingBubbleRenderer(waiting,
                        ((WaitingChatBubbleRendererAccessor) waiting).tlmAgentTweaks$title(),
                        ((WaitingChatBubbleRendererAccessor) waiting).tlmAgentTweaks$icon()));
        // 同一帧内首次取用时刷新文本；宽度与高度随后据此计算。
        replacement.update(entry.line());
        return replacement;
    }
}

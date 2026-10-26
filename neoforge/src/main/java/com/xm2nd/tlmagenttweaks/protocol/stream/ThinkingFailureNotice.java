package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingErrorClassifier;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 「上游拒绝思考参数」类失败的预设提示（issue #3 错误预处理）。
 * <p>
 * 命中条件从严：本轮请求确实注入过思考参数，且失败信息同时出现思考参数名与
 * 拒绝类措辞。命中后用预设文案替换 TLM 的原始英文报错，直接告知玩家调整思考设置。
 * 调用方必须已在服务端主线程（失败派发保证）。
 */
public final class ThinkingFailureNotice {

    static final String UNSUPPORTED = "chat.tlm_agent_tweaks.thinking.unsupported";

    private ThinkingFailureNotice() {
    }

    public static boolean shouldIntercept(LLMCallback callback, Throwable throwable) {
        if (callback == null || throwable == null) {
            return false;
        }
        return ProtocolBridge.thinkingApplied(callback)
                && ThinkingErrorClassifier.isThinkingUnsupported(throwable.getMessage());
    }

    public static void notify(LLMCallback callback, EntityMaid maid, long waitingChatBubbleId,
                              Throwable throwable) {
        try {
            String model = maid != null ? maid.getAiChatManager().getLLMModel() : "";
            if (maid != null && maid.getOwner() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable(UNSUPPORTED, model)
                        .withStyle(ChatFormatting.RED));
            }
            if (maid != null) {
                maid.getChatBubbleManager().removeChatBubble(waitingChatBubbleId);
            }
            TlmAgentTweaks.LOGGER.warn("思考设置被上游拒绝（已给出预设提示）: {}", throwable.getMessage());
        } catch (Throwable t) {
            // 提示本身失败绝不影响失败回调链
            TlmAgentTweaks.LOGGER.warn("思考设置预设提示发送失败（已忽略）", t);
        }
    }
}

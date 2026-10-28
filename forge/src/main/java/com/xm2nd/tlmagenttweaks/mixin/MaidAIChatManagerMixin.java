package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.chat.ChatVisibilityService;
import com.xm2nd.tlmagenttweaks.chat.NormalChatOriginAccess;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.UUID;

/**
 * 在正式普通聊天 {@code normalChat} 的回调派发处标记来源并广播玩家原文。
 * <p>
 * 精确重定向 {@code normalChat} 内的 {@code LLMClient.chat(LLMCallback)} 调用：
 * Self-Talk 可能在 HEAD 取消并延后正式派发，人设召回/历史压缩也发生在 HEAD 之前，
 * 挂在这里才能保证只在真实派发时执行一次。玩家原文取自外围方法的原始
 * {@code message} 参数，不读取已拼入游戏上下文与历史记录的 {@code messages}。
 * 随后用同一个 client、同一个 callback 原样执行派发，恰好一次。
 */
@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class MaidAIChatManagerMixin {

    @Redirect(method = "normalChat", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;"
                            + "chat(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;)V"))
    private void tlmAgentTweaks$markNormalChatOrigin(LLMClient client, LLMCallback callback,
                                                     String message, List<LLMMessage> messages,
                                                     LLMClient chatClient) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        EntityMaid maid = self.getMaid();
        // 来源取女仆当前服务端主人；无主人时不标记，也不广播
        UUID origin = null;
        if (maid.getOwner() instanceof ServerPlayer owner) {
            origin = owner.getUUID();
        }
        if (origin != null) {
            // 来源标记无论本轮公开与否都要写入：公开与私密是服务端当前状态
            ((NormalChatOriginAccess) callback).tlmAgentTweaks$setNormalChatOrigin(origin);
        }
        ChatVisibilityService.onNormalChatDispatch(maid, message, origin);
        client.chat(callback);
    }
}

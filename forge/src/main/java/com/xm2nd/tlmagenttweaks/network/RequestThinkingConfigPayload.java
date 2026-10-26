package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求每模型的思考配置快照。玩家打开 AI 聊天界面时发送，
 * 服务端以 {@link ThinkingConfigPayload} 回应。
 *
 * @param entityId 打开界面的女仆实体 id，服务端据此附带该女仆已存的思考选择
 */
public record RequestThinkingConfigPayload(int entityId) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_thinking_config");

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static RequestThinkingConfigPayload read(FriendlyByteBuf buf) {
        return new RequestThinkingConfigPayload(buf.readInt());
    }
}

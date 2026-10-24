package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求每模型的思考配置快照。玩家打开 AI 聊天界面时发送，
 * 服务端以 {@link ThinkingConfigPayload} 回应。
 */
public record RequestThinkingConfigPayload() {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_thinking_config");

    public void write(FriendlyByteBuf buf) {
        // 无字段
    }

    public static RequestThinkingConfigPayload read(FriendlyByteBuf buf) {
        return new RequestThinkingConfigPayload();
    }
}

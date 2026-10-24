package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求每模型的思考配置快照。玩家打开 AI 聊天界面时发送，
 * 服务端以 {@link ThinkingConfigPayload} 回应。
 */
public record RequestThinkingConfigPayload() implements CustomPacketPayload {

    public static final Type<RequestThinkingConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_thinking_config"));

    public static final StreamCodec<ByteBuf, RequestThinkingConfigPayload> STREAM_CODEC =
            StreamCodec.unit(new RequestThinkingConfigPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求每模型的思考配置快照。玩家打开 AI 聊天界面时发送，
 * 服务端以 {@link ThinkingConfigPayload} 回应。
 *
 * @param entityId 打开界面的女仆实体 id，服务端据此附带该女仆已存的思考选择
 */
public record RequestThinkingConfigPayload(int entityId) implements CustomPacketPayload {

    public static final Type<RequestThinkingConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_thinking_config"));

    public static final StreamCodec<ByteBuf, RequestThinkingConfigPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RequestThinkingConfigPayload decode(ByteBuf buf) {
            return new RequestThinkingConfigPayload(new FriendlyByteBuf(buf).readInt());
        }

        @Override
        public void encode(ByteBuf buf, RequestThinkingConfigPayload payload) {
            new FriendlyByteBuf(buf).writeInt(payload.entityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

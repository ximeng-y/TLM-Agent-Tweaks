package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求当前「普通对话全局可见」的权威状态（全局开关、本人私密偏好、管理员权限）。
 * 无业务字段，服务端按真实网络上下文玩家返回。
 */
public record RequestChatVisibilityPayload() implements CustomPacketPayload {

    public static final Type<RequestChatVisibilityPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_chat_visibility"));

    public static final StreamCodec<ByteBuf, RequestChatVisibilityPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RequestChatVisibilityPayload decode(ByteBuf buf) {
            return new RequestChatVisibilityPayload();
        }

        @Override
        public void encode(ByteBuf buf, RequestChatVisibilityPayload payload) {
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

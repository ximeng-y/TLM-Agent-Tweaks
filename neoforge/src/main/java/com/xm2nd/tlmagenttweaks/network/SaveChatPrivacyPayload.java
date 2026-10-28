package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：保存本人「我的对话设为私密」偏好。
 * 身份只能来自真实网络上下文玩家，不接受 UUID 或其他玩家身份字段。
 */
public record SaveChatPrivacyPayload(boolean privateChat) implements CustomPacketPayload {

    public static final Type<SaveChatPrivacyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "save_chat_privacy"));

    public static final StreamCodec<ByteBuf, SaveChatPrivacyPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SaveChatPrivacyPayload decode(ByteBuf buf) {
            return new SaveChatPrivacyPayload(((ByteBuf) buf).readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, SaveChatPrivacyPayload payload) {
            buf.writeBoolean(payload.privateChat);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

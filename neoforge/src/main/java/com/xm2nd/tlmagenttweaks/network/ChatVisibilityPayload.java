package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：全局可见权威状态快照。
 * 只回应请求或保存的玩家；页面收到后按服务端真实状态回显。
 */
public record ChatVisibilityPayload(boolean globalVisible, boolean privateChat,
                                    boolean canManageGlobal) implements CustomPacketPayload {

    public static final Type<ChatVisibilityPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "chat_visibility"));

    public static final StreamCodec<ByteBuf, ChatVisibilityPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ChatVisibilityPayload decode(ByteBuf buf) {
            return new ChatVisibilityPayload(((ByteBuf) buf).readBoolean(),
                    ((ByteBuf) buf).readBoolean(), ((ByteBuf) buf).readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, ChatVisibilityPayload payload) {
            buf.writeBoolean(payload.globalVisible);
            buf.writeBoolean(payload.privateChat);
            buf.writeBoolean(payload.canManageGlobal);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

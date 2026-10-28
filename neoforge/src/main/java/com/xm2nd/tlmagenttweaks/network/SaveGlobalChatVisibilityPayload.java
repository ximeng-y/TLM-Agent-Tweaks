package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：管理员保存「普通对话全局可见」开关。
 * 服务端再次校验 OP 权限（等级 2），无权限时不修改配置，仅回带真实状态。
 */
public record SaveGlobalChatVisibilityPayload(boolean globalVisible) implements CustomPacketPayload {

    public static final Type<SaveGlobalChatVisibilityPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "save_global_chat_visibility"));

    public static final StreamCodec<ByteBuf, SaveGlobalChatVisibilityPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SaveGlobalChatVisibilityPayload decode(ByteBuf buf) {
            return new SaveGlobalChatVisibilityPayload(((ByteBuf) buf).readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, SaveGlobalChatVisibilityPayload payload) {
            buf.writeBoolean(payload.globalVisible);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

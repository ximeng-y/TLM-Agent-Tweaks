package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求对指定女仆手动压缩聊天记录。
 *
 * @param entityId 女仆实体 id（客户端所在维度的实体 id）
 */
public record RequestCompressHistoryPayload(int entityId) implements CustomPacketPayload {

    public static final Type<RequestCompressHistoryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_compress"));

    public static final StreamCodec<ByteBuf, RequestCompressHistoryPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestCompressHistoryPayload::entityId,
            RequestCompressHistoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

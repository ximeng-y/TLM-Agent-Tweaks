package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求模型价格表（仅管理员）。
 */
public record RequestPriceTablePayload() implements CustomPacketPayload {

    public static final Type<RequestPriceTablePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_prices"));

    public static final StreamCodec<ByteBuf, RequestPriceTablePayload> STREAM_CODEC =
            StreamCodec.unit(new RequestPriceTablePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

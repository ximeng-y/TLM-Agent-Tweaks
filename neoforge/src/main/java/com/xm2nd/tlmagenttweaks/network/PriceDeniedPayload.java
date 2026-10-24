package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：价格表请求被服务端拒绝（非管理员）。
 * <p>
 * 与 {@link PriceTablePayload} 互斥回包：客户端收到后价格缓存进入无权限状态，
 * 界面据此给出明确提示，而不是停留在无解释的空白。
 */
public record PriceDeniedPayload() implements CustomPacketPayload {

    public static final Type<PriceDeniedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "price_denied"));

    public static final StreamCodec<ByteBuf, PriceDeniedPayload> STREAM_CODEC =
            StreamCodec.unit(new PriceDeniedPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

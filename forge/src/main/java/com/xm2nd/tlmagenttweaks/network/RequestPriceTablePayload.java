package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求模型价格表（仅管理员）。
 */
public record RequestPriceTablePayload() {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_prices");

    public void write(FriendlyByteBuf buf) {
        // 无字段
    }

    public static RequestPriceTablePayload read(FriendlyByteBuf buf) {
        return new RequestPriceTablePayload();
    }
}

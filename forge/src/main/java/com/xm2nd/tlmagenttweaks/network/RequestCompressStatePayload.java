package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** C2S：只查询压缩状态，不触发压缩。 */
public record RequestCompressStatePayload(int entityId) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_compress_state");

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
    }

    public static RequestCompressStatePayload read(FriendlyByteBuf buf) {
        return new RequestCompressStatePayload(buf.readVarInt());
    }
}

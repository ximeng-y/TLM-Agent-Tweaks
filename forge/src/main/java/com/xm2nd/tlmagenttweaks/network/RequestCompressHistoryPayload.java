package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求对指定女仆手动压缩聊天记录。
 *
 * @param entityId 女仆实体 id（客户端所在维度的实体 id）
 */
public record RequestCompressHistoryPayload(int entityId) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_compress");

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
    }

    public static RequestCompressHistoryPayload read(FriendlyByteBuf buf) {
        return new RequestCompressHistoryPayload(buf.readVarInt());
    }
}

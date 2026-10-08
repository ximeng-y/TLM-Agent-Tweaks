package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C：服务端实际压缩状态；任务结束时携带最新历史及摘要。 */
public record CompressHistoryStatePayload(int entityId, boolean running, CompoundTag aiData)
        implements CustomPacketPayload {
    public static final Type<CompressHistoryStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "compress_state"));
    public static final StreamCodec<ByteBuf, CompressHistoryStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CompressHistoryStatePayload::entityId,
            ByteBufCodecs.BOOL, CompressHistoryStatePayload::running,
            ByteBufCodecs.TRUSTED_COMPOUND_TAG, CompressHistoryStatePayload::aiData,
            CompressHistoryStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

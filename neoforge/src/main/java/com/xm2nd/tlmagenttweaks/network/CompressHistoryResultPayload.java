package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：手动压缩结果。
 *
 * @param success   压缩是否成功
 * @param reasonKey 失败原因的翻译键（成功时为空串）
 * @param argA      消息参数 1（如 below_threshold 时为当前上次对话 token 数）
 * @param argB      消息参数 2（如 below_threshold 时为阈值 K 值）
 * @param entityId  女仆实体 id
 * @param aiData    压缩成功后的女仆 AI 数据（writeToTag 产物；空 tag 表示无需刷新）
 */
public record CompressHistoryResultPayload(boolean success, String reasonKey, long argA, long argB,
                                           int entityId, CompoundTag aiData) implements CustomPacketPayload {

    public static final Type<CompressHistoryResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "compress_result"));

    public static final StreamCodec<ByteBuf, CompressHistoryResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, CompressHistoryResultPayload::success,
            ByteBufCodecs.STRING_UTF8, CompressHistoryResultPayload::reasonKey,
            ByteBufCodecs.VAR_LONG, CompressHistoryResultPayload::argA,
            ByteBufCodecs.VAR_LONG, CompressHistoryResultPayload::argB,
            ByteBufCodecs.VAR_INT, CompressHistoryResultPayload::entityId,
            ByteBufCodecs.TRUSTED_COMPOUND_TAG, CompressHistoryResultPayload::aiData,
            CompressHistoryResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
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
                                           int entityId, CompoundTag aiData) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "compress_result");

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(success);
        buf.writeUtf(reasonKey);
        buf.writeVarLong(argA);
        buf.writeVarLong(argB);
        buf.writeVarInt(entityId);
        buf.writeNbt(aiData);
    }

    public static CompressHistoryResultPayload read(FriendlyByteBuf buf) {
        // AI 数据含完整历史与摘要，可能超过 readNbt() 默认的 2 MiB 配额，故按可信数据不限量读取
        return new CompressHistoryResultPayload(buf.readBoolean(), buf.readUtf(), buf.readVarLong(),
                buf.readVarLong(), buf.readVarInt(), buf.readNbt(NbtAccounter.UNLIMITED));
    }
}

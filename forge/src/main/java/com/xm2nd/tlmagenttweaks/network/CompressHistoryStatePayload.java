package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** S2C：服务端实际压缩状态；任务结束时携带最新历史及摘要。 */
public record CompressHistoryStatePayload(int entityId, boolean running, CompoundTag aiData) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "compress_state");

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(running);
        buf.writeNbt(aiData);
    }

    public static CompressHistoryStatePayload read(FriendlyByteBuf buf) {
        // AI 数据含完整历史与摘要，可能超过 readNbt() 默认的 2 MiB 配额，故按可信数据不限量读取
        return new CompressHistoryStatePayload(buf.readVarInt(), buf.readBoolean(),
                buf.readNbt(NbtAccounter.UNLIMITED));
    }
}

package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * S2C：思考链展示快照。
 * <p>
 * 有意发送**有界完整快照**，而不是让客户端累加原始增量：换行、阶段切换、玩家中途进入
 * 可见范围都会让「增量序列」在客户端重放出错位，快照每次都是自洽的当前状态。
 *
 * @param dimension         女仆所在维度 ID
 * @param maidId            女仆 UUID
 * @param displaySessionId  展示会话 ID（每次请求新建，用于丢弃过期请求的迟到快照）
 * @param revision          递增修订号，客户端只接受更新的修订
 * @param bubbleId          当前等待气泡 ID；{@code -1} 表示已无等待气泡
 * @param phase             {@link #PHASE_THINKING} / {@link #PHASE_NATIVE} / {@link #PHASE_CLEAR}
 * @param line              当前有界逻辑行；非思考阶段为空串
 */
public record ThinkingDisplayPayload(String dimension, UUID maidId, long displaySessionId, long revision,
                                     long bubbleId, byte phase, String line) implements CustomPacketPayload {

    public static final byte PHASE_THINKING = 0;
    public static final byte PHASE_NATIVE = 1;
    public static final byte PHASE_CLEAR = 2;

    public static final Type<ThinkingDisplayPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "thinking_display"));

    /**
     * 手写编解码：字段数超过 {@code StreamCodec.composite} 的上限（6 对），
     * 且顺序与字段声明一致，便于后续增删字段时直接对照。
     */
    public static final StreamCodec<ByteBuf, ThinkingDisplayPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension());
                UUIDUtil.STREAM_CODEC.encode(buf, payload.maidId());
                ByteBufCodecs.VAR_LONG.encode(buf, payload.displaySessionId());
                ByteBufCodecs.VAR_LONG.encode(buf, payload.revision());
                ByteBufCodecs.VAR_LONG.encode(buf, payload.bubbleId());
                ByteBufCodecs.BYTE.encode(buf, payload.phase());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.line());
            },
            buf -> new ThinkingDisplayPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.BYTE.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

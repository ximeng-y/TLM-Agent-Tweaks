package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求 token 统计数据（仅管理员）。
 *
 * @param viewType   0=玩家排行 1=玩家明细 2=服务器总览
 * @param param      玩家明细时为玩家 UUID，其余为空串
 * @param windowDays 服务器总览的时间窗口天数；0=all
 */
public record RequestTokenStatsPayload(int viewType, String param, int windowDays) implements CustomPacketPayload {

    public static final int VIEW_RANKING = 0;
    public static final int VIEW_PLAYER_DETAIL = 1;
    public static final int VIEW_SERVER = 2;

    public static final Type<RequestTokenStatsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_stats"));

    public static final StreamCodec<ByteBuf, RequestTokenStatsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestTokenStatsPayload::viewType,
            ByteBufCodecs.STRING_UTF8, RequestTokenStatsPayload::param,
            ByteBufCodecs.VAR_INT, RequestTokenStatsPayload::windowDays,
            RequestTokenStatsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

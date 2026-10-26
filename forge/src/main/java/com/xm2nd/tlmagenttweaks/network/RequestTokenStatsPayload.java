package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求 token 统计数据（仅管理员）。
 *
 * @param viewType   0=玩家排行 1=玩家明细 2=服务器总览
 * @param param      玩家明细时为玩家 ID，通常为 UUID；兼容已有统计中的原始 ID，其余为空串
 * @param windowDays 服务器总览的时间窗口天数；0=all
 */
public record RequestTokenStatsPayload(int viewType, String param, int windowDays) {

    public static final int VIEW_RANKING = 0;
    public static final int VIEW_PLAYER_DETAIL = 1;
    public static final int VIEW_SERVER = 2;

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_stats");

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(viewType);
        buf.writeUtf(param);
        buf.writeVarInt(windowDays);
    }

    public static RequestTokenStatsPayload read(FriendlyByteBuf buf) {
        return new RequestTokenStatsPayload(buf.readVarInt(), buf.readUtf(), buf.readVarInt());
    }
}

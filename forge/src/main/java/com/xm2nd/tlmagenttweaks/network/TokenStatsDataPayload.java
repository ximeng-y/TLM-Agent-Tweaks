package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：token 统计数据（按 viewType 携带对应数据体）。
 * <p>
 * viewType 3 表示权限不足被拒绝（客户端显示提示）。
 *
 * @param viewType   0=排行 1=玩家明细 2=服务器总览 3=拒绝
 * @param ranking    viewType=0 时的排行数据
 * @param detail     viewType=1 时的明细数据
 * @param windowDays viewType=2 时的窗口天数（回显）
 * @param buckets    viewType=2 时的桶序列
 */
public record TokenStatsDataPayload(int viewType, List<RankEntry> ranking, PlayerDetailDto detail,
                                    int windowDays, List<BucketDto> buckets) {

    public static final int VIEW_DENIED = 3;

    /** 排行行：玩家 + 四类 token 总和 */
    public record RankEntry(String uuid, String name, long input, long output,
                            long cacheRead, long cacheWrite, long requests) {
        public long total() {
            return input + output + cacheRead + cacheWrite;
        }
    }

    /** 玩家明细：四类 token + 缓存命中率 + 双币种成本 */
    public record PlayerDetailDto(String uuid, String name, long input, long output, long cacheRead,
                                  long cacheWrite, long requests, double cacheHitRate,
                                  double costCny, boolean anyCnyPrice, double costUsd, boolean anyUsdPrice) {
    }

    /** 服务器总览桶 */
    public record BucketDto(int startDay, int endDay, int unitOrdinal, long input, long output,
                            long cacheRead, long cacheWrite, double costCny, boolean anyCnyPrice,
                            double costUsd, boolean anyUsdPrice) {
        public long total() {
            return input + output + cacheRead + cacheWrite;
        }
    }

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "stats_data");

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(viewType);
        switch (viewType) {
            case RequestTokenStatsPayload.VIEW_RANKING -> {
                buf.writeVarInt(ranking.size());
                for (RankEntry entry : ranking) {
                    buf.writeUtf(entry.uuid());
                    buf.writeUtf(entry.name());
                    buf.writeVarLong(entry.input());
                    buf.writeVarLong(entry.output());
                    buf.writeVarLong(entry.cacheRead());
                    buf.writeVarLong(entry.cacheWrite());
                    buf.writeVarLong(entry.requests());
                }
            }
            case RequestTokenStatsPayload.VIEW_PLAYER_DETAIL -> {
                buf.writeUtf(detail.uuid());
                buf.writeUtf(detail.name());
                buf.writeVarLong(detail.input());
                buf.writeVarLong(detail.output());
                buf.writeVarLong(detail.cacheRead());
                buf.writeVarLong(detail.cacheWrite());
                buf.writeVarLong(detail.requests());
                buf.writeDouble(detail.cacheHitRate());
                buf.writeDouble(detail.costCny());
                buf.writeBoolean(detail.anyCnyPrice());
                buf.writeDouble(detail.costUsd());
                buf.writeBoolean(detail.anyUsdPrice());
            }
            case RequestTokenStatsPayload.VIEW_SERVER -> {
                buf.writeVarInt(windowDays);
                buf.writeVarInt(buckets.size());
                for (BucketDto dto : buckets) {
                    buf.writeVarInt(dto.startDay());
                    buf.writeVarInt(dto.endDay());
                    buf.writeVarInt(dto.unitOrdinal());
                    buf.writeVarLong(dto.input());
                    buf.writeVarLong(dto.output());
                    buf.writeVarLong(dto.cacheRead());
                    buf.writeVarLong(dto.cacheWrite());
                    buf.writeDouble(dto.costCny());
                    buf.writeBoolean(dto.anyCnyPrice());
                    buf.writeDouble(dto.costUsd());
                    buf.writeBoolean(dto.anyUsdPrice());
                }
            }
            default -> {
            }
        }
    }

    public static TokenStatsDataPayload read(FriendlyByteBuf buf) {
        int viewType = buf.readVarInt();
        List<RankEntry> ranking = List.of();
        PlayerDetailDto detail = null;
        int windowDays = 0;
        List<BucketDto> buckets = List.of();
        switch (viewType) {
            case RequestTokenStatsPayload.VIEW_RANKING -> {
                int size = buf.readVarInt();
                List<RankEntry> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(new RankEntry(buf.readUtf(), buf.readUtf(),
                            buf.readVarLong(), buf.readVarLong(),
                            buf.readVarLong(), buf.readVarLong(), buf.readVarLong()));
                }
                ranking = list;
            }
            case RequestTokenStatsPayload.VIEW_PLAYER_DETAIL -> detail = new PlayerDetailDto(
                    buf.readUtf(), buf.readUtf(),
                    buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                    buf.readVarLong(), buf.readVarLong(), buf.readDouble(),
                    buf.readDouble(), buf.readBoolean(),
                    buf.readDouble(), buf.readBoolean());
            case RequestTokenStatsPayload.VIEW_SERVER -> {
                windowDays = buf.readVarInt();
                int size = buf.readVarInt();
                List<BucketDto> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(new BucketDto(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
                            buf.readVarLong(), buf.readDouble(), buf.readBoolean(),
                            buf.readDouble(), buf.readBoolean()));
                }
                buckets = list;
            }
            default -> {
            }
        }
        return new TokenStatsDataPayload(viewType, ranking, detail, windowDays, buckets);
    }
}

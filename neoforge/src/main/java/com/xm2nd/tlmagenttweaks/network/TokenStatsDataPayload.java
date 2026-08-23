package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
                                    int windowDays, List<BucketDto> buckets) implements CustomPacketPayload {

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

    public static final Type<TokenStatsDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "stats_data"));

    public static final StreamCodec<ByteBuf, TokenStatsDataPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TokenStatsDataPayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            int viewType = friendly.readVarInt();
            List<RankEntry> ranking = List.of();
            PlayerDetailDto detail = null;
            int windowDays = 0;
            List<BucketDto> buckets = List.of();
            switch (viewType) {
                case RequestTokenStatsPayload.VIEW_RANKING -> {
                    int size = friendly.readVarInt();
                    List<RankEntry> list = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(new RankEntry(friendly.readUtf(), friendly.readUtf(),
                                friendly.readVarLong(), friendly.readVarLong(),
                                friendly.readVarLong(), friendly.readVarLong(), friendly.readVarLong()));
                    }
                    ranking = list;
                }
                case RequestTokenStatsPayload.VIEW_PLAYER_DETAIL -> detail = new PlayerDetailDto(
                        friendly.readUtf(), friendly.readUtf(),
                        friendly.readVarLong(), friendly.readVarLong(), friendly.readVarLong(),
                        friendly.readVarLong(), friendly.readVarLong(), friendly.readDouble(),
                        friendly.readDouble(), friendly.readBoolean(),
                        friendly.readDouble(), friendly.readBoolean());
                case RequestTokenStatsPayload.VIEW_SERVER -> {
                    windowDays = friendly.readVarInt();
                    int size = friendly.readVarInt();
                    List<BucketDto> list = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(new BucketDto(friendly.readVarInt(), friendly.readVarInt(), friendly.readVarInt(),
                                friendly.readVarLong(), friendly.readVarLong(), friendly.readVarLong(),
                                friendly.readVarLong(), friendly.readDouble(), friendly.readBoolean(),
                                friendly.readDouble(), friendly.readBoolean()));
                    }
                    buckets = list;
                }
                default -> {
                }
            }
            return new TokenStatsDataPayload(viewType, ranking, detail, windowDays, buckets);
        }

        @Override
        public void encode(ByteBuf buf, TokenStatsDataPayload payload) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeVarInt(payload.viewType);
            switch (payload.viewType) {
                case RequestTokenStatsPayload.VIEW_RANKING -> {
                    friendly.writeVarInt(payload.ranking.size());
                    for (RankEntry entry : payload.ranking) {
                        friendly.writeUtf(entry.uuid());
                        friendly.writeUtf(entry.name());
                        friendly.writeVarLong(entry.input());
                        friendly.writeVarLong(entry.output());
                        friendly.writeVarLong(entry.cacheRead());
                        friendly.writeVarLong(entry.cacheWrite());
                        friendly.writeVarLong(entry.requests());
                    }
                }
                case RequestTokenStatsPayload.VIEW_PLAYER_DETAIL -> {
                    PlayerDetailDto dto = payload.detail;
                    friendly.writeUtf(dto.uuid());
                    friendly.writeUtf(dto.name());
                    friendly.writeVarLong(dto.input());
                    friendly.writeVarLong(dto.output());
                    friendly.writeVarLong(dto.cacheRead());
                    friendly.writeVarLong(dto.cacheWrite());
                    friendly.writeVarLong(dto.requests());
                    friendly.writeDouble(dto.cacheHitRate());
                    friendly.writeDouble(dto.costCny());
                    friendly.writeBoolean(dto.anyCnyPrice());
                    friendly.writeDouble(dto.costUsd());
                    friendly.writeBoolean(dto.anyUsdPrice());
                }
                case RequestTokenStatsPayload.VIEW_SERVER -> {
                    friendly.writeVarInt(payload.windowDays);
                    friendly.writeVarInt(payload.buckets.size());
                    for (BucketDto dto : payload.buckets) {
                        friendly.writeVarInt(dto.startDay());
                        friendly.writeVarInt(dto.endDay());
                        friendly.writeVarInt(dto.unitOrdinal());
                        friendly.writeVarLong(dto.input());
                        friendly.writeVarLong(dto.output());
                        friendly.writeVarLong(dto.cacheRead());
                        friendly.writeVarLong(dto.cacheWrite());
                        friendly.writeDouble(dto.costCny());
                        friendly.writeBoolean(dto.anyCnyPrice());
                        friendly.writeDouble(dto.costUsd());
                        friendly.writeBoolean(dto.anyUsdPrice());
                    }
                }
                default -> {
                }
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

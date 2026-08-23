package com.xm2nd.tlmagenttweaks.client;

import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 客户端统计数据缓存：存放最近一次服务端下发的各视图数据。
 * 界面通过版本号判断是否有新数据需要重新渲染。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientStatsCache {
    private static volatile long version = 0;
    private static volatile int lastViewType = -1;
    private static volatile List<TokenStatsDataPayload.RankEntry> ranking = List.of();
    private static volatile TokenStatsDataPayload.PlayerDetailDto detail;
    private static volatile int serverWindowDays = 30;
    private static volatile List<TokenStatsDataPayload.BucketDto> buckets = List.of();
    private static volatile boolean denied = false;

    private ClientStatsCache() {
    }

    public static void accept(TokenStatsDataPayload payload) {
        denied = payload.viewType() == TokenStatsDataPayload.VIEW_DENIED;
        if (!denied) {
            lastViewType = payload.viewType();
            switch (payload.viewType()) {
                case 0 -> ranking = payload.ranking();
                case 1 -> detail = payload.detail();
                case 2 -> {
                    serverWindowDays = payload.windowDays();
                    buckets = payload.buckets();
                }
                default -> {
                }
            }
        }
        version++;
    }

    public static long version() {
        return version;
    }

    public static int lastViewType() {
        return lastViewType;
    }

    public static List<TokenStatsDataPayload.RankEntry> ranking() {
        return ranking;
    }

    public static TokenStatsDataPayload.PlayerDetailDto detail() {
        return detail;
    }

    public static List<TokenStatsDataPayload.BucketDto> buckets() {
        return buckets;
    }

    public static int serverWindowDays() {
        return serverWindowDays;
    }

    public static boolean denied() {
        return denied;
    }
}

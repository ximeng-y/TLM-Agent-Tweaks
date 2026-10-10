package com.xm2nd.tlmagenttweaks.client;

import com.xm2nd.tlmagenttweaks.network.PriceTablePayload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * 客户端价格表缓存（最近一次服务端下发）。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPriceCache {
    private static volatile long version = 0;
    private static volatile Map<String, Map<String, Map<String, double[]>>> table = Map.of();

    private ClientPriceCache() {
    }

    public static void accept(PriceTablePayload payload) {
        table = payload.table();
        version++;
    }

    public static long version() {
        return version;
    }

    /** 查询某站点某模型某币种价格；无配置返回 null */
    public static double[] getPrice(String siteId, String model, String currency) {
        return table.getOrDefault(siteId, Map.of())
                .getOrDefault(model, Map.of())
                .get(currency);
    }

    public static boolean hasAnyPrice(String currency) {
        for (Map<String, Map<String, double[]>> byModel : table.values()) {
            for (Map<String, double[]> byCurrency : byModel.values()) {
                if (byCurrency.containsKey(currency)) {
                    return true;
                }
            }
        }
        return false;
    }
}

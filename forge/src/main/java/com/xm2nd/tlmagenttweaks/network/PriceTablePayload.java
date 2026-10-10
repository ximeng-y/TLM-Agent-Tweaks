package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S2C：模型价格表快照（不含任何密钥，纯价格数据）。
 * <p>
 * 结构：siteId → model → 币种（"cny"/"usd"）→ 4 元素 double 数组
 * （输入/输出/缓存命中/缓存写入，每百万 token 单价）。
 */
public record PriceTablePayload(Map<String, Map<String, Map<String, double[]>>> table) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "price_table");

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(table.size());
        table.forEach((siteId, byModel) -> {
            buf.writeUtf(siteId);
            buf.writeVarInt(byModel.size());
            byModel.forEach((model, byCurrency) -> {
                buf.writeUtf(model);
                buf.writeVarInt(byCurrency.size());
                byCurrency.forEach((currency, prices) -> {
                    buf.writeUtf(currency);
                    buf.writeVarInt(prices.length);
                    for (double value : prices) {
                        buf.writeDouble(value);
                    }
                });
            });
        });
    }

    public static PriceTablePayload read(FriendlyByteBuf buf) {
        Map<String, Map<String, Map<String, double[]>>> table = new LinkedHashMap<>();
        int siteCount = buf.readVarInt();
        for (int i = 0; i < siteCount; i++) {
            String siteId = buf.readUtf();
            int modelCount = buf.readVarInt();
            Map<String, Map<String, double[]>> byModel = new LinkedHashMap<>();
            for (int j = 0; j < modelCount; j++) {
                String model = buf.readUtf();
                int currencyCount = buf.readVarInt();
                Map<String, double[]> byCurrency = new LinkedHashMap<>();
                for (int k = 0; k < currencyCount; k++) {
                    String currency = buf.readUtf();
                    double[] prices = new double[buf.readVarInt()];
                    for (int m = 0; m < prices.length; m++) {
                        prices[m] = buf.readDouble();
                    }
                    byCurrency.put(currency, prices);
                }
                byModel.put(model, byCurrency);
            }
            table.put(siteId, byModel);
        }
        return new PriceTablePayload(table);
    }
}

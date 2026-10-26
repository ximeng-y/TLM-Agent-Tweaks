package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.handler.codec.DecoderException;
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

    /** 每份价格固定四个字段：输入/输出/缓存命中/缓存写入 */
    private static final int PRICE_FIELD_COUNT = 4;
    /** 解码上限：真实数据远小于这些值，超出视为伪造包并拒绝，避免无界分配 */
    private static final int MAX_SITES = 64;
    private static final int MAX_MODELS_PER_SITE = 1024;
    private static final int MAX_CURRENCIES_PER_MODEL = 8;

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
        int siteCount = readBoundedCount(buf, MAX_SITES, "site");
        for (int i = 0; i < siteCount; i++) {
            String siteId = buf.readUtf();
            int modelCount = readBoundedCount(buf, MAX_MODELS_PER_SITE, "model");
            Map<String, Map<String, double[]>> byModel = new LinkedHashMap<>();
            for (int j = 0; j < modelCount; j++) {
                String model = buf.readUtf();
                int currencyCount = readBoundedCount(buf, MAX_CURRENCIES_PER_MODEL, "currency");
                Map<String, double[]> byCurrency = new LinkedHashMap<>();
                for (int k = 0; k < currencyCount; k++) {
                    String currency = buf.readUtf();
                    int priceCount = buf.readVarInt();
                    if (priceCount != PRICE_FIELD_COUNT) {
                        throw new DecoderException("价格字段数必须为 " + PRICE_FIELD_COUNT + "，实际 " + priceCount);
                    }
                    double[] prices = new double[priceCount];
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

    /** 读取层级的条目数并校验上限，超限视为伪造包 */
    private static int readBoundedCount(FriendlyByteBuf buf, int max, String level) {
        int count = buf.readVarInt();
        if (count > max) {
            throw new DecoderException("价格表 " + level + " 条目数超限：" + count + " > " + max);
        }
        return count;
    }
}

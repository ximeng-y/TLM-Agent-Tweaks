package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S2C：模型价格表快照（不含任何密钥，纯价格数据）。
 * <p>
 * 结构：siteId → model → 币种（"cny"/"usd"）→ 4 元素 double 数组
 * （输入/输出/缓存命中/缓存写入，每百万 token 单价）。
 */
public record PriceTablePayload(Map<String, Map<String, Map<String, double[]>>> table) implements CustomPacketPayload {

    /** 每份价格固定四个字段：输入/输出/缓存命中/缓存写入 */
    private static final int PRICE_FIELD_COUNT = 4;
    /** 解码上限：真实数据远小于这些值，超出视为伪造包并拒绝，避免无界分配 */
    private static final int MAX_SITES = 64;
    private static final int MAX_MODELS_PER_SITE = 1024;
    private static final int MAX_CURRENCIES_PER_MODEL = 8;

    public static final Type<PriceTablePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "price_table"));

    public static final StreamCodec<ByteBuf, PriceTablePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PriceTablePayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            Map<String, Map<String, Map<String, double[]>>> table = new LinkedHashMap<>();
            int siteCount = readBoundedCount(friendly, MAX_SITES, "site");
            for (int i = 0; i < siteCount; i++) {
                String siteId = friendly.readUtf();
                int modelCount = readBoundedCount(friendly, MAX_MODELS_PER_SITE, "model");
                Map<String, Map<String, double[]>> byModel = new LinkedHashMap<>();
                for (int j = 0; j < modelCount; j++) {
                    String model = friendly.readUtf();
                    int currencyCount = readBoundedCount(friendly, MAX_CURRENCIES_PER_MODEL, "currency");
                    Map<String, double[]> byCurrency = new LinkedHashMap<>();
                    for (int k = 0; k < currencyCount; k++) {
                        String currency = friendly.readUtf();
                        int priceCount = friendly.readVarInt();
                        if (priceCount != PRICE_FIELD_COUNT) {
                            throw new DecoderException("价格字段数必须为 " + PRICE_FIELD_COUNT + "，实际 " + priceCount);
                        }
                        double[] prices = new double[priceCount];
                        for (int m = 0; m < prices.length; m++) {
                            prices[m] = friendly.readDouble();
                        }
                        byCurrency.put(currency, prices);
                    }
                    byModel.put(model, byCurrency);
                }
                table.put(siteId, byModel);
            }
            return new PriceTablePayload(table);
        }

        @Override
        public void encode(ByteBuf buf, PriceTablePayload payload) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeVarInt(payload.table.size());
            payload.table.forEach((siteId, byModel) -> {
                friendly.writeUtf(siteId);
                friendly.writeVarInt(byModel.size());
                byModel.forEach((model, byCurrency) -> {
                    friendly.writeUtf(model);
                    friendly.writeVarInt(byCurrency.size());
                    byCurrency.forEach((currency, prices) -> {
                        friendly.writeUtf(currency);
                        List<Double> values = new ArrayList<>(prices.length);
                        for (double price : prices) {
                            values.add(price);
                        }
                        friendly.writeVarInt(values.size());
                        for (double value : values) {
                            friendly.writeDouble(value);
                        }
                    });
                });
            });
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
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

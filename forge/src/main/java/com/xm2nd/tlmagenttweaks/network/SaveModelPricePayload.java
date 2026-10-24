package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：保存某站点某模型某币种的价格（仅管理员）。
 *
 * @param siteId   站点 id（服务端会校验其存在）
 * @param model    模型 id（服务端会校验其存在于管理员配置的站点模型表）
 * @param currency 币种："cny" 或 "usd"
 * @param values   4 元素价格（每百万 token）：输入/输出/缓存命中/缓存写入；clear=true 时忽略
 * @param clear    true 表示清除该币种配置
 */
public record SaveModelPricePayload(String siteId, String model, String currency,
                                    double[] values, boolean clear) {

    public SaveModelPricePayload {
        // 编解码对称约束：非清除时必须恰好四个价格（输入/输出/缓存命中/缓存写入）。
        // 编码按数组长度写、解码固定读四，违反长度的记录无法构造，异常长度在解码层即被拒绝。
        if (!clear && values.length != 4) {
            throw new IllegalArgumentException("价格载荷非清除时必须为 4 个值，实际 " + values.length);
        }
    }

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "save_price");

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(siteId);
        buf.writeUtf(model);
        buf.writeUtf(currency);
        buf.writeBoolean(clear);
        if (!clear) {
            for (double value : values) {
                buf.writeDouble(value);
            }
        }
    }

    public static SaveModelPricePayload read(FriendlyByteBuf buf) {
        String siteId = buf.readUtf();
        String model = buf.readUtf();
        String currency = buf.readUtf();
        boolean clear = buf.readBoolean();
        double[] values = new double[0];
        if (!clear) {
            values = new double[4];
            for (int i = 0; i < 4; i++) {
                values[i] = buf.readDouble();
            }
        }
        return new SaveModelPricePayload(siteId, model, currency, values, clear);
    }
}

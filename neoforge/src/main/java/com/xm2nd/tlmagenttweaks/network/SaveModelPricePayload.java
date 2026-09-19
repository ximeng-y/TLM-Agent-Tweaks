package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
                                    double[] values, boolean clear) implements CustomPacketPayload {

    public static final Type<SaveModelPricePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "save_price"));

    public static final StreamCodec<ByteBuf, SaveModelPricePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SaveModelPricePayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            String siteId = friendly.readUtf();
            String model = friendly.readUtf();
            String currency = friendly.readUtf();
            boolean clear = friendly.readBoolean();
            double[] values = new double[0];
            if (!clear) {
                values = new double[4];
                for (int i = 0; i < 4; i++) {
                    values[i] = friendly.readDouble();
                }
            }
            return new SaveModelPricePayload(siteId, model, currency, values, clear);
        }

        @Override
        public void encode(ByteBuf buf, SaveModelPricePayload payload) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeUtf(payload.siteId);
            friendly.writeUtf(payload.model);
            friendly.writeUtf(payload.currency);
            friendly.writeBoolean(payload.clear);
            if (!payload.clear) {
                for (double value : payload.values) {
                    friendly.writeDouble(value);
                }
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

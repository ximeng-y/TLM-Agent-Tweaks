package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLevel;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * S2C：每模型的思考配置快照（不含任何密钥）。
 * <p>
 * 结构：siteId → modelId → {可开关思考, 允许的思考强度位掩码}。
 * 只含配置过的模型，玩家侧据此决定点击模型名后是否展开思考选择。
 *
 * @param current 请求所带女仆当前已存的思考选择；无效或没有时为 null，供选择屏回显
 */
public record ThinkingConfigPayload(Map<String, Map<String, ModelThinkingConfig>> configs,
                                    CurrentChoice current) implements CustomPacketPayload {

    /**
     * 女仆已存的思考选择快照；levelId 原样透传，由客户端解析并做范围校验。
     */
    public record CurrentChoice(int entityId, String siteId, String modelId,
                                boolean enabled, String levelId) {
    }

    public static final Type<ThinkingConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "thinking_config"));

    public static final StreamCodec<ByteBuf, ThinkingConfigPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ThinkingConfigPayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            Map<String, Map<String, ModelThinkingConfig>> configs = new LinkedHashMap<>();
            int siteCount = friendly.readVarInt();
            for (int i = 0; i < siteCount; i++) {
                String siteId = friendly.readUtf();
                int modelCount = friendly.readVarInt();
                Map<String, ModelThinkingConfig> byModel = new LinkedHashMap<>();
                for (int j = 0; j < modelCount; j++) {
                    String model = friendly.readUtf();
                    byModel.put(model, decodeConfig(friendly));
                }
                configs.put(siteId, byModel);
            }
            return new ThinkingConfigPayload(configs, decodeCurrent(friendly));
        }

        @Override
        public void encode(ByteBuf buf, ThinkingConfigPayload payload) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeVarInt(payload.configs.size());
            payload.configs.forEach((siteId, byModel) -> {
                friendly.writeUtf(siteId);
                friendly.writeVarInt(byModel.size());
                byModel.forEach((model, config) -> {
                    friendly.writeUtf(model);
                    encodeConfig(friendly, config);
                });
            });
            encodeCurrent(friendly, payload.current());
        }
    };

    private static void encodeCurrent(FriendlyByteBuf buf, CurrentChoice current) {
        buf.writeBoolean(current != null);
        if (current != null) {
            buf.writeInt(current.entityId());
            buf.writeUtf(current.siteId());
            buf.writeUtf(current.modelId());
            buf.writeBoolean(current.enabled());
            buf.writeUtf(current.levelId());
        }
    }

    private static CurrentChoice decodeCurrent(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        return new CurrentChoice(buf.readInt(), buf.readUtf(), buf.readUtf(),
                buf.readBoolean(), buf.readUtf());
    }

    private static void encodeConfig(FriendlyByteBuf buf, ModelThinkingConfig config) {
        buf.writeBoolean(config.toggleAllowed());
        int mask = 0;
        for (ThinkingLevel level : ThinkingLevel.values()) {
            if (config.levels().contains(level)) {
                mask |= 1 << level.ordinal();
            }
        }
        buf.writeVarInt(mask);
    }

    private static ModelThinkingConfig decodeConfig(FriendlyByteBuf buf) {
        boolean toggleAllowed = buf.readBoolean();
        int mask = buf.readVarInt();
        Set<ThinkingLevel> levels = new LinkedHashSet<>();
        for (ThinkingLevel level : ThinkingLevel.values()) {
            if ((mask & (1 << level.ordinal())) != 0) {
                levels.add(level);
            }
        }
        return new ModelThinkingConfig(toggleAllowed, levels);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLevel;
import net.minecraft.network.FriendlyByteBuf;
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
                                    CurrentChoice current) {

    /**
     * 女仆已存的思考选择快照；levelId 原样透传，由客户端解析并做范围校验。
     */
    public record CurrentChoice(int entityId, String siteId, String modelId,
                                boolean enabled, String levelId) {
    }

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "thinking_config");

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(configs.size());
        configs.forEach((siteId, byModel) -> {
            buf.writeUtf(siteId);
            buf.writeVarInt(byModel.size());
            byModel.forEach((model, config) -> {
                buf.writeUtf(model);
                buf.writeBoolean(config.toggleAllowed());
                int mask = 0;
                for (ThinkingLevel level : ThinkingLevel.values()) {
                    if (config.levels().contains(level)) {
                        mask |= 1 << level.ordinal();
                    }
                }
                buf.writeVarInt(mask);
            });
        });
        buf.writeBoolean(current != null);
        if (current != null) {
            buf.writeInt(current.entityId());
            buf.writeUtf(current.siteId());
            buf.writeUtf(current.modelId());
            buf.writeBoolean(current.enabled());
            buf.writeUtf(current.levelId());
        }
    }

    public static ThinkingConfigPayload read(FriendlyByteBuf buf) {
        Map<String, Map<String, ModelThinkingConfig>> configs = new LinkedHashMap<>();
        int siteCount = buf.readVarInt();
        for (int i = 0; i < siteCount; i++) {
            String siteId = buf.readUtf();
            int modelCount = buf.readVarInt();
            Map<String, ModelThinkingConfig> byModel = new LinkedHashMap<>();
            for (int j = 0; j < modelCount; j++) {
                String model = buf.readUtf();
                boolean toggleAllowed = buf.readBoolean();
                int mask = buf.readVarInt();
                Set<ThinkingLevel> levels = new LinkedHashSet<>();
                for (ThinkingLevel level : ThinkingLevel.values()) {
                    if ((mask & (1 << level.ordinal())) != 0) {
                        levels.add(level);
                    }
                }
                byModel.put(model, new ModelThinkingConfig(toggleAllowed, levels));
            }
            configs.put(siteId, byModel);
        }
        CurrentChoice current = null;
        if (buf.readBoolean()) {
            current = new CurrentChoice(buf.readInt(), buf.readUtf(), buf.readUtf(),
                    buf.readBoolean(), buf.readUtf());
        }
        return new ThinkingConfigPayload(configs, current);
    }
}

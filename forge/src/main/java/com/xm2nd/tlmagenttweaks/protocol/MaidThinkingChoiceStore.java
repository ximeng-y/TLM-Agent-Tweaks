package com.xm2nd.tlmagenttweaks.protocol;

import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingChoice;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLevel;
import net.minecraft.nbt.CompoundTag;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 玩家思考选择的服务端存取：写在女仆实体的 persistent data 上，
 * 随实体存档持久化，无需额外的世界保存数据。
 * <p>
 * 记录做出选择时的站点与模型；请求时若与女仆当前选择不一致则视为失效，回落默认行为。
 */
public final class MaidThinkingChoiceStore {

    private static final String KEY = "TlmAgentTweaksThinkingChoice";
    private static final String SITE = "Site";
    private static final String MODEL = "Model";
    private static final String ENABLED = "Enabled";
    private static final String LEVEL = "Level";

    /**
     * 一次落盘的选择快照。siteId/modelId 用于失效判定。
     */
    public record Stored(String siteId, String modelId, ThinkingChoice choice) {
    }

    private MaidThinkingChoiceStore() {
    }

    public static void write(EntityMaid maid, String siteId, String modelId, boolean enabled, ThinkingLevel level) {
        CompoundTag root = maid.getPersistentData();
        CompoundTag tag = new CompoundTag();
        tag.putString(SITE, siteId == null ? "" : siteId);
        tag.putString(MODEL, modelId == null ? "" : modelId);
        tag.putBoolean(ENABLED, enabled);
        tag.putString(LEVEL, level == null ? ThinkingLevel.DEFAULT.id() : level.id());
        root.put(KEY, tag);
    }

    /**
     * 读取选择；从未选择或数据残缺时返回 {@code null}。
     */
    public static Stored read(EntityMaid maid) {
        CompoundTag root = maid.getPersistentData();
        if (!(root.get(KEY) instanceof CompoundTag tag)) {
            return null;
        }
        String siteId = tag.getString(SITE);
        String modelId = tag.getString(MODEL);
        ThinkingLevel level = ThinkingLevel.fromId(tag.getString(LEVEL));
        if (modelId.isEmpty()) {
            return null;
        }
        return new Stored(siteId, modelId,
                new ThinkingChoice(tag.getBoolean(ENABLED), level));
    }
}

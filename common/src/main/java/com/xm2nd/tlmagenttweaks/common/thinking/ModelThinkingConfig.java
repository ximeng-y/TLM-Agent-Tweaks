package com.xm2nd.tlmagenttweaks.common.thinking;

import java.util.Set;

/**
 * 单个模型的思考配置（管理员在站点编辑器里设置）。
 *
 * @param toggleAllowed 是否允许玩家开关思考模式
 * @param levels        允许玩家选择的思考强度集合；为空时视为仅 default
 */
public record ModelThinkingConfig(boolean toggleAllowed, Set<ThinkingLevel> levels) {

    public ModelThinkingConfig {
        levels = levels == null || levels.isEmpty() ? Set.of(ThinkingLevel.DEFAULT) : Set.copyOf(levels);
    }

    /**
     * 未配置状态：不可开关、仅 default，等同于该模型没有思考选择入口。
     */
    public static ModelThinkingConfig unconfigured() {
        return new ModelThinkingConfig(false, Set.of(ThinkingLevel.DEFAULT));
    }

    /**
     * 是否存在可供玩家选择的面：允许开关，或允许的强度不止 default。
     * 两者皆无时点击模型名应直接完成选择，不弹思考菜单。
     */
    public boolean configured() {
        return toggleAllowed || levels.size() > 1;
    }

    public boolean allows(ThinkingLevel level) {
        return level != null && levels.contains(level);
    }
}

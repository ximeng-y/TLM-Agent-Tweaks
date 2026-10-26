package com.xm2nd.tlmagenttweaks.common.thinking;

/**
 * 思考强度档位，id 直接作为各协议请求参数里的字符串值透传。
 * <p>
 * 管理员按模型勾选支持的档位，玩家只能在其中选择；default 表示跟随上游默认行为。
 */
public enum ThinkingLevel {
    DEFAULT("default"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    private final String id;

    ThinkingLevel(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * 按 id 反查；未知或空 id 返回 {@code null}，由调用方决定忽略还是回退。
     */
    public static ThinkingLevel fromId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (ThinkingLevel level : values()) {
            if (level.id.equals(id)) {
                return level;
            }
        }
        return null;
    }
}

package com.xm2nd.tlmagenttweaks.common.thinking;

/**
 * 玩家对某次模型选择做出的思考决定。
 *
 * @param enabled 思考开关；关闭时各协议按自身能力显式禁用或省略思考参数
 * @param level   思考强度；enabled 且非 default 时才向请求注入强度参数
 */
public record ThinkingChoice(boolean enabled, ThinkingLevel level) {

    public ThinkingChoice {
        level = level == null ? ThinkingLevel.DEFAULT : level;
    }
}

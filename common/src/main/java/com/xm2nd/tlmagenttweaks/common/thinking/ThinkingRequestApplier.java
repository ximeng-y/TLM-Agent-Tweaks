package com.xm2nd.tlmagenttweaks.common.thinking;

import com.google.gson.JsonObject;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolRequestEncoder;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;

/**
 * 把玩家的思考决定写入已编码完成的请求 JSON。
 * <p>
 * 输入是最终发出的请求体（Chat 原样、Responses/Anthropic 为编码器产物），
 * 因此这里按各协议的参数形态直接改写；上游不支持某参数时的报错由
 * {@link ThinkingErrorClassifier} 在失败侧给出预设提示。
 */
public final class ThinkingRequestApplier {

    /** Anthropic 思考预算映射：必须小于 max_tokens，故发出时同步抬高 max_tokens。 */
    private static final int ANTHROPIC_BUDGET_DEFAULT = 8192;
    private static final int ANTHROPIC_BUDGET_LOW = 2048;
    private static final int ANTHROPIC_BUDGET_MEDIUM = 8192;
    private static final int ANTHROPIC_BUDGET_HIGH = 16384;
    private static final int ANTHROPIC_BUDGET_XHIGH = 24576;
    private static final int ANTHROPIC_BUDGET_MAX = 32768;

    private ThinkingRequestApplier() {
    }

    /**
     * 就地修改请求体。返回是否写入了思考参数；false 表示没有可写内容，
     * 但上方对遗留字段的清除仍然生效（调用方统一序列化改写后的 body）。
     */
    public static boolean apply(SiteProtocol protocol, JsonObject body, ThinkingChoice choice) {
        if (protocol == null || body == null || choice == null) {
            return false;
        }
        // TLM 的 hasThinkingField 遗留字段与历史残留先清掉，避免与本次选择打架。
        body.remove("thinking");
        body.remove("reasoning_effort");
        body.remove("reasoning");

        switch (protocol) {
            case CHAT_COMPLETIONS -> {
                if (!choice.enabled()) {
                    // 关闭：写入国内站点通用的 thinking 扩展（与 TLM disableThinking 同形）。
                    JsonObject thinking = new JsonObject();
                    thinking.addProperty("type", "disabled");
                    body.add("thinking", thinking);
                    return true;
                }
                if (choice.level() != ThinkingLevel.DEFAULT) {
                    body.addProperty("reasoning_effort", choice.level().id());
                    return true;
                }
                // 开 + default：跟随上游默认行为，不注入任何参数。
                return false;
            }
            case RESPONSES -> {
                if (!choice.enabled()) {
                    body.add("reasoning", effort("none"));
                    return true;
                }
                if (choice.level() != ThinkingLevel.DEFAULT) {
                    body.add("reasoning", effort(choice.level().id()));
                    return true;
                }
                return false;
            }
            case ANTHROPIC_MESSAGES -> {
                if (!choice.enabled()) {
                    // Anthropic 省略 thinking 即关闭。
                    return false;
                }
                int budget = anthropicBudget(choice.level());
                body.addProperty("max_tokens", ProtocolRequestEncoder.DEFAULT_ANTHROPIC_MAX_TOKENS + budget);
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "enabled");
                thinking.addProperty("budget_tokens", budget);
                body.add("thinking", thinking);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static JsonObject effort(String value) {
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", value);
        return reasoning;
    }

    private static int anthropicBudget(ThinkingLevel level) {
        return switch (level) {
            case LOW -> ANTHROPIC_BUDGET_LOW;
            case MEDIUM -> ANTHROPIC_BUDGET_MEDIUM;
            case HIGH -> ANTHROPIC_BUDGET_HIGH;
            case XHIGH -> ANTHROPIC_BUDGET_XHIGH;
            case MAX -> ANTHROPIC_BUDGET_MAX;
            default -> ANTHROPIC_BUDGET_DEFAULT;
        };
    }
}

package com.xm2nd.tlmagenttweaks.common.thinking;

import java.util.Locale;

/**
 * 识别「上游不支持思考参数/思考强度」类失败，用于给出预设的玩家提示。
 * <p>
 * 只做关键字匹配：思考参数名 + 拒绝类措辞同时出现才判定，
 * 并在调用方额外要求本轮请求确实注入过思考参数，避免误伤无关错误。
 */
public final class ThinkingErrorClassifier {

    private static final String[] PARAM_HINTS = {
            "reasoning_effort", "reasoning.effort", "\"reasoning\"", "thinking"
    };

    private static final String[] REJECT_HINTS = {
            "unsupported", "unknown", "unrecognized", "not support", "not supported",
            "does not support", "invalid"
    };

    private ThinkingErrorClassifier() {
    }

    public static boolean isThinkingUnsupported(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        boolean paramRelated = false;
        for (String hint : PARAM_HINTS) {
            if (lower.contains(hint)) {
                paramRelated = true;
                break;
            }
        }
        if (!paramRelated) {
            return false;
        }
        for (String hint : REJECT_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}

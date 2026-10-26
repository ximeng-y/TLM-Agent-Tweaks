package com.xm2nd.tlmagenttweaks.common.stream;

import java.util.Locale;

/** 单次 HTTP 尝试的判定规则：成功判定、可重试状态码与「改用非流式」的触发条件。 */
final class HttpRetryRules {

    /** 错误正文的读取与上报上限，避免整个错误页进入内存、日志与聊天栏。 */
    static final int MAX_ERROR_BODY_CHARS = 4096;

    static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    /** 允许做一次非流式回退之外，指定可重试的 HTTP 状态码。 */
    static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    /**
     * 仅当服务端明确表示 stream/stream_options 不受支持时才允许非流式回退。
     * 鉴权、限流、普通参数错误与服务端错误均原样透传。
     */
    static boolean shouldRetryWithoutStream(int status, String body) {
        if (status == 401 || status == 403 || status == 404 || status == 429) {
            return false;
        }
        if (status != 400 && status != 415 && status != 422) {
            return false;
        }
        String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return text.matches("(?s).*\\bstream(?:ing|_options)?\\b.*")
                && (text.contains("not support") || text.contains("unsupported")
                || text.contains("unknown") || text.contains("unrecognized") || text.contains("invalid")
                || text.contains("cannot") || text.contains("does not support"));
    }

    static String truncate(String body) {
        if (body == null || body.length() <= MAX_ERROR_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_ERROR_BODY_CHARS);
    }

    private HttpRetryRules() {
    }
}

package com.xm2nd.tlmagenttweaks.common.stream;

/**
 * 只读增量事件。{@code requestId} 用于把同一逻辑请求的增量串起来，
 * 回退后继续沿用同一 requestId，订阅方据此清空旧增量。
 */
public record StreamEvent(String requestId, StreamEventType type, String text) {

    public static StreamEvent of(String requestId, StreamEventType type) {
        return new StreamEvent(requestId, type, "");
    }

    public static StreamEvent text(String requestId, StreamEventType type, String delta) {
        return new StreamEvent(requestId, type, delta == null ? "" : delta);
    }
}

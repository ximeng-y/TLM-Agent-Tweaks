package com.xm2nd.tlmagenttweaks.common.stream;

/**
 * 单个已完整分帧的 SSE 事件。{@code data} 为多行 data 以 LF 连接后的结果。
 */
public record SseEvent(String event, String data, String id, long retryMillis) {
}

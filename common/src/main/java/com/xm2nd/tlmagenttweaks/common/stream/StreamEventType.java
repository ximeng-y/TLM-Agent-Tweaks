package com.xm2nd.tlmagenttweaks.common.stream;

/**
 * 只读增量事件类型。事件仅用于展示订阅，不参与聚合结果，也不携带任何工具执行语义。
 */
public enum StreamEventType {
    /** 请求开始（已获得响应头之前的逻辑起点）。 */
    STARTED,
    /** 正文增量。 */
    TEXT_DELTA,
    /** 可公开的思考/摘要增量（不含 signature、encrypted 等 opaque 内容）。 */
    REASONING_DELTA,
    /** 流式传输失败，即将回退非流式；订阅方应清空已展示的增量。 */
    FALLBACK,
    /** 请求最终成功（流式聚合成功，或非流式回退/直返 JSON 成功）。 */
    COMPLETED,
    /** 最终失败：回退仍失败，或最终拿到的是 HTTP 错误响应。 */
    FAILED,
    /** 调用方主动取消，不发生回退。 */
    CANCELLED
}

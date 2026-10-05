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
    /** 已聚合出完整结果。 */
    COMPLETED,
    /** 流式与回退均失败，作为最终失败上报。 */
    FAILED,
    /** 调用方主动取消，不发生回退。 */
    CANCELLED
}

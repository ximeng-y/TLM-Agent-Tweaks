package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonObject;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;

/**
 * 单次流式响应的协议聚合器：把协议增量事件聚合成一份完整的“原始协议响应对象”，
 * 交给 {@code ProtocolResponseDecoder} 做与普通响应完全相同的归一化与元数据保存。
 * <p>
 * {@link #finish()} 只在流真正收到协议终止标志时返回结果，否则抛出异常触发非流式回退。
 */
public interface ProtocolStreamAggregator {

    /**
     * 处理一个 SSE 事件的原始 data 文本（未解析 JSON）。
     *
     * @param data SSE {@code data} 字段内容
     * @throws ProtocolException 数据损坏、协议错误块或不完整状态
     */
    void accept(String data) throws ProtocolException;

    /**
     * 是否已收到协议终止标志（Chat {@code [DONE]}、Responses
     * {@code response.completed}、Anthropic {@code message_stop}）。
     * 为 true 时可提前结束读取，不必等待 keep-alive 连接的 EOF。
     */
    boolean complete();

    /**
     * 流输入结束：确认终止标志并返回完整原始协议对象。
     *
     * @throws ProtocolException 缺少终止标志、聚合不完整或半途出错
     */
    JsonObject finish() throws ProtocolException;
}

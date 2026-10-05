package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonObject;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;

/**
 * 请求级只读事件监听器：接收增量事件，并感知最终聚合出的完整原始协议对象。
 * <p>
 * 只读：任何实现都不能改变请求、工具执行或聚合结果。监听者抛出的异常必须被隔离。
 */
public interface ProtocolStreamRequestListener {

    /**
     * 增量/状态事件。实现方可忽略，但不得阻塞模型请求线程。
     */
    default void onEvent(String requestId, StreamEventType type, String text) {
    }

    /**
     * 流式成功聚合后、进入原 TLM 响应处理前触发一次。
     * 失败、取消、回退到非流式成功时不触发（回退后最终结果由原处理路径负责）。
     */
    default void onAggregated(String requestId, SiteProtocol protocol, JsonObject rawResponse) {
    }
}

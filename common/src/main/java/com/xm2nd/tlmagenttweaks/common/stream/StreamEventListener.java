package com.xm2nd.tlmagenttweaks.common.stream;

/**
 * 只读增量监听器。监听者抛出的异常必须由分发方隔离，不能影响模型请求本身。
 */
@FunctionalInterface
public interface StreamEventListener {
    void onEvent(StreamEvent event);
}

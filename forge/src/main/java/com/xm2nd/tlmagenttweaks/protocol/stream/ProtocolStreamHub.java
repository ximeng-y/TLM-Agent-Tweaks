package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.stream.StreamEvent;
import com.xm2nd.tlmagenttweaks.common.stream.StreamEventType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 服务端只读流式增量事件中心。
 * <p>
 * 所有导出请求的增量、回退、完成、失败、取消都会经过这里。订阅者只能读，
 * 不能影响聚合结果；订阅者抛出的异常一律隔离。客户端的网络与展示（#2）尚未实现，
 * 本类只提供订阅入口，不主动向聊天/气泡/TTS 注入任何内容。
 */
public final class ProtocolStreamHub {
    private static final List<Consumer<ProtocolStreamEvent>> SUBSCRIBERS = new CopyOnWriteArrayList<>();

    private ProtocolStreamHub() {
    }

    public static void subscribe(Consumer<ProtocolStreamEvent> subscriber) {
        if (subscriber != null) {
            SUBSCRIBERS.add(subscriber);
        }
    }

    public static void unsubscribe(Consumer<ProtocolStreamEvent> subscriber) {
        SUBSCRIBERS.remove(subscriber);
    }

    public static void onEvent(ProtocolStreamListener.ProtocolStreamContext context,
                               String requestId, StreamEventType type, String text) {
        if (SUBSCRIBERS.isEmpty() || context == null) {
            return;
        }
        StreamEvent event = new StreamEvent(requestId, type, text == null ? "" : text);
        ProtocolStreamEvent platformEvent = new ProtocolStreamEvent(context, event);
        for (Consumer<ProtocolStreamEvent> subscriber : SUBSCRIBERS) {
            try {
                subscriber.accept(platformEvent);
            } catch (Throwable throwable) {
                TlmAgentTweaks.LOGGER.warn("流式增量订阅者抛出异常（已隔离）", throwable);
            }
        }
    }

    /**
     * 带请求上下文的只读平台事件：订阅方据此判断增量应展示给哪个女仆/站点/气泡。
     */
    public record ProtocolStreamEvent(ProtocolStreamListener.ProtocolStreamContext context, StreamEvent event) {
    }
}

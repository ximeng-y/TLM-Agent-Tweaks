package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.stream.ProtocolStreamRequestListener;
import com.xm2nd.tlmagenttweaks.common.stream.StreamEventType;

import java.util.UUID;

/**
 * 请求级只读事件监听器：在创建时捕获不可变请求上下文（女仆 UUID、站点 ID、
 * 等待气泡 ID、维度），把带上下文的平台事件转交 {@link ProtocolStreamHub}。
 * <p>
 * 不再使用无上下文全局单例：订阅方（#2）必须能知道增量属于哪个女仆/站点/气泡。
 * 日志只记录请求 ID、协议、事件类型与字符数，不打印模型正文。
 */
public final class ProtocolStreamListener implements ProtocolStreamRequestListener {
    private final ProtocolStreamContext context;

    public ProtocolStreamListener(LLMOpenAISite site, LLMCallback callback) {
        this.context = ProtocolStreamContext.capture(site, callback);
    }

    @Override
    public void onEvent(String requestId, StreamEventType type, String text) {
        ProtocolStreamHub.onEvent(context, requestId, type, text);
        if (type == StreamEventType.COMPLETED || type == StreamEventType.FALLBACK
                || type == StreamEventType.FAILED || type == StreamEventType.CANCELLED) {
            TlmAgentTweaks.LOGGER.debug("流式事件 requestId={} maid={} site={} type={}",
                    requestId, context.maidId(), context.siteId(), type);
        }
    }

    @Override
    public void onAggregated(String requestId, SiteProtocol protocol, JsonObject rawResponse) {
        TlmAgentTweaks.LOGGER.debug("流式聚合完成 requestId={} maid={} site={} protocol={}",
                requestId, context.maidId(), context.siteId(), protocol.id());
    }

    /**
     * 按请求捕获的不可变上下文。字段可能为 null（如无女仆/无等待气泡的调用）。
     */
    public record ProtocolStreamContext(String maidId, String siteId, String dimension,
                                        Long waitingBubbleId) {
        static ProtocolStreamContext capture(LLMOpenAISite site, LLMCallback callback) {
            String maidId = null;
            String dimension = null;
            Long waitingBubbleId = null;
            try {
                if (callback != null) {
                    EntityMaid maid = callback.getMaid();
                    if (maid != null) {
                        UUID uuid = maid.getUUID();
                        maidId = uuid == null ? null : uuid.toString();
                        try {
                            dimension = maid.level().dimension().location().toString();
                        } catch (Throwable ignored) {
                            dimension = null;
                        }
                    }
                    waitingBubbleId = callback.getWaitingChatBubbleId();
                }
            } catch (Throwable ignored) {
                // 上下文捕获失败不应影响请求本身
            }
            String siteId = site == null ? null : site.id();
            return new ProtocolStreamContext(maidId, siteId, dimension, waitingBubbleId);
        }
    }
}

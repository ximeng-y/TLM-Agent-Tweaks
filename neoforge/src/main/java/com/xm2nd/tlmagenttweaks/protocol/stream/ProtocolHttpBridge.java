package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.stream.StreamingHttpTransport;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolBridge;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge 侧流式请求入口：冻结请求、接入带上下文的只读事件中心、登记可取消句柄。
 * <p>
 * 不维护 per-maid 的第二个索引，避免异步 unregister 与主线程取消并发读写集合；
 * 按女仆取消时直接遍历请求规模很小的 {@link #PENDING} 并按不可变 maidId 过滤。
 */
public final class ProtocolHttpBridge {
    /** 显式声明取消语义替代物，避免与 {@link CompletableFuture} 的取消语义混淆。 */
    public interface CancelHandle {
        void cancel();
    }

    private static final StreamingHttpTransport TRANSPORT = new StreamingHttpTransport();
    private static final Map<LLMCallback, Entry> PENDING = new ConcurrentHashMap<>();
    private static final Set<LLMCallback> CANCELLED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ProtocolHttpBridge() {
    }

    public static CompletableFuture<HttpResponse<String>> send(HttpClient client, HttpRequest request,
                                                               LLMOpenAISite site, LLMCallback callback) {
        SiteProtocol protocol = ProtocolBridge.protocolOf(site);
        ProtocolStreamListener listener = new ProtocolStreamListener(site, callback);
        StreamingHttpTransport.FrozenRequest frozen = StreamingHttpTransport.freeze(request);
        StreamingHttpTransport.Call call = TRANSPORT.sendCall(client, frozen, protocol, listener);
        register(callback, call);
        return call.future().whenComplete((response, throwable) -> unregister(callback));
    }

    private static void register(LLMCallback callback, StreamingHttpTransport.Call call) {
        if (callback == null) {
            return;
        }
        PENDING.put(callback, new Entry(maidId(callback), call::cancel));
    }

    private static void unregister(LLMCallback callback) {
        if (callback != null) {
            PENDING.remove(callback);
        }
    }

    /**
     * 取消指定女仆当前所有在途请求（实体移除/离开世界）。取消不回退。
     */
    public static void cancelMaid(UUID maidId) {
        if (maidId == null) {
            return;
        }
        String target = maidId.toString();
        for (Map.Entry<LLMCallback, Entry> entry : List.copyOf(PENDING.entrySet())) {
            if (target.equals(entry.getValue().maidId)) {
                cancel(entry.getKey());
            }
        }
    }

    /**
     * 服务器停止时取消所有在途请求并清空登记表。取消不回退。
     */
    public static void cancelAll() {
        for (LLMCallback callback : List.copyOf(PENDING.keySet())) {
            cancel(callback);
        }
        PENDING.clear();
    }

    /**
     * 该回调是否已被生命周期取消。取消不得触发 onFailure，也不回退。
     */
    public static boolean wasCancelled(LLMCallback callback) {
        return callback != null && CANCELLED.contains(callback);
    }

    /**
     * 请求终态后清除取消标记，避免回调对象长期被集合引用。
     */
    public static void clearCancelled(LLMCallback callback) {
        if (callback != null) {
            CANCELLED.remove(callback);
        }
    }

    private static void cancel(LLMCallback callback) {
        Entry entry = PENDING.remove(callback);
        if (entry != null) {
            CANCELLED.add(callback);
            entry.handle.cancel();
        }
    }

    private static String maidId(LLMCallback callback) {
        try {
            return callback.getMaid() == null ? null : callback.getMaid().getUUID().toString();
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static final class Entry {
        private final String maidId;
        private final CancelHandle handle;

        private Entry(String maidId, CancelHandle handle) {
            this.maidId = maidId;
            this.handle = handle;
        }
    }
}

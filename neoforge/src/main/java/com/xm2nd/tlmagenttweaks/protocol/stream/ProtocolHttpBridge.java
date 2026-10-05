package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.stream.RequestRetryPolicy;
import com.xm2nd.tlmagenttweaks.common.stream.SiteThrottleScheduler;
import com.xm2nd.tlmagenttweaks.common.stream.StreamingHttpTransport;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolBridge;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge 侧请求出口：冻结请求、接入只读事件中心、登记取消句柄与整轮预算。
 * <p>
 * 同一个 {@link StreamingHttpTransport.Session} 会承载本轮的全部实际 HTTP 尝试
 * （状态码重试与非流式回退），因此这里只需要登记一次取消句柄。
 * <p>
 * 不维护 per-maid 的第二个索引：按女仆取消时直接遍历请求规模很小的 {@link #PENDING}
 * 并按不可变 maidId 过滤，避免异步 unregister 与主线程取消并发读写集合。
 */
public final class ProtocolHttpBridge {
    /** 显式声明取消语义替代物，避免与 {@link CompletableFuture} 的取消语义混淆。 */    public interface CancelHandle {
        void cancel();
    }

    /** 站点级发送调度器：跨请求共享，进程内单例。 */
    private static final SiteThrottleScheduler GATE = new SiteThrottleScheduler(
            RetrySettings.SITE_SPACING, RetrySettings.RETRY_DELAY);
    private static final StreamingHttpTransport TRANSPORT = new StreamingHttpTransport(null, GATE);
    private static final Map<LLMCallback, Entry> PENDING = new ConcurrentHashMap<>();
    private static final Set<LLMCallback> CANCELLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** 已被整轮超时终结的回调：只上报一次最终失败，之后的迟到结果一律丢弃。 */
    private static final Set<LLMCallback> TIMED_OUT =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean stopping;

    private ProtocolHttpBridge() {
    }

    public static CompletableFuture<HttpResponse<String>> send(HttpClient client, HttpRequest request,
                                                               com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite site,
                                                               LLMCallback callback) {
        if (isInactive(callback)) {
            return CompletableFuture.failedFuture(new StreamingHttpTransport.CancelledException());
        }
        if (ProtocolRetryBudget.isExpired(callback)) {
            // 整轮预算已耗尽：拒绝后续（含工具续接产生的）请求，且不再产生第二次失败。
            markTimedOut(callback);
            return CompletableFuture.failedFuture(timeoutFailure());
        }
        SiteProtocol protocol = ProtocolBridge.protocolOf(site);
        RequestRetryPolicy policy = RetrySettings.policy();
        ProtocolStreamListener listener = new ProtocolStreamListener(site, callback);
        StreamingHttpTransport.FrozenRequest frozen = StreamingHttpTransport.freeze(request);
        String siteId = site == null ? null : site.id();
        // 提示器随本次请求的配置快照构造，配置在请求进行中被改动不影响本轮。
        RetryNotifier notifier = new RetryNotifier(callback, policy);        StreamingHttpTransport.Call call = TRANSPORT.prepare(client, frozen, protocol, listener,
                siteId, ProtocolRetryBudget.budgetFor(callback), policy, notifier);
        register(callback, call::cancel, notifier);
        // 发送与登记之间可能恰好发生实体离场，不能依赖只触发一次的离场事件兜底。
        if (isInactive(callback)) {
            cancel(callback);
            return call.future();
        }
        call.start();
        return call.future().whenComplete((response, throwable) -> unregister(callback));
    }

    public static boolean isInactive(LLMCallback callback) {
        if (callback == null || callback.getMaid() == null) {
            return true;
        }
        var maid = callback.getMaid();
        return stopping || !maid.isAlive() || maid.isRemoved()
                || !(maid.level() instanceof ServerLevel level)
                || level.getServer() != ServerLifecycleHooks.getCurrentServer()
                || level.getServer().isStopped();
    }

    private static void register(LLMCallback callback, CancelHandle handle, RetryNotifier notifier) {
        if (callback == null) {
            return;
        }
        PENDING.put(callback, new Entry(maidId(callback), handle, notifier));
        // 工具续接沿用同一个回调对象，因此剩余时间必须重新计算：
        // 每轮请求都按完整预算重排一次，整轮上限会随着请求次数无限延长。
        notifier.scheduleDeadline(ProtocolRetryBudget.remainingNanos(callback),
                () -> onBudgetExpired(callback));
    }

    private static void unregister(LLMCallback callback) {
        if (callback == null) {
            return;
        }
        Entry entry = PENDING.remove(callback);
        if (entry != null) {
            entry.notifier.cancelDeadline();
            entry.notifier.finish();
        }
    }

    /**
     * 整轮预算到期：作废该轮在途请求与排队任务，并让超时原因成为这次对话的最终失败。
     * <p>
     * 只作废已经超时的那一轮：同一女仆可能同时还有别的、开始得更晚因而仍在预算内的对话，
     * 按女仆一刀切会把它们一起静默取消。
     */
    private static void onBudgetExpired(LLMCallback callback) {
        if (callback == null) {
            return;
        }
        markTimedOut(callback);
        for (LLMCallback pending : List.copyOf(PENDING.keySet())) {
            if (TIMED_OUT.contains(pending) || ProtocolRetryBudget.isExpired(pending)) {
                cancel(pending);
            }
        }
    }

    /**
     * 该回调是否已被整轮超时终结；首次调用返回 {@code true}，之后恒为 {@code false}。
     * <p>
     * 标记不复位：同一次对话的后续工具轮次都不应再获得新的 240 秒。
     */
    public static boolean markTimedOut(LLMCallback callback) {
        return callback != null && TIMED_OUT.add(callback);
    }

    /** 整轮预算耗尽时使用的统一失败原因。 */
    public static Throwable timeoutFailure() {
        return new HttpTimeoutException("LLM conversation exceeded the %d s budget"
                .formatted(ProtocolRetryBudget.BUDGET_NANOS / 1_000_000_000L));
    }

    /** 取消指定女仆当前所有在途请求（实体移除/离开世界）。取消不回退。 */
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
     * 服务器停止时取消所有在途请求、丢弃站点排队任务并清空登记表。
     */
    public static void cancelAll() {
        stopping = true;
        ProtocolRetryBudget.cancelAll();
        GATE.discardAll();
        invalidateAll();
    }

    /**
     * 作废全部在途请求并清空登记表，但不取消整轮预算。
     * <p>
     * 停服时由 {@link #cancelAll()} 连预算一起作废；单次对话被全局打断时只作废请求，
     * 使同一次对话的后续轮次仍受原截止时间约束，而不是重新获得完整的 240 秒。
     */
    static void invalidateAll() {
        for (LLMCallback callback : List.copyOf(PENDING.keySet())) {
            cancel(callback);
        }
        PENDING.clear();
    }

    public static void onServerStarted() {
        stopping = false;
    }

    /**
     * 该回调是否应被静默丢弃。
     * <p>
     * 生命周期取消（实体离场 / 停服）不回退、也不触发业务失败回调；
     * 整轮超时虽然同样取消了在途请求，但必须让超时原因走到 TLM 的失败回调上，
     * 否则玩家看不到任何提示，因此这种情况不算静默取消。
     */
    public static boolean wasCancelled(LLMCallback callback) {
        return callback != null && CANCELLED.contains(callback) && !TIMED_OUT.contains(callback);
    }

    /** 请求终态后清除取消标记，避免回调对象长期被集合引用。 */
    public static void clearCancelled(LLMCallback callback) {
        if (callback != null) {
            CANCELLED.remove(callback);
        }
    }

    /** 丢弃站点级排队与冷却状态（跨存档复用站点对象时使用）。 */
    static void discardQueuedSends() {
        GATE.discardAll();
    }

    private static void cancel(LLMCallback callback) {
        Entry entry = PENDING.remove(callback);
        if (entry == null) {
            return;
        }
        CANCELLED.add(callback);
        entry.handle.cancel();
        entry.notifier.cancelDeadline();
        entry.notifier.finish();
    }

    private static String maidId(LLMCallback callback) {
        UUID uuid = maidOf(callback);
        return uuid == null ? null : uuid.toString();
    }

    private static UUID maidOf(LLMCallback callback) {
        try {
            return callback == null || callback.getMaid() == null ? null : callback.getMaid().getUUID();
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static final class Entry {
        private final String maidId;
        private final CancelHandle handle;
        private final RetryNotifier notifier;

        private Entry(String maidId, CancelHandle handle, RetryNotifier notifier) {
            this.maidId = maidId;
            this.handle = handle;
            this.notifier = notifier;
        }
    }
}

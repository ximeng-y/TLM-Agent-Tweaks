package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
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
 * 统一终态：正常完成、失败完成、静默取消、预算到期都在本桥上收口。
 * 终态由 取消标记（{@link #CANCELLED}）、超时标记（{@link #TIMED_OUT}）与
 * 完成标记（{@link #FINISHED}）三类弱引用状态决定，设置后不再清除，
 * 只随回调对象被回收而消失。
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
    /**
     * 显式/生命周期取消标记：弱引用，一旦设置不再清除。
     * 业务主动取消（{@link #cancel}）与实体离场/停服都落到这里；
     * 只要标记还在，发送、结果派发与迟到回调一律被静默丢弃。
     */
    private static final Set<LLMCallback> CANCELLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /**
     * 已被整轮超时终结的回调：值保留唯一一次派发的超时异常对象，作为合法超时通知的识别许可。
     * 只上报一次最终失败；许可消耗后值置 null，但键保留（弱引用），迟到结果仍被丢弃。
     */
    private static final Map<LLMCallback, Throwable> TIMED_OUT =
            Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * 正常终态标记：统一成功/失败派发与业务 {@code complete()} 都会落到这里。
     * 阻断「HTTP 已结束但业务派发尚未执行」与正常完成后迟到的结果重新进入业务。
     */
    private static final Set<LLMCallback> FINISHED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean stopping;

    private ProtocolHttpBridge() {
    }

    public static CompletableFuture<HttpResponse<String>> send(HttpClient client, HttpRequest request,
                                                               com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite site,
                                                               LLMCallback callback) {
        if (isTerminal(callback) || isInactive(callback)) {
            // 取消/完成/超时早于发送：不取预算、不发请求。
            return CompletableFuture.failedFuture(new StreamingHttpTransport.CancelledException());
        }
        if (ProtocolRetryBudget.isExpired(callback)) {
            // 整轮预算已耗尽：拒绝后续（含工具续接产生的）请求，且不再产生第二次失败。
            expire(callback, request);
            return CompletableFuture.failedFuture(timeoutFailure());
        }
        SiteProtocol protocol = ProtocolBridge.protocolOf(site);
        RequestRetryPolicy policy = RetrySettings.policy();
        ProtocolStreamListener listener = new ProtocolStreamListener(site, callback);
        StreamingHttpTransport.FrozenRequest frozen = StreamingHttpTransport.freeze(request);
        String siteId = site == null ? null : site.id();
        // 提示器随本次请求的配置快照构造，配置在请求进行中被改动不影响本轮。
        RetryNotifier notifier = new RetryNotifier(callback, policy);
        StreamingHttpTransport.Call call = TRANSPORT.prepare(client, frozen, protocol, listener,
                siteId, ProtocolRetryBudget.budgetFor(callback), policy, notifier);
        register(callback, call::cancel, notifier, request);
        // 发送与登记之间可能恰好发生实体离场/取消/完成，不能依赖只触发一次的离场事件兜底。
        if (isInactive(callback)) {
            // 实体离场/停服：显式生命周期取消，保留取消标记。
            cancel(callback);
            return call.future();
        }
        if (isTerminal(callback)) {
            // 并发已完成终态：只作废传输句柄，不再重复打标记。
            releaseResources(callback);
            return call.future();
        }
        call.start();
        return call.future().whenComplete((response, throwable) -> notifier.stopNotifications());
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

    private static void register(LLMCallback callback, CancelHandle handle, RetryNotifier notifier,
                                 HttpRequest request) {
        if (callback == null) {
            return;
        }
        synchronized (PENDING) {
            Entry entry = new Entry(maidId(callback), handle, notifier);
            Entry previous = PENDING.put(callback, entry);
            if (previous != null) {
                previous.notifier.finish();
            }
            // 续接只重排剩余时间；HTTP 完成后仍保留计时，直至回调进入统一终态。
            notifier.scheduleDeadline(ProtocolRetryBudget.remainingNanos(callback),
                    () -> onBudgetExpired(callback, entry, request));
        }
    }

    /**
     * 关闭该回调整轮登记：移除在途登记、关闭 deadline 与后续等待提示、释放整轮预算，
     * 并打上完成标记。幂等；不发送成功或失败回调，不操作历史/业务 pending/互聊链/气泡。
     * <p>
     * 不清除已有取消/超时标记，防止迟到回调重新变成有效请求。
     */
    public static void finishConversation(LLMCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (PENDING) {
            Entry entry = PENDING.remove(callback);
            if (entry != null) {
                entry.notifier.finish();
            }
        }
        FINISHED.add(callback);
        ProtocolRetryBudget.release(callback);
    }

    /**
     * 整轮预算到期：作废该轮在途请求与排队任务，并让超时原因成为这次对话的最终失败。
     * <p>
     * 只作废已经超时的那一轮：同一女仆可能同时还有别的、开始得更晚因而仍在预算内的对话，
     * 按女仆一刀切会把它们一起静默取消。
     */
    private static void onBudgetExpired(LLMCallback callback, Entry expected, HttpRequest request) {
        synchronized (PENDING) {
            if (PENDING.get(callback) != expected) {
                return;
            }
            expire(callback, request);
        }
    }

    /**
     * 真正到期的唯一收尾：先登记超时标记并清理请求/计时器/预算，再派发唯一一次超时失败。
     * 不依赖子类是否调用父类。超时不算显式取消，不写入 {@link #CANCELLED}。
     */
    private static void expire(LLMCallback callback, HttpRequest request) {
        Throwable failure = timeoutFailure();
        synchronized (TIMED_OUT) {
            if (TIMED_OUT.containsKey(callback)) {
                return;
            }
            TIMED_OUT.put(callback, failure);
        }
        releaseResources(callback);
        try {
            callback.runOnServerThread(() -> {
                try {
                    // 通知已排队但尚未执行时，业务可能又调用了 cancel / complete：此时抑制本次超时通知。
                    if (isInactive(callback) || CANCELLED.contains(callback) || wasFinished(callback)) {
                        return;
                    }
                    callback.onFailure(request, failure, ErrorCode.REQUEST_RECEIVED_ERROR);
                } catch (Throwable throwable) {
                    TlmAgentTweaks.LOGGER.warn("整轮预算超时通知抛出异常（已隔离）", throwable);
                } finally {
                    closeTimeoutLicense(callback, failure);
                }
            });
        } catch (Throwable throwable) {
            TlmAgentTweaks.LOGGER.warn("整轮预算超时通知调度失败（已忽略）", throwable);
        }
    }

    public static boolean wasTimedOut(LLMCallback callback) {
        return callback != null && TIMED_OUT.containsKey(callback);
    }

    /**
     * 超时后的失败许可检查：仅放行整轮超时主动派发的那一次失败（异常对象相同即许可），
     * 且不在此消耗许可——唯一派发结束后由 {@link #closeTimeoutLicense} 关闭。
     */
    public static boolean acceptFailure(LLMCallback callback, Throwable failure) {
        if (callback == null) {
            return false;
        }
        synchronized (TIMED_OUT) {
            if (!TIMED_OUT.containsKey(callback)) {
                return true;
            }
            return TIMED_OUT.get(callback) == failure;
        }
    }

    /** 唯一一次超时失败派发结束后关闭通知许可，但保留「已经超时」的弱引用标记。 */
    private static void closeTimeoutLicense(LLMCallback callback, Throwable failure) {
        if (callback == null) {
            return;
        }
        synchronized (TIMED_OUT) {
            if (TIMED_OUT.get(callback) == failure) {
                TIMED_OUT.put(callback, null);
            }
        }
    }

    /** 整轮预算耗尽时使用的统一失败原因。 */
    public static Throwable timeoutFailure() {
        return new HttpTimeoutException("LLM conversation exceeded the %d s budget"
                .formatted(ProtocolRetryBudget.BUDGET_NANOS / 1_000_000_000L));
    }

    /**
     * 统一成功派发：在公共响应调用点（最终文本出口）由 business 方法之前完成本轮清理，
     * 然后原样调用实际回调对象的覆盖方法。业务派发落在服务端主线程，执行前再次检查取消状态。
     *
     * @param responseChat 原始成功响应（保留原对象，不重建）
     */
    public static void dispatchSuccess(LLMCallback callback, Object responseChat) {
        if (callback == null || isTerminal(callback)) {
            return;
        }
        finishConversation(callback);
        if (isInactive(callback)) {
            return;
        }
        try {
            callback.runOnServerThread(() -> {
                if (isInactive(callback) || wasTimedOut(callback) || CANCELLED.contains(callback)) {
                    return;
                }
                try {
                    ((ResponseCallback) (Object) callback).onSuccess(responseChat);
                } catch (Throwable throwable) {
                    TlmAgentTweaks.LOGGER.warn("LLM 成功回调抛出异常（已隔离）", throwable);
                }
            });
        } catch (Throwable throwable) {
            TlmAgentTweaks.LOGGER.warn("LLM 成功回调调度失败（已忽略）", throwable);
        }
    }

    /**
     * 统一失败派发：在公共响应调用点（传输/HTTP/解析/业务校验失败）调用实际回调对象的覆盖方法，
     * 保留原异常与错误码。调用前完成本轮清理；业务派发落在服务端主线程，执行前再次检查取消状态。
     */
    public static void dispatchFailure(LLMCallback callback, HttpRequest request, Throwable throwable, int errorCode) {
        if (callback == null || isTerminal(callback)) {
            return;
        }
        finishConversation(callback);
        if (isInactive(callback)) {
            return;
        }
        try {
            callback.runOnServerThread(() -> {
                if (isInactive(callback) || wasTimedOut(callback) || CANCELLED.contains(callback)) {
                    return;
                }
                try {
                    callback.onFailure(request, throwable, errorCode);
                } catch (Throwable t) {
                    TlmAgentTweaks.LOGGER.warn("LLM 失败回调抛出异常（已隔离）", t);
                }
            });
        } catch (Throwable t) {
            TlmAgentTweaks.LOGGER.warn("LLM 失败回调调度失败（已忽略）", t);
        }
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
            cancelKeepingBudget(callback);
        }
        PENDING.clear();
    }

    public static void onServerStarted() {
        stopping = false;
    }

    /**
     * 该回调是否被显式/生命周期取消（设置后持久保存，不再因消费一次结果而清除）。
     * <p>
     * 生命周期取消（实体离场 / 停服）与业务 {@code cancel} 都不回退、也不触发业务失败回调；
     * 整轮超时不属于显式取消，由 {@link #wasTimedOut} 单独表达。
     */
    public static boolean wasCancelled(LLMCallback callback) {
        return callback != null && CANCELLED.contains(callback) && !wasTimedOut(callback);
    }

    /** 公开生命周期查询：显式或生命周期取消状态；正常完成、真实超时不视为显式取消。 */
    public static boolean isCancelled(LLMCallback callback) {
        return wasCancelled(callback);
    }

    /** 完成标记：统一成功/失败派发或业务 complete 已关闭该回调整轮登记。 */
    public static boolean wasFinished(LLMCallback callback) {
        return callback != null && FINISHED.contains(callback);
    }

    /** 终态判断：取消、超时、正常完成任一成立即视为已终态，不再进入发送与结果派发。 */
    public static boolean isTerminal(LLMCallback callback) {
        return callback == null || wasCancelled(callback) || wasTimedOut(callback) || wasFinished(callback);
    }

    /**
     * 静默取消单个回调：作废 HTTP/排队/重试/deadline、释放预算、禁止后续重发与结果派发。
     * 按**原始回调对象**生效；即使当前没有 {@link #PENDING} 登记也会保留取消标记，
     * 覆盖「取消早于发送」与「HTTP 已结束但业务派发尚未执行」。幂等。
     */
    public static void cancel(LLMCallback callback) {
        if (callback == null) {
            return;
        }
        cancelAllWith(callback, true);
    }

    /**
     * 取消登记并清理整轮资源。显式/生命周期取消时写取消标记与完成标记；
     * 终态标记与预算释放先于传输句柄取消，避免同步完成的回调看到旧状态。
     */
    private static void cancelAllWith(LLMCallback callback, boolean markCancelled) {
        if (markCancelled) {
            CANCELLED.add(callback);
            FINISHED.add(callback);
        }
        releaseResources(callback);
    }

    /**
     * 只作废在途请求/排队/计时器并释放预算，**不写任何终态标记**。
     * 用于：已并发完成终态后的兜底清理（{@link #send} 后置检查）与超时收尾
     * （{@link #expire} 需在派发成功后才由 dispatch 打完成标记，超时标记已单独登记）。
     */
    private static void releaseResources(LLMCallback callback) {
        Entry entry;
        synchronized (PENDING) {
            entry = PENDING.remove(callback);
        }
        ProtocolRetryBudget.release(callback);
        if (entry != null) {
            entry.handle.cancel();
            entry.notifier.cancelDeadline();
            entry.notifier.finish();
        }
    }

    /**
     * 只作废请求/排队/计时器与完成标记，但**保留整轮预算**（世界卸载场景：同一回调
     * 若意外存活，它的截止时间仍应按原轮次计算，不被重置）。
     */
    private static void cancelKeepingBudget(LLMCallback callback) {
        CANCELLED.add(callback);
        FINISHED.add(callback);
        Entry entry;
        synchronized (PENDING) {
            entry = PENDING.remove(callback);
        }
        if (entry != null) {
            entry.handle.cancel();
            entry.notifier.cancelDeadline();
            entry.notifier.finish();
        }
    }

    /** 丢弃站点级排队与冷却状态（跨存档复用站点对象时使用）。 */
    static void discardQueuedSends() {
        GATE.discardAll();
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

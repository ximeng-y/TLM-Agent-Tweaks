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
 * 终态由单一弱引用状态 {@link #TERMINAL} 决定（值见 {@link #STATE_FINISHED} 等常量），
 * 设置后不再清除，只随回调对象被回收而消失。
 * <p>
 * 完成与超时的竞争认领是原子的：响应线程认领 {@code finished}、deadline 线程认领
 * {@code timed_out}，两者用 {@code putIfAbsent} 抢占同一个状态位，只有一个获胜者
 * 拥有唯一派发权，避免「响应被超时吞掉、超时被完成吞掉」的双杀交错。
 * 显式 {@code cancel} / {@code complete} 是业务主动动作，强制覆盖任何竞争状态，
 * 从而抑制尚未派发的超时/成功/失败通知。
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
     * 终态状态机：弱引用，键为回调实例。值仅允许 {@link #STATE_FINISHED}、
     * {@link #STATE_TIMED_OUT}、{@link #STATE_CANCELLED} 三种；空值表示仍在运行。
     * 竞争认领（完成/超时）用 {@code putIfAbsent} 抢占，只产生一个获胜者；
     * 业务 cancel / complete 用强制写入覆盖竞争状态。
     */
    private static final Map<LLMCallback, String> TERMINAL = new WeakHashMap<>();
    private static final String STATE_FINISHED = "finished";
    private static final String STATE_TIMED_OUT = "timed_out";
    private static final String STATE_CANCELLED = "cancelled";
    /**
     * 超时派发许可：值保留唯一一次派发的超时异常对象，作为合法超时通知的识别许可。
     * 只上报一次最终失败；许可消耗后值置 null，但键保留（弱引用），迟到结果仍被丢弃。
     */
    private static final Map<LLMCallback, Throwable> TIMED_OUT_FAILURE =
            Collections.synchronizedMap(new WeakHashMap<>());
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

    /** 读取回调的终态状态；空值表示仍在运行。与认领/覆盖共用 {@link #TERMINAL} 同一把锁，保证读-写原子。 */
    private static String stateOf(LLMCallback callback) {
        if (callback == null) {
            return null;
        }
        synchronized (TERMINAL) {
            return TERMINAL.get(callback);
        }
    }

    /**
     * 竞争认领：仅在当前无任何终态时写入。返回 {@code true} 表示本线程是第一个
     * 认领者（获胜者），拥有唯一派发权；已被另一竞争方（完成/超时）抢占时返回
     * {@code false}，调用方应放弃派发且不再打标记。
     */
    private static boolean claimTerminal(LLMCallback callback, String state) {
        if (callback == null) {
            return false;
        }
        synchronized (TERMINAL) {
            return TERMINAL.putIfAbsent(callback, state) == null;
        }
    }

    /** 业务主动覆盖：无条件写入终态（取消/完成优先于竞争终态，抑制尚未派发的通知）。 */
    private static void forceTerminal(LLMCallback callback, String state) {
        if (callback == null) {
            return;
        }
        synchronized (TERMINAL) {
            TERMINAL.put(callback, state);
        }
    }

    /**
     * 关闭该回调整轮登记：移除在途登记、关闭 deadline 与后续等待提示、释放整轮预算。
     * 幂等；不发送成功或失败回调，不操作历史/业务 pending/互聊链/气泡。
     * <p>
     * 业务自决结束（{@link LlmConversationLifecycle#complete}）时强制打完成标记，
     * 覆盖竞争状态并抑制尚未派发的通知。仅用于业务主动 complete；
     * 超时自身的收尾路径不得走这里（会覆盖 {@link #STATE_TIMED_OUT}），由
     * callbacks 兜底入口在 `wasTimedOut` 时跳过。
     */
    public static void finishConversation(LLMCallback callback) {
        if (callback == null) {
            return;
        }
        forceTerminal(callback, STATE_FINISHED);
        releaseResources(callback);
    }

    /**
     * 兜底幂等收尾：仅在当前无终态时竞争认领完成标记并释放资源。
     * 用于 {@code super.onSuccess}/{@code super.onFailure} 等重复进入路径——
     * 不覆盖已存在的超时/取消/完成终态，因此不会破坏超时语义
     * （超时后仅放行许可失败，其余迟到失败仍被丢弃）。
     */
    public static void tryFinish(LLMCallback callback) {
        if (callback == null) {
            return;
        }
        if (claimTerminal(callback, STATE_FINISHED)) {
            releaseResources(callback);
        }
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
     * 真正到期的唯一收尾：先原子认领超时终态并清理请求/计时器/预算，再派发唯一一次超时失败。
     * 若完成/取消已抢先认领（或业务已强制终态），则放弃派发，不产生第二次失败。
     * 超时不算显式取消，不写入 {@link #STATE_CANCELLED}。
     */
    private static void expire(LLMCallback callback, HttpRequest request) {
        Throwable failure = timeoutFailure();
        // 竞争认领：若响应线程已认领 finished 或业务已取消，这里立即放弃。
        if (!claimTerminal(callback, STATE_TIMED_OUT)) {
            return;
        }
        synchronized (TIMED_OUT_FAILURE) {
            TIMED_OUT_FAILURE.put(callback, failure);
        }
        releaseResources(callback);
        try {
            callback.runOnServerThread(() -> {
                try {
                    // 通知已排队但尚未执行时，业务可能又调用了 cancel / complete：
                    // 强制覆盖状态后这里不再等于 timed_out，本次超时通知被抑制。
                    if (isInactive(callback) || stateOf(callback) != STATE_TIMED_OUT) {
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
        return STATE_TIMED_OUT.equals(stateOf(callback));
    }

    /**
     * 超时后的失败许可检查：仅放行整轮超时主动派发的那一次失败（异常对象相同即许可），
     * 且不在此消耗许可——唯一派发结束后由 {@link #closeTimeoutLicense} 关闭。
     */
    public static boolean acceptFailure(LLMCallback callback, Throwable failure) {
        if (callback == null) {
            return false;
        }
        if (!wasTimedOut(callback)) {
            return true;
        }
        synchronized (TIMED_OUT_FAILURE) {
            return TIMED_OUT_FAILURE.get(callback) == failure;
        }
    }

    /** 唯一一次超时失败派发结束后关闭通知许可，但保留「已经超时」的弱引用状态。 */
    private static void closeTimeoutLicense(LLMCallback callback, Throwable failure) {
        if (callback == null) {
            return;
        }
        synchronized (TIMED_OUT_FAILURE) {
            if (TIMED_OUT_FAILURE.get(callback) == failure) {
                TIMED_OUT_FAILURE.put(callback, null);
            }
        }
    }

    /** 整轮预算耗尽时使用的统一失败原因。 */
    public static Throwable timeoutFailure() {
        return new HttpTimeoutException("LLM conversation exceeded the %d s budget"
                .formatted(ProtocolRetryBudget.BUDGET_NANOS / 1_000_000_000L));
    }

    /**
     * 统一成功派发：原子认领完成终态后在公共响应调用点（最终文本出口）完成本轮清理，
     * 然后原样调用实际回调对象的覆盖方法。业务派发落在服务端主线程，执行前再次检查取消状态。
     * 若超时/deadline 线程已抢先认领，则放弃派发，不会产生竞争双杀。
     *
     * @param responseChat 原始成功响应（保留原对象，不重建）
     */
    public static void dispatchSuccess(LLMCallback callback, Object responseChat) {
        if (callback == null || !claimTerminal(callback, STATE_FINISHED)) {
            return;
        }
        releaseResources(callback);
        if (isInactive(callback)) {
            return;
        }
        try {
            callback.runOnServerThread(() -> {
                if (isInactive(callback) || isCancelled(callback)) {
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
     * 统一失败派发：原子认领完成终态后在公共响应调用点（传输/HTTP/解析/业务校验失败）
     * 调用实际回调对象的覆盖方法，保留原异常与错误码。调用前完成本轮清理；
     * 业务派发落在服务端主线程，执行前再次检查取消状态。
     */
    public static void dispatchFailure(LLMCallback callback, HttpRequest request, Throwable throwable, int errorCode) {
        if (callback == null || !claimTerminal(callback, STATE_FINISHED)) {
            return;
        }
        releaseResources(callback);
        if (isInactive(callback)) {
            return;
        }
        try {
            callback.runOnServerThread(() -> {
                if (isInactive(callback) || isCancelled(callback)) {
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
     * 已超时后又显式取消仍视为取消（取消标记独立可查），取消优先抑制后续通知。
     * 整轮超时不视为显式取消。
     */
    public static boolean wasCancelled(LLMCallback callback) {
        return STATE_CANCELLED.equals(stateOf(callback));
    }

    /** 公开生命周期查询：显式或生命周期取消状态；正常完成、真实超时不视为显式取消。 */
    public static boolean isCancelled(LLMCallback callback) {
        return wasCancelled(callback);
    }

    /** 完成标记：统一成功/失败派发或业务 complete 已关闭该回调整轮登记。 */
    public static boolean wasFinished(LLMCallback callback) {
        return STATE_FINISHED.equals(stateOf(callback));
    }

    /** 终态判断：取消、超时、正常完成任一成立即视为已终态，不再进入发送与结果派发。 */
    public static boolean isTerminal(LLMCallback callback) {
        return stateOf(callback) != null;
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
     * 取消登记并清理整轮资源。显式/生命周期取消时强制写取消标记；
     * 终态标记与预算释放先于传输句柄取消，避免同步完成的回调看到旧状态。
     */
    private static void cancelAllWith(LLMCallback callback, boolean markCancelled) {
        if (markCancelled) {
            forceTerminal(callback, STATE_CANCELLED);
        }
        releaseResources(callback);
    }

    /**
     * 只作废在途请求/排队/计时器并释放预算，**不写任何终态标记**。
     * 用于：已并发完成终态后的兜底清理（{@link #send} 后置检查）与超时/派发收尾
     * （终态标记已由竞争认领或强制覆盖单独登记）。
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
     * 只作废请求/排队/计时器并写取消标记，但**保留整轮预算**（世界卸载场景：同一回调
     * 若意外存活，它的截止时间仍应按原轮次计算，不被重置）。
     */
    private static void cancelKeepingBudget(LLMCallback callback) {
        forceTerminal(callback, STATE_CANCELLED);
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

package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.stream.RequestRetryPolicy;
import com.xm2nd.tlmagenttweaks.common.stream.StreamingHttpTransport;
import com.xm2nd.tlmagenttweaks.thinking.ThinkingDisplayService;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重试期间的用户可见反馈：把等待气泡改成「正在重试」，并看管本轮对话的总时间预算。
 * <p>
 * 关键约束：
 * <ul>
 *   <li>重试不是失败：期间不输出聊天栏错误、不提前调用 TLM 的失败回调；</li>
 *   <li>气泡更新必须发生在服务端主线程，且每次刷新前重新校验请求与气泡是否仍然有效，
 *       避免迟到的刷新覆盖已经落地的最终回答；</li>
 *   <li>回调不可用（女仆已卸载/离开世界）时静默跳过，绝不因提示失败而中断请求。</li>
 * </ul>
 */
final class RetryNotifier implements StreamingHttpTransport.AttemptListener {

    /** 服务端主线程之外唯一需要计时的地方：整轮预算到期通知。 */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "tlm-retry-deadline");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TIMER::shutdownNow, "tlm-retry-shutdown"));
    }

    private final LLMCallback callback;
    private final RequestRetryPolicy policy;
    private final ThinkingDisplayService.Handle displayHandle;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean deadlineClosed = new AtomicBoolean();
    private volatile ScheduledFuture<?> deadline;

    RetryNotifier(LLMCallback callback, RequestRetryPolicy policy,
                  ThinkingDisplayService.Handle displayHandle) {
        this.callback = callback;
        this.policy = policy;
        this.displayHandle = displayHandle;
    }

    @Override
    public void onScheduled(StreamingHttpTransport.AttemptListener.Stage stage,
                            boolean firstAttempt, int retryOrdinal, boolean rateLimited,
                            StreamingHttpTransport.AttemptListener.Cause cause) {
        if (closed.get()) {
            return;
        }
        Component text;
        if (stage == StreamingHttpTransport.AttemptListener.Stage.SITE_QUEUE) {
            text = RetryBubble.siteQueue(retryOrdinal, policy.maxRetries(), firstAttempt);
        } else if (cause == StreamingHttpTransport.AttemptListener.Cause.FALLBACK) {
            // 非流式重发不消耗状态码重试次数，因此不显示「第几次」。
            text = RetryBubble.fallback();
        } else if (cause == StreamingHttpTransport.AttemptListener.Cause.TRANSPORT_RETRY) {
            text = RetryBubble.transportRetry();
        } else {
            text = RetryBubble.retry(retryOrdinal, policy.maxRetries(), rateLimited);
        }
        // 排队与重试提示要占用气泡副文本：先切回原生提示，思考链让位给 TLM 自己的文字。
        // 只有回退会关闭本轮的思考播放；传输重试与状态码重试之后仍会恢复滚动。
        ThinkingDisplayService.onNativeStage(callback, displayHandle,
                cause == StreamingHttpTransport.AttemptListener.Cause.FALLBACK);
        show(text);
    }

    @Override
    public void onSendStart(boolean firstAttempt, boolean fallback) {
        if (fallback) {
            // 非流式回退真正发出：明确当前模式与「已发出、等待响应」，
            // 避免整段重发期间看起来仍停在等待重试。
            ThinkingDisplayService.onNativeStage(callback, displayHandle, true);
            show(RetryBubble.fallbackSent());
            return;
        }
        // 流式重试真正发出时重新从空窗口开始播放思考；不会重播中断流残片。
        ThinkingDisplayService.onSendStart(callback, displayHandle, firstAttempt);
    }

    /**
     * 安排整轮预算到期通知；到点后由调用方作废本轮请求。
     *
     * @param remainingNanos 本轮对话的剩余时间（非本次请求的完整预算）
     */
    void scheduleDeadline(long remainingNanos, Runnable onExpire) {
        if (remainingNanos <= 0L) {
            // 预算已耗尽：立刻收口，不必等下一次调度。
            onExpire.run();
            return;
        }
        try {
            deadline = TIMER.schedule(() -> {
                if (deadlineClosed.compareAndSet(false, true)) {
                    try {
                        onExpire.run();
                    } catch (Throwable throwable) {
                        TlmAgentTweaks.LOGGER.warn("整轮预算到期处理抛出异常（已隔离）", throwable);
                    }
                }
            }, remainingNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            // 进程正在退出：不再需要预算通知。
        }
    }

    void cancelDeadline() {
        deadlineClosed.set(true);
        ScheduledFuture<?> current = deadline;
        deadline = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    /** 请求已进入终态：停止一切后续气泡刷新。 */
    void finish() {
        stopNotifications();
        cancelDeadline();
    }

    /** HTTP 已结束，但整轮预算仍需覆盖随后的异步工具执行。 */
    void stopNotifications() {
        closed.set(true);
    }

    /**
     * 切到服务端主线程刷新等待气泡。
     * <p>
     * 单次尝试可能连续经历「重试等待」与「站点排队」两次通知，因此刷新前必须重新读取
     * 回调当前的等待气泡 ID：{@code refreshThinkingText} 每次都会换成一个新的 ID。
     */
    private void show(Component text) {
        if (ProtocolHttpBridge.isInactive(callback)) {
            return;
        }
        try {
            callback.runOnServerThread(() -> {
                if (closed.get() || ProtocolHttpBridge.isInactive(callback)) {
                    return;
                }
                if (hasWaitingBubble(callback)) {
                    RetryBubble.apply(callback, text);
                }
            });
        } catch (Throwable throwable) {
            TlmAgentTweaks.LOGGER.debug("等待气泡刷新调度失败（已忽略）", throwable);
        }
    }

    /** 等待气泡是否仍然存在：已经给出回答或已被移除时不再写入提示。 */
    private static boolean hasWaitingBubble(LLMCallback callback) {
        try {
            EntityMaid maid = callback.getMaid();
            if (maid == null) {
                return false;
            }
            return maid.getChatBubbleManager().getChatBubble(callback.getWaitingChatBubbleId()) != null;
        } catch (Throwable throwable) {
            return false;
        }
    }
}

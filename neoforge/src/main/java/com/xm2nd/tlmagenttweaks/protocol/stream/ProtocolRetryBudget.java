package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.xm2nd.tlmagenttweaks.common.stream.ConversationBudget;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 逻辑对话预算登记表。
 * <p>
 * 一次「玩家一次对话 / 一次自话」的全过程共享同一个 {@link ConversationBudget}：
 * TLM 的工具续接不会新建回调——{@code addToolResult} 返回调用者自身，
 * {@code onFunctionCall} 最终执行的都是 {@code client.chat(同一个回调)}，
 * 因此同一轮的每次请求取到的是同一个预算实例，240 秒是整轮预算而不会被工具轮次重置。
 * <p>
 * 工具派发给子 agent 时产生的独立回调（以及与之并行的侧路回调）不属于主流程，
 * 各自持有自己的预算。
 * <p>
 * 登记键是回调对象本身（弱引用，避免长期持有第三方回调）：普通 HTTP 成功不能删除登记，
 * 因为随后可能还会执行工具并继续请求。清理不依赖子类是否调用父类
 * （{@code onSuccess} / {@code onFailure}）：统一成功/失败派发、业务 {@code complete()}
 * 与整轮超时收尾都会调用 {@link ProtocolHttpBridge#finishConversation}，
 * 由它在移除 {@code PENDING} 登记时顺带释放预算。
 */
public final class ProtocolRetryBudget {

    /** 单次逻辑对话的固定总预算。 */
    public static final long BUDGET_NANOS = 240L * 1_000_000_000L;

    private static final Map<LLMCallback, Budget> BUDGETS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ProtocolRetryBudget() {
    }

    /**
     * 取得该回调本轮对话的预算：同一回调再次发送、或同一轮的工具续接回调都返回同一个实例。
     * <p>
     * 首次调用即开始计时；若本轮已被超时或停服作废，返回的预算处于耗尽状态，
     * 调用方必须拒绝该次请求。
     */
    public static ConversationBudget budgetFor(LLMCallback callback) {
        if (callback == null) {
            return new Budget();
        }
        synchronized (BUDGETS) {
            Budget existing = BUDGETS.get(callback);
            if (existing != null) {
                return existing;
            }
            Budget created = new Budget();
            BUDGETS.put(callback, created);
            return created;
        }
    }

    /** 回调进入终态（统一成功/失败派发、{@link ProtocolHttpBridge#finishConversation}），结束本轮登记。 */
    public static void release(LLMCallback callback) {
        if (callback != null) {
            BUDGETS.remove(callback);
        }
    }

    /**
     * 该回调所在轮次还剩多少时间；已耗尽或未登记时返回 0。
     * <p>
     * 每次请求都要按剩余时间重排到期通知：工具续接沿用同一个回调对象，
     * 若按完整预算重新计时，整轮 240 秒的约束就会被每一轮工具调用无限延长。
     */
    public static long remainingNanos(LLMCallback callback) {
        Budget budget = budgetOf(callback);
        if (budget == null) {
            return 0L;
        }
        return Math.max(0L, budget.deadlineNanos() - System.nanoTime());
    }

    /** 该回调本轮是否已被超时/停服作废（会顺带把预算标记为耗尽）。 */
    public static boolean isExpired(LLMCallback callback) {
        Budget budget = budgetOf(callback);
        if (budget == null) {
            return false;
        }
        if (System.nanoTime() >= budget.deadlineNanos()) {
            budget.markExpired();
            return true;
        }
        return budget.expired.get();
    }

    /** 停服：作废全部在途整轮预算并清空登记。 */
    public static void cancelAll() {
        synchronized (BUDGETS) {
            for (Budget budget : BUDGETS.values()) {
                budget.markExpired();
            }
            BUDGETS.clear();
        }
    }

    private static Budget budgetOf(LLMCallback callback) {
        if (callback == null) {
            return null;
        }
        synchronized (BUDGETS) {
            return BUDGETS.get(callback);
        }
    }

    /** 整轮预算：截止时间在首次进入协议出口时确定，只能被标记耗尽。 */
    private static final class Budget implements ConversationBudget {
        private final long deadline = System.nanoTime() + BUDGET_NANOS;
        private final AtomicBoolean expired = new AtomicBoolean();

        @Override
        public long deadlineNanos() {
            return deadline;
        }

        @Override
        public boolean isExpired() {
            return expired.get() || System.nanoTime() >= deadline;
        }

        @Override
        public void markExpired() {
            expired.set(true);
        }
    }
}

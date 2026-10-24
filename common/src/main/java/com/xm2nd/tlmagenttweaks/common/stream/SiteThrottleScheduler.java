package com.xm2nd.tlmagenttweaks.common.stream;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 纯 JDK 站点发送调度器：把「请求自身的重试等待」与「站点级冷却/发送间隔」排进同一条时间线。
 * <p>
 * 分两段，语义不同：
 * <ol>
 *   <li><b>自身等待</b>（{@code waiting}）：本次请求的重试等待期，只推迟自己，
 *       不影响同站点其他请求，也不占用站点发送额度；</li>
 *   <li><b>站点队列</b>（{@code queue}）：等待站点冷却结束，以及距该站点上一次发送的最小间隔，整站共享。</li>
 * </ol>
 * 规则：
 * <ul>
 *   <li>冷却截止时刻只能被 {@link #throttle} 向后推（{@code max} 语义），不会被提前；</li>
 *   <li>站点处于冷却时该站点尚未发出的请求一律排队，已经发出的请求不受影响、不被取消；</li>
 *   <li>同站点相邻两次发送至少间隔 {@code spacing}：截止时刻在每次放行后推进，
 *       队列清空也不清除；空闲超过该间隔后到达的请求仍可立即发送；</li>
 *   <li>请求自身的重试等待与站点时序并行满足，取两者中较晚的允许时刻；</li>
 *   <li>放行回调统一在调度线程上串行执行，因此「获准」与「真正发出」之间不存在乱序窗口；</li>
 *   <li>不同站点状态完全独立，放行不等待已放行请求结束；</li>
 *   <li>用绝对截止时刻推进调度，不使用 {@code Thread.sleep}，也不阻塞服务端主线程。</li>
 * </ul>
 * 全部状态由同一把锁保护；所有对外回调都在锁外执行，第三方回调不会阻塞调度。
 * 回调由提交方在提交时一并给出，因此不存在“回调尚未注册就被触发”的竞态；
 * 回调一律在调度线程上执行，实现方不得在其中阻塞。
 */
public final class SiteThrottleScheduler {

    /** 无站点上下文时使用的站点标识。 */
    public static final String DEFAULT_SITE = "";

    private final long spacingNanos;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private final Map<String, SiteState> sites = new HashMap<>();

    public SiteThrottleScheduler(Duration spacing) {
        this.spacingNanos = Math.max(0L, Objects.requireNonNull(spacing, "spacing").toNanos());
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tlm-request-timer");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** 尚未放行的发送请求。 */
    public interface Handle {
        /** 撤销等待；已经放行的请求不受影响。 */
        void cancel();
    }

    /** 发送请求的时序回调。三个方法各自至多触发一次。 */
    public interface Send {
        /** 已放行，可以真正发送。 */
        void onGranted();

        /** 因站点冷却或未到该站点本次允许发送的时刻而排队（不含请求自身的重试等待）。 */
        void onQueued();

        /** 尚未放行即被丢弃（服务端停止）。 */
        void onDiscarded();
    }

    /**
     * 提交一次发送请求。
     *
     * @param siteId       站点标识（TLM 的 {@code site.id()}）
     * @param waitForNanos 本次请求自身的等待时长（重试等待）；0 表示立即可发送
     * @param send         时序回调
     * @return 尚未放行时可撤销的句柄
     */
    public Handle submit(String siteId, long waitForNanos, Send send) {
        Objects.requireNonNull(send, "send");
        Request request = new Request(siteId, waitForNanos, send);
        synchronized (lock) {
            SiteState site = site(request.siteId);
            long now = System.nanoTime();
            if (request.readyAt <= now) {
                site.queue.addLast(request);
            } else {
                // List 的 addLast 是 JDK 21 的 SequencedCollection 才有的方法，
                // common 源码要同时被 Java 17 的 forge 薄壳编译，这里用 add
                site.waiting.add(request);
            }
            // 放行只在调度线程上发生：这里只登记，并让调度线程立刻按最新时序处理一次
            // （放行条件未满足时该次处理只补发排队通知并改排到真正的允许时刻）。
            schedule(site, now, now);
        }
        return request;
    }

    /**
     * 把该站点的冷却截止时刻推进到 {@code max(已有截止, 当前时刻 + cooldown)}；
     * 不会因为更短的冷却而提前，也不会取消已经发出的请求。
     */
    public void throttle(String siteId, Duration cooldown) {
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            return;
        }
        long until = System.nanoTime() + cooldown.toNanos();
        synchronized (lock) {
            SiteState site = sites.get(key(siteId));
            if (site == null) {
                // 该站点当前没有任何在途请求：冷却无需记录。
                return;
            }
            if (until > site.cooldownUntil) {
                site.cooldownUntil = until;
            }
            // 只推进冷却并让调度线程立刻复核；放行一律留给调度线程，避免调用线程抢在间隔之前发出。
            long now = System.nanoTime();
            schedule(site, now, now);
        }
    }

    /**
     * 丢弃全部尚未放行的等待项并清空站点状态（服务端停止、或同时撤销排队与延迟发送时使用）。
     * <p>
     * 已经放行的请求不受影响；本方法可重复调用，之后新的 {@link #submit} 会重新排队。
     */
    public void discardAll() {
        List<Runnable> actions;
        synchronized (lock) {
            actions = new ArrayList<>();
            for (SiteState site : sites.values()) {
                site.cancelTask();
                site.discardAll(actions);
            }
            sites.clear();
        }
        runAll(actions);
    }

    private static String key(String siteId) {
        return siteId == null ? DEFAULT_SITE : siteId;
    }

    private SiteState site(String key) {
        return sites.computeIfAbsent(key, SiteState::new);
    }

    /**
     * 调度线程上唯一的放行入口：先把自身等待结束的请求并入就绪队列，再按时序取出至多一个
     * 可放行请求。放行回调在锁外同步执行（回调返回前即完成 {@code sendAsync} 调用、不等待响应），
     * 收尾阶段才推进该站点下一次允许发送的时刻。
     */
    private void wake(SiteState site) {
        Runnable granted;
        List<Runnable> notices;
        synchronized (lock) {
            if (sites.get(site.siteId) != site) {
                return;
            }
            site.task = null;
            long now = System.nanoTime();
            promoteReady(site, now);
            granted = pollGrantable(site, now);
            if (granted == null) {
                // 放行则改为在放行收尾阶段重排，因此那里看到的是已推进的截止时间。
                schedule(site, now);
            }
            notices = queuedNotices(site);
        }
        runAll(notices);
        if (granted != null) {
            runOne(granted);
            finishGrant(site);
        }
    }

    /** 把自身等待已经结束的请求按提交顺序并入就绪队列。必须在锁内调用。 */
    private static void promoteReady(SiteState site, long now) {
        for (int i = 0; i < site.waiting.size(); ) {
            Request request = site.waiting.get(i);
            if (request.readyAt <= now) {
                site.waiting.remove(i);
                site.queue.addLast(request);
            } else {
                i++;
            }
        }
    }

    /**
     * 取出至多一个可放行请求并认领放行状态；返回锁外执行的放行回调，无可用项时返回 {@code null}。
     * 冷却与发送截止时间必须同时满足；已经终结的残留项直接跳过。必须在锁内调用。
     */
    private Runnable pollGrantable(SiteState site, long now) {
        while (!site.queue.isEmpty() && site.cooldownUntil <= now && site.nextReleaseAt <= now) {
            Request request = site.queue.pollFirst();
            Runnable action = request.grant();
            if (action != null) {
                return action;
            }
        }
        return null;
    }

    /** 就绪队列中每个尚未通知过的请求各补一次排队通知。必须在锁内调用。 */
    private static List<Runnable> queuedNotices(SiteState site) {
        List<Runnable> actions = new ArrayList<>();
        for (Request request : site.queue) {
            SiteState.addAction(actions, request.markQueued());
        }
        return actions;
    }

    /**
     * 放行收尾：以放行回调返回的时刻为起点推进该站点下一次允许发送的时间，然后重排唤醒。
     * 起点取得比真正的发送时刻更晚，因此回调执行延迟只会让间隔更保守，不会侵蚀它。
     * <p>
     * 站点状态已被 {@link #discardAll()} 清空时直接返回：旧回调不得重新建立旧站点或恢复旧任务。
     */
    private void finishGrant(SiteState site) {
        synchronized (lock) {
            if (sites.get(site.siteId) != site) {
                return;
            }
            long now = System.nanoTime();
            site.nextReleaseAt = now + spacingNanos;
            schedule(site, now);
        }
    }

    /** 按站点自身时序重排唤醒任务。必须在锁内调用。 */
    private void schedule(SiteState site, long now) {
        schedule(site, now, Long.MAX_VALUE);
    }

    /**
     * 重排该站点的唤醒任务；已有不晚于所需时刻的唤醒时不重复安排。
     * {@code noLaterThan} 用于要求调度线程立刻复核一次（提交与冷却推进），
     * 使排队通知无需等到真正允许发送的时刻才发出。必须在锁内调用。
     */
    private void schedule(SiteState site, long now, long noLaterThan) {
        long target = Math.min(site.nextWake(), noLaterThan);
        if (target == Long.MAX_VALUE) {
            site.cancelTask();
            return;
        }
        if (site.task != null && !site.task.isDone() && site.taskAt <= target) {
            return;
        }
        site.cancelTask();
        site.taskAt = target;
        // 不限制的截止时刻是 Long.MIN_VALUE，先比大小再相减，避免溢出成极大的正延迟。
        long delay = target <= now ? 0L : target - now;
        try {
            site.task = scheduler.schedule(() -> wake(site), delay, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            site.task = null;
            List<Runnable> actions = new ArrayList<>();
            site.discardAll(actions);
            runAll(actions);
        }
    }

    private static void runAll(List<Runnable> actions) {
        for (Runnable action : actions) {
            runOne(action);
        }
    }

    private static void runOne(Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // 时序回调不得影响调度线程
        }
    }

    /** 单站点状态；所有字段只在 {@link SiteThrottleScheduler#lock} 内访问。 */
    private static final class SiteState {
        private final String siteId;
        /** 自身重试等待中的请求，按提交顺序；需要按索引回退删除，因此用列表而非双端队列。 */
        private final List<Request> waiting = new ArrayList<>();
        /** 已就绪、等待站点放行的请求，FIFO。 */
        private final Deque<Request> queue = new ArrayDeque<>();
        private long cooldownUntil = Long.MIN_VALUE;
        /**
         * 该站点下一次允许发送的时刻；{@link Long#MIN_VALUE} 表示该站点尚未发送过、不限制。
         * 每次放行后向后推进，队列清空也不清除，因此队列曾经空过之后到达的请求同样受间隔约束。
         */
        private long nextReleaseAt = Long.MIN_VALUE;
        private ScheduledFuture<?> task;
        private long taskAt = Long.MIN_VALUE;

        private SiteState(String siteId) {
            this.siteId = siteId;
        }

        /** 下一次需要唤醒的时刻；无等待项时返回 {@link Long#MAX_VALUE}。 */
        private long nextWake() {
            long target = Long.MAX_VALUE;
            for (Request request : waiting) {
                target = Math.min(target, request.readyAt);
            }
            if (!queue.isEmpty()) {
                // 就绪队列要同时满足冷却与发送截止时间；队列为空时截止时刻无需唤醒。
                target = Math.min(target, Math.max(cooldownUntil, nextReleaseAt));
            }
            return target;
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel(false);
                task = null;
            }
        }

        private void discardAll(List<Runnable> actions) {
            for (Request request : waiting) {
                addAction(actions, request.discard());
            }
            waiting.clear();
            Request request;
            while ((request = queue.pollFirst()) != null) {
                addAction(actions, request.discard());
            }
            cooldownUntil = Long.MIN_VALUE;
            nextReleaseAt = Long.MIN_VALUE;
        }

        private static void addAction(List<Runnable> actions, Runnable action) {
            if (action != null) {
                actions.add(action);
            }
        }
    }

    /** 一次尚未放行的发送请求；非静态以便与调度共用同一把锁。 */
    private final class Request implements Handle {
        private final String siteId;
        private final long readyAt;
        private final Send send;
        /** {@link #STATE_PENDING} / {@link #STATE_QUEUED} / {@link #STATE_GRANTED} / {@link #STATE_DISCARDED} */
        private final AtomicInteger state = new AtomicInteger(STATE_PENDING);

        private Request(String siteId, long waitForNanos, Send send) {
            this.siteId = key(siteId);
            this.readyAt = System.nanoTime() + Math.max(0L, waitForNanos);
            this.send = send;
        }

        @Override
        public void cancel() {
            if (state.get() >= STATE_GRANTED) {
                return;
            }
            Runnable action = null;
            synchronized (lock) {
                SiteState site = sites.get(siteId);
                if (site != null
                        && (site.waiting.remove(this) || site.queue.remove(this))) {
                    action = discard();
                }
            }
            if (action != null) {
                runOne(action);
            }
        }

        /** 进入放行状态；返回锁外执行的回调，已终结时返回 null。 */
        private Runnable grant() {
            if (!state.compareAndSet(STATE_PENDING, STATE_GRANTED)
                    && !state.compareAndSet(STATE_QUEUED, STATE_GRANTED)) {
                return null;
            }
            return send::onGranted;
        }

        /** 标记为“已在站点队列中排队”；每个请求至多触发一次。 */
        private Runnable markQueued() {
            if (state.compareAndSet(STATE_PENDING, STATE_QUEUED)) {
                return send::onQueued;
            }
            return null;
        }

        /** 尚未放行即被丢弃；返回锁外执行的回调。 */
        private Runnable discard() {
            if (state.compareAndSet(STATE_PENDING, STATE_DISCARDED)
                    || state.compareAndSet(STATE_QUEUED, STATE_DISCARDED)) {
                return send::onDiscarded;
            }
            return null;
        }
    }

    private static final int STATE_PENDING = 0;
    private static final int STATE_QUEUED = 1;
    private static final int STATE_GRANTED = 2;
    private static final int STATE_DISCARDED = 3;
}

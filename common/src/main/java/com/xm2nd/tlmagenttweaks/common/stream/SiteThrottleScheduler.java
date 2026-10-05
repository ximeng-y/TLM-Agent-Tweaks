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
 * 纯 JDK 站点发送调度器：把「请求自身的重试等待」与「站点级冷却/积压」排进同一条时间线。
 * <p>
 * 分两段，语义不同：
 * <ol>
 *   <li><b>自身等待</b>（{@code waiting}）：本次请求的重试等待期，只推迟自己，
 *       不影响同站点其他请求，也不占用站点额度；</li>
 *   <li><b>站点队列</b>（{@code queue}）：等待站点冷却结束，以及积压放行之间的最小间隔，整站共享。</li>
 * </ol>
 * 规则：
 * <ul>
 *   <li>冷却截止时刻只能被 {@link #throttle} 向后推（{@code max} 语义），不会被提前；</li>
 *   <li>站点处于冷却时该站点尚未发出的请求一律排队，已经发出的请求不受影响、不被取消；</li>
 *   <li>冷却结束后按 FIFO 放行，积压期间相邻放行至少间隔 {@code spacing}；</li>
 *   <li>队列清空后恢复正常直发，不永久限制站点吞吐；</li>
 *   <li>不同站点状态完全独立，放行不等待已放行请求结束；</li>
 *   <li>用绝对截止时刻推进调度，不使用 {@code Thread.sleep}，也不阻塞服务端主线程。</li>
 * </ul>
 * 全部状态由同一把锁保护；所有对外回调都在锁外执行，第三方回调不会阻塞调度。
 */
public final class SiteThrottleScheduler implements SendGate {

    private final long spacingNanos;
    private final long retryDelayNanos;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private final Map<String, SiteState> sites = new HashMap<>();
    private boolean stopped;

    public SiteThrottleScheduler(Duration spacing, Duration retryDelay) {
        this.spacingNanos = Math.max(0L, Objects.requireNonNull(spacing, "spacing").toNanos());
        this.retryDelayNanos = Math.max(0L, Objects.requireNonNull(retryDelay, "retryDelay").toNanos());
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tlm-request-timer");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** 重试自身的固定等待时长；传输状态机与本调度器共用同一份常量。 */
    public long retryDelayNanos() {
        return retryDelayNanos;
    }

    @Override
    public Handle submit(String siteId, long waitForNanos, Send send) {
        Objects.requireNonNull(send, "send");
        Request request = new Request(siteId, waitForNanos, send);
        List<Runnable> actions;
        synchronized (lock) {
            if (stopped) {
                runOne(request.discard());
                return request;
            }
            SiteState site = site(request.siteId);
            long now = System.nanoTime();
            if (request.readyAt <= now) {
                site.queue.addLast(request);
            } else {
                site.waiting.addLast(request);
            }
            actions = drain(site, now);
            schedule(site, now);
        }
        runAll(actions);
        return request;
    }

    @Override
    public void throttle(String siteId, Duration cooldown) {
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            return;
        }
        long until = System.nanoTime() + cooldown.toNanos();
        List<Runnable> actions;
        synchronized (lock) {
            SiteState site = sites.get(key(siteId));
            if (site == null) {
                // 该站点当前没有任何在途请求：冷却无需记录。
                return;
            }
            if (until > site.cooldownUntil) {
                site.cooldownUntil = until;
            }
            long now = System.nanoTime();
            actions = drain(site, now);
            schedule(site, now);
        }
        runAll(actions);
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

    /** 当前尚未放行的等待项数量（诊断与测试用）。 */
    public int pendingCount() {
        synchronized (lock) {
            int total = 0;
            for (SiteState site : sites.values()) {
                total += site.waiting.size() + site.queue.size();
            }
            return total;
        }
    }

    private static String key(String siteId) {
        return siteId == null ? DEFAULT_SITE : siteId;
    }

    private SiteState site(String key) {
        return sites.computeIfAbsent(key, SiteState::new);
    }

    /**
     * 推进时间线：先把自身等待结束的请求并入站点队列，再按冷却与放行间隔依次放行。
     * 必须在锁内调用，返回的回调在锁外执行。
     */
    private List<Runnable> drain(SiteState site, long now) {
        List<Runnable> actions = new ArrayList<>();
        for (int i = site.waiting.size() - 1; i >= 0; i--) {
            Request request = site.waiting.get(i);
            if (request.readyAt <= now) {
                site.waiting.remove(i);
                site.queue.addLast(request);
            }
        }
        long moment = now;
        while (!site.queue.isEmpty() && site.cooldownUntil <= moment && site.nextReleaseAt <= moment) {
            Request request = site.queue.pollFirst();
            Runnable action = request.grant();
            if (action != null) {
                actions.add(action);
            }
            site.nextReleaseAt = site.queue.isEmpty() ? Long.MIN_VALUE : moment + spacingNanos;
            moment = System.nanoTime();
        }
        if (site.queue.isEmpty()) {
            // 队列排空后恢复正常直发，不留下永久节流。
            site.nextReleaseAt = Long.MIN_VALUE;
        }
        for (Request request : site.queue) {
            Runnable action = request.markQueued();
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    /** 重排该站点的唤醒任务；已有不晚于所需时刻的唤醒时不重复安排。必须在锁内调用。 */
    private void schedule(SiteState site, long now) {
        long target = site.nextWake();
        if (target == Long.MAX_VALUE) {
            site.cancelTask();
            return;
        }
        if (site.task != null && !site.task.isDone() && site.taskAt <= target) {
            return;
        }
        site.cancelTask();
        site.taskAt = target;
        try {
            site.task = scheduler.schedule(() -> wake(site),
                    Math.max(0L, target - now), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            site.task = null;
            List<Runnable> actions = new ArrayList<>();
            site.discardAll(actions);
            runAll(actions);
        }
    }

    private void wake(SiteState site) {
        List<Runnable> actions;
        synchronized (lock) {
            if (stopped || sites.get(site.siteId) != site) {
                return;
            }
            site.task = null;
            long now = System.nanoTime();
            actions = drain(site, now);
            schedule(site, now);
        }
        runAll(actions);
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
        /** 积压期间下一次允许放行的时刻；无积压时为 {@link Long#MIN_VALUE}（不限制）。 */
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
                        && (site.waiting.remove(this) || site.queue.remove(this))
                        && state.compareAndSet(STATE_PENDING, STATE_DISCARDED)) {
                    action = send::onDiscarded;
                }
            }
            if (action != null) {
                runOne(action);
            }
        }

        /** 进入放行状态；返回锁外执行的回调，已终结时返回 null。 */
        private Runnable grant() {
            if (!state.compareAndSet(STATE_PENDING, STATE_GRANTED)) {
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

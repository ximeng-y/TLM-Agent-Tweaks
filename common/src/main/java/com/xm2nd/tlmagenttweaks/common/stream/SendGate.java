package com.xm2nd.tlmagenttweaks.common.stream;

import java.time.Duration;

/**
 * 一次模型请求在真正发出之前必须通过的发送闸门。
 * <p>
 * 闸门只负责时序：请求自身的重试等待、站点级冷却、同站点相邻发送的最小间隔，不涉及任何 HTTP 语义。
 * 回调由提交方在提交时一并给出，因此不存在“回调尚未注册就被触发”的竞态；
 * 回调一律在调度线程上执行，实现方不得在其中阻塞。
 */
public interface SendGate {

    /** 无站点上下文时使用的站点标识。 */
    String DEFAULT_SITE = "";

    /**
     * 提交一次发送请求。
     *
     * @param siteId       站点标识（TLM 的 {@code site.id()}）
     * @param waitForNanos 本次请求自身的等待时长（重试等待）；0 表示立即可发送
     * @param send         时序回调
     * @return 尚未放行时可撤销的句柄
     */
    Handle submit(String siteId, long waitForNanos, Send send);

    /**
     * 把该站点的冷却截止时刻推进到 {@code max(已有截止, 当前时刻 + cooldown)}；
     * 不会因为更短的冷却而提前，也不会取消已经发出的请求。
     */
    void throttle(String siteId, Duration cooldown);

    /** 尚未放行的发送请求。 */
    interface Handle {
        /** 撤销等待；已经放行的请求不受影响。 */
        void cancel();
    }

    /** 发送请求的时序回调。三个方法各自至多触发一次。 */
    interface Send {
        /** 已放行，可以真正发送。 */
        void onGranted();

        /** 因站点冷却或未到该站点本次允许发送的时刻而排队（不含请求自身的重试等待）。 */
        void onQueued();

        /** 尚未放行即被丢弃（服务端停止）。 */
        void onDiscarded();
    }
}

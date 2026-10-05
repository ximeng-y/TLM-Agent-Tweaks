package com.xm2nd.tlmagenttweaks.common.stream;

import java.time.Duration;

/**
 * 一次模型 HTTP 请求的重试与调度策略快照。
 * <p>
 * 快照在请求进入协议出口时取一次并全程复用：配置在请求进行中被改动不会影响本轮，
 * 也不会出现同一轮里部分尝试按新配置、部分按旧配置的情况。
 *
 * @param enabled     是否启用状态码重试与站点冷却（关闭时保留 #8 的断流回退行为）
 * @param maxRetries  状态码重试的最大次数（不含首次发送）
 * @param retryDelay  每次状态码重试前的固定等待（忽略 {@code Retry-After}，不做指数退避）
 * @param siteSpacing 同一站点积压放行的最小间隔
 */
public record RequestRetryPolicy(boolean enabled, int maxRetries, Duration retryDelay, Duration siteSpacing) {

    /** 不启用重试与站点冷却（{@code retry.enabled=false}）。 */
    public static final RequestRetryPolicy DISABLED =
            new RequestRetryPolicy(false, 0, Duration.ZERO, Duration.ZERO);

    public RequestRetryPolicy {
        maxRetries = Math.max(0, maxRetries);
        retryDelay = retryDelay == null ? Duration.ZERO : retryDelay;
        siteSpacing = siteSpacing == null ? Duration.ZERO : siteSpacing;
        if (!enabled) {
            maxRetries = 0;
        }
    }

    public long retryDelayNanos() {
        return retryDelay.toNanos();
    }

    public long siteSpacingNanos() {
        return siteSpacing.toNanos();
    }
}

package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.xm2nd.tlmagenttweaks.Config;
import com.xm2nd.tlmagenttweaks.common.stream.RequestRetryPolicy;

import java.time.Duration;

/**
 * 请求重试的固定规则与服务端权威开关。
 * <p>
 * 次数与等待时长本轮不开放配置，只提供 {@code retry.enabled} 总开关；
 * 站点积压放行间隔与单次重试等待取同一常量：一次重试正好对应一个站点额度周期，
 * 避免出现「重试比站点恢复还快」的无效请求。
 * <p>
 * 状态码重试与响应头前断连的流式重发共用这份等待与放行间隔，但各自独立计数：
 * 传输重试不占用 {@link #MAX_RETRIES} 额度。
 */
public final class RetrySettings {

    /** 单次请求允许的最大额外重试次数（不含首次发送）。 */
    public static final int MAX_RETRIES = 2;

    /** 每次重试前的固定等待，忽略服务端返回的 {@code Retry-After}。 */
    public static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    /** 同一站点积压放行的最小间隔。 */
    public static final Duration SITE_SPACING = Duration.ofSeconds(5);

    private static final RequestRetryPolicy ENABLED =
            new RequestRetryPolicy(true, MAX_RETRIES, RETRY_DELAY, SITE_SPACING);

    private RetrySettings() {
    }

    /**
     * 是否启用状态码重试与站点冷却。
     * <p>
     * 配置尚未加载时按关闭处理：该情形只可能出现在服务端正常运行之外，
     * 此时不应重试（重试会放大一个已经异常的调用路径）。
     */
    public static boolean enabled() {
        try {
            return Config.RETRY_ENABLED.get();
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 本次请求使用的策略快照：开关关闭时返回 {@link RequestRetryPolicy#DISABLED}。 */
    public static RequestRetryPolicy policy() {
        return enabled() ? ENABLED : RequestRetryPolicy.DISABLED;
    }
}

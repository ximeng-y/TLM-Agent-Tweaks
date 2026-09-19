package com.xm2nd.tlmagenttweaks.common;

/**
 * 某种货币下一个模型的四类单价（每百万 token 的价格）。
 * 四字段与 {@link TokenUsageRecord} 的四类 token 一一对应。
 */
public class Price4 {
    public double inputPerMillion;
    public double outputPerMillion;
    public double cacheReadPerMillion;
    public double cacheWritePerMillion;

    public Price4() {
        // Gson 反序列化用
    }

    public Price4(double input, double output, double cacheRead, double cacheWrite) {
        this.inputPerMillion = input;
        this.outputPerMillion = output;
        this.cacheReadPerMillion = cacheRead;
        this.cacheWritePerMillion = cacheWrite;
    }

    /**
     * 按四类 token 数量计算费用。
     */
    public double cost(long input, long output, long cacheRead, long cacheWrite) {
        return input / 1_000_000.0 * inputPerMillion
                + output / 1_000_000.0 * outputPerMillion
                + cacheRead / 1_000_000.0 * cacheReadPerMillion
                + cacheWrite / 1_000_000.0 * cacheWritePerMillion;
    }
}

package com.xm2nd.tlmagenttweaks.client.gui;

import java.util.Locale;

/**
 * 数值格式化（客户端展示用）。
 */
public final class NumUtil {

    private NumUtil() {
    }

    /** token 数量：1234 → 1.2k，1234567 → 1.2M */
    public static String formatTokens(long value) {
        if (value >= 1_000_000_000L) {
            return trim(value / 1_000_000_000.0) + "B";
        }
        if (value >= 1_000_000L) {
            return trim(value / 1_000_000.0) + "M";
        }
        if (value >= 10_000L) {
            return trim(value / 1_000.0) + "k";
        }
        return String.valueOf(value);
    }

    /** 成本：保留两位小数 */
    public static String formatCost(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 百分比：0.456 → 45.6% */
    public static String formatPercent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }

    private static String trim(double value) {
        if (value >= 100) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}

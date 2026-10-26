package com.xm2nd.tlmagenttweaks.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 时间粒度工具：粒度=时间窗口，窗口内按桶出数据点。
 * <ul>
 *   <li>窗口 ≤ 90 天：按天分桶（1day=今天 1 根，30day=近 30 天每天一根）</li>
 *   <li>窗口 180/365 天：按周分桶（避免柱子过多）</li>
 *   <li>all：按月分桶，窗口自动收缩到数据最早的一天</li>
 * </ul>
 */
public final class GranularityUtil {

    /** 支持的窗口天数；0 表示 all */
    public static final int[] WINDOWS = {1, 3, 7, 14, 30, 60, 90, 180, 365, 0};

    private GranularityUtil() {
    }

    /** windowDays 是否为支持的统计窗口（{@link #WINDOWS} 白名单之一）；服务端以此拒绝任意伪造值 */
    public static boolean isSupportedWindow(int windowDays) {
        for (int supported : WINDOWS) {
            if (supported == windowDays) {
                return true;
            }
        }
        return false;
    }

    public enum BucketUnit {
        DAY, WEEK, MONTH
    }

    /** 一个数据桶 [startDay, endDay]（含两端，epochDay） */
    public record Bucket(int startDay, int endDay, BucketUnit unit) {
        public boolean contains(int day) {
            return day >= startDay && day <= endDay;
        }
    }

    /**
     * 计算窗口的桶序列。
     *
     * @param windowDays 窗口天数；0 表示 all（窗口从 minDay 到 today）
     * @param todayEpochDay 今天的 epochDay
     * @param minDay 数据中最早出现的 epochDay（all 窗口收缩用；无数据时可传 todayEpochDay）
     */
    public static List<Bucket> buckets(int windowDays, int todayEpochDay, int minDay) {
        int endDay = todayEpochDay;
        int startDay;
        BucketUnit unit;
        if (windowDays == 0) {
            startDay = Math.min(minDay, todayEpochDay);
            unit = BucketUnit.MONTH;
        } else {
            startDay = Math.max(todayEpochDay - windowDays + 1, 0);
            unit = windowDays <= 90 ? BucketUnit.DAY : BucketUnit.WEEK;
        }

        List<Bucket> buckets = new ArrayList<>();
        if (unit == BucketUnit.DAY) {
            for (int day = startDay; day <= endDay; day++) {
                buckets.add(new Bucket(day, day, unit));
            }
        } else if (unit == BucketUnit.WEEK) {
            // 以 startDay 为起点向后排桶，最后一桶截至 endDay
            int cursor = startDay;
            while (cursor <= endDay) {
                int bucketEnd = Math.min(cursor + 6, endDay);
                buckets.add(new Bucket(cursor, bucketEnd, unit));
                cursor = bucketEnd + 1;
            }
        } else {
            // 按自然月分桶
            LocalDate cursor = LocalDate.ofEpochDay(startDay);
            LocalDate endDate = LocalDate.ofEpochDay(endDay);
            while (!cursor.isAfter(endDate)) {
                LocalDate monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth());
                int bucketEnd = (int) LocalDate.ofEpochDay(cursor.toEpochDay()).plusDays(
                        monthEnd.toEpochDay() - cursor.toEpochDay()).toEpochDay();
                bucketEnd = Math.min(bucketEnd, endDay);
                buckets.add(new Bucket((int) cursor.toEpochDay(), bucketEnd, unit));
                cursor = monthEnd.plusDays(1);
            }
        }
        return buckets;
    }
}

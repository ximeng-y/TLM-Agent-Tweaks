package com.xm2nd.tlmagenttweaks.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GranularityUtilTest {

    @Test
    void shortWindowIsDailyBuckets() {
        int today = 20000;
        List<GranularityUtil.Bucket> buckets = GranularityUtil.buckets(7, today, today);
        assertEquals(7, buckets.size());
        assertEquals(today - 6, buckets.get(0).startDay());
        assertEquals(today, buckets.get(6).endDay());
        assertEquals(GranularityUtil.BucketUnit.DAY, buckets.get(0).unit());
    }

    @Test
    void oneDayWindowIsSingleBucket() {
        int today = 20000;
        List<GranularityUtil.Bucket> buckets = GranularityUtil.buckets(1, today, today);
        assertEquals(1, buckets.size());
        assertEquals(today, buckets.get(0).startDay());
        assertEquals(today, buckets.get(0).endDay());
    }

    @Test
    void midWindowUsesWeeks() {
        int today = 20000;
        List<GranularityUtil.Bucket> buckets = GranularityUtil.buckets(180, today, today);
        assertEquals(GranularityUtil.BucketUnit.WEEK, buckets.get(0).unit());
        // 每桶至多 7 天，桶完整覆盖窗口且无缝隙
        int covered = 0;
        for (GranularityUtil.Bucket bucket : buckets) {
            covered += bucket.endDay() - bucket.startDay() + 1;
            org.junit.jupiter.api.Assertions.assertTrue(bucket.endDay() - bucket.startDay() + 1 <= 7);
        }
        assertEquals(180, covered);
    }

    @Test
    void allWindowUsesMonthsAndClampsToMinDay() {
        int today = (int) LocalDate.of(2026, 8, 23).toEpochDay();
        int minDay = (int) LocalDate.of(2026, 3, 15).toEpochDay();
        List<GranularityUtil.Bucket> buckets = GranularityUtil.buckets(0, today, minDay);
        assertEquals(GranularityUtil.BucketUnit.MONTH, buckets.get(0).unit());
        assertEquals(minDay, buckets.get(0).startDay());
        assertEquals(today, buckets.get(buckets.size() - 1).endDay());
        // 桶之间无缝无重叠
        for (int i = 1; i < buckets.size(); i++) {
            assertEquals(buckets.get(i - 1).endDay() + 1, buckets.get(i).startDay());
        }
    }
}

package com.xm2nd.tlmagenttweaks.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenStatsStoreTest {

    @TempDir
    Path tempDir;

    private static long msOf(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void rankingAggregatesPerPlayerAndSortsDesc() {
        TokenStatsStore store = new TokenStatsStore();
        LocalDate today = LocalDate.now();
        store.record("u1", "Alice", "deepseek", "chat", 100, 50, 30, 0, msOf(today, 10));
        store.record("u1", "Alice", "deepseek", "chat", 10, 5, 0, 0, msOf(today, 11));
        store.record("u2", "Bob", "deepseek", "chat", 40, 20, 0, 0, msOf(today, 12));

        List<TokenStatsStore.PlayerSummary> ranking = store.playerRanking();
        assertEquals(2, ranking.size());
        // u1: 110 + 55 + 30 = 195；u2: 60
        assertEquals("u1", ranking.get(0).uuid());
        assertEquals("Alice", ranking.get(0).name());
        assertEquals(110, ranking.get(0).input());
        assertEquals(55, ranking.get(0).output());
        assertEquals(30, ranking.get(0).cacheRead());
        assertEquals(195, ranking.get(0).total());
        assertEquals(2, ranking.get(0).requests());
        assertEquals("u2", ranking.get(1).uuid());
    }

    @Test
    void cacheHitRateIsCacheReadOverPrompt() {
        TokenStatsStore store = new TokenStatsStore();
        LocalDate today = LocalDate.now();
        store.record("u1", "Alice", "s", "m", 100, 0, 100, 0, msOf(today, 10));
        // prompt = 100 + 100 = 200，命中 100 → 50%
        assertEquals(0.5, store.playerRanking().get(0).cacheHitRate(), 1e-9);
    }

    @Test
    void playerDetailCostsUseCurrentPricesPerModel() {
        TokenStatsStore store = new TokenStatsStore();
        LocalDate today = LocalDate.now();
        store.record("u1", "Alice", "deepseek", "chat", 1_000_000, 1_000_000, 0, 0, msOf(today, 10));
        store.record("u1", "Alice", "openai", "gpt", 2_000_000, 0, 0, 0, msOf(today, 11));

        PriceTable prices = new PriceTable();
        prices.setPrice("deepseek", "chat", PriceTable.Currency.CNY, new Price4(1, 2, 0.1, 0));
        prices.setPrice("openai", "gpt", PriceTable.Currency.USD, new Price4(10, 20, 0, 0));

        TokenStatsStore.PlayerDetail detail = store.playerDetail("u1", prices);
        // CNY：deepseek 输入 1M×1 + 输出 1M×2 = 3；openai 无 CNY 价格不计
        assertEquals(3.0, detail.cost().cny(), 1e-9);
        assertTrue(detail.cost().anyCnyPrice());
        // USD：openai 2M×10 = 20；deepseek 无 USD 价格不计
        assertEquals(20.0, detail.cost().usd(), 1e-9);
        assertTrue(detail.cost().anyUsdPrice());
    }

    @Test
    void noPricesMeansNoCostFlags() {
        TokenStatsStore store = new TokenStatsStore();
        store.record("u1", "Alice", "s", "m", 100, 100, 0, 0, msOf(LocalDate.now(), 10));
        TokenStatsStore.PlayerDetail detail = store.playerDetail("u1", new PriceTable());
        assertFalse(detail.cost().anyCnyPrice());
        assertFalse(detail.cost().anyUsdPrice());
    }

    @Test
    void serverBucketsAggregateDaysAndWeeks() {
        TokenStatsStore store = new TokenStatsStore();
        LocalDate today = LocalDate.now();
        int todayDay = (int) today.toEpochDay();
        store.record("u1", "A", "s", "m", 10, 5, 0, 0, msOf(today, 10));
        store.record("u2", "B", "s", "m", 7, 3, 0, 0, msOf(today.minusDays(1), 10));

        List<GranularityUtil.Bucket> dayBuckets = GranularityUtil.buckets(3, todayDay, todayDay);
        assertEquals(3, dayBuckets.size());
        List<TokenStatsStore.BucketTotals> totals = store.serverBuckets(dayBuckets, new PriceTable());
        assertEquals(3, totals.size());
        // 最后一桶是今天
        TokenStatsStore.BucketTotals todayBucket = totals.get(2);
        assertEquals(10, todayBucket.input());
        assertEquals(5, todayBucket.output());
        // 昨天的桶
        TokenStatsStore.BucketTotals yesterdayBucket = totals.get(1);
        assertEquals(7, yesterdayBucket.input());
    }

    @Test
    void persistenceRoundtripAcrossMonths() throws Exception {
        TokenStatsStore store = new TokenStatsStore();
        LocalDate today = LocalDate.now();
        LocalDate lastMonth = today.minusDays(today.getDayOfMonth() + 2); // 保证落在上个月
        store.record("u1", "Alice", "s", "m", 10, 5, 2, 1, msOf(today, 10));
        store.record("u2", "Bob", "s", "m", 20, 6, 0, 0, msOf(lastMonth, 10));
        store.flush(tempDir);

        // 新实例加载后数据一致
        TokenStatsStore reloaded = new TokenStatsStore();
        reloaded.load(tempDir);
        List<TokenStatsStore.PlayerSummary> ranking = reloaded.playerRanking();
        assertEquals(2, ranking.size());
        TokenStatsStore.PlayerSummary alice = ranking.stream().filter(p -> p.uuid().equals("u1")).findFirst().orElseThrow();
        assertEquals(10, alice.input());
        assertEquals(1, alice.cacheWrite());
        TokenStatsStore.PlayerSummary bob = ranking.stream().filter(p -> p.uuid().equals("u2")).findFirst().orElseThrow();
        assertEquals(20, bob.input());

        // 加载后无脏数据，flush 是空操作且不报错
        reloaded.flush(tempDir);
    }

    @Test
    void knownPlayersKeepsLatestName() {
        TokenStatsStore store = new TokenStatsStore();
        LocalDate today = LocalDate.now();
        store.record("u1", "OldName", "s", "m", 1, 1, 0, 0, msOf(today, 10));
        store.record("u1", "NewName", "s", "m", 1, 1, 0, 0, msOf(today, 11));
        Map<String, String> players = store.knownPlayers();
        assertEquals("NewName", players.get("u1"));
    }

    @Test
    void minDayFallsBackToTodayWhenEmpty() {
        TokenStatsStore store = new TokenStatsStore();
        assertEquals(20000, store.minDay(20000));
    }

    @Test
    void loadSkipsCorruptedMonthFileAndKeepsOthers() throws Exception {
        TokenStatsStore store = new TokenStatsStore();
        LocalDate today = LocalDate.now();
        store.record("u1", "Alice", "s", "m", 10, 5, 0, 0, msOf(today, 10));
        store.flush(tempDir);
        // 混入一个损坏的月度文件
        Files.writeString(tempDir.resolve("stats-1999-01.json"), "{ not valid json", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("stats-1999-02.json"), "", StandardCharsets.UTF_8);

        TokenStatsStore reloaded = new TokenStatsStore();
        reloaded.load(tempDir); // 不应抛异常
        List<TokenStatsStore.PlayerSummary> ranking = reloaded.playerRanking();
        assertEquals(1, ranking.size());
        assertEquals(10, ranking.get(0).input());
    }

    @Test
    void rankingFallsBackToUuidWhenNameMissing() throws Exception {
        LocalDate today = LocalDate.now();
        String json = ("[{\"day\":%d,\"ownerUuid\":\"u1\",\"ownerName\":null,\"siteId\":\"s\",\"model\":\"m\","
                + "\"inputTokens\":10,\"outputTokens\":5,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"requestCount\":1}]")
                .formatted((int) today.toEpochDay());
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("stats-%04d-%02d.json".formatted(today.getYear(), today.getMonthValue())),
                json, StandardCharsets.UTF_8);

        TokenStatsStore store = new TokenStatsStore();
        store.load(tempDir);
        List<TokenStatsStore.PlayerSummary> ranking = store.playerRanking();
        assertEquals(1, ranking.size());
        assertEquals("u1", ranking.get(0).name());
    }
}

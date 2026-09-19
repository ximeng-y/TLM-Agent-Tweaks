package com.xm2nd.tlmagenttweaks.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceTableTest {

    @TempDir
    Path tempDir;

    @Test
    void dualCurrencyIsIndependent() {
        PriceTable table = new PriceTable();
        table.setPrice("deepseek", "chat", PriceTable.Currency.CNY, new Price4(1, 2, 0.1, 0));
        // 只配 CNY，USD 查不到
        assertTrue(table.getPrice("deepseek", "chat", PriceTable.Currency.CNY).isPresent());
        assertTrue(table.getPrice("deepseek", "chat", PriceTable.Currency.USD).isEmpty());
        assertFalse(table.hasAnyPrice(PriceTable.Currency.USD));

        table.setPrice("deepseek", "chat", PriceTable.Currency.USD, new Price4(0.14, 0.28, 0.014, 0));
        assertTrue(table.hasAnyPrice(PriceTable.Currency.USD));
    }

    @Test
    void costMathIsPerMillionTokens() {
        Price4 price = new Price4(2, 8, 0.2, 4);
        // 1M 输入×2 + 0.5M 输出×8×0.5... 直接算：0.5M 输出 = 4
        assertEquals(2 + 4, price.cost(1_000_000, 500_000, 0, 0), 1e-9);
        // 3M 缓存命中 ×0.2 = 0.6；1M 缓存写入 ×4 = 4
        assertEquals(4.6, price.cost(0, 0, 3_000_000, 1_000_000), 1e-9);
    }

    @Test
    void saveLoadRoundtrip() throws Exception {
        PriceTable table = new PriceTable();
        table.setPrice("deepseek", "chat", PriceTable.Currency.CNY, new Price4(1, 2, 0.1, 0));
        table.setPrice("deepseek", "chat", PriceTable.Currency.USD, new Price4(0.14, 0.28, 0.014, 0));
        table.setPrice("openai", "gpt-4o", PriceTable.Currency.USD, new Price4(2.5, 10, 1.25, 0));
        Path file = tempDir.resolve("prices.json");
        table.save(file);

        PriceTable loaded = new PriceTable();
        loaded.load(file);
        Optional<Price4> cny = loaded.getPrice("deepseek", "chat", PriceTable.Currency.CNY);
        assertTrue(cny.isPresent());
        assertEquals(0.1, cny.get().cacheReadPerMillion, 1e-9);
        Optional<Price4> usd = loaded.getPrice("openai", "gpt-4o", PriceTable.Currency.USD);
        assertTrue(usd.isPresent());
        assertEquals(2.5, usd.get().inputPerMillion, 1e-9);
        assertTrue(loaded.getPrice("openai", "gpt-4o", PriceTable.Currency.CNY).isEmpty());
    }

    @Test
    void snapshotRoundtripForNetwork() {
        PriceTable table = new PriceTable();
        table.setPrice("deepseek", "chat", PriceTable.Currency.CNY, new Price4(1, 2, 0.1, 0));
        table.setPrice("deepseek", "r1", PriceTable.Currency.USD, new Price4(0.5, 2, 0.1, 0));

        Map<String, Map<String, Map<String, double[]>>> snapshot = table.snapshot();
        PriceTable restored = new PriceTable();
        restored.loadSnapshot(snapshot);

        Price4 expectedCny = table.getPrice("deepseek", "chat", PriceTable.Currency.CNY).orElseThrow();
        Price4 actualCny = restored.getPrice("deepseek", "chat", PriceTable.Currency.CNY).orElseThrow();
        assertEquals(expectedCny.inputPerMillion, actualCny.inputPerMillion, 1e-9);
        assertEquals(expectedCny.outputPerMillion, actualCny.outputPerMillion, 1e-9);
        assertEquals(expectedCny.cacheReadPerMillion, actualCny.cacheReadPerMillion, 1e-9);
        assertEquals(expectedCny.cacheWritePerMillion, actualCny.cacheWritePerMillion, 1e-9);

        Price4 expectedUsd = table.getPrice("deepseek", "r1", PriceTable.Currency.USD).orElseThrow();
        Price4 actualUsd = restored.getPrice("deepseek", "r1", PriceTable.Currency.USD).orElseThrow();
        assertEquals(expectedUsd.inputPerMillion, actualUsd.inputPerMillion, 1e-9);
        assertTrue(restored.getPrice("deepseek", "r1", PriceTable.Currency.CNY).isEmpty());
    }

    @Test
    void setNullPriceClearsCurrency() {
        PriceTable table = new PriceTable();
        table.setPrice("s", "m", PriceTable.Currency.CNY, new Price4(1, 1, 1, 1));
        table.setPrice("s", "m", PriceTable.Currency.CNY, null);
        assertTrue(table.getPrice("s", "m", PriceTable.Currency.CNY).isEmpty());
    }

    @Test
    void loadMissingFileKeepsEmptyTable() {
        PriceTable table = new PriceTable();
        table.load(tempDir.resolve("nonexistent.json"));
        assertFalse(table.hasAnyPrice(PriceTable.Currency.CNY));
    }
}

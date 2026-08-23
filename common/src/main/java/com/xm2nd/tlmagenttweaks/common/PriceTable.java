package com.xm2nd.tlmagenttweaks.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 模型价格表：siteId → model → 双币种价格（CNY/USD 各自独立，不按汇率换算）。
 * <p>
 * 持久化为 JSON 文件（由壳层提供路径，通常为全局 config 目录，不随世界存档）。
 * 成本按查询时现价计算：修改价格会追溯影响历史成本显示。
 */
public class PriceTable {
    /** siteId → model → 币种 → 价格 */
    private final Map<String, Map<String, CurrencyPrices>> table = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TABLE_TYPE = new TypeToken<Map<String, Map<String, CurrencyPrices>>>() {
    }.getType();

    /** 单模型的双币种价格，任一币种可为 null（表示未配置该币种） */
    public static class CurrencyPrices {
        public Price4 cny;
        public Price4 usd;
    }

    /** 查询某模型某币种的价格 */
    public Optional<Price4> getPrice(String siteId, String model, Currency currency) {
        CurrencyPrices prices = table.getOrDefault(siteId, Map.of()).get(model);
        if (prices == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(currency == Currency.CNY ? prices.cny : prices.usd);
    }

    /** 设置某模型某币种的价格；null 表示清除该币种配置 */
    public synchronized void setPrice(String siteId, String model, Currency currency, Price4 price) {
        CurrencyPrices prices = table.computeIfAbsent(siteId, k -> new LinkedHashMap<>())
                .computeIfAbsent(model, k -> new CurrencyPrices());
        if (currency == Currency.CNY) {
            prices.cny = price;
        } else {
            prices.usd = price;
        }
    }

    /** 是否有任一模型配置了指定币种的价格（用于 UI 决定是否显示该币种成本线） */
    public boolean hasAnyPrice(Currency currency) {
        for (Map<String, CurrencyPrices> byModel : table.values()) {
            for (CurrencyPrices prices : byModel.values()) {
                if (currency == Currency.CNY ? prices.cny != null : prices.usd != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 导出整表快照（网络下发 / 调试用）：{site → model → 币种 → 4 元素数组} */
    public synchronized Map<String, Map<String, Map<String, double[]>>> snapshot() {
        Map<String, Map<String, Map<String, double[]>>> out = new LinkedHashMap<>();
        table.forEach((siteId, byModel) -> {
            Map<String, Map<String, double[]>> modelOut = new LinkedHashMap<>();
            byModel.forEach((model, prices) -> {
                Map<String, double[]> currencyOut = new LinkedHashMap<>();
                if (prices.cny != null) {
                    currencyOut.put("cny", toDoubleArray(prices.cny));
                }
                if (prices.usd != null) {
                    currencyOut.put("usd", toDoubleArray(prices.usd));
                }
                modelOut.put(model, currencyOut);
            });
            out.put(siteId, modelOut);
        });
        return out;
    }

    /** 从快照恢复整表（与 {@link #snapshot()} 对应） */
    public synchronized void loadSnapshot(Map<String, Map<String, Map<String, double[]>>> snapshot) {
        table.clear();
        snapshot.forEach((siteId, byModel) -> byModel.forEach((model, byCurrency) -> {
            double[] cny = byCurrency.get("cny");
            if (cny != null && cny.length == 4) {
                setPrice(siteId, model, Currency.CNY, new Price4(cny[0], cny[1], cny[2], cny[3]));
            }
            double[] usd = byCurrency.get("usd");
            if (usd != null && usd.length == 4) {
                setPrice(siteId, model, Currency.USD, new Price4(usd[0], usd[1], usd[2], usd[3]));
            }
        }));
    }

    public synchronized void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(table, TABLE_TYPE), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized void load(Path file) {
        table.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, Map<String, CurrencyPrices>> loaded =
                    GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TABLE_TYPE);
            if (loaded != null) {
                table.putAll(loaded);
            }
        } catch (Exception e) {
            throw new IllegalStateException("价格表文件损坏或不可读: " + file, e);
        }
    }

    private static double[] toDoubleArray(Price4 price) {
        return new double[]{price.inputPerMillion, price.outputPerMillion,
                price.cacheReadPerMillion, price.cacheWritePerMillion};
    }

    public enum Currency {
        CNY, USD
    }
}

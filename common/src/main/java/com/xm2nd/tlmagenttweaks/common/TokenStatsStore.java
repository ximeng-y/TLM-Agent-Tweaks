package com.xm2nd.tlmagenttweaks.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * token 用量统计引擎（线程安全）。
 * <p>
 * 记录来自 LLM 回调线程（{@link #record}），查询与落盘在服务端主线程。
 * 聚合粒度：(天, 玩家, 站点, 模型)。持久化为月度 JSON 文件 stats-yyyy-MM.json。
 */
public class TokenStatsStore {
    /** (day, ownerUuid, siteId, model) → 聚合行 */
    private final ConcurrentHashMap<RowKey, TokenUsageRecord> rows = new ConcurrentHashMap<>();
    /** 有未落盘数据的月份（monthKey = year * 12 + month） */
    private final Set<Integer> dirtyMonths = ConcurrentHashMap.newKeySet();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ROW_LIST_TYPE = new TypeToken<List<TokenUsageRecord>>() {
    }.getType();
    private static final Pattern FILE_PATTERN = Pattern.compile("stats-(\\d{4})-(\\d{2})\\.json");
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenStatsStore.class);

    private record RowKey(int day, String ownerUuid, String siteId, String model) {
    }

    // ---------- 查询结果模型 ----------

    /** 玩家汇总（四类 token 总和） */
    public record PlayerSummary(String uuid, String name, long input, long output,
                                long cacheRead, long cacheWrite, long requests) {
        public long total() {
            return input + output + cacheRead + cacheWrite;
        }

        /** 缓存命中率 = 缓存命中 / prompt（prompt = 输入 + 缓存命中） */
        public double cacheHitRate() {
            long prompt = input + cacheRead;
            return prompt == 0 ? 0 : (double) cacheRead / prompt;
        }
    }

    /** 双币种成本；anyXxxPrice=false 表示没有任何模型配置过该币种价格，成本值无意义 */
    public record CostResult(double cny, double usd, boolean anyCnyPrice, boolean anyUsdPrice) {
    }

    /** 玩家明细 = 汇总 + 成本 */
    public record PlayerDetail(PlayerSummary summary, CostResult cost) {
    }

    /** 一个时间桶的服务器总消耗 */
    public record BucketTotals(int startDay, int endDay, GranularityUtil.BucketUnit unit,
                               long input, long output, long cacheRead, long cacheWrite,
                               double costCny, double costUsd, boolean anyCnyPrice, boolean anyUsdPrice) {
        public long total() {
            return input + output + cacheRead + cacheWrite;
        }
    }

    // ---------- 记录 ----------

    /**
     * 记录一次 LLM 请求的 token 用量（线程安全，可在回调线程直接调用）。
     *
     * @param epochMs 请求完成时刻（毫秒）
     */
    public void record(String ownerUuid, String ownerName, String siteId, String model,
                       long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                       long epochMs) {
        int day = (int) LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).toEpochDay();
        RowKey key = new RowKey(day, ownerUuid, siteId, model);
        rows.compute(key, (k, row) -> {
            TokenUsageRecord r = row != null ? row : new TokenUsageRecord(day, ownerUuid, ownerName, siteId, model);
            // 玩家名以最近一次非空为准（处理改名）
            if (ownerName != null && !ownerName.isBlank()) {
                r.ownerName = ownerName;
            }
            r.add(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens);
            return r;
        });
        dirtyMonths.add(monthKey(day));
    }

    // ---------- 查询 ----------

    /** 玩家排行（按总 token 降序） */
    public List<PlayerSummary> playerRanking() {
        Map<String, Agg> byPlayer = new HashMap<>();
        for (TokenUsageRecord row : rows.values()) {
            Agg agg = byPlayer.computeIfAbsent(row.ownerUuid, k -> new Agg(row.ownerUuid, row.ownerName));
            agg.name = row.ownerName != null && !row.ownerName.isBlank() ? row.ownerName : agg.name;
            agg.add(row);
        }
        List<PlayerSummary> list = new ArrayList<>();
        for (Agg agg : byPlayer.values()) {
            // 显示名缺失（如磁盘数据 ownerName 为 null）时回退为 uuid，避免网络编码 NPE
            if (agg.name == null) {
                agg.name = agg.uuid;
            }
            list.add(agg.toSummary());
        }
        list.sort(Comparator.comparingLong(PlayerSummary::total).reversed());
        return list;
    }

    /** 单玩家明细（含按查询时现价计算的成本） */
    public PlayerDetail playerDetail(String ownerUuid, PriceTable prices) {
        Agg agg = new Agg(ownerUuid, ownerUuid);
        List<TokenUsageRecord> playerRows = new ArrayList<>();
        for (TokenUsageRecord row : rows.values()) {
            if (row.ownerUuid.equals(ownerUuid)) {
                agg.name = row.ownerName != null && !row.ownerName.isBlank() ? row.ownerName : agg.name;
                agg.add(row);
                playerRows.add(row);
            }
        }
        return new PlayerDetail(agg.toSummary(), costOf(playerRows, prices));
    }

    /** 已知玩家：uuid → 显示名 */
    public Map<String, String> knownPlayers() {
        Map<String, String> players = new HashMap<>();
        for (TokenUsageRecord row : rows.values()) {
            players.merge(row.ownerUuid, row.ownerName, (oldName, newName) ->
                    newName == null || newName.isBlank() ? oldName : newName);
        }
        return players;
    }

    /** 数据中最早出现的 epochDay（无数据返回 todayEpochDay） */
    public int minDay(int todayEpochDay) {
        int min = Integer.MAX_VALUE;
        for (RowKey key : rows.keySet()) {
            min = Math.min(min, key.day);
        }
        return min == Integer.MAX_VALUE ? todayEpochDay : min;
    }

    /** 按桶序列聚合服务器总消耗（含成本） */
    public List<BucketTotals> serverBuckets(List<GranularityUtil.Bucket> buckets, PriceTable prices) {
        List<BucketTotals> result = new ArrayList<>(buckets.size());
        for (GranularityUtil.Bucket bucket : buckets) {
            Agg agg = new Agg("", "");
            List<TokenUsageRecord> bucketRows = new ArrayList<>();
            for (TokenUsageRecord row : rows.values()) {
                if (bucket.contains(row.day)) {
                    agg.add(row);
                    bucketRows.add(row);
                }
            }
            CostResult cost = costOf(bucketRows, prices);
            result.add(new BucketTotals(bucket.startDay(), bucket.endDay(), bucket.unit(),
                    agg.input, agg.output, agg.cacheRead, agg.cacheWrite,
                    cost.cny(), cost.usd(), cost.anyCnyPrice(), cost.anyUsdPrice()));
        }
        return result;
    }

    /** 按 (站点, 模型) 逐行用现价累加成本；未配置价格的模型部分不计入 */
    private static CostResult costOf(List<TokenUsageRecord> rowsIn, PriceTable prices) {
        double cny = 0;
        double usd = 0;
        boolean anyCny = false;
        boolean anyUsd = false;
        for (TokenUsageRecord row : rowsIn) {
            var cnyPrice = prices.getPrice(row.siteId, row.model, PriceTable.Currency.CNY);
            if (cnyPrice.isPresent()) {
                cny += cnyPrice.get().cost(row.inputTokens, row.outputTokens, row.cacheReadTokens, row.cacheWriteTokens);
                anyCny = true;
            }
            var usdPrice = prices.getPrice(row.siteId, row.model, PriceTable.Currency.USD);
            if (usdPrice.isPresent()) {
                usd += usdPrice.get().cost(row.inputTokens, row.outputTokens, row.cacheReadTokens, row.cacheWriteTokens);
                anyUsd = true;
            }
        }
        return new CostResult(cny, usd, anyCny, anyUsd);
    }

    // ---------- 持久化 ----------

    /** 从目录加载全部月度文件 */
    public void load(Path dir) {
        rows.clear();
        dirtyMonths.clear();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "stats-*.json")) {
            for (Path file : stream) {
                Matcher matcher = FILE_PATTERN.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                try {
                    List<TokenUsageRecord> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ROW_LIST_TYPE);
                    if (loaded == null) {
                        continue;
                    }
                    for (TokenUsageRecord row : loaded) {
                        if (row.ownerUuid == null || row.siteId == null || row.model == null) {
                            continue;
                        }
                        rows.put(new RowKey(row.day, row.ownerUuid, row.siteId, row.model), row);
                    }
                } catch (JsonSyntaxException e) {
                    // 单个文件损坏只跳过该月，不影响其余数据
                    LOGGER.warn("统计文件 {} 损坏，已跳过该月数据", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("统计目录不可读: " + dir, e);
        }
    }

    /** 落盘所有脏月份（无脏数据时为空操作） */
    public void flush(Path dir) throws IOException {
        if (dirtyMonths.isEmpty()) {
            return;
        }
        Files.createDirectories(dir);
        for (Integer monthKey : List.copyOf(dirtyMonths)) {
            LocalDate monthStart = LocalDate.of(monthKey / 12, monthKey % 12 + 1, 1);
            int firstDay = (int) monthStart.toEpochDay();
            int lastDay = (int) monthStart.plusDays(monthStart.lengthOfMonth() - 1).toEpochDay();

            List<TokenUsageRecord> monthRows = new ArrayList<>();
            for (TokenUsageRecord row : rows.values()) {
                if (row.day >= firstDay && row.day <= lastDay) {
                    monthRows.add(row);
                }
            }
            Path file = dir.resolve("stats-%04d-%02d.json".formatted(monthStart.getYear(), monthStart.getMonthValue()));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(monthRows, ROW_LIST_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirtyMonths.remove(monthKey);
            // 写盘期间回调线程可能新增该月数据（弱一致迭代未覆盖）：有遗漏则保留脏标记，下次落盘补写
            for (TokenUsageRecord row : rows.values()) {
                if (row.day >= firstDay && row.day <= lastDay && !monthRows.contains(row)) {
                    dirtyMonths.add(monthKey);
                    break;
                }
            }
        }
    }

    private static int monthKey(int day) {
        LocalDate date = LocalDate.ofEpochDay(day);
        return date.getYear() * 12 + date.getMonthValue() - 1;
    }

    // ---------- 内部累加器 ----------

    private static final class Agg {
        final String uuid;
        String name;
        long input;
        long output;
        long cacheRead;
        long cacheWrite;
        long requests;

        Agg(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        void add(TokenUsageRecord row) {
            input += row.inputTokens;
            output += row.outputTokens;
            cacheRead += row.cacheReadTokens;
            cacheWrite += row.cacheWriteTokens;
            requests += row.requestCount;
        }

        PlayerSummary toSummary() {
            return new PlayerSummary(uuid, name, input, output, cacheRead, cacheWrite, requests);
        }
    }
}

package com.xm2nd.tlmagenttweaks.stats;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.Config;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.Price4;
import com.xm2nd.tlmagenttweaks.common.PriceTable;
import com.xm2nd.tlmagenttweaks.common.TokenStatsStore;
import com.xm2nd.tlmagenttweaks.common.TokenUsageParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 服务端统计服务：包装 common 引擎，负责生命周期（加载/落盘）与 LLM 响应记账入口。
 * <p>
 * 统计数据按月存于 {@code <世界存档>/tlm_agent_tweaks/stats-yyyy-MM.json}；
 * 价格表存于全局 {@code config/tlm_agent_tweaks/prices.json}（不随世界）。
 */
public final class TokenStatsService {
    private static final TokenStatsStore STORE = new TokenStatsStore();
    private static final PriceTable PRICES = new PriceTable();
    private static final long FLUSH_INTERVAL_MS = 5 * 60 * 1000;

    private static Path statsDir;
    private static Path pricesFile;
    private static volatile long lastFlushMs;

    private TokenStatsService() {
    }

    public static TokenStatsStore store() {
        return STORE;
    }

    public static PriceTable prices() {
        return PRICES;
    }

    /** 统计记录中的 ownerName 可能为空；优先使用服务器掌握的真实玩家名。 */
    public static String playerName(MinecraftServer server, String uuid, String recordedName) {
        UUID id = UUID.fromString(uuid);
        var online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        if (server.getProfileCache() != null) {
            var profile = server.getProfileCache().get(id);
            if (profile.isPresent()) {
                return profile.get().getName();
            }
        }
        // 从未缓存过的离线玩家只能沿用历史名字；不发起外部账号查询。
        return recordedName == null || recordedName.isBlank() ? uuid : recordedName;
    }

    // ---------- 生命周期 ----------

    public static void onServerStarted(MinecraftServer server) {
        try {
            statsDir = server.getWorldPath(new net.minecraft.world.level.storage.LevelResource("tlm_agent_tweaks"));
            STORE.load(statsDir);
        } catch (Exception e) {
            TlmAgentTweaks.LOGGER.error("token 统计数据加载失败，本次启动将从空数据开始", e);
        }
        try {
            pricesFile = FMLPaths.CONFIGDIR.get().resolve("tlm_agent_tweaks").resolve("prices.json");
            PRICES.load(pricesFile);
        } catch (Exception e) {
            TlmAgentTweaks.LOGGER.error("模型价格表加载失败", e);
        }
        lastFlushMs = System.currentTimeMillis();
    }

    public static void onServerStopping() {
        flush();
        savePrices();
    }

    public static void onServerTick() {
        long now = System.currentTimeMillis();
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            lastFlushMs = now;
            flush();
        }
    }

    private static void flush() {
        if (statsDir == null) {
            return;
        }
        try {
            STORE.flush(statsDir);
        } catch (IOException e) {
            TlmAgentTweaks.LOGGER.error("token 统计数据落盘失败", e);
        }
    }

    public static void savePrices() {
        if (pricesFile == null) {
            return;
        }
        try {
            PRICES.save(pricesFile);
        } catch (IOException e) {
            TlmAgentTweaks.LOGGER.error("模型价格表保存失败", e);
        }
    }

    // ---------- 记账 ----------

    /**
     * LLM 响应记账入口（由 LLMOpenAIClientMixin 在响应处理头部调用，位于 HTTP 回调线程）。
     * 任何异常都不上抛，绝不影响 TLM 原生聊天流程。
     */
    public static void onLlmResponse(LLMCallback callback, LLMOpenAISite site,
                                     HttpResponse<String> response, Throwable throwable) {
        if (!Config.STATS_ENABLED.get()) {
            return;
        }
        if (response == null || throwable != null || response.statusCode() != 200) {
            return;
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return;
        }
        TokenUsageParser.UsageStats usage = TokenUsageParser.parse(body);
        if (usage == null) {
            return;
        }
        if (!Config.STATS_RECORD_SUMMARY_CALLS.get() && isSummaryCallback(callback)) {
            return;
        }
        EntityMaid maid = callback.getMaid();
        if (maid == null) {
            return;
        }
        UUID owner = maid.getOwnerUUID();
        if (owner == null) {
            return;
        }
        String ownerName = maid.getAiChatManager().ownerName;
        String model = maid.getAiChatManager().getLLMModel();
        STORE.record(owner.toString(), ownerName, site.id(), model,
                usage.inputOnly(), usage.completionTokens(), usage.cacheRead(), usage.cacheWrite(),
                System.currentTimeMillis());
    }

    private static boolean isSummaryCallback(LLMCallback callback) {
        return "HistorySummaryCallback".equals(callback.getClass().getSimpleName());
    }

    // ---------- 价格 ----------

    /** 写入一条价格并立即落盘；price 传 null 表示清除该币种配置 */
    public static void setPrice(String siteId, String model, PriceTable.Currency currency, Price4 price) {
        PRICES.setPrice(siteId, model, currency, price);
        savePrices();
    }

    public static boolean isAdmin(net.minecraft.server.level.ServerPlayer player) {
        return player.hasPermissions(Config.STATS_ADMIN_PERMISSION_LEVEL.get());
    }
}

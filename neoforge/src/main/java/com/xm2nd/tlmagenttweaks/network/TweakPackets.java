package com.xm2nd.tlmagenttweaks.network;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.ClientPacketHandlers;
import com.xm2nd.tlmagenttweaks.common.GranularityUtil;
import com.xm2nd.tlmagenttweaks.common.Price4;
import com.xm2nd.tlmagenttweaks.common.PriceTable;
import com.xm2nd.tlmagenttweaks.common.TokenStatsStore;
import com.xm2nd.tlmagenttweaks.compress.ManualCompressService;
import com.xm2nd.tlmagenttweaks.stats.TokenStatsService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 网络包注册与服务端处理。
 * <p>
 * 客户端处理统一走 {@link ClientPacketHandlers}（仅在 lambda 内引用，专用服务端不会加载该类）。
 */
public final class TweakPackets {

    private TweakPackets() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // optional：未安装本 mod 的客户端可以正常加入装有本 mod 的服务器
        // （服务端记账/压缩等功能照常运行，仅缺少可视化入口）。
        // 反方向（客户端装了、服务端没装）由 ServerPresenceGate 在登录后主动断开，
        // 保证服务端必装的语义。
        PayloadRegistrar registrar = event.registrar(TlmAgentTweaks.MOD_ID).versioned("1").optional();
        registrar.playToServer(RequestCompressHistoryPayload.TYPE, RequestCompressHistoryPayload.STREAM_CODEC,
                TweakPackets::handleCompressRequest);
        registrar.playToClient(CompressHistoryResultPayload.TYPE, CompressHistoryResultPayload.STREAM_CODEC,
                TweakPackets::handleCompressResult);
        registrar.playToServer(RequestCompressStatePayload.TYPE, RequestCompressStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ManualCompressService.handleStateRequest(player, payload.entityId());
                    }
                }));
        registrar.playToClient(CompressHistoryStatePayload.TYPE, CompressHistoryStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPacketHandlers.onCompressState(payload)));
        registrar.playToServer(RequestTokenStatsPayload.TYPE, RequestTokenStatsPayload.STREAM_CODEC,
                TweakPackets::handleStatsRequest);
        registrar.playToClient(TokenStatsDataPayload.TYPE, TokenStatsDataPayload.STREAM_CODEC,
                TweakPackets::handleStatsData);
        registrar.playToServer(RequestPriceTablePayload.TYPE, RequestPriceTablePayload.STREAM_CODEC,
                TweakPackets::handlePriceTableRequest);
        registrar.playToServer(SaveModelPricePayload.TYPE, SaveModelPricePayload.STREAM_CODEC,
                TweakPackets::handleSavePrice);
        registrar.playToClient(PriceTablePayload.TYPE, PriceTablePayload.STREAM_CODEC,
                TweakPackets::handlePriceTable);
        registrar.playToClient(PriceDeniedPayload.TYPE, PriceDeniedPayload.STREAM_CODEC,
                TweakPackets::handlePriceDenied);
        // 思考链展示快照：新增的 optional S2C 通道，不支持它的客户端照常进服（不接收即可）
        registrar.playToClient(ThinkingDisplayPayload.TYPE, ThinkingDisplayPayload.STREAM_CODEC,
                TweakPackets::handleThinkingDisplay);
    }

    // ---------- 压缩 ----------

    private static void handleCompressRequest(RequestCompressHistoryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                ManualCompressService.handleRequest(serverPlayer, payload.entityId());
            }
        });
    }

    private static void handleCompressResult(CompressHistoryResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.onCompressResult(payload));
    }

    // ---------- 统计 ----------

    private static void handleStatsRequest(RequestTokenStatsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!TokenStatsService.isAdmin(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.translatable("message.tlm_agent_tweaks.stats.denied")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                context.reply(new TokenStatsDataPayload(TokenStatsDataPayload.VIEW_DENIED,
                        List.of(), null, 0, List.of()));
                return;
            }
            TokenStatsStore store = TokenStatsService.store();
            switch (payload.viewType()) {
                case RequestTokenStatsPayload.VIEW_RANKING -> {
                    List<TokenStatsDataPayload.RankEntry> ranking = store.playerRanking().stream()
                            .map(p -> new TokenStatsDataPayload.RankEntry(p.uuid(),
                                    TokenStatsService.playerName(serverPlayer.getServer(), p.uuid(), p.name()), p.input(), p.output(),
                                    p.cacheRead(), p.cacheWrite(), p.requests()))
                            .toList();
                    context.reply(new TokenStatsDataPayload(RequestTokenStatsPayload.VIEW_RANKING,
                            ranking, null, 0, List.of()));
                }
                case RequestTokenStatsPayload.VIEW_PLAYER_DETAIL -> {
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(payload.param());
                    } catch (IllegalArgumentException exception) {
                        TlmAgentTweaks.LOGGER.warn("拒绝来自 {} 的非法玩家 UUID 统计请求",
                                serverPlayer.getGameProfile().getName());
                        return;
                    }
                    TokenStatsStore.PlayerDetail detail =
                            store.playerDetail(playerId.toString(), TokenStatsService.prices());
                    TokenStatsStore.PlayerSummary s = detail.summary();
                    context.reply(new TokenStatsDataPayload(RequestTokenStatsPayload.VIEW_PLAYER_DETAIL,
                            List.of(),
                            new TokenStatsDataPayload.PlayerDetailDto(s.uuid(),
                                    TokenStatsService.playerName(serverPlayer.getServer(), s.uuid(), s.name()), s.input(), s.output(),
                                    s.cacheRead(), s.cacheWrite(), s.requests(), s.cacheHitRate(),
                                    detail.cost().cny(), detail.cost().anyCnyPrice(),
                                    detail.cost().usd(), detail.cost().anyUsdPrice()),
                            0, List.of()));
                }
                case RequestTokenStatsPayload.VIEW_SERVER -> {
                    // 统计窗口只接受白名单值，非法天数不进入聚合（避免伪造值产生数千桶）
                    if (!GranularityUtil.isSupportedWindow(payload.windowDays())) {
                        TlmAgentTweaks.LOGGER.warn("拒绝来自 {} 的非法统计窗口 {}",
                                serverPlayer.getGameProfile().getName(), payload.windowDays());
                        return;
                    }
                    int today = (int) LocalDate.now().toEpochDay();
                    List<GranularityUtil.Bucket> buckets =
                            GranularityUtil.buckets(payload.windowDays(), today, store.minDay(today));
                    List<TokenStatsStore.BucketTotals> totals = store.serverBuckets(buckets, TokenStatsService.prices());
                    List<TokenStatsDataPayload.BucketDto> dtoList = totals.stream()
                            .map(t -> new TokenStatsDataPayload.BucketDto(t.startDay(), t.endDay(),
                                    t.unit().ordinal(), t.input(), t.output(), t.cacheRead(), t.cacheWrite(),
                                    t.costCny(), t.anyCnyPrice(), t.costUsd(), t.anyUsdPrice()))
                            .toList();
                    context.reply(new TokenStatsDataPayload(RequestTokenStatsPayload.VIEW_SERVER,
                            List.of(), null, payload.windowDays(), dtoList));
                }
                default -> {
                }
            }
        });
    }

    private static void handleStatsData(TokenStatsDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.onStatsData(payload));
    }

    // ---------- 价格 ----------

    private static void handlePriceTableRequest(RequestPriceTablePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!TokenStatsService.isAdmin(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.translatable("message.tlm_agent_tweaks.stats.denied")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                context.reply(new PriceDeniedPayload());
                return;
            }
            context.reply(new PriceTablePayload(TokenStatsService.prices().snapshot()));
        });
    }

    private static void handlePriceTable(PriceTablePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.onPriceTable(payload));
    }

    private static void handlePriceDenied(PriceDeniedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.onPriceDenied(payload));
    }

    private static void handleSavePrice(SaveModelPricePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!TokenStatsService.isAdmin(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.translatable("message.tlm_agent_tweaks.stats.denied")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
            // 站点/模型必须存在于管理员配置的可用站点表，拒绝任意伪造键
            LLMSite site = AvailableSites.getLLMSite(payload.siteId());
            if (!(site instanceof LLMOpenAISite openAiSite) || !openAiSite.modelEntries().containsKey(payload.model())) {
                serverPlayer.sendSystemMessage(Component.translatable("message.tlm_agent_tweaks.price.invalid_model")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
            PriceTable.Currency currency = switch (payload.currency()) {
                case "cny" -> PriceTable.Currency.CNY;
                case "usd" -> PriceTable.Currency.USD;
                default -> null;
            };
            if (currency == null) {
                return;
            }
            if (payload.clear()) {
                TokenStatsService.setPrice(payload.siteId(), payload.model(), currency, null);
                serverPlayer.sendSystemMessage(Component.translatable("message.tlm_agent_tweaks.price.cleared",
                        payload.model()));
            } else {
                // 载荷构造已保证非清除时恰好四个价格，这里只做数值语义校验
                double[] values = payload.values();
                for (double value : values) {
                    if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
                        return;
                    }
                }
                TokenStatsService.setPrice(payload.siteId(), payload.model(), currency,
                        new Price4(values[0], values[1], values[2], values[3]));
                serverPlayer.sendSystemMessage(Component.translatable("message.tlm_agent_tweaks.price.saved",
                        payload.model()));
            }
            context.reply(new PriceTablePayload(TokenStatsService.prices().snapshot()));
        });
    }

    // ---------- 思考链展示 ----------

    private static void handleThinkingDisplay(ThinkingDisplayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.onThinkingDisplay(payload));
    }
}

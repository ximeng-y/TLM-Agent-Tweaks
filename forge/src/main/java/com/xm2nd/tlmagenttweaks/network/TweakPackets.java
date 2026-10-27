package com.xm2nd.tlmagenttweaks.network;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.client.ClientPacketHandlers;
import com.xm2nd.tlmagenttweaks.common.GranularityUtil;
import com.xm2nd.tlmagenttweaks.common.Price4;
import com.xm2nd.tlmagenttweaks.common.PriceTable;
import com.xm2nd.tlmagenttweaks.common.TokenStatsStore;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLevel;
import com.xm2nd.tlmagenttweaks.compress.ManualCompressService;
import com.xm2nd.tlmagenttweaks.protocol.MaidThinkingChoiceStore;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolLLMSite;
import com.xm2nd.tlmagenttweaks.stats.TokenStatsService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 网络包注册与服务端处理。
 * <p>
 * 通道按「允许缺失」注册（{@link NetworkRegistry#acceptMissingOr}）：未安装本 mod 的客户端
 * 可以正常加入装有本 mod 的服务器，服务端记账与压缩功能照常运行，仅缺少可视化入口。
 * 反方向（客户端装了、服务端没装）由客户端侧的守卫在登录后主动断开，保证服务端必装的语义。
 * <p>
 * 客户端处理统一走 {@link ClientPacketHandlers}（仅在 lambda 内引用，专用服务端不会加载该类）。
 */
public final class TweakPackets {

    private static final String PROTOCOL_VERSION = "1";

    private static SimpleChannel channel;

    private TweakPackets() {
    }

    /** 建通道并逐包注册；由入口类的构造器调用一次。 */
    public static void register() {
        SimpleChannel created = NetworkRegistry.newSimpleChannel(
                ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));
        channel = created;

        // ---------- 压缩 ----------
        toServer(created, RequestCompressHistoryPayload.class, 0, TweakPackets::handleCompressRequest);
        toClient(created, CompressHistoryResultPayload.class, 1, TweakPackets::handleCompressResult);
        toServer(created, RequestCompressStatePayload.class, 2, TweakPackets::handleCompressStateRequest);
        toClient(created, CompressHistoryStatePayload.class, 3, TweakPackets::handleCompressState);

        // ---------- 统计 ----------
        toServer(created, RequestTokenStatsPayload.class, 4, TweakPackets::handleStatsRequest);
        toClient(created, TokenStatsDataPayload.class, 5, TweakPackets::handleStatsData);

        // ---------- 价格 ----------
        toServer(created, RequestPriceTablePayload.class, 6, TweakPackets::handlePriceTableRequest);
        toServer(created, SaveModelPricePayload.class, 7, TweakPackets::handleSavePrice);
        toClient(created, PriceTablePayload.class, 8, TweakPackets::handlePriceTable);
        toClient(created, PriceDeniedPayload.class, 10, TweakPackets::handlePriceDenied);

        // 思考链展示快照：不支持它的客户端照常进服（不接收即可）
        toClient(created, ThinkingDisplayPayload.class, 9, TweakPackets::handleThinkingDisplay);

        // 思考配置快照与玩家思考选择：与思考链展示一样允许缺失，不影响进服
        toServer(created, RequestThinkingConfigPayload.class, 10, TweakPackets::handleThinkingConfigRequest);
        toClient(created, ThinkingConfigPayload.class, 11, TweakPackets::handleThinkingConfig);
        toServer(created, SaveThinkingChoicePayload.class, 12, TweakPackets::handleSaveThinkingChoice);
    }

    /** 已建的通道；未完成注册时为 {@code null}。 */
    public static SimpleChannel channel() {
        return channel;
    }

    // ---------- 发送快捷方式 ----------

    public static <T> void sendToServer(T message) {
        channel.sendToServer(message);
    }

    public static <T> void sendToPlayer(ServerPlayer player, T message) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static <T> void sendToAllPlayers(T message) {
        channel.send(PacketDistributor.ALL.noArg(), message);
    }

    // ---------- 注册辅助 ----------

    private static <T> void toServer(SimpleChannel channel, Class<T> type, int id,
                                     BiConsumer<T, Supplier<NetworkEvent.Context>> consumer) {
        register(channel, type, id, NetworkDirection.PLAY_TO_SERVER, consumer);
    }

    private static <T> void toClient(SimpleChannel channel, Class<T> type, int id,
                                     BiConsumer<T, Supplier<NetworkEvent.Context>> consumer) {
        register(channel, type, id, NetworkDirection.PLAY_TO_CLIENT, consumer);
    }

    private static <T> void register(SimpleChannel channel, Class<T> type, int id,
                                     NetworkDirection direction,
                                     BiConsumer<T, Supplier<NetworkEvent.Context>> consumer) {
        Function<FriendlyByteBuf, T> decoder = buf -> decode(type, buf);
        channel.messageBuilder(type, id, direction)
                .encoder(TweakPackets::encode)
                .decoder(decoder)
                .consumerMainThread(consumer)
                .add();
    }

    private static <T> void encode(T message, FriendlyByteBuf buf) {
        // 目标平台是 Java 17，此处不能用 switch 的模式匹配
        if (message instanceof RequestCompressHistoryPayload payload) {
            payload.write(buf);
        } else if (message instanceof CompressHistoryResultPayload payload) {
            payload.write(buf);
        } else if (message instanceof RequestCompressStatePayload payload) {
            payload.write(buf);
        } else if (message instanceof CompressHistoryStatePayload payload) {
            payload.write(buf);
        } else if (message instanceof RequestTokenStatsPayload payload) {
            payload.write(buf);
        } else if (message instanceof TokenStatsDataPayload payload) {
            payload.write(buf);
        } else if (message instanceof RequestPriceTablePayload payload) {
            payload.write(buf);
        } else if (message instanceof SaveModelPricePayload payload) {
            payload.write(buf);
        } else if (message instanceof PriceTablePayload payload) {
            payload.write(buf);
        } else if (message instanceof PriceDeniedPayload payload) {
            payload.write(buf);
        } else if (message instanceof ThinkingDisplayPayload payload) {
            payload.write(buf);
        } else if (message instanceof RequestThinkingConfigPayload payload) {
            payload.write(buf);
        } else if (message instanceof ThinkingConfigPayload payload) {
            payload.write(buf);
        } else if (message instanceof SaveThinkingChoicePayload payload) {
            payload.write(buf);
        } else {
            throw new IllegalArgumentException("未注册的网络包类型: " + message.getClass().getName());
        }
    }

    private static <T> T decode(Class<T> type, FriendlyByteBuf buf) {
        Object payload;
        if (type == RequestCompressHistoryPayload.class) {
            payload = RequestCompressHistoryPayload.read(buf);
        } else if (type == CompressHistoryResultPayload.class) {
            payload = CompressHistoryResultPayload.read(buf);
        } else if (type == RequestCompressStatePayload.class) {
            payload = RequestCompressStatePayload.read(buf);
        } else if (type == CompressHistoryStatePayload.class) {
            payload = CompressHistoryStatePayload.read(buf);
        } else if (type == RequestTokenStatsPayload.class) {
            payload = RequestTokenStatsPayload.read(buf);
        } else if (type == TokenStatsDataPayload.class) {
            payload = TokenStatsDataPayload.read(buf);
        } else if (type == RequestPriceTablePayload.class) {
            payload = RequestPriceTablePayload.read(buf);
        } else if (type == SaveModelPricePayload.class) {
            payload = SaveModelPricePayload.read(buf);
        } else if (type == PriceTablePayload.class) {
            payload = PriceTablePayload.read(buf);
        } else if (type == PriceDeniedPayload.class) {
            payload = PriceDeniedPayload.read(buf);
        } else if (type == ThinkingDisplayPayload.class) {
            payload = ThinkingDisplayPayload.read(buf);
        } else if (type == RequestThinkingConfigPayload.class) {
            payload = RequestThinkingConfigPayload.read(buf);
        } else if (type == ThinkingConfigPayload.class) {
            payload = ThinkingConfigPayload.read(buf);
        } else if (type == SaveThinkingChoicePayload.class) {
            payload = SaveThinkingChoicePayload.read(buf);
        } else {
            throw new IllegalArgumentException("未注册的网络包类型: " + type.getName());
        }
        return type.cast(payload);
    }

    private static void reply(Object message, NetworkEvent.Context context) {
        channel.reply(message, context);
    }

    // ---------- 压缩 ----------

    private static void handleCompressRequest(RequestCompressHistoryPayload payload,
                                              Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer serverPlayer = ctx.get().getSender();
        if (serverPlayer != null) {
            ManualCompressService.handleRequest(serverPlayer, payload.entityId());
        }
    }

    private static void handleCompressResult(CompressHistoryResultPayload payload,
                                             Supplier<NetworkEvent.Context> ctx) {
        ClientPacketHandlers.onCompressResult(payload);
    }

    private static void handleCompressStateRequest(RequestCompressStatePayload payload,
                                                   Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer serverPlayer = ctx.get().getSender();
        if (serverPlayer != null) {
            ManualCompressService.handleStateRequest(serverPlayer, payload.entityId());
        }
    }

    private static void handleCompressState(CompressHistoryStatePayload payload,
                                            Supplier<NetworkEvent.Context> ctx) {
        ClientPacketHandlers.onCompressState(payload);
    }

    // ---------- 统计 ----------

    private static void handleStatsRequest(RequestTokenStatsPayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer serverPlayer = context.getSender();
        if (serverPlayer == null) {
            return;
        }
        if (!TokenStatsService.isAdmin(serverPlayer)) {
            serverPlayer.sendSystemMessage(Component.translatable("message.tlm_agent_tweaks.stats.denied")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            reply(new TokenStatsDataPayload(TokenStatsDataPayload.VIEW_DENIED,
                    List.of(), null, 0, List.of()), context);
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
                reply(new TokenStatsDataPayload(RequestTokenStatsPayload.VIEW_RANKING,
                        ranking, null, 0, List.of()), context);
            }
            case RequestTokenStatsPayload.VIEW_PLAYER_DETAIL -> {
                String queryId;
                if (store.containsPlayer(payload.param())) {
                    // 排行/下拉框会回传存档中的原始 ID（含历史坏 UUID），直接放行查询
                    queryId = payload.param();
                } else {
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(payload.param());
                    } catch (IllegalArgumentException exception) {
                        TlmAgentTweaks.LOGGER.warn("拒绝来自 {} 的非法玩家 UUID 统计请求",
                                serverPlayer.getGameProfile().getName());
                        return;
                    }
                    // 原始 ID 不存在但参数合法：保持按规范化后的 UUID 查询
                    queryId = playerId.toString();
                }
                TokenStatsStore.PlayerDetail detail =
                        store.playerDetail(queryId, TokenStatsService.prices());
                TokenStatsStore.PlayerSummary s = detail.summary();
                reply(new TokenStatsDataPayload(RequestTokenStatsPayload.VIEW_PLAYER_DETAIL,
                        List.of(),
                        new TokenStatsDataPayload.PlayerDetailDto(s.uuid(),
                                TokenStatsService.playerName(serverPlayer.getServer(), s.uuid(), s.name()), s.input(), s.output(),
                                s.cacheRead(), s.cacheWrite(), s.requests(), s.cacheHitRate(),
                                detail.cost().cny(), detail.cost().anyCnyPrice(),
                                detail.cost().usd(), detail.cost().anyUsdPrice()),
                        0, List.of()), context);
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
                reply(new TokenStatsDataPayload(RequestTokenStatsPayload.VIEW_SERVER,
                        List.of(), null, payload.windowDays(), dtoList), context);
            }
            default -> {
            }
        }
    }

    private static void handleStatsData(TokenStatsDataPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ClientPacketHandlers.onStatsData(payload);
    }

    // ---------- 价格 ----------

    private static void handlePriceTableRequest(RequestPriceTablePayload payload,
                                                Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer serverPlayer = context.getSender();
        if (serverPlayer == null) {
            return;
        }
        if (!TokenStatsService.isAdmin(serverPlayer)) {
            serverPlayer.sendSystemMessage(Component.translatable("gui.tlm_agent_tweaks.price.denied")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            reply(new PriceDeniedPayload(), context);
            return;
        }
        reply(new PriceTablePayload(TokenStatsService.prices().snapshot()), context);
    }

    private static void handlePriceTable(PriceTablePayload payload, Supplier<NetworkEvent.Context> ctx) {
        ClientPacketHandlers.onPriceTable(payload);
    }

    private static void handlePriceDenied(PriceDeniedPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ClientPacketHandlers.onPriceDenied(payload);
    }

    private static void handleSavePrice(SaveModelPricePayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer serverPlayer = context.getSender();
        if (serverPlayer == null) {
            return;
        }
        if (!TokenStatsService.isAdmin(serverPlayer)) {
            serverPlayer.sendSystemMessage(Component.translatable("gui.tlm_agent_tweaks.price.denied")
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
        reply(new PriceTablePayload(TokenStatsService.prices().snapshot()), context);
    }

    // ---------- 思考链展示 ----------

    private static void handleThinkingDisplay(ThinkingDisplayPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ClientPacketHandlers.onThinkingDisplay(payload);
    }

    // ---------- 思考配置与选择 ----------

    private static void handleThinkingConfigRequest(RequestThinkingConfigPayload payload,
                                                    Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        Map<String, Map<String, ModelThinkingConfig>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, LLMSite> entry : AvailableSites.LLM_SITES.entrySet()) {
            if (!(entry.getValue() instanceof ProtocolLLMSite protocolSite) || !protocolSite.enabled()) {
                continue;
            }
            Map<String, ModelThinkingConfig> configs = protocolSite.thinkingConfigs();
            if (!configs.isEmpty()) {
                snapshot.put(entry.getKey(), configs);
            }
        }
        reply(new ThinkingConfigPayload(snapshot,
                currentThinkingChoice(player, payload.entityId())), context);
    }

    /**
     * 读取请求所带女仆已存的思考选择，供玩家侧选择屏回显；
     * 实体不存在、不归属该玩家或没有有效记录时返回 null。
     */
    private static ThinkingConfigPayload.CurrentChoice currentThinkingChoice(ServerPlayer player, int entityId) {
        if (!(player.level().getEntity(entityId) instanceof EntityMaid maid) || !maid.isOwnedBy(player)) {
            return null;
        }
        MaidThinkingChoiceStore.Stored stored = MaidThinkingChoiceStore.read(maid);
        if (stored == null) {
            return null;
        }
        return new ThinkingConfigPayload.CurrentChoice(entityId, stored.siteId(), stored.modelId(),
                stored.choice().enabled(), stored.choice().level().id());
    }

    private static void handleThinkingConfig(ThinkingConfigPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ClientPacketHandlers.onThinkingConfig(payload);
    }

    private static void handleSaveThinkingChoice(SaveThinkingChoicePayload payload,
                                                 Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null) {
            return;
        }
        if (!(player.level().getEntity(payload.entityId()) instanceof EntityMaid maid)
                || !maid.isOwnedBy(player)) {
            return;
        }
        // 与价格配置同思路：站点/模型必须是管理员配置过的，选择范围也必须落在配置内
        if (!(AvailableSites.getLLMSite(payload.siteId()) instanceof ProtocolLLMSite protocolSite)) {
            return;
        }
        ModelThinkingConfig config = protocolSite.thinkingConfigs().get(payload.modelId());
        if (config == null || !config.configured()) {
            return;
        }
        ThinkingLevel level = ThinkingLevel.fromId(payload.levelId());
        if (!payload.enabled()) {
            // 「关闭」是开关提供的选项：不允许开关时不得关闭
            if (!config.toggleAllowed()) {
                return;
            }
            MaidThinkingChoiceStore.write(maid, payload.siteId(), payload.modelId(), false, ThinkingLevel.DEFAULT);
            return;
        }
        // 开关未开放时思考隐含开启，只校验强度落在配置内
        if (level == null || !config.allows(level)) {
            return;
        }
        MaidThinkingChoiceStore.write(maid, payload.siteId(), payload.modelId(), true, level);
    }
}

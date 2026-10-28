package com.xm2nd.tlmagenttweaks.server;

import com.xm2nd.tlmagenttweaks.chat.ChatVisibilityService;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolMetadataPersistence;
import com.xm2nd.tlmagenttweaks.stats.TokenStatsService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端生命周期：统计引擎的加载、周期落盘与停服落盘。
 */
public final class ServerLifecycleHandler {

    private ServerLifecycleHandler() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        TokenStatsService.onServerStarted(event.getServer());
        ProtocolMetadataPersistence.onServerStarted(event.getServer());
        ChatVisibilityService.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // 每次进入服务器世界的黄字通知入口，只处理真实 ServerPlayer
        if (event.getEntity() instanceof ServerPlayer player) {
            ChatVisibilityService.onPlayerLoggedIn(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        TokenStatsService.onServerTick();
        ProtocolMetadataPersistence.onServerTick();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ProtocolMetadataPersistence.onServerStopping();
        TokenStatsService.onServerStopping();
        ChatVisibilityService.onServerStopping();
    }
}

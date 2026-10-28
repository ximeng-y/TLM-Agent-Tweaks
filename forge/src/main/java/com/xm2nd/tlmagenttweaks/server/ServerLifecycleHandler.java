package com.xm2nd.tlmagenttweaks.server;

import com.xm2nd.tlmagenttweaks.chat.ChatVisibilityService;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolMetadataPersistence;
import com.xm2nd.tlmagenttweaks.stats.TokenStatsService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;

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
    public static void onServerTick(ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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

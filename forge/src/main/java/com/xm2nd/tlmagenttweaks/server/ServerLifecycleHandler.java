package com.xm2nd.tlmagenttweaks.server;

import com.xm2nd.tlmagenttweaks.protocol.ProtocolMetadataPersistence;
import com.xm2nd.tlmagenttweaks.stats.TokenStatsService;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
    }
}

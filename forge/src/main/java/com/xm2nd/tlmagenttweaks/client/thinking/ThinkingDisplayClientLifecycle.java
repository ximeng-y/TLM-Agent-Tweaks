package com.xm2nd.tlmagenttweaks.client.thinking;

import com.xm2nd.tlmagenttweaks.network.ThinkingDisplayPayload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ClientTickEvent;

/**
 * 客户端思考链展示的生命周期：每 tick 推进快照缓存，退出世界时清空。
 */
@OnlyIn(Dist.CLIENT)
public final class ThinkingDisplayClientLifecycle {

    private ThinkingDisplayClientLifecycle() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ThinkingDisplayCache.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ThinkingDisplayCache.clear();
    }
}

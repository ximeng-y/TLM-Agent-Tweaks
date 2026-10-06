package com.xm2nd.tlmagenttweaks.client.thinking;

import com.xm2nd.tlmagenttweaks.network.ThinkingDisplayPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端思考链展示的生命周期：每 tick 推进快照缓存，退出世界时清空。
 */
@OnlyIn(Dist.CLIENT)
public final class ThinkingDisplayClientLifecycle {

    private ThinkingDisplayClientLifecycle() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ThinkingDisplayCache.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ThinkingDisplayCache.clear();
    }
}

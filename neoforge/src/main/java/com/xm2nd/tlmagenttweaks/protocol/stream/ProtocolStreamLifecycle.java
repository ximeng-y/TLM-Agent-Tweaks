package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 流式请求生命周期：实体离开世界与服务器停止时主动取消在途请求，避免残留回调
 * 污染重进世界后的对话；取消不会触发非流式回退。
 */
public final class ProtocolStreamLifecycle {
    private ProtocolStreamLifecycle() {
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof EntityMaid maid) {
            ProtocolHttpBridge.cancelMaid(maid.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ProtocolHttpBridge.cancelAll();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ProtocolHttpBridge.onServerStarted();
    }
}

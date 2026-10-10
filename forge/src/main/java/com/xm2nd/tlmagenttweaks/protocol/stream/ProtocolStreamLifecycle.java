package com.xm2nd.tlmagenttweaks.protocol.stream;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;

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
        // 停服：作废在途请求、丢弃站点排队任务、并结束全部整轮预算。
        ProtocolHttpBridge.cancelAll();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ProtocolHttpBridge.onServerStarted();
    }

    /**
     * 世界卸载（退回主菜单/切换存档）：作废在途请求与站点排队，但不重置整轮预算。
     * <p>
     * 重新进入世界后同一个回调对象不会再被续用，而客户端重连会复用同一批站点对象：
     * 站点冷却与积压属于连接级状态，必须在这里清空，否则上次存档残留的冷却会推迟新世界的首次请求。
     * 预算则不重置——同一个回调若意外存活，它的截止时间仍应按原轮次计算。
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            ProtocolHttpBridge.invalidateAll();
            ProtocolHttpBridge.discardQueuedSends();
        }
    }
}

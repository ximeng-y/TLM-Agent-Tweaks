package com.xm2nd.tlmagenttweaks.client;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 服务端必装守卫（客户端）。
 * <p>
 * 本 mod 的网络通道按「允许缺失」注册：客户端没装也能加入装有本 mod 的服务器。
 * 反方向（客户端装了、服务端没装）在这里拦截——登录完成后查询通道的远端存在性，
 * 服务端未注册本 mod 通道（或本端没建通道）时主动断开并给出原因；
 * 单人游戏（本地集成服与客户端同 JVM，必然同时安装）跳过检查。
 */
@OnlyIn(Dist.CLIENT)
public final class ServerPresenceGate {

    private ServerPresenceGate() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Connection connection = event.getConnection();
        if (connection == null) {
            return;
        }
        if (connection.isMemoryConnection()) {
            // 单人游戏：集成服务端与客户端同 JVM，必然同时安装
            return;
        }
        SimpleChannel channel = TweakPackets.channel();
        // 本 mod 的通道以「允许缺失」注册，因此远端未注册时 isRemotePresent 为 false；
        // 单人游戏已在上面跳过
        if (channel == null || !channel.isRemotePresent(connection)) {
            TlmAgentTweaks.LOGGER.info("Disconnecting from server without {} installed", TlmAgentTweaks.MOD_ID);
            connection.disconnect(Component.translatable("disconnect.tlm_agent_tweaks.missing_server"));
        }
    }
}

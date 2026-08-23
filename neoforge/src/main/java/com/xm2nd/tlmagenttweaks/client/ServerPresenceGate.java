package com.xm2nd.tlmagenttweaks.client;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

/**
 * 服务端必装守卫（客户端）。
 * <p>
 * 本 mod 的网络通道注册为 optional：客户端没装也能加入装有本 mod 的服务器。
 * 反方向（客户端装了、服务端没装）在这里拦截——登录完成后读取协商出的
 * 通道表，若服务端未注册本 mod 的通道则主动断开并给出原因；
 * 单人游戏（本地集成服与客户端同 JVM，必然同时安装）跳过检查。
 */
@OnlyIn(Dist.CLIENT)
public final class ServerPresenceGate {

    /** 用任一服务端 bound 通道作为存在性探针 */
    private static final ResourceLocation PROBE_CHANNEL =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_compress");

    private ServerPresenceGate() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientPacketListener listener = event.getPlayer().connection;
        if (listener == null) {
            return;
        }
        Connection connection = listener.getConnection();
        if (connection.isMemoryConnection()) {
            // 单人游戏：集成服务端与客户端同 JVM，必然同时安装
            return;
        }
        var setup = ChannelAttributes.getPayloadSetup(connection);
        boolean present = setup != null && setup.getChannel(ConnectionProtocol.PLAY, PROBE_CHANNEL) != null;
        if (!present) {
            TlmAgentTweaks.LOGGER.info("Disconnecting from server without {} installed", TlmAgentTweaks.MOD_ID);
            connection.disconnect(Component.translatable("disconnect.tlm_agent_tweaks.missing_server"));
        }
    }
}

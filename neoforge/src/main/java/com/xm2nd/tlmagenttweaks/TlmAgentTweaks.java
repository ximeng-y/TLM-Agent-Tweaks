package com.xm2nd.tlmagenttweaks;

import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import com.xm2nd.tlmagenttweaks.server.ServerLifecycleHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TLM Agent Tweaks 入口：车万女仆 AI 功能增强附属。
 */
@Mod(TlmAgentTweaks.MOD_ID)
public class TlmAgentTweaks {
    public static final String MOD_ID = "tlm_agent_tweaks";
    public static final Logger LOGGER = LoggerFactory.getLogger(TlmAgentTweaks.class);

    public TlmAgentTweaks(IEventBus modEventBus) {
        // COMMON 配置（服务端权威）
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // 网络包
        modEventBus.addListener(TweakPackets::register);
        // 服务端生命周期（统计引擎加载/落盘）
        NeoForge.EVENT_BUS.register(ServerLifecycleHandler.class);
        // 流式请求生命周期（实体离开世界/停服时取消在途请求）
        NeoForge.EVENT_BUS.register(com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolStreamLifecycle.class);
        // 思考链展示的服务端状态与快照下发（只读展示，不影响请求与工具执行）
        NeoForge.EVENT_BUS.register(com.xm2nd.tlmagenttweaks.thinking.ThinkingDisplayService.class);

        // 客户端：服务端必装守卫（装了本 mod 的客户端连未安装的服务器时主动断开）
        if (FMLEnvironment.dist.isClient()) {
            // 客户端本地配置（独立的 CLIENT 配置文件，不加入 COMMON，也不同步到服务器）
            com.xm2nd.tlmagenttweaks.client.ClientConfig.register();
            NeoForge.EVENT_BUS.register(com.xm2nd.tlmagenttweaks.client.ServerPresenceGate.class);
            NeoForge.EVENT_BUS.register(
                    com.xm2nd.tlmagenttweaks.client.thinking.ThinkingDisplayClientLifecycle.class);
            NeoForge.EVENT_BUS.register(com.xm2nd.tlmagenttweaks.client.gui.SettingsNavigationController.class);
            modEventBus.addListener(com.xm2nd.tlmagenttweaks.client.gui.SelfTalkNavigationBridge::onClientSetup);
        }

        // 客户端 Cloth Config 配置界面：反射注册（未装 cloth-config 时自动跳过）
        if (FMLEnvironment.dist.isClient()) {
            registerClothConfigIfPresent();
        }
    }

    /**
     * 引用 cloth 客户端类的订阅器类不能在未装 cloth-config 时被加载，
     * 因此通过 Class.forName + 反射注册（字节码中不出现对 cloth/订阅器类的直接引用）。
     */
    private static void registerClothConfigIfPresent() {
        try {
            if (ModList.get().isLoaded("cloth_config")) {
                Class<?> clazz = Class.forName("com.xm2nd.tlmagenttweaks.client.TweaksClothConfig");
                NeoForge.EVENT_BUS.register(clazz);
            }
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Cloth Config 配置页注册失败，配置界面不可用（不影响核心功能）", e);
        }
    }
}

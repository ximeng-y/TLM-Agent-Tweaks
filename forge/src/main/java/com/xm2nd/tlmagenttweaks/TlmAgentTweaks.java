package com.xm2nd.tlmagenttweaks;

import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import com.xm2nd.tlmagenttweaks.server.ServerLifecycleHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TLM Agent Tweaks 入口：车万女仆 AI 功能增强附属（Forge 1.20.1）。
 */
@Mod(TlmAgentTweaks.MOD_ID)
public class TlmAgentTweaks {
    public static final String MOD_ID = "tlm_agent_tweaks";
    public static final Logger LOGGER = LoggerFactory.getLogger(TlmAgentTweaks.class);

    /**
     * 构造器注入 {@link FMLJavaModLoadingContext}：Forge 47.4 已把
     * {@code ModLoadingContext.get()} 标为 {@code @Deprecated(forRemoval)}，
     * 配置注册走注入进来的容器。
     */
    public TlmAgentTweaks(FMLJavaModLoadingContext context) {
        // COMMON 配置（服务端权威）
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // 网络包（Forge 1.20.1 走 SimpleChannel，由本类初始化时一次性建通道）
        TweakPackets.register();
        // 服务端生命周期（统计引擎加载/落盘）
        MinecraftForge.EVENT_BUS.register(ServerLifecycleHandler.class);
        // 流式请求生命周期（实体离开世界/停服时取消在途请求）
        MinecraftForge.EVENT_BUS.register(com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolStreamLifecycle.class);
        // 思考链展示的服务端状态与快照下发（只读展示，不影响请求与工具执行）
        MinecraftForge.EVENT_BUS.register(com.xm2nd.tlmagenttweaks.thinking.ThinkingDisplayService.class);

        // 客户端：服务端必装守卫（装了本 mod 的客户端连未安装的服务器时主动断开）
        if (FMLEnvironment.dist.isClient()) {
            // 客户端本地配置（独立的 CLIENT 配置文件，不加入 COMMON，也不同步到服务器）
            com.xm2nd.tlmagenttweaks.client.ClientConfig.register(context);
            MinecraftForge.EVENT_BUS.register(com.xm2nd.tlmagenttweaks.client.ServerPresenceGate.class);
            MinecraftForge.EVENT_BUS.register(
                    com.xm2nd.tlmagenttweaks.client.thinking.ThinkingDisplayClientLifecycle.class);
            MinecraftForge.EVENT_BUS.register(com.xm2nd.tlmagenttweaks.client.gui.SettingsNavigationController.class);
            // Self-Talk 设置导航桥：FMLClientSetupEvent 属 mod 事件总线，不是 MinecraftForge.EVENT_BUS
            context.getModEventBus().addListener(
                    com.xm2nd.tlmagenttweaks.client.gui.SelfTalkNavigationBridge::onClientSetup);
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
                MinecraftForge.EVENT_BUS.register(clazz);
            }
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Cloth Config 配置页注册失败，配置界面不可用（不影响核心功能）", e);
        }
    }
}

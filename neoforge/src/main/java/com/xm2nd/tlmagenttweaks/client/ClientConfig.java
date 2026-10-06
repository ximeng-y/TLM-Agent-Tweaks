package com.xm2nd.tlmagenttweaks.client;

import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端本地配置。
 * <p>
 * 配置项只影响本玩家的显示，不参与服务端记账，也不同步到服务器或 Self-Talk，
 * 因此使用 {@link ModConfig.Type#CLIENT}（文件为 {@code config/tlm_agent_tweaks-client.toml}）。
 * <p>
 * 本类不引用任何 Minecraft 客户端渲染类：它由入口类的客户端分支注册，但在类加载层面
 * 保持与渲染无关，避免专用服务端加载时的连带初始化问题。
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 是否在本玩家的等待气泡内滚动显示模型思考内容。 */
    public static final ModConfigSpec.BooleanValue SHOW_REASONING = BUILDER
            .comment("是否在等待气泡中滚动显示模型的思考内容（仅影响本玩家的显示，不影响工具提示与请求行为）")
            .define("thinking.showReasoning", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

    /** 在入口类的客户端分支调用：注册 CLIENT 配置，不加入 COMMON。 */
    public static void register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    /** 当前是否显示思考链。配置尚未加载时按默认值（开启）处理。 */
    public static boolean showReasoning() {
        try {
            if (SPEC.isLoaded()) {
                return SHOW_REASONING.get();
            }
        } catch (Throwable ignored) {
            // 配置不可用时按默认展示，绝不因读取失败影响渲染
        }
        return true;
    }

    /** 供设置页开关写入并立即落盘。 */
    public static void setShowReasoning(boolean value) {
        SHOW_REASONING.set(value);
        SPEC.save();
    }
}

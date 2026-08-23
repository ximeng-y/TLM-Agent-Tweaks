package com.xm2nd.tlmagenttweaks;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置（COMMON，服务端权威）。
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MANUAL_COMPRESS_THRESHOLD_K = BUILDER
            .comment("手动压缩聊天记录的阈值（单位 K token）。上次对话 token 用量达到该值时才允许玩家手动压缩，",
                    "防止反复压缩浪费 token。TLM 原生自动压缩阈值仍由 TLM 自己的配置控制，互不影响")
            .defineInRange("compress.manualThresholdK", 8, 1, 1024);

    public static final ModConfigSpec.BooleanValue STATS_ENABLED = BUILDER
            .comment("token 用量统计总开关（关闭后不再记录任何用量，已有数据保留）")
            .define("stats.enabled", true);

    public static final ModConfigSpec.IntValue STATS_ADMIN_PERMISSION_LEVEL = BUILDER
            .comment("查看 token 统计详情 / 设置模型价格所需的 OP 权限等级")
            .defineInRange("stats.adminPermissionLevel", 2, 0, 4);

    public static final ModConfigSpec.BooleanValue STATS_RECORD_SUMMARY_CALLS = BUILDER
            .comment("是否把历史压缩摘要请求也计入 token 统计（这些请求同样消耗 token）")
            .define("stats.recordSummaryCalls", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}

package com.xm2nd.tlmagenttweaks;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 模组配置（COMMON，服务端权威）。
 */
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue MANUAL_COMPRESS_THRESHOLD_K = BUILDER
            .comment("手动压缩聊天记录的阈值（单位 K token）。上次对话 token 用量达到该值时才允许玩家手动压缩，",
                    "防止反复压缩浪费 token。TLM 原生自动压缩阈值仍由 TLM 自己的配置控制，互不影响")
            .defineInRange("compress.manualThresholdK", 8, 1, 1024);

    public static final ForgeConfigSpec.BooleanValue STATS_ENABLED = BUILDER
            .comment("token 用量统计总开关（关闭后不再记录任何用量，已有数据保留）")
            .define("stats.enabled", true);

    public static final ForgeConfigSpec.IntValue STATS_ADMIN_PERMISSION_LEVEL = BUILDER
            .comment("查看 token 统计详情 / 设置模型价格所需的 OP 权限等级")
            .defineInRange("stats.adminPermissionLevel", 2, 0, 4);

    public static final ForgeConfigSpec.BooleanValue STATS_RECORD_SUMMARY_CALLS = BUILDER
            .comment("是否把历史压缩摘要请求也计入 token 统计（这些请求同样消耗 token）")
            .define("stats.recordSummaryCalls", true);

    public static final ForgeConfigSpec.BooleanValue CHAT_GLOBAL_VISIBLE = BUILDER
            .comment("玩家与女仆普通对话内容全局可见总开关（服务端权威）。开启且玩家未勾选「我的对话设为私密」时，",
                    "玩家原文与女仆最终文字回复会以灰色系统消息广播给全服其他在线玩家（跨维度，无距离筛选）；",
                    "关闭或玩家勾选私密时不新增广播，TLM 原有的本人/主人回显与气泡不受影响")
            .define("chat.globalVisible", true);

    public static final ForgeConfigSpec.BooleanValue RETRY_ENABLED = BUILDER
            .comment("请求失败自动重试总开关（服务端权威）。开启后：429 与 500/502/503/504 最多额外重试 2 次，",
                    "每次固定等待 5 秒（忽略 Retry-After，不做指数退避）；站点冷却期间后到的请求会排队延后发送。",
                    "关闭后不做任何重试与站点冷却，但流式断流改用非流式重发的行为不受本开关控制")
            .define("retry.enabled", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}

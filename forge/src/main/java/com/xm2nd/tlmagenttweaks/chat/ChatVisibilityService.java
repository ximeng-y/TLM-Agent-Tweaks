package com.xm2nd.tlmagenttweaks.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.UUID;

/**
 * 「玩家与女仆普通对话全局可见」的权威状态与统一分发服务。
 * <p>
 * 职责：读取/保存玩家私密偏好、管理员全局开关（含配置文件重载收口）、
 * 玩家原文与女仆最终回复的全服文字广播、登录与开启时的黄字通知。
 * 所有玩家枚举、世界数据读写、配置应用与系统消息都在服务端主线程执行；
 * 可能来自 HTTP 线程的回调入口会交回主线程。
 * <p>
 * 公开判定只有两项：当前全局开启，且来源玩家未勾选私密。
 * 广播与通知使用原生系统消息，接收方无需安装本 mod。
 */
public final class ChatVisibilityService {

    /** 开启全局可见与玩家登录时发送的黄字通知原文（不使用翻译 key，未装本 mod 的客户端也能完整显示）。 */
    private static final String GLOBAL_NOTICE =
            "当前服务器已开启玩家与女仆聊天内容全局可见，若不希望自己与女仆的聊天内容全局可见，"
                    + "可对女仆按 T 后点击🧩进入配置页面进行关闭";

    private static volatile MinecraftServer activeServer;

    /** 运行期「上次已处理的全局值」基线：仅用于识别 false → true 的一次性开启通知。 */
    private static volatile Boolean lastAppliedGlobal;

    private ChatVisibilityService() {
    }

    // ---------- 生命周期 ----------

    public static void onServerStarted(MinecraftServer server) {
        activeServer = server;
        // 基线取当前已加载的配置值：启动初始化不算一次关闭到开启
        lastAppliedGlobal = Config.CHAT_GLOBAL_VISIBLE.get();
    }

    public static void onServerStopping() {
        activeServer = null;
        lastAppliedGlobal = null;
    }

    /**
     * 配置重载收口：文件观察线程回调，状态比较与通知任务交回服务器主线程。
     * 管理员保存引发的二次重载会看到相同值，不重复发送通知。
     */
    public static void onModConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != Config.SPEC) {
            return;
        }
        MinecraftServer server = activeServer;
        if (server == null) {
            return;
        }
        server.submit(() -> applyGlobalState(Config.CHAT_GLOBAL_VISIBLE.get()));
    }

    // ---------- 状态读取与保存 ----------

    public static boolean isGlobalVisible() {
        return Config.CHAT_GLOBAL_VISIBLE.get();
    }

    public static boolean isPrivate(MinecraftServer server, UUID playerId) {
        return ChatPrivacySavedData.get(server).isPrivate(playerId);
    }

    /** 管理员全局保存权限固定为原生 OP 等级 2，不复用统计查看权限配置。 */
    public static boolean canManageGlobal(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    public static void savePrivate(ServerPlayer player, boolean value) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ChatPrivacySavedData.get(server).setPrivate(player.getUUID(), value);
    }

    /**
     * 管理员保存全局开关：真实服务端执行配置写入与落盘，随后立即应用状态。
     * 之后文件观察器的重载事件看见相同值，不会再次发送通知。
     */
    public static void saveGlobalFromAdmin(ServerPlayer player, boolean value) {
        if (!canManageGlobal(player)) {
            return;
        }
        if (player.getServer() == null) {
            return;
        }
        Config.CHAT_GLOBAL_VISIBLE.set(value);
        Config.SPEC.save();
        applyGlobalState(value);
    }

    /**
     * 唯一的全局状态应用口：管理员保存与配置文件重载都走这里。
     * 先推进基线，再仅在真实 false → true 时向在线未勾选私密的玩家各发一次通知。
     */
    private static void applyGlobalState(boolean newValue) {
        MinecraftServer server = activeServer;
        if (server == null) {
            return;
        }
        Boolean baseline = lastAppliedGlobal;
        lastAppliedGlobal = newValue;
        if (baseline == null || baseline || !newValue) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isPrivate(server, player.getUUID())) {
                continue;
            }
            sendGlobalNotice(player);
        }
    }

    // ---------- 通知 ----------

    /** 玩家每次进入服务器世界：全局开启且未勾选私密时提醒一次。死亡与维度切换不提醒。 */
    public static void onPlayerLoggedIn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !isGlobalVisible() || isPrivate(server, player.getUUID())) {
            return;
        }
        sendGlobalNotice(player);
    }

    private static void sendGlobalNotice(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(GLOBAL_NOTICE).withStyle(ChatFormatting.YELLOW));
    }

    // ---------- 广播 ----------

    /**
     * 正式普通对话派发时广播玩家原文（在请求真正发出前、上下文拼装之外取原始 message）。
     * 排除来源玩家：TLM 客户端已在本地回显本人消息。
     */
    public static void onNormalChatDispatch(EntityMaid maid, String rawMessage, UUID origin) {
        if (origin == null) {
            return;
        }
        runOnServerThread(maid, () -> {
            MinecraftServer server = maidServer(maid);
            if (server == null || !isGlobalVisible() || isPrivate(server, origin)) {
                return;
            }
            ServerPlayer source = server.getPlayerList().getPlayer(origin);
            if (source == null) {
                return;
            }
            String text = "<" + source.getScoreboardName() + "> " + rawMessage;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getUUID().equals(origin)) {
                    continue;
                }
                player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
            }
        });
    }

    /**
     * 最终回复成功时广播女仆最终文字。排除当前主人：TLM 原生会回显给主人一次。
     * 广播在最终 LLM 成功逻辑完成后进行，不等 TTS 合成；TTS 成功或失败都不会再触发。
     */
    public static void onNormalReplySuccess(EntityMaid maid, UUID origin, String chatText) {
        if (chatText == null || chatText.isBlank()) {
            return;
        }
        runOnServerThread(maid, () -> {
            MinecraftServer server = maidServer(maid);
            if (server == null || !isGlobalVisible() || isPrivate(server, origin)) {
                return;
            }
            String text = "<" + maid.getName().getString() + "> " + chatText;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (maid.getOwner() == player) {
                    continue;
                }
                player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
            }
        });
    }

    private static MinecraftServer maidServer(EntityMaid maid) {
        if (maid.level() instanceof ServerLevel level) {
            return level.getServer();
        }
        return null;
    }

    /** 服务方法可能在 HTTP 线程被调用，统一交回服务器主线程执行。 */
    private static void runOnServerThread(EntityMaid maid, Runnable task) {
        if (maid.level() instanceof ServerLevel level) {
            MinecraftServer server = level.getServer();
            if (server.isSameThread()) {
                task.run();
            } else {
                server.submit(task);
            }
        }
    }
}

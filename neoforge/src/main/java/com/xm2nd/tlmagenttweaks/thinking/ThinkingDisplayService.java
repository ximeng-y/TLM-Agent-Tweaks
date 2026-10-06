package com.xm2nd.tlmagenttweaks.thinking;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.WaitingChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.stream.StreamEventType;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLineBuffer;
import com.xm2nd.tlmagenttweaks.network.ThinkingDisplayPayload;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolHttpBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 思考链展示的服务端状态机：本功能的服务端展示状态唯一管理者。
 * <p>
 * 展示会话按**实际回调对象**区分（不是按女仆 UUID 合并）：同一女仆的不同对话、
 * 工具派生的子流程互不干扰；同一回调的工具续接会为每一轮请求新建
 * {@link Handle}，因此旧请求的迟到事件不会影响新请求。
 * <p>
 * 线程模型：HTTP 线程只投递事件与请求级标记；阶段、文本缓冲、修订号、女仆与气泡查询
 * 一律落在服务端主线程（{@link LLMCallback#runOnServerThread}）。唯一的跨线程写入是
 * {@link Handle#reasoningSuppressed()}，它属于请求元数据而非展示状态，必须在
 * {@code onSendStart} 之前对调度线程可见（见 {@link #onStreamEvent} 与
 * {@link #onSendStart}）。
 * <p>
 * 只为确实存在 {@link WaitingChatBubbleData} 的回调展示：摘要、规划与子流程回调
 * 不会因此获得新气泡——本服务从始至终不创建、不删除、不替换任何服务端气泡。
 * <p>
 * 展示失败一律隔离，绝不中断或推迟原请求、工具执行与最终回答。
 */
public final class ThinkingDisplayService {

    /** 当前有效的请求句柄：键随回调被回收，值不反向持有回调，避免弱引用登记被值拖住。 */
    private static final Map<LLMCallback, Handle> CURRENT =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 女仆 UUID → 正在跟踪该女仆的玩家 UUID 集合。 */
    private static final Map<UUID, Set<UUID>> OBSERVERS = new ConcurrentHashMap<>();
    private static final AtomicLong SESSION_IDS = new AtomicLong();

    private ThinkingDisplayService() {
    }

    /** 一次**请求**的展示句柄：每次发送新建，旧句柄的迟到事件一律作废。 */
    public static final class Handle {
        private final long displaySessionId;
        /**
         * 本请求是否已改用非流式。由 HTTP/调度线程写入、读取，因此必须是原子类型：
         * 它的可见性不能依赖主线程任务队列的执行时机。
         */
        private final AtomicBoolean reasoningSuppressed = new AtomicBoolean();
        /** 单行有界缓冲区；只在主线程读写。 */
        private final ThinkingLineBuffer buffer = new ThinkingLineBuffer();
        /** 终态清理可能来自任意线程，故为 volatile。 */
        private volatile boolean ended;

        private byte phase = ThinkingDisplayPayload.PHASE_THINKING;
        private long revision;
        private long bubbleId = -1L;
        private boolean dirty;
        private UUID maidId;
        private String dimension;

        private Handle(long displaySessionId) {
            this.displaySessionId = displaySessionId;
        }

        public boolean reasoningSuppressed() {
            return this.reasoningSuppressed.get();
        }

        private void suppressReasoning() {
            this.reasoningSuppressed.set(true);
        }
    }

    // ---------- 请求生命周期 ----------

    /**
     * 新一轮请求：建立新句柄并清空旧思考回到 {@code THINKING}。
     * 返回 null 表示本次调用不参与展示（无回调）。
     */
    public static Handle beginRequest(LLMCallback callback) {
        try {
            if (callback == null) {
                return null;
            }
            Handle handle = new Handle(SESSION_IDS.incrementAndGet());
            CURRENT.put(callback, handle);
            submit(callback, handle, () -> {
                if (!isCurrent(callback, handle) || !hasWaitingBubble(callback)) {
                    // 无等待气泡的摘要/规划/子流程：不展示，也不影响原请求。
                    return;
                }
                handle.buffer.clear();
                handle.phase = ThinkingDisplayPayload.PHASE_THINKING;
                handle.revision++;
                handle.dirty = true;
            });
            return handle;
        } catch (Throwable throwable) {
            debug("思考展示会话建立失败（已忽略）", throwable);
            return null;
        }
    }

    /**
     * 整轮终态清理：删除该回调的展示状态并让客户端丢弃对应气泡的替代绘制。
     * 幂等；成功、失败、超时、主动 complete/cancel 与生命周期取消都走这里。
     */
    public static void endRequest(LLMCallback callback) {
        try {
            if (callback == null) {
                return;
            }
            Handle handle = CURRENT.remove(callback);
            if (handle == null) {
                return;
            }
            handle.ended = true;
            submit(callback, handle, () -> sendClear(callback, handle));
        } catch (Throwable throwable) {
            debug("思考展示终态清理失败（已忽略）", throwable);
        }
    }

    /** 停服/世界卸载：直接丢弃登记表，不依赖网络事件作为唯一清理来源。 */
    public static void clearAll() {
        CURRENT.clear();
        OBSERVERS.clear();
    }

    // ---------- 阶段切换 ----------

    /**
     * 只读流式事件接入点。
     * <p>
     * {@code COMPLETED} 只代表单次 HTTP 成功，后面还可能执行工具，因此**不得**结束展示会话；
     * {@code TEXT_DELTA} 不变成流式聊天正文；signature、encrypted reasoning 等协议私有
     * 元数据不出现在事件文本里，也就不会被展示。
     */
    public static void onStreamEvent(LLMCallback callback, Handle handle, StreamEventType type, String text) {
        if (handle == null || type == null) {
            return;
        }
        try {
            switch (type) {
                case REASONING_DELTA -> {
                    if (text == null || text.isEmpty()) {
                        return;
                    }
                    submit(callback, handle, () -> {
                        if (!isCurrent(callback, handle) || handle.reasoningSuppressed()) {
                            // 已回退非流式：不再播放中断流残片。
                            return;
                        }
                        handle.buffer.append(text);
                        handle.phase = ThinkingDisplayPayload.PHASE_THINKING;
                        handle.revision++;
                        handle.dirty = true;
                    });
                }
                case FALLBACK -> {
                    // 标记必须在调度线程可见：随后的 onSendStart 要据此拒绝恢复思考播放。
                    handle.suppressReasoning();
                    submit(callback, handle, () -> {
                        if (!isCurrent(callback, handle)) {
                            return;
                        }
                        handle.buffer.clear();
                        handle.phase = ThinkingDisplayPayload.PHASE_NATIVE;
                        handle.revision++;
                        handle.dirty = true;
                    });
                }
                default -> {
                    // STARTED / TEXT_DELTA / COMPLETED / FAILED / CANCELLED 都不改变展示阶段：
                    // 终态清理由 endRequest 统一负责。
                }
            }
        } catch (Throwable throwable) {
            debug("思考展示事件处理失败（已忽略）", throwable);
        }
    }

    /**
     * 等待提示（重试/排队/非流式回退）实际显示：交回原生气泡显示，不再替换副文本。
     */
    public static void onNativeStage(LLMCallback callback, Handle handle, boolean fallback) {
        if (handle == null) {
            return;
        }
        try {
            if (fallback) {
                handle.suppressReasoning();
            }
            submit(callback, handle, () -> {
                if (!isCurrent(callback, handle)) {
                    return;
                }
                handle.buffer.clear();
                handle.phase = ThinkingDisplayPayload.PHASE_NATIVE;
                handle.revision++;
                handle.dirty = true;
            });
        } catch (Throwable throwable) {
            debug("思考展示阶段切换失败（已忽略）", throwable);
        }
    }

    /**
     * 真正发出时的阶段恢复：普通流式重试从空窗口重新开始；
     * 已标记非流式回退的请求保持原生提示，不恢复播放残片。
     */
    public static void onSendStart(LLMCallback callback, Handle handle, boolean firstAttempt) {
        if (handle == null || firstAttempt || handle.reasoningSuppressed()) {
            return;
        }
        try {
            submit(callback, handle, () -> {
                if (!isCurrent(callback, handle) || handle.reasoningSuppressed()) {
                    return;
                }
                handle.buffer.clear();
                handle.phase = ThinkingDisplayPayload.PHASE_THINKING;
                handle.revision++;
                handle.dirty = true;
            });
        } catch (Throwable throwable) {
            debug("思考展示阶段恢复失败（已忽略）", throwable);
        }
    }

    /**
     * 真实工具执行开始（TLM {@code onSingleCall} 调用 {@code ITool.onCallAsync} 之前）。
     * <p>
     * 工具执行期间不显示思考内容：副文本交还给 TLM 自己的工具提示。
     * 这里只切阶段，不改写气泡、不参与工具调用本身。
     */
    public static void onToolExecutionStart(LLMCallback callback) {
        Handle handle = (callback == null) ? null : CURRENT.get(callback);
        if (handle == null) {
            return;
        }
        handle.suppressReasoning();
        try {
            submit(callback, handle, () -> {
                if (!isCurrent(callback, handle)) {
                    return;
                }
                handle.buffer.clear();
                handle.phase = ThinkingDisplayPayload.PHASE_NATIVE;
                handle.revision++;
                handle.dirty = true;
            });
        } catch (Throwable throwable) {
            debug("思考展示工具阶段切换失败（已忽略）", throwable);
        }
    }

    // ---------- 每 tick 快照 ----------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<Map.Entry<LLMCallback, Handle>> snapshot;
        synchronized (CURRENT) {
            if (CURRENT.isEmpty()) {
                return;
            }
            snapshot = List.copyOf(CURRENT.entrySet());
        }
        for (Map.Entry<LLMCallback, Handle> entry : snapshot) {
            try {
                flush(entry.getKey(), entry.getValue());
            } catch (Throwable throwable) {
                debug("思考展示快照发送失败（已忽略）", throwable);
            }
        }
    }

    /** 每个 tick 最多为每个发生变化的展示会话发送一次快照；同 tick 的增量已合并进缓冲。 */
    private static void flush(LLMCallback callback, Handle handle) {
        if (handle.ended || !handle.dirty || !isCurrent(callback, handle)) {
            return;
        }
        if (ProtocolHttpBridge.isInactive(callback)) {
            return;
        }
        EntityMaid maid = callback.getMaid();
        if (maid == null || !(maid.level() instanceof net.minecraft.server.level.ServerLevel)) {
            return;
        }
        // 每次刷新前重新解析气泡绑定：工具提示与重试都会换成新的等待气泡 ID。
        long bubbleId = callback.getWaitingChatBubbleId();
        if (!isWaitingBubble(maid, bubbleId)) {
            return;
        }
        handle.maidId = maid.getUUID();
        handle.dimension = maid.level().dimension().location().toString();
        handle.bubbleId = bubbleId;
        handle.dirty = false;
        sendSnapshot(handle);
    }

    private static void sendSnapshot(Handle handle) {
        boolean thinking = handle.phase == ThinkingDisplayPayload.PHASE_THINKING;
        String line = thinking ? handle.buffer.line() : "";
        sendToObservers(handle.maidId, new ThinkingDisplayPayload(handle.dimension, handle.maidId,
                handle.displaySessionId, handle.revision, handle.bubbleId, handle.phase, line));
    }

    private static void sendClear(LLMCallback callback, Handle handle) {
        if (handle.maidId == null || handle.dimension == null) {
            // 从未发布过快照：客户端没有状态需要清理。
            return;
        }
        sendToObservers(handle.maidId, new ThinkingDisplayPayload(handle.dimension, handle.maidId,
                handle.displaySessionId, handle.revision, handle.bubbleId,
                ThinkingDisplayPayload.PHASE_CLEAR, ""));
    }

    // ---------- 观察玩家跟踪 ----------

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof EntityMaid maid)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID maidId = maid.getUUID();
        observersOf(maidId).add(player.getUUID());
        // 开始跟踪时补发当前快照，避免玩家中途进入可见范围时看到陈旧或不一致的窗口。
        for (Handle handle : currentHandlesOf(maidId)) {
            sendSnapshotTo(handle, player);
        }
    }

    @SubscribeEvent
    public static void onStopTracking(PlayerEvent.StopTracking event) {
        if (!(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        Set<UUID> observers = OBSERVERS.get(maid.getUUID());
        if (observers != null) {
            observers.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        for (Set<UUID> observers : OBSERVERS.values()) {
            observers.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clearAll();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            clearAll();
        }
    }

    private static Set<UUID> observersOf(UUID maidId) {
        return OBSERVERS.computeIfAbsent(maidId, key -> ConcurrentHashMap.newKeySet());
    }

    private static List<Handle> currentHandlesOf(UUID maidId) {
        List<Handle> result = new java.util.ArrayList<>(2);
        synchronized (CURRENT) {
            for (Handle handle : CURRENT.values()) {
                if (!handle.ended && maidId.equals(handle.maidId) && handle.dirty) {
                    result.add(handle);
                }
            }
        }
        return result;
    }

    // ---------- 发送 ----------

    /**
     * 只发给正在跟踪该女仆、且**协商出了本 payload** 的玩家：
     * 未安装本 mod 或不支持新通道的客户端不接收任何包，进服行为不变。
     */
    private static void sendToObservers(UUID maidId, ThinkingDisplayPayload payload) {
        if (maidId == null) {
            return;
        }
        Set<UUID> observers = OBSERVERS.get(maidId);
        if (observers == null || observers.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (UUID playerId : List.copyOf(observers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                observers.remove(playerId);
                continue;
            }
            sendTo(player, payload);
        }
    }

    private static void sendSnapshotTo(Handle handle, ServerPlayer player) {
        boolean thinking = handle.phase == ThinkingDisplayPayload.PHASE_THINKING;
        sendTo(player, new ThinkingDisplayPayload(handle.dimension, handle.maidId,
                handle.displaySessionId, handle.revision, handle.bubbleId, handle.phase,
                thinking ? handle.buffer.line() : ""));
    }

    private static void sendTo(ServerPlayer player, ThinkingDisplayPayload payload) {
        try {
            if (player.connection == null || !player.connection.hasChannel(ThinkingDisplayPayload.TYPE)) {
                return;
            }
            PacketDistributor.sendToPlayer(player, payload);
        } catch (Throwable throwable) {
            debug("思考展示快照发送失败（已忽略）", throwable);
        }
    }

    // ---------- 内部工具 ----------

    private static void submit(LLMCallback callback, Handle handle, Runnable task) {
        if (callback == null || handle == null) {
            return;
        }
        try {
            callback.runOnServerThread(() -> {
                try {
                    if (!handle.ended) {
                        task.run();
                    }
                } catch (Throwable throwable) {
                    debug("思考展示主线程任务失败（已忽略）", throwable);
                }
            });
        } catch (Throwable throwable) {
            debug("思考展示任务调度失败（已忽略）", throwable);
        }
    }

    private static boolean isCurrent(LLMCallback callback, Handle handle) {
        return !handle.ended && CURRENT.get(callback) == handle;
    }

    private static boolean hasWaitingBubble(LLMCallback callback) {
        try {
            EntityMaid maid = callback.getMaid();
            return maid != null && isWaitingBubble(maid, callback.getWaitingChatBubbleId());
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static boolean isWaitingBubble(EntityMaid maid, long bubbleId) {
        try {
            return maid.getChatBubbleManager().getChatBubble(bubbleId) instanceof WaitingChatBubbleData;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static void debug(String message, Throwable throwable) {
        TlmAgentTweaks.LOGGER.debug(message, throwable);
    }
}

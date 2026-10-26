package com.xm2nd.tlmagenttweaks.thinking;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.WaitingChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.stream.StreamEventType;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLineBuffer;
import com.xm2nd.tlmagenttweaks.network.ThinkingDisplayPayload;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolHttpBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

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
    /** 女仆 UUID → 正在跟踪该女仆的玩家 UUID 集合；集合清空时键随之移除，不滞留到停服。 */
    private static final Map<UUID, Set<UUID>> OBSERVERS = new ConcurrentHashMap<>();
    /**
     * 气泡归属：一个等待气泡同时只由一个展示会话负责。
     * <p>
     * 工具续接会用同一个回调发起下一轮请求、复用同一个气泡，知识型工具
     * （{@code GroundedAnswerCallback}）更是直接拿父回调的气泡发起子请求。若允许多个会话
     * 争抢同一气泡，客户端就得靠遍历顺序在「旧的工具阶段」与「新的思考阶段」之间二选一，
     * 结果不确定。这里将气泡归属绑定到回调，而不是单次请求：同一回调换轮次时继承归属，
     * 其余回调让位，直到持有者整轮结束或换到另一个气泡。
     * <p>
     * 定权规则与认领时机都必须与遍历顺序无关：以「谁先认领」定胜负，等价于让每 tick 遍历
     * 登记表的顺序决定父子谁持有气泡。因此父请求在**工具执行之前**同步认领新气泡
     * （见 {@link #onToolExecutionStart}），工具续接只清空旧快照，不释放回调的归属。
     */
    private static final Map<BubbleKey, Ownership> OWNERS = new ConcurrentHashMap<>();
    private static final AtomicLong SESSION_IDS = new AtomicLong();

    private ThinkingDisplayService() {
    }

    /** 气泡的唯一标识：同一气泡在不同维度/女仆下互不相干。 */
    private record BubbleKey(String dimension, UUID maidId, long bubbleId) {
    }

    /** 同一回调跨请求复用的归属令牌；不反向持有回调，保留 CURRENT 的弱引用语义。 */
    private static final class Ownership {
        /** 仅整轮终态置位，单次请求退休不会释放归属。 */
        private volatile boolean ended;
        /** 当前负责的气泡键，只在服务端主线程读写。 */
        private BubbleKey bubbleKey;
    }

    /** 一次**请求**的展示句柄：每次发送新建，旧句柄的迟到事件一律作废。 */
    public static final class Handle {
        private final long displaySessionId;
        private final Ownership ownership;
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
        /** 是否已经向客户端发布过该会话的快照：决定终态是否需要发 CLEAR。 */
        private boolean published;

        private Handle(long displaySessionId, Ownership ownership) {
            this.displaySessionId = displaySessionId;
            this.ownership = ownership;
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
     * <p>
     * 同一回调发起新一轮请求（工具批次续接会复用同一个回调）时，上一轮句柄在这里
     * **主动退休**：旧句柄失效并通知客户端撤下它的显示，但气泡归属由新句柄继承。
     * 子请求可能在工具等待超时后仍未结束，不能在换轮次时给它留下抢占父气泡的空档。
     */
    public static Handle beginRequest(LLMCallback callback) {
        try {
            if (callback == null) {
                return null;
            }
            Handle handle;
            Handle previous;
            synchronized (CURRENT) {
                previous = CURRENT.get(callback);
                Ownership ownership = previous == null ? new Ownership() : previous.ownership;
                handle = new Handle(SESSION_IDS.incrementAndGet(), ownership);
                CURRENT.put(callback, handle);
                if (previous != null) {
                    previous.ended = true;
                }
            }
            if (previous != null) {
                // 退休通知与下面的新阶段任务都排在同一条主线程队列上，顺序即语义：
                // 客户端先收到旧会话的 CLEAR，再收到新会话的 THINKING。
                submitForced(callback, () -> sendClear(previous));
            }
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
     * <p>
     * 清理任务必须绕过 {@link #submit} 的 {@code ended} 守卫——本方法刚把
     * {@code ended} 置位，若走同一条守卫则 CLEAR 永远不会执行，客户端只能等到
     * 别的条件才撤下旧思考（启用 TTS 时 TLM 会长时间保留同一个等待气泡）。
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
            handle.ownership.ended = true;
            submitForced(callback, () -> {
                releaseOwnership(handle.ownership);
                sendClear(handle);
            });
        } catch (Throwable throwable) {
            debug("思考展示终态清理失败（已忽略）", throwable);
        }
    }

    /** 停服/世界卸载：直接丢弃登记表，不依赖网络事件作为唯一清理来源。 */
    public static void clearAll() {
        CURRENT.clear();
        OBSERVERS.clear();
        OWNERS.clear();
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
     * <p>
     * 本方法同时承担**父子归属定序**：TLM 在调用 {@code onCallAsync} 之前已经
     * {@code refreshWaitingChatBubble} 换出新气泡 ID，而知识型工具（{@code GroundedAnswerCallback}、
     * {@code QueryMinecraftWikiTool}）会在 {@code onCallAsync} 内部用该气泡创建子请求。
     * 若把归属留到每 tick 的批量发送再定，父、子谁先被 {@link WeakHashMap} 遍历到就成了
     * 随机结果：子请求先被遍历时会占住气泡，工具提示反而被它的思考替换。
     * 因此这里在**创建任何子请求之前**、于服务端主线程同步完成归属绑定。
     * <p>
     * 调用点必在主线程：TLM 的 {@code refreshWaitingChatBubble} 强制要求主线程，而它就在本注入
     * 之前执行；因此这里读写的展示状态与该线程上的 tick 发送处于同一线程，不引入新的竞态。
     * 绑定的发布仍由 tick 负责，保持「先 CLEAR 后新状态」的包序语义不变。
     */
    public static void onToolExecutionStart(LLMCallback callback) {
        Handle handle = (callback == null) ? null : CURRENT.get(callback);
        if (handle == null) {
            return;
        }
        handle.suppressReasoning();
        try {
            if (isCurrent(callback, handle)) {
                // 同步认领刚刷新的气泡；后续子请求在同一调用栈内创建时只能让位。
                resolveBinding(callback, handle);
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
            debug("思考展示工具阶段切换失败（已忽略）", throwable);
        }
    }

    // ---------- 每 tick 快照 ----------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
        if (!resolveBinding(callback, handle)) {
            return;
        }
        handle.dirty = false;
        sendSnapshot(handle);
    }

    /**
     * 解析并锁定本次发布的目标：女仆、维度与等待气泡 ID，同时定下气泡归属。
     * <p>
     * 每次发布前都重新解析，因为工具提示与重试都会把等待气泡换成新的 ID。
     * 若该气泡已由别的展示会话负责（工具子请求复用父气泡、旧会话尚未退休），
     * 本会话让位：不发布，也就不会出现两个会话争抢同一气泡的显示。
     *
     * @return 是否可以发布
     */
    private static boolean resolveBinding(LLMCallback callback, Handle handle) {
        if (ProtocolHttpBridge.isInactive(callback)) {
            return false;
        }
        EntityMaid maid = callback.getMaid();
        if (maid == null || !(maid.level() instanceof net.minecraft.server.level.ServerLevel)) {
            return false;
        }
        long bubbleId = callback.getWaitingChatBubbleId();
        if (!isWaitingBubble(maid, bubbleId)) {
            return false;
        }
        UUID maidId = maid.getUUID();
        String dimension = maid.level().dimension().location().toString();
        BubbleKey key = new BubbleKey(dimension, maidId, bubbleId);
        Ownership ownership = handle.ownership;
        BubbleKey previousKey = ownership.bubbleKey;
        if (key.equals(previousKey)) {
            // 已持有该气泡：确认归属仍在。停服/卸载会清空归属表，这里允许重新认领，
            // 否则该会话会永久卡在「有绑定但无归属」而不再发布。
            if (claimBubble(key, ownership)) {
                applyBinding(handle, maidId, dimension, bubbleId);
                return true;
            }
            return false;
        }
        if (!claimBubble(key, ownership)) {
            // 该气泡已由其他**存活**的展示会话负责：让位，旧归属保持不变以免误撤。
            return false;
        }
        if (previousKey != null) {
            // 换气泡：撤销旧气泡的归属，避免旧键一直被本会话占着。
            OWNERS.remove(previousKey, ownership);
        }
        ownership.bubbleKey = key;
        applyBinding(handle, maidId, dimension, bubbleId);
        return true;
    }

    /**
     * 认领气泡归属：现有存活回调保留归属，不按单次请求的展示会话 ID 比较优先级。
     * <p>
     * 父回调在工具执行前认领，同一回调后续请求复用令牌；即使子请求尚未结束而父请求已经
     * 开始下一轮，也不会发生归属转移。单次请求退休与回调整轮终态是两个独立的生命周期。
     *
     * @return 本会话是否持有该气泡的归属
     */
    private static boolean claimBubble(BubbleKey key, Ownership ownership) {
        while (true) {
            Ownership current = OWNERS.get(key);
            if (current == ownership) {
                return true;
            }
            if (current == null) {
                if (OWNERS.putIfAbsent(key, ownership) == null) {
                    return true;
                }
                continue;
            }
            if (!current.ended) {
                // 其他回调仍持有该气泡：本回调让位。
                return false;
            }
            // 原回调已进入终态：用 CAS 接管，避免覆盖别的存活归属。
            if (OWNERS.replace(key, current, ownership)) {
                return true;
            }
        }
    }

    private static void applyBinding(Handle handle, UUID maidId, String dimension, long bubbleId) {
        handle.maidId = maidId;
        handle.dimension = dimension;
        handle.bubbleId = bubbleId;
    }

    /** 整轮终态撤销回调的气泡归属；单次请求退休只清理快照。 */
    private static void releaseOwnership(Ownership ownership) {
        BubbleKey key = ownership.bubbleKey;
        if (key != null) {
            OWNERS.remove(key, ownership);
            ownership.bubbleKey = null;
        }
    }

    private static void sendSnapshot(Handle handle) {
        boolean thinking = handle.phase == ThinkingDisplayPayload.PHASE_THINKING;
        String line = thinking ? handle.buffer.line() : "";
        handle.published = true;
        sendToObservers(handle.maidId, new ThinkingDisplayPayload(handle.dimension, handle.maidId,
                handle.displaySessionId, handle.revision, handle.bubbleId, handle.phase, line));
    }

    private static void sendClear(Handle handle) {
        if (!handle.published || handle.maidId == null || handle.dimension == null) {
            // 从未发布过快照：客户端没有状态需要清理。
            return;
        }
        handle.published = false;
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
        // 开始跟踪时补发现有的有效快照，避免玩家中途进入可见范围时看不到当前思考。
        // 不能要求 dirty：常规发送已经把它清掉，这里要补的是「已发布且仍然有效」的状态。
        List<Map.Entry<LLMCallback, Handle>> snapshot;
        synchronized (CURRENT) {
            if (CURRENT.isEmpty()) {
                return;
            }
            snapshot = List.copyOf(CURRENT.entrySet());
        }
        for (Map.Entry<LLMCallback, Handle> entry : snapshot) {
            Handle handle = entry.getValue();
            BubbleKey key = handle.ownership.bubbleKey;
            if (handle.ended || key == null || OWNERS.get(key) != handle.ownership
                    || !maidId.equals(handle.maidId)) {
                continue;
            }
            sendSnapshotTo(handle, player);
        }
    }

    @SubscribeEvent
    public static void onStopTracking(PlayerEvent.StopTracking event) {
        if (!(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        UUID maidId = maid.getUUID();
        Set<UUID> observers = OBSERVERS.get(maidId);
        if (observers != null) {
            removeObserver(maidId, observers, event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        for (Map.Entry<UUID, Set<UUID>> entry : OBSERVERS.entrySet()) {
            removeObserver(entry.getKey(), entry.getValue(), playerId);
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

    /**
     * 移除一个观察玩家；集合因此清空时连同女仆键一并移除，不把空集合滞留到停服/世界卸载。
     * 删键在 compute 内按「同一实例且仍为空」判定：与 {@link #onStartTracking} 的
     * computeIfAbsent 重建竞态时不会误删刚建好的新集合。
     */
    private static void removeObserver(UUID maidId, Set<UUID> observers, UUID playerId) {
        observers.remove(playerId);
        if (observers.isEmpty()) {
            OBSERVERS.compute(maidId, (key, current) ->
                    current == observers && observers.isEmpty() ? null : current);
        }
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
                removeObserver(maidId, observers, playerId);
                continue;
            }
            sendTo(player, payload);
        }
    }

    private static void sendSnapshotTo(Handle handle, ServerPlayer player) {
        boolean thinking = handle.phase == ThinkingDisplayPayload.PHASE_THINKING;
        handle.published = true;
        sendTo(player, new ThinkingDisplayPayload(handle.dimension, handle.maidId,
                handle.displaySessionId, handle.revision, handle.bubbleId, handle.phase,
                thinking ? handle.buffer.line() : ""));
    }

    private static void sendTo(ServerPlayer player, ThinkingDisplayPayload payload) {
        try {
            if (player.connection == null || TweakPackets.channel() == null
                    || !TweakPackets.channel().isRemotePresent(player.connection.connection)) {
                return;
            }
            TweakPackets.sendToPlayer(player, payload);
        } catch (Throwable throwable) {
            debug("思考展示快照发送失败（已忽略）", throwable);
        }
    }

    // ---------- 内部工具 ----------

    private static void submit(LLMCallback callback, Handle handle, Runnable task) {
        if (callback == null || handle == null) {
            return;
        }
        submitForced(callback, () -> {
            if (!handle.ended) {
                task.run();
            }
        });
    }

    /**
     * 不受 {@code ended} 守卫约束的主线程调度：仅供**终态清理**使用。
     * <p>
     * {@link #endRequest} 与 {@link #beginRequest} 都会先把旧句柄置为 ended，再靠这里
     * 把 CLEAR 发出去；若走 {@link #submit} 则任务会被自己的守卫拦掉，客户端永远收不到清理。
     */
    private static void submitForced(LLMCallback callback, Runnable task) {
        if (callback == null) {
            return;
        }
        try {
            callback.runOnServerThread(() -> {
                try {
                    task.run();
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

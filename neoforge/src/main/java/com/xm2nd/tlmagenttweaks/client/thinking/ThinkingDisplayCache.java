package com.xm2nd.tlmagenttweaks.client.thinking;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.network.ThinkingDisplayPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端思考链展示缓存。
 * <p>
 * 服务端发来的是**完整快照**，这里只做整体覆盖，不累加网络文本：因此换行、阶段切换、
 * 玩家中途进入可见范围都不会产生文本错接。
 * <p>
 * 按「维度 + 女仆 + 展示会话」区分状态；新修订覆盖旧修订，旧会话的快照不再被考虑。
 * 快照可能先于原生的实体/气泡同步到达，此时把它暂存最多
 * {@link #PENDING_TIMEOUT_TICKS} 个客户端 tick，仍未匹配到气泡则丢弃（后续快照可重建）。
 * <p>
 * 开关只影响本地绘制：关闭时这里照常接收快照，但渲染路径不会查询它；
 * 重新开启后显示的是最新快照，不会补播关闭期间的内容。
 */
@OnlyIn(Dist.CLIENT)
public final class ThinkingDisplayCache {

    /** 快照最多等待多少个客户端 tick，等待原生气泡同步到达。 */
    public static final int PENDING_TIMEOUT_TICKS = 40;

    /** 只在客户端主线程读写，无需并发容器。 */
    private static final Map<Key, Entry> ENTRIES = new HashMap<>();

    private ThinkingDisplayCache() {
    }

    /** 展示状态：客户端绘制只读这里，不做任何协议解析。 */
    public static final class Entry {
        private final long displaySessionId;
        private long revision;
        private long bubbleId;
        private byte phase;
        private String line = "";
        private int ageTicks;

        private Entry(long displaySessionId) {
            this.displaySessionId = displaySessionId;
        }

        public long bubbleId() {
            return bubbleId;
        }

        public byte phase() {
            return phase;
        }

        public String line() {
            return line;
        }
    }

    private record Key(String dimension, UUID maidId, long displaySessionId) {
    }

    public static void accept(ThinkingDisplayPayload payload) {
        try {
            if (payload.dimension() == null || payload.maidId() == null) {
                return;
            }
            if (payload.phase() == ThinkingDisplayPayload.PHASE_CLEAR || payload.bubbleId() < 0L) {
                // CLEAR、以及「已无等待气泡」：该展示会话不再作为展示目标。
                removeSession(payload.dimension(), payload.maidId(), payload.displaySessionId());
                return;
            }
            Key key = new Key(payload.dimension(), payload.maidId(), payload.displaySessionId());
            Entry existing = ENTRIES.get(key);
            if (existing != null && payload.revision() <= existing.revision) {
                // 迟到或重复的快照不覆盖更新的状态。
                return;
            }
            Entry entry = new Entry(payload.displaySessionId());
            entry.revision = payload.revision();
            entry.bubbleId = payload.bubbleId();
            entry.phase = payload.phase();
            entry.line = payload.line() == null ? "" : payload.line();
            ENTRIES.put(key, entry);
        } catch (Throwable ignored) {
            // 展示缓存绝不能影响客户端主流程
        }
    }

    /**
     * 取出与「当前正在绘制的这个等待气泡数据对象」对应的展示条目。
     * <p>
     * 调用方直接给出正在渲染的女仆，因此这里的匹配是精确的：气泡 ID 变化后，
     * 旧气泡的对象与新 ID 不再对应，旧气泡不会被当成展示目标。
     *
     * @param data 该气泡本次传入的原始 {@link IChatBubbleData} 对象
     * @return 命中时返回条目，否则返回 null（调用方应原样使用原 renderer）
     */
    public static Entry currentFor(EntityMaid maid, Object data) {
        Level level = maid.level();
        String dimension = level.dimension().location().toString();
        UUID maidId = maid.getUUID();
        for (Map.Entry<Key, Entry> candidate : ENTRIES.entrySet()) {
            Key key = candidate.getKey();
            Entry entry = candidate.getValue();
            if (!key.dimension().equals(dimension) || !key.maidId().equals(maidId)) {
                continue;
            }
            if (bubbleData(maid, entry.bubbleId) == data) {
                return entry;
            }
        }
        return null;
    }

    /** 客户端每 tick 推进一次：为等待匹配的快照计时，超时丢弃；离开世界时整体清空。 */
    public static void tick() {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        if (ENTRIES.isEmpty()) {
            return;
        }
        String dimension = level.dimension().location().toString();
        for (Iterator<Map.Entry<Key, Entry>> iterator = ENTRIES.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<Key, Entry> candidate = iterator.next();
            Entry entry = candidate.getValue();
            if (candidate.getKey().dimension().equals(dimension)) {
                // 仍在当前维度：气泡本身是否还在由渲染路径逐帧判断，这里只统计快照年龄。
                entry.ageTicks = 0;
                continue;
            }
            entry.ageTicks++;
            if (entry.ageTicks > PENDING_TIMEOUT_TICKS) {
                iterator.remove();
            }
        }
    }

    /** 退出世界、断开连接时清空全部展示状态。 */
    public static void clear() {
        ENTRIES.clear();
    }

    /** 当前保留的展示会话数量。 */
    public static int size() {
        return ENTRIES.size();
    }

    private static void removeSession(String dimension, UUID maidId, long displaySessionId) {
        ENTRIES.keySet().removeIf(key -> key.displaySessionId() == displaySessionId
                && key.maidId().equals(maidId) && key.dimension().equals(dimension));
    }

    private static Object bubbleData(EntityMaid maid, long bubbleId) {
        try {
            return maid.getChatBubbleManager().getChatBubble(bubbleId);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

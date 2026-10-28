package com.xm2nd.tlmagenttweaks.chat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家「我的对话设为私密」偏好的世界数据。
 * <p>
 * 按 UUID 保存已勾选私密的玩家集合（集合中不存在即为默认 false），绑定主世界数据存储，
 * 服务器所有维度共用一份；依靠原生世界保存机制落盘，不存对话正文或女仆 ID。
 * 只在服务端主线程访问。
 */
public class ChatPrivacySavedData extends SavedData {

    private static final String DATA_NAME = "tlm_agent_tweaks_chat_privacy";

    private final Set<UUID> privatePlayers = new HashSet<>();

    public static ChatPrivacySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ChatPrivacySavedData::load, ChatPrivacySavedData::new, DATA_NAME);
    }

    public boolean isPrivate(UUID playerId) {
        return this.privatePlayers.contains(playerId);
    }

    /** 实际发生变化时才标脏，避免无意义的频繁写盘。 */
    public void setPrivate(UUID playerId, boolean value) {
        if (value) {
            if (this.privatePlayers.add(playerId)) {
                this.setDirty();
            }
        } else if (this.privatePlayers.remove(playerId)) {
            this.setDirty();
        }
    }

    public static ChatPrivacySavedData load(CompoundTag tag) {
        ChatPrivacySavedData data = new ChatPrivacySavedData();
        data.readNbt(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        writeNbt(tag);
        return tag;
    }

    private void readNbt(CompoundTag tag) {
        this.privatePlayers.clear();
        ListTag list = tag.getList("PrivatePlayers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("Id")) {
                this.privatePlayers.add(entry.getUUID("Id"));
            }
        }
    }

    private void writeNbt(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID id : this.privatePlayers) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            list.add(entry);
        }
        tag.put("PrivatePlayers", list);
    }
}

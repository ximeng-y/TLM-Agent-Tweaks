package com.xm2nd.tlmagenttweaks.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolMetadataStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 协议 opaque 元数据的世界存档持久化。
 * <p>
 * Responses reasoning/encrypted item 与 Anthropic thinking/signature 必须能跨服务端重启
 * 回到同一女仆、站点和协议；文件损坏时销毁会丢失历史对应关系，因此转入全局失败关闭。
 */
public final class ProtocolMetadataPersistence {
    public static final String DATA_DIRECTORY = "tlm_agent_tweaks";
    public static final String FILE_NAME = "protocol-metadata.json";

    private static final Gson GSON = new Gson();
    private static final long FLUSH_INTERVAL_MS = 1000;
    private static final ProtocolMetadataStore STORE = new ProtocolMetadataStore();

    private static volatile Path metadataFile;
    private static volatile boolean dirty;
    private static volatile long lastFlushMs;
    private static long generation;

    private ProtocolMetadataPersistence() {
    }

    public static ProtocolMetadataStore store() {
        return STORE;
    }

    public static void onServerStarted(MinecraftServer server) {
        Path directory = server.getWorldPath(new LevelResource(DATA_DIRECTORY));
        metadataFile = directory.resolve(FILE_NAME);
        dirty = false;
        lastFlushMs = System.currentTimeMillis();
        try {
            Files.createDirectories(directory);
            if (Files.exists(metadataFile)) {
                JsonObject root = JsonParser.parseString(Files.readString(metadataFile, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                STORE.load(root);
            } else {
                STORE.clear();
            }
        } catch (Exception e) {
            STORE.clear();
            STORE.markGlobalReplayUncertain();
            // 覆盖损坏文件为“全局失败关闭”快照，避免下次启动因文件缺失而重新放开不完整重放。
            dirty = true;
            TlmAgentTweaks.LOGGER.error("协议 opaque 元数据加载失败，本次启动将对缺失元数据失败关闭", e);
        }
    }

    public static void onServerTick() {
        if (!dirty || metadataFile == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFlushMs < FLUSH_INTERVAL_MS) {
            return;
        }
        flush();
    }

    public static void onServerStopping() {
        flush();
    }

    public static void markDirty() {
        if (metadataFile != null) {
            synchronized (ProtocolMetadataPersistence.class) {
                generation++;
                dirty = true;
            }
        }
    }

    private static synchronized void flush() {
        Path file = metadataFile;
        if (file == null || !dirty) {
            return;
        }
        long flushGeneration = generation;
        try {
            JsonObject snapshot = STORE.toJson();
            writeAtomically(file, GSON.toJson(snapshot));
            if (generation == flushGeneration) {
                dirty = false;
            }
            lastFlushMs = System.currentTimeMillis();
        } catch (Exception e) {
            TlmAgentTweaks.LOGGER.error("协议 opaque 元数据保存失败", e);
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

package com.xm2nd.tlmagenttweaks.common.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话隔离的协议私有元数据缓存。
 * <p>
 * 仅保存 Responses reasoning/encrypted item 或 Anthropic thinking/signature 等
 * 需要原样回传的少量 JSON；按稳定的会话作用域隔离，并有总条目数和单对话条目数上限。
 */
public final class ProtocolMetadataStore {
    public static final int FORMAT_VERSION = 1;
    public static final int DEFAULT_MAX_ENTRIES = 256;
    public static final int DEFAULT_MAX_ENTRIES_PER_SCOPE = 32;

    private final int maxEntries;
    private final int maxEntriesPerScope;
    private final Map<Object, LinkedHashMap<String, JsonElement>> entries = new HashMap<>();
    private final LinkedHashMap<Object, Boolean> replayUncertainScopes =
            new LinkedHashMap<>(16, 0.75f, true);
    private int totalEntries;
    private boolean replayUncertainOverflow;

    public ProtocolMetadataStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRIES_PER_SCOPE);
    }

    public ProtocolMetadataStore(int maxEntries, int maxEntriesPerScope) {
        if (maxEntries <= 0 || maxEntriesPerScope <= 0) {
            throw new IllegalArgumentException("Metadata cache limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxEntriesPerScope = maxEntriesPerScope;
    }

    public synchronized void put(Object scope, String key, JsonElement metadata) {
        if (scope == null || key == null || key.isBlank() || metadata == null) {
            return;
        }
        LinkedHashMap<String, JsonElement> scopeEntries =
                entries.computeIfAbsent(scope, ignored -> new LinkedHashMap<>(16, 0.75f, true));
        if (scopeEntries.containsKey(key)) {
            JsonElement updated = metadata.deepCopy();
            scopeEntries.put(key, updated);
            markReplayUncertainIfNeeded(scope, updated);
            return;
        }

        scopeEntries.put(key, metadata.deepCopy());
        totalEntries++;
        // 只要该会话曾经出现过必须回传的 opaque 项，后续缺失元数据就必须失败关闭，
        // 即使元数据因历史键变化或重启而无法按原键命中。
        markReplayUncertainIfNeeded(scope, metadata);
        while (scopeEntries.size() > maxEntriesPerScope) {
            String eldest = scopeEntries.keySet().iterator().next();
            JsonElement removed = scopeEntries.remove(eldest);
            totalEntries--;
            markReplayUncertainIfNeeded(scope, removed);
        }
        if (scopeEntries.isEmpty()) {
            entries.remove(scope);
        }
        while (totalEntries > maxEntries && !entries.isEmpty()) {
            Object eldestScope = null;
            String eldestKey = null;
            for (Map.Entry<Object, LinkedHashMap<String, JsonElement>> entry : entries.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    eldestScope = entry.getKey();
                    eldestKey = entry.getValue().keySet().iterator().next();
                    break;
                }
            }
            if (eldestScope == null) {
                break;
            }
            LinkedHashMap<String, JsonElement> scopeMap = entries.get(eldestScope);
            JsonElement removed = scopeMap.remove(eldestKey);
            totalEntries--;
            markReplayUncertainIfNeeded(eldestScope, removed);
            if (scopeMap.isEmpty()) {
                entries.remove(eldestScope);
            }
        }
    }

    public synchronized JsonElement get(Object scope, String key) {
        if (scope == null || key == null) {
            return null;
        }
        LinkedHashMap<String, JsonElement> scopeEntries = entries.get(scope);
        if (scopeEntries == null) {
            return null;
        }
        JsonElement metadata = scopeEntries.get(key);
        return metadata == null ? null : metadata.deepCopy();
    }

    public synchronized void removeScope(Object scope) {
        LinkedHashMap<String, JsonElement> removed = entries.remove(scope);
        if (removed != null) {
            totalEntries -= removed.size();
        }
        replayUncertainScopes.remove(scope);
    }

    public synchronized void clear() {
        entries.clear();
        replayUncertainScopes.clear();
        replayUncertainOverflow = false;
        totalEntries = 0;
    }

    public synchronized int size() {
        return totalEntries;
    }

    /**
     * 导出可持久化的元数据快照。仅支持本 mod 的会话作用域类型。
     */
    public synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);

        JsonArray entriesJson = new JsonArray();
        for (Map.Entry<Object, LinkedHashMap<String, JsonElement>> scopeEntry : entries.entrySet()) {
            for (Map.Entry<String, JsonElement> entry : scopeEntry.getValue().entrySet()) {
                JsonObject stored = new JsonObject();
                stored.add("scope", scopeToJson(scopeEntry.getKey()));
                stored.addProperty("key", entry.getKey());
                stored.add("metadata", entry.getValue().deepCopy());
                entriesJson.add(stored);
            }
        }
        root.add("entries", entriesJson);

        JsonArray replayUncertain = new JsonArray();
        for (Object scope : replayUncertainScopes.keySet()) {
            replayUncertain.add(scopeToJson(scope));
        }
        root.add("replay_uncertain_scopes", replayUncertain);
        root.addProperty("replay_uncertain_overflow", replayUncertainOverflow);
        return root;
    }

    /**
     * 从持久化快照恢复元数据。输入无效时不修改当前缓存。
     */
    public synchronized void load(JsonObject root) throws ProtocolException {
        if (root == null) {
            throw new ProtocolException("Metadata snapshot must not be null");
        }
        int version = optionalInt(root, "version", -1);
        if (version != FORMAT_VERSION) {
            throw new ProtocolException("Unsupported metadata snapshot version: " + version);
        }

        List<StoredEntry> loadedEntries = new ArrayList<>();
        JsonArray entriesJson = optionalArray(root, "entries");
        if (entriesJson != null) {
            for (JsonElement element : entriesJson) {
                if (!element.isJsonObject()) {
                    throw new ProtocolException("Metadata snapshot entry must be an object");
                }
                JsonObject stored = element.getAsJsonObject();
                Object scope = scopeFromJson(stored.get("scope"));
                String key = requiredString(stored, "key");
                JsonElement metadata = stored.get("metadata");
                if (metadata == null || metadata.isJsonNull()) {
                    throw new ProtocolException("Metadata snapshot entry metadata must not be null");
                }
                loadedEntries.add(new StoredEntry(scope, key, metadata.deepCopy()));
            }
        }

        List<Object> loadedReplayUncertain = new ArrayList<>();
        JsonArray replayUncertain = optionalArray(root, "replay_uncertain_scopes");
        if (replayUncertain != null) {
            for (JsonElement element : replayUncertain) {
                loadedReplayUncertain.add(scopeFromJson(element));
            }
        }
        boolean loadedOverflow = optionalBoolean(root, "replay_uncertain_overflow", false);

        clearInternal();
        for (Object scope : loadedReplayUncertain) {
            putReplayUncertain(scope);
        }
        this.replayUncertainOverflow = loadedOverflow;
        for (StoredEntry entry : loadedEntries) {
            put(entry.scope(), entry.key(), entry.metadata());
        }
    }

    /**
     * 持久化文件损坏时无法判断历史 opaque 项是否完整，后续缺失元数据必须失败关闭。
     */
    public synchronized void markGlobalReplayUncertain() {
        replayUncertainOverflow = true;
    }

    /**
     * 该会话是否曾出现需要 opaque 回传的元数据，或是否因容量淘汰丢失过此类元数据。
     * 一旦为 true，调用方对缺失元数据的工具历史必须失败关闭，不能静默重放为不完整请求。
     */
    public synchronized boolean isReplayUncertain(Object scope) {
        return replayUncertainOverflow || Boolean.TRUE.equals(replayUncertainScopes.get(scope));
    }

    private void markReplayUncertainIfNeeded(Object scope, JsonElement metadata) {
        if (metadata != null && metadata.isJsonObject()
                && metadata.getAsJsonObject().has("opaqueRequired")
                && metadata.getAsJsonObject().get("opaqueRequired").isJsonPrimitive()
                && metadata.getAsJsonObject().get("opaqueRequired").getAsBoolean()) {
            putReplayUncertain(scope);
        }
    }

    private void putReplayUncertain(Object scope) {
        replayUncertainScopes.put(scope, true);
        while (replayUncertainScopes.size() > maxEntries) {
            Object eldest = replayUncertainScopes.keySet().iterator().next();
            replayUncertainScopes.remove(eldest);
            replayUncertainOverflow = true;
        }
    }

    private void clearInternal() {
        entries.clear();
        replayUncertainScopes.clear();
        replayUncertainOverflow = false;
        totalEntries = 0;
    }

    private static JsonObject scopeToJson(Object scope) {
        if (!(scope instanceof ProtocolConversationScope conversationScope)) {
            throw new IllegalArgumentException("Unsupported metadata scope type: "
                    + (scope == null ? "null" : scope.getClass().getName()));
        }
        JsonObject json = new JsonObject();
        json.addProperty("owner_id", conversationScope.ownerId());
        json.addProperty("site_id", conversationScope.siteId());
        json.addProperty("protocol", conversationScope.protocol().id());
        return json;
    }

    private static Object scopeFromJson(JsonElement element) throws ProtocolException {
        if (element == null || !element.isJsonObject()) {
            throw new ProtocolException("Metadata snapshot scope must be an object");
        }
        JsonObject scope = element.getAsJsonObject();
        String ownerId = requiredString(scope, "owner_id");
        String siteId = requiredString(scope, "site_id");
        SiteProtocol protocol = SiteProtocol.fromIdOrThrow(requiredString(scope, "protocol"));
        return new ProtocolConversationScope(ownerId, siteId, protocol);
    }

    private static JsonArray optionalArray(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonArray()) {
            throw new ProtocolException("Metadata snapshot field '" + field + "' must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolException("Metadata snapshot field '" + field + "' must be a string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new ProtocolException("Metadata snapshot field '" + field + "' must not be blank");
        }
        return value;
    }

    private static int optionalInt(JsonObject object, String field, int fallback) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ProtocolException("Metadata snapshot field '" + field + "' must be a number");
        }
        return element.getAsInt();
    }

    private static boolean optionalBoolean(JsonObject object, String field, boolean fallback)
            throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ProtocolException("Metadata snapshot field '" + field + "' must be a boolean");
        }
        return element.getAsBoolean();
    }

    private record StoredEntry(Object scope, String key, JsonElement metadata) {
    }
}

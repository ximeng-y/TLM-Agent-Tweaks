package com.xm2nd.tlmagenttweaks.common.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 由 tool_call 批次生成稳定键，只用于本 mod 的内存元数据查找。
 */
final class ProtocolMetadataKey {
    private ProtocolMetadataKey() {
    }

    static String forToolCalls(SiteProtocol protocol, JsonArray toolCalls) throws ProtocolException {
        if (protocol == null || toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(protocol.id().getBytes(StandardCharsets.UTF_8));
            for (JsonElement element : toolCalls) {
                if (!element.isJsonObject()) {
                    throw new ProtocolException("Tool call must be an object");
                }
                JsonObject toolCall = element.getAsJsonObject();
                String id = string(toolCall, "id", true);
                JsonObject function = object(toolCall, "function", true);
                String name = string(function, "name", true);
                String arguments = string(function, "arguments", true);
                digest.update((byte) 0x1f);
                digest.update(id.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
                digest.update(arguments.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new ProtocolException("SHA-256 is unavailable", e);
        }
    }

    private static String string(JsonObject object, String field, boolean required) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            if (required) {
                throw new ProtocolException("Tool call field '" + field + "' is missing");
            }
            return "";
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolException("Tool call field '" + field + "' must be a string");
        }
        return element.getAsString();
    }

    private static JsonObject object(JsonObject object, String field, boolean required) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            if (required) {
                throw new ProtocolException("Tool call field '" + field + "' is missing or not an object");
            }
            return new JsonObject();
        }
        return element.getAsJsonObject();
    }
}

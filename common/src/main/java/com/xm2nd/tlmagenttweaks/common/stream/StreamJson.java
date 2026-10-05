package com.xm2nd.tlmagenttweaks.common.stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;

/**
 * 流式聚合器共享的 JSON 读写工具，避免三份重复的特判代码。
 */
final class StreamJson {
    private StreamJson() {
    }

    static JsonObject parseObject(String data, String context) throws ProtocolException {
        if (data == null || data.isBlank()) {
            throw new ProtocolException(context + ": empty SSE data");
        }
        try {
            JsonElement element = JsonParser.parseString(data);
            if (!element.isJsonObject()) {
                throw new ProtocolException(context + ": SSE data is not a JSON object");
            }
            return element.getAsJsonObject();
        } catch (ProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProtocolException(context + ": SSE data is not valid JSON", e);
        }
    }

    static String optionalString(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolException("Field '" + field + "' must be a string");
        }
        return element.getAsString();
    }

    static String stringOr(JsonObject object, String field, String fallback) throws ProtocolException {
        String value = optionalString(object, field);
        return value == null ? fallback : value;
    }

    static String requiredString(JsonObject object, String field) throws ProtocolException {
        String value = optionalString(object, field);
        if (value == null || value.isEmpty()) {
            throw new ProtocolException("Field '" + field + "' is required");
        }
        return value;
    }

    static int intOr(JsonObject object, String field, int fallback) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ProtocolException("Field '" + field + "' must be a number");
        }
        return element.getAsInt();
    }

    static JsonObject optionalObject(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw new ProtocolException("Field '" + field + "' must be an object");
        }
        return element.getAsJsonObject();
    }

    static JsonObject requiredObject(JsonObject object, String field) throws ProtocolException {
        JsonObject value = optionalObject(object, field);
        if (value == null) {
            throw new ProtocolException("Field '" + field + "' is required");
        }
        return value;
    }

    static JsonArray optionalArray(JsonObject object, String field) throws ProtocolException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonArray()) {
            throw new ProtocolException("Field '" + field + "' must be an array");
        }
        return element.getAsJsonArray();
    }

    static JsonObject objectWith(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("key/value pairs required");
        }
        JsonObject object = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            object.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return object;
    }

    static void addNumber(JsonObject target, String field, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            target.addProperty(field, value.getAsLong());
        }
    }
}

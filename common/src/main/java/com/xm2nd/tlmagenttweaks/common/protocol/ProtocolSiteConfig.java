package com.xm2nd.tlmagenttweaks.common.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 站点协议配置，只保存协议和 URL 语义，不保存端点正文或鉴权信息。
 */
public record ProtocolSiteConfig(SiteProtocol protocol, UrlMode urlMode) {
    public static final String PROTOCOL = "protocol";
    public static final String URL_MODE = "url_mode";

    /**
     * 旧版 openai 站点没有这两个字段，必须保持完整 URL 语义。
     */
    public static final SiteProtocol LEGACY_PROTOCOL = SiteProtocol.CHAT_COMPLETIONS;
    public static final UrlMode LEGACY_URL_MODE = UrlMode.EXACT;

    public ProtocolSiteConfig {
        if (protocol == null || urlMode == null) {
            throw new IllegalArgumentException("protocol and urlMode must not be null");
        }
    }

    public static ProtocolSiteConfig legacyDefaults() {
        return new ProtocolSiteConfig(LEGACY_PROTOCOL, LEGACY_URL_MODE);
    }

    public static ProtocolSiteConfig fromJson(JsonObject json) throws ProtocolException {
        if (json == null) {
            return legacyDefaults();
        }
        SiteProtocol protocol = LEGACY_PROTOCOL;
        if (json.has(PROTOCOL)) {
            protocol = SiteProtocol.fromIdOrThrow(asNullableString(json.get(PROTOCOL), PROTOCOL));
        }
        UrlMode urlMode = LEGACY_URL_MODE;
        if (json.has(URL_MODE)) {
            urlMode = UrlMode.fromIdOrThrow(asNullableString(json.get(URL_MODE), URL_MODE));
        }
        return new ProtocolSiteConfig(protocol, urlMode);
    }

    public void writeTo(JsonObject json) {
        json.addProperty(PROTOCOL, protocol.id());
        json.addProperty(URL_MODE, urlMode.id());
    }

    private static String asNullableString(JsonElement element, String field) throws ProtocolException {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolException("Field '" + field + "' must be a string");
        }
        return element.getAsString();
    }
}

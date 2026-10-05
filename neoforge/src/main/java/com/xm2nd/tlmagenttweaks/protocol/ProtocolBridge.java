package com.xm2nd.tlmagenttweaks.protocol;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm2nd.tlmagenttweaks.common.protocol.DecodedResponse;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolConversationScope;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolRequestEncoder;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolResponseDecoder;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolUrls;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.protocol.UrlMode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * NeoForge 侧协议桥接：把 common 纯 Java 编解码器接到 TLM 的站点/HTTP 生命周期。
 */
public final class ProtocolBridge {
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final Gson GSON = new Gson();
    private static final Map<LLMCallback, ProtocolException> REQUEST_ERRORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ProtocolBridge() {
    }

    public static SiteProtocol protocolOf(LLMOpenAISite site) {
        if (site instanceof ProtocolLLMSite protocolSite) {
            return protocolSite.protocol();
        }
        return SiteProtocol.CHAT_COMPLETIONS;
    }

    public static UrlMode urlModeOf(LLMOpenAISite site) {
        if (site instanceof ProtocolLLMSite protocolSite) {
            return protocolSite.urlMode();
        }
        return UrlMode.EXACT;
    }

    public static LLMOpenAISite withProtocol(LLMOpenAISite site, SiteProtocol protocol, UrlMode urlMode) {
        if (site == null) {
            return null;
        }
        SiteProtocol resolvedProtocol = protocol == null ? protocolOf(site) : protocol;
        UrlMode resolvedMode = urlMode == null ? urlModeOf(site) : urlMode;
        if (site instanceof ProtocolLLMSite protocolSite
                && protocolSite.protocol() == resolvedProtocol
                && protocolSite.urlMode() == resolvedMode) {
            return site;
        }
        return new ProtocolLLMSite(site.id(), site.icon(), site.url(), site.enabled(), site.secretKey(),
                site.hasThinkingField(), Map.copyOf(site.headers()), Map.copyOf(site.modelEntries()),
                resolvedProtocol, resolvedMode);
    }

    public static String encodeBody(LLMOpenAISite site, Gson gson, Object canonicalRequest, LLMCallback callback) {
        String canonicalJson = gson.toJson(canonicalRequest);
        SiteProtocol protocol = protocolOf(site);
        if (protocol == SiteProtocol.CHAT_COMPLETIONS) {
            return canonicalJson;
        }
        try {
            JsonObject canonical = JsonParser.parseString(canonicalJson).getAsJsonObject();
            return ProtocolRequestEncoder.encodeToString(protocol, canonical, ProtocolMetadataPersistence.store(),
                    conversationScope(callback, site));
        } catch (ProtocolException | RuntimeException e) {
            failRequest(callback, toProtocolException("Failed to encode LLM request", e));
            // 真正的异常会在 sendAsync redirect 处作为 failedFuture 交给原 handle。
            return "{}";
        }
    }

    public static URI resolveUri(LLMOpenAISite site, URI original, LLMCallback callback) {
        if (site == null || original == null) {
            return original;
        }
        SiteProtocol protocol = protocolOf(site);
        UrlMode urlMode = urlModeOf(site);
        if (protocol == SiteProtocol.CHAT_COMPLETIONS && urlMode == UrlMode.EXACT) {
            return original;
        }
        try {
            return ProtocolUrls.resolve(protocol, urlMode, original.toString());
        } catch (ProtocolException e) {
            failRequest(callback, e);
            return original;
        }
    }

    public static HttpRequest.Builder applyAuthorization(HttpRequest.Builder builder, SiteProtocol protocol,
                                                         String apiKey) {
        if (protocol == SiteProtocol.ANTHROPIC_MESSAGES) {
            builder.header("x-api-key", apiKey == null ? "" : apiKey);
            builder.header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            builder.header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
        }
        return builder;
    }

    public static HttpRequest.Builder applyAuthorizationHeader(HttpRequest.Builder builder, SiteProtocol protocol,
                                                              String originalHeaderValue) {
        if (protocol != SiteProtocol.ANTHROPIC_MESSAGES) {
            return builder.header("Authorization", originalHeaderValue);
        }
        String prefix = "Bearer ";
        String apiKey = originalHeaderValue != null && originalHeaderValue.startsWith(prefix)
                ? originalHeaderValue.substring(prefix.length()) : originalHeaderValue;
        return applyAuthorization(builder, protocol, apiKey);
    }

    public static void failRequest(LLMCallback callback, ProtocolException error) {
        if (callback == null || error == null) {
            return;
        }
        synchronized (REQUEST_ERRORS) {
            if (!REQUEST_ERRORS.containsKey(callback)) {
                REQUEST_ERRORS.put(callback, error);
            }
        }
    }

    public static ProtocolException consumeRequestError(LLMCallback callback) {
        if (callback == null) {
            return null;
        }
        synchronized (REQUEST_ERRORS) {
            return REQUEST_ERRORS.remove(callback);
        }
    }

    public static ProtocolConversationScope conversationScope(LLMCallback callback, LLMOpenAISite site) {
        String ownerId = callback != null && callback.getMaid() != null
                ? callback.getMaid().getUUID().toString()
                : "callback-" + System.identityHashCode(callback);
        String siteId = site == null || site.id() == null ? "" : site.id();
        return new ProtocolConversationScope(ownerId, siteId, protocolOf(site));
    }

    public static boolean isNormalized(HttpResponse<?> response) {
        return response instanceof ProtocolHttpResponse protocolResponse
                && protocolResponse.isProtocolNormalized();
    }

    public static HttpResponse<String> normalizeResponse(LLMOpenAISite site, HttpResponse<String> response,
                                                         LLMCallback callback) throws ProtocolException {
        if (response == null || site == null || isNormalized(response)) {
            return response;
        }
        SiteProtocol protocol = protocolOf(site);
        if (protocol == SiteProtocol.CHAT_COMPLETIONS) {
            return response;
        }
        DecodedResponse decoded = ProtocolResponseDecoder.decode(protocol, response.body());
        if (decoded.metadataKey() != null && decoded.metadata() != null && callback != null) {
            ProtocolMetadataPersistence.store().put(conversationScope(callback, site),
                    decoded.metadataKey(), decoded.metadata());
            ProtocolMetadataPersistence.markDirty();
        }
        return new ProtocolHttpResponse(response, GSON.toJson(decoded.body()), true);
    }

    private static ProtocolException toProtocolException(String message, Throwable throwable) {
        if (throwable instanceof ProtocolException protocolException) {
            return protocolException;
        }
        return new ProtocolException(message, throwable);
    }
}

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
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingChoice;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingRequestApplier;

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
    /** 本轮请求是否注入过思考参数：失败侧据此决定是否给出思考设置的预设提示。 */
    private static final Map<LLMCallback, Boolean> THINKING_APPLIED =
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
        return withProtocol(site, protocol, urlMode,
                site instanceof ProtocolLLMSite protocolSite ? protocolSite.thinkingConfigs() : Map.of());
    }

    public static LLMOpenAISite withProtocol(LLMOpenAISite site, SiteProtocol protocol, UrlMode urlMode,
                                             Map<String, ModelThinkingConfig> thinkingConfigs) {
        if (site == null) {
            return null;
        }
        SiteProtocol resolvedProtocol = protocol == null ? protocolOf(site) : protocol;
        UrlMode resolvedMode = urlMode == null ? urlModeOf(site) : urlMode;
        Map<String, ModelThinkingConfig> resolvedThinking = thinkingConfigs == null
                ? Map.of() : thinkingConfigs;
        if (site instanceof ProtocolLLMSite protocolSite
                && protocolSite.protocol() == resolvedProtocol
                && protocolSite.urlMode() == resolvedMode
                && protocolSite.thinkingConfigs().equals(resolvedThinking)) {
            return site;
        }
        return new ProtocolLLMSite(site.id(), site.icon(), site.url(), site.enabled(), site.secretKey(),
                site.hasThinkingField(), Map.copyOf(site.headers()), Map.copyOf(site.modelEntries()),
                resolvedProtocol, resolvedMode, resolvedThinking);
    }

    public static String encodeBody(LLMOpenAISite site, Gson gson, Object canonicalRequest, LLMCallback callback) {
        String canonicalJson = gson.toJson(canonicalRequest);
        SiteProtocol protocol = protocolOf(site);
        ThinkingChoice choice = resolveThinkingChoice(site, callback);
        if (protocol == SiteProtocol.CHAT_COMPLETIONS) {
            return choice == null ? canonicalJson : rewriteBody(protocol, canonicalJson, callback, choice);
        }
        try {
            JsonObject canonical = JsonParser.parseString(canonicalJson).getAsJsonObject();
            String encoded = ProtocolRequestEncoder.encodeToString(protocol, canonical,
                    ProtocolMetadataPersistence.store(), conversationScope(callback, site));
            return choice == null ? encoded : rewriteBody(protocol, encoded, callback, choice);
        } catch (ProtocolException | RuntimeException e) {
            failRequest(callback, toProtocolException("Failed to encode LLM request", e));
            // 真正的异常会在 sendAsync redirect 处作为 failedFuture 交给原 handle。
            return "{}";
        }
    }

    /**
     * 汇总该次请求的思考决定：站点没给模型配置思考项、或女仆没做过（仍有效的）选择时返回
     * {@code null}，请求保持 TLM 原样。
     */
    private static ThinkingChoice resolveThinkingChoice(LLMOpenAISite site, LLMCallback callback) {
        if (!(site instanceof ProtocolLLMSite protocolSite) || callback == null
                || callback.getMaid() == null) {
            return null;
        }
        String model = callback.getMaid().getAiChatManager().getLLMModel();
        ModelThinkingConfig config = protocolSite.thinkingConfigs().get(model);
        if (config == null || !config.configured()) {
            return null;
        }
        MaidThinkingChoiceStore.Stored stored = MaidThinkingChoiceStore.read(callback.getMaid());
        if (stored == null || !stored.siteId().equals(site.id()) || !stored.modelId().equals(model)) {
            return null;
        }
        ThinkingChoice choice = stored.choice();
        // 关闭思考是开关提供的选项：不允许开关时「关闭」无从谈起
        if (!choice.enabled() && !config.toggleAllowed()) {
            return null;
        }
        // 强度必须落在管理员配置内；开关未开放时思考隐含开启，只看强度合法性
        if (choice.enabled() && !config.allows(choice.level())) {
            return null;
        }
        return choice;
    }

    /**
     * 把思考决定写进最终请求体；写入成功时登记标记，供失败侧的预设提示判定使用。
     */
    private static String rewriteBody(SiteProtocol protocol, String json, LLMCallback callback,
                                      ThinkingChoice choice) {
        try {
            JsonObject body = JsonParser.parseString(json).getAsJsonObject();
            if (!ThinkingRequestApplier.apply(protocol, body, choice)) {
                return json;
            }
            synchronized (THINKING_APPLIED) {
                THINKING_APPLIED.put(callback, Boolean.TRUE);
            }
            return GSON.toJson(body);
        } catch (RuntimeException e) {
            // 思考注入失败不阻断请求，保持编码器产物原样发出。
            return json;
        }
    }

    /**
     * 本轮请求是否注入过思考参数；只读不清理，回调对象被回收后条目随之失效。
     */
    public static boolean thinkingApplied(LLMCallback callback) {
        if (callback == null) {
            return false;
        }
        synchronized (THINKING_APPLIED) {
            return THINKING_APPLIED.containsKey(callback);
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

    /**
     * 流式聚合成功后的统一归一化入口：与普通响应走同一套
     * {@link ProtocolResponseDecoder} 与元数据保存逻辑，只执行一次。
     *
     * @return 标记为已归一化的响应，可直接重新进入原 handle，不会再被归一化
     */
    public static HttpResponse<String> normalizeAggregated(LLMOpenAISite site, HttpResponse<String> response,
                                                           LLMCallback callback) throws ProtocolException {
        if (isNormalized(response)) {
            return response;
        }
        return normalizeResponse(site, response, callback);
    }

    private static ProtocolException toProtocolException(String message, Throwable throwable) {
        if (throwable instanceof ProtocolException protocolException) {
            return protocolException;
        }
        return new ProtocolException(message, throwable);
    }
}

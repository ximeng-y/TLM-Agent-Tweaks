package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Client;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolBridge;
import com.xm2nd.tlmagenttweaks.stats.TokenStatsService;
import com.google.gson.Gson;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 接管 TLM 的 OpenAI 兼容客户端出口，并在原响应处理前统一协议形态。
 * <p>
 * 请求仍由 TLM 原 {@code chat} 方法构造消息和工具，只替换请求正文、URI 与鉴权头；
 * 响应仍回到 TLM 原 {@code handle}，因此工具调用、历史和失败回调语义不变。
 * Self-Talk 未注入此类，双方无注入冲突。
 */
@Mixin(value = LLMOpenAIClient.class, remap = false)
public abstract class LLMOpenAIClientMixin {

    @Shadow
    @Final
    protected LLMOpenAISite site;

    @Shadow
    protected abstract void handle(LLMCallback callback, HttpResponse<String> response,
                                   Throwable throwable, HttpRequest request);

    @Redirect(method = "chat", at = @At(value = "INVOKE",
            target = "java/net/URI.create(Ljava/lang/String;)Ljava/net/URI;"))
    private URI tlmAgentTweaks$createSiteUri(String rawUrl, LLMCallback callback) {
        try {
            return URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            ProtocolBridge.failRequest(callback, new ProtocolException("Invalid LLM site URL", e));
            // sendAsync 会消费上面的错误并返回 failedFuture，不会真的访问该占位地址。
            return URI.create("http://127.0.0.1/");
        }
    }

    @Redirect(method = "chat", at = @At(value = "INVOKE",
            target = "com/github/tartaricacid/touhoulittlemaid/ai/agent/tool/ITool.trigger"
                    + "(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;"
                    + "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/openai/request/ChatCompletion;)Z"))
    private boolean tlmAgentTweaks$triggerTool(ITool<?> tool, EntityMaid maid, ChatCompletion chatCompletion,
                                               LLMCallback callback) {
        try {
            return tool.trigger(maid, chatCompletion);
        } catch (RuntimeException e) {
            ProtocolBridge.failRequest(callback, new ProtocolException("LLM tool trigger failed", e));
            return false;
        }
    }

    @Redirect(method = "chat", at = @At(value = "INVOKE",
            target = "com/google/gson/Gson.toJson(Ljava/lang/Object;)Ljava/lang/String;", ordinal = 0))
    private String tlmAgentTweaks$encodeRequest(Gson gson, Object canonicalRequest, LLMCallback callback) {
        return ProtocolBridge.encodeBody(this.site, gson, canonicalRequest, callback);
    }

    @Redirect(method = "chat", at = @At(value = "INVOKE",
            target = "java/net/http/HttpRequest$Builder.uri(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;"))
    private HttpRequest.Builder tlmAgentTweaks$resolveUri(HttpRequest.Builder builder, URI original,
                                                         LLMCallback callback) {
        return builder.uri(ProtocolBridge.resolveUri(this.site, original, callback));
    }

    @Redirect(method = "chat", at = @At(value = "INVOKE",
            target = "java/net/http/HttpRequest$Builder.header(Ljava/lang/String;Ljava/lang/String;)"
                    + "Ljava/net/http/HttpRequest$Builder;", ordinal = 1))
    private HttpRequest.Builder tlmAgentTweaks$applyAuthorization(HttpRequest.Builder builder,
                                                                 String name, String value) {
        SiteProtocol protocol = ProtocolBridge.protocolOf(this.site);
        return ProtocolBridge.applyAuthorizationHeader(builder, protocol, value);
    }

    @Redirect(method = "chat", at = @At(value = "INVOKE",
            target = "java/net/http/HttpClient.sendAsync(Ljava/net/http/HttpRequest;"
                    + "Ljava/net/http/HttpResponse$BodyHandler;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<HttpResponse<String>> tlmAgentTweaks$sendAsync(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<String> bodyHandler,
            LLMCallback callback) {
        ProtocolException requestError = ProtocolBridge.consumeRequestError(callback);
        if (requestError != null) {
            return CompletableFuture.failedFuture(requestError);
        }
        return client.sendAsync(request, bodyHandler);
    }

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void tlmAgentTweaks$handleProtocolResponse(LLMCallback callback, HttpResponse<String> response,
                                                       Throwable throwable, HttpRequest request, CallbackInfo ci) {
        if (((Client) (Object) this).shouldStopChat(callback.getMaid())) {
            ci.cancel();
            return;
        }
        if (response == null || throwable != null || response.statusCode() < 200 || response.statusCode() >= 300) {
            tlmAgentTweaks$recordUsage(callback, response, throwable);
            return;
        }
        if (!ProtocolBridge.isNormalized(response)) {
            try {
                HttpResponse<String> normalized = ProtocolBridge.normalizeResponse(this.site, response, callback);
                if (normalized != response) {
                    this.handle(callback, normalized, throwable, request);
                    ci.cancel();
                    return;
                }
            } catch (ProtocolException e) {
                tlmAgentTweaks$recordUsage(callback, response, throwable);
                callback.onFailure(request, new Throwable("Protocol response decode failed: " + e.getMessage(), e),
                        ErrorCode.JSON_DECODE_ERROR);
                ci.cancel();
                return;
            } catch (Throwable t) {
                tlmAgentTweaks$recordUsage(callback, response, throwable);
                callback.onFailure(request, new Throwable("Protocol response decode failed", t),
                        ErrorCode.JSON_DECODE_ERROR);
                ci.cancel();
                return;
            }
        }
        tlmAgentTweaks$recordUsage(callback, response, throwable);
    }

    private void tlmAgentTweaks$recordUsage(LLMCallback callback, HttpResponse<String> response, Throwable throwable) {
        try {
            TokenStatsService.onLlmResponse(callback, this.site, response, throwable);
        } catch (Throwable t) {
            // 统计绝不能影响聊天流程
            TlmAgentTweaks.LOGGER.warn("token 用量统计记录失败（已忽略）", t);
        }
    }
}

package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Client;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.common.TokenRequestContext;
import com.xm2nd.tlmagenttweaks.common.TokenStatsHttpResponse;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.protocol.ProtocolBridge;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolHttpBridge;
import com.xm2nd.tlmagenttweaks.stats.TokenStatsService;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
        try {
            return ProtocolHttpBridge.send(client, request, this.site, callback);
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }

    @Redirect(method = "onTextCall", at = @At(value = "INVOKE",
            target = "com/github/tartaricacid/touhoulittlemaid/ai/service/ResponseCallback.onSuccess"
                    + "(Ljava/lang/Object;)V"))
    private void tlmAgentTweaks$dispatchTextSuccess(ResponseCallback callback, Object responseChat) {
        // 覆盖空文本与非空文本两处最终成功调用；非 LLMCallback 保持原样调用。
        if (callback instanceof LLMCallback llmCallback) {
            ProtocolHttpBridge.dispatchSuccess(llmCallback, responseChat);
        } else {
            callback.onSuccess(responseChat);
        }
    }

    /**
     * 1.20.1 的 {@code handle} 里 lambda 编号与 1.21.1 不同：1.20.1 的
     * {@code lambda$handle$1} 是 token 计数 mapper（{@code (int, ChatTokensCapability)}），
     * 业务体在 {@code lambda$handle$2}。两者都列入 {@code method} 数组并放低 require/expect，
     * 使同一份源码在两侧都能命中且不因合成名漂移而崩。
     */
    @Redirect(method = {"lambda$handle$1", "lambda$handle$2"}, require = 0, expect = 0,
            at = @At(value = "INVOKE",
            target = "com/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback.onFailure"
                    + "(Ljava/net/http/HttpRequest;Ljava/lang/Throwable;I)V"))
    private void tlmAgentTweaks$dispatchHandleFailure(LLMCallback callback, HttpRequest request,
                                                      Throwable throwable, int errorCode) {
        // Token 超限与无 choice 两处失败统一进入失败派发。
        ProtocolHttpBridge.dispatchFailure(callback, request, throwable, errorCode);
    }

    /**
     * handleResponse 是 {@code LLMClient} 接口的默认方法，其字节码只存在于接口内，
     * 实现类 {@link LLMOpenAIClient} 未声明该方法——按 {@code method="handleResponse"} 注入
     * 会被 Mixin 注入器忽略（目标类无该方法），required:true 下直接注入失败。
     * 因此改为在实现类确实存在的方法 {@code handle} 体内的调用点上重定向，
     * 复刻接口默认实现的成功/失败分支，并把三处失败统一进入失败派发。
     */
    @Redirect(method = "handle", at = @At(value = "INVOKE",
            target = "com/github/tartaricacid/touhoulittlemaid/ai/service/llm/openai/LLMOpenAIClient.handleResponse"
                    + "(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;"
                    + "Ljava/net/http/HttpResponse;Ljava/lang/Throwable;Ljava/net/http/HttpRequest;"
                    + "Ljava/util/function/Consumer;Ljava/lang/reflect/Type;)V"))
    private void tlmAgentTweaks$dispatchHandleResponse(LLMOpenAIClient client, LLMCallback callback,
                                                       HttpResponse<String> response, Throwable throwable,
                                                       HttpRequest request, Consumer consumer, Type type) {
        if (throwable != null) {
            ProtocolHttpBridge.dispatchFailure(callback, request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        try {
            int statusCode = response.statusCode();
            if (200 <= statusCode && statusCode < 300) {
                Object message = Client.GSON.fromJson(response.body(), type);
                consumer.accept(message);
            } else {
                String message = "HTTP Error Code: %d, Response: %s".formatted(statusCode, response.body());
                ProtocolHttpBridge.dispatchFailure(callback, request,
                        new Throwable(message), ErrorCode.REQUEST_RECEIVED_ERROR);
            }
        } catch (JsonSyntaxException e) {
            String message = "Exception %s, JSON is: %s".formatted(e.getLocalizedMessage(), response.body());
            ProtocolHttpBridge.dispatchFailure(callback, request,
                    new Throwable(message), ErrorCode.JSON_DECODE_ERROR);
        }
    }

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void tlmAgentTweaks$handleProtocolResponse(LLMCallback callback, HttpResponse<String> response,
                                                       Throwable throwable, HttpRequest request, CallbackInfo ci) {
        // 取消/超时/正常完成是持久终态：丢弃迟到结果，不再消费标记。
        if (ProtocolHttpBridge.isTerminal(callback)) {
            ci.cancel();
            return;
        }
        if (ProtocolHttpBridge.isInactive(callback)
                || ((Client) (Object) this).shouldStopChat(callback.getMaid())) {
            ci.cancel();
            return;
        }
        // 统计包装先解包：协议归一化只识别自己的包装类型，成功状态与归一化一律使用 delegate。
        TokenRequestContext statsContext = null;
        HttpResponse<String> delegate = response;
        if (response instanceof TokenStatsHttpResponse statsResponse) {
            statsContext = statsResponse.context();
            delegate = statsResponse.delegate();
        }
        boolean successful = throwable == null && delegate != null
                && delegate.statusCode() >= 200 && delegate.statusCode() < 300;
        if (successful && !ProtocolBridge.isNormalized(delegate)) {
            try {
                HttpResponse<String> normalized = ProtocolBridge.normalizeAggregated(this.site, delegate, callback);
                if (normalized != delegate) {
                    // 归一化返回新响应：用同一快照重新包装后递归进入原 handle，外层不再记账
                    this.handle(callback, statsContext == null ? normalized
                            : new TokenStatsHttpResponse(statsContext, normalized), throwable, request);
                    ci.cancel();
                    return;
                }
            } catch (ProtocolException e) {
                tlmAgentTweaks$recordUsage(statsContext, delegate, throwable);
                ProtocolHttpBridge.dispatchFailure(callback, request,
                        new Throwable("Protocol response decode failed: " + e.getMessage(), e),
                        ErrorCode.JSON_DECODE_ERROR);
                ci.cancel();
                return;
            } catch (Throwable t) {
                tlmAgentTweaks$recordUsage(statsContext, delegate, throwable);
                ProtocolHttpBridge.dispatchFailure(callback, request,
                        new Throwable("Protocol response decode failed", t),
                        ErrorCode.JSON_DECODE_ERROR);
                ci.cancel();
                return;
            }
        }
        tlmAgentTweaks$recordUsage(statsContext, delegate, throwable);
    }

    private void tlmAgentTweaks$recordUsage(TokenRequestContext context, HttpResponse<String> response, Throwable throwable) {
        try {
            TokenStatsService.onHttpResponse(context, response, throwable);
        } catch (Throwable t) {
            // 统计绝不能影响聊天流程
            TlmAgentTweaks.LOGGER.warn("token 用量统计记录失败（已忽略）", t);
        }
    }
}

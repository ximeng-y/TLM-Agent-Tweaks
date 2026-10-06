package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolHttpBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.http.HttpRequest;

/**
 * 覆盖父类尚未接管的传输/HTTP/JSON 失败入口：{@code LLMClient.handleResponse}
 * 内的 {@code LLMCallback.onFailure} 调用统一重定向到本 mod 的失败派发，
 * 使「不调用父类」的自定义回调（内部规划、历史压缩等）也能可靠释放整轮预算。
 * <p>
 * {@code handleResponse} 是 {@code LLMClient} 的默认方法，本工程内唯一实现类是
 * {@link LLMOpenAIClient}（未覆写该方法）；注入落在接口默认方法的字节码上，
 * 因此对所有（含将来）实现生效。
 */
@Mixin(value = LLMOpenAIClient.class, remap = false)
public abstract class LLMClientMixin {

    /**
     * 传输异常、HTTP 非成功状态、JSON 解析失败三处 {@link LLMCallback#onFailure}
     * 统一进入失败派发；保留原异常与错误码，原失败回调体在该回调对象上原样执行。
     */
    @Redirect(method = "handleResponse", at = @At(value = "INVOKE",
            target = "com/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback.onFailure"
                    + "(Ljava/net/http/HttpRequest;Ljava/lang/Throwable;I)V"))
    private void tlmAgentTweaks$redirectHandleResponseFailure(LLMCallback callback, HttpRequest request,
                                                              Throwable throwable, int errorCode) {
        ProtocolHttpBridge.dispatchFailure(callback, request, throwable, errorCode);
    }
}
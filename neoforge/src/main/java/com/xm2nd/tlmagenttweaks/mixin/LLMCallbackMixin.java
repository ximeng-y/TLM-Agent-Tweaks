package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolHttpBridge;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolRetryBudget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpRequest;

/**
 * 在 TLM 回调的业务终态处结束整轮时间预算并清理请求登记。
 * <p>
 * 只做本 mod 自身的清理，不改变 TLM 任何行为，也不取消在途请求：
 * 取消由生命周期（实体离场 / 停服）负责，否则一次普通失败会被误判为取消而丢掉错误提示。
 */
@Mixin(value = LLMCallback.class, remap = false)
public abstract class LLMCallbackMixin {

    /** 对话正常结束（写历史、给气泡、播报均在其后由 TLM 完成）。 */
    @Inject(method = "onSuccess", at = @At("HEAD"))
    private void tlmAgentTweaks$endConversation(ResponseChat responseChat, CallbackInfo ci) {
        end();
    }

    /** 对话失败收尾。 */
    @Inject(method = "onFailure", at = @At("HEAD"))
    private void tlmAgentTweaks$endConversationOnFailure(HttpRequest request, Throwable throwable,
                                                        int errorCode, CallbackInfo ci) {
        end();
    }

    /**
     * 无论成功还是失败，本轮对话都已结束：释放预算并清掉取消标记，
     * 避免回调对象长期被这两个集合引用。
     */
    private void end() {
        LLMCallback self = (LLMCallback) (Object) this;
        ProtocolRetryBudget.release(self);
        ProtocolHttpBridge.clearCancelled(self);
    }
}

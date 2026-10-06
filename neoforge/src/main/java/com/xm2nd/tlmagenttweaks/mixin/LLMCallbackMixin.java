package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolHttpBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpRequest;

/**
 * TLM 工具执行等直接调用父类成功/失败的路径上的补充收尾入口。
 * <p>
 * 主要终态已由公共响应调用点（统一成功/失败派发）负责；本注入只是兜底：
 * 那些走了 {@code super.onSuccess} / {@code super.onFailure} 的路径再次进入父类时，
 * 重复清理必须无副作用（{@link ProtocolHttpBridge#tryFinish} 竞争认领，幂等）。
 * 正常派发已提前完成清理，父类收尾不再承担分散的预算释放职责。
 * <p>
 * 单状态终态语义：
 * <ul>
 *   <li>显式/生命周期取消优先于超时：已超时后又取消仍按取消处理，迟到结果静默丢弃；</li>
 *   <li>超时后只放行超时收尾主动派发的那一次失败（许可异常对象），其余迟到失败一律丢弃；</li>
 *   <li>正常完成（{@code finished}）不在此截断——成功派发已认领 {@code finished} 后，
 *       真实的写历史/气泡逻辑还必须执行，这里只做一次幂等收尾。</li>
 * </ul>
 */
@Mixin(value = LLMCallback.class, remap = false)
public abstract class LLMCallbackMixin {

    /** 对话正常结束（写历史、给气泡、播报均在其后由 TLM 完成）。 */
    @Inject(method = "onSuccess", at = @At("HEAD"), cancellable = true)
    private void tlmAgentTweaks$endConversation(ResponseChat responseChat, CallbackInfo ci) {
        if (ProtocolHttpBridge.wasTimedOut((LLMCallback) (Object) this)) {
            ci.cancel();
            return;
        }
        if (ProtocolHttpBridge.wasCancelled((LLMCallback) (Object) this)) {
            // 显式取消后的迟到成功：不写历史、不上屏、不产生任何可见行为。
            ci.cancel();
            return;
        }
        // 直接调用父类成功：兜底收尾。重复进入无副作用。
        ProtocolHttpBridge.tryFinish((LLMCallback) (Object) this);
    }

    /**
     * 对话失败收尾。
     * <p>
     * 整轮超时只允许主动发出的那一次失败进入父类；其余迟到失败一律丢弃。
     * 显式取消后的父类失败也被静默截断，不显示红字。
     * 父类这里的许可检查不消耗许可——唯一一次超时派发结束后由超时收尾关闭。
     */
    @Inject(method = "onFailure", at = @At("HEAD"), cancellable = true)
    private void tlmAgentTweaks$endConversationOnFailure(HttpRequest request, Throwable throwable,
                                                         int errorCode, CallbackInfo ci) {
        if (ProtocolHttpBridge.wasCancelled((LLMCallback) (Object) this)) {
            ci.cancel();
            return;
        }
        if (ProtocolHttpBridge.wasTimedOut((LLMCallback) (Object) this)
                && !ProtocolHttpBridge.acceptFailure((LLMCallback) (Object) this, throwable)) {
            ci.cancel();
            return;
        }
        ProtocolHttpBridge.tryFinish((LLMCallback) (Object) this);
    }
}
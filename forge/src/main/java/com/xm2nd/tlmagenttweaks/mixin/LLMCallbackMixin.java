package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.chat.ChatVisibilityService;
import com.xm2nd.tlmagenttweaks.chat.NormalChatOriginAccess;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolHttpBridge;
import com.xm2nd.tlmagenttweaks.protocol.stream.ThinkingFailureNotice;
import com.xm2nd.tlmagenttweaks.thinking.ThinkingDisplayService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.http.HttpRequest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * TLM 工具执行等直接调用父类成功/失败的路径上的补充收尾入口。
 * <p>
 * 主要终态已由公共响应调用点（统一成功/失败派发）负责；本注入只是兜底：
 * 那些走了 {@code super.onSuccess} / {@code super.onFailure} 的路径再次进入父类时，
 * 重复清理必须无副作用（{@link ProtocolHttpBridge#tryFinish} 竞争认领，幂等）。
 * 正常派发已提前完成清理，父类收尾不再承担分散的预算释放职责。
 * <p>
 * 另外在 {@code onSingleCall} 真正执行工具前切回原生提示阶段：该位置在原工具提示刷新之后、
 * 真实工具执行之前，因此「模型返回了 tool_call 分片」不会被误当成工具已经开始执行。
 * 本注入不替换工具调用，也不改变其 future 与续接逻辑。
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
public abstract class LLMCallbackMixin implements NormalChatOriginAccess {

    @Shadow
    protected EntityMaid maid;

    @Shadow
    protected long waitingChatBubbleId;

    /** 正式普通对话来源：仅由 normalChat 的真实派发点写入，默认无来源。 */
    @Unique
    private UUID tlmAgentTweaks$normalChatOrigin;

    @Override
    public void tlmAgentTweaks$setNormalChatOrigin(UUID playerId) {
        this.tlmAgentTweaks$normalChatOrigin = playerId;
    }

    @Override
    public UUID tlmAgentTweaks$takeNormalChatOrigin() {
        UUID origin = this.tlmAgentTweaks$normalChatOrigin;
        this.tlmAgentTweaks$normalChatOrigin = null;
        return origin;
    }

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
     * 普通对话最终成功的公开回复钩子。
     * <p>
     * 挂在最后正常返回点：空白回复早退走 onFailure、超时/取消的迟到成功在 HEAD 被取消，
     * 两者都不会到达此处，自然不进入公开广播。仅携带正式普通来源标记的回调才处理，
     * 工具子请求、摘要、人设生成与 Self-Talk 回调没有该标记，不会被公开。
     */
    @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
            at = @At("TAIL"))
    private void tlmAgentTweaks$broadcastNormalReply(ResponseChat responseChat, CallbackInfo ci) {
        UUID origin = this.tlmAgentTweaks$takeNormalChatOrigin();
        if (origin == null) {
            return;
        }
        ChatVisibilityService.onNormalReplySuccess(this.maid, origin, responseChat.getChatText());
    }

    /**
     * 对话失败收尾。
     * <p>
     * 整轮超时只允许主动发出的那一次失败进入父类；其余迟到失败一律丢弃。
     * 显式取消后的父类失败也被静默截断，不显示红字。
     * 父类这里的许可检查不消耗许可——唯一一次超时派发结束后由超时收尾关闭。
     * <p>
     * 「上游拒绝思考参数」类失败改用预设提示替换原始英文报错（issue #3），
     * 等待气泡的清理由预设提示自己完成。
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
        if (ThinkingFailureNotice.shouldIntercept((LLMCallback) (Object) this, throwable)) {
            ProtocolHttpBridge.tryFinish((LLMCallback) (Object) this);
            ThinkingFailureNotice.notify((LLMCallback) (Object) this,
                    this.maid, this.waitingChatBubbleId, throwable);
            ci.cancel();
            return;
        }
        ProtocolHttpBridge.tryFinish((LLMCallback) (Object) this);
    }

    /**
     * 工具即将真正执行：等待气泡副文本改由原生工具提示占用，思考链让位。
     * <p>
     * 注入点在 {@code ITool.onCallAsync} 调用之前，即工具提示刷新之后、真实执行之前；
     * 使用该方法当前实际使用的 {@code callback}（工具批次续接时可能已换成下一个回调），
     * 因此展示跟随的是本次工具执行真正归属的那个会话。
     * <p>
     * 目标方法返回 {@code CompletableFuture<LLMCallback>}，因此回调类型必须是
     * {@link CallbackInfoReturnable}：Mixin 在应用注入时按描述符校验，返回类型不匹配会直接
     * 抛 {@code InvalidInjectionException} 而使目标类加载失败。本注入不读也不改返回值。
     */
    @Inject(method = "onSingleCall", at = @At(value = "INVOKE",
            target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/agent/tool/ITool;"
                    + "onCallAsync(Ljava/lang/String;Ljava/lang/Object;"
                    + "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;"
                    + "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;)"
                    + "Ljava/util/concurrent/CompletableFuture;"))
    private void tlmAgentTweaks$onToolExecutionStart(ToolCall toolCall, LLMCallback callback, LLMClient client,
                                                     CallbackInfoReturnable<CompletableFuture<LLMCallback>> cir) {
        ThinkingDisplayService.onToolExecutionStart(callback);
    }
}
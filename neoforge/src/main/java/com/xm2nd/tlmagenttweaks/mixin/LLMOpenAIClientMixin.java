package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import com.xm2nd.tlmagenttweaks.stats.TokenStatsService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 在 TLM 的 OpenAI 兼容客户端处理响应前记录 token 用量。
 * <p>
 * TLM 自身只解析 total_tokens（无缓存明细），这里直接解析原始响应体，
 * 覆盖所有经 LLMOpenAIClient 的请求（普通聊天/自言自语/互聊/自动人设/知识库/历史压缩）。
 * Self-Talk 未注入此类，双方无注入冲突。
 */
@Mixin(value = LLMOpenAIClient.class, remap = false)
public abstract class LLMOpenAIClientMixin {

    @Shadow
    @Final
    protected LLMOpenAISite site;

    @Inject(method = "handle", at = @At("HEAD"))
    private void tlmAgentTweaks$recordUsage(LLMCallback callback, HttpResponse<String> response,
                                            Throwable throwable, HttpRequest request, CallbackInfo ci) {
        try {
            TokenStatsService.onLlmResponse(callback, this.site, response, throwable);
        } catch (Throwable t) {
            // 统计绝不能影响聊天流程
            TlmAgentTweaks.LOGGER.warn("token 用量统计记录失败（已忽略）", t);
        }
    }
}

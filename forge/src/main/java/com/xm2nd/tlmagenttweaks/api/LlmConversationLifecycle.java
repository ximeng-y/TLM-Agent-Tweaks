package com.xm2nd.tlmagenttweaks.api;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.xm2nd.tlmagenttweaks.protocol.stream.ProtocolHttpBridge;

/**
 * 对话生命周期公开门面：供附属 mod 在业务侧对一次 LLM 对话作废或收尾。
 * <p>
 * 所有方法都是薄门面，委托 {@link ProtocolHttpBridge}，不暴露内部 Map、计时器或传输句柄。
 * 全部按**具体回调实例**生效：作废只影响传进来的那一个回调，不影响同一女仆的其它对话。
 *
 * <h2>何时调用</h2>
 * <ul>
 *   <li>请求被业务判定为作废（如互聊被主人插话），且不再期待任何结果：调用 {@link #cancel}；</li>
 *   <li>业务自行结束、不再续接、也不再走成功/失败回调：调用 {@link #complete}。</li>
 * </ul>
 * 正常走成功/失败回调的请求不需要调用本类：Agent-Tweaks 在公共响应调用点统一收尾。
 */
public final class LlmConversationLifecycle {

    private LlmConversationLifecycle() {
    }

    /**
     * 业务明确结束且不会再续接时，关闭该回调整轮登记。
     * 幂等；不调用业务回调、不影响同一女仆的其它对话。
     *
     * @param callback 需要结束整轮登记的回调；{@code null} 为无操作
     */
    public static void complete(LLMCallback callback) {
        ProtocolHttpBridge.finishConversation(callback);
    }

    /**
     * 静默作废该回调：取消 HTTP/排队/重试/deadline、释放预算，禁止后续重发与结果派发。
     * 幂等；即使当前没有在途登记（如取消早于发送）也会保留取消标记，迟到结果被丢弃。
     * 不调用业务回调；不代替业务清理工具历史、pending、等待气泡或互聊链。
     *
     * @param callback 要作废的回调；{@code null} 为无操作
     */
    public static void cancel(LLMCallback callback) {
        ProtocolHttpBridge.cancel(callback);
    }

    /**
     * 查询该回调是否被显式/生命周期取消（业务 {@link #cancel}、实体离场、停服）。
     * 正常完成与真实超时不视为显式取消。
     *
     * @param callback 要查询的回调；{@code null} 返回 {@code false}
     */
    public static boolean isCancelled(LLMCallback callback) {
        return ProtocolHttpBridge.isCancelled(callback);
    }
}
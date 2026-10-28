package com.xm2nd.tlmagenttweaks.chat;

import java.util.UUID;

/**
 * 普通对话来源标记：附在正式普通聊天回调对象上的 duck 接口。
 * <p>
 * 只有 {@code MaidAIChatManager.normalChat} 的真实派发点会写入来源 UUID；
 * 工具子请求、摘要、人设生成和 Self-Talk 回调不会经过该标记点，不自动继承标记。
 * 最终成功时取出并清除，避免同一个最终成功重复广播。
 */
public interface NormalChatOriginAccess {

    void tlmAgentTweaks$setNormalChatOrigin(UUID playerId);

    /** 取出并清除来源标记；无来源时返回 {@code null}。 */
    UUID tlmAgentTweaks$takeNormalChatOrigin();
}

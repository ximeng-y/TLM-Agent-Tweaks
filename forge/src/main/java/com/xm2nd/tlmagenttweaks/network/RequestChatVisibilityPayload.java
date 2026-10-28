package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：请求当前「普通对话全局可见」的权威状态（全局开关、本人私密偏好、管理员权限）。
 * 无业务字段，服务端按真实网络上下文玩家返回。
 */
public record RequestChatVisibilityPayload() {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "request_chat_visibility");

    public void write(FriendlyByteBuf buf) {
    }

    public static RequestChatVisibilityPayload read(FriendlyByteBuf buf) {
        return new RequestChatVisibilityPayload();
    }
}

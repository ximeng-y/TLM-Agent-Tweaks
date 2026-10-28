package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：管理员保存「普通对话全局可见」开关。
 * 服务端再次校验 OP 权限（等级 2），无权限时不修改配置，仅回带真实状态。
 */
public record SaveGlobalChatVisibilityPayload(boolean globalVisible) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "save_global_chat_visibility");

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(globalVisible);
    }

    public static SaveGlobalChatVisibilityPayload read(FriendlyByteBuf buf) {
        return new SaveGlobalChatVisibilityPayload(buf.readBoolean());
    }
}

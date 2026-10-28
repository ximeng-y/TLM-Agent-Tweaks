package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：保存本人「我的对话设为私密」偏好。
 * 身份只能来自真实网络上下文玩家，不接受 UUID 或其他玩家身份字段。
 */
public record SaveChatPrivacyPayload(boolean privateChat) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "save_chat_privacy");

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(privateChat);
    }

    public static SaveChatPrivacyPayload read(FriendlyByteBuf buf) {
        return new SaveChatPrivacyPayload(buf.readBoolean());
    }
}

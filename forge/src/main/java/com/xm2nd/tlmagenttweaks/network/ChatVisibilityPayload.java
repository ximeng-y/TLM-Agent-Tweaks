package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：全局可见权威状态快照。
 * 只回应请求或保存的玩家；页面收到后按服务端真实状态回显。
 */
public record ChatVisibilityPayload(boolean globalVisible, boolean privateChat,
                                    boolean canManageGlobal) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "chat_visibility");

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(globalVisible);
        buf.writeBoolean(privateChat);
        buf.writeBoolean(canManageGlobal);
    }

    public static ChatVisibilityPayload read(FriendlyByteBuf buf) {
        return new ChatVisibilityPayload(buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }
}

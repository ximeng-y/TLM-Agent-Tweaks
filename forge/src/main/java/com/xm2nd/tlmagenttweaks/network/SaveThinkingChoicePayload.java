package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：玩家在思考选择界面确认的决定。
 * <p>
 * 服务端会再次校验该模型的管理员配置（允许开关/允许的强度），非法选择直接丢弃。
 */
public record SaveThinkingChoicePayload(int entityId, String siteId, String modelId,
                                        boolean enabled, String levelId) {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "save_thinking_choice");

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeUtf(siteId);
        buf.writeUtf(modelId);
        buf.writeBoolean(enabled);
        buf.writeUtf(levelId);
    }

    public static SaveThinkingChoicePayload read(FriendlyByteBuf buf) {
        return new SaveThinkingChoicePayload(buf.readInt(), buf.readUtf(),
                buf.readUtf(), buf.readBoolean(), buf.readUtf());
    }
}

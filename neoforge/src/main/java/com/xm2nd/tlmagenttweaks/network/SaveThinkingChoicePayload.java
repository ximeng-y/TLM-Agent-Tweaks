package com.xm2nd.tlmagenttweaks.network;

import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：玩家在思考选择界面确认的决定。
 * <p>
 * 服务端会再次校验该模型的管理员配置（允许开关/允许的强度），非法选择直接丢弃。
 */
public record SaveThinkingChoicePayload(int entityId, String siteId, String modelId,
                                        boolean enabled, String levelId) implements CustomPacketPayload {

    public static final Type<SaveThinkingChoicePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TlmAgentTweaks.MOD_ID, "save_thinking_choice"));

    public static final StreamCodec<ByteBuf, SaveThinkingChoicePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SaveThinkingChoicePayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            return new SaveThinkingChoicePayload(friendly.readInt(), friendly.readUtf(),
                    friendly.readUtf(), friendly.readBoolean(), friendly.readUtf());
        }

        @Override
        public void encode(ByteBuf buf, SaveThinkingChoicePayload payload) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeInt(payload.entityId);
            friendly.writeUtf(payload.siteId);
            friendly.writeUtf(payload.modelId);
            friendly.writeBoolean(payload.enabled);
            friendly.writeUtf(payload.levelId);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

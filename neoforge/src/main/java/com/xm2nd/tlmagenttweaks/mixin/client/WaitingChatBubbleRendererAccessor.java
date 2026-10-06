package com.xm2nd.tlmagenttweaks.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.implement.WaitingChatBubbleRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只读访问原生等待气泡 renderer 已算好的标题与图标。
 * <p>
 * 替代 renderer 直接复用这两项，其余（宽度、高度、旋转图标、背景纹理）由本 mod 自行重算，
 * 因此无需访问、也绝不修改服务端气泡数据或 renderer 缓存。
 */
@Mixin(value = WaitingChatBubbleRenderer.class, remap = false)
public interface WaitingChatBubbleRendererAccessor {

    @Accessor("text")
    Component tlmAgentTweaks$title();

    @Accessor("icon")
    ResourceLocation tlmAgentTweaks$icon();
}

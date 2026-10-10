package com.xm2nd.tlmagenttweaks.client.thinking;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.EntityGraphics;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.IChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.implement.WaitingChatBubbleRenderer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 思考阶段的本次绘制 renderer：布局与原生等待气泡完全一致，只把副文本换成滚动的思考行。
 * <p>
 * 本类**不写入** {@code WaitingChatBubbleData} 的 renderer 缓存：只在本次绘制中替代，
 * 工具阶段、开关关闭与状态结束时原 renderer 自然恢复。
 * <p>
 * 固定宽度单行窗口：未填满时左对齐；溢出后从行首丢弃屏外字符，使最新内容靠右显示。
 * 布局从思考开始即预留（即使当前还没有思考文本），避免每个增量改变气泡背景尺寸。
 */
public class ThinkingBubbleRenderer implements IChatBubbleRenderer {

    /** 副文本缩放与颜色沿用原生等待气泡。 */
    private static final float SECONDARY_TEXT_SCALE = 0.5f;
    private static final int SECONDARY_TEXT_COLOR = 0x666666;
    /** 图标与文本起点占用的宽度，与原生 {@code getWidth()} 的 +18 对应。 */
    private static final int ICON_TEXT_OFFSET = 18;
    /** 思考窗口宽度（气泡局部坐标单位）；缩放前字体测量宽度为其 2 倍。 */
    private static final int WINDOW_WIDTH = 96;
    private static final int WINDOW_FONT_WIDTH = Math.round(WINDOW_WIDTH / SECONDARY_TEXT_SCALE);

    private final Font font;
    private final ResourceLocation bg;
    private final ResourceLocation icon;
    private final Component title;
    private final int height;

    /** 当前思考行；渲染时按实际字体宽度裁剪出可见后缀。 */
    private String line = "";

    public ThinkingBubbleRenderer(WaitingChatBubbleRenderer original, Component title, ResourceLocation icon) {
        this.font = Minecraft.getInstance().font;
        this.bg = original.getBackgroundTexture();
        this.title = title;
        this.icon = icon;
        // 高度沿用原生双行计算：主文本行高 + 缩放后的副文本行高 + 3。
        this.height = font.lineHeight + Math.round(font.lineHeight * SECONDARY_TEXT_SCALE) + 3;
    }

    /** 快照变化时更新当前行；裁剪在渲染时按实际字体宽度一次算出，不做逐帧历史遍历。 */
    public void update(String line) {
        this.line = line == null ? "" : line;
    }

    @Override
    public int getHeight() {
        return this.height;
    }

    @Override
    public int getWidth() {
        // 气泡总宽度：图标与文本起点 + max(原标题宽度, 固定思考窗口宽度)。
        return ICON_TEXT_OFFSET + Math.max(this.font.width(this.title), WINDOW_WIDTH);
    }

    @Override
    public ResourceLocation getBackgroundTexture() {
        return this.bg;
    }

    @Override
    public void render(EntityMaidRenderer renderer, EntityGraphics graphics) {
        graphics.getPoseStack().pushPose();
        float time = (Util.getMillis() % 3600);
        graphics.getPoseStack().translate(8, this.height / 2f, 0);
        graphics.getPoseStack().mulPose(Axis.ZP.rotationDegrees(time));
        graphics.blit(this.icon, -8, -8, 0, 0, 16, 16, 16, 16);
        graphics.getPoseStack().popPose();

        // 标题的绘制位置与原生等待气泡一致。
        graphics.drawString(this.font, this.title, ICON_TEXT_OFFSET, 2, 0x000000, false);

        String visible = visibleSuffix();
        if (visible.isEmpty()) {
            // 尚无思考文本：只有等待标题、图标及空的副文本区域。
            return;
        }
        int scaledWidth = Math.round(this.font.width(visible) * SECONDARY_TEXT_SCALE);
        // 是否溢出要按**整行**判断：截取出的可见后缀本身当然装得下，用它判断永远不会溢出。
        // 未溢出时从窗口左侧开始填充（新字逐个向右延伸）；溢出后只留可见后缀并右对齐，
        // 使最新内容贴右。否则第一个字就会贴在右边，与约定相反。
        boolean overflowed = this.font.width(this.line) > WINDOW_FONT_WIDTH;
        float x = overflowed ? Math.max(0, WINDOW_WIDTH - scaledWidth) : 0;

        graphics.getPoseStack().pushPose();
        graphics.getPoseStack().translate(ICON_TEXT_OFFSET, this.font.lineHeight + 1, 0);
        graphics.getPoseStack().scale(SECONDARY_TEXT_SCALE, SECONDARY_TEXT_SCALE, 1);
        graphics.drawString(this.font, Component.literal(visible),
                Math.round(x / SECONDARY_TEXT_SCALE), 5, SECONDARY_TEXT_COLOR, false);
        graphics.getPoseStack().popPose();
    }

    /**
     * 按实际字体宽度取「完整可见后缀」：从行尾向前累积到刚超过窗口宽度时停手，
     * 只保留落在窗口内的那一段。屏外字符不绘制，也不使用世界空间裁剪。
     * <p>
     * 未溢出时返回整行。
     */
    private String visibleSuffix() {
        if (this.line.isEmpty()) {
            return "";
        }
        if (this.font.width(this.line) <= WINDOW_FONT_WIDTH) {
            return this.line;
        }
        int start = this.line.length();
        while (start > 0) {
            int candidate = start - 1;
            char c = this.line.charAt(candidate);
            if (Character.isLowSurrogate(c) && candidate > 0
                    && Character.isHighSurrogate(this.line.charAt(candidate - 1))) {
                // 与前面的高位代理一起保留，避免切断代理对。
                candidate--;
            }
            if (this.font.width(this.line.substring(candidate)) > WINDOW_FONT_WIDTH) {
                break;
            }
            start = candidate;
        }
        return this.line.substring(start);
    }

    @Nullable
    public ResourceLocation icon() {
        return this.icon;
    }
}

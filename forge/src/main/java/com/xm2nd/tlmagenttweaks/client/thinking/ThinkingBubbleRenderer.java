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
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;

import javax.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 思考阶段的本次绘制 renderer：布局与原生等待气泡完全一致，只把副文本换成滚动的思考行。
 * <p>
 * 本类**不写入** {@code WaitingChatBubbleData} 的 renderer 缓存：只在本次绘制中替代，
 * 工具阶段、开关关闭与状态结束时原 renderer 自然恢复。
 * <p>
 * 固定宽度单行窗口：未填满时左对齐；溢出后从行首丢弃屏外字符，使最新内容靠右显示。
 * 布局从思考开始即预留（即使当前还没有思考文本），避免每个增量改变气泡背景尺寸。
 * <p>
 * 实例由 {@code ChatBubbleRendererMixin} 逐帧重建，跨帧复用的测宽与裁剪结果放在类级
 * 缓存里：相同行内容（且字体未变）不重复测宽；裁剪按切点单调性二分，不再逐字符重测
 * 完整后缀。缓存只在渲染线程访问，无需并发容器。
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

    /** 测宽缓存条目上限：多个女仆同时思考时互不冲刷。 */
    private static final int MEASURE_CACHE_SIZE = 8;

    /** 缓存键：行内容 + 字体实例。字体实例不随字体资源重载更换，字宽变化由重载回调显式清空缓存来失效。 */
    private record MeasureKey(String line, Font font) {
    }

    /** 一次测宽与裁剪的全部结果：整行宽度、可见后缀及其宽度。 */
    private record MeasureResult(int fullWidth, String visible, int visibleWidth) {
    }

    /** 跨帧存活的测宽缓存（访问序 LRU）；渲染线程独占访问。 */
    private static final Map<MeasureKey, MeasureResult> MEASURE_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MeasureKey, MeasureResult> eldest) {
            return size() > MEASURE_CACHE_SIZE;
        }
    };

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

    /** 快照变化时更新当前行；测宽与裁剪结果按「行内容 + 字体」跨帧缓存复用。 */
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

        MeasureResult measured = measured();
        String visible = measured.visible();
        if (visible.isEmpty()) {
            // 尚无思考文本：只有等待标题、图标及空的副文本区域。
            return;
        }
        int scaledWidth = Math.round(measured.visibleWidth() * SECONDARY_TEXT_SCALE);
        // 是否溢出要按**整行**判断：截取出的可见后缀本身当然装得下，用它判断永远不会溢出。
        // 未溢出时从窗口左侧开始填充（新字逐个向右延伸）；溢出后只留可见后缀并右对齐，
        // 使最新内容贴右。否则第一个字就会贴在右边，与约定相反。
        boolean overflowed = measured.fullWidth() > WINDOW_FONT_WIDTH;
        float x = overflowed ? Math.max(0, WINDOW_WIDTH - scaledWidth) : 0;

        graphics.getPoseStack().pushPose();
        graphics.getPoseStack().translate(ICON_TEXT_OFFSET, this.font.lineHeight + 1, 0);
        graphics.getPoseStack().scale(SECONDARY_TEXT_SCALE, SECONDARY_TEXT_SCALE, 1);
        graphics.drawString(this.font, Component.literal(visible),
                Math.round(x / SECONDARY_TEXT_SCALE), 5, SECONDARY_TEXT_COLOR, false);
        graphics.getPoseStack().popPose();
    }

    /** 取当前行的测宽与裁剪结果；缓存未命中时才真正测量。 */
    private MeasureResult measured() {
        MeasureKey key = new MeasureKey(this.line, this.font);
        MeasureResult cached = MEASURE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        MeasureResult result = measure(this.line, this.font);
        MEASURE_CACHE.put(key, result);
        return result;
    }

    /** 字体等资源重载后显式清空测宽缓存，下一次绘制按新字形重新测量与裁剪。 */
    public static void clearMeasureCache() {
        MEASURE_CACHE.clear();
    }

    /** 在客户端资源重载监听注册事件里挂入清空回调：清空发生在重载 apply 阶段，位于字体应用之后。 */
    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                clearMeasureCache();
            }
        });
    }

    /** 测量整行宽度并裁出可见后缀；未溢出时可见后缀即整行。 */
    private static MeasureResult measure(String line, Font font) {
        int fullWidth = font.width(line);
        if (fullWidth <= WINDOW_FONT_WIDTH) {
            return new MeasureResult(fullWidth, line, fullWidth);
        }
        String visible = line.substring(clipStart(line, font));
        return new MeasureResult(fullWidth, visible, font.width(visible));
    }

    /**
     * 求装得下窗宽的最大可见后缀的起点：切点左移时后缀测宽单调不减（原版字体每个
     * 字形步进非负），因此对切点二分、每次仍用真实 {@code font.width} 测量，替代
     * 逐字符回退重测完整后缀的平方级路径，测宽语义与原实现一致。
     * 屏外字符不绘制，也不使用世界空间裁剪。
     */
    private static int clipStart(String line, Font font) {
        // width(substring(0)) > WINDOW_FONT_WIDTH 已由调用方确认，答案落在 [1, line.length()]。
        int low = 1;
        int high = line.length();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (font.width(line.substring(mid)) <= WINDOW_FONT_WIDTH) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        // 向右微调到安全切点；「§」序列切点附近的独立测宽存在少量非单调区间，
        // 微调同时兜底保证返回的切点必然装得下（右移只会更短更窄）。
        int start = low;
        while (start < line.length()
                && (!isValidCut(line, start) || font.width(line.substring(start)) > WINDOW_FONT_WIDTH)) {
            start++;
        }
        return start;
    }

    /** 切点是否安全：后缀不得以孤立低位代理开头，也不得切在「§」与其格式字符之间。 */
    private static boolean isValidCut(String line, int start) {
        if (Character.isLowSurrogate(line.charAt(start))
                && Character.isHighSurrogate(line.charAt(start - 1))) {
            return false;
        }
        return line.charAt(start - 1) != '\u00A7';
    }

    @Nullable
    public ResourceLocation icon() {
        return this.icon;
    }
}

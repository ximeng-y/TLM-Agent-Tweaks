package com.xm2nd.tlmagenttweaks.client.gui.stats;

import com.xm2nd.tlmagenttweaks.network.TokenStatsDataPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

import java.util.List;
import java.util.function.Consumer;

/**
 * 玩家选择下拉菜单：开合状态、行滚动、自绘浮层与点击命中。
 * 浮层抬升 z 坐标绘制，避免被底页文字穿透（#16 的要求）。
 */
public final class PlayerDropdown {

    private boolean open;
    private int scroll;

    public boolean isOpen() {
        return open;
    }

    public void close() {
        this.open = false;
    }

    /** 切换开合；打开时把选中玩家滚进可视区。 */
    public void toggle(int selectedIndex, int rankingSize, Button selector, int screenHeight) {
        this.open = !this.open;
        if (this.open) {
            this.scroll = Math.min(selectedIndex,
                    Math.max(0, rankingSize - menuRows(rankingSize, selector, screenHeight)));
        }
    }

    /** 滚轮滚动一行；返回 true 表示已消费。 */
    public boolean scrolled(double scrollY, int rankingSize, Button selector, int screenHeight) {
        this.scroll = Math.max(0, Math.min(rankingSize - menuRows(rankingSize, selector, screenHeight),
                this.scroll - (int) Math.signum(scrollY)));
        return true;
    }

    /**
     * 菜单开着时消费任意点击：点中菜单行则选中该玩家，随后一律关闭菜单。
     * 与原行为一致——开着的菜单吞掉本次点击，不落到下层组件。
     *
     * @return 恒为 true（菜单打开时的点击不继续传递）
     */
    public boolean clicked(double mouseX, double mouseY, int button,
                           List<TokenStatsDataPayload.RankEntry> ranking, Button selector,
                           int screenHeight, Consumer<String> onSelect) {
        int top = menuTop(selector);
        int rows = menuRows(ranking.size(), selector, screenHeight);
        if (button == 0 && mouseX >= selector.getX()
                && mouseX < selector.getX() + selector.getWidth()
                && mouseY >= top && mouseY < top + rows * 20) {
            int index = this.scroll + (int) ((mouseY - top) / 20);
            if (index >= 0 && index < ranking.size()) {
                onSelect.accept(ranking.get(index).uuid());
            }
        }
        this.open = false;
        return true;
    }

    public void render(GuiGraphics graphics, Font font, int screenHeight, int mouseX, int mouseY,
                       Button selector, List<TokenStatsDataPayload.RankEntry> ranking, String selectedUuid) {
        if (!this.open || ranking.isEmpty()) {
            return;
        }
        int left = selector.getX();
        int right = left + selector.getWidth();
        int top = menuTop(selector);
        int rows = menuRows(ranking.size(), selector, screenHeight);
        graphics.flush();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 350);
            graphics.fill(left, top, right, top + rows * 20, 0xFF202020);
            for (int i = 0; i < rows; i++) {
                TokenStatsDataPayload.RankEntry player = ranking.get(this.scroll + i);
                int y = top + i * 20;
                boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + 20;
                if (hover || player.uuid().equals(selectedUuid)) {
                    graphics.fill(left, y, right, y + 20, hover ? 0xFF555555 : 0xFF383838);
                }
                graphics.drawString(font, font.plainSubstrByWidth(player.name(), right - left - 12),
                        left + 6, y + 6, 0xFFFFFF);
            }
            if (ranking.size() > rows) {
                int thumbHeight = Math.max(4, rows * 20 * rows / ranking.size());
                int thumbTop = top + (rows * 20 - thumbHeight) * this.scroll / (ranking.size() - rows);
                graphics.fill(right - 2, thumbTop, right, thumbTop + thumbHeight, 0xFFAAAAAA);
            }
            graphics.flush();
        } finally {
            graphics.pose().popPose();
        }
    }

    private static int menuTop(Button selector) {
        return selector.getY() + selector.getHeight() + 2;
    }

    private static int menuRows(int rankingSize, Button selector, int screenHeight) {
        int top = menuTop(selector);
        return Math.min(rankingSize, Math.max(1, Math.min(8, (screenHeight - top - 8) / 20)));
    }
}

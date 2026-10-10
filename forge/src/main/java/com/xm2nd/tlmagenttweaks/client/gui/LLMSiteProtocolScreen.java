package com.xm2nd.tlmagenttweaks.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolUrls;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.protocol.UrlMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 轻量协议设置屏：只编辑协议、URL 语义和最终端点预览。
 * <p>
 * 所有值通过回调保留在原站点编辑屏的 Mixin 字段中，因此返回、resize
 * 或再次打开这里都不会把未保存的选择丢掉。站点编辑面板本身不再增加越界控件。
 */
@OnlyIn(Dist.CLIENT)
public class LLMSiteProtocolScreen extends Screen {
    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 200;
    private static final int OPTION_HEIGHT = 20;
    private static final int OPTION_GAP = 2;

    private final Screen parent;
    private final String siteId;
    private final Supplier<String> urlSupplier;
    private final BiConsumer<SiteProtocol, UrlMode> onApply;

    private SiteProtocol protocol;
    private UrlMode urlMode;
    private Menu openMenu = Menu.NONE;

    private final List<FlatColorButton> protocolOptions = new ArrayList<>();
    private final List<FlatColorButton> urlModeOptions = new ArrayList<>();

    private FlatColorButton protocolButton;
    private FlatColorButton urlModeButton;
    private int panelLeft;
    private int panelRight;
    private int panelTop;
    private int panelBottom;
    private int contentLeft;
    private int contentWidth;

    private enum Menu {
        NONE,
        PROTOCOL,
        URL_MODE
    }

    public LLMSiteProtocolScreen(Screen parent, String siteId, Supplier<String> urlSupplier,
                                 SiteProtocol protocol, UrlMode urlMode,
                                 BiConsumer<SiteProtocol, UrlMode> onApply) {
        super(Component.translatable("gui.tlm_agent_tweaks.llm.title"));
        this.parent = parent;
        this.siteId = siteId == null ? "" : siteId;
        this.urlSupplier = urlSupplier;
        this.protocol = protocol == null ? SiteProtocol.CHAT_COMPLETIONS : protocol;
        this.urlMode = urlMode == null ? UrlMode.EXACT : urlMode;
        this.onApply = onApply;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.protocolOptions.clear();
        this.urlModeOptions.clear();

        int panelWidth = Math.min(PANEL_WIDTH, Math.max(220, this.width - 24));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(150, this.height - 24));
        this.panelLeft = (this.width - panelWidth) / 2;
        this.panelRight = this.panelLeft + panelWidth;
        this.panelTop = Math.max(6, (this.height - panelHeight) / 2);
        this.panelBottom = this.panelTop + panelHeight;

        this.contentLeft = this.panelLeft + 20;
        this.contentWidth = panelWidth - 40;
        int selectorY = this.panelTop + 54;

        this.protocolButton = this.addRenderableWidget(new FlatColorButton(
                this.contentLeft, selectorY, this.contentWidth, OPTION_HEIGHT,
                this.protocolLabel(), button -> this.toggleMenu(Menu.PROTOCOL)));
        this.urlModeButton = this.addRenderableWidget(new FlatColorButton(
                this.contentLeft, selectorY + 30, this.contentWidth, OPTION_HEIGHT,
                this.urlModeLabel(), button -> this.toggleMenu(Menu.URL_MODE)));

        this.addRenderableWidget(new FlatColorButton(
                this.contentLeft, this.panelBottom - 30, this.contentWidth, OPTION_HEIGHT,
                Component.translatable("gui.tlm_agent_tweaks.llm.back"),
                button -> this.onClose()));

        for (SiteProtocol value : SiteProtocol.values()) {
            FlatColorButton option = this.addRenderableWidget(new FlatColorButton(
                    this.contentLeft, 0, this.contentWidth, OPTION_HEIGHT,
                    this.protocolName(value), button -> this.selectProtocol(value)));
            option.visible = false;
            this.protocolOptions.add(option);
        }
        for (UrlMode value : UrlMode.values()) {
            FlatColorButton option = this.addRenderableWidget(new FlatColorButton(
                    this.contentLeft, 0, this.contentWidth, OPTION_HEIGHT,
                    this.urlModeName(value), button -> this.selectUrlMode(value)));
            option.visible = false;
            this.urlModeOptions.add(option);
        }

        this.updateMenuLayout();
    }

    private void toggleMenu(Menu menu) {
        this.openMenu = this.openMenu == menu ? Menu.NONE : menu;
        this.updateMenuLayout();
    }

    private void selectProtocol(SiteProtocol value) {
        this.protocol = value;
        this.openMenu = Menu.NONE;
        this.applyCurrent();
        this.updateMenuLayout();
    }

    private void selectUrlMode(UrlMode value) {
        this.urlMode = value;
        this.openMenu = Menu.NONE;
        this.applyCurrent();
        this.updateMenuLayout();
    }

    private void applyCurrent() {
        if (this.onApply != null) {
            this.onApply.accept(this.protocol, this.urlMode);
        }
    }

    private void updateMenuLayout() {
        if (this.protocolButton == null || this.urlModeButton == null) {
            return;
        }
        this.protocolButton.setMessage(this.protocolLabel());
        this.urlModeButton.setMessage(this.urlModeLabel());
        this.positionOptions(this.protocolOptions, this.protocolButton, this.openMenu == Menu.PROTOCOL);
        this.positionOptions(this.urlModeOptions, this.urlModeButton, this.openMenu == Menu.URL_MODE);
    }

    private void positionOptions(List<FlatColorButton> options, FlatColorButton anchor, boolean visible) {
        if (options.isEmpty()) {
            return;
        }
        int listHeight = options.size() * OPTION_HEIGHT + (options.size() - 1) * OPTION_GAP;
        int below = anchor.getY() + anchor.getHeight() + OPTION_GAP;
        int above = anchor.getY() - OPTION_GAP - listHeight;
        int y = below + listHeight <= this.panelBottom - 8 ? below : above;
        y = Math.max(this.panelTop + 8, Math.min(y, this.panelBottom - 8 - listHeight));
        for (int i = 0; i < options.size(); i++) {
            FlatColorButton option = options.get(i);
            option.setY(y + i * (OPTION_HEIGHT + OPTION_GAP));
            option.visible = visible;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.openMenu != Menu.NONE) {
            // 菜单绘制在最上层，点击也必须优先交给它，不能由下面先注册的按钮截获。
            List<FlatColorButton> options = this.openMenu == Menu.PROTOCOL
                    ? this.protocolOptions : this.urlModeOptions;
            for (FlatColorButton option : options) {
                if (option.mouseClicked(mouseX, mouseY, button)) {
                    this.setFocused(option);
                    return true;
                }
            }
            this.openMenu = Menu.NONE;
            this.updateMenuLayout();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && this.openMenu != Menu.NONE) {
            this.openMenu = Menu.NONE;
            this.updateMenuLayout();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xC0101010);
        graphics.fillGradient(this.panelLeft, this.panelTop, this.panelRight,
                this.panelBottom, 0xD0202020, 0xD0202020);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.panelTop + 14, 0xFFF3EFE0);
        if (!this.siteId.isBlank()) {
            graphics.drawString(this.font, this.siteId, this.contentLeft, this.panelTop + 32, 0xFF777777);
        }
        graphics.drawString(this.font,
                Component.translatable("gui.tlm_agent_tweaks.llm.final_url"),
                this.contentLeft, this.panelTop + 120, 0xFF777777);

        UrlPreview preview = this.previewUrl();
        int previewColor = preview.valid() ? 0xFFB8B8B8 : 0xFFFF7777;
        graphics.drawString(this.font, this.truncate(preview.text(), this.contentWidth),
                this.contentLeft, this.panelTop + 134, previewColor);

        for (Renderable renderable : this.renderables) {
            if (this.protocolOptions.contains(renderable) || this.urlModeOptions.contains(renderable)) {
                continue;
            }
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }

        graphics.flush();

        if (this.openMenu != Menu.NONE) {
            List<FlatColorButton> menuOptions = this.openMenu == Menu.PROTOCOL
                    ? this.protocolOptions : this.urlModeOptions;
            int menuTop = menuOptions.get(0).getY();
            int menuBottom = menuOptions.get(menuOptions.size() - 1).getY() + OPTION_HEIGHT;

            graphics.pose().pushPose();
            try {
                graphics.pose().translate(0.0F, 0.0F, 350.0F);
                graphics.fill(this.contentLeft, menuTop, this.contentLeft + this.contentWidth,
                        menuBottom, 0xFF202020);
                for (FlatColorButton option : menuOptions) {
                    if (option.visible) {
                        option.render(graphics, mouseX, mouseY, partialTick);
                    }
                }
                graphics.flush();
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    private UrlPreview previewUrl() {
        String rawUrl = this.urlSupplier == null ? "" : this.urlSupplier.get();
        try {
            URI uri = ProtocolUrls.resolve(this.protocol, this.urlMode, rawUrl);
            return new UrlPreview(uri.toString(), true);
        } catch (ProtocolException e) {
            return new UrlPreview(Component.translatable(
                    "gui.tlm_agent_tweaks.llm.final_url_invalid", e.getMessage()).getString(), false);
        }
    }

    private String truncate(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = result.toString() + text.charAt(i) + suffix;
            if (this.font.width(candidate) > maxWidth) {
                break;
            }
            result.append(text.charAt(i));
        }
        return result + suffix;
    }

    private Component protocolLabel() {
        return Component.translatable("gui.tlm_agent_tweaks.llm.protocol", this.protocolName(this.protocol));
    }

    private Component urlModeLabel() {
        return Component.translatable("gui.tlm_agent_tweaks.llm.url_mode", this.urlModeName(this.urlMode));
    }

    private Component protocolName(SiteProtocol value) {
        return switch (value) {
            case CHAT_COMPLETIONS -> Component.translatable(
                    "gui.tlm_agent_tweaks.llm.protocol.chat_completions");
            case RESPONSES -> Component.translatable("gui.tlm_agent_tweaks.llm.protocol.responses");
            case ANTHROPIC_MESSAGES -> Component.translatable(
                    "gui.tlm_agent_tweaks.llm.protocol.anthropic_messages");
        };
    }

    private Component urlModeName(UrlMode value) {
        return switch (value) {
            case EXACT -> Component.translatable("gui.tlm_agent_tweaks.llm.url_mode.exact");
            case BASE -> Component.translatable("gui.tlm_agent_tweaks.llm.url_mode.base");
        };
    }

    @Override
    public void onClose() {
        this.applyCurrent();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record UrlPreview(String text, boolean valid) {
    }
}

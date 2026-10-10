package com.xm2nd.tlmagenttweaks.client.gui;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.ClientAvailableSitesSync;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.xm2nd.tlmagenttweaks.client.ClientPriceCache;
import com.xm2nd.tlmagenttweaks.network.RequestPriceTablePayload;
import com.xm2nd.tlmagenttweaks.network.SaveModelPricePayload;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * 模型价格编辑屏（仅管理员，服务端会二次鉴权）。
 * <p>
 * 固定站点 + 固定模型，只配置这一个模型的输入/输出/缓存命中/缓存写入价格；
 * 站点/模型是否存在于管理员同步的可用站点表（ClientAvailableSitesSync）决定
 * 保存/清除是否可用，未保存的站点/模型进入时只读并明确提示。
 */
@OnlyIn(Dist.CLIENT)
public class PriceConfigScreen extends Screen {

    private static final String[] CURRENCIES = {"cny", "usd"};
    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 220;
    private static final int CONTENT_MARGIN = 20;
    private static final int ROW_HEIGHT = 18;
    private static final int BOX_WIDTH = 110;

    private final Screen parent;
    private final String siteId;
    private final String model;

    private int currencyIndex;
    private int panelLeft;
    private int panelRight;
    private int panelTop;
    private int panelBottom;

    private FlatColorButton currencyButton;
    private FlatColorButton saveButton;
    private FlatColorButton clearButton;

    private EditBox[] priceBoxes = new EditBox[4];
    private Component statusMessage = Component.empty();
    private long statusTimestamp = -1;
    /** 用户是否在当前选择下手动编辑过（避免异步到达的价格缓存覆盖输入） */
    private boolean userEdited;
    private long consumedPriceVersion;

    private static final String[] FIELD_KEYS = {
            "gui.tlm_agent_tweaks.price.input",
            "gui.tlm_agent_tweaks.price.output",
            "gui.tlm_agent_tweaks.price.cache_read",
            "gui.tlm_agent_tweaks.price.cache_write"
    };

    public PriceConfigScreen(String siteId, String model, Screen parent) {
        super(Component.translatable("gui.tlm_agent_tweaks.price.title"));
        this.siteId = siteId == null ? "" : siteId;
        this.model = model == null ? "" : model;
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 拉取最新价格表（服务端会校验管理员；非管理员仅拿不到回包）
        TweakPackets.sendToServer(new RequestPriceTablePayload());
        if (this.font == null) {
            return;
        }

        int panelWidth = Math.min(PANEL_WIDTH, Math.max(220, this.width - 24));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(160, this.height - 20));
        this.panelLeft = (this.width - panelWidth) / 2;
        this.panelRight = this.panelLeft + panelWidth;
        this.panelTop = Math.max(6, (this.height - panelHeight) / 2);
        this.panelBottom = this.panelTop + panelHeight;

        int contentLeft = this.panelLeft + CONTENT_MARGIN;
        int contentRight = this.panelRight - CONTENT_MARGIN;

        // 重绘（resize 等）时保留同币种下已编辑的四输入值，不因重建清掉正在输入的价格
        String[] editedValues = this.userEdited && this.priceBoxes[0] != null ? readBoxValues() : null;

        // 货币独立顶行
        this.currencyButton = this.addRenderableWidget(new FlatColorButton(
                contentRight - 100, this.panelTop + 58, 100, 18,
                Component.translatable("gui.tlm_agent_tweaks.price.currency." + currentCurrency()),
                b -> {
                    this.currencyIndex = (this.currencyIndex + 1) % CURRENCIES.length;
                    this.userEdited = false;
                    refillBoxes();
                    updateCurrencyButton();
                }));

        // 四行价格：输入 / 输出 / 缓存命中 / 缓存写入
        int boxX = contentRight - BOX_WIDTH;
        for (int i = 0; i < 4; i++) {
            EditBox box = new EditBox(this.font, boxX, this.panelTop + 88 + i * ROW_HEIGHT, BOX_WIDTH, 16,
                    Component.translatable(FIELD_KEYS[i]));
            box.setMaxLength(16);
            box.setResponder(text -> this.userEdited = true);
            this.priceBoxes[i] = box;
            this.addRenderableWidget(box);
        }
        if (editedValues != null) {
            for (int i = 0; i < 4; i++) {
                this.priceBoxes[i].setValue(editedValues[i]);
            }
        } else {
            refillBoxes();
        }

        // 保存 / 清除 / 返回
        this.saveButton = this.addRenderableWidget(new FlatColorButton(
                contentLeft, this.panelBottom - 28, 80, 18,
                Component.translatable("gui.tlm_agent_tweaks.price.save"), b -> save(false)));
        this.clearButton = this.addRenderableWidget(new FlatColorButton(
                contentLeft + 88, this.panelBottom - 28, 80, 18,
                Component.translatable("gui.tlm_agent_tweaks.price.clear"), b -> save(true)));
        this.addRenderableWidget(new FlatColorButton(
                contentRight - 80, this.panelBottom - 28, 80, 18,
                Component.translatable("gui.tlm_agent_tweaks.stats.back"), b -> this.onClose()));
        updateButtons();
    }

    private String currentCurrency() {
        return CURRENCIES[this.currencyIndex];
    }

    private void updateCurrencyButton() {
        if (this.currencyButton != null) {
            this.currencyButton.setMessage(
                    Component.translatable("gui.tlm_agent_tweaks.price.currency." + currentCurrency()));
        }
    }

    private String[] readBoxValues() {
        String[] values = new String[4];
        for (int i = 0; i < 4; i++) {
            values[i] = this.priceBoxes[i] == null ? "" : this.priceBoxes[i].getValue();
        }
        return values;
    }

    /** 站点与模型必须存在于管理员配置的可用站点表（未保存/已删除模型不允许发保存包） */
    private boolean isModelAvailable() {
        return !this.model.isBlank()
                && ClientAvailableSitesSync.getClientLLMSites()
                .getOrDefault(this.siteId, Map.of()).containsKey(this.model);
    }

    private void updateButtons() {
        boolean available = isModelAvailable();
        if (this.saveButton != null) {
            this.saveButton.active = available;
        }
        if (this.clearButton != null) {
            this.clearButton.active = available;
        }
    }

    /** 从客户端价格缓存回填当前固定模型的输入框 */
    private void refillBoxes() {
        if (this.font == null || this.priceBoxes[0] == null) {
            return;
        }
        double[] prices = isModelAvailable()
                ? ClientPriceCache.getPrice(this.siteId, this.model, currentCurrency()) : null;
        for (int i = 0; i < 4; i++) {
            String text = prices == null ? "" : NumUtil.formatCost(prices[i]);
            this.priceBoxes[i].setValue(text);
        }
        this.userEdited = false;
    }

    private void save(boolean clear) {
        if (!isModelAvailable()) {
            showStatus(Component.translatable("gui.tlm_agent_tweaks.price.save_site_model_first"));
            return;
        }
        double[] values = new double[4];
        if (!clear) {
            for (int i = 0; i < 4; i++) {
                String text = this.priceBoxes[i].getValue().trim();
                try {
                    // 留空视为 0（未计价的部分）
                    values[i] = text.isEmpty() ? 0 : Double.parseDouble(text);
                    if (values[i] < 0 || Double.isNaN(values[i]) || Double.isInfinite(values[i])) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    showStatus(Component.translatable("gui.tlm_agent_tweaks.price.bad_number"));
                    return;
                }
            }
        }
        TweakPackets.sendToServer(new SaveModelPricePayload(
                this.siteId, this.model, currentCurrency(), values, clear));
        showStatus(Component.translatable("gui.tlm_agent_tweaks.price.sending"));
    }

    private void showStatus(Component message) {
        this.statusMessage = message;
        this.statusTimestamp = System.currentTimeMillis();
    }

    private String truncate(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        for (int i = text.length() - 1; i > 0; i--) {
            String candidate = text.substring(0, i) + suffix;
            if (this.font.width(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return suffix;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // 全屏灰暗遮罩 + 深灰居中面板，与协议设置页一致
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xC0101010);
        graphics.fillGradient(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom,
                0xD0202020, 0xD0202020);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.panelTop + 14, 0xFFF3EFE0);

        // 模型名显示在面板标题下方更低的独立位置
        Component modelLabel = Component.translatable("gui.tlm_agent_tweaks.price.model");
        int modelLabelX = this.panelLeft + CONTENT_MARGIN;
        int modelY = this.panelTop + 34;
        graphics.drawString(this.font, modelLabel, modelLabelX, modelY, 0xFF777777);
        int modelTextX = modelLabelX + this.font.width(modelLabel) + 8;
        int modelMaxWidth = Math.max(40, this.panelRight - CONTENT_MARGIN - modelTextX);
        String shownModel = this.model.isBlank() ? "—" : truncate(this.model, modelMaxWidth);
        graphics.drawString(this.font, shownModel, modelTextX, modelY,
                isModelAvailable() ? 0xFFB8B8B8 : 0xFFFF7777);

        // 货币行标题
        graphics.drawString(this.font, Component.translatable("gui.tlm_agent_tweaks.price.currency"),
                this.panelLeft + CONTENT_MARGIN, this.panelTop + 61, 0xFF777777);

        // 四行价格字段标题
        for (int i = 0; i < 4; i++) {
            graphics.drawString(this.font, Component.translatable(FIELD_KEYS[i]),
                    this.panelLeft + CONTENT_MARGIN, this.panelTop + 88 + i * ROW_HEIGHT + 3, 0xFF777777);
        }

        // 未保存站点/模型时持续提示；否则显示 2 秒临时状态
        if (!isModelAvailable()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.tlm_agent_tweaks.price.save_site_model_first"),
                    this.width / 2, this.panelTop + 178, 0xFFFF7777);
        } else if (System.currentTimeMillis() - this.statusTimestamp < 2000) {
            graphics.drawCenteredString(this.font, this.statusMessage,
                    this.width / 2, this.panelTop + 178, 0xFFFF7777);
        }

        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }

        // 长模型名悬停提示在全部控件之后绘制，多行拆分避免超出屏幕
        if (!this.model.isBlank() && this.font.width(this.model) > modelMaxWidth
                && mouseX >= modelTextX && mouseX <= modelTextX + this.font.width(shownModel)
                && mouseY >= modelY && mouseY <= modelY + 9) {
            graphics.renderTooltip(this.font,
                    this.font.split(Component.literal(this.model), Math.min(300, this.width - 16)),
                    mouseX, mouseY);
        }
    }

    @Override
    public void tick() {
        // 站点同步到表后随已有同步更新保存/清除可用状态
        updateButtons();
        // 价格表缓存异步到达时回填（用户未手动编辑时）
        if (!this.userEdited && ClientPriceCache.version() != this.consumedPriceVersion) {
            this.consumedPriceVersion = ClientPriceCache.version();
            refillBoxes();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

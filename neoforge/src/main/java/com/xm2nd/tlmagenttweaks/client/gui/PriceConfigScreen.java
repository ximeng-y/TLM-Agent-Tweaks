package com.xm2nd.tlmagenttweaks.client.gui;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.ClientAvailableSitesSync;
import com.xm2nd.tlmagenttweaks.client.ClientPriceCache;
import com.xm2nd.tlmagenttweaks.network.RequestPriceTablePayload;
import com.xm2nd.tlmagenttweaks.network.SaveModelPricePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型价格编辑屏（仅管理员，服务端会二次鉴权）。
 * <p>
 * 站点与模型列表均来自管理员配置、同步到客户端的可用站点表
 * （ClientAvailableSitesSync，与 LLM 模型选择弹窗同源），不从别处抓取。
 */
@OnlyIn(Dist.CLIENT)
public class PriceConfigScreen extends Screen {

    private static final String[] CURRENCIES = {"cny", "usd"};

    private final Screen parent;
    private final String initialSiteId;

    private List<String> sites = new ArrayList<>();
    private List<String> models = new ArrayList<>();
    private int siteIndex;
    private int modelIndex;
    private int currencyIndex;
    private int modelScroll;

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

    public PriceConfigScreen(String initialSiteId, Screen parent) {
        super(Component.translatable("gui.tlm_agent_tweaks.price.title"));
        this.initialSiteId = initialSiteId;
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 拉取最新价格表（服务端会校验管理员；非管理员仅拿不到回包）
        PacketDistributor.sendToServer(new RequestPriceTablePayload());

        Map<String, Map<String, String>> clientSites = ClientAvailableSitesSync.getClientLLMSites();
        this.sites = new ArrayList<>(clientSites.keySet());
        if (this.sites.isEmpty() || this.font == null) {
            return;
        }
        int found = this.sites.indexOf(this.initialSiteId);
        this.siteIndex = found >= 0 ? found : 0;
        this.models = new ArrayList<>(clientSites.getOrDefault(currentSite(), Map.of()).keySet());
        this.modelIndex = Math.min(this.modelIndex, Math.max(0, this.models.size() - 1));

        int cx = this.width / 2;

        // 站点切换
        this.addRenderableWidget(Button.builder(Component.literal("‹"), b -> cycleSite(-1))
                .bounds(cx - 130, 22, 20, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal(currentSite()), b -> {
                })
                .bounds(cx - 106, 22, 212, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("›"), b -> cycleSite(1))
                .bounds(cx + 110, 22, 20, 18).build());

        // 货币切换
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlm_agent_tweaks.price.currency." + currentCurrency()),
                        b -> {
                            this.currencyIndex = (this.currencyIndex + 1) % CURRENCIES.length;
                            this.userEdited = false;
                            refillBoxes();
                            this.rebuildWidgets();
                        })
                .bounds(cx - 30, 150, 60, 16).build());

        // 四个价格输入框
        for (int i = 0; i < 4; i++) {
            EditBox box = new EditBox(this.font, cx + 40, 148 + i * 22, 90, 16,
                    Component.translatable(FIELD_KEYS[i]));
            box.setMaxLength(16);
            box.setResponder(text -> this.userEdited = true);
            this.priceBoxes[i] = box;
            this.addRenderableWidget(box);
        }

        // 保存 / 清除 / 返回
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlm_agent_tweaks.price.save"), b -> save(false))
                .bounds(cx - 130, 240, 80, 18).build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlm_agent_tweaks.price.clear"), b -> save(true))
                .bounds(cx - 44, 240, 80, 18).build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlm_agent_tweaks.stats.back"), b -> this.onClose())
                .bounds(cx + 42, 240, 80, 18).build());

        refillBoxes();
    }

    private String currentSite() {
        return this.sites.isEmpty() ? "" : this.sites.get(this.siteIndex);
    }

    private String currentCurrency() {
        return CURRENCIES[this.currencyIndex];
    }

    private void cycleSite(int delta) {
        if (this.sites.isEmpty()) {
            return;
        }
        this.siteIndex = Math.floorMod(this.siteIndex + delta, this.sites.size());
        this.models = new ArrayList<>(ClientAvailableSitesSync.getClientLLMSites()
                .getOrDefault(currentSite(), Map.of()).keySet());
        this.modelIndex = 0;
        this.modelScroll = 0;
        this.userEdited = false;
        refillBoxes();
        this.rebuildWidgets();
    }

    /** 从客户端价格缓存回填当前选择的输入框 */
    private void refillBoxes() {
        if (this.font == null || this.priceBoxes[0] == null) {
            return;
        }
        double[] prices = this.models.isEmpty() || this.modelIndex >= this.models.size()
                ? null
                : ClientPriceCache.getPrice(currentSite(), this.models.get(this.modelIndex), currentCurrency());
        for (int i = 0; i < 4; i++) {
            String text = prices == null ? "" : NumUtil.formatCost(prices[i]);
            this.priceBoxes[i].setValue(text);
        }
        this.userEdited = false;
    }

    private void save(boolean clear) {
        if (this.models.isEmpty() || this.modelIndex >= this.models.size()) {
            showStatus(Component.translatable("gui.tlm_agent_tweaks.price.no_model"));
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
        PacketDistributor.sendToServer(new SaveModelPricePayload(
                currentSite(), this.models.get(this.modelIndex), currentCurrency(), values, clear));
        showStatus(Component.translatable("gui.tlm_agent_tweaks.price.sending"));
    }

    private void showStatus(Component message) {
        this.statusMessage = message;
        this.statusTimestamp = System.currentTimeMillis();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        if (this.sites.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.tlm_agent_tweaks.price.no_sites"),
                    this.width / 2, this.height / 2, 0x999999);
            return;
        }

        // 模型列表（左列，可点击/滚动）
        int listX = this.width / 2 - 130;
        int listTop = 48;
        int listBottom = 138;
        int visible = (listBottom - listTop) / 12;
        this.modelScroll = Math.max(0, Math.min(this.modelScroll, Math.max(0, this.models.size() - visible)));

        graphics.drawString(this.font,
                Component.translatable("gui.tlm_agent_tweaks.price.model"), listX, listTop - 12, 0xFF777777);
        if (this.models.isEmpty()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.tlm_agent_tweaks.price.no_models"),
                    listX, listTop + 4, 0x999999);
        }
        graphics.enableScissor(listX - 2, listTop - 2, listX + 200, listBottom + 2);
        for (int i = 0; i < visible && this.modelScroll + i < this.models.size(); i++) {
            String model = this.models.get(this.modelScroll + i);
            int y = listTop + i * 12;
            int color = this.modelScroll + i == this.modelIndex ? 0xFF55FF55 : 0xDDDDDD;
            graphics.fill(listX - 2, y - 1, listX + 200, y + 10, this.modelScroll + i == this.modelIndex ? 0x33333333 : 0x00000000);
            graphics.drawString(this.font, model, listX, y, color);
        }
        graphics.disableScissor();

        // 价格输入区标签
        for (int i = 0; i < 4; i++) {
            graphics.drawString(this.font, Component.translatable(FIELD_KEYS[i]),
                    this.width / 2 - 30 - this.font.width(Component.translatable(FIELD_KEYS[i])) - 6,
                    151 + i * 22, 0xFF777777);
        }

        // 状态消息（2 秒）
        if (System.currentTimeMillis() - this.statusTimestamp < 2000) {
            graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, 226, 0xFFFF7777);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !this.models.isEmpty()) {
            int listX = this.width / 2 - 130;
            int listTop = 48;
            int listBottom = 138;
            if (mouseX >= listX - 2 && mouseX <= listX + 200 && mouseY >= listTop - 2 && mouseY < listBottom) {
                int row = (int) ((mouseY - listTop) / 12) + this.modelScroll;
                if (row >= 0 && row < this.models.size() && row != this.modelIndex) {
                    this.modelIndex = row;
                    this.userEdited = false;
                    refillBoxes();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.modelScroll = Math.max(0, this.modelScroll - (int) Math.signum(scrollY));
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void tick() {
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

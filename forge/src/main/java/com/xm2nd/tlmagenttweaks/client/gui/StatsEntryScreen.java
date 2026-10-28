package com.xm2nd.tlmagenttweaks.client.gui;

import com.xm2nd.tlmagenttweaks.client.ClientConfig;
import com.xm2nd.tlmagenttweaks.network.ChatVisibilityPayload;
import com.xm2nd.tlmagenttweaks.network.RequestChatVisibilityPayload;
import com.xm2nd.tlmagenttweaks.network.SaveChatPrivacyPayload;
import com.xm2nd.tlmagenttweaks.network.SaveGlobalChatVisibilityPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.xm2nd.tlmagenttweaks.network.TweakPackets;

/**
 * Agent Tweaks 设置页：承载 Token 统计入口，并在新版 Self-Talk 存在时
 * 提供统一的左上角页面导航。
 * <p>
 * 另承载「我的对话设为私密」勾选与（管理员）服务器全局可见开关：
 * 首次初始化请求一次权威状态，快照到达前禁用新增保存控件，回包后按服务端真实状态回显。
 */
@OnlyIn(Dist.CLIENT)
public class StatsEntryScreen extends Screen {

    private final Screen parent;

    /** 服务端权威快照：为 null 表示尚未收到（含回包丢失）。 */
    private ChatVisibilityPayload snapshot;
    private boolean privateWaiting;
    private boolean globalWaiting;
    private Checkbox privateCheckbox;
    private Button globalButton;

    public StatsEntryScreen(Screen parent) {
        super(Component.translatable("gui.tlm_agent_tweaks.stats_entry.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 与新版 Self-Talk 合装时，统一页签由本页自行注册
        SettingsNavigationController.addTweaksPageTabs(this::addRenderableWidget);

        // 快照未到达或仍有保存待确认时重新请求：回包可能在子页打开期间丢失，
        // 从 Token 统计页返回后靠此恢复；收到快照且无等待保存时不再重发
        if (this.snapshot == null || this.privateWaiting || this.globalWaiting) {
            TweakPackets.sendToServer(new RequestChatVisibilityPayload());
        }

        int cx = this.width / 2;
        int cy = this.height / 2;
        // 五行自上而下：思考链开关、Token 统计、本人私密勾选、全局可见（管理员为开关）、返回
        this.addRenderableWidget(Button.builder(thinkingLabel(), b -> {
                    ClientConfig.setShowReasoning(!ClientConfig.showReasoning());
                    b.setMessage(thinkingLabel());
                })
                .tooltip(Tooltip.create(
                        Component.translatable("gui.tlm_agent_tweaks.button.thinking.tooltip")))
                .bounds(cx - 100, cy - 48, 200, 20).build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlm_agent_tweaks.button.token_stats"),
                        b -> this.minecraft.setScreen(new TokenStatsScreen(this)))
                .bounds(cx - 100, cy - 24, 200, 20).build());

        this.privateCheckbox = new Checkbox(cx - 100, cy, 200, 20,
                Component.translatable("gui.tlm_agent_tweaks.chat_privacy.checkbox"),
                this.snapshot != null && this.snapshot.privateChat()) {
            @Override
            public void onPress() {
                super.onPress();
                // 等待权威回包期间禁用，回包后由服务端真实状态重建控件
                StatsEntryScreen.this.privateWaiting = true;
                this.active = false;
                TweakPackets.sendToServer(new SaveChatPrivacyPayload(this.selected()));
            }
        };
        this.privateCheckbox.setTooltip(Tooltip.create(
                Component.translatable("gui.tlm_agent_tweaks.chat_privacy.checkbox.tooltip")));
        // 未收到快照前禁用新增保存控件，不能按本地默认值自动写服务器
        this.privateCheckbox.active = this.snapshot != null && !this.privateWaiting;
        this.addRenderableWidget(this.privateCheckbox);

        this.globalButton = Button.builder(globalLabel(), b -> {
                    if (this.snapshot == null) {
                        return;
                    }
                    StatsEntryScreen.this.globalWaiting = true;
                    b.active = false;
                    TweakPackets.sendToServer(
                            new SaveGlobalChatVisibilityPayload(!this.snapshot.globalVisible()));
                })
                .bounds(cx - 100, cy + 24, 200, 20)
                .build();
        // 普通玩家不显示可编辑的全局控件，仅由下方状态文字展示当前值
        this.globalButton.visible = this.snapshot != null && this.snapshot.canManageGlobal();
        this.globalButton.active = this.snapshot != null && this.snapshot.canManageGlobal()
                && !this.globalWaiting;
        this.addRenderableWidget(this.globalButton);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.tlm_agent_tweaks.stats.back"),
                        b -> this.onClose())
                .bounds(cx - 100, cy + 48, 200, 20).build());
    }

    /** 客户端收到服务端权威快照：按真实状态重建控件并恢复可用。 */
    public void tlmAgentTweaks$onVisibilitySnapshot(ChatVisibilityPayload payload) {
        this.snapshot = payload;
        this.privateWaiting = false;
        this.globalWaiting = false;
        this.rebuildWidgets();
    }

    /** 每次 init 都读取当前配置，页面重开或 resize 不会恢复默认值。 */
    private static Component thinkingLabel() {
        return Component.translatable(ClientConfig.showReasoning()
                ? "gui.tlm_agent_tweaks.button.thinking_on"
                : "gui.tlm_agent_tweaks.button.thinking_off");
    }

    private Component globalLabel() {
        if (this.snapshot == null) {
            return Component.translatable("gui.tlm_agent_tweaks.chat_privacy.loading");
        }
        return Component.translatable(this.snapshot.globalVisible()
                ? "gui.tlm_agent_tweaks.chat_privacy.global_on"
                : "gui.tlm_agent_tweaks.chat_privacy.global_off");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int cy = this.height / 2;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, cy - 68, 0xFFFFFF);
        // 加载中或非管理员时，在全局控件行展示当前状态文字
        if (this.snapshot == null || !this.snapshot.canManageGlobal()) {
            graphics.drawCenteredString(this.font, globalLabel(), this.width / 2, cy + 29, 0xA0A0A0);
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

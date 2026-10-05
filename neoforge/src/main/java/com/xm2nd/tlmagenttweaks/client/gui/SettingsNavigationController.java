package com.xm2nd.tlmagenttweaks.client.gui;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.function.Consumer;

/**
 * Self-Talk 与 Agent Tweaks 的统一设置导航。
 * <p>
 * Self-Talk 页面始终是真实、独立创建的 Screen，不嵌入普通容器；切出前先通过
 * 跨仓库桥执行 Prompt 保存与模态预检。未接入桥时完全不创建页签。
 */
@OnlyIn(Dist.CLIENT)
public final class SettingsNavigationController {

    private static final int TAB_X = 6;
    private static final int TAB_Y = 30;
    private static final int TAB_SIZE = 20;
    private static final int TAB_GAP = 2;

    private static EntityMaid navigationMaid;
    private static Screen chatParent;
    private static Screen selfTalkScreen;

    private SettingsNavigationController() {
    }

    /** Self-Talk 物理入口通过桥把原聊天屏和当前女仆委托给本 mod。 */
    public static void openFromSelfTalkEntry(EntityMaid maid, Screen parent) {
        if (maid == null || parent == null) {
            TlmAgentTweaks.LOGGER.warn("Self-Talk 设置入口缺少女仆或父界面，已忽略本次打开请求");
            return;
        }
        navigationMaid = maid;
        chatParent = parent;
        openSelfTalkScreen();
    }

    /** 单装或旧版 Self-Talk 的独立 Tweaks 入口。 */
    public static void openTweaks(EntityMaid maid, Screen parent) {
        if (maid == null || parent == null) {
            TlmAgentTweaks.LOGGER.warn("Agent Tweaks 设置入口缺少女仆或父界面，已忽略本次打开请求");
            return;
        }
        navigationMaid = maid;
        chatParent = parent;
        selfTalkScreen = null;
        Minecraft.getInstance().setScreen(new StatsEntryScreen(parent));
    }

    /**
     * 仅为当前由本控制器打开的 Self-Talk Screen 实例添加页签。
     * 使用 NeoForge 原生 addListener API，不反射修改 Screen 内部集合。
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!SelfTalkNavigationBridge.isAvailable() || event.getScreen() != selfTalkScreen) {
            return;
        }
        addNavigationTabs(true, event::addListener);
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() == selfTalkScreen) {
            selfTalkScreen = null;
        }
    }

    /** Tweaks 页在自身 init 中调用，使用页面自有的 addRenderableWidget 注册页签。 */
    public static void addTweaksPageTabs(Consumer<Button> addWidget) {
        if (!SelfTalkNavigationBridge.isAvailable()) {
            return;
        }
        addNavigationTabs(false, addWidget);
    }

    private static void addNavigationTabs(boolean selfTalkActive, Consumer<Button> add) {
        Button tweaksTab = Button.builder(Component.literal("🧩"), button -> openTweaksFromSelfTalk())
                .bounds(TAB_X, TAB_Y, TAB_SIZE, TAB_SIZE)
                .tooltip(Tooltip.create(Component.translatable(
                        "gui.tlm_agent_tweaks.navigation.tweaks.tooltip")))
                .build();
        tweaksTab.active = !selfTalkActive;

        Button selfTalkTab = Button.builder(Component.literal("💬"), button -> openSelfTalkScreen())
                .bounds(TAB_X + TAB_SIZE + TAB_GAP, TAB_Y, TAB_SIZE, TAB_SIZE)
                .tooltip(Tooltip.create(Component.translatable(
                        "gui.tlm_agent_tweaks.navigation.selftalk.tooltip")))
                .build();
        selfTalkTab.active = !selfTalkActive;

        add.accept(tweaksTab);
        add.accept(selfTalkTab);
    }

    /** 从 Self-Talk 切到 Tweaks：保存 Prompt 并确认没有浮层后，才真正切屏。 */
    private static void openTweaksFromSelfTalk() {
        if (selfTalkScreen == null || chatParent == null) {
            return;
        }
        if (!SelfTalkNavigationBridge.prepareForNavigation(selfTalkScreen)) {
            return;
        }
        selfTalkScreen = null;
        Minecraft.getInstance().setScreen(new StatsEntryScreen(chatParent));
    }

    /**
     * 每次切入都新建 Self-Talk Screen，确保重新发起配置请求，不缓存旧页的
     * dirty 状态与回包守卫。
     */
    private static void openSelfTalkScreen() {
        if (!SelfTalkNavigationBridge.isAvailable() || navigationMaid == null || chatParent == null) {
            return;
        }
        Screen screen = SelfTalkNavigationBridge.createSettingsScreen(navigationMaid, chatParent);
        if (screen == null) {
            return;
        }
        selfTalkScreen = screen;
        Minecraft.getInstance().setScreen(screen);
    }
}

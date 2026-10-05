package com.xm2nd.tlmagenttweaks.client.gui;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.TlmAgentTweaks;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;

/**
 * 对 Self-Talk 设置导航桥的可选反射适配器。
 * <p>
 * 本类字节码中不引用 Self-Talk 类型，旧版本缺少桥类或仅实现部分方法时，
 * 本 mod 会保留独立入口而不影响启动。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkNavigationBridge {

    private static final String BRIDGE_CLASS = "com.maidmod.selftalk.client.SelfTalkSettingsBridge";

    private static volatile boolean available;
    private static Method createSettingsScreenMethod;
    private static Method prepareForNavigationMethod;

    private SelfTalkNavigationBridge() {
    }

    /** 所有客户端资源就绪后解析桥方法；只有全部兼容才注册 opener。 */
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(SelfTalkNavigationBridge::tryRegister);
    }

    public static boolean isAvailable() {
        return available;
    }

    private static void tryRegister() {
        if (!ModList.get().isLoaded("maid_self_talk")) {
            return;
        }
        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS);
            Method setSettingsOpenerMethod = bridgeClass.getMethod(
                    "setSettingsOpener", BiConsumer.class);
            Method createMethod = bridgeClass.getMethod(
                    "createSettingsScreen", EntityMaid.class, Screen.class);
            Method prepareMethod = bridgeClass.getMethod(
                    "prepareForNavigation", Screen.class);

            requireStaticVoid("setSettingsOpener", setSettingsOpenerMethod);
            requireStaticReturnType("createSettingsScreen", createMethod, Screen.class);
            requireStaticReturnType("prepareForNavigation", prepareMethod, boolean.class);

            setSettingsOpenerMethod.invoke(null,
                    (BiConsumer<EntityMaid, Screen>) SettingsNavigationController::openFromSelfTalkEntry);
            createSettingsScreenMethod = createMethod;
            prepareForNavigationMethod = prepareMethod;
            available = true;
            TlmAgentTweaks.LOGGER.info("Self-Talk 设置导航桥已接入");
        } catch (ClassNotFoundException e) {
            TlmAgentTweaks.LOGGER.info("未发现 Self-Talk 设置导航桥，启用独立设置入口");
        } catch (ReflectiveOperationException | LinkageError e) {
            TlmAgentTweaks.LOGGER.warn("Self-Talk 设置导航桥不完整，启用独立设置入口", e);
        }
    }

    public static Screen createSettingsScreen(EntityMaid maid, Screen parent) {
        if (!available) {
            return null;
        }
        try {
            return (Screen) createSettingsScreenMethod.invoke(null, maid, parent);
        } catch (IllegalAccessException | InvocationTargetException e) {
            TlmAgentTweaks.LOGGER.error("创建 Self-Talk 设置界面失败", e);
            return null;
        }
    }

    /**
     * 切换出 Self-Talk 前保存 Prompt / 检查浮层。
     * 失败时返回 false，宁可不切换也不能绕过 Self-Talk 的保存与模态拦截。
     */
    public static boolean prepareForNavigation(Screen screen) {
        if (!available) {
            return false;
        }
        try {
            Object result = prepareForNavigationMethod.invoke(null, screen);
            return Boolean.TRUE.equals(result);
        } catch (IllegalAccessException | InvocationTargetException e) {
            TlmAgentTweaks.LOGGER.error("Self-Talk 设置界面切换预检失败", e);
            return false;
        }
    }

    private static void requireStaticVoid(String name, Method method) throws NoSuchMethodException {
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
            throw new NoSuchMethodException(BRIDGE_CLASS + "#" + name + " 必须为 public static void");
        }
    }

    private static void requireStaticReturnType(String name, Method method, Class<?> returnType)
            throws NoSuchMethodException {
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != returnType) {
            throw new NoSuchMethodException(
                    BRIDGE_CLASS + "#" + name + " 返回类型必须为 " + returnType.getName());
        }
    }
}

package com.xm2nd.tlmagenttweaks.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsNavigationControllerTest {

    @Test
    void selfTalkPageOnlyEnablesTweaksTab() {
        SettingsNavigationController.TabState state = SettingsNavigationController.tabState(true);

        assertTrue(state.tweaksActive(), "Self-Talk 页应允许切到 Tweaks");
        assertFalse(state.selfTalkActive(), "Self-Talk 页的自身页签应禁用");
    }

    @Test
    void tweaksPageOnlyEnablesSelfTalkTab() {
        SettingsNavigationController.TabState state = SettingsNavigationController.tabState(false);

        assertFalse(state.tweaksActive(), "Tweaks 页的自身页签应禁用");
        assertTrue(state.selfTalkActive(), "Tweaks 页应允许切到 Self-Talk");
    }
}

package com.xm2nd.tlmagenttweaks.common.thinking;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelThinkingConfigTest {

    @Test
    void nullOrEmptyLevelsFallBackToDefault() {
        assertEquals(Set.of(ThinkingLevel.DEFAULT), new ModelThinkingConfig(false, null).levels());
        assertEquals(Set.of(ThinkingLevel.DEFAULT), new ModelThinkingConfig(true, Set.of()).levels());
    }

    @Test
    void unconfiguredIsNotConfigured() {
        assertFalse(ModelThinkingConfig.unconfigured().configured());
        // 仅 default 且不可开关：没有可选项，等同未配置
        assertFalse(new ModelThinkingConfig(false, Set.of(ThinkingLevel.DEFAULT)).configured());
    }

    @Test
    void toggleAloneOrExtraLevelsAloneCountAsConfigured() {
        assertTrue(new ModelThinkingConfig(true, Set.of(ThinkingLevel.DEFAULT)).configured());
        assertTrue(new ModelThinkingConfig(false, Set.of(ThinkingLevel.DEFAULT, ThinkingLevel.LOW)).configured());
    }

    @Test
    void allowsChecksMembership() {
        ModelThinkingConfig config = new ModelThinkingConfig(false, Set.of(ThinkingLevel.DEFAULT, ThinkingLevel.HIGH));
        assertTrue(config.allows(ThinkingLevel.HIGH));
        assertFalse(config.allows(ThinkingLevel.MAX));
        assertFalse(config.allows(null));
    }
}

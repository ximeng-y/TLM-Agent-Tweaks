package com.xm2nd.tlmagenttweaks.common.thinking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingLevelTest {

    @Test
    void idRoundtrip() {
        for (ThinkingLevel level : ThinkingLevel.values()) {
            assertEquals(level, ThinkingLevel.fromId(level.id()));
        }
    }

    @Test
    void unknownOrNullIdYieldsNull() {
        assertNull(ThinkingLevel.fromId("minimal"));
        assertNull(ThinkingLevel.fromId(""));
        assertNull(ThinkingLevel.fromId(null));
    }
}

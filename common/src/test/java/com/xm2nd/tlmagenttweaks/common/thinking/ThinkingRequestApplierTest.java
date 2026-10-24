package com.xm2nd.tlmagenttweaks.common.thinking;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingRequestApplierTest {

    @Test
    void chatOffWritesThinkingDisabled() {
        JsonObject body = parse("{\"model\":\"m\",\"messages\":[]}");
        assertTrue(ThinkingRequestApplier.apply(SiteProtocol.CHAT_COMPLETIONS, body,
                new ThinkingChoice(false, ThinkingLevel.DEFAULT)));
        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertNull(body.get("reasoning_effort"));
    }

    @Test
    void chatOnDefaultClearsLegacyThinkingFieldOnly() {
        JsonObject body = parse("{\"model\":\"m\",\"messages\":[],\"thinking\":{\"type\":\"disabled\"}}");
        assertFalse(ThinkingRequestApplier.apply(SiteProtocol.CHAT_COMPLETIONS, body,
                new ThinkingChoice(true, ThinkingLevel.DEFAULT)));
        assertNull(body.get("thinking"));
        assertNull(body.get("reasoning_effort"));
    }

    @Test
    void chatOnWithLevelWritesReasoningEffort() {
        JsonObject body = parse("{\"model\":\"m\",\"messages\":[]}");
        assertTrue(ThinkingRequestApplier.apply(SiteProtocol.CHAT_COMPLETIONS, body,
                new ThinkingChoice(true, ThinkingLevel.XHIGH)));
        assertEquals("xhigh", body.get("reasoning_effort").getAsString());
    }

    @Test
    void responsesOffAndLevel() {
        JsonObject off = parse("{\"model\":\"m\",\"input\":[]}");
        assertTrue(ThinkingRequestApplier.apply(SiteProtocol.RESPONSES, off,
                new ThinkingChoice(false, ThinkingLevel.DEFAULT)));
        assertEquals("none", off.getAsJsonObject("reasoning").get("effort").getAsString());

        JsonObject level = parse("{\"model\":\"m\",\"input\":[]}");
        assertTrue(ThinkingRequestApplier.apply(SiteProtocol.RESPONSES, level,
                new ThinkingChoice(true, ThinkingLevel.MAX)));
        assertEquals("max", level.getAsJsonObject("reasoning").get("effort").getAsString());
    }

    @Test
    void anthropicOnBumpsMaxTokensAndWritesBudget() {
        JsonObject body = parse("{\"model\":\"m\",\"messages\":[],\"max_tokens\":4096}");
        assertTrue(ThinkingRequestApplier.apply(SiteProtocol.ANTHROPIC_MESSAGES, body,
                new ThinkingChoice(true, ThinkingLevel.LOW)));
        assertEquals(4096 + 2048, body.get("max_tokens").getAsInt());
        assertEquals("enabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals(2048, body.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
    }

    @Test
    void anthropicOffOmitsThinking() {
        JsonObject body = parse("{\"model\":\"m\",\"messages\":[],\"max_tokens\":4096}");
        assertFalse(ThinkingRequestApplier.apply(SiteProtocol.ANTHROPIC_MESSAGES, body,
                new ThinkingChoice(false, ThinkingLevel.DEFAULT)));
        assertNull(body.get("thinking"));
        assertEquals(4096, body.get("max_tokens").getAsInt());
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}

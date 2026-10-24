package com.xm2nd.tlmagenttweaks.common.thinking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingErrorClassifierTest {

    @Test
    void openAiUnrecognizedReasoningEffortMatches() {
        String message = "HTTP Error Code: 400, Response: {\"error\":{\"message\":"
                + "\"Unrecognized request argument supplied: reasoning_effort\","
                + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":null}}";
        assertTrue(ThinkingErrorClassifier.isThinkingUnsupported(message));
    }

    @Test
    void anthropicThinkingRejectedMatches() {
        String message = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"thinking is not supported with this model\"}}";
        assertTrue(ThinkingErrorClassifier.isThinkingUnsupported(message));
    }

    @Test
    void unrelatedFailuresDoNotMatch() {
        assertFalse(ThinkingErrorClassifier.isThinkingUnsupported(null));
        assertFalse(ThinkingErrorClassifier.isThinkingUnsupported(""));
        assertFalse(ThinkingErrorClassifier.isThinkingUnsupported("HTTP Error Code: 429, Response: rate limited"));
        assertFalse(ThinkingErrorClassifier.isThinkingUnsupported(
                "HTTP Error Code: 401, Response: {\"error\":\"invalid api key\"}"));
    }
}

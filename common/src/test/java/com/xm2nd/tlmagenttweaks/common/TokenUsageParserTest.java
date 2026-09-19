package com.xm2nd.tlmagenttweaks.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TokenUsageParserTest {

    @Test
    void parsesOpenAiStyleCachedTokens() {
        String body = """
                {"id":"x","choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":200,
                "total_tokens":1200,"prompt_tokens_details":{"cached_tokens":600}}}
                """;
        TokenUsageParser.UsageStats stats = TokenUsageParser.parse(body);
        assertEquals(1000, stats.promptTokens());
        assertEquals(200, stats.completionTokens());
        assertEquals(600, stats.cacheRead());
        assertEquals(0, stats.cacheWrite());
        assertEquals(400, stats.inputOnly());
    }

    @Test
    void parsesDeepSeekStyleCacheHit() {
        String body = """
                {"usage":{"prompt_tokens":1000,"completion_tokens":100,
                "prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}}
                """;
        TokenUsageParser.UsageStats stats = TokenUsageParser.parse(body);
        assertEquals(800, stats.cacheRead());
        assertEquals(200, stats.inputOnly());
    }

    @Test
    void parsesAnthropicStyleCacheWrite() {
        String body = """
                {"usage":{"prompt_tokens":1000,"completion_tokens":100,
                "prompt_tokens_details":{"cached_tokens":300},"cache_creation_input_tokens":700}}
                """;
        TokenUsageParser.UsageStats stats = TokenUsageParser.parse(body);
        assertEquals(300, stats.cacheRead());
        assertEquals(700, stats.cacheWrite());
        assertEquals(700, stats.inputOnly());
    }

    @Test
    void bothCacheStylesTakeMax() {
        String body = """
                {"usage":{"prompt_tokens":1000,"completion_tokens":1,
                "prompt_cache_hit_tokens":400,"prompt_tokens_details":{"cached_tokens":500}}}
                """;
        assertEquals(500, TokenUsageParser.parse(body).cacheRead());
    }

    @Test
    void missingUsageReturnsNull() {
        assertNull(TokenUsageParser.parse("{\"choices\":[]}"));
        assertNull(TokenUsageParser.parse("{\"usage\":null}"));
    }

    @Test
    void malformedJsonReturnsNull() {
        assertNull(TokenUsageParser.parse("<html>Bad Gateway</html>"));
        assertNull(TokenUsageParser.parse("{}"));
        // 全零用量视为无效
        assertNull(TokenUsageParser.parse("{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}"));
    }
}

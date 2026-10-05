package com.xm2nd.tlmagenttweaks.protocol;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.protocol.UrlMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProtocolLLMSiteCodecTest {

    @Test
    void codecRoundtripsProtocolAndUrlMode() {
        ProtocolLLMSite site = new ProtocolLLMSite(
                "site-1",
                ResourceLocation.fromNamespaceAndPath("tlm_agent_tweaks", "test"),
                "https://example.test",
                true,
                "",
                false,
                Map.of(),
                List.of(new LLMOpenAISite.ModelEntry("model-1")),
                SiteProtocol.ANTHROPIC_MESSAGES,
                UrlMode.BASE);

        CompoundTag encoded = (CompoundTag) ProtocolLLMSite.Serializer.CODEC
                .encodeStart(NbtOps.INSTANCE, site)
                .getOrThrow();
        LLMOpenAISite decoded = ProtocolLLMSite.Serializer.CODEC
                .parse(NbtOps.INSTANCE, encoded)
                .getOrThrow();

        ProtocolLLMSite protocolSite = assertInstanceOf(ProtocolLLMSite.class, decoded);
        assertEquals(SiteProtocol.ANTHROPIC_MESSAGES, protocolSite.protocol());
        assertEquals(UrlMode.BASE, protocolSite.urlMode());
    }

    @Test
    void legacyConfigWithoutNewFieldsKeepsExactOpenAiSemantics() {
        ProtocolLLMSite site = new ProtocolLLMSite(
                "site-1",
                ResourceLocation.fromNamespaceAndPath("tlm_agent_tweaks", "test"),
                "https://example.test/v1/chat/completions",
                true,
                "",
                false,
                Map.of(),
                List.of(new LLMOpenAISite.ModelEntry("model-1")),
                SiteProtocol.RESPONSES,
                UrlMode.BASE);

        CompoundTag encoded = (CompoundTag) ProtocolLLMSite.Serializer.CODEC
                .encodeStart(NbtOps.INSTANCE, site)
                .getOrThrow();
        encoded.remove("protocol");
        encoded.remove("url_mode");

        ProtocolLLMSite decoded = assertInstanceOf(ProtocolLLMSite.class,
                ProtocolLLMSite.Serializer.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
        assertEquals(SiteProtocol.CHAT_COMPLETIONS, decoded.protocol());
        assertEquals(UrlMode.EXACT, decoded.urlMode());
    }
}

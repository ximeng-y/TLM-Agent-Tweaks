package com.xm2nd.tlmagenttweaks.protocol;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolSiteConfig;
import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import com.xm2nd.tlmagenttweaks.common.protocol.UrlMode;
import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.common.thinking.ThinkingLevel;

import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 带协议配置的 TLM OpenAI 兼容站点。
 * <p>
 * 继承原 {@link LLMOpenAISite}，从而不改变 TLM 的类型判断、客户端工厂和模型选择逻辑。
 * 在协议字段之外还携带每模型的思考配置（可开关 + 允许的思考强度），随站点一起持久化。
 */
public class ProtocolLLMSite extends LLMOpenAISite {
    private final SiteProtocol protocol;
    private final UrlMode urlMode;
    private final Map<String, ModelThinkingConfig> thinkingConfigs;

    public ProtocolLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                           boolean hasThinkingField, Map<String, String> headers,
                           Map<String, ModelEntry> modelEntries, SiteProtocol protocol, UrlMode urlMode) {
        this(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries, protocol, urlMode, Map.of());
    }

    public ProtocolLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                           boolean hasThinkingField, Map<String, String> headers,
                           Map<String, ModelEntry> modelEntries, SiteProtocol protocol, UrlMode urlMode,
                           Map<String, ModelThinkingConfig> thinkingConfigs) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, new HashMap<>(modelEntries));
        this.protocol = protocol;
        this.urlMode = urlMode;
        this.thinkingConfigs = thinkingConfigs == null ? Map.of() : Map.copyOf(thinkingConfigs);
    }

    public ProtocolLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                           boolean hasThinkingField, Map<String, String> headers,
                           List<ModelEntry> modelEntries, SiteProtocol protocol, UrlMode urlMode) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
        this.protocol = protocol;
        this.urlMode = urlMode;
        this.thinkingConfigs = Map.of();
    }

    public SiteProtocol protocol() {
        return protocol;
    }

    public UrlMode urlMode() {
        return urlMode;
    }

    public Map<String, ModelThinkingConfig> thinkingConfigs() {
        return thinkingConfigs;
    }

    public static class Serializer extends LLMOpenAISite.Serializer {
        private static final Codec<SiteProtocol> PROTOCOL_CODEC = Codec.STRING.comapFlatMap(
                id -> SiteProtocol.fromId(id)
                        .map(DataResult::success)
                        .orElseGet(() -> DataResult.error(() -> "Unknown LLM protocol: " + id)),
                SiteProtocol::id);

        private static final Codec<UrlMode> URL_MODE_CODEC = Codec.STRING.comapFlatMap(
                id -> UrlMode.fromId(id)
                        .map(DataResult::success)
                        .orElseGet(() -> DataResult.error(() -> "Unknown URL mode: " + id)),
                UrlMode::id);

        private static final Codec<ModelThinkingConfig> THINKING_CONFIG_CODEC = RecordCodecBuilder.create(
                instance -> instance.group(
                        Codec.BOOL.fieldOf("toggle_allowed").forGetter(ModelThinkingConfig::toggleAllowed),
                        Codec.STRING.listOf().fieldOf("levels").forGetter(config -> config.levels().stream()
                                .map(ThinkingLevel::id).toList())
                ).apply(instance, ProtocolLLMSite.Serializer::decodeThinkingConfig));

        private static final String THINKING_CONFIG = "thinking_config";

        private static ModelThinkingConfig decodeThinkingConfig(boolean toggleAllowed, List<String> levels) {
            Set<ThinkingLevel> parsed = new LinkedHashSet<>();
            for (String id : levels) {
                ThinkingLevel level = ThinkingLevel.fromId(id);
                if (level != null) {
                    parsed.add(level);
                }
            }
            return new ModelThinkingConfig(toggleAllowed, parsed);
        }

        public static final Codec<LLMOpenAISite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(LLMOpenAISite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(LLMOpenAISite::icon),
                Codec.STRING.fieldOf(URL).forGetter(LLMOpenAISite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(LLMOpenAISite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(LLMOpenAISite::secretKey),
                Codec.BOOL.optionalFieldOf(HAS_THINKING_FIELD, false).forGetter(LLMOpenAISite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(LLMOpenAISite::headers),
                MODELS_CODEC.fieldOf(MODELS).forGetter(LLMOpenAISite::modelEntries),
                PROTOCOL_CODEC.optionalFieldOf(ProtocolSiteConfig.PROTOCOL, ProtocolSiteConfig.LEGACY_PROTOCOL)
                        .forGetter(site -> site instanceof ProtocolLLMSite configured
                                ? configured.protocol() : ProtocolSiteConfig.LEGACY_PROTOCOL),
                URL_MODE_CODEC.optionalFieldOf(ProtocolSiteConfig.URL_MODE, ProtocolSiteConfig.LEGACY_URL_MODE)
                        .forGetter(site -> site instanceof ProtocolLLMSite configured
                                ? configured.urlMode() : ProtocolSiteConfig.LEGACY_URL_MODE),
                Codec.unboundedMap(Codec.STRING, THINKING_CONFIG_CODEC)
                        .optionalFieldOf(THINKING_CONFIG, Map.of())
                        .forGetter(site -> site instanceof ProtocolLLMSite configured
                                ? configured.thinkingConfigs() : Map.of())
        ).apply(instance, ProtocolLLMSite::new));

        @Override
        public LLMOpenAISite defaultSite() {
            return new ProtocolLLMSite(API_TYPE, SerializableSite.defaultIcon(API_TYPE),
                    "https://api.openai.com/v1/chat/completions", false,
                    StringUtils.EMPTY, false, Map.of(),
                    List.of(
                            new ModelEntry("gpt-4o"),
                            new ModelEntry("gpt-4.1"),
                            new ModelEntry("gpt-5-mini", true),
                            new ModelEntry("gpt-5.1", true),
                            new ModelEntry("gpt-5.2", true),
                            new ModelEntry("gpt-5.4", true)
                    ),
                    SiteProtocol.CHAT_COMPLETIONS, UrlMode.BASE);
        }

        @Override
        public Codec<LLMOpenAISite> codec() {
            return CODEC;
        }
    }
}

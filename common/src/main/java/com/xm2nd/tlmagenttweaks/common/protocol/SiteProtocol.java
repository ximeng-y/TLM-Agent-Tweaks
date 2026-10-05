package com.xm2nd.tlmagenttweaks.common.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * 站点使用的 LLM 协议。
 * <p>
 * 配置值保持稳定、可读，不能使用 enum ordinal 作为持久化标识。
 */
public enum SiteProtocol {
    CHAT_COMPLETIONS("chat_completions"),
    RESPONSES("responses"),
    ANTHROPIC_MESSAGES("anthropic_messages");

    private final String id;

    SiteProtocol(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<SiteProtocol> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(protocol -> protocol.id.equals(id))
                .findFirst();
    }

    public static SiteProtocol fromIdOrThrow(String id) throws ProtocolException {
        return fromId(id).orElseThrow(() -> new ProtocolException("Unknown LLM protocol: " + id));
    }
}

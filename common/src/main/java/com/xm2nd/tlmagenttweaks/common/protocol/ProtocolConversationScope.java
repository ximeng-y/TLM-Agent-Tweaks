package com.xm2nd.tlmagenttweaks.common.protocol;

import java.util.Objects;

/**
 * 协议私有元数据的作用域。
 * <p>
 * 使用女仆 UUID 等稳定会话标识，而不是一次 HTTP 请求的 callback 身份；
 * 同时包含站点和协议，避免不同女仆、站点或协议之间串用 opaque 项。
 */
public record ProtocolConversationScope(String ownerId, String siteId, SiteProtocol protocol) {
    public ProtocolConversationScope {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(protocol, "protocol");
    }
}

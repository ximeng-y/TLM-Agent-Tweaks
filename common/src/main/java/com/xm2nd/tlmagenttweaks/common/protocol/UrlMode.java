package com.xm2nd.tlmagenttweaks.common.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * 站点 URL 的语义模式。
 * <p>
 * {@link #EXACT} 表示 URL 已经是完整端点；{@link #BASE} 表示 URL 是基地址，
 * 需要按协议补全默认端点。
 */
public enum UrlMode {
    EXACT("exact"),
    BASE("base");

    private final String id;

    UrlMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<UrlMode> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(mode -> mode.id.equals(id))
                .findFirst();
    }

    public static UrlMode fromIdOrThrow(String id) throws ProtocolException {
        return fromId(id).orElseThrow(() -> new ProtocolException("Unknown URL mode: " + id));
    }
}

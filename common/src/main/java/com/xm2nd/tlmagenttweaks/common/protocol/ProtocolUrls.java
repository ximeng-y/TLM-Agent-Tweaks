package com.xm2nd.tlmagenttweaks.common.protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * LLM 站点端点处理。
 * <p>
 * EXACT 模式原样保留完整端点；BASE 模式在缺少端点后缀时补全默认后缀。
 * BASE 模式下如果用户已经填了完整端点，也会保留完整端点，避免重复拼接。
 */
public final class ProtocolUrls {
    private ProtocolUrls() {
    }

    public static URI resolve(SiteProtocol protocol, UrlMode mode, String rawUrl) throws ProtocolException {
        if (protocol == null || mode == null) {
            throw new ProtocolException("Protocol and URL mode must not be null");
        }
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        if (trimmed.isEmpty()) {
            throw new ProtocolException("URL must not be empty");
        }

        URI uri = parse(trimmed);
        if (mode == UrlMode.EXACT) {
            return uri;
        }

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        path = stripTrailingSlash(path);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (endsWithEndpoint(lowerPath, protocol)) {
            return rebuild(uri, stripTrailingSlash(path));
        }

        String basePath = stripTrailingSlash(path);
        String resolvedPath = switch (protocol) {
            case CHAT_COMPLETIONS -> basePathWithOpenAiVersion(basePath) + "/chat/completions";
            case RESPONSES -> basePathWithOpenAiVersion(basePath) + "/responses";
            case ANTHROPIC_MESSAGES -> basePathWithAnthropicVersion(basePath) + "/messages";
        };
        return rebuild(uri, resolvedPath);
    }

    private static URI parse(String value) throws ProtocolException {
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw new ProtocolException("URL must include a scheme: " + value);
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new ProtocolException("Unsupported URL scheme: " + uri.getScheme());
            }
            if (uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()) {
                throw new ProtocolException("URL must include a host: " + value);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ProtocolException("Invalid URL syntax", e);
        }
    }

    private static boolean endsWithEndpoint(String lowerPath, SiteProtocol protocol) {
        String suffix = switch (protocol) {
            case CHAT_COMPLETIONS -> "/chat/completions";
            case RESPONSES -> "/responses";
            case ANTHROPIC_MESSAGES -> "/messages";
        };
        return lowerPath.equals(suffix) || lowerPath.endsWith(suffix);
    }

    private static String basePathWithOpenAiVersion(String basePath) {
        if (basePath.isEmpty() || basePath.equals("/")) {
            return "/v1";
        }
        return basePath;
    }

    private static String basePathWithAnthropicVersion(String basePath) {
        if (basePath.isEmpty() || basePath.equals("/")) {
            return "/v1";
        }
        if (basePath.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            return basePath;
        }
        return basePath + "/v1";
    }

    private static String stripTrailingSlash(String path) {
        if (path.isEmpty() || path.equals("/")) {
            return path;
        }
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static URI rebuild(URI original, String path) throws ProtocolException {
        StringBuilder builder = new StringBuilder();
        builder.append(original.getScheme()).append("://").append(original.getRawAuthority()).append(path);
        if (original.getRawQuery() != null && !original.getRawQuery().isEmpty()) {
            builder.append('?').append(original.getRawQuery());
        }
        try {
            return new URI(builder.toString());
        } catch (URISyntaxException e) {
            throw new ProtocolException("Failed to build resolved URL", e);
        }
    }
}

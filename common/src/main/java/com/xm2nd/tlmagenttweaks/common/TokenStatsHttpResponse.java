package com.xm2nd.tlmagenttweaks.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import javax.net.ssl.SSLSession;

/**
 * 携带 {@link TokenRequestContext} 归因快照的响应包装：完整委托被包装响应。
 * <p>
 * 响应处理方须先用 {@link #delegate()} 解包再参与协议归一化判断
 * （协议归一化只识别自己的包装类型，统计包装不能直接交给它）；
 * 记账时从 {@link #context()} 取发送前的归因快照。
 */
public final class TokenStatsHttpResponse implements HttpResponse<String> {
    private final TokenRequestContext context;
    private final HttpResponse<String> delegate;

    public TokenStatsHttpResponse(TokenRequestContext context, HttpResponse<String> delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    public TokenRequestContext context() {
        return context;
    }

    public HttpResponse<String> delegate() {
        return delegate;
    }

    @Override
    public int statusCode() {
        return delegate.statusCode();
    }

    @Override
    public HttpRequest request() {
        return delegate.request();
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
        return delegate.previousResponse();
    }

    @Override
    public HttpHeaders headers() {
        return delegate.headers();
    }

    @Override
    public String body() {
        return delegate.body();
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return delegate.sslSession();
    }

    @Override
    public URI uri() {
        return delegate.uri();
    }

    @Override
    public HttpClient.Version version() {
        return delegate.version();
    }
}

package com.xm2nd.tlmagenttweaks.protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import javax.net.ssl.SSLSession;

/**
 * 包装一个已规范化的响应，保留原状态码、请求和响应头。
 */
final class ProtocolHttpResponse implements HttpResponse<String> {
    private final HttpResponse<String> delegate;
    private final int statusCode;
    private final String body;
    private final HttpRequest request;
    private final URI uri;
    private final boolean protocolNormalized;

    ProtocolHttpResponse(HttpResponse<String> delegate, String body, boolean protocolNormalized) {
        this.delegate = delegate;
        this.statusCode = delegate.statusCode();
        this.body = body;
        this.request = delegate.request();
        this.uri = delegate.uri();
        this.protocolNormalized = protocolNormalized;
    }

    boolean isProtocolNormalized() {
        return protocolNormalized;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public HttpRequest request() {
        return request;
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
        return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return delegate.sslSession();
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public HttpClient.Version version() {
        return delegate.version();
    }
}

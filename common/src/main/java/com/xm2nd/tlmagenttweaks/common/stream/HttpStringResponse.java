package com.xm2nd.tlmagenttweaks.common.stream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import javax.net.ssl.SSLSession;

/**
 * 只保留状态码/请求/响应头与字符串 body 的轻量 {@link HttpResponse}。
 * <p>
 * 流式聚合完成后用它承载原始协议对象，交由平台上层的响应归一化入口处理，
 * 从而避免 common 层依赖任何平台类型。
 */
public final class HttpStringResponse implements HttpResponse<String> {
    private final int statusCode;
    private final HttpRequest request;
    private final HttpHeaders headers;
    private final String body;
    private final Optional<SSLSession> sslSession;
    private final URI uri;
    private final HttpClient.Version version;

    public HttpStringResponse(int statusCode, HttpRequest request, HttpHeaders headers, String body,
                              Optional<SSLSession> sslSession, URI uri, HttpClient.Version version) {
        this.statusCode = statusCode;
        this.request = request;
        this.headers = headers;
        this.body = body;
        this.sslSession = sslSession;
        this.uri = uri;
        this.version = version;
    }

    public static HttpStringResponse of(HttpResponse<?> head, String body) {
        return new HttpStringResponse(head.statusCode(), head.request(), head.headers(), body,
                head.sslSession(), head.uri(), head.version());
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
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public String body() {
        return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return sslSession;
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public HttpClient.Version version() {
        return version;
    }
}

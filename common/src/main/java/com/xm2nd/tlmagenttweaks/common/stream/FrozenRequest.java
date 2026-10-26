package com.xm2nd.tlmagenttweaks.common.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 一次读入内存的请求快照：请求体只解一次，重试与非流式回退都复用同一份字节，
 * 连同模型、消息、工具结果与鉴权头保持不变；重试不经过 {@code LLMClient.chat}，
 * 因此不会重新构造历史、不会重复执行工具。
 */
public final class FrozenRequest {
    private final HttpRequest request;
    private final byte[] body;

    private FrozenRequest(HttpRequest request, byte[] body) {
        this.request = request;
        this.body = body;
    }

    /** 冻结一次请求：要求带请求体，并一次性读完全部字节。 */
    public static FrozenRequest freeze(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow(
                () -> new IllegalArgumentException("Streaming request must have a body"));
        return new FrozenRequest(request, readPublisher(publisher));
    }

    public HttpRequest request() {
        return request;
    }

    public byte[] body() {
        return body.clone();
    }

    private static byte[] readPublisher(HttpRequest.BodyPublisher publisher) {
        int initial = publisher.contentLength() > 0 && publisher.contentLength() < Integer.MAX_VALUE
                ? (int) publisher.contentLength() : 256;
        ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
        CompletableFuture<Throwable> finished = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.write(chunk, 0, chunk.length);
            }

            @Override
            public void onError(Throwable throwable) {
                finished.complete(throwable);
            }

            @Override
            public void onComplete() {
                finished.complete(null);
            }
        });
        try {
            Throwable failure = finished.get(30, TimeUnit.SECONDS);
            if (failure != null) {
                throw new UncheckedIOException(new IOException("Request body publisher failed", failure));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Interrupted while reading request body", e));
        } catch (ExecutionException | TimeoutException e) {
            throw new UncheckedIOException(new IOException("Failed to read request body", e));
        }
        return out.toByteArray();
    }
}

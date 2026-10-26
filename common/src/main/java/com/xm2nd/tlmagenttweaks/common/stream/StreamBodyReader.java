package com.xm2nd.tlmagenttweaks.common.stream;

import com.xm2nd.tlmagenttweaks.common.protocol.SiteProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.util.Locale;

/**
 * 响应正文的读取与聚合装配：SSE 流式增量或整段 JSON，交给对应协议的聚合器。
 * <p>
 * 读取以硬截止时刻为准（{@code deadline}），存活性由 {@link AttemptGuard} 校验：
 * 每次读取与每个事件前确认本次尝试仍在途，失效时抛出取消异常终止读取。
 */
final class StreamBodyReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamBodyReader.class);
    private static final int READ_BUFFER_BYTES = 8192;

    /** 读取过程与单次尝试的耦合点：校验存活性，并把增量派发放进同一临界区。 */
    interface AttemptGuard {
        /** 校验本次尝试仍在途；失效时抛出取消异常终止读取。 */
        void ensureActive();

        /** 派发一个流式增量；实现方须保证与 {@link #ensureActive} 共用同一把锁。 */
        void onEvent(StreamEventType type, String text);
    }

    enum ContentKind {
        SSE,
        JSON
    }

    static ContentKind contentKind(HttpHeaders headers) {
        String contentType = headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("text/event-stream")) {
            return ContentKind.SSE;
        }
        if (contentType.contains("application/json") || contentType.contains("+json")) {
            return ContentKind.JSON;
        }
        // 未声明或未知类型时按 SSE 处理，保持既有行为。
        return ContentKind.SSE;
    }

    /** 消费 SSE：终止标志到达即停止读取（keep-alive 连接不必等 EOF）。 */
    static String readStream(InputStream stream, long deadline, SiteProtocol protocol,
                             AttemptGuard guard) throws Exception {
        ProtocolStreamAggregator aggregator = newAggregator(protocol, guard);
        SseDecoder decoder = new SseDecoder();
        try (stream) {
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            while (true) {
                ensureWithinDeadline(deadline);
                int read = stream.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                for (SseEvent event : decoder.feed(buffer, 0, read)) {
                    guard.ensureActive();
                    aggregator.accept(event.data());
                    if (aggregator.complete()) {
                        break;
                    }
                }
                if (aggregator.complete()) {
                    break;
                }
            }
            if (!aggregator.complete()) {
                for (SseEvent event : decoder.finish()) {
                    guard.ensureActive();
                    aggregator.accept(event.data());
                }
            }
            return aggregator.finish().toString();
        }
    }

    static String readAll(InputStream stream, long deadline, AttemptGuard guard) throws Exception {
        return readAll(stream, deadline, guard, Integer.MAX_VALUE);
    }

    /**
     * 带读取上限的完整读取：达到 {@code maxBytes} 后停止，剩余正文连同连接一并丢弃。
     * 流仍由 try-with-resources 关闭；字节上限即字符上限的保守上界（UTF-8 每字符至少一字节）。
     */
    static String readAll(InputStream stream, long deadline, AttemptGuard guard, int maxBytes) throws Exception {
        try (stream) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            while (true) {
                ensureWithinDeadline(deadline);
                int read = stream.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                guard.ensureActive();
                out.write(buffer, 0, read);
                if (out.size() >= maxBytes) {
                    break;
                }
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static ProtocolStreamAggregator newAggregator(SiteProtocol protocol, AttemptGuard guard) {
        StreamEventListener bridge = event -> {
            // 只读增量监听者的异常必须隔离，不能中断聚合或触发回退。
            try {
                guard.onEvent(event.type(), event.text());
            } catch (Throwable throwable) {
                LOGGER.warn("流式增量监听器抛出异常（已隔离）", throwable);
            }
        };
        return switch (protocol) {
            case CHAT_COMPLETIONS -> new ChatCompletionsStreamAggregator(bridge);
            case RESPONSES -> new ResponsesStreamAggregator(bridge);
            case ANTHROPIC_MESSAGES -> new AnthropicStreamAggregator(bridge);
        };
    }

    private static void ensureWithinDeadline(long deadline) throws IOException {
        if (System.nanoTime() - deadline >= 0) {
            throw new IOException("Streaming read deadline exceeded");
        }
    }

    private StreamBodyReader() {
    }
}

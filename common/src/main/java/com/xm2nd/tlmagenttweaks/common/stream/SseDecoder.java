package com.xm2nd.tlmagenttweaks.common.stream;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 增量式 SSE 解帧器：从任意切分的字节块中还原出完整事件。
 * <p>
 * 严格遵守 SSE 行协议：UTF-8 可跨字节块（整行按字节累积后再解码）、支持
 * CRLF/LF/CR 换行、空行分帧、注释行、同一事件多个 {@code data} 行（以 LF 连接）。
 * 结尾未遇到空行的残留数据不会在 {@link #finish()} 中伪造成事件，调用方需自行
 * 判断缺失终止标志。
 */
public final class SseDecoder {
    private static final int MAX_LINE_BYTES = 1 << 20;
    private static final int MAX_EVENT_DATA_CHARS = 4 << 20;

    private final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream(256);
    private final List<String> dataLines = new ArrayList<>();
    private String eventType;
    private String lastEventId;
    private long retryMillis = -1;
    private int dataChars;
    private boolean previousWasCr;

    public List<SseEvent> feed(byte[] bytes, int offset, int length) {
        List<SseEvent> events = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int value = bytes[offset + i] & 0xFF;
            if (value == '\r' || value == '\n') {
                boolean crlf = value == '\n' && previousWasCr;
                previousWasCr = value == '\r';
                if (crlf) {
                    continue;
                }
                SseEvent event = endLine();
                if (event != null) {
                    events.add(event);
                }
            } else {
                previousWasCr = false;
                if (lineBytes.size() >= MAX_LINE_BYTES) {
                    throw new IllegalStateException("SSE line exceeds " + MAX_LINE_BYTES + " bytes");
                }
                lineBytes.write(value);
            }
        }
        return events;
    }

    /**
     * 输入流结束。SSE 规范允许最后一行没有换行，因此此处补一次行结束处理；
     * 但不会伪造缺失空行的分帧事件。
     */
    public List<SseEvent> finish() {
        if (lineBytes.size() == 0) {
            return List.of();
        }
        previousWasCr = false;
        SseEvent event = endLine();
        return event == null ? List.of() : List.of(event);
    }

    private SseEvent endLine() {
        String text = lineBytes.toString(StandardCharsets.UTF_8);
        lineBytes.reset();
        if (text.isEmpty()) {
            return dispatch();
        }
        if (text.charAt(0) == ':') {
            return null;
        }
        int colon = text.indexOf(':');
        String field = colon < 0 ? text : text.substring(0, colon);
        String value = colon < 0 ? "" : text.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if ("data".equals(field)) {
            dataChars += value.length();
            if (dataChars > MAX_EVENT_DATA_CHARS) {
                throw new IllegalStateException("SSE event data exceeds " + MAX_EVENT_DATA_CHARS + " chars");
            }
            dataLines.add(value);
        } else if ("event".equals(field)) {
            eventType = value;
        } else if ("id".equals(field)) {
            if (!value.contains("\u0000")) {
                lastEventId = value;
            }
        } else if ("retry".equals(field)) {
            try {
                retryMillis = Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // 非法 retry 字段按规范忽略
            }
        }
        return null;
    }

    private SseEvent dispatch() {
        if (dataLines.isEmpty()) {
            eventType = null;
            return null;
        }
        String data = String.join("\n", dataLines);
        SseEvent event = new SseEvent(eventType, data, lastEventId, retryMillis);
        dataLines.clear();
        eventType = null;
        dataChars = 0;
        return event;
    }

    /**
     * 便于测试与轻量调用：按 UTF-8 解码整段文本。
     */
    public List<SseEvent> feed(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return feed(bytes, 0, bytes.length);
    }
}

package com.xm2nd.tlmagenttweaks.common.stream;

import com.xm2nd.tlmagenttweaks.common.protocol.ProtocolException;

/** 服务端通过 SSE 明确返回的失败，不属于可回退重发的传输中断。 */
public final class StreamServiceException extends ProtocolException {
    public StreamServiceException(String message) {
        super(message);
    }
}

package com.xm2nd.tlmagenttweaks.common.protocol;

/**
 * 协议配置、请求编码或响应解码错误。
 * <p>
 * 该异常只包含协议字段名和简短原因，不包含 API Key 或完整用户输入。
 */
public class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

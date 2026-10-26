package com.xm2nd.tlmagenttweaks.common;

/**
 * 一次 LLM 请求的统计归因快照：在请求冻结之后、实际发送之前捕获，随响应传回记账位置。
 * <p>
 * 只保存不可变的归因字段，不持有女仆实体、聊天管理器或回调等可变对象；
 * 请求途中切换模型、站点或主人，不改变已经发出的请求的统计归属。
 */
public final class TokenRequestContext {
    private final String ownerUuid;
    private final String ownerName;
    private final String siteId;
    private final String model;
    private final boolean summaryCall;

    public TokenRequestContext(String ownerUuid, String ownerName, String siteId, String model, boolean summaryCall) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.siteId = siteId;
        this.model = model;
        this.summaryCall = summaryCall;
    }

    public String ownerUuid() {
        return ownerUuid;
    }

    public String ownerName() {
        return ownerName;
    }

    public String siteId() {
        return siteId;
    }

    public String model() {
        return model;
    }

    public boolean summaryCall() {
        return summaryCall;
    }
}

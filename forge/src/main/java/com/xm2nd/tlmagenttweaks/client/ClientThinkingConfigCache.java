package com.xm2nd.tlmagenttweaks.client;

import com.xm2nd.tlmagenttweaks.common.thinking.ModelThinkingConfig;
import com.xm2nd.tlmagenttweaks.network.ThinkingConfigPayload;

import java.util.Map;

/**
 * 客户端持有的每模型思考配置快照（siteId → modelId → 配置），
 * 以及快照所带女仆当前已存的思考选择。
 * <p>
 * 玩家打开 AI 聊天界面时向服务端请求一次；服务端站点配置变更后，
 * 重新打开界面即会刷新。快照不含密钥等敏感信息。
 */
public final class ClientThinkingConfigCache {

    private static volatile Map<String, Map<String, ModelThinkingConfig>> CONFIGS = Map.of();
    private static volatile ThinkingConfigPayload.CurrentChoice CURRENT;

    private ClientThinkingConfigCache() {
    }

    public static void accept(Map<String, Map<String, ModelThinkingConfig>> configs) {
        CONFIGS = configs == null ? Map.of() : configs;
    }

    public static void acceptCurrent(ThinkingConfigPayload.CurrentChoice current) {
        CURRENT = current;
    }

    /**
     * 查询某站点某模型的思考配置；未配置或快照未同步时返回 {@code null}。
     */
    public static ModelThinkingConfig get(String siteId, String modelId) {
        if (siteId == null || modelId == null) {
            return null;
        }
        Map<String, ModelThinkingConfig> byModel = CONFIGS.get(siteId);
        return byModel == null ? null : byModel.get(modelId);
    }

    /**
     * 读取指定女仆当前已存的思考选择；无记录、或记录属于其他女仆时返回 {@code null}。
     */
    public static ThinkingConfigPayload.CurrentChoice current(int entityId) {
        ThinkingConfigPayload.CurrentChoice current = CURRENT;
        return current != null && current.entityId() == entityId ? current : null;
    }
}

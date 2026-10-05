package com.xm2nd.tlmagenttweaks.protocol;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ServiceType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;

/**
 * 用带协议字段的站点序列化器覆盖 TLM 原 openai 序列化器。
 * <p>
 * TLM 注册完成后才会执行扩展注册，因此同 api_type 的后注册项生效；
 * 旧配置缺少 protocol/url_mode 时由 codec 默认保持 openai + 完整 URL 语义。
 */
@LittleMaidExtension
public class TlmProtocolIntegration implements ILittleMaid {
    @Override
    public void registerAIChatSerializer(SerializerRegister register) {
        register.register(ServiceType.LLM, LLMOpenAISite.API_TYPE, new ProtocolLLMSite.Serializer());
    }
}

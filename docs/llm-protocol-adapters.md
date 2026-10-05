# LLM 三协议站点适配

本项目在 TLM 原有 OpenAI 兼容站点编辑器中增加协议选择，由本 mod 统一承接
Chat Completions、OpenAI Responses 和 Anthropic Messages 的非流式请求转换。
其他 mod 继续调用 TLM 原有 LLM 接口，不需要重复实现协议适配。

## 站点配置

- 站点编辑器底部原来的价格按钮位置拆成「价格」和「协议」两个入口。
- 「协议」打开轻量协议设置屏，提供协议、URL 模式和最终 URL 预览。
- `完整端点`（EXACT）原样使用填写的 URL；`基地址`（BASE）按协议补全
  `/chat/completions`、`/responses` 或 `/v1/messages`。如果 BASE 下已经填写
  完整端点，则保留该端点，避免重复拼接。
- 新建站点默认使用 `Chat Completions + 基地址`；旧配置没有协议字段时默认
  `Chat Completions + 完整端点`，保持原有语义。
- 最终 URL 预览只读取当前 URL 输入和选择，不写回、不发送、也不会打印密钥。

## 协议边界

- Chat Completions 请求和响应沿用 TLM 原形态。
- Responses 请求把 TLM 的 `chat.completion` 历史规范化为 `input` 项，并在
  可回传时保留 reasoning/encrypted 项；响应规范化为 TLM 的
  `chat.completion`。
- Anthropic Messages 请求把 system、user、assistant 和 tool 历史规范化为
  Messages 内容块；响应规范化为 TLM 的 `chat.completion`。
- 当前 PR 只覆盖可靠的非流式请求和协议边界。流式输出、思考内容 UI 以及
  断流自动回退非流式将在后续 PR 中实现。
- 明确的错误响应、未完成 Responses 状态、Anthropic `max_tokens` 截断和
  未知协议输出不会被当成成功；HTTP 错误仍交给 TLM 原失败回调处理。

## opaque 元数据

Responses reasoning/encrypted item 和 Anthropic thinking/signature 等 opaque
信息按女仆 UUID、站点 ID 和协议隔离保存在世界存档中，并限制总条目数与每个
会话条目数。后续工具循环或新回调能在同一作用域内取回这些信息，不会跨女仆、
不同站点或不同协议串用。

如果普通历史没有 opaque 元数据，按 canonical 工具调用重建
`function_call`/`tool_use`。如果同一作用域曾经需要 opaque 回传、随后因淘汰
或存档损坏无法命中对应元数据，则请求失败关闭，不会静默发送不完整的历史。

## 验证

在仓库根目录执行：

```powershell
& 'E:\others\Projects\Minecraft-Mod\Chunk-Plan\.XMTEMP\gradle-8.14.2\bin\gradle.bat' :common:test :neoforge:build
```

测试覆盖 URL 解析、三协议工具多轮、usage 归一化、HTTP 错误、配置
roundtrip 和旧 OpenAI 工具历史切换到 Responses/Anthropic。界面和实际
Minecraft 客户端运行未在本 PR 的自动测试中实机验证。

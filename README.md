# TLM Agent Tweaks

车万女仆（[Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)）的 AI 功能增强附属 mod（NeoForge 1.21.1，需 TLM ≥ 1.5.3）。

## 安装要求

| 场景 | 结果 |
| --- | --- |
| 服务端装、客户端不装 | ✅ 可正常进服游玩，服务端 token 记账/压缩/统计照常运行；客户端只是看不到本 mod 的任何按钮与界面 |
| 服务端不装、客户端装 | ❌ 禁止进入：客户端登录后会检测到服务器缺少本 mod 并主动断开（单人游戏不受此限制） |
| 双端都装 | ✅ 完整功能 |

网络通道以 optional 方式注册，配合客户端登录守卫实现上述非对称语义。

## 功能

1. **AI 功能增强配置栏**：在 TLM 的 Cloth Config 设置页「AI 功能」大类下追加与 LLM/TTS/STT 并列的「AI 功能增强」子栏目（挂载方式与 Self-Talk 一致）。
2. **手动压缩聊天记录**：在历史聊天记录界面「清空聊天记录」按钮上方新增「压缩聊天记录」按钮，调用车万女仆原生 LLM 摘要压缩；管理员可在配置栏设置阈值（K token），上次对话用量未达阈值时不允许压缩，防止反复压缩浪费 token。压缩成功后 TLM 会将用量计数清零，天然防连点。
3. **token 明细统计**：按输入 / 输出 / 缓存命中 / 缓存写入四类记录每次 LLM 请求（覆盖普通聊天、自言自语、互聊、自动人设、知识库问答、历史压缩摘要），兼容 OpenAI（`prompt_tokens_details.cached_tokens`）、DeepSeek（`prompt_cache_hit_tokens`）、Anthropic 系代理（`cache_creation_input_tokens`）三种缓存计费字段口径。管理员可在 TLM 站点编辑器里点「💰 价格」为每个模型设置四类单价，支持 CNY / USD 双币种，**两币种独立存储、不按汇率换算**。
4. **统一设置入口与 Token 统计详情页**：AI 聊天窗口提供 Agent Tweaks 设置页；与新版 Self-Talk 合装时，由 Self-Talk 的 💬 物理入口统一进入，并用左上角两个图标页签切换 Agent Tweaks / Self-Talk，Self-Talk 原有业务界面不迁移。仅管理员（默认 OP 2 级，可配置）可查看统计，包含三视图：
   - **玩家排行**：一玩家一行 + 四色横向堆叠柱状图；
   - **玩家明细**：四类 token、缓存命中率、CNY/USD 双币种消费金额（均点击排行行进入，‹ › 切换玩家）；
   - **服务器总览**：粒度（时间窗口）可选 1/3/7/14/30/60/90/180/365day/all，窗口内按天出柱（180d/365d 自动按周、all 按月合并），四色竖向堆叠柱 + CNY/USD 双线成本折线图。

5. **请求失败自动重试**：模型站点返回 429 或 500/502/503/504 时，同一轮对话内自动重发，每次请求最多额外重试 **2 次**、每次固定等待 **5 秒**（忽略 `Retry-After`，不做指数退避）。429 一律重试，不区分短期限流与配额耗尽。流式请求在收到响应头之前断连时，保持 `stream:true` 再发一次（独立 1 次额度，不占状态码重试次数）；额度用尽后直接失败，不改用非流式。重试期间只更新等待气泡，不输出聊天栏错误、不提前调用失败回调。站点被限流后进入 5 秒冷却，冷却期间尚未发出的请求排队延后，避免把一个正在限流的站点推向更深的限流。整轮对话（含全部工具续接）共享 240 秒固定预算。规则写死，仅提供 `retry.enabled` 总开关。详见 [docs/llm-request-retry.md](docs/llm-request-retry.md)。

6. **思考链气泡滚动**：把模型的思考（reasoning）增量滚动显示在 TLM 原生「少女思考中」等待气泡内——单行、定宽、超宽时左侧填充右侧滚出；换行即清空重填。进入工具执行阶段时切回 TLM 原生的工具提示文本，请求结束/失败/取消时清空。流式断流改用普通模式重发时**不补播**中断前的片段，并在回退请求真正发出后显示「已改用普通模式发送，等待响应…」；传输重试与状态码重试则从空窗口恢复滚动。每个玩家可在设置页顶部按钮单独开关（默认开启，仅影响本机显示，不改变请求）。详见 [docs/thinking-bubble.md](docs/thinking-bubble.md)。

## 构建

本仓库不携带 gradle wrapper（沿用本地惯例），使用本机缓存的 Gradle 8.14.2（如 Chunk-Plan 的 `.XMTEMP/gradle-8.14.2/bin/gradle`）：

```bash
gradle :common:test      # 统计引擎单元测试
gradle :neoforge:build   # 产出 neoforge/build/libs/tlm-agent-tweaks-<版本>-neoforge-1.21.1.jar
gradle :neoforge:runServer   # 开发服务端冒烟（run/ 下已置 eula）
gradle :neoforge:runClient   # 开发客户端
```

JDK 21 toolchain（路径固定在 `gradle.properties` 的 `org.gradle.java.installations.paths`）。

## 工程结构（Chunk-Plan 式单分支混合工程）

```
common/     纯 Java 统计引擎，零 Minecraft/TLM 依赖（Gson + slf4j 编译期），含 JUnit
neoforge/   NeoForge 21.1 薄壳：入口/配置/mixin/网络/GUI/Cloth 集成
            （common 源码经 sourceSets.java.srcDir 并入编译，不产独立 jar）
```

将来的 Forge 1.20.1 迁移只需新增 `forge` 薄壳子项目（Java 17 + ForgeGradle 6，同样并入 common 源码），无需分叉分支。

## 配置

`config/tlm_agent_tweaks-common.toml`（也可在游戏内 TLM 设置页 → AI 功能 → AI 功能增强 中修改）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `compress.manualThresholdK` | `8` | 手动压缩阈值（K token） |
| `stats.enabled` | `true` | token 统计总开关 |
| `stats.adminPermissionLevel` | `2` | 查看统计/设置价格所需 OP 等级 |
| `stats.recordSummaryCalls` | `true` | 压缩摘要请求是否计入统计 |
| `retry.enabled` | `true` | 请求失败自动重试总开关（关闭后无重试、无站点冷却、无传输重试；流式断流改用非流式重发不受影响） |

客户端本地配置 `config/tlm_agent_tweaks-client.toml`（仅客户端，不同步到服务器）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `thinking.showReasoning` | `true` | 是否在本机等待气泡中滚动显示模型思考链（游戏内 Agent Tweaks 设置页顶部按钮可切换） |

## 数据文件

- 统计数据：`<世界存档>/tlm_agent_tweaks/stats-yyyy-MM.json`（按月分文件，原子写入，服务端每 5 分钟与停服时落盘）
- 模型价格：`config/tlm_agent_tweaks/prices.json`（全局，不随世界）

**成本按查询时现价计算**：修改价格会追溯影响全部历史成本显示（记录只存 token 数量，不存储当时价格）。

## 价格设置的口径

- 模型列表只从管理员配置的可用站点表读取（与 TLM 的 LLM 模型选择弹窗同源），新建未保存的站点无法设价；
- 价格单位为「每百万 token」；四类：输入 / 输出 / 缓存命中 / 缓存写入；
- CNY 与 USD 各自独立配置，可同时存在，互不换算；某币种未配置任何模型时，统计页不显示该币种。

## 与 Maid Self-Talk 的兼容

- 互不为前置、可同装也可只装其一；
- 未装 Self-Talk 时，本 mod 在 AI 聊天窗口注册第 4 槽的 🧩 独立设置入口；
- 与提供 `SelfTalkSettingsBridge` 的新版 Self-Talk 合装时，Self-Talk 保留并注册第 4 槽 💬 物理入口，点击后委托本 mod 打开统一导航；Agent Tweaks 与 Self-Talk 页面保持为两个真实独立 Screen，通过左上角 `20x20` 图标页签切换，页签位于 `(6, 4)` 并提供完整 tooltip；
- 切出 Self-Talk 前由导航调用桥的 `prepareForNavigation`：有浮层时禁止切换，其余情况先保存 Prompt 再切换；每次重新进入 Self-Talk 都新建页面以重新请求配置，不缓存旧 dirty 回包守卫；
- ESC 返回原始 AI 聊天屏；Token 统计页的返回目标为 Agent Tweaks 设置页；
- 与不提供桥的旧版 Self-Talk 合装时，保留其第 4 槽原入口，本 mod 使用第 5 槽 🧩 提供独立设置入口；旧版无统一导航提示由入口 tooltip 与本文档说明，避免静默丢失入口；
- Self-Talk 自身业务代码不迁移；当前统一导航仅在 NeoForge 1.21.1 侧实现，Forge 1.20.1 迁移尚未覆盖；
- 本 mod 在 `LLMCallback.onSuccess` / `onFailure` 的 HEAD 清理整轮预算并拦截超时后的迟到结果；状态码重试等待期间不调用 `onFailure`，整轮超时才派发最终失败。与 Self-Talk 的注入方法使用不同名称；
- 已知微小行为：手动压缩的摘要回调会被 Self-Talk 视作一次「玩家聊天结束」而重置其聊天状态标记，影响可忽略。

## 统计口径备注

- 归属：按女仆主人 UUID 记账（自言自语、互聊等女仆自发请求同样计入其主人名下）；
- 输入 = `prompt_tokens − 缓存命中`（下限 0）；输出 = `completion_tokens`；
- 缓存命中率 = 缓存命中 / prompt；
- 记录点位于 `LLMOpenAIClient.handle` 头部，直接解析原始响应体，不依赖 TLM 的 Usage 解析（TLM 只保留 total_tokens）。

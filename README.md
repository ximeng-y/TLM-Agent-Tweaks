# TLM Agent Tweaks

车万女仆（[Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)）的 AI 功能增强附属 mod（NeoForge 1.21.1，需 TLM ≥ 1.5.3）。

## 功能

1. **AI 功能增强配置栏**：在 TLM 的 Cloth Config 设置页「AI 功能」大类下追加与 LLM/TTS/STT 并列的「AI 功能增强」子栏目（挂载方式与 Self-Talk 一致）。
2. **手动压缩聊天记录**：在历史聊天记录界面「清空聊天记录」按钮上方新增「压缩聊天记录」按钮，调用车万女仆原生 LLM 摘要压缩；管理员可在配置栏设置阈值（K token），上次对话用量未达阈值时不允许压缩，防止反复压缩浪费 token。压缩成功后 TLM 会将用量计数清零，天然防连点。
3. **token 明细统计**：按输入 / 输出 / 缓存命中 / 缓存写入四类记录每次 LLM 请求（覆盖普通聊天、自言自语、互聊、自动人设、知识库问答、历史压缩摘要），兼容 OpenAI（`prompt_tokens_details.cached_tokens`）、DeepSeek（`prompt_cache_hit_tokens`）、Anthropic 系代理（`cache_creation_input_tokens`）三种缓存计费字段口径。管理员可在 TLM 站点编辑器里点「💰 价格」为每个模型设置四类单价，支持 CNY / USD 双币种，**两币种独立存储、不按汇率换算**。
4. **Token 统计详情页**：入口在 AI 聊天窗口的 💬 按钮（未装 Self-Talk 时由本 mod 注册该按钮；装了 Self-Talk 时复用其按钮并在其设置屏内追加「Token 统计」按钮，两者互不冲突、互不为前置）。仅管理员（默认 OP 2 级，可配置）可查看，包含三视图：
   - **玩家排行**：一玩家一行 + 四色横向堆叠柱状图；
   - **玩家明细**：四类 token、缓存命中率、CNY/USD 双币种消费金额（均点击排行行进入，‹ › 切换玩家）；
   - **服务器总览**：粒度（时间窗口）可选 1/3/7/14/30/60/90/180/365day/all，窗口内按天出柱（180d/365d 自动按周、all 按月合并），四色竖向堆叠柱 + CNY/USD 双线成本折线图。

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

`config/tlmagenttweaks-common.toml`（也可在游戏内 TLM 设置页 → AI 功能 → AI 功能增强 中修改）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `compress.manualThresholdK` | `8` | 手动压缩阈值（K token） |
| `stats.enabled` | `true` | token 统计总开关 |
| `stats.adminPermissionLevel` | `2` | 查看统计/设置价格所需 OP 等级 |
| `stats.recordSummaryCalls` | `true` | 压缩摘要请求是否计入统计 |

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
- AI 聊天窗口左侧第 4 槽的 💬 按钮：谁装了 Self-Talk 就由谁注册（互斥注册，不重叠）；
- 装 Self-Talk 时，「Token 统计」按钮经 `@Pseudo` mixin（字符串目标 + 目标类不存在时自动跳过）注入其玩家设置屏底部，不与其原有 4 个开关按钮及提示文字重叠；
- 双方 mixin 目标类完全不相交（本 mod 只注入 `LLMOpenAIClient.handle`、TLM 的三个界面与压缩管理器；Self-Talk 注入 `MaidAIChatManager.normalChat`、`LLMCallback` 等），无注入冲突；
- 已知微小行为：手动压缩的摘要回调会被 Self-Talk 视作一次「玩家聊天结束」而重置其聊天状态标记，影响可忽略。

## 统计口径备注

- 归属：按女仆主人 UUID 记账（自言自语、互聊等女仆自发请求同样计入其主人名下）；
- 输入 = `prompt_tokens − 缓存命中`（下限 0）；输出 = `completion_tokens`；
- 缓存命中率 = 缓存命中 / prompt；
- 记录点位于 `LLMOpenAIClient.handle` 头部，直接解析原始响应体，不依赖 TLM 的 Usage 解析（TLM 只保留 total_tokens）。

# TLM Agent Tweaks

给 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）添加「多协议接入 / 思考链显示 / 请求重试 / Token 与费用统计」等 AI 功能增强的附属 Mod。

## 功能

- **多协议接入**：原版 Touhou Little Maid 仅支持 OpenAI Chat Completions；本 Mod 额外支持 OpenAI Responses、Anthropic Messages，可为不同模型站点选择对应协议
- **思考链显示**：支持在「少女思考中」气泡里滚动显示模型返回的思考内容，执行工具时切回工具提示
- **请求失败自动重试**：遇到限流或部分服务器错误时自动重试，等待期间会在气泡中显示状态
- **流式请求支持**：支持以流式方式请求模型；站点不支持流式或流式响应中断时，可自动改用普通请求重新获取结果
- **手动压缩聊天记录**：原版 Touhou Little Maid 仅支持自动压缩；本 Mod 支持在聊天历史界面主动生成历史摘要，保留近期对话；可设置最低 Token 用量，避免频繁压缩造成额外消耗
- **Token 明细统计**：分别记录输入、输出、缓存命中、缓存写入用量，支持按玩家查看及服务器总览
- **费用统计**：为每个站点下的模型设置价格，根据 Token 用量计算费用，支持人民币与美元计价
- **图表与玩家排行**：查看玩家用量排行、玩家明细、服务器用量与费用趋势，支持选择统计时间范围

## 环境要求

| 加载器 | Minecraft | Java | Touhou Little Maid |
| --- | --- | --- | --- |
| NeoForge | 1.21.1 | 21 | 1.5.3 |
| Forge | 1.20.1 | 17 | 1.5.3 |

请安装与 Minecraft 版本及加载器对应的文件。

Cloth Config 为可选依赖，用于在车万女仆设置页中显示「AI 功能增强」配置栏目。

## 安装

1. 安装对应版本的 Touhou Little Maid 及其前置依赖
2. 将本 Mod 对应版本的 jar 放入 `mods` 文件夹
3. 在车万女仆中配置可用的 AI 站点和模型

单人游戏正常安装即可。多人服务器的安装要求如下：

| 服务端 | 客户端 | 使用情况 |
| --- | --- | --- |
| 已安装 | 已安装 | 可使用完整功能 |
| 已安装 | 未安装 | 可以正常进服，服务端请求增强与统计继续运行，但该玩家看不到本 Mod 的按钮、统计界面和思考链显示 |
| 未安装 | 已安装 | 无法进入服务器，客户端会提示服务器缺少本 Mod |

## 使用

### 配置站点协议

打开车万女仆的 **LLM 站点编辑器**，点击 URL 输入框右侧的「协议设置」：

- **Chat Completions**：使用 OpenAI Chat Completions 兼容接口
- **Responses**：使用 OpenAI Responses 接口
- **Anthropic Messages**：使用 Anthropic Messages 接口

URL 支持两种填写方式：

- **基地址**：由 Mod 根据所选协议补全接口路径
- **完整端点**：直接使用填写的完整请求地址

页面会显示最终请求 URL，方便检查。协议需要按服务商实际提供的接口选择，不会自动识别。

### 玩家独立设置

对着女仆打开 AI 聊天输入界面（通常是按 T 键），点击左侧 **🧩** 按钮打开 Agent Tweaks 设置：

- **显示思考链**：默认开启，只影响自己看到的气泡，不改变模型请求，也不影响其他玩家
- **Token 消耗详情**：打开统计界面，需要管理员权限

> 思考链显示需要模型和站点实际返回可展示的思考内容。它只改变「少女思考中」气泡，不会把思考内容写入聊天记录或交给 TTS 播报，也不改变最终回答原有的展示方式。

### 手动压缩聊天记录

打开女仆的历史聊天记录界面，点击「清空聊天记录」上方的 **「压缩聊天记录」**：

- 仅女仆主人可以发起压缩
- 将较早的聊天内容总结为摘要，降低上下文长度
- 默认要求上次对话用量达到 **8K Token 的阈值（管理员可调整）**，并有足够的历史内容可供压缩
- 压缩过程中不能重复发起，完成后会刷新摘要与聊天记录

**压缩会调用模型并产生 Token 消耗。** 这是额外提供的手动入口，不替代车万女仆原有的自动压缩。

### Token 与费用统计

进入 **Agent Tweaks 设置 → Token 消耗详情**：

- **玩家排行**：按玩家查看用量，通过不同颜色区分四类 Token
- **玩家明细**：查看具体用量、缓存命中率与费用，可通过下拉框切换玩家
- **服务器总览**：查看所选时间范围内的用量柱状图与费用趋势

统计按**女仆主人**归属，自言自语和互聊等女仆主动发起的请求也计入主人名下。

查看统计与设置模型价格默认需要 **OP 2 级权限**，管理员可调整权限要求。

### 设置模型价格

在 **LLM 站点编辑器**中，点击对应模型所在行的 **价格按钮**：

- 分别填写输入、输出、缓存命中、缓存写入四类价格
- 单位为**每百万 Token**
- 人民币与美元独立设置，**不会自动按汇率换算**
- 价格按站点和模型分别保存，同名模型在不同站点可以使用不同价格

**费用仅供参考和估算，实际仍以服务商端消耗为准。**

- 价格需要手动填写，不会自动获取
- 历史费用按查询时的当前价格计算，修改价格也会影响历史费用显示
- 用量依赖接口返回的数据；未返回的用量不会凭空估算，失败或中断请求产生的实际费用可能没有被完整记录

### 全局配置

安装 Cloth Config 后，在车万女仆设置页的 **「AI 功能 → AI 功能增强」** 中调整：

- **手动压缩阈值**：默认 8K Token
- **Token 统计**：默认开启
- **管理员权限等级**：默认 OP 2 级
- **记录摘要请求**：默认开启，将历史压缩摘要的用量计入统计

### 自动重试与流式回退

自动重试默认开启：

- 遇到 **429、500、502、503、504** 时，每次请求最多额外重试 **2 次**
- 每次重试先等待 **5 秒**；遇到站点排队时，实际发送可能更晚
- 流式请求在收到响应头之前失败时，另有一次保持流式的重发机会
- 站点明确不支持流式、流式中断或流式重发仍失败时，可再改用普通模式请求一次
- 同一轮对话及其工具续接共享 **240 秒**总时限，不会无限等待

重试次数、等待时间与总时限目前不可单独调整。自动重试总开关为公共配置中的 `retry.enabled`，**关闭后仍保留流式异常的普通模式回退**。

**重试和回退会重新向站点发送请求，可能产生额外费用，也不保证一定成功。**

## 与其他附属 Mod 配合

### TLM Self-Talk

- 两个 Mod 互不为前置，可分别安装
- 同装后，Self-Talk 发起的自言自语与互聊请求也可使用本 Mod 的协议适配、请求增强和 Token 统计
- 支持统一导航的版本可切换两个独立设置页；不支持的版本保留各自入口
- 自言自语、欢迎语、互聊及其提示词仍由 Self-Talk 管理

### TouhouLittleMaid-MIMO-TTS

可与 MiMo TTS 附属 Mod 配合使用。本 Mod 负责 LLM 请求与相关展示，不替代其语音合成功能。

## 许可

MIT

---

# TLM Agent Tweaks (English)

An add-on for [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) that enhances its AI features with **multiple API protocols / reasoning display / request retries / token and cost statistics**.

## Features

- **Multiple API protocols**: Touhou Little Maid originally supports only OpenAI Chat Completions. This mod additionally supports OpenAI Responses and Anthropic Messages, with a protocol setting for each model site
- **Reasoning display**: Supports scrolling the model's reasoning inside the "The maid is thinking..." bubble, switching back to tool-call hints while tools are running
- **Automatic request retries**: Automatically retries rate-limited requests and certain server errors, with status messages shown in the bubble while waiting
- **Streaming request support**: Supports streaming requests to models. If a site does not support streaming or a stream is interrupted, the mod can automatically send a non-streaming request to retrieve the result
- **Manual chat history compression**: Touhou Little Maid originally supports only automatic compression. This mod lets you generate a history summary from the chat history screen while retaining recent conversations. A minimum token threshold can be set to avoid extra usage from frequent compression
- **Detailed token statistics**: Tracks input, output, cache read, and cache write tokens separately, with per-player views and a server overview
- **Cost statistics**: Set model prices for each site and calculate costs from token usage, with support for CNY and USD
- **Charts and player rankings**: View player usage rankings, individual player details, and server-wide usage and cost trends over a selected time range

## Requirements

| Loader | Minecraft | Java | Touhou Little Maid |
| --- | --- | --- | --- |
| NeoForge | 1.21.1 | 21 | 1.5.3 |
| Forge | 1.20.1 | 17 | 1.5.3 |

Install the file that matches your Minecraft version and mod loader.

Cloth Config is an optional dependency that adds the Agent Tweaks configuration section to Touhou Little Maid's settings.

## Installation

1. Install the matching version of Touhou Little Maid and its required dependencies
2. Place the matching jar for this mod in the `mods` folder
3. Configure an available AI site and model in Touhou Little Maid

For single-player, install the mod normally. Multiplayer installation requirements are as follows:

| Server | Client | Behavior |
| --- | --- | --- |
| Installed | Installed | All features are available |
| Installed | Not installed | The player can join normally. Server-side request enhancements and statistics remain active, but the player cannot see this mod's buttons, statistics screens, or reasoning display |
| Not installed | Installed | The player cannot join; the client reports that the server is missing this mod |

## Usage

### Configure the site protocol

Open Touhou Little Maid's **LLM site editor** and click "Protocol Settings" to the right of the URL field:

- **Chat Completions**: Uses an OpenAI Chat Completions-compatible API
- **Responses**: Uses the OpenAI Responses API
- **Anthropic Messages**: Uses the Anthropic Messages API

There are two URL modes:

- **Base URL**: The mod appends the API path for the selected protocol
- **Exact endpoint**: Uses the complete request URL exactly as entered

The screen previews the final request URL for checking. Choose the protocol according to the API your provider actually offers; it is not detected automatically.

### Per-player settings

Open the maid's AI chat input screen while looking at her (usually by pressing T), then click the **🧩** button on the left to open Agent Tweaks settings:

- **Show Thinking**: On by default. Only affects the bubbles you see; it does not change model requests or affect other players
- **Token Stats**: Opens the statistics screen; administrator permission is required

> Reasoning display requires the model and site to actually return displayable reasoning content. It only changes the "The maid is thinking..." bubble. Reasoning is not written to chat history or sent to TTS, and the final answer's existing presentation is unchanged.

### Manually compress chat history

Open the maid's chat history screen and click **"Compress History"** above "Clear History Chat":

- Only the maid's owner can start compression
- Summarizes older conversations to reduce context length
- By default, the previous conversation's usage must reach the **8K-token threshold (adjustable by administrators)**, and there must be enough history to compress
- Another compression cannot be started while one is running; the summary and history refresh when it finishes

**Compression calls the model and consumes tokens.** This is an additional manual option, not a replacement for Touhou Little Maid's original automatic compression.

### Token and cost statistics

Go to **Agent Tweaks settings → Token Stats**:

- **Ranking**: View usage by player, with different colors for the four token categories
- **Player**: View detailed usage, cache hit rate, and costs; switch players with the dropdown
- **Server**: View usage bar charts and cost trends for the selected time range

Usage is attributed to the **maid's owner**. Requests initiated by maids, such as self-talk and maid-to-maid chats, are also counted under their owner.

Viewing statistics and setting model prices requires **OP permission level 2** by default. Administrators can change this requirement.

### Set model prices

In the **LLM site editor**, click the **price button** on the corresponding model's row:

- Enter separate prices for input, output, cache read, and cache write tokens
- Prices are **per million tokens**
- CNY and USD are configured independently, with **no automatic exchange-rate conversion**
- Prices are saved separately for each site and model, so the same model can have different prices on different sites

**Costs are for reference and estimation only; actual usage and charges are determined by the service provider.**

- Prices must be entered manually and are not fetched automatically
- Historical costs are calculated using the current prices at query time; changing prices also changes the displayed historical costs
- Usage depends on data returned by the API. Missing usage is not estimated, and actual charges from failed or interrupted requests may not be fully recorded

### Global configuration

With Cloth Config installed, open the **AI features → Agent Tweaks** section in Touhou Little Maid's settings to adjust:

- **Manual Compress Threshold**: 8K tokens by default
- **Enable token statistics**: On by default
- **Stats view permission level**: OP level 2 by default
- **Count summary requests in stats**: On by default; includes usage from chat history compression summaries

### Automatic retries and streaming fallback

Automatic retries are enabled by default:

- For **429, 500, 502, 503, and 504**, each request can be retried up to **2 additional times**
- Each retry waits **5 seconds** first; site queuing may delay the actual send further
- If a streaming request fails before response headers arrive, it has one additional chance to be resent as a streaming request
- If the site explicitly does not support streaming, the stream is interrupted, or the streaming resend still fails, the request can be sent once more in non-streaming mode
- A conversation and all of its tool-call continuations share a **240-second** total time limit, rather than waiting indefinitely

The retry count, wait time, and total time limit cannot currently be adjusted individually. The automatic retry switch is `retry.enabled` in the common configuration. **Disabling it still preserves the non-streaming fallback for streaming failures.**

**Retries and fallbacks send new requests to the site, may incur additional charges, and are not guaranteed to succeed.**

## Compatibility with other add-ons

### TLM Self-Talk

- Neither mod requires the other; they can be installed independently
- When both are installed, Self-Talk's self-talk and maid-to-maid chat requests can also use this mod's protocol adaptation, request enhancements, and token statistics
- Versions supporting unified navigation can switch between the two independent settings pages; other versions retain separate entry buttons
- Self-talk, welcome greetings, maid-to-maid chats, and their prompts remain managed by Self-Talk

### TouhouLittleMaid-MIMO-TTS

Can be used alongside the MiMo TTS add-on. This mod handles LLM requests and related display features; it does not replace MiMo TTS's speech synthesis.

## License

MIT

## 项目简介

本项目开发「TLM-Agent-Tweaks」，一个车万女仆（Touhou Little Maid）的附属 mod，主要目的是增强车万女仆 mod 的 Agent 相关功能的使用体验。

当前目标平台：**NeoForge 1.21.1 + TLM 1.5.3**（后续迁移 Forge 1.20.1）。

## 工程结构与构建

Chunk-Plan 式单分支混合工程（见 `E:\others\Projects\Minecraft-Mod\Chunk-Plan`）：

- `common/`：纯 Java 统计引擎，零 Minecraft/TLM import（仅 Gson+slf4j compileOnly），含 JUnit 测试
- `neoforge/`：NeoForge 薄壳（入口/配置/mixin/网络/GUI/Cloth 集成），经 `sourceSets.java.srcDir` 并入 common 源码编译
- Forge 1.20.1 迁移时新增 `forge/` 薄壳子项目（Java 17 + ForgeGradle 6），不采用 Self-Talk 的双分支方式

构建不携带 gradle wrapper：使用本机缓存 Gradle 8.14.2（如 Chunk-Plan 仓库 `.XMTEMP/gradle-8.14.2/bin/gradle`）。JDK 21 toolchain 路径固定于 `gradle.properties`。

常用任务：`:common:test`（引擎单测）、`:neoforge:build`（产 jar）、`:neoforge:runServer` / `:neoforge:runClient`（开发运行，`neoforge/run/eula.txt` 已置 true，已被 gitignore）。

TLM 1.5.3 解包参考源码位于 Self-Talk 仓库 `E:\others\Projects\Minecraft-Mod\TouhouLittleMaid-Self-Talk\.XMTEMP\tlm-extract\`，修改 mixin 注入点前先对照该源码。

## 兼容性

本项目需兼容以下两个附属 mod（功能互不冲突、可同环境共存）。两个项目的源码以 junction 软链接挂在本仓库 `compat/` 下（本地对照开发用，已被 gitignore 忽略），开发时直接读链接目录：

- `compat/TouhouLittleMaid-MIMO-TTS`：为车万女仆 AI 聊天接入小米 MiMo 语音合成
- `compat/TouhouLittleMaid-Self-Talk`：车万女仆 AI 自言自语 + 主人登录欢迎语

## 版本控制

- 提交信息使用 Conventional Commits 前缀（`feat:` / `fix:` / `docs:` / `refactor:` / `chore:` 等），后接简洁中文描述
- `main` 为主分支；功能开发开 `feat/xxx` 分支，完成后合并回 `main`；个人定制分支使用 `cc/xxx` 前缀
- 发布按语义化版本打 tag（`vX.Y.Z` 格式）

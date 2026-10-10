## 项目简介

本项目开发「TLM-Agent-Tweaks」，一个车万女仆（Touhou Little Maid）的附属 mod，主要目的是增强车万女仆 mod 的 Agent 相关功能的使用体验。

当前目标平台：**Forge 1.20.1（Java 17）与 NeoForge 1.21.1（Java 21）双加载器 + TLM 1.5.3**。

## 工程结构与构建

Chunk-Plan 式单分支混合工程（见 `E:\others\Projects\Minecraft-Mod\Chunk-Plan`）：

- `common/`：纯 Java 统计引擎，零 Minecraft/TLM import（仅 Gson+slf4j compileOnly），含 JUnit 测试
- `neoforge/`：NeoForge 薄壳（入口/配置/mixin/网络/GUI/Cloth 集成），经 `sourceSets.java.srcDir` 并入 common 源码编译
- `forge/`：Forge 薄壳，职责与 neoforge 相同，Java 17 + ForgeGradle 6 + MixinGradle，同样并入 common 源码
- 两个薄壳共用同一份 common 源码，加载器差异各自在薄壳内消化；**不分叉分支**，区分只发生在出包时（不采用 Self-Talk 的双分支方式）

构建不携带 gradle wrapper：使用本机缓存 Gradle 8.14.2（如 Chunk-Plan 仓库 `.XMTEMP/gradle-8.14.2/bin/gradle`）。JDK 路径固定于 `gradle.properties`（common/neoforge 用 21，forge 用 17）。

常用任务：`:common:test`（引擎单测）、`:neoforge:build` / `:forge:build`（产 jar）、两个薄壳各自的 `runServer` / `runClient`（开发运行，`<薄壳>/run/` 下已置 eula，已被 gitignore）。

TLM 1.5.3 解包参考源码位于 Self-Talk 仓库 `E:\others\Projects\Minecraft-Mod\TouhouLittleMaid-Self-Talk\.XMTEMP\tlm-extract\`（1.21.1）；1.20.1 侧见同仓库 `.XMTEMP\tlm-1.20.1\`（git 分支 `1.20`）。

### Forge 侧的 mixin 注意事项

Forge 1.20.1 的生产 jar 里，TLM 自己覆写的**原版方法**是 SRG 名（`init` → `m_7856_`、`tick` → `m_86600_`、`render(GuiGraphics,...)` → `m_88315_`），而 TLM **自有方法名保持可读**（`addLeftButtons`、`buildSite`、`handle`、`onSuccess` 等）。因此：

- **TLM 类的成员一律用 `remap = false`。** TLM 不在 MC 的 reobf 映射集里，`remap = true` 对它的任何成员都会报 `Unable to locate obfuscation mapping`——与该方法名是否可读无关。
- `remap = false` 是**按字面名直取**，不做任何反查。对同一个成员，dev（`fg.deobf` 后的 mojmap）与 prod（SRG）名字不同，所以**原版覆写方法没有一种写法能同时命中两侧**：写 `init` 则 prod 找不到，写 `m_7856_` 则 dev 找不到。
- **因此注入点优先选 TLM 自有方法**（`addLeftButtons`、`createRows`、`buildSite`、`handle`、`onSuccess`、`renderSummaryPanel` 等，名字两侧一致），当前的 mixin 全部按此实现。确需挂原版方法时，先 `javap` 生产 jar 确认 SRG 名，并接受只能在生产环境命中。
- `@Shadow` / `@Accessor` 指向 TLM 自有字段同样要写 `remap = false`，否则编译期会刷 `Unable to locate obfuscation mapping` 警告；
- 禁止用字符串反射取原版方法名（`getDeclaredMethod("init")` 等在 prod 会静默失败），改为编译期调用；
- 目标屏的父类是 `Screen` 时，mixin 类可 `extends Screen` 以直接调用 protected 的 `addRenderableWidget`（父类不参与合并，构造器不会被执行）；
- mixin 配置由 jar manifest 的 `MixinConfigs` 属性声明（`mods.toml` 不解析 `[[mixins]]` 段）。

## 兼容性

本项目需兼容以下两个附属 mod（功能互不冲突、可同环境共存）。两个项目的源码以 junction 软链接挂在本仓库 `compat/` 下（本地对照开发用，已被 gitignore 忽略），开发时直接读链接目录：

- `compat/TouhouLittleMaid-MIMO-TTS`：为车万女仆 AI 聊天接入小米 MiMo 语音合成
- `compat/TouhouLittleMaid-Self-Talk`：车万女仆 AI 自言自语 + 主人登录欢迎语

## 版本控制

- 提交信息使用 Conventional Commits 前缀（`feat:` / `fix:` / `docs:` / `refactor:` / `chore:` 等），后接简洁中文描述
- `main` 为主分支；功能开发开 `feat/xxx` 分支，完成后合并回 `main`；个人定制分支使用 `cc/xxx` 前缀
- 发布按语义化版本打 tag（`vX.Y.Z` 格式）

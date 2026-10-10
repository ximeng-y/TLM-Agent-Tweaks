# Forge 1.20.1 ↔ NeoForge 1.21.1 API 差异参考

本文件面向 TLM-Agent-Tweaks 的 **Forge 1.20.1 移植**（后续新增 `forge/` 薄壳子项目，见根 `AGENTS.md`），逐项给出 1.20.1 Forge 写法 → 1.21.1 NeoForge 写法的对照。代码片段均给出两侧**真实可编译**的写法（按知识库原文整理，非凭记忆）。

适用范围：本项目是一个「无方块、无物品，只有 GUI / 网络 / 事件 / mixin / 配置」的附属 mod，因此**不涉及**世界生成、数据组件、物品模型、方块实体、能力系统等内容；这些条目在文档末尾统一标注「本项目不涉及」，无需展开。

> 知识库范围说明：NeoForge 侧为官方 Docs 1.21.1 本地化（63 页）；Forge 侧为官方 Docs 1.20.1 本地化（`10-Forge-Mod-Dev/` 64 块）。知识库中**没有** Mixin 系统文档（NeoForge 与 Forge 侧均无独立 mixin 块）、Cloth Config、`pack_format` 1.21.1 数值、Parchment 在 Forge 侧的专项文档——这些条目将明确标注「知识库未覆盖，需另行查证」。涉及 Mixin 的部分结合本项目实测踩坑记忆（`neoforge-mixin-gotchas`）给出。

---

## 1. 工程与构建

### 1.1 MDK 结构与 Gradle 插件

| 方面 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 构建插件 | ForgeGradle（`net.minecraftforge.gradle`） | NeoGradle 7（`net.neoforged.gradle`）或 ModDevGradle（`net.neoforged.moddev`） |
| Java | Java 17（官方推荐 Temurin 17） | Java 21（推荐 Microsoft/其他 64 位 JDK） |
| 开发运行任务 | `genIntellijRuns` / `genEclipseRuns` / `getVSCodeRuns`；命令行 `runClient` / `runServer` / `runData` / `runGameTestServer` | `runClient` / `runServer`（NeoGradle 7 默认生成 `runs/client`、`runs/server` 目录） |
| 可复用文件 | `gradle` 子目录、`build.gradle`、`gradlew`、`gradlew.bat`、`settings.gradle` | 同左 |

```gradle
// Forge 1.20.1（ForgeGradle）
buildscript {
    repositories { maven { url = 'https://maven.minecraftforge.net' } }
    dependencies { classpath group: 'net.minecraftforge.gradle', name: 'ForgeGradle', version: '6.0', changing: true }
}
apply plugin: 'net.minecraftforge.gradle'
minecraft {
    mappings channel: 'official', version: '1.20.1'
}
```

```gradle
// NeoForge 1.21.1（NeoGradle 7，本项目当前用法）
plugins {
    id 'net.neoforged.gradle' version '7.0.170'
}
minecraft {
    mappings channel: 'official', version: minecraft_version
}
```

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/01-Getting-Started/01-Getting-Started-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/01-Getting-Started/01-Getting-Started-1_21_1.md

### 1.2 映射：Parchment vs Mojang 官方映射

两侧官方 MDK 默认都用 Mojang 官方映射（`channel: 'official'`）。Parchment 是在官方映射之上叠加参数名/Javadoc 的层，**知识库只提供了 Parchment 的通用概念（在官方映射之上叠加）**，未提供 ForgeGradle/NeoGradle 侧的具体配置键，需另行查证。

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-Fabric-开发知识库/26_2/16-Porting/89-Mappings-Index-26_2.md（Parchment 概念，Fabric 侧）
> 知识库未覆盖：Forge/NeoForge 工程侧 Parchment 具体配置写法，需另行查证。

### 1.3 gradle.properties 关键键

NeoForge 1.21.1 侧 MDK 在 `gradle.properties` 中集中管理版本号并内联进 `neoforge.mods.toml`：

```properties
# NeoForge 1.21.1（本项目当前 gradle.properties 相关键）
minecraft_version=1.21.1
minecraft_version_range=[1.21.1,1.22)
neoforge_version=21.1.219
neo_version_range=[21.1.219,21.2)
loader_version_range=[1,)
mod_id=tlm_agent_tweaks
mod_name=TLM Agent Tweaks
mod_version=0.1.0
mod_group_id=com.xm2nd
mod_authors=xm2nd
mod_license=All Rights Reserved
mod_description=...
```

Forge 1.20.1 侧 MDK 同样使用 `gradle.properties`（ForgeGradle 也读取 `minecraft_version`、`forge_version` 等），但知识库的 1.20.1 Mod Files 笔记**未列出 gradle.properties 键表**，只给出 `mods.toml` 与 `build.gradle` 约定：

```gradle
// Forge 1.20.1 build.gradle 中与 gradle.properties 对应的常用写法
base.archivesName = 'mymod'
group = 'com.example'
version = '1.20-1.0.0.0'
```

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/01-Getting-Started/01-Getting-Started-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/01-Getting-Started/02-Getting-Started-Mod-Files-1_21_1.md
> 知识库未覆盖：Forge 1.20.1 侧 `gradle.properties` 的完整键表（如 `forge_version`、`loader_version_range` 命名），需另行查证。

---

## 2. Mod 元数据：mods.toml / neoforge.mods.toml

### 2.1 文件名与版本边界

| 项目 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 文件 | `src/main/resources/META-INF/mods.toml` | `src/main/resources/META-INF/neoforge.mods.toml` |
| `loaderVersion` | `"[46,)"`（Forge 1.20.1 = Forge 46.x） | `"[1,)"`（javafml 语言加载器版本为 1） |

### 2.2 顶层属性差异

```toml
# Forge 1.20.1 头部
modLoader="javafml"
loaderVersion="[46,)"
license="All Rights Reserved"
showAsResourcePack=false
```

```toml
# NeoForge 1.21.1 头部
modLoader="javafml"
loaderVersion="[1,)"
license="MIT"
showAsResourcePack=false
```

主要字段差异：

| 字段 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| `loaderVersion` | Forge 大版本号（1.20.1 为 `[46,)`） | javafml/lowcodefml 版本（目前为 `[1,)`） |
| `[[mods]]` 中 `displayTest` | `"MATCH_VERSION"` / `"IGNORE_ALL_VERSION"`（客户端-服务端兼容性显示） | 移除（NeoForge 不再有该字段，单侧 mod 用 `@Mod(díst)` 或依赖 `side` 表达） |
| `[[dependencies.<modid>]]` 中 `mandatory` | boolean，必填 | 改为 `type`：`"required"` / `"optional"` / `"incompatible"` / `"discouraged"` |
| `[[mixins]]` | 无（mixin 由 ForgeGradle + refmap 机制） | 有：`[[mixins]] config="<modid>.mixins.json"`（NeoForge 通过 `neoforge.mods.toml` 声明 mixin 配置） |
| `features` | `java_version="[17,)"` | `javaVersion="[17,)"`（键名驼峰化） |

### 2.3 依赖声明对照

```toml
# Forge 1.20.1 依赖（对 Forge 本身 + 车万女仆）
[[dependencies.examplemod]]
  modId="forge"
  mandatory=true
  versionRange="[46,)"
  ordering="NONE"
  side="BOTH"

[[dependencies.examplemod]]
  modId="touhou_little_maid"
  mandatory=true
  versionRange="[1.5.3,)"
  ordering="AFTER"
  side="BOTH"
```

```toml
# NeoForge 1.21.1 依赖（mandatory 改为 type）
[[dependencies.tlm_agent_tweaks]]
  modId="neoforge"
  type="required"
  versionRange="[21.1.219,)"
  ordering="NONE"
  side="BOTH"

[[dependencies.tlm_agent_tweaks]]
  modId="touhou_little_maid"
  type="required"
  versionRange="[1.5.3,)"
  ordering="AFTER"
  side="BOTH"
```

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/01-Getting-Started/02-Getting-Started-Mod-Files-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/01-Getting-Started/02-Getting-Started-Mod-Files-1_21_1.md

---

## 3. 入口类（@Mod 构造函数）

### 3.1 Forge 1.20.1：无参 / FMLJavaModLoadingContext

Forge 1.20.1 的 `@Mod` 构造函数默认**无参**，mod 总线通过 `FMLJavaModLoadingContext` 静态获取；也支持 `(FMLJavaModLoadingContext)` 与 `(IEventBus, ModContainer)` 等重载（知识库给出的是无参 + `FMLJavaModLoadingContext.get().getModEventBus()` 的写法）：

```java
// Forge 1.20.1
@Mod("examplemod")
public class Example {
    public Example() {
        // 初始化逻辑写在这里
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
    }
}
```

### 3.2 NeoForge 1.21.1：IEventBus / ModContainer 注入

NeoForge 1.21.1 把 `IEventBus`、`ModContainer`、`Dist` 作为构造参数**注入**，顺序任意、均非必需、不允许重复：

```java
// NeoForge 1.21.1
@Mod("examplemod")
public class ExampleMod {
    public ExampleMod(IEventBus modBus, ModContainer container) {
        // 初始化逻辑写在这里
    }
}
```

客户端专属逻辑用 `dist` 参数限定（可拆成单独的 `@Mod` 类）：

```java
// NeoForge 1.21.1 客户端专属入口
@Mod(value = "examplemod", dist = Dist.CLIENT)
public class ExampleModClient {
    public ExampleModClient(IEventBus modBus, Dist dist) {
        Minecraft.getInstance().whatever();
    }
}
```

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/01-Getting-Started/02-Getting-Started-Mod-Files-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/01-Getting-Started/02-Getting-Started-Mod-Files-1_21_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/02-Concepts/03-Concepts-Sides-1_21_1.md

---

## 4. 事件系统

### 4.1 总线对照

| 概念 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 主总线 | `MinecraftForge.EVENT_BUS` | `NeoForge.EVENT_BUS`（game 总线） |
| mod 总线获取 | `FMLJavaModLoadingContext.get().getModEventBus()`（静态） | 构造参数 `IEventBus modBus` 注入 |
| 注解驱动注册 | `@Mod.EventBusSubscriber(modid, bus = Bus.FORGE / Bus.MOD, value = Dist.CLIENT)` | `@EventBusSubscriber(modid, bus = Bus.GAME / Bus.MOD, value = Dist.CLIENT)`（包 `net.neoforged.bus.api`，NeoForge 侧类名去掉 `Mod.` 前缀） |
| 生命周期事件 | `FMLCommonSetupEvent` / `FMLClientSetupEvent` / `FMLDedicatedServerSetupEvent` 在 mod 总线，并行执行 | 同语义（`ParallelDispatchEvent`），NeoForge 侧也在 mod 总线 |

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/02-Concepts/02-Concepts-Events-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/02-Concepts/01-Concepts-Events-1_21_1.md

### 4.2 注册处理器对照

```java
// Forge 1.20.1：实例处理器注册到主总线
MinecraftForge.EVENT_BUS.register(new MyForgeEventHandler());

// Forge 1.20.1：静态处理器注册到主总线
MinecraftForge.EVENT_BUS.register(MyStaticForgeEventHandler.class);

// Forge 1.20.1：mod 总线处理器
FMLJavaModLoadingContext.get().getModEventBus().addListener(this::modEventHandler);
```

```java
// NeoForge 1.21.1：实例处理器
NeoForge.EVENT_BUS.register(new EventHandler());

// NeoForge 1.21.1：静态处理器
NeoForge.EVENT_BUS.register(EventHandler.class);

// NeoForge 1.21.1：mod 总线处理器（注入）
public YourMod(IEventBus modBus) {
    modBus.addListener(this::modEventHandler);
}
```

### 4.3 注解订阅器对照

```java
// Forge 1.20.1：客户端专属、Forge 总线
@Mod.EventBusSubscriber(modid = "mymod", bus = Bus.FORGE, value = Dist.CLIENT)
public class MyStaticClientOnlyEventHandler {
    @SubscribeEvent
    public static void drawLast(RenderLevelStageEvent event) { /* ... */ }
}
```

```java
// NeoForge 1.21.1：客户端专属、game 总线（注意类名与枚举名变化）
@EventBusSubscriber(value = Dist.CLIENT, modid = "mymod")
public class EventHandler {
    @SubscribeEvent
    public static void onLivingJump(LivingJumpEvent event) { /* ... */ }
}
```

### 4.4 常用事件类包路径对照（影响本项目的子集）

| 事件 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 客户端 tick | `net.minecraftforge.event.TickEvent$ClientTickEvent` | `net.neoforged.neoforge.event.tick.ClientTickEvent` |
| 服务端 tick | `net.minecraftforge.event.TickEvent$ServerTickEvent` | `net.neoforged.neoforge.event.tick.ServerTickEvent`（本项目记忆实测：`ServerTickEvent.Post` 在 `net.neoforged.neoforge.event.tick`） |
| 玩家登录/登出 | `net.minecraftforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent` / `PlayerLoggedOutEvent` | `net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent` / `PlayerLoggedOutEvent` |
| 服务器启动/停止 | `net.minecraftforge.event.server.ServerStartingEvent` / `ServerStoppingEvent` / `ServerStartedEvent` | `net.neoforged.neoforge.event.server.ServerStartingEvent` / `ServerStoppingEvent` / `ServerStartedEvent` |
| 屏幕打开/关闭 | `net.minecraftforge.client.event.ScreenEvent$Opening` / `ScreenEvent$Closing` | `net.neoforged.neoforge.client.event.ScreenEvent$Opening` / `ScreenEvent$Closing` |
| 注册菜单屏幕 | `FMLClientSetupEvent` 中 `MenuScreens.register(...)`（`enqueueWork` 内） | `RegisterMenuScreensEvent`（mod 总线、仅客户端） |

> 知识库说明：`TickEvent` / `ScreenEvent` 等在两侧知识库的事件块中仅给出总览与注册机制，**具体包路径**以项目记忆实测（`net.neoforged.neoforge.event.tick`）与两侧事件块确认，事件包路径的完整逐条对照知识库未覆盖，需另行查证。

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/02-Concepts/02-Concepts-Events-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/02-Concepts/01-Concepts-Events-1_21_1.md

---

## 5. 网络

### 5.1 Forge 1.20.1：SimpleChannel 完整样板

```java
// Forge 1.20.1
private static final String PROTOCOL_VERSION = "1";
public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
    new ResourceLocation("mymodid", "main"),
    () -> PROTOCOL_VERSION,
    PROTOCOL_VERSION::equals,
    PROTOCOL_VERSION::equals
);

// 注册数据包：鉴别符 id++，编码器/解码器/处理器三个方法引用
private static int ID = 0;
public static void register() {
    INSTANCE.registerMessage(ID++, MyMessage.class,
        MyMessage::encode,      // BiConsumer<MyMessage, FriendlyByteBuf>
        MyMessage::decode,      // Function<FriendlyByteBuf, MyMessage>
        MyMessage::handle);     // BiConsumer<MyMessage, Supplier<NetworkEvent.Context>>
}
```

消息处理器（网络线程默认，必须 `enqueueWork` 切主线程 + `setPacketHandled(true)`）：

```java
// Forge 1.20.1 数据包类内
public static void handle(MyMessage msg, Supplier<NetworkEvent.Context> ctx) {
    ctx.get().enqueueWork(() -> {
        ServerPlayer sender = ctx.get().getSender(); // 发送该包的客户端
        // 做事
    });
    ctx.get().setPacketHandled(true);
}

// Forge 1.20.1 服务端发往客户端，用 PacketDistributor 便捷分发
INSTANCE.send(PacketDistributor.PLAYER.with(serverPlayer), new MyMessage());   // 发给单个玩家
INSTANCE.send(PacketDistributor.TRACKING_CHUNK.with(levelChunk), new MyMessage()); // 发给追踪区块的所有玩家
INSTANCE.send(PacketDistributor.ALL.noArg(), new MyMessage());                 // 广播

// Forge 1.20.1 客户端处理器要用 DistExecutor 包裹，确保只在物理客户端执行
ctx.get().enqueueWork(() ->
    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlerClass.handlePacket(msg, ctx))
);
```

通道版本检查器（`NetworkRegistry` 定义的「元版本」）：

```java
// Forge 1.20.1：对端缺少该通道（仍是 Forge 端点）/ 对端是原版
// ABSENT / ACCEPT_VANILLA（知识库写作 ACCEPTVANILLA）
// 对两者返回 false 表示该通道必须存在于对端
```

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/07-Networking/02-Networking-SimpleImpl-1_20_1.md
> 知识库未覆盖：`PacketDistributor` 的完整枚举清单（`PLAYER`/`TRACKING_CHUNK`/`ALL` 之外），需另行查证。

### 5.2 NeoForge 1.21.1：PayloadRegistrar 完整样板

```java
// NeoForge 1.21.1 数据包定义（CustomPacketPayload + StreamCodec）
public record MyData(String name, int age) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MyData> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("mymod", "my_data"));

    public static final StreamCodec<ByteBuf, MyData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, MyData::name,
        ByteBufCodecs.VAR_INT, MyData::age,
        MyData::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

// NeoForge 1.21.1 注册（mod 事件总线上的 RegisterPayloadHandlersEvent）
@SubscribeEvent // on the mod event bus
public static void register(final RegisterPayloadHandlersEvent event) {
    final PayloadRegistrar registrar = event.registrar("1"); // 网络版本号
    registrar.playBidirectional(
        MyData.TYPE,
        MyData.STREAM_CODEC,
        new DirectionalPayloadHandler<>(
            ClientPayloadHandler::handleDataOnMain,
            ServerPayloadHandler::handleDataOnMain
        )
    );
}

// NeoForge 1.21.1 处理器（默认在主线程；指定网络线程则用 executesOn(HandlerThread.NETWORK)）
public class ClientPayloadHandler {
    public static void handleDataOnMain(final MyData data, final IPayloadContext context) {
        // 主线程直接做事
    }
}
```

线程切换（与 1.20.1 `ctx.get().enqueueWork` 对应）：

```java
// NeoForge 1.21.1：context.enqueueWork 返回 CompletableFuture，需处理异常
public class ClientPayloadHandler {
    public static void handleDataOnNetwork(final MyData data, final IPayloadContext context) {
        context.enqueueWork(() -> {
            // 主线程做事
        }).exceptionally(e -> {
            context.disconnect(Component.translatable("my_mod.networking.failed", e.getMessage()));
            return null;
        });
    }
}
```

发送：

```java
// NeoForge 1.21.1 发送（PacketDistributor 提供便捷方法）
PacketDistributor.sendToServer(new MyData(...));                  // 客户端→服务端
PacketDistributor.sendToPlayer(serverPlayer, new MyData(...));    // 服务端→单个玩家
PacketDistributor.sendToPlayersTrackingChunk(serverLevel, chunkPos, new MyData(...)); // 追踪区块的玩家
PacketDistributor.sendToAllPlayers(new MyData(...));              // 广播
```

限制：发往客户端 ≤ 1 MiB；发往服务端 < 32 KiB。

### 5.3 语义映射表

| Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|
| `INSTANCE.sendToServer(...)`（客户端） | `PacketDistributor.sendToServer(...)` |
| `INSTANCE.send(PacketDistributor.PLAYER.with(player), ...)` | `PacketDistributor.sendToPlayer(player, ...)` |
| `INSTANCE.send(PacketDistributor.TRACKING_CHUNK.with(chunk), ...)` | `PacketDistributor.sendToPlayersTrackingChunk(...)` |
| `INSTANCE.send(PacketDistributor.ALL.noArg(), ...)` | `PacketDistributor.sendToAllPlayers(...)` |
| 方向：`NetworkDirection.PLAY_TO_SERVER` / `PLAY_TO_CLIENT`（`SimpleChannel` 隐式按 `registerMessage` 方向） | 注册方法 `playToServer` / `playToClient` / `playBidirectional`（或 `configuration*` / `common*` 注册配置阶段） |
| 通道版本检查（`NetworkRegistry.newSimpleChannel(..., predicate, predicate)`，`ABSENT`/`ACCEPTVANILLA`） | `event.registrar("1")` 传网络版本字符串；可选通道语义由 `neoforge.mods.toml` 依赖 `type="optional"` 表达 |
| 线程切换 `ctx.get().enqueueWork(Runnable)` + `setPacketHandled(true)` | `context.enqueueWork(...)`（返回 `CompletableFuture`）+ `executesOn(HandlerThread.MAIN/NETWORK)` 控制注册线程 |
| `FriendlyByteBuf` 手写编解码 | `StreamCodec` / `ByteBufCodecs`（`STRING_UTF8`、`VAR_INT`、`BOOL`、`COMPOUND_TAG`、`list`、`optional`、`idMapper` 等） |
| 回包：包处理中直接再发一个包 | 处理函数返回 `CompletableFuture<Optional<CustomPacketPayload>>` 时自动作为回包发出（`IPayloadContext` 配合） |

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/11-Networking/01-Networking-Overview-1_21_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/11-Networking/02-Registering-Payloads-1_21_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/11-Networking/03-Stream-Codecs-1_21_1.md
> 知识库未覆盖：NeoForge 处理器「返回 `CompletableFuture<Optional<Payload>>` 作为回包」的精确写法（知识库仅在概念上提及 `context.reply()`，未给出该返回形式代码），需另行查证。

---

## 6. 配置

### 6.1 API 与包路径

| 项目 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 构建器 | `ForgeConfigSpec$Builder`（包 `net.minecraftforge.common.ForgeConfigSpec`） | `ModConfigSpec.Builder`（包 `net.neoforged.neoforge.common.ModConfigSpec`） |
| 注册 | `ModLoadingContext.get().registerConfig(Type.COMMON, SPEC)` | `ModContainer#registerConfig(ModConfig.Type.COMMON, SPEC)`（构造参数注入） |
| 类型枚举 | `ModConfig.Type`（`CLIENT` / `COMMON` / `SERVER`） | `ModConfig.Type`（`CLIENT` / `COMMON` / `SERVER` / `STARTUP`） |
| 配置事件 | `ModConfigEvent$Loading` / `ModConfigEvent$Reloading`（mod 总线） | `ModConfigEvent.Loading` / `ModConfigEvent.Reloading`（mod 总线） |

### 6.2 创建配置对照

```java
// Forge 1.20.1
public final ModConfigSpec.ConfigValue<String> welcomeMessage;

ExampleConfig(ForgeConfigSpec.Builder builder) {
    welcomeMessage = builder.define("welcome_message", "Hello from the config!");
}
static {
    Pair<ExampleConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
        .configure(ExampleConfig::new);
    CONFIG = pair.getLeft();
    CONFIG_SPEC = pair.getRight();
}
```

```java
// NeoForge 1.21.1
public final ModConfigSpec.ConfigValue<String> welcomeMessage;

ExampleConfig(ModConfigSpec.Builder builder) {
    welcomeMessage = builder.define("welcome_message", "Hello from the config!");
}
static {
    Pair<ExampleConfig, ModConfigSpec> pair = new ModConfigSpec.Builder()
        .configure(ExampleConfig::new);
    CONFIG = pair.getLeft();
    CONFIG_SPEC = pair.getRight();
}
```

### 6.3 注册对照

```java
// Forge 1.20.1：mod 构造函数内
public ExampleMod() {
    ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
}
```

```java
// NeoForge 1.21.1：ModContainer 注入
public ExampleMod(ModContainer container) {
    container.registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
}
```

### 6.4 CLIENT / COMMON / SERVER 语义（两侧一致）

| Type | 加载端 | 同步到客户端 | 客户端位置 | 服务端位置 | 默认后缀 |
|---|---|---|---|---|---|
| CLIENT | 仅客户端 | 否 | `.minecraft/config` | 无 | `-client` |
| COMMON | 双端 | 否 | `.minecraft/config` | `<服务器文件夹>/config` | `-common` |
| SERVER | 双端 | 是（每存档可覆盖） | `.minecraft/saves/<存档>/serverconfig` | `<服务器文件夹>/world/serverconfig` | `-server` |

NeoForge 额外有 `STARTUP`（注册时立即读取、不跨端同步、默认 `-startup` 后缀），不建议用于启用/禁用内容。

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/13-Miscellaneous/01-Miscellaneous-Config-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/13-Miscellaneous/01-Configuration-1_21_1.md

---

## 7. 资源与注册

### 7.1 pack.mcmeta 的 pack_format

| 版本 | pack_format |
|---|---|
| 1.20.1（数据包） | `15` |
| 1.21.1 | 知识库未覆盖数值（NeoForge 资源总览指出：**对 mod 来说 `pack.mcmeta` 是多余的，会被合成生成**） |

```json
// Forge 1.20.1（若 mod 自带 pack.mcmeta）
{
  "pack": {
    "pack_format": 15,
    "description": "Example data pack for Minecraft 1.20.1"
  }
}
```

NeoForge 1.21.1 侧：NeoForge 会为 mod 合成 `pack.mcmeta`，**不需要手工提供**（该文件对 mod 来说多余）。

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/01-Data-Pack-Core/02-Pack-Metadata-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/06-Resources/01-Resources-Overview-1_21_1.md
> 知识库未覆盖：1.21.1 的 pack_format 数值（34），需另行查证。

### 7.2 ResourceLocation 构造

```java
// Forge 1.20.1：直接 new
ResourceLocation id = new ResourceLocation("examplemod", "example_item");

// NeoForge 1.21.1：静态工厂（构造函数已被禁用/移除）
ResourceLocation id = ResourceLocation.fromNamespaceAndPath("examplemod", "example_item");
ResourceLocation parsed = ResourceLocation.parse("examplemod:example_item");
ResourceLocation vanilla = ResourceLocation.withDefaultNamespace("example_item"); // minecraft:example_item
```

### 7.3 语言文件

两侧都在 `assets/<modid>/lang/<locale>.json`（如 `en_us.json`），UTF-8 编码的翻译键 → 值 map。写法一致：

```json
{
  "item.examplemod.example_item": "Example Item Name",
  "block.examplemod.example_block": "Example Block Name",
  "config.examplemod.welcome_message": "Welcome message"
}
```

差异提示：
- 1.20.1 专用服务器只支持 `en_us` locale；服务端向客户端发消息必须用 `Component.translatable(...)`（保留翻译键），不要用客户端专属的 `I18n#get(...)` 预格式化。
- NeoForge 1.21.1 从 20.4.179 起支持用翻译键 `fml.menu.mods.info.description.<modid>` 覆写 mod 描述（可选）。
- `Component` 创建方式两侧一致：`Component.literal` / `Component.translatable` / `Component.empty`。

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/02-Concepts/06-Concepts-Internationalization-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/06-Resources/02-Resources-Client-I18n-1_21_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/13-Miscellaneous/05-Resource-Locations-1_21_1.md

---

## 8. Mixin

> 知识库说明：**NeoForge 与 Forge 两侧知识库均无独立 Mixin 块**（检索「Mixin」「refmap」仅命中 Fabric 侧 Loom 文档与 NeoForge Mod 文件中的 `[[mixins]]` 声明）。以下条目中，知识库可支撑的（`[[mixins]]` 声明、Fabric 侧 refmap 概念）给出出处；其余为**本项目实测踩坑记忆（neoforge-mixin-gotchas）+ 需另行查证**。

### 8.1 Mixin 配置声明差异

NeoForge 1.21.1 通过 `neoforge.mods.toml` 的 `[[mixins]]` 块声明 mixin 配置：

```toml
# NeoForge 1.21.1
[[mixins]]
config="examplemod.mixins.json"
```

Forge 1.20.1 侧：知识库的 `mods.toml` 表**没有** `[[mixins]]` 条目；Forge 1.20.1 的 mixin 依赖注入方式（mixins.json 资源 + ForgeGradle 处理）知识库未覆盖，需另行查证。

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/01-Getting-Started/02-Getting-Started-Mod-Files-1_21_1.md

### 8.2 Forge 1.20.1 侧：refmap 与 remap 机制（知识库未覆盖）

Forge 1.20.1 的 MDK 通常使用：

```gradle
// Forge 1.20.1（ForgeGradle 常见写法，需对照具体 ForgeGradle 版本验证）
mixin {
    add sourceSets.main, "<modid>.refmap.json"
    config "<modid>.mixins.json"
}
```

`mixin.env.remapRefMap=true`、`mixin.env.refMapRemappingFile=...` 等 JVM 参数是 Forge 1.20.1 下让 Mixin 在**运行时混淆名 → 官方名**正确解析的关键（refmap 由注解处理器在构建期生成）。

> 知识库未覆盖：Forge 1.20.1 侧 `mixin { ... }` Gradle 配置、`mixin.env.remapRefMap` / `refMapRemappingFile` 的具体写法与 refmap 生成细节，需另行查证。

### 8.3 compatibilityLevel：JAVA_17 vs JAVA_21

```jsonc
// Forge 1.20.1 mixins.json：JAVA_17
{
  "compatibilityLevel": "JAVA_17",
  "mixins": [ "..." ],
  "client": [ "..." ],
  "injectors": { "defaultRequire": 1 }
}
```

```jsonc
// NeoForge 1.21.1 mixins.json：JAVA_21
{
  "compatibilityLevel": "JAVA_21",
  "mixins": [ "..." ],
  "client": [ "..." ],
  "injectors": { "defaultRequire": 1 }
}
```

> 出处（Fabric 侧 mixin 配置概念）：SYSTEMATIC-BASE/Minecraft/Minecraft-Fabric-开发知识库/26_2/14-Loader/83-Overview-26_2.md
> 知识库未覆盖：NeoForge/Forge 侧 mixins.json 的 `compatibilityLevel` 具体值，需另行查证（本项目记忆实测为 NeoForge 1.21.1 = JAVA_21）。

### 8.4 @Pseudo 与 remap

- **`@Pseudo`**：目标类来自「可选 mod」时使用（配合字符串目标 `@Mixin(targets="...")`），目标类不存在时自动跳过，避免 PREPARE 阶段早于 FML `ModList` 初始化导致 NPE。**本项目记忆实测**：`IMixinConfigPlugin#shouldApplyMixin` 在 PREPARE 阶段执行、早于 `ModList` 初始化，调 `ModList.get().isLoaded(...)` 会崩；所以对可选 mod 目标的 mixin 不写插件，直接 `@Mixin(targets="...")` + `@Pseudo`。此结论两侧通用（Mixin 0.8.x 语义）。
- **`remap`**：Forge 1.20.1 依赖 refmap，mixin 注解默认 `remap=true`，靠 refmap 把源码中的官方名映射到运行时混淆名。NeoForge 1.21.1 官方映射直接用于运行时（MC 不再对 mod 混淆目标），多数 mixin 注入点已是运行时真实名，因此 `remap=false` 常见——**但此结论知识库未直接覆盖**，为从「NeoForge 官方映射即运行时映射」推导，需结合实际注入点验证。

> 出处（知识库概念）：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/01-Getting-Started/02-Getting-Started-Mod-Files-1_21_1.md（`[[mixins]]`）
> 知识库未覆盖：`@Pseudo`、`remap` 选项、NeoForge「为何多数 mixin 可 remap=false」的官方依据，需另行查证（本项目记忆 neoforge-mixin-gotchas 为实测佐证）。

---

## 9. 客户端 API 差异（GUI / 屏幕）

### 9.1 render 签名与背景

```java
// 两侧 render 方法签名一致
@Override
public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    // 背景通常最先渲染
    this.renderBackground(graphics);          // 1.21.1 可传 v 偏移重载（1.20.1 也有该重载）
    super.render(graphics, mouseX, mouseY, partialTick);
    this.renderTooltip(graphics, mouseX, mouseY); // 容器屏幕的悬停槽位提示框
}
```

### 9.2 blit 与 drawString 签名

两侧 `GuiGraphics#blit` / `#drawString` / `#drawCenteredString` 签名一致（均以 `GuiGraphics` 为第一参数），但 1.21.1 额外引入 sprite 体系（`#blitSprite`）：

```java
// Forge 1.20.1 与 NeoForge 1.21.1 均可用的 blit（256x256 PNG 假设）
graphics.blit(BACKGROUND_LOCATION, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

// NeoForge 1.21.1 新增：sprite 直接 blitSprite（纹理在 textures/gui/sprites/，不带扩展名）
private static final ResourceLocation SPRITE =
    ResourceLocation.fromNamespaceAndPath("examplemod", "container/example_container/example_sprite");
graphics.blitSprite(SPRITE, x, y, width, height);
```

```java
// 两侧一致的 drawString / drawCenteredString
graphics.drawString(this.font, this.label, this.labelX, this.labelY, 0x404040);
graphics.drawCenteredString(this.font, title, this.width / 2, 8, 0xFFFFFF);
```

> 知识库差异点：Forge 1.20.1 与 NeoForge 1.21.1 的屏幕笔记给出的 `blit`/`drawString` 签名完全相同（都强调 `GuiGraphics` 第一参数、六个/九个整数 blit 重载），**未发现签名级破坏性变更**。主要变化在 1.21.1 新增 `blitSprite` 与 sprite 缩放体系。

### 9.3 屏幕生命周期：init / tick / onClose / removed

两侧一致：

```java
// 两侧一致：init 中加小部件
@Override
protected void init() {
    super.init();
    this.addRenderableWidget(new EditBox(/* ... */));
}

// 两侧一致：tick
@Override
public void tick() {
    super.tick();
}

// 两侧一致：关闭
@Override
public void onClose() {
    super.onClose();
}

@Override
public void removed() {
    super.removed();
}
```

### 9.4 按钮类

1.20.1 的 `new Button(x, y, w, h, component, onPress)` 在两侧均已被 `Button.builder(component, onPress).bounds(x, y, w, h).build()` 取代：

```java
// 两侧一致（1.20.1 与 1.21.1 都可用）
this.addRenderableWidget(
    Button.builder(Component.translatable("gui.examplemod.done"),
        btn -> this.onClose())
        .bounds(this.width / 2 - 80, this.height / 2 - 10, 160, 20)
        .build()
);
```

### 9.5 Component / ChatFormatting

两侧一致：`Component.literal(...)` / `Component.translatable(...)` / `Component.empty()`；`ChatFormatting.RED` 用于样式。1.21.1 仍支持 `Component.translatable(key, args...)`（可变参数，只用 `%s`）。两侧都不应在服务端用 `I18n#get` 预格式化（客户端专属类）。

### 9.6 容器屏幕注册差异

```java
// Forge 1.20.1：FMLClientSetupEvent 中 enqueueWork 包裹 MenuScreens.register
@SubscribeEvent
public static void onClientSetup(FMLClientSetupEvent event) {
    event.enqueueWork(() -> MenuScreens.register(MY_MENU.get(), MyContainerScreen::new));
}
```

```java
// NeoForge 1.21.1：mod 总线 RegisterMenuScreensEvent
@SubscribeEvent // on the mod event bus only on the physical client
public static void registerScreens(RegisterMenuScreensEvent event) {
    event.register(MY_MENU.get(), MyContainerScreen::new);
}
```

> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_20_1-内容开发知识库/10-Forge-Mod-Dev/08-GUIs/02-GUIs-Screens-1_20_1.md
> 出处：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/09-GUIs/02-GUIs-Screens-1_21_1.md
> 知识库未覆盖：`Button.builder` 与 `blitSprite` 具体参数重载的逐条签名，需另行查证（知识库给出核心用法但非全量重载表）。

---

## 10. Cloth Config

| 项目 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 坐标 | `me.shedaniel.clothconfig2` | `me.shedaniel.clothconfig2` |
| 版本线 | 11.x（Cloth Config for Forge 1.20.1） | 15.x（本项目 `cloth_config_version=15.0.140+neoforge`） |
| 接入方式 | `ClothConfigScreenFactory` 扩展点注册到 `ModLoadingContext.get().registerExtensionPoint(...)` | `IConfigScreenFactory` 扩展点注册到 `ModContainer#registerExtensionPoint(...)` |

**知识库未覆盖**：Cloth Config 两个版本的 API 差异（构建器 `ConfigBuilder` 用法、`screen.self()` / `screen.getScreen()` 等）在知识库中无独立条目。NeoForge 侧知识库仅提到内置 `ConfigurationScreen`（`IConfigScreenFactory` + `container.registerExtensionPoint`）。本项目现有用法可参考 `neoforge` 薄壳中的注册代码，但**两侧 Cloth API 差异需另行查证**。

> 出处（NeoForge 内置配置界面入口）：SYSTEMATIC-BASE/Minecraft/Minecraft-1_21_1-NeoForge-开发知识库/13-Miscellaneous/01-Configuration-1_21_1.md
> 知识库未覆盖：Cloth Config 11.x / 15.x 的具体 API 差异与坐标，需另行查证。

---

## 11. 其它 1.20.1 → 1.21.1 破坏性变更（可能影响本项目的子集）

只列出可能影响「无方块无物品、只有 GUI/网络/事件/mixin」附属 mod 的条目：

| 变更 | 影响说明 | 应对 |
|---|---|---|
| `ResourceLocation` 构造器移除 | 1.21.1 用 `fromNamespaceAndPath` / `parse` | 全局替换 `new ResourceLocation(...)` |
| `RegisterMenuScreensEvent` 取代 `FMLClientSetupEvent`+`MenuScreens.register` | 容器屏幕注册时机与总线变化 | 见 §9.6 |
| `@EventBusSubscriber` 命名与枚举 | `@Mod.EventBusSubscriber` → `@EventBusSubscriber`；`Bus.FORGE` → `Bus.GAME` | 见 §4.3 |
| `MinecraftForge.EVENT_BUS` → `NeoForge.EVENT_BUS` | 主总线常量类名变化 | 见 §4.1 |
| 客户端 tick 事件包迁移 | `TickEvent$ClientTickEvent` → `net.neoforged.neoforge.event.tick.*` | 见 §4.4 |
| 网络从 SimpleChannel → Payload | 手写编解码 → `StreamCodec`；`sendToServer` 等 API 变化 | 见 §5 |
| `Tooltip` / `renderTooltip` 变化 | 1.21.1 提示框用 `GuiGraphics#renderTooltip(Component, x, y)`，并引入 `Tooltip` 组件体系 | 1.20.1 与 1.21.1 均以 `GuiGraphics#renderTooltip` 为主；`Tooltip` 体系具体差异知识库未展开，需另行查证 |
| mixin 注册路径 | 1.21.1 用 `[[mixins]]` 声明（本项目已修复过注册路径） | 见 §8.1 |

### 本项目不涉及（无需移植）

- 方块 / 方块状态 / 方块实体 / BER
- 物品 / 数据组件（Data Components）/ 物品模型 / 物品属性
- 世界生成 / 生物群系 / 结构 / 噪声
- 能力系统（Capabilities）/ 数据附加（Attachments）
- 合成 / 战利品表 / 标签 / 进度 / 条件
- 粒子 / 音效 / 按键绑定（本项目暂无按键绑定）
- Access Transformer / 可扩展枚举 / 数据生成

---

## 附：知识库未覆盖清单（需另行查证）

1. Forge 1.20.1 侧 `gradle.properties` 完整键表。
2. Forge/NeoForge 工程侧 Parchment 具体配置写法。
3. 两侧事件类的完整包路径逐条对照（本项目事件子集已在 §4.4 给出）。
4. Forge 1.20.1 侧 Mixin 的 `mixin { }` Gradle 配置、`mixin.env.remapRefMap` / `refMapRemappingFile`、refmap 生成细节。
5. NeoForge 1.21.1 侧 mixin `compatibilityLevel` / `@Pseudo` / `remap=false` 的官方依据（本项目记忆为实测佐证）。
6. 1.21.1 的 pack_format 数值（= 34）。
7. NeoForge「处理器返回 `CompletableFuture<Optional<Payload>>` 作回包」精确写法（`context.reply()` 语义）。
8. Cloth Config 11.x / 15.x API 差异。
9. 1.21.1 `Tooltip` 组件体系与 1.20.1 的差异细节。
10. `Button.builder` / `blitSprite` 全量重载签名。

# API 插件开发指南（简体中文）

本指南面向**插件开发者**。API 以**插件 jar** 形式独立编写、打包、签名，上传到网关后**热插拔**（安装 / 禁用 / 升级 / 卸载），重启后自动重挂载。仓库内的 `echo/` 是一个可直接运行的完整示例，随文档对照阅读效果最佳。

**写一个插件现在只需一个 `build.gradle.kts`（约 10 行）+ 你的 Kotlin 源码**：签名、密钥、描述符、文档所需资源都由 Gradle 插件 `top.fatweb.api-plugin` 自动完成。

- SDK 构件：`top.fatweb:api-management-plugin-sdk`（由 Gradle 插件自动加入）
- Gradle 插件：`top.fatweb.api-plugin`（本指南推荐）
- SDK 包：`top.fatweb.apimanagement.sdk.annotation` / `top.fatweb.apimanagement.sdk.plugin`
- 网关 / 控制台：[api-management](https://github.com/FatttSnake/api-management) · [api-management-console](https://github.com/FatttSnake/api-management-console)

---

## 0. 准备工作：让 SDK 与 Gradle 插件可用

Gradle 插件与 SDK 从**网关仓库**一起构建发布。首次使用前先在 [api-management](https://github.com/FatttSnake/api-management) 仓库执行一次（把构件装进本地 maven 仓库）：

```shell
./gradlew :plugin-sdk:publishToMavenLocal :plugin-gradle-plugin:publishToMavenLocal
```

之后你新建的插件工程会在 `settings.gradle.kts` 的 `pluginManagement` 里加 `mavenLocal()` 来解析它们。已发布到私有 Nexus 时同理，把对应仓库地址加进去即可（也可用 `-PapiPlugin.sdkVersion=<版本>` 覆盖 SDK 版本）。

---

## 1. 整体模型

```
插件工程（独立 Gradle build）                   网关（api-management-backend）
┌──────────────────────────────────┐        ┌──────────────────────────────────────────┐
│ Kotlin 源码 + 手动 OpenAPI 片段   │ 上传   │ PluginSigner.verify（签名完整性）          │
│ (build.gradle.kts 声明 apiPlugin)│──────▶│ 信任库校验（公钥 keyId 在库且启用）         │
│                                  │        │ 子优先 ClassLoader 加载 jar               │
│ Gradle 插件在 build 期自动完成：   │        │ 子容器 GenericApplicationContext         │
│  ├ 生成 api-plugin.json           │        │ RequestMappingInfo + ApiVersionCondition│
│  ├ genPluginKeys 生成 keys/       │        │ → registerMapping                       │
│  ├ 嵌入 plugin.pub.pem            │        │ DB upsert（插件/接口/权限树）             │
│  └ signPlugin（Ed25519 签名）     │        │ 启动时从 blob 自动重挂载                  │
└──────────────────────────────────┘        └──────────────────────────────────────────┘
```

### 安全边界（务必理解）

插件代码运行在**网关 JVM 内**，任何数据隔离都挡不住恶意代码。**真正防恶意的边界是：强制 Ed25519 签名 + 签名公钥必须在管理员维护的信任库中**。数据隔离（每插件独立数据源 + 窄 `PluginContext` API）是防误触的纵深防御。因此：

- **私钥是身份的根**，持私钥者可冒充该开发者发布插件。它只存在你的 `keys/private.pem`，**永不提交、绝不要泄露**。
- 公钥 `keys/public.pem` 要主动提交给网关管理员加入信任库，否则插件无法安装。
- **删除 `keys/private.pem` 再构建 = 换了身份**：旧插件下次重启重挂载会因签名者不再可信而不再挂载。

---

## 2. 一键搭建：最小插件工程

新建目录（如 `geo/`），放三个文件 + 你的源码即可：

```
geo/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ keys/            # 首次 build 自动生成（private.pem 勿提交）
└─ src/main/
   ├─ kotlin/com/example/geo/GeoController.kt   # 你的 @ApiController 控制器
   └─ resources/META-INF/plugin-openapi.json    # OpenAPI 文档片段（可选）
```

### 2.1 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()   // 解析 top.fatweb.api-plugin 与 SDK SNAPSHOT
    }
}

rootProject.name = "geo-plugin"
```

### 2.2 `build.gradle.kts`（全部配置就这些）

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"           // 你用的 Kotlin 版本
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}

version = "1.0.0"                            // 默认也作为 versionName

apiPlugin {
    pluginId = "geo"                          // 唯一 ID，^[a-z][a-z0-9-]*$
    pluginName = "Geo 插件"                    // 显示名
    versionCode = 1                           // 必填；发版递增（网关据此判定升级）
    description = "把地理编码能力开放成 API"      // 可选
    author = "你的名字"                         // 可选
    mainClass = "com.example.geo.GeoLifecycle" // 有 PluginLifecycle 时可选
}
```

`apiPlugin` 各字段的默认值与对应描述符字段：

| DSL 字段 | 必填 | 默认 | 写入描述符 |
|---|---|---|---|
| `pluginId` | ✅ | 工程名 | `pluginId`（须与 `@ApiController.plugin` 一致） |
| `pluginName` | | 同 `pluginId` | `name` |
| `versionName` | | 工程 `version`（空串同样回退） | `versionName` |
| `versionCode` | ✅ | —（必填，无默认） | `versionCode`（升级须单调递增） |
| `description` / `author` | | 空 | 同名 |
| `mainClass` | | 无 | `mainClass`（缺省时网关自动发现） |
| `sdkVersion` | | 插件自带 SDK 版本 | （不写描述符，只控制依赖版本） |
| `archiveName` | | `<pluginId>-<versionName>.jar` | （jar 文件名） |

> 该插件会自动：给工程加 `repositories`（mavenCentral + mavenLocal）与 SDK 的 `implementation` 依赖；**每次构建**从上面的 DSL 生成 `META-INF/api-plugin.json` 与 `META-INF/plugin.pub.pem`（build 目录内）；生成 `keys/`；按 `<pluginId>-<versionName>.jar` 命名并签名。所以仓库里**不再需要**手写 `api-plugin.json` / `plugin.pub.pem`。

### 2.3 `.gitignore`

```gitignore
keys/            # 私钥/公钥（私钥绝不可提交）
build/
.gradle/
```

### 2.4 三个命令

```shell
./gradlew build           # 一键：生成 keys/ + 描述符 + pub.pem + 签名
./gradlew genPluginKeys   # 单独重新生成/确认密钥（已有则跳过）
./gradlew verifyPlugin    # 自检 jar 签名（签名无效会失败退出）
```

产物：`build/libs/<pluginId>-<versionName>.jar`，**已签名**，可直接上传。

插件在 `api plugin` 任务组下共注册五个任务：`generatePluginDescriptor` 与 `preparePluginPubKey`（都挂在 `jar` 上，构建时会自动跑到）、`genPluginKeys`、`signPlugin`（挂在 `build` 上）与 `verifyPlugin`。上面这三条是需要手动执行的，其余由构建代劳。

### 2.5 手动方式（不用 Gradle 插件时）

只想加个签名任务而不引入 DSL 时，`build.gradle.kts` 最简可写成：

```kotlin
plugins { kotlin("jvm") version "2.3.21" }
repositories { mavenCentral(); mavenLocal() }
dependencies {
    implementation("top.fatweb:api-management-plugin-sdk:1.0.0-SNAPSHOT")
}
kotlin { jvmToolchain(25) }

val signPlugin by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar)
    mainClass.set("top.fatweb.apimanagement.sdk.plugin.PluginSigner")
    classpath = configurations.runtimeClasspath.get()
    args("sign",
         tasks.jar.get().archiveFile.get().asFile.absolutePath,
         layout.projectDirectory.file("keys/private.pem").asFile.absolutePath)
}
tasks.build { dependsOn(signPlugin) }
```

手工方式下你要自己维护 `keys/private.pem`、`src/main/resources/META-INF/api-plugin.json` 与 `plugin.pub.pem`（密钥用 `PluginSigner genkey keys/` 生成后，把 `public.pem` 复制为 `plugin.pub.pem`）。手写 `api-plugin.json` 时 `versionName` 必填（`pluginId`、`name` 同样必填）。

---

## 3. 描述符

默认由 Gradle 插件在 `build` 期从 `apiPlugin {}` 生成（见 2.2 的映射表），`echo/` 生成结果形如：

```json
{
  "pluginId": "echo",
  "name": "Echo 插件",
  "versionName": "1.0.0",
  "versionCode": 1,
  "description": "Echo 插件，演示 API 插件热插拔全流程",
  "author": "FatttSnake",
  "mainClass": "com.example.echo.EchoLifecycle"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `pluginId` | ✅ | 唯一 ID，`^[a-z][a-z0-9-]*$`，与所有 `@ApiController.plugin` 一致 |
| `name` | ✅ | 插件显示名（菜单/权限树/文档用） |
| `versionName` | ✅ | 人类可读版本，如 `1.2.0`；手写描述符**必填**（用 Gradle 插件则缺省取工程 `version`） |
| `versionCode` | | **单调递增**整数；升级必须大于当前已装版本，否则拒绝 |
| `description` / `author` | | 描述 / 作者 |
| `mainClass` | | `PluginLifecycle` 实现类全限定名（需 `@Component`，且必须落在被扫描的包内——见 §6.1）；缺省则自动发现，而写了一个匹配不到任何 bean 的值时不会报错，会静默改用第一个生命周期 bean |

> `versionCode` 参考 Android versionCode 语义：**同一 pluginId 只允许装一个实例，升级只能往更高 versionCode 升**。

---

## 4. 签名与信任库

### 4.1 生成密钥（自动）

`./gradlew build`（或 `genPluginKeys`）首次运行会生成 Ed25519 密钥对到 `keys/`：

- `keys/private.pem`（PKCS8）—— 只参与本机签名，**绝不提交/泄露**
- `keys/public.pem`（SPKI）—— 提交给网关管理员加信任库

构建时公钥会作为 `META-INF/plugin.pub.pem` 嵌入 jar；签名 `META-INF/plugin.sig` 由 `signPlugin` 写入。

> **换身份**：删除 `keys/private.pem` 后重新 `build` 即重新生成一对密钥（jar 内公钥也随之更新、重新签名）。

### 4.2 本地自检

```shell
./gradlew verifyPlugin
# 期望输出: Signature valid: echo-1.0.0.jar
```

### 4.3 管理员加入信任库

```
POST /system/api/plugin/key
{ "publicKey": "<keys/public.pem 的内容>", "alias": "你的名字" }
```

网关自动从公钥派生 `keyId`（SPKI 的 SHA-256）。此后你的插件即可安装。信任库管理接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/system/api/plugin/key` | 列表 |
| POST | `/system/api/plugin/key` | 添加公钥 |
| PATCH | `/system/api/plugin/key` | 启用/停用 |
| DELETE | `/system/api/plugin/key/{keyId}` | 删除 |

> 撤销：管理员删除/停用你的公钥后，已装插件在**下次重启重挂载**时会因签名者不再可信而**不挂载**（数据库行保留，`loadError` 记录原因）。

---

## 5. OpenAPI 文档配置

可选的手写资源 `src/main/resources/META-INF/plugin-openapi.json`（OpenAPI 3.1 片段），描述参数与数据结构，供网关给最终用户展示。`echo/` 的片段：

```json
{
  "openapi": "3.1.2",
  "paths": {
    "/api/echo/v1/ping": {
      "get": {
        "summary": "回声",
        "operationId": "ping",
        "responses": {
          "200": {
            "description": "OK",
            "content": {
              "application/json": {
                "schema": { "$ref": "#/components/schemas/PingResponse" }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "PingResponse": {
        "type": "object",
        "properties": {
          "message": { "type": "string", "examples": ["pong"] },
          "userId": { "type": ["integer", "null"], "format": "int64" }
        }
      }
    }
  }
}
```

- `paths` 的 key 用完整公开路径 `/api/{plugin}/v{version}/...`（与调用 URL 一致）；
- 安装时原样存入 `t_s_api_plugin.openapi`；
- `GET /user/api/docs`（列表）/ `GET /user/api/docs/{pluginId}`（详情）返回给前端；
- **片段是 OpenAPI 3.1 文档**，其 Schema 遵循 JSON Schema 2020-12：示例用 `examples: [值]`（数组）而非 `example`，可空用 `type` 数组表达（`"type": ["integer", "null"]`），二进制载荷用 `contentMediaType`（如 `application/octet-stream`）而非 `format: binary`；
- 一个片段覆盖该插件的全部接口，可同时描述多个 API 版本（如 `/api/echo/v1/...` 与 `/api/echo/v2/...`）；
- **缺省不影响安装**：文档端点仍返回接口列表，只是缺少参数/结构详情。

---

## 6. 编写控制器与业务代码

SDK 的 `implementation` 依赖已由 Gradle 插件自动加入，并透传 spring-web / spring-context / swagger 注解，直接写控制器即可。

### 6.1 `@ApiController`

```kotlin
@ApiController(
    plugin = "geo",     // 必须与 apiPlugin.pluginId 一致（即 api-plugin.json 的 pluginId）
    version = 1         // API 版本，同一插件可含多个版本
)
class GeoController(
    private val pluginContext: PluginContext,
    private val geoService: GeoService
) {
    // 接口级 name/description 取自 @Operation（summary/description）
    @Operation(summary = "地理编码", description = "将地址解析为坐标", operationId = "geocode")
    @GetMapping("/geocode")
    fun geocode(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("result" to geoService.lookup(pluginContext.currentUserId())))
}
```

要点：

- 控制器用**构造函数注入** `PluginContext` 和自己的 `@Service`，子容器自动装配。但它的扫描范围比 Spring Boot 应用窄：扫描基包是 jar 自身的顶层包中、能通往你的 `@ApiController` 所在包的那些，且只认 `@Component`（因此 `@Service` / `@Configuration` 有效）。**落在这些根包之外的组件永远不会被发现**——不报错，只是不在那里——所以协作者要和控制器放在同一棵包树下。若一个根包都推导不出来，安装会直接失败并报 `Cannot derive scan packages`。
- 这个子容器里只有你的 bean，加上网关注册的 `pluginContext` 与 `pluginDataSource.<name>`，除此之外什么都没有：它是一个普通的 `GenericApplicationContext`，**没有任何自动配置**。特别地，没有任何东西会开启调度，所以 `@Scheduled` 方法除非你自己在配置里打开，否则永远不会被处理。
- 插件级元数据（`name`/`description`/`author`/版本号）在 `apiPlugin { }` 里**声明一次**（→ `api-plugin.json`）；`@ApiController` 只声明每个控制器属于哪个插件（`plugin`，必须等于 descriptor 的 `pluginId`）与哪个 API 版本（`version`）。
- 每个接口的展示 `name` = `@Operation.summary`（缺省回退方法名）、`description` = `@Operation.description`（缺省为空），会体现在权限树与接口表里。
- 每个端点方法标注 HTTP 映射注解；只有 `@GetMapping` / `@PostMapping` / `@PutMapping` / `@PatchMapping` / `@DeleteMapping` 会被解析。方法级 `@RequestMapping` **不在其列**——该端点被跳过，安装其余步骤照常进行，调用方拿到 404。`operationId`（缺省=方法名）决定 API 作用域码：`api:{plugin}:v{version}:{operationId}`。
- `@ApiController(path = [...])` 在版本段之后追加一段基础路径，且**只取第一个元素**：`/api/{plugin}/v{version}/<path[0]>/<方法路径>`。
- 一个插件内可有**多个 `@ApiController(version=n)`** 共存实现向前兼容。`echo/` 即如此：`EchoController`（v1，含 `ping`/`version`）与 `EchoControllerV2`（v2，含 `ping`/`whoami`）在**同一插件**里共存——请求 `v2` 及以上时 `ping` 由 v2 覆盖（滚动兼容），`v1` 专属的 `version` 仍由 v1 兜底。

### 6.2 响应规则 `ApiResponse<T>`

SDK 提供的用户侧响应信封，与网关内部 `ResponseResult` 解耦：

| 字段 | 说明 |
|---|---|
| `code` | 业务码；**`0` = 成功** |
| `success` | 是否成功 |
| `msg` | 信息 |
| `data` | 数据 |

建议错误码分段：`1000-1999` 客户端/参数、`2000-2999` 业务失败、`5000+` 服务端错误。也可返回裸类型（如 `ByteArray` + `produces = [IMAGE_PNG]`）实现"透传"。

> 网关侧的鉴权/限流/计费错误（Key 无效、配额超限等）会自动以 `ApiResponse` 形状返回，并带上网关自身的错误码（`code != 0`）。与插件相关的那些位于 4xxxx 段：`40063` 安装失败、`40064` 签名者不受信、`40065` 签名无效、`40066` 版本冲突、`40067` 数据源无效——所以**别把自己的业务码放进 4xxxx**：两套码空间在同一个信封里数值上会重叠，除了"是谁发出的"之外没有任何东西能区分它们。

### 6.3 数据隔离与交互 `PluginContext`

插件**不接触网关数据源/MyBatis**；经 `PluginContext` 与网关交互：

| 方法 | 说明 |
|---|---|
| `datasources` | 插件自己的独立数据源，按声明的名字取值（`Map<String, DataSource>`）。名字不在 map 里，就是网关还没有东西可连（见下） |
| `storage` | 插件自己的文件区 `PluginStorage`（见 §6.7） |
| `currentUserId()` / `currentAccessKeyId()` | 当前调用者 |
| `getBalance(userId)` | 查用户余额 |
| `getInterfaceInfo(code)` | 查接口运行期配置 |
| `getSetting(key)` | 读插件设置：管理员配置项（含 schema 默认值回落）或插件运行态键（见 §6.6） |
| `saveSetting(key, value)` | 写插件运行态键；**写 schema 声明过的配置项会抛 `IllegalArgumentException`** |

**关于 `datasources`**：插件只有在自己的 `plugin-config.json` 的 `datasources` 数组里声明了数据源，才会拿到它，并按声明的 `name` 取值。未声明即**永不注入**（即使库里存在该插件的数据源配置，管理端也会拒绝为其保存）。数据源只注册进该插件自己的子容器，网关自己的数据源永不暴露给插件。

声明里带 `dbType`，两种方言的供给方式不同：

- **`SQLITE` ——零配置**：声明即供给，网关在挂载时按 `app.storage.plugin-datasource-dir/{pluginId}/{name}.db` 建库建池，不落配置行、管理员不需要做任何事。两级路径都来自声明，没有任何提交值能进到路径里。
- **`MYSQL` ——就是几条普通配置项**：声明里每个槽位点名一个配置 key（`host` / `port` / `database` / `username` / `password` / `params`）。JDBC URL 由网关拼装，`params` 以**连接属性**下发给驱动，插件作者与管理员都不写连接串。**host 有值即视为已配置**，在那之前该数据源不会出现在 `context.datasources` 里，插件应返回明确错误提示。

一个插件可以声明多个数据源，两种方言也可以混用。数据源在**挂载时构建**，而保存构成数据源的配置项时**网关会自动重挂载插件**（见 §8）——所以插件要能接受保存之后 `onStop` / `onStart` 再跑一遍，只存在内存里的状态会随之丢失。

### 6.4 生命周期 `PluginLifecycle`

```kotlin
@Component
class GeoLifecycle : PluginLifecycle {
    override fun onInstall(context: PluginContext) {}    // 每次挂载后（可做 DDL/种子数据）
    override fun onStart(context: PluginContext) {}      // 每次挂载后，紧跟 onInstall
    override fun onStop(context: PluginContext) {}       // 每次卸载前
    override fun onUninstall(context: PluginContext) {}  // 插件被摘掉之后
}
```

在 `apiPlugin { mainClass = "…" }` 里指定，或让网关自动发现。`echo/` 的 `EchoLifecycle` 在每个钩子里写入一条插件级设置（`installedAt` / `startedAt` / `stoppedAt` / `uninstalledAt`），演示 `PluginContext.saveSetting` 的用法。

**自动发现失败时是安静的，所以要主动核对。** `PluginLifecycle` 只有在它确实是一个被子容器扫描到的 Spring bean 时才会运行（见 §6.1）——放在控制器包树之外的实现不会被实例化，所有钩子静默跳过，而**安装照样报成功**。`mainClass` 只是一种偏好而非硬性要求：写了一个匹配不到任何 bean 的值不会让任何东西失败，网关会回退到它找到的第一个 `PluginLifecycle` bean（一旦你有多个，这就会开始有影响）；而一个生命周期 bean 都没有时，插件就是在完全没有生命周期的情况下挂载的。

**挂载不是一次性事件，这些钩子因此也不是。** 网关会在插件安装时、网关启动重挂载时、`reload` 时，以及任何一次改到该插件数据源组成值的保存之后挂载它——而**每次挂载都会先跑 `onInstall` 再跑 `onStart`**。重挂载会先把插件拆掉，所以插件生命周期中途会出现自己的 `onStop`，不要指望只放在内存里的状态还在。`onUninstall` 值得多读两遍：**升级是先卸载旧版本再装新版本**，所以升级时它也会跑一次——绝不能当成"插件再也回不来了"而在那里删数据。网关只有在卸载显式要求时才清理插件的设置、SQLite 数据库与文件（`purgeData=true`），升级永远不会。

### 6.5 自带依赖

插件可**打包自己的第三方库**（子优先 ClassLoader 隔离）。仅下列前缀委托给网关父加载器（保证注解/序列化类身份一致）：`java.* javax.* jakarta.* sun.* jdk.* org.springframework.* org.slf4j.* tools.jackson.* com.fasterxml.* io.swagger.* org.springdoc.* com.baomidou.* kotlin.* kotlinx.* top.fatweb.apimanagement.*`。其余类名从插件 jar 优先加载。若需打包依赖，用 shadow/fat jar 将依赖类合并进插件 jar。

> SDK 只声明 `spring-web` / `spring-context` / `swagger-annotations`，**不含日志依赖**：要写日志就自己引 `slf4j-api` 来编译，但 `org.slf4j.` 是向上委派的，运行期你的日志调用落在网关的绑定上，自己打进去的那份根本不会被加载。其余自己持有的资源记得在 `onStop` 里释放。

### 6.6 插件配置声明 `META-INF/plugin-config.json`

插件在自己 jar 里声明"哪些设置由管理员配置、怎么渲染、默认值是多少"，网关在**挂载时**读取并快照到插件行上，管理端接口返回的是这份声明 + 折叠进去的当前值，由控制台据此渲染配置界面。插件侧仍然只读 `context.getSetting(key)`，**不需要为了配置改任何代码**。

```json
{
  "datasources": [
    {
      "name": "main",
      "dbType": "mysql",
      "required": true,
      "host": "db.host",
      "port": "db.port",
      "database": "db.name",
      "username": "db.user",
      "password": "db.password",
      "params": "db.params"
    },
    { "name": "cache", "dbType": "sqlite" }
  ],
  "groups": [
    {
      "key": "db",
      "title": "数据库",
      "fields": [
        { "key": "db.host", "type": "string", "title": "主机" },
        { "key": "db.port", "type": "number", "title": "端口",
          "default": 3306, "minimum": 1, "maximum": 65535, "integer": true },
        { "key": "db.name", "type": "string", "title": "库名" },
        { "key": "db.user", "type": "string", "title": "用户名" },
        { "key": "db.password", "type": "secret", "title": "密码" },
        { "key": "db.params", "type": "string", "title": "驱动参数" }
      ]
    },
    {
      "key": "share",
      "title": "分享设置",
      "fields": [
        { "key": "ttlHours", "type": "number", "title": "有效期（小时）",
          "default": 1, "minimum": 1, "maximum": 168, "integer": true },
        { "key": "allowSnapshot", "type": "boolean", "title": "启用快照", "default": true },
        { "key": "mode", "type": "enum", "title": "模式", "default": "fast",
          "options": [ { "value": "fast", "label": "快速" }, { "value": "slow" } ] },
        { "key": "notice", "type": "text", "title": "提示语", "maxLength": 200 },
        { "key": "uploadToken", "type": "secret", "title": "上传令牌" }
      ]
    }
  ]
}
```

数据源条目的字段：

| 字段 | 说明 |
|---|---|
| `name` | 插件取用该数据源时的名字：`^[a-z][a-z0-9-]*$`，插件内唯一。它同时是路径片段与 bean 名后缀，所以被收窄成一个纯小写单词 |
| `dbType` | `mysql` / `sqlite`，必填，决定网关怎么供给 |
| `required` | 插件是否离了它就跑不起来。**仅用于上报**——它不会把任何配置项变成必填，插件在它缺席时照样挂载 |
| `host` `port` `database` `username` `password` `params` | 仅 `mysql`：每个槽位对应的配置 key。`password` 必须指向 `secret` 字段，其余槽位指向 `string` / `text` 字段，`port` 指向 `number` 字段 |

`mysql` 数据源至少要点名 `host` 与 `database`；`sqlite` 数据源不点名任何槽位。一个配置 key 不能被两个槽位或两个数据源占用，被引用的 key 必须存在，`password` 槽位也不能指向 `required` 字段——必填字段会拒绝空提交，密码就再也清不掉了。

| 字段 | 说明 |
|---|---|
| `key` | 配置键，`^[A-Za-z][A-Za-z0-9._-]*$`、长度 ≤ 100、全插件唯一；即 `t_b_api_plugin_setting` 的 key |
| `type` | `string` / `text` / `number` / `boolean` / `enum` / `secret`；**所有值在传输与存储层都是字符串**（boolean 用 `"true"/"false"`） |
| `title` / `description` / `placeholder` | 界面文案 |
| `default` | 默认值；**不落库为值行**，未配置时由 `getSetting` 回落返回。secret 不允许声明默认值 |
| `required` | 是否必填：提交了该键就必须非空（空值表示清除，不算满足），未提交的才由存储值或默认值满足。**只对本次提交点名的分组校验**——没提交的分组等于没做决定 |
| `minimum` / `maximum` / `integer` | 仅 `number` 可用 |
| `minLength` / `maxLength` / `pattern` | 仅 `string` / `text` / `secret` 可用 |
| `options` | 仅 `enum` 可用，非空且值唯一 |

要点：

- **`datasources` 数组是独立数据源的唯一声明**：每个条目为一项连接事实点名一个配置 key，于是这些事实就是普通配置项，表单、约束、密钥加密全部复用。**没有这个数组 = 不使用独立库**，网关既不构建也不注入，管理端也拒绝为其保存数据源。详见 §6.3。
- **默认值只在 schema 里声明一次**：DB 只存管理员显式覆盖过的项，`getSetting` 查不到行时返回 schema 默认值。所以插件升级改默认值能生效，插件代码里也不必再写一份兜底。
- **所有权**：schema 声明过的 key 归管理员，插件 `saveSetting` 写它会抛异常；未声明的 key 仍是插件的运行态空间（`installedAt` 这类照旧可写）。
- **secret 项**：网关加密存储（AES-GCM，密钥派生自 `app.security.tokenSecret`），管理端接口**永不回传明文**——secret 的 `value` 恒为 `null`，唯一能表示"存过没有"的是 `hasValue`。因此**保持原值的做法是提交时不带这个 key**，带任何别的值就是覆盖，留空就是清除。**没有掩码可以回传**：控制台若自己造一个 `******` 回传，每次保存都会把已存的密钥覆盖掉。插件读到的始终是明文。`unreadable: true` 表示该 secret 的密文在 `tokenSecret` 轮换后已解不开——只能重新录入，在此之前插件自己 `getSetting` 读它会抛 `IllegalStateException`。注意轮换 `tokenSecret` 会让所有 secret 与所有外链同时失效。另外 `hasValue` **每个字段都有**（不止 secret）：它表示"管理员自己设过值"，反之表示读到的是声明的默认值。
- **校验是网关侧权威的**：未声明的 key、类型不符、超范围、enum 越界都会被拒绝保存。同一分组或同一 key 重复提交会被判为结构性错误；又因为数据源背后的值就是网关构建连接的依据，保存前还会先过一遍连接规则（host 形状、端口范围、库名、驱动参数黑名单）——根本连不上的组合会被拒，而不是先存下来。
- **schema 非法即拒绝安装**（刻意的 fail-fast）：文件不存在 = 无配置项，文件存在但解析失败 = 安装失败并报错；schema 内分组/字段 key 重复、或 `mysql` 数据源没有同时给出 `host` 与 `database` 槽位，同样安装失败。
- **生效时机**：普通配置项**改完即生效**（每次 `getSetting` 都读库），不需要重新挂载。构成数据源的配置项不同——连接是网关构建的，改它会重挂载插件（见 §8）。这个判断依据的是**值确实变了**而不是"有人提交了请求"，所以保存无关配置项不会重启你的插件。
- **编辑器 / 构建期反馈**：网关仓库随附一份与这些校验对应的 JSON Schema：[`docs/plugin-config.schema.json`](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-config.schema.json)。把它接给编辑器或构建步骤时要用 **raw 地址** `https://raw.githubusercontent.com/FatttSnake/api-management/master/docs/plugin-config.schema.json` （`.vscode/settings.json` 里加一条 `json.schemas`、`fileMatch` 设为 `["**/META-INF/plugin-config.json"]`；JetBrains 用 JSON Schema Mapping；CI 里可以用 `check-jsonschema`）。别把上面那个 `github.com/.../blob/...` 网页地址交给工具：它返回的是 HTML，拿到它的客户端会**静默**加载失败——JetBrains 右下角照样显示识别到了 schema，但补全和校验一概不来。**但不要把它写进文件本身**：`$schema` 键本身就是网关不认识的属性，顶层只接受 `groups`、`datasources`，写了会直接导致安装失败。

### 6.7 文件存储与外链 `PluginStorage`

`context.storage` 是插件唯一的文件通道，支持**两种不可互换的寻址方式**：

| | 内容寻址 | 位置寻址 |
|---|---|---|
| 方法 | `saveContent` / `loadContent` / `existsContent` / `deleteContent` | `saveFile` / `loadFile` / `existsFile` / `deleteFile` / `fileExternalUrl` |
| key | 内容 SHA-256（也是返回值） | 插件自己挑的相对路径 |
| 存储形态 | 网关 zstd 压缩、相同内容全局一份 | 原样字节 |
| 免登外链 | **不支持**（压缩过，不能直接交给浏览器） | 支持，`fileExternalUrl(path, ttl)` |
| 适用 | 归档、去重、只经插件读写的对象 | 要给人下载 / 浏览器直接访问的文件 |

```kotlin
// 位置寻址：原样存、可发外链
val path = "files/$ownerId/$id.png"
context.storage.saveFile(path, bytes)
val url = context.storage.fileExternalUrl(path, Duration.ofHours(2))  // null = 网关拼不出绝对 URL

// 内容寻址：相同内容只存一份，适合归档与去重
val key = context.storage.saveContent(bytes)   // 返回 SHA-256
context.storage.existsContent(key)             // 写之前可以先问一句“是不是已经有同样的内容”
context.storage.loadContent(key)
```

- **引用计数**：`saveContent` 每次调用 +1，**每次成功保存都要配一次 `deleteContent`**；删除只减计数，字节由网关回收。
- **路径规则**：`path` 是 `/` 分隔的相对路径，每段必须匹配 `^[A-Za-z0-9][A-Za-z0-9._-]*$` 且长度 ≤ **200**，不能是 `.` / `..` 或 Windows 设备名（`con`、`prn`、`aux`、`nul`、`com1`-`com9`、`lpt1`-`lpt9`，带不带扩展名都算），也不能含 `\`、`:`；整条路径上限 **1024** 字符。用户给的文件名**不要直接拼进路径**（用 UUID + 过滤后的扩展名，原名存数据库）。
- **命名空间**：所有路径自动落在 `data/files/plugin-data/{插件ID}/` 下，签名与 key 都由插件 ID 组合而成，**无法访问别的插件或网关自己的文件**；越界路径会被拒绝（不是静默逃逸）。
- **外链语义**：免登 bearer 凭证，持有者在过期前一直可读、**无法提前吊销**；删除文件后立即失效。ttl 超过网关 `external-url-max-ttl` 会被**钳制**下来，而 0 或负数会被**拒绝**——一个永远打不开的链接从来不是调用者的本意；不传该参数则用配置的默认值。`local` 模式返回 `/public/storage/{插件ID}/{路径}?e=&s=` 的 HMAC 签名 URL，`s3` 模式返回对象存储预签名 URL。
- **外链与 `local` 后台同源**：网关对非图片白名单的类型一律以 `Content-Disposition: attachment` + `nosniff` 下发。

---

## 7. 构建 → 上传 → 授权 → 调用（全流程，以 `echo/` 为例）

```shell
# 1. 构建并签名（首次会自动生成 keys/、描述符、pub.pem）
cd echo
./gradlew build
# 产物 build/libs/echo-1.0.0.jar（已签名）；公钥在 keys/public.pem

# 2. 管理员加信任公钥（首次；内容来自 keys/public.pem）
POST /system/api/plugin/key   {"publicKey": "<...>", "alias": "FatttSnake"}

# 3. 上传安装
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar，最大 50 MB)

# 3.1 声明了 SQLite 数据源的插件（如 filebox 的 cache）：装完即用，无事可做
GET  /system/api/plugin/filebox/config
#    -> datasources: [ {name:"main", dbType:"MYSQL", required:true, configured:false, keys:[...]},
#                      {name:"cache", dbType:"SQLITE", required:false, configured:true, keys:[]} ]
#       SQLite 文件在 data/db/plugin/filebox/cache.db

# 3.2 声明了 MySQL 数据源的插件：连接信息就是几条普通配置项，因此走配置接口保存——
#     保存即由网关自动重挂载，不必再调 reload。保存是按分组写的，没传的键等于不做决定
PUT  /system/api/plugin/config
     {"pluginId":"filebox","groups":[
        {"key":"db","values":[
           {"key":"db.host","value":"10.0.0.240"},
           {"key":"db.port","value":"3306"},
           {"key":"db.name","value":"filebox"},
           {"key":"db.user","value":"filebox"},
           {"key":"db.password","value":"…"},
           {"key":"db.params","value":"useSSL=false&serverTimezone=UTC"}]}]}

# 3.2.1 保存前先试连（没传的项用已存值）
POST /system/api/plugin/filebox/config/datasource/test
     {"name":"main","values":[{"key":"db.host","value":"10.0.0.240"},{"key":"db.name","value":"filebox"}]}

# 4. 给用户授权
GET  /user/api/key/available-apis                 # 按插件分组的可选接口
POST /user/api/key   {"permissionCodes": ["api:echo:v1:ping", "api:echo:v1:version", ...]}

# 5. 调用（Basic 认证 accessKey:secretKey）
GET /api/echo/v1/ping     Authorization: Basic <base64(accessKey:secretKey)>
GET /api/echo/v1/version
GET /api/echo/v2/ping     # v2 覆盖 v1 的 ping（滚动兼容示例）
GET /api/echo/v2/whoami   # v2 专属
```

预期响应示例（`/api/echo/v2/ping`）：

```json
{ "code": 0, "success": true, "msg": "success", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

---

## 8. 管理操作摘要

| 操作 | 方式 | 说明 |
|---|---|---|
| 禁用 / 调价 | `PUT /system/api/plugin` | `enable: false` 立即生效，调用返回 `Api disabled` |
| 查看配置 | `GET /system/api/plugin/{pluginId}/config` | 返回插件的配置声明 + 折叠进去的当前值：每个字段有 `value`、`default`、`hasValue`、`unreadable`（secret 的 `value` 恒为 `null`）；另有构成数据源的配置项，以及每个数据源的 `name`、`dbType`、`required`、`configured` |
| 试连 | `POST /system/api/plugin/{pluginId}/config/datasource/test` | `{name, values}`；没传的项、或传了但没给值，都用已存值。**仅 MySQL**（SQLite 是网关自备的文件）；指向别的数据源的值、或 `main` 尚未配置会被拒，真正的失败回 40067 与驱动原文 |
| 修改配置 | `PUT /system/api/plugin/config` | 按分组保存：`{pluginId, groups:[{key, values:[{key,value}]}]}`。普通配置项立即生效；没传的键保持原值，传空则清除该键（文本类的空串是一个值）。`required` 只对本次点名的分组校验。**改到构成数据源的配置项会自动重挂载插件**，不需要额外步骤 |
| 配置数据源 | 同上（配置接口） | MySQL 数据源由其 `datasources` 条目点名的那些配置项描述。清空 host 即取消配置，这不是错误——插件只是不再拿到该数据源 |
| 重新挂载 | `POST /system/api/plugin/{pluginId}/reload` | 用已存储的 jar 原地重挂载：版本、DB 行、权限树、配置、数据全部保留。用于**换了 jar**；改数据源不需要它 |
| 升级 | 改 `apiPlugin.versionCode` 后重新构建上传 | 旧版自动替换；`versionCode <= 当前` 会被拒绝。**升级不清除插件配置与数据**（设置、数据源、文件区都保留），也不会把管理员禁用过的插件重新启用——`reload` 与改数据源的配置保存同样不会。内部实现是先卸载旧版本再安装新版本，所以插件会依次看到 `onStop` → `onUninstall` → `onInstall` → `onStart`，不要把 `onUninstall` 当成插件的终点（见 §6.4） |
| 卸载 | `DELETE /system/api/plugin/{pluginId}?purgeData=false` | 路由注销、DB 行删除、权限树清理。`purgeData` 默认为 `false`，即**保留**插件设置、SQLite 数据库目录与文件区，重装同一 pluginId 即可直接续用；`purgeData=true` 才清除这几类。**外部 MySQL 库不在此列**——网关不会去删 DBA 库里的表 |
| 重启 | 无需操作 | 已装插件从 blob 自动重挂载，无需重传 |

---

## 9. 常见问题

- **换插件身份 / 想让旧版本失效**：删除 `keys/private.pem` 后重新 `build` 会生成新公钥；请同步把新 `keys/public.pem` 交给管理员更新信任库（旧插件重启后不再重挂载）。
- **`Plugin signer is not trusted`**：公钥未上传到信任库，或已被禁用；用 `POST /system/api/plugin/key` 加入并启用。（完全没有签名的 jar 回的是另一条 `Plugin signature invalid`——确认你传的是 `build` 产物而不是 `./gradlew jar` 的产物。）
- **`Version code N is not greater than current M`**：同 pluginId 已存在更高版本；升级需增大 `apiPlugin.versionCode`。
- **`./gradlew jar` 得到的 jar 没有签名**：签名只在 `build` / `signPlugin` 里做，请用 `./gradlew build` 产出物。
- **`verifyPlugin` 失败**：jar 内公钥与签名私钥不配对（例如误删了其中一个文件）——删除整个 `keys/` 重新 `build`。
- **设置写入报唯一键冲突**：设置存在 `t_b_api_plugin_setting`，其唯一键是 `(plugin_id, setting_key)`——每个插件每个 key 一行，没有软删除列。网关侧不存在需要修复的唯一键冲突：`saveSetting` 是先读后写；而 `purgeData=false` 时设置会跨重装保留，所以真正要找的是"某个插件既把 key 写进自己的运行态、又在配置 schema 里声明了同一个 key"这种情况（声明过的 key 归管理员，`saveSetting` 会直接拒绝）。
- **升级后接口消失**：新 jar 里必须仍含全部需要保留的 `@ApiController`；卸载旧版后只保留新 jar 声明的接口。
- **插件装上了，但生命周期钩子一个都不跑 / 自己的 `@Service` 注入不进来**：该组件落在子容器扫描范围之外（§6.1）——把它放到与 `@ApiController` 同一棵包树下。`mainClass` 写了一个不是 bean 的类名同样不会失败，网关会静默改用找到的第一个 `PluginLifecycle` bean（§6.4）。
- **端点 404**：方法级 `@RequestMapping` 不会被解析（§6.1）——改用 `@GetMapping` / `@PostMapping` 等。同时检查 `@ApiController.plugin` 是否等于描述符 `pluginId`，以及 URL 里的版本是否**大于等于**控制器声明的 `version`。
- **`@Scheduled` 不触发**：插件的子容器没有自动配置、也不开启调度（§6.1）——确实需要就在自己的配置里打开。
- **怎么知道插件为什么没挂载**：挂载失败会记录在插件行上，并通过它的 `loadError` 字段暴露（挂载成功时为 `null`），从插件列表或 `GET /system/api/plugin/{pluginId}/info` 都能读到。如果插件在列表里、也是启用状态，行为却像不存在一样，那通常是上面两条说的扫描或生命周期发现问题，而不是安装失败。

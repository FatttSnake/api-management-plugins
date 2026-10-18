# API 插件开发指南（简体中文）

本指南面向**插件开发者**。API 以**插件 jar** 形式独立编写、打包、签名，上传到网关后**热插拔**（安装 / 禁用 / 升级 / 卸载），重启后自动重挂载。仓库内的 `echo/` 是一个可直接运行的完整示例，随文档对照阅读效果最佳。

**写一个插件只需一个 `build.gradle.kts`（约 10 行）+ 你的 Kotlin 源码**：签名、密钥、描述符、文档所需资源都由 Gradle 插件 `top.fatweb.api-plugin` 自动完成。

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

拉到更新的网关仓库之后要重新执行一次，以保证 Gradle 插件及其提供的任务是最新的。

---

## 1. 整体模型

```
插件工程（独立 Gradle build）                   网关（api-management-backend）
┌──────────────────────────────────┐        ┌──────────────────────────────────────────┐
│ Kotlin 源码（含响应 DTO）         │ 上传   │ PluginSigner.verify（签名完整性）          │
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
   └─ kotlin/com/example/geo/GeoResponses.kt    # 响应 DTO（文档由网关从它们生成）
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
    versionCode = 1                           // 必填；高于已装版本为升级，相同则为原地重部署
    description = "把地理编码能力开放成 API"      // 可选
    author = "你的名字"                         // 可选
    mainClass = "com.example.geo.GeoLifecycle" // 有 PluginLifecycle 时可选
}
```

`apiPlugin` 各字段的默认值与对应描述符字段：

| DSL 字段 | 必填 | 默认 | 写入描述符 |
|---|---|---|---|
| `pluginId` | ✅ | 工程名 | `pluginId`（插件的唯一身份来源；`@ApiController` 不再重复它） |
| `pluginName` | | 同 `pluginId` | `name` |
| `versionName` | | 工程 `version`（空串同样回退） | `versionName` |
| `versionCode` | ✅ | —（必填，无默认） | `versionCode`（更高为升级；与已装版本相同则可重新部署） |
| `description` / `author` | | 空 | 同名 |
| `mainClass` | | 无 | `mainClass`（缺省时用插件唯一的 `PluginLifecycle` bean；有多个 bean 又没写 `mainClass` 会导致安装失败） |
| `sdkVersion` | | 插件自带 SDK 版本 | （不写描述符，只控制依赖版本） |
| `archiveName` | | `<pluginId>-<versionName>.jar` | （jar 文件名） |

> 该插件会自动：给工程加 `repositories`（mavenCentral + mavenLocal）与 SDK 的 `implementation` 依赖；**每次构建**从上面的 DSL 生成 `META-INF/api-plugin.json` 与 `META-INF/plugin.pub.pem`（build 目录内）；生成 `keys/`；按 `<pluginId>-<versionName>.jar` 命名并签名。所以仓库里**不需要**手写 `api-plugin.json` / `plugin.pub.pem`。

### 2.3 `.gitignore`

```gitignore
keys/            # 私钥/公钥（私钥绝不可提交）
build/
.gradle/
```

### 2.4 四个命令

```shell
./gradlew build           # 一键：生成 keys/ + 描述符 + pub.pem + 签名
./gradlew genPluginKeys   # 单独重新生成/确认密钥（已有则跳过）
./gradlew verifyPlugin    # 自检 jar 签名（签名无效会失败退出）
./gradlew deployPlugin    # 构建 + 签名 + 验签 + 上传，直接推到运行中的网关
```

产物：`build/libs/<pluginId>-<versionName>.jar`，**已签名**，可直接上传。

插件在 `api plugin` 任务组下共注册六个任务：`generatePluginDescriptor` 与 `preparePluginPubKey`（都挂在 `jar` 上，构建时会自动跑到）、`genPluginKeys`、`signPlugin`（挂在 `build` 上）、`verifyPlugin` 与 `deployPlugin`。上面这四条是需要手动执行的，其余由构建代劳。

`deployPlugin` 就是 §7 第 3 步的手工上传，改一行就重新部署的循环用它：

```shell
./gradlew deployPlugin -PapiPlugin.serverUrl=http://localhost:8111 -PapiPlugin.accessToken=<token>
```

两项配置也都可以走环境变量（`API_PLUGIN_SERVER_URL`、`API_PLUGIN_ACCESS_TOKEN`），且都不该写进 `build.gradle.kts`。token 即控制台登录响应里的 `accessToken`，默认有效期 2 小时；没有任何东西会轮换它，所以在这段时间内一直可用（除非网关的 Redis 没有做持久化又被重启）。加 `-PapiPlugin.trustSigner=true` 可以在同一次运行里顺便把该 jar 的签名公钥登记到网关——属于一次性便利，因为公钥登记之后会一直受信。

想在插件源码里下断点、以及哪些改动不用重新部署就能生效，见网关仓库的 [docs/plugin-debugging_zh.md](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-debugging_zh.md)。

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
| `pluginId` | ✅ | 唯一 ID，`^[a-z][a-z0-9-]{0,63}$`——最多 64 字符，与网关 `plugin_id` 列宽一致。所有端点都挂在 `/api/{plugin}/v{version}/…` 之下 |
| `name` | ✅ | 插件显示名（菜单/权限树/文档用） |
| `versionName` | ✅ | 人类可读版本，如 `1.2.0`；手写描述符**必填**（用 Gradle 插件则缺省取工程 `version`） |
| `versionCode` | | 整数，至少 1。**高于**已装版本即升级；**等于**已装版本允许，会原地替换代码（改一行就重新部署的循环正是反复做这件事）；**低于**已装版本会被拒绝 |
| `description` / `author` | | 描述 / 作者 |
| `mainClass` | | `PluginLifecycle` 实现类全限定名（需 `@Component`，且必须落在被扫描的包内——见 §6.1）。缺省时用插件唯一的 `PluginLifecycle` bean；写了匹配不到任何 bean 的值，或有多个生命周期 bean 又没写 `mainClass`，都会**导致安装失败** |

> `versionCode` 只沿用 Android 语义的前半段：**同一 pluginId 只允许装一个实例**。后半段不同——升级必须往更高的 `versionCode` 走，但**重装当前已安装的版本是允许的**，会原地替换；只有**更低**的版本会被拒绝。所以开发期间反复迭代不必每次递增。

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

## 5. OpenAPI 文档

**不需要手写 OpenAPI 片段。** 网关在每次挂载插件时，从你的控制器反射生成一份 OpenAPI 3.1 片段（`paths` + `components.schemas`），写入插件记录，并由 `GET /user/api/docs/{pluginId}` 给最终用户展示。这份片段与网关实际挂载的接口同源（同一份反射结果），因此不可能与 `@Operation` / `@GetMapping` 漂移。

要让它描述得完整，只需把事情在代码里表达出来：

### 5.1 返回值用具体类型，不要用 `Map`

`ApiResponse<Map<String, Any?>>` 只能生成一个开放对象 `{"type":"object","additionalProperties":{}}`——`Map` 的键在运行时无从得知。声明一个 DTO 即可：

```kotlin
// GeoResponses.kt
data class GeocodeResponse(
    @field:Schema(description = "格式化地址", example = "北京市东城区…") val address: String,
    @field:Schema(description = "纬度") val latitude: Double?,
    @field:Schema(description = "经度") val longitude: Double?
)

// GeoController.kt
@Operation(summary = "地理编码", description = "把地址解析为经纬度", operationId = "geocode")
@GetMapping("/geocode")
fun geocode(@RequestParam("address") address: String): ApiResponse<GeocodeResponse> =
    ApiResponse.ok(geocodeService.geocode(address))
```

生成的片段里 `data` 就是 `GeocodeResponse` 的 schema（含字段描述、类型、可空）。**注意线上 JSON 的形状**：DTO 的字段名与可空性一旦确定就是对外契约，要慎重选择。

### 5.2 参数、请求体、描述

- 方法参数上的 `@PathVariable` / `@RequestParam` / `@RequestHeader` / `@CookieValue` → `parameters`（`in`、`required` 由注解决定；未写名字时取 Kotlin 参数名）；
- `@RequestBody` → `application/json` 的 `requestBody`；`@RequestPart` → `multipart/form-data`，每个 part 成为一个属性；
- 补充说明用 swagger 注解：参数上 `@Parameter(description = ..., example = ...)`，DTO 字段上 `@field:Schema(description = ..., example = ...)`；
- `operationId` / `summary` / `description` 取自方法上的 `@Operation`（`operationId` 仍是接口的持久化身份，必填）。

### 5.3 生成规则（了解即可）

- `paths` 的 key 是完整公开路径 `/api/{plugin}/v{version}/...`，与调用 URL 一致；
- 片段是 OpenAPI 3.1，Schema 遵循 JSON Schema 2020-12：可空用类型联合 `"type": ["integer", "null"]`，示例用 `examples: [值]` 数组，二进制用 `contentMediaType`；
- 返回 `ApiResponse<T>` 时 envelope 被内联（`code`/`success`/`msg`/`data`），`data` 是 `T` 的 schema；`ResponseEntity<ByteArray>` → `application/octet-stream`；`Unit`/`void` → 200 响应无 `content`；
- 一个片段覆盖该插件全部接口，多个 `@ApiController` 版本（v1/v2）都在其中；
- **生成失败不影响安装**：文档端点仍返回接口列表，只是该插件缺参数/结构详情，网关日志里有一条 WARN。

---

## 6. 编写控制器与业务代码

SDK 的 `implementation` 依赖已由 Gradle 插件自动加入，并透传 spring-web / spring-context / swagger 注解 / Kotlin Jackson 模块 / SLF4J，直接写控制器即可。

### 6.1 `@ApiController`

```kotlin
@ApiController(
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

- 控制器用**构造函数注入** `PluginContext` 和自己的 `@Service`，子容器自动装配。但它的扫描范围比 Spring Boot 应用窄：扫描基包是 jar 自身的顶层包中、能通往你的 `@ApiController` 所在包的那些，且只认 `@Component`（因此 `@Service` / `@Configuration` 有效）。**落在这些根包之外的组件不会被注册**，挂载时会打一条点名该组件的 WARN——所以协作者要和控制器放在同一棵包树下。若一个根包都推导不出来，安装会直接失败并报 `Cannot derive scan packages`。
- 这个子容器里只有你的 bean，加上网关注册的 `pluginContext` 与 `pluginDataSource.<name>`，除此之外什么都没有：它是一个普通的 `GenericApplicationContext`，**没有任何自动配置**。特别地，没有任何东西会开启调度，所以 `@Scheduled` 方法除非你自己在配置里打开，否则永远不会被处理。
- 插件级元数据（`name`/`description`/`author`/版本号）在 `apiPlugin { }` 里**声明一次**（→ `api-plugin.json`），插件的身份同样如此。`@ApiController` 只携带 API `version` 与子 `path`，**没有 `plugin` 属性需要对齐了**。
- 每个接口的展示 `name` = `@Operation.summary`（缺省回退方法名）、`description` = `@Operation.description`（缺省为空），会体现在权限树与接口表里。
- 每个端点方法标注 HTTP 映射注解；只有 `@GetMapping` / `@PostMapping` / `@PutMapping` / `@PatchMapping` / `@DeleteMapping` 会被解析。方法级 `@RequestMapping` **不在其列**——**安装会被拒绝**并点名该方法，而不是静默跳过、让调用方拿到 404。每个被映射的方法都必须有 `operationId`（缺失即拒绝），它决定 API 作用域码：`api:{plugin}:v{version}:{operationId}`。
- `version` 必须**至少为 1**：声明 `version = 0` 或负数的控制器会拼出路由器永远匹配不到的路径，因此安装会直接拒绝，而不是写出一条谁也调不到的接口。
- 同一插件内，两个端点不得占用相同的路径与 HTTP 方法——会在安装期被拒绝并点名两个方法，而不是留到之后变成 ambiguous mapping 报错。**不同**版本下路径相同是允许的：版本本身就在路径里。
- `@ApiController(path = [...])` 在版本段之后追加一段基础路径：`/api/{plugin}/v{version}/<path[0]>/<方法路径>`。写成数组只是为了对齐 `@RequestMapping` 的形状，**实际只支持一个元素**——声明多个会在安装期被拒绝，而不是丢掉其余几个。
- 方法级映射同理，一个方法只支持一个路径：`@GetMapping("/a", "/b")` 会在安装期被拒绝并点名该方法，而不是只挂 `/a`。需要多个路径就各自声明一个方法，或映射一个公共前缀。`value` 与 `path` 互为别名，用任意一个写路径即可——两个都写且内容不同会被拒绝，而不是替你挑一个。
- `params` / `headers` / `produces` / `consumes` 都按声明的语义作为路由条件生效，所以「要求某个参数/请求头」的方法行为与 Spring 应用里一致。
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

一个插件可以声明多个数据源，两种方言也可以混用。数据源在**挂载时构建**，而保存构成数据源的配置项时**网关会自动重挂载插件**（见 §8）——所以插件要能接受保存之后 `onStop` / `onStart` 再跑一遍，只存在内存里的状态会随之丢失。被管理员禁用的插件是唯一例外：在重新启用之前，任何保存都不会把它挂起来。

### 6.4 生命周期 `PluginLifecycle`

```kotlin
@Component
class GeoLifecycle : PluginLifecycle {
    override fun onStart(context: PluginContext) {}      // 每次挂载、路由注册之前
    override fun onStop(context: PluginContext) {}       // 每次卸载、正在服务的请求都结束之后
    override fun onUninstall(context: PluginContext) {}  // 这一版要走了（升级也会走；数据源已关、类加载器仍在）——绝不在这里删数据
    override fun onPurge(context: PluginContext) {}      // 明确要求清除数据的卸载；在这里抛异常会拒绝该次卸载
}
```

在 `apiPlugin { mainClass = "…" }` 里指定，或让网关自动发现。`echo/` 的 `EchoLifecycle` 在每个钩子里写入一条插件级设置（`startedAt` / `stoppedAt` / `uninstalledAt`），演示 `PluginContext.saveSetting` 的用法。

**`PluginLifecycle` 只有在它确实是一个被子容器扫描到的 Spring bean 时才会运行**（见 §6.1）：放在控制器包树之外的实现不会被实例化，所有钩子跳过，挂载时会打一条点名该组件的 WARN。描述符里的 `mainClass` 指明哪个生命周期 bean 是入口：不写时用插件唯一的 `PluginLifecycle` bean，**有多个 bean 又没写 `mainClass` 会导致安装失败**，而不是随便挑一个；写了 `mainClass` 却匹配不到任何 bean 同样会失败。一个生命周期 bean 都没有时，插件就是在完全没有生命周期的情况下挂载的。

**挂载不是一次性事件，这些钩子因此也不是。** 插件在安装、网关启动重挂载、`reload`、任何一次改到它数据源组成值的保存，以及**它处于挂载失败状态时的任意一次保存**之后都会重新挂载，而**每次挂载都会跑 `onStart`**。重挂载的顺序是**先把新实例建起来并跑完 `onStart`（此刻旧实例仍在服务），再拆掉旧实例**，所以中途会出现自己的 `onStop`（在新 `onStart` 之后），只放在内存里的状态会随之丢失——也意味着 `onStart` 可能和上一份实例同时在跑。最后这一点直接决定你**能怎么改自己的库表**：新版本可以在 `onStart` 里做迁移（它的数据源是开着的），但那一刻**被替换的旧版本仍在按旧 schema 服务**，而旧版本自己的 `onUninstall` 跑的时候**数据源已经关了**。所以任何"改掉旧版本正在读的东西"的迁移——删列、改名——都是旧实例扛不住的。要做就**只加表、只加列**，别去动既有的东西，等发布过一两轮再开始依赖新增部分。

**禁用插件不等于卸载插件。** `enable: false` 会把插件的 runtime 拆掉：路由注销、网关下次启动时不再加载它、请求它的接口返回 `Api disabled` 而不会进到你的代码。它的设置、数据源、文件区与接口上的授权全部原地保留，重新启用就是把同一个 jar 挂回它们之上。没有任何东西会自动把它拉回来——保存配置不会重挂载一个被禁用的插件，**在被禁用的插件上上传新版本也只会存下来、不会启动它**。

**决定 `onUninstall` 是否运行的是「声明的版本有没有变」**，而不是「插件是不是要没了」。升级会跑它，重装同一个已装版本不会跑——`reload`、改数据源触发的重挂载、网关关闭也都不会跑，因为这几种都是把同一个 jar 重新挂起来。它在那一版被拿出网关时运行，而它**绝不是删数据的地方**：它**每次发版升级都会跑**，在这里释放自己的 storage 或设置，就等于每次升级都丢一次，而不是只在管理员明确要求时才丢。删数据是 `onPurge` 的职责，升级永远不会触发它。

**`onPurge` 是唯一一个「失败会拒绝该次卸载」的钩子。** 插件自己拥有的表（包括网关从不触碰的 MySQL 库里的表）就是在它里面删的，它运行时插件的数据源、storage 与设置都还可用。被管理员禁用的插件会为此**被临时挂起来跑最后一次**（刻意不跑它的 `onStart`），因为一个没在运行的插件同样得有能力删掉自己的数据。它一旦抛异常，网关会回给管理员 `40068`、把原因记进插件行的 `loadError`，并且**什么都不删**——行、配置、文件全都还在，修好原因后再执行一次卸载即可。

### 6.5 打包依赖

第三方依赖会**自动打包**。Gradle 插件解析 `runtimeClasspath`，过滤掉网关已提供的库，其余依赖以原始 jar 字节放进 `META-INF/api-plugin-libs/<sha256>.jar`，并生成描述它们的 `index.json`。**不需要**自己配 Shadow 或 fat jar；插件 jar 的大小上限是网关的 `app.storage.plugin-jar-max-bytes`（默认 200 MB）。

网关已提供的库不会被打包：SDK、Spring、Springdoc/Swagger、SLF4J/Logback、Jackson（2 与 3）、Micrometer、commons-logging、JSpecify、Kotlin/Kotlinx、Jakarta API、Tomcat、MyBatis-Plus、HikariCP、MySQL/SQLite/Flyway 与 BouncyCastle，运行时从网关解析。要保留瘦包可设 `apiPlugin { bundleDependencies = false }`；只需要编译、不希望打包的依赖用 `compileOnly`。

网关会把嵌套 jar 解压到内容寻址缓存，并按依赖的 SHA-256 与直接依赖集合在插件间共享同一个类加载器——同一个库无论被多少插件使用都只定义一次。每个库保留自己的 `META-INF/services` 与 multi-release 布局，依赖类不会被合并进你的 jar。

仅下列类名前缀委托给网关父加载器（保证注解/序列化类身份一致）：`java.* javax.* jakarta.* sun.* jdk.* org.springframework.* org.apache.commons.logging.* io.micrometer.* org.jspecify.* org.slf4j.* tools.jackson.* com.fasterxml.* io.swagger.* org.springdoc.* com.baomidou.* com.zaxxer.hikari.* com.mysql.cj.jdbc.* org.sqlite.* kotlin.* kotlinx.* top.fatweb.apimanagement.*`。其余类名优先从你打包的库加载。

这些前缀**只从父加载器解析，不再回退到 jar**：这些命名空间下的类永远来自网关，打包一份替换品不会生效。插件自己的代码不要放在 `top.fatweb.apimanagement.*` 下，也不要试图遮蔽网关已有的连接池/驱动类。

BouncyCastle 默认由网关提供（1.86，`bcprov` + `bcpkix` + `bcutil`）。网关提供的是运行期类路径，编译期仍需自行声明：`compileOnly("org.bouncycastle:bcpkix-jdk18on:1.86")`（`bcprov`、`bcutil` 随其传递引入；BC 不会被放进插件包）。需要其他版本的插件可设 `apiPlugin { bundleBouncyCastle = true }`，它会打包完整 BC 闭包。由于 `Security` provider 是 JVM 全局注册的，自带 BC 时必须把 `Provider` 实例传给 `JcaContentSignerBuilder.setProvider(...)`、`JcaX509CertificateConverter.setProvider(...)` 等 API——**不要调用 `Security.addProvider`**：全局注册会在 reload 后钉住该插件的共享类加载器，并可能与网关的 `"BC"` provider 冲突。

> SDK 声明 `spring-web` / `spring-context` / `swagger-annotations` / `slf4j-api` / Jackson 3（`jackson-module-kotlin`）：直接用 `LoggerFactory` 写日志、用 `jacksonObjectMapper()` 做序列化即可。`org.slf4j.` 与 `tools.jackson.` 都是向上委派的，运行期你的日志调用落在网关的绑定上、你的 mapper 就是网关的 Jackson——构建时已经会把它们过滤掉。其余自己持有的资源记得在 `onStop` 里释放。

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
- **所有权**：schema 声明过的 key 归管理员，插件 `saveSetting` 写它会抛异常；未声明的 key 仍是插件的运行态空间（`startedAt` 这类照旧可写）。
- **secret 项**：网关加密存储（AES-GCM，密钥派生自 `app.security.tokenSecret`），接口**永不回传明文**——`value` 恒为 `null`，用 `hasValue` 表示存过没有。**保持原值就是提交时不带这个 key**：带别的值是覆盖，留空是清除；没有掩码可以回传，控制台若自造一个 `******` 回传，每次保存都会覆盖掉已存密钥。插件读到的始终是明文。`unreadable: true` 表示密文在 `tokenSecret` 轮换后已解不开，只能重新录入（在此之前 `getSetting` 读它抛 `IllegalStateException`）；轮换会让所有 secret 与所有外链同时失效。`hasValue` 每个字段都有，表示管理员自己设过值，否则读到的是声明的默认值。
- **校验是网关侧权威的**：未声明的 key、类型不符、超范围、enum 越界都会被拒绝保存。同一分组或同一 key 重复提交会被判为结构性错误；又因为数据源背后的值就是网关构建连接的依据，保存前还会先过一遍连接规则（host 形状、端口范围、库名、驱动参数黑名单）——根本连不上的组合会被拒，而不是先存下来。
- **schema 非法即拒绝安装**（刻意的 fail-fast）：文件不存在 = 无配置项，文件存在但解析失败 = 安装失败并报错；schema 内分组/字段 key 重复、或 `mysql` 数据源没有同时给出 `host` 与 `database` 槽位，同样安装失败。
- **生效时机**：普通配置项**改完即生效**（每次 `getSetting` 都读库），不需要重新挂载。构成数据源的配置项不同——连接是网关构建的，改它会重挂载插件（见 §8）。这个判断依据的是**值确实变了**而不是"有人提交了请求"，所以保存无关配置项不会重启你的插件。唯一例外是插件当前处于挂载失败状态（`load_error` 有值）：此时**任意一次保存都会重挂载它**，好让管理员修好之后不用手动 `reload` 就能自动叫回来。
- **编辑器 / 构建期反馈**：网关仓库随附一份与这些校验对应的 JSON Schema：[`docs/plugin-config.schema.json`](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-config.schema.json)。接给编辑器或 CI 时要用 **raw 地址** `https://raw.githubusercontent.com/FatttSnake/api-management/master/docs/plugin-config.schema.json`（`.vscode/settings.json` 里加一条 `json.schemas`、`fileMatch` 设为 `["**/META-INF/plugin-config.json"]`；JetBrains 用 JSON Schema Mapping；CI 可用 `check-jsonschema`）。用 `github.com/.../blob/...` 网页地址会拿到 HTML，客户端**静默**加载失败。**别把它写进文件本身**：`$schema` 是网关不认识的属性，顶层只接受 `groups`、`datasources`，写了会导致安装失败。

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
#    也可以交给 Gradle 插件：./gradlew deployPlugin -PapiPlugin.serverUrl=… -PapiPlugin.accessToken=…
#    （重传当前已安装的版本会原地替换，所以调试循环不必递增 apiPlugin.versionCode）

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
| 启用 / 禁用 | `PATCH /system/api/plugin` | `{id, enable}`。禁用会把插件的 runtime **拆掉**：不再服务、网关下次启动不再加载它、请求它的接口返回 `Api disabled`；它的配置、数据源、文件与接口授权全部保留，重新启用就是把同一个 jar 挂回它们之上。`PUT /system/api/plugin` 也可以在改插件级默认单价/限流/门禁时带上同一个开关 |
| 查看配置 | `GET /system/api/plugin/{pluginId}/config` | 返回插件的配置声明 + 折叠进去的当前值：每个字段有 `value`、`default`、`hasValue`、`unreadable`（secret 的 `value` 恒为 `null`）；另有构成数据源的配置项，以及每个数据源的 `name`、`dbType`、`required`、`configured` |
| 试连 | `POST /system/api/plugin/{pluginId}/config/datasource/test` | `{name, values}`；没传的项、或传了但没给值，都用已存值。**仅 MySQL**（SQLite 是网关自备的文件）；指向别的数据源的值、或 `main` 尚未配置会被拒，真正的失败回 40067 与驱动原文 |
| 修改配置 | `PUT /system/api/plugin/config` | 按分组保存：`{pluginId, groups:[{key, values:[{key,value}]}]}`。普通配置项立即生效；没传的键保持原值，传空则清除该键（文本类的空串是一个值）。`required` 只对本次点名的分组校验。**改到构成数据源的配置项会自动重挂载插件**，不需要额外步骤 |
| 配置数据源 | 同上（配置接口） | MySQL 数据源由其 `datasources` 条目点名的那些配置项描述。清空 host 即取消配置，这不是错误——插件只是不再拿到该数据源 |
| 重新挂载 | `POST /system/api/plugin/{pluginId}/reload` | 用已存储的 jar 原地重挂载：版本、DB 行、权限树、配置、数据全部保留。用于**换了 jar**；改数据源不需要它 |
| 升级 | 调大 `apiPlugin.versionCode` 后重新构建上传 | 旧版**原地替换**；只有**更低**的 `versionCode` 会被拒绝（40066）。**管理员设过的东西全部保留**：插件级默认单价/限流/门禁、每个接口自己的单价、计费模式、是否需要 key、限流、访问模式与启用状态、这些接口上的全部角色授权，外加设置、数据源与文件区。只有新版本不再提供的接口会连同它的行与授权一起消失。重传当前已安装的版本做的是同一件事，且是允许的——调试循环用的就是它。被管理员禁用的插件不会被重新启用、也不会被启动；`reload`、改数据源的配置保存以及其它任何保存同样不会。内部实现是先把新版本建起来并跑完 `onStart`，再拆掉旧版本，所以插件会依次看到 `onStart`（新）→ `onStop` → `onUninstall`（旧）（见 §6.4） |
| 卸载 | `DELETE /system/api/plugin/{pluginId}?purgeData=false` | 路由注销、DB 行删除、权限树清理。`purgeData` 默认为 `false`，即**保留**插件设置、SQLite 数据库目录与文件区，重装同一 pluginId 即可直接续用；`purgeData=true` 才清除这几类。**外部 MySQL 库不在此列**——网关不会去删 DBA 库里的表，那是 `onPurge` 的活。**没在运行**的插件（被禁用，或挂载失败）会为此被临时挂起来跑最后一次退出钩子；`onPurge` 一旦抛异常，卸载会被**拒绝**（40068）且什么都不删 |
| 重启 | 无需操作 | 已装插件从 blob 自动重挂载，无需重传 |

---

## 9. 常见问题

- **换插件身份 / 想让旧版本失效**：删除 `keys/private.pem` 后重新 `build` 会生成新公钥；请同步把新 `keys/public.pem` 交给管理员更新信任库（旧插件重启后不再重挂载）。
- **`Plugin signer is not trusted`**：公钥未上传到信任库，或已被禁用；用 `POST /system/api/plugin/key` 加入并启用。（完全没有签名的 jar 回的是另一条 `Plugin signature invalid`——确认你传的是 `build` 产物而不是 `./gradlew jar` 的产物。）
- **`Version code N is older than the installed M`**：要部署的版本低于已安装的版本；升级需调大 `apiPlugin.versionCode`。重传当前已安装的版本是允许的，所以只有发版才需要动它。
- **`./gradlew jar` 得到的 jar 没有签名**：签名只在 `build` / `signPlugin` 里做，请用 `./gradlew build` 产出物。
- **`verifyPlugin` 失败**：jar 内公钥与签名私钥不配对（例如误删了其中一个文件）——删除整个 `keys/` 重新 `build`。
- **设置写入报唯一键冲突**：设置存在 `t_b_api_plugin_setting`，其唯一键是 `(plugin_id, setting_key)`——每个插件每个 key 一行，没有软删除列。网关侧不存在需要修复的唯一键冲突：`saveSetting` 是先读后写；而 `purgeData=false` 时设置会跨重装保留，所以真正要找的是"某个插件既把 key 写进自己的运行态、又在配置 schema 里声明了同一个 key"这种情况（声明过的 key 归管理员，`saveSetting` 会直接拒绝）。
- **升级后接口消失**：新 jar 里必须仍含全部需要保留的 `@ApiController`；卸载旧版后只保留新 jar 声明的接口。
- **插件装上了，但生命周期钩子一个都不跑 / 自己的 `@Service` 注入不进来**：该组件落在子容器扫描范围之外（§6.1）——挂载时会打一条点名该组件的 WARN，找到它并把组件移到与 `@ApiController` 同一棵包树下。`mainClass` 写了一个不是 bean 的类名、或有多个 `PluginLifecycle` bean 却没写 `mainClass`，都会**导致安装失败**（§6.4），而不是静默挑一个。
- **端点 404**：检查 URL 里的版本是否**大于等于**控制器声明的 `version`，以及控制器是否声明了 `version = 0`（安装会直接拒绝，因为谁也调不到）。方法级 `@RequestMapping` 不会是原因——它会在安装期被拒绝（§6.1）。有一种"404"其实不是 404：请求**已被管理员禁用**的插件的接口，返回的是 `Api disabled`，网关会区分这两种情况。
- **插件被禁用了却还在干活**：检查它在路由之外做了什么。禁用会把 runtime 拆掉并让它保持不运行（下次启动也不加载），但拆的是**runtime**——如果插件的工作是由外部发起的（它在别处注册过 webhook、或某个服务会来轮询它），那部分不是网关能停掉的。
- **`@Scheduled` 不触发**：插件的子容器没有自动配置、也不开启调度（§6.1）——确实需要就在自己的配置里打开。
- **怎么知道插件为什么没挂载**：挂载失败会记录在插件行上，并通过它的 `loadError` 字段暴露（挂载成功时为 `null`），从插件列表或 `GET /system/api/plugin/{pluginId}/info` 都能读到。如果插件在列表里、也是启用状态，行为却像不存在一样，那通常是上面两条说的扫描或生命周期发现问题，而不是安装失败。

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
| `mainClass` | | `PluginLifecycle` 实现类全限定名（需 `@Component`）；缺省则自动发现 |

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
# 期望输出: Signature valid
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

可选的手写资源 `src/main/resources/META-INF/plugin-openapi.json`（OpenAPI 3.0 片段），描述参数与数据结构，供网关给最终用户展示。`echo/` 的片段：

```json
{
  "openapi": "3.0.1",
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
          "message": { "type": "string", "example": "pong" },
          "userId": { "type": "integer", "format": "int64", "nullable": true }
        }
      }
    }
  }
}
```

- `paths` 的 key 用完整公开路径 `/api/{plugin}/v{version}/...`（与调用 URL 一致）；
- 安装时原样存入 `t_s_api_plugin.openapi`；
- `GET /user/api/docs`（列表）/ `GET /user/api/docs/{pluginId}`（详情）返回给前端；
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

- 控制器用**构造函数注入** `PluginContext` 和自己的 `@Service`，子容器自动装配。
- 插件级元数据（`name`/`description`/`author`/版本号）在 `apiPlugin { }` 里**声明一次**（→ `api-plugin.json`）；`@ApiController` 只声明每个控制器属于哪个插件（`plugin`，必须等于 descriptor 的 `pluginId`）与哪个 API 版本（`version`）。
- 每个接口的展示 `name` = `@Operation.summary`（缺省回退方法名）、`description` = `@Operation.description`（缺省为空），会体现在权限树与接口表里。
- 每个端点方法标注 HTTP 映射注解（`@GetMapping` 等）；`operationId`（缺省=方法名）决定 API 作用域码：`api:{plugin}:v{version}:{operationId}`。
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

> 网关侧的鉴权/限流/计费错误（Key 无效、配额超限等）会自动以 `ApiResponse` 形状返回（复用网关自身错误码，`code != 0`）。

### 6.3 数据隔离与交互 `PluginContext`

插件**不接触网关数据源/MyBatis**；经 `PluginContext` 与网关交互：

| 方法 | 说明 |
|---|---|
| `datasource` | 插件自己的独立数据源（管理员在 `t_s_plugin_datasource` 配置），未配置为 null |
| `currentUserId()` / `currentAccessKeyId()` | 当前调用者 |
| `getBalance(userId)` | 查用户余额 |
| `getInterfaceInfo(code)` | 查接口运行期配置 |
| `getSetting(key)` / `saveSetting(key, value)` | 插件级设置（网关持久化） |

如需独立存储：管理员为插件配置数据源（支持独立 MySQL 库 或 每插件一个 SQLite 文件），密码加密存储，数据源只注册进该插件自己的子容器。

### 6.4 生命周期 `PluginLifecycle`

```kotlin
@Component
class GeoLifecycle : PluginLifecycle {
    override fun onInstall(context: PluginContext) {}    // 安装后（可做 DDL/种子数据）
    override fun onStart(context: PluginContext) {}     // 启动重挂载后
    override fun onStop(context: PluginContext) {}      // 卸载/关停前
    override fun onUninstall(context: PluginContext) {} // 卸载后
}
```

在 `apiPlugin { mainClass = "…" }` 里指定，或让网关自动发现。`echo/` 的 `EchoLifecycle` 在每个钩子里写入一条插件级设置（`installedAt` / `startedAt` / `stoppedAt` / `uninstalledAt`），演示 `PluginContext.saveSetting` 的用法。

### 6.5 自带依赖

插件可**打包自己的第三方库**（子优先 ClassLoader 隔离）。仅下列前缀委托给网关父加载器（保证注解/序列化类身份一致）：`java.* javax.* jakarta.* org.springframework.* tools.jackson.* com.fasterxml.* io.swagger.* org.springdoc.* com.baomidou.* kotlin.* kotlinx.* org.slf4j.* top.fatweb.apimanagement.*`。其余类名从插件 jar 优先加载。若需打包依赖，用 shadow/fat jar 将依赖类合并进插件 jar。

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
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar)

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
{ "code": 0, "success": true, "msg": "OK", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

---

## 8. 管理操作摘要

| 操作 | 方式 | 说明 |
|---|---|---|
| 禁用 / 调价 | `PUT /system/api/plugin` | `enable: false` 立即生效，调用返回 `Api disabled` |
| 升级 | 改 `apiPlugin.versionCode` 后重新构建上传 | 旧版自动替换；`versionCode <= 当前` 会被拒绝 |
| 卸载 | `DELETE /system/api/plugin/{pluginId}` | 路由注销、DB 行软删、权限树清理 |
| 重启 | 无需操作 | 已装插件从 blob 自动重挂载，无需重传 |

---

## 9. 常见问题

- **换插件身份 / 想让旧版本失效**：删除 `keys/private.pem` 后重新 `build` 会生成新公钥；请同步把新 `keys/public.pem` 交给管理员更新信任库（旧插件重启后不再重挂载）。
- **`Plugin not trusted`**：公钥未上传到信任库，或已被禁用；用 `POST /system/api/plugin/key` 加入并启用。
- **`Version code N is not greater than current M`**：同 pluginId 已存在更高版本；升级需增大 `apiPlugin.versionCode`。
- **`./gradlew jar` 得到的 jar 没有签名**：签名只在 `build` / `signPlugin` 里做，请用 `./gradlew build` 产出物。
- **`verifyPlugin` 失败**：jar 内公钥与签名私钥不配对（例如误删了其中一个文件）——删除整个 `keys/` 重新 `build`。
- **重装后设置写入报唯一键冲突**：请确保网关已升级到包含 `t_s_plugin_setting unique(plugin_id, setting_key, deleted)` 修复的版本。
- **升级后接口消失**：新 jar 里必须仍含全部需要保留的 `@ApiController`；卸载旧版后只保留新 jar 声明的接口。

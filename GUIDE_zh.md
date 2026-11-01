# 插件开发指南

本指南面向**插件开发者**。一个 API 作为**插件 jar** 独立开发、打包并签名，上传到网关后**热插拔**（安装 / 禁用 / 升级 / 卸载），重启后自动重新挂载。本仓库的 `echo/` 与 `filebox/` 是完整可运行的示例，建议对照阅读。

**写一个插件只需一个 `build.gradle.kts`（约 10 行）+ 你的 Kotlin 源码。** 签名、密钥、描述符、依赖 lock 与 OpenAPI 元数据都由 Gradle 插件 `top.fatweb.api-plugin` 处理。

- 契约 artifact：`top.fatweb:api-management-gateway-api`（Gradle 插件自动添加）
- Gradle 插件：`top.fatweb.api-plugin`
- API 包：`top.fatweb.apimanagement.api.v1`
- 网关 / 控制台：[api-management](https://github.com/FatttSnake/api-management) · [api-management-console](https://github.com/FatttSnake/api-management-console)

---

## 0. 前置：发布契约与 Gradle 插件

Gradle 插件与契约都从**网关仓库**构建发布。首次使用前，在 [api-management](https://github.com/FatttSnake/api-management) 仓库执行一次：

```shell
./gradlew :gateway-api:publishToMavenLocal :plugin-signing:publishToMavenLocal :plugin-gradle-plugin:publishToMavenLocal
```

插件工程在 `settings.gradle.kts` 的 `pluginManagement` 里加入 `mavenLocal()` 即可解析；使用私有 Nexus 时同样加上对应仓库。

拉取新版网关后重新执行一次，确保 Gradle 插件及其 task 是最新的。

---

## 1. 整体模型

```
插件工程（独立 Gradle 构建）                        网关（api-management）
┌──────────────────────────────────────┐        ┌──────────────────────────────────────────┐
│ Kotlin 源码（ApiPlugin 入口）         │ 上传   │ PluginSigner.verify（完整性）             │
│ build.gradle.kts 声明 apiPlugin      │──────▶│ 信任库校验（keyId 存在且启用）             │
│                                      │        │ PluginClassLoader + 模块 loader           │
│ 构建期：                              │        │ ApiPlugin.onStart → 注册路由              │
│  ├ api-plugin.json                   │        │ 路由表 + ApiAccessInterceptor             │
│  ├ api-plugin-deps.json（lock）       │        │ DB upsert（插件/接口/权限树）             │
│  ├ 模块 jar                          │        │ 启动时从已存 jar 重新挂载                  │
│  ├ plugin.pub.pem                    │        └──────────────────────────────────────────┘
│  └ Ed25519 签名                      │
└──────────────────────────────────────┘
```

插件 jar 是瘦包：运行时依赖是 lock 中记录的独立模块 jar。网关按内容寻址存储模块、校验 SHA-256，并为每个装载单元提供一个类加载器。

### 安全边界（务必理解）

插件代码运行在**网关 JVM 内**，任何数据隔离都阻止不了恶意代码。**真正的边界是强制 Ed25519 签名 + 管理员维护的信任库**；数据隔离（插件自有数据源、收窄的 `GatewayContext`）只是对误操作的纵深防御。

- **私钥就是身份根**——持有者可以冒名发布插件。它只存在于 `keys/private.pem`，**绝不提交、绝不泄露**。
- 把 `keys/public.pem` 交给网关管理员；不受信任或已禁用的公钥会在安装时被拒绝。
- **删掉 `keys/private.pem` 重新构建会更换身份**：已安装插件在下次重启后不会重新挂载，因为签名者不再受信任。

---

## 2. 脚手架：最小插件工程

新建目录（如 `geo/`），放三个文件 + 源码：

```
geo/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ keys/            # 首次构建自动生成（private.pem 勿提交）
└─ src/main/kotlin/com/example/geo/GeoPlugin.kt
```

### 2.1 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()   // 解析 top.fatweb.api-plugin 与 gateway-api snapshot
    }
}

rootProject.name = "geo-plugin"
```

### 2.2 `build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}

version = "1.0.0"                                 // 也是默认 versionName

apiPlugin {
    pluginId = "geo"                              // ^[a-z][a-z0-9-]{0,63}$
    pluginName = "Geo 插件"
    versionCode = 1                               // 必填；更高为升级，相同则原地重新部署
    description = "把地理编码开放成 API"             // 可选
    author = "你的名字"                             // 可选
    mainClass = "com.example.geo.GeoPlugin"       // 必填：ApiPlugin 实现
    requiresGateway = ">=1.0.0 <2.0.0"            // 默认值
    requiresGatewayApi = ">=1.0.0-SNAPSHOT <2.0.0" // 默认 ^1.0
}
```

| DSL 字段 | 必填 | 默认 | 描述符字段 |
|---|---|---|---|
| `pluginId` | | 工程名 | `pluginId` |
| `pluginName` | | 同 `pluginId` | `name` |
| `versionName` | | 工程 `version` | `versionName` |
| `versionCode` | ✅ | — | `versionCode` |
| `description` / `author` | | 空 | 同名字段 |
| `mainClass` | ✅ | — | `mainClass` |
| `requiresGateway` | | `>=1.0.0 <2.0.0` | `requiresGateway` |
| `requiresGatewayApi` | | `^1.0` | `requiresGatewayApi`；其最小 major 也是编译期 gateway-api 版本 |
| `archiveName` | | `<pluginId>-<versionName>.jar` | jar 文件名 |
| `sharedLoader(vararg group:artifact)` | | 无 | lock `groups` |

### 2.3 `.gitignore`

```gitignore
keys/            # 私钥绝不提交
build/
.gradle/
```

### 2.4 构建 task

```shell
./gradlew build           # 密钥 + 描述符 + lock + 模块 + pub.pem + 签名
./gradlew verifyPlugin    # 自检 jar 签名
./gradlew deployPlugin    # 构建 + 签名 + 验签 + 上传模块与 jar
```

产物：`build/libs/<pluginId>-<versionName>.jar`（已签名）。依赖模块位于
`build/generated/api-plugin-modules/`。

插件提供 `generatePluginLock`、`generatePluginDescriptor`、`genPluginKeys`、
`preparePluginPubKey`、`checkPluginAbi`、`signPlugin`、`verifyPlugin`、`deployPlugin`。
`generatePluginLock`、`generatePluginDescriptor`、`preparePluginPubKey` 接入 `jar`；
`checkPluginAbi` 接入 `check`，`signPlugin` 接入 `build`。

`deployPlugin` 相当于 §7 的上传步骤，不用打开浏览器：

```shell
./gradlew deployPlugin -PapiPlugin.serverUrl=http://localhost:8080 -PapiPlugin.accessToken=<token>
```

两项也读取环境变量（`API_PLUGIN_SERVER_URL`、`API_PLUGIN_ACCESS_TOKEN`），都不应写进
`build.gradle.kts`。token 是控制台登录返回的 `accessToken`。加
`-PapiPlugin.trustSigner=true` 可在同一次运行里注册签名公钥。

想在调试器里单步插件代码，见网关仓库的
[docs/plugin-debugging_zh.md](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-debugging_zh.md)。

### 2.5 手动方式（不用 Gradle 插件）

推荐使用 Gradle 插件：它生成描述符与 lock、接入 jar 并完成签名。手工构建必须自行维护
`META-INF/api-plugin.json`、`META-INF/api-plugin-deps.json`、`META-INF/plugin.pub.pem`、
`META-INF/plugin.sig` 以及模块 jar，不在本指南范围内。

---

## 3. 描述符

Gradle 插件在构建期从 `apiPlugin { }` 生成。`echo/` 的产物形如：

```json
{
  "format": 1,
  "pluginId": "echo",
  "name": "Echo 插件",
  "versionName": "1.0.0",
  "versionCode": 1,
  "description": "Echo 插件，演示 API 插件热插拔全流程（安装/升级/卸载/禁用/签名/文档）",
  "author": "FatttSnake",
  "mainClass": "com.example.echo.EchoPlugin",
  "requiresGateway": ">=1.0.0 <2.0.0",
  "requiresGatewayApi": ">=1.0.0-SNAPSHOT <2.0.0"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `format` | ✅ | 描述符格式，当前为 `1` |
| `pluginId` | ✅ | 唯一 ID，`^[a-z][a-z0-9-]{0,63}$`；所有接口以 `/api/{plugin}/v{version}/…` 暴露 |
| `name` | ✅ | 展示名（菜单 / 权限树 / 文档） |
| `versionName` | ✅ | 人类可读版本，如 `1.2.0` |
| `versionCode` | ✅ | 整数，至少 1。**高于**已安装值即升级；重传**已安装的版本**原地替换；**更低**的版本被拒绝 |
| `mainClass` | ✅ | `ApiPlugin` 实现的 FQCN |
| `requiresGateway` / `requiresGatewayApi` | ✅ | 与运行中网关校验的版本范围 |
| `description` / `author` | | 元数据 |

---

## 4. 签名与信任库

### 4.1 密钥（自动生成）

`./gradlew build`（或 `genPluginKeys`）首次运行会在 `keys/` 生成 Ed25519 密钥对：

- `keys/private.pem`（PKCS8）——仅用于本地签名，**绝不提交 / 泄露**
- `keys/public.pem`（SPKI）——交给网关管理员

公钥会嵌入 jar 的 `META-INF/plugin.pub.pem`，`META-INF/plugin.sig` 由 `signPlugin` 写入。

> **更换身份**：删掉 `keys/private.pem` 后重新 `build` 即生成新密钥对。

### 4.2 本地自检

```shell
./gradlew verifyPlugin
# 期望输出：Signature valid: echo-1.0.0.jar
```

### 4.3 管理员把公钥加入信任库

```
POST /system/api/plugin/key
{ "publicKey": "<keys/public.pem 的内容>", "alias": "你的名字" }
```

网关从公钥推导 `keyId`（SPKI 的 SHA-256）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/system/api/plugin/key` | 列表 |
| POST | `/system/api/plugin/key` | 新增公钥 |
| PATCH | `/system/api/plugin/key` | 启用 / 禁用 |
| DELETE | `/system/api/plugin/key/{keyId}` | 删除 |

> 吊销：管理员删除或禁用公钥后，已安装插件在下次重启时**不会重新挂载**；数据库行保留，`loadError` 记录原因。

---

## 5. 编程模型

插件不使用 Spring MVC 注解，也没有 Spring 子容器。实现 `ApiPlugin`，在 `onStart` 收到的
`GatewayContext` 上注册路由：

```kotlin
class GeoPlugin : ApiPlugin {
    override fun onStart(context: GatewayContext) {
        context.api().register(
            RouteDefinition("GET", "/geocode", 1, OperationMetadata("geocode", "地理编码"))
        ) { request ->
            ApiResponse.ok(mapOf("address" to request.queryParam("address")))
        }
    }
}
```

### 5.1 路由与版本

- 路径相对于 `/api/{pluginId}/v{apiVersion}`，不能包含 query 或 fragment。
- `apiVersion` 至少为 1。请求可以选择**更高**版本；网关取不超过请求版本的最高已声明版本。
- 同一路径可以在不同版本下声明：`echo/` 在一个插件里提供 v1 的 `ping` / `version` 与 v2 的
  `ping` / `whoami`。
- `OperationMetadata.operationId` 是接口的稳定身份，定义权限码
  `api:{plugin}:v{version}:{operationId}`，不能为空。
- 路由在 `onStart` 中暂存，方法正常返回后才激活；`onStart` 失败不会发布任何路由。

### 5.2 请求与响应

`ApiRequest` 提供 `method`、`path`、`apiVersion`、`principal`、`traceId`、`interfaceInfo`、
`body`（原始字节）、`pathParam`、`queryParam` / `queryParams`、`header` 与 `bodyText()`。

`ApiResponse.ok(data)` 用标准信封 `{code, success, msg, data}` 包装 payload；
`ApiResponse.fail(code, message, data)` 返回业务错误；`ApiResponse.raw(status, contentType,
bytes)` 绕过信封（用于下载），`ApiResponse.text(...)` 返回纯文本；`withHeader(...)` 与
`withHttpStatus(...)` 调整响应。

建议错误码区间：`1000-1999` 客户端/参数，`2000-2999` 业务失败，`5000+` 服务端错误。
插件相关的网关错误在 `4xxxx`（`40063` 安装失败、`40064` 签名者不受信任、`40065` 签名无效、
`40066` 版本冲突、`40067` 数据源无效、`40068` purge 失败）——不要在该区间定义自己的业务码。

### 5.3 OpenAPI 元数据

不需要手写 OpenAPI 片段。每次挂载时，网关根据路由的 `OperationMetadata` 生成 OpenAPI 3.1
片段并写入插件行；用户通过 `GET /user/api/docs/{pluginId}` 获取。

声明接口接受与返回的内容：

```kotlin
OperationMetadata(
    operationId = "geocode",
    summary = "地理编码",
    description = "把地址解析为坐标",
    parameters = listOf(
        ParameterSpec.query("address", description = "待解析地址", example = "上海"),
    ),
    requestBodySchema = null,
    responses = mapOf(
        "200" to ResponseSpec(
            description = "成功",
            schema = SchemaSpec.objectOf(
                properties = mapOf(
                    "address" to SchemaSpec.string("格式化地址"),
                    "latitude" to SchemaSpec.number("纬度", nullable = true),
                    "longitude" to SchemaSpec.number("经度", nullable = true),
                ),
                required = listOf("address"),
            ),
            example = mapOf("address" to "上海", "latitude" to 31.23, "longitude" to 121.47),
        )
    ),
)
```

`ParameterSpec` 覆盖 query / path / header / cookie 参数；`SchemaSpec` 提供 object、array、
string、integer、number、boolean 工厂。生成的 `paths` key 是完整公开路径
`/api/{plugin}/v{version}/...`。

### 5.4 生命周期

```kotlin
class GeoPlugin : ApiPlugin {
    override fun onStart(context: GatewayContext) {}   // 每次挂载；在这里注册路由
    override fun onStop() {}                           // 每次卸载，在途请求排空之后
    override fun onUninstall() {}                      // 当前版本即将离开（升级也会触发）
    override fun onPurge() {}                          // 管理员要求删除数据的卸载
}
```

- `onStart` 在安装、启动恢复、`reload`、数据源变更导致的重挂载、以及失败挂载后的任意保存时执行。
  不要把关键状态只放在内存里。
- `onStop` 在在途请求排空后执行；路由、插件事件与调度会先被移除。
- `onUninstall` 在声明版本变化或插件被移除时执行，升级也会执行。**不要在这里删数据**：每次发布升级都会跑。
- `onPurge` 仅在管理员请求 `purgeData=true` 时执行，且在 `onStop` **之前**，此时数据源、存储和设置仍然可用。
  抛异常会拒绝卸载（40068），什么都不会删除。
- 被禁用的插件不会在下次启动时加载；重新启用会用同一个 jar 覆盖原有数据重新挂载。

### 5.5 `GatewayContext`

`GatewayContext` 是插件能接触到的唯一网关面，不会暴露 Spring 容器、Bean、Mapper 或网关 ObjectMapper。

| 访问器 | 说明 |
|---|---|
| `api()` | 路由注册，仅在 `onStart` 期间有效 |
| `settings()` | 插件级设置：`get`、`getSecret`、`save`、`all` |
| `dataSources()` | 插件自有数据源：`names()`、`all()`、`get(name)` |
| `storage()` | 插件文件区：内容寻址与位置寻址对象 |
| `users()` / `audit()` / `events()` / `http()` / `scheduler()` | 插件作用域的平台能力 |
| `json()` / `logger()` / `metadata()` | JSON 编解码、插件日志、描述符与版本元数据 |

### 5.6 设置与配置

插件在 `META-INF/plugin-config.json` 里声明**哪些设置归管理员所有**。网关在挂载时读取、
快照到插件行，控制台据此渲染表单；插件通过 `context.settings()` 读取，**配置变化不需要改代码**。

```json
{
  "datasources": [
    { "name": "main", "dbType": "mysql", "required": true,
      "host": "db.host", "port": "db.port", "database": "db.name",
      "username": "db.user", "password": "db.password", "params": "db.params" },
    { "name": "cache", "dbType": "sqlite" }
  ],
  "groups": [
    { "key": "db", "title": "数据库", "fields": [
        { "key": "db.host", "type": "string", "title": "主机" },
        { "key": "db.port", "type": "number", "title": "端口", "default": 3306, "minimum": 1, "maximum": 65535, "integer": true },
        { "key": "db.name", "type": "string", "title": "库名" },
        { "key": "db.user", "type": "string", "title": "用户名" },
        { "key": "db.password", "type": "secret", "title": "密码" },
        { "key": "db.params", "type": "string", "title": "驱动参数" }
    ] }
  ]
}
```

- 字段类型：`string` / `text` / `number` / `boolean` / `enum` / `secret`。线上与存储中的值都是字符串。
- `default` 只声明一次、不落库；`settings().get(key)` 在未设置时回落到它。
- 声明的 key 归管理员所有：`settings().save(...)` 会拒绝它。未声明的 key 归插件自己（如 `startedAt`）。
- secret 加密存储，管理接口永不回传明文：`value` 为 `null`，`hasValue` 表示是否已设置。保存时不传该 key
  表示保持原值，传空表示清除；`settings().getSecret(key)` 读回明文。
- MySQL 数据源在 **host 与 database 都有值**后才算配置完成；任一留空即取消配置，不算错误。
  SQLite 数据源不需要任何配置。没有 `datasources` 声明就不会注入任何数据源。
- 普通值在下一次读取时生效，无需重挂载；保存数据源连接项会**重挂载**插件，因为连接由网关构建。
- [`docs/plugin-config.schema.json`](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-config.schema.json)
  镜像了这些校验，可用于编辑器与 CI。不要在文件里加 `$schema`：网关只接受顶层 `groups` 与 `datasources`。

### 5.7 文件存储与外链

`context.storage()` 提供两种不可互换的寻址方式：

| | 内容寻址 | 位置寻址 |
|---|---|---|
| 方法 | `saveContent` / `loadContent` / `existsContent` / `deleteContent` | `saveFile` / `loadFile` / `existsFile` / `deleteFile` / `fileExternalUrl` |
| 键 | 内容 SHA-256（也是返回值） | 插件自行选择的相对路径 |
| 存储 | zstd 压缩，相同内容只存一份 | 原样字节 |
| 外链 | 不支持 | 支持，`fileExternalUrl(path, ttl)` |
| 适用 | 归档、去重、仅插件自己读的对象 | 需要下载或浏览器打开的文件 |

```kotlin
val path = "files/$ownerId/$id.png"
context.storage().saveFile(path, bytes)
val url = context.storage().fileExternalUrl(path, Duration.ofHours(2))

val key = context.storage().saveContent(bytes)   // 返回 SHA-256
context.storage().deleteContent(key)             // 每次 save 对应一次 delete
```

- **引用计数**：每次 `saveContent` 增加引用，必须恰好对应一次 `deleteContent`。
- **路径规则**：`/` 分隔的相对路径，每段 `^[A-Za-z0-9][A-Za-z0-9._-]*$`，每段最多 200 字符、总长 1024；
  不能是 `.` / `..`、Windows 设备名，不能含 `\` 或 `:`。**不要把用户文件名拼进路径**。
- **命名空间**：所有路径落在 `data/files/plugin-data/{pluginId}/` 下，无法访问其他插件或网关文件。
- **外链语义**：免登 bearer 凭证，过期前无法吊销；删除文件会立即失效。超过配置上限的 ttl 会被钳制，
  零或负数会被拒绝。

### 5.8 依赖与共享 loader 组

插件 jar 是瘦包。运行时依赖在构建期锁定，并以独立模块 jar 上传。网关**只提供** JDK、Kotlin stdlib
与 `gateway-api`；Spring、Jackson、MyBatis、Hikari、Flyway、JDBC 驱动等都是插件的普通依赖。

默认一个模块就是一个装载单元，只能看到自己声明的依赖。有些库不符合这个模型：Flyway 通过绑定在
“定义 `flyway-core` 的 loader”上的静态 `ServiceLoader` 注册表发现数据库类型；MyBatis-Plus 会引用
POM 里标成 optional 的 MyBatis 与 SLF4J。把它们声明成**共享 loader 组**：

```kotlin
apiPlugin {
    sharedLoader("org.flywaydb:flyway-core", "org.flywaydb:flyway-mysql")
}
```

DSL 只接受 `group:artifact`（不带版本，至少两个），版本来自实际解析结果。只把必须互相可见的模块放一组；
不要把包含同一批类的模块放一组（按 digest 排序靠前的 jar 生效）；分组不能造成装载单元环。
组是共享、引用计数与卸载的单位。

lock 中记录但网关没有的模块，会在 `app.plugin.module.online` 为真时从
`app.plugin.module.repositories` 下载，只有 SHA-256 与大小都与 lock 一致才接受。`deployPlugin`
会先上传模块，因此离线部署也能用。

---

## 6. 描述符、lock 与 jar 条目

```
META-INF/api-plugin.json        描述符 format 1
META-INF/api-plugin-deps.json   依赖 lock：坐标、digest、大小、直接依赖边、组
META-INF/plugin.pub.pem         签名公钥
META-INF/plugin.sig             Ed25519 签名
```

lock 会排除平台提供的 `top.fatweb:api-management-gateway-api`、`org.jetbrains.kotlin:kotlin-stdlib*`
与 `org.jetbrains:annotations`；其余依赖都会成为模块。

---

## 7. 构建 → 上传 → 配置 → 调用

以 `echo/` 为例：

```shell
cd echo
./gradlew build
# 产物：build/libs/echo-1.0.0.jar（已签名）；公钥在 keys/public.pem

# 1. 管理员信任公钥（首次）
POST /system/api/plugin/key   {"publicKey": "<...>", "alias": "FatttSnake"}

# 2. 上传安装
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar)
# 或：./gradlew deployPlugin -PapiPlugin.serverUrl=… -PapiPlugin.accessToken=…

# 3. 给用户授权
GET  /user/api/key/available-apis
POST /user/api/key   {"permissionCodes": ["api:echo:v1:ping", "api:echo:v1:version",
                                         "api:echo:v2:ping", "api:echo:v2:whoami"]}

# 4. 调用（Basic 认证 accessKey:secretKey）
GET /api/echo/v1/ping
GET /api/echo/v1/version
GET /api/echo/v2/ping
GET /api/echo/v2/whoami
```

以 `filebox/` 为例：

```shell
cd filebox
./gradlew build

# cache（SQLite）数据源装完即用；main（MySQL）需要配置。
GET  /system/api/plugin/filebox/config
# -> datasources: [ {name:"main", dbType:"MYSQL", required:true, configured:false, keys:[...]},
#                   {name:"cache", dbType:"SQLITE", required:false, configured:true, keys:[]} ]

# 保存按分组写，没传的键等于不做决定；保存数据源值后网关自动重挂载插件。
PUT  /system/api/plugin/config
     {"pluginId":"filebox","groups":[{"key":"db","values":[
        {"key":"db.host","value":"10.0.0.240"},
        {"key":"db.port","value":"3306"},
        {"key":"db.name","value":"filebox"},
        {"key":"db.user","value":"filebox"},
        {"key":"db.password","value":"…"},
        {"key":"db.params","value":"useSSL=false&serverTimezone=UTC"}]}]}

# 可选：用尚未保存的值试连
POST /system/api/plugin/filebox/config/datasource/test
     {"name":"main","values":[{"key":"db.host","value":"10.0.0.240"},
                              {"key":"db.name","value":"filebox"}]}

# 然后授权并调用
POST /user/api/key   {"permissionCodes": ["api:filebox:v1:upload", "api:filebox:v1:list", ...]}
POST /api/filebox/v1/upload?fileName=report.pdf     (body：原始字节)
GET  /api/filebox/v1/files?page=1&size=20
```

示例响应（`/api/echo/v2/ping`）：

```json
{ "code": 0, "success": true, "msg": "success", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

---

## 8. 管理操作速查

| 操作 | 方式 | 说明 |
|---|---|---|
| 启用 / 禁用 | `PATCH /system/api/plugin` | `{id, enable}`。禁用会停掉运行时：注销路由、下次启动不加载、接口返回 `Api disabled`。配置、数据源、文件与授权保留；重新启用会用同一个 jar 挂载 |
| 读配置 | `GET /system/api/plugin/{pluginId}/config` | 声明 schema 与当前值：每个字段 `value`、`default`、`hasValue`、`unreadable`，secret 的 `value` 恒为 `null`；以及每个数据源的 `name`、`dbType`、`required`、`configured` |
| 保存配置 | `PUT /system/api/plugin/config` | 按分组：`{pluginId, groups:[{key, values:[{key,value}]}]}`。普通值立刻生效；没传的键保持原值；传空即清除。改动数据源值会**重挂载**插件 |
| 试连 | `POST /system/api/plugin/{pluginId}/config/datasource/test` | `{name, values}`；仅 MySQL。真实失败返回 40067 与驱动原因 |
| reload | `POST /system/api/plugin/{pluginId}/reload` | 原地重挂载已安装 jar，保留版本、数据行、配置与数据。用于替换过 jar 的场景；数据源变更不需要它 |
| 升级 | 调大 `apiPlugin.versionCode`，重新构建上传 | 旧版本原地替换；只有更低版本被拒绝（40066）。管理员配置、授权、数据源与文件都保留；被禁用的插件不会启动 |
| 卸载 | `DELETE /system/api/plugin/{pluginId}?purgeData=false` | 注销路由、删除插件行与权限树。`purgeData=false` 保留设置、SQLite 数据与文件；`true` 清除它们。外部 MySQL 库网关永不删除——那是 `onPurge` 的职责。停用的插件会为退出钩子最后挂载一次；`onPurge` 抛异常会拒绝卸载（40068） |
| 重启 | 无需操作 | 已安装插件会从已存 jar 自动重新挂载 |

---

## 9. FAQ

- **`Plugin signer is not trusted`**：公钥不在信任库，或已被禁用。通过 `POST /system/api/plugin/key` 添加并启用。完全没有签名的 jar 会报 `Plugin signature invalid`——请上传 `build` 的产物，而不是 `./gradlew jar` 的产物。
- **`Version code N is older than the installed M`**：调大 `apiPlugin.versionCode`；重传已安装的版本是允许的。
- **`./gradlew jar` 的产物没签名**：签名在 `build` / `signPlugin` 中执行，请使用 `build` 产物。
- **`verifyPlugin` 失败**：jar 内公钥与 `keys/private.pem` 不配对。删除 `keys/` 重新构建。
- **升级后接口消失**：新 jar 必须仍然注册你想保留的每个路由；旧版本的路由不会残留。
- **`onStart` 没执行 / 协作者没被创建**：插件没有依赖注入或 Spring 容器。在 `onStart` 里自行构造协作者，或作为插件实例的普通对象持有。
- **接口 404**：检查请求版本是否不低于路由声明的版本。被禁用插件的接口返回 `Api disabled`，不是资源不存在。
- **插件已禁用但还在做事**：禁用会停掉路由、调度与事件。由外部方驱动的工作（例如在别处注册的 webhook）网关无法停止。
- **`NoClassDefFoundError`**：模块不在 lock 中，或需要互相可见的模块没有放进 `sharedLoader` 组。修改依赖后重新构建。

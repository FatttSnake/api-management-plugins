<div align="center">
    <h1>
        <img alt="API Management Plugins" src="docs/logo.svg" width="140">
        <br>
        <span>API Management Plugins</span>
    </h1>
</div>
<div align="center">
    <b>API Management 网关的插件工作区 —— 独立开发、打包并签名可热插拔的 API 插件</b>
</div>

# 概述 ([EN](README.md), 简体中文)

[API Management](https://github.com/FatttSnake/api-management) 平台的全部「可用 API」都以**插件**形式提供：插件以 **Ed25519 签名**的 jar 上传到网关，在**隔离的类加载器 / 子容器**中**热插拔**（安装 / 禁用 / 升级 / 卸载，无需重启），声明接口统一暴露为 `/api/{plugin}/v{version}/...`，由平台统一负责身份认证、访问控制、频控限流、计量计费与审计。

本仓库是插件的**开发工作区**：**根目录下每个子目录是一个相互独立的 Gradle 插件工程**，各自的打包与签名完全独立。仓库内置 `echo/` —— 一个可直接运行的完整示例插件。

**写一个新插件的配置如今只有一个 `build.gradle.kts`（约 10 行）+ 你的 Kotlin 源码**：SDK 依赖、密钥生成、`api-plugin.json` 描述符、公钥嵌入与 Ed25519 签名，全部由 Gradle 插件 `top.fatweb.api-plugin` 在 `build` 期自动完成。

# 仓库结构

```
api-management-plugins/
├─ echo/                        # 完整示例插件（独立 Gradle 工程）
│  ├─ build.gradle.kts          # kotlin jvm + id("top.fatweb.api-plugin") + apiPlugin{...}
│  ├─ settings.gradle.kts       # pluginManagement 含 mavenLocal（解析 Gradle 插件/SDK）
│  ├─ keys/                     # 首次 build 自动生成（private.pem 勿提交）
│  └─ src/main/
│     ├─ kotlin/com/example/echo/   # EchoController(v1) / EchoControllerV2(v2) / EchoService / EchoLifecycle
│     └─ resources/META-INF/
│        └─ plugin-openapi.json     # OpenAPI 文档片段（可选；描述符/公钥/签名由插件生成）
├─ filebox/                     # 数据与文件示例插件（独立 Gradle 工程）
│  ├─ build.gradle.kts          # 同上（pluginId = filebox）
│  ├─ settings.gradle.kts       # rootProject.name = filebox-plugin
│  ├─ keys/                     # 首次 build 自动生成
│  └─ src/main/
│     ├─ kotlin/com/example/filebox/  # FileboxController / FileboxService / FileboxRepository
│     │                               # / FileboxSchema / FileboxLifecycle
│     └─ resources/META-INF/
│        ├─ plugin-config.json      # 配置声明：普通项 / 布尔开关 / number / secret / 数据源声明 ×2
│        └─ plugin-openapi.json     # OpenAPI 文档片段
├─ docs/logo.svg                # 项目 Logo
├─ GUIDE.md                     # 插件开发指南（EN）
├─ GUIDE_zh.md                  # 插件开发指南（简体中文）
├─ LICENSE                      # GPL-3.0
└─ README.md / README_zh.md     # 本文件
```

> 其余插件工程以后平级放在仓库根目录，每个都是独立的 Gradle 构建。

# 示例插件 `echo/`

一个「回声」插件，演示了插件的完整能力：

| 演示点 | 实现 |
|---|---|
| 控制器 / 版本共存 | `EchoController`(v1)、`EchoControllerV2`(v2)——同一插件内滚动兼容 |
| 业务 bean 注入 | `EchoService`（构造函数注入） |
| 生命周期钩子 | `EchoLifecycle`（`installedAt`/`startedAt`/`stoppedAt`/`uninstalledAt` 写入插件级设置） |
| 描述符 / 文档 | `apiPlugin {}` 生成 `api-plugin.json` + 手写 `plugin-openapi.json` |
| 密钥 / 签名 | `genPluginKeys` 自动生成 + `signPlugin` 构建期签名 |

安装并授权后可调用（Basic 认证 `accessKey:secretKey`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/echo/v1/ping` | v1 回声：`message` + `userId` |
| GET | `/api/echo/v1/version` | v1 版本信息与接口列表 |
| GET | `/api/echo/v2/ping` | 从 v2 起覆盖 v1 的 `ping`（滚动兼容） |
| GET | `/api/echo/v2/whoami` | 当前调用者：`userId` + `accessKeyId` |

示例响应（`/api/echo/v2/ping`）：

```json
{ "code": 0, "success": true, "msg": "success", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

# 示例插件 `filebox/`

一个「文件盒」插件：文件存在**插件自己的独立数据源**里（元数据）与**插件自己的文件区**里（字节），演示配置声明、一个插件多个数据源、以及两种文件寻址方式。

| 演示点 | 实现 |
|---|---|
| 配置声明 | `META-INF/plugin-config.json`：number / boolean / text / secret 四种类型 + 默认值回落 + 两条数据源声明 |
| 一个插件两个数据源 | 文件行进 `main`——MySQL，由管理员用普通配置项描述；快照进 `cache`——SQLite，网关**零配置**供给（`data/db/plugin/filebox/cache.db`）。装完 `context.datasources["cache"]` 即可用，`["main"]` 等 host 填上就有；DDL 跨 MySQL / SQLite 可移植 |
| 连接改完自动生效 | 填上 `main` 的配置后网关**自动重挂载**插件（连接是它构建的），**没有一步 reload**；保存前还能先试连（详见 [GUIDE_zh.md](GUIDE_zh.md) §6.3、§8） |
| 位置寻址 + 免登外链 | `saveFile` / `loadFile` / `fileExternalUrl`：上传、下载、外链、删除 |
| 内容寻址 + 去重 | `saveContent` / `loadContent` / `existsContent`：同一段文本只存一份，响应里的 `deduplicated` 表示命中了已有对象 |
| 配置即时生效 | 关掉 `allowSnapshot` 后快照接口立刻返回未启用，**无需重新挂载** |
| 生命周期 | `FileboxLifecycle` 在 `onInstall` / `onStart` 建表并写运行态设置 |

安装、配置后调用（Basic 认证 `accessKey:secretKey`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/filebox/v1/upload` | multipart 上传，返回文件 ID 与存储路径 |
| GET | `/api/filebox/v1/files` | 当前调用者的文件列表 |
| GET | `/api/filebox/v1/files/{id}` | 下载原样字节（透传，不带响应信封） |
| GET | `/api/filebox/v1/files/{id}/link` | 生成免登外链，`ttlHours` 缺省时取配置项 `defaultLinkTtlHours` |
| DELETE | `/api/filebox/v1/files/{id}` | 删除元数据与字节，已发外链随之失效 |
| POST | `/api/filebox/v1/snapshots` | 存文本快照（内容寻址），相同内容只存一份 |
| GET | `/api/filebox/v1/snapshots/{contentKey}` | 按 SHA-256 读回文本 |

快照那条数据源**装完即用**，什么都不用配。文件那条需要一个 MySQL 库，用配置接口填一次即生效（详见 [GUIDE_zh.md](GUIDE_zh.md) §6.3、§8）：

```shell
GET  /system/api/plugin/filebox/config
# datasources: [ {name:"main", dbType:"MYSQL", required:true, configured:false, keys:[db.host, ...]},
#               {name:"cache", dbType:"SQLITE", required:false, configured:true, keys:[]} ]

# 先试连，再保存——保存后无需任何 reload。保存按分组写，没传的键等于不做决定
POST /system/api/plugin/filebox/config/datasource/test
     {"name":"main","values":[{"key":"db.host","value":"10.0.0.240"},{"key":"db.name","value":"filebox"}]}
PUT  /system/api/plugin/config
     {"pluginId":"filebox","groups":[{"key":"db","values":[
        {"key":"db.host","value":"10.0.0.240"},{"key":"db.name","value":"filebox"},
        {"key":"db.user","value":"filebox"},{"key":"db.password","value":"…"}]}]}
```

# 快速开始

### 0. 让 Gradle 插件 / SDK 可用（一次性）

在 [api-management](https://github.com/FatttSnake/api-management)（网关）仓库执行一次：

```shell
./gradlew :plugin-sdk:publishToMavenLocal :plugin-gradle-plugin:publishToMavenLocal
```

### 1. 构建示例插件（自动生成密钥、描述符并签名）

```shell
cd echo
./gradlew build
# 产物：echo/build/libs/echo-1.0.0.jar（已签名），公钥在 echo/keys/public.pem
./gradlew verifyPlugin     # 可选：自检签名
```

### 2. 上传并调用（面向网关管理员/使用方）

```shell
# 1. 管理员把公钥加入信任库（内容来自 echo/keys/public.pem）
POST /system/api/plugin/key   { "publicKey": "<...>", "alias": "你的名字" }

# 2. 上传安装
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar，最大 50 MB)

# 3. 授权给 API 账户
POST /user/api/key   { "permissionCodes": ["api:echo:v1:ping", "api:echo:v1:version", "api:echo:v2:ping", "api:echo:v2:whoami"] }

# 4. 调用
curl -u "<accessKey>:<secretKey>" http://<gateway>/api/echo/v2/ping
```

**升级**：在 `build.gradle.kts` 里调大 `apiPlugin.versionCode` 后重新构建上传即可。更多操作见 [GUIDE_zh.md](GUIDE_zh.md) §7-§8。

# 一键创建你自己的插件

新建一个目录（如 `geo/`），放下面两个文件 + 你的 Kotlin 源码，然后 `./gradlew build` 即可得到签名 jar：

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal(); mavenLocal() }
}
rootProject.name = "geo-plugin"
```

```kotlin
// build.gradle.kts —— 全部构建配置
plugins {
    kotlin("jvm") version "2.3.21"
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}
version = "1.0.0"

apiPlugin {
    pluginId = "geo"              // 唯一 ID，须与 @ApiController.plugin 一致
    pluginName = "Geo 插件"
    versionCode = 1               // 必填；驱动网关升级判定，发版须递增
    description = "把地理编码开放成 API"   // 可选
    author = "你的名字"                    // 可选
    mainClass = "com.example.geo.GeoLifecycle"  // 有生命周期时可选
}
```

```kotlin
// src/main/kotlin/com/example/geo/GeoController.kt —— 你的控制器
@ApiController(plugin = "geo", version = 1)   // 接口级 name/description 取自各方法的 @Operation
class GeoController(private val pluginContext: PluginContext) {
    @GetMapping("/geocode")
    fun geocode(): ApiResponse<Map<String, Any?>> = ApiResponse.ok(mapOf("ok" to true))
}
```

构建即自动：生成 `keys/`、生成 `META-INF/api-plugin.json`、嵌入 `plugin.pub.pem`、按 `<pluginId>-<versionName>.jar` 命名并 Ed25519 签名。

完整的 DSL 字段与手动（不用 Gradle 插件）写法见 [GUIDE_zh.md](GUIDE_zh.md) / [GUIDE.md](GUIDE.md)。

# 环境要求

- **JDK 25+**
- `top.fatweb.api-plugin` 与 `top.fatweb:api-management-plugin-sdk:1.0.0-SNAPSHOT` 可解析（本地 `mavenLocal` 或私有镜像）
- 首次构建自动下载 Gradle wrapper，无需预装 Gradle

# 关联项目

[API Management](https://github.com/FatttSnake/api-management)

[Web Console](https://github.com/FatttSnake/api-management-console)

# 许可证

[GPL-3.0](LICENSE)

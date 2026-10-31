<div align="center">
    <h1>
        <img alt="API Management Plugins" src="docs/logo.svg" width="140">
        <br>
        <span>API Management Plugins</span>
    </h1>
</div>
<div align="center">
    <b>API Management 网关的插件工作区 —— 开发、打包并签名可热插拔的 API 插件</b>
</div>

# 概述 ([EN](README.md), 简体中文)

[API Management](https://github.com/FatttSnake/api-management) 平台的全部「可用 API」都以**插件**形式提供：插件以 **Ed25519 签名**的 jar 上传到网关并**热插拔**（安装 / 禁用 / 升级 / 卸载，无需重启），声明接口统一暴露为 `/api/{plugin}/v{version}/...`，身份认证、访问控制、频控限流、计量计费与审计都由平台负责。

本仓库是插件的**开发工作区**：**根目录下每个子目录都是相互独立的 Gradle 插件工程**，各自打包与签名。仓库内置 `echo/` 与 `filebox/` 两个可直接运行的完整示例。

**写一个插件只需一个 `build.gradle.kts`（约 10 行）+ 你的 Kotlin 源码。** Gradle 插件 `top.fatweb.api-plugin` 在 `build` 期完成：编译期依赖 `gateway-api`、把运行时依赖解析成**依赖 lock**、把每个依赖模块复制成独立 jar、生成 `META-INF/api-plugin.json`、嵌入公钥并以 Ed25519 签名。插件 jar 本身保持瘦包——不需要 Shadow，也不需要 fat jar。

# 仓库结构

```
api-management-plugins/
├─ echo/                        # 最小示例插件（独立 Gradle 工程）
│  ├─ build.gradle.kts          # kotlin jvm + id("top.fatweb.api-plugin") + apiPlugin{...}
│  ├─ settings.gradle.kts       # pluginManagement 含 mavenLocal（Gradle 插件 / gateway-api）
│  ├─ keys/                     # 首次 build 自动生成（private.pem 勿提交）
│  └─ src/main/kotlin/com/example/echo/
│     ├─ EchoPlugin.kt          # ApiPlugin：v1/v2 路由、元数据、生命周期钩子
│     └─ EchoResponses.kt       # 响应 DTO
├─ filebox/                     # 数据与文件示例插件（独立 Gradle 工程）
│  ├─ build.gradle.kts          # MyBatis-Plus + Flyway + sharedLoader 组
│  ├─ settings.gradle.kts       # rootProject.name = filebox-plugin
│  ├─ keys/                     # 首次 build 自动生成
│  └─ src/main/
│     ├─ kotlin/com/example/filebox/
│     │  ├─ FileboxPlugin.kt    # ApiPlugin 入口
│     │  ├─ FileboxRuntime.kt   # 单次挂载的组合根
│     │  ├─ api/                # 路由与响应 DTO
│     │  ├─ config/             # 类型化配置读取
│     │  ├─ db/                 # Flyway + MyBatis-Plus、实体、Mapper、Repository
│     │  └─ service/            # 文件与快照用例
│     └─ resources/
│        ├─ META-INF/plugin-config.json          # 配置声明（字段 + 数据源）
│        └─ db/migration/{main,cache}/V1__*.sql  # 插件自有迁移
├─ docs/logo.svg                # 项目 Logo
├─ GUIDE.md                     # 插件开发指南（EN）
├─ GUIDE_zh.md                  # 插件开发指南（简体中文）
├─ LICENSE                      # GPL-3.0
└─ README.md / README_zh.md     # 本文件
```

> 其余插件工程以后平级放在仓库根目录，每个都是独立的 Gradle 构建。

# 示例插件 `echo/`

一个最小「回声」插件，演示编程模型：

| 演示点 | 实现 |
|---|---|
| 入口 | `EchoPlugin` 实现 `ApiPlugin`，在 `onStart` 中注册路由 |
| 路由注册 | `context.api().register(RouteDefinition(...)) { request -> ... }` |
| 版本共存 | v1 的 `ping` / `version`，v2 的 `ping` / `whoami`；请求更高版本时向前兼容到最新匹配路由 |
| 显式 OpenAPI | `OperationMetadata` + `ParameterSpec` + `ResponseSpec` + `SchemaSpec` |
| 生命周期 | `onStart` / `onStop` / `onUninstall` 写入插件级设置 |
| 描述符 / 文档 | `apiPlugin {}` 生成 `api-plugin.json`；文档由声明的元数据生成 |
| 密钥 / 签名 | `genPluginKeys` + `signPlugin` 随 `build` 执行 |

安装并授权后可调用（Basic 认证 `accessKey:secretKey`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/echo/v1/ping` | v1 回声：`message` + `userId` |
| GET | `/api/echo/v1/version` | v1 版本信息与接口列表 |
| GET | `/api/echo/v2/ping` | 从 v2 起覆盖 v1 的 `ping` |
| GET | `/api/echo/v2/whoami` | 当前调用者：`userId` + `accessKeyId` |

示例响应（`/api/echo/v2/ping`）：

```json
{ "code": 0, "success": true, "msg": "success", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

# 示例插件 `filebox/`

一个「文件盒」插件：数据放在**插件自己的两个数据源**里——元数据在 MySQL、快照在 SQLite——字节放在**插件自己的文件区**里。

| 演示点 | 实现 |
|---|---|
| 配置声明 | `META-INF/plugin-config.json`：string / number / boolean / text / secret 字段与默认值，外加两条数据源声明 |
| 一个插件两个数据源 | `main` 是 MySQL，由普通配置项描述；`cache` 是 SQLite，网关**零配置**供给（`data/db/plugin/filebox/cache.db`） |
| 插件自有迁移 | 挂载时 Flyway 在 MySQL 上执行 `db/migration/main`、在 SQLite 上执行 `db/migration/cache`；迁移失败会让挂载失败并写入 `loadError` |
| 持久化 | MyBatis-Plus（`MybatisSqlSessionFactory` + 分页拦截器）配合插件自己的 Mapper / Repository |
| 配置即时生效 | 普通值每次读取都取最新；保存数据源连接后网关**自动重挂载**插件，没有单独的 reload |
| 位置寻址 + 免登外链 | `upload` / `download` / `link` / `remove`：存储、读回、生成临时外链、删除 |
| 内容寻址 + 去重 | `snapshot` / `readSnapshot`：相同文本只存一份，以 SHA-256 为键 |
| 生命周期 | `FileboxPlugin` 每次安装构建一个 runtime；`onPurge` 删除插件自己的表并释放内容寻址对象 |

安装、配置后调用（Basic 认证 `accessKey:secretKey`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/filebox/v1/upload?fileName=&token=` | 请求体即文件字节；返回文件 ID 与存储路径 |
| GET | `/api/filebox/v1/files?name=&page=&size=` | 当前调用者的文件，支持过滤与分页 |
| GET | `/api/filebox/v1/files/{id}` | 下载原样字节（透传，不带响应信封） |
| GET | `/api/filebox/v1/files/{id}/link?ttlHours=` | 生成免登外链；`ttlHours` 缺省时取配置值 |
| DELETE | `/api/filebox/v1/files/{id}` | 删除元数据与字节；已发外链随之失效 |
| POST | `/api/filebox/v1/snapshots?text=` | 存文本快照（内容寻址、去重） |
| GET | `/api/filebox/v1/snapshots/{contentKey}` | 按 SHA-256 读回文本 |

快照那条数据源**装完即用**。文件那条需要一个 MySQL 库，用配置接口填一次（详见 [GUIDE_zh.md](GUIDE_zh.md) §7）：

```shell
GET  /system/api/plugin/filebox/config
# datasources: [ {name:"main", dbType:"MYSQL", required:true, configured:false, keys:[db.host, ...]},
#               {name:"cache", dbType:"SQLITE", required:false, configured:true, keys:[]} ]

# 先试连，再保存；保存这些值后网关会自动重挂载插件
POST /system/api/plugin/filebox/config/datasource/test
     {"name":"main","values":[{"key":"db.host","value":"10.0.0.240"},{"key":"db.name","value":"filebox"}]}
PUT  /system/api/plugin/config
     {"pluginId":"filebox","groups":[{"key":"db","values":[
        {"key":"db.host","value":"10.0.0.240"},{"key":"db.name","value":"filebox"},
        {"key":"db.user","value":"filebox"},{"key":"db.password","value":"…"}]}]}
```

# 快速开始

### 0. 发布契约与工具链（一次性）

在 [api-management](https://github.com/FatttSnake/api-management)（网关）仓库执行：

```shell
./gradlew :gateway-api:publishToMavenLocal :plugin-signing:publishToMavenLocal :plugin-gradle-plugin:publishToMavenLocal
```

### 1. 构建示例插件

```shell
cd echo
./gradlew build
# 产物：echo/build/libs/echo-1.0.0.jar（已签名），公钥在 echo/keys/public.pem
./gradlew verifyPlugin     # 可选：自检签名
```

jar 内含 `META-INF/api-plugin.json`（描述符）、`META-INF/api-plugin-deps.json`（依赖 lock）、
`META-INF/plugin.pub.pem` 与 `META-INF/plugin.sig`。依赖模块是 `build/generated/api-plugin-modules/`
下的独立 jar；`deployPlugin` 会先上传它们，网关也可以从配置的 Maven 仓库补齐缺失模块。

### 2. 上传并调用（面向网关管理员/使用方）

```shell
# 1. 管理员信任签名公钥（内容来自 echo/keys/public.pem）
POST /system/api/plugin/key   { "publicKey": "<...>", "alias": "你的名字" }

# 2. 上传安装插件 jar
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar)

# 3. 授权给 API 账户
POST /user/api/key   { "permissionCodes": ["api:echo:v1:ping", "api:echo:v1:version", "api:echo:v2:ping", "api:echo:v2:whoami"] }

# 4. 调用
curl -u "<accessKey>:<secretKey>" http://<gateway>/api/echo/v2/ping
```

**升级**：调大 `apiPlugin.versionCode` 后重新构建上传即可。调试循环不必升版本：重传当前已安装的
版本会原地替换，只有**更低**的版本会被拒绝。更多操作见 [GUIDE_zh.md](GUIDE_zh.md) §8；想在调试器里
单步插件代码，见网关仓库的 [docs/plugin-debugging_zh.md](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-debugging_zh.md)。

# 创建你自己的插件

新建一个目录（如 `geo/`），放下面两个文件 + 你的 Kotlin 源码，然后执行 `./gradlew build`。

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
    pluginId = "geo"                 // 唯一 ID，≤64 字符
    pluginName = "Geo 插件"
    versionCode = 1                  // 必填；更高为升级，相同则原地重新部署
    description = "把地理编码开放成 API"   // 可选
    author = "你的名字"                    // 可选
    mainClass = "com.example.geo.GeoPlugin"   // 必填：你的 ApiPlugin 实现
    // 仅当多个模块必须共享一个类加载器时，例如 Flyway 的数据库支持：
    // sharedLoader("org.flywaydb:flyway-core", "org.flywaydb:flyway-mysql")
}
```

```kotlin
// src/main/kotlin/com/example/geo/GeoPlugin.kt
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

构建即自动：生成 `keys/`、生成 `META-INF/api-plugin.json`、嵌入 `plugin.pub.pem`、按
`<pluginId>-<versionName>.jar` 命名并 Ed25519 签名。

完整的 DSL 字段、依赖 lock 与 `sharedLoader` 规则见 [GUIDE_zh.md](GUIDE_zh.md) / [GUIDE.md](GUIDE.md)。

# 环境要求

- **JDK 25+**
- `top.fatweb.api-plugin` 与 `top.fatweb:api-management-gateway-api:1.0.0-SNAPSHOT` 可解析（本地 `mavenLocal` 或私有镜像）
- 首次构建自动下载 Gradle wrapper，无需预装 Gradle

# 关联项目

[API Management](https://github.com/FatttSnake/api-management)

[Web Console](https://github.com/FatttSnake/api-management-console)

# 许可证

[GPL-3.0](LICENSE)

<div align="center">
    <h1>
        <img alt="API Management Plugins" src="docs/logo.svg" width="140">
        <br>
        <span>API Management Plugins</span>
    </h1>
</div>
<div align="center">
    <b>The plugin workspace for the API Management gateway — build, package and sign hot-pluggable API plugins</b>
</div>

# Overview ([简体中文](README_zh.md), EN)

Every "available API" on the [API Management](https://github.com/FatttSnake/api-management) platform is delivered by a **plugin**: an **Ed25519-signed** jar is uploaded to the gateway and **hot-plugged** (install / disable / upgrade / uninstall, no restart required). Its endpoints are exposed as `/api/{plugin}/v{version}/...`, while authentication, access control, rate limiting, metering, billing and auditing are handled by the platform.

This repository is the **development workspace** for plugins: **every subdirectory under the root is an independent Gradle plugin project** with its own packaging and signing. It contains `echo/` and `filebox/` — two complete, runnable examples.

**Building a plugin is one `build.gradle.kts` (~10 lines) + your Kotlin sources.** The Gradle plugin `top.fatweb.api-plugin` compiles against `gateway-api`, resolves runtime dependencies into a **dependency lock**, copies each dependency module as its own jar, generates `META-INF/api-plugin.json`, embeds the public key and signs the jar with Ed25519 at `build` time. The plugin jar itself stays thin: no Shadow, no fat jar.

# Repository layout

```
api-management-plugins/
├─ echo/                        # minimal example plugin (standalone Gradle project)
│  ├─ build.gradle.kts          # kotlin jvm + id("top.fatweb.api-plugin") + apiPlugin{...}
│  ├─ settings.gradle.kts       # pluginManagement includes mavenLocal (Gradle plugin / gateway-api)
│  ├─ keys/                     # auto-generated on first build (never commit private.pem)
│  └─ src/main/kotlin/com/example/echo/
│     ├─ EchoPlugin.kt          # ApiPlugin: v1/v2 routes, metadata, lifecycle hooks
│     └─ EchoResponses.kt       # response DTOs
├─ filebox/                     # data & file example plugin (standalone Gradle project)
│  ├─ build.gradle.kts          # MyBatis-Plus + Flyway + sharedLoader groups
│  ├─ settings.gradle.kts       # rootProject.name = filebox-plugin
│  ├─ keys/                     # auto-generated on first build
│  └─ src/main/
│     ├─ kotlin/com/example/filebox/
│     │  ├─ FileboxPlugin.kt    # ApiPlugin entry point
│     │  ├─ FileboxRuntime.kt   # composition root of one mount
│     │  ├─ api/                # routes and response DTOs
│     │  ├─ config/             # typed settings
│     │  ├─ db/                 # Flyway + MyBatis-Plus, entities, mappers, repositories
│     │  └─ service/            # file and snapshot use cases
│     └─ resources/
│        ├─ META-INF/plugin-config.json          # config schema (fields + datasources)
│        └─ db/migration/{main,cache}/V1__*.sql  # plugin-owned migrations
├─ docs/logo.svg                # project logo
├─ GUIDE.md                     # plugin development guide (EN)
├─ GUIDE_zh.md                  # plugin development guide (简体中文)
├─ LICENSE                      # GPL-3.0
└─ README.md / README_zh.md     # this file
```

> Further plugins live as siblings under the repository root, each an independent Gradle build.

# Example plugin `echo/`

A minimal "echo" plugin demonstrating the programming model:

| What | How |
|---|---|
| Entry point | `EchoPlugin` implements `ApiPlugin` and registers routes in `onStart` |
| Route registration | `context.api().register(RouteDefinition(...)) { request -> ... }` |
| Version coexistence | v1 `ping` / `version`, v2 `ping` / `whoami`; a higher requested version rolls forward to the latest matching route |
| Explicit OpenAPI metadata | `OperationMetadata` + `ParameterSpec` + `ResponseSpec` + `SchemaSpec` |
| Lifecycle | `onStart` / `onStop` / `onUninstall` write plugin-scoped settings; `onStart` removes the previous `stoppedAt` marker |
| Descriptor / docs | `apiPlugin {}` generates `api-plugin.json`; docs come from the declared metadata |
| Keys / signing | `genPluginKeys` + `signPlugin` run as part of `build` |

Install it, grant the APIs, then call with Basic auth `accessKey:secretKey`:

| Method | Path | Description |
|---|---|---|
| GET | `/api/echo/v1/ping` | v1 echo: `message` + `userId` |
| GET | `/api/echo/v1/version` | v1 version info and endpoint list |
| GET | `/api/echo/v2/ping` | From v2 on, overrides v1 `ping` |
| GET | `/api/echo/v2/whoami` | Current caller: `userId` + `accessKeyId` |

Example response (`/api/echo/v2/ping`):

```json
{ "code": 0, "success": true, "msg": "success", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

# Example plugin `filebox/`

A "file box" plugin whose data lives in **two plugin-owned datasources** — metadata in MySQL, snapshots in SQLite — and whose bytes live in the **plugin's own file area**.

| What | How |
|---|---|
| Config schema | `META-INF/plugin-config.json`: string / number / boolean / text / secret fields with defaults, plus two datasource declarations |
| Two datasources | `main` is MySQL, described by ordinary config keys; `cache` is SQLite, provisioned by the gateway with zero configuration (`data/db/plugin/filebox/cache.db`) |
| Plugin-owned migrations | Flyway runs `db/migration/main` on MySQL and `db/migration/cache` on SQLite at mount; a failed migration fails the mount with `loadError` |
| Persistence | MyBatis-Plus (`MybatisSqlSessionFactory`, pagination interceptor) over plugin-scoped mappers and repositories |
| Config takes effect immediately | Ordinary values are read per call; saving a datasource connection remounts the plugin automatically — there is no separate reload step |
| Location addressing + login-free links | `upload` / `download` / `link` / `remove`: store, read back, mint a temporary external URL, delete |
| Content addressing + deduplication | `snapshot` / `readSnapshot`: identical text is stored once, keyed by SHA-256 |
| Lifecycle | `FileboxPlugin` mounts a runtime per install; `onConfigChanged` logs the changed keys; `onPurge` drops the plugin's tables and releases content-addressed objects |

Endpoints after install and configuration (Basic auth `accessKey:secretKey`):

| Method | Path | Description |
|---|---|---|
| POST | `/api/filebox/v1/upload?fileName=&token=` | Raw request body is the file; returns the file ID and storage path |
| GET | `/api/filebox/v1/files?name=&page=&size=` | Current caller's files, filtered and paginated |
| GET | `/api/filebox/v1/files/{id}` | Download the original bytes (pass-through, no response envelope) |
| GET | `/api/filebox/v1/files/{id}/link?ttlHours=` | Mint a login-free link; `ttlHours` defaults to the configured value |
| DELETE | `/api/filebox/v1/files/{id}` | Delete metadata and bytes; issued links stop working |
| POST | `/api/filebox/v1/snapshots?text=` | Store a text snapshot (content-addressed, deduplicated) |
| GET | `/api/filebox/v1/snapshots/{contentKey}` | Read the text back by SHA-256 |

The `cache` datasource works immediately after install. The `main` datasource needs a MySQL database, filled in once through the config API (see [GUIDE.md](GUIDE.md) §7):

```shell
GET  /system/api/plugin/filebox/config
# datasources: [ {name:"main", dbType:"MYSQL", required:true, configured:false, keys:[db.host, ...]},
#               {name:"cache", dbType:"SQLITE", required:false, configured:true, keys:[]} ]

# Try the connection, then save. Saving these values remounts the plugin automatically.
POST /system/api/plugin/filebox/config/datasource/test
     {"name":"main","values":[{"key":"db.host","value":"10.0.0.240"},{"key":"db.name","value":"filebox"}]}
PUT  /system/api/plugin/config
     {"pluginId":"filebox","groups":[{"key":"db","values":[
        {"key":"db.host","value":"10.0.0.240"},{"key":"db.name","value":"filebox"},
        {"key":"db.user","value":"filebox"},{"key":"db.password","value":"…"}]}]}
```

# Quick start

### 0. Publish the contract and tooling (once)

In the [api-management](https://github.com/FatttSnake/api-management) (gateway) repository:

```shell
./gradlew :gateway-api:publishToMavenLocal :plugin-signing:publishToMavenLocal :plugin-gradle-plugin:publishToMavenLocal
```

### 1. Build an example plugin

```shell
cd echo
./gradlew build
# artifact: echo/build/libs/echo-1.0.0.jar (signed), public key in echo/keys/public.pem
./gradlew verifyPlugin     # optional: self-check the signature
```

The jar contains `META-INF/api-plugin.json` (descriptor), `META-INF/api-plugin-deps.json`
(dependency lock), `META-INF/plugin.pub.pem` and `META-INF/plugin.sig`. Dependency modules are
separate jars under `build/generated/api-plugin-modules/`; `deployPlugin` uploads them before the
plugin, or the gateway fetches missing modules from its configured Maven repositories.

### 2. Upload and call (gateway administrator / consumer)

```shell
# 1. Trust the signing key (contents of echo/keys/public.pem)
POST /system/api/plugin/key   { "publicKey": "<...>", "alias": "your name" }

# 2. Upload and install the plugin jar
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar)

# 3. Grant APIs to an API account
POST /user/api/key   { "permissionCodes": ["api:echo:v1:ping", "api:echo:v1:version", "api:echo:v2:ping", "api:echo:v2:whoami"] }

# 4. Call
curl -u "<accessKey>:<secretKey>" http://<gateway>/api/echo/v2/ping
```

**Upgrade**: raise `apiPlugin.versionCode`, rebuild and upload. For the edit-and-deploy loop you do
not need to bump anything: re-uploading the installed version replaces it in place; only a **lower**
version is refused. More operations are in [GUIDE.md](GUIDE.md) §8. To step through plugin code in a
debugger, see the gateway's [docs/plugin-debugging.md](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-debugging.md).

# Create your own plugin

Create a directory (e.g. `geo/`) with the two files below plus your Kotlin sources, then run
`./gradlew build`.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal(); mavenLocal() }
}
rootProject.name = "geo-plugin"
```

```kotlin
// build.gradle.kts — the whole build config
plugins {
    kotlin("jvm") version "2.3.21"
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}
version = "1.0.0"

apiPlugin {
    pluginId = "geo"                 // unique ID, ≤64 characters
    pluginName = "Geo Plugin"
    versionCode = 1                  // required; higher is an upgrade, the same one redeploys in place
    description = "Expose geocoding as an API"   // optional
    author = "Your name"                          // optional
    mainClass = "com.example.geo.GeoPlugin"       // required: your ApiPlugin implementation
    // Only when several modules must share one class loader, e.g. Flyway's database support:
    // sharedLoader("org.flywaydb:flyway-core", "org.flywaydb:flyway-mysql")
}
```

```kotlin
// src/main/kotlin/com/example/geo/GeoPlugin.kt
class GeoPlugin : ApiPlugin {
    override fun onStart(context: GatewayContext) {
        context.api().register(
            RouteDefinition("GET", "/geocode", 1, OperationMetadata("geocode", "Geocode an address"))
        ) { request ->
            ApiResponse.ok(mapOf("address" to request.queryParam("address")))
        }
    }
}
```

The build generates `keys/`, `META-INF/api-plugin.json`, embeds `plugin.pub.pem`, names the jar
`<pluginId>-<versionName>.jar` and signs it with Ed25519.

For the full DSL reference, the dependency lock and `sharedLoader` rules, see
[GUIDE.md](GUIDE.md) / [GUIDE_zh.md](GUIDE_zh.md).

# Requires

- **JDK 25+**
- `top.fatweb.api-plugin` and `top.fatweb:api-management-gateway-api:1.0.0-SNAPSHOT` resolvable (local `mavenLocal` or a private mirror)
- The Gradle wrapper downloads on first build; no pre-installed Gradle needed

# Related projects

[API Management](https://github.com/FatttSnake/api-management)

[Web Console](https://github.com/FatttSnake/api-management-console)

# License

[GPL-3.0](LICENSE)

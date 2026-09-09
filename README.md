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

Every "available API" on the [API Management](https://github.com/FatttSnake/api-management) platform is delivered by a **plugin**: an **Ed25519-signed** jar is uploaded to the gateway and **hot-plugged** (install / disable / upgrade / uninstall, no restart required) inside an **isolated classloader / child container**; the endpoints it declares are uniformly exposed as `/api/{plugin}/v{version}/...`, while authentication, access control, rate limiting, metering, billing and auditing are all handled by the platform.

This repository is the **development workspace** for plugins: **every subdirectory under the root is an independent Gradle plugin project** with its own packaging and signing. It bundles `echo/` — a complete, runnable example plugin.

**Building a new plugin is now a single `build.gradle.kts` (~10 lines) + your Kotlin sources**: the SDK dependency, key generation, the `api-plugin.json` descriptor, public-key embedding and the Ed25519 signature are all handled by the Gradle plugin `top.fatweb.api-plugin` at `build` time.

# Repository layout

```
api-management-plugins/
├─ echo/                        # complete example plugin (standalone Gradle project)
│  ├─ build.gradle.kts          # kotlin jvm + id("top.fatweb.api-plugin") + apiPlugin{...}
│  ├─ settings.gradle.kts       # pluginManagement includes mavenLocal (Gradle plugin / SDK)
│  ├─ keys/                     # auto-generated on first build (never commit private.pem)
│  └─ src/main/
│     ├─ kotlin/com/example/echo/   # EchoController(v1) / EchoControllerV2(v2) / EchoService / EchoLifecycle
│     └─ resources/META-INF/
│        └─ plugin-openapi.json     # OpenAPI fragment (optional; descriptor/pub key/signature are generated)
├─ docs/logo.svg                # project logo
├─ GUIDE.md                     # plugin development guide (EN)
├─ GUIDE_zh.md                  # plugin development guide (简体中文)
├─ LICENSE                      # GPL-3.0
└─ README.md / README_zh.md     # this file
```

> Further plugins live as siblings under the repository root, each an independent Gradle build.

# Example plugin `echo/`

An "echo" plugin demonstrating the full plugin capabilities:

| Demo point | Implementation |
|---|---|
| Controllers / version coexistence | `EchoController`(v1), `EchoControllerV2`(v2) — rolling-compatible inside one plugin |
| Business bean injection | `EchoService` (constructor injection) |
| Lifecycle hooks | `EchoLifecycle` (writes `installedAt`/`startedAt`/`stoppedAt`/`uninstalledAt` plugin settings) |
| Descriptor / docs | `api-plugin.json` from `apiPlugin {}` + hand-written `plugin-openapi.json` |
| Keys / signing | `genPluginKeys` auto-generation + `signPlugin` at build time |

Once installed and granted, call it with Basic auth (`accessKey:secretKey`):

| Method | Path | Description |
|---|---|---|
| GET | `/api/echo/v1/ping` | v1 echo: `message` + `userId` |
| GET | `/api/echo/v1/version` | v1 version info and endpoint list |
| GET | `/api/echo/v2/ping` | overrides v1's `ping` from v2 on (rolling compatibility) |
| GET | `/api/echo/v2/whoami` | current caller: `userId` + `accessKeyId` |

Sample response (`/api/echo/v2/ping`):

```json
{ "code": 0, "success": true, "msg": "OK", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

# Quick start

### 0. Make the Gradle plugin / SDK available (once)

Run this once in the [api-management](https://github.com/FatttSnake/api-management) (gateway) repository:

```shell
./gradlew :plugin-sdk:publishToMavenLocal :plugin-gradle-plugin:publishToMavenLocal
```

### 1. Build the sample plugin (auto-generates keys, descriptor and signature)

```shell
cd echo
./gradlew build
# artifact: echo/build/libs/echo-1.0.0.jar (already signed); public key in echo/keys/public.pem
./gradlew verifyPlugin     # optional: self-check the signature
```

### 2. Upload and call (gateway admin / consumer)

```shell
# 1. Admin adds the public key to the trust store (contents from echo/keys/public.pem)
POST /system/api/plugin/key   { "publicKey": "<...>", "alias": "Your name" }

# 2. Upload and install
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar)

# 3. Grant APIs to an API account
POST /user/api/key   { "permissionCodes": ["api:echo:v1:ping", "api:echo:v1:version", "api:echo:v2:ping", "api:echo:v2:whoami"] }

# 4. Call
curl -u "<accessKey>:<secretKey>" http://<gateway>/api/echo/v2/ping
```

**Upgrade**: bump `apiPlugin.versionCode` in `build.gradle.kts`, rebuild and re-upload. For **uninstall / disable / restart recovery** and more, see [GUIDE.md](GUIDE.md) §7-§8.

# Create your own plugin in one go

Create a directory (e.g. `geo/`), add the two files below plus your Kotlin sources, then `./gradlew build` produces a signed jar:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal(); mavenLocal() }
}
rootProject.name = "geo-plugin"
```

```kotlin
// build.gradle.kts — the entire build configuration
plugins {
    kotlin("jvm") version "2.3.21"
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}
version = "1.0.0"

apiPlugin {
    pluginId = "geo"              // unique ID; must equal @ApiController.plugin
    pluginName = "Geo Plugin"
    versionCode = 1               // required; drives the gateway upgrade check, bump per release
    description = "Expose geocoding as an API"   // optional
    author = "Your name"                          // optional
    mainClass = "com.example.geo.GeoLifecycle"    // only when you have a lifecycle
}
```

```kotlin
// src/main/kotlin/com/example/geo/GeoController.kt — your controller
@ApiController(plugin = "geo", version = 1)   // per-interface name/description come from each method's @Operation
class GeoController(private val pluginContext: PluginContext) {
    @GetMapping("/geocode")
    fun geocode(): ApiResponse<Map<String, Any?>> = ApiResponse.ok(mapOf("ok" to true))
}
```

The build automatically generates `keys/`, produces `META-INF/api-plugin.json`, embeds `plugin.pub.pem`, names the jar `<pluginId>-<versionName>.jar` and signs it with Ed25519.

For the full DSL reference and the manual (no Gradle plugin) approach, see [GUIDE.md](GUIDE.md) / [GUIDE_zh.md](GUIDE_zh.md).

# Requires

- **JDK 25+**
- `top.fatweb.api-plugin` and `top.fatweb:api-management-plugin-sdk:1.0.0-SNAPSHOT` resolvable (local `mavenLocal` or a private mirror)
- The Gradle wrapper downloads on first build; no pre-installed Gradle needed

# Related projects

[API Management](https://github.com/FatttSnake/api-management)

[Web Console](https://github.com/FatttSnake/api-management-console)

# License

[GPL-3.0](LICENSE)

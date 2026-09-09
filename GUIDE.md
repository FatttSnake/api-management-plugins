# API Plugin Development Guide

This guide is for **plugin developers**. An API is authored, packaged and signed independently as a **plugin jar**, uploaded to the gateway and **hot-plugged** (install / disable / upgrade / uninstall) with automatic re-mounting after restart. The `echo/` directory in this repository is a complete, runnable example — reading it side by side with this document works best.

**Writing a plugin is now a single `build.gradle.kts` (~10 lines) + your Kotlin sources**: signing, keys, the descriptor and documentation resources are all handled by the Gradle plugin `top.fatweb.api-plugin`.

- SDK artifact: `top.fatweb:api-management-plugin-sdk` (added automatically by the Gradle plugin)
- Gradle plugin: `top.fatweb.api-plugin` (recommended by this guide)
- SDK packages: `top.fatweb.apimanagement.sdk.annotation` / `top.fatweb.apimanagement.sdk.plugin`
- Gateway / console: [api-management](https://github.com/FatttSnake/api-management) · [api-management-console](https://github.com/FatttSnake/api-management-console)

---

## 0. Prerequisite: make the SDK and Gradle plugin available

The Gradle plugin and SDK are built and published together from the **gateway repository**. Before first use, run this once inside the [api-management](https://github.com/FatttSnake/api-management) repository (installs the artifacts into your local Maven repository):

```shell
./gradlew :plugin-sdk:publishToMavenLocal :plugin-gradle-plugin:publishToMavenLocal
```

A plugin project then resolves them by adding `mavenLocal()` under `pluginManagement` in its `settings.gradle.kts`. If you publish to a private Nexus instead, add that repository likewise (the SDK version can be overridden with `-PapiPlugin.sdkVersion=<version>`).

---

## 1. Overall model

```
Plugin project (standalone Gradle build)           Gateway (api-management-backend)
┌──────────────────────────────────────┐        ┌──────────────────────────────────────────┐
│ Kotlin sources + manual OpenAPI      │ upload │ PluginSigner.verify (integrity)          │
│ (build.gradle.kts declares apiPlugin)│──────▶│ Trust store check (pub key keyId         │
│                                      │        │   present & enabled)                     │
│ The Gradle plugin does at            │        │ Child-first ClassLoader loads jar        │
│ build time:                          │        │ Child container GenericApplicationContext│
│  ├ generate api-plugin.json          │        │ RequestMappingInfo + ApiVersionCondition │
│  ├ genPluginKeys → keys/             │        │ → registerMapping                        │
│  ├ embed plugin.pub.pem              │        │ DB upsert (plugin/API/permission tree)   │
│  └ signPlugin (Ed25519)              │        │ Auto re-mount from blob on startup       │
└──────────────────────────────────────┘        └──────────────────────────────────────────┘
```

### Security boundary (read this carefully)

Plugin code runs **inside the gateway JVM**; no data isolation can stop malicious code. **The real anti-malware boundary is: mandatory Ed25519 signature + the signing public key must be in the administrator-maintained trust store.** Data isolation (an independent datasource per plugin + the narrow `PluginContext` API) is defense-in-depth against accidents, not against malice. Therefore:

- **The private key is the root of identity** — whoever holds it can publish plugins as you. It lives only in your `keys/private.pem`; **never commit it or leak it**.
- Submit the public key `keys/public.pem` to the gateway administrator to add it to the trust store, otherwise your plugin cannot be installed.
- **Deleting `keys/private.pem` and rebuilding changes your identity**: previously installed plugins will no longer be re-mounted after the next restart because the signer is no longer trusted.

---

## 2. One-click scaffold: the minimal plugin project

Create a directory (e.g. `geo/`) with three files plus your sources:

```
geo/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ keys/            # auto-generated on first build (never commit private.pem)
└─ src/main/
   ├─ kotlin/com/example/geo/GeoController.kt    # your @ApiController
   └─ resources/META-INF/plugin-openapi.json     # OpenAPI fragment (optional)
```

### 2.1 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()   // resolves top.fatweb.api-plugin and the SDK snapshot
    }
}

rootProject.name = "geo-plugin"
```

### 2.2 `build.gradle.kts` (that is all the build config)

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"               // your Kotlin version
    id("top.fatweb.api-plugin") version "1.0.0-SNAPSHOT"
}

version = "1.0.0"                                 // also the default versionName

apiPlugin {
    pluginId = "geo"                              // unique ID, ^[a-z][a-z0-9-]*$
    pluginName = "Geo Plugin"                     // display name
    versionCode = 1                               // required; drives the upgrade check, bump per release
    description = "Expose geocoding as an API"    // optional
    author = "Your name"                          // optional
    mainClass = "com.example.geo.GeoLifecycle"    // only when you have a PluginLifecycle
}
```

Defaults of the `apiPlugin` DSL and the descriptor field they map to:

| DSL field | Required | Default | Descriptor field |
|---|---|---|---|
| `pluginId` | ✅ | project name | `pluginId` (must equal `@ApiController.plugin`) |
| `pluginName` | | same as `pluginId` | `name` |
| `versionName` | | project `version` (blank also falls back) | `versionName` |
| `versionCode` | ✅ | — (required, no default) | `versionCode` (must increase on upgrade) |
| `description` / `author` | | empty | same name |
| `mainClass` | | none | `mainClass` (auto-discovered by the gateway when absent) |
| `sdkVersion` | | SDK version bundled with the plugin | (dependency only, not in the descriptor) |
| `archiveName` | | `<pluginId>-<versionName>.jar` | (jar file name) |

> The plugin automatically adds repositories (`mavenCentral` + `mavenLocal`) and the SDK's `implementation` dependency; on **every build** it generates `META-INF/api-plugin.json` and `META-INF/plugin.pub.pem` from the DSL above (inside `build/`), generates `keys/`, names the jar `<pluginId>-<versionName>.jar` and signs it. So you **no longer hand-write** `api-plugin.json` / `plugin.pub.pem` in the repo.

### 2.3 `.gitignore`

```gitignore
keys/            # keys (the private key must never be committed)
build/
.gradle/
```

### 2.4 Three commands

```shell
./gradlew build           # one-shot: keys/ + descriptor + pub.pem + signature
./gradlew genPluginKeys   # regenerate/confirm keys (skips when they exist)
./gradlew verifyPlugin    # self-check the jar signature (fails on invalid)
```

Artifact: `build/libs/<pluginId>-<versionName>.jar`, **already signed**, ready to upload.

### 2.5 Manual approach (without the Gradle plugin)

If you only want a signing task without the DSL, the minimal `build.gradle.kts` is:

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

In the manual mode you must maintain `keys/private.pem`, `src/main/resources/META-INF/api-plugin.json` and `plugin.pub.pem` yourself (run `PluginSigner genkey keys/`, then copy `public.pem` to `plugin.pub.pem`). When hand-writing `api-plugin.json`, `versionName` is required (`pluginId` and `name` are too).

---

## 3. Descriptor

Generated by the Gradle plugin from `apiPlugin { }` at `build` time (see the mapping in 2.2). `echo/` produces something like:

```json
{
  "pluginId": "echo",
  "name": "Echo 插件",
  "versionName": "1.0.0",
  "versionCode": 1,
  "description": "Echo plugin demonstrating the whole hot-plug lifecycle",
  "author": "FatttSnake",
  "mainClass": "com.example.echo.EchoLifecycle"
}
```

| Field | Required | Description |
|---|---|---|
| `pluginId` | ✅ | Unique ID, `^[a-z][a-z0-9-]*$`; must match `plugin` on every `@ApiController` |
| `name` | ✅ | Display name (menus / permission tree / docs) |
| `versionName` | ✅ | Human-readable version, e.g. `1.2.0`; **required in a hand-written descriptor** (with the Gradle plugin it defaults to the project `version`) |
| `versionCode` | | **Monotonically increasing** integer; upgrades must exceed the installed one or they are rejected |
| `description` / `author` | | Description / author |
| `mainClass` | | FQCN of the `PluginLifecycle` implementation (needs `@Component`); auto-discovered when absent |

> `versionCode` follows the Android semantics: **only one instance per `pluginId` is allowed, and upgrades may only move to a higher `versionCode`**.

---

## 4. Signing and the trust store

### 4.1 Generating keys (automatic)

`./gradlew build` (or `genPluginKeys`) generates an Ed25519 key pair into `keys/` on first run:

- `keys/private.pem` (PKCS8) — used only for local signing, **never commit / leak it**
- `keys/public.pem` (SPKI) — submit to the gateway admin to add to the trust store

At build time the public key is embedded in the jar as `META-INF/plugin.pub.pem`; the signature `META-INF/plugin.sig` is written by `signPlugin`.

> **Changing identity**: delete `keys/private.pem` and run `build` again — a fresh pair is generated (and the jar's embedded public key and signature update accordingly).

### 4.2 Local self-check

```shell
./gradlew verifyPlugin
# Expected output: Signature valid
```

### 4.3 Admin adds your key to the trust store

```
POST /system/api/plugin/key
{ "publicKey": "<contents of keys/public.pem>", "alias": "Your name" }
```

The gateway derives `keyId` from the public key automatically (SHA-256 of the SPKI). After that your plugin can be installed. Trust-store management endpoints:

| Method | Path | Description |
|---|---|---|
| GET | `/system/api/plugin/key` | List |
| POST | `/system/api/plugin/key` | Add public key |
| PATCH | `/system/api/plugin/key` | Enable / disable |
| DELETE | `/system/api/plugin/key/{keyId}` | Delete |

> Revocation: once the admin deletes/disables your public key, installed plugins will **not be re-mounted** on the next restart because the signer is no longer trusted (the database row is kept, and `loadError` records the reason).

---

## 5. OpenAPI documentation

An optional hand-written resource `src/main/resources/META-INF/plugin-openapi.json` (an OpenAPI 3.0 fragment) describes parameters and data structures for the gateway to show end users. Fragment from `echo/`:

```json
{
  "openapi": "3.0.1",
  "paths": {
    "/api/echo/v1/ping": {
      "get": {
        "summary": "Echo",
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

- Use the full public path `/api/{plugin}/v{version}/...` as the `paths` key (identical to the call URL);
- Stored verbatim into `t_s_api_plugin.openapi` on install;
- Served to the front end by `GET /user/api/docs` (list) / `GET /user/api/docs/{pluginId}` (detail);
- **Missing it does not block install**: the docs endpoint still returns the API list, it just lacks parameter/structure details.

---

## 6. Writing controllers and business code

The SDK's `implementation` dependency is added automatically by the Gradle plugin and exposes the spring-web / spring-context / swagger annotations transitively — just write controllers.

### 6.1 `@ApiController`

```kotlin
@ApiController(
    plugin = "geo",   // must equal apiPlugin.pluginId (the descriptor pluginId)
    version = 1       // API version; one plugin may ship several versions
)
class GeoController(
    private val pluginContext: PluginContext,
    private val geoService: GeoService
) {
    // Per-interface name/description come from @Operation (summary/description)
    @Operation(summary = "Geocode", description = "Turn an address into coordinates", operationId = "geocode")
    @GetMapping("/geocode")
    fun geocode(): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("result" to geoService.lookup(pluginContext.currentUserId())))
}
```

Key points:

- Controllers take `PluginContext` and their own `@Service` via **constructor injection**; the child container wires them automatically.
- Plugin-level metadata (`name` / `description` / `author` / version) is declared **once** in `apiPlugin { }` (→ `api-plugin.json`); `@ApiController` only declares which plugin (`plugin`, must equal the descriptor `pluginId`) and version (`version`) each controller belongs to.
- Each endpoint's display `name` = `@Operation.summary` (fallback: method name) and `description` = `@Operation.description` (fallback: empty); they surface in the permission tree and the interface table.
- Every endpoint method carries an HTTP mapping annotation (`@GetMapping`, etc.); `operationId` (defaults to the method name) defines the API permission code: `api:{plugin}:v{version}:{operationId}`.
- A plugin may ship **multiple `@ApiController(version=n)`** side by side for forward compatibility. `echo/` does exactly this: `EchoController` (v1, `ping`/`version`) and `EchoControllerV2` (v2, `ping`/`whoami`) coexist in **the same plugin** — from `v2` onward `ping` is served by v2 (rolling compatibility), while the v1-only `version` endpoint is still handled by v1 as a fallback.

### 6.2 The response envelope `ApiResponse<T>`

The user-facing response envelope provided by the SDK, decoupled from the gateway-internal `ResponseResult`:

| Field | Description |
|---|---|
| `code` | Business code; **`0` = success** |
| `success` | Whether the call succeeded |
| `msg` | Message |
| `data` | Payload |

Recommended error-code ranges: `1000-1999` client/parameter, `2000-2999` business failure, `5000+` server error. You may also return a bare type (e.g. `ByteArray` + `produces = [IMAGE_PNG]`) for pass-through.

> Gateway-side auth / rate-limit / billing errors (invalid key, quota exceeded, etc.) are returned automatically in the `ApiResponse` shape (reusing the gateway's own error codes, `code != 0`).

### 6.3 Data isolation and interaction: `PluginContext`

Plugins **never touch the gateway datasource or MyBatis**; they interact with the gateway through `PluginContext`:

| Method | Description |
|---|---|
| `datasource` | The plugin's own independent datasource (configured by the admin in `t_s_plugin_datasource`); null when not configured |
| `currentUserId()` / `currentAccessKeyId()` | Current caller |
| `getBalance(userId)` | Query a user's balance |
| `getInterfaceInfo(code)` | Query an API's runtime configuration |
| `getSetting(key)` / `saveSetting(key, value)` | Plugin-scoped settings (persisted by the gateway) |

If you need independent storage: ask the admin to configure a datasource for the plugin (an independent MySQL schema, or one SQLite file per plugin). Passwords are stored encrypted, and the datasource is registered only in that plugin's own child container.

### 6.4 Lifecycle: `PluginLifecycle`

```kotlin
@Component
class GeoLifecycle : PluginLifecycle {
    override fun onInstall(context: PluginContext) {}    // after install (DDL/seed data)
    override fun onStart(context: PluginContext) {}     // after mount at startup
    override fun onStop(context: PluginContext) {}      // before uninstall/shutdown
    override fun onUninstall(context: PluginContext) {} // after uninstall
}
```

Reference it in `apiPlugin { mainClass = "…" }`, or let the gateway auto-discover it. `echo/`'s `EchoLifecycle` writes one plugin-scoped setting per hook (`installedAt` / `startedAt` / `stoppedAt` / `uninstalledAt`), demonstrating `PluginContext.saveSetting`.

### 6.5 Bundling your own dependencies

A plugin may **package its own third-party libraries** (isolated by a child-first ClassLoader). Only the following prefixes are delegated to the gateway's parent loader (so annotation/serialization class identity stays consistent): `java.* javax.* jakarta.* org.springframework.* tools.jackson.* com.fasterxml.* io.swagger.* org.springdoc.* com.baomidou.* kotlin.* kotlinx.* org.slf4j.* top.fatweb.apimanagement.*`. Every other class name is loaded from the plugin jar first. To bundle dependencies, use a shadow/fat jar to merge dependency classes into the plugin jar.

---

## 7. Build → upload → authorize → call (full flow, using `echo/`)

```shell
# 1. Build and sign (first run auto-generates keys/, descriptor and pub.pem)
cd echo
./gradlew build
# artifact build/libs/echo-1.0.0.jar (already signed); public key in keys/public.pem

# 2. Admin adds the trusted public key (first time; contents from keys/public.pem)
POST /system/api/plugin/key   {"publicKey": "<...>", "alias": "FatttSnake"}

# 3. Upload and install
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar)

# 4. Grant APIs to a user
GET  /user/api/key/available-apis                 # optional APIs grouped by plugin
POST /user/api/key   {"permissionCodes": ["api:echo:v1:ping", "api:echo:v1:version", ...]}

# 5. Call (Basic auth accessKey:secretKey)
GET /api/echo/v1/ping     Authorization: Basic <base64(accessKey:secretKey)>
GET /api/echo/v1/version
GET /api/echo/v2/ping     # v2 overrides v1's ping (rolling-compat example)
GET /api/echo/v2/whoami   # v2 only
```

Expected response example (`/api/echo/v2/ping`):

```json
{ "code": 0, "success": true, "msg": "OK", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

---

## 8. Administrative operations summary

| Operation | How | Description |
|---|---|---|
| Disable / re-price | `PUT /system/api/plugin` | `enable: false` takes effect immediately; calls return `Api disabled` |
| Upgrade | bump `apiPlugin.versionCode`, rebuild and upload | the old version is replaced automatically; `versionCode <= current` is rejected |
| Uninstall | `DELETE /system/api/plugin/{pluginId}` | routes unregistered, DB row soft-deleted, permission tree cleaned |
| Restart | nothing to do | installed plugins re-mount automatically from blob; no re-upload |

---

## 9. FAQ

- **Change identity / revoke old installs**: delete `keys/private.pem` and rebuild — a new public key is minted; send the new `keys/public.pem` to the admin to update the trust store (old plugins are not re-mounted after restart).
- **`Plugin not trusted`**: the public key has not been uploaded to the trust store, or it has been disabled; add and enable it via `POST /system/api/plugin/key`.
- **`Version code N is not greater than current M`**: a higher version of the same `pluginId` already exists; bump `apiPlugin.versionCode` to upgrade.
- **The jar from `./gradlew jar` is unsigned**: signing runs as part of `build` / `signPlugin` only — use the `./gradlew build` artifact.
- **`verifyPlugin` fails**: the public key in the jar does not pair with the signing private key (e.g. one of the files was deleted) — delete the whole `keys/` and rebuild.
- **Unique-key conflict while writing settings after reinstall**: make sure the gateway is upgraded to a build that includes the `t_s_plugin_setting unique(plugin_id, setting_key, deleted)` fix.
- **APIs disappear after an upgrade**: the new jar must still contain every `@ApiController` you want to keep; after the old version is uninstalled only the APIs declared by the new jar remain.

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

The plugin contributes five tasks in the `api plugin` group: `generatePluginDescriptor` and `preparePluginPubKey` (both wired into `jar`, so a build runs them for you), `genPluginKeys`, `signPlugin` (wired into `build`) and `verifyPlugin`. The three above are the ones worth running by hand.

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
| `mainClass` | | FQCN of the `PluginLifecycle` implementation (needs `@Component`, and must fall inside a scanned package — see §6.1); auto-discovered when absent, and a value matching no bean quietly falls back to the first lifecycle bean instead of failing |

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
# Expected output: Signature valid: echo-1.0.0.jar
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

- Controllers take `PluginContext` and their own `@Service` via **constructor injection**; the child container wires them automatically. Its scanning is narrower than a Spring Boot application's: the base packages are the jar's own top-level packages that lead to one of your `@ApiController` packages, and it looks for `@Component` (so `@Service` / `@Configuration` count). **A component outside those roots is never discovered** — no error, it is simply not there — so keep your collaborators in the same package tree as your controllers. If no root can be derived at all, the install fails with `Cannot derive scan packages`.
- That child container holds your beans plus the `pluginContext` and `pluginDataSource.<name>` beans the gateway registers, and nothing else: it is a plain `GenericApplicationContext`, with no auto-configuration of any kind. In particular nothing turns scheduling on, so a `@Scheduled` method is never processed unless your own configuration enables it.
- Plugin-level metadata (`name` / `description` / `author` / version) is declared **once** in `apiPlugin { }` (→ `api-plugin.json`); `@ApiController` only declares which plugin (`plugin`, must equal the descriptor `pluginId`) and version (`version`) each controller belongs to.
- Each endpoint's display `name` = `@Operation.summary` (fallback: method name) and `description` = `@Operation.description` (fallback: empty); they surface in the permission tree and the interface table.
- Every endpoint method carries an HTTP mapping annotation; only `@GetMapping` / `@PostMapping` / `@PutMapping` / `@PatchMapping` / `@DeleteMapping` are resolved. A method-level `@RequestMapping` is **not** one of them — the method is skipped, the rest of the install proceeds as usual, and the caller gets a 404. `operationId` (defaults to the method name) defines the API permission code: `api:{plugin}:v{version}:{operationId}`.
- `@ApiController(path = [...])` adds a base path after the version segment, and **only the first element is used**: `/api/{plugin}/v{version}/<path[0]>/<method path>`.
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

> Gateway-side auth / rate-limit / billing errors (invalid key, quota exceeded, etc.) are returned automatically in the `ApiResponse` shape, carrying the gateway's own codes with `code != 0`. Those plugin-related ones live in the 4xxxx range — `40063` install failed, `40064` signer not trusted, `40065` signature invalid, `40066` version conflict, `40067` datasource invalid — so **do not put your own business codes in 4xxxx**: the two spaces overlap numerically inside the same envelope, and nothing distinguishes them but which side emitted them.

### 6.3 Data isolation and interaction: `PluginContext`

Plugins **never touch the gateway datasource or MyBatis**; they interact with the gateway through `PluginContext`:

| Method | Description |
|---|---|
| `datasources` | The plugin's own independent datasources, by declared name (`Map<String, DataSource>`). A name that is absent is one the gateway has nothing to connect with yet (see below) |
| `storage` | The plugin's own file area, `PluginStorage` (see §6.7) |
| `currentUserId()` / `currentAccessKeyId()` | Current caller |
| `getBalance(userId)` | Query a user's balance |
| `getInterfaceInfo(code)` | Query an API's runtime configuration |
| `getSetting(key)` | Read a setting: an admin config value (falling back to its schema default) or the plugin's own runtime key (see §6.6) |
| `saveSetting(key, value)` | Write a runtime key; **writing a key declared by the config schema raises `IllegalArgumentException`** |

**About `datasources`**: a plugin is given an independent datasource only when it declares it in the `datasources` array of its own `plugin-config.json`, and each is keyed by the `name` it was declared under. Without the declaration nothing is ever injected, whatever rows exist, and the admin API refuses to store a configuration for that plugin. The datasources are registered only in that plugin's own child container; the gateway's own datasource is never exposed to plugins.

The declaration carries a `dbType`, and the two dialects are supplied differently:

- **`SQLITE` needs no configuration at all**: declaring it is what supplies it. The gateway provisions `app.storage.plugin-datasource-dir/{pluginId}/{name}.db` while mounting, stores no row and asks the administrator for nothing. Both path segments come from the declaration, so no submitted value ever reaches the path.
- **`MYSQL` is described by ordinary config fields**, each connection slot of the declaration naming the config key that holds it: `host`, `port`, `database`, `username`, `password` and `params`. The gateway composes the JDBC URL and hands `params` to the driver as connection properties, so neither the plugin author nor the administrator writes a connection string. **A datasource is absent from `context.datasources` until its host has a value**, and the plugin should say so rather than fail obscurely.

A plugin may declare several datasources, and may mix the two dialects. The gateway builds them **while mounting**, and saving a change to one of the config values behind a datasource remounts the plugin on its own (see §8) - so a plugin has to expect `onStop` / `onStart` to run again after such a save, and anything it holds only in memory is lost with them.

### 6.4 Lifecycle: `PluginLifecycle`

```kotlin
@Component
class GeoLifecycle : PluginLifecycle {
    override fun onInstall(context: PluginContext) {}    // after every mount (DDL / seed data)
    override fun onStart(context: PluginContext) {}      // after every mount, right after onInstall
    override fun onStop(context: PluginContext) {}       // before every unmount
    override fun onUninstall(context: PluginContext) {}  // after the plugin is taken out
}
```

Reference it in `apiPlugin { mainClass = "…" }`, or let the gateway auto-discover it. `echo/`'s `EchoLifecycle` writes one plugin-scoped setting per hook (`installedAt` / `startedAt` / `stoppedAt` / `uninstalledAt`), demonstrating `PluginContext.saveSetting`.

**Discovery fails quietly, so check it deliberately.** A `PluginLifecycle` only runs if it is a Spring bean the child container actually scanned (see §6.1) — one parked outside your controllers' package tree is never instantiated, every hook is silently skipped, and the install still reports success. `mainClass` is a preference rather than a requirement: a value matching no bean fails nothing, the gateway falls back to the first `PluginLifecycle` bean it finds (which starts to matter as soon as you have more than one), and if there is no lifecycle bean at all the plugin mounts with no lifecycle whatsoever.

**A mount is not a one-off, so these hooks are not either.** The gateway mounts a plugin when it is installed, when it starts and re-hydrates it, on `reload`, and whenever a save touches a value one of its datasources is composed from — and every mount runs `onInstall` and then `onStart`. A remount tears the plugin down first, so expect your own `onStop` in the middle of your plugin's life and keep nothing you need in memory alone. `onUninstall` is worth reading twice: **an upgrade uninstalls the old version before installing the new one**, so it runs then too — never treat it as "the plugin is gone for good" and destroy data there. The gateway purges a plugin's settings, its SQLite databases and its files only when an uninstall explicitly asks for it (`purgeData=true`), and an upgrade never does.

### 6.5 Bundling your own dependencies

A plugin may **package its own third-party libraries** (isolated by a child-first ClassLoader). Only the following prefixes are delegated to the gateway's parent loader (so annotation/serialization class identity stays consistent): `java.* javax.* jakarta.* sun.* jdk.* org.springframework.* org.slf4j.* tools.jackson.* com.fasterxml.* io.swagger.* org.springdoc.* com.baomidou.* kotlin.* kotlinx.* top.fatweb.apimanagement.*`. Every other class name is loaded from the plugin jar first. To bundle dependencies, use a shadow/fat jar to merge dependency classes into the plugin jar.

> The SDK declares `spring-web` / `spring-context` / `swagger-annotations` and **no logging dependency**: add `slf4j-api` to compile against it, but `org.slf4j.` is delegated upwards, so at runtime your logging calls land on the gateway's binding and the copy you bundle is never loaded. Release whatever else you hold yourself in `onStop`.

### 6.6 Declaring your configuration: `META-INF/plugin-config.json`

A plugin declares in its own jar *which* settings an administrator owns, how they render and what their defaults are. The gateway reads that file **while mounting**, snapshots it onto the plugin row, and the admin API serves that declaration with the current values folded into it - the console is what renders the form from the result. The plugin itself still just reads `context.getSetting(key)` - **no plugin code changes for configuration**.

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
      "title": "Database",
      "fields": [
        { "key": "db.host", "type": "string", "title": "Host" },
        { "key": "db.port", "type": "number", "title": "Port",
          "default": 3306, "minimum": 1, "maximum": 65535, "integer": true },
        { "key": "db.name", "type": "string", "title": "Database" },
        { "key": "db.user", "type": "string", "title": "Username" },
        { "key": "db.password", "type": "secret", "title": "Password" },
        { "key": "db.params", "type": "string", "title": "Driver parameters" }
      ]
    },
    {
      "key": "share",
      "title": "Sharing",
      "fields": [
        { "key": "ttlHours", "type": "number", "title": "Link life (hours)",
          "default": 1, "minimum": 1, "maximum": 168, "integer": true },
        { "key": "allowSnapshot", "type": "boolean", "title": "Enable snapshots", "default": true },
        { "key": "mode", "type": "enum", "title": "Mode", "default": "fast",
          "options": [ { "value": "fast", "label": "Fast" }, { "value": "slow" } ] },
        { "key": "notice", "type": "text", "title": "Notice", "maxLength": 200 },
        { "key": "uploadToken", "type": "secret", "title": "Upload token" }
      ]
    }
  ]
}
```

A datasource entry takes:

| Field | Description |
|---|---|
| `name` | The name the plugin asks its datasource by: `^[a-z][a-z0-9-]*$`, unique in the plugin. It is also a path segment and a bean-name suffix, which is why it is held to a plain lowercase word |
| `dbType` | `mysql` / `sqlite`, required. It decides how the gateway supplies the datasource |
| `required` | Whether the plugin cannot run without it. **Reporting only** - it never makes a config field required, and the plugin still mounts without one |
| `host` `port` `database` `username` `password` `params` | `mysql` only: the config key holding each connection fact. `password` must be a `secret` field, every other slot a `string` / `text` one, and `port` a `number` one |

A `mysql` datasource has to name at least `host` and `database`; a `sqlite` one names no slot at all. One config key cannot be claimed by two slots or two datasources, a referred key has to exist, and a `password` slot may not point at a `required` field - a required field refuses a blank submission, so the password could then never be cleared.

| Field | Description |
|---|---|
| `key` | Config key: `^[A-Za-z][A-Za-z0-9._-]*$`, at most 100 characters, unique in the plugin; it is the setting key in `t_b_api_plugin_setting` |
| `type` | `string` / `text` / `number` / `boolean` / `enum` / `secret`. **Every value is a string** on the wire and in storage (a boolean is `"true"` / `"false"`) |
| `title` / `description` / `placeholder` | Display text |
| `default` | Default value; **never stored as a value row**, it is what an unset key reads as. A secret must not declare one |
| `required` | Whether the field must resolve to a value: a key that was submitted has to carry a non-blank one, while a key that was left out is satisfied by a stored value or a declared default. Checked **only for the groups this submission names** - a group left out is one nothing was decided about |
| `minimum` / `maximum` / `integer` | `number` only |
| `minLength` / `maxLength` / `pattern` | `string` / `text` / `secret` only |
| `options` | `enum` only, non-empty and with unique values |

Worth knowing:

- **The `datasources` array is the only declaration of an isolated database**: each entry names a config key per connection fact, so those facts are settings like any other and get the form, the constraints and secret encryption for free. **No entry means no isolated database**: nothing is built or injected, and the admin API refuses to store one. See §6.3.
- **A default is declared once, in the schema**: the database only stores what the administrator has explicitly overridden, and `getSetting` falls back to the declared default. That is what lets a plugin upgrade change a default, and what saves your code from repeating it.
- **Ownership**: a key declared here belongs to the administrator, and the plugin's `saveSetting` raises on it. Keys you do not declare stay yours (`installedAt` and friends keep working).
- **Secrets** are encrypted at rest (AES-GCM, key derived from `app.security.tokenSecret`) and **never returned by the API**: a secret's `value` is always `null`, and `hasValue` is the only thing that says whether one is stored - which is why keeping a secret means **leaving its key out of the submission**, sending any other value replaces it, and sending a blank clears it. **There is no mask to echo back**: a console that invents a `******` of its own would overwrite the stored secret every time it saved. The plugin reads secrets back as plaintext either way. `unreadable: true` marks a stored secret the gateway can no longer decrypt, because `tokenSecret` was rotated after it was written - re-entering the value is the only fix, and until then the plugin's own `getSetting` raises `IllegalStateException` on that key. Rotating `tokenSecret` invalidates every secret and every external link at once. Note that `hasValue` is present on **every** field, not just secrets: it says whether the administrator has set a value of their own, as opposed to the declared default being read.
- **Validation is server-side and authoritative**: an undeclared key, a wrong type, an out-of-range number or an enum value outside its options is rejected. A submission that names the same group or the same key twice is refused as a structural mistake, and because the values behind a datasource are what the gateway builds a connection from, a save is also held to the connection rules before anything is written (host shape, port range, database name, and the driver-parameter deny list) - a combination that could never connect is refused rather than stored.
- **An unparsable schema fails the install** (deliberate fail-fast). No file means no configuration; a broken file means the install is refused with the reason - and so is a schema that duplicates a group or field key, or declares a `mysql` datasource without both a `host` and a `database` slot.
- **When it takes effect**: an ordinary config value is visible on the plugin's very next read, so nothing has to be remounted. A value a datasource is composed from is different - the gateway builds the connection, so changing one remounts the plugin (see §8). That is decided on the change rather than on the request, so saving an unrelated value does not restart your plugin.
- **Editor and build feedback**: a JSON Schema mirroring these checks ships with the gateway at [`docs/plugin-config.schema.json`](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-config.schema.json). Point your editor or a build step at its **raw** URL, `https://raw.githubusercontent.com/FatttSnake/api-management/master/docs/plugin-config.schema.json` (a `.vscode/settings.json` `json.schemas` entry with `fileMatch: ["**/META-INF/plugin-config.json"]`, a JetBrains JSON Schema mapping, or `check-jsonschema` in CI). Use the raw URL rather than the `github.com/.../blob/...` page linked above: the blob page serves HTML, and a consumer handed it fails to load the schema **silently** - JetBrains keeps listing the mapping in the status bar while completion and validation never arrive. What you must **not** do is reference it from inside the file: a `$schema` key is itself an unsupported property, and the gateway accepts only `groups` and `datasources` at the top level - it would refuse the install.

### 6.7 File storage and external links: `PluginStorage`

`context.storage` is a plugin's only file channel, and it offers **two addressing modes that are not interchangeable**:

| | Content addressing | Location addressing |
|---|---|---|
| Methods | `saveContent` / `loadContent` / `existsContent` / `deleteContent` | `saveFile` / `loadFile` / `existsFile` / `deleteFile` / `fileExternalUrl` |
| Key | Content SHA-256 (also the return value) | A relative path the plugin picks |
| Stored as | zstd-compressed, one object per distinct content | Verbatim bytes |
| External link | **Not possible** (compressed objects cannot be handed to a browser) | Yes, via `fileExternalUrl(path, ttl)` |
| Good for | Archives, deduplication, objects only the plugin reads | Files somebody downloads or a browser opens |

```kotlin
// Location addressing: stored verbatim, can be linked
val path = "files/$ownerId/$id.png"
context.storage.saveFile(path, bytes)
val url = context.storage.fileExternalUrl(path, Duration.ofHours(2))  // null when the gateway cannot build an absolute URL

// Content addressing: identical content is stored once
val key = context.storage.saveContent(bytes)   // returns the SHA-256
context.storage.existsContent(key)             // ask before writing whether these bytes are already there
context.storage.loadContent(key)
```

- **Reference counting**: every `saveContent` raises it and **needs exactly one `deleteContent`**. Deleting only decrements the count; the gateway reclaims the bytes.
- **Path rules**: a `/`-separated relative path whose segments must match `^[A-Za-z0-9][A-Za-z0-9._-]*$` and be at most **200** characters, must not be `.` / `..` or a Windows device name (`con`, `prn`, `aux`, `nul`, `com1`-`com9`, `lpt1`-`lpt9`, with or without an extension), and must not contain `\` or `:`. The whole path is capped at **1024** characters. **Never splice a user-supplied file name into a path** - use a UUID plus a filtered extension and keep the original name in your database.
- **Namespace**: every path resolves under `data/files/plugin-data/{pluginId}/`; both the signature and the key are composed from the plugin ID, so **no call can reach another plugin or the gateway's own files**. An escaping path is rejected rather than silently followed.
- **Link semantics**: a login-free bearer credential - readable by whoever holds it until it expires, and **it cannot be revoked** before then. Deleting the file makes it fail immediately. A ttl above the gateway's `external-url-max-ttl` is **clamped** down, while a zero or negative one is **rejected** - a link that never works is never what a caller means; leave the argument out to get the configured default. In `local` mode you get an HMAC-signed `/public/storage/{pluginId}/{path}?e=&s=` URL; in `s3` mode, a presigned object-store URL.
- **Links share the console's origin in `local` mode**, so every type outside the image whitelist is served as `Content-Disposition: attachment` with `nosniff`.

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
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar, at most 50 MB)

# 3.1 A plugin that declares a SQLite datasource (filebox's `cache`, for instance): it is
#     ready on install, and there is nothing to configure
GET  /system/api/plugin/filebox/config
#    -> datasources: [ {name:"main", dbType:"MYSQL", required:true, configured:false, keys:[...]},
#                      {name:"cache", dbType:"SQLITE", required:false, configured:true, keys:[]} ]
#       the SQLite file is data/db/plugin/filebox/cache.db

# 3.2 A plugin that declares a MySQL datasource: its connection facts are ordinary config
#     values, so they are saved through the config endpoint - and saving them remounts the
#     plugin on its own, no reload needed. A save is written a group at a time, and a value
#     left out is one nothing is decided about
PUT  /system/api/plugin/config
     {"pluginId":"filebox","groups":[
        {"key":"db","values":[
           {"key":"db.host","value":"10.0.0.240"},
           {"key":"db.port","value":"3306"},
           {"key":"db.name","value":"filebox"},
           {"key":"db.user","value":"filebox"},
           {"key":"db.password","value":"…"},
           {"key":"db.params","value":"useSSL=false&serverTimezone=UTC"}]}]}

# 3.2.1 Try the connection before saving it (values left out are read from what is stored)
POST /system/api/plugin/filebox/config/datasource/test
     {"name":"main","values":[{"key":"db.host","value":"10.0.0.240"},{"key":"db.name","value":"filebox"}]}

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
{ "code": 0, "success": true, "msg": "success", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

---

## 8. Administrative operations summary

| Operation | How | Description |
|---|---|---|
| Disable / re-price | `PUT /system/api/plugin` | `enable: false` takes effect immediately; calls return `Api disabled` |
| Read configuration | `GET /system/api/plugin/{pluginId}/config` | returns the declared schema with the current values folded in - per field `value`, `default`, `hasValue` and `unreadable`, with a secret's `value` always `null` - plus the settings each datasource is composed from, and every declared datasource's `name`, `dbType`, `required` and `configured` |
| Save configuration | `PUT /system/api/plugin/config` | a group at a time: `{pluginId, groups:[{key, values:[{key,value}]}]}`. An ordinary value takes effect immediately; a value left out keeps the stored one, and a blank clears the key (a blank is a value of its own to a text field). Required fields are checked only for the groups the save names. **Changing a value a datasource is composed from remounts the plugin**, so no separate step is needed |
| Test a connection | `POST /system/api/plugin/{pluginId}/config/datasource/test` | `{name, values}`; a value left out, or sent with no value at all, is read from what is stored. **MySQL only** (a SQLite datasource is a file the gateway supplies); a value belonging to another datasource, or an unconfigured `main`, is refused, and a genuine failure answers 40067 with the driver's reason |
| Configure a datasource | the config endpoint above | a MySQL datasource is described by the declared fields named in its `datasources` entry. Blanking the host un-configures it, which is not an error - the plugin simply stops being given it |
| Reload | `POST /system/api/plugin/{pluginId}/reload` | re-mounts the installed jar in place, keeping the version, DB rows, permission tree, configuration and data. For **a jar that has been replaced**; a datasource change does not need it |
| Upgrade | bump `apiPlugin.versionCode`, rebuild and upload | the old version is replaced automatically; `versionCode <= current` is rejected. **An upgrade keeps the plugin's configuration and data** (settings, datasources, file area), and does not re-enable a plugin the administrator disabled - the same holds for `reload` and for a datasource-changing config save. Internally it uninstalls the old version before installing the new one, so the plugin sees `onStop` → `onUninstall` → `onInstall` → `onStart` and must not treat `onUninstall` as the end of its life (see §6.4) |
| Uninstall | `DELETE /system/api/plugin/{pluginId}?purgeData=false` | routes unregistered, DB row removed, permission tree cleaned. `purgeData` defaults to `false`, which **keeps** the plugin's settings, its SQLite database directory and its files, so reinstalling the same plugin ID picks up where it left off; pass `true` to purge them. **An external MySQL database is not on that list** - the gateway never drops tables in a database it does not own |
| Restart | nothing to do | installed plugins re-mount automatically from blob; no re-upload |

---

## 9. FAQ

- **Change identity / revoke old installs**: delete `keys/private.pem` and rebuild — a new public key is minted; send the new `keys/public.pem` to the admin to update the trust store (old plugins are not re-mounted after restart).
- **`Plugin signer is not trusted`**: the public key has not been uploaded to the trust store, or it has been disabled; add and enable it via `POST /system/api/plugin/key`. (A jar with no signature at all answers `Plugin signature invalid` instead - check that you uploaded the `build` artifact and not the one from `./gradlew jar`.)
- **`Version code N is not greater than current M`**: a higher version of the same `pluginId` already exists; bump `apiPlugin.versionCode` to upgrade.
- **The jar from `./gradlew jar` is unsigned**: signing runs as part of `build` / `signPlugin` only — use the `./gradlew build` artifact.
- **`verifyPlugin` fails**: the public key in the jar does not pair with the signing private key (e.g. one of the files was deleted) — delete the whole `keys/` and rebuild.
- **Unique-key conflict while writing settings**: the settings live in `t_b_api_plugin_setting`, whose unique key is `(plugin_id, setting_key)` - one row per plugin and key, with no soft-delete column. There is no unique-key conflict to fix on the gateway side: `saveSetting` reads before it writes, and since settings survive a reinstall when `purgeData=false`, a plugin that writes its own runtime keys while also having them declared in its config schema is the thing to look for (a declared key is administrator-owned and is refused by `saveSetting`).
- **APIs disappear after an upgrade**: the new jar must still contain every `@ApiController` you want to keep; after the old version is uninstalled only the APIs declared by the new jar remain.
- **The plugin installed, but no lifecycle hook runs / my own `@Service` is not injected**: the component sits outside the packages the child container scans (§6.1) — put it in the same package tree as your `@ApiController`. A `mainClass` naming a class that is not a bean does not fail either; the gateway quietly uses the first `PluginLifecycle` bean it finds (§6.4).
- **An endpoint 404s**: a method-level `@RequestMapping` is not resolved (§6.1) — use `@GetMapping` / `@PostMapping` / … . Also check that `@ApiController.plugin` equals the descriptor `pluginId`, and that the version in the URL is **at least** the controller's declared `version`.
- **A `@Scheduled` method never fires**: the plugin's child container is not auto-configured and enables no scheduling (§6.1) — turn it on in your own configuration if you need it.
- **How do I see why a plugin did not mount?**: mounting failures are recorded on the plugin row and surface as its `loadError` field, `null` when it loaded — readable from the plugin list or `GET /system/api/plugin/{pluginId}/info`. A plugin that is listed and enabled yet behaves as if it were absent is usually the scan or lifecycle-discovery problem in the two items above, not a failed install.

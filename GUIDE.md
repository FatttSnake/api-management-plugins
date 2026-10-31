# API Plugin Development Guide

This guide is for **plugin developers**. An API is authored, packaged and signed independently as a **plugin jar**, uploaded to the gateway and **hot-plugged** (install / disable / upgrade / uninstall) with automatic re-mounting after restart. `echo/` and `filebox/` in this repository are complete, runnable examples — reading them side by side with this document works best.

**Writing a plugin is one `build.gradle.kts` (~10 lines) + your Kotlin sources.** Signing, keys, the descriptor, the dependency lock and OpenAPI metadata are all handled by the Gradle plugin `top.fatweb.api-plugin`.

- Contract artifact: `top.fatweb:api-management-gateway-api` (added automatically by the Gradle plugin)
- Gradle plugin: `top.fatweb.api-plugin`
- API package: `top.fatweb.apimanagement.api.v1`
- Gateway / console: [api-management](https://github.com/FatttSnake/api-management) · [api-management-console](https://github.com/FatttSnake/api-management-console)

---

## 0. Prerequisite: publish the contract and Gradle plugin

The Gradle plugin and the contract are built and published from the **gateway repository**. Before first use, run this once inside [api-management](https://github.com/FatttSnake/api-management):

```shell
./gradlew :gateway-api:publishToMavenLocal :plugin-signing:publishToMavenLocal :plugin-gradle-plugin:publishToMavenLocal
```

A plugin project resolves them by adding `mavenLocal()` under `pluginManagement` in its `settings.gradle.kts`. If you publish to a private Nexus instead, add that repository likewise.

Re-run it after pulling a newer gateway so the Gradle plugin and the tasks it contributes are current.

---

## 1. Overall model

```
Plugin project (standalone Gradle build)           Gateway (api-management)
┌──────────────────────────────────────┐        ┌──────────────────────────────────────────┐
│ Kotlin sources (ApiPlugin entry)     │ upload │ PluginSigner.verify (integrity)          │
│ build.gradle.kts declares apiPlugin  │──────▶│ Trust store check (keyId present, enabled)│
│                                      │        │ PluginClassLoader + module loaders        │
│ Build time:                          │        │ ApiPlugin.onStart → routes                │
│  ├ api-plugin.json                   │        │ Route registry + ApiAccessInterceptor     │
│  ├ api-plugin-deps.json (lock)       │        │ DB upsert (plugin/API/permission tree)    │
│  ├ module jars                       │        │ Re-mount from the stored jar on startup   │
│  ├ plugin.pub.pem                    │        └──────────────────────────────────────────┘
│  └ Ed25519 signature                 │
└──────────────────────────────────────┘
```

The plugin jar is thin: its runtime dependencies are separate module jars named by the lock. The gateway stores modules content-addressed, verifies SHA-256, and gives a plugin one class loader per loading unit.

### Security boundary (read this carefully)

Plugin code runs **inside the gateway JVM**; no data isolation can stop malicious code. **The real anti-malware boundary is the mandatory Ed25519 signature plus the administrator-maintained trust store.** Data isolation (plugin-owned datasources, a narrow `GatewayContext`) is defense-in-depth against accidents, not against malice.

- **The private key is the root of identity** — whoever holds it can publish plugins as you. It lives only in your `keys/private.pem`; **never commit it or leak it**.
- Submit `keys/public.pem` to the gateway administrator; an untrusted or disabled key is refused at install.
- **Deleting `keys/private.pem` and rebuilding changes your identity**: previously installed plugins will not be re-mounted after restart because the signer is no longer trusted.

---

## 2. Scaffold: the minimal plugin project

Create a directory (e.g. `geo/`) with three files plus your sources:

```
geo/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ keys/            # auto-generated on first build (never commit private.pem)
└─ src/main/kotlin/com/example/geo/GeoPlugin.kt
```

### 2.1 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()   // resolves top.fatweb.api-plugin and the gateway-api snapshot
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

version = "1.0.0"                                 // also the default versionName

apiPlugin {
    pluginId = "geo"                              // ^[a-z][a-z0-9-]{0,63}$
    pluginName = "Geo Plugin"
    versionCode = 1                               // required; higher upgrades, same redeploys in place
    description = "Expose geocoding as an API"    // optional
    author = "Your name"                          // optional
    mainClass = "com.example.geo.GeoPlugin"       // required: the ApiPlugin implementation
    requiresGateway = ">=1.0.0 <2.0.0"            // default
    requiresGatewayApi = ">=1.0.0-SNAPSHOT <2.0.0" // default is ^1.0
}
```

| DSL field | Required | Default | Descriptor field |
|---|---|---|---|
| `pluginId` | | project name | `pluginId` |
| `pluginName` | | same as `pluginId` | `name` |
| `versionName` | | project `version` | `versionName` |
| `versionCode` | ✅ | — | `versionCode` |
| `description` / `author` | | empty | same name |
| `mainClass` | ✅ | — | `mainClass` |
| `requiresGateway` | | `>=1.0.0 <2.0.0` | `requiresGateway` |
| `requiresGatewayApi` | | `^1.0` | `requiresGatewayApi`; its minimum major is also the compile-time gateway-api version |
| `archiveName` | | `<pluginId>-<versionName>.jar` | jar file name |
| `sharedLoader(vararg group:artifact)` | | none | lock `groups` |

### 2.3 `.gitignore`

```gitignore
keys/            # the private key must never be committed
build/
.gradle/
```

### 2.4 Build tasks

```shell
./gradlew build           # keys + descriptor + lock + modules + pub.pem + signature
./gradlew verifyPlugin    # self-check the jar signature
./gradlew deployPlugin    # build + sign + verify + upload modules and jar
```

Artifact: `build/libs/<pluginId>-<versionName>.jar`, already signed. Dependency modules are under
`build/generated/api-plugin-modules/`.

The plugin contributes `generatePluginLock`, `generatePluginDescriptor`, `genPluginKeys`,
`preparePluginPubKey`, `checkPluginAbi`, `signPlugin`, `verifyPlugin` and `deployPlugin`.
`generatePluginLock`, `generatePluginDescriptor` and `preparePluginPubKey` are wired into `jar`;
`checkPluginAbi` is wired into `check`, and `signPlugin` into `build`.

`deployPlugin` is §7's upload step without the browser:

```shell
./gradlew deployPlugin -PapiPlugin.serverUrl=http://localhost:8080 -PapiPlugin.accessToken=<token>
```

Both settings also read an environment variable (`API_PLUGIN_SERVER_URL`, `API_PLUGIN_ACCESS_TOKEN`), and neither belongs in `build.gradle.kts`. The token is the `accessToken` a console login returns. Add `-PapiPlugin.trustSigner=true` to register the jar's signer in the same run.

For breakpoints in plugin source, see the gateway's [docs/plugin-debugging.md](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-debugging.md).

### 2.5 Manual approach (without the Gradle plugin)

The Gradle plugin is the supported path: it generates the descriptor and lock, wires the jar and signs it. A hand-maintained build has to reproduce all of that — descriptor at `META-INF/api-plugin.json`, lock at `META-INF/api-plugin-deps.json`, public key at `META-INF/plugin.pub.pem`, signature at `META-INF/plugin.sig`, and the module jars — and is out of scope for this guide.

---

## 3. Descriptor

Generated by the Gradle plugin from `apiPlugin { }` at build time. `echo/` produces:

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

| Field | Required | Description |
|---|---|---|
| `format` | ✅ | Descriptor format, currently `1` |
| `pluginId` | ✅ | Unique ID, `^[a-z][a-z0-9-]{0,63}$`; namespaces every endpoint as `/api/{plugin}/v{version}/…` |
| `name` | ✅ | Display name (menus / permission tree / docs) |
| `versionName` | ✅ | Human-readable version, e.g. `1.2.0` |
| `versionCode` | ✅ | Integer, at least 1. A value **above** the installed one is an upgrade; deploying **the installed one** again replaces it in place; a value **below** it is refused |
| `mainClass` | ✅ | FQCN of the `ApiPlugin` implementation |
| `requiresGateway` / `requiresGatewayApi` | ✅ | Version ranges checked against the running gateway |
| `description` / `author` | | Metadata |

---

## 4. Signing and the trust store

### 4.1 Keys (automatic)

`./gradlew build` (or `genPluginKeys`) generates an Ed25519 key pair into `keys/` on first run:

- `keys/private.pem` (PKCS8) — used only for local signing, **never commit / leak it**
- `keys/public.pem` (SPKI) — submit to the gateway admin

The public key is embedded in the jar as `META-INF/plugin.pub.pem`; `META-INF/plugin.sig` is written
by `signPlugin`.

> **Changing identity**: delete `keys/private.pem` and run `build` again — a fresh pair is generated.

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

The gateway derives `keyId` from the public key (SHA-256 of the SPKI).

| Method | Path | Description |
|---|---|---|
| GET | `/system/api/plugin/key` | List |
| POST | `/system/api/plugin/key` | Add public key |
| PATCH | `/system/api/plugin/key` | Enable / disable |
| DELETE | `/system/api/plugin/key/{keyId}` | Delete |

> Revocation: once the admin deletes or disables your public key, installed plugins are **not re-mounted** on the next restart; the database row is kept and `loadError` records the reason.

---

## 5. Programming model

Plugins do not use Spring MVC annotations and do not get a Spring child context. Implement
`ApiPlugin` and register routes on the `GatewayContext` passed to `onStart`:

```kotlin
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

### 5.1 Routes and versions

- The path is relative to `/api/{pluginId}/v{apiVersion}` and must not contain a query or fragment.
- `apiVersion` must be at least 1. A request may select a **higher** version than the one declared;
  the gateway picks the highest declared version not above the requested one.
- The same path may be declared under different versions: `echo/` serves v1 `ping` / `version` and
  v2 `ping` / `whoami` from one plugin.
- `OperationMetadata.operationId` is the endpoint's stable identity and defines the permission code
  `api:{plugin}:v{version}:{operationId}`. It must not be blank.
- Routes are staged during `onStart` and activated only after it returns normally; a failed
  `onStart` publishes no routes.

### 5.2 Requests and responses

`ApiRequest` exposes `method`, `path`, `apiVersion`, `principal`, `traceId`, `interfaceInfo`,
`body` (raw bytes), `pathParam`, `queryParam` / `queryParams`, `header` and `bodyText()`.

`ApiResponse.ok(data)` wraps the payload in the standard envelope `{code, success, msg, data}`;
`ApiResponse.fail(code, message, data)` returns a business error. `ApiResponse.raw(status,
contentType, bytes)` bypasses the envelope for downloads, and `ApiResponse.text(...)` returns plain
text. `withHeader(...)` and `withHttpStatus(...)` adjust a response.

Recommended error-code ranges: `1000-1999` client/parameter, `2000-2999` business failure, `5000+`
server error. Plugin-related gateway errors live in `4xxxx` (`40063` install failed, `40064` signer
not trusted, `40065` signature invalid, `40066` version conflict, `40067` datasource invalid,
`40068` purge failed) — do not use that range for your own business codes.

### 5.3 OpenAPI metadata

There is no hand-written OpenAPI fragment. On every mount the gateway builds an OpenAPI 3.1
fragment from the routes' `OperationMetadata` and stores it on the plugin row; users get it through
`GET /user/api/docs/{pluginId}`.

Declare what the endpoint accepts and returns:

```kotlin
OperationMetadata(
    operationId = "geocode",
    summary = "Geocode",
    description = "Resolve an address to coordinates",
    parameters = listOf(
        ParameterSpec.query("address", description = "Address to resolve", example = "Shanghai"),
    ),
    requestBodySchema = null,
    responses = mapOf(
        "200" to ResponseSpec(
            description = "Success",
            schema = SchemaSpec.objectOf(
                properties = mapOf(
                    "address" to SchemaSpec.string("Formatted address"),
                    "latitude" to SchemaSpec.number("Latitude", nullable = true),
                    "longitude" to SchemaSpec.number("Longitude", nullable = true),
                ),
                required = listOf("address"),
            ),
            example = mapOf("address" to "Shanghai", "latitude" to 31.23, "longitude" to 121.47),
        )
    ),
)
```

`ParameterSpec` covers query / path / header / cookie parameters; `SchemaSpec` has factories for
objects, arrays, strings, integers, numbers and booleans. The generated `paths` key is the full
public path `/api/{plugin}/v{version}/...`.

### 5.4 Lifecycle

```kotlin
class GeoPlugin : ApiPlugin {
    override fun onStart(context: GatewayContext) {}   // every mount; register routes here
    override fun onStop() {}                           // every unmount, after in-flight requests drain
    override fun onUninstall() {}                      // this version is going away (an upgrade too)
    override fun onPurge() {}                          // a uninstall that asked to delete data
}
```

- `onStart` runs on install, startup recovery, `reload`, a datasource-driven remount, and any save
  while the plugin is down from a failed mount. Keep nothing essential only in memory.
- `onStop` runs after in-flight requests drain; routes and plugin events/schedules are removed first.
- `onUninstall` runs when the declared version changes or the plugin is removed, and on an upgrade.
  It is **not** the place to delete data: it runs on every release upgrade.
- `onPurge` runs only when an administrator requests `purgeData=true`, **before** `onStop`, while
  datasources, storage and settings are still open. If it throws, the uninstall is refused (40068)
  and nothing is deleted.
- A disabled plugin is not loaded at the next startup; enabling it mounts the same jar over the
  same rows.

### 5.5 `GatewayContext`

`GatewayContext` is the only gateway surface. It never exposes the Spring container, beans, mappers
or the gateway object mapper.

| Accessor | Description |
|---|---|
| `api()` | Route registration, valid during `onStart` |
| `settings()` | Plugin-scoped settings: `get`, `getSecret`, `save`, `all` |
| `dataSources()` | Plugin-owned datasources: `names()`, `all()`, `get(name)` |
| `storage()` | Plugin file area: content-addressed and location-addressed objects |
| `users()` / `audit()` / `events()` / `http()` / `scheduler()` | Scoped platform capabilities |
| `json()` / `logger()` / `metadata()` | JSON codec, plugin logger, descriptor and version metadata |

### 5.6 Settings and configuration

A plugin declares **which settings an administrator owns** in `META-INF/plugin-config.json`. The
gateway reads it while mounting, snapshots it onto the plugin row, and the console renders the form
from it. The plugin reads values through `context.settings()`; no plugin code changes for
configuration.

```json
{
  "datasources": [
    { "name": "main", "dbType": "mysql", "required": true,
      "host": "db.host", "port": "db.port", "database": "db.name",
      "username": "db.user", "password": "db.password", "params": "db.params" },
    { "name": "cache", "dbType": "sqlite" }
  ],
  "groups": [
    { "key": "db", "title": "Database", "fields": [
        { "key": "db.host", "type": "string", "title": "Host" },
        { "key": "db.port", "type": "number", "title": "Port", "default": 3306, "minimum": 1, "maximum": 65535, "integer": true },
        { "key": "db.name", "type": "string", "title": "Database" },
        { "key": "db.user", "type": "string", "title": "Username" },
        { "key": "db.password", "type": "secret", "title": "Password" },
        { "key": "db.params", "type": "string", "title": "Driver parameters" }
    ] }
  ]
}
```

- Field types: `string` / `text` / `number` / `boolean` / `enum` / `secret`. Every value is a string
  on the wire and in storage.
- A `default` is declared once and is never stored; `settings().get(key)` falls back to it.
- A declared key is administrator-owned: `settings().save(...)` refuses it. Undeclared keys belong
  to the plugin (for example `startedAt`).
- Secrets are encrypted at rest and never returned by the admin API: `value` is `null`, `hasValue`
  says whether one is stored. Leaving a secret key out of a save keeps it; a blank clears it.
  `settings().getSecret(key)` reads it as plaintext.
- A `mysql` datasource is configured once **both** its host and database have values; blanking
  either un-configures it, not an error. A `sqlite` datasource needs no configuration at all.
  Without a `datasources` entry a plugin gets no datasource, whatever rows exist.
- An ordinary value takes effect on the next read without a remount. Saving a value a datasource is
  composed from **remounts** the plugin, because the gateway builds the connection.
- The JSON Schema at
  [`docs/plugin-config.schema.json`](https://github.com/FatttSnake/api-management/blob/master/docs/plugin-config.schema.json)
  mirrors these checks for editors and CI. Do not add `$schema` to the file itself: the gateway
  accepts only `groups` and `datasources` at the top level.

### 5.7 File storage and external links

`context.storage()` offers two addressing modes that are not interchangeable:

| | Content addressing | Location addressing |
|---|---|---|
| Methods | `saveContent` / `loadContent` / `existsContent` / `deleteContent` | `saveFile` / `loadFile` / `existsFile` / `deleteFile` / `fileExternalUrl` |
| Key | Content SHA-256 (also the return value) | A relative path the plugin picks |
| Stored as | zstd-compressed, one object per distinct content | Verbatim bytes |
| External link | Not possible | Yes, via `fileExternalUrl(path, ttl)` |
| Good for | Archives, deduplication, objects only the plugin reads | Files somebody downloads or a browser opens |

```kotlin
val path = "files/$ownerId/$id.png"
context.storage().saveFile(path, bytes)
val url = context.storage().fileExternalUrl(path, Duration.ofHours(2))

val key = context.storage().saveContent(bytes)   // returns the SHA-256
context.storage().deleteContent(key)             // one delete per save
```

- **Reference counting**: every `saveContent` raises it and needs exactly one `deleteContent`.
- **Path rules**: a `/`-separated relative path, segments `^[A-Za-z0-9][A-Za-z0-9._-]*$`, at most
  200 characters per segment and 1024 in total; no `.` / `..`, Windows device names, `\` or `:`.
  Never splice a user-supplied file name into a path.
- **Namespace**: every path resolves under `data/files/plugin-data/{pluginId}/`; no call can reach
  another plugin or the gateway's own files.
- **Link semantics**: a login-free bearer credential that cannot be revoked before it expires;
  deleting the file makes it fail immediately. A ttl above the configured maximum is clamped; a
  zero or negative one is rejected.

### 5.8 Dependencies and shared loader groups

The plugin jar is thin. Runtime dependencies are locked at build time and shipped as independent
module jars. The gateway provides **only** the JDK, Kotlin stdlib and `gateway-api`; Spring,
Jackson, MyBatis, Hikari, Flyway and JDBC drivers are ordinary plugin dependencies.

By default one module is one loading unit and sees only its declared dependencies. Some libraries
do not fit that model: Flyway resolves database types from a static `ServiceLoader` registry bound
to the loader that defined `flyway-core`, and MyBatis-Plus references MyBatis and SLF4J from POM
dependencies marked optional. Declare such modules as a **shared loader group**:

```kotlin
apiPlugin {
    sharedLoader("org.flywaydb:flyway-core", "org.flywaydb:flyway-mysql")
}
```

The DSL takes `group:artifact` values without versions (at least two); the resolved version comes
from the classpath. Group only modules that must be mutually visible, never modules that ship the
same classes (the first jar by digest wins), and do not create a cycle between loading units. A
group is the unit of sharing, reference counting and unloading.

A module the lock names but the gateway does not have is downloaded from
`app.plugin.module.repositories` while `app.plugin.module.online` is true, and accepted only when
its SHA-256 and size match the lock. `deployPlugin` uploads modules first, so offline deployments
work without network access.

---

## 6. Descriptor, lock and jar entries

```
META-INF/api-plugin.json        descriptor format 1
META-INF/api-plugin-deps.json   dependency lock: coordinates, digests, sizes, direct edges, groups
META-INF/plugin.pub.pem         signer public key
META-INF/plugin.sig             Ed25519 signature
```

The lock excludes `top.fatweb:api-management-gateway-api`, `org.jetbrains.kotlin:kotlin-stdlib*`
and `org.jetbrains:annotations`, which the platform provides. Everything else becomes a module.

---

## 7. Build → upload → configure → call

Using `echo/`:

```shell
cd echo
./gradlew build
# artifact: build/libs/echo-1.0.0.jar (signed); public key in keys/public.pem

# 1. Admin trusts the public key (first time)
POST /system/api/plugin/key   {"publicKey": "<...>", "alias": "FatttSnake"}

# 2. Upload and install
POST /system/api/plugin/install   (multipart: file=@echo-1.0.0.jar)
# or: ./gradlew deployPlugin -PapiPlugin.serverUrl=… -PapiPlugin.accessToken=…

# 3. Grant APIs to a user
GET  /user/api/key/available-apis
POST /user/api/key   {"permissionCodes": ["api:echo:v1:ping", "api:echo:v1:version",
                                         "api:echo:v2:ping", "api:echo:v2:whoami"]}

# 4. Call (Basic auth accessKey:secretKey)
GET /api/echo/v1/ping
GET /api/echo/v1/version
GET /api/echo/v2/ping
GET /api/echo/v2/whoami
```

Using `filebox/`:

```shell
cd filebox
./gradlew build

# The cache (SQLite) datasource is ready on install; main (MySQL) needs configuration.
GET  /system/api/plugin/filebox/config
# -> datasources: [ {name:"main", dbType:"MYSQL", required:true, configured:false, keys:[...]},
#                   {name:"cache", dbType:"SQLITE", required:false, configured:true, keys:[]} ]

# A save is written a group at a time; a value left out is one nothing is decided about.
# Saving a datasource value remounts the plugin automatically.
PUT  /system/api/plugin/config
     {"pluginId":"filebox","groups":[{"key":"db","values":[
        {"key":"db.host","value":"10.0.0.240"},
        {"key":"db.port","value":"3306"},
        {"key":"db.name","value":"filebox"},
        {"key":"db.user","value":"filebox"},
        {"key":"db.password","value":"…"},
        {"key":"db.params","value":"useSSL=false&serverTimezone=UTC"}]}]}

# Optionally test the connection with values that are not saved yet
POST /system/api/plugin/filebox/config/datasource/test
     {"name":"main","values":[{"key":"db.host","value":"10.0.0.240"},
                              {"key":"db.name","value":"filebox"}]}

# Then authorize and call
POST /user/api/key   {"permissionCodes": ["api:filebox:v1:upload", "api:filebox:v1:list", ...]}
POST /api/filebox/v1/upload?fileName=report.pdf     (body: raw bytes)
GET  /api/filebox/v1/files?page=1&size=20
```

Expected response (`/api/echo/v2/ping`):

```json
{ "code": 0, "success": true, "msg": "success", "data": { "message": "pong", "version": 2, "userId": 1 } }
```

---

## 8. Administrative operations summary

| Operation | How | Description |
|---|---|---|
| Enable / disable | `PATCH /system/api/plugin` | `{id, enable}`. Disabling takes the runtime down: routes unregistered, not loaded at the next start, its APIs answer `Api disabled`. Configuration, datasources, files and grants are kept; enabling mounts the same jar over them |
| Read configuration | `GET /system/api/plugin/{pluginId}/config` | Declared schema with current values: per field `value`, `default`, `hasValue`, `unreadable`; a secret's `value` is always `null`; plus every datasource's `name`, `dbType`, `required`, `configured` |
| Save configuration | `PUT /system/api/plugin/config` | A group at a time: `{pluginId, groups:[{key, values:[{key,value}]}]}`. An ordinary value takes effect immediately; a value left out keeps the stored one; a blank clears it. Changing a datasource value **remounts** the plugin |
| Test a connection | `POST /system/api/plugin/{pluginId}/config/datasource/test` | `{name, values}`; MySQL only. A genuine failure answers 40067 with the driver's reason |
| Reload | `POST /system/api/plugin/{pluginId}/reload` | Re-mounts the installed jar in place, keeping version, rows, configuration and data. For a jar that has been replaced; a datasource change does not need it |
| Upgrade | Raise `apiPlugin.versionCode`, rebuild and upload | The old version is replaced in place; only a lower `versionCode` is rejected (40066). Administrator settings, grants, datasources and files survive. A disabled plugin is not started |
| Uninstall | `DELETE /system/api/plugin/{pluginId}?purgeData=false` | Routes unregistered, row and permission tree removed. `purgeData=false` keeps settings, SQLite data and files; `true` purges them. An external MySQL database is never dropped by the gateway — that is `onPurge`'s job. A plugin that is down is mounted one last time for its exit hooks, and a failing `onPurge` refuses the uninstall (40068) |
| Restart | nothing to do | Installed plugins re-mount automatically from the stored jar |

---

## 9. FAQ

- **`Plugin signer is not trusted`**: the public key is not in the trust store, or has been disabled. Add and enable it via `POST /system/api/plugin/key`. A jar with no signature answers `Plugin signature invalid` instead — upload the `build` artifact, not the one from `./gradlew jar`.
- **`Version code N is older than the installed M`**: raise `apiPlugin.versionCode`; deploying the installed version again is allowed.
- **The jar from `./gradlew jar` is unsigned**: signing runs as part of `build` / `signPlugin`. Use the `build` artifact.
- **`verifyPlugin` fails**: the embedded public key does not pair with `keys/private.pem`. Delete `keys/` and rebuild.
- **APIs disappear after an upgrade**: the new jar must still register every route you want to keep; only the new version's routes remain.
- **`onStart` is not called / a collaborator is not created**: plugins have no dependency injection or Spring container. Construct collaborators yourself in `onStart`, or keep them as plain objects owned by the plugin instance.
- **An endpoint 404s**: check that the requested version is at least the route's declared version. A request to an API of a disabled plugin answers `Api disabled`, not a missing-resource error.
- **A disabled plugin is still doing something**: switching it off stops its routes, scheduler and events. Work driven by an outside party (a webhook registered elsewhere) is outside the gateway's control.
- **`NoClassDefFoundError` for a library class**: the module is not in the lock, or two modules that must see each other are not in a `sharedLoader` group. Rebuild after changing dependencies.

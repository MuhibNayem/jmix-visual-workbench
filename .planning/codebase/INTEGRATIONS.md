# External Integrations

**Analysis Date:** 2026-07-27

## APIs & External Services

**IntelliJ Platform:**
- IntelliJ IDEA Community Platform 2024.1 - host application and API surface for the plugin.
  - SDK/Client: `org.jetbrains.intellij` Gradle plugin 1.17.4 in `plugin/build.gradle.kts`; compile target `IC` 2024.1 in `plugin/gradle.properties`.
  - Auth: None. The plugin runs inside the user's IDE process and uses declared module dependencies from `plugin/src/main/resources/META-INF/plugin.xml`.
  - Touchpoints: tool-window and action registrations in `plugin/src/main/resources/META-INF/plugin.xml`; implementations in `plugin/src/main/kotlin/com/jmixstudio/toolwindow/`, `plugin/src/main/kotlin/com/jmixstudio/actions/`, and `plugin/src/main/kotlin/com/jmixstudio/services/`.

**Embedded Browser / JCEF:**
- IntelliJ JCEF - hosts the React designer and transports commands between TypeScript and Kotlin.
  - SDK/Client: IntelliJ `JBCefBrowser`, `JBCefJSQuery`, and CEF handlers in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
  - Auth: None; communication is an in-process bridge injected into the embedded page.
  - Protocol: TypeScript calls `window.javaBridge.send(action, payload)` from `webui/src/bridge/index.ts`; Kotlin receives JSON, dispatches generation actions, and executes `window.onBridgeResponse(...)` in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
  - Serialization: Gson 2.11.0, declared in `plugin/build.gradle.kts` and used by `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

**Jmix Project Integration:**
- Open Jmix project - detection, configuration inference, and generated-code target.
  - SDK/Client: IntelliJ `Project`/`VirtualFile` APIs in `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
  - Auth: None.
  - Detection: reads root `build.gradle` or `build.gradle.kts` and recognizes `io.jmix` or `jmix-gradle-plugin` in `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
  - Generated APIs: Jmix Core, Flow UI, Security, Jakarta Persistence, Spring Data, and Spring imports are emitted by generators under `plugin/src/main/kotlin/com/jmixstudio/generator/`; those libraries must exist in the target Jmix application and are not dependencies of this plugin.

**Schema and Workflow Formats:**
- Liquibase XML - migrations are generated against the official dbchangelog namespace and latest schema URL in `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`.
  - SDK/Client: No Liquibase library. XML is built by the custom `XmlBuilder` in `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`.
  - Runtime access: No database connection or Liquibase process is opened by the plugin.
- BPMN 2.0 / Flowable / Jmix BPM - process definitions are generated with BPMN, Flowable, and Jmix namespaces in `plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt`.
  - SDK/Client: No BPM engine library. The plugin writes `.bpmn20.xml` files through `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Jmix Flow UI/menu/fetch-plan XML - namespace-compatible files are produced by `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt`, `MenuGenerator.kt`, and `CrudOrchestrator.kt`.

**Development and Dependency Services:**
- Vite development server - optional local UI endpoint.
  - SDK/Client: `JBCefBrowser.loadURL` in `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
  - Configuration: JVM system property `jmixstudio.dev.url`; `webui/vite.config.ts` fixes the server to port 5173 with `strictPort: true`.
  - Auth: None in repository configuration.
- Maven Central - Gradle artifact repository for Gson and other declared runtime dependencies, configured in `plugin/build.gradle.kts`.
- Gradle Plugin Portal - implicit plugin-resolution source for the Kotlin and JetBrains IntelliJ Gradle plugins because `plugin/settings.gradle.kts` declares no custom `pluginManagement` repositories.
- npm registry - source of the locked frontend packages recorded in `webui/package-lock.json`.
- Gradle distribution service - Gradle 8.7 distribution URL is recorded in `plugin/gradle/wrapper/gradle-wrapper.properties`, although the wrapper launcher and JAR are not present.

**Remote Application APIs:**
- Not detected. Source under `plugin/src/` and `webui/src/` contains no HTTP client, `fetch`, Axios, WebSocket, GraphQL, or SaaS SDK integration. Namespace URLs in `plugin/src/main/kotlin/com/jmixstudio/generator/` are emitted XML identifiers/schema references, not application API calls.

## Data Storage

**Databases:**
- No application database is connected by the plugin.
  - Connection: Not applicable; no datasource URL, credential, JDBC client, or environment variable exists in `plugin/build.gradle.kts`, `plugin/src/`, or `webui/src/`.
  - Client: None.
  - Database awareness: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` infers PostgreSQL, MySQL/MariaDB, SQL Server, Oracle, or HSQLDB from target build text; `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt` uses the selected `DatabaseType` only to emit migration XML.

**File Storage:**
- Local host-project filesystem only.
  - Generated Java/XML/properties/BPMN files are written with `java.io.File` inside IntelliJ write commands by `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
  - IntelliJ's local VFS is recursively refreshed after generation by `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
  - Default destinations (`src/main/java`, `src/main/resources`, `db/changelog`, `processes`) come from `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt` and `CodeGenerationService.kt`.
  - The React UI does not persist designer data to browser storage; `webui/src/store/index.ts` keeps it in memory.

**Caching:**
- Per-project in-memory configuration cache only. `cachedConfig` is held and explicitly reset by `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- UI state is process-local Zustand state in `webui/src/store/index.ts`; there is no Redis, browser `localStorage`, IndexedDB, or filesystem cache integration under `webui/src/`.

## Authentication & Identity

**Auth Provider:**
- None.
  - Implementation: The IntelliJ plugin and embedded UI have no login, token, OAuth, session, role check, or identity-provider SDK in `plugin/src/`, `webui/src/`, `plugin/build.gradle.kts`, or `webui/package.json`.
  - Host boundary: File writes run with the permissions of the IntelliJ process through `WriteCommandAction` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
  - Generated security roles are source artifacts for the target Jmix application, produced by `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`; they do not authenticate this plugin.

## Monitoring & Observability

**Error Tracking:**
- None. No Sentry, telemetry, analytics, or remote error-reporting dependency is declared in `plugin/build.gradle.kts` or `webui/package.json`.

**Logs:**
- IntelliJ `Logger` records bridge actions, bridge failures, generation failures, and generated relative paths in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` and `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Development bridge simulation logs to the embedded browser console in `webui/src/bridge/index.ts`.
- Generation errors also return through the JCEF JSON response as `GenerationResult.errors` from `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.

## CI/CD & Deployment

**Hosting:**
- IntelliJ plugin package. Production UI assets are copied from `webui/dist/` to `plugin/build/resources/main/webui/` by `copyWebUi` in `plugin/build.gradle.kts` and loaded as `/webui/index.html` by `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
- Optional development hosting uses Vite on local port 5173 according to `webui/vite.config.ts`; the plugin uses it only when `jmixstudio.dev.url` is supplied.
- There is no standalone web hosting target in `webui/vite.config.ts` or repository deployment configuration.

**CI Pipeline:**
- None detected. The repository contains no GitHub Actions, GitLab CI, Jenkins, CircleCI, Azure Pipelines, or equivalent workflow configuration alongside `plugin/build.gradle.kts` and `webui/package.json`.
- No IntelliJ Marketplace publishing/signing task is configured in `plugin/build.gradle.kts`.

## Environment Configuration

**Required env vars:**
- None. No environment-variable access occurs under `plugin/src/` or `webui/src/`, and no `.env` file is present.
- Optional non-environment setting: `-Djmixstudio.dev.url=http://localhost:5173`, consumed by `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
- Target configuration is inferred from the open project's root build file by `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`; no value is read from this repository's environment.

**Secrets location:**
- Not applicable. No repository-managed secret file, secret-variable convention, credential loader, or token consumer is present in `plugin/src/`, `webui/src/`, `plugin/build.gradle.kts`, or `webui/package.json`.

## Webhooks & Callbacks

**Incoming:**
- No network webhooks.
- In-process JavaScript callbacks are installed as `window.onBridgeReady` and `window.onBridgeResponse` in `webui/src/bridge/index.ts`, then invoked by `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Supported bridge commands in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` are `generateEntity`, `generateCrud`, `generateView`, `generateMigration`, `generateRole`, `generateBpm`, `getProjectConfig`, `getEntities`, and `ping`.

**Outgoing:**
- No network callbacks or webhook deliveries.
- UI-to-plugin messages use `window.javaBridge.send` from `webui/src/bridge/index.ts`; the injected implementation forwards JSON through `JBCefJSQuery` in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

---

*Integration audit: 2026-07-27*

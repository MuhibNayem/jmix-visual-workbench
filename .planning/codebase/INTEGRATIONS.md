# External Integrations

**Analysis Date:** 2026-08-04

## APIs & External Services

**Build-time artifact sources:**
- Maven Central + Gradle Plugin Portal - JVM dependencies and Gradle plugins (`plugin/settings.gradle.kts`, `plugin/build.gradle.kts`, `plugin/buildSrc/build.gradle.kts`).
- JetBrains IntelliJ Platform repositories - IntelliJ IDEA Ultimate 2025.3 / 2026.2 platform distributions, bundled modules/plugins, and Plugin Verifier inputs, resolved by the IntelliJ Platform Gradle Plugin 2.18.0 (`plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`).
- Jmix public Maven repository `https://global.repo.jmix.io/repository/public` - compatibility certification fixtures only; content-filtered to `io.jmix.*` groups (`plugin/build.gradle.kts`).
- Node.js distribution download - Node 24.18.0 fetched into `plugin/build/nodejs` by com.github.node-gradle.node 7.1.0 (`plugin/build.gradle.kts`).
- Foojay API - Eclipse Temurin toolchain provisioning via `org.gradle.toolchains.foojay-resolver-convention` 1.0.0 (`plugin/settings.gradle.kts`).

**Runtime network calls (user-initiated features only):**
- Organization template catalog downloads - `java.net.http.HttpClient` with redirects disabled (`HttpClient.Redirect.NEVER`); every bundle is Ed25519-verified before installation (`plugin/src/main/kotlin/org/jmixworkbench/project/JmixOrganizationTemplateCatalog.kt`).
- Loopback REST contract probes - `plugin/src/main/kotlin/org/jmixworkbench/services/RestApiWorkspaceService.kt` fetches live contracts only from validated loopback targets (`validatedLoopbackTarget`) of the user's running application.
- Runtime preview health probe - `HttpURLConnection` against the target app's loopback address and resolved `server.port` (default 8080) (`plugin/src/main/kotlin/org/jmixworkbench/services/JmixRuntimeService.kt`).

## Embedded Runtime (JCEF)

**Browser hosting:**
- JCEF is supplied by the IntelliJ runtime; support is checked with `JBCefApp.isSupported()` before browser creation (`plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`).
- Surfaces: `Jmix Visual Workbench` tool window, `Jmix Runtime Preview` tool window, and file editor providers `JmixFlowUiFileEditorProvider` / `JmixEntityFileEditorProvider` (registered in `plugin/src/main/resources/META-INF/plugin.xml`; implementations in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/` and `plugin/src/main/kotlin/org/jmixworkbench/editor/`).
- Production UI is served from a private origin `https://jmix-workbench.invalid` through a custom `CefResourceHandler` backed by plugin classpath resources; entry URLs `/index.html`, `/flowui-editor.html`, `/entity-editor.html` (the editor URLs map back to `index.html`); responses set `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Resource-Policy: same-origin` (`plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt`). Non private-origin requests pass through the normal browser pipeline.

**JS bridge protocol:**
- Kotlin side: `JBCefJSQuery` dispatcher in `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` injects `window.javaBridge.send(action, payload, requestId)` and answers via `window.onBridgeResponse(action, requestId, result)`; readiness via `window.onBridgeReady`. Injection is gated to packaged-workbench origin URLs only.
- TypeScript side: `webui/src/bridge/index.ts` - pending queue, per-request-id promise routing, `window.jmixWorkbenchLaunchContext` / `window.onWorkbenchLaunchContext` handshake, dev simulation (`webui/src/bridge/devMocks.ts`).
- The bridge action surface includes `get*Workspace` reads and `preview*`/`apply*` change pairs for entities, CRUD, FlowUI XML/controllers, schema migrations, security roles/policies, menus, scenario tests, visual logic, visual rules, DMN decisions, integration connectors, REST API contracts, environment configuration, project profiles, database entity import, plus runtime preview and navigation actions (`plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`).

## Target Jmix Project Integration

**Project discovery and indexing:**
- Jmix/Gradle detection and config parsing: `plugin/src/main/kotlin/org/jmixworkbench/services/JmixProjectService.kt`, `plugin/src/main/kotlin/org/jmixworkbench/discovery/static/GradleConfigParser.kt`.
- Semantic application graph: `plugin/src/main/kotlin/org/jmixworkbench/discovery/semantic/ApplicationGraphIndexer.kt`, `plugin/src/main/kotlin/org/jmixworkbench/services/ApplicationGraphService.kt`, with file-based indexes registered in `plugin/src/main/resources/META-INF/plugin.xml`.
- Compatibility classification for Jmix 2.8 and 3.0 lines: `plugin/src/main/resources/compatibility/phase2-registry.json`, `plugin/src/main/kotlin/org/jmixworkbench/discovery/compatibility/CompatibilityRegistry.kt`.

**Generated-code contracts (what generators emit into the user's project):**
- Jmix Core/Data/FlowUI/Email APIs (`io.jmix.core.*`, `io.jmix.data.*`, `io.jmix.flowui.*`, `io.jmix.email.*`) emitted by generators under `plugin/src/main/kotlin/org/jmixworkbench/generator/` (e.g. `EntityGenerator.kt`, `ViewControllerGenerator.kt`, `AggregateUpdateServiceGenerator.kt`, `IntegrationConnectorGenerator.kt`).
- Jakarta Persistence entities, Spring stereotype/event listeners, Liquibase changelog XML, BPMN, and DMN artifacts: `EntityGenerator.kt`, `EventListenerGenerator.kt`, `MigrationGenerator.kt`, `BpmGenerator.kt`, `DmnDecisionGenerator.kt`.
- Integration connector generation targets spring-kafka, spring-rabbit, spring-integration-sftp, JDK `HttpClient`/Spring HTTP client settings (`HttpClientSettings` for Boot 4), `javax.sql.DataSource`, and Jmix `FileStorage`/`Emailer` (`plugin/src/main/kotlin/org/jmixworkbench/generator/IntegrationConnectorGenerator.kt`).
- Certification proof: generated sources are compiled against exact Jmix 2.8.2 (JDK 17/21) and 3.0.0 (JDK 21/25) cells with spring-kafka/rabbit/sftp/web/boot, spring-security-oauth2-client, resilience4j-spring-boot3/4 2.4.0, spring-jdbc, and micrometer (`plugin/build.gradle.kts`).

**New project generation:**
- New-project wizard and template generators emit complete Jmix projects (Gradle wrapper files from plugin classpath resources, HSQLDB file datasource defaults): `plugin/src/main/kotlin/org/jmixworkbench/project/JmixNewProjectWizard.kt`, `JmixProjectTemplateGenerator.kt`, `JmixFlowUiProjectTemplate.kt`, `JmixProjectInstaller.kt`.

## Data Storage

**Databases (plugin itself):**
- None. The plugin keeps no database of its own; state lives in IntelliJ project/application services and in the opened project's files.

**Databases (features touching user databases):**
- Live database reverse engineering over plain JDBC: user-supplied driver jars are loaded via a `URLClassLoader`, metadata read through `java.sql.DatabaseMetaData`; recognized URL prefixes `jdbc:postgresql:`, `jdbc:mysql:`/`jdbc:mariadb:`, `jdbc:sqlserver:`, `jdbc:oracle:`, `jdbc:db2:` (plus HSQLDB), driver-class inference (e.g. `org.postgresql.Driver`), and environment-variable substitution inside JDBC URLs (`plugin/src/main/kotlin/org/jmixworkbench/services/DatabaseReverseEngineeringService.kt`); entity import planning in `plugin/src/main/kotlin/org/jmixworkbench/services/DatabaseEntityImportPlanner.kt`.
- Live DB evidence lanes for host tests are configured via `jvw.live.db.*` system properties (`plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`).
- Certification evidence runtimes (separate Gradle projects with Docker): `certification/database-runtime/` (runtime app + Liquibase against postgres:16.9, mysql:8.4.6, mariadb:11.4.8, mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04, gvenzl/oracle-free:23.7-slim-faststart in `certification/database-runtime/docker-compose.yml`; harness `certification/database-runtime/run-matrix.sh`) and `certification/integration-runtime/`.

**File Storage:**
- Local filesystem only. Generated artifacts are written into the opened IntelliJ project; the web bundle is staged at `plugin/build/generated-resources/webui/` and packaged into each host plugin ZIP (`plugin/build.gradle.kts`, `plugin/hosts/idea253/build.gradle.kts`).

**Caching:**
- No external cache service. In-memory caches exist inside IDE services (e.g. cached workspace snapshots in `plugin/src/main/kotlin/org/jmixworkbench/services/RestApiWorkspaceService.kt`).

## Authentication & Identity

**Auth provider:**
- None. The workbench has no login, no third-party identity provider, and stores no credentials of its own.

**Secrets handling:**
- Target-project `.env` / `.env.properties` management in `plugin/src/main/kotlin/org/jmixworkbench/services/JmixEnvironmentConfigurationService.kt`: secret-named values are redacted (`SECRET_REDACTION`) before reaching the JCEF UI, pending secret changes are held only inside the service, and `javax.crypto` (`SecretKeySpec`) is used internally.
- Live database credentials are supplied by the user at runtime and forwarded only as test/system properties in evidence lanes (`jvw.live.db.username` / `jvw.live.db.password`, host build files).

**Generated security artifacts:**
- Jmix resource/row-level security roles are generated into target projects (`plugin/src/main/kotlin/org/jmixworkbench/generator/RoleGenerator.kt`); compatibility cells compile against spring-security-oauth2-client (`plugin/build.gradle.kts`).

## Monitoring & Observability

**Error Tracking:**
- None (no Sentry/crash-reporting integration).

**Logs:**
- IntelliJ platform `Logger` only, inside plugin services and the bridge (e.g. `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`). No telemetry, analytics, or remote reporting exists anywhere in `plugin/src/main/kotlin/` or `webui/src/`.

## CI/CD & Deployment

**Hosting:**
- None - the product ships as IntelliJ plugin ZIPs.

**CI Pipeline:**
- GitHub Actions `Phase 1 CI` (`.github/workflows/ci.yml`): triggers on pull requests and pushes to `main`; `permissions: contents: read`; concurrency group with cancel-in-progress; runs on `ubuntu-24.04` (120-minute timeout) with Temurin 21; verifies the checked-in wrapper jar sha256 and distribution sha256; runs `cd plugin && ./gradlew clean phase1Check --dependency-verification=strict --no-daemon --no-configuration-cache --stacktrace`; uploads both host plugin ZIPs as `jmix-visual-workbench-plugin-zips` and test/Plugin Verifier reports as `phase1-verification-reports` (7-day retention). Actions pinned by commit SHA: `actions/checkout` v4.2.2, `actions/setup-java` v4.7.1, `actions/upload-artifact` v4.6.2.
- Dependabot (`.github/dependabot.yml`): weekly review-only PRs for gradle (`/plugin`), npm (`/webui`), and github-actions (`/`); no auto-merge.
- No release, signing, or Marketplace publishing pipeline (`docs/BUILDING.md`, `docs/RELEASE-INTEGRITY.md`).

## Environment Configuration

**Required env vars:**
- None for build or runtime; the build is fully wrapper-bootstrapped.

**Recognized system/Gradle properties (development and evidence seams):**
- `jmixworkbench.dev.enabled`, `jmixworkbench.dev.url` - load the UI from the Vite dev server instead of the packaged bundle (`plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`).
- `jvw.live.db.*`, `jvw.project.template.*` - forwarded to host-lane tests for live-database evidence and project-template runtime certification (`plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`).
- `-PlocalIdea253Path`, `-PlocalIdea262Path`, `-PlocalIdeaPath` - local IDE SDK overrides validated against `Resources/build.txt` (`plugin/hosts/idea253/build.gradle.kts`, `docs/BUILDING.md`).

**Secrets location:**
- No secrets exist in the repository (no `.env*` files are present). Secret values only appear transiently as user-supplied inputs for live database features or as redacted entries read from the target project's own `.env` files.

## Webhooks & Callbacks

**Incoming:**
- None.

**Outgoing:**
- None.

## Certification Evidence Runtimes (Docker Middleware)

**database-runtime** (`certification/database-runtime/docker-compose.yml`):
- postgres:16.9, mysql:8.4.6, mariadb:11.4.8, mssql 2022-CU20-ubuntu-22.04, oracle-free 23.7-slim-faststart; harness `certification/database-runtime/run-matrix.sh`; runtime app under `certification/database-runtime/src/main/` (entities, Liquibase changelogs, `persistence.xml`).

**integration-runtime** (`certification/integration-runtime/docker-compose.yml`):
- postgres:16.9, apache/kafka:4.0.0, rabbitmq:4.1.2-management-alpine, atmoz/sftp:alpine, ghcr.io/shopify/toxiproxy:2.12.0, wiremock/wiremock:3.13.2-2; harness `certification/integration-runtime/run-matrix.sh`; runtime app under `certification/integration-runtime/src/main/`.

These projects are evidence harnesses only; they are not part of the shipped plugin.

---

*Integration audit: 2026-08-04*

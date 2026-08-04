# Technology Stack

**Analysis Date:** 2026-08-04

## Languages

**Primary:**
- Kotlin (compiler 2.4.0) - All IntelliJ plugin production sources under `plugin/src/main/kotlin/org/jmixworkbench/`, plus all build scripts. Compiler version pinned in `plugin/gradle/libs.versions.toml` (`kotlin = "2.4.0"`). Per-host language/API levels are pinned: Kotlin 2.2 for the IDEA 253 lane (`plugin/hosts/idea253/build.gradle.kts`), Kotlin 2.4 for the IDEA 262 lane (`plugin/hosts/idea262/build.gradle.kts`).
- TypeScript (declared `^5.5.4`, locked 5.9.3) - React workbench UI, bridge client, store, and models under `webui/src/`. Declared range in `webui/package.json`; locked resolution in `webui/package-lock.json` (lockfileVersion 3).

**Secondary:**
- Java - Custom Gradle task classes in `plugin/buildSrc/src/main/java/org/jmixworkbench/build/` compiled with `options.release.set(17)` (`plugin/buildSrc/build.gradle.kts`); generated compatibility fixtures compiled under JDK 17/21/25 cells (`plugin/build.gradle.kts`); generated target-project code can be Java or Kotlin (`plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/org/jmixworkbench/generator/KotlinEntityGenerator.kt`).
- Gradle Kotlin DSL - Aggregate build `plugin/build.gradle.kts`, settings `plugin/settings.gradle.kts`, host builds `plugin/hosts/idea253/build.gradle.kts` and `plugin/hosts/idea262/build.gradle.kts`, buildSrc `plugin/buildSrc/build.gradle.kts`.
- XML - Plugin descriptors (`plugin/src/main/resources/META-INF/plugin.xml`, `plugin/src/main/resources/META-INF/jmix-kotlin.xml`, `plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml`, `plugin/hosts/idea262/src/main/resources/META-INF/plugin.xml`), dependency verification metadata (`plugin/gradle/verification-metadata.xml`), and generator outputs (FlowUI views, Liquibase changelogs, BPMN, DMN).
- TOML - Gradle version catalog `plugin/gradle/libs.versions.toml`.
- CSS - Tailwind directives and global styles in `webui/src/index.css`; configured by `webui/tailwind.config.js` and `webui/postcss.config.js`.
- HTML - Vite app shell `webui/index.html`.
- JavaScript (ESM) - `webui/tailwind.config.js`, `webui/postcss.config.js`.
- Shell - Certification harness scripts `certification/database-runtime/run-matrix.sh`, `certification/integration-runtime/run-matrix.sh`.

## Runtime

**Environment:**
- IntelliJ IDEA Ultimate 2025.3 (IU-253) and IntelliJ IDEA Ultimate 2026.2 (IU-262) are the two supported plugin host runtimes. Each is an isolated Gradle included build: `plugin/hosts/idea253/` resolves `intellijIdeaUltimate("2025.3")`, `plugin/hosts/idea262/` resolves `intellijIdeaUltimate("2026.2")`; both are wired into the aggregate build via `includeBuild` in `plugin/settings.gradle.kts`. Local IDE SDKs may replace remote coordinates via `-PlocalIdea253Path` / `-PlocalIdea262Path`, validated against `Resources/build.txt` (wrong IU build branch is rejected).
- JCEF is supplied by the IntelliJ runtime. `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt` checks `JBCefApp.isSupported()` before creating browsers. The IDEA 262 lane explicitly declares `bundledModule("intellij.libraries.jcef")` and `bundledModule("intellij.platform.ui.jcef")` (`plugin/hosts/idea262/build.gradle.kts`).
- Gradle 9.5.1 is the only supported build entry point. Distribution URL and `distributionSha256Sum` are pinned in `plugin/gradle/wrapper/gradle-wrapper.properties`; the checked-in `plugin/gradle/wrapper/gradle-wrapper.jar` is sha256-verified by CI (`plugin/gradlew`, `plugin/gradlew.bat`, `.github/workflows/ci.yml`). Global Gradle installations are unsupported per `docs/BUILDING.md`.
- JDK 21 is the bootstrap JDK (CI installs Temurin 21 in `.github/workflows/ci.yml`; required by `docs/BUILDING.md`). The Foojay resolver convention plugin 1.0.0 (`plugin/settings.gradle.kts`) provisions missing compilation toolchains: Eclipse Temurin (Adoptium vendor) Java 21 compiles the idea253 lane (`plugin/hosts/idea253/build.gradle.kts`) and Java 25 compiles the idea262 lane (`plugin/hosts/idea262/build.gradle.kts`). `verifyHostToolchains` checks the resolved compiler launcher metadata.
- Node.js 24.18.0 is the UI build runtime, downloaded by the Gradle build itself. The `node` extension in `plugin/build.gradle.kts` sets `version.set(libs.versions.node.runtime)` (`node-runtime = "24.18.0"` in `plugin/gradle/libs.versions.toml`), `download.set(true)`, `workDir` `plugin/build/nodejs`, `npmWorkDir` `plugin/build/npm`; `webui/package.json` pins `engines.node` to `24.18.0`. Installed plugins load only bundled static resources and never execute Node at runtime (`docs/BUILDING.md`).

**Package Manager:**
- npm with lockfileVersion 3 at `webui/package-lock.json`; the Gradle build runs `npm ci` (`npmInstallCommand.set("ci")` in `plugin/build.gradle.kts`), preceded by `snapshotNpmLockHash` to detect lockfile drift.
- Gradle dependency locking (STRICT mode) per host lane: `plugin/hosts/idea253/gradle/dependency-locks/gradle.lockfile` and `plugin/hosts/idea262/gradle/dependency-locks/gradle.lockfile` covering `runtimeClasspath` + `testRuntimeClasspath`; policy documented in `plugin/gradle/dependency-locks/README.md`.
- Gradle dependency verification (SHA-256 checksums) in `plugin/gradle/verification-metadata.xml`; CI runs with `--dependency-verification=strict`.

## Frameworks

**Core:**
- IntelliJ Platform SDK (2025.3 / 2026.2) - tool windows, project/application services, actions, PSI reference contributors, file-based indexes, inspections, file editors, rename processors, JCEF. Shared registrations in `plugin/src/main/resources/META-INF/plugin.xml`; hard dependencies `com.intellij.modules.platform`, `com.intellij.modules.java`, `com.intellij.modules.xml`, `com.intellij.properties`, `com.intellij.gradle`; optional `org.jetbrains.kotlin` (config-file `jmix-kotlin.xml`).
- IntelliJ Platform Gradle Plugin 2.18.0 (`plugin/gradle/libs.versions.toml`) - resolves IDE distributions, bundled modules/plugins, test frameworks, and Plugin Verifier.
- React 18.3.1 - workbench UI mounted via `ReactDOM.createRoot` in `webui/src/main.tsx`; locked in `webui/package-lock.json`.
- Zustand 4.5.7 - UI state (`webui/src/store/index.ts`); declared `^4.5.5` in `webui/package.json`.
- Tailwind CSS 3.4.19 - styling (`webui/tailwind.config.js`, `webui/src/index.css`); declared `^3.4.10`.

**Testing:**
- JUnit 4.13.2 + `kotlin("test")` + JUnit Vintage engine 5.10.1 - host-lane tests (`plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`), e.g. `plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253DescriptorTest.kt`.
- IntelliJ Platform Test Framework (`TestFrameworkType.Platform`, `TestFrameworkType.Plugin.Java`) - IDE fixture tests under `plugin/src/test/kotlin/`, e.g. `plugin/src/test/kotlin/org/jmixworkbench/actions/InjectJmixRepositoryActionTest.kt` (`LightJavaCodeInsightFixtureTestCase`) and `plugin/src/test/kotlin/org/jmixworkbench/services/ApplicationGraphServiceIntegrationTest.kt`.
- JUnit Jupiter 5.10.1 (+ `junit-platform-launcher` 1.10.1) - buildSrc task tests (`plugin/buildSrc/build.gradle.kts`, e.g. `plugin/buildSrc/src/test/java/org/jmixworkbench/build/VerifyWebBundleTaskTest.java`) and the aggregate `phase2CoreTest` source set (`plugin/build.gradle.kts`), e.g. `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/semantic/ApplicationGraphIndexerTest.kt`.
- webui has no test runner; the only frontend static gate is `tsc` in `npm run build` (`webui/package.json`).

**Build/Dev:**
- Gradle Kotlin DSL aggregate build `plugin/build.gradle.kts` - custom source sets (`phase2Core`, `compatibilityGenerator`, per-cell `*Compatibility`), Node integration, web bundle assembly/verification, host orchestration, and the `phase1Check` gate.
- `plugin/buildSrc/` - Java build tasks `AssembleWebBundleTask`, `VerifyWebBundleTask`, `VerifyPluginZipContentsTask`, `SnapshotFileHashTask`, plus `WebBundleFingerprint` (`plugin/buildSrc/src/main/java/org/jmixworkbench/build/`).
- com.github.node-gradle.node 7.1.0 (`plugin/gradle/libs.versions.toml`) - downloads Node 24.18.0 and runs `npm ci` / `npm run build` inside Gradle (`plugin/build.gradle.kts`).
- Vite 8.1.5 - UI dev server (port 5173, `strictPort: true`) and production bundler; `base: './'`, `outDir: 'dist'` (`webui/vite.config.ts`).
- @vitejs/plugin-react 6.0.4 - React JSX integration (`webui/vite.config.ts`).
- TypeScript 5.9.3 compiler - strict mode, target ES2020, `moduleResolution: bundler`, `jsx: react-jsx`, `noUnusedLocals`/`noUnusedParameters` disabled, `noFallthroughCasesInSwitch` and `forceConsistentCasingInFileNames` enabled (`webui/tsconfig.json`).
- PostCSS 8.5.23 + Autoprefixer 10.5.4 - CSS processing (`webui/postcss.config.js`; locked versions in `webui/package-lock.json`).
- Foojay resolver convention 1.0.0 - JDK auto-provisioning (`plugin/settings.gradle.kts`).

## Key Dependencies

**Critical (plugin runtime):**
- Gson 2.11.0 - bridge payload and model serialization; declared in both host builds (`plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`) and via the catalog for aggregate source sets (`plugin/gradle/libs.versions.toml`).
- swagger-parser-v3 2.1.45 (`io.swagger.parser.v3:swagger-parser-v3`) - OpenAPI 3 parsing/bundling in `plugin/src/main/kotlin/org/jmixworkbench/services/OpenApiContractService.kt` and `plugin/src/main/kotlin/org/jmixworkbench/services/OpenApiDocumentBundler.kt`; declared in both host builds.
- IntelliJ bundled dependencies (provided, not packaged): module `intellij.java.psi`; plugins `org.jetbrains.kotlin`, `com.intellij.properties`, `com.intellij.gradle` (both host builds).
- Host builds reject bundling the IDE-provided Kotlin runtime (`verifyNoBundledKotlinRuntime` in `plugin/hosts/idea253/build.gradle.kts`).

**UI:**
- react / react-dom 18.3.1, zustand 4.5.7, lucide-react 0.441.0, clsx 2.1.1 - declared in `webui/package.json`, locked in `webui/package-lock.json`.

**Compatibility certification (test-scope only, aggregate build):**
- Jmix BOM/core/data/flowui 2.8.2 (JDK 17 and JDK 21 cells) and 3.0.0 (JDK 21 and JDK 25 cells), resolved from the Jmix public repository `https://global.repo.jmix.io/repository/public` content-filtered to `io.jmix.*` (`plugin/build.gradle.kts`).
- spring-kafka, spring-rabbit, spring-integration-sftp, spring-web, spring-boot (+ `spring-boot-http-client` for the Jmix 3 cells), spring-security-oauth2-client, resilience4j-spring-boot3/4 2.4.0, spring-jdbc, micrometer-core, micrometer-observation - compile targets for generated-code certification cells (`plugin/build.gradle.kts`, cells declared as `jmix28Jdk17`, `jmix28Jdk21`, `jmix30Jdk21`, `jmix30Jdk25`).

**Infrastructure (JDK-only):**
- `java.net.http.HttpClient` - organization template catalog downloads and loopback REST probes (`plugin/src/main/kotlin/org/jmixworkbench/project/JmixOrganizationTemplateCatalog.kt`, `plugin/src/main/kotlin/org/jmixworkbench/services/RestApiWorkspaceService.kt`).
- `java.sql.*` JDBC + user-supplied driver jars via `URLClassLoader` - live database reverse engineering (`plugin/src/main/kotlin/org/jmixworkbench/services/DatabaseReverseEngineeringService.kt`).
- `java.security.Signature` Ed25519 - template catalog signature verification (`plugin/src/main/kotlin/org/jmixworkbench/project/JmixOrganizationTemplateCatalog.kt`).
- No external templating engine: generated sources are rendered by `plugin/src/main/kotlin/org/jmixworkbench/generator/JavaClassBuilder.kt` and `plugin/src/main/kotlin/org/jmixworkbench/generator/XmlBuilder.kt`.

## Configuration

**Environment:**
- No `.env` files or secret stores exist in the repository. Runtime configuration derives from the opened IntelliJ project.
- Development seam JVM properties: `jmixworkbench.dev.enabled` and `jmixworkbench.dev.url`, read in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`; used to load the Vite dev server (default `http://localhost:5173`, `webui/vite.config.ts`) instead of the packaged bundle.
- Evidence-lane system properties forwarded to host tests: `jvw.live.db.*` (enabled/id/url/username/password/driver/driverClasspath/catalog/schema/hostLane/evidenceFile) and `jvw.project.template.*` (runtime cells/languages/templates/uiKinds/organizationOnly, java17Home/java21Home/java25Home) in `plugin/hosts/idea253/build.gradle.kts` and `plugin/hosts/idea262/build.gradle.kts`.
- Plugin identity and build behavior: `pluginGroup = org.jmixworkbench`, `pluginId = org.jmixworkbench`, `pluginName = Jmix Visual Workbench`, `pluginVersion = 1.0.0`, `org.gradle.jvmargs = -Xmx2g`, `org.gradle.configuration-cache = true` (`plugin/gradle.properties`).

**Build:**
- Version catalog: `plugin/gradle/libs.versions.toml` (Gradle 9.5.1, IntelliJ Platform Gradle Plugin 2.18.0, foojay 1.0.0, node plugin 7.1.0, Node runtime 24.18.0, Gson 2.11.0, Kotlin 2.4.0, idea253 `2025.3` / idea262 `2026.2`).
- Settings: `plugin/settings.gradle.kts` (foojay resolver, `RepositoriesMode.PREFER_PROJECT` with mavenCentral, `includeBuild` of `hosts/idea253` and `hosts/idea262`, root name `jmix-visual-workbench`).
- Aggregate build: `plugin/build.gradle.kts` (mavenCentral + JmixPublic repositories, custom source sets, compatibility cells, Node tasks `snapshotNpmLockHash`/`npmCi`/`compileWebUi`/`buildWebUi`/`verifyWebBundle`, `verifyPluginZipContents`, `phase1Check`).
- Host builds: `plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts` (shared sources from `../../src/main/kotlin`, Adoptium toolchains, Kotlin language pins, STRICT dependency locking, web bundle verification, plugin ZIP packaging, plugin verifier).
- Dependency verification: `plugin/gradle/verification-metadata.xml` (SHA-256, strict in CI).
- Lock policy doc: `plugin/gradle/dependency-locks/README.md`.
- UI configs: `webui/package.json`, `webui/package-lock.json`, `webui/tsconfig.json`, `webui/vite.config.ts`, `webui/tailwind.config.js`, `webui/postcss.config.js`.

## Platform Requirements

**Development:**
- Git and a Java 21 bootstrap JDK capable of launching Gradle 9.5.1; network access on the first build (Gradle distribution, Node 24.18.0 archive, Temurin 21/25 toolchains, IntelliJ IDEA Ultimate 2025.3/2026.2 platform inputs, Maven/Plugin Portal dependencies, Plugin Verifier targets) per `docs/BUILDING.md`.
- Full gate: `cd plugin && ./gradlew clean phase1Check --dependency-verification=strict`.
- Offline reproduction after a populated cache: `cd plugin && ./gradlew clean phase1Check --offline --dependency-verification=strict`.
- Focused integrity gate: `cd plugin && ./gradlew snapshotLockHashes verifyLockedConfigurations verifyDependencyIntegrity compareLockHashes --dependency-verification=strict`.
- Test gate: `cd plugin && ./gradlew test --dependency-verification=strict` (`README.md`).
- UI-only development: `npm ci` + `npm run dev` in `webui/` (Vite, port 5173 strict), then enable `jmixworkbench.dev.enabled`/`jmixworkbench.dev.url` in the IDE; production UI is bundled into the plugin ZIP and served from a private JCEF origin with no Node at runtime (`docs/BUILDING.md`, `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt`).

**Production:**
- Deployment targets are per-host IntelliJ plugin ZIPs (development/CI artifacts, not signed releases): `plugin/hosts/idea253/build/distributions/jmix-visual-workbench-1.0.0-idea253.zip` and `plugin/hosts/idea262/build/distributions/jmix-visual-workbench-1.0.0-idea262.zip` (`docs/BUILDING.md`).
- No Marketplace publishing, signing, or release pipeline is configured; policy in `docs/RELEASE-INTEGRITY.md`.
- Plugin version 1.0.0 (`plugin/gradle.properties`, `plugin/src/main/resources/META-INF/plugin.xml`); UI version 1.0.0 (`webui/package.json`).

---

*Stack analysis: 2026-08-04*

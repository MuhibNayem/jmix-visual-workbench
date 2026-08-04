# Codebase Structure

**Analysis Date:** 2026-08-04

## Directory Layout

```
jmix-visual-workbench/
├── plugin/ # IntelliJ plugin aggregate Gradle build
│ ├── build.gradle.kts # Aggregate build: source sets, npm pipeline, compatibility matrix, host verification
│ ├── settings.gradle.kts # Includes hosts/idea253 and hosts/idea262 builds
│ ├── gradle.properties # pluginGroup/pluginId/pluginName/pluginVersion
│ ├── gradlew / gradlew.bat # Checked-in wrapper (Gradle 9.5.1)
│ ├── gradle/ # Wrapper jar, libs.versions.toml, verification-metadata.xml, dependency-locks/
│ ├── buildSrc/ # Custom build tasks (web bundle assembly/verification, zip checks)
│ ├── hosts/
│ │ ├── idea253/ # IDEA Ultimate 2025.3 lane (JDK 21, Kotlin 2.2, since/until 253)
│ │ └── idea262/ # IDEA Ultimate 2026.2 lane (JDK 25, Kotlin 2.4, since/until 262)
│ └── src/
│ ├── main/kotlin/org/jmixworkbench/ # Shared plugin sources (compiled by both host lanes)
│ │ ├── actions/ # IDE action entry points
│ │ ├── bridge/ # JCEF request dispatcher
│ │ ├── discovery/ # Evidence model, indexer, change planning, compatibility, parsers
│ │ ├── editor/ # JCEF-backed native file editors
│ │ ├── generator/ # Stateless source generators/builders/patchers
│ │ ├── ide/ # Native references, indexes, inspections, rename, line markers
│ │ ├── model/ # Bridge payload models
│ │ ├── project/ # New-project wizard, templates, organization catalogs
│ │ ├── services/ # Project services: indexing, workspaces, changes, generation
│ │ └── toolwindow/ # JCEF host, launch context, navigation, packaged resources
│ ├── main/resources/ # Shared resources (descriptors, registry JSON, icons, inspection HTML)
│ ├── test/kotlin/ # Shared tests run by both host lanes (platform + integration)
│ ├── phase2CoreTest/kotlin/ # Platform-independent core tests (JUnit 5)
│ └── compatibilityGenerator/kotlin/ # Fixture corpus generator for the compatibility matrix
├── webui/ # React/TypeScript workbench UI (Vite)
│ ├── index.html / vite.config.ts / tsconfig.json / package.json / package-lock.json
│ ├── tailwind.config.js / postcss.config.js
│ └── src/
│ ├── main.tsx / App.tsx / index.css / vite-env.d.ts
│ ├── bridge/ # Bridge client + dev mocks
│ ├── store/ # Zustand state
│ ├── types/ # Mirrored payload types
│ └── components/ # Designer workspaces (one dir per feature)
├── certification/
│ ├── database-runtime/ # Five-database Jmix runtime certification lab (Docker Compose)
│ └── integration-runtime/ # Generated integration connector runtime certification lab
├── docs/ # Build, compatibility, parity, and native-editor documentation
├── .planning/ # GSD planning artifacts (phases, research, codebase maps)
├── .github/ # dependabot.yml, workflows/ci.yml (Phase 1 CI, ubuntu-24.04)
└── README.md / CLEAN_ROOM.md / LICENSE / NOTICE / SECURITY.md / TRADEMARKS.md / ...
```

## Directory Purposes

**`plugin/`:**
- Purpose: Gradle aggregate build that orchestrates the web bundle, host lanes, compatibility cells, and verification tasks. Root project name `jmix-visual-workbench` (`plugin/settings.gradle.kts`).
- Contains: `plugin/build.gradle.kts`, `plugin/settings.gradle.kts`, `plugin/gradle.properties`, wrapper, `plugin/gradle/libs.versions.toml` (IntelliJ Platform Gradle plugin 2.18.0, Node plugin 7.1.0, Node runtime 24.18.0, Gson 2.11.0, Kotlin 2.4.0), `plugin/gradle/verification-metadata.xml`.
- Key files: `plugin/build.gradle.kts` (source sets `phase2Core`/`phase2CoreTest`/`compatibilityGenerator`, empty `main`/`test`, npm tasks, `verifyHostToolchains`, `verifyHostBuildDefinitions`).

**`plugin/buildSrc/`:**
- Purpose: Custom Gradle task implementations for bundle integrity.
- Key files: `plugin/buildSrc/src/main/java/org/jmixworkbench/build/AssembleWebBundleTask.java`, `VerifyWebBundleTask.java`, `VerifyPluginZipContentsTask.java`, `SnapshotFileHashTask.java`, `WebBundleFingerprint.java`; tests in `plugin/buildSrc/src/test/java/org/jmixworkbench/build/`.

**`plugin/hosts/idea253/` and `plugin/hosts/idea262/`:**
- Purpose: Isolated included builds that compile the shared Kotlin sources against one IntelliJ version each and produce the distributable plugin zip.
- Contains: `build.gradle.kts` (host lane contract: idea253 = `intellijIdeaUltimate("2025.3")`, Java 21, Kotlin 2.2, `sinceBuild 253`/`untilBuild 253.*`; idea262 = `intellijIdeaUltimate("2026.2")`, Java 25, Kotlin 2.4, `sinceBuild 262`/`untilBuild 262.*`), `src/main/resources/META-INF/plugin.xml` (host descriptor), `gradle.properties`, `gradle/dependency-locks/gradle.lockfile` (strict locking), `Idea253DescriptorTest.kt` / `Idea262DescriptorTest.kt`.
- Key behavior: both map `main` kotlin sources to `../../src/main/kotlin` and tests to `../../src/test/kotlin` (`plugin/hosts/idea253/build.gradle.kts`); `processResources` merges shared `plugin/src/main/resources` excluding `META-INF/plugin.xml`, plus `plugin/build/generated-resources` (web bundle), repo `LICENSE`/`NOTICE`, and `project-template/` files including the wrapper jar.

**`plugin/src/main/kotlin/org/jmixworkbench/`:**
- Purpose: All production plugin code, package root `org.jmixworkbench`.
- Contains: 10 subpackages (see layout). No files live directly at the package root.
- Key files: `bridge/JcefBridge.kt`, `services/ApplicationGraphService.kt`, `services/WorkspaceChangeService.kt`, `services/CodeGenerationService.kt`, `discovery/semantic/ApplicationGraphIndexer.kt`, `toolwindow/JmixWorkbenchToolWindowFactory.kt`.

**`plugin/src/main/resources/`:**
- Purpose: Shared plugin resources consumed by both host lanes.
- Key files: `META-INF/plugin.xml` (shared descriptor), `META-INF/jmix-kotlin.xml` (empty stub), `compatibility/phase2-registry.json` (certified read-only compatibility cells), `icons/workbench.svg`, `inspectionDescriptions/*.html` (five inspection description pages).

**`plugin/src/test/kotlin/`:**
- Purpose: Shared tests compiled and run by both host lanes against the IntelliJ test framework (87 files: generator tests, service tests, `ide/` assistance tests, `project/` tests, `toolwindow/` tests including `WorkbenchToolWindowFactoryIntegrationTest`).

**`plugin/src/phase2CoreTest/kotlin/`:**
- Purpose: Platform-independent tests for discovery/change/compatibility core logic (JUnit 5; e.g. `discovery/semantic/ApplicationGraphIndexerTest.kt`, `discovery/change/WorkspaceChangePlannerTest.kt`, `discovery/PlatformIndependenceTest.kt`), wired through the `phase2CoreTest` task in `plugin/build.gradle.kts`.

**`plugin/src/compatibilityGenerator/kotlin/`:**
- Purpose: `org.jmixworkbench.certification.CompatibilityFixtureGenerator` emits the exact Java/Kotlin fixture corpus compiled by the four compatibility cells (`jmix28Jdk17`, `jmix28Jdk21`, `jmix30Jdk21`, `jmix30Jdk25`).

**`webui/`:**
- Purpose: React 18 + TypeScript designer UI; Vite build with `base: './'` and strict dev port 5173 (`webui/vite.config.ts`); scripts `dev`/`build` (`tsc && vite build`)/`preview` (`webui/package.json`).
- Key files: `webui/src/App.tsx` (14 workspace tabs), `webui/src/store/index.ts`, `webui/src/bridge/index.ts`, `webui/src/types/index.ts`.

**`webui/src/components/`:**
- Purpose: One directory per designer feature.
- Contains: `EntityDesigner/` (designer + `entityModelAdapter.ts`, inheritance/event-listener/repository panels, embedded override editor, source evidence), `ViewDesigner/` (`ViewDesigner.tsx`, `ExistingFlowUiDesigner.tsx`, `FlowUiComponentCatalog.ts`), `CrudWizard/`, `MenuDesigner/`, `RoleDesigner/` (+ `ExistingRolePolicyEditor.tsx`, `SecurityWorkspace.tsx`), `ApiDesigner/`, `IntegrationDesigner/`, `WorkflowDesigner/` (+ `WorkflowSimulationDialog.tsx`), `LogicDesigner/`, `RuleDesigner/` (+ `DmnDecisionDesigner.tsx`), `ScenarioDesigner/`, `MigrationPanel/`, `ProjectProperties/` (+ `EnvironmentConfiguration.tsx`), `ProjectMap/`, `shared/` (`Toast.tsx`, `ResponsivePaneSwitcher.tsx`).

**`certification/`:**
- Purpose: Self-contained runtime certification labs with their own Gradle builds and Docker Compose files.
- Key files: `certification/database-runtime/run-matrix.sh`, `certification/database-runtime/docker-compose.yml`, `certification/integration-runtime/run-matrix.sh`.

**`docs/`:**
- Purpose: Normative project documentation (16 files), e.g. `docs/BUILDING.md`, `docs/COMPATIBILITY.md`, `docs/NATIVE-FLOWUI-EDITOR.md`, `docs/NATIVE-INDEX-ARCHITECTURE.md`, `docs/ENTERPRISE-PARITY-AUDIT.md`, `docs/RELEASE-INTEGRITY.md`.

## Key File Locations

**Entry Points:**
- `plugin/src/main/resources/META-INF/plugin.xml`: shared plugin descriptor (registration source of truth for extensions)
- `plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml`, `plugin/hosts/idea262/src/main/resources/META-INF/plugin.xml`: packaged host descriptors
- `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`: tool window boot
- `plugin/src/main/kotlin/org/jmixworkbench/actions/Actions.kt`: menu actions
- `webui/index.html`, `webui/src/main.tsx`, `webui/src/App.tsx`: UI boot and tab routing
- `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`: bridge dispatcher (114 actions)

**Configuration:**
- `plugin/gradle.properties`: plugin id/version
- `plugin/gradle/libs.versions.toml`: toolchain versions
- `plugin/gradle/verification-metadata.xml`: dependency verification
- `plugin/hosts/*/gradle.properties` + `plugin/hosts/*/gradle/dependency-locks/gradle.lockfile`: host lane state
- `webui/vite.config.ts`, `webui/tsconfig.json`, `webui/tailwind.config.js`, `webui/postcss.config.js`
- `plugin/src/main/resources/compatibility/phase2-registry.json`: compatibility registry data

**Core Logic:**
- `plugin/src/main/kotlin/org/jmixworkbench/services/ApplicationGraphService.kt`: semantic index service
- `plugin/src/main/kotlin/org/jmixworkbench/discovery/semantic/ApplicationGraphIndexer.kt`: pure indexer
- `plugin/src/main/kotlin/org/jmixworkbench/discovery/model/DiscoveryModel.kt`: evidence model
- `plugin/src/main/kotlin/org/jmixworkbench/discovery/change/WorkspaceChangePlanner.kt`: change planning
- `plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceChangeService.kt`: atomic apply/rollback
- `plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceHistoryService.kt`: undo/redo
- `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt`: generation orchestration
- `plugin/src/main/kotlin/org/jmixworkbench/generator/CrudOrchestrator.kt`: multi-file CRUD composition

**Testing:**
- `plugin/src/test/kotlin/`: host-lane tests (run by both idea253 and idea262)
- `plugin/src/phase2CoreTest/kotlin/`: platform-independent core tests
- `plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253DescriptorTest.kt`, `plugin/hosts/idea262/src/test/kotlin/org/jmixworkbench/host/idea262/Idea262DescriptorTest.kt`: host contract tests
- `plugin/buildSrc/src/test/java/org/jmixworkbench/build/`: build task tests
- `webui/`: no test framework configured (no test script or test dependencies in `webui/package.json`)

## Module Boundaries

**Aggregate vs host builds:**
- The aggregate `plugin/build.gradle.kts` keeps `main`/`test` source sets empty on purpose; IntelliJ-dependent production sources compile only inside the two host included builds (`plugin/settings.gradle.kts`). Running conventional `./gradlew test` at the aggregate level exercises only SDK-free source sets.
- Shared sources: both hosts compile `plugin/src/main/kotlin` and `plugin/src/test/kotlin`; host-local additions are limited to the descriptor, resources, and the descriptor tests.

**Resource merge rules (packaging):**
- Host `processResources` (`plugin/hosts/idea253/build.gradle.kts`) merges: host `src/main/resources` first, shared `plugin/src/main/resources` with `exclude("META-INF/plugin.xml")`, `plugin/build/generated-resources` (web bundle), `LICENSE`/`NOTICE`, and `project-template/` assets (including `plugin/gradle/wrapper/gradle-wrapper.jar`). `DuplicatesStrategy.FAIL` guards against silent conflicts.

**Web bundle flow:**
- `webui/` → `npmCi` → `compileWebUi` (stages `plugin/build/webui-dist`) → `buildWebUi` (`AssembleWebBundleTask` → `plugin/build/generated-resources/webui` with `build-info.json`) → `verifyWebBundle` → host `processResources` → plugin zip (verified by `VerifyPluginZipContentsTask`). Hosts read the bundle from `../../build/generated-resources/webui`.

**UI/backend contract:**
- Bridge action names and payload shapes are defined jointly by `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`, `plugin/src/main/kotlin/org/jmixworkbench/model/`, `webui/src/bridge/index.ts`, and `webui/src/types/index.ts`. Kotlin models and TypeScript interfaces are maintained manually in sync.

## Naming Conventions

**Directories:**
- Kotlin packages: lowercase single words under `org/jmixworkbench/` (`actions`, `bridge`, `discovery`, `editor`, `generator`, `ide`, `model`, `project`, `services`, `toolwindow`); discovery subpackages by concern (`change`, `compatibility`, `flowui`, `model`, `navigation`, `runtime`, `security`, `semantic`, `static`).
- Host lanes: `idea<build-number>` (`hosts/idea253`, `hosts/idea262`).
- Web UI features: PascalCase directory matching the primary component (`components/EntityDesigner/EntityDesigner.tsx`).
- Certification labs: kebab-case purpose names (`database-runtime`, `integration-runtime`).

**Files:**
- Kotlin: PascalCase per primary class; services end in `Service`, parsers in `Parser`, planners in `Planner`, change services in `ChangeService`, generators in `Generator`, patchers in `Patcher`, builders in `Builder`.
- Tests: `<Class>Test.kt`, integration tests `<Area>IntegrationTest.kt`, live-database matrix tests `*LiveMatrixTest.kt`.
- Web UI: PascalCase `.tsx` components, camelCase `.ts` helpers (`entityModelAdapter.ts`, `devMocks.ts`), `index.ts` for subsystem entries.

## Where to Add New Code

**New bridge action:**
- Backend handler: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` (add to the dispatch chain + a `handle*` method; parse payloads with `runCatching`; heavy work via background task pattern).
- Payload model: `plugin/src/main/kotlin/org/jmixworkbench/model/` (or the relevant service file for response types).
- Client method + types: `webui/src/bridge/index.ts`, `webui/src/types/index.ts`.

**New designer workspace:**
- Component directory: `webui/src/components/<Feature>/<Feature>.tsx`
- Tab registration: `workspaces` array and render switch in `webui/src/App.tsx`; `ActiveTab` union in `webui/src/store/index.ts`.
- Backend workspace service: `plugin/src/main/kotlin/org/jmixworkbench/services/<Feature>WorkspaceService.kt` + registration in all three descriptors (`plugin/src/main/resources/META-INF/plugin.xml`, `plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml`, `plugin/hosts/idea262/src/main/resources/META-INF/plugin.xml`).

**New generator:**
- Implementation: `plugin/src/main/kotlin/org/jmixworkbench/generator/<Name>Generator.kt` (stateless; return strings/plans, never write files).
- Tests: `plugin/src/test/kotlin/org/jmixworkbench/generator/<Name>GeneratorTest.kt`.
- Wiring: expose through a service and bridge preview/apply actions.

**New inspection/reference/index:**
- Implementation: `plugin/src/main/kotlin/org/jmixworkbench/ide/`.
- Registration: add to all three plugin descriptors (shared + both hosts); inspection description HTML in `plugin/src/main/resources/inspectionDescriptions/`.
- Tests: `plugin/src/test/kotlin/org/jmixworkbench/ide/`.

**New discovery/change core logic:**
- Implementation: `plugin/src/main/kotlin/org/jmixworkbench/discovery/<concern>/` (keep IDE-independent).
- Tests: `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/<concern>/` (JUnit 5, no IntelliJ SDK).

**Utilities:**
- Shared path safety: `plugin/src/main/kotlin/org/jmixworkbench/services/ProjectFileResolver.kt`
- Read-action helper: `plugin/src/main/kotlin/org/jmixworkbench/services/IntellijReadActions.kt`
- Java/XML rendering: `plugin/src/main/kotlin/org/jmixworkbench/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/org/jmixworkbench/generator/XmlBuilder.kt`

## Special Directories

**`plugin/build/`:**
- Purpose: Aggregate build outputs — `generated-resources/webui` (packaged web bundle), `webui-dist` (staged Vite output), `host-metadata/`, `compatibility/generated-sources`, reports.
- Generated: Yes. Committed: No.

**`webui/dist/` and `webui/node_modules/`:**
- Purpose: Vite output and npm dependencies.
- Generated: Yes. Committed: No.

**`plugin/hosts/*/gradle/dependency-locks/`:**
- Purpose: Strict dependency lock state per host lane (verified by `verifyLockedConfigurations`).
- Generated: No (maintained via Gradle locking). Committed: Yes.

**`plugin/gradle/wrapper/`:**
- Purpose: Checked-in Gradle wrapper (9.5.1 with sha256 pin) — the only supported build entry per `README.md`.
- Generated: No. Committed: Yes.

**`.planning/`:**
- Purpose: GSD workflow artifacts (phases, research, codebase maps, state).
- Generated: Partially (by GSD commands). Committed: Yes.

**`certification/*/`:**
- Purpose: Disposable certification labs with their own committed Gradle wrappers; evidence files are written at run time (not committed).
- Generated: Partially. Committed: Harness sources yes, run evidence no.

---

*Structure analysis: 2026-08-04*

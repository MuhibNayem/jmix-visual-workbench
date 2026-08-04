# Architecture

**Analysis Date:** 2026-08-04

## Pattern Overview

**Overall:** IntelliJ Platform plugin (Kotlin) hosting an embedded React/TypeScript workbench in JCEF, with an action-oriented JSON bridge, an evidence-based semantic project index, digest-bound preview/apply change planning, and dual IDE host build lanes.

**Key Characteristics:**
- One shared Kotlin source tree (`plugin/src/main/kotlin/org/jmixworkbench/`) is compiled by two isolated IntelliJ host builds: `plugin/hosts/idea253` (IDEA Ultimate 2025.3, JDK 21) and `plugin/hosts/idea262` (IDEA Ultimate 2026.2, JDK 25). Host descriptors live at `plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml` and `plugin/hosts/idea262/src/main/resources/META-INF/plugin.xml`; the aggregate `plugin/` build keeps its `main`/`test` source sets empty so no SDK-less compilation can happen (`plugin/build.gradle.kts`).
- The React UI is bundled by Vite, packaged into plugin resources, and served inside JCEF from a virtual origin `https://jmix-workbench.invalid` by `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt`. Browser content is treated as untrusted: the bridge is only injected for packaged-origin pages and every capability is server-side validated.
- All mutation follows a fixed pipeline: preview (with plan digest) → explicit apply referencing that digest → plan via `WorkspaceChangePlanner` → atomic `WriteCommandAction` write with exact byte-level rollback on failure → history record for undo/redo → application-graph invalidation (`plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceChangeService.kt`).
- Project understanding comes from an evidence-based discovery model (`plugin/src/main/kotlin/org/jmixworkbench/discovery/model/DiscoveryModel.kt`) and a pure semantic indexer (`plugin/src/main/kotlin/org/jmixworkbench/discovery/semantic/ApplicationGraphIndexer.kt`) that produces artifacts/relationships without executing Gradle.
- Compatibility is fail-closed: `plugin/src/main/kotlin/org/jmixworkbench/discovery/compatibility/CompatibilityRegistry.kt` loads `plugin/src/main/resources/compatibility/phase2-registry.json` and only certifies read-only cells for exact Jmix 2.8 / 3.0 profiles.
- Generators (`plugin/src/main/kotlin/org/jmixworkbench/generator/`) are stateless string producers; only the services layer writes to the user's project.

## Layers

**Plugin Registration (descriptors):**
- Purpose: Declare plugin id `org.jmixworkbench`, platform dependencies, tool windows, services, editors, indexes, inspections, and actions.
- Location: `plugin/src/main/resources/META-INF/plugin.xml` (shared descriptor), `plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml`, `plugin/hosts/idea262/src/main/resources/META-INF/plugin.xml` (host-specific descriptors that win packaging), `plugin/src/main/resources/META-INF/jmix-kotlin.xml` (optional Kotlin depends config — currently an empty `<idea-plugin/>` stub).
- Contains: `<depends>` on `com.intellij.modules.platform|java|xml`, `com.intellij.properties`, `com.intellij.gradle`, optional `org.jetbrains.kotlin`; one custom extension point `templateCatalogSigningProvider` (`org.jmixworkbench.project.JmixTemplateCatalogSigningProvider`); two tool windows; ~20 project services; two file editor providers; 11 file-based indexes; 7 reference contributors; 2 rename processors; 2 line markers; 14 local inspections (group `Jmix`, level `ERROR`, all `enabledByDefault`).
- Depends on: IntelliJ Platform extension points supplied by the host builds.
- Used by: IntelliJ at plugin load; the host `processResources` merge excludes the shared `META-INF/plugin.xml` so the host descriptor is authoritative in packaged artifacts (`plugin/hosts/idea253/build.gradle.kts`).
- Boundary note: the shared descriptor registers `referencesSearch` (`org.jmixworkbench.ide.JmixJpaMappedByReferenceSearchExecutor`) which is not present in the idea253 host descriptor; registration changes may need to be applied to all three descriptors.

**Tool Window Shell & Navigation:**
- Purpose: Create the JCEF browser, resolve the UI location (dev URL vs packaged bundle), attach the bridge, and route navigation requests into the embedded UI.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`, `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/WorkbenchLaunchContext.kt`, `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/WorkbenchNavigationService.kt`, `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt`, `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixRuntimePreviewToolWindow.kt`.
- Contains: `WorkbenchToolWindowStartup.plan(...)` producing `JcefUnavailable | WebBundleMissing | DevelopmentUrlRejected | Browser`; dev mode gated by `-Djmixworkbench.dev.enabled=true` plus a credential-free loopback `-Djmixworkbench.dev.url` (`WorkbenchBridgeAccess.NONE` in dev); packaged entry `/webui/index.html` served as `https://jmix-workbench.invalid/index.html`; `/flowui-editor.html` and `/entity-editor.html` mapped to `index.html`; `WorkbenchSurface` enum (`TOOL_WINDOW`, `FLOW_UI_EDITOR`, `ENTITY_EDITOR`, `ENTITY_DESIGNER`, `VIEW_DESIGNER`, `CRUD_DESIGNER`, `PROJECT_PROPERTIES`); `WorkbenchSurfaceOpenPolicy` restricts browser-requested surface opens to indexed evidence; second tool window `Jmix Runtime Preview` (bottom, closeable contents).
- Depends on: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`, JCEF APIs, the packaged web bundle produced by the build.
- Used by: `plugin/src/main/resources/META-INF/plugin.xml` tool window registrations; `plugin/src/main/kotlin/org/jmixworkbench/actions/Actions.kt` via `WorkbenchNavigationService.request(...)`.

**Native File Editors:**
- Purpose: Open Jmix FlowUI descriptors and entity sources in JCEF-backed file editors that reuse the same web bundle.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/editor/JmixFlowUiFileEditor.kt`, `plugin/src/main/kotlin/org/jmixworkbench/editor/JmixEntityFileEditor.kt`.
- Contains: `JmixFlowUiFileEditorProvider`/`JmixEntityFileEditorProvider` (`FileEditorProvider`, `acceptRequiresReadAction`), eligibility checks (`FlowUiFileEditorEligibility`), per-editor `JBCefBrowser` loading the packaged entry URL with an editor launch context.
- Depends on: `PackagedWorkbenchResourceHandler` infrastructure, `WorkbenchLaunchContext`, bridge.
- Used by: `fileEditorProvider` registrations in the plugin descriptors.

**IDE Actions:**
- Purpose: Entry points from the IDE menus into workbench surfaces, gated to detected Jmix projects.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/actions/Actions.kt`, `plugin/src/main/kotlin/org/jmixworkbench/actions/InjectJmixRepositoryAction.kt`.
- Contains: `JmixProjectAction` base (visibility via `JmixProjectService.isJmixProject()`); `OpenDesignerAction` (`TOOL_WINDOW`), `OpenProjectPropertiesAction` (`PROJECT_PROPERTIES`), `NewEntityAction` (`ENTITY_DESIGNER`), `NewViewAction` (`VIEW_DESIGNER`), `NewCrudAction` (`CRUD_DESIGNER`) — all call `WorkbenchNavigationService.getInstance(project).request(WorkbenchLaunchContext(surface))`; `InjectJmixRepositoryAction` (Generate menu) injects the Jmix Maven repository into build files with a confirmation dialog.
- Depends on: `plugin/src/main/kotlin/org/jmixworkbench/services/JmixProjectService.kt`, `WorkbenchNavigationService`.
- Used by: `<actions>` registrations (`JmixWorkbench.NewMenu` group in NewGroup; ToolsMenu; GenerateGroup).

**Native IDE Intelligence (`ide/`):**
- Purpose: Jmix-aware editor assistance in plain source files: references, completions, inspections, rename, line markers.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/ide/` (29 files), e.g. `JmixSymbolFileIndexes.kt`, `JmixReferenceResolution.kt`, `JmixFlowUiMetadata.kt`, `JmixSpringBeanSymbols.kt`, `JmixUiSecuritySymbols.kt`, `Jmix*ReferenceContributor.kt`, `Jmix*Inspection.kt`, `JmixSpringBeanRenameProcessor.kt`, `JmixMessageBundleRenameProcessor.kt`, `JmixViewDescriptorLineMarkerProvider.kt`.
- Contains: 4 symbol services (`JmixDomainSymbolService`, `JmixUiSecuritySymbolService`, `JmixSpringBeanSymbolService`, `JmixRestConfigurationSymbolService`), 11 candidate `fileBasedIndex` implementations (entity, view controller, specific policy, Spring bean, Spring stereotype usage, menu, fetch plan, FlowUI descriptor, REST descriptor, message bundle, Studio metadata), reference contributors for XML/JAVA/kotlin/Properties, fail-closed inspections.
- Depends on: IntelliJ PSI/index APIs; discovery evidence for candidate scoping.
- Used by: descriptor registrations; tests under `plugin/src/test/kotlin/org/jmixworkbench/ide/`.

**JCEF Bridge (backend dispatcher):**
- Purpose: Inject the JS bridge, parse/validate incoming JSON, dispatch 114 actions to services, marshal responses back to the browser.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` (~4,600 lines).
- Contains: protocol JS→Java `window.cefQuery({ request: JSON.stringify({ action, payload, requestId }) })`, Java→JS `window.onBridgeResponse(action, requestId, json)`; injection of `window.javaBridge.send(action, payload, requestId)`, `window.onBridgeReady()`, `window.onWorkbenchLaunchContext(...)`; push event `applicationGraphUpdated`; an `if (action == ...)` dispatch chain; per-handler `runCatching` payload parsing; background execution via `Task.Backgroundable` + `AppExecutorUtil.getAppExecutorService()` with `.finishOnUiThread(ModalityState.any())`; `invokeLater` for UI interactions; unknown actions return `{"error":"Unknown action: ..."}`; exceptions are logged (`log.error("Bridge error", e)`) and returned as error responses; injection guarded by `isPackagedWorkbenchOriginUrl(...)`.
- Depends on: services layer, discovery layer, models, Gson.
- Used by: `JmixWorkbenchToolWindowFactory` and the native file editors.

**Web UI Application:**
- Purpose: Render the visual workbench; collect inputs; call the bridge; show toasts.
- Location: `webui/index.html`, `webui/src/main.tsx`, `webui/src/App.tsx`, `webui/src/store/index.ts`, `webui/src/bridge/index.ts`, `webui/src/bridge/devMocks.ts`, `webui/src/types/index.ts`, `webui/src/components/**`.
- Contains: 14 workspace tabs in `webui/src/App.tsx` (`projectProperties`, `projectMap`, `entity`, `view`, `crud`, `menu`, `role`, `api`, `integration`, `workflow`, `logic`, `rules`, `scenario`, `migration`); native-editor mode detection via `window.location.pathname` (`/flowui-editor.html`, `/entity-editor.html`); launch-context handling (`FLOW_UI_EDITOR`, `ENTITY_EDITOR`, `ENTITY_DESIGNER`, `VIEW_DESIGNER`, `CRUD_DESIGNER`); Zustand store (default tab `projectMap`, project config, shared entity model, `flowUiLocator`, `crudEntityLocator`, toasts); `Bridge` class with pending queue until `onBridgeReady`, requestId-matched promises, workspace/graph caches, and a development simulation (backed by `webui/src/bridge/devMocks.ts`) that mirrors the digest-bound preview/apply protocol when `window.javaBridge` is absent; DOM event `jmix-workbench-index-updated` fired from `applicationGraphUpdated`.
- Depends on: bridge protocol; Tailwind styling (`webui/src/index.css`, `webui/tailwind.config.js`).
- Used by: `webui/index.html` (Vite); packaged into plugin resources by the build.

**Workspace Services (`services/`):**
- Purpose: Load per-domain workspace state, plan/validate/apply source-safe changes, and provide refactor/import workflows.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/services/` (~52 files).
- Contains, by role:
 - Core indexing/detection: `ApplicationGraphService.kt` (`graph(forceRefresh)`, `progress()`, incremental document listener, `invalidate()`), `JmixProjectService.kt` (Jmix detection, base package/version/database heuristics, cached config), `ProjectFileResolver.kt` (registered-root resolution; rejects `\`, `..`, blank segments), `IntellijReadActions.kt` (`cancellableRead` helper), `ProjectSourceDestinationService.kt`, `ProjectSourceText.kt`.
 - Change engine: `WorkspaceChangeService.kt` (`preview`, `prepareApply`, `applyPrepared` in `WriteCommandAction`, exact rollback with byte verification, `JVW-CHANGE-ROLLBACK-FAILED`), `WorkspaceHistoryService.kt` (undo/redo with `WorkspaceMutationPhase` checkpoints), `WorkspaceMutationProbe.kt`.
 - Generation orchestration: `CodeGenerationService.kt` (`preview*/prepare*/generate*` for entity, CRUD, view, migration, role, menu, BPM/workflow, database import; applies plans through `WorkspaceChangeService`).
 - Domain workspaces: `FlowUiWorkspaceService.kt`, `MenuWorkspaceService.kt`, `SecurityWorkspaceService.kt`, `SchemaWorkspaceService.kt`, `RestApiWorkspaceService.kt`, `ScenarioWorkspaceService.kt`, `VisualLogicWorkspaceService.kt`, `VisualRuleWorkspaceService.kt`, `DmnDecisionWorkspaceService.kt`, `IntegrationConnectorWorkspaceService.kt`, `WorkflowWorkspaceService.kt`, `JmixProjectPropertiesService.kt`, `JmixEnvironmentConfigurationService.kt`.
 - Source change services: `FlowUiControllerChangeService.kt`, `FlowUiControllerPsiReader.kt`, `AggregateUpdateServiceChangeService.kt`, `DataRepositoryChangeService.kt`, `SecurityRoleChangeService.kt`, `RestApiChangeService.kt`, `ExistingEntityChangeService.kt`, `EntityEventListenerService.kt`, `EntityAttributePropagationService.kt`, `EntityAttributeRefactorService.kt`, `EntityAttributeTypeExpansionService.kt`, `EntityAttributeTypeCutoverService.kt`, `RepositoryMethodRefactorService.kt`.
 - Reverse engineering / contracts: `DatabaseReverseEngineeringService.kt`, `DatabaseEntityImportPlanner.kt`, `DatabaseEntityImportProfileService.kt`, `OpenApiContractService.kt`, `OpenApiDocumentBundler.kt`, `OpenApiContractEvolutionAnalyzer.kt`, `OpenApiEvolutionApprovalService.kt`, `OpenApiJmixEvolutionRemapPlanner.kt`.
 - Parsers/runtime: `MigrationJsonParser.kt`, `DmnDecisionParser.kt`, `WorkflowXmlParser.kt`, `RepositorySourceParser.kt`, `RepositorySemanticAnalyzer.kt`, `JmixRuntimeService.kt` (runtime inspection + FlowUI hot deploy proposals), `RuntimeSecurityEvidenceService.kt`.
- Depends on: discovery layer, generator layer, IntelliJ VFS/PSI/write-command APIs.
- Used by: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` (all handlers); registered as project services in the descriptors.

**Discovery & Indexing (`discovery/`):**
- Purpose: IDE-independent evidence model, semantic indexing, change planning, compatibility gating, and format parsers.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/discovery/`.
- Contains:
 - `model/DiscoveryModel.kt`: `Evidence<T>`, `EvidenceConfidence` (`EXACT|STRONG|WEAK|CONFLICTING`), `EvidenceSourceKind`, `TrustState`, `ImportState`, `BuildKind`, `ModuleRole`, `SourceRootKind`, ~60-value `ArtifactKind`, `ArtifactOrigin`, `RelationshipType`, `DiagnosticSeverity/Category`, `CompatibilityState`, `DiscoverySnapshot`, `BuildSnapshot`, `ModuleSnapshot`, `SourceRootSnapshot`, `DependencyFact`, `JmixProfile` (includes `CUBA`/`FUTURE`/`NOT_DETECTED` classifications), `ArtifactSnapshot`, `ArtifactRelationship`, `DiscoveryDiagnostic`, `SourceLocator`, `CompatibilityDecision`.
 - `model/CanonicalDiscoveryJson.kt`: deterministic canonical JSON writer for evidence.
 - `semantic/ApplicationGraphIndexer.kt`: pure indexer (`GraphSourceFile` input) covering JVM sources, view/menu/fetch-plan/REST/Liquibase/workflow/DMN XML, properties/YAML/SQL, report templates, integration properties.
 - `change/WorkspaceChangePlanner.kt`: `WorkspaceChangeSet` → `WorkspaceChangePlan` (`PlannedWorkspaceFile`, create/modify/delete planning); `change/SourcePreservingMerge.kt`: merge logic that preserves manual source.
 - `compatibility/CompatibilityRegistry.kt`: loads `compatibility/phase2-registry.json`, enforces `CERTIFIED_READ_ONLY` cells for exact Jmix 2.8/3.0 with `P2_CERTIFIED_READ_ONLY` reason codes; future/unknown versions are uncertified.
 - `flowui/FlowUiDescriptorParser.kt`, `navigation/SourceNavigationPolicy.kt`, `runtime/JmixRuntimeConfiguration.kt` (`JmixRuntimeConfigurationParser`), `security/SecurityWorkspaceBuilder.kt` + `security/RuntimeSecurityEvidenceModel.kt`, `static/GradleConfigParser.kt` (token-based; explicitly no Gradle/Groovy/process/network integration).
- Depends on: JDK/Gson only (kept platform-independent; covered by the `phase2Core` source set).
- Used by: services layer; tested by `plugin/src/phase2CoreTest/kotlin/`.

**Generators (`generator/`):**
- Purpose: Deterministic rendering/patching of target-project artifacts from models.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/generator/` (25 files).
- Contains: builders `JavaClassBuilder.kt`, `XmlBuilder.kt`; `EntityGenerator.kt` + `KotlinEntityGenerator.kt`; `DataRepositoryGenerator.kt` + `KotlinDataRepositoryGenerator.kt`; `ViewXmlGenerator.kt`, `ViewControllerGenerator.kt`, `CrudOrchestrator.kt` (`generate(entity, config, options)` composing multi-file CRUD output); `MigrationGenerator.kt`; `MenuGenerator.kt` + `MenuSourcePatcher.kt`; `RoleGenerator.kt`; `ScenarioTestGenerator.kt`; `BpmGenerator.kt`; `DmnDecisionGenerator.kt`; `VisualLogicGenerator.kt`; `VisualRuleGenerator.kt`; `IntegrationConnectorGenerator.kt`; `OpenApiJmixLayerGenerator.kt`; `RestApiSourcePatcher.kt`; `EventListenerGenerator.kt`; `AggregateUpdateServiceGenerator.kt`.
- Depends on: `model/`, builders, JDK only.
- Used by: `services/CodeGenerationService.kt`, `services/*WorkspaceService.kt`, `services/*ChangeService.kt`. Generators never write files.

**Bridge Models (`model/`):**
- Purpose: Closed Kotlin payload contracts deserialized from TypeScript-shaped JSON.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/model/` — `EntityModel.kt`, `ViewModel.kt`, `MigrationModel.kt`, `RoleModel.kt`, `ProjectConfig.kt`, `ScenarioModel.kt`, `VisualLogicModel.kt`, `VisualRuleModel.kt`, `WorkflowModel.kt`, `DmnDecisionModel.kt`, `IntegrationConnectorModel.kt`.
- Depends on: Gson annotations.
- Used by: bridge handlers, services, generators. Mirrored manually in `webui/src/types/index.ts`.

**Project Creation (`project/`):**
- Purpose: New-project wizard, template generation/installation, signed organization template and connector catalogs.
- Location: `plugin/src/main/kotlin/org/jmixworkbench/project/` — `JmixNewProjectWizard.kt` (`GeneratorNewProjectWizard`), `JmixProjectTemplateGenerator.kt` (object with `GeneratedJmixProject`, validation exception), `JmixProjectInstaller.kt`, `JmixFlowUiProjectTemplate.kt`, `JmixOrganizationTemplateCatalog.kt`, `JmixTemplateCatalogSettings.kt`, `JmixTemplateCatalogConfigurable.kt`, `JmixTemplateCatalogAuthoring.kt`, `JmixTemplateCatalogAuthoringDialog.kt`, `JmixTemplateCatalogSigningProvider.kt` (custom extension point interface), `JmixTemplateOverlayPlanner.kt`, `JmixConnectorCatalogAuthoringDialog.kt`.
- Depends on: IntelliJ wizard/configurable APIs; packaged `project-template/` resources (Gradle wrapper jar and template files merged by host `processResources`).
- Used by: `newProjectWizard.generator` and `applicationConfigurable` registrations.

**Build & Host Lanes:**
- Purpose: Compile the shared sources against two IntelliJ versions, package the web bundle, and verify integrity.
- Location: `plugin/build.gradle.kts` (aggregate), `plugin/settings.gradle.kts`, `plugin/gradle/libs.versions.toml`, `plugin/gradle/verification-metadata.xml`, `plugin/buildSrc/src/main/java/org/jmixworkbench/build/` (`AssembleWebBundleTask`, `VerifyWebBundleTask`, `VerifyPluginZipContentsTask`, `SnapshotFileHashTask`, `WebBundleFingerprint`), `plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`.
- Contains: included builds `idea253`/`idea262`; source sets `phase2Core`, `phase2CoreTest`, `compatibilityGenerator`; compatibility compile cells `jmix28Jdk17`/`jmix28Jdk21` (Jmix 2.8.2) and `jmix30Jdk21`/`jmix30Jdk25` (Jmix 3.0.0) against `io.jmix.bom`; npm pipeline tasks `snapshotNpmLockHash` → `npmCi` → `compileWebUi` → `buildWebUi` → `verifyWebBundle`; `verifyHostToolchains` (idea253 must be Java 21, idea262 Java 25) and `verifyHostBuildDefinitions` (immutable host contracts, e.g. `intellijIdeaUltimate("2025.3")`, `sinceBuild 253/262`); strict dependency locking per host (`plugin/hosts/*/gradle/dependency-locks/gradle.lockfile`).
- Depends on: Gradle wrapper 9.5.1 (`plugin/gradle/wrapper/gradle-wrapper.properties`), Node Gradle plugin 7.1.0 with pinned Node runtime 24.18.0, IntelliJ Platform Gradle plugin 2.18.0.
- Used by: `./gradlew` commands from `plugin/` (`README.md` documents `./gradlew test --dependency-verification=strict`).

**Certification Harnesses:**
- Purpose: Out-of-IDE runtime evidence for generated code.
- Location: `certification/database-runtime/` (same Jmix domain model against five real databases via Docker Compose; `run-matrix.sh`; fixture credentials supplied via `CERT_*` env vars), `certification/integration-runtime/` (executes production-generated connector sources; `run-matrix.sh`).
- Contains: standalone Gradle builds with committed wrappers, Spring Boot applications (`RuntimeCertificationApplication.java`, `IntegrationRuntimeCertificationApplication.java`), Liquibase changelogs.
- Used by: manual/CI certification runs; referenced by `docs/DATABASE-RUNTIME-CERTIFICATION.md` and `docs/PROJECT-TEMPLATE-CERTIFICATION.md`.

## Data Flow

**Tool window boot:**
1. IntelliJ instantiates `JmixWorkbenchToolWindowFactory` (`plugin/src/main/resources/META-INF/plugin.xml` tool window `Jmix Visual Workbench`, anchor right).
2. `WorkbenchToolWindowStartup.plan(...)` checks `JBCefApp.isSupported()`, dev-mode system properties, then packaged resource `/webui/index.html`.
3. For packaged mode the browser installs `PackagedWorkbenchRequestHandler` and loads `https://jmix-workbench.invalid/index.html`; the handler serves bundle files from plugin resources.
4. On load end `JcefBridge` injects `window.javaBridge`, publishes `window.jmixWorkbenchLaunchContext`, and fires `window.onBridgeReady()`.
5. `webui/src/App.tsx` mounts, calls `bridge.getProjectConfig()`, subscribes to `jmix-workbench-index-updated`, and renders the active workspace.
6. Failure modes render labeled errors with codes `JVW-JCEF-UNAVAILABLE`, `JVW-WEB-BUNDLE-MISSING`, or `JVW-DEV-URL-REJECTED` (`plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`).

**Navigation / launch context:**
1. IDE action (or bridge action `openWorkbenchSurface`) calls `WorkbenchNavigationService.request(WorkbenchLaunchContext(surface, sourceLocator))`.
2. The tool window bridge publishes the context via `window.onWorkbenchLaunchContext(...)`.
3. `webui/src/App.tsx` switches `activeTab` / opens the FlowUI or entity designer for the locator.
4. Browser-requested surface opens (`openWorkbenchSurface`) pass `WorkbenchSurfaceOpenPolicy.prepare(...)` which accepts only indexed evidence (`CRUD_DESIGNER` requires an indexed entity locator; `FLOW_UI_EDITOR` requires an indexed `VIEW_DESCRIPTOR`); denials return `JVW-WORKBENCH-*` codes.

**Preview → apply mutation cycle:**
1. UI calls a `preview*` bridge action; the handler parses the payload (`runCatching`) and asks a service for a `WorkspaceChangeSet`.
2. `WorkspaceChangePlanner.plan(...)` produces a `WorkspaceChangePlan`; `WorkspaceChangeService.preview(...)` returns `WorkspaceChangePreviewResponse` with `changeSetId` and `planDigest`.
3. UI shows the diff; user approval calls the matching `apply*` action including `expectedPlanDigest`.
4. The backend re-prepares (`prepareApply`), verifies the digest, then `applyPrepared` runs inside `WriteCommandAction.runWriteCommandAction`; any failure triggers exact byte-level rollback (`JVW-CHANGE-ROLLBACK-FAILED` if rollback itself fails).
5. On success `WorkspaceHistoryService.record(...)` stores the entry for undo/redo and `ApplicationGraphService.invalidate()` refreshes the index.

**Index lifecycle:**
1. `ApplicationGraphService.graph()` builds the snapshot via `ApplicationGraphIndexer` over files resolved by `ProjectFileResolver`, with `progress()` polled by `getApplicationGraphProgress`.
2. A document listener re-indexes changed files incrementally; updates are pushed to the UI as `applicationGraphUpdated`, surfaced as the `jmix-workbench-index-updated` CustomEvent (`webui/src/bridge/index.ts`, `webui/src/App.tsx`).
3. Mutations invalidate the graph so subsequent reads re-index.

**State Management:**
- UI-global state: single Zustand store `webui/src/store/index.ts` (active tab, project config, shared entity, designer locators, toasts). Per-designer drafts live in component-local state.
- Backend per-project state: IntelliJ project services (detection cache in `JmixProjectService`, graph cache in `ApplicationGraphService`, history in `WorkspaceHistoryService`).
- Client caches: workspace and application-graph caches inside the `Bridge` class (`webui/src/bridge/index.ts`).
- Generators and the discovery layer are stateless.

## Key Abstractions

**Evidence / DiscoverySnapshot / SourceLocator:**
- Purpose: Every project fact carries confidence, source kind, and trust state; locators are project-relative and revision-checked.
- Examples: `plugin/src/main/kotlin/org/jmixworkbench/discovery/model/DiscoveryModel.kt`
- Pattern: Immutable data classes + enums; only locators cross the JCEF boundary (`plugin/src/main/kotlin/org/jmixworkbench/toolwindow/WorkbenchLaunchContext.kt`).

**WorkspaceChangeSet / WorkspaceChangePlan:**
- Purpose: Declarative description of intended file creates/modifies/deletes, planned into exact bytes before any write.
- Examples: `plugin/src/main/kotlin/org/jmixworkbench/discovery/change/WorkspaceChangePlanner.kt`, `plugin/src/main/kotlin/org/jmixworkbench/discovery/change/SourcePreservingMerge.kt`
- Pattern: Pure planning objects consumed by `WorkspaceChangeService`.

**Digest-bound previews (planDigest / expectedPlanDigest):**
- Purpose: Prevent stale-preview application; apply is rejected unless it references the exact digest of the approved preview.
- Examples: preview/apply handler pairs in `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`; mirrored in the dev simulation in `webui/src/bridge/index.ts`.
- Pattern: Preview returns `changeSetId` + `planDigest`; apply requires `expectedPlanDigest`.

**WorkbenchLaunchContext / WorkbenchSurface:**
- Purpose: Trusted JVM-to-web navigation contract; the web UI cannot choose or elevate its own native editor surface.
- Examples: `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/WorkbenchLaunchContext.kt`
- Pattern: Enum surfaces + optional `SourceLocator`, validated by `WorkbenchSurfaceOpenPolicy`.

**CompatibilityRegistry / CompatibilityProfile:**
- Purpose: Per-profile, per-operation compatibility decisions; fail-closed for uncertified versions.
- Examples: `plugin/src/main/kotlin/org/jmixworkbench/discovery/compatibility/CompatibilityRegistry.kt`, `plugin/src/main/resources/compatibility/phase2-registry.json`
- Pattern: JSON registry loaded with strict validation (`CERTIFIED_READ_ONLY` only in Phase 2).

**GenerationResult:**
- Purpose: Uniform success flag, written-file list, and error list across generation workflows.
- Examples: `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt`, mirrored in `webui/src/types/index.ts`.
- Pattern: Data class serialized by Gson.

**Bridge client / dispatcher pair:**
- Purpose: Hide global JCEF callbacks behind promise-based typed methods.
- Examples: `webui/src/bridge/index.ts` (singleton `bridge`), `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`.
- Pattern: requestId-matched promises over `window.javaBridge`/`window.onBridgeResponse`.

**JavaClassBuilder / XmlBuilder:**
- Purpose: Shared fluent rendering for generated Java and XML.
- Examples: `plugin/src/main/kotlin/org/jmixworkbench/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/org/jmixworkbench/generator/XmlBuilder.kt`.
- Pattern: Mutable builders used only during synchronous generator calls.

## Entry Points

**Plugin descriptor:**
- Location: `plugin/src/main/resources/META-INF/plugin.xml` (+ host copies under `plugin/hosts/*/src/main/resources/META-INF/plugin.xml`)
- Triggers: IntelliJ plugin loading.
- Responsibilities: Register tool windows, editors, services, indexes, inspections, actions, extension point.

**Tool window factory:**
- Location: `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`
- Triggers: Opening the `Jmix Visual Workbench` tool window.
- Responsibilities: Startup plan, browser creation, bridge attachment, navigation subscription, disposal.

**IDE actions:**
- Location: `plugin/src/main/kotlin/org/jmixworkbench/actions/Actions.kt`, `plugin/src/main/kotlin/org/jmixworkbench/actions/InjectJmixRepositoryAction.kt`
- Triggers: New menu group `Jmix Visual Workbench`, Tools menu, Generate menu.
- Responsibilities: Gate on Jmix detection, open workbench surfaces, inject repository.

**Web UI boot:**
- Location: `webui/index.html`, `webui/src/main.tsx`, `webui/src/App.tsx`
- Triggers: JCEF load of packaged `/index.html` (or `/flowui-editor.html` / `/entity-editor.html` aliases) or Vite dev server in dev mode.
- Responsibilities: Mount React app, fetch project config, apply launch context, render the active workspace.

**Bridge dispatcher:**
- Location: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`
- Triggers: `window.javaBridge.send` calls.
- Responsibilities: Parse, validate, dispatch 114 actions, return JSON responses.

**Build entry points:**
- Location: `plugin/build.gradle.kts`, `plugin/settings.gradle.kts`, `plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`, `webui/package.json`
- Triggers: `cd plugin && ./gradlew test --dependency-verification=strict` (README-documented), host `buildPlugin`/`runIde` tasks, `npm run dev|build` in `webui/`.
- Responsibilities: Web bundle assembly, host compilation, plugin zip packaging and verification.

**Certification entry points:**
- Location: `certification/database-runtime/run-matrix.sh`, `certification/integration-runtime/run-matrix.sh`
- Triggers: manual/CI certification.
- Responsibilities: Run runtime evidence matrices.

## Error Handling

**Strategy:** Structured, code-bearing errors at every boundary; fail-closed compatibility; exact rollback for failed writes.

**Patterns:**
- Error codes use the `JVW-` prefix: tool window (`JVW-JCEF-UNAVAILABLE`, `JVW-WEB-BUNDLE-MISSING`, `JVW-DEV-URL-REJECTED` in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`), surface policy (`JVW-WORKBENCH-SURFACE-SOURCE-REQUIRED`, `JVW-WORKBENCH-ENTITY-SOURCE-STALE`, `JVW-WORKBENCH-VIEW-SOURCE-STALE`, `JVW-WORKBENCH-SURFACE-DENIED` in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/WorkbenchLaunchContext.kt`), mutation (`JVW-CHANGE-ROLLBACK-FAILED` in `plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceChangeService.kt`), generator-level (`error("LOGIC_ENTRY_POINT_REQUIRED", ...)` in `plugin/src/main/kotlin/org/jmixworkbench/generator/VisualLogicGenerator.kt`).
- Bridge dispatch wraps parsing and handling in `catch (e: Exception)` → `log.error("Bridge error", e)` and returns an error response; unknown actions return `{"error":"Unknown action: ..."}` (`plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`).
- Handlers parse payloads with `runCatching { gson.fromJson(...) }.getOrElse { error -> ... }` and return structured error objects instead of throwing.
- Apply failures trigger byte-exact rollback; rollback verification failures aggregate suppressed errors (`WorkspaceRollbackFailure`).
- UI surfaces errors via toasts (`webui/src/store/index.ts`) and by inspecting `error` fields on responses (`webui/src/App.tsx`, designer components).
- Dev simulation reproduces stale-preview rejection (`JVW-PROJECT-PROPERTIES-DEVELOPMENT-REQUEST-INVALID` etc.) in `webui/src/bridge/index.ts`.

## Cross-Cutting Concerns

**Security (untrusted JCEF content):** Bridge injection only for packaged-origin pages (`isPackagedWorkbenchOriginUrl` checks in `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`); action allowlisting via the dispatch chain; browser surface opens validated against indexed evidence; path traversal rejected in `plugin/src/main/kotlin/org/jmixworkbench/services/ProjectFileResolver.kt`; dev URLs restricted to credential-free loopback; containerized certification uses fixture credentials only (`certification/database-runtime/README.md`).

**Threading:** Reads use `cancellableRead` (`plugin/src/main/kotlin/org/jmixworkbench/services/IntellijReadActions.kt`); heavy bridge handlers run as background tasks on `AppExecutorUtil` and finish on the UI thread; writes run in `WriteCommandAction`; JS responses execute via `executeJavaScript`.

**Logging:** `Logger.getInstance(...)` per integration-facing class; bridge logs each request action except polling (`plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`).

**Compatibility gating:** `CompatibilityRegistry` decisions feed discovery diagnostics; build-level compile cells certify generated code against Jmix 2.8.2/3.0.0 BOMs (`plugin/build.gradle.kts`).

**Determinism & integrity:** Canonical JSON (`plugin/src/main/kotlin/org/jmixworkbench/discovery/model/CanonicalDiscoveryJson.kt`); web bundle fingerprinting and verification (`plugin/buildSrc/src/main/java/org/jmixworkbench/build/WebBundleFingerprint.java`, `VerifyWebBundleTask`); npm lock hash snapshots (`SnapshotFileHashTask`); plugin zip content verification (`VerifyPluginZipContentsTask`); strict dependency verification (`plugin/gradle/verification-metadata.xml`) and per-host strict lockfiles.

**Implemented-vs-stubbed boundaries:** `plugin/src/main/resources/META-INF/jmix-kotlin.xml` is an empty `<idea-plugin/>` stub (Kotlin-language extensions are registered directly in the main descriptors with `language="kotlin"`); generators are reachable only through services/bridge — no generator writes files directly; the aggregate build's `main`/`test` source sets are intentionally empty; `README.md` claims remain bounded by `docs/ENTERPRISE-PARITY-AUDIT.md`.

---

*Architecture analysis: 2026-08-04*

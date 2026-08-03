# Architecture

**Analysis Date:** 2026-07-27

## Pattern Overview

**Overall:** Embedded web application inside an IntelliJ Platform plugin, connected to a Kotlin code-generation backend through an in-process JSON command bridge.

**Key Characteristics:**
- The IDE host is the outer runtime: `plugin/src/main/resources/META-INF/plugin.xml` registers the tool window, project services, and IDE actions.
- The visual designer is a client-side React application: `webui/src/main.tsx` mounts `webui/src/App.tsx`, which conditionally renders the six designer components under `webui/src/components/`.
- The runtime boundary is action-oriented rather than REST-oriented: `webui/src/bridge/index.ts` sends `{ action, payload }` messages and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` dispatches those actions.
- Kotlin model objects are the backend generation contract: `plugin/src/main/kotlin/com/jmixstudio/model/` is deserialized from TypeScript-shaped payloads declared in `webui/src/types/index.ts`.
- Generators are mostly stateless transformations from model objects to source strings: `plugin/src/main/kotlin/com/jmixstudio/generator/` contains singleton generator objects and the reusable `JavaClassBuilder` and `XmlBuilder`.
- Project mutation is centralized in one IntelliJ project service: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` writes generated files and refreshes the IDE virtual file system.
- Documentation claims are not runtime evidence: `README.md` describes intended breadth, while the implemented command set and reachable UI are defined by `webui/src/App.tsx`, `webui/src/bridge/index.ts`, and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

## Runtime Topology

```text
`plugin/src/main/resources/META-INF/plugin.xml`
        |
        v
`plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`
        |
        +--> JCEF loads `webui/dist/index.html` or the Vite dev URL
        |
        v
`webui/src/App.tsx` + `webui/src/components/**`
        |
        v
`webui/src/bridge/index.ts` -- JSON action/payload -->
`plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
        |
        +--> `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`
        +--> `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`
                    |
                    v
        `plugin/src/main/kotlin/com/jmixstudio/generator/**`
                    |
                    v
        generated files in the open Jmix project
```

## Layers

**IntelliJ Extension Registration:**
- Purpose: Declare plugin identity, compatibility, tool-window factory, project services, and IDE actions.
- Location: `plugin/src/main/resources/META-INF/plugin.xml`
- Contains: One `toolWindow` extension, two `projectService` extensions, and four action registrations.
- Depends on: IntelliJ Platform extension points configured by `plugin/build.gradle.kts`.
- Used by: IntelliJ startup and action-system discovery before any Kotlin entry point runs.

**IDE Entry and Host UI:**
- Purpose: Create the embedded browser, resolve the web UI URL, attach the bridge, and expose the designer as a tool window.
- Location: `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`
- Contains: JCEF support detection, `JBCefBrowser` creation, development/production/fallback URL resolution, content disposal, and unconditional tool-window availability.
- Depends on: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, JCEF APIs, and the bundled `webui/dist` artifact copied by `plugin/build.gradle.kts`.
- Used by: The `Jmix Studio` tool-window extension in `plugin/src/main/resources/META-INF/plugin.xml`.

**IDE Actions:**
- Purpose: Expose the designer through the Tools menu and New menu for recognized Jmix projects.
- Location: `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`
- Contains: `OpenDesignerAction`, `NewEntityAction`, `NewViewAction`, and `NewCrudAction`.
- Depends on: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` for action visibility and IntelliJ `ToolWindowManager` for activation.
- Used by: Action declarations in `plugin/src/main/resources/META-INF/plugin.xml`.
- Implemented boundary: The three “New” actions only show the tool window; `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt` contains no call that selects the matching tab in `webui/src/store/index.ts`.

**React Application Shell:**
- Purpose: Mount the designer application, request project configuration, own tab navigation, and render global notifications.
- Location: `webui/src/main.tsx`, `webui/src/App.tsx`, `webui/src/index.css`
- Contains: React root creation, six tab definitions, conditional designer mounting, sidebar layout, and the shared `Toast`.
- Depends on: `webui/src/store/index.ts`, `webui/src/bridge/index.ts`, and all component entry files under `webui/src/components/`.
- Used by: `webui/index.html`, built by `webui/vite.config.ts` into `webui/dist/`.

**Designer Features:**
- Purpose: Gather generation inputs and submit typed models to the backend.
- Location: `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/components/CrudWizard/CrudWizard.tsx`, `webui/src/components/MenuDesigner/MenuDesigner.tsx`, `webui/src/components/RoleDesigner/RoleDesigner.tsx`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`
- Contains: Entity form and preview, drag-and-drop view component tree, CRUD wizard, menu tree editor, role policy editor, and a seven-change-type migration editor.
- Depends on: Shared Zustand state from `webui/src/store/index.ts`, request helpers from `webui/src/bridge/index.ts`, and payload contracts from `webui/src/types/index.ts`.
- Used by: Conditional rendering in `webui/src/App.tsx`.
- Implemented boundary: `webui/src/components/MenuDesigner/MenuDesigner.tsx` submits `generateMenu`, but `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` has no `generateMenu` dispatch branch.

**Client State:**
- Purpose: Share the active tab, detected project configuration, the entity under design, generation status/results, and toast notifications.
- Location: `webui/src/store/index.ts`
- Contains: A single Zustand store with immutable update functions and default entity/attribute factories.
- Depends on: Model interfaces from `webui/src/types/index.ts`.
- Used by: `webui/src/App.tsx` and all files under `webui/src/components/`.
- State boundary: Entity and CRUD state is shared through `webui/src/store/index.ts`; view, menu, role, and migration editor data is local React state in their respective files under `webui/src/components/`.

**Client Bridge Adapter:**
- Purpose: Queue commands until JCEF injects the bridge, route responses to promises, and simulate generation responses during Vite development.
- Location: `webui/src/bridge/index.ts`
- Contains: Global `window.javaBridge`, `window.onBridgeReady`, and `window.onBridgeResponse` contracts; pending command queue; listener registry; request helpers for entity, CRUD, view, migration, role, BPM, and project configuration.
- Depends on: JCEF-injected functions created by `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Used by: `webui/src/App.tsx` and the designer components under `webui/src/components/`.
- Protocol boundary: Requests carry an action name but no request ID in `webui/src/bridge/index.ts`; promise resolution is matched by action name.

**Server Bridge Adapter:**
- Purpose: Inject the JavaScript bridge into JCEF, parse incoming JSON, dispatch backend operations, and execute response callbacks in the browser.
- Location: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Contains: `JBCefJSQuery` lifecycle, Gson deserialization, action routing, service calls, logging, and JavaScript response execution.
- Depends on: Models in `plugin/src/main/kotlin/com/jmixstudio/model/`, generators in `plugin/src/main/kotlin/com/jmixstudio/generator/`, and services in `plugin/src/main/kotlin/com/jmixstudio/services/`.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
- Implemented commands: `generateEntity`, `generateCrud`, `generateView`, `generateMigration`, `generateRole`, `generateBpm`, `getProjectConfig`, `getEntities`, and `ping` are enumerated in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

**Project Discovery and Configuration:**
- Purpose: Decide whether the open project is a Jmix project and infer generation roots, base package, Jmix version, and database type.
- Location: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`
- Contains: Root Gradle file lookup, marker-string detection, group/package inference, dependency-string database detection, version inference, cached configuration, and output-path helpers.
- Depends on: IntelliJ `Project` and `VirtualFile` APIs.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt` and every backend handler in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Scope boundary: Only root `build.gradle` or `build.gradle.kts` is inspected by `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`; multi-module discovery is not implemented there.

**Shared Domain Models:**
- Purpose: Represent entities, views, migrations, roles, and target-project configuration independent of code rendering.
- Location: `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/RoleModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`
- Contains: Kotlin data classes, sealed migration changes, enums with Gson serialized names, computed names, and path helpers.
- Depends on: Gson annotations from the dependency declared in `plugin/build.gradle.kts`.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, and all generator files under `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Cross-language contract: Equivalent but not identical payload interfaces are maintained manually in `webui/src/types/index.ts`.

**Generation Orchestration:**
- Purpose: Convert one entity definition into a coordinated entity/migration/view/menu/role/messages/repository/fetch-plan output set.
- Location: `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`
- Contains: `CrudOptions`, generated-file metadata, view-model construction, generator composition, and path selection.
- Depends on: Every primary model and most generators under `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` through the `generateCrud` bridge action.
- Write behavior: The orchestrator only returns strings and paths; `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` performs the actual writes.

**Specialized Generators:**
- Purpose: Render specific Jmix artifacts from backend models.
- Location: `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MenuGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/DataRepositoryGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt`
- Contains: Kotlin singleton objects with `generate` functions that return Java, XML, or BPMN text.
- Depends on: Models in `plugin/src/main/kotlin/com/jmixstudio/model/` and builders in `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, or other specialized generators.
- Reachability boundary: `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt` is not referenced by the bridge, service, or CRUD orchestrator; `plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt` is backend-reachable but has no tab in `webui/src/App.tsx`.

**Source Builders:**
- Purpose: Provide reusable fluent construction and formatting for generated Java and XML.
- Location: `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`
- Contains: Package/import/class/member rendering in `JavaClassBuilder` and namespace/attribute/element/text rendering in `XmlBuilder`.
- Depends on: Kotlin/JDK standard library only within those two files.
- Used by: Specialized generators under `plugin/src/main/kotlin/com/jmixstudio/generator/`.

**Generation Application Service:**
- Purpose: Choose output paths, invoke generators, mutate the open project, package success/error results, and refresh IntelliJ VFS state.
- Location: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`
- Contains: Per-artifact workflows, `GenerationResult`, `writeFile`, `appendFile`, message rendering, and VFS refresh.
- Depends on: Models in `plugin/src/main/kotlin/com/jmixstudio/model/`, generators in `plugin/src/main/kotlin/com/jmixstudio/generator/`, Java `File`, and IntelliJ write-command/VFS APIs.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Mutation boundary: This is the only implemented layer that writes to the user’s Jmix project; generator files under `plugin/src/main/kotlin/com/jmixstudio/generator/` remain pure string producers.

## Data Flow

**Plugin and UI Startup:**

1. IntelliJ reads `plugin/src/main/resources/META-INF/plugin.xml` and instantiates `JmixStudioToolWindowFactory`.
2. `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt` creates a `JBCefBrowser` and a `JcefBridge`.
3. `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt` loads the `jmixstudio.dev.url` system property, bundled `/webui/index.html`, local `webui/dist/index.html`, or `about:blank` in that order.
4. `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` injects `window.javaBridge.send` after the main JCEF frame finishes loading.
5. `webui/src/bridge/index.ts` marks itself ready, drains queued actions, and forwards responses to registered listeners.
6. `webui/src/App.tsx` requests `getProjectConfig` and stores the result in `webui/src/store/index.ts`.

**Single-Artifact Generation:**

1. A designer under `webui/src/components/` validates local/shared form state and calls a helper in `webui/src/bridge/index.ts`.
2. `webui/src/bridge/index.ts` serializes an action/payload through the JCEF-injected `window.javaBridge.send`.
3. `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` parses the request, deserializes the payload into a model from `plugin/src/main/kotlin/com/jmixstudio/model/`, and resolves project configuration.
4. `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` invokes a generator under `plugin/src/main/kotlin/com/jmixstudio/generator/`.
5. `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` writes or appends target-project files inside an IntelliJ write command and schedules a recursive VFS refresh.
6. `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` calls `window.onBridgeResponse(action, result)`; `webui/src/bridge/index.ts` resolves the matching request; the originating designer updates `webui/src/store/index.ts` toasts/results.

**Full CRUD Generation:**

1. `webui/src/components/EntityDesigner/EntityDesigner.tsx` edits the shared `EntityModel` in `webui/src/store/index.ts`.
2. `webui/src/components/CrudWizard/CrudWizard.tsx` reads the same entity, combines it with local `CrudOptions`, and sends `generateCrud` through `webui/src/bridge/index.ts`.
3. `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` deserializes the nested entity/options payload and calls `CodeGenerationService.generateCrud`.
4. `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt` creates entity, migration, list/detail view, menu, role, messages, optional repository, and optional fetch-plan outputs.
5. `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` writes each selected `GeneratedFile` and returns every written relative path.

**Development-Mode Flow:**

1. `webui/vite.config.ts` serves the UI on port 5173.
2. `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt` uses the `jmixstudio.dev.url` system property when supplied.
3. If no injected Java bridge is available, `webui/src/bridge/index.ts` logs requests and emits simulated successful generation responses after 300 ms.
4. Simulated development responses in `webui/src/bridge/index.ts` do not execute Kotlin models, generators, project detection, or file writes.

**State Management:**
- Global UI state uses one Zustand store in `webui/src/store/index.ts`.
- The active tab, project configuration, shared entity, generation status/result, and toasts live in `webui/src/store/index.ts`.
- View/menu/role/migration drafts live in local component state in their corresponding files under `webui/src/components/`.
- Backend project configuration is cached per IntelliJ project by `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- The code generation backend keeps no generated-domain state between requests in `plugin/src/main/kotlin/com/jmixstudio/generator/`.

## Key Abstractions

**`EntityModel`:**
- Purpose: Canonical backend input for entity, migration-from-entity, repository, and CRUD generation.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `webui/src/types/index.ts`
- Pattern: Rich data class with computed fully qualified class and table names in `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`.

**`ViewModel` and `ComponentModel`:**
- Purpose: Intermediate representation shared by the visual view designer and CRUD scaffolder before XML/controller rendering.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, `webui/src/types/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`
- Pattern: Recursive component tree plus data containers, facets, actions, and controller metadata in `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`.

**`MigrationModel` and `DbChange`:**
- Purpose: Represent Liquibase changelogs and supported database changes before XML rendering.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`
- Pattern: Kotlin sealed change hierarchy on the backend and a smaller discriminated UI union in `webui/src/components/MigrationPanel/MigrationPanel.tsx`.

**`RoleModel`:**
- Purpose: Represent resource and row-level Jmix security roles.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/RoleModel.kt`, `webui/src/types/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`
- Pattern: One top-level role model with policy collections and a scope-based generator branch in `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`.

**`ProjectConfig`:**
- Purpose: Carry detected target-project metadata and centralize default source/resource/changelog paths.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`
- Pattern: Immutable configuration data with computed path helpers in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`.

**`GenerationResult`:**
- Purpose: Return a uniform success flag, written-file list, and error list across bridge operations.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `webui/src/types/index.ts`
- Pattern: Backend data class mirrored by a TypeScript interface and serialized by Gson in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

**`CrudOutput` and `GeneratedFile`:**
- Purpose: Keep multi-file generation pure until the application service chooses what to write.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`
- Pattern: Value objects containing relative path, rendered content, and description in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.

**`Bridge`:**
- Purpose: Hide global browser callbacks and expose promise-based feature methods to React components.
- Examples: `webui/src/bridge/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Pattern: Singleton client adapter paired with one Kotlin dispatcher per tool-window browser.

**`JavaClassBuilder` and `XmlBuilder`:**
- Purpose: Standardize source formatting and nested document construction across generators.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`
- Pattern: Mutable fluent builders used only during synchronous generator calls.

## Entry Points

**Plugin Metadata Entry:**
- Location: `plugin/src/main/resources/META-INF/plugin.xml`
- Triggers: IntelliJ plugin loading.
- Responsibilities: Register tool window, services, compatibility, and IDE actions.

**Tool Window Entry:**
- Location: `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`
- Triggers: IntelliJ creates the `Jmix Studio` tool-window content.
- Responsibilities: Check JCEF support, create browser/bridge, load UI, and dispose the bridge with the content.

**IDE Action Entry:**
- Location: `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`
- Triggers: Tools menu or New menu actions registered in `plugin/src/main/resources/META-INF/plugin.xml`.
- Responsibilities: Gate actions to detected Jmix projects and show the tool window.

**Web Entry:**
- Location: `webui/index.html`, `webui/src/main.tsx`
- Triggers: JCEF or Vite loads the web application.
- Responsibilities: Provide the root DOM node, import global styling, and mount `App`.

**Application Entry:**
- Location: `webui/src/App.tsx`
- Triggers: React root render from `webui/src/main.tsx`.
- Responsibilities: Fetch project configuration and route tab state to designer components.

**Backend Command Entry:**
- Location: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Triggers: `window.javaBridge.send` calls emitted by `webui/src/bridge/index.ts`.
- Responsibilities: Parse, dispatch, generate, and return JSON-compatible results.

**Build Entry:**
- Location: `plugin/build.gradle.kts`, `webui/package.json`, `webui/vite.config.ts`
- Triggers: Gradle plugin tasks or npm scripts.
- Responsibilities: Build the React bundle, copy `webui/dist` into plugin resources, compile Kotlin, and package the IntelliJ plugin.

## Error Handling

**Strategy:** Validate essential UI fields before sending, convert backend exceptions into `GenerationResult` or bridge-level JSON errors, display user-facing failures as toasts, and log backend exceptions through IntelliJ logging.

**Patterns:**
- Designer-level validation and toast feedback live in each file under `webui/src/components/`.
- Bridge promise callers use `try/catch/finally` to reset shared `isGenerating` state from `webui/src/store/index.ts`.
- Unknown actions return an error JSON object from `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Request parsing/dispatch exceptions are logged and returned through the `error` response action in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Per-generation exceptions are caught, logged, and converted to `GenerationResult(false, errors=...)` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Missing Jmix project configuration produces an explicit error object from handlers in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Unsupported JCEF renders an in-tool-window error label from `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
- There is no rollback or transactional multi-file write in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`; files already written remain if a later write throws.

## Cross-Cutting Concerns

**Logging:** IntelliJ `Logger` records bridge actions/errors in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` and generated paths/errors in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`; Vite-only bridge simulation uses `console.log` in `webui/src/bridge/index.ts`.

**Validation:** Required-field checks are local to files under `webui/src/components/`; Kotlin model constructors and generators under `plugin/src/main/kotlin/com/jmixstudio/model/` and `plugin/src/main/kotlin/com/jmixstudio/generator/` do not expose a shared validation layer.

**Authentication:** Not applicable to the plugin itself; `plugin/src/main/resources/META-INF/plugin.xml` declares local IntelliJ platform dependencies, and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` accepts commands only through the embedded JCEF query.

**Serialization:** Gson in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` maps TypeScript payloads from `webui/src/types/index.ts` to Kotlin models under `plugin/src/main/kotlin/com/jmixstudio/model/`.

**Threading and IDE Safety:** File writes run inside `WriteCommandAction` and VFS refresh is scheduled with `ApplicationManager.invokeLater` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.

**Build Coupling:** `plugin/build.gradle.kts` makes resource processing depend on `copyWebUi`, which copies the prebuilt `webui/dist` output; it does not invoke the npm build declared in `webui/package.json`.

**Implemented Boundaries:**
- Entity, CRUD, view, role, migration, and BPM backend commands are dispatched in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Entity, CRUD, view, role, and migration commands have visible UI callers under `webui/src/components/`.
- Standalone menu editing is visible in `webui/src/components/MenuDesigner/MenuDesigner.tsx` but its `generateMenu` action is absent from `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Entity discovery is explicitly stubbed to `{"entities":[]}` in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- BPM generation is callable through `webui/src/bridge/index.ts` and implemented in Kotlin, but `webui/src/App.tsx` has no BPM tab or component.
- Event-listener generation exists in `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt` but has no service, bridge, or UI call site.
- IDE “New Entity/View/CRUD” actions show the same tool window without selecting a designer in `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`.
- `README.md` describes the intended feature set and architecture; use the executable paths above as the source of truth for reachable behavior.

---

*Architecture analysis: 2026-07-27*

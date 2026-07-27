# Codebase Structure

**Analysis Date:** 2026-07-27

## Directory Layout

```text
jmix-studio-clone/
├── .gitignore                         # Ignores Gradle, Node, IDE, and OS artifacts
├── .planning/
│   └── codebase/                      # Generated GSD codebase maps
├── README.md                          # Intended features, build steps, and architecture narrative
├── plugin/                            # IntelliJ plugin and Kotlin generation backend
│   ├── .gradle/                       # Generated local Gradle state
│   ├── build.gradle.kts               # Plugin build and web-bundle copy task
│   ├── gradle.properties              # Plugin/platform/version build properties
│   ├── settings.gradle.kts            # Gradle root project name
│   ├── gradle/wrapper/
│   │   └── gradle-wrapper.properties  # Gradle 8.7 wrapper configuration
│   └── src/main/
│       ├── kotlin/com/jmixstudio/
│       │   ├── actions/
│       │   │   └── Actions.kt         # IntelliJ action handlers
│       │   ├── bridge/
│       │   │   └── JcefBridge.kt      # JCEF JSON command dispatcher
│       │   ├── generator/             # Source builders and artifact generators
│       │   ├── model/                 # Backend generation-domain models
│       │   ├── parser/                # Empty placeholder directory; no parser implementation
│       │   ├── services/              # Project discovery and file-write services
│       │   └── toolwindow/
│       │       └── JmixStudioToolWindowFactory.kt
│       └── resources/
│           ├── META-INF/plugin.xml    # IntelliJ extension/action registration
│           └── icons/jmix.svg         # Tool-window icon
└── webui/                             # React/TypeScript visual designer
    ├── dist/                          # Generated Vite production bundle
    ├── node_modules/                  # Installed Node dependencies
    ├── index.html                     # Vite HTML entry
    ├── package.json                   # Frontend scripts and dependencies
    ├── package-lock.json              # Locked npm dependency graph
    ├── postcss.config.js              # PostCSS/Tailwind processing
    ├── tailwind.config.js             # Theme and source scanning
    ├── tsconfig.json                  # Strict TypeScript configuration
    ├── vite.config.ts                 # Vite build/server configuration
    └── src/
        ├── App.tsx                    # Application shell and tab navigation
        ├── main.tsx                   # React mount entry
        ├── index.css                  # Tailwind imports and global styling
        ├── vite-env.d.ts              # Vite environment types
        ├── bridge/index.ts            # Browser-side JCEF adapter
        ├── store/index.ts             # Shared Zustand store
        ├── types/index.ts             # Frontend generation contracts
        └── components/
            ├── CrudWizard/CrudWizard.tsx
            ├── EntityDesigner/EntityDesigner.tsx
            ├── MenuDesigner/MenuDesigner.tsx
            ├── MigrationPanel/MigrationPanel.tsx
            ├── RoleDesigner/RoleDesigner.tsx
            ├── ViewDesigner/ViewDesigner.tsx
            └── shared/Toast.tsx
```

## Directory Purposes

**`.planning/codebase/`:**
- Purpose: Store generated architecture, stack, quality, testing, integration, and concern maps.
- Contains: Markdown analysis artifacts such as `.planning/codebase/ARCHITECTURE.md` and `.planning/codebase/STRUCTURE.md`.
- Key files: `.planning/codebase/ARCHITECTURE.md`, `.planning/codebase/STRUCTURE.md`
- Add mapping documents here; do not place runtime source under `.planning/codebase/`.

**`plugin/`:**
- Purpose: Build and package the IntelliJ plugin and its Kotlin backend.
- Contains: Gradle configuration, wrapper metadata, source, resources, and generated Gradle state.
- Key files: `plugin/build.gradle.kts`, `plugin/gradle.properties`, `plugin/settings.gradle.kts`
- Treat `plugin/` as an independent Gradle project; its root is defined by `plugin/settings.gradle.kts`.

**`plugin/src/main/kotlin/com/jmixstudio/actions/`:**
- Purpose: Implement IntelliJ action-system entry points.
- Contains: Menu and Tools action classes that show the Jmix Studio tool window.
- Key files: `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`
- Register every new action class in `plugin/src/main/resources/META-INF/plugin.xml`.

**`plugin/src/main/kotlin/com/jmixstudio/bridge/`:**
- Purpose: Own all messages crossing between React/JCEF and Kotlin.
- Contains: JavaScript injection, JSON parsing, action dispatch, model deserialization, response execution, and bridge disposal.
- Key files: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Add new backend commands to the dispatch table and a focused handler in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

**`plugin/src/main/kotlin/com/jmixstudio/generator/`:**
- Purpose: Transform generation-domain models into Java/XML/BPMN strings without directly writing files.
- Contains: Two reusable builders, specialized generators, and the full CRUD orchestrator.
- Key files: `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`
- Keep new renderers in `plugin/src/main/kotlin/com/jmixstudio/generator/` and return content to `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.

**`plugin/src/main/kotlin/com/jmixstudio/model/`:**
- Purpose: Define Kotlin-side generation inputs and target-project configuration.
- Contains: Entity, view, role, migration, and project models.
- Key files: `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/RoleModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`
- Mirror bridge-visible model changes in `webui/src/types/index.ts`.

**`plugin/src/main/kotlin/com/jmixstudio/parser/`:**
- Purpose: Not implemented; the directory is empty.
- Contains: No source files.
- Key files: Not detected in `plugin/src/main/kotlin/com/jmixstudio/parser/`.
- Do not assume parser behavior exists; entity discovery remains a stub in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

**`plugin/src/main/kotlin/com/jmixstudio/services/`:**
- Purpose: Integrate pure generation logic with IntelliJ project state and the target filesystem.
- Contains: Jmix project detection/configuration and generated-file writes/VFS refresh.
- Key files: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`
- Put IDE-aware orchestration here; keep render-only logic in `plugin/src/main/kotlin/com/jmixstudio/generator/`.

**`plugin/src/main/kotlin/com/jmixstudio/toolwindow/`:**
- Purpose: Host the React UI inside IntelliJ.
- Contains: Tool-window creation, JCEF capability fallback, bridge lifecycle, and UI URL resolution.
- Key files: `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`
- Keep browser hosting concerns here and command handling in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

**`plugin/src/main/resources/`:**
- Purpose: Supply IntelliJ plugin metadata and packaged visual assets.
- Contains: `META-INF/plugin.xml` and `icons/jmix.svg`; the Gradle build also copies the generated web bundle to build resources.
- Key files: `plugin/src/main/resources/META-INF/plugin.xml`, `plugin/src/main/resources/icons/jmix.svg`, `plugin/build.gradle.kts`
- Declare extensions/actions in `plugin/src/main/resources/META-INF/plugin.xml`; add static IDE assets under `plugin/src/main/resources/`.

**`webui/`:**
- Purpose: Build and develop the embedded visual designer.
- Contains: npm manifest/lockfile, Vite/TypeScript/Tailwind/PostCSS configuration, generated dependencies/output, and React source.
- Key files: `webui/package.json`, `webui/package-lock.json`, `webui/vite.config.ts`, `webui/tsconfig.json`
- Treat `webui/dist/` and `webui/node_modules/` as generated directories governed by `.gitignore`.

**`webui/src/components/`:**
- Purpose: Organize user-facing designer features by capability.
- Contains: One directory per designer and one shared-component directory.
- Key files: `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/components/CrudWizard/CrudWizard.tsx`, `webui/src/components/MenuDesigner/MenuDesigner.tsx`, `webui/src/components/RoleDesigner/RoleDesigner.tsx`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`
- Add each new top-level designer as `webui/src/components/<Feature>/<Feature>.tsx`, then wire it into `webui/src/App.tsx`.

**`webui/src/bridge/`:**
- Purpose: Isolate components from JCEF global functions and expose promise-based commands.
- Contains: The singleton `Bridge`, readiness queue, response listeners, development simulation, and typed request helpers.
- Key files: `webui/src/bridge/index.ts`
- Add a named frontend helper in `webui/src/bridge/index.ts` for every stable backend action in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

**`webui/src/store/`:**
- Purpose: Hold state shared across tabs and global presentation state.
- Contains: Active tab, project configuration, shared entity draft, generation status/result, and toasts.
- Key files: `webui/src/store/index.ts`
- Put cross-feature state in `webui/src/store/index.ts`; keep feature-only draft state local to the component under `webui/src/components/`.

**`webui/src/types/`:**
- Purpose: Define frontend payloads and responses that cross the bridge or are shared between features.
- Contains: Entity, view, role, migration, project configuration, CRUD options, and generation result interfaces.
- Key files: `webui/src/types/index.ts`
- Keep bridge payload names and enum serialized values aligned with models under `plugin/src/main/kotlin/com/jmixstudio/model/`.

## Key File Locations

**Entry Points:**
- `plugin/src/main/resources/META-INF/plugin.xml`: IntelliJ extension and action registration.
- `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`: Plugin UI runtime entry.
- `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`: User-invoked IDE entries.
- `webui/index.html`: Browser document entry.
- `webui/src/main.tsx`: React render entry.
- `webui/src/App.tsx`: Designer shell and feature routing.

**Configuration:**
- `plugin/build.gradle.kts`: Kotlin/IntelliJ build, compatibility patching, and web bundle copy.
- `plugin/gradle.properties`: Plugin identity, IntelliJ platform, Java/Kotlin, and Gradle settings.
- `plugin/settings.gradle.kts`: Gradle project identity.
- `plugin/gradle/wrapper/gradle-wrapper.properties`: Gradle wrapper distribution.
- `webui/package.json`: Frontend scripts and package graph root.
- `webui/package-lock.json`: Exact frontend dependency resolution.
- `webui/vite.config.ts`: Development server and production bundle layout.
- `webui/tsconfig.json`: TypeScript compiler policy.
- `webui/tailwind.config.js`: Theme tokens and Tailwind content roots.
- `webui/postcss.config.js`: Tailwind/autoprefixer pipeline.
- `.gitignore`: Generated-directory exclusions.

**Core Logic:**
- `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`: Backend command boundary.
- `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`: Target-project detection.
- `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`: File-generation workflows and writes.
- `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`: Multi-file CRUD composition.
- `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`: Java rendering primitive.
- `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`: XML rendering primitive.
- `webui/src/bridge/index.ts`: Frontend command boundary.
- `webui/src/store/index.ts`: Shared frontend state.
- `webui/src/types/index.ts`: Cross-language payload contracts.

**Testing:**
- `plugin/src/test/`: Not present; no Kotlin test source set exists in the current tree.
- `webui/src/**/*.test.*`: Not present; no frontend test files exist in the current tree.
- `webui/package.json`: No test script or test dependency is configured.
- `plugin/build.gradle.kts`: No explicit test framework dependency is configured.

**Documentation:**
- `README.md`: Feature claims, architecture narrative, and manual build/install instructions.
- `.planning/codebase/ARCHITECTURE.md`: Implementation-grounded architecture map.
- `.planning/codebase/STRUCTURE.md`: Implementation-grounded placement guide.

## Naming Conventions

**Files:**
- Kotlin domain/service/generator files use PascalCase matching the primary type: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Related IntelliJ action classes share the plural aggregate file `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`.
- React top-level components use PascalCase file and directory names: `webui/src/components/EntityDesigner/EntityDesigner.tsx`.
- Shared frontend modules use lowercase `index.ts`: `webui/src/bridge/index.ts`, `webui/src/store/index.ts`, `webui/src/types/index.ts`.
- Build configuration uses tool-standard names: `plugin/build.gradle.kts`, `webui/vite.config.ts`, `webui/tsconfig.json`.

**Directories:**
- Kotlin packages use lowercase functional names under `plugin/src/main/kotlin/com/jmixstudio/`: `generator`, `model`, `services`, `bridge`, `actions`, `toolwindow`.
- React feature directories use PascalCase under `webui/src/components/`: `ViewDesigner`, `CrudWizard`, `MigrationPanel`.
- Shared React components use lowercase `webui/src/components/shared/`.
- Generated tool directories preserve tool-standard names: `plugin/.gradle/`, `webui/node_modules/`, `webui/dist/`.

## Where to Add New Code

**New Visual Designer Feature:**
- Primary code: `webui/src/components/<Feature>/<Feature>.tsx`
- Navigation: Add the tab definition and conditional render to `webui/src/App.tsx`.
- Shared payloads: Add bridge-facing types to `webui/src/types/index.ts`.
- Frontend command: Add a named request helper to `webui/src/bridge/index.ts`.
- Backend command: Add dispatch and handler logic to `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Tests: Create co-located `webui/src/components/<Feature>/<Feature>.test.tsx` only after adding a test runner/configuration to `webui/package.json`.

**New Backend Generation Artifact:**
- Model: Add or extend a model in `plugin/src/main/kotlin/com/jmixstudio/model/`.
- Renderer: Add `plugin/src/main/kotlin/com/jmixstudio/generator/<Artifact>Generator.kt`.
- Write workflow: Add a focused method to `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Bridge exposure: Add a handler/action in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Frontend contract: Mirror the payload in `webui/src/types/index.ts`.
- Tests: Add `plugin/src/test/kotlin/com/jmixstudio/generator/<Artifact>GeneratorTest.kt` after configuring a test dependency in `plugin/build.gradle.kts`.

**New CRUD Output:**
- Output metadata and composition: Extend `CrudOutput`/`GeneratedFile` production in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.
- Conditional write selection: Update `generateCrud` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- User option: Extend `CrudOptions` in both `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt` and `webui/src/types/index.ts`, then expose it in `webui/src/components/CrudWizard/CrudWizard.tsx`.

**New IntelliJ Action:**
- Implementation: Add the action class in `plugin/src/main/kotlin/com/jmixstudio/actions/`.
- Registration: Add the matching `<action>` declaration to `plugin/src/main/resources/META-INF/plugin.xml`.
- Project gating: Reuse `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- UI navigation: Add an explicit plugin-to-web navigation message across `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` and `webui/src/bridge/index.ts`; the existing actions in `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt` only show the tool window.

**New Project Detection Rule:**
- Detection logic: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`
- Configuration representation/path helper: `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`
- Frontend mirror: `webui/src/types/index.ts`
- Initial consumption: `webui/src/App.tsx` and `webui/src/store/index.ts`

**New Shared React UI Primitive:**
- Implementation: `webui/src/components/shared/<Component>.tsx`
- Global styling: `webui/src/index.css`
- Theme token: `webui/tailwind.config.js`

**New Source-Rendering Primitive:**
- Java source behavior: `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`
- XML behavior: `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`
- Keep artifact-specific rules in the specialized generator under `plugin/src/main/kotlin/com/jmixstudio/generator/`.

**Utilities:**
- Backend generator-only helper: Place near its owning generator in `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Backend project/IDE helper: Place near its owning service in `plugin/src/main/kotlin/com/jmixstudio/services/`.
- Frontend feature-only helper: Keep it in the owning file under `webui/src/components/`, matching the existing tree helpers in `webui/src/components/ViewDesigner/ViewDesigner.tsx` and `webui/src/components/MenuDesigner/MenuDesigner.tsx`.
- Frontend cross-feature helper: Create a focused lower-case module under `webui/src/` and import it from components; no general utility directory currently exists under `webui/src/`.

## Special Directories

**`plugin/.gradle/`:**
- Purpose: Local Gradle caches and configuration state for the plugin build.
- Generated: Yes, by Gradle runs configured from `plugin/build.gradle.kts`.
- Committed: No; ignored by `.gitignore`.

**`webui/node_modules/`:**
- Purpose: Installed frontend packages resolved from `webui/package-lock.json`.
- Generated: Yes, by npm.
- Committed: No; ignored by `.gitignore`.

**`webui/dist/`:**
- Purpose: Production React bundle consumed by `plugin/build.gradle.kts` and loaded by `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
- Generated: Yes, by the `build` script in `webui/package.json` using `webui/vite.config.ts`.
- Committed: No; ignored by `.gitignore`.

**`plugin/build/`:**
- Purpose: Compiled plugin outputs, copied web resources, distributions, and Gradle intermediates.
- Generated: Yes, by tasks in `plugin/build.gradle.kts`.
- Committed: No; ignored by `.gitignore`.
- Current tree: Not present at analysis time; `plugin/build.gradle.kts` defines its expected production.

**`plugin/src/main/kotlin/com/jmixstudio/parser/`:**
- Purpose: Empty placeholder for parsing/discovery behavior.
- Generated: No.
- Committed: Directory presence depends on filesystem tracking because it contains no file.
- Current implementation: No parser source exists; `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` returns an empty entity list.

**`.planning/codebase/`:**
- Purpose: GSD-generated codebase reference documents.
- Generated: Yes, by codebase mapping.
- Committed: Eligible; `.gitignore` does not exclude `.planning/`.

---

*Structure analysis: 2026-07-27*

<!-- GSD:project-start source:PROJECT.md -->
## Project

**Jmix Visual Development Workbench**

An original, clean-room IntelliJ IDEA plugin that gives Jmix developers a safe visual workbench for understanding, designing, and changing real Jmix applications. It combines an embedded React interface with source-aware IntelliJ services, version-aware Jmix adapters, previewable change plans, and validated generators for entities, views, security, Liquibase, menus, localization, and related project artifacts.

The product is intended for professional teams working on valuable multi-module repositories. It is not a byte-for-byte copy of Jmix Studio, does not use proprietary Studio code or assets, and does not bypass Jmix licensing. It implements compatible developer workflows from public specifications using original code.

**Core Value:** Developers can make substantial Jmix project changes visually without risking silent source corruption: every operation understands the existing project, shows the intended diff, validates the result, applies changes atomically, and can be undone.

### Constraints

- **Platform:** IntelliJ IDEA 2025.3+ is the minimum product family baseline for current Jmix 3 tooling; compatibility must be verified rather than claimed.
- **Jmix versions:** Jmix 2.x, Jmix 3.x, Jmix 1.x, and CUBA-era solutions differ materially. Certified read/write support must be declared per adapter and fixture matrix; legacy migration is isolated from normal editing.
- **Technology:** Kotlin for plugin services, React/TypeScript for the JCEF UI, and Gradle for plugin builds remain the starting architecture unless evidence justifies a change.
- **Security:** JCEF content is untrusted input. Bridge commands must be allowlisted and validated independently of the UI.
- **Data integrity:** No direct string-based overwrite of existing structured project files is acceptable for enterprise release.
- **Legal:** The project must remain an independent compatible workbench and must not use proprietary implementation materials.
- **Quality:** Changes affecting project files require automated safety, parser, generator, integration, and failure-rollback coverage.
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- Kotlin 1.9.25 - IntelliJ plugin, project services, JCEF bridge, models, and code generators under `plugin/src/main/kotlin/com/jmixstudio/`; the compiler plugin version is fixed in `plugin/build.gradle.kts` and repeated in `plugin/gradle.properties`.
- TypeScript 5.5-compatible source / 5.9.3 locked compiler - React UI, state, bridge client, and mirrored models under `webui/src/`; the declared range is in `webui/package.json` and the installed resolution is in `webui/package-lock.json`.
- Java 17 bytecode target - generated Java source targets Jmix/Jakarta/Spring APIs in `plugin/src/main/kotlin/com/jmixstudio/generator/`, while the plugin itself uses the Java plugin and Kotlin JVM toolchain 17 in `plugin/build.gradle.kts`.
- CSS - Tailwind directives and custom global rules in `webui/src/index.css`.
- HTML - Vite application shell in `webui/index.html`.
- JavaScript ESM - Tailwind and PostCSS configuration in `webui/tailwind.config.js` and `webui/postcss.config.js`.
- XML - IntelliJ plugin metadata in `plugin/src/main/resources/META-INF/plugin.xml`; generators emit Jmix Flow UI, Liquibase, fetch-plan, menu, and BPMN XML from `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Gradle Kotlin DSL - plugin build and project naming in `plugin/build.gradle.kts` and `plugin/settings.gradle.kts`.
## Runtime
- IntelliJ IDEA Community Platform 2024.1 (`IC`) is the plugin runtime declared by `platformType` and `platformVersion` in `plugin/gradle.properties`.
- The plugin supports IntelliJ build 241 through 251.* according to `patchPluginXml` in `plugin/build.gradle.kts`; declared platform dependencies are `com.intellij.modules.platform` and `com.intellij.modules.java` in `plugin/src/main/resources/META-INF/plugin.xml`.
- JDK 17 is the compilation toolchain in `plugin/build.gradle.kts`. Use JDK 17 or a Gradle-compatible newer JDK to build; the repository does not pin a local JDK distribution.
- JCEF is supplied by the IntelliJ runtime rather than a standalone repository dependency. `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt` checks `JBCefApp.isSupported()` before creating the embedded browser.
- Node.js is the UI build runtime. The repository has no `.nvmrc`, `.node-version`, or `engines` entry in `webui/package.json`; locked Vite 5.4.21 requires Node `^18.0.0 || >=20.0.0` in `webui/package-lock.json`.
- npm - UI dependencies and scripts are declared in `webui/package.json`.
- Lockfile: present, npm lockfile version 3 at `webui/package-lock.json`; use `npm ci` for a reproducible install.
- Gradle 8.7 - distribution metadata exists at `plugin/gradle/wrapper/gradle-wrapper.properties`.
- Wrapper completeness: incomplete. `plugin/gradlew`, `plugin/gradlew.bat`, and `plugin/gradle/wrapper/gradle-wrapper.jar` are absent, so the README wrapper commands in `README.md` require restoring the wrapper or using a compatible system Gradle.
## Frameworks
- IntelliJ Platform SDK 2024.1 - tool-window, project-service, action, VFS, logging, write-command, and JCEF APIs used throughout `plugin/src/main/kotlin/com/jmixstudio/` and registered in `plugin/src/main/resources/META-INF/plugin.xml`.
- React 18.3.1 - component runtime mounted from `webui/src/main.tsx`; exact versions for `react` and `react-dom` are locked in `webui/package-lock.json`.
- Zustand 4.5.7 - in-memory UI state and notifications in `webui/src/store/index.ts`; the exact resolution is in `webui/package-lock.json`.
- Tailwind CSS 3.4.19 - styling system configured by `webui/tailwind.config.js` and consumed by `webui/src/index.css`; exact resolution is in `webui/package-lock.json`.
- Not detected. `webui/package.json` has no test script or test dependency, `plugin/build.gradle.kts` has no test dependency, and no test sources are present under `webui/src/` or `plugin/src/`.
- Gradle 8.7 metadata - plugin build orchestration via `plugin/gradle/wrapper/gradle-wrapper.properties` and `plugin/build.gradle.kts`.
- Kotlin JVM Gradle plugin 1.9.25 - compiles the plugin source in `plugin/src/main/kotlin/`.
- JetBrains IntelliJ Gradle plugin 1.17.4 - downloads/configures IntelliJ 2024.1 and provides `runIde`/`buildPlugin` behavior from `plugin/build.gradle.kts`.
- Vite 5.4.21 - development server and production bundler configured in `webui/vite.config.ts`; exact version is locked in `webui/package-lock.json`.
- TypeScript 5.9.3 - strict type checking configured in `webui/tsconfig.json`; `npm run build` runs `tsc && vite build` from `webui/package.json`.
- PostCSS 8.5.23 and Autoprefixer 10.5.4 - Tailwind CSS processing configured in `webui/postcss.config.js`; exact versions are locked in `webui/package-lock.json`.
- `@vitejs/plugin-react` 4.7.0 - React Fast Refresh/JSX integration enabled in `webui/vite.config.ts`; exact version is locked in `webui/package-lock.json`.
## Key Dependencies
- Gson 2.11.0 - serializes and deserializes bridge payloads and model enums in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` and `plugin/src/main/kotlin/com/jmixstudio/model/`; declared in `plugin/build.gradle.kts`.
- IntelliJ JCEF APIs - embeds the built React UI and implements a bidirectional JavaScript query bridge in `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt` and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- React/React DOM 18.3.1 - renders all designers under `webui/src/components/` from `webui/src/main.tsx`.
- Zustand 4.5.7 - centralizes project configuration, entity-editor state, generation status, and toast state in `webui/src/store/index.ts`.
- Lucide React 0.441.0 - icon components used by the view, menu, role, and migration designers under `webui/src/components/`; declared in `webui/package.json`.
- clsx 2.1.1 - class-name composition dependency declared in `webui/package.json`; no source import is currently present under `webui/src/`.
- Java standard filesystem API plus IntelliJ VFS - writes generated artifacts and refreshes the host project in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Custom `JavaClassBuilder` and `XmlBuilder` - no external templating engine is used; source generation is implemented in `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`.
- Jakarta Persistence, Spring Data/Spring, and Jmix APIs are generated-code contracts, not plugin runtime dependencies. Imports are emitted by `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `DataRepositoryGenerator.kt`, `EventListenerGenerator.kt`, `RoleGenerator.kt`, and `ViewControllerGenerator.kt`.
## Configuration
- No `.env` files, environment-variable reads, or secret configuration are present in the repository. Runtime configuration is derived from the open IntelliJ project by `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- Development UI location is the JVM system property `jmixstudio.dev.url`, read in `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`; the documented local value is `http://localhost:5173` in `README.md`.
- Target-project defaults are `src/main/java`, `src/main/resources`, Jmix 2.4.0, and PostgreSQL in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`. `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` overrides package, Jmix version, and database type when its build-file heuristics match.
- Plugin identity, IntelliJ version, JVM memory, and configuration-cache settings: `plugin/gradle.properties`.
- Gradle plugins, repositories, Gson, JVM toolchain, compatibility range, resource copying, and package tasks: `plugin/build.gradle.kts`.
- IntelliJ extension/action registrations: `plugin/src/main/resources/META-INF/plugin.xml`.
- UI dependency graph and commands: `webui/package.json` and `webui/package-lock.json`.
- TypeScript: `webui/tsconfig.json`.
- Vite output, relative asset base, and fixed development port 5173: `webui/vite.config.ts`.
- Tailwind theme/content scanning: `webui/tailwind.config.js`.
- CSS processing: `webui/postcss.config.js`.
## Platform Requirements
- Install Node satisfying locked Vite's engine (`^18 || >=20`) and run `npm ci` in `webui/`, based on `webui/package-lock.json`.
- Build the UI with `npm run build` from `webui/package.json`; output is `webui/dist/` as configured in `webui/vite.config.ts`.
- Provide Gradle compatible with the Kotlin 1.9.25 and IntelliJ plugin 1.17.4 build in `plugin/build.gradle.kts`, or restore the missing wrapper artifacts referenced by `plugin/gradle/wrapper/gradle-wrapper.properties`.
- Build `webui/dist/` before plugin resources are processed. `copyWebUi` in `plugin/build.gradle.kts` copies that existing directory but does not invoke npm.
- Use an IntelliJ distribution with JCEF support to run the tool window; unsupported runtimes show an error label from `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
- Deployment target is an IntelliJ plugin ZIP generated by the IntelliJ Gradle plugin from `plugin/build.gradle.kts`, not a separately hosted web application.
- The production UI is loaded from `/webui/index.html` bundled in plugin resources by `copyWebUi` in `plugin/build.gradle.kts`; `base: './'` in `webui/vite.config.ts` keeps bundled asset URLs relative.
- Plugin version is 1.0.0 in `plugin/gradle.properties` and `plugin/src/main/resources/META-INF/plugin.xml`; UI version is 1.0.0 in `webui/package.json`.
- No Marketplace publishing, signing, deployment, or CI configuration is present alongside `plugin/build.gradle.kts` and `webui/package.json`.
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Naming Patterns
- Use PascalCase for Kotlin files that contain a primary class or generator object, as in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, and `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`.
- Keep Kotlin files under a directory matching their package declaration; `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt` declares `com.jmixstudio.generator`, and `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` declares `com.jmixstudio.services`.
- Use PascalCase for React component files and their containing feature directories, as in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/MigrationPanel/MigrationPanel.tsx`.
- Use `index.ts` for subsystem entry modules that expose a singleton or a shared set of types, as in `webui/src/bridge/index.ts`, `webui/src/store/index.ts`, and `webui/src/types/index.ts`.
- A related set of small IntelliJ actions may share one PascalCase file; `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt` contains `NewEntityAction`, `NewViewAction`, `NewCrudAction`, and `OpenDesignerAction`.
- Use lower camel case for Kotlin and TypeScript functions, including `generateEntity` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `detectBasePackage` in `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`, and `defaultEntity` in `webui/src/store/index.ts`.
- Give each stateless Kotlin generator a public `generate(...)` entry point and private verb-led helpers such as `generateJpaEntity(...)` and `generateAttributeField(...)` in `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`.
- Prefix UI event functions with `handle`, as in `handleGenerate` in `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, and `webui/src/components/CrudWizard/CrudWizard.tsx`.
- Prefix state mutation helpers with verbs such as `add`, `update`, `remove`, `reset`, `toggle`, or `set`; examples are `addAttribute`, `updateAttribute`, and `removeAttribute` in `webui/src/store/index.ts`.
- Name React component functions in PascalCase, as in `App` in `webui/src/App.tsx` and `Toast` in `webui/src/components/shared/Toast.tsx`.
- Use lower camel case for local values and properties in both languages, as in `writtenFiles` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` and `selectedAttr` in `webui/src/components/EntityDesigner/EntityDesigner.tsx`.
- Prefer Kotlin `val` and immutable local references; use `var` only for evolving builder or traversal state, as in `xmlDeclaration` in `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt` and `cachedConfig` in `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- Use upper snake case for TypeScript module constants that represent fixed vocabularies or protocol constants, as in `ATTRIBUTE_TYPES` in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `DND_MIME` in `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
- Descriptive camel-case constants are also used for local configuration objects and CSS strings, as in `defaultOptions` in `webui/src/components/CrudWizard/CrudWizard.tsx` and `btnPrimary` in `webui/src/components/ViewDesigner/ViewDesigner.tsx`; preserve the style of the surrounding module.
- Use PascalCase for Kotlin classes, data classes, enum classes, and sealed classes, as in `ProjectConfig` in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`, `CrudOutput` in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, and `DbChange` in `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`.
- Use upper snake case for Kotlin enum values, as in `POSTGRES` and `HSQLDB` in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt` and `DATA_GRID` in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.
- Use PascalCase for TypeScript interfaces and type aliases, as in `EntityModel`, `ComponentModel`, and `CrudOptions` in `webui/src/types/index.ts`.
- Use lower-camel serialized string literals in TypeScript unions because they mirror the bridge payload, as in `'mappedSuperclass'`, `'jmixGenerated'`, and `'manyToOne'` in `webui/src/types/index.ts`.
- Keep bridge-facing Kotlin model names aligned with TypeScript interface names; `EntityModel` and `ViewModel` appear in both `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, and `webui/src/types/index.ts`.
## Code Style
- Kotlin uses four-space indentation, opening braces on the declaration line, multiline arguments with one argument per line, and trailing commas inconsistently; representative files are `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.
- TypeScript and TSX use two-space indentation, single-quoted strings, semicolon-free statements, trailing commas in multiline arrays/objects, and parentheses around most arrow-function parameters; representative files are `webui/src/App.tsx`, `webui/src/store/index.ts`, and `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
- Keep JSX attributes on separate lines for multiline elements and use Tailwind utility classes directly in `className`; this is the dominant pattern in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/RoleDesigner/RoleDesigner.tsx`.
- Extract repeated long Tailwind class strings to module constants when a component reuses them, as demonstrated by `btnPrimary`, `btnGhost`, `btnIcon`, and `inputSm` in `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
- No formatter configuration is present: there is no `.editorconfig`, Prettier, Biome, ktlint, or Spotless file alongside `webui/package.json` or `plugin/build.gradle.kts`; match the local file style manually.
- No ESLint script or dependency is defined in `webui/package.json`, and no ESLint configuration is present under `webui/`; the only frontend static gate is the TypeScript compiler invoked by `npm run build` in `webui/package.json`.
- TypeScript strict mode and casing checks are enabled in `webui/tsconfig.json`, while unused locals and unused parameters are explicitly allowed and library checks are skipped.
- No detekt, ktlint, Spotless, Checkstyle, PMD, or SpotBugs plugin is configured in `plugin/build.gradle.kts`; Kotlin style is not machine-enforced.
- Avoid adding unused imports or dead declarations even though current tooling permits them; `webui/src/App.tsx` already destructures an unused `addToast`, and `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` imports an unused `VirtualFile`.
## Import Organization
- No TypeScript path alias is configured in `webui/tsconfig.json`; use relative imports such as `../../types` and `../../bridge` as shown in `webui/src/components/CrudWizard/CrudWizard.tsx`.
- Kotlin uses package imports rooted at `com.jmixstudio`; broad wildcard imports exist at subsystem boundaries in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.
- There are no frontend barrel exports beyond the direct entry modules `webui/src/types/index.ts`, `webui/src/store/index.ts`, and `webui/src/bridge/index.ts`.
## Error Handling
- Catch operational exceptions at the project-service boundary, log the exception, and convert it into `GenerationResult(success = false, errors = ...)`; all generation operations follow this pattern in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Represent expected “not a Jmix project” failures with an early return rather than an exception in the request handlers in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Catch malformed bridge input at the outer request boundary, log it, and return an error response in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Validate required UI input with guard clauses before setting loading state, as in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and the multiple changelog checks in `webui/src/components/MigrationPanel/MigrationPanel.tsx`.
- Wrap awaited generation calls in `try`/`catch`/`finally`, surface the domain result through `addToast`, and always clear `isGenerating` in `finally`; this pattern is used in `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, and `webui/src/components/CrudWizard/CrudWizard.tsx`.
- Treat bridge errors as resolved payloads as well as possible caught failures: `request(...)` resolves for either the matching action or the literal `error` action in `webui/src/bridge/index.ts`, while components inspect `GenerationResult.success` in `webui/src/components/CrudWizard/CrudWizard.tsx`.
- Error typing is inconsistent: `catch (e: any)` is used in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/CrudWizard/CrudWizard.tsx`, while parameterless `catch` is used in `webui/src/components/ViewDesigner/ViewDesigner.tsx`; match the nearest component until a shared typed-error convention exists.
## Logging
- Create one private logger per integration-facing class with `Logger.getInstance(Class::class.java)`, as in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Use `log.info` for generated-file paths and received bridge action names in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Use `log.error(message, exception)` when an operation fails so the IDE log retains the stack trace, as in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Limit browser-console logging to the Vite development fallback guarded by `import.meta.env.DEV` in `webui/src/bridge/index.ts`; production components communicate through toasts in `webui/src/store/index.ts`.
## Comments
- Use KDoc on public generator, bridge, service, and builder classes to state responsibility and supported behavior, as in `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, and `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- Use section-divider comments to make large generators and large UI components navigable, as in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt` and `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
- Use concise inline comments for non-obvious protocol or domain decisions, such as the bridge development simulation in `webui/src/bridge/index.ts` and trait composition behavior in `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`.
- Use JSX comments to label major visual regions in long render trees, as in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/RoleDesigner/RoleDesigner.tsx`.
- Generator-emitted TODO comments are intentional output placeholders, not implementation TODOs; examples are assembled in `plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt`.
- No JSDoc or TSDoc convention is established in `webui/src/`; types and names in `webui/src/types/index.ts` carry the documentation burden.
- KDoc is class-level rather than per-method in the generator layer; `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` documents the builder while fluent methods remain self-describing.
## Function Design
## Module Design
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## Pattern Overview
- The IDE host is the outer runtime: `plugin/src/main/resources/META-INF/plugin.xml` registers the tool window, project services, and IDE actions.
- The visual designer is a client-side React application: `webui/src/main.tsx` mounts `webui/src/App.tsx`, which conditionally renders the six designer components under `webui/src/components/`.
- The runtime boundary is action-oriented rather than REST-oriented: `webui/src/bridge/index.ts` sends `{ action, payload }` messages and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` dispatches those actions.
- Kotlin model objects are the backend generation contract: `plugin/src/main/kotlin/com/jmixstudio/model/` is deserialized from TypeScript-shaped payloads declared in `webui/src/types/index.ts`.
- Generators are mostly stateless transformations from model objects to source strings: `plugin/src/main/kotlin/com/jmixstudio/generator/` contains singleton generator objects and the reusable `JavaClassBuilder` and `XmlBuilder`.
- Project mutation is centralized in one IntelliJ project service: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` writes generated files and refreshes the IDE virtual file system.
- Documentation claims are not runtime evidence: `README.md` describes intended breadth, while the implemented command set and reachable UI are defined by `webui/src/App.tsx`, `webui/src/bridge/index.ts`, and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
## Runtime Topology
```text
```
## Layers
- Purpose: Declare plugin identity, compatibility, tool-window factory, project services, and IDE actions.
- Location: `plugin/src/main/resources/META-INF/plugin.xml`
- Contains: One `toolWindow` extension, two `projectService` extensions, and four action registrations.
- Depends on: IntelliJ Platform extension points configured by `plugin/build.gradle.kts`.
- Used by: IntelliJ startup and action-system discovery before any Kotlin entry point runs.
- Purpose: Create the embedded browser, resolve the web UI URL, attach the bridge, and expose the designer as a tool window.
- Location: `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`
- Contains: JCEF support detection, `JBCefBrowser` creation, development/production/fallback URL resolution, content disposal, and unconditional tool-window availability.
- Depends on: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, JCEF APIs, and the bundled `webui/dist` artifact copied by `plugin/build.gradle.kts`.
- Used by: The `Jmix Studio` tool-window extension in `plugin/src/main/resources/META-INF/plugin.xml`.
- Purpose: Expose the designer through the Tools menu and New menu for recognized Jmix projects.
- Location: `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`
- Contains: `OpenDesignerAction`, `NewEntityAction`, `NewViewAction`, and `NewCrudAction`.
- Depends on: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` for action visibility and IntelliJ `ToolWindowManager` for activation.
- Used by: Action declarations in `plugin/src/main/resources/META-INF/plugin.xml`.
- Implemented boundary: The three “New” actions only show the tool window; `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt` contains no call that selects the matching tab in `webui/src/store/index.ts`.
- Purpose: Mount the designer application, request project configuration, own tab navigation, and render global notifications.
- Location: `webui/src/main.tsx`, `webui/src/App.tsx`, `webui/src/index.css`
- Contains: React root creation, six tab definitions, conditional designer mounting, sidebar layout, and the shared `Toast`.
- Depends on: `webui/src/store/index.ts`, `webui/src/bridge/index.ts`, and all component entry files under `webui/src/components/`.
- Used by: `webui/index.html`, built by `webui/vite.config.ts` into `webui/dist/`.
- Purpose: Gather generation inputs and submit typed models to the backend.
- Location: `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/components/CrudWizard/CrudWizard.tsx`, `webui/src/components/MenuDesigner/MenuDesigner.tsx`, `webui/src/components/RoleDesigner/RoleDesigner.tsx`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`
- Contains: Entity form and preview, drag-and-drop view component tree, CRUD wizard, menu tree editor, role policy editor, and a seven-change-type migration editor.
- Depends on: Shared Zustand state from `webui/src/store/index.ts`, request helpers from `webui/src/bridge/index.ts`, and payload contracts from `webui/src/types/index.ts`.
- Used by: Conditional rendering in `webui/src/App.tsx`.
- Implemented boundary: `webui/src/components/MenuDesigner/MenuDesigner.tsx` submits `generateMenu`, but `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` has no `generateMenu` dispatch branch.
- Purpose: Share the active tab, detected project configuration, the entity under design, generation status/results, and toast notifications.
- Location: `webui/src/store/index.ts`
- Contains: A single Zustand store with immutable update functions and default entity/attribute factories.
- Depends on: Model interfaces from `webui/src/types/index.ts`.
- Used by: `webui/src/App.tsx` and all files under `webui/src/components/`.
- State boundary: Entity and CRUD state is shared through `webui/src/store/index.ts`; view, menu, role, and migration editor data is local React state in their respective files under `webui/src/components/`.
- Purpose: Queue commands until JCEF injects the bridge, route responses to promises, and simulate generation responses during Vite development.
- Location: `webui/src/bridge/index.ts`
- Contains: Global `window.javaBridge`, `window.onBridgeReady`, and `window.onBridgeResponse` contracts; pending command queue; listener registry; request helpers for entity, CRUD, view, migration, role, BPM, and project configuration.
- Depends on: JCEF-injected functions created by `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Used by: `webui/src/App.tsx` and the designer components under `webui/src/components/`.
- Protocol boundary: Requests carry an action name but no request ID in `webui/src/bridge/index.ts`; promise resolution is matched by action name.
- Purpose: Inject the JavaScript bridge into JCEF, parse incoming JSON, dispatch backend operations, and execute response callbacks in the browser.
- Location: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Contains: `JBCefJSQuery` lifecycle, Gson deserialization, action routing, service calls, logging, and JavaScript response execution.
- Depends on: Models in `plugin/src/main/kotlin/com/jmixstudio/model/`, generators in `plugin/src/main/kotlin/com/jmixstudio/generator/`, and services in `plugin/src/main/kotlin/com/jmixstudio/services/`.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
- Implemented commands: `generateEntity`, `generateCrud`, `generateView`, `generateMigration`, `generateRole`, `generateBpm`, `getProjectConfig`, `getEntities`, and `ping` are enumerated in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Purpose: Decide whether the open project is a Jmix project and infer generation roots, base package, Jmix version, and database type.
- Location: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`
- Contains: Root Gradle file lookup, marker-string detection, group/package inference, dependency-string database detection, version inference, cached configuration, and output-path helpers.
- Depends on: IntelliJ `Project` and `VirtualFile` APIs.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt` and every backend handler in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Scope boundary: Only root `build.gradle` or `build.gradle.kts` is inspected by `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`; multi-module discovery is not implemented there.
- Purpose: Represent entities, views, migrations, roles, and target-project configuration independent of code rendering.
- Location: `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/RoleModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`
- Contains: Kotlin data classes, sealed migration changes, enums with Gson serialized names, computed names, and path helpers.
- Depends on: Gson annotations from the dependency declared in `plugin/build.gradle.kts`.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, and all generator files under `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Cross-language contract: Equivalent but not identical payload interfaces are maintained manually in `webui/src/types/index.ts`.
- Purpose: Convert one entity definition into a coordinated entity/migration/view/menu/role/messages/repository/fetch-plan output set.
- Location: `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`
- Contains: `CrudOptions`, generated-file metadata, view-model construction, generator composition, and path selection.
- Depends on: Every primary model and most generators under `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` through the `generateCrud` bridge action.
- Write behavior: The orchestrator only returns strings and paths; `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` performs the actual writes.
- Purpose: Render specific Jmix artifacts from backend models.
- Location: `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MenuGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/DataRepositoryGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt`
- Contains: Kotlin singleton objects with `generate` functions that return Java, XML, or BPMN text.
- Depends on: Models in `plugin/src/main/kotlin/com/jmixstudio/model/` and builders in `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, or other specialized generators.
- Reachability boundary: `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt` is not referenced by the bridge, service, or CRUD orchestrator; `plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt` is backend-reachable but has no tab in `webui/src/App.tsx`.
- Purpose: Provide reusable fluent construction and formatting for generated Java and XML.
- Location: `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`
- Contains: Package/import/class/member rendering in `JavaClassBuilder` and namespace/attribute/element/text rendering in `XmlBuilder`.
- Depends on: Kotlin/JDK standard library only within those two files.
- Used by: Specialized generators under `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Purpose: Choose output paths, invoke generators, mutate the open project, package success/error results, and refresh IntelliJ VFS state.
- Location: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`
- Contains: Per-artifact workflows, `GenerationResult`, `writeFile`, `appendFile`, message rendering, and VFS refresh.
- Depends on: Models in `plugin/src/main/kotlin/com/jmixstudio/model/`, generators in `plugin/src/main/kotlin/com/jmixstudio/generator/`, Java `File`, and IntelliJ write-command/VFS APIs.
- Used by: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Mutation boundary: This is the only implemented layer that writes to the user’s Jmix project; generator files under `plugin/src/main/kotlin/com/jmixstudio/generator/` remain pure string producers.
## Data Flow
- Global UI state uses one Zustand store in `webui/src/store/index.ts`.
- The active tab, project configuration, shared entity, generation status/result, and toasts live in `webui/src/store/index.ts`.
- View/menu/role/migration drafts live in local component state in their corresponding files under `webui/src/components/`.
- Backend project configuration is cached per IntelliJ project by `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- The code generation backend keeps no generated-domain state between requests in `plugin/src/main/kotlin/com/jmixstudio/generator/`.
## Key Abstractions
- Purpose: Canonical backend input for entity, migration-from-entity, repository, and CRUD generation.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `webui/src/types/index.ts`
- Pattern: Rich data class with computed fully qualified class and table names in `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`.
- Purpose: Intermediate representation shared by the visual view designer and CRUD scaffolder before XML/controller rendering.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, `webui/src/types/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`
- Pattern: Recursive component tree plus data containers, facets, actions, and controller metadata in `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`.
- Purpose: Represent Liquibase changelogs and supported database changes before XML rendering.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`
- Pattern: Kotlin sealed change hierarchy on the backend and a smaller discriminated UI union in `webui/src/components/MigrationPanel/MigrationPanel.tsx`.
- Purpose: Represent resource and row-level Jmix security roles.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/RoleModel.kt`, `webui/src/types/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`
- Pattern: One top-level role model with policy collections and a scope-based generator branch in `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`.
- Purpose: Carry detected target-project metadata and centralize default source/resource/changelog paths.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`
- Pattern: Immutable configuration data with computed path helpers in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`.
- Purpose: Return a uniform success flag, written-file list, and error list across bridge operations.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `webui/src/types/index.ts`
- Pattern: Backend data class mirrored by a TypeScript interface and serialized by Gson in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Purpose: Keep multi-file generation pure until the application service chooses what to write.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`
- Pattern: Value objects containing relative path, rendered content, and description in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.
- Purpose: Hide global browser callbacks and expose promise-based feature methods to React components.
- Examples: `webui/src/bridge/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Pattern: Singleton client adapter paired with one Kotlin dispatcher per tool-window browser.
- Purpose: Standardize source formatting and nested document construction across generators.
- Examples: `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`
- Pattern: Mutable fluent builders used only during synchronous generator calls.
## Entry Points
- Location: `plugin/src/main/resources/META-INF/plugin.xml`
- Triggers: IntelliJ plugin loading.
- Responsibilities: Register tool window, services, compatibility, and IDE actions.
- Location: `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`
- Triggers: IntelliJ creates the `Jmix Studio` tool-window content.
- Responsibilities: Check JCEF support, create browser/bridge, load UI, and dispose the bridge with the content.
- Location: `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`
- Triggers: Tools menu or New menu actions registered in `plugin/src/main/resources/META-INF/plugin.xml`.
- Responsibilities: Gate actions to detected Jmix projects and show the tool window.
- Location: `webui/index.html`, `webui/src/main.tsx`
- Triggers: JCEF or Vite loads the web application.
- Responsibilities: Provide the root DOM node, import global styling, and mount `App`.
- Location: `webui/src/App.tsx`
- Triggers: React root render from `webui/src/main.tsx`.
- Responsibilities: Fetch project configuration and route tab state to designer components.
- Location: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Triggers: `window.javaBridge.send` calls emitted by `webui/src/bridge/index.ts`.
- Responsibilities: Parse, dispatch, generate, and return JSON-compatible results.
- Location: `plugin/build.gradle.kts`, `webui/package.json`, `webui/vite.config.ts`
- Triggers: Gradle plugin tasks or npm scripts.
- Responsibilities: Build the React bundle, copy `webui/dist` into plugin resources, compile Kotlin, and package the IntelliJ plugin.
## Error Handling
- Designer-level validation and toast feedback live in each file under `webui/src/components/`.
- Bridge promise callers use `try/catch/finally` to reset shared `isGenerating` state from `webui/src/store/index.ts`.
- Unknown actions return an error JSON object from `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Request parsing/dispatch exceptions are logged and returned through the `error` response action in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Per-generation exceptions are caught, logged, and converted to `GenerationResult(false, errors=...)` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Missing Jmix project configuration produces an explicit error object from handlers in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Unsupported JCEF renders an in-tool-window error label from `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.
- There is no rollback or transactional multi-file write in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`; files already written remain if a later write throws.
## Cross-Cutting Concerns
- Entity, CRUD, view, role, migration, and BPM backend commands are dispatched in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Entity, CRUD, view, role, and migration commands have visible UI callers under `webui/src/components/`.
- Standalone menu editing is visible in `webui/src/components/MenuDesigner/MenuDesigner.tsx` but its `generateMenu` action is absent from `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Entity discovery is explicitly stubbed to `{"entities":[]}` in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- BPM generation is callable through `webui/src/bridge/index.ts` and implemented in Kotlin, but `webui/src/App.tsx` has no BPM tab or component.
- Event-listener generation exists in `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt` but has no service, bridge, or UI call site.
- IDE “New Entity/View/CRUD” actions show the same tool window without selecting a designer in `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`.
- `README.md` describes the intended feature set and architecture; use the executable paths above as the source of truth for reachable behavior.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, or `.github/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->

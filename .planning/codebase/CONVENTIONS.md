# Coding Conventions

**Analysis Date:** 2026-07-27

## Naming Patterns

**Files:**
- Use PascalCase for Kotlin files that contain a primary class or generator object, as in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, and `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`.
- Keep Kotlin files under a directory matching their package declaration; `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt` declares `com.jmixstudio.generator`, and `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` declares `com.jmixstudio.services`.
- Use PascalCase for React component files and their containing feature directories, as in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/MigrationPanel/MigrationPanel.tsx`.
- Use `index.ts` for subsystem entry modules that expose a singleton or a shared set of types, as in `webui/src/bridge/index.ts`, `webui/src/store/index.ts`, and `webui/src/types/index.ts`.
- A related set of small IntelliJ actions may share one PascalCase file; `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt` contains `NewEntityAction`, `NewViewAction`, `NewCrudAction`, and `OpenDesignerAction`.

**Functions:**
- Use lower camel case for Kotlin and TypeScript functions, including `generateEntity` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `detectBasePackage` in `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`, and `defaultEntity` in `webui/src/store/index.ts`.
- Give each stateless Kotlin generator a public `generate(...)` entry point and private verb-led helpers such as `generateJpaEntity(...)` and `generateAttributeField(...)` in `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`.
- Prefix UI event functions with `handle`, as in `handleGenerate` in `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, and `webui/src/components/CrudWizard/CrudWizard.tsx`.
- Prefix state mutation helpers with verbs such as `add`, `update`, `remove`, `reset`, `toggle`, or `set`; examples are `addAttribute`, `updateAttribute`, and `removeAttribute` in `webui/src/store/index.ts`.
- Name React component functions in PascalCase, as in `App` in `webui/src/App.tsx` and `Toast` in `webui/src/components/shared/Toast.tsx`.

**Variables:**
- Use lower camel case for local values and properties in both languages, as in `writtenFiles` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` and `selectedAttr` in `webui/src/components/EntityDesigner/EntityDesigner.tsx`.
- Prefer Kotlin `val` and immutable local references; use `var` only for evolving builder or traversal state, as in `xmlDeclaration` in `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt` and `cachedConfig` in `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- Use upper snake case for TypeScript module constants that represent fixed vocabularies or protocol constants, as in `ATTRIBUTE_TYPES` in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `DND_MIME` in `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
- Descriptive camel-case constants are also used for local configuration objects and CSS strings, as in `defaultOptions` in `webui/src/components/CrudWizard/CrudWizard.tsx` and `btnPrimary` in `webui/src/components/ViewDesigner/ViewDesigner.tsx`; preserve the style of the surrounding module.

**Types:**
- Use PascalCase for Kotlin classes, data classes, enum classes, and sealed classes, as in `ProjectConfig` in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`, `CrudOutput` in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, and `DbChange` in `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`.
- Use upper snake case for Kotlin enum values, as in `POSTGRES` and `HSQLDB` in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt` and `DATA_GRID` in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.
- Use PascalCase for TypeScript interfaces and type aliases, as in `EntityModel`, `ComponentModel`, and `CrudOptions` in `webui/src/types/index.ts`.
- Use lower-camel serialized string literals in TypeScript unions because they mirror the bridge payload, as in `'mappedSuperclass'`, `'jmixGenerated'`, and `'manyToOne'` in `webui/src/types/index.ts`.
- Keep bridge-facing Kotlin model names aligned with TypeScript interface names; `EntityModel` and `ViewModel` appear in both `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, and `webui/src/types/index.ts`.

## Code Style

**Formatting:**
- Kotlin uses four-space indentation, opening braces on the declaration line, multiline arguments with one argument per line, and trailing commas inconsistently; representative files are `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.
- TypeScript and TSX use two-space indentation, single-quoted strings, semicolon-free statements, trailing commas in multiline arrays/objects, and parentheses around most arrow-function parameters; representative files are `webui/src/App.tsx`, `webui/src/store/index.ts`, and `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
- Keep JSX attributes on separate lines for multiline elements and use Tailwind utility classes directly in `className`; this is the dominant pattern in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/RoleDesigner/RoleDesigner.tsx`.
- Extract repeated long Tailwind class strings to module constants when a component reuses them, as demonstrated by `btnPrimary`, `btnGhost`, `btnIcon`, and `inputSm` in `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
- No formatter configuration is present: there is no `.editorconfig`, Prettier, Biome, ktlint, or Spotless file alongside `webui/package.json` or `plugin/build.gradle.kts`; match the local file style manually.

**Linting:**
- No ESLint script or dependency is defined in `webui/package.json`, and no ESLint configuration is present under `webui/`; the only frontend static gate is the TypeScript compiler invoked by `npm run build` in `webui/package.json`.
- TypeScript strict mode and casing checks are enabled in `webui/tsconfig.json`, while unused locals and unused parameters are explicitly allowed and library checks are skipped.
- No detekt, ktlint, Spotless, Checkstyle, PMD, or SpotBugs plugin is configured in `plugin/build.gradle.kts`; Kotlin style is not machine-enforced.
- Avoid adding unused imports or dead declarations even though current tooling permits them; `webui/src/App.tsx` already destructures an unused `addToast`, and `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` imports an unused `VirtualFile`.

## Import Organization

**Order:**
1. Put framework or package imports first in TypeScript, as with React and `lucide-react` in `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
2. Put relative application imports after external imports in TypeScript, as with `../../store`, `../../bridge`, and `../../types` in `webui/src/components/EntityDesigner/EntityDesigner.tsx`.
3. Use `import type` for type-only TypeScript dependencies, as in `webui/src/App.tsx`, `webui/src/store/index.ts`, and `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
4. Put the Kotlin `package` declaration first, then a blank line, then imports; examples are `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` and `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
5. Kotlin import grouping is not consistently alphabetized: IntelliJ/Gson, project, `org.cef`, and JDK imports vary in order in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` and `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`; keep imports readable and minimize wildcard use when editing.

**Path Aliases:**
- No TypeScript path alias is configured in `webui/tsconfig.json`; use relative imports such as `../../types` and `../../bridge` as shown in `webui/src/components/CrudWizard/CrudWizard.tsx`.
- Kotlin uses package imports rooted at `com.jmixstudio`; broad wildcard imports exist at subsystem boundaries in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.
- There are no frontend barrel exports beyond the direct entry modules `webui/src/types/index.ts`, `webui/src/store/index.ts`, and `webui/src/bridge/index.ts`.

## Error Handling

**Patterns:**
- Catch operational exceptions at the project-service boundary, log the exception, and convert it into `GenerationResult(success = false, errors = ...)`; all generation operations follow this pattern in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Represent expected “not a Jmix project” failures with an early return rather than an exception in the request handlers in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Catch malformed bridge input at the outer request boundary, log it, and return an error response in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Validate required UI input with guard clauses before setting loading state, as in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and the multiple changelog checks in `webui/src/components/MigrationPanel/MigrationPanel.tsx`.
- Wrap awaited generation calls in `try`/`catch`/`finally`, surface the domain result through `addToast`, and always clear `isGenerating` in `finally`; this pattern is used in `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, and `webui/src/components/CrudWizard/CrudWizard.tsx`.
- Treat bridge errors as resolved payloads as well as possible caught failures: `request(...)` resolves for either the matching action or the literal `error` action in `webui/src/bridge/index.ts`, while components inspect `GenerationResult.success` in `webui/src/components/CrudWizard/CrudWizard.tsx`.
- Error typing is inconsistent: `catch (e: any)` is used in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/CrudWizard/CrudWizard.tsx`, while parameterless `catch` is used in `webui/src/components/ViewDesigner/ViewDesigner.tsx`; match the nearest component until a shared typed-error convention exists.

## Logging

**Framework:** IntelliJ `Logger` in the Kotlin plugin; `console` only for frontend development simulation.

**Patterns:**
- Create one private logger per integration-facing class with `Logger.getInstance(Class::class.java)`, as in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Use `log.info` for generated-file paths and received bridge action names in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- Use `log.error(message, exception)` when an operation fails so the IDE log retains the stack trace, as in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Limit browser-console logging to the Vite development fallback guarded by `import.meta.env.DEV` in `webui/src/bridge/index.ts`; production components communicate through toasts in `webui/src/store/index.ts`.

## Comments

**When to Comment:**
- Use KDoc on public generator, bridge, service, and builder classes to state responsibility and supported behavior, as in `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, and `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- Use section-divider comments to make large generators and large UI components navigable, as in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt` and `webui/src/components/ViewDesigner/ViewDesigner.tsx`.
- Use concise inline comments for non-obvious protocol or domain decisions, such as the bridge development simulation in `webui/src/bridge/index.ts` and trait composition behavior in `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`.
- Use JSX comments to label major visual regions in long render trees, as in `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/RoleDesigner/RoleDesigner.tsx`.
- Generator-emitted TODO comments are intentional output placeholders, not implementation TODOs; examples are assembled in `plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt`.

**JSDoc/TSDoc:**
- No JSDoc or TSDoc convention is established in `webui/src/`; types and names in `webui/src/types/index.ts` carry the documentation burden.
- KDoc is class-level rather than per-method in the generator layer; `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` documents the builder while fluent methods remain self-describing.

## Function Design

**Size:** Keep reusable transformations small and pure where the codebase already does so, such as `findNode` and `findPath` in `webui/src/components/ViewDesigner/ViewDesigner.tsx` and path helpers in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`. Feature components and generators are currently large—`webui/src/components/ViewDesigner/ViewDesigner.tsx` and `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`—so add new reusable logic outside render bodies or behind private generator helpers.

**Parameters:** Pass typed model objects and configuration objects rather than loose argument lists, as in `CrudOrchestrator.generate(entity, config, options)` in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`. Use default values for optional behavior in Kotlin data classes in `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt` and TypeScript state constructors in `webui/src/store/index.ts`.

**Return Values:** Return generated source as `String` from stateless Kotlin generators such as `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`; return a structured `CrudOutput` when multiple files are produced in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`; return `GenerationResult` from I/O operations in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.

**State Updates:** Keep React/Zustand updates immutable with object spread, array mapping, and filtering, as demonstrated by attribute updates in `webui/src/store/index.ts` and recursive component-tree helpers in `webui/src/components/ViewDesigner/ViewDesigner.tsx`.

**Builder DSLs:** Use Kotlin extension-lambda builders and `apply` for fluent source construction, as in `JavaClassBuilder` in `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` and `XmlBuilder` in `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`.

## Module Design

**Exports:** Kotlin generators are singleton `object` modules with a small public surface, as in `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt`. React components use default exports in `webui/src/App.tsx` and `webui/src/components/shared/Toast.tsx`; shared singletons and types use named exports in `webui/src/bridge/index.ts`, `webui/src/store/index.ts`, and `webui/src/types/index.ts`.

**Barrel Files:** No general component barrel is used under `webui/src/components/`; import the component file directly as done in `webui/src/App.tsx`. Treat `webui/src/types/index.ts`, `webui/src/store/index.ts`, and `webui/src/bridge/index.ts` as subsystem entry points rather than adding an application-wide barrel.

**Service Boundaries:** IntelliJ-dependent behavior belongs in project services annotated with `@Service(Service.Level.PROJECT)`, as in `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` and `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`. Keep string generation in `plugin/src/main/kotlin/com/jmixstudio/generator/` free of IntelliJ dependencies.

**Cross-Layer Contracts:** Keep Kotlin models in `plugin/src/main/kotlin/com/jmixstudio/model/` and their serialized TypeScript counterparts in `webui/src/types/index.ts` synchronized because Gson deserializes bridge payloads in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.

---

*Convention analysis: 2026-07-27*

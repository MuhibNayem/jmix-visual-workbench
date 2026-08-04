# Coding Conventions

**Analysis Date:** 2026-08-04

## Naming Patterns

**Kotlin files:**
- PascalCase file name matching the primary type, in a directory that exactly matches the package declaration: `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt` declares `org.jmixworkbench.services`.
- One file may hold a primary type plus tightly related helpers: `plugin/src/main/kotlin/org/jmixworkbench/actions/Actions.kt` (several small actions), `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt` (const URLs + provider + handler classes), `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt` (startup plan, resolver, lifecycle, fallback panel).
- Subsystem package = concern: `actions/`, `bridge/`, `discovery/`, `editor/`, `generator/`, `ide/`, `model/`, `project/`, `services/`, `toolwindow/` under `plugin/src/main/kotlin/org/jmixworkbench/`.

**Kotlin types:**
- Stateless generators are Kotlin `object` singletons with a public `generate(...)` entry point: `object EntityGenerator` in `plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt`, `object ViewXmlGenerator` in `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt`, `object CrudOrchestrator` in `plugin/src/main/kotlin/org/jmixworkbench/generator/CrudOrchestrator.kt`.
- Services are classes named `*Service`, registered at project level, and expose `companion object { fun getInstance(project: Project) }`: `plugin/src/main/kotlin/org/jmixworkbench/services/JmixProjectService.kt` (companion at line ~343, `getInstance` at ~421), `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt`.
- Models are `data class` + `enum class` with UPPER_SNAKE_CASE enum entries: `EntityType.ENUM` in `plugin/src/main/kotlin/org/jmixworkbench/model/EntityModel.kt`, `EvidenceConfidence.CONFLICTING` in `plugin/src/main/kotlin/org/jmixworkbench/discovery/model/DiscoveryModel.kt`.
- Platform-integration internals that are not extension API surface are marked `internal`: `internal const val PACKAGED_WORKBENCH_ORIGIN` and `internal object WorkbenchUiResourceResolver` in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/`, `internal fun interface WorkspaceMutationProbe` in `plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceMutationProbe.kt`, `internal object RepositoryContract` in `plugin/src/main/kotlin/org/jmixworkbench/generator/DataRepositoryGenerator.kt`. Use `internal` for helpers that only this plugin consumes.
- Result/outcome types are `data class` named `*Result` or `sealed interface` for discriminated unions: `GenerationResult` in `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt`, `SourceMergeResult` in `plugin/src/main/kotlin/org/jmixworkbench/discovery/change/SourcePreservingMerge.kt`, `sealed interface WorkbenchStartupPlan` in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`.
- Test seams use `fun interface`: `WorkspaceMutationProbe`, `JmixProjectResourceLoader` (`plugin/src/main/kotlin/org/jmixworkbench/project/JmixProjectInstaller.kt`), `WorkbenchProjectBridge` (`plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`).
- Module constants are UPPER_SNAKE `internal const val`: `JCEF_UNAVAILABLE_CODE = "JVW-JCEF-UNAVAILABLE"` in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`.

**TypeScript files:**
- React components: PascalCase file inside a PascalCase feature directory, default-exported function component: `webui/src/components/MenuDesigner/MenuDesigner.tsx` → `export default function MenuDesigner()`.
- Subsystem entry modules are `index.ts`: `webui/src/bridge/index.ts`, `webui/src/store/index.ts`, `webui/src/types/index.ts`.
- Feature-local adapters/helpers use camelCase files: `webui/src/components/EntityDesigner/entityModelAdapter.ts`, `webui/src/bridge/devMocks.ts`, `webui/src/components/ViewDesigner/FlowUiComponentCatalog.ts`.
- Module-level pure helper functions are camelCase and declared above the component: `findMenuNode`, `updateMenuNode`, `flattenForGeneration` in `webui/src/components/MenuDesigner/MenuDesigner.tsx`.
- Module constants are UPPER_SNAKE where they are fixed vocabularies (e.g. `STARTER_MENU` in `webui/src/components/MenuDesigner/MenuDesigner.tsx`); otherwise follow surrounding file style.

**Cross-language contract:**
- Bridge-facing Kotlin model names align with TypeScript interfaces: `EntityModel`, `ViewModel` exist in both `plugin/src/main/kotlin/org/jmixworkbench/model/` and `webui/src/types/index.ts`.
- TypeScript uses camelCase string-literal unions mirroring Gson-serialized Kotlin enums: `'mappedSuperclass'`, `'jmixGenerated'`, `'manyToOne'` in `webui/src/types/index.ts`.

## Code Style

**Formatting:**
- No formatter is configured anywhere: no `.editorconfig`, Prettier, Biome, ktlint, detekt, or Spotless exists next to `webui/package.json` or `plugin/build.gradle.kts`. Match the surrounding file style manually.
- Kotlin: four-space indentation, opening brace on the declaration line, multiline calls with one argument per line and frequent trailing commas (`plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt`, `plugin/build.gradle.kts`).
- TypeScript/TSX: two-space indentation, single quotes, no semicolons, parentheses around arrow parameters, one JSX attribute per line for multiline elements (`webui/src/store/index.ts`, `webui/src/components/MenuDesigner/MenuDesigner.tsx`).
- Tailwind utility classes are written directly in `className`; repeated long class strings may be extracted to module constants.

**Linting / static gates:**
- No ESLint script or config exists under `webui/`; the only frontend gate is the TypeScript compiler: `npm run build` runs `tsc && vite build` (`webui/package.json`).
- `webui/tsconfig.json`: `strict: true`, `noFallthroughCasesInSwitch: true`, `forceConsistentCasingInFileNames: true`, but `noUnusedLocals: false` and `noUnusedParameters: false`. Avoid introducing unused imports anyway.
- Kotlin style is not machine-enforced; the enforced gates are compilation plus architecture verification tasks in `plugin/build.gradle.kts` (`verifyNativeIndexArchitecture` line ~602, `verifyMutationArchitecture` line ~703) — see TESTING.md. Do not add broad PSI scopes, global cache keys, or new project-write primitives outside the certified boundaries; the build will reject them.

## Import Organization

**Kotlin:**
- Explicit imports rooted at `org.jmixworkbench` plus IntelliJ Platform APIs; wildcard imports are used at subsystem boundaries for the model package: `import org.jmixworkbench.model.*` in `plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt`.
- Tests import each model type explicitly (`plugin/src/test/kotlin/org/jmixworkbench/generator/EntityAndCrudGeneratorTest.kt` imports ~40 `org.jmixworkbench.model.*` types one by one).

**TypeScript:**
- No path aliases are configured in `webui/tsconfig.json`; use relative imports: `../../store`, `../../bridge`, `../shared/ResponsivePaneSwitcher` (`webui/src/components/MenuDesigner/MenuDesigner.tsx`).
- Observed order: (1) `react`, (2) third-party libraries (`lucide-react`), (3) local modules (`../../store`, `../../bridge`), (4) `import type` statements for types: `import type { DragEvent, ReactNode } from 'react'`, `import type { ... } from '../../types'`.
- Use `import type` for type-only imports; this pattern is consistent across `webui/src/components/`.

## Error Handling

**Kotlin — validation:**
- Validate inputs at the top of `generate(...)` with `require(...)`/`check(...)`/`error(...)`, and prefix every message with a stable `JVW-AREA-CONDITION:` diagnostic code:
 ```kotlin
 // plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt
 require(JAVA_IDENTIFIER.matches(entity.className)) {
 "JVW-ENTITY-CLASS-NAME-INVALID: '${entity.className}' is not a valid Java class name."
 }
 ```
 Over 1200 `JVW-*` codes exist across `plugin/src/main/kotlin/` (e.g. `JVW-MENU-SOURCE-MALFORMED` in `plugin/src/main/kotlin/org/jmixworkbench/generator/MenuSourcePatcher.kt`, `JVW-WORKBENCH-SURFACE-SOURCE-REQUIRED` in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/WorkbenchLaunchContext.kt`). When adding validation, invent a new `JVW-` code in the same UPPER-KEBAB style and keep it stable — tests assert on these codes.
- Stable failure codes are named `internal const val` where reused: `WEB_BUNDLE_MISSING_CODE = "JVW-WEB-BUNDLE-MISSING"` in `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`.

**Kotlin — operation boundaries:**
- Service operations convert exceptions to result objects instead of throwing across the bridge:
 ```kotlin
 // plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt
 return try {
 ...
 } catch (e: Exception) {
 log.error("Entity generation failed", e)
 GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
 }
 ```
- Rethrow IntelliJ cancellation before any general catch: `catch (canceled: ProcessCanceledException) { throw canceled }` (`plugin/src/main/kotlin/org/jmixworkbench/actions/InjectJmixRepositoryAction.kt`), and rethrow when `cause is ProcessCanceledException || cause is ReadAction.CannotReadException` (`plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` lines ~892 and ~4500). Never swallow cancellation.
- Non-critical parsing uses `runCatching { ... }.getOrNull()` or `.getOrElse { ... }`: `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt`, `plugin/src/main/kotlin/org/jmixworkbench/generator/DmnDecisionGenerator.kt`.
- Return rich result data classes (`success` flag + `errors` + outputs) rather than throwing for expected failures: `GenerationResult`, `SourceMergeResult`, `FlowUiParseResult` (`plugin/src/main/kotlin/org/jmixworkbench/discovery/flowui/FlowUiDescriptorParser.kt`), `JmixProjectInstallResult` (`plugin/src/main/kotlin/org/jmixworkbench/project/JmixProjectInstaller.kt`).
- Caught exception variables are descriptive nouns: `failure`, `canceled`, `cancelled`, `cause`, `duplicate` — not `e`/`ex` in newer code (older service catches still use `e: Exception`; match the file you edit).

**TypeScript:**
- Guard-validate before async work, then wrap awaited bridge calls in `try/catch`, surface failures through toasts, and clear busy state in `finally` (pattern in `webui/src/components/ApiDesigner/ApiDesigner.tsx`, `webui/src/components/RoleDesigner/SecurityWorkspace.tsx`).
- Use parameterless `catch {` when the error object is unused, or `catch (cause)` when it is rendered; promise chains use `.catch(() => { ... })` (`webui/src/components/RoleDesigner/ExistingRolePolicyEditor.tsx`).

## Logging

**Framework:** IntelliJ platform logger, deliberately sparse.

**Patterns:**
- One private logger per integration-facing class, held as an instance property:
 ```kotlin
 private val log = Logger.getInstance(JcefBridge::class.java)
 ```
 Exactly three classes do this today: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`, `plugin/src/main/kotlin/org/jmixworkbench/services/ApplicationGraphService.kt`, `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt`. Do not sprinkle loggers across generators or models — generators stay pure.
- `log.error("message", exception)` on operation failure so stack traces reach the IDE log (`plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt`).
- User-visible feedback belongs to the UI toast system (`webui/src/store/index.ts`), not to logs.
- Browser console logging is limited to the Vite development fallback guarded by `import.meta.env.DEV` in `webui/src/bridge/index.ts`.

## Comments

**When to comment:**
- Every public generator, service, bridge, and builder type carries a class-level KDoc stating responsibility and supported behavior:
 ```kotlin
 // plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt
 /**
 * Generates complete JPA/Jmix entity Java source from an EntityModel.
 * Handles: all entity types, ID strategies, inheritance, traits, ...
 */
 ```
- Protocol-heavy files document the wire contract in KDoc: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` (lines ~192–199, includes a "Protocol:" section).
- Build scripts explain *why* (e.g. Node plugin repository rationale in `plugin/build.gradle.kts` lines 24–27 and `plugin/settings.gradle.kts` lines 13–15).
- Generator-emitted TODO/log strings are *output* placeholders for generated target code (e.g. in `plugin/src/main/kotlin/org/jmixworkbench/generator/IntegrationConnectorGenerator.kt`), not implementation debt.
- JSDoc/TSDoc is not an established convention in `webui/src/`; types in `webui/src/types/index.ts` carry the documentation burden. Keep KDoc class-level rather than per-method in Kotlin.

## Function Design

**Generators (Kotlin):**
- Pure transformations: model in, `String` (or value object) out; no file IO, no IDE APIs. IO is centralized in services (`CodeGenerationService`, workspace services). Keep new generators pure; writes belong in `plugin/src/main/kotlin/org/jmixworkbench/services/`.
- Entry point validates first, then dispatches with `when` on an enum:
 ```kotlin
 fun generate(entity: EntityModel): String {
 validate(entity)
 return when (entity.entityType) {
 EntityType.ENUM -> generateEnum(entity)
 EntityType.DTO -> generateDto(entity)
 else -> generateJpaEntity(entity)
 }
 }
 ```
- Private helpers are verb-led (`generateJpaEntity`, `planCreate`, `planModify`, `planDelete` in `plugin/src/main/kotlin/org/jmixworkbench/discovery/change/WorkspaceChangePlanner.kt`).
- Source rendering goes through the shared fluent builders `plugin/src/main/kotlin/org/jmixworkbench/generator/JavaClassBuilder.kt` and `plugin/src/main/kotlin/org/jmixworkbench/generator/XmlBuilder.kt` instead of ad-hoc string assembly.

**Services (Kotlin):**
- Constructor takes `private val project: Project`; access via `companion object getInstance(project)`; registration through `@Service(Service.Level.PROJECT)` (e.g. `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt` line ~29) and/or `<projectService>` entries in `plugin/src/main/resources/META-INF/plugin.xml` (lines ~43–60). New services must be reachable through one of these mechanisms.
- Inject test seams as `fun interface` parameters or internal constructors rather than static lookups (`plugin/src/main/kotlin/org/jmixworkbench/project/JmixProjectInstaller.kt`).

**Components (TypeScript):**
- Function components with hooks; local draft state in `useState`, shared state through the Zustand store (`webui/src/store/index.ts`, `export const useStore = create<AppState>(...)`).
- Keep pure tree/list helpers as module-level functions and unit-testable without React (`webui/src/components/MenuDesigner/MenuDesigner.tsx`).
- All backend calls go through the singleton `bridge` from `webui/src/bridge/index.ts` (`export const bridge = new Bridge()`); requests carry `{ action, payload, requestId }` and resolve via `window.onBridgeResponse`.

## Module Design

**Exports:**
- Kotlin: default public visibility for cross-package APIs; `internal` for implementation details; `private companion object` for constants internal to a class (`plugin/src/main/kotlin/org/jmixworkbench/generator/JavaClassBuilder.kt`).
- TypeScript: one default-exported component per feature file; named exports for shared singletons (`bridge`, `useStore`) and all payload types in `webui/src/types/index.ts`.
- No barrel files beyond the three subsystem entry modules (`webui/src/bridge/index.ts`, `webui/src/store/index.ts`, `webui/src/types/index.ts`).

**Mirrored contracts:**
- `plugin/src/main/kotlin/org/jmixworkbench/model/` (Gson-deserialized) and `webui/src/types/index.ts` are maintained manually in sync; changing one side of a bridge payload requires changing both plus the dispatcher in `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`.

---

*Convention analysis: 2026-08-04*

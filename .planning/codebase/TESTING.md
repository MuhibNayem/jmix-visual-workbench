# Testing Patterns

**Analysis Date:** 2026-07-27

## Test Framework

**Runner:**
- Not detected for Kotlin: `plugin/build.gradle.kts` applies the Java and Kotlin plugins but declares no JUnit, Kotest, or IntelliJ test-framework dependency.
- Not detected for React/TypeScript: `webui/package.json` declares no Vitest, Jest, React Testing Library, or Playwright dependency and has no `test` script.
- Gradle contributes a conventional `test` task through the Java plugin in `plugin/build.gradle.kts`, but there are no test sources or assertion dependencies for it to execute.
- Config: no dedicated test configuration exists alongside `plugin/build.gradle.kts`, `webui/vite.config.ts`, or `webui/tsconfig.json`.

**Assertion Library:**
- Not detected in `plugin/build.gradle.kts` or `webui/package.json`.

**Run Commands:**
```bash
cd webui && npm run build
# Current frontend verification: strict TypeScript compilation followed by a Vite production build, from webui/package.json.

cd plugin && gradle test
# A Gradle task exists implicitly, but the checkout has no tests and the available Gradle 9.4.1 cannot apply org.jetbrains.intellij 1.17.4 from plugin/build.gradle.kts.
```

- No all-tests, watch-test, or coverage command is currently available in `webui/package.json`.
- The README commands `cd plugin && ./gradlew buildPlugin` and `cd plugin && ./gradlew runIde` in `README.md` are not runnable from this checkout because `plugin/gradlew`, `plugin/gradlew.bat`, and `plugin/gradle/wrapper/gradle-wrapper.jar` are absent.

## Test File Organization

**Location:**
- No test directory exists under `plugin/src/`; all 22 Kotlin files are production sources under `plugin/src/main/kotlin/`.
- No test directory or co-located test file exists under `webui/src/`; all 13 TypeScript/TSX files are production sources.
- No repository-level E2E directory exists alongside `plugin/`, `webui/`, or `README.md`.

**Naming:**
- No `*Test.kt`, `*Tests.kt`, `*.test.ts`, `*.test.tsx`, `*.spec.ts`, `*.spec.tsx`, or `*IT.kt` naming convention is established anywhere outside ignored dependency/build directories in `jmix-studio-clone/`.

**Structure:**
```text
plugin/src/main/kotlin/     # Production Kotlin only
webui/src/                 # Production TypeScript/TSX only
```

- If Kotlin tests are introduced, the Gradle source-set convention implied by `plugin/build.gradle.kts` is `plugin/src/test/kotlin/`, but that directory is not currently present.
- If frontend tests are introduced, select and configure a runner in `webui/package.json` before establishing either co-located or separate test placement; `webui/vite.config.ts` currently contains build and dev-server configuration only.

## Test Structure

**Suite Organization:**
- Not applicable: no automated test suite exists in `plugin/src/` or `webui/src/`.
- Do not infer a `describe`/`it`, JUnit annotation, setup, or teardown convention from the production code in `plugin/src/main/kotlin/` or `webui/src/`.

**Patterns:**
- Setup pattern: Not detected in `plugin/src/` or `webui/src/`.
- Teardown pattern: Not detected in `plugin/src/` or `webui/src/`.
- Assertion pattern: Not detected in `plugin/src/` or `webui/src/`.
- The only repeatable automated frontend check is the `tsc && vite build` script in `webui/package.json`; it checks type/build validity, not behavior.

## Mocking

**Framework:** Not detected in `plugin/build.gradle.kts` or `webui/package.json`.

**Patterns:**
- No mocking pattern exists in `plugin/src/` or `webui/src/`.
- The development-only bridge simulation in `webui/src/bridge/index.ts` is runtime fallback behavior, not a test mock: it logs the action, waits 300 ms, and resolves a fabricated successful `GenerationResult`.

**What to Mock:**
- No project-wide rule is established in `plugin/src/` or `webui/src/`.
- IntelliJ `Project`, VFS, `WriteCommandAction`, and JCEF dependencies are the natural external boundaries around `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`, and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- The browser bridge is the natural frontend boundary because feature components import the singleton from `webui/src/bridge/index.ts`.

**What NOT to Mock:**
- No project-wide rule is established in `plugin/src/` or `webui/src/`.
- Pure source transformations in `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`, and `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt` have no external dependency to mock.
- Immutable state transformations in `webui/src/store/index.ts` and pure tree helpers in `webui/src/components/ViewDesigner/ViewDesigner.tsx` can be exercised directly once a frontend runner exists.

## Fixtures and Factories

**Test Data:**
- No test fixtures or factories exist under `plugin/src/` or `webui/src/`.
- Production defaults currently act as UI seed data: `defaultEntity()` and `defaultAttribute()` in `webui/src/store/index.ts`, `defaultOptions` in `webui/src/components/CrudWizard/CrudWizard.tsx`, and initial menu nodes in `webui/src/components/MenuDesigner/MenuDesigner.tsx`.
- Kotlin data classes provide default values that make model fixtures straightforward to construct, especially `EntityModel` in `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `ProjectConfig` in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`, and `CrudOptions` in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`.

**Location:**
- Not detected; no fixture directory exists under `plugin/src/` or `webui/src/`.

## Coverage

**Requirements:** None enforced in `plugin/build.gradle.kts`, `webui/package.json`, or any CI configuration under `jmix-studio-clone/`.

**View Coverage:**
```bash
# Not available: no JaCoCo, Kover, Istanbul/c8, or Vitest/Jest coverage configuration exists.
```

- No coverage threshold, report task, badge, or committed coverage artifact is referenced by `README.md`, `plugin/build.gradle.kts`, or `webui/package.json`.
- Current effective automated behavioral coverage is zero because no test files exist under `plugin/src/` or `webui/src/`.

## Test Types

**Unit Tests:**
- Not used: there are no unit tests for the fluent builders in `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`.
- Not used: there are no golden/snapshot tests for generated Java, XML, Liquibase, BPMN, or CRUD output from `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Not used: there are no store or pure-helper tests for `webui/src/store/index.ts` or `webui/src/components/ViewDesigner/ViewDesigner.tsx`.

**Integration Tests:**
- Not used: bridge action routing, Gson model conversion, and error serialization in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` are untested.
- Not used: project detection and build-file parsing in `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` are untested.
- Not used: real file generation, append behavior, IntelliJ write actions, and VFS refresh in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` are untested.
- Not used: React feature components do not have integration tests against the bridge singleton in `webui/src/bridge/index.ts`.

**E2E Tests:**
- Not used: there is no Playwright, Cypress, Selenium, IntelliJ UI test, or Robot Framework dependency in `webui/package.json` or `plugin/build.gradle.kts`.
- No automated workflow launches the JCEF tool window registered in `plugin/src/main/resources/META-INF/plugin.xml`, exercises the React UI in `webui/src/App.tsx`, and verifies generated project files.

## Common Patterns

**Async Testing:**
- No async test pattern exists in `webui/src/` or `plugin/src/`.
- Async production behavior that requires future coverage includes response subscription and unsubscription in `webui/src/bridge/index.ts`, the 300 ms development timer in `webui/src/bridge/index.ts`, the 5-second toast timer in `webui/src/store/index.ts`, and loading cleanup in `webui/src/components/EntityDesigner/EntityDesigner.tsx`.

**Error Testing:**
- No error-test pattern exists in `webui/src/` or `plugin/src/`.
- Error paths that currently have no regression coverage include malformed JSON and unknown actions in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, generator/file failures converted to `GenerationResult` in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, and failed result toasts in `webui/src/components/CrudWizard/CrudWizard.tsx`.

## Testability Map

**Highly Testable Pure Kotlin:**
- `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt` are deterministic builders with string outputs and no IntelliJ dependencies.
- Generator singleton objects under `plugin/src/main/kotlin/com/jmixstudio/generator/` accept model objects and return strings; `EntityGenerator.generate`, `MigrationGenerator.generate`, `RoleGenerator.generate`, and `BpmGenerator.generate` can be covered with table-driven or golden-output tests.
- `CrudOrchestrator.generate` in `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt` composes pure generators into a structured list of relative paths and contents without writing files.
- Path conversion helpers and derived properties in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt` and `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt` are deterministic.

**Frontend Pure Logic:**
- Default-model and immutable state logic lives in `webui/src/store/index.ts`, but the store is created and exported as a singleton at module load.
- Recursive tree transformations in `webui/src/components/ViewDesigner/ViewDesigner.tsx` are pure but module-private, so direct unit testing requires either testing through the component or extracting/exporting the helpers.
- Expected CRUD file calculation in `webui/src/components/CrudWizard/CrudWizard.tsx` is deterministic but kept in the feature component module.

**IntelliJ-Coupled Kotlin:**
- `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt` directly obtains IntelliJ services and performs private `java.io.File` writes inside `WriteCommandAction`, so isolated tests need an IntelliJ test fixture or an extracted file-writing port.
- `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` reads `Project.baseDir` and `VirtualFile` contents directly and caches the result, so tests need synthetic IntelliJ projects/VFS fixtures.
- `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` creates `JBCefJSQuery` and registers handlers in its constructor, coupling routing tests to JCEF unless message parsing/routing is extracted.
- `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt` loads either a development URL or bundled JCEF content and therefore requires an IntelliJ/JCEF integration environment.

**Frontend Coupling:**
- `webui/src/bridge/index.ts` exports an eagerly constructed singleton that mutates global `window` callbacks during module evaluation; tests need DOM globals and careful module isolation.
- Feature components such as `webui/src/components/EntityDesigner/EntityDesigner.tsx` and `webui/src/components/MigrationPanel/MigrationPanel.tsx` import the bridge and Zustand store directly, with no dependency-injection seam.
- Large render-and-state modules—especially `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`, and `webui/src/components/EntityDesigner/EntityDesigner.tsx`—combine validation, model mutation, bridge calls, and presentation, which pushes most behavioral checks toward component integration tests.

## Build and CI Verification

**Frontend Build:**
- `npm run build` from `webui/package.json` completed successfully on 2026-07-27: TypeScript compiled, Vite transformed 1,587 modules, and output was written under ignored `webui/dist/`.
- This build verifies compilation and bundling only; no tests or lint checks run before `vite build` in `webui/package.json`.
- The README instruction to build the UI with `cd webui && npm run build` in `README.md` matches the actual script and succeeds with the installed dependencies recorded by `webui/package-lock.json`.

**Plugin Build:**
- `plugin/gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.7, but the wrapper scripts and wrapper JAR are missing, so the `./gradlew` commands documented in `README.md` cannot start.
- Running the implicit `test` task with the available system Gradle 9.4.1 fails while applying `org.jetbrains.intellij` 1.17.4 from `plugin/build.gradle.kts` because Gradle's `DefaultArtifactPublicationSet` type is unavailable; plugin compilation and the empty test task are therefore not verified.
- `plugin/build.gradle.kts` makes `processResources` depend on `copyWebUi`, which copies existing `webui/dist/` into plugin resources but does not invoke npm; the sequential UI-then-plugin order documented in `README.md` is required by the current build.
- JDK 17 is enforced through `jvmToolchain(17)` in `plugin/build.gradle.kts`, matching the JDK prerequisite in `README.md`.
- Node.js 18 is stated in `README.md` but is not enforced through an `engines` field in `webui/package.json`, an `.nvmrc`, or another version-manager file under `webui/`.

**CI Pipeline:**
- Not detected: no GitHub Actions, GitLab CI, Jenkins, CircleCI, or Azure Pipelines definition exists alongside `README.md`.
- No automated gate currently runs `npm run build`, a Gradle task, behavioral tests, linting, or coverage for changes to `webui/` or `plugin/`.

---

*Testing analysis: 2026-07-27*

# Testing Patterns

**Analysis Date:** 2026-08-04

## Test Framework

**Runners (Kotlin plugin code):**
- `kotlin.test` (`assertEquals`, `assertTrue`, `assertFailsWith`, `assertNull`, `assertSame`, `@Test`) is the assertion vocabulary for all Kotlin suites.
- Shared host-lane suite (`plugin/src/test/kotlin/`): executed on the JUnit Platform via `useJUnitPlatform()` in both `plugin/hosts/idea253/build.gradle.kts` (line ~310) and `plugin/hosts/idea262/build.gradle.kts` (line ~312), with `kotlin("test")`, `junit:junit:4.13.2`, and `org.junit.vintage:junit-vintage-engine:5.10.1` so IntelliJ JUnit3-style fixture tests run.
- IntelliJ Platform test framework: `testFramework(TestFrameworkType.Platform)` and `testFramework(TestFrameworkType.Plugin.Java)` (`plugin/hosts/idea253/build.gradle.kts` lines ~146–147).
- Phase 2 discovery suite (`plugin/src/phase2CoreTest/kotlin/`): JUnit 5 via `kotlin("test-junit5")` + `junit-jupiter-engine:5.10.1` + `junit-platform-launcher:1.10.1`, wired in `plugin/build.gradle.kts` (lines ~106–114, `useJUnitPlatform()` at ~392).
- buildSrc tasks (`plugin/buildSrc/`): Java + JUnit Jupiter 5.10.1 (`plugin/buildSrc/build.gradle.kts`).

**Assertion library:** `kotlin.test` (Kotlin), JUnit Jupiter `Assertions` (buildSrc Java tests).

**Run commands** (from `plugin/`, always with the checked-in wrapper):
```bash
./gradlew test --dependency-verification=strict # phase2CoreTest + verifyWebBundle, then both host test lanes via testShared
./gradlew phase2CoreTest # platform-independent discovery contract tests only
./gradlew phase2FastCheck # fast Phase 2 verification lanes
./gradlew clean phase1Check --dependency-verification=strict # full gate: tests, smoke tests, plugin builds, verifier, zip checks
./gradlew testShared # explicit: :idea253:test :idea262:test
```

## Test File Organization

**Locations and counts:**
- `plugin/src/test/kotlin/org/jmixworkbench/` — 87 shared test classes compiled and executed by **both** host lanes. The host builds attach it directly: `kotlin.srcDir("../../src/test/kotlin")` in `plugin/hosts/idea253/build.gradle.kts` (line ~126) and the equivalent in `plugin/hosts/idea262/build.gradle.kts`. Main sources attach the same way (`kotlin.srcDir("../../src/main/kotlin")`).
- `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/` — 12 platform-independent contract tests for the discovery layer, run by the aggregate `phase2CoreTest` task. The aggregate build's `main` and `test` source sets are intentionally empty (`plugin/build.gradle.kts` lines ~90–98) so a bare `./gradlew test` cannot compile IntelliJ-dependent code without an SDK.
- `plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253DescriptorTest.kt` and `plugin/hosts/idea262/src/test/kotlin/org/jmixworkbench/host/idea262/Idea262DescriptorTest.kt` — lane-local descriptor checks.
- `plugin/buildSrc/src/test/java/org/jmixworkbench/build/` — 2 task tests: `VerifyWebBundleTaskTest.java`, `VerifyPluginZipContentsTaskTest.java`.
- `webui/` — **no tests and no test framework at all** (see Gaps).

**Naming:** `<Subject>Test.kt` for unit/contract tests; `<Subject>IntegrationTest.kt` for tests needing a real project fixture or IDE (`JmixProjectServiceIntegrationTest.kt`, `ApplicationGraphServiceIntegrationTest.kt`, `WorkbenchToolWindowFactoryIntegrationTest.kt`); `*LiveMatrixTest.kt` for externally provisioned runs (`DatabaseReverseEngineeringLiveMatrixTest.kt`).

**Structure:** tests mirror production packages: `plugin/src/test/kotlin/org/jmixworkbench/generator/EntityAndCrudGeneratorTest.kt` tests `plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt`; `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/change/SourcePreservingMergeTest.kt` tests `plugin/src/main/kotlin/org/jmixworkbench/discovery/change/SourcePreservingMerge.kt`.

## Test Structure

**Pure unit test (kotlin.test, backtick names):**
```kotlin
// plugin/src/test/kotlin/org/jmixworkbench/generator/MigrationGeneratorTest.kt
class MigrationGeneratorTest {
 @Test
 fun `constraints rollback and portable attributes are emitted as valid Liquibase XML`() {
 ...
 }
}
```

**IntelliJ fixture test (JUnit3-style `test*` methods):**
```kotlin
// plugin/src/test/kotlin/org/jmixworkbench/ide/JmixNativeDomainAssistanceTest.kt
class JmixNativeDomainAssistanceTest : LightJavaCodeInsightFixtureTestCase() {
 fun testUnresolvedEntityPropertyIsHighlightedAndFixed() {
 myFixture.addClass(""" ... """)
 myFixture.enableInspections(JmixDomainXmlReferenceInspection())
 myFixture.configureByText("menu.xml", """ ... """)
 val problem = myFixture.doHighlighting()
 ...
 }
}
```
Use `LightJavaCodeInsightFixtureTestCase` for Java PSI assistance/inspection tests and `BasePlatformTestCase` for platform-service/workspace tests (e.g. `plugin/src/test/kotlin/org/jmixworkbench/services/JmixProjectPropertiesIntegrationTest.kt`).

**IDE-launch smoke test:** `plugin/src/test/kotlin/org/jmixworkbench/toolwindow/WorkbenchToolWindowFactoryIntegrationTest.kt` is a plain kotlin.test class but is **excluded from the lane `test` task** (`excludeTestsMatching(...)` in `plugin/hosts/idea253/build.gradle.kts` line ~319) and **only included in `hostSmokeTest`** — an `intellijPlatformTesting.testIde` task (line ~323) that boots a real sandboxed IDE per lane.

## Mocking

**Framework:** None (no MockK/Mockito anywhere). Test doubles are hand-written:
- `fun interface` seams in production code exist specifically for this: `WorkspaceMutationProbe`, `JmixProjectResourceLoader`, `JmixProjectInstallProbe` (`plugin/src/main/kotlin/org/jmixworkbench/project/JmixProjectInstaller.kt`), `WorkbenchProjectBridge`/`WorkbenchFileEditorRuntime`.
- JDK dynamic proxies stand in for IDE interfaces where needed: `java.lang.reflect.Proxy` in `plugin/src/test/kotlin/org/jmixworkbench/toolwindow/WorkbenchToolWindowFactoryIntegrationTest.kt`.
- Virtual project fixtures are created through the platform fixture API (`myFixture.addClass`, `myFixture.addFileToProject`, `myFixture.configureByText`) instead of filesystem mocks.

**What to mock:** IDE/runtime interfaces at the edge (browsers, loaders, probes). **What NOT to mock:** generators, parsers, and models — assert on their real string/JSON output; `JVW-*` diagnostic codes are asserted verbatim.

## Fixtures and Factories

- Fixtures are inline Kotlin/Java source strings inside test methods (`myFixture.addClass("""...""")` in `plugin/src/test/kotlin/org/jmixworkbench/ide/JmixNativeDomainAssistanceTest.kt`) and inline XML/JSON payloads for parser tests (`plugin/src/test/kotlin/org/jmixworkbench/services/WorkflowXmlParserTest.kt`, `plugin/src/test/kotlin/org/jmixworkbench/services/MigrationJsonParserTest.kt`).
- There are no test resource directories: `plugin/src/test/resources/` and `plugin/src/phase2CoreTest/resources/` do not exist on disk. New fixture data should be inline unless a test genuinely needs a binary/resource file.
- Generated compatibility fixtures are produced by `generateCompatibilityFixtures` from `plugin/src/compatibilityGenerator/kotlin/org/jmixworkbench/certification/CompatibilityFixtureGenerator.kt` into `plugin/build/compatibility/generated-sources` — build-owned, never checked in.

## Coverage

**Requirements:** None enforced. No JaCoCo, Kover, or Istanbul tooling exists; the word "coverage" in the repo is a domain term (`SchemaMigrationCoverage`, `ApplicationGraphModuleCoverage`), not test coverage.

**Substitutes that ARE enforced:** see "Quality gates" below — architectural verification tasks, compatibility compilation cells, Plugin Verifier, and zip-content verification carry the load that coverage gates usually carry.

## Test Types

**Unit tests:** generator/parser/model contract tests in `plugin/src/test/kotlin/org/jmixworkbench/generator/`, `.../services/` (parser-flavored), and all of `plugin/src/phase2CoreTest/`. Platform-independent phase2 tests run without any IntelliJ SDK — the `phase2Core` source set compiles only `org/jmixworkbench/discovery/**` plus `discovery/static/GradleConfigParser.kt` (`plugin/build.gradle.kts` lines ~37–53).

**Integration tests (platform fixtures):** `BasePlatformTestCase`/`LightJavaCodeInsightFixtureTestCase` suites under `plugin/src/test/kotlin/org/jmixworkbench/ide/`, `.../editor/`, `.../services/`, `.../project/`, `.../actions/` — run on both host lanes.

**IDE smoke tests:** `hostSmokeTest` per lane (real IDE startup, tool-window factory attach).

**Live/opt-in tests:** skipped unless enabled via system properties forwarded by `tasks.withType<Test>` in both host builds (`plugin/hosts/idea253/build.gradle.kts` lines ~152–176): `jvw.live.db.enabled/url/username/password/driver/driverClasspath/hostLane/evidenceFile` (used by `plugin/src/test/kotlin/org/jmixworkbench/services/DatabaseReverseEngineeringLiveMatrixTest.kt`), `jvw.project.template.runtime.*`, `jvw.project.template.java17Home|java21Home|java25Home`. CI never sets these.

**E2E (UI):** Not present — `webui/` has no test runner.

**Runtime certification harnesses (outside the main build):** `certification/database-runtime/` (Docker Compose matrix, `./run-matrix.sh`, machine-readable evidence per cell into `evidence/current`) and `certification/integration-runtime/`, documented in `docs/DATABASE-RUNTIME-CERTIFICATION.md`. These produce certification evidence, not regression tests, and are not invoked by `phase1Check`.

## Quality Gates That DO Exist

**CI (`.github/workflows/ci.yml`):** one job, `phase1` on `ubuntu-24.04` (push to `main` + PRs): validates the checked-in wrapper jar SHA-256 and `distributionSha256Sum`, installs Temurin 21, then runs:
```bash
cd plugin && ./gradlew clean phase1Check --dependency-verification=strict --no-daemon --no-configuration-cache --stacktrace
```
Artifacts uploaded: both lane plugin ZIPs (`jmix-visual-workbench-1.0.0-idea253.zip`, `-idea262.zip`) and all test/Plugin Verifier reports. Actions pinned by commit SHA. Dependabot (`.github/dependabot.yml`) opens review-only PRs for gradle/npm/github-actions ecosystems.

**`phase1Check` chain (`plugin/build.gradle.kts`):** `phase1Check` → `verifyPluginZipContents` → `phase1Idea262Gate` → `phase1Idea253Gate` → `phase1RootGate`. Each gate re-invokes the wrapper with `--dependency-verification=strict --no-daemon --stacktrace`.
- Root gate runs `phase1FastCheck`, which depends on: `certifyGeneratedCodeCompatibility`, `phase2CoreTest`, `verifyWebBundle`, `verifyHostBuildDefinitions`, `verifyHostToolchains`, `verifyNativeIndexArchitecture`, `verifyMutationArchitecture`, `verifyDependencyIntegrity` (lines ~1120–1131).
- Each host gate runs `:clean :compileKotlin :test :hostSmokeTest :buildPlugin :verifyPlugin :verifyNoBundledKotlinRuntime` for that lane (lines ~1110–1117).

**Architecture verification tasks (build-time static checks on source text):**
- `verifyNativeIndexArchitecture` (~line 602): rejects broad index scopes, global PSI cache keys, and extension-wide scans in `plugin/src/main/kotlin/org/jmixworkbench/ide/`.
- `verifyMutationArchitecture` (~line 703): rejects project-write primitives (`WriteCommandAction`, etc.) outside the certified mutation boundary files (`WorkspaceChangeService.kt`, `WorkspaceHistoryService.kt`, `JmixProjectInstaller.kt`, `JmixOrganizationTemplateCatalog.kt`, `JmixTemplateCatalogAuthoring.kt`, `InjectJmixRepositoryAction.kt`, `ProjectSourceText.kt`), requires `internal fun interface WorkspaceMutationProbe` to exist, and forbids the bridge from referencing it.
- `verifyHostBuildDefinitions` (~line 533): asserts immutable host-lane contracts (toolchain versions, `sinceBuild`/`untilBuild`, descriptor `<depends>` entries) by string-checking `plugin/hosts/idea*/build.gradle.kts` and `plugin.xml`.
- `verifyHostToolchains` (~line 510): proves idea253 compiles with Java 21 and idea262 with Java 25 via toolchain metadata.

**Dependency integrity:**
- `plugin/gradle/verification-metadata.xml`: SHA-256 verification with `<verify-metadata>true</verify-metadata>`; CI always passes `--dependency-verification=strict`; `--write-verification-metadata`/`--write-locks` are never used in CI (policy in `docs/BUILDING.md` and `plugin/gradle/dependency-locks/README.md`).
- Strict per-lane dependency locking: `plugin/hosts/idea253/gradle/dependency-locks/gradle.lockfile` and `plugin/hosts/idea262/gradle/dependency-locks/gradle.lockfile` (`LockMode.STRICT`, only `runtimeClasspath` + `testRuntimeClasspath`), guarded by `snapshotLockHashes` → `verifyLockedConfigurations` → `compareLockHashes` → `verifyDependencyIntegrity` (`plugin/build.gradle.kts` lines ~832–881).
- npm drift check: `snapshotNpmLockHash` captures `webui/package-lock.json` before `npmCi`, and the integrity task fails if `npm ci` changed the lockfile (lines ~455–466, ~922–925). Lockfile must stay version 3.

**Artifact verification:** `VerifyPluginZipContentsTask` and `VerifyWebBundleTask` in `plugin/buildSrc/src/main/java/org/jmixworkbench/build/` (both have their own JUnit tests) reject missing/stale web bundles, forbidden Node/cache payloads, and wrong provenance inside the lane ZIPs.

**Compatibility certification:** `certifyGeneratedCodeCompatibility` (~line 253) compiles the generated source corpus against exact cells — Jmix 2.8.2 on JDK 17/21 and 3.0.0 on JDK 21/25 (`targetCompatibilityCells`, lines ~142–147) — and writes evidence to `plugin/build/reports/compatibility/generated-code-certification.json`.

**Plugin Verifier:** `pluginVerifier()` + `:verifyPlugin` run per lane as part of every host gate.

## Common Patterns

**Assertion style:**
```kotlin
// plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/model/DiscoveryModelTest.kt
assertEquals(expected, actual)
assertFailsWith<IllegalArgumentException> { ... }
assertTrue("2.4.0" !in rendered)
```

**Async testing:** Not applicable to the Kotlin suites; the React UI has no tests. Bridge behavior is exercised indirectly through the IDE smoke test.

**Error testing:** assert on `JVW-*` diagnostic codes and on `GenerationResult.success == false` with expected `errors` entries; use `assertFailsWith<...>` for model validation.

## Gaps

- **`webui/` is completely untested.** `webui/package.json` has only `dev`/`build`/`preview` scripts and no test dependency; the sole frontend gate is `tsc && vite build`. Any UI logic change ships unverified beyond type checking.
- **No coverage measurement anywhere** (no JaCoCo/Kover/Istanbul); there is no quantitative evidence of which generator/parser paths are exercised.
- **`plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` (the ~4500-line dispatcher) has no dedicated unit test file** in `plugin/src/test/kotlin/`; its behavior is covered only indirectly via service-level and smoke tests.
- **Live matrix tests never run in CI** (`jvw.live.db.*` and template-runtime properties are unset in `.github/workflows/ci.yml`); database/runtime evidence depends on manual `certification/database-runtime/run-matrix.sh` runs.
- **The `compatibilityGenerator` source set** (`plugin/src/compatibilityGenerator/kotlin/`) has no tests of its own; correctness is inferred from the downstream compatibility compilation cells.
- **No frontend integration test for the JCEF bridge protocol** — request/response matching, timeouts, and the pending queue in `webui/src/bridge/index.ts` rely on the smoke test and manual verification.

---

*Testing analysis: 2026-08-04*

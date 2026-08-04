# Codebase Concerns

**Analysis Date:** 2026-08-04

Scope note: findings are audited against the current `org.jmixworkbench` implementation (dual host lanes idea253/idea262), not against the older `com.jmixstudio` state still embedded in `AGENTS.md`. Each finding states **Confirmed** (verified in code) or **Risk** (plausible, not fully provable from static evidence), with severity and evidence.

## Charter Context (what the project demands)

The charter (root `AGENTS.md`, `CLEAN_ROOM.md`, `SECURITY.md`) demands:
- Data integrity: no silent source corruption; previewable, atomic, undoable changes.
- JCEF security: UI content is untrusted; bridge commands allowlisted and validated independently of the UI.
- Version-aware adapters with certified read/write support declared per Jmix line.
- Automated safety, parser, generator, integration, and failure-rollback coverage.

The current implementation satisfies much of this (see "Implemented Safeguards" at the bottom). The concerns below are the residual gaps.

## Tech Debt

**Dual mutation protocols: legacy direct-apply actions coexist with preview/apply workspace flow** — Severity: Medium — Confirmed
- Issue: Two generations of bridge protocol are live. The new protocol is preview → digest-bound apply through `WorkspaceChangeService` with undo. Legacy `generateX` actions skip the preview UX and apply directly.
- Files: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` (legacy dispatch at lines 753–766: `generateEntity`, `generateCrud`, `generateView`, `generateMigration`, `generateRole`, `generateMenu`, `generateBpm`, `getEntities`, `ping`), `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt` (legacy `generateEntity` line 47 … `generateBpmProcess` line 234, all funneling through `applyGeneratedPlan` line 1098).
- UI callers still on the legacy path:
 - `webui/src/components/ViewDesigner/ViewDesigner.tsx:943` — `await bridge.generateView(view)` (direct apply, no source preview).
 - `webui/src/components/MenuDesigner/MenuDesigner.tsx:509` — raw `bridge.request<GenerationResult>('generateMenu', …)` (direct apply; backend only has a staleness guard `JVW-MENU-SOURCE-STALE` in `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt:1282`).
- Dead client-side legacy methods no component calls anymore: `generateEntity`, `generateCrud`, `generateMigration`, `generateRole`, `generateBpm` in `webui/src/bridge/index.ts` (lines 2834–3138) — still shipped and maintained.
- Impact: charter requires "previewable change plans"; FlowUI view creation and standalone menu edits apply without a reviewable diff. Two code paths double the maintenance surface.
- Fix approach: migrate ViewDesigner and MenuDesigner to the preview/apply protocol (backend already exposes `WorkspaceChangeService.preview/prepareApply` plumbing via `CodeGenerationService.previewGeneratedPlan`/`prepareGeneratedPlan`, lines 1106–1124); delete unused legacy bridge actions and client methods.

**God-file dispatcher and oversized components** — Severity: Medium — Confirmed
- Issue: `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt` is ~4,600 lines (observed content through line 4628) containing ~100 action branches, response-transfer machinery, and per-feature request parsing in one class.
- Other oversized files (observed minimum line counts): `webui/src/bridge/index.ts` (~3,600 lines), `webui/src/components/ViewDesigner/ExistingFlowUiDesigner.tsx` (≥2,350), `webui/src/components/LogicDesigner/LogicDesigner.tsx` (≥1,670), `webui/src/components/WorkflowDesigner/WorkflowDesigner.tsx` (≥1,600), `plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt` (≥1,500).
- Impact: every new feature edits the same dispatcher file; merge churn and review blind spots concentrate in security-critical code.
- Fix approach: split `JcefBridge.kt` into per-domain handler objects sharing one dispatcher; extract bridge client per-feature modules in `webui/src/bridge/`.

**Stale planning/assessment documents describe a codebase that no longer exists** — Severity: Medium — Confirmed
- Issue: `AGENTS.md` embedded STACK/ARCHITECTURE/CONVENTIONS sections reference `plugin/src/main/kotlin/com/jmixstudio/…` paths, IntelliJ 2024.1, a missing Gradle wrapper, six tabs, `getEntities` stubbed to `{"entities":[]}`, and "no generateMenu dispatch branch". All of that is obsolete: current tree is `org/jmixworkbench`, hosts are idea253/idea262 (`plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`), `plugin/gradlew` exists, `generateMenu` is implemented (`plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt:758,822`), and `getEntities` reads the live application graph (`JcefBridge.kt:860–868`).
- `JMIX_STUDIO_ASSESSMENT.md` (dated 2026-07-27) still lists P0 gaps that the current code has closed: "`getEntities` bridge action is a TODO" (line 124) and "Menu Designer … `generateMenu` … not implemented in the bridge" (lines 128–130, 288), plus "targets obsolete Gradle IntelliJ plugin 1.17.4 and IntelliJ 2024.1" (line 105).
- Impact: GSD planning phases that load these documents will plan against a phantom codebase and may "fix" things that are already fixed or miss things that moved.
- Fix approach: regenerate `AGENTS.md` codebase sections and mark `JMIX_STUDIO_ASSESSMENT.md` as a historical baseline with a status column.

**No frontend lint/format/test tooling** — Severity: Medium — Confirmed
- Issue: `webui/package.json` has only `dev`, `build` (`tsc && vite build`), and `preview` scripts. No test runner, no ESLint, no Prettier/Biome; no `.eslintrc*`/`.editorconfig` files exist under `webui/`. The only static gate is the TypeScript compiler.
- Impact: 36 TS/TSX source files — including the ~3,600-line bridge client that frames every backend command — are gated by type checks alone. Style drift and dead code accumulate (legacy methods above are an example).
- Fix approach: add ESLint (typescript-eslint) + a formatter, and a test runner (Vitest fits the Vite 8 toolchain pinned in `webui/package.json`).

## Known Bugs

**Bridge error body built by string interpolation is embedded unescaped into page JavaScript** — Severity: Low — Confirmed
- Symptoms: an unknown action returns `else -> """{"error":"Unknown action: $action"}"""` at `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt:763`. The small-payload response path embeds that string verbatim into executed script: `window.onBridgeResponse($actionJson, $requestIdJson, $resultJson);` (`JcefBridge.kt:4545–4558`). Every other handler builds `resultJson` via `gson.toJson(...)`; this one interpolates the caller-controlled `action` value into JSON/JS without escaping, so a crafted action name can break out of the object literal.
- Trigger: any page controlling the workbench origin can send an arbitrary action string (the page is the caller; in packaged mode the page is the immutable first-party bundle served from classpath at the private origin enforced by `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt:57–60`).
- Workaround/mitigation today: exploitation requires an already-compromised or dev-mode (`http://127.0.0.1:5173`) page, so practical impact is limited; `actionJson`/`requestIdJson` themselves are safely serialized (`JcefBridge.kt:4554–4555`).
- Fix approach: build the unknown-action error with `gson.toJson(mapOf("error" to "Unknown action: $action"))` like all other handlers; add a unit test asserting every `sendResponse` payload is Gson-produced.

**No other reproducible defects were confirmed.** Legacy "broken" behaviors described in `JMIX_STUDIO_ASSESSMENT.md` (empty `getEntities`, missing `generateMenu`) are fixed in current code (see evidence above).

## Security Considerations

**JCEF trust boundary is well-engineered; one defense-in-depth gap** — Severity: Low overall — Confirmed (strengths) + Confirmed (gap)
- Strengths (verified):
 - Packaged UI is served only from classpath resources under a private origin `https://jmix-workbench.invalid` with every request intercepted and no fall-through to DNS/filesystem/network: `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt:57–60, 201–246`; security headers including same-origin CORP at line 184; 32 MiB resource cap at line 24.
 - Development URL requires `-Djmixworkbench.dev.enabled=true` and must be exactly a credential-free loopback HTTP origin on port 5173 or it is rejected: `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt:23, 37–65, 196–199, 236–238`.
 - Bridge dispatch is effectively an allowlist: unknown actions return an error (`JcefBridge.kt:763`); malformed input is caught at the boundary (`JcefBridge.kt:767–772`). A build guard explicitly forbids exposing mutation fault injection through the bridge (`plugin/build.gradle.kts:774`).
 - Path traversal defense: locator paths reject blank segments, `.`, `..`, and backslashes; no absolute path crosses the bridge: `plugin/src/main/kotlin/org/jmixworkbench/services/ProjectFileResolver.kt:82–100`.
 - REST invocation from the workbench is loopback-only, credential-free, redirect-free, timeout-bounded, and header-restricted: `plugin/src/main/kotlin/org/jmixworkbench/services/RestApiWorkspaceService.kt:96–198`.
 - Live database inspection keeps secrets backend-side ("Connection secrets are resolved only inside the backend and never cross the JCEF bridge"), read-only, with connect/network timeouts: `plugin/src/main/kotlin/org/jmixworkbench/services/DatabaseReverseEngineeringService.kt:26–29, 157–158`.
 - Organization template catalogs require Ed25519 signatures verified against a trusted-key policy with sha256 payload inventory and atomic install: `plugin/src/main/kotlin/org/jmixworkbench/project/JmixOrganizationTemplateCatalog.kt:371–424, 873–909, 1312`.
 - Secret editing policy denies literal passwords/placeholder defaults for secret keys: `docs/PROJECT-PROPERTIES-PARITY.md:40, 73–80` and `plugin/src/test/kotlin/org/jmixworkbench/services/JmixProjectPropertiesServiceTest.kt:44–45`.
 - Runtime hot-deploy only activates when the target project itself enables `jmix.core.unsafe-runtime-features-enabled`, `jmix.core.hot-deploy-enabled`, and `jmix.core.trigger-files-enabled`: `plugin/src/main/kotlin/org/jmixworkbench/services/JmixRuntimeService.kt:313–330`; loopback-only runtime probing with containment/symlink checks at lines 260, 561, 653–660.
- Gap: the unescaped unknown-action error body (see Known Bugs) violates the letter of "bridge commands must be validated independently of the UI".

**Certification harness ships dev-only default credentials** — Severity: Low — Confirmed
- Risk: `certification/database-runtime/docker-compose.yml` and `certification/integration-runtime/docker-compose.yml` use env-overridable default passwords (e.g. `POSTGRES_PASSWORD: ${CERT_POSTGRES_PASSWORD:-jmixcert-postgres}` line 9; MSSQL SA default line 60) bound to `127.0.0.1` only (line 11).
- Current mitigation: loopback binding, local-only test databases, override variables provided.
- Recommendations: keep defaults clearly documented as dev-only; never reuse these credentials in shared environments.

## Performance Bottlenecks

**Legacy `generateX` bridge handlers run synchronously on the bridge callback thread** — Severity: Medium — Confirmed
- Problem: the legacy dispatch block (`plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt:753–766`) calls `CodeGenerationService.generateEntity/generateCrud/generateView/generateMigration/generateRole/generateMenu/generateBpmProcess` inline (e.g. `handleGenerateEntity` lines 777–783), with no `ReadAction.nonBlocking`/executor handoff.
- Cause: all newer handlers deliberately schedule work off-thread — `ReadAction.nonBlocking { … }.submit(AppExecutorUtil.getAppExecutorService())` (e.g. lines 947–1005), `submitReadResponse` (lines 905, 1104, 1402, 1759…), `submitBackgroundResponse` (line 1818), plain executor tasks (lines 873, 1839). The legacy path predates this pattern and performs PSI reads plus a `WriteCommandAction` apply (`plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceChangeService.kt:103`) synchronously.
- Impact: UI freezes on the active ViewDesigner/MenuDesigner apply paths for the full duration of generation and file writes.
- Improvement path: route legacy handlers through the same non-blocking submit helpers, or delete them once UI callers migrate to preview/apply.

**Whole-project application graph indexing** — Severity: Low–Medium — Risk (mitigated by design, needs scale proof)
- Problem: `getApplicationGraph` builds/serializes the full project graph; transfer size is explicitly measured in MiB (`JcefBridge.kt:879`) and large payloads move via Base64 chunked transfer (`JcefBridge.kt:4569–4617`).
- Current mitigation: progress reporting (`getApplicationGraphProgress`, `JcefBridge.kt:283–284`), incremental update listeners (`JcefBridge.kt:209–210`), file-based candidate indexes (`plugin/src/main/resources/META-INF/plugin.xml` registers ten `fileBasedIndex` extensions), and index-scale tests (`plugin/src/test/kotlin/org/jmixworkbench/ide/JmixNativeIndexScaleTest.kt`).
- Improvement path: `docs/ENTERPRISE-PARITY-AUDIT.md:127` itself lists outstanding proof work: dumb-mode, cold-index, completion/navigation latency, memory and leak budgets on representative customer repositories. Schedule that validation before GA claims on 3,000+ file repos.

## Fragile Areas

**Source-preserving mutation of existing user files** — Why fragile: modifying handwritten Java/Kotlin/XML by construction is the highest-corruption-risk surface; correctness depends on exact-fingerprint preflights and PSI validation.
- Files: `plugin/src/main/kotlin/org/jmixworkbench/discovery/change/SourcePreservingMerge.kt`, `plugin/src/main/kotlin/org/jmixworkbench/discovery/change/WorkspaceChangePlanner.kt`, `plugin/src/main/kotlin/org/jmixworkbench/generator/MenuSourcePatcher.kt`, `plugin/src/main/kotlin/org/jmixworkbench/generator/RestApiSourcePatcher.kt`, `plugin/src/main/kotlin/org/jmixworkbench/services/FlowUiControllerChangeService.kt`, `plugin/src/main/kotlin/org/jmixworkbench/services/EntityAttributeRefactorService.kt`, `plugin/src/main/kotlin/org/jmixworkbench/actions/InjectJmixRepositoryAction.kt` (exact-rollback proof at lines 335–338, 528–537).
- Safe modification: always go through `WorkspaceChangeService.preview → prepareApply → applyPrepared`; never write documents directly. Staleness guards must stay (e.g. `JVW-MENU-SOURCE-STALE` in `CodeGenerationService.kt:1282`, `JVW-WORKFLOW-SOURCE-STALE` at line 275).
- Test coverage: present and targeted — `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/change/SourcePreservingMergeTest.kt`, `WorkspaceChangePlannerTest.kt`, `plugin/src/test/kotlin/org/jmixworkbench/generator/MenuSourcePatcherTest.kt`, `RestApiSourcePatcherTest.kt`, `plugin/src/test/kotlin/org/jmixworkbench/services/WorkspaceMutationFailureSafetyTest.kt`, `WorkspaceDocumentRoundTripTest.kt`, `WorkspaceHistoryServiceTest.kt`.

**Cross-host JCEF API drift handled by runtime reflection** — Why fragile: `CefResourceHandler` method sets differ between IDEA 2025.3 and 2026.2; the packaged-UI handler is a dynamic proxy implementing whichever methods the host declares.
- Files: `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt:251–270` (proxy), host descriptors `plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml` (no `com.intellij.modules.jcef` dependency, runtime `JBCefApp.isSupported()` check) vs `plugin/hosts/idea262/src/main/resources/META-INF/plugin.xml` (explicit `com.intellij.modules.jcef` dependency, asserted by `plugin/build.gradle.kts:573, 591`).
- Safe modification: never link new JCEF callback types directly in shared code; extend the proxy and both descriptor tests.
- Test coverage: `plugin/src/test/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandlerTest.kt`, `WorkbenchToolWindowFactoryIntegrationTest.kt`, host descriptor tests `plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253DescriptorTest.kt` and `plugin/hosts/idea262/src/test/kotlin/org/jmixworkbench/host/idea262/Idea262DescriptorTest.kt`.

**String-built Java/Kotlin/XML generators** — Why fragile: generators emit source text via fluent builders; runtime correctness of emitted code is not validated inside the IDE at generation time.
- Files: `plugin/src/main/kotlin/org/jmixworkbench/generator/JavaClassBuilder.kt`, `XmlBuilder.kt`, and all `*Generator.kt` siblings.
- Safe modification: keep generators pure string producers; all writes go through `WorkspaceChangeService`.
- Test coverage: strong offline gate — the build compiles a generated corpus against exact Jmix artifacts per cell (`certifyGeneratedCodeCompatibility`, `plugin/build.gradle.kts:253–258`; fixture generator `plugin/src/compatibilityGenerator/kotlin/org/jmixworkbench/certification/CompatibilityFixtureGenerator.kt`), plus per-generator unit tests under `plugin/src/test/kotlin/org/jmixworkbench/generator/`.

## Scaling Limits

**Graph payload transfer**: large application graphs serialize to JSON in-memory and move through Base64 chunking (`plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt:879, 4569–4617`); very large monorepos will pressure IDE heap (`plugin/gradle.properties` sets `-Xmx2g` for the build only; runtime heap is the IDE's). Capacity is project-size-dependent; no hard cap was found — monitoring (MiB logging) exists instead. Scaling path: paginate/artifact-slice graph responses before raising heap requirements.

**Packaged UI resource cap**: 32 MiB per resource (`plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt:24`) — bundle growth beyond this fails closed; not a near-term limit.

**Certification matrix breadth**: only four generated-code cells exist today (see Missing Critical Features); each new Jmix/JDK cell adds a compile lane in `plugin/build.gradle.kts:137–250`.

## Dependencies at Risk

**IntelliJ Platform API drift between the two host lanes** — Risk: Low (mitigated)
- Risk: same sources compile against IDEA Ultimate 2025.3 (Java 21, Kotlin 2.2) and IDEA Ultimate 2026.2 (Java 25, Kotlin 2.4) — `plugin/build.gradle.kts:559–584`. Platform or JCEF API changes can break one lane silently.
- Impact: tool window/bridge failure on one IDE family.
- Migration plan/mitigation: exact-version lanes with dependency lockfiles (`plugin/hosts/idea253/gradle/dependency-locks/gradle.lockfile`, `plugin/hosts/idea262/gradle/dependency-locks/gradle.lockfile`), immutable-lane contract checks (`verifyHostBuildDefinitions`, `plugin/build.gradle.kts:533–595`), Plugin Verifier evidence for both lanes (`docs/COMPATIBILITY.md:11–12`; `pluginVerifier()` in `plugin/hosts/idea253/build.gradle.kts:148`), and per-lane descriptor/smoke tests.

**Frontend toolchain freshness** — Risk: Low
- `webui/package.json` pins Node engine `24.18.0` and Vite `8.1.5` exactly; the Gradle build pins the Node distribution with sha256 (`plugin/build.gradle.kts:940`) and enforces npm lockfile v3 plus lock-hash drift checks (`plugin/build.gradle.kts:920–925`). Dependabot is configured (`.github/dependabot.yml`). Main risk is the lack of frontend tests (see Test Coverage Gaps), not the dependencies themselves.

## Missing Critical Features

**No certified write-support declarations; compatibility registry certifies read-only discovery only** — Severity: Medium–High — Confirmed
- Problem: `plugin/src/main/resources/compatibility/phase2-registry.json` contains only `discovery.*` operations, all in state `CERTIFIED_READ_ONLY` (e.g. lines 5–16). The generated-code matrix certifies compilation of generated artifacts against Jmix 2.8.2 (JDK 17/21) and 3.0.0 (JDK 21/25) only (`docs/COMPATIBILITY.md:58–63`); Jmix 1.x, CUBA, and other 2.x minors are explicitly uncertified (`docs/COMPATIBILITY.md:97–98`).
- No version gate found in generators: `detectJmixVersion` exists (`plugin/src/main/kotlin/org/jmixworkbench/services/JmixProjectService.kt:337`) but no `jmixVersion` branch exists anywhere under `plugin/src/main/kotlin/org/jmixworkbench/generator/`, so generation proceeds best-effort on uncertified targets.
- Blocks: the charter's "certified read/write support must be declared per adapter and fixture matrix" is only half-met (read side declared; write side undeclared and ungated).
- Fix approach: extend the registry with write-operation cells per Jmix line and fail closed (or warn explicitly) when `ProjectConfig.jmixVersion` falls outside certified cells.

**Previewable diff missing for view creation and standalone menu editing** — Severity: Medium — Confirmed
- Problem: charter requires every substantial change to "show the intended diff". Entity, CRUD, migration, role, and most existing-artifact flows have preview/apply UX (e.g. `webui/src/components/MigrationPanel/MigrationPanel.tsx:472–490`, `webui/src/components/RoleDesigner/RoleDesigner.tsx:191–221`, `webui/src/components/CrudWizard/CrudWizard.tsx:54`), but `ViewDesigner.tsx:943` and `MenuDesigner.tsx:509` apply directly.
- Blocks: enterprise release claim of universal preview-before-write.

**No release signing/publishing pipeline** — Severity: Low (pre-GA expected) — Confirmed
- Problem: no `signPlugin`/`publishPlugin`/Marketplace channel configuration in `plugin/hosts/idea253/build.gradle.kts` or `plugin/hosts/idea262/build.gradle.kts`; descriptor self-describes as "Early, non-certified clean-room visual workbench prototype" (`plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml:9`).
- Blocks: signed distribution; release-integrity expectations in `docs/RELEASE-INTEGRITY.md` are not yet wired to tasks.

**Intermediate IDE versions unsupported by design** — Severity: Low — Confirmed
- Problem: lanes pin `sinceBuild/untilBuild` to `253`/`253.*` and `262`/`262.*` (`plugin/build.gradle.kts:567–568, 583–584`); IDE builds between lanes (e.g. 2026.1) have no artifact and are declared unsupported (`docs/COMPATIBILITY.md:14–16`). Users on those builds get nothing.
- Fix approach: document the gap in-product or add a lane when an intermediate IDE reaches adoption.

## Test Coverage Gaps

**Zero automated tests for the entire React/TypeScript UI** — Priority: High — Confirmed
- What's not tested: bridge client protocol (queueing, request-id correlation, dev fallback guards in `webui/src/bridge/index.ts:203–240`), all 15+ designer components under `webui/src/components/`, validation guards before apply.
- Files: `webui/` (no `*.test.*`/`*.spec.*` anywhere; no test script in `webui/package.json`).
- Risk: the UI frames every mutation request; a client-side regression (wrong payload shape, missing digest, swallowed error) can reach backend apply paths. Backend defenses (fingerprints, snapshots, restore) mitigate corruption but not usability regressions.
- Fix: introduce Vitest + Testing Library; start with `webui/src/bridge/index.ts` protocol tests and per-designer apply-guard tests.

**No unit tests for the JCEF bridge dispatcher itself** — Priority: Medium — Confirmed
- What's not tested: action allowlist behavior, unknown-action handling, malformed-JSON boundaries, response serialization safety in `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt`. There is no `plugin/src/test/kotlin/org/jmixworkbench/bridge/` directory.
- Risk: the security-critical dispatch layer changes without a regression net (the line-763 interpolation bug is exactly the class of bug a dispatcher test would catch).
- Fix: extract dispatch + response-building into testable units; add tests for unknown/malformed actions and for "every response body is Gson-serialized".

**Legacy synchronous generation path lacks a threading assertion** — Priority: Medium — Risk
- What's not tested: that `generateView`/`generateMenu` applies do not freeze the EDT; no test asserts off-thread scheduling for bridge handlers (contrast: failure-safety is certified by `plugin/src/test/kotlin/org/jmixworkbench/services/WorkspaceMutationFailureSafetyTest.kt`).
- Fix: migrate handlers to the executor pattern and cover with a test asserting the callback thread returns before apply completes.

**Certification matrices are manual/scripted, not CI-resident** — Priority: Low — Confirmed
- What's not tested automatically: database-runtime and integration-runtime matrices require Docker and credentials (`certification/database-runtime/run-matrix.sh`, `certification/integration-runtime/run-matrix.sh`, compose files); CI (`.github/workflows/ci.yml`) runs only the wrapper-only dual-lane gate `./gradlew clean phase1Check --dependency-verification=strict` on ubuntu-24.04 (lines 40–42).
- Risk: runtime DB/integration certification evidence can lag code changes between manual runs.
- Fix: schedule matrix runs as a gated CI job with ephemeral containers and no default credentials.

## Implemented Safeguards (verified — do not regress)

- Atomic multi-file apply with pre-captured snapshots and failure restore inside one `WriteCommandAction`: `plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceChangeService.kt:103–173` (snapshots 237–244, restore 289–311), revision fingerprint preflights at lines 449–475.
- Undo/redo with post-edit conflict detection and rollback-of-rollback handling: `plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceHistoryService.kt:61–228, 399, 449–483`; exposed via `undoWorkspaceChange` (`JcefBridge.kt:623–628`).
- Failure-safety certification probe kept internal and bridge-invisible: `plugin/src/main/kotlin/org/jmixworkbench/services/WorkspaceMutationProbe.kt:3–13`; enforced by `plugin/src/test/kotlin/org/jmixworkbench/services/WorkspaceMutationFailureSafetyTest.kt` and build guard `plugin/build.gradle.kts:774`.
- Dependency integrity: npm lock hash pinning, Gradle lock hash comparison, wrapper sha256 pin requirement, lockfile-version checks (`plugin/build.gradle.kts:415–417, 832–925`), strict dependency verification in CI.
- 99 Kotlin test files under `plugin/src/test/` plus the `phase2CoreTest` source set (`plugin/src/phase2CoreTest/`) covering discovery, parsers, generators, services, and tool-window boundaries.

---

*Concerns audit: 2026-08-04*

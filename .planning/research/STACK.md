# Technology Stack

**Project:** Jmix Studio Clone — original enterprise IntelliJ visual development workbench  
**Domain:** IntelliJ Platform plugin for safe visual and semantic editing of existing Jmix applications  
**Researched:** 2026-07-27  
**Research mode:** Ecosystem  
**Overall confidence:** HIGH for host-platform and Jmix baselines; MEDIUM for the exact frontend package patch levels and the breadth of legacy-project certification until fixtures exist

## Recommendation in One Sentence

Build a Kotlin IntelliJ plugin around PSI, UAST, Kotlin Analysis API, XML DOM/PSI, Workspace Model, and the imported Gradle model; isolate Jmix-version knowledge behind capability-based adapters; use React and TypeScript inside JCEF only for visual canvases; and ship two host artifacts so the Java 21 IntelliJ 2025.3–2026.1 line and the Java 25 IntelliJ 2026.2 line can each use their native Kotlin and JCEF contracts.

The workbench must not link Jmix, Spring, Vaadin, EclipseLink, or Liquibase runtime libraries into the plugin. It should inspect the target repository's source, descriptors, imported project model, and resolved dependencies. Real Jmix runtimes belong in certification fixtures and integration tests, not in the production plugin classpath.

## Compatibility Is a Product Contract

“Supports Jmix” must mean an explicit set of safe operations on a certified repository shape, not merely that a project can be opened. The released plugin should embed a signed, machine-readable `compatibility-manifest.json` that maps:

```text
host IDE lane
× Jmix framework range
× project build DSL
× source language
× repository topology
× artifact kind
× requested operation
→ certified read/write | read-only diagnostic | unsupported
```

The Kotlin backend is the only authority allowed to evaluate that matrix. The JCEF frontend receives capabilities and proposed changes; it never chooses whether a write is allowed.

### Host IDE Delivery Matrix

| Artifact lane | IntelliJ branches | Runtime / bytecode | Kotlin baseline | JCEF declaration | Initial compatibility declaration | Why | Confidence |
|---|---|---|---|---|---|---|---|
| `idea253` | IDEA 2025.3 (`253`) through 2026.1 (`261`) | JBR 21 / JVM target 21 | Kotlin 2.2.20 language/API baseline; use the IDE-bundled stdlib and coroutines | Runtime support check with `JBCefApp.isSupported()`; do not declare the new JCEF module alias in the artifact that must install on early 2025.3 | `sinceBuild=253`, `untilBuild=261.*` | A single Java 21 artifact can cover the Jmix 2.8 minimum IDE and 2026.1 without pulling Kotlin runtime duplicates into the plugin | HIGH |
| `idea262` | IDEA 2026.2 (`262`) initially | JBR 25 / JVM target 25 | Kotlin 2.4.0, matching the bundled 2026.2 compiler/runtime | Declare `com.intellij.modules.jcef` and add the Gradle bundled dependency `intellij.platform.ui.jcef` | `sinceBuild=262`, `untilBuild=262.*` until verified against later branches | IntelliJ 2026.2 moves to Java 25, removes K1-era Kotlin APIs, and requires an explicit JCEF dependency | HIGH |

Both artifacts contain the same protocol, semantic model, Jmix adapters, and feature behavior. Only thin platform wiring, compiler target, Kotlin baseline, IDE test distribution, and JCEF dependency metadata differ.

Do not advertise one universal binary across the 2026.2 boundary. Revisit the split only after Plugin Verifier and real-IDE tests prove that a single artifact can safely span both runtimes.

### Target Jmix Capability Matrix

Certification is per exact tested patch and operation. A version range may be recognized, but only manifest entries backed by fixtures may perform writes.

| Target repository | Java / Gradle baseline | Framework-family facts used by adapters | Read/write policy | Required fixture seeds | Confidence |
|---|---|---|---|---|---|
| Jmix 2.8.x | Java 17 and 21; official 2.8 migration wrapper is Gradle 8.14.4 | Jmix 2.x/Spring Boot 3/Vaadin 24 family; IDEA 2025.3 minimum for Studio 2.8 | Certify exact patches individually. Start with 2.8.0 and current 2.8.2; allow writes only for artifact/operation cells that pass the suite | Fresh 2.8.0, current 2.8.2, upgraded 2.7→2.8, Java 17, Java 21, Groovy DSL, Kotlin DSL, mixed-language, add-on-heavy, multi-store, composite | HIGH for published baseline; MEDIUM for certification breadth until built |
| Jmix 3.0.x | Java 21 or 25; official 3.0 wrapper is Gradle 9.5.1 | Spring Boot 4, Vaadin 25.1, EclipseLink 5, Flowable 8; Studio supports `build.gradle.kts` | Certify exact patches individually. Seed 3.0.0; new patches are recognized read-only until regression fixtures pass | Fresh 3.0.0, upgraded 2.8→3.0, Java 21, Java 25, both Gradle DSLs, mixed-language, add-on-heavy, multi-store, composite | HIGH for 3.0.0 baseline; MEDIUM for future patches |
| Future Jmix 3.x minor/patch | Derive from imported model, wrapper, toolchains, BOM and plugin versions | Unknown until official release notes and schemas are reviewed | Recognize version evidence and provide safe diagnostics; no mutation until an adapter/schema update and fixture suite pass | One representative project for every new framework minor plus upgraded enterprise repository fixtures | HIGH as a safety policy |
| Jmix 2.0–2.7 | Typically Java 17/21 depending on release; wrapper varies | Earlier 2.x schemas, dependencies, and Studio conventions | Read-only inventory, validation, preview, and migration guidance by default. Add narrowly certified write cells only if business demand justifies dedicated adapters | Representative LTS-era and upgraded projects; never infer compatibility from 2.8 alone | MEDIUM |
| Jmix 1.x, CUBA-derived, Maven, unsupported Gradle, custom/unresolved BOM | Varies | Legacy or unrecognized metadata | Static, non-destructive diagnostics only. Never run migrations or silently normalize files | Sanitized customer repositories used only to improve recognition and messages | HIGH as a safety policy; LOW on complete recognition |
| Version cannot be proven | Unknown | Conflicting build scripts, catalogs, imported model, or dependency graph | Read-only; report the conflicting evidence and the exact fact needed to unlock a capability | Conflict and offline-resolution fixtures | HIGH |

### Certification Dimensions

The suite must cover the projects enterprises actually operate, not only generated samples.

| Dimension | Minimum certification cells | Why it changes safety |
|---|---|---|
| Source language | Java-only, Kotlin-only, mixed Java/Kotlin | UAST can normalize reads, but Java PSI and Kotlin PSI/Analysis API require different mutation paths |
| Build DSL | Groovy `build.gradle`, Kotlin `build.gradle.kts`, version catalogs, convention plugins | Build scripts are executable programs; a formatter or text template cannot safely rewrite arbitrary constructs |
| Topology | Single-module, multi-module, Gradle composite build, nested included build, functional/starter add-on pair | An included build is a separate Gradle build, not a subproject; module ownership and dependency direction differ |
| Dependency shape | Jmix core only, many official add-ons, third-party add-ons, internal add-ons, overridden dependency versions | Add-ons contribute entities, views, resources, configuration, descriptors, and transitive constraints |
| Data stores | Main store only, multiple stores, cross-store references, profile-specific properties, placeholders, custom database settings | Store ownership affects entity relations, repositories, fetch plans, Liquibase, and runtime validation |
| Repository history | Fresh generated project, upgraded project, hand-customized project, partially migrated project | Upgrades and local edits leave valid shapes that generated-sample assumptions miss |
| Customization | Unknown XML attributes/elements, comments/order, custom controllers, entity annotations, menu/security extensions, handcrafted Liquibase, custom source sets | Round-trip preservation is a release requirement; unknown-but-valid content must survive |
| Dependency access | Public repositories, internal Maven proxy, offline/cached dependencies, unavailable private add-on | Diagnostics must degrade without requiring network access or leaking repository credentials |
| IDE import state | Fully synced, stale sync, failed sync, smart mode, dumb/indexing mode | Some semantic capabilities require indexes or a resolved Gradle model and must be disabled explicitly when absent |

Every read/write cell must pass:

1. Open and recognize the repository without executing an editor mutation.
2. Parse → semantic model → no-op serialization with zero diff.
3. Apply a golden mutation and preserve all unrelated bytes or PSI structure that the operation does not own.
4. Reopen and reparse the result.
5. Compile or run the appropriate Gradle verification in the repository's own wrapper/toolchain.
6. Run relevant Jmix application-context or focused integration checks.
7. Undo inside the IDE and verify exact restoration.
8. Exercise cancellation, partial failure, and atomic rollback.
9. Install and test both applicable IDE artifact lanes, then run Plugin Verifier.

### Capability-Gating Rules

- Certification is operation-level, not a single project-wide Boolean. An unknown build-script construct can block dependency edits while entity inspection and XML preview remain available.
- Recognition never implies permission to write.
- Version detection must compare the imported Gradle model, Jmix Gradle plugin version, platform BOM/dependencies, catalogs, wrapper, and source conventions. Conflicts make the relevant capability read-only.
- Static diagnostics must not require Gradle execution, network access, database access, or private-repository credentials. Use IntelliJ's already imported model when available.
- Database introspection is an explicit, separately consented read-only action. It is not a prerequisite for opening or diagnosing a project.
- Every UI action asks the backend for a current capability token tied to document versions. Stale tokens and stale previews are rejected.
- Unknown elements, annotations, comments, ordering, formatting, custom source roots, and add-on contributions are preserved unless the operation explicitly owns them.

## Recommended Stack

### Build, Language, and Host Platform

| Technology | Recommended version/baseline | Purpose | Why | Confidence |
|---|---|---|---|---|
| IntelliJ Platform Gradle Plugin | 2.18.0 | Build, instrument, test, verify, sign, and publish both plugin artifacts | The 2.x plugin is the active toolchain; 1.x is no longer active. Version 2.18.0 is the compatibility recommendation for IntelliJ 2026.2 | HIGH |
| Gradle wrapper for the plugin product | 9.6.1 | Reproducible product build and CI | Current documented Gradle line; can run on the Java 21 and Java 25 build lanes. Keep target-project fixtures on their own official wrappers | HIGH |
| Java toolchains | 21 for `idea253`, 25 for `idea262` | Compile and test against the host runtime | IntelliJ 2024.2+ uses Java 21; IntelliJ 2026.2 moves to Java 25. Gradle toolchains make the lanes explicit | HIGH |
| Kotlin | 2.2.20 API/language floor for shared code; 2.4.0 compiler for `idea262` | Plugin implementation and typed protocol models | These align with the Kotlin versions bundled by IDEA 2025.3 and 2026.2. Restrict shared code to the lower API floor and isolate lane-specific code | HIGH |
| IntelliJ bundled Kotlin stdlib/coroutines | IDEA-bundled versions | Runtime Kotlin support and background tasks | JetBrains advises plugin authors not to bundle a duplicate stdlib and to use the bundled coroutine library | HIGH |
| IntelliJ Platform APIs | 2025.3 as the shared source floor | IDE services, projects, documents, indexes, actions, UI integration | Matches the minimum IDE required by Jmix 2.8 and minimizes compatibility shims | HIGH |

Use a Gradle multi-project build with conceptual modules such as:

```text
protocol-schema       JSON Schema and generated Kotlin/TypeScript contracts
domain-model          IDE-independent normalized Jmix model and ChangePlan
jmix-adapter-spi      recognition, capabilities, schemas, operations
jmix-adapter-2_8      Jmix 2.8 rules and fixtures
jmix-adapter-3_0      Jmix 3.0 rules and fixtures
intellij-semantic     PSI/UAST/XML/Gradle/Workspace implementations
host-idea253          Java 21 plugin distribution
host-idea262          Java 25 plugin distribution and explicit JCEF dependency
frontend              React/Vite application bundled as plugin resources
fixtures              representative repositories and expected changes
```

Keep `domain-model`, adapter decisions, protocol validation, and diff planning independent of IntelliJ where practical. Put all PSI handles, `Project`, `VirtualFile`, smart pointers, read/write actions, and UI services behind `intellij-semantic`.

### IntelliJ Dependencies

| Dependency/module | Declaration role | Required? | Notes |
|---|---|---|---|
| `com.intellij.modules.platform` | Base platform APIs | Yes | Declare the lowest compatible platform boundary |
| `com.intellij.modules.xml` | XML PSI and DOM APIs | Yes | Required for views, menus, fetch plans, and Liquibase XML |
| `com.intellij.java` | Java PSI, Java project model and test framework support | Yes | Declare as bundled plugin dependency |
| `com.intellij.gradle` | Imported Gradle project/build-tree model | Yes | Use resolved/imported structure rather than parsing only the root build file |
| `org.jetbrains.kotlin` | Kotlin PSI and Analysis API | Optional dependency with separate descriptor if Java-only projects should still open without Kotlin plugin | K2/Analysis API is the supported direction; do not use K1 APIs |
| `com.intellij.modules.jcef` / `intellij.platform.ui.jcef` | JCEF runtime | Required in the `idea262` visual distribution | Also retain `JBCefApp.isSupported()` and a native fallback/error surface |

Use optional plugin configuration descriptors to keep non-Kotlin inspections usable if the Kotlin plugin is unavailable. A Kotlin-source write capability must remain off until its dependency and indexes are ready.

### Project Model and Semantic Editing

| Technology/API | Version scope | Purpose | Required usage |
|---|---|---|---|
| Workspace Model and `ProjectFileIndex` | Public Workspace Model API, 2024.2+ | Modules, content roots, source roots, ownership | Locate source and resources from the IDE model; do not assume Maven-like paths |
| IntelliJ Gradle integration model | Host-bundled `com.intellij.gradle` | Multi-project and composite build topology, dependencies, source sets | Model included builds as separate builds and keep ownership stable across refresh |
| UAST | Host API | Unified Java/Kotlin read-only queries | Use narrow conversions for performance; never mutate UAST |
| Java PSI | Host API | Java parsing, references, generation, and mutation | Create/replace semantic PSI nodes inside a command/write action |
| Kotlin PSI + Analysis API | K2-era API | Kotlin resolution and language-specific generation/mutation | Use Analysis API for semantics and Kotlin PSI/code style for edits; isolate API churn behind an adapter |
| XML PSI | Host API | Loss-minimizing parsing and mutation | Preservation layer for comments, ordering, unknown nodes, namespaces, and malformed-but-diagnosable files |
| XML DOM | Host API | Typed access to known Jmix descriptor/schema elements | Use for known schema projections; fall back to XML PSI for preservation. Do not depend on the unmaintained DOM UI-binding layer |
| Properties PSI/document APIs | Host API | Message bundles and configuration | Preserve encoding, escaping, comments, and locale-file relationships |
| Smart PSI pointers and document modification stamps | Host API | Stable preview/apply linkage | Reject apply when files or PSI have changed since preview |
| Command processor, `WriteCommandAction`, PSI document manager, VFS | Host API | Atomic writes and IDE undo | One user-visible operation must be one undoable command; no direct `java.io.File` mutation |

The core mutation pipeline should be:

```text
repository evidence
  → version/topology recognition
  → normalized semantic model
  → capability decision
  → immutable ChangePlan
  → previewed per-file semantic/text diff
  → revalidate versions and invariants
  → one IDE command/write transaction
  → reparse and verify
  → rollback on failure
```

Do not regex-rewrite Java, Kotlin, XML, or Gradle source. For Gradle DSL constructs that cannot be represented safely by the available model, emit a proposed patch or diagnostic and keep automatic apply disabled.

### Jmix Adapter Layer

Each adapter should own:

- Version evidence and contradiction rules.
- Known Jmix annotations, base types, add-on conventions, descriptor namespaces, and schemas.
- Mapping between entities, DTOs, views, controllers, data stores, fetch plans, menus, security roles, messages, and Liquibase artifacts.
- Artifact-specific read and write capabilities.
- Preservation policy and invariants.
- Fixture manifests and golden mutations.

Adapters return normalized domain objects and `ChangePlan` operations; they do not expose PSI objects to JCEF. Supporting a new Jmix patch should normally mean adding/updating fixtures, schema data, and narrowly scoped adapter rules rather than branching throughout the UI.

Jmix composite projects need first-class modeling. Gradle `includeBuild` creates separate builds in a build tree, while Jmix add-ons often contain functional and starter modules. A flat “all modules are subprojects” model will produce edits in the wrong repository and must not be used.

### Frontend and JCEF Workbench

| Technology | Recommended version | Purpose | Why | Confidence |
|---|---|---|---|---|
| React | 19.2.x | Visual canvases, inspectors, diff/plan views | Mature component model and current maintained release | HIGH |
| TypeScript | 6.0.x | Strict frontend types and generated protocol contracts | Current stable language/tooling line in 2026; use `strict` and no implicit untyped bridge payloads | HIGH |
| Vite | 8.1.x, lock the minor | Bundling the frontend into plugin resources | Current supported Vite line; produces a static bundle suitable for JCEF. Vite advises pinning minors when type changes matter | HIGH |
| Node.js | 24 LTS | Reproducible frontend build in local development and CI | Supported LTS line and satisfies Vite 8's runtime requirements | HIGH |
| npm | Version bundled/pinned with Node 24; committed lockfile and `npm ci` | Dependency resolution | Minimize toolchain surface; reproducibility matters more than changing package managers | HIGH |
| Zod | 4.x | Validate untrusted JSON at the frontend boundary | TypeScript inference plus runtime validation; malformed or newer payloads fail closed | HIGH |
| Zustand | 5.x | Normalized transient canvas/draft state | Small state layer suited to editor drafts; server/IDE state still belongs to the Kotlin backend | MEDIUM |

Use native IntelliJ Swing/Kotlin UI for settings, simple forms, notifications, actions, project-tree integrations, and accessibility-critical dialogs. Use React/JCEF only where a graph, canvas, spatial editor, or rich visual diff materially benefits from browser layout and interaction.

The frontend is a packaged static application:

- No localhost server in production.
- No remote scripts, CDN assets, or arbitrary navigation.
- A restrictive Content Security Policy.
- JCEF client, request handlers, and subscriptions disposed with the IntelliJ component lifecycle.
- A clear fallback when JCEF is unsupported.
- IDE theme, scale, keyboard, focus, accessibility, and locale tokens passed through a small platform adapter.

### Typed Bridge and Protocol

Use JSON Schema as the source of truth for commands, events, capabilities, errors, protocol version, request ID, project/session ID, document versions, cancellation, and progress.

Recommended protocol tooling:

| Technology | Version | Purpose | Notes |
|---|---|---|---|
| JSON Schema | Draft 2020-12 | Language-neutral contract | Commit schemas and compatibility examples |
| Kotlin serialization JSON | 1.9.0 for the lower Kotlin 2.2 lane unless a compatibility test justifies a newer split | Kotlin encoding/decoding | Exclude Kotlin stdlib from the packaged plugin; use strict decoding and explicit discriminators |
| Zod | 4.x | Frontend validation generated from or checked against schemas | Validate every inbound event and outbound command |
| Schema compatibility check | CI-owned | Prevent accidental breaking protocol changes | Breaking changes require a protocol-major bump and dual-version negotiation or a coordinated artifact release |

The bridge should expose small typed commands such as `loadModel`, `planChange`, `validatePlan`, `applyPlan`, and `cancelRequest`, not a generic “execute backend method” endpoint. Responses must distinguish unsupported, read-only, stale, validation failure, cancellation, and internal failure.

### Testing Stack

| Layer | Recommended technology/version | What it proves | Confidence |
|---|---|---|---|
| Domain and adapter units | Kotlin test + JUnit Jupiter 5.x, pinned current patch in version catalog | Version recognition, capability matrices, normalization, invariants, protocol behavior | MEDIUM on patch, HIGH on approach |
| PSI/XML/Gradle model tests | IntelliJ Platform test framework matching each target IDE; `TestFrameworkType.Platform` and Java/Kotlin bundled test dependencies | Real PSI parsing and edits, code style, references, XML preservation, model ownership | HIGH |
| Fixture/golden tests | Real representative Jmix repositories, per-fixture wrapper/toolchain, snapshot and semantic assertions | No-op round trip, targeted diff, compilation, upgrade/customization preservation | HIGH |
| Real-IDE integration | IntelliJ Starter and Driver, JUnit 5 | Project import, indexes, actions, tool windows, dialogs, Gradle refresh, undo, dumb mode, lifecycle | HIGH |
| JCEF integration | JetBrains JCEF test helpers plus Starter/Driver on installer distributions | Native browser startup, bridge, focus, disposal, fallback | HIGH |
| Frontend units/components | Vitest 4.1.x + React Testing Library | Reducers/stores, validators, components, keyboard and accessibility behavior | HIGH |
| Browser UI/bridge-stub E2E | Playwright 1.62.x | Complex canvas workflows and protocol failure cases without booting an IDE for every case | HIGH |
| Plugin binary verification | JetBrains Plugin Verifier via `verifyPlugin` against every supported branch | Removed/internal API use, compatibility problems | HIGH |
| Signed artifact verification | IntelliJ `verifyPluginSignature` | Release ZIP signature and certificate chain | HIGH |

JetBrains recommends functional/model-level tests with real platform components rather than mocks. Mock only process/network/database boundaries owned by this product. Do not mock PSI, project model, documents, VFS, or undo behavior in the tests that claim write certification.

JCEF integration CI must use IDE installer artifacts for 2026.2 because JetBrains notes that the multi-OS archive currently does not contain the native JCEF libraries. Run a small OS matrix on macOS, Windows, and Linux for every release candidate.

### Release, Signing, Provenance, and SBOM

| Technology | Recommended version | Purpose | Why | Confidence |
|---|---|---|---|---|
| JetBrains `signPlugin` | From IntelliJ Platform Gradle Plugin 2.18.0 | Sign each distribution ZIP | Native supported signing workflow; keys/passwords supplied only through CI secrets | HIGH |
| `verifyPluginSignature` | Same build plugin | Verify the produced ZIP before publication | Detects signing or packaging mistakes before upload | HIGH |
| Plugin Verifier | Tool resolved by Gradle plugin; pin tested release in dependency lock/version catalog | Verify binaries against supported IDEs | Required compatibility gate, not a post-release check | HIGH |
| CycloneDX Gradle plugin | 3.3.0 | JVM/Gradle SBOM | Supports aggregate multi-project CycloneDX 1.6 output | HIGH |
| `@cyclonedx/cyclonedx-npm` | 4.2.1 | Frontend/npm SBOM | Produces reproducible npm dependency BOMs and supports validation | MEDIUM on patch, HIGH on tool |
| CycloneDX CLI | Pin current verified release | Merge JVM and frontend BOMs and validate final CycloneDX 1.6 document | One release-level SBOM must represent the packaged product | HIGH |
| GitHub Actions artifact attestations | Current hosted action/API | Build provenance and SBOM attestations | Sigstore-backed proof linking source workflow, artifact, and SBOM | HIGH |
| SHA-256 checksums | Platform tools/CI | Offline artifact integrity | Simple channel-independent verification | HIGH |

Release each host artifact with:

- Signed plugin ZIP.
- SHA-256 checksum.
- CycloneDX 1.6 aggregate SBOM covering Kotlin/JVM and bundled npm assets.
- Build-provenance attestation and SBOM attestation.
- Embedded compatibility manifest and human-readable support matrix.
- Plugin Verifier report for every advertised IDE branch.
- Fixture certification report listing exact Jmix patches and operation cells.

Releases are produced only in CI from a tagged commit with locked dependencies and clean generated sources. Marketplace re-signing does not replace verifying the locally produced signature and provenance before upload.

## CI Compatibility Matrix

Run fast shared tests on every pull request, then use a tiered certification matrix:

### Pull Request Gate

| Axis | Cells |
|---|---|
| Host | IDEA 2025.3/JBR 21 and IDEA 2026.2/JBR 25 compile, unit, PSI, protocol, frontend |
| Jmix | Representative Jmix 2.8.2 and 3.0.0 repositories |
| Operations | No-op round trip plus affected golden mutations |
| Binary | Plugin Verifier on lowest and newest advertised IDE |
| Frontend | Type check, lint, Vitest, bridge-schema compatibility |
| Supply chain | Dependency lock check, SBOM generation/validation, secret scan |

### Nightly Gate

- Full Java/Kotlin/mixed, Groovy/Kotlin DSL, single/multi/composite, add-on, data-store, upgraded/customized fixture matrix.
- Current supported patch plus oldest certified patch for each Jmix family.
- Offline and unavailable-private-dependency diagnostics.
- Gradle sync success, stale sync, failed sync, and dumb-mode behavior.
- Mutation compile/integration tests using each fixture's own wrapper and toolchain.
- Repeated open/edit/undo/close loops to detect PSI, message-bus, JCEF, and process leaks.

### Release-Candidate Gate

- All certified cells, no sampling.
- macOS, Windows, and Linux real-IDE/JCEF smoke tests.
- Plugin Verifier for all advertised host branches.
- Signature verification, install from ZIP, Marketplace-compatible packaging.
- Reproducibility check, final merged SBOM validation, checksums, attestations.

## Version and Upgrade Policy

- Pin the Gradle wrapper, IntelliJ Platform Gradle Plugin, Node major, Vite minor, TypeScript minor, test drivers, SBOM tools, and Plugin Verifier in version catalogs/lockfiles.
- Depend on documented public IntelliJ APIs only. Run the JetBrains API migration tool and Plugin Verifier before raising `untilBuild`.
- Treat every IDE branch, Jmix patch/minor, Gradle wrapper shift, Kotlin bundled-version shift, and descriptor schema shift as a compatibility event.
- A new target version enters **recognized read-only** first. It becomes **certified read/write** only after official-source review, adapter/schema update, and all relevant fixtures pass.
- Keep two previously certified plugin releases downloadable so enterprise users can remain on an older IDE/Jmix combination during controlled upgrades.
- Publish compatibility changes as product release notes, including capabilities withdrawn because a regression was found.
- Do not auto-update or rewrite a target project's Gradle wrapper, Java toolchain, Jmix version, or dependencies merely to make it fit the workbench. Migration is a separate, previewed, explicitly selected operation.

## Alternatives Considered

| Category | Recommended | Alternative | Why not |
|---|---|---|---|
| Host packaging | Two artifacts at the Java 21/25 boundary | One binary for IDEA 2025.3 through 2026.2 | Conflicting bytecode/runtime, Kotlin, and JCEF dependency requirements increase untestable conditional behavior |
| Backend language | Kotlin | Java-only plugin | Kotlin integrates naturally with IntelliJ APIs and immutable domain modeling; Java does not eliminate the need for Kotlin Analysis API integration |
| Source analysis | PSI + UAST + Analysis API | Regex/text parsing | Cannot safely resolve references, preserve semantics, or support Java/Kotlin enterprise customization |
| XML handling | XML PSI preservation plus typed DOM projection | DOM serialization alone | A generic serializer risks deleting comments, unknown nodes, ordering, namespaces, and customer extensions |
| Project topology | Workspace/imported Gradle build-tree model | Scan conventional directories/root `build.gradle` | Fails custom source sets, multi-module projects, and composite/included builds |
| Gradle mutations | Narrow semantic PSI/model edits with proposed-patch fallback | Template-rewrite the build script | Gradle DSL is executable and often customized through catalogs and convention plugins |
| UI | Native IntelliJ UI plus focused React/JCEF canvases | All-JCEF application | Weakens IDE integration, accessibility, lifecycle behavior, and simple-form maintainability |
| Frontend framework | React 19.2 + TypeScript 6 | Vaadin inside JCEF | Vaadin is the target application's runtime UI technology, not an appropriate embedded editor frontend dependency |
| Plugin runtime deps | No Jmix/Spring/Vaadin runtime | Embed Jmix libraries in the plugin | Causes classloader/version conflicts and couples the IDE plugin to one target framework patch |
| State authority | Kotlin semantic model and source-of-truth files | JCEF browser state | Browser state can become stale and cannot safely own PSI/document writes |
| Protocol | Versioned schema-generated commands/events | Raw string JavaScript callbacks or generic RPC | Untyped payloads make compatibility, validation, cancellation, and security failures likely |
| End-to-end tests | Starter/Driver plus platform/JCEF test helpers | Screenshot-only GUI automation | Screenshot tests do not prove PSI safety, undo, imported models, or backend invariants |
| Legacy support | Read-only diagnostics until certified | Best-effort write support | False positives on customized enterprise repositories can corrupt source and erode trust |
| SBOM | CycloneDX aggregate JVM+npm BOM | JVM dependency report only | Misses the bundled frontend supply chain |

## Build Baseline

The exact task names should be finalized with the Gradle build, but the intended reproducible flow is:

```bash
# Frontend: Node 24 LTS and committed package-lock.json
npm ci --prefix frontend
npm run typecheck --prefix frontend
npm test --prefix frontend
npm run build --prefix frontend

# Plugin: product wrapper Gradle 9.6.1 with provisioned JDK 21 and 25 toolchains
./gradlew check
./gradlew buildPlugin
./gradlew verifyPlugin

# Release-only CI with protected secrets
./gradlew signPlugin
./gradlew verifyPluginSignature
./gradlew cyclonedxBom
```

Fixture tasks must invoke the wrapper checked into each fixture (`8.14.4` for the Jmix 2.8 migration baseline and `9.5.1` for the Jmix 3.0 baseline). The product build must not silently replace those wrappers.

## Sources

All critical platform/version claims below come from official documentation, official release notes, or official project repositories.

### Jmix

- [Jmix 2.8 — What’s New](https://docs.jmix.io/jmix/2.8/whats-new/index.html) — Studio 2.8/IDEA 2025.3 minimum and Gradle 8.14.4 migration baseline. **HIGH**
- [Jmix official releases](https://github.com/jmix-framework/jmix/releases) — exact current framework patch tags, including 2.8.2 and 3.0.0. **HIGH**
- [Jmix 3.0 release notes](https://docs.jmix.io/jmix/whats-new/release-3.0.html) — Java 21/25, Gradle 9.5.1, Spring Boot 4, Vaadin 25.1, EclipseLink 5, Flowable 8, and Kotlin Gradle DSL support. **HIGH**
- [Jmix current setup](https://docs.jmix.io/jmix/setup.html) — current IDE and JDK requirements. **HIGH**
- [Jmix 2.2 release notes](https://docs.jmix.io/2.x/jmix/2.2/whats-new/index.html) — Java 17/21 support context for the 2.x family. **HIGH**
- [Jmix composite projects](https://docs.jmix.io/3.x/jmix/studio/composite-projects.html) — included builds, application/add-on structure, and cross-project dependencies. **HIGH**
- [Jmix data stores](https://docs.jmix.io/jmix/2.8/studio/data-stores.html) — main/additional stores, database variety, profiles/placeholders, and Liquibase tooling. **HIGH**
- [Jmix data model](https://docs.jmix.io/jmix/2.8/data-model/index.html) — multiple stores and cross-store entity relationships. **HIGH**
- [Creating Jmix add-ons](https://docs.jmix.io/jmix/modularity/creating-add-ons.html) — functional/starter module topology. **HIGH**
- [Jmix Studio features](https://docs.jmix.io/jmix/2.8/studio/studio-features.html) — official feature and artifact landscape. **HIGH**

### IntelliJ Platform

- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html) — active Gradle plugin, minimums, and tasks. **HIGH**
- [IntelliJ 2026.2 plugin compatibility announcement](https://platform.jetbrains.com/t/2026-2-is-coming-time-to-check-your-plugin-compatibility/4618) — branch `262`, Java 25, plugin 2.18.0, explicit JCEF dependency, and installer-artifact caveat. **HIGH**
- [IntelliJ build number ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html) — build branches and compatibility declarations. **HIGH**
- [Creating an IntelliJ plugin project](https://plugins.jetbrains.com/docs/intellij/creating-plugin-project.html) — Java 21/25 platform requirements. **HIGH**
- [Kotlin for IntelliJ plugins](https://plugins.jetbrains.com/docs/intellij/using-kotlin.html) — bundled Kotlin versions, stdlib, and coroutine policy. **HIGH**
- [Java and Kotlin plugin development](https://plugins.jetbrains.com/docs/intellij/idea.html) — Java/Kotlin bundled dependencies, K2, and Analysis API direction. **HIGH**
- [2026 IntelliJ API changes](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html) — removal of K1 Kotlin plugin APIs in 2026.2. **HIGH**
- [Plugin dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html) — bundled Java, Gradle, Kotlin dependency identifiers and optional descriptors. **HIGH**
- [Plugin compatibility with products/modules](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html) — platform and XML module declarations. **HIGH**
- [Embedded browser/JCEF](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html) — support checks, lifecycle, and testing. **HIGH**
- [PSI files](https://plugins.jetbrains.com/docs/intellij/psi-files.html) and [modifying PSI](https://plugins.jetbrains.com/docs/intellij/modifying-psi.html) — semantic source model and safe mutation rules. **HIGH**
- [UAST](https://plugins.jetbrains.com/docs/intellij/uast.html) — unified Java/Kotlin read-only analysis and mutation limits. **HIGH**
- [XML DOM API](https://plugins.jetbrains.com/docs/intellij/xml-dom-api.html) — typed XML mapping and dependency. **HIGH**
- [Documents and commands](https://plugins.jetbrains.com/docs/intellij/documents.html) — undo-stack semantics. **HIGH**
- [IntelliJ project and Workspace Model](https://plugins.jetbrains.com/docs/intellij/project-model.html) — module/root modeling and public Workspace Model status. **HIGH**
- [Testing IntelliJ plugins](https://plugins.jetbrains.com/docs/intellij/testing-plugins.html) — real-platform functional testing guidance. **HIGH**
- [IntelliJ test framework dependencies](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html) — platform/Java test framework, verifier, and signer dependencies. **HIGH**
- [IntelliJ integration tests with Starter and Driver](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html) — real-IDE JUnit integration testing. **HIGH**
- [Plugin compatibility verification](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html) — Plugin Verifier workflow. **HIGH**
- [Plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) — signing and signature verification. **HIGH**

### Gradle, Frontend, Testing, and Supply Chain

- [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html) — current Gradle/JVM runtime compatibility. **HIGH**
- [Gradle Java toolchains](https://docs.gradle.org/current/userguide/toolchains.html) — reproducible multi-JDK builds. **HIGH**
- [Gradle composite builds](https://docs.gradle.org/current/userguide/composite_builds.html) — included-build semantics. **HIGH**
- [Gradle 9.5.1 release notes](https://docs.gradle.org/9.5.1/release-notes.html) — Jmix 3.0 fixture wrapper context. **HIGH**
- [Kotlinx serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases) — Kotlin compiler compatibility by release. **HIGH**
- [React 19.2](https://react.dev/blog/2025/10/01/react-19-2) — current recommended React line. **HIGH**
- [TypeScript 6.0 release notes](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-6-0.html) — current compiler line. **HIGH**
- [Vite 8 announcement](https://vite.dev/blog/announcing-vite8) and [supported Vite releases](https://vite.dev/releases) — Node minimum and maintained minor policy. **HIGH**
- [Node.js releases](https://nodejs.org/en/about/previous-releases) — Node 24 LTS status. **HIGH**
- [Zod 4](https://zod.dev/v4) — current runtime schema-validation line. **HIGH**
- [Vitest releases](https://vitest.dev/blog) — current frontend test line. **HIGH**
- [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/) — component testing approach. **HIGH**
- [Playwright release notes](https://playwright.dev/docs/release-notes) — current browser test line. **HIGH**
- [CycloneDX Gradle plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin) — Gradle SBOM version and aggregate support. **HIGH**
- [CycloneDX npm tool](https://github.com/CycloneDX/cyclonedx-node-npm) — npm SBOM generation. **HIGH**
- [CycloneDX CLI](https://github.com/CycloneDX/cyclonedx-cli) — merge and validation workflow. **HIGH**
- [GitHub artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations) — provenance and SBOM attestation workflow. **HIGH**

## Confidence and Remaining Validation Gaps

| Area | Confidence | Reason / next validation |
|---|---|---|
| IntelliJ host split and build tooling | HIGH | Based on current JetBrains 2026.2 migration guidance and official platform docs |
| Jmix 2.8/3.0 Java and wrapper baselines | HIGH | Explicit in official Jmix release notes |
| Semantic editing architecture | HIGH | Directly follows supported IntelliJ APIs and their documented mutation constraints |
| Enterprise certification dimensions | MEDIUM-HIGH | Derived from official Jmix topology/data-store/add-on features and repository assessment; must be validated with sanitized real customer repositories |
| Frontend framework choices | HIGH | All core recommendations are current maintained official releases |
| Exact frontend/testing patch pins | MEDIUM | Re-resolve and lock exact patches when the build is created; the architecture does not depend on those patches |
| Kotlin serialization 1.9.0 across both host lanes | MEDIUM | Verify against packaged-plugin dependency inspection and both Kotlin compilers; split protocol runtime by lane if needed |
| Jmix 2.0–2.7 and 1.x recognition coverage | LOW-MEDIUM | Requires explicit legacy samples and official version-by-version schema review; therefore remains read-only by default |
| Safe writes in arbitrary Gradle convention plugins/custom DSL | LOW | Some constructs cannot be safely represented; proposed patches/read-only behavior is the intended permanent fallback |

The highest-priority phase-specific research should be a prototype compatibility harness that opens representative Java/Kotlin, multi-module/composite, add-on-heavy, multi-store, customized, and upgraded repositories on both IDE lanes. That evidence should finalize the first public read/write cells before feature breadth is expanded.

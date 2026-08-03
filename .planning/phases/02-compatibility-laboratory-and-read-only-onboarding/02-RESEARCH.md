---
phase: 02-compatibility-laboratory-and-read-only-onboarding
date: 2026-07-28
status: complete
sources: primary
---

# Phase 2 Research: Compatibility Laboratory and Read-Only Onboarding

## Research Question

What architecture, IntelliJ APIs, Jmix evidence, safety boundaries, and
validation fixtures are required to plan an enterprise-grade, non-mutating
discovery and compatibility layer for existing Jmix repositories?

## Executive Recommendation

Phase 2 should replace the current `JmixProjectService` heuristic with a
read-only, snapshot-oriented discovery pipeline:

1. collect immutable facts from IntelliJ's already-imported project/module/root,
   SDK, library, and VFS models;
2. augment them with bounded static parsing of known Gradle/property/XML/source
   files without evaluating build scripts;
3. normalize facts into stable build/module/artifact identities with provenance,
   confidence, diagnostics, and content fingerprints;
4. derive an immutable semantic inventory incrementally and cancellably;
5. evaluate every operation/profile pair through one deny-by-default,
   evidence-backed compatibility registry;
6. expose snapshots and native navigation through a read-only bridge/API and a
   non-technical onboarding UI.

Do not use the Gradle Tooling API, project Gradle wrapper, application startup,
database connection, repository resolution, or network requests during this
phase. Gradle's Tooling API runs against a daemon and configures build logic;
that violates the zero-execution contract for untrusted onboarding.

## Current Gap Analysis

The live repository has a solid packaged JCEF foundation, but discovery is a
prototype:

- `JmixProjectService` reads only root `build.gradle[.kts]`.
- It uses deprecated `project.baseDir`, raw file bytes, regular expressions, and
  unsafe defaults (`2.4.0`, PostgreSQL, `com.example.app`).
- It cannot represent multiple builds, modules, source sets, languages, stores,
  add-ons, dependency health, migration graphs, or provenance.
- It caches one mutable `ProjectConfig` without a project/import/VFS revision.
- `getEntities` returns an empty hard-coded array.
- All visible designer tabs remain present even when the project/profile has no
  certified operation cell.
- The bridge has no correlated bounded discovery protocol; Phase 3 owns the full
  privilege-boundary protocol, so Phase 2 must expose only strictly read-only
  actions and avoid widening mutation.
- Existing generation services remain reachable in the prototype bridge.
  Phase 2 must ensure its onboarding surface cannot imply those operations are
  certified.

## Sourced Platform Facts

### Jmix version and Java cells

- Current Jmix 3.0 requires Java 21 or 25 and raises the minimum IntelliJ IDEA
  host to 2025.3. Source:
  [Jmix 3.0 release documentation](https://docs.jmix.io/jmix/whats-new/release-3.0.html)
  and [Jmix setup](https://docs.jmix.io/jmix/setup.html).
- Jmix 2.x supports application projects on Java 17 or 21. Source:
  [Jmix 2.x project setup](https://docs.jmix.io/2.x/jmix/2.7/tutorial/project-setup.html)
  and [Java 21 support notes](https://docs.jmix.io/jmix/2.2/whats-new/index.html).
- Earlier Jmix 2.x can be upgraded within the 2.x line, while Jmix 1.x/CUBA-era
  migration is architecturally different and should use an isolated incremental
  workflow. Source:
  [Migration from older versions](https://docs.jmix.io/2.x/jmix/2.8/migration-from-older-versions.html).

Recommendation: the detector reports observed version/JDK facts exactly. The
registry initially recognizes many profiles but certifies no Phase 2 writes.
The initial target cells remain:

| Jmix line | Target JDK evidence | Phase 2 state |
|---|---|---|
| 2.8.x | 17 or 21 | certified read-only when fixture evidence passes |
| 3.0.x | 21 or 25 | certified read-only when fixture evidence passes |
| earlier 2.x | observed 17/21/other | recognized diagnostic, read-only |
| 1.x / CUBA | observed | legacy diagnostic, read-only |
| future/unknown | observed | unsupported or diagnostic, read-only |

"Latest" must mean the latest JDK explicitly supported by the detected Jmix
line and fixture registry, not the latest installed JDK.

### Build and module topology

- Jmix composite projects use Gradle `includeBuild` and can aggregate
  applications and add-ons from one or multiple repositories. Source:
  [Jmix Composite Projects](https://docs.jmix.io/jmix/studio/composite-projects.html).
- Gradle composites contain recursively included, isolated builds; they differ
  from multi-project builds. Source:
  [Gradle Composite Builds](https://docs.gradle.org/current/userguide/composite_builds.html).
- Jmix add-ons normally have a functional module plus a starter module. Module
  dependency order affects overrides and Liquibase execution. Source:
  [Creating Jmix Add-ons](https://docs.jmix.io/jmix/modularity/creating-add-ons.html).

Recommendation: model `BuildNode`, `ModuleNode`, and `SourceSetNode` separately.
Never flatten included builds or infer ownership from path prefixes alone.

### Data stores and migrations

- A Jmix application can have a main store and multiple additional/custom
  stores. Source:
  [Jmix architecture](https://docs.jmix.io/jmix/concepts/architecture.html) and
  [Data Stores](https://docs.jmix.io/jmix/studio/data-stores.html).
- Store configuration is expressed through application properties and
  configuration classes; each managed store can have its own Liquibase root.
- Liquibase roots include application and add-on changelogs, and application
  changelogs execute after dependency changelogs. Source:
  [Database Schema Migration](https://docs.jmix.io/jmix/data-model/db-migration.html)
  and [Creating Jmix Add-ons](https://docs.jmix.io/jmix/modularity/creating-add-ons.html).

Recommendation: Phase 2 discovers property keys, resource paths, includes,
module/store ownership, and dialect evidence only. It never resolves credentials,
loads drivers, tests a connection, or opens a database.

### IntelliJ threading, PSI, and indexes

- IntelliJ PSI, VFS, project-root, and index structures are not thread-safe.
- Index-dependent analysis must be cancellable and smart-mode aware. Current
  SDK guidance recommends coroutine read actions for modern targets, while
  `ReadAction.nonBlocking().inSmartMode()` remains a cross-lane option.
  Source:
  [IntelliJ Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html).

Recommendation: host-specific adapters hide 253/262 read-action differences.
Long-lived caches store serializable facts and `SmartPsiElementPointer` only
where navigation requires it; never retain raw PSI elements between actions.

### Why the Gradle Tooling API is excluded

The Tooling API queries models by using a long-lived Gradle daemon and supports
build/task execution and downloads. Source:
[Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html).

Recommendation: Phase 2 may consume IntelliJ's already-imported external-system
data as evidence, but must not trigger sync, wrapper download, dependency
resolution, or Tooling API model requests. Missing import data becomes a
diagnostic, not permission to execute build logic.

## Proposed Domain Model

All values are immutable and JSON-serializable. Paths sent to the web UI are
project-relative display paths or opaque navigation IDs, never absolute machine
paths.

### Project snapshot

```text
DiscoverySnapshot
  snapshotId: SHA-256 of normalized facts
  projectId: opaque stable local ID
  createdAt
  trustState: TRUSTED | UNTRUSTED | UNKNOWN
  importState: READY | INDEXING | STALE | FAILED | ABSENT
  builds: List<BuildSnapshot>
  artifacts: List<ArtifactSummary>
  relationships: List<ArtifactRelationship>
  diagnostics: List<DiscoveryDiagnostic>
  capabilities: List<CompatibilityDecision>
```

### Build/module facts

```text
BuildSnapshot
  id, displayName, relativeRoot, kind, includedBy, provenance, fingerprint
ModuleSnapshot
  id, buildId, ideModuleId, gradlePath, role, sourceSets, sdk, languageMix
SourceRootSnapshot
  id, moduleId, relativePath, kind, language, generated, test, provenance
DependencyFact
  coordinate, selectedVersion, scope, resolved, origin, owningModule
```

Module roles: `APPLICATION`, `ADDON_FUNCTIONAL`, `ADDON_STARTER`,
`BUILD_LOGIC`, `AGGREGATOR`, `LIBRARY`, `UNKNOWN`.

### Jmix profile facts

```text
JmixProfile
  classification
  platformVersion
  platformLine
  targetJdk
  basePackages
  plugins
  addOns
  stores
  migrationRoots
  languages
  topology
  evidence
  diagnostics
```

Every inferred field uses:

```text
Evidence<T>
  value
  sourceKind
  sourceId
  confidence: EXACT | STRONG | WEAK | CONFLICTING
  observedFingerprint
```

No default value may masquerade as detected evidence. Unknown and conflicting
facts remain explicit.

### Semantic artifacts

Kinds required by DISC-06:

- entity, DTO, enum;
- view descriptor and controller;
- fetch plan;
- menu item/source;
- resource/row role;
- message bundle/key;
- repository;
- Liquibase root/include/changeset;
- module/build/source set;
- add-on and data store.

Each artifact has:

```text
ArtifactId = SHA-256(kind + buildId + moduleId + normalized semantic key)
ArtifactSnapshot
  id, kind, semanticKey, owner, sourceLocation, origin, fingerprint,
  displayName, summary, diagnostics
SourceLocation
  navigationId, relativePath, line, column, symbol
```

Relationships are typed (`DECLARES`, `CONTROLS`, `USES_ENTITY`, `EXTENDS`,
`REFERENCES_FETCH_PLAN`, `NAVIGATES_TO`, `INCLUDES_CHANGELOG`,
`BELONGS_TO_STORE`, `DEPENDS_ON_ADDON`, `LOCALIZES`) and identify source
provenance.

## Discovery Pipeline

### Stage 0: safety/trust snapshot

- Read project disposed/trust/dumb/import state.
- Do not trigger trust prompts, Gradle sync, downloads, network, or DB access.
- Create a new cancellation scope tied to project and tool-window disposal.

### Stage 1: imported topology facts

- Read ProjectFileIndex, ModuleManager, ModuleRootManager, SDK, content/source
  roots, libraries, and imported external-system metadata under bounded read
  actions.
- Snapshot values immediately into immutable plain data.
- Record missing, stale, conflicting, excluded, generated, test, and external
  roots.

### Stage 2: bounded static build/config scanning

- Scan only allowlisted filenames below known content/build roots:
  `settings.gradle[.kts]`, `build.gradle[.kts]`, `gradle.properties`,
  `libs.versions.toml`, wrapper properties, application properties/YAML,
  `jmix-studio.xml`, and known Liquibase root XML.
- Apply file-count, byte, depth, include, symlink, and time limits.
- Parse tolerant subsets; do not attempt to evaluate arbitrary Groovy/Kotlin
  DSL. Dynamic expressions produce `DYNAMIC_BUILD_LOGIC` diagnostics.
- Treat version catalogs, convention plugins, unresolved aliases, and imported
  dependency coordinates as separate evidence sources.

### Stage 3: incremental semantic index

- Use IntelliJ PSI/index APIs when smart, with cancellation checks and expiring
  read actions.
- In dumb mode, publish topology/config facts plus an `INDEXING` diagnostic and
  schedule later semantic slices.
- Process files by artifact kind and module; publish deltas by stable ID.
- Reparse changed files only. Dependency/library/import changes invalidate the
  affected profile or module slice.

### Stage 4: compatibility evaluation

- Convert normalized profile facts to a canonical `ProfileKey`.
- Evaluate `(operationId, ProfileKey)` against the reviewed registry.
- Return one state: `CERTIFIED_READ_WRITE`, `CERTIFIED_READ_ONLY`,
  `RECOGNIZED_DIAGNOSTIC`, `UNSUPPORTED`.
- Phase 2 registry contains no write-authorizing cells.
- Every decision contains reason code, human explanation, evidence IDs, missing
  evidence, tested alternative/migration path, and registry version/digest.

### Stage 5: UI/navigation projection

- Send bounded summaries/pages to the web UI.
- Keep full snapshot/cache in Kotlin, never in browser local storage.
- Resolve navigation IDs in Kotlin and invoke native OpenFileDescriptor or
  symbol navigation only after project/root/line validation.

## Compatibility Registry

The registry is version-controlled data, not `when` statements distributed
through features.

```text
operationId
profileSelector
state
reasonCode
evidenceRefs
testedJmix
testedTargetJdks
testedHostLanes
fixtureIds
expiresOnRegression
migrationHint
```

Required invariants:

- unknown operation => `UNSUPPORTED`;
- missing registry cell => no write capability;
- stale/conflicting/trust/import/index facts => downgrade only;
- a regression can disable one operation/profile cell without broad disabling;
- UI copy is generated from the backend decision, never recomputed in
  TypeScript;
- registry validation fails duplicate/overlapping ambiguous selectors and
  missing fixture evidence.

## UI/UX Implications

Phase 2 needs a new default `Project Overview` experience rather than dropping
users directly into an editable entity form.

Recommended information architecture:

- overview: project health, trust/import/index state, detected Jmix/JDK/profile,
  read-only banner, blockers, recommended next step;
- project tree: builds, modules, roots, add-ons, stores, migration roots;
- inventory: search/filter artifact table with counts and diagnostics;
- relationships: selected artifact incoming/outgoing links;
- compatibility: operation matrix with status/reason/evidence/tested path;
- diagnostics: severity, reason code, source link, non-technical explanation;
- existing designers remain visibly non-certified/disabled until later phases.

Copy rules:

- say "Read-only: no project files or databases were changed";
- distinguish "not detected", "conflicting", "still indexing", "import failed",
  "recognized but not certified", and "unsupported";
- never replace unknown values with defaults;
- show exact source/provenance on demand without exposing machine paths.

## Threat Model

| Threat | Consequence | Required mitigation | Blocking tests |
|---|---|---|---|
| Gradle/Tooling API invocation | arbitrary build code, downloads, file/network mutation | no Tooling API, wrapper, task, sync, or process launch in discovery package | process/network tripwire and static dependency scan |
| DB/file-store connection | credential exposure or external mutation | never load drivers or open JDBC/HTTP/file-store connections | socket/JDBC tripwire fixtures |
| VFS/IDE write action | project/cache/config mutation | discovery code has no write-command/write-action/service dependency | VFS byte tree and IDE-config snapshot before/after |
| Untrusted project escalation | executing repository-controlled logic | trust state is input; untrusted always diagnostic/read-only | untrusted fixture decision tests |
| Symlink/path escape | scanning outside repository | canonical containment, no follow-links, root/file/depth/byte budgets | adversarial symlink fixtures |
| Long-lived invalid PSI | crashes, stale results | immutable facts, pointers only for navigation, validity recheck | edit/delete/rename/dispose stress tests |
| EDT blocking | frozen IDE on enterprise repository | background cancellable stages and small UI projections | EDT assertions and latency budgets |
| Cancellation ignored | leaks/continued indexing after close | cooperative checks at file/item boundaries; project/toolwindow scope | cancellation/disposal tests |
| Snapshot drift | stale profile authorizes capability | content/import/root fingerprints; recompute/downgrade on drift | stale snapshot tests |
| Registry overlap/default allow | unintended write capability | deny-by-default, schema validation, exact evidence refs | property-based registry tests |
| Browser payload flood | memory/latency problems | paging, byte/item limits, stable cursors, no source bodies | payload limit tests |
| Sensitive path/credential leak | support/privacy exposure | relative paths, secret-redacting property view, opaque nav IDs | golden redaction tests |

## What Not to Build in Phase 2

- no entity/view/menu/role/migration writes;
- no project creation or migration execution;
- no Gradle sync, task, wrapper, dependency resolution, or Tooling API;
- no application startup, runtime metadata introspection, DB comparison, or
  network repository queries;
- no full Phase 3 bridge protocol or change engine;
- no promise of broad read/write support from a version-range match.

## Implementation Decomposition

Recommended plan sequence:

1. read-only domain model, reason codes, registry schema/validator, and mutation
   tripwire test harness;
2. imported project/build/module/root/dependency fact collector with 253/262
   host adapters;
3. bounded static config/build/topology/store/migration collector;
4. cancellable incremental semantic inventory, relationship graph, cache and
   invalidation;
5. read-only backend projection, native navigation, onboarding/diagnostic UI;
6. representative fixture laboratory and exact-host installed UAT.

Keep source collectors independent from UI serialization. Use pure functions
for normalization, classification, fingerprints, registry evaluation, and
diagnostic copy so most tests do not require an IDE.

## Validation Architecture

### Test layers

| Layer | Purpose | Examples |
|---|---|---|
| pure unit | parsers, normalization, IDs, fingerprints, registry decisions | dynamic DSL, conflicts, aliases, path budgets |
| contract/property | deny-by-default and deterministic serialization | selector overlap, stable digest, sorted output |
| IntelliJ managed host | imported roots/modules/libraries, PSI, smart/dumb, navigation | exact 253/262 adapters |
| mutation tripwire | prove no source/config/VCS/DB/network/process state change | before/after tree hashes, socket/process hooks |
| fixtures | realistic Jmix profiles and adversarial repositories | matrix below |
| web unit/accessibility | overview/inventory/diagnostic states and bounded paging | Vitest + testing library |
| installed-product | exact ZIPs in signed IDEs | import/open/index/browse/navigate/close |

### Required fixtures

- Jmix 2.8 Java 17 and Java 21, Groovy and Kotlin DSL;
- Jmix 3.0 Java 21 and Java 25, Java/Kotlin/mixed;
- single module, multi-project, monorepo composite, separate-repository
  composite, nested included build, convention-plugin/version-catalog build;
- application plus custom add-on functional/starter modules;
- main and multiple additional stores with multiple Liquibase roots;
- unresolved/private dependencies, offline imported model, failed import,
  stale import, indexing/dumb mode;
- earlier Jmix 2.x, Jmix 1.x, CUBA marker, future version, no Jmix,
  conflicting version/JDK evidence;
- generated/test/custom roots, symlink escape, oversized/deep tree, malformed
  XML/properties/TOML/build files;
- entities, DTOs, enums, views/controllers, fetch plans, menus, roles, messages,
  repositories, changelogs and unresolved relationships;
- enterprise synthetic scale fixture with deterministic generation.

### Per-plan command contract

Add root lifecycle tasks:

```text
phase2FastCheck
phase2FixtureCheck
phase2HostCheck
phase2WebCheck
phase2MutationCheck
phase2Check
```

`phase2Check` must run:

- all pure/shared tests;
- both exact host managed suites;
- web type/build/unit/accessibility checks;
- fixture classification/inventory golden tests;
- mutation tripwire for repository bytes, `.idea`, `.gradle`, VCS metadata,
  database/socket/process hooks;
- deterministic snapshot/registry digest checks;
- plugin ZIP content and Plugin Verifier gates;
- installed-product UAT evidence before phase completion.

### Nyquist sampling

- every task: focused unit/contract test;
- every plan: `phase2FastCheck`;
- every wave: relevant fixture/host/web/mutation gates;
- before verification: clean `phase2Check`;
- no three consecutive implementation tasks without a behavior test;
- negative fixtures are mandatory, not optional hardening.

### Performance budgets for Phase 2

Initial budgets to enforce and refine with evidence:

- no blocking discovery work on EDT; EDT entry methods return/schedule within
  50 ms in managed tests;
- first topology snapshot for small fixture within 2 s after imported model is
  ready;
- incremental single-file semantic update within 1 s p95 on synthetic fixture;
- cancellation stops new work within 250 ms at test-controlled checkpoints;
- browser page payload <= 1 MiB and <= 500 artifacts;
- no retained Project/PSI/browser references after disposal leak probe.

Enterprise-scale absolute budgets remain Phase 7 certification, but Phase 2
must build the measurement hooks.

### Acceptance evidence

Phase 2 cannot pass from model tests alone. Required evidence:

1. all fixture trees unchanged byte-for-byte before/after open, browse,
   navigation, cancellation, and close;
2. no external process, socket, DB, or network event from discovery;
3. exact profile and reason-coded capabilities for every fixture;
4. incremental inventory/navigation behavior in smart, dumb, stale, failed, and
   untrusted states;
5. same logical snapshot digest on both host lanes where host differences are
   irrelevant;
6. signed installed-product screenshots/logs on IDEA 2025.3 and 2026.2.

## Requirement-to-Architecture Map

| Requirements | Primary architecture |
|---|---|
| COMP-01/02/03/06/07 | profile evidence + registry + reason-coded downgrade |
| DISC-01 | mutation/network/DB/process tripwire and read-only collectors |
| DISC-02/03 | imported build/module/source-root topology |
| DISC-04 | imported dependency facts + bounded build/config parsing |
| DISC-05 | property/config/Liquibase store model, no connections |
| DISC-06/07 | stable semantic artifact snapshots |
| DISC-08 | cancellable incremental smart/dumb pipeline |
| DISC-09 | typed relationship graph |
| DISC-10 | opaque native navigation IDs |
| TEAM-07 | background scheduling, cancellation, disposal, EDT budgets |

## Planning Guidance

- Split this phase into several fine-grained plans; one monolithic "project
  scanner" would be unverifiable and hard to review.
- Put deny-by-default registry and mutation tripwire first so later collectors
  cannot accidentally acquire privilege.
- Build exact host API adapters only where 253/262 differ; keep the semantic
  core platform-agnostic.
- Treat imported data and static parsing as independent evidence sources with
  conflict diagnostics.
- Never serialize absolute paths, credentials, raw property values likely to
  contain secrets, or source bodies to the UI.
- Make every degraded state useful: users should still see topology,
  diagnostics, navigation to known files, and the precise reason more
  capability is unavailable.

# Project Research Summary

**Project:** Jmix Visual Development Workbench  
**Domain:** Enterprise IntelliJ plugin for safe, semantic, visual development of existing Jmix applications  
**Researched:** 2026-07-27  
**Confidence:** HIGH on product direction, platform architecture, and safety invariants; MEDIUM on the initial breadth of certified repository profiles until fixtures and customer pilots exist

## Executive Summary

This product should become the worldwide default companion for teams that already own valuable Jmix solutions, not a screen-for-screen Studio clone or another create-only generator. Experts build this class of tool as a source-aware IntelliJ plugin: the IDE's imported Gradle/Workspace model and PSI-based language services discover the real repository, an immutable semantic projection explains cross-artifact relationships, and every proposed edit is staged, diffed, validated, applied as one undoable command, and reverified. Source remains authoritative. The React/JCEF workbench is a focused visual surface for complex canvases and review, never the compatibility authority or file-writing backend.

The defining product contract is **feature-level certified compatibility**. The Kotlin backend must evaluate every operation against a signed, fixture-backed matrix covering the host IDE lane, exact Jmix version, JDK, source language, Gradle DSL, build topology, add-ons, data stores, artifact shape, and requested operation. A recognized project is not automatically writable. Exact cells with passing evidence receive certified read/write support; recognized-but-uncertified, legacy, ambiguous, stale, untrusted, or customized cells degrade to useful diagnostic/read-only behavior. There must be no “force unsafe generation” switch. This lets the product welcome long-lived enterprise repositories worldwide from the first release while expanding mutation safely instead of making unverifiable blanket claims.

The principal risk is confident mutation based on an incomplete model of a customized repository. Mitigate it by building the compatibility laboratory and broad read-only discovery first; centralizing all writes in a deterministic `ChangePlan` pipeline; using Java/Kotlin/XML/properties/Gradle-specific semantic editors; and requiring zero-diff round trips, compile/integration validation, fault rollback, exact Undo/Redo, installed-plugin testing, and release provenance for every certified write cell. The first public mutation promise should be deliberately narrow—Jmix 2.8 and 3.0 entity/data workflows on explicitly listed fixtures—then expand by evidence across UI, security, repository shapes, versions, add-ons, and data stores.

## Product Contract

The compatibility registry is both the support statement and the authorization system:

```text
IDE lane × Jmix version × JDK × source language × Gradle DSL
× build topology × add-ons × data-store shape × artifact customization
× requested operation
→ certified read/write | certified read-only | recognized diagnostic | unsupported
```

| Compatibility state | User value | Backend policy |
|---|---|---|
| Certified read/write | Inspect, plan, validate, preview, apply, undo, and reproduce the named operation | Enable only the certified operation through the central change engine |
| Certified read-only | Trusted semantic inventory, navigation, impact analysis, and diagnostics | Do not expose Apply |
| Recognized but uncertified | Best-effort inventory, explicit uncertainty, compatibility report, and non-applicable plan export | Reject all writes even if the UI is manipulated |
| Legacy diagnostic | Identify older Jmix/CUBA shapes and provide migration-oriented diagnostics | Isolate from normal editors; no guessed adapter or migration execution |
| Unknown/ambiguous/stale/untrusted | Explain the missing evidence and retain generic navigation where safe | No project code execution, database access, external process, or mutation |

Certification is operation-specific. A project may allow `entity.addAttribute` while keeping `gradle.addDependency` or mutation of one customized XML descriptor read-only. New Jmix and IDE versions enter recognized/read-only status first and are promoted only by a reviewed adapter/manifest change with immutable fixture evidence.

## Key Findings

### Recommended Stack

The recommended stack is a Kotlin ports-and-adapters modular monolith in the IntelliJ process, with shared domain/protocol/adapter modules and thin host-specific distributions. It must inspect target Jmix dependencies rather than embedding Jmix, Spring, Vaadin, EclipseLink, or Liquibase runtimes in the plugin. See [STACK.md](./STACK.md) for exact versions and alternatives.

**Core technologies:**

- **IntelliJ Platform Gradle Plugin 2.18.0 + Gradle 9.6.1:** reproducible build, testing, verification, signing, and publication for both host lanes.
- **Two host artifacts:** `idea253` for IDEA 2025.3–2026.1 on Java 21/Kotlin 2.2.20 API floor, and `idea262` for IDEA 2026.2 on Java 25/Kotlin 2.4.0 with the explicit JCEF module. Do not claim one universal binary until evidence proves it.
- **Kotlin backend:** immutable domain models, use cases, compatibility decisions, change planning, validation, and protocol handling; use the IDE-bundled Kotlin runtime/coroutines.
- **Workspace Model, `ProjectFileIndex`, and imported Gradle model:** authoritative build/module/source-root/topology evidence without silently evaluating Gradle scripts.
- **UAST plus Java PSI and Kotlin PSI/Analysis API:** UAST for normalized reads only; physical language PSI for minimal semantic writes.
- **XML PSI/DOM, Properties PSI, and DSL-specific Gradle PSI:** preservation-first structured editing of Jmix descriptors, messages, changelogs, and build metadata.
- **React 19.2, TypeScript 6, Vite 8.1, Node 24 LTS:** visual canvases and plan/diff experiences packaged as static JCEF resources. Native IntelliJ UI remains preferred for simple forms, settings, notifications, and accessibility-critical interactions.
- **JSON Schema 2020-12, Kotlin serialization, and Zod 4:** one versioned, generated, runtime-validated bridge contract with request correlation, negotiation, limits, cancellation, and structured errors.
- **JUnit 5, IntelliJ platform tests, Starter/Driver, Vitest, React Testing Library, Playwright, Plugin Verifier:** tests from pure domain logic through installed-plugin/JCEF behavior.
- **JetBrains signing, CycloneDX, checksums, and provenance attestations:** an immutable, verifiable release bundle covering both JVM and bundled frontend dependencies.

Critical version rules:

- Jmix 2.8 certification begins with exact tested patches such as 2.8.0 and 2.8.2, Java 17/21, and each fixture's Gradle wrapper (the official migration baseline is 8.14.4).
- Jmix 3.0 certification begins with 3.0.0, Java 21/25, Groovy and Kotlin DSL, and the fixture's official Gradle 9.5.1 baseline.
- Later patches/minors and earlier 2.x/1.x/CUBA estates remain recognized/read-only until separately certified; version-range inference never unlocks mutation.

### Expected Features

The feature strategy is a trustworthy vertical slice for existing repositories. Create-only scaffolding is insufficient because enterprise adoption depends on loading, preserving, and safely evolving years of hand-edited source. See [FEATURES.md](./FEATURES.md) for detailed acceptance scenarios.

**Must have — safety and read-side table stakes:**

- Supported-project discovery across single-module, multi-project, composite/included-build, Java, Kotlin, mixed-language, add-on-heavy, multi-store, customized, and upgraded repositories.
- Incremental semantic index of entities, DTOs, enums, views/controllers, fetch plans, menus, roles, messages, repositories, changelogs, module ownership, and add-on provenance.
- Backend-evaluated version/capability adapters with reasons for every enabled, read-only, blocked, or unsupported operation.
- Open/index/browse behavior with zero repository, build, network, database, or VCS mutation.
- Native navigation, file/line diagnostics, honest smart/dumb-mode behavior, cancellation, accessibility, and measured large-project responsiveness.

**Must have — mutation table stakes:**

- Typed/correlated bridge commands with a controlled origin, schema/size limits, timeouts, cancellation, and trusted-project enforcement.
- Deterministic plan, semantic summary, exact text diff, plan fingerprint, validation results, and explicit confirmation before Apply.
- Path/ownership containment, stale-plan and conflict detection, one-command atomic apply, failure restoration, crash journal, and IDE Undo/Redo.
- Parse/schema/reference/cross-artifact validation before apply and reparsing/compile/integration checks after.
- Java and Kotlin entity/DTO/enum round trip, coordinated additive Liquibase, list/detail view creation and existing view/controller round trip, fetch plans, owned menus, localization, and resource roles.
- Deterministic headless validation matching IDE diagnostics, with every public support claim generated from the same compatibility manifest used by the backend and CI.

**Should have — differentiators:**

- **Plan-first safety center:** explain source, database, view, localization, menu, and security consequences before a change.
- **No-unrelated-diff guarantee:** make visual editing credible in hand-maintained repositories.
- **Compatibility explanation:** expose evidence, limitations, and exact reasons for degradation instead of silently hiding controls.
- **Artifact impact graph:** navigate incoming/outgoing relationships and ownership across Jmix artifacts.
- **Reproducible plan export:** allow team review, CI revalidation, failure reproduction, and auditable change management without leaking source or secrets.
- **Broad read-only onboarding:** deliver immediate value on legacy/uncertified solutions while certified write breadth grows.

**Defer to v2+ or separately gated phases:**

- Cross-version project migration, full database reverse engineering, runtime-backed preview/hot deploy, and any database execution.
- Row-level predicate/JPQL authoring, full JPQL console, repository/update-service designers, OpenAPI orchestration, and broad project/add-on creation.
- Project-wide ER canvas, database drift explorer, policy simulation, team policy packs, and add-on extension SDK until core contracts survive two adapters and two vertical slices.
- BPMN/DMN runtime claims, autonomous AI writes, cloud deployment, default telemetry, or any feature that bypasses the proven change engine.

**Permanent anti-features:** pixel-identical Studio cloning, proprietary assets/code/templates, license bypass, whole-file regeneration of existing artifacts, React as source of truth, generic best-effort version generation, inferred destructive schema changes, and silent file/build/database mutation.

### Architecture Approach

Use a source-authoritative CQRS-like read/write separation inside one plugin process. The read side incrementally builds immutable, revisioned semantic snapshots from topology, cheap candidate indexes, and smart-mode resolvers. The write side accepts typed intents only after a current backend capability decision, turns them into an immutable `ChangePlan`, stages exact post-images, runs deterministic validation and diff, and gives the confirmed digest to one `ApplyCoordinator`. React never receives PSI objects or ambient filesystem authority. See [ARCHITECTURE.md](./ARCHITECTURE.md) for the complete design.

**Major components:**

1. **Controlled JCEF host and protocol gateway** — serves packaged assets, enforces origin/CSP/navigation, validates bounded messages, correlates/cancels requests, and dispatches only allowlisted use cases.
2. **Workspace topology service** — represents build-tree-qualified builds, included builds, modules, source sets, add-on roles, module dependency graphs, data stores, and imported-model health.
3. **Candidate indexes and semantic resolver** — locate artifacts cheaply, then resolve only changed candidates/dependents through PSI/UAST/XML/properties and adapter metadata.
4. **Immutable project snapshot service** — publishes value descriptors, reference graphs, diagnostics, fingerprints, capability decisions, and bounded deltas rather than retaining live PSI or a shadow database.
5. **Compatibility registry and Jmix adapters** — recognize 2.8, 3.x, legacy, and unknown profiles; own version-specific schemas/catalogs/recipes; authorize feature-level operations using signed fixture evidence.
6. **Artifact readers/editors** — separate Java, Kotlin, XML, properties, Gradle, TOML, and Liquibase semantic mutation ports that preserve unknown content and block ambiguous anchors.
7. **Change engine** — plans, stages, validates, diffs, journals, applies, verifies, rolls back, and reindexes every mutation; no generator or extension writes directly.
8. **Diagnostics/audit/headless validation** — stable codes, redacted local records, support bundles, CI-equivalent rules, and certification evidence.
9. **Compatibility laboratory** — pinned, provenance-reviewed fixtures and installed-plugin scenarios that turn claimed operations into reproducible evidence.

Key patterns:

- Functional core with an IntelliJ shell.
- Immutable snapshots and deltas instead of global mutable models.
- UAST for cross-language reads; language-specific physical PSI for writes.
- Preservation-first XML/properties editing and conservative DSL-specific Gradle edits.
- Capabilities supplied by the backend, never inferred in React.
- Deterministic outputs and plan digests tied to snapshot, adapter, topology, and code-style evidence.
- One controlled write capability, declarative extension outputs, and no generic RPC or local web server.

### Compatibility and Fixture Strategy

The compatibility laboratory is a product subsystem, not only a test folder. Every certified manifest row links to an immutable fixture commit, exact environment/profile metadata, named workflows, CI provenance, limitations, and the plugin version that earned the claim.

**Minimum fixture axes:**

- **Framework:** Jmix 2.8.0, current 2.8 patch, Jmix 3.0.0, upgraded 2.7→2.8 and 2.8→3.0, earlier/legacy negative-recognition samples, and conflict/unresolved-version samples.
- **Host:** IDEA 2025.3/JBR 21 and IDEA 2026.2/JBR 25 initially, then every advertised branch and optional dependency/edition combination.
- **Language/build:** Java-only, Kotlin-only, mixed; Groovy DSL, Kotlin DSL, version catalogs, `buildSrc`, and convention-plugin ownership.
- **Topology:** single-module, multi-project, composite/included builds, nested builds, multiple repositories, and add-on functional/starter pairs.
- **Dependencies/data:** core-only, official/third-party/internal/private/unavailable add-ons; main/multiple/cross stores; supported database dialects; offline and stale/failed import modes.
- **History/customization:** fresh, upgraded, partially migrated, hand-formatted, unknown XML/annotations/components, manual Liquibase, custom source sets, generated roots, line-ending/encoding/OS variants, and read-only/VCS files.
- **Adversarial:** malformed files, duplicate IDs, traversal and symlink cases, stale previews, concurrent edits, cancellation, dumb mode, browser reload, partial-write/crash injection, and untrusted projects.

**Every write cell must prove:**

1. Opening, recognition, and indexing are non-mutating.
2. Parse → semantic model → no-op serialization is byte-identical.
3. A golden change produces only the owned minimal diff and preserves unsupported/manual content.
4. All staged/post-apply artifacts reparse and cross-artifact references remain valid.
5. The fixture compiles with its own wrapper/toolchain and passes focused Jmix/Liquibase integration checks.
6. Reapplying is idempotent; one Undo restores the exact preimage and Redo restores the exact result.
7. Stale/concurrent changes block; cancellation and every injected apply failure leave exact original state.
8. Installed plugin ZIPs pass real-IDE/JCEF flows and Plugin Verifier on all advertised hosts.
9. Performance, privacy/redaction, and reproducibility gates pass for the declared profile.

**Promotion rule:** recognized/read-only → certified read/write only through reviewed adapter plus manifest changes after all relevant matrix cells pass. A regression removes or downgrades the capability; documentation is generated from the registry so marketing cannot outrun evidence. Sanitized customer repositories should expand the lab, but only with permission, anonymization, provenance, and license review.

### Critical Pitfalls

See [PITFALLS.md](./PITFALLS.md) for the full risk register and gates.

1. **Mistaking recognition for safe mutation** — enforce feature-level capability decisions in Kotlin; ambiguous, legacy, stale, untrusted, or customized states remain read-only.
2. **Retaining stale PSI/index state** — publish immutable snapshots, keep stable IDs/fingerprints, re-resolve in cancellable smart reads, and block apply during dumb mode or topology drift.
3. **Confusing a write action with a transaction** — stage all work off the EDT, recheck every precondition, apply one minimal command, retain restoration preimages, fault-inject every boundary, and prove exact Undo/Redo.
4. **Giving JCEF ambient authority** — use one controlled origin, CSP/navigation restrictions, allowlisted typed commands, payload limits, project-trust gates, root-derived targets, canonical containment, and symlink/race tests.
5. **Lossy textual regeneration** — use artifact-specific semantic edits, preserve unknown content/provenance, require byte-identical no-op round trips, and block uncertain anchors instead of normalizing whole files.
6. **Flattening the real Gradle/Jmix graph** — consume imported Workspace/Gradle evidence, keep included-build/module/store identities distinct, and never fall back to root regexes or hard-coded source paths.
7. **Producing valid syntax with wrong Jmix/Liquibase semantics** — use adapter-owned schemas/catalogs, full changelog/store graphs, cross-artifact validation, target-toolchain tests, and explicit destructive intent.
8. **Claiming broad 2.8/3.x support from a few templates** — certify exact operation/profile tuples; treat every Jmix/IDE/add-on change as a compatibility event.
9. **Testing generators instead of the installed product** — combine units, PSI tests, enterprise fixtures, databases, installed-ZIP flows, Plugin Verifier, failure injection, and clean release builds.
10. **Ignoring enterprise scale, reproducibility, or trust** — incremental indexing, bounded payloads/caches, measurable budgets, deterministic IDE/CI plans, redacted audit, locked dependencies, SBOM, provenance, and signed artifacts are release gates.
11. **Violating the clean-room/brand boundary** — adopt an original identity, public-source provenance, contribution controls, trademark disclaimer, license review, and no proprietary Studio material.

## Implications for Roadmap

The roadmap should optimize for safe adoption by existing enterprises: ship broad, useful read-only understanding early; earn a narrow set of mutation permissions; then widen certification by workflow and repository profile. Feature breadth without certification evidence is not progress toward the product goal.

### Phase 1: Clean-Room Product and Dual-Lane Build Foundation

**Rationale:** The current prototype does not produce a trustworthy plugin artifact, and public distribution cannot proceed while identity, legal provenance, IDE compatibility, and release integrity are unresolved.  
**Delivers:** Original product/package/asset identity; license, disclaimer, contribution and provenance policies; complete Gradle wrapper; `idea253` and `idea262` build lanes; bundled frontend build dependency; plugin load smoke test; Plugin Verifier; locked dependencies; initial SBOM/sign/checksum/provenance pipeline.  
**Addresses:** Reproducible installable build, clean-room implementation, honest IDE/edition capability surface.  
**Avoids:** Stale frontend bundles, one-binary assumptions, optional dependency load failures, unsigned/non-reproducible releases, and brand/IP confusion.  
**Exit gate:** G0 clean-room identity plus basic G6/G8 build and artifact integrity.

### Phase 2: Compatibility Laboratory and Worldwide Read-Only Onboarding

**Rationale:** Existing repositories must be understood before any editor exists. Broad diagnostic/read-only support is the first useful worldwide product and the evidence base for every later write claim.  
**Delivers:** Fixture governance; build-tree/topology model; Jmix/IDE/JDK/DSL/add-on/store recognition; 2.8, 3.x, legacy, and unknown adapters; two-stage semantic index; immutable snapshots/deltas; reference/provenance graph; signed compatibility-manifest schema; compatibility report and native navigation with zero mutation.  
**Addresses:** Supported-project discovery, semantic index, capability adapters, open-first behavior, native diagnostics, artifact impact foundations.  
**Avoids:** Root-regex discovery, flattened composite builds, stale PSI, permissive version matching, silent Gradle execution, and guessed defaults.  
**Exit gate:** G1/G2 across certified candidates and uncertified/legacy/negative fixtures.

### Phase 3: Typed Privilege Boundary and Universal Change Engine

**Rationale:** No domain designer should invent its own bridge, generator, validation, or write path. Prove one reusable safety kernel before exposing real source mutations.  
**Delivers:** Generated JSON Schema contracts; controlled JCEF origin/CSP/navigation and native fallback; request correlation/limits/cancellation; capability tokens; immutable plans; staging; semantic/platform diff; tiered validation; plan digest/freshness; path/ownership containment; one-command ApplyCoordinator; fault rollback; recovery journal; Undo/Redo; redacted audit; a harmless properties-based end-to-end proof.  
**Addresses:** Typed commands, plan/diff, validation, atomic apply, stale conflict detection, path safety, plan export foundation.  
**Avoids:** JCEF ambient authority, UI-only gating, direct file I/O, partial writes, nondeterministic preview/apply, and unsafe paths.  
**Exit gate:** G3/G4 including adversarial protocol/path tests and exhaustive apply fault injection.

### Phase 4: Certified Entity and Data Vertical Slice

**Rationale:** Entity metadata anchors the highest-value cross-artifact workflow and exercises Java/Kotlin PSI, localization, data-store ownership, and Liquibase together. It is the correct first earned write capability.  
**Delivers:** Existing Java/Kotlin entity, DTO, and enum reads; bounded attribute/association/ID/validation/index/trait mutations; message coordination; additive Liquibase planning and includes; Jmix 2.8 and 3.0 adapter-specific outputs; compile/integration/database validation; first published read/write cells.  
**Addresses:** Entity/DTO/enum round trip, additive schema evolution, localization, compatibility explanation, no-unrelated-diff guarantee.  
**Avoids:** UAST writes, whole-file regeneration, inferred rename/drop, wrong store/dialect, historical changeset modification, and false cross-version equivalence.  
**Exit gate:** G5/G6 for exact initial fixture tuples, including byte-identical no-op, idempotence, rollback, Undo, and compile/start.

### Phase 5: Existing CRUD UI Round Trip

**Rationale:** Once entity metadata and the change engine are dependable, complete the professional Jmix workflow instead of expanding into unrelated generators.  
**Delivers:** Bounded list/detail view creation; existing descriptor/controller round trip; fetch plans; owned menu edits with inherited/add-on provenance; localization coordination; native navigation between artifacts; version-specific component catalogs and explanations.  
**Addresses:** View list/detail vertical slice, existing view round trip, fetch-plan and menu designers, CRUD impact graph.  
**Avoids:** Flattening unknown XML/add-on nodes, treating schematic React preview as runtime WYSIWYG, duplicate IDs/references, or editing inherited artifacts as local.  
**Exit gate:** Per-artifact G5/G6 plus complete existing-CRUD enhancement scenarios on both initial Jmix lines.

### Phase 6: Security, Team Reproducibility, and Headless Quality

**Rationale:** A CRUD workflow is not enterprise-complete without permissions, reproducible CI evidence, ownership, and diagnosable operations.  
**Delivers:** Resource-role designer with inherited/default-deny provenance; cross-artifact security validation; deterministic headless validation; reproducible/redacted plan export; compatibility tables generated from the registry; local audit/support bundle; IDE/CI equivalence; accessibility and keyboard completion for released workflows.  
**Addresses:** Resource roles, native diagnostics, reproducible plan export, plan-first safety center, team review and CI usage.  
**Avoids:** Misrepresented effective permissions, workstation-dependent output, opaque policy overrides, documentation drift, and source/secret leakage.  
**Exit gate:** G9 for deterministic plans, IDE/CI parity, audit redaction, and secured-navigation integration scenarios.

### Phase 7: Enterprise Certification Expansion and Scale

**Rationale:** Worldwide leadership comes from coverage of actual estates, not a large feature menu tested on samples. Expand the matrix horizontally only after the first two write slices are proven.  
**Delivers:** Additional exact Jmix patch/profile certification; mixed-language and DSL breadth; composite/multi-repository/add-on-heavy/multi-store/customized/upgraded fixtures; unsupported-artifact preservation; offline/private dependency diagnostics; incremental performance and leak work; OS/filesystem matrix; published latency/memory/payload budgets; pilot program with licensed/anonymized enterprise corpora.  
**Addresses:** Large-project responsiveness, compatibility breadth, enterprise diagnostics, release-grade fixture matrix.  
**Avoids:** Blanket version claims, full rescans/rerenders, small-fixture bias, private add-on guessing, case/symlink/long-path failures, and compatibility claims without evidence.  
**Exit gate:** G7 plus complete G6 evidence for every newly advertised cell.

### Phase 8: Trusted Ecosystem and Advanced Workflows

**Rationale:** Extensions and advanced capabilities are leverage only after two built-in adapters and complete entity/UI slices have stabilized the contracts.  
**Delivers:** Capability-scoped add-on SDK/catalog contributions; team policy packs; policy impact simulation; read-only ER/database drift views; separately researched database introspection and migration assistance; controlled public/private update channels; global documentation, onboarding, and migration guidance.  
**Addresses:** Add-on extensibility, organization governance, advanced analysis, legacy migration assistance, and international enterprise adoption.  
**Avoids:** Third-party write bypass, default database/network access, unsupported runtime claims, and expansion beyond the central capability/change-plan model.  
**Exit gate:** Each advanced workflow receives its own threat model, adapter/fixture matrix, and explicit consent boundary before any write/external effect.

### Phase Ordering Rationale

- The build/legal/release baseline comes first because every later compatibility result must correspond to an installable, original, verifiable artifact.
- Topology, recognition, and immutable semantic reads precede designers because wrong ownership or version evidence invalidates every edit.
- Broad read-only diagnostics precede narrow writes, creating immediate value for long-lived estates without converting uncertainty into repository risk.
- The protocol and change engine are a single reusable safety dependency for all feature phases; no feature may ship a side write path.
- Entity + messages + Liquibase is the first complete vertical slice because it tests the hardest semantic/data coordination while unlocking downstream views.
- Views, controllers, fetch plans, menus, and localization belong together because their references and ownership must be validated as one CRUD outcome.
- Resource security, headless validation, audit, and plan export complete the team workflow before compatibility breadth and ecosystem expansion.
- Certification expands in two dimensions independently: new operations on already proven profiles, then existing operations on new repository profiles. Never widen both simultaneously without isolating evidence.

### Research Flags

**Phases requiring deeper phase research or technical spikes:**

- **Phase 2:** public imported-Gradle API surface across IDEA 2025.3–2026.2, certification granularity, legacy-recognition boundaries, fixture licensing/anonymization, optional edition/plugin degradation.
- **Phase 3:** packaged custom JCEF scheme/CSP behavior, non-physical staging versus physical-apply hash equivalence, mixed document/VFS crash recovery, canonical/symlink path handling across operating systems.
- **Phase 4:** Kotlin K2 mutation APIs across both IDE lanes, exact Jmix 2.8/3.0 recipes, properties preservation, Liquibase formats/dialects and target-toolchain validation.
- **Phase 5:** authoritative Jmix/add-on XML schemas and component catalogs, unknown-node preservation, controller/descriptor linking, actual preview terminology and accessibility interaction model.
- **Phase 6:** effective resource-role semantics, enterprise plan/audit retention, redaction, and policy-override governance.
- **Phase 7:** representative corpus acquisition, measurable performance budgets, remote-development behavior, network/case/encoding/filesystem variants, and prioritized patch/profile coverage from pilot demand.
- **Phase 8:** public extension API threat boundaries, private add-on metadata, database credential/introspection threat model, migration scope, update-channel trust, and global support/localization strategy.

**Phases with well-documented patterns that can skip broad ecosystem research after focused spikes:**

- **Phase 1:** IntelliJ build, Plugin Verifier, signing, dependency locking, SBOM, and provenance have strong official guidance.
- **Phase 3 core protocol:** JSON Schema validation, correlation, cancellation, CSP, and allowlisted dispatch are established patterns; research should focus only on IntelliJ/JCEF integration details.
- **Phase 6 headless validation and release-generated documentation:** deterministic CLI validation and manifest-derived documentation are standard once domain contracts exist.

## Open Questions for Requirements and Planning

1. What exact operation/profile cells constitute the first public certified read/write promise? Recommended starting point: entity reads plus a small additive entity/message/Liquibase mutation subset on Jmix 2.8.0/2.8.2 and 3.0.0 fixtures, not blanket version claims.
2. Which real organizations, industries, regions, and repository shapes define “worldwide go-to,” and how will licensed/anonymized fixtures and pilot interviews be obtained without compromising source or credentials?
3. Which IntelliJ editions and optional Kotlin/Gradle/Database/Spring capabilities are mandatory, and what exact native/JCEF/read-only fallback is promised for each?
4. Can stable public imported-Gradle APIs cover the full 2025.3–2026.2 range, or must host-specific adapters isolate internal/API drift?
5. Which Kotlin PSI/Analysis API mutation subset is stable enough to certify across both host lanes?
6. Which Jmix/add-on XML schemas and component catalogs can be bundled from public sources versus resolved from the target classpath?
7. Which Liquibase formats and database dialects are writable in v1? Recommended default: XML and a deliberately small dialect matrix; YAML/JSON/formatted SQL remain read-only until certified.
8. What is the supported behavior for custom convention plugins and version catalogs when ownership or insertion points cannot be proven? Recommended permanent fallback: navigation/proposed patch only, never best-effort Apply.
9. What quantitative startup, indexing, incremental-refresh, plan, EDT write-lock, heap, and bridge budgets will the enterprise fixture corpus enforce?
10. How durable can mixed PSI/document/VFS crash recovery be made, and what exact recovery claim can the product honestly publish?
11. Is remote development in v1? Recommended answer: no claim until backend/frontend placement and filesystem authority are researched and tested.
12. What original product name, trademark disclaimer, license, contribution policy, and clean-room evidence process will pass legal/Marketplace review?
13. Is change-plan export only a review artifact in v1 or a future checked-in automation format? Recommended v1: immutable redacted review/reproduction artifact, not a second source of truth.
14. Which advanced workflows have sufficient demand to follow CRUD: repositories/update services, database drift, migration, OpenAPI, policy simulation, or extension SDK?

## Confidence Assessment

| Area | Confidence | Notes |
|---|---|---|
| Stack | HIGH | Host/runtime split, IntelliJ Gradle plugin, PSI/UAST boundaries, Jmix Java/Gradle baselines, testing, signing, and SBOM guidance come from official sources. Exact frontend/test patch pins and cross-lane serialization packaging remain implementation checks. |
| Features | HIGH on table stakes; MEDIUM on prioritization | Official Jmix/IntelliJ workflows establish expected feature families. V1 ordering and enterprise differentiators are strongly reasoned but need customer/pilot validation. |
| Architecture | HIGH on boundaries; MEDIUM on difficult adapters | Modular monolith, immutable snapshots, central change engine, IntelliJ threading/write rules, and backend capability enforcement are well supported. Kotlin mutation, Gradle semantic edits, staging equivalence, and crash recovery need spikes. |
| Pitfalls | HIGH | Critical failure modes and platform mechanics are documented by IntelliJ, Gradle, Jmix, Liquibase, and supply-chain standards. Organizational controls and numerical performance targets need empirical validation. |
| Enterprise compatibility breadth | MEDIUM | The required axes and safe degradation policy are clear, but no fixture corpus or certification evidence exists yet. |
| Legacy recognition | LOW-MEDIUM | Safety policy is strong; reliable version-by-version recognition of Jmix 1.x, early 2.x, CUBA, forks, and private add-ons requires authorized samples. |

**Overall confidence:** HIGH in the recommended product/architecture direction; MEDIUM in release scope until the compatibility laboratory proves the first certified cells.

### Gaps to Address

- **No certified evidence exists yet:** treat all current mutation paths as unsafe prototypes until the fixture laboratory and manifest gates are implemented.
- **Enterprise corpus access:** secure permissioned, sanitized, provenance-recorded fixtures and pilot partners before claiming worldwide compatibility.
- **Exact initial matrix:** freeze narrow versions, IDE builds, languages, DSLs, topologies, artifact operations, and dialects during Phase 2 requirements.
- **K2 and Gradle API stability:** isolate behind host/language adapters and spike before setting long-lived compatibility ranges.
- **Unknown/private add-ons:** preserve and diagnose by default; require an authorized catalog/adapter and fixtures for mutation.
- **Performance targets:** establish baselines on representative repositories before committing public budgets.
- **Crash-recovery semantics:** document operation-failure/Undo guarantees separately from best-effort process-crash recovery.
- **Legal/brand review:** complete before public naming, screenshots, Marketplace copy, or external contribution intake.
- **Remote development and nonstandard filesystems:** explicitly exclude until separate certification exists.

## Sources

The detailed source lists and confidence annotations are maintained in [STACK.md](./STACK.md), [FEATURES.md](./FEATURES.md), [ARCHITECTURE.md](./ARCHITECTURE.md), and [PITFALLS.md](./PITFALLS.md). The product constraints and success criteria come from [PROJECT.md](../PROJECT.md).

### Primary — Official/High Confidence

- [Jmix Studio feature catalog](https://docs.jmix.io/jmix/studio/studio-features.html) and [Jmix 2.8 catalog](https://docs.jmix.io/jmix/2.8/studio/studio-features.html) — expected artifact and workflow families.
- [Jmix 3.0 release and migration changes](https://docs.jmix.io/jmix/whats-new/release-3.0.html) and [Jmix 2.8 What's New](https://docs.jmix.io/jmix/2.8/whats-new/index.html) — IDE, JDK, Gradle, framework, source, XML, and workflow boundaries.
- [Jmix composite projects](https://docs.jmix.io/3.x/jmix/studio/composite-projects.html), [add-on structure](https://docs.jmix.io/jmix/modularity/creating-add-ons.html), and [data stores](https://docs.jmix.io/3.x/jmix/data-model/data-stores.html) — enterprise topology and ownership axes.
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), [build ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html), and [plugin compatibility verification](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html) — host build and release gates.
- [Workspace Model](https://plugins.jetbrains.com/docs/intellij/workspace-model.html), [PSI modification](https://plugins.jetbrains.com/docs/intellij/modifying-psi.html), [UAST](https://plugins.jetbrains.com/docs/intellij/uast.html), [documents/commands](https://plugins.jetbrains.com/docs/intellij/documents.html), and [threading](https://plugins.jetbrains.com/docs/intellij/threading-model.html) — semantic model and safe mutation rules.
- [Embedded Browser/JCEF](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html), [Trusted Projects](https://plugins.jetbrains.com/docs/intellij/trusted-projects.html), and [Disposer](https://plugins.jetbrains.com/docs/intellij/disposers.html) — privilege boundary and lifecycle.
- [Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html), [composite builds](https://docs.gradle.org/current/userguide/composite_builds.html), and [dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html) — imported-model, topology, and supply-chain behavior.
- [Liquibase validation](https://docs.liquibase.com/community/reference-guide-5-1/database-inspection-change-tracking-and-utility-commands/validate), [changeset checksums](https://docs.liquibase.com/community/user-guide-5-0-3/what-is-a-changeset-checksum), and [preconditions](https://docs.liquibase.com/community/user-guide-5-0-2/what-are-preconditions) — changelog safety and validation limits.
- [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12), [CycloneDX](https://github.com/CycloneDX/specification), and [SLSA provenance](https://slsa.dev/spec/v1.2/) — protocol and release evidence standards.

### Secondary — Medium Confidence or Validation Needed

- Project-local prototype and codebase assessment — strong evidence of current gaps, but not evidence of production compatibility.
- Enterprise prioritization, policy, audit, and rollout recommendations — synthesis of established engineering practice; validate with target-team interviews and pilots.
- Numerical performance budgets and initial certification breadth — starting targets only; replace with measured corpus evidence.

---
*Research completed: 2026-07-27*  
*Ready for roadmap: yes*

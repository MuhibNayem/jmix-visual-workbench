# Roadmap: Jmix Visual Development Workbench

## Overview

This roadmap turns the current prototype into a source-first enterprise workbench by making a reproducible clean-room plugin buildable first, then delivering broad non-mutating understanding of existing repositories before any project write is possible. Mutation is earned through one typed, validated, atomic change engine and narrow fixture-certified entity/data and CRUD slices. Team workflows, enterprise-scale certification, and trusted worldwide release follow only after those contracts hold. Source remains authoritative throughout: compatibility is authorized per operation/profile cell, and uncertified, legacy, ambiguous, stale, or untrusted repositories remain useful but read-only.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Clean-Room Build Foundation** - Produce original, reproducible, installable plugin artifacts suitable for immediate implementation and verification.
- [ ] **Phase 2: Compatibility Laboratory and Read-Only Onboarding** - Discover and explain real repositories without mutation, while authorizing capabilities only from exact fixture evidence.
- [ ] **Phase 3: Typed Privilege Boundary and Change Engine** - Make every proposed write bounded, previewable, validated, atomic, recoverable, and centrally enforced.
- [ ] **Phase 4: Certified Entity and Data Round Trip** - Earn the first narrow write capability for existing Java/Kotlin data models, localization, and additive Liquibase evolution.
- [ ] **Phase 5: Certified Existing CRUD Round Trip** - Complete source-preserving list/detail view, controller, fetch-plan, menu, and cross-artifact CRUD workflows.
- [ ] **Phase 6: Security and Reproducible Team Workflow** - Add source-aware resource-role editing, IDE/CI parity, accessible workflows, and redacted review/support artifacts.
- [ ] **Phase 7: Enterprise Certification and Scale** - Expand certified profiles only through representative fixtures, installed-product evidence, regression downgrades, and measured scale.
- [ ] **Phase 8: Trusted Worldwide Release** - Publish verified immutable artifacts and versioned guidance whose compatibility claims cannot outrun tested evidence.

## Phase Details

### Phase 1: Clean-Room Build Foundation
**Goal**: Contributors can build and developers can install an original, reproducible plugin foundation on each initial IntelliJ lane.
**Depends on**: Nothing (first phase)
**Requirements**: PROD-01, PROD-02, PROD-03, PROD-04, PROD-05, PROD-06
**Success Criteria** (what must be TRUE):
  1. A contributor or CI worker can clone the repository and build every initial plugin lane through checked-in wrappers; a pinned project-local Node distribution is provisioned automatically when no global Node exists.
  2. Each produced ZIP contains the production web bundle from the same source revision, a stale or missing bundle fails the build, and installed plugins require no Node runtime.
  3. A developer can install each ZIP into its advertised IntelliJ lane and open the workbench without class-loading, JCEF, or missing-resource errors.
  4. The installed product and repository use an original identity, license, trademark disclaimer, and contribution/provenance rules, while build dependencies are pinned and integrity-verified.
**Plans**: 5/5 plans executed

### Phase 2: Compatibility Laboratory and Read-Only Onboarding
**Goal**: Developers can safely understand and navigate existing Jmix estates before the workbench is allowed to mutate any repository.
**Depends on**: Phase 1
**Requirements**: COMP-01, COMP-02, COMP-03, COMP-06, COMP-07, DISC-01, DISC-02, DISC-03, DISC-04, DISC-05, DISC-06, DISC-07, DISC-08, DISC-09, DISC-10, TEAM-07
**Success Criteria** (what must be TRUE):
  1. Opening, importing, indexing, browsing, and closing every fixture leaves source, configuration, build, VCS, database, and network state unchanged, while showing the exact detected project profile.
  2. Developers can inspect real build/module/source-root topology, languages, add-ons, data stores, migration roots, import health, and unresolved dependencies without executing arbitrary project code or opening a database connection.
  3. Developers can browse a cancellable, incremental semantic inventory with stable identity, ownership, provenance, fingerprints, diagnostics, relationships, smart/dumb-mode behavior, and native file-and-line navigation.
  4. Every operation receives a backend compatibility state and reason; write capability is absent unless the exact operation/profile cell has reviewed passing evidence, and uncertified, legacy, ambiguous, stale, untrusted, or future projects remain diagnostic/read-only.
  5. When an option is unavailable, the workbench explains the missing evidence and identifies the tested profile or isolated migration path that could make it available.
**Plans**: 18 plans
**UI hint**: yes

Plans:
- [ ] 02-01-PLAN.md — Establish the IntelliJ-free discovery model, safe locators, and deterministic serialization lane.
- [ ] 02-02-PLAN.md — Implement the pure deny-by-default registry and static Gradle/profile parser.
- [ ] 02-03-PLAN.md — Define enforceable discovery effects and intentional zero-mutation tripwires.
- [ ] 02-04-PLAN.md — Add transitive forbidden-effect scans and the pure-first dual-host fast gate.
- [ ] 02-05-PLAN.md — Collect imported build/module/root/dependency facts through exact 253/262 adapters.
- [ ] 02-06-PLAN.md — Parse bounded static build/config/store/Liquibase evidence without execution or connections.
- [ ] 02-07-PLAN.md — Inventory semantic artifacts and assemble typed relationship provenance with safe locators.
- [ ] 02-08-PLAN.md — Orchestrate cancellable smart/dumb incremental snapshots, cache, and invalidation.
- [ ] 02-09-PLAN.md — Register revision-bound native navigation and lock all shared/host descriptors.
- [ ] 02-10-PLAN.md — Expose bounded redacted read-only bridge pages and projection-time navigation handles.
- [ ] 02-11-PLAN.md — Build TypeScript contracts, bridge/store state, and the web test harness.
- [ ] 02-12-PLAN.md — Implement overview, compatibility, diagnostics, and every durable UI state.
- [ ] 02-13-PLAN.md — Implement the responsive shell, structure/inventory/relationships, and locked designers.
- [ ] 02-14-PLAN.md — Complete the bounded deterministic fixture laboratory and exact expected outputs.
- [ ] 02-15-PLAN.md — Run clean non-circular automated pre-UAT lifecycle certification.
- [ ] 02-16-PLAN.md — Verify the full installed signed-host UI and zero-mutation matrix.
- [ ] 02-17-PLAN.md — Validate installed evidence and run definitive clean Phase 2 certification.
- [ ] 02-18-PLAN.md — Wire the live dual-host tool-window composition root, UI request bindings, and installed-style lifecycle.

### Phase 3: Typed Privilege Boundary and Change Engine
**Goal**: Every project mutation passes through one deterministic, least-privilege plan, validation, apply, and recovery pipeline.
**Depends on**: Phase 2
**Requirements**: SAFE-01, SAFE-02, SAFE-03, SAFE-04, SAFE-05, SAFE-06, SAFE-07, SAFE-08, SAFE-09, SAFE-10, SAFE-11, SAFE-13
**Success Criteria** (what must be TRUE):
  1. The packaged workbench uses a controlled origin and trust gate, and every bounded, correlated, cancellable bridge request is versioned, typed, allowlisted, schema-validated, and answered with a structured result.
  2. Every mutating intent first presents an immutable plan with semantic operations, exact affected files and classifications, before/after diff, diagnostics, dependencies, and a stable digest.
  3. Apply remains unavailable until paths, ownership, compatibility, snapshot freshness, fingerprints, writability, syntax, schemas, references, adapter rules, and policy evidence all pass; drift or unsafe targets produce actionable conflicts.
  4. Supported edits preserve manual and unknown content, formatting, encoding, and line endings; uncertain anchors block, and no generator, designer, bridge handler, extension, or service can bypass the central coordinator.
  5. A successful multi-file plan is one IDE command with exact Undo/Redo, while any failure restores the pre-operation bytes and metadata and leaves a redacted local audit/recovery record.
**Plans**: TBD
**UI hint**: yes

### Phase 4: Certified Entity and Data Round Trip
**Goal**: Developers can safely inspect and plan narrowly certified, source-preserving entity/data changes on existing Jmix projects.
**Depends on**: Phase 3
**Requirements**: DATA-01, DATA-02, DATA-03, DATA-04, DATA-05, DATA-06, DATA-07, DATA-08, DATA-09, DATA-10
**Success Criteria** (what must be TRUE):
  1. Developers can open existing hand-edited Java and Kotlin entities, DTOs, and enums, then close them without edits and obtain a byte-identical no-op.
  2. Developers can plan adapter-supported attributes, validations, indexes, instance names, comments, traits, identifier strategies, and associations with correct ownership semantics and no unrelated diff.
  3. Entity and enum plans coordinate missing localization keys across configured bundles and locales while preserving manual values, comments, ordering, and incomplete-translation diagnostics.
  4. Entity changes can add a deterministic Liquibase changeset and master include to the correct module, store, graph, identifier rules, and certified dialect, while historical or destructive changes require a separate explicit validated intent.
**Plans**: TBD
**UI hint**: yes

### Phase 5: Certified Existing CRUD Round Trip
**Goal**: Developers can complete certified entity-to-CRUD workflows on existing Jmix 2.8.x and 3.0.x projects without semantic drift or unrelated source changes.
**Depends on**: Phase 4
**Requirements**: COMP-04, COMP-05, CRUD-01, CRUD-02, CRUD-03, CRUD-04, CRUD-05, CRUD-06, CRUD-07, CRUD-08
**Success Criteria** (what must be TRUE):
  1. Exact advertised Jmix 2.8.x/Java 17 or 21 and Jmix 3.0.x/Java 21 or 25 fixture cells can complete the v1 entity/data and CRUD workflows using their adapter-specific rules.
  2. From an indexed entity, developers can plan bounded list/detail views and reopen the linked descriptor and controller as one semantic structure without changing either file merely by viewing it.
  3. Supported view edits preserve unknown/custom XML, namespaces, comments, add-on components, controller code, imports, annotations, and formatting, while palette choices and generated APIs come from the selected adapter and detected catalog.
  4. Developers can edit supported fetch plans and owned menu items with provenance, while cycles, unresolved references, and duplicate identifiers block Apply with source-linked diagnostics.
  5. Every advertised entity-to-view workflow compiles and reopens without semantic drift after Apply.
**Plans**: TBD
**UI hint**: yes

### Phase 6: Security and Reproducible Team Workflow
**Goal**: Teams can review, validate, secure, and reproduce supported plans consistently across the IDE and CI.
**Depends on**: Phase 5
**Requirements**: COMP-08, SAFE-12, TEAM-01, TEAM-02, TEAM-03, TEAM-04, TEAM-05, TEAM-06, TEAM-08, TEAM-09
**Success Criteria** (what must be TRUE):
  1. Developers can open and plan supported resource-role changes while distinguishing current, inherited, framework, and add-on policies and keeping default-deny provenance explicit.
  2. Invalid role targets, unresolved resources, policy conflicts, and unsecured generated navigation block Apply with cross-artifact, source-linked diagnostics.
  3. For the same revision and inputs, the documented headless command and IDE produce the same normalized operations, digest, compatibility state, artifact/policy validation, and diagnostic codes.
  4. Every released visual workflow is keyboard-operable, labeled and focusable, preserves visible focus, supports zoom, and passes automated plus manual accessibility gates.
  5. Teams can export deterministic redacted plan reports and support bundles, review ownership and dependencies without workstation-specific output, and read compatibility tables generated from the same registry that authorizes backend operations.
**Plans**: TBD
**UI hint**: yes

### Phase 7: Enterprise Certification and Scale
**Goal**: Advertised compatibility and performance reflect representative enterprise repositories and installed-product evidence rather than broad version assumptions.
**Depends on**: Phase 6
**Requirements**: ENT-01, ENT-02, ENT-03, ENT-04, ENT-05, ENT-06, ENT-07, ENT-08, ENT-09
**Success Criteria** (what must be TRUE):
  1. The compatibility laboratory covers pinned fresh, upgraded, customized, malformed, adversarial, and recognized-read-only fixtures across declared languages, DSLs, source layouts, topologies, add-ons, stores, dependency health, and offline states.
  2. Every certified write cell proves non-mutating open, byte-identical no-op, minimal golden diff, parse/reparse, compile/integration behavior, idempotence, stale blocking, failure rollback, and exact Undo/Redo.
  3. Installed plugin ZIP scenarios exercise import, indexing, workbench loading, JCEF, plan review, Apply, Undo/Redo, and diagnostics on every advertised IntelliJ host.
  4. Pull-request, nightly, and release-candidate matrices enforce progressively broader Jmix, IntelliJ, and target-project JDK coverage from Java 17 through the latest officially compatible tested release, and any regression automatically blocks or downgrades only the affected operation/profile cell.
  5. Permissioned, anonymized customer-derived and representative enterprise-scale fixtures meet published startup, indexing, EDT, interaction, memory, cache, payload, and disposal/leak budgets with recorded provenance and retention controls.
**Plans**: TBD

### Phase 8: Trusted Worldwide Release
**Goal**: Worldwide users can obtain verifiable plugin artifacts and exact, versioned guidance for the capabilities actually certified.
**Depends on**: Phase 7
**Requirements**: PROD-07, PROD-08, ENT-10
**Success Criteria** (what must be TRUE):
  1. Every release candidate produces immutable plugin ZIPs with checksums, JVM and frontend SBOMs, provenance metadata, and recorded signature-verification results.
  2. Plugin Verifier and installed-plugin smoke tests pass on every IntelliJ host before that host appears in release claims or documentation.
  3. Each release publishes versioned installation, compatibility, onboarding, troubleshooting, migration, privacy, security, contribution, and release guidance alongside the artifacts.
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Clean-Room Build Foundation | 5/5 | Complete | 2026-07-28 |
| 2. Compatibility Laboratory and Read-Only Onboarding | 0/18 | Not started | - |
| 3. Typed Privilege Boundary and Change Engine | 0/TBD | Not started | - |
| 4. Certified Entity and Data Round Trip | 0/TBD | Not started | - |
| 5. Certified Existing CRUD Round Trip | 0/TBD | Not started | - |
| 6. Security and Reproducible Team Workflow | 0/TBD | Not started | - |
| 7. Enterprise Certification and Scale | 0/TBD | Not started | - |
| 8. Trusted Worldwide Release | 0/TBD | Not started | - |

# Requirements: Jmix Visual Development Workbench

**Defined:** 2026-07-27  
**Core value:** Developers can make substantial Jmix project changes visually without risking silent source corruption: every operation understands the existing project, shows the intended diff, validates the result, applies changes atomically, and can be undone.

## v1 Requirements

### Product, Build, and Integrity

- [ ] **PROD-01**: A contributor can clone the repository and build all supported plugin artifacts with checked-in wrappers and documented JDK/Node prerequisites.
- [ ] **PROD-02**: A developer can install each produced ZIP into its advertised IntelliJ IDEA lane and open the workbench without class-loading, JCEF, or missing-resource errors.
- [ ] **PROD-03**: Every plugin build includes a production web bundle built from the same source revision and fails if the bundle is missing or stale.
- [ ] **PROD-04**: Users see an original product name, original assets, an explicit license, and a clear “compatible with Jmix; not affiliated with or endorsed by Haulmont” disclaimer.
- [ ] **PROD-05**: Contributors work under documented clean-room, source-provenance, trademark, security, and contribution rules that prohibit proprietary Studio materials and license circumvention.
- [ ] **PROD-06**: Release dependencies are pinned, locked where supported, and verified against recorded checksums or equivalent integrity metadata.
- [ ] **PROD-07**: Every release candidate produces checksums, JVM-and-frontend SBOMs, provenance metadata, and signature-verification results for each immutable plugin ZIP.
- [ ] **PROD-08**: Plugin Verifier and installed-plugin smoke tests pass on every advertised IntelliJ host before that host appears in release documentation.

### Compatibility Contract

- [ ] **COMP-01**: A developer can view the exact detected IntelliJ, Jmix, JDK, Gradle DSL, build topology, language, add-on, data-store, trust, and import-health profile for the open project.
- [ ] **COMP-02**: The backend returns an operation-specific compatibility state and reason: certified read/write, certified read-only, recognized diagnostic, or unsupported.
- [ ] **COMP-03**: A project receives write capability only when its exact operation/profile cell is present in the reviewed compatibility registry with passing fixture evidence.
- [ ] **COMP-04**: Certified Jmix 2.8.x fixtures can complete the v1 entity/data and CRUD workflows using adapter-specific source and configuration rules.
- [ ] **COMP-05**: Certified Jmix 3.0.x fixtures can complete the v1 entity/data and CRUD workflows using adapter-specific source and configuration rules.
- [ ] **COMP-06**: Recognized earlier Jmix 2.x, Jmix 1.x, CUBA-era, future, ambiguous, stale, untrusted, or uncertified projects remain useful for diagnostics and navigation but cannot be mutated.
- [ ] **COMP-07**: Users can see why a requested option is unavailable for their exact profile and what tested profile or migration path would make it available.
- [ ] **COMP-08**: Release compatibility tables are generated from the same registry that authorizes backend operations, so documentation cannot claim unsupported write coverage.

### Existing-Project Discovery and Semantic Understanding

- [ ] **DISC-01**: Opening, importing, indexing, browsing, and closing any fixture causes zero source, configuration, build, VCS, database, or network mutation.
- [ ] **DISC-02**: Developers can inspect single-module, multi-project, composite/included-build, nested-build, and add-on functional/starter topologies without flattening module ownership.
- [ ] **DISC-03**: The workbench identifies real Java, Kotlin, mixed-language, custom, generated, test, and resource source roots from IDE/imported build evidence instead of hard-coded paths.
- [ ] **DISC-04**: The workbench reports resolved Jmix platform versions, Gradle plugins, official/third-party/internal add-ons, optional IDE capabilities, and unresolved dependencies without executing arbitrary project code.
- [ ] **DISC-05**: Developers can inspect main and additional data stores, their module ownership, migration roots, and dialect evidence without opening a database connection.
- [ ] **DISC-06**: Developers can browse an indexed semantic inventory of entities, DTOs, enums, views/controllers, fetch plans, menus, roles, messages, repositories, changelogs, modules, and add-on provenance.
- [ ] **DISC-07**: Every indexed artifact exposes stable identity, owning build/module/source set, source location, origin/provenance, diagnostics, and current revision fingerprint.
- [ ] **DISC-08**: Index updates are incremental, cancellable, dumb-mode aware, and never retain invalid PSI as long-lived application state.
- [ ] **DISC-09**: Developers can inspect incoming and outgoing relationships among indexed Jmix artifacts without creating a second authoritative project model.
- [ ] **DISC-10**: Developers can navigate from workbench items and diagnostics to the relevant source file and line using native IntelliJ navigation.

### Privilege Boundary and Change Safety

- [ ] **SAFE-01**: The packaged workbench loads only from a controlled origin with navigation restrictions, content security policy, trusted-project gating, and no ambient filesystem authority.
- [ ] **SAFE-02**: Every bridge request carries a protocol version, unique request ID, typed action, bounded payload, and structured success or error response.
- [ ] **SAFE-03**: Bridge requests are schema-validated, allowlisted, size/depth limited, timeout-aware, cancellable, and rejected when their origin or project trust state is invalid.
- [ ] **SAFE-04**: All output targets are derived and validated in Kotlin from semantic identifiers; canonical containment, symlink, traversal, separator, wrong-module, and ownership checks prevent out-of-root writes.
- [ ] **SAFE-05**: Every mutating intent produces an immutable change plan containing semantic operations, exact affected files, create/modify/delete classification, before/after diff, diagnostics, dependencies, and a stable digest.
- [ ] **SAFE-06**: Applying a plan rechecks project snapshot, target fingerprints, compatibility decision, ownership, writability, and validation evidence; any drift blocks the operation with an actionable conflict.
- [ ] **SAFE-07**: Supported edits preserve comments, formatting, manual code, unknown annotations/nodes/properties, line endings, encoding, and unrelated content; uncertain anchors block instead of triggering whole-file regeneration.
- [ ] **SAFE-08**: Planned artifacts parse and pass syntax, schema, reference, cross-artifact, adapter, and policy validation before Apply becomes available.
- [ ] **SAFE-09**: A successful multi-file plan applies through one IntelliJ command, while any injected or real failure restores the exact pre-operation bytes and metadata.
- [ ] **SAFE-10**: One IDE Undo restores every file in a successful plan to its exact preimage, and one Redo restores the exact validated result.
- [ ] **SAFE-11**: No generator, designer, bridge handler, extension, or background service can bypass the central change-plan and apply coordinator to write project files.
- [ ] **SAFE-12**: Developers can export a deterministic, redacted plan report and revalidate it headlessly against the intended project revision without exposing source contents or secrets by default.
- [ ] **SAFE-13**: Local audit and recovery records identify tool/adapter versions, operation, project/profile fingerprints, diagnostics, and outcome while redacting source, credentials, personal paths, and proprietary metadata.

### Entity, DTO, Enum, Localization, and Data Evolution

- [ ] **DATA-01**: Developers can open existing hand-edited Java entities, DTOs, and enums as semantic models without changing their files.
- [ ] **DATA-02**: Developers can open existing hand-edited Kotlin entities, DTOs, and enums as semantic models without changing their files.
- [ ] **DATA-03**: Opening and closing a supported Java or Kotlin artifact without edits produces a byte-identical no-op result.
- [ ] **DATA-04**: Developers can plan supported additions or edits to attributes, validations, indexes, instance names, comments, and adapter-supported traits with no unrelated diff.
- [ ] **DATA-05**: Developers can model supported UUID, numeric, string, embedded, and composite identifier strategies only when the selected adapter can generate and validate the complete contract.
- [ ] **DATA-06**: Developers can plan supported to-one, to-many, bidirectional, composition, cascade, fetch, and delete-policy associations with correct Java/Kotlin collection and ownership semantics.
- [ ] **DATA-07**: Entity and enum changes coordinate missing localization keys across configured bundles/locales while preserving comments, ordering, manual values, and incomplete-translation diagnostics.
- [ ] **DATA-08**: Entity changes can propose new additive Liquibase changesets and deterministic master includes without editing historical applied changesets.
- [ ] **DATA-09**: Liquibase plans target the correct owning module, data store, changelog graph, identifier rules, and certified database dialect.
- [ ] **DATA-10**: Rename, drop, type-narrowing, data migration, and other destructive operations are never inferred from an entity diff or applied without a separately explicit, validated intent.

### Existing CRUD UI Round Trip

- [ ] **CRUD-01**: From an indexed entity, developers can plan adapter-valid bounded list and detail views with descriptors, controllers, containers/loaders, actions, routes, captions, and optional navigation.
- [ ] **CRUD-02**: Developers can open an existing supported view descriptor together with its controller and see their linked semantic structure without modifying either file.
- [ ] **CRUD-03**: Supported view edits preserve unknown/custom XML nodes, namespaces, comments, add-on components, controller methods, imports, annotations, and manual formatting.
- [ ] **CRUD-04**: Developers can create or edit supported named fetch plans, inheritance, nested properties, named references, and fetch modes; invalid or cyclic references block Apply.
- [ ] **CRUD-05**: Developers can inspect composite menus with local, framework, and add-on provenance; inherited items remain read-only and owned edits produce a minimal XML diff.
- [ ] **CRUD-06**: Duplicate view, route, component, action, menu, message, fetch-plan, or reference identifiers block the plan with source-linked diagnostics.
- [ ] **CRUD-07**: View palette choices and generated APIs come from the selected adapter and detected classpath/add-on catalog rather than a universal hard-coded component list.
- [ ] **CRUD-08**: The entity-to-view workflow compiles and reopens without semantic drift on every Jmix/profile cell advertised for CRUD write support.

### Security, Team Workflow, and IDE Quality

- [ ] **TEAM-01**: Developers can open and plan supported resource-role changes for role identity/hierarchy, UI, entity, attribute, menu, and specific policies.
- [ ] **TEAM-02**: Role views distinguish current, inherited, framework, and add-on policies while keeping default-deny behavior and effective provenance explicit.
- [ ] **TEAM-03**: Invalid role targets, unresolved resources, policy conflicts, and unsecured generated navigation produce blocking cross-artifact diagnostics.
- [ ] **TEAM-04**: A documented headless command validates the same project model, compatibility decisions, artifact references, and policy rules used by the IDE.
- [ ] **TEAM-05**: For the same revision and inputs, IDE and headless planning produce the same normalized operations, digest, compatibility state, and diagnostic codes.
- [ ] **TEAM-06**: Released visual workflows are fully operable by keyboard, expose labeled/focusable controls, retain visible focus, support zoom, and pass automated plus manual accessibility gates.
- [ ] **TEAM-07**: Long-running discovery, indexing, planning, validation, and rendering work is cancellable and does not perform blocking work on the IntelliJ event-dispatch thread.
- [ ] **TEAM-08**: Developers can create a redacted support bundle containing compatibility, diagnostics, performance, and release metadata without source contents, credentials, or proprietary dependency data.
- [ ] **TEAM-09**: Plans identify ownership and dependent operations clearly enough for teams to review generated changes in the IDE, CI, and version control without workstation-specific output.

### Enterprise Certification and Worldwide Adoption

- [ ] **ENT-01**: The compatibility laboratory includes pinned fresh, upgraded, customized, malformed, adversarial, and recognized-read-only fixtures across the declared Jmix version matrix.
- [ ] **ENT-02**: Fixture coverage includes Java-only, Kotlin-only, mixed language, Groovy/Kotlin DSL, version catalogs, convention plugins, custom source sets, generated roots, and hand-formatted artifacts.
- [ ] **ENT-03**: Fixture coverage includes single-module, multi-project, composite/included-build, multi-repository, add-on-heavy, multiple-data-store, private/unavailable dependency, offline, stale-import, and failed-import profiles.
- [ ] **ENT-04**: Every certified write cell proves non-mutating open, byte-identical no-op, minimal golden diff, parse/reparse, compile/integration checks, idempotence, stale blocking, failure rollback, and exact Undo/Redo.
- [ ] **ENT-05**: Installed plugin ZIP scenarios exercise project import, indexes, workbench loading, JCEF protocol, plan review, Apply, Undo/Redo, and diagnostics on every advertised IntelliJ host.
- [ ] **ENT-06**: Pull request, nightly, and release-candidate CI matrices enforce progressively broader Jmix, IDE, JDK, operating-system, filesystem, database, topology, add-on, and adversarial coverage.
- [ ] **ENT-07**: Representative enterprise-scale fixtures meet published budgets for startup impact, initial/incremental indexing, event-dispatch-thread blocking, interaction latency, memory, cache size, payload size, and disposal/leaks.
- [ ] **ENT-08**: A compatibility regression automatically blocks or downgrades the affected operation/profile cell instead of leaving a permissive support claim in place.
- [ ] **ENT-09**: Sanitized customer-derived fixtures enter the laboratory only with permission, anonymization, provenance, retention, access-control, and license review.
- [ ] **ENT-10**: Worldwide users receive versioned installation, compatibility, onboarding, troubleshooting, migration, privacy, security, contribution, and release documentation generated alongside each product release.

## v2 Requirements

### Advanced Analysis and Governance

- **ADV-01**: Developers can explore a searchable project-wide entity relationship canvas sourced from the semantic index.
- **ADV-02**: Developers can compare JPA, Liquibase, and a read-only live database schema with provenance-aware drift classification.
- **ADV-03**: Teams can use checked-in, schema-versioned policy packs to enforce naming, ownership, architecture, safety, and generation rules in IDE and CI.
- **ADV-04**: Teams can simulate effective permissions across supported resource- and row-level role hierarchies with provenance.
- **ADV-05**: Add-ons can contribute version-bounded catalogs, metadata, validators, and declarative plan operations through a capability-scoped extension SDK.

### Broader Jmix Workflows

- **FLOW-01**: Developers can author validated row-level JPQL/predicate policies after security semantics are certified.
- **FLOW-02**: Developers can use a Jmix-aware JPQL designer and validation console integrated with native editor/database capabilities.
- **FLOW-03**: Developers can create and round-trip data repositories and Jmix 3 update services through version adapters.
- **FLOW-04**: Developers can run reproducible OpenAPI client/service generation using checked-in configuration and explicit generated-source ownership.
- **FLOW-05**: Developers can use runtime-backed preview or hot deploy with explicit process lifecycle, build, log, and security boundaries.
- **FLOW-06**: Developers can perform read-only database reverse engineering before selecting explicit reviewed entity/Liquibase changes.
- **FLOW-07**: Developers can use separately certified project/add-on creation templates and project-property workflows.

### Migration and Ecosystem

- **MIGR-01**: Teams can assess and plan Jmix 2.8-to-3.x upgrades through an isolated, audited migration workflow.
- **MIGR-02**: Teams can receive read-only migration guidance for earlier Jmix 2.x, Jmix 1.x, and CUBA-era applications without pretending normal round-trip compatibility.
- **MIGR-03**: Private enterprises can use controlled update channels and internal compatibility manifests without weakening artifact verification.
- **MIGR-04**: Optional AI assistance can propose typed intents only through the same compatibility, preview, validation, approval, and atomic-apply pipeline as manual actions.

## Out of Scope

| Capability | Reason |
|------------|--------|
| Pixel-identical Jmix Studio reproduction | Creates trademark/IP risk and does not improve source safety or enterprise outcomes |
| Proprietary Studio code, templates, protocols, icons, assets, or decompiled behavior | Violates the clean-room product boundary |
| Subscription, license, or entitlement bypass | Illegitimate and unrelated to compatible development workflows |
| Commercial Jmix add-on runtime redistribution | Users must license and install commercial runtimes separately |
| Visual editor as a second source of truth | Causes drift and unsafe merge behavior; repository source remains authoritative |
| Whole-file regeneration of existing artifacts | Cannot satisfy manual-code preservation or no-unrelated-diff requirements |
| A universal “best effort” generator or “force unsafe” switch | Turns uncertainty into repository corruption risk |
| Automatic destructive schema inference or database execution | Renames/drops/data moves require separate explicit intent and review |
| BPMN/DMN editor presented as a BPM runtime | Authoring and a licensed/runtime engine are different products |
| Autonomous AI file writes | Any future AI must submit the same typed, reviewable change plans |
| Core cloud deployment or credential management | Expands operational risk beyond the source-development core |
| Default telemetry or remote upload of source/model data | Enterprise repository metadata is sensitive; operation is local-first |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| PROD-01 | TBD | Pending |
| PROD-02 | TBD | Pending |
| PROD-03 | TBD | Pending |
| PROD-04 | TBD | Pending |
| PROD-05 | TBD | Pending |
| PROD-06 | TBD | Pending |
| PROD-07 | TBD | Pending |
| PROD-08 | TBD | Pending |
| COMP-01 | TBD | Pending |
| COMP-02 | TBD | Pending |
| COMP-03 | TBD | Pending |
| COMP-04 | TBD | Pending |
| COMP-05 | TBD | Pending |
| COMP-06 | TBD | Pending |
| COMP-07 | TBD | Pending |
| COMP-08 | TBD | Pending |
| DISC-01 | TBD | Pending |
| DISC-02 | TBD | Pending |
| DISC-03 | TBD | Pending |
| DISC-04 | TBD | Pending |
| DISC-05 | TBD | Pending |
| DISC-06 | TBD | Pending |
| DISC-07 | TBD | Pending |
| DISC-08 | TBD | Pending |
| DISC-09 | TBD | Pending |
| DISC-10 | TBD | Pending |
| SAFE-01 | TBD | Pending |
| SAFE-02 | TBD | Pending |
| SAFE-03 | TBD | Pending |
| SAFE-04 | TBD | Pending |
| SAFE-05 | TBD | Pending |
| SAFE-06 | TBD | Pending |
| SAFE-07 | TBD | Pending |
| SAFE-08 | TBD | Pending |
| SAFE-09 | TBD | Pending |
| SAFE-10 | TBD | Pending |
| SAFE-11 | TBD | Pending |
| SAFE-12 | TBD | Pending |
| SAFE-13 | TBD | Pending |
| DATA-01 | TBD | Pending |
| DATA-02 | TBD | Pending |
| DATA-03 | TBD | Pending |
| DATA-04 | TBD | Pending |
| DATA-05 | TBD | Pending |
| DATA-06 | TBD | Pending |
| DATA-07 | TBD | Pending |
| DATA-08 | TBD | Pending |
| DATA-09 | TBD | Pending |
| DATA-10 | TBD | Pending |
| CRUD-01 | TBD | Pending |
| CRUD-02 | TBD | Pending |
| CRUD-03 | TBD | Pending |
| CRUD-04 | TBD | Pending |
| CRUD-05 | TBD | Pending |
| CRUD-06 | TBD | Pending |
| CRUD-07 | TBD | Pending |
| CRUD-08 | TBD | Pending |
| TEAM-01 | TBD | Pending |
| TEAM-02 | TBD | Pending |
| TEAM-03 | TBD | Pending |
| TEAM-04 | TBD | Pending |
| TEAM-05 | TBD | Pending |
| TEAM-06 | TBD | Pending |
| TEAM-07 | TBD | Pending |
| TEAM-08 | TBD | Pending |
| TEAM-09 | TBD | Pending |
| ENT-01 | TBD | Pending |
| ENT-02 | TBD | Pending |
| ENT-03 | TBD | Pending |
| ENT-04 | TBD | Pending |
| ENT-05 | TBD | Pending |
| ENT-06 | TBD | Pending |
| ENT-07 | TBD | Pending |
| ENT-08 | TBD | Pending |
| ENT-09 | TBD | Pending |
| ENT-10 | TBD | Pending |

**Coverage:**
- v1 requirements: 76
- Mapped to phases: 0
- Unmapped: 76

---
*Requirements defined: 2026-07-27*

# Jmix Visual Development Workbench

## What This Is

An original, clean-room IntelliJ IDEA plugin that gives Jmix developers a safe visual workbench for understanding, designing, and changing real Jmix applications. It combines an embedded React interface with source-aware IntelliJ services, version-aware Jmix adapters, previewable change plans, and validated generators for entities, views, security, Liquibase, menus, localization, and related project artifacts.

The product is intended for professional teams working on valuable multi-module repositories. It is not a byte-for-byte copy of Jmix Studio, does not use proprietary Studio code or assets, and does not bypass Jmix licensing. It implements compatible developer workflows from public specifications using original code.

## Core Value

Developers can make substantial Jmix project changes visually without risking silent source corruption: every operation understands the existing project, shows the intended diff, validates the result, applies changes atomically, and can be undone.

## Target Users

- Jmix developers using IntelliJ IDEA 2025.3 or newer.
- Enterprise product teams maintaining large, multi-module Jmix 2.8 LTS and Jmix 3.x applications.
- Platform teams that need repeatable scaffolding, reviewable code generation, policy controls, and CI-compatible validation.
- Developers who prefer visual modeling for structure but retain normal source-code ownership and extension points.

## Current State

The repository contains a substantial prototype:

- A React/TypeScript workbench with entity, view, CRUD, menu, role, and migration panels.
- A Kotlin IntelliJ plugin shell using JCEF.
- Draft in-memory models and generators for Java, XML, Liquibase, roles, menus, repositories, events, and BPMN.
- The web UI currently compiles into a production Vite bundle.

The prototype is not production-safe:

- The IntelliJ plugin does not build with the available toolchain and targets an obsolete IDE baseline.
- Several default generation paths produce invalid Java or XML.
- Existing project artifacts are not parsed or round-tripped.
- File changes are direct, destructive, non-atomic, and lack preview or rollback.
- The browser bridge lacks a versioned typed protocol, request correlation, origin controls, and robust validation.
- Tests, CI, plugin verification, release signing, SBOMs, and compatibility matrices are absent.

The detailed evidence and clean-room boundary are recorded in `JMIX_STUDIO_ASSESSMENT.md` and `.planning/codebase/`.

## Requirements

### Validated

- ✓ The React/TypeScript workbench can be built as a production Vite bundle. — existing
- ✓ The prototype exposes coherent visual concepts for entity, view, CRUD, menu, role, and migration workflows. — existing
- ✓ The plugin/web architecture can host a React workbench in an IntelliJ JCEF tool window. — existing prototype

### Active

- [ ] Produce a reproducible, installable IntelliJ plugin build on a supported JDK/Gradle/IntelliJ baseline.
- [ ] Support existing enterprise Jmix solutions through explicit, testable compatibility adapters: full read/write workflows for certified Jmix 2.x and 3.x ranges, safe read-only diagnostics for recognized-but-uncertified versions, and separately gated legacy migration assistance.
- [ ] Discover and index real single-module, multi-module, composite, Java, Kotlin, mixed-language, customized, and add-on-heavy Jmix projects without unsafe defaults.
- [ ] Parse existing Java/Kotlin, XML, properties, Gradle, and Liquibase artifacts into a semantic project model.
- [ ] Generate and round-trip entities, enums, DTOs, associations, IDs, views, controllers, menus, messages, roles, fetch plans, repositories, and migrations.
- [ ] Present every mutating operation as a deterministic change plan with file ownership, validation diagnostics, and a human-readable diff.
- [ ] Apply multi-file changes atomically through IntelliJ write commands with path containment, conflict detection, backups, and IDE undo/redo.
- [ ] Use a versioned, typed, schema-validated, request-correlated JCEF protocol with timeouts, cancellation, payload limits, and origin restrictions.
- [ ] Provide source-aware entity, view, menu, role, and migration designers that load existing project state instead of only producing new files.
- [ ] Validate generated source and configuration with parser/golden tests and representative Jmix fixture projects.
- [ ] Ship CI release gates for unit, integration, UI, plugin verifier, compatibility, security, dependency, and artifact integrity checks.
- [ ] Provide enterprise-quality diagnostics, auditability, accessibility, performance budgets, documentation, and migration guidance.
- [ ] Publish a fixture-backed compatibility matrix covering Jmix versions, IntelliJ versions/editions, Java/Gradle baselines, languages, build layouts, databases, data stores, add-ons, and supported read/write workflows.
- [ ] Establish an original product identity, explicit license, trademark disclaimer, contribution policy, and secure release process.

### Out of Scope

- Copying, decompiling, patching, calling into, or redistributing proprietary Jmix Studio code, templates, icons, branding, or assets.
- Circumventing subscriptions, license checks, or entitlement controls in Jmix Studio or commercial Jmix add-ons.
- Claiming that this workbench supplies commercial Jmix runtime functionality such as the Jmix BPM runtime.
- Pixel-identical reproduction of Jmix Studio.
- Replacing IntelliJ's source editor or forcing all business logic into visual editors.
- Silent generation into valuable repositories without preview, validation, and an undoable transaction.

## Product Principles

1. **Repository safety before feature count.** A missing generator is preferable to a generator that can overwrite or corrupt user work.
2. **Round-trip, not one-way scaffolding.** Visual state and source state must remain reconcilable.
3. **Explicit compatibility.** Version differences belong in adapters and fixtures, never scattered conditionals or guessed defaults.
4. **Native IDE behavior.** Use PSI/UAST, DOM, VFS, write commands, inspections, notifications, and undo/redo where they provide correctness.
5. **Source remains authoritative.** The UI is a projection and editor of real project state, not an isolated shadow model.
6. **Reviewable automation.** Stable plans, diffs, diagnostics, and generated-file provenance make automation suitable for teams and CI.
7. **Clean-room implementation.** Public documentation and open specifications define compatibility; all implementation and product design are original.

## Enterprise Success Criteria

- A new contributor can clone the repository and produce a verified plugin artifact with documented prerequisites and no manual file repair.
- The plugin installs and starts on every supported IntelliJ baseline and correctly identifies supported and unsupported Jmix projects.
- Opening an existing customized enterprise solution is always non-mutating; unsupported constructs remain visible and preserved, while write operations are enabled only where the compatibility matrix and project analysis prove them safe.
- Representative fixtures include Java, Kotlin, multi-module, composite, multiple-data-store, add-on-heavy, custom-component, hand-formatted, and long-lived upgraded projects—not only newly generated samples.
- Destructive path traversal and out-of-root writes are impossible by construction and covered by tests.
- A failed multi-file operation leaves the repository byte-for-byte unchanged.
- Existing hand-edited source survives supported visual edits; conflicts stop with actionable diagnostics.
- Representative Jmix 2.8 and 3.x fixture projects compile and validate after each supported workflow.
- Plugin verifier, dependency, security, license, and artifact checks block release regressions.
- Large multi-module projects stay within documented indexing, memory, startup, and interaction budgets.
- Every public release is versioned, checksummed, documented, reproducible, and accompanied by a compatibility matrix and SBOM.

## Constraints

- **Platform:** IntelliJ IDEA 2025.3+ is the minimum product family baseline for current Jmix 3 tooling; compatibility must be verified rather than claimed.
- **Jmix versions:** Jmix 2.x, Jmix 3.x, Jmix 1.x, and CUBA-era solutions differ materially. Certified read/write support must be declared per adapter and fixture matrix; legacy migration is isolated from normal editing.
- **Technology:** Kotlin for plugin services, React/TypeScript for the JCEF UI, and Gradle for plugin builds remain the starting architecture unless evidence justifies a change.
- **Security:** JCEF content is untrusted input. Bridge commands must be allowlisted and validated independently of the UI.
- **Data integrity:** No direct string-based overwrite of existing structured project files is acceptable for enterprise release.
- **Legal:** The project must remain an independent compatible workbench and must not use proprietary implementation materials.
- **Quality:** Changes affecting project files require automated safety, parser, generator, integration, and failure-rollback coverage.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Build an original compatible workbench, not a clone | Protects the project and its users from IP and licensing risk | Accepted |
| Treat source files as the system of record | Prevents divergence between visual models and real applications | Accepted |
| Establish safety/build foundations before expanding designers | Current direct writes and invalid output are release blockers | Accepted |
| Support Jmix 2.8 LTS and 3.x through adapters and fixtures | Enterprise users need both stability and current-platform support | Accepted |
| Recognize older enterprise projects without guessing | Worldwide adoption requires safe onboarding of long-lived solutions; uncertified projects must degrade to diagnostic/read-only behavior | Accepted |
| Require plan → preview → validate → atomic apply for mutations | Makes visual automation reviewable and recoverable | Accepted |
| Use quality-focused planning with research, plan checks, verification, and test coverage | “World class” requires enforced gates, not feature claims | Accepted |

---
*Last updated: 2026-07-27 after initial enterprise assessment*

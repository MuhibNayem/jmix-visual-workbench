# Feature Landscape

**Project:** Jmix Visual Development Workbench  
**Domain:** Clean-room, source-aware visual workbench for Jmix applications in IntelliJ IDEA  
**Researched:** 2026-07-27  
**Overall confidence:** HIGH for documented Jmix and IntelliJ workflows; MEDIUM for prioritization and enterprise-product expectations

## Product Position

The product should not compete by reproducing Jmix Studio screen-for-screen. It should provide an original workflow around a stronger promise:

> Understand the existing Jmix project, turn a requested visual edit into a deterministic change plan, prove that the result is valid, and apply it as one undoable IntelliJ operation.

That promise is narrower than full Jmix Studio parity and more valuable than a collection of one-way generators. Public Jmix documentation establishes the expected artifact coverage. IntelliJ establishes the expected editor behavior. Open tools such as JHipster JDL, Jeddict, Liquibase/JPA tooling, and OpenAPI Generator establish expectations for model visualization, reverse engineering, validation, reproducibility, and dry runs.

The source tree remains authoritative. Visual state is a projection of Java/Kotlin, XML, properties, Gradle, and Liquibase files, never a second hidden model.

## Ecosystem Baseline

### Official Jmix Studio 2.8 versus 3.0

The 2.8 and 3.0 Studio catalogs have the same main workflow families: entities, data stores, view creation and design, fetch plans, menus, roles, coding assistance, JPQL, hot deploy, reverse engineering, OpenAPI, repositories, project properties, add-ons, profiles, and composite projects. This means enterprise users moving between supported lines will expect stable concepts even where generated APIs differ.

| Workflow | Jmix Studio 2.8 public baseline | Jmix Studio 3.0 public baseline | Requirement for this workbench |
|---|---|---|---|
| Project navigation | Jmix tool window organizes project artifacts | Same, with major actions also exposed in the Project tool-window context menu | Present one semantic project tree and native context actions; do not require users to navigate only through a web canvas |
| Entity modeling | JPA/DTO/enum designers; text/design tabs; associations, traits, validation, indexes, localization, DB import | Same workflow, plus inherited-attribute visibility, update-service creation, and new version-specific traits such as tenant ID | One internal model with explicit `2.8` and `3.x` capabilities; unsupported controls are explained, not silently omitted |
| View creation | Wizards generate list/detail and other template-based views | Same, with update-service-aware creation and 3.x component/API changes | Wizard choices must come from the target project's classpath and adapter, not a hard-coded component list |
| View editing | XML editor, controller navigation, structure, inspector, palette, and interactive schematic preview | Same family; 3.0 uses Vaadin 25/Jmix 3 APIs and theme/component changes | Round-trip descriptor and controller edits; preview status and build errors must be visible; never claim runtime fidelity for a schematic-only preview |
| Data loading | Visual fetch-plan editing with nested reference fetch plans and fetch modes | Same family | Validate entity/property/fetch-plan references and preserve named-plan reuse |
| Menu | Text/structure views; drag reorder; application plus inherited add-on items; composite/single modes | Same family | Show owned versus inherited items and refuse duplicate IDs or unsafe flattening |
| Security | Resource and row-level roles; current versus ancestor permissions; inherited project/add-on resources | Same family | Default-deny semantics, role hierarchy, provenance, entity/attribute/UI/specific policies, and predicate/JPQL validation |
| Database | Data-store configuration, schema reverse engineering, partial reverse engineering, Liquibase generation/aggregation | Same family; 3.0 adds control over drop changesets for unmapped tables | Read-only introspection by default; destructive/drop changes require conspicuous opt-in and exact diff |
| Build/project upgrade | Version and project-property workflows | Studio 3.0 migrates Gradle/JDK/dependencies/source/XML for the Jmix 3 baseline and reads/writes Kotlin DSL | Version changes are a separate audited migration workflow, never a side effect of opening or editing a designer |
| Developer intelligence | Completion, references, inspections, intentions, quick fixes, navigation, snippets, JPQL support | Same family | Use native editor affordances for small local tasks; visual workbench should complement, not replace, IntelliJ |
| Integration | OpenAPI client generation and service scaffolding | Same, with Spring Boot 4/Jackson 3 generator options | Treat the schema and checked-in configuration as source inputs; show generated-source ownership and regeneration behavior |

**Version conclusion:** v1 should support the shared core workflows but use separate compatibility adapters and fixture projects for 2.8 and 3.x. Cross-version project upgrade is a later, independently gated feature because Jmix 3.0 changes Java, Gradle, Spring Boot, Vaadin, EclipseLink, code signatures, XML/component behavior, and build scripts.

### IntelliJ-Native Workflow Expectations

| IntelliJ behavior | User expectation | Product implication |
|---|---|---|
| PSI-aware editing | Refactors and edits preserve syntax, references, imports, formatting, and unrelated code | Existing Java/Kotlin must be modified semantically, not replaced with generated text |
| Command/write-action model | IDE edits participate in Undo/Redo | A multi-file feature change is one named command and one undo step |
| Navigation and references | Gutter icons, Find Usages, Go to Declaration, and context actions connect artifacts | Entity → view → menu → role → changelog relationships need native navigation |
| Inspections and quick fixes | Problems appear near source and can be fixed locally | The workbench must publish diagnostics into the Problems/editor experience, not hide them in a canvas |
| Persistence and database tooling | Entities, repositories, changelogs, JPQL, database objects, reverse engineering, and diagrams can be explored together | Integrate with detected IntelliJ capabilities where possible; do not recreate database credential management in React |
| Diff and change review | IDE users inspect exact file changes before accepting them | Every mutation needs file list, semantic summary, textual diff, conflicts, and validation state |

### Adjacent Open-Tool Lessons

| Tool/workflow | Public capability | Lesson to adopt | Boundary |
|---|---|---|---|
| JHipster JDL / JDL Studio | Textual model for applications, entities, validations, and relationships; visual representation; export/import; generated Liquibase and UI | A reviewable, shareable model and explicit regeneration inputs scale better than transient wizard state | Do not introduce a second authoritative DSL in v1; export a plan/report before considering a durable DSL |
| Jeddict | Open visual JPA/Jakarta EE modeling, Java and DB reverse engineering, DDL generation, architecture visualization | Model-first and database-first views are complementary; a project-wide relationship diagram is valuable | Its NetBeans implementation and assets are not implementation inputs |
| OpenAPI Generator | Validate, configure, dry-run, generate, customize templates, and run from CLI/build tools | Generator settings must be explicit and reproducible; validation and dry-run belong before writes | Prefer invoking/configuring the established generator rather than building another OpenAPI implementation |
| IntelliJ Persistence/JPA tooling | Entity/repository/changelog tree, ER diagrams, DB reverse engineering, source navigation, JPQL console | Use platform integrations and avoid duplicate panes when IntelliJ already owns the capability | Ultimate-only capability availability must be detected; the plugin must degrade honestly |

## Table Stakes

Missing any P0 item makes the product unsafe or misleading. P1 items define a credible first public release for professional Jmix teams.

| Priority | Feature | Why Expected | Complexity | Testable acceptance |
|---|---|---|---|---|
| P0 | Supported-project discovery | All later designers depend on knowing the real module, source set, Jmix line, language, and artifact roots | High | Given single-module, multi-module, composite, Kotlin-DSL, and unsupported fixtures, the tool identifies modules and Jmix versions without writing files; ambiguous/unsupported projects show a blocking diagnostic |
| P0 | Semantic project index | Existing-project editing is the core workflow, not an optional enhancement | High | Index fixture entities, enums, DTOs, views/controllers, menus, roles, fetch plans, messages, changelogs, repositories, source sets, and add-on provenance; update affected entries after a file edit without a full rescan |
| P0 | Version capability adapters | Jmix 2.8 and 3.x share concepts but not all source/XML/build APIs | High | The same internal feature request produces adapter-specific valid output in 2.8 and 3.x fixtures; unsupported options are disabled with a reason |
| P0 | Change plan and exact diff | Professional users must know what automation will change | High | Every mutating command returns files created/modified/deleted, structured operations, before/after diff, warnings, validation results, and a stable plan fingerprint before Apply is enabled |
| P0 | Atomic apply, conflict detection, Undo/Redo | Partial or stale writes can corrupt valuable repositories | High | If any staged operation or validation fails, fixture bytes remain unchanged; editing a planned file before apply blocks with a conflict; successful multi-file apply is reverted by one IDE Undo |
| P0 | Path and ownership safety | Bridge or model input must never escape project/module roots or overwrite unknown artifacts | Medium | Traversal, separators in identifiers, symlink escape, wrong-module targets, duplicate output, and silent overwrite are rejected; unknown/manual content is preserved or the plan blocks |
| P0 | Typed, correlated workbench commands | Concurrent visual operations must be reliable and privileged actions constrained | Medium | Protocol version, request ID, schema validation, allowlist, payload limit, timeout/cancel, trusted origin, and structured error codes are integration-tested |
| P0 | Parse/validate before and after | A pretty diff is insufficient if Java/XML/Liquibase is invalid | High | Planned Java/Kotlin parses; XML is well formed and schema-compatible; IDs/references are resolvable; Liquibase structure validates; representative generated fixtures compile |
| P0 | Read-only/open-first behavior | Opening a project or designer must never modify it | Low | Opening/indexing every fixture causes zero filesystem, Gradle, VCS, or database mutation |
| P1 | Entity/DTO/enum round-trip designer | This is the foundational Jmix visual workflow | High | Open hand-edited Java and Kotlin models; add/edit/remove attributes, associations, IDs, traits, validations, indexes, instance names, comments, and captions; reopen with no model drift and no unrelated diff |
| P1 | Liquibase change derivation | Entity changes normally require coordinated schema evolution | High | Entity edits propose additive changesets and master inclusion; identifiers are collision-free; generated changelog validates for supported DB fixtures; drop/rename is never inferred without explicit confirmation |
| P1 | View list/detail vertical slice | CRUD view generation is the highest-value UI workflow | High | From an indexed entity, create list/detail descriptor and controller, data containers/loaders, actions, route, captions, and optional menu entry; compile/start fixture and reopen both in designer |
| P1 | Existing view round trip | Create-only scaffolding does not serve enterprise repositories | High | Load descriptor plus controller, show unknown/custom nodes without loss, edit supported properties/tree positions, apply, and reopen with an equivalent semantic model |
| P1 | Fetch-plan designer | Correct data loading is required for non-trivial views | Medium | Create/edit named plans, inheritance, nested properties, named reference plans, and fetch modes; invalid/cyclic references block apply |
| P1 | Menu designer with provenance | Menus combine application, framework, and add-on definitions | Medium | Display composite result and owned definition separately; inherited nodes are read-only; drag reorder creates minimal owned XML diff; duplicate IDs/references block apply |
| P1 | Resource-role designer | CRUD UI is unusable without corresponding permissions in secured applications | High | Model role identity/hierarchy and UI/entity/attribute/specific policies; show current versus inherited policies; default-deny remains explicit; generated role compiles |
| P1 | Localization coordination | Entity/view/menu captions are part of normal Jmix generation | Medium | Plan missing message keys across configured locales, preserve comments/order/manual values, and report incomplete translations without overwriting them |
| P1 | Native navigation and diagnostics | Users expect an IDE tool, not a disconnected embedded website | Medium | Go from semantic tree or designer selection to source; navigate linked artifacts; errors appear with file/line when available; context actions open the relevant designer state |
| P1 | Deterministic headless validation | Teams need CI confidence for generated/edited artifacts | Medium | A documented Gradle/CLI task validates fixture artifacts and produces machine-readable diagnostics without starting the IDE or requiring credentials |
| P1 | Accessibility and keyboard completeness | Enterprise IDE workflows cannot depend on pointer-only drag/drop | Medium | All designer operations have keyboard alternatives, labeled controls, visible focus, supported zoom, and pass the chosen automated accessibility gate plus manual keyboard audit |
| P1 | Large-project responsiveness | Blocking indexing or UI freezes make the plugin unusable | High | Publish and enforce startup/index/update/interaction budgets on a representative multi-module fixture; cancellable background work never performs long parsing/generation on the UI thread |

## Differentiators

These are valuable because they improve on one-way scaffolders without requiring breadth parity.

| Priority | Feature | Value Proposition | Complexity | Testable acceptance |
|---|---|---|---|---|
| V1 | Plan-first safety center | One place explains cross-artifact consequences before source changes | High | Changing an entity attribute shows coordinated source, DB, view, fetch-plan, localization, and security impacts; user may exclude only operations whose dependencies remain valid |
| V1 | No-unrelated-diff guarantee | Makes visual editing credible in hand-maintained enterprise code | High | Golden fixtures with comments, formatting, custom annotations/XML, and controller methods retain all unsupported/manual content; tests fail on unrelated AST/XML/property changes |
| V1 | Compatibility explanation | Users understand why a control or template differs between 2.8 and 3.x | Medium | Each adapter capability has support status and source; selecting an unavailable feature shows the target version and a concrete alternative |
| V1 | Artifact impact graph | Reveals relationships that are hard to infer from separate files | Medium | Selecting an entity/view/role shows navigable incoming/outgoing references with ownership and diagnostic status; graph results match fixture references |
| V1 | Reproducible plan export | Lets teams review automation in PRs and reproduce failures | Medium | Export contains tool/adapter versions, inputs, target fingerprints, operations, diagnostics, and redacted environment data; revalidation detects drift |
| Later | Project-wide entity relationship canvas | Accelerates understanding of large inherited models | High | Render searchable/filterable entity graph from source, support navigation, and plan relationship edits without making the diagram authoritative |
| Later | Read-only database drift explorer | Compares JPA, Liquibase, and live schema while keeping destructive actions explicit | High | For supported databases, classify missing/extra/changed objects with provenance; no SQL runs without a separate approved execution path |
| Later | Policy impact simulation | Shows effective access across role hierarchy before deployment | High | Given role fixtures, calculate effective UI/entity/attribute permissions and identify deny/allow provenance; results match Jmix integration tests |
| Later | Team policy packs | Enforces organization naming, generation, safety, and architecture rules | High | Checked-in policy config is schema-versioned; violations block or warn consistently in IDE and headless validation |
| Later | Extension SDK for add-on catalogs | Lets Jmix add-ons contribute components/templates/validators without core changes | High | A sample extension registers version-bounded metadata and validation without privileged arbitrary workbench commands |

## Anti-Features

| Anti-Feature | Why Avoid | What to Do Instead |
|---|---|---|
| Pixel-identical Jmix Studio clone | Creates IP/trademark risk and does not create user value | Original information architecture based on public workflows and IntelliJ conventions |
| Proprietary code, templates, icons, branding, or protocol reuse | Violates the clean-room boundary | Use public documentation/specifications and original implementation/assets; maintain source citations and contributor rules |
| License or entitlement bypass | Illegitimate and out of product scope | Interoperate with open Jmix framework artifacts; require users to license commercial runtimes/add-ons separately |
| Visual editor as the source of truth | Causes source/model divergence and merge problems | Parse source on open, plan semantic edits, and re-index after apply |
| Direct write / “Generate now” button | Hides scope and can partially corrupt projects | Plan → diff → validate → explicit apply → Undo |
| Whole-file regeneration of existing artifacts | Erases manual code, comments, formatting, and unknown extension points | PSI/DOM/properties-aware minimal edits; block when safe merge is impossible |
| “WYSIWYG” claim for a schematic React mock | Misleads users about actual Vaadin/Jmix rendering | Label schematic mode accurately; later offer runtime-backed preview with build/log state |
| Replacing IntelliJ's code editor, diff, database, secrets, VCS, or Gradle UI | Duplicates mature features and creates inconsistent behavior | Integrate or deep-link to native capabilities, with honest degradation when unavailable |
| Always-on database writes | Database access is high risk and environment-specific | Read-only introspection first; emit reviewed Liquibase changes; keep execution separate |
| Inferring destructive schema operations | Renames look like drop/add and can destroy data | Require explicit rename/drop intent, show data-loss warnings, and never auto-apply |
| Universal version generator | Scattered conditionals will silently emit wrong APIs | Versioned adapters, capability matrix, golden fixtures, and compile tests |
| Full Studio parity in v1 | Delays the trustworthy vertical slice and multiplies unsafe surface area | Finish entity → migration → view → menu → role round trip before adding more designers |
| BPMN editor presented as a BPM runtime | Authoring XML does not supply licensed runtime functionality | Defer; integrate only with a separately installed/licensed engine and state the boundary |
| Autonomous AI file writes | Expands the attack and correctness surface before deterministic operations are safe | Any future AI produces the same inspectable `ChangePlan` as a manual action |
| Cloud deployment in the core workbench | Requires credentials, vendor choices, and operational scope unrelated to source-safe design | Export deployable project changes; add provider integrations only as optional later modules |
| Telemetry or remote schema/model upload by default | Enterprise source/model metadata can be sensitive | Local-first operation, explicit opt-in telemetry, documented data inventory, and redaction |

## Feature Dependencies

```text
Buildable IntelliJ plugin
  → supported-project discovery
    → semantic project index
      → version capability adapters
        → artifact round-trip designers

Typed command protocol
  → change-plan engine
    → exact diff + validation
      → atomic apply + Undo
        → every mutating designer

Entity round trip
  → association/index/validation editing
    → Liquibase derivation
      → CRUD list/detail workflow
        → fetch-plan + menu + localization coordination
          → resource-role coordination

Semantic project index
  → native navigation/diagnostics
  → artifact impact graph
  → project-wide ER canvas
  → policy impact simulation

Headless validation + fixture matrix
  → supported compatibility claims
  → team policy packs
  → safe future AI assistance
```

## V1 Recommendation

V1 is a trustworthy vertical slice for existing applications, not broad parity.

### Include in V1

1. Safe project discovery/indexing for single-module, multi-module, and representative composite Jmix 2.8 and 3.x fixtures.
2. Typed bridge, plan/diff/validation, conflict detection, atomic apply, and IDE Undo.
3. Entity/DTO/enum Java and Kotlin round trip with associations, IDs, validations, indexes, captions, and supported version traits.
4. Coordinated additive Liquibase planning and changelog aggregation.
5. List/detail view creation plus existing descriptor/controller round trip for a deliberately bounded component set.
6. Fetch plans, owned menu edits, localization, and resource-role policies needed to complete that CRUD slice.
7. Native navigation, diagnostics, deterministic headless validation, accessibility, and performance budgets.

### Defer Until After V1

| Feature | Reason to defer |
|---|---|
| Cross-version project migration | Large independent risk surface; Jmix 3.0 migration includes build, source, UI, and runtime breaking changes |
| Full database reverse engineering | Requires driver/credential/security design and dialect matrix; partial read-only introspection can follow the safe data-model slice |
| Row-level predicate/JPQL role authoring | Requires deeper semantic validation and security testing than resource policies |
| Full JPQL visual designer/console | IntelliJ/Jmix coding assistance and database tools cover part of the need; focus on reference validation first |
| Repository method designer and update-service designer | Valuable after entity/view round trip is stable; version-specific APIs must be adapter-owned |
| OpenAPI workflow | Prefer a reproducible wrapper around OpenAPI Generator; it is not required for the first Jmix CRUD vertical slice |
| Hot deploy/runtime-backed preview | Process lifecycle, Vaadin frontend build, security, and compatibility complexity |
| Add-ons marketplace/project creation/templates | Project lifecycle breadth does not improve existing-repository safety |
| BPMN/DMN | Separate authoring/runtime/license domain |
| AI assistant/agent actions | Must wait until all automated edits are constrained by the proven plan engine |
| Cloud deployment | Operational and credential scope outside the core value |

## Roadmap-Oriented Acceptance Scenarios

These scenarios should be treated as release outcomes rather than UI tasks:

1. **Open without damage:** open each supported fixture, index it, browse artifacts, close the IDE; Git reports no changes.
2. **Existing entity round trip:** open a hand-edited entity, add a localized validated attribute and index, preview impacts, apply, compile, undo, and recover the exact original bytes.
3. **Association vertical slice:** add a bidirectional association; plan both entity edits, Liquibase foreign key/index, fetch/view implications, and captions; compile both Jmix lines.
4. **Existing CRUD enhancement:** add an entity attribute to existing list/detail views and fetch plan without losing custom XML/controller code.
5. **Secured navigation:** add a menu entry and role permission for a view; inherited/add-on items remain untouched; duplicate identifiers block.
6. **Stale-plan protection:** change a target source file after preview; Apply refuses and explains which fingerprint changed.
7. **Failure rollback:** inject failure on the last operation of a multi-file apply; repository remains byte-for-byte unchanged.
8. **Version boundary:** request a 3.x-only feature in a 2.8 fixture; no file changes occur and the UI gives a version-specific explanation.
9. **CI equivalence:** headless validation reports the same invalid reference/schema errors as the IDE.
10. **Large-project usability:** open and incrementally update the representative enterprise fixture within published time/memory/UI-freeze budgets.

## Confidence Assessment

| Area | Confidence | Notes |
|---|---|---|
| Jmix feature families | HIGH | Current official 2.8 and 3.x Studio catalogs and feature documentation agree |
| Jmix 3.0 differences | HIGH | Official 3.0 release/migration documentation lists IDE, JDK, Gradle, dependency, source, Studio, and component changes |
| IntelliJ-native expectations | HIGH | Official IntelliJ Platform and IntelliJ IDEA documentation |
| Adjacent-tool capabilities | HIGH | Official project documentation/repositories |
| V1 prioritization | MEDIUM | Strongly grounded in repository assessment and enterprise safety principles, but should be validated with target-team interviews |
| Exact UX and performance budgets | LOW until measured | Define quantitative thresholds during platform-foundation planning using representative fixtures |

## Sources

### Jmix (official)

- [Jmix Studio 3.x feature catalog](https://docs.jmix.io/jmix/studio/studio-features.html) — HIGH
- [Jmix Studio 2.8 feature catalog](https://docs.jmix.io/jmix/2.8/studio/studio-features.html) — HIGH
- [Jmix Studio overview and IntelliJ integration](https://docs.jmix.io/3.x/jmix/studio/index.html) — HIGH
- [Jmix 3.0 release, Studio improvements, upgrade actions, and breaking changes](https://docs.jmix.io/jmix/whats-new/release-3.0.html) — HIGH
- [Entity Designer](https://docs.jmix.io/3.x/jmix/studio/entity-designer.html) — HIGH
- [View Designer 2.8](https://docs.jmix.io/jmix/2.8/studio/view-designer.html) — HIGH
- [View Creation Wizard](https://docs.jmix.io/3.x/jmix/studio/view-wizard.html) — HIGH
- [Fetch Plan Designer](https://docs.jmix.io/3.x/jmix/studio/fetch-plan-designer.html) — HIGH
- [Menu Designer](https://docs.jmix.io/3.x/jmix/studio/menu-designer.html) — HIGH
- [Role Designer](https://docs.jmix.io/3.x/jmix/studio/role-designer.html) — HIGH
- [Coding Assistance](https://docs.jmix.io/3.x/jmix/studio/coding-assistance.html) — HIGH
- [Reverse Engineering](https://docs.jmix.io/3.x/jmix/studio/reverse-engineering.html) — HIGH
- [Composite Projects](https://docs.jmix.io/3.x/jmix/studio/composite-projects.html) — HIGH
- [OpenAPI integration workflow](https://docs.jmix.io/3.x/jmix/openapi-integration-guide/index.html) — HIGH

### IntelliJ / JetBrains (official)

- [Modifying PSI](https://plugins.jetbrains.com/docs/intellij/modifying-psi.html) — HIGH
- [Code Inspections and Intentions](https://plugins.jetbrains.com/docs/intellij/code-inspections-and-intentions.html) — HIGH
- [IntelliJ Persistence tool window](https://www.jetbrains.com/help/idea/persistence-tool-window.html) — HIGH
- [IntelliJ Database tool window](https://www.jetbrains.com/help/idea/database-tool-window.html) — HIGH
- [JPA Buddy capabilities maintained by JetBrains](https://www.jetbrains.com/help/idea/jpa-buddy.html) — HIGH

### Adjacent open tools (primary)

- [JHipster JDL](https://www.jhipster.tech/jdl/intro/) and [relationship generation](https://www.jhipster.tech/managing-relationships/) — HIGH
- [Jeddict open-source modeler repository](https://github.com/jeddict/jeddict) — HIGH
- [OpenAPI Generator usage, validation, dry-run, and generation](https://openapi-generator.tech/docs/usage/) — HIGH

### Project-local evidence

- `.planning/PROJECT.md`
- `JMIX_STUDIO_ASSESSMENT.md`
- `.planning/codebase/ARCHITECTURE.md`
- `.planning/codebase/CONCERNS.md`
- `.planning/codebase/CONVENTIONS.md`
- `.planning/codebase/INTEGRATIONS.md`
- `.planning/codebase/STACK.md`
- `.planning/codebase/STRUCTURE.md`
- `.planning/codebase/TESTING.md`

## Open Questions for Phase-Specific Research

- What exact Java/Kotlin PSI/UAST subset can be safely round-tripped while preserving uncommon annotations and code style?
- Which IntelliJ product editions and bundled plugins are required for database/persistence integration, and what is the Community-edition degradation path?
- Which Jmix 2.8/3.x XML schemas and add-on component catalogs can be resolved directly from the target classpath?
- What representative enterprise fixture size should define indexing, memory, and interaction budgets?
- Should the exported change plan remain a review artifact only, or later become a checked-in declarative automation format?
- Which database dialects are mandatory for the first Liquibase validation matrix?


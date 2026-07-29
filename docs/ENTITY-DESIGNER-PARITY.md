# Entity Designer Parity

Status date: 2026-07-30

This is the evidence and remaining-work contract for `STUDIO-CORE-001` and
`STUDIO-CORE-011`. It follows the official Jmix
[Entity Designer](https://docs.jmix.io/jmix/studio/entity-designer.html),
[entity model](https://docs.jmix.io/jmix/2.7/data-model/entities.html),
[enumeration model](https://docs.jmix.io/jmix/2.8/data-model/enumerations.html),
[datatype model](https://docs.jmix.io/jmix/data-model/datatype.html), and
[reverse-engineering workflow](https://docs.jmix.io/jmix/2.7/studio/reverse-engineering.html).

## Implemented in the current entity milestone

- JPA entity, mapped-superclass, embeddable, DTO and typed Jmix enumeration
  generation.
- UUID, Long, Integer, String and configured embedded identifier classes, with
  matching repository identifier types.
- Scalar metadata for String, Character, Boolean, Integer, Long, Double,
  BigDecimal, `java.util.Date`, Java time and offset time types,
  `java.sql.Date`, `java.sql.Time`, UUID, URI, byte array, `FileRef`, Jmix
  enums, embedded types and explicit custom Java/SQL datatypes.
- Correct Jmix `EnumClass<String|Integer>` generation. Persistent entity fields
  store the enum ID and expose null-safe enum accessors; they do not use JPA
  `@Enumerated`.
- Jmix DTO metadata through `@JmixEntity`, `@JmixId`, `@JmixProperty`,
  `@JmixGeneratedValue`, `@Store`, system-level metadata and optional
  read-only accessors without leaking JPA annotations into DTOs.
- Instance-name attributes and patterns; system-level, comment, LOB,
  property-datatype, dependent-property, annotated-properties-only and
  read-only metadata.
- Bean-validation annotations, parameters, escaped messages and validation
  groups.
- Relationships, compositions, collection type, ownership, cascade, orphan
  removal, join tables, delete policy and Jmix cross-data-store to-one
  references.
- Indexes, unique constraints, database views, DDL policy, protected unmapped
  schema objects, repositories and localized captions.
- Liquibase type alignment and remarks for the new scalar types, Jmix enum ID
  type and explicit custom SQL definitions.
- Existing Java source updates remain revision-bound, previewed and atomic.
  Additions and supported persistence-metadata changes preserve handwritten
  bodies and unmanaged annotations and are combined with rollback-capable
  Liquibase changes.
- New Java/Kotlin language selection is module-aware: entities, typed
  `EnumClass` declarations, repositories and repository activation source are
  generated directly into the matching production source set.
- Existing handwritten Kotlin entities now participate in additive round trip.
  The schema workspace reconstructs Kotlin IDs, columns, nullability, lengths,
  precision, scale, persistence and relationships; additions preserve manual
  bodies and imports, read current unsaved IntelliJ documents, reject stale
  indexed revisions, validate the resulting Kotlin PSI and share the atomic
  rollback-capable Liquibase plan. Managed nullability, uniqueness, length,
  precision and scale changes preserve unknown annotation arguments and
  generate checked forward/rollback migrations. Explicit scalar column renames
  now update Java/Kotlin `@Column` mappings and generate a preconditioned
  Liquibase `renameColumn` with reverse rollback in the same atomic preview.
  Explicit owning-side `ManyToOne` and `OneToOne` join-column renames receive
  the same treatment for Java and Kotlin: only the literal `@JoinColumn(name)`
  changes, every other handwritten annotation argument is preserved, and
  old-exists/new-absent preconditions plus reverse rollback are generated.
  Target/cardinality/ownership/cascade/fetch/constraint changes, inferred
  columns, inverse/collection/join-table/cross-store mappings, collisions,
  disabled DDL, type/removal and unsafe narrowing fail closed instead of
  guessing.
- Existing scalar and relationship properties with stable explicit physical
  mappings can launch IntelliJ's native rename processor directly from Entity
  Designer. The exact revision and live Java/Kotlin PSI declaration are
  resolved first; collisions, inferred scalar/join mappings, stale source and
  read-only declarations fail closed. JPA `mappedBy` strings now provide native
  Java/Kotlin navigation, completion, indexed Find Usages and rename references;
  usage preview also includes plugin-contributed FlowUI, fetch-plan, JPQL and
  security references. A missing existing property in the normal update request
  is rejected rather than being misread as an addition.
- Existing Java/Kotlin attributes can launch IntelliJ Safe Delete directly from
  Entity Designer after exact-revision, writable-declaration and stable physical
  mapping checks. IntelliJ and plugin-contributed Jmix references participate in
  the usage preview. The physical column is deliberately retained after source
  deletion so production data cannot be silently dropped; the UI explains the
  two-stage retirement and requires a later schema/data audit.
- Existing entities can inspect their live mapped table through the
  project-owned JDBC driver and the active profile configuration. The backend
  supports datasource-only stores with Liquibase intentionally disabled,
  ordered active profiles, property/environment placeholders, driver-specific
  connection/read timeouts, read-only metadata access and redacted failures.
  It returns a credential-free, digest-stamped snapshot of columns, primary
  keys, imported foreign keys, indexes and dependency tables. The responsive
  in-IDE review surface distinguishes mapped, primary-key, generated,
  relationship and unsupported columns; lets developers edit proposed
  property names/types; and stages only selected additions into the existing
  revision-bound Java/Kotlin atomic source/Liquibase preview. Known foreign
  keys map to existing entities, and vendor-specific types fail closed until
  the developer chooses an explicit supported datatype. An optional schema
  selector resolves multi-schema databases; duplicate table names without an
  explicit schema fail closed instead of depending on driver return order.
- Existing attributes now have a connected, revision-bound propagation
  workflow beyond Studio's basic "Add to Views" operation. The impact review
  discovers every matching FlowUI instance/collection container, form, grid,
  inline fetch plan, shared fetch plan, default entity message bundle and
  exact non-wildcard resource-role attribute policy across indexed modules.
  It inserts type-appropriate bound fields, grid columns and association fetch
  properties, understands `_base` coverage for local scalar attributes,
  expands self-closing XML without reformatting handwritten source, creates or
  extends the default message bundle, and can extend several existing
  attribute policies in one role. Recommended presentation/localization
  targets are preselected; privilege-expanding role changes are supported but
  visibly marked and never preselected. Locale-specific bundles are reported
  for human translation rather than populated with guessed text. All selected
  files share one digest-bound preview/apply, stale target rejection, atomic
  rollback and post-apply idempotency contract.
- The designer consumes the connected application graph and displays entity
  consumers across views, services, security, REST, workflow, menus and
  migrations.
- The native web surface has entity-kind-aware controls, a dedicated enum
  workspace, useful empty-state guidance and responsive pane switching.

## Claim boundary

This milestone materially expands the entity model and generator but does not
yet make `STUDIO-CORE-001` or `STUDIO-CORE-011` STRONG. Those claims remain
blocked until all of the following pass:

1. Complete schema-aware Java and Kotlin PSI refactoring for handwritten
   entities. Stable-mapping scalar and relationship property rename delegates
   to IntelliJ usage preview, including native `mappedBy` references, and
   explicit scalar and owning to-one join-column physical rename is atomic
   with checked Liquibase rollback. Combined property-and-physical mapping
   changes, relationship shape changes, type change and the data-audited
   physical retirement stage after native Safe Delete remain.
   Additive and managed-mapping Java/Kotlin round trip is implemented.
2. The first partial live-database merge is implemented for a selected
   existing entity/table and missing columns. Complete table/view selection,
   composite-key and join-table mapping, all FK dependency import, database
   catalog browsing, saved mapping overrides and repeatable regeneration
   across schema evolution remain.
3. Deeper propagation remains for controller code that constructs components
   dynamically, inherited fetch-plan coverage, fragment-owned bindings and
   translation-catalog/provider integration. Static FlowUI forms/grids,
   inline/shared fetch plans, default messages and exact resource-role
   attribute policies are implemented in one impact-reviewed atomic plan.
4. Composite-ID migration generation from a proven embeddable structure.
5. Populated PostgreSQL, MySQL/MariaDB, MSSQL and Oracle reverse-engineering
   and migration fixtures.
6. Installed-IDE interaction, undo, accessibility, large-project latency,
   memory and leak evidence.

No release or marketing claim may describe complete Studio Entity Designer
parity until those gates are closed.

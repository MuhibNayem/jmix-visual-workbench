# Entity Designer Parity

Status date: 2026-07-30

This is the evidence and remaining-work contract for `STUDIO-CORE-001` and
`STUDIO-CORE-011`. It follows the official Jmix
[Entity Designer](https://docs.jmix.io/jmix/studio/entity-designer.html),
[entity model](https://docs.jmix.io/jmix/2.7/data-model/entities.html),
[enumeration model](https://docs.jmix.io/jmix/2.8/data-model/enumerations.html),
[datatype model](https://docs.jmix.io/jmix/data-model/datatype.html), and
[reverse-engineering workflow](https://docs.jmix.io/jmix/2.7/studio/reverse-engineering.html).
Repository behavior follows the official
[Data Repository](https://docs.jmix.io/jmix/data-access/data-repositories.html)
contract and
[Data Repository Wizard](https://docs.jmix.io/jmix/studio/data-repository-wizard.html).

## Implemented in the current entity milestone

- The designer now indexes existing Java and Kotlin
  `JmixDataRepository<Entity, Id>` interfaces and reconstructs derived and
  explicit JPQL methods, typed value/page/sort/fetch-plan/repository-context
  parameters, nullable contracts, named bindings, fetch plans,
  `@ApplyConstraints`, aggregate `KeyValueEntity` properties, query hints and
  developer documentation. Handwritten/custom/default/native-query members
  remain visible source-owned evidence instead of being silently rewritten.
  New repositories, additive methods and the supported metadata of existing
  methods share one responsive visual editor. Existing callable contracts,
  documentation and binding declarations remain native-refactoring owned.
- Repository authoring now uses one entity-aware semantic model in the backend,
  the responsive designer and native IntelliJ references. Derived queries
  resolve inherited and nested properties, operators, arity, value types,
  ordering and result shape. JPQL validates entity roots, joins (including
  outer joins), aliases, mapped paths, parameters, aggregate/scalar/entity
  projections, query properties and fetch-plan compatibility. Existing
  source-owned methods remain visible advisory evidence: an unsupported or
  invalid handwritten method no longer disables preview for an independent
  valid additive method, while every new draft still fails closed on a
  blocking diagnostic.
- Java and Kotlin `@Query` strings now provide native entity/property
  completion, segment navigation, Find Usages, inspections, quick fixes and
  rename through the same PSI entity model used by FlowUI, fetch-plan and
  security references. Escaped Java strings, Kotlin strings, qualified entity
  names, nested paths and class/property rename are covered on both IDE hosts.
  Named JPQL parameters and Spring Data `@Param` literals now form one native
  Java/Kotlin symbol: completion, navigation, unresolved diagnostics, quick
  fixes, Find Usages and rename update every query occurrence and the explicit
  binding without renaming an intentionally different JVM parameter.
  This remains a bounded JPQL analyzer, not a complete JPQL language
  implementation: subqueries, constructor projections, `TREAT`, map functions,
  complete correlated-alias scoping and language injection are explicit
  certification gaps.
- Four release-blocking round-trip defects are closed. Java embedded mappings
  collect nested override imports; repository fetch-plan annotations use a
  qualified name when the `io.jmix.core.FetchPlan` parameter type would
  collide; changing a subtype draft to a hierarchy root clears its superclass;
  and the association-override editor preserves and independently edits every
  composite join column. A real narrow-browser journey authored and retained
  two join columns. Draft rows now expose an explicit keyboard-accessible
  Details control, and attribute-detail fields no longer create an off-screen
  implicit grid column.
- Java and Kotlin generation now validates duplicate JVM signatures, result and
  parameter types, paging contracts, exact named/positional JPQL bindings,
  aggregate projections and read-only single-entity SELECT semantics.
  Repository- and method-level constraint bypass is conspicuous in the UI and
  emits only explicit `@ApplyConstraints(false)`. Unsupported native Spring
  Data JPA queries fail closed because their execution and Jmix DataManager
  security semantics cannot be guaranteed.
- Existing handwritten repositories receive revision-bound additive imports
  and declarations plus exact annotation-range updates for modeled `@Query`,
  `@FetchPlan`, `@ApplyConstraints` and `@QueryHints` metadata. The callable
  declaration is never regenerated: `@Override`, modifiers, documentation,
  custom annotations, parameter nullability/bindings, comments and formatting
  remain byte-preserved. A comment inside a changed annotation or any unknown
  construct makes that metadata read-only. Rename, delete, reorder, callable
  changes, stale revisions and signature collisions route to native
  refactoring or fail closed. Creation also emits module/language-correct
  repository activation when the indexed project has none.
- FlowUI collection loading can now be wired to the official
  `findAll(Pageable, JmixDataRepositoryContext)` delegate contract, preserving
  filter, paging, sorting, fetch-plan and hint context. Detail saving emits a
  deliberately bounded one-entity/no-removal delegate and rejects aggregate
  semantics in favor of a transactional update service. Java and Kotlin
  controllers both receive revision-bound injection and delegates; Kotlin
  insertion is anchored to Kotlin PSI class-body evidence and uses
  collision-proof qualified types. The backend evaluates the exact invoked
  `findAll` or `save` constraint policy, honors method-over-repository
  precedence, follows resolvable parent interfaces, rejects unproven custom
  hierarchies during indexing and exposes no bridge-controlled unconstrained
  override.
- Entity Designer pane selection now responds to its actual IntelliJ/JCEF
  container width rather than the outer browser viewport. At 360 and 768
  pixels, configuration, attributes and preview are keyboard-selectable full
  panes; at 1,280 pixels the complete three-pane workspace returns. Real
  browser checks prove equal shell client/scroll widths and an unoverflowed
  repository panel at every breakpoint.
- Real Jmix entity Java and Kotlin files now open with a native IntelliJ
  **Design** editor beside the normal source editor. Eligibility is
  project-contained and annotation-aware; the editor passes only a
  project-relative locator and the SHA-256 revision of the current IntelliJ
  document, including unsaved changes. The isolated packaged route resolves
  exactly one indexed entity at that revision, locks entity-source selection,
  and fails closed on stale, missing, or ambiguous source evidence. Reselecting
  Design republishes the current document revision. New Entity, New View, and
  CRUD actions now use a retained project navigation channel and open their
  exact workspaces even when the tool window has not yet been created.
  Registrations exist in the shared descriptor and both host-specific
  descriptors; build-time source parity and nested packaged-ZIP checks reject a
  missing entity editor or navigation service. Both installable JAR
  descriptors were inspected after assembly, 290 tests and three packaged-host
  smoke tests pass on each supported IntelliJ lane, and the native route
  completed preview interaction with zero shell overflow at a 360-pixel
  development editor width.
- New Java and Kotlin entities now author JPA inheritance as an explicit root
  or subtype contract. The designer covers `SINGLE_TABLE`, `JOINED`, and
  `TABLE_PER_CLASS`, discriminator column/type/length/value metadata, subtype
  parent evidence, and JOINED primary-key join columns. Generators avoid
  duplicating inherited ID, version, and trait members; the schema workspace
  reconstructs the same hierarchy from handwritten Java/Kotlin source with a
  bounded parent walk. Liquibase emits proven root discriminator and JOINED
  subtype PK/FK changes, while subtype DDL that lacks complete inherited-column
  evidence fails closed instead of inventing a destructive schema.
- Embedded mappings now support visually authored, nested
  `@AttributeOverride` and `@AssociationOverride` paths, multi-column join
  overrides, explicit SQL/column metadata, and indexed embeddable-member
  assistance. Java/Kotlin generation and schema parsing share the same model.
  A new embedded attribute can be added to an exact-revision handwritten
  entity without replacing manual code; its explicit scalar/join columns and
  foreign keys enter the same checked forward/rollback Liquibase preview.
  Existing hierarchy declarations and already-declared embedded mappings are
  intentionally source-derived/read-only until complete parent/child and
  physical-column inventory makes shape-changing edits provably safe.
- The inheritance and embedded-override controls are independent responsive
  components rather than another expansion of the central designer. Real
  browser interaction covered root/subtype switching, embedded override
  creation, and a 1,280-pixel no-overflow shell. Both real host descriptors now
  contain the native entity editor registration, and host-level packaged
  descriptor tests assert it directly in addition to the source-parity and
  nested-ZIP gates.
- Existing Java/Kotlin source contracts now reopen with declared traits,
  superclass, interfaces, lifecycle callbacks and `@EntityListeners` intact
  instead of being reconstructed as empty designer state. A bounded,
  cycle-safe inheritance walk exposes inherited attributes and trait origins
  with the exact declaring entity and depth. The evidence is read-only for
  handwritten sources, trait-managed fields can be hidden from the everyday
  attribute list, and the native/web designer share the same model.
- Attribute copy is available for new and handwritten entities. Copies receive
  collision-free logical names, regenerated scalar/join mappings, deep-copied
  validation/annotation collections, cleared uniqueness expansion and no
  inherited ownership choreography. Handwritten declarations remain
  order-locked; newly staged drafts can be reordered without claiming that
  source declarations moved. New-entity lifecycle callbacks and JPA
  `@EntityListeners` classes are visually authored, while existing declarations
  remain source-derived and protected. Browser interaction verified copy, safe column
  regeneration, draft reorder, inherited evidence expansion and zero shell
  overflow at a 360-pixel embedded width.
- Existing persistent entities now expose a separate Jmix data-event listener
  workflow. It creates current Spring `EntitySavingEvent`,
  `EntityLoadingEvent`, and before/after-commit `EntityChangedEvent` handlers
  in Java or Kotlin, derives the target module and source root from the exact
  indexed entity revision, previews one create-only atomic change, and rejects
  stale entities, source collisions, unsupported model kinds and incoherent
  transaction choices. After-commit data access is an explicit
  `REQUIRES_NEW` option rather than a hidden assumption. Indexed listener
  methods appear beside the entity, navigate to source, and every generated
  handler is linked back through `LISTENS_TO`. The service is registered in all
  three plugin descriptors. Five focused contracts pass on both IntelliJ hosts;
  real-browser preview/apply and the complete creator passed at desktop and
  360-pixel widths with no overflow or diagnostics.
- Indexed entities now open a dedicated list/detail-view workflow directly
  from both the normal workbench and the native IntelliJ entity editor. The
  launch crosses surfaces only with an exact revision-bound entity locator;
  stale or non-entity locators and attempts to elevate web content into native
  editor surfaces fail closed. Existing-entity mode never recreates the
  handwritten Java/Kotlin class or its table migration, rejects any visual
  entity-contract drift, and lets developers select menu, messages, fetch
  plans, repositories and security artifacts independently. A new full-access
  role is off by default for existing entities and requires explicit opt-in.
  Connected impact cards navigate to exact source, while indexed FlowUI
  descriptors open directly in the visual designer. Dual-host tests prove
  exact-revision routing and generation exclusion; browser interaction proves
  the security-role opt-in default, no entity or Liquibase entry in preview,
  native-editor routing, and a 360-pixel shell with
  equal visible/scroll widths and no runtime warnings.
- JPA entity, mapped-superclass, embeddable, DTO and typed Jmix enumeration
  generation.
- UUID, Long, Integer, String and configured embedded identifier classes, with
  matching repository identifier types. Java and Kotlin embeddable identifier
  generation now emits `Serializable` value objects with deterministic
  member-based `equals()`/`hashCode()` rather than structurally valid but
  identity-unsafe placeholder classes.
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
  schema objects, repositories and localized captions. Generated Java/Kotlin
  `@Table` mappings preserve explicit table, schema, and catalog names.
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
  Evidence-backed owning to-one mappings can also change optionality in either
  direction, including in the same atomic plan as an explicit join-column
  rename. Complete physical inventory must prove the current non-key column,
  nullability, uniqueness and one matching foreign key. Contraction checks
  existing null data under the old column name before rename; constraints are
  applied under the new name and rollback reverses the order. Physical
  destination collisions, target/unsupported-cardinality/ownership changes,
  inferred columns, inverse/collection/join-table/cross-store mappings,
  disabled DDL, type/removal and unsafe combined changes fail closed instead of
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
- Logical-property and explicit physical-column rename can now be coordinated
  safely instead of forcing a developer to choose one refactor surface. The
  bridge first performs a read-only native rename preflight, then the designer
  previews and applies only the scalar `@Column(name)` or owning to-one
  `@JoinColumn(name)` change plus reversible Liquibase, refreshes the indexed
  source revision, and finally revalidates and opens IntelliJ's real usage
  preview for the logical Java/Kotlin/Jmix rename. Canceling the final IDE
  refactor leaves a valid explicit physical mapping rather than a half-renamed
  source tree.
- Established bidirectional owning many-to-one/one-to-many and
  one-to-one/one-to-one pairs now transform together when the owning side
  narrows or widens. The planner derives the inverse role internally, updates
  both Java/Kotlin annotations and scalar/collection property types in one
  revision-bound preview, preserves initializers, manual methods and unmanaged
  annotations, and generates DDL only for the proven owning join column.
  Ambiguous inverses, stale sources, cross-store targets, unsupported shapes
  and directly submitted internal choreography roles fail closed. Java fields
  with ordinary single-line initializers are included in handwritten entity
  discovery. The browser completed the coordinated physical/native-rename
  handoff at desktop width and the bidirectional selection flow at 1440, 768,
  480 and 360 pixels with no horizontal overflow or diagnostics; protected
  existing attributes expose a dedicated keyboard-focusable inspector at
  compact widths.
- Existing Java/Kotlin attributes can launch IntelliJ Safe Delete directly from
  Entity Designer after exact-revision, writable-declaration and stable physical
  mapping checks. IntelliJ and plugin-contributed Jmix references participate in
  the usage preview. The physical column is deliberately retained after source
  deletion so production data cannot be silently dropped; the UI explains the
  staged retirement. After the source refactor, complete high-confidence
  Liquibase inventory exposes an unmapped, non-key, non-unique, non-FK column
  as a reversible quarantine rename. The generated retired name is deterministic
  and portable, old-exists/new-absent preconditions and reverse rollback are
  mandatory, and explicit `@DdlGeneration(unmappedColumns=...)` protection
  disables the suggestion. Final deletion remains a retention-policy decision.
- Existing handwritten scalar attributes can request IntelliJ's project-wide
  Type Migration from Entity Designer with a selected target type. The backend
  resolves the exact current Java field or Kotlin light field, rejects stale,
  inferred-column, identifier, relationship, custom and ambiguous declarations,
  binds the refactoring to project scope and always opens usage preview. Before
  launch it classifies the persistence impact as source-only, expand/contract,
  externally managed or incomplete evidence; reports the real Liquibase column
  type plus primary-key, uniqueness, index and incoming/outgoing foreign-key
  dependencies; and states when conversion is not automatically reversible.
  Source-only changes such as compatible persisted representations open the
  real refactoring preview without inventing a schema rewrite. Expand/contract,
  externally managed and incomplete-evidence outcomes fail closed before the
  source refactor opens. Proven lossless conversions can now create the
  expansion stage: a deterministic portable shadow column, transactional
  non-null backfill, restored mandatory constraint, old-column/shadow-column
  existence preconditions that HALT on failure or evaluation error, and custom
  rollback that drops only the shadow. Qualified `SCHEMA.TABLE` mappings are
  preserved. The original column remains authoritative and untouched.
  Unproven or database-specific conversions fail closed. The cutover gate now
  resolves the active Jmix data-store profile without exposing credentials,
  loads the project's own JDBC driver, verifies the deployed original and
  deterministic shadow columns, checks target SQL type/capacity, and rejects
  any row whose shadow is null or differs from the authoritative original.
  Successful evidence creates a random, memory-only, exact-revision/property/
  type/schema-bound capability that expires after twenty minutes. Only that
  capability can open IntelliJ Type Migration for an expand/contract outcome.
  A bounded memory-only recovery identity permits live re-verification after a
  long IntelliJ review without persisting credentials or weakening expiry.
  After the developer applies the native Java/Kotlin usage preview and refreshes
  the index, the final cutover preview rechecks live value parity and proposes
  one exact edit to the existing `@Column(name)` literal; every other handwritten
  annotation argument and source byte is retained, the preview is revision
  bound, and apply is atomic. Later contraction and final deletion remain
  separate retention gates because reversing the declaration cannot restore
  intentionally retired data.
- Existing handwritten `@Table` mappings now round-trip `name`, `schema`, and
  `catalog` for Java and Kotlin instead of collapsing qualified mappings to a
  table name. Existing entities can browse live catalogs, schemas, tables, and
  views, then inspect any selected object through the
  project-owned JDBC driver and the active profile configuration. The backend
  supports datasource-only stores with Liquibase intentionally disabled,
  ordered active profiles, property/environment placeholders, driver-specific
  connection/read timeouts, read-only metadata access and redacted failures.
  Browsing is bounded, filterable, case-tolerant across JDBC implementations,
  and credential-free; large result sets are explicitly reported as truncated.
  Inspection returns a credential-free, digest-stamped snapshot of columns, primary
  keys, imported foreign keys, indexes and dependency tables. The responsive
  in-IDE browser uses a fluid two-column/intermediate layout and table cards
  rather than viewport-forced cramped controls. Its review surface distinguishes mapped, primary-key, generated,
  relationship and unsupported columns; lets developers edit proposed
  property names/types; and stages only selected additions into the existing
  revision-bound Java/Kotlin atomic source/Liquibase preview. Known foreign
  keys map to existing entities, and vendor-specific types fail closed until
  the developer chooses an explicit supported datatype. An optional schema
  selector resolves multi-schema databases; duplicate table names without an
  explicit schema fail closed instead of depending on driver return order.
  Arbitrary tables remain useful for read-only metadata comparison, but every
  checkbox and import action stays locked unless the backend resolves the exact
  expected entity, data store, table, explicit/default schema, and catalog.
  Same-named tables in another schema therefore cannot be imported into the
  selected entity. Browser checks covered both the locked arbitrary-table path
  and the exact mapped-table path, with no runtime errors.
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
   with checked Liquibase rollback. Combined logical-property and explicit
   physical mapping rename is implemented as a safe staged choreography, and
   established bidirectional owning many-to-one/one-to-many and
   one-to-one/one-to-one cardinality changes update both sources atomically.
   Other relationship shapes, scalar contraction and final post-retention
   physical deletion remain.
   Deployed-data/type/value verification and exact
   Java/Kotlin mapping cutover are implemented with an expiring backend
   capability. The non-destructive expansion stage, Native
   project-wide Java/Kotlin type-migration preview with physical dependency
   classification, Native Safe Delete and reversible quarantine are implemented.
   Additive and managed-mapping Java/Kotlin round trip is implemented.
2. Credential-safe live catalog/schema/table/view browsing, arbitrary read-only
   inspection, and exact-mapping-gated missing-column merge are implemented.
   Composite-key and join-table mapping, recursive FK dependency import, saved
   mapping overrides and repeatable regeneration across schema evolution
   remain.
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

Repository creation and additive Java/Kotlin method authoring are implemented.
Remaining repository work is IntelliJ-native injection/refactoring assistance,
view delegate integration, safe mutation of existing source-owned methods and
runtime integration certification against representative Jmix applications.

No release or marketing claim may describe complete Studio Entity Designer
parity until those gates are closed.

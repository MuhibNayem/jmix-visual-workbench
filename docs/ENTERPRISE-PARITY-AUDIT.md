# Enterprise Parity Audit

Audit date: 2026-07-30

This audit compares the implemented workbench with
`FULL-GUI-DEVELOPMENT-REQUIREMENTS.md`,
`JMIX-STUDIO-SURPASS-LEDGER.md`, the supplied 16-module payroll architecture
brief, and the supplied 2026-07-29 Jmix Studio comparison. Status is based on
source inspection, packaged-web verification, browser interaction, and the
IntelliJ 2025.3/2026.2 test suites.

## Verdict

The workbench is an advanced, source-safe Jmix engineering plugin, but it is
not yet a complete implementation of every requirement in the full-GUI
contract. It already covers a useful paid-Studio-like core and several
enterprise capabilities beyond basic Studio scaffolding. It must not yet be
marketed as replacing all Java/Kotlin development or as a complete visual
operations platform.

The atomic `STUDIO-*`, `SURPASS-*`, and `CERT-*` entries in the surpass ledger
are now the strict claim gates. The first production tranche of native
IntelliJ coding assistance is implemented, while complete Jmix semantic
coverage, full-catalog database reverse engineering, project/add-on/upgrade
management, OpenAPI clients, deployment, compatibility certification and
signed distribution remain explicit requirements rather than implied backlog.

The native and visual UI-policy contract is grounded in the official Jmix 2.7
[UI Constraints documentation](https://docs.jmix.io/jmix/2.7/uiconstraints/index.html),
including its Enterprise-subscription status and its documented component
forms (`save`, `userGrid.edit`, `addressFragment.cityField`). Fragment
composition follows the official
[FlowUI fragment contract](https://docs.jmix.io/jmix/flow-ui/fragments/fragments.html)
and declarative `fragment class="…"` descriptor relationship.
Native controller typing and handler discovery follow the official
[visual-component contract](https://docs.jmix.io/jmix/flow-ui/visual-components.html),
[Subscribe API](https://docs.jmix.io/api/2.7/io/jmix/flowui/view/Subscribe.html)
and
[StudioComponent metadata API](https://docs.jmix.io/api/2.7/io/jmix/flowui/kit/meta/StudioComponent.html).
The project dependency graph, rather than a plugin-bundled tag table, supplies
the authoritative XML element, injection class and custom-subscription
metadata, so supported add-ons participate in the same native contracts.
REST service mapping follows the official Jmix
[Services API contract](https://docs.jmix.io/jmix/2.7/rest/business-logic.html)
and
[REST configuration properties](https://docs.jmix.io/jmix/2.7/rest/app-properties.html):
XML parameter names are public request names, parameter types are optional
unless same-arity overloads require disambiguation, and configuration resources
are classpath-relative. Entity-event transaction diagnostics follow the
official
[Jmix entity-event contract](https://docs.jmix.io/jmix/2.7/data-access/entity-events.html).
Spring `@Bean` aliases and factory products follow the public
[Spring bean naming contract](https://docs.spring.io/spring-framework/reference/core/beans/java/bean-annotation.html).

Entity-generation evidence update (2026-07-30): Java and Kotlin generated
embeddable identifiers now implement `Serializable` with deterministic
member-based equality/hash semantics, and generated `@Table` mappings retain
explicit schema and catalog qualifiers. Composite-ID schema migration proof is
still tracked as a separate gate.

Relationship-evolution evidence update (2026-07-30): established local owning
many-to-one and one-to-one mappings can now change between optional and required
without replacing the relationship. Complete Liquibase evidence must prove the
exact unqualified non-key column, its current nullability/uniqueness and one
matching foreign key. Java/Kotlin annotations and checked forward/rollback
nullability changes are previewed and applied together; optional-to-required
halts on existing null references, while required-to-optional rollback fails
safely if later data introduces nulls. An explicit collision-free join-column
rename can participate in that same ordered change set: data is checked under
the old name, the column is renamed, its constraint changes under the new name,
and rollback reverses those operations.

Entity-refactor evidence update (2026-07-30): a logical Java/Kotlin property
rename can now be coordinated with an explicit scalar or owning to-one physical
column rename. A read-only IntelliJ rename preflight runs before any write; the
digest-bound mapping/Liquibase plan is then applied and re-indexed before the
plugin revalidates and opens IntelliJ's native usage preview. This deliberately
staged order keeps every intermediate checkout runnable and safe if the
developer cancels the final logical rename. Established bidirectional
many-to-one/one-to-many and one-to-one/one-to-one pairs also change cardinality
as one two-source transaction: the inverse annotation, Java/Kotlin
scalar/collection type and initializer are updated while DDL remains confined
to the proven owning join column. Handwritten Java fields with single-line
initializers are now indexed. Focused browser evidence completed the coordinated
rename handoff at 1440 pixels and exercised bidirectional-cardinality selection
at 1440, 768, 480 and 360 pixels without overflow, clipping or diagnostics.
At compact widths, protected attributes use an explicit keyboard-focusable
inspection control rather than relying on a hard-to-hit table-row target.

| Requirement area | Status | Implemented evidence | Remaining enterprise gap |
|---|---|---|---|
| Existing multi-module projects | Strong | Composite Gradle topology, source sets, typed exact custom roots (`srcDir`, `srcDirs`, `setSrcDirs`, assignment and nested-DSL forms), generated roots, source-less/orphan/recovered modules, common `projectDir` remaps (`file`, `File(rootDir, …)`, `settingsDir.resolve`, layout directory APIs), IntelliJ and static dependency edges, deterministic ownership conflict handling, YAML/XML/SQL/Java/Kotlin/Groovy parsing, incremental cache, explicit partial-index health, and a verified remapped 16-module dependency chain. Recovered roots now remain authoritative destinations for Java/resource/test generation, FlowUI/BPMN/DMN, security, server logic, scenarios, Liquibase classpaths, base-package inference and runtime configuration discovery | Validate against more real 3,000+ file repositories, included builds outside registered IntelliJ roots, and build logic whose module/source topology is created dynamically at Gradle execution time |
| Connected application graph | Strong | Cross-module artifacts and relationships for entities, views, controllers, reusable business rules, services, security, REST, workflows, menus, configuration, jobs, reports, integrations and Liquibase; bounded five-level connected behavior/change-impact paths | Deeper method-level control/dataflow and provider-specific runtime semantics |
| Safe visual changes | Strong | Typed models, immutable digest-bound previews, stale-source rejection, atomic workspace changes, history/undo, read-only lock for unsupported BPMN/source constructs | Broaden PSI/source patchers to every supported language construct while retaining formatting |
| Entity and schema studio | Substantial | Entity-kind-aware authoring for JPA entities, mapped superclasses, embeddables, Jmix DTOs and typed `EnumClass<String|Integer>` enumerations; official scalar datatype families and explicit custom Java/SQL datatypes; configured embedded IDs; traits, inheritance, validation groups, listeners, comments/system/LOB/property metadata, read-only accessors, relationships/compositions/cross-store references, repositories, constraints/indexes and aligned Liquibase. Java/Kotlin selection targets the matching module production source set and generates native entities, enums, repositories and activation configuration. Existing entity source opens in a native IntelliJ `Design` editor backed by the current unsaved document revision; New Entity, New View and New CRUD actions route directly to their intended workspaces. The provider and navigation service are registered in the shared descriptor and both independently packaged IntelliJ host descriptors, with source and nested-plugin-ZIP verification gates preventing undiscoverable releases. Existing Java and Kotlin both support revision-bound, current-document-aware, source-preserving additions and managed nullability/uniqueness/length/precision/scale updates after reconstructing Kotlin ID/column/shape/relationship metadata; Kotlin results are PSI-validated and both languages share atomic checked forward/rollback migrations. Handwritten attribute comments, system/LOB/Jmix-property markers, dependency lists, property datatypes, SQL column definitions, read-only Kotlin declarations, Jakarta validation parameters/messages/groups and source-only annotation names are reconstructed for Java and Kotlin. Managed annotations are edited through exact source ranges while custom annotations, manual methods, unmanaged column arguments and physical DDL remain untouched; duplicate or mutability-changing requests fail closed. Existing handwritten Jmix DTOs, embeddables and mapped superclasses are classified and reopened as their real model kind instead of fake tables; reusable persistent mappings remain visible, DTO properties remain non-persistent, and all three are excluded from table drift and Liquibase generation. A newly designed Java or Kotlin attribute can be added to handwritten source and propagated to selected matching FlowUI forms/grids, inline/shared fetch plans, the default message bundle and exact resource-role attribute policies in one revision-bound transaction. Manual methods and annotations remain intact, conflicting edits fail closed, and privilege expansion is never preselected. Stable-mapping scalar and relationship properties launch IntelliJ native usage-preview rename from Entity Designer after exact-source, collision and inferred-mapping checks. Logical property rename can be coordinated with explicit scalar or owning to-one physical mapping evolution through native read-only preflight, reversible mapping/Liquibase apply, source re-index and a second native validation before IntelliJ usage preview. Stable-mapping Java/Kotlin attributes also launch IntelliJ Safe Delete with plugin-contributed Jmix usages; source deletion and physical data retirement are deliberately separated so a column is never silently dropped. Handwritten scalar properties now request IntelliJ project-wide Type Migration through exact Java fields or Kotlin light fields; the plugin classifies source-only versus expand/contract/external/incomplete schema impact and reports PK, unique, index and incoming/outgoing FK dependencies without claiming that `modifyDataType` is automatically reversible. Source-only outcomes open refactoring preview. Proven lossless SQL changes can create a non-destructive expansion changelog with deterministic shadow naming, transactional backfill, mandatory-constraint restoration, schema-qualified operations, HALT-on-failure/error preconditions and rollback that drops only the shadow; unproven conversions fail closed. A credential-contained, read-only live gate now loads the project JDBC driver, resolves case-sensitive schema metadata, checks the deployed original/shadow columns and target SQL capacity, and rejects null or value-divergent backfill rows. An expiring memory-only capability bound to the exact revision/property/type/schema is required to open native source migration. After refresh, mapping cutover rechecks live parity and atomically changes only the exact Java/Kotlin `@Column(name)` literal. Complete high-confidence schema inventory then offers dependency-gated reversible quarantine for unmapped non-key/non-unique/non-FK columns using portable deterministic names, old-exists/new-absent preconditions and reverse rollback that drops only the quarantine; explicitly protected unmapped columns never receive a retirement suggestion. JPA `mappedBy` strings provide Java/Kotlin completion, navigation, targeted-index Find Usages and safe rename; native FlowUI/fetch-plan/JPQL/security references also participate. New owning many-to-one, one-to-one and explicit join-table many-to-many relationships can generate their matching inverse property in existing handwritten Java/Kotlin targets; source, target and owning-side Liquibase are one atomic revision-bound preview. Established owning relationships can add or repair the inverse while combining safe source-semantic edits, without recreating unchanged DDL. Established bidirectional many-to-one/one-to-many and one-to-one/one-to-one pairs narrow or widen as a single two-source plan that changes inverse annotations plus Java/Kotlin scalar/collection types and initializers while keeping DDL on the proven owning column. Collisions, source-shape drift, stale targets, cross-store mappings and module dependency cycles fail closed. Explicit scalar and owning-side to-one physical-column renames update Java/Kotlin `@Column`/`@JoinColumn` mappings and generate old-exists/new-absent Liquibase preconditions with reverse rollback in the same preview while preserving all unmanaged annotation arguments; ambiguous, inferred, inverse, collection, join-table, cross-store, shape-changing, colliding and unmanaged requests fail closed. Handwritten Java/Kotlin `@Table(name, schema, catalog)` mappings round-trip all three qualifiers. The credential-contained live browser lists and filters bounded catalogs, schemas, tables, and views through the project's active-profile JDBC driver, while arbitrary tables remain read-only. A digest-stamped columns/PK/FK/index snapshot stages selected scalar or mapped-relationship columns into the source-safe update preview only after the backend proves the exact expected entity/store/table/schema/catalog mapping; same-named cross-schema tables and unsupported database types fail closed. The database-first designer selects multiple roots, resolves a bounded recursive foreign-key closure, reuses exact indexed entity mappings, creates ordered composite-ID embeddables, preserves multi-column owning joins, recognizes only strict two-FK/unique pure join tables, maps qualified many-to-many joins, requires explicit stable IDs for views, makes computed columns persistence-read-only, and blocks missing keys, ambiguous unnamed FKs, mapping ambiguity, unsupported types and stale live metadata. Every generated table mapping is DDL-disabled and the entire Java/Kotlin/message batch is previewed and applied atomically. Database-first mappings can be persisted as deterministic, credential-free, source-controlled profiles; a profile replays its exact module/store/package/language/table selections and overrides, reviews added/removed/changed live-table drift against the saved baseline, and is atomically created or revision-bound replaced with the generated entity/message batch. Existing attributes can also be propagated through the same impact review. `_base` coverage and self-closing XML are handled, manual source is retained, locale bundles require translation review, and multi-file operations are atomic and idempotent. Datastore-aware migration proposals, include-chain protection and schema diagnostics are integrated | Other relationship shapes, contraction and final post-retention deletion; dynamic controller-built UI, inherited/fragment fetch coverage and translation-provider propagation; composite-ID migration proof; populated multi-database rehearsal |
| FlowUI designer | Substantial | Native IntelliJ Design/XML `FileEditor` for project-contained `view` and `fragment` descriptors; current unsaved IntelliJ documents are the source of truth for load, digest-bound preview, apply, undo and redo; switching back to Design republishes the current manual revision; clean unopened files still persist through the VFS; safe external composite-root aliases and a private-origin launch context avoid absolute-path exposure. The isolated editor route removes the global workbench navigation and retains the responsive permanent three-pane layout; palette-to-canvas and canvas-to-canvas drag/drop with before/inside/after targets; click insertion; immediate digest-guarded XML synchronization; global undo/redo; source-safe copy/cut/paste/clone, responsive wrapping and namespace-preserving layout conversion; subtree ID/reference rewriting; selection stability; desktop/tablet/mobile and zoom previews; Jmix-native form/grid/flex responsiveness; component tree/properties; bindings/loaders/fetch plans/actions; controller discovery and guarded controller changes | Runtime-fidelity fixtures for custom/add-on components, reusable templates, themes, accessibility authoring, every handwritten-controller construct and genuine hot reload; installed-IDE JCEF interaction, memory and leak proof |
| Native IntelliJ editor intelligence | Substantial | Native PSI references for FlowUI XML IDs, Java/Kotlin controller annotations, view/screen IDs, nested menu IDs, Spring menu bean names and callable bean methods, message keys in XML and Java/Kotlin APIs, resource-policy entity metadata aliases, inherited/nested entity-attribute paths, JPQL row-policy paths, specific permissions, Jmix/JPA entity classes, property containers, inline fetch plans and shared fetch plans; completion; Ctrl/Cmd+B navigation; Find Usages; private-field project-use-scope enlargement; declaration-side, cross-file safe rename; derived Spring bean rename propagation; unresolved/ambiguous/unsafe-reference inspections with nearest-symbol fixes; duplicate menu and invalid fetch-plan diagnostics; descriptor-file rename tracking; bidirectional controller/descriptor gutter navigation; legacy `ScreenPolicy` compatibility; and explicit Kotlin K2-mode compatibility. Localized message keys are valid polyvariant symbols and declaration rename updates the base bundle, writable locale siblings and XML/Java/Kotlin usages. Menu bean methods enforce exact Java `Map<String, Object>` or Kotlin `Map<String, Any>` contracts. Jmix `rest-services.xml` resolves indexed Java/Kotlin Spring services, methods, overloads and positional public payload parameters; completes and navigates JVM parameter types; preserves intentional public aliases; refactors coupled bean/method/parameter/type declarations; and diagnoses unresolved, ambiguous, non-public, wrong-arity/type and duplicate mappings. `@RestService`, recursive Java/Kotlin composed Spring stereotypes, standard component-name `@AliasFor` aliases and Java/Kotlin `@Bean` factory products share the inventory, including explicit aliases and derived factory-method rename. Profile-specific `jmix.rest.services-config` and `jmix.rest.queries-config` values resolve comma-separated classpath resources, preserve prefixes during rename, distinguish descriptor kinds and fail closed on missing, duplicate or multi-module-ambiguous paths while leaving external and placeholder resources runtime-owned. Java/Kotlin event-listener inspections validate Spring-bean ownership, listener arity, exact Jmix entity generic binding, pre-store listener choice and after-commit `REQUIRES_NEW` data access. Java/Kotlin `@ViewComponent`, `@Subscribe`, `@Install` and `@Supply` contracts receive placement, instance-member, EventObject/return/parameter, target, duplicate-installation, delegate-SAM and generic-injection diagnostics. Exact XML injection types come from opened-project Jmix/add-on Studio metadata, including generic components, nonvisual elements and custom subscriptions. Premium-style `@UiComponentPolicy` intelligence resolves selected views, view actions, component actions and recursively nested fragments in Java/Kotlin; dotted paths support completion, navigation, Find Usages, nearest-ID fixes and safe XML-policy refactoring, and the visual Security Workspace consumes the same composed graph. Entity, view, Spring bean, menu, message, permission, fetch-plan, FlowUI descriptor, REST descriptor and Studio metadata discovery uses ten independent persistent content-sensitive candidate indexes plus a keyed composed-stereotype usage index; cache hits avoid candidate enumeration, PSI validation is cancellable, a build guard prohibits broad-scope regressions, and all 50 JCEF non-blocking reads are smart-mode/project-expiry guarded | Link application-event publishers to listeners; extend native service intelligence to BPMN/expressions and other consumers; cover the full configuration catalog, YAML, placeholders and `.env`; add fetch-plan coverage analysis, language injections, snippets and more intentions; prove installed-IDE dumb-mode, cold-index, completion/navigation latency, memory and leak budgets on representative customer repositories |
| Visual programming language | Strong | Typed Java 17+ service model; permanent palette/canvas/inspector; structured literals/parameters/variables; constrained Jmix entity CRUD/query/property operations; indexed service calls; conditions, requirements, returns, exceptions and logs; explicit CRUD authorization; transaction propagation/isolation/read-only/timeout; reusable typed private subflows; typed collection iteration with ITEM/DONE routes and index variables; structured try/catch/finally subflow boundaries; bounded cyclic execution; recursion, argument/result type, exception signature and transitive read-only-write rejection; deterministic side-effect-free path tracing; deterministic source-owned round trip; first-class subflow/caller impact edges | Event/queue primitives, richer formulas/pattern matching, cross-service visual composition and semantic compilation against every target-project dependency |
| Formula, decision and rules | Substantial | Responsive permanent palette/tree/inspector expression workspace plus a responsive three-region Jmix/Flowable DMN editor; typed inputs/outputs and conditions; UNIQUE/FIRST/ANY/PRIORITY/OUTPUT_ORDER/RULE_ORDER/COLLECT hit policies; SUM/MIN/MAX/COUNT aggregation; ordered output priorities; overlap/conflict/shadow analysis; authoring version/status/effective dates; typed simulation with matched-rule highlighting; deterministic `.dmn` generation under production resource roots; standard-DMN read-only parsing; exact owned-source round trip and manual-source lock; first-class decision/input/output/rule graph artifacts and BPMN decision-task impact links | Decision trees and reusable rule sets, bulk/cross-version simulation, deployed-version activation/retirement/migration, tenant-aware rollout and semantic compilation against every target-project dependency |
| Workflow and case management | Substantial | BPMN states/transitions, roles, forms, listeners, mappings, timers, messages, signals, retries, multi-instance/quorum, compensation, embedded/event/transaction subprocesses, cancel/terminate/error semantics, Jmix email task, Flowable DMN business-rule tasks linked to indexed decision keys, unresolved-decision diagnostics, deterministic simulation and UI-transition bypass diagnostics | Live process-instance trace, assignee hierarchy/delegation resolution, deployed version migration and ad-hoc case management |
| Security designer | Strong | Resource roles, row roles, entity/attribute/view/menu/specific policies, native and visual UI component constraints across view actions, component actions and nested fragments, effective-access workspace, runtime evidence and unconstrained-access diagnostics | Full OIDC claim-to-role simulation, organization/session-context matrices and end-to-end “run real screen as role” |
| Jmix REST/API studio | Substantial | `rest-services.xml` and `rest-queries.xml` discovery/editing, method/query parameters, fetch plans, authorization/transaction warnings, saved invocation payloads and redacted token input | OpenAPI-first authoring, GraphQL/gRPC/SOAP, consumer/provider contract suites and schema-evolution governance |
| Integration designer | Substantial | Responsive permanent catalog/canvas/inspector workspace; dependency-aware HTTP/webhook, Kafka, RabbitMQ, SFTP, Jmix email, Jmix file/object storage, SMS, payment and identity-provider adapter models; indexed `OAuth2AuthorizedClientManager` selection with externalized registration/principal properties; externalized endpoints/secrets; indexed inbound-handler binding; bounded timeout, retry/backoff/DLT, delivery, transaction, ordering, idempotency, circuit-breaker, bulkhead, rate-limit and observability policies; fail-closed conflict validation; two-file digest-bound preview/apply; exact owned-source rediscovery/lock; first-class connector/method/service-call graph edges | Implement persisted outbox/dispatcher design, Rabbit listener retry-interceptor selection, provider-specific metrics/tracing/audit runtime emission, organization catalog/versioning, exact Spring Security version adapters and real broker/SFTP/provider fault-injection suites |
| Migration designer | Substantial | Visual Liquibase changes, module/store ownership, include chains, indexes/FKs/unique constraints, rollback and portability-oriented types | Live database diff, data-preserving destructive changes, upgrade rehearsal against populated snapshots and workflow-instance migration |
| Production diagnostics | Substantial | Money/`Double`, UI workflow transitions, unconstrained data access, native SQL writes, missing transactions, unsafe logging, REST mismatches, schema/index/migration and source-link diagnostics; visual-logic recursion, invalid control branches, typed subflow signature mismatches, unsupported exception boundaries and writes reachable through read-only entry points | Duplicate-calculation detection, outbound timeout/retry proof, circular build wiring, full job side-effect analysis and release policy profiles |
| Scenario/test studio | Substantial | Visual isolated entity seeding, system/named-user execution, service invocation, entity/property/count assertions, direct result assertions, required-failure assertions, deterministic Java preview and owned-source round trip | Recorded FlowUI journeys, workflow-runtime actions, REST contract steps, async eventual assertions, migration rehearsal, performance/fuzz/accessibility tests |
| Runtime debugger | Major gap | Runtime preview launcher and deterministic workflow simulator | Breakpoints, variable/watch inspection, transaction/SQL tracing, async/job replay, audit timeline and production-safe telemetry attachment |
| Collaboration/governance | Major gap | Reviewable source output and local change history | Shared model merge semantics, ownership/approval policies, signed catalogs, audit export, CI policy gates and team permissions |
| Enterprise/NGO field operation | Major gap | Large-workspace safety limits and tenant-aware workflow metadata | Offline-first sync/conflict tools, low-bandwidth data packages, geospatial/case/beneficiary features, localization workflows and field-device diagnostics |
| Self-sustained operation | Substantial | Gradle-managed Node distribution and reproducible web packaging; Java 17-compatible source with current-JDK host testing | Signed distribution/update channel, dependency/SBOM UI, telemetry consent, crash recovery and marketplace release automation |

## Verified in this audit

- The production web application builds and is packaged into the plugin.
- The Entity Designer Jmix data-listener workflow was exercised in the real
  browser from event and transaction-phase selection through immutable preview
  and atomic apply. The creator distinguishes JPA `@EntityListeners` from
  Spring Jmix entity events, exposes `REQUIRES_NEW` only for an after-commit
  handler, and derives the target module from the indexed entity rather than
  trusting a client path. At 1440 and 360 pixels the document and body widths
  exactly matched the viewport and the browser reported no warnings or errors.
  Five focused contracts pass on both IntelliJ hosts and prove Java/Kotlin
  syntax, stale/collision rejection, explicit transaction semantics, atomic
  create-only output, application-graph discovery and `LISTENS_TO` links for
  every generated handler.
- The Entity Designer live-database slice was exercised end to end in a real
  browser: an existing multi-module `LoanApp` mapped its `EMPLOYEE_ID` foreign
  key to the indexed `Employee` entity, proposed two scalar columns, staged all
  three additions, and produced the immutable atomic source/migration preview.
  At 1440, 1024 and 640 pixels the document width equalled the viewport, no
  visible button/input/select crossed the viewport boundary, and the browser
  reported no runtime errors. Dual-host integration contracts resolve an
  active-profile datasource-only store, connect through the configured JDBC
  driver, inspect live metadata, identify already-mapped columns, preserve
  precision/scale and prove that URL, username and password do not cross the
  response boundary.
- The database-first Entity Designer flow was exercised from live table
  selection through dependency planning and immutable file preview. A
  composite-key `LoanAcct` produced a dedicated identity class, reused the
  indexed cross-module `Employee` entity and exposed all source/message files
  as one atomic batch. At 820 and 480 pixels the document width exactly matched
  the viewport; the narrow layout retained complete cards, controls and file
  evidence without horizontal overflow, and the browser reported no runtime
  errors. Dual-host planner/generator contracts cover ordered composite keys,
  multi-column foreign keys, strict join-table recognition, explicit view
  identifiers, deterministic/stale digests, computed columns and ambiguous
  unnamed-FK rejection.
- Repeatable database mappings were exercised in the real browser from saved
  profile selection through live drift review and immutable preview of the
  source-controlled profile. The exact-baseline result remained visible at
  desktop width, and at 480 pixels the document and body widths exactly matched
  the viewport with no runtime warnings or errors. Dual-host contracts prove
  deterministic credential-free serialization, strict profile identity and
  baseline validation, and atomic revision-bound profile replacement alongside
  generated Kotlin source without database DDL.
- The handwritten-entity connected update was exercised in the real browser:
  adding a selected attribute exposed one atomic entity-and-surfaces review,
  included the exact entity source in the immutable preview, and left the
  privilege-expanding role target unselected. At 480 pixels the complete impact
  review had no document or body overflow and produced no browser warnings or
  errors. Dual-host integration contracts cover both Java and Kotlin source,
  preserve handwritten methods, generate language-native properties, and update
  matching forms, grids and message bundles in the same revision-bound plan.
- Existing non-table Jmix models were exercised through Entity Designer using a
  handwritten Kotlin DTO: the source selector retained its real `dto` kind,
  the safety panel explicitly disabled table DDL, database actions were absent,
  and the 480-pixel layout had no overflow or browser diagnostics. Dual-host
  contracts distinguish Java DTOs, Kotlin embeddables and mapped superclasses,
  exclude them from false table drift, and prove a source-preserving DTO
  attribute addition produces no Liquibase file.
- Handwritten Java and Kotlin attribute metadata was reconstructed and edited
  through the Entity Designer source-metadata panel. The browser exposed
  validation, dependency and preserved custom-annotation evidence, accepted
  comment and validation changes, and at 480 pixels kept document and body
  width equal to the viewport without warnings or errors. Dual-host contracts
  prove exact managed-annotation edits preserve custom annotations, manual
  methods, unmanaged column arguments and Kotlin mutability, while metadata-only
  changes generate no Liquibase file and unsafe or ambiguous requests fail
  closed.
- Existing handwritten Java and Kotlin relationships were exercised through
  the responsive relationship-semantics panel. Fetch, cascade, orphan removal,
  Jmix composition and delete policy remained source-only, while an explicit
  local owning many-to-one could be narrowed to one-to-one through a
  duplicate-data precondition and reversible unique constraint. Other target,
  target, ownership, join structure and combined physical changes remained
  structurally locked. The 480-pixel browser view retained every control
  without right-edge clipping and reported no warnings or errors. Dual-host
  contracts prove Jakarta Persistence default fetch reconstruction, exact
  source-only edits, custom-annotation and manual-method preservation, zero
  Liquibase output for source-only edits, checked uniqueness DDL for narrowing,
  and fail-closed unsupported cardinality changes.
- A newly designed owning relationship can now create its matching inverse
  property in an existing handwritten entity as one immutable, revision-bound
  change. The responsive Entity Designer exposes the paired intent only for
  proven same-module, same-store JPA targets and explains the generated
  `mappedBy` contract. At 480 pixels the complete inverse editor remained
  usable without right-edge clipping and the browser reported no warnings or
  errors. Dual-host contracts cover Java-to-Kotlin many-to-one/one-to-many
  generation, owning-side-only Liquibase, self-references in one source file,
  manual-method preservation, target-name collisions, and fail-closed
  cross-module dependency cycles.
- An established owning relationship can now add or repair its inverse property
  without recreating the physical mapping. The browser exercised a handwritten
  same-module many-to-one, changed its fetch semantics, enabled the inverse,
  and retained both intentions together. At desktop and 480-pixel widths the
  editor remained complete without right-edge clipping or browser diagnostics.
  Dual-host contracts prove the source relationship must still match its exact
  indexed target, ownership, join and constraint shape; the Java source and
  Kotlin target are revision-checked as one change, manual methods survive, and
  no Liquibase file is produced when the database mapping is unchanged.
- Owning one-to-one relationships can now widen safely to many-to-one when the
  complete Liquibase inventory proves exactly one named, single-column unique
  constraint or unique index. Java and Kotlin mappings remove only the managed
  `unique` argument and change the relationship annotation while retaining
  custom join-column arguments and manual methods. The same atomic preview drops
  the exact constraint/index and rollback recreates it; unnamed, missing,
  partial, ambiguous, externally managed or schema/catalog-unqualified evidence
  fails closed. The responsive designer explains the conditional rollback and
  remained complete at 480 pixels with no browser diagnostics. Dual-host
  contracts cover both constraint-backed Java and index-backed Kotlin mappings.
- An established inverse one-to-one can now receive physical ownership from its
  exact handwritten counterpart. The planner resolves and revision-checks both
  Java/Kotlin sources, changes `mappedBy` and `@JoinColumn` on the correct sides,
  preserves manual code and unmanaged annotation arguments, and keeps JPA
  optionality aligned with database nullability. Complete same-store Liquibase
  evidence must prove the old non-key join column, exactly one named unique
  constraint or index, exactly one matching foreign key, and a collision-free
  destination column. The atomic migration adds and backfills the new unique
  foreign key before removing the old mapping; rollback restores the old column,
  data, nullability, exact constraint/index and foreign key before retiring the
  new column. Dual-host contracts cover Java receiving from Kotlin and Kotlin
  receiving from Java, constraint- and index-backed schemas, collision rejection
  and preservation of both manual sources. The responsive control was exercised
  at 1440, 480 and 360 pixels with no horizontal overflow or browser diagnostics.
- Established Java and Kotlin owning to-one relationships can now move in both
  directions between optional and required. The planner requires complete,
  unqualified physical evidence for the exact non-key join column, its current
  nullability/uniqueness and one matching foreign key; schema drift, missing or
  ambiguous keys, views, partial history and combined cardinality changes fail
  closed. Source edits preserve manual annotations/methods and unmanaged
  `@JoinColumn` arguments while keeping Jakarta `optional` and database
  `nullable` aligned. Liquibase checks column existence and null data before
  contraction, expands only the proven NOT NULL constraint, and generates
  reverse rollback. The same preview may rename an explicit join column:
  destination collisions are rejected from the physical inventory, null data
  is checked using the old name, forward operations rename before changing the
  constraint, and rollback changes the constraint before renaming back.
  Apply-and-refresh contracts prove both source and physical schema re-index to
  the new state. The responsive two-control workflow was exercised at 1440,
  480 and 360 pixels without horizontal overflow or browser diagnostics.
- Coordinated entity rename now preflights the exact native Java/Kotlin
  declaration without modifying files, applies an independently reversible
  explicit physical mapping rename, refreshes its source fingerprint, and only
  then opens IntelliJ usage preview for the logical property and connected Jmix
  references. Canceling at the IDE stage leaves valid source and schema
  mappings. The same milestone completes established bidirectional
  many-to-one/one-to-many and one-to-one/one-to-one narrowing/widening: both
  handwritten sources, inverse annotation, Java/Kotlin scalar-or-collection
  type, initializer and owning-side uniqueness migration are one immutable
  preview. Java initialized fields are now reconstructed by the schema index.
  Mixed-language apply-and-refresh coverage preserves manual code. The browser
  completed the coordinated mapping/native-refactor handoff at 1440 pixels and
  the responsive bidirectional-cardinality interaction at 1440/768/480/360
  pixels with no horizontal overflow or diagnostics.
- The Integration Designer was exercised in a real browser. Catalog selection,
  indexed Kafka handler binding, non-blocking retry and dead-letter property
  authoring, and the immutable two-file Java/policy preview completed
  successfully. At 1280 pixels, the catalog, canvas and inspector ended exactly
  at the viewport edge with no horizontal overflow. Deterministic iframe
  viewport fixtures measured 1000/1000 and 640/640 document widths; all three
  regions remained rendered and stacked in order without right-side clipping.
- Connector workspace tests on IntelliJ 2025.3 and 2026.2 force every connector
  kind through target-workspace capability validation,
  Java PSI syntax validation and the owned Java/policy source-pair pipeline.
  Identity connectors require and inject an indexed
  `OAuth2AuthorizedClientManager`, externalized registration ID and
  application-scoped principal; missing manager selection fails closed.
- Application-graph contracts prove generated connector classes are indexed as
  first-class integration endpoints, declare their operations, and connect
  operation-to-service impact edges.
- The workflow palette, subprocess inspector, Jmix email inspector and scenario
  failure-assertion editor were exercised in a real browser without console
  errors.
- IntelliJ 2025.3: 310 tests and 3 host smoke tests passed; the packaged
  plugin verifier reports compatibility with IU-253.28294.334.
- IntelliJ 2026.2: 310 tests and 3 host smoke tests passed; the packaged
  plugin verifier reports compatibility with IU-262.8665.258.
- Platform-independent discovery/parser contracts: 70 tests passed.
- Eight native editor-assistance scenarios pass on both IntelliJ hosts,
  covering FlowUI XML, Java and Kotlin completion, navigation, Find Usages,
  cross-file rename, inspections, quick fixes and bidirectional gutter links.
- Thirteen native domain-assistance scenarios pass on both IntelliJ hosts,
  covering Java/Kotlin entities, metadata aliases, nested properties, private
  field use scope, generic collection property containers, shared fetch plans,
  built-ins, cross-file rename,
  modification-aware cache invalidation and fail-closed duplicate/ambiguity
  diagnostics.
- Sixteen native UI/security-assistance scenarios pass on both IntelliJ hosts,
  covering XML/Java/Kotlin message keys, view and legacy screen IDs, nested
  menu declarations, view/menu policies, entity metadata aliases, nested
  entity-attribute paths, JPQL row-policy paths, specific permissions,
  completion, navigation, cross-language Find Usages, safe rename,
  inspections and quick fixes. Paid-style `@UiComponentPolicy` assistance
  resolves a selected view by class or ID, composes view actions,
  component-owned actions and recursively nested fragment components, and
  keeps every dotted segment rename-safe across Java/Kotlin and XML. Matching
  visual-security graph tests prove the same three target types resolve to
  effective component surfaces and invalid contracts become actionable
  findings. The locale regression proves that a key present
  in base and Bengali bundles is not falsely unresolved and that renaming the
  base declaration updates both bundles plus XML, Java and Kotlin usages.
- Eight native menu-bean scenarios pass on both IntelliJ hosts, covering Java
  and Kotlin stereotype beans, Java `@Bean` factories, indexed callable-method
  completion/navigation, Find Usages, cross-XML method rename, explicit and
  derived bean rename propagation, ambiguity and unsafe-signature diagnostics,
  exact generic map contracts, and artifact-specific cache invalidation.
- Fifteen native REST/configuration/event scenarios pass on both IntelliJ
  hosts. They cover Java/Kotlin services, `@RestService`, Java/Kotlin `@Bean`
  factory products, overload and JVM-type disambiguation, public payload
  aliases, completion, navigation, Find Usages and safe rename; profile-aware
  services/query resource references; duplicate, ambiguity and wrong-kind
  diagnostics; entity-event generic, listener and transaction safety; and
  artifact-specific REST descriptor cache invalidation.
- Five native composed-Spring scenarios pass on both IntelliJ hosts. They
  cover recursive Java and Kotlin stereotype declarations, cross-language
  usage, standard component-name `@AliasFor` navigation and rename, strict
  rejection of unrelated string-valued annotations, relevant-source
  invalidation and unrelated-annotation cache stability.
- Thirteen native FlowUI controller-contract scenarios pass on both IntelliJ
  hosts, covering valid and invalid Java/Kotlin `@ViewComponent`, `@Subscribe`,
  `@Install` and `@Supply` declarations, raw-generic repair, target-kind
  scoping, exact project-version XML injection types, logical listener-subject
  completion/navigation/rename, ambiguous event listeners, add-on
  `customSubscriptions`, field and setter-method injection, and Java/Kotlin
  diagnostics delivered to the visual View Designer without enabling unsafe
  Kotlin mutation.
- A 3,000-unrelated-file native index fixture passes on both IntelliJ hosts.
  It combines XML, properties and Java noise with real entity, view, menu,
  message, fetch-plan, specific-permission and Spring-bean declarations; every
  symbol remains discoverable, FlowUI/REST discovery excludes unrelated XML, and
  edits to one artifact family do not evict another. One hundred warm reads
  measured 6/6 ms total on IDEA 2025.3/2026.2; access after unrelated edits
  measured 2/2 ms; twenty consecutive three-file typing cycles measured
  76/88 ms total while preserving every cache identity. A relevant message
  edit replaced only the message inventory in 5/5 ms, a relevant REST
  descriptor edit replaced only its own inventory, and a relevant Studio
  metadata edit replaced only its project-version snapshot. The architectural
  contract, p50/p95/p99 measurements and explicit remaining installed-IDE
  benchmark boundary are documented in `NATIVE-INDEX-ARCHITECTURE.md`.
- The Menu Designer was exercised at 1440, 1024, 768 and 390 pixels. Indexed
  Spring bean and method values survived breakpoint changes, desktop and
  adaptive property panels had no horizontal overflow, and the browser
  reported no errors or warnings.
- Typed server-logic node creation, branch connection, edge selection, local
  undo, Java preview and 1440/1000/640-pixel adaptive layouts were exercised in
  Chrome without horizontal page overflow or current-run console errors.
- Reusable visual-subflow authoring, zoom/fit controls and the deterministic
  execution-path tracer were exercised in the live browser; collection and
  try/catch/finally blocks remained available in the same permanent palette.
  The rendered edge endpoints were
  measured at the node boundary after layout changes, and the browser reported
  no errors or warnings.
- Generator and workspace tests prove exact owned-source round trip for private
  subflows, structured Java generation, recursion rejection, exact argument
  typing, exception-handler signature validation and transitive rejection of
  persistence writes behind read-only public entry points.
- Application-graph contracts prove generated private visual subflows appear as
  service-method artifacts and exact caller-to-subflow impact relationships;
  generated helper methods remain excluded from the business graph.
- Rules/formulas were exercised in Chrome at 1440×900, 1000×760 and 640×760:
  palette, expression tree and inspector remained visible, the narrow layout
  stacked without right-side clipping, the validator template rewrote the
  typed tree, and source preview opened without console errors.
- The DMN decision-table workspace was exercised in a real browser at 1440×900
  and 640×900. Its library, matrix and inspector used the full available width
  at desktop size and stacked without clipping at 640 pixels; page width
  remained equal to viewport width. Rule creation, undo, typed condition
  rendering, simulation with matched-rule output and source-safe DMN preview
  completed without browser warnings or errors.
- Pure parser/generator tests cover deterministic Flowable-compatible DMN,
  every supported hit-policy behavior used by simulation, priority ordering,
  COLLECT aggregation, UNIQUE/ANY overlap rejection, standard-file parsing and
  fail-closed rejection of arbitrary expressions and XML doctypes. The
  semantic graph test proves DMN decisions, columns and rules are indexed and
  a BPMN business-rule task resolves to its decision artifact.
- A synthetic 16-module enterprise suite with `projectDir` remaps and a
  15-edge dependency chain is indexed deterministically on both IntelliJ hosts;
  declared Gradle ownership now propagates into every recovered source root.
- Integration tests prove an exact build-declared custom Java root, custom
  resource root and derived test root survive indexing and are offered to
  authoring services under the recovered module identity. Nested Gradle DSL
  blocks and broad parent IntelliJ roots no longer erase the child root's
  language/resource identity.
- Custom roots are also verified beyond indexing: project base-package
  detection resolves an entity under a custom Java root; Liquibase generation
  preserves the custom resource destination and classpath-relative logical
  path; runtime preview resolves `application.properties`, context path and
  recovered application module from a custom resource root.
- `projectDir` declarations using `File(rootDir, …)`, `settingsDir.resolve(…)`
  and the layout directory API, plus Kotlin/Groovy `setSrcDirs`, `srcDirs =`
  and direct `srcDirs(…)` declarations, are recovered and connected on both
  IntelliJ hosts.
- Duplicate Gradle paths such as `:core` in a root build and an included build
  retain build-qualified identities; their dependency edges are verified not
  to cross-wire between composites on either IntelliJ host.
- The existing FlowUI round-trip designer was exercised at 1440×900,
  1000×760 and 640×760. Palette, canvas and inspector remained simultaneously
  visible, the right inspector ended exactly at the viewport edge, page width
  did not overflow, mobile canvas preview stayed contained, and live
  insertion/undo/redo completed successfully.
- Real FlowUI `view` and `fragment` XML files now receive a native IntelliJ
  **Design** editor beside the standard XML editor. Dual-host tests prove
  descriptor eligibility, Design-before-XML registration, safe external-root
  locators, current unsaved-document fingerprints, manual-XML reselection,
  disposal, exact packaged-route bytes, document-aware preview/apply/history
  and stale rejection after a post-preview manual edit. A clean unopened
  external-composite file remains persistently writable rather than being
  mistaken for an actively edited cached PSI document.
- The isolated native editor route was exercised in a real browser at 1280,
  640, 440 and 320 pixels. The global workbench navigation was absent,
  palette/canvas/inspector remained available without tabs, shell width equaled
  scroll width at every size, and no browser errors were reported. A live
  component insertion invoked preview and apply, enabled Undo, and Undo invoked
  the workspace-history bridge. At sub-480 widths the three regions stack in a
  continuous scroll surface with usable palette/tree/data heights.
- Copy/paste, layout conversion and responsive wrapper insertion were exercised
  in the live-arrange FlowUI designer and each produced a separate undoable
  history entry. At 640×760 the arrange/reuse controls, canvas and property
  inspector remained contained with page width equal to viewport width.
- Parser contracts prove copied nested subtrees receive collision-free IDs,
  internal `component.action` references follow the copied IDs, wrapper
  insertion preserves manual XML, and conversion preserves XML namespace
  prefixes and children while removing incompatible layout-only properties.
- Generated `TODO` bodies were removed and non-void controller methods require
  explicit bodies. Collection loading now emits the official repository
  delegate using `Pageable` plus `JmixDataRepositoryContext`; bounded detail
  saving rejects aggregate/removal semantics before any write. Java and Kotlin
  controller injection is revision-bound, and effective method/repository
  constraint bypass is denied without any bridge-controlled override.
- Supported existing repository metadata now changes only exact annotation
  ranges. Handwritten callable declarations, comments, `@Override`, modifiers,
  documentation, nullability and parameter bindings are preserved; comments
  inside a changed annotation and unknown shapes make the operation read-only.
  Java/Kotlin JPQL parameters and explicit `@Param` literals participate in one
  native rename/find-usages symbol. Derived-condition insertion retains any
  static `OrderBy` suffix.
- IntelliJ's native Generate menu now discovers accessible Jmix repositories
  through inheritor indexes and module dependency scope, including custom
  generic repository hierarchies. It injects Java fields or Kotlin properties
  with PSI, recognizes existing field/constructor injection, applies one
  undoable command, restores the original document on mutation failure and
  warns natively when inherited `@ApplyConstraints` is disabled or cannot be
  proven. `@NoRepositoryBean` fragments are excluded, dumb mode is disabled,
  and shared plus both host descriptors register the action.
- Existing indexed Java and Kotlin repository methods now expose source
  navigation, Rename, Change Signature and Safe Delete directly in Entity
  Designer. Every launch is bound to the exact project-contained source
  revision, repository qualified name, indexed method/signature and live PSI
  range; malformed, stale, ambiguous and read-only mutation requests are
  denied before an IntelliJ action can run. IntelliJ retains preview, usage
  search, conflict handling and undo ownership, so handwritten declarations
  are never regenerated. The real UI handoff passed at 900, 600 and 480 pixels
  without horizontal escape or browser errors.
- The conventional `./gradlew test` lifecycle now builds and fingerprints the
  current self-managed-Node web bundle before starting isolated IntelliJ
  2025.3 and 2026.2 lanes, avoiding stale-bundle races and retaining the outer
  configuration cache.

## Release blockers for the full product promise

1. Extend the native Jmix semantic surface from REST services into BPMN and
   other service consumers, add application-event publisher/listener linking,
   the complete configuration catalog/YAML/placeholder model and fetch-plan
   coverage analysis; then
   complete installed-IDE cold-index, latency, memory,
   cancellation, dumb-mode and leak proof. The global-rescan/global-PSI-cache
   defect is removed and guarded by the 3,000-file dual-host fixture.
2. Advance DMN into reusable rule sets/trees, bulk cross-version simulation
   and deployed-version rollout/migration governance; add event/queue
   primitives and richer expression semantics to typed visual logic.
3. Add real runtime debugging, process-instance migration and assignee/security
   context simulation.
4. Finish the integration runtime tranche: persisted outbox/dispatch, Rabbit
   retry infrastructure, provider-specific observability, signed organization
   catalogs, version adapters and real failure simulation.
5. Complete recorded UI/API/workflow/migration scenario execution.
6. Add collaboration, CI governance, signed distribution and enterprise
   operational controls.
7. Prove scale and round-trip behavior on representative customer repositories,
   including the referenced 16-module payroll system.

## Recommended next execution order

1. Finish the remaining repository workflow edges: transactional aggregate
   update-service generation, complete JPQL grammar/language injection, and semantic
   compilation of generated Java/Kotlin repositories and controllers against
   the supported Jmix/Java matrix. Lossless existing metadata edits,
   repository-backed view delegates, controller injection and native
   callable Open/Rename/Change-Signature/Safe-Delete plus `@Param`/JPQL
   parameter refactoring are now implemented; source-owned diagnostics remain
   advisory for independent additions.
2. Complete evidence-backed editing of existing inheritance and embedded
   mappings, followed by localized-caption workflows and safe enum-usage
   migration. Root/subtype authoring, explicit nested scalar/association
   overrides including composite joins, copy/reorder, inherited/trait
   visibility, callback authoring and Java/Kotlin listener creation/navigation
   already round-trip for the supported new/additive shapes.
3. Continue splitting the central Entity Designer into bounded
   feature modules. Inheritance and embedded-override controls are now isolated;
   add component, interaction, responsive and accessibility regression tests.
4. Complete arbitrary handwritten Java/Kotlin fixtures, database-first
   usability and installed-IDE dual-host performance/memory/leak certification.
5. Complete the remaining native IntelliJ semantic surface.
6. DMN rule sets/trees, deployed-version governance and typed event/queue integration.
7. Live workflow/security/runtime inspection and process migration.
8. Complete connector runtime infrastructure, organization catalogs and
   provider fault-injection diagnostics.
9. Recorded scenario execution across FlowUI, REST, workflow and migrations.
10. Collaboration, release governance, performance and marketplace hardening.

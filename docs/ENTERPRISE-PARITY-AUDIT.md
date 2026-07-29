# Enterprise Parity Audit

Audit date: 2026-07-29

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
coverage, live database reverse engineering, project/add-on/upgrade
management, OpenAPI clients, deployment, compatibility certification and
signed distribution remain explicit requirements rather than implied backlog.

| Requirement area | Status | Implemented evidence | Remaining enterprise gap |
|---|---|---|---|
| Existing multi-module projects | Strong | Composite Gradle topology, source sets, typed exact custom roots (`srcDir`, `srcDirs`, `setSrcDirs`, assignment and nested-DSL forms), generated roots, source-less/orphan/recovered modules, common `projectDir` remaps (`file`, `File(rootDir, …)`, `settingsDir.resolve`, layout directory APIs), IntelliJ and static dependency edges, deterministic ownership conflict handling, YAML/XML/SQL/Java/Kotlin/Groovy parsing, incremental cache, explicit partial-index health, and a verified remapped 16-module dependency chain. Recovered roots now remain authoritative destinations for Java/resource/test generation, FlowUI/BPMN/DMN, security, server logic, scenarios, Liquibase classpaths, base-package inference and runtime configuration discovery | Validate against more real 3,000+ file repositories, included builds outside registered IntelliJ roots, and build logic whose module/source topology is created dynamically at Gradle execution time |
| Connected application graph | Strong | Cross-module artifacts and relationships for entities, views, controllers, reusable business rules, services, security, REST, workflows, menus, configuration, jobs, reports, integrations and Liquibase; bounded five-level connected behavior/change-impact paths | Deeper method-level control/dataflow and provider-specific runtime semantics |
| Safe visual changes | Strong | Typed models, immutable digest-bound previews, stale-source rejection, atomic workspace changes, history/undo, read-only lock for unsupported BPMN/source constructs | Broaden PSI/source patchers to every supported language construct while retaining formatting |
| Entity and schema studio | Substantial | Entity creation, associations/compositions/enums, constraints/indexes, additive existing-entity editing, datastore-aware Liquibase proposals, include-chain protection and schema diagnostics | Full inheritance/embeddable/projection editing, destructive migration choreography and live populated-schema rehearsal |
| FlowUI designer | Substantial | Responsive permanent three-pane layout; palette-to-canvas and canvas-to-canvas drag/drop with before/inside/after targets; click insertion; immediate digest-guarded XML synchronization; global undo/redo; source-safe copy/cut/paste/clone, responsive wrapping and namespace-preserving layout conversion; subtree ID/reference rewriting; selection stability; desktop/tablet/mobile and zoom previews; Jmix-native form/grid/flex responsiveness; component tree/properties; bindings/loaders/fetch plans/actions; controller discovery and guarded controller changes | Genuine runtime hot reload for all views, reusable component templates, complete custom-component/theme/accessibility authoring and all handwritten-controller constructs |
| Native IntelliJ editor intelligence | Substantial | Native PSI references for FlowUI XML IDs, Java/Kotlin controller annotations, view/screen IDs, nested menu IDs, message keys in XML and Java/Kotlin APIs, resource-policy entity metadata aliases, inherited/nested entity-attribute paths, JPQL row-policy paths, specific permissions, Jmix/JPA entity classes, property containers, inline fetch plans and shared fetch plans; completion; Ctrl/Cmd+B navigation; Find Usages; private-field project-use-scope enlargement; declaration-side, cross-file safe rename; unresolved/ambiguous-reference inspections with nearest-symbol fixes; duplicate menu and invalid fetch-plan diagnostics; modification-aware uncapped project symbol discovery; descriptor-file rename tracking; bidirectional controller/descriptor gutter navigation; legacy `ScreenPolicy` compatibility; and explicit Kotlin K2-mode compatibility | Expand the remaining semantic surface to service methods, events, configuration, menu bean methods and UI component policies; add fetch-plan coverage analysis, injections, handler-signature validation, snippets and intentions; prove installed-IDE, heavy-fixture, dumb-mode and representative large-project latency |
| Visual programming language | Strong | Typed Java 17+ service model; permanent palette/canvas/inspector; structured literals/parameters/variables; constrained Jmix entity CRUD/query/property operations; indexed service calls; conditions, requirements, returns, exceptions and logs; explicit CRUD authorization; transaction propagation/isolation/read-only/timeout; reusable typed private subflows; typed collection iteration with ITEM/DONE routes and index variables; structured try/catch/finally subflow boundaries; bounded cyclic execution; recursion, argument/result type, exception signature and transitive read-only-write rejection; deterministic side-effect-free path tracing; deterministic source-owned round trip; first-class subflow/caller impact edges | Event/queue primitives, richer formulas/pattern matching, cross-service visual composition and semantic compilation against every target-project dependency |
| Formula, decision and rules | Substantial | Responsive permanent palette/tree/inspector expression workspace plus a responsive three-region Jmix/Flowable DMN editor; typed inputs/outputs and conditions; UNIQUE/FIRST/ANY/PRIORITY/OUTPUT_ORDER/RULE_ORDER/COLLECT hit policies; SUM/MIN/MAX/COUNT aggregation; ordered output priorities; overlap/conflict/shadow analysis; authoring version/status/effective dates; typed simulation with matched-rule highlighting; deterministic `.dmn` generation under production resource roots; standard-DMN read-only parsing; exact owned-source round trip and manual-source lock; first-class decision/input/output/rule graph artifacts and BPMN decision-task impact links | Decision trees and reusable rule sets, bulk/cross-version simulation, deployed-version activation/retirement/migration, tenant-aware rollout and semantic compilation against every target-project dependency |
| Workflow and case management | Substantial | BPMN states/transitions, roles, forms, listeners, mappings, timers, messages, signals, retries, multi-instance/quorum, compensation, embedded/event/transaction subprocesses, cancel/terminate/error semantics, Jmix email task, Flowable DMN business-rule tasks linked to indexed decision keys, unresolved-decision diagnostics, deterministic simulation and UI-transition bypass diagnostics | Live process-instance trace, assignee hierarchy/delegation resolution, deployed version migration and ad-hoc case management |
| Security designer | Strong | Resource roles, row roles, entity/attribute/view/menu/specific policies, effective-access workspace, runtime evidence and unconstrained-access diagnostics | Full OIDC claim-to-role simulation, organization/session-context matrices and end-to-end “run real screen as role” |
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
- IntelliJ 2025.3: 161 tests passed.
- IntelliJ 2026.2: 161 tests passed.
- Platform-independent discovery/parser contracts: 69 tests passed.
- Eight native editor-assistance scenarios pass on both IntelliJ hosts,
  covering FlowUI XML, Java and Kotlin completion, navigation, Find Usages,
  cross-file rename, inspections, quick fixes and bidirectional gutter links.
- Thirteen native domain-assistance scenarios pass on both IntelliJ hosts,
  covering Java/Kotlin entities, metadata aliases, nested properties, private
  field use scope, generic collection property containers, shared fetch plans,
  built-ins, cross-file rename,
  modification-aware cache invalidation and fail-closed duplicate/ambiguity
  diagnostics.
- Nine native UI/security-assistance scenarios pass on both IntelliJ hosts,
  covering XML/Java/Kotlin message keys, view and legacy screen IDs, nested
  menu declarations, view/menu policies, entity metadata aliases, nested
  entity-attribute paths, JPQL row-policy paths, specific permissions,
  completion, navigation, cross-language Find Usages, safe rename,
  inspections and quick fixes.
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
- Copy/paste, layout conversion and responsive wrapper insertion were exercised
  in the live-arrange FlowUI designer and each produced a separate undoable
  history entry. At 640×760 the arrange/reuse controls, canvas and property
  inspector remained contained with page width equal to viewport width.
- Parser contracts prove copied nested subtrees receive collision-free IDs,
  internal `component.action` references follow the copied IDs, wrapper
  insertion preserves manual XML, and conversion preserves XML namespace
  prefixes and children while removing incompatible layout-only properties.
- Generated `TODO` bodies were removed, non-void controller methods now require
  explicit bodies, and collection load-delegate scaffolding is rejected until
  a complete typed implementation is available.
- The conventional `./gradlew test` lifecycle now builds and fingerprints the
  current self-managed-Node web bundle before starting isolated IntelliJ
  2025.3 and 2026.2 lanes, avoiding stale-bundle races and retaining the outer
  configuration cache.

## Release blockers for the full product promise

1. Complete native Jmix editor intelligence across service methods, events,
   configuration, menu bean methods and UI component policies, add fetch-plan
   coverage analysis, then add installed-IDE and enterprise-scale performance
   proof.
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

1. Complete the native IntelliJ semantic surface and installed-IDE proof.
2. DMN rule sets/trees, deployed-version governance and typed event/queue integration.
3. Live workflow/security/runtime inspection and process migration.
4. Complete connector runtime infrastructure, organization catalogs and
   provider fault-injection diagnostics.
5. Recorded scenario execution across FlowUI, REST, workflow and migrations.
6. Collaboration, release governance, performance and marketplace hardening.

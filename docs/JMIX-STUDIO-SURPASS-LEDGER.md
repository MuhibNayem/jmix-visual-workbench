# Jmix Studio Surpass Ledger

Baseline date: 2026-07-29

This is the binding completion ledger for the product goal: build a clean-room,
source-safe IntelliJ plugin that reaches documented Jmix Studio parity and then
surpasses it for large enterprise Jmix development.

It supplements, and does not replace:

- `FULL-GUI-DEVELOPMENT-REQUIREMENTS.md`;
- `ENTERPRISE-PARITY-AUDIT.md`;
- the supplied 16-module payroll architecture and supervisor requirements; and
- the supplied Studio comparison dated 2026-07-29.

No feature may be called complete merely because a screen exists. Every item in
this ledger is governed by the per-feature definition of done in
`FULL-GUI-DEVELOPMENT-REQUIREMENTS.md`: discovery, typed representation,
validation, impact analysis, immutable diff preview, atomic apply and undo,
round-trip safety, target-toolchain verification, runtime inspection,
accessibility, documentation, and enterprise-scale tests.

## Status and claim rules

| Status | Meaning |
|---|---|
| `STRONG` | Implemented and verified in the current audit, with only explicitly listed hardening work remaining |
| `PARTIAL` | Useful implementation exists, but one or more mandatory behavior or proof gates are missing |
| `MISSING` | No production-capable implementation exists |
| `ACTIVE` | The current implementation tranche |

The product MUST NOT claim:

- **Studio breadth parity** until every `STUDIO-*` item is `STRONG`;
- **production parity** until every `CERT-*` item is `STRONG`; or
- **clear enterprise superiority** until every `SURPASS-*` item is `STRONG` on
  representative existing enterprise projects.

Percentages and broad labels are planning aids only. The atomic items and their
evidence decide completion.

## Official documented baseline

The clean-room baseline is derived only from public documentation:

- [Studio feature list](https://docs.jmix.io/jmix/studio/studio-features.html)
- [Using Jmix Studio](https://docs.jmix.io/jmix/studio/index.html)
- [Coding assistance](https://docs.jmix.io/jmix/studio/coding-assistance.html)
- [Entity designer](https://docs.jmix.io/jmix/studio/entity-designer.html)
- [View designer](https://docs.jmix.io/jmix/studio/view-designer.html)
- [View creation wizard](https://docs.jmix.io/jmix/studio/view-wizard.html)
- [Add-ons marketplace](https://docs.jmix.io/jmix/studio/marketplace.html)
- [Composite projects](https://docs.jmix.io/jmix/studio/composite-projects.html)

Public behavior is a compatibility target, not a license to copy proprietary
source code, assets, protocols, or internal implementation.

## A. Studio core development parity

| ID | Mandatory capability | Current status | Completion evidence required |
|---|---|---|---|
| STUDIO-CORE-001 | Java and Kotlin entity designer: persistent entities, DTOs, mapped superclasses, embeddables, composite IDs, traits, inheritance, validations, lifecycle callbacks, listeners, indexes, DB views, repositories and localization | PARTIAL | Exact Java/Kotlin round trip and compile tests for every supported construct |
| STUDIO-CORE-002 | Data-store designer: main/additional stores, SQL and non-SQL stores, credentials as property references, module ownership and connection testing | PARTIAL | Real-store fixtures, secret redaction and atomic configuration changes |
| STUDIO-CORE-003 | View creation wizard for entity, DTO, blank, fragment and main-view templates, including repositories, fetch plans, messages and menu updates | PARTIAL | One atomic multi-file preview/apply pipeline for every template |
| STUDIO-CORE-004 | Complete FlowUI WYSIWYG designer with source/controller navigation, custom components, themes, accessibility, responsive behavior and manual-source preservation | PARTIAL | Runtime-fidelity suite plus custom-component and handwritten-controller fixtures |
| STUDIO-CORE-005 | Standalone fetch-plan discovery, creation, inheritance, visual editing, validation, usage navigation and safe refactoring | PARTIAL | Exact source round trip and broken-reference tests across modules |
| STUDIO-CORE-006 | At least three-level menu authoring, hierarchy validation, duplicate detection, role visibility, source navigation and safe refactoring | PARTIAL | Migrate all menu writes to immutable preview/apply and verify nested fixtures |
| STUDIO-CORE-007 | Resource and row role authoring, inheritance, entity/attribute/view/menu/specific policies and effective-access analysis | STRONG | Remaining OIDC and real-screen role execution is tracked under SURPASS-SEC |
| STUDIO-CORE-008 | Full visual JPQL designer: metadata-aware completion, joins, parameters, fetch plans, validation, execution console and safe query insertion | PARTIAL | Multi-store execution fixtures and semantic query validation |
| STUDIO-CORE-009 | Framework-aware hot deploy for supported UI, messages, security, configuration and application changes with explicit restart boundaries | PARTIAL | Target-version runtime matrix proving every supported reload class |
| STUDIO-CORE-010 | BPMN modeler compatible with Jmix BPM/Flowable artifacts, forms, listeners, DMN, deployment and source round trip | PARTIAL | Deployed runtime operations and version-migration fixtures |
| STUDIO-CORE-011 | Live database reverse engineering, partial reverse engineering, entity/schema mapping, type mapping and safe regeneration | MISSING | Populated PostgreSQL/MySQL/MSSQL/Oracle fixtures with loss-prevention tests |
| STUDIO-CORE-012 | Framework-specific coding assistance: completion, references, injections, line markers, navigation, inspections, intentions, quick fixes and safe refactorings | MISSING | IntelliJ light/heavy fixture suites for every supported language/artifact pair |
| STUDIO-CORE-013 | Context-aware Jmix code snippets and generators that use project conventions and never emit placeholder production behavior | PARTIAL | Discoverable editor actions plus target-toolchain compilation |
| STUDIO-CORE-014 | AI assistance with explicit consent, source/privacy boundaries, reviewable changes, offline-disabled behavior and no required proprietary runtime | MISSING | Threat model, privacy controls, evaluation suite and deterministic fallback |

## B. Project management and environment parity

| ID | Mandatory capability | Current status | Completion evidence required |
|---|---|---|---|
| STUDIO-PROJECT-001 | New-project wizard for certified Jmix versions, Java 17 through the latest certified Java, repositories, SDKs and application/add-on/composite templates | MISSING | Generated projects compile and run in every certified cell |
| STUDIO-PROJECT-002 | Jmix semantic project window with modules, data, UI, security, workflows, integrations, migrations and diagnostics | STRONG | Scale and accessibility proof remains under CERT-SCALE |
| STUDIO-PROJECT-003 | Project-properties editor for Jmix/add-on versions, repositories, Java/Kotlin, data stores and shared composite settings | PARTIAL | Immutable Gradle/settings edits and composite-project fixtures |
| STUDIO-PROJECT-004 | Add-on marketplace/catalog discovery, compatibility resolution, install, configure, remove and upgrade, including private organization catalogs | MISSING | Signed metadata, dependency-conflict handling and rollback tests |
| STUDIO-PROJECT-005 | Plugin settings, per-project settings, safe settings migration, export/import and enterprise policy locks | PARTIAL | Versioned schema, migration and corrupted-settings recovery tests |
| STUDIO-PROJECT-006 | Composite projects and included builds: create/add/remove subprojects, dependency graph editing, cycle prevention and common upgrades | PARTIAL | Atomic Gradle plus `@JmixModule` changes across real composite fixtures |
| STUDIO-PROJECT-007 | Profile-specific properties and external `.env` editing with secret detection, redaction and environment comparison | PARTIAL | No-secret-leak tests and runtime profile validation |
| STUDIO-PROJECT-008 | Welcome/onboarding experience for create, open, import, diagnose and recover existing Jmix projects | MISSING | First-run accessibility and failure-recovery journeys |
| STUDIO-PROJECT-009 | Complete localization tooling: message discovery, locale matrix, missing/unused keys, safe rename, fallback preview and translation import/export | PARTIAL | Cross-module locale fixtures and UI preview |
| STUDIO-PROJECT-010 | Jmix and add-on upgrade assistant with release notes, compatibility adapters, migrations, build verification and exact rollback | MISSING | Real sequential upgrades across every certified version family |

## C. Advanced Studio parity

| ID | Mandatory capability | Current status | Completion evidence required |
|---|---|---|---|
| STUDIO-ADV-001 | OpenAPI-first client generation with authentication, configuration, model customization, regeneration safety and contract testing | MISSING | Multiple OpenAPI-version fixtures and generated-client compilation |
| STUDIO-ADV-002 | Data-repository creation, method/query design, injection, view delegate integration and refactoring | PARTIAL | Java/Kotlin repository fixtures and runtime integration tests |
| STUDIO-ADV-003 | Custom project-template creation, validation, versioning, organization catalog and offline use | MISSING | Signed template bundles and generated-project matrix |
| STUDIO-ADV-004 | Docker and cloud deployment designer with environment configuration, secrets, health checks, migrations, rollback and portable output | MISSING | Local container plus supported-provider deployment rehearsals |
| STUDIO-ADV-005 | Jmix REST/OpenAPI service authoring, saved invocation, authorization, transactions, contract evolution and client generation | PARTIAL | Provider/consumer contract and compatibility suites |

## D. Required capabilities that surpass Studio

| ID | Mandatory differentiator | Current status | Completion evidence required |
|---|---|---|---|
| SURPASS-GRAPH-001 | Whole-application, multi-build graph connecting entities, views, services, methods, REST, security, workflows, integrations, jobs, reports, migrations, configuration and tests | STRONG | Deeper runtime/dataflow evidence is tracked in CERT-SCALE and CERT-RUNTIME |
| SURPASS-GRAPH-002 | Transitive impact analysis, semantic change preview and explanation of editable/read-only/blocked ownership | STRONG | Representative customer-repository certification |
| SURPASS-LOGIC-001 | Typed visual server logic, reusable subflows, collections, exceptions, transactions, authorization and version-aware generated source | STRONG | Queue/event primitives and target-dependency semantic compilation remain active gaps |
| SURPASS-LOGIC-002 | Rules, formulas, decision tables/trees, reusable rule sets, lifecycle governance and tenant-aware rollout | PARTIAL | Trees/rule sets and deployed-version migration |
| SURPASS-INT-001 | Visual connector builder for HTTP/webhooks, Kafka, RabbitMQ, SFTP, email, file/object storage, SMS, payments and identity | ACTIVE | All listed adapter families now use the source-safe visual pipeline, including identity connectors bound to an indexed OAuth2 client manager; organization catalog, exact version adapters and real provider fixtures remain |
| SURPASS-INT-002 | Reliability design: bounded timeouts, retry/backoff, circuit breaker, bulkhead, rate limits, idempotency, transactions, outbox, ordering, DLQ and replay | ACTIVE | Typed policies and fail-closed conflict checks now generate reviewed adapter/config pairs; persisted outbox, Rabbit retry infrastructure, replay, provider observability and fault injection remain |
| SURPASS-INT-003 | Organization connector catalog with signed templates, policy enforcement, versioning, secrets and approval workflow | MISSING | Signed catalog and multi-team governance tests |
| SURPASS-RUNTIME-001 | Runtime workflow/logic debugger with breakpoints, variables, watches, transaction/SQL traces, async replay and audit timeline | MISSING | Production-safe local/remote runtime fixtures |
| SURPASS-SEC-001 | Effective security simulation for menu, view, entity, attribute, specific, row-level, REST and workflow access by role/user/organization/context | PARTIAL | OIDC claims and real-screen/server execution evidence |
| SURPASS-QUALITY-001 | Financial and production diagnostics: unsafe money, duplicated calculations, missing server validation/transactions/idempotency, workflow bypass, broken JPQL/fetch plans, logging and schema risks | PARTIAL | Cross-method duplicate-calculation and full job/integration analysis |
| SURPASS-SIM-001 | Visual integration failure simulation for timeouts, partial failure, duplicates, redelivery, reordering, DLQ, compensation and provider outage | MISSING | Deterministic plus real-container fault suites |
| SURPASS-TEST-001 | Recorded UI/API/workflow/migration scenarios with isolated seeds, eventual assertions, security contexts and generated readable tests | PARTIAL | Real recording/execution and async/runtime steps |
| SURPASS-GOV-001 | Semantic Git diff, visual merge, ownership, approval policy, audit export and CI quality gates | MISSING | Concurrent-team and protected-branch fixtures |
| SURPASS-FIELD-001 | Offline/low-bandwidth field tooling, sync/conflict design, data packages, geospatial/case/beneficiary support and device diagnostics | MISSING | Disconnected and conflict-heavy reference scenarios |
| SURPASS-OPEN-001 | Generated applications remain normal, readable Jmix source with no proprietary workbench runtime dependency | STRONG | Enforced continuously by every generator and release test |

## E. Compatibility, proof and productization gates

| ID | Mandatory proof gate | Current status | Completion evidence required |
|---|---|---|---|
| CERT-WRITE-001 | Every write path uses compatibility selection, immutable visible diff, stale rejection, atomic apply, rollback and undo; no hidden legacy generator path | PARTIAL | Central write-path inventory with tests for every mutation command |
| CERT-JAVA-001 | Java 17 through the latest declared Java version | PARTIAL | Compile/run matrix with generated-source fixtures at every declared level |
| CERT-KOTLIN-001 | Kotlin discovery and editing wherever Kotlin support is declared | PARTIAL | Exact Kotlin PSI round trip and target compilation |
| CERT-JMIX-001 | Explicit Jmix 2.x/3.x version adapters; only tested cells are advertised | PARTIAL | Published compatibility matrix and real-project fixtures per cell |
| CERT-IDE-001 | Installed-plugin tests for every declared IntelliJ IDEA version | PARTIAL | Clean IDE installation, startup, UI and leak tests per release |
| CERT-DB-001 | Certified PostgreSQL, MySQL/MariaDB, MSSQL and Oracle behavior where advertised | MISSING | Containers/managed fixtures with migration and reverse-engineering suites |
| CERT-SCALE-001 | Deterministic onboarding and editing of 16–100-module, 3,000+ file, multi-store, add-on-heavy projects | PARTIAL | Payroll fixture plus anonymized representative enterprise suites |
| CERT-PERF-001 | Published indexing, UI latency, generation, memory and leak budgets | MISSING | Repeatable benchmark baselines and regression gates |
| CERT-RUNTIME-001 | Parse, compile, reopen and representative runtime validation after each generated change class | PARTIAL | Target application smoke/runtime matrix |
| CERT-SEC-001 | Plugin threat model, secure defaults, dependency scanning, secret protection and security regression tests | PARTIAL | Reviewed threat model and release-blocking AppSec gates |
| CERT-ACCESS-001 | Keyboard operation, screen-reader semantics, responsive layouts and accessibility checks for every designer | PARTIAL | Automated and manual WCAG-oriented audit |
| CERT-RELEASE-001 | Signed Marketplace artifact, reproducible build, SBOM, provenance, third-party notices, update channels and rollback | MISSING | Independently verifiable release pipeline |
| CERT-NODE-001 | Self-sustained frontend build without a preinstalled Node.js, with integrity verification and offline strategy | STRONG | Keep dependency provenance and offline cache verification current |
| CERT-DOCS-001 | User, administrator, migration, compatibility, troubleshooting and extension documentation with maintained examples | MISSING | Versioned documentation release and support runbook |

## Execution rule

Implementation order may change to reduce risk, but requirements may not be
removed or silently weakened. Any status change MUST update
`ENTERPRISE-PARITY-AUDIT.md` with concrete evidence and remaining gaps.

The current active tranche is `SURPASS-INT-001` and `SURPASS-INT-002`. The next
release audit MUST also reconcile all newly completed work against
`STUDIO-*`, `SURPASS-*`, and `CERT-*` identifiers.

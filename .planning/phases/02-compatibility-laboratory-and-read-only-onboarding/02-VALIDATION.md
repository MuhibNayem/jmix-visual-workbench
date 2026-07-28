---
phase: 2
slug: compatibility-laboratory-and-read-only-onboarding
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-28
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for read-only discovery, compatibility, semantic
> inventory, navigation, and onboarding.

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + IntelliJ Platform managed tests + Gradle fixture tasks + Vitest/Testing Library |
| **Config file** | Existing dual-host Gradle builds; Wave 0 adds Phase 2 fixture and web test harnesses |
| **Quick run command** | `./gradlew phase2FastCheck --dependency-verification=strict` |
| **Full suite command** | `./gradlew clean phase2Check --dependency-verification=strict` |
| **Estimated runtime** | Quick: <=120 seconds after warm-up; full: environment-dependent exact-host and installed-product gates |

## Sampling Rate

- **After every task commit:** Run the task-focused test plus
  `./gradlew phase2FastCheck --dependency-verification=strict`.
- **After every plan wave:** Run the relevant fixture, host, web, and mutation
  tripwire lifecycle tasks.
- **Before `/gsd-verify-work`:** Clean `phase2Check` must be green.
- **Maximum warm quick-feedback latency:** 120 seconds.
- **No three consecutive tasks:** without a behavioral automated check.
- **Negative/adversarial fixtures:** required in the same plan that implements
  the corresponding collector or authorization rule.

## Per-Capability Verification Map

| Capability | Requirements | Threat Ref | Secure Behavior | Test Type | Lifecycle gate | Status |
|---|---|---|---|---|---|---|
| Registry/schema/reason codes | COMP-01/02/03/06/07 | T2-CAP | Unknown, missing, stale, conflicting, legacy, future, or untrusted profiles cannot authorize writes | unit/property | `phase2FastCheck` | ⬜ pending |
| Mutation tripwire | DISC-01 | T2-MUTATE, T2-PROCESS, T2-NET, T2-DB | Open/index/browse/navigate/cancel/close changes no repository, IDE, VCS, DB, process, or network state | fixture/contract | `phase2MutationCheck` | ⬜ pending |
| Build/module/root topology | DISC-02/03/04 | T2-EXEC, T2-PATH | Consumes imported IDE facts and bounded static files without Gradle execution or path escape | host/fixture | `phase2HostCheck phase2FixtureCheck` | ⬜ pending |
| Stores/migration roots | DISC-05 | T2-DB, T2-SECRET | Reports store/changelog evidence without resolving credentials or opening a connection | unit/fixture | `phase2FixtureCheck phase2MutationCheck` | ⬜ pending |
| Semantic inventory | DISC-06/07 | T2-PSI, T2-STALE | Stable IDs, owners, provenance, locations, diagnostics, and fingerprints are deterministic | unit/host/golden | `phase2FastCheck phase2HostCheck` | ⬜ pending |
| Incremental/cancellable index | DISC-08, TEAM-07 | T2-EDT, T2-CANCEL, T2-PSI | No blocking scan on EDT; smart/dumb updates cancel and dispose without invalid PSI retention | host/performance | `phase2HostCheck` | ⬜ pending |
| Relationship graph | DISC-09 | T2-STALE | Typed incoming/outgoing relationships reference valid snapshot artifacts and source evidence | unit/golden | `phase2FixtureCheck` | ⬜ pending |
| Native navigation | DISC-10 | T2-PATH, T2-STALE | Opaque ID resolves only to current in-project source and validated line/symbol | host/adversarial | `phase2HostCheck` | ⬜ pending |
| Onboarding UI | COMP-01/02/06/07, DISC-06/10 | T2-PAYLOAD, T2-SECRET | Non-technical read-only/profile/diagnostic copy matches backend facts; no absolute paths/source bodies/secrets | web/contract/a11y | `phase2WebCheck` | ⬜ pending |
| Exact installed hosts | all | T2-HOST | Same logical snapshot and useful UI in signed IDEA 253/262; no startup/JCEF/resource error | installed UAT | `phase2InstalledCheck` + evidence | ⬜ pending |

## Threat References

| ID | Threat |
|---|---|
| T2-CAP | capability escalation or permissive default |
| T2-MUTATE | repository, IDE config, cache, or VCS mutation |
| T2-EXEC | Gradle/build/application code execution |
| T2-PROCESS | wrapper/task/external process launch |
| T2-NET | dependency or arbitrary network access |
| T2-DB | database or file-store connection |
| T2-PATH | symlink, traversal, outside-root scan/navigation |
| T2-SECRET | credential, machine path, or source-content leakage |
| T2-PSI | invalid/long-lived PSI or unsafe threading |
| T2-STALE | stale snapshot authorizes or misreports capability |
| T2-EDT | blocking work freezes IntelliJ event thread |
| T2-CANCEL | work survives cancellation/project/tool-window disposal |
| T2-PAYLOAD | unbounded browser payload/memory pressure |
| T2-HOST | host-specific API/behavior divergence |

## Wave 0 Requirements

- [ ] Add platform-agnostic unit test source set for discovery/registry pure code.
- [ ] Add a deterministic fixture manifest and fixture generator beneath
  `plugin/fixtures/phase2/` with no credentials or proprietary source.
- [ ] Add before/after mutation tripwire for repository bytes, `.idea`,
  `.gradle`, VCS metadata, processes, sockets, and database hooks.
- [ ] Add exact 253/262 managed host discovery harnesses.
- [ ] Add Vitest + Testing Library + accessibility checks owned by the
  build-provisioned Node workflow.
- [ ] Add lifecycle tasks `phase2FastCheck`, `phase2FixtureCheck`,
  `phase2HostCheck`, `phase2WebCheck`, `phase2MutationCheck`, and `phase2Check`.
- [ ] Add deterministic snapshot/registry golden update workflow that cannot run
  during normal verification.
- [ ] Add test-controlled scheduler/cancellation seams and EDT assertions.

## Required Fixture Matrix

- Jmix 2.8: Java 17/21, Groovy/Kotlin DSL, Java/Kotlin/mixed source.
- Jmix 3.0: Java 21/25, Groovy/Kotlin DSL, Java/Kotlin/mixed source.
- Single module, multi-project, nested/composite/included builds, version
  catalogs, convention plugins, application, add-on functional/starter.
- Main/additional/custom stores and multiple Liquibase roots.
- Offline, unresolved/private dependency, stale/failed/absent import, dumb mode.
- Earlier 2.x, 1.x/CUBA, future, non-Jmix, conflicting and malformed profiles.
- Custom/generated/test roots, symlink escape, oversized/deep trees.
- Every semantic artifact kind and unresolved/cyclic relationship cases.
- Deterministic enterprise synthetic scale fixture.

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|---|---|---|---|
| Installed onboarding in exact signed hosts | COMP-01/02/06/07, DISC-06/10 | Native IntelliJ/JCEF/project import and visual copy are not fully represented by managed tests | Install fresh same-revision ZIP in isolated signed IDEA 2025.3 and 2026.2 profiles, open representative read-only fixtures, inspect profile/tree/inventory/diagnostics, navigate to source, cancel/close, review logs and screenshots |
| Non-technical diagnostic comprehension | COMP-07 | Human wording/decision comprehension needs judgment | Review indexing, import-failed, conflicting, legacy, future, untrusted, and unsupported states; confirm each says what was observed, why capability is unavailable, and the tested/migration path |
| Zero-mutation observation | DISC-01 | Automated tripwire is primary but final end-user flow needs corroboration | Compare fixture/VCS/IDE state before and after installed-product browsing; confirm no Gradle/task/database/network prompt or side effect |

## Performance and Payload Budgets

- No blocking discovery on EDT; managed entrypoint returns/schedules within
  50 ms.
- Small-fixture topology snapshot <=2 s after imported model readiness.
- Single-file incremental semantic update <=1 s p95 on synthetic fixture.
- Cancellation prevents new work within 250 ms at test checkpoints.
- Browser page <=500 artifacts and <=1 MiB serialized payload.
- Disposal leak probe retains no Project, PSI, browser, or scheduler scope.

## Validation Sign-Off

- [x] Every phase requirement is mapped to an automated capability gate.
- [x] Sampling continuity prevents three unchecked tasks.
- [x] Negative security fixtures are mandatory.
- [x] No watch-mode command is permitted.
- [x] `nyquist_compliant: true` set in frontmatter.
- [ ] Wave 0 infrastructure implemented.
- [ ] Quick feedback measured below 120 seconds after warm-up.
- [ ] Full clean dual-host gate green.
- [ ] Signed installed-product UAT and identity/read-only review passed.

**Approval:** planning contract approved 2026-07-28; execution evidence pending

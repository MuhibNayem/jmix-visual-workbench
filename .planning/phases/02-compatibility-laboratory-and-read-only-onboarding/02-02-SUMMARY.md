---
phase: 02-compatibility-laboratory-and-read-only-onboarding
plan: "02"
subsystem: discovery-compatibility
tags: [kotlin, compatibility-registry, static-parser, canonical-json, sha256, tdd]

requires:
  - phase: 02-compatibility-laboratory-and-read-only-onboarding
    provides: Immutable discovery contracts, evidence confidence, canonical JSON, and the IntelliJ-free core lane from Plan 02-01
provides:
  - Evidence-backed deny-by-default compatibility registry with zero Phase 2 write cells
  - Reason-coded compatibility downgrades for trust, import, index, conflict, legacy, future, and unmatched profiles
  - Pure static Gradle, version-catalog, topology, coordinate, JDK, and add-on evidence parser
  - Opaque internal add-on identifiers and permutation-stable registry/profile digests
affects: [phase-02-collectors, fixture-laboratory, host-discovery, onboarding-ui, phase-03-write-authorization]

tech-stack:
  added: []
  patterns:
    - Version-controlled compatibility data validated before evaluation
    - Pure token-level build parsing with explicit unknown and conflicting evidence
    - Imported evidence outranks matching static evidence without overriding conflicts
    - Internal coordinates are replaced by opaque SHA-256-derived identifiers

key-files:
  created:
    - plugin/src/main/kotlin/org/jmixworkbench/discovery/compatibility/CompatibilityRegistry.kt
    - plugin/src/main/resources/compatibility/phase2-registry.json
    - plugin/src/main/kotlin/org/jmixworkbench/discovery/static/GradleConfigParser.kt
    - plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/compatibility/CompatibilityRegistryTest.kt
    - plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/static/GradleConfigParserTest.kt
  modified: []

key-decisions:
  - "Allowlist only discovery.snapshot, discovery.inventory, discovery.relationships, and discovery.navigate in the Phase 2 registry; every other operation fails closed."
  - "Certify exact Jmix 2.8 Java 17/21 and Jmix 3.0 Java 21/25 read cells only when injected reviewed fixture evidence and both host lanes are present."
  - "Merge imported and static facts by evidence strength while preserving multiple distinct values as CONFLICTING rather than selecting a default."
  - "Redact configured internal coordinate groups to stable opaque SHA-256 identifiers before parser model output."

patterns-established:
  - "Registry gate: schema, operation, selector overlap, host lane, fixture, and write-state validation precedes evaluation."
  - "Parser gate: repository build text is never evaluated; dynamic or unresolved syntax produces reason-coded diagnostics."
  - "Deterministic evidence: normalized collections and facts are canonically serialized before SHA-256 hashing."

requirements-completed: [COMP-01, COMP-02, COMP-03, COMP-06, COMP-07, DISC-01, DISC-04]

duration: 15 min
completed: 2026-07-28
---

# Phase 02 Plan 02: Compatibility Registry and Static Gradle Evidence Summary

**A reviewed no-write compatibility registry and pure static Gradle evidence parser now produce reason-coded, deterministic authorization and profile facts without executing repository code.**

## Performance

- **Duration:** 15 min
- **Started:** 2026-07-28T08:01:30Z
- **Completed:** 2026-07-28T08:16:47Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- Added a version-controlled registry with 16 exact read-only cells covering four discovery operations across Jmix 2.8 Java 17/21 and Jmix 3.0 Java 21/25.
- Enforced deny-by-default evaluation for unknown operations, missing cells, write-like values, untrusted projects, stale/failed/absent imports, incomplete indexes, conflicts, legacy projects, and future profiles.
- Added validated, injected fixture evidence so core tests remain independent of the later laboratory manifest while still requiring reviewed fixture IDs and host lanes 253/262.
- Parsed Groovy/Kotlin DSL literals, version catalogs, convention plugins, includes, included builds, Jmix coordinates, Java targets, and imported coordinates without Gradle or scripting dependencies.
- Preserved dynamic, unresolved, malformed, and conflicting evidence explicitly; classified public/third-party/internal add-ons and redacted internal coordinates before output.
- Produced stable registry and profile SHA-256 digests independent of input or field order.

## Task Commits

Each TDD task was committed through RED and GREEN:

1. **Task 1 RED: Implement the reviewed deny-by-default registry** - `5ddcb89` (test)
2. **Task 1 GREEN: Implement the reviewed deny-by-default registry** - `5e3f83e` (feat)
3. **Task 2 RED: Parse static Gradle and profile evidence without evaluation** - `83deebe` (test)
4. **Task 2 GREEN: Parse static Gradle and profile evidence without evaluation** - `eed0c6a` (feat)

## Files Created/Modified

- `plugin/src/main/kotlin/org/jmixworkbench/discovery/compatibility/CompatibilityRegistry.kt` - Loads, validates, canonically digests, and evaluates reviewed compatibility cells with explicit downgrade reasons.
- `plugin/src/main/resources/compatibility/phase2-registry.json` - Contains only the 16 reviewed discovery read cells and no write-authorizing state.
- `plugin/src/main/kotlin/org/jmixworkbench/discovery/static/GradleConfigParser.kt` - Extracts bounded static Gradle/catalog/import evidence, merges confidence, redacts internal add-ons, and hashes canonical results.
- `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/compatibility/CompatibilityRegistryTest.kt` - Covers exact cells, degraded states, write rejection, overlap, missing evidence, and digest stability.
- `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/static/GradleConfigParserTest.kt` - Covers Groovy/Kotlin/catalog/import inputs, dynamic and malformed syntax, conflicts, redaction, confidence precedence, and permutations.

## Decisions Made

- Phase 2 registry data can authorize only the four named discovery operations and only as `CERTIFIED_READ_ONLY`; mutation-like operations and write-like state spellings are validation failures.
- Registry core tests use an injected synthetic `FixtureEvidenceIndex`; Plan 02-14 remains responsible for the definitive generated-manifest referential check.
- Exact imported model evidence promotes an identical static fact to `EXACT`, but distinct observed values always become `CONFLICTING`.
- Internal groups are caller-configured, and matching coordinates are represented only by stable opaque hashes in parser outputs and canonical JSON.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Corrected evidence-confidence precedence**

- **Found during:** Task 2 (Parse static Gradle and profile evidence without evaluation)
- **Issue:** The first merge implementation used enum ordinal order, which could allow `STRONG` static evidence to outrank matching `EXACT` imported evidence.
- **Fix:** Added an explicit confidence rank and a regression test proving matching imported evidence produces an `EXACT` merged fact with imported provenance.
- **Files modified:** `plugin/src/main/kotlin/org/jmixworkbench/discovery/static/GradleConfigParser.kt`, `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/static/GradleConfigParserTest.kt`
- **Verification:** Focused parser tests and `phase2FastCheck` pass under strict dependency verification.
- **Committed in:** `eed0c6a`

---

**Total deviations:** 1 auto-fixed (1 bug).
**Impact on plan:** The correction preserves the planned evidence-confidence semantics with no scope expansion.

## Issues Encountered

The initial sandboxed Gradle invocation could not acquire the existing user-cache wrapper lock. Verification was rerun with approved access to the existing Gradle cache; no project dependency or lock files changed.

## User Setup Required

None - no external service configuration required.

## Verification

- Full `./gradlew phase2CoreTest phase2FastCheck --dependency-verification=strict --no-daemon` passed in 5 seconds warm.
- Focused `CompatibilityRegistryTest` and `GradleConfigParserTest` gates passed together with the fast aggregate.
- Registry acceptance scans found `P2_WRITE_FORBIDDEN` and `P2_REGISTRY_CELL_MISSING` and confirmed zero `CERTIFIED_READ_WRITE` JSON cells.
- Parser acceptance scans found `P2_DYNAMIC_BUILD_LOGIC`, `P2_ALIAS_UNRESOLVED`, and `CONFLICTING`.
- Static scans found no Gradle, Groovy runtime, Kotlin scripting, process, network, SQL, or IDE write dependencies in the new pure parser/registry sources.
- JUnit XML exists under `plugin/build/test-results/phase2CoreTest/`, and `git diff --check` passed.

## Known Stubs

None. Empty input collections and nullable/conflicting evidence values are intentional representations of observed absence, not unwired runtime or UI data.

## Next Phase Readiness

- Plan 02-03 can consume deterministic registry decisions and parsed build/profile evidence without adding authorization logic to host collectors.
- Later fixture-laboratory work can replace the inline synthetic fixture index with generated manifest evidence for definitive registry certification.
- Mutation remains disabled; no Phase 2 cell authorizes a project write.

## Self-Check: PASSED

- All five implementation/test artifacts and this summary exist.
- Commits `5ddcb89`, `5e3f83e`, `83deebe`, and `eed0c6a` exist in repository history.
- Full strict tests, acceptance scans, JUnit XML check, no-write invariant, and `git diff --check` passed.

---
*Phase: 02-compatibility-laboratory-and-read-only-onboarding*
*Completed: 2026-07-28*

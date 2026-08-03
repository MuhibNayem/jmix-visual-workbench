---
phase: 02-compatibility-laboratory-and-read-only-onboarding
plan: "01"
subsystem: discovery-core
tags: [kotlin, junit5, canonical-json, sha256, discovery, intellij-independent]

requires:
  - phase: 01-clean-room-build-foundation
    provides: Pinned Gradle/Kotlin toolchain, strict dependency verification, and exact dual-host build structure
provides:
  - IntelliJ-free Phase 2 core test lane with JUnit XML and a fast lifecycle aggregate
  - Immutable discovery, evidence, profile, artifact, relationship, diagnostic, and compatibility contracts
  - Safe revision-bound project-relative source locators
  - Sorted canonical JSON plus deterministic snapshot and artifact SHA-256 identities
affects: [phase-02-collectors, compatibility-registry, semantic-inventory, read-only-bridge, host-adapters]

tech-stack:
  added:
    - Aggregate Kotlin JVM source set with Kotlin Test and JUnit 5 runtime
  patterns:
    - Allowlisted pure discovery compilation with no IntelliJ Platform classpath
    - Nullable and conflicting evidence instead of inferred prototype defaults
    - Canonical UTF-8 JSON with sorted facts and host-local metadata excluded

key-files:
  created:
    - plugin/src/main/kotlin/org/jmixworkbench/discovery/model/DiscoveryModel.kt
    - plugin/src/main/kotlin/org/jmixworkbench/discovery/model/CanonicalDiscoveryJson.kt
    - plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/PlatformIndependenceTest.kt
    - plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/model/DiscoveryModelTest.kt
    - plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/model/CanonicalDiscoveryJsonTest.kt
  modified:
    - plugin/build.gradle.kts

key-decisions:
  - "Phase 2 supersedes the Phase 1 Kotlin-free aggregate constraint only for allowlisted pure discovery compilation; the aggregate remains IntelliJ-free."
  - "Exclude snapshot IDs, project-local IDs, and creation timestamps from canonical JSON and snapshot identities."
  - "Represent source locations only as validated project-relative coordinates plus a revision fingerprint."
  - "Preserve absent or conflicting optional IDE evidence with nullable values and explicit confidence instead of installed/enabled defaults."

patterns-established:
  - "Pure lane: downstream discovery contracts must pass phase2CoreTest before host integration."
  - "Canonical identity: sort semantic collections and object keys before UTF-8 SHA-256 hashing."
  - "Safe locator: reject absolute paths, traversal, ambiguous separators, and unbound revisions at construction."

requirements-completed: [COMP-01, DISC-07, TEAM-07]

duration: 16min
completed: 2026-07-28
---

# Phase 02 Plan 01: Platform-Independent Discovery Contracts Summary

**IntelliJ-free discovery contracts now produce safe, permutation-stable canonical JSON and SHA-256 identities in a 7-second warm test lane.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-07-28T07:40:19Z
- **Completed:** 2026-07-28T07:56:40Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments

- Added `phase2CoreTest` and `phase2FastCheck` without any IntelliJ Platform runtime dependency; the warm aggregate completed in 6.55 seconds and emitted JUnit XML.
- Defined immutable discovery/evidence/profile/build/module/root/dependency/artifact/relationship/diagnostic/compatibility contracts, including the research-defined confidence, state, role, and relationship enums.
- Enforced project-relative, revision-bound `SourceLocator` values with no runtime navigation authority, machine path, PSI, or VFS handle.
- Implemented canonical UTF-8 JSON with sorted keys and semantic collections, plus SHA-256 helpers for snapshots and artifact identities.
- Covered missing and conflicting optional IDE evidence, absence of prototype defaults, input permutations, host-local incidental metadata, and exact platform independence.

## Task Commits

Each TDD task was committed through RED and GREEN:

1. **Task 1 RED: Establish the IntelliJ-free core test lane** - `7bfd419` (test)
2. **Task 1 GREEN: Establish the IntelliJ-free core test lane** - `b987328` (feat)
3. **Task 2 RED: Define immutable contracts and canonical identities** - `679513c` (test)
4. **Task 2 GREEN: Define immutable contracts and canonical identities** - `799c818` (feat)

## Files Created/Modified

- `plugin/build.gradle.kts` - Applies the pinned Kotlin JVM plugin and defines the allowlisted pure source sets, dependencies, JUnit task, and fast aggregate.
- `plugin/src/main/kotlin/org/jmixworkbench/discovery/model/DiscoveryModel.kt` - Immutable host-neutral discovery contracts and safe source-locator invariants.
- `plugin/src/main/kotlin/org/jmixworkbench/discovery/model/CanonicalDiscoveryJson.kt` - Canonical JSON, semantic normalization, and SHA-256 identity helpers.
- `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/PlatformIndependenceTest.kt` - Rejects IntelliJ artifacts/classes from the pure runtime.
- `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/model/DiscoveryModelTest.kt` - Covers safe locators, explicit unknown/conflicting evidence, and stable roles.
- `plugin/src/phase2CoreTest/kotlin/org/jmixworkbench/discovery/model/CanonicalDiscoveryJsonTest.kt` - Covers permutation stability, incidental metadata exclusion, normalized IDs, and UTF-8 SHA-256.

## Decisions Made

- Phase 2 supersedes the Phase 1 Kotlin-free aggregate constraint only for this strict pure-source allowlist; the aggregate remains free of IntelliJ Platform dependencies.
- Canonical snapshots intentionally omit `snapshotId`, `projectId`, and `createdAtEpochMillis`; those values describe local/runtime identity rather than normalized repository facts.
- Artifact IDs hash kind, build ID, module ID, and a Unicode-normalized semantic key with unambiguous separators.
- Optional IDE capability presence, enablement, and version are nullable; conflicting confidence never silently becomes installed or enabled.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Restored JVM dependency resolution in the aggregate build**

- **Found during:** Task 1 GREEN
- **Issue:** Node plugin 7.1.0 adds a project-level Ivy repository. With `PREFER_PROJECT`, that caused settings-level Maven Central to be ignored, so the new Kotlin/JUnit configurations could not resolve.
- **Fix:** Added the already-reviewed Maven Central repository at aggregate project scope and selected the explicit Kotlin JUnit 5 adapter.
- **Files modified:** `plugin/build.gradle.kts`
- **Verification:** Strict `phase2CoreTest`, `phase2FastCheck`, task discovery, and runtime dependency inspection all passed.
- **Committed in:** `b987328`

---

**Total deviations:** 1 auto-fixed (1 blocking issue).
**Impact on plan:** The fix was limited to the aggregate build and was required for the planned pure test lane; host repository policy remains unchanged.

## Issues Encountered

The first sandboxed Gradle invocation could not create a lock in the user Gradle cache. Verification was rerun with approved access to the existing cache; no project files or dependency locks were changed.

## User Setup Required

None - no external service configuration required.

## Verification

- `./gradlew phase2CoreTest --tests 'org.jmixworkbench.discovery.PlatformIndependenceTest' phase2FastCheck --dependency-verification=strict --no-daemon` passed.
- `./gradlew phase2CoreTest --tests 'org.jmixworkbench.discovery.model.*' phase2FastCheck --dependency-verification=strict --no-daemon` passed.
- Full `phase2CoreTest phase2FastCheck` passed warm in 6.55 seconds, below the 60-second pure-lane ceiling.
- `phase2CoreTestRuntimeClasspath` contains Kotlin/JUnit only and no IntelliJ Platform artifacts.
- JUnit XML exists at `plugin/build/test-results/phase2CoreTest/`.
- Contract/static acceptance searches and `git diff --check` passed.

## Known Stubs

None. Empty collection defaults and nullable evidence values are intentional representations of observed absence, not unwired UI data.

## Next Phase Readiness

- Plans 02-02 onward can consume already-tested, platform-neutral contracts rather than introducing core behavior inside host tests.
- The pure lane is ready to grow with compatibility registry, bounded parser, and fixture packages while retaining the same no-IntelliJ invariant.
- No mutation capability is enabled by this plan.

## Self-Check: PASSED

- All six created/modified implementation and test files exist.
- Commits `7bfd419`, `b987328`, `679513c`, and `799c818` exist in repository history.
- Final strict tests, runtime dependency inspection, JUnit XML check, and `git diff --check` passed.

---
*Phase: 02-compatibility-laboratory-and-read-only-onboarding*
*Completed: 2026-07-28*

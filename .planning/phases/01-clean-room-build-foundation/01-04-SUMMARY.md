---
phase: 01-clean-room-build-foundation
plan: "04"
subsystem: build-integrity-ci
tags: [dependency-locking, checksum-verification, github-actions, dependabot, intellij-platform]

requires:
  - phase: 01-clean-room-build-foundation
    provides: Exact dual-host plugin artifacts and aggregate verification lifecycle from Plans 02-03
provides:
  - Strict read-only Gradle locks and reviewed SHA-256 verification metadata
  - Deterministic clean root, IDEA 253, IDEA 262, and ZIP verification stages
  - Immutable wrapper-only CI with artifact/report retention
  - Build, compatibility, release-integrity, and automated validation contracts
affects: [phase-02-compatibility, release-gates, dependency-updates, contributor-builds]

tech-stack:
  added:
    - GitHub Actions CI with immutable action SHAs
    - Dependabot review-only update policy
    - Gradle dependency locking and verification metadata
  patterns:
    - Strict locks only on runtimeClasspath and testRuntimeClasspath
    - Root-owned serialized wrapper stages for composite build verification
    - Exact remote IDEA coordinates with build-number-validated local SDK overrides
    - Vendor-published SDK archive checksums and project-local Node

key-files:
  created:
    - .github/workflows/ci.yml
    - .github/dependabot.yml
    - docs/BUILDING.md
    - docs/COMPATIBILITY.md
    - docs/RELEASE-INTEGRITY.md
    - plugin/gradle/dependency-locks/README.md
    - plugin/hosts/idea253/gradle/dependency-locks/gradle.lockfile
    - plugin/hosts/idea262/gradle/dependency-locks/gradle.lockfile
  modified:
    - plugin/build.gradle.kts
    - plugin/gradle/verification-metadata.xml
    - plugin/hosts/idea253/build.gradle.kts
    - plugin/hosts/idea262/build.gradle.kts
    - .planning/phases/01-clean-room-build-foundation/01-VALIDATION.md
    - README.md

key-decisions:
  - "Lock only the two standard runtime configurations per lane and prove normal verification never rewrites lock state."
  - "Serialize the aggregate gate as nested wrapper stages so root clean, root resources, IDEA 253, IDEA 262, and ZIP inspection cannot race across composite builds."
  - "Keep exact remote IDEA 2025.3/2026.2 coordinates for CI while allowing exact lane-specific local SDK paths validated through Resources/build.txt."
  - "Use reviewed SHA-256 metadata, exact JetBrains archive sidecar hashes, and narrowly scoped trust only for unhashable local SDK directory pseudo-artifacts."
  - "Keep target-project mutation non-certified and disabled; Phase 1 documents future matrix cells without claiming support."

patterns-established:
  - "Supply-chain gate: wrapper, npm lock, Gradle locks, verification metadata, repository policy, and CI verification mode are all checked before host stages."
  - "CI gate: bootstrap only Java 21, invoke only the checked-in wrapper, and let the build provision exact Node and Java 21/25 toolchains."

requirements-completed: [PROD-01, PROD-02, PROD-03, PROD-06]

duration: 36min
completed: 2026-07-28
---

# Phase 1 Plan 4: Dependency Integrity and CI Gate Summary

**Strict dependency locks and reviewed SHA-256 verification feed a serialized wrapper-only CI gate for exact IDEA 2025.3 and 2026.2 plugin artifacts**

## Performance

- **Duration:** 36 min
- **Started:** 2026-07-27T20:03:42Z
- **Completed:** 2026-07-27T20:39:52Z
- **Tasks:** 2
- **Files modified:** 14

## Accomplishments

- Added strict, checked-in lock state for only `runtimeClasspath` and `testRuntimeClasspath` in each host lane, with before/after hash proof that verification is read-only.
- Added reviewed Gradle SHA-256 metadata for build plugins, Node, compiler/test dependencies, both SDK-derived runtimes, and the exact JetBrains-published remote IDEA DMG hashes.
- Reworked `phase1Check` into deterministic root-fast, IDEA 253, IDEA 262, and ZIP-content stages, each invoked through the checked-in wrapper under strict verification.
- Added immutable GitHub Actions CI and review-only Dependabot configuration without global Gradle/Node installation, auto-merge, publication, or signing behavior.
- Documented the contributor build path, host/target compatibility distinction, integrity update procedure, future secret interfaces, and deliberately disabled mutation scope.
- Completed the automated Phase 1 validation ledger while leaving the manual two-IDE installation checkpoint explicit.

## Task Commits

Each task was committed atomically:

1. **Task 1: Enforce dependency integrity and checked-in lock state** — `aace7f7` (chore)
2. **Task 2: Automate and document the strict Phase 1 gate** — `c80a153` (feat)

## Files Created/Modified

- `plugin/gradle/dependency-locks/README.md` and both lane lockfiles — exact lock regeneration and read-only verification workflow.
- `plugin/gradle/verification-metadata.xml` — SHA-256 metadata, exact remote SDK archive hashes, and narrowly scoped local-directory trust.
- Root and host Gradle builds — strict lock verification, integrity assertions, lane-specific local SDK seams, and serialized aggregate stages.
- `.github/workflows/ci.yml` — immutable wrapper validation, strict clean Phase 1 gate, and exact artifact/report uploads.
- `.github/dependabot.yml` — weekly review-only Gradle, npm, and GitHub Actions updates.
- `docs/BUILDING.md`, `docs/COMPATIBILITY.md`, and `docs/RELEASE-INTEGRITY.md` — contributor, compatibility, and integrity contracts.
- `README.md` and `01-VALIDATION.md` — current build entrypoint, future target matrix, and automated evidence.

## Decisions Made

- Restricted dependency locking to the two standard runtime configurations in each lane; IntelliJ platform/tooling configurations remain checksum-verified rather than incorrectly forced into lockfiles.
- Reused the root verification metadata for every nested wrapper stage. CI and fresh builds retain `intellijIdeaUltimate("2025.3")` and `intellijIdeaUltimate("2026.2")`.
- Added `localIdea253Path` and `localIdea262Path` for aggregate workstation validation, with the existing `localIdeaPath` fallback for direct single-lane builds.
- Trusted only the exact `localIde:IU` build-number coordinates because Gradle exposes extracted SDK roots as unhashable directory pseudo-artifacts. File artifacts and generated Ivy metadata remain SHA-256 verified.
- Pinned GitHub Actions by full commit SHA and provisioned only a Java 21 bootstrap JDK; Gradle owns Node 24.18.0 and Java 21/25 toolchain acquisition.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Corrected lock-entry validation**
- **Found during:** Task 1
- **Issue:** The initial assertion regex did not recognize Gradle lock lines ending in `=runtimeClasspath`.
- **Fix:** Matched the exact configuration suffix representation used by generated lockfiles.
- **Files modified:** Both host build scripts.
- **Commit:** `aace7f7`

**2. [Rule 1 - Bug] Serialized the clean composite gate**
- **Found during:** Task 2 clean aggregate verification
- **Issue:** Composite included-build prerequisites could inspect or write shared root outputs while root `clean` or `buildWebUi` was running.
- **Fix:** Kept direct host tasks fail-fast and made `phase1Check` orchestrate explicit nested wrapper stages: outer clean, root fast gate, clean IDEA 253 gate, clean IDEA 262 gate, then ZIP inspection.
- **Files modified:** `plugin/build.gradle.kts`
- **Commit:** `c80a153`

**3. [Rule 3 - Blocking] Supported two exact extracted SDKs in one aggregate run**
- **Found during:** Task 2 full-gate verification
- **Issue:** Only transformed IDEA SDK directories remained cached; the original source DMGs were unavailable offline, and the single local path property could not target two lanes.
- **Fix:** Added lane-specific properties with strict IU build-number validation and retained the single-lane fallback. Remote coordinates remain the default for CI and fresh builds.
- **Files modified:** Both host build scripts and `docs/BUILDING.md`.
- **Commit:** `c80a153`

**4. [Rule 3 - Blocking] Handled Gradle local-directory verification semantics**
- **Found during:** Task 2 strict local SDK verification
- **Issue:** Gradle can checksum files inside SDK-derived configurations but cannot checksum the extracted SDK root directory pseudo-artifact.
- **Fix:** Generated all available SHA-256 entries and narrowly trusted only the exact local IU build coordinates; remote CI archives use JetBrains-published SHA-256 values.
- **Files modified:** `plugin/gradle/verification-metadata.xml` and `docs/RELEASE-INTEGRITY.md`.
- **Commit:** `c80a153`

**5. [Rule 1 - Verification] Omitted an unsupported Gradle flag**
- **Found during:** Task verification
- **Issue:** Gradle 9.5.1 does not support the plan's `--non-interactive` flag.
- **Fix:** Used `--no-daemon --no-configuration-cache --stacktrace` without weakening strict dependency verification.
- **Files modified:** None.
- **Commit:** N/A

---

**Total deviations:** 5 auto-fixed (2 build correctness bugs, 2 blocking local verification issues, 1 verification-only correction).
**Impact on plan:** The changes make the required aggregate entrypoint deterministic and preserve exact remote CI inputs. No product mutation or release-publication capability was enabled.

## Issues Encountered

- Online verification-metadata finalization stalled after resolution; deterministic offline generation from already cached exact artifacts completed successfully.
- The original IDEA DMGs were absent from the local artifact cache. The build used already-extracted exact SDKs for local proof and pinned JetBrains' official checksum sidecars for fresh remote CI resolution.
- Plugin Verifier continues to report the already-known deprecated/experimental IntelliJ API usages, but both exact lanes are compatible.

## Known Stubs

None in files created or modified by this plan. Future signing, SBOM, publication, and target-project mutation are documented interfaces or explicitly disabled roadmap work, not incomplete Phase 1 implementations.

## Authentication Gates

None.

## User Setup Required

Normal contributor builds need a Java 21 bootstrap JDK and first-run network access. No CI secrets or manual credentials are required for Phase 1.

## Verification

- Strict read-only integrity command passed offline: `snapshotLockHashes verifyLockedConfigurations verifyDependencyIntegrity phase1FastCheck compareLockHashes`.
- Clean aggregate command passed offline in 1m23s using exact validated IU-253.28294.334 and IU-262.8665.258 local SDKs.
- IDEA 253 and IDEA 262 clean compilation, unit tests, smoke tests, packaging, no-bundled-Kotlin checks, and Plugin Verifier all passed.
- Plugin Verifier 1.409 result: **Compatible** for both exact hosts.
- IDEA 253 ZIP SHA-256: `99c051d7e523f98d12da4d3b69e7aed8f8f362911488538b04c30da18622b46a`.
- IDEA 262 ZIP SHA-256: `9aacc63e5d4ba0f8f2c10f4c4c0fa460bc4deacdf80bce03663a389ff55d5c16`.
- Both ZIPs contain frontend input SHA-256 `d3e09141750875fbe53c56206d309ae60e2b25224fd3e94f1b9cfd3f051c6ae4`.
- XML, YAML, immutable action pins, developer-path scan, documentation matrix grep, `git diff --check`, and the 1 GiB disk reserve all passed.

## Next Phase Readiness

- Phase 2 can consume the exact build matrix and safe compatibility language without reopening build-tool integrity.
- Dependency updates have a documented lock/checksum regeneration path and cannot silently bypass strict CI verification.
- Manual installation in exact minimum IDEs remains the only Phase 1 approval checkpoint; automated Nyquist evidence is complete.

## Self-Check: PASSED

- All 14 created or modified plan files and this summary exist.
- Task commits `aace7f7` and `c80a153` are present in repository history.
- Exact remote SDK hashes are present, no machine-specific path is serialized, and the summary diff is clean.

---
*Phase: 01-clean-room-build-foundation*
*Completed: 2026-07-28*

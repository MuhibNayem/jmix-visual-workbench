---
phase: 01-clean-room-build-foundation
plan: "02"
subsystem: build-foundation
tags: [gradle, node, intellij-platform, kotlin, toolchains, composite-build]

requires:
  - phase: 01-clean-room-build-foundation
    provides: Original Jmix Visual Workbench identity and clean-room boundary from Plan 01
provides:
  - Complete checksum-pinned Gradle 9.5.1 wrapper
  - Gradle-provisioned Node 24.18.0 frontend build with same-revision input manifest
  - Isolated IDEA 253-261 and IDEA 262 host build lanes over canonical sources
  - Auto-provisioned Eclipse Temurin Java 21 and 25 compiler toolchains
affects: [plugin-packaging, compatibility-verification, dependency-integrity, ci]

tech-stack:
  added:
    - Gradle 9.5.1 wrapper
    - IntelliJ Platform Gradle Plugin 2.18.0
    - Kotlin Gradle Plugin 2.4.0
    - Node Gradle Plugin 7.1.0
    - Foojay Toolchains Resolver 1.0.0
    - Node 24.18.0
  patterns:
    - Aggregate build with isolated included host builds
    - Build-owned frontend resources with declared-input SHA-256 fingerprint
    - Exact compiler launcher metadata verification per host lane

key-files:
  created:
    - plugin/gradlew
    - plugin/gradlew.bat
    - plugin/gradle/wrapper/gradle-wrapper.jar
    - plugin/gradle/libs.versions.toml
    - plugin/buildSrc/src/main/java/org/jmixworkbench/build/AssembleWebBundleTask.java
    - plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyWebBundleTask.java
    - plugin/hosts/idea253/build.gradle.kts
    - plugin/hosts/idea262/build.gradle.kts
  modified:
    - plugin/build.gradle.kts
    - plugin/settings.gradle.kts
    - plugin/gradle.properties
    - plugin/gradle/wrapper/gradle-wrapper.properties
    - webui/package.json
    - webui/package-lock.json

key-decisions:
  - "Keep the aggregate build free of Kotlin and IntelliJ plugins; apply only the pinned Node plugin and delegate host compilation to isolated included builds."
  - "Allow project repositories only in the aggregate build because Node plugin 7.1.0 adds its pinned distribution Ivy repository; keep both host builds on FAIL_ON_PROJECT_REPOS."
  - "Use Eclipse Temurin as the explicit Foojay-provisioned vendor for Java 21 and 25 compiler launchers."

patterns-established:
  - "Host lane isolation: idea253 compiles Kotlin 2.2/JVM 21 for builds 253-261.*, while idea262 compiles Kotlin 2.4/JVM 25 for build 262.* with explicit JCEF dependency."
  - "Web resource integrity: npm ci and Vite produce staging output under plugin/build, then a custom task assembles and fingerprints the packaged bundle."

requirements-completed: [PROD-01, PROD-03]

duration: 20min
completed: 2026-07-27
---

# Phase 1 Plan 2: Self-Sustaining Dual-Lane Build Summary

**Checksum-pinned Gradle entry point with downloaded Node, fingerprinted same-revision web resources, and isolated IntelliJ 253/262 builds on provisioned Java 21/25 toolchains**

## Performance

- **Duration:** 20 min
- **Started:** 2026-07-27T18:42:33Z
- **Completed:** 2026-07-27T19:02:51Z
- **Tasks:** 2
- **Files modified:** 25

## Accomplishments

- Restored the official Gradle 9.5.1 wrapper scripts/JAR and pinned both distribution and wrapper JAR SHA-256 checksums.
- Made Gradle download Node 24.18.0, run `npm ci`, build Vite output below `plugin/build/`, and reject missing or stale packaged UI resources using a declared-input digest.
- Added isolated IDEA 2025.3/253-261 and IDEA 2026.2/262 host builds sharing one Kotlin source tree while enforcing their distinct Kotlin, JVM, descriptor, and JCEF contracts.
- Proved Foojay provisioned Eclipse Temurin Java 21 and 25 compiler launchers while Node was absent from `PATH`.
- Added aggregate compile, test, build, verifier, toolchain, and Phase 1 lifecycle tasks without applying Kotlin or IntelliJ plugins to the aggregate root.

## Task Commits

Each task was committed atomically:

1. **Task 1: Restore and pin the Gradle wrapper and build version sources** — `802d0a3` (chore)
2. **Task 2: Build same-revision web resources and two host-specific plugin lanes** — `ca769e8` (feat)

## Files Created/Modified

- `plugin/gradlew`, `plugin/gradlew.bat`, `plugin/gradle/wrapper/` — complete official Gradle 9.5.1 wrapper with exact checksums.
- `plugin/gradle/libs.versions.toml` — exact Gradle, IntelliJ, Kotlin, Foojay, Node plugin/runtime, Gson, and IDE lane versions.
- `plugin/build.gradle.kts` — Node provisioning, npm/Vite orchestration, web integrity checks, and composite lifecycle tasks.
- `plugin/buildSrc/src/main/java/org/jmixworkbench/build/` — configuration-cache-aware bundle assembly, fingerprinting, and verification task types.
- `plugin/hosts/idea253/` — Kotlin 2.2/JVM 21 build lane for IDEA builds 253 through 261.*.
- `plugin/hosts/idea262/` — Kotlin 2.4/JVM 25 build lane for IDEA 262.* with explicit `com.intellij.modules.jcef` and `intellij.platform.ui.jcef` dependencies.
- `webui/package.json`, `webui/package-lock.json` — exact Node 24.18.0 engine contract aligned in package and lock metadata.

## Decisions Made

- Kept host builds as isolated composites because their IntelliJ dependencies, Kotlin API levels, JVM bytecode, and descriptors are intentionally incompatible build contexts.
- Selected Eclipse Temurin for deterministic Foojay toolchain resolution rather than silently accepting whichever local JDK vendor happens to be installed.
- Kept configuration caching enabled generally while marking only cross-build source/ZIP/metadata inspection tasks incompatible; those tasks cannot serialize Gradle script object references but remain deterministic and blocking.
- Used host-local `verifyWebBundle` tasks so direct lane resource/package execution rejects missing or stale aggregate output; root lifecycle tasks build and verify the current UI first.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Corrected unsupported Gradle verification option**
- **Found during:** Task 1 verification
- **Issue:** Gradle 9.5.1 rejects the plan's `--non-interactive` option as unknown.
- **Fix:** Ran the same verification commands without that unsupported flag and used `--no-daemon --stacktrace` for bounded diagnostics.
- **Files modified:** None
- **Verification:** Wrapper and restricted-PATH gates exited successfully.
- **Committed in:** N/A (verification-only correction)

**2. [Rule 3 - Blocking] Allowed the Node plugin's required project-scoped Ivy repository**
- **Found during:** Task 2 composite configuration
- **Issue:** `FAIL_ON_PROJECT_REPOS` at the aggregate root rejected the pinned Node plugin's `Node.js` Ivy repository.
- **Fix:** Set only the aggregate root to `PREFER_PROJECT` with an explanatory comment; both host builds retain `FAIL_ON_PROJECT_REPOS`.
- **Files modified:** `plugin/settings.gradle.kts`
- **Verification:** `./gradlew help --no-daemon --stacktrace` passed.
- **Committed in:** `ca769e8`

**3. [Rule 3 - Blocking] Bounded configuration-cache handling for composite verification tasks**
- **Found during:** Task 2 restricted-PATH gate
- **Issue:** Four source/metadata inspection tasks passed functionally but Gradle failed while serializing their script-object closures into configuration-cache state.
- **Fix:** Explicitly marked only those external inspection tasks as configuration-cache-incompatible.
- **Files modified:** `plugin/build.gradle.kts`, `plugin/hosts/idea253/build.gradle.kts`, `plugin/hosts/idea262/build.gradle.kts`
- **Verification:** The same restricted-PATH gate completed with `BUILD SUCCESSFUL`.
- **Committed in:** `ca769e8`

---

**Total deviations:** 3 auto-fixed (1 bug, 2 blocking issues).
**Impact on plan:** All corrections were required to execute the exact pinned build on Gradle 9.5.1; no runtime feature or mutation capability was added.

## Issues Encountered

- The locked npm dependency graph reports one moderate and one high audit finding. This plan preserved the existing lock graph and did not perform a breaking `npm audit fix --force`; Plan 01-04 owns dependency integrity review and lock/verification enforcement.
- Gradle reports configuration-cache serialization diagnostics for the explicitly incompatible composite inspection tasks, discards that cache entry, and completes the gate successfully. Normal build tasks remain configuration-cache eligible.

## User Setup Required

None - Gradle downloads its pinned wrapper distribution, Node runtime, and Java compiler toolchains as needed.

## Verification

- Official wrapper JAR SHA-256: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.
- Wrapper distribution checksum pinned to `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`.
- `./gradlew --version` reported Gradle 9.5.1.
- `./gradlew help --no-daemon --stacktrace` passed for the aggregate composite.
- With `PATH=/usr/bin:/bin:/usr/sbin:/sbin`, Gradle downloaded/used Node 24.18.0, ran `npm ci`, compiled the React/Vite bundle, generated `index.html` plus `build-info.json`, verified the input digest, resolved Java 21/25 launcher metadata, and passed `phase1FastCheck`.
- Toolchain metadata reported Eclipse Temurin Java 21 for idea253 and Eclipse Temurin Java 25 for idea262.
- Host build assertions verify exact build ranges, Kotlin/JVM contracts, explicit 262 JCEF dependency, resource freshness dependencies, and rejection of bundled Kotlin stdlib/coroutines.
- `git diff --check` passed.

## Next Phase Readiness

- Plan 01-03 can compile/package the canonical plugin sources through both host lanes and add ZIP/load/resource smoke assertions.
- Plan 01-04 can add dependency locks, verification metadata, artifact integrity, and full clean aggregate gates.
- Existing prototype mutation paths remain non-certified and unchanged.

## Self-Check: PASSED

- All created wrapper, build logic, host lane, and summary files exist.
- Task commits `802d0a3` and `ca769e8` are present in repository history.
- Generated `.gradle`, `.intellijPlatform`, `build`, `node_modules`, and `dist` directories are ignored and not staged.

---
*Phase: 01-clean-room-build-foundation*
*Completed: 2026-07-27*

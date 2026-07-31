---
phase: 01-clean-room-build-foundation
plan: "03"
subsystem: plugin-packaging
tags: [intellij-platform, plugin-verifier, jcef, zip, provenance, smoke-tests]

requires:
  - phase: 01-clean-room-build-foundation
    provides: Dual exact host lanes and same-revision frontend bundle from Plan 02
provides:
  - Canonical Kotlin compilation and shared tests on IDEA 2025.3 and IDEA 2026.2
  - Deterministic lane-suffixed plugin ZIPs with embedded frontend provenance
  - Exact-host Plugin Verifier compatibility evidence for IU-253.28294.334 and IU-262.8665.258
  - Safe packaged-resource resolution and stable JCEF/bundle fallback diagnostics
affects: [release-gates, plugin-installation, compatibility-certification, phase-01-integrity]

tech-stack:
  added:
    - IntelliJ Plugin Verifier 1.409
    - JUnit 4 runtime bridge for IntelliJ-hosted JUnit Platform tests
  patterns:
    - Exact host SDK verification through pluginVerification.ides.current()
    - Strict optional local SDK override with build-number validation
    - Nested plugin ZIP inspection with shared frontend input digest
    - Classpath-only production resource resolution with stable fallback codes

key-files:
  created:
    - plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyPluginZipContentsTask.java
    - plugin/src/test/kotlin/org/jmixworkbench/toolwindow/WorkbenchUiResourceResolverTest.kt
    - plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253PluginSmokeTest.kt
    - plugin/hosts/idea262/src/test/kotlin/org/jmixworkbench/host/idea262/Idea262PluginSmokeTest.kt
    - .planning/phases/01-clean-room-build-foundation/deferred-items.md
  modified:
    - plugin/build.gradle.kts
    - plugin/gradle.properties
    - plugin/hosts/idea253/build.gradle.kts
    - plugin/hosts/idea262/build.gradle.kts
    - plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt
    - plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt
    - plugin/src/main/resources/META-INF/plugin.xml
    - plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml
    - plugin/hosts/idea262/src/main/resources/META-INF/plugin.xml

key-decisions:
  - "Use the verifier-compliant unreleased plugin ID org.jmixworkbench rather than muting JetBrains marketplace policy."
  - "Keep exact remote IntelliJ IDEA Ultimate coordinates for clean builds and allow a property-driven local SDK only when its IU build branch matches the lane."
  - "Verify each lane against its current exact platform so packaging and compatibility checks share one SDK."
  - "Production tool-window startup resolves only the packaged classpath entry point; explicit development URLs remain opt-in."

patterns-established:
  - "Artifact gate: both ZIPs must contain identity, descriptor range, web entry point, hashed assets, provenance manifest, icon, license, and notice while excluding Node/npm caches, source maps, stale identity, and developer paths."
  - "Host smoke gate: lane tests load the packaged descriptor/resource and exercise non-mutating fallback seams before Plugin Verifier runs."

requirements-completed: [PROD-02, PROD-03]

duration: 54min
completed: 2026-07-28
---

# Phase 1 Plan 3: Installable Dual-Lane Plugin Artifacts Summary

**Verifier-compatible IDEA 2025.3 and 2026.2 plugin ZIPs with deterministic packaging, embedded frontend provenance, packaged-resource smoke tests, and safe JCEF fallback diagnostics**

## Performance

- **Duration:** 54 min
- **Started:** 2026-07-27T19:05:54Z
- **Completed:** 2026-07-27T20:00:07Z
- **Tasks:** 2
- **Files modified:** 17

## Accomplishments

- Fixed the canonical DataGrid generator compile errors and proved the full shared Kotlin tree compiles under both exact host lanes.
- Removed checkout-relative and blank-page production fallbacks; the tool window now uses packaged resources or displays stable `JVW-JCEF-UNAVAILABLE` / `JVW-WEB-BUNDLE-MISSING` diagnostics.
- Built deterministic `idea253` and `idea262` ZIPs containing the same fingerprinted frontend source revision, required legal/icon resources, and no Node/npm runtime or cache payloads.
- Added shared resource tests plus lane smoke tests for patched descriptor identity/ranges, JCEF dependency, packaged frontend loading, factory construction, and non-mutating fallback behavior.
- Ran IntelliJ Plugin Verifier 1.409 without policy mutes: `org.jmixworkbench:1.0.0` is compatible with IU-253.28294.334 and IU-262.8665.258.
- Kept all prototype project mutation paths non-certified and unchanged.

## Task Commits

Each task was committed atomically:

1. **Task 1: Resolve shared-source compile blockers and harden packaged resource fallback** — `e8fea1e` (fix)
2. **Task 2: Assemble and inspect both plugin ZIPs with host smoke evidence** — `9563479` (feat)

## Files Created/Modified

- `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt` — consistent DataGrid helper call and receiver.
- `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt` — classpath resource resolver and explicit safe fallback panels.
- `plugin/src/test/` and `plugin/hosts/*/src/test/` — shared resolver tests and exact-lane packaged plugin smoke tests.
- `plugin/hosts/*/build.gradle.kts` — shared tests, deterministic archives, verifier CLI/current IDE configuration, legal resources, and guarded local SDK validation.
- `plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyPluginZipContentsTask.java` — nested ZIP/JAR content, provenance, and forbidden-payload gate.
- `plugin/build.gradle.kts` — aggregate smoke, artifact inspection, verifier, and Phase 1 lifecycle wiring.
- Plugin descriptors and identity records — verifier-compliant `org.jmixworkbench` ID.

## Decisions Made

- Changed the unreleased ID from `org.jmixworkbench.intellij` to `org.jmixworkbench` when Plugin Verifier rejected the reserved template word. No verifier problem was muted.
- Retained exact `intellijIdeaUltimate("2025.3")` and `intellijIdeaUltimate("2026.2")` release coordinates. The optional `localIdeaPath` seam is property-driven, validates `Resources/build.txt`, and cannot silently target the wrong IU branch.
- Declared both public 2026.2 JCEF modules because `intellij.platform.ui.jcef` depends on `intellij.libraries.jcef`, which owns the `org.cef.*` classes.
- Removed the obsolete unified-platform `com.intellij.java` build dependency while preserving `<depends>com.intellij.modules.java</depends>` in both packaged descriptors.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Corrected canonical DataGrid generator compilation**
- **Found during:** Task 1
- **Issue:** The call used the wrong helper name and the helper ignored its explicit parent receiver.
- **Fix:** Called `generateDataGridContents` and created columns through `parent.child("columns")`.
- **Files modified:** `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt`
- **Commit:** `e8fea1e`

**2. [Rule 2 - Critical] Replaced unsafe/misleading resource fallbacks**
- **Found during:** Task 1
- **Issue:** Production startup could read a checkout-relative path and silently load `about:blank`.
- **Fix:** Added a testable classpath resolver and explicit safe Swing diagnostics for missing JCEF or bundle resources.
- **Files modified:** Tool-window factory and shared resolver tests.
- **Commit:** `e8fea1e`

**3. [Rule 3 - Blocking] Aligned unified-platform build dependencies**
- **Found during:** Task 1 compilation
- **Issue:** `com.intellij.java` is not a bundled plugin ID in unified IDEA, while 2026.2 exposes JCEF as dependent public modules.
- **Fix:** Removed the obsolete Java build dependency, preserved the Java descriptor dependency, and declared `intellij.libraries.jcef` plus `intellij.platform.ui.jcef` for 2026.2.
- **Files modified:** Both host build scripts.
- **Commit:** `9563479`

**4. [Rule 3 - Blocking] Completed test and verifier runtime declarations**
- **Found during:** Task 1/2 verification
- **Issue:** IntelliJ-hosted JUnit Platform tests needed the JUnit 4 runtime bridge, and `verifyPlugin` had no declared verifier CLI.
- **Fix:** Added JUnit 4 test runtime and `pluginVerifier()` to both isolated lanes.
- **Files modified:** Both host build scripts.
- **Commit:** `9563479`

**5. [Rule 3 - Blocking] Added root clean lifecycle and disk-safe exact-SDK validation**
- **Found during:** Task 2 aggregate verification
- **Issue:** The aggregate root had no `clean` task, and re-resolving removed IDE source images exceeded safe disk headroom despite proven exact SDK transforms.
- **Fix:** Applied Gradle `base`; kept exact remote release coordinates while adding an optional build-number-validated `localIdeaPath` override; used `ides.current()` so verifier and packaging share the same exact SDK.
- **Files modified:** Root and host build scripts.
- **Commit:** `9563479`

**6. [Rule 1 - Production Gate] Adopted a verifier-compliant plugin ID**
- **Found during:** Task 2 Plugin Verifier
- **Issue:** JetBrains rejected the unreleased ID `org.jmixworkbench.intellij` because plugin IDs may not contain the reserved template word `intellij`.
- **Fix:** Changed all descriptors, assertions, tests, properties, and planning identity records to `org.jmixworkbench`; no verifier mute was added.
- **Files modified:** Plugin descriptors, Gradle properties, smoke/ZIP assertions, and identity planning records.
- **Commit:** `9563479` plus final documentation commit.

**7. [Rule 1 - Bug] Removed unsupported Gradle verification flag**
- **Found during:** Task verification
- **Issue:** Gradle 9.5.1 does not support the plan's `--non-interactive` option.
- **Fix:** Ran equivalent gates with `--no-daemon --no-configuration-cache --stacktrace`.
- **Files modified:** None.
- **Commit:** N/A (verification-only correction)

---

**Total deviations:** 7 auto-fixed (3 bugs/production gates, 1 critical safeguard, 3 blocking build issues).
**Impact on plan:** Every correction tightened compilation, packaging, verifier compliance, or safe local validation. No mutation capability or advertised compatibility cell was added.

## Issues Encountered

- IDE images and extracted SDKs temporarily exhausted disk headroom. Downloads were serialized, the executor stopped before unsafe extraction, and only explicitly approved redundant DMG/temp cache files were removed. Final validation retained more than the 1 GiB safety reserve.
- Plugin Verifier reports six pre-existing deprecated and six inherited experimental IntelliJ API usages on each lane. Both exact hosts are compatible; details are tracked in `deferred-items.md`.
- Gradle configuration-cache diagnostics from prior composite inspection tasks were avoided during final proof with `--no-configuration-cache`; the tasks themselves remained blocking and deterministic.

## Known Stubs

None in files created or modified by this plan. Prototype mutation behavior remains intentionally non-certified rather than stubbed into the release gate.

## User Setup Required

None for normal builds. Clean CI/builds use the exact remote IDEA Ultimate coordinates. The optional `-PlocalIdeaPath=...` override exists only for validated local SDK reuse and rejects the wrong build branch.

## Verification

- IDEA 2025.3: canonical compile, shared tests, host smoke tests, deterministic ZIP build, no-bundled-Kotlin check, and Plugin Verifier all passed against IU-253.28294.334.
- IDEA 2026.2: the same gates passed against IU-262.8665.258 with explicit JCEF modules and descriptor dependency.
- Plugin Verifier result on both lanes: **Compatible**, dynamically loadable, no compatibility errors.
- IDEA 2025.3 ZIP SHA-256: `f973ed97128470c067e79fdb49afba9008a53bcbb558ca0c84c5fddf5b7863c9`.
- IDEA 2026.2 ZIP SHA-256: `e798981e0b584e99e5bf318628cff5962b4cd3531566d5d7ba60f5b1bf0df373`.
- Both ZIPs contain web input SHA-256: `d3e09141750875fbe53c56206d309ae60e2b25224fd3e94f1b9cfd3f051c6ae4`.
- ZIP inspection proved descriptor ID/name/range, lane JCEF policy, packaged `webui/index.html`, referenced hashed assets, `build-info.json`, icon, `LICENSE`, and `NOTICE`; it rejected caches, runtimes, source maps, stale identity, and developer paths.
- `git diff --check` passed and generated build/cache outputs remain ignored.

## Next Phase Readiness

- Plan 01-04 can lock dependency resolution and verification metadata around two installable, exact-host-compatible artifacts.
- Release automation can use `phase1Check`; disk-constrained local validation can run each lane against a validated local SDK and inspect the already-built ZIPs.
- Mutation remains disabled until later compatibility and atomic-change phases certify it.

## Self-Check: PASSED

- All created source, test, verifier, deferred-item, and summary files exist.
- Task commits `e8fea1e` and `9563479` are present in repository history.
- Exact IU-253.28294.334 and IU-262.8665.258 Plugin Verifier report directories exist.

---
*Phase: 01-clean-room-build-foundation*
*Completed: 2026-07-28*

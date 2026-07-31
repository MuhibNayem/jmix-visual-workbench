---
phase: 01-clean-room-build-foundation
fixed_at: 2026-07-27T21:32:08Z
review_path: .planning/phases/01-clean-room-build-foundation/01-REVIEW.md
iteration: 2
findings_in_scope: 1
fixed: 1
skipped: 0
status: all_fixed
---

# Phase 1: Code Review Fix Report

**Fixed at:** 2026-07-27T21:32:08Z
**Source review:** `.planning/phases/01-clean-room-build-foundation/01-REVIEW.md`
**Iteration:** 2

**Summary:**

- Findings in scope: 1
- Fixed: 1
- Skipped: 0

## Fixed Issues

### WR-01: Host smoke tests still bypass real tool-window startup wiring

**Files modified:** `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`, `plugin/src/test/kotlin/org/jmixworkbench/toolwindow/WorkbenchToolWindowFactoryIntegrationTest.kt`, both host build scripts and descriptor tests, and `plugin/build.gradle.kts`
**Commit:** `037bab2`
**Applied fix:** Introduced injectable browser, bridge, content, and runtime seams while retaining the public zero-argument IntelliJ factory constructor. The integration test now invokes the real `createToolWindowContent` method and proves packaged and development URL loading, bridge policy, content-manager attachment, all fallback paths, disposer attachment, bridge-before-browser disposal, and exactly-once lifecycle behavior. Each host now registers an independent IntelliJ Platform-managed `testIde` task filtered to this integration class; the ordinary unit-test task excludes it, so `hostSmokeTest` is no longer an alias.

## Verification

- `:idea253:hostSmokeTest` passed against the validated local IU-253.28294.334 SDK.
- `:idea262:hostSmokeTest` passed against the validated local IU-262.8665.258 SDK.
- Aggregate `hostSmokeTest` passed and invoked both dedicated managed host tasks.
- Final `./gradlew clean phase1Check --dependency-verification=strict --offline --no-daemon --no-configuration-cache --stacktrace` passed.
- Plugin Verifier 1.409 reported both host artifacts compatible.
- Final ZIP SHA-256: idea253 `5cbaddd32d413f6213f6370a600b20e7a43a707dfd17ab7a2f4fbcee23aa6579`; idea262 `1dc0e50752413c4947137e9b0793042aabdc51607816b408ce64950bf06baa67`.
- Both ZIPs contain frontend input SHA-256 `68f234d003085b29f314dcbd0091a9441639646b5ced1b581f577f79b280ca79`.
- Disk headroom remained approximately 2.9 GiB; no external cache was deleted.

---

_Fixed: 2026-07-27T21:32:08Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 2_

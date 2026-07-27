---
phase: 01-clean-room-build-foundation
fixed_at: 2026-07-27T21:12:01Z
review_path: .planning/phases/01-clean-room-build-foundation/01-REVIEW.md
iteration: 1
findings_in_scope: 11
fixed: 11
skipped: 0
status: all_fixed
---

# Phase 1: Code Review Fix Report

**Fixed at:** 2026-07-27T21:12:01Z
**Source review:** `.planning/phases/01-clean-room-build-foundation/01-REVIEW.md`
**Iteration:** 1

**Summary:**

- Findings in scope: 11
- Fixed: 11
- Skipped: 0

## Fixed Issues

### CR-01: Strict Ubuntu CI has only macOS ARM64 verification metadata

**Files modified:** `.github/workflows/ci.yml`, `plugin/gradle/verification-metadata.xml`, `plugin/build.gradle.kts`, `docs/RELEASE-INTEGRITY.md`
**Commit:** `2a09d40`
**Applied fix:** Pinned CI to Linux x64 Ubuntu 24.04, added the official JetBrains Linux archive sidecar checksums and Node.js signed Linux x64 checksum, and made the integrity task require those exact artifacts whenever Ubuntu CI is configured. Exact remote IDEA coordinates and strict verification remain unchanged.

### CR-02: Arbitrary development pages receive the privileged project bridge

**Status:** fixed: requires human verification
**Files modified:** `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`, shared resolver tests, and both host contract tests
**Commit:** `64e2a19`
**Applied fix:** Development content now requires a separate enable flag and an exact credential-free loopback HTTP origin on port 5173. HTTPS, remote/hostname origins, userinfo, query, fragment, path, and unexpected ports are rejected. Development content receives no `JcefBridge`; only the packaged classpath UI can receive the project bridge.

### WR-01: The JCEF browser is not disposed with tool-window content

**Status:** fixed: requires human verification
**Files modified:** `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt`, shared lifecycle tests, and both host contract tests
**Commit:** `64e2a19`
**Applied fix:** Added an idempotent content lifecycle that disposes the bridge first and the JCEF browser second. Tests assert the disposal order and prove it runs exactly once.

### WR-02: View generation emits invalid duplicate namespace declarations

**Status:** fixed: requires human verification
**Files modified:** `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt`, `plugin/src/test/kotlin/org/jmixworkbench/generator/ViewXmlGeneratorTest.kt`
**Commit:** `9552dbb`
**Applied fix:** Removed the duplicate `ns(...)` declarations and retained one valid default/data namespace declaration path. A namespace-aware parser test covers list, detail, and fragment descriptors.

### WR-03: The IDEA 253 artifact advertises six unverified IDE branches

**Files modified:** IDEA 253 build/test contracts, aggregate assertions, ZIP assertions, `README.md`, `docs/BUILDING.md`, and `docs/COMPATIBILITY.md`
**Commit:** `94451f5`
**Applied fix:** Narrowed the IDEA 253 artifact to the exactly verified `253.*` branch and aligned tests, artifact inspection, and documentation. Branches 254–261 remain unsupported until exact evidence exists.

### WR-04: Host “smoke” tests never exercise tool-window startup

**Status:** fixed: requires human verification
**Files modified:** tool-window startup/lifecycle seam, shared tests, and both host contract tests
**Commit:** `64e2a19`
**Applied fix:** Replaced constructor-only evidence with a startup-plan seam covering packaged resources, unsupported JCEF, missing bundles, rejected development URLs, bridge access, and disposal lifecycle behavior in both host lanes.

### WR-05: ZIP inspection skips payloads and does not implement its credential claim

**Status:** fixed: requires human verification
**Files modified:** `plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyPluginZipContentsTask.java`, `plugin/buildSrc/src/test/java/org/jmixworkbench/build/VerifyPluginZipContentsTaskTest.java`
**Commit:** `f071dd3`
**Applied fix:** The scanner now validates every outer entry, every nested JAR entry, and every non-JAR outer payload before selecting exactly one main plugin JAR. It rejects absolute, backslash, drive-qualified, and parent-segment entries; scans stale identity/developer paths across all payload bytes; and scans text payloads for private keys, AWS keys, GitHub tokens, and assigned credentials. Synthetic ZIP tests prove outer/nested traversal rejection, scanning after the main JAR, outer payload scanning, and credential detection.

### WR-06: Web-bundle verification is incomplete and not containment-safe

**Status:** fixed: requires human verification
**Files modified:** shared verifier, build-logic tests, buildSrc test configuration, and both host verifier tasks
**Commit:** `cea32e4`
**Applied fix:** Root and direct host verifiers now inspect every local `src`/`href`, reject absolute and backslash paths, require normalized and real-path containment, and require the referenced file to exist. Tests cover valid, missing, absolute, `../`, and backslash traversal references.

### IN-01: Phase-added direct build dependencies are absent from the notice inventory

**Files modified:** `THIRD_PARTY_NOTICES.md`
**Commit:** `3105b7a`
**Applied fix:** Added the Node Gradle plugin, Foojay resolver, Node runtime, Plugin Verifier, JUnit 4/5, and Kotlin test with their roles and reviewed license families.

### IN-02: The application destructures an unused toast action

**Files modified:** `webui/src/App.tsx`
**Commit:** `2cbe2e5`
**Applied fix:** Removed the unused `addToast` store action from the application destructuring assignment.

### IN-03: Data-grid generation retains dead parameters and a no-op branch

**Files modified:** `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt`
**Commit:** `9552dbb`
**Applied fix:** Removed the unused `view` parameter and the no-op list-action filtering branch while preserving existing action generation.

## Verification

- Focused TypeScript/Vite build passed.
- Namespace-aware list/detail/fragment XML generator test passed.
- IDEA 253 and IDEA 262 JCEF origin/startup/disposal contract tests passed.
- Build-logic containment and synthetic ZIP traversal/credential/full-payload tests passed.
- All three web-bundle verifier tasks passed under strict dependency verification.
- Strict dependency-integrity verification passed with the new Linux CI metadata.
- Final `./gradlew clean phase1Check --dependency-verification=strict --offline --no-daemon --no-configuration-cache --stacktrace` passed against validated local IU-253.28294.334 and IU-262.8665.258 SDKs.
- Plugin Verifier 1.409 reported both artifacts compatible.
- Final ZIP SHA-256: idea253 `9dd034009b0144887a544e0a5f6fa2f48c765bbe973d3d9975f1e95e9251971b`; idea262 `7f64298898881c15f6fc302db881832e1e32536295b18dd4596906ab9437b640`.
- Both ZIPs contain frontend input SHA-256 `68f234d003085b29f314dcbd0091a9441639646b5ced1b581f577f79b280ca79`.
- Disk headroom remained approximately 3.1 GiB; no external cache was deleted.

---

_Fixed: 2026-07-27T21:12:01Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_

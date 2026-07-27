---
phase: 1
slug: clean-room-build-foundation
status: verified-automated
nyquist_compliant: true
wave_0_complete: true
created: 2026-07-27
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Gradle TestKit/JUnit 5 + IntelliJ Platform test framework + Vitest/TypeScript build assertions |
| **Config file** | Root and isolated host Gradle builds, host smoke tests, and custom integrity verification tasks |
| **Quick run command** | `./gradlew phase1FastCheck` |
| **Full suite command** | `./gradlew clean phase1Check` |
| **Estimated runtime** | Quick: under 120 seconds after dependency warm-up; full: environment-dependent because two IDE distributions/verifier targets are resolved |

## Sampling Rate

- **After every task commit:** Run `./gradlew phase1FastCheck`
- **After every plan wave:** Run `./gradlew phase1Check`
- **Before `/gsd-verify-work`:** `./gradlew clean phase1Check` must be green
- **Max local feedback latency:** 120 seconds for the quick gate after dependencies are warm
- **Network-dependent verification:** Plugin Verifier and first-time toolchain/IDE resolution may run in a separate full gate but cannot be skipped for phase sign-off

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-01-01 | 01 | 1 | PROD-05 | T1-IP | Required clean-room, provenance, trademark, security, and contribution policies are present | policy/static | task-local file/grep command | ✅ direct | ✅ green |
| 01-01-02 | 01 | 1 | PROD-04 | T1-IP | Product ID, name, packages, assets, and descriptors no longer claim to be Studio/Clone | policy/static | task-local namespace/descriptor command | ✅ direct | ✅ green |
| 01-02-01 | 02 | 2 | PROD-01 | T1-BUILD | Complete wrapper builds without global Gradle/Node and pins supported Gradle/Kotlin inputs | build integration | wrapper/checksum/version command | ✅ direct | ✅ green |
| 01-02-02 | 02 | 2 | PROD-03 | T1-STALE | UI is built by project-local Node; JDK 21/25 toolchains provision and stale assets block | build integration | `./gradlew verifyWebBundle verifyHostToolchains phase1FastCheck` | ✅ direct | ✅ green |
| 01-03-01 | 03 | 3 | PROD-02 | T1-HOST | Both host descriptors compile for exact build/JBR/JCEF contracts | platform integration | `./gradlew compileHostKotlin testShared hostSmokeTest` | ✅ direct | ✅ green |
| 01-03-02 | 03 | 3 | PROD-02, PROD-03 | T1-LOAD | ZIP resources resolve and Plugin Verifier passes minimum advertised IDEs | IntelliJ/package | `./gradlew buildHostPlugins verifyHostPlugins verifyPluginZipContents` | ✅ direct | ✅ green |
| 01-04-01 | 04 | 4 | PROD-06 | T1-SUPPLY | Wrapper/dependencies are pinned/verified and lock verification is read-only | supply-chain/static | `./gradlew verifyLockedConfigurations verifyDependencyIntegrity` | ✅ direct | ✅ green |
| 01-04-02 | 04 | 4 | PROD-01, PROD-02, PROD-03 | T1-PACKAGE | Clean aggregate gate emits and verifies both same-revision ZIPs | package/full | `./gradlew clean phase1Check` | ✅ direct | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

## Wave 0 Requirements

- [x] Complete Gradle wrapper and pinned wrapper checksum.
- [x] Root/host build structure and `phase1FastCheck` / `phase1Check` lifecycle tasks.
- [x] Kotlin/JUnit and IntelliJ Platform test dependencies for both host lanes.
- [x] Product identity/policy task-local assertions.
- [x] Build-owned frontend bundle task using pinned downloaded Node and `npm ci`.
- [x] Frontend input/build manifest and stale/missing negative assertions.
- [x] Host descriptor and ZIP content verification.
- [x] Plugin load/resource/fallback smoke-test fixtures.
- [x] Dependency lock and verification metadata generation/review workflow.

## Automated Evidence

- `snapshotLockHashes verifyLockedConfigurations verifyDependencyIntegrity phase1FastCheck compareLockHashes` passed with strict dependency verification offline.
- The serialized `clean phase1Check` gate passed with strict dependency verification offline against validated local IU-253.28294.334 and IU-262.8665.258 SDKs.
- Both lane unit/smoke suites passed, and Plugin Verifier 1.409 reported each plugin compatible with its exact IDE.
- ZIP inspection reported SHA-256 `99c051d7e523f98d12da4d3b69e7aed8f8f362911488538b04c30da18622b46a` for idea253 and `9aacc63e5d4ba0f8f2c10f4c4c0fa460bc4deacdf80bce03663a389ff55d5c16` for idea262.
- Both ZIPs contain frontend input SHA-256 `d3e09141750875fbe53c56206d309ae60e2b25224fd3e94f1b9cfd3f051c6ae4`.

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Install and open each host ZIP in its exact minimum IDE | PROD-02 | Final installer/UI behavior is not completely represented by unit tests | Install the 253 ZIP into IDEA 2025.3 and the 262 ZIP into IDEA 2026.2, open a project, open the tool window, record versions/logs/screenshots, and confirm no class-loading/JCEF/resource errors |
| Build on a clean machine/container with no global Node | PROD-01, PROD-03 | Proves absence of hidden workstation Node dependency | Remove Node from `PATH`, use only the checked-in Gradle wrapper and documented bootstrap JDK, run the clean phase gate, then inspect the ZIP |
| Clean-room/brand review | PROD-04, PROD-05 | Affiliation and provenance require human judgment | Review plugin name/ID/assets/descriptions/docs and contributor policy; confirm no proprietary Studio material or misleading endorsement claim |

## Validation Sign-Off

- [x] All tasks have `<automated>` verification or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verification
- [x] Wave 0 covers all missing references
- [x] No watch-mode flags
- [x] Quick feedback latency is under 120 seconds after warm-up
- [x] Both exact host lanes pass the full gate
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending manual installation

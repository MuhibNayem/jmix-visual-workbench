---
phase: 01-clean-room-build-foundation
plan: "05"
subsystem: packaged-jcef-runtime
tags: [jcef, private-origin, classpath-resources, intellij-253, intellij-262, installed-uat]

requires:
  - phase: 01-clean-room-build-foundation
    provides: Dual-host build, packaging, integrity, and installed-product gap diagnosis
provides:
  - Constrained private JCEF origin for packaged React resources
  - Cross-lane request/resource-handler adapter with deterministic disposal
  - Security and lifecycle regression coverage
  - Passed official signed-product UAT on IDEA 2025.3 and 2026.2
affects: [all-visual-designers, project-bridge, release-gates, enterprise-compatibility]

tech-stack:
  added:
    - JCEF request/resource handler backed by plugin classpath resources
  patterns:
    - Synthetic private HTTPS origin with deny-by-default resource policy
    - Bridge only in packaged mode; explicit unbridged loopback development mode
    - Handler registration before navigation and idempotent unregistration on disposal

key-files:
  created:
    - plugin/src/main/kotlin/org/jmixworkbench/toolwindow/PackagedWorkbenchResourceHandler.kt
    - .planning/phases/01-clean-room-build-foundation/evidence/idea253-packaged-ui.png
    - .planning/phases/01-clean-room-build-foundation/evidence/idea262-packaged-ui.png
  modified:
    - plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt
    - plugin/src/test/kotlin/org/jmixworkbench/toolwindow/
    - plugin/hosts/idea253/src/test/
    - plugin/hosts/idea262/src/test/
    - .planning/phases/01-clean-room-build-foundation/01-HUMAN-UAT.md
    - .planning/phases/01-clean-room-build-foundation/01-VALIDATION.md
    - .planning/phases/01-clean-room-build-foundation/01-VERIFICATION.md

key-decisions:
  - "Never give Chromium a JVM `jar:file:` URL; serve packaged resources from `https://jmix-workbench.invalid`."
  - "Map only validated private-origin paths to `/webui/**`; reject ambiguous encodings, traversal, unsupported methods, unknown MIME types, and off-origin requests."
  - "Keep the privileged project bridge exclusive to packaged mode and deny packaged-browser network access."
  - "Treat official signed-host visual rendering and bridge logs as required evidence beyond fake-browser and Plugin Verifier checks."

requirements-completed: [PROD-02, PROD-03, PROD-06]

duration: 1h49m
completed: 2026-07-28
---

# Phase 1 Plan 5: Packaged JCEF Origin and Installed UAT Summary

**The packaged React workbench now renders through a closed classpath-backed
origin in both official signed IntelliJ host lanes.**

## Performance

- **Duration:** 1h49m
- **Started:** 2026-07-28T03:48:48Z
- **Completed:** 2026-07-28T05:37:15Z
- **Tasks:** 2
- **Implementation commit:** `8e9adbe`

## Accomplishments

- Replaced unsupported Chromium navigation to a JVM `jar:file:` URL with the
  synthetic private origin `https://jmix-workbench.invalid`.
- Added a pure packaged-resource policy and a cross-lane JCEF adapter that
  serves only validated `/webui/**` classpath content, denies external network
  requests, constrains methods/MIME/size, emits hardened headers, and cleans up
  deterministically.
- Upgraded shared and managed-host regression tests to assert handler
  registration order, private-origin navigation, development-mode separation,
  bridge attachment rules, security rejection cases, and lifecycle behavior.
- Passed the strict clean dual-host gate and both exact Plugin Verifier targets.
- Installed fresh artifacts in isolated profiles of official signed/notarized
  IDEA `IU-253.28294.334` and `IU-262.8665.258`; both visibly rendered the
  actual packaged React designer and logged `Bridge request: getProjectConfig`.
- Completed the installed identity and clean-room review with no proprietary
  Studio material, license bypass, or endorsement impression.

## Artifact Evidence

| Lane | ZIP SHA-256 | Installed host | Result |
|---|---|---|---|
| IDEA 253 | `77cd8bf4f988acf98979a5dbe21b6bae23d7dce067972e92bd855943f378f976` | `IU-253.28294.334` | Passed |
| IDEA 262 | `311b795b5e1dc127a6d345eb3d7b50772a1597449ee73bab77350efbe422ad8c` | `IU-262.8665.258` | Passed |

Both ZIPs embed revision
`8e9adbefb672e34ece2f4e2d142c507b5608eb65` and frontend input SHA-256
`68f234d003085b29f314dcbd0091a9441639646b5ced1b581f577f79b280ca79`.

## Deviations and Issues

### Fixed blocker

The real IDEA 2025.3 test exposed that Chromium cannot navigate directly to a
classpath `jar:file:` URL. Unit fakes had only proved URL forwarding. The fix
introduced a real JCEF distribution-resource model and added regression tests
that fail if packaged startup omits handler installation or navigates to a JAR
URL.

### Test-environment issue

The isolated IDEA 2026.2 project attempted an unnecessary Gradle synchronization
and exhausted the final disk reserve while copying a bundled Gradle JAR. This
did not involve plugin code. After project-model initialization settled, the
tool window registered and passed visual/JCEF/bridge UAT. The disposable Gradle
daemon, mounted IDE image, and 1.7 GB test directory were removed after evidence
capture.

## Verification

- Strict `clean phase1Check` passed for both exact host lanes.
- Plugin Verifier reported both artifacts compatible.
- Packaged-resource policy/security and managed-host lifecycle suites passed.
- IDEA 2025.3 and 2026.2 screenshots are retained under `evidence/`.
- Both installed logs confirmed JCEF initialization and `getProjectConfig`.
- No `ERR_UNKNOWN_URL_SCHEME`, plugin exception, class-loading, descriptor,
  JCEF, or missing-resource error was observed in the successful runs.
- `git diff --check` passed.

## Next Phase Readiness

Phase 1 is complete for the currently advertised 253.* and 262.* lanes. The
remaining roadmap still includes the enterprise Jmix project model, safe
transactional generation, designers, synchronization, compatibility fixtures,
and release hardening; no whole-product completion is implied.

## Self-Check: PASSED

- Implementation commit and both same-revision ZIPs exist.
- Both evidence images are valid PNG files with recorded hashes.
- Human UAT, validation, and verification ledgers report the observed results.
- The test IDE installations and disposable profiles were removed without
  deleting source, settings, or release artifacts.

---
*Phase: 01-clean-room-build-foundation*
*Completed: 2026-07-28*

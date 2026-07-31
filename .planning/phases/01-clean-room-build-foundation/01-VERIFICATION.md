---
phase: 01-clean-room-build-foundation
verified: 2026-07-28T05:37:15Z
status: passed
score: 17/18 must-haves verified; 1 intentionally deferred
overrides_applied: 0
deferred:
  - truth: "IDE 253-261 and IDE 262 artifacts are built from one canonical source tree with correct JVM/JCEF contracts."
    addressed_in: "Phase 7"
    evidence: "Phase 7 success criterion 4 expands IntelliJ coverage through broader enforced matrices; Phase 1 review commit 94451f5 deliberately narrowed the unverified 253-261 claim to the evidenced 253.* branch."
human_verification_completed:
  - test: "Install and open the idea253 ZIP in official signed IntelliJ IDEA Ultimate 2025.3."
    result: "passed"
    evidence: "evidence/idea253-packaged-ui.png and bridge log getProjectConfig"
  - test: "Install and open the idea262 ZIP in official signed IntelliJ IDEA 2026.2."
    result: "passed"
    evidence: "evidence/idea262-packaged-ui.png and bridge log getProjectConfig"
  - test: "Review the installed identity, icon, compatibility wording, and clean-room policy."
    result: "passed"
    evidence: "Original Jmix Visual Workbench identity; no proprietary Studio material, license bypass, endorsement claim, or stale clone branding."
---

# Phase 1: Clean-Room Build Foundation Verification Report

**Phase Goal:** Contributors can build and developers can install an original, reproducible plugin foundation on each initial IntelliJ lane.
**Verified:** 2026-07-28T05:37:15Z
**Status:** passed
**Re-verification:** Yes — installed-product JCEF blocker closed by Plan 01-05

## Goal Achievement

The checked-in build, current source, exact-host reports, post-fix ZIPs, official
signed-product UAT, and captured screenshots substantiate the Phase 1 goal. The
previous `jar:file:` Chromium failure is closed: production now navigates only
to a constrained private HTTPS origin backed by packaged classpath resources.
This report does not treat summaries as evidence: ZIPs, descriptors, source,
task wiring, XML results, verifier verdicts, checksums, IDE signatures/logs, and
visible rendering were inspected independently.

### Observable Truths

| # | Source | Truth | Status | Evidence |
|---|---|---|---|---|
| 1 | ROADMAP SC1 | The project builds every initial advertised lane through checked-in wrappers and provisions pinned local Node without global Node. | ✓ VERIFIED | `plugin/gradlew`, `.bat`, wrapper JAR and pinned Gradle 9.5.1 distribution are tracked; wrapper reported 9.5.1; Node plugin is 7.1.0 with `version=24.18.0`, `download=true`, `npm ci`; cached build-owned Node reported v24.18.0; root tasks invoke both included lanes. |
| 2 | ROADMAP SC2 | Every ZIP contains a current production web bundle; stale/missing assets fail; installed plugins need no Node. | ✓ VERIFIED | Both ZIPs contain `webui/index.html`, hashed JS/CSS and `build-info.json`; current UI digest recomputed to `68f234...a79`, identical in both ZIPs; host `verifyWebBundle` and ZIP inspection reject stale/missing/escaping resources and Node payloads; runtime Kotlin contains no Node/npm execution. |
| 3 | ROADMAP SC3 | A developer can install each ZIP and open the workbench without class-loading, JCEF, or missing-resource errors. | ✓ VERIFIED | Official signed/notarized IU-253.28294.334 and IU-262.8665.258 both registered the tool window and visibly rendered the packaged React designer. Each log recorded `Bridge request: getProjectConfig`; no plugin/JCEF/resource error was observed. |
| 4 | ROADMAP SC4 | Product identity/legal/provenance are original and dependencies pinned/integrity-verified. | ✓ VERIFIED | Installed descriptor/UI identity is `Jmix Visual Workbench` / `org.jmixworkbench`; original icon/UI and independent-product disclaimer passed visual review; legal/policy documents and integrity gates are enforced. |
| 5 | Plan 01 | Repository and installed product identify as Jmix Visual Workbench, never Jmix Studio Clone. | ✓ VERIFIED | No old runtime name/package/icon references in plugin/web runtime scope; both packaged descriptors contain the new name and ID. |
| 6 | Plan 01 | Product states compatibility with Jmix and independence from Haulmont. | ✓ VERIFIED | Exact disclaimer appears in `TRADEMARKS.md`, README, source descriptors and packaged descriptors. |
| 7 | Plan 01 | Contributors are prohibited from proprietary Studio material or license bypass. | ✓ VERIFIED | `CLEAN_ROOM.md` and `CONTRIBUTING.md` explicitly prohibit proprietary code/assets/templates/protocols, decompilation-derived behavior and license circumvention and require provenance attestation. |
| 8 | Plan 02 | Checked-in wrapper is the only required Gradle entry point. | ✓ VERIFIED | Complete official wrapper files are tracked; wrapper JAR SHA-256 is `497c8c...a9c7`; CI and docs use `./gradlew`, not global Gradle. |
| 9 | Plan 02 | Global Node is not required to build and installed plugin never needs Node. | ✓ VERIFIED | Gradle owns Node 24.18.0 below `plugin/build`; CI has no Node setup action; runtime loads `/webui/index.html` from classpath and has no process launch for Node/npm. |
| 10 | Plan 02 | IDE 253-261 and IDE 262 artifacts use canonical source with correct JVM/JCEF contracts. | ◇ DEFERRED | Canonical source and exact 253.*/262.* lane contracts are verified, but branches 254-261 are not claimed. Review finding WR-03 correctly narrowed the descriptor because those branches lacked exact compile/verifier/install evidence. Phase 7 owns broader IntelliJ matrix expansion. |
| 11 | Plan 02 | Packaging cannot reuse stale checked-out `webui/dist`. | ✓ VERIFIED | Build output comes from `plugin/build/webui-dist` then `plugin/build/generated-resources`; both host resource/package tasks require digest-checked generated output; no checkout-relative production fallback remains. |
| 12 | Plan 03 | Both advertised host lanes compile and produce installable ZIPs from one source tree. | ✓ VERIFIED | Current lane ZIPs exist; final test XML is green; exact-host verifier verdicts are compatible; both lane builds point to `../../src/main/kotlin`. |
| 13 | Plan 03 | Packaged web resources resolve; missing resources and unsupported JCEF show safe explicit fallback UI. | ✓ VERIFIED | The classpath policy/provider, private-origin JCEF adapter, and stable `JVW-JCEF-UNAVAILABLE`, `JVW-WEB-BUNDLE-MISSING`, and rejected-dev-URL panels are covered by unit, security, and managed factory integration tests. |
| 14 | Plan 03 | Only build blockers are fixed; unsafe generation is not certified. | ✓ VERIFIED | README and compatibility docs label direct-write/generator behavior non-certified and unsafe for valuable repositories; Phase 1 source changes center on build/packaging/startup, not certification. |
| 15 | Plan 04 | Resolved Gradle/npm inputs are pinned and integrity checked. | ✓ VERIFIED | Two strict lockfiles exist; verification metadata contains 1,096 SHA-256 entries; lock, wrapper, npm and CI-bypass assertions are wired into `verifyDependencyIntegrity`. |
| 16 | Plan 04 | CI uses only wrapper and project-local Node. | ✓ VERIFIED | Immutable CI action SHAs; only Java 21 bootstrap is installed; command is `plugin/gradlew clean phase1Check`; no setup-node/global npm/global Gradle. |
| 17 | Plan 04 | Documentation separates host JVM from target Jmix Java 17/21/25. | ✓ VERIFIED | `docs/BUILDING.md` and `docs/COMPATIBILITY.md` separate JBR 21/25 host lanes from future Jmix 2.8 Java 17/21 and Jmix 3 Java 21/25 fixture cells. |
| 18 | Plan 04 | Full clean Phase 1 gate passes or reports exact unresolved external evidence. | ✓ VERIFIED | The post-fix strict clean gate passed using exact validated IU253/IU262 paths; current test XML, ZIP hashes, embedded revision, and verifier reports match the recorded final evidence. |

**Score:** 17/18 truths verified; 1 intentionally narrowed item is deferred to broader-matrix work. All truths in the currently advertised 253.* and 262.* scope pass.

### Deferred Items

| # | Item | Addressed In | Evidence |
|---|---|---|---|
| 1 | Advertise and verify IntelliJ branches 254-261 in the older host line. | Phase 7 | Phase 7 criterion 4 requires broader IntelliJ matrix enforcement. Commit `94451f5` narrowed Phase 1 to 253.* because claiming 254-261 without exact compile/verifier/install evidence violated the project rule “compatibility must be verified rather than claimed.” |

This is not a current advertised-lane gap: `docs/COMPATIBILITY.md`, descriptors, tests and ZIP assertions consistently claim only 253.* and 262.*. It is a literal deviation from Plan 02's earlier 253-261 wording and is therefore preserved as deferred rather than silently counted as verified.

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `LICENSE`, `NOTICE`, `TRADEMARKS.md` | Apache 2.0, attribution and exact disclaimer | ✓ VERIFIED | Substantive files; exact markers present; LICENSE/NOTICE packaged in both main plugin JARs. |
| `CLEAN_ROOM.md`, `CONTRIBUTING.md`, `SECURITY.md`, `THIRD_PARTY_NOTICES.md` | Clean-room, provenance, reporting and direct-dependency rules | ✓ VERIFIED | Substantive and cross-linked; no placeholder reporting address. |
| `plugin/src/main/kotlin/org/jmixworkbench/` | Canonical original Kotlin namespace | ✓ VERIFIED | All runtime package declarations use `org.jmixworkbench`; descriptor classes resolve, including four action classes in `Actions.kt`. |
| `plugin/src/main/resources/icons/workbench.svg` | Original product asset | ✓ VERIFIED | New geometric SVG is packaged/referenced and the installed identity review found no proprietary Studio material or misleading endorsement. |
| Wrapper files | Complete checksum-pinned Gradle 9.5.1 wrapper | ✓ VERIFIED | Scripts/JAR/properties tracked; exact wrapper and distribution SHA-256 values present. |
| `plugin/gradle/libs.versions.toml` | Exact build/tool/runtime versions | ✓ VERIFIED | Gradle 9.5.1, IPGP 2.18.0, Foojay 1.0.0, Node plugin 7.1.0, Node 24.18.0, Kotlin 2.4.0 and exact IDE coordinates. |
| `plugin/hosts/idea253/` | 253.*, Kotlin 2.2/JVM21 host build | ✓ VERIFIED | Canonical sources, Temurin 21, exact IU 2025.3, patched range 253.*, no explicit JCEF descriptor dependency. |
| `plugin/hosts/idea262/` | 262.*, Kotlin 2.4/JVM25 + JCEF host build | ✓ VERIFIED | Canonical sources, Temurin 25, exact IU 2026.2, both build JCEF modules and descriptor JCEF dependency. |
| `plugin/buildSrc/...WebBundle*` | Build-owned UI assembly/fingerprint/verification | ✓ VERIFIED | Substantive hashing, resource containment, manifest and missing/stale rejection logic. |
| `VerifyPluginZipContentsTask.java` | Complete nested ZIP/JAR integrity gate | ✓ VERIFIED | Checks exactly two lanes, descriptors, web/assets/digest/legal/icon and rejects Node caches, source maps, paths, stale identity and credential patterns. |
| Host ZIPs | One deterministic distribution per lane | ✓ VERIFIED | `idea253` SHA-256 `77cd8bf...f976`; `idea262` SHA-256 `311b795b...d8c`; both were built from revision `8e9adbe...eb65`, inspect successfully, and contain UI digest `68f234...a79`. |
| Tool-window tests | Resource/fallback, private-origin security, lifecycle, and real factory startup coverage | ✓ VERIFIED | All shared unit/security tests and both managed host smoke suites passed with zero failures/errors/skips. |
| `verification-metadata.xml` and lane lockfiles | Reviewed checksums and strict lock state | ✓ VERIFIED | Metadata is substantive; two 19-line lockfiles cover runtime/test runtime; normal verification hashes lock state before/after. |
| `.github/workflows/ci.yml` | Immutable wrapper-only dual-lane gate | ✓ VERIFIED | All `uses:` entries pin 40-character commits; strict clean gate and ZIP/report uploads are wired. |
| Build/compatibility/integrity docs | Exact commands, matrices, artifact names and scope | ✓ VERIFIED | Current docs match exact ZIP names, 253.*/262.* descriptor claims and non-certified mutation boundary. |
| `01-VALIDATION.md` | Honest automated/manual ledger | ✓ VERIFIED | Automated evidence, official signed-host UAT, screenshots, and identity review are green; approval is passed. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| Host descriptors | Kotlin implementations | fully qualified extension/action/service class names | ✓ WIRED | Tool window and services resolve to actual classes; four actions resolve inside `Actions.kt`. |
| README | legal/policy documents | repository links | ✓ WIRED | All six policy/legal links are present. |
| Root UI build | host resources | `verifyWebBundle` and `processResources` | ✓ WIRED | Host `processResources`/`buildPlugin` depend on host freshness gate and consume only aggregate generated resources. |
| Root aggregate | both host builds | included-build task providers and serialized lane gates | ✓ WIRED | Compilation, tests, smoke, packaging, verifier and Kotlin-runtime checks target both lanes. |
| Tool-window factory | packaged web entry | private-origin resource handler backed by `/webui/**` classpath lookup | ✓ WIRED | Production installs the constrained handler before navigation to `https://jmix-workbench.invalid/`; it never gives Chromium a `jar:file:` URL or checkout-relative fallback. |
| ZIP content tests | tool-window resource | nested plugin JAR inspection | ✓ WIRED | Both ZIPs require packaged entry, referenced hashed assets and manifest. |
| CI | strict Phase 1 gate | checked-in wrapper command | ✓ WIRED | CI invokes `./gradlew clean phase1Check --dependency-verification=strict`. |
| Documentation | actual distributions | exact lane-suffixed names | ✓ WIRED | BUILDING and CI upload paths match current artifacts. |

### Data-Flow Trace (Level 4)

| Artifact | Data | Source | Produces Real Data | Status |
|---|---|---|---|---|
| Web bundle | Vite JS/CSS/index | Declared `webui` inputs → downloaded Node/npm ci → Vite staging | Yes; current 21-file digest recomputes exactly | ✓ FLOWING |
| `build-info.json` | version, revision, input paths/digest | Gradle providers + `WebBundleFingerprint` | Yes; exact digest embedded in both ZIPs | ✓ FLOWING |
| Host plugin resources | generated web tree | `processResources.from("../../build/generated-resources")` after freshness check | Yes; nested ZIP inspection finds entry and assets | ✓ FLOWING |
| Tool-window content | private packaged origin and project bridge | classpath policy/provider → JCEF adapter → browser load → content manager | Yes; managed factory tests and both installed hosts exercise real wiring | ✓ FLOWING |
| Toolchain metadata | Java launcher version/vendor/path | Gradle toolchain launcher providers | Yes; cached metadata reports Temurin 21 and 25 | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command/check | Result | Status |
|---|---|---|---|
| Wrapper is pinned Gradle | `plugin/gradlew --version` | Gradle 9.5.1 | ✓ PASS |
| Wrapper JAR integrity | `shasum -a 256` | `497c8c...a9c7` | ✓ PASS |
| Current UI input fingerprint | Independent Node SHA-256 recomputation of path/NUL/content/NUL stream | `68f234...a79`, matches bundle and both ZIPs | ✓ PASS |
| ZIP contents/descriptor contracts | Read-only nested ZIP/JAR inspection | Both contain descriptor, hashed assets, manifest, icon, LICENSE/NOTICE; no Node/Kotlin-runtime/cache/source-map entries | ✓ PASS |
| Exact host compatibility | Read verifier verdict files | Both `Compatible`; six deprecated and six experimental usages each | ✓ PASS with deferred warnings |
| Real startup smoke | Read JUnit XML | Both managed integration suites passed with zero failures/errors/skips | ✓ PASS |
| Official IDEA 2025.3 UAT | Install fresh idea253 ZIP in isolated signed IU-253.28294.334 profile and open tool window | Packaged React designer rendered; bridge request logged; screenshot captured | ✓ PASS |
| Official IDEA 2026.2 UAT | Install fresh idea262 ZIP in isolated signed IU-262.8665.258 profile and open tool window | Packaged React designer rendered after project initialization; bridge request logged; screenshot captured | ✓ PASS |
| Source hygiene | `git diff --check` and focused status review | No whitespace error; only the expected Phase 1 evidence/report changes are pending this report commit | ✓ PASS |
| Full strict clean gate | `./gradlew clean phase1Check --dependency-verification=strict` | Passed from revision `8e9adbe`; both exact Plugin Verifier targets compatible | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|---|---|---|---|---|
| PROD-01 | 01-02, 01-04 | Wrapper builds all advertised artifacts and provisions local Node | ✓ SATISFIED | Complete wrapper, Node download/npm ci, both lane task graph, immutable CI and recorded restricted-PATH/full-gate evidence. |
| PROD-02 | 01-03, 01-04, 01-05 | Install/open each advertised ZIP without startup errors | ✓ SATISFIED | Both fresh ZIPs passed automated gates and visibly rendered through JCEF in official signed exact hosts with bridge logs and screenshots. |
| PROD-03 | 01-02, 01-03, 01-04 | Same-source production bundle, stale failure and no installed Node | ✓ SATISFIED | Current input digest matches both artifacts; stale/missing/resource-containment checks are wired; packaged runtime has no Node execution/payload. |
| PROD-04 | 01-01, 01-05 | Original name/assets/license/disclaimer | ✓ SATISFIED | Name/ID/license/disclaimer/icon are mechanically verified and installed-product identity review found no proprietary Studio material or affiliation impression. |
| PROD-05 | 01-01 | Clean-room/provenance/trademark/security/contribution rules | ✓ SATISFIED | Required documents contain explicit prohibitions, provenance citations and attestation. |
| PROD-06 | 01-04 | Pinned, locked and checksum-verified dependencies | ✓ SATISFIED | Exact catalog/wrapper/npm graph, strict lane locks, 1,096 SHA-256 metadata entries and CI enforcement. |

No Phase 1 requirements are orphaned: PROD-01 through PROD-06 all appear in plan frontmatter and REQUIREMENTS traceability.

### Anti-Patterns Found

| File | Line/Area | Pattern | Severity | Impact |
|---|---|---|---|---|
| Plugin Verifier reports | both exact lanes | Six deprecated and six experimental IntelliJ API usages | ℹ️ Info | Verifier still reports compatible/dynamically loadable; already recorded in `deferred-items.md`. |
| Startup integration tests | browser construction/load exceptions | Tests cover planned branches, policy, registration order and disposal but cannot fully execute native Chromium | ℹ️ Info | Official installed-product UAT on both exact hosts supplies the native JCEF evidence. |

No blocker stubs, TODO/FIXME markers, empty handlers, global Node launch, checkout-relative production web fallback, old runtime namespace, dynamic build version, or CI verification bypass was found in the Phase 1 foundation scope.

### Human Verification Completed

1. Official signed IntelliJ IDEA Ultimate 2025.3 (`IU-253.28294.334`)
   rendered the packaged React workbench. Evidence:
   `evidence/idea253-packaged-ui.png`, SHA-256
   `d9988e8600f1dc41e2686f569448b8b189be3c5efdd8e00c24b43c85b5c9b76b`.
2. Official signed IntelliJ IDEA 2026.2 (`IU-262.8665.258`) rendered the
   same-revision packaged React workbench. Evidence:
   `evidence/idea262-packaged-ui.png`, SHA-256
   `a71042c926f13fb94224756e80a5989d155e360b3d14efbc9aa4666bbd83a8a8`.
3. Installed name, ID, icon, UI, compatibility wording and clean-room policies
   passed independent-product review.

### Gaps Summary

No actionable automated or installed-product Phase 1 gap remains for the
currently advertised 253.* and 262.* host lanes. The earlier 253-261 plan range
was an unsafe unsupported claim and was correctly narrowed after code review;
expansion remains recorded as deferred broader-matrix work rather than restored
without evidence.

Phase 1 is passed. This does not claim that the whole product roadmap or paid
Studio-equivalent feature set is complete; it certifies only the clean-room
dual-host build and installed foundation.

---

_Verified: 2026-07-28T05:37:15Z_
_Verifier: Codex (gsd-verifier)_

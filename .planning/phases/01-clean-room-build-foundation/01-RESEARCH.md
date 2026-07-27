# Phase 1 Research: Clean-Room Build Foundation

**Phase:** 1  
**Researched:** 2026-07-27  
**Status:** Ready for planning  
**Confidence:** High for the build/product direction; medium for exact dual-lane Gradle packaging until the first clean build proves it

## Executive Recommendation

Phase 1 should replace the current non-buildable single-project shell with an original, reproducible, self-sustaining IntelliJ plugin build. It must produce separately verified host artifacts rather than claiming one universal ZIP:

- `idea253`: IntelliJ IDEA branches 253 through 261, Java/JBR 21 bytecode floor.
- `idea262`: IntelliJ IDEA branch 262, Java/JBR 25 and explicit JCEF module dependency.

The plugin must package a production React bundle built by Gradle from the same checkout. End users require no Node runtime. Contributors and CI invoke the checked-in Gradle wrapper; the build downloads a pinned project-local Node distribution when necessary and runs the lockfile-based frontend build.

This phase must not enable or certify writes into Jmix projects. Java 17-through-latest support describes target Jmix applications, not the JVM used to run modern IntelliJ:

- Initial target-project cells planned for later certification: Jmix 2.8 on Java 17/21 and Jmix 3.0 on Java 21/25.
- The plugin host runs on the JBR required by the selected IntelliJ lane.
- Newer JDK/Jmix combinations enter recognized/read-only status before they can receive write certification.

## Current-State Evidence

The current repository cannot satisfy Phase 1:

- `plugin/build.gradle.kts` uses inactive `org.jetbrains.intellij` 1.17.4 and targets IntelliJ 2024.1.
- `plugin/gradle/wrapper/` has only properties; launcher scripts and wrapper JAR are missing.
- `copyWebUi` copies an existing `webui/dist` directory but does not build it, so stale or unrelated assets can be packaged.
- Plugin compatibility ends at 251.*, below the 2025.3 minimum used by current Jmix Studio/Jmix 3 tooling.
- The current identity uses “Jmix Studio Clone,” `com.jmixstudio.clone`, Jmix-branded naming, and no license or trademark disclaimer.
- There is no CI, dependency verification, dependency locking, plugin verification, installed-plugin smoke test, or release integrity output.
- The local machine currently has Java 25 and Node 22, no Java 21 installation, an incomplete wrapper, and a system Gradle cache that is not a reliable project prerequisite.

## Build Architecture

### 1. Checked-in, pinned entry point

Use a complete Gradle 9.5.1 wrapper compatible with IntelliJ Platform Gradle Plugin 2.18.0 and the selected Kotlin Gradle Plugin lanes. Gradle 9.6.1 is outside the documented fully supported range of Kotlin Gradle Plugin 2.4.0, so it is not the supported intersection for this phase. Pin the distribution URL and SHA-256 checksum in `gradle-wrapper.properties`. Contributors should run only the wrapper; global Gradle is not part of the supported build contract.

Keep wrapper and build caches outside release artifacts. A clean checkout should be able to resolve its toolchains and dependencies with network access, then build reproducibly from pinned inputs.

### 2. Two host distributions

Use an aggregate Gradle build plus two isolated included builds with shared canonical source. Both included builds use Kotlin Gradle Plugin 2.4.0, the supported Kotlin/Gradle 9.5 intersection. The idea253 compiler is constrained to Kotlin language/API 2.2 and JVM 21 so shared code cannot call Kotlin 2.4-only runtime APIs unavailable on the older host. Isolation still keeps IntelliJ Platform dependencies, descriptors, tasks, and toolchains independent:

```text
plugin/
  settings.gradle.kts
  build.gradle.kts                 # shared versions, Node/UI tasks, aggregate checks
  gradle/
    libs.versions.toml
    wrapper/
  hosts/
    idea253/
      settings.gradle.kts          # isolated KGP/plugin classpath
      build.gradle.kts
      src/main/resources/META-INF/plugin.xml
    idea262/
      settings.gradle.kts          # isolated KGP/plugin classpath
      build.gradle.kts
      src/main/resources/META-INF/plugin.xml
  src/main/kotlin/                 # shared plugin implementation for Phase 1
  src/main/resources/              # shared original assets/resources
```

Each included host build compiles/packages the shared sources against its own IntelliJ dependency and plugin descriptor. The root aggregate references included-build task providers and applies neither Kotlin/IntelliJ plugin:

- The 253 lane declares `since-build=253`, `until-build=261.*`, targets JVM 21, depends on platform and Java modules, and checks `JBCefApp.isSupported()` at runtime.
- The 262 lane declares `since-build=262`, initially `until-build=262.*`, targets JVM 25, and declares the explicit JCEF dependency required by 2026.2.
- Both lanes must use the same plugin ID and version so they represent host-specific builds of one product, but distribution filenames must include the host lane.
- Do not shade Kotlin stdlib/coroutines supplied by IntelliJ.

If the 2.18 Gradle DSL cannot package shared sources cleanly through this exact layout, the acceptable fallback is two thin host projects that point their source sets at the same canonical source directories. Copying source files between modules is not acceptable.

### 3. Self-provisioning frontend build

Apply a pinned Gradle Node plugin (the maintained `com.github.node-gradle.node` line) in the build aggregator:

- Pin Node 24 LTS to an exact patch and set `download=true`.
- Keep its installation in the Gradle/project cache, never in a global machine location.
- Point npm tasks at `webui/`.
- Use `npm ci`, not `npm install`.
- Run the existing strict TypeScript/Vite production build.
- Declare `webui/package.json`, `package-lock.json`, TypeScript/Vite/PostCSS/Tailwind config, HTML, and `webui/src/**` as task inputs.
- Declare a build-owned directory under `plugin/build/` as output; do not treat checked-out `webui/dist` as authoritative.
- Make each host `processResources` depend on the frontend task and package the build-owned output.
- Fail packaging if `index.html` or referenced assets are absent.
- Record the source revision/input digest in a small generated manifest packaged beside the UI. A verification task must compare the manifest with current inputs.

Installed plugins load only the packaged static resources and must not download, install, or execute Node.

### 4. Toolchain behavior

The wrapper requires a documented bootstrap JDK compatible with Gradle. Compilation uses explicit Java toolchains:

- JVM 21 for the 253–261 lane.
- JVM 25 for the 262 lane.

Apply `org.gradle.toolchains.foojay-resolver-convention` 1.0.0 in both included-build settings and request Java language versions 21 and 25 with an explicit vendor policy. Auto-provision missing toolchains into Gradle's cache, document network/offline behavior, and assert each compilation task uses the intended launcher. Do not silently substitute the host developer's current JDK for a lane's compiler target.

Target-project Java support is separate metadata for later phases. Phase 1 documents the planned matrix but does not yet inspect or mutate Jmix project code.

## Original Product and Legal Boundary

Adopt an original identity before expanding implementation:

- Product name: **Jmix Visual Workbench** (descriptive compatibility use; not “Studio” and not “Clone”).
- Plugin ID: a stable original ID such as `org.jmixworkbench.intellij`.
- Kotlin namespace: an original namespace such as `org.jmixworkbench`.
- Tool-window/action names: “Jmix Visual Workbench.”
- Original icon and visual assets; remove the current Jmix-logo-derived asset if provenance cannot be established.
- Apache License 2.0 is recommended for enterprise-friendly use, subject to owner confirmation.
- Add `NOTICE`, `TRADEMARKS.md`, `CLEAN_ROOM.md`, `CONTRIBUTING.md`, `SECURITY.md`, and third-party notices.
- State: “Compatible with Jmix. Independent project; not affiliated with or endorsed by Haulmont.”
- Prohibit proprietary Jmix Studio code, assets, templates, decompilation-derived behavior, license bypass, and redistribution of commercial add-on runtimes.
- Require contributors to cite public specifications/issues/docs for compatibility behavior and attest that contributions follow the clean-room policy.

This is an engineering boundary, not legal advice. Marketplace/public release still needs owner/legal review.

## Dependency and Supply-Chain Integrity

Phase 1 should establish checks that later release work can strengthen:

- Centralize exact plugin/library/tool versions in a version catalog or equivalent single source.
- Keep npm lockfile v3 and use `npm ci`.
- Enable Gradle dependency locking for resolvable project configurations.
- Generate Gradle dependency-verification metadata with SHA-256 checksums for resolved build dependencies and review changes.
- Pin the wrapper distribution checksum.
- Reject dynamic versions, snapshots, unreviewed repositories, and unpinned CI actions.
- Generate a dependency inventory/SBOM precursor or full CycloneDX output if it can be added without destabilizing the first build.
- Do not require signing secrets locally. Signing tasks consume CI environment secrets only and remain safely skippable for ordinary builds.

PROD-07/08 are Phase 8 requirements, so signed release publication and the final release matrix are not Phase 1 exit requirements. Phase 1 must nevertheless make later signing/verifier integration possible and run a basic verifier/smoke gate.

## Build and Test Gates

Minimum Phase 1 commands:

1. Wrapper validation and version output.
2. Frontend clean install/build using downloaded Node.
3. Frontend same-revision/input-manifest verification.
4. Kotlin compilation for both host lanes.
5. Plugin descriptor validation for both lanes.
6. Plugin ZIP assembly for both lanes.
7. ZIP content assertion: production `webui/index.html`, hashed assets, build manifest, original icon, license/notice files, and no Node/npm caches or source maps unless intentionally included.
8. Lane-specific Plugin Verifier for idea253 against IntelliJ 2025.3 and idea262 against IntelliJ 2026.2, wired into the aggregate full gate.
9. Installed-plugin or test-framework smoke test that creates the tool-window factory, proves packaged resources resolve, and exercises the non-JCEF fallback.
10. Clean rebuild comparison for stable artifact contents after normalizing unavoidable ZIP timestamps/metadata, or an explicit documented reproducibility report if byte identity is not yet achieved.

The existing generator compile defects must not be hidden. Either compile and fix blockers necessary for packaging, or explicitly quarantine unsafe prototype generators from the build. Phase 1 should not advertise those generators as working.

## Threat Model

| Threat | Phase 1 control |
|---|---|
| Proprietary code/assets enter the product | Clean-room policy, provenance review, original identity/assets, contribution attestation |
| Malicious or compromised build dependency | Pinned versions, restricted repositories, wrapper checksum, npm lockfile, Gradle verification metadata |
| Stale or attacker-controlled `webui/dist` is packaged | Build-owned output, declared inputs, `npm ci`, same-revision/input digest, ZIP assertions |
| Node installation modifies developer machine | Project-local downloaded Node only; no global installation commands |
| One ZIP falsely claims incompatible IDE/JBR support | Separate host descriptors/artifacts and Plugin Verifier per advertised lane |
| Secrets leak into source or artifacts | No local keys; CI environment inputs; artifact content/secret scan |
| Release contains Node caches or source/developer paths | ZIP allowlist/content test and redaction checks |
| Untrusted project receives mutation capability | No new mutation enablement in Phase 1; existing prototype remains explicitly non-certified |

Severity policy: any high-severity clean-room, credential, dependency-integrity, host-compatibility, or packaged-code finding blocks Phase 1 completion.

## Validation Architecture

### Test layers

| Layer | What it proves | Suggested implementation |
|---|---|---|
| Static build assertions | Exact versions, descriptors, task wiring, no dynamic dependencies | Gradle TestKit/build-logic tests or focused verification tasks |
| Frontend build | TypeScript and production assets build under downloaded Node | Gradle Node/npm tasks invoking `npm ci` and `npm run build` |
| Kotlin compile | Shared source compiles against both host APIs/JVM targets | `:hosts:idea253:compileKotlin`, `:hosts:idea262:compileKotlin` |
| Descriptor/package | IDs, versions, build ranges, JCEF dependencies, resources are correct | IntelliJ Platform Gradle validation plus ZIP inspection tests |
| Platform compatibility | No invalid API references for advertised hosts | Plugin Verifier on minimum/current IDEs per lane |
| Installed behavior | Plugin loads and workbench/fallback resources resolve | IntelliJ platform test or Starter/Driver smoke scenario |
| Reproducibility/integrity | Same inputs produce controlled output and verified dependencies | clean double-build comparison, dependency verification, lock checks |
| Policy/provenance | Original identity and required legal/security documents are present | repository policy test and secret/license scan |

### Requirement-to-evidence map

| Requirement | Required automated evidence |
|---|---|
| PROD-01 | Complete wrapper; clean build without global Gradle/Node; explicit lane toolchains |
| PROD-02 | Two installable ZIPs; descriptor validation; load/resource smoke tests |
| PROD-03 | Gradle-built web assets; input manifest; stale/missing resource negative tests; ZIP requires no Node |
| PROD-04 | New ID/name/package/asset assertions; license/disclaimer/notice presence |
| PROD-05 | Clean-room/contribution/security/provenance docs and policy checks |
| PROD-06 | Exact versions, npm lock enforcement, Gradle locks/verification metadata, wrapper checksum |

### Nyquist sampling rule

Every Phase 1 task must add or update a check at the same time as the production change. No plan may defer all verification to a final “test everything” task. The first task establishes red tests/assertions where practical; later tasks make them pass.

### Manual evidence

- Install each ZIP in the exact advertised IDE once before calling the lane usable.
- Open the workbench with JCEF available and verify the explicit fallback when unavailable.
- Review original identity/assets and legal text for accidental affiliation claims.
- Confirm a machine/container with no global Node can build through Gradle provisioning.

## Planning Implications

Recommended plans:

1. **Identity and policy boundary** — original name/ID/package/assets, license, disclaimer, clean-room, contribution, and security documents.
2. **Self-sustaining build** — complete wrapper, isolated included host builds, pinned/auto-provisioned JDK 21/25 toolchains, Gradle-provisioned Node, same-revision frontend resource pipeline.
3. **Build correctness** — resolve source compile blockers required for packaging, add smoke/package/ZIP assertions, dependency locks and verification.
4. **Compatibility verification and documentation** — build both ZIPs, run Plugin Verifier/smoke gates, document prerequisites/artifacts/host-versus-target-Java matrix.

Plans 1 and the early validation scaffolding can run before host packaging. Plans 2–4 are dependency ordered because verifier/install evidence requires assembled artifacts.

## Sources

Primary sources already captured with detailed URLs and confidence in:

- `.planning/research/STACK.md`
- `.planning/research/ARCHITECTURE.md`
- `.planning/research/PITFALLS.md`
- `.planning/research/SUMMARY.md`
- `JMIX_STUDIO_ASSESSMENT.md`

Key authoritative references:

- JetBrains IntelliJ Platform Gradle Plugin 2.x documentation: <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html>
- JetBrains dependency extension and verifier/signer tooling: <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html>
- JetBrains plugin compatibility/build ranges: <https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html>
- JetBrains 2026.2 compatibility guidance: <https://platform.jetbrains.com/t/2026-2-is-coming-time-to-check-your-plugin-compatibility/4618>
- JetBrains plugin testing guidance: <https://plugins.jetbrains.com/docs/intellij/testing-plugins.html>
- Jmix 2.8 release/setup documentation: <https://docs.jmix.io/jmix/2.8/whats-new/index.html>
- Jmix 3.0 release requirements: <https://docs.jmix.io/jmix/whats-new/release-3.0.html>
- Gradle wrapper, dependency locking, and dependency verification manuals: <https://docs.gradle.org/current/userguide/gradle_wrapper.html>, <https://docs.gradle.org/current/userguide/dependency_locking.html>, <https://docs.gradle.org/current/userguide/dependency_verification.html>
- Node Gradle plugin documentation: <https://github.com/node-gradle/gradle-node-plugin>
- Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0>

## Research Complete

Phase 1 can be planned without enabling Jmix repository mutation. The plan must preserve the host-runtime versus target-project-JDK distinction, make Node a project-local build tool only, and require evidence for every build/identity claim.

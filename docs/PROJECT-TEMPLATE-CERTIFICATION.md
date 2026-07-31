# Native Jmix Project Wizard Certification

Date: 2026-07-31

## Scope

The plugin now contributes a native IntelliJ **New Project → Jmix** generator.
It does not depend on the embedded web workbench and is registered in the shared
descriptor plus both packaged host descriptors.

The certified template set contains:

- a headless Jmix application with a real Spring/Jmix startup, Jmix metadata,
  EclipseLink store, HSQLDB development profile and versioned Liquibase root;
- a FlowUI application with Java or Kotlin sources, responsive theme,
  main/login/welcome views, a three-level menu, localized bundles, resource
  roles, entity-backed production users and a local-only in-memory
  administrator whose password is environment-controlled or randomly generated;
- a source-first reusable Jmix add-on with sources/Javadoc publication;
- a composite build containing a connected application and shared add-on; and
- optional Ed25519-signed organization baselines selected from an immutable,
  reverified offline cache.

The wizard exposes template type, Java/Kotlin language, headless/FlowUI
application mode, Jmix version, Java target, SDK, Maven coordinates, base
package, seven-character Jmix project ID, locales, `mavenLocal()` opt-in and
credential-free HTTPS repository URLs.

Organization baselines are managed natively under **Settings → Tools → Jmix
Organization Templates**. Exact bundle SHA-256, signer, catalog/version and
anti-rollback policy are reverified before generation. The same page now
authors schema-v2 text/binary overlays through a reviewed side-by-side diff
and transient key files or pluggable enterprise HSM/PKCS#11 signing providers.
See
`ORGANIZATION-TEMPLATE-CATALOGS.md`.

## Safety and reproducibility

- Unknown Jmix versions and uncertified Jmix/Java combinations fail closed.
- Jmix 2.8.2 supports Java 17 and 21 with Gradle 8.14.4.
- Jmix 3.0.0 supports Java 21 and Java 25 runtime with Java 21 bytecode and
  Gradle 9.5.1.
- Both Gradle distributions are pinned by SHA-256.
- The plugin bundles the Gradle wrapper scripts and wrapper JAR, and verifies
  the wrapper JAR SHA-256 before installing any target file.
- Generation is deterministic and rejects invalid Java packages, unsafe
  coordinates, credentials in repository URLs, non-HTTPS external
  repositories, traversal, duplicate paths and symlinked parents.
- Guided template authoring ignores generated/IDE/VCS content, refuses
  symbolic links, path collisions and protected wrapper changes, bounds every
  scan, detects concurrent file replacement, and repeats the source digest and
  typed-change comparison after review before signing.
- Installation is create-only. Existing files are never replaced.
- Every file is staged before the first mutation. A mid-install failure removes
  only wizard-created files/directories and preserves pre-existing `.idea`
  content.
- The generated development datasource contains no production credential.
  FlowUI production uses the database user repository, while the `dev` profile
  uses an in-memory administrator. `JMIX_DEV_ADMIN_PASSWORD` can provide the
  local password; otherwise a cryptographically random one-time password is
  logged at local startup. No fixed administrator password is generated.
- FlowUI templates provide menu, view and specific-policy roles. Their default
  menu is nested three levels and their layouts use bounded fluid sizing and a
  mobile breakpoint.
- The wizard applies the selected IntelliJ SDK and links the generated build
  through IntelliJ's public Gradle project-settings API after a successful
  installation. Plugin Verifier confirms that this path uses no internal API.

## Real runtime matrix

The opt-in `JmixProjectTemplateRuntimeTest` materializes projects through the
same generator and installer shipped in the plugin, then uses each generated
wrapper to clean, compile and test Java and Kotlin versions of all three
template types. For applications and composites it covers both headless and
FlowUI modes; add-ons are UI-neutral. FlowUI cells also build the real Vaadin
production frontend. Application templates and composite application modules
run Liquibase and start/close a real Spring/Jmix context.

| Jmix | Runtime JDK | Compile target | Variants | Result |
|---|---:|---:|---:|---|
| 2.8.2 | 17 | 17 | 10 | PASS |
| 2.8.2 | 21 | 21 | 10 | PASS |
| 3.0.0 | 21 | 21 | 10 | PASS |
| 3.0.0 | 25 | 21 | 10 | PASS |

The 40-variant matrix completed in 19 minutes 17 seconds on 2026-07-31. It
verifies aligned JUnit launcher/engine dependencies, correct Jmix 2/Jmix 3
`DataSourceProperties` packages, Jmix-compatible Kotlin annotations, FlowUI
theme assets, security/view registration, and core → shared add-on →
application module ordering. Every runnable project must log startup on the
selected runtime; Java 25 cannot silently fall back to Java 21. Jmix 3 Java 25
templates use the Java 25 toolchain while `--release 21` and Kotlin JVM target
21 retain framework-compatible bytecode.

A separate eight-variant organization-template matrix covers Java/Kotlin
FlowUI projects in all four cells. Each cell authors and signs a strict
schema-v2 catalog containing source text and an exact binary resource, verifies
and applies its overlay, builds the production frontend, runs Liquibase and
starts Jmix on the selected runtime. Legacy schema-v1 text catalogs have a
separate compatibility contract. The current eight-cell run passed in
4 minutes 49 seconds on 2026-07-31.

## Packaging gates

Both installable ZIPs must contain:

- `JmixNewProjectWizard`, `JmixProjectTemplateGenerator` and
  `JmixProjectInstaller`;
- the catalog verifier, manager and native organization-template configurable;
- the native authoring/review dialogs and signing-provider extension point;
- `project-template/gradlew` and `gradlew.bat`;
- `project-template/gradle/wrapper/gradle-wrapper.jar`;
- the `newProjectWizard.generator` registration; and
- the mandatory `com.intellij.gradle` dependency.

The root descriptor architecture check, both host descriptor tests and the
nested ZIP verifier enforce these requirements.

## Release evidence

The final clean `phase1Check` release gate passed in 7 minutes 24 seconds on
2026-07-31:

| Host | Regression + smoke tests | Plugin Verifier | Installable ZIP SHA-256 |
|---|---:|---|---|
| IntelliJ IDEA 2025.3 | 360 passed (357 regression + 3 smoke), 0 skipped/failed/error | Compatible; no internal API usage | `85e741da504900ec32bf2ebccb38b5e241acb404867a0eae12477b32ea4ebf4a` |
| IntelliJ IDEA 2026.2 | 360 passed (357 regression + 3 smoke), 0 skipped/failed/error | Compatible; no internal API usage | `4cb0282b159be4e743975d9bd2e437cc815fdfbc7efd2565ecbdc6b80f422767` |

The same gate passed strict dependency verification, generated-code
compatibility for all four Jmix/JDK cells, mutation/index architecture checks,
the production web bundle, host smoke tests and nested ZIP-content inspection.
The bundled Gradle plugin artifacts for both IntelliJ hosts are individually
SHA-256 pinned in Gradle verification metadata.

## Remaining before STRONG

- installed IntelliJ keyboard, screen-reader, validation and recovery journeys.

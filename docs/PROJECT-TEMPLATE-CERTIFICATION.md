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
- a composite build containing a connected application and shared add-on.

The wizard exposes template type, Java/Kotlin language, headless/FlowUI
application mode, Jmix version, Java target, SDK, Maven coordinates, base
package, seven-character Jmix project ID, locales, `mavenLocal()` opt-in and
credential-free HTTPS repository URLs.

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

## Packaging gates

Both installable ZIPs must contain:

- `JmixNewProjectWizard`, `JmixProjectTemplateGenerator` and
  `JmixProjectInstaller`;
- `project-template/gradlew` and `gradlew.bat`;
- `project-template/gradle/wrapper/gradle-wrapper.jar`;
- the `newProjectWizard.generator` registration; and
- the mandatory `com.intellij.gradle` dependency.

The root descriptor architecture check, both host descriptor tests and the
nested ZIP verifier enforce these requirements.

## Release evidence

The clean `phase1Check` release gate passed in 7 minutes 59 seconds on
2026-07-31:

| Host | Regression + smoke tests | Plugin Verifier | Installable ZIP SHA-256 |
|---|---:|---|---|
| IntelliJ IDEA 2025.3 | 341 passed (338 regression + 3 smoke), 0 skipped/failed/error | Compatible; no internal API usage | `6faffeb2595a5115aee55477fffc1d19c58b742f76aa403f95dfd324c1ee1cc5` |
| IntelliJ IDEA 2026.2 | 341 passed (338 regression + 3 smoke), 0 skipped/failed/error | Compatible; no internal API usage | `84f32952485fa490e0f4524bd85bb395c5bd47b3190b88ef349e322bb526bf09` |

The same gate passed strict dependency verification, generated-code
compatibility for all four Jmix/JDK cells, mutation/index architecture checks,
the production web bundle, host smoke tests and nested ZIP-content inspection.
The bundled Gradle plugin artifacts for both IntelliJ hosts are individually
SHA-256 pinned in Gradle verification metadata.

## Remaining before STRONG

- organization-controlled, signed and offline custom-template catalogs;
- installed IntelliJ keyboard, screen-reader, validation and recovery journeys.

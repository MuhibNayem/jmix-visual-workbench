# Native Jmix Project Wizard Certification

Date: 2026-07-31

## Scope

The plugin now contributes a native IntelliJ **New Project → Jmix** generator.
It does not depend on the embedded web workbench and is registered in the shared
descriptor plus both packaged host descriptors.

The first certified template set contains:

- a headless Jmix application with a real Spring/Jmix startup, Jmix metadata,
  EclipseLink store, HSQLDB development profile and versioned Liquibase root;
- a source-first reusable Jmix add-on with sources/Javadoc publication;
- a composite build containing a connected application and shared add-on.

The wizard exposes template type, Jmix version, Java target, SDK, Maven
coordinates, base package, seven-character Jmix project ID, locales,
`mavenLocal()` opt-in and credential-free HTTPS repository URLs.

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
  Its README requires production secrets to come from an external profile or
  secret store.
- The wizard applies the selected IntelliJ SDK and links the generated build
  through IntelliJ's public Gradle project-settings API after a successful
  installation. Plugin Verifier confirms that this path uses no internal API.

## Real runtime matrix

The opt-in `JmixProjectTemplateRuntimeTest` materializes projects through the
same generator and installer shipped in the plugin, then uses each generated
wrapper to clean, compile and test all three template types. Application
templates and composite application modules additionally start and close a real
Spring/Jmix context.

| Jmix | Runtime JDK | Compile target | Application | Add-on | Composite |
|---|---:|---:|---|---|---|
| 2.8.2 | 17 | 17 | PASS | PASS | PASS |
| 2.8.2 | 21 | 21 | PASS | PASS | PASS |
| 3.0.0 | 21 | 21 | PASS | PASS | PASS |
| 3.0.0 | 25 | 21 | PASS | PASS | PASS |

The matrix also verifies aligned JUnit launcher/engine dependencies and correct
Jmix 2/Jmix 3 `DataSourceProperties` packages.

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

The clean `phase1Check` release gate passed on 2026-07-31:

| Host | Regression + smoke tests | Plugin Verifier | Installable ZIP SHA-256 |
|---|---:|---|---|
| IntelliJ IDEA 2025.3 | 339 passed, 0 skipped/failed/error | Compatible; no internal API usage | `57c4d9e65f6cbd6864e10ea991038d8eac9b0112dc6f39463d3f78e1a7db5bf2` |
| IntelliJ IDEA 2026.2 | 339 passed, 0 skipped/failed/error | Compatible; no internal API usage | `0430b1e9e67588ecf84cb9583dd97497808b6b786e15fc2233dde8c889a5ba1c` |

The same gate passed strict dependency verification, generated-code
compatibility for all four Jmix/JDK cells, mutation/index architecture checks,
the production web bundle, host smoke tests and nested ZIP-content inspection.
The bundled Gradle plugin artifacts for both IntelliJ hosts are individually
SHA-256 pinned in Gradle verification metadata.

## Remaining before STRONG

- full FlowUI application templates matching ordinary Jmix Studio daily
  project creation;
- Java/Kotlin selection and compiled Kotlin template variants;
- organization-controlled, signed and offline custom-template catalogs;
- installed IntelliJ keyboard, screen-reader, validation and recovery journeys.

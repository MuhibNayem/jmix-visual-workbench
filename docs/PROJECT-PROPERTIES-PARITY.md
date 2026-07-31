# Project Configuration Parity

## Product contract

The Project Configuration workspace follows the public Jmix Studio project
properties and data-store contracts:

- [Project Properties](https://docs.jmix.io/jmix/studio/project-properties.html)
  defines repositories, Jmix version, locales and formats, artifact
  coordinates, server settings and dependency management.
- [Data Stores](https://docs.jmix.io/jmix/studio/data-stores.html) defines main
  and additional stores, profile-aware configuration, environment
  placeholders, schema-management modes, generic databases, driver
  dependencies and connection testing.

This workbench must support those workflows without executing untrusted Gradle
scripts merely to render the editor and without moving credentials into JCEF.

## Implemented discovery baseline

`JmixProjectPropertiesService` builds a revision-bound inventory from the
current IntelliJ project model and the cached application graph:

- every imported module or included content root can contribute Groovy/Kotlin
  Gradle builds, settings, `gradle.properties`, version catalogs and
  `application[-profile].properties`;
- conventional main resource roots and their `config` directories are covered
  without recursively rescanning the whole repository;
- existing static Gradle evidence reports effective/conflicting Jmix and Java
  versions plus detected add-ons;
- profiles expose server port, context path, locales, main/additional data
  stores, driver and Liquibase configuration;
- undeclared stores discovered through datasource prefixes are flagged rather
  than silently treated as valid additional stores;
- literal passwords, secret values, placeholder defaults, JDBC URL user-info
  and password/token query parameters are redacted before serialization;
- malformed properties and YAML configurations fail closed into explicit
  read-only findings instead of being guessed.

The responsive web workspace is available inside the normal IDE workbench and
through **Tools → Jmix Project Configuration**. Every source is navigable by an
exact revision locator. The shared and both host-specific descriptors register
the service and action; the nested installable-ZIP gate requires their classes
and registrations.

## Current verification

- Pure parser tests cover profiles, locales, multi-store discovery, undeclared
  stores, placeholder handling and credential redaction.
- IntelliJ integration tests load real fixture files through the project model
  and prove the complete serialized workspace contains no datasource secret.
- A clean dual-host release gate passes 357 regression plus 3 smoke tests per
  host with zero skips/failures/errors, compatible Plugin Verifier results and
  exact nested-ZIP inspection.
- The production TypeScript/Vite build passes.
- Browser inspection at 1280, 640, 440 and 320 CSS pixels proves zero document
  overflow, non-overlapping adaptive regions and no clipped workspace buttons.
  Workspace controls have a minimum 36-pixel keyboard/touch target and visible
  focus styling.
- The build-owned Node runtime and immutable web-input fingerprint produce the
  packaged UI; no system Node installation is required.

Release ZIP SHA-256:

- IDEA 2025.3:
  `85e741da504900ec32bf2ebccb38b5e241acb404867a0eae12477b32ea4ebf4a`
- IDEA 2026.2:
  `4cb0282b159be4e743975d9bd2e437cc815fdfbc7efd2565ecbdc6b80f422767`

## Required before STRONG

Discovery is only the first half of the workflow. The row remains `PARTIAL`
until all of the following use immutable preview/apply, stale rejection, exact
rollback and IntelliJ undo:

1. source-preserving Groovy/Kotlin DSL repository editing, including
   credential providers and private organization repositories;
2. Jmix/add-on upgrade handoff with release notes, migration adapters and build
   verification rather than unsafe literal-only version replacement;
3. artifact name/group/version and Java/Kotlin/toolchain changes across
   convention plugins, version catalogs and composite builds;
4. locale matrix and format-string editing;
5. server port/context and profile comparison/editing;
6. main/additional/generic data-store create, edit and removal, dependency and
   configuration-class changes, schema-management modes and protected secret
   references;
7. credential-safe connection tests and supported-database runtime proof;
8. dependency add/remove/upgrade with conflict analysis;
9. installed-IDE keyboard, screen-reader, cancellation and recovery
   certification on both supported hosts.

Unknown dynamic Gradle, YAML, convention-plugin or external-policy shapes must
remain read-only until their exact owner and mutation contract are proven.

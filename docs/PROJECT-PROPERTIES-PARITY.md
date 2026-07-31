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
  stores, active Spring profiles, driver and Liquibase configuration;
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

## Implemented profile mutation contract

The same workspace now edits existing indexed
`application[-profile].properties` files without replacing or reformatting the
document:

- server port, context path, available locales and the default profile's active
  Spring profiles;
- `jmix.core.additional-stores`;
- existing main/additional store JDBC URL, username, driver class, Liquibase
  changelog and enabled state;
- password replacement through an environment-variable reference only.

Existing separators, comments, ordering, Unicode, escaped values and CR, LF or
CRLF line endings remain byte-for-byte unchanged outside the exact value
ranges. New properties are appended deterministically using the document's
line separator. Duplicate source declarations, continued logical lines,
unsupported keys, malformed values, stale locators, unindexed targets and
post-preview edits fail closed.

The preview returned to JCEF deliberately contains only the selected keys.
Unrelated source, comments and secret-bearing properties never cross the
bridge. Literal password values and placeholder defaults are denied for secret
updates; URL user-info and secret query parameters are also denied. The
backend-owned approval digest still binds the complete original and result
documents. Apply recomputes the plan, rechecks it under IntelliJ's write lock,
writes atomically, verifies the result and records exact visual undo/redo.

This intentionally strengthens the public Studio placeholder workflow:
non-secret properties can use placeholders, while password editing does not
permit a literal fallback secret to traverse JCEF.

## Current verification

- Pure parser tests cover profiles, locales, multi-store discovery, undeclared
  stores, active profiles, escaped/continued properties, CR/LF/CRLF handling,
  placeholder handling and credential redaction.
- IntelliJ integration tests load real fixture files through the project model
  and prove the complete serialized workspace contains no datasource secret.
  Mutation fixtures additionally prove focused secret-safe preview, exact
  formatting preservation, deterministic append, validation, indexed-target
  ownership, stale/digest rejection, atomic apply and exact undo/redo.
- Thirteen focused parser/integration tests pass independently on IDEA 2025.3
  and IDEA 2026.2 with zero skips/failures/errors.
- Build-owned package-contract tests require the profile request/apply models,
  backend bridge, service, native Tools action and both frontend action names
  in every installable ZIP.
- The final clean release gate passes 367 regression tests plus 3 host smoke
  tests independently on IDEA 2025.3 and IDEA 2026.2 with zero
  failures/errors/skips. Both Plugin Verifier lanes report `Compatible`, and
  exact nested-ZIP inspection passes.
- The production TypeScript/Vite build passes.
- Browser inspection at 1280, 640, 440 and 320 CSS pixels proves zero document
  or body overflow, no overflowing inputs and no clipped workspace buttons.
  Visible workspace controls have a minimum 40-pixel keyboard/touch target and
  visible
  focus styling.
- The build-owned Node runtime and immutable web-input fingerprint produce the
  packaged UI; no system Node installation is required.

Release ZIP SHA-256:

- IDEA 2025.3:
  `fce62a327212ce51a1c7bacc225529841c77afa3801cd37925ffff0e7e5e4d43`
- IDEA 2026.2:
  `4e34357663d0cb0a75101cfdb00de9d75450efd70ba9142b2cdb6135ec5d2ef8`

Both archives contain web input SHA-256
`77e05abe10d66550cfeb7d5705c851edea56a2090803983938bdc8b074af912b`.

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
4. complete locale matrix, fallback and format-string editing beyond the
   implemented profile locale list;
5. profile comparison, creation/removal and external `.env` mutation beyond
   the implemented active/profile-specific properties editing;
6. main/additional/generic data-store create and removal, dependency and
   configuration-class changes, schema-management modes, multi-DB identifier
   policies and protected secret references beyond the implemented
   source-preserving connection-property editor;
7. credential-safe connection tests and supported-database runtime proof;
8. dependency add/remove/upgrade with conflict analysis;
9. installed-IDE keyboard, screen-reader, cancellation and recovery
   certification on both supported hosts.

Unknown dynamic Gradle, YAML, convention-plugin or external-policy shapes must
remain read-only until their exact owner and mutation contract are proven.

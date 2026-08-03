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
- [External Configuration with `.env`](https://docs.jmix.io/jmix/studio/external-config-with-env.html)
  defines the Studio workflow for connecting project-local environment files.
- [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
  and [profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html)
  define import precedence, placeholders, profile groups and includes.

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

## Implemented profile lifecycle and comparison

The same workspace now completes the safe profile lifecycle inside the IDE:

- create a named `application-<profile>.properties` beside an indexed module
  default without cloning credentials or unknown source;
- compare a profile against its module default, including explicit overrides,
  environment-only values and effective inherited fallback;
- remove non-default profiles through a credential-safe summary that never
  sends the deleted document's property values to JCEF;
- block removal of the default profile and any profile still selected by
  indexed active/include/group configuration; dynamic activation references
  fail closed;
- preserve exact deleted bytes for rollback and visual undo/redo.

Profile creation, modification and deletion use the same backend-recomputed
approval digest. The shared workspace mutation engine now treats deletion as a
first-class exact-revision operation: outer and under-write-lock preflight,
post-write absence verification, injected-failure rollback, undo recreation,
redo deletion and stale restored-source rejection are enforced for all future
visual features, not only Project Configuration.

## Implemented external environment and activation contract

Project Configuration now includes a continuous responsive three-region
environment workspace rather than a disconnected tab:

- only project-local `.env` and `.env.properties` files explicitly imported by
  an indexed `spring.config.import` declaration are inventoried;
- unsupported, absolute, dynamic, traversing or ambiguous imports remain
  visible as read-only findings and cannot become mutation targets;
- comments, blank lines, `export`, quoted/unquoted values and CR/LF/CRLF are
  parsed with exact ranges; duplicates and unsupported syntax fail closed;
- non-secret variables support revision-bound add, update and guarded removal;
  missing imported files are created only when the first variable is approved;
- references from application properties are shown, and referenced variables
  cannot be removed before their consumers are changed;
- profiles without an environment import can add
  `optional:file:.env[.properties]` through a focused, source-preserving
  preview;
- active-profile expressions, placeholder defaults, imported values, profile
  groups, includes, missing profile files and cycles are explained without
  claiming that configuration evidence proves a running process;
- IntelliJ `.run`, `.idea/runConfigurations` and workspace launch evidence is
  indexed across registered multi-module roots and explicitly labelled as
  launch evidence rather than runtime proof.

Secret values never enter JCEF. Secret selection occurs in a native IntelliJ
`JBPasswordField`; the backend retains a single-use, five-minute, memory-only
capability and clears the dialog character buffer. JCEF receives only a
redacted focused preview. Browser-visible workspace, source, launch,
change-set, plan and history revisions use per-project HMAC tokens instead of
raw content hashes, preventing weak-secret hash-oracle attacks. Environment
source opening uses a dedicated native verifier that validates the opaque token
against the current bounded workspace and consumes the real fingerprint only
inside IntelliJ. Forged, stale and cross-workspace tokens fail closed.

## Current verification

- Pure parser tests cover profiles, locales, multi-store discovery, undeclared
  stores, active profiles, escaped/continued properties, CR/LF/CRLF handling,
  placeholder handling and credential redaction.
- IntelliJ integration tests load real fixture files through the project model
  and prove the complete serialized workspace contains no datasource secret.
  Mutation fixtures additionally prove focused secret-safe preview, exact
  formatting preservation, deterministic append, validation, indexed-target
  ownership, stale/digest rejection, atomic apply and exact undo/redo.
- Twenty-three focused parser/integration tests pass independently on IDEA 2025.3
  and IDEA 2026.2 with zero skips/failures/errors.
- Build-owned package-contract tests require the profile request/apply models,
  backend bridge, service, native Tools action and both frontend action names
  in every installable ZIP.
- The final clean release gate passes 377 regression tests plus 3 host smoke
  tests independently on IDEA 2025.3 and IDEA 2026.2 with zero
  failures/errors/skips. Both Plugin Verifier lanes report `Compatible`, and
  exact nested-ZIP inspection passes.
- The production TypeScript/Vite build passes.
- Browser inspection at 1280, 640, 440 and 320 CSS pixels proves zero document
  or body overflow, no overflowing workspace regions or controls, and at least
  40-pixel visible workspace controls. Default/environment comparison, create
  review/apply, deletion review/apply, active-profile protection and completion
  feedback work at 320 pixels with no console warning or error. The external
  environment workspace separately passes 1280/320 inspection with exact
  viewport width, no horizontal overflow, no sub-40-pixel controls and working
  opaque-token source navigation.
- The build-owned Node runtime and immutable web-input fingerprint produce the
  packaged UI; no system Node installation is required.

Release ZIP SHA-256:

- IDEA 2025.3:
  `40e59a2a1111df9d062cf5446bef6cede85bfc063be21341945bba75d7aa6ced`
- IDEA 2026.2:
  `7bb66c1efd0cafb06607dfa39584f4ba536bc615dff28c82272b4660ac6e83a1`

Both archives contain web input SHA-256
`7638031f4d917734a867dc00d5638f372f7942edac0228cbbe24bbb7ac5a8a3d`.

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
5. attach-to-process runtime activation proof and safe operating-system or
   deployment-environment reconciliation beyond the implemented `.env`,
   profile-group/include and IntelliJ launch-evidence workflows;
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

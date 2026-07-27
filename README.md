# Jmix Visual Workbench

Jmix Visual Workbench is an original, clean-room IntelliJ IDEA plugin prototype
for understanding and eventually changing Jmix applications through reviewable
visual workflows.

**Compatible with Jmix. This independent project is not affiliated with or
endorsed by Haulmont.**

## Current status

This repository is early and non-certified. It contains a React/TypeScript
workbench, a Kotlin IntelliJ plugin shell, in-memory models, and draft
string-based generators. The checked-in wrapper now builds and verifies
separate verified IDEA 253 and IDEA 262 plugin artifacts with a project-local Node
24.18.0 toolchain. The current generator and bridge paths remain prototypes:
they do not safely parse, preview, merge, validate, atomically apply, or undo
changes to an existing project.

Do not run the current mutation paths against a valuable repository. The
verified findings and planned remediation are in
[JMIX_STUDIO_ASSESSMENT.md](JMIX_STUDIO_ASSESSMENT.md) and
[the project roadmap](.planning/ROADMAP.md).

## Intended product boundary

The product direction is a source-aware workbench in which:

- repository source remains authoritative;
- compatibility is declared per tested Jmix/IDE/JDK/project profile and
  operation;
- unrecognized or uncertified projects remain diagnostic or read-only;
- every mutation is planned, diffed, validated, contained to the intended
  module, applied atomically through IntelliJ, and undoable;
- compatibility behavior is implemented independently from public
  specifications and openly licensed sources.

Existing entity, view, CRUD, menu, role, migration, repository, event, and BPMN
generators are non-certified prototypes, not supported product features.

## Architecture snapshot

```text
webui/                        React/TypeScript prototype workbench
  src/
    components/               Draft visual designers
    bridge/                   JCEF client adapter
    store/                    Zustand state
    types/                    Mirrored payload types

plugin/                       Kotlin IntelliJ plugin prototype
  src/main/kotlin/
    actions/                  IDE action entry points
    bridge/                   JCEF request dispatcher
    generator/                Draft pure string generators
    model/                    Draft backend models
    services/                 Project detection and direct-write prototype
    toolwindow/               JCEF host
```

The target architecture replaces direct writes with a typed privilege boundary,
semantic project index, version-aware adapters, immutable change plans,
structured editors, validation, atomic application, and exact rollback/undo.

## Build and compatibility evidence

Use only the checked-in wrapper; global Gradle and Node installations are not
part of the build:

```text
cd plugin
./gradlew clean phase1Check --dependency-verification=strict
```

Gradle downloads a project-local Node 24.18.0 runtime, runs the locked frontend
build, provisions Java 21 and Java 25 compiler toolchains, builds both
lane-suffixed ZIPs, runs smoke/tests and Plugin Verifier, and inspects packaged
contents. Manual installation into the two minimum IDEs remains pending.

- [Exact build prerequisites, commands, artifacts, and offline behavior](docs/BUILDING.md)
- [IntelliJ host and target-project compatibility matrices](docs/COMPATIBILITY.md)
- [Dependency, checksum, CI, and future signing/SBOM policy](docs/RELEASE-INTEGRITY.md)

Java 17, Java 21, and Java 25 target-project cells are future fixture
certification targets. Repository mutation remains non-certified and disabled
for valuable repositories; this build work does not enable it.

## Project policies

- [Apache License 2.0](LICENSE)
- [Clean-room implementation policy](CLEAN_ROOM.md)
- [Trademark and compatibility statement](TRADEMARKS.md)
- [Contribution and provenance requirements](CONTRIBUTING.md)
- [Security reporting](SECURITY.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

The clean-room policy prohibits proprietary Jmix Studio code, assets, templates,
protocol copying, decompilation-derived behavior, license bypass, and
redistribution of commercial add-on runtimes.

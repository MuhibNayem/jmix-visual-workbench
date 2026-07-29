# Jmix Visual Workbench

Jmix Visual Workbench is an original, clean-room IntelliJ IDEA plugin prototype
for understanding and eventually changing Jmix applications through reviewable
visual workflows.

**Compatible with Jmix. This independent project is not affiliated with or
endorsed by Haulmont.**

## Current status

This repository is an advanced, non-certified development preview. It contains
a module-aware semantic application index, source-safe visual change planning,
digest-bound previews, stale-source rejection, atomic IntelliJ writes and
workspace undo/redo. Supported visual documents use deterministic round-trip
models; manual or unsupported source is kept read-only instead of being
overwritten.

The checked-in wrapper builds and tests separate IDEA 253 and IDEA 262 plugin
artifacts with a Gradle-managed Node 24.18.0 toolchain. Generated target-project
source stays Java 17 compatible while the host lanes are exercised with their
current Java toolchains.

The workbench is not yet certified as a complete replacement for every paid
Studio or handwritten-development workflow. Review the current evidence and
remaining gaps in [the enterprise parity audit](docs/ENTERPRISE-PARITY-AUDIT.md).

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

Implemented workspaces currently include:

- connected multi-build/multi-module application mapping and impact analysis;
- entity, CRUD, menu, resource/row security, REST and Liquibase tooling;
- a permanent three-region FlowUI round-trip designer;
- advanced BPMN authoring and deterministic workflow simulation;
- typed transactional server logic and reusable server-enforced formulas;
- Jmix/Flowable DMN decision tables with hit policies, conflict analysis,
  governance metadata, simulation and BPMN key resolution;
- source-generated integration scenarios and production-readiness diagnostics.

Every workspace remains subject to the compatibility and certification
boundaries documented in the audit.

## Architecture snapshot

```text
webui/                        React/TypeScript prototype workbench
  src/
    components/               Responsive visual engineering workspaces
    bridge/                   JCEF client adapter
    store/                    Zustand state
    types/                    Mirrored payload types

plugin/                       Kotlin IntelliJ plugin prototype
  src/main/kotlin/
    actions/                  IDE action entry points
    bridge/                   JCEF request dispatcher
    generator/                Deterministic typed source generators/patchers
    model/                    Closed bridge and authoring models
    services/                 Index, preview, source-safety and workspace changes
    toolwindow/               JCEF host
```

The active architecture uses a typed bridge boundary, semantic project index,
immutable reviewed change plans, structured editors, validation, atomic
application and workspace rollback/undo.

## Build and compatibility evidence

Use only the checked-in wrapper; global Gradle and Node installations are not
part of the build:

```text
cd plugin
./gradlew test --dependency-verification=strict
```

Gradle downloads a project-local Node 24.18.0 runtime when needed, runs the
locked frontend build, packages the exact current web bundle, provisions the
host toolchains and runs the shared IDEA 253/262 test lanes.

- [Exact build prerequisites, commands, artifacts, and offline behavior](docs/BUILDING.md)
- [IntelliJ host and target-project compatibility matrices](docs/COMPATIBILITY.md)
- [Dependency, checksum, CI, and future signing/SBOM policy](docs/RELEASE-INTEGRITY.md)

Java 17, Java 21 and current-JDK target projects are the compatibility
direction. Generated Java is constrained to Java 17 syntax; representative
real-project fixture certification and signed marketplace distribution remain
release work.

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

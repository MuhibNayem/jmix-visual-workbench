# Jmix Visual Workbench

Jmix Visual Workbench is an original, clean-room IntelliJ IDEA plugin prototype
for understanding and eventually changing Jmix applications through reviewable
visual workflows.

**Compatible with Jmix. This independent project is not affiliated with or
endorsed by Haulmont.**

## Current status

This repository is early and non-certified. It contains a React/TypeScript
workbench, a Kotlin IntelliJ plugin shell, in-memory models, and draft
string-based generators. The frontend can be built, but the checked-in plugin
build is not yet reproducible on the supported IntelliJ baseline. The current
generator and bridge paths are prototypes: they do not safely parse, preview,
merge, validate, atomically apply, or undo changes to an existing project.

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

## Building the prototype

The frontend currently requires a local Node.js installation:

```bash
cd webui
npm ci
npm run build
```

The IntelliJ plugin build is currently blocked by an incomplete Gradle wrapper,
obsolete platform/build configuration, and known source defects. Commands that
claim to produce an installable plugin are intentionally not documented until
the self-sustaining dual-host build is implemented and verified.

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

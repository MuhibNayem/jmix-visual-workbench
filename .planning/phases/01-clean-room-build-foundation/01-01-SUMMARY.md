---
phase: 01-clean-room-build-foundation
plan: "01"
subsystem: product-governance
tags: [clean-room, apache-2.0, trademark, intellij-plugin, kotlin, react]

requires: []
provides:
  - Original Jmix Visual Workbench product identity and org.jmixworkbench namespace
  - Apache License 2.0 plus clean-room, provenance, trademark, contribution, and security policies
  - Honest non-certified prototype status and independent compatibility disclaimer
affects: [build-foundation, plugin-packaging, compatibility, contributor-governance]

tech-stack:
  added: []
  patterns:
    - Public-source provenance and contributor clean-room attestation
    - Original plugin identity with namespace-aligned descriptor references

key-files:
  created:
    - LICENSE
    - CLEAN_ROOM.md
    - CONTRIBUTING.md
    - TRADEMARKS.md
    - SECURITY.md
    - THIRD_PARTY_NOTICES.md
    - plugin/src/main/resources/icons/workbench.svg
  modified:
    - README.md
    - plugin/src/main/resources/META-INF/plugin.xml
    - plugin/src/main/kotlin/org/jmixworkbench/
    - webui/package.json
    - webui/package-lock.json
    - webui/index.html
    - webui/src/App.tsx

key-decisions:
  - "Use Jmix Visual Workbench with plugin ID org.jmixworkbench and Kotlin namespace org.jmixworkbench."
  - "Treat all existing generators and mutation paths as non-certified prototypes; this plan changes identity only and does not enable mutation."

patterns-established:
  - "Compatibility positioning: Compatible with Jmix; independent and not affiliated with or endorsed by Haulmont."
  - "Compatibility contributions require public-source citations and clean-room attestation."

requirements-completed: [PROD-04, PROD-05]

duration: 7min
completed: 2026-07-27
---

# Phase 1 Plan 1: Identity and Policy Boundary Summary

**Original Jmix Visual Workbench identity with Apache 2.0 licensing, explicit clean-room governance, namespace-aligned runtime metadata, and an original geometric icon**

## Performance

- **Duration:** 7 min
- **Started:** 2026-07-27T18:27:56Z
- **Completed:** 2026-07-27T18:35:10Z
- **Tasks:** 2
- **Files modified:** 37

## Accomplishments

- Replaced clone-oriented marketing with honest early, non-certified prototype status and the exact independent compatibility disclaimer.
- Added Apache License 2.0, notice, trademark, clean-room, contribution/provenance, security, and third-party dependency policies.
- Moved all 22 Kotlin sources from `com.jmixstudio` to `org.jmixworkbench` without generator or mutation behavior changes.
- Aligned the plugin ID, descriptor classes, tool-window/action names, npm metadata, HTML/UI labels, development property, and migration author identity.
- Replaced the legacy lettermark with an original geometric `workbench.svg` asset.

## Task Commits

Each task was committed atomically:

1. **Task 1: Establish the legal, clean-room, contribution, and security boundary** — `3029425` (docs)
2. **Task 2: Rename product, namespace, descriptors, UI metadata, and asset** — `dffdaf7` (refactor)

## Files Created/Modified

- `LICENSE`, `NOTICE` — Apache License 2.0 and project attribution.
- `TRADEMARKS.md`, `CLEAN_ROOM.md` — independent compatibility positioning and allowed/prohibited implementation inputs.
- `CONTRIBUTING.md`, `SECURITY.md` — contributor provenance attestation and private vulnerability-reporting guidance without an invented address.
- `THIRD_PARTY_NOTICES.md` — direct dependency inventory and generated-inventory precedence rule.
- `README.md` — verified status, architecture boundary, build limitations, and policy links.
- `plugin/src/main/kotlin/org/jmixworkbench/` — mechanically moved original Kotlin source inventory.
- `plugin/src/main/resources/META-INF/plugin.xml` — original plugin ID, name, vendor, classes, actions, tool window, description, and icon reference.
- `plugin/src/main/resources/icons/workbench.svg` — original geometric product asset.
- `webui/package.json`, `webui/package-lock.json`, `webui/index.html`, `webui/src/App.tsx` — consistent npm, document, and visible product identity.

## Bounded Kotlin Migration Evidence

The pre-move inventory captured before editing was:

```text
plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt
plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt
plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/DataRepositoryGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt
plugin/src/main/kotlin/com/jmixstudio/generator/MenuGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt
plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt
plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt
plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt
plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt
plugin/src/main/kotlin/com/jmixstudio/model/RoleModel.kt
plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt
plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt
plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt
plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt
```

`git diff --name-status --find-renames=70% 3029425^..dffdaf7` recorded:

```text
A CLEAN_ROOM.md
A CONTRIBUTING.md
A LICENSE
A NOTICE
M README.md
A SECURITY.md
A THIRD_PARTY_NOTICES.md
A TRADEMARKS.md
R088 plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt plugin/src/main/kotlin/org/jmixworkbench/actions/Actions.kt
R095 plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt
R099 plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/BpmGenerator.kt
R099 plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/CrudOrchestrator.kt
R097 plugin/src/main/kotlin/com/jmixstudio/generator/DataRepositoryGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/DataRepositoryGenerator.kt
R099 plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt
R098 plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/EventListenerGenerator.kt
R099 plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt plugin/src/main/kotlin/org/jmixworkbench/generator/JavaClassBuilder.kt
R096 plugin/src/main/kotlin/com/jmixstudio/generator/MenuGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/MenuGenerator.kt
R099 plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/MigrationGenerator.kt
R099 plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/RoleGenerator.kt
R099 plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/ViewControllerGenerator.kt
R099 plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt
R098 plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt plugin/src/main/kotlin/org/jmixworkbench/generator/XmlBuilder.kt
R099 plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt plugin/src/main/kotlin/org/jmixworkbench/model/EntityModel.kt
R098 plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt plugin/src/main/kotlin/org/jmixworkbench/model/MigrationModel.kt
R097 plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt plugin/src/main/kotlin/org/jmixworkbench/model/ProjectConfig.kt
R098 plugin/src/main/kotlin/com/jmixstudio/model/RoleModel.kt plugin/src/main/kotlin/org/jmixworkbench/model/RoleModel.kt
R099 plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt plugin/src/main/kotlin/org/jmixworkbench/model/ViewModel.kt
R099 plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt plugin/src/main/kotlin/org/jmixworkbench/services/CodeGenerationService.kt
R097 plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt plugin/src/main/kotlin/org/jmixworkbench/services/JmixProjectService.kt
R086 plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt
M plugin/src/main/resources/META-INF/plugin.xml
D plugin/src/main/resources/icons/jmix.svg
A plugin/src/main/resources/icons/workbench.svg
M webui/index.html
M webui/package-lock.json
M webui/package.json
M webui/src/App.tsx
```

A file-by-file comparison of every old Kotlin source with its new path passed
after normalizing only `com.jmixstudio` → `org.jmixworkbench`, exposed
`JmixStudio` class/product names, the development-property prefix, and the
migration author identity. No generator, bridge, service, or mutation logic
changed.

## Decisions Made

- Selected the stable original plugin ID `org.jmixworkbench` and product namespace `org.jmixworkbench`.
- Renamed only the public class exposing `JmixStudio` to `JmixWorkbench`; all other Kotlin class and behavior shapes remain unchanged.
- Kept the prototype visible but labeled it non-certified rather than deleting or expanding mutation features.
- Used GitHub private vulnerability reporting when available and repository maintainers otherwise, avoiding a fabricated security address.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- The GSD progress commands updated frontmatter and requirements but did not
  recognize this repository's existing `**Plans**:` and performance-table
  formatting. ROADMAP and the human-readable STATE progress fields were
  reconciled to the command-reported 1/4 plans and 25% completion.

## User Setup Required

None - no external service configuration required.

## Known Stubs

- `plugin/src/main/kotlin/org/jmixworkbench/bridge/JcefBridge.kt:153` — `getEntities` returns an empty array and line 154 retains the existing source-scan TODO. This pre-existing prototype behavior is intentionally unchanged; Phase 2 supplies the semantic read-only index.
- `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewControllerGenerator.kt:289` — generated custom controller methods contain a TODO body. This is pre-existing non-certified generator output.
- `plugin/src/main/kotlin/org/jmixworkbench/generator/EventListenerGenerator.kt:112` — generated event callbacks contain a TODO body. This is pre-existing non-certified generator output.
- `plugin/src/main/kotlin/org/jmixworkbench/generator/EntityGenerator.kt:223` — generated entity lifecycle callbacks contain a TODO body. This is pre-existing non-certified generator output.

These stubs do not prevent this plan's identity and policy goal. The README and
policies explicitly prevent treating these mutation paths as certified features.

## Verification

- Policy/static acceptance checks: passed.
- Normalized Kotlin identity-only comparison: passed for all 22 moved sources.
- Plugin descriptor XML parsing and implementation-class source resolution: passed.
- Old runtime identity/package search: passed with no matches.
- npm package-lock root metadata match: passed.
- `npm run build`: passed (TypeScript and Vite production build).
- `git diff --check`: passed.

## Next Phase Readiness

- Original identity and contributor boundary are ready for Plan 01-02's self-sustaining dual-host build work.
- Existing generator and direct-write risks remain deliberately non-certified and must not be enabled by Phase 1 build changes.

## Self-Check: PASSED

- All required policy, runtime identity, UI metadata, icon, and summary files exist.
- Task commits `3029425` and `dffdaf7` are present in repository history.

---
*Phase: 01-clean-room-build-foundation*
*Completed: 2026-07-27*

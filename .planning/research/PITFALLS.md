# Domain Pitfalls

**Project:** Jmix Visual Development Workbench  
**Domain:** Clean-room IntelliJ/JCEF visual code-generation plugin for existing Jmix solutions  
**Researched:** 2026-07-27  
**Overall confidence:** HIGH for IntelliJ, Gradle, Jmix, Liquibase, and release mechanics documented by primary sources; MEDIUM for recommended organizational controls; LOW for exact performance budgets until representative enterprise repositories are measured

## Executive Risk Position

The central enterprise risk is not that a generator occasionally emits ugly code. It is that the plugin can form an incomplete model of a valuable, long-lived repository and then confidently apply a valid-looking but semantically wrong multi-file change.

Existing Jmix estates worldwide will not look like new samples. Expect upgraded projects with historical conventions, custom Gradle logic, private add-ons, multiple repositories, mixed Java/Kotlin, multiple data stores, manual Liquibase history, profile-specific configuration, hand-edited XML, inherited add-on artifacts, and partially migrated APIs. Therefore:

> Mutation is a certified capability, not the default. If project classification, artifact ownership, target version, or semantic merge safety is uncertain, the workbench must remain read-only.

This policy must be enforced in backend services. Hiding an Apply button in React is not a security or data-integrity boundary.

### Mandatory Degradation Policy

| Detected state | Permitted behavior | Forbidden behavior |
|---|---|---|
| Certified IDE + exact Jmix adapter + supported add-on capability set + unambiguous module/store ownership | Index, plan, validate, preview, and apply supported operations | Any operation outside the certified capability set |
| Recognized Jmix 2.x/3.x project but exact patch/add-on/customization combination is uncertified | Read-only inventory, navigation, diagnostics, plan export marked non-applicable | Source, Gradle, XML, properties, or changelog mutation |
| Legacy Jmix 1.x, CUBA, Classic UI, unknown fork, incomplete Gradle import, or ambiguous build graph | Read-only file/artifact discovery with explicit unsupported reasons | “Best guess” adapter selection or generated output |
| IntelliJ dumb mode, Gradle sync in progress/failed, stale plan, invalid PSI, or unresolved source roots | Cached read-only display; queue cancellable re-analysis for smart mode | Index-dependent apply or fallback regex generation |
| Untrusted project / IntelliJ safe mode | Passive viewing that does not execute project code, builds, scripts, or privileged bridge commands | Gradle execution, database access, external processes, or file writes |

The certified matrix should use exact tuples, not marketing ranges:

```text
IDE build × plugin build × Jmix BOM/Gradle plugin × Java/Kotlin mode
× project topology × add-on set × data-store/dialect × artifact operation
```

## Critical Pitfalls

### Pitfall 1: Treating PSI and Index Results as Stable Application State

**What goes wrong:** The visual model is populated from PSI or indexes and retained while users edit files, Gradle reimports modules, indexing restarts, or a plugin unloads. Later operations dereference invalid elements, resolve against an obsolete graph, throw `IndexNotReadyException`, or apply a plan derived from stale symbols.

**Why it happens:**

- PSI files and documents are created on demand and may be garbage-collected.
- PSI/VFS/module objects can become invalid between read actions.
- Index access is restricted during dumb mode.
- UAST is a read-only abstraction; Java-like PSI from UAST may be synthetic and non-modifiable for Kotlin.
- A long-lived JCEF screen encourages storing backend objects or assuming a loaded draft is still current.

**Warning signs:**

- Backend services cache `PsiElement`, `PsiFile`, `Document`, `VirtualFile`, or `Module` instances indefinitely.
- Actions are marked `DumbAware` while calling resolve or file-based indexes.
- `IndexNotReadyException` is caught and replaced with empty entities or guessed defaults.
- The UI can Apply while indexing or Gradle sync status is unresolved.
- Reopening an edited file shows a different model than the still-open designer.

**Prevention:**

- Export immutable, serializable semantic snapshots to the UI; never expose or retain live PSI objects across requests.
- Persist stable identifiers and `SmartPsiElementPointer` only where appropriate, then revalidate pointers and source fingerprints inside each new read action.
- Use cancellable non-blocking/coroutine read actions and smart-mode scheduling for index-dependent work.
- Make dumb-mode behavior explicit: either genuinely index-free read-only functionality or “analysis pending”; never partial mutation.
- Invalidate only affected semantic entries from VFS/PSI/root-model events, and re-resolve symbols before planning and before apply.
- Use UAST to read common Java/Kotlin declarations, but use language-specific physical PSI for writes. Kotlin editing requires the Kotlin plugin/Analysis API compatibility path; UAST itself is not a write API.

**Validation gate:**

- Force dumb mode before, during, and after every discovery workflow.
- Edit, rename, delete, and move a referenced Java/Kotlin/XML file between plan and apply; Apply must stop with a stale-plan diagnostic.
- Change module/source roots during a queued analysis; no invalid PSI access or wrong-module plan is allowed.
- Reload the JCEF page, close/reopen the project, unload/reload the plugin, and verify that no stale backend reference remains.

**Phase mapping:** Platform foundation and semantic index. This gate must pass before the first mutating designer.

**Confidence:** HIGH — IntelliJ officially documents dumb-mode restrictions, object validity, non-blocking smart-mode reads, UAST read-only behavior, and PSI lifecycle.

---

### Pitfall 2: Confusing a Write Action With an Atomic, Undoable Transaction

**What goes wrong:** Generation performs expensive parsing or rendering on the Event Dispatch Thread (EDT), freezes the IDE, writes multiple files in separate commands, and leaves a partial repository when a later write fails. Undo reverts only part of the operation or prompts unexpectedly across documents. Direct `java.io.File` writes bypass open documents and VFS semantics.

**Why it happens:**

- IntelliJ requires PSI/document writes in a write action and command, but those primitives do not automatically provide application-level transaction rollback.
- The write lock currently runs on EDT, so work left inside it blocks the whole IDE.
- A “write each file then refresh the project” prototype is easy to build.
- Unsaved editor content, VCS read-only state, delayed VFS persistence, formatting, and import optimization complicate multi-file changes.

**Warning signs:**

- One `WriteCommandAction` per generated file.
- Parsing, reference resolution, formatting the whole project, disk traversal, or Gradle execution inside a write action.
- Recursive root refresh after every operation.
- Success is reported before all documents are committed/flushed and postconditions are checked.
- Failure leaves earlier output files changed.
- Undo requires several steps or fails to recover byte-equivalent pre-state.

**Prevention:**

- Build and validate the complete `ChangePlan` off EDT using immutable snapshots.
- Capture before-content, writable status, encoding, line endings, file identity, and fingerprints for every target.
- Immediately before mutation, revalidate fingerprints and semantic anchors.
- Apply accepted edits in one named IntelliJ command with the smallest possible write-action scope.
- Use PSI/VFS/document APIs for IDE-owned files; check `ReadonlyStatusHandler` before mutation.
- Treat the command as undo integration, not as transaction rollback. Keep a staged restoration set and restore all touched files if any operation or postcondition fails.
- Commit documents and complete postponed PSI operations deliberately; run formatting only on changed elements.
- Refresh only exact externally created paths, asynchronously and without holding a read lock.

**Validation gate:**

- Inject failure at every operation boundary, including the final file and post-format stage. The repository and open documents must return to the exact pre-operation state.
- One IDE Undo must restore the exact original bytes for a successful multi-file operation; Redo must restore the accepted result.
- Test unsaved editor changes, VCS/read-only files, mixed encodings/line endings, file creation/deletion, and external file changes during apply.
- Run UI-freeze assertions and verify no long work occurs on EDT or under the write lock.

**Phase mapping:** Change engine in the platform foundation. No feature phase may invent a separate write path.

**Confidence:** HIGH — IntelliJ documents command/undo behavior, write-action/EDT rules, PSI consistency checks, and VFS refresh constraints.

---

### Pitfall 3: Giving an Embedded Web Page Ambient Filesystem Authority

**What goes wrong:** A JCEF page loaded from a development URL, redirected navigation, compromised bundled dependency, injected script, or crafted bridge payload can invoke privileged file-generation commands. User-controlled identifiers escape the intended module root through absolute paths, `..`, platform separators, Unicode/path normalization, or symlinks.

**Why it happens:**

- JCEF supports asynchronous JavaScript-to-plugin callbacks that execute plugin code.
- A privileged query injected after every navigation effectively gives each loaded origin plugin-process authority.
- React validation is bypassable; callers can issue raw bridge messages.
- Lexical `startsWith()` checks do not prove containment when symlinks or unresolved paths are involved.
- IntelliJ plugins run with the IDE process’s filesystem privileges.

**Warning signs:**

- Bridge installation does not check the current main-frame origin/resource scheme.
- External navigation is allowed in the privileged browser.
- A JVM property can point the browser at an arbitrary URL in production.
- File paths are accepted from the UI rather than derived from validated semantic identifiers and module roots.
- Paths are normalized but not checked against real/canonical roots and existing ancestors.
- Bridge responses are assembled by interpolating strings into executable JavaScript.
- Writes remain enabled when the project is untrusted.

**Prevention:**

- Serve packaged UI through one controlled scheme/host and install privileged handlers only for that exact origin.
- Block or externalize all other navigation; development origins must be loopback-only, opt-in, visibly marked, and incapable of release activation.
- Gate dangerous capabilities on IntelliJ Trusted Project state.
- Define a versioned allowlisted command protocol with request IDs, schema validation, bounded payload/depth/node counts, timeouts, cancellation, and structured errors.
- Serialize response data; never construct executable JavaScript with untrusted string interpolation.
- Derive target paths from a selected module/source root plus validated Java identifiers or restricted filenames.
- Reject absolute paths, separators in identifiers, dot segments, NULs, reserved device names, duplicate targets, and targets outside declared content/source roots.
- Resolve real paths for existing roots/ancestors and explicitly test symlink/junction escapes and creation-time races.
- Make project mutation capability-scoped: a read-only page never receives an apply command.

**Validation gate:**

- Navigation tests cover bundled origin, redirects, `file:`, `jar:`, `data:`, remote HTTP(S), loopback development, subframes, reload, and browser disposal.
- Path tests cover POSIX and Windows separators, absolute/UNC/drive paths, repeated encoding, dot segments, case behavior, Unicode normalization, symlinks/junctions, and a target replaced between validation and write.
- Raw malformed, oversized, recursive, duplicated, timed-out, cancelled, replayed, and unknown bridge requests cannot write.
- Untrusted-project tests prove zero build, process, database, network, or filesystem mutation.

**Phase mapping:** Security boundary in the platform foundation; repeated as a release gate.

**Confidence:** HIGH for trusted-project, JCEF, and path-traversal mechanics; MEDIUM for the exact origin-handler design because JetBrains documents the APIs rather than prescribing a complete JCEF threat model.

---

### Pitfall 4: Textual Regeneration Masquerading as Semantic Round Trip

**What goes wrong:** The plugin reparses an existing entity/view/role/menu into a simplified internal model, drops unknown constructs, then rewrites the whole file. Comments, custom annotations, controller logic, XML extension nodes, formatting, ordering, localized values, and add-on metadata disappear. Two valid independent plans overwrite one another.

**Why it happens:**

- A canonical generator model usually represents less than a long-lived enterprise file.
- Java/Kotlin PSI, XML DOM, properties, Gradle, and Liquibase each need different merge semantics.
- “Generated by us” is mistaken for ownership of an entire file forever.
- Visual state is treated as authoritative after the source has changed.
- Text diffs cannot tell a safe rename from delete-and-recreate or distinguish owned from inherited artifacts.

**Warning signs:**

- Opening and saving without edits changes a file.
- Comments or unknown XML attributes vanish.
- Reordering imports, XML children, or properties dominates the diff.
- Kotlin files are converted to Java-shaped output.
- A stale plan can still apply because only the file path, not its content/semantic anchors, was checked.
- Concurrent plans based on the same snapshot both apply successfully.
- Generated-file markers are the only conflict strategy.

**Prevention:**

- Keep source authoritative and model the workbench as a projection.
- Represent unsupported/unknown nodes explicitly and preserve them in place.
- Edit the smallest stable PSI/DOM/properties node; block when a unique semantic anchor cannot be proven.
- Track artifact and field-level provenance: application-owned, add-on/inherited, tool-generated, manually customized, or unknown.
- Fingerprint both target bytes and relevant semantic dependencies at plan time.
- Reparse and compare immediately before apply. If source changed, recompute or show a three-way semantic conflict; do not auto-rebase.
- Separate new-file templating from existing-file editing.
- Never infer destructive schema changes from a model rename.

**Validation gate:**

- No-op round trips are byte-identical.
- Golden fixtures include comments, unusual annotation order, custom annotations, Kotlin syntax, hand-written methods, namespace extensions, profile properties, duplicate-looking keys, and add-on nodes.
- Supported edits create only the expected minimal diff; unsupported content remains byte-equivalent.
- Two developers/plans edit the same and adjacent semantic elements; overlapping plans block, independent plans revalidate safely.
- Rename/move/refactor and manual edits between preview and apply produce actionable conflicts, never silent regeneration.

**Phase mapping:** Entity/data vertical slice, then view/menu/security vertical slice. Each artifact type needs its own round-trip gate.

**Confidence:** HIGH for PSI/DOM modification mechanics; MEDIUM for merge-policy details, which require fixture validation against target organizations’ conventions.

---

### Pitfall 5: Detecting “A Jmix Project” Instead of Modeling the Actual Build Graph

**What goes wrong:** Root-file regexes select the wrong module, source set, Jmix version, base package, language, data store, or changelog. Output lands in an aggregator, starter module, generated directory, excluded root, or unrelated included build. Private convention plugins and version catalogs hide the information the regex expects.

**Why it happens:**

- Gradle supports multi-project builds, included/composite builds, Kotlin/Groovy DSL, `buildSrc`, convention plugins, version catalogs, custom source sets, and dependency substitution.
- Jmix add-ons normally contain functional and starter modules.
- Jmix composite projects may span one or multiple repositories and separately record data-store metadata.
- Existing solutions often keep application/profile configuration in non-default locations.
- Gradle scripts are executable code, not a reliably regex-parsable declarative format.

**Warning signs:**

- Only root `build.gradle(.kts)` is inspected.
- `src/main/java` and `src/main/resources` are hard-coded.
- Database and Jmix version default silently when detection fails.
- Plugin aliases such as `libs.plugins.*`, private plugins, or BOM constraints are ignored.
- Included builds, custom source sets, Kotlin-only modules, add-on functional/starter pairs, profiles, and `.env` files are absent from fixtures.
- A failed or in-progress Gradle import still enables Apply.

**Prevention:**

- Start from IntelliJ’s imported Project/Workspace Model and `ProjectFileIndex` for module, content-root, and source-root ownership.
- Use the Gradle-imported/Tooling model for project hierarchy, dependencies, included builds, source sets, tasks, and resolved classpath; do not execute Gradle in untrusted projects.
- Build an explicit graph of build → included build → project/module → source set → Jmix application/add-on/starter/functional module → artifact roots → data stores.
- Record detection evidence and confidence for every decision. Ambiguity must require explicit user selection or read-only degradation.
- Treat imported classpath and resolved BOM/plugin versions as stronger evidence than source-text matching.
- Recompute affected topology after Gradle sync/root changes and invalidate plans tied to the old graph.

**Validation gate:**

- Fixture matrix covers Groovy/Kotlin DSL, version catalogs, convention plugins, `buildSrc`, custom roots/source sets, generated sources, multi-project, composite/included builds, separate repositories, private add-ons, mixed Java/Kotlin, and multiple application modules.
- Include upgraded repositories whose build files contain historical/manual structure, not only generated samples.
- For every fixture, assert exact target module, language, source/resource roots, Jmix version, add-on provenance, data stores, and changelog roots.
- Failed/partial sync and unsupported Gradle/JDK combinations remain read-only with no fallback guesses.

**Phase mapping:** Supported-project discovery in the platform foundation. Enterprise topology breadth expands in the enterprise-scale phase, but the read-only fallback is required from day one.

**Confidence:** HIGH — IntelliJ, Gradle, and Jmix officially document the relevant project, composite-build, module, and source-root models.

---

### Pitfall 6: Producing Well-Formed Files That Are Wrong for Jmix or the Database

**What goes wrong:** XML parses but uses the wrong namespace/schema/component contract. Properties edits overwrite comments, ordering, encodings, additional bundles, profiles, or manual values. Liquibase changesets collide, omit a root include, mutate already-executed history, target the wrong data store/dialect, or generate destructive changes for shared/unmapped tables.

**Why it happens:**

- Well-formed XML is weaker than schema- and reference-valid Jmix XML.
- Jmix menus, views, fetch plans, roles, and changelogs form cross-file models with inherited add-on content.
- Properties files can have multiple bundles/locales/profiles and placeholders.
- Liquibase identities include filepath + author + ID, and deployed changesets are checksum-tracked.
- Liquibase `validate` catches changelog structure/XSD/duplicate identifiers/checksums but does not prove generated SQL will succeed.
- Jmix supports main, additional, custom, and REST data stores; additional JPA stores have separate configuration and changelogs.
- Enterprise databases may be shared with other applications, so “unmapped” does not mean “safe to drop.”

**Warning signs:**

- XML is built by string append or emitted as a second root element.
- Namespace/schema URLs and component catalogs are hard-coded globally.
- A properties file is regenerated from a map.
- Existing changesets are edited after deployment.
- Migration IDs begin at `001` for every entity or are unique only within one file.
- Root include-chain, contexts, labels, preconditions, quoting, identifier length, or target data store are not modeled.
- One PostgreSQL fixture is used to claim cross-database support.
- Drop/rename is inferred automatically from a missing/added attribute.

**Prevention:**

- Use XML PSI/DOM with adapter-owned schemas/catalogs and preserve unknown nodes, comments, order, namespace prefixes, and inherited provenance.
- Use Properties PSI/resource-bundle semantics; preserve encoding, separators, comments, ordering, and user values. Add missing keys rather than rewriting bundles.
- Parse the complete Liquibase include graph and allocate identifiers against the whole graph.
- Never edit an executed changeset by default. Add a new changeset; treat checksum changes as release blockers unless explicitly reviewed as a controlled exception.
- Structurally update the correct root changelog and verify referenced files.
- Bind every entity/migration to an explicit data store and dialect capability; do not default ambiguous stores.
- Require explicit intent, impact explanation, and suitable preconditions for destructive changes. Never infer rename/drop from a model diff.
- Keep database execution separate from source apply. Read-only introspection is the default.

**Validation gate:**

- Parse and schema/reference-validate every planned and post-apply XML/properties artifact.
- Run Liquibase `validate` across the complete include graph; assert unique filepath/author/ID combinations and unchanged checksums for historical changesets.
- Inspect `update-sql`, then update and rollback disposable databases for each claimed dialect. Acknowledge that `validate` and `update-sql` alone do not prove SQL execution correctness.
- Test Jmix application startup/compile with main and multiple additional stores, profile-specific properties, multiple supported DB types, shared-table exclusions, and add-on-provided changelogs.
- No-op/unsupported edits preserve exact manual content.

**Phase mapping:** Entity/Liquibase vertical slice first; views, menus, fetch plans, roles, and localization repeat the structured-artifact gate in the UI vertical slice.

**Confidence:** HIGH — IntelliJ XML/PSI, Liquibase identity/checksum/validation behavior, and Jmix data-store/changelog workflows are officially documented.

---

### Pitfall 7: Treating “2.8 and 3.x” as Two Static Template Switches

**What goes wrong:** A universal generator scatters version checks and emits APIs, XML, Gradle syntax, component attributes, security configuration, or add-on contracts from the wrong platform line. A later Jmix/IntelliJ patch silently breaks a previously green compatibility claim.

**Why it happens:**

- Jmix 2.8 is an LTS line, while 3.x continues a feature-release cadence.
- Jmix 3.0 moved its minimum IDE to 2025.3, Java to 21/25, Gradle wrapper to 9.5.1, and introduced substantial Spring Boot/Vaadin/EclipseLink/source/XML/security changes.
- Jmix 2.8 itself added new source/XML concepts such as element collections, facets in fragments, additional message bundles, and `.env` support.
- Add-ons have their own versions and may contribute entities, components, messages, roles, migrations, and runtime assumptions.
- IntelliJ APIs and required Java/Kotlin/plugin dependencies change across IDE releases; 2024.2+ requires IntelliJ Platform Gradle Plugin 2.x, and 2026.x contains additional changes.

**Warning signs:**

- One `if (majorVersion >= 3)` branch controls all generation.
- Component/annotation catalogs are hard-coded in the plugin rather than resolved from the target classpath and adapter.
- “3.x compatible” is claimed after testing one 3.0 sample.
- Plugin Verifier is run only against the compile target.
- Unsupported or unknown versions silently select the nearest adapter.
- Add-on presence changes the UI but not validation fixtures.

**Prevention:**

- Define a stable internal semantic model and isolated capability adapters for exact supported ranges.
- Resolve actual Jmix BOM/Gradle plugin/add-on versions and classpath capabilities from the imported build.
- Make capabilities explicit: supported, read-only, unsupported-with-reason, or experimental.
- Keep build-script/project migration as a separate audited workflow; it must never be a side effect of a designer edit.
- Test IDE and Jmix lines independently; use separate plugin artifacts if one binary cannot safely span the supported IDE/JCEF/Kotlin range.
- Monitor Jmix release/migration notes and IntelliJ incompatible API changes before each release.

**Validation gate:**

- Compile, validate, and start representative fixtures for every certified adapter tuple and supported IDE build.
- Run Plugin Verifier against every published IDE target and full-product integration tests against installed plugin ZIPs.
- Add-on-heavy fixtures verify inherited metadata and extension components, not just base Jmix.
- A 3.x-only request in 2.8, or any unknown patch/fork, yields read-only behavior and a precise capability explanation.
- New Jmix or IDE release support is opt-in only after matrix gates pass; it is never enabled by a permissive version range alone.

**Phase mapping:** Version-adapter foundation and every later feature; compatibility is an ongoing release process, not a completed phase.

**Confidence:** HIGH — Jmix release/support policies and IntelliJ compatibility guidance are primary-source documented.

---

### Pitfall 8: Scaling by Full Rescan, Full Refresh, and Full Rerender

**What goes wrong:** Opening or changing a large composite project causes long indexing, repeated Gradle queries, high memory usage, UI freezes, huge bridge messages, and stale callbacks. The plugin becomes unusable precisely in the repositories enterprise users care about.

**Why it happens:**

- Full PSI/AST materialization and UAST conversion are expensive.
- Recursive VFS refresh cost grows with the project rather than the change.
- Long read actions block pending writes and user typing.
- VFS/PSI listeners can receive massive event batches.
- Nested React state causes whole-tree copies/renders.
- JCEF clients, queries, listeners, documents, caches, or PSI references can outlive the tool window/project if not disposed.

**Warning signs:**

- Recursive project scans on startup or every request.
- `refresh(true, true)` on the project root.
- Whole-file UAST conversion or AST loading merely to collect declarations.
- Index/resolve work in `AnAction.update()` or on EDT.
- Unbounded bridge payloads, queues, node depth, logs, or cached models.
- Reopening the tool window increases listeners, memory, or response count.
- Performance is demonstrated only on generated toy projects.

**Prevention:**

- Use existing indexes and targeted PSI traversal; add custom stubs/indexes only when justified by measured query patterns.
- Consider file/PSI gists for lazy per-file facts that do not require project-wide aggregation.
- Use cancellable read actions, cancellation checkpoints, smart-mode coordination, and debounced/merged background updates.
- Pre-filter by module/source root/file type before parsing; avoid whole-file UAST conversion.
- Update a dependency-aware semantic index incrementally.
- Refresh exact paths only; batch VFS events and invalidate caches cheaply.
- Normalize/virtualize large UI trees and page bridge results; cap payload, node count, recursion depth, generated size, and pending requests.
- Register JCEF, listeners, queues, and caches under the shortest correct `Disposable`.

**Validation gate:**

- Define measured budgets for startup, initial index, incremental edit, plan generation, apply/write-lock time, UI responsiveness, heap growth, and bridge payload on representative small, enterprise, and stress corpora.
- Performance tests run cold/warm, with/without shared indexes, during indexing, after Gradle sync, and across repeated open/close/reload cycles.
- Capture UI-freeze reports and leak/disposer failures as release blockers.
- A one-file edit must not trigger a project-wide rescan or recursive root refresh.

**Phase mapping:** Baseline instrumentation in the platform foundation; hard enterprise budgets and large-corpus gates in the enterprise-scale phase.

**Confidence:** HIGH for the failure mechanisms and IntelliJ guidance; LOW for numeric budgets until target repositories are profiled.

---

### Pitfall 9: A Test Suite That Proves Generators but Not the Installed Product

**What goes wrong:** Pure generator and snapshot tests pass while the plugin fails to load, JCEF resources are stale/missing, bridge DTOs do not deserialize, optional plugins are absent, dumb mode breaks actions, generated fixtures do not compile, or Liquibase fails against a real database.

**Why it happens:**

- Frontend development simulation can return success without executing Kotlin.
- Plugin Verifier checks binary compatibility, not business behavior.
- Liquibase `validate` does not validate database execution.
- Golden strings can bless consistently invalid output.
- Light fixtures do not expose all module/import/classloader/JCEF behavior.
- New-project fixtures omit historical customizations and merge hazards.

**Warning signs:**

- `npm run build` is treated as product validation.
- No test installs the built ZIP into a full IDE.
- Tests use only in-memory models, not parsed existing artifacts.
- No failure injection, Undo, read-only file, stale plan, or path traversal coverage.
- Compatibility claims exceed the fixture matrix.
- Mandatory suites are routinely skipped due to flakiness.

**Prevention:**

- Pure unit/golden tests: adapters, model conversion, merge algorithms, path policy, bridge schema, Java/XML/properties/Liquibase generation.
- IntelliJ light tests: PSI reads/writes, references, formatting, smart pointers, structure consistency, inspections, Undo.
- Heavy/full-product tests: roots, Gradle imports, optional dependencies, classpaths, project lifecycle, dumb mode, plugin unload.
- Installed-ZIP integration tests: plugin metadata, bundled web assets, JCEF load/bridge/navigation, actions, one complete user story.
- Generated-project tests: compile/test/start representative Jmix 2.8 and 3.x fixtures, Java/Kotlin, multi-module/composite, add-on-heavy, multiple data stores.
- Database tests: validate, inspect SQL, update, rollback, and repeat against every claimed dialect.
- Adversarial tests: malformed/oversized bridge data, path/symlink escapes, stale plans, concurrent edits, partial failure, untrusted projects.
- Clean-checkout release tests must build the exact artifact later signed/published.

**Validation gate:**

- Each compatibility claim maps to named, passing fixture and integration scenarios.
- Parser/generator output is independently parsed/compiled; snapshots are not the sole oracle.
- The same headless validation rules and diagnostics run in IDE and CI.
- Zero mandatory test skips/flakes at release; quarantine removes a claim rather than weakening the gate.

**Phase mapping:** Test laboratory begins in the platform foundation and grows with every feature. No phase is complete without its cross-layer scenario.

**Confidence:** HIGH — JetBrains recommends light/heavy tests, full-product integration tests for user stories, and Plugin Verifier as a separate compatibility check.

---

### Pitfall 10: Shipping an Unsigned or Non-Reproducible Dependency Bundle

**What goes wrong:** A release packages stale `webui/dist`, unreviewed transitive dependencies, a compromised Gradle/npm artifact, or a different ZIP from the one tested. Enterprise users cannot verify provenance, inventory components, or trust a private update channel.

**Why it happens:**

- npm and Gradle each resolve large transitive graphs.
- Lockfiles fix versions but do not alone authenticate artifact contents.
- Local ignored build output can mask missing build dependencies.
- Signing keys/publishing tokens are easy to mishandle in CI.
- Marketplace and private repositories have different signing behavior.

**Warning signs:**

- The plugin build copies an existing frontend directory instead of depending on a clean frontend build.
- Dynamic/SNAPSHOT dependencies, mutable actions, or unpinned build tools exist in release CI.
- Gradle wrapper lacks a distribution checksum or dependency verification metadata.
- No SBOM, license inventory, vulnerability gate, provenance, checksums, or signature-verification step exists.
- Signing occurs on a developer workstation or before the final artifact is known.

**Prevention:**

- Build from a clean, pinned environment using the complete Gradle wrapper and `npm ci`.
- Make frontend compilation a declared input/dependency of the plugin artifact; verify bundled asset hashes.
- Pin dependencies and CI actions; enable Gradle dependency locking plus checksum/signature verification and wrapper distribution checksum.
- Restrict repositories and review all dependency-verification metadata changes independently.
- Generate a CycloneDX/SPDX SBOM including plugin and bundled frontend dependencies; run license and vulnerability policy.
- Produce build provenance tied to source revision, builder, inputs, and artifact digest.
- Sign the final tested plugin ZIP, then run `verifyPluginSignature`; publish that exact digest.
- For private channels, document the trusted CA/key rollout and rotation process.
- Keep signing keys in protected CI/KMS-backed secrets with least privilege, approval, and audit.

**Validation gate:**

- A clean release job rebuilds, verifies dependency integrity, runs all gates, emits SBOM/provenance/checksums, signs, verifies, and publishes one immutable digest.
- A second clean build compares dependency graph, SBOM, inputs, and artifact contents; any unavoidable non-reproducible metadata is documented and minimized.
- Installation tests verify the signed artifact from the intended Marketplace/private channel.
- Dependency or build-tool upgrades require reviewed lock/verification/SBOM diffs.

**Phase mapping:** Product/release foundation, then every public/private release.

**Confidence:** HIGH for Gradle and JetBrains signing/integrity mechanics; MEDIUM for the selected SBOM/provenance policy.

---

### Pitfall 11: Violating the Clean-Room Boundary or Creating Brand Confusion

**What goes wrong:** The project copies or derives proprietary Jmix Studio code, templates, icons, protocol details, or visual identity; contributors use decompiled material; the product name implies an official Studio replacement or affiliation. Publication creates copyright, license, trademark, Marketplace, and customer-procurement risk.

**Why it happens:**

- Jmix Framework is Apache-2.0 open source, but Jmix Studio and commercial add-ons are separately licensed.
- The Jmix commercial license prohibits adapting, modifying, decompiling, disassembling, or reverse engineering the Software except as permitted by law/license.
- “Clone” naming and copied workflow visuals invite implementation-by-imitation.
- The Jmix name and logo are stated trademarks of Haulmont.
- Generated templates/assets may be mistaken for public specifications.

**Warning signs:**

- Repository/product/package names retain `jmixstudio` or “clone.”
- UI, icons, text, templates, or file output are justified only by observing/decompiling the proprietary plugin.
- No source/provenance record exists for compatibility behavior.
- Contributors cannot attest that their changes avoid proprietary implementation material.
- README claims affiliation, endorsement, licensing parity, or commercial runtime inclusion.

**Prevention:**

- Rename the product and packages; use an original identity, icons, layout, wording, and interaction design.
- Add a prominent factual compatibility/trademark disclaimer and avoid Jmix marks in a source-identifying way that suggests ownership or endorsement.
- Use only public Jmix documentation, public schemas/specifications, and correctly licensed open-source framework code as compatibility inputs.
- Maintain a research/provenance ledger linking each compatibility rule or template decision to an allowed public source.
- Adopt contributor clean-room rules, license/CLA or DCO policy, asset provenance requirements, and a process to quarantine potentially tainted contributions.
- Do not implement entitlement bypass, redistribute Studio/add-on code, or claim that generated BPMN supplies a commercial runtime.
- Obtain qualified legal review before public branding/distribution. This research is not legal advice.

**Validation gate:**

- Release has a chosen license, NOTICE/third-party inventory, trademark disclaimer, contribution policy, clean-room policy, and documented source provenance.
- Brand/asset/source scan finds no proprietary names, copied icons, templates, screenshots, or unexplained large similarities.
- A human legal/brand review approves the public name, Marketplace listing, compatibility claims, and screenshots.

**Phase mapping:** Product/legal reset before implementation expansion; contribution and release review continue permanently.

**Confidence:** HIGH for the license/trademark facts from Jmix’s own terms; MEDIUM for process sufficiency pending counsel.

---

### Pitfall 12: Team-Scale Automation Without Shared Ownership and Policy

**What goes wrong:** Different developers/plugin versions generate different output, migrations collide, inherited artifacts are modified as though locally owned, policy exceptions are invisible, and visual plans cannot be reproduced in CI or reviewed meaningfully in pull requests.

**Why it happens:**

- Local IDE automation is easy to treat as a personal tool.
- Timestamps, locale, filesystem ordering, environment variables, and plugin/add-on resolution create nondeterminism.
- An enterprise repository has multiple owners for entities, schemas, security, UI, localization, and build files.
- Migration/change IDs are a coordination resource, not just a formatting concern.
- Plan exports and diagnostics can leak source, filesystem, connection, or secret data.

**Warning signs:**

- The same intent produces different diffs on two workstations.
- No tool/adapter version, target fingerprint, ownership, or policy decision is recorded.
- Overrides are free-form and unaudited.
- IDE validation differs from CI.
- Generated output ownership is inferred from a header rather than repository policy and provenance.
- Teams resolve frequent generator-created merge conflicts manually.

**Prevention:**

- Store a schema-versioned, reviewable project policy defining supported adapters, naming, target roots, ownership, destructive-operation rules, dialects, and validation severity.
- Make plans deterministic: stable ordering, normalized locale/time zone, explicit IDs/inputs, no environment-dependent templates, and recorded tool/adapter versions.
- Export a redacted plan manifest with source fingerprints, semantic operations, target ownership, diagnostics, policy decisions, and result digest.
- Run the same planner/validators headlessly in CI; never require developers to trust an opaque IDE-only result.
- Require explicit reason/approver for policy overrides and destructive changes.
- Define file/artifact ownership and code-review routes; add-on/inherited artifacts are read-only unless the owning module is selected and certified.
- Coordinate Liquibase IDs and include ordering at repository scope.
- Provide controlled release channels, rollback guidance, compatibility notices, and audit retention without collecting source/credentials by default.

**Validation gate:**

- Same source + policy + tool version + intent yields byte-identical plan and output across clean machines.
- IDE and headless validation produce equivalent findings and severity.
- Multi-developer concurrency tests prove collision detection for changesets and semantic edits.
- Plan/audit exports pass secret/privacy redaction tests.
- Pilot teams can identify who owns every planned operation and reproduce the result from the manifest.

**Phase mapping:** Determinism and plan metadata start with the change engine; policy packs, audit, and rollout controls mature in the enterprise-scale phase.

**Confidence:** MEDIUM — controls are established enterprise engineering practice but must be tailored through target-team pilots.

## Moderate Pitfalls

### Pitfall 13: Assuming Optional IDE Capabilities Exist Everywhere

**What goes wrong:** Kotlin, Gradle, Database Tools, Spring, or JCEF APIs are used without correct declared/optional dependencies. The plugin fails to load or presents features that cannot operate in the installed edition/runtime.

**Prevention:**

- Declare required platform modules/plugins explicitly.
- Make Kotlin/Database/Spring integrations optional only when a tested fallback exists.
- Check JCEF support before constructing the browser and provide a useful native/read-only fallback.
- Publish an edition/capability matrix; do not imply database tooling exists in every product.

**Validation gate:** Install the ZIP into every supported IDE edition with each optional dependency present/absent; plugin load, feature visibility, and degradation must match the matrix.

**Phase mapping:** Platform foundation and release matrix.

### Pitfall 14: Letting Diagnostics, Plans, or Telemetry Exfiltrate Enterprise Metadata

**What goes wrong:** Logs, crash reports, plan exports, or future telemetry include source snippets, absolute paths, repository names, database URLs/users, environment placeholders, or proprietary model structure.

**Prevention:**

- Local-first by default; no telemetry or remote upload without explicit opt-in and documented inventory.
- Structured error codes with redacted arguments; source inclusion is an explicit user-reviewed attachment.
- Never log raw bridge payloads, generated source, credentials, or resolved secrets.
- Scrub absolute paths and connection data from exported plans and support bundles.

**Validation gate:** Automated secret/canary tests over logs, exceptions, plan exports, crash bundles, and analytics events; offline operation remains fully functional.

**Phase mapping:** Security foundation; enterprise operations.

### Pitfall 15: Mixing Generated Source Ownership With Runtime or Database Execution

**What goes wrong:** A visual edit unexpectedly runs Gradle, connects to a database, applies Liquibase, recreates a store, starts Vaadin, or invokes external tools. Users cannot distinguish source planning from environment mutation.

**Prevention:**

- Separate read/index, source plan/apply, build validation, database introspection, and database execution into distinct capability and consent levels.
- Opening a project/designer is always side-effect free.
- External process/database operations show exact command/target, trust state, credential source, cancellation, and logs.
- Database introspection is read-only by default; source apply never auto-runs migrations.

**Validation gate:** Capability tests prove that each workflow touches only declared systems; network/database/process canaries remain untouched during open/index/plan/source-apply.

**Phase mapping:** Platform foundation; database/runtime integrations only in later independently researched phases.

## Minor Pitfalls

### Pitfall 16: Accessibility and Keyboard-Only Failure in a Canvas-Heavy UI

**What goes wrong:** Drag-and-drop designers are unusable for keyboard, screen-reader, high-contrast, or zoom users, blocking enterprise adoption.

**Prevention:** Every canvas operation needs a keyboard/list alternative, semantic labels, visible focus, IntelliJ theme/high-contrast behavior, and stable zoom/scaling.

**Validation gate:** Automated accessibility checks plus manual keyboard/screen-reader/high-contrast audit on supported operating systems.

**Phase mapping:** UI vertical slice; not a post-release polish phase.

### Pitfall 17: Documentation and Compatibility Claims Drifting From the Artifact

**What goes wrong:** README/Marketplace text says “supports Jmix 3.x” while the shipped adapter/fixtures cover fewer versions or a known operation is read-only.

**Prevention:** Generate public capability/compatibility tables from the same versioned registry used by the plugin and CI. Link every claim to a passing release gate.

**Validation gate:** Release fails when documentation claims lack a current certified matrix entry.

**Phase mapping:** Release foundation and every release.

## Cross-Phase Validation Gates

| Gate | Required outcome | Blocks |
|---|---|---|
| G0 — Clean-room identity | Original name/assets, license, disclaimer, source provenance, contribution rules, legal/brand review | Any public distribution |
| G1 — Read-only safety | Open/index/detect all certified and unsupported fixtures with zero filesystem/build/database mutation | Any designer work |
| G2 — Platform lifecycle | Dumb mode, cancellation, PSI validity, Gradle/root changes, disposal, and optional dependencies behave safely | Semantic indexing |
| G3 — Trust and containment | Trusted-origin bridge, project-trust gating, payload limits, canonical root containment, symlink/race tests | Any file mutation |
| G4 — Plan transaction | Exact diff, writable/stale checks, fault rollback, one-step Undo/Redo, no unrelated changes | First mutating vertical slice |
| G5 — Artifact correctness | PSI/XML/properties parse/round-trip; Liquibase include/identity/checksum/DB matrix; fixture compile/start | Feature completion |
| G6 — Compatibility | Exact IDE/Jmix/add-on/language/topology tuples pass Plugin Verifier and full installed-ZIP scenarios | Compatibility claim |
| G7 — Enterprise scale | Representative large/customized repositories meet measured latency, write-lock, memory, leak, and payload budgets | Enterprise pilot |
| G8 — Release integrity | Clean build, locked/verified dependencies, SBOM, provenance, checksums, signed-and-verified immutable ZIP | Release/publish |
| G9 — Team governance | Deterministic IDE/CI plans, ownership/policy enforcement, audit redaction, concurrent-change collision tests | Multi-team rollout |

## Phase-Specific Warnings

| Phase topic | Likely pitfall | Required mitigation/gate |
|---|---|---|
| Product/legal reset | Proprietary implementation or brand confusion survives under a new feature set | G0 before packages/UI/assets/templates expand |
| Build/platform foundation | Green compile hides wrong IDE modules, unsigned output, or stale frontend bundle | G6 basic plugin load plus G8 build integrity |
| Project discovery/index | Root-regex detection and dumb-mode fallback misclassify complex estates | G1/G2 across long-lived, mixed-language, composite, add-on-heavy fixtures |
| Typed JCEF bridge | Loaded page gets ambient file authority | G3 origin/trust/protocol/path adversarial suite |
| Change plan/apply | One write command is mistaken for atomicity | G4 exhaustive fault injection and exact Undo |
| Entity/DTO/enum round trip | Simplified model erases manual Java/Kotlin constructs | G5 no-op byte identity and minimal semantic diff |
| Liquibase/data stores | Wrong store/dialect, historical checksum edit, shared-table drop | G5 full include graph plus disposable DB matrix; destructive intent is explicit |
| View/fetch/menu/localization | Unknown/add-on XML and manual bundles are flattened | Per-artifact G5 provenance-aware round trips |
| Roles/security | Inherited/default-deny semantics are misrepresented | Compile and Jmix integration tests for effective policies |
| Developer intelligence | Index/resolve work freezes EDT or fails in dumb mode | G2 plus measured action/update latency |
| Enterprise scale | Small fixtures conceal rescan, leak, and nondeterminism | G7/G9 on representative target repositories |
| Every release | New Jmix/IDE patch silently expands a permissive support range | G6 exact tuple certification; unknown variants remain read-only |

## “What Might Be Missed?” Review

- **Remote Development / split mode:** Plugin/frontend/backend placement and filesystem authority may differ. Research this before claiming remote support.
- **IntelliJ 2026.2+ JCEF/runtime changes:** Verify explicit module dependencies and Java runtime requirements for each target build rather than assuming 2025.3 behavior.
- **Kotlin K2/Analysis API drift:** Mixed-language mutation needs its own adapter and fixture matrix, not only UAST discovery.
- **Non-Git VCS/read-only workflows:** IntelliJ writable checks are required even if Git-based fixture tests pass.
- **Network filesystems, case-insensitive filesystems, Windows junctions, and very long paths:** Add platform-specific containment and atomicity tests.
- **Private add-ons with proprietary schemas/templates:** Default to read-only unless the organization supplies an authorized adapter/extension and fixtures.
- **Custom XML component namespaces and custom data stores:** Preserve and navigate unknown constructs; never round-trip them through a lossy base model.
- **Liquibase formatted SQL/YAML/JSON:** V1 should explicitly state whether these are read-only; XML support does not imply support for all changelog formats.
- **Database credential handling:** Defer until a threat model covers IntelliJ credential storage, profiles, placeholders, SSH/cloud drivers, and logging.
- **Generated-source roots:** Editing generated output may be overwritten by the owning generator; discover provenance and modify the true input or remain read-only.
- **Schema-per-tenant/multitenancy and cross-store references:** Require phase-specific Jmix integration tests before mutation support.
- **File-watch races from external generators/builds:** Revalidate fingerprints after external processes and before Apply.

## Sources

### IntelliJ Platform / JetBrains (official, HIGH confidence)

- [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [Indexing and PSI Stubs / Dumb Mode](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)
- [PSI Files and lifecycle](https://plugins.jetbrains.com/docs/intellij/psi-files.html)
- [Modifying the PSI](https://plugins.jetbrains.com/docs/intellij/modifying-psi.html)
- [Documents, commands, writable checks, and Undo](https://plugins.jetbrains.com/docs/intellij/documents.html)
- [UAST — read-only API and Java/Kotlin caveats](https://plugins.jetbrains.com/docs/intellij/uast.html)
- [Virtual File System](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html)
- [Virtual Files and delayed persistence](https://plugins.jetbrains.com/docs/intellij/virtual-file.html)
- [Project Model](https://plugins.jetbrains.com/docs/intellij/project-model.html)
- [Workspace Model](https://plugins.jetbrains.com/docs/intellij/workspace-model.html)
- [Module/content/source roots](https://plugins.jetbrains.com/docs/intellij/module.html)
- [XML DOM API](https://plugins.jetbrains.com/docs/intellij/xml-dom-api.html)
- [Embedded Browser / JCEF](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html)
- [Trusted Projects](https://plugins.jetbrains.com/docs/intellij/trusted-projects.html)
- [Action System threading/performance](https://plugins.jetbrains.com/docs/intellij/action-system.html)
- [Plugin dependencies and optional integrations](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html)
- [IntelliJ IDEA Java/Kotlin plugin APIs and K2 guidance](https://plugins.jetbrains.com/docs/intellij/idea.html)
- [PSI Performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html)
- [Disposer and Disposable](https://plugins.jetbrains.com/docs/intellij/disposers.html)
- [Integration Tests](https://plugins.jetbrains.com/docs/intellij/integration-tests.html)
- [Light and Heavy Tests](https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html)
- [Verifying Plugin Compatibility / Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
- [Incompatible IntelliJ API Changes](https://plugins.jetbrains.com/docs/intellij/api-changes-list.html)
- [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)

### Jmix (official/primary, HIGH confidence)

- [Jmix 2.8 What’s New and upgrade baseline](https://docs.jmix.io/jmix/2.8/whats-new/index.html)
- [Jmix 2.8 release announcement and LTS statement](https://www.jmix.io/blog/jmix-2-8-is-released/)
- [Jmix version support policy](https://www.jmix.io/framework/versioning/)
- [Jmix 3.0 release, upgrade procedure, and breaking changes](https://docs.jmix.io/jmix/whats-new/release-3.0.html)
- [Jmix Composite Projects](https://docs.jmix.io/3.x/jmix/studio/composite-projects.html)
- [Jmix add-on functional/starter module structure](https://docs.jmix.io/jmix/modularity/creating-add-ons.html)
- [Jmix data stores and cross-store model](https://docs.jmix.io/3.x/jmix/data-model/data-stores.html)
- [Jmix Studio data-store and Liquibase workflows](https://docs.jmix.io/3.x/jmix/studio/data-stores.html)
- [Jmix migration from older versions](https://docs.jmix.io/2.x/jmix/2.8/migration-from-older-versions.html)
- [Jmix Studio feature catalog 2.8](https://docs.jmix.io/jmix/2.8/studio/studio-features.html)
- [Jmix Studio feature catalog 3.x](https://docs.jmix.io/jmix/studio/studio-features.html)
- [Jmix Studio and Add-ons Software License Agreement](https://www.jmix.io/commercial-software-license/)
- [Jmix Terms of Use / trademark statement](https://www.jmix.io/terms-of-use/)

### Gradle (official, HIGH confidence)

- [Tooling API and IDE/project models](https://docs.gradle.org/current/userguide/tooling_api.html)
- [Multi-project build structure](https://docs.gradle.org/current/userguide/intro_multi_project_builds.html)
- [Composite builds / included builds](https://docs.gradle.org/current/userguide/composite_builds.html)
- [Version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html)
- [Dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)
- [Dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
- [Gradle build security and wrapper checksum guidance](https://docs.gradle.org/current/userguide/best_practices_security.html)

### Liquibase (official, HIGH confidence)

- [Validate command and its limits](https://docs.liquibase.com/community/reference-guide-5-1/database-inspection-change-tracking-and-utility-commands/validate)
- [Update SQL inspection and its limits](https://docs.liquibase.com/secure/reference-guide-5-2-1/init-update-and-rollback-commands/update-sql)
- [Changeset checksum behavior](https://docs.liquibase.com/community/user-guide-5-0-3/what-is-a-changeset-checksum)
- [Duplicate changeset identifiers](https://docs.liquibase.com/secure/reference-guide-5-1-1/parameters/allow-duplicated-changeset-identifiers)
- [Preconditions for destructive/assumption-sensitive changes](https://docs.liquibase.com/community/user-guide-5-0-2/what-are-preconditions)

### Security and supply-chain standards (primary, HIGH/MEDIUM confidence)

- [CWE-22 Path Traversal](https://cwe.mitre.org/data/definitions/22.html)
- [OWASP Input Validation Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html)
- [CycloneDX official specification](https://github.com/CycloneDX/specification)
- [SLSA v1.2 specification and provenance](https://slsa.dev/spec/v1.2/)

### Project-local evidence

- `.planning/PROJECT.md`
- `JMIX_STUDIO_ASSESSMENT.md`
- `.planning/codebase/ARCHITECTURE.md`
- `.planning/codebase/CONCERNS.md`
- `.planning/codebase/CONVENTIONS.md`
- `.planning/codebase/INTEGRATIONS.md`
- `.planning/codebase/STACK.md`
- `.planning/codebase/STRUCTURE.md`
- `.planning/codebase/TESTING.md`
- `.planning/research/FEATURES.md`


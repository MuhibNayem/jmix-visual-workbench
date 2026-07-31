# Codebase Concerns

**Analysis Date:** 2026-07-27

## Tech Debt

**The Kotlin plugin is not buildable from the checked-in source:**
- Issue: `ViewXmlGenerator.generateComponentTree()` calls `generateDataGridContent(...)`, but the only declared function is `generateDataGridContents(...)`. The declared function also calls `child(...)` without using its `parent` receiver, producing additional unresolved references.
- Files: `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt`
- Impact: The plugin cannot compile, so none of the installed-plugin workflows described in `README.md` are currently deliverable. A direct JDK 17 `kotlinc` compilation of `plugin/src/main/kotlin/com/jmixstudio/model/*.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/*.kt` fails at these references.
- Fix approach: Rename the call or declaration consistently, wrap the column generation in `parent.child(...)`, then make `compileKotlin` a required CI check before addressing downstream generator defects.

**The generic Java source builder accepts syntax fragments as import names:**
- Issue: `extends_()` and `implements_()` always add their argument to the import set, even when the argument is a simple name or a parameterized type such as `StandardListView<Customer>` or `JpaRepository<Customer, UUID>`. It also defines `typeImport` and `returnTypeImport` without consuming them and does not collect imports from parameter annotations.
- Files: `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/DataRepositoryGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EventListenerGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`
- Impact: Multiple generators emit illegal imports such as `import StandardListView<Customer>;`, `import JpaRepository<Customer, UUID>;`, or `import StandardEntity;`. Other generated code omits required component and parameter-annotation imports.
- Fix approach: Represent Java types structurally, keep fully qualified raw import names separate from rendered generic type expressions, reject imports without a package, and compile every generated Java fixture in tests.

**Frontend/backend protocol types are duplicated manually:**
- Issue: TypeScript string unions mirror Kotlin enums, but several Kotlin enums have no `@SerializedName` mapping and the CRUD option values use different casing/naming from Kotlin constants.
- Files: `webui/src/types/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`
- Impact: The TypeScript build passes while valid UI payloads deserialize to null Kotlin enum values or cannot deserialize at all. Compile-time checking does not cross the JCEF boundary.
- Fix approach: Define one versioned wire schema, generate TypeScript/Kotlin DTOs from it, add explicit discriminator fields and enum mappings, and validate payloads before invoking generators.

**Large monolithic modules concentrate unrelated behavior:**
- Issue: Designer state, recursive tree algorithms, validation, bridge calls, and rendering are combined in single components; generator files similarly combine model translation, syntax decisions, and output assembly.
- Files: `webui/src/components/ViewDesigner/ViewDesigner.tsx` (1,022 lines), `webui/src/components/MigrationPanel/MigrationPanel.tsx` (716 lines), `webui/src/components/EntityDesigner/EntityDesigner.tsx` (619 lines), `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt` (774 lines), `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt` (578 lines)
- Impact: Protocol and generator changes require editing broad files with no focused tests, increasing regression risk and making review difficult.
- Fix approach: Extract wire DTO adapters, validators, pure tree reducers, per-artifact generators, and reusable editor controls into independently tested modules.

**The repository does not contain a usable Gradle wrapper:**
- Issue: `plugin/gradle/wrapper/gradle-wrapper.properties` exists, but `plugin/gradlew`, `plugin/gradlew.bat`, and `plugin/gradle/wrapper/gradle-wrapper.jar` are absent.
- Files: `plugin/gradle/wrapper/gradle-wrapper.properties`, `README.md`, `plugin/build.gradle.kts`
- Impact: The documented `./gradlew buildPlugin` and `./gradlew runIde` commands fail immediately. Builds depend on an externally installed Gradle version and machine state.
- Fix approach: Generate and commit a complete wrapper using the version declared by `plugin/gradle/wrapper/gradle-wrapper.properties`, then verify the exact README commands in CI.

**Generated artifacts and local dependency trees are present in the working tree:**
- Issue: `webui/node_modules/`, `webui/dist/`, `plugin/.gradle/`, and `plugin/build/` exist locally while `.gitignore` excludes them.
- Files: `.gitignore`, `webui/dist/index.html`, `plugin/build/reports/problems/problems-report.html`
- Impact: Local artifacts can conceal missing clean-build steps or stale bundled UI behavior. The current plugin build directory contains reports but no compiled classes or distribution ZIP.
- Fix approach: Validate from a clean checkout in CI and package only from freshly built inputs; do not treat existing `webui/dist/` or `plugin/build/` contents as release evidence.

## Known Bugs

**View generation payloads cannot be deserialized reliably:**
- Symptoms: The UI sends component values such as `vbox`, `textField`, `instance`, and facet/action wire names. `ComponentType`, `DataContainerType`, `FacetType`, and `ActionType` use uppercase Kotlin constant names and have no `@SerializedName` annotations.
- Files: `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/types/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/model/ViewModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Trigger: Click “Generate View” with the default `vbox` root or any normal component/data container.
- Workaround: None in the UI. Add explicit wire mappings or a custom adapter before generation.

**CRUD generation receives null enum options from the default UI:**
- Symptoms: The UI sends `dataGrid`, `form`, and `postgres`, while Kotlin expects `DATA_GRID`, `FORM`, and `POSTGRES` and provides no serialized aliases on these enums.
- Files: `webui/src/components/CrudWizard/CrudWizard.tsx`, `webui/src/types/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Trigger: Generate a CRUD stack with the default wizard options.
- Workaround: None exposed. Map the wire strings explicitly before constructing `CrudOptions`.

**Manual migration generation cannot deserialize its change list:**
- Symptoms: The UI sends objects with a `changeType` discriminator and shapes such as `{columnName, columnType}`, but Kotlin declares `MutableList<DbChange>` where `DbChange` is an abstract sealed class and registers no Gson polymorphic type adapter. Several field shapes also differ (`addForeignKey` versus `AddForeignKeyConstraint`, string columns versus `ColumnValueDef` lists).
- Files: `webui/src/components/MigrationPanel/MigrationPanel.tsx`, `webui/src/types/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Trigger: Add any migration change and click “Generate Migration.”
- Workaround: None. Introduce a discriminator-aware adapter and wire DTO-to-domain conversion.

**Menu Designer invokes an action the backend does not implement:**
- Symptoms: The UI requests `generateMenu`; the bridge returns `{"error":"Unknown action: generateMenu"}` because the action is absent from its dispatch table.
- Files: `webui/src/components/MenuDesigner/MenuDesigner.tsx`, `webui/src/bridge/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MenuGenerator.kt`
- Trigger: Click “Generate Menu.”
- Workaround: Generate a menu indirectly through CRUD, which has separate destructive behavior described below.

**Generated view XML contains invalid namespace declarations:**
- Symptoms: `ViewXmlGenerator` calls both `ns("", ...)` and `attr("xmlns", ...)`; `XmlBuilder` renders an empty prefix as `xmlns:=...`. The data namespace is also emitted twice.
- Files: `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`
- Trigger: Generate any view or fragment after fixing the current Kotlin compile blocker.
- Workaround: None. Make the namespace builder render the default namespace as `xmlns`, deduplicate namespace declarations, and parse generated XML in tests.

**Generated view controllers contain invalid imports and missing component imports:**
- Symptoms: Base classes are passed to `extends_()` as simple or generic strings, producing illegal imports. Resolved component types such as `DataGrid`, `TypedTextField`, and `JmixButton` are emitted without importing their packages.
- Files: `plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`
- Trigger: Generate a standard, list, or detail view containing normal UI components.
- Workaround: Manual correction of every generated controller.

**Generated role source is not valid for its destination:**
- Symptoms: `RoleGenerator` explicitly sets an empty package while `CodeGenerationService` writes the file under `<basePackage>.security`. Role policy methods are emitted as interface methods with bodies but without `default`, `static`, or `private`. The UI treats the human-readable role name as the Java filename/class name, so names such as “Order Manager” produce invalid identifiers and filenames.
- Files: `webui/src/components/RoleDesigner/RoleDesigner.tsx`, `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`
- Trigger: Generate a resource or row-level role, especially with the UI placeholder-style name.
- Workaround: Manually add the package, normalize the class identifier, and rewrite policy methods as valid abstract annotation methods.

**Default entity generation produces invalid trait imports and incomplete trait contracts:**
- Symptoms: The UI defaults to `standardEntity`. The generator calls `implements_("StandardEntity")`, which creates `import StandardEntity;`. Composite `STANDARD_ENTITY` and `AUDITABLE` branches do not generate the audit fields/accessors their own model declares.
- Files: `webui/src/store/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`
- Trigger: Generate an entity without changing the default trait selection.
- Workaround: Remove the trait and manually add supported Jmix interfaces/fields, or correct trait type metadata and composite expansion.

**Collection associations are generated as scalar Java fields:**
- Symptoms: `AttributeModel.javaType` always returns the related entity type for associations. `ONE_TO_MANY` and `MANY_TO_MANY` annotations are therefore applied to a scalar rather than `List<T>`/`Set<T>`. A missing `mappedBy` defaults to the literal `"id"`.
- Files: `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`
- Trigger: Generate an entity with a one-to-many or many-to-many attribute.
- Workaround: Manually change field/accessor types, imports, initialization, and ownership metadata.

**Embedded IDs are placeholders rather than generated composite IDs:**
- Symptoms: The entity field type is the literal `EmbeddedId`, while its accessors use `Object`; `IdConfig.embeddedAttributes` are never used to generate an embeddable ID class. Migration generation collapses the ID to one `VARCHAR(255)` column.
- Files: `plugin/src/main/kotlin/com/jmixstudio/model/EntityModel.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`
- Trigger: Select the advertised Embedded/Composite ID strategy.
- Workaround: Hand-write the ID class, accessors, and matching multi-column migration.

**View menu integration creates malformed or incomplete menu files:**
- Symptoms: View generation appends a standalone `<item/>` after an existing XML document, yielding multiple root elements. If no file exists, it writes only `<item/>` rather than `<menu-config>`.
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MenuGenerator.kt`
- Trigger: Generate a view that includes `menuEntry`.
- Workaround: Manually merge the entry inside the existing `<menu-config>` root.

**BPM approval output uses an undeclared XML prefix:**
- Symptoms: Conditional sequence flows emit `xsi:type="tFormalExpression"`, but the BPMN root does not declare `xmlns:xsi`.
- Files: `plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt`
- Trigger: Invoke `generateBpm`; the generated approval template always contains conditional flows.
- Workaround: Add the XML Schema Instance namespace manually.

**Bridge requests can hang forever or resolve the wrong operation:**
- Symptoms: `request()` has no timeout or rejection path and correlates responses only by action name. In production, a missing bridge queues the request indefinitely; concurrent requests with the same action can consume each other’s response.
- Files: `webui/src/bridge/index.ts`, `webui/src/store/index.ts`
- Trigger: Use the UI outside JCEF, encounter failed bridge injection, or issue overlapping requests for the same action.
- Workaround: Reload the UI. The protocol needs unique request IDs, timeouts, cancellation, and error rejection.

**Generation result reporting is incomplete:**
- Symptoms: Entity generation writes an entity, migration, optional repository, and messages but returns only the entity path. View generation omits the menu path from `filesWritten`.
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`
- Trigger: Generate an entity with DDL/repository enabled or a view with a menu entry.
- Workaround: Inspect the filesystem; do not rely on the success toast’s file count.

## Security Considerations

**User-controlled paths are not confined to the project root:**
- Risk: Class names, package names, role names, changelog IDs, and BPM process IDs flow into `File(projectRoot, relativePath)` without identifier validation, normalization, or a canonical-path containment check. Slash and `..` segments can traverse directories; absolute path behavior is also not rejected.
- Files: `webui/src/components/EntityDesigner/EntityDesigner.tsx`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/components/RoleDesigner/RoleDesigner.tsx`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`, `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`
- Current mitigation: The normal UI offers conventional placeholder values, but most fields are free-form and the bridge accepts direct JSON requests.
- Recommendations: Validate Java identifiers/package names and safe filenames, resolve against the canonical project root, reject any result outside that root, and test traversal/absolute/symlink cases.

**The JCEF bridge grants write capabilities without origin or navigation checks:**
- Risk: The bridge is injected after every main-frame load and dispatches file-generating actions without checking the page origin. A configured development URL—or any future navigation to untrusted content—receives project mutation capabilities.
- Files: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`
- Current mitigation: Production intends to load bundled UI resources, and the development URL requires a system property.
- Recommendations: Allowlist the bundled origin and loopback development origins, block external navigation, expose only capability-scoped commands, and disable writes until project trust is established.

**Bridge responses interpolate unescaped values into executable JavaScript:**
- Risk: `action` is placed inside a single-quoted JavaScript literal and exception text is manually inserted into JSON. Crafted action names or exception messages can break out of the intended syntax or make the response unparsable.
- Files: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Current mitigation: The bundled React UI uses fixed action strings, but the bridge itself does not enforce them before constructing responses.
- Recommendations: Serialize the complete callback arguments with Gson, never concatenate into JavaScript source, and reject unknown actions before reflecting input.

**Generated Java accepts raw code-bearing user strings:**
- Risk: Class names, custom annotation names/parameters, instance-name expressions, validation regex/messages, role descriptions, queries, and method bodies are inserted into Java source without consistent escaping or syntactic validation.
- Files: `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/RoleGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/DataRepositoryGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/ViewControllerGenerator.kt`
- Current mitigation: XML values are escaped by `XmlBuilder`, but Java generation has no equivalent literal/identifier encoder.
- Recommendations: Separate identifiers, literals, types, and raw code in the AST; escape each context; reserve raw source injection for an explicit advanced mode with preview.

**No payload size or complexity limits exist:**
- Risk: Deep component/menu trees or very large source/SQL strings can consume memory, recursion depth, generation time, and disk space through the bridge.
- Files: `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/components/MenuDesigner/MenuDesigner.tsx`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`
- Current mitigation: None detected.
- Recommendations: Bound request bytes, tree depth/node count, generated file size, and operation count; fail with structured validation errors.

## Performance Bottlenecks

**Every successful operation schedules a recursive refresh of the entire project:**
- Problem: A generation request refreshes `projectRoot` with `refresh(true, true)`, even when only one or a handful of files changed.
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`
- Cause: The service bypasses VFS-aware file APIs and compensates with a full recursive refresh.
- Improvement path: Use VFS/PSI write APIs for exact files and refresh only created/changed paths once per batch.

**CRUD writes are synchronous, sequential, and individually wrapped:**
- Problem: Up to eleven files are written one at a time, each through a separate write command, before a full-project refresh.
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`
- Cause: The service has no staged output or batch write abstraction.
- Improvement path: Generate and validate all content off the UI path, then apply one atomic write command with a single targeted refresh.

**Recursive editor updates clone and rerender broad trees:**
- Problem: View and menu edits recursively traverse and reconstruct full trees for find/update/remove/insert/move operations; `ViewDesigner` recursively renders every node with no memoized subtree boundary.
- Files: `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/components/MenuDesigner/MenuDesigner.tsx`
- Cause: Tree state is stored as nested React objects and manipulated with full recursive copies.
- Improvement path: Normalize nodes by ID, memoize rows/subtrees, measure large-tree behavior, and virtualize palettes/lists when node counts grow.

## Fragile Areas

**File generation is destructive and non-transactional:**
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`
- Why fragile: `writeText()` overwrites existing files without existence checks, diffs, confirmation, backups, or merge logic. CRUD writes files sequentially; an exception after earlier writes leaves a partial stack. Re-running CRUD replaces the application’s entire `menu.xml` and `messages.properties`.
- Safe modification: Stage outputs, validate/compile/parse them, show a conflict-aware diff, merge structured XML/properties, and commit all accepted writes in one IDE undoable transaction.
- Test coverage: No overwrite, rollback, conflict, merge, or IDE undo tests exist.

**Migration filenames are collision-prone:**
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/CrudOrchestrator.kt`, `plugin/src/main/kotlin/com/jmixstudio/generator/MigrationGenerator.kt`
- Why fragile: Entity/CRUD migrations use fixed `001-<table>.xml`; manual migrations use the unchecked user changelog ID. Existing files are silently replaced, and no master changelog include is updated.
- Safe modification: Allocate unique ordered IDs after scanning existing changelogs, reject collisions, and update the root changelog structurally.
- Test coverage: No repeated-generation, collision, include-chain, or database validation tests exist.

**Project detection assumes one root Java Gradle module:**
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`, `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`
- Why fragile: Detection checks only root `build.gradle`/`build.gradle.kts`, scans only `src/main/java`, hardcodes source/resource roots, chooses a package only along single-child directory chains, infers the database from build text, and defaults ambiguous projects to PostgreSQL/Jmix 2.4.
- Safe modification: Use IntelliJ module/source-root models, Gradle project data, Kotlin source roots, and explicit user selection when detection is ambiguous.
- Test coverage: No Kotlin-only, multi-module, custom-source-set, Maven, multiple-database, or version-catalog fixtures exist.

**The frontend bridge has no lifecycle state machine:**
- Files: `webui/src/bridge/index.ts`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`
- Why fragile: Ready state, pending requests, navigation/reload, disposal, response errors, and duplicate actions are handled by mutable arrays and global callbacks.
- Safe modification: Define connection states, request IDs, typed success/error envelopes, cancellation, timeout, and reload/dispose semantics.
- Test coverage: No bridge contract or reload/concurrency tests exist.

**Production JCEF resource loading is unverified:**
- Files: `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`, `plugin/build.gradle.kts`, `webui/vite.config.ts`
- Why fragile: The tool window passes `getResource("/webui/index.html").toExternalForm()` directly to JCEF. Packaged resources commonly use a `jar:` URL, but there is no custom scheme/resource handler, extraction path, packaged-plugin smoke test, or visible fallback error when loading resolves to `about:blank`.
- Safe modification: Add a supported classpath-resource handler or extract assets to a controlled local directory, surface load failures, and smoke-test the installed ZIP.
- Test coverage: No packaged JCEF launch test exists.

## Scaling Limits

**Large IDE projects:**
- Current capacity: No measured limit; every generation triggers a recursive refresh from the project root.
- Limit: Refresh cost grows with all project files, not the number of generated files.
- Scaling path: Refresh exact VFS nodes and benchmark representative multi-module repositories.

**Designer model size:**
- Current capacity: No enforced node/change limit and no performance benchmark.
- Limit: Nested immutable updates and full recursive rendering scale with total tree size and depth; deep trees also risk call-stack exhaustion.
- Scaling path: Normalize/virtualize state, add depth/node limits, and benchmark 100/1,000/10,000-node models.

**Bridge concurrency:**
- Current capacity: One action-name-based listener match; no request IDs or backpressure.
- Limit: Multiple simultaneous calls with the same action are ambiguous, and unbounded pending requests/listeners can accumulate.
- Scaling path: Add unique correlation IDs, bounded queues, cancellation, and per-request timeouts.

## Dependencies at Risk

**IntelliJ compatibility is narrow and not continuously verified:**
- Risk: Plugin metadata declares support from build 241 through `251.*`; newer IDE builds are rejected. The plugin depends on JCEF and Java modules but has no compatibility matrix.
- Impact: Installation or runtime can fail outside the declared range, including IDEs without supported JCEF.
- Migration plan: Add Plugin Verifier runs for every supported IntelliJ release, test JCEF/no-JCEF paths, and update `plugin/build.gradle.kts` plus `plugin/src/main/resources/META-INF/plugin.xml` together.

**Legacy IntelliJ Gradle plugin and incomplete wrapper reduce reproducibility:**
- Risk: `org.jetbrains.intellij` 1.17.4 is used with local build reports already showing Gradle deprecation warnings, while the wrapper executable/JAR is missing.
- Impact: Future Gradle versions can turn current deprecations into failures, and contributors cannot run the pinned build.
- Migration plan: Restore the Gradle wrapper first, pin CI, then migrate to the current IntelliJ Platform Gradle plugin with build/verification parity.

**Bundled UI freshness depends on a local ignored directory:**
- Risk: `copyWebUi` packages `../webui/dist`, which is ignored and may be stale or absent if `npm run build` was not run first; the Gradle task does not depend on an npm build task.
- Impact: A plugin build can package an old UI or no UI while Kotlin compilation succeeds.
- Migration plan: Make a reproducible frontend build an explicit Gradle input/dependency and verify hashed assets in the plugin ZIP.

## Missing Critical Features

**Existing entity discovery is a stub:**
- Problem: `getEntities` always returns an empty list and contains an explicit TODO instead of scanning `@JmixEntity` source.
- Blocks: The README claim of one-click CRUD “from any entity” is not implemented; the wizard only reuses the current in-memory Entity Designer model.
- Files: `README.md`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `webui/src/components/CrudWizard/CrudWizard.tsx`

**IDE “New” actions do not route to their advertised designers:**
- Problem: New Entity, New View, and New CRUD actions only show the tool window. The Entity action contains a comment where tab selection should occur; the other actions do not signal React at all.
- Blocks: Context menu actions cannot open the intended workflow directly.
- Files: `plugin/src/main/kotlin/com/jmixstudio/actions/Actions.kt`, `plugin/src/main/resources/META-INF/plugin.xml`, `webui/src/App.tsx`

**BPM generation has no visible designer or tab:**
- Problem: Backend generation and a bridge action exist, but `App.tsx` exposes only entity, view, CRUD, menu, role, and migration tabs.
- Blocks: The README’s BPM Generator feature is inaccessible through the shipped UI.
- Files: `README.md`, `plugin/src/main/kotlin/com/jmixstudio/generator/BpmGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `webui/src/App.tsx`

**The manual Migration Builder exposes only seven change types:**
- Problem: The README and Kotlin model advertise 25+ Liquibase operations, while the UI supports only create table, add/drop column, add foreign key, create index, insert data, and raw SQL—and those payloads are currently incompatible with Gson deserialization.
- Blocks: Most advertised migration operations cannot be authored visually.
- Files: `README.md`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`, `plugin/src/main/kotlin/com/jmixstudio/model/MigrationModel.kt`

**No designer persistence/import workflow exists:**
- Problem: Entity state is Zustand memory and other designer state is local React state; there is no save/open/import/scan round trip.
- Blocks: Reloading the JCEF page loses designs, and existing project artifacts cannot be edited visually.
- Files: `webui/src/store/index.ts`, `webui/src/components/ViewDesigner/ViewDesigner.tsx`, `webui/src/components/MenuDesigner/MenuDesigner.tsx`, `webui/src/components/RoleDesigner/RoleDesigner.tsx`, `webui/src/components/MigrationPanel/MigrationPanel.tsx`

**README “fully functional” status is not supported by implementation:**
- Problem: The README states the plugin is fully functional and documents installation/build output, but the Kotlin sources do not compile, the Gradle wrapper command is absent, Menu/Migration/View/CRUD flows have protocol blockers, entity discovery is stubbed, and BPM is not exposed.
- Blocks: Users cannot rely on the feature list as a release-readiness contract.
- Files: `README.md`, `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`, `plugin/gradle/wrapper/gradle-wrapper.properties`, `webui/src/App.tsx`

## Test Coverage Gaps

**Entire plugin and generator layer:**
- What's not tested: No test source files or test dependencies are present for models, builders, generators, services, bridge behavior, or IDE integration.
- Files: `plugin/build.gradle.kts`, `plugin/src/main/kotlin/com/jmixstudio/`
- Risk: Kotlin compile failures, invalid Java imports, invalid XML, path traversal, overwrite behavior, and model drift remain undetected.
- Priority: High

**Frontend designers and bridge:**
- What's not tested: `webui/package.json` defines only dev/build/preview scripts; there is no unit, component, or end-to-end test runner.
- Files: `webui/package.json`, `webui/src/bridge/index.ts`, `webui/src/components/`
- Risk: User flows can type-check and bundle successfully while every generation request fails at runtime.
- Priority: High

**Generated artifact validity:**
- What's not tested: There are no golden fixtures, XML schema parses, Java compilation checks, Liquibase update/rollback checks, BPMN parses, or target Jmix application builds.
- Files: `plugin/src/main/kotlin/com/jmixstudio/generator/`
- Risk: Generators report success after writing syntactically or semantically invalid artifacts.
- Priority: High

**Data-loss and security boundaries:**
- What's not tested: Existing-file conflicts, partial-write rollback, undo behavior, canonical path containment, symlink traversal, origin checks, malformed JSON, payload limits, and JavaScript escaping.
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`, `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`
- Risk: Project files or files outside the project can be overwritten without warning, and the embedded browser boundary is not hardened.
- Priority: High

**Project compatibility matrix:**
- What's not tested: Kotlin Jmix apps, multi-module Gradle builds, custom source sets, supported IntelliJ builds, JCEF-unavailable IDEs, and installed-plugin resource loading.
- Files: `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`, `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`, `plugin/build.gradle.kts`
- Risk: Detection defaults to incorrect paths/database/package or the packaged UI fails to load.
- Priority: High

**Observed build baseline:**
- What's not tested: `npm run build` succeeds for the current UI (TypeScript and Vite, 1,587 modules), but there is no clean end-to-end plugin build. The documented `./gradlew` entry point is absent, and isolated JDK 17 Kotlin compilation exposes source errors.
- Files: `webui/package.json`, `README.md`, `plugin/src/main/kotlin/com/jmixstudio/generator/ViewXmlGenerator.kt`, `plugin/gradle/wrapper/gradle-wrapper.properties`
- Risk: A green frontend build can be mistaken for a working product.
- Priority: High

---

*Concerns audit: 2026-07-27*

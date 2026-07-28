---
phase: 2
slug: compatibility-laboratory-and-read-only-onboarding
status: approved
shadcn_initialized: false
preset: none
created: 2026-07-28
reviewed_at: 2026-07-28T12:10:03+06:00
---

# Phase 2 — UI Design Contract

> Visual and interaction contract for the Compatibility Laboratory and Read-Only Onboarding phase. This is the implementation source of truth for the Phase 2 web UI.

---

## Product Intent

The default experience is **Project Overview**, a calm, non-technical explanation of what the workbench observed and what the user can safely do next. It must never open on an editable entity form.

The experience must make these truths obvious without requiring the user to understand the compatibility registry:

1. The workbench is read-only in Phase 2.
2. Opening and exploring the workbench did not change project files or contact a database.
3. Every value is observed evidence, not a guessed default.
4. Degraded projects remain useful for diagnosis and source navigation.
5. Mutation designers exist as future workflows but are not certified or available.

The visual language must preserve the original **Jmix Visual Workbench** identity and existing blue/dark token family. It must not imitate proprietary Jmix Studio layouts, illustrations, icons, naming, or assets.

Sources: locked phase direction, `REQUIREMENTS.md` COMP-01/02/03/06/07 and DISC-01–10, `02-RESEARCH.md` UI/UX Implications, and the existing `App.tsx`/Tailwind theme.

---

## Design System

| Property | Value |
|----------|-------|
| Tool | none — existing manual Tailwind CSS 3 token system |
| Preset | not applicable |
| Component library | none; use semantic React components and native HTML behavior |
| Icon library | Lucide React only |
| Font | `JetBrains Sans`, then the existing system stack: `-apple-system`, `BlinkMacSystemFont`, `Segoe UI`, `Roboto`, sans-serif |
| Theme | dark, designed as a companion to IntelliJ rather than a separate website |
| Styling | Tailwind utilities plus narrowly scoped CSS for container queries, focus, virtualization, and reduced motion |

Do not initialize shadcn, add a component registry, or add another icon library in this phase. Do not use emoji, proprietary marks, or text glyphs as icons. Lucide icons must use `aria-hidden="true"` when an adjacent text label supplies the accessible name.

### Existing tokens to retain

- `surface.DEFAULT`: `#1E1E2E`
- `surface.light`: `#2A2A3C`
- `surface.lighter`: `#363649`
- `surface.border`: `#45455A`
- `jmix.500`: `#4A90D9`
- `jmix.600`: `#2563EB`

Raise low-contrast body and secondary text to the accessible values declared below; do not retain `gray-500`/`gray-600` for essential text.

---

## Spacing Scale

Declared values are the only spacing tokens for margins, padding, and gaps.

| Token | Value | Usage |
|-------|-------|-------|
| xs | 4px | Icon-to-label gaps, compact inline separation |
| sm | 8px | Control groups, row padding, compact card gaps |
| md | 16px | Default content padding and section gaps |
| lg | 24px | Page and panel padding in wide panes |
| xl | 32px | Major content-group separation |
| 2xl | 48px | Empty-state and onboarding breathing room |
| 3xl | 64px | Reserved page-level spacing; do not use inside narrow panes |

Exceptions:

- 2px focus-ring width and 2px focus offset are strokes, not layout spacing.
- Dense desktop control heights may be 28px; default controls are 32px and prominent actions are 40px.
- Icon-only controls have a 32px visible button and a minimum 40×40px hit target.
- Tree indentation is 16px per level and stops after four visible indentation levels; deeper ancestry is represented in the accessible label and breadcrumb.

No 6px, 10px, 12px, 20px, or arbitrary Tailwind spacing may be introduced in Phase 2 layouts.

---

## Typography

Use exactly four text sizes and two weights. Sentence case is the default.

| Role | Size | Weight | Line Height |
|------|------|--------|-------------|
| Label / metadata | 12px | 400 or 600 | 1.4 |
| Body / controls | 13px | 400 or 600 | 1.5 |
| Section heading | 16px | 600 | 1.25 |
| Page display | 20px | 600 | 1.2 |

Rules:

- The only weights are regular `400` and semibold `600`; do not use bold `700`.
- Uppercase is reserved for short status codes and compact navigation group labels. It must not be used for headings or explanatory sentences.
- Reason codes, relative paths, registry digests, and fingerprints use the existing IDE monospace stack at 12px/1.4; user-facing explanations remain sans-serif.
- Truncated labels expose the full safe value on focus and hover. Sensitive or absolute values never enter the DOM and therefore never appear in tooltips.
- Paragraph measure is 72 characters maximum. Diagnostic explanations use short sentences and one recommendation per paragraph.

---

## Color

| Role | Value | Usage |
|------|-------|-------|
| Dominant (60%) | `#1E1E2E` | Workbench background, table background, empty canvas |
| Secondary (30%) | `#2A2A3C` | Navigation, cards, inspectors, headers |
| Accent (10%) | `#4A90D9` | Current section marker, selected-row border, links, focus emphasis, progress indicator |
| Destructive | `#EF4444` | Destructive actions only; Phase 2 renders no destructive action |

Accent reserved for:

- the active navigation item;
- the primary **Review Compatibility** action;
- native-navigation links such as **Open in Editor**;
- selected tree/table item border or icon;
- keyboard focus emphasis;
- indexing progress.

Do not use the accent as a general card background, decorative gradient, body-text color, or status substitute. A solid primary button uses `jmix.600` (`#2563EB`) with white text; `jmix.500` remains the canonical accent token.

### Supporting accessible tokens

| Token | Value | Contract |
|-------|-------|----------|
| Primary text | `#F4F4F5` | Headings and essential values |
| Secondary text | `#C4C4CC` | Body explanations |
| Muted text | `#A1A1AA` | Non-essential metadata only |
| Border | `#45455A` | Dividers and card boundaries |
| Info | `#60A5FA` | Information icon plus explicit text label |
| Positive/read-only verified | `#4ADE80` | Check/shield icon plus “Read-only verified” |
| Warning/degraded | `#FBBF24` | Warning icon plus state text |
| Error/blocking | `#F87171` | Error icon plus state text |
| Neutral/unknown | `#A1A1AA` | Question/clock icon plus state text |

Status meaning must always be conveyed by an icon, a visible text label, and an accessible name. Color is reinforcement only. Every text/background pair must meet WCAG 2.2 AA: 4.5:1 for body text and 3:1 for large text, borders that communicate state, and focus indicators.

---

## Information Architecture

The primary navigation order is fixed:

1. **Overview**
2. **Project Structure**
3. **Inventory**
4. **Relationships**
5. **Compatibility**
6. **Diagnostics**

A final separated group is labeled **Designers — unavailable** and lists:

- Entity Designer
- View Designer
- CRUD Wizard
- Menu Designer
- Role Designer
- Migrations

Designer rows are not routes and must not mount existing editor components. Each row displays a lock icon and **Not certified**. A separate focusable **Why unavailable** control opens the compatibility explanation for that operation. Do not rely on a disabled button tooltip, because disabled controls are not reliably keyboard-discoverable.

The global header contains:

- original workbench name and Lucide `Blocks` or `PanelsTopLeft` icon;
- current project display name only, never its absolute path;
- the current combined state, for example **Recognized — read-only**;
- an always-visible read-only shield indicator.

No “Generate,” “Create,” “Apply,” “Save,” “Run,” “Connect,” “Sync,” or database-test command appears anywhere in Phase 2.

---

## Responsive Tool-Window Layout

Responsiveness is based on the JCEF root container width, not the browser viewport. Set `container-type: inline-size` on the application shell.

### Wide: 960px and above

- 216px persistent navigation rail.
- Main content uses a 12-column grid.
- Overview content spans eight columns; a 320px contextual inspector occupies four columns when an item is selected.
- Inventory and diagnostic tables retain labeled columns with a sticky header.
- Maximum readable content width is 1280px; extra width remains surface background.

### Compact: 640–959px

- 52px icon rail with keyboard/focus tooltips and accessible labels.
- Main content is one column.
- Details open in a non-modal 320px overlay inspector anchored to the right; `Escape` closes it and returns focus to the invoking row.
- Tables keep Name, State, and Module columns; other details move to the inspector.

### Narrow: below 640px

- No persistent side rail.
- A 48px sticky top bar contains workbench identity, read-only shield, and a **Sections** menu.
- Content is a single column with 16px padding.
- Profile facts become stacked definition rows.
- Inventory and diagnostics switch from tables to virtualized accessible list items; no horizontal page scrolling.
- Relationship incoming/outgoing groups stack vertically.
- The inspector becomes a focus-managed, non-modal full-width detail layer below the sticky header; `Escape` returns to the source item.

At 200% zoom and a 320px-wide pane, all core content remains reachable without horizontal scrolling except intentionally scrollable safe values such as relative paths and fingerprints.

---

## Screen Contracts

### 1. Project Overview

The overview is the default route and uses this vertical order:

1. Read-only guarantee banner.
2. Project recognition/status summary.
3. Exact detected profile facts.
4. Trust/import/index health.
5. Topology and inventory summary.
6. Blockers and recommended next step.

#### Read-only guarantee banner

The banner is always present after the first response, including unsupported, failed, stale, untrusted, and cancelled states.

- Heading: **Read-only inspection**
- Body: **No project files or databases were changed.**
- Supporting line: **The workbench did not run Gradle tasks, start the application, resolve dependencies, or connect to a data store.**
- Visual: `ShieldCheck` icon for a completed observation; `Shield` icon while facts are still loading.

Do not use a dismiss button.

#### Exact profile facts

Display a definition-list grid with these fixed labels:

- Jmix version
- Target JDK
- IntelliJ host
- Gradle DSL
- Languages
- Build topology
- Project trust
- Import state
- Index state

Each value has a visible evidence state: **Detected**, **Not detected**, **Conflicting**, **Out of date**, or **Unavailable while indexing**. Never display a placeholder version, database, package, source root, or JDK.

For conflicting evidence, show a concise summary such as:

> **Conflicting** — imported build reports Java 17; module SDK reports Java 21.

The evidence disclosure lists safe source labels such as **Imported Gradle model**, **Module SDK**, or the project-relative file `build.gradle.kts`. It never shows source bodies or absolute paths.

#### Health stack

Trust, import, and index are three separate rows; do not collapse them into one ambiguous “Project health” score. Each row contains:

- icon and exact state label;
- one-sentence explanation;
- one safe next step when action is possible;
- **View evidence** disclosure.

Use this exact copy for normal and unknown health states:

| Domain/state | Label | Explanation | Next step |
|---|---|---|---|
| Trust `TRUSTED` | **Project trusted** | **IntelliJ marks this project as trusted. Read-only analysis can use the already imported IDE model.** | **View evidence** |
| Trust `UNKNOWN` | **Trust status unavailable** | **The workbench could not confirm IntelliJ’s trust state. Analysis remains limited and read-only.** | **Review Diagnostics** |
| Import `READY` | **Project import ready** | **IntelliJ’s imported project model is available for read-only analysis.** | **View evidence** |
| Import unknown | **Import status unavailable** | **The workbench could not confirm whether an imported project model is current. Static evidence remains available.** | **Review Diagnostics** |
| Index `READY` | **Index ready** | **IntelliJ indexing is complete for the project areas included in this snapshot.** | **Browse Inventory** |
| Index unknown | **Index status unavailable** | **The workbench could not confirm the current IntelliJ index state. Artifact results may be incomplete.** | **Review Diagnostics** |

Healthy states must not show a congratulatory toast or hide the evidence
disclosure. Unknown states must not silently inherit a healthy icon or value.

#### Summary cards

Use no more than four cards in one row:

- Builds and modules
- Source roots
- Add-ons and data stores
- Indexed artifacts

Cards display counts and a noun label, not decorative charts. Partial counts append **so far** while indexing. Zero is a valid observed value and is distinct from unknown.

#### Primary action

The overview primary action is **Review Compatibility**. It navigates to the compatibility matrix and moves focus to its heading. Secondary actions are contextual links, chiefly **Open in Editor**, **View Diagnostics**, and **Refresh Analysis**. Refresh reads the current IDE model only and must never imply Gradle sync or dependency resolution.

### 2. Project Structure

Use an accessible `treegrid` that preserves build/module/source-set ownership.

Hierarchy:

```text
Build
  Module
    Source set
      Source root
    Add-ons
    Data stores
      Migration roots
```

Node rows contain:

- type icon and display name;
- role, such as **Application**, **Add-on functional**, or **Add-on starter**;
- language/source-set badge;
- state icon and diagnostic count;
- optional project-relative path;
- **Open in Editor** only when a valid navigation target exists.

Tree behavior:

- `Up`/`Down` moves among visible rows.
- `Right` expands or moves to the first child.
- `Left` collapses or moves to the parent.
- `Enter` selects and opens details.
- `Ctrl+Enter` or `Cmd+Enter` invokes **Open in Editor** when available.
- Expansion persists only in in-memory UI state for the current tool-window lifetime.

Do not flatten composite/included builds. For deep structures, stop visual indentation at four levels and show ancestry in a breadcrumb.

Data store rows expose store name/classification, owning module, dialect evidence, and migration-root count. They never expose URLs, usernames, passwords, credential property values, driver connection strings, or connection-test actions.

Internal/proprietary add-on coordinates and names are projected as **Internal add-on** with an opaque local identifier. Public official/third-party add-ons may display their public name and detected version. No proprietary dependency metadata is serialized to the browser.

### 3. Inventory

The inventory is a bounded, incremental catalog, not an editor.

Controls:

- Search input labeled **Search inventory** with placeholder **Search artifacts by name or kind**.
- Kind filter with: All, Entities, DTOs, Enums, Views, Controllers, Fetch plans, Menus, Roles, Messages, Repositories, Changelogs, Modules, Add-ons, Data stores.
- Module/source-set filter.
- Diagnostics filter: All, With issues, No issues.
- Clear-filters action appears only when a filter is active.

Wide table columns:

1. Name
2. Kind
3. Owning module / source set
4. Origin
5. Diagnostics
6. Native navigation

Interaction:

- Default sort: kind, then locale-aware display name.
- Search updates after 200ms debounce and announces the result count in a polite live region.
- Page size is 100 by default, user-selectable to 250 or 500; 500 is the hard maximum.
- Rows are virtualized within each page.
- Page requests are cancellable. A newer search/filter request cancels the previous request.
- Selection is stable by artifact ID, not row index.
- `Up`/`Down` changes the active row, `Enter` opens details, and `Ctrl+Enter`/`Cmd+Enter` navigates natively.
- The browser receives summaries only; raw source bodies remain in IntelliJ.

Selected artifact details contain:

- safe display name and kind;
- stable identity, shown as a shortened fingerprint;
- owner build/module/source set;
- project-relative source location;
- origin/provenance;
- current revision fingerprint;
- diagnostics;
- incoming/outgoing relationship counts;
- **Open in Editor**.

### 4. Relationships

Phase 2 uses a comprehensible relationship list, not a free-form graph canvas.

Header:

- selected artifact name and kind;
- owner breadcrumb;
- **Open in Editor**.

Body:

- **Outgoing relationships** first;
- **Incoming relationships** second;
- rows use plain-language verbs such as **uses entity**, **controlled by**, **includes changelog**, or **localized by**;
- each row identifies target artifact, target kind, owner, and issue state;
- unresolved targets remain visible as **Unresolved reference**, with the source-linked diagnostic.

Selecting a related artifact replaces the detail context without losing the originating inventory filters. A visible **Back to [artifact]** breadcrumb and browser-independent in-memory history support return navigation.

Do not render pan/zoom canvases, force-directed graphs, mini-maps, or edge-only meaning in Phase 2.

### 5. Compatibility

Use a sortable operation matrix with these columns:

1. Operation
2. Availability
3. Why
4. Evidence
5. Tested path

User-facing availability labels map exactly to backend decisions:

| Backend state | Visible label | Treatment |
|---------------|---------------|-----------|
| `CERTIFIED_READ_WRITE` | Certified read/write | Must not occur for Phase 2 mutation operations; if received unexpectedly, UI still hides mutation controls and logs a blocking diagnostic |
| `CERTIFIED_READ_ONLY` | Certified read-only | Green shield/check icon plus text |
| `RECOGNIZED_DIAGNOSTIC` | Recognized — diagnostic only | Amber info/warning icon plus text |
| `UNSUPPORTED` | Unsupported | Neutral or error icon plus text based on backend severity |

Every row contains backend-provided human explanation. The UI must not recompute compatibility or convert a missing decision into availability.

Expanded evidence includes:

- reason code, secondary to the plain-language explanation;
- exact tested Jmix line and JDK;
- tested IntelliJ host lane;
- reviewed fixture/evidence identifiers;
- missing evidence;
- registry version and shortened digest;
- tested alternative or isolated migration path.

For mutation designers, the “Why” copy must say that the operation is not certified in this phase and identify the exact tested cell or later migration path when supplied. Never offer **Try anyway**, **Force**, or an override.

### 6. Diagnostics

Diagnostics are a task-oriented list with these columns in wide mode:

1. Severity
2. What happened
3. Affected area
4. What to do next
5. Source

Plain language comes first. Reason code, evidence ID, and fingerprint are secondary details.

Filters:

- severity;
- affected build/module;
- category: Trust, Import, Index, Profile, Build configuration, Dependency, Source, Relationship, Compatibility;
- **Show resolved for current snapshot** toggle, off by default.

Selecting a diagnostic opens a detail inspector with:

- one-sentence problem;
- why it matters;
- observed evidence;
- safe next step;
- reason code;
- **Open in Editor**, only when the navigation ID is valid.

Do not use toast notifications for discovery errors, import failures, stale state, unsupported profiles, or indexing progress. These are durable states and belong inline. Toasts are limited to transient confirmation such as **Opened in editor** or **Analysis cancelled** and must remain available to screen readers without stealing focus.

---

## State and Feedback Contract

Each state must have distinct text, iconography, behavior, and next step.

| State | Heading / label | Required explanation and behavior |
|-------|-----------------|-----------------------------------|
| Initial loading | **Inspecting project safely** | Show structural skeletons, read-only shield, and **Cancel Analysis** after 500ms. Do not show guessed values. |
| Indexing | **Still indexing** | Keep known topology/profile facts visible. Counts say **so far**. Explain that artifact details will appear as IntelliJ finishes indexing. Provide **Cancel Analysis**. |
| Cancelled | **Analysis paused** | Preserve completed facts, label them partial, and offer **Resume Analysis**. |
| Not detected | **Jmix not detected** | Say which expected public markers were not observed. Keep project structure and diagnostics available. Do not substitute a Jmix version. |
| Conflicting | **Project signals conflict** | List the conflicting safe evidence values and recommend resolving/importing the project in IntelliJ. Remain read-only. |
| Stale | **Project information is out of date** | Mark every affected count/decision **Stale**. Keep navigation where validation passes. Offer **Refresh Analysis**, never **Sync Gradle**. |
| Import failed | **Project import failed** | Show facts collected safely from static files, identify missing imported evidence, and link to diagnostics. Do not trigger import. |
| Import absent | **Project has not been imported** | Explain that only bounded static evidence is available. Do not prompt or trigger Gradle execution. |
| Untrusted | **Project is not trusted** | Explain that analysis is limited and no repository-controlled code was run. Keep safe static diagnostics/navigation. Do not trigger a trust prompt. |
| Recognized read-only | **Recognized — read-only** | State the exact recognized profile and the evidence-backed reason writes are unavailable. |
| Unsupported | **Unsupported profile** | Explain what was observed, why no tested cell applies, and the tested profile/migration path when one exists. |
| Discovery failure | **Project analysis could not finish** | Preserve successful sections, identify the failed section, offer **Retry Analysis**, and link to diagnostics. |
| Empty inventory | **No Jmix artifacts detected** | **No supported artifacts were found in the indexed project areas. Review Project Structure and Diagnostics for missing or excluded roots.** |
| Filtered empty | **No matching artifacts** | **Clear one or more filters or search for a different name or kind.** |
| No relationships | **No relationships detected** | **No incoming or outgoing Jmix relationships were found for this artifact in the current snapshot.** |
| No diagnostics | **No diagnostics for this snapshot** | **The workbench did not detect project-understanding issues. Compatibility limits may still apply.** |

### Loading and progress

- Skeletons match final geometry and never replace already known facts.
- Indeterminate progress uses a small spinner plus visible text; known progress uses a bar with current/total.
- Animations last 120–160ms and affect opacity/transform only.
- `prefers-reduced-motion: reduce` disables spinners in favor of a static progress icon and removes transitions.
- A polite live region announces stage changes no more than once per second.

### Cancellation

- **Cancel Analysis** is always secondary, never destructive.
- Cancellation stops new work and retains the last complete immutable snapshot.
- While cancellation is pending, label the control **Cancelling…** and prevent duplicate requests.
- Closing the tool window cancels outstanding work without a confirmation dialog.

### Freshness

Every page header shows one of:

- **Current snapshot**
- **Indexing — partial snapshot**
- **Stale snapshot**
- **Analysis paused**

Do not display workstation-specific timestamps in exported or shared copy. In the local UI, a relative age may appear only as secondary text.

---

## Copywriting Contract

| Element | Copy |
|---------|------|
| Primary CTA | **Review Compatibility** |
| Read-only heading | **Read-only inspection** |
| Read-only guarantee | **No project files or databases were changed.** |
| Empty state heading | **No Jmix artifacts detected** |
| Empty state body | **No supported artifacts were found in the indexed project areas. Review Project Structure and Diagnostics for missing or excluded roots.** |
| Error state | **Project analysis could not finish. The completed results are still available. Review Diagnostics, then retry the analysis.** |
| Native navigation | **Open in Editor** |
| Refresh action | **Refresh Analysis** |
| Cancel action | **Cancel Analysis** |
| Destructive confirmation | None. Phase 2 has no destructive action and must render no destructive confirmation. |

### Voice

- Lead with what the user can understand: **Project import failed**, not `IMPORT_MODEL_UNAVAILABLE`.
- Follow with cause, consequence, and one next step.
- Use **detected**, **observed**, and **evidence** for facts.
- Use **not detected** when evidence is absent; never use **default**, **assumed**, or a fabricated value.
- Use **conflicting** when evidence disagrees; show both safe values.
- Use **still indexing** for incomplete IntelliJ indexes.
- Use **recognized — read-only** for known but uncertified profiles.
- Use **unsupported** only when the backend returns `UNSUPPORTED`, and always add why.
- Avoid “invalid project,” “just,” “simply,” “magic,” and blame-oriented language.
- Technical reason codes are copyable metadata, not headings.

---

## Evidence, Redaction, and Privacy

The browser projection and rendered DOM must never contain:

- absolute machine paths or home-directory fragments;
- credential values, connection strings, tokens, usernames, or passwords;
- raw Java, Kotlin, XML, properties, YAML, TOML, or Gradle source bodies;
- proprietary/internal dependency coordinates, repository URLs, or metadata;
- database contents or live connection state.

Allowed evidence:

- project-relative paths;
- safe source-kind labels;
- public Jmix/add-on coordinates already classified as public;
- opaque navigation IDs;
- stable artifact IDs and shortened fingerprints;
- reason codes, fixture IDs, registry version, and registry digest;
- counts and classifications.

All redaction happens in Kotlin before serialization. CSS masking, client-side replacement, hidden DOM, browser local storage, and tooltip suppression are not acceptable privacy controls.

---

## Component Inventory

| Component | Required behavior |
|-----------|-------------------|
| `WorkbenchShell` | Container-query root, adaptive navigation, focus target, no persistent browser storage |
| `ReadOnlyBanner` | Permanent shield, exact guarantee copy, never dismissible |
| `SnapshotStatus` | Current/partial/stale/paused label with icon and accessible name |
| `ProjectProfileGrid` | Semantic `dl`; exact values plus evidence state; no defaults |
| `HealthRow` | Separate trust/import/index row with explanation and safe next step |
| `SummaryCard` | Count, noun, partial/unknown distinction; no decorative chart |
| `ProjectTreeGrid` | Ownership-preserving hierarchy, roving focus, native navigation |
| `InventoryToolbar` | Labeled search, filters, result count, clear filters |
| `VirtualizedArtifactList` | Stable IDs, bounded pages, keyboard selection, list fallback in narrow panes |
| `ArtifactInspector` | Safe identity, ownership, provenance, fingerprint, diagnostics, relationships |
| `RelationshipList` | Incoming/outgoing groups with visible verbs and unresolved targets |
| `CompatibilityMatrix` | Backend-authored decision/reason/evidence/tested path; no client authorization |
| `DiagnosticList` | Plain-language issue, next step, source navigation, durable state |
| `EvidenceDisclosure` | Safe evidence labels and metadata; no source body or absolute path |
| `StatusBadge` | Icon + text + accessible name; never color-only |
| `DisabledDesignerGroup` | Locked, not certified, separate **Why unavailable** affordance; never mounts a designer |
| `EmptyState` | State-specific heading/body; no generic “Nothing here” |
| `InlineError` | Preserves partial results and provides one safe recovery action |
| `ProgressRegion` | Bounded announcements, cancel action, reduced-motion support |

Do not introduce dashboard charts, radial health scores, gradients, glass effects, large hero illustrations, decorative metric sparklines, or a relationship graph canvas.

---

## Interaction and Accessibility

### Keyboard

- The first focusable element is a visible **Skip to main content** link.
- Focus order follows visual order: shell header, primary navigation, page heading/actions, content, inspector.
- Navigation uses roving focus; `Home`/`End` move to first/last item.
- Tables/lists use one tab stop plus arrow-key row navigation.
- `Escape` closes menus/inspectors and returns focus to the invoker.
- Native source navigation is always available as a labeled button/link; double-click is optional and never the only method.
- Keyboard shortcuts appear only after being implemented in both IntelliJ host lanes.

### Focus

- Every interactive element has a 2px `#93C5FD` focus ring with 2px offset against both dominant and secondary surfaces.
- Do not remove outlines.
- Selection and focus are visually different: selection uses a 2px left/accent border; focus uses the external ring.
- Focus never jumps when incremental pages arrive. If the active item disappears after filtering, focus returns to the search control and the live region announces the change.

### Semantics

- One `h1` per section; nested headings do not skip levels.
- Profile facts use `dl`/`dt`/`dd`.
- Status changes use `role="status"`; blocking analysis failures use `role="alert"` only when they first occur.
- Tree structure uses `treegrid` with level/expanded/selected properties.
- Compatibility and wide inventories use semantic tables; narrow variants use named lists with equivalent information.
- Tooltips supplement visible labels; they never carry required explanations.

### Zoom and density

- Verify at 100%, 150%, and 200% browser zoom.
- Text reflows without clipping and controls do not overlap.
- At 200% zoom, persistent side rails collapse according to the container contract.
- Dense 28px controls are limited to table/tree toolbars; primary and menu controls are at least 32px high.

---

## Registry Safety

| Registry | Blocks Used | Safety Gate |
|----------|-------------|-------------|
| shadcn official | none | not applicable — shadcn is not initialized |
| Third-party registries | none | not applicable — no registry code permitted in Phase 2 |

No registry vetting is required because the contract introduces no registry source or block.

---

## Verification Contract

The implementation is not visually complete until all of these are demonstrated:

### Required visual fixtures

Capture each at 360px, 640px, and 1024px container widths:

- recognized Jmix 2.8 / Java 17 read-only;
- recognized Jmix 3.0 / Java 25 read-only;
- still indexing with partial counts and cancellation;
- Jmix not detected;
- conflicting Jmix/JDK evidence;
- stale snapshot;
- failed import;
- absent import;
- untrusted project;
- recognized but not certified legacy profile;
- unsupported future profile;
- large inventory with paging/virtualization;
- empty and filtered-empty inventory;
- unresolved relationship;
- diagnostics with error, warning, and information rows;
- all designer entries locked and non-certified.

### Automated checks

- No route defaults to or mounts a mutation designer.
- No Phase 2 DOM snapshot contains **Generate**, **Apply**, **Save**, **Run**, **Connect**, **Try anyway**, or **Force** as an enabled action.
- Every backend compatibility state renders exact visible text, icon, accessible name, reason, and tested path.
- Unknown/conflicting values never render prototype defaults such as Jmix `2.4.0`, PostgreSQL, `com.example.app`, or `src/main/*`.
- Axe/Testing Library checks pass for headings, landmarks, names, focus, tables/treegrid, live regions, and color-independent statuses.
- Keyboard-only tests cover navigation, tree expansion, inventory selection, inspector return focus, filters, pagination, cancellation, and native navigation.
- Reduced-motion tests remove non-essential transitions and animated spinners.
- Redaction tests prove that absolute paths, credential values, source bodies, and proprietary/internal coordinates never enter bridge payloads or the DOM.
- Page payloads remain at or below 500 artifacts and 1 MiB.
- Search/filter request replacement cancels the previous request and does not move focus.

### Manual installed-host checks

In signed IntelliJ IDEA 2025.3 and 2026.2:

- the workbench feels native at narrow and wide tool-window widths;
- text remains readable at 150% and 200% zoom;
- focus is always visible;
- native file/line navigation returns to the correct editor location;
- non-technical reviewers can explain why each degraded fixture is read-only;
- browsing, cancellation, navigation, and close produce no project, build, database, VCS, process, or network mutation;
- the identity is recognizably Jmix Visual Workbench and does not resemble proprietary Studio assets or layout.

---

## Source Decisions

| Source | Decisions used |
|--------|----------------|
| `STATE.md` / `ROADMAP.md` | Phase 2 is non-mutating onboarding; all mutation remains uncertified |
| `REQUIREMENTS.md` | Exact profile, operation-specific compatibility, topology, inventory, relationships, diagnostics, native navigation, cancellation |
| `02-RESEARCH.md` | Overview-first IA, deny-by-default backend copy, degraded-state distinctions, safe evidence and redaction |
| `02-VALIDATION.md` | Tool-window accessibility, bounded pages/payloads, installed-host UAT, performance/cancellation states |
| `App.tsx` / `index.css` | Existing dark workbench identity and navigation shell |
| `tailwind.config.js` | Existing surface and blue accent tokens |
| `package.json` | React/Tailwind/Lucide manual design-system state |
| User locked direction | Non-technical UI, no proprietary imitation, narrow/wide panes, all explicit states and security exclusions |

---

## Checker Sign-Off

- [x] Dimension 1 Copywriting: PASS (healthy/unknown health-state copy added after review)
- [x] Dimension 2 Visuals: PASS
- [x] Dimension 3 Color: PASS
- [x] Dimension 4 Typography: PASS
- [x] Dimension 5 Spacing: PASS
- [x] Dimension 6 Registry Safety: PASS

**Approval:** approved 2026-07-28

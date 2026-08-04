# Plugin Performance & Usability Fix Plan

**Date:** 2026-08-04
**Status:** Approved for implementation (user directive)
**Evidence base:** `~/.cache/JetBrains/IntelliJIdea2026.2/log/idea.log` (Aug 3–4 sessions), freeze dumps `threadDumps-freeze-20260803-125736-*` and `threadDumps-freeze-20260803-162658-*`, code tracing of `bridge/`, `services/`, `discovery/`, and `webui/src/`.
**Target project observed:** `inteacc-payroll` (Jmix 2.5.2, multi-module) on IDEA 2026.2 (idea262 lane).

## Confirmed Root Causes

1. **RC1 — No index persistence.** The application graph is rebuilt from scratch on every IDE start. Cold build on a real multi-module Jmix project takes minutes.
2. **RC2 — Blocking non-cancellable reads.** `ApplicationGraphService.stableRead` = `ReadAction.compute` wrapping the entire `collectCandidates()` walk (and per-file PSI checks). Holds the read lock for minutes; write actions (autosave, VFS refresh, project close) queue behind it → EDT freezes. Freeze dumps show EDT in `SuvorovProgress` write-wait while plugin threads hold reads. Non-cancellable → shutdown `AlreadyDisposedException` noise.
3. **RC3 — Cold-path PSI parsing.** `inspectJvmSyntax` calls `PsiManager.findFile` for every JVM candidate file during graph build (thousands of PSI parses under blocking read).
4. **RC4 — Quadratic-ish linking.** `ApplicationGraphIndexer.addImplicitSourceRelationships` scans each source's evidence text per entity group / service group / workflow alias (`containsDelimited` indexOf loops). Dump shows pooled threads stuck in `Pattern.match`/`containsSymbol`. Cost = Σ(sources × candidate-groups).
5. **RC5 — Starving workspace loaders.** `JcefBridge.submitReadResponse` uses `ReadAction.nonBlocking{}.inSmartMode(project)`; long computations are cancelled by any write action and **restarted from zero**. Under write pressure (worsened by RC2) they never complete → UI promises never resolve → "stuck at loading".
6. **RC6 — Schema workspace regex blowup.** `SchemaWorkspaceService.load` calls `sourceFields(source)` (two full-file regex scans + substring/annotation walks) once per attribute lookup: `fieldDeclaration`, `associationSnapshot`, `fieldNullable`, `fieldUnique`, `fieldIntegerArgument` ×3, etc. ≈ 8–10 full scans per attribute per entity.
7. **RC7 — UI remount storm.** `webui/src/App.tsx` renders `<main key={workspaceRevision}>` and bumps `workspaceRevision` on every `jmix-workbench-index-updated` event. Every incremental index update unmounts/remounts the entire active workspace: local state lost, loaders restart, requests duplicated (freeze dump shows 2 concurrent graph builds + 2 project-properties loads + schema load).
8. **RC8 — Synchronous callback-thread handlers.** The fallback `when` branch in `JcefBridge.handleRequest` (`getProjectConfig`, `getEntities`, legacy `generate*`, `simulateDmnDecision`) runs synchronously on the JCEF callback thread. `getProjectConfig` → `JmixProjectService.detectConfig` walks the file tree (depth 24, up to 25k source reads) or triggers `graph()` → startup stall.

## User Directive (2026-08-04)

1. Fix the plugin from the core so it is verifiably usable.
2. **Persist indexing/knowledge already scanned into the target project repo under `.jmix-workbench/`** so restarts are cheap.
3. **Update related files/knowledge efficiently on edit/add/mutation** — true incremental pipeline, no full rebuilds for small changes.

## Fixes

### F1 — Persistent graph knowledge cache in `<projectRoot>/.jmix-workbench/` (RC1, part of directive 2)
- New platform-free store `discovery/persistence/GraphCacheStore.kt` (pure JDK + Gson, testable in `phase2CoreTest`).
- Cache file: `.jmix-workbench/graph-cache.json` (atomic temp-file + rename writes, schema version + plugin cache version, corrupt-file tolerant: any parse/validation failure → rebuild).
- Persist: inventory stamps (relativePath, modificationStamp, documentModificationStamp, length, ownership), per-file `FileContribution` (indexing output is a pure function of content+fingerprint), and the last `ApplicationGraphResponse`.
- Startup: load cache → validate stamps against VFS → re-read/re-index **only** changed files → re-assemble from cached contributions → publish. Cold start drops from minutes to seconds when the tree is unchanged.
- Exclude `.jmix-workbench` from our own scans (`EXCLUDED_DIRECTORY_NAMES`, `JmixProjectService.EXCLUDED_DIRECTORIES`) and ignore its VFS events; debounce saves after successful builds.

### F2 — Cancellable, write-friendly indexing (RC2, RC3)
- Replace the monolithic `stableRead { collectCandidates() }` with chunked `ReadAction.computeCancellable` segments (per module / per content root) with `ProgressManager.checkCanceled()` between chunks; never hold the read lock across the whole walk.
- Remove per-file PSI syntax inspection from the cold indexing path (keep the diagnostics machinery; demote to on-demand validation used by mutation previews, where the file is already PSI-touched).
- Keep incremental listeners as-is (they are correct); they now feed a cheap rebuild because contributions are cached (F1).

### F3 — Inverted-index implicit linking (RC4)
- Tokenize each source's evidence text once into an identifier set during `indexFile` (store in `FileContribution` → persisted by F1).
- `addImplicitSourceRelationships` becomes set membership: candidate alias tokens ∩ file token set, with the same bounded-context preference rules (`preferredCandidates`) and qualified-name wins. Same output semantics, O(text) per file instead of O(files × groups × text).
- Regression-proof with `phase2CoreTest` fixtures asserting identical relationships on the existing corpus.

### F4 — Non-starving bridge reads (RC5)
- Keep `ReadAction.nonBlocking` but drop `inSmartMode` for workspace reads (dumb-mode-safe loaders already read text/VFS only where needed) and wrap long pure-CPU sections outside read actions (pattern already used by `submitBackgroundResponse`).
- Coalesce duplicate in-flight graph requests in `ApplicationGraphService` (single-flight: second caller joins the running build — already partially present via `buildLock`; make joiners return the same response instead of re-queueing work).

### F5 — Schema workspace de-quadratic pass (RC6)
- Memoize `sourceFields(source)` per file inside `load()` (one scan per entity source, reused by all per-attribute lookups); hoist per-call `Regex(...)` constructions to companions where trivial.
- No behavior change; same snapshots.

### F6 — No more remount storm (RC7)
- `App.tsx`: remove `key={workspaceRevision}` from `<main>`; keep the revision counter in the store and expose the index-update event; `ProjectMap` (and any graph-consumer) refreshes data in place instead of being destroyed.
- Result: designers keep state across incremental updates; no duplicate request storms.

### F7 — Get heavy work off the bridge callback thread (RC8)
- Route the synchronous fallback handlers (`getProjectConfig`, legacy `generate*`, `simulateDmnDecision`) through the app executor; responses always via `sendResponse`.
- `JmixProjectService.detectConfig`: keep caching; prefer graph-derived base package only when the graph is already cached (never trigger a synchronous build from detection).

## Verification Gates

1. `cd plugin && ./gradlew phase2CoreTest --dependency-verification=strict` — discovery-layer contract tests incl. new cache store + linking equivalence tests.
2. `cd plugin && ./gradlew :idea262:compileKotlin :idea262:test --dependency-verification=strict` — host-lane compile + shared suite (IDE SDK already in Gradle cache from Aug 3 builds).
3. `cd plugin && ./gradlew :idea262:buildPlugin --dependency-verification=strict` → fresh `jmix-visual-workbench-1.0.0-idea262.zip`.
4. Install into `~/.local/share/JetBrains/IntelliJIdea2026.2/` (IDE closed) and validate against `inteacc-payroll`: startup graph loads from cache, no SEVERE plugin entries, workspace requests complete, Entity Designer renders.
5. `webui`: `npm run build` type-check passes.

## Out of Scope (recorded, not now)

- Legacy direct-apply `generateView`/`generateMenu` preview migration (CONCERNS.md tech debt).
- Frontend test framework introduction (Vitest) — follow-up phase.
- Bridge dispatcher decomposition into handler registry — follow-up phase.

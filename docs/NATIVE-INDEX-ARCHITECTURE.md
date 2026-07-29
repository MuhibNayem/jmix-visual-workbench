# Native Index Architecture

The native IntelliJ assistance layer must never discover Jmix symbols by
enumerating every file during completion, reference resolution, highlighting
or Find Usages. This document defines the production invariant and the
verification required to preserve it.

## Persistent artifact indexes

The plugin registers independent content-sensitive IntelliJ file-based indexes
for:

| Index | Candidate sources |
|---|---|
| Entity declarations | Java and Kotlin files containing a Jmix/JPA entity annotation marker |
| View controller declarations | Java and Kotlin files containing `ViewController` or legacy `UiController` markers |
| Specific permissions | Java and Kotlin files containing `SpecificPolicy` markers |
| Spring menu beans | Java and Kotlin files containing supported Spring/Jakarta bean annotation markers |
| Menu declarations | XML descriptors whose root is `menu-config` or `menu` |
| Shared fetch plans | XML descriptors whose root is `fetchPlans` or `fetch-plans` |
| FlowUI descriptors | XML descriptors whose root is `view` or `fragment` |
| Message bundles | `messages.properties` and locale variants |
| Jmix Studio metadata | Java and Kotlin declarations containing `StudioComponent`, `StudioDataComponent`, `StudioFacet` or `StudioElement` |

Indexers inspect text only and never construct PSI. Classification is
conservative: the native services validate returned candidates with real PSI,
so a false positive is harmless while an unsupported declaration cannot become
a silent false negative.

Every index stores a 64-bit content fingerprint as its forward value. Editing
a candidate therefore advances only that artifact family's modification stamp.
For example, editing a message bundle cannot evict the entity, menu, view or
security inventories.

## Hot-path contract

Native symbol access follows this sequence:

1. Return no index-dependent variants during IntelliJ dumb mode.
2. Bring only the requested artifact index up to date.
3. Compare its modification stamp and the project-root stamp with the cached
   inventory.
4. Return the same cached inventory on a hit without enumerating candidate
   files or parsing PSI.
5. On a miss, retrieve only files classified by that index, validate them with
   PSI and check cancellation while walking files and declarations.
6. Store the completed inventory against the artifact-specific stamp.

Project-root changes are included because dependency and included-build changes
can introduce symbols without editing an existing source file.

FlowUI component metadata is resolved through IntelliJ's annotation stub index
against project and library scope, then cached against the dedicated Studio
metadata content stamp and project-root stamp. This makes the exact Jmix/add-on
dependency versions authoritative for XML injection types and custom
subscriptions without querying the annotation index again on unrelated typing.

Project and dependency discovery uses the union of IntelliJ project-content and
library scopes. It does not use the broader everything/scratch scope. A cold
view-cache fill validates only view-controller files returned by its candidate
index; it does not perform a second global annotation traversal.

The following are prohibited in editor hot paths:

- `FilenameIndex.getAllFilesByExt(...)`;
- `GlobalSearchScope.allScope(...)`;
- `FileTypeIndex`;
- global PSI modification counters as aggregate cache keys;
- project-wide VFS or PSI traversal;
- non-cancellable long read actions;
- index-dependent non-blocking reads without smart-mode scheduling;
- holding a write action while preparing or validating a change.

All JCEF bridge non-blocking reads are expired with the project and scheduled
in smart mode. Remaining synchronous read scopes use cancellable read actions.

`verifyNativeIndexArchitecture` enforces this contract in every aggregate
gate. It scans native IDE sources for prohibited APIs and verifies that the
same nine index implementations are registered in the shared, IntelliJ
2025.3 and IntelliJ 2026.2 descriptors. A broad-scan or registration regression
therefore fails the build before packaging.

## Scale regression

`JmixNativeIndexScaleTest` creates 3,000 unrelated XML, properties and Java
files across sixteen module-shaped roots alongside real entities, views, menus,
messages, fetch plans, permissions and Spring menu beans. It proves that:

- every real symbol remains discoverable;
- FlowUI discovery excludes unrelated XML;
- 100 repeated warm reads reuse the exact seven symbol inventories and Studio
  metadata snapshot;
- adding unrelated files does not evict any inventory;
- 20 consecutive in-place typing cycles across unrelated XML, properties and
  Java files preserve the identity of all seven inventories and the metadata
  snapshot;
- editing a real message bundle replaces only the message inventory while all
  other inventories retain object identity;
- editing a real Studio metadata declaration replaces only the metadata
  snapshot while all symbol inventories retain object identity;
- warm and typing-cycle p50/p95/p99 latency stays within explicit budgets;
- warm and incremental lookup stay below the explicit two-second ceiling and
  the repeated-typing loop stays below its five-second ceiling on both
  supported IntelliJ hosts.

Observed in the 2026-07-29 milestone run:

| Host | 100 warm reads total (p95/p99) | Lookup after unrelated edits | 20 three-file typing cycles total (p95/p99) | Relevant message edit |
|---|---:|---:|---:|---:|
| IntelliJ IDEA 2025.3 | 5 ms (0/0 ms) | 2 ms | 84 ms (6/6 ms) | 5 ms |
| IntelliJ IDEA 2026.2 | 9 ms (0/0 ms) | 4 ms | 134 ms (12/12 ms) | 8 ms |

The light-fixture gate is a deterministic regression, not a substitute for an
installed-IDE benchmark. Release certification must additionally publish cold
indexing, completion p50/p95/p99, navigation, memory and leak results on
representative 16–100-module repositories.

## Extension rule

Any new native symbol family must provide:

1. an independent index or a proven key-specific invalidation mechanism;
2. a content-sensitive forward value;
3. a candidate classifier that does not use PSI;
4. PSI validation with cancellation checks;
5. dumb-mode behavior;
6. an unrelated-edit cache-stability test;
7. a candidate-change invalidation test;
8. coverage on every supported IntelliJ host.

Adding a convenience fallback that scans all files violates this architecture,
even if it appears fast on a small sample project.

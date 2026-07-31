# Compatibility

Host compatibility and target-project compatibility are independent contracts.
An IntelliJ host JVM runs the plugin; a target-project JDK compiles a Jmix
application. One does not imply the other.

## IntelliJ host lanes

| Artifact | Descriptor range | Host JVM floor | Automated evidence |
| --- | --- | --- | --- |
| `jmix-visual-workbench-1.0.0-idea253.zip` | IU build 253.* | JBR/Java 21 | Compiles and packages against exact IDEA Ultimate 2025.3; Plugin Verifier passed IU-253.28294.334 |
| `jmix-visual-workbench-1.0.0-idea262.zip` | IU build 262.* | JBR/Java 25 | Compiles and packages against exact IDEA Ultimate 2026.2 with explicit JCEF modules; Plugin Verifier passed IU-262.8665.258 |

The 253 descriptor range covers only the verified IntelliJ 253 branch on the
JBR/Java 21 host line. The 262 descriptor is a separate JBR/Java 25 artifact.
Intermediate branches remain unsupported until each has exact compile,
Plugin Verifier, and installation evidence.
Both ZIPs contain the same plugin ID/version and frontend input digest.

Automated compilation, smoke, package-content, and Plugin Verifier checks are
green. Manual installation and workbench opening in the two minimum IDEs remain
pending; the host lanes are not presented as release-certified until that
checkpoint is recorded.

## Target Jmix generated-code matrix

The compatibility gate generates its source corpus directly from the production
Java/Kotlin generators and compiles it against exact public Jmix artifacts. It
does not compile a hand-maintained imitation. Every cell contains a JPA/Jmix
entity, an advanced `JmixDataRepository`, a FlowUI detail controller and a
transactional aggregate update service. The corpus deliberately exercises the
repository fetch-plan type/annotation name collision, named JPQL parameters,
paging, query hints, constraints and the Jmix 3 `SaveDelegate` /
`RemoveDelegate` contracts. Each line also compiles the production-generated
Kafka durable-outbox adapter with its matching Jackson 2/3 API, JDBC leases,
Jmix permission/audit boundary, Micrometer metrics, Observation tracing and
Spring Kafka broker acknowledgement contract.

| Exact Jmix | Target-project JDK | Languages | Generated artifacts | Current evidence |
| --- | --- | --- | --- | --- |
| 2.8.2 | 17 | Java and Kotlin | entity, repository, FlowUI controller, aggregate update service, durable integration outbox | Strict compile passed; class major 61 |
| 2.8.2 | 21 | Java and Kotlin | entity, repository, FlowUI controller, aggregate update service, durable integration outbox | Strict compile passed; class major 65 |
| 3.0.0 | 21 | Java and Kotlin | entity, repository, FlowUI controller, aggregate update service, durable integration outbox | Strict compile passed; class major 65 |
| 3.0.0 | 25 | Java and Kotlin | entity, repository, FlowUI controller, aggregate update service, durable integration outbox | Strict compile passed; class major 69 |

This follows Jmix's published runtime boundary: the Jmix 2 line supports Java
17/21, while [Jmix 3.0 requires Java 21 or
25](https://docs.jmix.io/jmix/whats-new/release-3.0.html). Exact framework
versions are pinned to the official [2.8.2 and 3.0.0
releases](https://github.com/jmix-framework/jmix/releases). Dependencies are
resolved only from Maven Central and the group-filtered official Jmix public
repository, with reviewed SHA-256 verification metadata. Target JDKs are
self-provisioned by Gradle when absent; no preinstalled Node.js or full JDK
matrix is assumed.

`certifyGeneratedCodeCompatibility` is release-blocking through
`phase1Check`. Its deterministic evidence report records:

- the production-generated source-manifest digest;
- exact Jmix and target JDK cell;
- compiler vendor/runtime;
- asserted Java/Kotlin class-file major version;
- class counts;
- resolved compile-classpath count and aggregate SHA-256 digest.

The report is written to
`plugin/build/reports/compatibility/generated-code-certification.json`.

## Write-compatibility boundary

Compilation evidence proves that the covered generated contracts are valid for
the exact cells above. It does **not** by itself certify every visual mutation,
database, add-on, application startup or runtime business flow.

The Phase 2 compatibility-registry parser/evaluator and its fail-closed
read-only decisions are implemented and release-tested. Production mutation
services additionally revalidate project trust, index health, exact source
ownership, revisions and operation-specific invariants. Earlier Jmix 2.x, Jmix
1.x, CUBA-era, future, ambiguous, stale, untrusted or otherwise uncertified
profiles remain diagnostic/read-only unless an operation has its own stronger
evidence. Exact generated-code certification will not be used to imply blanket
write authorization.

The shared write pipeline also rechecks every revision after it owns IntelliJ's
write lock, snapshots document-aware source values and absent parent
directories, verifies the complete approved result, and records undo only
after verification. Any injected partial write, cancellation or undo/redo
failure restores and verifies the entire prior file and directory topology.
Concurrent edits between outer preflight and write-lock acquisition are
preserved and reject the whole plan. Native repository injection uses its
language PSI but shares the locked-document and exact-restoration contract.
`verifyMutationArchitecture` release-blocks any newly introduced direct write
primitive outside those reviewed boundaries and ensures failure injection
cannot cross the JCEF bridge.

This evidence covers deterministic one-shot failures. It does not claim that a
persistent operating-system or hardware failure which also prevents restoration
can be made atomically reversible across multiple physical files. Crash/power
loss recovery, installed-IDE filesystem fault testing, and IDE-owned refactor
certification remain separate release gates.

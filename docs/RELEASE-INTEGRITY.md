# Release Integrity

Phase 1 establishes build-input and CI integrity. It does not claim the signed,
SBOM-backed, provenance-attested public release process planned for Phase 8.

## Enforced now

- The Gradle 9.5.1 wrapper distribution and wrapper JAR have exact SHA-256
  checksums.
- Gradle, Kotlin, IntelliJ Platform, Foojay, Node plugin/runtime, Gson, and IDE
  coordinates are exact.
- The idea253 and idea262 `runtimeClasspath` and `testRuntimeClasspath`
  configurations use strict checked-in lock state.
- `plugin/gradle/verification-metadata.xml` records reviewed SHA-256 values for
  Gradle-resolved build inputs. Exact macOS ARM64 DMGs and Linux x64 IDEA
  archives use JetBrains' published SHA-256 sidecars; the Linux x64 Node archive
  uses the Node.js signed `SHASUMS256.txt`. Strict verification is mandatory in
  Ubuntu CI.
- npm uses lockfile v3 and `npm ci`; the build fails if that command changes
  `package-lock.json`.
- CI actions use immutable full commit SHAs with readable version comments.
- CI validates the wrapper before execution, provisions only a Java 21
  bootstrap JDK, lets Foojay provision exact Java 21/25 compiler toolchains,
  and invokes only `plugin/gradlew`.
- ZIP inspection rejects Node/npm caches, source maps, stale identity,
  credentials patterns, and developer paths from plugin artifacts.

The root verification metadata intentionally uses SHA-256 checksums without a
trusted-key block. Publisher signatures are not consistently available for the
resolved graph; pretending otherwise would weaken review. Trusted keys may be
added only when their ownership and scope are reviewed.

The optional extracted local-SDK seam is narrower than the remote CI path.
Gradle represents an extracted SDK root as a directory pseudo-artifact, which
cannot receive a file checksum. Verification metadata therefore trusts only
the exact `localIde:IU:IU-253.28294.334` and
`localIde:IU:IU-262.8665.258` coordinates; each host independently rejects a
path whose `Resources/build.txt` does not match its expected branch. CI does
not use this exception.

## Updating locks and checksums

Lock only the four standard runtime configurations using the commands in
`plugin/gradle/dependency-locks/README.md`. Then run the successful affected
build tasks with Gradle's
`--write-verification-metadata sha256` option. Review every new component,
artifact, repository implication, and checksum before committing.

Normal builds must be read-only:

```text
cd plugin
./gradlew snapshotLockHashes verifyLockedConfigurations verifyDependencyIntegrity compareLockHashes --dependency-verification=strict
```

`compareLockHashes` proves resolution did not rewrite the reviewed lock files.
CI rejects dependency-verification bypass flags.

## Artifact checksums

The two lane ZIPs are deterministic in entry ordering and timestamps, but their
embedded build manifest includes the current source revision. Therefore a ZIP
checksum identifies one revision, not a permanent filename. For a candidate
revision:

```text
shasum -a 256 plugin/hosts/idea253/build/distributions/jmix-visual-workbench-1.0.0-idea253.zip
shasum -a 256 plugin/hosts/idea262/build/distributions/jmix-visual-workbench-1.0.0-idea262.zip
```

GitHub Actions uploads these as short-lived, non-release artifacts together
with test and Plugin Verifier reports.

## Future signing, SBOM, and publication interfaces

Future signing tasks may consume the conventional secret names
`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD`; publication may
consume `PUBLISH_TOKEN`. Only names are documented here. Values must remain in
the CI secret store, must never be echoed, committed, cached, or uploaded, and
must not be required for ordinary contributor builds.

Phase 8 must add JVM and frontend SBOMs, immutable candidate checksums,
provenance metadata, signature-verification results, installed-plugin evidence,
and release approval. None of those outputs is represented as complete by this
document.

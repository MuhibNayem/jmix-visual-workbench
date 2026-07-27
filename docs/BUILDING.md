# Building Jmix Visual Workbench

The supported build entry point is the checked-in Gradle wrapper. A global
Gradle installation and a global Node installation are neither used nor
supported by the build contract.

## Prerequisites

- Git
- A Java 21 bootstrap JDK capable of launching Gradle 9.5.1
- Network access on the first build

From the repository root:

```text
cd plugin
./gradlew clean phase1Check --dependency-verification=strict
```

Gradle downloads Node 24.18.0 into the build-owned `plugin/build/nodejs`
directory, runs `npm ci` against `webui/package-lock.json`, builds the current
React/Vite sources, and packages the result into both host plugins. It never
calls a global `node`, `npm`, or `gradle` executable. Installed plugins load
only their bundled static resources and do not download or execute Node.

The Foojay resolver 1.0.0 supplies missing Eclipse Temurin compilation
toolchains through the Gradle cache:

- Java 21 compiles the verified IDEA 253 lane.
- Java 25 compiles the IDEA 262 lane.

`verifyHostToolchains` checks the actual compiler launcher metadata. These
compiler JDKs are separate from the Java versions used by a developer's target
Jmix project.

## First run and offline use

The first clean build needs network access for the Gradle 9.5.1 distribution,
Node 24.18.0 archive, Java 21/25 toolchains when absent, exact IntelliJ IDEA
Ultimate 2025.3 and 2026.2 platform inputs, Maven/Plugin Portal dependencies,
and Plugin Verifier. All versions and checksums remain controlled by repository
files.

After those exact inputs are cached, an offline reproduction can use:

```text
cd plugin
./gradlew clean phase1Check --offline --dependency-verification=strict
```

Offline mode is not a substitute for a populated cache. It fails rather than
selecting different platform, toolchain, Node, or dependency versions.

The optional `-PlocalIdeaPath=/absolute/path` development seam may be used when
running one host build directly. For an aggregate local build, pass both
`-PlocalIdea253Path=/absolute/path/to/IU-253` and
`-PlocalIdea262Path=/absolute/path/to/IU-262`. Each lane validates
`Resources/build.txt` and rejects a local SDK from the wrong IU build branch.
CI and fresh aggregate builds do not set these properties: they retain the
exact remote `intellijIdeaUltimate("2025.3")` and
`intellijIdeaUltimate("2026.2")` coordinates.

## Outputs

The complete gate creates:

```text
plugin/hosts/idea253/build/distributions/jmix-visual-workbench-1.0.0-idea253.zip
plugin/hosts/idea262/build/distributions/jmix-visual-workbench-1.0.0-idea262.zip
```

Test reports live below each lane's `build/reports/tests/` directory. Plugin
Verifier reports live below each lane's `build/reports/pluginVerifier/`
directory. `verifyPluginZipContents` checks both ZIPs for the packaged web
entry point, hashed assets, same-revision build manifest, original icon,
license/notice files, and forbidden Node/cache/developer-path payloads.

These are development and CI artifacts, not signed public releases. See
[Release Integrity](RELEASE-INTEGRITY.md).

## Focused integrity checks

The read-only lock/integrity gate is:

```text
cd plugin
./gradlew snapshotLockHashes verifyLockedConfigurations verifyDependencyIntegrity compareLockHashes --dependency-verification=strict
```

Dependency lock and checksum changes require explicit regeneration and review;
normal verification and CI never pass `--write-locks` or
`--write-verification-metadata`.

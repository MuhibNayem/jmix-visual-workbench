# Dependency lock scope

Phase 1 locks only the two standard resolvable runtime configurations in each
isolated IntelliJ host build:

- `hosts/idea253/gradle/dependency-locks/gradle.lockfile`
- `hosts/idea262/gradle/dependency-locks/gradle.lockfile`

Each file contains `runtimeClasspath` and `testRuntimeClasspath`. IntelliJ
archive, instrumentation, detached, and Plugin Verifier target configurations
are intentionally excluded because they are platform artifacts rather than the
plugin's standard JVM dependency graph. Those resolved artifacts are instead
covered by the root `gradle/verification-metadata.xml` SHA-256 policy.

Regenerate one configuration at a time from `plugin/`:

```text
./gradlew -p hosts/idea253 dependencies --configuration runtimeClasspath --write-locks
./gradlew -p hosts/idea253 dependencies --configuration testRuntimeClasspath --write-locks
./gradlew -p hosts/idea262 dependencies --configuration runtimeClasspath --write-locks
./gradlew -p hosts/idea262 dependencies --configuration testRuntimeClasspath --write-locks
```

Lock changes require review. Normal CI and verification must never use
`--write-locks`; `snapshotLockHashes` and `compareLockHashes` prove the checked-in
state is unchanged by read-only resolution.

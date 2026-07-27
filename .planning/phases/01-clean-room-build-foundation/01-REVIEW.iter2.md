---
phase: 01-clean-room-build-foundation
reviewed: 2026-07-27T20:49:32Z
depth: standard
files_reviewed: 42
files_reviewed_list:
  - .github/dependabot.yml
  - .github/workflows/ci.yml
  - CLEAN_ROOM.md
  - CONTRIBUTING.md
  - LICENSE
  - README.md
  - SECURITY.md
  - THIRD_PARTY_NOTICES.md
  - TRADEMARKS.md
  - docs/BUILDING.md
  - docs/COMPATIBILITY.md
  - docs/RELEASE-INTEGRITY.md
  - plugin/build.gradle.kts
  - plugin/buildSrc/src/main/java/org/jmixworkbench/build/AssembleWebBundleTask.java
  - plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyPluginZipContentsTask.java
  - plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyWebBundleTask.java
  - plugin/gradle.properties
  - plugin/gradle/dependency-locks/README.md
  - plugin/gradle/libs.versions.toml
  - plugin/gradle/verification-metadata.xml
  - plugin/gradle/wrapper/gradle-wrapper.jar
  - plugin/gradle/wrapper/gradle-wrapper.properties
  - plugin/gradlew
  - plugin/gradlew.bat
  - plugin/hosts/idea253/build.gradle.kts
  - plugin/hosts/idea253/gradle/dependency-locks/gradle.lockfile
  - plugin/hosts/idea253/src/main/resources/META-INF/plugin.xml
  - plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253PluginSmokeTest.kt
  - plugin/hosts/idea262/build.gradle.kts
  - plugin/hosts/idea262/gradle/dependency-locks/gradle.lockfile
  - plugin/hosts/idea262/src/main/resources/META-INF/plugin.xml
  - plugin/hosts/idea262/src/test/kotlin/org/jmixworkbench/host/idea262/Idea262PluginSmokeTest.kt
  - plugin/settings.gradle.kts
  - plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt
  - plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt
  - plugin/src/main/resources/META-INF/plugin.xml
  - plugin/src/main/resources/icons/workbench.svg
  - plugin/src/test/kotlin/org/jmixworkbench/toolwindow/WorkbenchUiResourceResolverTest.kt
  - webui/index.html
  - webui/package-lock.json
  - webui/package.json
  - webui/src/App.tsx
findings:
  critical: 2
  warning: 6
  info: 3
  total: 11
status: issues_found
---

# Phase 1: Code Review Report

**Reviewed:** 2026-07-27T20:49:32Z
**Depth:** standard
**Files Reviewed:** 42
**Status:** issues_found

## Summary

The clean-room policy and identity work is internally consistent, the checked-in
wrapper JAR matches its asserted SHA-256 value, the XML/JSON inputs are
well-formed, and the composite build has a clear separation between aggregate
and host lanes. However, the phase is not ready to serve as its claimed CI and
security foundation.

The strict Ubuntu CI job cannot consume the checked-in dependency-verification
metadata because the metadata contains only macOS ARM64 Node and IntelliJ
archives. Separately, the tool-window factory gives any URL supplied through
`jmixworkbench.dev.url` the same privileged bridge as the packaged UI, including
the existing project-writing commands. Additional warnings cover JCEF disposal,
invalid generated XML namespaces, an unverified six-branch compatibility claim,
smoke tests that never start the tool window, and incomplete resource/artifact
inspection.

## Critical Issues

### CR-01: Strict Ubuntu CI has only macOS ARM64 verification metadata

**Files:**

- `.github/workflows/ci.yml:19`
- `.github/workflows/ci.yml:39-41`
- `plugin/gradle/verification-metadata.xml:2819-2827`
- `plugin/gradle/verification-metadata.xml:3701-3704`

**Issue:** The only CI runner is `ubuntu-latest`, and it invokes the build with
`--dependency-verification=strict`. The verification metadata contains only
`idea-2025.3-aarch64.dmg`, `idea-2026.2-aarch64.dmg`, and
`node-24.18.0-darwin-arm64.tar.gz`. The Linux x64 IDEA archives and Node archive
that the runner must resolve have no reviewed checksums, so a clean CI run will
fail strict dependency verification before completing the advertised gate. The
local macOS proof therefore does not establish that the checked-in CI workflow
works.

**Fix:** Resolve the exact build on Linux x64 in a controlled update, add the
reviewed Linux Node and IDEA archive checksums (and any platform-specific
derived artifacts Gradle requests), then run the workflow-equivalent command in
a clean Linux environment:

```text
cd plugin
./gradlew clean phase1Check \
  --dependency-verification=strict \
  --no-daemon \
  --no-configuration-cache \
  --stacktrace
```

Keep the macOS hashes as additional supported-workstation inputs rather than
replacing them.

### CR-02: Arbitrary development pages receive the privileged project bridge

**Files:**

- `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt:24-31`
- `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt:69-80`
- `plugin/src/test/kotlin/org/jmixworkbench/toolwindow/WorkbenchUiResourceResolverTest.kt:30-37`

**Issue:** Any non-blank `jmixworkbench.dev.url` value bypasses packaged-resource
resolution without scheme, host, or origin validation. The factory then creates
`JcefBridge` before loading that URL. Consequently, a remote or compromised
development page receives the same bridge as the trusted packaged UI and can
invoke the existing generation/write commands against the open project. The
test positively codifies arbitrary HTTP URL acceptance but does not assert a
safe origin policy. This violates the project's stated rule that JCEF content is
untrusted and that privileged commands must be independently constrained.

**Fix:** Do not attach the mutating bridge to externally supplied content. At a
minimum, parse the development URI, allow only an explicit loopback origin in a
separately enabled development mode, reject credentials/fragments/unexpected
ports, and enforce the same exact origin in the browser/bridge request boundary.
Until command-level authorization and validation exist, expose only a read-only
development bridge or load the packaged classpath URL:

```kotlin
val uiUri = WorkbenchUiResourceResolver.resolve(...).toURI()
check(uiUri.scheme == "jar" || isExplicitReadOnlyLoopbackDevOrigin(uiUri))

val bridge = if (uiUri.scheme == "jar") {
    JcefBridge(project, browser)
} else {
    ReadOnlyDevelopmentBridge(project, browser)
}
```

Add rejection tests for `https://example.com`, non-loopback HTTP hosts,
userinfo, and origin changes.

## Warnings

### WR-01: The JCEF browser is not disposed with tool-window content

**File:** `plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt:78-84`

**Issue:** The content disposer calls only `bridge.dispose()`. That releases the
`JBCefJSQuery`, but it does not dispose `JBCefBrowser` or its native CEF
resources. Closing/recreating project tool windows can therefore retain browser
clients, handlers, and native resources for the life of the IDE process.

**Fix:** Dispose both objects in the content lifecycle, in dependency order:

```kotlin
content.setDisposer {
    bridge.dispose()
    browser.dispose()
}
```

Prefer registering both with IntelliJ's `Disposer` under one parent disposable
if the supported platform API requires that pattern.

### WR-02: View generation emits invalid duplicate namespace declarations

**File:** `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt:20-25`

**Also affected:** `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt:276-280`

**Issue:** Both generators call `ns("", NS_LAYOUT)` and `ns("data", NS_DATA)`,
then add `xmlns` and `xmlns:data` again as attributes. The current `XmlBuilder`
renders the empty prefix as `xmlns:="..."` and renders `xmlns:data` a second
time, producing XML that is not namespace-well-formed. The Phase 1 compile and
smoke gates do not generate and parse even one descriptor, so the build passes
while this canonical generator remains unusable.

**Fix:** Use one namespace mechanism only. With the current builder, remove the
two `ns(...)` calls and retain the valid attributes:

```kotlin
attr("xmlns", NS_LAYOUT)
attr("xmlns:data", NS_DATA)
```

Add a generator test that parses list, detail, and fragment output with a
namespace-aware XML parser.

### WR-03: The IDEA 253 artifact advertises six unverified IDE branches

**Files:**

- `plugin/hosts/idea253/build.gradle.kts:168-181`
- `docs/COMPATIBILITY.md:9-20`
- `plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253PluginSmokeTest.kt:19-25`

**Issue:** The descriptor permits installation on branches 253 through 261, but
the build compiles against IDEA 2025.3 and `pluginVerification.ides.current()`
verifies only that exact 253 SDK. The compatibility test merely asserts that
the broad descriptor text is present. No compilation, Plugin Verifier run, or
installation smoke evidence covers branches 254-261, despite the project rule
that compatibility must be verified rather than claimed. API removal or module
changes in any intermediate branch can therefore produce an installable but
unloadable plugin.

**Fix:** Either narrow `untilBuild` to the actually verified branch (for
example, `253.*`) or add exact IDE inputs and Plugin Verifier evidence for every
advertised branch before widening the descriptor:

```kotlin
pluginVerification {
    ides {
        // Add one reviewed exact IDE per supported branch.
    }
}
```

Make the compatibility document and test derive from that verified matrix
rather than asserting a hand-written range.

### WR-04: Host “smoke” tests never exercise tool-window startup

**Files:**

- `plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253PluginSmokeTest.kt:15-35`
- `plugin/hosts/idea262/src/test/kotlin/org/jmixworkbench/host/idea262/Idea262PluginSmokeTest.kt:14-34`

**Issue:** `assertNotNull(JmixWorkbenchToolWindowFactory())` only invokes an
empty constructor. It does not call `createToolWindowContent`, create/dispose a
browser, attach the bridge, load the packaged URL, or verify fallback content
through the IntelliJ content manager. Both tests can stay green when runtime
startup or lifecycle behavior is broken. Calling `hostSmokeTest` is also only
an alias for the ordinary `test` task, so it provides no additional installed
or sandboxed execution.

**Fix:** Add an IntelliJ fixture or seam that invokes
`createToolWindowContent` for supported and unsupported JCEF cases and asserts
content creation plus disposal. If a true host smoke test is not yet available,
rename the current task/tests to descriptor/resource unit tests so the gate
does not overstate their evidence.

### WR-05: ZIP inspection skips payloads and does not implement its credential claim

**Files:**

- `plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyPluginZipContentsTask.java:44-49`
- `plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyPluginZipContentsTask.java:89-112`
- `plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyPluginZipContentsTask.java:187-207`
- `docs/RELEASE-INTEGRITY.md:23-24`

**Issue:** The release-integrity document says ZIP inspection rejects
credential patterns, but `FORBIDDEN_CONTENT` contains only stale identity and
two developer-path forms. The task content-scans nested JARs only until it finds
the first JAR containing `META-INF/plugin.xml`, then returns; subsequent JARs
and non-JAR outer entries are never content-scanned. It also does not reject
absolute or `..` archive entry names. Secrets, stale identity, developer paths,
or traversal-shaped entries can therefore be present in an artifact that passes
the stated gate.

**Fix:** First scan every outer entry and every nested JAR entry, rejecting
absolute/traversal names after normalized containment checks. Apply reviewed
credential/private-key patterns to all text-like payloads, with explicit
allowlists for known test fixtures. Separately identify the one main plugin JAR
and perform descriptor/resource validation without returning before the full
archive scan completes.

### WR-06: Web-bundle verification is incomplete and not containment-safe

**Files:**

- `plugin/buildSrc/src/main/java/org/jmixworkbench/build/VerifyWebBundleTask.java:66-75`
- `plugin/hosts/idea253/build.gradle.kts:185-204`
- `plugin/hosts/idea262/build.gradle.kts:187-206`

**Issue:** The shared verifier normalizes referenced paths but never checks that
the result remains below `bundleDirectory`, so a `../` reference can be
satisfied by a file outside the bundle. The host-local verifiers do not inspect
referenced assets at all; they only compare the source digest and check
`index.html`/`build-info.json`. A direct host `buildPlugin` can therefore
package a bundle whose referenced JavaScript or CSS is missing. The aggregate
ZIP gate catches common missing assets later, but direct lane builds and the
shared verifier's own path-safety contract remain unsound.

**Fix:** Reuse one verifier in all three builds and enforce containment before
checking a resource:

```java
Path candidate = bundleDirectory.resolve(resource).normalize();
if (!candidate.startsWith(bundleDirectory) || !Files.isRegularFile(candidate)) {
    throw new IllegalStateException("Invalid or missing bundled resource: " + resource);
}
```

Reject absolute paths and backslash traversal explicitly, and add tests for
missing, absolute, and `../` references.

## Info

### IN-01: Phase-added direct build dependencies are absent from the notice inventory

**File:** `THIRD_PARTY_NOTICES.md:3-24`

**Issue:** The document describes itself as the direct build/runtime dependency
inventory, but it omits Phase 1 additions such as the Node Gradle plugin,
Foojay toolchain resolver, Node runtime, Plugin Verifier, and test dependencies.
This makes the hand-maintained inventory incomplete under its own update rule.

**Fix:** Add every direct Phase 1 build/test/runtime component with its role and
reviewed license, or narrow the document's stated scope until a generated
inventory is authoritative.

### IN-02: The application destructures an unused toast action

**File:** `webui/src/App.tsx:21-22`

**Issue:** `addToast` is read from the Zustand store but never used.

**Fix:** Remove it from the destructuring assignment until project-config load
errors are surfaced through a toast.

### IN-03: Data-grid generation retains dead parameters and a no-op branch

**File:** `plugin/src/main/kotlin/org/jmixworkbench/generator/ViewXmlGenerator.kt:219-253`

**Issue:** `view` is never used, and `listActions` is computed only to enter an
empty `if` block. The dead branch suggests toolbar generation exists when it
does not and makes the recently repaired helper harder to verify.

**Fix:** Remove the unused parameter and no-op block, or implement and test the
intended toolbar output.

---

_Reviewed: 2026-07-27T20:49:32Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

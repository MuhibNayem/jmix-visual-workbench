---
phase: 01-clean-room-build-foundation
reviewed: 2026-07-27T21:19:50Z
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
  critical: 0
  warning: 1
  info: 0
  total: 1
status: issues_found
---

# Phase 1: Code Review Report

**Reviewed:** 2026-07-27T21:19:50Z
**Depth:** standard
**Files Reviewed:** 42
**Status:** issues_found

## Summary

The iteration resolves ten of the eleven prior findings. Linux x64 verification
metadata now matches the pinned `ubuntu-24.04` runner for both IDEA archives and
the Node archive, and CI invokes the complete strict `phase1Check` gate without
an ignore-failures, skip, or verification-bypass option. Development content no
longer receives the project bridge; the content disposer owns both bridge and
browser; generated view XML is namespace-well-formed; the IDEA 253 claim is
narrowed to `253.*`; ZIP and web-resource validation now scan and contain the
reviewed payloads; dependency notices are complete; and the two prior dead-code
items are removed.

One warning remains. The host tests exercise extracted startup and lifecycle
helpers, but still do not execute the real tool-window factory or prove those
helpers are wired into IntelliJ content creation. The `hostSmokeTest` tasks also
remain aliases of the ordinary unit-test task.

Focused build-logic tests passed. The strict offline `phase1FastCheck` passed,
and both host test suites passed against the exact cached IU-253.28294.334 and
IU-262.8665.258 SDKs. XML inputs and the fix-range diff check also passed.

## Warnings

### WR-01: Host smoke tests still bypass real tool-window startup wiring

**Files:**

- `plugin/hosts/idea253/src/test/kotlin/org/jmixworkbench/host/idea253/Idea253PluginSmokeTest.kt:30-52`
- `plugin/hosts/idea262/src/test/kotlin/org/jmixworkbench/host/idea262/Idea262PluginSmokeTest.kt:29-51`
- `plugin/hosts/idea253/build.gradle.kts:269-272`
- `plugin/hosts/idea262/build.gradle.kts:271-274`

**Issue:** The tests call `WorkbenchToolWindowStartup.plan`,
`WorkbenchFallbackPanel`, and a separately constructed
`WorkbenchBrowserLifecycle`. They never invoke
`JmixWorkbenchToolWindowFactory.createToolWindowContent`, create content through
the IntelliJ content manager, instantiate the real `JBCefBrowser`/`JcefBridge`
path, or dispose the actual content object. Consequently, removing the disposer
from `createBrowserContent`, attaching a bridge for development content, failing
to add content, or breaking browser URL loading can still leave both host suites
green. The `hostSmokeTest` task adds no separate evidence because it only
depends on the same ordinary `test` task.

**Fix:** Add an IntelliJ fixture or injected browser/content seam that invokes
`createToolWindowContent` through the real factory and asserts:

1. packaged content creates one browser-backed content item with project bridge
   access;
2. development content creates browser content without a project bridge;
3. unsupported JCEF, a missing bundle, and a rejected development URL add the
   expected fallback content; and
4. disposing the created content disposes the actual bridge before the actual
   browser exactly once.

Register `hostSmokeTest` as a dedicated `Test` task (or test suite) selecting
those startup-integration tests instead of aliasing the complete unit-test task.

---

_Reviewed: 2026-07-27T21:19:50Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

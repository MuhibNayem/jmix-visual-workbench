---
status: diagnosed
phase: 01-clean-room-build-foundation
source: [01-VERIFICATION.md]
started: 2026-07-27T21:43:47Z
updated: 2026-07-28T03:48:48Z
---

## Current Test

number: 1
name: IDEA 2025.3 installation and open
expected: |
  Install the idea253 ZIP in exact IDEA Ultimate 2025.3, open a disposable
  project, and open Jmix Visual Workbench; the packaged UI renders with no
  class-loading, JCEF, descriptor, or missing-resource error.
awaiting: gap fix and retest

## Tests

### 1. IDEA 2025.3 installation and open
expected: Install the idea253 ZIP in exact IDEA Ultimate 2025.3, open a disposable project, and open Jmix Visual Workbench; the packaged UI renders with no class-loading, JCEF, descriptor, or missing-resource error.
result: issue
reported: "The tool window opens, but Chromium displays 'This site can’t be reached' for the packaged jar:file URL with ERR_UNKNOWN_URL_SCHEME."
severity: blocker

### 2. IDEA 2026.2 installation and open
expected: Install the idea262 ZIP in exact IDEA Ultimate 2026.2 and open Jmix Visual Workbench; the packaged UI renders through the explicit JCEF dependency with no startup or resource error.
result: [pending]

### 3. Identity and clean-room review
expected: The installed name, icon, descriptor copy, README, and policy documents present an original independent product, create no Haulmont endorsement impression, contain no proprietary Studio material, and state acceptable provenance rules.
result: [pending]

## Summary

total: 3
passed: 0
issues: 1
pending: 2
skipped: 0
blocked: 0

## Gaps

- truth: "The packaged web UI renders when the installed IDEA 2025.3 plugin tool window opens."
  status: failed
  reason: "Installed-product UAT observed ERR_UNKNOWN_URL_SCHEME because JCEF cannot navigate directly to the packaged jar:file URL."
  severity: blocker
  test: 1
  root_cause: "Packaged startup converts the classpath entry point to a JVM jar:file URL and passes it directly to JBCefBrowser.loadURL. Chromium has no jar protocol handler. Existing tests only recorded that URL in a fake browser, while ZIP and Plugin Verifier gates checked presence/compatibility rather than performing a real navigation."
  artifacts:
    - path: "plugin/src/main/kotlin/org/jmixworkbench/toolwindow/JmixWorkbenchToolWindowFactory.kt"
      issue: "Packaged startup passes a jar:file URL to JBCefBrowser.loadURL."
    - path: "plugin/src/test/kotlin/org/jmixworkbench/toolwindow/WorkbenchToolWindowFactoryIntegrationTest.kt"
      issue: "The fake browser asserted URL forwarding but could not exercise Chromium protocol handling."
    - path: ".planning/debug/jcef-jar-unknown-url-scheme.md"
      issue: "Root-cause evidence and cross-lane API analysis."
  missing:
    - "Register an exact private packaged-resource origin through JCEF request/resource handlers before navigation."
    - "Normalize and constrain classpath paths, methods, MIME types, response headers, and handler disposal."
    - "Replace fake jar-URL assertions with provider security tests, packaged-origin integration checks, and real signed-IDE UAT on both host lanes."
  debug_session: ".planning/debug/jcef-jar-unknown-url-scheme.md"

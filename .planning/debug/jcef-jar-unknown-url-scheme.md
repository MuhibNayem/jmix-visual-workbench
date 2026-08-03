---
status: resolved
trigger: "The installed IDEA 2025.3 plugin tool window opens, but Chromium displays “This site can’t be reached” for a jar:file URL with ERR_UNKNOWN_URL_SCHEME."
created: 2026-07-28T03:42:35Z
updated: 2026-07-28T03:48:48Z
---

## Current Focus

hypothesis: confirmed — packaged startup forwards a JVM `jar:file:` classpath URL directly to Chromium
test: trace the packaged URL from classpath resolution through the browser adapter and compare it with JetBrains' documented distribution-resource loading contract
expecting: a real JCEF navigation cannot resolve the JVM-only `jar:` protocol
next_action: implement and verify a bounded packaged-resource origin backed by JCEF request handlers

## Symptoms

expected: Exact signed IntelliJ IDEA Ultimate 2025.3 opens Jmix Visual Workbench and renders the packaged UI without JCEF/resource errors.
actual: The tool window opens but Chromium displays “This site can’t be reached” for a jar:file URL.
errors: ERR_UNKNOWN_URL_SCHEME
reproduction: Phase 1 HUMAN-UAT Test 1 in an isolated official signed IDEA 2025.3 installed-product run.
started: Discovered during real installed-product UAT after automated host smoke tests passed.

## Eliminated

- Missing packaged entry point: the built resources and both distribution checks contain `/webui/index.html`.
- Broken relative bundle output: the generated entry point references one local JavaScript asset and one local CSS asset under `./assets/`.
- JCEF absence: the installed tool window created a real Chromium error page, so the supported-JCEF branch was reached.
- Bridge initialization as the initiating fault: navigation fails at the URL scheme before the document or its bridge client can execute.

## Evidence

- timestamp: 2026-07-28T03:42:35Z
  checked: requested branch ancestry guard
  found: git merge-base HEAD b8c41082fe49a344b91eab4b46fc59db932f77c1 returned b8c41082fe49a344b91eab4b46fc59db932f77c1
  implication: current branch descends from the requested baseline; no history modification was performed

- timestamp: 2026-07-28T03:46:00Z
  checked: packaged runtime path in `JmixWorkbenchToolWindowFactory.kt`
  found: `WorkbenchUiResourceResolver` converts `/webui/index.html` to `URL.toExternalForm()`, producing `jar:file:...!/webui/index.html`; `createBrowserContent` passes that string to `JBCefBrowser.loadURL`.
  implication: Chromium receives a JVM classloader URL without any protocol adapter or resource handler.

- timestamp: 2026-07-28T03:46:30Z
  checked: existing resolver, factory integration, host smoke, ZIP, and verifier coverage
  found: the unit/integration browser is a fake that only records `loadedUrls`; archive checks prove resource presence and Plugin Verifier checks platform compatibility, but no automated test performs a real Chromium navigation.
  implication: all existing gates can pass while the first installed-product document load fails.

- timestamp: 2026-07-28T03:47:00Z
  checked: JetBrains IntelliJ Platform SDK JCEF documentation, "Loading Resources From Plugin Distribution"
  found: packaged plugin HTML/CSS/JavaScript is not directly accessible to the browser and should be exposed at predefined URLs through `CefRequestHandler` and `CefResourceRequestHandler`.
  implication: direct JAR navigation violates the host platform's documented resource-loading model.

- timestamp: 2026-07-28T03:47:30Z
  checked: exact IDEA 2025.3 and IDEA 2026.2 JCEF APIs with `javap`
  found: both lanes expose compatible request-handler registration and legacy resource-handler methods; IDEA 2026.2 adds newer methods without removing the shared methods.
  implication: one source implementation can support both certified host lanes without reflection or version branching.

- timestamp: 2026-07-28T03:48:48Z
  checked: generated production asset graph
  found: `index.html` currently references one local module script and one local stylesheet, but a visual enterprise UI will add assets and lazy chunks over time.
  implication: a general resource provider is safer than inlining today's two assets and remains compatible with future bundles.

## Resolution

root_cause: "Packaged startup treats a classloader URL as a browser URL. `URL.toExternalForm()` preserves the JVM-only `jar:file:` scheme and `JBCefBrowser.loadURL` forwards it unchanged to Chromium, which rejects it with ERR_UNKNOWN_URL_SCHEME. Tests asserted forwarding rather than rendering, so they encoded the defect as expected behavior."
fix: "Expose `/webui/**` through an exact private synthetic HTTPS origin registered on the browser's JCEF client before navigation. Map only normalized, decoded, traversal-free paths to classpath resources; constrain methods and origin; set deterministic MIME and defensive response headers; return explicit 404/405 responses; unregister the handler during browser disposal. Keep the development origin unbridged and unchanged. Do not inline the current bundle because that would not scale to future assets or lazy chunks."
verification: "Add pure resource-provider security/MIME tests; change factory integration and both managed host smoke tests to require packaged-origin loading and handler installation rather than a JAR URL; run both exact host compile/test/build/verifier gates; then repeat real signed IntelliJ IDEA 2025.3 and 2026.2 installed-product UAT and visually confirm the React UI renders."
files_changed:
  - .planning/debug/jcef-jar-unknown-url-scheme.md

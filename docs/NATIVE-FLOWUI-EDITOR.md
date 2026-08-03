# Native FlowUI Editor Architecture

## Product contract

Jmix FlowUI descriptors open as a first-class IntelliJ file editor with two
coexisting surfaces:

- **Design** — the connected visual editor;
- **XML** — IntelliJ's normal source editor.

The plugin does not replace or wrap the XML editor. A developer can switch
between visual and manual editing without closing the file, generating a
parallel copy, or choosing one authoring mode permanently.

Only valid project-contained XML descriptors whose document root is `view` or
`fragment` receive the Design editor. Menu, security, Spring, Liquibase and
arbitrary XML files remain in their normal IntelliJ editors.

## Native host

`JmixFlowUiFileEditorProvider` is registered in the shared descriptor and both
supported host descriptors. It uses `PLACE_BEFORE_DEFAULT_EDITOR`, so the
visual editor appears beside IntelliJ's standard XML editor.

The Design surface is hosted by JCEF using the same verified web bundle as the
tool window, but it has an isolated `/flowui-editor.html` entry route. That
route:

- omits the global workbench navigation;
- opens the existing-descriptor designer directly;
- preserves the permanent palette, canvas and property inspector;
- exposes no "new view" action that could silently change editing context.

If JCEF is unavailable or the verified web bundle is missing, the Design
surface shows a deterministic diagnostic. The XML editor remains usable.

## Bridge trust boundary

The editor loads only from the plugin's private packaged origin. The JCEF
bridge publishes launch context only while the browser is on that origin.
The launch payload contains:

- the surface kind (`FLOW_UI_EDITOR`);
- a project-relative or collision-safe external-root alias;
- the current SHA-256 revision fingerprint.

Absolute project paths and raw source text do not cross the launch boundary.
Requests still pass through the existing action allowlist, payload limits,
project containment checks, revision validation and workspace-change
services. `/flowui-editor.html` is an exact packaged alias for the verified
`index.html`; query parameters, path traversal and off-origin requests retain
the common resource-handler policy.

## Round-trip source model

`ProjectSourceText` is the common text boundary for FlowUI loading, change
preview, apply, history, undo and redo.

Read precedence is:

1. the current cached IntelliJ `Document`, including unsaved manual edits;
2. the virtual-file byte stream when no document exists.

Write behavior is:

1. an open or unsaved document is changed through IntelliJ's document model,
   preserving its dirty state and native editor continuity;
2. a clean unopened file is persisted through the VFS.

Every visual change still requires a digest-bound preview. Apply rejects a
manual edit made after preview. Visual undo and redo use the same current text
boundary, so they do not restore stale disk bytes over newer XML work.

When the developer returns to Design after changing XML, `selectNotify()`
publishes a fresh locator and current document fingerprint. The existing
designer reloads that revision immediately. Document changes also publish the
standard `FileEditor.PROP_MODIFIED` state to IntelliJ.

## Multi-module ownership

Eligibility and launch locators use `ProjectFileResolver`, not string
relativization against only the base directory. Descriptors under registered
modules, included builds and external composite content roots retain their
indexed ownership. External roots use safe aliases without exposing absolute
paths or accepting `..` traversal.

## Responsive contract

The editor is container-responsive because IntelliJ editor width is
independent of the browser viewport:

- at 480 pixels and above, palette, canvas and inspector remain simultaneous
  columns with proportional minimums;
- below 480 pixels, all three regions remain in one continuous, vertically
  scrollable workspace—never tabs or one-pane drawers;
- the stacked palette receives enough height for Components, Component Tree
  and Data & Bindings to remain usable;
- the canvas owns its internal horizontal scroll for desktop/tablet/mobile
  screen previews; the editor shell itself must not overflow.

Development builds accept `?editorWidth=<pixels>` on the isolated editor route
to reproduce embedded widths without changing production behavior.

## Verification evidence

The 2026-07-29 milestone passed:

- provider eligibility for views/fragments and rejection of unrelated XML;
- Design-before-XML policy and stable editor type ID;
- current unsaved-document fingerprint at creation and reselection;
- safe external composite-root aliases;
- editor-session disposal;
- preview/apply/undo/redo against unsaved documents;
- stale rejection after a post-preview manual edit;
- clean unopened cross-root persistence and history;
- exact packaged editor-route bytes;
- descriptor registration in IntelliJ 2025.3 and 2026.2;
- real-browser insertion and undo through the isolated editor route;
- layout measurement at 1280, 640, 440 and 320 pixels with zero shell-level
  horizontal overflow and no browser errors;
- the full `phase1Check` gate: frontend production build, dependency and
  architecture guards, all host tests and smoke tests, Plugin Verifier
  compatibility and both reproducible plugin ZIP checks.

## Remaining certification boundary

This milestone establishes native editor hosting and safe round trip. It does
not by itself certify complete FlowUI Studio parity. `STUDIO-CORE-004` remains
`PARTIAL` until runtime-fidelity fixtures cover custom and add-on components,
themes, accessibility authoring, handwritten controller edge cases, genuine
hot reload and installed-IDE JCEF interaction, memory and leak budgets.

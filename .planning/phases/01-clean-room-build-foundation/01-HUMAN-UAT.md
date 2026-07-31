---
status: passed
phase: 01-clean-room-build-foundation
source: [01-VERIFICATION.md, 01-05-PLAN.md]
started: 2026-07-27T21:43:47Z
completed: 2026-07-28T05:37:15Z
updated: 2026-07-28T05:37:15Z
---

# Phase 1 Human UAT

## Result

All three installed-product checkpoints passed. Both artifacts were produced from
revision `8e9adbefb672e34ece2f4e2d142c507b5608eb65`, installed into isolated
profiles of official signed JetBrains distributions, and exercised through the
visible IntelliJ UI.

## Tests

### 1. IntelliJ IDEA 2025.3 installation and open

- **Artifact:** `plugin/hosts/idea253/build/distributions/jmix-visual-workbench-1.0.0-idea253.zip`
- **ZIP SHA-256:** `77cd8bf4f988acf98979a5dbe21b6bae23d7dce067972e92bd855943f378f976`
- **Host:** official signed/notarized IntelliJ IDEA Ultimate 2025.3,
  build `IU-253.28294.334`
- **Result:** passed
- **Observed:** the `Jmix Visual Workbench` tool-window button registered, the
  packaged React entity designer rendered, and the log recorded
  `Bridge request: getProjectConfig`.
- **Negative checks:** no `ERR_UNKNOWN_URL_SCHEME`, class-loading, descriptor,
  JCEF, or missing-resource error was observed.
- **Evidence:** `evidence/idea253-packaged-ui.png`
- **Evidence SHA-256:** `d9988e8600f1dc41e2686f569448b8b189be3c5efdd8e00c24b43c85b5c9b76b`

### 2. IntelliJ IDEA 2026.2 installation and open

- **Artifact:** `plugin/hosts/idea262/build/distributions/jmix-visual-workbench-1.0.0-idea262.zip`
- **ZIP SHA-256:** `311b795b5e1dc127a6d345eb3d7b50772a1597449ee73bab77350efbe422ad8c`
- **Host:** official signed/notarized IntelliJ IDEA 2026.2,
  build `IU-262.8665.258`
- **Result:** passed
- **Observed:** after project-model initialization settled, the
  `Jmix Visual Workbench` tool-window button registered, the packaged React
  entity designer rendered, JCEF initialized, and the log recorded
  `Bridge request: getProjectConfig`.
- **Negative checks:** no plugin exception, `ERR_UNKNOWN_URL_SCHEME`,
  class-loading, descriptor, JCEF, or missing-resource error was observed.
- **Evidence:** `evidence/idea262-packaged-ui.png`
- **Evidence SHA-256:** `a71042c926f13fb94224756e80a5989d155e360b3d14efbc9aa4666bbd83a8a8`

The isolated host initially attempted an unnecessary Gradle synchronization and
ran out of disk space while copying a bundled Gradle JAR. That project-import
failure was outside the plugin and did not affect the successful tool-window,
JCEF, or bridge checks. The disposable import and host profile were removed
after evidence capture.

### 3. Identity and clean-room review

- **Result:** passed
- **Observed:** installed UI and descriptor identify the independent product as
  `Jmix Visual Workbench` with plugin ID `org.jmixworkbench`.
- **Review:** the icon and UI are original project assets; no Jmix Studio
  branding, proprietary asset, license bypass, or Haulmont endorsement claim was
  present. The README and clean-room/trademark/provenance policies explicitly
  state compatibility without affiliation.

## Summary

| Outcome | Count |
|---|---:|
| Passed | 3 |
| Issues | 0 |
| Pending | 0 |
| Skipped | 0 |
| Blocked | 0 |

The earlier `jar:file:` blocker is closed. Packaged Chromium navigation now uses
the constrained private origin `https://jmix-workbench.invalid`, backed only by
classpath resources under `/webui/**`.

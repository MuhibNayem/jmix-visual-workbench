# Deferred Items

## 01-03 Plugin Verifier findings

- The exact IDEA 2025.3 and 2026.2 verifier runs report six pre-existing deprecated API usages: two `Project.baseDir` calls and four inherited `ToolWindowFactory` methods. They do not block compatibility and are outside this packaging/resource-hardening plan.
- The same runs report six inherited experimental `ToolWindowFactory` API usages. The plugin is compatible and dynamically loadable; revisit these APIs when the tool-window implementation is modernized.
- Plugin Verifier warns that each JetBrains IDE layout index references optional product components absent from the downloaded macOS distribution. Verification still schedules and completes the plugin compatibility check against the exact IU builds.

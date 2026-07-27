# Third-Party Notices

This hand-maintained inventory records the direct build and runtime dependencies
currently declared by the project. Generated dependency inventories and SBOMs,
once available, supersede this list for a particular revision or release.

| Component | Use | Declared license |
| --- | --- | --- |
| IntelliJ Platform SDK and JCEF APIs | Plugin host APIs | JetBrains product/platform terms |
| Kotlin Gradle plugin | Kotlin compilation | Apache License 2.0 |
| IntelliJ Platform Gradle plugin | Plugin build tooling | Apache License 2.0 |
| Gradle Node plugin | Project-local Node/npm provisioning | Apache License 2.0 |
| Foojay toolchain resolver | JDK toolchain discovery and provisioning | Apache License 2.0 |
| Node.js | Project-local frontend build runtime | MIT |
| IntelliJ Plugin Verifier | Plugin compatibility verification | Apache License 2.0 |
| Gson | JVM JSON serialization | Apache License 2.0 |
| JUnit 4 and JUnit 5 | JVM and IntelliJ-hosted tests | Eclipse Public License 1.0 / 2.0 |
| Kotlin test | Kotlin test assertions and adapters | Apache License 2.0 |
| React and React DOM | Web UI | MIT |
| Zustand | Web UI state | MIT |
| Lucide React | Icons | ISC |
| clsx | CSS class composition | MIT |
| Vite and React plugin | Frontend build | MIT |
| TypeScript | Frontend compilation | Apache License 2.0 |
| Tailwind CSS, PostCSS, Autoprefixer | Frontend styling/build | MIT |

Transitive dependencies retain their own licenses and notices. Before
distribution, produce and review a machine-generated inventory for the exact
locked dependency graph. Adding a dependency requires updating this document,
the lockfiles, and any generated inventory.

This file is informational and does not replace the license text supplied by a
third party. Project source is licensed under [LICENSE](LICENSE).

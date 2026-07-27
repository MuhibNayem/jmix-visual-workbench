# Technology Stack

**Analysis Date:** 2026-07-27

## Languages

**Primary:**
- Kotlin 1.9.25 - IntelliJ plugin, project services, JCEF bridge, models, and code generators under `plugin/src/main/kotlin/com/jmixstudio/`; the compiler plugin version is fixed in `plugin/build.gradle.kts` and repeated in `plugin/gradle.properties`.
- TypeScript 5.5-compatible source / 5.9.3 locked compiler - React UI, state, bridge client, and mirrored models under `webui/src/`; the declared range is in `webui/package.json` and the installed resolution is in `webui/package-lock.json`.
- Java 17 bytecode target - generated Java source targets Jmix/Jakarta/Spring APIs in `plugin/src/main/kotlin/com/jmixstudio/generator/`, while the plugin itself uses the Java plugin and Kotlin JVM toolchain 17 in `plugin/build.gradle.kts`.

**Secondary:**
- CSS - Tailwind directives and custom global rules in `webui/src/index.css`.
- HTML - Vite application shell in `webui/index.html`.
- JavaScript ESM - Tailwind and PostCSS configuration in `webui/tailwind.config.js` and `webui/postcss.config.js`.
- XML - IntelliJ plugin metadata in `plugin/src/main/resources/META-INF/plugin.xml`; generators emit Jmix Flow UI, Liquibase, fetch-plan, menu, and BPMN XML from `plugin/src/main/kotlin/com/jmixstudio/generator/`.
- Gradle Kotlin DSL - plugin build and project naming in `plugin/build.gradle.kts` and `plugin/settings.gradle.kts`.

## Runtime

**Environment:**
- IntelliJ IDEA Community Platform 2024.1 (`IC`) is the plugin runtime declared by `platformType` and `platformVersion` in `plugin/gradle.properties`.
- The plugin supports IntelliJ build 241 through 251.* according to `patchPluginXml` in `plugin/build.gradle.kts`; declared platform dependencies are `com.intellij.modules.platform` and `com.intellij.modules.java` in `plugin/src/main/resources/META-INF/plugin.xml`.
- JDK 17 is the compilation toolchain in `plugin/build.gradle.kts`. Use JDK 17 or a Gradle-compatible newer JDK to build; the repository does not pin a local JDK distribution.
- JCEF is supplied by the IntelliJ runtime rather than a standalone repository dependency. `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt` checks `JBCefApp.isSupported()` before creating the embedded browser.
- Node.js is the UI build runtime. The repository has no `.nvmrc`, `.node-version`, or `engines` entry in `webui/package.json`; locked Vite 5.4.21 requires Node `^18.0.0 || >=20.0.0` in `webui/package-lock.json`.

**Package Manager:**
- npm - UI dependencies and scripts are declared in `webui/package.json`.
- Lockfile: present, npm lockfile version 3 at `webui/package-lock.json`; use `npm ci` for a reproducible install.
- Gradle 8.7 - distribution metadata exists at `plugin/gradle/wrapper/gradle-wrapper.properties`.
- Wrapper completeness: incomplete. `plugin/gradlew`, `plugin/gradlew.bat`, and `plugin/gradle/wrapper/gradle-wrapper.jar` are absent, so the README wrapper commands in `README.md` require restoring the wrapper or using a compatible system Gradle.

## Frameworks

**Core:**
- IntelliJ Platform SDK 2024.1 - tool-window, project-service, action, VFS, logging, write-command, and JCEF APIs used throughout `plugin/src/main/kotlin/com/jmixstudio/` and registered in `plugin/src/main/resources/META-INF/plugin.xml`.
- React 18.3.1 - component runtime mounted from `webui/src/main.tsx`; exact versions for `react` and `react-dom` are locked in `webui/package-lock.json`.
- Zustand 4.5.7 - in-memory UI state and notifications in `webui/src/store/index.ts`; the exact resolution is in `webui/package-lock.json`.
- Tailwind CSS 3.4.19 - styling system configured by `webui/tailwind.config.js` and consumed by `webui/src/index.css`; exact resolution is in `webui/package-lock.json`.

**Testing:**
- Not detected. `webui/package.json` has no test script or test dependency, `plugin/build.gradle.kts` has no test dependency, and no test sources are present under `webui/src/` or `plugin/src/`.

**Build/Dev:**
- Gradle 8.7 metadata - plugin build orchestration via `plugin/gradle/wrapper/gradle-wrapper.properties` and `plugin/build.gradle.kts`.
- Kotlin JVM Gradle plugin 1.9.25 - compiles the plugin source in `plugin/src/main/kotlin/`.
- JetBrains IntelliJ Gradle plugin 1.17.4 - downloads/configures IntelliJ 2024.1 and provides `runIde`/`buildPlugin` behavior from `plugin/build.gradle.kts`.
- Vite 5.4.21 - development server and production bundler configured in `webui/vite.config.ts`; exact version is locked in `webui/package-lock.json`.
- TypeScript 5.9.3 - strict type checking configured in `webui/tsconfig.json`; `npm run build` runs `tsc && vite build` from `webui/package.json`.
- PostCSS 8.5.23 and Autoprefixer 10.5.4 - Tailwind CSS processing configured in `webui/postcss.config.js`; exact versions are locked in `webui/package-lock.json`.
- `@vitejs/plugin-react` 4.7.0 - React Fast Refresh/JSX integration enabled in `webui/vite.config.ts`; exact version is locked in `webui/package-lock.json`.

## Key Dependencies

**Critical:**
- Gson 2.11.0 - serializes and deserializes bridge payloads and model enums in `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt` and `plugin/src/main/kotlin/com/jmixstudio/model/`; declared in `plugin/build.gradle.kts`.
- IntelliJ JCEF APIs - embeds the built React UI and implements a bidirectional JavaScript query bridge in `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt` and `plugin/src/main/kotlin/com/jmixstudio/bridge/JcefBridge.kt`.
- React/React DOM 18.3.1 - renders all designers under `webui/src/components/` from `webui/src/main.tsx`.
- Zustand 4.5.7 - centralizes project configuration, entity-editor state, generation status, and toast state in `webui/src/store/index.ts`.
- Lucide React 0.441.0 - icon components used by the view, menu, role, and migration designers under `webui/src/components/`; declared in `webui/package.json`.
- clsx 2.1.1 - class-name composition dependency declared in `webui/package.json`; no source import is currently present under `webui/src/`.

**Infrastructure:**
- Java standard filesystem API plus IntelliJ VFS - writes generated artifacts and refreshes the host project in `plugin/src/main/kotlin/com/jmixstudio/services/CodeGenerationService.kt`.
- Custom `JavaClassBuilder` and `XmlBuilder` - no external templating engine is used; source generation is implemented in `plugin/src/main/kotlin/com/jmixstudio/generator/JavaClassBuilder.kt` and `plugin/src/main/kotlin/com/jmixstudio/generator/XmlBuilder.kt`.
- Jakarta Persistence, Spring Data/Spring, and Jmix APIs are generated-code contracts, not plugin runtime dependencies. Imports are emitted by `plugin/src/main/kotlin/com/jmixstudio/generator/EntityGenerator.kt`, `DataRepositoryGenerator.kt`, `EventListenerGenerator.kt`, `RoleGenerator.kt`, and `ViewControllerGenerator.kt`.

## Configuration

**Environment:**
- No `.env` files, environment-variable reads, or secret configuration are present in the repository. Runtime configuration is derived from the open IntelliJ project by `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt`.
- Development UI location is the JVM system property `jmixstudio.dev.url`, read in `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`; the documented local value is `http://localhost:5173` in `README.md`.
- Target-project defaults are `src/main/java`, `src/main/resources`, Jmix 2.4.0, and PostgreSQL in `plugin/src/main/kotlin/com/jmixstudio/model/ProjectConfig.kt`. `plugin/src/main/kotlin/com/jmixstudio/services/JmixProjectService.kt` overrides package, Jmix version, and database type when its build-file heuristics match.

**Build:**
- Plugin identity, IntelliJ version, JVM memory, and configuration-cache settings: `plugin/gradle.properties`.
- Gradle plugins, repositories, Gson, JVM toolchain, compatibility range, resource copying, and package tasks: `plugin/build.gradle.kts`.
- IntelliJ extension/action registrations: `plugin/src/main/resources/META-INF/plugin.xml`.
- UI dependency graph and commands: `webui/package.json` and `webui/package-lock.json`.
- TypeScript: `webui/tsconfig.json`.
- Vite output, relative asset base, and fixed development port 5173: `webui/vite.config.ts`.
- Tailwind theme/content scanning: `webui/tailwind.config.js`.
- CSS processing: `webui/postcss.config.js`.

## Platform Requirements

**Development:**
- Install Node satisfying locked Vite's engine (`^18 || >=20`) and run `npm ci` in `webui/`, based on `webui/package-lock.json`.
- Build the UI with `npm run build` from `webui/package.json`; output is `webui/dist/` as configured in `webui/vite.config.ts`.
- Provide Gradle compatible with the Kotlin 1.9.25 and IntelliJ plugin 1.17.4 build in `plugin/build.gradle.kts`, or restore the missing wrapper artifacts referenced by `plugin/gradle/wrapper/gradle-wrapper.properties`.
- Build `webui/dist/` before plugin resources are processed. `copyWebUi` in `plugin/build.gradle.kts` copies that existing directory but does not invoke npm.
- Use an IntelliJ distribution with JCEF support to run the tool window; unsupported runtimes show an error label from `plugin/src/main/kotlin/com/jmixstudio/toolwindow/JmixStudioToolWindowFactory.kt`.

**Production:**
- Deployment target is an IntelliJ plugin ZIP generated by the IntelliJ Gradle plugin from `plugin/build.gradle.kts`, not a separately hosted web application.
- The production UI is loaded from `/webui/index.html` bundled in plugin resources by `copyWebUi` in `plugin/build.gradle.kts`; `base: './'` in `webui/vite.config.ts` keeps bundled asset URLs relative.
- Plugin version is 1.0.0 in `plugin/gradle.properties` and `plugin/src/main/resources/META-INF/plugin.xml`; UI version is 1.0.0 in `webui/package.json`.
- No Marketplace publishing, signing, deployment, or CI configuration is present alongside `plugin/build.gradle.kts` and `webui/package.json`.

---

*Stack analysis: 2026-07-27*

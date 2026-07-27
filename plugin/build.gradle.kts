import com.github.gradle.node.npm.task.NpmTask
import org.jmixworkbench.build.AssembleWebBundleTask
import org.jmixworkbench.build.VerifyWebBundleTask

plugins {
    alias(libs.plugins.node)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val webUiDirectory = layout.projectDirectory.dir("../webui")
val stagedWebUiDirectory = layout.buildDirectory.dir("webui-dist")
val generatedWebBundleDirectory = layout.buildDirectory.dir("generated-resources/webui")
val hostMetadataDirectory = layout.buildDirectory.dir("host-metadata")

val webUiInputs = files(
    webUiDirectory.file("package.json"),
    webUiDirectory.file("package-lock.json"),
    webUiDirectory.file("index.html"),
    webUiDirectory.file("tsconfig.json"),
    webUiDirectory.file("vite.config.ts"),
    webUiDirectory.file("postcss.config.js"),
    webUiDirectory.file("tailwind.config.js"),
    fileTree(webUiDirectory.dir("src")) {
        include("**/*")
    },
)

val gitRevision = providers.exec {
    workingDir = layout.projectDirectory.asFile.parentFile
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

node {
    version.set(libs.versions.node.runtime)
    download.set(true)
    nodeProjectDir.set(webUiDirectory)
    workDir.set(layout.buildDirectory.dir("nodejs"))
    npmWorkDir.set(layout.buildDirectory.dir("npm"))
    npmInstallCommand.set("ci")
    enableTaskRules.set(false)
}

val npmCi = tasks.register<NpmTask>("npmCi") {
    description = "Installs the locked UI dependency graph with the downloaded Node runtime."
    npmCommand.set(listOf("ci"))
    args.set(emptyList())
    workingDir.set(webUiDirectory.asFile)
    inputs.files(
        webUiDirectory.file("package.json"),
        webUiDirectory.file("package-lock.json"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(webUiDirectory.dir("node_modules"))
}

val compileWebUi = tasks.register<NpmTask>("compileWebUi") {
    description = "Builds the current UI checkout into a build-owned staging directory."
    dependsOn(npmCi)
    npmCommand.set(listOf("run", "build"))
    args.set(
        listOf(
            "--",
            "--outDir",
            stagedWebUiDirectory.get().asFile.absolutePath,
            "--emptyOutDir",
        ),
    )
    workingDir.set(webUiDirectory.asFile)
    inputs.files(webUiInputs).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(stagedWebUiDirectory)
}

val buildWebUi = tasks.register<AssembleWebBundleTask>("buildWebUi") {
    description = "Assembles same-revision UI resources and their input fingerprint."
    dependsOn(compileWebUi)
    compiledAssets.set(stagedWebUiDirectory)
    inputRoot.set(webUiDirectory)
    declaredInputs.from(webUiInputs)
    pluginVersion.set(providers.gradleProperty("pluginVersion"))
    revision.set(gitRevision)
    outputDirectory.set(generatedWebBundleDirectory)
}

val verifyWebBundle = tasks.register<VerifyWebBundleTask>("verifyWebBundle") {
    description = "Rejects missing, stale, or incomplete build-owned web resources."
    dependsOn(buildWebUi)
    inputRoot.set(webUiDirectory)
    declaredInputs.from(webUiInputs)
    bundleDirectory.set(generatedWebBundleDirectory)
}

tasks.register("verifyHostToolchains") {
    description = "Proves both host lanes resolve their exact Java compilation launchers."
    notCompatibleWithConfigurationCache("Reads metadata produced by isolated included builds.")
    dependsOn(
        gradle.includedBuild("idea253").task(":writeToolchainMetadata"),
        gradle.includedBuild("idea262").task(":writeToolchainMetadata"),
    )
    inputs.files(
        hostMetadataDirectory.map { it.file("idea253.json") },
        hostMetadataDirectory.map { it.file("idea262.json") },
    )
    doLast {
        val idea253Metadata = hostMetadataDirectory.get().file("idea253.json").asFile.readText()
        val idea262Metadata = hostMetadataDirectory.get().file("idea262.json").asFile.readText()
        check(Regex("\"languageVersion\"\\s*:\\s*21").containsMatchIn(idea253Metadata)) {
            "idea253 compiler launcher is not Java 21: $idea253Metadata"
        }
        check(Regex("\"languageVersion\"\\s*:\\s*25").containsMatchIn(idea262Metadata)) {
            "idea262 compiler launcher is not Java 25: $idea262Metadata"
        }
    }
}

tasks.register("verifyHostBuildDefinitions") {
    description = "Checks the immutable host lane contracts without resolving IDE distributions."
    notCompatibleWithConfigurationCache("Validates composite-build source files directly.")
    inputs.files(
        "hosts/idea253/settings.gradle.kts",
        "hosts/idea253/build.gradle.kts",
        "hosts/idea253/src/main/resources/META-INF/plugin.xml",
        "hosts/idea262/settings.gradle.kts",
        "hosts/idea262/build.gradle.kts",
        "hosts/idea262/src/main/resources/META-INF/plugin.xml",
    )
    doLast {
        val idea253Build = file("hosts/idea253/build.gradle.kts").readText()
        val idea253Descriptor = file("hosts/idea253/src/main/resources/META-INF/plugin.xml").readText()
        val idea262Build = file("hosts/idea262/build.gradle.kts").readText()
        val idea262Descriptor = file("hosts/idea262/src/main/resources/META-INF/plugin.xml").readText()

        check("JavaLanguageVersion.of(21)" in idea253Build)
        check("KotlinVersion.KOTLIN_2_2" in idea253Build)
        check("JvmTarget.JVM_21" in idea253Build)
        check("sinceBuild.set(\"253\")" in idea253Build)
        check("untilBuild.set(\"261.*\")" in idea253Build)
        check("<depends>com.intellij.modules.jcef</depends>" !in idea253Descriptor)

        check("JavaLanguageVersion.of(25)" in idea262Build)
        check("KotlinVersion.KOTLIN_2_4" in idea262Build)
        check("JvmTarget.JVM_25" in idea262Build)
        check("sinceBuild.set(\"262\")" in idea262Build)
        check("untilBuild.set(\"262.*\")" in idea262Build)
        check("bundledPlugin(\"intellij.platform.ui.jcef\")" in idea262Build)
        check("<depends>com.intellij.modules.jcef</depends>" in idea262Descriptor)
    }
}

tasks.register("compileHostKotlin") {
    description = "Compiles the canonical Kotlin sources against both host lanes."
    dependsOn(
        gradle.includedBuild("idea253").task(":compileKotlin"),
        gradle.includedBuild("idea262").task(":compileKotlin"),
    )
}

tasks.register("testShared") {
    description = "Runs the shared test lifecycle against both host lanes."
    dependsOn(
        gradle.includedBuild("idea253").task(":test"),
        gradle.includedBuild("idea262").task(":test"),
    )
}

tasks.register("assembleHostPlugins") {
    description = "Builds both lane-suffixed plugin distributions."
    dependsOn(
        gradle.includedBuild("idea253").task(":buildPlugin"),
        gradle.includedBuild("idea262").task(":buildPlugin"),
    )
}

tasks.register("buildHostPlugins") {
    description = "Builds current web resources before both host distributions."
    dependsOn(verifyWebBundle)
    finalizedBy("assembleHostPlugins")
}

tasks.register("verifyHostPlugins") {
    description = "Runs artifact/runtime checks for both host distributions."
    dependsOn(
        gradle.includedBuild("idea253").task(":verifyPlugin"),
        gradle.includedBuild("idea253").task(":verifyNoBundledKotlinRuntime"),
        gradle.includedBuild("idea262").task(":verifyPlugin"),
        gradle.includedBuild("idea262").task(":verifyNoBundledKotlinRuntime"),
    )
}

tasks.register("phase1FastCheck") {
    description = "Runs the fast Phase 1 build, UI-freshness, and toolchain contract checks."
    dependsOn(
        verifyWebBundle,
        "verifyHostBuildDefinitions",
        "verifyHostToolchains",
    )
}

tasks.register("phase1Check") {
    description = "Runs all currently available Phase 1 build checks."
    dependsOn(
        "phase1FastCheck",
        "compileHostKotlin",
        "testShared",
    )
}

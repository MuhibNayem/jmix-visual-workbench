import com.github.gradle.node.npm.task.NpmTask
import org.jmixworkbench.build.AssembleWebBundleTask
import org.jmixworkbench.build.SnapshotFileHashTask
import org.jmixworkbench.build.VerifyPluginZipContentsTask
import org.jmixworkbench.build.VerifyWebBundleTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    base
    alias(libs.plugins.node)
    alias(libs.plugins.kotlin.jvm)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    // Node plugin 7.1.0 adds its distribution Ivy repository at project scope,
    // so aggregate JVM dependencies must use the reviewed project-level mirror too.
    mavenCentral()
    maven {
        name = "JmixPublic"
        url = uri("https://global.repo.jmix.io/repository/public")
        content {
            includeGroupByRegex("io\\.jmix(?:\\..*)?")
        }
    }
}

val phase2CoreSourceSet = sourceSets.create("phase2Core") {
    kotlin.setSrcDirs(listOf("src/main/kotlin"))
    kotlin.include(
        "org/jmixworkbench/discovery/model/**",
        "org/jmixworkbench/discovery/change/**",
        "org/jmixworkbench/discovery/flowui/**",
        "org/jmixworkbench/discovery/runtime/**",
        "org/jmixworkbench/discovery/security/**",
        "org/jmixworkbench/discovery/navigation/**",
        "org/jmixworkbench/discovery/compatibility/**",
        "org/jmixworkbench/discovery/semantic/**",
        "org/jmixworkbench/discovery/static/GradleConfigParser.kt",
    )
    resources.setSrcDirs(listOf("src/main/resources"))
    resources.include("compatibility/phase2-registry.json")
}

val phase2CoreTestSourceSet = sourceSets.create("phase2CoreTest") {
    kotlin.setSrcDirs(listOf("src/phase2CoreTest/kotlin"))
    resources.setSrcDirs(listOf("src/phase2CoreTest/resources"))
    compileClasspath += phase2CoreSourceSet.output
    runtimeClasspath += output + compileClasspath
}

val compatibilityGeneratorSourceSet = sourceSets.create("compatibilityGenerator") {
    kotlin.setSrcDirs(listOf("src/main/kotlin", "src/compatibilityGenerator/kotlin"))
    kotlin.include(
        "org/jmixworkbench/model/EntityModel.kt",
        "org/jmixworkbench/model/ViewModel.kt",
        "org/jmixworkbench/generator/JavaClassBuilder.kt",
        "org/jmixworkbench/generator/EntityGenerator.kt",
        "org/jmixworkbench/generator/KotlinEntityGenerator.kt",
        "org/jmixworkbench/generator/DataRepositoryGenerator.kt",
        "org/jmixworkbench/generator/KotlinDataRepositoryGenerator.kt",
        "org/jmixworkbench/generator/AggregateUpdateServiceGenerator.kt",
        "org/jmixworkbench/generator/ViewControllerGenerator.kt",
        "org/jmixworkbench/certification/CompatibilityFixtureGenerator.kt",
    )
    resources.setSrcDirs(emptyList<String>())
}

// IntelliJ-dependent production sources are compiled by the two explicit host
// builds below. Keeping the aggregate JVM source sets empty prevents an
// accidental SDK-less compilation when contributors run the conventional
// `./gradlew test` command.
sourceSets.named("main") {
    kotlin.setSrcDirs(emptyList<String>())
    resources.setSrcDirs(emptyList<String>())
}

sourceSets.named("test") {
    kotlin.setSrcDirs(emptyList<String>())
    resources.setSrcDirs(emptyList<String>())
}

configurations.named(phase2CoreTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations[phase2CoreSourceSet.implementationConfigurationName])
}

dependencies {
    add(compatibilityGeneratorSourceSet.implementationConfigurationName, libs.gson)
    add(phase2CoreTestSourceSet.implementationConfigurationName, kotlin("test-junit5"))
    add(
        phase2CoreTestSourceSet.runtimeOnlyConfigurationName,
        "org.junit.jupiter:junit-jupiter-engine:5.10.1",
    )
    add(
        phase2CoreTestSourceSet.runtimeOnlyConfigurationName,
        "org.junit.platform:junit-platform-launcher:1.10.1",
    )
}

val generatedCompatibilitySources = layout.buildDirectory.dir("compatibility/generated-sources")
val compatibilityEvidenceFile = layout.buildDirectory.file(
    "reports/compatibility/generated-code-certification.json",
)

val generateCompatibilityFixtures = tasks.register<JavaExec>("generateCompatibilityFixtures") {
    description = "Generates the exact Java/Kotlin source corpus used by the Jmix compatibility matrix."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named(compatibilityGeneratorSourceSet.classesTaskName))
    classpath = compatibilityGeneratorSourceSet.runtimeClasspath
    mainClass.set("org.jmixworkbench.certification.CompatibilityFixtureGenerator")
    args(generatedCompatibilitySources.get().asFile.absolutePath)
    inputs.files(
        compatibilityGeneratorSourceSet.allSource,
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedCompatibilitySources)
}

data class TargetCompatibilityCell(
    val id: String,
    val jmixVersion: String,
    val jmixLineDirectory: String,
    val targetJdk: Int,
)

val targetCompatibilityCells = listOf(
    TargetCompatibilityCell("jmix28Jdk17", "2.8.2", "jmix28", 17),
    TargetCompatibilityCell("jmix28Jdk21", "2.8.2", "jmix28", 21),
    TargetCompatibilityCell("jmix30Jdk21", "3.0.0", "jmix30", 21),
    TargetCompatibilityCell("jmix30Jdk25", "3.0.0", "jmix30", 25),
)

val targetCompatibilityCompileTasks = targetCompatibilityCells.flatMap { cell ->
    val sourceSet = sourceSets.create("${cell.id}Compatibility") {
        java.setSrcDirs(
            listOf(
                generatedCompatibilitySources.map { it.dir("common/java") },
                generatedCompatibilitySources.map { it.dir("${cell.jmixLineDirectory}/java") },
            ),
        )
        kotlin.setSrcDirs(
            listOf(
                generatedCompatibilitySources.map { it.dir("common/kotlin") },
                generatedCompatibilitySources.map { it.dir("${cell.jmixLineDirectory}/kotlin") },
            ),
        )
        resources.setSrcDirs(emptyList<String>())
    }
    dependencies {
        add(
            sourceSet.implementationConfigurationName,
            platform("io.jmix.bom:jmix-bom:${cell.jmixVersion}"),
        )
        add(
            sourceSet.implementationConfigurationName,
            "io.jmix.core:jmix-core:${cell.jmixVersion}",
        )
        add(
            sourceSet.implementationConfigurationName,
            "io.jmix.data:jmix-data:${cell.jmixVersion}",
        )
        add(
            sourceSet.implementationConfigurationName,
            "io.jmix.flowui:jmix-flowui:${cell.jmixVersion}",
        )
    }

    val compiler = javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(cell.targetJdk))
    }
    val launcher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(cell.targetJdk))
    }
    val compileKotlin = tasks.named<KotlinCompile>(sourceSet.getCompileTaskName("kotlin")) {
        dependsOn(generateCompatibilityFixtures)
        kotlinJavaToolchain.toolchain.use(launcher)
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(cell.targetJdk.toString()))
    }
    val compileJava = tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        dependsOn(generateCompatibilityFixtures)
        javaCompiler.set(compiler)
        options.release.set(cell.targetJdk)
    }
    listOf(compileKotlin, compileJava)
}

val certifyGeneratedCodeCompatibility = tasks.register(
    "certifyGeneratedCodeCompatibility",
) {
    description = "Compiles current generated Java/Kotlin code against exact Jmix 2.8/3.0 and JDK cells."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(targetCompatibilityCompileTasks)
    inputs.dir(generatedCompatibilitySources)
    targetCompatibilityCompileTasks.forEach { task ->
        inputs.files(task.map { it.outputs.files })
    }
    outputs.file(compatibilityEvidenceFile)
    doLast {
        val generatedRoot = generatedCompatibilitySources.get().asFile
        val manifest = generatedRoot.resolve("source-manifest.json")
        check(manifest.isFile) {
            "Compatibility source manifest was not generated."
        }
        val sourceManifest = manifest.readText()
        check(Regex("\"path\"").findAll(sourceManifest).count() >= 9) {
            "Compatibility source corpus is unexpectedly small."
        }
        targetCompatibilityCompileTasks.forEach { taskProvider ->
            val task = taskProvider.get()
            val classFiles = task.outputs.files.files
                .asSequence()
                .filter(File::exists)
                .flatMap { output ->
                    if (output.isDirectory) output.walkTopDown().asSequence() else sequenceOf(output)
                }
                .count { it.isFile && it.extension == "class" }
            check(classFiles > 0) {
                "${task.path} produced no class files."
            }
        }

        val report = compatibilityEvidenceFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                append("{\n")
                append("  \"schemaVersion\": \"generated-code-certification-v1\",\n")
                append("  \"sourceManifestSha256\": \"").append(sha256(manifest)).append("\",\n")
                append("  \"cells\": [\n")
                targetCompatibilityCells.forEachIndexed { index, cell ->
                    val sourceSetName = "${cell.id}Compatibility"
                    val capitalizedSourceSetName = sourceSetName.replaceFirstChar(Char::uppercase)
                    val kotlinTask = tasks.getByName(
                        "compile${capitalizedSourceSetName}Kotlin",
                    ) as KotlinCompile
                    val javaTask = tasks.getByName(
                        "compile${capitalizedSourceSetName}Java",
                    ) as JavaCompile
                    val expectedBytecodeMajor = cell.targetJdk + 44
                    val languageTasks = listOf(
                        "java" to javaTask,
                        "kotlin" to kotlinTask,
                    )
                    val classCounts = languageTasks.associate { (language, task) ->
                        val classFiles = task.outputs.files.files
                            .asSequence()
                            .filter(File::exists)
                            .flatMap { output ->
                                if (output.isDirectory) {
                                    output.walkTopDown().asSequence()
                                } else {
                                    sequenceOf(output)
                                }
                            }
                            .filter { it.isFile && it.extension == "class" }
                            .toList()
                        check(classFiles.isNotEmpty()) {
                            "${task.path} produced no $language class files."
                        }
                        classFiles.forEach { classFile ->
                            val header = classFile.inputStream().use { input ->
                                ByteArray(8).also { bytes ->
                                    check(input.read(bytes) == bytes.size) {
                                        "Truncated class file: $classFile"
                                    }
                                }
                            }
                            val magic = header.take(4).joinToString("") {
                                "%02x".format(it.toInt() and 0xff)
                            }
                            val major = ((header[6].toInt() and 0xff) shl 8) or
                                (header[7].toInt() and 0xff)
                            check(magic == "cafebabe" && major == expectedBytecodeMajor) {
                                "$classFile has class major $major; expected $expectedBytecodeMajor."
                            }
                        }
                        language to classFiles.size
                    }
                    val compileClasspath = configurations.getByName(
                        "${sourceSetName}CompileClasspath",
                    ).files.filter(File::isFile)
                    val classpathDigest = MessageDigest.getInstance("SHA-256").let { digest ->
                        compileClasspath
                            .map { dependency -> dependency.name to sha256(dependency) }
                            .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
                            .forEach { (name, checksum) ->
                                digest.update(name.toByteArray(Charsets.UTF_8))
                                digest.update(0)
                                digest.update(checksum.toByteArray(Charsets.UTF_8))
                                digest.update(0)
                            }
                        HexFormat.of().formatHex(digest.digest())
                    }
                    val compilerMetadata = javaTask.javaCompiler.get().metadata
                    append("    {\"id\":\"").append(cell.id)
                        .append("\",\"jmixVersion\":\"").append(cell.jmixVersion)
                        .append("\",\"targetJdk\":").append(cell.targetJdk)
                        .append(",\"compilerVendor\":\"")
                        .append(compilerMetadata.vendor.replace("\"", "\\\""))
                        .append("\",\"compilerRuntime\":\"")
                        .append(compilerMetadata.javaRuntimeVersion.replace("\"", "\\\""))
                        .append("\",\"bytecodeMajor\":").append(expectedBytecodeMajor)
                        .append(",\"languages\":[\"java\",\"kotlin\"],")
                        .append("\"artifacts\":[\"entity\",\"repository\",\"flowui-controller\",")
                        .append("\"aggregate-update-service\"],")
                        .append("\"classCounts\":{\"java\":").append(classCounts.getValue("java"))
                        .append(",\"kotlin\":").append(classCounts.getValue("kotlin")).append("},")
                        .append("\"compileClasspathArtifacts\":").append(compileClasspath.size)
                        .append(",\"compileClasspathSha256\":\"").append(classpathDigest)
                        .append("\",\"result\":\"PASSED\"}")
                    if (index != targetCompatibilityCells.lastIndex) append(',')
                    append('\n')
                }
                append("  ]\n")
                append("}\n")
            },
        )
    }
}

val phase2CoreTest = tasks.register<Test>("phase2CoreTest") {
    description = "Runs platform-independent Phase 2 discovery contract tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = phase2CoreTestSourceSet.output.classesDirs
    classpath = phase2CoreTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    reports.junitXml.required.set(true)
}

tasks.named<Test>("test") {
    description = "Runs parser contracts and the shared suite against both supported IntelliJ hosts."
    dependsOn(
        phase2CoreTest,
        "verifyWebBundle",
    )
    finalizedBy("testShared")
}

tasks.register("phase2FastCheck") {
    description = "Runs the currently available fast Phase 2 verification lanes."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(phase2CoreTest)
}

val webUiDirectory = layout.projectDirectory.dir("../webui")
val stagedWebUiDirectory = layout.buildDirectory.dir("webui-dist")
val generatedWebBundleDirectory = layout.buildDirectory.dir("generated-resources/webui")
val hostMetadataDirectory = layout.buildDirectory.dir("host-metadata")
val dependencyIntegrityDirectory = layout.buildDirectory.dir("dependency-integrity")
val npmLockHashSnapshot = dependencyIntegrityDirectory.map { it.file("npm-lock.sha256") }
val lockHashSnapshot = dependencyIntegrityDirectory.map { it.file("gradle-locks.properties") }
val dependencyLockFiles = files(
    layout.projectDirectory.file("hosts/idea253/gradle/dependency-locks/gradle.lockfile"),
    layout.projectDirectory.file("hosts/idea262/gradle/dependency-locks/gradle.lockfile"),
)

fun sha256(file: File): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

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

val snapshotNpmLockHash = tasks.register<SnapshotFileHashTask>("snapshotNpmLockHash") {
    description = "Captures package-lock.json before npm ci so drift is detectable."
    inputFile.set(webUiDirectory.file("package-lock.json"))
    outputFile.set(npmLockHashSnapshot)
}

val npmCi = tasks.register<NpmTask>("npmCi") {
    description = "Installs the locked UI dependency graph with the downloaded Node runtime."
    dependsOn(snapshotNpmLockHash)
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
        "src/main/kotlin/org/jmixworkbench/project/JmixNewProjectWizard.kt",
    )
    doLast {
        val idea253Build = file("hosts/idea253/build.gradle.kts").readText()
        val idea253Descriptor = file("hosts/idea253/src/main/resources/META-INF/plugin.xml").readText()
        val idea262Build = file("hosts/idea262/build.gradle.kts").readText()
        val idea262Descriptor = file("hosts/idea262/src/main/resources/META-INF/plugin.xml").readText()
        val projectWizard =
            file("src/main/kotlin/org/jmixworkbench/project/JmixNewProjectWizard.kt").readText()

        check("JavaLanguageVersion.of(21)" in idea253Build)
        check("KotlinVersion.KOTLIN_2_2" in idea253Build)
        check("JvmTarget.JVM_21" in idea253Build)
        check("intellijIdeaUltimate(\"2025.3\")" in idea253Build)
        check("buildNumber.startsWith(\"IU-253.\")" in idea253Build)
        check("pluginVerifier()" in idea253Build)
        check("current()" in idea253Build)
        check("""bundledPlugin("com.intellij.gradle")""" in idea253Build)
        check("sinceBuild.set(\"253\")" in idea253Build)
        check("untilBuild.set(\"253.*\")" in idea253Build)
        check("<depends>com.intellij.gradle</depends>" in idea253Descriptor)
        check("org.jmixworkbench.project.JmixNewProjectWizard" in idea253Descriptor)
        check("<depends>com.intellij.modules.jcef</depends>" !in idea253Descriptor)

        check("JavaLanguageVersion.of(25)" in idea262Build)
        check("KotlinVersion.KOTLIN_2_4" in idea262Build)
        check("JvmTarget.JVM_25" in idea262Build)
        check("intellijIdeaUltimate(\"2026.2\")" in idea262Build)
        check("buildNumber.startsWith(\"IU-262.\")" in idea262Build)
        check("pluginVerifier()" in idea262Build)
        check("current()" in idea262Build)
        check("""bundledPlugin("com.intellij.gradle")""" in idea262Build)
        check("sinceBuild.set(\"262\")" in idea262Build)
        check("untilBuild.set(\"262.*\")" in idea262Build)
        check("<depends>com.intellij.gradle</depends>" in idea262Descriptor)
        check("org.jmixworkbench.project.JmixNewProjectWizard" in idea262Descriptor)
        check("bundledModule(\"intellij.libraries.jcef\")" in idea262Build)
        check("bundledModule(\"intellij.platform.ui.jcef\")" in idea262Build)
        check("<depends>com.intellij.modules.jcef</depends>" in idea262Descriptor)

        check("GradleSettings.getInstance(project)" in projectWizard)
        check("GradleProjectSettings()" in projectWizard)
        check("DistributionType.WRAPPED" in projectWizard)
        check("ExternalSystemUtil.requestImport" !in projectWizard) {
            "The Jmix project wizard must not use IntelliJ's internal Gradle import API."
        }
    }
}

val verifyNativeIndexArchitecture = tasks.register("verifyNativeIndexArchitecture") {
    description = "Rejects broad scopes, global PSI cache keys, and extension-wide scans in native IDE code."
    val nativeSources = fileTree(layout.projectDirectory.dir("src/main/kotlin")) {
        include("**/*.kt")
    }
    val pluginDescriptors = files(
        layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml"),
        layout.projectDirectory.file("hosts/idea253/src/main/resources/META-INF/plugin.xml"),
        layout.projectDirectory.file("hosts/idea262/src/main/resources/META-INF/plugin.xml"),
    )
    inputs.files(nativeSources, pluginDescriptors)
    doLast {
        val prohibited = linkedMapOf(
            "GlobalSearchScope.allScope(" to
                "Use project-content, project, or project-and-libraries scope.",
            "PsiModificationTracker" to
                "Use the owning file-based index modification stamp.",
            "FilenameIndex.getAllFilesByExt(" to
                "Register and query a content-sensitive candidate index.",
            "FileTypeIndex" to
                "Register and query a content-sensitive candidate index.",
        )
        nativeSources.files.sorted().forEach { source ->
            val text = source.readText()
            prohibited.forEach { (marker, remediation) ->
                check(marker !in text) {
                    "${source.relativeTo(layout.projectDirectory.asFile)} contains prohibited " +
                        "native-index marker '$marker'. $remediation"
                }
            }
        }

        val indexSource = file(
            "src/main/kotlin/org/jmixworkbench/ide/JmixSymbolFileIndexes.kt",
        ).readText()
        val indexClasses = Regex(
            """class\s+(Jmix\w+CandidateFileIndex)\s*:\s*JmixCandidateFileIndex""",
        ).findAll(indexSource).map { it.groupValues[1] }.toSet()
        check(indexClasses.size == 10) {
            "Expected ten independent native candidate indexes, found " +
                "${indexClasses.size}: ${indexClasses.sorted()}"
        }
        pluginDescriptors.files.forEach { descriptor ->
            val descriptorText = descriptor.readText()
            check(
                """<fileEditorProvider implementation="org.jmixworkbench.editor.JmixFlowUiFileEditorProvider"/>""" in
                    descriptorText,
            ) {
                "${descriptor.relativeTo(layout.projectDirectory.asFile)} must register the native FlowUI editor."
            }
            check(
                """<fileEditorProvider implementation="org.jmixworkbench.editor.JmixEntityFileEditorProvider"/>""" in
                    descriptorText,
            ) {
                "${descriptor.relativeTo(layout.projectDirectory.asFile)} must register the native entity editor."
            }
            check(
                """<newProjectWizard.generator implementation="org.jmixworkbench.project.JmixNewProjectWizard"/>""" in
                    descriptorText,
            ) {
                "${descriptor.relativeTo(layout.projectDirectory.asFile)} must register the native Jmix project wizard."
            }
            check(
                """<projectService serviceImplementation="org.jmixworkbench.toolwindow.WorkbenchNavigationService"/>""" in
                    descriptorText,
            ) {
                "${descriptor.relativeTo(layout.projectDirectory.asFile)} must register action navigation."
            }
            listOf(
                "org.jmixworkbench.ide.JmixJavaUiComponentPolicyInspection",
                "org.jmixworkbench.ide.JmixKotlinUiComponentPolicyInspection",
                "org.jmixworkbench.ide.JmixSpringStereotypeUsageFileIndex",
            ).forEach { requiredExtension ->
                check(requiredExtension in descriptorText) {
                    "${descriptor.relativeTo(layout.projectDirectory.asFile)} must register $requiredExtension."
                }
            }
            val registered = Regex(
                """<fileBasedIndex implementation="org\.jmixworkbench\.ide\.(Jmix\w+CandidateFileIndex)"/>""",
            ).findAll(descriptorText).map { it.groupValues[1] }.toSet()
            check(registered == indexClasses) {
                "${descriptor.relativeTo(layout.projectDirectory.asFile)} must register exactly " +
                    "the native candidate indexes. Missing=${indexClasses - registered}, " +
                    "unexpected=${registered - indexClasses}"
            }
        }
    }
}

val verifyMutationArchitecture = tasks.register("verifyMutationArchitecture") {
    description = "Rejects new project-write primitives outside the certified mutation boundaries."
    val projectDirectoryRoot = layout.projectDirectory.asFile
    val productionSources = fileTree(layout.projectDirectory.dir("src/main/kotlin")) {
        include("**/*.kt")
    }
    val probeSourceFile = layout.projectDirectory.file(
        "src/main/kotlin/org/jmixworkbench/services/WorkspaceMutationProbe.kt",
    ).asFile
    val bridgeSources = fileTree(
        layout.projectDirectory.dir("src/main/kotlin/org/jmixworkbench/bridge"),
    ) {
        include("**/*.kt")
    }
    inputs.files(productionSources, probeSourceFile, bridgeSources)
    doLast {
        val sharedBoundary = setOf(
            "src/main/kotlin/org/jmixworkbench/services/WorkspaceChangeService.kt",
            "src/main/kotlin/org/jmixworkbench/services/WorkspaceHistoryService.kt",
        )
        val projectTemplateBoundary = setOf(
            "src/main/kotlin/org/jmixworkbench/project/JmixProjectInstaller.kt",
        )
        val allowedByMarker = linkedMapOf(
            "WriteCommandAction" to
                sharedBoundary + "src/main/kotlin/org/jmixworkbench/actions/InjectJmixRepositoryAction.kt",
            "runWriteAction" to emptySet(),
            "VfsUtil.saveText" to
                sharedBoundary + "src/main/kotlin/org/jmixworkbench/services/ProjectSourceText.kt",
            ".createChildData(" to sharedBoundary,
            ".setText(" to setOf(
                "src/main/kotlin/org/jmixworkbench/services/ProjectSourceText.kt",
                "src/main/kotlin/org/jmixworkbench/actions/InjectJmixRepositoryAction.kt",
            ),
            ".delete(this)" to sharedBoundary,
            "Files.newOutputStream(" to projectTemplateBoundary,
            "Files.move(" to projectTemplateBoundary,
            "Files.createDirectory(" to projectTemplateBoundary,
            "Files.deleteIfExists(" to projectTemplateBoundary,
        )
        productionSources.files.sorted().forEach { source ->
            val relativePath = source.relativeTo(projectDirectoryRoot).invariantSeparatorsPath
            val text = source.readText()
            allowedByMarker.forEach { (marker, allowedFiles) ->
                if (marker in text) {
                    check(relativePath in allowedFiles) {
                        "$relativePath uses project-write primitive '$marker' outside the certified " +
                            "WorkspaceChange/WorkspaceHistory/native-PSI mutation boundaries."
                    }
                }
            }
        }
        val probeSource = probeSourceFile.readText()
        check("internal fun interface WorkspaceMutationProbe" in probeSource) {
            "Failure injection must remain internal to the plugin implementation."
        }
        bridgeSources.files.forEach { bridge ->
            check("WorkspaceMutationProbe" !in bridge.readText()) {
                "The JCEF bridge must never expose mutation fault injection: " +
                    bridge.relativeTo(projectDirectoryRoot)
            }
        }
        val projectInstaller = file(
            "src/main/kotlin/org/jmixworkbench/project/JmixProjectInstaller.kt",
        ).readText()
        listOf(
            "LinkOption.NOFOLLOW_LINKS",
            "Files.isSymbolicLink(",
            "Refusing to overwrite existing project path",
            "stageTextFiles(",
            "stageResources(",
            "verifyWrapper(",
            "installedFiles.asReversed()",
            "createdDirectories.asReversed()",
        ).forEach { requiredSafetyControl ->
            check(requiredSafetyControl in projectInstaller) {
                "Native project installation boundary lost safety control '$requiredSafetyControl'."
            }
        }
    }
}

val snapshotLockHashes = tasks.register("snapshotLockHashes") {
    description = "Records SHA-256 values for the two reviewed host dependency lock files."
    inputs.files(dependencyLockFiles)
    outputs.file(lockHashSnapshot)
    doLast {
        val missing = dependencyLockFiles.files.filterNot(File::isFile)
        check(missing.isEmpty()) { "Dependency lock files are missing: $missing" }
        val output = lockHashSnapshot.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            dependencyLockFiles.files
                .sortedBy { it.relativeTo(layout.projectDirectory.asFile).invariantSeparatorsPath }
                .joinToString(separator = "\n", postfix = "\n") { lockFile ->
                    val relativePath = lockFile.relativeTo(layout.projectDirectory.asFile).invariantSeparatorsPath
                    "$relativePath=${sha256(lockFile)}"
                },
        )
    }
}

val verifyLockedConfigurations = tasks.register("verifyLockedConfigurations") {
    description = "Resolves only runtimeClasspath and testRuntimeClasspath in both host builds."
    dependsOn(
        snapshotLockHashes,
        gradle.includedBuild("idea253").task(":verifyLockedConfigurations"),
        gradle.includedBuild("idea262").task(":verifyLockedConfigurations"),
    )
}

tasks.register("compareLockHashes") {
    description = "Proves read-only locked resolution did not rewrite either host lock file."
    dependsOn(verifyLockedConfigurations)
    inputs.file(lockHashSnapshot)
    inputs.files(dependencyLockFiles)
    doLast {
        val recorded = lockHashSnapshot.get().asFile.readLines()
            .filter(String::isNotBlank)
            .associate { line -> line.substringBefore("=") to line.substringAfter("=") }
        dependencyLockFiles.files.forEach { lockFile ->
            val relativePath = lockFile.relativeTo(layout.projectDirectory.asFile).invariantSeparatorsPath
            check(recorded[relativePath] == sha256(lockFile)) {
                "Dependency lock changed during read-only resolution: $relativePath"
            }
        }
    }
}

val verifyDependencyIntegrity = tasks.register("verifyDependencyIntegrity") {
    description = "Enforces wrapper, npm, Gradle lock, repository, and CI verification policy."
    dependsOn(npmCi, verifyLockedConfigurations)
    inputs.files(
        layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"),
        layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.jar"),
        layout.projectDirectory.file("gradle/verification-metadata.xml"),
        layout.projectDirectory.file("gradle/libs.versions.toml"),
        webUiDirectory.file("package.json"),
        webUiDirectory.file("package-lock.json"),
        layout.projectDirectory.file("settings.gradle.kts"),
        layout.projectDirectory.file("hosts/idea253/settings.gradle.kts"),
        layout.projectDirectory.file("hosts/idea262/settings.gradle.kts"),
    )
    inputs.files(dependencyLockFiles)
    inputs.files(
        fileTree(layout.projectDirectory.dir("hosts")) {
            include("**/*.gradle.kts")
        },
        fileTree(layout.projectDirectory.dir("../.github")) {
            include("**/*.yml", "**/*.yaml")
        },
    )
    doLast {
        val wrapperProperties = file("gradle/wrapper/gradle-wrapper.properties")
        val wrapperJar = file("gradle/wrapper/gradle-wrapper.jar")
        val verificationMetadata = file("gradle/verification-metadata.xml")
        val packageLock = webUiDirectory.file("package-lock.json").asFile
        val expectedWrapperJarSha256 = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

        check(wrapperProperties.isFile) { "Gradle wrapper properties are missing." }
        check(
            Regex("""^distributionSha256Sum=[0-9a-f]{64}$""", RegexOption.MULTILINE)
                .containsMatchIn(wrapperProperties.readText()),
        ) {
            "gradle-wrapper.properties must pin distributionSha256Sum."
        }
        check(wrapperJar.isFile) { "Gradle wrapper JAR is missing." }
        check(sha256(wrapperJar) == expectedWrapperJarSha256) {
            "Gradle wrapper JAR checksum mismatch."
        }
        check(packageLock.isFile) { "webui/package-lock.json is required." }
        check(packageLock.readText().contains(""""lockfileVersion": 3""")) {
            "webui/package-lock.json must remain npm lockfile version 3."
        }
        check(npmLockHashSnapshot.get().asFile.readText().trim() == sha256(packageLock)) {
            "npm ci changed webui/package-lock.json; package and lock declarations drifted."
        }
        check(verificationMetadata.isFile) { "gradle/verification-metadata.xml is required." }
        val verificationText = verificationMetadata.readText()
        check("<sha256 value=" in verificationText) {
            "Gradle verification metadata must contain reviewed SHA-256 checksums."
        }
        check("<verify-signatures>false</verify-signatures>" in verificationText) {
            "Signature verification must remain disabled unless trusted publisher keys are reviewed."
        }
        val ciWorkflow = file("../.github/workflows/ci.yml").readText()
        if (Regex("""runs-on:\s*ubuntu-""").containsMatchIn(ciWorkflow)) {
            mapOf(
                "idea-2025.3.tar.gz" to "13f4174ba16c1cef04871cb261433536d002586c269a809392c20ee3f94959f5",
                "idea-2026.2.tar.gz" to "a8055cadef1a6eed4558f8bc9bd591c3a4939f4c8c34560fdf58ab4d2a5c783d",
                "node-24.18.0-linux-x64.tar.gz" to "783130984963db7ba9cbd01089eaf2c2efb055c7c1693c943174b967b3050cb8",
            ).forEach { (artifact, checksum) ->
                check("name=\"$artifact\"" in verificationText && "value=\"$checksum\"" in verificationText) {
                    "Strict Ubuntu CI requires reviewed verification metadata for $artifact."
                }
            }
        }

        val versionCatalog = file("gradle/libs.versions.toml").readText()
        val catalogVersions = Regex("""(?m)^[a-zA-Z0-9_.-]+\s*=\s*"([^"]+)"$""")
            .findAll(versionCatalog.substringBefore("[libraries]"))
            .map { it.groupValues[1] }
        catalogVersions.forEach { declaredVersion ->
            check(
                !declaredVersion.contains("+") &&
                    !declaredVersion.contains("SNAPSHOT", ignoreCase = true) &&
                    !declaredVersion.startsWith("latest.", ignoreCase = true) &&
                    !declaredVersion.contains("[") &&
                    !declaredVersion.contains("("),
            ) {
                "Dynamic or changing version is forbidden: $declaredVersion"
            }
        }

        val buildDeclarations = fileTree(layout.projectDirectory) {
            include("**/*.gradle.kts", "**/*.toml")
            exclude("build/**", "hosts/**/build/**")
        }.files.joinToString("\n") { it.readText() }
        check(!Regex("""(?i)(isChanging|changing)\s*=\s*true""").containsMatchIn(buildDeclarations)) {
            "Changing Gradle dependencies are forbidden."
        }
        check(!Regex("""version\s+["'](?:latest\.[^"']+|[^"']*\+|[^"']*-SNAPSHOT)["']""", RegexOption.IGNORE_CASE)
            .containsMatchIn(buildDeclarations)) {
            "Dynamic Gradle plugin/dependency versions are forbidden."
        }

        val rootSettings = file("settings.gradle.kts").readText()
        check("gradlePluginPortal()" in rootSettings && "mavenCentral()" in rootSettings)
        check("org.gradle.toolchains.foojay-resolver-convention" in rootSettings) {
            "The aggregate build must self-provision target-project JDK toolchains."
        }
        check(!Regex("""maven\s*\{\s*url""").containsMatchIn(rootSettings)) {
            "Undocumented aggregate repositories are forbidden."
        }
        val rootBuild = file("build.gradle.kts").readText()
        check(
            "https://global.repo.jmix.io/repository/public" in rootBuild &&
                """includeGroupByRegex("io\\.jmix(?:\\..*)?")""" in rootBuild
        ) {
            "The compatibility matrix requires the group-filtered official Jmix public repository."
        }
        listOf("idea253", "idea262").forEach { lane ->
            val settings = file("hosts/$lane/settings.gradle.kts").readText()
            check("gradlePluginPortal()" in settings && "mavenCentral()" in settings)
            check("defaultRepositories()" in settings)
            check("RepositoriesMode.FAIL_ON_PROJECT_REPOS" in settings)
            check(!Regex("""maven\s*\{\s*url""").containsMatchIn(settings)) {
                "Undocumented $lane repositories are forbidden."
            }
        }

        val workflowDirectory = file("../.github/workflows")
        if (workflowDirectory.isDirectory) {
            workflowDirectory.walkTopDown()
                .filter { it.isFile && it.extension in setOf("yml", "yaml") }
                .forEach { workflow ->
                    val text = workflow.readText()
                    check(
                        !Regex("""--dependency-verification(?:=|\s+)(?:off|lenient)""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(text),
                    ) {
                        "CI must not bypass strict dependency verification: $workflow"
                }
            }
        }
    }
}

tasks.register("compileHostKotlin") {
    description = "Compiles the canonical Kotlin sources against both host lanes."
    dependsOn(
        gradle.includedBuild("idea253").task(":compileKotlin"),
        gradle.includedBuild("idea262").task(":compileKotlin"),
    )
}

tasks.register<Exec>("testShared") {
    description = "Builds current web resources, then runs the shared test lifecycle against both host lanes."
    dependsOn(verifyWebBundle)
    workingDir(layout.projectDirectory)
    executable(layout.projectDirectory.file("gradlew").asFile)
    args(
        buildList {
            add(":idea253:test")
            add(":idea262:test")
            add("--dependency-verification=strict")
            add("--no-daemon")
            add("--no-configuration-cache")
            if (gradle.startParameter.isOffline) add("--offline")
            gradle.startParameter.projectProperties
                .filterKeys { it in setOf("localIdeaPath", "localIdea253Path", "localIdea262Path") }
                .toSortedMap()
                .forEach { (name, value) -> add("-P$name=$value") }
        },
    )
}

tasks.register("hostSmokeTest") {
    description = "Runs focused factory startup, content attachment, and lifecycle integration tests in both host lanes."
    dependsOn(
        gradle.includedBuild("idea253").task(":hostSmokeTest"),
        gradle.includedBuild("idea262").task(":hostSmokeTest"),
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

tasks.register<VerifyPluginZipContentsTask>("verifyPluginZipContents") {
    description = "Inspects both plugin ZIPs for required resources, shared provenance, and forbidden build caches."
    archives.from(
        layout.projectDirectory.file(
            "hosts/idea253/build/distributions/jmix-visual-workbench-${project.version}-idea253.zip",
        ),
        layout.projectDirectory.file(
            "hosts/idea262/build/distributions/jmix-visual-workbench-${project.version}-idea262.zip",
        ),
    )
}

fun nestedGradleArguments(vararg requestedTasks: String): List<String> = buildList {
    addAll(requestedTasks)
    addAll(
        listOf(
            "--dependency-verification=strict",
            "--no-daemon",
            "--no-configuration-cache",
            "--stacktrace",
        ),
    )
    if (gradle.startParameter.isOffline) {
        add("--offline")
    }
    gradle.startParameter.projectProperties
        .filterKeys { it in setOf("localIdeaPath", "localIdea253Path", "localIdea262Path") }
        .toSortedMap()
        .forEach { (name, value) -> add("-P$name=$value") }
}

fun hostGateArguments(host: String): List<String> = nestedGradleArguments(
    ":$host:clean",
    ":$host:compileKotlin",
    ":$host:test",
    ":$host:hostSmokeTest",
    ":$host:buildPlugin",
    ":$host:verifyPlugin",
    ":$host:verifyNoBundledKotlinRuntime",
)

tasks.register("phase1FastCheck") {
    description = "Runs the fast Phase 1 build, UI-freshness, and toolchain contract checks."
    dependsOn(
        certifyGeneratedCodeCompatibility,
        phase2CoreTest,
        verifyWebBundle,
        "verifyHostBuildDefinitions",
        "verifyHostToolchains",
        verifyNativeIndexArchitecture,
        verifyMutationArchitecture,
        verifyDependencyIntegrity,
    )
}

val phase1RootGate = tasks.register<Exec>("phase1RootGate") {
    description = "Runs root web, integrity, and toolchain checks after the outer clean completes."
    mustRunAfter("clean")
    workingDir(layout.projectDirectory)
    executable(layout.projectDirectory.file("gradlew").asFile)
    args(nestedGradleArguments("phase1FastCheck"))
}

val phase1Idea253Gate = tasks.register<Exec>("phase1Idea253Gate") {
    description = "Runs the IDEA 253 host gate after root-owned web resources are verified."
    dependsOn(phase1RootGate)
    workingDir(layout.projectDirectory)
    executable(layout.projectDirectory.file("gradlew").asFile)
    args(hostGateArguments("idea253"))
}

val phase1Idea262Gate = tasks.register<Exec>("phase1Idea262Gate") {
    description = "Runs the IDEA 262 host gate after the IDEA 253 lane completes."
    dependsOn(phase1Idea253Gate)
    workingDir(layout.projectDirectory)
    executable(layout.projectDirectory.file("gradlew").asFile)
    args(hostGateArguments("idea262"))
}

val verifyPluginZipContents = tasks.named("verifyPluginZipContents") {
    dependsOn(phase1Idea262Gate)
}

tasks.register("phase1Check") {
    description = "Runs all currently available Phase 1 build checks."
    dependsOn(verifyPluginZipContents)
}

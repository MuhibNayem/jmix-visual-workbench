import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

fun sharedProperty(name: String): Provider<String> =
    providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText.map { contents ->
        contents.lineSequence()
            .map(String::trim)
            .first { it.startsWith("$name =") || it.startsWith("$name=") }
            .substringAfter("=")
            .trim()
    }

fun fingerprintWebInputs(inputRoot: File, inputFiles: Set<File>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputFiles.filter(File::isFile)
        .sortedBy { it.relativeTo(inputRoot).invariantSeparatorsPath }
        .forEach { file ->
            digest.update(file.relativeTo(inputRoot).invariantSeparatorsPath.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(file.readBytes())
            digest.update(0)
        }
    return HexFormat.of().formatHex(digest.digest())
}

fun verifyBundledResources(bundleDirectory: File, index: File) {
    val normalizedBundle = bundleDirectory.toPath().toAbsolutePath().normalize()
    val realBundle = normalizedBundle.toRealPath()
    Regex("""(?:src|href)="(?:\./)?([^"#?]+)""")
        .findAll(index.readText())
        .map { it.groupValues[1] }
        .filterNot { it.contains("://") || it.startsWith("data:") }
        .forEach { resource ->
            val resourcePath = Path.of(resource)
            val candidate = normalizedBundle.resolve(resourcePath).normalize()
            check(
                !resourcePath.isAbsolute &&
                    '\\' !in resource &&
                    candidate.startsWith(normalizedBundle) &&
                    Files.isRegularFile(candidate) &&
                    candidate.toRealPath().startsWith(realBundle),
            ) {
                "Invalid or missing bundled resource: $resource"
            }
        }
}

group = sharedProperty("pluginGroup").get()
version = sharedProperty("pluginVersion").get()

val webUiDirectory = layout.projectDirectory.dir("../../../webui")
val webBundleDirectory = layout.projectDirectory.dir("../../build/generated-resources/webui")
val hostMetadataFile = layout.projectDirectory.file("../../build/host-metadata/idea262.json")
val localIdeaPath = providers.gradleProperty("localIdea262Path")
    .orElse(providers.gradleProperty("localIdeaPath"))
val verifiedLocalIdeaPath = localIdeaPath.map { path ->
    val buildFile = file(path).resolve("Resources/build.txt")
    check(buildFile.isFile) { "localIdeaPath has no Resources/build.txt: $path" }
    val buildNumber = buildFile.readText().trim()
    check(buildNumber.startsWith("IU-262.")) {
        "idea262 localIdeaPath must target an IU-262 build, found: $buildNumber"
    }
    path
}
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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

sourceSets {
    main {
        kotlin.srcDir("../../src/main/kotlin")
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    test {
        kotlin.srcDir("../../src/test/kotlin")
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
    intellijPlatform {
        if (localIdeaPath.isPresent) {
            local(verifiedLocalIdeaPath)
        } else {
            intellijIdeaUltimate("2026.2")
        }
        bundledModule("intellij.java.psi")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.properties")
        bundledPlugin("com.intellij.gradle")
        bundledModule("intellij.libraries.jcef")
        bundledModule("intellij.platform.ui.jcef")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        pluginVerifier()
    }
}

tasks.withType<Test>().configureEach {
    listOf(
        "jvw.live.db.enabled",
        "jvw.live.db.id",
        "jvw.live.db.url",
        "jvw.live.db.username",
        "jvw.live.db.password",
        "jvw.live.db.driver",
        "jvw.live.db.driverClasspath",
        "jvw.live.db.catalog",
        "jvw.live.db.schema",
        "jvw.live.db.hostLane",
        "jvw.live.db.evidenceFile",
        "jvw.project.template.runtime.cells",
        "jvw.project.template.java17Home",
        "jvw.project.template.java21Home",
        "jvw.project.template.java25Home",
    ).forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

val lockedConfigurationNames = setOf("runtimeClasspath", "testRuntimeClasspath")

configurations.matching { it.name in lockedConfigurationNames }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

dependencyLocking {
    lockMode = LockMode.STRICT
    lockFile = file("$projectDir/gradle/dependency-locks/gradle.lockfile")
}

tasks.register("verifyLockedConfigurations") {
    description = "Resolves only the standard idea262 runtime configurations under strict lock state."
    notCompatibleWithConfigurationCache("Resolves and inspects the explicitly locked configurations.")
    inputs.file(layout.projectDirectory.file("gradle/dependency-locks/gradle.lockfile"))
    doLast {
        val lockFile = layout.projectDirectory.file("gradle/dependency-locks/gradle.lockfile").asFile
        check(lockFile.isFile) { "idea262 dependency lock state is missing: $lockFile" }
        val lockState = lockFile.readText()

        lockedConfigurationNames.sorted().forEach { configurationName ->
            val configuration = configurations.getByName(configurationName)
            check(configuration.isCanBeResolved) {
                "idea262 $configurationName must remain resolvable."
            }
            configuration.allDependencies.withType(ExternalModuleDependency::class.java).forEach { dependency ->
                val version = dependency.version.orEmpty()
                check(!dependency.isChanging) {
                    "idea262 $configurationName contains changing dependency ${dependency.group}:${dependency.name}:$version"
                }
                check(
                    version.isEmpty() ||
                        (!version.contains("+") &&
                            !version.contains("SNAPSHOT", ignoreCase = true) &&
                            !version.startsWith("latest.", ignoreCase = true) &&
                            !version.contains("[") &&
                            !version.contains("(")),
                ) {
                    "idea262 $configurationName contains dynamic dependency ${dependency.group}:${dependency.name}:$version"
                }
            }
            configuration.incoming.resolutionResult.allComponents.toList()
            check(configurationName in lockState) {
                "idea262 lock state has no entry for $configurationName."
            }
        }
    }
}

intellijPlatform {
    projectName = "jmix-visual-workbench-idea262"
    buildSearchableOptions = false
    pluginConfiguration {
        version.set(sharedProperty("pluginVersion"))
        ideaVersion {
            sinceBuild.set("262")
            untilBuild.set("262.*")
        }
    }
    pluginVerification {
        ides {
            current()
        }
    }
}

val verifyWebBundle = tasks.register("verifyWebBundle") {
    description = "Rejects missing or stale root-generated web resources."
    notCompatibleWithConfigurationCache("Validates resources produced by the aggregate build.")
    inputs.files(webUiInputs)
    inputs.dir(webBundleDirectory)
    doLast {
        val index = webBundleDirectory.file("index.html").asFile
        val manifest = webBundleDirectory.file("build-info.json").asFile
        check(index.isFile) { "Run the root buildWebUi task: generated index.html is missing." }
        check(manifest.isFile) { "Run the root buildWebUi task: build-info.json is missing." }
        val recordedDigest = Regex("\"inputSha256\"\\s*:\\s*\"([0-9a-f]{64})\"")
            .find(manifest.readText())
            ?.groupValues
            ?.get(1)
            ?: error("Generated build-info.json has no inputSha256.")
        val currentDigest = fingerprintWebInputs(webUiDirectory.asFile, webUiInputs.files)
        check(recordedDigest == currentDigest) {
            "Generated web resources are stale: $recordedDigest != $currentDigest"
        }
        verifyBundledResources(webBundleDirectory.asFile, index)
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(verifyWebBundle)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(layout.projectDirectory.dir("../../src/main/resources")) {
        exclude("META-INF/plugin.xml")
    }
    from(layout.projectDirectory.dir("../../build/generated-resources"))
    from(layout.projectDirectory.file("../../../LICENSE"))
    from(layout.projectDirectory.file("../../../NOTICE"))
    from(layout.projectDirectory.file("../../gradle/wrapper/gradle-wrapper.jar")) {
        into("project-template/gradle/wrapper")
    }
    from(files(
        layout.projectDirectory.file("../../gradlew"),
        layout.projectDirectory.file("../../gradlew.bat"),
    )) {
        into("project-template")
    }
}

tasks.named("buildPlugin") {
    dependsOn(verifyWebBundle)
}

tasks.named<Zip>("buildPlugin") {
    archiveFileName.set("jmix-visual-workbench-${project.version}-idea262.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Keep platform tests deterministic and offline. Otherwise the IDE starts an
    // asynchronous Marketplace refresh and may leave pluginsXMLIds.json truncated.
    systemProperty("idea.plugins.host", "https://jmix-workbench.invalid")
    systemProperty("idea.load.plugins.id", "com.intellij.java,org.jetbrains.kotlin,org.jmixworkbench")
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("org.jmixworkbench.toolwindow.WorkbenchToolWindowFactoryIntegrationTest")
    }
}

val hostSmokeTest by intellijPlatformTesting.testIde.registering {
    task {
        description = "Runs the focused idea262 real factory startup and lifecycle integration tests."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        filter {
            includeTestsMatching("org.jmixworkbench.toolwindow.WorkbenchToolWindowFactoryIntegrationTest")
        }
    }
}

val compilerLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

tasks.register("writeToolchainMetadata") {
    description = "Resolves and records the exact idea262 Java compiler launcher."
    notCompatibleWithConfigurationCache("Records resolved toolchain metadata outside this included build.")
    inputs.property("expectedLanguageVersion", 25)
    outputs.file(hostMetadataFile)
    doLast {
        val metadata = compilerLauncher.get().metadata
        check(metadata.languageVersion.asInt() == 25) {
            "idea262 resolved Java ${metadata.languageVersion}, expected Java 25."
        }
        hostMetadataFile.asFile.parentFile.mkdirs()
        hostMetadataFile.asFile.writeText(
            """
            {
              "lane": "idea262",
              "languageVersion": ${metadata.languageVersion.asInt()},
              "vendor": "${metadata.vendor}",
              "installationPath": "${metadata.installationPath.asFile}"
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.register("verifyNoBundledKotlinRuntime") {
    description = "Rejects host artifacts that bundle the IDE-provided Kotlin runtime."
    notCompatibleWithConfigurationCache("Inspects the assembled plugin ZIP.")
    dependsOn(tasks.named("buildPlugin"))
    val archive = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    inputs.file(archive)
    doLast {
        ZipFile(archive.get().asFile).use { zip ->
            val forbidden = zip.entries().asSequence()
                .map { it.name.lowercase() }
                .filter { "kotlin-stdlib" in it || "kotlinx-coroutines" in it }
                .toList()
            check(forbidden.isEmpty()) {
                "idea262 plugin must not bundle Kotlin stdlib/coroutines: $forbidden"
            }
        }
    }
}

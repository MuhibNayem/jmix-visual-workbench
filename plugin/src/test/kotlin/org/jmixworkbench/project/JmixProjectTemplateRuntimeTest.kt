package org.jmixworkbench.project

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in real Gradle/Jmix certification. Run with:
 * -Djvw.project.template.runtime.cells=2.8.2:17,2.8.2:21,3.0.0:21,3.0.0:25
 * and matching -Djvw.project.template.java{17,21,25}Home properties.
 */
class JmixProjectTemplateRuntimeTest {
    @Test
    fun `generated applications addons and composites compile and start`() {
        val cells = System.getProperty("jvw.project.template.runtime.cells")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        if (cells.isEmpty()) return

        val languages = selectedEntries(
            propertyName = "jvw.project.template.runtime.languages",
            entries = JmixProjectLanguage.entries,
        )
        val templateKinds = selectedEntries(
            propertyName = "jvw.project.template.runtime.templates",
            entries = JmixProjectTemplateKind.entries,
        )
        val requestedUiKinds = selectedEntries(
            propertyName = "jvw.project.template.runtime.uiKinds",
            entries = JmixProjectUiKind.entries,
        )
        cells.forEach { cell ->
            val (jmixVersion, javaText) = cell.split(':').also {
                require(it.size == 2) { "Invalid project-template runtime cell '$cell'." }
            }
            val javaVersion = javaText.toInt()
            val javaHome = Path.of(
                requireNotNull(System.getProperty("jvw.project.template.java${javaVersion}Home")) {
                    "Missing jvw.project.template.java${javaVersion}Home for cell $cell."
                },
            )
            assertTrue(Files.isExecutable(javaHome.resolve("bin/java")), "Invalid JAVA_HOME: $javaHome")
            languages.forEach { language ->
                templateKinds.forEach { templateKind ->
                    val uiKinds = if (templateKind == JmixProjectTemplateKind.ADDON) {
                        listOf(JmixProjectUiKind.HEADLESS)
                    } else {
                        requestedUiKinds
                    }
                    uiKinds.forEach { uiKind ->
                        certify(
                            cell = cell,
                            jmixVersion = jmixVersion,
                            javaVersion = javaVersion,
                            javaHome = javaHome,
                            templateKind = templateKind,
                            language = language,
                            uiKind = uiKind,
                        )
                    }
                }
            }
        }
    }

    private fun <T : Enum<T>> selectedEntries(
        propertyName: String,
        entries: List<T>,
    ): List<T> {
        val selected = System.getProperty(propertyName)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        if (selected.isEmpty()) return entries
        return selected.map { value ->
            entries.singleOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error(
                    "Unknown $propertyName value '$value'; expected " +
                        entries.joinToString { it.name.lowercase() },
                )
        }.distinct()
    }

    private fun certify(
        cell: String,
        jmixVersion: String,
        javaVersion: Int,
        javaHome: Path,
        templateKind: JmixProjectTemplateKind,
        language: JmixProjectLanguage,
        uiKind: JmixProjectUiKind,
    ) {
        val variant = "${templateKind.name}-${language.name}-${uiKind.name}".lowercase()
        val root = Files.createTempDirectory(
            "jmix-template-${cell.replace(':', '-')}-$variant-",
        )
        try {
            val generated = JmixProjectTemplateGenerator.generate(
                JmixProjectTemplateRequest(
                    projectName = "Certified Payroll",
                    groupId = "com.acme",
                    artifactId = "certified-payroll",
                    basePackage = "com.acme.payroll",
                    projectId = "payroll",
                    jmixVersion = jmixVersion,
                    javaVersion = javaVersion,
                    templateKind = templateKind,
                    language = language,
                    uiKind = uiKind,
                    locales = listOf("en", "bn"),
                ),
            )
            JmixProjectInstaller.install(root, generated)
            val tasks = when (templateKind) {
                JmixProjectTemplateKind.APPLICATION -> when (uiKind) {
                    JmixProjectUiKind.HEADLESS -> listOf("clean", "test", "run")
                    JmixProjectUiKind.FLOW_UI -> listOf("clean", "test", "bootRun")
                }
                JmixProjectTemplateKind.ADDON -> listOf("clean", "test")
                JmixProjectTemplateKind.COMPOSITE -> when (uiKind) {
                    JmixProjectUiKind.HEADLESS ->
                        listOf("clean", "test", ":application:run")
                    JmixProjectUiKind.FLOW_UI ->
                        listOf("clean", "test", ":application:bootRun")
                }
            }
            val command = buildList {
                add("./gradlew")
                addAll(tasks)
                if (uiKind == JmixProjectUiKind.FLOW_UI) {
                    add("-Pjvw.certifyStartup=true")
                }
                add("--no-daemon")
                add("--no-configuration-cache")
                add("--stacktrace")
            }
            val process = ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["JAVA_HOME"] = javaHome.toString()
                    environment()["PATH"] =
                        "${javaHome.resolve("bin")}:${environment()["PATH"].orEmpty()}"
                    environment()["JMIX_DEV_ADMIN_PASSWORD"] = "runtime-certification-only"
                }
                .start()
            val output = StringBuilder()
            val readerThread = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < 1_000_000) {
                            output.appendLine(line)
                        }
                    }
                }
            }.apply {
                name = "jmix-template-runtime-output"
                isDaemon = true
                start()
            }
            val completed = process.waitFor(12, TimeUnit.MINUTES)
            if (!completed) {
                process.destroyForcibly()
            }
            readerThread.join(10_000)
            assertTrue(completed, "Generated $cell $variant build timed out.\n$output")
            if (process.exitValue() != 0) {
                val testResults = root.resolve("build/test-results")
                if (Files.isDirectory(testResults)) {
                    Files.walk(testResults).use { paths ->
                        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
                            .sorted()
                            .forEach { result ->
                                output.appendLine()
                                output.appendLine("===== ${root.relativize(result)} =====")
                                output.appendLine(Files.readString(result))
                            }
                    }
                }
            }
            assertEquals(
                0,
                process.exitValue(),
                "Generated $cell $variant build failed.\n$output",
            )
            if (templateKind != JmixProjectTemplateKind.ADDON) {
                assertTrue(
                    "using Java $javaVersion" in output,
                    "Generated $cell $variant did not start on the selected Java runtime.\n$output",
                )
            }
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

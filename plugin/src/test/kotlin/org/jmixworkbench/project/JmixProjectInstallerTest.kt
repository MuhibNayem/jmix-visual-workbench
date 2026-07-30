package org.jmixworkbench.project

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JmixProjectInstallerTest {
    @Test
    fun `installs text and integrity-checked wrapper resources`() {
        val root = createTempDirectory("jmix-project-install-")
        try {
            val generated = JmixProjectTemplateGenerator.generate(request())

            val result = JmixProjectInstaller.install(root, generated)

            assertTrue("build.gradle.kts" in result.installedFiles)
            assertTrue(root.resolve("gradle/wrapper/gradle-wrapper.jar").exists())
            assertContainsText(root.resolve("settings.gradle.kts"), """rootProject.name = "Payroll"""")
            assertTrue(Files.size(root.resolve("gradle/wrapper/gradle-wrapper.jar")) > 10_000)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `never overwrites an existing project file`() {
        val root = createTempDirectory("jmix-project-overwrite-")
        try {
            root.resolve("build.gradle.kts").writeText("// user-owned")

            assertFails {
                JmixProjectInstaller.install(
                    root,
                    JmixProjectTemplateGenerator.generate(request()),
                )
            }

            assertEquals("// user-owned", root.resolve("build.gradle.kts").readText())
            assertFalse(root.resolve("settings.gradle.kts").exists())
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `rolls back only wizard-created paths after a mid-install failure`() {
        val root = createTempDirectory("jmix-project-rollback-")
        try {
            root.resolve(".idea").createDirectories()
            root.resolve(".idea/workspace.xml").writeText("<project/>")
            var installed = 0

            assertFails {
                JmixProjectInstaller.install(
                    projectRoot = root,
                    project = JmixProjectTemplateGenerator.generate(request()),
                    resourceLoader = defaultResourceLoader(),
                    probe = JmixProjectInstallProbe {
                        installed++
                        if (installed == 4) error("injected installation failure")
                    },
                )
            }

            assertEquals("<project/>", root.resolve(".idea/workspace.xml").readText())
            assertEquals(
                listOf(".idea", ".idea/workspace.xml"),
                Files.walk(root).use { paths ->
                    paths.filter { it != root }
                        .map { root.relativize(it).toString() }
                        .sorted()
                        .toList()
                },
            )
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `rejects a symlinked generated parent`() {
        val root = createTempDirectory("jmix-project-symlink-")
        val outside = createTempDirectory("jmix-project-outside-")
        try {
            runCatching { Files.createSymbolicLink(root.resolve("src"), outside) }
                .getOrElse { return }

            assertFails {
                JmixProjectInstaller.install(
                    root,
                    JmixProjectTemplateGenerator.generate(request()),
                )
            }
            assertEquals(0, Files.list(outside).use { it.count() })
        } finally {
            deleteTree(root)
            deleteTree(outside)
        }
    }

    private fun request(): JmixProjectTemplateRequest = JmixProjectTemplateRequest(
        projectName = "Payroll",
        groupId = "com.acme",
        artifactId = "payroll",
        basePackage = "com.acme.payroll",
        projectId = "payroll",
        jmixVersion = "2.8.2",
        javaVersion = 17,
    )

    private fun defaultResourceLoader(): JmixProjectResourceLoader =
        JmixProjectResourceLoader { resource ->
            JmixProjectInstallerTest::class.java.getResourceAsStream(resource)
        }

    private fun assertContainsText(
        path: Path,
        expected: String,
    ) {
        assertTrue(expected in path.readText())
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

package org.jmixworkbench.discovery

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformIndependenceTest {
    @Test
    fun `core test runtime excludes IntelliJ Platform artifacts`() {
        val classpathEntries = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { it.replace('\\', '/').lowercase() }

        val forbiddenMarkers = listOf(
            "com.intellij",
            "org.jetbrains.intellij",
            "ideaic-",
            "ideaiu-",
            "intellij-platform",
        )
        val leakedEntries = classpathEntries.filter { entry ->
            forbiddenMarkers.any(entry::contains)
        }

        assertTrue(
            leakedEntries.isEmpty(),
            "phase2CoreTest runtime contains IntelliJ Platform artifacts: $leakedEntries",
        )
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.intellij.openapi.project.Project")
        }
    }
}

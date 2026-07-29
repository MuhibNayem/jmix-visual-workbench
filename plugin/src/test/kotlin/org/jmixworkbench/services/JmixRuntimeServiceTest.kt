package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jmixworkbench.discovery.model.ArtifactKind
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JmixRuntimeServiceTest : HeavyPlatformTestCase() {

    fun testFindsRecoveredApplicationConfigurationInCustomResourceRoot() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(root, "settings.gradle.kts", """include(":operations")""")
            write(root, "build.gradle.kts", "plugins { java }")
            write(
                root,
                "operations/build.gradle.kts",
                """
                plugins {
                    id("io.jmix") version "2.8.3"
                }
                sourceSets {
                    named("main") {
                        resources {
                            srcDir("runtime-resources")
                        }
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "operations/runtime-resources/application.properties",
                """
                server.port=1
                server.servlet.context-path=/operations
                """.trimIndent(),
            )
            write(
                root,
                "operations/runtime-resources/com/acme/operations/operation-list-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="operation-list-view">
                    <layout><span id="title" text="Operations"/></layout>
                </view>
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val descriptor = assertNotNull(
            graph.artifacts.singleOrNull {
                it.kind == ArtifactKind.VIEW_DESCRIPTOR &&
                    it.displayName == "operation-list-view"
            },
            "The descriptor in the custom resource root was not indexed.",
        )
        val response = JmixRuntimeService.getInstance(project).inspect(
            JmixRuntimeInspectionRequest(descriptor.sourceLocator),
        )

        assertTrue(response.accepted, response.issues.toString())
        val target = assertNotNull(
            response.targets.singleOrNull(),
            "The recovered application target was not resolved.",
        )
        assertTrue(target.moduleId.endsWith("#:operations"), target.moduleId)
        assertEquals("operations", target.moduleRoot)
        assertEquals("http://127.0.0.1:1/operations", target.baseUrl)
        assertTrue(
            target.configSources.contains("operations/runtime-resources/application.properties"),
            target.configSources.toString(),
        )
    }

    private fun write(root: VirtualFile, path: String, content: String) {
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            root
        } else {
            requireNotNull(VfsUtil.createDirectoryIfMissing(root, parentPath))
        }
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}

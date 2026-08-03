package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectFileResolverIntegrationTest : HeavyPlatformTestCase() {
    private var externalRootPath: Path? = null

    fun testExternalCompositeContentRootIsIndexedWithSafeAliasAndNavigable() {
        val projectRoot = getOrCreateProjectBaseDir()
        val siblingName = "${projectRoot.name}-external-${System.nanoTime()}"
        val sibling = WriteAction.compute<VirtualFile, RuntimeException> {
            projectRoot.parent.createChildDirectory(this, siblingName)
        }
        externalRootPath = sibling.toNioPath()

        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).newModule(
                "${projectRoot.path}/external-composite.iml",
                ModuleType.EMPTY.id,
            )
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            rootModel.addContentEntry(sibling)
            rootModel.commit()
            write(
                sibling,
                "build.gradle.kts",
                """
                plugins {
                    id("io.jmix") version "2.8.3"
                }
                jmix {
                    projectId.set("addon")
                }
                """.trimIndent(),
            )
            write(
                sibling,
                "src/main/java/com/acme/addon/entity/ExternalEntity.java",
                """
                package com.acme.addon.entity;

                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import java.util.UUID;

                @JmixEntity
                @Entity(name = "addon_ExternalEntity")
                public class ExternalEntity {
                    @Id
                    private UUID id;
                }
                """.trimIndent(),
            )
        }

        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val entity = graph.artifacts.single { artifact ->
            artifact.kind == ArtifactKind.ENTITY && artifact.displayName == "ExternalEntity"
        }
        val locatorPath = entity.sourceLocator.relativePath

        assertFalse(graph.indexHealth.complete)
        assertEquals(1, graph.indexHealth.discoveredSourceRootCount)
        assertTrue(graph.diagnostics.any {
            it.reasonCode == "JVW-INDEX-GRADLE-SYNC-INCOMPLETE"
        })
        assertTrue(graph.indexHealth.moduleCount >= 1)
        val externalCoverage = graph.modules.single { it.moduleId == entity.owner.moduleId }
        assertEquals(1, externalCoverage.contentRootCount)
        assertEquals(1, externalCoverage.indexedFileCount)
        assertEquals(1, externalCoverage.discoveredSourceRootCount)
        assertEquals(externalCoverage.candidateFileCount, externalCoverage.indexedFileCount)
        assertTrue(locatorPath.startsWith("${ProjectFileResolver.EXTERNAL_PREFIX}/"))
        assertFalse(".." in locatorPath)
        assertEquals(
            "addon",
            JmixProjectService.getInstance(project)
                .projectIdForModule(locatorPath.substringBefore("/src/main/")),
        )
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locatorPath)
        assertNotNull(resolved)
        assertTrue(resolved!!.external)
        assertTrue(resolved.file.path.endsWith("/com/acme/addon/entity/ExternalEntity.java"))

        val navigation = ApplicationGraphService.getInstance(project).prepareNavigation(
            SourceNavigationRequest(
                relativePath = locatorPath,
                line = entity.sourceLocator.line,
                column = entity.sourceLocator.column,
                revisionFingerprint = entity.sourceLocator.revisionFingerprint,
            ),
        )
        assertTrue(navigation.success, "${navigation.errorCode}: ${navigation.message}")
        assertTrue(navigation.file?.path?.endsWith("/ExternalEntity.java") == true)

        verifyAtomicCrossRootEditAndHistory(locatorPath, projectRoot)
    }

    override fun tearDown() {
        val path = externalRootPath
        try {
            super.tearDown()
        } finally {
            if (path != null) {
                runCatching {
                    Files.walk(path).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                }
            }
        }
    }

    private fun write(root: VirtualFile, path: String, content: String) {
        val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }

    private fun verifyAtomicCrossRootEditAndHistory(
        entityPath: String,
        projectRoot: VirtualFile,
    ) {
        val resolver = ProjectFileResolver.getInstance(project)
        val entityFile = requireNotNull(resolver.resolveFile(entityPath)?.file)
        val originalEntity = String(entityFile.contentsToByteArray(false), entityFile.charset)
        val originalToken = "public class ExternalEntity"
        val tokenOffset = originalEntity.indexOf(originalToken)
        assertTrue(tokenOffset >= 0)
        val externalPrefix = entityPath.substringBefore("/src/main/")
        val externalCreatedPath =
            "$externalPrefix/src/main/resources/com/acme/addon/workbench-proof.txt"
        val baseCreatedPath = "src/main/resources/workbench-root-proof.txt"
        val changeSet = WorkspaceChangeSet(
            id = "external-composite-atomic-edit",
            label = "Edit external composite module",
            files = listOf(
                WorkspaceFileChange(
                    relativePath = entityPath,
                    mode = WorkspaceFileChangeMode.MODIFY,
                    baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(originalEntity),
                    edits = listOf(
                        WorkspaceTextEdit(
                            startOffset = tokenOffset,
                            endOffset = tokenOffset + originalToken.length,
                            expectedText = originalToken,
                            replacement = "$originalToken /* visual-safe */",
                        ),
                    ),
                ),
                WorkspaceFileChange(
                    relativePath = externalCreatedPath,
                    mode = WorkspaceFileChangeMode.CREATE,
                    baseRevisionFingerprint = null,
                    createContent = "external",
                ),
                WorkspaceFileChange(
                    relativePath = baseCreatedPath,
                    mode = WorkspaceFileChangeMode.CREATE,
                    baseRevisionFingerprint = null,
                    createContent = "root",
                ),
            ),
        )
        val changeService = WorkspaceChangeService.getInstance(project)
        val preview = changeService.preview(changeSet)
        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        val prepared = changeService.prepareApply(
            WorkspaceChangeApplyRequest(changeSet, requireNotNull(preview.planDigest)),
        )
        val applied = changeService.applyPrepared(prepared)
        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
        assertTrue(
            String(entityFile.contentsToByteArray(false), entityFile.charset)
                .contains("ExternalEntity /* visual-safe */"),
        )
        assertEquals(
            "external",
            String(
                requireNotNull(resolver.resolveFile(externalCreatedPath)).file.contentsToByteArray(false),
            ),
        )
        assertEquals(
            "root",
            String(requireNotNull(projectRoot.findFileByRelativePath(baseCreatedPath)).contentsToByteArray(false)),
        )

        val history = WorkspaceHistoryService.getInstance(project)
        val undone = history.undo()
        assertTrue(undone.success, undone.issues.joinToString { "${it.code}: ${it.message}" })
        assertEquals(originalEntity, String(entityFile.contentsToByteArray(false), entityFile.charset))
        assertTrue(resolver.resolveFile(externalCreatedPath) == null)
        assertTrue(projectRoot.findFileByRelativePath(baseCreatedPath) == null)

        val redone = history.redo()
        assertTrue(redone.success, redone.issues.joinToString { "${it.code}: ${it.message}" })
        assertTrue(
            String(entityFile.contentsToByteArray(false), entityFile.charset)
                .contains("ExternalEntity /* visual-safe */"),
        )
        assertNotNull(resolver.resolveFile(externalCreatedPath))
        assertNotNull(projectRoot.findFileByRelativePath(baseCreatedPath))
    }
}

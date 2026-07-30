package org.jmixworkbench.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class WorkspaceMutationFailureSafetyTest : HeavyPlatformTestCase() {
    fun testInjectedFailureRestoresEveryFileAndCreatedDirectory() {
        val fixture = fixture("injected-failure")
        val prepared = fixture.prepare()
        val response = fixture.service.applyPrepared(
            prepared,
            WorkspaceMutationProbe { event ->
                if (
                    event.phase == WorkspaceMutationPhase.AFTER_FILE_MUTATION &&
                    event.fileIndex == 1
                ) {
                    throw IOException("Injected one-shot disk write failure")
                }
            },
        )

        assertFalse(response.success)
        assertTrue(response.issues.any { it.code == "JVW-CHANGE-APPLY-FAILED" })
        fixture.assertOriginalWorkspace()
        assertFalse(WorkspaceHistoryService.getInstance(project).snapshot().canUndo)
    }

    fun testCancellationAfterPartialMutationRestoresEveryByteAndPropagatesCancellation() {
        val fixture = fixture("cancellation")
        val prepared = fixture.prepare()

        try {
            fixture.service.applyPrepared(
                prepared,
                WorkspaceMutationProbe { event ->
                    if (
                        event.phase == WorkspaceMutationPhase.AFTER_FILE_MUTATION &&
                        event.fileIndex == 0
                    ) {
                        throw ProcessCanceledException()
                    }
                },
            )
            fail("Cancellation must propagate to the IntelliJ caller.")
        } catch (_: ProcessCanceledException) {
            // IntelliJ cancellation is not converted into an ordinary failure.
        }

        fixture.assertOriginalWorkspace()
        assertFalse(WorkspaceHistoryService.getInstance(project).snapshot().canUndo)
    }

    fun testConcurrentEditAfterOuterPreflightIsPreservedAndWholePlanIsRejected() {
        val fixture = fixture("concurrent-edit")
        val prepared = fixture.prepare()
        val manual = fixture.originalC + "// concurrent manual edit\n"
        val response = fixture.service.applyPrepared(
            prepared,
            WorkspaceMutationProbe { event ->
                if (event.phase == WorkspaceMutationPhase.AFTER_OUTER_PREFLIGHT) {
                    ApplicationManager.getApplication().runWriteAction {
                        VfsUtil.saveText(fixture.fileC, manual)
                    }
                }
            },
        )

        assertFalse(response.success)
        assertTrue(response.issues.any { it.code == "JVW-CHANGE-STALE" })
        assertEquals(fixture.originalA, VfsUtil.loadText(fixture.fileA))
        assertEquals(manual, VfsUtil.loadText(fixture.fileC))
        assertNull(fixture.root.findFileByRelativePath(fixture.createdPath))
        assertNull(fixture.root.findFileByRelativePath(fixture.createdTopDirectory))
        assertFalse(WorkspaceHistoryService.getInstance(project).snapshot().canUndo)
    }

    fun testInjectedUndoFailureRestoresCompleteAppliedChangeAndRetainsUndoEntry() {
        val fixture = fixture("undo-failure")
        val applied = fixture.apply()
        assertTrue(applied.success, applied.issues.joinToString { "${it.code}: ${it.message}" })
        fixture.assertAppliedWorkspace()

        val history = WorkspaceHistoryService.getInstance(project)
        val undo = history.undo(
            WorkspaceMutationProbe { event ->
                if (
                    event.phase == WorkspaceMutationPhase.AFTER_FILE_MUTATION &&
                    event.fileIndex == 0
                ) {
                    throw IOException("Injected undo failure")
                }
            },
        )

        assertFalse(undo.success)
        assertTrue(undo.issues.any { it.code == "JVW-HISTORY-UNDO-FAILED" })
        fixture.assertAppliedWorkspace()
        assertTrue(undo.history.canUndo)
        assertFalse(undo.history.canRedo)
    }

    fun testWholeChangeUndoAndRedoRestoreFilesAndDirectoryTopology() {
        val fixture = fixture("whole-change")
        assertTrue(fixture.apply().success)

        val history = WorkspaceHistoryService.getInstance(project)
        val undo = history.undo()
        assertTrue(undo.success, undo.issues.joinToString { "${it.code}: ${it.message}" })
        fixture.assertOriginalWorkspace()
        assertFalse(undo.history.canUndo)
        assertTrue(undo.history.canRedo)

        val redo = history.redo()
        assertTrue(redo.success, redo.issues.joinToString { "${it.code}: ${it.message}" })
        fixture.assertAppliedWorkspace()
        assertTrue(redo.history.canUndo)
        assertFalse(redo.history.canRedo)
    }

    private fun fixture(id: String): MutationFixture {
        val root = getOrCreateProjectBaseDir()
        val pathA = "src/main/java/com/company/loan/AtomicA.java"
        val createdPath = "src/main/resources/certification/$id/deep/generated.txt"
        val pathC = "src/main/resources/com/company/loan/atomic-c.properties"
        val originalA = "class AtomicA { String state = \"before\"; }\n"
        val changedA = originalA.replace("before", "after")
        val originalC = "state=before\n"
        val changedC = "state=after\n"
        val fileA = createFile(root, pathA, originalA)
        val fileC = createFile(root, pathC, originalC)
        val changeSet = WorkspaceChangeSet(
            id = "failure-safety-$id",
            label = "Certify atomic workspace mutation $id",
            files = listOf(
                replacement(pathA, originalA, "before", "after"),
                WorkspaceFileChange(
                    relativePath = createdPath,
                    mode = WorkspaceFileChangeMode.CREATE,
                    baseRevisionFingerprint = null,
                    createContent = "generated-after\n",
                ),
                replacement(pathC, originalC, "before", "after"),
            ),
        )
        return MutationFixture(
            root = root,
            fileA = fileA,
            fileC = fileC,
            originalA = originalA,
            changedA = changedA,
            originalC = originalC,
            changedC = changedC,
            createdPath = createdPath,
            createdTopDirectory = "src/main/resources/certification",
            changeSet = changeSet,
            service = WorkspaceChangeService.getInstance(project),
        )
    }

    private fun replacement(
        path: String,
        source: String,
        expected: String,
        replacement: String,
    ): WorkspaceFileChange {
        val offset = source.indexOf(expected)
        return WorkspaceFileChange(
            relativePath = path,
            mode = WorkspaceFileChangeMode.MODIFY,
            baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(source),
            edits = listOf(
                WorkspaceTextEdit(
                    startOffset = offset,
                    endOffset = offset + expected.length,
                    expectedText = expected,
                    replacement = replacement,
                ),
            ),
        )
    }

    private fun createFile(root: VirtualFile, path: String, content: String): VirtualFile =
        WriteAction.compute<VirtualFile, RuntimeException> {
            val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
            parent.findOrCreateChildData(this, path.substringAfterLast('/')).also {
                VfsUtil.saveText(it, content)
            }
        }

    private inner class MutationFixture(
        val root: VirtualFile,
        val fileA: VirtualFile,
        val fileC: VirtualFile,
        val originalA: String,
        val changedA: String,
        val originalC: String,
        val changedC: String,
        val createdPath: String,
        val createdTopDirectory: String,
        val changeSet: WorkspaceChangeSet,
        val service: WorkspaceChangeService,
    ) {
        fun prepare(): PreparedWorkspaceChange {
            val preview = service.preview(changeSet)
            assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
            return service.prepareApply(
                WorkspaceChangeApplyRequest(changeSet, requireNotNull(preview.planDigest)),
            )
        }

        fun apply(): WorkspaceChangeApplyResponse = service.applyPrepared(prepare())

        fun assertOriginalWorkspace() {
            assertEquals(originalA, VfsUtil.loadText(fileA))
            assertEquals(originalC, VfsUtil.loadText(fileC))
            assertNull(root.findFileByRelativePath(createdPath))
            assertNull(root.findFileByRelativePath(createdTopDirectory))
        }

        fun assertAppliedWorkspace() {
            assertEquals(changedA, VfsUtil.loadText(fileA))
            assertEquals(changedC, VfsUtil.loadText(fileC))
            assertEquals(
                "generated-after\n",
                VfsUtil.loadText(requireNotNull(root.findFileByRelativePath(createdPath))),
            )
        }
    }
}

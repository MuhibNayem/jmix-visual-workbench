package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceHistoryServiceTest : HeavyPlatformTestCase() {
    fun testAppliedVisualChangeCanBeUndoneAndRedoneWithExactRevisions() {
        val path = "src/main/resources/com/company/loan/loan-detail-view.xml"
        val original = "<view><layout><textField id=\"amount\"/></layout></view>\n"
        val changed = original.replace("amount", "principal")
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
            VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), original)
        }
        val changeSet = WorkspaceChangeSet(
            id = "flowui-history-test",
            label = "Rename amount field",
            files = listOf(
                WorkspaceFileChange(
                    relativePath = path,
                    mode = WorkspaceFileChangeMode.MODIFY,
                    baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(original),
                    edits = listOf(
                        WorkspaceTextEdit(
                            startOffset = original.indexOf("amount"),
                            endOffset = original.indexOf("amount") + "amount".length,
                            expectedText = "amount",
                            replacement = "principal",
                        ),
                    ),
                ),
            ),
        )
        val workspace = WorkspaceChangeService.getInstance(project)
        val preview = workspace.preview(changeSet)
        val applied = workspace.applyPrepared(
            workspace.prepareApply(
                WorkspaceChangeApplyRequest(changeSet, requireNotNull(preview.planDigest)),
            ),
        )

        assertTrue(applied.success, applied.issues.joinToString { it.message })
        assertEquals(changed, VfsUtil.loadText(requireNotNull(root.findFileByRelativePath(path))))
        assertTrue(WorkspaceHistoryService.getInstance(project).snapshot().canUndo)

        val undone = WorkspaceHistoryService.getInstance(project).undo()
        assertTrue(undone.success, undone.issues.joinToString { it.message })
        assertEquals(original, VfsUtil.loadText(requireNotNull(root.findFileByRelativePath(path))))
        assertEquals(CanonicalDiscoveryJson.sha256(original), undone.revisions[path])
        assertFalse(undone.history.canUndo)
        assertTrue(undone.history.canRedo)

        val redone = WorkspaceHistoryService.getInstance(project).redo()
        assertTrue(redone.success, redone.issues.joinToString { it.message })
        assertEquals(changed, VfsUtil.loadText(requireNotNull(root.findFileByRelativePath(path))))
        assertEquals(CanonicalDiscoveryJson.sha256(changed), redone.revisions[path])
        assertTrue(redone.history.canUndo)
        assertFalse(redone.history.canRedo)
    }

    fun testUndoIsBlockedWhenSourceWasChangedAfterVisualOperation() {
        val path = "src/main/resources/com/company/loan/stale-view.xml"
        val original = "<view><layout/></view>\n"
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
            VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), original)
        }
        val insertion = "<view><layout id=\"main\"/></view>\n"
        val changeSet = WorkspaceChangeSet(
            id = "flowui-history-stale-test",
            label = "Identify layout",
            files = listOf(
                WorkspaceFileChange(
                    relativePath = path,
                    mode = WorkspaceFileChangeMode.MODIFY,
                    baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(original),
                    edits = listOf(
                        WorkspaceTextEdit(
                            startOffset = original.indexOf("/>"),
                            endOffset = original.indexOf("/>"),
                            expectedText = "",
                            replacement = " id=\"main\"",
                        ),
                    ),
                ),
            ),
        )
        val workspace = WorkspaceChangeService.getInstance(project)
        val preview = workspace.preview(changeSet)
        assertTrue(
            workspace.applyPrepared(
                workspace.prepareApply(
                    WorkspaceChangeApplyRequest(changeSet, requireNotNull(preview.planDigest)),
                ),
            ).success,
        )
        assertEquals(insertion, VfsUtil.loadText(requireNotNull(root.findFileByRelativePath(path))))
        WriteAction.run<RuntimeException> {
            VfsUtil.saveText(requireNotNull(root.findFileByRelativePath(path)), insertion + "<!-- manual -->\n")
        }

        val undo = WorkspaceHistoryService.getInstance(project).undo()
        assertFalse(undo.success)
        assertTrue(undo.issues.any { it.code == "JVW-HISTORY-SOURCE-STALE" })
        assertTrue(undo.history.canUndo)
    }
}

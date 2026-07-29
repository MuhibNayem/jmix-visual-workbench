package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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

class WorkspaceDocumentRoundTripTest : HeavyPlatformTestCase() {
    fun testVisualPreviewApplyUndoAndRedoUseUnsavedIntelliJDocument() {
        val path = "src/main/resources/com/company/loan/loan-detail-view.xml"
        val disk = "<view><layout><textField id=\"amount\"/></layout></view>\n"
        val manual = disk.replace("amount", "principal")
        val visual = manual.replace("principal", "approvedPrincipal")
        val file = createFile(path, disk)
        val documentManager = FileDocumentManager.getInstance()
        val document = requireNotNull(documentManager.getDocument(file))
        WriteAction.run<RuntimeException> {
            document.setText(manual)
        }

        val changeSet = replacement(path, manual, "principal", "approvedPrincipal")
        val workspace = WorkspaceChangeService.getInstance(project)
        val preview = workspace.preview(changeSet)

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        assertEquals(manual, preview.files.single().originalContent)
        assertEquals(CanonicalDiscoveryJson.sha256(manual), preview.files.single().beforeFingerprint)
        val applied = workspace.applyPrepared(
            workspace.prepareApply(
                WorkspaceChangeApplyRequest(changeSet, requireNotNull(preview.planDigest)),
            ),
        )

        assertTrue(applied.success, applied.issues.joinToString { it.message })
        assertEquals(visual, document.text)
        assertTrue(documentManager.isFileModified(file))
        assertEquals(disk, VfsUtil.loadText(file))

        val undone = WorkspaceHistoryService.getInstance(project).undo()
        assertTrue(undone.success, undone.issues.joinToString { it.message })
        assertEquals(manual, document.text)
        assertEquals(CanonicalDiscoveryJson.sha256(manual), undone.revisions[path])

        val redone = WorkspaceHistoryService.getInstance(project).redo()
        assertTrue(redone.success, redone.issues.joinToString { it.message })
        assertEquals(visual, document.text)
        assertEquals(CanonicalDiscoveryJson.sha256(visual), redone.revisions[path])
    }

    fun testManualDocumentEditAfterPreviewRejectsApply() {
        val path = "src/main/resources/com/company/loan/stale-view.xml"
        val disk = "<view><layout><textField id=\"amount\"/></layout></view>\n"
        val manual = disk.replace("amount", "principal")
        val afterPreview = manual.replace("principal", "manuallyApproved")
        val file = createFile(path, disk)
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteAction.run<RuntimeException> {
            document.setText(manual)
        }
        val changeSet = replacement(path, manual, "principal", "approvedPrincipal")
        val workspace = WorkspaceChangeService.getInstance(project)
        val preview = workspace.preview(changeSet)
        assertTrue(preview.accepted)

        WriteAction.run<RuntimeException> {
            document.setText(afterPreview)
        }
        val prepared = workspace.prepareApply(
            WorkspaceChangeApplyRequest(changeSet, requireNotNull(preview.planDigest)),
        )
        val applied = workspace.applyPrepared(prepared)

        assertFalse(applied.success)
        assertTrue(applied.issues.any { "STALE" in it.code }, applied.issues.joinToString { it.code })
        assertEquals(afterPreview, document.text)
        assertEquals(disk, VfsUtil.loadText(file))
    }

    private fun createFile(path: String, content: String) =
        WriteAction.compute<com.intellij.openapi.vfs.VirtualFile, RuntimeException> {
            val root = getOrCreateProjectBaseDir()
            val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
            parent.findOrCreateChildData(this, path.substringAfterLast('/')).also {
                VfsUtil.saveText(it, content)
            }
        }

    private fun replacement(
        path: String,
        source: String,
        expected: String,
        replacement: String,
    ): WorkspaceChangeSet {
        val offset = source.indexOf(expected)
        return WorkspaceChangeSet(
            id = "document-round-trip:$path",
            label = "Update FlowUI component",
            files = listOf(
                WorkspaceFileChange(
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
                ),
            ),
        )
    }
}

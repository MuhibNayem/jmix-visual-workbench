package org.jmixworkbench.services

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * One source-of-truth boundary for project text used by visual mutations.
 *
 * An IntelliJ document may contain valid unsaved work that is newer than the
 * VFS byte stream. Reads prefer that document. Writes preserve open or dirty
 * documents inside the caller's write command, while clean unopened files are
 * persisted through the VFS.
 */
internal object ProjectSourceText {
    fun read(file: VirtualFile): String {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        return document?.text ?: String(file.contentsToByteArray(false), file.charset)
    }

    fun write(project: Project, file: VirtualFile, content: String) {
        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getCachedDocument(file)
        val preserveDocumentState = document != null && (
            documentManager.isDocumentUnsaved(document) ||
                FileEditorManager.getInstance(project).isFileOpen(file)
            )
        if (!preserveDocumentState) {
            VfsUtil.saveText(file, content)
            return
        }
        check(documentManager.requestWriting(requireNotNull(document), project)) {
            "IntelliJ denied write access to ${file.name}."
        }
        document.setText(content)
    }
}

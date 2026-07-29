package org.jmixworkbench.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jmixworkbench.discovery.change.PlannedWorkspaceFile
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import java.util.ArrayDeque

/**
 * Project-scoped, revision-checked history for visual workspace mutations.
 *
 * IntelliJ commands remain useful to native editors, while this history gives
 * the JCEF workbench deterministic undo/redo controls and stale-source guards.
 */
@Service(Service.Level.PROJECT)
class WorkspaceHistoryService(
    private val project: Project,
) {
    private val undoEntries = ArrayDeque<HistoryEntry>()
    private val redoEntries = ArrayDeque<HistoryEntry>()
    private var retainedBytes = 0L

    @Synchronized
    fun record(plan: WorkspaceChangePlan) {
        if (!plan.accepted || plan.files.isEmpty()) return
        val entry = HistoryEntry(
            changeSetId = plan.changeSetId,
            label = plan.label,
            files = plan.files,
            retainedBytes = plan.files.sumOf { file ->
                (file.originalContent?.toByteArray(Charsets.UTF_8)?.size ?: 0).toLong() +
                    file.resultContent.toByteArray(Charsets.UTF_8).size
            },
        )
        retainedBytes -= redoEntries.sumOf(HistoryEntry::retainedBytes)
        redoEntries.clear()
        undoEntries.addLast(entry)
        retainedBytes += entry.retainedBytes
        trim()
    }

    @Synchronized
    fun snapshot(): WorkspaceHistorySnapshot = snapshotUnsafe()

    @Synchronized
    fun undo(): WorkspaceHistoryMutationResponse {
        val entry = undoEntries.peekLast()
            ?: return noChange("Nothing to undo.")
        val response = mutate(entry, Direction.UNDO)
        if (response.success) {
            undoEntries.removeLast()
            redoEntries.addLast(entry)
        }
        return response.copy(history = snapshotUnsafe())
    }

    @Synchronized
    fun redo(): WorkspaceHistoryMutationResponse {
        val entry = redoEntries.peekLast()
            ?: return noChange("Nothing to redo.")
        val response = mutate(entry, Direction.REDO)
        if (response.success) {
            redoEntries.removeLast()
            undoEntries.addLast(entry)
        }
        return response.copy(history = snapshotUnsafe())
    }

    private fun mutate(entry: HistoryEntry, direction: Direction): WorkspaceHistoryMutationResponse {
        val resolver = ProjectFileResolver.getInstance(project)
        val targets = linkedMapOf<String, ResolvedProjectTarget>()
        entry.files.forEach { planned ->
            targets[planned.relativePath] = resolver.resolveTarget(planned.relativePath)
                ?: return rejected(
                    "JVW-HISTORY-PROJECT-MISSING",
                    "The registered content root for ${planned.relativePath} is unavailable.",
                )
        }
        val preflight = preflight(targets, entry, direction)
        if (preflight != null) {
            return WorkspaceHistoryMutationResponse(
                success = false,
                message = preflight.message,
                changedFiles = emptyList(),
                revisions = emptyMap(),
                history = snapshotUnsafe(),
                issues = listOf(preflight),
            )
        }

        val beforeMutation = entry.files.associate { planned ->
            val target = requireNotNull(targets[planned.relativePath])
            val file = target.root.findFileByRelativePath(target.relativePath)
            planned.relativePath to file?.let { String(it.contentsToByteArray(false), it.charset) }
        }
        return try {
            WriteCommandAction.runWriteCommandAction(
                project,
                "${direction.label} ${entry.label}",
                "jmix-workbench-history:${direction.name.lowercase()}:${entry.changeSetId}",
                Runnable {
                    try {
                        val files = if (direction == Direction.UNDO) entry.files.asReversed() else entry.files
                        files.forEach { planned ->
                            applyFile(requireNotNull(targets[planned.relativePath]), planned, direction)
                        }
                    } catch (failure: Throwable) {
                        restore(targets, beforeMutation)
                        throw failure
                    }
                },
            )
            ApplicationGraphService.getInstance(project).invalidate()
            val revisions = entry.files.mapNotNull { planned ->
                val target = requireNotNull(targets[planned.relativePath])
                val file = target.root.findFileByRelativePath(target.relativePath)
                    ?: return@mapNotNull null
                planned.relativePath to CanonicalDiscoveryJson.sha256(
                    String(file.contentsToByteArray(false), file.charset),
                )
            }.toMap()
            WorkspaceHistoryMutationResponse(
                success = true,
                message = "${direction.pastTense} ${entry.label}.",
                changedFiles = entry.files.map(PlannedWorkspaceFile::relativePath),
                revisions = revisions,
                history = snapshotUnsafe(),
                issues = emptyList(),
            )
        } catch (failure: Throwable) {
            rejected(
                "JVW-HISTORY-${direction.name}-FAILED",
                failure.message ?: "${direction.label} failed and the workspace was restored.",
            )
        }
    }

    private fun preflight(
        targets: Map<String, ResolvedProjectTarget>,
        entry: HistoryEntry,
        direction: Direction,
    ): WorkspaceChangeIssue? {
        entry.files.forEach { planned ->
            val target = targets[planned.relativePath]
                ?: return WorkspaceChangeIssue(
                    "JVW-HISTORY-PROJECT-MISSING",
                    "The registered content root is unavailable.",
                    planned.relativePath,
                )
            val file = target.root.findFileByRelativePath(target.relativePath)
            when (planned.mode) {
                WorkspaceFileChangeMode.MODIFY -> {
                    if (
                        !target.root.isValid ||
                        file == null ||
                        file.isDirectory ||
                        !VfsUtilCore.isAncestor(target.root, file, false)
                    ) {
                        return WorkspaceChangeIssue(
                            "JVW-HISTORY-SOURCE-MISSING",
                            "Cannot ${direction.name.lowercase()} because ${planned.relativePath} is unavailable.",
                            planned.relativePath,
                        )
                    }
                    val current = CanonicalDiscoveryJson.sha256(
                        String(file.contentsToByteArray(false), file.charset),
                    )
                    val expected = if (direction == Direction.UNDO) planned.afterFingerprint else planned.beforeFingerprint
                    if (current != expected) {
                        return WorkspaceChangeIssue(
                            "JVW-HISTORY-SOURCE-STALE",
                            "Cannot ${direction.name.lowercase()} because the source changed after this visual operation.",
                            planned.relativePath,
                        )
                    }
                }
                WorkspaceFileChangeMode.CREATE -> {
                    if (direction == Direction.UNDO) {
                        if (file == null || file.isDirectory) {
                            return WorkspaceChangeIssue(
                                "JVW-HISTORY-CREATED-FILE-MISSING",
                                "Cannot undo because the generated file no longer exists.",
                                planned.relativePath,
                            )
                        }
                        val current = CanonicalDiscoveryJson.sha256(
                            String(file.contentsToByteArray(false), file.charset),
                        )
                        if (current != planned.afterFingerprint) {
                            return WorkspaceChangeIssue(
                                "JVW-HISTORY-SOURCE-STALE",
                                "Cannot undo because the generated file was edited afterward.",
                                planned.relativePath,
                            )
                        }
                    } else if (file != null) {
                        return WorkspaceChangeIssue(
                            "JVW-HISTORY-REDO-CONFLICT",
                            "Cannot redo because the generated target already exists.",
                            planned.relativePath,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun applyFile(
        target: ResolvedProjectTarget,
        planned: PlannedWorkspaceFile,
        direction: Direction,
    ) {
        when (planned.mode) {
            WorkspaceFileChangeMode.MODIFY -> {
                val file = target.root.findFileByRelativePath(target.relativePath)
                    ?: error("Source disappeared during ${direction.name.lowercase()}: ${planned.relativePath}")
                val content = if (direction == Direction.UNDO) {
                    planned.originalContent ?: error("Undo source content is unavailable.")
                } else {
                    planned.resultContent
                }
                VfsUtil.saveText(file, content)
            }
            WorkspaceFileChangeMode.CREATE -> {
                if (direction == Direction.UNDO) {
                    target.root.findFileByRelativePath(target.relativePath)
                        ?.delete(this)
                } else {
                    createFile(target, planned.relativePath, planned.resultContent)
                }
            }
        }
    }

    private fun restore(
        targets: Map<String, ResolvedProjectTarget>,
        contents: Map<String, String?>,
    ) {
        contents.forEach { (path, content) ->
            val target = targets[path] ?: return@forEach
            val file = target.root.findFileByRelativePath(target.relativePath)
            when {
                content == null && file != null -> runCatching { file.delete(this) }
                content != null && file == null -> runCatching { createFile(target, path, content) }
                content != null && file != null -> runCatching { VfsUtil.saveText(file, content) }
            }
        }
    }

    private fun createFile(
        target: ResolvedProjectTarget,
        displayPath: String,
        content: String,
    ): VirtualFile {
        val parentPath = target.relativePath.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            target.root
        } else {
            VfsUtil.createDirectoryIfMissing(target.root, parentPath)
                ?: error("Cannot create source directory: $parentPath")
        }
        val fileName = target.relativePath.substringAfterLast('/')
        check(parent.findChild(fileName) == null) { "Target already exists: $displayPath" }
        return parent.createChildData(this, fileName).also { VfsUtil.saveText(it, content) }
    }

    private fun trim() {
        while (undoEntries.size > MAX_ENTRIES || retainedBytes > MAX_RETAINED_BYTES) {
            val removed = undoEntries.pollFirst() ?: break
            retainedBytes -= removed.retainedBytes
        }
    }

    private fun snapshotUnsafe(): WorkspaceHistorySnapshot =
        WorkspaceHistorySnapshot(
            canUndo = undoEntries.isNotEmpty(),
            undoLabel = undoEntries.peekLast()?.label,
            undoDepth = undoEntries.size,
            canRedo = redoEntries.isNotEmpty(),
            redoLabel = redoEntries.peekLast()?.label,
            redoDepth = redoEntries.size,
        )

    private fun noChange(message: String) =
        WorkspaceHistoryMutationResponse(
            success = false,
            message = message,
            changedFiles = emptyList(),
            revisions = emptyMap(),
            history = snapshotUnsafe(),
            issues = emptyList(),
        )

    private fun rejected(code: String, message: String) =
        WorkspaceHistoryMutationResponse(
            success = false,
            message = message,
            changedFiles = emptyList(),
            revisions = emptyMap(),
            history = snapshotUnsafe(),
            issues = listOf(WorkspaceChangeIssue(code, message)),
        )

    private data class HistoryEntry(
        val changeSetId: String,
        val label: String,
        val files: List<PlannedWorkspaceFile>,
        val retainedBytes: Long,
    )

    private enum class Direction(
        val label: String,
        val pastTense: String,
    ) {
        UNDO("Undo", "Undid"),
        REDO("Redo", "Redid"),
    }

    companion object {
        private const val MAX_ENTRIES = 50
        private const val MAX_RETAINED_BYTES = 32L * 1024 * 1024

        fun getInstance(project: Project): WorkspaceHistoryService =
            project.getService(WorkspaceHistoryService::class.java)
    }
}

data class WorkspaceHistorySnapshot(
    val canUndo: Boolean,
    val undoLabel: String?,
    val undoDepth: Int,
    val canRedo: Boolean,
    val redoLabel: String?,
    val redoDepth: Int,
)

data class WorkspaceHistoryMutationResponse(
    val success: Boolean,
    val message: String,
    val changedFiles: List<String>,
    val revisions: Map<String, String>,
    val history: WorkspaceHistorySnapshot,
    val issues: List<WorkspaceChangeIssue>,
)

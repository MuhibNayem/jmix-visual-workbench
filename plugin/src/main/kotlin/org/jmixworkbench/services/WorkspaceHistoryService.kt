package org.jmixworkbench.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
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
    fun record(
        plan: WorkspaceChangePlan,
        createdParentPaths: Map<String, List<String>> = emptyMap(),
    ) {
        if (!plan.accepted || plan.files.isEmpty()) return
        val entry = HistoryEntry(
            changeSetId = plan.changeSetId,
            label = plan.label,
            files = plan.files,
            createdParentPaths = createdParentPaths
                .filterKeys { path -> plan.files.any { it.relativePath == path } }
                .mapValues { (_, paths) -> paths.distinct() },
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
    fun undo(): WorkspaceHistoryMutationResponse =
        undo(WorkspaceMutationProbe.NONE)

    internal fun undo(mutationProbe: WorkspaceMutationProbe): WorkspaceHistoryMutationResponse {
        val entry = undoEntries.peekLast()
            ?: return noChange("Nothing to undo.")
        val response = mutate(entry, Direction.UNDO, mutationProbe)
        if (response.success) {
            undoEntries.removeLast()
            redoEntries.addLast(entry)
        }
        return response.copy(history = snapshotUnsafe())
    }

    @Synchronized
    fun redo(): WorkspaceHistoryMutationResponse =
        redo(WorkspaceMutationProbe.NONE)

    internal fun redo(mutationProbe: WorkspaceMutationProbe): WorkspaceHistoryMutationResponse {
        val entry = redoEntries.peekLast()
            ?: return noChange("Nothing to redo.")
        val response = mutate(entry, Direction.REDO, mutationProbe)
        if (response.success) {
            redoEntries.removeLast()
            undoEntries.addLast(entry)
        }
        return response.copy(history = snapshotUnsafe())
    }

    private fun mutate(
        entry: HistoryEntry,
        direction: Direction,
        mutationProbe: WorkspaceMutationProbe,
    ): WorkspaceHistoryMutationResponse {
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

        return try {
            mutationProbe.observe(
                WorkspaceMutationEvent(
                    operation = direction.operation,
                    phase = WorkspaceMutationPhase.AFTER_OUTER_PREFLIGHT,
                ),
            )
            WriteCommandAction.runWriteCommandAction(
                project,
                "${direction.label} ${entry.label}",
                "jmix-workbench-history:${direction.name.lowercase()}:${entry.changeSetId}",
                Runnable {
                    var snapshots: Map<String, HistoryTargetSnapshot>? = null
                    try {
                        val lockedPreflight = preflight(targets, entry, direction)
                        if (lockedPreflight != null) {
                            throw LockedHistoryPreflightFailure(lockedPreflight)
                        }
                        snapshots = captureSnapshots(targets, entry)
                        mutationProbe.observe(
                            WorkspaceMutationEvent(
                                operation = direction.operation,
                                phase = WorkspaceMutationPhase.AFTER_LOCKED_PREFLIGHT,
                            ),
                        )
                        val files = if (direction == Direction.UNDO) entry.files.asReversed() else entry.files
                        files.forEachIndexed { index, planned ->
                            ProgressManager.checkCanceled()
                            mutationProbe.observe(
                                WorkspaceMutationEvent(
                                    operation = direction.operation,
                                    phase = WorkspaceMutationPhase.BEFORE_FILE_MUTATION,
                                    relativePath = planned.relativePath,
                                    fileIndex = index,
                                ),
                            )
                            applyFile(requireNotNull(targets[planned.relativePath]), planned, direction)
                            mutationProbe.observe(
                                WorkspaceMutationEvent(
                                    operation = direction.operation,
                                    phase = WorkspaceMutationPhase.AFTER_FILE_MUTATION,
                                    relativePath = planned.relativePath,
                                    fileIndex = index,
                                ),
                            )
                        }
                        if (direction == Direction.UNDO) {
                            cleanupCreatedParents(targets, entry.createdParentPaths)
                        }
                        verifyDirection(targets, entry, direction)
                    } catch (failure: Throwable) {
                        val captured = snapshots
                        if (captured != null) {
                            try {
                                mutationProbe.observe(
                                    WorkspaceMutationEvent(
                                        operation = direction.operation,
                                        phase = WorkspaceMutationPhase.BEFORE_ROLLBACK,
                                    ),
                                )
                                restore(targets, captured)
                                mutationProbe.observe(
                                    WorkspaceMutationEvent(
                                        operation = direction.operation,
                                        phase = WorkspaceMutationPhase.AFTER_ROLLBACK,
                                    ),
                                )
                            } catch (rollbackFailure: Throwable) {
                                failure.addSuppressed(rollbackFailure)
                                throw HistoryRollbackFailure(failure, rollbackFailure)
                            }
                        }
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
                    ProjectSourceText.read(file),
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
        } catch (canceled: ProcessCanceledException) {
            throw canceled
        } catch (failure: LockedHistoryPreflightFailure) {
            WorkspaceHistoryMutationResponse(
                success = false,
                message = failure.issue.message,
                changedFiles = emptyList(),
                revisions = emptyMap(),
                history = snapshotUnsafe(),
                issues = listOf(failure.issue),
            )
        } catch (failure: HistoryRollbackFailure) {
            rejected(
                "JVW-HISTORY-${direction.name}-ROLLBACK-FAILED",
                failure.message ?: "${direction.label} failed and exact restoration could not be proven.",
            )
        } catch (failure: Throwable) {
            rejected(
                "JVW-HISTORY-${direction.name}-FAILED",
                failure.message ?: "${direction.label} failed and the workspace was restored.",
            )
        }
    }

    private fun captureSnapshots(
        targets: Map<String, ResolvedProjectTarget>,
        entry: HistoryEntry,
    ): Map<String, HistoryTargetSnapshot> =
        entry.files.associate { planned ->
            val target = requireNotNull(targets[planned.relativePath])
            val file = target.root.findFileByRelativePath(target.relativePath)
            planned.relativePath to HistoryTargetSnapshot(
                content = file?.let(ProjectSourceText::read),
                missingParentPaths = missingParentPaths(target),
            )
        }

    private fun missingParentPaths(target: ResolvedProjectTarget): List<String> {
        val parentPath = target.relativePath.substringBeforeLast('/', "")
        if (parentPath.isBlank()) return emptyList()
        val missing = mutableListOf<String>()
        var current = ""
        parentPath.split('/').filter(String::isNotBlank).forEach { segment ->
            current = if (current.isBlank()) segment else "$current/$segment"
            if (target.root.findFileByRelativePath(current) == null) {
                missing += current
            }
        }
        return missing
    }

    private fun cleanupCreatedParents(
        targets: Map<String, ResolvedProjectTarget>,
        createdParentPaths: Map<String, List<String>>,
    ) {
        createdParentPaths.forEach { (relativePath, directories) ->
            val target = requireNotNull(targets[relativePath])
            directories
                .sortedByDescending { it.count { character -> character == '/' } }
                .forEach { directoryPath ->
                    val directory = target.root.findFileByRelativePath(directoryPath)
                    if (directory != null && directory.isDirectory && directory.children.isEmpty()) {
                        directory.delete(this)
                    }
                }
        }
    }

    private fun verifyDirection(
        targets: Map<String, ResolvedProjectTarget>,
        entry: HistoryEntry,
        direction: Direction,
    ) {
        entry.files.forEach { planned ->
            val target = requireNotNull(targets[planned.relativePath])
            val file = target.root.findFileByRelativePath(target.relativePath)
            val expected = when {
                direction == Direction.UNDO && planned.mode == WorkspaceFileChangeMode.CREATE -> null
                direction == Direction.REDO && planned.mode == WorkspaceFileChangeMode.DELETE -> null
                direction == Direction.UNDO -> planned.originalContent
                else -> planned.resultContent
            }
            when (expected) {
                null -> check(file == null) {
                    "History verification failed: ${planned.relativePath} still exists."
                }
                else -> {
                    check(file != null && !file.isDirectory) {
                        "History verification failed: ${planned.relativePath} is unavailable."
                    }
                    check(ProjectSourceText.read(requireNotNull(file)) == expected) {
                        "History verification failed: ${planned.relativePath} differs from the expected revision."
                    }
                }
            }
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
                        ProjectSourceText.read(file),
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
                            ProjectSourceText.read(file),
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
                WorkspaceFileChangeMode.DELETE -> {
                    if (direction == Direction.UNDO) {
                        if (file != null) {
                            return WorkspaceChangeIssue(
                                "JVW-HISTORY-UNDO-CONFLICT",
                                "Cannot undo because the deleted target was recreated.",
                                planned.relativePath,
                            )
                        }
                    } else {
                        if (
                            !target.root.isValid ||
                            file == null ||
                            file.isDirectory ||
                            !VfsUtilCore.isAncestor(target.root, file, false)
                        ) {
                            return WorkspaceChangeIssue(
                                "JVW-HISTORY-SOURCE-MISSING",
                                "Cannot redo because ${planned.relativePath} is unavailable.",
                                planned.relativePath,
                            )
                        }
                        val current = CanonicalDiscoveryJson.sha256(ProjectSourceText.read(file))
                        if (current != planned.beforeFingerprint) {
                            return WorkspaceChangeIssue(
                                "JVW-HISTORY-SOURCE-STALE",
                                "Cannot redo because the restored source was edited afterward.",
                                planned.relativePath,
                            )
                        }
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
                ProjectSourceText.write(project, file, content)
            }
            WorkspaceFileChangeMode.CREATE -> {
                if (direction == Direction.UNDO) {
                    target.root.findFileByRelativePath(target.relativePath)
                        ?.delete(this)
                } else {
                    createFile(target, planned.relativePath, planned.resultContent)
                }
            }
            WorkspaceFileChangeMode.DELETE -> {
                if (direction == Direction.UNDO) {
                    createFile(
                        target,
                        planned.relativePath,
                        planned.originalContent ?: error("Deleted source content is unavailable."),
                    )
                } else {
                    target.root.findFileByRelativePath(target.relativePath)
                        ?.delete(this)
                }
            }
        }
    }

    private fun restore(
        targets: Map<String, ResolvedProjectTarget>,
        snapshots: Map<String, HistoryTargetSnapshot>,
    ) {
        val restorationFailures = mutableListOf<Throwable>()
        snapshots.entries.toList().asReversed().forEach { (path, snapshot) ->
            val target = requireNotNull(targets[path])
            runCatching {
                val file = target.root.findFileByRelativePath(target.relativePath)
                when {
                    snapshot.content == null && file != null -> file.delete(this)
                    snapshot.content != null && file == null -> createFile(target, path, snapshot.content)
                    snapshot.content != null && file != null ->
                        ProjectSourceText.write(project, file, snapshot.content)
                }
            }.exceptionOrNull()?.let(restorationFailures::add)
        }
        snapshots.forEach { (path, snapshot) ->
            val target = requireNotNull(targets[path])
            snapshot.missingParentPaths
                .sortedByDescending { it.count { character -> character == '/' } }
                .forEach { directoryPath ->
                    runCatching {
                        val directory = target.root.findFileByRelativePath(directoryPath)
                        if (directory != null && directory.isDirectory && directory.children.isEmpty()) {
                            directory.delete(this)
                        }
                    }.exceptionOrNull()?.let(restorationFailures::add)
                }
        }
        snapshots.forEach { (path, snapshot) ->
            val target = requireNotNull(targets[path])
            runCatching {
                val file = target.root.findFileByRelativePath(target.relativePath)
                when (val content = snapshot.content) {
                    null -> check(file == null) {
                        "History rollback verification failed: $path still exists."
                    }
                    else -> {
                        check(file != null && !file.isDirectory) {
                            "History rollback verification failed: $path is unavailable."
                        }
                        check(ProjectSourceText.read(requireNotNull(file)) == content) {
                            "History rollback verification failed: $path differs from its original revision."
                        }
                    }
                }
            }.exceptionOrNull()?.let(restorationFailures::add)
        }
        if (restorationFailures.isNotEmpty()) {
            val failure = IllegalStateException(
                "Exact history rollback failed for ${restorationFailures.size} workspace operation(s).",
            )
            restorationFailures.forEach(failure::addSuppressed)
            throw failure
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
        val createdParentPaths: Map<String, List<String>>,
        val retainedBytes: Long,
    )

    private data class HistoryTargetSnapshot(
        val content: String?,
        val missingParentPaths: List<String>,
    )

    private class LockedHistoryPreflightFailure(
        val issue: WorkspaceChangeIssue,
    ) : IllegalStateException(issue.message)

    private class HistoryRollbackFailure(
        mutationFailure: Throwable,
        rollbackFailure: Throwable,
    ) : IllegalStateException(
        "The history mutation failed (${mutationFailure.message ?: mutationFailure::class.java.simpleName}) " +
            "and exact rollback also failed (${rollbackFailure.message ?: rollbackFailure::class.java.simpleName}).",
        mutationFailure,
    )

    private enum class Direction(
        val label: String,
        val pastTense: String,
        val operation: WorkspaceMutationOperation,
    ) {
        UNDO("Undo", "Undid", WorkspaceMutationOperation.UNDO),
        REDO("Redo", "Redid", WorkspaceMutationOperation.REDO),
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

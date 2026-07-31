package org.jmixworkbench.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jmixworkbench.discovery.change.PlannedWorkspaceFile
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangePlanner
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator

@Service(Service.Level.PROJECT)
class WorkspaceChangeService(
    private val project: Project,
) {
    fun preview(changeSet: WorkspaceChangeSet): WorkspaceChangePreviewResponse =
        prepare(changeSet).preview()

    fun prepareApply(request: WorkspaceChangeApplyRequest): PreparedWorkspaceChange {
        val prepared = prepare(request.changeSet)
        if (!prepared.plan.accepted) {
            return prepared
        }
        if (request.expectedPlanDigest.isBlank() ||
            request.expectedPlanDigest != prepared.plan.planDigest
        ) {
            return PreparedWorkspaceChange(
                plan = prepared.plan.copy(
                    accepted = false,
                    planDigest = null,
                    files = emptyList(),
                    issues = listOf(
                        WorkspaceChangeIssue(
                            code = "JVW-CHANGE-PREVIEW-STALE",
                            message = "The approved preview no longer matches the current workspace.",
                        ),
                    ),
                ),
                baseDir = prepared.baseDir,
                targets = prepared.targets,
            )
        }
        return prepared
    }

    fun applyPrepared(prepared: PreparedWorkspaceChange): WorkspaceChangeApplyResponse =
        applyPrepared(prepared, WorkspaceMutationProbe.NONE)

    internal fun applyPrepared(
        prepared: PreparedWorkspaceChange,
        mutationProbe: WorkspaceMutationProbe,
    ): WorkspaceChangeApplyResponse {
        val plan = prepared.plan
        if (
            !plan.accepted ||
            plan.planDigest == null ||
            prepared.baseDir == null ||
            plan.files.any { it.relativePath !in prepared.targets }
        ) {
            return WorkspaceChangeApplyResponse(
                success = false,
                changeSetId = plan.changeSetId,
                planDigest = plan.planDigest,
                filesChanged = emptyList(),
                issues = plan.issues.ifEmpty {
                    listOf(
                        WorkspaceChangeIssue(
                            code = "JVW-CHANGE-PREPARE-REJECTED",
                            message = "The workspace change did not pass preflight validation.",
                        ),
                    )
                },
            )
        }

        val preflightIssue = preflight(plan, prepared.targets)
        if (preflightIssue != null) {
            return WorkspaceChangeApplyResponse(
                success = false,
                changeSetId = plan.changeSetId,
                planDigest = plan.planDigest,
                filesChanged = emptyList(),
                issues = listOf(preflightIssue),
            )
        }

        return try {
            mutationProbe.observe(
                WorkspaceMutationEvent(
                    operation = WorkspaceMutationOperation.APPLY,
                    phase = WorkspaceMutationPhase.AFTER_OUTER_PREFLIGHT,
                ),
            )
            WriteCommandAction.runWriteCommandAction(
                project,
                plan.label,
                plan.changeSetId,
                Runnable {
                    var snapshots: Map<String, WorkspaceTargetSnapshot>? = null
                    try {
                        val lockedPreflightIssue = preflight(plan, prepared.targets)
                        if (lockedPreflightIssue != null) {
                            throw LockedWorkspacePreflightFailure(lockedPreflightIssue)
                        }
                        snapshots = captureSnapshots(plan, prepared.targets)
                        mutationProbe.observe(
                            WorkspaceMutationEvent(
                                operation = WorkspaceMutationOperation.APPLY,
                                phase = WorkspaceMutationPhase.AFTER_LOCKED_PREFLIGHT,
                            ),
                        )
                        plan.files.forEachIndexed { index, planned ->
                            ProgressManager.checkCanceled()
                            mutationProbe.observe(
                                WorkspaceMutationEvent(
                                    operation = WorkspaceMutationOperation.APPLY,
                                    phase = WorkspaceMutationPhase.BEFORE_FILE_MUTATION,
                                    relativePath = planned.relativePath,
                                    fileIndex = index,
                                ),
                            )
                            when (planned.mode) {
                                WorkspaceFileChangeMode.CREATE -> {
                                    val target = requireNotNull(prepared.targets[planned.relativePath])
                                    createFile(target, planned)
                                }
                                WorkspaceFileChangeMode.MODIFY -> {
                                    val target = requireNotNull(prepared.targets[planned.relativePath])
                                    val file = target.root.findFileByRelativePath(target.relativePath)
                                        ?: error("Source disappeared during write: ${planned.relativePath}")
                                    ProjectSourceText.write(project, file, planned.resultContent)
                                }
                                WorkspaceFileChangeMode.DELETE -> {
                                    val target = requireNotNull(prepared.targets[planned.relativePath])
                                    val file = target.root.findFileByRelativePath(target.relativePath)
                                        ?: error("Source disappeared during deletion: ${planned.relativePath}")
                                    file.delete(this)
                                }
                            }
                            mutationProbe.observe(
                                WorkspaceMutationEvent(
                                    operation = WorkspaceMutationOperation.APPLY,
                                    phase = WorkspaceMutationPhase.AFTER_FILE_MUTATION,
                                    relativePath = planned.relativePath,
                                    fileIndex = index,
                                ),
                            )
                        }
                        verifyApplied(plan, prepared.targets)
                        WorkspaceHistoryService.getInstance(project).record(
                            plan = plan,
                            createdParentPaths = requireNotNull(snapshots).mapValues { it.value.missingParentPaths },
                        )
                    } catch (failure: Throwable) {
                        val captured = snapshots
                        if (captured != null) {
                            try {
                                mutationProbe.observe(
                                    WorkspaceMutationEvent(
                                        operation = WorkspaceMutationOperation.APPLY,
                                        phase = WorkspaceMutationPhase.BEFORE_ROLLBACK,
                                    ),
                                )
                                restoreSnapshots(plan, prepared.targets, captured)
                                mutationProbe.observe(
                                    WorkspaceMutationEvent(
                                        operation = WorkspaceMutationOperation.APPLY,
                                        phase = WorkspaceMutationPhase.AFTER_ROLLBACK,
                                    ),
                                )
                            } catch (rollbackFailure: Throwable) {
                                failure.addSuppressed(rollbackFailure)
                                throw WorkspaceRollbackFailure(failure, rollbackFailure)
                            }
                        }
                        throw failure
                    }
                },
            )
            runCatching { ApplicationGraphService.getInstance(project).invalidate() }
            WorkspaceChangeApplyResponse(
                success = true,
                changeSetId = plan.changeSetId,
                planDigest = plan.planDigest,
                filesChanged = plan.files.map(PlannedWorkspaceFile::relativePath),
                issues = emptyList(),
            )
        } catch (canceled: ProcessCanceledException) {
            throw canceled
        } catch (failure: LockedWorkspacePreflightFailure) {
            WorkspaceChangeApplyResponse(
                success = false,
                changeSetId = plan.changeSetId,
                planDigest = plan.planDigest,
                filesChanged = emptyList(),
                issues = listOf(failure.issue),
            )
        } catch (failure: WorkspaceRollbackFailure) {
            WorkspaceChangeApplyResponse(
                success = false,
                changeSetId = plan.changeSetId,
                planDigest = plan.planDigest,
                filesChanged = emptyList(),
                issues = listOf(
                    WorkspaceChangeIssue(
                        code = "JVW-CHANGE-ROLLBACK-FAILED",
                        message = failure.message
                            ?: "The workspace change failed and exact restoration could not be proven.",
                    ),
                ),
            )
        } catch (failure: Throwable) {
            WorkspaceChangeApplyResponse(
                success = false,
                changeSetId = plan.changeSetId,
                planDigest = plan.planDigest,
                filesChanged = emptyList(),
                issues = listOf(
                    WorkspaceChangeIssue(
                        code = "JVW-CHANGE-APPLY-FAILED",
                        message = failure.message ?: "The atomic workspace change failed and was rolled back.",
                    ),
                ),
            )
        }
    }

    private fun captureSnapshots(
        plan: WorkspaceChangePlan,
        targets: Map<String, ResolvedProjectTarget>,
    ): Map<String, WorkspaceTargetSnapshot> =
        plan.files.associate { planned ->
            val target = requireNotNull(targets[planned.relativePath])
            val file = target.root.findFileByRelativePath(target.relativePath)
            planned.relativePath to WorkspaceTargetSnapshot(
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

    private fun verifyApplied(
        plan: WorkspaceChangePlan,
        targets: Map<String, ResolvedProjectTarget>,
    ) {
        plan.files.forEach { planned ->
            val target = requireNotNull(targets[planned.relativePath])
            val file = target.root.findFileByRelativePath(target.relativePath)
            if (planned.mode == WorkspaceFileChangeMode.DELETE) {
                check(file == null) {
                    "Mutation verification failed: ${planned.relativePath} still exists."
                }
                return@forEach
            }
            check(file != null) {
                "Mutation verification failed: ${planned.relativePath} is missing."
            }
            check(!file.isDirectory) {
                "Mutation verification failed: ${planned.relativePath} is a directory."
            }
            check(ProjectSourceText.read(requireNotNull(file)) == planned.resultContent) {
                "Mutation verification failed: ${planned.relativePath} does not match the approved result."
            }
        }
    }

    private fun restoreSnapshots(
        plan: WorkspaceChangePlan,
        targets: Map<String, ResolvedProjectTarget>,
        snapshots: Map<String, WorkspaceTargetSnapshot>,
    ) {
        val restorationFailures = mutableListOf<Throwable>()
        plan.files.asReversed().forEach { planned ->
            val target = requireNotNull(targets[planned.relativePath])
            val snapshot = requireNotNull(snapshots[planned.relativePath])
            runCatching {
                val file = target.root.findFileByRelativePath(target.relativePath)
                when {
                    snapshot.content == null && file != null -> file.delete(this)
                    snapshot.content != null && file == null ->
                        createFile(target, planned.relativePath, snapshot.content)
                    snapshot.content != null && file != null ->
                        ProjectSourceText.write(project, file, snapshot.content)
                }
            }.exceptionOrNull()?.let(restorationFailures::add)
        }
        snapshots.forEach { (relativePath, snapshot) ->
            val target = requireNotNull(targets[relativePath])
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
        plan.files.forEach { planned ->
            val target = requireNotNull(targets[planned.relativePath])
            val snapshot = requireNotNull(snapshots[planned.relativePath])
            runCatching {
                val file = target.root.findFileByRelativePath(target.relativePath)
                when (val content = snapshot.content) {
                    null -> check(file == null) {
                        "Rollback verification failed: ${planned.relativePath} still exists."
                    }
                    else -> {
                        check(file != null && !file.isDirectory) {
                            "Rollback verification failed: ${planned.relativePath} is unavailable."
                        }
                        check(ProjectSourceText.read(requireNotNull(file)) == content) {
                            "Rollback verification failed: ${planned.relativePath} differs from its original bytes."
                        }
                    }
                }
            }.exceptionOrNull()?.let(restorationFailures::add)
        }
        if (restorationFailures.isNotEmpty()) {
            val failure = IllegalStateException(
                "Exact rollback failed for ${restorationFailures.size} workspace operation(s).",
            )
            restorationFailures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun prepare(changeSet: WorkspaceChangeSet): PreparedWorkspaceChange {
        val baseDir = project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)
        if (baseDir == null) {
            return rejected(
                changeSet,
                "JVW-CHANGE-PROJECT-MISSING",
                "The open project root is unavailable.",
            )
        }
        val currentContent = linkedMapOf<String, String?>()
        val targets = linkedMapOf<String, ResolvedProjectTarget>()
        val resolver = ProjectFileResolver.getInstance(project)
        for (change in changeSet.files) {
            if (!validPath(change.relativePath)) {
                currentContent[change.relativePath] = null
                continue
            }
            val target = resolver.resolveTarget(change.relativePath)
                ?: return rejected(
                    changeSet,
                    "JVW-CHANGE-PATH-REJECTED",
                    "A proposed target is outside the registered project content roots.",
                    change.relativePath,
                    baseDir,
                )
            targets[change.relativePath] = target
            val file = target.root.findFileByRelativePath(target.relativePath)
            if (file == null) {
                currentContent[change.relativePath] = null
                continue
            }
            if (file.isDirectory || !VfsUtilCore.isAncestor(target.root, file, false)) {
                return rejected(
                    changeSet,
                    "JVW-CHANGE-PATH-REJECTED",
                    "A proposed target is outside its registered project content root.",
                    change.relativePath,
                    baseDir,
                )
            }
            currentContent[change.relativePath] = runCatching {
                ProjectSourceText.read(file)
            }.getOrElse {
                return rejected(
                    changeSet,
                    "JVW-CHANGE-SOURCE-UNREADABLE",
                    "A proposed source file cannot be read.",
                    change.relativePath,
                    baseDir,
                )
            }
        }
        return PreparedWorkspaceChange(
            plan = WorkspaceChangePlanner.plan(changeSet, currentContent),
            baseDir = baseDir,
            targets = targets,
        )
    }

    private fun preflight(
        plan: WorkspaceChangePlan,
        targets: Map<String, ResolvedProjectTarget>,
    ): WorkspaceChangeIssue? {
        plan.files.forEach { planned ->
            val target = targets[planned.relativePath]
                ?: return WorkspaceChangeIssue(
                    "JVW-CHANGE-PATH-REJECTED",
                    "The approved target content root is no longer registered.",
                    planned.relativePath,
                )
            if (!target.root.isValid) {
                return WorkspaceChangeIssue(
                    "JVW-CHANGE-PATH-REJECTED",
                    "The approved target content root is no longer available.",
                    planned.relativePath,
                )
            }
            val file = target.root.findFileByRelativePath(target.relativePath)
            when (planned.mode) {
                WorkspaceFileChangeMode.CREATE -> if (file != null) {
                    return WorkspaceChangeIssue(
                        "JVW-CHANGE-CREATE-CONFLICT",
                        "A target file was created after preview.",
                        planned.relativePath,
                    )
                }
                WorkspaceFileChangeMode.MODIFY -> {
                    if (
                        file == null ||
                        file.isDirectory ||
                        !VfsUtilCore.isAncestor(target.root, file, false)
                    ) {
                        return WorkspaceChangeIssue(
                            "JVW-CHANGE-SOURCE-MISSING",
                            "A source file disappeared after preview.",
                            planned.relativePath,
                        )
                    }
                    val fingerprint = runCatching {
                        CanonicalDiscoveryJson.sha256(ProjectSourceText.read(file))
                    }.getOrNull()
                    if (fingerprint == null || fingerprint != planned.beforeFingerprint) {
                        return WorkspaceChangeIssue(
                            "JVW-CHANGE-STALE",
                            "A source file changed after preview.",
                            planned.relativePath,
                        )
                    }
                }
                WorkspaceFileChangeMode.DELETE -> {
                    if (
                        file == null ||
                        file.isDirectory ||
                        !VfsUtilCore.isAncestor(target.root, file, false)
                    ) {
                        return WorkspaceChangeIssue(
                            "JVW-CHANGE-SOURCE-MISSING",
                            "A source selected for deletion disappeared after preview.",
                            planned.relativePath,
                        )
                    }
                    val fingerprint = runCatching {
                        CanonicalDiscoveryJson.sha256(ProjectSourceText.read(file))
                    }.getOrNull()
                    if (fingerprint == null || fingerprint != planned.beforeFingerprint) {
                        return WorkspaceChangeIssue(
                            "JVW-CHANGE-STALE",
                            "A source selected for deletion changed after preview.",
                            planned.relativePath,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun createFile(
        target: ResolvedProjectTarget,
        planned: PlannedWorkspaceFile,
    ): VirtualFile = createFile(target, planned.relativePath, planned.resultContent)

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

    private fun validPath(path: String): Boolean =
        runCatching {
            SourceLocator(relativePath = path, revisionFingerprint = "workspace-change-service")
        }.isSuccess

    private fun rejected(
        changeSet: WorkspaceChangeSet,
        code: String,
        message: String,
        path: String? = null,
        baseDir: VirtualFile? = null,
    ): PreparedWorkspaceChange =
        PreparedWorkspaceChange(
            plan = WorkspaceChangePlan(
                accepted = false,
                changeSetId = changeSet.id,
                label = changeSet.label,
                planDigest = null,
                files = emptyList(),
                issues = listOf(WorkspaceChangeIssue(code, message, path)),
            ),
            baseDir = baseDir,
        )

    companion object {
        fun getInstance(project: Project): WorkspaceChangeService =
            project.getService(WorkspaceChangeService::class.java)
    }

    private data class WorkspaceTargetSnapshot(
        val content: String?,
        val missingParentPaths: List<String>,
    )

    private class LockedWorkspacePreflightFailure(
        val issue: WorkspaceChangeIssue,
    ) : IllegalStateException(issue.message)

    private class WorkspaceRollbackFailure(
        mutationFailure: Throwable,
        rollbackFailure: Throwable,
    ) : IllegalStateException(
        "The workspace mutation failed (${mutationFailure.message ?: mutationFailure::class.java.simpleName}) " +
            "and exact rollback also failed (${rollbackFailure.message ?: rollbackFailure::class.java.simpleName}).",
        mutationFailure,
    )
}

data class WorkspaceChangeApplyRequest(
    val changeSet: WorkspaceChangeSet,
    val expectedPlanDigest: String,
)

data class WorkspaceChangeFilePreview(
    val relativePath: String,
    val mode: WorkspaceFileChangeMode,
    val beforeFingerprint: String?,
    val afterFingerprint: String,
    val originalContent: String?,
    val resultContent: String,
    val appliedEditCount: Int,
)

data class WorkspaceChangePreviewResponse(
    val accepted: Boolean,
    val changeSetId: String,
    val label: String,
    val planDigest: String?,
    val files: List<WorkspaceChangeFilePreview>,
    val issues: List<WorkspaceChangeIssue>,
)

data class WorkspaceChangeApplyResponse(
    val success: Boolean,
    val changeSetId: String,
    val planDigest: String?,
    val filesChanged: List<String>,
    val issues: List<WorkspaceChangeIssue>,
)

data class PreparedWorkspaceChange(
    val plan: WorkspaceChangePlan,
    val baseDir: VirtualFile?,
    val targets: Map<String, ResolvedProjectTarget> = emptyMap(),
) {
    fun preview(): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = plan.accepted,
            changeSetId = plan.changeSetId,
            label = plan.label,
            planDigest = plan.planDigest,
            files = plan.files.map { file ->
                WorkspaceChangeFilePreview(
                    relativePath = file.relativePath,
                    mode = file.mode,
                    beforeFingerprint = file.beforeFingerprint,
                    afterFingerprint = file.afterFingerprint,
                    originalContent = file.originalContent,
                    resultContent = file.resultContent,
                    appliedEditCount = file.appliedEditCount,
                )
            },
            issues = plan.issues,
        )
}

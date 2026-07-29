package org.jmixworkbench.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
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

    fun applyPrepared(prepared: PreparedWorkspaceChange): WorkspaceChangeApplyResponse {
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

        val modifiedFiles = mutableListOf<PlannedWorkspaceFile>()
        val createdFiles = mutableListOf<VirtualFile>()
        return try {
            WriteCommandAction.runWriteCommandAction(
                project,
                plan.label,
                plan.changeSetId,
                Runnable {
                    try {
                        plan.files.forEach { planned ->
                            when (planned.mode) {
                                WorkspaceFileChangeMode.CREATE -> {
                                    val target = requireNotNull(prepared.targets[planned.relativePath])
                                    val created = createFile(target, planned)
                                    createdFiles += created
                                }
                                WorkspaceFileChangeMode.MODIFY -> {
                                    val target = requireNotNull(prepared.targets[planned.relativePath])
                                    val file = target.root.findFileByRelativePath(target.relativePath)
                                        ?: error("Source disappeared during write: ${planned.relativePath}")
                                    ProjectSourceText.write(project, file, planned.resultContent)
                                    modifiedFiles += planned
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        modifiedFiles.asReversed().forEach { planned ->
                            val target = prepared.targets[planned.relativePath]
                            val file = target?.root?.findFileByRelativePath(target.relativePath)
                            if (file != null && planned.originalContent != null) {
                                runCatching { ProjectSourceText.write(project, file, planned.originalContent) }
                            }
                        }
                        createdFiles.asReversed().forEach { file ->
                            if (file.isValid) {
                                runCatching { file.delete(this) }
                            }
                        }
                        throw failure
                    }
                },
            )
            ApplicationGraphService.getInstance(project).invalidate()
            WorkspaceHistoryService.getInstance(project).record(plan)
            WorkspaceChangeApplyResponse(
                success = true,
                changeSetId = plan.changeSetId,
                planDigest = plan.planDigest,
                filesChanged = plan.files.map(PlannedWorkspaceFile::relativePath),
                issues = emptyList(),
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
            }
        }
        return null
    }

    private fun createFile(
        target: ResolvedProjectTarget,
        planned: PlannedWorkspaceFile,
    ): VirtualFile {
        val parentPath = target.relativePath.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            target.root
        } else {
            VfsUtil.createDirectoryIfMissing(target.root, parentPath)
                ?: error("Cannot create source directory: $parentPath")
        }
        val fileName = target.relativePath.substringAfterLast('/')
        check(parent.findChild(fileName) == null) { "Target already exists: ${planned.relativePath}" }
        return parent.createChildData(this, fileName).also { VfsUtil.saveText(it, planned.resultContent) }
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

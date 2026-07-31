package org.jmixworkbench.discovery.change

import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator

enum class WorkspaceFileChangeMode {
    CREATE,
    MODIFY,
    DELETE,
}

data class WorkspaceTextEdit(
    val startOffset: Int,
    val endOffset: Int,
    val expectedText: String,
    val replacement: String,
)

data class WorkspaceFileChange(
    val relativePath: String,
    val mode: WorkspaceFileChangeMode,
    val baseRevisionFingerprint: String?,
    val edits: List<WorkspaceTextEdit> = emptyList(),
    val createContent: String? = null,
)

data class WorkspaceChangeSet(
    val id: String,
    val label: String,
    val files: List<WorkspaceFileChange>,
)

data class WorkspaceChangeIssue(
    val code: String,
    val message: String,
    val relativePath: String? = null,
)

data class PlannedWorkspaceFile(
    val relativePath: String,
    val mode: WorkspaceFileChangeMode,
    val beforeFingerprint: String?,
    val afterFingerprint: String,
    val originalContent: String?,
    val resultContent: String,
    val appliedEditCount: Int,
)

data class WorkspaceChangePlan(
    val accepted: Boolean,
    val changeSetId: String,
    val label: String,
    val planDigest: String?,
    val files: List<PlannedWorkspaceFile>,
    val issues: List<WorkspaceChangeIssue>,
)

object WorkspaceChangePlanner {
    private const val MAX_FILES = 100
    private const val MAX_EDITS_PER_FILE = 2_000
    private const val MAX_RESULT_BYTES_PER_FILE = 4 * 1024 * 1024
    private const val MAX_TOTAL_RESULT_BYTES = 32 * 1024 * 1024

    fun plan(
        changeSet: WorkspaceChangeSet,
        currentContent: Map<String, String?>,
    ): WorkspaceChangePlan {
        val issues = mutableListOf<WorkspaceChangeIssue>()
        if (changeSet.id.isBlank() || changeSet.id.length > 200) {
            issues += issue("JVW-CHANGE-ID-INVALID", "A stable change-set identifier is required.")
        }
        if (changeSet.label.isBlank() || changeSet.label.length > 200) {
            issues += issue("JVW-CHANGE-LABEL-INVALID", "A concise change label is required.")
        }
        if (changeSet.files.isEmpty() || changeSet.files.size > MAX_FILES) {
            issues += issue(
                "JVW-CHANGE-FILE-COUNT-INVALID",
                "A change set must contain between 1 and $MAX_FILES files.",
            )
        }

        val duplicatePaths = changeSet.files.groupingBy(WorkspaceFileChange::relativePath)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicatePaths.sorted().forEach { path ->
            issues += issue("JVW-CHANGE-DUPLICATE-PATH", "A file can occur only once in a change set.", path)
        }

        val planned = changeSet.files.sortedBy(WorkspaceFileChange::relativePath).mapNotNull { fileChange ->
            planFile(fileChange, currentContent, issues)
        }
        val totalBytes = planned.sumOf { it.resultContent.toByteArray(Charsets.UTF_8).size.toLong() }
        if (totalBytes > MAX_TOTAL_RESULT_BYTES) {
            issues += issue(
                "JVW-CHANGE-TOTAL-SIZE-EXCEEDED",
                "The proposed result exceeds the reviewed ${MAX_TOTAL_RESULT_BYTES / (1024 * 1024)} MiB change-set limit.",
            )
        }

        if (issues.isNotEmpty()) {
            return WorkspaceChangePlan(
                accepted = false,
                changeSetId = changeSet.id,
                label = changeSet.label,
                planDigest = null,
                files = emptyList(),
                issues = issues.distinct(),
            )
        }

        val digestPayload = buildString {
            append(changeSet.id).append('\u0000').append(changeSet.label).append('\n')
            planned.forEach { file ->
                append(file.relativePath).append('\u0000')
                    .append(file.mode.name).append('\u0000')
                    .append(file.beforeFingerprint.orEmpty()).append('\u0000')
                    .append(file.afterFingerprint).append('\n')
            }
        }
        return WorkspaceChangePlan(
            accepted = true,
            changeSetId = changeSet.id,
            label = changeSet.label,
            planDigest = CanonicalDiscoveryJson.sha256(digestPayload),
            files = planned,
            issues = emptyList(),
        )
    }

    private fun planFile(
        change: WorkspaceFileChange,
        currentContent: Map<String, String?>,
        issues: MutableList<WorkspaceChangeIssue>,
    ): PlannedWorkspaceFile? {
        val path = change.relativePath
        if (!validRelativePath(path)) {
            issues += issue(
                "JVW-CHANGE-PATH-REJECTED",
                "The target must be an unambiguous project-relative path.",
                path,
            )
            return null
        }
        val current = currentContent[path]
        return when (change.mode) {
            WorkspaceFileChangeMode.CREATE -> planCreate(change, current, issues)
            WorkspaceFileChangeMode.MODIFY -> planModify(change, current, issues)
            WorkspaceFileChangeMode.DELETE -> planDelete(change, current, issues)
        }
    }

    private fun planCreate(
        change: WorkspaceFileChange,
        current: String?,
        issues: MutableList<WorkspaceChangeIssue>,
    ): PlannedWorkspaceFile? {
        if (current != null) {
            issues += issue(
                "JVW-CHANGE-CREATE-CONFLICT",
                "Creation was rejected because the target file already exists.",
                change.relativePath,
            )
            return null
        }
        if (change.baseRevisionFingerprint != null || change.edits.isNotEmpty() || change.createContent == null) {
            issues += issue(
                "JVW-CHANGE-CREATE-MALFORMED",
                "A create operation requires content and cannot contain a base revision or text edits.",
                change.relativePath,
            )
            return null
        }
        if (!withinFileLimit(change.createContent, change.relativePath, issues)) {
            return null
        }
        return PlannedWorkspaceFile(
            relativePath = change.relativePath,
            mode = WorkspaceFileChangeMode.CREATE,
            beforeFingerprint = null,
            afterFingerprint = CanonicalDiscoveryJson.sha256(change.createContent),
            originalContent = null,
            resultContent = change.createContent,
            appliedEditCount = 0,
        )
    }

    private fun planModify(
        change: WorkspaceFileChange,
        current: String?,
        issues: MutableList<WorkspaceChangeIssue>,
    ): PlannedWorkspaceFile? {
        if (current == null) {
            issues += issue(
                "JVW-CHANGE-SOURCE-MISSING",
                "Modification was rejected because the indexed source no longer exists.",
                change.relativePath,
            )
            return null
        }
        if (change.createContent != null || change.edits.isEmpty() || change.edits.size > MAX_EDITS_PER_FILE) {
            issues += issue(
                "JVW-CHANGE-EDIT-COUNT-INVALID",
                "A modification requires between 1 and $MAX_EDITS_PER_FILE targeted text edits.",
                change.relativePath,
            )
            return null
        }
        val currentFingerprint = CanonicalDiscoveryJson.sha256(current)
        if (change.baseRevisionFingerprint.isNullOrBlank() ||
            change.baseRevisionFingerprint != currentFingerprint
        ) {
            issues += issue(
                "JVW-CHANGE-STALE",
                "The source changed after the visual model was created. Refresh and review the change again.",
                change.relativePath,
            )
            return null
        }

        val ordered = change.edits.sortedWith(compareBy(WorkspaceTextEdit::startOffset, WorkspaceTextEdit::endOffset))
        var previous: WorkspaceTextEdit? = null
        ordered.forEach { edit ->
            val invalidRange = edit.startOffset < 0 ||
                edit.endOffset < edit.startOffset ||
                edit.endOffset > current.length
            if (invalidRange) {
                issues += issue(
                    "JVW-CHANGE-RANGE-INVALID",
                    "A text edit range is outside the current source.",
                    change.relativePath,
                )
                return null
            }
            val prior = previous
            if (prior != null &&
                (prior.endOffset > edit.startOffset ||
                    (prior.startOffset == prior.endOffset &&
                        edit.startOffset == edit.endOffset &&
                        prior.startOffset == edit.startOffset))
            ) {
                issues += issue(
                    "JVW-CHANGE-EDITS-OVERLAP",
                    "Text edits must be non-overlapping and deterministic.",
                    change.relativePath,
                )
                return null
            }
            if (current.substring(edit.startOffset, edit.endOffset) != edit.expectedText) {
                issues += issue(
                    "JVW-CHANGE-EXPECTED-TEXT-MISMATCH",
                    "The targeted source text no longer matches the proposed edit.",
                    change.relativePath,
                )
                return null
            }
            previous = edit
        }

        val result = StringBuilder(current).apply {
            ordered.asReversed().forEach { edit ->
                replace(edit.startOffset, edit.endOffset, edit.replacement)
            }
        }.toString()
        if (!withinFileLimit(result, change.relativePath, issues)) {
            return null
        }
        return PlannedWorkspaceFile(
            relativePath = change.relativePath,
            mode = WorkspaceFileChangeMode.MODIFY,
            beforeFingerprint = currentFingerprint,
            afterFingerprint = CanonicalDiscoveryJson.sha256(result),
            originalContent = current,
            resultContent = result,
            appliedEditCount = ordered.size,
        )
    }

    private fun planDelete(
        change: WorkspaceFileChange,
        current: String?,
        issues: MutableList<WorkspaceChangeIssue>,
    ): PlannedWorkspaceFile? {
        if (current == null) {
            issues += issue(
                "JVW-CHANGE-SOURCE-MISSING",
                "Deletion was rejected because the indexed source no longer exists.",
                change.relativePath,
            )
            return null
        }
        val currentFingerprint = CanonicalDiscoveryJson.sha256(current)
        if (
            change.baseRevisionFingerprint.isNullOrBlank() ||
            change.baseRevisionFingerprint != currentFingerprint
        ) {
            issues += issue(
                "JVW-CHANGE-STALE",
                "The source changed after the visual model was created. Refresh and review the deletion again.",
                change.relativePath,
            )
            return null
        }
        if (change.edits.isNotEmpty() || change.createContent != null) {
            issues += issue(
                "JVW-CHANGE-DELETE-MALFORMED",
                "A deletion requires only the exact source revision and cannot contain content or text edits.",
                change.relativePath,
            )
            return null
        }
        return PlannedWorkspaceFile(
            relativePath = change.relativePath,
            mode = WorkspaceFileChangeMode.DELETE,
            beforeFingerprint = currentFingerprint,
            // The digest remains non-null for backwards-compatible previews;
            // DELETE verification is based on target absence, not this value.
            afterFingerprint = CanonicalDiscoveryJson.sha256(""),
            originalContent = current,
            resultContent = "",
            appliedEditCount = 0,
        )
    }

    private fun validRelativePath(path: String): Boolean =
        runCatching {
            SourceLocator(relativePath = path, revisionFingerprint = "workspace-change-validation")
        }.isSuccess

    private fun withinFileLimit(
        content: String,
        path: String,
        issues: MutableList<WorkspaceChangeIssue>,
    ): Boolean {
        if (content.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES_PER_FILE) {
            return true
        }
        issues += issue(
            "JVW-CHANGE-FILE-SIZE-EXCEEDED",
            "The proposed file exceeds the reviewed ${MAX_RESULT_BYTES_PER_FILE / (1024 * 1024)} MiB limit.",
            path,
        )
        return false
    }

    private fun issue(code: String, message: String, path: String? = null): WorkspaceChangeIssue =
        WorkspaceChangeIssue(code, message, path)
}

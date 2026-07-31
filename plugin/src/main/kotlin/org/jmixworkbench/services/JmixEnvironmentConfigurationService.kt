package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import java.awt.BorderLayout
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Credential-contained support for Jmix/Spring external `.env` configuration.
 *
 * Only files explicitly connected through `spring.config.import` are exposed.
 * Secret values never leave this service: JCEF receives redacted inventory and
 * a one-time capability produced by a native IntelliJ password dialog.
 */
@Service(Service.Level.PROJECT)
class JmixEnvironmentConfigurationService(
    private val project: Project,
) {
    private val clock: Clock = Clock.systemUTC()
    private val browserBoundaryKey = ByteArray(32).also(SecureRandom()::nextBytes)
    private val secretCapabilities = linkedMapOf<String, PendingSecretEnvironmentChange>()

    fun inspect(): JmixEnvironmentWorkspace {
        val properties = JmixProjectPropertiesService.getInstance(project).inspect()
        val resolver = ProjectFileResolver.getInstance(project)
        val issues = mutableListOf<JmixEnvironmentIssue>()
        val imports = mutableListOf<ResolvedEnvironmentImport>()

        properties.profiles.forEach { profile ->
            val raw = profile.properties
                .firstOrNull { property -> property.key == SPRING_CONFIG_IMPORT }
                ?.displayValue
                ?: return@forEach
            splitImports(raw).forEach { token ->
                resolveEnvironmentImport(profile, token)?.let(imports::add)
                    ?: issues.add(
                        JmixEnvironmentIssue(
                            code = "JVW-ENV-IMPORT-READ-ONLY",
                            message =
                                "The import '$token' is not a bounded project .env/.env.properties " +
                                    "file and remains read-only.",
                            relativePath = profile.locator.relativePath,
                        ),
                    )
            }
        }

        val references = environmentReferences(properties)
        val files = imports
            .groupBy(ResolvedEnvironmentImport::relativePath)
            .toSortedMap()
            .map { (relativePath, fileImports) ->
                val resolved = resolver.resolveFile(relativePath)?.file
                val source = resolved?.takeIf { file -> !file.isDirectory }
                val content = source?.let { file ->
                    runCatching { ProjectSourceText.read(file) }.getOrElse { failure ->
                        issues += JmixEnvironmentIssue(
                            code = "JVW-ENV-UNREADABLE",
                            message = failure.message ?: "The imported environment file cannot be read.",
                            relativePath = relativePath,
                        )
                        null
                    }
                }
                val document = content?.let(::parseEnvironmentDocument)
                document?.issues?.forEach { issue ->
                    issues += JmixEnvironmentIssue(issue.code, issue.message, relativePath)
                }
                val duplicateNames = document?.entries
                    .orEmpty()
                    .groupingBy(EnvironmentEntry::name)
                    .eachCount()
                    .filterValues { count -> count > 1 }
                    .keys
                val variables = document?.entries
                    .orEmpty()
                    .distinctBy(EnvironmentEntry::name)
                    .map { entry ->
                        val secret =
                            isSecretName(entry.name) ||
                                references[entry.name].orEmpty().any(
                                    JmixEnvironmentReferenceSnapshot::secretProperty,
                                )
                        JmixEnvironmentVariableSnapshot(
                            name = entry.name,
                            displayValue = if (secret) SECRET_REDACTION else entry.value,
                            secret = secret,
                            mutable = entry.name !in duplicateNames && document?.issues.orEmpty().isEmpty(),
                            references = references[entry.name].orEmpty().map { reference ->
                                reference.copy(
                                    profileLocator = browserLocator(reference.profileLocator),
                                )
                            },
                        )
                    }
                    .sortedBy(JmixEnvironmentVariableSnapshot::name)
                JmixEnvironmentFileSnapshot(
                    relativePath = relativePath,
                    locator = content?.let {
                        SourceLocator(
                            relativePath,
                            revisionFingerprint = opaqueRevision(
                                relativePath,
                                CanonicalDiscoveryJson.sha256(it),
                            ),
                        )
                    },
                    existing = content != null,
                    mutable = content == null || document?.issues?.isEmpty() == true,
                    importedBy = fileImports.map { imported ->
                        JmixEnvironmentImportSnapshot(
                            profileLocator = browserLocator(imported.profile.locator),
                            profile = imported.profile.profile,
                            modulePath = imported.profile.modulePath,
                            optional = imported.optional,
                            declaration = imported.declaration,
                        )
                    }.distinctBy { imported ->
                        "${imported.profileLocator.relativePath}\u0000${imported.declaration}"
                    },
                    variables = variables,
                )
            }

        val activations = activationSnapshots(properties, imports, files, issues)
            .map { activation ->
                activation.copy(
                    declarationLocator = activation.declarationLocator?.let(::browserLocator),
                )
            }
        val launchConfigurations = launchConfigurationSnapshots(resolver, issues)
        val internalDigest = CanonicalDiscoveryJson.sha256(
            buildString {
                append(properties.snapshotDigest).append('\u0000')
                files.forEach { file ->
                    append(file.relativePath).append('\u0000')
                        .append(file.locator?.revisionFingerprint.orEmpty()).append('\u0000')
                    file.variables.forEach { variable ->
                        append(variable.name).append('\u0000')
                            .append(variable.secret).append('\u0000')
                            .append(if (variable.secret) SECRET_REDACTION else variable.displayValue)
                            .append('\n')
                    }
                }
                launchConfigurations.forEach { launch ->
                    append(launch.relativePath).append('\u0000')
                        .append(launch.revisionFingerprint).append('\n')
                }
            },
        )
        return JmixEnvironmentWorkspace(
            files = files,
            connectionCandidates = properties.profiles
                .filterNot { profile ->
                    imports.any { imported -> imported.profile.locator == profile.locator }
                }
                .map { profile ->
                    JmixEnvironmentConnectionCandidate(
                        profileLocator = browserLocator(profile.locator),
                        profile = profile.profile,
                        modulePath = profile.modulePath,
                        proposedRelativePath = listOf(profile.modulePath, ".env")
                            .filter(String::isNotBlank)
                            .joinToString("/"),
                    )
                },
            activations = activations,
            launchConfigurations = launchConfigurations,
            issues = issues.distinct().sortedWith(
                compareBy(JmixEnvironmentIssue::code, JmixEnvironmentIssue::relativePath),
            ),
            snapshotDigest = keyedDigest("workspace\u0000$internalDigest"),
        )
    }

    /**
     * Resolves an opaque browser locator without ever returning the source-content
     * fingerprint to JCEF. Navigation is performed by the native bridge only.
     */
    fun prepareNavigation(
        request: JmixEnvironmentNavigationRequest,
    ): PreparedSourceNavigation {
        val workspace = inspect()
        val allowedLocators = buildList {
            workspace.files.forEach { file ->
                file.locator?.let(::add)
                file.importedBy.forEach { imported -> add(imported.profileLocator) }
                file.variables.forEach { variable ->
                    variable.references.forEach { reference -> add(reference.profileLocator) }
                }
            }
            workspace.connectionCandidates.forEach { candidate -> add(candidate.profileLocator) }
            workspace.activations.forEach { activation ->
                activation.declarationLocator?.let(::add)
            }
            workspace.launchConfigurations.forEach { launch ->
                add(
                    SourceLocator(
                        relativePath = launch.relativePath,
                        revisionFingerprint = launch.revisionFingerprint,
                    ),
                )
            }
        }
        if (allowedLocators.none { locator ->
                locator.relativePath == request.relativePath &&
                    locator.revisionFingerprint == request.revisionFingerprint
            }
        ) {
            return PreparedSourceNavigation.failure(
                "JVW-ENV-NAVIGATION-TOKEN-INVALID",
                "The environment source token is missing, stale or outside the reviewed configuration.",
            )
        }
        val file = ProjectFileResolver.getInstance(project)
            .resolveFile(request.relativePath)
            ?.file
            ?.takeIf { candidate -> !candidate.isDirectory }
            ?: return PreparedSourceNavigation.failure(
                "JVW-ENV-NAVIGATION-SOURCE-MISSING",
                "The reviewed environment source no longer exists.",
            )
        val realFingerprint = runCatching {
            CanonicalDiscoveryJson.sha256(ProjectSourceText.read(file))
        }.getOrNull() ?: return PreparedSourceNavigation.failure(
            "JVW-ENV-NAVIGATION-SOURCE-UNREADABLE",
            "The reviewed environment source can no longer be read.",
        )
        if (opaqueRevision(request.relativePath, realFingerprint) != request.revisionFingerprint) {
            return PreparedSourceNavigation.failure(
                "JVW-ENV-NAVIGATION-STALE",
                "The environment source changed after inspection. Refresh and navigate again.",
            )
        }
        return ApplicationGraphService.getInstance(project).prepareNavigation(
            SourceNavigationRequest(
                relativePath = request.relativePath,
                line = request.line,
                column = request.column,
                revisionFingerprint = realFingerprint,
            ),
        )
    }

    /**
     * Prevents generic visual undo/redo responses from exposing raw hashes for
     * environment or application-profile files that may contain credentials.
     */
    fun browserSafeHistoryResponse(
        response: WorkspaceHistoryMutationResponse,
    ): WorkspaceHistoryMutationResponse {
        if (response.revisions.isEmpty()) return response
        return response.copy(
            revisions = response.revisions.mapValues { (relativePath, revision) ->
                if (SENSITIVE_CONFIGURATION_FILE.matches(relativePath.substringAfterLast('/'))) {
                    opaqueRevision(relativePath, revision)
                } else {
                    revision
                }
            },
        )
    }

    fun previewChange(request: JmixEnvironmentChangeRequest): WorkspaceChangePreviewResponse {
        if (isSecretName(request.variableName)) {
            return rejectedPreview(
                "JVW-ENV-SECRET-NATIVE-REQUIRED",
                "Secret variables can be changed only through the native IntelliJ secure dialog.",
                request.relativePath,
            )
        }
        val proposal = proposeChange(request)
        return proposal.changeSet
            ?.let { changeSet ->
                WorkspaceChangeService.getInstance(project).preview(changeSet)
                    .let { preview ->
                        browserSafePreview(
                            preview = preview,
                            variableName = request.variableName,
                            secret = false,
                            externalPlanDigest = preview.planDigest?.let { digest ->
                                opaquePlanDigest(changeSet.id, digest)
                            },
                        )
                    }
            }
            ?: proposal.rejectedPreview()
    }

    fun prepareChange(request: JmixEnvironmentChangeApplyRequest): PreparedWorkspaceChange {
        if (isSecretName(request.change.variableName)) {
            return rejectedPrepared(
                "JVW-ENV-SECRET-NATIVE-REQUIRED",
                "Secret variables can be changed only through the native IntelliJ secure dialog.",
                request.change.relativePath,
            )
        }
        val proposal = proposeChange(request.change)
        val changeSet = proposal.changeSet ?: return proposal.rejectedPrepared()
        val preview = WorkspaceChangeService.getInstance(project).preview(changeSet)
        val realPlanDigest = preview.planDigest
            ?: return rejectedPrepared(
                "JVW-ENV-CHANGE-REJECTED",
                preview.issues.joinToString { it.message }.ifBlank {
                    "The environment change did not pass preflight validation."
                },
                request.change.relativePath,
            )
        if (
            request.expectedPlanDigest.isBlank() ||
            request.expectedPlanDigest != opaquePlanDigest(changeSet.id, realPlanDigest)
        ) {
            return rejectedPrepared(
                "JVW-CHANGE-PREVIEW-STALE",
                "The approved environment preview no longer matches the current workspace.",
                request.change.relativePath,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, realPlanDigest),
        )
    }

    fun previewConnection(
        request: JmixEnvironmentConnectionRequest,
    ): WorkspaceChangePreviewResponse {
        val proposal = proposeConnection(request)
        return proposal.changeSet
            ?.let { changeSet ->
                WorkspaceChangeService.getInstance(project).preview(changeSet).let { preview ->
                    preview.copy(
                        planDigest = preview.planDigest?.let { digest ->
                            opaquePlanDigest(changeSet.id, digest)
                        },
                        files = preview.files.map { file ->
                            file.copy(
                                beforeFingerprint = null,
                                afterFingerprint = SECRET_REDACTION,
                                originalContent = null,
                                resultContent =
                                    "spring.config.import += optional:file:${request.environmentFile}[.properties]",
                            )
                        },
                    )
                }
            }
            ?: proposal.rejectedPreview()
    }

    fun prepareConnection(
        request: JmixEnvironmentConnectionApplyRequest,
    ): PreparedWorkspaceChange {
        val proposal = proposeConnection(request.change)
        val changeSet = proposal.changeSet ?: return proposal.rejectedPrepared()
        val preview = WorkspaceChangeService.getInstance(project).preview(changeSet)
        val realPlanDigest = preview.planDigest
            ?: return rejectedPrepared(
                "JVW-ENV-CONNECTION-REJECTED",
                preview.issues.joinToString { it.message }.ifBlank {
                    "The environment connection did not pass preflight validation."
                },
                request.change.profileLocator.relativePath,
            )
        if (
            request.expectedPlanDigest.isBlank() ||
            request.expectedPlanDigest != opaquePlanDigest(changeSet.id, realPlanDigest)
        ) {
            return rejectedPrepared(
                "JVW-CHANGE-PREVIEW-STALE",
                "The approved environment connection no longer matches the current workspace.",
                request.change.profileLocator.relativePath,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, realPlanDigest),
        )
    }

    /**
     * Must be invoked on the IntelliJ event dispatch thread.
     */
    fun prepareSecretChange(request: JmixEnvironmentSecretChangeRequest): JmixEnvironmentSecretPreviewResponse {
        val normalizedName = request.variableName.trim()
        if (!ENVIRONMENT_NAME.matches(normalizedName)) {
            return rejectedSecretPreview(
                "JVW-ENV-NAME-INVALID",
                "Environment names must use uppercase letters, numbers and underscores.",
                request.relativePath,
            )
        }
        val dialog = SecretEnvironmentValueDialog(normalizedName)
        if (!dialog.showAndGet()) {
            return rejectedSecretPreview(
                "JVW-ENV-SECRET-CANCELLED",
                "The secure environment-variable change was cancelled.",
                request.relativePath,
            )
        }
        val chars = dialog.secret()
        return try {
            val value = String(chars)
            val change = JmixEnvironmentChangeRequest(
                workspaceDigest = request.workspaceDigest,
                relativePath = request.relativePath,
                locator = request.locator,
                variableName = normalizedName,
                mode = JmixEnvironmentChangeMode.SET,
                value = value,
            )
            val proposal = proposeChange(change, allowSecret = true)
            val changeSet = proposal.changeSet
                ?: return JmixEnvironmentSecretPreviewResponse(
                    accepted = false,
                    capability = null,
                    preview = proposal.rejectedPreview(),
                )
            val preview = WorkspaceChangeService.getInstance(project).preview(changeSet)
            if (!preview.accepted || preview.planDigest == null) {
                return JmixEnvironmentSecretPreviewResponse(
                    accepted = false,
                    capability = null,
                    preview = browserSafePreview(
                        preview = preview,
                        variableName = normalizedName,
                        secret = true,
                        externalPlanDigest = null,
                    ),
                )
            }
            val issued = issueSecretCapability(
                request = request,
                changeSet = changeSet,
                planDigest = preview.planDigest,
            )
            JmixEnvironmentSecretPreviewResponse(
                accepted = true,
                capability = issued.capability,
                preview = browserSafePreview(
                    preview = preview,
                    variableName = normalizedName,
                    secret = true,
                    externalPlanDigest = issued.clientPlanDigest,
                ),
            )
        } finally {
            chars.fill('\u0000')
            dialog.clear()
        }
    }

    fun prepareSecretApply(
        request: JmixEnvironmentSecretApplyRequest,
    ): PreparedWorkspaceChange {
        val pending = synchronized(secretCapabilities) {
            purgeExpiredCapabilities()
            secretCapabilities.remove(request.capability)
        } ?: return rejectedPrepared(
            "JVW-ENV-SECRET-CAPABILITY-INVALID",
            "The secure approval is missing, expired or has already been consumed.",
            null,
        )
        if (
            request.expectedPlanDigest.isBlank() ||
            request.expectedPlanDigest != pending.clientPlanDigest ||
            inspect().snapshotDigest != pending.workspaceDigest
        ) {
            return rejectedPrepared(
                "JVW-ENV-SECRET-CAPABILITY-STALE",
                "The project changed after the secure approval. Reopen the native dialog and review again.",
                pending.relativePath,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(pending.changeSet, pending.realPlanDigest),
        )
    }

    private fun proposeChange(
        request: JmixEnvironmentChangeRequest,
        allowSecret: Boolean = false,
    ): EnvironmentChangeProposal {
        val workspace = inspect()
        if (request.workspaceDigest.isBlank() || request.workspaceDigest != workspace.snapshotDigest) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-WORKSPACE-STALE",
                "Environment configuration changed after it was loaded. Refresh and review again.",
                request.relativePath,
            )
        }
        val normalizedName = request.variableName.trim()
        if (!ENVIRONMENT_NAME.matches(normalizedName)) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-NAME-INVALID",
                "Environment names must use uppercase letters, numbers and underscores.",
                request.relativePath,
            )
        }
        if (!allowSecret && isSecretName(normalizedName)) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-SECRET-NATIVE-REQUIRED",
                "Secret variables can be changed only through the native IntelliJ secure dialog.",
                request.relativePath,
            )
        }
        val file = workspace.files.singleOrNull { candidate ->
            candidate.relativePath == request.relativePath
        } ?: return EnvironmentChangeProposal.rejected(
            "JVW-ENV-FILE-UNIMPORTED",
            "Only a project .env file explicitly connected through spring.config.import can be changed.",
            request.relativePath,
        )
        if (!file.mutable) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-FILE-READ-ONLY",
                "The imported environment file contains ambiguous or unsupported syntax.",
                request.relativePath,
            )
        }
        if (
            !allowSecret &&
            file.variables.firstOrNull { variable -> variable.name == normalizedName }?.secret == true
        ) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-SECRET-NATIVE-REQUIRED",
                "Secret variables can be changed only through the native IntelliJ secure dialog.",
                request.relativePath,
            )
        }
        if (file.locator != request.locator) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-FILE-STALE",
                "The imported environment file changed after inspection.",
                request.relativePath,
            )
        }
        val resolver = ProjectFileResolver.getInstance(project)
        val current = resolver.resolveFile(request.relativePath)?.file
        val content = current?.let(ProjectSourceText::read)
        val fingerprint = content?.let(CanonicalDiscoveryJson::sha256)
        if (
            content != null &&
            opaqueRevision(request.relativePath, requireNotNull(fingerprint)) !=
            request.locator?.revisionFingerprint
        ) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-FILE-STALE",
                "The imported environment file changed after inspection.",
                request.relativePath,
            )
        }
        val document = content?.let(::parseEnvironmentDocument)
            ?: EnvironmentDocument(emptyList(), "\n", emptyList())
        if (document.issues.isNotEmpty()) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-FILE-READ-ONLY",
                "The imported environment file contains ambiguous or unsupported syntax.",
                request.relativePath,
            )
        }
        val entries = document.entries.filter { entry -> entry.name == normalizedName }
        if (entries.size > 1) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-DUPLICATE",
                "$normalizedName is declared more than once and cannot be changed visually.",
                request.relativePath,
            )
        }
        val entry = entries.singleOrNull()
        val edit = when (request.mode) {
            JmixEnvironmentChangeMode.SET -> {
                val value = request.value
                    ?: return EnvironmentChangeProposal.rejected(
                        "JVW-ENV-VALUE-MISSING",
                        "A value is required when setting $normalizedName.",
                        request.relativePath,
                    )
                validateEnvironmentValue(value)?.let { message ->
                    return EnvironmentChangeProposal.rejected(
                        "JVW-ENV-VALUE-INVALID",
                        message,
                        request.relativePath,
                    )
                }
                val encoded = encodeEnvironmentValue(value, entry?.quote)
                if (entry != null) {
                    WorkspaceTextEdit(
                        startOffset = entry.valueStartOffset,
                        endOffset = entry.valueEndOffset,
                        expectedText = requireNotNull(content).substring(
                            entry.valueStartOffset,
                            entry.valueEndOffset,
                        ),
                        replacement = encoded,
                    )
                } else {
                    val existing = content.orEmpty()
                    val prefix = when {
                        existing.isEmpty() || existing.endsWith('\n') || existing.endsWith('\r') -> ""
                        else -> document.lineSeparator
                    }
                    WorkspaceTextEdit(
                        startOffset = existing.length,
                        endOffset = existing.length,
                        expectedText = "",
                        replacement = "$prefix$normalizedName=$encoded${document.lineSeparator}",
                    )
                }
            }

            JmixEnvironmentChangeMode.REMOVE -> {
                if (entry == null) {
                    return EnvironmentChangeProposal.rejected(
                        "JVW-ENV-VARIABLE-MISSING",
                        "$normalizedName is not declared in ${request.relativePath}.",
                        request.relativePath,
                    )
                }
                val references = file.variables
                    .firstOrNull { variable -> variable.name == normalizedName }
                    ?.references
                    .orEmpty()
                if (references.isNotEmpty()) {
                    return EnvironmentChangeProposal.rejected(
                        "JVW-ENV-VARIABLE-REFERENCED",
                        "$normalizedName is still referenced by ${references.first().propertyKey}.",
                        references.first().profileLocator.relativePath,
                    )
                }
                WorkspaceTextEdit(
                    startOffset = entry.lineStartOffset,
                    endOffset = entry.lineEndOffset,
                    expectedText = requireNotNull(content).substring(
                        entry.lineStartOffset,
                        entry.lineEndOffset,
                    ),
                    replacement = "",
                )
            }
        }
        val fileChange = if (content == null) {
            if (request.mode != JmixEnvironmentChangeMode.SET) {
                return EnvironmentChangeProposal.rejected(
                    "JVW-ENV-FILE-MISSING",
                    "The missing imported environment file can only be created by setting a variable.",
                    request.relativePath,
                )
            }
            WorkspaceFileChange(
                relativePath = request.relativePath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = edit.replacement,
            )
        } else {
            WorkspaceFileChange(
                relativePath = request.relativePath,
                mode = WorkspaceFileChangeMode.MODIFY,
                baseRevisionFingerprint = fingerprint,
                edits = listOf(edit),
            )
        }
        val secret = allowSecret || isSecretName(normalizedName)
        return EnvironmentChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "environment-${
                    request.mode.name.lowercase()
                }:${keyedDigest(
                    "change-set\u0000${request.relativePath}\u0000$fingerprint\u0000" +
                        "$normalizedName\u0000${request.mode}",
                ).take(24)}",
                label = when (request.mode) {
                    JmixEnvironmentChangeMode.SET ->
                        if (secret) "Securely update $normalizedName" else "Update $normalizedName"
                    JmixEnvironmentChangeMode.REMOVE -> "Remove $normalizedName"
                },
                files = listOf(fileChange),
            ),
            issues = emptyList(),
        )
    }

    private fun proposeConnection(
        request: JmixEnvironmentConnectionRequest,
    ): EnvironmentChangeProposal {
        val workspace = inspect()
        if (request.workspaceDigest.isBlank() || request.workspaceDigest != workspace.snapshotDigest) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-WORKSPACE-STALE",
                "Environment configuration changed after it was loaded. Refresh and review again.",
                request.profileLocator.relativePath,
            )
        }
        if (request.environmentFile !in SUPPORTED_ENVIRONMENT_FILES) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-CONNECTION-FILE-INVALID",
                "Only .env and .env.properties project files can be connected.",
                request.profileLocator.relativePath,
            )
        }
        val candidate = workspace.connectionCandidates.singleOrNull { current ->
            current.profileLocator == request.profileLocator
        } ?: return EnvironmentChangeProposal.rejected(
            "JVW-ENV-CONNECTION-STALE",
            "The selected profile is already connected or no longer available.",
            request.profileLocator.relativePath,
        )
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.profileLocator.relativePath)
            ?.file
            ?: return EnvironmentChangeProposal.rejected(
                "JVW-ENV-CONNECTION-PROFILE-MISSING",
                "The selected application profile no longer exists.",
                request.profileLocator.relativePath,
            )
        val content = ProjectSourceText.read(resolved)
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (
            opaqueRevision(request.profileLocator.relativePath, fingerprint) !=
            request.profileLocator.revisionFingerprint
        ) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-CONNECTION-PROFILE-STALE",
                "The selected application profile changed after inspection.",
                request.profileLocator.relativePath,
            )
        }
        val document = JmixProjectPropertiesService.parseEditablePropertiesDocument(content)
        val declarations = document.entries.filter { entry -> entry.key == SPRING_CONFIG_IMPORT }
        if (declarations.size > 1 || declarations.any { entry -> entry.continued }) {
            return EnvironmentChangeProposal.rejected(
                "JVW-ENV-CONNECTION-IMPORT-AMBIGUOUS",
                "spring.config.import is duplicated or continued and remains read-only.",
                request.profileLocator.relativePath,
            )
        }
        val declaration = "optional:file:${request.environmentFile}[.properties]"
        val current = declarations.singleOrNull()
        val edit = if (current == null) {
            val prefix = when {
                content.isEmpty() || content.endsWith('\n') || content.endsWith('\r') -> ""
                else -> document.lineSeparator
            }
            WorkspaceTextEdit(
                startOffset = content.length,
                endOffset = content.length,
                expectedText = "",
                replacement = "$prefix$SPRING_CONFIG_IMPORT=$declaration${document.lineSeparator}",
            )
        } else {
            val existingTokens = splitImports(current.decodedValue)
            if (existingTokens.any { token ->
                    resolveEnvironmentImport(
                        JmixApplicationProfileSnapshot(
                            modulePath = candidate.modulePath,
                            profile = candidate.profile,
                            locator = candidate.profileLocator,
                            serverPort = null,
                            contextPath = null,
                            activeProfiles = emptyList(),
                            availableLocales = emptyList(),
                            stores = emptyList(),
                            properties = emptyList(),
                        ),
                        token,
                    ) != null
                }
            ) {
                return EnvironmentChangeProposal.rejected(
                    "JVW-ENV-CONNECTION-ALREADY-CONNECTED",
                    "The selected profile already imports an environment file.",
                    request.profileLocator.relativePath,
                )
            }
            WorkspaceTextEdit(
                startOffset = current.valueStartOffset,
                endOffset = current.valueEndOffset,
                expectedText = content.substring(current.valueStartOffset, current.valueEndOffset),
                replacement = listOf(current.decodedValue.trim(), declaration)
                    .filter(String::isNotBlank)
                    .joinToString(","),
            )
        }
        return EnvironmentChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "environment-connect:${
                    keyedDigest(
                        "connection-change-set\u0000${request.profileLocator.relativePath}\u0000" +
                            "$fingerprint\u0000${request.environmentFile}",
                    ).take(24)
                }",
                label = "Connect ${request.environmentFile} to ${candidate.profile} profile",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.profileLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = fingerprint,
                        edits = listOf(edit),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun activationSnapshots(
        workspace: JmixProjectPropertiesWorkspace,
        imports: List<ResolvedEnvironmentImport>,
        files: List<JmixEnvironmentFileSnapshot>,
        issues: MutableList<JmixEnvironmentIssue>,
    ): List<JmixProfileActivationSnapshot> {
        return workspace.profiles
            .groupBy(JmixApplicationProfileSnapshot::modulePath)
            .toSortedMap()
            .map { (modulePath, profiles) ->
                val defaultProfile = profiles.firstOrNull { profile -> profile.profile == "default" }
                val rawActive = defaultProfile?.properties
                    ?.firstOrNull { property -> property.key == SPRING_PROFILES_ACTIVE }
                    ?.displayValue
                val relevantImports = imports.filter { imported ->
                    imported.profile.modulePath == modulePath &&
                        (imported.profile.profile == "default" || imported.profile == defaultProfile)
                }
                val importedValues = linkedMapOf<String, String>()
                relevantImports.forEach { imported ->
                    files.firstOrNull { file -> file.relativePath == imported.relativePath }
                        ?.variables
                        ?.filterNot(JmixEnvironmentVariableSnapshot::secret)
                        ?.forEach { variable -> importedValues[variable.name] = variable.displayValue }
                }
                val resolved = resolveProfileExpression(rawActive, importedValues)
                val declared = resolved.profiles
                val available = profiles.map(JmixApplicationProfileSnapshot::profile).toSet()
                val groupNames = profiles
                    .flatMap(JmixApplicationProfileSnapshot::properties)
                    .map(JmixApplicationPropertySnapshot::key)
                    .filter { key -> key.startsWith(SPRING_PROFILE_GROUP_PREFIX) }
                    .map { key -> key.removePrefix(SPRING_PROFILE_GROUP_PREFIX) }
                    .toSet()
                val expanded = expandProfileGroups(declared, profiles, issues, modulePath)
                val missing = expanded.filterNot { profile ->
                    profile == "default" || profile in available || profile in groupNames
                }
                JmixProfileActivationSnapshot(
                    modulePath = modulePath,
                    declarationLocator = defaultProfile?.locator,
                    rawExpression = rawActive,
                    source = resolved.source,
                    declaredProfiles = declared,
                    expandedProfiles = expanded,
                    missingProfiles = missing,
                    runtimeProven = false,
                    explanation = when (resolved.source) {
                        JmixProfileActivationSource.STATIC ->
                            "Resolved statically from application.properties; runtime use is not yet proven."
                        JmixProfileActivationSource.IMPORTED_ENV ->
                            "Resolved from an explicitly imported environment file; launch/runtime use is not yet proven."
                        JmixProfileActivationSource.PLACEHOLDER_DEFAULT ->
                            "Resolved from the placeholder default; an operating-system or launch override may win."
                        JmixProfileActivationSource.UNRESOLVED ->
                            "The active-profile expression depends on a value not available from reviewed project configuration."
                        JmixProfileActivationSource.NOT_CONFIGURED ->
                            "No project-level active profile is configured."
                    },
                )
            }
    }

    private fun expandProfileGroups(
        initial: List<String>,
        profiles: List<JmixApplicationProfileSnapshot>,
        issues: MutableList<JmixEnvironmentIssue>,
        modulePath: String,
    ): List<String> {
        val groups = linkedMapOf<String, List<String>>()
        val includes = mutableListOf<String>()
        profiles.forEach { profile ->
            profile.properties.forEach { property ->
                when {
                    property.key.startsWith(SPRING_PROFILE_GROUP_PREFIX) ->
                        groups[property.key.removePrefix(SPRING_PROFILE_GROUP_PREFIX)] =
                            splitProfiles(property.displayValue)
                    property.key == SPRING_PROFILES_INCLUDE ->
                        includes += splitProfiles(property.displayValue)
                }
            }
        }
        val result = linkedSetOf<String>()
        val visiting = linkedSetOf<String>()
        fun add(profile: String) {
            if (!visiting.add(profile)) {
                issues += JmixEnvironmentIssue(
                    code = "JVW-PROFILE-GROUP-CYCLE",
                    message = "Profile group '$profile' forms an activation cycle.",
                    relativePath = modulePath.takeIf(String::isNotBlank),
                )
                return
            }
            result += profile
            groups[profile].orEmpty().forEach(::add)
            visiting.remove(profile)
        }
        (initial + includes).forEach(::add)
        return result.toList()
    }

    private fun resolveProfileExpression(
        raw: String?,
        importedValues: Map<String, String>,
    ): ResolvedProfileExpression {
        if (raw.isNullOrBlank()) {
            return ResolvedProfileExpression(JmixProfileActivationSource.NOT_CONFIGURED, emptyList())
        }
        val placeholder = PROFILE_PLACEHOLDER.matchEntire(raw.trim())
            ?: return ResolvedProfileExpression(
                JmixProfileActivationSource.STATIC,
                splitProfiles(raw),
            )
        val name = placeholder.groupValues[1]
        val imported = importedValues[name]
        if (imported != null) {
            return ResolvedProfileExpression(
                JmixProfileActivationSource.IMPORTED_ENV,
                splitProfiles(imported),
            )
        }
        val fallback = placeholder.groupValues[2].takeIf(String::isNotBlank)
        if (fallback != null) {
            return ResolvedProfileExpression(
                JmixProfileActivationSource.PLACEHOLDER_DEFAULT,
                splitProfiles(fallback),
            )
        }
        return ResolvedProfileExpression(JmixProfileActivationSource.UNRESOLVED, emptyList())
    }

    private fun launchConfigurationSnapshots(
        resolver: ProjectFileResolver,
        issues: MutableList<JmixEnvironmentIssue>,
    ): List<JmixLaunchProfileSnapshot> {
        val paths = mutableListOf<String>()
        resolver.registeredRoots().forEach { root ->
            fun rooted(relativePath: String): String =
                listOf(root.prefix, relativePath).filter(String::isNotBlank).joinToString("/")
            resolver.resolveFile(rooted(".run"))?.file?.takeIf { it.isDirectory }?.children
                ?.filter { file -> !file.isDirectory && file.name.endsWith(".run.xml") }
                ?.forEach { file -> resolver.locatorPath(file)?.let(paths::add) }
            resolver.resolveFile(rooted(".idea/runConfigurations"))?.file
                ?.takeIf { it.isDirectory }
                ?.children
                ?.filter { file -> !file.isDirectory && file.extension == "xml" }
                ?.forEach { file -> resolver.locatorPath(file)?.let(paths::add) }
            resolver.resolveFile(rooted(".idea/workspace.xml"))?.file?.let { file ->
                resolver.locatorPath(file)?.let(paths::add)
            }
        }
        return paths.distinct().sorted().mapNotNull { relativePath ->
            val file = resolver.resolveFile(relativePath)?.file ?: return@mapNotNull null
            val content = runCatching { ProjectSourceText.read(file) }.getOrNull() ?: return@mapNotNull null
            if (content.toByteArray().size > MAX_LAUNCH_CONFIGURATION_BYTES) {
                issues += JmixEnvironmentIssue(
                    "JVW-LAUNCH-CONFIG-TOO-LARGE",
                    "The launch configuration exceeds the reviewed inspection limit.",
                    relativePath,
                )
                return@mapNotNull null
            }
            val activeProfiles = linkedSetOf<String>()
            ENV_PROFILE_XML.findAll(content).forEach { match ->
                activeProfiles += splitProfiles(xmlDecode(match.groupValues[1]))
            }
            SYSTEM_PROFILE_ARGUMENT.findAll(content).forEach { match ->
                activeProfiles += splitProfiles(xmlDecode(match.groupValues[1]))
            }
            val envFiles = ENV_FILE_XML.findAll(content)
                .flatMap { match -> match.groupValues[1].split(';').asSequence() }
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { value -> value.replace("\$PROJECT_DIR\$/", "") }
                .filter { value -> value == ".env" || value == ".env.properties" }
                .distinct()
                .toList()
            if (activeProfiles.isEmpty() && envFiles.isEmpty()) return@mapNotNull null
            JmixLaunchProfileSnapshot(
                relativePath = relativePath,
                revisionFingerprint = opaqueRevision(
                    relativePath,
                    CanonicalDiscoveryJson.sha256(content),
                ),
                activeProfiles = activeProfiles.toList(),
                environmentFiles = envFiles,
                runtimeProven = false,
                explanation =
                    "This is launch-configuration evidence. The process must still be started and inspected " +
                        "before these profiles can be called runtime active.",
            )
        }
    }

    private fun environmentReferences(
        workspace: JmixProjectPropertiesWorkspace,
    ): Map<String, List<JmixEnvironmentReferenceSnapshot>> {
        val references = linkedMapOf<String, MutableList<JmixEnvironmentReferenceSnapshot>>()
        workspace.profiles.forEach { profile ->
            profile.properties.forEach { property ->
                PROFILE_PLACEHOLDER.findAll(property.displayValue).forEach { match ->
                    references.getOrPut(match.groupValues[1]) { mutableListOf() } +=
                        JmixEnvironmentReferenceSnapshot(
                            profileLocator = profile.locator,
                            propertyKey = property.key,
                            secretProperty = property.secret,
                        )
                }
            }
        }
        return references.mapValues { (_, values) -> values.distinct() }
    }

    private fun resolveEnvironmentImport(
        profile: JmixApplicationProfileSnapshot,
        declaration: String,
    ): ResolvedEnvironmentImport? {
        var value = declaration.trim()
        val optional = value.startsWith("optional:")
        if (optional) value = value.removePrefix("optional:")
        if (!value.startsWith("file:")) return null
        value = value.removePrefix("file:")
        if (value.endsWith("[.properties]")) value = value.removeSuffix("[.properties]")
        value = value.removePrefix("./")
        if (
            value.isBlank() ||
            '\\' in value ||
            value.startsWith('/') ||
            ':' in value ||
            PROFILE_PLACEHOLDER.containsMatchIn(value)
        ) {
            return null
        }
        val segments = value.split('/')
        if (segments.any { segment -> segment.isBlank() || segment == "." || segment == ".." }) return null
        if (segments.last() !in SUPPORTED_ENVIRONMENT_FILES) return null
        val root = profile.modulePath.trim('/')
        val relativePath = listOf(root, value).filter(String::isNotBlank).joinToString("/")
        if (ProjectFileResolver.getInstance(project).resolveTarget(relativePath) == null) return null
        return ResolvedEnvironmentImport(profile, declaration, relativePath, optional)
    }

    private fun issueSecretCapability(
        request: JmixEnvironmentSecretChangeRequest,
        changeSet: WorkspaceChangeSet,
        planDigest: String,
    ): IssuedSecretCapability = synchronized(secretCapabilities) {
        purgeExpiredCapabilities()
        while (secretCapabilities.size >= MAX_SECRET_CAPABILITIES) {
            secretCapabilities.remove(secretCapabilities.entries.first().key)
        }
        val capability = UUID.randomUUID().toString()
        val clientPlanDigest = UUID.randomUUID().toString()
        val expiresAt = clock.instant().plus(SECRET_CAPABILITY_LIFETIME)
        secretCapabilities[capability] = PendingSecretEnvironmentChange(
            workspaceDigest = request.workspaceDigest,
            relativePath = request.relativePath,
            realPlanDigest = planDigest,
            clientPlanDigest = clientPlanDigest,
            changeSet = changeSet,
            expiresAt = expiresAt,
        )
        com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                synchronized(secretCapabilities) {
                    secretCapabilities[capability]
                        ?.takeIf { pending -> !pending.expiresAt.isAfter(clock.instant()) }
                        ?.let { secretCapabilities.remove(capability) }
                }
            },
            SECRET_CAPABILITY_LIFETIME.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        IssuedSecretCapability(capability, clientPlanDigest)
    }

    private fun purgeExpiredCapabilities() {
        val now = clock.instant()
        secretCapabilities.entries.removeIf { entry -> !entry.value.expiresAt.isAfter(now) }
    }

    private fun browserSafePreview(
        preview: WorkspaceChangePreviewResponse,
        variableName: String,
        secret: Boolean,
        externalPlanDigest: String?,
    ): WorkspaceChangePreviewResponse =
        preview.copy(
            planDigest = externalPlanDigest,
            files = preview.files.map { file ->
                file.copy(
                    beforeFingerprint = null,
                    afterFingerprint = SECRET_REDACTION,
                    originalContent = null,
                    resultContent =
                        "$variableName=${if (secret) SECRET_REDACTION else "value selected in editor"}",
                )
            },
        )

    private fun opaqueRevision(relativePath: String, realFingerprint: String): String =
        keyedDigest("revision\u0000$relativePath\u0000$realFingerprint")

    private fun browserLocator(locator: SourceLocator): SourceLocator =
        locator.copy(
            revisionFingerprint = opaqueRevision(
                locator.relativePath,
                locator.revisionFingerprint,
            ),
        )

    private fun opaquePlanDigest(changeSetId: String, realPlanDigest: String): String =
        keyedDigest("plan\u0000$changeSetId\u0000$realPlanDigest")

    private fun keyedDigest(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(browserBoundaryKey, "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        internal fun parseEnvironmentDocument(content: String): EnvironmentDocument {
            val entries = mutableListOf<EnvironmentEntry>()
            val issues = mutableListOf<EnvironmentParseIssue>()
            var lineSeparator: String? = null
            var offset = 0
            while (offset < content.length) {
                val line = physicalLine(content, offset)
                if (lineSeparator == null && line.separator.isNotEmpty()) lineSeparator = line.separator
                val raw = content.substring(offset, line.contentEndOffset)
                val trimmed = raw.trimStart()
                if (trimmed.isNotBlank() && !trimmed.startsWith('#')) {
                    var declaration = trimmed
                    val leading = raw.length - trimmed.length
                    var relativeOffset = leading
                    if (declaration.startsWith("export ")) {
                        relativeOffset += "export ".length
                        declaration = declaration.removePrefix("export ")
                    }
                    val separator = declaration.indexOf('=')
                    if (separator <= 0) {
                        issues += EnvironmentParseIssue(
                            "JVW-ENV-SYNTAX-UNSUPPORTED",
                            "Only NAME=value declarations, comments and blank lines can be changed visually.",
                        )
                    } else {
                        val name = declaration.substring(0, separator).trim()
                        val rawValue = declaration.substring(separator + 1)
                        if (!ENVIRONMENT_NAME.matches(name) || name != declaration.substring(0, separator)) {
                            issues += EnvironmentParseIssue(
                                "JVW-ENV-NAME-UNSUPPORTED",
                                "Environment names must use uppercase letters, numbers and underscores without spacing.",
                            )
                        } else {
                            val valueStart = offset + relativeOffset + separator + 1
                            val parsed = parseEnvironmentValue(rawValue)
                            if (parsed == null) {
                                issues += EnvironmentParseIssue(
                                    "JVW-ENV-VALUE-SYNTAX-UNSUPPORTED",
                                    "$name uses an unterminated or ambiguous quoted value.",
                                )
                            } else {
                                entries += EnvironmentEntry(
                                    name = name,
                                    value = parsed.first,
                                    quote = parsed.second,
                                    lineStartOffset = offset,
                                    lineEndOffset = line.nextOffset,
                                    valueStartOffset = valueStart,
                                    valueEndOffset = line.contentEndOffset,
                                )
                            }
                        }
                    }
                }
                offset = line.nextOffset
            }
            entries.groupingBy(EnvironmentEntry::name)
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
                .forEach { name ->
                    issues += EnvironmentParseIssue(
                        "JVW-ENV-DUPLICATE",
                        "$name is declared more than once.",
                    )
                }
            return EnvironmentDocument(entries, lineSeparator ?: "\n", issues.distinct())
        }

        private fun parseEnvironmentValue(raw: String): Pair<String, Char?>? {
            if (raw.isEmpty()) return "" to null
            val quote = raw.first().takeIf { character -> character == '\'' || character == '"' }
                ?: return raw to null
            var escaped = false
            var closing = -1
            for (index in 1 until raw.length) {
                val character = raw[index]
                when {
                    quote == '"' && escaped -> escaped = false
                    quote == '"' && character == '\\' -> escaped = true
                    character == quote -> {
                        closing = index
                        break
                    }
                }
            }
            if (closing < 0) return null
            val tail = raw.substring(closing + 1).trim()
            if (tail.isNotEmpty() && !tail.startsWith('#')) return null
            val body = raw.substring(1, closing)
            return if (quote == '\'') {
                body to quote
            } else {
                unescapeDoubleQuoted(body) to quote
            }
        }

        private fun unescapeDoubleQuoted(value: String): String = buildString {
            var escaped = false
            value.forEach { character ->
                if (escaped) {
                    append(
                        when (character) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> character
                        },
                    )
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else {
                    append(character)
                }
            }
            if (escaped) append('\\')
        }

        private fun physicalLine(content: String, start: Int): EnvironmentPhysicalLine {
            var cursor = start
            while (cursor < content.length && content[cursor] != '\r' && content[cursor] != '\n') cursor++
            val separator = when {
                cursor >= content.length -> ""
                content[cursor] == '\r' && cursor + 1 < content.length && content[cursor + 1] == '\n' ->
                    "\r\n"
                content[cursor] == '\r' -> "\r"
                else -> "\n"
            }
            return EnvironmentPhysicalLine(cursor, cursor + separator.length, separator)
        }

        private fun splitImports(raw: String): List<String> =
            raw.split(',').map(String::trim).filter(String::isNotBlank).take(MAX_IMPORTS)

        private fun splitProfiles(raw: String): List<String> =
            raw.split(',').map(String::trim).filter(PROFILE_NAME::matches).distinct().take(MAX_PROFILES)

        private fun validateEnvironmentValue(value: String): String? =
            when {
                value.length > MAX_ENVIRONMENT_VALUE_LENGTH ->
                    "Environment values cannot exceed $MAX_ENVIRONMENT_VALUE_LENGTH characters."
                value.any { character ->
                    character == '\u0000' || character == '\r' || character == '\n'
                } ->
                    "Multiline and NUL-containing environment values cannot be changed visually."
                else -> null
            }

        private fun encodeEnvironmentValue(value: String, preferredQuote: Char?): String =
            when {
                preferredQuote == '\'' && '\'' !in value -> "'$value'"
                preferredQuote == '"' -> "\"${escapeDoubleQuoted(value)}\""
                UNQUOTED_ENVIRONMENT_VALUE.matches(value) -> value
                '\'' !in value -> "'$value'"
                else -> "\"${escapeDoubleQuoted(value)}\""
            }

        private fun escapeDoubleQuoted(value: String): String =
            value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")

        private fun isSecretName(name: String): Boolean =
            SECRET_NAME_PATTERNS.any { pattern -> pattern.containsMatchIn(name.lowercase()) }

        private fun rejectedPreview(
            code: String,
            message: String,
            relativePath: String?,
        ): WorkspaceChangePreviewResponse =
            WorkspaceChangePreviewResponse(
                accepted = false,
                changeSetId = "environment:rejected",
                label = "Environment change rejected",
                planDigest = null,
                files = emptyList(),
                issues = listOf(WorkspaceChangeIssue(code, message, relativePath)),
            )

        private fun rejectedPrepared(
            code: String,
            message: String,
            relativePath: String?,
        ): PreparedWorkspaceChange =
            PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "environment:rejected",
                    label = "Environment change rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = listOf(WorkspaceChangeIssue(code, message, relativePath)),
                ),
                baseDir = null,
            )

        private fun rejectedSecretPreview(
            code: String,
            message: String,
            relativePath: String?,
        ): JmixEnvironmentSecretPreviewResponse =
            JmixEnvironmentSecretPreviewResponse(
                accepted = false,
                capability = null,
                preview = rejectedPreview(code, message, relativePath),
            )

        private fun xmlDecode(value: String): String =
            value.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")

        fun getInstance(project: Project): JmixEnvironmentConfigurationService =
            project.getService(JmixEnvironmentConfigurationService::class.java)

        private const val SPRING_CONFIG_IMPORT = "spring.config.import"
        private const val SPRING_PROFILES_ACTIVE = "spring.profiles.active"
        private const val SPRING_PROFILES_INCLUDE = "spring.profiles.include"
        private const val SPRING_PROFILE_GROUP_PREFIX = "spring.profiles.group."
        private const val SECRET_REDACTION = "••••••••"
        private const val MAX_IMPORTS = 32
        private const val MAX_PROFILES = 100
        private const val MAX_ENVIRONMENT_VALUE_LENGTH = 4_096
        private const val MAX_LAUNCH_CONFIGURATION_BYTES = 2 * 1024 * 1024
        private const val MAX_SECRET_CAPABILITIES = 10
        private val SECRET_CAPABILITY_LIFETIME = Duration.ofMinutes(5)
        private val ENVIRONMENT_NAME = Regex("""[A-Z_][A-Z0-9_]{0,199}""")
        private val PROFILE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9_-]{0,99}""")
        private val SENSITIVE_CONFIGURATION_FILE = Regex(
            """(?:\.env(?:\.properties)?|application(?:-[A-Za-z0-9_.-]+)?\.(?:properties|ya?ml))""",
        )
        private val PROFILE_PLACEHOLDER =
            Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}""")
        private val UNQUOTED_ENVIRONMENT_VALUE = Regex("""[A-Za-z0-9_./:@+-]*""")
        private val SUPPORTED_ENVIRONMENT_FILES = setOf(".env", ".env.properties")
        private val SECRET_NAME_SEGMENTS = setOf(
            "password",
            "passwd",
            "pwd",
            "passphrase",
            "pin",
            "secret",
            "client_secret",
            "token",
            "auth",
            "bearer",
            "session",
            "cookie",
            "key",
            "api_key",
            "apikey",
            "access_key",
            "private_key",
            "signing_key",
            "encryption_key",
            "credential",
            "credentials",
        )
        private val SECRET_NAME_PATTERNS = SECRET_NAME_SEGMENTS.map { segment ->
            Regex("""(?:^|[_-])${Regex.escape(segment)}(?:[_-]|$)""")
        }
        private val ENV_PROFILE_XML = Regex(
            """<env\s+[^>]*name="SPRING_PROFILES_ACTIVE"[^>]*value="([^"]*)"[^>]*/?>""",
            RegexOption.IGNORE_CASE,
        )
        private val SYSTEM_PROFILE_ARGUMENT = Regex(
            """(?:-D|--)?spring\.profiles\.active(?:=|%3D)([A-Za-z0-9_,.-]+)""",
            RegexOption.IGNORE_CASE,
        )
        private val ENV_FILE_XML = Regex(
            """(?:name="(?:envFilePaths|envFilePath)"\s+value|key="envFilePaths"\s+value)="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
    }
}

private class SecretEnvironmentValueDialog(
    private val variableName: String,
) : DialogWrapper(false) {
    private val field = JBPasswordField()

    init {
        title = "Set secure environment value"
        setOKButtonText("Review secure change")
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            add(
                FormBuilder.createFormBuilder()
                    .addComponent(
                        JBLabel(
                            "<html>Set <b>$variableName</b>. The value stays in the IntelliJ process " +
                                "and is never sent to the embedded browser.</html>",
                        ),
                    )
                    .addLabeledComponent("Secret value:", field)
                    .panel,
                BorderLayout.CENTER,
            )
        }

    override fun getPreferredFocusedComponent(): JComponent = field

    fun secret(): CharArray = field.password

    fun clear() {
        field.text = ""
    }
}

data class JmixEnvironmentWorkspace(
    val files: List<JmixEnvironmentFileSnapshot>,
    val connectionCandidates: List<JmixEnvironmentConnectionCandidate>,
    val activations: List<JmixProfileActivationSnapshot>,
    val launchConfigurations: List<JmixLaunchProfileSnapshot>,
    val issues: List<JmixEnvironmentIssue>,
    val snapshotDigest: String,
)

data class JmixEnvironmentConnectionCandidate(
    val profileLocator: SourceLocator,
    val profile: String,
    val modulePath: String,
    val proposedRelativePath: String,
)

data class JmixEnvironmentFileSnapshot(
    val relativePath: String,
    val locator: SourceLocator?,
    val existing: Boolean,
    val mutable: Boolean,
    val importedBy: List<JmixEnvironmentImportSnapshot>,
    val variables: List<JmixEnvironmentVariableSnapshot>,
)

data class JmixEnvironmentImportSnapshot(
    val profileLocator: SourceLocator,
    val profile: String,
    val modulePath: String,
    val optional: Boolean,
    val declaration: String,
)

data class JmixEnvironmentVariableSnapshot(
    val name: String,
    val displayValue: String,
    val secret: Boolean,
    val mutable: Boolean,
    val references: List<JmixEnvironmentReferenceSnapshot>,
)

data class JmixEnvironmentReferenceSnapshot(
    val profileLocator: SourceLocator,
    val propertyKey: String,
    val secretProperty: Boolean,
)

enum class JmixProfileActivationSource {
    STATIC,
    IMPORTED_ENV,
    PLACEHOLDER_DEFAULT,
    UNRESOLVED,
    NOT_CONFIGURED,
}

data class JmixProfileActivationSnapshot(
    val modulePath: String,
    val declarationLocator: SourceLocator?,
    val rawExpression: String?,
    val source: JmixProfileActivationSource,
    val declaredProfiles: List<String>,
    val expandedProfiles: List<String>,
    val missingProfiles: List<String>,
    val runtimeProven: Boolean,
    val explanation: String,
)

data class JmixLaunchProfileSnapshot(
    val relativePath: String,
    val revisionFingerprint: String,
    val activeProfiles: List<String>,
    val environmentFiles: List<String>,
    val runtimeProven: Boolean,
    val explanation: String,
)

data class JmixEnvironmentIssue(
    val code: String,
    val message: String,
    val relativePath: String? = null,
)

enum class JmixEnvironmentChangeMode {
    SET,
    REMOVE,
}

data class JmixEnvironmentChangeRequest(
    val workspaceDigest: String,
    val relativePath: String,
    val locator: SourceLocator?,
    val variableName: String,
    val mode: JmixEnvironmentChangeMode,
    val value: String? = null,
)

data class JmixEnvironmentChangeApplyRequest(
    val change: JmixEnvironmentChangeRequest,
    val expectedPlanDigest: String,
)

data class JmixEnvironmentConnectionRequest(
    val workspaceDigest: String,
    val profileLocator: SourceLocator,
    val environmentFile: String = ".env",
)

data class JmixEnvironmentConnectionApplyRequest(
    val change: JmixEnvironmentConnectionRequest,
    val expectedPlanDigest: String,
)

data class JmixEnvironmentSecretChangeRequest(
    val workspaceDigest: String,
    val relativePath: String,
    val locator: SourceLocator?,
    val variableName: String,
)

data class JmixEnvironmentSecretPreviewResponse(
    val accepted: Boolean,
    val capability: String?,
    val preview: WorkspaceChangePreviewResponse,
)

data class JmixEnvironmentSecretApplyRequest(
    val capability: String,
    val expectedPlanDigest: String,
)

data class JmixEnvironmentNavigationRequest(
    val relativePath: String,
    val line: Int? = null,
    val column: Int? = null,
    val revisionFingerprint: String,
)

internal data class EnvironmentDocument(
    val entries: List<EnvironmentEntry>,
    val lineSeparator: String,
    val issues: List<EnvironmentParseIssue>,
)

internal data class EnvironmentEntry(
    val name: String,
    val value: String,
    val quote: Char?,
    val lineStartOffset: Int,
    val lineEndOffset: Int,
    val valueStartOffset: Int,
    val valueEndOffset: Int,
)

internal data class EnvironmentParseIssue(
    val code: String,
    val message: String,
)

private data class EnvironmentPhysicalLine(
    val contentEndOffset: Int,
    val nextOffset: Int,
    val separator: String,
)

private data class ResolvedEnvironmentImport(
    val profile: JmixApplicationProfileSnapshot,
    val declaration: String,
    val relativePath: String,
    val optional: Boolean,
)

private data class ResolvedProfileExpression(
    val source: JmixProfileActivationSource,
    val profiles: List<String>,
)

private data class PendingSecretEnvironmentChange(
    val workspaceDigest: String,
    val relativePath: String,
    val realPlanDigest: String,
    val clientPlanDigest: String,
    val changeSet: WorkspaceChangeSet,
    val expiresAt: Instant,
)

private data class IssuedSecretCapability(
    val capability: String,
    val clientPlanDigest: String,
)

private data class EnvironmentChangeProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
) {
    fun rejectedPreview(): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "environment:rejected",
            label = "Environment change rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    fun rejectedPrepared(): PreparedWorkspaceChange =
        PreparedWorkspaceChange(
            plan = WorkspaceChangePlan(
                accepted = false,
                changeSetId = "environment:rejected",
                label = "Environment change rejected",
                planDigest = null,
                files = emptyList(),
                issues = issues,
            ),
            baseDir = null,
        )

    companion object {
        fun rejected(
            code: String,
            message: String,
            relativePath: String?,
        ): EnvironmentChangeProposal =
            EnvironmentChangeProposal(
                changeSet = null,
                issues = listOf(WorkspaceChangeIssue(code, message, relativePath)),
            )
    }
}

package org.jmixworkbench.services

import com.google.gson.Gson
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.VisualRuleGenerator
import org.jmixworkbench.model.RuleDataType
import org.jmixworkbench.model.RuleDiagnosticSeverity
import org.jmixworkbench.model.RuleExpressionKind
import org.jmixworkbench.model.RuleExpressionModel
import org.jmixworkbench.model.VisualRuleModel
import java.util.Base64

/**
 * Existing-project lifecycle for pure reusable visual rules.
 *
 * Only an exact deterministic source carrying a valid model marker is editable.
 * Manual Java changes immediately convert the document to read-only, preserving
 * handwritten enterprise behavior.
 */
@Service(Service.Level.PROJECT)
class VisualRuleWorkspaceService(private val project: Project) {
    private val gson = Gson()

    fun load(forceRefresh: Boolean = false): VisualRuleWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        val logicWorkspace = VisualLogicWorkspaceService.getInstance(project).load(false)
        val destinations = logicWorkspace.destinations
        return VisualRuleWorkspaceResponse(
            graphDigest = graph.snapshotDigest,
            destinations = destinations,
            defaultDestinationId = destinations.firstOrNull { it.recommended }?.id,
            contextArtifacts = graph.artifacts.filter { it.kind in CONTEXT_KINDS },
            existingDocuments = discoverExisting(destinations),
            issues = buildList {
                if (!graph.indexHealth.complete) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-RULE-GRAPH-PARTIAL",
                            "The application index is partial. Resolve index diagnostics before trusting complete rule impact coverage.",
                        ),
                    )
                }
                if (destinations.isEmpty()) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-RULE-DESTINATION-MISSING",
                            "No production Java destination is available. Import Gradle modules and refresh.",
                        ),
                    )
                }
            },
        )
    }

    fun preview(model: VisualRuleModel): WorkspaceChangePreviewResponse {
        val proposal = propose(model)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues)
    }

    fun prepare(request: VisualRuleApplyRequest): PreparedWorkspaceChange {
        val proposal = propose(request.model)
        val changeSet = proposal.changeSet
            ?: return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "visual-rule:rejected",
                    label = "Visual rule generation rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues,
                ),
                baseDir = null,
            )
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    internal fun propose(model: VisualRuleModel): VisualRuleProposal {
        val workspace = load(false)
        val destination = workspace.destinations.firstOrNull { it.id == model.destinationId }
            ?: return rejected(
                "JVW-RULE-DESTINATION-INVALID",
                "The selected production destination is no longer registered. Refresh the workspace.",
            )
        val issues = validateAgainstIndex(model, workspace.contextArtifacts)
        if (issues.isNotEmpty()) return VisualRuleProposal(null, issues)

        val storedModel = model.copy(sourceLocator = null)
        val content = VisualRuleGenerator.generate(storedModel, encode(storedModel))
        javaSyntaxError("${model.className}.java", content)?.let { syntax ->
            return rejected(
                "JVW-RULE-GENERATED-SYNTAX",
                "Generated Java is not syntactically valid: ${syntax.errorDescription}",
            )
        }
        val targetPath = buildString {
            append(destination.sourceRoot.trimEnd('/', '\\'))
            append('/')
            append(model.packageName.replace('.', '/'))
            append('/')
            append(model.className)
            append(".java")
        }
        val change = if (model.sourceLocator == null) {
            WorkspaceFileChange(
                relativePath = targetPath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = content,
            )
        } else {
            val existing = loadOwned(model.sourceLocator)
                ?: return rejected(
                    "JVW-RULE-SOURCE-NOT-OWNED",
                    "The existing rule is not an unchanged workbench-owned source. Handwritten Java is never overwritten.",
                    model.sourceLocator.relativePath,
                )
            if (existing.locator.relativePath != targetPath) {
                return rejected(
                    "JVW-RULE-MOVE-UNSUPPORTED",
                    "Changing module, package, or class would move this rule. Create a new rule instead.",
                    existing.locator.relativePath,
                )
            }
            if (model.sourceLocator.revisionFingerprint != existing.locator.revisionFingerprint) {
                return rejected(
                    "JVW-RULE-SOURCE-STALE",
                    "The source changed after it was loaded. Refresh before editing.",
                    existing.locator.relativePath,
                )
            }
            WorkspaceFileChange(
                relativePath = targetPath,
                mode = WorkspaceFileChangeMode.MODIFY,
                baseRevisionFingerprint = existing.locator.revisionFingerprint,
                edits = listOf(
                    WorkspaceTextEdit(
                        startOffset = 0,
                        endOffset = existing.content.length,
                        expectedText = existing.content,
                        replacement = content,
                    ),
                ),
            )
        }
        val identity = listOf(targetPath, content, model.sourceLocator?.revisionFingerprint.orEmpty())
            .joinToString("\u0000")
        return VisualRuleProposal(
            changeSet = WorkspaceChangeSet(
                id = "visual-rule:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "${if (model.sourceLocator == null) "Create" else "Update"} visual rule ${model.name}",
                files = listOf(change),
            ),
            issues = emptyList(),
        )
    }

    private fun validateAgainstIndex(
        model: VisualRuleModel,
        artifacts: List<ArtifactSnapshot>,
    ): List<WorkspaceChangeIssue> {
        val issues = VisualRuleGenerator.validate(model).diagnostics
            .filter { it.severity == RuleDiagnosticSeverity.ERROR }
            .map { diagnostic ->
                WorkspaceChangeIssue(
                    diagnostic.code,
                    buildString {
                        diagnostic.expressionId?.let { append('[').append(it).append("] ") }
                        append(diagnostic.message)
                    },
                )
            }
            .toMutableList()
        val entities = artifacts
            .filter { it.kind == ArtifactKind.ENTITY || it.kind == ArtifactKind.DTO }
            .flatMap { listOf(it.semanticKey, it.displayName) }
            .toSet()
        model.parameters
            .filter { it.dataType == RuleDataType.ENTITY && it.javaType !in entities }
            .forEach { parameter ->
                issues += WorkspaceChangeIssue(
                    "JVW-RULE-ENTITY-NOT-INDEXED",
                    "Entity parameter '${parameter.name}' is not present in the current application index: ${parameter.javaType}.",
                )
            }
        val attributeKeys = artifacts
            .filter { it.kind == ArtifactKind.ENTITY_ATTRIBUTE }
            .map(ArtifactSnapshot::semanticKey)
            .toSet()
        fun inspect(expression: RuleExpressionModel) {
            if (expression.kind == RuleExpressionKind.PROPERTY) {
                val parameter = model.parameters.firstOrNull { it.name == expression.parameterName }
                if (parameter?.dataType == RuleDataType.ENTITY) {
                    val firstSegment = expression.propertyPath.orEmpty().substringBefore('.')
                    if ("${parameter.javaType}.$firstSegment" !in attributeKeys) {
                        issues += WorkspaceChangeIssue(
                            "JVW-RULE-PROPERTY-NOT-INDEXED",
                            "[${expression.id}] Property '${expression.propertyPath}' is not indexed on ${parameter.javaType}.",
                        )
                    }
                }
            }
            expression.children.forEach(::inspect)
        }
        inspect(model.expression)
        return issues.distinct().sortedBy(WorkspaceChangeIssue::code)
    }

    private fun discoverExisting(
        destinations: List<VisualLogicDestinationSnapshot>,
    ): List<VisualRuleDocumentSnapshot> {
        val resolver = ProjectFileResolver.getInstance(project)
        val documents = mutableListOf<VisualRuleDocumentSnapshot>()
        var visited = 0
        destinations.forEach { destination ->
            if (visited >= MAX_RULE_FILES) return@forEach
            val target = resolver.resolveTarget(destination.sourceRoot) ?: return@forEach
            val root = if (target.relativePath.isBlank()) {
                target.root
            } else {
                target.root.findFileByRelativePath(target.relativePath)
            } ?: return@forEach
            visitJavaFiles(root) { file ->
                if (visited++ >= MAX_RULE_FILES || file.length > MAX_RULE_BYTES) return@visitJavaFiles
                val relativePath = resolver.locatorPath(file) ?: return@visitJavaFiles
                val content = runCatching {
                    String(file.contentsToByteArray(false), file.charset)
                }.getOrNull() ?: return@visitJavaFiles
                val decoded = decode(content) ?: return@visitJavaFiles
                val locator = SourceLocator(
                    relativePath = relativePath,
                    symbol = decoded.className,
                    revisionFingerprint = CanonicalDiscoveryJson.sha256(content),
                )
                val owned = VisualRuleGenerator.generate(decoded, encode(decoded)) == content
                documents += VisualRuleDocumentSnapshot(
                    locator = locator,
                    model = decoded.copy(sourceLocator = locator),
                    editable = owned,
                    issue = if (owned) null else "Manual Java changes were detected; visual overwrite is disabled.",
                )
            }
        }
        return documents.distinctBy { it.locator.relativePath }.sortedBy { it.locator.relativePath }
    }

    private fun loadOwned(locator: SourceLocator): OwnedVisualRuleSource? {
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath) ?: return null
        val content = runCatching {
            String(resolved.file.contentsToByteArray(false), resolved.file.charset)
        }.getOrNull() ?: return null
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (fingerprint != locator.revisionFingerprint) return null
        val decoded = decode(content) ?: return null
        if (VisualRuleGenerator.generate(decoded, encode(decoded)) != content) return null
        return OwnedVisualRuleSource(locator.copy(revisionFingerprint = fingerprint), content)
    }

    private fun visitJavaFiles(root: VirtualFile, consumer: (VirtualFile) -> Unit) {
        if (root.isDirectory) {
            root.children.sortedBy(VirtualFile::getName).forEach { visitJavaFiles(it, consumer) }
        } else if (root.extension.equals("java", ignoreCase = true)) {
            consumer(root)
        }
    }

    private fun javaSyntaxError(fileName: String, content: String): PsiErrorElement? {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            fileName,
            JavaFileType.INSTANCE,
            content,
        )
        return PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
    }

    private fun encode(model: VisualRuleModel): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            gson.toJson(model.copy(sourceLocator = null)).toByteArray(Charsets.UTF_8),
        )

    private fun decode(content: String): VisualRuleModel? {
        val marker = content.lineSequence()
            .firstOrNull { it.startsWith(VisualRuleGenerator.markerPrefix()) }
            ?.removePrefix(VisualRuleGenerator.markerPrefix())
            ?.trim()
            ?.takeIf { it.length <= MAX_MARKER_LENGTH }
            ?: return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(marker), Charsets.UTF_8)
            gson.fromJson(json, VisualRuleModel::class.java).copy(sourceLocator = null)
        }.getOrNull()
    }

    private fun rejected(
        code: String,
        message: String,
        relativePath: String? = null,
    ): VisualRuleProposal =
        VisualRuleProposal(null, listOf(WorkspaceChangeIssue(code, message, relativePath)))

    private fun rejectedPreview(issues: List<WorkspaceChangeIssue>): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "visual-rule:rejected",
            label = "Visual rule generation rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    companion object {
        private const val MAX_RULE_FILES = 50_000
        private const val MAX_RULE_BYTES = 8L * 1024 * 1024
        private const val MAX_MARKER_LENGTH = 4_000_000
        private val CONTEXT_KINDS = setOf(
            ArtifactKind.ENTITY,
            ArtifactKind.ENTITY_ATTRIBUTE,
            ArtifactKind.DTO,
            ArtifactKind.ENUM,
            ArtifactKind.BUSINESS_RULE,
            ArtifactKind.SERVICE,
            ArtifactKind.SERVICE_METHOD,
            ArtifactKind.VALIDATOR,
            ArtifactKind.VIEW_DESCRIPTOR,
            ArtifactKind.WORKFLOW_PROCESS,
            ArtifactKind.REST_ENDPOINT,
        )

        fun getInstance(project: Project): VisualRuleWorkspaceService =
            project.getService(VisualRuleWorkspaceService::class.java)
    }
}

data class VisualRuleDocumentSnapshot(
    val locator: SourceLocator,
    val model: VisualRuleModel,
    val editable: Boolean,
    val issue: String?,
)

data class VisualRuleWorkspaceResponse(
    val graphDigest: String,
    val destinations: List<VisualLogicDestinationSnapshot>,
    val defaultDestinationId: String?,
    val contextArtifacts: List<ArtifactSnapshot>,
    val existingDocuments: List<VisualRuleDocumentSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

data class VisualRuleApplyRequest(
    val model: VisualRuleModel,
    val expectedPlanDigest: String,
)

data class VisualRuleProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class OwnedVisualRuleSource(
    val locator: SourceLocator,
    val content: String,
)

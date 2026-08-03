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
import org.jmixworkbench.generator.VisualLogicGenerator
import org.jmixworkbench.model.LogicDiagnosticSeverity
import org.jmixworkbench.model.LogicMethodKind
import org.jmixworkbench.model.LogicNodeKind
import org.jmixworkbench.model.VisualLogicClassModel
import java.util.Base64

/**
 * Source-safe lifecycle for visual transactional Jmix services.
 *
 * Only files carrying a valid round-trip model marker and matching their exact
 * deterministic regeneration can be updated. Handwritten or externally
 * modified Java is always read-only.
 */
@Service(Service.Level.PROJECT)
class VisualLogicWorkspaceService(private val project: Project) {
    private val gson = Gson()

    fun load(forceRefresh: Boolean = false): VisualLogicWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        val destinations = destinations(graph)
        return VisualLogicWorkspaceResponse(
            graphDigest = graph.snapshotDigest,
            destinations = destinations,
            defaultDestinationId = destinations.firstOrNull { it.recommended }?.id,
            contextArtifacts = graph.artifacts.filter { it.kind in CONTEXT_KINDS },
            existingDocuments = discoverExisting(destinations),
            issues = buildList {
                if (!graph.indexHealth.complete) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-LOGIC-GRAPH-PARTIAL",
                            "The application index is partial. Generation remains source-safe, but resolve index diagnostics before trusting complete impact coverage.",
                        ),
                    )
                }
                if (destinations.isEmpty()) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-LOGIC-DESTINATION-MISSING",
                            "No production Java destination is available. Import the Gradle modules and refresh.",
                        ),
                    )
                }
            },
        )
    }

    fun preview(model: VisualLogicClassModel): WorkspaceChangePreviewResponse {
        val proposal = propose(model)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues)
    }

    fun prepare(request: VisualLogicApplyRequest): PreparedWorkspaceChange {
        val proposal = propose(request.model)
        val changeSet = proposal.changeSet
            ?: return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "visual-logic:rejected",
                    label = "Visual logic generation rejected",
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

    internal fun propose(model: VisualLogicClassModel): VisualLogicProposal {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val destination = destinations(graph).firstOrNull { it.id == model.destinationId }
            ?: return rejected(
                "JVW-LOGIC-DESTINATION-INVALID",
                "The selected production destination is no longer registered. Refresh the workspace.",
            )
        val issues = validate(model, graph.artifacts)
        if (issues.isNotEmpty()) {
            return VisualLogicProposal(null, issues)
        }
        val storedModel = model.copy(sourceLocator = null)
        val encoded = encode(storedModel)
        val content = VisualLogicGenerator.generate(storedModel, encoded)
        javaSyntaxError("${model.className}.java", content)?.let { syntax ->
            return rejected(
                "JVW-LOGIC-GENERATED-SYNTAX",
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
                    "JVW-LOGIC-SOURCE-NOT-OWNED",
                    "The existing service is not an unchanged workbench-owned visual service. Handwritten Java is never overwritten.",
                    model.sourceLocator.relativePath,
                )
            if (existing.locator.relativePath != targetPath) {
                return rejected(
                    "JVW-LOGIC-MOVE-UNSUPPORTED",
                    "Changing module, package, or class would move the existing service. Create a new visual service instead.",
                    existing.locator.relativePath,
                )
            }
            if (model.sourceLocator.revisionFingerprint != existing.locator.revisionFingerprint) {
                return rejected(
                    "JVW-LOGIC-SOURCE-STALE",
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
        return VisualLogicProposal(
            changeSet = WorkspaceChangeSet(
                id = "visual-logic:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "${if (model.sourceLocator == null) "Create" else "Update"} visual service ${model.name}",
                files = listOf(change),
            ),
            issues = issues,
        )
    }

    private fun destinations(graph: ApplicationGraphResponse): List<VisualLogicDestinationSnapshot> {
        val fallbackPackage = JmixProjectService.getInstance(project).getConfig()?.basePackage
            ?.takeIf(String::isNotBlank)
            ?: "com.example.app"
        val candidates = ProjectSourceDestinationService.getInstance(project)
            .productionJava(graph)
            .map { source ->
                VisualLogicDestinationSnapshot(
                    id = destinationId(source.moduleId, source.sourceRoot),
                    moduleId = source.moduleId,
                    sourceRoot = source.sourceRoot,
                    defaultPackage = "${inferModuleBasePackage(source.moduleId, graph, fallbackPackage)}.service",
                    recommended = false,
                )
            }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<VisualLogicDestinationSnapshot> { destination ->
                    graph.artifacts.any {
                        it.owner.moduleId == destination.moduleId && it.kind == ArtifactKind.ENTITY
                    }
                }.thenBy { it.moduleId }.thenBy { it.sourceRoot },
            )
        return candidates.mapIndexed { index, destination ->
            destination.copy(recommended = index == 0)
        }
    }

    private fun inferModuleBasePackage(
        moduleId: String,
        graph: ApplicationGraphResponse,
        fallback: String,
    ): String {
        val packages = graph.artifacts.asSequence()
            .filter {
                it.owner.moduleId == moduleId &&
                    it.kind in setOf(
                        ArtifactKind.ENTITY,
                        ArtifactKind.BUSINESS_RULE,
                        ArtifactKind.SERVICE,
                        ArtifactKind.VIEW_CONTROLLER,
                        ArtifactKind.REST_CONTROLLER,
                    )
            }
            .map { it.semanticKey.substringBefore('#').substringBeforeLast('.', "") }
            .filter(String::isNotBlank)
            .map(::trimConventionalPackage)
            .groupingBy { it }
            .eachCount()
        return packages.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length },
        )?.key ?: fallback
    }

    private fun trimConventionalPackage(packageName: String): String {
        val segments = packageName.split('.').toMutableList()
        while (segments.lastOrNull() in CONVENTIONAL_PACKAGE_SUFFIXES) segments.removeLast()
        return segments.joinToString(".").ifBlank { packageName }
    }

    private fun discoverExisting(
        destinations: List<VisualLogicDestinationSnapshot>,
    ): List<VisualLogicDocumentSnapshot> {
        val resolver = ProjectFileResolver.getInstance(project)
        val documents = mutableListOf<VisualLogicDocumentSnapshot>()
        var visited = 0
        destinations.forEach { destination ->
            if (visited >= MAX_VISUAL_LOGIC_FILES) return@forEach
            val target = resolver.resolveTarget(destination.sourceRoot) ?: return@forEach
            val root = if (target.relativePath.isBlank()) {
                target.root
            } else {
                target.root.findFileByRelativePath(target.relativePath)
            } ?: return@forEach
            visitJavaFiles(root) { file ->
                if (visited++ >= MAX_VISUAL_LOGIC_FILES || file.length > MAX_VISUAL_LOGIC_BYTES) {
                    return@visitJavaFiles
                }
                val relativePath = resolver.locatorPath(file) ?: return@visitJavaFiles
                val content = runCatching {
                    String(file.contentsToByteArray(false), file.charset)
                }.getOrNull() ?: return@visitJavaFiles
                val encoded = encodedMarker(content) ?: return@visitJavaFiles
                val decoded = decodeEncoded(encoded) ?: return@visitJavaFiles
                val fingerprint = CanonicalDiscoveryJson.sha256(content)
                val locator = SourceLocator(
                    relativePath = relativePath,
                    symbol = decoded.className,
                    revisionFingerprint = fingerprint,
                )
                val owned = VisualLogicGenerator.generate(decoded, encoded) == content
                documents += VisualLogicDocumentSnapshot(
                    locator = locator,
                    model = decoded.copy(sourceLocator = locator),
                    editable = owned,
                    issue = if (owned) null else "Manual Java changes were detected; visual overwrite is disabled.",
                )
            }
        }
        return documents.distinctBy { it.locator.relativePath }.sortedBy { it.locator.relativePath }
    }

    private fun loadOwned(locator: SourceLocator): OwnedVisualLogicSource? {
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath) ?: return null
        val content = runCatching {
            String(resolved.file.contentsToByteArray(false), resolved.file.charset)
        }.getOrNull() ?: return null
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (fingerprint != locator.revisionFingerprint) return null
        val encoded = encodedMarker(content) ?: return null
        val decoded = decodeEncoded(encoded) ?: return null
        if (VisualLogicGenerator.generate(decoded, encoded) != content) return null
        return OwnedVisualLogicSource(
            locator = locator.copy(revisionFingerprint = fingerprint),
            content = content,
        )
    }

    private fun visitJavaFiles(root: VirtualFile, consumer: (VirtualFile) -> Unit) {
        if (root.isDirectory) {
            root.children.sortedBy(VirtualFile::getName).forEach { visitJavaFiles(it, consumer) }
        } else if (root.extension.equals("java", ignoreCase = true)) {
            consumer(root)
        }
    }

    private fun validate(
        model: VisualLogicClassModel,
        artifacts: List<ArtifactSnapshot>,
    ): List<WorkspaceChangeIssue> {
        val issues = VisualLogicGenerator.validate(model).diagnostics
            .filter { it.severity == LogicDiagnosticSeverity.ERROR }
            .map { diagnostic ->
            WorkspaceChangeIssue(
                code = diagnostic.code,
                message = buildString {
                    diagnostic.methodName?.let { append(it).append(": ") }
                    diagnostic.nodeId?.let { append('[').append(it).append("] ") }
                    append(diagnostic.message)
                },
            )
        }.toMutableList()
        val indexedEntities = artifacts
            .filter { it.kind == ArtifactKind.ENTITY || it.kind == ArtifactKind.DTO }
            .flatMap { listOf(it.semanticKey, it.displayName) }
            .toSet()
        val indexedTypes = artifacts
            .filter {
                it.kind in setOf(
                    ArtifactKind.SERVICE,
                    ArtifactKind.BUSINESS_RULE,
                    ArtifactKind.SOURCE_TYPE,
                    ArtifactKind.REPOSITORY,
                    ArtifactKind.VALIDATOR,
                )
            }
            .flatMap { listOf(it.semanticKey, it.displayName) }
            .toSet()
        model.methods.forEach { method ->
            method.nodes.forEach { node ->
                node.entityClass?.takeIf(String::isNotBlank)?.let { entityClass ->
                    if (node.kind in ENTITY_NODE_KINDS && entityClass !in indexedEntities) {
                        issues += WorkspaceChangeIssue(
                            "JVW-LOGIC-ENTITY-NOT-INDEXED",
                            "${method.name} [${node.id}]: Entity is not present in the current application index: $entityClass.",
                        )
                    }
                }
                node.beanClass?.takeIf(String::isNotBlank)?.let { beanClass ->
                    if (node.kind == LogicNodeKind.CALL_SERVICE && beanClass !in indexedTypes) {
                        issues += WorkspaceChangeIssue(
                            "JVW-LOGIC-BEAN-NOT-INDEXED",
                            "${method.name} [${node.id}]: Service bean type is not present in the current application index: $beanClass.",
                        )
                    }
                }
            }
        }
        return issues.distinct().sortedBy(WorkspaceChangeIssue::code)
    }

    private fun javaSyntaxError(fileName: String, content: String): PsiErrorElement? {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            fileName,
            JavaFileType.INSTANCE,
            content,
        )
        return PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
    }

    private fun encode(model: VisualLogicClassModel): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            gson.toJson(model.copy(sourceLocator = null)).toByteArray(Charsets.UTF_8),
        )

    private fun decode(content: String): VisualLogicClassModel? {
        val marker = encodedMarker(content) ?: return null
        return decodeEncoded(marker)
    }

    private fun encodedMarker(content: String): String? =
        content.lineSequence()
            .firstOrNull { it.startsWith(VisualLogicGenerator.markerPrefix()) }
            ?.removePrefix(VisualLogicGenerator.markerPrefix())
            ?.trim()
            ?.takeIf { it.length <= MAX_MARKER_LENGTH }

    private fun decodeEncoded(marker: String): VisualLogicClassModel? {
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(marker), Charsets.UTF_8)
            val decoded = gson.fromJson(json, VisualLogicClassModel::class.java)
            decoded.copy(
                methods = decoded.methods.map { method ->
                    method.copy(
                        kind = runCatching { requireNotNull(method.kind) }
                            .getOrDefault(LogicMethodKind.ENTRY_POINT),
                    )
                },
                sourceLocator = null,
            )
        }.getOrNull()
    }

    private fun destinationId(moduleId: String, sourceRoot: String): String =
        CanonicalDiscoveryJson.sha256("$moduleId\u0000$sourceRoot").take(24)

    private fun rejected(
        code: String,
        message: String,
        relativePath: String? = null,
    ): VisualLogicProposal =
        VisualLogicProposal(null, listOf(WorkspaceChangeIssue(code, message, relativePath)))

    private fun rejectedPreview(issues: List<WorkspaceChangeIssue>): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "visual-logic:rejected",
            label = "Visual logic generation rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    companion object {
        private const val MAX_VISUAL_LOGIC_FILES = 50_000
        private const val MAX_VISUAL_LOGIC_BYTES = 8L * 1024 * 1024
        private const val MAX_MARKER_LENGTH = 4_000_000
        private val ENTITY_NODE_KINDS = setOf(
            LogicNodeKind.CREATE_ENTITY,
            LogicNodeKind.LOAD_ENTITY_BY_ID,
            LogicNodeKind.LOAD_ENTITIES,
            LogicNodeKind.AUTHORIZE_ENTITY,
        )
        private val CONTEXT_KINDS = setOf(
            ArtifactKind.ENTITY,
            ArtifactKind.ENTITY_ATTRIBUTE,
            ArtifactKind.DTO,
            ArtifactKind.ENUM,
            ArtifactKind.BUSINESS_RULE,
            ArtifactKind.SERVICE,
            ArtifactKind.SERVICE_METHOD,
            ArtifactKind.REPOSITORY,
            ArtifactKind.VALIDATOR,
            ArtifactKind.RESOURCE_ROLE,
            ArtifactKind.ROW_ROLE,
            ArtifactKind.SECURITY_POLICY,
        )
        private val CONVENTIONAL_PACKAGE_SUFFIXES = setOf(
            "entity",
            "service",
            "view",
            "security",
            "rest",
            "controller",
            "repository",
        )

        fun getInstance(project: Project): VisualLogicWorkspaceService =
            project.getService(VisualLogicWorkspaceService::class.java)
    }
}

data class VisualLogicDestinationSnapshot(
    val id: String,
    val moduleId: String,
    val sourceRoot: String,
    val defaultPackage: String,
    val recommended: Boolean,
)

data class VisualLogicDocumentSnapshot(
    val locator: SourceLocator,
    val model: VisualLogicClassModel,
    val editable: Boolean,
    val issue: String?,
)

data class VisualLogicWorkspaceResponse(
    val graphDigest: String,
    val destinations: List<VisualLogicDestinationSnapshot>,
    val defaultDestinationId: String?,
    val contextArtifacts: List<ArtifactSnapshot>,
    val existingDocuments: List<VisualLogicDocumentSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

data class VisualLogicApplyRequest(
    val model: VisualLogicClassModel,
    val expectedPlanDigest: String,
)

data class VisualLogicProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class OwnedVisualLogicSource(
    val locator: SourceLocator,
    val content: String,
)

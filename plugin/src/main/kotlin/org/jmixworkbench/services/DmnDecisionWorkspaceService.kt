package org.jmixworkbench.services

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.DmnDecisionGenerator
import org.jmixworkbench.model.DmnDecisionModel
import org.jmixworkbench.model.DmnDiagnosticSeverity
import org.jmixworkbench.model.DmnSimulationRequest
import org.jmixworkbench.model.DmnSimulationResult
import org.jmixworkbench.model.WorkflowNodeType
import java.util.Base64

/**
 * Source-safe lifecycle for Jmix/Flowable DMN decision tables.
 *
 * A visual document can only overwrite source when the embedded model still
 * regenerates the exact bytes on disk. Standard or manually changed DMN is
 * indexed and reported but never rewritten by this service.
 */
@Service(Service.Level.PROJECT)
class DmnDecisionWorkspaceService(private val project: Project) {
    private val gson = Gson()

    fun load(forceRefresh: Boolean = false): DmnDecisionWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        val destinations = destinations(graph)
        val documents = discoverExisting(destinations)
        val keys = documents.mapNotNull { it.model?.key }.toSet()
        val workflowReferences = workflowReferences(graph).map { reference ->
            reference.copy(resolved = reference.decisionKey in keys)
        }
        return DmnDecisionWorkspaceResponse(
            graphDigest = graph.snapshotDigest,
            destinations = destinations,
            defaultDestinationId = destinations.firstOrNull { it.recommended }?.id,
            existingDocuments = documents,
            workflowReferences = workflowReferences,
            issues = buildList {
                if (!graph.indexHealth.complete) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-DMN-GRAPH-PARTIAL",
                            "The application index is partial. Resolve index diagnostics before trusting complete decision impact coverage.",
                        ),
                    )
                }
                if (destinations.isEmpty()) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-DMN-DESTINATION-MISSING",
                            "No production resource destination is available. Import the Gradle modules and refresh.",
                        ),
                    )
                }
                workflowReferences.filterNot(DmnWorkflowReferenceSnapshot::resolved).forEach { reference ->
                    add(
                        WorkspaceChangeIssue(
                            "JVW-DMN-WORKFLOW-UNRESOLVED",
                            "Workflow '${reference.processId}' node '${reference.nodeName}' references missing decision '${reference.decisionKey}'.",
                            reference.locator.relativePath,
                        ),
                    )
                }
            }.distinctBy { "${it.code}\u0000${it.relativePath.orEmpty()}\u0000${it.message}" },
        )
    }

    fun preview(model: DmnDecisionModel): WorkspaceChangePreviewResponse {
        val proposal = propose(model)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues)
    }

    fun prepare(request: DmnDecisionApplyRequest): PreparedWorkspaceChange {
        val proposal = propose(request.model)
        val changeSet = proposal.changeSet
            ?: return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "dmn-decision:rejected",
                    label = "DMN decision generation rejected",
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

    fun simulate(request: DmnSimulationRequest): DmnSimulationResult =
        DmnDecisionGenerator.simulate(request.model, request.inputs)

    internal fun propose(model: DmnDecisionModel): DmnDecisionProposal {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val destination = destinations(graph).firstOrNull { it.id == model.destinationId }
            ?: return rejected(
                "JVW-DMN-DESTINATION-INVALID",
                "The selected production resource destination is no longer registered. Refresh the workspace.",
            )
        val errors = DmnDecisionGenerator.validate(model).diagnostics
            .filter { it.severity == DmnDiagnosticSeverity.ERROR }
            .map { diagnostic ->
                WorkspaceChangeIssue(
                    diagnostic.code,
                    buildString {
                        diagnostic.ruleIds.takeIf(List<String>::isNotEmpty)?.let {
                            append('[').append(it.joinToString()).append("] ")
                        }
                        diagnostic.columnId?.let { append('[').append(it).append("] ") }
                        append(diagnostic.message)
                    },
                )
            }
        if (errors.isNotEmpty()) return DmnDecisionProposal(null, errors)

        val storedModel = model.copy(sourceLocator = null)
        val content = DmnDecisionGenerator.generate(storedModel, encode(storedModel))
        val targetPath = "${destination.resourceRoot.trimEnd('/', '\\')}/dmn/${model.fileName}"
        val change = if (model.sourceLocator == null) {
            if (ProjectFileResolver.getInstance(project).resolveFile(targetPath) != null) {
                return rejected(
                    "JVW-DMN-TARGET-EXISTS",
                    "A file already exists at the selected target. Open that document or choose another file name.",
                    targetPath,
                )
            }
            WorkspaceFileChange(
                relativePath = targetPath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = content,
            )
        } else {
            val existing = loadOwned(model.sourceLocator)
                ?: return rejected(
                    "JVW-DMN-SOURCE-NOT-OWNED",
                    "The existing decision is not an unchanged workbench-owned DMN source. Manual DMN is never overwritten.",
                    model.sourceLocator.relativePath,
                )
            if (existing.locator.relativePath != targetPath) {
                return rejected(
                    "JVW-DMN-MOVE-UNSUPPORTED",
                    "Changing module or file name would move this decision. Create a new decision instead.",
                    existing.locator.relativePath,
                )
            }
            if (model.sourceLocator.revisionFingerprint != existing.locator.revisionFingerprint) {
                return rejected(
                    "JVW-DMN-SOURCE-STALE",
                    "The DMN source changed after it was loaded. Refresh before editing.",
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
        return DmnDecisionProposal(
            WorkspaceChangeSet(
                id = "dmn-decision:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "${if (model.sourceLocator == null) "Create" else "Update"} DMN decision ${model.name}",
                files = listOf(change),
            ),
            emptyList(),
        )
    }

    private fun destinations(graph: ApplicationGraphResponse): List<DmnDestinationSnapshot> {
        val candidates = ProjectSourceDestinationService.getInstance(project)
            .productionResources(graph)
            .map { source ->
                DmnDestinationSnapshot(
                    id = CanonicalDiscoveryJson.sha256("${source.moduleId}\u0000${source.sourceRoot}").take(24),
                    moduleId = source.moduleId,
                    resourceRoot = source.sourceRoot,
                    dmnDirectory = "${source.sourceRoot}/dmn",
                    recommended = false,
                )
            }
            .distinctBy(DmnDestinationSnapshot::id)
            .sortedWith(
                compareByDescending<DmnDestinationSnapshot> { destination ->
                    graph.artifacts.any {
                        it.owner.moduleId == destination.moduleId &&
                            it.kind in setOf(ArtifactKind.ENTITY, ArtifactKind.WORKFLOW_PROCESS)
                    }
                }.thenBy(DmnDestinationSnapshot::moduleId)
                    .thenBy(DmnDestinationSnapshot::resourceRoot),
            )
        return candidates.mapIndexed { index, destination ->
            destination.copy(recommended = index == 0)
        }
    }

    private fun discoverExisting(
        destinations: List<DmnDestinationSnapshot>,
    ): List<DmnDecisionDocumentSnapshot> {
        val resolver = ProjectFileResolver.getInstance(project)
        val documents = mutableListOf<DmnDecisionDocumentSnapshot>()
        var visited = 0
        destinations.forEach { destination ->
            if (visited >= MAX_DMN_FILES) return@forEach
            val resolved = resolver.resolveTarget(destination.dmnDirectory) ?: return@forEach
            val root = if (resolved.relativePath.isBlank()) {
                resolved.root
            } else {
                resolved.root.findFileByRelativePath(resolved.relativePath)
            } ?: return@forEach
            visitDmnFiles(root) { file ->
                if (visited++ >= MAX_DMN_FILES || file.length > MAX_DMN_BYTES) return@visitDmnFiles
                val path = resolver.locatorPath(file) ?: return@visitDmnFiles
                val content = read(file) ?: return@visitDmnFiles
                val decoded = decode(content)
                val parsed = if (decoded == null) {
                    DmnDecisionParser.parse(content, destination.id, file.name)
                } else {
                    DmnDecisionParseResult(decoded, emptyList())
                }
                val discoveredModel = parsed.model
                if (discoveredModel == null) {
                    documents += DmnDecisionDocumentSnapshot(
                        locator = SourceLocator(path, revisionFingerprint = CanonicalDiscoveryJson.sha256(content)),
                        model = null,
                        editable = false,
                        issue = buildString {
                            append("Standard or handwritten DMN is protected and cannot be represented safely.")
                            parsed.unsupported.firstOrNull()?.let { append(' ').append(it) }
                        },
                    )
                    return@visitDmnFiles
                }
                val locator = SourceLocator(
                    relativePath = path,
                    symbol = discoveredModel.key,
                    revisionFingerprint = CanonicalDiscoveryJson.sha256(content),
                )
                val owned = decoded != null && runCatching {
                    DmnDecisionGenerator.generate(decoded, encode(decoded)) == content
                }.getOrDefault(false)
                documents += DmnDecisionDocumentSnapshot(
                    locator = locator,
                    model = discoveredModel.copy(sourceLocator = locator),
                    editable = owned,
                    issue = when {
                        owned -> null
                        decoded != null -> "Manual DMN changes were detected; visual overwrite is disabled."
                        parsed.unsupported.isEmpty() ->
                            "Existing standard DMN was parsed for inspection. Visual overwrite is disabled."
                        else ->
                            "Existing DMN was parsed read-only; unsupported expressions remain protected: ${parsed.unsupported.first()}"
                    },
                )
            }
        }
        return documents.distinctBy { it.locator.relativePath }.sortedBy { it.locator.relativePath }
    }

    private fun workflowReferences(graph: ApplicationGraphResponse): List<DmnWorkflowReferenceSnapshot> =
        graph.artifacts.asSequence()
            .filter { it.kind == ArtifactKind.WORKFLOW_PROCESS }
            .take(MAX_WORKFLOW_PROCESSES)
            .flatMap { artifact ->
                val loaded = WorkflowWorkspaceService.getInstance(project).load(
                    artifact.sourceLocator.relativePath,
                    artifact.semanticKey,
                    artifact.owner.moduleId,
                )
                loaded.workflow?.nodes.orEmpty().asSequence()
                    .filter { it.type == WorkflowNodeType.BUSINESS_RULE_STATE }
                    .mapNotNull { node ->
                        node.decisionTableKey?.takeIf(String::isNotBlank)?.let { key ->
                            DmnWorkflowReferenceSnapshot(
                                processId = artifact.semanticKey,
                                nodeId = node.id,
                                nodeName = node.name,
                                decisionKey = key,
                                locator = artifact.sourceLocator,
                                resolved = false,
                            )
                        }
                    }
            }
            .distinctBy { "${it.locator.relativePath}\u0000${it.nodeId}\u0000${it.decisionKey}" }
            .sortedWith(compareBy(DmnWorkflowReferenceSnapshot::processId, DmnWorkflowReferenceSnapshot::nodeId))
            .toList()

    private fun loadOwned(locator: SourceLocator): OwnedDmnSource? {
        val file = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath)?.file ?: return null
        val content = read(file) ?: return null
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (fingerprint != locator.revisionFingerprint) return null
        val decoded = decode(content) ?: return null
        if (runCatching { DmnDecisionGenerator.generate(decoded, encode(decoded)) == content }.getOrDefault(false).not()) {
            return null
        }
        return OwnedDmnSource(locator.copy(revisionFingerprint = fingerprint), content)
    }

    private fun read(file: VirtualFile): String? =
        runCatching { String(file.contentsToByteArray(false), file.charset) }.getOrNull()

    private fun visitDmnFiles(root: VirtualFile, consumer: (VirtualFile) -> Unit) {
        if (root.isDirectory) {
            root.children.sortedBy(VirtualFile::getName).forEach { visitDmnFiles(it, consumer) }
        } else if (
            root.name.endsWith(".dmn", ignoreCase = true) ||
            root.name.endsWith(".dmn.xml", ignoreCase = true)
        ) {
            consumer(root)
        }
    }

    private fun encode(model: DmnDecisionModel): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            gson.toJson(model.copy(sourceLocator = null)).toByteArray(Charsets.UTF_8),
        )

    private fun decode(content: String): DmnDecisionModel? {
        val marker = content.lineSequence()
            .firstOrNull { it.trimStart().startsWith(DmnDecisionGenerator.markerPrefix()) }
            ?.trim()
            ?.removePrefix(DmnDecisionGenerator.markerPrefix())
            ?.removeSuffix("-->")
            ?.trim()
            ?.takeIf { it.length <= MAX_MARKER_LENGTH }
            ?: return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(marker), Charsets.UTF_8)
            gson.fromJson(json, DmnDecisionModel::class.java).copy(sourceLocator = null)
        }.getOrNull()
    }

    private fun rejected(
        code: String,
        message: String,
        relativePath: String? = null,
    ): DmnDecisionProposal =
        DmnDecisionProposal(null, listOf(WorkspaceChangeIssue(code, message, relativePath)))

    private fun rejectedPreview(issues: List<WorkspaceChangeIssue>): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "dmn-decision:rejected",
            label = "DMN decision generation rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    companion object {
        private const val MAX_DMN_FILES = 50_000
        private const val MAX_DMN_BYTES = 8L * 1024 * 1024
        private const val MAX_MARKER_LENGTH = 4_000_000
        private const val MAX_WORKFLOW_PROCESSES = 10_000
        fun getInstance(project: Project): DmnDecisionWorkspaceService =
            project.getService(DmnDecisionWorkspaceService::class.java)
    }
}

data class DmnDestinationSnapshot(
    val id: String,
    val moduleId: String,
    val resourceRoot: String,
    val dmnDirectory: String,
    val recommended: Boolean,
)

data class DmnDecisionDocumentSnapshot(
    val locator: SourceLocator,
    val model: DmnDecisionModel?,
    val editable: Boolean,
    val issue: String?,
)

data class DmnWorkflowReferenceSnapshot(
    val processId: String,
    val nodeId: String,
    val nodeName: String,
    val decisionKey: String,
    val locator: SourceLocator,
    val resolved: Boolean,
)

data class DmnDecisionWorkspaceResponse(
    val graphDigest: String,
    val destinations: List<DmnDestinationSnapshot>,
    val defaultDestinationId: String?,
    val existingDocuments: List<DmnDecisionDocumentSnapshot>,
    val workflowReferences: List<DmnWorkflowReferenceSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

data class DmnDecisionApplyRequest(
    val model: DmnDecisionModel,
    val expectedPlanDigest: String,
)

data class DmnDecisionProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class OwnedDmnSource(
    val locator: SourceLocator,
    val content: String,
)

package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.flowui.FlowUiDescriptorParser
import org.jmixworkbench.discovery.flowui.FlowUiDescriptorSnapshot
import org.jmixworkbench.discovery.flowui.FlowUiMoveDirection
import org.jmixworkbench.discovery.flowui.FlowUiPropertyChangeProposal
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactRelationship
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.navigation.SourceNavigationPolicy

@Service(Service.Level.PROJECT)
class FlowUiWorkspaceService(
    private val project: Project,
) {
    fun load(request: FlowUiWorkspaceRequest): FlowUiWorkspaceResponse {
        val loaded = loadDescriptor(request.sourceLocator)
        if (loaded.document == null) {
            return FlowUiWorkspaceResponse(
                accepted = false,
                document = null,
                contextArtifacts = emptyList(),
                contextRelationships = emptyList(),
                issues = loaded.issues,
                dataModel = null,
                controllerModel = null,
            )
        }

        val graph = ApplicationGraphService.getInstance(project).graph()
        val descriptor = graph.artifacts.firstOrNull {
            it.kind == ArtifactKind.VIEW_DESCRIPTOR &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        }
        val context = if (descriptor == null) {
            ContextSelection(
                artifacts = graph.artifacts
                    .asSequence()
                    .filter { it.sourceLocator.relativePath == request.sourceLocator.relativePath }
                    .take(MAX_CONTEXT_ARTIFACTS)
                    .toList(),
                relationships = emptyList(),
            )
        } else {
            selectContext(descriptor, graph.artifacts, graph.relationships)
        }

        return FlowUiWorkspaceResponse(
            accepted = true,
            document = loaded.document,
            contextArtifacts = context.artifacts,
            contextRelationships = context.relationships,
            issues = emptyList(),
            dataModel = buildDataModel(loaded.document, graph.artifacts),
            controllerModel = FlowUiControllerPsiReader.read(project, context.artifacts),
        )
    }

    fun previewPropertyChange(request: FlowUiPropertyChangeRequest): WorkspaceChangePreviewResponse {
        val proposal = propertyProposal(request)
        if (!proposal.accepted) {
            return rejectedPreview("flowui-property:rejected", proposal.issues)
        }
        if (proposal.noChange || proposal.changeSet == null) {
            return WorkspaceChangePreviewResponse(
                accepted = true,
                changeSetId = "flowui-property:no-change",
                label = "No FlowUI source change required",
                planDigest = null,
                files = emptyList(),
                issues = emptyList(),
            )
        }
        return WorkspaceChangeService.getInstance(project).preview(proposal.changeSet)
    }

    fun preparePropertyChange(request: FlowUiPropertyApplyRequest): PreparedWorkspaceChange {
        val proposal = propertyProposal(request.change)
        val changeSet = proposal.changeSet
        if (!proposal.accepted || proposal.noChange || changeSet == null) {
            val issues = proposal.issues.ifEmpty {
                listOf(
                    WorkspaceChangeIssue(
                        code = "JVW-FLOWUI-NO-CHANGE",
                        message = "The selected property already has the requested value.",
                        relativePath = request.change.sourceLocator.relativePath,
                    ),
                )
            }
            return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = changeSet?.id ?: "flowui-property:rejected",
                    label = changeSet?.label ?: "FlowUI property change rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = issues,
                ),
                baseDir = null,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(
                changeSet = changeSet,
                expectedPlanDigest = request.expectedPlanDigest,
            ),
        )
    }

    fun previewDirectTextChange(request: FlowUiDirectTextChangeRequest): WorkspaceChangePreviewResponse {
        val proposal = directTextProposal(request)
        if (!proposal.accepted) {
            return rejectedPreview("flowui-text:rejected", proposal.issues)
        }
        if (proposal.noChange || proposal.changeSet == null) {
            return WorkspaceChangePreviewResponse(
                accepted = true,
                changeSetId = "flowui-text:no-change",
                label = "No FlowUI source change required",
                planDigest = null,
                files = emptyList(),
                issues = emptyList(),
            )
        }
        return WorkspaceChangeService.getInstance(project).preview(proposal.changeSet)
    }

    fun prepareDirectTextChange(request: FlowUiDirectTextApplyRequest): PreparedWorkspaceChange =
        prepareProposal(
            proposal = directTextProposal(request.change),
            expectedPlanDigest = request.expectedPlanDigest,
            path = request.change.sourceLocator.relativePath,
            rejectedId = "flowui-text:rejected",
        )

    fun previewStructureChange(request: FlowUiStructureChangeRequest): WorkspaceChangePreviewResponse {
        val proposal = structureProposal(request)
        if (!proposal.accepted) {
            return rejectedPreview("flowui-structure:rejected", proposal.issues)
        }
        if (proposal.noChange || proposal.changeSet == null) {
            return WorkspaceChangePreviewResponse(
                accepted = true,
                changeSetId = "flowui-structure:no-change",
                label = "No FlowUI source change required",
                planDigest = null,
                files = emptyList(),
                issues = emptyList(),
            )
        }
        return WorkspaceChangeService.getInstance(project).preview(proposal.changeSet)
    }

    fun prepareStructureChange(request: FlowUiStructureApplyRequest): PreparedWorkspaceChange =
        prepareProposal(
            proposal = structureProposal(request.change),
            expectedPlanDigest = request.expectedPlanDigest,
            path = request.change.sourceLocator.relativePath,
            rejectedId = "flowui-structure:rejected",
        )

    private fun propertyProposal(request: FlowUiPropertyChangeRequest): FlowUiPropertyChangeProposal =
        loadDescriptor(request.sourceLocator).let { loaded ->
            val document = loaded.document
                ?: return@let FlowUiPropertyChangeProposal(
                    accepted = false,
                    noChange = false,
                    changeSet = null,
                    issues = loaded.issues,
                )
            FlowUiDescriptorParser.proposePropertyChange(
                document = document,
                elementKey = request.elementKey,
                propertyName = request.propertyName,
                value = request.value,
            )
        }

    private fun directTextProposal(request: FlowUiDirectTextChangeRequest): FlowUiPropertyChangeProposal =
        loadDescriptor(request.sourceLocator).let { loaded ->
            val document = loaded.document
                ?: return@let FlowUiPropertyChangeProposal(
                    accepted = false,
                    noChange = false,
                    changeSet = null,
                    issues = loaded.issues,
                )
            FlowUiDescriptorParser.proposeDirectTextChange(document, request.elementKey, request.value)
        }

    private fun structureProposal(request: FlowUiStructureChangeRequest): FlowUiPropertyChangeProposal =
        loadDescriptor(request.sourceLocator).let { loaded ->
            val document = loaded.document
                ?: return@let FlowUiPropertyChangeProposal(
                    accepted = false,
                    noChange = false,
                    changeSet = null,
                    issues = loaded.issues,
                )
            when (request.operation) {
                FlowUiStructureOperation.INSERT_CHILD -> FlowUiDescriptorParser.proposeInsertChild(
                    document = document,
                    parentKey = request.parentKey.orEmpty(),
                    tagName = request.tagName.orEmpty(),
                    attributes = request.attributes,
                )
                FlowUiStructureOperation.DELETE -> FlowUiDescriptorParser.proposeDeleteElement(
                    document = document,
                    elementKey = request.elementKey.orEmpty(),
                )
                FlowUiStructureOperation.MOVE_UP -> FlowUiDescriptorParser.proposeMoveElement(
                    document = document,
                    elementKey = request.elementKey.orEmpty(),
                    direction = FlowUiMoveDirection.UP,
                )
                FlowUiStructureOperation.MOVE_DOWN -> FlowUiDescriptorParser.proposeMoveElement(
                    document = document,
                    elementKey = request.elementKey.orEmpty(),
                    direction = FlowUiMoveDirection.DOWN,
                )
            }
        }

    private fun prepareProposal(
        proposal: FlowUiPropertyChangeProposal,
        expectedPlanDigest: String,
        path: String,
        rejectedId: String,
    ): PreparedWorkspaceChange {
        val changeSet = proposal.changeSet
        if (!proposal.accepted || proposal.noChange || changeSet == null) {
            val issues = proposal.issues.ifEmpty {
                listOf(
                    WorkspaceChangeIssue(
                        code = "JVW-FLOWUI-NO-CHANGE",
                        message = "The requested FlowUI operation no longer produces a source change.",
                        relativePath = path,
                    ),
                )
            }
            return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = changeSet?.id ?: rejectedId,
                    label = changeSet?.label ?: "FlowUI structure change rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = issues,
                ),
                baseDir = null,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, expectedPlanDigest),
        )
    }

    private fun loadDescriptor(locator: SourceLocator): LoadedDescriptor {
        val validation = SourceNavigationPolicy.validate(
            relativePath = locator.relativePath,
            line = locator.line,
            column = locator.column,
            revisionFingerprint = locator.revisionFingerprint,
        )
        val validated = validation.locator
            ?: return LoadedDescriptor(
                document = null,
                issues = listOf(
                    WorkspaceChangeIssue(
                        code = validation.errorCode ?: "JVW-FLOWUI-PATH-REJECTED",
                        message = validation.message,
                        relativePath = locator.relativePath,
                    ),
                ),
            )
        if (!validated.relativePath.endsWith(".xml", ignoreCase = true)) {
            return rejectedDescriptor(
                "JVW-FLOWUI-SOURCE-UNSUPPORTED",
                "Only Jmix FlowUI XML descriptors can be opened in the round-trip designer.",
                validated.relativePath,
            )
        }
        val baseDir = project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)
            ?: return rejectedDescriptor(
                "JVW-FLOWUI-PROJECT-MISSING",
                "The open project root is unavailable.",
                validated.relativePath,
            )
        val file = baseDir.findFileByRelativePath(validated.relativePath)
            ?: return rejectedDescriptor(
                "JVW-FLOWUI-SOURCE-MISSING",
                "The selected FlowUI descriptor no longer exists.",
                validated.relativePath,
            )
        if (file.isDirectory || !VfsUtilCore.isAncestor(baseDir, file, false)) {
            return rejectedDescriptor(
                "JVW-FLOWUI-PATH-REJECTED",
                "The selected descriptor is outside the open project.",
                validated.relativePath,
            )
        }
        if (file.length > MAX_DESCRIPTOR_BYTES) {
            return rejectedDescriptor(
                "JVW-FLOWUI-SOURCE-TOO-LARGE",
                "The descriptor exceeds the reviewed ${MAX_DESCRIPTOR_BYTES / (1024 * 1024)} MiB editing limit.",
                validated.relativePath,
            )
        }
        val content = runCatching {
            String(file.contentsToByteArray(false), file.charset)
        }.getOrElse {
            return rejectedDescriptor(
                "JVW-FLOWUI-SOURCE-UNREADABLE",
                "The selected FlowUI descriptor cannot be read.",
                validated.relativePath,
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (!SourceNavigationPolicy.revisionMatches(validated, fingerprint)) {
            return rejectedDescriptor(
                "JVW-FLOWUI-SOURCE-STALE",
                "The FlowUI descriptor changed after the selected application-map snapshot. Refresh before editing.",
                validated.relativePath,
            )
        }
        val parsed = FlowUiDescriptorParser.parse(validated.relativePath, content, fingerprint)
        return LoadedDescriptor(parsed.document, parsed.issues)
    }

    private fun selectContext(
        descriptor: ArtifactSnapshot,
        artifacts: List<ArtifactSnapshot>,
        relationships: List<ArtifactRelationship>,
    ): ContextSelection {
        val ids = linkedSetOf(descriptor.id)
        artifacts.asSequence()
            .filter { it.sourceLocator.relativePath == descriptor.sourceLocator.relativePath }
            .take(MAX_CONTEXT_ARTIFACTS)
            .forEach { ids += it.id }

        repeat(CONTEXT_DEPTH) {
            relationships.asSequence()
                .filter { it.sourceArtifactId in ids || it.targetArtifactId in ids }
                .take(MAX_CONTEXT_RELATIONSHIPS)
                .forEach { relationship ->
                    ids += relationship.sourceArtifactId
                    relationship.targetArtifactId?.let(ids::add)
                }
        }

        val selectedArtifacts = artifacts.asSequence()
            .filter { it.id in ids }
            .sortedWith(compareBy<ArtifactSnapshot>({ contextRank(it.kind) }, { it.displayName }))
            .take(MAX_CONTEXT_ARTIFACTS)
            .toList()
        val selectedIds = selectedArtifacts.mapTo(hashSetOf(), ArtifactSnapshot::id)
        val selectedRelationships = relationships.asSequence()
            .filter { it.sourceArtifactId in selectedIds && it.targetArtifactId in selectedIds }
            .take(MAX_CONTEXT_RELATIONSHIPS)
            .toList()
        return ContextSelection(selectedArtifacts, selectedRelationships)
    }

    private fun buildDataModel(
        document: FlowUiDescriptorSnapshot,
        allArtifacts: List<ArtifactSnapshot>,
    ): FlowUiDataWorkspaceSnapshot {
        val elements = document.elements.associateBy { it.key }
        fun children(elementKey: String) =
            elements[elementKey]?.childKeys.orEmpty().mapNotNull(elements::get)

        val containers = document.elements
            .filter { it.localTag in DATA_CONTAINER_TAGS && !it.id.isNullOrBlank() }
            .map { container ->
                val loader = children(container.key).firstOrNull { it.localTag == "loader" }
                val query = loader?.let { children(it.key).firstOrNull { child -> child.localTag == "query" } }
                val inlineFetchPlan = children(container.key).firstOrNull { it.localTag == "fetchPlan" }
                FlowUiDataContainerSnapshot(
                    elementKey = container.key,
                    id = container.id.orEmpty(),
                    kind = container.localTag,
                    entityClass = container.attributes.firstOrNull { it.name == "class" }?.value,
                    property = container.attributes.firstOrNull { it.name == "property" }?.value,
                    fetchPlan = container.attributes.firstOrNull { it.name == "fetchPlan" }?.value
                        ?: inlineFetchPlan?.attributes?.firstOrNull {
                            it.name == "extends" || it.name == "name"
                        }?.value,
                    fetchPlanElementKey = inlineFetchPlan?.key,
                    loaderElementKey = loader?.key,
                    loaderId = loader?.id,
                    queryElementKey = query?.key,
                    query = query?.directText,
                )
            }
        val bindings = document.elements.mapNotNull { element ->
            val containerId = element.attributes.firstOrNull {
                it.name == "dataContainer" || it.name == "itemsContainer"
            }?.value ?: return@mapNotNull null
            FlowUiComponentBindingSnapshot(
                elementKey = element.key,
                componentId = element.id,
                componentTag = element.localTag,
                containerId = containerId,
                property = element.attributes.firstOrNull { it.name == "property" }?.value,
            )
        }
        val relevantEntities = containers.mapNotNull(FlowUiDataContainerSnapshot::entityClass).toSet()
        val fields = allArtifacts.asSequence()
            .filter { it.kind == ArtifactKind.ENTITY_ATTRIBUTE }
            .filter { artifact ->
                val entityKey = artifact.semanticKey.substringBeforeLast('.', "")
                relevantEntities.any { requested ->
                    entityKey == requested ||
                        entityKey.endsWith(".$requested") ||
                        requested.endsWith(".$entityKey")
                }
            }
            .map { artifact ->
                FlowUiEntityFieldSnapshot(
                    artifactId = artifact.id,
                    entitySemanticKey = artifact.semanticKey.substringBeforeLast('.', ""),
                    name = artifact.displayName,
                    type = artifact.summary?.substringBefore(" attribute of"),
                    sourceLocator = artifact.sourceLocator,
                )
            }
            .distinctBy { "${it.entitySemanticKey}.${it.name}" }
            .sortedWith(compareBy(FlowUiEntityFieldSnapshot::entitySemanticKey, FlowUiEntityFieldSnapshot::name))
            .take(MAX_ENTITY_FIELDS)
            .toList()
        val queryParameters = document.elements.asSequence()
            .filter { it.localTag == "query" && !it.directText.isNullOrBlank() }
            .flatMap { query ->
                JPQL_PARAMETER.findAll(query.directText.orEmpty()).map { match ->
                    FlowUiQueryParameterSnapshot(
                        queryElementKey = query.key,
                        name = match.groupValues[1],
                    )
                }
            }
            .distinct()
            .toList()
        return FlowUiDataWorkspaceSnapshot(
            containers = containers,
            bindings = bindings,
            entityFields = fields,
            queryParameters = queryParameters,
        )
    }

    private fun contextRank(kind: ArtifactKind): Int = when (kind) {
        ArtifactKind.VIEW_DESCRIPTOR -> 0
        ArtifactKind.VIEW_CONTROLLER -> 1
        ArtifactKind.VIEW_HANDLER -> 2
        ArtifactKind.DATA_CONTAINER, ArtifactKind.DATA_LOADER, ArtifactKind.FETCH_PLAN,
        ArtifactKind.JPQL_QUERY, ArtifactKind.QUERY_PARAMETER -> 3
        ArtifactKind.UI_COMPONENT, ArtifactKind.UI_ACTION -> 4
        ArtifactKind.ENTITY, ArtifactKind.ENTITY_ATTRIBUTE -> 5
        ArtifactKind.SERVICE, ArtifactKind.SERVICE_METHOD -> 6
        ArtifactKind.RESOURCE_ROLE, ArtifactKind.ROW_ROLE, ArtifactKind.SECURITY_POLICY -> 7
        ArtifactKind.WORKFLOW_PROCESS, ArtifactKind.WORKFLOW_STATE -> 8
        else -> 9
    }

    private fun rejectedDescriptor(code: String, message: String, path: String): LoadedDescriptor =
        LoadedDescriptor(
            document = null,
            issues = listOf(WorkspaceChangeIssue(code, message, path)),
        )

    private fun rejectedPreview(id: String, issues: List<WorkspaceChangeIssue>) =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = id,
            label = "FlowUI property change rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    companion object {
        private const val MAX_DESCRIPTOR_BYTES = 2L * 1024 * 1024
        private const val MAX_CONTEXT_ARTIFACTS = 250
        private const val MAX_CONTEXT_RELATIONSHIPS = 1_000
        private const val MAX_ENTITY_FIELDS = 5_000
        private const val CONTEXT_DEPTH = 2
        private val DATA_CONTAINER_TAGS = setOf("instance", "collection", "keyValueCollection", "keyValueInstance")
        private val JPQL_PARAMETER = Regex(""":([A-Za-z_][A-Za-z0-9_]*)""")

        fun getInstance(project: Project): FlowUiWorkspaceService =
            project.getService(FlowUiWorkspaceService::class.java)
    }
}

data class FlowUiWorkspaceRequest(
    val sourceLocator: SourceLocator,
)

data class FlowUiPropertyChangeRequest(
    val sourceLocator: SourceLocator,
    val elementKey: String,
    val propertyName: String,
    val value: String,
)

data class FlowUiPropertyApplyRequest(
    val change: FlowUiPropertyChangeRequest,
    val expectedPlanDigest: String,
)

data class FlowUiDirectTextChangeRequest(
    val sourceLocator: SourceLocator,
    val elementKey: String,
    val value: String,
)

data class FlowUiDirectTextApplyRequest(
    val change: FlowUiDirectTextChangeRequest,
    val expectedPlanDigest: String,
)

enum class FlowUiStructureOperation {
    INSERT_CHILD,
    DELETE,
    MOVE_UP,
    MOVE_DOWN,
}

data class FlowUiStructureChangeRequest(
    val sourceLocator: SourceLocator,
    val operation: FlowUiStructureOperation,
    val elementKey: String? = null,
    val parentKey: String? = null,
    val tagName: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class FlowUiStructureApplyRequest(
    val change: FlowUiStructureChangeRequest,
    val expectedPlanDigest: String,
)

data class FlowUiWorkspaceResponse(
    val accepted: Boolean,
    val document: FlowUiDescriptorSnapshot?,
    val contextArtifacts: List<ArtifactSnapshot>,
    val contextRelationships: List<ArtifactRelationship>,
    val issues: List<WorkspaceChangeIssue>,
    val dataModel: FlowUiDataWorkspaceSnapshot? = null,
    val controllerModel: FlowUiControllerWorkspaceSnapshot? = null,
)

data class FlowUiDataContainerSnapshot(
    val elementKey: String,
    val id: String,
    val kind: String,
    val entityClass: String?,
    val property: String?,
    val fetchPlan: String?,
    val fetchPlanElementKey: String?,
    val loaderElementKey: String?,
    val loaderId: String?,
    val queryElementKey: String?,
    val query: String?,
)

data class FlowUiComponentBindingSnapshot(
    val elementKey: String,
    val componentId: String?,
    val componentTag: String,
    val containerId: String,
    val property: String?,
)

data class FlowUiEntityFieldSnapshot(
    val artifactId: String,
    val entitySemanticKey: String,
    val name: String,
    val type: String?,
    val sourceLocator: SourceLocator,
)

data class FlowUiQueryParameterSnapshot(
    val queryElementKey: String,
    val name: String,
)

data class FlowUiDataWorkspaceSnapshot(
    val containers: List<FlowUiDataContainerSnapshot>,
    val bindings: List<FlowUiComponentBindingSnapshot>,
    val entityFields: List<FlowUiEntityFieldSnapshot>,
    val queryParameters: List<FlowUiQueryParameterSnapshot>,
)

private data class LoadedDescriptor(
    val document: FlowUiDescriptorSnapshot?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class ContextSelection(
    val artifacts: List<ArtifactSnapshot>,
    val relationships: List<ArtifactRelationship>,
)

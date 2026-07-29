package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.flowui.FlowUiDescriptorParser
import org.jmixworkbench.discovery.flowui.FlowUiDescriptorSnapshot
import org.jmixworkbench.discovery.flowui.FlowUiElementSnapshot
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Propagates already-persisted entity attributes into their presentation and
 * loading surfaces. The service intentionally re-discovers every target for
 * preview and apply: target ids are revision-bound and cannot be replayed
 * against a different project state.
 */
@Service(Service.Level.PROJECT)
class EntityAttributePropagationService(
    private val project: Project,
) {
    fun inspect(request: EntityAttributePropagationInspectionRequest): EntityAttributePropagationInspectionResponse {
        val discovery = discover(request)
        return EntityAttributePropagationInspectionResponse(
            accepted = discovery.issues.none { it.code.startsWith("JVW-PROPAGATION-REQUEST") },
            entityQualifiedName = request.entityQualifiedName,
            attributes = discovery.attributes.map { it.name },
            targets = discovery.candidates.map(PropagationCandidate::snapshot),
            issues = discovery.issues,
        )
    }

    fun preview(request: EntityAttributePropagationChangeRequest): WorkspaceChangePreviewResponse {
        val proposal = propose(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues)
    }

    fun prepareApply(request: EntityAttributePropagationApplyRequest): PreparedWorkspaceChange {
        val proposal = propose(request.change)
        val changeSet = proposal.changeSet
            ?: return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "entity-attribute-propagation:rejected",
                    label = "Entity attribute propagation rejected",
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

    private fun propose(request: EntityAttributePropagationChangeRequest): PropagationProposal {
        val discovery = discover(request.inspection)
        if (discovery.issues.any { it.code.startsWith("JVW-PROPAGATION-REQUEST") }) {
            return PropagationProposal(null, discovery.issues)
        }
        val selectedIds = request.targetIds.toSet()
        if (selectedIds.isEmpty()) {
            return rejected(
                "JVW-PROPAGATION-TARGETS-EMPTY",
                "Select at least one reviewed propagation target.",
            )
        }
        if (selectedIds.size != request.targetIds.size) {
            return rejected(
                "JVW-PROPAGATION-TARGETS-DUPLICATE",
                "A propagation target can be selected only once.",
            )
        }
        val candidatesById = discovery.candidates.associateBy { it.snapshot.id }
        val stale = selectedIds - candidatesById.keys
        if (stale.isNotEmpty()) {
            return rejected(
                "JVW-PROPAGATION-TARGET-STALE",
                "One or more selected targets no longer match the current application graph. Inspect impact again.",
            )
        }
        val selected = selectedIds.mapNotNull(candidatesById::get)
        selected.firstOrNull { !it.snapshot.supported }?.let { target ->
            return rejected(
                "JVW-PROPAGATION-TARGET-UNSUPPORTED",
                "${target.snapshot.label} is impact evidence only and cannot be changed automatically.",
                target.snapshot.relativePath,
            )
        }

        val files = mutableListOf<WorkspaceFileChange>()
        selected.groupBy { it.snapshot.relativePath }.forEach { (relativePath, targets) ->
            val securityTargets = targets.filter {
                it.snapshot.kind == EntityAttributePropagationTargetKind.RESOURCE_ROLE
            }
            if (securityTargets.isNotEmpty()) {
                val securityTarget = securityTargets.singleOrNull()
                    ?: return rejected(
                        "JVW-PROPAGATION-SECURITY-DUPLICATE",
                        "A resource role can occur only once in an attribute propagation plan.",
                        relativePath,
                    )
                if (targets.size != 1) {
                    return rejected(
                        "JVW-PROPAGATION-SECURITY-COLLISION",
                        "A resource role propagation target cannot share its source with another target kind.",
                        relativePath,
                    )
                }
                val securityRequest = securityTarget.securityRequest
                    ?: return rejected(
                        "JVW-PROPAGATION-SECURITY-STALE",
                        "The reviewed resource role policy selection is no longer available.",
                        relativePath,
                    )
                val securityProposal = SecurityRoleChangeService.getInstance(project)
                    .proposeAttributePropagation(securityRequest)
                val securityChange = securityProposal.changeSet?.files?.singleOrNull()
                    ?: return PropagationProposal(null, securityProposal.issues)
                files += securityChange
                return@forEach
            }
            val create = targets.singleOrNull()?.messageCreateContent
            if (create != null) {
                files += WorkspaceFileChange(
                    relativePath = relativePath,
                    mode = WorkspaceFileChangeMode.CREATE,
                    baseRevisionFingerprint = null,
                    createContent = create,
                )
                return@forEach
            }
            val loaded = load(relativePath)
                ?: return rejected(
                    "JVW-PROPAGATION-SOURCE-MISSING",
                    "A selected propagation source no longer exists.",
                    relativePath,
                )
            if (targets.any { it.revisionFingerprint != loaded.fingerprint }) {
                return rejected(
                    "JVW-PROPAGATION-SOURCE-STALE",
                    "A selected propagation source changed after impact inspection. Inspect impact again.",
                    relativePath,
                )
            }
            val edits = targets.flatMap { candidate ->
                when (candidate.snapshot.kind) {
                    EntityAttributePropagationTargetKind.VIEW_FORM,
                    EntityAttributePropagationTargetKind.VIEW_GRID,
                    EntityAttributePropagationTargetKind.INLINE_FETCH_PLAN,
                    -> listOfNotNull(flowInsertEdit(loaded.content, candidate))
                    EntityAttributePropagationTargetKind.SHARED_FETCH_PLAN ->
                        listOfNotNull(sharedFetchInsertEdit(loaded.content, candidate))
                    EntityAttributePropagationTargetKind.MESSAGE_BUNDLE ->
                        listOfNotNull(messageAppendEdit(loaded.content, candidate))
                    EntityAttributePropagationTargetKind.RESOURCE_ROLE -> emptyList()
                }
            }
            if (edits.size != targets.size) {
                return rejected(
                    "JVW-PROPAGATION-PLACEMENT-STALE",
                    "A selected insertion point can no longer be resolved safely.",
                    relativePath,
                )
            }
            files += WorkspaceFileChange(
                relativePath = relativePath,
                mode = WorkspaceFileChangeMode.MODIFY,
                baseRevisionFingerprint = loaded.fingerprint,
                edits = edits,
            )
        }
        if (files.isEmpty()) {
            return rejected(
                "JVW-PROPAGATION-NO-CHANGE",
                "The selected targets no longer require propagation.",
            )
        }
        val identity = buildString {
            append(request.inspection.entityQualifiedName)
            discovery.attributes.sortedBy { it.name }.forEach { append('\u0000').append(it.name) }
            selected.sortedBy { it.snapshot.id }.forEach { candidate ->
                append('\u0000').append(candidate.snapshot.id)
                    .append('\u0000').append(candidate.revisionFingerprint)
            }
        }
        return PropagationProposal(
            WorkspaceChangeSet(
                id = "entity-attribute-propagation:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Propagate ${discovery.attributes.size} ${request.inspection.className} attribute" +
                    if (discovery.attributes.size == 1) "" else "s",
                files = files,
            ),
            discovery.issues,
        )
    }

    private fun discover(request: EntityAttributePropagationInspectionRequest): PropagationDiscovery {
        val requestedNames = request.attributeNames.map(String::trim)
        if (
            request.entityQualifiedName.isBlank() ||
            request.entityName.isBlank() ||
            request.className.isBlank() ||
            requestedNames.isEmpty()
        ) {
            return requestRejected("Entity identity and at least one attribute are required.")
        }
        if (
            requestedNames.size > MAX_ATTRIBUTES ||
            requestedNames.any { !JAVA_IDENTIFIER.matches(it) } ||
            requestedNames.distinct().size != requestedNames.size
        ) {
            return requestRejected("Attribute names must be unique Java identifiers and the reviewed limit is $MAX_ATTRIBUTES.")
        }
        val schema = SchemaWorkspaceService.getInstance(project).load()
        val entity = schema.entities.singleOrNull { it.qualifiedName == request.entityQualifiedName }
            ?: return requestRejected(
                "The entity is not present in the current indexed schema. Apply the entity change and refresh before propagation.",
            )
        if (entity.entityName != request.entityName || entity.className != request.className) {
            return requestRejected("The requested entity identity no longer matches the indexed schema.")
        }
        val attributesByName = entity.attributes.associateBy { it.name }
        val missing = requestedNames.filterNot(attributesByName::containsKey)
        if (missing.isNotEmpty()) {
            return requestRejected(
                "Attributes are not present in the indexed entity: ${missing.sorted().joinToString()}.",
            )
        }
        val attributes = requestedNames.map(attributesByName::getValue)
        val graph = ApplicationGraphService.getInstance(project).graph()
        val issues = mutableListOf<WorkspaceChangeIssue>()
        val candidates = mutableListOf<PropagationCandidate>()

        graph.artifacts.asSequence()
            .filter { it.kind == ArtifactKind.VIEW_DESCRIPTOR }
            .distinctBy { it.sourceLocator.relativePath }
            .sortedBy { it.sourceLocator.relativePath }
            .forEach { artifact ->
                val loaded = load(artifact.sourceLocator.relativePath) ?: return@forEach
                val parsed = FlowUiDescriptorParser.parse(
                    artifact.sourceLocator.relativePath,
                    loaded.content,
                    loaded.fingerprint,
                )
                val document = parsed.document
                if (document == null) {
                    issues += WorkspaceChangeIssue(
                        "JVW-PROPAGATION-VIEW-SKIPPED",
                        "A related FlowUI descriptor is malformed and was excluded from automatic propagation.",
                        artifact.sourceLocator.relativePath,
                    )
                    return@forEach
                }
                candidates += flowCandidates(document, loaded.fingerprint, entity, attributes)
            }

        graph.artifacts.asSequence()
            .filter { it.kind == ArtifactKind.FETCH_PLAN }
            .distinctBy { it.sourceLocator.relativePath }
            .sortedBy { it.sourceLocator.relativePath }
            .forEach { artifact ->
                val loaded = load(artifact.sourceLocator.relativePath) ?: return@forEach
                if (!secureXml(loaded.content)) {
                    issues += WorkspaceChangeIssue(
                        "JVW-PROPAGATION-FETCH-PLAN-SKIPPED",
                        "A shared fetch-plan source is malformed and was excluded from automatic propagation.",
                        artifact.sourceLocator.relativePath,
                    )
                    return@forEach
                }
                candidates += sharedFetchCandidates(
                    artifact.sourceLocator.relativePath,
                    loaded,
                    entity,
                    attributes,
                )
            }

        val messageDiscovery = messageCandidates(entity, attributes, graph, schema.stores)
        val messageTargets = messageDiscovery.candidates
        candidates += messageTargets
        issues += messageDiscovery.issues
        val messagePath = messageTargets.singleOrNull()?.snapshot?.relativePath
        if (messagePath != null) {
            val directory = messagePath.substringBeforeLast('/')
            val localizedBundles = graph.artifacts.filter {
                it.kind == ArtifactKind.MESSAGE_BUNDLE &&
                    it.sourceLocator.relativePath.substringBeforeLast('/') == directory &&
                    it.sourceLocator.relativePath.substringAfterLast('/').startsWith("messages_")
            }
            if (localizedBundles.isNotEmpty()) {
                issues += WorkspaceChangeIssue(
                    "JVW-PROPAGATION-LOCALE-REVIEW",
                    "Only the default message bundle is generated. ${localizedBundles.size} locale-specific " +
                        "bundle${if (localizedBundles.size == 1) "" else "s"} require human translation review.",
                    messagePath,
                )
            }
        }
        candidates += securityCandidates(entity, attributes, graph.artifacts)
        return PropagationDiscovery(
            attributes = attributes,
            candidates = candidates.distinctBy { it.snapshot.id }.sortedWith(
                compareBy<PropagationCandidate> { it.snapshot.kind.ordinal }
                    .thenBy { it.snapshot.relativePath }
                    .thenBy { it.snapshot.label },
            ),
            issues = issues,
        )
    }

    private fun flowCandidates(
        document: FlowUiDescriptorSnapshot,
        fingerprint: String,
        entity: SchemaEntitySnapshot,
        attributes: List<SchemaEntityAttributeSnapshot>,
    ): List<PropagationCandidate> {
        val elementsByKey = document.elements.associateBy { it.key }
        val matchingContainers = document.elements.filter { element ->
            element.localTag in DATA_CONTAINER_TAGS &&
                entityMatches(element.attribute("class"), entity)
        }
        val candidates = mutableListOf<PropagationCandidate>()
        matchingContainers.forEach { container ->
            val containerId = container.id ?: return@forEach
            document.elements.filter { element ->
                element.localTag == "dataGrid" &&
                    (element.attribute("dataContainer") == containerId ||
                        element.attribute("itemsContainer") == containerId)
            }.forEach { grid ->
                val existing = descendantProperties(grid, elementsByKey)
                val missing = attributes.filterNot { it.name in existing }
                if (missing.isNotEmpty()) {
                    candidates += flowCandidate(
                        document,
                        fingerprint,
                        grid,
                        EntityAttributePropagationTargetKind.VIEW_GRID,
                        "Grid ${grid.id ?: document.viewId}",
                        "Add ${missing.size} column${if (missing.size == 1) "" else "s"} bound to $containerId.",
                        missing,
                    )
                }
            }
            document.elements.filter { element ->
                element.localTag in FORM_CONTAINER_TAGS &&
                    element.attribute("dataContainer") == containerId
            }.forEach { form ->
                val existing = descendantProperties(form, elementsByKey)
                val missing = attributes.filterNot { it.name in existing }
                if (missing.isNotEmpty()) {
                    candidates += flowCandidate(
                        document,
                        fingerprint,
                        form,
                        EntityAttributePropagationTargetKind.VIEW_FORM,
                        "Form ${form.id ?: document.viewId}",
                        "Add ${missing.size} bound field${if (missing.size == 1) "" else "s"} for $containerId.",
                        missing,
                    )
                }
            }
            container.childKeys.mapNotNull(elementsByKey::get)
                .filter { it.localTag == "fetchPlan" }
                .forEach { fetchPlan ->
                    val existing = descendantFetchProperties(fetchPlan, elementsByKey)
                    val extendsBase = fetchPlan.attribute("extends")
                        ?.split(',')
                        ?.map(String::trim)
                        ?.contains("_base") == true
                    val missing = attributes.filter { attribute ->
                        attribute.association || (!extendsBase && attribute.name !in existing)
                    }.filterNot { it.name in existing }
                    if (missing.isNotEmpty()) {
                        candidates += flowCandidate(
                            document,
                            fingerprint,
                            fetchPlan,
                            EntityAttributePropagationTargetKind.INLINE_FETCH_PLAN,
                            "Inline fetch plan for $containerId",
                            "Load ${missing.size} attribute${if (missing.size == 1) "" else "s"} explicitly.",
                            missing,
                            recommended = missing.any(SchemaEntityAttributeSnapshot::association),
                        )
                    }
                }
        }
        return candidates
    }

    private fun flowCandidate(
        document: FlowUiDescriptorSnapshot,
        fingerprint: String,
        parent: FlowUiElementSnapshot,
        kind: EntityAttributePropagationTargetKind,
        label: String,
        detail: String,
        missing: List<SchemaEntityAttributeSnapshot>,
        recommended: Boolean = true,
    ): PropagationCandidate {
        val id = targetId(kind, document.relativePath, parent.key, missing)
        return PropagationCandidate(
            snapshot = EntityAttributePropagationTargetSnapshot(
                id = id,
                kind = kind,
                label = label,
                relativePath = document.relativePath,
                detail = detail,
                missingAttributes = missing.map { it.name },
                recommended = recommended,
                supported = true,
                securityExpanding = false,
            ),
            revisionFingerprint = fingerprint,
            parentKey = parent.key,
            parentStart = parent.sourceStart,
            parentEndTagStart = parent.endTagStart,
            parentSelfClosing = parent.selfClosing,
            startTagEnd = parent.startTagEnd,
            parentTag = parent.tagName,
            attributes = missing,
        )
    }

    private fun sharedFetchCandidates(
        relativePath: String,
        loaded: LoadedSource,
        entity: SchemaEntitySnapshot,
        attributes: List<SchemaEntityAttributeSnapshot>,
    ): List<PropagationCandidate> =
        FETCH_PLAN_START.findAll(loaded.content).mapNotNull { match ->
            val attrs = xmlAttributes(match.value)
            if (!entityMatches(attrs["class"] ?: attrs["entity"], entity)) return@mapNotNull null
            val range = xmlElementRange(loaded.content, match.range.first, "fetchPlan") ?: return@mapNotNull null
            val body = loaded.content.substring(range.contentStart, range.contentEnd)
            val existing = PROPERTY_START.findAll(body)
                .mapNotNull { xmlAttributes(it.value)["name"] }
                .toSet()
            val extendsBase = attrs["extends"]
                ?.split(',')
                ?.map(String::trim)
                ?.contains("_base") == true
            val missing = attributes.filter { attribute ->
                attribute.association || (!extendsBase && attribute.name !in existing)
            }.filterNot { it.name in existing }
            if (missing.isEmpty()) return@mapNotNull null
            val name = attrs["name"].orEmpty().ifBlank { entity.className }
            PropagationCandidate(
                snapshot = EntityAttributePropagationTargetSnapshot(
                    id = targetId(
                        EntityAttributePropagationTargetKind.SHARED_FETCH_PLAN,
                        relativePath,
                        "${match.range.first}:$name",
                        missing,
                    ),
                    kind = EntityAttributePropagationTargetKind.SHARED_FETCH_PLAN,
                    label = "Fetch plan $name",
                    relativePath = relativePath,
                    detail = "Add ${missing.size} explicit fetch propert${if (missing.size == 1) "y" else "ies"}.",
                    missingAttributes = missing.map { it.name },
                    recommended = missing.any(SchemaEntityAttributeSnapshot::association),
                    supported = true,
                    securityExpanding = false,
                ),
                revisionFingerprint = loaded.fingerprint,
                parentStart = match.range.first,
                parentEndTagStart = range.contentEnd,
                parentSelfClosing = range.selfClosing,
                startTagEnd = match.range.last + 1,
                parentTag = match.value
                    .removePrefix("<")
                    .takeWhile { !it.isWhitespace() && it != '>' && it != '/' },
                attributes = missing,
            )
        }.toList()

    private fun messageCandidates(
        entity: SchemaEntitySnapshot,
        attributes: List<SchemaEntityAttributeSnapshot>,
        graph: ApplicationGraphResponse,
        stores: List<SchemaDataStoreSnapshot>,
    ): MessageCandidateDiscovery {
        val packagePath = entity.qualifiedName.substringBeforeLast('.').replace('.', '/')
        val existingArtifacts = graph.artifacts.filter {
            it.kind == ArtifactKind.MESSAGE_BUNDLE &&
                it.owner.moduleId == entity.moduleId &&
                it.sourceLocator.relativePath.endsWith("$packagePath/messages.properties")
        }
        if (existingArtifacts.size > 1) {
            return MessageCandidateDiscovery(
                emptyList(),
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-PROPAGATION-MESSAGE-AMBIGUOUS",
                        "Several default entity message bundles match ${entity.qualifiedName}. Select the bundle in source instead of guessing.",
                    ),
                ),
            )
        }
        val conventionalPath = entity.sourceLocator.relativePath
            .replace(Regex("""(^|/)src/main/(?:java|kotlin)/""")) { match ->
                "${match.groupValues[1]}src/main/resources/"
            }
            .takeIf { it != entity.sourceLocator.relativePath }
            ?.substringBeforeLast('/')
            ?.plus("/messages.properties")
        val resourceDestinations = ProjectSourceDestinationService.getInstance(project)
            .productionResources(graph)
            .filter { it.moduleId == entity.moduleId }
        val configurationPath = stores.firstOrNull {
            it.moduleId == entity.moduleId && it.name == entity.storeName
        }?.configurationLocator?.relativePath
        val configuredResourceRoot = configurationPath?.let { path ->
            resourceDestinations
                .filter { destination ->
                    path == destination.sourceRoot ||
                        path.startsWith("${destination.sourceRoot.trimEnd('/')}/")
                }
                .maxByOrNull { it.sourceRoot.length }
        }
        val expectedPath = existingArtifacts.singleOrNull()?.sourceLocator?.relativePath
            ?: conventionalPath
            ?: configuredResourceRoot?.let {
                "${it.sourceRoot.trimEnd('/')}/$packagePath/messages.properties"
            }
            ?: resourceDestinations.singleOrNull()?.let {
                "${it.sourceRoot.trimEnd('/')}/$packagePath/messages.properties"
            }
            ?: return MessageCandidateDiscovery(
                emptyList(),
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-PROPAGATION-MESSAGE-DESTINATION-AMBIGUOUS",
                        "No unique production resource root owns the entity message bundle. View and fetch-plan propagation remain available.",
                    ),
                ),
            )
        val existingArtifact = existingArtifacts.singleOrNull()
        val existing = existingArtifact?.let { load(expectedPath) }
        val existingKeys = existing?.content?.lineSequence()
            ?.mapNotNull { line ->
                line.takeIf { it.isNotBlank() && !it.trimStart().startsWith("#") }
                    ?.substringBefore('=')
                    ?.substringBefore(':')
                    ?.trim()
            }
            ?.toSet()
            .orEmpty()
        val missing = attributes.filter { "${entity.className}.${it.name}" !in existingKeys }
        if (missing.isEmpty()) return MessageCandidateDiscovery(emptyList(), emptyList())
        val lines = missing.joinToString("\n") { attribute ->
            "${entity.className}.${attribute.name}=${humanCaption(attribute.name)}"
        } + "\n"
        val fingerprint = existing?.fingerprint.orEmpty()
        val id = targetId(
            EntityAttributePropagationTargetKind.MESSAGE_BUNDLE,
            expectedPath,
            entity.className,
            missing,
        )
        return MessageCandidateDiscovery(
            candidates = listOf(
                PropagationCandidate(
                    snapshot = EntityAttributePropagationTargetSnapshot(
                        id = id,
                        kind = EntityAttributePropagationTargetKind.MESSAGE_BUNDLE,
                        label = if (existing == null) "Create entity message bundle" else "Entity message bundle",
                        relativePath = expectedPath,
                        detail = "Add ${missing.size} default-locale caption key${if (missing.size == 1) "" else "s"}.",
                        missingAttributes = missing.map { it.name },
                        recommended = true,
                        supported = true,
                        securityExpanding = false,
                    ),
                    revisionFingerprint = fingerprint,
                    attributes = missing,
                    messageLines = lines,
                    messageCreateContent = if (existing == null) lines else null,
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun securityCandidates(
        entity: SchemaEntitySnapshot,
        attributes: List<SchemaEntityAttributeSnapshot>,
        artifacts: List<org.jmixworkbench.discovery.model.ArtifactSnapshot>,
    ): List<PropagationCandidate> =
        artifacts.asSequence()
            .filter { it.kind == ArtifactKind.RESOURCE_ROLE }
            .distinctBy { it.sourceLocator.relativePath }
            .mapNotNull { role ->
                val loaded = load(role.sourceLocator.relativePath) ?: return@mapNotNull null
                val inspection = SecurityRoleChangeService.getInstance(project).inspectPolicies(
                    SecurityRolePolicyInspectionRequest(
                        roleLocator = role.sourceLocator.copy(revisionFingerprint = loaded.fingerprint),
                        roleClassName = role.displayName.substringAfterLast('.'),
                    ),
                )
                if (!inspection.accepted) return@mapNotNull null
                val matching = inspection.policies.filter { policy ->
                    policy.editable &&
                        policy.type == SecurityRolePolicyType.ENTITY_ATTRIBUTE &&
                        policy.policy?.entityClass == entity.qualifiedName &&
                        policy.policy.allowWildcard.not() &&
                        attributes.any { it.name !in policy.policy.attributes }
                }
                if (matching.isEmpty()) return@mapNotNull null
                val missing = attributes.filter { attribute ->
                    matching.any { attribute.name !in requireNotNull(it.policy).attributes }
                }
                val actions = matching.mapNotNull { it.policy?.attributeAction?.name }
                    .distinct()
                    .sorted()
                PropagationCandidate(
                    snapshot = EntityAttributePropagationTargetSnapshot(
                        id = targetId(
                            EntityAttributePropagationTargetKind.RESOURCE_ROLE,
                            role.sourceLocator.relativePath,
                            role.semanticKey,
                            missing,
                        ),
                        kind = EntityAttributePropagationTargetKind.RESOURCE_ROLE,
                        label = "Security role ${role.displayName}",
                        relativePath = role.sourceLocator.relativePath,
                        detail = "Explicitly extend ${matching.size} existing ${actions.joinToString("/")} attribute " +
                            "polic${if (matching.size == 1) "y" else "ies"}. This expands effective privileges.",
                        missingAttributes = missing.map { it.name },
                        recommended = false,
                        supported = true,
                        securityExpanding = true,
                    ),
                    revisionFingerprint = loaded.fingerprint,
                    attributes = missing,
                    securityRequest = SecurityRoleAttributePropagationRequest(
                        roleLocator = role.sourceLocator.copy(revisionFingerprint = loaded.fingerprint),
                        roleClassName = role.displayName.substringAfterLast('.'),
                        entityQualifiedName = entity.qualifiedName,
                        policyLocators = matching.map { it.locator },
                        attributeNames = attributes.map { it.name },
                    ),
                )
            }
            .toList()

    private fun flowInsertEdit(content: String, candidate: PropagationCandidate): WorkspaceTextEdit? {
        val parsed = FlowUiDescriptorParser.parse(
            candidate.snapshot.relativePath,
            content,
            candidate.revisionFingerprint,
        ).document ?: return null
        val parent = parsed.elements.singleOrNull { it.key == candidate.parentKey } ?: return null
        if (parent.sourceStart != candidate.parentStart) {
            return null
        }
        val markups = candidate.attributes.map { attribute ->
            when (candidate.snapshot.kind) {
                EntityAttributePropagationTargetKind.VIEW_GRID ->
                    """<column property="${xmlEscape(attribute.name)}"/>"""
                EntityAttributePropagationTargetKind.VIEW_FORM ->
                    """<${fieldTag(attribute)} property="${xmlEscape(attribute.name)}"/>"""
                EntityAttributePropagationTargetKind.INLINE_FETCH_PLAN ->
                    fetchPropertyMarkup(attribute)
                else -> return null
            }
        }
        if (parent.selfClosing) {
            return expandSelfClosingEdit(
                content = content,
                start = parent.sourceStart,
                startTagEnd = parent.startTagEnd,
                tagName = parent.tagName,
                markups = markups,
            )
        }
        if (parent.endTagStart < parent.startTagEnd) return null
        return insertChildrenEdit(content, parent, markups)
    }

    private fun sharedFetchInsertEdit(content: String, candidate: PropagationCandidate): WorkspaceTextEdit? {
        val start = candidate.parentStart ?: return null
        val endTagStart = candidate.parentEndTagStart ?: return null
        val startTagEnd = candidate.startTagEnd ?: return null
        if (start !in content.indices || startTagEnd > content.length || endTagStart > content.length) return null
        val markups = candidate.attributes.map(::fetchPropertyMarkup)
        if (candidate.parentSelfClosing) {
            return expandSelfClosingEdit(
                content,
                start,
                startTagEnd,
                candidate.parentTag ?: "fetchPlan",
                markups,
            )
        }
        val synthetic = FlowUiElementSnapshot(
            key = "shared-fetch-plan@$start",
            tagName = "fetchPlan",
            localTag = "fetchPlan",
            id = null,
            parentKey = null,
            childKeys = emptyList(),
            sourceStart = start,
            startTagEnd = startTagEnd,
            endTagStart = endTagStart,
            sourceEnd = endTagStart,
            selfClosing = false,
            attributes = emptyList(),
            directText = null,
            directTextStart = null,
            directTextEnd = null,
            directTextCdata = false,
        )
        return insertChildrenEdit(content, synthetic, markups)
    }

    private fun messageAppendEdit(content: String, candidate: PropagationCandidate): WorkspaceTextEdit? {
        val lines = candidate.messageLines ?: return null
        val separator = when {
            content.isEmpty() || content.endsWith("\n") || content.endsWith("\r") -> ""
            else -> newlineOf(content)
        }
        return WorkspaceTextEdit(
            startOffset = content.length,
            endOffset = content.length,
            expectedText = "",
            replacement = separator + lines.replace("\n", newlineOf(content)),
        )
    }

    private fun expandSelfClosingEdit(
        content: String,
        start: Int,
        startTagEnd: Int,
        tagName: String,
        markups: List<String>,
    ): WorkspaceTextEdit? {
        if (tagName.isBlank() || markups.isEmpty()) return null
        val startTag = content.substring(start, startTagEnd)
        val openTag = startTag.replace(Regex("""/\s*>$"""), ">")
        if (openTag == startTag) return null
        val indent = indentationAt(content, start)
        val childIndent = indent + indentUnit(content)
        val newline = newlineOf(content)
        val replacement = buildString {
            append(openTag)
            append(newline)
            markups.forEach { append(childIndent).append(it).append(newline) }
            append(indent).append("</").append(tagName).append('>')
        }
        return WorkspaceTextEdit(start, startTagEnd, startTag, replacement)
    }

    private fun insertChildrenEdit(
        content: String,
        parent: FlowUiElementSnapshot,
        markups: List<String>,
    ): WorkspaceTextEdit? {
        if (markups.isEmpty() || parent.endTagStart !in 0..content.length) return null
        val newline = newlineOf(content)
        val parentIndent = indentationAt(content, parent.sourceStart)
        val childIndent = parent.childKeys.firstOrNull()
            ?.let { key ->
                FlowUiDescriptorParser.parse("temporary-view.xml", content).document
                    ?.elements?.singleOrNull { it.key == key }
            }
            ?.let { indentationAt(content, it.sourceStart) }
            ?.takeIf { it.length > parentIndent.length }
            ?: parentIndent + indentUnit(content)
        val closingLineStart = content.lastIndexOf('\n', parent.endTagStart - 1).let { it + 1 }
        val closingPrefix = content.substring(closingLineStart, parent.endTagStart)
        val block = markups.joinToString(newline) { "$childIndent$it" }
        return if (closingPrefix.all(Char::isWhitespace)) {
            WorkspaceTextEdit(
                startOffset = closingLineStart,
                endOffset = closingLineStart,
                expectedText = "",
                replacement = "$block$newline",
            )
        } else {
            WorkspaceTextEdit(
                startOffset = parent.endTagStart,
                endOffset = parent.endTagStart,
                expectedText = "",
                replacement = "$newline$block$newline$parentIndent",
            )
        }
    }

    private fun descendantProperties(
        parent: FlowUiElementSnapshot,
        elementsByKey: Map<String, FlowUiElementSnapshot>,
    ): Set<String> {
        val result = mutableSetOf<String>()
        val pending = ArrayDeque(parent.childKeys)
        while (pending.isNotEmpty()) {
            val element = elementsByKey[pending.removeFirst()] ?: continue
            element.attribute("property")?.substringBefore('.')?.takeIf(String::isNotBlank)?.let(result::add)
            pending.addAll(element.childKeys)
        }
        return result
    }

    private fun descendantFetchProperties(
        parent: FlowUiElementSnapshot,
        elementsByKey: Map<String, FlowUiElementSnapshot>,
    ): Set<String> {
        val result = mutableSetOf<String>()
        val pending = ArrayDeque(parent.childKeys)
        while (pending.isNotEmpty()) {
            val element = elementsByKey[pending.removeFirst()] ?: continue
            if (element.localTag == "property") {
                element.attribute("name")?.takeIf(String::isNotBlank)?.let(result::add)
            }
            pending.addAll(element.childKeys)
        }
        return result
    }

    private fun load(relativePath: String): LoadedSource? {
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(relativePath) ?: return null
        if (resolved.file.isDirectory) return null
        val content = runCatching { ProjectSourceText.read(resolved.file) }.getOrNull() ?: return null
        return LoadedSource(content, CanonicalDiscoveryJson.sha256(content))
    }

    private fun requestRejected(message: String): PropagationDiscovery =
        PropagationDiscovery(
            emptyList(),
            emptyList(),
            listOf(WorkspaceChangeIssue("JVW-PROPAGATION-REQUEST-INVALID", message)),
        )

    private fun rejected(code: String, message: String, path: String? = null): PropagationProposal =
        PropagationProposal(null, listOf(WorkspaceChangeIssue(code, message, path)))

    private fun rejectedPreview(issues: List<WorkspaceChangeIssue>) =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "entity-attribute-propagation:rejected",
            label = "Entity attribute propagation rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    companion object {
        private const val MAX_ATTRIBUTES = 100
        private val JAVA_IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        private val DATA_CONTAINER_TAGS = setOf("instance", "collection")
        private val FORM_CONTAINER_TAGS = setOf("formLayout", "form")
        private val FETCH_PLAN_START = Regex("""<(?:(?:[A-Za-z_][\w.-]*):)?fetchPlan\b[^>]*>""")
        private val PROPERTY_START = Regex("""<(?:(?:[A-Za-z_][\w.-]*):)?property\b[^>]*>""")
        private val XML_ATTRIBUTE = Regex("""([A-Za-z_][\w.:-]*)\s*=\s*(["'])(.*?)\2""")

        fun getInstance(project: Project): EntityAttributePropagationService =
            project.getService(EntityAttributePropagationService::class.java)

        private fun FlowUiElementSnapshot.attribute(name: String): String? =
            attributes.firstOrNull { it.name == name }?.value

        private fun entityMatches(value: String?, entity: SchemaEntitySnapshot): Boolean =
            value?.trim() in setOf(entity.qualifiedName, entity.entityName, entity.className)

        private fun targetId(
            kind: EntityAttributePropagationTargetKind,
            path: String,
            placement: String,
            attributes: List<SchemaEntityAttributeSnapshot>,
        ): String = CanonicalDiscoveryJson.sha256(
            listOf(
                kind.name,
                path,
                placement,
                attributes.map { it.name }.sorted().joinToString(","),
            ).joinToString("\u0000"),
        ).take(32)

        private fun fieldTag(attribute: SchemaEntityAttributeSnapshot): String = when {
            attribute.association -> "entityPicker"
            attribute.javaType in setOf("Boolean", "boolean", "java.lang.Boolean") -> "checkbox"
            attribute.javaType.endsWith("LocalDate") || attribute.javaType == "java.sql.Date" -> "datePicker"
            attribute.javaType.endsWith("LocalDateTime") ||
                attribute.javaType.endsWith("OffsetDateTime") -> "dateTimePicker"
            attribute.javaType in setOf(
                "Integer",
                "int",
                "Long",
                "long",
                "java.lang.Integer",
                "java.lang.Long",
            ) -> "integerField"
            attribute.javaType.endsWith("BigDecimal") ||
                attribute.javaType in setOf("Double", "double", "java.lang.Double") -> "bigDecimalField"
            else -> "textField"
        }

        private fun fetchPropertyMarkup(attribute: SchemaEntityAttributeSnapshot): String =
            if (attribute.association) {
                """<property name="${xmlEscape(attribute.name)}" fetchPlan="_base"/>"""
            } else {
                """<property name="${xmlEscape(attribute.name)}"/>"""
            }

        private fun humanCaption(name: String): String =
            name.replace(Regex("""([a-z0-9])([A-Z])"""), "$1 $2")
                .replace('_', ' ')
                .replaceFirstChar(Char::uppercase)

        private fun xmlEscape(value: String): String = buildString(value.length) {
            value.forEach { char ->
                append(
                    when (char) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&apos;"
                        else -> char
                    },
                )
            }
        }

        private fun newlineOf(content: String): String = if ("\r\n" in content) "\r\n" else "\n"

        private fun indentationAt(content: String, offset: Int): String {
            val lineStart = content.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { it + 1 }
            return content.substring(lineStart, offset.coerceAtMost(content.length)).takeWhile(Char::isWhitespace)
        }

        private fun indentUnit(content: String): String {
            val indents = content.lineSequence()
                .map { it.takeWhile(Char::isWhitespace) }
                .filter(String::isNotEmpty)
                .map(String::length)
                .filter { it > 0 }
                .toList()
            return " ".repeat(indents.minOrNull()?.coerceIn(2, 8) ?: 4)
        }

        private fun xmlAttributes(tag: String): Map<String, String> =
            XML_ATTRIBUTE.findAll(tag).associate { match ->
                match.groupValues[1].substringAfter(':') to match.groupValues[3]
            }

        private fun secureXml(content: String): Boolean = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isXIncludeAware = false
            factory.setExpandEntityReferences(false)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(InputSource(StringReader(content)))
            true
        }.getOrDefault(false)

        private fun xmlElementRange(content: String, start: Int, localTag: String): XmlElementRange? {
            val openingEnd = content.indexOf('>', start).takeIf { it >= 0 } ?: return null
            if (content.substring(start, openingEnd + 1).matches(Regex("""(?s).*?/\s*>"""))) {
                return XmlElementRange(openingEnd + 1, openingEnd + 1, true)
            }
            val token = Regex("""</?(?:(?:[A-Za-z_][\w.-]*):)?${Regex.escape(localTag)}\b[^>]*>""")
            var depth = 0
            token.findAll(content, start).forEach { match ->
                val text = match.value
                val closing = text.startsWith("</")
                val selfClosing = text.matches(Regex("""(?s).*?/\s*>"""))
                when {
                    closing -> {
                        depth -= 1
                        if (depth == 0) return XmlElementRange(openingEnd + 1, match.range.first, false)
                    }
                    !selfClosing -> depth += 1
                }
            }
            return null
        }
    }
}

data class EntityAttributePropagationInspectionRequest(
    val entityQualifiedName: String,
    val entityName: String,
    val className: String,
    val attributeNames: List<String>,
)

data class EntityAttributePropagationChangeRequest(
    val inspection: EntityAttributePropagationInspectionRequest,
    val targetIds: List<String>,
)

data class EntityAttributePropagationApplyRequest(
    val change: EntityAttributePropagationChangeRequest,
    val expectedPlanDigest: String,
)

data class EntityAttributePropagationInspectionResponse(
    val accepted: Boolean,
    val entityQualifiedName: String,
    val attributes: List<String>,
    val targets: List<EntityAttributePropagationTargetSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

data class EntityAttributePropagationTargetSnapshot(
    val id: String,
    val kind: EntityAttributePropagationTargetKind,
    val label: String,
    val relativePath: String,
    val detail: String,
    val missingAttributes: List<String>,
    val recommended: Boolean,
    val supported: Boolean,
    val securityExpanding: Boolean,
)

enum class EntityAttributePropagationTargetKind {
    VIEW_FORM,
    VIEW_GRID,
    INLINE_FETCH_PLAN,
    SHARED_FETCH_PLAN,
    MESSAGE_BUNDLE,
    RESOURCE_ROLE,
}

private data class PropagationDiscovery(
    val attributes: List<SchemaEntityAttributeSnapshot>,
    val candidates: List<PropagationCandidate>,
    val issues: List<WorkspaceChangeIssue>,
)

private data class PropagationCandidate(
    val snapshot: EntityAttributePropagationTargetSnapshot,
    val revisionFingerprint: String,
    val parentKey: String? = null,
    val parentStart: Int? = null,
    val parentEndTagStart: Int? = null,
    val parentSelfClosing: Boolean = false,
    val startTagEnd: Int? = null,
    val parentTag: String? = null,
    val attributes: List<SchemaEntityAttributeSnapshot>,
    val messageLines: String? = null,
    val messageCreateContent: String? = null,
    val securityRequest: SecurityRoleAttributePropagationRequest? = null,
)

private data class PropagationProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class MessageCandidateDiscovery(
    val candidates: List<PropagationCandidate>,
    val issues: List<WorkspaceChangeIssue>,
)

private data class LoadedSource(
    val content: String,
    val fingerprint: String,
)

private data class XmlElementRange(
    val contentStart: Int,
    val contentEnd: Int,
    val selfClosing: Boolean,
)

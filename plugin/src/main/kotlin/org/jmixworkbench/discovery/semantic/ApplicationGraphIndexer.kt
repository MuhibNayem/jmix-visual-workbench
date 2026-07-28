package org.jmixworkbench.discovery.semantic

import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactOrigin
import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.ArtifactRelationship
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.DiagnosticCategory
import org.jmixworkbench.discovery.model.DiagnosticSeverity
import org.jmixworkbench.discovery.model.DiscoveryDiagnostic
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.discovery.model.SourceLanguage
import org.jmixworkbench.discovery.model.SourceLocator
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Pure, read-only semantic indexing for existing Jmix sources.
 *
 * The indexer accepts already-read source text and has no filesystem, Gradle, process,
 * network, database, VFS, or write APIs. Host adapters remain responsible for producing
 * bounded [GraphSourceFile] values from the imported IntelliJ project model.
 */
class ApplicationGraphIndexer {
    fun index(input: ApplicationGraphIndexInput): ApplicationGraphIndexResult {
        val artifacts = linkedMapOf<String, DetectedArtifact>()
        val pendingLinks = mutableListOf<PendingLink>()
        val diagnostics = mutableListOf<DiscoveryDiagnostic>()
        val acceptedFiles = input.files
            .sortedBy(GraphSourceFile::relativePath)
            .take(input.maxFiles)

        if (input.files.size > input.maxFiles) {
            diagnostics += diagnostic(
                reasonCode = "P2_GRAPH_FILE_LIMIT",
                message = "The application graph stopped at the configured file limit.",
                nextStep = "Narrow the indexed scope or raise the reviewed enterprise indexing limit.",
                category = DiagnosticCategory.INDEX,
                severity = DiagnosticSeverity.WARNING,
            )
        }

        acceptedFiles.forEach { file ->
            if (file.content.toByteArray(Charsets.UTF_8).size > input.maxFileBytes) {
                diagnostics += diagnostic(
                    reasonCode = "P2_GRAPH_FILE_OVERSIZED",
                    message = "An oversized source file was excluded from semantic indexing.",
                    nextStep = "Open the file natively or raise the reviewed per-file limit.",
                    category = DiagnosticCategory.INDEX,
                    severity = DiagnosticSeverity.WARNING,
                    locator = file.locator(),
                )
                return@forEach
            }

            when (file.language) {
                SourceLanguage.JAVA,
                SourceLanguage.KOTLIN,
                -> indexJvm(file, artifacts, pendingLinks, diagnostics)

                SourceLanguage.XML -> indexXml(file, artifacts, pendingLinks, diagnostics)
                SourceLanguage.PROPERTIES -> indexProperties(file, artifacts)
                else -> Unit
            }

            if (artifacts.size >= input.maxArtifacts) {
                diagnostics += diagnostic(
                    reasonCode = "P2_GRAPH_ARTIFACT_LIMIT",
                    message = "The application graph stopped at the configured artifact limit.",
                    nextStep = "Filter the inventory or raise the reviewed enterprise artifact limit.",
                    category = DiagnosticCategory.INDEX,
                    severity = DiagnosticSeverity.WARNING,
                )
                return@forEach
            }
        }

        addImplicitSourceRelationships(acceptedFiles, artifacts, pendingLinks)
        val relationships = resolveLinks(artifacts, pendingLinks)
        val relationshipDiagnostics = relationships.mapNotNull(ArtifactRelationship::diagnostic)

        return ApplicationGraphIndexResult(
            artifacts = artifacts.values.map(DetectedArtifact::snapshot).sortedBy(ArtifactSnapshot::id),
            relationships = relationships.sortedWith(
                compareBy(
                    ArtifactRelationship::sourceArtifactId,
                    { it.targetArtifactId.orEmpty() },
                    { it.type.name },
                ),
            ),
            diagnostics = (diagnostics + relationshipDiagnostics).distinctBy(DiscoveryDiagnostic::id).sortedBy(
                DiscoveryDiagnostic::id,
            ),
        )
    }

    private fun indexJvm(
        file: GraphSourceFile,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val packageName = PACKAGE.find(file.content)?.groupValues?.get(1).orEmpty()
        val typeMatch = TYPE.find(file.content) ?: return
        val typeName = typeMatch.groupValues[2]
        val semanticKey = if (packageName.isBlank()) typeName else "$packageName.$typeName"
        val primaryKind = jvmKind(file.content, typeMatch.groupValues[1], typeName)
        val aliases = linkedSetOf(semanticKey, typeName)
        VIEW_CONTROLLER_ID.find(file.content)?.groupValues?.get(1)?.let(aliases::add)
        ROLE_CODE.find(file.content)?.groupValues?.get(2)?.let(aliases::add)
        val primary = addArtifact(
            artifacts = artifacts,
            file = file,
            kind = primaryKind,
            semanticKey = semanticKey,
            displayName = typeName,
            summary = jvmSummary(primaryKind, file),
            symbol = semanticKey,
            aliases = aliases,
            token = typeName,
        )

        VIEW_DESCRIPTOR.find(file.content)?.groupValues?.get(1)?.let { descriptor ->
            links += primary.link(
                target = descriptor.substringBeforeLast('.'),
                type = RelationshipType.CONTROLS,
                expectedKinds = setOf(ArtifactKind.VIEW_DESCRIPTOR),
                locator = file.locator(descriptor, semanticKey),
            )
        }

        val mappedMethods = KOTLIN_MAPPED_METHOD.findAll(file.content).map { match ->
            MappedMethod(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        } + JAVA_MAPPED_METHOD.findAll(file.content).map { match ->
            MappedMethod(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        mappedMethods.forEach { method ->
            val mapping = mappingVerb(method.annotation)
            val path = method.path.ifBlank { "/" }
            val methodName = method.name
            val endpoint = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.REST_ENDPOINT,
                semanticKey = "$semanticKey#$methodName:$mapping:$path",
                displayName = "$mapping $path",
                summary = "REST endpoint declared by $typeName.$methodName",
                symbol = "$semanticKey#$methodName",
                aliases = setOf("$semanticKey#$methodName", "$mapping $path", path),
                token = methodName,
            )
            links += primary.link(endpoint, RelationshipType.EXPOSES_ENDPOINT, file.locator(methodName, semanticKey))
        }

        EVENT_METHOD.findAll(file.content).forEach { match ->
            val methodName = match.groupValues[1]
            val listener = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.EVENT_LISTENER,
                semanticKey = "$semanticKey#$methodName",
                displayName = methodName,
                summary = "Application event listener declared by $typeName",
                symbol = "$semanticKey#$methodName",
                aliases = setOf("$semanticKey#$methodName", methodName),
                token = methodName,
            )
            links += primary.link(listener, RelationshipType.DECLARES, file.locator(methodName, semanticKey))
        }

        SCHEDULED_METHOD.findAll(file.content).forEach { match ->
            val methodName = match.groupValues[1]
            val job = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.SCHEDULED_JOB,
                semanticKey = "$semanticKey#$methodName",
                displayName = methodName,
                summary = "Scheduled job declared by $typeName",
                symbol = "$semanticKey#$methodName",
                aliases = setOf("$semanticKey#$methodName", methodName),
                token = methodName,
            )
            links += primary.link(job, RelationshipType.SCHEDULED_BY, file.locator(methodName, semanticKey))
        }

        ROLE_REFERENCES.findAll(file.content).forEach { match ->
            match.groupValues[2].split(',').map(String::trim).forEach { rawRole ->
                val role = rawRole.trim('"', '\'', ' ', '{', '}')
                if (role.isNotBlank()) {
                    links += primary.link(
                        target = role,
                        type = RelationshipType.SECURED_BY,
                        expectedKinds = setOf(ArtifactKind.RESOURCE_ROLE, ArtifactKind.ROW_ROLE),
                        locator = file.locator(role, semanticKey),
                    )
                }
            }
        }

        if (UNSAFE_MONEY_FIELD.containsMatchIn(file.content)) {
            val match = UNSAFE_MONEY_FIELD.find(file.content)!!
            diagnostics += diagnostic(
                reasonCode = "P2_UNSAFE_FLOATING_POINT_MONEY",
                message = "A money-like field uses Float or Double and may produce rounding errors.",
                nextStep = "Review the field and use BigDecimal with explicit scale and rounding where it represents money.",
                category = DiagnosticCategory.DATA_QUALITY,
                severity = DiagnosticSeverity.WARNING,
                locator = file.locator(match.value, semanticKey),
            )
        }

        if (primaryKind == ArtifactKind.SERVICE &&
            SAVE_CALL.containsMatchIn(file.content) &&
            !TRANSACTIONAL.containsMatchIn(file.content)
        ) {
            diagnostics += diagnostic(
                reasonCode = "P2_MISSING_TRANSACTION_BOUNDARY",
                message = "A service performs persistence work without a visible transaction boundary.",
                nextStep = "Confirm that a called service owns the transaction or add an explicit server-side boundary.",
                category = DiagnosticCategory.TRANSACTION,
                severity = DiagnosticSeverity.WARNING,
                locator = file.locator(".save(", semanticKey),
            )
        }

        if (primaryKind == ArtifactKind.VIEW_CONTROLLER && UI_WORKFLOW_TRANSITION.containsMatchIn(file.content)) {
            val match = UI_WORKFLOW_TRANSITION.find(file.content)!!
            diagnostics += diagnostic(
                reasonCode = "P2_WORKFLOW_TRANSITION_IN_UI",
                message = "A workflow or process-state transition appears to occur directly in UI controller code.",
                nextStep = "Move the transition behind a validated transactional workflow service and keep the view as an invoker.",
                category = DiagnosticCategory.WORKFLOW,
                severity = DiagnosticSeverity.WARNING,
                locator = file.locator(match.value, semanticKey),
            )
        }
    }

    private fun indexXml(
        file: GraphSourceFile,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val document = parseXml(file.content)
        if (document == null) {
            diagnostics += diagnostic(
                reasonCode = "P2_XML_MALFORMED",
                message = "Malformed XML was excluded from the application graph.",
                nextStep = "Open the descriptor and correct XML syntax before using visual analysis.",
                category = DiagnosticCategory.SOURCE,
                severity = DiagnosticSeverity.ERROR,
                locator = file.locator(),
            )
            return
        }

        val root = document.documentElement ?: return
        when (root.localTag()) {
            "view" -> indexViewXml(file, root, artifacts, links)
            "menu-config", "menu" -> indexMenuXml(file, root, artifacts, links)
            "fetchPlans", "fetch-plans" -> indexFetchPlans(file, root, artifacts)
            "databaseChangeLog" -> indexLiquibase(file, root, artifacts, links)
            "definitions" -> indexWorkflow(file, root, artifacts, links)
        }
    }

    private fun indexViewXml(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val viewId = root.attr("id").ifBlank { file.fileNameWithoutExtension() }
        val view = addArtifact(
            artifacts,
            file,
            ArtifactKind.VIEW_DESCRIPTOR,
            viewId,
            viewId,
            "FlowUI view descriptor",
            viewId,
            setOf(viewId, file.fileNameWithoutExtension()),
            viewId,
        )

        root.descendants("collection", "instance").forEach { element ->
            val id = element.attr("id").ifBlank { return@forEach }
            val container = addArtifact(
                artifacts,
                file,
                ArtifactKind.DATA_CONTAINER,
                "$viewId#$id",
                id,
                "View data container",
                id,
                setOf("$viewId#$id", id),
                id,
            )
            links += view.link(container, RelationshipType.DECLARES, file.locator(id, id))
            element.attr("class").takeIf(String::isNotBlank)?.let { entity ->
                links += container.link(
                    entity,
                    RelationshipType.BINDS_TO_ENTITY,
                    setOf(ArtifactKind.ENTITY, ArtifactKind.DTO),
                    file.locator(entity, id),
                )
            }
            element.attr("fetchPlan").takeIf(String::isNotBlank)?.let { fetchPlan ->
                links += container.link(
                    fetchPlan,
                    RelationshipType.REFERENCES_FETCH_PLAN,
                    setOf(ArtifactKind.FETCH_PLAN),
                    file.locator(fetchPlan, id),
                )
            }
        }

        root.descendants("loader").forEachIndexed { index, element ->
            val id = element.attr("id").ifBlank { "loader-${index + 1}" }
            val loader = addArtifact(
                artifacts,
                file,
                ArtifactKind.DATA_LOADER,
                "$viewId#$id",
                id,
                "Collection or instance data loader",
                id,
                setOf("$viewId#$id", id),
                id,
            )
            links += view.link(loader, RelationshipType.DECLARES, file.locator(id, id))
            element.attr("fetchPlan").takeIf(String::isNotBlank)?.let { fetchPlan ->
                links += loader.link(
                    fetchPlan,
                    RelationshipType.REFERENCES_FETCH_PLAN,
                    setOf(ArtifactKind.FETCH_PLAN),
                    file.locator(fetchPlan, id),
                )
            }
        }

        root.allElements().forEach { element ->
            val id = element.attr("id")
            if (id.isBlank() || element.localTag() in NON_COMPONENT_VIEW_TAGS) return@forEach
            val component = addArtifact(
                artifacts,
                file,
                if (element.localTag() == "action") ArtifactKind.UI_ACTION else ArtifactKind.UI_COMPONENT,
                "$viewId#$id",
                id,
                element.localTag(),
                id,
                setOf("$viewId#$id", id),
                id,
            )
            links += view.link(component, RelationshipType.DECLARES, file.locator(id, id))
            element.attr("dataContainer").takeIf(String::isNotBlank)?.let { container ->
                links += component.link(
                    "$viewId#$container",
                    RelationshipType.BINDS_TO_ENTITY,
                    setOf(ArtifactKind.DATA_CONTAINER),
                    file.locator(container, id),
                )
            }
        }
    }

    private fun indexMenuXml(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val menuKey = file.relativePath
        val menu = addArtifact(
            artifacts,
            file,
            ArtifactKind.MENU_SOURCE,
            menuKey,
            file.fileNameWithoutExtension(),
            "Jmix menu source",
            menuKey,
            setOf(menuKey),
            root.tagName,
        )
        root.descendants("item").forEachIndexed { index, element ->
            val id = element.attr("id").ifBlank { "item-${index + 1}" }
            val item = addArtifact(
                artifacts,
                file,
                ArtifactKind.MENU_ITEM,
                "$menuKey#$id",
                id,
                "Menu item",
                id,
                setOf("$menuKey#$id", id),
                id,
            )
            links += menu.link(item, RelationshipType.DECLARES, file.locator(id, id))
            element.attr("view").takeIf(String::isNotBlank)?.let { viewId ->
                links += item.link(
                    viewId,
                    RelationshipType.NAVIGATES_TO,
                    setOf(ArtifactKind.VIEW_DESCRIPTOR),
                    file.locator(viewId, id),
                )
            }
        }
    }

    private fun indexFetchPlans(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
    ) {
        root.descendants("fetchPlan", "fetch-plan").forEachIndexed { index, element ->
            val name = element.attr("name").ifBlank { element.attr("id") }.ifBlank { "fetch-plan-${index + 1}" }
            val entity = element.attr("entity")
            addArtifact(
                artifacts,
                file,
                ArtifactKind.FETCH_PLAN,
                if (entity.isBlank()) name else "$entity:$name",
                name,
                if (entity.isBlank()) "Named fetch plan" else "Fetch plan for $entity",
                name,
                setOf(name, "$entity:$name"),
                name,
            )
        }
    }

    private fun indexLiquibase(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val rootArtifact = addArtifact(
            artifacts,
            file,
            ArtifactKind.LIQUIBASE_ROOT,
            file.relativePath,
            file.fileNameWithoutExtension(),
            "Liquibase changelog",
            file.relativePath,
            setOf(file.relativePath, file.fileNameWithoutExtension()),
            root.tagName,
        )
        root.descendants("include").forEachIndexed { index, element ->
            val included = element.attr("file").ifBlank { "include-${index + 1}" }
            val includeArtifact = addArtifact(
                artifacts,
                file,
                ArtifactKind.LIQUIBASE_INCLUDE,
                "${file.relativePath}#$included",
                included,
                "Liquibase include",
                included,
                setOf(included),
                included,
            )
            links += rootArtifact.link(
                includeArtifact,
                RelationshipType.INCLUDES_CHANGELOG,
                file.locator(included, included),
            )
        }
        root.descendants("changeSet").forEachIndexed { index, element ->
            val id = element.attr("id").ifBlank { "change-set-${index + 1}" }
            val author = element.attr("author")
            val changeSet = addArtifact(
                artifacts,
                file,
                ArtifactKind.LIQUIBASE_CHANGESET,
                "${file.relativePath}#$id:$author",
                id,
                "Liquibase changeset${if (author.isBlank()) "" else " by $author"}",
                id,
                setOf(id, "${file.relativePath}#$id"),
                id,
            )
            links += rootArtifact.link(changeSet, RelationshipType.DECLARES, file.locator(id, id))
        }
    }

    private fun indexWorkflow(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        root.descendants("process").forEachIndexed { processIndex, processElement ->
            val processId = processElement.attr("id").ifBlank { "process-${processIndex + 1}" }
            val process = addArtifact(
                artifacts,
                file,
                ArtifactKind.WORKFLOW_PROCESS,
                processId,
                processElement.attr("name").ifBlank { processId },
                "BPMN workflow process",
                processId,
                setOf(processId),
                processId,
            )
            val stateByNodeId = mutableMapOf<String, DetectedArtifact>()
            processElement.allElements().filter { it.localTag() in WORKFLOW_NODE_TAGS }.forEachIndexed { index, node ->
                val nodeId = node.attr("id").ifBlank { "state-${index + 1}" }
                val state = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.WORKFLOW_STATE,
                    "$processId#$nodeId",
                    node.attr("name").ifBlank { nodeId },
                    node.localTag(),
                    nodeId,
                    setOf("$processId#$nodeId", nodeId),
                    nodeId,
                )
                stateByNodeId[nodeId] = state
                links += process.link(
                    state,
                    RelationshipType.PARTICIPATES_IN_WORKFLOW,
                    file.locator(nodeId, nodeId),
                )
            }
            processElement.descendants("sequenceFlow").forEach { flow ->
                val source = stateByNodeId[flow.attr("sourceRef")] ?: return@forEach
                val target = flow.attr("targetRef").takeIf(String::isNotBlank) ?: return@forEach
                links += source.link(
                    "$processId#$target",
                    RelationshipType.TRANSITIONS_TO,
                    setOf(ArtifactKind.WORKFLOW_STATE),
                    file.locator(flow.attr("id").ifBlank { target }, flow.attr("id")),
                )
            }
        }
    }

    private fun indexProperties(
        file: GraphSourceFile,
        artifacts: MutableMap<String, DetectedArtifact>,
    ) {
        val bundle = addArtifact(
            artifacts,
            file,
            ArtifactKind.MESSAGE_BUNDLE,
            file.relativePath,
            file.fileNameWithoutExtension(),
            "Localization message bundle",
            file.relativePath,
            setOf(file.relativePath, file.fileNameWithoutExtension()),
            file.fileNameWithoutExtension(),
        )
        PROPERTY_KEY.findAll(file.content).forEach { match ->
            val key = match.groupValues[1]
            addArtifact(
                artifacts,
                file,
                ArtifactKind.MESSAGE_KEY,
                "${bundle.semanticKey}#$key",
                key,
                "Localization message",
                key,
                setOf(key, "${bundle.semanticKey}#$key"),
                key,
            )
        }
    }

    private fun addImplicitSourceRelationships(
        files: List<GraphSourceFile>,
        artifacts: Map<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val sourceArtifacts = artifacts.values.groupBy { it.snapshot.sourceLocator.relativePath }
        val entities = artifacts.values.filter { it.snapshot.kind == ArtifactKind.ENTITY }
        val services = artifacts.values.filter { it.snapshot.kind == ArtifactKind.SERVICE }
        val workflows = artifacts.values.filter { it.snapshot.kind == ArtifactKind.WORKFLOW_PROCESS }

        files.forEach { file ->
            val owners = sourceArtifacts[file.relativePath].orEmpty()
            owners.forEach { source ->
                entities.filterNot { it.id == source.id }.forEach { entity ->
                    if (entity.aliases.any { alias -> containsSymbol(file.content, alias) }) {
                        links += source.link(
                            entity,
                            RelationshipType.USES_ENTITY,
                            file.locator(entity.displayName, source.semanticKey),
                        )
                    }
                }
                services.filterNot { it.id == source.id }.forEach { service ->
                    if (service.aliases.any { alias -> containsSymbol(file.content, alias) }) {
                        links += source.link(
                            service,
                            RelationshipType.CALLS_SERVICE,
                            file.locator(service.displayName, source.semanticKey),
                        )
                    }
                }
                workflows.forEach { workflow ->
                    if (workflow.aliases.any { alias -> containsSymbol(file.content, alias) }) {
                        links += source.link(
                            workflow,
                            RelationshipType.PARTICIPATES_IN_WORKFLOW,
                            file.locator(workflow.displayName, source.semanticKey),
                        )
                    }
                }
            }
        }
    }

    private fun resolveLinks(
        artifacts: Map<String, DetectedArtifact>,
        pending: List<PendingLink>,
    ): List<ArtifactRelationship> {
        val aliases = linkedMapOf<String, MutableList<DetectedArtifact>>()
        artifacts.values.forEach { artifact ->
            artifact.aliases.forEach { alias ->
                aliases.getOrPut(normalizeAlias(alias)) { mutableListOf() }.add(artifact)
            }
        }

        return pending.distinctBy { listOf(it.sourceId, it.targetRef, it.type.name, it.locator.relativePath) }.map { link ->
            val candidates = aliases[normalizeAlias(link.targetRef)].orEmpty()
                .filter { link.expectedKinds.isEmpty() || it.snapshot.kind in link.expectedKinds }
                .distinctBy(DetectedArtifact::id)
            val target = candidates.singleOrNull()
            val linkDiagnostic = when {
                target != null -> null
                candidates.size > 1 -> diagnostic(
                    reasonCode = "P2_RELATIONSHIP_AMBIGUOUS",
                    message = "A relationship target is ambiguous: ${link.targetRef}.",
                    nextStep = "Open the source and select the intended owned artifact.",
                    category = DiagnosticCategory.RELATIONSHIP,
                    severity = DiagnosticSeverity.WARNING,
                    locator = link.locator,
                )
                else -> diagnostic(
                    reasonCode = "P2_RELATIONSHIP_UNRESOLVED",
                    message = "A relationship target could not be resolved: ${link.targetRef}.",
                    nextStep = "Import or index the owning module, dependency, or add-on.",
                    category = DiagnosticCategory.RELATIONSHIP,
                    severity = DiagnosticSeverity.INFO,
                    locator = link.locator,
                )
            }
            ArtifactRelationship(
                sourceArtifactId = link.sourceId,
                targetArtifactId = target?.id,
                type = link.type,
                sourceLocator = link.locator,
                diagnostic = linkDiagnostic,
            )
        }
    }

    private fun addArtifact(
        artifacts: MutableMap<String, DetectedArtifact>,
        file: GraphSourceFile,
        kind: ArtifactKind,
        semanticKey: String,
        displayName: String,
        summary: String,
        symbol: String?,
        aliases: Set<String>,
        token: String,
    ): DetectedArtifact {
        val id = CanonicalDiscoveryJson.artifactId(kind, file.owner.buildId, file.owner.moduleId, semanticKey)
        return artifacts.getOrPut(id) {
            val locator = file.locator(token, symbol)
            val snapshot = ArtifactSnapshot(
                id = id,
                kind = kind,
                semanticKey = semanticKey,
                owner = file.owner,
                sourceLocator = locator,
                origin = file.origin,
                fingerprint = file.fingerprint,
                displayName = displayName,
                summary = summary,
            )
            DetectedArtifact(
                snapshot = snapshot,
                semanticKey = semanticKey,
                displayName = displayName,
                aliases = (aliases + semanticKey + displayName).filter(String::isNotBlank).toSet(),
            )
        }
    }

    private fun jvmKind(content: String, declarationKind: String, typeName: String): ArtifactKind =
        when {
            JMIX_ENTITY.containsMatchIn(content) -> ArtifactKind.ENTITY
            VIEW_CONTROLLER_ID.containsMatchIn(content) -> ArtifactKind.VIEW_CONTROLLER
            REST_CONTROLLER.containsMatchIn(content) -> ArtifactKind.REST_CONTROLLER
            RESOURCE_ROLE.containsMatchIn(content) -> ArtifactKind.RESOURCE_ROLE
            ROW_ROLE.containsMatchIn(content) -> ArtifactKind.ROW_ROLE
            REPOSITORY.containsMatchIn(content) || typeName.endsWith("Repository") -> ArtifactKind.REPOSITORY
            VALIDATOR.containsMatchIn(content) || typeName.endsWith("Validator") -> ArtifactKind.VALIDATOR
            SERVICE.containsMatchIn(content) || typeName.endsWith("Service") -> ArtifactKind.SERVICE
            declarationKind == "enum" || declarationKind == "enum class" -> ArtifactKind.ENUM
            declarationKind == "data class" || typeName.endsWith("Dto") || typeName.endsWith("DTO") -> ArtifactKind.DTO
            else -> ArtifactKind.SERVICE
        }

    private fun jvmSummary(kind: ArtifactKind, file: GraphSourceFile): String =
        "${kind.name.lowercase().replace('_', ' ')} from ${file.language.name.lowercase()} source"

    private fun parseXml(content: String): Document? =
        runCatching {
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
        }.getOrNull()

    private fun Element.attr(name: String): String = getAttribute(name).trim()

    private fun Element.localTag(): String = (localName ?: tagName.substringAfter(':')).trim()

    private fun Element.descendants(vararg tags: String): List<Element> =
        allElements().filter { element -> tags.any { it == element.localTag() } }

    private fun Element.allElements(): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(node: Node) {
            val children = node.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is Element) {
                    result += child
                    visit(child)
                }
            }
        }
        visit(this)
        return result
    }

    private fun GraphSourceFile.locator(token: String? = null, symbol: String? = null): SourceLocator {
        val offset = token?.takeIf(String::isNotBlank)?.let(content::indexOf)?.takeIf { it >= 0 }
        val line = offset?.let { content.take(it).count { character -> character == '\n' } + 1 }
        val column = offset?.let {
            val lineStart = content.lastIndexOf('\n', it - 1).let { index -> if (index < 0) 0 else index + 1 }
            it - lineStart + 1
        }
        return SourceLocator(
            relativePath = relativePath,
            symbol = symbol,
            line = line,
            column = column,
            revisionFingerprint = fingerprint,
        )
    }

    private fun GraphSourceFile.fileNameWithoutExtension(): String =
        relativePath.substringAfterLast('/').substringBeforeLast('.')

    private fun DetectedArtifact.link(
        target: DetectedArtifact,
        type: RelationshipType,
        locator: SourceLocator,
    ): PendingLink =
        PendingLink(id, target.semanticKey, type, setOf(target.snapshot.kind), locator)

    private fun DetectedArtifact.link(
        target: String,
        type: RelationshipType,
        expectedKinds: Set<ArtifactKind>,
        locator: SourceLocator,
    ): PendingLink =
        PendingLink(id, target, type, expectedKinds, locator)

    private fun containsSymbol(content: String, symbol: String): Boolean {
        val simple = symbol.substringAfterLast('.').substringAfterLast('#')
        if (simple.length < 3) return false
        return Regex("(?<![A-Za-z0-9_])${Regex.escape(simple)}(?![A-Za-z0-9_])").containsMatchIn(content)
    }

    private fun normalizeAlias(value: String): String =
        value.trim().removeSuffix(".xml").replace('\\', '/').lowercase()

    private fun mappingVerb(annotation: String): String =
        when (annotation) {
            "GetMapping" -> "GET"
            "PostMapping" -> "POST"
            "PutMapping" -> "PUT"
            "PatchMapping" -> "PATCH"
            "DeleteMapping" -> "DELETE"
            else -> "REQUEST"
        }

    private fun diagnostic(
        reasonCode: String,
        message: String,
        nextStep: String?,
        category: DiagnosticCategory,
        severity: DiagnosticSeverity,
        locator: SourceLocator? = null,
    ): DiscoveryDiagnostic {
        val identity = listOf(reasonCode, locator?.relativePath.orEmpty(), locator?.line?.toString().orEmpty(), message)
            .joinToString("\u0000")
        return DiscoveryDiagnostic(
            id = CanonicalDiscoveryJson.sha256(identity),
            severity = severity,
            category = category,
            reasonCode = reasonCode,
            message = message,
            nextStep = nextStep,
            sourceLocator = locator,
        )
    }

    private data class DetectedArtifact(
        val snapshot: ArtifactSnapshot,
        val semanticKey: String,
        val displayName: String,
        val aliases: Set<String>,
    ) {
        val id: String
            get() = snapshot.id
    }

    private data class PendingLink(
        val sourceId: String,
        val targetRef: String,
        val type: RelationshipType,
        val expectedKinds: Set<ArtifactKind>,
        val locator: SourceLocator,
    )

    private data class MappedMethod(
        val annotation: String,
        val path: String,
        val name: String,
    )

    private companion object {
        val PACKAGE = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        val TYPE = Regex("""\b(enum\s+class|data\s+class|class|interface|object|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val JMIX_ENTITY = Regex("""@(JmixEntity|Entity)\b""")
        val VIEW_CONTROLLER_ID = Regex("""@ViewController\s*\(\s*["']([^"']+)["']""")
        val VIEW_DESCRIPTOR = Regex("""@ViewDescriptor\s*\(\s*["']([^"']+)["']""")
        val REST_CONTROLLER = Regex("""@(RestController|Controller)\b""")
        val RESOURCE_ROLE = Regex("""@ResourceRole\b""")
        val ROW_ROLE = Regex("""@(RowLevelRole|RowLevelPolicy)\b""")
        val REPOSITORY = Regex("""@(Repository)\b|JmixDataRepository|CrudRepository|JpaRepository""")
        val VALIDATOR = Regex("""ConstraintValidator|@\w*Validator\b""")
        val SERVICE = Regex("""@(Service|Component)\b""")
        val TRANSACTIONAL = Regex("""@Transactional\b""")
        val SAVE_CALL = Regex("""\.(save|saveAll|remove|delete|deleteAll)\s*\(""")
        val KOTLIN_MAPPED_METHOD = Regex(
            """@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)\s*(?:\(\s*(?:value\s*=\s*)?["']?([^"')\s,]*)["']?[^)]*\))?[\s\S]{0,500}?\bfun\s+([A-Za-z_]\w*)\s*\(""",
        )
        val JAVA_MAPPED_METHOD = Regex(
            """@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)\s*(?:\(\s*(?:value\s*=\s*)?["']?([^"')\s,]*)["']?[^)]*\))?[\s\S]{0,500}?\b(?:public|protected|private)?\s*[\w<>,?.\[\]\s]+\s+([A-Za-z_]\w*)\s*\(""",
        )
        val EVENT_METHOD = Regex("""@EventListener(?:\s*\([^)]*\))?[\s\S]{0,300}?\b(?:fun\s+|[\w<>,?.\[\]\s]+\s+)([A-Za-z_]\w*)\s*\(""")
        val SCHEDULED_METHOD = Regex("""@Scheduled(?:\s*\([^)]*\))?[\s\S]{0,300}?\b(?:fun\s+|[\w<>,?.\[\]\s]+\s+)([A-Za-z_]\w*)\s*\(""")
        val ROLE_REFERENCES = Regex("""@(RolesAllowed|Secured)\s*\(\s*(?:value\s*=\s*)?([^)]*)\)""")
        val ROLE_CODE = Regex("""@(ResourceRole|RowLevelRole)\s*\([^)]*\bcode\s*=\s*["']([^"']+)["'][^)]*\)""")
        val UNSAFE_MONEY_FIELD = Regex(
            """(?i)\b(?:Double|Float|double|float)\s+([A-Za-z_]\w*(?:amount|balance|salary|wage|loan|interest|payment|principal|rate|money|total)\w*|(?:amount|balance|salary|wage|loan|interest|payment|principal|rate|money|total)\w*)""",
        )
        val UI_WORKFLOW_TRANSITION = Regex("""\b(setProcessState|setWorkflowState|setStatus|processState\s*=|workflowState\s*=)\b""")
        val PROPERTY_KEY = Regex("""(?m)^\s*([^#!\s][^=:\s]*)\s*[:=]""")
        val NON_COMPONENT_VIEW_TAGS = setOf(
            "view",
            "data",
            "collection",
            "instance",
            "loader",
            "query",
            "fetchPlan",
            "fetch-plan",
            "facets",
            "actions",
            "layout",
        )
        val WORKFLOW_NODE_TAGS = setOf(
            "startEvent",
            "endEvent",
            "userTask",
            "serviceTask",
            "manualTask",
            "businessRuleTask",
            "sendTask",
            "receiveTask",
            "exclusiveGateway",
            "parallelGateway",
            "inclusiveGateway",
            "subProcess",
            "callActivity",
            "intermediateCatchEvent",
            "intermediateThrowEvent",
        )
    }
}

data class GraphSourceFile(
    val relativePath: String,
    val content: String,
    val owner: ArtifactOwner,
    val language: SourceLanguage,
    val origin: ArtifactOrigin = if (language == SourceLanguage.XML || language == SourceLanguage.PROPERTIES) {
        ArtifactOrigin.RESOURCE
    } else {
        ArtifactOrigin.SOURCE
    },
    val fingerprint: String = CanonicalDiscoveryJson.sha256(content),
) {
    init {
        SourceLocator(relativePath = relativePath, revisionFingerprint = fingerprint)
    }
}

data class ApplicationGraphIndexInput(
    val files: List<GraphSourceFile>,
    val maxFiles: Int = 20_000,
    val maxFileBytes: Int = 2 * 1024 * 1024,
    val maxArtifacts: Int = 100_000,
) {
    init {
        require(maxFiles in 1..200_000)
        require(maxFileBytes in 1..16 * 1024 * 1024)
        require(maxArtifacts in 1..1_000_000)
    }
}

data class ApplicationGraphIndexResult(
    val artifacts: List<ArtifactSnapshot>,
    val relationships: List<ArtifactRelationship>,
    val diagnostics: List<DiscoveryDiagnostic>,
)

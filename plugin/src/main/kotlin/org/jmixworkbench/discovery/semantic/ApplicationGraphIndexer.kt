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
                SourceLanguage.PROPERTIES -> indexProperties(file, artifacts, pendingLinks, diagnostics)
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
        SPRING_BEAN_NAME.find(file.content)?.groupValues?.get(2)?.let(aliases::add)
        REST_SERVICE_NAME.find(file.content)?.groupValues?.get(1)?.let(aliases::add)
        JMIX_ENTITY_NAME.find(file.content)?.groupValues?.get(1)?.let(aliases::add)
        TABLE_NAME.find(file.content)?.groupValues?.get(1)?.let(aliases::add)
        if (primaryKind == ArtifactKind.SERVICE) {
            aliases += typeName.replaceFirstChar(Char::lowercase)
        }
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
            analysisText = file.content,
        )

        if (primaryKind == ArtifactKind.ENTITY) {
            indexEntityAttributes(file, primary, semanticKey, typeName, artifacts, links)
        }

        ROUTE.find(file.content)?.let { routeMatch ->
            val routePath = routeMatch.groupValues[1]
            val route = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.VIEW_ROUTE,
                semanticKey = "$semanticKey#$routePath",
                displayName = routePath.ifBlank { "/" },
                summary = "Vaadin route for $typeName",
                symbol = semanticKey,
                aliases = setOf(routePath, "$semanticKey#$routePath"),
                token = routePath,
            )
            links += primary.link(route, RelationshipType.ROUTED_AS, file.locator(routePath, semanticKey))
        }

        if (primaryKind == ArtifactKind.VIEW_CONTROLLER) {
            indexViewControllerMembers(file, primary, semanticKey, artifacts, links)
        }

        if (primaryKind == ArtifactKind.SERVICE) {
            indexServiceMethods(file, primary, semanticKey, typeName, artifacts, links)
        }

        if (primaryKind == ArtifactKind.RESOURCE_ROLE || primaryKind == ArtifactKind.ROW_ROLE) {
            indexSecurityPolicies(file, primary, semanticKey, artifacts, links)
        }

        VIEW_DESCRIPTOR.find(file.content)?.groupValues?.get(1)?.let { descriptor ->
            links += primary.link(
                target = descriptor.substringBeforeLast('.'),
                type = RelationshipType.CONTROLS,
                expectedKinds = setOf(ArtifactKind.VIEW_DESCRIPTOR),
                locator = file.locator(descriptor, semanticKey),
            )
        }

        val mappedMethods = KOTLIN_MAPPED_METHOD.findAll(file.content).map { match ->
            MappedMethod(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.range.last + 1)
        } + JAVA_MAPPED_METHOD.findAll(file.content).map { match ->
            MappedMethod(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.range.last + 1)
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
                analysisText = file.memberSnippet(method.bodyAnchor),
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
                analysisText = file.memberSnippet(match.range.last + 1),
            )
            links += primary.link(listener, RelationshipType.DECLARES, file.locator(methodName, semanticKey))
            ENTITY_EVENT_TARGET.find(listener.analysisText.orEmpty())?.groupValues?.get(1)?.let { entity ->
                links += listener.link(
                    entity,
                    RelationshipType.LISTENS_TO,
                    setOf(ArtifactKind.ENTITY),
                    file.locator(entity, "$semanticKey#$methodName"),
                )
            }
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
                analysisText = file.memberSnippet(match.range.last + 1),
            )
            links += primary.link(job, RelationshipType.SCHEDULED_BY, file.locator(methodName, semanticKey))
        }

        NATIVE_SQL_STATEMENT.findAll(file.content).forEachIndexed { index, match ->
            val operationName = match.groupValues[1].uppercase()
            val tableName = match.groupValues[2]
            val operation = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.DATABASE_OPERATION,
                semanticKey = "$semanticKey#$operationName:$tableName:${index + 1}",
                displayName = "$operationName $tableName",
                summary = "Native SQL write declared by $typeName",
                symbol = semanticKey,
                aliases = setOf("$semanticKey#$operationName:$tableName:${index + 1}"),
                token = match.value,
                analysisText = match.value,
            )
            links += primary.link(operation, RelationshipType.DECLARES, file.locator(match.value, semanticKey))
            links += operation.link(
                tableName,
                RelationshipType.WRITES_ENTITY,
                setOf(ArtifactKind.ENTITY),
                file.locator(tableName, semanticKey),
            )
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

        addJvmRiskDiagnostics(file, semanticKey, diagnostics)
    }

    private fun indexEntityAttributes(
        file: GraphSourceFile,
        entity: DetectedArtifact,
        semanticKey: String,
        typeName: String,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val fields = buildList {
            JAVA_FIELD.findAll(file.content).forEach { match ->
                add(FieldDeclaration(match.groupValues[2], match.groupValues[1]))
            }
            KOTLIN_FIELD.findAll(file.content).forEach { match ->
                add(FieldDeclaration(match.groupValues[1], match.groupValues[2]))
            }
        }.distinctBy(FieldDeclaration::name)
            .filterNot { it.name == "serialVersionUID" || it.name.all(Char::isUpperCase) }

        fields.forEach { field ->
            val attribute = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.ENTITY_ATTRIBUTE,
                semanticKey = "$semanticKey.${field.name}",
                displayName = field.name,
                summary = "${field.type} attribute of $typeName",
                symbol = "$semanticKey.${field.name}",
                aliases = entity.aliases.mapTo(linkedSetOf()) { alias -> "$alias.${field.name}" },
                token = field.name,
            )
            links += entity.link(
                attribute,
                RelationshipType.DECLARES,
                file.locator(field.name, "$semanticKey.${field.name}"),
            )
        }
    }

    private fun indexViewControllerMembers(
        file: GraphSourceFile,
        controller: DetectedArtifact,
        semanticKey: String,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val viewId = VIEW_CONTROLLER_ID.find(file.content)?.groupValues?.get(1)
        val componentKinds = setOf(
            ArtifactKind.UI_COMPONENT,
            ArtifactKind.UI_ACTION,
            ArtifactKind.DATA_CONTAINER,
            ArtifactKind.DATA_LOADER,
        )
        val injected = sequenceOf(JAVA_VIEW_COMPONENT, KOTLIN_VIEW_COMPONENT)
            .flatMap { regex -> regex.findAll(file.content) }
            .map { match ->
                val explicitId = match.groupValues[1]
                val fieldName = match.groupValues[2]
                explicitId.ifBlank { fieldName } to fieldName
            }
            .distinct()
        injected.forEach { (componentId, fieldName) ->
            links += controller.link(
                target = viewTarget(viewId, componentId),
                type = RelationshipType.INJECTS_COMPONENT,
                expectedKinds = componentKinds,
                locator = file.locator(fieldName, "$semanticKey#$fieldName"),
            )
        }

        SUBSCRIBE_METHOD.findAll(file.content).forEach { match ->
            val targetId = match.groupValues[1]
            val methodName = match.groupValues[2]
            val handler = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.VIEW_HANDLER,
                semanticKey = "$semanticKey#$methodName",
                displayName = methodName,
                summary = if (targetId.isBlank()) "View lifecycle handler" else "Subscription handler for $targetId",
                symbol = "$semanticKey#$methodName",
                aliases = setOf("$semanticKey#$methodName", methodName),
                token = methodName,
                analysisText = file.memberSnippet(match.range.last + 1),
            )
            links += controller.link(handler, RelationshipType.DECLARES, file.locator(methodName, "$semanticKey#$methodName"))
            if (targetId.isNotBlank()) {
                links += handler.link(
                    target = viewTarget(viewId, targetId),
                    type = RelationshipType.SUBSCRIBES_TO,
                    expectedKinds = componentKinds,
                    locator = file.locator(targetId, "$semanticKey#$methodName"),
                )
            }
        }

        INSTALL_METHOD.findAll(file.content).forEach { match ->
            val targetId = match.groupValues[1].substringBefore('.')
            val methodName = match.groupValues[2]
            val handler = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.VIEW_HANDLER,
                semanticKey = "$semanticKey#$methodName",
                displayName = methodName,
                summary = "Install delegate for ${match.groupValues[1]}",
                symbol = "$semanticKey#$methodName",
                aliases = setOf("$semanticKey#$methodName", methodName),
                token = methodName,
                analysisText = file.memberSnippet(match.range.last + 1),
            )
            links += controller.link(handler, RelationshipType.DECLARES, file.locator(methodName, "$semanticKey#$methodName"))
            links += handler.link(
                target = viewTarget(viewId, targetId),
                type = RelationshipType.SUBSCRIBES_TO,
                expectedKinds = componentKinds,
                locator = file.locator(targetId, "$semanticKey#$methodName"),
            )
        }
    }

    private fun indexServiceMethods(
        file: GraphSourceFile,
        service: DetectedArtifact,
        semanticKey: String,
        typeName: String,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val exposedMethods = REST_METHOD_DECLARATION.findAll(file.content)
            .map { it.groupValues[1] }
            .toSet()
        val declarations = buildList {
            JAVA_METHOD_DECLARATION.findAll(file.content).forEach { match ->
                add(MethodDeclaration(match.groupValues[1], match.range.last + 1))
            }
            KOTLIN_METHOD_DECLARATION.findAll(file.content).forEach { match ->
                add(MethodDeclaration(match.groupValues[1], match.range.last + 1))
            }
        }.distinctBy(MethodDeclaration::name)
            .filterNot { it.name == typeName || it.name in METHOD_KEYWORDS }

        declarations.forEach { declaration ->
            val aliases = service.aliases.mapTo(linkedSetOf()) { alias -> "$alias#${declaration.name}" }
            aliases += "$semanticKey#${declaration.name}"
            val method = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.SERVICE_METHOD,
                semanticKey = "$semanticKey#${declaration.name}",
                displayName = declaration.name,
                summary = "Service operation declared by $typeName",
                symbol = "$semanticKey#${declaration.name}",
                aliases = aliases,
                token = declaration.name,
                analysisText = file.memberSnippet(declaration.bodyAnchor),
            )
            links += service.link(
                method,
                RelationshipType.DECLARES,
                file.locator(declaration.name, "$semanticKey#${declaration.name}"),
            )
            if (declaration.name in exposedMethods) {
                links += service.link(
                    method,
                    RelationshipType.EXPOSES_SERVICE_METHOD,
                    file.locator(declaration.name, "$semanticKey#${declaration.name}"),
                )
            }
        }
    }

    private fun indexSecurityPolicies(
        file: GraphSourceFile,
        role: DetectedArtifact,
        semanticKey: String,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        POLICY_ANNOTATION.findAll(file.content).forEachIndexed { index, match ->
            val policyType = match.groupValues[1]
            val body = match.groupValues[2]
            val policy = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.SECURITY_POLICY,
                semanticKey = "$semanticKey#$policyType-${index + 1}",
                displayName = policyType,
                summary = securityPolicySummary(policyType, body),
                symbol = "$semanticKey#$policyType-${index + 1}",
                aliases = setOf("$semanticKey#$policyType-${index + 1}"),
                token = policyType,
            )
            links += role.link(policy, RelationshipType.DECLARES, file.locator(policyType, policy.semanticKey))

            when (policyType) {
                "EntityPolicy" -> policyEntityReference(body)?.let { entity ->
                    links += policy.link(
                        entity,
                        RelationshipType.APPLIES_POLICY_TO,
                        setOf(ArtifactKind.ENTITY),
                        file.locator(entity, policy.semanticKey),
                    )
                }
                "EntityAttributePolicy" -> {
                    val entity = policyEntityReference(body)
                    if (entity != null) {
                        stringValues(ATTRIBUTES_ARGUMENT.find(body)?.groupValues?.get(1).orEmpty()).forEach { attribute ->
                            if (attribute != "*") {
                                links += policy.link(
                                    "$entity.$attribute",
                                    RelationshipType.APPLIES_POLICY_TO,
                                    setOf(ArtifactKind.ENTITY_ATTRIBUTE),
                                    file.locator(attribute, policy.semanticKey),
                                )
                            }
                        }
                    }
                }
                "ViewPolicy" -> stringValues(VIEW_IDS_ARGUMENT.find(body)?.groupValues?.get(1).orEmpty()).forEach { view ->
                    if (view != "*") {
                        links += policy.link(
                            view,
                            RelationshipType.APPLIES_POLICY_TO,
                            setOf(ArtifactKind.VIEW_DESCRIPTOR),
                            file.locator(view, policy.semanticKey),
                        )
                    }
                }
                "MenuPolicy" -> stringValues(MENU_IDS_ARGUMENT.find(body)?.groupValues?.get(1).orEmpty()).forEach { menu ->
                    if (menu != "*") {
                        links += policy.link(
                            menu,
                            RelationshipType.APPLIES_POLICY_TO,
                            setOf(ArtifactKind.MENU_ITEM),
                            file.locator(menu, policy.semanticKey),
                        )
                    }
                }
            }
        }
    }

    private fun addJvmRiskDiagnostics(
        file: GraphSourceFile,
        semanticKey: String,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        fun add(
            regex: Regex,
            reasonCode: String,
            message: String,
            nextStep: String,
            category: DiagnosticCategory,
            severity: DiagnosticSeverity = DiagnosticSeverity.WARNING,
        ) {
            val match = regex.find(file.content) ?: return
            diagnostics += diagnostic(
                reasonCode = reasonCode,
                message = message,
                nextStep = nextStep,
                category = category,
                severity = severity,
                locator = file.locator(match.value, semanticKey),
            )
        }

        add(
            UNCONSTRAINED_ACCESS,
            "P2_UNCONSTRAINED_DATA_ACCESS",
            "Unconstrained data access bypasses row-level security constraints.",
            "Verify the server-side authorization boundary and document why constrained DataManager cannot be used.",
            DiagnosticCategory.SECURITY,
        )
        add(
            NATIVE_SQL_WRITE,
            "P2_NATIVE_SQL_WRITE",
            "A native SQL write can bypass entity listeners, validation, security, and audit behavior.",
            "Move the mutation behind a reviewed transactional service and model its side effects explicitly.",
            DiagnosticCategory.TRANSACTION,
        )
        add(
            PRINT_STACK_TRACE,
            "P2_PRINT_STACK_TRACE",
            "printStackTrace() bypasses structured production logging and may expose sensitive details.",
            "Use the project logging framework with an appropriate message and exception context.",
            DiagnosticCategory.SOURCE,
        )
        add(
            SWALLOWED_EXCEPTION,
            "P2_SWALLOWED_EXCEPTION",
            "An exception appears to be swallowed without logging, propagation, or recovery.",
            "Handle the failure explicitly or propagate it to the owning transaction boundary.",
            DiagnosticCategory.SOURCE,
        )
        add(
            HARDCODED_HTTP_ENDPOINT,
            "P2_HARDCODED_HTTP_ENDPOINT",
            "An outbound HTTP endpoint is hard-coded in source.",
            "Move the endpoint to validated external configuration and keep credentials out of source control.",
            DiagnosticCategory.SECURITY,
        )
        if (OUTBOUND_HTTP_CLIENT.containsMatchIn(file.content) && !HTTP_TIMEOUT_CONFIGURATION.containsMatchIn(file.content)) {
            val match = OUTBOUND_HTTP_CLIENT.find(file.content)!!
            diagnostics += diagnostic(
                reasonCode = "P2_OUTBOUND_HTTP_TIMEOUT_MISSING",
                message = "An outbound HTTP client is used without visible timeout configuration.",
                nextStep = "Configure connection and response timeouts and review retry/idempotency behavior.",
                category = DiagnosticCategory.SOURCE,
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
            "services" -> if (root.namespaceURI.orEmpty().contains("/rest/services")) {
                indexRestServices(file, root, artifacts, links, diagnostics)
            }
            "queries" -> if (root.namespaceURI.orEmpty().contains("/rest/queries")) {
                indexRestQueries(file, root, artifacts, links, diagnostics)
            }
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

        val containerEntities = linkedMapOf<String, String>()
        val containerArtifacts = linkedMapOf<String, DetectedArtifact>()
        root.descendants("collection", "instance", "keyValueCollection", "keyValueInstance").forEach { element ->
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
            containerArtifacts[id] = container
            links += view.link(container, RelationshipType.DECLARES, file.locator(id, id))
            element.attr("class").takeIf(String::isNotBlank)?.let { entity ->
                containerEntities[id] = entity
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
            element.directChildren("fetchPlan", "fetch-plan").firstOrNull()?.let { fetchPlanElement ->
                val fetchPlanName = fetchPlanElement.attr("name")
                    .ifBlank { fetchPlanElement.attr("extends") }
                    .ifBlank { "$viewId#$id:inline" }
                val entity = containerEntities[id].orEmpty()
                val inlineFetchPlan = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.FETCH_PLAN,
                    "$viewId#$id:$fetchPlanName",
                    fetchPlanName,
                    if (entity.isBlank()) "Inline view fetch plan" else "Inline fetch plan for $entity",
                    fetchPlanName,
                    setOf("$viewId#$id:$fetchPlanName"),
                    fetchPlanName,
                )
                links += container.link(
                    inlineFetchPlan,
                    RelationshipType.REFERENCES_FETCH_PLAN,
                    file.locator(fetchPlanName, id),
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
            val owningContainer = element.ancestors()
                .firstOrNull { it.localTag() in DATA_CONTAINER_TAGS }
                ?.attr("id")
            owningContainer?.let { containerId ->
                containerArtifacts[containerId]?.let { container ->
                    links += container.link(loader, RelationshipType.DECLARES, file.locator(id, containerId))
                }
            }
            element.attr("fetchPlan").takeIf(String::isNotBlank)?.let { fetchPlan ->
                links += loader.link(
                    fetchPlan,
                    RelationshipType.REFERENCES_FETCH_PLAN,
                    setOf(ArtifactKind.FETCH_PLAN),
                    file.locator(fetchPlan, id),
                )
            }

            element.directChildren("query").firstOrNull()?.let { queryElement ->
                val queryText = queryElement.textContent.trim().replace(Regex("""\s+"""), " ")
                if (queryText.isNotBlank()) {
                    val query = addArtifact(
                        artifacts,
                        file,
                        ArtifactKind.JPQL_QUERY,
                        "$viewId#$id:query",
                        id,
                        queryText.take(240),
                        "$viewId#$id:query",
                        setOf("$viewId#$id:query"),
                        queryText.take(80),
                    )
                    links += loader.link(query, RelationshipType.EXECUTES_QUERY, file.locator(queryText.take(80), id))
                    val entityRef = JPQL_FROM.find(queryText)?.groupValues?.get(1)
                        ?: owningContainer?.let(containerEntities::get)
                    entityRef?.let { entity ->
                        links += query.link(
                            entity,
                            RelationshipType.LOADS_ENTITY,
                            setOf(ArtifactKind.ENTITY, ArtifactKind.DTO),
                            file.locator(entity.substringAfterLast('.'), query.semanticKey),
                        )
                    }
                    JPQL_PARAMETER.findAll(queryText).map { it.groupValues[1] }.distinct().forEach { parameterName ->
                        val parameter = addArtifact(
                            artifacts,
                            file,
                            ArtifactKind.QUERY_PARAMETER,
                            "${query.semanticKey}#$parameterName",
                            parameterName,
                            "JPQL loader parameter",
                            "${query.semanticKey}#$parameterName",
                            setOf("${query.semanticKey}#$parameterName"),
                            parameterName,
                        )
                        links += query.link(
                            parameter,
                            RelationshipType.DECLARES_PARAMETER,
                            file.locator(parameterName, query.semanticKey),
                        )
                    }
                }
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
            element.attr("itemsContainer").takeIf(String::isNotBlank)?.let { container ->
                links += component.link(
                    "$viewId#$container",
                    RelationshipType.BINDS_TO_ENTITY,
                    setOf(ArtifactKind.DATA_CONTAINER),
                    file.locator(container, id),
                )
            }
            val effectiveContainer = element.inheritedAttr("dataContainer")
            val property = element.attr("property")
            val entity = effectiveContainer?.let(containerEntities::get)
            if (entity != null && property.isNotBlank()) {
                links += component.link(
                    "$entity.${property.substringBefore('.')}",
                    RelationshipType.BINDS_TO_ATTRIBUTE,
                    setOf(ArtifactKind.ENTITY_ATTRIBUTE),
                    file.locator(property, id),
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

    private fun indexRestServices(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val config = addArtifact(
            artifacts,
            file,
            ArtifactKind.REST_SERVICE_CONFIG,
            file.relativePath,
            file.fileNameWithoutExtension(),
            "Jmix REST service allowlist",
            file.relativePath,
            setOf(file.relativePath, file.classpathResourcePath(), file.relativePath.substringAfterLast('/')),
            root.tagName,
        )
        val signatures = mutableSetOf<String>()
        root.descendants("service").forEachIndexed { serviceIndex, serviceElement ->
            val serviceName = serviceElement.attr("name").ifBlank { "service-${serviceIndex + 1}" }
            serviceElement.directChildren("method").forEachIndexed { methodIndex, methodElement ->
                val methodName = methodElement.attr("name").ifBlank { "method-${methodIndex + 1}" }
                val params = methodElement.directChildren("param")
                val signature = "$serviceName#$methodName(${params.joinToString { it.attr("type") }})"
                if (!signatures.add(signature)) {
                    diagnostics += diagnostic(
                        reasonCode = "P2_REST_SERVICE_METHOD_DUPLICATE",
                        message = "Duplicate REST service method contract: $signature.",
                        nextStep = "Remove the duplicate or provide explicit parameter types for an overloaded service method.",
                        category = DiagnosticCategory.SOURCE,
                        severity = DiagnosticSeverity.ERROR,
                        locator = file.locator(methodName, signature),
                    )
                }
                val method = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.REST_SERVICE_METHOD,
                    signature,
                    "$serviceName.$methodName",
                    "REST-exposed service method with ${params.size} parameter(s)",
                    signature,
                    setOf(signature, "$serviceName#$methodName"),
                    methodName,
                )
                links += config.link(method, RelationshipType.EXPOSES_SERVICE_METHOD, file.locator(methodName, signature))
                links += method.link(
                    "$serviceName#$methodName",
                    RelationshipType.IMPLEMENTED_BY,
                    setOf(ArtifactKind.SERVICE_METHOD),
                    file.locator(serviceName, signature),
                )
                params.forEachIndexed { parameterIndex, parameterElement ->
                    val name = parameterElement.attr("name").ifBlank { "param-${parameterIndex + 1}" }
                    val type = parameterElement.attr("type")
                    val parameter = addArtifact(
                        artifacts,
                        file,
                        ArtifactKind.CONTRACT_PARAMETER,
                        "$signature#$name",
                        name,
                        if (type.isBlank()) "REST parameter with inferred type" else "REST parameter of type $type",
                        "$signature#$name",
                        setOf("$signature#$name"),
                        name,
                    )
                    links += method.link(
                        parameter,
                        RelationshipType.DECLARES_PARAMETER,
                        file.locator(name, signature),
                    )
                }
            }
        }
    }

    private fun indexRestQueries(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val config = addArtifact(
            artifacts,
            file,
            ArtifactKind.REST_QUERY_CONFIG,
            file.relativePath,
            file.fileNameWithoutExtension(),
            "Jmix predefined REST query allowlist",
            file.relativePath,
            setOf(file.relativePath, file.classpathResourcePath(), file.relativePath.substringAfterLast('/')),
            root.tagName,
        )
        val identities = mutableSetOf<String>()
        root.descendants("query").forEachIndexed { index, queryElement ->
            val name = queryElement.attr("name").ifBlank { "query-${index + 1}" }
            val entity = queryElement.attr("entity")
            val identity = "$entity:$name"
            if (!identities.add(identity)) {
                diagnostics += diagnostic(
                    reasonCode = "P2_REST_QUERY_DUPLICATE",
                    message = "Duplicate predefined REST query identity: $identity.",
                    nextStep = "Give every query a unique entity and name combination.",
                    category = DiagnosticCategory.QUERY,
                    severity = DiagnosticSeverity.ERROR,
                    locator = file.locator(name, identity),
                )
            }
            val jpql = queryElement.directChildren("jpql").firstOrNull()
                ?.textContent
                ?.trim()
                ?.replace(Regex("""\s+"""), " ")
                .orEmpty()
            val query = addArtifact(
                artifacts,
                file,
                ArtifactKind.REST_QUERY,
                identity,
                name,
                jpql.ifBlank { "Predefined REST query for $entity" }.take(240),
                identity,
                setOf(identity, name),
                name,
            )
            links += config.link(query, RelationshipType.DECLARES, file.locator(name, identity))
            if (entity.isNotBlank()) {
                links += query.link(
                    entity,
                    RelationshipType.LOADS_ENTITY,
                    setOf(ArtifactKind.ENTITY, ArtifactKind.DTO),
                    file.locator(entity, identity),
                )
            }
            queryElement.attr("fetchPlan").takeIf(String::isNotBlank)?.let { fetchPlan ->
                links += query.link(
                    fetchPlan,
                    RelationshipType.REFERENCES_FETCH_PLAN,
                    setOf(ArtifactKind.FETCH_PLAN),
                    file.locator(fetchPlan, identity),
                )
            }

            val declaredParams = queryElement.descendants("param")
                .filter { it.ancestors().firstOrNull { ancestor -> ancestor.localTag() == "query" } == queryElement }
                .mapNotNull { element -> element.attr("name").takeIf(String::isNotBlank)?.let { it to element } }
                .toMap()
            declaredParams.forEach { (parameterName, parameterElement) ->
                val type = parameterElement.attr("type")
                val parameter = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.CONTRACT_PARAMETER,
                    "$identity#$parameterName",
                    parameterName,
                    if (type.isBlank()) "REST query parameter" else "REST query parameter of type $type",
                    "$identity#$parameterName",
                    setOf("$identity#$parameterName"),
                    parameterName,
                )
                links += query.link(
                    parameter,
                    RelationshipType.DECLARES_PARAMETER,
                    file.locator(parameterName, identity),
                )
            }
            val referencedParams = JPQL_PARAMETER.findAll(jpql).map { it.groupValues[1] }.toSet()
            val missingDeclarations = referencedParams - declaredParams.keys
            val unusedDeclarations = declaredParams.keys - referencedParams
            if (missingDeclarations.isNotEmpty() || unusedDeclarations.isNotEmpty()) {
                val details = buildList {
                    if (missingDeclarations.isNotEmpty()) add("undeclared: ${missingDeclarations.sorted().joinToString()}")
                    if (unusedDeclarations.isNotEmpty()) add("unused: ${unusedDeclarations.sorted().joinToString()}")
                }.joinToString("; ")
                diagnostics += diagnostic(
                    reasonCode = "P2_REST_QUERY_PARAMETER_MISMATCH",
                    message = "REST query $identity has a parameter contract mismatch ($details).",
                    nextStep = "Make the JPQL placeholders and declared REST parameters match exactly.",
                    category = DiagnosticCategory.QUERY,
                    severity = DiagnosticSeverity.ERROR,
                    locator = file.locator(name, identity),
                )
            }
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
            element.allElements().filter { it.localTag() in SCHEMA_CHANGE_TAGS }.forEachIndexed { changeIndex, change ->
                val tableNames = when (change.localTag()) {
                    "addForeignKeyConstraint" -> listOf(
                        change.attr("baseTableName"),
                        change.attr("referencedTableName"),
                    )
                    else -> listOf(change.attr("tableName"))
                }.filter(String::isNotBlank)
                tableNames.forEach { tableName ->
                    val schemaObject = addArtifact(
                        artifacts,
                        file,
                        ArtifactKind.SCHEMA_OBJECT,
                        "${file.relativePath}#$id:${change.localTag()}:$tableName:$changeIndex",
                        tableName,
                        "${change.localTag()} in changeset $id",
                        tableName,
                        setOf(tableName),
                        tableName,
                    )
                    links += changeSet.link(
                        schemaObject,
                        RelationshipType.MIGRATES,
                        file.locator(tableName, id),
                    )
                    links += schemaObject.link(
                        tableName,
                        RelationshipType.MIGRATES,
                        setOf(ArtifactKind.ENTITY),
                        file.locator(tableName, id),
                    )
                }
            }
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
        links: MutableList<PendingLink>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val isMessages = file.fileNameWithoutExtension().startsWith("messages")
        val bundleKind = if (isMessages) ArtifactKind.MESSAGE_BUNDLE else ArtifactKind.CONFIGURATION_FILE
        val propertyKind = if (isMessages) ArtifactKind.MESSAGE_KEY else ArtifactKind.CONFIGURATION_PROPERTY
        val bundle = addArtifact(
            artifacts,
            file,
            bundleKind,
            file.relativePath,
            file.fileNameWithoutExtension(),
            if (isMessages) "Localization message bundle" else "Application configuration",
            file.relativePath,
            setOf(file.relativePath, file.fileNameWithoutExtension()),
            file.fileNameWithoutExtension(),
        )
        PROPERTY_ENTRY.findAll(file.content).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            val property = addArtifact(
                artifacts,
                file,
                propertyKind,
                "${bundle.semanticKey}#$key",
                key,
                if (isMessages) "Localization message" else value.take(240),
                key,
                setOf(key, "${bundle.semanticKey}#$key"),
                key,
            )
            links += bundle.link(property, RelationshipType.DECLARES, file.locator(key, key))
            when (key) {
                "jmix.rest.services-config" -> links += property.link(
                    value,
                    RelationshipType.CONFIGURES,
                    setOf(ArtifactKind.REST_SERVICE_CONFIG),
                    file.locator(value, key),
                )
                "jmix.rest.queries-config" -> links += property.link(
                    value,
                    RelationshipType.CONFIGURES,
                    setOf(ArtifactKind.REST_QUERY_CONFIG),
                    file.locator(value, key),
                )
            }
            if (!isMessages && SECRET_PROPERTY_KEY.containsMatchIn(key) && isLiteralSecret(value)) {
                diagnostics += diagnostic(
                    reasonCode = "P2_HARDCODED_SECRET_PROPERTY",
                    message = "A credential-like property contains a literal value.",
                    nextStep = "Use an environment variable, secret store, or encrypted deployment configuration.",
                    category = DiagnosticCategory.SECURITY,
                    severity = DiagnosticSeverity.ERROR,
                    locator = file.locator(key, key),
                )
            }
            if (!isMessages && HTTP_PROPERTY_VALUE.containsMatchIn(value) && !isLocalEndpoint(value)) {
                diagnostics += diagnostic(
                    reasonCode = "P2_EXTERNAL_ENDPOINT_CONFIGURATION",
                    message = "An external endpoint is configured here and should be included in integration impact analysis.",
                    nextStep = "Verify environment overrides, TLS, timeouts, authentication, retry, and data-handling requirements.",
                    category = DiagnosticCategory.SOURCE,
                    severity = DiagnosticSeverity.INFO,
                    locator = file.locator(key, key),
                )
            }
        }
    }

    private fun addImplicitSourceRelationships(
        files: List<GraphSourceFile>,
        artifacts: Map<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val sourceArtifacts = artifacts.values
            .filter { it.snapshot.kind in IMPLICIT_RELATIONSHIP_SOURCE_KINDS }
            .groupBy { it.snapshot.sourceLocator.relativePath }
        val entities = artifacts.values.filter { it.snapshot.kind == ArtifactKind.ENTITY }
        val services = artifacts.values.filter { it.snapshot.kind == ArtifactKind.SERVICE }
        val workflows = artifacts.values.filter { it.snapshot.kind == ArtifactKind.WORKFLOW_PROCESS }

        files.forEach { file ->
            val owners = sourceArtifacts[file.relativePath].orEmpty()
            owners.forEach { source ->
                val evidenceText = source.analysisText ?: return@forEach
                entities.filterNot { it.id == source.id }.forEach { entity ->
                    if (entity.aliases.any { alias -> containsSymbol(evidenceText, alias) }) {
                        links += source.link(
                            entity,
                            RelationshipType.USES_ENTITY,
                            file.locator(entity.displayName, source.semanticKey),
                        )
                    }
                }
                services.filterNot { it.id == source.id }.forEach { service ->
                    if (service.aliases.any { alias -> containsSymbol(evidenceText, alias) }) {
                        links += source.link(
                            service,
                            RelationshipType.CALLS_SERVICE,
                            file.locator(service.displayName, source.semanticKey),
                        )
                    }
                }
                workflows.forEach { workflow ->
                    if (workflow.aliases.any { alias -> containsSymbol(evidenceText, alias) }) {
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
        analysisText: String? = null,
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
                analysisText = analysisText,
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
            SERVICE.containsMatchIn(content) ||
                REST_SERVICE_NAME.containsMatchIn(content) ||
                typeName.endsWith("Service") -> ArtifactKind.SERVICE
            declarationKind == "enum" || declarationKind == "enum class" -> ArtifactKind.ENUM
            declarationKind == "data class" || typeName.endsWith("Dto") || typeName.endsWith("DTO") -> ArtifactKind.DTO
            else -> ArtifactKind.SOURCE_TYPE
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

    private fun Element.directChildren(vararg tags: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && tags.any { it == child.localTag() }) {
                result += child
            }
        }
        return result
    }

    private fun Element.ancestors(): Sequence<Element> =
        generateSequence(parentNode) { it.parentNode }.filterIsInstance<Element>()

    private fun Element.inheritedAttr(name: String): String? =
        (sequenceOf(this) + ancestors())
            .map { it.attr(name) }
            .firstOrNull(String::isNotBlank)

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

    private fun GraphSourceFile.classpathResourcePath(): String =
        relativePath.substringAfter("/src/main/resources/", relativePath)

    private fun GraphSourceFile.memberSnippet(anchor: Int): String {
        val safeAnchor = anchor.coerceIn(0, content.length)
        val declarationEnd = (safeAnchor + 1_500).coerceAtMost(content.length)
        val openingBrace = content.indexOf('{', safeAnchor).takeIf { it in safeAnchor until declarationEnd }
            ?: return content.substring(safeAnchor, declarationEnd)
        val maximumEnd = (openingBrace + 12_000).coerceAtMost(content.length)
        var depth = 0
        var index = openingBrace
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        while (index < maximumEnd) {
            val current = content[index]
            val next = content.getOrNull(index + 1)
            when {
                lineComment -> if (current == '\n') lineComment = false
                blockComment -> if (current == '*' && next == '/') {
                    blockComment = false
                    index += 1
                }
                quote != null -> {
                    when {
                        escaped -> escaped = false
                        current == '\\' -> escaped = true
                        current == quote -> quote = null
                    }
                }
                current == '/' && next == '/' -> {
                    lineComment = true
                    index += 1
                }
                current == '/' && next == '*' -> {
                    blockComment = true
                    index += 1
                }
                current == '"' || current == '\'' -> quote = current
                current == '{' -> depth += 1
                current == '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return content.substring(safeAnchor, index + 1)
                    }
                }
            }
            index += 1
        }
        return content.substring(safeAnchor, maximumEnd)
    }

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

    private fun viewTarget(viewId: String?, memberId: String): String =
        if (viewId.isNullOrBlank()) memberId else "$viewId#$memberId"

    private fun policyEntityReference(body: String): String? =
        ENTITY_CLASS_ARGUMENT.find(body)?.groupValues?.get(1)
            ?: ENTITY_NAME_ARGUMENT.find(body)?.groupValues?.get(1)

    private fun stringValues(value: String): List<String> =
        STRING_LITERAL.findAll(value).map { it.groupValues[1] }.toList()

    private fun securityPolicySummary(policyType: String, body: String): String {
        val compactBody = body.trim().replace(Regex("""\s+"""), " ").take(180)
        return if (compactBody.isBlank()) policyType else "$policyType: $compactBody"
    }

    private fun isLiteralSecret(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isNotBlank() &&
            !normalized.startsWith("\${") &&
            !normalized.startsWith("#{") &&
            !normalized.startsWith("ENC(") &&
            !normalized.startsWith("{cipher}")
    }

    private fun isLocalEndpoint(value: String): Boolean =
        value.contains("://localhost", ignoreCase = true) ||
            value.contains("://127.0.0.1") ||
            value.contains("://0.0.0.0")

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
        val analysisText: String?,
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
        val bodyAnchor: Int,
    )

    private data class FieldDeclaration(
        val name: String,
        val type: String,
    )

    private data class MethodDeclaration(
        val name: String,
        val bodyAnchor: Int,
    )

    private companion object {
        val PACKAGE = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        val TYPE = Regex("""\b(enum\s+class|data\s+class|class|interface|object|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val JMIX_ENTITY = Regex("""@(JmixEntity|Entity)\b""")
        val JMIX_ENTITY_NAME = Regex("""@JmixEntity\s*\([^)]*\bname\s*=\s*["']([^"']+)["']""")
        val TABLE_NAME = Regex("""@Table\s*\([^)]*\bname\s*=\s*["']([^"']+)["']""")
        val VIEW_CONTROLLER_ID = Regex("""@ViewController\s*\(\s*["']([^"']+)["']""")
        val VIEW_DESCRIPTOR = Regex("""@ViewDescriptor\s*\(\s*["']([^"']+)["']""")
        val ROUTE = Regex("""@Route\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']""")
        val REST_CONTROLLER = Regex("""@(RestController|Controller)\b""")
        val REST_SERVICE_NAME = Regex("""@RestService\s*\(\s*["']([^"']+)["']""")
        val SPRING_BEAN_NAME = Regex("""@(Service|Component)\s*\(\s*["']([^"']+)["']""")
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
        val EVENT_METHOD = Regex("""@(?:Transactional)?EventListener(?:\s*\([^)]*\))?[\s\S]{0,300}?\b(?:fun\s+|[\w<>,?.\[\]\s]+\s+)([A-Za-z_]\w*)\s*\(""")
        val ENTITY_EVENT_TARGET = Regex(
            """\b(?:EntitySavingEvent|EntityChangedEvent|EntityLoadingEvent|EntityLoadedEvent|EntityDeletingEvent|EntityRemovedEvent)\s*<\s*(?:\?\s+extends\s+)?([A-Za-z_][\w.]*)""",
        )
        val SCHEDULED_METHOD = Regex("""@Scheduled(?:\s*\([^)]*\))?[\s\S]{0,300}?\b(?:fun\s+|[\w<>,?.\[\]\s]+\s+)([A-Za-z_]\w*)\s*\(""")
        val JAVA_FIELD = Regex(
            """(?m)^\s*(?:@\w+(?:\s*\([^)]*\))?\s*)*(?:private|protected|public)\s+(?:final\s+)?([A-Za-z_][\w<>,?.\[\]\s]*)\s+([A-Za-z_]\w*)\s*(?:[;=])""",
        )
        val KOTLIN_FIELD = Regex(
            """(?m)^\s*(?:@\w+(?:\s*\([^)]*\))?\s*)*(?:private\s+|protected\s+|public\s+)?(?:lateinit\s+)?(?:val|var)\s+([A-Za-z_]\w*)\s*:\s*([^=\n]+)""",
        )
        val JAVA_VIEW_COMPONENT = Regex(
            """@ViewComponent(?:\s*\(\s*["']([^"']+)["']\s*\))?[\s\S]{0,180}?\b(?:private|protected|public)\s+(?:final\s+)?[\w<>,?.\[\]]+\s+([A-Za-z_]\w*)""",
        )
        val KOTLIN_VIEW_COMPONENT = Regex(
            """@ViewComponent(?:\s*\(\s*["']([^"']+)["']\s*\))?[\s\S]{0,180}?\b(?:private\s+|protected\s+|public\s+)?(?:lateinit\s+)?var\s+([A-Za-z_]\w*)""",
        )
        val SUBSCRIBE_METHOD = Regex(
            """@Subscribe\s*(?:\(\s*(?:(?:id|value)\s*=\s*)?["']([^"']+)["'][^)]*\))?[\s\S]{0,320}?\b(?:fun\s+|(?:public|protected|private)?\s*[\w<>,?.\[\]\s]+\s+)([A-Za-z_]\w*)\s*\(""",
        )
        val INSTALL_METHOD = Regex(
            """@Install\s*\(\s*(?:to\s*=\s*)?["']([^"']+)["'][^)]*\)[\s\S]{0,320}?\b(?:fun\s+|(?:public|protected|private)?\s*[\w<>,?.\[\]\s]+\s+)([A-Za-z_]\w*)\s*\(""",
        )
        val JAVA_METHOD_DECLARATION = Regex(
            """(?m)^\s*(?:@\w+(?:\s*\([^)]*\))?\s*)*(?:public|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>,?.\[\]\s]+\s+([A-Za-z_]\w*)\s*\(""",
        )
        val KOTLIN_METHOD_DECLARATION = Regex(
            """(?m)^\s*(?:@\w+(?:\s*\([^)]*\))?\s*)*(?:public\s+|protected\s+)?fun\s+([A-Za-z_]\w*)\s*\(""",
        )
        val REST_METHOD_DECLARATION = Regex(
            """@RestMethod(?:\s*\([^)]*\))?[\s\S]{0,320}?\b(?:fun\s+|(?:public|protected|private)?\s*[\w<>,?.\[\]\s]+\s+)([A-Za-z_]\w*)\s*\(""",
        )
        val ROLE_REFERENCES = Regex("""@(RolesAllowed|Secured)\s*\(\s*(?:value\s*=\s*)?([^)]*)\)""")
        val ROLE_CODE = Regex("""@(ResourceRole|RowLevelRole)\s*\([^)]*\bcode\s*=\s*["']([^"']+)["'][^)]*\)""")
        val POLICY_ANNOTATION = Regex(
            """@(EntityPolicy|EntityAttributePolicy|ViewPolicy|MenuPolicy|SpecificPolicy|UiComponentPolicy)\s*\(([\s\S]{0,1400}?)\)""",
        )
        val ENTITY_CLASS_ARGUMENT = Regex("""\bentityClass\s*=\s*([A-Za-z_][\w.]*)\.class""")
        val ENTITY_NAME_ARGUMENT = Regex("""\bentityName\s*=\s*["']([^"']+)["']""")
        val ATTRIBUTES_ARGUMENT = Regex("""\battributes\s*=\s*(\{[^}]*}|["'][^"']+["'])""")
        val VIEW_IDS_ARGUMENT = Regex("""\bviewIds\s*=\s*(\{[^}]*}|["'][^"']+["'])""")
        val MENU_IDS_ARGUMENT = Regex("""\bmenuIds\s*=\s*(\{[^}]*}|["'][^"']+["'])""")
        val STRING_LITERAL = Regex("""["']([^"']+)["']""")
        val UNSAFE_MONEY_FIELD = Regex(
            """(?i)\b(?:Double|Float|double|float)\s+([A-Za-z_]\w*(?:amount|balance|salary|wage|loan|interest|payment|principal|rate|money|total)\w*|(?:amount|balance|salary|wage|loan|interest|payment|principal|rate|money|total)\w*)""",
        )
        val UI_WORKFLOW_TRANSITION = Regex("""\b(setProcessState|setWorkflowState|setStatus|processState\s*=|workflowState\s*=)\b""")
        val UNCONSTRAINED_ACCESS = Regex("""\bUnconstrainedDataManager\b|\.unconstrained\s*\(""")
        val NATIVE_SQL_WRITE = Regex(
            """(?is)(?:createNativeQuery|executeUpdate|JdbcTemplate|NamedParameterJdbcTemplate)[\s\S]{0,240}?\b(?:insert\s+into|update\s+|delete\s+from|merge\s+into|truncate\s+)""",
        )
        val NATIVE_SQL_STATEMENT = Regex(
            """(?is)(?:createNativeQuery|queryForObject|queryForList|update)\s*\(\s*["']\s*(insert\s+into|update|delete\s+from|merge\s+into|truncate)\s+([A-Za-z_][\w$.]*)""",
        )
        val PRINT_STACK_TRACE = Regex("""\.printStackTrace\s*\(""")
        val SWALLOWED_EXCEPTION = Regex(
            """(?s)catch\s*\([^)]*(?:Exception|Throwable)[^)]*\)\s*\{\s*(?://[^\n]*\s*|/\*.*?\*/\s*)?}""",
        )
        val HARDCODED_HTTP_ENDPOINT = Regex("""["']https?://(?!localhost\b|127\.0\.0\.1\b)[^"']+["']""")
        val OUTBOUND_HTTP_CLIENT = Regex("""\b(RestTemplate|WebClient|HttpClient|FeignClient|RestClient)\b""")
        val HTTP_TIMEOUT_CONFIGURATION = Regex("""(?i)\b(connectTimeout|readTimeout|responseTimeout|requestTimeout|callTimeout)\b""")
        val PROPERTY_ENTRY = Regex("""(?m)^\s*([^#!\s][^=:\s]*)\s*[:=]\s*(.*?)\s*$""")
        val SECRET_PROPERTY_KEY = Regex("""(?i)(password|passwd|secret|client-secret|api[-_.]?key|access[-_.]?token|private[-_.]?key)""")
        val HTTP_PROPERTY_VALUE = Regex("""(?i)^https?://""")
        val JPQL_FROM = Regex("""(?i)\bfrom\s+([A-Za-z_][\w.]*)""")
        val JPQL_PARAMETER = Regex(""":([A-Za-z_]\w*)""")
        val METHOD_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "return", "new")
        val DATA_CONTAINER_TAGS = setOf("collection", "instance", "keyValueCollection", "keyValueInstance")
        val SCHEMA_CHANGE_TAGS = setOf(
            "createTable",
            "addColumn",
            "dropColumn",
            "renameColumn",
            "modifyDataType",
            "createIndex",
            "addUniqueConstraint",
            "addForeignKeyConstraint",
            "dropTable",
        )
        val IMPLICIT_RELATIONSHIP_SOURCE_KINDS = setOf(
            ArtifactKind.SOURCE_TYPE,
            ArtifactKind.DTO,
            ArtifactKind.ENUM,
            ArtifactKind.VIEW_CONTROLLER,
            ArtifactKind.REPOSITORY,
            ArtifactKind.SERVICE,
            ArtifactKind.SERVICE_METHOD,
            ArtifactKind.REST_CONTROLLER,
            ArtifactKind.REST_ENDPOINT,
            ArtifactKind.VALIDATOR,
            ArtifactKind.VIEW_HANDLER,
            ArtifactKind.EVENT_LISTENER,
            ArtifactKind.SCHEDULED_JOB,
            ArtifactKind.RESOURCE_ROLE,
            ArtifactKind.ROW_ROLE,
        )
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

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
                SourceLanguage.GROOVY,
                -> indexJvm(
                    file.copy(
                        content = maskJvmComments(file.content),
                        fingerprint = file.fingerprint,
                    ),
                    artifacts,
                    pendingLinks,
                    diagnostics,
                )

                SourceLanguage.XML -> indexXml(file, artifacts, pendingLinks, diagnostics)
                SourceLanguage.PROPERTIES -> indexProperties(file, artifacts, pendingLinks, diagnostics)
                SourceLanguage.YAML -> indexYaml(file, artifacts, pendingLinks, diagnostics)
                SourceLanguage.SQL -> indexSql(file, artifacts, pendingLinks, diagnostics)
                SourceLanguage.MIXED,
                SourceLanguage.UNKNOWN,
                -> indexProjectAsset(file, artifacts)
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
        roleCode(file.content)?.let(aliases::add)
        SPRING_BEAN_NAME.find(file.content)?.groupValues?.get(2)?.let(aliases::add)
        REST_SERVICE_NAME.find(file.content)?.groupValues?.get(1)?.let(aliases::add)
        JMIX_ENTITY_NAME.find(file.content)?.groupValues?.get(1)?.let(aliases::add)
        TABLE_NAME.find(file.content)?.groupValues?.get(1)?.let(aliases::add)
        if (
            primaryKind == ArtifactKind.SERVICE ||
            primaryKind == ArtifactKind.BUSINESS_RULE ||
            primaryKind == ArtifactKind.INTEGRATION_ENDPOINT
        ) {
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

        if (
            primaryKind == ArtifactKind.SERVICE ||
            primaryKind == ArtifactKind.BUSINESS_RULE ||
            primaryKind == ArtifactKind.INTEGRATION_ENDPOINT
        ) {
            indexServiceMethods(file, primary, semanticKey, typeName, artifacts, links)
        }

        if (primaryKind == ArtifactKind.RESOURCE_ROLE || primaryKind == ArtifactKind.ROW_ROLE) {
            indexSecurityPolicies(file, primary, semanticKey, artifacts, links, diagnostics)
            roleBaseReferences(file.content, typeName).forEach { baseRole ->
                links += primary.link(
                    target = baseRole,
                    type = RelationshipType.EXTENDS,
                    expectedKinds = setOf(primaryKind),
                    locator = file.locator(baseRole, semanticKey),
                )
            }
        }

        FLOW_UI_DESCRIPTOR.find(file.content)?.groupValues?.get(1)?.let { descriptor ->
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

    /**
     * Removes line and nested block comments without changing offsets.
     *
     * The semantic fallback parser must not discover types, annotations, SQL,
     * service calls, or security policies from commented-out code. String,
     * character, Java text-block, and Kotlin/Groovy triple-quoted contents are
     * retained because Jmix metadata is frequently declared as annotation
     * arguments. Newlines and total length remain identical so source
     * navigation continues to point at the original file.
     */
    private fun maskJvmComments(content: String): String {
        if ('/' !in content) return content
        val masked = content.toCharArray()
        var index = 0
        var blockDepth = 0
        var lineComment = false
        var quote: Char? = null
        var tripleQuote: Char? = null
        var escaped = false
        while (index < content.length) {
            val current = content[index]
            val next = content.getOrNull(index + 1)
            val third = content.getOrNull(index + 2)
            when {
                lineComment -> {
                    if (current == '\n' || current == '\r') {
                        lineComment = false
                    } else {
                        masked[index] = ' '
                    }
                }
                blockDepth > 0 -> {
                    when {
                        current == '/' && next == '*' -> {
                            masked[index] = ' '
                            masked[index + 1] = ' '
                            blockDepth += 1
                            index += 1
                        }
                        current == '*' && next == '/' -> {
                            masked[index] = ' '
                            masked[index + 1] = ' '
                            blockDepth -= 1
                            index += 1
                        }
                        current != '\n' && current != '\r' -> masked[index] = ' '
                    }
                }
                tripleQuote != null -> {
                    if (current == tripleQuote && next == tripleQuote && third == tripleQuote) {
                        tripleQuote = null
                        index += 2
                    }
                }
                quote != null -> {
                    when {
                        escaped -> escaped = false
                        current == '\\' -> escaped = true
                        current == quote -> quote = null
                    }
                }
                current == '"' && next == '"' && third == '"' -> {
                    tripleQuote = '"'
                    index += 2
                }
                current == '\'' && next == '\'' && third == '\'' -> {
                    tripleQuote = '\''
                    index += 2
                }
                current == '"' || current == '\'' -> quote = current
                current == '/' && next == '/' -> {
                    masked[index] = ' '
                    masked[index + 1] = ' '
                    lineComment = true
                    index += 1
                }
                current == '/' && next == '*' -> {
                    masked[index] = ' '
                    masked[index + 1] = ' '
                    blockDepth = 1
                    index += 1
                }
            }
            index += 1
        }
        return String(masked)
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
            .associateBy { it.groupValues[1] }
        val restServiceName = REST_SERVICE_NAME.find(file.content)?.groupValues?.get(1)
            ?: typeName.replaceFirstChar(Char::lowercase)
        val declarations = buildList {
            JAVA_METHOD_DECLARATION.findAll(file.content).forEach { match ->
                add(
                    MethodDeclaration(
                        match.groupValues[1],
                        match.range.last + 1,
                        TRANSACTIONAL.containsMatchIn(match.value),
                        visualSubflow = false,
                    ),
                )
            }
            KOTLIN_METHOD_DECLARATION.findAll(file.content).forEach { match ->
                add(
                    MethodDeclaration(
                        match.groupValues[1],
                        match.range.last + 1,
                        TRANSACTIONAL.containsMatchIn(match.value),
                        visualSubflow = false,
                    ),
                )
            }
            VISUAL_SUBFLOW_METHOD.findAll(file.content).forEach { match ->
                add(
                    MethodDeclaration(
                        match.groupValues[1],
                        match.range.last + 1,
                        transactional = false,
                        visualSubflow = true,
                    ),
                )
            }
        }.distinctBy(MethodDeclaration::name)
            .filterNot { it.name == typeName || it.name in METHOD_KEYWORDS }

        val declaredArtifacts = linkedMapOf<String, DetectedArtifact>()
        declarations.forEach { declaration ->
            val aliases = service.aliases.mapTo(linkedSetOf()) { alias -> "$alias#${declaration.name}" }
            aliases += "$semanticKey#${declaration.name}"
            val method = addArtifact(
                artifacts = artifacts,
                file = file,
                kind = ArtifactKind.SERVICE_METHOD,
                semanticKey = "$semanticKey#${declaration.name}",
                displayName = declaration.name,
                summary = when {
                    declaration.visualSubflow -> "Reusable visual subflow declared by $typeName"
                    declaration.transactional -> "Transactional service operation declared by $typeName"
                    else -> "Service operation declared by $typeName"
                },
                symbol = "$semanticKey#${declaration.name}",
                aliases = aliases,
                token = declaration.name,
                analysisText = file.memberSnippet(declaration.bodyAnchor),
            )
            declaredArtifacts[declaration.name] = method
            links += service.link(
                method,
                RelationshipType.DECLARES,
                file.locator(declaration.name, "$semanticKey#${declaration.name}"),
            )
            val exposedMatch = exposedMethods[declaration.name]
            if (exposedMatch != null) {
                links += service.link(
                    method,
                    RelationshipType.EXPOSES_SERVICE_METHOD,
                    file.locator(declaration.name, "$semanticKey#${declaration.name}"),
                )
                val parameters = parseMethodParameters(file.content, exposedMatch.range.last)
                val contractKey = "$restServiceName#${declaration.name}@annotation"
                val contract = addArtifact(
                    artifacts = artifacts,
                    file = file,
                    kind = ArtifactKind.REST_SERVICE_METHOD,
                    semanticKey = contractKey,
                    displayName = "$restServiceName.${declaration.name}",
                    summary = "Annotation-exposed REST service method with ${parameters.size} parameter(s)",
                    symbol = "$semanticKey#${declaration.name}",
                    aliases = setOf(contractKey, "$restServiceName#${declaration.name}"),
                    token = declaration.name,
                    analysisText = method.analysisText,
                )
                links += service.link(
                    contract,
                    RelationshipType.EXPOSES_SERVICE_METHOD,
                    file.locator(declaration.name, "$semanticKey#${declaration.name}"),
                )
                links += contract.link(
                    method,
                    RelationshipType.IMPLEMENTED_BY,
                    file.locator(declaration.name, "$semanticKey#${declaration.name}"),
                )
                parameters.forEach { parameter ->
                    val parameterArtifact = addArtifact(
                        artifacts = artifacts,
                        file = file,
                        kind = ArtifactKind.CONTRACT_PARAMETER,
                        semanticKey = "$contractKey#${parameter.name}",
                        displayName = parameter.name,
                        summary = "REST parameter of type ${parameter.type}",
                        symbol = "$semanticKey#${declaration.name}",
                        aliases = setOf("$contractKey#${parameter.name}"),
                        token = parameter.name,
                    )
                    links += contract.link(
                        parameterArtifact,
                        RelationshipType.DECLARES_PARAMETER,
                        file.locator(parameter.name, "$semanticKey#${declaration.name}"),
                    )
                }
            }
        }
        if (declarations.any(MethodDeclaration::visualSubflow)) {
            declarations.forEach { caller ->
                val callerArtifact = declaredArtifacts[caller.name] ?: return@forEach
                val callerBody = callerArtifact.analysisText.orEmpty()
                declarations
                    .filter { it.visualSubflow && it.name != caller.name }
                    .forEach { target ->
                        if (!Regex("""\b${Regex.escape(target.name)}\s*\(""").containsMatchIn(callerBody)) {
                            return@forEach
                        }
                        val targetArtifact = declaredArtifacts[target.name] ?: return@forEach
                        links += callerArtifact.link(
                            targetArtifact,
                            RelationshipType.CALLS_SERVICE,
                            file.locator(target.name, callerArtifact.semanticKey),
                        )
                    }
            }
        }
    }

    private fun parseMethodParameters(content: String, openingParenthesis: Int): List<FieldDeclaration> {
        if (openingParenthesis !in content.indices || content[openingParenthesis] != '(') return emptyList()
        var depth = 1
        var index = openingParenthesis + 1
        var quote: Char? = null
        var escaped = false
        while (index < content.length && depth > 0) {
            val current = content[index]
            when {
                quote != null -> when {
                    escaped -> escaped = false
                    current == '\\' -> escaped = true
                    current == quote -> quote = null
                }
                current == '"' || current == '\'' -> quote = current
                current == '(' || current == '<' || current == '[' -> depth += 1
                current == ')' || current == '>' || current == ']' -> depth -= 1
            }
            index += 1
        }
        if (depth != 0) return emptyList()
        val raw = content.substring(openingParenthesis + 1, index - 1)
        return splitTopLevel(raw).mapNotNull { declaration ->
            val cleaned = declaration
                .replace(Regex("""@\w+(?:\s*\([^)]*\))?\s*"""), "")
                .replace(Regex("""\b(?:final|crossinline|noinline|vararg)\s+"""), "")
                .trim()
            when {
                ':' in cleaned -> {
                    val name = cleaned.substringBefore(':').trim().substringAfterLast(' ')
                    val type = cleaned.substringAfter(':').substringBefore('=').trim()
                    if (name.matches(IDENTIFIER) && type.isNotBlank()) FieldDeclaration(name, type) else null
                }
                else -> {
                    val name = cleaned.substringBefore('=').trim().substringAfterLast(' ')
                    val type = cleaned.substringBefore('=').trim().removeSuffix(name).trim()
                    if (name.matches(IDENTIFIER) && type.isNotBlank()) FieldDeclaration(name, type) else null
                }
            }
        }
    }

    private fun splitTopLevel(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        var escaped = false
        value.forEachIndexed { index, current ->
            when {
                quote != null -> when {
                    escaped -> escaped = false
                    current == '\\' -> escaped = true
                    current == quote -> quote = null
                }
                current == '"' || current == '\'' -> quote = current
                current == '<' || current == '(' || current == '[' || current == '{' -> depth += 1
                current == '>' || current == ')' || current == ']' || current == '}' -> depth = (depth - 1).coerceAtLeast(0)
                current == ',' && depth == 0 -> {
                    result += value.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += value.substring(start)
        return result.map(String::trim).filter(String::isNotBlank)
    }

    private fun indexSecurityPolicies(
        file: GraphSourceFile,
        role: DetectedArtifact,
        semanticKey: String,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val rowPolicyKindsByEntity = linkedMapOf<String, MutableSet<String>>()
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
                offset = match.range.first,
            )
            links += role.link(
                policy,
                RelationshipType.DECLARES,
                file.locatorAt(match.range.first, policy.semanticKey),
            )

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
                "UiComponentPolicy" -> {
                    val viewTarget = UI_COMPONENT_VIEW_CLASS_ARGUMENT.find(body)
                        ?.groupValues
                        ?.get(1)
                        ?: UI_COMPONENT_VIEW_ID_ARGUMENT.find(body)
                            ?.groupValues
                            ?.get(1)
                    if (!viewTarget.isNullOrBlank()) {
                        links += policy.link(
                            viewTarget,
                            RelationshipType.APPLIES_POLICY_TO,
                            setOf(ArtifactKind.VIEW_CONTROLLER),
                            file.locator(viewTarget, policy.semanticKey),
                        )
                    }
                }
                "JpqlRowLevelPolicy", "PredicateRowLevelPolicy" -> {
                    val entity = policyEntityReference(body)
                    if (entity != null) {
                        links += policy.link(
                            entity,
                            RelationshipType.APPLIES_POLICY_TO,
                            setOf(ArtifactKind.ENTITY),
                            file.locator(entity, policy.semanticKey),
                        )
                        rowPolicyKindsByEntity.getOrPut(entity) { linkedSetOf() } += policyType
                    }
                    if (policyType == "JpqlRowLevelPolicy") {
                        val where = stringArgument(body, "where")
                        val join = stringArgument(body, "join")
                        if (where.isNullOrBlank()) {
                            diagnostics += diagnostic(
                                reasonCode = "P2_ROW_POLICY_WHERE_MISSING",
                                message = "A JPQL row-level policy has no visible where clause.",
                                nextStep = "Add a bounded where clause using the {E} placeholder.",
                                category = DiagnosticCategory.SECURITY,
                                severity = DiagnosticSeverity.ERROR,
                                locator = file.locator(policyType, policy.semanticKey),
                            )
                        } else if ("{E}" !in where && (join == null || "{E}" !in join)) {
                            diagnostics += diagnostic(
                                reasonCode = "P2_ROW_POLICY_ENTITY_PLACEHOLDER_MISSING",
                                message = "A JPQL row-level policy does not use the required {E} entity placeholder.",
                                nextStep = "Reference the protected entity through {E} in the where or join clause.",
                                category = DiagnosticCategory.SECURITY,
                                severity = DiagnosticSeverity.WARNING,
                                locator = file.locator(policyType, policy.semanticKey),
                            )
                        }
                    }
                }
            }

            if (containsWildcardGrant(policyType, body)) {
                diagnostics += diagnostic(
                    reasonCode = "P2_SECURITY_WILDCARD_POLICY",
                    message = "$policyType grants wildcard access and should be reviewed as an enterprise boundary.",
                    nextStep = "Prefer narrowly scoped policies or document why unrestricted access is required.",
                    category = DiagnosticCategory.SECURITY,
                    severity = DiagnosticSeverity.WARNING,
                    locator = file.locator(policyType, policy.semanticKey),
                )
            }
        }

        if (role.snapshot.kind == ArtifactKind.ROW_ROLE) {
            if (rowPolicyKindsByEntity.isEmpty()) {
                diagnostics += diagnostic(
                    reasonCode = "P2_ROW_ROLE_EMPTY",
                    message = "A row-level role declares no JPQL or predicate policies.",
                    nextStep = "Add row-level policies or remove the empty role before assigning it.",
                    category = DiagnosticCategory.SECURITY,
                    severity = DiagnosticSeverity.WARNING,
                    locator = role.snapshot.sourceLocator,
                )
            }
            rowPolicyKindsByEntity.forEach { (entity, policyKinds) ->
                if ("JpqlRowLevelPolicy" in policyKinds && "PredicateRowLevelPolicy" !in policyKinds) {
                    diagnostics += diagnostic(
                        reasonCode = "P2_ROW_POLICY_NESTED_GRAPH_COVERAGE",
                        message = "$entity has JPQL row filtering but no predicate policy for nested object graphs.",
                        nextStep = "Review whether the entity can be loaded as a nested collection; add a READ predicate when required.",
                        category = DiagnosticCategory.SECURITY,
                        severity = DiagnosticSeverity.WARNING,
                        locator = role.snapshot.sourceLocator,
                    )
                }
                if ("PredicateRowLevelPolicy" in policyKinds && "JpqlRowLevelPolicy" !in policyKinds) {
                    diagnostics += diagnostic(
                        reasonCode = "P2_ROW_POLICY_ROOT_QUERY_COVERAGE",
                        message = "$entity has predicate filtering but no JPQL policy for efficient root queries.",
                        nextStep = "Add a matching JPQL policy when the entity is loaded as a root.",
                        category = DiagnosticCategory.SECURITY,
                        severity = DiagnosticSeverity.WARNING,
                        locator = role.snapshot.sourceLocator,
                    )
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
            "view", "fragment" -> indexViewXml(file, root, artifacts, links)
            "menu-config", "menu" -> indexMenuXml(file, root, artifacts, links)
            "fetchPlans", "fetch-plans" -> indexFetchPlans(file, root, artifacts)
            "databaseChangeLog" -> indexLiquibase(file, root, artifacts, links)
            "definitions" -> if (
                root.namespaceURI.orEmpty().contains("/DMN/", ignoreCase = true) ||
                root.descendants("decisionTable").isNotEmpty()
            ) {
                indexDmn(file, root, artifacts, links)
            } else {
                indexWorkflow(file, root, artifacts, links)
            }
            "jasperReport" -> indexReportTemplate(file, root, artifacts, links)
            "services" -> if (root.namespaceURI.orEmpty().contains("/rest/services")) {
                indexRestServices(file, root, artifacts, links, diagnostics)
            }
            "queries" -> if (root.namespaceURI.orEmpty().contains("/rest/queries")) {
                indexRestQueries(file, root, artifacts, links, diagnostics)
            }
        }
    }

    private fun indexReportTemplate(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val reportName = root.attr("name").ifBlank { file.fileNameWithoutExtension() }
        val report = addArtifact(
            artifacts,
            file,
            ArtifactKind.REPORT_TEMPLATE,
            file.relativePath,
            reportName,
            "JasperReports template",
            reportName,
            setOf(reportName, file.relativePath, file.fileNameWithoutExtension()),
            reportName,
        )
        root.descendants("queryString").forEachIndexed { index, queryElement ->
            val queryText = queryElement.textContent.trim().replace(Regex("""\s+"""), " ")
            if (queryText.isBlank()) return@forEachIndexed
            val query = addArtifact(
                artifacts,
                file,
                ArtifactKind.REPORT_QUERY,
                "${file.relativePath}#report-query-${index + 1}",
                "report-query-${index + 1}",
                queryText.take(240),
                "${file.relativePath}#report-query-${index + 1}",
                setOf("${file.relativePath}#report-query-${index + 1}"),
                queryText.take(80),
            )
            links += report.link(
                query,
                RelationshipType.EXECUTES_QUERY,
                file.locator(queryText.take(80), reportName),
            )
            JPQL_FROM.find(queryText)?.groupValues?.get(1)?.let { entity ->
                links += query.link(
                    entity,
                    RelationshipType.LOADS_ENTITY,
                    setOf(ArtifactKind.ENTITY, ArtifactKind.DTO),
                    file.locator(entity.substringAfterLast('.'), reportName),
                )
            }
        }
    }

    private fun indexProjectAsset(
        file: GraphSourceFile,
        artifacts: MutableMap<String, DetectedArtifact>,
    ) {
        val extension = file.relativePath.substringAfterLast('.', "").lowercase()
        val kind = when (extension) {
            "css", "scss", "sass", "less" -> ArtifactKind.THEME_ASSET
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "html" -> ArtifactKind.FRONTEND_ASSET
            "ftl", "freemarker" -> ArtifactKind.REPORT_TEMPLATE
            "json" -> ArtifactKind.CONFIGURATION_FILE
            else -> return
        }
        addArtifact(
            artifacts,
            file,
            kind,
            file.relativePath,
            file.relativePath.substringAfterLast('/'),
            when (kind) {
                ArtifactKind.THEME_ASSET -> "Theme stylesheet"
                ArtifactKind.FRONTEND_ASSET -> "Frontend source or template"
                ArtifactKind.REPORT_TEMPLATE -> "Report template"
                else -> "JSON configuration"
            },
            file.relativePath,
            setOf(file.relativePath, file.relativePath.substringAfterLast('/')),
            file.relativePath.substringAfterLast('/'),
        )
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
            val ownerId = element.takeIf { it.localTag() == "action" }
                ?.ancestors()
                ?.firstOrNull { ancestor ->
                    ancestor.localTag() != "actions" &&
                        ancestor.localTag() !in NON_COMPONENT_VIEW_TAGS &&
                        ancestor.attr("id").isNotBlank()
                }
                ?.attr("id")
            val componentPath = ownerId?.let { "$it.$id" } ?: id
            val component = addArtifact(
                artifacts,
                file,
                if (element.localTag() == "action") ArtifactKind.UI_ACTION else ArtifactKind.UI_COMPONENT,
                "$viewId#$componentPath",
                componentPath,
                element.localTag(),
                componentPath,
                setOf("$viewId#$componentPath", componentPath, id),
                id,
            )
            links += view.link(component, RelationshipType.DECLARES, file.locator(id, id))
            if (element.localTag() == "fragment") {
                element.attr("class").takeIf(String::isNotBlank)?.let { controllerClass ->
                    links += component.link(
                        controllerClass,
                        RelationshipType.IMPLEMENTED_BY,
                        setOf(ArtifactKind.VIEW_CONTROLLER),
                        file.locator(controllerClass, component.semanticKey),
                    )
                }
            }
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
        var nodeIndex = 0
        fun visit(parent: DetectedArtifact, parentElement: Element, ancestry: String) {
            parentElement.directChildren("menu", "item", "separator").forEachIndexed { childIndex, element ->
                val tag = element.localTag()
                nodeIndex += 1
                val declaredId = element.attr("id")
                val viewId = element.attr("view")
                val bean = element.attr("bean")
                val stableId = declaredId.ifBlank {
                    viewId.ifBlank {
                        bean.takeIf(String::isNotBlank)?.let { "$it#${element.attr("beanMethod")}" }
                            ?: "$tag-${nodeIndex}"
                    }
                }
                val semanticPath = "$ancestry/$childIndex:$stableId"
                val item = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.MENU_ITEM,
                    "$menuKey#$semanticPath",
                    stableId,
                    when {
                        tag == "menu" -> "Menu container"
                        tag == "separator" -> "Menu separator"
                        bean.isNotBlank() -> "Bean menu action"
                        else -> "View menu item"
                    },
                    stableId,
                    setOf("$menuKey#$stableId", stableId, semanticPath),
                    declaredId.ifBlank { tag },
                )
                links += menu.link(item, RelationshipType.DECLARES, file.locator(stableId, stableId))
                if (parent != menu) {
                    links += parent.link(item, RelationshipType.DECLARES, file.locator(stableId, stableId))
                }
                viewId.takeIf(String::isNotBlank)?.let { targetViewId ->
                    links += item.link(
                        targetViewId,
                        RelationshipType.NAVIGATES_TO,
                        setOf(ArtifactKind.VIEW_DESCRIPTOR),
                        file.locator(targetViewId, stableId),
                    )
                }
                if (tag == "menu") {
                    visit(item, element, semanticPath)
                }
            }
        }
        visit(menu, root, "")
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
        (root.descendants("include").map { it to false } +
            root.descendants("includeAll").map { it to true })
            .forEachIndexed { index, (element, includeAll) ->
            val included = element.attr(if (includeAll) "path" else "file")
                .ifBlank { "include-${index + 1}" }
            val includeArtifact = addArtifact(
                artifacts,
                file,
                ArtifactKind.LIQUIBASE_INCLUDE,
                "${file.relativePath}#${if (includeAll) "all:" else ""}$included",
                included,
                if (includeAll) "Liquibase includeAll directory" else "Liquibase include",
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
                if (
                    node.localTag() == "serviceTask" &&
                    node.attr("flowable:type").equals("dmn", ignoreCase = true)
                ) {
                    node.descendants("field")
                        .firstOrNull { it.attr("name") == "decisionTableReferenceKey" }
                        ?.descendants("string")
                        ?.firstOrNull()
                        ?.textContent
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let { decisionKey ->
                            links += state.link(
                                decisionKey,
                                RelationshipType.EVALUATES_DECISION,
                                setOf(ArtifactKind.DECISION_TABLE),
                                file.locator(decisionKey, nodeId),
                            )
                        }
                }
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

    private fun indexDmn(
        file: GraphSourceFile,
        root: Element,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        root.descendants("decision").forEachIndexed { decisionIndex, decisionElement ->
            val key = decisionElement.attr("id").ifBlank { "decision-${decisionIndex + 1}" }
            val tableElement = decisionElement.descendants("decisionTable").firstOrNull()
                ?: return@forEachIndexed
            val hitPolicy = tableElement.attr("hitPolicy").ifBlank { "UNIQUE" }
            val aggregation = tableElement.attr("aggregation").takeIf(String::isNotBlank)
            val decision = addArtifact(
                artifacts,
                file,
                ArtifactKind.DECISION_TABLE,
                key,
                decisionElement.attr("name").ifBlank { key },
                buildString {
                    append("DMN decision table · ").append(hitPolicy)
                    aggregation?.let { append(" / ").append(it) }
                },
                key,
                setOf(key, decisionElement.attr("name").ifBlank { key }),
                key,
            )
            tableElement.directChildren("input").forEachIndexed { index, input ->
                val id = input.attr("id").ifBlank { "input-${index + 1}" }
                val variable = input.descendants("text").firstOrNull()?.textContent?.trim().orEmpty()
                val column = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.DECISION_INPUT,
                    "$key#input:$id",
                    input.attr("label").ifBlank { variable.ifBlank { id } },
                    "DMN input · ${input.descendants("inputExpression").firstOrNull()?.attr("typeRef").orEmpty()}",
                    id,
                    setOf("$key#input:$id", id, variable),
                    id,
                )
                links += decision.link(column, RelationshipType.DECLARES, file.locator(id, key))
            }
            tableElement.directChildren("output").forEachIndexed { index, output ->
                val id = output.attr("id").ifBlank { "output-${index + 1}" }
                val variable = output.attr("name")
                val column = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.DECISION_OUTPUT,
                    "$key#output:$id",
                    output.attr("label").ifBlank { variable.ifBlank { id } },
                    "DMN output · ${output.attr("typeRef")}",
                    id,
                    setOf("$key#output:$id", id, variable),
                    id,
                )
                links += decision.link(column, RelationshipType.DECLARES, file.locator(id, key))
            }
            tableElement.directChildren("rule").forEachIndexed { index, ruleElement ->
                val id = ruleElement.attr("id").ifBlank { "rule-${index + 1}" }
                val description = ruleElement.directChildren("description")
                    .firstOrNull()?.textContent?.trim().orEmpty()
                val rule = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.DECISION_RULE,
                    "$key#rule:$id",
                    description.ifBlank { id },
                    "DMN rule ${index + 1}",
                    id,
                    setOf("$key#rule:$id", id),
                    id,
                )
                links += decision.link(rule, RelationshipType.DECLARES, file.locator(id, key))
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
            indexIntegrationProperty(file, property, key, value, artifacts, links)
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

    private fun indexYaml(
        file: GraphSourceFile,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val configuration = addArtifact(
            artifacts,
            file,
            ArtifactKind.CONFIGURATION_FILE,
            file.relativePath,
            file.fileNameWithoutExtension(),
            "YAML application or module configuration",
            file.relativePath,
            setOf(file.relativePath, file.fileNameWithoutExtension()),
            file.fileNameWithoutExtension(),
        )
        val parsed = parseYamlEntries(file)
        parsed.entries.forEach { entry ->
            val property = addArtifact(
                artifacts,
                file,
                ArtifactKind.CONFIGURATION_PROPERTY,
                "${file.relativePath}#${entry.key}",
                entry.key,
                entry.value.take(240),
                entry.key,
                setOf(entry.key, "${file.relativePath}#${entry.key}"),
                entry.key.substringAfterLast('.'),
                offset = entry.offset,
            )
            links += configuration.link(
                property,
                RelationshipType.DECLARES,
                file.locatorAt(entry.offset, entry.key),
            )
            when (entry.key) {
                "jmix.rest.services-config" -> links += property.link(
                    entry.value,
                    RelationshipType.CONFIGURES,
                    setOf(ArtifactKind.REST_SERVICE_CONFIG),
                    file.locatorAt(entry.offset, entry.key),
                )
                "jmix.rest.queries-config" -> links += property.link(
                    entry.value,
                    RelationshipType.CONFIGURES,
                    setOf(ArtifactKind.REST_QUERY_CONFIG),
                    file.locatorAt(entry.offset, entry.key),
                )
            }
            indexIntegrationProperty(file, property, entry.key, entry.value, artifacts, links)
            if (SECRET_PROPERTY_KEY.containsMatchIn(entry.key) && isLiteralSecret(entry.value)) {
                diagnostics += diagnostic(
                    reasonCode = "P2_HARDCODED_SECRET_PROPERTY",
                    message = "A credential-like YAML property contains a literal value.",
                    nextStep = "Use an environment variable, secret store, or encrypted deployment configuration.",
                    category = DiagnosticCategory.SECURITY,
                    severity = DiagnosticSeverity.ERROR,
                    locator = file.locatorAt(entry.offset, entry.key),
                )
            }
            if (HTTP_PROPERTY_VALUE.containsMatchIn(entry.value) && !isLocalEndpoint(entry.value)) {
                diagnostics += diagnostic(
                    reasonCode = "P2_EXTERNAL_ENDPOINT_CONFIGURATION",
                    message = "An external endpoint is configured here and should be included in integration impact analysis.",
                    nextStep = "Verify environment overrides, TLS, timeouts, authentication, retry, and data-handling requirements.",
                    category = DiagnosticCategory.SOURCE,
                    severity = DiagnosticSeverity.INFO,
                    locator = file.locatorAt(entry.offset, entry.key),
                )
            }
        }
        if (parsed.unsupportedOffset != null) {
            diagnostics += diagnostic(
                reasonCode = "P2_YAML_PARTIAL",
                message = "A YAML construct could not be represented safely in the semantic property map.",
                nextStep = "Open the file natively and remove syntax errors; anchors, tags, or complex flow structures require an imported YAML parser.",
                category = DiagnosticCategory.SOURCE,
                severity = DiagnosticSeverity.ERROR,
                locator = file.locatorAt(parsed.unsupportedOffset, file.relativePath),
            )
        }
    }

    private fun indexIntegrationProperty(
        file: GraphSourceFile,
        property: DetectedArtifact,
        key: String,
        value: String,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
    ) {
        val normalizedKey = key.lowercase()
        val externalEndpoint = HTTP_PROPERTY_VALUE.find(value)
            ?.value
            ?.takeUnless(::isLocalEndpoint)
        val label = when {
            "spring.security.oauth2" in normalizedKey ||
                "jmix.oidc" in normalizedKey ||
                "keycloak" in normalizedKey -> "Identity and OIDC"
            "spring.mail" in normalizedKey ||
                "jmix.email" in normalizedKey ||
                normalizedKey.startsWith("mail.") ||
                "smtp" in normalizedKey -> "Email and SMTP"
            "google.cloud.storage" in normalizedKey ||
                "gcs" in normalizedKey ||
                "s3" in normalizedKey ||
                "file-storage" in normalizedKey ||
                "filestorage" in normalizedKey -> "File and object storage"
            "sms" in normalizedKey -> "SMS provider"
            "superset" in normalizedKey -> "Apache Superset"
            "libreoffice" in normalizedKey -> "LibreOffice"
            "report" in normalizedKey -> "Reporting"
            "quartz" in normalizedKey -> "Quartz scheduler"
            externalEndpoint != null -> "External HTTP endpoint"
            else -> return
        }
        val integration = addArtifact(
            artifacts,
            file,
            ArtifactKind.INTEGRATION_ENDPOINT,
            "${file.relativePath}#integration:$label",
            label,
            externalEndpoint
                ?.let(::redactEndpoint)
                ?.let { "Configured endpoint: $it" }
                ?: "Integration configuration",
            label,
            setOf(label, key, "${file.relativePath}#integration:$label"),
            key,
        )
        links += property.link(
            integration,
            RelationshipType.CONFIGURES,
            file.locator(key, key),
        )
    }

    private fun redactEndpoint(value: String): String =
        value.substringBefore('?')
            .substringBefore('#')
            .replace(Regex("""(https?://)[^/@\s]+@"""), "\$1<redacted>@")

    private fun parseYamlEntries(file: GraphSourceFile): ParsedYaml {
        val entries = mutableListOf<YamlEntry>()
        val stack = mutableListOf<Pair<Int, String>>()
        var unsupportedOffset: Int? = null
        var offset = 0
        var blockScalarIndent: Int? = null
        file.content.lineSequence().forEach { rawLine ->
            val lineOffset = offset
            offset += rawLine.length + 1
            if (rawLine.isBlank() || rawLine.trimStart().startsWith('#')) return@forEach
            val indentText = rawLine.takeWhile(Char::isWhitespace)
            if ('\t' in indentText) {
                unsupportedOffset = unsupportedOffset ?: lineOffset
                return@forEach
            }
            val indent = indentText.length
            blockScalarIndent?.let { activeIndent ->
                if (indent > activeIndent) return@forEach
                blockScalarIndent = null
            }
            val content = stripYamlComment(rawLine.drop(indent)).trimEnd()
            if (content.isBlank() || content == "---" || content == "...") {
                if (content == "---") stack.clear()
                return@forEach
            }
            if (content.startsWith("- ")) {
                unsupportedOffset = unsupportedOffset ?: lineOffset + indent
                return@forEach
            }
            val separator = yamlSeparator(content)
            if (separator <= 0) {
                unsupportedOffset = unsupportedOffset ?: lineOffset + indent
                return@forEach
            }
            val rawKey = content.substring(0, separator).trim()
            val key = rawKey.trim('"', '\'')
            if (!YAML_KEY.matches(key)) {
                unsupportedOffset = unsupportedOffset ?: lineOffset + indent
                return@forEach
            }
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            val path = (stack.map { it.second } + key).joinToString(".")
            val rawValue = content.substring(separator + 1).trim()
            if (rawValue.isBlank()) {
                stack += indent to key
                return@forEach
            }
            if (rawValue == "|" || rawValue == ">" || rawValue.startsWith("|-") || rawValue.startsWith(">-")) {
                entries += YamlEntry(path, "<block scalar>", lineOffset + indent)
                blockScalarIndent = indent
                return@forEach
            }
            if (rawValue.startsWith('&') || rawValue.startsWith('*') || rawValue.startsWith('!') ||
                rawKey == "<<" || rawValue.count { it == '[' } != rawValue.count { it == ']' } ||
                rawValue.count { it == '{' } != rawValue.count { it == '}' }
            ) {
                unsupportedOffset = unsupportedOffset ?: lineOffset + indent + separator + 1
            }
            entries += YamlEntry(
                key = path,
                value = rawValue.trim().trim('"', '\''),
                offset = lineOffset + indent,
            )
        }
        return ParsedYaml(entries, unsupportedOffset)
    }

    private fun stripYamlComment(value: String): String {
        var singleQuoted = false
        var doubleQuoted = false
        value.forEachIndexed { index, character ->
            when {
                character == '\'' && !doubleQuoted -> singleQuoted = !singleQuoted
                character == '"' && !singleQuoted &&
                    (index == 0 || value[index - 1] != '\\') -> doubleQuoted = !doubleQuoted
                character == '#' && !singleQuoted && !doubleQuoted &&
                    (index == 0 || value[index - 1].isWhitespace()) -> return value.substring(0, index)
            }
        }
        return value
    }

    private fun yamlSeparator(value: String): Int {
        var singleQuoted = false
        var doubleQuoted = false
        value.forEachIndexed { index, character ->
            when {
                character == '\'' && !doubleQuoted -> singleQuoted = !singleQuoted
                character == '"' && !singleQuoted &&
                    (index == 0 || value[index - 1] != '\\') -> doubleQuoted = !doubleQuoted
                character == ':' && !singleQuoted && !doubleQuoted &&
                    (index == value.lastIndex || value[index + 1].isWhitespace()) -> return index
            }
        }
        return -1
    }

    private fun indexSql(
        file: GraphSourceFile,
        artifacts: MutableMap<String, DetectedArtifact>,
        links: MutableList<PendingLink>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val formattedLiquibase = LIQUIBASE_FORMATTED_SQL.containsMatchIn(file.content)
        val root = addArtifact(
            artifacts,
            file,
            if (formattedLiquibase) ArtifactKind.LIQUIBASE_ROOT else ArtifactKind.CONFIGURATION_FILE,
            file.relativePath,
            file.fileNameWithoutExtension(),
            if (formattedLiquibase) "Liquibase formatted SQL changelog" else "SQL resource",
            file.relativePath,
            setOf(file.relativePath, file.fileNameWithoutExtension()),
            file.fileNameWithoutExtension(),
        )
        LIQUIBASE_SQL_INCLUDE.findAll(file.content).forEachIndexed { index, match ->
            val included = match.groupValues[1]
            val include = addArtifact(
                artifacts,
                file,
                ArtifactKind.LIQUIBASE_INCLUDE,
                "${file.relativePath}#include:$index:$included",
                included,
                "Liquibase formatted SQL include",
                included,
                setOf(included),
                included,
                offset = match.range.first,
            )
            links += root.link(include, RelationshipType.INCLUDES_CHANGELOG, file.locatorAt(match.range.first, included))
        }
        val changeSets = LIQUIBASE_SQL_CHANGESET.findAll(file.content).toList()
        if (formattedLiquibase && changeSets.isEmpty()) {
            diagnostics += diagnostic(
                reasonCode = "P2_LIQUIBASE_SQL_MALFORMED",
                message = "A formatted SQL changelog has no valid --changeset author:id declaration.",
                nextStep = "Add a valid Liquibase changeset header or remove the formatted-SQL marker.",
                category = DiagnosticCategory.SOURCE,
                severity = DiagnosticSeverity.ERROR,
                locator = file.locator(),
            )
        }
        val scopes = if (changeSets.isEmpty()) {
            listOf(SqlScope(root, 0, file.content.length))
        } else {
            changeSets.mapIndexed { index, match ->
                val author = match.groupValues[1]
                val id = match.groupValues[2]
                val changeSet = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.LIQUIBASE_CHANGESET,
                    "${file.relativePath}#$author:$id",
                    id,
                    "Liquibase SQL changeset by $author",
                    "$author:$id",
                    setOf(id, "$author:$id", "${file.relativePath}#$id"),
                    match.value,
                    offset = match.range.first,
                )
                links += root.link(
                    changeSet,
                    RelationshipType.DECLARES,
                    file.locatorAt(match.range.first, "$author:$id"),
                )
                SqlScope(
                    owner = changeSet,
                    start = match.range.last + 1,
                    end = changeSets.getOrNull(index + 1)?.range?.first ?: file.content.length,
                )
            }
        }
        scopes.forEach { scope ->
            val sql = file.content.substring(scope.start, scope.end)
            SQL_TABLE_OPERATION.findAll(sql).forEachIndexed { index, match ->
                val operation = match.groupValues[1].uppercase().replace(Regex("""\s+"""), " ")
                val table = match.groupValues[2].trim('"', '`', '[', ']')
                val absoluteOffset = scope.start + match.range.first
                val schemaObject = addArtifact(
                    artifacts,
                    file,
                    ArtifactKind.SCHEMA_OBJECT,
                    "${scope.owner.semanticKey}#$operation:$table:$index",
                    table,
                    "$operation in ${scope.owner.displayName}",
                    table,
                    setOf(table),
                    table,
                    offset = absoluteOffset,
                )
                links += scope.owner.link(
                    schemaObject,
                    RelationshipType.MIGRATES,
                    file.locatorAt(absoluteOffset, table),
                )
                links += schemaObject.link(
                    table,
                    RelationshipType.MIGRATES,
                    setOf(ArtifactKind.ENTITY),
                    file.locatorAt(absoluteOffset, table),
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
        val services = artifacts.values.filter {
            it.snapshot.kind == ArtifactKind.SERVICE ||
                it.snapshot.kind == ArtifactKind.BUSINESS_RULE ||
                it.snapshot.kind == ArtifactKind.INTEGRATION_ENDPOINT
        }
        val workflows = artifacts.values.filter { it.snapshot.kind == ArtifactKind.WORKFLOW_PROCESS }

        files.forEach { file ->
            val owners = sourceArtifacts[file.relativePath].orEmpty()
            owners.forEach { source ->
                val evidenceText = source.analysisText ?: return@forEach
                referencedArtifacts(source, evidenceText, entities.filterNot { it.id == source.id }).forEach { entity ->
                    if (entity != null) {
                        links += source.link(
                            entity,
                            RelationshipType.USES_ENTITY,
                            file.locator(entity.displayName, source.semanticKey),
                        )
                    } else {
                        val reference = ambiguousReference(evidenceText, entities) ?: return@forEach
                        links += source.link(
                            reference,
                            RelationshipType.USES_ENTITY,
                            setOf(ArtifactKind.ENTITY),
                            file.locator(reference, source.semanticKey),
                        )
                    }
                }
                referencedArtifacts(source, evidenceText, services.filterNot { it.id == source.id }).forEach { service ->
                    if (service != null) {
                        links += source.link(
                            service,
                            RelationshipType.CALLS_SERVICE,
                            file.locator(service.displayName, source.semanticKey),
                        )
                    } else {
                        val reference = ambiguousReference(evidenceText, services) ?: return@forEach
                        links += source.link(
                            reference,
                            RelationshipType.CALLS_SERVICE,
                            setOf(
                                ArtifactKind.SERVICE,
                                ArtifactKind.BUSINESS_RULE,
                                ArtifactKind.INTEGRATION_ENDPOINT,
                            ),
                            file.locator(reference, source.semanticKey),
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

    /**
     * Resolves text-only JVM references conservatively.
     *
     * A large composite build frequently contains the same simple class name in
     * multiple bounded contexts. Fully-qualified references win. Otherwise the
     * source module/build is preferred, and an ambiguous global match stays
     * unresolved instead of creating false impact edges.
     */
    private fun referencedArtifacts(
        source: DetectedArtifact,
        evidenceText: String,
        candidates: List<DetectedArtifact>,
    ): List<DetectedArtifact?> {
        val result = mutableListOf<DetectedArtifact?>()
        candidates.groupBy { normalizeAlias(it.displayName) }.values.forEach { sameSimpleName ->
            val qualified = sameSimpleName.filter { candidate ->
                candidate.semanticKey.contains('.') &&
                    containsQualifiedSymbol(evidenceText, candidate.semanticKey)
            }
            if (qualified.isNotEmpty()) {
                result += qualified
                return@forEach
            }
            if (sameSimpleName.none { containsArtifactReference(evidenceText, it) }) return@forEach
            val preferred = preferredCandidates(source, sameSimpleName)
            result += preferred.singleOrNull()
        }
        return result
    }

    private fun ambiguousReference(
        evidenceText: String,
        candidates: List<DetectedArtifact>,
    ): String? = candidates
        .groupBy { normalizeAlias(it.displayName) }
        .values
        .firstOrNull { group ->
            group.size > 1 &&
                group.any { containsArtifactReference(evidenceText, it) } &&
                group.none { containsQualifiedSymbol(evidenceText, it.semanticKey) }
        }
        ?.first()
        ?.displayName

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
            val source = artifacts[link.sourceId]
            val preferred = source?.let { preferredCandidates(it, candidates) } ?: candidates
            val target = preferred.singleOrNull()
            val linkDiagnostic = when {
                target != null -> null
                preferred.size > 1 -> diagnostic(
                    reasonCode = "P2_RELATIONSHIP_AMBIGUOUS",
                    message = "A relationship target is ambiguous in the nearest module/build scope: ${link.targetRef}.",
                    nextStep = "Use a fully-qualified reference or import the intended owned artifact explicitly.",
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

    private fun preferredCandidates(
        source: DetectedArtifact,
        candidates: List<DetectedArtifact>,
    ): List<DetectedArtifact> {
        if (candidates.size <= 1) return candidates
        val owner = source.snapshot.owner
        val sameModule = candidates.filter {
            it.snapshot.owner.buildId == owner.buildId && it.snapshot.owner.moduleId == owner.moduleId
        }
        if (sameModule.isNotEmpty()) return sameModule
        val sameBuild = candidates.filter { it.snapshot.owner.buildId == owner.buildId }
        return sameBuild.ifEmpty { candidates }
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
        offset: Int? = null,
    ): DetectedArtifact {
        val id = CanonicalDiscoveryJson.artifactId(kind, file.owner.buildId, file.owner.moduleId, semanticKey)
        return artifacts.getOrPut(id) {
            val locator = offset?.let { file.locatorAt(it, symbol) } ?: file.locator(token, symbol)
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
            VIEW_CONTROLLER_ID.containsMatchIn(content) ||
                FRAGMENT_DESCRIPTOR.containsMatchIn(content) -> ArtifactKind.VIEW_CONTROLLER
            REST_CONTROLLER.containsMatchIn(content) -> ArtifactKind.REST_CONTROLLER
            RESOURCE_ROLE.containsMatchIn(content) -> ArtifactKind.RESOURCE_ROLE
            ROW_ROLE.containsMatchIn(content) -> ArtifactKind.ROW_ROLE
            INTEGRATION_CONNECTOR.containsMatchIn(content) -> ArtifactKind.INTEGRATION_ENDPOINT
            typeName.endsWith("Rule") &&
                (SERVICE.containsMatchIn(content) || SPRING_BEAN_NAME.containsMatchIn(content)) ->
                ArtifactKind.BUSINESS_RULE
            REPOSITORY.containsMatchIn(content) || typeName.endsWith("Repository") -> ArtifactKind.REPOSITORY
            VALIDATOR.containsMatchIn(content) || typeName.endsWith("Validator") -> ArtifactKind.VALIDATOR
            SERVICE.containsMatchIn(content) ||
                REST_SERVICE_NAME.containsMatchIn(content) ||
                typeName.endsWith("Service") -> ArtifactKind.SERVICE
            declarationKind == "enum" || declarationKind == "enum class" -> ArtifactKind.ENUM
            declarationKind == "data class" || typeName.endsWith("Dto") || typeName.endsWith("DTO") -> ArtifactKind.DTO
            else -> ArtifactKind.SOURCE_TYPE
        }

    private fun jvmSummary(kind: ArtifactKind, file: GraphSourceFile): String {
        if (kind == ArtifactKind.RESOURCE_ROLE || kind == ArtifactKind.ROW_ROLE) {
            val annotation = if (kind == ArtifactKind.RESOURCE_ROLE) "ResourceRole" else "RowLevelRole"
            val body = ROLE_ANNOTATION.find(file.content)
                ?.takeIf { it.groupValues[1] == annotation }
                ?.groupValues
                ?.get(2)
                .orEmpty()
            val name = stringArgument(body, "name")
            val code = roleCode(file.content)
            val scope = Regex("""\bscope\s*=\s*([^,\n)]+)""").find(body)?.groupValues?.get(1)?.trim()
            val details = buildList {
                name?.let { add("name=$it") }
                code?.let { add("code=$it") }
                scope?.let { add("scope=$it") }
            }
            return if (details.isEmpty()) annotation else "$annotation: ${details.joinToString(", ")}"
        }
        return "${kind.name.lowercase().replace('_', ' ')} from ${file.language.name.lowercase()} source"
    }

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
        return locatorAt(offset, symbol)
    }

    private fun GraphSourceFile.locatorAt(offset: Int?, symbol: String? = null): SourceLocator {
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

    private fun containsQualifiedSymbol(content: String, symbol: String): Boolean {
        if (!symbol.contains('.')) return false
        return Regex("(?<![A-Za-z0-9_$.])${Regex.escape(symbol)}(?![A-Za-z0-9_$.])")
            .containsMatchIn(content)
    }

    private fun containsArtifactReference(content: String, artifact: DetectedArtifact): Boolean =
        artifact.aliases.any { alias -> containsSymbol(content, alias) }

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
        val compactBody = body.trim().replace(Regex("""\s+"""), " ").take(2_200)
        return if (compactBody.isBlank()) policyType else "$policyType: $compactBody"
    }

    private fun roleBaseReferences(content: String, typeName: String): List<String> {
        val escapedType = Regex.escape(typeName)
        val raw = Regex("""\binterface\s+$escapedType\s+(?:extends\s+|:\s*)([^{]+)""")
            .find(content)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return raw.split(',')
            .map { declaration ->
                declaration.trim()
                    .substringBefore('<')
                    .substringBefore('(')
                    .trim()
            }
            .filter { JAVA_TYPE_REFERENCE.matches(it) }
            .distinct()
    }

    private fun roleCode(content: String): String? {
        val annotationBody = ROLE_ANNOTATION.find(content)?.groupValues?.get(2).orEmpty()
        stringArgument(annotationBody, "code")?.let { return it }
        val codeExpression = Regex("""\bcode\s*=\s*([A-Za-z_$][\w$.]*)""")
            .find(annotationBody)
            ?.groupValues
            ?.get(1)
        val constantName = codeExpression?.substringAfterLast('.') ?: "CODE"
        return Regex(
            """(?m)\b(?:String\s+|const\s+val\s+)${Regex.escape(constantName)}\s*(?::\s*String\s*)?=\s*["']([^"']+)["']""",
        ).find(content)?.groupValues?.get(1)
    }

    private fun stringArgument(body: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""")
            .find(body)
            ?.groupValues
            ?.get(1)

    private fun containsWildcardGrant(policyType: String, body: String): Boolean =
        when (policyType) {
            "EntityPolicy" -> policyEntityReference(body) == "*" ||
                Regex("""\bactions\s*=\s*(?:\{[^}]*\bALL\b[^}]*}|[^,\n)]*\bALL\b)""").containsMatchIn(body)
            "EntityAttributePolicy" -> "*" in stringValues(ATTRIBUTES_ARGUMENT.find(body)?.groupValues?.get(1).orEmpty())
            "ViewPolicy" -> "*" in stringValues(VIEW_IDS_ARGUMENT.find(body)?.groupValues?.get(1).orEmpty())
            "MenuPolicy" -> "*" in stringValues(MENU_IDS_ARGUMENT.find(body)?.groupValues?.get(1).orEmpty())
            "SpecificPolicy" -> "*" in stringValues(RESOURCES_ARGUMENT.find(body)?.groupValues?.get(1).orEmpty())
            else -> false
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

    private data class YamlEntry(
        val key: String,
        val value: String,
        val offset: Int,
    )

    private data class ParsedYaml(
        val entries: List<YamlEntry>,
        val unsupportedOffset: Int?,
    )

    private data class SqlScope(
        val owner: DetectedArtifact,
        val start: Int,
        val end: Int,
    )

    private data class MethodDeclaration(
        val name: String,
        val bodyAnchor: Int,
        val transactional: Boolean,
        val visualSubflow: Boolean,
    )

    private companion object {
        val PACKAGE = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        val TYPE = Regex("""\b(enum\s+class|data\s+class|class|interface|object|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val JMIX_ENTITY = Regex("""@(JmixEntity|Entity)\b""")
        val JMIX_ENTITY_NAME = Regex("""@JmixEntity\s*\([^)]*\bname\s*=\s*["']([^"']+)["']""")
        val TABLE_NAME = Regex("""@Table\s*\([^)]*\bname\s*=\s*["']([^"']+)["']""")
        val VIEW_CONTROLLER_ID = Regex("""@ViewController\s*\(\s*["']([^"']+)["']""")
        val FLOW_UI_DESCRIPTOR = Regex(
            """@(?:[\w.]+\.)?(?:ViewDescriptor|FragmentDescriptor)\s*\(\s*(?:(?:value|path)\s*=\s*)?["']([^"']+)["']""",
        )
        val FRAGMENT_DESCRIPTOR =
            Regex("""@(?:[\w.]+\.)?FragmentDescriptor\b""")
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
        val VISUAL_SUBFLOW_METHOD = Regex(
            """(?m)^[ \t]*@SuppressWarnings\([ \t]*["']JVW-VISUAL-SUBFLOW["'][ \t]*\)[ \t]*\r?\n[ \t]*private[ \t]+(?:static[ \t]+)?(?:final[ \t]+)?[\w<>,?.\[\] \t]+[ \t]+([A-Za-z_]\w*)[ \t]*\(""",
        )
        val INTEGRATION_CONNECTOR = Regex(
            """@SuppressWarnings\([ \t]*["']JVW-INTEGRATION-CONNECTOR["'][ \t]*\)""",
        )
        val ROLE_REFERENCES = Regex("""@(RolesAllowed|Secured)\s*\(\s*(?:value\s*=\s*)?([^)]*)\)""")
        val ROLE_ANNOTATION = Regex("""@(ResourceRole|RowLevelRole)\s*\(([\s\S]{0,1200}?)\)""")
        val POLICY_ANNOTATION = Regex(
            """@(EntityPolicy|EntityAttributePolicy|ViewPolicy|MenuPolicy|SpecificPolicy|UiComponentPolicy|JpqlRowLevelPolicy|PredicateRowLevelPolicy)\s*\(([\s\S]{0,2200}?)\)""",
        )
        val ENTITY_CLASS_ARGUMENT = Regex("""\bentityClass\s*=\s*([A-Za-z_][\w.]*)\.class""")
        val ENTITY_NAME_ARGUMENT = Regex("""\bentityName\s*=\s*["']([^"']+)["']""")
        val ATTRIBUTES_ARGUMENT = Regex("""\battributes\s*=\s*(\{[^}]*}|["'][^"']+["'])""")
        val VIEW_IDS_ARGUMENT = Regex("""\bviewIds\s*=\s*(\{[^}]*}|["'][^"']+["'])""")
        val MENU_IDS_ARGUMENT = Regex("""\bmenuIds\s*=\s*(\{[^}]*}|["'][^"']+["'])""")
        val RESOURCES_ARGUMENT = Regex("""\bresources\s*=\s*(\{[^}]*}|["'][^"']+["'])""")
        val UI_COMPONENT_VIEW_CLASS_ARGUMENT =
            Regex("""\bviewClass\s*=\s*([A-Za-z_$][\w$.]*)\s*(?:\.class|::class)""")
        val UI_COMPONENT_VIEW_ID_ARGUMENT =
            Regex("""\bviewId\s*=\s*["']([^"']+)["']""")
        val STRING_LITERAL = Regex("""["']([^"']+)["']""")
        val JAVA_TYPE_REFERENCE = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
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
        val YAML_KEY = Regex("""[A-Za-z0-9_.\-/]+""")
        val LIQUIBASE_FORMATTED_SQL = Regex("""(?im)^\s*--\s*liquibase\s+formatted\s+sql\s*$""")
        val LIQUIBASE_SQL_CHANGESET = Regex(
            """(?im)^[ \t]*--[ \t]*changeset[ \t]+([A-Za-z0-9_.@-]+):([A-Za-z0-9_.-]+)(?:[ \t]+.*)?$""",
        )
        val LIQUIBASE_SQL_INCLUDE = Regex(
            """(?im)^[ \t]*--[ \t]*include[ \t]+file:[ \t]*([^\s]+)(?:[ \t]+.*)?$""",
        )
        val SQL_TABLE_OPERATION = Regex(
            """(?is)\b(create\s+table|alter\s+table|drop\s+table|insert\s+into|update|delete\s+from|merge\s+into|truncate\s+table)\s+(?:if\s+(?:not\s+)?exists\s+)?(["`\[]?[A-Za-z_][\w$.-]*["`\]]?)""",
        )
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
            ArtifactKind.BUSINESS_RULE,
            ArtifactKind.SERVICE,
            ArtifactKind.SERVICE_METHOD,
            ArtifactKind.INTEGRATION_ENDPOINT,
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
            "content",
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
            "transaction",
            "callActivity",
            "intermediateCatchEvent",
            "intermediateThrowEvent",
            "boundaryEvent",
        )
    }
}

data class GraphSourceFile(
    val relativePath: String,
    val content: String,
    val owner: ArtifactOwner,
    val language: SourceLanguage,
    val origin: ArtifactOrigin = if (
        language == SourceLanguage.XML ||
        language == SourceLanguage.PROPERTIES ||
        language == SourceLanguage.YAML ||
        language == SourceLanguage.SQL
    ) {
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

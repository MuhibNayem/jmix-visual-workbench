package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactRelationship
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.DiagnosticSeverity
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.security.SecurityPolicyEffect
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@Service(Service.Level.PROJECT)
class RestApiWorkspaceService(private val project: Project) {
    private val cachedWorkspace = AtomicReference<RestApiWorkspaceResponse?>()

    fun load(forceRefresh: Boolean = false): RestApiWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        if (!forceRefresh) {
            cachedWorkspace.get()
                ?.takeIf { it.graphDigest == graph.snapshotDigest }
                ?.let { return it }
        }
        val security = SecurityWorkspaceService.getInstance(project).source()
        return enrichEditableContracts(RestApiWorkspaceBuilder.build(graph, security)).also(cachedWorkspace::set)
    }

    private fun enrichEditableContracts(workspace: RestApiWorkspaceResponse): RestApiWorkspaceResponse {
        val resolver = ProjectFileResolver.getInstance(project)
        val byPath = workspace.operations
            .filter { it.kind == RestApiOperationKind.QUERY }
            .groupBy { it.sourceLocator.relativePath }
        if (byPath.isEmpty()) return workspace
        val details = mutableMapOf<String, Pair<String?, String?>>()
        byPath.forEach { (path, operations) ->
            val file = resolver.resolveFile(path)?.file ?: return@forEach
            val source = runCatching { String(file.contentsToByteArray(false), file.charset) }.getOrNull()
                ?: return@forEach
            val document = parseRestXml(source) ?: return@forEach
            val queries = document.getElementsByTagNameNS("*", "query")
            for (index in 0 until queries.length) {
                val query = queries.item(index) as? org.w3c.dom.Element ?: continue
                val name = query.getAttribute("name")
                val entity = query.getAttribute("entity")
                val fetchPlan = query.getAttribute("fetchPlan").takeIf(String::isNotBlank)
                val jpqlNodes = query.getElementsByTagNameNS("*", "jpql")
                val jpql = (0 until jpqlNodes.length).asSequence()
                    .mapNotNull { jpqlNodes.item(it) as? org.w3c.dom.Element }
                    .firstOrNull { it.parentNode == query }
                    ?.textContent
                    ?.trim()
                operations.firstOrNull { operation ->
                    operation.path == "/rest/queries/$entity/$name"
                }?.let { details[it.artifactId] = fetchPlan to jpql }
            }
        }
        if (details.isEmpty()) return workspace
        return workspace.copy(
            operations = workspace.operations.map { operation ->
                val detail = details[operation.artifactId] ?: return@map operation
                operation.copy(
                    fetchPlanName = detail.first,
                    queryText = detail.second,
                )
            },
        )
    }

    private fun parseRestXml(source: String): org.w3c.dom.Document? = runCatching {
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
        }.parse(InputSource(StringReader(source)))
    }.getOrNull()

    fun invoke(request: RestApiInvocationRequest): RestApiInvocationResponse {
        val started = System.nanoTime()
        val target = validatedLoopbackTarget(request.baseUrl, request.path)
            ?: return RestApiInvocationResponse.rejected(
                "JVW-REST-INVOKE-TARGET-REJECTED",
                "Only credential-free HTTP(S) loopback targets are accepted.",
            )
        val method = request.method.trim().uppercase()
        if (method !in ALLOWED_METHODS) {
            return RestApiInvocationResponse.rejected(
                "JVW-REST-INVOKE-METHOD-REJECTED",
                "Unsupported HTTP method: $method.",
            )
        }
        val bodyBytes = request.body.toByteArray(Charsets.UTF_8)
        if (bodyBytes.size > MAX_REQUEST_BYTES) {
            return RestApiInvocationResponse.rejected(
                "JVW-REST-INVOKE-BODY-OVERSIZED",
                "The request body exceeds the reviewed ${MAX_REQUEST_BYTES / 1024} KiB limit.",
            )
        }
        val timeoutMillis = request.timeoutMillis.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)
        val builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofMillis(timeoutMillis))
        request.headers.forEach { (rawName, rawValue) ->
            val name = rawName.trim()
            val value = rawValue.trim()
            if (name in ALLOWED_HEADERS && value.isNotBlank() && '\n' !in value && '\r' !in value) {
                builder.header(name, value)
            }
        }
        if ("Accept" !in request.headers) builder.header("Accept", "application/json")
        if (bodyBytes.isNotEmpty() && "Content-Type" !in request.headers) {
            builder.header("Content-Type", "application/json")
        }
        val publisher = if (bodyBytes.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(bodyBytes)
        }
        builder.method(method, publisher)
        return runCatching {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            val responseBytes = response.body()
            val truncated = responseBytes.size > MAX_RESPONSE_BYTES
            val retained = if (truncated) responseBytes.copyOf(MAX_RESPONSE_BYTES) else responseBytes
            RestApiInvocationResponse(
                accepted = true,
                status = response.statusCode(),
                durationMillis = elapsedMillis(started),
                headers = response.headers().map()
                    .filterKeys { it.lowercase() !in SENSITIVE_RESPONSE_HEADERS },
                body = retained.toString(Charsets.UTF_8),
                truncated = truncated,
                errorCode = null,
                message = if (truncated) {
                    "Response truncated at ${MAX_RESPONSE_BYTES / 1024} KiB."
                } else {
                    "Request completed."
                },
            )
        }.getOrElse { error ->
            RestApiInvocationResponse(
                accepted = false,
                status = null,
                durationMillis = elapsedMillis(started),
                headers = emptyMap(),
                body = "",
                truncated = false,
                errorCode = "JVW-REST-INVOKE-FAILED",
                message = error.message ?: "The local Jmix API request failed.",
            )
        }
    }

    private fun validatedLoopbackTarget(baseUrl: String, path: String): URI? {
        val base = runCatching { URI(baseUrl.trim().trimEnd('/')) }.getOrNull() ?: return null
        if (base.scheme !in setOf("http", "https") || base.userInfo != null || base.fragment != null) return null
        val host = base.host ?: return null
        val normalizedHost = host.trim('[', ']').lowercase()
        if (normalizedHost !in LOOPBACK_HOSTS) return null
        val normalizedPath = path.trim()
        if (!normalizedPath.startsWith("/") || '\r' in normalizedPath || '\n' in normalizedPath) return null
        val target = runCatching { URI("${base.toASCIIString()}$normalizedPath") }.getOrNull() ?: return null
        if (target.host != base.host || target.scheme != base.scheme || target.userInfo != null || target.fragment != null) {
            return null
        }
        return target
    }

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    companion object {
        private const val MAX_REQUEST_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MIN_TIMEOUT_MILLIS = 500L
        private const val MAX_TIMEOUT_MILLIS = 60_000L
        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        private val LOOPBACK_HOSTS = setOf(
            "localhost",
            "127.0.0.1",
            "::1",
            "0:0:0:0:0:0:0:1",
        )
        private val ALLOWED_HEADERS = setOf(
            "Accept",
            "Content-Type",
            "Authorization",
            "If-Match",
            "If-None-Match",
            "X-Tenant-Id",
            "X-Idempotency-Key",
            "Idempotency-Key",
        )
        private val SENSITIVE_RESPONSE_HEADERS = setOf("set-cookie", "proxy-authenticate")

        fun getInstance(project: Project): RestApiWorkspaceService =
            project.getService(RestApiWorkspaceService::class.java)
    }
}

internal object RestApiWorkspaceBuilder {
    fun build(
        graph: ApplicationGraphResponse,
        security: org.jmixworkbench.discovery.security.SecurityWorkspaceSnapshot,
    ): RestApiWorkspaceResponse {
        val artifactsById = graph.artifacts.associateBy(ArtifactSnapshot::id)
        val outgoing = graph.relationships.groupBy(ArtifactRelationship::sourceArtifactId)
        val incoming = graph.relationships
            .filter { it.targetArtifactId != null }
            .groupBy { it.targetArtifactId!! }
        val restEnabledRoleIds = security.policies
            .filter { policy ->
                policy.type == "SpecificPolicy" &&
                    policy.effect == SecurityPolicyEffect.GRANT &&
                    (policy.wildcard || "rest.enabled" in policy.resourceExpressions)
            }
            .map { it.roleId }
            .toSet()
        val rolesById = security.roles.associateBy { it.id }
        fun hasRestEnabled(roleId: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
            if (!visited.add(roleId)) return false
            if (roleId in restEnabledRoleIds) return true
            return rolesById[roleId]?.inheritedRoleIds.orEmpty().any { hasRestEnabled(it, visited) }
        }
        val apiRoles = security.roles.filter { role ->
            hasRestEnabled(role.id) && ("API" in role.scopes || "ALL" in role.scopes)
        }

        val properties = graph.artifacts
            .filter { it.kind == ArtifactKind.CONFIGURATION_PROPERTY }
            .groupBy(ArtifactSnapshot::displayName)
        fun propertyValues(name: String): String = properties[name].orEmpty()
            .mapNotNull(ArtifactSnapshot::summary)
            .distinct()
            .joinToString(" · ")
        val authenticatedPatterns = propertyValues("jmix.resource-server.authenticated-url-patterns")
        val anonymousPatterns = propertyValues("jmix.resource-server.anonymous-url-patterns")
        val restProtected = properties["jmix.resource-server.authenticated-url-patterns"].orEmpty()
            .mapNotNull(ArtifactSnapshot::summary)
            .any { "/rest/**" in it || "/rest/" in it }

        val operations = graph.artifacts.mapNotNull { artifact ->
            when (artifact.kind) {
                ArtifactKind.REST_ENDPOINT -> controllerOperation(
                    artifact, artifactsById, outgoing, incoming, graph,
                )
                ArtifactKind.REST_SERVICE_METHOD -> genericOperation(
                    artifact, RestApiOperationKind.SERVICE, artifactsById, outgoing, incoming,
                )
                ArtifactKind.REST_QUERY -> genericOperation(
                    artifact, RestApiOperationKind.QUERY, artifactsById, outgoing, incoming,
                )
                else -> null
            }
        }.sortedWith(compareBy(RestApiOperationSnapshot::kind, RestApiOperationSnapshot::path))

        val configArtifacts = graph.artifacts.filter {
            it.kind == ArtifactKind.REST_SERVICE_CONFIG || it.kind == ArtifactKind.REST_QUERY_CONFIG
        }
        val registeredConfigIds = graph.relationships
            .filter { it.type == RelationshipType.CONFIGURES && it.targetArtifactId != null }
            .mapNotNull(ArtifactRelationship::targetArtifactId)
            .toSet()
        val configs = configArtifacts.map { artifact ->
            RestApiConfigSnapshot(
                artifactId = artifact.id,
                kind = if (artifact.kind == ArtifactKind.REST_SERVICE_CONFIG) "SERVICES" else "QUERIES",
                moduleId = artifact.owner.moduleId,
                registered = artifact.id in registeredConfigIds,
                operationCount = operations.count { operation ->
                    incoming[operation.artifactId].orEmpty().any { it.sourceArtifactId == artifact.id }
                },
                sourceLocator = artifact.sourceLocator,
            )
        }

        val findings = buildList {
            if (operations.any { it.kind != RestApiOperationKind.CONTROLLER } && apiRoles.isEmpty()) {
                add(
                    finding(
                        "JVW-REST-ENABLED-API-ROLE-MISSING",
                        DiagnosticSeverity.ERROR,
                        "Generic REST is configured without an API-scoped rest.enabled role",
                        "Create or update an API-scoped resource role with SpecificPolicy(\"rest.enabled\").",
                    ),
                )
            }
            if (operations.isNotEmpty() && !restProtected) {
                add(
                    finding(
                        "JVW-REST-AUTHENTICATED-PATTERN-MISSING",
                        DiagnosticSeverity.ERROR,
                        "REST URL protection is not visible",
                        "Configure jmix.resource-server.authenticated-url-patterns=/rest/** or an equivalent reviewed security chain.",
                    ),
                )
            }
            configs.filterNot(RestApiConfigSnapshot::registered).forEach { config ->
                add(
                    RestApiFindingSnapshot(
                        code = "JVW-REST-CONFIG-NOT-REGISTERED",
                        severity = DiagnosticSeverity.ERROR,
                        title = "REST configuration is not registered",
                        message = "${config.sourceLocator.relativePath} is indexed but not connected from application properties.",
                        remediation = "Register it using jmix.rest.${config.kind.lowercase()}-config.",
                        operationId = null,
                        sourceLocator = config.sourceLocator,
                    ),
                )
            }
            operations.filter { it.kind == RestApiOperationKind.SERVICE }.forEach { operation ->
                add(
                    RestApiFindingSnapshot(
                        code = "JVW-REST-SERVICE-ROW-SECURITY-MANUAL",
                        severity = DiagnosticSeverity.WARNING,
                        title = "Service boundary requires explicit row-security review",
                        message = "${operation.displayName} parameters and results are not automatically checked against row-level policies.",
                        remediation = "Use constrained DataManager and explicit authorization inside the transactional service.",
                        operationId = operation.artifactId,
                        sourceLocator = operation.sourceLocator,
                    ),
                )
            }
            graph.diagnostics.filter { diagnostic ->
                diagnostic.reasonCode.contains("REST") ||
                    diagnostic.reasonCode.contains("TRANSACTION") ||
                    diagnostic.reasonCode == "P2_UNCONSTRAINED_DATA_ACCESS"
            }.forEach { diagnostic ->
                add(
                    RestApiFindingSnapshot(
                        code = diagnostic.reasonCode,
                        severity = diagnostic.severity,
                        title = diagnostic.message.substringBefore('.'),
                        message = diagnostic.message,
                        remediation = diagnostic.nextStep,
                        operationId = operations.firstOrNull {
                            it.sourceLocator.relativePath == diagnostic.sourceLocator?.relativePath
                        }?.artifactId,
                        sourceLocator = diagnostic.sourceLocator,
                    ),
                )
            }
        }.distinctBy { listOf(it.code, it.operationId, it.sourceLocator?.relativePath) }
            .sortedWith(compareBy({ severityRank(it.severity) }, RestApiFindingSnapshot::code))

        return RestApiWorkspaceResponse(
            graphDigest = graph.snapshotDigest,
            operations = operations,
            configs = configs,
            apiRoles = apiRoles.map { role ->
                RestApiRoleSnapshot(role.id, role.name, role.code, role.scopes, role.sourceLocator)
            },
            security = RestApiSecuritySnapshot(
                restProtected = restProtected,
                authenticatedPatterns = authenticatedPatterns,
                anonymousPatterns = anonymousPatterns,
                restEnabledRoleCount = apiRoles.size,
            ),
            openApi = RestApiOpenApiSnapshot(
                genericJsonPath = "/rest/docs/openapi.json",
                detailedJsonPath = "/rest/docs/openapiDetailed.json",
                genericYamlPath = "/rest/docs/openapi.yaml",
                detailedYamlPath = "/rest/docs/openapiDetailed.yaml",
            ),
            findings = findings,
            summary = RestApiWorkspaceSummary(
                controllerCount = operations.count { it.kind == RestApiOperationKind.CONTROLLER },
                serviceCount = operations.count { it.kind == RestApiOperationKind.SERVICE },
                queryCount = operations.count { it.kind == RestApiOperationKind.QUERY },
                errorCount = findings.count { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.BLOCKING },
                warningCount = findings.count { it.severity == DiagnosticSeverity.WARNING },
            ),
        )
    }

    private fun controllerOperation(
        artifact: ArtifactSnapshot,
        artifactsById: Map<String, ArtifactSnapshot>,
        outgoing: Map<String, List<ArtifactRelationship>>,
        incoming: Map<String, List<ArtifactRelationship>>,
        graph: ApplicationGraphResponse,
    ): RestApiOperationSnapshot {
        val displayParts = artifact.displayName.split(' ', limit = 2)
        val controllerIds = incoming[artifact.id].orEmpty()
            .filter { it.type == RelationshipType.EXPOSES_ENDPOINT }
            .map(ArtifactRelationship::sourceArtifactId)
        val relevantLinks = outgoing[artifact.id].orEmpty() + controllerIds.flatMap { outgoing[it].orEmpty() }
        return operation(
            artifact = artifact,
            kind = RestApiOperationKind.CONTROLLER,
            methods = listOf(displayParts.firstOrNull().orEmpty().ifBlank { "REQUEST" }),
            path = displayParts.getOrNull(1).orEmpty().ifBlank { "/" },
            artifactsById = artifactsById,
            outgoing = outgoing,
            incoming = incoming,
            extraLinks = relevantLinks,
            transactionBoundary = if (graph.diagnostics.any {
                    it.reasonCode == "P2_MISSING_TRANSACTION_BOUNDARY" &&
                        it.sourceLocator?.relativePath == artifact.sourceLocator.relativePath
                }
            ) "MISSING" else "IMPLEMENTATION",
            rowSecurity = "IMPLEMENTATION_DEPENDENT",
        )
    }

    private fun genericOperation(
        artifact: ArtifactSnapshot,
        kind: RestApiOperationKind,
        artifactsById: Map<String, ArtifactSnapshot>,
        outgoing: Map<String, List<ArtifactRelationship>>,
        incoming: Map<String, List<ArtifactRelationship>>,
    ): RestApiOperationSnapshot {
        val path = when (kind) {
            RestApiOperationKind.SERVICE -> {
                val service = artifact.displayName.substringBeforeLast('.')
                val method = artifact.displayName.substringAfterLast('.')
                "/rest/services/$service/$method"
            }
            RestApiOperationKind.QUERY -> {
                val entity = artifact.semanticKey.substringBefore(':')
                "/rest/queries/$entity/${artifact.displayName}"
            }
            else -> "/"
        }
        val implementationVisible = outgoing[artifact.id].orEmpty()
            .filter { it.type == RelationshipType.IMPLEMENTED_BY }
            .mapNotNull { it.targetArtifactId?.let(artifactsById::get) }
            .any { it.summary.orEmpty().startsWith("Transactional ") }
        return operation(
            artifact = artifact,
            kind = kind,
            methods = listOf("GET", "POST"),
            path = path,
            artifactsById = artifactsById,
            outgoing = outgoing,
            incoming = incoming,
            extraLinks = outgoing[artifact.id].orEmpty(),
            transactionBoundary = when {
                kind == RestApiOperationKind.QUERY -> "READ_ONLY"
                implementationVisible -> "VISIBLE"
                else -> "IMPLEMENTATION"
            },
            rowSecurity = if (kind == RestApiOperationKind.QUERY) "ENFORCED_READ" else "SERVICE_DEPENDENT",
        )
    }

    private fun operation(
        artifact: ArtifactSnapshot,
        kind: RestApiOperationKind,
        methods: List<String>,
        path: String,
        artifactsById: Map<String, ArtifactSnapshot>,
        outgoing: Map<String, List<ArtifactRelationship>>,
        incoming: Map<String, List<ArtifactRelationship>>,
        extraLinks: List<ArtifactRelationship>,
        transactionBoundary: String,
        rowSecurity: String,
    ): RestApiOperationSnapshot {
        val parameterArtifacts = outgoing[artifact.id].orEmpty()
            .filter { it.type == RelationshipType.DECLARES_PARAMETER }
            .mapNotNull { it.targetArtifactId?.let(artifactsById::get) }
        val entityIds = extraLinks.filter {
            it.type in setOf(RelationshipType.USES_ENTITY, RelationshipType.LOADS_ENTITY, RelationshipType.WRITES_ENTITY)
        }.mapNotNull(ArtifactRelationship::targetArtifactId).distinct()
        val implementationIds = (extraLinks + incoming[artifact.id].orEmpty())
            .filter { it.type == RelationshipType.IMPLEMENTED_BY }
            .mapNotNull(ArtifactRelationship::targetArtifactId)
            .distinct()
        val securedRoleIds = extraLinks
            .filter { it.type == RelationshipType.SECURED_BY }
            .mapNotNull(ArtifactRelationship::targetArtifactId)
            .distinct()
        val fetchPlanName = extraLinks.firstOrNull { it.type == RelationshipType.REFERENCES_FETCH_PLAN }
            ?.targetArtifactId
            ?.let(artifactsById::get)
            ?.displayName
        return RestApiOperationSnapshot(
            artifactId = artifact.id,
            kind = kind,
            displayName = artifact.displayName,
            methods = methods,
            path = path,
            moduleId = artifact.owner.moduleId,
            parameters = parameterArtifacts.map { parameter ->
                RestApiParameterSnapshot(
                    name = parameter.displayName,
                    javaType = parameter.summary?.substringAfter("type ", "java.lang.Object")
                        ?.substringBefore(' ')
                        ?: "java.lang.Object",
                    location = if ("GET" in methods) "QUERY_OR_BODY" else "BODY",
                    required = true,
                    sourceLocator = parameter.sourceLocator,
                )
            },
            entityArtifactIds = entityIds,
            entityNames = entityIds.mapNotNull { artifactsById[it]?.displayName },
            implementationArtifactIds = implementationIds,
            securedRoleIds = securedRoleIds,
            transactionBoundary = transactionBoundary,
            rowSecurity = rowSecurity,
            queryText = artifact.summary?.takeIf { kind == RestApiOperationKind.QUERY },
            fetchPlanName = fetchPlanName,
            sourceLocator = artifact.sourceLocator,
        )
    }

    private fun finding(
        code: String,
        severity: DiagnosticSeverity,
        message: String,
        remediation: String,
    ) = RestApiFindingSnapshot(
        code = code,
        severity = severity,
        title = message,
        message = message,
        remediation = remediation,
        operationId = null,
        sourceLocator = null,
    )

    private fun severityRank(severity: DiagnosticSeverity): Int = when (severity) {
        DiagnosticSeverity.BLOCKING -> 0
        DiagnosticSeverity.ERROR -> 1
        DiagnosticSeverity.WARNING -> 2
        DiagnosticSeverity.INFO -> 3
    }

}

enum class RestApiOperationKind {
    CONTROLLER,
    SERVICE,
    QUERY,
}

data class RestApiWorkspaceResponse(
    val graphDigest: String,
    val operations: List<RestApiOperationSnapshot>,
    val configs: List<RestApiConfigSnapshot>,
    val apiRoles: List<RestApiRoleSnapshot>,
    val security: RestApiSecuritySnapshot,
    val openApi: RestApiOpenApiSnapshot,
    val findings: List<RestApiFindingSnapshot>,
    val summary: RestApiWorkspaceSummary,
)

data class RestApiOperationSnapshot(
    val artifactId: String,
    val kind: RestApiOperationKind,
    val displayName: String,
    val methods: List<String>,
    val path: String,
    val moduleId: String,
    val parameters: List<RestApiParameterSnapshot>,
    val entityArtifactIds: List<String>,
    val entityNames: List<String>,
    val implementationArtifactIds: List<String>,
    val securedRoleIds: List<String>,
    val transactionBoundary: String,
    val rowSecurity: String,
    val queryText: String? = null,
    val fetchPlanName: String? = null,
    val sourceLocator: SourceLocator,
)

data class RestApiParameterSnapshot(
    val name: String,
    val javaType: String,
    val location: String,
    val required: Boolean,
    val sourceLocator: SourceLocator,
)

data class RestApiConfigSnapshot(
    val artifactId: String,
    val kind: String,
    val moduleId: String,
    val registered: Boolean,
    val operationCount: Int,
    val sourceLocator: SourceLocator,
)

data class RestApiRoleSnapshot(
    val id: String,
    val name: String,
    val code: String,
    val scopes: List<String>,
    val sourceLocator: SourceLocator,
)

data class RestApiSecuritySnapshot(
    val restProtected: Boolean,
    val authenticatedPatterns: String,
    val anonymousPatterns: String,
    val restEnabledRoleCount: Int,
)

data class RestApiOpenApiSnapshot(
    val genericJsonPath: String,
    val detailedJsonPath: String,
    val genericYamlPath: String,
    val detailedYamlPath: String,
)

data class RestApiFindingSnapshot(
    val code: String,
    val severity: DiagnosticSeverity,
    val title: String,
    val message: String,
    val remediation: String?,
    val operationId: String?,
    val sourceLocator: SourceLocator?,
)

data class RestApiWorkspaceSummary(
    val controllerCount: Int,
    val serviceCount: Int,
    val queryCount: Int,
    val errorCount: Int,
    val warningCount: Int,
)

data class RestApiInvocationRequest(
    val baseUrl: String,
    val path: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val timeoutMillis: Long = 10_000,
)

data class RestApiInvocationResponse(
    val accepted: Boolean,
    val status: Int?,
    val durationMillis: Long,
    val headers: Map<String, List<String>>,
    val body: String,
    val truncated: Boolean,
    val errorCode: String?,
    val message: String,
) {
    companion object {
        fun rejected(code: String, message: String) = RestApiInvocationResponse(
            accepted = false,
            status = null,
            durationMillis = 0,
            headers = emptyMap(),
            body = "",
            truncated = false,
            errorCode = code,
            message = message,
        )
    }
}

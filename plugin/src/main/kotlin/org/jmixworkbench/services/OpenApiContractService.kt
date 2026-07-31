package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.model.IntegrationHttpMethod
import org.jmixworkbench.model.IntegrationOpenApiBinding
import org.jmixworkbench.model.IntegrationOpenApiOperationModel
import org.jmixworkbench.model.IntegrationOpenApiParameterLocation
import org.jmixworkbench.model.IntegrationOpenApiParameterModel
import org.jmixworkbench.model.IntegrationOpenApiPropertyModel
import org.jmixworkbench.model.IntegrationOpenApiSchemaKind
import org.jmixworkbench.model.IntegrationOpenApiSchemaModel
import org.jmixworkbench.model.IntegrationOpenApiSecurityRequirementModel
import org.jmixworkbench.model.IntegrationOpenApiSecuritySchemeKind
import org.jmixworkbench.model.IntegrationOpenApiSecuritySchemeModel
import java.security.MessageDigest
import java.util.HexFormat
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-owned, network-isolated OpenAPI discovery and operation resolution.
 *
 * Contract files are parsed only from IntelliJ content roots. Parser reference
 * resolution and external-reference validation are disabled, and this service
 * resolves only same-document `#/components/...` references itself. Every
 * selected operation is bound to the exact document SHA-256 and is reconstructed
 * by the backend before preview/apply.
 */
@Service(Service.Level.PROJECT)
class OpenApiContractService(private val project: Project) {
    private val cache = ConcurrentHashMap<String, CachedContract>()

    fun discover(
        destinations: List<IntegrationConnectorDestinationSnapshot>,
        explicitlyReferencedPaths: Set<String> = emptySet(),
        forceRefresh: Boolean = false,
    ): List<OpenApiContractSnapshot> {
        if (forceRefresh) cache.clear()
        val resolver = ProjectFileResolver.getInstance(project)
        val candidates = linkedMapOf<String, Pair<VirtualFile, String?>>()
        var visited = 0
        for (destination in destinations.sortedBy(IntegrationConnectorDestinationSnapshot::moduleId)) {
            if (visited >= MAX_DISCOVERY_FILES) break
            val target = resolver.resolveTarget(destination.resourceRoot) ?: continue
            val root = if (target.relativePath.isBlank()) {
                target.root
            } else {
                target.root.findFileByRelativePath(target.relativePath)
            } ?: continue
            VfsUtilCore.iterateChildrenRecursively(
                root,
                { file ->
                    ProgressManager.checkCanceled()
                    file.name !in IGNORED_DIRECTORIES
                },
                processor@{ file ->
                    ProgressManager.checkCanceled()
                    if (visited++ >= MAX_DISCOVERY_FILES) return@processor false
                    if (!file.isDirectory && isAutomaticCandidate(file)) {
                        resolver.locatorPath(file)?.let { path ->
                            candidates.putIfAbsent(path, file to destination.moduleId)
                        }
                    }
                    true
                },
            )
        }
        explicitlyReferencedPaths.sorted().forEach { path ->
            resolver.resolveFile(path)?.file?.let { file ->
                if (isSupportedFile(file)) candidates.putIfAbsent(path, file to null)
            }
        }
        return candidates.mapNotNull { (path, candidate) ->
            runCatching { inspect(path, candidate.first, candidate.second) }.getOrNull()
        }.sortedWith(
            compareBy<OpenApiContractSnapshot> { it.moduleId.orEmpty() }
                .thenBy(OpenApiContractSnapshot::title)
                .thenBy(OpenApiContractSnapshot::relativePath),
        )
    }

    fun inspectProjectFile(file: VirtualFile): OpenApiContractSnapshot {
        require(ProjectFileResolver.getInstance(project).contains(file)) {
            "OpenAPI contracts must be project-owned files under an IntelliJ content root."
        }
        require(isSupportedFile(file)) {
            "OpenAPI contracts must use .json, .yaml, or .yml."
        }
        val path = requireNotNull(ProjectFileResolver.getInstance(project).locatorPath(file)) {
            "The selected OpenAPI contract has no safe project-relative locator."
        }
        return inspect(path, file, null)
    }

    fun resolve(binding: IntegrationOpenApiBinding): OpenApiContractResolution {
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(binding.relativePath)
            ?: throw IllegalArgumentException(
                "OpenAPI contract '${binding.relativePath}' is no longer available in the project.",
            )
        val contract = inspect(binding.relativePath, resolved.file, null)
        require(contract.documentSha256 == binding.documentSha256) {
            "OpenAPI contract '${binding.relativePath}' changed. Refresh and review the new contract before generation."
        }
        require(contract.specificationVersion == binding.specificationVersion) {
            "OpenAPI specification version changed. Refresh and review the contract."
        }
        val parsed = parseFile(binding.relativePath, resolved.file)
        val operation = parsed.resolve(binding)
        return OpenApiContractResolution(contract, operation)
    }

    private fun inspect(
        path: String,
        file: VirtualFile,
        moduleId: String?,
    ): OpenApiContractSnapshot {
        require(file.length in 1..MAX_DOCUMENT_BYTES) {
            "OpenAPI contract must be between 1 byte and ${MAX_DOCUMENT_BYTES / (1024 * 1024)} MiB."
        }
        val key = file.url
        val cached = cache[key]
        if (
            cached != null &&
            cached.modificationStamp == file.modificationStamp &&
            cached.length == file.length
        ) {
            return cached.snapshot.copy(moduleId = moduleId ?: cached.snapshot.moduleId)
        }
        val parsed = parseFile(path, file)
        val snapshot = parsed.snapshot(moduleId)
        if (cache.size >= MAX_CACHED_CONTRACTS) {
            cache.keys.sorted().firstOrNull()?.let(cache::remove)
        }
        cache[key] = CachedContract(file.modificationStamp, file.length, snapshot)
        return snapshot
    }

    private fun parseFile(path: String, file: VirtualFile): ParsedOpenApiContract {
        require(file.length in 1..MAX_DOCUMENT_BYTES) {
            "OpenAPI contract must be between 1 byte and ${MAX_DOCUMENT_BYTES / (1024 * 1024)} MiB."
        }
        val bytes = file.contentsToByteArray(false)
        return OpenApiContractParser.parse(path, bytes)
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 5L * 1024 * 1024
        private const val MAX_DISCOVERY_FILES = 100_000
        private const val MAX_CACHED_CONTRACTS = 128
        private val SUPPORTED_EXTENSIONS = setOf("json", "yaml", "yml")
        private val IGNORED_DIRECTORIES = setOf(
            ".git",
            ".gradle",
            ".idea",
            "build",
            "out",
            "node_modules",
            "target",
        )

        private fun isSupportedFile(file: VirtualFile): Boolean =
            !file.isDirectory && file.extension?.lowercase() in SUPPORTED_EXTENSIONS

        private fun isAutomaticCandidate(file: VirtualFile): Boolean {
            if (!isSupportedFile(file)) return false
            val name = file.nameWithoutExtension.lowercase()
            val normalizedPath = file.path.replace('\\', '/').lowercase()
            return name == "openapi" ||
                name == "swagger" ||
                "openapi" in name ||
                "api-contract" in name ||
                "api-spec" in name ||
                "/openapi/" in normalizedPath
        }

        fun getInstance(project: Project): OpenApiContractService =
            project.getService(OpenApiContractService::class.java)
    }
}

data class OpenApiContractSnapshot(
    val relativePath: String,
    val documentSha256: String,
    val specificationVersion: String,
    val title: String,
    val apiVersion: String?,
    val moduleId: String?,
    val operations: List<OpenApiOperationSnapshot>,
    val parserMessages: List<String>,
    val issues: List<String>,
    val valid: Boolean,
)

data class OpenApiOperationSnapshot(
    val key: String,
    val operationId: String?,
    val javaMethodName: String,
    val method: IntegrationHttpMethod,
    val path: String,
    val summary: String,
    val tags: List<String>,
    val deprecated: Boolean,
    val requestMediaTypes: List<String>,
    val requestTypeLabel: String?,
    val requestRepresentations: List<OpenApiRepresentationSnapshot>,
    val responses: List<OpenApiResponseSnapshot>,
    val responseTypeLabel: String?,
    val parameters: List<OpenApiParameterSnapshot>,
    val securitySchemes: List<String>,
    val securityRequirements: List<IntegrationOpenApiSecurityRequirementModel> = emptyList(),
    val requestSchemaId: String? = null,
    val responseSchemaId: String? = null,
    val schemas: List<OpenApiSchemaSnapshot> = emptyList(),
    val defaultBinding: IntegrationOpenApiBinding?,
    val supported: Boolean,
    val issues: List<String>,
)

data class OpenApiSchemaSnapshot(
    val id: String,
    val javaName: String,
    val kind: IntegrationOpenApiSchemaKind,
    val typeLabel: String,
    val nullable: Boolean,
    val enumValues: List<String>,
    val itemSchemaId: String?,
    val additionalPropertiesAllowed: Boolean,
    val properties: List<OpenApiSchemaPropertySnapshot>,
)

data class OpenApiSchemaPropertySnapshot(
    val wireName: String,
    val javaName: String,
    val schemaId: String,
    val typeLabel: String,
    val required: Boolean,
    val nullable: Boolean,
    val readOnly: Boolean,
    val writeOnly: Boolean,
)

data class OpenApiResponseSnapshot(
    val status: String,
    val mediaTypes: List<String>,
    val hasBody: Boolean,
    val description: String?,
    val representations: List<OpenApiRepresentationSnapshot> = emptyList(),
)

data class OpenApiRepresentationSnapshot(
    val mediaType: String,
    val typeLabel: String?,
    val supported: Boolean,
    val issue: String? = null,
)

data class OpenApiParameterSnapshot(
    val name: String,
    val javaName: String,
    val location: IntegrationOpenApiParameterLocation,
    val required: Boolean,
    val typeLabel: String,
)

data class OpenApiContractResolution(
    val contract: OpenApiContractSnapshot,
    val operation: IntegrationOpenApiOperationModel,
)

data class OpenApiContractSelectionResponse(
    val selected: Boolean,
    val contract: OpenApiContractSnapshot?,
    val message: String?,
)

private data class CachedContract(
    val modificationStamp: Long,
    val length: Long,
    val snapshot: OpenApiContractSnapshot,
)

internal object OpenApiContractParser {
    private const val MAX_OPERATIONS = 2_000
    private const val MAX_SCHEMAS = 512
    private const val MAX_PROPERTIES = 4_000
    private const val MAX_DEPTH = 48
    private const val MAX_PARSER_MESSAGES = 100
    private const val MAX_MESSAGE_LENGTH = 1_000

    fun parse(relativePath: String, bytes: ByteArray): ParsedOpenApiContract {
        require(bytes.isNotEmpty()) { "OpenAPI contract is empty." }
        require(bytes.size <= 5 * 1024 * 1024) { "OpenAPI contract exceeds the 5 MiB safety limit." }
        val text = bytes.toString(Charsets.UTF_8)
        require('\u0000' !in text) { "OpenAPI contract contains binary NUL characters." }
        val options = ParseOptions().apply {
            isResolve = false
            isResolveFully = false
            isResolveCombinators = false
            isResolveRequestBody = false
            isResolveResponses = false
            isValidateExternalRefs = false
            isValidateInternalRefs = false
            isFlatten = false
            isSafelyResolveURL = true
            remoteRefAllowList = emptyList()
            remoteRefBlockList = listOf("*")
        }
        val result = OpenAPIV3Parser().readContents(text, null, options)
        val openApi = result.openAPI
            ?: throw IllegalArgumentException(
                result.messages?.firstOrNull()?.take(MAX_MESSAGE_LENGTH)
                    ?: "The document is not a valid OpenAPI 3.x contract.",
            )
        val version = openApi.openapi?.trim().orEmpty()
        require(version.startsWith("3.0.") || version.startsWith("3.1.")) {
            "Only OpenAPI 3.0.x and 3.1.x contracts are supported; found '${version.ifBlank { "unknown" }}'."
        }
        val parserMessages = result.messages.orEmpty()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_PARSER_MESSAGES)
            .map { it.take(MAX_MESSAGE_LENGTH) }
        return ParsedOpenApiContract(
            relativePath = relativePath,
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            openApi = openApi,
            parserMessages = parserMessages,
            limits = ParserLimits(MAX_OPERATIONS, MAX_SCHEMAS, MAX_PROPERTIES, MAX_DEPTH),
        ).also(ParsedOpenApiContract::validateEnvelope)
    }
}

internal class ParsedOpenApiContract(
    private val relativePath: String,
    private val sha256: String,
    private val openApi: OpenAPI,
    private val parserMessages: List<String>,
    private val limits: ParserLimits,
) {
    private val components: Components = openApi.components ?: Components()
    private val operationEntries: List<OperationEntry> by lazy { readOperations() }

    fun validateEnvelope() {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && ".." !in relativePath.split('/')) {
            "OpenAPI contract must have a safe project-relative path."
        }
        require(openApi.paths != null && openApi.paths.isNotEmpty()) {
            "OpenAPI contract has no paths."
        }
        require(operationEntries.size <= limits.maxOperations) {
            "OpenAPI contract exceeds the ${limits.maxOperations}-operation safety limit."
        }
        val duplicateOperationIds = operationEntries.mapNotNull { it.operation.operationId?.trim() }
            .filter(String::isNotBlank)
            .groupingBy(String::lowercase)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateOperationIds.isEmpty()) {
            "OpenAPI operationId values must be unique ignoring case: ${duplicateOperationIds.sorted().joinToString()}."
        }
    }

    fun snapshot(moduleId: String?): OpenApiContractSnapshot {
        val issues = mutableListOf<String>()
        val snapshots = operationEntries.map { entry ->
            runCatching { snapshotOperation(entry) }.getOrElse { failure ->
                OpenApiOperationSnapshot(
                    key = "${entry.method.name} ${entry.path}",
                    operationId = entry.operation.operationId?.trim()?.takeIf(String::isNotBlank),
                    javaMethodName = javaMethodName(entry),
                    method = entry.method,
                    path = entry.path,
                    summary = entry.operation.summary?.trim().orEmpty(),
                    tags = entry.operation.tags.orEmpty().map(String::trim).filter(String::isNotBlank).distinct(),
                    deprecated = entry.operation.deprecated == true,
                    requestMediaTypes = emptyList(),
                    requestTypeLabel = null,
                    requestRepresentations = emptyList(),
                    responses = emptyList(),
                    responseTypeLabel = null,
                    parameters = emptyList(),
                    securitySchemes = securitySchemes(entry.operation),
                    defaultBinding = null,
                    supported = false,
                    issues = listOf(failure.message ?: "Operation cannot be represented safely."),
                )
            }
        }
        if (snapshots.none(OpenApiOperationSnapshot::supported)) {
            issues += "No operation can be generated safely from this contract."
        }
        return OpenApiContractSnapshot(
            relativePath = relativePath,
            documentSha256 = sha256,
            specificationVersion = openApi.openapi,
            title = openApi.info?.title?.trim()?.takeIf(String::isNotBlank) ?: relativePath.substringAfterLast('/'),
            apiVersion = openApi.info?.version?.trim()?.takeIf(String::isNotBlank),
            moduleId = moduleId,
            operations = snapshots.sortedWith(
                compareBy<OpenApiOperationSnapshot> { it.tags.firstOrNull().orEmpty() }
                    .thenBy(OpenApiOperationSnapshot::path)
                    .thenBy { it.method.name },
            ),
            parserMessages = parserMessages,
            issues = issues,
            valid = issues.isEmpty() && snapshots.any(OpenApiOperationSnapshot::supported),
        )
    }

    fun resolve(binding: IntegrationOpenApiBinding): IntegrationOpenApiOperationModel {
        require(binding.relativePath == relativePath) { "OpenAPI contract path does not match the selected document." }
        require(binding.documentSha256 == sha256) { "OpenAPI contract digest is stale." }
        require(binding.specificationVersion == openApi.openapi) { "OpenAPI specification version is stale." }
        val candidates = operationEntries.filter { it.method == binding.method && it.path == binding.path }
        val entry = candidates.singleOrNull {
            binding.operationId == null || it.operation.operationId == binding.operationId
        } ?: throw IllegalArgumentException(
            "OpenAPI operation ${binding.method} ${binding.path} is missing or ambiguous.",
        )
        return buildOperation(
            entry = entry,
            requestMediaType = binding.requestMediaType,
            responseStatus = binding.responseStatus,
            responseMediaType = binding.responseMediaType,
        )
    }

    private fun snapshotOperation(entry: OperationEntry): OpenApiOperationSnapshot {
        require(entry.path.startsWith('/') && entry.path.length <= 2_048) {
            "Operation path must start with '/' and be at most 2048 characters."
        }
        require(entry.pathItem.`$ref`.isNullOrBlank()) {
            "Referenced Path Item objects are not generated until their resolution is unambiguous."
        }
        val requestBody = resolveRequestBody(entry.operation.requestBody)
        val requestMediaTypes = requestBody?.content.orEmpty().keys.sorted()
        val responseEntries = resolveResponses(entry.operation)
        val responseSummaries = responseEntries.map { (status, response) ->
            OpenApiResponseSnapshot(
                status = status,
                mediaTypes = response.content.orEmpty().keys.sorted(),
                hasBody = response.content.orEmpty().values.any { it.schema != null },
                description = response.description?.trim()?.take(300),
            )
        }
        require(
            requestMediaTypes.size + responseSummaries.sumOf { it.mediaTypes.size } <=
                MAX_REPRESENTATIONS_PER_OPERATION,
        ) {
            "Operation exposes more than $MAX_REPRESENTATIONS_PER_OPERATION request/response representations."
        }
        val defaultRequestMedia = preferredMediaType(requestMediaTypes)
        val defaultResponse = preferredResponse(responseSummaries)
        val binding = IntegrationOpenApiBinding(
            relativePath = relativePath,
            documentSha256 = sha256,
            specificationVersion = openApi.openapi,
            operationId = entry.operation.operationId?.trim()?.takeIf(String::isNotBlank),
            method = entry.method,
            path = entry.path,
            requestMediaType = defaultRequestMedia,
            responseStatus = defaultResponse?.status,
            responseMediaType = preferredMediaType(defaultResponse?.mediaTypes.orEmpty()),
        )
        val built = buildOperation(
            entry,
            binding.requestMediaType,
            binding.responseStatus,
            binding.responseMediaType,
        )
        val schemaById = built.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val requestRepresentations = requestMediaTypes.map { mediaType ->
            representation(
                entry = entry,
                requestMediaType = mediaType,
                responseStatus = binding.responseStatus,
                responseMediaType = binding.responseMediaType,
                selectedMediaType = mediaType,
                request = true,
            )
        }
        val responses = responseSummaries.map { response ->
            response.copy(
                representations = response.mediaTypes.map { mediaType ->
                    if (!isSuccessfulStatus(response.status)) {
                        OpenApiRepresentationSnapshot(
                            mediaType = mediaType,
                            typeLabel = null,
                            supported = false,
                            issue = "Only explicit successful 2xx responses can be selected for generated return values.",
                        )
                    } else {
                        representation(
                            entry = entry,
                            requestMediaType = binding.requestMediaType,
                            responseStatus = response.status,
                            responseMediaType = mediaType,
                            selectedMediaType = mediaType,
                            request = false,
                        )
                    }
                },
            )
        }
        return OpenApiOperationSnapshot(
            key = "${entry.method.name} ${entry.path}",
            operationId = built.operationId,
            javaMethodName = built.javaMethodName,
            method = entry.method,
            path = entry.path,
            summary = entry.operation.summary?.trim().orEmpty(),
            tags = entry.operation.tags.orEmpty().map(String::trim).filter(String::isNotBlank).distinct(),
            deprecated = built.deprecated,
            requestMediaTypes = requestMediaTypes,
            requestTypeLabel = built.requestSchemaId
                ?.let(schemaById::get)
                ?.let { typeLabel(it, schemaById) },
            requestRepresentations = requestRepresentations,
            responses = responses,
            responseTypeLabel = built.responseSchemaId
                ?.let(schemaById::get)
                ?.let { typeLabel(it, schemaById) },
            parameters = built.parameters.map { parameter ->
                OpenApiParameterSnapshot(
                    name = parameter.wireName,
                    javaName = parameter.javaName,
                    location = parameter.location,
                    required = parameter.required,
                    typeLabel = typeLabel(requireNotNull(schemaById[parameter.schemaId]), schemaById),
                )
            },
            securitySchemes = built.securitySchemes,
            securityRequirements = built.securityRequirements,
            requestSchemaId = built.requestSchemaId,
            responseSchemaId = built.responseSchemaId,
            schemas = built.schemas.map { schema ->
                OpenApiSchemaSnapshot(
                    id = schema.id,
                    javaName = schema.javaName,
                    kind = schema.kind,
                    typeLabel = typeLabel(schema, schemaById),
                    nullable = schema.nullable,
                    enumValues = schema.enumValues,
                    itemSchemaId = schema.itemSchemaId,
                    additionalPropertiesAllowed = schema.additionalPropertiesAllowed,
                    properties = schema.properties.map { property ->
                        OpenApiSchemaPropertySnapshot(
                            wireName = property.wireName,
                            javaName = property.javaName,
                            schemaId = property.schemaId,
                            typeLabel = typeLabel(
                                requireNotNull(schemaById[property.schemaId]),
                                schemaById,
                            ),
                            required = property.required,
                            nullable = property.nullable,
                            readOnly = property.readOnly,
                            writeOnly = property.writeOnly,
                        )
                    },
                )
            },
            defaultBinding = binding,
            supported = true,
            issues = emptyList(),
        )
    }

    private fun representation(
        entry: OperationEntry,
        requestMediaType: String?,
        responseStatus: String?,
        responseMediaType: String?,
        selectedMediaType: String,
        request: Boolean,
    ): OpenApiRepresentationSnapshot = runCatching {
        val operation = buildOperation(
            entry = entry,
            requestMediaType = requestMediaType,
            responseStatus = responseStatus,
            responseMediaType = responseMediaType,
        )
        val schemas = operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val schemaId = if (request) operation.requestSchemaId else operation.responseSchemaId
        OpenApiRepresentationSnapshot(
            mediaType = selectedMediaType,
            typeLabel = schemaId?.let(schemas::get)?.let { typeLabel(it, schemas) },
            supported = true,
        )
    }.getOrElse { failure ->
        OpenApiRepresentationSnapshot(
            mediaType = selectedMediaType,
            typeLabel = null,
            supported = false,
            issue = failure.message ?: "Representation cannot be generated safely.",
        )
    }

    private fun buildOperation(
        entry: OperationEntry,
        requestMediaType: String?,
        responseStatus: String?,
        responseMediaType: String?,
    ): IntegrationOpenApiOperationModel {
        require(entry.path.startsWith('/') && entry.path.length <= 2_048) {
            "Operation path must start with '/' and be at most 2048 characters."
        }
        require(entry.pathItem.`$ref`.isNullOrBlank()) {
            "Referenced Path Item objects are not supported."
        }
        val methodName = javaMethodName(entry)
        val collector = SchemaCollector(components, limits)
        val parameters = mergedParameters(entry).map { parameter ->
            val location = when (parameter.`in`?.lowercase()) {
                "path" -> IntegrationOpenApiParameterLocation.PATH
                "query" -> IntegrationOpenApiParameterLocation.QUERY
                "header" -> IntegrationOpenApiParameterLocation.HEADER
                "cookie" -> IntegrationOpenApiParameterLocation.COOKIE
                else -> throw IllegalArgumentException(
                    "Parameter '${parameter.name}' uses unsupported location '${parameter.`in`}'.",
                )
            }
            val wireName = parameter.name?.trim().orEmpty()
            require(wireName.isNotBlank()) { "Every operation parameter must have a name." }
            require(location != IntegrationOpenApiParameterLocation.PATH || parameter.required == true) {
                "Path parameter '$wireName' must be required."
            }
            require(parameter.content == null) {
                "Parameter '$wireName' uses a media-type representation and requires an explicit serializer."
            }
            validateParameterSerialization(parameter, location, wireName)
            val schema = parameter.schema
                ?: throw IllegalArgumentException("Parameter '$wireName' has no schema.")
            val schemaId = collector.collect(schema, "${methodName}_${wireName}_parameter", 0)
            val schemaModel = collector.schema(schemaId)
            require(schemaModel.kind !in setOf(IntegrationOpenApiSchemaKind.OBJECT, IntegrationOpenApiSchemaKind.ANY)) {
                "Object parameter '$wireName' requires an explicit visual serializer and is not generated implicitly."
            }
            if (schemaModel.kind == IntegrationOpenApiSchemaKind.ARRAY) {
                val item = collector.schema(requireNotNull(schemaModel.itemSchemaId))
                require(
                    item.kind !in setOf(
                        IntegrationOpenApiSchemaKind.OBJECT,
                        IntegrationOpenApiSchemaKind.ARRAY,
                        IntegrationOpenApiSchemaKind.ANY,
                        IntegrationOpenApiSchemaKind.BINARY,
                    ),
                ) {
                    "Array parameter '$wireName' contains values that require an explicit serializer."
                }
                require(location != IntegrationOpenApiParameterLocation.COOKIE) {
                    "Array cookie parameter '$wireName' requires explicit cookie serialization."
                }
            }
            IntegrationOpenApiParameterModel(
                wireName = wireName,
                javaName = safeJavaIdentifier(wireName, lowerCamel = true),
                location = location,
                schemaId = schemaId,
                required = parameter.required == true || location == IntegrationOpenApiParameterLocation.PATH,
                style = parameter.style?.toString(),
                explode = parameter.explode,
            )
        }
        val duplicateJavaNames = parameters.groupingBy(IntegrationOpenApiParameterModel::javaName)
            .eachCount().filterValues { it > 1 }.keys
        require(duplicateJavaNames.isEmpty()) {
            "Operation parameters collapse to duplicate Java names: ${duplicateJavaNames.sorted().joinToString()}."
        }
        val pathVariables = PATH_VARIABLE.findAll(entry.path).map { it.groupValues[1] }.toSet()
        val declaredPathVariables = parameters.filter {
            it.location == IntegrationOpenApiParameterLocation.PATH
        }.map(IntegrationOpenApiParameterModel::wireName).toSet()
        require(pathVariables == declaredPathVariables) {
            "Path template variables and declared path parameters differ."
        }

        val requestBody = resolveRequestBody(entry.operation.requestBody)
        val selectedRequestMedia = selectMediaType(
            available = requestBody?.content.orEmpty().keys,
            selected = requestMediaType,
            label = "request",
            required = requestBody != null,
        )
        validateRequestMediaType(selectedRequestMedia)
        val requestSchemaId = selectedRequestMedia?.let { media ->
            val schema = requestBody?.content?.get(media)?.schema
                ?: throw IllegalArgumentException(
                    "Selected OpenAPI request representation '$media' has no schema.",
                )
            collector.collect(schema, "${methodName}_request", 0)
        }
        validateMediaSchema(selectedRequestMedia, requestSchemaId, collector, "request")

        val responses = resolveResponses(entry.operation)
        val selectedStatus = selectResponseStatus(responses, responseStatus)
        val response = selectedStatus?.let(responses::get)
        val selectedResponseMedia = selectMediaType(
            available = response?.content.orEmpty().keys,
            selected = responseMediaType,
            label = "response",
            required = false,
        )
        val responseSchemaId = selectedResponseMedia?.let { media ->
            val schema = response?.content?.get(media)?.schema
                ?: throw IllegalArgumentException(
                    "Selected OpenAPI response representation '$media' has no schema.",
                )
            collector.collect(schema, "${methodName}_response", 0)
        }
        validateMediaSchema(selectedResponseMedia, responseSchemaId, collector, "response")
        collector.validateComplete()
        return IntegrationOpenApiOperationModel(
            contractPath = relativePath,
            contractSha256 = sha256,
            specificationVersion = openApi.openapi,
            title = openApi.info?.title?.trim()?.takeIf(String::isNotBlank)
                ?: relativePath.substringAfterLast('/'),
            apiVersion = openApi.info?.version?.trim()?.takeIf(String::isNotBlank),
            operationId = entry.operation.operationId?.trim()?.takeIf(String::isNotBlank),
            javaMethodName = methodName,
            method = entry.method,
            path = entry.path,
            deprecated = entry.operation.deprecated == true,
            requestMediaType = selectedRequestMedia,
            requestRequired = requestBody?.required == true,
            requestSchemaId = requestSchemaId,
            responseStatus = selectedStatus,
            responseMediaType = selectedResponseMedia,
            responseSchemaId = responseSchemaId,
            parameters = parameters,
            schemas = collector.models(),
            securitySchemes = securitySchemes(entry.operation),
            securityRequirements = securityRequirements(entry.operation),
        )
    }

    private fun readOperations(): List<OperationEntry> {
        val result = mutableListOf<OperationEntry>()
        openApi.paths.orEmpty().toSortedMap().forEach { (path, item) ->
            item.readOperationsMap().entries.sortedBy { it.key.name }.forEach { (method, operation) ->
                val integrationMethod = when (method) {
                    PathItem.HttpMethod.GET -> IntegrationHttpMethod.GET
                    PathItem.HttpMethod.POST -> IntegrationHttpMethod.POST
                    PathItem.HttpMethod.PUT -> IntegrationHttpMethod.PUT
                    PathItem.HttpMethod.PATCH -> IntegrationHttpMethod.PATCH
                    PathItem.HttpMethod.DELETE -> IntegrationHttpMethod.DELETE
                    else -> null
                } ?: return@forEach
                result += OperationEntry(path, integrationMethod, item, operation)
            }
        }
        return result
    }

    private fun mergedParameters(entry: OperationEntry): List<Parameter> {
        val merged = LinkedHashMap<String, Parameter>()
        (entry.pathItem.parameters.orEmpty() + entry.operation.parameters.orEmpty()).forEach { raw ->
            val parameter = resolveParameter(raw)
            val key = "${parameter.`in`?.lowercase()}:${parameter.name}"
            merged[key] = parameter
        }
        return merged.values.sortedWith(
            compareBy<Parameter> {
                when (it.`in`?.lowercase()) {
                    "path" -> 0
                    "query" -> 1
                    "header" -> 2
                    "cookie" -> 3
                    else -> 4
                }
            }.thenBy { it.name.orEmpty() },
        )
    }

    private fun resolveParameter(parameter: Parameter): Parameter =
        resolveComponentRef(parameter.`$ref`, "#/components/parameters/", components.parameters, parameter)

    private fun resolveRequestBody(requestBody: RequestBody?): RequestBody? =
        requestBody?.let {
            resolveComponentRef(it.`$ref`, "#/components/requestBodies/", components.requestBodies, it)
        }

    private fun resolveResponses(operation: Operation): LinkedHashMap<String, ApiResponse> {
        val result = linkedMapOf<String, ApiResponse>()
        operation.responses.orEmpty().toSortedMap().forEach { (status, raw) ->
            result[status] = resolveComponentRef(
                raw.`$ref`,
                "#/components/responses/",
                components.responses,
                raw,
            )
        }
        return result
    }

    private fun <T> resolveComponentRef(
        ref: String?,
        prefix: String,
        available: Map<String, T>?,
        inline: T,
    ): T {
        if (ref.isNullOrBlank()) return inline
        require(ref.startsWith(prefix)) {
            "External or cross-document reference '$ref' is blocked. Bundle the referenced definition into this project-owned contract."
        }
        val name = decodePointer(ref.removePrefix(prefix))
        return available.orEmpty()[name]
            ?: throw IllegalArgumentException("Unresolved local reference '$ref'.")
    }

    private fun securitySchemes(operation: Operation): List<String> {
        return securityRequirements(operation)
            .flatMap(IntegrationOpenApiSecurityRequirementModel::schemes)
            .map(IntegrationOpenApiSecuritySchemeModel::name)
            .distinct()
            .sorted()
    }

    private fun securityRequirements(operation: Operation): List<IntegrationOpenApiSecurityRequirementModel> {
        val effective = operation.security ?: openApi.security.orEmpty()
        if (effective.isEmpty() || effective.any { it.isEmpty() }) return emptyList()
        return effective.map { requirement ->
            require(requirement.isNotEmpty()) { "OpenAPI security requirement cannot be empty here." }
            IntegrationOpenApiSecurityRequirementModel(
                schemes = requirement.entries.sortedBy(Map.Entry<String, List<String>>::key).map { (name, scopes) ->
                    val raw = components.securitySchemes.orEmpty()[name]
                        ?: throw IllegalArgumentException(
                            "OpenAPI security requirement references missing scheme '$name'.",
                        )
                    val scheme = resolveComponentRef(
                        raw.`$ref`,
                        "#/components/securitySchemes/",
                        components.securitySchemes,
                        raw,
                    )
                    securityScheme(name, scheme, scopes.orEmpty())
                },
            )
        }.distinct()
    }

    private fun securityScheme(
        name: String,
        scheme: SecurityScheme,
        requiredScopes: List<String>,
    ): IntegrationOpenApiSecuritySchemeModel {
        val kind = when (scheme.type?.toString()?.lowercase()) {
            "apikey" -> {
                val location = when (scheme.`in`?.toString()?.lowercase()) {
                    "header" -> IntegrationOpenApiParameterLocation.HEADER
                    "query" -> IntegrationOpenApiParameterLocation.QUERY
                    "cookie" -> IntegrationOpenApiParameterLocation.COOKIE
                    else -> throw IllegalArgumentException(
                        "OpenAPI API-key scheme '$name' has no supported parameter location.",
                    )
                }
                require(location == IntegrationOpenApiParameterLocation.HEADER) {
                    "OpenAPI API-key scheme '$name' uses ${location.name.lowercase()}; only header API keys are generated safely."
                }
                require(!scheme.name.isNullOrBlank() && HTTP_TOKEN.matches(scheme.name)) {
                    "OpenAPI API-key scheme '$name' has an invalid header name."
                }
                IntegrationOpenApiSecuritySchemeKind.API_KEY
            }
            "http" -> when (scheme.scheme?.lowercase()) {
                "basic" -> IntegrationOpenApiSecuritySchemeKind.HTTP_BASIC
                "bearer" -> IntegrationOpenApiSecuritySchemeKind.HTTP_BEARER
                else -> throw IllegalArgumentException(
                    "OpenAPI HTTP security scheme '$name' uses unsupported scheme '${scheme.scheme}'.",
                )
            }
            "oauth2" -> {
                val clientCredentials = scheme.flows?.clientCredentials
                if (clientCredentials != null) {
                    val declaredScopes = clientCredentials.scopes.orEmpty().keys
                    require(requiredScopes.all(declaredScopes::contains)) {
                        "OpenAPI OAuth2 requirement '$name' references scopes not declared by its client-credentials flow."
                    }
                    IntegrationOpenApiSecuritySchemeKind.OAUTH2_CLIENT_CREDENTIALS
                } else {
                    IntegrationOpenApiSecuritySchemeKind.OAUTH2_OTHER
                }
            }
            "openidconnect" -> IntegrationOpenApiSecuritySchemeKind.OPEN_ID_CONNECT
            "mutualtls" -> IntegrationOpenApiSecuritySchemeKind.MUTUAL_TLS
            else -> throw IllegalArgumentException(
                "OpenAPI security scheme '$name' uses unsupported type '${scheme.type}'.",
            )
        }
        return IntegrationOpenApiSecuritySchemeModel(
            name = name,
            kind = kind,
            parameterName = scheme.name?.trim()?.takeIf(String::isNotBlank),
            parameterLocation = if (kind == IntegrationOpenApiSecuritySchemeKind.API_KEY) {
                IntegrationOpenApiParameterLocation.HEADER
            } else {
                null
            },
            requiredScopes = requiredScopes.map(String::trim).filter(String::isNotBlank).distinct().sorted(),
        )
    }

    private fun preferredResponse(responses: List<OpenApiResponseSnapshot>): OpenApiResponseSnapshot? =
        responses.filter { isSuccessfulStatus(it.status) }
            .minByOrNull { it.status.uppercase() }
            ?: responses.firstOrNull { it.status.equals("default", true) }

    private fun selectResponseStatus(
        responses: Map<String, ApiResponse>,
        selected: String?,
    ): String? {
        if (responses.isEmpty()) return null
        if (selected != null) {
            require(responses.containsKey(selected)) { "Selected OpenAPI response '$selected' is no longer present." }
            require(isSuccessfulStatus(selected)) {
                "Generated clients require an explicit successful 2xx response contract; '$selected' is not successful."
            }
            return selected
        }
        return responses.keys.filter(::isSuccessfulStatus)
            .minByOrNull(String::uppercase)
            ?: throw IllegalArgumentException(
                "Operation has no explicit successful 2xx response contract.",
            )
    }

    private fun preferredMediaType(mediaTypes: Collection<String>): String? =
        mediaTypes.firstOrNull { it.equals("application/json", true) }
            ?: mediaTypes.firstOrNull { it.lowercase().endsWith("+json") }
            ?: mediaTypes.sorted().firstOrNull()

    private fun isSuccessfulStatus(status: String): Boolean =
        status.matches(Regex("""2\d\d""")) || status.equals("2XX", true)

    private fun selectMediaType(
        available: Collection<String>,
        selected: String?,
        label: String,
        required: Boolean,
    ): String? {
        if (available.isEmpty()) {
            require(!required || selected == null) { "Selected OpenAPI $label media type is no longer present." }
            return null
        }
        if (selected != null) {
            return available.firstOrNull { it == selected }
                ?: throw IllegalArgumentException("Selected OpenAPI $label media type '$selected' is no longer present.")
        }
        return preferredMediaType(available)
    }

    private fun validateRequestMediaType(mediaType: String?) {
        if (mediaType == null) return
        val normalized = mediaType.substringBefore(';').trim().lowercase()
        require(
            normalized == "application/json" ||
                normalized.endsWith("+json") ||
                normalized == "text/plain" ||
                normalized == "application/octet-stream",
        ) {
            "Request media type '$mediaType' requires a dedicated serializer; implicit form or multipart generation is blocked."
        }
    }

    private fun validateMediaSchema(
        mediaType: String?,
        schemaId: String?,
        collector: SchemaCollector,
        label: String,
    ) {
        if (mediaType == null || schemaId == null) return
        val normalized = mediaType.substringBefore(';').trim().lowercase()
        val schema = collector.schema(schemaId)
        when {
            normalized == "application/json" || normalized.endsWith("+json") -> Unit
            normalized == "text/plain" -> require(
                schema.kind == IntegrationOpenApiSchemaKind.STRING && schema.enumValues.isEmpty(),
            ) {
                "OpenAPI $label media type '$mediaType' requires a plain String schema."
            }
            normalized == "application/octet-stream" -> require(
                schema.kind == IntegrationOpenApiSchemaKind.BINARY,
            ) {
                "OpenAPI $label media type '$mediaType' requires a binary schema."
            }
            else -> throw IllegalArgumentException(
                "OpenAPI $label media type '$mediaType' requires a dedicated message converter.",
            )
        }
    }

    private fun javaMethodName(entry: OperationEntry): String {
        val source = entry.operation.operationId?.trim()?.takeIf(String::isNotBlank)
            ?: "${entry.method.name.lowercase()}_${entry.path}"
        val identifier = safeJavaIdentifier(source, lowerCamel = true)
        return if (identifier in OBJECT_METHOD_NAMES) "${identifier}Operation" else identifier
    }

    private fun validateParameterSerialization(
        parameter: Parameter,
        location: IntegrationOpenApiParameterLocation,
        wireName: String,
    ) {
        require(parameter.allowReserved != true) {
            "Parameter '$wireName' permits reserved characters and requires an explicit URI serializer."
        }
        val style = parameter.style?.toString()?.lowercase()
        val expectedStyle = when (location) {
            IntegrationOpenApiParameterLocation.PATH,
            IntegrationOpenApiParameterLocation.HEADER,
            -> "simple"
            IntegrationOpenApiParameterLocation.QUERY,
            IntegrationOpenApiParameterLocation.COOKIE,
            -> "form"
        }
        require(style == null || style == expectedStyle) {
            "Parameter '$wireName' uses unsupported $style serialization; only $expectedStyle is generated for ${location.name.lowercase()} parameters."
        }
        if (location == IntegrationOpenApiParameterLocation.HEADER) {
            require(wireName.lowercase() !in OPENAPI_IGNORED_HEADER_PARAMETERS) {
                "OpenAPI header parameter '$wireName' is reserved by HTTP content negotiation or authentication."
            }
        }
    }

    private fun typeLabel(
        schema: IntegrationOpenApiSchemaModel,
        schemas: Map<String, IntegrationOpenApiSchemaModel>,
    ): String = when (schema.kind) {
        IntegrationOpenApiSchemaKind.OBJECT -> schema.javaName
        IntegrationOpenApiSchemaKind.ARRAY ->
            "List<${schema.itemSchemaId?.let(schemas::get)?.let { typeLabel(it, schemas) } ?: "Object"}>"
        IntegrationOpenApiSchemaKind.STRING -> "String"
        IntegrationOpenApiSchemaKind.INTEGER -> if (schema.format == "int64") "Long" else "Integer"
        IntegrationOpenApiSchemaKind.NUMBER -> if (schema.format == "float") "Float" else "BigDecimal"
        IntegrationOpenApiSchemaKind.BOOLEAN -> "Boolean"
        IntegrationOpenApiSchemaKind.UUID -> "UUID"
        IntegrationOpenApiSchemaKind.DATE -> "LocalDate"
        IntegrationOpenApiSchemaKind.DATE_TIME -> "OffsetDateTime"
        IntegrationOpenApiSchemaKind.BINARY -> "byte[]"
        IntegrationOpenApiSchemaKind.ANY -> "Object"
    }

    private data class OperationEntry(
        val path: String,
        val method: IntegrationHttpMethod,
        val pathItem: PathItem,
        val operation: Operation,
    )

    companion object {
        private const val MAX_REPRESENTATIONS_PER_OPERATION = 64
        private val PATH_VARIABLE = Regex("""\{([^{}]+)}""")
        private val HTTP_TOKEN = Regex("""[!#$%&'*+\-.^_`|~0-9A-Za-z]+""")
        private val OPENAPI_IGNORED_HEADER_PARAMETERS = setOf(
            "accept",
            "content-type",
            "authorization",
        )
        private val OBJECT_METHOD_NAMES = setOf(
            "clone",
            "equals",
            "finalize",
            "getClass",
            "hashCode",
            "notify",
            "notifyAll",
            "toString",
            "wait",
        )
    }
}

private class SchemaCollector(
    private val components: Components,
    private val limits: ParserLimits,
) {
    private val models = linkedMapOf<String, IntegrationOpenApiSchemaModel>()
    private val javaNames = linkedMapOf<String, String>()
    private var propertyCount = 0

    fun collect(raw: Schema<*>, suggestedName: String, depth: Int): String {
        require(depth <= limits.maxDepth) {
            "OpenAPI schema nesting exceeds the ${limits.maxDepth}-level safety limit."
        }
        val ref = raw.`$ref`
        val resolved: Schema<*>
        val id: String
        val baseName: String
        if (!ref.isNullOrBlank()) {
            require(ref.startsWith(SCHEMA_PREFIX)) {
                "External or cross-document schema reference '$ref' is blocked."
            }
            val componentName = decodePointer(ref.removePrefix(SCHEMA_PREFIX))
            resolved = components.schemas.orEmpty()[componentName]
                ?: throw IllegalArgumentException("Unresolved local schema reference '$ref'.")
            id = "component:$componentName"
            baseName = componentName
        } else {
            resolved = raw
            id = "inline:${canonicalId(suggestedName)}"
            baseName = raw.title?.trim()?.takeIf(String::isNotBlank) ?: suggestedName
        }
        if (id in models) return id
        require(models.size < limits.maxSchemas) {
            "Selected operation exceeds the ${limits.maxSchemas}-schema safety limit."
        }
        val javaName = uniqueJavaTypeName(baseName, id)
        models[id] = IntegrationOpenApiSchemaModel(id, javaName, IntegrationOpenApiSchemaKind.ANY)

        require(resolved.oneOf.orEmpty().isEmpty() && resolved.anyOf.orEmpty().isEmpty()) {
            "Schema '$baseName' uses oneOf/anyOf. Choose or map a discriminator variant explicitly before generation."
        }
        require(resolved.not == null && resolved.patternProperties.orEmpty().isEmpty()) {
            "Schema '$baseName' uses validation constructs that require an explicit mapper."
        }
        val allOf = resolved.allOf.orEmpty()
        val propertySources = if (allOf.isEmpty()) listOf(resolved) else allOf + resolved.copyWithoutAllOf()
        val mergedProperties = linkedMapOf<String, Schema<*>>()
        val required = linkedSetOf<String>()
        propertySources.forEach { source ->
            val effective = resolveLocal(source)
            require(effective.oneOf.orEmpty().isEmpty() && effective.anyOf.orEmpty().isEmpty()) {
                "Schema '$baseName' contains a polymorphic allOf branch."
            }
            effective.required.orEmpty().forEach(required::add)
            effective.properties.orEmpty().toSortedMap().forEach { (name, property) ->
                val previous = mergedProperties.put(name, property)
                if (previous != null && schemaSignature(previous) != schemaSignature(property)) {
                    throw IllegalArgumentException(
                        "Schema '$baseName' defines incompatible allOf property '$name'.",
                    )
                }
            }
        }
        propertyCount += mergedProperties.size
        require(propertyCount <= limits.maxProperties) {
            "Selected operation exceeds the ${limits.maxProperties}-property safety limit."
        }
        val kind = schemaKind(resolved, mergedProperties)
        val properties = if (kind == IntegrationOpenApiSchemaKind.OBJECT) {
            mergedProperties.map { (wireName, property) ->
                val childId = collect(property, "${baseName}_$wireName", depth + 1)
                IntegrationOpenApiPropertyModel(
                    wireName = wireName,
                    javaName = safeJavaIdentifier(wireName, lowerCamel = true),
                    schemaId = childId,
                    required = wireName in required,
                    nullable = nullable(property),
                    readOnly = property.readOnly == true,
                    writeOnly = property.writeOnly == true,
                )
            }.also { values ->
                val duplicates = values.groupingBy(IntegrationOpenApiPropertyModel::javaName)
                    .eachCount().filterValues { it > 1 }.keys
                require(duplicates.isEmpty()) {
                    "Schema '$baseName' has property names that collapse to duplicate Java names: ${duplicates.joinToString()}."
                }
            }
        } else {
            emptyList()
        }
        val itemSchemaId = if (kind == IntegrationOpenApiSchemaKind.ARRAY) {
            val items = resolved.items
                ?: throw IllegalArgumentException("Array schema '$baseName' has no item schema.")
            collect(items, "${baseName}_item", depth + 1)
        } else {
            null
        }
        require(
            mergedProperties.isEmpty() ||
                resolved.additionalProperties == null ||
                resolved.additionalProperties == false
        ) {
            "Schema '$baseName' mixes named and arbitrary properties and requires an explicit mapper."
        }
        val additionalPropertiesSchemaId = when (val additional = resolved.additionalProperties) {
            is Schema<*> -> collect(additional, "${baseName}_value", depth + 1)
            else -> null
        }
        models[id] = IntegrationOpenApiSchemaModel(
            id = id,
            javaName = javaName,
            kind = kind,
            format = resolved.format?.lowercase(),
            nullable = nullable(resolved),
            enumValues = resolved.enum.orEmpty().map { it.toString() }.distinct().sorted(),
            properties = properties,
            itemSchemaId = itemSchemaId,
            additionalPropertiesSchemaId = additionalPropertiesSchemaId,
            additionalPropertiesAllowed = resolved.additionalProperties == true ||
                resolved.additionalProperties is Schema<*>,
        )
        return id
    }

    fun schema(id: String): IntegrationOpenApiSchemaModel =
        requireNotNull(models[id]) { "OpenAPI schema '$id' was not collected." }

    fun models(): List<IntegrationOpenApiSchemaModel> = models.values.sortedBy(IntegrationOpenApiSchemaModel::id)

    fun validateComplete() {
        require(models.size <= limits.maxSchemas && propertyCount <= limits.maxProperties) {
            "OpenAPI schema normalization exceeded its declared safety bounds."
        }
    }

    private fun resolveLocal(schema: Schema<*>): Schema<*> {
        val ref = schema.`$ref` ?: return schema
        require(ref.startsWith(SCHEMA_PREFIX)) {
            "External or cross-document schema reference '$ref' is blocked."
        }
        val name = decodePointer(ref.removePrefix(SCHEMA_PREFIX))
        return components.schemas.orEmpty()[name]
            ?: throw IllegalArgumentException("Unresolved local schema reference '$ref'.")
    }

    private fun schemaKind(
        schema: Schema<*>,
        properties: Map<String, Schema<*>>,
    ): IntegrationOpenApiSchemaKind {
        val types = schema.types.orEmpty().map(String::lowercase).filterNot { it == "null" }.toSet()
        val type = schema.type?.lowercase() ?: types.singleOrNull()
        val format = schema.format?.lowercase()
        return when {
            type == "array" || schema.items != null -> IntegrationOpenApiSchemaKind.ARRAY
            type == "object" || properties.isNotEmpty() || schema.additionalProperties != null ->
                IntegrationOpenApiSchemaKind.OBJECT
            type == "integer" -> IntegrationOpenApiSchemaKind.INTEGER
            type == "number" -> IntegrationOpenApiSchemaKind.NUMBER
            type == "boolean" -> IntegrationOpenApiSchemaKind.BOOLEAN
            type == "string" && format == "uuid" -> IntegrationOpenApiSchemaKind.UUID
            type == "string" && format == "date" -> IntegrationOpenApiSchemaKind.DATE
            type == "string" && format in setOf("date-time", "datetime") ->
                IntegrationOpenApiSchemaKind.DATE_TIME
            type == "string" && format in setOf("binary", "byte") -> IntegrationOpenApiSchemaKind.BINARY
            type == "string" || schema.enum.orEmpty().isNotEmpty() -> IntegrationOpenApiSchemaKind.STRING
            type == null -> IntegrationOpenApiSchemaKind.ANY
            else -> throw IllegalArgumentException("Unsupported OpenAPI schema type '$type'.")
        }
    }

    private fun nullable(schema: Schema<*>): Boolean =
        schema.nullable == true || schema.types.orEmpty().any { it.equals("null", true) }

    private fun uniqueJavaTypeName(source: String, id: String): String {
        val base = safeJavaIdentifier(source, lowerCamel = false)
        val current = javaNames[base]
        if (current == null || current == id) {
            javaNames[base] = id
            return base
        }
        val suffix = CanonicalDiscoveryJson.sha256(id).take(8)
        val candidate = "$base$suffix"
        javaNames[candidate] = id
        return candidate
    }

    private fun Schema<*>.copyWithoutAllOf(): Schema<*> = Schema<Any>().also { copy ->
        copy.type = type
        copy.types = types
        copy.format = format
        copy.properties = properties
        copy.required = required
        copy.items = items
        copy.additionalProperties = additionalProperties
        copy.nullable = nullable
        copy.enum = enum
        copy.readOnly = readOnly
        copy.writeOnly = writeOnly
        copy.oneOf = oneOf
        copy.anyOf = anyOf
        copy.not = not
        copy.patternProperties = patternProperties
    }

    companion object {
        private const val SCHEMA_PREFIX = "#/components/schemas/"
    }
}

internal data class ParserLimits(
    val maxOperations: Int,
    val maxSchemas: Int,
    val maxProperties: Int,
    val maxDepth: Int,
)

private fun schemaSignature(schema: Schema<*>): String =
    listOf(schema.`$ref`, schema.type, schema.format, schema.items?.`$ref`, schema.items?.type)
        .joinToString("\u0000")

private fun canonicalId(value: String): String =
    value.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(160).ifBlank { "schema" }

private fun decodePointer(value: String): String =
    value.replace("~1", "/").replace("~0", "~")

private fun safeJavaIdentifier(value: String, lowerCamel: Boolean): String {
    val words = value.trim().split(Regex("""[^A-Za-z0-9]+""")).filter(String::isNotBlank)
    val raw = if (words.isEmpty()) {
        "value"
    } else {
        words.mapIndexed { index, word ->
            val normalized = word.replaceFirstChar { character ->
                if (index == 0 && lowerCamel) character.lowercase() else character.uppercase()
            }
            normalized
        }.joinToString("")
    }
    val prefixed = if (raw.firstOrNull()?.isJavaIdentifierStart() == true) raw else "_$raw"
    val sanitized = prefixed.map { character ->
        if (character.isJavaIdentifierPart()) character else '_'
    }.joinToString("")
    return if (sanitized in JAVA_KEYWORDS) "${sanitized}Value" else sanitized
}

private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "record", "sealed",
    "permits", "yield", "var",
)

package org.jmixworkbench.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.core.util.Json
import io.swagger.v3.core.util.Yaml
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.model.IntegrationOpenApiReferencedDocument
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat

internal data class OpenApiSourceDocument(
    val relativePath: String,
    val bytes: ByteArray,
)

internal data class OpenApiBundledDocument(
    val bytes: ByteArray,
    val rootSha256: String,
    val referencedDocuments: List<IntegrationOpenApiReferencedDocument>,
)

/**
 * Creates a network-free parser document from a project-owned OpenAPI bundle.
 *
 * External schema references become deterministic synthetic root components,
 * preserving identity and cycles across files. Other supported Reference
 * Objects are expanded in place. The caller owns path resolution and must
 * return canonical project-relative files only.
 */
internal object OpenApiDocumentBundler {
    private const val MAX_DOCUMENTS = 128
    private const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 20 * 1024 * 1024
    private const val MAX_REFERENCES = 4_096
    private const val MAX_REFERENCE_DEPTH = 64

    fun bundle(
        rootPath: String,
        rootBytes: ByteArray,
        loader: (referrerPath: String, referencePath: String) -> OpenApiSourceDocument,
    ): OpenApiBundledDocument {
        requireSafeLocator(rootPath)
        requireDocumentSize(rootPath, rootBytes)
        val root = parseObject(rootPath, rootBytes)
        val state = BundleState(rootPath, root, rootBytes, loader)
        val transformed = state.transform(root, rootPath, ReferenceContext.ROOT, 0) as ObjectNode
        state.installSyntheticSchemas(transformed)
        return OpenApiBundledDocument(
            bytes = Json.mapper().writeValueAsBytes(transformed),
            rootSha256 = sha256(rootBytes),
            referencedDocuments = state.referencedDocuments(),
        )
    }

    private class BundleState(
        private val rootPath: String,
        root: ObjectNode,
        rootBytes: ByteArray,
        private val loader: (String, String) -> OpenApiSourceDocument,
    ) {
        private val documents = linkedMapOf(
            rootPath to LoadedDocument(rootPath, rootBytes, root),
        )
        private val syntheticSchemas = linkedMapOf<String, JsonNode>()
        private val syntheticByTarget = linkedMapOf<String, String>()
        private val resolvingInline = linkedSetOf<String>()
        private val rootSchemaNames = root.path("components").path("schemas")
            .takeIf(JsonNode::isObject)
            ?.fieldNames()
            ?.asSequence()
            ?.toSet()
            .orEmpty()
        private var totalBytes = rootBytes.size
        private var referenceCount = 0

        fun transform(
            node: JsonNode,
            currentPath: String,
            context: ReferenceContext,
            depth: Int,
        ): JsonNode {
            require(depth <= MAX_REFERENCE_DEPTH) {
                "OpenAPI reference nesting exceeds the $MAX_REFERENCE_DEPTH-level safety limit."
            }
            if (context == ReferenceContext.IGNORED) return node.deepCopy<JsonNode>()
            return when (node) {
                is ObjectNode -> transformObject(node, currentPath, context, depth)
                is ArrayNode -> Json.mapper().createArrayNode().also { result ->
                    node.forEach { child ->
                        result.add(transform(child, currentPath, context.arrayElement(), depth + 1))
                    }
                }
                else -> node.deepCopy<JsonNode>()
            }
        }

        fun installSyntheticSchemas(root: ObjectNode) {
            if (syntheticSchemas.isEmpty()) return
            val components = root.withObject("components")
            val schemas = components.withObject("schemas")
            syntheticSchemas.toSortedMap().forEach { (name, schema) ->
                require(!schemas.has(name)) {
                    "Synthetic OpenAPI schema name '$name' collides with a root component."
                }
                schemas.set<JsonNode>(name, schema)
            }
        }

        fun referencedDocuments(): List<IntegrationOpenApiReferencedDocument> = documents.values
            .asSequence()
            .filter { it.relativePath != rootPath }
            .map { document ->
                IntegrationOpenApiReferencedDocument(
                    relativePath = document.relativePath,
                    documentSha256 = sha256(document.bytes),
                )
            }
            .sortedBy(IntegrationOpenApiReferencedDocument::relativePath)
            .toList()

        private fun transformObject(
            node: ObjectNode,
            currentPath: String,
            context: ReferenceContext,
            depth: Int,
        ): JsonNode {
            val reference = node.get("${'$'}ref")?.takeIf(JsonNode::isTextual)?.asText()
            if (reference != null) {
                referenceCount++
                require(referenceCount <= MAX_REFERENCES) {
                    "OpenAPI bundle exceeds the $MAX_REFERENCES-reference safety limit."
                }
                return transformReference(node, reference, currentPath, context, depth)
            }
            val result = Json.mapper().createObjectNode()
            node.properties().toList().forEach { (name, child) ->
                result.set<JsonNode>(
                    name,
                    transform(child, currentPath, context.child(name), depth + 1),
                )
            }
            return result
        }

        private fun transformReference(
            source: ObjectNode,
            reference: String,
            currentPath: String,
            context: ReferenceContext,
            depth: Int,
        ): JsonNode {
            val parsed = parseReference(reference)
            if (parsed.documentPath.isEmpty() && currentPath == rootPath) {
                validateLocalFragment(reference, parsed.fragment)
                return copyLocalReference(source, parsed.fragment)
            }
            val targetDocument = if (parsed.documentPath.isEmpty()) {
                requireNotNull(documents[currentPath]) {
                    "OpenAPI reference base '$currentPath' is not loaded."
                }
            } else {
                load(currentPath, parsed.documentPath)
            }
            if (targetDocument.relativePath == rootPath) {
                validateLocalFragment(reference, parsed.fragment)
                return copyLocalReference(source, parsed.fragment)
            }
            val target = resolvePointer(targetDocument, parsed.fragment, reference)
            val effectiveContext = context.inferFromFragment(parsed.fragment)
            return if (effectiveContext == ReferenceContext.SCHEMA) {
                schemaReference(source, targetDocument, parsed.fragment, target, depth)
            } else {
                inlineReference(source, targetDocument, parsed.fragment, target, effectiveContext, depth)
            }
        }

        private fun schemaReference(
            source: ObjectNode,
            document: LoadedDocument,
            fragment: String,
            target: JsonNode,
            depth: Int,
        ): JsonNode {
            require(target.isObject) {
                "Referenced OpenAPI schema '${document.relativePath}#$fragment' must be an object."
            }
            val semanticSiblings = source.fieldNames().asSequence()
                .filter { it !in SCHEMA_REFERENCE_METADATA }
                .toList()
            require(semanticSiblings.isEmpty()) {
                "Cross-document schema reference '${document.relativePath}#$fragment' has unsupported sibling fields: " +
                    semanticSiblings.sorted().joinToString() + "."
            }
            val targetKey = "${document.relativePath}#$fragment"
            val componentName = syntheticByTarget.getOrPut(targetKey) {
                uniqueSyntheticName(document.relativePath, fragment, targetKey)
            }
            if (componentName !in syntheticSchemas) {
                syntheticSchemas[componentName] = Json.mapper().createObjectNode()
                syntheticSchemas[componentName] = transform(
                    target,
                    document.relativePath,
                    ReferenceContext.SCHEMA,
                    depth + 1,
                )
            }
            return Json.mapper().createObjectNode().also { result ->
                result.put("${'$'}ref", "#/components/schemas/$componentName")
                source.properties().toList()
                    .filter { (name, _) -> name in SCHEMA_REFERENCE_METADATA && name != "${'$'}ref" }
                    .forEach { (name, value) -> result.set<JsonNode>(name, value.deepCopy()) }
            }
        }

        private fun inlineReference(
            source: ObjectNode,
            document: LoadedDocument,
            fragment: String,
            target: JsonNode,
            context: ReferenceContext,
            depth: Int,
        ): JsonNode {
            require(context in INLINE_REFERENCE_CONTEXTS) {
                "Cross-document reference '${document.relativePath}#$fragment' occurs in an unsupported OpenAPI location."
            }
            require(target.isObject) {
                "Referenced OpenAPI ${context.label} '${document.relativePath}#$fragment' must be an object."
            }
            val unsupportedSiblings = source.fieldNames().asSequence()
                .filter { it !in REFERENCE_METADATA }
                .toList()
            require(unsupportedSiblings.isEmpty()) {
                "Cross-document ${context.label} reference has unsupported sibling fields: " +
                    unsupportedSiblings.sorted().joinToString() + "."
            }
            val targetKey = "${context.name}:${document.relativePath}#$fragment"
            require(resolvingInline.add(targetKey)) {
                "Cyclic cross-document ${context.label} reference '$targetKey' cannot be expanded safely."
            }
            return try {
                val transformed = transform(target, document.relativePath, context, depth + 1) as ObjectNode
                source.properties().toList()
                    .filter { (name, _) -> name in REFERENCE_METADATA && name != "${'$'}ref" }
                    .forEach { (name, value) -> transformed.set<JsonNode>(name, value.deepCopy()) }
                transformed
            } finally {
                resolvingInline.remove(targetKey)
            }
        }

        private fun load(referrerPath: String, referencePath: String): LoadedDocument {
            val source = loader(referrerPath, referencePath)
            requireSafeLocator(source.relativePath)
            documents[source.relativePath]?.let { existing ->
                require(existing.bytes.contentEquals(source.bytes)) {
                    "OpenAPI document '${source.relativePath}' resolved to inconsistent content."
                }
                return existing
            }
            require(documents.size < MAX_DOCUMENTS) {
                "OpenAPI bundle exceeds the $MAX_DOCUMENTS-document safety limit."
            }
            requireDocumentSize(source.relativePath, source.bytes)
            totalBytes += source.bytes.size
            require(totalBytes <= MAX_TOTAL_BYTES) {
                "OpenAPI bundle exceeds the ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB aggregate safety limit."
            }
            return LoadedDocument(
                relativePath = source.relativePath,
                bytes = source.bytes,
                root = parseObject(source.relativePath, source.bytes),
            ).also { documents[source.relativePath] = it }
        }

        private fun uniqueSyntheticName(relativePath: String, fragment: String, targetKey: String): String {
            val source = fragment.substringAfterLast('/').ifBlank {
                relativePath.substringAfterLast('/').substringBeforeLast('.')
            }
            val readable = source.replace(Regex("[^A-Za-z0-9_]+"), "_")
                .trim('_')
                .take(48)
                .ifBlank { "Schema" }
            var digestLength = 12
            while (true) {
                val candidate = "Jvw_${readable}_${CanonicalDiscoveryJson.sha256(targetKey).take(digestLength)}"
                if (candidate !in rootSchemaNames && candidate !in syntheticSchemas) return candidate
                digestLength += 4
            }
        }
    }

    private data class LoadedDocument(
        val relativePath: String,
        val bytes: ByteArray,
        val root: ObjectNode,
    )

    private data class ParsedReference(
        val documentPath: String,
        val fragment: String,
    )

    private enum class ReferenceContext(val label: String) {
        ROOT("document"),
        COMPONENTS("components"),
        SCHEMA("schema"),
        SCHEMA_MAP("schema"),
        PARAMETER("parameter"),
        PARAMETER_LIST("parameter"),
        PARAMETER_MAP("parameter"),
        REQUEST_BODY("request body"),
        REQUEST_BODY_MAP("request body"),
        RESPONSE("response"),
        RESPONSE_MAP("response"),
        SECURITY_SCHEME("security scheme"),
        SECURITY_SCHEME_MAP("security scheme"),
        PATH_ITEM("path item"),
        PATH_MAP("path item"),
        IGNORED("ignored metadata"),
        OTHER("reference");

        fun arrayElement(): ReferenceContext = when (this) {
            SCHEMA, SCHEMA_MAP -> SCHEMA
            PARAMETER_LIST, PARAMETER_MAP -> PARAMETER
            else -> this
        }

        fun child(name: String): ReferenceContext = when {
            name in IGNORED_REFERENCE_SUBTREES -> IGNORED
            this == ROOT && name == "components" -> COMPONENTS
            this == ROOT && name == "paths" -> PATH_MAP
            this == COMPONENTS && name == "schemas" -> SCHEMA_MAP
            this == COMPONENTS && name == "parameters" -> PARAMETER_MAP
            this == COMPONENTS && name == "requestBodies" -> REQUEST_BODY_MAP
            this == COMPONENTS && name == "responses" -> RESPONSE_MAP
            this == COMPONENTS && name == "securitySchemes" -> SECURITY_SCHEME_MAP
            this == COMPONENTS && name == "pathItems" -> PATH_MAP
            this == SCHEMA_MAP -> SCHEMA
            this == PARAMETER_MAP -> PARAMETER
            this == REQUEST_BODY_MAP -> REQUEST_BODY
            this == RESPONSE_MAP -> RESPONSE
            this == SECURITY_SCHEME_MAP -> SECURITY_SCHEME
            this == PATH_MAP -> PATH_ITEM
            name == "schema" -> SCHEMA
            name == "requestBody" -> REQUEST_BODY
            name == "responses" -> RESPONSE_MAP
            name == "parameters" -> PARAMETER_LIST
            name == "securitySchemes" -> SECURITY_SCHEME_MAP
            this == SCHEMA && name == "properties" -> SCHEMA_MAP
            this == SCHEMA && name in SCHEMA_CHILD_FIELDS -> SCHEMA
            this == SCHEMA && name in SCHEMA_ARRAY_FIELDS -> SCHEMA
            else -> OTHER
        }

        fun inferFromFragment(fragment: String): ReferenceContext {
            val tokens = fragment.trim('/').split('/')
            val section = tokens.windowed(2).firstOrNull { it.first() == "components" }?.lastOrNull()
            return when (section) {
                "schemas" -> SCHEMA
                "parameters" -> PARAMETER
                "requestBodies" -> REQUEST_BODY
                "responses" -> RESPONSE
                "securitySchemes" -> SECURITY_SCHEME
                "pathItems" -> PATH_ITEM
                else -> when (this) {
                    SCHEMA_MAP -> SCHEMA
                    PARAMETER_LIST, PARAMETER_MAP -> PARAMETER
                    REQUEST_BODY_MAP -> REQUEST_BODY
                    RESPONSE_MAP -> RESPONSE
                    SECURITY_SCHEME_MAP -> SECURITY_SCHEME
                    PATH_MAP -> PATH_ITEM
                    else -> this
                }
            }
        }
    }

    private fun parseReference(value: String): ParsedReference {
        require(value.isNotBlank() && value.length <= 4_096 && '\u0000' !in value && '\\' !in value) {
            "OpenAPI reference is empty, too long, or contains unsafe characters."
        }
        val uri = runCatching { URI(value) }.getOrElse {
            throw IllegalArgumentException("OpenAPI reference '$value' is not a valid URI reference.")
        }
        require(uri.scheme == null && uri.rawAuthority == null && uri.rawQuery == null && uri.rawPath.orEmpty().startsWith('/').not()) {
            "Remote, absolute, or query-bearing OpenAPI reference '$value' is blocked."
        }
        val fragment = uri.fragment.orEmpty()
        require(fragment.isEmpty() || fragment.startsWith('/')) {
            "OpenAPI reference '$value' uses an unsupported non-JSON-Pointer fragment."
        }
        return ParsedReference(uri.path.orEmpty(), fragment)
    }

    private fun resolvePointer(
        document: LoadedDocument,
        fragment: String,
        reference: String,
    ): JsonNode {
        val target = if (fragment.isEmpty()) document.root else document.root.at(fragment)
        require(!target.isMissingNode) {
            "Unresolved OpenAPI reference '$reference' in '${document.relativePath}'."
        }
        return target
    }

    private fun validateLocalFragment(reference: String, fragment: String) {
        require(fragment.isNotEmpty()) {
            "Whole-document self reference '$reference' is cyclic and cannot be generated safely."
        }
    }

    private fun copyLocalReference(source: ObjectNode, fragment: String): ObjectNode =
        source.deepCopy().also { it.put("${'$'}ref", "#$fragment") }

    private fun parseObject(path: String, bytes: ByteArray): ObjectNode {
        val text = bytes.toString(Charsets.UTF_8)
        require('\u0000' !in text) { "OpenAPI document '$path' contains binary NUL characters." }
        val first = text.firstOrNull { !it.isWhitespace() }
        val node = runCatching {
            if (first == '{' || first == '[') Json.mapper().readTree(text) else Yaml.mapper().readTree(text)
        }.getOrElse { failure ->
            throw IllegalArgumentException(
                "OpenAPI document '$path' is not valid JSON or YAML: ${failure.message.orEmpty().take(300)}",
            )
        }
        require(node is ObjectNode) { "OpenAPI document '$path' must contain an object at its root." }
        return node
    }

    private fun requireDocumentSize(path: String, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_DOCUMENT_BYTES) {
            "OpenAPI document '$path' must be between 1 byte and ${MAX_DOCUMENT_BYTES / (1024 * 1024)} MiB."
        }
    }

    private fun requireSafeLocator(path: String) {
        require(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                '\\' !in path &&
                path.split('/').none { it.isBlank() || it == "." || it == ".." },
        ) { "OpenAPI document must have a safe project-relative locator." }
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val SCHEMA_REFERENCE_METADATA = setOf("${'$'}ref", "title", "summary", "description", "example", "examples", "deprecated")
    private val REFERENCE_METADATA = setOf("${'$'}ref", "summary", "description")
    private val INLINE_REFERENCE_CONTEXTS = setOf(
        ReferenceContext.PARAMETER,
        ReferenceContext.REQUEST_BODY,
        ReferenceContext.RESPONSE,
        ReferenceContext.SECURITY_SCHEME,
        ReferenceContext.PATH_ITEM,
    )
    private val SCHEMA_CHILD_FIELDS = setOf(
        "items",
        "additionalProperties",
        "not",
        "contains",
        "propertyNames",
        "if",
        "then",
        "else",
        "unevaluatedProperties",
    )
    private val SCHEMA_ARRAY_FIELDS = setOf("allOf", "anyOf", "oneOf", "prefixItems")
    private val IGNORED_REFERENCE_SUBTREES = setOf(
        "callbacks",
        "examples",
        "externalDocs",
        "headers",
        "links",
        "webhooks",
        "xml",
    )
}

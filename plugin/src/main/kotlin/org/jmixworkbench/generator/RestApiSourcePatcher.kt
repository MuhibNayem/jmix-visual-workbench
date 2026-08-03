package org.jmixworkbench.generator

import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Produces one surgical insertion for Jmix REST XML allowlists.
 *
 * Existing bytes are never serialized through a DOM. Comments, namespaces,
 * processing instructions, whitespace and unsupported extension elements stay
 * exactly as the developer wrote them.
 */
object RestApiSourcePatcher {
    fun add(existing: String, contract: RestApiXmlContract): WorkspaceTextEdit {
        val root = secureRoot(existing)
            ?: error("JVW-REST-CONFIG-MALFORMED: the REST configuration is not well-formed XML.")
        val expectedRoot = when (contract) {
            is RestApiXmlContract.ServiceMethod -> "services"
            is RestApiXmlContract.Query -> "queries"
        }
        require(root == expectedRoot) {
            "JVW-REST-CONFIG-KIND-MISMATCH: expected a <$expectedRoot> configuration."
        }
        val document = XmlSpans.parse(existing)
        val rootSpan = document.singleRoot(expectedRoot)
        return when (contract) {
            is RestApiXmlContract.ServiceMethod -> addServiceMethod(existing, document, rootSpan, contract)
            is RestApiXmlContract.Query -> addQuery(existing, document, rootSpan, contract)
        }
    }

    fun update(
        existing: String,
        target: RestApiXmlTarget,
        contract: RestApiXmlContract,
    ): List<WorkspaceTextEdit> {
        validateDocumentKind(existing, target.expectedRoot)
        require(target.expectedRoot == contract.expectedRoot) {
            "JVW-REST-CONTRACT-KIND-MISMATCH: a contract cannot change between service and query kinds."
        }
        val document = XmlSpans.parse(existing)
        val root = document.singleRoot(target.expectedRoot)
        return when {
            target is RestApiXmlTarget.ServiceMethod && contract is RestApiXmlContract.ServiceMethod ->
                updateServiceMethod(existing, document, root, target, contract)
            target is RestApiXmlTarget.Query && contract is RestApiXmlContract.Query ->
                updateQuery(existing, document, root, target, contract)
            else -> error("JVW-REST-CONTRACT-KIND-MISMATCH: the selected contract kind changed.")
        }.also { edits ->
            require(edits.isNotEmpty()) {
                "JVW-REST-CONTRACT-NOOP: the visual contract is identical to the indexed source."
            }
        }
    }

    fun remove(existing: String, target: RestApiXmlTarget): List<WorkspaceTextEdit> {
        validateDocumentKind(existing, target.expectedRoot)
        val document = XmlSpans.parse(existing)
        val root = document.singleRoot(target.expectedRoot)
        val selected = when (target) {
            is RestApiXmlTarget.ServiceMethod -> findServiceMethod(document, root, target)
            is RestApiXmlTarget.Query -> findQuery(document, root, target)
        }
        val range = removalRange(existing, selected)
        return listOf(
            WorkspaceTextEdit(
                range.first,
                range.last + 1,
                existing.substring(range.first, range.last + 1),
                "",
            ),
        )
    }

    private fun updateServiceMethod(
        source: String,
        document: XmlSpans,
        root: XmlElementSpan,
        target: RestApiXmlTarget.ServiceMethod,
        contract: RestApiXmlContract.ServiceMethod,
    ): List<WorkspaceTextEdit> {
        validateIdentifier(contract.serviceName, "service name")
        validateIdentifier(contract.methodName, "method name")
        validateParameters(contract.parameters, allowMissingType = true)
        require(contract.serviceName == target.serviceName) {
            "JVW-REST-SERVICE-MOVE-UNSUPPORTED: keep the existing service name or add a new contract and remove this one."
        }
        val method = findServiceMethod(document, root, target)
        val signature = contract.parameters.joinToString(",") { it.javaType.trim() }
        document.directChildren(root, "service").forEach { service ->
            if (document.attribute(service, "name") == contract.serviceName) {
                document.directChildren(service, "method").filterNot { it.openStart == method.openStart }.forEach { other ->
                    val otherSignature = document.directChildren(other, "param")
                        .joinToString(",") { document.attribute(it, "type").orEmpty() }
                    require(document.attribute(other, "name") != contract.methodName || otherSignature != signature) {
                        "JVW-REST-CONTRACT-DUPLICATE: this service method signature is already exposed."
                    }
                }
            }
        }
        require(!method.selfClosing && method.closeStart != null) {
            "JVW-REST-METHOD-SELF-CLOSING: expand the existing method before editing parameters."
        }
        val edits = mutableListOf<WorkspaceTextEdit>()
        document.openingAttributesEdit(method, mapOf("name" to contract.methodName))?.let(edits::add)
        val existingParameters = document.directChildren(method, "param")
        existingParameters.map { removalRange(source, it) }.forEach { range ->
            edits += WorkspaceTextEdit(
                range.first,
                range.last + 1,
                source.substring(range.first, range.last + 1),
                "",
            )
        }
        val newline = newline(source)
        if (contract.parameters.isNotEmpty()) {
            val methodIndent = lineIndent(source, method.openStart)
            val parameterIndent = existingParameters.firstOrNull()
                ?.let { lineIndent(source, it.openStart) }
                ?: "$methodIndent    "
            val rendered = renderParameters(contract.parameters, parameterIndent, newline, allowMissingType = true)
            edits += WorkspaceTextEdit(
                method.closeStart,
                method.closeStart,
                "",
                insertionBeforeClose(source, method.closeStart, rendered, parameterIndent, newline),
            )
        }
        return edits.filterNot { it.expectedText == it.replacement }
    }

    private fun updateQuery(
        source: String,
        document: XmlSpans,
        root: XmlElementSpan,
        target: RestApiXmlTarget.Query,
        contract: RestApiXmlContract.Query,
    ): List<WorkspaceTextEdit> {
        validateQuery(contract)
        val query = findQuery(document, root, target)
        document.directChildren(root, "query").filterNot { it.openStart == query.openStart }.forEach { other ->
            require(
                document.attribute(other, "name") != contract.name ||
                    document.attribute(other, "entity") != contract.entityName,
            ) { "JVW-REST-CONTRACT-DUPLICATE: this entity and query name combination already exists." }
        }
        require(!query.selfClosing && query.closeStart != null) {
            "JVW-REST-QUERY-SELF-CLOSING: expand the existing query before editing it."
        }
        val edits = mutableListOf<WorkspaceTextEdit>()
        document.openingAttributesEdit(
            query,
            mapOf(
                "name" to contract.name,
                "entity" to contract.entityName,
                "fetchPlan" to contract.fetchPlan,
            ),
        )?.let(edits::add)
        val newline = newline(source)
        val queryIndent = lineIndent(source, query.openStart)
        val innerIndent = "$queryIndent    "
        val jpql = document.directChildren(query, "jpql").firstOrNull()
        if (jpql != null && !jpql.selfClosing && jpql.closeStart != null) {
            val replacement = "<![CDATA[${cdata(contract.jpql)}]]>"
            val expected = source.substring(jpql.openEnd, jpql.closeStart)
            if (expected != replacement) {
                edits += WorkspaceTextEdit(jpql.openEnd, jpql.closeStart, expected, replacement)
            }
        } else if (jpql?.selfClosing == true) {
            val range = removalRange(source, jpql)
            edits += WorkspaceTextEdit(
                range.first,
                range.last + 1,
                source.substring(range.first, range.last + 1),
                "",
            )
        }
        val params = document.directChildren(query, "params").firstOrNull()
        if (params != null && !params.selfClosing && params.closeStart != null) {
            val existingParameters = document.directChildren(params, "param")
            existingParameters.map { removalRange(source, it) }.forEach { range ->
                edits += WorkspaceTextEdit(
                    range.first,
                    range.last + 1,
                    source.substring(range.first, range.last + 1),
                    "",
                )
            }
            if (contract.parameters.isNotEmpty()) {
                val parameterIndent = existingParameters.firstOrNull()
                    ?.let { lineIndent(source, it.openStart) }
                    ?: "${lineIndent(source, params.openStart)}    "
                val rendered = renderParameters(contract.parameters, parameterIndent, newline, allowMissingType = false)
                edits += WorkspaceTextEdit(
                    params.closeStart,
                    params.closeStart,
                    "",
                    insertionBeforeClose(source, params.closeStart, rendered, parameterIndent, newline),
                )
            }
        } else if (params?.selfClosing == true && contract.parameters.isNotEmpty()) {
            val opening = source.substring(params.openStart, params.openEnd)
            val expanded = buildString {
                append(opening.replace(Regex("""/\s*>$"""), ">"))
                append(newline)
                append(renderParameters(contract.parameters, "$innerIndent    ", newline, allowMissingType = false))
                append(innerIndent).append("</params>")
            }
            edits += WorkspaceTextEdit(params.openStart, params.openEnd, opening, expanded)
        }
        val missingChildren = buildString {
            if (jpql == null || jpql.selfClosing) {
                append(innerIndent).append("<jpql><![CDATA[").append(cdata(contract.jpql))
                    .append("]]></jpql>").append(newline)
            }
            if (params == null && contract.parameters.isNotEmpty()) {
                append(innerIndent).append("<params>").append(newline)
                append(renderParameters(contract.parameters, "$innerIndent    ", newline, allowMissingType = false))
                append(innerIndent).append("</params>").append(newline)
            }
        }
        if (missingChildren.isNotEmpty()) {
            edits += WorkspaceTextEdit(
                query.closeStart,
                query.closeStart,
                "",
                insertionBeforeClose(source, query.closeStart, missingChildren, innerIndent, newline),
            )
        }
        return edits.filterNot { it.expectedText == it.replacement }
    }

    private fun findServiceMethod(
        document: XmlSpans,
        root: XmlElementSpan,
        target: RestApiXmlTarget.ServiceMethod,
    ): XmlElementSpan {
        val matches = document.directChildren(root, "service")
            .filter { document.attribute(it, "name") == target.serviceName }
            .flatMap { service ->
                document.directChildren(service, "method").filter { method ->
                    document.attribute(method, "name") == target.methodName &&
                        document.directChildren(method, "param")
                            .map { document.attribute(it, "type").orEmpty() } == target.parameterTypes
                }
            }
        return matches.singleOrNull()
            ?: error("JVW-REST-CONTRACT-TARGET-MISSING: the indexed service method is missing or ambiguous.")
    }

    private fun findQuery(
        document: XmlSpans,
        root: XmlElementSpan,
        target: RestApiXmlTarget.Query,
    ): XmlElementSpan = document.directChildren(root, "query").singleOrNull {
        document.attribute(it, "name") == target.name &&
            document.attribute(it, "entity") == target.entityName
    } ?: error("JVW-REST-CONTRACT-TARGET-MISSING: the indexed query is missing or ambiguous.")

    private fun addServiceMethod(
        source: String,
        document: XmlSpans,
        root: XmlElementSpan,
        contract: RestApiXmlContract.ServiceMethod,
    ): WorkspaceTextEdit {
        validateIdentifier(contract.serviceName, "service name")
        validateIdentifier(contract.methodName, "method name")
        validateParameters(contract.parameters, allowMissingType = true)
        val services = document.directChildren(root, "service")
        val service = services.firstOrNull { document.attribute(it, "name") == contract.serviceName }
        val signature = contract.parameters.joinToString(",") { it.javaType.trim() }
        services.forEach { candidate ->
            document.directChildren(candidate, "method").forEach { method ->
                if (document.attribute(candidate, "name") == contract.serviceName &&
                    document.attribute(method, "name") == contract.methodName
                ) {
                    val existingSignature = document.directChildren(method, "param")
                        .joinToString(",") { document.attribute(it, "type").orEmpty() }
                    require(existingSignature != signature) {
                        "JVW-REST-CONTRACT-DUPLICATE: this service method signature is already exposed."
                    }
                }
            }
        }
        val newline = newline(source)
        return if (service != null) {
            require(!service.selfClosing && service.closeStart != null) {
                "JVW-REST-SERVICE-SELF-CLOSING: expand the existing service element before adding a method."
            }
            val serviceIndent = lineIndent(source, service.openStart)
            val methodIndent = document.directChildren(service, "method").firstOrNull()
                ?.let { lineIndent(source, it.openStart) }
                ?: "$serviceIndent    "
            val parameterIndent = "$methodIndent    "
            val rendered = renderMethod(contract, methodIndent, parameterIndent, newline)
            WorkspaceTextEdit(
                startOffset = service.closeStart,
                endOffset = service.closeStart,
                expectedText = "",
                replacement = insertionBeforeClose(source, service.closeStart, rendered, methodIndent, newline),
            )
        } else {
            require(root.closeStart != null) { "JVW-REST-CONFIG-ROOT-UNCLOSED: REST configuration root is not closed." }
            val childIndent = document.directChildren(root).firstOrNull()
                ?.let { lineIndent(source, it.openStart) }
                ?: "${lineIndent(source, root.openStart)}    "
            val rendered = buildString {
                append(childIndent).append("<service name=\"").append(xml(contract.serviceName)).append("\">").append(newline)
                append(renderMethod(contract, "$childIndent    ", "$childIndent        ", newline))
                append(childIndent).append("</service>").append(newline)
            }
            WorkspaceTextEdit(
                startOffset = root.closeStart,
                endOffset = root.closeStart,
                expectedText = "",
                replacement = insertionBeforeClose(source, root.closeStart, rendered, childIndent, newline),
            )
        }
    }

    private fun addQuery(
        source: String,
        document: XmlSpans,
        root: XmlElementSpan,
        contract: RestApiXmlContract.Query,
    ): WorkspaceTextEdit {
        validateQuery(contract)
        document.directChildren(root, "query").forEach { existing ->
            require(
                document.attribute(existing, "name") != contract.name ||
                    document.attribute(existing, "entity") != contract.entityName,
            ) {
                "JVW-REST-CONTRACT-DUPLICATE: this entity and query name combination already exists."
            }
        }
        require(root.closeStart != null) { "JVW-REST-CONFIG-ROOT-UNCLOSED: REST configuration root is not closed." }
        val newline = newline(source)
        val indent = document.directChildren(root).firstOrNull()
            ?.let { lineIndent(source, it.openStart) }
            ?: "${lineIndent(source, root.openStart)}    "
        val inner = "$indent    "
        val parameterIndent = "$inner    "
        val rendered = buildString {
            append(indent).append("<query name=\"").append(xml(contract.name))
                .append("\" entity=\"").append(xml(contract.entityName))
                .append("\" fetchPlan=\"").append(xml(contract.fetchPlan)).append("\">").append(newline)
            append(inner).append("<jpql><![CDATA[").append(cdata(contract.jpql)).append("]]></jpql>").append(newline)
            if (contract.parameters.isNotEmpty()) {
                append(inner).append("<params>").append(newline)
                contract.parameters.forEach { parameter ->
                    append(parameterIndent).append("<param name=\"").append(xml(parameter.name))
                        .append("\" type=\"").append(xml(parameter.javaType)).append("\"/>").append(newline)
                }
                append(inner).append("</params>").append(newline)
            }
            append(indent).append("</query>").append(newline)
        }
        return WorkspaceTextEdit(
            startOffset = root.closeStart,
            endOffset = root.closeStart,
            expectedText = "",
            replacement = insertionBeforeClose(source, root.closeStart, rendered, indent, newline),
        )
    }

    private fun renderMethod(
        contract: RestApiXmlContract.ServiceMethod,
        methodIndent: String,
        parameterIndent: String,
        newline: String,
    ): String = buildString {
        append(methodIndent).append("<method name=\"").append(xml(contract.methodName)).append("\">").append(newline)
        contract.parameters.forEach { parameter ->
            append(parameterIndent).append("<param name=\"").append(xml(parameter.name)).append('"')
            if (parameter.javaType.isNotBlank()) append(" type=\"").append(xml(parameter.javaType)).append('"')
            append("/>").append(newline)
        }
        append(methodIndent).append("</method>").append(newline)
    }

    private fun renderParameters(
        parameters: List<RestApiXmlParameter>,
        indent: String,
        newline: String,
        allowMissingType: Boolean,
    ): String = buildString {
        parameters.forEach { parameter ->
            append(indent).append("<param name=\"").append(xml(parameter.name)).append('"')
            if (!allowMissingType || parameter.javaType.isNotBlank()) {
                append(" type=\"").append(xml(parameter.javaType)).append('"')
            }
            append("/>").append(newline)
        }
    }

    private fun insertionBeforeClose(
        source: String,
        offset: Int,
        rendered: String,
        indent: String,
        newline: String,
    ): String {
        val lineStart = source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val prefix = source.substring(lineStart, offset)
        return if (prefix.isBlank()) {
            rendered.removePrefix(prefix) + prefix
        } else {
            "$newline$rendered$indent"
        }
    }

    private fun validateParameters(parameters: List<RestApiXmlParameter>, allowMissingType: Boolean) {
        require(parameters.size <= 100) { "JVW-REST-PARAMETER-LIMIT: at most 100 parameters are supported." }
        require(parameters.map { it.name }.toSet().size == parameters.size) {
            "JVW-REST-PARAMETER-DUPLICATE: parameter names must be unique."
        }
        parameters.forEach { parameter ->
            validateIdentifier(parameter.name, "parameter name")
            require(
                (allowMissingType && parameter.javaType.isBlank()) ||
                    JAVA_TYPE.matches(parameter.javaType) && parameter.javaType.length <= 300,
            ) { "JVW-REST-PARAMETER-TYPE-INVALID: ${parameter.javaType} is not a supported Java type spelling." }
        }
    }

    private fun validateQuery(contract: RestApiXmlContract.Query) {
        validateIdentifier(contract.name, "query name")
        validateIdentifier(contract.entityName, "entity name")
        require(contract.fetchPlan.isNotBlank() && contract.fetchPlan.length <= 240) {
            "JVW-REST-QUERY-FETCH-PLAN-INVALID: a fetch plan is required."
        }
        require(contract.jpql.isNotBlank() && contract.jpql.length <= 100_000) {
            "JVW-REST-QUERY-JPQL-INVALID: a bounded JPQL select query is required."
        }
        require(contract.jpql.trimStart().startsWith("select", ignoreCase = true)) {
            "JVW-REST-QUERY-NOT-READ-ONLY: predefined REST queries must be read-only select queries."
        }
        validateParameters(contract.parameters, allowMissingType = false)
        val placeholders = JPQL_PARAMETER.findAll(contract.jpql)
            .map { it.groupValues[1] }
            .toSet()
        val declared = contract.parameters.map(RestApiXmlParameter::name).toSet()
        require(placeholders == declared) {
            val missing = (placeholders - declared).sorted()
            val unused = (declared - placeholders).sorted()
            "JVW-REST-QUERY-PARAMETER-MISMATCH: JPQL and declared parameters differ" +
                buildList {
                    if (missing.isNotEmpty()) add("undeclared ${missing.joinToString()}")
                    if (unused.isNotEmpty()) add("unused ${unused.joinToString()}")
                }.joinToString(prefix = " (", postfix = ").")
        }
    }

    private fun validateDocumentKind(existing: String, expectedRoot: String) {
        val root = secureRoot(existing)
            ?: error("JVW-REST-CONFIG-MALFORMED: the REST configuration is not well-formed XML.")
        require(root == expectedRoot) {
            "JVW-REST-CONFIG-KIND-MISMATCH: expected a <$expectedRoot> configuration."
        }
    }

    private fun removalRange(source: String, element: XmlElementSpan): IntRange {
        val elementEnd = element.closeEnd ?: element.openEnd
        val lineStart = source.lastIndexOf('\n', (element.openStart - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val newlineStart = source.indexOf('\n', elementEnd)
        val lineEnd = if (newlineStart < 0) source.length else newlineStart + 1
        val before = source.substring(lineStart, element.openStart)
        val after = source.substring(elementEnd, if (newlineStart < 0) source.length else newlineStart)
        return if (before.isBlank() && after.isBlank()) {
            lineStart until lineEnd
        } else {
            element.openStart until elementEnd
        }
    }

    private fun validateIdentifier(value: String, label: String) {
        require(value.isNotBlank() && value.length <= 240 && IDENTIFIER.matches(value)) {
            "JVW-REST-IDENTIFIER-INVALID: $label contains unsupported characters."
        }
    }

    private fun secureRoot(content: String): String? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.setExpandEntityReferences(false)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(content)))
        document.documentElement.localName ?: document.documentElement.tagName.substringAfter(':')
    }.getOrNull()

    private fun newline(source: String) = if ("\r\n" in source) "\r\n" else "\n"
    private fun lineIndent(source: String, offset: Int): String {
        val start = source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        return source.substring(start, offset).takeWhile { it == ' ' || it == '\t' }
    }
    private fun xml(value: String): String = value
        .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    private fun cdata(value: String): String = value.replace("]]>", "]]]]><![CDATA[>")

    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_.:$-]*""")
    private val JAVA_TYPE = Regex("""[A-Za-z_$][A-Za-z0-9_$.]*(?:\[\])*""")
    private val JPQL_PARAMETER = Regex(""":([A-Za-z_][A-Za-z0-9_]*)""")
}

sealed interface RestApiXmlContract {
    data class ServiceMethod(
        val serviceName: String,
        val methodName: String,
        val parameters: List<RestApiXmlParameter>,
    ) : RestApiXmlContract

    data class Query(
        val name: String,
        val entityName: String,
        val fetchPlan: String,
        val jpql: String,
        val parameters: List<RestApiXmlParameter>,
    ) : RestApiXmlContract
}

data class RestApiXmlParameter(val name: String, val javaType: String = "")

sealed interface RestApiXmlTarget {
    val expectedRoot: String

    data class ServiceMethod(
        val serviceName: String,
        val methodName: String,
        val parameterTypes: List<String>,
    ) : RestApiXmlTarget {
        override val expectedRoot: String = "services"
    }

    data class Query(
        val name: String,
        val entityName: String,
    ) : RestApiXmlTarget {
        override val expectedRoot: String = "queries"
    }
}

private val RestApiXmlContract.expectedRoot: String
    get() = when (this) {
        is RestApiXmlContract.ServiceMethod -> "services"
        is RestApiXmlContract.Query -> "queries"
    }

private data class XmlElementSpan(
    val name: String,
    val openStart: Int,
    val openEnd: Int,
    val closeStart: Int?,
    val closeEnd: Int?,
    val parentOpenStart: Int?,
    val selfClosing: Boolean,
)

private class XmlSpans private constructor(
    private val source: String,
    private val elements: List<XmlElementSpan>,
) {
    fun singleRoot(name: String): XmlElementSpan =
        elements.singleOrNull { it.parentOpenStart == null && it.name == name }
            ?: error("JVW-REST-CONFIG-ROOT-INVALID: expected exactly one <$name> root.")

    fun directChildren(parent: XmlElementSpan, name: String? = null): List<XmlElementSpan> =
        elements.filter { it.parentOpenStart == parent.openStart && (name == null || it.name == name) }

    fun attribute(element: XmlElementSpan, name: String): String? {
        val opening = source.substring(element.openStart, element.openEnd)
        return ATTRIBUTE.findAll(opening).firstOrNull { it.groupValues[1].substringAfter(':') == name }
            ?.let { match -> match.groupValues[2].ifEmpty { match.groupValues[3] } }
            ?.let(::unescape)
    }

    fun openingAttributesEdit(
        element: XmlElementSpan,
        desired: Map<String, String>,
    ): WorkspaceTextEdit? {
        val opening = source.substring(element.openStart, element.openEnd)
        var patched = opening
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        val present = mutableSetOf<String>()
        ATTRIBUTE.findAll(opening).forEach { match ->
            val localName = match.groupValues[1].substringAfter(':')
            val replacement = desired[localName] ?: return@forEach
            present += localName
            val valueGroup = match.groups[2] ?: match.groups[3] ?: return@forEach
            replacements += Triple(
                valueGroup.range.first,
                valueGroup.range.last + 1,
                escapeAttribute(replacement),
            )
        }
        replacements.sortedByDescending { it.first }.forEach { (start, end, replacement) ->
            patched = patched.replaceRange(start, end, replacement)
        }
        val missing = desired.filterKeys { it !in present }
        if (missing.isNotEmpty()) {
            val close = patched.lastIndexOf('>')
            val insertion = if (close > 0 && patched[close - 1] == '/') close - 1 else close
            val attributes = missing.entries.joinToString(separator = "") { (name, value) ->
                " $name=\"${escapeAttribute(value)}\""
            }
            patched = patched.substring(0, insertion) + attributes + patched.substring(insertion)
        }
        return if (patched == opening) null else WorkspaceTextEdit(
            element.openStart,
            element.openEnd,
            opening,
            patched,
        )
    }

    companion object {
        private val ATTRIBUTE = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

        fun parse(source: String): XmlSpans {
            data class Open(val name: String, val start: Int, val end: Int, val parent: Int?)
            val open = mutableListOf<Open>()
            val result = mutableListOf<XmlElementSpan>()
            var cursor = 0
            while (cursor < source.length) {
                val start = source.indexOf('<', cursor)
                if (start < 0) break
                when {
                    source.startsWith("<!--", start) -> cursor = skip(source, start, "-->")
                    source.startsWith("<![CDATA[", start) -> cursor = skip(source, start, "]]>")
                    source.startsWith("<?", start) -> cursor = skip(source, start, "?>")
                    source.startsWith("<!", start) -> cursor = tagEnd(source, start) + 1
                    else -> {
                        val end = tagEnd(source, start)
                        val body = source.substring(start + 1, end).trim()
                        val closing = body.startsWith("/")
                        val selfClosing = body.endsWith("/")
                        val name = body.removePrefix("/").takeWhile { !it.isWhitespace() && it != '/' && it != '>' }
                            .substringAfter(':')
                        require(name.isNotBlank()) { "JVW-REST-CONFIG-TOKEN-INVALID: malformed XML tag." }
                        if (closing) {
                            val current = open.removeLastOrNull()
                                ?: error("JVW-REST-CONFIG-TOKEN-INVALID: unmatched closing tag.")
                            require(current.name == name) { "JVW-REST-CONFIG-TOKEN-INVALID: mismatched closing tag." }
                            result += XmlElementSpan(
                                current.name, current.start, current.end, start, end + 1,
                                current.parent, selfClosing = false,
                            )
                        } else if (selfClosing) {
                            result += XmlElementSpan(name, start, end + 1, null, null, open.lastOrNull()?.start, true)
                        } else {
                            open += Open(name, start, end + 1, open.lastOrNull()?.start)
                        }
                        cursor = end + 1
                    }
                }
            }
            require(open.isEmpty()) { "JVW-REST-CONFIG-TOKEN-INVALID: unclosed XML tag." }
            return XmlSpans(source, result.sortedBy(XmlElementSpan::openStart))
        }

        private fun tagEnd(source: String, start: Int): Int {
            var quote: Char? = null
            var index = start + 1
            while (index < source.length) {
                val character = source[index]
                if (quote != null) {
                    if (character == quote) quote = null
                } else if (character == '\'' || character == '"') {
                    quote = character
                } else if (character == '>') {
                    return index
                }
                index++
            }
            error("JVW-REST-CONFIG-TOKEN-INVALID: unterminated XML tag.")
        }

        private fun skip(source: String, start: Int, terminator: String): Int {
            val end = source.indexOf(terminator, start + 2)
            require(end >= 0) { "JVW-REST-CONFIG-TOKEN-INVALID: unterminated XML section." }
            return end + terminator.length
        }

        private fun unescape(value: String): String = value
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        private fun escapeAttribute(value: String): String = value
            .replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")
    }
}

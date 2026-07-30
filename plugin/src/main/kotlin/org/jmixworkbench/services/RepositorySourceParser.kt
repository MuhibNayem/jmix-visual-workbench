package org.jmixworkbench.services

import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.MethodParameter
import org.jmixworkbench.model.QueryType
import org.jmixworkbench.model.RepositoryMethod
import org.jmixworkbench.model.RepositoryParameterRole
import org.jmixworkbench.model.RepositoryQueryHint

/**
 * Conservative source reader for Java/Kotlin Jmix repository interfaces.
 *
 * It recognizes abstract repository declarations only. Default methods, custom
 * fragments, and declarations containing unsupported source constructs remain
 * visible as non-editable evidence and are never rewritten by the visual tool.
 */
internal object RepositorySourceParser {

    fun parse(source: String, kotlin: Boolean): ParsedRepositorySource? {
        val declaration = REPOSITORY_INTERFACE.find(source) ?: return null
        val interfaceName = declaration.groupValues[1]
        val entityType = declaration.groupValues[2].trim()
        val idType = declaration.groupValues[3].trim()
        val bodyStart = source.indexOf('{', declaration.range.last + 1)
        if (bodyStart < 0) return null
        val bodyEnd = matchingDelimiter(source, bodyStart, '{', '}') ?: return null
        val prefix = source.substring(0, declaration.range.first)
        val members = source.substring(bodyStart + 1, bodyEnd)
        val parsedMethods = if (kotlin) {
            kotlinMethods(members, source)
        } else {
            javaMethods(members, source)
        }
        val rangedMethods = locateMethodRanges(
            source = source,
            bodyStartOffset = bodyStart + 1,
            bodyEndOffset = bodyEnd,
            methods = parsedMethods,
        )
        val explicitApplyConstraints = annotationArguments(prefix, "ApplyConstraints")
            ?.let(::booleanAnnotationValue)
        val applyConstraints = explicitApplyConstraints ?: true
        val supportedMethods = rangedMethods.mapNotNull(ParsedRepositoryMethod::method)
        return ParsedRepositorySource(
            interfaceName = interfaceName,
            entityType = entityType,
            idType = idType,
            config = DataRepositoryConfig(
                enabled = true,
                interfaceName = interfaceName,
                applyConstraints = applyConstraints,
                useNamedParameters = supportedMethods
                    .filter { it.queryType == QueryType.JPQL }
                    .any { NAMED_PARAMETER.containsMatchIn(it.query.orEmpty()) }
                    .takeIf { supportedMethods.any { method -> method.queryType == QueryType.JPQL } }
                    ?: true,
                methods = supportedMethods.toMutableList(),
            ),
            methods = rangedMethods,
            bodyCloseOffset = bodyEnd,
            repositoryApplyConstraints = explicitApplyConstraints,
        )
    }

    private fun javaMethods(body: String, source: String): List<ParsedRepositoryMethod> {
        val segments = splitJavaMembers(body)
        return segments.mapNotNull { segment ->
            val trimmed = segment.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val withoutAnnotations = removeAnnotations(trimmed)
                .replace(JAVADOC, " ")
                .replace(LINE_COMMENT, " ")
                .replace(BLOCK_COMMENT, " ")
                .trim()
                .removeSuffix(";")
                .trim()
            val signature = JAVA_METHOD.matchEntire(withoutAnnotations)
                ?: return@mapNotNull ParsedRepositoryMethod(
                    method = null,
                    sourceSignature = withoutAnnotations.take(240),
                    editable = false,
                    issue = "Default, custom, or structurally unsupported repository member.",
                    sourceText = trimmed,
                )
            val returnType = signature.groupValues[1].trim()
                .removePrefix("public ")
                .removePrefix("abstract ")
                .trim()
            val name = signature.groupValues[2]
            val parameterSource = methodParameterSource(trimmed, name)
                ?: signature.groupValues[3]
            val parameters = splitTopLevel(parameterSource).mapIndexedNotNull { index, value ->
                javaParameter(value, index)
            }
            parsedMethod(
                source = source,
                segment = trimmed,
                name = name,
                returnType = returnType,
                parameters = parameters,
            )
        }
    }

    private fun kotlinMethods(body: String, source: String): List<ParsedRepositoryMethod> {
        val declarations = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        var bracketDepth = 0
        var inString = false
        var escaped = false
        body.lines().forEach { line ->
            if (current.isNotEmpty() || line.trimStart().startsWith("@") ||
                line.trimStart().startsWith("/**") || line.contains("fun ")
            ) {
                current.appendLine(line)
                line.forEach { char ->
                    if (inString) {
                        if (escaped) escaped = false
                        else if (char == '\\') escaped = true
                        else if (char == '"') inString = false
                    } else {
                        when (char) {
                            '"' -> inString = true
                            '(' -> parenDepth++
                            ')' -> parenDepth--
                            '[' -> bracketDepth++
                            ']' -> bracketDepth--
                        }
                    }
                }
                if (
                    "fun " in current &&
                    parenDepth == 0 &&
                    bracketDepth == 0 &&
                    !inString &&
                    KOTLIN_METHOD.containsMatchIn(removeAnnotations(current.toString()).replace(JAVADOC, " "))
                ) {
                    declarations += current.toString()
                    current.clear()
                }
            }
        }
        if (current.isNotBlank()) declarations += current.toString()
        return declarations.mapNotNull { segment ->
            val withoutAnnotations = removeAnnotations(segment)
                .replace(JAVADOC, " ")
                .replace(LINE_COMMENT, " ")
                .replace(BLOCK_COMMENT, " ")
                .trim()
            val signature = KOTLIN_METHOD.find(withoutAnnotations)
                ?: return@mapNotNull ParsedRepositoryMethod(
                    method = null,
                    sourceSignature = withoutAnnotations.take(240),
                    editable = false,
                    issue = "Default, custom, or structurally unsupported Kotlin repository member.",
                    sourceText = segment.trim(),
                )
            val name = signature.groupValues[1]
            val parameterSource = methodParameterSource(segment, name)
                ?: signature.groupValues[2]
            val parameters = splitTopLevel(parameterSource).mapIndexedNotNull { index, value ->
                kotlinParameter(value, index)
            }
            val returnType = signature.groupValues[3].trim()
            val implementationTail = withoutAnnotations
                .substring(signature.range.last + 1)
                .trimStart()
            if (implementationTail.startsWith('=') || implementationTail.startsWith('{')) {
                return@mapNotNull ParsedRepositoryMethod(
                    method = null,
                    sourceSignature = "$name(${parameters.joinToString(",") { it.type }})",
                    editable = false,
                    issue = "Kotlin repository method implementations are source-owned.",
                    sourceText = segment.trim(),
                )
            }
            parsedMethod(
                source = source,
                segment = segment.trim(),
                name = name,
                returnType = returnType,
                parameters = parameters,
            )
        }
    }

    private fun parsedMethod(
        source: String,
        segment: String,
        name: String,
        returnType: String,
        parameters: List<MethodParameter>,
    ): ParsedRepositoryMethod {
        val queryArguments = annotationArguments(segment, "Query")
        val springQueryImport = source.contains("import org.springframework.data.jpa.repository.Query")
        val queryType = when {
            queryArguments == null -> QueryType.DERIVED
            springQueryImport || Regex("""\bnativeQuery\s*=\s*true\b""")
                .containsMatchIn(queryArguments) -> QueryType.NATIVE
            else -> QueryType.JPQL
        }
        val query = queryArguments?.let(::firstString)
        val properties = queryArguments?.let(::queryProperties).orEmpty()
        val fetchPlan = annotationArguments(segment, "FetchPlan")?.let(::firstString)
        val methodConstraints = annotationArguments(segment, "ApplyConstraints")
            ?.let(::booleanAnnotationValue)
        val hints = annotationArgumentBlocks(segment, "QueryHint").mapNotNull { arguments ->
            val hintName = namedString(arguments, "name")
            val hintValue = namedString(arguments, "value")
            if (hintName == null || hintValue == null) null else RepositoryQueryHint(hintName, hintValue)
        }
        val description = JAVADOC.find(segment)?.groupValues?.get(1)
            ?.lines()
            ?.joinToString("\n") { it.trim().removePrefix("*").trim() }
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val method = RepositoryMethod(
            name = name,
            returnType = returnType,
            parameters = parameters.toMutableList(),
            query = query,
            queryType = queryType,
            queryProperties = properties.toMutableList(),
            fetchPlan = fetchPlan,
            applyConstraints = methodConstraints,
            queryHints = hints.toMutableList(),
            description = description,
        )
        val unsupportedAnnotations = ANNOTATION_NAME.findAll(segment)
            .map { it.groupValues[1].substringAfterLast('.') }
            .filterNot { it in SUPPORTED_METHOD_ANNOTATIONS }
            .toSet()
        val editable = unsupportedAnnotations.isEmpty() &&
            queryType != QueryType.NATIVE &&
            !segment.contains(" default ")
        return ParsedRepositoryMethod(
            method = method,
            sourceSignature = signature(method),
            editable = editable,
            issue = when {
                queryType == QueryType.NATIVE ->
                    "Native Spring Data JPA query is source-owned; Jmix DataManager semantics cannot be guaranteed."
                unsupportedAnnotations.isNotEmpty() ->
                    "Custom annotations are source-owned: ${unsupportedAnnotations.sorted().joinToString()}."
                else -> null
            },
            sourceText = segment,
        )
    }

    private fun locateMethodRanges(
        source: String,
        bodyStartOffset: Int,
        bodyEndOffset: Int,
        methods: List<ParsedRepositoryMethod>,
    ): List<ParsedRepositoryMethod> {
        var cursor = bodyStartOffset
        return methods.map { method ->
            val start = source.indexOf(method.sourceText, cursor)
                .takeIf { it in bodyStartOffset until bodyEndOffset }
            if (start == null) {
                method.copy(
                    editable = false,
                    issue = method.issue
                        ?: "The exact source range could not be reconstructed safely.",
                )
            } else {
                val end = start + method.sourceText.length
                cursor = end
                method.copy(
                    sourceStartOffset = start,
                    sourceEndOffset = end,
                )
            }
        }
    }

    private fun javaParameter(value: String, index: Int): MethodParameter? {
        val cleaned = removeAnnotations(value).trim()
        val match = JAVA_PARAMETER.matchEntire(cleaned) ?: return null
        val type = match.groupValues[1].trim()
        val name = match.groupValues[2]
        return MethodParameter(
            name = name,
            type = type,
            bindingName = annotationArguments(value, "Param")?.let(::firstString),
            nullable = ANNOTATION_NAME.findAll(value)
                .any { it.groupValues[1].substringAfterLast('.') == "Nullable" },
            role = parameterRole(type),
        )
    }

    private fun kotlinParameter(value: String, index: Int): MethodParameter? {
        val cleaned = removeAnnotations(value).trim()
        val match = KOTLIN_PARAMETER.matchEntire(cleaned) ?: return null
        val type = match.groupValues[2].trim()
        return MethodParameter(
            name = match.groupValues[1],
            type = type.removeSuffix("?"),
            bindingName = annotationArguments(value, "Param")?.let(::firstString),
            nullable = type.endsWith('?'),
            role = parameterRole(type),
        )
    }

    private fun parameterRole(type: String): RepositoryParameterRole = when (
        type.removeSuffix("?").substringAfterLast('.').substringBefore('<')
    ) {
        "Pageable" -> RepositoryParameterRole.PAGEABLE
        "Sort" -> RepositoryParameterRole.SORT
        "FetchPlan" -> RepositoryParameterRole.FETCH_PLAN
        "JmixDataRepositoryContext" -> RepositoryParameterRole.CONTEXT
        else -> RepositoryParameterRole.VALUE
    }

    private fun splitJavaMembers(body: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var parenDepth = 0
        var braceDepth = 0
        var inString = false
        var escaped = false
        var lineComment = false
        var blockComment = false
        var index = 0
        while (index < body.length) {
            val char = body[index]
            val next = body.getOrNull(index + 1)
            when {
                lineComment -> if (char == '\n') lineComment = false
                blockComment -> if (char == '*' && next == '/') {
                    blockComment = false
                    index++
                }
                inString -> {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') inString = false
                }
                char == '/' && next == '/' -> {
                    lineComment = true
                    index++
                }
                char == '/' && next == '*' -> {
                    blockComment = true
                    index++
                }
                char == '"' -> inString = true
                char == '(' -> parenDepth++
                char == ')' -> parenDepth--
                char == '{' -> braceDepth++
                char == '}' -> braceDepth--
                char == ';' && parenDepth == 0 && braceDepth == 0 -> {
                    result += body.substring(start, index + 1)
                    start = index + 1
                }
            }
            index++
        }
        body.substring(start).takeIf(String::isNotBlank)?.let(result::add)
        return result
    }

    private fun splitTopLevel(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var paren = 0
        var angle = 0
        var bracket = 0
        var inString = false
        var escaped = false
        value.forEachIndexed { index, char ->
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '(' -> paren++
                    ')' -> paren--
                    '<' -> angle++
                    '>' -> angle--
                    '[' -> bracket++
                    ']' -> bracket--
                    ',' -> if (paren == 0 && angle == 0 && bracket == 0) {
                        result += value.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
        }
        result += value.substring(start).trim()
        return result.filter(String::isNotBlank)
    }

    /**
     * Returns the original parameter declaration so parameter annotations remain
     * available after the annotation-free signature has identified the method.
     */
    private fun methodParameterSource(source: String, methodName: String): String? {
        val declaration = Regex("""\b${Regex.escape(methodName)}\s*\(""").find(source)
            ?: return null
        val open = source.indexOf('(', declaration.range.first)
        if (open < 0) return null
        val close = matchingDelimiter(source, open, '(', ')') ?: return null
        return source.substring(open + 1, close)
    }

    private fun annotationArguments(source: String, simpleName: String): String? =
        annotationArgumentBlocks(source, simpleName).firstOrNull()

    private fun annotationArgumentBlocks(source: String, simpleName: String): List<String> {
        val starts = Regex("""@(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(simpleName)}\b""")
            .findAll(source)
        return starts.mapNotNull { match ->
            var cursor = match.range.last + 1
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            if (source.getOrNull(cursor) != '(') return@mapNotNull ""
            val end = matchingDelimiter(source, cursor, '(', ')') ?: return@mapNotNull null
            source.substring(cursor + 1, end)
        }.toList()
    }

    private fun removeAnnotations(source: String): String {
        val result = StringBuilder(source)
        val ranges = mutableListOf<IntRange>()
        ANNOTATION_NAME.findAll(source).forEach { match ->
            var end = match.range.last
            var cursor = end + 1
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            if (source.getOrNull(cursor) == '(') {
                end = matchingDelimiter(source, cursor, '(', ')') ?: end
            }
            ranges += match.range.first..end
        }
        ranges.asReversed().forEach { range ->
            for (index in range) result.setCharAt(index, ' ')
        }
        return result.toString()
    }

    private fun matchingDelimiter(
        source: String,
        start: Int,
        open: Char,
        close: Char,
    ): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < source.length) {
            val char = source[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return null
    }

    private fun booleanAnnotationValue(arguments: String): Boolean {
        val normalized = arguments.trim()
        return normalized.isBlank() ||
            normalized == "true" ||
            normalized == "value = true"
    }

    private fun firstString(arguments: String): String? =
        STRING_LITERAL.find(arguments)?.groupValues?.get(1)?.let(::unescape)

    private fun namedString(arguments: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*"((?:\\.|[^"\\])*)"""")
            .find(arguments)?.groupValues?.get(1)?.let(::unescape)

    private fun queryProperties(arguments: String): List<String> {
        val match = Regex("""\bproperties\s*=\s*[\[{]([\s\S]*?)[\]}]""").find(arguments)
            ?: return emptyList()
        return STRING_LITERAL.findAll(match.groupValues[1])
            .map { unescape(it.groupValues[1]) }
            .toList()
    }

    private fun unescape(value: String): String = buildString {
        var escaped = false
        value.forEach { char ->
            if (escaped) {
                append(
                    when (char) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> char
                    },
                )
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else {
                append(char)
            }
        }
        if (escaped) append('\\')
    }

    fun signature(method: RepositoryMethod): String =
        "${method.name}(${method.parameters.joinToString(",") { it.type.removeSuffix("?") }})"

    private val REPOSITORY_INTERFACE = Regex(
        """\binterface\s+([A-Za-z_$][A-Za-z0-9_$]*)[\s\S]{0,500}?\bJmixDataRepository\s*<\s*([^,>]+)\s*,\s*([^>]+)\s*>""",
    )
    private val JAVA_METHOD = Regex(
        """([\w$.,?<>\[\]\s]+?)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(([\s\S]*)\)""",
    )
    private val JAVA_PARAMETER = Regex(
        """([\w$.,?<>\[\]\s]+?)\s+([A-Za-z_$][A-Za-z0-9_$]*)""",
    )
    private val KOTLIN_METHOD = Regex(
        """\bfun\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(([\s\S]*?)\)\s*:\s*([^\n{=]+)""",
    )
    private val KOTLIN_PARAMETER = Regex(
        """([A-Za-z_$][A-Za-z0-9_$]*)\s*:\s*([\w$.,?<>\[\]\s]+)""",
    )
    private val JAVADOC = Regex("""(?s)/\*\*(.*?)\*/""")
    private val LINE_COMMENT = Regex("""(?m)//[^\r\n]*""")
    private val BLOCK_COMMENT = Regex("""(?s)/\*(?!\*)(.*?)\*/""")
    private val ANNOTATION_NAME = Regex("""@([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
    private val STRING_LITERAL = Regex(""""((?:\\.|[^"\\])*)"""")
    private val NAMED_PARAMETER = Regex("""(?<!:):[A-Za-z_$][A-Za-z0-9_$]*""")
    private val SUPPORTED_METHOD_ANNOTATIONS = setOf(
        "Query",
        "Param",
        "Nullable",
        "FetchPlan",
        "ApplyConstraints",
        "QueryHints",
        "QueryHint",
        "Override",
    )
}

internal data class ParsedRepositorySource(
    val interfaceName: String,
    val entityType: String,
    val idType: String,
    val config: DataRepositoryConfig,
    val methods: List<ParsedRepositoryMethod>,
    val bodyCloseOffset: Int,
    val repositoryApplyConstraints: Boolean? = null,
)

internal data class ParsedRepositoryMethod(
    val method: RepositoryMethod?,
    val sourceSignature: String,
    val editable: Boolean,
    val issue: String?,
    val sourceText: String,
    val sourceStartOffset: Int? = null,
    val sourceEndOffset: Int? = null,
)

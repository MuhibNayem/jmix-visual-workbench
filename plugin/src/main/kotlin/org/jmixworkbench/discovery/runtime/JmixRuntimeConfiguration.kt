package org.jmixworkbench.discovery.runtime

data class RuntimeConfigurationValue(
    val value: String?,
    val unresolvedPlaceholders: Set<String> = emptySet(),
)

data class ParsedRuntimeConfiguration(
    val values: Map<String, String>,
) {
    fun resolved(key: String): RuntimeConfigurationValue =
        JmixRuntimeConfigurationParser.resolve(values[key])

    fun boolean(key: String, default: Boolean): RuntimeConfigurationValue {
        val resolved = resolved(key)
        val normalized = resolved.value?.trim()?.lowercase()
        return when (normalized) {
            null, "" -> resolved.copy(value = default.toString())
            "true", "false" -> resolved.copy(value = normalized)
            else -> RuntimeConfigurationValue(null, resolved.unresolvedPlaceholders)
        }
    }
}

data class ParsedYamlRuntimeDocument(
    val profiles: Set<String>,
    val profileScoped: Boolean,
    val configuration: ParsedRuntimeConfiguration,
)

/**
 * A bounded parser for the Spring Boot/Jmix runtime keys needed by preview and hot deployment.
 *
 * It deliberately does not read environment variables or execute Spring configuration code.
 * Placeholder defaults are resolved, while environment-only placeholders remain explicit so an
 * IDE run configuration can be added as a higher-precedence source by the host adapter.
 */
object JmixRuntimeConfigurationParser {
    fun parseProperties(content: String): ParsedRuntimeConfiguration {
        val values = linkedMapOf<String, String>()
        joinPropertyContinuations(content).forEach { logicalLine ->
            val line = logicalLine.trim()
            if (line.isBlank() || line.startsWith('#') || line.startsWith('!')) return@forEach
            val separator = propertySeparator(line)
            if (separator < 0) return@forEach
            val key = line.substring(0, separator).trim()
            if (key.isBlank()) return@forEach
            values[key] = line.substring(separator + 1).trim()
        }
        return ParsedRuntimeConfiguration(values)
    }

    fun parseYaml(content: String): ParsedRuntimeConfiguration =
        merge(
            parseYamlDocuments(content)
                .filterNot(ParsedYamlRuntimeDocument::profileScoped)
                .map(ParsedYamlRuntimeDocument::configuration),
        )

    fun parseYamlDocuments(content: String): List<ParsedYamlRuntimeDocument> =
        splitYamlDocuments(content).map { document ->
            val configuration = parseYamlDocument(document)
            val profileExpression = configuration.values["spring.config.activate.on-profile"]
                ?: configuration.values["spring.profiles"]
            val profiles = profileExpression
                ?.split(',')
                ?.map(String::trim)
                ?.filter(PROFILE_NAME::matches)
                ?.toSet()
                .orEmpty()
            ParsedYamlRuntimeDocument(
                profiles = profiles,
                profileScoped = profileExpression != null,
                configuration = configuration,
            )
        }

    private fun parseYamlDocument(content: String): ParsedRuntimeConfiguration {
        val values = linkedMapOf<String, String>()
        val levels = mutableListOf<YamlLevel>()
        content.lineSequence().forEach { rawLine ->
            val withoutComment = stripYamlComment(rawLine)
            if (withoutComment.isBlank()) return@forEach
            val trimmed = withoutComment.trim()
            if (trimmed.startsWith('-')) return@forEach
            val separator = trimmed.indexOf(':')
            if (separator <= 0) return@forEach
            val indent = withoutComment.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            while (levels.lastOrNull()?.indent?.let { it >= indent } == true) {
                levels.removeLast()
            }
            val key = trimmed.substring(0, separator).trim().removeSurrounding("\"").removeSurrounding("'")
            if (!YAML_KEY.matches(key)) return@forEach
            val rawValue = trimmed.substring(separator + 1).trim()
            if (rawValue.isEmpty()) {
                levels += YamlLevel(indent, key)
            } else {
                val path = (levels.map(YamlLevel::key) + key).joinToString(".")
                values[path] = unquoteYaml(rawValue)
            }
        }
        return ParsedRuntimeConfiguration(values)
    }

    fun merge(configurations: List<ParsedRuntimeConfiguration>): ParsedRuntimeConfiguration =
        ParsedRuntimeConfiguration(
            buildMap {
                configurations.forEach { putAll(it.values) }
            },
        )

    fun resolve(raw: String?): RuntimeConfigurationValue {
        if (raw == null) return RuntimeConfigurationValue(null)
        val unresolved = linkedSetOf<String>()
        val resolved = PLACEHOLDER.replace(raw.trim()) { match ->
            val name = match.groupValues[1]
            val fallback = match.groupValues[2].takeIf { match.groupValues[2].isNotEmpty() }
            fallback ?: run {
                unresolved += name
                match.value
            }
        }
        return RuntimeConfigurationValue(
            value = resolved.takeIf { unresolved.isEmpty() },
            unresolvedPlaceholders = unresolved,
        )
    }

    fun normalizeContextPath(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value == "/") return ""
        return "/${value.trim('/')}"
    }

    private fun joinPropertyContinuations(content: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        content.lineSequence().forEach { line ->
            if (current.isNotEmpty()) current.append(line.trimStart()) else current.append(line)
            val trailingSlashes = current.reversed().takeWhile { it == '\\' }.count()
            if (trailingSlashes % 2 == 1) {
                current.setLength(current.length - 1)
            } else {
                result += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun propertySeparator(line: String): Int {
        var escaped = false
        var whitespace = -1
        var explicit = -1
        line.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if ((char == '=' || char == ':') && explicit < 0) {
                explicit = index
            } else if (char.isWhitespace() && whitespace < 0) {
                whitespace = index
            }
        }
        if (whitespace < 0) return explicit
        if (explicit < 0 || whitespace > explicit) return explicit
        val afterWhitespace = line.indexOfFirstFrom(whitespace) { !it.isWhitespace() }
        return if (afterWhitespace == explicit) explicit else whitespace
    }

    private fun stripYamlComment(line: String): String {
        var quote: Char? = null
        line.forEachIndexed { index, char ->
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char == '#' && (index == 0 || line[index - 1].isWhitespace()) ->
                    return line.substring(0, index)
            }
        }
        return line
    }

    private fun splitYamlDocuments(content: String): List<String> {
        val documents = mutableListOf<String>()
        val current = StringBuilder()
        content.lineSequence().forEach { line ->
            if (line.trim() == "---") {
                if (current.isNotBlank()) documents += current.toString()
                current.setLength(0)
            } else {
                current.append(line).append('\n')
            }
        }
        if (current.isNotBlank()) documents += current.toString()
        return documents.ifEmpty { listOf("") }
    }

    private fun unquoteYaml(value: String): String =
        when {
            value.length >= 2 && value.first() == '"' && value.last() == '"' ->
                value.substring(1, value.length - 1)
            value.length >= 2 && value.first() == '\'' && value.last() == '\'' ->
                value.substring(1, value.length - 1)
            else -> value
        }

    private data class YamlLevel(val indent: Int, val key: String)

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private val YAML_KEY = Regex("""[A-Za-z0-9_.-]+""")
    private val PROFILE_NAME = Regex("""[A-Za-z0-9_.-]+""")
    private val PLACEHOLDER = Regex("""\$\{([^}:]+)(?::([^}]*))?}""")
}

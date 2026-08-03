package org.jmixworkbench.discovery.static

import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.Evidence
import org.jmixworkbench.discovery.model.EvidenceConfidence
import org.jmixworkbench.discovery.model.EvidenceSourceKind

data class GradleTextInput(
    val relativePath: String,
    val content: String,
)

data class ImportedCoordinate(
    val coordinate: String,
    val sourceId: String,
)

enum class AddOnKind {
    PUBLIC,
    THIRD_PARTY,
    INTERNAL,
}

data class AddOnEvidence(
    val coordinate: String,
    val kind: AddOnKind,
    val sourceKind: EvidenceSourceKind,
    val sourceId: String,
)

data class StaticParseDiagnostic(
    val reasonCode: String,
    val message: String,
    val sourceId: String,
)

data class ParsedGradleProfile(
    val jmixVersion: Evidence<String>,
    val targetJdk: Evidence<Int>,
    val plugins: List<Evidence<String>>,
    val conventionPlugins: List<Evidence<String>>,
    val coordinates: List<Evidence<String>>,
    val includedProjects: List<Evidence<String>>,
    val includedBuilds: List<Evidence<String>>,
    val addOns: List<AddOnEvidence>,
    val diagnostics: List<StaticParseDiagnostic>,
    val canonicalJson: String,
    val digest: String,
)

/**
 * Extracts a deliberately bounded subset of Gradle and version-catalog syntax.
 *
 * This parser treats repository text only as tokens. It has no Gradle, Groovy,
 * Kotlin scripting, process, network, or repository-resolution integration.
 */
class GradleConfigParser(
    private val internalGroupPrefixes: Set<String> = emptySet(),
) {
    fun parse(
        inputs: List<GradleTextInput>,
        importedCoordinates: List<ImportedCoordinate> = emptyList(),
    ): ParsedGradleProfile {
        val orderedInputs = inputs.sortedBy { it.relativePath }
        val catalogs = orderedInputs
            .filter { it.relativePath.endsWith(".toml") }
            .map(::parseCatalog)
        val catalog = catalogs.fold(VersionCatalog.EMPTY, VersionCatalog::merge)
        val facts = MutableFacts()

        orderedInputs
            .filterNot { it.relativePath.endsWith(".toml") }
            .forEach { parseGradleText(it, catalog, facts) }
        importedCoordinates.sortedWith(compareBy(ImportedCoordinate::sourceId, ImportedCoordinate::coordinate))
            .forEach { imported ->
                addCoordinate(
                    coordinate = imported.coordinate,
                    sourceKind = EvidenceSourceKind.IMPORTED_GRADLE_MODEL,
                    sourceId = imported.sourceId,
                    confidence = EvidenceConfidence.EXACT,
                    facts = facts,
                )
            }

        val jmixVersion = mergeStringEvidence(facts.jmixVersions, "jmix-platform-version")
        val targetJdk = mergeIntEvidence(facts.targetJdks, "target-jdk")
        if (jmixVersion.confidence == EvidenceConfidence.CONFLICTING) {
            facts.diagnostics += StaticParseDiagnostic(
                reasonCode = "P2_EVIDENCE_CONFLICT",
                message = "Observed Jmix versions conflict; no version was selected.",
                sourceId = "merged-profile",
            )
        }
        if (targetJdk.confidence == EvidenceConfidence.CONFLICTING) {
            facts.diagnostics += StaticParseDiagnostic(
                reasonCode = "P2_EVIDENCE_CONFLICT",
                message = "Observed Java targets conflict; no target JDK was selected.",
                sourceId = "merged-profile",
            )
        }

        val plugins = facts.plugins.toEvidence()
        val conventions = facts.conventionPlugins.toEvidence()
        val coordinates = facts.coordinates.toEvidence()
        val includedProjects = facts.includedProjects.toEvidence()
        val includedBuilds = facts.includedBuilds.toEvidence()
        val addOns = classifyAddOns(facts.rawCoordinates)
        val diagnostics = facts.diagnostics
            .distinct()
            .sortedWith(compareBy(StaticParseDiagnostic::reasonCode, StaticParseDiagnostic::sourceId, StaticParseDiagnostic::message))
        val canonicalJson = canonicalProfileJson(
            jmixVersion = jmixVersion,
            targetJdk = targetJdk,
            plugins = plugins,
            conventions = conventions,
            coordinates = coordinates,
            includedProjects = includedProjects,
            includedBuilds = includedBuilds,
            addOns = addOns,
            diagnostics = diagnostics,
        )
        return ParsedGradleProfile(
            jmixVersion = jmixVersion,
            targetJdk = targetJdk,
            plugins = plugins,
            conventionPlugins = conventions,
            coordinates = coordinates,
            includedProjects = includedProjects,
            includedBuilds = includedBuilds,
            addOns = addOns,
            diagnostics = diagnostics,
            canonicalJson = canonicalJson,
            digest = CanonicalDiscoveryJson.sha256(canonicalJson),
        )
    }

    private fun parseGradleText(
        input: GradleTextInput,
        catalog: VersionCatalog,
        facts: MutableFacts,
    ) {
        val sourceKind = EvidenceSourceKind.STATIC_BUILD_FILE
        val fingerprint = CanonicalDiscoveryJson.sha256(input.content)
        if (!hasBalancedDelimiters(input.content)) {
            facts.diagnostics += StaticParseDiagnostic(
                reasonCode = "P2_MALFORMED_BUILD_TEXT",
                message = "Unbalanced build-file delimiters were tolerated; incomplete facts remain unknown.",
                sourceId = input.relativePath,
            )
        }

        PLUGIN_LITERAL.findAll(input.content).forEach { match ->
            val pluginId = match.groupValues[1]
            val version = match.groupValues[2].takeIf(String::isNotBlank)
            facts.plugins += StringFact(pluginId, sourceKind, input.relativePath, EvidenceConfidence.STRONG, fingerprint)
            if (isConventionPlugin(pluginId)) {
                facts.conventionPlugins += StringFact(
                    pluginId,
                    sourceKind,
                    input.relativePath,
                    EvidenceConfidence.WEAK,
                    fingerprint,
                )
            }
            if (pluginId == "io.jmix") {
                if (version != null) {
                    facts.jmixVersions += StringFact(
                        version,
                        sourceKind,
                        input.relativePath,
                        EvidenceConfidence.STRONG,
                        fingerprint,
                    )
                } else if (looksLikeDynamicJmixPlugin(match.value, input.content, match.range.last + 1)) {
                    facts.diagnostics += dynamicDiagnostic(input.relativePath, "Jmix plugin version")
                }
            }
        }

        PLUGIN_ALIAS.findAll(input.content).forEach { match ->
            val requestedAlias = normalizeAlias(match.groupValues[1].removePrefix("plugins."))
            val plugin = catalog.plugins[requestedAlias]
            if (plugin == null) {
                facts.diagnostics += unresolvedAlias(input.relativePath, match.groupValues[1])
            } else {
                val catalogFingerprint = plugin.fingerprint
                facts.plugins += StringFact(
                    plugin.id,
                    EvidenceSourceKind.VERSION_CATALOG,
                    plugin.sourceId,
                    EvidenceConfidence.STRONG,
                    catalogFingerprint,
                )
                if (isConventionPlugin(plugin.id)) {
                    facts.conventionPlugins += StringFact(
                        plugin.id,
                        EvidenceSourceKind.VERSION_CATALOG,
                        plugin.sourceId,
                        EvidenceConfidence.WEAK,
                        catalogFingerprint,
                    )
                }
                if (plugin.id == "io.jmix" && plugin.version != null) {
                    facts.jmixVersions += StringFact(
                        plugin.version,
                        EvidenceSourceKind.VERSION_CATALOG,
                        plugin.sourceId,
                        EvidenceConfidence.STRONG,
                        catalogFingerprint,
                    )
                }
            }
        }

        COORDINATE_LITERAL.findAll(input.content).forEach { match ->
            val coordinate = match.groupValues[1]
            if ('$' in coordinate) {
                facts.diagnostics += dynamicDiagnostic(input.relativePath, "dependency coordinate")
            } else {
                addCoordinate(
                    coordinate,
                    sourceKind,
                    input.relativePath,
                    EvidenceConfidence.STRONG,
                    facts,
                    fingerprint,
                )
            }
        }

        LIBRARY_ALIAS.findAll(input.content).forEach { match ->
            val rawAlias = match.groupValues[1]
            if (rawAlias.startsWith("plugins.")) return@forEach
            val library = catalog.libraries[normalizeAlias(rawAlias)]
            if (library == null) {
                facts.diagnostics += unresolvedAlias(input.relativePath, rawAlias)
            } else if (library.version == null) {
                facts.diagnostics += unresolvedAlias(input.relativePath, rawAlias)
            } else {
                addCoordinate(
                    coordinate = "${library.module}:${library.version}",
                    sourceKind = EvidenceSourceKind.VERSION_CATALOG,
                    sourceId = library.sourceId,
                    confidence = EvidenceConfidence.STRONG,
                    facts = facts,
                    fingerprint = library.fingerprint,
                )
            }
        }

        JAVA_LANGUAGE_VERSION.findAll(input.content).forEach { match ->
            facts.targetJdks += IntFact(
                value = match.groupValues[1].toInt(),
                sourceKind = sourceKind,
                sourceId = input.relativePath,
                confidence = EvidenceConfidence.STRONG,
                fingerprint = fingerprint,
            )
        }
        JAVA_VERSION_CONSTANT.findAll(input.content).forEach { match ->
            facts.targetJdks += IntFact(
                value = match.groupValues[1].toInt(),
                sourceKind = sourceKind,
                sourceId = input.relativePath,
                confidence = EvidenceConfidence.STRONG,
                fingerprint = fingerprint,
            )
        }
        if (DYNAMIC_JAVA_VERSION.containsMatchIn(input.content)) {
            facts.diagnostics += dynamicDiagnostic(input.relativePath, "Java toolchain")
        }

        INCLUDE_CALL.findAll(input.content).forEach { match ->
            val arguments = match.groupValues[1].ifBlank { match.groupValues[2] }
            QUOTED_LITERAL.findAll(arguments).forEach { literal ->
                facts.includedProjects += StringFact(
                    literal.groupValues[1],
                    sourceKind,
                    input.relativePath,
                    EvidenceConfidence.STRONG,
                    fingerprint,
                )
            }
        }
        INCLUDE_BUILD_CALL.findAll(input.content).forEach { match ->
            facts.includedBuilds += StringFact(
                match.groupValues[1],
                sourceKind,
                input.relativePath,
                EvidenceConfidence.STRONG,
                fingerprint,
            )
        }

        if (DYNAMIC_BUILD_MARKERS.any { it.containsMatchIn(input.content) } &&
            facts.diagnostics.none { it.sourceId == input.relativePath && it.reasonCode == "P2_DYNAMIC_BUILD_LOGIC" }
        ) {
            facts.diagnostics += dynamicDiagnostic(input.relativePath, "build expression")
        }
    }

    private fun addCoordinate(
        coordinate: String,
        sourceKind: EvidenceSourceKind,
        sourceId: String,
        confidence: EvidenceConfidence,
        facts: MutableFacts,
        fingerprint: String = CanonicalDiscoveryJson.sha256("$sourceId\u0000$coordinate"),
    ) {
        val parts = coordinate.split(':')
        if (parts.size != 3 || parts.any(String::isBlank)) {
            facts.diagnostics += StaticParseDiagnostic(
                reasonCode = "P2_MALFORMED_BUILD_TEXT",
                message = "Malformed dependency coordinate was ignored.",
                sourceId = sourceId,
            )
            return
        }
        val fact = StringFact(coordinate, sourceKind, sourceId, confidence, fingerprint)
        facts.rawCoordinates += fact
        facts.coordinates += if (isInternalCoordinate(coordinate)) {
            fact.copy(value = opaqueInternalCoordinate(coordinate))
        } else {
            fact
        }
        if (parts[0].startsWith("io.jmix")) {
            facts.jmixVersions += StringFact(parts[2], sourceKind, sourceId, confidence, fingerprint)
        }
    }

    private fun classifyAddOns(rawCoordinates: List<StringFact>): List<AddOnEvidence> =
        rawCoordinates.mapNotNull { fact ->
            val parts = fact.value.split(':')
            val group = parts[0]
            val artifact = parts[1]
            val kind = when {
                isInternalCoordinate(fact.value) -> AddOnKind.INTERNAL
                group.startsWith("io.jmix") && artifact != "jmix-core" -> AddOnKind.PUBLIC
                !group.startsWith("io.jmix") && ("addon" in artifact || "starter" in artifact) -> AddOnKind.THIRD_PARTY
                else -> null
            } ?: return@mapNotNull null
            AddOnEvidence(
                coordinate = if (kind == AddOnKind.INTERNAL) opaqueInternalCoordinate(fact.value) else fact.value,
                kind = kind,
                sourceKind = fact.sourceKind,
                sourceId = fact.sourceId,
            )
        }.distinct().sortedWith(compareBy({ it.kind.name }, AddOnEvidence::coordinate, AddOnEvidence::sourceId))

    private fun isInternalCoordinate(coordinate: String): Boolean {
        val group = coordinate.substringBefore(':')
        return internalGroupPrefixes.any { prefix -> group == prefix || group.startsWith("$prefix.") }
    }

    private fun opaqueInternalCoordinate(coordinate: String): String =
        "internal-addon-${CanonicalDiscoveryJson.sha256(coordinate).take(16)}"

    private fun parseCatalog(input: GradleTextInput): VersionCatalog {
        var section = ""
        val versions = linkedMapOf<String, String>()
        val rawPlugins = mutableListOf<RawCatalogEntry>()
        val rawLibraries = mutableListOf<RawCatalogEntry>()
        input.content.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.removeSurrounding("[", "]")
                return@forEach
            }
            val assignment = CATALOG_ASSIGNMENT.matchEntire(line) ?: return@forEach
            val alias = normalizeAlias(assignment.groupValues[1])
            val value = assignment.groupValues[2].trim()
            when (section) {
                "versions" -> quotedValue(value)?.let { versions[alias] = it }
                "plugins" -> rawPlugins += RawCatalogEntry(alias, value)
                "libraries" -> rawLibraries += RawCatalogEntry(alias, value)
            }
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(input.content)
        val plugins = rawPlugins.mapNotNull { entry ->
            val id = inlineTableValue(entry.value, "id") ?: return@mapNotNull null
            val version = inlineTableValue(entry.value, "version")
                ?: inlineTableValue(entry.value, "version.ref")?.let { versions[normalizeAlias(it)] }
            entry.alias to CatalogPlugin(id, version, input.relativePath, fingerprint)
        }.toMap()
        val libraries = rawLibraries.mapNotNull { entry ->
            val module = inlineTableValue(entry.value, "module") ?: return@mapNotNull null
            val version = inlineTableValue(entry.value, "version")
                ?: inlineTableValue(entry.value, "version.ref")?.let { versions[normalizeAlias(it)] }
            entry.alias to CatalogLibrary(module, version, input.relativePath, fingerprint)
        }.toMap()
        return VersionCatalog(plugins, libraries)
    }

    private fun mergeStringEvidence(facts: List<StringFact>, unknownSourceId: String): Evidence<String> {
        if (facts.isEmpty()) return unknownEvidence(unknownSourceId)
        val values = facts.map(StringFact::value).distinct().sorted()
        if (values.size > 1) {
            return Evidence(
                value = null,
                sourceKind = EvidenceSourceKind.UNKNOWN,
                sourceId = "merged:$unknownSourceId",
                confidence = EvidenceConfidence.CONFLICTING,
                observedFingerprint = mergedFingerprint(facts),
                observedValues = values,
            )
        }
        val preferred = facts.sortedWith(
            compareByDescending<StringFact> { confidenceRank(it.confidence) }
                .thenBy(StringFact::sourceId),
        ).first()
        return Evidence(
            value = values.single(),
            sourceKind = preferred.sourceKind,
            sourceId = preferred.sourceId,
            confidence = facts.maxBy { confidenceRank(it.confidence) }.confidence,
            observedFingerprint = mergedFingerprint(facts),
            observedValues = values,
        )
    }

    private fun mergeIntEvidence(facts: List<IntFact>, unknownSourceId: String): Evidence<Int> {
        if (facts.isEmpty()) return unknownEvidence(unknownSourceId)
        val values = facts.map(IntFact::value).distinct().sorted()
        if (values.size > 1) {
            return Evidence(
                value = null,
                sourceKind = EvidenceSourceKind.UNKNOWN,
                sourceId = "merged:$unknownSourceId",
                confidence = EvidenceConfidence.CONFLICTING,
                observedFingerprint = mergedFingerprint(facts),
                observedValues = values,
            )
        }
        val preferred = facts.sortedWith(
            compareByDescending<IntFact> { confidenceRank(it.confidence) }
                .thenBy(IntFact::sourceId),
        ).first()
        return Evidence(
            value = values.single(),
            sourceKind = preferred.sourceKind,
            sourceId = preferred.sourceId,
            confidence = facts.maxBy { confidenceRank(it.confidence) }.confidence,
            observedFingerprint = mergedFingerprint(facts),
            observedValues = values,
        )
    }

    private fun List<StringFact>.toEvidence(): List<Evidence<String>> =
        distinct().sortedWith(compareBy(StringFact::value, StringFact::sourceId, { it.sourceKind.name })).map { fact ->
            Evidence(
                value = fact.value,
                sourceKind = fact.sourceKind,
                sourceId = fact.sourceId,
                confidence = fact.confidence,
                observedFingerprint = fact.fingerprint,
                observedValues = listOf(fact.value),
            )
        }

    private fun canonicalProfileJson(
        jmixVersion: Evidence<String>,
        targetJdk: Evidence<Int>,
        plugins: List<Evidence<String>>,
        conventions: List<Evidence<String>>,
        coordinates: List<Evidence<String>>,
        includedProjects: List<Evidence<String>>,
        includedBuilds: List<Evidence<String>>,
        addOns: List<AddOnEvidence>,
        diagnostics: List<StaticParseDiagnostic>,
    ): String =
        canonicalJson(
            mapOf(
                "addOns" to addOns.map {
                    mapOf(
                        "coordinate" to it.coordinate,
                        "kind" to it.kind.name,
                        "sourceId" to it.sourceId,
                        "sourceKind" to it.sourceKind.name,
                    )
                },
                "conventionPlugins" to conventions.map(::evidenceMap),
                "coordinates" to coordinates.map(::evidenceMap),
                "diagnostics" to diagnostics.map {
                    mapOf(
                        "message" to it.message,
                        "reasonCode" to it.reasonCode,
                        "sourceId" to it.sourceId,
                    )
                },
                "includedBuilds" to includedBuilds.map(::evidenceMap),
                "includedProjects" to includedProjects.map(::evidenceMap),
                "jmixVersion" to evidenceMap(jmixVersion),
                "plugins" to plugins.map(::evidenceMap),
                "targetJdk" to evidenceMap(targetJdk),
            ),
        )

    private fun evidenceMap(evidence: Evidence<*>): Map<String, Any?> =
        mapOf(
            "confidence" to evidence.confidence.name,
            "observedFingerprint" to evidence.observedFingerprint,
            "observedValues" to evidence.observedValues,
            "sourceId" to evidence.sourceId,
            "sourceKind" to evidence.sourceKind.name,
            "value" to evidence.value,
        )

    private fun canonicalJson(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"${escapeJson(value)}\""
            is Boolean, is Number -> value.toString()
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
            is Map<*, *> -> value.entries
                .map { (key, item) -> key as String to item }
                .sortedBy { it.first }
                .joinToString(prefix = "{", postfix = "}") { (key, item) ->
                    "${canonicalJson(key)}:${canonicalJson(item)}"
                }
            else -> error("Unsupported parser digest value: ${value::class.qualifiedName}")
        }

    private fun escapeJson(value: String): String =
        buildString {
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }

    private fun dynamicDiagnostic(sourceId: String, subject: String): StaticParseDiagnostic =
        StaticParseDiagnostic(
            reasonCode = "P2_DYNAMIC_BUILD_LOGIC",
            message = "$subject uses a dynamic expression and remains unresolved.",
            sourceId = sourceId,
        )

    private fun unresolvedAlias(sourceId: String, alias: String): StaticParseDiagnostic =
        StaticParseDiagnostic(
            reasonCode = "P2_ALIAS_UNRESOLVED",
            message = "Version-catalog alias '$alias' could not be resolved statically.",
            sourceId = sourceId,
        )

    private fun looksLikeDynamicJmixPlugin(matched: String, content: String, endExclusive: Int): Boolean {
        if ("version" in matched) return false
        val suffix = content.substring(endExclusive, minOf(content.length, endExclusive + 96))
        return Regex("""^\s*version\s+[A-Za-z_${'$'}]""").containsMatchIn(suffix)
    }

    private fun hasBalancedDelimiters(content: String): Boolean {
        val stack = ArrayDeque<Char>()
        var quote: Char? = null
        var escaped = false
        for (character in content) {
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == quote) {
                    quote = null
                }
                continue
            }
            if (character == '"' || character == '\'') {
                quote = character
                continue
            }
            when (character) {
                '{', '(', '[' -> stack.addLast(character)
                '}' -> if (stack.removeLastOrNull() != '{') return false
                ')' -> if (stack.removeLastOrNull() != '(') return false
                ']' -> if (stack.removeLastOrNull() != '[') return false
            }
        }
        return quote == null && stack.isEmpty()
    }

    private fun isConventionPlugin(pluginId: String): Boolean =
        pluginId != "io.jmix" && ("convention" in pluginId || pluginId.endsWith(".conventions"))

    private fun normalizeAlias(alias: String): String = alias.trim().replace('.', '-')

    private fun quotedValue(value: String): String? =
        QUOTED_LITERAL.matchEntire(value)?.groupValues?.get(1)

    private fun inlineTableValue(table: String, key: String): String? {
        val keyPattern = Regex.escape(key)
        return Regex("""(?:^|[,{]\s*)$keyPattern\s*=\s*["']([^"']+)["']""")
            .find(table)
            ?.groupValues
            ?.get(1)
    }

    private fun mergedFingerprint(facts: List<Fact<*>>): String =
        CanonicalDiscoveryJson.sha256(
            facts.sortedWith(compareBy({ it.sourceId }, { it.fingerprint }))
                .joinToString("\u0000") { "${it.sourceId}:${it.fingerprint}" },
        )

    private fun <T> unknownEvidence(sourceId: String): Evidence<T> =
        Evidence(
            value = null,
            sourceKind = EvidenceSourceKind.UNKNOWN,
            sourceId = sourceId,
            confidence = EvidenceConfidence.WEAK,
            observedFingerprint = "",
        )

    private fun confidenceRank(confidence: EvidenceConfidence): Int =
        when (confidence) {
            EvidenceConfidence.EXACT -> 4
            EvidenceConfidence.STRONG -> 3
            EvidenceConfidence.WEAK -> 2
            EvidenceConfidence.CONFLICTING -> 1
        }

    private data class MutableFacts(
        val jmixVersions: MutableList<StringFact> = mutableListOf(),
        val targetJdks: MutableList<IntFact> = mutableListOf(),
        val plugins: MutableList<StringFact> = mutableListOf(),
        val conventionPlugins: MutableList<StringFact> = mutableListOf(),
        val coordinates: MutableList<StringFact> = mutableListOf(),
        val rawCoordinates: MutableList<StringFact> = mutableListOf(),
        val includedProjects: MutableList<StringFact> = mutableListOf(),
        val includedBuilds: MutableList<StringFact> = mutableListOf(),
        val diagnostics: MutableList<StaticParseDiagnostic> = mutableListOf(),
    )

    private interface Fact<T> {
        val value: T
        val sourceKind: EvidenceSourceKind
        val sourceId: String
        val confidence: EvidenceConfidence
        val fingerprint: String
    }

    private data class StringFact(
        override val value: String,
        override val sourceKind: EvidenceSourceKind,
        override val sourceId: String,
        override val confidence: EvidenceConfidence,
        override val fingerprint: String,
    ) : Fact<String>

    private data class IntFact(
        override val value: Int,
        override val sourceKind: EvidenceSourceKind,
        override val sourceId: String,
        override val confidence: EvidenceConfidence,
        override val fingerprint: String,
    ) : Fact<Int>

    private data class RawCatalogEntry(
        val alias: String,
        val value: String,
    )

    private data class CatalogPlugin(
        val id: String,
        val version: String?,
        val sourceId: String,
        val fingerprint: String,
    )

    private data class CatalogLibrary(
        val module: String,
        val version: String?,
        val sourceId: String,
        val fingerprint: String,
    )

    private data class VersionCatalog(
        val plugins: Map<String, CatalogPlugin>,
        val libraries: Map<String, CatalogLibrary>,
    ) {
        fun merge(other: VersionCatalog): VersionCatalog =
            VersionCatalog(
                plugins = plugins + other.plugins,
                libraries = libraries + other.libraries,
            )

        companion object {
            val EMPTY = VersionCatalog(emptyMap(), emptyMap())
        }
    }

    companion object {
        private val PLUGIN_LITERAL = Regex(
            """\bid\s*(?:\(\s*)?["']([^"']+)["']\s*\)?(?:\s+version\s*(?:\(\s*)?["']([^"']+)["']\s*\)?)?""",
        )
        private val PLUGIN_ALIAS = Regex("""\balias\s*\(\s*libs\.([A-Za-z0-9_.-]+)\s*\)""")
        private val LIBRARY_ALIAS = Regex(
            """\b(?:implementation|api|runtimeOnly|compileOnly|testImplementation)\s*\(\s*libs\.([A-Za-z0-9_.-]+)\s*\)""",
        )
        private val COORDINATE_LITERAL = Regex(
            """["']([A-Za-z0-9_.${'$'}{}-]+:[A-Za-z0-9_.${'$'}{}-]+:[^"' \t\r\n)]+)["']""",
        )
        private val JAVA_LANGUAGE_VERSION = Regex("""JavaLanguageVersion\.of\s*\(\s*(\d+)\s*\)""")
        private val JAVA_VERSION_CONSTANT = Regex("""JavaVersion\.VERSION_(\d+)""")
        private val DYNAMIC_JAVA_VERSION = Regex("""JavaLanguageVersion\.of\s*\(\s*[^)\d\s]""")
        private val INCLUDE_CALL = Regex(
            """(?m)^\s*include(?!Build)\s*(?:\(([^)]*)\)|([^\r\n]+))""",
        )
        private val INCLUDE_BUILD_CALL = Regex(
            """\bincludeBuild\s*(?:\(\s*)?["']([^"']+)["']\s*\)?""",
        )
        private val QUOTED_LITERAL = Regex("""["']([^"']+)["']""")
        private val CATALOG_ASSIGNMENT = Regex("""([A-Za-z0-9_.-]+)\s*=\s*(.+)""")
        private val DYNAMIC_BUILD_MARKERS = listOf(
            Regex("""providers\.gradleProperty"""),
            Regex("""project\.findProperty"""),
            Regex("""\$\{?[A-Za-z_]"""),
        )
    }
}

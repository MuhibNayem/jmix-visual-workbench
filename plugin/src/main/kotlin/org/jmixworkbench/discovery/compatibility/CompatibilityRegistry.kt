package org.jmixworkbench.discovery.compatibility

import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.CompatibilityDecision
import org.jmixworkbench.discovery.model.CompatibilityState
import org.jmixworkbench.discovery.model.ImportState
import org.jmixworkbench.discovery.model.ProfileClassification
import org.jmixworkbench.discovery.model.TrustState

fun interface FixtureEvidenceIndex {
    fun isReviewed(fixtureId: String): Boolean
}

data class CompatibilityProfile(
    val classification: ProfileClassification,
    val platformVersion: String?,
    val targetJdk: Int?,
)

data class CompatibilityContext(
    val profile: CompatibilityProfile,
    val trustState: TrustState,
    val importState: ImportState,
    val indexComplete: Boolean = true,
    val evidenceConflict: Boolean = false,
    val evidenceIds: List<String> = emptyList(),
)

class CompatibilityRegistry internal constructor(
    val version: String,
    val digest: String,
    internal val cells: List<RegistryCell>,
)

class RegistryValidationException(
    val reasonCode: String,
    message: String,
) : IllegalArgumentException("$reasonCode: $message")

internal data class RegistryCell(
    val operationId: String,
    val classification: ProfileClassification,
    val platformLine: String,
    val targetJdks: Set<Int>,
    val state: CompatibilityState,
    val reasonCode: String,
    val evidenceRefs: List<String>,
    val testedJmix: List<String>,
    val testedTargetJdks: List<Int>,
    val testedHostLanes: List<String>,
    val fixtureIds: List<String>,
    val testedAlternative: String,
) {
    fun matches(operation: String, profile: CompatibilityProfile): Boolean =
        operationId == operation &&
            classification == profile.classification &&
            profile.platformVersion.matchesLine(platformLine) &&
            profile.targetJdk in targetJdks
}

object CompatibilityRegistryLoader {
    private val allowedReadOperations = setOf(
        "discovery.snapshot",
        "discovery.inventory",
        "discovery.relationships",
        "discovery.navigate",
    )
    private val exactProfiles = mapOf(
        ProfileClassification.JMIX_2_8 to ("2.8" to setOf(17, 21)),
        ProfileClassification.JMIX_3_0 to ("3.0" to setOf(21, 25)),
    )
    private val requiredHostLanes = setOf("253", "262")

    fun loadResource(
        resourcePath: String,
        evidenceIndex: FixtureEvidenceIndex,
        classLoader: ClassLoader = CompatibilityRegistryLoader::class.java.classLoader,
    ): CompatibilityRegistry {
        val normalizedPath = resourcePath.removePrefix("/")
        val text = classLoader.getResourceAsStream(normalizedPath)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: throw RegistryValidationException(
            "P2_EVIDENCE_REFERENCE_MISSING",
            "Registry resource is missing: $resourcePath",
        )
        return load(text, evidenceIndex)
    }

    fun load(json: String, evidenceIndex: FixtureEvidenceIndex): CompatibilityRegistry {
        val root = try {
            RegistryJsonParser(json).parseObject()
        } catch (error: RegistryValidationException) {
            throw error
        } catch (error: RuntimeException) {
            throw RegistryValidationException(
                "P2_REGISTRY_MALFORMED",
                error.message ?: "Registry JSON is malformed.",
            )
        }
        val version = root.requiredString("version")
        val cells = root.requiredArray("cells").mapIndexed { index, value ->
            parseCell(value.requiredObject("cells[$index]"), index)
        }

        validate(version, cells, evidenceIndex)
        return CompatibilityRegistry(
            version = version,
            digest = CanonicalDiscoveryJson.sha256(canonicalRegistryJson(version, cells)),
            cells = cells.sortedWith(
                compareBy(
                    RegistryCell::operationId,
                    { it.classification.name },
                    RegistryCell::platformLine,
                    { it.targetJdks.sorted().joinToString(",") },
                ),
            ),
        )
    }

    private fun parseCell(value: Map<String, Any?>, index: Int): RegistryCell {
        val stateText = value.requiredString("state")
        if (stateText.trim().equals(CompatibilityState.CERTIFIED_READ_WRITE.name, ignoreCase = true)) {
            throw RegistryValidationException(
                "P2_WRITE_FORBIDDEN",
                "Phase 2 registry cell $index attempts to authorize writes.",
            )
        }
        val operationId = value.requiredString("operationId")
        if (operationId !in allowedReadOperations) {
            throw RegistryValidationException(
                "P2_WRITE_FORBIDDEN",
                "Phase 2 operation is not a reviewed read operation: $operationId",
            )
        }
        val state = enumValue<CompatibilityState>(stateText, "state")
        if (state != CompatibilityState.CERTIFIED_READ_ONLY) {
            throw RegistryValidationException(
                "P2_WRITE_FORBIDDEN",
                "Phase 2 registry cells must be CERTIFIED_READ_ONLY.",
            )
        }

        return RegistryCell(
            operationId = operationId,
            classification = enumValue(value.requiredString("classification"), "classification"),
            platformLine = value.requiredString("platformLine"),
            targetJdks = value.requiredIntList("targetJdks").toSet(),
            state = state,
            reasonCode = value.requiredString("reasonCode"),
            evidenceRefs = value.requiredStringList("evidenceRefs"),
            testedJmix = value.requiredStringList("testedJmix"),
            testedTargetJdks = value.requiredIntList("testedTargetJdks"),
            testedHostLanes = value.requiredStringList("testedHostLanes"),
            fixtureIds = value.requiredStringList("fixtureIds"),
            testedAlternative = value.requiredString("testedAlternative"),
        )
    }

    private fun validate(
        version: String,
        cells: List<RegistryCell>,
        evidenceIndex: FixtureEvidenceIndex,
    ) {
        if (version.isBlank() || cells.isEmpty()) {
            throw RegistryValidationException(
                "P2_REGISTRY_MALFORMED",
                "Registry version and cells are required.",
            )
        }

        cells.forEach { cell ->
            val exact = exactProfiles[cell.classification]
                ?: throw RegistryValidationException(
                    "P2_REGISTRY_CELL_MISSING",
                    "Only exact Jmix 2.8 and 3.0 profiles may be certified in Phase 2.",
                )
            if (cell.platformLine != exact.first || cell.targetJdks.isEmpty() || !exact.second.containsAll(cell.targetJdks)) {
                throw RegistryValidationException(
                    "P2_REGISTRY_CELL_MISSING",
                    "Unreviewed profile selector for ${cell.operationId}.",
                )
            }
            if (cell.reasonCode != "P2_CERTIFIED_READ_ONLY") {
                throw RegistryValidationException(
                    "P2_REGISTRY_MALFORMED",
                    "Certified read cells require P2_CERTIFIED_READ_ONLY.",
                )
            }
            if (cell.testedJmix.isEmpty() ||
                cell.testedTargetJdks.toSet() != cell.targetJdks ||
                cell.testedHostLanes.toSet() != requiredHostLanes ||
                cell.testedAlternative.isBlank()
            ) {
                throw RegistryValidationException(
                    "P2_EVIDENCE_REFERENCE_MISSING",
                    "Cell ${cell.operationId} lacks its reviewed tested path.",
                )
            }
            if (cell.fixtureIds.isEmpty() ||
                !cell.evidenceRefs.containsAll(cell.fixtureIds) ||
                cell.fixtureIds.any { !evidenceIndex.isReviewed(it) } ||
                cell.evidenceRefs.any { !evidenceIndex.isReviewed(it) }
            ) {
                throw RegistryValidationException(
                    "P2_EVIDENCE_REFERENCE_MISSING",
                    "Cell ${cell.operationId} references missing or unreviewed fixture evidence.",
                )
            }
        }

        for (leftIndex in cells.indices) {
            for (rightIndex in leftIndex + 1 until cells.size) {
                val left = cells[leftIndex]
                val right = cells[rightIndex]
                if (left.operationId == right.operationId &&
                    left.classification == right.classification &&
                    left.platformLine == right.platformLine &&
                    left.targetJdks.intersect(right.targetJdks).isNotEmpty()
                ) {
                    throw RegistryValidationException(
                        "P2_REGISTRY_SELECTOR_OVERLAP",
                        "Overlapping compatibility selectors exist for ${left.operationId}.",
                    )
                }
            }
        }
    }

    private fun canonicalRegistryJson(version: String, cells: List<RegistryCell>): String {
        val normalizedCells = cells.map { cell ->
            mapOf(
                "classification" to cell.classification.name,
                "evidenceRefs" to cell.evidenceRefs.distinct().sorted(),
                "fixtureIds" to cell.fixtureIds.distinct().sorted(),
                "operationId" to cell.operationId,
                "platformLine" to cell.platformLine,
                "reasonCode" to cell.reasonCode,
                "state" to cell.state.name,
                "targetJdks" to cell.targetJdks.sorted(),
                "testedAlternative" to cell.testedAlternative,
                "testedHostLanes" to cell.testedHostLanes.distinct().sorted(),
                "testedJmix" to cell.testedJmix.distinct().sorted(),
                "testedTargetJdks" to cell.testedTargetJdks.distinct().sorted(),
            )
        }.sortedBy { canonicalJson(it) }
        return canonicalJson(mapOf("cells" to normalizedCells, "version" to version))
    }

    private fun canonicalJson(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> buildString {
                append('"')
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
                append('"')
            }
            is Number, is Boolean -> value.toString()
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
            is Map<*, *> -> value.entries
                .map { (key, item) -> key as String to item }
                .sortedBy { it.first }
                .joinToString(prefix = "{", postfix = "}") { (key, item) ->
                    "${canonicalJson(key)}:${canonicalJson(item)}"
                }
            else -> error("Unsupported registry digest value: ${value::class.qualifiedName}")
        }
}

class CompatibilityEvaluator(
    private val registry: CompatibilityRegistry,
) {
    fun evaluate(operationId: String, context: CompatibilityContext): CompatibilityDecision {
        val cell = registry.cells.singleOrNull { it.matches(operationId, context.profile) }
        val degradation = degradationReason(context)
        if (degradation != null) {
            return decision(
                operationId = operationId,
                state = CompatibilityState.RECOGNIZED_DIAGNOSTIC,
                reasonCode = degradation,
                context = context,
                cell = cell,
                explanation = degradationExplanation(degradation),
                missingEvidence = missingEvidenceFor(degradation),
            )
        }

        when (context.profile.classification) {
            ProfileClassification.EARLIER_JMIX_2,
            ProfileClassification.LEGACY_JMIX_1,
            ProfileClassification.CUBA,
            -> return decision(
                operationId = operationId,
                state = CompatibilityState.RECOGNIZED_DIAGNOSTIC,
                reasonCode = "P2_LEGACY_DIAGNOSTIC",
                context = context,
                cell = cell,
                explanation = "The project is recognized as a legacy profile and remains diagnostic-only.",
                missingEvidence = listOf("exact-supported-profile", "reviewed-fixture-cell"),
            )

            ProfileClassification.FUTURE -> return decision(
                operationId = operationId,
                state = CompatibilityState.RECOGNIZED_DIAGNOSTIC,
                reasonCode = "P2_FUTURE_UNCERTIFIED",
                context = context,
                cell = cell,
                explanation = "The observed Jmix profile is newer than the reviewed compatibility registry.",
                missingEvidence = listOf("future-profile-fixture", "reviewed-registry-cell"),
            )

            else -> Unit
        }

        if (cell == null) {
            return decision(
                operationId = operationId,
                state = CompatibilityState.UNSUPPORTED,
                reasonCode = "P2_REGISTRY_CELL_MISSING",
                context = context,
                cell = null,
                explanation = "No reviewed compatibility cell matches this operation and exact profile.",
                missingEvidence = listOf("matching-registry-cell", "reviewed-fixture-evidence"),
            )
        }

        return decision(
            operationId = operationId,
            state = CompatibilityState.CERTIFIED_READ_ONLY,
            reasonCode = "P2_CERTIFIED_READ_ONLY",
            context = context,
            cell = cell,
            explanation = "This discovery operation is certified for read-only use with reviewed fixture evidence.",
            missingEvidence = listOf("phase3-write-authorization"),
        )
    }

    private fun decision(
        operationId: String,
        state: CompatibilityState,
        reasonCode: String,
        context: CompatibilityContext,
        cell: RegistryCell?,
        explanation: String,
        missingEvidence: List<String>,
    ): CompatibilityDecision =
        CompatibilityDecision(
            operationId = operationId,
            state = state,
            reasonCode = reasonCode,
            explanation = explanation,
            evidenceIds = (context.evidenceIds + cell.orEmptyEvidenceRefs()).distinct().sorted(),
            missingEvidence = missingEvidence.distinct().sorted(),
            testedJmix = cell?.testedJmix ?: listOfNotNull(context.profile.platformVersion),
            testedTargetJdks = cell?.testedTargetJdks ?: listOfNotNull(context.profile.targetJdk),
            testedHostLanes = cell?.testedHostLanes ?: listOf("253", "262"),
            fixtureIds = cell?.fixtureIds ?: emptyList(),
            registryVersion = registry.version,
            registryDigest = registry.digest,
            testedAlternative = cell?.testedAlternative
                ?: "Use Project Overview in diagnostic read-only mode and resolve the reported evidence gap.",
        )

    private fun degradationReason(context: CompatibilityContext): String? =
        when {
            context.trustState != TrustState.TRUSTED -> "P2_UNTRUSTED"
            context.importState == ImportState.STALE -> "P2_IMPORT_STALE"
            context.importState == ImportState.FAILED -> "P2_IMPORT_FAILED"
            context.importState == ImportState.ABSENT -> "P2_IMPORT_ABSENT"
            context.importState == ImportState.INDEXING || !context.indexComplete -> "P2_INDEX_INCOMPLETE"
            context.evidenceConflict -> "P2_EVIDENCE_CONFLICT"
            else -> null
        }

    private fun degradationExplanation(reasonCode: String): String =
        when (reasonCode) {
            "P2_UNTRUSTED" -> "Project trust is absent or unknown, so compatibility remains diagnostic-only."
            "P2_IMPORT_STALE" -> "Imported project evidence is stale and cannot certify capability."
            "P2_IMPORT_FAILED" -> "Project import failed and cannot certify capability."
            "P2_IMPORT_ABSENT" -> "Imported project evidence is absent and cannot certify capability."
            "P2_INDEX_INCOMPLETE" -> "Project indexing is incomplete and cannot certify capability."
            "P2_EVIDENCE_CONFLICT" -> "Observed profile evidence conflicts and cannot certify capability."
            else -> "Compatibility evidence is incomplete."
        }

    private fun missingEvidenceFor(reasonCode: String): List<String> =
        when (reasonCode) {
            "P2_UNTRUSTED" -> listOf("trusted-project")
            "P2_IMPORT_STALE" -> listOf("current-import-model")
            "P2_IMPORT_FAILED" -> listOf("successful-import-model")
            "P2_IMPORT_ABSENT" -> listOf("import-model")
            "P2_INDEX_INCOMPLETE" -> listOf("complete-index")
            "P2_EVIDENCE_CONFLICT" -> listOf("non-conflicting-profile-evidence")
            else -> listOf("complete-compatibility-evidence")
        }
}

private fun RegistryCell?.orEmptyEvidenceRefs(): List<String> = this?.evidenceRefs ?: emptyList()

private fun String?.matchesLine(line: String): Boolean =
    this != null && (this == line || this.startsWith("$line."))

private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    enumValues<T>().singleOrNull { it.name == value }
        ?: throw RegistryValidationException(
            "P2_REGISTRY_MALFORMED",
            "Unknown $field value: $value",
        )

private fun Map<String, Any?>.requiredString(field: String): String =
    (this[field] as? String)?.takeIf(String::isNotBlank)
        ?: throw RegistryValidationException("P2_REGISTRY_MALFORMED", "Missing string field: $field")

private fun Map<String, Any?>.requiredArray(field: String): List<Any?> =
    this[field] as? List<*>
        ?: throw RegistryValidationException("P2_REGISTRY_MALFORMED", "Missing array field: $field")

private fun Map<String, Any?>.requiredStringList(field: String): List<String> =
    requiredArray(field).map {
        it as? String
            ?: throw RegistryValidationException("P2_REGISTRY_MALFORMED", "$field must contain strings.")
    }.also {
        if (it.isEmpty() || it.any(String::isBlank)) {
            throw RegistryValidationException("P2_EVIDENCE_REFERENCE_MISSING", "$field cannot be empty.")
        }
    }

private fun Map<String, Any?>.requiredIntList(field: String): List<Int> =
    requiredArray(field).map {
        val number = it as? Long
            ?: throw RegistryValidationException("P2_REGISTRY_MALFORMED", "$field must contain integers.")
        if (number !in Int.MIN_VALUE..Int.MAX_VALUE) {
            throw RegistryValidationException("P2_REGISTRY_MALFORMED", "$field integer is out of range.")
        }
        number.toInt()
    }.also {
        if (it.isEmpty()) {
            throw RegistryValidationException("P2_EVIDENCE_REFERENCE_MISSING", "$field cannot be empty.")
        }
    }

private fun Any?.requiredObject(field: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<String, Any?>
        ?: throw RegistryValidationException("P2_REGISTRY_MALFORMED", "$field must be an object.")
}

private class RegistryJsonParser(
    private val input: String,
) {
    private var index = 0

    fun parseObject(): Map<String, Any?> {
        val result = parseValue().requiredObject("root")
        skipWhitespace()
        if (index != input.length) {
            malformed("Unexpected trailing content.")
        }
        return result
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (index >= input.length) malformed("Unexpected end of JSON.")
        return when (input[index]) {
            '{' -> parseObjectValue()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseInteger()
            else -> malformed("Unexpected character '${input[index]}'.")
        }
    }

    private fun parseObjectValue(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        val result = linkedMapOf<String, Any?>()
        if (consume('}')) return result
        while (true) {
            skipWhitespace()
            if (index >= input.length || input[index] != '"') malformed("Object key must be a string.")
            val key = parseString()
            if (result.containsKey(key)) malformed("Duplicate JSON key: $key")
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            if (consume('}')) return result
            expect(',')
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        val result = mutableListOf<Any?>()
        if (consume(']')) return result
        while (true) {
            result += parseValue()
            skipWhitespace()
            if (consume(']')) return result
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        return buildString {
            while (index < input.length) {
                val character = input[index++]
                when (character) {
                    '"' -> return@buildString
                    '\\' -> {
                        if (index >= input.length) malformed("Unterminated escape.")
                        append(
                            when (val escaped = input[index++]) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000c'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> parseUnicodeEscape()
                                else -> malformed("Unknown escape: \\$escaped")
                            },
                        )
                    }
                    else -> {
                        if (character.code < 0x20) malformed("Control character in string.")
                        append(character)
                    }
                }
            }
            malformed("Unterminated string.")
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > input.length) malformed("Incomplete unicode escape.")
        val digits = input.substring(index, index + 4)
        index += 4
        return digits.toIntOrNull(16)?.toChar() ?: malformed("Invalid unicode escape.")
    }

    private fun parseInteger(): Long {
        val start = index
        if (input[index] == '-') index++
        if (index >= input.length || input[index] !in '0'..'9') malformed("Invalid number.")
        while (index < input.length && input[index] in '0'..'9') index++
        if (index < input.length && input[index] in ".eE") {
            malformed("Registry numbers must be integers.")
        }
        return input.substring(start, index).toLongOrNull() ?: malformed("Invalid integer.")
    }

    private fun <T> parseLiteral(text: String, value: T): T {
        if (!input.startsWith(text, index)) malformed("Invalid literal.")
        index += text.length
        return value
    }

    private fun expect(character: Char) {
        skipWhitespace()
        if (!consume(character)) malformed("Expected '$character'.")
    }

    private fun consume(character: Char): Boolean {
        if (index < input.length && input[index] == character) {
            index++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index++
    }

    private fun malformed(message: String): Nothing =
        throw RegistryValidationException("P2_REGISTRY_MALFORMED", "$message At offset $index.")
}

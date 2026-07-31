package org.jmixworkbench.services

import org.jmixworkbench.model.IntegrationOpenApiJmixLayerModel
import org.jmixworkbench.model.IntegrationOpenApiJmixTargetKind
import org.jmixworkbench.model.IntegrationOpenApiMappingDirection
import org.jmixworkbench.model.IntegrationOpenApiOperationModel
import org.jmixworkbench.model.IntegrationOpenApiPropertyMapping
import org.jmixworkbench.model.IntegrationOpenApiPropertyModel
import org.jmixworkbench.model.IntegrationOpenApiSchemaKind
import org.jmixworkbench.model.IntegrationOpenApiSchemaModel
import java.util.Locale

enum class OpenApiRemapConfidence {
    EXACT,
    HIGH,
    REVIEW,
}

data class OpenApiPropertyRemapCandidate(
    val candidateSchemaProperty: String,
    val previousSchemaProperty: String,
    val previousEntityProperty: String,
    val direction: IntegrationOpenApiMappingDirection,
    val confidence: OpenApiRemapConfidence,
    val reason: String,
)

data class OpenApiSchemaRemapOption(
    val candidateSchemaId: String,
    val candidateJavaName: String,
    val confidence: OpenApiRemapConfidence,
    val structuralScore: Int,
    val exactPropertyMatches: Int,
    val compatiblePropertyMatches: Int,
    val requiredOutboundUnmapped: List<String>,
    val propertyCandidates: List<OpenApiPropertyRemapCandidate>,
)

data class OpenApiSchemaRemapPlan(
    val previousSchemaId: String,
    val previousJavaName: String,
    val targetKind: IntegrationOpenApiJmixTargetKind,
    val targetLabel: String,
    val options: List<OpenApiSchemaRemapOption>,
)

/**
 * Builds bounded, backend-owned suggestions for carrying a Jmix target across
 * renamed or structurally changed OpenAPI schemas. Suggestions are evidence,
 * never decisions: the browser must explicitly select a schema/property remap
 * and the resulting complete connector model is covered by native approval.
 */
object OpenApiJmixEvolutionRemapPlanner {
    private const val MAX_PLANS = 80
    private const val MAX_OPTIONS_PER_PLAN = 32
    private const val MAX_PROPERTY_CANDIDATES_PER_OPTION = 256

    fun plan(
        baseline: IntegrationOpenApiOperationModel,
        candidate: IntegrationOpenApiOperationModel,
        layer: IntegrationOpenApiJmixLayerModel?,
    ): List<OpenApiSchemaRemapPlan> {
        if (layer?.enabled != true) return emptyList()
        val previousSchemas = baseline.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val candidateSchemas = candidate.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val outboundSchemas = reachable(candidate, listOfNotNull(candidate.requestSchemaId))
        val reachableCandidates = reachable(candidate)
            .mapNotNull(candidateSchemas::get)
            .filter { it.kind == IntegrationOpenApiSchemaKind.OBJECT }
            .sortedWith(compareBy(IntegrationOpenApiSchemaModel::javaName, IntegrationOpenApiSchemaModel::id))

        return layer.mappings.asSequence()
            .mapNotNull { mapping ->
                val previousSchema = previousSchemas[mapping.schemaId]
                    ?.takeIf { it.kind == IntegrationOpenApiSchemaKind.OBJECT }
                    ?: return@mapNotNull null
                val effectiveMappings = mapping.properties.ifEmpty {
                    previousSchema.properties.map { property ->
                        IntegrationOpenApiPropertyMapping(
                            schemaProperty = property.javaName,
                            entityProperty = property.javaName,
                            direction = IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
                        )
                    }
                }
                val options = reachableCandidates.mapNotNull { currentSchema ->
                    option(
                        previousSchema = previousSchema,
                        currentSchema = currentSchema,
                        previousSchemas = previousSchemas,
                        currentSchemas = candidateSchemas,
                        mappings = effectiveMappings,
                        outbound = currentSchema.id in outboundSchemas,
                    )
                }.sortedWith(
                    compareByDescending<OpenApiSchemaRemapOption> { confidenceRank(it.confidence) }
                        .thenByDescending(OpenApiSchemaRemapOption::structuralScore)
                        .thenBy(OpenApiSchemaRemapOption::candidateJavaName)
                        .thenBy(OpenApiSchemaRemapOption::candidateSchemaId),
                ).take(MAX_OPTIONS_PER_PLAN)
                if (options.isEmpty()) return@mapNotNull null
                OpenApiSchemaRemapPlan(
                    previousSchemaId = previousSchema.id,
                    previousJavaName = previousSchema.javaName,
                    targetKind = mapping.targetKind,
                    targetLabel = mapping.existingEntity?.qualifiedName
                        ?: mapping.generatedClassName
                        ?: "generated DTO",
                    options = options,
                )
            }
            .sortedWith(compareBy(OpenApiSchemaRemapPlan::previousJavaName, OpenApiSchemaRemapPlan::previousSchemaId))
            .take(MAX_PLANS)
            .toList()
    }

    private fun option(
        previousSchema: IntegrationOpenApiSchemaModel,
        currentSchema: IntegrationOpenApiSchemaModel,
        previousSchemas: Map<String, IntegrationOpenApiSchemaModel>,
        currentSchemas: Map<String, IntegrationOpenApiSchemaModel>,
        mappings: List<IntegrationOpenApiPropertyMapping>,
        outbound: Boolean,
    ): OpenApiSchemaRemapOption? {
        val previousProperties = previousSchema.properties.associateBy(IntegrationOpenApiPropertyModel::javaName)
        val mappedProperties = mappings.mapNotNull { mapping ->
            previousProperties[mapping.schemaProperty]?.let { Triple(mapping, it, canonical(it.javaName)) }
        }
        val propertyCandidates = buildList {
            currentSchema.properties.forEach { currentProperty ->
                val compatible = mappedProperties.filter { (_, previousProperty) ->
                    compatibleShape(
                        previousProperty.schemaId,
                        currentProperty.schemaId,
                        previousSchemas,
                        currentSchemas,
                    )
                }
                compatible.forEach { (mapping, previousProperty, previousCanonical) ->
                    val confidenceAndReason = when {
                        previousProperty.javaName == currentProperty.javaName ->
                            OpenApiRemapConfidence.EXACT to "Exact generated property identity"
                        previousProperty.wireName == currentProperty.wireName ->
                            OpenApiRemapConfidence.HIGH to "Exact wire-property identity"
                        previousCanonical == canonical(currentProperty.javaName) ->
                            OpenApiRemapConfidence.HIGH to "Equivalent normalized property name"
                        compatible.size == 1 ->
                            OpenApiRemapConfidence.REVIEW to "Unique compatible property shape"
                        else ->
                            OpenApiRemapConfidence.REVIEW to "Compatible property shape"
                    }
                    add(
                        OpenApiPropertyRemapCandidate(
                            candidateSchemaProperty = currentProperty.javaName,
                            previousSchemaProperty = previousProperty.javaName,
                            previousEntityProperty = mapping.entityProperty,
                            direction = mapping.direction,
                            confidence = confidenceAndReason.first,
                            reason = confidenceAndReason.second,
                        ),
                    )
                }
            }
        }.distinctBy {
            listOf(it.candidateSchemaProperty, it.previousSchemaProperty, it.previousEntityProperty, it.direction)
        }.sortedWith(
            compareByDescending<OpenApiPropertyRemapCandidate> { confidenceRank(it.confidence) }
                .thenBy(OpenApiPropertyRemapCandidate::candidateSchemaProperty)
                .thenBy(OpenApiPropertyRemapCandidate::previousEntityProperty),
        ).take(MAX_PROPERTY_CANDIDATES_PER_OPTION)

        val bestByCurrent = propertyCandidates.groupBy(OpenApiPropertyRemapCandidate::candidateSchemaProperty)
            .mapValues { (_, options) -> options.maxBy { confidenceRank(it.confidence) } }
        val exact = bestByCurrent.values.count { it.confidence == OpenApiRemapConfidence.EXACT }
        val compatible = bestByCurrent.size
        val requiredOutboundUnmapped = if (outbound) currentSchema.properties.filter { property ->
            val best = bestByCurrent[property.javaName]
            property.required && !property.readOnly &&
                (best == null || best.direction == IntegrationOpenApiMappingDirection.INBOUND)
        }.map(IntegrationOpenApiPropertyModel::javaName).sorted() else emptyList()

        val nameEvidence = when {
            previousSchema.id == currentSchema.id -> 35
            previousSchema.javaName == currentSchema.javaName -> 25
            canonical(previousSchema.javaName) == canonical(currentSchema.javaName) -> 18
            else -> 0
        }
        val denominator = maxOf(previousSchema.properties.size, currentSchema.properties.size, 1)
        val propertyEvidence = (compatible * 60 / denominator).coerceAtMost(60)
        val score = (nameEvidence + propertyEvidence - requiredOutboundUnmapped.size * 8).coerceIn(0, 100)
        val confidence = when {
            previousSchema.id == currentSchema.id -> OpenApiRemapConfidence.EXACT
            score >= 70 && requiredOutboundUnmapped.isEmpty() -> OpenApiRemapConfidence.HIGH
            compatible > 0 || nameEvidence > 0 -> OpenApiRemapConfidence.REVIEW
            else -> return null
        }
        return OpenApiSchemaRemapOption(
            candidateSchemaId = currentSchema.id,
            candidateJavaName = currentSchema.javaName,
            confidence = confidence,
            structuralScore = score,
            exactPropertyMatches = exact,
            compatiblePropertyMatches = compatible,
            requiredOutboundUnmapped = requiredOutboundUnmapped,
            propertyCandidates = propertyCandidates,
        )
    }

    private fun compatibleShape(
        previousId: String,
        currentId: String,
        previous: Map<String, IntegrationOpenApiSchemaModel>,
        current: Map<String, IntegrationOpenApiSchemaModel>,
        visited: MutableSet<Pair<String, String>> = mutableSetOf(),
    ): Boolean {
        if (!visited.add(previousId to currentId)) return true
        val left = previous[previousId] ?: return false
        val right = current[currentId] ?: return false
        if (left.kind != right.kind) return false
        return when (left.kind) {
            IntegrationOpenApiSchemaKind.ARRAY -> {
                val leftItem = left.itemSchemaId ?: return false
                val rightItem = right.itemSchemaId ?: return false
                compatibleShape(leftItem, rightItem, previous, current, visited)
            }
            IntegrationOpenApiSchemaKind.INTEGER,
            IntegrationOpenApiSchemaKind.NUMBER,
            IntegrationOpenApiSchemaKind.STRING,
            -> left.format.orEmpty() == right.format.orEmpty()
            else -> true
        }
    }

    private fun reachable(
        operation: IntegrationOpenApiOperationModel,
        roots: List<String> = listOfNotNull(operation.requestSchemaId, operation.responseSchemaId),
    ): Set<String> {
        val schemas = operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val visited = linkedSetOf<String>()
        fun visit(id: String) {
            if (!visited.add(id)) return
            val schema = schemas[id] ?: return
            schema.properties.forEach { visit(it.schemaId) }
            schema.itemSchemaId?.let(::visit)
            schema.additionalPropertiesSchemaId?.let(::visit)
        }
        roots.forEach(::visit)
        return visited
    }

    private fun canonical(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun confidenceRank(confidence: OpenApiRemapConfidence): Int = when (confidence) {
        OpenApiRemapConfidence.EXACT -> 3
        OpenApiRemapConfidence.HIGH -> 2
        OpenApiRemapConfidence.REVIEW -> 1
    }
}

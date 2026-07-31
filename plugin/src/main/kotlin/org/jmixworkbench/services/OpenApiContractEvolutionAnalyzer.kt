package org.jmixworkbench.services

import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.model.IntegrationOpenApiOperationModel
import org.jmixworkbench.model.IntegrationOpenApiParameterModel
import org.jmixworkbench.model.IntegrationOpenApiSchemaKind
import org.jmixworkbench.model.IntegrationOpenApiSchemaModel

enum class OpenApiEvolutionImpact {
    NONE,
    COMPATIBLE,
    REVIEW,
    BREAKING,
}

enum class OpenApiEvolutionScope {
    OPERATION,
    PARAMETER,
    REQUEST,
    RESPONSE,
    SECURITY,
}

data class OpenApiEvolutionChange(
    val code: String,
    val scope: OpenApiEvolutionScope,
    val path: String,
    val wireImpact: OpenApiEvolutionImpact,
    val sourceImpact: OpenApiEvolutionImpact,
    val message: String,
    val before: String? = null,
    val after: String? = null,
)

data class OpenApiEvolutionReport(
    val baselineSha256: String,
    val candidateSha256: String,
    val baselineApiVersion: String?,
    val candidateApiVersion: String?,
    val wireImpact: OpenApiEvolutionImpact,
    val sourceImpact: OpenApiEvolutionImpact,
    val changes: List<OpenApiEvolutionChange>,
    val reportDigest: String,
) {
    val different: Boolean get() = changes.isNotEmpty()
    val breaking: Boolean
        get() = wireImpact == OpenApiEvolutionImpact.BREAKING ||
            sourceImpact == OpenApiEvolutionImpact.BREAKING
}

/**
 * Consumer-oriented semantic comparison for one generated OpenAPI operation.
 *
 * Wire compatibility and generated-source compatibility are intentionally
 * separate. A provider may add an optional parameter without breaking HTTP,
 * but regenerating a Java method with one more argument still breaks callers.
 */
object OpenApiContractEvolutionAnalyzer {
    fun compare(
        baseline: IntegrationOpenApiOperationModel,
        candidate: IntegrationOpenApiOperationModel,
    ): OpenApiEvolutionReport {
        val changes = mutableListOf<OpenApiEvolutionChange>()
        operationChanges(baseline, candidate, changes)
        parameterChanges(baseline, candidate, changes)
        schemaChanges(baseline, candidate, changes)
        securityChanges(baseline, candidate, changes)
        val ordered = changes.distinct().sortedWith(
            compareByDescending<OpenApiEvolutionChange> { impactRank(maxImpact(it.wireImpact, it.sourceImpact)) }
                .thenBy { it.scope.name }
                .thenBy(OpenApiEvolutionChange::path)
                .thenBy(OpenApiEvolutionChange::code),
        )
        val wire = ordered.fold(OpenApiEvolutionImpact.NONE) { current, change ->
            maxImpact(current, change.wireImpact)
        }
        val source = ordered.fold(OpenApiEvolutionImpact.NONE) { current, change ->
            maxImpact(current, change.sourceImpact)
        }
        val digestSource = buildString {
            append(baseline.contractSha256).append('\u0000').append(candidate.contractSha256)
            ordered.forEach { change ->
                append('\u0000').append(change.code)
                    .append('\u0001').append(change.scope)
                    .append('\u0001').append(change.path)
                    .append('\u0001').append(change.wireImpact)
                    .append('\u0001').append(change.sourceImpact)
                    .append('\u0001').append(change.before.orEmpty())
                    .append('\u0001').append(change.after.orEmpty())
            }
        }
        return OpenApiEvolutionReport(
            baselineSha256 = baseline.contractSha256,
            candidateSha256 = candidate.contractSha256,
            baselineApiVersion = baseline.apiVersion,
            candidateApiVersion = candidate.apiVersion,
            wireImpact = wire,
            sourceImpact = source,
            changes = ordered,
            reportDigest = CanonicalDiscoveryJson.sha256(digestSource),
        )
    }

    private fun operationChanges(
        before: IntegrationOpenApiOperationModel,
        after: IntegrationOpenApiOperationModel,
        changes: MutableList<OpenApiEvolutionChange>,
    ) {
        fun changed(
            code: String,
            path: String,
            old: String?,
            new: String?,
            wire: OpenApiEvolutionImpact,
            source: OpenApiEvolutionImpact,
            message: String,
        ) {
            if (old != new) changes += OpenApiEvolutionChange(
                code,
                OpenApiEvolutionScope.OPERATION,
                path,
                wire,
                source,
                message,
                old,
                new,
            )
        }
        changed(
            "OPENAPI_OPERATION_METHOD_CHANGED",
            "operation.method",
            before.method.name,
            after.method.name,
            OpenApiEvolutionImpact.BREAKING,
            OpenApiEvolutionImpact.BREAKING,
            "The HTTP method changed.",
        )
        changed(
            "OPENAPI_OPERATION_PATH_CHANGED",
            "operation.path",
            before.path,
            after.path,
            OpenApiEvolutionImpact.BREAKING,
            OpenApiEvolutionImpact.REVIEW,
            "The endpoint path changed.",
        )
        changed(
            "OPENAPI_OPERATION_ID_CHANGED",
            "operation.operationId",
            before.operationId,
            after.operationId,
            OpenApiEvolutionImpact.COMPATIBLE,
            OpenApiEvolutionImpact.BREAKING,
            "The operation ID changed the generated method identity.",
        )
        changed(
            "OPENAPI_JAVA_METHOD_CHANGED",
            "operation.javaMethodName",
            before.javaMethodName,
            after.javaMethodName,
            OpenApiEvolutionImpact.NONE,
            OpenApiEvolutionImpact.BREAKING,
            "The generated Java method name changed.",
        )
        changed(
            "OPENAPI_REQUEST_MEDIA_CHANGED",
            "request.mediaType",
            before.requestMediaType,
            after.requestMediaType,
            OpenApiEvolutionImpact.BREAKING,
            OpenApiEvolutionImpact.REVIEW,
            "The selected request representation changed.",
        )
        changed(
            "OPENAPI_RESPONSE_STATUS_CHANGED",
            "response.status",
            before.responseStatus,
            after.responseStatus,
            OpenApiEvolutionImpact.BREAKING,
            OpenApiEvolutionImpact.REVIEW,
            "The selected success status changed.",
        )
        changed(
            "OPENAPI_RESPONSE_MEDIA_CHANGED",
            "response.mediaType",
            before.responseMediaType,
            after.responseMediaType,
            OpenApiEvolutionImpact.BREAKING,
            OpenApiEvolutionImpact.REVIEW,
            "The selected response representation changed.",
        )
        if (before.requestRequired != after.requestRequired) {
            changes += OpenApiEvolutionChange(
                code = "OPENAPI_REQUEST_REQUIRED_CHANGED",
                scope = OpenApiEvolutionScope.REQUEST,
                path = "request.required",
                wireImpact = if (after.requestRequired) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.COMPATIBLE,
                sourceImpact = OpenApiEvolutionImpact.REVIEW,
                message = if (after.requestRequired) "The request body became required." else "The request body became optional.",
                before = before.requestRequired.toString(),
                after = after.requestRequired.toString(),
            )
        }
        if (!before.deprecated && after.deprecated) {
            changes += OpenApiEvolutionChange(
                "OPENAPI_OPERATION_DEPRECATED",
                OpenApiEvolutionScope.OPERATION,
                "operation.deprecated",
                OpenApiEvolutionImpact.REVIEW,
                OpenApiEvolutionImpact.REVIEW,
                "The provider deprecated this operation.",
                "false",
                "true",
            )
        }
    }

    private fun parameterChanges(
        before: IntegrationOpenApiOperationModel,
        after: IntegrationOpenApiOperationModel,
        changes: MutableList<OpenApiEvolutionChange>,
    ) {
        val beforeSchemas = before.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val afterSchemas = after.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val old = before.parameters.associateBy(::parameterKey)
        val new = after.parameters.associateBy(::parameterKey)
        (old.keys - new.keys).sorted().forEach { key ->
            val parameter = requireNotNull(old[key])
            changes += OpenApiEvolutionChange(
                "OPENAPI_PARAMETER_REMOVED",
                OpenApiEvolutionScope.PARAMETER,
                "parameter.$key",
                OpenApiEvolutionImpact.BREAKING,
                OpenApiEvolutionImpact.BREAKING,
                "Parameter '${parameter.wireName}' was removed from the generated operation.",
                parameterSignature(parameter, beforeSchemas),
                null,
            )
        }
        (new.keys - old.keys).sorted().forEach { key ->
            val parameter = requireNotNull(new[key])
            changes += OpenApiEvolutionChange(
                "OPENAPI_PARAMETER_ADDED",
                OpenApiEvolutionScope.PARAMETER,
                "parameter.$key",
                if (parameter.required) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.COMPATIBLE,
                OpenApiEvolutionImpact.BREAKING,
                if (parameter.required) "A required parameter was added." else "An optional parameter was added, but regeneration changes the Java signature.",
                null,
                parameterSignature(parameter, afterSchemas),
            )
        }
        (old.keys intersect new.keys).sorted().forEach { key ->
            val oldParameter = requireNotNull(old[key])
            val newParameter = requireNotNull(new[key])
            val oldSignature = parameterSignature(oldParameter, beforeSchemas)
            val newSignature = parameterSignature(newParameter, afterSchemas)
            if (oldSignature != newSignature) {
                changes += OpenApiEvolutionChange(
                    "OPENAPI_PARAMETER_CONTRACT_CHANGED",
                    OpenApiEvolutionScope.PARAMETER,
                    "parameter.$key",
                    OpenApiEvolutionImpact.BREAKING,
                    OpenApiEvolutionImpact.BREAKING,
                    "Parameter '${oldParameter.wireName}' changed type, requiredness, serialization, or generated name.",
                    oldSignature,
                    newSignature,
                )
            }
        }
    }

    private fun schemaChanges(
        before: IntegrationOpenApiOperationModel,
        after: IntegrationOpenApiOperationModel,
        changes: MutableList<OpenApiEvolutionChange>,
    ) {
        val beforeSchemas = before.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val afterSchemas = after.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val visited = mutableSetOf<Triple<String, String, OpenApiEvolutionScope>>()
        compareSchema(
            before.requestSchemaId,
            after.requestSchemaId,
            OpenApiEvolutionScope.REQUEST,
            "request.body",
            beforeSchemas,
            afterSchemas,
            visited,
            changes,
        )
        compareSchema(
            before.responseSchemaId,
            after.responseSchemaId,
            OpenApiEvolutionScope.RESPONSE,
            "response.body",
            beforeSchemas,
            afterSchemas,
            visited,
            changes,
        )
    }

    private fun compareSchema(
        beforeId: String?,
        afterId: String?,
        scope: OpenApiEvolutionScope,
        path: String,
        beforeSchemas: Map<String, IntegrationOpenApiSchemaModel>,
        afterSchemas: Map<String, IntegrationOpenApiSchemaModel>,
        visited: MutableSet<Triple<String, String, OpenApiEvolutionScope>>,
        changes: MutableList<OpenApiEvolutionChange>,
    ) {
        if (beforeId == null && afterId == null) return
        if (beforeId == null || afterId == null) {
            changes += OpenApiEvolutionChange(
                "OPENAPI_BODY_PRESENCE_CHANGED",
                scope,
                path,
                OpenApiEvolutionImpact.BREAKING,
                OpenApiEvolutionImpact.BREAKING,
                "The selected ${scope.name.lowercase()} body was ${if (beforeId == null) "added" else "removed"}.",
                beforeId,
                afterId,
            )
            return
        }
        val visitKey = Triple(beforeId, afterId, scope)
        if (!visited.add(visitKey)) return
        val before = beforeSchemas[beforeId]
        val after = afterSchemas[afterId]
        if (before == null || after == null) {
            changes += OpenApiEvolutionChange(
                "OPENAPI_SCHEMA_UNRESOLVED",
                scope,
                path,
                OpenApiEvolutionImpact.BREAKING,
                OpenApiEvolutionImpact.BREAKING,
                "A compared schema is missing from the normalized contract graph.",
                beforeId,
                afterId,
            )
            visited.remove(visitKey)
            return
        }
        if (beforeId != afterId || before.javaName != after.javaName) {
            changes += OpenApiEvolutionChange(
                "OPENAPI_SCHEMA_IDENTITY_CHANGED",
                scope,
                path,
                OpenApiEvolutionImpact.NONE,
                OpenApiEvolutionImpact.BREAKING,
                "The generated schema identity or Java type name changed.",
                "$beforeId|${before.javaName}",
                "$afterId|${after.javaName}",
            )
        }
        val beforeShape = scalarShape(before)
        val afterShape = scalarShape(after)
        if (before.kind != after.kind || before.format != after.format || before.additionalPropertiesAllowed != after.additionalPropertiesAllowed) {
            changes += OpenApiEvolutionChange(
                "OPENAPI_SCHEMA_SHAPE_CHANGED",
                scope,
                path,
                OpenApiEvolutionImpact.BREAKING,
                OpenApiEvolutionImpact.BREAKING,
                "Schema kind, format, or map behavior changed.",
                beforeShape,
                afterShape,
            )
            visited.remove(visitKey)
            return
        }
        if (before.nullable != after.nullable) {
            val restrictive = if (scope == OpenApiEvolutionScope.REQUEST) before.nullable && !after.nullable else !before.nullable && after.nullable
            changes += OpenApiEvolutionChange(
                "OPENAPI_SCHEMA_NULLABILITY_CHANGED",
                scope,
                path,
                if (restrictive) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.COMPATIBLE,
                OpenApiEvolutionImpact.REVIEW,
                "Schema nullability changed.",
                before.nullable.toString(),
                after.nullable.toString(),
            )
        }
        if (before.validation != after.validation) {
            val tightened = validationTightened(before.validation, after.validation)
            val relaxed = validationTightened(after.validation, before.validation)
            val wire = when (scope) {
                OpenApiEvolutionScope.REQUEST -> if (tightened) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.COMPATIBLE
                OpenApiEvolutionScope.RESPONSE -> if (relaxed) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.COMPATIBLE
                else -> OpenApiEvolutionImpact.REVIEW
            }
            changes += OpenApiEvolutionChange(
                "OPENAPI_SCHEMA_VALIDATION_CHANGED",
                scope,
                path,
                if (tightened && relaxed) OpenApiEvolutionImpact.BREAKING else wire,
                OpenApiEvolutionImpact.REVIEW,
                "Numeric, length, pattern, collection, uniqueness, or const validation changed.",
                validationSignature(before.validation),
                validationSignature(after.validation),
            )
        }
        if (before.enumValues != after.enumValues) {
            val removed = before.enumValues.toSet() - after.enumValues.toSet()
            val added = after.enumValues.toSet() - before.enumValues.toSet()
            val wire = when (scope) {
                OpenApiEvolutionScope.REQUEST -> if (removed.isNotEmpty()) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.COMPATIBLE
                OpenApiEvolutionScope.RESPONSE -> if (added.isNotEmpty()) OpenApiEvolutionImpact.REVIEW else OpenApiEvolutionImpact.COMPATIBLE
                else -> OpenApiEvolutionImpact.REVIEW
            }
            changes += OpenApiEvolutionChange(
                "OPENAPI_ENUM_VALUES_CHANGED",
                scope,
                path,
                wire,
                OpenApiEvolutionImpact.BREAKING,
                "Enum values changed; generated exhaustive switches and explicit constant references require review.",
                before.enumValues.joinToString(","),
                after.enumValues.joinToString(","),
            )
        }
        if (before.kind == IntegrationOpenApiSchemaKind.ARRAY) {
            compareSchema(
                before.itemSchemaId,
                after.itemSchemaId,
                scope,
                "$path[]",
                beforeSchemas,
                afterSchemas,
                visited,
                changes,
            )
        }
        if (before.kind != IntegrationOpenApiSchemaKind.OBJECT) {
            visited.remove(visitKey)
            return
        }
        val oldProperties = before.properties.associateBy { it.wireName }
        val newProperties = after.properties.associateBy { it.wireName }
        (oldProperties.keys - newProperties.keys).sorted().forEach { name ->
            changes += OpenApiEvolutionChange(
                "OPENAPI_PROPERTY_REMOVED",
                scope,
                "$path.$name",
                OpenApiEvolutionImpact.BREAKING,
                OpenApiEvolutionImpact.BREAKING,
                "Property '$name' was removed.",
                oldProperties[name]?.schemaId,
                null,
            )
        }
        (newProperties.keys - oldProperties.keys).sorted().forEach { name ->
            val property = requireNotNull(newProperties[name])
            val requiredForDirection = property.required && when (scope) {
                OpenApiEvolutionScope.REQUEST -> !property.readOnly
                OpenApiEvolutionScope.RESPONSE -> !property.writeOnly
                else -> true
            }
            changes += OpenApiEvolutionChange(
                "OPENAPI_PROPERTY_ADDED",
                scope,
                "$path.$name",
                if (scope == OpenApiEvolutionScope.REQUEST && requiredForDirection) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.COMPATIBLE,
                OpenApiEvolutionImpact.BREAKING,
                if (requiredForDirection && scope == OpenApiEvolutionScope.REQUEST) "A required request property was added." else "A property was added; generated source shape changes.",
                null,
                property.schemaId,
            )
        }
        (oldProperties.keys intersect newProperties.keys).sorted().forEach { name ->
            val oldProperty = requireNotNull(oldProperties[name])
            val newProperty = requireNotNull(newProperties[name])
            if (
                oldProperty.javaName != newProperty.javaName ||
                oldProperty.required != newProperty.required ||
                oldProperty.nullable != newProperty.nullable ||
                oldProperty.readOnly != newProperty.readOnly ||
                oldProperty.writeOnly != newProperty.writeOnly
            ) {
                val requestTightened = scope == OpenApiEvolutionScope.REQUEST &&
                    ((!oldProperty.required && newProperty.required) ||
                        (oldProperty.nullable && !newProperty.nullable) ||
                        (!oldProperty.readOnly && newProperty.readOnly))
                val responseWeakened = scope == OpenApiEvolutionScope.RESPONSE &&
                    ((oldProperty.required && !newProperty.required) ||
                        (!oldProperty.nullable && newProperty.nullable) ||
                        (!oldProperty.writeOnly && newProperty.writeOnly))
                changes += OpenApiEvolutionChange(
                    "OPENAPI_PROPERTY_CONTRACT_CHANGED",
                    scope,
                    "$path.$name",
                    if (requestTightened || responseWeakened) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.REVIEW,
                    if (oldProperty.javaName != newProperty.javaName) OpenApiEvolutionImpact.BREAKING else OpenApiEvolutionImpact.REVIEW,
                    "Property requiredness, nullability, visibility, or generated name changed.",
                    propertySignature(oldProperty),
                    propertySignature(newProperty),
                )
            }
            compareSchema(
                oldProperty.schemaId,
                newProperty.schemaId,
                scope,
                "$path.$name",
                beforeSchemas,
                afterSchemas,
                visited,
                changes,
            )
        }
        // This is a recursion-stack guard rather than a global de-duplicator:
        // the same reusable schema must still report impact at every logical
        // request/response property path where developers consume it.
        visited.remove(visitKey)
    }

    private fun securityChanges(
        before: IntegrationOpenApiOperationModel,
        after: IntegrationOpenApiOperationModel,
        changes: MutableList<OpenApiEvolutionChange>,
    ) {
        val old = securitySignature(before)
        val new = securitySignature(after)
        if (old == new) return
        changes += OpenApiEvolutionChange(
            "OPENAPI_SECURITY_REQUIREMENTS_CHANGED",
            OpenApiEvolutionScope.SECURITY,
            "operation.security",
            OpenApiEvolutionImpact.BREAKING,
            OpenApiEvolutionImpact.REVIEW,
            "Authentication alternatives, scheme contracts, or OAuth2 scopes changed.",
            old,
            new,
        )
    }

    private fun parameterKey(parameter: IntegrationOpenApiParameterModel): String =
        "${parameter.location.name.lowercase()}:${parameter.wireName}"

    private fun parameterSignature(
        parameter: IntegrationOpenApiParameterModel,
        schemas: Map<String, IntegrationOpenApiSchemaModel>,
    ): String = listOf(
        parameter.javaName,
        schemaSignature(parameter.schemaId, schemas),
        parameter.required,
        parameter.style,
        parameter.explode,
    ).joinToString("|")

    private fun schemaSignature(
        schemaId: String,
        schemas: Map<String, IntegrationOpenApiSchemaModel>,
    ): String = schemas[schemaId]?.let(::scalarShape) ?: "missing:$schemaId"

    private fun scalarShape(schema: IntegrationOpenApiSchemaModel): String = listOf(
        schema.kind,
        schema.format,
        schema.nullable,
        schema.enumValues.joinToString(","),
        schema.additionalPropertiesAllowed,
        validationSignature(schema.validation),
    ).joinToString("|")

    private fun validationSignature(
        validation: org.jmixworkbench.model.IntegrationOpenApiValidationModel,
    ): String = listOf(
        validation.minimum,
        validation.minimumExclusive,
        validation.maximum,
        validation.maximumExclusive,
        validation.multiplesOf.joinToString(","),
        validation.minLength,
        validation.maxLength,
        validation.patterns.joinToString(","),
        validation.minItems,
        validation.maxItems,
        validation.uniqueItems,
        validation.minProperties,
        validation.maxProperties,
        validation.constValue,
    ).joinToString("|")

    /** True when [after] accepts a strict subset that [before] accepted. */
    private fun validationTightened(
        before: org.jmixworkbench.model.IntegrationOpenApiValidationModel,
        after: org.jmixworkbench.model.IntegrationOpenApiValidationModel,
    ): Boolean {
        fun decimal(value: String?) = value?.toBigDecimalOrNull()
        fun minimumTightened(): Boolean {
            val old = decimal(before.minimum)
            val new = decimal(after.minimum)
            return when {
                old == null -> new != null
                new == null -> false
                new > old -> true
                new < old -> false
                else -> !before.minimumExclusive && after.minimumExclusive
            }
        }
        fun maximumTightened(): Boolean {
            val old = decimal(before.maximum)
            val new = decimal(after.maximum)
            return when {
                old == null -> new != null
                new == null -> false
                new < old -> true
                new > old -> false
                else -> !before.maximumExclusive && after.maximumExclusive
            }
        }
        fun minimum(old: Int?, new: Int?): Boolean = new != null && (old == null || new > old)
        fun maximum(old: Int?, new: Int?): Boolean = new != null && (old == null || new < old)
        return minimumTightened() ||
            maximumTightened() ||
            (after.multiplesOf.toSet() - before.multiplesOf.toSet()).isNotEmpty() ||
            minimum(before.minLength, after.minLength) ||
            maximum(before.maxLength, after.maxLength) ||
            (after.patterns.toSet() - before.patterns.toSet()).isNotEmpty() ||
            minimum(before.minItems, after.minItems) ||
            maximum(before.maxItems, after.maxItems) ||
            (!before.uniqueItems && after.uniqueItems) ||
            minimum(before.minProperties, after.minProperties) ||
            maximum(before.maxProperties, after.maxProperties) ||
            (before.constValue == null && after.constValue != null) ||
            (before.constValue != null && after.constValue != null && before.constValue != after.constValue)
    }

    private fun propertySignature(property: org.jmixworkbench.model.IntegrationOpenApiPropertyModel): String =
        listOf(
            property.javaName,
            property.required,
            property.nullable,
            property.readOnly,
            property.writeOnly,
        ).joinToString("|")

    private fun securitySignature(operation: IntegrationOpenApiOperationModel): String =
        operation.securityRequirements.map { requirement ->
            requirement.schemes.map { scheme ->
                listOf(
                    scheme.name,
                    scheme.kind,
                    scheme.parameterName,
                    scheme.parameterLocation,
                    scheme.requiredScopes.sorted().joinToString(","),
                ).joinToString(":")
            }.sorted().joinToString("+")
        }.sorted().joinToString(" OR ")

    private fun maxImpact(left: OpenApiEvolutionImpact, right: OpenApiEvolutionImpact): OpenApiEvolutionImpact =
        if (impactRank(left) >= impactRank(right)) left else right

    private fun impactRank(impact: OpenApiEvolutionImpact): Int = when (impact) {
        OpenApiEvolutionImpact.NONE -> 0
        OpenApiEvolutionImpact.COMPATIBLE -> 1
        OpenApiEvolutionImpact.REVIEW -> 2
        OpenApiEvolutionImpact.BREAKING -> 3
    }
}

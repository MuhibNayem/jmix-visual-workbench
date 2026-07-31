package org.jmixworkbench.generator

import org.jmixworkbench.model.EntityType
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationOpenApiJmixLayerModel
import org.jmixworkbench.model.IntegrationOpenApiJmixTargetKind
import org.jmixworkbench.model.IntegrationOpenApiJmixTypeMapping
import org.jmixworkbench.model.IntegrationOpenApiMappingDirection
import org.jmixworkbench.model.IntegrationOpenApiCustomConverterBinding
import org.jmixworkbench.model.IntegrationOpenApiEnumAdapterBinding
import org.jmixworkbench.model.IntegrationOpenApiOperationModel
import org.jmixworkbench.model.IntegrationOpenApiPropertyModel
import org.jmixworkbench.model.IntegrationOpenApiSchemaKind
import org.jmixworkbench.model.IntegrationOpenApiSchemaModel
import java.util.Locale

/**
 * Generates the Jmix-facing layer over an OpenAPI transport connector.
 *
 * The generator deliberately emits dependency-free, explicit mapping code
 * instead of relying on an annotation processor being present in the target
 * module. It still follows Jmix's DTO mapping contract: entities are created
 * through Metadata and inbound DTO entities are marked as not-new only after a
 * complete mapping succeeds.
 */
object OpenApiJmixLayerGenerator {
    private const val MAX_GENERATED_FILES = 80

    data class ResolvedEntityTarget(
        val artifactId: String,
        val qualifiedName: String,
        val entityType: EntityType,
        val attributes: List<ResolvedEntityAttribute>,
    ) {
        val className: String get() = qualifiedName.substringAfterLast('.')
    }

    data class ResolvedEntityAttribute(
        val name: String,
        val javaType: String,
        val readOnly: Boolean,
    )

    data class Input(
        val connector: IntegrationConnectorModel,
        val operation: IntegrationOpenApiOperationModel,
        val layer: IntegrationOpenApiJmixLayerModel,
        val entityNamePrefix: String,
        val existingTargets: Map<String, ResolvedEntityTarget>,
    )

    data class GeneratedSource(
        val packageName: String,
        val className: String,
        val role: String,
        val content: String,
    ) {
        val packageRelativePath: String
            get() = "${packageName.replace('.', '/')}/$className.java"
    }

    data class Result(
        val sources: List<GeneratedSource>,
        val issues: List<String>,
    )

    fun transportType(
        operation: IntegrationOpenApiOperationModel,
        schemaId: String,
        connectorPackage: String,
        connectorClass: String,
    ): String = transportJavaType(operation, schemaId, connectorPackage, connectorClass)

    fun generate(input: Input): Result {
        val issues = validate(input)
        if (issues.isNotEmpty()) return Result(emptyList(), issues)

        val context = Context(input)
        val sources = buildList {
            context.generatedMappings.forEach { resolved ->
                add(
                    GeneratedSource(
                        packageName = input.layer.dtoPackage,
                        className = resolved.className,
                        role = "JMIX_DTO",
                        content = renderDto(context, resolved),
                    ),
                )
            }
            context.generatedEnums.forEach { schema ->
                add(
                    GeneratedSource(
                        packageName = input.layer.dtoPackage,
                        className = context.generatedTypeName(schema.id),
                        role = "JMIX_ENUM",
                        content = renderEnum(context, schema),
                    ),
                )
            }
            add(
                GeneratedSource(
                    packageName = input.layer.mapperPackage,
                    className = context.mapperClassName,
                    role = "JMIX_MAPPER",
                    content = renderMapper(context),
                ),
            )
            add(
                GeneratedSource(
                    packageName = input.layer.servicePackage,
                    className = input.layer.serviceClassName,
                    role = "APPLICATION_SERVICE",
                    content = renderService(context),
                ),
            )
        }.sortedWith(compareBy(GeneratedSource::packageName, GeneratedSource::className))
        return if (sources.size > MAX_GENERATED_FILES) {
            Result(
                emptyList(),
                listOf("Jmix mapping would generate ${sources.size} files; the reviewed limit is $MAX_GENERATED_FILES."),
            )
        } else {
            Result(sources, emptyList())
        }
    }

    fun validate(input: Input): List<String> {
        val layer = input.layer
        val operation = input.operation
        val schemas = operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val issues = mutableListOf<String>()

        if (!layer.enabled) return emptyList()
        listOf(layer.dtoPackage, layer.mapperPackage, layer.servicePackage).forEach { packageName ->
            if (!isPackageName(packageName)) issues += "Jmix mapping package '$packageName' is not a safe Java package."
        }
        if (!isJavaIdentifier(layer.serviceClassName)) {
            issues += "Jmix application service class name is not a safe Java identifier."
        }
        if (!isJavaIdentifier(layer.serviceBeanName) || layer.serviceBeanName.firstOrNull()?.isLowerCase() != true) {
            issues += "Jmix application service bean name must be a lower-camel Java identifier."
        }
        if (!isJavaIdentifier(input.entityNamePrefix.replace("-", "_"))) {
            issues += "The backend-derived Jmix entity-name prefix is invalid."
        }
        if (operation.requestSchemaId == null && operation.responseSchemaId == null) {
            issues += "The selected OpenAPI operation has no request or response model to expose as Jmix entities."
        }
        val duplicateSchemas = layer.mappings.groupingBy(IntegrationOpenApiJmixTypeMapping::schemaId)
            .eachCount().filterValues { it > 1 }.keys
        if (duplicateSchemas.isNotEmpty()) {
            issues += "Each OpenAPI schema can have only one Jmix target: ${duplicateSchemas.sorted().joinToString()}."
        }

        val reachable = reachableEntitySchemaIds(operation)
        val mappings = layer.mappings.associateBy(IntegrationOpenApiJmixTypeMapping::schemaId)
        val responseRootObject = operation.responseSchemaId?.let { responseId ->
            val response = schemas[responseId]
            when (response?.kind) {
                IntegrationOpenApiSchemaKind.OBJECT -> response.id
                IntegrationOpenApiSchemaKind.ARRAY -> response.itemSchemaId?.takeIf {
                    schemas[it]?.kind == IntegrationOpenApiSchemaKind.OBJECT
                }
                else -> null
            }
        }
        val missing = reachable - mappings.keys
        if (missing.isNotEmpty()) {
            issues += "Every reachable object schema needs a mapping: ${missing.sorted().joinToString()}."
        }
        val unknown = mappings.keys - reachable
        if (unknown.isNotEmpty()) {
            issues += "Mappings reference schemas outside the selected request/response graph: ${unknown.sorted().joinToString()}."
        }

        val generatedNames = mutableMapOf<String, String>()
        layer.mappings.forEach { mapping ->
            val schema = schemas[mapping.schemaId]
            if (schema == null || schema.kind != IntegrationOpenApiSchemaKind.OBJECT) {
                issues += "Jmix type mapping '${mapping.schemaId}' must target an object schema."
                return@forEach
            }
            if (schema.additionalPropertiesAllowed) {
                issues += "OpenAPI map schema '${schema.javaName}' cannot become a Jmix entity attribute graph."
            }
            when (mapping.targetKind) {
                IntegrationOpenApiJmixTargetKind.GENERATED_DTO -> {
                    val name = mapping.generatedClassName.orEmpty()
                    if (!isJavaIdentifier(name)) {
                        issues += "Generated DTO class for '${schema.javaName}' is not a safe Java identifier."
                    } else {
                        generatedNames.putIfAbsent(name, mapping.schemaId)?.let { other ->
                            issues += "Generated DTO class '$name' is shared by schemas '$other' and '${mapping.schemaId}'."
                        }
                    }
                    if (mapping.existingEntity != null) {
                        issues += "Generated DTO mapping '${schema.javaName}' cannot carry an existing-entity binding."
                    }
                }
                IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY -> {
                    val binding = mapping.existingEntity
                    val target = input.existingTargets[mapping.schemaId]
                    if (binding == null || target == null) {
                        issues += "Existing entity mapping '${schema.javaName}' has no backend-resolved target."
                    } else if (
                        binding.artifactId != target.artifactId ||
                        binding.qualifiedName != target.qualifiedName
                    ) {
                        issues += "Existing entity mapping '${schema.javaName}' does not match the indexed target."
                    }
                    if (mapping.generatedClassName != null) {
                        issues += "Existing entity mapping '${schema.javaName}' cannot declare a generated DTO class."
                    }
                }
            }

            val schemaProperties = schema.properties.associateBy(IntegrationOpenApiPropertyModel::javaName)
            val targetProperties = targetProperties(mapping, schema, input.existingTargets[mapping.schemaId])
            val duplicateInbound = mapping.properties
                .filter { it.direction != IntegrationOpenApiMappingDirection.OUTBOUND }
                .groupingBy { it.entityProperty }
                .eachCount().filterValues { it > 1 }.keys
            val duplicateOutbound = mapping.properties
                .filter { it.direction != IntegrationOpenApiMappingDirection.INBOUND }
                .groupingBy { it.schemaProperty }
                .eachCount().filterValues { it > 1 }.keys
            if (duplicateInbound.isNotEmpty()) {
                issues += "Inbound mappings for '${schema.javaName}' write the same entity property more than once: ${duplicateInbound.sorted().joinToString()}."
            }
            if (duplicateOutbound.isNotEmpty()) {
                issues += "Outbound mappings for '${schema.javaName}' write the same transport property more than once: ${duplicateOutbound.sorted().joinToString()}."
            }
            effectivePropertyMappings(mapping, schema, input.existingTargets[mapping.schemaId]).forEach { property ->
                val source = schemaProperties[property.schemaProperty]
                val target = targetProperties[property.entityProperty]
                if (source == null) {
                    issues += "Schema '${schema.javaName}' has no property '${property.schemaProperty}'."
                }
                if (target == null) {
                    issues += "Jmix target for '${schema.javaName}' has no property '${property.entityProperty}'."
                }
                if (
                    property.direction != IntegrationOpenApiMappingDirection.OUTBOUND &&
                    target?.readOnly == true
                ) {
                    issues += "Inbound mapping cannot write read-only entity property '${property.entityProperty}'."
                }
                if (
                    source != null && target != null &&
                    property.enumAdapter == null && property.customConverter == null &&
                    !compatible(source.schemaId, target.javaType, schemas, mappings, input)
                ) {
                    issues += "Property '${schema.javaName}.${source.javaName}' (${typeName(source.schemaId, schemas)}) is not safely compatible with '${property.entityProperty}' (${target.javaType})."
                }
                if (property.enumAdapter != null && property.customConverter != null) {
                    issues += "Property '${schema.javaName}.${property.schemaProperty}' cannot use an enum adapter and a custom converter together."
                }
                if ((property.enumAdapter != null || property.customConverter != null) && mapping.targetKind != IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY) {
                    issues += "Mapping extensions are valid only when '${schema.javaName}' targets an existing Jmix entity."
                }
                property.enumAdapter?.let { adapter ->
                    if (!isPackageName(adapter.qualifiedName.substringBeforeLast('.', "")) ||
                        !isJavaIdentifier(adapter.qualifiedName.substringAfterLast('.')) ||
                        adapter.revisionFingerprint.isBlank()
                    ) {
                        issues += "Enum adapter for '${schema.javaName}.${property.schemaProperty}' has no safe revision-bound type identity."
                    }
                    val enumSchema = source?.let { schemas[it.schemaId] }
                    if (enumSchema?.kind != IntegrationOpenApiSchemaKind.STRING || enumSchema.enumValues.isEmpty()) {
                        issues += "Enum adapter for '${schema.javaName}.${property.schemaProperty}' requires an OpenAPI string enum."
                    }
                    if (adapter.values.map { it.wireValue }.toSet() != enumSchema?.enumValues?.toSet()) {
                        issues += "Enum adapter for '${schema.javaName}.${property.schemaProperty}' must map every OpenAPI wire value exactly once."
                    }
                    if (adapter.values.map { it.wireValue }.distinct().size != adapter.values.size) {
                        issues += "Enum adapter for '${schema.javaName}.${property.schemaProperty}' contains duplicate wire values."
                    }
                    if (
                        property.direction != IntegrationOpenApiMappingDirection.INBOUND &&
                        adapter.values.map { it.enumConstant }.distinct().size != adapter.values.size
                    ) {
                        issues += "Outbound enum adapter for '${schema.javaName}.${property.schemaProperty}' must map each domain constant to one wire value."
                    }
                    if (adapter.values.any { !isJavaIdentifier(it.enumConstant) }) {
                        issues += "Enum adapter for '${schema.javaName}.${property.schemaProperty}' contains an invalid Java enum constant."
                    }
                }
                property.customConverter?.let { converter ->
                    if (!isPackageName(converter.qualifiedName.substringBeforeLast('.', "")) ||
                        !isJavaIdentifier(converter.qualifiedName.substringAfterLast('.')) ||
                        converter.revisionFingerprint.isBlank()
                    ) {
                        issues += "Custom converter for '${schema.javaName}.${property.schemaProperty}' has no safe revision-bound bean identity."
                    }
                    if (
                        property.direction != IntegrationOpenApiMappingDirection.OUTBOUND &&
                        converter.inboundMethod == null
                    ) {
                        issues += "Custom converter for '${schema.javaName}.${property.schemaProperty}' needs an API-to-Jmix method."
                    }
                    if (
                        property.direction != IntegrationOpenApiMappingDirection.INBOUND &&
                        converter.outboundMethod == null
                    ) {
                        issues += "Custom converter for '${schema.javaName}.${property.schemaProperty}' needs a Jmix-to-API method."
                    }
                    listOfNotNull(converter.inboundMethod, converter.outboundMethod).forEach { method ->
                        if (!isJavaIdentifier(method.methodName) || method.signature.isBlank() ||
                            method.parameterType.isBlank() || method.returnType.isBlank()
                        ) {
                            issues += "Custom converter for '${schema.javaName}.${property.schemaProperty}' contains an invalid method contract."
                        }
                    }
                }
            }

            mapping.idProperty?.let {
                if (it !in targetProperties) issues += "Identifier property '$it' is absent from '${schema.javaName}' target."
            }
            if (
                mapping.schemaId == responseRootObject &&
                mapping.targetKind == IntegrationOpenApiJmixTargetKind.GENERATED_DTO &&
                mapping.idProperty == null
            ) {
                issues += "Response DTO '${schema.javaName}' requires a stable identifier property."
            }
            mapping.instanceNameProperty?.let {
                if (it !in targetProperties) issues += "Instance-name property '$it' is absent from '${schema.javaName}' target."
            }
        }
        val enumNames = reachableSchemaIds(operation)
            .mapNotNull(schemas::get)
            .filter { it.kind == IntegrationOpenApiSchemaKind.STRING && it.enumValues.isNotEmpty() }
            .groupBy { safeTypeName(it.javaName) }
        enumNames.filterValues { it.size > 1 }.forEach { (name, collisions) ->
            issues += "OpenAPI enum class '$name' is ambiguous across schemas: ${collisions.map { it.id }.sorted().joinToString()}."
        }
        enumNames.keys.intersect(generatedNames.keys).forEach { name ->
            issues += "Generated DTO and Jmix enum would both use class '$name'. Rename the DTO mapping."
        }

        val outbound = operation.requestSchemaId
            ?.let { reachableEntitySchemaIds(operation, it) }
            .orEmpty()
        outbound.forEach { schemaId ->
            val schema = schemas[schemaId] ?: return@forEach
            val mapping = mappings[schemaId] ?: return@forEach
            val mapped = effectivePropertyMappings(mapping, schema, input.existingTargets[schemaId])
                .filter { it.direction != IntegrationOpenApiMappingDirection.INBOUND }
                .mapTo(mutableSetOf()) { it.schemaProperty }
            schema.properties.filter { it.required && !it.readOnly }.forEach { property ->
                if (property.javaName !in mapped) {
                    issues += "Required outbound property '${schema.javaName}.${property.javaName}' has no entity mapping."
                }
            }
        }
        return issues.distinct().sorted()
    }

    private class Context(val input: Input) {
        val operation = input.operation
        val layer = input.layer
        val schemas = operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val mappings = layer.mappings.associateBy(IntegrationOpenApiJmixTypeMapping::schemaId)
        val mapperClassName = layer.serviceClassName.removeSuffix("Service") + "Mapper"
        val generatedMappings = layer.mappings
            .filter { it.targetKind == IntegrationOpenApiJmixTargetKind.GENERATED_DTO }
            .map { ResolvedMapping(it, requireNotNull(schemas[it.schemaId]), requireNotNull(it.generatedClassName)) }
            .sortedBy(ResolvedMapping::className)
        val generatedEnums = reachableSchemaIds(operation)
            .mapNotNull(schemas::get)
            .filter { it.kind == IntegrationOpenApiSchemaKind.STRING && it.enumValues.isNotEmpty() }
            .distinctBy(IntegrationOpenApiSchemaModel::id)
            .sortedBy(IntegrationOpenApiSchemaModel::javaName)
        val converterFields: Map<String, String> = run {
            val used = linkedSetOf("metadata", "entityStates")
            layer.mappings.flatMap(IntegrationOpenApiJmixTypeMapping::properties)
                .mapNotNull { it.customConverter?.qualifiedName }
                .distinct()
                .sorted()
                .associateWith { qualifiedName ->
                    val simpleName = qualifiedName.substringAfterLast('.')
                    val base = simpleName.replaceFirstChar(Char::lowercaseChar).ifBlank { "valueConverter" }
                    var candidate = base
                    var suffix = 2
                    while (!used.add(candidate)) candidate = "${base}${suffix++}"
                    candidate
                }
        }

        fun targetQualifiedName(schemaId: String): String {
            val mapping = requireNotNull(mappings[schemaId]) { "Missing mapping for schema '$schemaId'." }
            return when (mapping.targetKind) {
                IntegrationOpenApiJmixTargetKind.GENERATED_DTO ->
                    "${layer.dtoPackage}.${requireNotNull(mapping.generatedClassName)}"
                IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY ->
                    requireNotNull(input.existingTargets[schemaId]).qualifiedName
            }
        }

        fun generatedTypeName(schemaId: String): String {
            val schema = requireNotNull(schemas[schemaId])
            if (schema.kind == IntegrationOpenApiSchemaKind.OBJECT) {
                return targetQualifiedName(schemaId).substringAfterLast('.')
            }
            return safeTypeName(schema.javaName)
        }

        fun transportType(schemaId: String): String =
            transportJavaType(operation, schemaId, input.connector.packageName, input.connector.className)

        fun entityType(schemaId: String): String = entityJavaType(this, schemaId)

        fun mapping(schemaId: String): IntegrationOpenApiJmixTypeMapping = requireNotNull(mappings[schemaId])

        fun properties(schemaId: String): List<EffectivePropertyMapping> {
            val mapping = mapping(schemaId)
            val schema = requireNotNull(schemas[schemaId])
            return effectivePropertyMappings(mapping, schema, input.existingTargets[schemaId])
        }

        fun targetPropertyType(schemaId: String, entityProperty: String, propertySchemaId: String): String {
            val existing = input.existingTargets[schemaId]
            return existing?.attributes?.singleOrNull { it.name == entityProperty }?.javaType
                ?: entityJavaType(this, propertySchemaId)
        }

        fun converterField(binding: IntegrationOpenApiCustomConverterBinding): String =
            requireNotNull(converterFields[binding.qualifiedName])
    }

    private data class ResolvedMapping(
        val mapping: IntegrationOpenApiJmixTypeMapping,
        val schema: IntegrationOpenApiSchemaModel,
        val className: String,
    )

    private data class TargetProperty(
        val name: String,
        val javaType: String,
        val readOnly: Boolean,
    )

    private data class EffectivePropertyMapping(
        val schemaProperty: String,
        val entityProperty: String,
        val direction: IntegrationOpenApiMappingDirection,
        val enumAdapter: IntegrationOpenApiEnumAdapterBinding? = null,
        val customConverter: IntegrationOpenApiCustomConverterBinding? = null,
    )

    private fun renderDto(context: Context, resolved: ResolvedMapping): String = buildString {
        val mapping = resolved.mapping
        val schema = resolved.schema
        val properties = context.properties(schema.id)
        val targetBySource = properties.associateBy(EffectivePropertyMapping::schemaProperty)
        append("package ").append(context.layer.dtoPackage).append(";\n\n")
        append("import io.jmix.core.entity.annotation.JmixId;\n")
        append("import io.jmix.core.metamodel.annotation.JmixProperty;\n")
        append("import io.jmix.core.metamodel.annotation.InstanceName;\n")
        append("import io.jmix.core.metamodel.annotation.JmixEntity;\n\n")
        append("@JmixEntity(name = \"").append(escapeJava(context.input.entityNamePrefix))
            .append('_').append(resolved.className).append("\")\n")
        append("public class ").append(resolved.className).append(" {\n")
        schema.properties.forEach { property ->
            val target = targetBySource[property.javaName] ?: return@forEach
            if (mapping.idProperty == target.entityProperty) append("    @JmixId\n")
            if (mapping.instanceNameProperty == target.entityProperty) append("    @InstanceName\n")
            if (property.required && !property.nullable) append("    @JmixProperty(mandatory = true)\n")
            append("    private ").append(context.entityType(property.schemaId)).append(' ')
                .append(target.entityProperty).append(";\n\n")
        }
        schema.properties.forEach { property ->
            val target = targetBySource[property.javaName] ?: return@forEach
            val type = context.entityType(property.schemaId)
            val cap = target.entityProperty.replaceFirstChar(Char::uppercaseChar)
            append("    public ").append(type).append(" get").append(cap).append("() {\n")
            append("        return ").append(target.entityProperty).append(";\n")
            append("    }\n\n")
            append("    public void set").append(cap).append('(').append(type).append(' ')
                .append(target.entityProperty).append(") {\n")
            append("        this.").append(target.entityProperty).append(" = ")
                .append(target.entityProperty).append(";\n")
            append("    }\n\n")
        }
        append("}\n")
    }

    private fun renderEnum(context: Context, schema: IntegrationOpenApiSchemaModel): String = buildString {
        val className = context.generatedTypeName(schema.id)
        val constants = enumConstants(schema.enumValues)
        append("package ").append(context.layer.dtoPackage).append(";\n\n")
        append("import io.jmix.core.metamodel.datatype.EnumClass;\n\n")
        append("public enum ").append(className).append(" implements EnumClass<String> {\n")
        constants.forEachIndexed { index, (constant, wire) ->
            append("    ").append(constant).append("(\"").append(escapeJava(wire)).append("\")")
            append(if (index == constants.lastIndex) ";\n\n" else ",\n")
        }
        append("    private final String id;\n\n")
        append("    ").append(className).append("(String id) {\n")
        append("        this.id = id;\n")
        append("    }\n\n")
        append("    @Override\n")
        append("    public String getId() {\n")
        append("        return id;\n")
        append("    }\n\n")
        append("    public static ").append(className).append(" fromId(String id) {\n")
        append("        if (id == null) return null;\n")
        append("        for (").append(className).append(" value : values()) {\n")
        append("            if (value.id.equals(id)) return value;\n")
        append("        }\n")
        append("        return null;\n")
        append("    }\n")
        append("}\n")
    }

    private fun renderMapper(context: Context): String = buildString {
        val objectSchemas = reachableEntitySchemaIds(context.operation)
            .mapNotNull(context.schemas::get)
            .sortedBy(IntegrationOpenApiSchemaModel::javaName)
        append("package ").append(context.layer.mapperPackage).append(";\n\n")
        append("import io.jmix.core.EntityStates;\n")
        append("import io.jmix.core.Metadata;\n")
        append("import org.springframework.stereotype.Component;\n")
        append("import java.util.List;\n")
        append("import java.util.Objects;\n\n")
        append("@Component\n")
        append("public class ").append(context.mapperClassName).append(" {\n")
        append("    private final Metadata metadata;\n")
        append("    private final EntityStates entityStates;\n")
        context.converterFields.forEach { (qualifiedName, fieldName) ->
            append("    private final ").append(qualifiedName).append(' ').append(fieldName).append(";\n")
        }
        append('\n')
        append("    public ").append(context.mapperClassName)
            .append("(Metadata metadata, EntityStates entityStates")
        context.converterFields.forEach { (qualifiedName, fieldName) ->
            append(", ").append(qualifiedName).append(' ').append(fieldName)
        }
        append(") {\n")
        append("        this.metadata = metadata;\n")
        append("        this.entityStates = entityStates;\n")
        context.converterFields.values.forEach { fieldName ->
            append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n")
        }
        append("    }\n\n")
        objectSchemas.forEach { schema ->
            append(renderInboundMapper(context, schema))
            append('\n')
            append(renderOutboundMapper(context, schema))
            append('\n')
        }
        append("}\n")
    }

    private fun renderInboundMapper(context: Context, schema: IntegrationOpenApiSchemaModel): String = buildString {
        val transportType = context.transportType(schema.id)
        val entityType = context.targetQualifiedName(schema.id)
        val method = "to${entityType.substringAfterLast('.')}"
        val target = context.input.existingTargets[schema.id]
        append("    public ").append(entityType).append(' ').append(method)
            .append('(').append(transportType).append(" source) {\n")
        append("        if (source == null) return null;\n")
        append("        ").append(entityType).append(" target = metadata.create(")
            .append(entityType).append(".class);\n")
        val properties = context.properties(schema.id)
            .filter { it.direction != IntegrationOpenApiMappingDirection.OUTBOUND }
            .associateBy(EffectivePropertyMapping::schemaProperty)
        schema.properties.forEach { property ->
            val mapping = properties[property.javaName] ?: return@forEach
            val setter = "set${mapping.entityProperty.replaceFirstChar(Char::uppercaseChar)}"
            val targetType = context.targetPropertyType(schema.id, mapping.entityProperty, property.schemaId)
            append("        target.").append(setter).append('(')
                .append(inboundMappedExpression(context, mapping, property.schemaId, "source.${property.javaName}()", targetType))
                .append(");\n")
        }
        if (target == null || target.entityType == EntityType.DTO) {
            append("        entityStates.setNew(target, false);\n")
        }
        append("        return target;\n")
        append("    }\n")
    }

    private fun renderOutboundMapper(context: Context, schema: IntegrationOpenApiSchemaModel): String = buildString {
        val transportType = context.transportType(schema.id)
        val entityType = context.targetQualifiedName(schema.id)
        val method = "to${transportType.substringAfterLast('.')}"
        val mappings = context.properties(schema.id)
            .filter { it.direction != IntegrationOpenApiMappingDirection.INBOUND }
            .associateBy(EffectivePropertyMapping::schemaProperty)
        append("    public ").append(transportType).append(' ').append(method)
            .append('(').append(entityType).append(" source) {\n")
        append("        if (source == null) return null;\n")
        append("        return new ").append(transportType).append("(\n")
        schema.properties.forEachIndexed { index, property ->
            val mapping = mappings[property.javaName]
            val expression = if (mapping == null) {
                "null"
            } else {
                val getter = "source.get${mapping.entityProperty.replaceFirstChar(Char::uppercaseChar)}()"
                val targetType = context.targetPropertyType(schema.id, mapping.entityProperty, property.schemaId)
                outboundMappedExpression(context, mapping, property.schemaId, getter, targetType)
            }
            append("                ").append(expression)
            append(if (index == schema.properties.lastIndex) "\n" else ",\n")
        }
        append("        );\n")
        append("    }\n")
    }

    private fun renderService(context: Context): String = buildString {
        val operation = context.operation
        val connectorType = "${context.input.connector.packageName}.${context.input.connector.className}"
        val mapperType = "${context.layer.mapperPackage}.${context.mapperClassName}"
        val responseType = serviceEntityType(context, operation.responseSchemaId)
        val requestType = serviceEntityType(context, operation.requestSchemaId)
        append("package ").append(context.layer.servicePackage).append(";\n\n")
        append("import org.springframework.stereotype.Service;\n\n")
        append("@Service(\"").append(escapeJava(context.layer.serviceBeanName)).append("\")\n")
        append("public class ").append(context.layer.serviceClassName).append(" {\n")
        append("    private final ").append(connectorType).append(" connector;\n")
        append("    private final ").append(mapperType).append(" mapper;\n\n")
        append("    public ").append(context.layer.serviceClassName).append('(')
            .append(connectorType).append(" connector, ").append(mapperType).append(" mapper) {\n")
        append("        this.connector = connector;\n")
        append("        this.mapper = mapper;\n")
        append("    }\n\n")
        append("    public ").append(responseType ?: "void").append(' ')
            .append(operation.javaMethodName).append('(')
        val params = mutableListOf<String>()
        operation.parameters.forEach { parameter ->
            params += "${transportJavaType(operation, parameter.schemaId, context.input.connector.packageName, context.input.connector.className)} ${parameter.javaName}"
        }
        if (requestType != null) params += "$requestType requestEntity"
        if (context.input.connector.reliability.idempotency.enabled) {
            params += "String ${context.input.connector.reliability.idempotency.keyParameterName}"
        }
        append(params.joinToString()).append(") {\n")
        val callArgs = mutableListOf<String>()
        operation.parameters.forEach { callArgs += it.javaName }
        operation.requestSchemaId?.let { schemaId ->
            callArgs += serviceOutboundExpression(context, schemaId, "requestEntity")
        }
        if (context.input.connector.reliability.idempotency.enabled) {
            callArgs += context.input.connector.reliability.idempotency.keyParameterName
        }
        if (operation.responseSchemaId == null) {
            append("        connector.").append(operation.javaMethodName).append('(')
                .append(callArgs.joinToString()).append(");\n")
        } else {
            append("        var response = connector.").append(operation.javaMethodName).append('(')
                .append(callArgs.joinToString()).append(");\n")
            append("        return ").append(serviceInboundExpression(context, operation.responseSchemaId, "response"))
                .append(";\n")
        }
        append("    }\n")
        append("}\n")
    }

    private fun inboundExpression(
        context: Context,
        schemaId: String,
        expression: String,
        targetJavaType: String? = null,
    ): String {
        val schema = requireNotNull(context.schemas[schemaId])
        val normalizedTarget = targetJavaType?.replace(Regex("\\s+"), "")
        return when {
            schema.kind == IntegrationOpenApiSchemaKind.OBJECT ->
                "to${context.targetQualifiedName(schemaId).substringAfterLast('.')}($expression)"
            schema.kind == IntegrationOpenApiSchemaKind.ARRAY -> {
                val itemId = requireNotNull(schema.itemSchemaId)
                val targetItem = normalizedTarget
                    ?.substringAfter('<', "")
                    ?.substringBeforeLast('>', "")
                    ?.takeIf(String::isNotBlank)
                val mapped = "$expression.stream().map(value -> " +
                    inboundExpression(context, itemId, "value", targetItem) + ")"
                if (
                    normalizedTarget?.startsWith("java.util.Set<") == true ||
                    normalizedTarget?.startsWith("Set<") == true
                ) {
                    "$expression == null ? null : $mapped.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))"
                } else {
                    "$expression == null ? null : $mapped.toList()"
                }
            }
            schema.kind == IntegrationOpenApiSchemaKind.STRING && schema.enumValues.isNotEmpty() -> when (normalizedTarget) {
                "String", "java.lang.String" -> "$expression == null ? null : $expression.value()"
                else -> "$expression == null ? null : ${context.layer.dtoPackage}.${context.generatedTypeName(schemaId)}.fromId($expression.value())"
            }
            schema.kind == IntegrationOpenApiSchemaKind.NUMBER -> when (normalizedTarget) {
                "Double", "java.lang.Double" -> "$expression == null ? null : $expression.doubleValue()"
                "Float", "java.lang.Float" -> "$expression == null ? null : $expression.floatValue()"
                "BigDecimal", "java.math.BigDecimal" ->
                    if (schema.format == "float") {
                        "$expression == null ? null : java.math.BigDecimal.valueOf($expression.doubleValue())"
                    } else {
                        expression
                    }
                else -> expression
            }
            schema.kind == IntegrationOpenApiSchemaKind.BINARY ->
                "$expression == null ? null : java.util.Arrays.copyOf($expression, $expression.length)"
            else -> expression
        }
    }

    private fun inboundMappedExpression(
        context: Context,
        mapping: EffectivePropertyMapping,
        schemaId: String,
        expression: String,
        targetJavaType: String,
    ): String {
        mapping.customConverter?.let { converter ->
            val method = requireNotNull(converter.inboundMethod)
            return "$expression == null ? null : ${context.converterField(converter)}.${method.methodName}($expression)"
        }
        mapping.enumAdapter?.let { adapter ->
            val cases = adapter.values.joinToString(" ") { value ->
                "case \"${escapeJava(value.wireValue)}\" -> ${adapter.qualifiedName}.${value.enumConstant};"
            }
            return "$expression == null ? null : switch ($expression.value()) { $cases " +
                "default -> throw new IllegalArgumentException(\"Unsupported OpenAPI enum value: \" + $expression.value()); }"
        }
        return inboundExpression(context, schemaId, expression, targetJavaType)
    }

    private fun outboundExpression(
        context: Context,
        schemaId: String,
        expression: String,
        sourceJavaType: String? = null,
    ): String {
        val schema = requireNotNull(context.schemas[schemaId])
        val normalizedSource = sourceJavaType?.replace(Regex("\\s+"), "")
        return when {
            schema.kind == IntegrationOpenApiSchemaKind.OBJECT -> {
                val transport = context.transportType(schemaId).substringAfterLast('.')
                "to$transport($expression)"
            }
            schema.kind == IntegrationOpenApiSchemaKind.ARRAY -> {
                val itemId = requireNotNull(schema.itemSchemaId)
                val sourceItem = normalizedSource
                    ?.substringAfter('<', "")
                    ?.substringBeforeLast('>', "")
                    ?.takeIf(String::isNotBlank)
                "$expression == null ? null : $expression.stream().map(value -> " +
                    outboundExpression(context, itemId, "value", sourceItem) + ").toList()"
            }
            schema.kind == IntegrationOpenApiSchemaKind.STRING && schema.enumValues.isNotEmpty() -> {
                val transport = context.transportType(schemaId)
                val idExpression = when (normalizedSource) {
                    "String", "java.lang.String" -> expression
                    else -> "$expression.getId()"
                }
                "$expression == null ? null : $transport.fromValue($idExpression)"
            }
            schema.kind == IntegrationOpenApiSchemaKind.NUMBER -> {
                if (schema.format == "float") {
                    "$expression == null ? null : $expression.floatValue()"
                } else {
                    when (normalizedSource) {
                        "Double", "java.lang.Double", "Float", "java.lang.Float" ->
                            "$expression == null ? null : java.math.BigDecimal.valueOf($expression.doubleValue())"
                        else -> expression
                    }
                }
            }
            schema.kind == IntegrationOpenApiSchemaKind.BINARY ->
                "$expression == null ? null : java.util.Arrays.copyOf($expression, $expression.length)"
            else -> expression
        }
    }

    private fun outboundMappedExpression(
        context: Context,
        mapping: EffectivePropertyMapping,
        schemaId: String,
        expression: String,
        sourceJavaType: String,
    ): String {
        mapping.customConverter?.let { converter ->
            val method = requireNotNull(converter.outboundMethod)
            return "$expression == null ? null : ${context.converterField(converter)}.${method.methodName}($expression)"
        }
        mapping.enumAdapter?.let { adapter ->
            val transport = context.transportType(schemaId)
            val cases = adapter.values.joinToString(" ") { value ->
                "case ${value.enumConstant} -> \"${escapeJava(value.wireValue)}\";"
            }
            return "$expression == null ? null : $transport.fromValue(switch ($expression) { $cases " +
                "default -> throw new IllegalArgumentException(\"Unsupported Jmix enum value: \" + $expression); })"
        }
        return outboundExpression(context, schemaId, expression, sourceJavaType)
    }

    private fun serviceOutboundExpression(context: Context, schemaId: String, expression: String): String {
        val schema = requireNotNull(context.schemas[schemaId])
        return if (schema.kind == IntegrationOpenApiSchemaKind.ARRAY) {
            val itemId = requireNotNull(schema.itemSchemaId)
            val transport = context.transportType(itemId).substringAfterLast('.')
            "$expression == null ? null : $expression.stream().map(mapper::to$transport).toList()"
        } else {
            val transport = context.transportType(schemaId).substringAfterLast('.')
            "mapper.to$transport($expression)"
        }
    }

    private fun serviceInboundExpression(context: Context, schemaId: String, expression: String): String {
        val schema = requireNotNull(context.schemas[schemaId])
        return if (schema.kind == IntegrationOpenApiSchemaKind.ARRAY) {
            val itemId = requireNotNull(schema.itemSchemaId)
            val entity = context.targetQualifiedName(itemId).substringAfterLast('.')
            "$expression == null ? null : $expression.stream().map(mapper::to$entity).toList()"
        } else {
            val entity = context.targetQualifiedName(schemaId).substringAfterLast('.')
            "mapper.to$entity($expression)"
        }
    }

    private fun serviceEntityType(context: Context, schemaId: String?): String? {
        schemaId ?: return null
        val schema = requireNotNull(context.schemas[schemaId])
        return if (schema.kind == IntegrationOpenApiSchemaKind.ARRAY) {
            "java.util.List<${context.targetQualifiedName(requireNotNull(schema.itemSchemaId))}>"
        } else {
            context.targetQualifiedName(schemaId)
        }
    }

    private fun entityJavaType(context: Context, schemaId: String): String {
        val schema = requireNotNull(context.schemas[schemaId])
        return when {
            schema.kind == IntegrationOpenApiSchemaKind.OBJECT -> context.targetQualifiedName(schemaId)
            schema.kind == IntegrationOpenApiSchemaKind.ARRAY ->
                "java.util.List<${entityJavaType(context, requireNotNull(schema.itemSchemaId))}>"
            schema.kind == IntegrationOpenApiSchemaKind.STRING && schema.enumValues.isNotEmpty() ->
                "${context.layer.dtoPackage}.${context.generatedTypeName(schemaId)}"
            schema.kind == IntegrationOpenApiSchemaKind.STRING -> "java.lang.String"
            schema.kind == IntegrationOpenApiSchemaKind.INTEGER ->
                if (schema.format == "int64") "java.lang.Long" else "java.lang.Integer"
            schema.kind == IntegrationOpenApiSchemaKind.NUMBER ->
                if (schema.format == "float") "java.lang.Double" else "java.math.BigDecimal"
            schema.kind == IntegrationOpenApiSchemaKind.BOOLEAN -> "java.lang.Boolean"
            schema.kind == IntegrationOpenApiSchemaKind.UUID -> "java.util.UUID"
            schema.kind == IntegrationOpenApiSchemaKind.DATE -> "java.time.LocalDate"
            schema.kind == IntegrationOpenApiSchemaKind.DATE_TIME -> "java.time.OffsetDateTime"
            schema.kind == IntegrationOpenApiSchemaKind.BINARY -> "byte[]"
            else -> "java.lang.Object"
        }
    }

    private fun transportJavaType(
        operation: IntegrationOpenApiOperationModel,
        schemaId: String,
        connectorPackage: String,
        connectorClass: String,
        visiting: MutableSet<String> = mutableSetOf(),
    ): String {
        val schema = operation.schemas.single { it.id == schemaId }
        require(visiting.add(schemaId)) { "Recursive OpenAPI type '$schemaId' is not mappable." }
        return try {
            val prefix = "$connectorPackage.$connectorClass"
            when {
                schema.kind == IntegrationOpenApiSchemaKind.OBJECT -> "$prefix.${nestedTypeName(schema, connectorClass)}"
                schema.kind == IntegrationOpenApiSchemaKind.ARRAY ->
                    "java.util.List<${transportJavaType(operation, requireNotNull(schema.itemSchemaId), connectorPackage, connectorClass, visiting)}>"
                schema.kind == IntegrationOpenApiSchemaKind.STRING && schema.enumValues.isNotEmpty() ->
                    "$prefix.${nestedTypeName(schema, connectorClass)}"
                schema.kind == IntegrationOpenApiSchemaKind.STRING -> "java.lang.String"
                schema.kind == IntegrationOpenApiSchemaKind.INTEGER ->
                    if (schema.format == "int64") "java.lang.Long" else "java.lang.Integer"
                schema.kind == IntegrationOpenApiSchemaKind.NUMBER ->
                    if (schema.format == "float") "java.lang.Float" else "java.math.BigDecimal"
                schema.kind == IntegrationOpenApiSchemaKind.BOOLEAN -> "java.lang.Boolean"
                schema.kind == IntegrationOpenApiSchemaKind.UUID -> "java.util.UUID"
                schema.kind == IntegrationOpenApiSchemaKind.DATE -> "java.time.LocalDate"
                schema.kind == IntegrationOpenApiSchemaKind.DATE_TIME -> "java.time.OffsetDateTime"
                schema.kind == IntegrationOpenApiSchemaKind.BINARY -> "byte[]"
                else -> "java.lang.Object"
            }
        } finally {
            visiting.remove(schemaId)
        }
    }

    private fun reachableEntitySchemaIds(
        operation: IntegrationOpenApiOperationModel,
        root: String? = null,
    ): Set<String> = reachableSchemaIds(operation, root)
        .mapNotNull(operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)::get)
        .filter { it.kind == IntegrationOpenApiSchemaKind.OBJECT }
        .mapTo(linkedSetOf(), IntegrationOpenApiSchemaModel::id)

    private fun reachableSchemaIds(
        operation: IntegrationOpenApiOperationModel,
        root: String? = null,
    ): Set<String> {
        val schemas = operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val roots = if (root != null) listOf(root) else listOfNotNull(
            operation.requestSchemaId,
            operation.responseSchemaId,
        )
        val visited = linkedSetOf<String>()
        fun visit(schemaId: String) {
            if (!visited.add(schemaId)) return
            val schema = schemas[schemaId] ?: return
            schema.properties.forEach { visit(it.schemaId) }
            schema.itemSchemaId?.let(::visit)
            schema.additionalPropertiesSchemaId?.let(::visit)
        }
        roots.forEach(::visit)
        return visited
    }

    private fun targetProperties(
        mapping: IntegrationOpenApiJmixTypeMapping,
        schema: IntegrationOpenApiSchemaModel,
        existing: ResolvedEntityTarget?,
    ): Map<String, TargetProperty> = when (mapping.targetKind) {
        IntegrationOpenApiJmixTargetKind.GENERATED_DTO -> {
            val effective = if (mapping.properties.isEmpty()) {
                schema.properties.map {
                    EffectivePropertyMapping(
                        it.javaName,
                        it.javaName,
                        IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
                    )
                }
            } else {
                mapping.properties.map {
                    EffectivePropertyMapping(
                        it.schemaProperty,
                        it.entityProperty,
                        it.direction,
                        it.enumAdapter,
                        it.customConverter,
                    )
                }
            }
            effective.associate { property ->
                property.entityProperty to TargetProperty(property.entityProperty, "<derived>", false)
            }
        }
        IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY ->
            existing.orEmptyAttributes().associate { it.name to TargetProperty(it.name, it.javaType, it.readOnly) }
    }

    private fun effectivePropertyMappings(
        mapping: IntegrationOpenApiJmixTypeMapping,
        schema: IntegrationOpenApiSchemaModel,
        existing: ResolvedEntityTarget?,
    ): List<EffectivePropertyMapping> {
        if (mapping.properties.isNotEmpty()) {
            return mapping.properties.map {
                EffectivePropertyMapping(
                    it.schemaProperty,
                    it.entityProperty,
                    it.direction,
                    it.enumAdapter,
                    it.customConverter,
                )
            }
        }
        val existingNames = existing?.attributes?.mapTo(hashSetOf(), ResolvedEntityAttribute::name)
        return schema.properties.mapNotNull { property ->
            if (existingNames == null || property.javaName in existingNames) {
                EffectivePropertyMapping(
                    property.javaName,
                    property.javaName,
                    IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
                )
            } else {
                null
            }
        }
    }

    private fun compatible(
        schemaId: String,
        targetJavaType: String,
        schemas: Map<String, IntegrationOpenApiSchemaModel>,
        mappings: Map<String, IntegrationOpenApiJmixTypeMapping>,
        input: Input,
    ): Boolean {
        if (targetJavaType == "<derived>") return true
        val schema = schemas[schemaId] ?: return false
        val normalized = normalizeJavaType(targetJavaType)
        return when {
            schema.kind == IntegrationOpenApiSchemaKind.OBJECT ->
                normalizeJavaType(
                    when (mappings[schemaId]?.targetKind) {
                        IntegrationOpenApiJmixTargetKind.GENERATED_DTO ->
                            "${input.layer.dtoPackage}.${mappings[schemaId]?.generatedClassName}"
                        IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY ->
                            input.existingTargets[schemaId]?.qualifiedName
                        null -> null
                    }.orEmpty(),
                ) == normalized || normalized.substringAfterLast('.') ==
                    input.existingTargets[schemaId]?.className
            schema.kind == IntegrationOpenApiSchemaKind.ARRAY ->
                normalized.startsWith("java.util.List<") || normalized.startsWith("List<") ||
                    normalized.startsWith("java.util.Set<") || normalized.startsWith("Set<")
            schema.kind == IntegrationOpenApiSchemaKind.STRING && schema.enumValues.isNotEmpty() ->
                normalized in setOf(
                    "String",
                    "java.lang.String",
                    "${input.layer.dtoPackage}.${safeTypeName(schema.javaName)}",
                )
            schema.kind == IntegrationOpenApiSchemaKind.STRING -> normalized in setOf("String", "java.lang.String")
            schema.kind == IntegrationOpenApiSchemaKind.INTEGER && schema.format == "int64" ->
                normalized in setOf("Long", "java.lang.Long")
            schema.kind == IntegrationOpenApiSchemaKind.INTEGER ->
                normalized in setOf("Integer", "java.lang.Integer")
            schema.kind == IntegrationOpenApiSchemaKind.NUMBER ->
                normalized in setOf(
                    "Double", "java.lang.Double", "Float", "java.lang.Float",
                    "BigDecimal", "java.math.BigDecimal",
                )
            schema.kind == IntegrationOpenApiSchemaKind.BOOLEAN ->
                normalized in setOf("Boolean", "java.lang.Boolean")
            schema.kind == IntegrationOpenApiSchemaKind.UUID ->
                normalized in setOf("UUID", "java.util.UUID")
            schema.kind == IntegrationOpenApiSchemaKind.DATE ->
                normalized in setOf("LocalDate", "java.time.LocalDate")
            schema.kind == IntegrationOpenApiSchemaKind.DATE_TIME ->
                normalized in setOf("OffsetDateTime", "java.time.OffsetDateTime")
            schema.kind == IntegrationOpenApiSchemaKind.BINARY -> normalized == "byte[]"
            else -> false
        }
    }

    private fun typeName(schemaId: String, schemas: Map<String, IntegrationOpenApiSchemaModel>): String =
        schemas[schemaId]?.javaName ?: schemaId

    private fun ResolvedEntityTarget?.orEmptyAttributes(): List<ResolvedEntityAttribute> =
        this?.attributes.orEmpty()

    private fun enumConstants(values: List<String>): List<Pair<String, String>> {
        val used = mutableSetOf<String>()
        return values.mapIndexed { index, wire ->
            var base = wire.uppercase(Locale.ROOT)
                .replace(Regex("[^A-Z0-9_]+"), "_")
                .trim('_')
                .ifBlank { "VALUE_${index + 1}" }
            if (base.first().isDigit()) base = "VALUE_$base"
            if (base.lowercase(Locale.ROOT) in JAVA_KEYWORDS) base = "${base}_VALUE"
            var candidate = base
            var suffix = 2
            while (!used.add(candidate)) candidate = "${base}_${suffix++}"
            candidate to wire
        }
    }

    private fun nestedTypeName(schema: IntegrationOpenApiSchemaModel, connectorClass: String): String =
        if (schema.javaName == connectorClass) "${schema.javaName}Model" else schema.javaName

    private fun safeTypeName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9_$]"), "_")
        val prefixed = if (cleaned.firstOrNull()?.isDigit() == true) "Type_$cleaned" else cleaned
        return prefixed.ifBlank { "GeneratedType" }
    }

    private fun normalizeJavaType(value: String): String = value.replace(Regex("\\s+"), "")

    private fun isPackageName(value: String): Boolean =
        value.length in 3..300 && value.split('.').size >= 2 && value.split('.').all(::isJavaIdentifier)

    private fun isJavaIdentifier(value: String): Boolean =
        value.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")) && value !in JAVA_KEYWORDS

    private fun escapeJava(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while",
    )
}

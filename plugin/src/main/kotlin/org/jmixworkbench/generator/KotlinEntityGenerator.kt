package org.jmixworkbench.generator

import org.jmixworkbench.model.*

/**
 * Generates idiomatic Kotlin entities without translating Java source text.
 *
 * The generator intentionally uses field access for Jmix enums so the persisted
 * EnumClass ID has the same semantics as the Java generator. All other attributes
 * are Kotlin properties, allowing manual edits to round-trip through normal PSI.
 */
object KotlinEntityGenerator {
    data class Fragment(
        val source: String,
        val imports: Set<String>,
    )

    fun generate(entity: EntityModel): String {
        validate(entity)
        return when (entity.entityType) {
            EntityType.ENUM -> enumSource(entity)
            EntityType.DTO -> dtoSource(entity)
            else -> jpaSource(entity)
        }
    }

    fun attributeFragment(entity: EntityModel, attribute: AttributeModel): Fragment {
        val imports = linkedSetOf("jakarta.persistence.*")
        return Fragment(attributeSource(entity, attribute, imports), imports)
    }

    private fun jpaSource(entity: EntityModel): String {
        val imports = commonImports(entity)
        val body = mutableListOf<String>()
        if (entity.entityType != EntityType.EMBEDDABLE) {
            body += idSource(entity, imports)
        }
        body += traitSources(entity, imports)
        body += entity.attributes.flatMap { attribute ->
            attributeSource(entity, attribute, imports).split(FRAGMENT_SEPARATOR)
        }
        if (entity.embeddableIdentity) {
            imports += "java.util.Objects"
            body += """
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other == null || javaClass != other.javaClass) return false
                    other as ${entity.className}
                    return ${entity.attributes.joinToString(" && ") { "${it.name} == other.${it.name}" }}
                }

                override fun hashCode(): Int = Objects.hash(${entity.attributes.joinToString(", ") { it.name }})
            """.trimIndent()
        }
        entity.instanceNamePattern?.let { expression ->
            imports += "io.jmix.core.metamodel.annotation.InstanceName"
            body += """
                @InstanceName
                fun getInstanceName(): String = $expression
            """.trimIndent()
        }
        entity.lifecycleCallbacks.forEach { callback ->
            imports += "jakarta.persistence.${callback.annotation.removePrefix("@")}"
            body += """
                ${callback.annotation}
                protected fun on${callback.name.lowercase().replaceFirstChar(Char::uppercase)}() {
                    // Intentionally empty; add reviewed lifecycle logic explicitly.
                }
            """.trimIndent()
        }

        val classAnnotations = classAnnotations(entity, imports)
        val inheritance = buildString {
            entity.extendsClass?.takeIf(String::isNotBlank)?.let {
                if ('.' in it) imports += it
                append(" : ").append(it.substringAfterLast('.')).append("()")
            }
            val interfaces = (
                entity.implementsInterfaces +
                    if (entity.embeddableIdentity) listOf("java.io.Serializable") else emptyList()
                ).distinct().map {
                if ('.' in it) imports += it
                it.substringAfterLast('.')
            }
            if (interfaces.isNotEmpty()) {
                append(if (isEmpty()) " : " else ", ")
                append(interfaces.joinToString())
            }
        }
        val classKeyword = when (entity.entityType) {
            EntityType.MAPPED_SUPERCLASS -> "abstract class"
            else -> "open class"
        }
        return sourceFile(
            entity.packageName,
            imports,
            buildString {
                append(classAnnotations.joinToString("\n")).append('\n')
                append(classKeyword).append(' ').append(entity.className).append(inheritance)
                append(" {\n")
                if (body.isNotEmpty()) {
                    append(body.joinToString("\n\n") { indent(it) }).append('\n')
                }
                append("}\n")
            },
        )
    }

    private fun dtoSource(entity: EntityModel): String {
        val imports = linkedSetOf(
            "io.jmix.core.metamodel.annotation.JmixEntity",
            "io.jmix.core.metamodel.annotation.InstanceName",
        )
        if (entity.dataStore.isNotBlank() && entity.dataStore != "main") {
            imports += "io.jmix.core.metamodel.annotation.Store"
        }
        val body = mutableListOf(idSource(entity, imports, dto = true))
        body += entity.attributes.flatMap { attribute ->
            attributeSource(entity, attribute.copy(transientFlag = true), imports, dto = true)
                .split(FRAGMENT_SEPARATOR)
        }
        entity.instanceNamePattern?.let { expression ->
            body += """
                @InstanceName
                fun getInstanceName(): String = $expression
            """.trimIndent()
        }
        val annotations = mutableListOf(
            annotation(
                "JmixEntity",
                buildMap {
                    put("name", quote(entity.resolvedEntityName))
                    if (entity.annotatedPropertiesOnly) put("annotatedPropertiesOnly", "true")
                },
            ),
        )
        if (entity.dataStore.isNotBlank() && entity.dataStore != "main") {
            annotations += annotation("Store", mapOf("name" to quote(entity.dataStore)))
        }
        if (entity.systemLevel) {
            imports += "io.jmix.core.entity.annotation.SystemLevel"
            annotations += "@SystemLevel"
        }
        entity.comment?.let {
            imports += "io.jmix.core.metamodel.annotation.Comment"
            annotations += annotation("Comment", value = quote(it))
        }
        return sourceFile(
            entity.packageName,
            imports,
            buildString {
                append(annotations.joinToString("\n")).append('\n')
                append("open class ").append(entity.className).append(" {\n")
                append(body.joinToString("\n\n") { indent(it) }).append('\n')
                append("}\n")
            },
        )
    }

    private fun enumSource(entity: EntityModel): String {
        val config = requireNotNull(entity.enumConfig)
        val idType = if (config.idType == EnumIdType.INTEGER) "Int" else "String"
        val imports = linkedSetOf("io.jmix.core.metamodel.datatype.EnumClass")
        val constants = config.values.joinToString(",\n") { value ->
            val stored = if (config.idType == EnumIdType.INTEGER) value.storedValue else quote(value.storedValue)
            "    ${value.name}($stored)"
        }
        return sourceFile(
            entity.packageName,
            imports,
            """
                enum class ${entity.className}(private val id: $idType) : EnumClass<$idType> {
                $constants;

                    override fun getId(): $idType = id

                    companion object {
                        @JvmStatic
                        fun fromId(id: $idType?): ${entity.className}? =
                            entries.firstOrNull { it.id == id }
                    }
                }
            """.trimIndent() + "\n",
        )
    }

    private fun commonImports(entity: EntityModel): LinkedHashSet<String> =
        linkedSetOf<String>().apply {
            add("jakarta.persistence.*")
            add("io.jmix.core.metamodel.annotation.JmixEntity")
            add("io.jmix.core.entity.annotation.JmixGeneratedValue")
            if (entity.instanceNamePattern != null) add("io.jmix.core.metamodel.annotation.InstanceName")
            if (entity.dataStore.isNotBlank() && entity.dataStore != "main") {
                add("io.jmix.core.metamodel.annotation.Store")
            }
        }

    private fun classAnnotations(
        entity: EntityModel,
        imports: MutableSet<String>,
    ): List<String> = buildList {
        entity.inheritance?.let { inheritance ->
            add(annotation("Inheritance", mapOf("strategy" to "InheritanceType.${inheritance.strategy.name}")))
            inheritance.discriminatorColumn?.let {
                add(
                    annotation(
                        "DiscriminatorColumn",
                        linkedMapOf(
                            "name" to quote(it),
                            "discriminatorType" to "DiscriminatorType.${inheritance.discriminatorType}",
                        ),
                    ),
                )
            }
            inheritance.discriminatorValue?.let {
                add(annotation("DiscriminatorValue", value = quote(it)))
            }
        }
        when (entity.entityType) {
            EntityType.ENTITY ->
                add(annotation("Entity", mapOf("name" to quote(entity.resolvedEntityName))))
            EntityType.MAPPED_SUPERCLASS -> add("@MappedSuperclass")
            EntityType.EMBEDDABLE -> add("@Embeddable")
            else -> Unit
        }
        if (entity.entityType == EntityType.ENTITY) {
            val parameters = linkedMapOf<String, String>("name" to quote(entity.resolvedTableName))
            entity.tableSchema?.takeIf(String::isNotBlank)?.let {
                parameters["schema"] = quote(it)
            }
            entity.tableCatalog?.takeIf(String::isNotBlank)?.let {
                parameters["catalog"] = quote(it)
            }
            if (entity.indexes.isNotEmpty()) {
                parameters["indexes"] = entity.indexes.joinToString(prefix = "[", postfix = "]") {
                    annotation(
                        "Index",
                        linkedMapOf(
                            "name" to quote(it.name),
                            "columnList" to quote(it.columns.joinToString(", ")),
                        ).apply { if (it.unique) put("unique", "true") },
                    ).removePrefix("@")
                }
            }
            if (entity.uniqueConstraints.isNotEmpty()) {
                parameters["uniqueConstraints"] =
                    entity.uniqueConstraints.joinToString(prefix = "[", postfix = "]") {
                        annotation(
                            "UniqueConstraint",
                            linkedMapOf(
                                "name" to quote(it.name),
                                "columnNames" to it.columns.joinToString(prefix = "[", postfix = "]", transform = ::quote),
                            ),
                        ).removePrefix("@")
                    }
            }
            add(annotation("Table", parameters))
        }
        add(
            annotation(
                "JmixEntity",
                buildMap {
                    if (entity.entityType != EntityType.ENTITY) {
                        put("name", quote(entity.resolvedEntityName))
                    }
                    if (entity.annotatedPropertiesOnly) put("annotatedPropertiesOnly", "true")
                },
            ),
        )
        if (entity.dataStore.isNotBlank() && entity.dataStore != "main") {
            add(annotation("Store", mapOf("name" to quote(entity.dataStore))))
        }
        if (entity.databaseView) {
            imports += "io.jmix.data.DbView"
            add("@DbView")
        }
        if (entity.systemLevel) {
            imports += "io.jmix.core.entity.annotation.SystemLevel"
            add("@SystemLevel")
        }
        entity.comment?.let {
            imports += "io.jmix.core.metamodel.annotation.Comment"
            add(annotation("Comment", value = quote(it)))
        }
        val ddl = entity.ddlGeneration
        if (
            ddl.effectiveMode != DdlGenerationMode.CREATE_AND_DROP ||
            ddl.unmappedColumns.isNotEmpty() ||
            ddl.unmappedConstraints.isNotEmpty()
        ) {
            imports += "io.jmix.data.DdlGeneration"
            imports += "io.jmix.data.DdlGeneration.DbScriptGenerationMode"
            add(
                annotation(
                    "DdlGeneration",
                    buildMap {
                        put("value", "DbScriptGenerationMode.${ddl.effectiveMode.name}")
                        if (ddl.unmappedColumns.isNotEmpty()) {
                            put(
                                "unmappedColumns",
                                ddl.unmappedColumns.joinToString(prefix = "[", postfix = "]", transform = ::quote),
                            )
                        }
                        if (ddl.unmappedConstraints.isNotEmpty()) {
                            put(
                                "unmappedConstraints",
                                ddl.unmappedConstraints.joinToString(prefix = "[", postfix = "]", transform = ::quote),
                            )
                        }
                    },
                ),
            )
        }
        if (entity.entityListeners.isNotEmpty()) {
            entity.entityListeners.filter { '.' in it }.forEach(imports::add)
            add(
                annotation(
                    "EntityListeners",
                    value = entity.entityListeners.joinToString(prefix = "[", postfix = "]") {
                        "${it.substringAfterLast('.')}::class"
                    },
                ),
            )
        }
        entity.annotations.forEach { custom ->
            custom.importPath?.let(imports::add)
            add(customAnnotation(custom))
        }
    }

    private fun idSource(
        entity: EntityModel,
        imports: MutableSet<String>,
        dto: Boolean = false,
    ): String {
        val annotations = mutableListOf<String>()
        if (dto) {
            imports += "io.jmix.core.metamodel.annotation.JmixId"
            annotations += "@JmixId"
            if (entity.annotatedPropertiesOnly) {
                imports += "io.jmix.core.metamodel.annotation.JmixProperty"
                annotations += "@JmixProperty"
            }
            if (entity.id.generation == IdGeneration.JMIX_GENERATED) {
                imports += "io.jmix.core.entity.annotation.JmixGeneratedValue"
                annotations += "@JmixGeneratedValue"
            }
        } else if (entity.id.type == IdType.EMBEDDED) {
            annotations += "@EmbeddedId"
        } else {
            annotations += "@Id"
            val column = linkedMapOf(
                "name" to quote(entity.id.columnName),
                "nullable" to "false",
            )
            entity.id.length?.takeIf { entity.id.type == IdType.STRING }?.let {
                column["length"] = it.toString()
            }
            annotations += annotation("Column", column)
            when (entity.id.generation) {
                IdGeneration.JMIX_GENERATED -> annotations += "@JmixGeneratedValue"
                IdGeneration.IDENTITY ->
                    annotations += annotation("GeneratedValue", mapOf("strategy" to "GenerationType.IDENTITY"))
                IdGeneration.SEQUENCE -> {
                    val name = entity.id.sequenceName ?: "${entity.resolvedTableName}_seq"
                    annotations += annotation(
                        "GeneratedValue",
                        linkedMapOf("strategy" to "GenerationType.SEQUENCE", "generator" to quote(name)),
                    )
                    annotations += annotation(
                        "SequenceGenerator",
                        linkedMapOf("name" to quote(name), "sequenceName" to quote(name), "allocationSize" to "1"),
                    )
                }
                IdGeneration.ASSIGNED -> Unit
            }
        }
        val type = when (entity.id.type) {
            IdType.UUID -> "UUID".also { imports += "java.util.UUID" }
            IdType.LONG -> "Long"
            IdType.INTEGER -> "Int"
            IdType.STRING -> "String"
            IdType.EMBEDDED -> requireNotNull(entity.id.embeddedIdClass).also {
                if ('.' in it) imports += it
            }.substringAfterLast('.')
        }
        val mutability = if (dto && entity.dtoConfig?.readOnly == true) "val" else "var"
        return annotations.joinToString("\n") + "\n$mutability id: $type? = null"
    }

    private fun traitSources(entity: EntityModel, imports: MutableSet<String>): List<String> {
        val traits = entity.traits.toSet()
        val fields = mutableListOf<String>()
        fun add(name: String, type: String, column: String, annotationName: String, importName: String) {
            imports += importName
            fields += "@$annotationName\n@Column(name = ${quote(column)})\nvar $name: $type? = null"
        }
        if (TraitType.UUID_TRAIT in traits && entity.id.type != IdType.UUID) {
            imports += "java.util.UUID"
            fields += "@JmixGeneratedValue\n@Column(name = \"UUID\", nullable = false, unique = true)\nvar uuid: UUID? = null"
        }
        if (TraitType.HAS_VERSION in traits || TraitType.STANDARD_ENTITY in traits) {
            fields += "@Version\n@Column(name = \"VERSION\", nullable = false)\nvar version: Int? = null"
        }
        if (TraitType.SOFT_DELETE in traits) {
            imports += "java.time.OffsetDateTime"
            add("deletedDate", "OffsetDateTime", "DELETED_DATE", "DeletedDate", "io.jmix.core.annotation.DeletedDate")
            add("deletedBy", "String", "DELETED_BY", "DeletedBy", "io.jmix.core.annotation.DeletedBy")
        }
        if (TraitType.HAS_TENANT_ID in traits) {
            add("sysTenantId", "String", "SYS_TENANT_ID", "TenantId", "io.jmix.core.annotation.TenantId")
        }
        val audit = TraitType.AUDITABLE in traits || TraitType.STANDARD_ENTITY in traits
        if (audit || TraitType.CREATED_BY in traits) {
            add("createdBy", "String", "CREATED_BY", "CreatedBy", "org.springframework.data.annotation.CreatedBy")
        }
        if (audit || TraitType.CREATED_DATE in traits) {
            imports += "java.time.OffsetDateTime"
            add("createdDate", "OffsetDateTime", "CREATED_DATE", "CreatedDate", "org.springframework.data.annotation.CreatedDate")
        }
        if (audit || TraitType.UPDATED_BY in traits) {
            add("lastModifiedBy", "String", "LAST_MODIFIED_BY", "LastModifiedBy", "org.springframework.data.annotation.LastModifiedBy")
        }
        if (audit || TraitType.UPDATED_DATE in traits) {
            imports += "java.time.OffsetDateTime"
            add("lastModifiedDate", "OffsetDateTime", "LAST_MODIFIED_DATE", "LastModifiedDate", "org.springframework.data.annotation.LastModifiedDate")
        }
        return fields
    }

    private fun attributeSource(
        entity: EntityModel,
        attribute: AttributeModel,
        imports: MutableSet<String>,
        dto: Boolean = false,
    ): String {
        attribute.requiresImport.forEach(imports::add)
        attribute.enumClass?.takeIf { '.' in it }?.let(imports::add)
        attribute.association?.relatedEntity?.takeIf { '.' in it }?.let(imports::add)
        attribute.embeddedClass?.takeIf { '.' in it }?.let(imports::add)
        attribute.validations.flatMap(ValidationModel::groups)
            .filter { '.' in it }
            .forEach(imports::add)

        if (attribute.association?.crossDataStore == true) {
            val association = requireNotNull(attribute.association)
            val idType = kotlinIdType(association.relatedIdType, imports)
            imports += "io.jmix.core.entity.annotation.SystemLevel"
            imports += "io.jmix.core.metamodel.annotation.JmixProperty"
            imports += "io.jmix.core.metamodel.annotation.DependsOnProperties"
            val idProperty = """
                @SystemLevel
                @Column(name = ${quote(association.joinColumnName ?: attribute.resolvedColumnName + "_ID")}${if (attribute.mandatory) ", nullable = false" else ""})
                var ${attribute.relationshipIdAttributeName}: $idType? = null
            """.trimIndent()
            val reference = """
                @Transient
                @JmixProperty
                @DependsOnProperties(${quote(attribute.relationshipIdAttributeName)})
                var ${attribute.name}: ${association.relatedEntity.substringAfterLast('.')}? = null
            """.trimIndent()
            return idProperty + FRAGMENT_SEPARATOR + reference
        }

        val annotations = attributeAnnotations(entity, attribute, imports, dto)
        if (attribute.type == AttributeType.ENUM && !dto) {
            val enumType = requireNotNull(attribute.enumClass).substringAfterLast('.')
            val idType = if (attribute.enumIdType == EnumIdType.INTEGER) "Int" else "String"
            val suffix = attribute.name.replaceFirstChar(Char::uppercase)
            val backing = buildString {
                append(annotations.joinToString("\n")).append('\n')
                append("@JvmField\nprotected var ").append(attribute.name).append(": ")
                    .append(idType).append("? = null")
            }
            val accessor = buildString {
                append("fun get").append(suffix).append("(): ").append(enumType)
                    .append("? = ").append(attribute.name).append("?.let(").append(enumType).append("::fromId)")
                if (!attribute.readOnly) {
                    append("\n\nfun set").append(suffix).append("(value: ").append(enumType)
                        .append("?) {\n    ").append(attribute.name).append(" = value?.getId()\n}")
                }
            }
            return backing + FRAGMENT_SEPARATOR + accessor
        }
        val type = kotlinAttributeType(attribute, imports)
        val mutable = if (attribute.readOnly || (dto && entity.dtoConfig?.readOnly == true)) "val" else "var"
        val initializer = attribute.defaultValue?.takeIf(String::isNotBlank) ?: "null"
        return annotations.joinToString("\n") + "\n$mutable ${attribute.name}: $type? = $initializer"
    }

    private fun attributeAnnotations(
        entity: EntityModel,
        attribute: AttributeModel,
        imports: MutableSet<String>,
        dto: Boolean,
    ): List<String> = buildList {
        if (attribute.mandatory && attribute.validations.none { it.type == ValidationType.NOT_NULL }) {
            imports += "jakarta.validation.constraints.NotNull"
            add("@NotNull")
        }
        if (attribute.name == entity.instanceNameAttribute) {
            imports += "io.jmix.core.metamodel.annotation.InstanceName"
            add("@InstanceName")
        }
        if (attribute.systemLevel) {
            imports += "io.jmix.core.entity.annotation.SystemLevel"
            add("@SystemLevel")
        }
        attribute.comment?.let {
            imports += "io.jmix.core.metamodel.annotation.Comment"
            add(annotation("Comment", value = quote(it)))
        }
        if (attribute.lob) add("@Lob")
        if (attribute.jmixProperty || entity.annotatedPropertiesOnly || attribute.transientFlag || dto) {
            imports += "io.jmix.core.metamodel.annotation.JmixProperty"
            add(annotation("JmixProperty", if (attribute.mandatory) mapOf("mandatory" to "true") else emptyMap()))
        }
        if (attribute.dependsOnProperties.isNotEmpty()) {
            imports += "io.jmix.core.metamodel.annotation.DependsOnProperties"
            add(
                annotation(
                    "DependsOnProperties",
                    value = attribute.dependsOnProperties.joinToString(prefix = "[", postfix = "]", transform = ::quote),
                ),
            )
        }
        attribute.propertyDatatype?.takeIf(String::isNotBlank)?.let {
            imports += "io.jmix.core.metamodel.annotation.PropertyDatatype"
            add(annotation("PropertyDatatype", value = quote(it)))
        }
        attribute.validations.forEach { validation ->
            imports += validation.type.importPath
            val parameters = linkedMapOf<String, String>()
            when (validation.type) {
                ValidationType.SIZE -> {
                    validation.value?.let { parameters["min"] = it }
                    validation.value2?.let { parameters["max"] = it }
                }
                ValidationType.MIN, ValidationType.MAX -> validation.value?.let { parameters["value"] = it }
                ValidationType.DECIMAL_MIN, ValidationType.DECIMAL_MAX ->
                    validation.value?.let { parameters["value"] = quote(it) }
                ValidationType.PATTERN -> validation.value?.let { parameters["regexp"] = quote(it) }
                ValidationType.DIGITS -> {
                    validation.value?.let { parameters["integer"] = it }
                    validation.value2?.let { parameters["fraction"] = it }
                }
                else -> Unit
            }
            validation.message?.let { parameters["message"] = quote(it) }
            if (validation.groups.isNotEmpty()) {
                parameters["groups"] = validation.groups.joinToString(prefix = "[", postfix = "]") {
                    "${it.substringAfterLast('.')}::class"
                }
            }
            add(annotation(validation.type.annotation, parameters))
        }
        attribute.annotations.forEach { custom ->
            custom.importPath?.let(imports::add)
            add(customAnnotation(custom))
        }
        when {
            dto -> Unit
            attribute.type in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION) -> {
                val association = requireNotNull(attribute.association)
                val parameters = linkedMapOf<String, String>("fetch" to "FetchType.${association.fetch.name}")
                if (association.cascade.isNotEmpty()) {
                    parameters["cascade"] = association.cascade.joinToString(prefix = "[", postfix = "]") {
                        "CascadeType.${it.name}"
                    }
                }
                when (association.associationType) {
                    AssociationType.MANY_TO_ONE -> {
                        if (attribute.mandatory) parameters["optional"] = "false"
                        add(annotation("ManyToOne", parameters))
                        add(kotlinJoinColumnAnnotation(association, attribute))
                    }
                    AssociationType.ONE_TO_MANY -> {
                        parameters["mappedBy"] = quote(requireNotNull(association.mappedBy))
                        if (association.orphanRemoval) parameters["orphanRemoval"] = "true"
                        add(annotation("OneToMany", parameters))
                    }
                    AssociationType.MANY_TO_MANY -> {
                        association.mappedBy?.takeIf(String::isNotBlank)?.let { parameters["mappedBy"] = quote(it) }
                        add(annotation("ManyToMany", parameters))
                        association.joinTable?.takeIf { association.mappedBy.isNullOrBlank() }?.let {
                            val joinColumns = it.joinColumns.ifEmpty {
                                mutableListOf(
                                    AssociationJoinColumn(
                                        it.joinColumnName,
                                        "",
                                    ),
                                )
                            }
                            val inverseJoinColumns = it.inverseJoinColumns.ifEmpty {
                                mutableListOf(
                                    AssociationJoinColumn(
                                        it.inverseJoinColumnName,
                                        "",
                                    ),
                                )
                            }
                            add(
                                annotation(
                                    "JoinTable",
                                    linkedMapOf(
                                        "name" to quote(it.name),
                                        "joinColumns" to joinColumns.joinToString(
                                            prefix = "[",
                                            postfix = "]",
                                            transform = ::kotlinJoinColumn,
                                        ),
                                        "inverseJoinColumns" to inverseJoinColumns.joinToString(
                                            prefix = "[",
                                            postfix = "]",
                                            transform = ::kotlinJoinColumn,
                                        ),
                                    ).apply {
                                        it.schema?.takeIf(String::isNotBlank)?.let { value ->
                                            put("schema", quote(value))
                                        }
                                        it.catalog?.takeIf(String::isNotBlank)?.let { value ->
                                            put("catalog", quote(value))
                                        }
                                    },
                                ),
                            )
                        }
                    }
                    AssociationType.ONE_TO_ONE -> {
                        association.mappedBy?.let { parameters["mappedBy"] = quote(it) }
                        if (attribute.mandatory && association.mappedBy == null) parameters["optional"] = "false"
                        if (association.orphanRemoval) parameters["orphanRemoval"] = "true"
                        add(annotation("OneToOne", parameters))
                        if (association.mappedBy == null) {
                            add(kotlinJoinColumnAnnotation(association, attribute))
                        }
                    }
                }
                if (attribute.type == AttributeType.COMPOSITION) {
                    imports += "io.jmix.core.metamodel.annotation.Composition"
                    imports += "io.jmix.core.entity.annotation.OnDelete"
                    imports += "io.jmix.core.DeletePolicy"
                    add("@Composition")
                    add(annotation("OnDelete", mapOf("value" to "DeletePolicy.CASCADE")))
                }
            }
            attribute.type == AttributeType.EMBEDDED -> add("@Embedded")
            attribute.transientFlag -> add("@Transient")
            else -> {
                val parameters = linkedMapOf("name" to quote(attribute.resolvedColumnName))
                if (attribute.mandatory) parameters["nullable"] = "false"
                if (attribute.unique) parameters["unique"] = "true"
                if (attribute.readOnly) {
                    parameters["insertable"] = "false"
                    parameters["updatable"] = "false"
                }
                if (
                    attribute.length != null &&
                    attribute.type in setOf(AttributeType.STRING, AttributeType.ENUM, AttributeType.URI, AttributeType.FILE_REF)
                ) {
                    parameters["length"] = attribute.length.toString()
                }
                attribute.precision?.let { parameters["precision"] = it.toString() }
                attribute.scale?.let { parameters["scale"] = it.toString() }
                attribute.sqlType?.takeIf(String::isNotBlank)?.let { parameters["columnDefinition"] = quote(it) }
                add(annotation("Column", parameters))
            }
        }
    }

    private fun kotlinJoinColumnAnnotation(
        association: AssociationConfig,
        attribute: AttributeModel,
    ): String {
        val columns = association.joinColumns.ifEmpty {
            mutableListOf(
                AssociationJoinColumn(
                    name = association.joinColumnName ?: attribute.resolvedColumnName + "_ID",
                    referencedColumnName = association.relatedIdColumnName,
                    nullable = !attribute.mandatory,
                ),
            )
        }
        return if (columns.size == 1) {
            "@${kotlinJoinColumn(columns.single())}"
        } else {
            annotation(
                "JoinColumns",
                mapOf(
                    "value" to columns.joinToString(
                        prefix = "[",
                        postfix = "]",
                        transform = ::kotlinJoinColumn,
                    ),
                ),
            )
        }
    }

    private fun kotlinJoinColumn(column: AssociationJoinColumn): String =
        annotation(
            "JoinColumn",
            linkedMapOf("name" to quote(column.name)).apply {
                column.referencedColumnName.takeIf(String::isNotBlank)?.let {
                    put("referencedColumnName", quote(it))
                }
                column.nullable?.let { put("nullable", it.toString()) }
                if (!column.insertable) put("insertable", "false")
                if (!column.updatable) put("updatable", "false")
            },
        ).removePrefix("@")

    private fun kotlinAttributeType(attribute: AttributeModel, imports: MutableSet<String>): String = when (attribute.type) {
        AttributeType.CHARACTER -> "Char"
        AttributeType.INTEGER -> "Int"
        AttributeType.BYTE_ARRAY -> "ByteArray"
        AttributeType.ASSOCIATION, AttributeType.COMPOSITION -> {
            val association = requireNotNull(attribute.association)
            val related = association.relatedEntity.substringAfterLast('.')
            when (association.associationType) {
                AssociationType.ONE_TO_MANY, AssociationType.MANY_TO_MANY ->
                    if (association.collectionType == AssociationCollectionType.SET) "MutableSet<$related>" else "MutableList<$related>"
                else -> related
            }
        }
        AttributeType.CUSTOM -> requireNotNull(attribute.javaTypeName).also {
            if ('.' in it) imports += it
        }.substringAfterLast('.')
        else -> attribute.javaType
    }

    private fun kotlinIdType(type: IdType, imports: MutableSet<String>): String = when (type) {
        IdType.UUID -> "UUID".also { imports += "java.util.UUID" }
        IdType.LONG -> "Long"
        IdType.INTEGER -> "Int"
        IdType.STRING -> "String"
        IdType.EMBEDDED -> "Any"
    }

    private fun customAnnotation(custom: CustomAnnotation): String =
        annotation(custom.name, custom.parameters.mapValues { kotlinExpression(it.value) })

    private fun kotlinExpression(value: String): String =
        value.replace(Regex("""\.class\b"""), "::class")
            .let { converted ->
                if (converted.startsWith("{") && converted.endsWith("}")) {
                    "[${converted.substring(1, converted.length - 1)}]"
                } else {
                    converted
                }
            }

    private fun sourceFile(packageName: String, imports: Set<String>, declaration: String): String = buildString {
        append("package ").append(packageName).append("\n\n")
        imports.filter(String::isNotBlank).distinct().sorted().forEach {
            append("import ").append(it).append('\n')
        }
        if (imports.isNotEmpty()) append('\n')
        append(declaration)
    }

    private fun annotation(
        name: String,
        parameters: Map<String, String> = emptyMap(),
        value: String? = null,
    ): String {
        val arguments = buildList {
            value?.let(::add)
            parameters.forEach { (key, argument) -> add("$key = $argument") }
        }
        return if (arguments.isEmpty()) "@$name" else "@$name(${arguments.joinToString()})"
    }

    private fun quote(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun indent(value: String): String =
        value.lineSequence().joinToString("\n") { line -> if (line.isBlank()) line else "    $line" }

    private fun validate(entity: EntityModel) {
        require(IDENTIFIER.matches(entity.className)) {
            "JVW-ENTITY-CLASS-NAME-INVALID: '${entity.className}' is not a valid Kotlin class name."
        }
        require(entity.packageName.isNotBlank() && entity.packageName.split('.').all(IDENTIFIER::matches)) {
            "JVW-ENTITY-PACKAGE-INVALID: '${entity.packageName}' is not a valid Kotlin package."
        }
        require(entity.attributes.map(AttributeModel::name).distinct().size == entity.attributes.size) {
            "JVW-ENTITY-ATTRIBUTE-DUPLICATE: attribute names must be unique."
        }
        entity.attributes.forEach {
            require(IDENTIFIER.matches(it.name)) {
                "JVW-ENTITY-ATTRIBUTE-NAME-INVALID: '${it.name}' is not a valid Kotlin property name."
            }
        }
        if (entity.embeddableIdentity) {
            require(entity.entityType == EntityType.EMBEDDABLE && entity.attributes.isNotEmpty()) {
                "JVW-ENTITY-EMBEDDABLE-IDENTITY-INVALID: a composite identifier must be a non-empty embeddable."
            }
            require(entity.attributes.all {
                !it.transientFlag &&
                    it.type !in setOf(
                        AttributeType.ASSOCIATION,
                        AttributeType.COMPOSITION,
                        AttributeType.EMBEDDED,
                    )
            }) {
                "JVW-ENTITY-EMBEDDABLE-IDENTITY-SHAPE: composite identifier members must be persistent scalar attributes."
            }
        }
        if (entity.entityType == EntityType.ENUM) {
            val config = requireNotNull(entity.enumConfig) {
                "JVW-ENTITY-ENUM-CONFIG-MISSING: an enumeration needs values and an ID type."
            }
            require(config.values.isNotEmpty()) {
                "JVW-ENTITY-ENUM-VALUES-MISSING: an enumeration needs at least one value."
            }
        }
    }

    private const val FRAGMENT_SEPARATOR = "\u0000"
    private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
}

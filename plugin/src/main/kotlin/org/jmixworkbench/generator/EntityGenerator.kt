package org.jmixworkbench.generator

import org.jmixworkbench.model.*

/**
 * Generates complete JPA/Jmix entity Java source from an EntityModel.
 * Handles: all entity types, ID strategies, inheritance, traits, associations,
 * compositions, embedded, enums, validations, indexes, lifecycle callbacks,
 * soft delete, multitenancy, DDL generation config, custom annotations.
 */
object EntityGenerator {

    fun generate(entity: EntityModel): String {
        validate(entity)
        return when (entity.entityType) {
            EntityType.ENUM -> generateEnum(entity)
            EntityType.DTO -> generateDto(entity)
            else -> generateJpaEntity(entity)
        }
    }

    private fun validate(entity: EntityModel) {
        require(JAVA_IDENTIFIER.matches(entity.className)) {
            "JVW-ENTITY-CLASS-NAME-INVALID: '${entity.className}' is not a valid Java class name."
        }
        require(JAVA_IDENTIFIER.matches(entity.resolvedEntityName)) {
            "JVW-ENTITY-METADATA-NAME-INVALID: '${entity.resolvedEntityName}' is not a valid Jmix entity name."
        }
        require(
            entity.packageName.split('.').all(JAVA_IDENTIFIER::matches) &&
                entity.packageName.isNotBlank(),
        ) {
            "JVW-ENTITY-PACKAGE-INVALID: '${entity.packageName}' is not a valid Java package."
        }
        val duplicateAttributes = entity.attributes.groupingBy(AttributeModel::name)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateAttributes.isEmpty()) {
            "JVW-ENTITY-ATTRIBUTE-DUPLICATE: ${duplicateAttributes.sorted().joinToString()}."
        }
        entity.attributes.forEach { attribute ->
            require(JAVA_IDENTIFIER.matches(attribute.name)) {
                "JVW-ENTITY-ATTRIBUTE-NAME-INVALID: '${attribute.name}' is not a valid Java field name."
            }
            if (attribute.type == AttributeType.CUSTOM) {
                require(!attribute.javaTypeName.isNullOrBlank()) {
                    "JVW-ENTITY-CUSTOM-JAVA-TYPE-MISSING: ${attribute.name} needs a Java type."
                }
                require(attribute.transientFlag || !attribute.sqlType.isNullOrBlank()) {
                    "JVW-ENTITY-CUSTOM-SQL-TYPE-MISSING: persisted custom attribute " +
                        "${attribute.name} needs an explicit SQL type."
                }
            }
            if (attribute.type == AttributeType.ENUM) {
                require(!attribute.enumClass.isNullOrBlank()) {
                    "JVW-ENTITY-ENUM-CLASS-MISSING: ${attribute.name} needs a Jmix EnumClass."
                }
            }
            if (attribute.type == AttributeType.EMBEDDED) {
                require(!attribute.embeddedClass.isNullOrBlank()) {
                    "JVW-ENTITY-EMBEDDED-CLASS-MISSING: ${attribute.name} needs an embeddable class."
                }
                validateEmbeddedOverrides(attribute)
            }
            validateRelationship(attribute)
        }
        validateInheritance(entity)
        if (
            entity.id.type == IdType.EMBEDDED &&
            entity.entityType in setOf(EntityType.ENTITY, EntityType.MAPPED_SUPERCLASS)
        ) {
            require(!entity.id.embeddedIdClass.isNullOrBlank()) {
                "JVW-ENTITY-EMBEDDED-ID-CLASS-MISSING: an embedded identifier needs an embeddable class."
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
        if (entity.entityType == EntityType.DTO) {
            require(entity.id.type != IdType.EMBEDDED) {
                "JVW-ENTITY-DTO-EMBEDDED-ID-UNSUPPORTED: DTO entities need a scalar Jmix identifier."
            }
        }
        if (entity.entityType == EntityType.ENUM) {
            val enumConfig = requireNotNull(entity.enumConfig) {
                "JVW-ENTITY-ENUM-CONFIG-MISSING: an enumeration needs values and an ID type."
            }
            require(enumConfig.values.isNotEmpty()) {
                "JVW-ENTITY-ENUM-VALUES-MISSING: an enumeration needs at least one value."
            }
            val invalidNames = enumConfig.values.map(EnumValueModel::name)
                .filterNot(JAVA_IDENTIFIER::matches)
            require(invalidNames.isEmpty()) {
                "JVW-ENTITY-ENUM-VALUE-NAME-INVALID: ${invalidNames.joinToString()}."
            }
            require(enumConfig.values.map(EnumValueModel::name).distinct().size == enumConfig.values.size) {
                "JVW-ENTITY-ENUM-VALUE-NAME-DUPLICATE: enumeration constant names must be unique."
            }
            require(enumConfig.values.map(EnumValueModel::storedValue).distinct().size == enumConfig.values.size) {
                "JVW-ENTITY-ENUM-ID-DUPLICATE: stored enumeration IDs must be unique."
            }
            if (enumConfig.idType == EnumIdType.INTEGER) {
                require(enumConfig.values.all { it.storedValue.toIntOrNull() != null }) {
                    "JVW-ENTITY-ENUM-INTEGER-ID-INVALID: every stored ID must be a 32-bit integer."
                }
            }
        }
        val schemaNames = (entity.indexes.map(IndexModel::name) +
            entity.uniqueConstraints.map(UniqueConstraintModel::name))
        require(schemaNames.none(String::isBlank)) {
            "JVW-ENTITY-SCHEMA-NAME-MISSING: Every index and unique constraint needs a name."
        }
        val duplicateSchemaNames = schemaNames.groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateSchemaNames.isEmpty()) {
            "JVW-ENTITY-SCHEMA-NAME-DUPLICATE: ${duplicateSchemaNames.sorted().joinToString()}."
        }
        entity.indexes.forEach { index ->
            require(index.columns.isNotEmpty()) {
                "JVW-ENTITY-INDEX-COLUMNS-MISSING: Index ${index.name} needs at least one column."
            }
        }
        entity.uniqueConstraints.forEach { constraint ->
            require(constraint.columns.isNotEmpty()) {
                "JVW-ENTITY-UNIQUE-COLUMNS-MISSING: Constraint ${constraint.name} needs at least one column."
            }
        }
    }

    private fun validateInheritance(entity: EntityModel) {
        val inheritance = entity.inheritance ?: return
        require(entity.entityType == EntityType.ENTITY) {
            "JVW-ENTITY-INHERITANCE-TYPE-INVALID: JPA inheritance is available only to persistent entities."
        }
        require(inheritance.discriminatorType in setOf("STRING", "CHAR", "INTEGER")) {
            "JVW-ENTITY-DISCRIMINATOR-TYPE-INVALID: use STRING, CHAR, or INTEGER."
        }
        require(inheritance.discriminatorLength == null || inheritance.discriminatorLength > 0) {
            "JVW-ENTITY-DISCRIMINATOR-LENGTH-INVALID: discriminator length must be positive."
        }
        if (inheritance.role == InheritanceRole.SUBTYPE) {
            require(!entity.extendsClass.isNullOrBlank()) {
                "JVW-ENTITY-INHERITANCE-PARENT-MISSING: an inheritance subtype needs a parent entity."
            }
            require(inheritance.discriminatorColumn.isNullOrBlank()) {
                "JVW-ENTITY-SUBTYPE-DISCRIMINATOR-COLUMN: only the hierarchy root declares the discriminator column."
            }
        } else {
            require(inheritance.primaryKeyJoinColumnName.isNullOrBlank()) {
                "JVW-ENTITY-ROOT-PRIMARY-KEY-JOIN: only a JOINED subtype declares @PrimaryKeyJoinColumn."
            }
        }
        if (inheritance.strategy == InheritanceStrategy.TABLE_PER_CLASS) {
            require(
                inheritance.discriminatorColumn.isNullOrBlank() &&
                    inheritance.discriminatorValue.isNullOrBlank() &&
                    inheritance.discriminatorLength == null,
            ) {
                "JVW-ENTITY-TABLE-PER-CLASS-DISCRIMINATOR: TABLE_PER_CLASS does not use a discriminator."
            }
        }
        if (!inheritance.primaryKeyJoinColumnName.isNullOrBlank()) {
            require(
                inheritance.role == InheritanceRole.SUBTYPE &&
                    inheritance.strategy == InheritanceStrategy.JOINED,
            ) {
                "JVW-ENTITY-PRIMARY-KEY-JOIN-INVALID: @PrimaryKeyJoinColumn is valid only for a JOINED subtype."
            }
        }
    }

    private fun validateEmbeddedOverrides(attribute: AttributeModel) {
        val scalarPaths = attribute.embeddedAttributeOverrides.map { it.path.trim() }
        val associationPaths = attribute.embeddedAssociationOverrides.map { it.path.trim() }
        require((scalarPaths + associationPaths).none(String::isBlank)) {
            "JVW-ENTITY-EMBEDDED-OVERRIDE-PATH-MISSING: every embedded override needs a member path."
        }
        require((scalarPaths + associationPaths).distinct().size == scalarPaths.size + associationPaths.size) {
            "JVW-ENTITY-EMBEDDED-OVERRIDE-DUPLICATE: override paths must be unique across scalar and association mappings."
        }
        require(attribute.embeddedAttributeOverrides.all { it.columnName.isNotBlank() }) {
            "JVW-ENTITY-EMBEDDED-OVERRIDE-COLUMN-MISSING: every scalar override needs a column."
        }
        require(attribute.embeddedAssociationOverrides.all {
            it.joinColumns.isNotEmpty() && it.joinColumns.all { column -> column.name.isNotBlank() }
        }) {
            "JVW-ENTITY-EMBEDDED-ASSOCIATION-JOIN-MISSING: every association override needs a join column."
        }
    }

    private fun validateRelationship(attribute: AttributeModel) {
        if (attribute.type !in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION)) return
        val association = requireNotNull(attribute.association) {
            "JVW-ENTITY-RELATIONSHIP-CONFIG-MISSING: ${attribute.name} has no relationship configuration."
        }
        require(association.relatedEntity.isNotBlank()) {
            "JVW-ENTITY-RELATIONSHIP-TARGET-MISSING: ${attribute.name} has no related entity."
        }
        require(
            !association.crossDataStore ||
                association.associationType in setOf(AssociationType.MANY_TO_ONE, AssociationType.ONE_TO_ONE),
        ) {
            "JVW-ENTITY-CROSS-STORE-TO-MANY-UNSUPPORTED: Jmix cross-data-store references must be to-one."
        }
        require(!(association.crossDataStore && attribute.type == AttributeType.COMPOSITION)) {
            "JVW-ENTITY-CROSS-STORE-COMPOSITION-UNSUPPORTED: compositions cannot cross data stores."
        }
        if (association.associationType == AssociationType.ONE_TO_MANY) {
            require(!association.mappedBy.isNullOrBlank()) {
                "JVW-ENTITY-ONE-TO-MANY-MAPPED-BY-MISSING: ${attribute.name} must name the inverse to-one attribute."
            }
        }
        if (
            association.associationType == AssociationType.MANY_TO_MANY &&
            association.mappedBy.isNullOrBlank()
        ) {
            requireNotNull(association.joinTable) {
                "JVW-ENTITY-MANY-TO-MANY-JOIN-TABLE-MISSING: owning relationship ${attribute.name} needs a join table."
            }
        }
        if (attribute.type == AttributeType.COMPOSITION) {
            require(
                association.associationType in setOf(AssociationType.ONE_TO_MANY, AssociationType.ONE_TO_ONE),
            ) {
                "JVW-ENTITY-COMPOSITION-CARDINALITY-INVALID: compositions must be one-to-many or one-to-one."
            }
        }
    }

    // ─── JPA Entity / MappedSuperclass / Embeddable ──────────────────────────

    private fun generateJpaEntity(entity: EntityModel): String {
        val b = JavaClassBuilder(entity.className)
        b.package_(entity.packageName)

        // Standard imports
        b.import_(
            "jakarta.persistence.*",
            "io.jmix.core.metamodel.annotation.JmixEntity",
            "io.jmix.core.annotation.DeletedBy",
            "io.jmix.core.annotation.DeletedDate",
            "io.jmix.core.metamodel.annotation.InstanceName",
            "io.jmix.core.entity.annotation.JmixGeneratedValue"
        )

        // Trait imports
        entity.traits.forEach { trait ->
            when (trait) {
                TraitType.UUID_TRAIT -> b.import_("java.util.UUID")
                TraitType.SOFT_DELETE -> {
                    b.import_("java.util.Date", "java.time.OffsetDateTime")
                }
                TraitType.HAS_TENANT_ID -> {}
                TraitType.HAS_VERSION -> {}
                TraitType.CREATED_BY, TraitType.CREATED_DATE,
                TraitType.UPDATED_BY, TraitType.UPDATED_DATE -> {}
                TraitType.AUDITABLE, TraitType.STANDARD_ENTITY -> {
                    b.import_("java.util.Date", "java.time.OffsetDateTime")
                }
            }
        }

        // Inheritance
        entity.inheritance?.let { inh ->
            if (inh.role == InheritanceRole.ROOT) {
                b.annotation {
                    name = "Inheritance"
                    importPath = "jakarta.persistence.Inheritance"
                    param("strategy", "InheritanceType.${inh.strategy.name}")
                }
                if (
                    inh.strategy != InheritanceStrategy.TABLE_PER_CLASS &&
                    !inh.discriminatorColumn.isNullOrBlank()
                ) {
                    b.annotation {
                        name = "DiscriminatorColumn"
                        importPath = "jakarta.persistence.DiscriminatorColumn"
                        param("name", "\"${escapeJavaString(inh.discriminatorColumn)}\"")
                        param("discriminatorType", "DiscriminatorType.${inh.discriminatorType}")
                        inh.discriminatorLength?.let { param("length", it.toString()) }
                    }
                }
            }
            if (
                inh.strategy != InheritanceStrategy.TABLE_PER_CLASS &&
                !inh.discriminatorValue.isNullOrBlank()
            ) {
                b.annotation {
                    name = "DiscriminatorValue"
                    importPath = "jakarta.persistence.DiscriminatorValue"
                    value("\"${escapeJavaString(inh.discriminatorValue)}\"")
                }
            }
            if (
                inh.role == InheritanceRole.SUBTYPE &&
                inh.strategy == InheritanceStrategy.JOINED &&
                !inh.primaryKeyJoinColumnName.isNullOrBlank()
            ) {
                b.annotation {
                    name = "PrimaryKeyJoinColumn"
                    importPath = "jakarta.persistence.PrimaryKeyJoinColumn"
                    param("name", "\"${escapeJavaString(inh.primaryKeyJoinColumnName)}\"")
                    inh.primaryKeyJoinReferencedColumnName?.takeIf(String::isNotBlank)?.let {
                        param("referencedColumnName", "\"${escapeJavaString(it)}\"")
                    }
                }
            }
        }

        // @Entity / @MappedSuperclass / @Embeddable
        when (entity.entityType) {
            EntityType.ENTITY -> b.annotation {
                name = "Entity"
                importPath = "jakarta.persistence.Entity"
                param("name", "\"${entity.resolvedEntityName}\"")
            }
            EntityType.MAPPED_SUPERCLASS -> b.annotation {
                name = "MappedSuperclass"
                importPath = "jakarta.persistence.MappedSuperclass"
            }
            EntityType.EMBEDDABLE -> b.annotation {
                name = "Embeddable"
                importPath = "jakarta.persistence.Embeddable"
            }
            else -> {}
        }

        // @Table
        if (entity.entityType == EntityType.ENTITY) {
            b.annotation {
                name = "Table"
                importPath = "jakarta.persistence.Table"
                param("name", "\"${escapeJavaString(entity.resolvedTableName)}\"")
                entity.tableSchema?.takeIf(String::isNotBlank)?.let {
                    param("schema", "\"${escapeJavaString(it)}\"")
                }
                entity.tableCatalog?.takeIf(String::isNotBlank)?.let {
                    param("catalog", "\"${escapeJavaString(it)}\"")
                }
                if (entity.indexes.isNotEmpty() || entity.uniqueConstraints.isNotEmpty()) {
                    val indexAnns = entity.indexes.map { idx ->
                        buildString {
                            append("@Index(name = \"${idx.name}\", columnList = \"${idx.columns.joinToString(", ")}\"")
                            if (idx.unique) append(", unique = true")
                            append(")")
                        }
                    }
                    val ucAnns = entity.uniqueConstraints.map { uc ->
                        "@UniqueConstraint(name = \"${uc.name}\", columnNames = {${uc.columns.joinToString(", ") { "\"$it\"" }}})"
                    }
                    if (indexAnns.isNotEmpty()) {
                        param("indexes", "{${indexAnns.joinToString(", ")}}")
                    }
                    if (ucAnns.isNotEmpty()) {
                        param("uniqueConstraints", "{${ucAnns.joinToString(", ")}}")
                    }
                }
            }
        }

        // @JmixEntity
        b.annotation {
            name = "JmixEntity"
            importPath = "io.jmix.core.metamodel.annotation.JmixEntity"
            if (entity.entityType != EntityType.ENTITY) {
                param("name", "\"${entity.resolvedEntityName}\"")
            }
            if (entity.annotatedPropertiesOnly) {
                param("annotatedPropertiesOnly", "true")
            }
        }

        if (entity.dataStore.isNotBlank() && entity.dataStore != "main") {
            b.annotation {
                name = "Store"
                importPath = "io.jmix.core.metamodel.annotation.Store"
                param("name", "\"${entity.dataStore}\"")
            }
        }

        if (entity.databaseView) {
            b.annotation {
                name = "DbView"
                importPath = "io.jmix.data.DbView"
            }
        }

        if (entity.systemLevel) {
            b.annotation {
                name = "SystemLevel"
                importPath = "io.jmix.core.entity.annotation.SystemLevel"
            }
        }

        entity.comment?.let { comment ->
            b.annotation {
                name = "Comment"
                importPath = "io.jmix.core.metamodel.annotation.Comment"
                value("\"${escapeJavaString(comment)}\"")
            }
        }

        if (entity.entityListeners.isNotEmpty()) {
            entity.entityListeners.filter { '.' in it }.forEach { b.import_(it) }
            b.annotation {
                name = "EntityListeners"
                importPath = "jakarta.persistence.EntityListeners"
                value(
                    entity.entityListeners.joinToString(
                        prefix = "{",
                        postfix = "}",
                    ) { "${it.substringAfterLast('.')}.class" },
                )
            }
        }

        // @DdlGeneration
        val ddl = entity.ddlGeneration
        if (
            ddl.effectiveMode != DdlGenerationMode.CREATE_AND_DROP ||
            ddl.unmappedColumns.isNotEmpty() ||
            ddl.unmappedConstraints.isNotEmpty()
        ) {
            b.annotation {
                name = "DdlGeneration"
                importPath = "io.jmix.data.DdlGeneration"
                value("DbScriptGenerationMode.${ddl.effectiveMode.name}")
                if (ddl.unmappedColumns.isNotEmpty()) {
                    param(
                        "unmappedColumns",
                        ddl.unmappedColumns.joinToString(prefix = "{", postfix = "}") { "\"$it\"" },
                    )
                }
                if (ddl.unmappedConstraints.isNotEmpty()) {
                    param(
                        "unmappedConstraints",
                        ddl.unmappedConstraints.joinToString(prefix = "{", postfix = "}") { "\"$it\"" },
                    )
                }
            }
            b.import_("io.jmix.data.DdlGeneration.DbScriptGenerationMode")
        }

        // Extends
        entity.extendsClass?.let { b.extends_(it) }
        entity.implementsInterfaces.forEach { b.implements_(it) }
        if (entity.embeddableIdentity) {
            b.implements_("java.io.Serializable")
            b.import_("java.util.Objects")
            b.field {
                name = "serialVersionUID"
                type = "long"
                visibility = JavaClassBuilder.Visibility.PRIVATE
                isStatic = true
                isFinal = true
                initializer = "1L"
            }
        }

        // JavaDoc mirrors the metadata comment for source readability.
        entity.comment?.let { b.comment_(it) }

        // Custom annotations
        entity.annotations.forEach { ann ->
            b.annotation {
                name = ann.name
                importPath = ann.importPath
                ann.parameters.forEach { (k, v) -> param(k, v) }
            }
        }

        val inheritanceSubtype = entity.inheritance?.role == InheritanceRole.SUBTYPE

        // ── ID field ──
        if (entity.entityType != EntityType.EMBEDDABLE && !inheritanceSubtype) {
            generateIdField(b, entity)
        }

        // ── Version field (if trait) ──
        if (
            !inheritanceSubtype &&
            entity.traits.any { it == TraitType.HAS_VERSION || it == TraitType.STANDARD_ENTITY }
        ) {
            b.field {
                name = "version"
                type = "Integer"
                visibility = JavaClassBuilder.Visibility.PRIVATE
                annotation {
                    name = "Version"
                    importPath = "jakarta.persistence.Version"
                }
                annotation {
                    name = "Column"
                    importPath = "jakarta.persistence.Column"
                    param("name", "\"VERSION\"")
                    param("nullable", "false")
                }
            }
        }

        // ── Trait fields ──
        if (!inheritanceSubtype) generateTraitFields(b, entity)

        // ── Attribute fields ──
        entity.attributes.forEach { attr ->
            generateAttributeField(b, attr, entity)
        }

        // ── Getters and setters ──
        if (entity.entityType != EntityType.EMBEDDABLE && !inheritanceSubtype) {
            generateIdAccessors(b, entity)
        }
        if (
            !inheritanceSubtype &&
            entity.traits.any { it == TraitType.HAS_VERSION || it == TraitType.STANDARD_ENTITY }
        ) {
            generateGetterSetter(b, "version", "Integer")
        }
        if (!inheritanceSubtype) generateTraitAccessors(b, entity)
        entity.attributes.forEach { attr ->
            if (
                attr.type in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION) &&
                attr.association?.crossDataStore == true
            ) {
                generateGetterSetter(
                    b,
                    attr.relationshipIdAttributeName,
                    idJavaType(attr.association.relatedIdType),
                )
            }
            generateAttributeAccessors(b, attr)
        }
        if (entity.embeddableIdentity) {
            generateEmbeddableIdentityEquality(b, entity)
        }

        // ── Instance name ──
        entity.instanceNamePattern?.let { pattern ->
            b.method {
                name = "getInstanceName"
                returnType = "String"
                visibility = JavaClassBuilder.Visibility.PUBLIC
                annotation {
                    name = "InstanceName"
                    importPath = "io.jmix.core.metamodel.annotation.InstanceName"
                }
                line("return $pattern;")
            }
        }

        // ── Lifecycle callbacks ──
        entity.lifecycleCallbacks.forEach { callback ->
            b.method {
                name = "on${callback.name.lowercase().replaceFirstChar { it.uppercase() }}"
                returnType = "void"
                visibility = JavaClassBuilder.Visibility.PUBLIC
                annotation {
                    name = callback.annotation.removePrefix("@")
                    importPath = "jakarta.persistence.${callback.annotation.removePrefix("@")}"
                }
                line("// Intentionally no generated side effects; add reviewed lifecycle logic explicitly.")
            }
        }

        return b.build()
    }

    private fun generateEmbeddableIdentityEquality(
        builder: JavaClassBuilder,
        entity: EntityModel,
    ) {
        builder.method {
            name = "equals"
            returnType = "boolean"
            isOverride = true
            param("Object", "other")
            line("if (this == other) return true;")
            line("if (other == null || getClass() != other.getClass()) return false;")
            line("${entity.className} that = (${entity.className}) other;")
            line(
                "return " + entity.attributes.joinToString(" && ") {
                    "Objects.equals(${it.name}, that.${it.name})"
                } + ";",
            )
        }
        builder.method {
            name = "hashCode"
            returnType = "int"
            isOverride = true
            line("return Objects.hash(${entity.attributes.joinToString(", ") { it.name }});")
        }
    }

    private fun JavaClassBuilder.FieldBuilder.addJoinColumnAnnotations(
        association: AssociationConfig,
        attribute: AttributeModel,
    ) {
        val columns = association.joinColumns.ifEmpty {
            mutableListOf(
                AssociationJoinColumn(
                    name = association.joinColumnName ?: attribute.resolvedColumnName + "_ID",
                    referencedColumnName = association.relatedIdColumnName,
                    nullable = !attribute.mandatory,
                ),
            )
        }
        if (columns.size == 1) {
            val column = columns.single()
            annotation {
                name = "JoinColumn"
                importPath = "jakarta.persistence.JoinColumn"
                param("name", "\"${escapeJavaString(column.name)}\"")
                if (column.referencedColumnName.isNotBlank()) {
                    param(
                        "referencedColumnName",
                        "\"${escapeJavaString(column.referencedColumnName)}\"",
                    )
                }
                column.nullable?.let { param("nullable", it.toString()) }
                if (!column.insertable) param("insertable", "false")
                if (!column.updatable) param("updatable", "false")
            }
        } else {
            annotation {
                name = "JoinColumns"
                importPath = "jakarta.persistence.JoinColumns"
                value(columns.joinToString(prefix = "{", postfix = "}", transform = ::javaJoinColumn))
            }
        }
    }

    private fun javaJoinColumn(column: AssociationJoinColumn): String = buildString {
        append("@JoinColumn(name = \"")
            .append(escapeJavaString(column.name))
            .append('"')
        if (column.referencedColumnName.isNotBlank()) {
            append(", referencedColumnName = \"")
                .append(escapeJavaString(column.referencedColumnName))
                .append('"')
        }
        column.nullable?.let { append(", nullable = ").append(it) }
        if (!column.insertable) append(", insertable = false")
        if (!column.updatable) append(", updatable = false")
        append(')')
    }

    private fun javaEmbeddedAttributeOverride(
        override: EmbeddedAttributeOverride,
    ): String = buildString {
        append("@AttributeOverride(name = \"")
            .append(escapeJavaString(override.path))
            .append("\", column = @Column(name = \"")
            .append(escapeJavaString(override.columnName))
            .append('"')
        override.nullable?.let { append(", nullable = ").append(it) }
        override.unique?.let { append(", unique = ").append(it) }
        override.length?.let { append(", length = ").append(it) }
        override.precision?.let { append(", precision = ").append(it) }
        override.scale?.let { append(", scale = ").append(it) }
        override.insertable?.let { append(", insertable = ").append(it) }
        override.updatable?.let { append(", updatable = ").append(it) }
        (override.columnDefinition ?: override.sqlType)
            ?.takeIf(String::isNotBlank)
            ?.let {
                append(", columnDefinition = \"")
                    .append(escapeJavaString(it))
                    .append('"')
            }
        append("))")
    }

    private fun javaEmbeddedAssociationOverride(
        override: EmbeddedAssociationOverride,
    ): String = buildString {
        append("@AssociationOverride(name = \"")
            .append(escapeJavaString(override.path))
            .append("\", joinColumns = ")
            .append(
                override.joinColumns.joinToString(
                    prefix = "{",
                    postfix = "}",
                    transform = ::javaJoinColumn,
                ),
            )
            .append(')')
    }

    private val JAVA_IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")

    private fun generateIdField(b: JavaClassBuilder, entity: EntityModel) {
        if (entity.id.type == IdType.EMBEDDED) {
            val embeddedIdClass = requireNotNull(entity.id.embeddedIdClass)
            if ('.' in embeddedIdClass) b.import_(embeddedIdClass)
            b.field {
                name = "id"
                type = embeddedIdClass.substringAfterLast('.')
                visibility = JavaClassBuilder.Visibility.PROTECTED
                annotation {
                    name = "EmbeddedId"
                    importPath = "jakarta.persistence.EmbeddedId"
                }
            }
            return
        }
        if (entity.id.type == IdType.UUID) {
            b.import_("java.util.UUID")
        }

        val idJavaType = when (entity.id.type) {
            IdType.UUID -> "UUID"
            IdType.LONG -> "Long"
            IdType.INTEGER -> "Integer"
            IdType.STRING -> "String"
            IdType.EMBEDDED -> "Object"
        }

        b.field {
            name = "id"
            type = idJavaType
            visibility = JavaClassBuilder.Visibility.PROTECTED
            annotation {
                name = "Id"
                importPath = "jakarta.persistence.Id"
            }
            annotation {
                name = "Column"
                importPath = "jakarta.persistence.Column"
                param("name", "\"${entity.id.columnName}\"")
                param("nullable", "false")
                if (entity.id.type == IdType.STRING && entity.id.length != null) {
                    param("length", entity.id.length.toString())
                }
            }
            when (entity.id.generation) {
                IdGeneration.JMIX_GENERATED -> annotation {
                    name = "JmixGeneratedValue"
                    importPath = "io.jmix.core.entity.annotation.JmixGeneratedValue"
                }
                IdGeneration.IDENTITY -> annotation {
                    name = "GeneratedValue"
                    importPath = "jakarta.persistence.GeneratedValue"
                    param("strategy", "GenerationType.IDENTITY")
                }
                IdGeneration.SEQUENCE -> {
                    annotation {
                        name = "GeneratedValue"
                        importPath = "jakarta.persistence.GeneratedValue"
                        param("strategy", "GenerationType.SEQUENCE")
                        param("generator", "\"${entity.id.sequenceName ?: "${entity.resolvedTableName}_seq"}\"")
                    }
                    annotation {
                        name = "SequenceGenerator"
                        importPath = "jakarta.persistence.SequenceGenerator"
                        param("name", "\"${entity.id.sequenceName ?: "${entity.resolvedTableName}_seq"}\"")
                        param("sequenceName", "\"${entity.id.sequenceName ?: "${entity.resolvedTableName}_seq"}\"")
                        param("allocationSize", "1")
                    }
                }
                IdGeneration.ASSIGNED -> {}
            }
        }
    }

    private fun generateTraitFields(b: JavaClassBuilder, entity: EntityModel) {
        val traits = entity.traits.toSet()
        if (TraitType.UUID_TRAIT in traits && entity.id.type != IdType.UUID) {
            b.field {
                name = "uuid"
                type = "UUID"
                visibility = JavaClassBuilder.Visibility.PROTECTED
                annotation {
                    name = "JmixGeneratedValue"
                    importPath = "io.jmix.core.entity.annotation.JmixGeneratedValue"
                }
                annotation {
                    name = "Column"
                    importPath = "jakarta.persistence.Column"
                    param("name", "\"UUID\"")
                    param("nullable", "false")
                    param("unique", "true")
                }
            }
        }
        if (TraitType.SOFT_DELETE in traits) {
            traitField(
                b,
                "deletedDate",
                "OffsetDateTime",
                "DELETED_DATE",
                "DeletedDate",
                "io.jmix.core.annotation.DeletedDate",
            )
            traitField(
                b,
                "deletedBy",
                "String",
                "DELETED_BY",
                "DeletedBy",
                "io.jmix.core.annotation.DeletedBy",
            )
        }
        if (TraitType.HAS_TENANT_ID in traits) {
            traitField(
                b,
                "sysTenantId",
                "String",
                "SYS_TENANT_ID",
                "TenantId",
                "io.jmix.core.annotation.TenantId",
            )
        }
        val compositeAudit = TraitType.AUDITABLE in traits || TraitType.STANDARD_ENTITY in traits
        if (compositeAudit || TraitType.CREATED_BY in traits) {
            traitField(
                b,
                "createdBy",
                "String",
                "CREATED_BY",
                "CreatedBy",
                "org.springframework.data.annotation.CreatedBy",
            )
        }
        if (compositeAudit || TraitType.CREATED_DATE in traits) {
            traitField(
                b,
                "createdDate",
                "OffsetDateTime",
                "CREATED_DATE",
                "CreatedDate",
                "org.springframework.data.annotation.CreatedDate",
            )
        }
        if (compositeAudit || TraitType.UPDATED_BY in traits) {
            traitField(
                b,
                "lastModifiedBy",
                "String",
                "LAST_MODIFIED_BY",
                "LastModifiedBy",
                "org.springframework.data.annotation.LastModifiedBy",
            )
        }
        if (compositeAudit || TraitType.UPDATED_DATE in traits) {
            traitField(
                b,
                "lastModifiedDate",
                "OffsetDateTime",
                "LAST_MODIFIED_DATE",
                "LastModifiedDate",
                "org.springframework.data.annotation.LastModifiedDate",
            )
        }
    }

    private fun traitField(
        b: JavaClassBuilder,
        fieldName: String,
        fieldType: String,
        columnName: String,
        annotationName: String,
        annotationImport: String,
    ) {
        b.field {
            name = fieldName
            type = fieldType
            visibility = JavaClassBuilder.Visibility.PROTECTED
            annotation {
                name = annotationName
                importPath = annotationImport
            }
            annotation {
                name = "Column"
                importPath = "jakarta.persistence.Column"
                param("name", "\"$columnName\"")
            }
        }
    }

    private fun generateAttributeField(b: JavaClassBuilder, attr: AttributeModel, entity: EntityModel) {
        attr.validations
            .flatMap(ValidationModel::groups)
            .filter { '.' in it }
            .forEach { b.import_(it) }
        if (
            attr.type in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION) &&
            attr.association?.crossDataStore == true
        ) {
            generateCrossDataStoreReference(b, attr)
            return
        }
        b.field {
            name = attr.name
            type = attr.persistentJavaType
            visibility = JavaClassBuilder.Visibility.PROTECTED
            if (attr.transientFlag) isTransient = true
            attr.defaultValue?.takeIf(String::isNotBlank)?.let {
                initializer = it
            }

            attr.requiresImport.forEach { b.import_(it) }
            attr.enumClass?.takeIf { attr.type == AttributeType.ENUM && '.' in it }
                ?.let { b.import_(it) }

            addEntityAttributeMetadata(attr, entity)

            // Association / Composition annotations
            if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                attr.association?.let { assoc ->
                    if ('.' in assoc.relatedEntity) b.import_(assoc.relatedEntity)
                    when (assoc.associationType) {
                        AssociationType.MANY_TO_ONE -> {
                            annotation {
                                name = "ManyToOne"
                                importPath = "jakarta.persistence.ManyToOne"
                                param("fetch", "FetchType.${assoc.fetch.name}")
                                if (attr.mandatory) param("optional", "false")
                                cascadeParameter(assoc.cascade)
                            }
                            addJoinColumnAnnotations(assoc, attr)
                        }
                        AssociationType.ONE_TO_MANY -> {
                            annotation {
                                name = "OneToMany"
                                importPath = "jakarta.persistence.OneToMany"
                                param("mappedBy", "\"${assoc.mappedBy}\"")
                                param("fetch", "FetchType.${assoc.fetch.name}")
                                if (assoc.cascade.isNotEmpty()) {
                                    param("cascade", "{${assoc.cascade.joinToString(", ") { "CascadeType.${it.name}" }}}")
                                }
                                if (assoc.orphanRemoval) param("orphanRemoval", "true")
                            }
                        }
                        AssociationType.MANY_TO_MANY -> {
                            annotation {
                                name = "ManyToMany"
                                importPath = "jakarta.persistence.ManyToMany"
                                param("fetch", "FetchType.${assoc.fetch.name}")
                                if (!assoc.mappedBy.isNullOrBlank()) {
                                    param("mappedBy", "\"${assoc.mappedBy}\"")
                                }
                                if (assoc.cascade.isNotEmpty()) {
                                    param("cascade", "{${assoc.cascade.joinToString(", ") { "CascadeType.${it.name}" }}}")
                                }
                            }
                            assoc.joinTable?.takeIf { assoc.mappedBy.isNullOrBlank() }?.let { jt ->
                                annotation {
                                    name = "JoinTable"
                                    importPath = "jakarta.persistence.JoinTable"
                                    param("name", "\"${escapeJavaString(jt.name)}\"")
                                    jt.schema?.takeIf(String::isNotBlank)?.let {
                                        param("schema", "\"${escapeJavaString(it)}\"")
                                    }
                                    jt.catalog?.takeIf(String::isNotBlank)?.let {
                                        param("catalog", "\"${escapeJavaString(it)}\"")
                                    }
                                    val joinColumns = jt.joinColumns.ifEmpty {
                                        mutableListOf(
                                            AssociationJoinColumn(
                                                jt.joinColumnName,
                                                "",
                                            ),
                                        )
                                    }
                                    val inverseJoinColumns = jt.inverseJoinColumns.ifEmpty {
                                        mutableListOf(
                                            AssociationJoinColumn(
                                                jt.inverseJoinColumnName,
                                                "",
                                            ),
                                        )
                                    }
                                    param(
                                        "joinColumns",
                                        joinColumns.joinToString(prefix = "{", postfix = "}", transform = ::javaJoinColumn),
                                    )
                                    param(
                                        "inverseJoinColumns",
                                        inverseJoinColumns.joinToString(
                                            prefix = "{",
                                            postfix = "}",
                                            transform = ::javaJoinColumn,
                                        ),
                                    )
                                }
                            }
                        }
                        AssociationType.ONE_TO_ONE -> {
                            annotation {
                                name = "OneToOne"
                                importPath = "jakarta.persistence.OneToOne"
                                param("fetch", "FetchType.${assoc.fetch.name}")
                                if (attr.mandatory && assoc.mappedBy == null) param("optional", "false")
                                if (assoc.mappedBy != null) param("mappedBy", "\"${assoc.mappedBy}\"")
                                if (assoc.cascade.isNotEmpty()) {
                                    param("cascade", "{${assoc.cascade.joinToString(", ") { "CascadeType.${it.name}" }}}")
                                }
                                if (assoc.orphanRemoval) param("orphanRemoval", "true")
                            }
                            if (assoc.mappedBy == null) {
                                addJoinColumnAnnotations(assoc, attr)
                            }
                        }
                    }

                    // Composition annotation
                    if (attr.type == AttributeType.COMPOSITION) {
                        annotation {
                            name = "Composition"
                            importPath = "io.jmix.core.metamodel.annotation.Composition"
                        }
                        annotation {
                            name = "OnDelete"
                            importPath = "io.jmix.core.entity.annotation.OnDelete"
                            param("value", "DeletePolicy.CASCADE")
                        }
                        b.import_(
                            "io.jmix.core.metamodel.annotation.Composition",
                            "io.jmix.core.entity.annotation.OnDelete",
                            "io.jmix.core.DeletePolicy"
                        )
                    }
                }
                return@field
            }

            // Embedded
            if (attr.type == AttributeType.EMBEDDED) {
                annotation {
                    name = "Embedded"
                    importPath = "jakarta.persistence.Embedded"
                }
                if (attr.embeddedAttributeOverrides.isNotEmpty()) {
                    b.import_(
                        "jakarta.persistence.AttributeOverride",
                        "jakarta.persistence.Column",
                    )
                    annotation {
                        name = "AttributeOverrides"
                        importPath = "jakarta.persistence.AttributeOverrides"
                        value(
                            attr.embeddedAttributeOverrides.joinToString(
                                prefix = "{",
                                postfix = "}",
                                transform = ::javaEmbeddedAttributeOverride,
                            ),
                        )
                    }
                }
                if (attr.embeddedAssociationOverrides.isNotEmpty()) {
                    b.import_(
                        "jakarta.persistence.AssociationOverride",
                        "jakarta.persistence.JoinColumn",
                    )
                    annotation {
                        name = "AssociationOverrides"
                        importPath = "jakarta.persistence.AssociationOverrides"
                        value(
                            attr.embeddedAssociationOverrides.joinToString(
                                prefix = "{",
                                postfix = "}",
                                transform = ::javaEmbeddedAssociationOverride,
                            ),
                        )
                    }
                }
                attr.embeddedClass?.let { b.import_(it) }
                return@field
            }

            // Enum
            if (attr.type == AttributeType.ENUM) {
                attr.enumClass?.takeIf { '.' in it }?.let { b.import_(it) }
            }

            // Transient
            if (attr.transientFlag) {
                annotation {
                    name = "Transient"
                    importPath = "jakarta.persistence.Transient"
                }
                return@field
            }

            // @Column
            annotation {
                name = "Column"
                importPath = "jakarta.persistence.Column"
                param("name", "\"${attr.resolvedColumnName}\"")
                if (attr.mandatory) param("nullable", "false")
                if (attr.unique) param("unique", "true")
                if (attr.readOnly) {
                    param("insertable", "false")
                    param("updatable", "false")
                }
                if (
                    attr.length != null &&
                    attr.type in setOf(
                        AttributeType.STRING,
                        AttributeType.ENUM,
                        AttributeType.URI,
                        AttributeType.FILE_REF,
                    )
                ) {
                    param("length", attr.length.toString())
                }
                if (attr.precision != null) param("precision", attr.precision.toString())
                if (attr.scale != null) param("scale", attr.scale.toString())
                attr.sqlType?.takeIf(String::isNotBlank)?.let {
                    param("columnDefinition", "\"${escapeJavaString(it)}\"")
                }
            }
        }
    }

    private fun JavaClassBuilder.FieldBuilder.addEntityAttributeMetadata(
        attribute: AttributeModel,
        entity: EntityModel,
    ) {
        if (
            attribute.mandatory &&
            attribute.validations.none { it.type == ValidationType.NOT_NULL }
        ) {
            annotation {
                name = "NotNull"
                importPath = "jakarta.validation.constraints.NotNull"
            }
        }
        if (attribute.name == entity.instanceNameAttribute) {
            annotation {
                name = "InstanceName"
                importPath = "io.jmix.core.metamodel.annotation.InstanceName"
            }
        }
        if (attribute.systemLevel) {
            annotation {
                name = "SystemLevel"
                importPath = "io.jmix.core.entity.annotation.SystemLevel"
            }
        }
        attribute.comment?.let { comment ->
            annotation {
                name = "Comment"
                importPath = "io.jmix.core.metamodel.annotation.Comment"
                value("\"${escapeJavaString(comment)}\"")
            }
        }
        if (attribute.lob) {
            annotation {
                name = "Lob"
                importPath = "jakarta.persistence.Lob"
            }
        }
        if (
            attribute.jmixProperty ||
            entity.annotatedPropertiesOnly ||
            attribute.transientFlag
        ) {
            annotation {
                name = "JmixProperty"
                importPath = "io.jmix.core.metamodel.annotation.JmixProperty"
                if (attribute.mandatory) param("mandatory", "true")
            }
        }
        attribute.dependsOnProperties.takeIf(List<String>::isNotEmpty)?.let { dependencies ->
            annotation {
                name = "DependsOnProperties"
                importPath = "io.jmix.core.metamodel.annotation.DependsOnProperties"
                value(
                    dependencies.joinToString(prefix = "{", postfix = "}") {
                        "\"${escapeJavaString(it)}\""
                    },
                )
            }
        }
        attribute.propertyDatatype?.takeIf(String::isNotBlank)?.let { datatype ->
            annotation {
                name = "PropertyDatatype"
                importPath = "io.jmix.core.metamodel.annotation.PropertyDatatype"
                value("\"${escapeJavaString(datatype)}\"")
            }
        }
        attribute.validations.forEach { validation ->
            annotation {
                name = validation.type.annotation
                importPath = validation.type.importPath
                when (validation.type) {
                    ValidationType.SIZE -> {
                        validation.value?.let { param("min", it) }
                        validation.value2?.let { param("max", it) }
                    }
                    ValidationType.MIN, ValidationType.MAX -> {
                        validation.value?.let { param("value", it) }
                    }
                    ValidationType.DECIMAL_MIN, ValidationType.DECIMAL_MAX -> {
                        validation.value?.let { param("value", "\"${escapeJavaString(it)}\"") }
                    }
                    ValidationType.PATTERN -> {
                        validation.value?.let {
                            param("regexp", "\"${escapeJavaString(it)}\"")
                        }
                    }
                    ValidationType.DIGITS -> {
                        validation.value?.let { param("integer", it) }
                        validation.value2?.let { param("fraction", it) }
                    }
                    else -> Unit
                }
                validation.message?.let {
                    param("message", "\"${escapeJavaString(it)}\"")
                }
                if (validation.groups.isNotEmpty()) {
                    param(
                        "groups",
                        validation.groups.joinToString(prefix = "{", postfix = "}") {
                            "${it.substringAfterLast('.')}.class"
                        },
                    )
                }
            }
        }
        attribute.annotations.forEach { custom ->
            annotation {
                name = custom.name
                importPath = custom.importPath
                custom.parameters.forEach(::param)
            }
        }
    }

    private fun JavaClassBuilder.AnnotationBuilder.cascadeParameter(cascade: List<CascadeType>) {
        if (cascade.isNotEmpty()) {
            param("cascade", "{${cascade.joinToString(", ") { "CascadeType.${it.name}" }}}")
        }
    }

    private fun generateCrossDataStoreReference(
        b: JavaClassBuilder,
        attr: AttributeModel,
    ) {
        val association = requireNotNull(attr.association)
        val idType = idJavaType(association.relatedIdType)
        val idAttribute = attr.relationshipIdAttributeName
        if ('.' in association.relatedEntity) b.import_(association.relatedEntity)
        if (association.relatedIdType == IdType.UUID) b.import_("java.util.UUID")
        b.field {
            name = idAttribute
            type = idType
            visibility = JavaClassBuilder.Visibility.PROTECTED
            annotation {
                name = "SystemLevel"
                importPath = "io.jmix.core.entity.annotation.SystemLevel"
            }
            annotation {
                name = "Column"
                importPath = "jakarta.persistence.Column"
                param("name", "\"${association.joinColumnName ?: attr.resolvedColumnName + "_ID"}\"")
                if (attr.mandatory) param("nullable", "false")
            }
        }
        b.field {
            name = attr.name
            type = association.relatedEntity.substringAfterLast('.')
            visibility = JavaClassBuilder.Visibility.PROTECTED
            annotation {
                name = "Transient"
                importPath = "jakarta.persistence.Transient"
            }
            annotation {
                name = "JmixProperty"
                importPath = "io.jmix.core.metamodel.annotation.JmixProperty"
            }
            annotation {
                name = "DependsOnProperties"
                importPath = "io.jmix.core.metamodel.annotation.DependsOnProperties"
                value("\"$idAttribute\"")
            }
        }
    }

    private fun idJavaType(type: IdType): String = when (type) {
        IdType.UUID -> "UUID"
        IdType.LONG -> "Long"
        IdType.INTEGER -> "Integer"
        IdType.STRING -> "String"
        IdType.EMBEDDED -> "Object"
    }

    private fun generateIdAccessors(b: JavaClassBuilder, entity: EntityModel) {
        val idType = when (entity.id.type) {
            IdType.UUID -> "UUID"
            IdType.LONG -> "Long"
            IdType.INTEGER -> "Integer"
            IdType.STRING -> "String"
            IdType.EMBEDDED -> requireNotNull(entity.id.embeddedIdClass)
                .substringAfterLast('.')
        }
        generateGetterSetter(b, "id", idType)
    }

    private fun generateAttributeAccessors(
        b: JavaClassBuilder,
        attribute: AttributeModel,
    ) {
        if (attribute.type == AttributeType.ENUM) {
            val enumType = requireNotNull(attribute.enumClass).substringAfterLast('.')
            val suffix = attribute.name.replaceFirstChar(Char::uppercase)
            b.method {
                name = "get$suffix"
                returnType = enumType
                visibility = JavaClassBuilder.Visibility.PUBLIC
                line(
                    "return ${attribute.name} == null ? null : " +
                        "$enumType.fromId(${attribute.name});",
                )
            }
            if (!attribute.readOnly) {
                b.method {
                    name = "set$suffix"
                    returnType = "void"
                    visibility = JavaClassBuilder.Visibility.PUBLIC
                    param(enumType, attribute.name)
                    line(
                        "this.${attribute.name} = ${attribute.name} == null ? null : " +
                            "${attribute.name}.getId();",
                    )
                }
            }
            return
        }
        generateGetterSetter(
            b,
            attribute.name,
            attribute.javaType,
            includeSetter = !attribute.readOnly,
        )
    }

    private fun generateTraitAccessors(b: JavaClassBuilder, entity: EntityModel) {
        val traits = entity.traits.toSet()
        if (TraitType.UUID_TRAIT in traits && entity.id.type != IdType.UUID) {
            generateGetterSetter(b, "uuid", "UUID")
        }
        if (TraitType.SOFT_DELETE in traits) {
            generateGetterSetter(b, "deletedDate", "OffsetDateTime")
            generateGetterSetter(b, "deletedBy", "String")
        }
        if (TraitType.HAS_TENANT_ID in traits) {
            generateGetterSetter(b, "sysTenantId", "String")
        }
        val compositeAudit = TraitType.AUDITABLE in traits || TraitType.STANDARD_ENTITY in traits
        if (compositeAudit || TraitType.CREATED_BY in traits) {
            generateGetterSetter(b, "createdBy", "String")
        }
        if (compositeAudit || TraitType.CREATED_DATE in traits) {
            generateGetterSetter(b, "createdDate", "OffsetDateTime")
        }
        if (compositeAudit || TraitType.UPDATED_BY in traits) {
            generateGetterSetter(b, "lastModifiedBy", "String")
        }
        if (compositeAudit || TraitType.UPDATED_DATE in traits) {
            generateGetterSetter(b, "lastModifiedDate", "OffsetDateTime")
        }
    }

    private fun generateGetterSetter(
        b: JavaClassBuilder,
        fieldName: String,
        type: String,
        includeSetter: Boolean = true,
    ) {
        val capName = fieldName.replaceFirstChar { it.uppercase() }
        val getterPrefix = if (type == "Boolean" || type == "boolean") "is" else "get"

        b.method {
            name = "$getterPrefix$capName"
            returnType = type
            visibility = JavaClassBuilder.Visibility.PUBLIC
            line("return $fieldName;")
        }
        if (includeSetter) {
            b.method {
                name = "set$capName"
                returnType = "void"
                visibility = JavaClassBuilder.Visibility.PUBLIC
                param(type, fieldName)
                line("this.$fieldName = $fieldName;")
            }
        }
    }

    // ─── Enum Generation ─────────────────────────────────────────────────────

    private fun generateEnum(entity: EntityModel): String {
        val enumCfg = entity.enumConfig ?: EnumConfig()
        val b = JavaClassBuilder(entity.className)
        b.package_(entity.packageName)
        b.asEnum()

        val idType = if (enumCfg.idType == EnumIdType.INTEGER) "Integer" else "String"
        b.import_("io.jmix.core.metamodel.datatype.EnumClass")
        b.implements_("EnumClass<$idType>")

        b.field {
            name = "id"
            type = idType
            visibility = JavaClassBuilder.Visibility.PRIVATE
            isFinal = true
        }

        // Constructor
        b.method {
            name = entity.className
            returnType = ""
            visibility = JavaClassBuilder.Visibility.PRIVATE
            param(idType, "id")
            line("this.id = id;")
        }

        // getId
        b.method {
            name = "getId"
            returnType = idType
            visibility = JavaClassBuilder.Visibility.PUBLIC
            annotation {
                name = "Override"
            }
            line("return id;")
        }

        // fromId
        b.method {
            name = "fromId"
            returnType = entity.className
            visibility = JavaClassBuilder.Visibility.PUBLIC
            isStatic = true
            annotation {
                name = "Nullable"
                importPath = "org.springframework.lang.Nullable"
            }
            param(idType, "id")
            line("for (${entity.className} at : ${entity.className}.values()) {")
            line("    if (at.getId().equals(id)) {")
            line("        return at;")
            line("    }")
            line("}")
            line("return null;")
        }

        // Enum constants
        enumCfg.values.forEach { v ->
            b.enumConstant {
                name = v.name
                arg(
                    if (enumCfg.idType == EnumIdType.INTEGER) {
                        v.storedValue
                    } else {
                        "\"${escapeJavaString(v.storedValue)}\""
                    },
                )
            }
        }

        return b.build()
    }

    // ─── DTO Generation ──────────────────────────────────────────────────────

    private fun generateDto(entity: EntityModel): String {
        val b = JavaClassBuilder(entity.className)
        b.package_(entity.packageName)
        b.import_(
            "io.jmix.core.metamodel.annotation.JmixEntity",
            "io.jmix.core.metamodel.annotation.InstanceName"
        )

        b.annotation {
            name = "JmixEntity"
            importPath = "io.jmix.core.metamodel.annotation.JmixEntity"
            param("name", "\"${entity.resolvedEntityName}\"")
            if (entity.annotatedPropertiesOnly) {
                param("annotatedPropertiesOnly", "true")
            }
        }

        if (entity.dataStore.isNotBlank() && entity.dataStore != "main") {
            b.annotation {
                name = "Store"
                importPath = "io.jmix.core.metamodel.annotation.Store"
                param("name", "\"${entity.dataStore}\"")
            }
        }

        if (entity.systemLevel) {
            b.annotation {
                name = "SystemLevel"
                importPath = "io.jmix.core.entity.annotation.SystemLevel"
            }
        }

        entity.comment?.let { comment ->
            b.comment_(comment)
            b.annotation {
                name = "Comment"
                importPath = "io.jmix.core.metamodel.annotation.Comment"
                value("\"${escapeJavaString(comment)}\"")
            }
        }

        // ID
        generateDtoIdField(b, entity)

        // Attributes
        entity.attributes.forEach { attr ->
            attr.validations
                .flatMap(ValidationModel::groups)
                .filter { '.' in it }
                .forEach { b.import_(it) }
            b.field {
                name = attr.name
                type = attr.javaType
                visibility = JavaClassBuilder.Visibility.PROTECTED
                attr.defaultValue?.takeIf(String::isNotBlank)?.let {
                    initializer = it
                }
                attr.requiresImport.forEach { b.import_(it) }
                attr.enumClass?.takeIf { '.' in it }?.let { b.import_(it) }
                addEntityAttributeMetadata(attr, entity)
            }
            generateGetterSetter(
                b,
                attr.name,
                attr.javaType,
                includeSetter = !attr.readOnly && entity.dtoConfig?.readOnly != true,
            )
        }

        // Instance name
        entity.instanceNamePattern?.let { pattern ->
            b.method {
                name = "getInstanceName"
                returnType = "String"
                annotation {
                    name = "InstanceName"
                    importPath = "io.jmix.core.metamodel.annotation.InstanceName"
                }
                line("return $pattern;")
            }
        }

        return b.build()
    }

    private fun generateDtoIdField(
        b: JavaClassBuilder,
        entity: EntityModel,
    ) {
        require(entity.id.type != IdType.EMBEDDED) {
            "JVW-ENTITY-DTO-EMBEDDED-ID-UNSUPPORTED: DTO entities need a scalar Jmix identifier."
        }
        val idType = when (entity.id.type) {
            IdType.UUID -> "UUID"
            IdType.LONG -> "Long"
            IdType.INTEGER -> "Integer"
            IdType.STRING -> "String"
            IdType.EMBEDDED -> error("Validated above")
        }
        if (entity.id.type == IdType.UUID) b.import_("java.util.UUID")
        b.field {
            name = "id"
            type = idType
            visibility = JavaClassBuilder.Visibility.PROTECTED
            annotation {
                name = "JmixId"
                importPath = "io.jmix.core.metamodel.annotation.JmixId"
            }
            if (entity.annotatedPropertiesOnly) {
                annotation {
                    name = "JmixProperty"
                    importPath = "io.jmix.core.metamodel.annotation.JmixProperty"
                }
            }
            if (entity.id.generation == IdGeneration.JMIX_GENERATED) {
                annotation {
                    name = "JmixGeneratedValue"
                    importPath = "io.jmix.core.entity.annotation.JmixGeneratedValue"
                }
            }
        }
        generateGetterSetter(
            b,
            "id",
            idType,
            includeSetter = entity.dtoConfig?.readOnly != true,
        )
    }

    private fun escapeJavaString(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
}

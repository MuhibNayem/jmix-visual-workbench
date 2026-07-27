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
        return when (entity.entityType) {
            EntityType.ENUM -> generateEnum(entity)
            EntityType.DTO -> generateDto(entity)
            else -> generateJpaEntity(entity)
        }
    }

    // ─── JPA Entity / MappedSuperclass / Embeddable ──────────────────────────

    private fun generateJpaEntity(entity: EntityModel): String {
        val b = JavaClassBuilder(entity.className)
        b.package_(entity.packageName)

        // Standard imports
        b.import_(
            "jakarta.persistence.*",
            "io.jmix.core.entity.annotation.JmixEntity",
            "io.jmix.core.annotation.DeletedBy",
            "io.jmix.core.annotation.DeletedDate",
            "io.jmix.core.metamodel.annotation.InstanceName",
            "io.jmix.core.metamodel.annotation.JmixGeneratedValue"
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
            b.annotation {
                name = "Inheritance"
                importPath = "jakarta.persistence.Inheritance"
                param("strategy", "InheritanceType.${inh.strategy.name}")
            }
            if (inh.discriminatorColumn != null) {
                b.annotation {
                    name = "DiscriminatorColumn"
                    importPath = "jakarta.persistence.DiscriminatorColumn"
                    param("name", "\"${inh.discriminatorColumn}\"")
                    param("discriminatorType", "DiscriminatorType.${inh.discriminatorType}")
                }
            }
            if (inh.discriminatorValue != null) {
                b.annotation {
                    name = "DiscriminatorValue"
                    importPath = "jakarta.persistence.DiscriminatorValue"
                    value("\"${inh.discriminatorValue}\"")
                }
            }
        }

        // @Entity / @MappedSuperclass / @Embeddable
        when (entity.entityType) {
            EntityType.ENTITY -> b.annotation {
                name = "Entity"
                importPath = "jakarta.persistence.Entity"
                param("name", "\"${entity.className}\"")
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
        if (entity.entityType == EntityType.ENTITY && entity.ddlGeneration.enabled) {
            b.annotation {
                name = "Table"
                importPath = "jakarta.persistence.Table"
                param("name", "\"${entity.resolvedTableName}\"")
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
            importPath = "io.jmix.core.entity.annotation.JmixEntity"
        }

        // @DdlGeneration
        if (!entity.ddlGeneration.enabled) {
            b.annotation {
                name = "DdlGeneration"
                importPath = "io.jmix.core.entity.annotation.DdlGeneration"
                param("value", "false")
            }
        }

        // Extends
        entity.extendsClass?.let { b.extends_(it) }
        entity.implementsInterfaces.forEach { b.implements_(it) }

        // Trait interfaces
        entity.traits.forEach { trait ->
            b.implements_(trait.interfaceName)
        }

        // Comment
        entity.comment?.let { b.comment_(it) }

        // Custom annotations
        entity.annotations.forEach { ann ->
            b.annotation {
                name = ann.name
                importPath = ann.importPath
                ann.parameters.forEach { (k, v) -> param(k, v) }
            }
        }

        // ── ID field ──
        generateIdField(b, entity)

        // ── Version field (if trait) ──
        if (entity.traits.any { it == TraitType.HAS_VERSION || it == TraitType.STANDARD_ENTITY }) {
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
        generateTraitFields(b, entity)

        // ── Attribute fields ──
        entity.attributes.forEach { attr ->
            generateAttributeField(b, attr, entity)
        }

        // ── Getters and setters ──
        generateIdAccessors(b, entity)
        if (entity.traits.any { it == TraitType.HAS_VERSION || it == TraitType.STANDARD_ENTITY }) {
            generateGetterSetter(b, "version", "Integer")
        }
        generateTraitAccessors(b, entity)
        entity.attributes.forEach { attr ->
            generateGetterSetter(b, attr.name, attr.javaType)
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
                line("// TODO: implement ${callback.name}")
            }
        }

        return b.build()
    }

    private fun generateIdField(b: JavaClassBuilder, entity: EntityModel) {
        if (entity.id.type == IdType.EMBEDDED) {
            b.field {
                name = "id"
                type = "EmbeddedId"
                visibility = JavaClassBuilder.Visibility.PROTECTED
                annotation {
                    name = "EmbeddedId"
                    importPath = "jakarta.persistence.EmbeddedId"
                }
            }
            return
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
                    importPath = "io.jmix.core.metamodel.annotation.JmixGeneratedValue"
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
        entity.traits.forEach { trait ->
            when (trait) {
                TraitType.SOFT_DELETE -> {
                    b.field {
                        name = "deletedDate"
                        type = "Date"
                        visibility = JavaClassBuilder.Visibility.PROTECTED
                        annotation {
                            name = "DeletedDate"
                            importPath = "io.jmix.core.annotation.DeletedDate"
                        }
                        annotation {
                            name = "Column"
                            importPath = "jakarta.persistence.Column"
                            param("name", "\"DELETED_DATE\"")
                        }
                    }
                    b.field {
                        name = "deletedBy"
                        type = "String"
                        visibility = JavaClassBuilder.Visibility.PROTECTED
                        annotation {
                            name = "DeletedBy"
                            importPath = "io.jmix.core.annotation.DeletedBy"
                        }
                        annotation {
                            name = "Column"
                            importPath = "jakarta.persistence.Column"
                            param("name", "\"DELETED_BY\"")
                        }
                    }
                }
                TraitType.HAS_TENANT_ID -> {
                    b.field {
                        name = "tenantId"
                        type = "String"
                        visibility = JavaClassBuilder.Visibility.PROTECTED
                        annotation {
                            name = "Column"
                            importPath = "jakarta.persistence.Column"
                            param("name", "\"TENANT_ID\"")
                        }
                    }
                }
                TraitType.CREATED_BY -> {
                    b.field {
                        name = "createdBy"
                        type = "String"
                        visibility = JavaClassBuilder.Visibility.PROTECTED
                        annotation {
                            name = "CreatedBy"
                            importPath = "io.jmix.core.annotation.CreatedBy"
                        }
                        annotation {
                            name = "Column"
                            importPath = "jakarta.persistence.Column"
                            param("name", "\"CREATED_BY\"")
                        }
                    }
                }
                TraitType.CREATED_DATE -> {
                    b.field {
                        name = "createdDate"
                        type = "Date"
                        visibility = JavaClassBuilder.Visibility.PROTECTED
                        annotation {
                            name = "CreatedDate"
                            importPath = "io.jmix.core.annotation.CreatedDate"
                        }
                        annotation {
                            name = "Column"
                            importPath = "jakarta.persistence.Column"
                            param("name", "\"CREATED_DATE\"")
                        }
                    }
                }
                TraitType.UPDATED_BY -> {
                    b.field {
                        name = "updatedBy"
                        type = "String"
                        visibility = JavaClassBuilder.Visibility.PROTECTED
                        annotation {
                            name = "UpdatedBy"
                            importPath = "io.jmix.core.annotation.UpdatedBy"
                        }
                        annotation {
                            name = "Column"
                            importPath = "jakarta.persistence.Column"
                            param("name", "\"UPDATED_BY\"")
                        }
                    }
                }
                TraitType.UPDATED_DATE -> {
                    b.field {
                        name = "updatedDate"
                        type = "Date"
                        visibility = JavaClassBuilder.Visibility.PROTECTED
                        annotation {
                            name = "UpdatedDate"
                            importPath = "io.jmix.core.annotation.UpdatedDate"
                        }
                        annotation {
                            name = "Column"
                            importPath = "jakarta.persistence.Column"
                            param("name", "\"UPDATED_DATE\"")
                        }
                    }
                }
                TraitType.AUDITABLE -> {
                    // Auditable is a composite trait — fields come from CreatedBy/CreatedDate/UpdatedBy/UpdatedDate
                }
                TraitType.STANDARD_ENTITY -> {
                    // StandardEntity includes UUID + Version + Auditable — handled by other trait entries
                }
                else -> {}
            }
        }
    }

    private fun generateAttributeField(b: JavaClassBuilder, attr: AttributeModel, entity: EntityModel) {
        b.field {
            name = attr.name
            type = attr.javaType
            visibility = JavaClassBuilder.Visibility.PROTECTED
            if (attr.transientFlag) isTransient = true

            attr.requiresImport.forEach { b.import_(it) }

            // Association / Composition annotations
            if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                attr.association?.let { assoc ->
                    b.import_(assoc.relatedEntity)
                    when (assoc.associationType) {
                        AssociationType.MANY_TO_ONE -> {
                            annotation {
                                name = "ManyToOne"
                                importPath = "jakarta.persistence.ManyToOne"
                                param("fetch", "FetchType.${assoc.fetch.name}")
                                if (assoc.mappedBy != null) param("optional", "false")
                            }
                            annotation {
                                name = "JoinColumn"
                                importPath = "jakarta.persistence.JoinColumn"
                                param("name", "\"${assoc.joinColumnName ?: attr.resolvedColumnName + "_ID"}\"")
                            }
                        }
                        AssociationType.ONE_TO_MANY -> {
                            annotation {
                                name = "OneToMany"
                                importPath = "jakarta.persistence.OneToMany"
                                param("mappedBy", "\"${assoc.mappedBy ?: "id"}\"")
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
                                if (assoc.cascade.isNotEmpty()) {
                                    param("cascade", "{${assoc.cascade.joinToString(", ") { "CascadeType.${it.name}" }}}")
                                }
                            }
                            assoc.joinTable?.let { jt ->
                                annotation {
                                    name = "JoinTable"
                                    importPath = "jakarta.persistence.JoinTable"
                                    param("name", "\"${jt.name}\"")
                                    param("joinColumns", "@JoinColumn(name = \"${jt.joinColumnName}\")")
                                    param("inverseJoinColumns", "@JoinColumn(name = \"${jt.inverseJoinColumnName}\")")
                                }
                            }
                        }
                        AssociationType.ONE_TO_ONE -> {
                            annotation {
                                name = "OneToOne"
                                importPath = "jakarta.persistence.OneToOne"
                                param("fetch", "FetchType.${assoc.fetch.name}")
                                if (assoc.mappedBy != null) param("mappedBy", "\"${assoc.mappedBy}\"")
                                if (assoc.cascade.isNotEmpty()) {
                                    param("cascade", "{${assoc.cascade.joinToString(", ") { "CascadeType.${it.name}" }}}")
                                }
                                if (assoc.orphanRemoval) param("orphanRemoval", "true")
                            }
                            if (assoc.mappedBy == null) {
                                annotation {
                                    name = "JoinColumn"
                                    importPath = "jakarta.persistence.JoinColumn"
                                    param("name", "\"${assoc.joinColumnName ?: attr.resolvedColumnName + "_ID"}\"")
                                }
                            }
                        }
                    }

                    // Composition annotation
                    if (attr.type == AttributeType.COMPOSITION) {
                        annotation {
                            name = "Composition"
                            importPath = "io.jmix.core.entity.annotation.Composition"
                        }
                        annotation {
                            name = "OnDelete"
                            importPath = "io.jmix.core.entity.annotation.OnDelete"
                            param("value", "DeletePolicy.CASCADE")
                        }
                        b.import_(
                            "io.jmix.core.entity.annotation.Composition",
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
                attr.embeddedClass?.let { b.import_(it) }
                return@field
            }

            // Enum
            if (attr.type == AttributeType.ENUM) {
                annotation {
                    name = "Enumerated"
                    importPath = "jakarta.persistence.Enumerated"
                    param("value", "EnumType.STRING")
                }
                attr.enumClass?.let { b.import_(it) }
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
                if (attr.length != null && attr.type == AttributeType.STRING) {
                    param("length", attr.length.toString())
                }
                if (attr.precision != null) param("precision", attr.precision.toString())
                if (attr.scale != null) param("scale", attr.scale.toString())
            }

            // Validations
            attr.validations.forEach { v ->
                annotation {
                    name = v.type.annotation
                    importPath = v.type.importPath
                    when (v.type) {
                        ValidationType.SIZE -> {
                            v.value?.let { param("min", it) }
                            v.value2?.let { param("max", it) }
                        }
                        ValidationType.MIN, ValidationType.MAX -> {
                            v.value?.let { param("value", it) }
                        }
                        ValidationType.DECIMAL_MIN, ValidationType.DECIMAL_MAX -> {
                            v.value?.let { param("value", "\"$it\"") }
                        }
                        ValidationType.PATTERN -> {
                            v.value?.let { param("regexp", "\"$it\"") }
                        }
                        ValidationType.DIGITS -> {
                            v.value?.let { param("integer", it) }
                            v.value2?.let { param("fraction", it) }
                        }
                        else -> {}
                    }
                    v.message?.let { param("message", "\"$it\"") }
                }
            }

            // Custom annotations
            attr.annotations.forEach { ann ->
                annotation {
                    name = ann.name
                    importPath = ann.importPath
                    ann.parameters.forEach { (k, v) -> param(k, v) }
                }
            }
        }
    }

    private fun generateIdAccessors(b: JavaClassBuilder, entity: EntityModel) {
        val idType = when (entity.id.type) {
            IdType.UUID -> "UUID"
            IdType.LONG -> "Long"
            IdType.INTEGER -> "Integer"
            IdType.STRING -> "String"
            IdType.EMBEDDED -> "Object"
        }
        generateGetterSetter(b, "id", idType)
    }

    private fun generateTraitAccessors(b: JavaClassBuilder, entity: EntityModel) {
        entity.traits.forEach { trait ->
            when (trait) {
                TraitType.SOFT_DELETE -> {
                    generateGetterSetter(b, "deletedDate", "Date")
                    generateGetterSetter(b, "deletedBy", "String")
                }
                TraitType.HAS_TENANT_ID -> generateGetterSetter(b, "tenantId", "String")
                TraitType.CREATED_BY -> generateGetterSetter(b, "createdBy", "String")
                TraitType.CREATED_DATE -> generateGetterSetter(b, "createdDate", "Date")
                TraitType.UPDATED_BY -> generateGetterSetter(b, "updatedBy", "String")
                TraitType.UPDATED_DATE -> generateGetterSetter(b, "updatedDate", "Date")
                else -> {}
            }
        }
    }

    private fun generateGetterSetter(b: JavaClassBuilder, fieldName: String, type: String) {
        val capName = fieldName.replaceFirstChar { it.uppercase() }
        val getterPrefix = if (type == "Boolean" || type == "boolean") "is" else "get"

        b.method {
            name = "$getterPrefix$capName"
            returnType = type
            visibility = JavaClassBuilder.Visibility.PUBLIC
            line("return $fieldName;")
        }
        b.method {
            name = "set$capName"
            returnType = "void"
            visibility = JavaClassBuilder.Visibility.PUBLIC
            param(type, fieldName)
            line("this.$fieldName = $fieldName;")
        }
    }

    // ─── Enum Generation ─────────────────────────────────────────────────────

    private fun generateEnum(entity: EntityModel): String {
        val enumCfg = entity.enumConfig ?: EnumConfig()
        val b = JavaClassBuilder(entity.className)
        b.package_(entity.packageName)
        b.asEnum()
        b.implements_("io.jmix.core.metamodel.datatype.EnumClass")

        val idType = if (enumCfg.idType == EnumIdType.INTEGER) "Integer" else "String"
        b.import_("io.jmix.core.metamodel.datatype.EnumClass")

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
                arg(if (enumCfg.idType == EnumIdType.INTEGER) v.storedValue else "\"${v.storedValue}\"")
            }
        }

        return b.build()
    }

    // ─── DTO Generation ──────────────────────────────────────────────────────

    private fun generateDto(entity: EntityModel): String {
        val b = JavaClassBuilder(entity.className)
        b.package_(entity.packageName)
        b.import_(
            "io.jmix.core.entity.annotation.JmixEntity",
            "io.jmix.core.metamodel.annotation.InstanceName"
        )

        b.annotation {
            name = "JmixEntity"
            importPath = "io.jmix.core.entity.annotation.JmixEntity"
        }

        entity.dtoConfig?.let { dto ->
            if (dto.readOnly) {
                b.annotation {
                    name = "JmixEntity"
                    importPath = "io.jmix.core.entity.annotation.JmixEntity"
                }
            }
        }

        entity.comment?.let { b.comment_(it) }

        // ID
        generateIdField(b, entity)
        generateIdAccessors(b, entity)

        // Attributes
        entity.attributes.forEach { attr ->
            b.field {
                name = attr.name
                type = attr.javaType
                visibility = JavaClassBuilder.Visibility.PROTECTED
                attr.requiresImport.forEach { b.import_(it) }
            }
            generateGetterSetter(b, attr.name, attr.javaType)
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
}

package org.jmixworkbench.model

import com.google.gson.annotations.SerializedName

// ─── Entity ──────────────────────────────────────────────────────────────────

data class EntityModel(
    val className: String,
    val packageName: String,
    val dataStore: String = "main",
    val generationTarget: EntityGenerationTarget? = null,
    var entityName: String = "",
    var tableName: String = "",
    val entityType: EntityType = EntityType.ENTITY,
    val id: IdConfig = IdConfig(),
    val inheritance: InheritanceConfig? = null,
    val traits: MutableList<TraitType> = mutableListOf(),
    val attributes: MutableList<AttributeModel> = mutableListOf(),
    val indexes: MutableList<IndexModel> = mutableListOf(),
    val uniqueConstraints: MutableList<UniqueConstraintModel> = mutableListOf(),
    val instanceNamePattern: String? = null,
    val instanceNameAttribute: String? = null,
    val comment: String? = null,
    val systemLevel: Boolean = false,
    val annotatedPropertiesOnly: Boolean = false,
    val databaseView: Boolean = false,
    val ddlGeneration: DdlGenerationConfig = DdlGenerationConfig(),
    val softDelete: SoftDeleteConfig? = null,
    val multitenancy: MultitenancyConfig? = null,
    val dataRepository: DataRepositoryConfig? = null,
    val lifecycleCallbacks: MutableList<LifecycleCallback> = mutableListOf(),
    val entityListeners: MutableList<String> = mutableListOf(),
    val enumConfig: EnumConfig? = null,
    val dtoConfig: DtoConfig? = null,
    val extendsClass: String? = null,
    val implementsInterfaces: MutableList<String> = mutableListOf(),
    val annotations: MutableList<CustomAnnotation> = mutableListOf()
) {
    val fullName: String get() = "$packageName.$className"
    val resolvedEntityName: String
        get() = entityName.ifEmpty { className }
    val resolvedTableName: String
        get() = tableName.ifEmpty {
            className.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
        }

    fun withProjectNaming(projectId: String?): EntityModel {
        val prefix = projectId?.trim().orEmpty()
        if (prefix.isEmpty()) return this
        val defaultTable = className
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .uppercase()
        return copy(
            entityName = entityName.ifEmpty { "${prefix}_$className" },
            tableName = tableName.ifEmpty { "${prefix.uppercase()}_$defaultTable" },
        )
    }
}

data class EntityGenerationTarget(
    val moduleId: String? = null,
    val storeId: String? = null,
)

enum class EntityType {
    @SerializedName("entity") ENTITY,
    @SerializedName("mappedSuperclass") MAPPED_SUPERCLASS,
    @SerializedName("embeddable") EMBEDDABLE,
    @SerializedName("dto") DTO,
    @SerializedName("enum") ENUM
}

// ─── ID Configuration ────────────────────────────────────────────────────────

data class IdConfig(
    val type: IdType = IdType.UUID,
    val generation: IdGeneration = IdGeneration.JMIX_GENERATED,
    val columnName: String = "ID",
    val length: Int? = null,
    val sequenceName: String? = null,
    val embeddedIdClass: String? = null,
    val embeddedAttributes: MutableList<AttributeModel> = mutableListOf()
)

enum class IdType {
    @SerializedName("uuid") UUID,
    @SerializedName("long") LONG,
    @SerializedName("integer") INTEGER,
    @SerializedName("string") STRING,
    @SerializedName("embedded") EMBEDDED
}

enum class IdGeneration {
    @SerializedName("jmixGenerated") JMIX_GENERATED,
    @SerializedName("identity") IDENTITY,
    @SerializedName("sequence") SEQUENCE,
    @SerializedName("assigned") ASSIGNED
}

// ─── Inheritance ─────────────────────────────────────────────────────────────

data class InheritanceConfig(
    val strategy: InheritanceStrategy = InheritanceStrategy.SINGLE_TABLE,
    val discriminatorColumn: String? = null,
    val discriminatorType: String = "STRING",
    val discriminatorValue: String? = null
)

enum class InheritanceStrategy {
    @SerializedName("singleTable") SINGLE_TABLE,
    @SerializedName("joined") JOINED,
    @SerializedName("tablePerClass") TABLE_PER_CLASS
}

// ─── Traits ──────────────────────────────────────────────────────────────────

enum class TraitType(val interfaceName: String, val fields: List<String>) {
    @SerializedName("uuid") UUID_TRAIT("UuidEntity", listOf("id")),
    @SerializedName("softDelete") SOFT_DELETE("SoftDeleteEntity", listOf("deletedDate", "deletedBy")),
    @SerializedName("hasTenantId") HAS_TENANT_ID("HasTenantId", listOf("sysTenantId")),
    @SerializedName("hasVersion") HAS_VERSION("HasVersion", listOf("version")),
    @SerializedName("createdBy") CREATED_BY("CreatedBy", listOf("createdBy")),
    @SerializedName("createdDate") CREATED_DATE("CreatedDate", listOf("createdDate")),
    @SerializedName("updatedBy") UPDATED_BY("LastModifiedBy", listOf("lastModifiedBy")),
    @SerializedName("updatedDate") UPDATED_DATE("LastModifiedDate", listOf("lastModifiedDate")),
    @SerializedName("auditable") AUDITABLE(
        "Auditable",
        listOf("createdBy", "createdDate", "lastModifiedBy", "lastModifiedDate"),
    ),
    @SerializedName("standardEntity") STANDARD_ENTITY(
        "StandardEntity",
        listOf("id", "version", "createdBy", "createdDate", "lastModifiedBy", "lastModifiedDate"),
    )
}

// ─── Attributes ──────────────────────────────────────────────────────────────

data class AttributeModel(
    val name: String,
    val type: AttributeType = AttributeType.STRING,
    val columnName: String? = null,
    val mandatory: Boolean = false,
    val unique: Boolean = false,
    val length: Int? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    val comment: String? = null,
    val localizedCaption: String? = null,
    val defaultValue: String? = null,
    val transientFlag: Boolean = false,
    val systemLevel: Boolean = false,
    val readOnly: Boolean = false,
    val jmixProperty: Boolean = false,
    val dependsOnProperties: MutableList<String> = mutableListOf(),
    val propertyDatatype: String? = null,
    val lob: Boolean = false,
    val javaTypeName: String? = null,
    val sqlType: String? = null,
    // Association / Composition
    val association: AssociationConfig? = null,
    // Embedded
    val embeddedClass: String? = null,
    // Enum
    val enumClass: String? = null,
    val enumIdType: EnumIdType = EnumIdType.STRING,
    // Validation
    val validations: MutableList<ValidationModel> = mutableListOf(),
    // Custom annotations
    val annotations: MutableList<CustomAnnotation> = mutableListOf(),
    // Fetch plan inclusion
    val inBaseFetchPlan: Boolean = true
) {
    val resolvedColumnName: String
        get() = columnName ?: name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

    val javaType: String
        get() = when (type) {
            AttributeType.STRING -> "String"
            AttributeType.CHARACTER -> "Character"
            AttributeType.INTEGER -> "Integer"
            AttributeType.LONG -> "Long"
            AttributeType.DOUBLE -> "Double"
            AttributeType.BIG_DECIMAL -> "BigDecimal"
            AttributeType.BOOLEAN -> "Boolean"
            AttributeType.DATE -> "Date"
            AttributeType.LOCAL_DATE -> "LocalDate"
            AttributeType.LOCAL_DATE_TIME -> "LocalDateTime"
            AttributeType.LOCAL_TIME -> "LocalTime"
            AttributeType.OFFSET_TIME -> "OffsetTime"
            AttributeType.OFFSET_DATE_TIME -> "OffsetDateTime"
            AttributeType.SQL_DATE -> "java.sql.Date"
            AttributeType.SQL_TIME -> "java.sql.Time"
            AttributeType.UUID -> "UUID"
            AttributeType.URI -> "URI"
            AttributeType.BYTE_ARRAY -> "byte[]"
            AttributeType.FILE_REF -> "FileRef"
            AttributeType.ENUM -> enumClass ?: "Object"
            AttributeType.ASSOCIATION, AttributeType.COMPOSITION -> {
                val related = association?.relatedEntity?.substringAfterLast('.') ?: "Object"
                when (association?.associationType) {
                    AssociationType.ONE_TO_MANY, AssociationType.MANY_TO_MANY ->
                        "${association.collectionType.javaType}<$related>"
                    else -> related
                }
            }
            AttributeType.EMBEDDED -> embeddedClass ?: "Object"
            AttributeType.CUSTOM -> javaTypeName
                ?.substringAfterLast('.')
                ?.takeIf(String::isNotBlank)
                ?: "Object"
        }

    val requiresImport: List<String>
        get() = when (type) {
            AttributeType.BIG_DECIMAL -> listOf("java.math.BigDecimal")
            AttributeType.DATE -> listOf("java.util.Date")
            AttributeType.LOCAL_DATE -> listOf("java.time.LocalDate")
            AttributeType.LOCAL_DATE_TIME -> listOf("java.time.LocalDateTime")
            AttributeType.LOCAL_TIME -> listOf("java.time.LocalTime")
            AttributeType.OFFSET_TIME -> listOf("java.time.OffsetTime")
            AttributeType.OFFSET_DATE_TIME -> listOf("java.time.OffsetDateTime")
            AttributeType.UUID -> listOf("java.util.UUID")
            AttributeType.URI -> listOf("java.net.URI")
            AttributeType.FILE_REF -> listOf("io.jmix.core.FileRef")
            AttributeType.CUSTOM -> javaTypeName
                ?.takeIf { '.' in it }
                ?.let { listOf(it) }
                .orEmpty()
            AttributeType.ASSOCIATION, AttributeType.COMPOSITION -> when (association?.associationType) {
                AssociationType.ONE_TO_MANY, AssociationType.MANY_TO_MANY ->
                    listOf(association.collectionType.importPath)
                else -> emptyList()
            }
            else -> emptyList()
        }

    val persistentJavaType: String
        get() = if (type == AttributeType.ENUM) {
            if (enumIdType == EnumIdType.INTEGER) "Integer" else "String"
        } else {
            javaType
        }

    val relationshipIdAttributeName: String
        get() = association?.localIdAttributeName?.takeIf(String::isNotBlank) ?: "${name}Id"
}

enum class AttributeType {
    @SerializedName("string") STRING,
    @SerializedName("character") CHARACTER,
    @SerializedName("integer") INTEGER,
    @SerializedName("long") LONG,
    @SerializedName("double") DOUBLE,
    @SerializedName("bigDecimal") BIG_DECIMAL,
    @SerializedName("boolean") BOOLEAN,
    @SerializedName("date") DATE,
    @SerializedName("localDate") LOCAL_DATE,
    @SerializedName("localDateTime") LOCAL_DATE_TIME,
    @SerializedName("localTime") LOCAL_TIME,
    @SerializedName("offsetTime") OFFSET_TIME,
    @SerializedName("offsetDateTime") OFFSET_DATE_TIME,
    @SerializedName("sqlDate") SQL_DATE,
    @SerializedName("sqlTime") SQL_TIME,
    @SerializedName("uuid") UUID,
    @SerializedName("uri") URI,
    @SerializedName("byteArray") BYTE_ARRAY,
    @SerializedName("fileRef") FILE_REF,
    @SerializedName("enum") ENUM,
    @SerializedName("association") ASSOCIATION,
    @SerializedName("composition") COMPOSITION,
    @SerializedName("embedded") EMBEDDED,
    @SerializedName("custom") CUSTOM
}

data class AssociationConfig(
    val associationType: AssociationType,
    val relatedEntity: String,
    val relatedTableName: String? = null,
    val relatedIdColumnName: String = "ID",
    val relatedIdType: IdType = IdType.UUID,
    val localIdAttributeName: String? = null,
    val mappedBy: String? = null,
    val joinColumnName: String? = null,
    val joinTable: JoinTableConfig? = null,
    val cascade: MutableList<CascadeType> = mutableListOf(),
    val fetch: FetchType = FetchType.LAZY,
    val collectionType: AssociationCollectionType = AssociationCollectionType.LIST,
    val crossDataStore: Boolean = false,
    val orphanRemoval: Boolean = false,
    val onDelete: String? = null
)

enum class AssociationCollectionType(val javaType: String, val importPath: String) {
    @SerializedName("list") LIST("List", "java.util.List"),
    @SerializedName("set") SET("Set", "java.util.Set"),
}

enum class AssociationType {
    @SerializedName("manyToOne") MANY_TO_ONE,
    @SerializedName("oneToMany") ONE_TO_MANY,
    @SerializedName("manyToMany") MANY_TO_MANY,
    @SerializedName("oneToOne") ONE_TO_ONE
}

data class JoinTableConfig(
    val name: String,
    val joinColumnName: String,
    val inverseJoinColumnName: String
)

enum class CascadeType {
    @SerializedName("all") ALL,
    @SerializedName("persist") PERSIST,
    @SerializedName("merge") MERGE,
    @SerializedName("remove") REMOVE,
    @SerializedName("refresh") REFRESH,
    @SerializedName("detach") DETACH
}

enum class FetchType {
    @SerializedName("lazy") LAZY,
    @SerializedName("eager") EAGER
}

// ─── Validation ──────────────────────────────────────────────────────────────

data class ValidationModel(
    val type: ValidationType,
    val value: String? = null,
    val value2: String? = null,
    val message: String? = null,
    val groups: MutableList<String> = mutableListOf()
)

enum class ValidationType(val annotation: String, val importPath: String) {
    @SerializedName("notNull") NOT_NULL("NotNull", "jakarta.validation.constraints.NotNull"),
    @SerializedName("notEmpty") NOT_EMPTY("NotEmpty", "jakarta.validation.constraints.NotEmpty"),
    @SerializedName("notBlank") NOT_BLANK("NotBlank", "jakarta.validation.constraints.NotBlank"),
    @SerializedName("size") SIZE("Size", "jakarta.validation.constraints.Size"),
    @SerializedName("min") MIN("Min", "jakarta.validation.constraints.Min"),
    @SerializedName("max") MAX("Max", "jakarta.validation.constraints.Max"),
    @SerializedName("decimalMin") DECIMAL_MIN("DecimalMin", "jakarta.validation.constraints.DecimalMin"),
    @SerializedName("decimalMax") DECIMAL_MAX("DecimalMax", "jakarta.validation.constraints.DecimalMax"),
    @SerializedName("pattern") PATTERN("Pattern", "jakarta.validation.constraints.Pattern"),
    @SerializedName("email") EMAIL("Email", "jakarta.validation.constraints.Email"),
    @SerializedName("past") PAST("Past", "jakarta.validation.constraints.Past"),
    @SerializedName("future") FUTURE("Future", "jakarta.validation.constraints.Future"),
    @SerializedName("positive") POSITIVE("Positive", "jakarta.validation.constraints.Positive"),
    @SerializedName("negative") NEGATIVE("Negative", "jakarta.validation.constraints.Negative"),
    @SerializedName("digits") DIGITS("Digits", "jakarta.validation.constraints.Digits"),
    @SerializedName("assertTrue") ASSERT_TRUE("AssertTrue", "jakarta.validation.constraints.AssertTrue")
}

// ─── Indexes & Constraints ───────────────────────────────────────────────────

data class IndexModel(
    val name: String,
    val columns: List<String>,
    val unique: Boolean = false,
    val tablespace: String? = null
)

data class UniqueConstraintModel(
    val name: String,
    val columns: List<String>
)

// ─── DDL Generation ──────────────────────────────────────────────────────────

data class DdlGenerationConfig(
    val enabled: Boolean = true,
    val mode: DdlGenerationMode = DdlGenerationMode.CREATE_AND_DROP,
    val unmappedColumns: MutableList<String> = mutableListOf(),
    val unmappedConstraints: MutableList<String> = mutableListOf(),
    val generateCreateTable: Boolean = true,
    val generateAlterTable: Boolean = true,
    val generateDropTable: Boolean = false,
    val generateCreateIndex: Boolean = true
) {
    val effectiveMode: DdlGenerationMode
        get() = if (enabled) mode else DdlGenerationMode.DISABLED
}

enum class DdlGenerationMode {
    @SerializedName("createAndDrop") CREATE_AND_DROP,
    @SerializedName("createOnly") CREATE_ONLY,
    @SerializedName("disabled") DISABLED,
}

// ─── Soft Delete ─────────────────────────────────────────────────────────────

data class SoftDeleteConfig(
    val enabled: Boolean = true,
    val deletedDateColumn: String = "DELETED_DATE",
    val deletedByColumn: String = "DELETED_BY"
)

// ─── Multitenancy ────────────────────────────────────────────────────────────

data class MultitenancyConfig(
    val enabled: Boolean = true,
    val tenantIdColumn: String = "TENANT_ID",
    val tenantIdType: String = "String"
)

// ─── Data Repository ─────────────────────────────────────────────────────────

data class DataRepositoryConfig(
    val enabled: Boolean = true,
    val interfaceName: String? = null,
    val methods: MutableList<RepositoryMethod> = mutableListOf()
)

data class RepositoryMethod(
    val name: String,
    val returnType: String,
    val parameters: MutableList<MethodParameter> = mutableListOf(),
    val query: String? = null,
    val queryType: QueryType = QueryType.JPQL
)

data class MethodParameter(
    val name: String,
    val type: String
)

enum class QueryType {
    @SerializedName("jpql") JPQL,
    @SerializedName("native") NATIVE,
    @SerializedName("derived") DERIVED
}

// ─── Lifecycle ───────────────────────────────────────────────────────────────

enum class LifecycleCallback(val annotation: String) {
    @SerializedName("prePersist") PRE_PERSIST("@PrePersist"),
    @SerializedName("postPersist") POST_PERSIST("@PostPersist"),
    @SerializedName("preUpdate") PRE_UPDATE("@PreUpdate"),
    @SerializedName("postUpdate") POST_UPDATE("@PostUpdate"),
    @SerializedName("preRemove") PRE_REMOVE("@PreRemove"),
    @SerializedName("postRemove") POST_REMOVE("@PostRemove"),
    @SerializedName("postLoad") POST_LOAD("@PostLoad")
}

// ─── Enum Config ─────────────────────────────────────────────────────────────

data class EnumConfig(
    val idType: EnumIdType = EnumIdType.STRING,
    val values: MutableList<EnumValueModel> = mutableListOf()
)

enum class EnumIdType {
    @SerializedName("string") STRING,
    @SerializedName("integer") INTEGER
}

data class EnumValueModel(
    val name: String,
    val storedValue: String,
    val caption: String? = null,
    val localizedCaptions: MutableMap<String, String> = mutableMapOf()
)

// ─── DTO Config ──────────────────────────────────────────────────────────────

data class DtoConfig(
    val readOnly: Boolean = true,
    val query: String? = null,
    val store: String? = null
)

// ─── Custom Annotation ───────────────────────────────────────────────────────

data class CustomAnnotation(
    val name: String,
    val importPath: String? = null,
    val parameters: MutableMap<String, String> = mutableMapOf()
)

package org.jmixworkbench.services

import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.model.AssociationCollectionType
import org.jmixworkbench.model.AssociationConfig
import org.jmixworkbench.model.AssociationJoinColumn
import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.AttributeModel
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.DdlGenerationConfig
import org.jmixworkbench.model.DdlGenerationMode
import org.jmixworkbench.model.EntityGenerationTarget
import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EntityType
import org.jmixworkbench.model.FetchType
import org.jmixworkbench.model.IdConfig
import org.jmixworkbench.model.IdGeneration
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.IndexModel
import org.jmixworkbench.model.JoinTableConfig
import org.jmixworkbench.model.UniqueConstraintModel
import java.util.Locale

internal object DatabaseEntityImportPlanner {
    fun plan(
        request: DatabaseEntityImportRequest,
        store: SchemaDataStoreSnapshot,
        database: DatabaseProductSnapshot,
        importedTables: List<DatabaseImportedTable>,
    ): DatabaseEntityImportPlanResponse {
        val tablesByKey = importedTables.associateBy { DatabaseObjectKey.of(it.table) }
        val generatedTables = importedTables.filter { it.existingEntity == null }
        val classNames = assignClassNames(
            generatedTables.map { it.table },
            request.classNameOverrides,
        )
        val foreignKeys = importedTables.associate { imported ->
            DatabaseObjectKey.of(imported.table) to groupForeignKeys(imported.table.foreignKeys)
        }
        val joinTables = generatedTables.mapNotNull { imported ->
            detectJoinTable(imported.table, foreignKeys.getValue(DatabaseObjectKey.of(imported.table)))
                ?.let { DatabaseObjectKey.of(imported.table) to it }
        }.toMap()
        val tablePlans = mutableListOf<DatabaseEntityImportTablePlan>()
        val entityModels = linkedMapOf<String, EntityModel>()
        val generatedEntityByTable = generatedTables
            .filterNot { DatabaseObjectKey.of(it.table) in joinTables }
            .associate { imported ->
                val key = DatabaseObjectKey.of(imported.table)
                key to "${request.packageName}.${classNames.getValue(key)}"
            }
        val existingEntityByTable = importedTables
            .mapNotNull { imported ->
                imported.existingEntity?.let { DatabaseObjectKey.of(imported.table) to it }
            }
            .toMap()

        generatedTables
            .filterNot { DatabaseObjectKey.of(it.table) in joinTables }
            .forEach { imported ->
                val key = DatabaseObjectKey.of(imported.table)
                val table = imported.table
                val issues = mutableListOf<WorkspaceChangeIssue>()
                val groupedForeignKeys = foreignKeys.getValue(key)
                if (groupedForeignKeys.sumOf { it.localColumns.size } != table.foreignKeys.size) {
                    issues += WorkspaceChangeIssue(
                        "JVW-DB-IMPORT-FK-METADATA-AMBIGUOUS",
                        "${table.name} contains unnamed foreign-key rows that cannot be grouped unambiguously. " +
                            "Name the database constraints before generating entity relationships.",
                    )
                }
                val identifierColumns = identifierColumns(request, table)
                if (identifierColumns.isEmpty()) {
                    issues += WorkspaceChangeIssue(
                        "JVW-DB-IMPORT-IDENTIFIER-MISSING",
                        if (table.type.equals("VIEW", ignoreCase = true)) {
                            "${table.name} is a view. Choose stable unique identifier columns before generation."
                        } else {
                            "${table.name} has no primary key. Add a database primary key before mapping this table."
                        },
                    )
                }
                val identifierSet = identifierColumns.map { it.uppercase(Locale.ROOT) }.toSet()
                val columnsByName = table.columns.associateBy { it.name.uppercase(Locale.ROOT) }
                val missingIdentifierColumns = identifierSet - columnsByName.keys
                if (missingIdentifierColumns.isNotEmpty()) {
                    issues += WorkspaceChangeIssue(
                        "JVW-DB-IMPORT-IDENTIFIER-COLUMN-MISSING",
                        "${table.name} identifier columns are missing: ${missingIdentifierColumns.sorted().joinToString()}.",
                    )
                }
                val idMapping = identifierMapping(
                    table = table,
                    identifierColumns = identifierColumns,
                    className = classNames.getValue(key),
                    packageName = request.packageName,
                    sourceLanguage = request.sourceLanguage,
                    generationTarget = EntityGenerationTarget(request.moduleId, request.storeId),
                    issues = issues,
                )
                val consumedColumns = identifierSet.toMutableSet()
                val attributes = mutableListOf<AttributeModel>()
                groupedForeignKeys.forEach { foreignKey ->
                    val targetKey = DatabaseObjectKey(
                        foreignKey.referencedCatalog,
                        foreignKey.referencedSchema,
                        foreignKey.referencedTableName,
                    )
                    val targetEntityName = generatedEntityByTable[targetKey]
                        ?: existingEntityByTable[targetKey]?.qualifiedName
                    val targetTable = tablesByKey[targetKey]?.table
                    val targetId = targetTable?.let {
                        identifierMapping(
                            table = it,
                            identifierColumns = identifierColumns(request, it),
                            className = classNames[targetKey] ?: it.name.toPascalIdentifier(),
                            packageName = request.packageName,
                            sourceLanguage = request.sourceLanguage,
                            generationTarget = EntityGenerationTarget(request.moduleId, request.storeId),
                            issues = mutableListOf(),
                        )
                    }
                    val existingTarget = existingEntityByTable[targetKey]
                    if (targetEntityName == null) {
                        issues += WorkspaceChangeIssue(
                            "JVW-DB-IMPORT-FK-TARGET-MISSING",
                            "${table.name}.${foreignKey.localColumns.joinToString()} references " +
                                "${foreignKey.referencedTableName}, but no mapped or selected target entity is available.",
                        )
                        return@forEach
                    }
                    val localColumns = foreignKey.localColumns.mapNotNull { columnsByName[it.uppercase(Locale.ROOT)] }
                    if (localColumns.size != foreignKey.localColumns.size) {
                        issues += WorkspaceChangeIssue(
                            "JVW-DB-IMPORT-FK-COLUMN-MISSING",
                            "${table.name} foreign key ${foreignKey.label} has incomplete local column metadata.",
                        )
                        return@forEach
                    }
                    val targetIdType = existingTarget?.idType ?: targetId?.id?.type ?: IdType.UUID
                    val targetIdColumn = existingTarget?.idColumnName
                        ?: targetId?.id?.columnName
                        ?: foreignKey.referencedColumns.firstOrNull()
                        ?: "ID"
                    val localSet = foreignKey.localColumns.map { it.uppercase(Locale.ROOT) }.toSet()
                    val oneToOne = table.indexes.any {
                        it.unique && it.columns.map { column -> column.uppercaseRoot() }.toSet() == localSet
                    }
                    val baseName = foreignKey.attributeName(
                        targetEntityName.substringAfterLast('.'),
                    )
                    val attributeName = uniqueAttributeName(
                        baseName,
                        attributes.mapTo(linkedSetOf(), AttributeModel::name),
                    )
                    attributes += AttributeModel(
                        name = attributeName,
                        type = AttributeType.ASSOCIATION,
                        mandatory = localColumns.all { !it.nullable },
                        comment = localColumns.mapNotNull(DatabaseColumnSnapshot::remarks)
                            .distinct().joinToString("; ").takeIf(String::isNotBlank),
                        association = AssociationConfig(
                            associationType = if (oneToOne) {
                                AssociationType.ONE_TO_ONE
                            } else {
                                AssociationType.MANY_TO_ONE
                            },
                            relatedEntity = targetEntityName,
                            relatedTableName = foreignKey.referencedTableName,
                            relatedIdColumnName = targetIdColumn,
                            relatedIdType = targetIdType,
                            joinColumnName = foreignKey.localColumns.singleOrNull(),
                            joinColumns = foreignKey.localColumns.zip(foreignKey.referencedColumns)
                                .mapTo(mutableListOf()) { (local, referenced) ->
                                    AssociationJoinColumn(
                                        name = local,
                                        referencedColumnName = referenced,
                                        nullable = columnsByName[local.uppercase(Locale.ROOT)]?.nullable,
                                        insertable = local.uppercase(Locale.ROOT) !in identifierSet,
                                        updatable = local.uppercase(Locale.ROOT) !in identifierSet,
                                    )
                                },
                            fetch = FetchType.LAZY,
                        ),
                        validations = mutableListOf(),
                        annotations = mutableListOf(),
                        dependsOnProperties = mutableListOf(),
                    )
                    consumedColumns += localSet
                }
                table.columns
                    .filterNot { it.name.uppercase(Locale.ROOT) in consumedColumns }
                    .forEach { column ->
                        val suggestion = DatabaseTypeMapper.suggest(
                            column,
                            primaryKey = false,
                            foreignKey = null,
                            relatedEntity = null,
                        )
                        if (suggestion.unsupportedReason != null) {
                            issues += WorkspaceChangeIssue(
                                "JVW-DB-IMPORT-COLUMN-UNSUPPORTED",
                                "${table.name}.${column.name}: ${suggestion.unsupportedReason}",
                            )
                        } else {
                            attributes += column.toAttribute(suggestion)
                        }
                    }
                val status = when {
                    issues.isNotEmpty() -> DatabaseEntityImportStatus.BLOCKED
                    identifierColumns.size > 1 -> DatabaseEntityImportStatus.COMPOSITE_KEY
                    table.type.equals("VIEW", ignoreCase = true) -> DatabaseEntityImportStatus.VIEW
                    else -> DatabaseEntityImportStatus.READY
                }
                val entity = if (status != DatabaseEntityImportStatus.BLOCKED && idMapping != null) {
                    EntityModel(
                        className = classNames.getValue(key),
                        packageName = request.packageName,
                        sourceLanguage = request.sourceLanguage,
                        dataStore = store.name,
                        generationTarget = EntityGenerationTarget(request.moduleId, request.storeId),
                        entityName = "",
                        tableName = table.name,
                        tableSchema = table.schema,
                        tableCatalog = table.catalog,
                        entityType = EntityType.ENTITY,
                        id = idMapping.id,
                        traits = mutableListOf(),
                        attributes = attributes,
                        indexes = table.indexes.filterNot { index ->
                            index.columns.map { column -> column.uppercaseRoot() }.toSet() == identifierSet
                        }.filterNot(DatabaseIndexSnapshot::unique).mapTo(mutableListOf()) {
                            IndexModel(it.name, it.columns, unique = false)
                        },
                        uniqueConstraints = table.indexes
                            .filter(DatabaseIndexSnapshot::unique)
                            .filterNot {
                                it.columns.map { column -> column.uppercaseRoot() }.toSet() == identifierSet
                            }
                            .mapTo(mutableListOf()) {
                                UniqueConstraintModel(it.name, it.columns)
                            },
                        comment = table.remarks,
                        databaseView = table.type.equals("VIEW", ignoreCase = true),
                        ddlGeneration = disabledDdl(),
                    )
                } else {
                    null
                }
                if (idMapping?.embeddable != null && entity != null) {
                    entityModels[idMapping.embeddable.fullName] = idMapping.embeddable
                }
                if (entity != null) entityModels[entity.fullName] = entity
                tablePlans += DatabaseEntityImportTablePlan(
                    table = table,
                    selectedByUser = imported.selectedByUser,
                    requiredBy = imported.requiredBy.sorted(),
                    status = status,
                    entityClassName = entity?.className ?: classNames.getValue(key),
                    entityQualifiedName = entity?.fullName,
                    compositeIdClassName = idMapping?.embeddable?.fullName,
                    generated = entity != null,
                    issues = issues,
                )
            }

        importedTables.filter { it.existingEntity != null }.forEach { imported ->
            tablePlans += DatabaseEntityImportTablePlan(
                table = imported.table,
                selectedByUser = imported.selectedByUser,
                requiredBy = imported.requiredBy.sorted(),
                status = DatabaseEntityImportStatus.EXISTING_ENTITY,
                entityClassName = imported.existingEntity?.className,
                entityQualifiedName = imported.existingEntity?.qualifiedName,
                compositeIdClassName = null,
                generated = false,
                issues = emptyList(),
            )
        }

        joinTables.forEach { (joinKey, joinTable) ->
            val imported = tablesByKey.getValue(joinKey)
            val issues = mutableListOf<WorkspaceChangeIssue>()
            val leftKey = joinTable.left.targetKey
            val rightKey = joinTable.right.targetKey
            val leftName = generatedEntityByTable[leftKey] ?: existingEntityByTable[leftKey]?.qualifiedName
            val rightName = generatedEntityByTable[rightKey] ?: existingEntityByTable[rightKey]?.qualifiedName
            val leftEntity = leftName?.let(entityModels::get)
            val rightEntity = rightName?.let(entityModels::get)
            if (leftName == null || rightName == null) {
                issues += WorkspaceChangeIssue(
                    "JVW-DB-IMPORT-JOIN-TARGET-MISSING",
                    "${imported.table.name} join-table endpoints are not both mapped or selected.",
                )
            } else if (leftEntity == null && rightEntity == null) {
                issues += WorkspaceChangeIssue(
                    "JVW-DB-IMPORT-JOIN-EXISTING-READONLY",
                    "${imported.table.name} connects only existing entities; automatic handwritten edits require a separate reviewed round trip.",
                )
            } else {
                val ownerLeft = leftEntity != null
                val owner = if (ownerLeft) leftEntity else rightEntity
                val inverse = if (ownerLeft) rightEntity else leftEntity
                val ownerFk = if (ownerLeft) joinTable.left else joinTable.right
                val inverseFk = if (ownerLeft) joinTable.right else joinTable.left
                val ownerName = if (ownerLeft) leftName else rightName
                val inverseName = if (ownerLeft) rightName else leftName
                if (owner != null) {
                    val ownerProperty = uniqueAttributeName(
                        inverseName.substringAfterLast('.').replaceFirstChar(Char::lowercase) + "Set",
                        owner.attributes.mapTo(linkedSetOf(), AttributeModel::name),
                    )
                    owner.attributes += AttributeModel(
                        name = ownerProperty,
                        type = AttributeType.ASSOCIATION,
                        association = AssociationConfig(
                            associationType = AssociationType.MANY_TO_MANY,
                            relatedEntity = inverseName,
                            collectionType = AssociationCollectionType.SET,
                            joinTable = JoinTableConfig(
                                name = imported.table.name,
                                joinColumnName = ownerFk.localColumns.first(),
                                inverseJoinColumnName = inverseFk.localColumns.first(),
                                schema = imported.table.schema,
                                catalog = imported.table.catalog,
                                joinColumns = ownerFk.joinColumns(),
                                inverseJoinColumns = inverseFk.joinColumns(),
                            ),
                        ),
                        dependsOnProperties = mutableListOf(),
                        validations = mutableListOf(),
                        annotations = mutableListOf(),
                    )
                    if (inverse != null) {
                        val inverseProperty = uniqueAttributeName(
                            ownerName.substringAfterLast('.').replaceFirstChar(Char::lowercase) + "Set",
                            inverse.attributes.mapTo(linkedSetOf(), AttributeModel::name),
                        )
                        inverse.attributes += AttributeModel(
                            name = inverseProperty,
                            type = AttributeType.ASSOCIATION,
                            association = AssociationConfig(
                                associationType = AssociationType.MANY_TO_MANY,
                                relatedEntity = ownerName,
                                mappedBy = ownerProperty,
                                collectionType = AssociationCollectionType.SET,
                            ),
                            dependsOnProperties = mutableListOf(),
                            validations = mutableListOf(),
                            annotations = mutableListOf(),
                        )
                    }
                }
            }
            tablePlans += DatabaseEntityImportTablePlan(
                table = imported.table,
                selectedByUser = imported.selectedByUser,
                requiredBy = imported.requiredBy.sorted(),
                status = if (issues.isEmpty()) {
                    DatabaseEntityImportStatus.JOIN_TABLE
                } else {
                    DatabaseEntityImportStatus.BLOCKED
                },
                entityClassName = null,
                entityQualifiedName = null,
                compositeIdClassName = null,
                generated = false,
                issues = issues,
            )
        }
        val sortedPlans = tablePlans.sortedWith(
            compareBy<DatabaseEntityImportTablePlan> { it.table.schema.orEmpty().lowercase(Locale.ROOT) }
                .thenBy { it.table.name.lowercase(Locale.ROOT) },
        )
        val allIssues = sortedPlans.flatMap(DatabaseEntityImportTablePlan::issues)
        val digest = databaseImportDigest(request, database, sortedPlans)
        return DatabaseEntityImportPlanResponse(
            accepted = true,
            ready = allIssues.isEmpty() && entityModels.values.any { it.entityType == EntityType.ENTITY },
            snapshotDigest = digest,
            storeId = request.storeId,
            database = database,
            tables = sortedPlans,
            entities = entityModels.values.toList(),
            issues = allIssues,
        )
    }

    private fun identifierColumns(
        request: DatabaseEntityImportRequest,
        table: DatabaseTableSnapshot,
    ): List<String> {
        if (table.primaryKeyColumns.isNotEmpty()) return table.primaryKeyColumns
        if (!table.type.equals("VIEW", ignoreCase = true)) return emptyList()
        val key = DatabaseObjectKey.of(table).externalName
        return request.identifierOverrides[key].orEmpty()
    }

    private fun identifierMapping(
        table: DatabaseTableSnapshot,
        identifierColumns: List<String>,
        className: String,
        packageName: String,
        sourceLanguage: EntitySourceLanguage,
        generationTarget: EntityGenerationTarget,
        issues: MutableList<WorkspaceChangeIssue>,
    ): IdentifierMapping? {
        if (identifierColumns.isEmpty()) return null
        val columns = table.columns.associateBy { it.name.uppercase(Locale.ROOT) }
        val selected = identifierColumns.mapNotNull { columns[it.uppercase(Locale.ROOT)] }
        if (selected.size != identifierColumns.size) return null
        val attributes = selected.map { column ->
            val suggestion = DatabaseTypeMapper.suggest(column, true, null, null)
            if (
                suggestion.unsupportedReason != null ||
                suggestion.attributeType !in COMPOSITE_IDENTIFIER_TYPES
            ) {
                issues += WorkspaceChangeIssue(
                    "JVW-DB-IMPORT-IDENTIFIER-TYPE-UNSUPPORTED",
                    "${table.name}.${column.name} cannot be used as a generated Jmix identifier (${column.typeName}).",
                )
                return null
            }
            column.toAttribute(suggestion).copy(mandatory = true)
        }
        if (attributes.size != selected.size) return null
        if (selected.size == 1) {
            val column = selected.single()
            if (column.generated && !column.autoIncrement) {
                issues += WorkspaceChangeIssue(
                    "JVW-DB-IMPORT-GENERATED-IDENTIFIER-UNSUPPORTED",
                    "${table.name}.${column.name} is database-generated but is not an identity column. " +
                        "Configure this identifier manually before import.",
                )
                return null
            }
            val type = idType(attributes.single().type)
            if (type == null) {
                issues += WorkspaceChangeIssue(
                    "JVW-DB-IMPORT-IDENTIFIER-TYPE-UNSUPPORTED",
                    "${table.name}.${column.name} has no supported scalar Jmix identifier mapping.",
                )
                return null
            }
            return IdentifierMapping(
                id = IdConfig(
                    type = type,
                    generation = if (column.autoIncrement) IdGeneration.IDENTITY else IdGeneration.ASSIGNED,
                    columnName = column.name,
                    length = attributes.single().length,
                ),
                embeddable = null,
            )
        }
        val idClassName = "${className}Id"
        val embeddable = EntityModel(
            className = idClassName,
            packageName = packageName,
            sourceLanguage = sourceLanguage,
            generationTarget = generationTarget,
            entityName = idClassName,
            entityType = EntityType.EMBEDDABLE,
            embeddableIdentity = true,
            attributes = attributes.toMutableList(),
            ddlGeneration = disabledDdl(),
        )
        return IdentifierMapping(
            id = IdConfig(
                type = IdType.EMBEDDED,
                generation = IdGeneration.ASSIGNED,
                embeddedIdClass = "$packageName.$idClassName",
                embeddedAttributes = attributes.toMutableList(),
            ),
            embeddable = embeddable,
        )
    }

    private fun detectJoinTable(
        table: DatabaseTableSnapshot,
        foreignKeys: List<DatabaseForeignKeyGroup>,
    ): JoinTableDetection? {
        if (table.type.equals("VIEW", ignoreCase = true) || foreignKeys.size != 2) return null
        val fkColumns = foreignKeys.flatMap(DatabaseForeignKeyGroup::localColumns)
            .map { it.uppercaseRoot() }
            .toSet()
        if (fkColumns.size != foreignKeys.sumOf { it.localColumns.size }) return null
        val allColumns = table.columns.map { it.name.uppercase(Locale.ROOT) }.toSet()
        if (allColumns != fkColumns) return null
        val primary = table.primaryKeyColumns.map { it.uppercaseRoot() }.toSet()
        val uniqueCoverage = table.indexes.any {
            it.unique && it.columns.map { column -> column.uppercaseRoot() }.toSet() == fkColumns
        }
        if (primary != fkColumns && !uniqueCoverage) return null
        val sorted = foreignKeys.sortedBy { it.targetKey.externalName }
        return JoinTableDetection(sorted[0], sorted[1])
    }

    internal fun groupForeignKeys(
        foreignKeys: List<DatabaseForeignKeySnapshot>,
    ): List<DatabaseForeignKeyGroup> {
        val named = foreignKeys.filter { !it.name.isNullOrBlank() }
            .groupBy { "name:${it.name}" }
        val unnamed = foreignKeys.filter { it.name.isNullOrBlank() }
            .groupBy {
                listOf(
                    it.referencedCatalog.orEmpty(),
                    it.referencedSchema.orEmpty(),
                    it.referencedTableName,
                ).joinToString("\u0000").uppercase(Locale.ROOT)
            }
        val ambiguousUnnamed = unnamed.filterValues { values ->
            values.map(DatabaseForeignKeySnapshot::sequence).distinct().size != values.size
        }
        val groups = mutableListOf<List<DatabaseForeignKeySnapshot>>()
        groups += named.values
        groups += unnamed.filterKeys { it !in ambiguousUnnamed }.values
        return groups.map { rows ->
            val sorted = rows.sortedBy(DatabaseForeignKeySnapshot::sequence)
            val first = sorted.first()
            DatabaseForeignKeyGroup(
                name = first.name,
                localColumns = sorted.map(DatabaseForeignKeySnapshot::columnName),
                referencedCatalog = first.referencedCatalog,
                referencedSchema = first.referencedSchema,
                referencedTableName = first.referencedTableName,
                referencedColumns = sorted.map(DatabaseForeignKeySnapshot::referencedColumnName),
            )
        }
    }

    private fun assignClassNames(
        tables: List<DatabaseTableSnapshot>,
        overrides: Map<String, String>,
    ): Map<DatabaseObjectKey, String> {
        val base = tables.associate { table ->
            val key = DatabaseObjectKey.of(table)
            key to (
                overrides[key.externalName]?.takeIf(JVM_IDENTIFIER::matches)
                    ?: table.name.toPascalIdentifier()
                )
        }
        val duplicates = base.values.groupingBy { it.lowercaseRoot() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val used = linkedSetOf<String>()
        return base.entries.sortedBy { it.key.externalName }.associate { (key, value) ->
            val qualified = if (value.lowercase(Locale.ROOT) in duplicates) {
                key.schema.orEmpty().toPascalIdentifier() + value
            } else {
                value
            }
            var candidate = qualified.ifBlank { "ImportedEntity" }
            var suffix = 2
            while (!used.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$qualified${suffix++}"
            }
            key to candidate
        }
    }

    private fun databaseImportDigest(
        request: DatabaseEntityImportRequest,
        database: DatabaseProductSnapshot,
        tables: List<DatabaseEntityImportTablePlan>,
    ): String = CanonicalDiscoveryJson.sha256(
        buildString {
            append(request.storeId).append('\u0000')
            append(request.moduleId).append('\u0000')
            append(request.packageName).append('\u0000')
            append(request.sourceLanguage.name).append('\u0000')
            append(request.includeDependencies).append('\u0000')
            append(request.profileId.orEmpty()).append('\u0000')
            append(request.profileLabel.orEmpty()).append('\u0000')
            request.selectedTables
                .map { DatabaseObjectKey(it.catalog, it.schema, it.name).externalName }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .forEach { append("selected:").append(it).append('\u0000') }
            request.identifierOverrides.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (table, columns) ->
                append("identifier:").append(table).append(':')
                    .append(columns.joinToString(",")).append('\u0000')
            }
            request.classNameOverrides.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (table, className) ->
                append("class:").append(table).append(':').append(className).append('\u0000')
            }
            append(database.name).append('\u0000')
            append(database.version).append('\u0000')
            append(database.driverName).append('\u0000')
            append(database.driverVersion).append('\u0000')
            append(database.urlFingerprint).append('\u0000')
            tables.forEach { plan ->
                val table = plan.table
                append(DatabaseObjectKey.of(table).externalName).append('\u0000')
                append(table.type).append('\u0000')
                append(table.remarks.orEmpty()).append('\u0000')
                append(plan.status.name).append('\u0000')
                table.primaryKeyColumns.forEach { append("pk:").append(it).append('\u0000') }
                table.columns.forEach {
                    append(it.name).append(':').append(it.jdbcType).append(':')
                        .append(it.typeName).append(':').append(it.size).append(':')
                        .append(it.scale).append(':').append(it.nullable).append(':')
                        .append(it.autoIncrement).append(':').append(it.generated).append(':')
                        .append(it.ordinal).append(':').append(it.remarks.orEmpty()).append('\u0000')
                }
                table.foreignKeys.forEach {
                    append("fk:").append(it.name).append(':').append(it.columnName)
                        .append(':').append(it.referencedCatalog.orEmpty()).append(':')
                        .append(it.referencedSchema.orEmpty()).append(':')
                        .append(it.referencedTableName).append(':')
                        .append(it.referencedColumnName).append(':').append(it.sequence).append(':')
                        .append(it.updateRule).append(':').append(it.deleteRule).append('\u0000')
                }
                table.indexes.forEach {
                    append("idx:").append(it.name).append(':').append(it.unique).append(':')
                        .append(it.columns.joinToString(",")).append('\u0000')
                }
            }
        },
    )

    private fun DatabaseColumnSnapshot.toAttribute(
        suggestion: DatabaseColumnMappingSuggestion,
    ): AttributeModel = AttributeModel(
        name = suggestion.attributeName,
        type = suggestion.attributeType,
        columnName = name,
        mandatory = !nullable && !generated,
        length = suggestion.length,
        precision = suggestion.precision,
        scale = suggestion.scale,
        comment = remarks,
        lob = jdbcType in setOf(
            java.sql.Types.BLOB,
            java.sql.Types.CLOB,
            java.sql.Types.LONGVARBINARY,
            java.sql.Types.LONGVARCHAR,
        ),
        javaTypeName = suggestion.javaType.takeIf { suggestion.attributeType == AttributeType.CUSTOM },
        sqlType = suggestion.customSqlType,
        readOnly = generated,
        dependsOnProperties = mutableListOf(),
        validations = mutableListOf(),
        annotations = mutableListOf(),
    )

    private fun DatabaseForeignKeyGroup.attributeName(targetClassName: String): String {
        val local = localColumns.singleOrNull()
            ?.replace(Regex("""(?i)(_ID|_CODE|_KEY)$"""), "")
            ?.toCamelIdentifier()
        return local?.takeIf(String::isNotBlank)
            ?: targetClassName.replaceFirstChar(Char::lowercase)
    }

    private fun DatabaseForeignKeyGroup.joinColumns(): MutableList<AssociationJoinColumn> =
        localColumns.zip(referencedColumns).mapTo(mutableListOf()) { (local, referenced) ->
            AssociationJoinColumn(local, referenced)
        }

    private fun uniqueAttributeName(base: String, used: Set<String>): String {
        var candidate = base.ifBlank { "reference" }
        var suffix = 2
        while (candidate in used) candidate = "$base${suffix++}"
        return candidate
    }

    private fun idType(attributeType: AttributeType): IdType? = when (attributeType) {
        AttributeType.UUID -> IdType.UUID
        AttributeType.LONG -> IdType.LONG
        AttributeType.INTEGER -> IdType.INTEGER
        AttributeType.STRING -> IdType.STRING
        else -> null
    }

    private fun disabledDdl() = DdlGenerationConfig(
        enabled = false,
        mode = DdlGenerationMode.DISABLED,
    )

    private fun String.toPascalIdentifier(): String {
        val parts = split(Regex("""[^A-Za-z0-9]+""")).filter(String::isNotBlank)
        val value = parts.joinToString("") {
            it.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase)
        }.ifBlank { "Imported" }
        return if (value.first().isDigit()) "T$value" else value
    }

    private fun String.toCamelIdentifier(): String =
        toPascalIdentifier().replaceFirstChar(Char::lowercase)

    private fun String.uppercaseRoot(): String = uppercase(Locale.ROOT)
    private fun String.lowercaseRoot(): String = lowercase(Locale.ROOT)

    private val COMPOSITE_IDENTIFIER_TYPES = setOf(
        AttributeType.STRING,
        AttributeType.INTEGER,
        AttributeType.LONG,
        AttributeType.UUID,
    )
    private val JVM_IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
}

data class DatabaseEntityImportRequest(
    val storeId: String,
    val moduleId: String,
    val packageName: String,
    val sourceLanguage: EntitySourceLanguage = EntitySourceLanguage.JAVA,
    val selectedTables: List<DatabaseTableReference>,
    val includeDependencies: Boolean = true,
    val identifierOverrides: Map<String, List<String>> = emptyMap(),
    val classNameOverrides: Map<String, String> = emptyMap(),
    val profileId: String? = null,
    val profileLabel: String? = null,
    val connectTimeoutSeconds: Int = 10,
    val networkTimeoutSeconds: Int = 30,
)

data class DatabaseEntityImportPlanResponse(
    val accepted: Boolean,
    val ready: Boolean,
    val snapshotDigest: String?,
    val storeId: String?,
    val database: DatabaseProductSnapshot?,
    val tables: List<DatabaseEntityImportTablePlan>,
    val entities: List<EntityModel>,
    val issues: List<WorkspaceChangeIssue>,
    val profileDrift: DatabaseEntityImportProfileDrift? = null,
) {
    companion object {
        fun failure(code: String, message: String) = DatabaseEntityImportPlanResponse(
            accepted = false,
            ready = false,
            snapshotDigest = null,
            storeId = null,
            database = null,
            tables = emptyList(),
            entities = emptyList(),
            issues = listOf(WorkspaceChangeIssue(code, message)),
        )
    }
}

data class DatabaseEntityImportTablePlan(
    val table: DatabaseTableSnapshot,
    val selectedByUser: Boolean,
    val requiredBy: List<String>,
    val status: DatabaseEntityImportStatus,
    val entityClassName: String?,
    val entityQualifiedName: String?,
    val compositeIdClassName: String?,
    val generated: Boolean,
    val issues: List<WorkspaceChangeIssue>,
)

enum class DatabaseEntityImportStatus {
    READY,
    VIEW,
    COMPOSITE_KEY,
    JOIN_TABLE,
    EXISTING_ENTITY,
    BLOCKED,
}

internal data class DatabaseImportedTable(
    val table: DatabaseTableSnapshot,
    val selectedByUser: Boolean,
    val requiredBy: Set<String>,
    val existingEntity: SchemaEntitySnapshot?,
)

internal data class DatabaseObjectKey(
    val catalog: String?,
    val schema: String?,
    val name: String,
) {
    val externalName: String
        get() = listOfNotNull(catalog, schema, name).joinToString(".")

    override fun equals(other: Any?): Boolean =
        other is DatabaseObjectKey &&
            catalog.equals(other.catalog, ignoreCase = true) &&
            schema.equals(other.schema, ignoreCase = true) &&
            name.equals(other.name, ignoreCase = true)

    override fun hashCode(): Int = listOf(
        catalog.orEmpty().uppercase(Locale.ROOT),
        schema.orEmpty().uppercase(Locale.ROOT),
        name.uppercase(Locale.ROOT),
    ).hashCode()

    companion object {
        fun of(table: DatabaseTableSnapshot) =
            DatabaseObjectKey(table.catalog, table.schema, table.name)
    }
}

internal data class DatabaseForeignKeyGroup(
    val name: String?,
    val localColumns: List<String>,
    val referencedCatalog: String?,
    val referencedSchema: String?,
    val referencedTableName: String,
    val referencedColumns: List<String>,
) {
    val label: String get() = name ?: localColumns.joinToString("+")
    val targetKey: DatabaseObjectKey
        get() = DatabaseObjectKey(referencedCatalog, referencedSchema, referencedTableName)
}

private data class IdentifierMapping(
    val id: IdConfig,
    val embeddable: EntityModel?,
)

private data class JoinTableDetection(
    val left: DatabaseForeignKeyGroup,
    val right: DatabaseForeignKeyGroup,
)

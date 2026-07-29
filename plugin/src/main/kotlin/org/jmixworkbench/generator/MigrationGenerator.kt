package org.jmixworkbench.generator

import org.jmixworkbench.model.*

/**
 * Generates Liquibase XML changelogs from MigrationModel.
 * Handles: createTable, dropTable, renameTable, addColumn, dropColumn,
 * renameColumn, modifyColumn, addPrimaryKey, dropPrimaryKey,
 * addForeignKeyConstraint, dropForeignKeyConstraint, addUniqueConstraint,
 * dropUniqueConstraint, addNotNullConstraint, dropNotNullConstraint,
 * createIndex, dropIndex, insert, update, delete, createSequence,
 * dropSequence, addAutoIncrement, raw SQL, tagDatabase, remarks.
 */
object MigrationGenerator {

    private const val NS_LB = "http://www.liquibase.org/xml/ns/dbchangelog"

    fun generate(migration: MigrationModel): String {
        val xml = XmlBuilder("databaseChangeLog")
        xml.noDeclaration()

        xml.root {
            attr("xmlns", NS_LB)
            attr("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attr(
                "xsi:schemaLocation",
                "http://www.liquibase.org/xml/ns/dbchangelog " +
                "http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd"
            )
            migration.logicalFilePath?.let { attr("logicalFilePath", it) }
            migration.objectQuotingStrategy?.let { attr("objectQuotingStrategy", it) }

            migration.changes.forEach { changeSet ->
                generateChangeSet(this, changeSet)
            }
        }

        return xml.build()
    }

    private fun generateChangeSet(parent: XmlBuilder.Element, cs: ChangeSetModel) {
        parent.child("changeSet") {
            attr("id", cs.id)
            attr("author", cs.author)
            cs.comment?.let { attr("comment", it) }
            cs.context?.let { attr("context", it) }
            cs.dbms?.let { attr("dbms", it) }
            if (cs.runOnChange) attr("runOnChange", "true")
            if (cs.runAlways) attr("runAlways", "true")
            if (!cs.runInTransaction) attr("runInTransaction", "false")
            cs.labels?.let { attr("labels", it) }

            // Preconditions
            if (cs.preConditions.isNotEmpty()) {
                child("preConditions") {
                    attr("onFail", "MARK_RAN")
                    cs.preConditions.forEach { pc ->
                        generatePreCondition(this, pc)
                    }
                }
            }

            // Changes
            cs.changes.forEach { change ->
                generateChange(this, change)
            }
            if (cs.rollback.isNotEmpty()) {
                child("rollback") {
                    cs.rollback.forEach { change -> generateChange(this, change) }
                }
            }
        }
    }

    private fun generatePreCondition(parent: XmlBuilder.Element, pc: PreCondition) {
        when (pc.type) {
            PreConditionType.TABLE_EXISTS -> parent.child("tableExists") {
                pc.params["tableName"]?.let { attr("tableName", it) }
                pc.params["schemaName"]?.let { attr("schemaName", it) }
            }
            PreConditionType.TABLE_NOT_EXISTS -> parent.child("not") {
                child("tableExists") {
                    pc.params["tableName"]?.let { attr("tableName", it) }
                }
            }
            PreConditionType.COLUMN_EXISTS -> parent.child("columnExists") {
                pc.params["tableName"]?.let { attr("tableName", it) }
                pc.params["columnName"]?.let { attr("columnName", it) }
            }
            PreConditionType.COLUMN_NOT_EXISTS -> parent.child("not") {
                child("columnExists") {
                    pc.params["tableName"]?.let { attr("tableName", it) }
                    pc.params["columnName"]?.let { attr("columnName", it) }
                }
            }
            PreConditionType.SQL_CHECK -> parent.child("sqlCheck") {
                attr("expectedResult", pc.params["expectedResult"] ?: "1")
                text(pc.params["sql"] ?: "")
            }
            PreConditionType.DBMS -> parent.child("dbms") {
                pc.params["type"]?.let { attr("type", it) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateChange(parent: XmlBuilder.Element, change: DbChange) {
        when (change) {
            is DbChange.CreateTable -> parent.child("createTable") {
                attr("tableName", change.tableName)
                change.remarks?.let { attr("remarks", it) }
                change.columns.forEach { col -> generateColumn(this, col) }
            }

            is DbChange.DropTable -> parent.child("dropTable") {
                attr("tableName", change.tableName)
                if (change.cascadeConstraints) attr("cascadeConstraints", "true")
            }

            is DbChange.RenameTable -> parent.child("renameTable") {
                attr("oldTableName", change.oldTableName)
                attr("newTableName", change.newTableName)
            }

            is DbChange.AddColumn -> parent.child("addColumn") {
                attr("tableName", change.tableName)
                change.columns.forEach { col -> generateColumn(this, col) }
            }

            is DbChange.DropColumn -> parent.child("dropColumn") {
                attr("tableName", change.tableName)
                attr("columnName", change.columnName)
            }

            is DbChange.RenameColumn -> parent.child("renameColumn") {
                attr("tableName", change.tableName)
                attr("oldColumnName", change.oldColumnName)
                attr("newColumnName", change.newColumnName)
                change.columnDataType?.let { attr("columnDataType", it) }
            }

            is DbChange.ModifyColumn -> {
                change.newDataType?.let { newDataType ->
                    parent.child("modifyDataType") {
                        attr("tableName", change.tableName)
                        attr("columnName", change.columnName)
                        attr("newDataType", newDataType)
                    }
                }
                change.newNullable?.let { nullable ->
                    parent.child(if (nullable) "dropNotNullConstraint" else "addNotNullConstraint") {
                        attr("tableName", change.tableName)
                        attr("columnName", change.columnName)
                        change.newDataType?.let { attr("columnDataType", it) }
                        if (!nullable) change.newDefaultValue?.let { attr("defaultNullValue", it) }
                    }
                }
                change.newRemarks?.let { remarks ->
                    parent.child("setColumnRemarks") {
                        attr("tableName", change.tableName)
                        attr("columnName", change.columnName)
                        attr("remarks", remarks)
                    }
                }
            }

            is DbChange.AddPrimaryKey -> parent.child("addPrimaryKey") {
                attr("tableName", change.tableName)
                attr("constraintName", change.constraintName)
                attr("columnNames", change.columnNames.joinToString(", "))
            }

            is DbChange.DropPrimaryKey -> parent.child("dropPrimaryKey") {
                attr("tableName", change.tableName)
                change.constraintName?.let { attr("constraintName", it) }
            }

            is DbChange.AddForeignKeyConstraint -> parent.child("addForeignKeyConstraint") {
                attr("constraintName", change.constraintName)
                attr("baseTableName", change.baseTableName)
                attr("baseColumnNames", change.baseColumnNames)
                attr("referencedTableName", change.referencedTableName)
                attr("referencedColumnNames", change.referencedColumnNames)
                change.onDelete?.let { attr("onDelete", it) }
                change.onUpdate?.let { attr("onUpdate", it) }
            }

            is DbChange.DropForeignKeyConstraint -> parent.child("dropForeignKeyConstraint") {
                attr("constraintName", change.constraintName)
                attr("baseTableName", change.baseTableName)
            }

            is DbChange.AddUniqueConstraint -> parent.child("addUniqueConstraint") {
                attr("tableName", change.tableName)
                attr("constraintName", change.constraintName)
                attr("columnNames", change.columnNames.joinToString(", "))
            }

            is DbChange.DropUniqueConstraint -> parent.child("dropUniqueConstraint") {
                attr("tableName", change.tableName)
                attr("constraintName", change.constraintName)
            }

            is DbChange.AddNotNullConstraint -> parent.child("addNotNullConstraint") {
                attr("tableName", change.tableName)
                attr("columnName", change.columnName)
                change.columnDataType?.let { attr("columnDataType", it) }
                change.defaultNullValue?.let { attr("defaultNullValue", it) }
            }

            is DbChange.DropNotNullConstraint -> parent.child("dropNotNullConstraint") {
                attr("tableName", change.tableName)
                attr("columnName", change.columnName)
            }

            is DbChange.CreateIndex -> parent.child("createIndex") {
                attr("tableName", change.tableName)
                attr("indexName", change.indexName)
                if (change.unique) attr("unique", "true")
                change.tablespace?.let { attr("tablespace", it) }
                change.columns.forEach { col ->
                    child("column") {
                        attr("name", col.name)
                        if (col.descending) attr("descending", "true")
                    }
                }
            }

            is DbChange.DropIndex -> parent.child("dropIndex") {
                attr("tableName", change.tableName)
                attr("indexName", change.indexName)
            }

            is DbChange.InsertData -> parent.child("insert") {
                attr("tableName", change.tableName)
                change.columns.forEach { cv ->
                    child("column") {
                        attr("name", cv.name)
                        cv.value?.let { attr("value", it) }
                        cv.valueComputed?.let { attr("valueComputed", it) }
                        cv.type?.let { attr("type", it) }
                    }
                }
            }

            is DbChange.UpdateData -> parent.child("update") {
                attr("tableName", change.tableName)
                change.columns.forEach { cv ->
                    child("column") {
                        attr("name", cv.name)
                        cv.value?.let { attr("value", it) }
                        cv.valueComputed?.let { attr("valueComputed", it) }
                    }
                }
                change.whereClause?.let { child("where") { text(it) } }
            }

            is DbChange.DeleteData -> parent.child("delete") {
                attr("tableName", change.tableName)
                change.whereClause?.let { child("where") { text(it) } }
            }

            is DbChange.CreateSequence -> parent.child("createSequence") {
                attr("sequenceName", change.sequenceName)
                attr("startValue", change.startValue.toString())
                attr("incrementBy", change.incrementBy.toString())
                change.minValue?.let { attr("minValue", it.toString()) }
                change.maxValue?.let { attr("maxValue", it.toString()) }
                if (change.cycle) attr("cycle", "true")
            }

            is DbChange.DropSequence -> parent.child("dropSequence") {
                attr("sequenceName", change.sequenceName)
            }

            is DbChange.AddAutoIncrement -> parent.child("addAutoIncrement") {
                attr("tableName", change.tableName)
                attr("columnName", change.columnName)
                change.columnDataType?.let { attr("columnDataType", it) }
                change.startWith?.let { attr("startWith", it.toString()) }
            }

            is DbChange.RawSql -> parent.child("sql") {
                if (change.splitStatements) attr("splitStatements", "true")
                if (change.stripComments) attr("stripComments", "true")
                change.dbms?.let { attr("dbms", it) }
                text(change.sql)
            }

            is DbChange.RawSqlFile -> parent.child("sqlFile") {
                attr("path", change.path)
                if (change.splitStatements) attr("splitStatements", "true")
                if (change.stripComments) attr("stripComments", "true")
                change.dbms?.let { attr("dbms", it) }
            }

            is DbChange.TagDatabase -> parent.child("tagDatabase") {
                attr("tag", change.tag)
            }

            is DbChange.SetTableRemarks -> parent.child("setTableRemarks") {
                attr("tableName", change.tableName)
                attr("remarks", change.remarks)
            }

            is DbChange.SetColumnRemarks -> parent.child("setColumnRemarks") {
                attr("tableName", change.tableName)
                attr("columnName", change.columnName)
                attr("remarks", change.remarks)
            }
        }
    }

    private fun generateColumn(parent: XmlBuilder.Element, col: ColumnDef) {
        parent.child("column") {
            attr("name", col.name)
            attr("type", col.type)
            col.defaultValue?.let { attr("defaultValue", it) }
            col.defaultValueComputed?.let { attr("defaultValueComputed", it) }
            if (col.autoIncrement) attr("autoIncrement", "true")
            col.remarks?.let { attr("remarks", it) }
            if (col.primaryKey || !col.nullable || col.unique) {
                child("constraints") {
                    if (col.primaryKey) attr("primaryKey", "true")
                    if (!col.nullable || col.primaryKey) attr("nullable", "false")
                    if (col.unique) attr("unique", "true")
                }
            }
        }
    }

    // ─── Entity → Migration auto-generation ──────────────────────────────────

    fun generateFromEntity(entity: EntityModel, dbType: DatabaseType): MigrationModel {
        val migration = MigrationModel(
            changelogId = "001-${entity.resolvedTableName.lowercase()}"
        )

        val changeSet = ChangeSetModel(
            id = "1",
            comment = "Create table ${entity.resolvedTableName}"
        )

        val createTable = DbChange.CreateTable(
            tableName = entity.resolvedTableName,
            remarks = entity.comment
        )

        // ID column
        val idColType = idColumnType(entity.id.type, dbType, entity.id.length)

        createTable.columns.add(ColumnDef(
            name = entity.id.columnName,
            type = idColType,
            nullable = false,
            primaryKey = true,
            autoIncrement = entity.id.generation == IdGeneration.IDENTITY
        ))

        // Version column
        if (entity.traits.any { it == TraitType.HAS_VERSION || it == TraitType.STANDARD_ENTITY }) {
            createTable.columns.add(ColumnDef(
                name = "VERSION",
                type = "INT",
                nullable = false,
                defaultValue = "1"
            ))
        }

        // Trait columns
        val traits = entity.traits.toSet()
        if (TraitType.UUID_TRAIT in traits && entity.id.type != IdType.UUID) {
            createTable.columns.add(
                ColumnDef(
                    name = "UUID",
                    type = "UUID",
                    nullable = false,
                    unique = true,
                ),
            )
        }
        if (TraitType.SOFT_DELETE in traits) {
            createTable.columns.add(ColumnDef(name = "DELETED_DATE", type = "TIMESTAMP"))
            createTable.columns.add(ColumnDef(name = "DELETED_BY", type = "VARCHAR(255)"))
        }
        if (TraitType.HAS_TENANT_ID in traits) {
            createTable.columns.add(ColumnDef(name = "SYS_TENANT_ID", type = "VARCHAR(255)"))
        }
        val compositeAudit = TraitType.AUDITABLE in traits || TraitType.STANDARD_ENTITY in traits
        if (compositeAudit || TraitType.CREATED_BY in traits) {
            createTable.columns.add(ColumnDef(name = "CREATED_BY", type = "VARCHAR(255)"))
        }
        if (compositeAudit || TraitType.CREATED_DATE in traits) {
            createTable.columns.add(ColumnDef(name = "CREATED_DATE", type = "TIMESTAMP"))
        }
        if (compositeAudit || TraitType.UPDATED_BY in traits) {
            createTable.columns.add(ColumnDef(name = "LAST_MODIFIED_BY", type = "VARCHAR(255)"))
        }
        if (compositeAudit || TraitType.UPDATED_DATE in traits) {
            createTable.columns.add(ColumnDef(name = "LAST_MODIFIED_DATE", type = "TIMESTAMP"))
        }

        // Attribute columns
        entity.attributes.forEach { attr ->
            if (attr.transientFlag) return@forEach
            if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                attr.association?.let { assoc ->
                    when (assoc.associationType) {
                        AssociationType.MANY_TO_ONE, AssociationType.ONE_TO_ONE -> {
                            if (assoc.mappedBy == null || assoc.crossDataStore) {
                                createTable.columns.add(ColumnDef(
                                    name = assoc.joinColumnName ?: "${attr.resolvedColumnName}_ID",
                                    type = idColumnType(assoc.relatedIdType, dbType),
                                    nullable = !attr.mandatory,
                                    unique = assoc.associationType == AssociationType.ONE_TO_ONE &&
                                        !assoc.crossDataStore,
                                ))
                            }
                        }
                        AssociationType.MANY_TO_MANY -> {
                            // Join table created separately
                        }
                        AssociationType.ONE_TO_MANY -> {
                            // FK is on the other side
                        }
                    }
                }
                return@forEach
            }
            if (attr.type == AttributeType.EMBEDDED) return@forEach

            val colType = resolveColumnType(attr, dbType)
            createTable.columns.add(ColumnDef(
                name = attr.resolvedColumnName,
                type = colType,
                nullable = !attr.mandatory,
                unique = attr.unique,
                remarks = attr.comment
            ))
        }

        changeSet.changes.add(createTable)

        // Indexes
        entity.indexes.forEach { idx ->
            changeSet.changes.add(DbChange.CreateIndex(
                tableName = entity.resolvedTableName,
                indexName = idx.name,
                columns = idx.columns.map { IndexColumnDef(it) },
                unique = idx.unique
            ))
        }
        entity.uniqueConstraints.forEach { constraint ->
            changeSet.changes.add(
                DbChange.AddUniqueConstraint(
                    tableName = entity.resolvedTableName,
                    constraintName = constraint.name,
                    columnNames = constraint.columns,
                ),
            )
        }

        // Foreign keys
        val generatedJoinTables = mutableListOf<String>()
        entity.attributes.forEach { attr ->
            if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                attr.association?.let { assoc ->
                    if (
                        !assoc.crossDataStore &&
                        (
                            assoc.associationType == AssociationType.MANY_TO_ONE ||
                                (assoc.associationType == AssociationType.ONE_TO_ONE && assoc.mappedBy == null)
                            )
                    ) {
                        val fkCol = assoc.joinColumnName ?: "${attr.resolvedColumnName}_ID"
                        val refTable = relatedTableName(assoc)
                        changeSet.changes.add(DbChange.AddForeignKeyConstraint(
                            constraintName = "FK_${entity.resolvedTableName}_${fkCol}",
                            baseTableName = entity.resolvedTableName,
                            baseColumnNames = fkCol,
                            referencedTableName = refTable,
                            referencedColumnNames = assoc.relatedIdColumnName,
                            onDelete = assoc.onDelete
                        ))
                    }
                    if (
                        assoc.associationType == AssociationType.MANY_TO_MANY &&
                        assoc.mappedBy.isNullOrBlank() &&
                        assoc.joinTable != null
                    ) {
                        val jt = assoc.joinTable
                        generatedJoinTables += jt.name
                        changeSet.changes.add(DbChange.CreateTable(
                            tableName = jt.name,
                            columns = mutableListOf(
                                ColumnDef(
                                    name = jt.joinColumnName,
                                    type = idColumnType(entity.id.type, dbType, entity.id.length),
                                    nullable = false,
                                ),
                                ColumnDef(
                                    name = jt.inverseJoinColumnName,
                                    type = idColumnType(assoc.relatedIdType, dbType),
                                    nullable = false,
                                )
                            )
                        ))
                        changeSet.changes.add(DbChange.AddPrimaryKey(
                            tableName = jt.name,
                            constraintName = "PK_${jt.name}",
                            columnNames = listOf(jt.joinColumnName, jt.inverseJoinColumnName)
                        ))
                        changeSet.changes.add(
                            DbChange.AddForeignKeyConstraint(
                                constraintName = "FK_${jt.name}_${jt.joinColumnName}",
                                baseTableName = jt.name,
                                baseColumnNames = jt.joinColumnName,
                                referencedTableName = entity.resolvedTableName,
                                referencedColumnNames = entity.id.columnName,
                                onDelete = assoc.onDelete,
                            ),
                        )
                        changeSet.changes.add(
                            DbChange.AddForeignKeyConstraint(
                                constraintName = "FK_${jt.name}_${jt.inverseJoinColumnName}",
                                baseTableName = jt.name,
                                baseColumnNames = jt.inverseJoinColumnName,
                                referencedTableName = relatedTableName(assoc),
                                referencedColumnNames = assoc.relatedIdColumnName,
                                onDelete = assoc.onDelete,
                            ),
                        )
                    }
                }
            }
        }
        generatedJoinTables.asReversed().forEach { tableName ->
            changeSet.rollback.add(DbChange.DropTable(tableName, cascadeConstraints = true))
        }
        changeSet.rollback.add(
            DbChange.DropTable(
                tableName = entity.resolvedTableName,
                cascadeConstraints = true,
            ),
        )

        migration.changes.add(changeSet)
        return migration
    }

    private fun relatedTableName(association: AssociationConfig): String =
        association.relatedTableName?.takeIf(String::isNotBlank)
            ?: association.relatedEntity.substringAfterLast('.')
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .uppercase()

    private fun idColumnType(
        type: IdType,
        dbType: DatabaseType,
        stringLength: Int? = null,
    ): String = when (type) {
        IdType.UUID -> if (dbType == DatabaseType.MSSQL) "UNIQUEIDENTIFIER" else "UUID"
        IdType.LONG -> "BIGINT"
        IdType.INTEGER -> "INT"
        IdType.STRING -> "VARCHAR(${stringLength ?: 255})"
        IdType.EMBEDDED -> error("Composite identifiers require explicit relationship column mapping.")
    }

    private fun resolveColumnType(attr: AttributeModel, dbType: DatabaseType): String {
        return when (attr.type) {
            AttributeType.STRING -> "VARCHAR(${attr.length ?: 255})"
            AttributeType.CHARACTER -> "CHAR(1)"
            AttributeType.INTEGER -> "INT"
            AttributeType.LONG -> "BIGINT"
            AttributeType.DOUBLE -> "DOUBLE"
            AttributeType.BIG_DECIMAL -> "DECIMAL(${attr.precision ?: 19}, ${attr.scale ?: 2})"
            AttributeType.BOOLEAN -> "BOOLEAN"
            AttributeType.DATE -> "DATE"
            AttributeType.LOCAL_DATE -> "DATE"
            AttributeType.LOCAL_DATE_TIME -> "TIMESTAMP"
            AttributeType.LOCAL_TIME -> "TIME"
            AttributeType.OFFSET_TIME -> "TIME"
            AttributeType.OFFSET_DATE_TIME -> "TIMESTAMP"
            AttributeType.SQL_DATE -> "DATE"
            AttributeType.SQL_TIME -> "TIME"
            AttributeType.UUID ->
                if (dbType == DatabaseType.MSSQL) "UNIQUEIDENTIFIER" else "UUID"
            AttributeType.URI -> "VARCHAR(${attr.length ?: 255})"
            AttributeType.BYTE_ARRAY -> "BLOB"
            AttributeType.FILE_REF -> "VARCHAR(${attr.length ?: 1024})"
            AttributeType.ENUM ->
                if (attr.enumIdType == EnumIdType.INTEGER) {
                    "INT"
                } else {
                    "VARCHAR(${attr.length ?: 255})"
                }
            AttributeType.CUSTOM -> requireNotNull(attr.sqlType)
            else -> "VARCHAR(255)"
        }
    }
}

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

            is DbChange.ModifyColumn -> parent.child("modifyDataType") {
                attr("tableName", change.tableName)
                attr("columnName", change.columnName)
                change.newDataType?.let { attr("newDataType", it) }
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
            if (!col.nullable) attr("nullable", "false")
            col.defaultValue?.let { attr("defaultValue", it) }
            col.defaultValueComputed?.let { attr("defaultValueComputed", it) }
            if (col.autoIncrement) attr("autoIncrement", "true")
            col.remarks?.let { attr("remarks", it) }
            if (col.unique) attr("unique", "true")

            if (col.primaryKey) {
                child("constraints") {
                    attr("primaryKey", "true")
                    attr("nullable", "false")
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
        val idColType = when (entity.id.type) {
            IdType.UUID -> "UUID"
            IdType.LONG -> if (dbType == DatabaseType.MYSQL) "BIGINT" else "BIGINT"
            IdType.INTEGER -> "INT"
            IdType.STRING -> "VARCHAR(${entity.id.length ?: 255})"
            IdType.EMBEDDED -> "VARCHAR(255)"
        }

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
        entity.traits.forEach { trait ->
            when (trait) {
                TraitType.SOFT_DELETE -> {
                    createTable.columns.add(ColumnDef(name = "DELETED_DATE", type = "TIMESTAMP"))
                    createTable.columns.add(ColumnDef(name = "DELETED_BY", type = "VARCHAR(255)"))
                }
                TraitType.HAS_TENANT_ID -> {
                    createTable.columns.add(ColumnDef(name = "TENANT_ID", type = "VARCHAR(255)"))
                }
                TraitType.CREATED_BY -> {
                    createTable.columns.add(ColumnDef(name = "CREATED_BY", type = "VARCHAR(255)"))
                }
                TraitType.CREATED_DATE -> {
                    createTable.columns.add(ColumnDef(name = "CREATED_DATE", type = "TIMESTAMP"))
                }
                TraitType.UPDATED_BY -> {
                    createTable.columns.add(ColumnDef(name = "UPDATED_BY", type = "VARCHAR(255)"))
                }
                TraitType.UPDATED_DATE -> {
                    createTable.columns.add(ColumnDef(name = "UPDATED_DATE", type = "TIMESTAMP"))
                }
                else -> {}
            }
        }

        // Attribute columns
        entity.attributes.forEach { attr ->
            if (attr.transientFlag) return@forEach
            if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                attr.association?.let { assoc ->
                    when (assoc.associationType) {
                        AssociationType.MANY_TO_ONE, AssociationType.ONE_TO_ONE -> {
                            if (assoc.mappedBy == null) {
                                createTable.columns.add(ColumnDef(
                                    name = assoc.joinColumnName ?: "${attr.resolvedColumnName}_ID",
                                    type = "UUID",
                                    nullable = !attr.mandatory
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

        // Foreign keys
        entity.attributes.forEach { attr ->
            if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                attr.association?.let { assoc ->
                    if (assoc.associationType == AssociationType.MANY_TO_ONE ||
                        (assoc.associationType == AssociationType.ONE_TO_ONE && assoc.mappedBy == null)) {
                        val fkCol = assoc.joinColumnName ?: "${attr.resolvedColumnName}_ID"
                        val refTable = assoc.relatedEntity.substringAfterLast('.')
                            .replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
                        changeSet.changes.add(DbChange.AddForeignKeyConstraint(
                            constraintName = "FK_${entity.resolvedTableName}_${fkCol}",
                            baseTableName = entity.resolvedTableName,
                            baseColumnNames = fkCol,
                            referencedTableName = refTable,
                            referencedColumnNames = "ID",
                            onDelete = assoc.onDelete
                        ))
                    }
                    if (assoc.associationType == AssociationType.MANY_TO_MANY && assoc.joinTable != null) {
                        val jt = assoc.joinTable
                        changeSet.changes.add(DbChange.CreateTable(
                            tableName = jt.name,
                            columns = mutableListOf(
                                ColumnDef(name = jt.joinColumnName, type = "UUID", nullable = false),
                                ColumnDef(name = jt.inverseJoinColumnName, type = "UUID", nullable = false)
                            )
                        ))
                        changeSet.changes.add(DbChange.AddPrimaryKey(
                            tableName = jt.name,
                            constraintName = "PK_${jt.name}",
                            columnNames = listOf(jt.joinColumnName, jt.inverseJoinColumnName)
                        ))
                    }
                }
            }
        }

        migration.changes.add(changeSet)
        return migration
    }

    private fun resolveColumnType(attr: AttributeModel, dbType: DatabaseType): String {
        return when (attr.type) {
            AttributeType.STRING -> "VARCHAR(${attr.length ?: 255})"
            AttributeType.INTEGER -> "INT"
            AttributeType.LONG -> "BIGINT"
            AttributeType.DOUBLE -> "DOUBLE PRECISION"
            AttributeType.BIG_DECIMAL -> "DECIMAL(${attr.precision ?: 19}, ${attr.scale ?: 2})"
            AttributeType.BOOLEAN -> "BOOLEAN"
            AttributeType.DATE -> "DATE"
            AttributeType.LOCAL_DATE -> "DATE"
            AttributeType.LOCAL_DATE_TIME -> "TIMESTAMP"
            AttributeType.LOCAL_TIME -> "TIME"
            AttributeType.OFFSET_DATE_TIME -> "TIMESTAMP WITH TIME ZONE"
            AttributeType.UUID -> "UUID"
            AttributeType.BYTE_ARRAY -> "BLOB"
            AttributeType.ENUM -> "VARCHAR(255)"
            else -> "VARCHAR(255)"
        }
    }
}

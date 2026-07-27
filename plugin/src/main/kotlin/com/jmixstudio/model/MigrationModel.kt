package com.jmixstudio.model

import com.google.gson.annotations.SerializedName

// ─── Migration / Liquibase ───────────────────────────────────────────────────

data class MigrationModel(
    val changelogId: String,
    val author: String = "jmix-studio",
    val changes: MutableList<ChangeSetModel> = mutableListOf()
)

data class ChangeSetModel(
    val id: String,
    val author: String = "jmix-studio",
    val comment: String? = null,
    val changes: MutableList<DbChange> = mutableListOf(),
    val preConditions: MutableList<PreCondition> = mutableListOf(),
    val context: String? = null,
    val dbms: String? = null,
    val runOnChange: Boolean = false,
    val runAlways: Boolean = false
)

sealed class DbChange {
    // ── Table operations ──
    data class CreateTable(
        val tableName: String,
        val columns: MutableList<ColumnDef> = mutableListOf(),
        val primaryKey: PrimaryKeyDef? = null,
        val remarks: String? = null
    ) : DbChange()

    data class DropTable(
        val tableName: String,
        val cascadeConstraints: Boolean = false
    ) : DbChange()

    data class RenameTable(
        val oldTableName: String,
        val newTableName: String
    ) : DbChange()

    // ── Column operations ──
    data class AddColumn(
        val tableName: String,
        val columns: MutableList<ColumnDef> = mutableListOf()
    ) : DbChange()

    data class DropColumn(
        val tableName: String,
        val columnName: String
    ) : DbChange()

    data class RenameColumn(
        val tableName: String,
        val oldColumnName: String,
        val newColumnName: String,
        val columnDataType: String? = null
    ) : DbChange()

    data class ModifyColumn(
        val tableName: String,
        val columnName: String,
        val newDataType: String? = null,
        val newNullable: Boolean? = null,
        val newDefaultValue: String? = null,
        val newRemarks: String? = null
    ) : DbChange()

    // ── Constraint operations ──
    data class AddPrimaryKey(
        val tableName: String,
        val constraintName: String,
        val columnNames: List<String>
    ) : DbChange()

    data class DropPrimaryKey(
        val tableName: String,
        val constraintName: String? = null
    ) : DbChange()

    data class AddForeignKeyConstraint(
        val constraintName: String,
        val baseTableName: String,
        val baseColumnNames: String,
        val referencedTableName: String,
        val referencedColumnNames: String = "ID",
        val onDelete: String? = null,
        val onUpdate: String? = null
    ) : DbChange()

    data class DropForeignKeyConstraint(
        val constraintName: String,
        val baseTableName: String
    ) : DbChange()

    data class AddUniqueConstraint(
        val tableName: String,
        val constraintName: String,
        val columnNames: List<String>
    ) : DbChange()

    data class DropUniqueConstraint(
        val tableName: String,
        val constraintName: String
    ) : DbChange()

    data class AddNotNullConstraint(
        val tableName: String,
        val columnName: String,
        val columnDataType: String? = null,
        val defaultNullValue: String? = null
    ) : DbChange()

    data class DropNotNullConstraint(
        val tableName: String,
        val columnName: String
    ) : DbChange()

    // ── Index operations ──
    data class CreateIndex(
        val tableName: String,
        val indexName: String,
        val columns: List<IndexColumnDef>,
        val unique: Boolean = false,
        val tablespace: String? = null
    ) : DbChange()

    data class DropIndex(
        val tableName: String,
        val indexName: String
    ) : DbChange()

    // ── Data operations ──
    data class InsertData(
        val tableName: String,
        val columns: MutableList<ColumnValueDef> = mutableListOf()
    ) : DbChange()

    data class UpdateData(
        val tableName: String,
        val columns: MutableList<ColumnValueDef> = mutableListOf(),
        val whereClause: String? = null
    ) : DbChange()

    data class DeleteData(
        val tableName: String,
        val whereClause: String? = null
    ) : DbChange()

    // ── Sequence operations ──
    data class CreateSequence(
        val sequenceName: String,
        val startValue: Long = 1,
        val incrementBy: Long = 1,
        val minValue: Long? = null,
        val maxValue: Long? = null,
        val cycle: Boolean = false
    ) : DbChange()

    data class DropSequence(
        val sequenceName: String
    ) : DbChange()

    data class AddAutoIncrement(
        val tableName: String,
        val columnName: String,
        val columnDataType: String? = null,
        val startWith: Long? = null
    ) : DbChange()

    // ── Raw SQL ──
    data class RawSql(
        val sql: String,
        val splitStatements: Boolean = true,
        val stripComments: Boolean = true,
        val dbms: String? = null
    ) : DbChange()

    data class RawSqlFile(
        val path: String,
        val splitStatements: Boolean = true,
        val stripComments: Boolean = true,
        val dbms: String? = null
    ) : DbChange()

    // ── Tag / Comment ──
    data class TagDatabase(val tag: String) : DbChange()
    data class SetTableRemarks(val tableName: String, val remarks: String) : DbChange()
    data class SetColumnRemarks(val tableName: String, val columnName: String, val remarks: String) : DbChange()
}

// ── Column definitions ──

data class ColumnDef(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val defaultValue: String? = null,
    val defaultValueComputed: String? = null,
    val autoIncrement: Boolean = false,
    val remarks: String? = null,
    val unique: Boolean = false,
    val primaryKey: Boolean = false
)

data class PrimaryKeyDef(
    val constraintName: String,
    val columnNames: List<String>
)

data class IndexColumnDef(
    val name: String,
    val descending: Boolean = false
)

data class ColumnValueDef(
    val name: String,
    val value: String? = null,
    val valueComputed: String? = null,
    val type: String? = null
)

data class PreCondition(
    val type: PreConditionType,
    val params: MutableMap<String, String> = mutableMapOf()
)

enum class PreConditionType {
    @SerializedName("tableExists") TABLE_EXISTS,
    @SerializedName("tableNotExists") TABLE_NOT_EXISTS,
    @SerializedName("columnExists") COLUMN_EXISTS,
    @SerializedName("columnNotExists") COLUMN_NOT_EXISTS,
    @SerializedName("sqlCheck") SQL_CHECK,
    @SerializedName("dbms") DBMS
}

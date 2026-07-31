package org.jmixworkbench.services

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.jmixworkbench.model.ChangeSetModel
import org.jmixworkbench.model.ColumnDef
import org.jmixworkbench.model.ColumnValueDef
import org.jmixworkbench.model.DbChange
import org.jmixworkbench.model.IndexColumnDef
import org.jmixworkbench.model.MigrationModel
import org.jmixworkbench.model.PreCondition
import org.jmixworkbench.model.PreConditionOutcome

/**
 * Strict bridge decoder for Liquibase requests.
 *
 * Gson cannot instantiate the sealed [DbChange] hierarchy directly. Keeping the
 * discriminator handling here also lets the UI use compact forms while the
 * backend always receives the canonical, typed migration model.
 */
object MigrationJsonParser {
    private val gson = Gson()

    fun parse(root: JsonObject): MigrationModel {
        val author = root.string("author").ifBlank { "jmix-visual-workbench" }
        val migration = MigrationModel(
            changelogId = root.requiredString("changelogId"),
            author = author,
            logicalFilePath = root.optionalString("logicalFilePath"),
            objectQuotingStrategy = root.optionalString("objectQuotingStrategy"),
        )
        root.array("changes").forEachIndexed { index, element ->
            val json = element.asObject("changes[$index]")
            val changeSetAuthor = json.string("author").ifBlank { author }
            val changeSet = ChangeSetModel(
                id = json.requiredString("id"),
                author = changeSetAuthor,
                comment = json.optionalString("comment"),
                context = json.optionalString("context"),
                dbms = json.optionalString("dbms"),
                runOnChange = json.boolean("runOnChange"),
                runAlways = json.boolean("runAlways"),
                runInTransaction = json.booleanOrDefault("runInTransaction", true),
                labels = json.optionalString("labels"),
                preConditionOnFail = json.optionalString("preConditionOnFail")
                    ?.uppercase()
                    ?.let(PreConditionOutcome::valueOf)
                    ?: PreConditionOutcome.HALT,
                preConditionOnError = json.optionalString("preConditionOnError")
                    ?.uppercase()
                    ?.let(PreConditionOutcome::valueOf)
                    ?: PreConditionOutcome.HALT,
                preConditions = json.array("preConditions").mapIndexedTo(mutableListOf()) { pcIndex, pc ->
                    runCatching { gson.fromJson(pc, PreCondition::class.java) }
                        .getOrElse { error("Invalid precondition at changes[$index].preConditions[$pcIndex].") }
                },
            )
            json.array("changes").forEachIndexed { changeIndex, change ->
                changeSet.changes += parseChange(
                    change.asObject("changes[$index].changes[$changeIndex]"),
                    "changes[$index].changes[$changeIndex]",
                )
            }
            json.array("rollback").forEachIndexed { rollbackIndex, change ->
                changeSet.rollback += parseChange(
                    change.asObject("changes[$index].rollback[$rollbackIndex]"),
                    "changes[$index].rollback[$rollbackIndex]",
                )
            }
            migration.changes += changeSet
        }
        require(migration.changes.isNotEmpty()) { "At least one Liquibase changeset is required." }
        return migration
    }

    private fun parseChange(json: JsonObject, location: String): DbChange {
        val type = json.string("changeType").ifBlank { json.string("type") }
        require(type.isNotBlank()) { "A changeType is required at $location." }
        return when (type) {
            "createTable" -> DbChange.CreateTable(
                tableName = json.requiredString("tableName"),
                columns = json.array("columns").mapIndexedTo(mutableListOf()) { index, column ->
                    parseColumn(column.asObject("$location.columns[$index]"), "$location.columns[$index]")
                },
                primaryKey = null,
                remarks = json.optionalString("remarks"),
            )
            "dropTable" -> concrete(json, DbChange.DropTable::class.java)
            "renameTable" -> concrete(json, DbChange.RenameTable::class.java)
            "addColumn" -> {
                val columns = if (json.has("columns")) {
                    json.array("columns").mapIndexedTo(mutableListOf()) { index, column ->
                        parseColumn(column.asObject("$location.columns[$index]"), "$location.columns[$index]")
                    }
                } else {
                    mutableListOf(
                        ColumnDef(
                            name = json.requiredString("columnName"),
                            type = json.requiredString("columnType"),
                            nullable = json.booleanOrDefault("nullable", true),
                        ),
                    )
                }
                DbChange.AddColumn(
                    tableName = json.requiredString("tableName"),
                    columns = columns,
                    schemaName = json.optionalString("schemaName"),
                )
            }
            "dropColumn" -> concrete(json, DbChange.DropColumn::class.java)
            "renameColumn" -> concrete(json, DbChange.RenameColumn::class.java)
            "modifyColumn" -> concrete(json, DbChange.ModifyColumn::class.java)
            "addPrimaryKey" -> concrete(json, DbChange.AddPrimaryKey::class.java)
            "dropPrimaryKey" -> concrete(json, DbChange.DropPrimaryKey::class.java)
            "addForeignKey", "addForeignKeyConstraint" -> DbChange.AddForeignKeyConstraint(
                constraintName = json.optionalString("constraintName")
                    ?: "FK_${json.requiredString("tableName")}_${json.requiredString("column")}",
                baseTableName = json.string("baseTableName").ifBlank { json.requiredString("tableName") },
                baseColumnNames = json.string("baseColumnNames").ifBlank { json.requiredString("column") },
                referencedTableName = json.string("referencedTableName")
                    .ifBlank { json.requiredString("referencedTable") },
                referencedColumnNames = json.string("referencedColumnNames")
                    .ifBlank { json.string("referencedColumn").ifBlank { "ID" } },
                onDelete = json.optionalString("onDelete"),
                onUpdate = json.optionalString("onUpdate"),
            )
            "dropForeignKeyConstraint" -> concrete(json, DbChange.DropForeignKeyConstraint::class.java)
            "addUniqueConstraint" -> concrete(json, DbChange.AddUniqueConstraint::class.java)
            "dropUniqueConstraint" -> concrete(json, DbChange.DropUniqueConstraint::class.java)
            "addNotNullConstraint" -> concrete(json, DbChange.AddNotNullConstraint::class.java)
            "dropNotNullConstraint" -> concrete(json, DbChange.DropNotNullConstraint::class.java)
            "createIndex" -> DbChange.CreateIndex(
                tableName = json.requiredString("tableName"),
                indexName = json.requiredString("indexName"),
                columns = json.array("columns").mapIndexed { index, value ->
                    if (value.isJsonPrimitive) {
                        IndexColumnDef(value.asString)
                    } else {
                        runCatching { gson.fromJson(value, IndexColumnDef::class.java) }
                            .getOrElse { error("Invalid index column at $location.columns[$index].") }
                    }
                },
                unique = json.boolean("unique"),
                tablespace = json.optionalString("tablespace"),
            )
            "dropIndex" -> concrete(json, DbChange.DropIndex::class.java)
            "insertData" -> DbChange.InsertData(
                tableName = json.requiredString("tableName"),
                columns = parseColumnValues(json),
            )
            "updateData" -> concrete(json, DbChange.UpdateData::class.java)
            "deleteData" -> concrete(json, DbChange.DeleteData::class.java)
            "createSequence" -> concrete(json, DbChange.CreateSequence::class.java)
            "dropSequence" -> concrete(json, DbChange.DropSequence::class.java)
            "addAutoIncrement" -> concrete(json, DbChange.AddAutoIncrement::class.java)
            "rawSql" -> DbChange.RawSql(
                sql = json.requiredString("sql"),
                splitStatements = json.booleanOrDefault("splitStatements", true),
                stripComments = json.booleanOrDefault("stripComments", true),
                dbms = json.optionalString("dbms"),
            )
            "rawSqlFile" -> concrete(json, DbChange.RawSqlFile::class.java)
            "tagDatabase" -> concrete(json, DbChange.TagDatabase::class.java)
            "setTableRemarks" -> concrete(json, DbChange.SetTableRemarks::class.java)
            "setColumnRemarks" -> concrete(json, DbChange.SetColumnRemarks::class.java)
            else -> error("Unsupported Liquibase changeType '$type' at $location.")
        }
    }

    private fun parseColumn(json: JsonObject, location: String): ColumnDef {
        val type = json.string("type").ifBlank { json.string("columnType") }
        require(type.isNotBlank()) { "A column type is required at $location." }
        return ColumnDef(
            name = json.requiredString("name"),
            type = type,
            nullable = json.booleanOrDefault("nullable", true),
            defaultValue = json.optionalString("defaultValue"),
            defaultValueComputed = json.optionalString("defaultValueComputed"),
            autoIncrement = json.boolean("autoIncrement"),
            remarks = json.optionalString("remarks"),
            unique = json.boolean("unique"),
            primaryKey = json.boolean("primaryKey"),
        )
    }

    private fun parseColumnValues(json: JsonObject): MutableList<ColumnValueDef> {
        if (json.get("columns")?.isJsonArray == true) {
            return json.array("columns").mapTo(mutableListOf()) { value ->
                gson.fromJson(value, ColumnValueDef::class.java)
            }
        }
        val names = json.string("columns").split(',').map(String::trim).filter(String::isNotBlank)
        val values = json.string("values").split(',').map(String::trim)
        return names.mapIndexedTo(mutableListOf()) { index, name ->
            ColumnValueDef(name = name, value = values.getOrNull(index).orEmpty())
        }
    }

    private fun <T> concrete(json: JsonObject, type: Class<T>): T =
        runCatching { gson.fromJson(json, type) }
            .getOrElse { error("Invalid ${type.simpleName} change: ${it.message}") }

    private fun JsonElement.asObject(location: String): JsonObject {
        require(isJsonObject) { "An object is required at $location." }
        return asJsonObject
    }

    private fun JsonObject.array(name: String): JsonArray =
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray()

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()

    private fun JsonObject.requiredString(name: String): String =
        string(name).also { require(it.isNotBlank()) { "A non-empty '$name' value is required." } }

    private fun JsonObject.optionalString(name: String): String? =
        string(name).takeIf(String::isNotBlank)

    private fun JsonObject.boolean(name: String): Boolean =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false

    private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: default
}

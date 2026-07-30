package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.ChangeSetModel
import org.jmixworkbench.model.ColumnDef
import org.jmixworkbench.model.ColumnValueDef
import org.jmixworkbench.model.DbChange
import org.jmixworkbench.model.MigrationModel
import org.jmixworkbench.model.PreCondition
import org.jmixworkbench.model.PreConditionOutcome
import org.jmixworkbench.model.PreConditionType
import java.util.Locale

/**
 * Builds the non-destructive expansion half of an entity type evolution.
 *
 * The original column is never modified. A deterministic shadow column is
 * added, existing values are copied through a lossless database assignment,
 * and rollback drops only the shadow column. Source cutover and old-column
 * retirement are intentionally separate deployment gates.
 */
@Service(Service.Level.PROJECT)
class EntityAttributeTypeExpansionService(
    private val project: Project,
) {
    fun preview(
        request: EntityAttributeTypeMigrationRequest,
    ): EntityAttributeTypeExpansionPreviewResponse {
        val prepared = build(request)
        val migration = prepared.migration
            ?: return prepared.rejectedPreview()
        val preview = SchemaWorkspaceService.getInstance(project).previewMigration(migration)
        return EntityAttributeTypeExpansionPreviewResponse(
            accepted = preview.accepted,
            code = preview.issues.firstOrNull()?.code,
            message = if (preview.accepted) {
                "Expansion preview is ready. It preserves ${prepared.originalColumnName} and backfills ${prepared.shadowColumnName}."
            } else {
                preview.issues.firstOrNull()?.message ?: "Expansion preview was rejected."
            },
            shadowColumnName = prepared.shadowColumnName,
            targetSqlType = prepared.targetSqlType,
            preview = preview,
        )
    }

    fun prepareApply(
        request: EntityAttributeTypeExpansionApplyRequest,
    ): PreparedWorkspaceChange {
        val prepared = build(request.change)
        val migration = prepared.migration
        if (migration == null) {
            return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "entity-type-expansion:rejected",
                    label = "Entity type expansion rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = listOf(
                        WorkspaceChangeIssue(
                            prepared.code ?: "JVW-ENTITY-TYPE-EXPANSION-REJECTED",
                            prepared.message,
                            request.change.sourceLocator.relativePath,
                        ),
                    ),
                ),
                baseDir = null,
            )
        }
        return SchemaWorkspaceService.getInstance(project).prepareMigration(
            SchemaMigrationApplyRequest(migration, request.expectedPlanDigest),
        )
    }

    internal fun build(
        request: EntityAttributeTypeMigrationRequest,
    ): PreparedEntityAttributeTypeExpansion {
        val described = describe(request)
        val descriptor = described.descriptor
        if (descriptor == null) {
            return PreparedEntityAttributeTypeExpansion.failure(
                described.code ?: "JVW-ENTITY-TYPE-EXPANSION-ANALYSIS-REJECTED",
                described.message,
            )
        }
        val storeId = descriptor.storeId
        val qualifiedTable = descriptor.qualifiedTableName
        val tableName = descriptor.tableName
        val schemaName = descriptor.schemaName
        val originalColumn = descriptor.originalColumnName
        val shadowColumn = descriptor.shadowColumnName
        val targetSqlType = descriptor.targetSqlType
        val workspace = SchemaWorkspaceService.getInstance(project).load()
        val physicalStore = workspace.physicalSchemas.firstOrNull { it.storeId == storeId }
            ?: return PreparedEntityAttributeTypeExpansion.failure(
                "JVW-ENTITY-TYPE-EXPANSION-PHYSICAL-STORE-MISSING",
                "The managed physical schema snapshot is unavailable.",
            )
        if (!physicalStore.complete) {
            return PreparedEntityAttributeTypeExpansion.failure(
                "JVW-ENTITY-TYPE-EXPANSION-PHYSICAL-SCHEMA-PARTIAL",
                "Raw or unresolved migration history makes the physical schema incomplete.",
            )
        }
        val physicalTable = physicalStore.tables.firstOrNull { it.name.equals(tableName, true) }
            ?: return PreparedEntityAttributeTypeExpansion.failure(
                "JVW-ENTITY-TYPE-EXPANSION-PHYSICAL-TABLE-MISSING",
                "Table $qualifiedTable is absent from the managed Liquibase history.",
            )
        if (physicalTable.columns.any { it.name.equals(shadowColumn, true) }) {
            return PreparedEntityAttributeTypeExpansion.failure(
                "JVW-ENTITY-TYPE-EXPANSION-SHADOW-EXISTS",
                "Shadow column $shadowColumn already exists. Refresh and continue the recorded cutover instead of creating it again.",
            )
        }
        val entity = workspace.entities.firstOrNull {
            it.className == request.entityClassName &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        } ?: return PreparedEntityAttributeTypeExpansion.failure(
            "JVW-ENTITY-TYPE-EXPANSION-ENTITY-MISSING",
            "The exact entity snapshot is unavailable.",
        )
        val attribute = entity.attributes.firstOrNull { it.name == request.attributeName }
            ?: return PreparedEntityAttributeTypeExpansion.failure(
                "JVW-ENTITY-TYPE-EXPANSION-ATTRIBUTE-MISSING",
                "The exact attribute snapshot is unavailable.",
            )
        val digest = CanonicalDiscoveryJson.sha256(
            listOf(storeId, tableName, originalColumn, shadowColumn, targetSqlType).joinToString("\u0000"),
        ).take(12)
        val params = mutableMapOf(
            "tableName" to tableName,
            "columnName" to originalColumn,
        )
        val shadowParams = mutableMapOf(
            "tableName" to tableName,
            "columnName" to shadowColumn,
        )
        schemaName?.let {
            params["schemaName"] = it
            shadowParams["schemaName"] = it
        }
        val qualifiedSqlTable = listOfNotNull(schemaName, tableName).joinToString(".")
        val changeSets = mutableListOf(
            ChangeSetModel(
                id = "entity-type-expand-$digest-add",
                comment = "Add a non-destructive expansion shadow for " +
                    "${entity.qualifiedName}.${attribute.name}.",
                preConditions = mutableListOf(
                    PreCondition(PreConditionType.COLUMN_EXISTS, params),
                    PreCondition(PreConditionType.COLUMN_NOT_EXISTS, shadowParams),
                ),
                preConditionOnFail = PreConditionOutcome.HALT,
                preConditionOnError = PreConditionOutcome.HALT,
                changes = mutableListOf(
                    DbChange.AddColumn(
                        tableName = tableName,
                        schemaName = schemaName,
                        columns = mutableListOf(
                            ColumnDef(
                                name = shadowColumn,
                                type = targetSqlType,
                                nullable = true,
                                remarks = "Jmix Visual Workbench expansion shadow for $originalColumn",
                            ),
                        ),
                    ),
                ),
                rollback = mutableListOf(
                    DbChange.DropColumn(
                        tableName = tableName,
                        schemaName = schemaName,
                        columnName = shadowColumn,
                    ),
                ),
            ),
            ChangeSetModel(
                id = "entity-type-expand-$digest-backfill",
                comment = "Idempotently backfill $shadowColumn while $originalColumn remains authoritative.",
                preConditions = mutableListOf(
                    PreCondition(PreConditionType.COLUMN_EXISTS, params),
                    PreCondition(PreConditionType.COLUMN_EXISTS, shadowParams),
                ),
                preConditionOnFail = PreConditionOutcome.HALT,
                preConditionOnError = PreConditionOutcome.HALT,
                runInTransaction = true,
                changes = mutableListOf(
                    DbChange.UpdateData(
                        tableName = tableName,
                        schemaName = schemaName,
                        columns = mutableListOf(
                            ColumnValueDef(name = shadowColumn, valueComputed = originalColumn),
                        ),
                        whereClause = "$shadowColumn IS NULL AND $originalColumn IS NOT NULL",
                    ),
                ),
                rollback = mutableListOf(
                    DbChange.RawSql(
                        sql = "UPDATE $qualifiedSqlTable SET $shadowColumn = NULL",
                    ),
                ),
            ),
        )
        if (!attribute.nullable) {
            val validationParams = mutableMapOf(
                "expectedResult" to "0",
                "sql" to "SELECT COUNT(*) FROM $qualifiedSqlTable WHERE $shadowColumn IS NULL",
            )
            changeSets += ChangeSetModel(
                id = "entity-type-expand-$digest-constraint",
                comment = "Validate the backfill before restoring the mandatory constraint.",
                preConditions = mutableListOf(
                    PreCondition(PreConditionType.COLUMN_EXISTS, shadowParams),
                    PreCondition(PreConditionType.SQL_CHECK, validationParams),
                ),
                preConditionOnFail = PreConditionOutcome.HALT,
                preConditionOnError = PreConditionOutcome.HALT,
                changes = mutableListOf(
                    DbChange.AddNotNullConstraint(
                        tableName = tableName,
                        schemaName = schemaName,
                        columnName = shadowColumn,
                        columnDataType = targetSqlType,
                    ),
                ),
                rollback = mutableListOf(
                    DbChange.DropNotNullConstraint(
                        tableName = tableName,
                        schemaName = schemaName,
                        columnName = shadowColumn,
                    ),
                ),
            )
        }
        val migration = MigrationModel(
            changelogId = "entity-type-expand-$digest",
            changes = changeSets,
        )
        return PreparedEntityAttributeTypeExpansion(
            migration = SchemaMigrationChangeRequest(
                storeId = storeId,
                migration = migration,
                fileName = "entity-type-expand-$digest.xml",
            ),
            code = null,
            message = "Lossless expansion is ready.",
            originalColumnName = originalColumn,
            shadowColumnName = shadowColumn,
            targetSqlType = targetSqlType,
        )
    }

    /**
     * Reconstructs the immutable expansion identity without requiring the
     * shadow column to be absent. This remains valid after a generated
     * changelog has been indexed and is therefore the source of truth for live
     * deployment verification and mapping cutover.
     */
    internal fun describe(
        request: EntityAttributeTypeMigrationRequest,
    ): EntityAttributeTypeExpansionDescription {
        val typeMigration = EntityAttributeRefactorService.getInstance(project)
            .prepareTypeMigration(request)
        if (!typeMigration.accepted) {
            return EntityAttributeTypeExpansionDescription.failure(
                typeMigration.code ?: "JVW-ENTITY-TYPE-EXPANSION-ANALYSIS-REJECTED",
                typeMigration.message,
            )
        }
        val impact = typeMigration.schemaImpact
            ?: return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-IMPACT-MISSING",
                "Physical schema impact is unavailable.",
            )
        if (impact.strategy != EntityAttributeTypeSchemaStrategy.EXPAND_CONTRACT_REQUIRED) {
            return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-NOT-REQUIRED",
                "This change is ${impact.strategy.name.lowercase().replace('_', ' ')} and does not require a managed shadow-column expansion.",
            )
        }
        val currentType = typeMigration.currentType
            ?: return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-CURRENT-TYPE-MISSING",
                "The current scalar type is unavailable.",
            )
        val targetType = typeMigration.targetType
            ?: return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-TARGET-TYPE-MISSING",
                "The target scalar type is unavailable.",
            )
        val targetSqlType = impact.targetSqlType
            ?: return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-SQL-TYPE-MISSING",
                "The target SQL type is unavailable.",
            )
        if (!losslessAssignment(currentType, targetType, targetSqlType)) {
            return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-CONVERSION-REQUIRES-EXPRESSION",
                "$currentType → $targetType is not proven lossless by the portable conversion matrix. " +
                    "Use a reviewed, database-specific conversion expression and validation rehearsal.",
            )
        }
        val storeId = impact.storeId
            ?: return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-STORE-MISSING",
                "The managed data store is unavailable.",
            )
        val qualifiedTable = impact.tableName.orEmpty()
        val tableParts = qualifiedTable.split('.').map(String::trim).filter(String::isNotBlank)
        if (tableParts.isEmpty() || tableParts.size > 2 || tableParts.any { !IDENTIFIER.matches(it) }) {
            return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-TABLE-UNSUPPORTED",
                "Expansion requires a portable TABLE or SCHEMA.TABLE mapping.",
            )
        }
        val tableName = tableParts.last().uppercase(Locale.ROOT)
        val schemaName = tableParts.takeIf { it.size == 2 }?.first()?.uppercase(Locale.ROOT)
        val originalColumn = impact.columnName.orEmpty().uppercase(Locale.ROOT)
        if (!IDENTIFIER.matches(originalColumn)) {
            return EntityAttributeTypeExpansionDescription.failure(
                "JVW-ENTITY-TYPE-EXPANSION-COLUMN-UNSUPPORTED",
                "Expansion requires a portable unquoted source column.",
            )
        }
        return EntityAttributeTypeExpansionDescription(
            descriptor = EntityAttributeTypeExpansionDescriptor(
                storeId = storeId,
                schemaName = schemaName,
                tableName = tableName,
                originalColumnName = originalColumn,
                shadowColumnName = shadowColumnName(tableName, originalColumn, targetSqlType),
                targetSqlType = targetSqlType,
                sourceRevisionFingerprint = request.sourceLocator.revisionFingerprint,
                entityClassName = request.entityClassName,
                attributeName = request.attributeName,
                targetType = request.targetType,
            ),
            code = null,
            message = "Lossless expansion identity is ready.",
        )
    }

    companion object {
        private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        fun getInstance(project: Project): EntityAttributeTypeExpansionService =
            project.getService(EntityAttributeTypeExpansionService::class.java)

        internal fun shadowColumnName(
            tableName: String,
            columnName: String,
            targetSqlType: String,
        ): String {
            val suffix = CanonicalDiscoveryJson.sha256(
                "$tableName\u0000$columnName\u0000${targetSqlType.uppercase(Locale.ROOT)}",
            ).take(7).uppercase(Locale.ROOT)
            val readable = columnName.uppercase(Locale.ROOT)
                .replace(Regex("[^A-Z0-9_]"), "_")
                .take(18)
            return "JVE_${suffix}_$readable".take(30)
        }

        private fun losslessAssignment(
            current: AttributeType,
            target: AttributeType,
            targetSqlType: String,
        ): Boolean = when (current to target) {
            AttributeType.INTEGER to AttributeType.LONG,
            AttributeType.INTEGER to AttributeType.DOUBLE,
            AttributeType.INTEGER to AttributeType.BIG_DECIMAL,
            AttributeType.CHARACTER to AttributeType.STRING -> true
            AttributeType.LONG to AttributeType.BIG_DECIMAL -> {
                val decimal = Regex("""(?i)(?:DECIMAL|NUMERIC)\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
                    .matchEntire(targetSqlType.trim())
                val precision = decimal?.groupValues?.get(1)?.toIntOrNull()
                val scale = decimal?.groupValues?.get(2)?.toIntOrNull()
                precision != null && scale != null && precision - scale >= 19
            }
            else -> false
        }
    }
}

data class PreparedEntityAttributeTypeExpansion(
    val migration: SchemaMigrationChangeRequest?,
    val code: String?,
    val message: String,
    val originalColumnName: String?,
    val shadowColumnName: String?,
    val targetSqlType: String?,
) {
    fun rejectedPreview(): EntityAttributeTypeExpansionPreviewResponse {
        val issue = WorkspaceChangeIssue(
            code ?: "JVW-ENTITY-TYPE-EXPANSION-REJECTED",
            message,
        )
        return EntityAttributeTypeExpansionPreviewResponse(
            accepted = false,
            code = issue.code,
            message = issue.message,
            shadowColumnName = shadowColumnName,
            targetSqlType = targetSqlType,
            preview = WorkspaceChangePreviewResponse(
                accepted = false,
                changeSetId = "entity-type-expansion:rejected",
                label = "Entity type expansion rejected",
                planDigest = null,
                files = emptyList(),
                issues = listOf(issue),
            ),
        )
    }

    companion object {
        fun failure(code: String, message: String) = PreparedEntityAttributeTypeExpansion(
            migration = null,
            code = code,
            message = message,
            originalColumnName = null,
            shadowColumnName = null,
            targetSqlType = null,
        )
    }
}

data class EntityAttributeTypeExpansionPreviewResponse(
    val accepted: Boolean,
    val code: String?,
    val message: String,
    val shadowColumnName: String?,
    val targetSqlType: String?,
    val preview: WorkspaceChangePreviewResponse,
)

data class EntityAttributeTypeExpansionApplyRequest(
    val change: EntityAttributeTypeMigrationRequest,
    val expectedPlanDigest: String,
)

data class EntityAttributeTypeExpansionDescriptor(
    val storeId: String,
    val schemaName: String?,
    val tableName: String,
    val originalColumnName: String,
    val shadowColumnName: String,
    val targetSqlType: String,
    val sourceRevisionFingerprint: String,
    val entityClassName: String,
    val attributeName: String,
    val targetType: AttributeType,
) {
    val qualifiedTableName: String
        get() = listOfNotNull(schemaName, tableName).joinToString(".")
}

data class EntityAttributeTypeExpansionDescription(
    val descriptor: EntityAttributeTypeExpansionDescriptor?,
    val code: String?,
    val message: String,
) {
    companion object {
        fun failure(code: String, message: String) =
            EntityAttributeTypeExpansionDescription(null, code, message)
    }
}

package org.jmixworkbench.services

import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EntityType
import org.jmixworkbench.model.IdType
import java.sql.DatabaseMetaData
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseEntityImportPlannerTest {
    @Test
    fun `plans dependency closure and composite foreign key without losing column pairs`() {
        val branch = table(
            name = "BRANCH",
            columns = listOf(
                column("BANK_CODE", Types.VARCHAR, nullable = false),
                column("BRANCH_CODE", Types.VARCHAR, nullable = false),
                column("NAME", Types.VARCHAR, nullable = false),
            ),
            primaryKey = listOf("BANK_CODE", "BRANCH_CODE"),
        )
        val account = table(
            name = "ACCOUNT",
            columns = listOf(
                column("ID", Types.BIGINT, nullable = false, autoIncrement = true),
                column("BANK_CODE", Types.VARCHAR, nullable = false),
                column("BRANCH_CODE", Types.VARCHAR, nullable = false),
                column("BALANCE", Types.DECIMAL, nullable = false, size = 19, scale = 2),
            ),
            primaryKey = listOf("ID"),
            foreignKeys = listOf(
                foreignKey("FK_ACCOUNT_BRANCH", "BANK_CODE", "BRANCH", "BANK_CODE", 1),
                foreignKey("FK_ACCOUNT_BRANCH", "BRANCH_CODE", "BRANCH", "BRANCH_CODE", 2),
            ),
        )

        val plan = plan(
            imported(account, selected = true),
            imported(branch, requiredBy = setOf("PUBLIC.ACCOUNT")),
        )

        assertTrue(plan.ready)
        assertEquals(3, plan.entities.size)
        val branchEntity = plan.entities.single { it.className == "Branch" }
        assertEquals(IdType.EMBEDDED, branchEntity.id.type)
        assertEquals("test.domain.BranchId", branchEntity.id.embeddedIdClass)
        assertTrue(
            plan.entities.single {
                it.className == "BranchId"
            }.embeddableIdentity,
        )
        val association = plan.entities.single { it.className == "Account" }
            .attributes.single { it.association != null }
            .association
        assertNotNull(association)
        assertEquals(AssociationType.MANY_TO_ONE, association.associationType)
        assertEquals(
            listOf("BANK_CODE" to "BANK_CODE", "BRANCH_CODE" to "BRANCH_CODE"),
            association.joinColumns.map { it.name to it.referencedColumnName },
        )
        assertEquals(
            listOf("PUBLIC.ACCOUNT"),
            plan.tables.single { it.table.name == "BRANCH" }.requiredBy,
        )
    }

    @Test
    fun `recognizes only strict pure join tables and creates bidirectional many to many`() {
        val employee = table(
            "EMPLOYEE",
            listOf(column("ID", Types.BIGINT, false)),
            listOf("ID"),
        )
        val project = table(
            "PROJECT",
            listOf(column("ID", Types.BIGINT, false)),
            listOf("ID"),
        )
        val assignment = table(
            name = "EMPLOYEE_PROJECT",
            columns = listOf(
                column("EMPLOYEE_ID", Types.BIGINT, false),
                column("PROJECT_ID", Types.BIGINT, false),
            ),
            primaryKey = listOf("EMPLOYEE_ID", "PROJECT_ID"),
            foreignKeys = listOf(
                foreignKey("FK_ASSIGNMENT_EMPLOYEE", "EMPLOYEE_ID", "EMPLOYEE", "ID"),
                foreignKey("FK_ASSIGNMENT_PROJECT", "PROJECT_ID", "PROJECT", "ID"),
            ),
        )

        val plan = plan(
            imported(employee, selected = true),
            imported(project, requiredBy = setOf("PUBLIC.EMPLOYEE_PROJECT")),
            imported(assignment, selected = true),
        )

        assertTrue(plan.ready)
        assertEquals(
            DatabaseEntityImportStatus.JOIN_TABLE,
            plan.tables.single { it.table.name == "EMPLOYEE_PROJECT" }.status,
        )
        assertFalse(plan.entities.any { it.className == "EmployeeProject" })
        val ownerAssociation = plan.entities
            .filter { it.entityType == EntityType.ENTITY }
            .flatMap { it.attributes }
            .single { it.association?.joinTable?.name == "EMPLOYEE_PROJECT" }
            .association
        assertNotNull(ownerAssociation)
        assertEquals(AssociationType.MANY_TO_MANY, ownerAssociation.associationType)
        assertEquals("PUBLIC", ownerAssociation.joinTable?.schema)
        assertEquals("EMPLOYEE_ID", ownerAssociation.joinTable?.joinColumns?.single()?.name)
        assertEquals("PROJECT_ID", ownerAssociation.joinTable?.inverseJoinColumns?.single()?.name)
        assertTrue(
            plan.entities
                .filter { it.entityType == EntityType.ENTITY }
                .flatMap { it.attributes }
                .any { it.association?.mappedBy != null },
        )
    }

    @Test
    fun `blocks tables without primary key but allows views with explicit identifiers`() {
        val audit = table(
            "AUDIT_LOG",
            listOf(column("MESSAGE", Types.VARCHAR, false)),
            emptyList(),
        )
        val view = table(
            "ACTIVE_LOAN",
            listOf(
                column("LOAN_NO", Types.VARCHAR, false),
                column("AMOUNT", Types.DECIMAL, false, size = 19, scale = 2),
            ),
            emptyList(),
            type = "VIEW",
        )

        val blocked = plan(imported(audit, selected = true))
        assertFalse(blocked.ready)
        assertEquals(DatabaseEntityImportStatus.BLOCKED, blocked.tables.single().status)
        assertTrue(blocked.issues.any { it.code == "JVW-DB-IMPORT-IDENTIFIER-MISSING" })

        val viewPlan = plan(
            imported(view, selected = true),
            request = request(
                identifierOverrides = mapOf("PUBLIC.ACTIVE_LOAN" to listOf("LOAN_NO")),
            ),
        )
        assertTrue(viewPlan.ready)
        assertEquals(DatabaseEntityImportStatus.VIEW, viewPlan.tables.single().status)
        assertEquals(IdType.STRING, viewPlan.entities.single().id.type)
        assertTrue(viewPlan.entities.single().databaseView)
    }

    @Test
    fun `schema snapshot digest is deterministic and changes with metadata`() {
        val initial = table(
            "LOAN",
            listOf(column("ID", Types.BIGINT, false)),
            listOf("ID"),
        )
        val sameA = plan(imported(initial, selected = true))
        val sameB = plan(imported(initial, selected = true))
        val changed = plan(
            imported(
                initial.copy(
                    columns = initial.columns + column("STATUS", Types.VARCHAR, false),
                ),
                selected = true,
            ),
        )

        assertEquals(sameA.snapshotDigest, sameB.snapshotDigest)
        assertNotEquals(sameA.snapshotDigest, changed.snapshotDigest)
    }

    @Test
    fun `blocks ambiguous unnamed foreign key metadata instead of flattening relationships`() {
        val reference = table(
            "REFERENCE_DATA",
            listOf(column("ID", Types.BIGINT, false)),
            listOf("ID"),
        )
        val source = table(
            name = "SOURCE_DATA",
            columns = listOf(
                column("ID", Types.BIGINT, false),
                column("PRIMARY_REF_ID", Types.BIGINT, false),
                column("SECONDARY_REF_ID", Types.BIGINT, false),
            ),
            primaryKey = listOf("ID"),
            foreignKeys = listOf(
                foreignKey("", "PRIMARY_REF_ID", "REFERENCE_DATA", "ID"),
                foreignKey("", "SECONDARY_REF_ID", "REFERENCE_DATA", "ID"),
            ).map { it.copy(name = null) },
        )

        val plan = plan(
            imported(source, selected = true),
            imported(reference, requiredBy = setOf("PUBLIC.SOURCE_DATA")),
        )

        assertFalse(plan.ready)
        assertTrue(plan.issues.any { it.code == "JVW-DB-IMPORT-FK-METADATA-AMBIGUOUS" })
        assertEquals(
            DatabaseEntityImportStatus.BLOCKED,
            plan.tables.single { it.table.name == "SOURCE_DATA" }.status,
        )
    }

    @Test
    fun `maps computed database columns as persistence read only`() {
        val balance = table(
            "ACCOUNT_BALANCE",
            listOf(
                column("ID", Types.BIGINT, false),
                column("AVAILABLE_BALANCE", Types.DECIMAL, false, size = 19, scale = 2)
                    .copy(generated = true),
            ),
            listOf("ID"),
        )

        val plan = plan(imported(balance, selected = true))

        assertTrue(plan.ready)
        val generated = plan.entities.single().attributes.single()
        assertEquals(AttributeType.BIG_DECIMAL, generated.type)
        assertTrue(generated.readOnly)
        assertFalse(generated.mandatory)
    }

    private fun plan(
        vararg imported: DatabaseImportedTable,
        request: DatabaseEntityImportRequest = request(),
    ) = DatabaseEntityImportPlanner.plan(
        request = request,
        store = SchemaDataStoreSnapshot(
            id = "main",
            name = "main",
            moduleId = "app",
            configuredPath = "src/main/resources/application.properties",
            configurationLocator = null,
            rootChangelogPath = null,
            rootLocator = null,
            includeMode = SchemaIncludeMode.MISSING,
            includeTargets = emptyList(),
            generatedDirectory = null,
        ),
        database = DatabaseProductSnapshot(
            name = "PostgreSQL",
            version = "17",
            driverName = "PostgreSQL JDBC Driver",
            driverVersion = "42",
            urlFingerprint = "sha256:test",
        ),
        importedTables = imported.toList(),
    )

    private fun request(
        identifierOverrides: Map<String, List<String>> = emptyMap(),
    ) = DatabaseEntityImportRequest(
        storeId = "main",
        moduleId = "app",
        packageName = "test.domain",
        sourceLanguage = EntitySourceLanguage.JAVA,
        selectedTables = listOf(
            DatabaseTableReference(
                catalog = null,
                schema = "PUBLIC",
                name = "ACCOUNT",
                type = "TABLE",
                remarks = null,
            ),
        ),
        identifierOverrides = identifierOverrides,
    )

    private fun imported(
        table: DatabaseTableSnapshot,
        selected: Boolean = false,
        requiredBy: Set<String> = emptySet(),
    ) = DatabaseImportedTable(
        table = table,
        selectedByUser = selected,
        requiredBy = requiredBy,
        existingEntity = null,
    )

    private fun table(
        name: String,
        columns: List<DatabaseColumnSnapshot>,
        primaryKey: List<String>,
        foreignKeys: List<DatabaseForeignKeySnapshot> = emptyList(),
        type: String = "TABLE",
    ) = DatabaseTableSnapshot(
        catalog = null,
        schema = "PUBLIC",
        name = name,
        type = type,
        remarks = null,
        columns = columns,
        primaryKeyColumns = primaryKey,
        foreignKeys = foreignKeys,
        indexes = emptyList(),
        dependencyTables = foreignKeys.map { it.referencedTableName }.distinct(),
    )

    private fun column(
        name: String,
        jdbcType: Int,
        nullable: Boolean,
        size: Int? = if (jdbcType == Types.VARCHAR) 255 else null,
        scale: Int? = null,
        autoIncrement: Boolean = false,
    ) = DatabaseColumnSnapshot(
        name = name,
        jdbcType = jdbcType,
        typeName = when (jdbcType) {
            Types.VARCHAR -> "varchar"
            Types.BIGINT -> "int8"
            Types.DECIMAL -> "numeric"
            else -> "unknown"
        },
        size = size,
        scale = scale,
        nullable = nullable,
        defaultValue = null,
        remarks = null,
        autoIncrement = autoIncrement,
        generated = false,
        ordinal = 1,
    )

    private fun foreignKey(
        name: String,
        column: String,
        targetTable: String,
        targetColumn: String,
        sequence: Int = 1,
    ) = DatabaseForeignKeySnapshot(
        name = name,
        columnName = column,
        referencedCatalog = null,
        referencedSchema = "PUBLIC",
        referencedTableName = targetTable,
        referencedColumnName = targetColumn,
        updateRule = DatabaseMetaData.importedKeyNoAction,
        deleteRule = DatabaseMetaData.importedKeyNoAction,
        sequence = sequence,
    )
}

package org.jmixworkbench.services

import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.IdType
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseReverseEngineeringServiceTest {
    @Test
    fun `database scalar metadata maps to explicit Jmix attribute shapes`() {
        val text = DatabaseTypeMapper.suggest(
            column = column("APPLICATION_NO", Types.VARCHAR, "varchar", size = 40, nullable = false),
            primaryKey = false,
            foreignKey = null,
            relatedEntity = null,
        )
        assertEquals("applicationNo", text.attributeName)
        assertEquals(AttributeType.STRING, text.attributeType)
        assertEquals(40, text.length)
        assertTrue(text.mandatory)

        val money = DatabaseTypeMapper.suggest(
            column = column("APPROVED_AMOUNT", Types.DECIMAL, "numeric", size = 19, scale = 2),
            primaryKey = false,
            foreignKey = null,
            relatedEntity = null,
        )
        assertEquals(AttributeType.BIG_DECIMAL, money.attributeType)
        assertEquals("java.math.BigDecimal", money.javaType)
        assertEquals(19, money.precision)
        assertEquals(2, money.scale)

        val oracleBigIntPrimaryKey = DatabaseTypeMapper.suggest(
            column = column("ID", Types.NUMERIC, "NUMBER", size = 19, scale = 0, nullable = false),
            primaryKey = true,
            foreignKey = null,
            relatedEntity = null,
        )
        assertEquals(AttributeType.LONG, oracleBigIntPrimaryKey.attributeType)
        assertEquals("java.lang.Long", oracleBigIntPrimaryKey.javaType)
        assertNull(oracleBigIntPrimaryKey.precision)
        assertNull(oracleBigIntPrimaryKey.scale)

        val oracleLiquibaseBigIntPrimaryKey = DatabaseTypeMapper.suggest(
            column = column("ID", Types.NUMERIC, "NUMBER", size = 38, scale = 0, nullable = false),
            primaryKey = true,
            foreignKey = null,
            relatedEntity = null,
        )
        assertEquals(AttributeType.LONG, oracleLiquibaseBigIntPrimaryKey.attributeType)
        assertEquals("java.lang.Long", oracleLiquibaseBigIntPrimaryKey.javaType)

        val oracleArbitraryPrecisionValue = DatabaseTypeMapper.suggest(
            column = column("EXTERNAL_NUMBER", Types.NUMERIC, "NUMBER", size = 38, scale = 0),
            primaryKey = false,
            foreignKey = null,
            relatedEntity = null,
        )
        assertEquals(AttributeType.BIG_DECIMAL, oracleArbitraryPrecisionValue.attributeType)

        val uuid = DatabaseTypeMapper.suggest(
            column = column("EXTERNAL_ID", Types.OTHER, "uuid"),
            primaryKey = false,
            foreignKey = null,
            relatedEntity = null,
        )
        assertEquals(AttributeType.UUID, uuid.attributeType)
        assertNull(uuid.unsupportedReason)
    }

    @Test
    fun `known foreign key maps to a named many-to-one candidate`() {
        val foreignKey = DatabaseForeignKeySnapshot(
            name = "FK_LOAN_APP_EMPLOYEE",
            columnName = "EMPLOYEE_ID",
            referencedCatalog = null,
            referencedSchema = "public",
            referencedTableName = "EMPLOYEE",
            referencedColumnName = "ID",
            updateRule = 3,
            deleteRule = 3,
            sequence = 1,
        )
        val related = SchemaEntitySnapshot(
            artifactId = "entity:employee",
            moduleId = "hr",
            className = "Employee",
            qualifiedName = "com.company.hr.entity.Employee",
            entityName = "hr_Employee",
            tableName = "EMPLOYEE",
            storeName = "main",
            idType = IdType.UUID,
            idColumnName = "ID",
            databaseView = false,
            ddlMode = SchemaDdlMode.CREATE_AND_DROP,
            sourceLocator = SourceLocator(
                relativePath = "hr/src/main/java/com/company/hr/entity/Employee.java",
                revisionFingerprint = "employee-revision",
            ),
            attributes = emptyList(),
            migrationCoverage = SchemaMigrationCoverage.COVERED,
            migrationArtifactIds = emptyList(),
        )

        val association = DatabaseTypeMapper.suggest(
            column = column("EMPLOYEE_ID", Types.OTHER, "uuid", nullable = false),
            primaryKey = false,
            foreignKey = foreignKey,
            relatedEntity = related,
        )

        assertEquals(AttributeType.ASSOCIATION, association.attributeType)
        assertEquals("employee", association.attributeName)
        assertEquals(related.qualifiedName, association.relatedEntity)
        assertEquals("EMPLOYEE_ID", association.joinColumnName)
        assertEquals("EMPLOYEE", association.foreignKeyTable)
        assertEquals("ID", association.referencedColumnName)
    }

    @Test
    fun `vendor-specific type fails closed until developer chooses a datatype`() {
        val custom = DatabaseTypeMapper.suggest(
            column = column("SEARCH_VECTOR", Types.OTHER, "tsvector"),
            primaryKey = false,
            foreignKey = null,
            relatedEntity = null,
        )

        assertEquals(AttributeType.CUSTOM, custom.attributeType)
        assertEquals("tsvector", custom.customSqlType)
        assertTrue(custom.unsupportedReason?.contains("explicit Java/custom datatype") == true)
    }

    @Test
    fun `database failures redact connection and credential material`() {
        val message = redactDatabaseError(
            IllegalStateException(
                "Cannot connect jdbc:postgresql://bank.internal/payroll?password=visible " +
                    "username=payroll_owner password=another-secret",
            ),
        )

        assertFalse(message.contains("bank.internal"))
        assertFalse(message.contains("payroll_owner"))
        assertFalse(message.contains("another-secret"))
        assertFalse(message.contains("visible"))
        assertTrue(message.contains("jdbc:<redacted>"))
    }

    @Test
    fun `live cutover type evidence accepts capacity preserving shapes only`() {
        assertTrue(
            DatabaseSqlTypeCompatibility.accepts(
                "BIGINT",
                column("SHADOW_LONG", Types.BIGINT, "int8"),
            ),
        )
        assertTrue(
            DatabaseSqlTypeCompatibility.accepts(
                "DECIMAL(19, 2)",
                column("SHADOW_AMOUNT", Types.NUMERIC, "numeric", size = 24, scale = 2),
            ),
        )
        assertFalse(
            DatabaseSqlTypeCompatibility.accepts(
                "DECIMAL(19, 2)",
                column("SHADOW_AMOUNT", Types.NUMERIC, "numeric", size = 18, scale = 2),
            ),
        )
        assertFalse(
            DatabaseSqlTypeCompatibility.accepts(
                "DECIMAL(19, 2)",
                column("SHADOW_AMOUNT", Types.NUMERIC, "numeric", size = 24, scale = 4),
            ),
        )
        assertTrue(
            DatabaseSqlTypeCompatibility.accepts(
                "VARCHAR(255)",
                column("SHADOW_TEXT", Types.VARCHAR, "varchar", size = 512),
            ),
        )
        assertFalse(
            DatabaseSqlTypeCompatibility.accepts(
                "VARCHAR(255)",
                column("SHADOW_TEXT", Types.VARCHAR, "varchar", size = 120),
            ),
        )
        assertEquals(
            "SELECT COUNT(*) FROM \"PUBLIC\".\"LOAN_APP\" WHERE \"RISK_SCORE\" IS NOT NULL " +
                "AND (\"JVE_SHADOW\" IS NULL OR \"JVE_SHADOW\" <> \"RISK_SCORE\")",
            DatabaseBackfillVerificationSql.query(
                "\"PUBLIC\".\"LOAN_APP\"",
                "\"RISK_SCORE\"",
                "\"JVE_SHADOW\"",
            ),
        )
    }

    private fun column(
        name: String,
        jdbcType: Int,
        typeName: String,
        size: Int? = null,
        scale: Int? = null,
        nullable: Boolean = true,
    ) = DatabaseColumnSnapshot(
        name = name,
        jdbcType = jdbcType,
        typeName = typeName,
        size = size,
        scale = scale,
        nullable = nullable,
        defaultValue = null,
        remarks = null,
        autoIncrement = false,
        generated = false,
        ordinal = 1,
    )
}

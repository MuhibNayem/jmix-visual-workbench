package org.jmixworkbench.services

import com.google.gson.Gson
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.sql.ResultSet
import java.sql.Types
import java.util.Properties
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseReverseEngineeringIntegrationTest : HeavyPlatformTestCase() {
    fun testActiveProfileConnectionInspectsExistingEntityWithoutLeakingSecrets() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.first()
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                model.addContentEntry(root)
                model.commit()
            }
            write(root, "build.gradle.kts", """plugins { id("io.jmix") version "2.7.2" }""")
            write(
                root,
                "src/main/resources/application.properties",
                """
                spring.profiles.active=dev
                main.datasource.url=jdbc:invalid:default
                main.datasource.driver-class-name=${MetadataDriver::class.java.name}
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/application-dev.properties",
                """
                main.datasource.url=jdbc:jvw-fixture:payroll
                main.datasource.username=${'$'}{JVW_FIXTURE_USER:fixture-user}
                main.datasource.password=${'$'}{JVW_FIXTURE_PASSWORD:fixture-secret}
                main.datasource.driver-class-name=${MetadataDriver::class.java.name}
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/company/loan/entity/LoanApp.java",
                """
                package com.company.loan.entity;

                import io.jmix.core.metamodel.annotation.JmixEntity;
                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import java.util.UUID;

                @JmixEntity
                @Entity(name = "loan_LoanApp")
                @Table(name = "LOAN_APP")
                public class LoanApp {
                    @Id
                    @Column(name = "ID", nullable = false)
                    private UUID id;

                    @Column(name = "APPLICATION_NO", nullable = false, length = 40)
                    private String applicationNo;
                }
                """.trimIndent(),
            )
        }

        val workspace = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val entity = workspace.entities.firstOrNull {
            it.qualifiedName == "com.company.loan.entity.LoanApp"
        } ?: error("Fixture entity was not indexed: ${workspace.entities.map { it.qualifiedName }}")
        val store = workspace.stores.firstOrNull {
            it.moduleId == entity.moduleId && it.name == entity.storeName
        } ?: error("Fixture store was not indexed: ${workspace.stores}")

        val service = DatabaseReverseEngineeringService.getInstance(project)
        val ambiguous = service.inspectEntityTable(
            DatabaseEntityTableInspectionRequest(
                storeId = store.id,
                tableName = "LOAN_APP",
            ),
        )
        assertFalse(ambiguous.accepted)
        assertEquals("JVW-DB-TABLE-AMBIGUOUS", ambiguous.issues.single().code)

        val response = service.inspectEntityTable(
            DatabaseEntityTableInspectionRequest(
                storeId = store.id,
                tableName = "LOAN_APP",
                schemaName = "public",
            ),
        )

        assertTrue(response.accepted, response.issues.toString())
        assertEquals("jdbc:jvw-fixture:payroll", MetadataDriver.lastUrl)
        assertEquals("fixture-user", MetadataDriver.lastProperties?.getProperty("user"))
        assertEquals("fixture-secret", MetadataDriver.lastProperties?.getProperty("password"))
        assertEquals(entity.qualifiedName, response.existingEntityQualifiedName)
        assertEquals("FixtureDB", response.database?.name)
        assertEquals(2, response.table?.columns?.count { it.alreadyMapped })
        val approvedAmount = response.table?.columns?.firstOrNull { it.name == "APPROVED_AMOUNT" }
            ?: error("Live metadata column was not returned: $response")
        assertFalse(approvedAmount.alreadyMapped)
        assertEquals(19, approvedAmount.suggestion.precision)
        assertEquals(2, approvedAmount.suggestion.scale)
        assertTrue(response.snapshotDigest?.length == 64)

        val serialized = Gson().toJson(response)
        assertFalse(serialized.contains("fixture-secret"))
        assertFalse(serialized.contains("fixture-user"))
        assertFalse(serialized.contains("jdbc:jvw-fixture:payroll"))
    }

    override fun tearDown() {
        MetadataDriver.lastUrl = null
        MetadataDriver.lastProperties = null
        super.tearDown()
    }

    class MetadataDriver : Driver {
        override fun connect(url: String?, info: Properties?): Connection? {
            if (!acceptsURL(url)) return null
            lastUrl = url
            lastProperties = Properties().apply { if (info != null) putAll(info) }
            return fixtureConnection()
        }

        override fun acceptsURL(url: String?): Boolean = url?.startsWith("jdbc:jvw-fixture:") == true
        override fun getPropertyInfo(url: String?, info: Properties?) = emptyArray<DriverPropertyInfo>()
        override fun getMajorVersion() = 1
        override fun getMinorVersion() = 0
        override fun jdbcCompliant() = false
        override fun getParentLogger(): Logger = Logger.getLogger("jvw-fixture")

        companion object {
            var lastUrl: String? = null
            var lastProperties: Properties? = null
        }
    }

    companion object {
        private fun fixtureConnection(): Connection {
            lateinit var connection: Connection
            connection = proxy(Connection::class.java) { method, _ ->
                when (method.name) {
                    "getMetaData" -> fixtureMetadata()
                    "getCatalog" -> null
                    "isClosed" -> false
                    "getAutoCommit" -> false
                    "isReadOnly" -> true
                    "close", "setReadOnly", "setAutoCommit", "setNetworkTimeout", "rollback" -> null
                    "unwrap" -> connection
                    "isWrapperFor" -> false
                    else -> defaultValue(method.returnType)
                }
            }
            return connection
        }

        private fun fixtureMetadata(): DatabaseMetaData = proxy(DatabaseMetaData::class.java) { method, args ->
            when (method.name) {
                "getDatabaseProductName" -> "FixtureDB"
                "getDatabaseProductVersion" -> "17.2"
                "getDriverName" -> "Jmix Workbench Metadata Driver"
                "getDriverVersion" -> "1.0"
                "getTables" -> {
                    val schema = args?.get(1) as? String
                    val pattern = args?.get(2) as? String
                    resultSet(
                        if (pattern.equals("LOAN_APP", ignoreCase = true)) {
                            buildList {
                                add(
                                    mapOf(
                                        "TABLE_CAT" to null,
                                        "TABLE_SCHEM" to "public",
                                        "TABLE_NAME" to "LOAN_APP",
                                        "TABLE_TYPE" to "TABLE",
                                        "REMARKS" to "Loan applications",
                                    ),
                                )
                                if (schema == null) {
                                    add(
                                        mapOf(
                                            "TABLE_CAT" to null,
                                            "TABLE_SCHEM" to "archive",
                                            "TABLE_NAME" to "LOAN_APP",
                                            "TABLE_TYPE" to "TABLE",
                                            "REMARKS" to "Archived loan applications",
                                        ),
                                    )
                                }
                            }
                        } else {
                            emptyList()
                        },
                    )
                }
                "getColumns" -> resultSet(
                    listOf(
                        columnRow("ID", Types.OTHER, "uuid", 36, 0, false, 1),
                        columnRow("APPLICATION_NO", Types.VARCHAR, "varchar", 40, 0, false, 2),
                        columnRow("APPROVED_AMOUNT", Types.DECIMAL, "numeric", 19, 2, true, 3),
                    ),
                )
                "getPrimaryKeys" -> resultSet(listOf(mapOf("COLUMN_NAME" to "ID")))
                "getImportedKeys" -> resultSet(emptyList())
                "getIndexInfo" -> resultSet(
                    listOf(
                        mapOf(
                            "INDEX_NAME" to "UQ_LOAN_APP_NUMBER",
                            "COLUMN_NAME" to "APPLICATION_NO",
                            "ORDINAL_POSITION" to 1.toShort(),
                            "NON_UNIQUE" to false,
                        ),
                    ),
                )
                else -> defaultValue(method.returnType)
            }
        }

        private fun columnRow(
            name: String,
            jdbcType: Int,
            typeName: String,
            size: Int,
            scale: Int,
            nullable: Boolean,
            ordinal: Int,
        ): Map<String, Any?> = mapOf(
            "COLUMN_NAME" to name,
            "DATA_TYPE" to jdbcType,
            "TYPE_NAME" to typeName,
            "COLUMN_SIZE" to size,
            "DECIMAL_DIGITS" to scale,
            "NULLABLE" to if (nullable) DatabaseMetaData.columnNullable else DatabaseMetaData.columnNoNulls,
            "COLUMN_DEF" to null,
            "REMARKS" to null,
            "IS_AUTOINCREMENT" to "NO",
            "IS_GENERATEDCOLUMN" to "NO",
            "ORDINAL_POSITION" to ordinal,
        )

        private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
            var index = -1
            var wasNull = false
            lateinit var result: ResultSet
            result = proxy(ResultSet::class.java) { method, args ->
                when (method.name) {
                    "next" -> (++index) < rows.size
                    "close" -> null
                    "wasNull" -> wasNull
                    "getString", "getInt", "getShort", "getBoolean" -> {
                        val key = args?.firstOrNull()?.toString().orEmpty()
                        val value = rows.getOrNull(index)?.get(key)
                        wasNull = value == null
                        when (method.name) {
                            "getString" -> value?.toString()
                            "getInt" -> (value as? Number)?.toInt() ?: 0
                            "getShort" -> (value as? Number)?.toShort() ?: 0.toShort()
                            else -> value as? Boolean ?: false
                        }
                    }
                    "unwrap" -> result
                    "isWrapperFor" -> false
                    else -> defaultValue(method.returnType)
                }
            }
            return result
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, handler: (Method, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
                InvocationHandler { _, method, arguments ->
                    when (method.name) {
                        "toString" -> "JmixWorkbenchFixture${type.simpleName}"
                        "hashCode" -> System.identityHashCode(type)
                        "equals" -> false
                        else -> handler(method, arguments)
                    }
                },
            ) as T

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }

        private fun write(
            root: com.intellij.openapi.vfs.VirtualFile,
            path: String,
            content: String,
        ) {
            val file = VfsUtil.createDirectories("${root.path}/${path.substringBeforeLast('/', "")}")
                .findOrCreateChildData(this, path.substringAfterLast('/'))
            VfsUtil.saveText(file, content)
        }
    }
}

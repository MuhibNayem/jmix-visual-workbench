package org.jmixworkbench.services

import com.google.gson.JsonParser
import org.jmixworkbench.model.DbChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MigrationJsonParserTest {
    @Test
    fun `compact visual editor payload is decoded into canonical sealed changes and rollback`() {
        val payload = JsonParser.parseString(
            """
            {
              "changelogId": "loan-update",
              "author": "payroll-team",
              "changes": [{
                "id": "loan-2026-01",
                "author": "payroll-team",
                "changes": [
                  {"changeType":"addColumn","tableName":"LOAN_APP","columnName":"STATUS","columnType":"VARCHAR(32)","nullable":false},
                  {"changeType":"addForeignKey","tableName":"LOAN_APP","column":"EMPLOYEE_ID","referencedTable":"EMPLOYEE","referencedColumn":"ID","onDelete":"RESTRICT"},
                  {"changeType":"createIndex","tableName":"LOAN_APP","indexName":"IDX_LOAN_STATUS","columns":["STATUS"],"unique":false}
                ],
                "rollback": [
                  {"changeType":"dropColumn","tableName":"LOAN_APP","columnName":"STATUS"}
                ]
              }]
            }
            """.trimIndent(),
        ).asJsonObject

        val migration = MigrationJsonParser.parse(payload)
        val changeSet = migration.changes.single()
        val addColumn = assertIs<DbChange.AddColumn>(changeSet.changes[0])
        assertEquals("STATUS", addColumn.columns.single().name)
        assertEquals(false, addColumn.columns.single().nullable)
        val foreignKey = assertIs<DbChange.AddForeignKeyConstraint>(changeSet.changes[1])
        assertEquals("FK_LOAN_APP_EMPLOYEE_ID", foreignKey.constraintName)
        assertEquals("EMPLOYEE", foreignKey.referencedTableName)
        val index = assertIs<DbChange.CreateIndex>(changeSet.changes[2])
        assertEquals("STATUS", index.columns.single().name)
        assertTrue(changeSet.rollback.single() is DbChange.DropColumn)
    }
}

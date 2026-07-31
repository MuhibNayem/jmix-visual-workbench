package org.jmixworkbench.generator

import org.jmixworkbench.model.ChangeSetModel
import org.jmixworkbench.model.ColumnDef
import org.jmixworkbench.model.DbChange
import org.jmixworkbench.model.MigrationModel
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MigrationGeneratorTest {
    @Test
    fun `constraints rollback and portable attributes are emitted as valid Liquibase XML`() {
        val migration = MigrationModel(
            changelogId = "loan-schema",
            logicalFilePath = "com/company/loan/liquibase/changelog/loan-schema.xml",
            changes = mutableListOf(
                ChangeSetModel(
                    id = "loan-1",
                    author = "team",
                    comment = "Create the certified loan schema",
                    labels = "loan",
                    changes = mutableListOf(
                        DbChange.CreateTable(
                            tableName = "LOAN_APP",
                            columns = mutableListOf(
                                ColumnDef("ID", "UUID", nullable = false, primaryKey = true),
                                ColumnDef("APPLICATION_NO", "VARCHAR(64)", nullable = false, unique = true),
                            ),
                        ),
                    ),
                    rollback = mutableListOf(DbChange.DropTable("LOAN_APP", cascadeConstraints = true)),
                ),
            ),
        )

        val xml = MigrationGenerator.generate(migration)
        val document = parse(xml)
        val root = document.documentElement
        assertEquals(migration.logicalFilePath, root.getAttribute("logicalFilePath"))
        val columns = document.getElementsByTagName("column")
        val applicationNo = (0 until columns.length)
            .map { columns.item(it) as org.w3c.dom.Element }
            .first { it.getAttribute("name") == "APPLICATION_NO" }
        assertFalse(applicationNo.hasAttribute("nullable"))
        assertFalse(applicationNo.hasAttribute("unique"))
        val constraints = applicationNo.getElementsByTagName("constraints").item(0) as? org.w3c.dom.Element
        assertNotNull(constraints)
        assertEquals("false", constraints.getAttribute("nullable"))
        assertEquals("true", constraints.getAttribute("unique"))
        assertEquals(1, document.getElementsByTagName("rollback").length)
        val dropTable = document.getElementsByTagName("dropTable").item(0) as? org.w3c.dom.Element
        assertNotNull(dropTable)
        assertEquals("true", dropTable.getAttribute("cascadeConstraints"))
        assertTrue(xml.contains("""labels="loan""""))
        val changeSet = document.getElementsByTagName("changeSet").item(0) as org.w3c.dom.Element
        assertFalse(changeSet.hasAttribute("comment"))
        assertEquals(
            "Create the certified loan schema",
            document.getElementsByTagName("comment").item(0).textContent.trim(),
        )
    }

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }
}

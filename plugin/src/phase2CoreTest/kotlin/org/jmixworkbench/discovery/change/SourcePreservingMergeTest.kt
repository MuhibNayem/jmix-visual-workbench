package org.jmixworkbench.discovery.change

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcePreservingMergeTest {

    @Test
    fun `properties merge appends only absent keys and preserves manual values`() {
        val existing = """
            # Handwritten deployment caption
            LoanApp=Custom loan application
            custom.timeout=15s
        """.trimIndent()
        val generated = """
            LoanApp=Loan application
            LoanApp.loanAmount=Loan amount
        """.trimIndent()

        val result = SourcePreservingMerge.properties(existing, generated)

        assertTrue(result.accepted)
        val insertion = assertNotNull(result.insertion)
        val merged = StringBuilder(existing).insert(insertion.offset, insertion.text).toString()
        assertTrue("LoanApp=Custom loan application" in merged)
        assertFalse("LoanApp=Loan application" in merged)
        assertEquals(1, Regex("""(?m)^LoanApp\.loanAmount=""").findAll(merged).count())
    }

    @Test
    fun `menu merge inserts before the real root close without rewriting comments or formatting`() {
        val existing = """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <!-- handwritten menu grouping -->
                <item id="employees" view="Employee.list"/>
                <!-- misleading text: </menu-config> -->
            </menu-config>
        """.trimIndent()
        val generated = """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item id="loans" view="Loan.list"/>
            </menu-config>
        """.trimIndent()

        val result = SourcePreservingMerge.menu(existing, generated)

        assertTrue(result.accepted)
        val insertion = assertNotNull(result.insertion)
        val merged = StringBuilder(existing).insert(insertion.offset, insertion.text).toString()
        assertTrue("<!-- handwritten menu grouping -->" in merged)
        assertTrue(merged.indexOf("id=\"loans\"") < merged.lastIndexOf("</menu-config>"))
        assertTrue(SourcePreservingMerge.menu(merged, generated).accepted)
        assertNull(SourcePreservingMerge.menu(merged, generated).insertion)
    }

    @Test
    fun `menu merge rejects malformed roots and partial identifier conflicts`() {
        val malformed = SourcePreservingMerge.menu(
            "<menu-config><item id=\"existing\"></menu-config>",
            "<menu-config><item id=\"new\"/></menu-config>",
        )
        assertFalse(malformed.accepted)
        assertEquals("JVW-GENERATION-MENU-MALFORMED", malformed.issue?.code)

        val partial = SourcePreservingMerge.menu(
            "<menu-config><item id=\"existing\"/></menu-config>",
            "<menu-config><item id=\"existing\"/><item id=\"new\"/></menu-config>",
        )
        assertFalse(partial.accepted)
        assertEquals("JVW-GENERATION-MENU-PARTIAL-CONFLICT", partial.issue?.code)
    }

    @Test
    fun `menu root merge skips nested menu closing elements`() {
        val existing = """
            <menu xmlns="http://jmix.io/schema/flowui/menu" id="root">
                <menu id="administration">
                    <item id="users" view="User.list"/>
                </menu>
            </menu>
        """.trimIndent()
        val generated = """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item id="loans" view="Loan.list"/>
            </menu-config>
        """.trimIndent()

        val result = SourcePreservingMerge.menu(existing, generated)
        val insertion = assertNotNull(result.insertion)
        assertTrue(insertion.offset > existing.indexOf("</menu>"))
        val merged = StringBuilder(existing).insert(insertion.offset, insertion.text).toString()
        assertTrue(merged.indexOf("id=\"loans\"") > merged.indexOf("</menu>", existing.indexOf("administration")))
        assertTrue(merged.indexOf("id=\"loans\"") < merged.lastIndexOf("</menu>"))
    }
}

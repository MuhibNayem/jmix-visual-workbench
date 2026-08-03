package org.jmixworkbench.generator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuSourcePatcherTest {
    @Test
    fun `structural patch preserves manual extensions comments and item parameters`() {
        val existing = """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <!-- enterprise-owned comment -->
                <custom-extension mode="strict"/>
                <menu id="application" title="Application" custom-flag="retain">
                    <item id="customers" view="Old.list" data-owner="loan">
                        <properties>
                            <property name="scope" value="active"/>
                        </properties>
                    </item>
                    <item id="removed" view="Removed.list"/>
                </menu>
            </menu-config>
        """.trimIndent()
        val generated = """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="application" title="Application" opened="true">
                    <menu id="operations" title="Operations">
                        <item id="customers" title="Customers" view="Customer.list"/>
                    </menu>
                </menu>
            </menu-config>
        """.trimIndent()

        val patched = MenuSourcePatcher.patch(existing, generated)

        assertTrue("enterprise-owned comment" in patched)
        assertTrue("custom-extension" in patched)
        assertTrue("""custom-flag="retain"""" in patched)
        assertTrue("""data-owner="loan"""" in patched)
        assertTrue("""name="scope"""" in patched)
        assertTrue("""id="operations"""" in patched)
        assertTrue("""view="Customer.list"""" in patched)
        assertFalse("""id="removed"""" in patched)
    }

    @Test
    fun `malformed existing source is rejected before mutation`() {
        val failure = runCatching {
            MenuSourcePatcher.patch("<menu-config>", "<menu-config/>")
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("SOURCE-MALFORMED"))
    }
}

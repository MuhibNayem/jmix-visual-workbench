package org.jmixworkbench.generator

import org.jmixworkbench.model.MenuEntryModel
import org.jmixworkbench.model.MenuEntryType
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MenuGeneratorTest {
    @Test
    fun `three level hierarchy emits recursive Jmix menus without flattening`() {
        val xml = MenuGenerator.generate(
            listOf(
                entry("application", MenuEntryType.MENU, order = 10),
                entry("operations", MenuEntryType.MENU, parent = "application", order = 10),
                entry(
                    "customers",
                    MenuEntryType.VIEW,
                    parent = "operations",
                    order = 10,
                    view = "Customer.list",
                    shortcut = "ALT-C",
                ),
                entry("reporting", MenuEntryType.MENU, parent = "application", order = 20),
                entry("portfolio", MenuEntryType.VIEW, parent = "reporting", order = 10, view = "Portfolio.view"),
            ),
        )

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val application = document.getElementsByTagName("menu").item(0) as Element
        val operations = application.childElements().first { it.getAttribute("id") == "operations" }
        val customers = operations.childElements().single()

        assertEquals("application", application.getAttribute("id"))
        assertEquals("menu", operations.tagName)
        assertEquals("item", customers.tagName)
        assertEquals("Customer.list", customers.getAttribute("view"))
        assertEquals("ALT-C", customers.getAttribute("shortcutCombination"))
        assertTrue(xml.indexOf("operations") < xml.indexOf("reporting"))
    }

    @Test
    fun `empty submenu remains a menu and bean and separator use supported Jmix elements`() {
        val xml = MenuGenerator.generate(
            listOf(
                entry("root", MenuEntryType.MENU),
                entry("empty", MenuEntryType.MENU, parent = "root"),
                entry("invokeClose", MenuEntryType.BEAN, parent = "root")
                    .copy(bean = "menuBean", beanMethod = "closeMonth"),
                entry("divider", MenuEntryType.SEPARATOR, parent = "root"),
            ),
        )

        assertTrue("""<menu id="empty"""" in xml)
        assertTrue("""bean="menuBean"""" in xml)
        assertTrue("""beanMethod="closeMonth"""" in xml)
        assertTrue("<separator/>" in xml)
    }

    @Test
    fun `cycles and children under executable items are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MenuGenerator.generate(
                listOf(
                    entry("a", MenuEntryType.MENU, parent = "b"),
                    entry("b", MenuEntryType.MENU, parent = "a"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MenuGenerator.generate(
                listOf(
                    entry("root", MenuEntryType.MENU),
                    entry("view", MenuEntryType.VIEW, parent = "root", view = "View.list"),
                    entry("illegal", MenuEntryType.VIEW, parent = "view", view = "Illegal.list"),
                ),
            )
        }
    }

    private fun entry(
        id: String,
        type: MenuEntryType,
        parent: String? = null,
        order: Int = 100,
        view: String? = null,
        shortcut: String? = null,
    ) = MenuEntryModel(
        id = id,
        caption = id,
        parentId = parent,
        order = order,
        viewId = view,
        shortcut = shortcut,
        type = type,
    )

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}

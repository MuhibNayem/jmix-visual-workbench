package org.jmixworkbench.generator

import org.jmixworkbench.model.ComponentModel
import org.jmixworkbench.model.ComponentType
import org.jmixworkbench.model.FragmentModel
import org.jmixworkbench.model.ViewModel
import org.jmixworkbench.model.ViewType
import org.w3c.dom.Document
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.xml.sax.InputSource

class ViewXmlGeneratorTest {

    @Test
    fun `list detail and fragment descriptors are namespace well formed`() {
        val listView = ViewModel(
            viewName = "customerListView",
            packageName = "example.view.customer",
            viewType = ViewType.LIST_VIEW,
            layout = rootWith(ComponentModel("customersDataGrid", ComponentType.DATA_GRID)),
        )
        val detailView = ViewModel(
            viewName = "customerDetailView",
            packageName = "example.view.customer",
            viewType = ViewType.DETAIL_VIEW,
            layout = rootWith(ComponentModel("form", ComponentType.FORM_LAYOUT)),
        )
        val fragment = FragmentModel(
            name = "addressFragment",
            packageName = "example.view.address",
            layout = rootWith(ComponentModel("content", ComponentType.VBOX)),
        )

        assertDescriptor(ViewXmlGenerator.generate(listView), "view")
        assertDescriptor(ViewXmlGenerator.generate(detailView), "view")
        assertDescriptor(ViewXmlGenerator.generateFragment(fragment), "fragment")
    }

    private fun rootWith(child: ComponentModel): ComponentModel =
        ComponentModel(
            id = "root",
            type = ComponentType.VBOX,
            children = mutableListOf(child),
        )

    private fun assertDescriptor(xml: String, rootName: String) {
        assertFalse(xml.contains("xmlns:="))
        val document = parseNamespaceAware(xml)
        assertEquals(rootName, document.documentElement.localName)
        assertEquals("http://jmix.io/schema/flowui/layout", document.documentElement.namespaceURI)
        assertEquals(
            "http://jmix.io/schema/flowui/data",
            document.documentElement.getAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "data"),
        )
    }

    private fun parseNamespaceAware(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }
}

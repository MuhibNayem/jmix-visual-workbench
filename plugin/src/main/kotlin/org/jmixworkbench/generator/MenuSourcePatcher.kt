package org.jmixworkbench.generator

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Structurally patches a real menu document.
 *
 * Known menu attributes and hierarchy come from the visual model. Unknown
 * attributes, item parameters, custom icon elements, comments and unsupported
 * root children remain owned by the existing source and are retained.
 */
object MenuSourcePatcher {
    private val structuralTags = setOf("menu", "item", "separator")
    private val knownAttributes = setOf(
        "id", "title", "description", "icon", "classNames", "className", "opened",
        "view", "bean", "beanMethod", "shortcutCombination", "shortcut", "openedBy",
    )

    fun patch(existing: String, generated: String): String {
        val existingDocument = parse(existing)
            ?: error("JVW-MENU-SOURCE-MALFORMED: the indexed menu source is malformed.")
        val generatedDocument = parse(generated)
            ?: error("JVW-MENU-GENERATED-MALFORMED: the generated menu source is malformed.")
        val existingRoot = existingDocument.documentElement
        val generatedRoot = generatedDocument.documentElement
        require(existingRoot.localTag() in setOf("menu-config", "menu")) {
            "JVW-MENU-ROOT-UNSUPPORTED: expected a Jmix menu-config or menu root."
        }
        require(generatedRoot.localTag() == "menu-config") {
            "JVW-MENU-GENERATED-ROOT-UNSUPPORTED: generated menu root is invalid."
        }
        val available = existingRoot.allStructuralDescendants()
            .groupBy(::nodeKey)
            .mapValues { (_, value) -> value.toMutableList() }
        mergeContainer(existingDocument, existingRoot, generatedRoot, available)
        return serialize(existingDocument, existing.trimStart().startsWith("<?xml"))
    }

    private fun mergeContainer(
        document: Document,
        existing: Element,
        generated: Element,
        available: Map<String, MutableList<Element>>,
    ) {
        val existingStructural = existing.directChildren().filter { it.localTag() in structuralTags }
        val generatedStructural = generated.directChildren().filter { it.localTag() in structuralTags }
        val replacements = generatedStructural.map { desired ->
            val current = available[nodeKey(desired)]?.removeFirstOrNull()
            if (current == null) {
                (document.importNode(desired, true) as Element).also { imported ->
                    if (desired.localTag() == "menu") {
                        mergeContainer(document, imported, desired, available)
                    }
                }
            } else {
                mergeNode(document, current, desired, available)
                current
            }
        }

        existingStructural.filter { it.parentNode == existing }.forEach(existing::removeChild)
        replacements.forEach { existing.appendChild(it) }
    }

    private fun mergeNode(
        document: Document,
        existing: Element,
        generated: Element,
        available: Map<String, MutableList<Element>>,
    ) {
        knownAttributes.forEach { name ->
            if (generated.hasAttribute(name)) {
                existing.setAttribute(name, generated.getAttribute(name))
            } else {
                existing.removeAttribute(name)
            }
        }
        if (generated.localTag() == "menu") {
            mergeContainer(document, existing, generated, available)
        }
    }

    private fun nodeKey(element: Element): String {
        val tag = element.localTag()
        val id = element.getAttribute("id").trim()
        return if (id.isBlank()) "$tag:@anonymous" else "$tag:$id"
    }

    private fun Element.directChildren(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.allStructuralDescendants(): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(parent: Element) {
            parent.directChildren().forEach { child ->
                if (child.localTag() in structuralTags) result += child
                visit(child)
            }
        }
        visit(this)
        return result
    }

    private fun Element.localTag(): String = (localName ?: tagName.substringAfter(':')).trim()

    private fun parse(content: String): Document? =
        runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isXIncludeAware = false
            factory.setExpandEntityReferences(false)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(InputSource(StringReader(content)))
        }.getOrNull()

    private fun serialize(document: Document, keepDeclaration: Boolean): String {
        val transformerFactory = TransformerFactory.newInstance()
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        val transformer = transformerFactory.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (keepDeclaration) "no" else "yes")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        }
        return StringWriter().use { writer ->
            transformer.transform(DOMSource(document), StreamResult(writer))
            writer.toString().trimEnd() + "\n"
        }
    }
}

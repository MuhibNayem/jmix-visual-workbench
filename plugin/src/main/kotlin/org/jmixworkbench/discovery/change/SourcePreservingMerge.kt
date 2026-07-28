package org.jmixworkbench.discovery.change

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class SourceTextInsertion(
    val offset: Int,
    val text: String,
)

data class SourceMergeResult(
    val accepted: Boolean,
    val insertion: SourceTextInsertion?,
    val issue: WorkspaceChangeIssue?,
)

object SourcePreservingMerge {
    fun properties(existing: String, generated: String): SourceMergeResult {
        val existingKeys = PROPERTY_ENTRY.findAll(existing).map { it.groupValues[1] }.toSet()
        val additions = generated.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.startsWith('#') || it.startsWith('!') }
            .filterNot { line ->
                val key = line.substringBefore('=').substringBefore(':').trim()
                key in existingKeys
            }
            .toList()
        if (additions.isEmpty()) {
            return acceptedNoChange()
        }
        val prefix = if (existing.isEmpty() || existing.endsWith('\n')) "" else "\n"
        return accepted(
            SourceTextInsertion(
                existing.length,
                prefix + additions.joinToString("\n", postfix = "\n"),
            ),
        )
    }

    fun menu(existing: String, generated: String): SourceMergeResult {
        val existingDocument = parseXml(existing)
            ?: return rejected("JVW-GENERATION-MENU-MALFORMED", "The existing menu XML is malformed.")
        val generatedDocument = parseXml(generated)
            ?: return rejected("JVW-GENERATION-MENU-GENERATED-MALFORMED", "The generated menu XML is malformed.")
        val existingRoot = existingDocument.documentElement
        val generatedRoot = generatedDocument.documentElement
        if (existingRoot.localTag() !in MENU_ROOT_TAGS || generatedRoot.localTag() !in MENU_ROOT_TAGS) {
            return rejected(
                "JVW-GENERATION-MENU-ROOT-UNSUPPORTED",
                "Source-preserving menu merge requires a Jmix menu or menu-config root.",
            )
        }

        val existingIds = existingRoot.allElementsIncludingSelf()
            .map { it.getAttribute("id").trim() }
            .filter(String::isNotBlank)
            .toSet()
        val generatedIds = generatedRoot.allElementsIncludingSelf()
            .map { it.getAttribute("id").trim() }
            .filter(String::isNotBlank)
            .toSet()
        if (generatedIds.isEmpty()) {
            return rejected("JVW-GENERATION-MENU-EMPTY", "Generated menu content has no identified item.")
        }
        val duplicates = generatedIds intersect existingIds
        if (duplicates == generatedIds) {
            return acceptedNoChange()
        }
        if (duplicates.isNotEmpty()) {
            return rejected(
                "JVW-GENERATION-MENU-PARTIAL-CONFLICT",
                "Some generated menu identifiers already exist: ${duplicates.sorted().joinToString()}.",
            )
        }

        val generatedInner = rootInnerXml(generated, generatedRoot.tagName)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return rejected("JVW-GENERATION-MENU-EMPTY", "Generated menu content has no insertable item.")
        val closingOffset = rootClosingOffset(existing, existingRoot.tagName)
            ?: return rejected(
                "JVW-GENERATION-MENU-CLOSING-MISSING",
                "The existing menu root closing element cannot be located safely.",
            )
        val closingLineStart = existing.lastIndexOf('\n', closingOffset - 1).let { if (it < 0) 0 else it + 1 }
        val indentation = existing.substring(closingLineStart, closingOffset).takeWhile(Char::isWhitespace)
        val childIndent = "$indentation    "
        val inserted = generatedInner.lineSequence()
            .joinToString("\n") { line -> childIndent + line.trimStart() }
        val prefix = if (closingOffset == 0 || existing[closingOffset - 1] == '\n') "" else "\n"
        return accepted(SourceTextInsertion(closingOffset, "$prefix$inserted\n"))
    }

    private fun parseXml(content: String): Document? =
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

    private fun rootInnerXml(content: String, rootTagName: String): String? {
        val rootStart = content.indexOf("<$rootTagName").takeIf { it >= 0 } ?: return null
        var cursor = rootStart + rootTagName.length + 1
        var quote: Char? = null
        while (cursor < content.length) {
            val current = content[cursor]
            when {
                quote != null && current == quote -> quote = null
                quote == null && (current == '"' || current == '\'') -> quote = current
                quote == null && current == '>' -> {
                    val closing = rootClosingOffset(content, rootTagName) ?: return null
                    return content.substring(cursor + 1, closing)
                }
            }
            cursor += 1
        }
        return null
    }

    private fun rootClosingOffset(content: String, rootTagName: String): Int? {
        var cursor = 0
        var rootDepth = 0
        while (cursor < content.length) {
            when {
                content.startsWith("<!--", cursor) -> {
                    cursor = content.indexOf("-->", cursor + 4).takeIf { it >= 0 }?.plus(3) ?: return null
                }
                content.startsWith("<![CDATA[", cursor) -> {
                    cursor = content.indexOf("]]>", cursor + 9).takeIf { it >= 0 }?.plus(3) ?: return null
                }
                content.startsWith("<?", cursor) -> {
                    cursor = content.indexOf("?>", cursor + 2).takeIf { it >= 0 }?.plus(2) ?: return null
                }
                startsTag(content, cursor, rootTagName, closing = false) -> {
                    val end = tagEnd(content, cursor) ?: return null
                    if (content.substring(cursor, end).trimEnd().endsWith("/")) {
                        cursor = end + 1
                    } else {
                        rootDepth += 1
                        cursor = end + 1
                    }
                }
                startsTag(content, cursor, rootTagName, closing = true) -> {
                    if (rootDepth == 1) return cursor
                    rootDepth -= 1
                    cursor = (tagEnd(content, cursor) ?: return null) + 1
                }
                else -> cursor += 1
            }
        }
        return null
    }

    private fun startsTag(content: String, offset: Int, name: String, closing: Boolean): Boolean {
        val prefix = if (closing) "</$name" else "<$name"
        if (!content.startsWith(prefix, offset)) return false
        val boundary = content.getOrNull(offset + prefix.length)
        return boundary == null || boundary.isWhitespace() || boundary == '>' || boundary == '/'
    }

    private fun tagEnd(content: String, offset: Int): Int? {
        var cursor = offset
        var quote: Char? = null
        while (cursor < content.length) {
            val current = content[cursor]
            when {
                quote != null && current == quote -> quote = null
                quote == null && (current == '"' || current == '\'') -> quote = current
                quote == null && current == '>' -> return cursor
            }
            cursor += 1
        }
        return null
    }

    private fun Element.localTag(): String = (localName ?: tagName.substringAfter(':')).trim()

    private fun Element.allElementsIncludingSelf(): List<Element> {
        val result = mutableListOf(this)
        fun visit(element: Element) {
            val children = element.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is Element) {
                    result += child
                    visit(child)
                }
            }
        }
        visit(this)
        return result
    }

    private fun accepted(insertion: SourceTextInsertion): SourceMergeResult =
        SourceMergeResult(true, insertion, null)

    private fun acceptedNoChange(): SourceMergeResult =
        SourceMergeResult(true, null, null)

    private fun rejected(code: String, message: String): SourceMergeResult =
        SourceMergeResult(false, null, WorkspaceChangeIssue(code, message))

    private val PROPERTY_ENTRY = Regex("""(?m)^\s*([^#!\s][^=:\s]*)\s*[:=]""")
    private val MENU_ROOT_TAGS = setOf("menu", "menu-config")
}

package org.jmixworkbench.discovery.flowui

import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.w3c.dom.Document
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class FlowUiAttributeSnapshot(
    val name: String,
    val value: String,
    val rawValue: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val valueStart: Int,
    val valueEnd: Int,
    val quote: String,
)

data class FlowUiElementSnapshot(
    val key: String,
    val tagName: String,
    val localTag: String,
    val id: String?,
    val parentKey: String?,
    val childKeys: List<String>,
    val sourceStart: Int,
    val startTagEnd: Int,
    val endTagStart: Int,
    val sourceEnd: Int,
    val selfClosing: Boolean,
    val attributes: List<FlowUiAttributeSnapshot>,
    val directText: String?,
)

data class FlowUiDescriptorSnapshot(
    val relativePath: String,
    val revisionFingerprint: String,
    val viewId: String,
    val rootKey: String,
    val sourceText: String,
    val elements: List<FlowUiElementSnapshot>,
)

data class FlowUiParseResult(
    val accepted: Boolean,
    val document: FlowUiDescriptorSnapshot?,
    val issues: List<WorkspaceChangeIssue>,
)

data class FlowUiPropertyChangeProposal(
    val accepted: Boolean,
    val noChange: Boolean,
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

enum class FlowUiMoveDirection {
    UP,
    DOWN,
}

object FlowUiDescriptorParser {
    fun parse(
        relativePath: String,
        content: String,
        revisionFingerprint: String = CanonicalDiscoveryJson.sha256(content),
    ): FlowUiParseResult {
        val document = parseXml(content)
            ?: return rejected("JVW-FLOWUI-XML-MALFORMED", "The FlowUI descriptor is not well-formed XML.", relativePath)
        val root = document.documentElement
        val rootLocalTag = (root.localName ?: root.tagName.substringAfter(':')).trim()
        if (rootLocalTag != "view") {
            return rejected("JVW-FLOWUI-ROOT-UNSUPPORTED", "The descriptor root must be a Jmix FlowUI view.", relativePath)
        }
        val builders = mutableListOf<ElementBuilder>()
        val stack = mutableListOf<Int>()
        var cursor = 0
        while (cursor < content.length) {
            val opening = content.indexOf('<', cursor)
            if (opening < 0) break
            when {
                content.startsWith("<!--", opening) -> {
                    cursor = content.indexOf("-->", opening + 4).takeIf { it >= 0 }?.plus(3)
                        ?: return rejected("JVW-FLOWUI-COMMENT-UNCLOSED", "An XML comment is not closed.", relativePath)
                }
                content.startsWith("<![CDATA[", opening) -> {
                    cursor = content.indexOf("]]>", opening + 9).takeIf { it >= 0 }?.plus(3)
                        ?: return rejected("JVW-FLOWUI-CDATA-UNCLOSED", "A CDATA section is not closed.", relativePath)
                }
                content.startsWith("<?", opening) -> {
                    cursor = content.indexOf("?>", opening + 2).takeIf { it >= 0 }?.plus(2)
                        ?: return rejected("JVW-FLOWUI-PI-UNCLOSED", "An XML processing instruction is not closed.", relativePath)
                }
                content.startsWith("<!", opening) -> {
                    return rejected(
                        "JVW-FLOWUI-DECLARATION-REJECTED",
                        "External declarations are not allowed in editable FlowUI descriptors.",
                        relativePath,
                    )
                }
                content.startsWith("</", opening) -> {
                    val closingEnd = tagEnd(content, opening)
                        ?: return rejected("JVW-FLOWUI-TAG-UNCLOSED", "A closing XML tag is not closed.", relativePath)
                    val closingName = content.substring(opening + 2, closingEnd).trim().substringBefore(' ')
                    val matchingIndex = stack.indexOfLast { builders[it].tagName == closingName }
                    if (matchingIndex < 0) {
                        return rejected("JVW-FLOWUI-TAG-MISMATCH", "Unexpected closing tag: $closingName.", relativePath)
                    }
                    while (stack.size > matchingIndex + 1) stack.removeAt(stack.lastIndex)
                    val builderIndex = stack.removeAt(stack.lastIndex)
                    builders[builderIndex].endTagStart = opening
                    builders[builderIndex].sourceEnd = closingEnd + 1
                    cursor = closingEnd + 1
                }
                else -> {
                    val startTag = parseStartTag(content, opening)
                        ?: return rejected("JVW-FLOWUI-TAG-MALFORMED", "An XML start tag cannot be indexed safely.", relativePath)
                    val parentIndex = stack.lastOrNull()
                    val id = startTag.attributes.firstOrNull { it.name == "id" }?.value
                    val key = "${startTag.tagName}:${id.orEmpty()}@$opening"
                    val builder = ElementBuilder(
                        key = key,
                        tagName = startTag.tagName,
                        localTag = startTag.tagName.substringAfter(':'),
                        id = id,
                        parentIndex = parentIndex,
                        sourceStart = opening,
                        startTagEnd = startTag.endOffset,
                        endTagStart = if (startTag.selfClosing) startTag.endOffset else -1,
                        sourceEnd = if (startTag.selfClosing) startTag.endOffset else -1,
                        selfClosing = startTag.selfClosing,
                        attributes = startTag.attributes,
                    )
                    val builderIndex = builders.size
                    builders += builder
                    parentIndex?.let { builders[it].childIndices += builderIndex }
                    if (!startTag.selfClosing) stack += builderIndex
                    cursor = startTag.endOffset
                }
            }
        }
        if (stack.isNotEmpty() || builders.isEmpty()) {
            return rejected("JVW-FLOWUI-TAG-UNCLOSED", "The FlowUI element tree is incomplete.", relativePath)
        }

        val snapshots = builders.map { builder ->
            val directText = if (!builder.selfClosing &&
                builder.childIndices.isEmpty() &&
                builder.endTagStart >= builder.startTagEnd
            ) {
                val inner = content.substring(builder.startTagEnd, builder.endTagStart)
                val trimmed = inner.trim()
                when {
                    trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>") ->
                        trimmed.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
                    '<' !in trimmed -> trimmed.takeIf(String::isNotBlank)
                    else -> null
                }
            } else {
                null
            }
            FlowUiElementSnapshot(
                key = builder.key,
                tagName = builder.tagName,
                localTag = builder.localTag,
                id = builder.id,
                parentKey = builder.parentIndex?.let { builders[it].key },
                childKeys = builder.childIndices.map { builders[it].key },
                sourceStart = builder.sourceStart,
                startTagEnd = builder.startTagEnd,
                endTagStart = builder.endTagStart,
                sourceEnd = builder.sourceEnd,
                selfClosing = builder.selfClosing,
                attributes = builder.attributes,
                directText = directText,
            )
        }
        val rootSnapshot = snapshots.first()
        val viewId = rootSnapshot.attributes.firstOrNull { it.name == "id" }?.value
            ?: relativePath.substringAfterLast('/').substringBeforeLast('.')
        return FlowUiParseResult(
            accepted = true,
            document = FlowUiDescriptorSnapshot(
                relativePath = relativePath,
                revisionFingerprint = revisionFingerprint,
                viewId = viewId,
                rootKey = rootSnapshot.key,
                sourceText = content,
                elements = snapshots,
            ),
            issues = emptyList(),
        )
    }

    fun proposePropertyChange(
        document: FlowUiDescriptorSnapshot,
        elementKey: String,
        propertyName: String,
        value: String,
    ): FlowUiPropertyChangeProposal {
        if (!PROPERTY_NAME.matches(propertyName) || propertyName == "xmlns" || propertyName.startsWith("xmlns:")) {
            return proposalRejected(
                "JVW-FLOWUI-PROPERTY-REJECTED",
                "The requested XML property name is not editable.",
                document.relativePath,
            )
        }
        val element = document.elements.singleOrNull { it.key == elementKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The selected FlowUI element no longer exists.",
                document.relativePath,
            )
        val escaped = escapeAttribute(value)
        val existing = element.attributes.firstOrNull { it.name == propertyName }
        if (existing != null && existing.rawValue == escaped) {
            return FlowUiPropertyChangeProposal(true, true, null, emptyList())
        }
        val edit = if (existing != null) {
            WorkspaceTextEdit(
                startOffset = existing.valueStart,
                endOffset = existing.valueEnd,
                expectedText = existing.rawValue,
                replacement = escaped,
            )
        } else {
            val closeIndex = element.startTagEnd - 1
            val insertionOffset = if (document.sourceText.getOrNull(closeIndex - 1) == '/') closeIndex - 1 else closeIndex
            WorkspaceTextEdit(
                startOffset = insertionOffset,
                endOffset = insertionOffset,
                expectedText = "",
                replacement = " $propertyName=\"$escaped\"",
            )
        }
        val identity = "${document.relativePath}\u0000$elementKey\u0000$propertyName\u0000$value"
        return FlowUiPropertyChangeProposal(
            accepted = true,
            noChange = false,
            changeSet = WorkspaceChangeSet(
                id = "flowui-property:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Update ${element.id ?: element.localTag} $propertyName",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = document.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = document.revisionFingerprint,
                        edits = listOf(edit),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    fun proposeInsertChild(
        document: FlowUiDescriptorSnapshot,
        parentKey: String,
        tagName: String,
        attributes: Map<String, String>,
    ): FlowUiPropertyChangeProposal {
        if (!XML_NAME.matches(tagName) || tagName.length > MAX_XML_NAME_LENGTH) {
            return proposalRejected(
                "JVW-FLOWUI-TAG-REJECTED",
                "The requested component tag name is not safe to insert.",
                document.relativePath,
            )
        }
        if (attributes.size > MAX_INSERT_ATTRIBUTES ||
            attributes.keys.any {
                !PROPERTY_NAME.matches(it) || it.length > MAX_XML_NAME_LENGTH ||
                    it == "xmlns" || it.startsWith("xmlns:")
            }
        ) {
            return proposalRejected(
                "JVW-FLOWUI-ATTRIBUTES-REJECTED",
                "One or more component attributes are not safe to insert.",
                document.relativePath,
            )
        }
        val requestedId = attributes["id"]
        if (requestedId != null && document.elements.any { it.id == requestedId }) {
            return proposalRejected(
                "JVW-FLOWUI-ID-CONFLICT",
                "A FlowUI element with id '$requestedId' already exists.",
                document.relativePath,
            )
        }
        val parent = document.elements.singleOrNull { it.key == parentKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The selected parent element no longer exists.",
                document.relativePath,
            )
        if (parent.selfClosing || parent.endTagStart < parent.startTagEnd) {
            return proposalRejected(
                "JVW-FLOWUI-PARENT-SELF-CLOSING",
                "A child cannot be inserted into a self-closing XML element.",
                document.relativePath,
            )
        }

        val newline = newlineOf(document.sourceText)
        val parentIndent = indentationAt(document.sourceText, parent.sourceStart)
        val childIndent = parent.childKeys.firstOrNull()
            ?.let { key -> document.elements.singleOrNull { it.key == key } }
            ?.let { child -> indentationAt(document.sourceText, child.sourceStart) }
            ?.takeIf { it.length > parentIndent.length }
            ?: "$parentIndent${indentUnitOf(document.sourceText)}"
        val markup = buildString {
            append('<').append(tagName)
            attributes.toSortedMap().forEach { (name, value) ->
                append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"')
            }
            append("/>")
        }
        val closingLineStart = document.sourceText.lastIndexOf('\n', parent.endTagStart - 1).let { it + 1 }
        val closingPrefix = document.sourceText.substring(closingLineStart, parent.endTagStart)
        val edit = if (closingPrefix.all(Char::isWhitespace)) {
            WorkspaceTextEdit(
                startOffset = closingLineStart,
                endOffset = parent.endTagStart,
                expectedText = closingPrefix,
                replacement = "$childIndent$markup$newline$closingPrefix",
            )
        } else {
            WorkspaceTextEdit(
                startOffset = parent.endTagStart,
                endOffset = parent.endTagStart,
                expectedText = "",
                replacement = "$newline$childIndent$markup$newline$parentIndent",
            )
        }
        return structuralProposal(
            document = document,
            operationIdentity = "insert\u0000$parentKey\u0000$tagName\u0000${attributes.toSortedMap()}",
            label = "Insert ${requestedId ?: tagName}",
            edit = edit,
        )
    }

    fun proposeDeleteElement(
        document: FlowUiDescriptorSnapshot,
        elementKey: String,
    ): FlowUiPropertyChangeProposal {
        val element = document.elements.singleOrNull { it.key == elementKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The selected FlowUI element no longer exists.",
                document.relativePath,
            )
        if (element.parentKey == null) {
            return proposalRejected(
                "JVW-FLOWUI-ROOT-PROTECTED",
                "The FlowUI document root cannot be deleted.",
                document.relativePath,
            )
        }
        val lineStart = document.sourceText.lastIndexOf('\n', element.sourceStart - 1).let { it + 1 }
        val nextNewline = document.sourceText.indexOf('\n', element.sourceEnd)
        val lineContentEnd = if (nextNewline >= 0) nextNewline else document.sourceText.length
        val prefix = document.sourceText.substring(lineStart, element.sourceStart)
        val suffix = document.sourceText.substring(element.sourceEnd, lineContentEnd)
        val removeWholeLine = prefix.all(Char::isWhitespace) && suffix.all(Char::isWhitespace)
        val startOffset = if (removeWholeLine) lineStart else element.sourceStart
        val endOffset = if (removeWholeLine && nextNewline >= 0) nextNewline + 1 else element.sourceEnd
        return structuralProposal(
            document = document,
            operationIdentity = "delete\u0000$elementKey",
            label = "Delete ${element.id ?: element.localTag}",
            edit = WorkspaceTextEdit(
                startOffset = startOffset,
                endOffset = endOffset,
                expectedText = document.sourceText.substring(startOffset, endOffset),
                replacement = "",
            ),
        )
    }

    fun proposeMoveElement(
        document: FlowUiDescriptorSnapshot,
        elementKey: String,
        direction: FlowUiMoveDirection,
    ): FlowUiPropertyChangeProposal {
        val element = document.elements.singleOrNull { it.key == elementKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The selected FlowUI element no longer exists.",
                document.relativePath,
            )
        val parent = element.parentKey?.let { parentKey ->
            document.elements.singleOrNull { it.key == parentKey }
        } ?: return proposalRejected(
            "JVW-FLOWUI-ROOT-PROTECTED",
            "The FlowUI document root cannot be moved.",
            document.relativePath,
        )
        val index = parent.childKeys.indexOf(element.key)
        val siblingIndex = index + if (direction == FlowUiMoveDirection.UP) -1 else 1
        if (index < 0 || siblingIndex !in parent.childKeys.indices) {
            return FlowUiPropertyChangeProposal(
                accepted = true,
                noChange = true,
                changeSet = null,
                issues = emptyList(),
            )
        }
        val sibling = document.elements.single { it.key == parent.childKeys[siblingIndex] }
        val first = if (element.sourceStart < sibling.sourceStart) element else sibling
        val second = if (first === element) sibling else element
        val between = document.sourceText.substring(first.sourceEnd, second.sourceStart)
        val firstSource = document.sourceText.substring(first.sourceStart, first.sourceEnd)
        val secondSource = document.sourceText.substring(second.sourceStart, second.sourceEnd)
        return structuralProposal(
            document = document,
            operationIdentity = "move\u0000$direction\u0000$elementKey",
            label = "Move ${element.id ?: element.localTag} ${direction.name.lowercase()}",
            edit = WorkspaceTextEdit(
                startOffset = first.sourceStart,
                endOffset = second.sourceEnd,
                expectedText = document.sourceText.substring(first.sourceStart, second.sourceEnd),
                replacement = secondSource + between + firstSource,
            ),
        )
    }

    private fun structuralProposal(
        document: FlowUiDescriptorSnapshot,
        operationIdentity: String,
        label: String,
        edit: WorkspaceTextEdit,
    ): FlowUiPropertyChangeProposal =
        FlowUiPropertyChangeProposal(
            accepted = true,
            noChange = false,
            changeSet = WorkspaceChangeSet(
                id = "flowui-structure:${CanonicalDiscoveryJson.sha256("${document.relativePath}\u0000$operationIdentity").take(24)}",
                label = label,
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = document.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = document.revisionFingerprint,
                        edits = listOf(edit),
                    ),
                ),
            ),
            issues = emptyList(),
        )

    private fun parseStartTag(content: String, opening: Int): StartTag? {
        val end = tagEnd(content, opening) ?: return null
        var cursor = opening + 1
        while (cursor < end && content[cursor].isWhitespace()) cursor += 1
        val nameStart = cursor
        while (cursor < end && isNameCharacter(content[cursor])) cursor += 1
        if (cursor == nameStart) return null
        val tagName = content.substring(nameStart, cursor)
        val attributes = mutableListOf<FlowUiAttributeSnapshot>()
        while (cursor < end) {
            while (cursor < end && content[cursor].isWhitespace()) cursor += 1
            if (cursor >= end || content[cursor] == '/') break
            val attributeStart = cursor
            while (cursor < end && isNameCharacter(content[cursor])) cursor += 1
            if (cursor == attributeStart) return null
            val name = content.substring(attributeStart, cursor)
            while (cursor < end && content[cursor].isWhitespace()) cursor += 1
            if (cursor >= end || content[cursor] != '=') return null
            cursor += 1
            while (cursor < end && content[cursor].isWhitespace()) cursor += 1
            val quote = content.getOrNull(cursor)?.takeIf { it == '"' || it == '\'' } ?: return null
            cursor += 1
            val valueStart = cursor
            while (cursor < end && content[cursor] != quote) cursor += 1
            if (cursor >= end) return null
            val valueEnd = cursor
            val rawValue = content.substring(valueStart, valueEnd)
            cursor += 1
            attributes += FlowUiAttributeSnapshot(
                name = name,
                value = unescapeAttribute(rawValue),
                rawValue = rawValue,
                sourceStart = attributeStart,
                sourceEnd = cursor,
                valueStart = valueStart,
                valueEnd = valueEnd,
                quote = quote.toString(),
            )
        }
        val selfClosing = content.substring(opening, end).trimEnd().endsWith("/")
        return StartTag(tagName, end + 1, selfClosing, attributes)
    }

    private fun tagEnd(content: String, opening: Int): Int? {
        var cursor = opening
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

    private fun escapeAttribute(value: String): String =
        value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun unescapeAttribute(value: String): String =
        value.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun isNameCharacter(value: Char): Boolean =
        value.isLetterOrDigit() || value == '_' || value == '-' || value == ':' || value == '.'

    private fun rejected(code: String, message: String, path: String): FlowUiParseResult =
        FlowUiParseResult(false, null, listOf(WorkspaceChangeIssue(code, message, path)))

    private fun proposalRejected(code: String, message: String, path: String): FlowUiPropertyChangeProposal =
        FlowUiPropertyChangeProposal(false, false, null, listOf(WorkspaceChangeIssue(code, message, path)))

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

    private data class StartTag(
        val tagName: String,
        val endOffset: Int,
        val selfClosing: Boolean,
        val attributes: List<FlowUiAttributeSnapshot>,
    )

    private data class ElementBuilder(
        val key: String,
        val tagName: String,
        val localTag: String,
        val id: String?,
        val parentIndex: Int?,
        val sourceStart: Int,
        val startTagEnd: Int,
        var endTagStart: Int,
        var sourceEnd: Int,
        val selfClosing: Boolean,
        val attributes: List<FlowUiAttributeSnapshot>,
        val childIndices: MutableList<Int> = mutableListOf(),
    )

    private fun indentationAt(source: String, offset: Int): String {
        val lineStart = source.lastIndexOf('\n', offset - 1).let { it + 1 }
        return source.substring(lineStart, offset).takeWhile(Char::isWhitespace)
    }

    private fun newlineOf(source: String): String =
        if ("\r\n" in source) "\r\n" else "\n"

    private fun indentUnitOf(source: String): String {
        val indentation = source.lineSequence()
            .map { line -> line.takeWhile(Char::isWhitespace) }
            .filter(String::isNotEmpty)
            .minByOrNull(String::length)
        return indentation ?: "    "
    }

    private const val MAX_XML_NAME_LENGTH = 120
    private const val MAX_INSERT_ATTRIBUTES = 100
    private val PROPERTY_NAME = Regex("""[A-Za-z_][A-Za-z0-9_.:-]*""")
    private val XML_NAME = Regex("""[A-Za-z_][A-Za-z0-9_.:-]*""")
}

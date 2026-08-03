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
    val directTextStart: Int?,
    val directTextEnd: Int?,
    val directTextCdata: Boolean,
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
            var directTextStart: Int? = null
            var directTextEnd: Int? = null
            var directTextCdata = false
            val directText = if (!builder.selfClosing &&
                builder.childIndices.isEmpty() &&
                builder.endTagStart >= builder.startTagEnd
            ) {
                val inner = content.substring(builder.startTagEnd, builder.endTagStart)
                val trimmed = inner.trim()
                when {
                    trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>") -> {
                        val cdataOpening = content.indexOf("<![CDATA[", builder.startTagEnd)
                        val cdataClosing = content.indexOf("]]>", cdataOpening + 9)
                        val rawStart = cdataOpening + 9
                        val rawEnd = cdataClosing
                        val raw = content.substring(rawStart, rawEnd)
                        val leading = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: raw.length
                        val trailing = raw.indexOfLast { !it.isWhitespace() }.takeIf { it >= 0 }?.plus(1) ?: leading
                        directTextStart = rawStart + leading
                        directTextEnd = rawStart + trailing
                        directTextCdata = true
                        raw.substring(leading, trailing).takeIf(String::isNotBlank)
                    }
                    '<' !in trimmed -> {
                        val leading = inner.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: inner.length
                        val trailing = inner.indexOfLast { !it.isWhitespace() }.takeIf { it >= 0 }?.plus(1) ?: leading
                        directTextStart = builder.startTagEnd + leading
                        directTextEnd = builder.startTagEnd + trailing
                        inner.substring(leading, trailing).takeIf(String::isNotBlank)?.let(::unescapeText)
                    }
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
                directTextStart = directTextStart,
                directTextEnd = directTextEnd,
                directTextCdata = directTextCdata,
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

    fun proposeDirectTextChange(
        document: FlowUiDescriptorSnapshot,
        elementKey: String,
        value: String,
    ): FlowUiPropertyChangeProposal {
        val element = document.elements.singleOrNull { it.key == elementKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The selected FlowUI element no longer exists.",
                document.relativePath,
            )
        val start = element.directTextStart
        val end = element.directTextEnd
        if (start == null || end == null || start > end) {
            return proposalRejected(
                "JVW-FLOWUI-TEXT-UNSUPPORTED",
                "This element does not contain a safely editable direct text value.",
                document.relativePath,
            )
        }
        if (element.directTextCdata && "]]>" in value) {
            return proposalRejected(
                "JVW-FLOWUI-CDATA-TERMINATOR-REJECTED",
                "The value cannot contain a CDATA closing delimiter.",
                document.relativePath,
            )
        }
        val replacement = if (element.directTextCdata) value else escapeText(value)
        val expected = document.sourceText.substring(start, end)
        if (replacement == expected) {
            return FlowUiPropertyChangeProposal(true, true, null, emptyList())
        }
        return structuralProposal(
            document = document,
            operationIdentity = "text\u0000$elementKey\u0000$value",
            label = "Update ${element.id ?: element.localTag} text",
            edit = WorkspaceTextEdit(start, end, expected, replacement),
        )
    }

    fun proposeInsertChild(
        document: FlowUiDescriptorSnapshot,
        parentKey: String,
        tagName: String,
        attributes: Map<String, String>,
        childCapable: Boolean = false,
        beforeElementKey: String? = null,
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
            if (childCapable) {
                append("></").append(tagName).append('>')
            } else {
                append("/>")
            }
        }
        val beforeElement = beforeElementKey?.let { key ->
            document.elements.singleOrNull { it.key == key }
                ?.takeIf { it.parentKey == parent.key }
                ?: return proposalRejected(
                    "JVW-FLOWUI-PLACEMENT-STALE",
                    "The requested insertion position is no longer a child of the destination.",
                    document.relativePath,
                )
        }
        val closingLineStart = document.sourceText.lastIndexOf('\n', parent.endTagStart - 1).let { it + 1 }
        val closingPrefix = document.sourceText.substring(closingLineStart, parent.endTagStart)
        val edit = if (beforeElement != null) {
            val beforeLineStart = document.sourceText.lastIndexOf('\n', beforeElement.sourceStart - 1).let { it + 1 }
            val beforeIndent = indentationAt(document.sourceText, beforeElement.sourceStart)
            WorkspaceTextEdit(
                startOffset = beforeLineStart,
                endOffset = beforeLineStart,
                expectedText = "",
                replacement = "$beforeIndent$markup$newline",
            )
        } else if (closingPrefix.all(Char::isWhitespace)) {
            WorkspaceTextEdit(
                startOffset = closingLineStart,
                endOffset = closingLineStart,
                expectedText = "",
                replacement = "$childIndent$markup$newline",
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
            operationIdentity = "insert\u0000$parentKey\u0000$beforeElementKey\u0000$tagName\u0000$childCapable\u0000${attributes.toSortedMap()}",
            label = "Insert ${requestedId ?: tagName}",
            edit = edit,
        )
    }

    fun proposeReparentElement(
        document: FlowUiDescriptorSnapshot,
        elementKey: String,
        newParentKey: String,
        beforeElementKey: String? = null,
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
                "The FlowUI document root cannot be moved.",
                document.relativePath,
            )
        }
        val newParent = document.elements.singleOrNull { it.key == newParentKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The destination FlowUI element no longer exists.",
                document.relativePath,
            )
        val descendants = linkedSetOf(element.key)
        var changed = true
        while (changed) {
            changed = false
            document.elements.asSequence()
                .filter { it.parentKey in descendants && descendants.add(it.key) }
                .forEach { changed = true }
        }
        if (newParent.key in descendants) {
            return proposalRejected(
                "JVW-FLOWUI-CYCLIC-MOVE",
                "A component cannot be moved into itself or one of its descendants.",
                document.relativePath,
            )
        }
        if (newParent.selfClosing || newParent.endTagStart < newParent.startTagEnd) {
            return proposalRejected(
                "JVW-FLOWUI-PARENT-SELF-CLOSING",
                "The destination cannot contain child elements.",
                document.relativePath,
            )
        }
        val beforeElement = beforeElementKey?.let { key ->
            document.elements.singleOrNull { it.key == key }
                ?.takeIf { it.parentKey == newParent.key }
                ?: return proposalRejected(
                    "JVW-FLOWUI-PLACEMENT-STALE",
                    "The requested drop position is no longer a child of the destination.",
                    document.relativePath,
                )
        }
        if (beforeElement?.key == element.key) {
            return FlowUiPropertyChangeProposal(true, true, null, emptyList())
        }
        if (element.parentKey == newParentKey) {
            val siblings = newParent.childKeys
            val currentIndex = siblings.indexOf(element.key)
            val beforeIndex = beforeElement?.let { siblings.indexOf(it.key) } ?: siblings.size
            val effectiveIndex = if (currentIndex < beforeIndex) beforeIndex - 1 else beforeIndex
            if (currentIndex == effectiveIndex) {
                return FlowUiPropertyChangeProposal(true, true, null, emptyList())
            }
        }

        val source = document.sourceText
        val newline = newlineOf(source)
        val sourceLineStart = source.lastIndexOf('\n', element.sourceStart - 1).let { it + 1 }
        val sourceLineEnd = source.indexOf('\n', element.sourceEnd)
        val sourceContentEnd = if (sourceLineEnd >= 0) sourceLineEnd else source.length
        val sourcePrefix = source.substring(sourceLineStart, element.sourceStart)
        val sourceSuffix = source.substring(element.sourceEnd, sourceContentEnd)
        val removeWholeLine = sourcePrefix.all(Char::isWhitespace) && sourceSuffix.all(Char::isWhitespace)
        val removalStart = if (removeWholeLine) sourceLineStart else element.sourceStart
        val removalEnd = if (removeWholeLine && sourceLineEnd >= 0) sourceLineEnd + 1 else element.sourceEnd

        val parentIndent = indentationAt(source, newParent.sourceStart)
        val childIndent = newParent.childKeys.firstOrNull()
            ?.let { key -> document.elements.singleOrNull { it.key == key } }
            ?.let { child -> indentationAt(source, child.sourceStart) }
            ?.takeIf { it.length > parentIndent.length }
            ?: "$parentIndent${indentUnitOf(source)}"
        val originalIndent = indentationAt(source, element.sourceStart)
        val movedMarkup = reindentElement(
            source.substring(element.sourceStart, element.sourceEnd),
            originalIndent,
            childIndent,
            newline,
        )
        val closingLineStart = source.lastIndexOf('\n', newParent.endTagStart - 1).let { it + 1 }
        val closingPrefix = source.substring(closingLineStart, newParent.endTagStart)
        val insertion = if (beforeElement != null) {
            val beforeLineStart = source.lastIndexOf('\n', beforeElement.sourceStart - 1).let { it + 1 }
            val beforeIndent = indentationAt(source, beforeElement.sourceStart)
            WorkspaceTextEdit(
                startOffset = beforeLineStart,
                endOffset = beforeLineStart,
                expectedText = "",
                replacement = "$beforeIndent$movedMarkup$newline",
            )
        } else if (closingPrefix.all(Char::isWhitespace)) {
            WorkspaceTextEdit(
                startOffset = closingLineStart,
                endOffset = closingLineStart,
                expectedText = "",
                replacement = "$childIndent$movedMarkup$newline",
            )
        } else {
            WorkspaceTextEdit(
                startOffset = newParent.endTagStart,
                endOffset = newParent.endTagStart,
                expectedText = "",
                replacement = "$newline$childIndent$movedMarkup$newline$parentIndent",
            )
        }
        if (insertion.startOffset in (removalStart + 1) until removalEnd) {
            return proposalRejected(
                "JVW-FLOWUI-CYCLIC-MOVE",
                "The destination is inside the selected component source range.",
                document.relativePath,
            )
        }
        return structuralProposal(
            document = document,
            operationIdentity = "reparent\u0000$elementKey\u0000$newParentKey\u0000$beforeElementKey",
            label = "Position ${element.id ?: element.localTag} in ${newParent.id ?: newParent.localTag}",
            edits = listOf(
                WorkspaceTextEdit(
                    startOffset = removalStart,
                    endOffset = removalEnd,
                    expectedText = source.substring(removalStart, removalEnd),
                    replacement = "",
                ),
                insertion,
            ),
        )
    }

    fun proposeCopyElement(
        document: FlowUiDescriptorSnapshot,
        elementKey: String,
        newParentKey: String,
        beforeElementKey: String? = null,
    ): FlowUiPropertyChangeProposal {
        val element = document.elements.singleOrNull { it.key == elementKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The copied FlowUI element no longer exists.",
                document.relativePath,
            )
        if (element.parentKey == null) {
            return proposalRejected(
                "JVW-FLOWUI-ROOT-PROTECTED",
                "The FlowUI document root cannot be copied.",
                document.relativePath,
            )
        }
        val newParent = document.elements.singleOrNull { it.key == newParentKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The paste destination no longer exists.",
                document.relativePath,
            )
        if (newParent.selfClosing || newParent.endTagStart < newParent.startTagEnd) {
            return proposalRejected(
                "JVW-FLOWUI-PARENT-SELF-CLOSING",
                "The paste destination cannot contain child elements.",
                document.relativePath,
            )
        }
        val subtree = document.elements.filter {
            it.sourceStart >= element.sourceStart && it.sourceEnd <= element.sourceEnd
        }
        if (newParent.key in subtree.map(FlowUiElementSnapshot::key)) {
            return proposalRejected(
                "JVW-FLOWUI-CYCLIC-COPY",
                "A component cannot be copied into itself or one of its descendants.",
                document.relativePath,
            )
        }
        val duplicateIds = subtree.mapNotNull(FlowUiElementSnapshot::id)
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            return proposalRejected(
                "JVW-FLOWUI-DUPLICATE-ID",
                "The selected subtree already contains duplicate ids and cannot be copied safely.",
                document.relativePath,
            )
        }
        val beforeElement = beforeElementKey?.let { key ->
            document.elements.singleOrNull { it.key == key }
                ?.takeIf { it.parentKey == newParent.key }
                ?: return proposalRejected(
                    "JVW-FLOWUI-PLACEMENT-STALE",
                    "The requested paste position is no longer a child of the destination.",
                    document.relativePath,
                )
        }
        val usedIds = document.elements.mapNotNull(FlowUiElementSnapshot::id).toMutableSet()
        val copiedIds = linkedMapOf<String, String>()
        subtree.mapNotNull(FlowUiElementSnapshot::id).forEach { original ->
            copiedIds[original] = nextCopyId(original, usedIds)
        }
        var markup = document.sourceText.substring(element.sourceStart, element.sourceEnd)
        val replacements = subtree.flatMap { copied ->
            copied.attributes.mapNotNull { attribute ->
                val replacement = when {
                    attribute.name == "id" -> copiedIds[attribute.value]
                    else -> rewriteCopiedReference(attribute.value, copiedIds)
                        .takeIf { it != attribute.value }
                } ?: return@mapNotNull null
                RelativeReplacement(
                    start = attribute.valueStart - element.sourceStart,
                    end = attribute.valueEnd - element.sourceStart,
                    value = escapeAttribute(replacement),
                )
            }
        }.sortedByDescending(RelativeReplacement::start)
        replacements.forEach { replacement ->
            markup = markup.replaceRange(replacement.start, replacement.end, replacement.value)
        }
        val insertion = insertionEdit(
            document = document,
            parent = newParent,
            beforeElement = beforeElement,
            markup = markup,
            originalIndent = indentationAt(document.sourceText, element.sourceStart),
        )
        return structuralProposal(
            document = document,
            operationIdentity = "copy\u0000$elementKey\u0000$newParentKey\u0000$beforeElementKey\u0000$copiedIds",
            label = "Copy ${element.id ?: element.localTag}",
            edit = insertion,
        )
    }

    fun proposeWrapElement(
        document: FlowUiDescriptorSnapshot,
        elementKey: String,
        tagName: String,
        attributes: Map<String, String>,
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
                "The FlowUI document root cannot be wrapped.",
                document.relativePath,
            )
        }
        validateInsertedElement(document, tagName, attributes)?.let { return it }
        val newline = newlineOf(document.sourceText)
        val outerIndent = indentationAt(document.sourceText, element.sourceStart)
        val innerIndent = "$outerIndent${indentUnitOf(document.sourceText)}"
        val original = document.sourceText.substring(element.sourceStart, element.sourceEnd)
        val reindented = reindentElement(original, outerIndent, innerIndent, newline)
        val opening = buildOpeningTag(tagName, attributes)
        val replacement = buildString {
            append(opening).append('>').append(newline)
            append(innerIndent).append(reindented).append(newline)
            append(outerIndent).append("</").append(tagName).append('>')
        }
        return structuralProposal(
            document = document,
            operationIdentity = "wrap\u0000$elementKey\u0000$tagName\u0000${attributes.toSortedMap()}",
            label = "Wrap ${element.id ?: element.localTag} in ${attributes["id"] ?: tagName}",
            edit = WorkspaceTextEdit(
                startOffset = element.sourceStart,
                endOffset = element.sourceEnd,
                expectedText = original,
                replacement = replacement,
            ),
        )
    }

    fun proposeConvertLayout(
        document: FlowUiDescriptorSnapshot,
        elementKey: String,
        tagName: String,
    ): FlowUiPropertyChangeProposal {
        val element = document.elements.singleOrNull { it.key == elementKey }
            ?: return proposalRejected(
                "JVW-FLOWUI-ELEMENT-STALE",
                "The selected FlowUI layout no longer exists.",
                document.relativePath,
            )
        val targetLocalTag = tagName.substringAfter(':')
        if (element.localTag !in CONVERTIBLE_LAYOUT_TAGS || targetLocalTag !in CONVERTIBLE_LAYOUT_TAGS) {
            return proposalRejected(
                "JVW-FLOWUI-LAYOUT-CONVERSION-REJECTED",
                "Only Jmix box, flex, form, and grid layouts can be converted visually.",
                document.relativePath,
            )
        }
        if (element.localTag == targetLocalTag) {
            return FlowUiPropertyChangeProposal(true, true, null, emptyList())
        }
        val convertedTag = if (':' in element.tagName && ':' !in tagName) {
            "${element.tagName.substringBefore(':')}:$targetLocalTag"
        } else {
            tagName
        }
        if (!XML_NAME.matches(convertedTag) || convertedTag.length > MAX_XML_NAME_LENGTH) {
            return proposalRejected(
                "JVW-FLOWUI-TAG-REJECTED",
                "The requested layout tag name is not safe.",
                document.relativePath,
            )
        }
        val targetProperties = LAYOUT_SPECIFIC_PROPERTIES[targetLocalTag].orEmpty()
        val incompatibleProperties = ALL_LAYOUT_SPECIFIC_PROPERTIES - targetProperties
        val source = document.sourceText
        val edits = mutableListOf(
            WorkspaceTextEdit(
                startOffset = element.sourceStart + 1,
                endOffset = element.sourceStart + 1 + element.tagName.length,
                expectedText = element.tagName,
                replacement = convertedTag,
            ),
        )
        if (!element.selfClosing) {
            edits += WorkspaceTextEdit(
                startOffset = element.endTagStart + 2,
                endOffset = element.endTagStart + 2 + element.tagName.length,
                expectedText = element.tagName,
                replacement = convertedTag,
            )
        }
        element.attributes
            .filter { it.name in incompatibleProperties }
            .forEach { attribute ->
                var start = attribute.sourceStart
                while (start > element.sourceStart + 1 &&
                    source[start - 1].isWhitespace() &&
                    source[start - 1] != '\n' &&
                    source[start - 1] != '\r'
                ) {
                    start -= 1
                }
                edits += WorkspaceTextEdit(
                    startOffset = start,
                    endOffset = attribute.sourceEnd,
                    expectedText = source.substring(start, attribute.sourceEnd),
                    replacement = "",
                )
            }
        return structuralProposal(
            document = document,
            operationIdentity = "convert\u0000$elementKey\u0000$convertedTag",
            label = "Convert ${element.id ?: element.localTag} to $targetLocalTag",
            edits = edits,
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

    private fun validateInsertedElement(
        document: FlowUiDescriptorSnapshot,
        tagName: String,
        attributes: Map<String, String>,
    ): FlowUiPropertyChangeProposal? {
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
        return null
    }

    private fun buildOpeningTag(tagName: String, attributes: Map<String, String>): String =
        buildString {
            append('<').append(tagName)
            attributes.toSortedMap().forEach { (name, value) ->
                append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"')
            }
        }

    private fun insertionEdit(
        document: FlowUiDescriptorSnapshot,
        parent: FlowUiElementSnapshot,
        beforeElement: FlowUiElementSnapshot?,
        markup: String,
        originalIndent: String,
    ): WorkspaceTextEdit {
        val source = document.sourceText
        val newline = newlineOf(source)
        val parentIndent = indentationAt(source, parent.sourceStart)
        val childIndent = parent.childKeys.firstOrNull()
            ?.let { key -> document.elements.singleOrNull { it.key == key } }
            ?.let { child -> indentationAt(source, child.sourceStart) }
            ?.takeIf { it.length > parentIndent.length }
            ?: "$parentIndent${indentUnitOf(source)}"
        val reindented = reindentElement(markup, originalIndent, childIndent, newline)
        val closingLineStart = source.lastIndexOf('\n', parent.endTagStart - 1).let { it + 1 }
        val closingPrefix = source.substring(closingLineStart, parent.endTagStart)
        return if (beforeElement != null) {
            val beforeLineStart = source.lastIndexOf('\n', beforeElement.sourceStart - 1).let { it + 1 }
            val beforeIndent = indentationAt(source, beforeElement.sourceStart)
            WorkspaceTextEdit(
                startOffset = beforeLineStart,
                endOffset = beforeLineStart,
                expectedText = "",
                replacement = "$beforeIndent$reindented$newline",
            )
        } else if (closingPrefix.all(Char::isWhitespace)) {
            WorkspaceTextEdit(
                startOffset = closingLineStart,
                endOffset = closingLineStart,
                expectedText = "",
                replacement = "$childIndent$reindented$newline",
            )
        } else {
            WorkspaceTextEdit(
                startOffset = parent.endTagStart,
                endOffset = parent.endTagStart,
                expectedText = "",
                replacement = "$newline$childIndent$reindented$newline$parentIndent",
            )
        }
    }

    private fun nextCopyId(original: String, used: MutableSet<String>): String {
        val base = "${original}Copy"
        var candidate = base
        var suffix = 2
        while (candidate in used) {
            candidate = "$base$suffix"
            suffix += 1
        }
        used += candidate
        return candidate
    }

    private fun rewriteCopiedReference(value: String, copiedIds: Map<String, String>): String {
        copiedIds[value]?.let { return it }
        val prefix = copiedIds.keys
            .filter { value.startsWith("$it.") }
            .maxByOrNull(String::length)
            ?: return value
        return copiedIds.getValue(prefix) + value.removePrefix(prefix)
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
        structuralProposal(document, operationIdentity, label, listOf(edit))

    private fun structuralProposal(
        document: FlowUiDescriptorSnapshot,
        operationIdentity: String,
        label: String,
        edits: List<WorkspaceTextEdit>,
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
                        edits = edits,
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

    private fun escapeText(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun unescapeText(value: String): String =
        value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

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

    private data class RelativeReplacement(
        val start: Int,
        val end: Int,
        val value: String,
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

    private fun reindentElement(
        markup: String,
        oldIndent: String,
        newIndent: String,
        newline: String,
    ): String =
        markup.split(newline).mapIndexed { index, line ->
            if (index == 0 || line.isEmpty()) {
                line
            } else if (line.startsWith(oldIndent)) {
                newIndent + line.removePrefix(oldIndent)
            } else {
                newIndent + line.trimStart()
            }
        }.joinToString(newline)

    private const val MAX_XML_NAME_LENGTH = 120
    private const val MAX_INSERT_ATTRIBUTES = 100
    private val PROPERTY_NAME = Regex("""[A-Za-z_][A-Za-z0-9_.:-]*""")
    private val XML_NAME = Regex("""[A-Za-z_][A-Za-z0-9_.:-]*""")
    private val CONVERTIBLE_LAYOUT_TAGS = setOf("vbox", "hbox", "flexLayout", "formLayout", "gridLayout")
    private val LAYOUT_SPECIFIC_PROPERTIES = mapOf(
        "vbox" to setOf("spacing", "padding", "margin", "alignItems", "justifyContent", "expand"),
        "hbox" to setOf("spacing", "padding", "margin", "alignItems", "justifyContent", "wrap", "expand"),
        "flexLayout" to setOf(
            "flexDirection",
            "flexWrap",
            "justifyContent",
            "alignItems",
            "alignContent",
        ),
        "formLayout" to setOf(
            "autoResponsive",
            "minColumns",
            "maxColumns",
            "columnWidth",
            "labelsPosition",
            "responsiveSteps",
        ),
        "gridLayout" to setOf("columnMinWidth", "gap"),
    )
    private val ALL_LAYOUT_SPECIFIC_PROPERTIES = LAYOUT_SPECIFIC_PROPERTIES.values.flatten().toSet()
}

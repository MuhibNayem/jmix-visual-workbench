package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.util.ProcessingContext

/**
 * Native XML references for Jmix localization, menu declarations and menu to
 * view navigation. These references deliberately resolve to the same symbols
 * used by Java/Kotlin security policies so cross-file rename is symmetric.
 */
class JmixUiXmlReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            JmixUiXmlReferenceProvider,
        )
    }
}

internal object JmixUiXmlReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val file = value.containingFile as? XmlFile ?: return PsiReference.EMPTY_ARRAY
        val raw = value.value
        if (raw.isBlank()) return PsiReference.EMPTY_ARRAY

        if ((file.isJmixFlowUiDescriptor() ||
                file.rootTag?.localName == "window" ||
                file.isJmixMenuDescriptor()) &&
            raw.startsWith(JMIX_MESSAGE_PREFIX)
        ) {
            val target = jmixMessageTarget(
                value,
                raw,
                rangeOffset = 1,
            ) ?: return PsiReference.EMPTY_ARRAY
            return arrayOf(
                JmixXmlMessageReference(
                    value,
                    target.range,
                    target.logicalKey,
                    target.style,
                    target.group,
                ),
            )
        }

        if (!file.isJmixMenuDescriptor()) return PsiReference.EMPTY_ARRAY
        val tag = attribute.parent
        if (attribute.localName == "id" &&
            (tag.localName == "menu" || tag.localName == "item")
        ) {
            val references = mutableListOf<PsiReference>(
                JmixXmlMenuIdDeclarationReference(
                    value,
                    quotedXmlRange(raw),
                    attribute,
                ),
            )
            if (tag.localName == "item" &&
                tag.getAttribute("view") == null &&
                tag.getAttribute("screen") == null &&
                tag.getAttribute("bean") == null
            ) {
                references += JmixXmlViewIdReference(
                    value,
                    quotedXmlRange(raw),
                    raw,
                )
            }
            return references.toTypedArray()
        }
        if (attribute.localName in setOf("view", "screen") &&
            tag.localName == "item"
        ) {
            return arrayOf(
                JmixXmlViewIdReference(
                    value,
                    quotedXmlRange(raw),
                    raw,
                ),
            )
        }
        return PsiReference.EMPTY_ARRAY
    }
}

internal class JmixXmlViewIdReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val viewId: String,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateDeclarations()
            .filter { it.id == viewId }
            .map(::JmixViewIdElement)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateDeclarations()
            .asSequence()
            .take(JMIX_UI_COMPLETION_LIMIT)
            .map { declaration ->
                LookupElementBuilder.create(
                    JmixViewIdElement(declaration),
                    declaration.id,
                ).withTypeText(
                    declaration.controller?.qualifiedName
                        ?: declaration.valueElement.containingFile?.name.orEmpty(),
                    true,
                )
            }
            .distinctBy { it.lookupString }
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val attribute = element.parent as? XmlAttribute ?: return element
        attribute.setValue(newElementName)
        return attribute.valueElement ?: element
    }

    internal fun candidateDeclarations(): List<JmixViewIdDeclaration> =
        jmixViewIdDeclarations(element)
}

internal class JmixXmlMenuIdDeclarationReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val declaration: XmlAttribute,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        arrayOf(
            PsiElementResolveResult(
                JmixXmlAttributeNamedElement(declaration, "Jmix menu item"),
            ),
        )

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        declaration.setValue(newElementName)
        return declaration.valueElement ?: element
    }
}

internal class JmixXmlMessageReference(
    element: XmlAttributeValue,
    range: TextRange,
    internal val logicalKey: String,
    private val style: JmixMessageReferenceStyle,
    private val group: String,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateDeclarations()
            .filter { logicalKey in it.lookupKeys }
            .map { PsiElementResolveResult(it.property) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateDeclarations()
            .asSequence()
            .mapNotNull { declaration ->
                declaration.insertionFor(style, group)?.let { insertion ->
                    LookupElementBuilder.create(declaration.property, insertion)
                        .withTypeText(
                            declaration.property.containingFile.name,
                            true,
                        )
                }
            }
            .distinctBy { it.lookupString }
            .take(JMIX_UI_COMPLETION_LIMIT)
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val replacement = renamedMessageReference(
            style,
            group,
            logicalKey,
            newElementName,
        )
        val attribute = element.parent as? XmlAttribute ?: return element
        val prefix = if (style == JmixMessageReferenceStyle.GLOBAL) {
            JMIX_GLOBAL_MESSAGE_PREFIX
        } else {
            JMIX_MESSAGE_PREFIX
        }
        attribute.setValue(prefix + replacement)
        return attribute.valueElement ?: element
    }

    internal fun candidateDeclarations(): List<JmixMessageDeclaration> =
        jmixMessageDeclarations(element)
}

internal enum class JmixMessageReferenceStyle {
    LOCAL,
    FULL,
    GLOBAL,
}

internal data class JmixMessageTarget(
    val logicalKey: String,
    val style: JmixMessageReferenceStyle,
    val group: String,
    val range: TextRange,
)

internal fun jmixMessageTarget(
    context: PsiElement,
    raw: String,
    rangeOffset: Int = 0,
): JmixMessageTarget? {
    if (raw.startsWith(JMIX_GLOBAL_MESSAGE_PREFIX)) {
        val key = raw.removePrefix(JMIX_GLOBAL_MESSAGE_PREFIX)
            .takeIf(String::isNotBlank)
            ?: return null
        return JmixMessageTarget(
            logicalKey = key,
            style = JmixMessageReferenceStyle.GLOBAL,
            group = "",
            range = TextRange(
                rangeOffset + JMIX_GLOBAL_MESSAGE_PREFIX.length,
                rangeOffset + raw.length,
            ),
        )
    }
    if (!raw.startsWith(JMIX_MESSAGE_PREFIX)) return null
    val key = raw.removePrefix(JMIX_MESSAGE_PREFIX)
        .takeIf(String::isNotBlank)
        ?: return null
    val file = context.containingFile as? XmlFile
    val explicitGroup = key.substringBeforeLast('/', "")
    val group = when {
        explicitGroup.isNotBlank() -> explicitGroup
        file != null ->
            file.rootTag?.getAttributeValue("messagesGroup")
                ?.takeIf(String::isNotBlank)
                ?: file.jmixResourcePackage()

        else -> context.jmixResourcePackage()
    }
    val style = if ('/' in key) {
        JmixMessageReferenceStyle.FULL
    } else {
        JmixMessageReferenceStyle.LOCAL
    }
    val logical = if (style == JmixMessageReferenceStyle.LOCAL && group.isNotBlank()) {
        "$group/$key"
    } else {
        key
    }
    return JmixMessageTarget(
        logicalKey = logical,
        style = style,
        group = group,
        range = TextRange(
            rangeOffset + JMIX_MESSAGE_PREFIX.length,
            rangeOffset + raw.length,
        ),
    )
}

internal fun renamedMessageReference(
    style: JmixMessageReferenceStyle,
    group: String,
    oldLogicalKey: String,
    newPropertyName: String,
): String =
    when (style) {
        JmixMessageReferenceStyle.LOCAL -> newPropertyName.substringAfterLast('/')
        JmixMessageReferenceStyle.FULL -> {
            if ('/' in newPropertyName) {
                newPropertyName
            } else {
                val existingGroup = oldLogicalKey.substringBeforeLast('/', group)
                if (existingGroup.isBlank()) newPropertyName else "$existingGroup/$newPropertyName"
            }
        }
        JmixMessageReferenceStyle.GLOBAL -> newPropertyName
    }

private fun JmixMessageDeclaration.insertionFor(
    style: JmixMessageReferenceStyle,
    group: String,
): String? =
    when (style) {
        JmixMessageReferenceStyle.GLOBAL -> key.takeIf { '/' !in it }
        JmixMessageReferenceStyle.FULL ->
            lookupKeys.firstOrNull { '/' in it } ?: key

        JmixMessageReferenceStyle.LOCAL -> {
            val sameGroup = lookupKeys.firstOrNull { candidate ->
                candidate.substringBeforeLast('/', "") == group
            }
            sameGroup?.substringAfterLast('/')
                ?: lookupKeys.firstOrNull { '/' in it }
                ?: key
        }
    }

private fun quotedXmlRange(value: String): TextRange = TextRange(1, value.length + 1)

internal const val JMIX_MESSAGE_PREFIX = "msg://"
internal const val JMIX_GLOBAL_MESSAGE_PREFIX = "msg:///"

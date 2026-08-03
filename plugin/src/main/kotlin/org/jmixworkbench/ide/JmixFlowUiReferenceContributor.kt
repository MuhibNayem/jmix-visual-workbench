package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Native IntelliJ references for FlowUI component, container, loader and action
 * identifiers. These references power Ctrl/Cmd+B, reference completion and
 * safe usage-side rename directly in the XML editor.
 */
class JmixFlowUiReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            JmixFlowUiReferenceProvider,
        )
    }
}

internal object JmixFlowUiReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val file = value.containingFile as? XmlFile ?: return PsiReference.EMPTY_ARRAY
        if (!file.isJmixFlowUiDescriptor()) return PsiReference.EMPTY_ARRAY

        val rawValue = value.value
        if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY
        val identifierDeclaration =
            JmixFlowUiMetadata.injectionIdentifierAttributes(attribute.parent)
                .any { candidate -> candidate === attribute }
        if (identifierDeclaration) {
            return arrayOf(
                JmixFlowUiIdDeclarationReference(
                    value,
                    valueRange(rawValue),
                    attribute,
                ),
            )
        }
        return when (attribute.localName) {
            in DIRECT_ID_REFERENCE_ATTRIBUTES ->
                arrayOf(JmixFlowUiIdReference(value, valueRange(rawValue), rawValue))

            "action" -> actionReferences(value, rawValue)
            else -> PsiReference.EMPTY_ARRAY
        }
    }

    private fun actionReferences(
        value: XmlAttributeValue,
        rawValue: String,
    ): Array<PsiReference> {
        val separator = rawValue.indexOf('.')
        if (separator <= 0 || separator == rawValue.lastIndex) {
            return arrayOf(
                JmixFlowUiIdReference(
                    value,
                    valueRange(rawValue),
                    rawValue,
                    requiredTag = "action",
                ),
            )
        }
        val ownerId = rawValue.substring(0, separator)
        val actionId = rawValue.substring(separator + 1)
        return arrayOf(
            JmixFlowUiIdReference(
                value,
                TextRange(1, separator + 1),
                ownerId,
            ),
            JmixFlowUiIdReference(
                value,
                TextRange(separator + 2, rawValue.length + 1),
                actionId,
                requiredTag = "action",
                ownerId = ownerId,
            ),
        )
    }

    private fun valueRange(value: String): TextRange = TextRange(1, value.length + 1)

    private val DIRECT_ID_REFERENCE_ATTRIBUTES = setOf(
        "dataContainer",
        "itemsContainer",
        "dataLoader",
        "loader",
        "container",
        "for",
        "component",
        "target",
        "to",
    )
}

internal class JmixFlowUiIdReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val id: String,
    private val requiredTag: String? = null,
    private val ownerId: String? = null,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidates().map(::PsiElementResolveResult).toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateAttributes()
            .mapNotNull { attribute ->
                val name = attribute.value?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                LookupElementBuilder.create(name)
                    .withTypeText(attribute.parent.localName, true)
            }
            .distinctBy { it.lookupString }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val raw = element.value
        val start = rangeInElement.startOffset - 1
        val end = rangeInElement.endOffset - 1
        if (start < 0 || end > raw.length || start > end) return element
        val attribute = element.parent as? XmlAttribute ?: return element
        attribute.setValue(raw.replaceRange(start, end, newElementName))
        return attribute.valueElement ?: element
    }

    internal fun candidateAttributes(): List<XmlAttribute> {
        val file = element.containingFile as? XmlFile ?: return emptyList()
        val allTags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
        val owner = ownerId?.let { expectedOwner ->
            allTags.firstOrNull { tag ->
                JmixFlowUiMetadata.injectionIdentifierAttributes(tag)
                    .any { attribute -> attribute.value == expectedOwner }
            }
        }
        val scope = owner?.let { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) } ?: allTags
        return scope.asSequence()
            .filter { requiredTag == null || it.localName == requiredTag }
            .flatMap { tag ->
                JmixFlowUiMetadata.injectionIdentifierAttributes(tag)
                    .asSequence()
            }
            .toList()
    }

    private fun candidates(): List<PsiElement> =
        candidateAttributes()
            .filter { it.value == id }
            .map(::JmixFlowUiIdElement)
}

internal class JmixFlowUiIdDeclarationReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val declaration: XmlAttribute,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        arrayOf(PsiElementResolveResult(JmixFlowUiIdElement(declaration)))

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        declaration.setValue(newElementName)
        return declaration.valueElement ?: element
    }
}

/**
 * A stable named semantic element layered over a standard XML `id` attribute.
 * IntelliJ's rename and find-usages engines require a [PsiNamedElement]; the
 * XML PSI value itself is not named. Equivalence is based on the physical
 * declaration, so independently resolved wrappers remain the same symbol.
 */
internal class JmixFlowUiIdElement(
    internal val declaration: XmlAttribute,
) : FakePsiElement() {
    override fun getParent(): PsiElement = declaration

    override fun getManager(): PsiManager = declaration.manager

    override fun getContainingFile(): PsiFile = declaration.containingFile

    override fun getName(): String = declaration.value.orEmpty()

    override fun setName(name: String): PsiElement {
        declaration.setValue(name)
        return this
    }

    override fun getText(): String = name

    override fun getNavigationElement(): PsiElement = declaration.valueElement ?: declaration

    override fun getTextRange(): TextRange = navigationElement.textRange

    override fun getTextOffset(): Int = navigationElement.textOffset

    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)

    override fun isValid(): Boolean = declaration.isValid

    override fun isWritable(): Boolean = declaration.isWritable

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another is JmixFlowUiIdElement &&
            manager.areElementsEquivalent(declaration, another.declaration)

    override fun getPresentableText(): String = name

    override fun toString(): String = "Jmix FlowUI ID '$name'"
}

internal fun XmlFile.isJmixFlowUiDescriptor(): Boolean {
    val root = rootTag ?: return false
    if (root.localName != "view" && root.localName != "fragment") return false
    val namespace = root.namespace.orEmpty()
    return namespace.isBlank() ||
        "jmix" in namespace.lowercase() ||
        root.attributes.any { it.localName in setOf("focusComponent", "title") }
}

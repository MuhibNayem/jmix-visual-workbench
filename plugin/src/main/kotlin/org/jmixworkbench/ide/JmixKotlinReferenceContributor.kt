package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * Kotlin controller support implemented through stable platform PSI contracts.
 * This keeps the plugin binary independent of Kotlin compiler internals while
 * still providing native references when the bundled Kotlin plugin is enabled.
 */
class JmixKotlinReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLanguageInjectionHost::class.java),
            JmixKotlinReferenceProvider,
        )
    }
}

internal object JmixKotlinReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val host = element as? PsiLanguageInjectionHost ?: return PsiReference.EMPTY_ARRAY
        if (host.javaClass.simpleName != "KtStringTemplateExpression") {
            return PsiReference.EMPTY_ARRAY
        }
        val valueRange = host.kotlinStringContentRange() ?: return PsiReference.EMPTY_ARRAY
        val value = valueRange.substring(host.text)
        if (value.isBlank() || '$' in value) return PsiReference.EMPTY_ARRAY
        val annotation = host.kotlinAnnotationContext()
        if (annotation?.isJmixSpringBeanNameDeclaration() == true) {
            return arrayOf(
                JmixKotlinSpringBeanDeclarationReference(
                    host,
                    valueRange,
                    value,
                ),
            )
        }
        jmixKotlinUiSecurityReferences(host, valueRange, value, annotation)
            ?.let { return it }
        annotation ?: return PsiReference.EMPTY_ARRAY
        if (annotation.name == "ViewDescriptor" &&
            (annotation.attributeName == "value" || annotation.attributeName == null)
        ) {
            return arrayOf(JmixKotlinDescriptorReference(host, valueRange, value))
        }
        val actionPathAllowed = when (annotation.name) {
            "ViewComponent" ->
                false.takeIf { annotation.attributeName == "value" || annotation.attributeName == null }

            "Subscribe" ->
                true.takeIf {
                    annotation.attributeName == "id" ||
                        annotation.attributeName == "value" ||
                        annotation.attributeName == null
                }

            "Install", "Supply" ->
                true.takeIf { annotation.attributeName == "to" }

            else -> null
        } ?: return PsiReference.EMPTY_ARRAY
        return controllerReferences(
            host,
            valueRange,
            value,
            actionPathAllowed,
            host.kotlinControllerTargetTags(),
        )
    }

    private fun controllerReferences(
        host: PsiLanguageInjectionHost,
        valueRange: TextRange,
        value: String,
        actionPathAllowed: Boolean,
        targetTags: Set<String>?,
    ): Array<PsiReference> {
        val separator = value.indexOf('.')
        if (!actionPathAllowed ||
            separator <= 0 ||
            separator == value.lastIndex ||
            targetTags != null
        ) {
            return arrayOf(
                JmixKotlinFlowUiIdReference(
                    host,
                    valueRange,
                    value,
                    acceptedTags = targetTags,
                ),
            )
        }
        val contentStart = valueRange.startOffset
        val ownerId = value.substring(0, separator)
        val actionId = value.substring(separator + 1)
        return arrayOf(
            JmixKotlinFlowUiIdReference(
                host,
                TextRange(contentStart, contentStart + separator),
                ownerId,
            ),
            JmixKotlinFlowUiIdReference(
                host,
                TextRange(contentStart + separator + 1, valueRange.endOffset),
                actionId,
                acceptedTags = setOf("action"),
                ownerId = ownerId,
            ),
        )
    }
}

internal class JmixKotlinDescriptorReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val descriptorPath: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        findJmixDescriptorFiles(element, descriptorPath)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        findAllJmixDescriptorFiles(element)
            .take(JMIX_KOTLIN_MAX_COMPLETION_FILES)
            .map { file ->
                LookupElementBuilder.create(file.name)
                    .withTypeText(file.virtualFile.parent?.path.orEmpty(), true)
                    .withIcon(file.getIcon(0))
            }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val parentPath = descriptorPath.substringBeforeLast('/', "")
        val replacement = if (parentPath.isBlank()) newElementName else "$parentPath/$newElementName"
        return ElementManipulators.handleContentChange(element, rangeInElement, replacement)
    }
}

internal class JmixKotlinFlowUiIdReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val id: String,
    private val acceptedTags: Set<String>? = null,
    private val ownerId: String? = null,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateAttributes()
            .filter { it.value == id }
            .map(::JmixFlowUiIdElement)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateAttributes()
            .mapNotNull { attribute ->
                val name = attribute.value?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                LookupElementBuilder.create(name)
                    .withTypeText(attribute.parent.localName, true)
            }
            .distinctBy { it.lookupString }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)

    internal fun candidateAttributes(): List<XmlAttribute> =
        element.kotlinAssociatedDescriptorFiles().flatMap { file ->
            val allTags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            val owner = ownerId?.let { expectedOwner ->
                allTags.firstOrNull { it.getAttributeValue("id") == expectedOwner }
            }
            val scope = owner?.let { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) } ?: allTags
            scope.asSequence()
                .filter { acceptedTags == null || it.localName in acceptedTags }
                .mapNotNull { it.getAttribute("id") }
                .filter { !it.value.isNullOrBlank() }
                .toList()
        }
}

internal data class KotlinAnnotationContext(
    val name: String,
    val attributeName: String?,
)

internal fun PsiLanguageInjectionHost.kotlinAnnotationContext(): KotlinAnnotationContext? {
    val annotation = generateSequence(parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtAnnotationEntry" }
        ?: return null
    val name = KOTLIN_ANNOTATION_NAME.find(annotation.text)?.groupValues?.get(1)
        ?: return null
    val relativeStart = textRange.startOffset - annotation.textRange.startOffset
    if (relativeStart !in 0..annotation.textLength) return null
    val prefix = annotation.text.substring(0, relativeStart)
    val attributeName = KOTLIN_NAMED_ARGUMENT.findAll(prefix)
        .lastOrNull()
        ?.groupValues
        ?.get(1)
    return KotlinAnnotationContext(name, attributeName)
}

private fun PsiLanguageInjectionHost.kotlinControllerTargetTags(): Set<String>? {
    val annotation = generateSequence(parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtAnnotationEntry" }
        ?: return null
    val target = KOTLIN_CONTROLLER_TARGET.find(annotation.text)
        ?.groupValues
        ?.get(1)
    return jmixControllerTargetTags(target)
}

private fun PsiLanguageInjectionHost.kotlinAssociatedDescriptorFiles(): List<XmlFile> {
    val declaration = generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }
        ?: return emptyList()
    val path = KOTLIN_VIEW_DESCRIPTOR.find(declaration.text)?.groupValues?.get(1)
        ?: return emptyList()
    return findJmixDescriptorFiles(this, path)
}

internal fun PsiLanguageInjectionHost.kotlinStringContentRange(): TextRange? {
    val source = text
    return when {
        source.length >= 6 && source.startsWith("\"\"\"") && source.endsWith("\"\"\"") ->
            TextRange(3, source.length - 3)

        source.length >= 2 && source.startsWith('"') && source.endsWith('"') ->
            TextRange(1, source.length - 1)

        else -> null
    }
}

private val KOTLIN_ANNOTATION_NAME = Regex("""@(?:[\w.]+\.)?(\w+)""")
private val KOTLIN_NAMED_ARGUMENT = Regex("""\b(\w+)\s*=""")
private val KOTLIN_VIEW_DESCRIPTOR =
    Regex("@(?:[\\w.]+\\.)?ViewDescriptor\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"$]+)\"")
private val KOTLIN_CONTROLLER_TARGET =
    Regex("""\btarget\s*=\s*(?:Target\.)?(\w+)""")
private const val JMIX_KOTLIN_MAX_COMPLETION_FILES = 2_000

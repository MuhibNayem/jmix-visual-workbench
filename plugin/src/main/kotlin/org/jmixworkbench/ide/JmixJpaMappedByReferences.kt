package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiType
import com.intellij.psi.ResolveResult
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiManager
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

/**
 * Makes the string side of JPA bidirectional relationships participate in
 * IntelliJ navigation, completion, Find Usages and Rename.
 *
 * This deliberately resolves to the source declaration (including a Kotlin
 * property's navigation element), so a native property rename updates every
 * `mappedBy` occurrence instead of leaving a runtime-only mapping defect.
 */
internal fun jmixJavaMappedByReference(
    literal: PsiLiteralExpression,
    value: String,
): JmixJavaMappedByReference? {
    if (value.isBlank()) return null
    val pair = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java, false)
        ?: return null
    if (pair.name != "mappedBy") return null
    val annotation = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java, false)
        ?: return null
    if (annotation.shortName() !in JPA_RELATION_ANNOTATIONS) return null
    return JmixJavaMappedByReference(
        literal,
        TextRange(1, literal.textLength - 1),
        value,
    )
}

internal class JmixJavaMappedByReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val propertyName: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidates()
            .filter { it.name == propertyName }
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidates()
            .map { candidate ->
                LookupElementBuilder.create(candidate, candidate.name.orEmpty())
                    .withTypeText(candidate.containingFile?.name.orEmpty(), true)
            }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val replacement = JavaPsiFacade.getElementFactory(element.project)
            .createExpressionFromText(
                "\"${newElementName.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
                element,
            )
        return element.replace(replacement)
    }

    private fun candidates(): List<PsiNamedElement> {
        val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java, false)
            ?: return emptyList()
        val declaredType = PsiTreeUtil.getParentOfType(annotation, PsiField::class.java, false)
            ?.type
            ?: PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java, false)
                ?.returnType
            ?: return emptyList()
        return (
            relationshipTargetClass(declaredType, element)
                ?: relationshipTargetClassByName(declaredType, element)
            )
            ?.allFields
            .orEmpty()
            .asSequence()
            .filterNot(PsiField::hasModifierPropertyStatic)
            .mapNotNull(::sourceNamedElement)
            .distinctBy { it.name }
            .toList()
    }
}

internal fun jmixKotlinMappedByReference(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    value: String,
    context: KotlinAnnotationContext?,
): JmixKotlinMappedByReference? {
    if (
        value.isBlank() ||
        context == null ||
        context.name !in JPA_RELATION_ANNOTATIONS ||
        context.attributeName != "mappedBy"
    ) {
        return null
    }
    return JmixKotlinMappedByReference(host, valueRange, value)
}

internal class JmixKotlinMappedByReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val propertyName: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidates()
            .filter { it.name == propertyName }
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidates()
            .map { candidate ->
                LookupElementBuilder.create(candidate, candidate.name.orEmpty())
                    .withTypeText(candidate.containingFile?.name.orEmpty(), true)
            }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)

    private fun candidates(): List<PsiNamedElement> {
        val property = generateSequence(element.parent) { it.parent }
            .firstOrNull { it.javaClass.simpleName == "KtProperty" }
            ?: return emptyList()
        val typeName = KOTLIN_PROPERTY_TYPE.find(property.text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.removeSuffix("?")
            ?.let(::relationshipTargetTypeName)
            ?: return emptyList()
        val qualifiedName = kotlinQualifiedTypeName(property, typeName)
        val target = JavaPsiFacade.getInstance(element.project).findClass(
            qualifiedName,
            element.resolveScope,
        ) ?: return emptyList()
        return target.allFields
            .asSequence()
            .filterNot(PsiField::hasModifierPropertyStatic)
            .mapNotNull(::sourceNamedElement)
            .distinctBy { it.name }
            .toList()
    }
}

/**
 * Adds Java/Kotlin annotation strings to native reference searches through the
 * word index. This avoids repository-wide PSI walks while ensuring Rename and
 * Find Usages discover contributed `mappedBy` references.
 */
class JmixJpaMappedByReferenceSearchExecutor :
    QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    override fun execute(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ): Boolean {
        val target = queryParameters.elementToSearch as? PsiNamedElement ?: return true
        val name = target.name?.takeIf(String::isNotBlank) ?: return true
        if (
            target !is PsiField &&
            target.javaClass.simpleName != "KtProperty"
        ) {
            return true
        }
        val manager = PsiManager.getInstance(target.project)
        return PsiSearchHelper.getInstance(target.project).processElementsWithWord(
            { occurrence, _ ->
                val references = generateSequence(occurrence) { it.parent }
                    .take(8)
                    .flatMap { it.references.asSequence() }
                    .filter {
                        it is JmixJavaMappedByReference ||
                            it is JmixKotlinMappedByReference
                    }
                    .distinct()
                for (reference in references) {
                    val resolved = reference.resolve() ?: continue
                    if (manager.areElementsEquivalent(resolved, target) && !consumer.process(reference)) {
                        return@processElementsWithWord false
                    }
                }
                true
            },
            queryParameters.effectiveSearchScope,
            name,
            UsageSearchContext.IN_STRINGS,
            true,
        )
    }
}

private fun relationshipTargetClass(type: PsiType, context: PsiElement): PsiClass? {
    val classType = type as? PsiClassType ?: return relationshipTargetClassByName(type, context)
    val resolved = classType.resolve() ?: return relationshipTargetClassByName(type, context)
    if (resolved.qualifiedName in COLLECTION_TYPES) {
        val parameter = classType.parameters.singleOrNull()
            ?: return relationshipTargetClassByName(type, context)
        PsiTypesUtil.getPsiClass(parameter)?.let { return it }
        val simpleName = parameter.presentableText
            .substringAfterLast('.')
            .removeSuffix("?")
        return resolveShortClass(simpleName, context)
    }
    return resolved
}

private fun relationshipTargetClassByName(type: PsiType, context: PsiElement): PsiClass? {
    val simpleName = relationshipTargetTypeName(type.presentableText)
        .substringAfterLast('.')
    return resolveShortClass(simpleName, context)
}

private fun resolveShortClass(simpleName: String, context: PsiElement): PsiClass? {
    val ownerPackage = PsiTreeUtil.getParentOfType(context, PsiClass::class.java, false)
        ?.qualifiedName
        ?.substringBeforeLast('.', "")
    val candidates = PsiShortNamesCache.getInstance(context.project)
        .getClassesByName(simpleName, context.resolveScope)
    return candidates.firstOrNull {
        ownerPackage != null && it.qualifiedName == "$ownerPackage.$simpleName"
    } ?: candidates.firstOrNull()
}

private fun relationshipTargetTypeName(declared: String): String =
    declared.substringAfter('<', declared)
        .substringBeforeLast('>', declared)
        .substringBefore(',')
        .trim()
        .removeSuffix("?")

private fun kotlinQualifiedTypeName(element: PsiElement, typeName: String): String {
    if ('.' in typeName) return typeName
    val source = element.containingFile?.text.orEmpty()
    val imported = KOTLIN_IMPORT.findAll(source)
        .map { it.groupValues[1] }
        .firstOrNull { it.substringAfterLast('.') == typeName }
    if (imported != null) return imported
    val packageName = KOTLIN_PACKAGE.find(source)?.groupValues?.get(1).orEmpty()
    return if (packageName.isBlank()) typeName else "$packageName.$typeName"
}

private fun sourceNamedElement(field: PsiField): PsiNamedElement? =
    (field.navigationElement as? PsiNamedElement)
        ?.takeIf { it.name == field.name }
        ?: field

private fun PsiAnnotation.shortName(): String =
    qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.text?.substringAfterLast('.').orEmpty()

private fun PsiField.hasModifierPropertyStatic(): Boolean =
    hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)

private val JPA_RELATION_ANNOTATIONS =
    setOf("OneToOne", "OneToMany", "ManyToMany")
private val COLLECTION_TYPES =
    setOf(
        "java.util.Collection",
        "java.util.List",
        "java.util.Set",
        "java.lang.Iterable",
    )
private val KOTLIN_PROPERTY_TYPE =
    Regex("""(?s)\b(?:val|var)\s+[A-Za-z_$][A-Za-z0-9_$]*\s*:\s*([^=\n]+)""")
private val KOTLIN_IMPORT =
    Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$""")
private val KOTLIN_PACKAGE =
    Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$""")

package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import java.util.Locale

/**
 * Native, source-backed assistance for Jmix `rest-services.xml`.
 *
 * The runtime selects a method by bean name, method name, parameter count and,
 * when overloads share a count, optional parameter types. Parameter `name`
 * values are the public REST payload names; they are positionally associated
 * with source parameters and therefore remain navigable without incorrectly
 * requiring the public name to equal the Java/Kotlin source name.
 */
class JmixRestXmlReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            JmixRestXmlReferenceProvider,
        )
    }
}

internal object JmixRestXmlReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val file = value.containingFile as? XmlFile ?: return PsiReference.EMPTY_ARRAY
        if (!file.isJmixRestServicesDescriptor()) return PsiReference.EMPTY_ARRAY

        val tag = attribute.parent
        val raw = value.value
        val range = TextRange(1, raw.length + 1)
        return when {
            tag.localName == "service" && attribute.localName == "name" ->
                arrayOf(JmixRestServiceBeanReference(value, range, raw))

            tag.localName == "method" && attribute.localName == "name" ->
                arrayOf(JmixRestServiceMethodReference(value, range, raw))

            tag.localName == "param" && attribute.localName == "name" ->
                arrayOf(JmixRestServiceParameterReference(value, range))

            tag.localName == "param" && attribute.localName == "type" ->
                arrayOf(JmixRestServiceParameterTypeReference(value, range, raw))

            else -> PsiReference.EMPTY_ARRAY
        }
    }
}

internal class JmixRestServiceBeanReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val beanName: String,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateDeclarations()
            .filter { it.name == beanName }
            .map(::JmixSpringBeanElement)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateDeclarations()
            .asSequence()
            .map { declaration ->
                LookupElementBuilder.create(
                    JmixSpringBeanElement(declaration),
                    declaration.name,
                ).withTypeText(
                    declaration.classElement.containingFile?.name.orEmpty(),
                    true,
                )
            }
            .distinctBy { it.lookupString }
            .take(JMIX_REST_COMPLETION_LIMIT)
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val attribute = element.parent as? XmlAttribute ?: return element
        val declarations = candidateDeclarations()
        val declaration = declarations.singleOrNull { it.name == beanName }
            ?: declarations.singleOrNull {
                it.explicitNameElement == null &&
                    it.classElement.name == newElementName
            }
        val replacement = if (declaration?.explicitNameElement == null &&
            declaration != null
        ) {
            declaration.implicitNameKind.beanNameAfterBackingRename(newElementName)
        } else {
            newElementName
        }
        attribute.setValue(replacement)
        return attribute.valueElement ?: element
    }

    internal fun candidateDeclarations(): List<JmixSpringBeanDeclaration> =
        jmixSpringBeanDeclarations(element)
}

internal class JmixRestServiceMethodReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val methodName: String,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        resolvedMethods()
            .map { PsiElementResolveResult(it.element) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateMethods()
            .asSequence()
            .filter(JmixSpringBeanMethodDeclaration::isRestCallable)
            .map { method ->
                LookupElementBuilder.create(method.element, method.name)
                    .withTailText("  ${method.signature}", true)
                    .withTypeText(
                        method.element.containingFile?.name.orEmpty(),
                        true,
                    )
            }
            .take(JMIX_REST_COMPLETION_LIMIT)
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val attribute = element.parent as? XmlAttribute ?: return element
        attribute.setValue(newElementName)
        return attribute.valueElement ?: element
    }

    internal fun candidateBeans(): List<JmixSpringBeanDeclaration> =
        restServiceTag()?.getAttributeValue("name")
            ?.let { beanName ->
                jmixSpringBeanDeclarations(element).filter { it.name == beanName }
            }
            .orEmpty()

    internal fun candidateMethods(): List<JmixSpringBeanMethodDeclaration> =
        candidateBeans().flatMap(JmixSpringBeanDeclaration::methods)

    internal fun sameNameMethods(): List<JmixSpringBeanMethodDeclaration> =
        candidateMethods().filter { it.name == methodName }

    internal fun resolvedMethods(): List<JmixSpringBeanMethodDeclaration> {
        val methodTag = element.parentTag("method") ?: return emptyList()
        return methodTag.matchingRestMethods(
            sameNameMethods().filter(
                JmixSpringBeanMethodDeclaration::isRestCallable,
            ),
            ignoredTypeIndex = null,
        )
    }

    private fun restServiceTag(): XmlTag? =
        element.parentTag("method")?.parentTag?.takeIf { it.localName == "service" }
}

internal class JmixRestServiceParameterReference(
    element: XmlAttributeValue,
    range: TextRange,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateParameters()
            .map { PsiElementResolveResult(it.element) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateParameters()
            .asSequence()
            .map { parameter ->
                LookupElementBuilder.create(parameter.element, parameter.name)
                    .withTypeText(parameter.presentableType, true)
            }
            .distinctBy { it.lookupString }
            .take(JMIX_REST_COMPLETION_LIMIT)
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val attribute = element.parent as? XmlAttribute ?: return element
        attribute.setValue(newElementName)
        return attribute.valueElement ?: element
    }

    internal fun parameterIndex(): Int {
        val parameterTag = element.parentTag("param") ?: return -1
        val method = parameterTag.parentTag?.takeIf { it.localName == "method" }
            ?: return -1
        return method.subTags
            .filter { it.localName == "param" }
            .indexOf(parameterTag)
    }

    internal fun candidateParameters(): List<JmixSpringBeanMethodParameterDeclaration> {
        val index = parameterIndex()
        if (index < 0) return emptyList()
        return enclosingResolvedMethods()
            .mapNotNull { method -> method.parameters.getOrNull(index) }
            .distinctBy { parameter ->
                val file = parameter.element.containingFile?.virtualFile?.path.orEmpty()
                "$file:${parameter.element.textOffset}"
            }
    }

    internal fun enclosingResolvedMethods(): List<JmixSpringBeanMethodDeclaration> {
        val methodTag = element.parentTag("param")
            ?.parentTag
            ?.takeIf { it.localName == "method" }
            ?: return emptyList()
        val methodName = methodTag.getAttributeValue("name").orEmpty()
        val beanName = methodTag.parentTag
            ?.takeIf { it.localName == "service" }
            ?.getAttributeValue("name")
            .orEmpty()
        val methods = jmixSpringBeanDeclarations(element)
            .filter { it.name == beanName }
            .flatMap(JmixSpringBeanDeclaration::methods)
            .filter {
                it.name == methodName &&
                    it.isRestCallable
            }
        return methodTag.matchingRestMethods(methods, ignoredTypeIndex = null)
    }
}

internal class JmixRestServiceParameterTypeReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val configuredType: String,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        if (configuredType.isBlank() || configuredType.isPrimitiveRestType()) {
            return ResolveResult.EMPTY_ARRAY
        }
        val scope = ProjectScope.getAllScope(element.project)
        return configuredType.restTypeClassNames()
            .asSequence()
            .flatMap { className ->
                JavaPsiFacade.getInstance(element.project)
                    .findClasses(className, scope)
                    .asSequence()
            }
            .distinctBy(PsiClass::getQualifiedName)
            .map(::PsiElementResolveResult)
            .toList()
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> =
        candidateParameters()
            .asSequence()
            .map { parameter ->
                parameter.canonicalType.toRestConfigurationType()
            }
            .filter(String::isNotBlank)
            .distinct()
            .take(JMIX_REST_COMPLETION_LIMIT)
            .map { type ->
                LookupElementBuilder.create(type).withTypeText("method parameter", true)
            }
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val attribute = element.parent as? XmlAttribute ?: return element
        val arraySuffix = configuredType
            .takeLastWhile { it == '[' || it == ']' }
        val baseType = configuredType.dropLast(arraySuffix.length)
        val packageName = baseType.substringBeforeLast('.', "")
        val replacement = if (packageName.isBlank()) {
            "$newElementName$arraySuffix"
        } else {
            "$packageName.$newElementName$arraySuffix"
        }
        attribute.setValue(replacement)
        return attribute.valueElement ?: element
    }

    internal fun parameterIndex(): Int {
        val parameterTag = element.parentTag("param") ?: return -1
        val method = parameterTag.parentTag?.takeIf { it.localName == "method" }
            ?: return -1
        return method.subTags
            .filter { it.localName == "param" }
            .indexOf(parameterTag)
    }

    internal fun candidateMethodsIgnoringOwnType():
        List<JmixSpringBeanMethodDeclaration> {
        val parameterTag = element.parentTag("param") ?: return emptyList()
        val methodTag = parameterTag.parentTag
            ?.takeIf { it.localName == "method" }
            ?: return emptyList()
        val methodName = methodTag.getAttributeValue("name").orEmpty()
        val beanName = methodTag.parentTag
            ?.takeIf { it.localName == "service" }
            ?.getAttributeValue("name")
            .orEmpty()
        val methods = jmixSpringBeanDeclarations(element)
            .filter { it.name == beanName }
            .flatMap(JmixSpringBeanDeclaration::methods)
            .filter {
                it.name == methodName &&
                    it.isRestCallable
            }
        return methodTag.matchingRestMethods(
            methods,
            ignoredTypeIndex = parameterIndex(),
        )
    }

    internal fun candidateParameters(): List<JmixSpringBeanMethodParameterDeclaration> {
        val index = parameterIndex()
        if (index < 0) return emptyList()
        return candidateMethodsIgnoringOwnType()
            .mapNotNull { it.parameters.getOrNull(index) }
            .distinctBy { parameter ->
                val file = parameter.element.containingFile?.virtualFile?.path.orEmpty()
                "$file:${parameter.element.textOffset}"
            }
    }

    internal fun matchesConfiguredType(
        parameter: JmixSpringBeanMethodParameterDeclaration,
    ): Boolean = configuredType.matchesRestParameterType(parameter.canonicalType)
}

internal data class JmixRestParameterSpec(
    val name: String,
    val type: String,
)

internal fun XmlFile.isJmixRestServicesDescriptor(): Boolean {
    val root = rootTag ?: return false
    if (root.localName != "services") return false
    val namespace = root.namespace.orEmpty().lowercase(Locale.ROOT)
    return namespace.contains("jmix.io/schema/rest/services") ||
        namespace.isBlank() && name.lowercase(Locale.ROOT).contains("rest-services")
}

internal fun XmlTag.restParameterSpecs(): List<JmixRestParameterSpec> =
    subTags.asSequence()
        .filter { it.localName == "param" }
        .map { parameter ->
            JmixRestParameterSpec(
                name = parameter.getAttributeValue("name").orEmpty(),
                type = parameter.getAttributeValue("type").orEmpty(),
            )
        }
        .toList()

internal fun XmlTag.matchingRestMethods(
    methods: List<JmixSpringBeanMethodDeclaration>,
    ignoredTypeIndex: Int?,
): List<JmixSpringBeanMethodDeclaration> {
    val specs = restParameterSpecs()
    return methods.filter { method ->
        method.parameters.size == specs.size &&
            specs.indices.all { index ->
                index == ignoredTypeIndex ||
                    specs[index].type.isBlank() ||
                    specs[index].type.matchesRestParameterType(
                        method.parameters[index].canonicalType,
                    )
            }
    }
}

internal fun String.matchesRestParameterType(sourceType: String): Boolean {
    val configured = normalizeRestType()
    if (configured.isBlank()) return true
    val sourceVariants = sourceType.restTypeVariants()
    return configured in sourceVariants ||
        configured.substringAfterLast('.') in sourceVariants
}

private fun String.restTypeVariants(): Set<String> {
    val source = normalizeRestType()
    val raw = source.removeRestGenericArguments()
    val withoutNullable = raw.removeSuffix("?")
    val mapped = KOTLIN_JVM_REST_TYPES[withoutNullable]
    return buildSet {
        add(source)
        add(raw)
        add(withoutNullable)
        add(withoutNullable.substringAfterLast('.'))
        if (mapped != null) {
            add(mapped)
            add(mapped.substringAfterLast('.'))
        }
    }.filter(String::isNotBlank).toSet()
}

private fun String.toRestConfigurationType(): String {
    val normalized = normalizeRestType().removeRestGenericArguments().removeSuffix("?")
    return KOTLIN_JVM_REST_TYPES[normalized] ?: normalized
}

private fun String.restTypeClassNames(): Set<String> {
    val normalized = normalizeRestType()
        .removeRestGenericArguments()
        .removeSuffix("?")
        .removeSuffix("[]")
    val mapped = KOTLIN_JVM_REST_TYPES[normalized]
    return buildSet {
        if ('.' in normalized) add(normalized)
        if (mapped != null && !mapped.isPrimitiveRestType()) add(mapped)
        if ('.' !in normalized) add("java.lang.$normalized")
    }
}

private fun String.normalizeRestType(): String =
    replace(Regex("""\s+"""), "")
        .removePrefix("vararg")

private fun String.removeRestGenericArguments(): String {
    val normalized = normalizeRestType()
    val open = normalized.indexOf('<')
    if (open < 0) return normalized
    var depth = 0
    var cursor = open
    while (cursor < normalized.length) {
        when (normalized[cursor]) {
            '<' -> depth++
            '>' -> {
                depth--
                if (depth == 0) {
                    return normalized.removeRange(open, cursor + 1)
                }
            }
        }
        cursor++
    }
    return normalized.substring(0, open)
}

private fun String.isPrimitiveRestType(): Boolean =
    normalizeRestType() in REST_PRIMITIVE_TYPES

private fun PsiElement.parentTag(localName: String): XmlTag? =
    generateSequence(parent) { it.parent }
        .filterIsInstance<XmlTag>()
        .firstOrNull { it.localName == localName }

private const val JMIX_REST_COMPLETION_LIMIT = 1_000

private val REST_PRIMITIVE_TYPES = setOf(
    "boolean",
    "byte",
    "char",
    "double",
    "float",
    "int",
    "long",
    "short",
    "void",
)

private val KOTLIN_JVM_REST_TYPES = mapOf(
    "Any" to "java.lang.Object",
    "kotlin.Any" to "java.lang.Object",
    "Boolean" to "boolean",
    "kotlin.Boolean" to "boolean",
    "Byte" to "byte",
    "kotlin.Byte" to "byte",
    "Char" to "char",
    "kotlin.Char" to "char",
    "Double" to "double",
    "kotlin.Double" to "double",
    "Float" to "float",
    "kotlin.Float" to "float",
    "Int" to "int",
    "kotlin.Int" to "int",
    "Long" to "long",
    "kotlin.Long" to "long",
    "Short" to "short",
    "kotlin.Short" to "short",
    "String" to "java.lang.String",
    "kotlin.String" to "java.lang.String",
    "Unit" to "void",
    "kotlin.Unit" to "void",
)

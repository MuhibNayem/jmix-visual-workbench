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
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.ResolveResult
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import java.util.Locale

/**
 * Native references for Jmix Data Repository @Query strings.
 *
 * The repository generic identifies the root entity, so JPQL property
 * completion/navigation/refactoring uses the same PSI entity graph as FlowUI,
 * fetch plans, security policies, and mappedBy references.
 */
internal fun jmixJavaRepositoryQueryReferences(
    literal: PsiLiteralExpression,
): Array<PsiReference>? {
    val annotation = PsiTreeUtil.getParentOfType(
        literal,
        PsiAnnotation::class.java,
        false,
    ) ?: return null
    val annotationName = annotation.qualifiedName
        ?: annotation.nameReferenceElement?.referenceName
        ?: return null
    if (annotationName.substringAfterLast('.') != "Query") return null
    if (annotation.qualifiedName != null &&
        annotation.qualifiedName != JMIX_REPOSITORY_QUERY_ANNOTATION
    ) {
        return null
    }
    val attribute = PsiTreeUtil.getParentOfType(
        literal,
        PsiNameValuePair::class.java,
        false,
    )?.attributeName ?: "value"
    if (attribute != "value") return null
    val repository = PsiTreeUtil.getParentOfType(
        annotation,
        PsiClass::class.java,
        false,
    ) ?: return null
    val rootEntity = javaRepositoryEntity(repository) ?: return null
    val valueRange = ElementManipulators.getValueTextRange(literal)
    if (valueRange.isEmpty) return PsiReference.EMPTY_ARRAY
    val raw = valueRange.substring(literal.text)
    val tokens = repositoryJpqlTokens(raw)
    val method = PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java, false)
    val parameters = method?.let(::javaRepositoryBindings).orEmpty()
    return buildList {
        tokens.entity?.let { entity ->
            add(
                JmixJavaRepositoryEntityNameReference(
                    literal,
                    entity.range.shiftRight(valueRange.startOffset),
                    entity.name,
                ),
            )
        }
        tokens.properties.forEach { property ->
            add(
                JmixJavaEntityPropertyReference(
                    literal,
                    property.range.shiftRight(valueRange.startOffset),
                    rootEntity,
                    property.prefix,
                    property.name,
                ),
            )
        }
        tokens.parameters.forEach { parameter ->
            add(
                JmixJavaRepositoryParameterReference(
                    literal,
                    parameter.range.shiftRight(valueRange.startOffset),
                    parameter.name,
                    parameters,
                ),
            )
        }
    }.toTypedArray()
}

internal fun jmixJavaRepositoryParamReference(
    literal: PsiLiteralExpression,
): PsiReference? {
    val annotation = PsiTreeUtil.getParentOfType(
        literal,
        PsiAnnotation::class.java,
        false,
    ) ?: return null
    val annotationName = annotation.qualifiedName
        ?: annotation.nameReferenceElement?.referenceName
        ?: return null
    if (annotationName.substringAfterLast('.') != "Param") return null
    if (annotation.qualifiedName != null &&
        annotation.qualifiedName != SPRING_DATA_PARAM_ANNOTATION
    ) {
        return null
    }
    val attribute = PsiTreeUtil.getParentOfType(
        literal,
        PsiNameValuePair::class.java,
        false,
    )?.attributeName ?: "value"
    if (attribute != "value") return null
    val bindingName = literal.value as? String ?: return null
    val parameter = PsiTreeUtil.getParentOfType(
        annotation,
        PsiParameter::class.java,
        false,
    ) ?: return null
    return JmixJavaRepositoryParameterReference(
        element = literal,
        range = ElementManipulators.getValueTextRange(literal),
        bindingName = bindingName,
        candidates = listOf(
            JavaRepositoryBinding(
                bindingName = bindingName,
                parameter = parameter,
                explicitLiteral = literal,
            ),
        ),
    )
}

internal class JmixJavaRepositoryParameterReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val bindingName: String,
    private val candidates: List<JavaRepositoryBinding>,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidates.asSequence()
            .filter { it.bindingName == bindingName }
            .map(JavaRepositoryBinding::target)
            .map(::PsiElementResolveResult)
            .toList()
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidates.asSequence()
            .map(JavaRepositoryBinding::bindingName)
            .distinct()
            .sorted()
            .map { candidate ->
                LookupElementBuilder.create(candidate)
                    .withTypeText("repository parameter", true)
            }
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(
            element,
            rangeInElement,
            newElementName,
        )
}

private fun javaRepositoryBindings(method: PsiMethod): List<JavaRepositoryBinding> =
    method.parameterList.parameters.map { parameter ->
        val explicitLiteral = parameter.annotations.asSequence()
            .filter { annotation ->
                val name = annotation.qualifiedName
                    ?: annotation.nameReferenceElement?.referenceName
                name?.substringAfterLast('.') == "Param" &&
                    (annotation.qualifiedName == null ||
                        annotation.qualifiedName == SPRING_DATA_PARAM_ANNOTATION)
            }
            .mapNotNull { annotation ->
                annotation.findAttributeValue("value") as? PsiLiteralExpression
            }
            .firstOrNull { it.value is String }
        JavaRepositoryBinding(
            bindingName = (explicitLiteral?.value as? String)
                ?.takeIf(String::isNotBlank)
                ?: parameter.name.orEmpty(),
            parameter = parameter,
            explicitLiteral = explicitLiteral,
        )
    }.filter { it.bindingName.isNotBlank() }

internal data class JavaRepositoryBinding(
    val bindingName: String,
    val parameter: PsiParameter,
    val explicitLiteral: PsiLiteralExpression?,
) {
    fun target(): PsiElement =
        explicitLiteral?.let {
            JmixJavaRepositoryParameterElement(parameter, it, bindingName)
        } ?: parameter
}

internal class JmixJavaRepositoryParameterElement(
    parameter: PsiParameter,
    explicitLiteral: PsiLiteralExpression,
    private val fallbackName: String,
) : FakePsiElement(), PsiNamedElement {
    private val projectRef = parameter.project
    private val managerRef = parameter.manager
    private val parameterPointer: SmartPsiElementPointer<PsiParameter> =
        SmartPointerManager.getInstance(projectRef).createSmartPsiElementPointer(parameter)
    private val literalPointer: SmartPsiElementPointer<PsiLiteralExpression> =
        SmartPointerManager.getInstance(projectRef).createSmartPsiElementPointer(explicitLiteral)

    override fun getParent(): PsiElement =
        parameterPointer.element?.parent ?: containingFile ?: this

    override fun getManager(): PsiManager = managerRef

    override fun getContainingFile() =
        literalPointer.element?.containingFile ?: literalPointer.containingFile

    override fun getName(): String =
        (literalPointer.element?.value as? String)?.takeIf(String::isNotBlank)
            ?: fallbackName

    override fun setName(name: String): PsiElement {
        val literal = literalPointer.element ?: return this
        val replacement = JavaPsiFacade.getElementFactory(projectRef)
            .createExpressionFromText(
                "\"${escapeRepositoryParameterString(name)}\"",
                literal,
            )
        literal.replace(replacement)
        return this
    }

    override fun getText(): String = name

    override fun getNavigationElement(): PsiElement =
        literalPointer.element ?: parameterPointer.element ?: this

    override fun getTextRange(): TextRange =
        literalPointer.element?.textRange ?: TextRange.EMPTY_RANGE

    override fun getTextOffset(): Int =
        literalPointer.element?.textOffset ?: 0

    override fun getUseScope(): SearchScope =
        parameterPointer.element?.declarationScope?.let(::LocalSearchScope)
            ?: LocalSearchScope.EMPTY

    override fun isValid(): Boolean =
        parameterPointer.element != null && literalPointer.element != null

    override fun isWritable(): Boolean =
        literalPointer.element?.isWritable == true

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another is JmixJavaRepositoryParameterElement &&
            parameterPointer.element?.let { parameter ->
                another.parameterPointer.element?.let { other ->
                    managerRef.areElementsEquivalent(parameter, other)
                }
            } == true

    override fun getPresentableText(): String = name

    override fun toString(): String = "Repository parameter ':$name'"
}

private fun escapeRepositoryParameterString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

internal fun jmixKotlinRepositoryQueryReferences(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    raw: String,
    annotation: KotlinAnnotationContext?,
): Array<PsiReference>? {
    if (annotation?.name != "Query" ||
        annotation.attributeName !in setOf(null, "value")
    ) {
        return null
    }
    val rootEntity = kotlinRepositoryEntity(host) ?: return null
    val tokens = repositoryJpqlTokens(raw)
    val parameters = kotlinRepositoryBindings(host)
    return buildList {
        tokens.entity?.let { entity ->
            add(
                JmixKotlinEntityNameReference(
                    host,
                    entity.range.shiftRight(valueRange.startOffset),
                    entity.name,
                ),
            )
        }
        tokens.properties.forEach { property ->
            add(
                JmixKotlinEntityPropertyReference(
                    host,
                    property.range.shiftRight(valueRange.startOffset),
                    rootEntity,
                    property.prefix,
                    property.name,
                ),
            )
        }
        tokens.parameters.forEach { parameter ->
            add(
                JmixKotlinRepositoryParameterReference(
                    host,
                    parameter.range.shiftRight(valueRange.startOffset),
                    parameter.name,
                    parameters,
                ),
            )
        }
    }.toTypedArray()
}

internal fun jmixKotlinRepositoryParamReference(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    raw: String,
    annotation: KotlinAnnotationContext?,
): PsiReference? {
    if (annotation?.name != "Param" ||
        annotation.attributeName !in setOf(null, "value") ||
        raw.isBlank()
    ) {
        return null
    }
    val parameter = generateSequence(host.parent) { it.parent }
        .filterIsInstance<PsiNamedElement>()
        .firstOrNull { it.javaClass.simpleName == "KtParameter" }
        ?: return null
    return JmixKotlinRepositoryParameterReference(
        element = host,
        range = valueRange,
        bindingName = raw,
        candidates = listOf(
            KotlinRepositoryBinding(
                bindingName = raw,
                parameter = parameter,
                explicitHost = host,
                explicitRange = valueRange,
            ),
        ),
    )
}

internal class JmixKotlinRepositoryParameterReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val bindingName: String,
    private val candidates: List<KotlinRepositoryBinding>,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidates.asSequence()
            .filter { it.bindingName == bindingName }
            .map(KotlinRepositoryBinding::target)
            .map(::PsiElementResolveResult)
            .toList()
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidates.asSequence()
            .map(KotlinRepositoryBinding::bindingName)
            .distinct()
            .sorted()
            .map { candidate ->
                LookupElementBuilder.create(candidate)
                    .withTypeText("repository parameter", true)
            }
            .toList()
            .toTypedArray()

    override fun isReferenceTo(element: PsiElement): Boolean {
        // The @Param literal is the declaration surface of the synthetic symbol.
        // setName() updates it directly; excluding that same host from usage search
        // prevents a stale pre-rename range from applying the rename a second time.
        if (candidates.singleOrNull()?.explicitHost === this.element) return false
        return super.isReferenceTo(element)
    }

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(
            element,
            rangeInElement,
            newElementName,
        )
}

private fun kotlinRepositoryBindings(
    queryHost: PsiLanguageInjectionHost,
): List<KotlinRepositoryBinding> {
    val function = generateSequence(queryHost.parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtNamedFunction" }
        ?: return emptyList()
    return PsiTreeUtil.findChildrenOfType(function, PsiNamedElement::class.java)
        .asSequence()
        .filter { it.javaClass.simpleName == "KtParameter" }
        .filter { parameter ->
            generateSequence(parameter.parent) { it.parent }
                .firstOrNull { it.javaClass.simpleName == "KtNamedFunction" } === function
        }
        .mapNotNull { parameter ->
            val explicitHost = PsiTreeUtil.findChildrenOfType(
                parameter,
                PsiLanguageInjectionHost::class.java,
            ).firstOrNull { candidate ->
                candidate.javaClass.simpleName == "KtStringTemplateExpression" &&
                    candidate.kotlinAnnotationContext()?.let { annotation ->
                        annotation.name == "Param" &&
                            annotation.attributeName in setOf(null, "value")
                    } == true
            }
            val explicitRange = explicitHost?.kotlinStringContentRange()
            val explicitName = if (explicitHost != null && explicitRange != null) {
                explicitRange.substring(explicitHost.text).takeIf(String::isNotBlank)
            } else {
                null
            }
            val bindingName = explicitName ?: parameter.name
            bindingName?.takeIf(String::isNotBlank)?.let {
                KotlinRepositoryBinding(
                    bindingName = it,
                    parameter = parameter,
                    explicitHost = explicitHost,
                    explicitRange = explicitRange,
                )
            }
        }
        .toList()
}

internal data class KotlinRepositoryBinding(
    val bindingName: String,
    val parameter: PsiNamedElement,
    val explicitHost: PsiLanguageInjectionHost?,
    val explicitRange: TextRange?,
) {
    fun target(): PsiElement =
        if (explicitHost != null && explicitRange != null) {
            JmixKotlinRepositoryParameterElement(
                parameter,
                explicitHost,
                explicitRange,
                bindingName,
            )
        } else {
            parameter
        }
}

internal class JmixKotlinRepositoryParameterElement(
    parameter: PsiNamedElement,
    explicitHost: PsiLanguageInjectionHost,
    private val explicitRange: TextRange,
    private val fallbackName: String,
) : FakePsiElement(), PsiNamedElement {
    private val projectRef = parameter.project
    private val managerRef = parameter.manager
    private val parameterPointer: SmartPsiElementPointer<PsiNamedElement> =
        SmartPointerManager.getInstance(projectRef).createSmartPsiElementPointer(parameter)
    private val hostPointer: SmartPsiElementPointer<PsiLanguageInjectionHost> =
        SmartPointerManager.getInstance(projectRef).createSmartPsiElementPointer(explicitHost)

    override fun getParent(): PsiElement =
        parameterPointer.element?.parent ?: containingFile ?: this

    override fun getManager(): PsiManager = managerRef

    override fun getContainingFile() =
        hostPointer.element?.containingFile ?: hostPointer.containingFile

    override fun getName(): String {
        val host = hostPointer.element
        return if (host != null && explicitRange.endOffset <= host.textLength) {
            explicitRange.substring(host.text).takeIf(String::isNotBlank)
                ?: fallbackName
        } else {
            fallbackName
        }
    }

    override fun setName(name: String): PsiElement {
        hostPointer.element?.let { host ->
            ElementManipulators.handleContentChange(host, explicitRange, name)
        }
        return this
    }

    override fun getText(): String = name

    override fun getNavigationElement(): PsiElement =
        hostPointer.element ?: parameterPointer.element ?: this

    override fun getTextRange(): TextRange =
        hostPointer.element?.textRange ?: TextRange.EMPTY_RANGE

    override fun getTextOffset(): Int =
        hostPointer.element?.textOffset ?: 0

    override fun getUseScope(): SearchScope {
        val function = generateSequence(parameterPointer.element?.parent) { it.parent }
            .firstOrNull { it.javaClass.simpleName == "KtNamedFunction" }
        return function?.let(::LocalSearchScope) ?: LocalSearchScope.EMPTY
    }

    override fun isValid(): Boolean =
        parameterPointer.element != null && hostPointer.element != null

    override fun isWritable(): Boolean =
        hostPointer.element?.isWritable == true

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another is JmixKotlinRepositoryParameterElement &&
            parameterPointer.element?.let { parameter ->
                another.parameterPointer.element?.let { other ->
                    managerRef.areElementsEquivalent(parameter, other)
                }
            } == true

    override fun getPresentableText(): String = name

    override fun toString(): String = "Repository parameter ':$name'"
}

internal class JmixJavaRepositoryEntityNameReference(
    element: PsiLiteralExpression,
    range: TextRange,
    entityName: String,
) : JmixJavaEntityNameReference(element, range, entityName) {
    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(
            element,
            rangeInElement,
            renamedRepositoryEntityIdentifier(element, entityName, newElementName),
        )
}

private fun javaRepositoryEntity(repository: PsiClass): PsiClass? =
    javaRepositoryEntity(repository, PsiSubstitutor.EMPTY, linkedSetOf())

private fun javaRepositoryEntity(
    owner: PsiClass,
    ownerSubstitutor: PsiSubstitutor,
    visiting: LinkedHashSet<PsiClass>,
): PsiClass? {
    if (!visiting.add(owner)) return null
    owner.extendsListTypes.forEach { declaredType ->
        val type = (ownerSubstitutor.substitute(declaredType) as? PsiClassType)
            ?: declaredType
        val resolved = type.resolveGenerics()
        val superClass = resolved.element ?: return@forEach
        if (superClass.qualifiedName == JMIX_REPOSITORY_BASE ||
            superClass.name == "JmixDataRepository"
        ) {
            val entityType = superClass.typeParameters.firstOrNull()
                ?.let(resolved.substitutor::substitute)
                ?: type.parameters.firstOrNull()
            return (entityType as? PsiClassType)?.resolve()
        }
        javaRepositoryEntity(
            superClass,
            resolved.substitutor,
            LinkedHashSet(visiting),
        )?.let { return it }
    }
    return null
}

private fun kotlinRepositoryEntity(host: PsiLanguageInjectionHost): PsiClass? {
    val declaration = generateSequence(host.parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName in setOf("KtClass", "KtObject")
        } ?: return null
    val entityType = KOTLIN_JMIX_REPOSITORY.find(declaration.text)
        ?.groupValues
        ?.get(1)
        ?: return null
    return resolveJmixEntityClass(host, entityType)
}

private fun repositoryJpqlTokens(raw: String): RepositoryJpqlTokens {
    val searchable = maskJpqlStringLiterals(maskSourceEscapes(raw))
    val parameters = JPQL_PARAMETER.findAll(searchable).map { match ->
        val group = requireNotNull(match.groups[1])
        RepositoryJpqlParameterToken(
            name = group.value,
            range = TextRange(group.range.first, group.range.last + 1),
        )
    }.toList()
    val root = JPQL_FROM.find(searchable)
        ?: return RepositoryJpqlTokens(parameters = parameters)
    val entityGroup = root.groups[1] ?: return RepositoryJpqlTokens()
    val rootAlias = root.groupValues[2]
    val aliases = linkedMapOf(rootAlias to emptyList<String>())
    val properties = mutableListOf<RepositoryJpqlPropertyToken>()
    val occupied = mutableSetOf<TextRange>()

    JPQL_JOIN.findAll(searchable).forEach { match ->
        val prefix = aliases[match.groupValues[1]] ?: return@forEach
        val pathGroup = match.groups[2] ?: return@forEach
        val segments = pathGroup.value.split('.')
        addPropertyTokens(pathGroup.range.first, segments, prefix, properties, occupied)
        val alias = match.groupValues[3]
        if (alias.isNotBlank() && alias.lowercase(Locale.ROOT) !in JPQL_KEYWORDS) {
            aliases[alias] = prefix + segments
        }
    }

    JPQL_PATH.findAll(searchable).forEach { match ->
        val prefix = aliases[match.groupValues[1]] ?: return@forEach
        val pathGroup = match.groups[2] ?: return@forEach
        addPropertyTokens(
            pathGroup.range.first,
            pathGroup.value.split('.'),
            prefix,
            properties,
            occupied,
        )
    }

    return RepositoryJpqlTokens(
        entity = RepositoryJpqlEntityToken(
            entityGroup.value,
            TextRange(entityGroup.range.first, entityGroup.range.last + 1),
        ),
        properties = properties,
        parameters = parameters,
    )
}

private fun addPropertyTokens(
    pathStart: Int,
    segments: List<String>,
    aliasPrefix: List<String>,
    destination: MutableList<RepositoryJpqlPropertyToken>,
    occupied: MutableSet<TextRange>,
) {
    val prefix = aliasPrefix.toMutableList()
    var offset = pathStart
    segments.forEach { segment ->
        val range = TextRange(offset, offset + segment.length)
        if (segment.isNotBlank() && occupied.add(range)) {
            destination += RepositoryJpqlPropertyToken(
                name = segment,
                prefix = prefix.toList(),
                range = range,
            )
        }
        if (segment.isNotBlank()) prefix += segment
        offset += segment.length + 1
    }
}

private fun maskSourceEscapes(value: String): String {
    val result = StringBuilder(value)
    var index = 0
    while (index < value.length) {
        if (value[index] != '\\') {
            index++
            continue
        }
        var end = index + 2
        if (value.getOrNull(index + 1) == 'u') {
            end = (index + 6).coerceAtMost(value.length)
        }
        for (candidate in index until end.coerceAtMost(value.length)) {
            result.setCharAt(candidate, ' ')
        }
        index = end
    }
    return result.toString()
}

private fun maskJpqlStringLiterals(value: String): String {
    val result = StringBuilder(value)
    var quote: Char? = null
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (quote == null && character in setOf('\'', '"')) {
            quote = character
            result.setCharAt(index, ' ')
        } else if (quote != null) {
            result.setCharAt(index, ' ')
            if (character == quote && value.getOrNull(index + 1) == quote) {
                result.setCharAt(index + 1, ' ')
                index += 2
                continue
            }
            if (character == quote) quote = null
        }
        index++
    }
    return result.toString()
}

private fun renamedRepositoryEntityIdentifier(
    context: PsiElement,
    identifier: String,
    newClassName: String,
): String {
    val entityClass = resolveJmixEntityClass(context, identifier)
        ?: return when {
            '.' in identifier ->
                "${identifier.substringBeforeLast('.')}.$newClassName"

            '_' !in identifier && identifier.firstOrNull()?.isUpperCase() == true ->
                newClassName

            else -> identifier
        }
    return when {
        identifier == entityClass.name -> newClassName
        identifier == entityClass.qualifiedName && '.' in identifier ->
            "${identifier.substringBeforeLast('.')}.$newClassName"

        else -> identifier
    }
}

private data class RepositoryJpqlTokens(
    val entity: RepositoryJpqlEntityToken? = null,
    val properties: List<RepositoryJpqlPropertyToken> = emptyList(),
    val parameters: List<RepositoryJpqlParameterToken> = emptyList(),
)

private data class RepositoryJpqlEntityToken(
    val name: String,
    val range: TextRange,
)

private data class RepositoryJpqlPropertyToken(
    val name: String,
    val prefix: List<String>,
    val range: TextRange,
)

private data class RepositoryJpqlParameterToken(
    val name: String,
    val range: TextRange,
)

private val KOTLIN_JMIX_REPOSITORY = Regex(
    """(?:io\.jmix\.core\.repository\.)?JmixDataRepository\s*<\s*""" +
        """([A-Za-z_$][\w$.]*)\s*,""",
)
private val JPQL_FROM = Regex(
    """(?i)\bfrom\s+([A-Za-z_$][\w$.]*)\s+(?:as\s+)?([A-Za-z_$][\w$]*)""",
)
private val JPQL_JOIN = Regex(
    """(?i)\b(?:(?:left|right)(?:\s+outer)?|inner|outer)?\s*join(?:\s+fetch)?\s+""" +
        """([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""" +
        """(?:\s+(?:as\s+)?([A-Za-z_$][\w$]*))?""",
)
private val JPQL_PATH = Regex(
    """\b([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""",
)
private val JPQL_PARAMETER = Regex("""(?<!:):([A-Za-z_$][\w$]*)(?!:)""")
private val JPQL_KEYWORDS = setOf(
    "where",
    "join",
    "left",
    "right",
    "inner",
    "outer",
    "on",
    "with",
    "order",
    "group",
    "having",
)
private const val JMIX_REPOSITORY_QUERY_ANNOTATION = "io.jmix.core.repository.Query"
private const val JMIX_REPOSITORY_BASE = "io.jmix.core.repository.JmixDataRepository"
private const val SPRING_DATA_PARAM_ANNOTATION = "org.springframework.data.repository.query.Param"

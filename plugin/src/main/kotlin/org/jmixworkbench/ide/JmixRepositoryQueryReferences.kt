package org.jmixworkbench.ide

import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiSubstitutor
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
    }.toTypedArray()
}

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
    }.toTypedArray()
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
    val root = JPQL_FROM.find(searchable) ?: return RepositoryJpqlTokens()
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

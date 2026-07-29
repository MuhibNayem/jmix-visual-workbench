package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil

internal fun jmixKotlinUiSecurityReferences(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    value: String,
    annotation: KotlinAnnotationContext?,
): Array<PsiReference>? {
    if (annotation != null) {
        when {
            annotation.name in JMIX_KOTLIN_VIEW_CONTROLLER_NAMES &&
                annotation.attributeName in setOf(null, "value", "id") ->
                return arrayOf(
                    JmixKotlinViewIdDeclarationReference(
                        host,
                        valueRange,
                        value,
                    ),
                )

            annotation.name in setOf("ViewPolicy", "ScreenPolicy") &&
                annotation.attributeName in setOf("viewIds", "screenIds") ->
                return value.takeUnless { it == "*" }?.let {
                    arrayOf(JmixKotlinViewIdReference(host, valueRange, value))
                } ?: PsiReference.EMPTY_ARRAY

            annotation.name == "MenuPolicy" &&
                annotation.attributeName == "menuIds" ->
                return value.takeUnless { it == "*" }?.let {
                    arrayOf(JmixKotlinMenuIdReference(host, valueRange, value))
                } ?: PsiReference.EMPTY_ARRAY

            annotation.name == "SpecificPolicy" &&
                annotation.attributeName in setOf(null, "value", "resources") ->
                return value.takeUnless { it == "*" }?.let {
                    arrayOf(
                        JmixKotlinSpecificPolicyDeclarationReference(
                            host,
                            valueRange,
                            value,
                        ),
                    )
                } ?: PsiReference.EMPTY_ARRAY

            annotation.name in JMIX_KOTLIN_ENTITY_POLICY_ANNOTATIONS &&
                annotation.attributeName == "entityName" ->
                return value.takeUnless { it == "*" }?.let {
                    arrayOf(JmixKotlinEntityNameReference(host, valueRange, value))
                } ?: PsiReference.EMPTY_ARRAY

            annotation.name == "EntityAttributePolicy" &&
                annotation.attributeName == "attributes" -> {
                if (value == "*") return PsiReference.EMPTY_ARRAY
                val entityClass = host.kotlinSecurityEntityClass() ?: return PsiReference.EMPTY_ARRAY
                return kotlinEntityPropertyPathReferences(
                    host,
                    valueRange,
                    value,
                    entityClass,
                )
            }

            annotation.name == "JpqlRowLevelPolicy" &&
                annotation.attributeName in setOf("where", "join") -> {
                val entityClass = host.kotlinSecurityEntityClass() ?: return PsiReference.EMPTY_ARRAY
                return kotlinJpqlEntityPathReferences(
                    host,
                    valueRange,
                    value,
                    entityClass,
                )
            }
        }
    }
    return jmixKotlinSpecificPolicyUsage(host, valueRange, value)
        ?: jmixKotlinMessageReference(host, valueRange, value)
}

internal class JmixKotlinViewIdDeclarationReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val viewId: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val declarations = jmixViewIdDeclarations(element)
        val exact = declarations.filter { declaration ->
            declaration.id == viewId &&
                declaration.valueElement.manager.areElementsEquivalent(
                    declaration.valueElement,
                    element,
                )
        }.ifEmpty {
            declarations.filter { declaration ->
                declaration.id == viewId &&
                    declaration.valueElement.containingFile?.virtualFile ==
                    element.containingFile.virtualFile
            }
        }
        return exact
            .map(::JmixViewIdElement)
            .map(::PsiElementResolveResult)
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)
}

internal class JmixKotlinViewIdReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val viewId: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
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

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)

    internal fun candidateDeclarations(): List<JmixViewIdDeclaration> =
        jmixViewIdDeclarations(element)
}

internal class JmixKotlinMenuIdReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val menuId: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateDeclarations()
            .filter { it.id == menuId }
            .map {
                PsiElementResolveResult(
                    JmixXmlAttributeNamedElement(
                        it.declaration,
                        "Jmix menu item",
                    ),
                )
            }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateDeclarations()
            .asSequence()
            .take(JMIX_UI_COMPLETION_LIMIT)
            .map { declaration ->
                LookupElementBuilder.create(
                    JmixXmlAttributeNamedElement(
                        declaration.declaration,
                        "Jmix menu item",
                    ),
                    declaration.id,
                ).withTypeText(declaration.declaration.containingFile.name, true)
            }
            .distinctBy { it.lookupString }
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)

    internal fun candidateDeclarations(): List<JmixMenuIdDeclaration> =
        jmixMenuIdDeclarations(element)
}

internal class JmixKotlinEntityNameReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val entityName: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        resolveJmixEntityClasses(element, entityName)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        jmixEntityClasses(element)
            .asSequence()
            .take(JMIX_UI_COMPLETION_LIMIT)
            .mapNotNull { entityClass ->
                val insertion = entityClass.preferredMetadataName()
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                LookupElementBuilder.create(entityClass, insertion)
                    .withTypeText(entityClass.qualifiedName.orEmpty(), true)
            }
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(
            element,
            rangeInElement,
            renamedKotlinEntityIdentifier(element, entityName, newElementName),
        )

    internal fun candidateNames(): Sequence<String> =
        jmixEntityClasses(element).asSequence()
            .flatMap { it.jmixEntityAliases().asSequence() }
            .distinct()
}

internal class JmixKotlinEntityPropertyReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val rootEntity: PsiClass,
    private val pathPrefix: List<String>,
    private val propertyName: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateProperties()
            .filter { it.name == propertyName }
            .map { PsiElementResolveResult(it.element) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateProperties()
            .map { property ->
                LookupElementBuilder.create(property.element, property.name)
                    .withTypeText(property.type.presentableText, true)
                    .withIcon(property.element.getIcon(0))
            }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)

    internal fun candidateProperties(): List<JmixEntityProperty> =
        entityClassAtPath(rootEntity, pathPrefix)
            ?.let(::jmixEntityProperties)
            .orEmpty()
}

internal class JmixKotlinMessageReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    internal val logicalKey: String,
    private val style: JmixMessageReferenceStyle,
    private val group: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateDeclarations()
            .filter { logicalKey in it.lookupKeys }
            .map { PsiElementResolveResult(it.property) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateDeclarations()
            .asSequence()
            .mapNotNull { declaration ->
                kotlinMessageInsertion(declaration, style, group)?.let { insertion ->
                    LookupElementBuilder.create(declaration.property, insertion)
                        .withTypeText(declaration.property.containingFile.name, true)
                }
            }
            .distinctBy { it.lookupString }
            .take(JMIX_UI_COMPLETION_LIMIT)
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(
            element,
            rangeInElement,
            renamedMessageReference(
                style,
                group,
                logicalKey,
                newElementName,
            ),
        )

    internal fun candidateDeclarations(): List<JmixMessageDeclaration> =
        jmixMessageDeclarations(element)
}

internal class JmixKotlinSpecificPolicyDeclarationReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val resource: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val declarations = jmixSpecificPolicyDeclarations(element)
        val exact = declarations.filter { declaration ->
            declaration.resource == resource &&
                declaration.valueElement.manager.areElementsEquivalent(
                    declaration.valueElement,
                    element,
                )
        }.ifEmpty {
            declarations.filter { declaration ->
                declaration.resource == resource &&
                    declaration.valueElement.containingFile?.virtualFile ==
                    element.containingFile.virtualFile
            }
        }
        return exact
            .map(::JmixSpecificPolicyElement)
            .map(::PsiElementResolveResult)
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)
}

internal class JmixKotlinSpecificPolicyReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val resource: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateDeclarations()
            .filter { it.resource == resource }
            .map(::JmixSpecificPolicyElement)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateDeclarations()
            .asSequence()
            .take(JMIX_UI_COMPLETION_LIMIT)
            .map { declaration ->
                LookupElementBuilder.create(
                    JmixSpecificPolicyElement(declaration),
                    declaration.resource,
                ).withTypeText(
                    declaration.valueElement.containingFile.name,
                    true,
                )
            }
            .distinctBy { it.lookupString }
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)

    internal fun candidateDeclarations(): List<JmixSpecificPolicyDeclaration> =
        jmixSpecificPolicyDeclarations(element)
}

private fun kotlinEntityPropertyPathReferences(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    raw: String,
    rootEntity: PsiClass,
): Array<PsiReference> {
    val references = mutableListOf<PsiReference>()
    val prefix = mutableListOf<String>()
    var start = 0
    raw.split('.').forEach { segment ->
        val end = start + segment.length
        if (segment.isNotBlank()) {
            references += JmixKotlinEntityPropertyReference(
                host,
                TextRange(
                    valueRange.startOffset + start,
                    valueRange.startOffset + end,
                ),
                rootEntity,
                prefix.toList(),
                segment,
            )
            prefix += segment
        }
        start = end + 1
    }
    return references.toTypedArray()
}

private fun kotlinJpqlEntityPathReferences(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    raw: String,
    rootEntity: PsiClass,
): Array<PsiReference> =
    JMIX_KOTLIN_ROW_POLICY_ENTITY_PATH.findAll(raw)
        .flatMap { match ->
            val path = match.groupValues[1]
            val pathStart = match.groups[1]?.range?.first ?: return@flatMap emptySequence()
            val prefix = mutableListOf<String>()
            var segmentStart = 0
            path.split('.').asSequence().mapNotNull { segment ->
                val absoluteStart = valueRange.startOffset + pathStart + segmentStart
                val reference = segment.takeIf(String::isNotBlank)?.let {
                    JmixKotlinEntityPropertyReference(
                        host,
                        TextRange(
                            absoluteStart,
                            absoluteStart + segment.length,
                        ),
                        rootEntity,
                        prefix.toList(),
                        segment,
                    )
                }
                if (segment.isNotBlank()) prefix += segment
                segmentStart += segment.length + 1
                reference
            }
        }
        .toList()
        .toTypedArray()

private fun PsiLanguageInjectionHost.kotlinSecurityEntityClass(): PsiClass? {
    val annotation = generateSequence(parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtAnnotationEntry" }
        ?: return null
    val className = JMIX_KOTLIN_ENTITY_CLASS_ARGUMENT.find(annotation.text)
        ?.groupValues
        ?.get(1)
    if (!className.isNullOrBlank()) {
        val matches = jmixEntityClasses(this).filter { entityClass ->
            entityClass.name == className ||
                entityClass.qualifiedName == className ||
                entityClass.qualifiedName?.endsWith(".$className") == true
        }
        matches.singleOrNull()?.let { return it }
        if ('.' in className) {
            val resolved = JavaPsiFacade.getInstance(project).findClass(
                className,
                com.intellij.psi.search.GlobalSearchScope.allScope(project),
            )
            if (resolved != null) return resolved
        }
    }
    val entityName = JMIX_KOTLIN_ENTITY_NAME_ARGUMENT.find(annotation.text)
        ?.groupValues
        ?.get(1)
        ?: return null
    return resolveJmixEntityClass(this, entityName)
}

private fun jmixKotlinMessageReference(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    value: String,
): Array<PsiReference>? {
    val call = generateSequence(host.parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtCallExpression" }
        ?: return null
    val callText = call.text
    val callee = callText.substringBefore('(').trim()
    val methodName = callee.substringAfterLast('.')
    if (methodName !in setOf("getMessage", "formatMessage") ||
        "message" !in callee.lowercase()
    ) {
        return null
    }
    val arguments = PsiTreeUtil.findChildrenOfType(
        call,
        PsiLanguageInjectionHost::class.java,
    ).asSequence()
        .filter { it.javaClass.simpleName == "KtStringTemplateExpression" }
        .filter { candidate ->
            generateSequence(candidate.parent) { it.parent }
                .takeWhile { it != call }
                .none { it.javaClass.simpleName == "KtCallExpression" }
        }
        .sortedBy { it.textOffset }
        .toList()
    val index = arguments.indexOf(host)
    if (index < 0) return null
    val isReferenceArgument = when {
        arguments.size == 1 -> index == 0
        arguments.size >= 2 -> index == 1
        else -> false
    }
    if (!isReferenceArgument) return PsiReference.EMPTY_ARRAY
    val group = when {
        arguments.size >= 2 && index == 1 ->
            arguments.first().kotlinStringContentRange()
                ?.substring(arguments.first().text)
                .orEmpty()

        '/' !in value -> host.kotlinFilePackage()
        else -> ""
    }
    val style = if ('/' in value) {
        JmixMessageReferenceStyle.FULL
    } else {
        JmixMessageReferenceStyle.LOCAL
    }
    val logical = if (style == JmixMessageReferenceStyle.LOCAL && group.isNotBlank()) {
        "$group/$value"
    } else {
        value
    }
    return arrayOf(
        JmixKotlinMessageReference(
            host,
            valueRange,
            logical,
            style,
            group,
        ),
    )
}

private fun jmixKotlinSpecificPolicyUsage(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    value: String,
): Array<PsiReference>? {
    val call = generateSequence(host.parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtCallExpression" }
        ?: return null
    val callee = call.text.substringBefore('(').trim().substringAfterLast('.')
    if (callee != "SpecificOperationAccessContext") return null
    val firstString = PsiTreeUtil.findChildrenOfType(
        call,
        PsiLanguageInjectionHost::class.java,
    ).asSequence()
        .filter { it.javaClass.simpleName == "KtStringTemplateExpression" }
        .minByOrNull { it.textOffset }
    if (firstString != host) return PsiReference.EMPTY_ARRAY
    return arrayOf(
        JmixKotlinSpecificPolicyReference(
            host,
            valueRange,
            value,
        ),
    )
}

private fun PsiElement.kotlinFilePackage(): String =
    JMIX_KOTLIN_PACKAGE.find(containingFile?.text.orEmpty())
        ?.groupValues
        ?.get(1)
        .orEmpty()

private fun renamedKotlinEntityIdentifier(
    context: PsiElement,
    identifier: String,
    newClassName: String,
): String {
    val entityClass = resolveJmixEntityClass(context, identifier) ?: return identifier
    return when {
        identifier == entityClass.name -> newClassName
        identifier == entityClass.qualifiedName && '.' in identifier ->
            "${identifier.substringBeforeLast('.')}.$newClassName"

        else -> identifier
    }
}

private fun kotlinMessageInsertion(
    declaration: JmixMessageDeclaration,
    style: JmixMessageReferenceStyle,
    group: String,
): String? =
    when (style) {
        JmixMessageReferenceStyle.GLOBAL -> declaration.key.takeIf { '/' !in it }
        JmixMessageReferenceStyle.FULL ->
            declaration.lookupKeys.firstOrNull { '/' in it } ?: declaration.key

        JmixMessageReferenceStyle.LOCAL -> {
            declaration.lookupKeys.firstOrNull { candidate ->
                candidate.substringBeforeLast('/', "") == group
            }?.substringAfterLast('/')
                ?: declaration.lookupKeys.firstOrNull { '/' in it }
                ?: declaration.key
        }
    }

private val JMIX_KOTLIN_VIEW_CONTROLLER_NAMES = setOf("ViewController", "UiController")
private val JMIX_KOTLIN_ENTITY_POLICY_ANNOTATIONS = setOf(
    "EntityPolicy",
    "EntityAttributePolicy",
    "JpqlRowLevelPolicy",
    "PredicateRowLevelPolicy",
)
private val JMIX_KOTLIN_ENTITY_CLASS_ARGUMENT =
    Regex("""\bentityClass\s*=\s*([\w.]+)::class\b""")
private val JMIX_KOTLIN_ENTITY_NAME_ARGUMENT =
    Regex("""\bentityName\s*=\s*"([^"$]+)"""")
private val JMIX_KOTLIN_ROW_POLICY_ENTITY_PATH =
    Regex("""\{E}\.([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""")
private val JMIX_KOTLIN_PACKAGE =
    Regex("""(?m)^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""")

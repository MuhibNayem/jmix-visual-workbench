package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiType
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil

internal fun jmixJavaUiSecurityReferences(
    literal: PsiLiteralExpression,
    value: String,
): Array<PsiReference>? {
    val annotation = literal.jmixContainingAnnotation()
    if (annotation != null) {
        val annotationName = annotation.jmixShortName()
        val attributeName = literal.jmixAnnotationAttributeName()
        when {
            annotationName in JMIX_VIEW_CONTROLLER_NAMES &&
                attributeName in setOf("value", "id") ->
                return arrayOf(
                    JmixJavaViewIdDeclarationReference(
                        literal,
                        quotedJavaRange(value),
                        value,
                    ),
                )

            annotationName in setOf("ViewPolicy", "ScreenPolicy") &&
                attributeName in setOf("viewIds", "screenIds") ->
                return value.takeUnless { it == "*" }?.let {
                    arrayOf(
                        JmixJavaViewIdReference(
                            literal,
                            quotedJavaRange(value),
                            value,
                        ),
                    )
                } ?: PsiReference.EMPTY_ARRAY

            annotationName == "UiComponentPolicy" && attributeName == "viewId" ->
                return arrayOf(
                    JmixJavaViewIdReference(
                        literal,
                        quotedJavaRange(value),
                        value,
                    ),
                )

            annotationName == "UiComponentPolicy" && attributeName == "componentIds" ->
                return javaUiComponentPolicyReferences(literal, value, annotation)

            annotationName == "MenuPolicy" && attributeName == "menuIds" ->
                return value.takeUnless { it == "*" }?.let {
                    arrayOf(
                        JmixJavaMenuIdReference(
                            literal,
                            quotedJavaRange(value),
                            value,
                        ),
                    )
                } ?: PsiReference.EMPTY_ARRAY

            annotationName == "SpecificPolicy" &&
                attributeName in setOf("value", "resources") ->
                return value.takeUnless { it == "*" }?.let {
                    arrayOf(
                        JmixJavaSpecificPolicyDeclarationReference(
                            literal,
                            quotedJavaRange(value),
                            value,
                        ),
                    )
                } ?: PsiReference.EMPTY_ARRAY

            annotationName in JMIX_ENTITY_POLICY_ANNOTATIONS &&
                attributeName == "entityName" ->
                return value.takeUnless { it == "*" }?.let {
                    arrayOf(
                        JmixJavaEntityNameReference(
                            literal,
                            quotedJavaRange(value),
                            value,
                        ),
                    )
                } ?: PsiReference.EMPTY_ARRAY

            annotationName == "EntityAttributePolicy" &&
                attributeName == "attributes" -> {
                if (value == "*") return PsiReference.EMPTY_ARRAY
                val entityClass = annotation.jmixSecurityEntityClass() ?: return PsiReference.EMPTY_ARRAY
                return javaEntityPropertyPathReferences(literal, value, entityClass)
            }

            annotationName == "JpqlRowLevelPolicy" &&
                attributeName in setOf("where", "join") -> {
                val entityClass = annotation.jmixSecurityEntityClass() ?: return PsiReference.EMPTY_ARRAY
                return javaJpqlEntityPathReferences(literal, value, entityClass)
            }
        }
    }
    return jmixJavaSpecificPolicyUsage(literal, value)
        ?: jmixJavaMessageReference(literal, value)
}

internal class JmixJavaViewIdDeclarationReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val viewId: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
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
        replaceJavaStringLiteral(element, newElementName)
}

internal class JmixJavaViewIdReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val viewId: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
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
        replaceJavaStringLiteral(element, newElementName)

    internal fun candidateDeclarations(): List<JmixViewIdDeclaration> =
        jmixViewIdDeclarations(element)
}

internal class JmixJavaMenuIdReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val menuId: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
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
                ).withTypeText(
                    declaration.declaration.containingFile.name,
                    true,
                )
            }
            .distinctBy { it.lookupString }
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        replaceJavaStringLiteral(element, newElementName)

    internal fun candidateDeclarations(): List<JmixMenuIdDeclaration> =
        jmixMenuIdDeclarations(element)
}

internal open class JmixJavaEntityNameReference(
    element: PsiLiteralExpression,
    range: TextRange,
    protected val entityName: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
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

    open override fun handleElementRename(newElementName: String): PsiElement =
        replaceJavaStringLiteral(
            element,
            renamedEntityIdentifier(element, entityName, newElementName),
        )

    internal fun candidateNames(): Sequence<String> =
        jmixEntityClasses(element).asSequence()
            .flatMap { it.jmixEntityAliases().asSequence() }
            .distinct()
}

internal class JmixJavaEntityPropertyReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val rootEntity: PsiClass,
    private val pathPrefix: List<String>,
    private val propertyName: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
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

internal class JmixJavaSpecificPolicyDeclarationReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val resource: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
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
        replaceJavaStringLiteral(element, newElementName)
}

internal class JmixJavaSpecificPolicyReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val resource: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
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
        replaceJavaStringLiteral(element, newElementName)

    internal fun candidateDeclarations(): List<JmixSpecificPolicyDeclaration> =
        jmixSpecificPolicyDeclarations(element)
}

internal class JmixJavaMessageReference(
    element: PsiLiteralExpression,
    range: TextRange,
    internal val logicalKey: String,
    private val style: JmixMessageReferenceStyle,
    private val group: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateDeclarations()
            .filter { logicalKey in it.lookupKeys }
            .map { PsiElementResolveResult(it.property) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateDeclarations()
            .asSequence()
            .mapNotNull { declaration ->
                javaMessageInsertion(declaration, style, group)?.let { insertion ->
                    LookupElementBuilder.create(declaration.property, insertion)
                        .withTypeText(declaration.property.containingFile.name, true)
                }
            }
            .distinctBy { it.lookupString }
            .take(JMIX_UI_COMPLETION_LIMIT)
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        replaceJavaStringLiteral(
            element,
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

private fun javaEntityPropertyPathReferences(
    literal: PsiLiteralExpression,
    raw: String,
    rootEntity: PsiClass,
): Array<PsiReference> {
    val references = mutableListOf<PsiReference>()
    val prefix = mutableListOf<String>()
    var start = 0
    raw.split('.').forEach { segment ->
        val end = start + segment.length
        if (segment.isNotBlank()) {
            references += JmixJavaEntityPropertyReference(
                literal,
                TextRange(start + 1, end + 1),
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

private fun javaJpqlEntityPathReferences(
    literal: PsiLiteralExpression,
    raw: String,
    rootEntity: PsiClass,
): Array<PsiReference> =
    JMIX_ROW_POLICY_ENTITY_PATH.findAll(raw)
        .flatMap { match ->
            val path = match.groupValues[1]
            val pathStart = match.groups[1]?.range?.first ?: return@flatMap emptySequence()
            val prefix = mutableListOf<String>()
            var segmentStart = 0
            path.split('.').asSequence().mapNotNull { segment ->
                val absoluteStart = pathStart + segmentStart
                val reference = segment.takeIf(String::isNotBlank)?.let {
                    JmixJavaEntityPropertyReference(
                        literal,
                        TextRange(
                            absoluteStart + 1,
                            absoluteStart + segment.length + 1,
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

private fun jmixJavaMessageReference(
    literal: PsiLiteralExpression,
    value: String,
): Array<PsiReference>? {
    val call = PsiTreeUtil.getParentOfType(
        literal,
        PsiMethodCallExpression::class.java,
        false,
    ) ?: return null
    val methodName = call.methodExpression.referenceName ?: return null
    if (methodName !in setOf("getMessage", "formatMessage")) return null
    val resolvedOwner = call.resolveMethod()?.containingClass?.qualifiedName
    val receiver = call.methodExpression.qualifierExpression?.text.orEmpty()
    if (resolvedOwner !in JMIX_MESSAGE_API_CLASSES &&
        "message" !in receiver.lowercase()
    ) {
        return null
    }
    val arguments = call.argumentList.expressions
    val index = arguments.indexOf(literal)
    if (index < 0) return null

    val group = when {
        arguments.size >= 2 && index == 1 ->
            javaMessageGroup(arguments[0], literal)

        index == 0 && '/' !in value ->
            literal.jmixContainingClassPackage()

        else -> ""
    }
    val isReferenceArgument = when {
        arguments.size == 1 -> index == 0
        arguments.size >= 2 -> index == 1
        else -> false
    }
    if (!isReferenceArgument) return PsiReference.EMPTY_ARRAY
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
        JmixJavaMessageReference(
            literal,
            quotedJavaRange(value),
            logical,
            style,
            group,
        ),
    )
}

private fun jmixJavaSpecificPolicyUsage(
    literal: PsiLiteralExpression,
    value: String,
): Array<PsiReference>? {
    val newExpression = PsiTreeUtil.getParentOfType(
        literal,
        PsiNewExpression::class.java,
        false,
    ) ?: return null
    val className = newExpression.classReference?.qualifiedName
        ?: newExpression.classReference?.referenceName
    if (className?.substringAfterLast('.') != "SpecificOperationAccessContext") {
        return null
    }
    if (newExpression.argumentList?.expressions?.firstOrNull() != literal) {
        return PsiReference.EMPTY_ARRAY
    }
    return arrayOf(
        JmixJavaSpecificPolicyReference(
            literal,
            quotedJavaRange(value),
            value,
        ),
    )
}

private fun javaMessageGroup(expression: PsiElement, context: PsiElement): String =
    when (expression) {
        is PsiLiteralExpression -> expression.value as? String ?: ""
        is PsiClassObjectAccessExpression ->
            (expression.operand.type as? PsiClassType)
                ?.resolve()
                ?.qualifiedName
                ?.substringBeforeLast('.', "")
                .orEmpty()

        else -> {
            if (expression.text.endsWith("getClass()")) {
                context.jmixContainingClassPackage()
            } else {
                ""
            }
        }
    }

private fun PsiAnnotation.jmixSecurityEntityClass(): PsiClass? {
    val classValue = findDeclaredAttributeValue("entityClass")
    val classType = (classValue as? PsiClassObjectAccessExpression)?.operand?.type
    (classType as? PsiClassType)?.resolve()?.let { return it }
    val entityNameValue = findDeclaredAttributeValue("entityName")
    val entityName = (entityNameValue as? PsiLiteralExpression)?.value as? String
        ?: return null
    return resolveJmixEntityClass(this, entityName)
}

private fun PsiLiteralExpression.jmixContainingAnnotation(): PsiAnnotation? =
    PsiTreeUtil.getParentOfType(this, PsiAnnotation::class.java, false)

private fun PsiAnnotation.jmixShortName(): String? =
    qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.referenceName

private fun PsiLiteralExpression.jmixAnnotationAttributeName(): String {
    val pair = PsiTreeUtil.getParentOfType(this, PsiNameValuePair::class.java, false)
    return pair?.attributeName ?: "value"
}

private fun PsiElement.jmixContainingClassPackage(): String =
    PsiTreeUtil.getParentOfType(this, PsiClass::class.java, false)
        ?.qualifiedName
        ?.substringBeforeLast('.', "")
        .orEmpty()

internal fun replaceJavaStringLiteral(
    literal: PsiLiteralExpression,
    replacement: String,
): PsiElement {
    val escaped = replacement.replace("\\", "\\\\").replace("\"", "\\\"")
    val expression = JavaPsiFacade.getElementFactory(literal.project)
        .createExpressionFromText("\"$escaped\"", literal)
    return literal.replace(expression)
}

private fun renamedEntityIdentifier(
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

private fun javaMessageInsertion(
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

private fun quotedJavaRange(value: String): TextRange = TextRange(1, value.length + 1)

private val JMIX_VIEW_CONTROLLER_NAMES = setOf("ViewController", "UiController")
private val JMIX_ENTITY_POLICY_ANNOTATIONS = setOf(
    "EntityPolicy",
    "EntityAttributePolicy",
    "JpqlRowLevelPolicy",
    "PredicateRowLevelPolicy",
)
private val JMIX_MESSAGE_API_CLASSES = setOf(
    "io.jmix.core.Messages",
    "io.jmix.flowui.view.MessageBundle",
)
private val JMIX_ROW_POLICY_ENTITY_PATH =
    Regex("""\{E}\.([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""")

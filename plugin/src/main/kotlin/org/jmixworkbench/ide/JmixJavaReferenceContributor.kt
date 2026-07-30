package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

/**
 * Connects @ViewDescriptor Java string values to their actual FlowUI XML file.
 */
class JmixJavaReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            JmixViewDescriptorReferenceProvider,
        )
    }
}

internal object JmixViewDescriptorReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        val value = literal.value as? String ?: return PsiReference.EMPTY_ARRAY
        jmixJavaMappedByReference(literal, value)?.let { return arrayOf(it) }
        jmixJavaRepositoryParamReference(literal)?.let { return arrayOf(it) }
        jmixJavaRepositoryQueryReferences(literal)?.let { return it }
        if (literal.isJmixSpringBeanNameDeclaration()) {
            return arrayOf(
                JmixJavaSpringBeanDeclarationReference(
                    literal,
                    quotedValueRange(value),
                    value,
                ),
            )
        }
        jmixJavaUiSecurityReferences(literal, value)?.let { return it }
        if (literal.isSubscribeSubjectValue()) {
            return arrayOf(
                JmixJavaSubscribeSubjectReference(
                    literal,
                    quotedValueRange(value),
                    value,
                ),
            )
        }
        if (value.isBlank()) return PsiReference.EMPTY_ARRAY
        if (literal.isViewDescriptorValue()) {
            return arrayOf(
                JmixViewDescriptorReference(
                    literal,
                    quotedValueRange(value),
                    value,
                ),
            )
        }
        return literal.controllerReferenceKind()?.let { kind ->
            controllerReferences(
                literal,
                value,
                kind,
                literal.controllerTargetTags(),
            )
        } ?: PsiReference.EMPTY_ARRAY
    }

    private fun controllerReferences(
        literal: PsiLiteralExpression,
        value: String,
        kind: ControllerReferenceKind,
        targetTags: Set<String>?,
    ): Array<PsiReference> {
        val separator = value.indexOf('.')
        if (separator <= 0 ||
            separator == value.lastIndex ||
            !kind.actionPathAllowed ||
            targetTags != null
        ) {
            return arrayOf(
                JmixJavaFlowUiIdReference(
                    literal,
                    quotedValueRange(value),
                    value,
                    acceptedTags = targetTags,
                ),
            )
        }
        val ownerId = value.substring(0, separator)
        val actionId = value.substring(separator + 1)
        return arrayOf(
            JmixJavaFlowUiIdReference(
                literal,
                TextRange(1, separator + 1),
                ownerId,
            ),
            JmixJavaFlowUiIdReference(
                literal,
                TextRange(separator + 2, value.length + 1),
                actionId,
                acceptedTags = setOf("action"),
                ownerId = ownerId,
            ),
        )
    }
}

internal class JmixJavaSubscribeSubjectReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val subject: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidates()
            .filter { candidate ->
                candidate.logicalName == subject ||
                    candidate.method.name == subject
            }
            .map(JmixSubscribeSubject::method)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidates()
            .map { candidate ->
                LookupElementBuilder.create(
                    candidate.method,
                    candidate.logicalName,
                ).withTypeText(
                    candidate.method.containingClass?.qualifiedName.orEmpty(),
                    true,
                )
            }
            .distinctBy { it.lookupString }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val logicalName = newElementName
            .removePrefix("add")
            .removePrefix("set")
            .replaceFirstChar(Char::lowercaseChar)
        val replacement = JavaPsiFacade.getElementFactory(element.project)
            .createExpressionFromText(
                "\"${escapeJavaString(logicalName)}\"",
                element,
            )
        return element.replace(replacement)
    }

    private fun candidates(): List<JmixSubscribeSubject> {
        val annotation = element.containingAnnotation() ?: return emptyList()
        val method = PsiTreeUtil.getParentOfType(
            annotation,
            PsiMethod::class.java,
            false,
        ) ?: return emptyList()
        if (method.parameterList.parametersCount != 1) return emptyList()
        val controller = method.containingClass ?: return emptyList()
        val targetClasses = JmixFlowUiMetadata.subscribeTargetClasses(
            controller,
            annotation,
        )
        if (targetClasses.isEmpty()) return emptyList()
        return JmixFlowUiMetadata.subscribeSubjects(
            targetClasses,
            method.parameterList.parameters.single().type,
        )
    }
}

internal class JmixViewDescriptorReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val descriptorPath: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        findJmixDescriptorFiles(element, descriptorPath)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        findAllJmixDescriptorFiles(element)
            .take(MAX_COMPLETION_FILES)
            .map { file ->
                LookupElementBuilder.create(file.name)
                    .withTypeText(file.virtualFile.parent?.path.orEmpty(), true)
                    .withIcon(file.getIcon(0))
            }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val parentPath = descriptorPath.substringBeforeLast('/', "")
        val replacementPath = if (parentPath.isBlank()) newElementName else "$parentPath/$newElementName"
        val factory: PsiElementFactory = JavaPsiFacade.getElementFactory(element.project)
        val replacement = factory.createExpressionFromText("\"${escapeJava(replacementPath)}\"", element)
        return element.replace(replacement)
    }

    private fun escapeJava(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val MAX_COMPLETION_FILES = 2_000
    }
}

internal class JmixJavaFlowUiIdReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val id: String,
    private val acceptedTags: Set<String>? = null,
    private val ownerId: String? = null,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
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

    override fun handleElementRename(newElementName: String): PsiElement {
        val value = element.value as? String ?: return element
        val start = rangeInElement.startOffset - 1
        val end = rangeInElement.endOffset - 1
        if (start < 0 || start > end || end > value.length) return element
        val replacementValue = value.replaceRange(start, end, newElementName)
        val replacement = JavaPsiFacade.getElementFactory(element.project)
            .createExpressionFromText("\"${escapeJavaString(replacementValue)}\"", element)
        return element.replace(replacement)
    }

    internal fun candidateAttributes(): List<XmlAttribute> =
        element.associatedDescriptorFiles().flatMap { file ->
            val allTags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            val owner = ownerId?.let { expectedOwner ->
                allTags.firstOrNull { tag ->
                    JmixFlowUiMetadata.injectionIdentifierAttributes(tag)
                        .any { attribute -> attribute.value == expectedOwner }
                }
            }
            val scope = owner?.let { PsiTreeUtil.findChildrenOfType(it, XmlTag::class.java) } ?: allTags
            scope.asSequence()
                .filter { acceptedTags == null || it.localName in acceptedTags }
                .flatMap { tag ->
                    JmixFlowUiMetadata.injectionIdentifierAttributes(tag)
                        .asSequence()
                }
                .toList()
        }
}

private fun PsiLiteralExpression.isViewDescriptorValue(): Boolean {
    val annotation = containingAnnotation() ?: return false
    val shortName = annotation.qualifiedName?.substringAfterLast('.')
        ?: annotation.nameReferenceElement?.referenceName
    return shortName == "ViewDescriptor"
}

private fun PsiLiteralExpression.isSubscribeSubjectValue(): Boolean {
    val annotation = containingAnnotation() ?: return false
    val shortName = annotation.qualifiedName?.substringAfterLast('.')
        ?: annotation.nameReferenceElement?.referenceName
    if (shortName != "Subscribe") return false
    return PsiTreeUtil.getParentOfType(
        this,
        PsiNameValuePair::class.java,
        false,
    )?.attributeName == "subject"
}

private fun PsiLiteralExpression.controllerReferenceKind(): ControllerReferenceKind? {
    val annotation = containingAnnotation() ?: return null
    val shortName = annotation.qualifiedName?.substringAfterLast('.')
        ?: annotation.nameReferenceElement?.referenceName
        ?: return null
    val attributeName = PsiTreeUtil.getParentOfType(this, PsiNameValuePair::class.java, false)
        ?.attributeName
        ?: "value"
    return when (shortName) {
        "ViewComponent" -> ControllerReferenceKind.COMPONENT
            .takeIf { attributeName == "value" }

        "Subscribe" -> ControllerReferenceKind.COMPONENT_OR_ACTION
            .takeIf { attributeName == "value" || attributeName == "id" }

        "Install", "Supply" -> ControllerReferenceKind.COMPONENT_OR_ACTION
            .takeIf { attributeName == "to" }

        else -> null
    }
}

private fun PsiLiteralExpression.containingAnnotation(): PsiAnnotation? =
    PsiTreeUtil.getParentOfType(this, PsiAnnotation::class.java, false)

private fun PsiLiteralExpression.controllerTargetTags(): Set<String>? {
    val target = containingAnnotation()
        ?.findDeclaredAttributeValue("target")
        ?.text
        ?.substringAfterLast('.')
        ?.trim()
    return jmixControllerTargetTags(target)
}

internal fun PsiLiteralExpression.associatedDescriptorFiles(): List<XmlFile> {
    val controller = PsiTreeUtil.getParentOfType(this, PsiClass::class.java, false)
        ?: return emptyList()
    return jmixDescriptorFilesForController(this, controller)
}

internal fun jmixDescriptorFilesForController(
    context: PsiElement,
    controller: PsiClass,
): List<XmlFile> {
    val descriptorLiteral = controller.annotations.asSequence()
        .filter { annotation ->
            val shortName = annotation.qualifiedName?.substringAfterLast('.')
                ?: annotation.nameReferenceElement?.referenceName
            shortName == "ViewDescriptor" || shortName == "FragmentDescriptor"
        }
        .mapNotNull { annotation ->
            PsiTreeUtil.findChildOfType(annotation.parameterList, PsiLiteralExpression::class.java)
        }
        .firstOrNull()
    val path = descriptorLiteral?.value as? String
        ?: JMIX_DESCRIPTOR_PATH.find(
            controller.navigationElement.text,
        )?.groupValues?.get(1)
        ?: return emptyList()
    return findJmixDescriptorFiles(context, path)
}

internal fun findJmixDescriptorFiles(context: PsiElement, path: String): List<XmlFile> {
    val normalized = path.replace('\\', '/').trimStart('/')
    val fileName = normalized.substringAfterLast('/')
    return FilenameIndex.getVirtualFilesByName(
        fileName,
        GlobalSearchScope.projectScope(context.project),
    ).asSequence()
        .filter { virtualFile ->
            virtualFile.path.replace('\\', '/').endsWith(normalized) ||
                (normalized == fileName && virtualFile.name == fileName)
        }
        .mapNotNull { PsiManager.getInstance(context.project).findFile(it) }
        .filterIsInstance<XmlFile>()
        .filter(XmlFile::isJmixFlowUiDescriptor)
        .toList()
}

internal fun findAllJmixDescriptorFiles(context: PsiElement): List<XmlFile> =
    indexedJmixCandidateFiles(
        context.project,
        JmixFlowUiDescriptorCandidateFileIndex.NAME,
        GlobalSearchScope.projectScope(context.project),
    ).asSequence()
        .mapNotNull { PsiManager.getInstance(context.project).findFile(it) }
        .filterIsInstance<XmlFile>()
        .filter(XmlFile::isJmixFlowUiDescriptor)
        .sortedBy { it.virtualFile.path }
        .toList()

private fun quotedValueRange(value: String): TextRange = TextRange(1, value.length + 1)

private fun escapeJavaString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private val JMIX_DESCRIPTOR_PATH = Regex(
    """@(?:[\w.]+\.)?(?:ViewDescriptor|FragmentDescriptor)\s*\(\s*(?:(?:value|path)\s*=\s*)?"([^"$]+)"""",
)

private enum class ControllerReferenceKind(
    val actionPathAllowed: Boolean,
) {
    COMPONENT(false),
    COMPONENT_OR_ACTION(true),
}

internal fun jmixControllerTargetTags(target: String?): Set<String>? =
    when (target) {
        "DATA_LOADER" -> setOf("loader")
        "DATA_CONTAINER" -> setOf(
            "instance",
            "collection",
            "keyValueCollection",
            "dataContext",
        )
        else -> null
    }

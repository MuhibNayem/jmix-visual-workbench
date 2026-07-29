package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Native IntelliJ symbol model for the premium Jmix UI Constraints contract.
 *
 * A policy path is semantic, not merely dotted XML text:
 *  - `save` is an action owned by the view;
 *  - `usersDataGrid.edit` is an action owned by a component;
 *  - `addressFragment.cityField` crosses into a fragment descriptor;
 *  - fragments can themselves contain fragments and component actions.
 *
 * Keeping a reference per path segment makes Find Usages and XML declaration
 * rename safe for both the owner/fragment segment and the terminal symbol.
 */
internal data class JmixUiComponentPolicyPath(
    val segments: List<XmlAttribute>,
) {
    val names: List<String>
        get() = segments.map { it.value.orEmpty() }

    val displayPath: String
        get() = names.joinToString(".")
}

internal data class JmixUiComponentPolicySegmentCandidate(
    val attribute: XmlAttribute,
    val path: String,
)

internal class JmixJavaUiComponentPolicyReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val rawPath: String,
    private val segmentIndex: Int,
    private val descriptors: () -> List<XmlFile>,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateSegments()
            .filter { it.attribute.value == currentSegment() }
            .map { PsiElementResolveResult(JmixFlowUiIdElement(it.attribute)) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateSegments()
            .mapNotNull { candidate ->
                val name = candidate.attribute.value?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                LookupElementBuilder.create(JmixFlowUiIdElement(candidate.attribute), name)
                    .withTypeText(candidate.path, true)
            }
            .distinctBy { it.lookupString }
            .take(JMIX_UI_COMPLETION_LIMIT)
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val value = element.value as? String ?: return element
        val contentStart = rangeInElement.startOffset - 1
        val contentEnd = rangeInElement.endOffset - 1
        if (contentStart < 0 || contentStart > contentEnd || contentEnd > value.length) {
            return element
        }
        return replaceJavaStringLiteral(
            element,
            value.replaceRange(contentStart, contentEnd, newElementName),
        )
    }

    internal fun candidateSegments(): List<JmixUiComponentPolicySegmentCandidate> =
        jmixUiComponentPolicySegmentCandidates(
            element,
            descriptors(),
            rawPath,
            segmentIndex,
        )

    private fun currentSegment(): String =
        rawPath.split('.').getOrElse(segmentIndex) { "" }
}

internal class JmixKotlinUiComponentPolicyReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val rawPath: String,
    private val segmentIndex: Int,
    private val descriptors: () -> List<XmlFile>,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateSegments()
            .filter { it.attribute.value == currentSegment() }
            .map { PsiElementResolveResult(JmixFlowUiIdElement(it.attribute)) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateSegments()
            .mapNotNull { candidate ->
                val name = candidate.attribute.value?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                LookupElementBuilder.create(JmixFlowUiIdElement(candidate.attribute), name)
                    .withTypeText(candidate.path, true)
            }
            .distinctBy { it.lookupString }
            .take(JMIX_UI_COMPLETION_LIMIT)
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)

    internal fun candidateSegments(): List<JmixUiComponentPolicySegmentCandidate> =
        jmixUiComponentPolicySegmentCandidates(
            element,
            descriptors(),
            rawPath,
            segmentIndex,
        )

    private fun currentSegment(): String =
        rawPath.split('.').getOrElse(segmentIndex) { "" }
}

internal fun javaUiComponentPolicyReferences(
    literal: PsiLiteralExpression,
    value: String,
    annotation: PsiAnnotation,
): Array<PsiReference> {
    val descriptors = lazy(LazyThreadSafetyMode.NONE) {
        javaUiComponentPolicyDescriptors(literal, annotation)
    }
    return dottedJavaPolicyReferences(value) { range, segmentIndex ->
        JmixJavaUiComponentPolicyReference(
            literal,
            range,
            value,
            segmentIndex,
            descriptors::value,
        )
    }.map { it as PsiReference }.toTypedArray()
}

internal fun kotlinUiComponentPolicyReferences(
    host: PsiLanguageInjectionHost,
    valueRange: TextRange,
    value: String,
): Array<PsiReference> {
    val descriptors = lazy(LazyThreadSafetyMode.NONE) {
        kotlinUiComponentPolicyDescriptors(host)
    }
    return dottedKotlinPolicyReferences(valueRange, value) { range, segmentIndex ->
        JmixKotlinUiComponentPolicyReference(
            host,
            range,
            value,
            segmentIndex,
            descriptors::value,
        )
    }.map { it as PsiReference }.toTypedArray()
}

private fun dottedJavaPolicyReferences(
    value: String,
    factory: (TextRange, Int) -> JmixJavaUiComponentPolicyReference,
): Array<JmixJavaUiComponentPolicyReference> {
    var start = 0
    return value.split('.')
        .mapIndexed { index, segment ->
            val end = start + segment.length
            factory(TextRange(start + 1, end + 1), index).also {
                start = end + 1
            }
        }
        .toTypedArray()
}

private fun dottedKotlinPolicyReferences(
    valueRange: TextRange,
    value: String,
    factory: (TextRange, Int) -> JmixKotlinUiComponentPolicyReference,
): Array<JmixKotlinUiComponentPolicyReference> {
    var start = valueRange.startOffset
    return value.split('.')
        .mapIndexed { index, segment ->
            val end = start + segment.length
            factory(TextRange(start, end), index).also {
                start = end + 1
            }
        }
        .toTypedArray()
}

private fun javaUiComponentPolicyDescriptors(
    context: PsiElement,
    annotation: PsiAnnotation,
): List<XmlFile> {
    val controllers = linkedSetOf<PsiClass>()
    val viewClass = annotation.findDeclaredAttributeValue("viewClass")
    ((viewClass as? PsiClassObjectAccessExpression)
        ?: PsiTreeUtil.findChildOfType(viewClass, PsiClassObjectAccessExpression::class.java))
        ?.operand
        ?.type
        ?.let { it as? PsiClassType }
        ?.resolve()
        ?.let(controllers::add)

    val viewId = annotation.findDeclaredAttributeValue("viewId")
        ?.let { JavaPsiFacade.getInstance(context.project).constantEvaluationHelper.computeConstantExpression(it) }
        as? String
    if (!viewId.isNullOrBlank()) {
        resolveJmixViewIds(context, viewId)
            .mapNotNullTo(controllers) { it.controller ?: it.valueElement.enclosingJavaClass() }
    }

    return buildList {
        controllers.flatMapTo(this) { jmixDescriptorFilesForController(context, it) }
        if (!viewId.isNullOrBlank()) {
            resolveJmixViewIds(context, viewId)
                .flatMapTo(this) { descriptorFilesForViewDeclaration(context, it) }
        }
    }.distinctBy { it.virtualFile.path }
}

private fun kotlinUiComponentPolicyDescriptors(
    host: PsiLanguageInjectionHost,
): List<XmlFile> {
    val annotation = host.kotlinContainingAnnotationElement() ?: return emptyList()
    val annotationText = annotation.text
    val viewIds = KOTLIN_UI_POLICY_VIEW_ID.findAll(annotationText)
        .map { it.groupValues[1] }
        .filter(String::isNotBlank)
        .toSet()
    val viewClassName = KOTLIN_UI_POLICY_VIEW_CLASS.find(annotationText)
        ?.groupValues
        ?.get(1)

    val declarations = jmixViewIdDeclarations(host)
    val matchingDeclarations = declarations.filter { declaration ->
        declaration.id in viewIds ||
            viewClassName?.let { declaration.matchesKotlinControllerName(it) } == true
    }
    val controllers = linkedSetOf<PsiClass>()
    viewClassName?.let { resolvePolicyControllerClasses(host, it) }
        ?.let(controllers::addAll)
    matchingDeclarations
        .mapNotNullTo(controllers) { it.controller ?: it.valueElement.enclosingJavaClass() }

    return buildList {
        controllers.flatMapTo(this) { jmixDescriptorFilesForController(host, it) }
        matchingDeclarations.flatMapTo(this) {
            descriptorFilesForViewDeclaration(host, it)
        }
    }.distinctBy { it.virtualFile.path }
}

private fun descriptorFilesForViewDeclaration(
    context: PsiElement,
    declaration: JmixViewIdDeclaration,
): List<XmlFile> {
    declaration.controller?.let {
        return jmixDescriptorFilesForController(context, it)
    }
    val owner = declaration.valueElement.enclosingKotlinControllerElement()
        ?: return emptyList()
    val path = KOTLIN_CONTROLLER_DESCRIPTOR.find(owner.text)?.groupValues?.get(1)
        ?: return emptyList()
    return findJmixDescriptorFiles(context, path)
}

private fun JmixViewIdDeclaration.matchesKotlinControllerName(
    requestedName: String,
): Boolean {
    val owner = valueElement.enclosingKotlinControllerElement() ?: return false
    val simpleName = requestedName.substringAfterLast('.')
    val declaredName = KOTLIN_CLASS_NAME.find(owner.text)?.groupValues?.get(1)
        ?: return false
    if (declaredName != simpleName) return false
    if ('.' !in requestedName) return true
    val packageName = KOTLIN_PACKAGE.find(valueElement.containingFile.text)
        ?.groupValues
        ?.get(1)
        .orEmpty()
    return "$packageName.$declaredName" == requestedName
}

private fun resolvePolicyControllerClasses(
    context: PsiElement,
    requestedName: String,
): List<PsiClass> {
    val project = context.project
    val scope = GlobalSearchScope.projectScope(project)
    if ('.' in requestedName) {
        return listOfNotNull(JavaPsiFacade.getInstance(project).findClass(requestedName, scope))
    }
    val importedName = KOTLIN_IMPORT.findAll(context.containingFile.text)
        .map { it.groupValues[1] }
        .firstOrNull { it.substringAfterLast('.') == requestedName }
    val exact = importedName?.let {
        JavaPsiFacade.getInstance(project).findClass(it, scope)
    }
    if (exact != null) return listOf(exact)
    return PsiShortNamesCache.getInstance(project)
        .getClassesByName(requestedName, scope)
        .toList()
}

private fun PsiElement.enclosingJavaClass(): PsiClass? =
    PsiTreeUtil.getParentOfType(this, PsiClass::class.java, false)

private fun PsiElement.enclosingKotlinControllerElement(): PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }

private fun PsiLanguageInjectionHost.kotlinContainingAnnotationElement(): PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtAnnotationEntry" }

private fun jmixUiComponentPolicySegmentCandidates(
    context: PsiElement,
    descriptors: List<XmlFile>,
    rawPath: String,
    segmentIndex: Int,
): List<JmixUiComponentPolicySegmentCandidate> {
    val rawSegments = rawPath.split('.')
    val expectedPrefix = rawSegments.take(segmentIndex)
    return descriptors
        .flatMap { descriptor ->
            collectUiComponentPolicyPaths(
                context = context,
                descriptor = descriptor,
                descriptorChain = linkedSetOf(),
                depth = 0,
            )
        }
        .asSequence()
        .filter { path ->
            path.segments.size > segmentIndex &&
                path.names.take(segmentIndex) == expectedPrefix
        }
        .map { path ->
            JmixUiComponentPolicySegmentCandidate(
                attribute = path.segments[segmentIndex],
                path = path.displayPath,
            )
        }
        .distinctBy {
            "${it.attribute.containingFile.virtualFile.path}:${it.attribute.textOffset}"
        }
        .sortedWith(
            compareBy<JmixUiComponentPolicySegmentCandidate> { it.attribute.value.orEmpty() }
                .thenBy { it.path },
        )
        .toList()
}

private fun collectUiComponentPolicyPaths(
    context: PsiElement,
    descriptor: XmlFile,
    descriptorChain: LinkedHashSet<String>,
    depth: Int,
): List<JmixUiComponentPolicyPath> {
    ProgressManager.checkCanceled()
    if (depth > JMIX_UI_POLICY_MAX_FRAGMENT_DEPTH) return emptyList()
    val descriptorPath = descriptor.virtualFile.path
    if (!descriptorChain.add(descriptorPath)) return emptyList()

    val result = mutableListOf<JmixUiComponentPolicyPath>()
    try {
        val root = descriptor.rootTag ?: return emptyList()
        val tags = PsiTreeUtil.findChildrenOfType(root, XmlTag::class.java)
            .asSequence()
            .filter { it.isUiComponentPolicySurface(root) }
            .toList()

        tags.asSequence()
            .filter { it.localName != "action" }
            .mapNotNull { it.getAttribute("id") }
            .filter { !it.value.isNullOrBlank() }
            .forEach { result += JmixUiComponentPolicyPath(listOf(it)) }

        tags.asSequence()
            .filter { it.localName == "action" }
            .mapNotNull { action ->
                val actionId = action.getAttribute("id")
                    ?.takeIf { !it.value.isNullOrBlank() }
                    ?: return@mapNotNull null
                val ownerId = action.uiActionOwner(root)?.getAttribute("id")
                JmixUiComponentPolicyPath(
                    listOfNotNull(ownerId, actionId),
                )
            }
            .forEach(result::add)

        tags.asSequence()
            .filter { it.localName == "fragment" }
            .forEach { fragmentTag ->
                ProgressManager.checkCanceled()
                val fragmentId = fragmentTag.getAttribute("id")
                    ?.takeIf { !it.value.isNullOrBlank() }
                    ?: return@forEach
                val controllerName = fragmentTag.getAttributeValue("class")
                    ?.takeIf(String::isNotBlank)
                    ?: return@forEach
                resolvePolicyControllerClasses(context, controllerName)
                    .asSequence()
                    .flatMap {
                        jmixDescriptorFilesForController(context, it).asSequence()
                    }
                    .distinctBy { it.virtualFile.path }
                    .flatMap { fragmentDescriptor ->
                        collectUiComponentPolicyPaths(
                            context,
                            fragmentDescriptor,
                            descriptorChain,
                            depth + 1,
                        ).asSequence()
                    }
                    .forEach { nestedPath ->
                        result += JmixUiComponentPolicyPath(
                            listOf(fragmentId) + nestedPath.segments,
                        )
                    }
            }
    } finally {
        descriptorChain.remove(descriptorPath)
    }
    return result.distinctBy { path ->
        path.segments.joinToString("|") {
            "${it.containingFile.virtualFile.path}:${it.textOffset}"
        }
    }
}

private fun XmlTag.isUiComponentPolicySurface(root: XmlTag): Boolean {
    if (this == root || getAttribute("id") == null) return false
    return generateSequence(parentTag) { it.parentTag }
        .takeWhile { it != root }
        .none { it.localName in JMIX_NON_VISUAL_DESCRIPTOR_SECTIONS }
}

private fun XmlTag.uiActionOwner(root: XmlTag): XmlTag? =
    generateSequence(parentTag) { it.parentTag }
        .takeWhile { it != root }
        .firstOrNull { ancestor ->
            ancestor.localName != "actions" &&
                ancestor.localName !in JMIX_NON_VISUAL_DESCRIPTOR_SECTIONS &&
                !ancestor.getAttributeValue("id").isNullOrBlank()
        }

private val KOTLIN_UI_POLICY_VIEW_ID =
    Regex("""\bviewId\s*=\s*"([^"$]*)"""")
private val KOTLIN_UI_POLICY_VIEW_CLASS =
    Regex("""\bviewClass\s*=\s*([A-Za-z_$][\w$.]*)::class""")
private val KOTLIN_CONTROLLER_DESCRIPTOR = Regex(
    """@(?:[\w.]+\.)?(?:ViewDescriptor|FragmentDescriptor)\s*\(\s*(?:(?:value|path)\s*=\s*)?"([^"$]+)"""",
)
private val KOTLIN_CLASS_NAME =
    Regex("""\b(?:class|object)\s+([A-Za-z_$][\w$]*)""")
private val KOTLIN_PACKAGE =
    Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$.]*)""")
private val KOTLIN_IMPORT =
    Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$.]*)""")
private val JMIX_NON_VISUAL_DESCRIPTOR_SECTIONS = setOf(
    "data",
    "facets",
    "dialogMode",
)
private const val JMIX_UI_POLICY_MAX_FRAGMENT_DEPTH = 32

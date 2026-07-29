package org.jmixworkbench.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import java.util.Locale

/**
 * Project-wide Spring bean inventory used by Jmix menu navigation.
 *
 * Discovery is backed by [JmixSpringBeanCandidateFileIndex], so normal Java,
 * Kotlin, XML and properties edits do not rescan the project or invalidate this
 * inventory. PSI is used only to validate the small indexed candidate set.
 */
@Service(Service.Level.PROJECT)
internal class JmixSpringBeanSymbolService(
    private val project: Project,
) {
    @Volatile
    private var cache: JmixSpringBeanCache? = null

    fun beans(): List<JmixSpringBeanDeclaration> {
        if (DumbService.isDumb(project)) return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        ensureJmixCandidateIndexUpToDate(
            project,
            JmixSpringBeanCandidateFileIndex.NAME,
            scope,
        )
        val stamp = jmixCandidateIndexStamp(
            project,
            JmixSpringBeanCandidateFileIndex.NAME,
        )
        cache?.takeIf { it.stamp == stamp }?.let { return it.beans }

        ProgressManager.checkCanceled()
        val candidates = indexedJmixCandidateFiles(
            project,
            JmixSpringBeanCandidateFileIndex.NAME,
            scope,
        )
        val beans = computeBeans(candidates)
        cache = JmixSpringBeanCache(
            jmixCandidateIndexStamp(
                project,
                JmixSpringBeanCandidateFileIndex.NAME,
            ),
            beans,
        )
        return beans
    }

    private fun computeBeans(
        candidates: List<VirtualFile>,
    ): List<JmixSpringBeanDeclaration> {
        val manager = PsiManager.getInstance(project)
        return candidates.asSequence()
            .onEach { ProgressManager.checkCanceled() }
            .mapNotNull(manager::findFile)
            .flatMap { file ->
                when (file.virtualFile.extension?.lowercase(Locale.ROOT)) {
                    "java" -> javaBeans(file).asSequence()
                    "kt" -> kotlinBeans(file).asSequence()
                    else -> emptySequence()
                }
            }
            .filter { it.name.isNotBlank() }
            .distinctBy { declaration ->
                val file = declaration.navigationElement.containingFile
                    ?.virtualFile
                    ?.path
                    .orEmpty()
                "$file:${declaration.navigationElement.textOffset}:${declaration.name}"
            }
            .sortedWith(
                compareBy<JmixSpringBeanDeclaration> { it.name }
                    .thenBy {
                        it.navigationElement.containingFile?.virtualFile?.path.orEmpty()
                    },
            )
            .toList()
    }

    private fun javaBeans(file: PsiElement): List<JmixSpringBeanDeclaration> =
        buildList {
            PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)
                .asSequence()
                .onEach { ProgressManager.checkCanceled() }
                .mapNotNull(PsiClass::jmixSpringBeanDeclaration)
                .forEach(::add)
            PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)
                .asSequence()
                .onEach { ProgressManager.checkCanceled() }
                .flatMap(PsiMethod::jmixBeanFactoryDeclarations)
                .forEach(::add)
        }

    private fun kotlinBeans(file: PsiElement): List<JmixSpringBeanDeclaration> =
        PsiTreeUtil.findChildrenOfType(file, PsiNamedElement::class.java)
            .asSequence()
            .onEach { ProgressManager.checkCanceled() }
            .filter {
                it.javaClass.simpleName == "KtClass" ||
                    it.javaClass.simpleName == "KtObjectDeclaration"
            }
            .mapNotNull(PsiNamedElement::jmixKotlinSpringBeanDeclaration)
            .toList()

    companion object {
        fun getInstance(project: Project): JmixSpringBeanSymbolService =
            project.getService(JmixSpringBeanSymbolService::class.java)
    }
}

private data class JmixSpringBeanCache(
    val stamp: JmixCandidateIndexStamp,
    val beans: List<JmixSpringBeanDeclaration>,
)

internal data class JmixSpringBeanDeclaration(
    val name: String,
    val navigationElement: PsiElement,
    val classElement: PsiNamedElement,
    val explicitNameElement: PsiElement?,
    val methods: List<JmixSpringBeanMethodDeclaration>,
    val implicitNameKind: JmixSpringBeanImplicitNameKind,
)

internal enum class JmixSpringBeanImplicitNameKind {
    CLASS,
    FACTORY_METHOD,
}

internal data class JmixSpringBeanMethodDeclaration(
    val name: String,
    val element: PsiNamedElement,
    val signature: String,
    val invalidReason: String?,
) {
    val isMenuCallable: Boolean
        get() = invalidReason == null
}

internal class JmixSpringBeanElement(
    internal val declaration: JmixSpringBeanDeclaration,
    private val renameBackingDeclaration: Boolean = true,
) : FakePsiElement() {
    private val projectRef = declaration.navigationElement.project
    private val managerRef = declaration.navigationElement.manager
    private val navigationPointer: SmartPsiElementPointer<PsiElement> =
        SmartPointerManager.getInstance(projectRef)
            .createSmartPsiElementPointer(declaration.navigationElement)
    private val classPointer: SmartPsiElementPointer<PsiElement> =
        SmartPointerManager.getInstance(projectRef)
            .createSmartPsiElementPointer(declaration.classElement)
    private val explicitPointer: SmartPsiElementPointer<PsiElement>? =
        declaration.explicitNameElement?.let { explicit ->
            SmartPointerManager.getInstance(projectRef)
                .createSmartPsiElementPointer(explicit)
        }

    override fun getParent(): PsiElement =
        navigationPointer.element?.parent
            ?: containingFile
            ?: this

    override fun getManager(): PsiManager = managerRef

    override fun getContainingFile() =
        navigationPointer.element?.containingFile
            ?: navigationPointer.containingFile

    override fun getName(): String {
        val explicit = explicitPointer?.element
        return when (explicit) {
            is PsiLiteralExpression ->
                (explicit.value as? String).orEmpty().ifBlank { declaration.name }

            is PsiLanguageInjectionHost ->
                explicit.kotlinStringContentRange()
                    ?.substring(explicit.text)
                    ?.takeIf(String::isNotBlank)
                    ?: declaration.name

            else -> (classPointer.element as? PsiNamedElement)
                ?.name
                ?.let { backingName ->
                    declaration.implicitNameKind.beanNameAfterBackingRename(
                        backingName,
                    )
                }
                ?: declaration.name
        }
    }

    override fun setName(name: String): PsiElement {
        val explicit = explicitPointer?.element
        if (explicit == null && !renameBackingDeclaration) return this
        when (explicit) {
            is PsiLiteralExpression -> {
                val replacement = JavaPsiFacade.getElementFactory(project)
                    .createExpressionFromText(
                        "\"${escapeSpringBeanString(name)}\"",
                        explicit,
                    )
                explicit.replace(replacement)
            }

            is PsiLanguageInjectionHost -> {
                val range = explicit.kotlinStringContentRange()
                    ?: TextRange(0, explicit.textLength)
                ElementManipulators.handleContentChange(explicit, range, name)
            }

            else -> (classPointer.element as? PsiNamedElement)
                ?.setName(
                    when (declaration.implicitNameKind) {
                        JmixSpringBeanImplicitNameKind.CLASS ->
                            springClassNameForBeanName(name)

                        JmixSpringBeanImplicitNameKind.FACTORY_METHOD -> name
                    },
                )
        }
        return this
    }

    override fun getText(): String = name

    override fun getNavigationElement(): PsiElement =
        navigationPointer.element ?: this

    override fun getTextRange() =
        navigationPointer.element?.textRange ?: TextRange.EMPTY_RANGE

    override fun getTextOffset(): Int =
        navigationPointer.element?.textOffset ?: 0

    override fun getUseScope(): SearchScope =
        GlobalSearchScope.projectScope(projectRef)

    override fun isValid(): Boolean = navigationPointer.element != null

    override fun isWritable(): Boolean =
        navigationPointer.element?.isWritable == true

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another is JmixSpringBeanElement &&
            navigationPointer.element?.let { navigation ->
                another.navigationPointer.element?.let { other ->
                    managerRef.areElementsEquivalent(navigation, other)
                }
            } == true

    override fun getPresentableText(): String = name

    override fun toString(): String = "Spring bean '$name'"
}

internal fun jmixSpringBeanDeclarations(
    context: PsiElement,
): List<JmixSpringBeanDeclaration> =
    JmixSpringBeanSymbolService.getInstance(context.project).beans()

internal class JmixJavaSpringBeanDeclarationReference(
    element: PsiLiteralExpression,
    range: TextRange,
    private val beanName: String,
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        explicitBeanDeclarations(element, beanName)
            .map(::JmixSpringBeanElement)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val replacement = JavaPsiFacade.getElementFactory(element.project)
            .createExpressionFromText(
                "\"${escapeSpringBeanString(newElementName)}\"",
                element,
            )
        return element.replace(replacement)
    }
}

internal class JmixKotlinSpringBeanDeclarationReference(
    element: PsiLanguageInjectionHost,
    range: TextRange,
    private val beanName: String,
) : PsiPolyVariantReferenceBase<PsiLanguageInjectionHost>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        explicitBeanDeclarations(element, beanName)
            .map(::JmixSpringBeanElement)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        ElementManipulators.handleContentChange(
            element,
            rangeInElement,
            newElementName,
        )
}

internal fun PsiLiteralExpression.isJmixSpringBeanNameDeclaration(): Boolean {
    val annotation = PsiTreeUtil.getParentOfType(
        this,
        PsiAnnotation::class.java,
        false,
    ) ?: return false
    if (!annotation.isSpringBeanNameAnnotation()) return false
    val declared = annotation.findDeclaredAttributeValue("value")
        ?: annotation.findDeclaredAttributeValue("name")
    return manager.areElementsEquivalent(declared, this) ||
        declared?.let { PsiTreeUtil.isAncestor(it, this, false) } == true
}

internal fun KotlinAnnotationContext.isJmixSpringBeanNameDeclaration(): Boolean =
    name in SPRING_BEAN_STEREOTYPES &&
        (attributeName == null || attributeName == "value" || attributeName == "name")

private fun explicitBeanDeclarations(
    element: PsiElement,
    beanName: String,
): List<JmixSpringBeanDeclaration> =
    jmixSpringBeanDeclarations(element).filter { declaration ->
        declaration.name == beanName &&
            declaration.explicitNameElement?.let { explicit ->
                element.manager.areElementsEquivalent(explicit, element)
            } == true
    }

private fun PsiClass.jmixSpringBeanDeclaration(): JmixSpringBeanDeclaration? {
    val annotation = annotations.firstOrNull(PsiAnnotation::isSpringBeanStereotype)
        ?: return null
    val explicit = annotation.findDeclaredAttributeValue("value")
        ?: annotation.findDeclaredAttributeValue("name")
    val literal = explicit as? PsiLiteralExpression
    val explicitName = (literal?.value as? String)?.takeIf(String::isNotBlank)
    val className = name?.takeIf(String::isNotBlank) ?: return null
    val navigation = literal ?: nameIdentifier ?: this
    val methods = allMethods.asSequence()
        .filterNot(PsiMethod::isConstructor)
        .filterNot { method ->
            method.containingClass?.qualifiedName == "java.lang.Object"
        }
        .map(PsiMethod::jmixMenuMethodDeclaration)
        .distinctBy { declaration ->
            val owner = declaration.element.containingFile?.virtualFile?.path.orEmpty()
            "$owner:${declaration.element.textOffset}:${declaration.signature}"
        }
        .sortedWith(
            compareBy<JmixSpringBeanMethodDeclaration> { it.name }
                .thenBy { it.signature },
        )
        .toList()
    return JmixSpringBeanDeclaration(
        name = explicitName ?: springDefaultBeanName(className),
        navigationElement = navigation,
        classElement = this,
        explicitNameElement = literal,
        methods = methods,
        implicitNameKind = JmixSpringBeanImplicitNameKind.CLASS,
    )
}

private fun PsiAnnotation.isSpringBeanStereotype(): Boolean {
    val name = qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.referenceName
        ?: return false
    return name in SPRING_BEAN_STEREOTYPES
}

private fun PsiAnnotation.isSpringBeanNameAnnotation(): Boolean {
    val name = qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.referenceName
        ?: return false
    return name in SPRING_BEAN_STEREOTYPES || name == "Bean"
}

private fun PsiMethod.jmixBeanFactoryDeclarations():
    Sequence<JmixSpringBeanDeclaration> {
    val annotation = annotations.firstOrNull { candidate ->
        val name = candidate.qualifiedName?.substringAfterLast('.')
            ?: candidate.nameReferenceElement?.referenceName
        name == "Bean"
    } ?: return emptySequence()
    val declared = annotation.findDeclaredAttributeValue("value")
        ?: annotation.findDeclaredAttributeValue("name")
    val explicitLiterals = when (declared) {
        is PsiLiteralExpression -> listOf(declared)
        null -> emptyList()
        else -> PsiTreeUtil.findChildrenOfType(
            declared,
            PsiLiteralExpression::class.java,
        ).toList()
    }.mapNotNull { literal ->
        (literal.value as? String)
            ?.takeIf(String::isNotBlank)
            ?.let { name -> name to literal }
    }
    val producedClass = returnType?.let(PsiUtil::resolveClassInClassTypeOnly)
    val methods = producedClass?.allMethods
        ?.asSequence()
        ?.filterNot(PsiMethod::isConstructor)
        ?.filterNot { method ->
            method.containingClass?.qualifiedName == "java.lang.Object"
        }
        ?.map(PsiMethod::jmixMenuMethodDeclaration)
        ?.distinctBy { declaration ->
            val owner = declaration.element.containingFile?.virtualFile?.path.orEmpty()
            "$owner:${declaration.element.textOffset}:${declaration.signature}"
        }
        ?.sortedWith(
            compareBy<JmixSpringBeanMethodDeclaration> { it.name }
                .thenBy { it.signature },
        )
        ?.toList()
        .orEmpty()
    val names = if (explicitLiterals.isNotEmpty()) {
        explicitLiterals
    } else {
        listOf(name to null)
    }
    return names.asSequence().map { (beanName, literal) ->
        JmixSpringBeanDeclaration(
            name = beanName,
            navigationElement = literal ?: nameIdentifier ?: this,
            classElement = this,
            explicitNameElement = literal,
            methods = methods,
            implicitNameKind = JmixSpringBeanImplicitNameKind.FACTORY_METHOD,
        )
    }
}

private fun PsiMethod.jmixMenuMethodDeclaration(): JmixSpringBeanMethodDeclaration {
    val reason = when {
        !hasModifierProperty(PsiModifier.PUBLIC) ->
            "Menu bean method must be public"

        hasModifierProperty(PsiModifier.STATIC) ->
            "Menu bean method must be an instance method"

        returnType != PsiTypes.voidType() ->
            "Menu bean method must return void"

        parameterList.parametersCount > 1 ->
            "Menu bean method must have no parameters or one Map<String, Object> parameter"

        parameterList.parametersCount == 1 &&
            !parameterList.parameters.single().type.isMenuPropertiesMap() ->
            "Menu bean method parameter must be Map<String, Object>"

        else -> null
    }
    return JmixSpringBeanMethodDeclaration(
        name = name,
        element = this,
        signature = formatJavaMenuSignature(),
        invalidReason = reason,
    )
}

private fun PsiMethod.formatJavaMenuSignature(): String =
    buildString {
        append(name)
        append('(')
        append(
            parameterList.parameters.joinToString { parameter ->
                parameter.type.presentableText
            },
        )
        append(')')
    }

private fun PsiType.isMenuPropertiesMap(): Boolean {
    val canonical = canonicalText.substringBefore('<').trim()
    val presentable = presentableText.substringBefore('<').trim()
    return canonical == "java.util.Map" ||
        canonical == "Map" ||
        presentable == "java.util.Map" ||
        presentable == "Map"
}

private fun PsiNamedElement.jmixKotlinSpringBeanDeclaration(): JmixSpringBeanDeclaration? {
    val header = text.substringBefore('{')
    if (KOTLIN_SPRING_STEREOTYPE.find(header) == null) return null
    val className = name?.takeIf(String::isNotBlank) ?: return null
    val explicitHost = PsiTreeUtil.findChildrenOfType(
        this,
        PsiLanguageInjectionHost::class.java,
    ).asSequence()
        .filter { it.javaClass.simpleName == "KtStringTemplateExpression" }
        .firstOrNull { host ->
            val context = host.kotlinAnnotationContext() ?: return@firstOrNull false
            context.name in SPRING_BEAN_STEREOTYPES &&
                (context.attributeName == null ||
                    context.attributeName == "value" ||
                    context.attributeName == "name")
        }
    val explicitName = explicitHost
        ?.kotlinStringContentRange()
        ?.substring(explicitHost.text)
        ?.takeIf(String::isNotBlank)
    val navigation = explicitHost ?: this
    val methods = PsiTreeUtil.findChildrenOfType(
        this,
        PsiNamedElement::class.java,
    ).asSequence()
        .filter { it.javaClass.simpleName == "KtNamedFunction" }
        .filter { function -> function.nearestKotlinClass() === this }
        .map(PsiNamedElement::jmixKotlinMenuMethodDeclaration)
        .sortedWith(
            compareBy<JmixSpringBeanMethodDeclaration> { it.name }
                .thenBy { it.signature },
        )
        .toList()
    return JmixSpringBeanDeclaration(
        name = explicitName ?: springDefaultBeanName(className),
        navigationElement = navigation,
        classElement = this,
        explicitNameElement = explicitHost,
        methods = methods,
        implicitNameKind = JmixSpringBeanImplicitNameKind.CLASS,
    )
}

private fun PsiElement.nearestKotlinClass(): PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }

private fun PsiNamedElement.jmixKotlinMenuMethodDeclaration():
    JmixSpringBeanMethodDeclaration {
    val functionName = name.orEmpty()
    val signature = kotlinFunctionSignature(text, functionName)
    val reason = when {
        KOTLIN_NON_PUBLIC_MODIFIER.containsMatchIn(signature.header) ->
            "Menu bean method must be public"

        KOTLIN_SUSPEND_MODIFIER.containsMatchIn(signature.header) ->
            "Menu bean method cannot be suspend"

        signature.returnType != null && signature.returnType !in KOTLIN_UNIT_TYPES ->
            "Menu bean method must return Unit"

        signature.expressionBody ->
            "Menu bean method must have a Unit block body"

        signature.parameters.size > 1 ->
            "Menu bean method must have no parameters or one Map<String, Any> parameter"

        signature.parameters.size == 1 &&
            !KOTLIN_MAP_PARAMETER.containsMatchIn(signature.parameters.single()) ->
            "Menu bean method parameter must be Map<String, Any>"

        else -> null
    }
    return JmixSpringBeanMethodDeclaration(
        name = functionName,
        element = this,
        signature = signature.display,
        invalidReason = reason,
    )
}

private data class KotlinFunctionSignature(
    val header: String,
    val display: String,
    val parameters: List<String>,
    val returnType: String?,
    val expressionBody: Boolean,
)

private fun kotlinFunctionSignature(
    source: String,
    name: String,
): KotlinFunctionSignature {
    val open = source.indexOf('(')
    val close = if (open >= 0) matchingDelimiter(source, open, '(', ')') else -1
    val header = if (close >= 0) source.substring(0, close + 1) else source
    val parameterSource = if (open >= 0 && close > open) {
        source.substring(open + 1, close)
    } else {
        ""
    }
    val parameters = splitTopLevelParameters(parameterSource)
    val afterParameters = if (close >= 0) source.substring(close + 1) else ""
    val returnType = KOTLIN_RETURN_TYPE.find(afterParameters)
        ?.groupValues
        ?.get(1)
        ?.trim()
    return KotlinFunctionSignature(
        header = header,
        display = "$name(${parameters.joinToString()})",
        parameters = parameters,
        returnType = returnType,
        expressionBody = afterParameters.trimStart().startsWith("=") ||
            returnType != null &&
            afterParameters.substringAfter(returnType).trimStart().startsWith("="),
    )
}

private fun matchingDelimiter(
    source: String,
    start: Int,
    open: Char,
    close: Char,
): Int {
    var depth = 0
    var quote: Char? = null
    var cursor = start
    while (cursor < source.length) {
        val character = source[cursor]
        when {
            quote != null && character == quote && source.getOrNull(cursor - 1) != '\\' ->
                quote = null

            quote != null -> Unit
            character == '"' || character == '\'' -> quote = character
            character == open -> depth++
            character == close -> {
                depth--
                if (depth == 0) return cursor
            }
        }
        cursor++
    }
    return -1
}

private fun splitTopLevelParameters(source: String): List<String> {
    if (source.isBlank()) return emptyList()
    val parameters = mutableListOf<String>()
    var angle = 0
    var round = 0
    var square = 0
    var cursor = 0
    var start = 0
    while (cursor < source.length) {
        when (source[cursor]) {
            '<' -> angle++
            '>' -> if (angle > 0) angle--
            '(' -> round++
            ')' -> if (round > 0) round--
            '[' -> square++
            ']' -> if (square > 0) square--
            ',' -> if (angle == 0 && round == 0 && square == 0) {
                parameters += source.substring(start, cursor).trim()
                start = cursor + 1
            }
        }
        cursor++
    }
    parameters += source.substring(start).trim()
    return parameters.filter(String::isNotBlank)
}

internal fun springDefaultBeanName(className: String): String =
    when {
        className.length > 1 &&
            className[0].isUpperCase() &&
            className[1].isUpperCase() -> className

        else -> className.replaceFirstChar(Char::lowercase)
    }

private fun springClassNameForBeanName(beanName: String): String =
    beanName.replaceFirstChar(Char::uppercase)

internal fun JmixSpringBeanImplicitNameKind.beanNameAfterBackingRename(
    newName: String,
): String =
    when (this) {
        JmixSpringBeanImplicitNameKind.CLASS -> springDefaultBeanName(newName)
        JmixSpringBeanImplicitNameKind.FACTORY_METHOD -> newName
    }

private fun escapeSpringBeanString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

internal val SPRING_BEAN_STEREOTYPES = setOf(
    "Component",
    "Service",
    "Repository",
    "Controller",
    "RestController",
    "Configuration",
    "Named",
)
private val KOTLIN_SPRING_STEREOTYPE =
    Regex("""@(?:[\w.]+\.)?(?:${SPRING_BEAN_STEREOTYPES.joinToString("|")})\b""")
private val KOTLIN_NON_PUBLIC_MODIFIER =
    Regex("""\b(?:private|protected|internal)\b""")
private val KOTLIN_SUSPEND_MODIFIER = Regex("""\bsuspend\b""")
private val KOTLIN_RETURN_TYPE =
    Regex("""^\s*:\s*([A-Za-z_][\w.]*)""")
private val KOTLIN_MAP_PARAMETER =
    Regex("""\b(?:kotlin\.collections\.|java\.util\.)?(?:Mutable)?Map\s*<""")
private val KOTLIN_UNIT_TYPES = setOf("Unit", "kotlin.Unit")

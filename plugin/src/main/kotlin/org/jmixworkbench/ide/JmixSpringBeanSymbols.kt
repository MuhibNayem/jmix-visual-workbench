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
import com.intellij.psi.PsiClassType
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
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.PsiShortNamesCache
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
        buildList {
            val declarations = PsiTreeUtil.findChildrenOfType(
                file,
                PsiNamedElement::class.java,
            )
            declarations.asSequence()
                .onEach { ProgressManager.checkCanceled() }
                .filter {
                    it.javaClass.simpleName == "KtClass" ||
                        it.javaClass.simpleName == "KtObjectDeclaration"
                }
                .mapNotNull(PsiNamedElement::jmixKotlinSpringBeanDeclaration)
                .forEach(::add)
            declarations.asSequence()
                .onEach { ProgressManager.checkCanceled() }
                .filter { it.javaClass.simpleName == "KtNamedFunction" }
                .flatMap(PsiNamedElement::jmixKotlinBeanFactoryDeclarations)
                .forEach(::add)
        }

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
    val restInvalidReason: String?,
    val parameters: List<JmixSpringBeanMethodParameterDeclaration>,
) {
    val isMenuCallable: Boolean
        get() = invalidReason == null

    val isRestCallable: Boolean
        get() = restInvalidReason == null
}

internal data class JmixSpringBeanMethodParameterDeclaration(
    val name: String,
    val element: PsiNamedElement,
    val canonicalType: String,
    val presentableType: String,
)

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
    val restReason = when {
        !hasModifierProperty(PsiModifier.PUBLIC) ->
            "REST service method must be public"

        else -> null
    }
    val parameters = parameterList.parameters.map { parameter ->
        JmixSpringBeanMethodParameterDeclaration(
            name = parameter.name,
            element = parameter,
            canonicalType = parameter.type.canonicalText,
            presentableType = parameter.type.presentableText,
        )
    }
    return JmixSpringBeanMethodDeclaration(
        name = name,
        element = this,
        signature = formatJavaMenuSignature(),
        invalidReason = reason,
        restInvalidReason = restReason,
        parameters = parameters,
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
    val classType = this as? PsiClassType ?: return false
    val rawType = classType.rawType()
    val rawName = rawType.resolve()?.qualifiedName
        ?: rawType.canonicalText
    if (rawName != "java.util.Map" && rawName != "Map") return false

    val arguments = classType.parameters
    if (arguments.size != 2) return false
    return arguments[0].canonicalText in JAVA_STRING_TYPES &&
        arguments[1].canonicalText in JAVA_OBJECT_TYPES
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
    val methods = jmixKotlinProducedMethods()
    return JmixSpringBeanDeclaration(
        name = explicitName ?: springDefaultBeanName(className),
        navigationElement = navigation,
        classElement = this,
        explicitNameElement = explicitHost,
        methods = methods,
        implicitNameKind = JmixSpringBeanImplicitNameKind.CLASS,
    )
}

private fun PsiNamedElement.jmixKotlinBeanFactoryDeclarations():
    Sequence<JmixSpringBeanDeclaration> {
    val functionName = name?.takeIf(String::isNotBlank)
        ?: return emptySequence()
    val header = text.substringBefore('{')
    if (KOTLIN_BEAN_FACTORY_ANNOTATION.find(header) == null) {
        return emptySequence()
    }
    val signature = kotlinFunctionSignature(text, functionName)
    val returnTypeName = signature.returnType
        ?.removeSuffix("?")
        ?.takeIf(String::isNotBlank)
        ?: return emptySequence()
    val explicitNames = PsiTreeUtil.findChildrenOfType(
        this,
        PsiLanguageInjectionHost::class.java,
    ).asSequence()
        .filter { it.javaClass.simpleName == "KtStringTemplateExpression" }
        .mapNotNull { host ->
            val context = host.kotlinAnnotationContext() ?: return@mapNotNull null
            if (context.name != "Bean" ||
                context.attributeName !in setOf(null, "value", "name")
            ) {
                return@mapNotNull null
            }
            host.kotlinStringContentRange()
                ?.substring(host.text)
                ?.takeIf(String::isNotBlank)
                ?.let { name -> name to host }
        }
        .toList()
    val producedSourceClass = containingFile?.let { file ->
        PsiTreeUtil.findChildrenOfType(
            file,
            PsiNamedElement::class.java,
        ).firstOrNull { candidate ->
            candidate.name == returnTypeName.substringAfterLast('.') &&
                (candidate.javaClass.simpleName == "KtClass" ||
                    candidate.javaClass.simpleName == "KtObjectDeclaration")
        }
    }
    val producedPsiClass = when {
        producedSourceClass != null -> null
        '.' in returnTypeName ->
            JavaPsiFacade.getInstance(project).findClass(
                returnTypeName,
                ProjectScope.getAllScope(project),
            )

        else ->
            PsiShortNamesCache.getInstance(project)
                .getClassesByName(
                    returnTypeName,
                    ProjectScope.getAllScope(project),
                )
                .singleOrNull()
    }
    val methods = when {
        producedSourceClass != null ->
            producedSourceClass.jmixKotlinProducedMethods()

        producedPsiClass != null ->
            producedPsiClass.allMethods.asSequence()
                .filterNot(PsiMethod::isConstructor)
                .filterNot { method ->
                    method.containingClass?.qualifiedName == "java.lang.Object"
                }
                .map(PsiMethod::jmixMenuMethodDeclaration)
                .distinctBy { declaration ->
                    val owner = declaration.element.containingFile
                        ?.virtualFile
                        ?.path
                        .orEmpty()
                    "$owner:${declaration.element.textOffset}:${declaration.signature}"
                }
                .sortedWith(
                    compareBy<JmixSpringBeanMethodDeclaration> { it.name }
                        .thenBy { it.signature },
                )
                .toList()

        else -> emptyList()
    }
    val names: List<Pair<String, PsiElement?>> =
        explicitNames.ifEmpty { listOf(functionName to null) }
    return names.asSequence().map { (beanName, explicit) ->
        JmixSpringBeanDeclaration(
            name = beanName,
            navigationElement = explicit ?: this,
            classElement = this,
            explicitNameElement = explicit,
            methods = methods,
            implicitNameKind = JmixSpringBeanImplicitNameKind.FACTORY_METHOD,
        )
    }
}

private fun PsiNamedElement.jmixKotlinProducedMethods():
    List<JmixSpringBeanMethodDeclaration> =
    PsiTreeUtil.findChildrenOfType(
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
            !signature.parameters.single().isKotlinMenuPropertiesMapParameter() ->
            "Menu bean method parameter must be Map<String, Any>"

        else -> null
    }
    val restReason = when {
        KOTLIN_NON_PUBLIC_MODIFIER.containsMatchIn(signature.header) ->
            "REST service method must be public"

        KOTLIN_SUSPEND_MODIFIER.containsMatchIn(signature.header) ->
            "REST service method cannot be suspend"

        else -> null
    }
    val parameterElements = PsiTreeUtil.findChildrenOfType(
        this,
        PsiNamedElement::class.java,
    ).asSequence()
        .filter { it.javaClass.simpleName == "KtParameter" }
        .filter { parameter -> parameter.nearestKotlinFunction() === this }
        .mapNotNull { parameter ->
            val parameterName = parameter.name?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val parameterText = parameter.text.trim()
            val isVararg = parameterText.startsWith("vararg ")
            val declaredType = parameterText
                .removePrefix("vararg ")
                .substringAfter(':', "")
                .substringBefore('=')
                .trim()
            val canonicalType = parameter.kotlinJvmCanonicalType(
                declaredType,
                isVararg,
            )
            JmixSpringBeanMethodParameterDeclaration(
                name = parameterName,
                element = parameter,
                canonicalType = canonicalType,
                presentableType = if (isVararg) {
                    "vararg $declaredType"
                } else {
                    declaredType
                },
            )
        }
        .toList()
    return JmixSpringBeanMethodDeclaration(
        name = functionName,
        element = this,
        signature = signature.display,
        invalidReason = reason,
        restInvalidReason = restReason,
        parameters = parameterElements,
    )
}

private fun PsiElement.nearestKotlinFunction(): PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtNamedFunction" }

private fun PsiNamedElement.kotlinJvmCanonicalType(
    declaredType: String,
    isVararg: Boolean,
): String {
    val normalized = declaredType.replace(Regex("""\s+"""), "")
    val nullable = normalized.endsWith('?')
    val withoutNullable = normalized.removeSuffix("?")
    val arrayElement = withoutNullable
        .takeIf { it.startsWith("Array<") && it.endsWith('>') }
        ?.substringAfter('<')
        ?.dropLast(1)
    val canonical = when {
        arrayElement != null -> {
            val elementType = kotlinJvmCanonicalType(arrayElement, false)
            "${JVM_PRIMITIVE_WRAPPERS[elementType] ?: elementType}[]"
        }

        withoutNullable in KOTLIN_PRIMITIVE_ARRAY_TYPES ->
            requireNotNull(KOTLIN_PRIMITIVE_ARRAY_TYPES[withoutNullable])

        else -> {
            val rawType = withoutNullable.substringBefore('<')
            val builtIn = if (nullable) {
                KOTLIN_NULLABLE_JVM_TYPES[rawType] ?: KOTLIN_JVM_TYPES[rawType]
            } else {
                KOTLIN_JVM_TYPES[rawType]
            }
            builtIn ?: resolveKotlinJvmClassName(rawType) ?: rawType
        }
    }
    return if (isVararg) "$canonical[]" else canonical
}

private fun PsiNamedElement.resolveKotlinJvmClassName(
    typeName: String,
): String? {
    if (typeName.isBlank()) return null
    val projectScope = ProjectScope.getAllScope(project)
    if ('.' in typeName) {
        JavaPsiFacade.getInstance(project)
            .findClass(typeName, projectScope)
            ?.qualifiedName
            ?.let { return it }
    }
    val simpleName = typeName.substringAfterLast('.')
    val fileText = containingFile?.text.orEmpty()
    val imported = KOTLIN_IMPORT_DIRECTIVE.findAll(fileText)
        .mapNotNull { match ->
            val qualifiedName = match.groupValues[1]
            val alias = match.groupValues[2].takeIf(String::isNotBlank)
            if ((alias ?: qualifiedName.substringAfterLast('.')) == simpleName) {
                qualifiedName
            } else {
                null
            }
        }
        .firstOrNull()
    if (imported != null) return imported

    val packageName = KOTLIN_PACKAGE_DIRECTIVE.find(fileText)
        ?.groupValues
        ?.get(1)
        .orEmpty()
    if (packageName.isNotBlank()) {
        JavaPsiFacade.getInstance(project)
            .findClass("$packageName.$simpleName", projectScope)
            ?.qualifiedName
            ?.let { return it }
    }
    return PsiShortNamesCache.getInstance(project)
        .getClassesByName(simpleName, projectScope)
        .mapNotNull(PsiClass::getQualifiedName)
        .distinct()
        .singleOrNull()
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
    val functionStart = Regex("""\bfun\b""").find(source)
        ?.range
        ?.last
        ?.plus(1)
        ?: -1
    val nameStart = if (functionStart >= 0) {
        Regex("""\b${Regex.escape(name)}\s*\(""")
            .find(source, functionStart)
            ?.range
            ?.first
            ?: -1
    } else {
        -1
    }
    val open = if (nameStart >= 0) {
        source.indexOf('(', nameStart + name.length)
    } else {
        -1
    }
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

private fun String.isKotlinMenuPropertiesMapParameter(): Boolean {
    val declaredType = substringAfter(':', "")
        .substringBefore('=')
        .trim()
    return KOTLIN_MAP_PARAMETER.matches(declaredType)
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
    "RestService",
    "Repository",
    "Controller",
    "RestController",
    "Configuration",
    "Named",
)
private val KOTLIN_SPRING_STEREOTYPE =
    Regex("""@(?:[\w.]+\.)?(?:${SPRING_BEAN_STEREOTYPES.joinToString("|")})\b""")
private val KOTLIN_BEAN_FACTORY_ANNOTATION =
    Regex("""@(?:[\w.]+\.)?Bean\b""")
private val KOTLIN_NON_PUBLIC_MODIFIER =
    Regex("""\b(?:private|protected|internal)\b""")
private val KOTLIN_SUSPEND_MODIFIER = Regex("""\bsuspend\b""")
private val KOTLIN_PACKAGE_DIRECTIVE =
    Regex("""(?m)^\s*package\s+([\w.]+)\s*$""")
private val KOTLIN_IMPORT_DIRECTIVE =
    Regex("""(?m)^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$""")
private val KOTLIN_JVM_TYPES = mapOf(
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
    "Collection" to "java.util.Collection",
    "kotlin.collections.Collection" to "java.util.Collection",
    "MutableCollection" to "java.util.Collection",
    "kotlin.collections.MutableCollection" to "java.util.Collection",
    "Iterable" to "java.lang.Iterable",
    "kotlin.collections.Iterable" to "java.lang.Iterable",
    "List" to "java.util.List",
    "kotlin.collections.List" to "java.util.List",
    "MutableList" to "java.util.List",
    "kotlin.collections.MutableList" to "java.util.List",
    "Map" to "java.util.Map",
    "kotlin.collections.Map" to "java.util.Map",
    "MutableMap" to "java.util.Map",
    "kotlin.collections.MutableMap" to "java.util.Map",
    "Set" to "java.util.Set",
    "kotlin.collections.Set" to "java.util.Set",
    "MutableSet" to "java.util.Set",
    "kotlin.collections.MutableSet" to "java.util.Set",
)
private val KOTLIN_NULLABLE_JVM_TYPES = mapOf(
    "Boolean" to "java.lang.Boolean",
    "kotlin.Boolean" to "java.lang.Boolean",
    "Byte" to "java.lang.Byte",
    "kotlin.Byte" to "java.lang.Byte",
    "Char" to "java.lang.Character",
    "kotlin.Char" to "java.lang.Character",
    "Double" to "java.lang.Double",
    "kotlin.Double" to "java.lang.Double",
    "Float" to "java.lang.Float",
    "kotlin.Float" to "java.lang.Float",
    "Int" to "java.lang.Integer",
    "kotlin.Int" to "java.lang.Integer",
    "Long" to "java.lang.Long",
    "kotlin.Long" to "java.lang.Long",
    "Short" to "java.lang.Short",
    "kotlin.Short" to "java.lang.Short",
)
private val KOTLIN_PRIMITIVE_ARRAY_TYPES = mapOf(
    "BooleanArray" to "boolean[]",
    "kotlin.BooleanArray" to "boolean[]",
    "ByteArray" to "byte[]",
    "kotlin.ByteArray" to "byte[]",
    "CharArray" to "char[]",
    "kotlin.CharArray" to "char[]",
    "DoubleArray" to "double[]",
    "kotlin.DoubleArray" to "double[]",
    "FloatArray" to "float[]",
    "kotlin.FloatArray" to "float[]",
    "IntArray" to "int[]",
    "kotlin.IntArray" to "int[]",
    "LongArray" to "long[]",
    "kotlin.LongArray" to "long[]",
    "ShortArray" to "short[]",
    "kotlin.ShortArray" to "short[]",
)
private val JVM_PRIMITIVE_WRAPPERS = mapOf(
    "boolean" to "java.lang.Boolean",
    "byte" to "java.lang.Byte",
    "char" to "java.lang.Character",
    "double" to "java.lang.Double",
    "float" to "java.lang.Float",
    "int" to "java.lang.Integer",
    "long" to "java.lang.Long",
    "short" to "java.lang.Short",
)
private val KOTLIN_RETURN_TYPE =
    Regex("""^\s*:\s*([A-Za-z_][\w.]*)""")
private val KOTLIN_MAP_PARAMETER =
    Regex(
        """(?:kotlin\.collections\.|java\.util\.)?(?:Mutable)?Map\s*""" +
            """<\s*(?:kotlin\.)?String\s*,\s*""" +
            """(?:(?:kotlin\.)?Any|(?:java\.lang\.)?Object)\??\s*>""",
    )
private val KOTLIN_UNIT_TYPES = setOf("Unit", "kotlin.Unit")
private val JAVA_STRING_TYPES = setOf("java.lang.String", "String")
private val JAVA_OBJECT_TYPES = setOf("java.lang.Object", "Object")

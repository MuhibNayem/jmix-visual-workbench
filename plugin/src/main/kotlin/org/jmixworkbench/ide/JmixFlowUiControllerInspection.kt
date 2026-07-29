package org.jmixworkbench.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

/**
 * Native controller-contract validation matching the safety-critical part of
 * Jmix Studio's FlowUI coding assistance.
 *
 * The analyzer works from the controller's real PSI and is shared with the
 * visual View Designer. Java inspection, visual diagnostics and generation
 * therefore cannot silently disagree about an existing handler.
 */
class JmixJavaFlowUiControllerInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor = object : JavaElementVisitor() {
        override fun visitClass(aClass: PsiClass) {
            jmixJavaFlowUiControllerIssues(aClass)
                .values
                .flatten()
                .forEach { issue -> holder.registerJmixControllerIssue(issue) }
        }
    }
}

/**
 * Kotlin K2-safe structural validation. It deliberately uses stable platform
 * PSI contracts rather than Kotlin compiler implementation classes.
 */
class JmixKotlinFlowUiControllerInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element.javaClass.simpleName !in KOTLIN_CONTROLLER_TYPES) return
            if (element.parent?.let { parent ->
                    parent.javaClass.simpleName in KOTLIN_CONTROLLER_TYPES
                } == true
            ) {
                return
            }
            jmixKotlinFlowUiControllerIssues(element).forEach { issue ->
                holder.registerJmixControllerIssue(issue)
            }
        }
    }
}

internal data class JmixFlowUiControllerIssue(
    val element: PsiElement,
    val code: String,
    val message: String,
    val severity: ProblemHighlightType = ProblemHighlightType.GENERIC_ERROR,
    val quickFix: LocalQuickFix? = null,
)

internal fun jmixJavaFlowUiControllerIssues(
    controllerClass: PsiClass,
): Map<PsiModifierListOwner, List<JmixFlowUiControllerIssue>> {
    val issues = linkedMapOf<PsiModifierListOwner, MutableList<JmixFlowUiControllerIssue>>()
    val isController = controllerClass.isJmixFlowUiController()
    val injections = controllerClass.fields
        .filter { field ->
            field.annotations.any { it.jmixShortName() == "ViewComponent" }
        }
        .associateBy { field ->
            field.annotations
                .first { it.jmixShortName() == "ViewComponent" }
                .stringAttribute("value")
                ?.ifBlank { field.name }
                ?: field.name
        }

    controllerClass.fields.forEach { field ->
        val annotation = field.annotations
            .firstOrNull { it.jmixShortName() == "ViewComponent" }
            ?: return@forEach
        val fieldIssues = issues.getOrPut(field, ::mutableListOf)
        if (!isController) {
            fieldIssues += annotation.issue(
                "JMIX-VIEW-COMPONENT-LOCATION",
                "@ViewComponent injection must be declared inside a Jmix view or fragment controller",
            )
        }
        if (field.hasModifierProperty(PsiModifier.STATIC)) {
            fieldIssues += annotation.issue(
                "JMIX-VIEW-COMPONENT-STATIC",
                "@ViewComponent injection must be an instance field",
            )
        }
        val classType = field.type as? PsiClassType
        val resolvedType = classType?.resolve()
        if (classType != null &&
            resolvedType != null &&
            resolvedType.typeParameters.isNotEmpty() &&
            classType.parameters.isEmpty()
        ) {
            val typeElement = field.typeElement ?: field
            fieldIssues += JmixFlowUiControllerIssue(
                element = typeElement,
                code = "JMIX-VIEW-COMPONENT-RAW-TYPE",
                message = "Generic @ViewComponent injection '${field.name}' must declare type arguments",
                severity = ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                quickFix = AddJmixWildcardTypeArgumentsQuickFix(
                    resolvedType.typeParameters.size,
                ),
            )
        }
    }

    val installationGroups = linkedMapOf<JmixInstallationKey, MutableList<PsiMethod>>()
    controllerClass.methods.forEach { method ->
        method.annotations
            .filter { it.jmixShortName() in JMIX_HANDLER_ANNOTATIONS }
            .forEach { annotation ->
                val methodIssues = issues.getOrPut(method, ::mutableListOf)
                val kind = annotation.jmixShortName()
                if (!isController) {
                    methodIssues += annotation.issue(
                        "JMIX-HANDLER-LOCATION",
                        "@$kind method must be declared inside a Jmix view or fragment controller",
                    )
                }
                if (method.hasModifierProperty(PsiModifier.STATIC)) {
                    methodIssues += annotation.issue(
                        "JMIX-HANDLER-STATIC",
                        "@$kind method must be an instance method",
                    )
                }
                when (kind) {
                    "Subscribe" -> validateJavaSubscribe(method, annotation, methodIssues)
                    "Install" -> {
                        validateJavaInstall(method, annotation, injections, methodIssues)
                        annotation.installationKey()?.let { key ->
                            installationGroups.getOrPut(key, ::mutableListOf) += method
                        }
                    }

                    "Supply" -> {
                        validateJavaSupply(method, annotation, methodIssues)
                        annotation.installationKey()?.let { key ->
                            installationGroups.getOrPut(key, ::mutableListOf) += method
                        }
                    }
                }
            }
    }

    installationGroups
        .filterValues { methods -> methods.size > 1 }
        .forEach { (key, methods) ->
            methods.forEach { method ->
                val annotation = method.annotations.first {
                    it.jmixShortName() == "Install" ||
                        it.jmixShortName() == "Supply"
                }
                issues.getOrPut(method, ::mutableListOf) += annotation.issue(
                    "JMIX-INSTALL-DUPLICATE",
                    "Multiple controller methods install '${key.subject}' on " +
                        "'${key.target}' for ${key.scope}",
                )
            }
        }

    return issues
}

private fun validateJavaSubscribe(
    method: PsiMethod,
    annotation: PsiAnnotation,
    issues: MutableList<JmixFlowUiControllerIssue>,
) {
    if (method.returnType != PsiTypes.voidType()) {
        issues += method.issue(
            "JMIX-SUBSCRIBE-RETURN",
            "@Subscribe event handler must return void",
        )
    }
    if (method.parameterList.parametersCount != 1) {
        issues += method.issue(
            "JMIX-SUBSCRIBE-PARAMETERS",
            "@Subscribe event handler must declare exactly one event parameter",
        )
        return
    }
    val eventType = method.parameterList.parameters.single().type
    if (eventType.isResolvedNonEventObject() == true) {
        issues += method.parameterList.parameters.single().issue(
            "JMIX-SUBSCRIBE-EVENT-TYPE",
            "@Subscribe parameter must extend java.util.EventObject",
        )
    }
    val target = annotation.stringAttribute("value")
        ?: annotation.stringAttribute("id")
    if (target.isNullOrBlank() && !annotation.stringAttribute("subject").isNullOrBlank()) {
        issues += annotation.issue(
            "JMIX-SUBSCRIBE-SUBJECT-WITHOUT-TARGET",
            "@Subscribe subject requires a component, data-container, or data-loader target",
        )
    }
}

private fun validateJavaInstall(
    method: PsiMethod,
    annotation: PsiAnnotation,
    injections: Map<String, PsiField>,
    issues: MutableList<JmixFlowUiControllerIssue>,
) {
    val target = annotation.stringAttribute("to").orEmpty()
    if (target.isBlank()) {
        issues += annotation.issue(
            "JMIX-INSTALL-TARGET",
            "@Install must declare a target in 'to'",
        )
        return
    }
    val scope = annotation.targetScope()
    val explicitSubject = annotation.stringAttribute("subject")
    val explicitType = annotation.hasExplicitClassAttribute("type")
    val subject = explicitSubject
        ?: "loadDelegate".takeIf { scope == "DATA_LOADER" }
    if (subject.isNullOrBlank() && !explicitType) {
        issues += annotation.issue(
            "JMIX-INSTALL-SUBJECT",
            "@Install must declare 'subject' or a functional-interface 'type'",
        )
        return
    }
    if (explicitType || subject.isNullOrBlank()) return

    val targetField = injections[target] ?: return
    val targetClass = PsiUtil.resolveClassInClassTypeOnly(targetField.type) ?: return
    val setterName = "set" + subject.replaceFirstChar(Char::uppercaseChar)
    val candidates = targetClass.allMethods.filter { candidate ->
        candidate.name == setterName
    }
    if (candidates.isEmpty()) {
        issues += annotation.issue(
            "JMIX-INSTALL-POINT-MISSING",
            "Unable to find installation point '$setterName' on ${targetClass.name}",
        )
        return
    }
    val validPoints = candidates.mapNotNull { candidate ->
        candidate.functionalInstallationMethod()
    }
    if (validPoints.isEmpty()) {
        issues += annotation.issue(
            "JMIX-INSTALL-POINT-INVALID",
            "Installation point '$setterName' must return void and accept one functional interface",
        )
        return
    }
    val compatible = validPoints.any { functionalMethod ->
        method.parameterList.parametersCount ==
            functionalMethod.parameterList.parametersCount &&
            (method.returnType == PsiTypes.voidType()) ==
            (functionalMethod.returnType == PsiTypes.voidType())
    }
    if (!compatible) {
        issues += method.issue(
            "JMIX-INSTALL-SIGNATURE",
            "@Install delegate signature must match the '$setterName' functional interface",
        )
    }
}

private fun validateJavaSupply(
    method: PsiMethod,
    annotation: PsiAnnotation,
    issues: MutableList<JmixFlowUiControllerIssue>,
) {
    if (annotation.stringAttribute("to").isNullOrBlank()) {
        issues += annotation.issue(
            "JMIX-SUPPLY-TARGET",
            "@Supply must declare a target in 'to'",
        )
    }
    if (annotation.stringAttribute("subject").isNullOrBlank() &&
        !annotation.hasExplicitClassAttribute("type")
    ) {
        issues += annotation.issue(
            "JMIX-SUPPLY-SUBJECT",
            "@Supply must declare 'subject' or supplied object 'type'",
        )
    }
    if (method.parameterList.parametersCount != 0) {
        issues += method.issue(
            "JMIX-SUPPLY-PARAMETERS",
            "@Supply method must not declare parameters",
        )
    }
    if (method.returnType == null || method.returnType == PsiTypes.voidType()) {
        issues += method.issue(
            "JMIX-SUPPLY-RETURN",
            "@Supply method must return the supplied object",
        )
    }
}

private fun PsiMethod.functionalInstallationMethod(): PsiMethod? {
    if (returnType != PsiTypes.voidType() ||
        parameterList.parametersCount != 1
    ) {
        return null
    }
    val functionalClass = PsiUtil.resolveClassInClassTypeOnly(
        parameterList.parameters.single().type,
    ) ?: return null
    if (!functionalClass.isInterface) return null
    val abstractMethods = functionalClass.allMethods
        .asSequence()
        .filter { method ->
            method.hasModifierProperty(PsiModifier.ABSTRACT) &&
                !method.hasModifierProperty(PsiModifier.STATIC) &&
                method.containingClass?.qualifiedName != "java.lang.Object"
        }
        .distinctBy { method ->
            method.name + method.parameterList.parameters.joinToString(
                prefix = "(",
                postfix = ")",
            ) { parameter -> parameter.type.erasureKey() }
        }
        .toList()
    return abstractMethods.singleOrNull()
}

private fun PsiType.erasureKey(): String =
    (this as? PsiClassType)?.rawType()?.canonicalText ?: canonicalText

private fun PsiType.isResolvedNonEventObject(): Boolean? {
    val classType = this as? PsiClassType ?: return true
    val typeClass = classType.resolve()
        ?: return classType.canonicalText
            .substringBefore('<')
            .let { canonical ->
                (canonical.startsWith("java.lang.") ||
                    canonical in KNOWN_NON_EVENT_OBJECT_TYPES)
                    .takeIf { it }
            }
    return !typeClass.hasEventObjectSupertype(mutableSetOf())
}

private fun PsiClass.hasEventObjectSupertype(
    visited: MutableSet<PsiClass>,
): Boolean {
    if (!visited.add(this)) return false
    if (qualifiedName == "java.util.EventObject") return true
    return superTypes.any { superType ->
        superType.canonicalText.substringBefore('<') == "java.util.EventObject" ||
            superType.resolve()?.hasEventObjectSupertype(visited) == true
    }
}

private fun PsiClass.isJmixFlowUiController(): Boolean =
    annotations.any { annotation ->
        annotation.jmixShortName() in JMIX_CONTROLLER_ANNOTATIONS
    }

private fun PsiAnnotation.installationKey(): JmixInstallationKey? {
    val target = stringAttribute("to")?.takeIf(String::isNotBlank) ?: return null
    val scope = targetScope()
    val subject = stringAttribute("subject")
        ?: "loadDelegate".takeIf { scope == "DATA_LOADER" }
        ?: findDeclaredAttributeValue("type")
            ?.text
            ?.takeIf { it.isNotBlank() && !it.contains("Object.class") }
        ?: return null
    return JmixInstallationKey(target, scope, subject)
}

private data class JmixInstallationKey(
    val target: String,
    val scope: String,
    val subject: String,
)

private fun PsiAnnotation.targetScope(): String =
    findDeclaredAttributeValue("target")
        ?.text
        ?.substringAfterLast('.')
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: "COMPONENT"

private fun PsiAnnotation.hasExplicitClassAttribute(name: String): Boolean =
    findDeclaredAttributeValue(name)
        ?.text
        ?.takeIf { text ->
            text.isNotBlank() &&
                !text.contains("Object.class") &&
                !text.contains("Any::class")
        } != null

private fun PsiAnnotation.stringAttribute(name: String): String? =
    findDeclaredAttributeValue(name)
        ?.let { value ->
            JavaPsiFacade.getInstance(project)
                .constantEvaluationHelper
                .computeConstantExpression(value) as? String
        }
        ?.takeIf(String::isNotBlank)

private fun PsiAnnotation.jmixShortName(): String =
    qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.referenceName.orEmpty()

private fun PsiAnnotation.issue(
    code: String,
    message: String,
): JmixFlowUiControllerIssue =
    JmixFlowUiControllerIssue(
        element = nameReferenceElement ?: this,
        code = code,
        message = message,
    )

private fun PsiElement.issue(
    code: String,
    message: String,
): JmixFlowUiControllerIssue =
    JmixFlowUiControllerIssue(
        element = (this as? PsiNamedElement)?.navigationElement ?: this,
        code = code,
        message = message,
    )

private fun ProblemsHolder.registerJmixControllerIssue(
    issue: JmixFlowUiControllerIssue,
) {
    val fixes = issue.quickFix?.let { arrayOf(it) } ?: LocalQuickFix.EMPTY_ARRAY
    registerProblem(
        issue.element,
        issue.message,
        issue.severity,
        *fixes,
    )
}

private class AddJmixWildcardTypeArgumentsQuickFix(
    private val arity: Int,
) : LocalQuickFix {
    override fun getFamilyName(): String =
        "Add generic arguments to Jmix view-component injection"

    override fun getName(): String =
        "Add ${if (arity == 1) "wildcard type argument" else "wildcard type arguments"}"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val typeElement = descriptor.psiElement
        if (!typeElement.isValid || arity <= 0) return
        val replacement = JavaPsiFacade.getElementFactory(project)
            .createTypeElementFromText(
                typeElement.text + List(arity) { "?" }
                    .joinToString(prefix = "<", postfix = ">"),
                typeElement,
            )
        typeElement.replace(replacement)
    }
}

private fun jmixKotlinFlowUiControllerIssues(
    controller: PsiElement,
): List<JmixFlowUiControllerIssue> {
    val controllerHeader = controller.text.substringBefore('{')
    val isController = JMIX_KOTLIN_CONTROLLER_ANNOTATION.containsMatchIn(
        controllerHeader,
    )
    val functions = PsiTreeUtil.findChildrenOfType(
        controller,
        PsiNamedElement::class.java,
    ).asSequence()
        .filter { it.javaClass.simpleName == "KtNamedFunction" }
        .filter { function -> function.nearestKotlinController() === controller }
        .map { function ->
            function to kotlinHandlerAnnotations(function.text)
        }
        .filter { (_, annotations) -> annotations.isNotEmpty() }
        .toList()
    val issues = mutableListOf<JmixFlowUiControllerIssue>()
    val installationGroups = linkedMapOf<JmixInstallationKey, MutableList<PsiElement>>()

    functions.forEach { (function, annotations) ->
        val signature = parseKotlinControllerFunction(function.text)
        annotations.forEach { annotation ->
            if (!isController) {
                issues += function.issue(
                    "JMIX-KOTLIN-HANDLER-LOCATION",
                    "@${annotation.name} function must be declared inside a Jmix view or fragment controller",
                )
            }
            when (annotation.name) {
                "Subscribe" -> {
                    if (signature.returnType !in setOf(null, "Unit", "kotlin.Unit")) {
                        issues += function.issue(
                            "JMIX-KOTLIN-SUBSCRIBE-RETURN",
                            "@Subscribe event handler must return Unit",
                        )
                    }
                    if (signature.parameters.size != 1) {
                        issues += function.issue(
                            "JMIX-KOTLIN-SUBSCRIBE-PARAMETERS",
                            "@Subscribe event handler must declare exactly one event parameter",
                        )
                    } else {
                        val eventType = signature.parameters.single()
                            .substringAfter(':', "")
                            .trim()
                        if (eventType.isNotBlank() &&
                            isResolvedKotlinNonEventObject(function, eventType) == true
                        ) {
                            issues += function.issue(
                                "JMIX-KOTLIN-SUBSCRIBE-EVENT-TYPE",
                                "@Subscribe parameter must extend java.util.EventObject",
                            )
                        }
                    }
                }

                "Install" -> {
                    if (annotation.target.isBlank()) {
                        issues += function.issue(
                            "JMIX-KOTLIN-INSTALL-TARGET",
                            "@Install must declare a target in 'to'",
                        )
                    }
                    val subject = annotation.subject
                        ?: "loadDelegate".takeIf {
                            annotation.scope == "DATA_LOADER"
                        }
                    if (subject == null && !annotation.hasType) {
                        issues += function.issue(
                            "JMIX-KOTLIN-INSTALL-SUBJECT",
                            "@Install must declare 'subject' or a functional-interface 'type'",
                        )
                    }
                    if (annotation.target.isNotBlank() && subject != null) {
                        installationGroups.getOrPut(
                            JmixInstallationKey(
                                annotation.target,
                                annotation.scope,
                                subject,
                            ),
                            ::mutableListOf,
                        ) += function
                    }
                }

                "Supply" -> {
                    if (annotation.target.isBlank()) {
                        issues += function.issue(
                            "JMIX-KOTLIN-SUPPLY-TARGET",
                            "@Supply must declare a target in 'to'",
                        )
                    }
                    if (annotation.subject == null && !annotation.hasType) {
                        issues += function.issue(
                            "JMIX-KOTLIN-SUPPLY-SUBJECT",
                            "@Supply must declare 'subject' or supplied object 'type'",
                        )
                    }
                    if (signature.parameters.isNotEmpty()) {
                        issues += function.issue(
                            "JMIX-KOTLIN-SUPPLY-PARAMETERS",
                            "@Supply function must not declare parameters",
                        )
                    }
                    if (signature.returnType in setOf("Unit", "kotlin.Unit")) {
                        issues += function.issue(
                            "JMIX-KOTLIN-SUPPLY-RETURN",
                            "@Supply function must return the supplied object",
                        )
                    }
                    annotation.subject?.let { subject ->
                        if (annotation.target.isNotBlank()) {
                            installationGroups.getOrPut(
                                JmixInstallationKey(
                                    annotation.target,
                                    annotation.scope,
                                    subject,
                                ),
                                ::mutableListOf,
                            ) += function
                        }
                    }
                }
            }
        }
    }

    installationGroups
        .filterValues { functionsForPoint -> functionsForPoint.size > 1 }
        .forEach { (key, functionsForPoint) ->
            functionsForPoint.forEach { function ->
                issues += function.issue(
                    "JMIX-KOTLIN-INSTALL-DUPLICATE",
                    "Multiple controller functions install '${key.subject}' on " +
                        "'${key.target}' for ${key.scope}",
                )
            }
        }
    return issues
}

private data class KotlinHandlerAnnotation(
    val name: String,
    val target: String,
    val subject: String?,
    val scope: String,
    val hasType: Boolean,
)

private data class KotlinControllerFunctionSignature(
    val parameters: List<String>,
    val returnType: String?,
)

private fun kotlinHandlerAnnotations(source: String): List<KotlinHandlerAnnotation> {
    val functionStart = Regex("""\bfun\b""")
        .find(source)
        ?.range
        ?.first
        ?: source.length
    val header = source.substring(0, functionStart)
    return JMIX_KOTLIN_HANDLER_ANNOTATION.findAll(header).map { match ->
        val name = match.groupValues[1]
        val arguments = match.groupValues[2]
        val targetAttribute = if (name == "Subscribe") "id|value" else "to"
        val target = Regex("""\b(?:$targetAttribute)\s*=\s*"([^"$]+)"""")
            .find(arguments)
            ?.groupValues
            ?.get(1)
            ?: Regex("""^\s*"([^"$]+)"""")
                .find(arguments)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        KotlinHandlerAnnotation(
            name = name,
            target = target,
            subject = Regex("""\bsubject\s*=\s*"([^"$]+)"""")
                .find(arguments)
                ?.groupValues
                ?.get(1),
            scope = Regex("""\btarget\s*=\s*(?:Target\.)?(\w+)""")
                .find(arguments)
                ?.groupValues
                ?.get(1)
                ?: "COMPONENT",
            hasType = Regex("""\btype\s*=""").containsMatchIn(arguments),
        )
    }.toList()
}

private fun parseKotlinControllerFunction(
    source: String,
): KotlinControllerFunctionSignature {
    val funMatch = Regex("""\bfun\s+[A-Za-z_][A-Za-z0-9_]*\s*\(""")
        .find(source)
        ?: return KotlinControllerFunctionSignature(emptyList(), null)
    val open = source.indexOf('(', funMatch.range.first)
    val close = matchingKotlinDelimiter(source, open)
    if (open < 0 || close < 0) {
        return KotlinControllerFunctionSignature(emptyList(), null)
    }
    val parameters = splitKotlinControllerParameters(
        source.substring(open + 1, close),
    )
    val tail = source.substring(close + 1)
        .substringBefore('{')
        .substringBefore('=')
    val returnType = Regex(""":\s*([A-Za-z_][A-Za-z0-9_$.<>?]*)""")
        .find(tail)
        ?.groupValues
        ?.get(1)
    return KotlinControllerFunctionSignature(parameters, returnType)
}

private fun splitKotlinControllerParameters(source: String): List<String> {
    if (source.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    var start = 0
    var angle = 0
    var parentheses = 0
    source.forEachIndexed { index, character ->
        when (character) {
            '<' -> angle++
            '>' -> if (angle > 0) angle--
            '(' -> parentheses++
            ')' -> if (parentheses > 0) parentheses--
            ',' -> if (angle == 0 && parentheses == 0) {
                source.substring(start, index)
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(parts::add)
                start = index + 1
            }
        }
    }
    source.substring(start)
        .trim()
        .takeIf(String::isNotBlank)
        ?.let(parts::add)
    return parts
}

private fun matchingKotlinDelimiter(source: String, open: Int): Int {
    if (open !in source.indices || source[open] != '(') return -1
    var depth = 0
    for (index in open until source.length) {
        when (source[index]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return -1
}

private fun PsiElement.nearestKotlinController(): PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName in KOTLIN_CONTROLLER_TYPES }

private fun isResolvedKotlinNonEventObject(
    context: PsiElement,
    sourceType: String,
): Boolean? {
    val simpleName = sourceType
        .substringBefore('<')
        .removeSuffix("?")
        .substringAfterLast('.')
        .substringAfterLast('$')
    if (simpleName.isBlank()) return null
    val classes = PsiShortNamesCache.getInstance(context.project)
        .getClassesByName(
            simpleName,
            GlobalSearchScope.projectScope(context.project),
        )
    if (classes.isEmpty()) return null
    return classes.none { candidate ->
        candidate.qualifiedName == "java.util.EventObject" ||
            InheritanceUtil.isInheritor(candidate, "java.util.EventObject")
    }
}

private val JMIX_CONTROLLER_ANNOTATIONS =
    setOf("ViewController", "UiController", "FragmentDescriptor")
private val KNOWN_NON_EVENT_OBJECT_TYPES =
    setOf("String", "Object", "Number", "Boolean", "Character")
private val JMIX_HANDLER_ANNOTATIONS = setOf("Subscribe", "Install", "Supply")
private val KOTLIN_CONTROLLER_TYPES =
    setOf("KtClass", "KtObjectDeclaration")
private val JMIX_KOTLIN_CONTROLLER_ANNOTATION =
    Regex("""@(?:[\w.]+\.)?(?:ViewController|UiController|FragmentDescriptor)\b""")
private val JMIX_KOTLIN_HANDLER_ANNOTATION =
    Regex(
        """@(?:[\w.]+\.)?(Subscribe|Install|Supply)\b(?:\s*\(([\s\S]*?)\))?""",
    )

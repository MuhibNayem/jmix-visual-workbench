package org.jmixworkbench.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

/**
 * Validates the Spring application/entity-event contracts used by Jmix.
 *
 * Java type references already provide the platform's completion, navigation
 * and safe class refactoring. This inspection adds the Jmix-specific semantic
 * layer: Spring-bean ownership, listener arity, entity generic binding,
 * correct pre-save listener choice and safe after-commit data access.
 */
class JmixJavaEventListenerInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                val listener = method.annotations.firstNotNullOfOrNull {
                    it.jmixEventListenerKind()
                } ?: return
                inspectJavaEventListener(method, listener, holder)
            }
        }
}

class JmixKotlinEventListenerInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val function = element as? PsiNamedElement ?: return
                if (function.javaClass.simpleName != "KtNamedFunction") return
                val header = function.text.jmixKotlinFunctionHeader()
                val listener = header.jmixKotlinEventListenerKind() ?: return
                inspectKotlinEventListener(
                    function,
                    header,
                    listener,
                    holder,
                )
            }
        }
}

private fun inspectJavaEventListener(
    method: PsiMethod,
    listener: JmixEventListenerKind,
    holder: ProblemsHolder,
) {
    if (!method.isIndexedJmixSpringBeanMember()) {
        holder.registerProblem(
            method.nameIdentifier ?: method,
            "Jmix event listener must belong to a Spring bean",
        )
    }
    if (method.hasModifierProperty(PsiModifier.STATIC)) {
        holder.registerProblem(
            method.nameIdentifier ?: method,
            "Jmix event listener must be an instance method",
        )
    }
    if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
        holder.registerProblem(
            method.nameIdentifier ?: method,
            "Jmix event listener must have an implementation",
        )
    }

    val parameters = method.parameterList.parameters
    if (parameters.size > 1) {
        holder.registerProblem(
            method.parameterList,
            "Spring event listener must declare at most one event parameter",
        )
        return
    }
    if (parameters.isEmpty()) {
        if (listener.annotation?.hasDeclaredEventClasses() != true) {
            holder.registerProblem(
                method.nameIdentifier ?: method,
                "Parameterless Spring event listener must declare event classes in the annotation",
            )
        }
        return
    }

    val event = parameters.single().type.jmixEntityEventType() ?: return
    val parameter = parameters.single()
    when {
        event.entityType == null ->
            holder.registerProblem(
                parameter,
                "${event.name} must declare an exact Jmix entity generic type",
            )

        event.entityType is PsiWildcardType ->
            holder.registerProblem(
                parameter,
                "${event.name} must not use a wildcard entity generic",
            )

        event.entityClass?.isJmixEntity() != true ->
            holder.registerProblem(
                parameter,
                "${event.name} generic type must resolve to a Jmix entity",
            )
    }

    inspectEntityEventTransactionSafety(
        eventName = event.name,
        listener = listener,
        methodText = method.text,
        hasRequiresNew = method.annotations.any(PsiAnnotation::isRequiresNewTransaction),
        problemElement = listener.annotation ?: method,
        holder = holder,
    )
}

private fun inspectKotlinEventListener(
    function: PsiNamedElement,
    header: String,
    listener: JmixEventListenerKind,
    holder: ProblemsHolder,
) {
    if (!function.isIndexedJmixSpringBeanMember()) {
        holder.registerProblem(
            function,
            "Jmix event listener must belong to a Spring bean",
        )
    }
    if (KOTLIN_EVENT_STATIC_MODIFIER.containsMatchIn(header)) {
        holder.registerProblem(
            function,
            "Jmix event listener must be an instance method",
        )
    }
    val parameters = PsiTreeUtil.findChildrenOfType(
        function,
        PsiNamedElement::class.java,
    ).asSequence()
        .filter { it.javaClass.simpleName == "KtParameter" }
        .filter { it.nearestKotlinNamedFunction() === function }
        .toList()
    if (parameters.size > 1) {
        holder.registerProblem(
            function,
            "Spring event listener must declare at most one event parameter",
        )
        return
    }
    if (parameters.isEmpty()) {
        if (!listener.annotationText.hasKotlinDeclaredEventClasses()) {
            holder.registerProblem(
                function,
                "Parameterless Spring event listener must declare event classes in the annotation",
            )
        }
        return
    }

    val parameterType = parameters.single().text
        .substringAfter(':', "")
        .substringBefore('=')
        .trim()
    val rawEventName = KOTLIN_RAW_ENTITY_EVENT_TYPE
        .find(parameterType)
        ?.groupValues
        ?.get(1)
        ?: return
    val eventMatch = KOTLIN_ENTITY_EVENT_TYPE.find(parameterType)
    if (eventMatch == null) {
        holder.registerProblem(
            parameters.single(),
            "$rawEventName must declare an exact Jmix entity generic type",
        )
        return
    }
    val eventName = eventMatch.groupValues[1]
    val entityType = eventMatch.groupValues[2].trim()
    val entity = resolveJmixEntityClasses(function, entityType)
    when {
        entityType.isBlank() ->
            holder.registerProblem(
                parameters.single(),
                "$eventName must declare an exact Jmix entity generic type",
            )

        entityType.startsWith("*") ||
            entityType.startsWith("out ") ||
            entityType.startsWith("in ") ->
            holder.registerProblem(
                parameters.single(),
                "$eventName must not use a wildcard entity generic",
            )

        entity.size != 1 ->
            holder.registerProblem(
                parameters.single(),
                "$eventName generic type must resolve to one Jmix entity",
            )
    }

    inspectEntityEventTransactionSafety(
        eventName = eventName,
        listener = listener,
        methodText = function.text,
        hasRequiresNew =
            KOTLIN_REQUIRES_NEW_TRANSACTION.containsMatchIn(header),
        problemElement = function,
        holder = holder,
    )
}

private fun inspectEntityEventTransactionSafety(
    eventName: String,
    listener: JmixEventListenerKind,
    methodText: String,
    hasRequiresNew: Boolean,
    problemElement: PsiElement,
    holder: ProblemsHolder,
) {
    if (eventName in JMIX_PRE_STORE_ENTITY_EVENTS &&
        listener.transactional
    ) {
        holder.registerProblem(
            problemElement,
            "$eventName must use @EventListener so entity state is handled in the datastore transaction",
        )
        return
    }
    if (eventName == "EntityChangedEvent" &&
        listener.transactional &&
        listener.afterCommit &&
        JMIX_EVENT_DATA_ACCESS.containsMatchIn(methodText) &&
        !hasRequiresNew
    ) {
        holder.registerProblem(
            problemElement,
            "After-commit EntityChangedEvent data access requires a new transaction",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
        )
    }
}

private fun PsiMethod.isIndexedJmixSpringBeanMember(): Boolean {
    val owner = containingClass ?: return false
    val manager = manager
    return jmixSpringBeanDeclarations(this).any { bean ->
        manager.areElementsEquivalent(bean.classElement, owner) ||
            bean.methods.any { declaration ->
                val method = declaration.element as? PsiMethod
                method != null && manager.areElementsEquivalent(method, this)
            }
    }
}

private fun PsiNamedElement.isIndexedJmixSpringBeanMember(): Boolean {
    val manager = manager
    val owner = generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }
    return jmixSpringBeanDeclarations(this).any { bean ->
        owner != null && manager.areElementsEquivalent(bean.classElement, owner) ||
            bean.methods.any { declaration ->
                manager.areElementsEquivalent(declaration.element, this)
            }
    }
}

private fun PsiAnnotation.jmixEventListenerKind(): JmixEventListenerKind? {
    val shortName = qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.referenceName
        ?: return null
    return when (shortName) {
        "EventListener" ->
            JmixEventListenerKind(
                transactional = false,
                afterCommit = false,
                annotation = this,
                annotationText = text,
            )

        "TransactionalEventListener" ->
            JmixEventListenerKind(
                transactional = true,
                afterCommit = !text.contains("BEFORE_COMMIT") &&
                    !text.contains("AFTER_ROLLBACK") &&
                    !text.contains("AFTER_COMPLETION"),
                annotation = this,
                annotationText = text,
            )

        else -> null
    }
}

private fun String.jmixKotlinEventListenerKind(): JmixEventListenerKind? {
    val match = KOTLIN_EVENT_LISTENER_ANNOTATION.find(this) ?: return null
    val name = match.groupValues[1]
    val annotationText = match.value
    return JmixEventListenerKind(
        transactional = name == "TransactionalEventListener",
        afterCommit = name == "TransactionalEventListener" &&
            !annotationText.contains("BEFORE_COMMIT") &&
            !annotationText.contains("AFTER_ROLLBACK") &&
            !annotationText.contains("AFTER_COMPLETION"),
        annotation = null,
        annotationText = annotationText,
    )
}

private fun PsiAnnotation.hasDeclaredEventClasses(): Boolean =
    findDeclaredAttributeValue("classes") != null ||
        findDeclaredAttributeValue("value") != null

private fun String.hasKotlinDeclaredEventClasses(): Boolean {
    val arguments = substringAfter('(', "").substringBeforeLast(')', "")
    return arguments.isNotBlank()
}

private fun PsiAnnotation.isRequiresNewTransaction(): Boolean {
    val shortName = qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.referenceName
    return shortName == "Transactional" && text.contains("REQUIRES_NEW")
}

private data class JavaEntityEventType(
    val name: String,
    val entityType: PsiType?,
    val entityClass: PsiClass?,
)

private fun PsiType.jmixEntityEventType(): JavaEntityEventType? {
    val classType = this as? PsiClassType ?: return null
    val eventClass = classType.resolve()
    val eventName = eventClass?.name
        ?: classType.rawType().canonicalText.substringAfterLast('.')
    if (eventName !in JMIX_ENTITY_EVENTS) return null
    val entityType = classType.parameters.singleOrNull()
    val entityClass = entityType?.let(PsiUtil::resolveClassInClassTypeOnly)
    return JavaEntityEventType(eventName, entityType, entityClass)
}

private fun String.jmixKotlinFunctionHeader(): String = substringBefore('{')

private fun PsiElement.nearestKotlinNamedFunction(): PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull { it.javaClass.simpleName == "KtNamedFunction" }

private data class JmixEventListenerKind(
    val transactional: Boolean,
    val afterCommit: Boolean,
    val annotation: PsiAnnotation?,
    val annotationText: String,
)

private val JMIX_ENTITY_EVENTS = setOf(
    "EntityChangedEvent",
    "EntitySavingEvent",
    "EntityLoadingEvent",
)
private val JMIX_PRE_STORE_ENTITY_EVENTS = setOf(
    "EntitySavingEvent",
    "EntityLoadingEvent",
)
private val JMIX_EVENT_DATA_ACCESS = Regex(
    """\b(?:dataManager|unconstrainedDataManager|[A-Za-z_]\w*Repository)\s*\.""" +
        """\s*(?:load|save|remove|delete|find|count|create)\b""",
    RegexOption.IGNORE_CASE,
)
private val KOTLIN_EVENT_LISTENER_ANNOTATION = Regex(
    """@(?:[\w.]+\.)?(EventListener|TransactionalEventListener)""" +
        """(?:\s*\([^)]*\))?""",
)
private val KOTLIN_ENTITY_EVENT_TYPE = Regex(
    """(?:[\w.]+\.)?(EntityChangedEvent|EntitySavingEvent|EntityLoadingEvent)""" +
        """\s*<\s*([^>]+)\s*>""",
)
private val KOTLIN_RAW_ENTITY_EVENT_TYPE = Regex(
    """(?:[\w.]+\.)?(EntityChangedEvent|EntitySavingEvent|EntityLoadingEvent)\b""",
)
private val KOTLIN_EVENT_STATIC_MODIFIER =
    Regex("""\b(?:@JvmStatic|static)\b""")
private val KOTLIN_REQUIRES_NEW_TRANSACTION = Regex(
    """@(?:[\w.]+\.)?Transactional\s*\([^)]*REQUIRES_NEW[^)]*\)""",
)

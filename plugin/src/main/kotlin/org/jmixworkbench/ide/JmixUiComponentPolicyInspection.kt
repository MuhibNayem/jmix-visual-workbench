package org.jmixworkbench.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * Validates the selection and collection invariants that Jmix evaluates only
 * when resource roles are loaded. Catching them in the editor prevents a role
 * from silently addressing the wrong view or applying no constraint at all.
 */
class JmixJavaUiComponentPolicyInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor = object : JavaElementVisitor() {
        override fun visitAnnotation(annotation: PsiAnnotation) {
            if (annotation.shortName() != "UiComponentPolicy") return
            validateJavaUiComponentPolicy(annotation, holder)
        }
    }
}

class JmixKotlinUiComponentPolicyInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element.javaClass.simpleName != "KtAnnotationEntry") return
            if (KOTLIN_UI_COMPONENT_POLICY_NAME.find(element.text) == null) return
            validateKotlinUiComponentPolicy(element, holder)
        }
    }
}

private fun validateJavaUiComponentPolicy(
    annotation: PsiAnnotation,
    holder: ProblemsHolder,
) {
    val viewIdValue = annotation.findDeclaredAttributeValue("viewId")
        ?.let {
            JavaPsiFacade.getInstance(annotation.project)
                .constantEvaluationHelper
                .computeConstantExpression(it)
        } as? String
    val hasViewId = !viewIdValue.isNullOrBlank()
    val viewClassValue = annotation.findDeclaredAttributeValue("viewClass")
    val hasViewClass = viewClassValue != null &&
        viewClassValue.text !in JMIX_EMPTY_POLICY_VIEW_CLASSES
    val anchor = annotation.nameReferenceElement ?: annotation

    when {
        hasViewId && hasViewClass -> holder.registerProblem(
            anchor,
            "@UiComponentPolicy must select exactly one target: viewClass or viewId",
        )

        !hasViewId && !hasViewClass -> holder.registerProblem(
            anchor,
            "@UiComponentPolicy must select a target using viewClass or viewId",
        )
    }

    val componentValue = annotation.findDeclaredAttributeValue("componentIds")
    val componentLiterals = when (componentValue) {
        is PsiLiteralExpression -> listOf(componentValue)
        null -> emptyList()
        else -> PsiTreeUtil.findChildrenOfType(
            componentValue,
            PsiLiteralExpression::class.java,
        ).toList()
    }
        .mapNotNull { literal ->
            (literal.value as? String)?.let { value -> literal to value }
        }
    if (componentLiterals.isEmpty() || componentLiterals.all { it.second.isBlank() }) {
        holder.registerProblem(
            anchor,
            "@UiComponentPolicy must contain at least one non-empty component ID",
        )
    }
    componentLiterals
        .filter { it.second.isBlank() }
        .forEach { (literal, _) ->
            holder.registerProblem(literal, "UI component policy ID must not be empty")
        }
    componentLiterals
        .groupBy(Pair<PsiLiteralExpression, String>::second)
        .filterKeys(String::isNotBlank)
        .values
        .filter { it.size > 1 }
        .forEach { duplicates ->
            duplicates.drop(1).forEach { (literal, value) ->
                holder.registerProblem(
                    literal,
                    "Duplicate UI component policy ID '$value'",
                )
            }
        }
}

private fun validateKotlinUiComponentPolicy(
    annotation: PsiElement,
    holder: ProblemsHolder,
) {
    val text = annotation.text
    val viewId = KOTLIN_POLICY_VIEW_ID.find(text)?.groupValues?.get(1)
    val hasViewId = !viewId.isNullOrBlank()
    val hasViewClass = KOTLIN_POLICY_VIEW_CLASS.containsMatchIn(text) &&
        KOTLIN_POLICY_VIEW_CLASS.find(text)?.groupValues?.get(1) !in
        JMIX_EMPTY_KOTLIN_POLICY_VIEW_CLASSES

    when {
        hasViewId && hasViewClass -> holder.registerProblem(
            annotation,
            "@UiComponentPolicy must select exactly one target: viewClass or viewId",
        )

        !hasViewId && !hasViewClass -> holder.registerProblem(
            annotation,
            "@UiComponentPolicy must select a target using viewClass or viewId",
        )
    }

    val componentArgument = KOTLIN_POLICY_COMPONENT_IDS.find(text)
        ?.groupValues
        ?.get(1)
        .orEmpty()
    val componentIds = KOTLIN_STRING.findAll(componentArgument)
        .map { it.groupValues[1] }
        .toList()
    if (componentIds.isEmpty() || componentIds.all(String::isBlank)) {
        holder.registerProblem(
            annotation,
            "@UiComponentPolicy must contain at least one non-empty component ID",
        )
    }
    if (componentIds.any(String::isBlank)) {
        holder.registerProblem(annotation, "UI component policy ID must not be empty")
    }
    componentIds.groupingBy(String::toString)
        .eachCount()
        .filter { (id, count) -> id.isNotBlank() && count > 1 }
        .keys
        .forEach { duplicate ->
            holder.registerProblem(
                annotation,
                "Duplicate UI component policy ID '$duplicate'",
            )
        }
}

private fun PsiAnnotation.shortName(): String? =
    qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.referenceName

private val KOTLIN_UI_COMPONENT_POLICY_NAME =
    Regex("""@(?:[\w.]+\.)?UiComponentPolicy\b""")
private val KOTLIN_POLICY_VIEW_ID =
    Regex("""\bviewId\s*=\s*"([^"$]*)"""")
private val KOTLIN_POLICY_VIEW_CLASS =
    Regex("""\bviewClass\s*=\s*([A-Za-z_$][\w$.]*)::class""")
private val KOTLIN_POLICY_COMPONENT_IDS = Regex(
    """\bcomponentIds\s*=\s*(\[[\s\S]*?]|\barrayOf\s*\([\s\S]*?\)|"[^"]*")""",
)
private val KOTLIN_STRING = Regex(""""([^"$]*)"""")
private val JMIX_EMPTY_POLICY_VIEW_CLASSES = setOf(
    "void.class",
    "Void.class",
    "Object.class",
)
private val JMIX_EMPTY_KOTLIN_POLICY_VIEW_CLASSES = setOf(
    "Unit",
    "Nothing",
    "Any",
)

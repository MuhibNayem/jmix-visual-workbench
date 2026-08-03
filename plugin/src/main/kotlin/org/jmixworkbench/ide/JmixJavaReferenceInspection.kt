package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReference

/**
 * Native, on-the-fly validation for Jmix controller, localization and
 * security references.
 */
class JmixJavaReferenceInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                expression.references.asSequence()
                    .filter(PsiReference::isJmixJavaReference)
                    .filterNot(PsiReference::resolvesToAnyJmixTarget)
                    .forEach { reference ->
                        val unresolved = reference.rangeInElement
                            .substring(reference.element.text)
                        val closest = reference.completionCandidates()
                            .map { candidate -> candidate to jmixEditDistance(unresolved, candidate) }
                            .filter { (_, distance) -> distance <= JMIX_REFERENCE_FIX_DISTANCE }
                            .minWithOrNull(
                                compareBy<Pair<String, Int>> { it.second }
                                    .thenBy { it.first },
                            )
                            ?.first
                        holder.registerProblem(
                            expression,
                            reference.rangeInElement,
                            "Unresolved Jmix reference '$unresolved'",
                            *closest?.let {
                                arrayOf(ReplaceJmixJavaReferenceQuickFix(it))
                            }.orEmpty(),
                        )
                    }
            }
        }
}

private fun PsiReference.completionCandidates(): Sequence<String> =
    (when (this) {
        is JmixJavaFlowUiIdReference ->
            candidateAttributes().asSequence().mapNotNull { it.value }

        is JmixJavaUiComponentPolicyReference ->
            candidateSegments().asSequence().mapNotNull { it.attribute.value }

        is JmixViewDescriptorReference ->
            findAllJmixDescriptorFiles(element).asSequence().map { it.name }

        is JmixJavaViewIdReference ->
            candidateDeclarations().asSequence().map { it.id }

        is JmixJavaMenuIdReference ->
            candidateDeclarations().asSequence().map { it.id }

        is JmixJavaEntityNameReference -> candidateNames()

        is JmixJavaEntityPropertyReference ->
            candidateProperties().asSequence().map { it.name }

        is JmixJavaMessageReference ->
            candidateDeclarations().asSequence()
                .flatMap { it.lookupKeys.asSequence() }

        is JmixJavaSpecificPolicyReference ->
            candidateDeclarations().asSequence().map { it.resource }

        else -> emptySequence()
    } + variants.asSequence()
        .filterIsInstance<LookupElement>()
        .map(LookupElement::getLookupString))
        .filter(String::isNotBlank)
        .distinct()

private fun PsiReference.isJmixJavaReference(): Boolean =
        this is JmixViewDescriptorReference ||
        this is JmixJavaFlowUiIdReference ||
        this is JmixJavaUiComponentPolicyReference ||
        this is JmixJavaViewIdReference ||
        this is JmixJavaMenuIdReference ||
        this is JmixJavaEntityNameReference ||
        this is JmixJavaEntityPropertyReference ||
        this is JmixJavaRepositoryParameterReference ||
        this is JmixJavaMessageReference ||
        this is JmixJavaSpecificPolicyReference ||
        this is JmixJavaSpringBeanDeclarationReference

private class ReplaceJmixJavaReferenceQuickFix(
    private val replacement: String,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with existing Jmix reference"

    override fun getName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement as? PsiLiteralExpression ?: return
        val reference = literal.references.firstOrNull { candidate ->
            candidate.isJmixJavaReference() &&
                candidate.rangeInElement == descriptor.textRangeInElement
        } ?: return
        reference.handleElementRename(replacement)
    }
}

internal fun jmixEditDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftChar ->
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightChar ->
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
            )
        }
        previous = current
    }
    return previous[right.length]
}

private const val JMIX_REFERENCE_FIX_DISTANCE = 3

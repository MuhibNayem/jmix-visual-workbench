package org.jmixworkbench.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiReference

class JmixKotlinReferenceInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val host = element as? PsiLanguageInjectionHost ?: return
                host.references.asSequence()
                    .filter { it is JmixKotlinDescriptorReference || it is JmixKotlinFlowUiIdReference }
                    .filter { it.resolve() == null }
                    .forEach { reference ->
                        val unresolved = reference.rangeInElement.substring(host.text)
                        val closest = reference.kotlinCompletionCandidates()
                            .map { candidate -> candidate to jmixEditDistance(unresolved, candidate) }
                            .filter { (_, distance) -> distance <= JMIX_KOTLIN_FIX_DISTANCE }
                            .minWithOrNull(
                                compareBy<Pair<String, Int>> { it.second }
                                    .thenBy { it.first },
                            )
                            ?.first
                        holder.registerProblem(
                            host,
                            reference.rangeInElement,
                            "Unresolved Jmix reference '$unresolved'",
                            *closest?.let {
                                arrayOf(ReplaceJmixKotlinReferenceQuickFix(it))
                            }.orEmpty(),
                        )
                    }
            }
        }
}

private fun PsiReference.kotlinCompletionCandidates(): Sequence<String> =
    when (this) {
        is JmixKotlinFlowUiIdReference ->
            candidateAttributes().asSequence().mapNotNull { it.value }

        is JmixKotlinDescriptorReference ->
            findAllJmixDescriptorFiles(element).asSequence().map { it.name }

        else -> emptySequence()
    }.filter(String::isNotBlank).distinct()

private class ReplaceJmixKotlinReferenceQuickFix(
    private val replacement: String,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with existing Jmix reference"

    override fun getName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val host = descriptor.psiElement as? PsiLanguageInjectionHost ?: return
        val reference = host.references.firstOrNull { candidate ->
            (candidate is JmixKotlinDescriptorReference || candidate is JmixKotlinFlowUiIdReference) &&
                candidate.rangeInElement == descriptor.textRangeInElement
        } ?: return
        reference.handleElementRename(replacement)
    }
}

private const val JMIX_KOTLIN_FIX_DISTANCE = 3

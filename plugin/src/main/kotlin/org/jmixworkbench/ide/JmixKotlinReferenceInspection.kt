package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
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
                    .filter(PsiReference::isJmixKotlinReference)
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
    (when (this) {
        is JmixKotlinFlowUiIdReference ->
            candidateAttributes().asSequence().mapNotNull { it.value }

        is JmixKotlinDescriptorReference ->
            findAllJmixDescriptorFiles(element).asSequence().map { it.name }

        is JmixKotlinViewIdReference ->
            candidateDeclarations().asSequence().map { it.id }

        is JmixKotlinMenuIdReference ->
            candidateDeclarations().asSequence().map { it.id }

        is JmixKotlinEntityNameReference -> candidateNames()

        is JmixKotlinEntityPropertyReference ->
            candidateProperties().asSequence().map { it.name }

        is JmixKotlinMessageReference ->
            candidateDeclarations().asSequence()
                .flatMap { it.lookupKeys.asSequence() }

        is JmixKotlinSpecificPolicyReference ->
            candidateDeclarations().asSequence().map { it.resource }

        else -> emptySequence()
    } + variants.asSequence()
        .filterIsInstance<LookupElement>()
        .map(LookupElement::getLookupString))
        .filter(String::isNotBlank)
        .distinct()

private fun PsiReference.isJmixKotlinReference(): Boolean =
    this is JmixKotlinDescriptorReference ||
        this is JmixKotlinFlowUiIdReference ||
        this is JmixKotlinViewIdReference ||
        this is JmixKotlinMenuIdReference ||
        this is JmixKotlinEntityNameReference ||
        this is JmixKotlinEntityPropertyReference ||
        this is JmixKotlinMessageReference ||
        this is JmixKotlinSpecificPolicyReference

private class ReplaceJmixKotlinReferenceQuickFix(
    private val replacement: String,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with existing Jmix reference"

    override fun getName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val host = descriptor.psiElement as? PsiLanguageInjectionHost ?: return
        val reference = host.references.firstOrNull { candidate ->
            candidate.isJmixKotlinReference() &&
                candidate.rangeInElement == descriptor.textRangeInElement
        } ?: return
        reference.handleElementRename(replacement)
    }
}

private const val JMIX_KOTLIN_FIX_DISTANCE = 3

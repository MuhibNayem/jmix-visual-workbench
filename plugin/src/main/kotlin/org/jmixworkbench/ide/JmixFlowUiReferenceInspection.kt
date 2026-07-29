package org.jmixworkbench.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/**
 * On-the-fly FlowUI reference validation with a conservative nearest-ID fix.
 */
class JmixFlowUiReferenceInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : XmlElementVisitor() {
            override fun visitXmlAttributeValue(value: XmlAttributeValue) {
                if (!(value.containingFile as? com.intellij.psi.xml.XmlFile).let {
                        it?.isJmixFlowUiDescriptor() == true
                    }
                ) return
                value.references.filterIsInstance<JmixFlowUiIdReference>().forEach { reference ->
                    if (reference.multiResolve(false).isNotEmpty()) return@forEach
                    val unresolved = reference.value.orEmpty()
                    val closest = reference.candidateAttributes()
                        .mapNotNull { it.value }
                        .filter(String::isNotBlank)
                        .distinct()
                        .map { candidate -> candidate to editDistance(unresolved, candidate) }
                        .filter { (_, distance) -> distance <= MAX_FIX_DISTANCE }
                        .minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
                        ?.first
                    holder.registerProblem(
                        value,
                        reference.rangeInElement,
                        "Unresolved Jmix FlowUI reference '$unresolved'",
                        *closest?.let { arrayOf(ReplaceJmixReferenceQuickFix(it)) }.orEmpty(),
                    )
                }
            }
        }

    private fun editDistance(left: String, right: String): Int {
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

    private companion object {
        const val MAX_FIX_DISTANCE = 3
    }
}

private class ReplaceJmixReferenceQuickFix(
    private val replacement: String,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with existing Jmix ID"

    override fun getName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val value = descriptor.psiElement as? XmlAttributeValue ?: return
        val range = descriptor.textRangeInElement
        val raw = value.value
        val start = range.startOffset - 1
        val end = range.endOffset - 1
        if (start < 0 || start > end || end > raw.length) return
        val attribute = value.parent as? XmlAttribute ?: return
        attribute.setValue(raw.replaceRange(start, end, replacement))
    }
}

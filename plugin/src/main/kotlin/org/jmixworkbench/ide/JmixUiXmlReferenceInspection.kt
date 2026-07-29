package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Validates the connected Jmix UI surface: menu-to-view routes, localization
 * keys and duplicate menu identifiers.
 */
class JmixUiXmlReferenceInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : XmlElementVisitor() {
            override fun visitXmlAttributeValue(value: XmlAttributeValue) {
                value.references.asSequence()
                    .filter {
                        it is JmixXmlViewIdReference ||
                            it is JmixXmlMessageReference
                    }
                    .filter { it.resolve() == null }
                    .forEach { reference ->
                        val unresolved = reference.rangeInElement.substring(value.text)
                        val closest = reference.xmlUiCandidates()
                            .map { candidate ->
                                candidate to jmixEditDistance(unresolved, candidate)
                            }
                            .filter { (_, distance) -> distance <= JMIX_XML_UI_FIX_DISTANCE }
                            .minWithOrNull(
                                compareBy<Pair<String, Int>> { it.second }
                                    .thenBy { it.first },
                            )
                            ?.first
                        val kind = if (reference is JmixXmlMessageReference) {
                            "message"
                        } else {
                            "view"
                        }
                        holder.registerProblem(
                            value,
                            reference.rangeInElement,
                            "Unresolved Jmix $kind reference '$unresolved'",
                            *closest?.let {
                                arrayOf(ReplaceJmixXmlUiReferenceQuickFix(it))
                            }.orEmpty(),
                        )
                    }

                val attribute = value.parent as? XmlAttribute ?: return
                val file = value.containingFile as? XmlFile ?: return
                val tag = attribute.parent
                if (!file.isJmixMenuDescriptor() ||
                    attribute.localName != "id" ||
                    tag.localName !in setOf("menu", "item")
                ) {
                    return
                }
                val id = attribute.value?.takeIf(String::isNotBlank) ?: return
                val duplicates = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                    .asSequence()
                    .filter { it.localName == "menu" || it.localName == "item" }
                    .mapNotNull { it.getAttribute("id") }
                    .filter { it.value == id }
                    .toList()
                if (duplicates.size > 1) {
                    holder.registerProblem(
                        value,
                        "Duplicate Jmix menu id '$id' in ${file.name}",
                    )
                }
            }
        }
}

private fun PsiReference.xmlUiCandidates(): Sequence<String> {
    val explicit = when (this) {
        is JmixXmlViewIdReference ->
            candidateDeclarations().asSequence().map { it.id }

        is JmixXmlMessageReference ->
            candidateDeclarations().asSequence()
                .flatMap { it.lookupKeys.asSequence() }

        else -> emptySequence()
    }
    val lookup = variants.asSequence()
        .filterIsInstance<LookupElement>()
        .map(LookupElement::getLookupString)
    return (explicit + lookup).filter(String::isNotBlank).distinct()
}

private class ReplaceJmixXmlUiReferenceQuickFix(
    private val replacement: String,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with existing Jmix symbol"

    override fun getName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val value = descriptor.psiElement as? XmlAttributeValue ?: return
        val reference = value.references.firstOrNull { candidate ->
            (candidate is JmixXmlViewIdReference ||
                candidate is JmixXmlMessageReference) &&
                candidate.rangeInElement == descriptor.textRangeInElement
        } ?: return
        reference.handleElementRename(replacement)
    }
}

private const val JMIX_XML_UI_FIX_DISTANCE = 4

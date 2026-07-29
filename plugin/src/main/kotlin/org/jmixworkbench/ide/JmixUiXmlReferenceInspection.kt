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
                            it is JmixXmlMessageReference ||
                            it is JmixXmlSpringBeanReference ||
                            it is JmixXmlSpringBeanMethodReference
                    }
                    .forEach { reference ->
                        if (reference.registerSpringBeanProblem(holder, value)) {
                            return@forEach
                        }
                        if (reference.resolve() != null) return@forEach
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
                        } else if (reference is JmixXmlSpringBeanReference) {
                            "Spring bean"
                        } else if (reference is JmixXmlSpringBeanMethodReference) {
                            "Spring bean method"
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
                if (!file.isJmixMenuDescriptor()) return

                if (tag.localName == "item" &&
                    attribute.localName in setOf("bean", "beanMethod")
                ) {
                    val bean = tag.getAttributeValue("bean").orEmpty()
                    val method = tag.getAttributeValue("beanMethod").orEmpty()
                    if (bean.isBlank() || method.isBlank()) {
                        holder.registerProblem(
                            value,
                            "Jmix menu item requires both bean and beanMethod",
                        )
                    }
                }

                if (attribute.localName != "id" ||
                    tag.localName !in setOf("menu", "item")
                ) return
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

        is JmixXmlSpringBeanReference ->
            candidateDeclarations().asSequence().map { it.name }

        is JmixXmlSpringBeanMethodReference ->
            candidateMethods().asSequence()
                .filter(JmixSpringBeanMethodDeclaration::isMenuCallable)
                .map { it.name }

        else -> emptySequence()
    }
    val lookup = variants.asSequence()
        .filterIsInstance<LookupElement>()
        .map(LookupElement::getLookupString)
    return (explicit + lookup).filter(String::isNotBlank).distinct()
}

private fun PsiReference.registerSpringBeanProblem(
    holder: ProblemsHolder,
    value: XmlAttributeValue,
): Boolean {
    when (this) {
        is JmixXmlSpringBeanReference -> {
            val matches = multiResolve(false)
            if (matches.size > 1) {
                val name = rangeInElement.substring(value.text)
                holder.registerProblem(
                    value,
                    rangeInElement,
                    "Ambiguous Jmix Spring bean '$name': " +
                        "${matches.size} declarations use this name",
                )
                return true
            }
        }

        is JmixXmlSpringBeanMethodReference -> {
            val beanMatches = candidateBeans()
            if (beanMatches.size > 1) {
                holder.registerProblem(
                    value,
                    rangeInElement,
                    "Cannot resolve Jmix menu method because the Spring bean name is ambiguous",
                )
                return true
            }
            val name = rangeInElement.substring(value.text)
            val methods = candidateMethods().filter { it.name == name }
            if (methods.size > 1) {
                holder.registerProblem(
                    value,
                    rangeInElement,
                    "Ambiguous Jmix menu bean method '$name': overloaded methods are not safe",
                )
                return true
            }
            val invalid = methods.singleOrNull()?.invalidReason
            if (invalid != null) {
                holder.registerProblem(
                    value,
                    rangeInElement,
                    "$invalid for Jmix menu invocation",
                )
                return true
            }
        }
    }
    return false
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
                candidate is JmixXmlMessageReference ||
                candidate is JmixXmlSpringBeanReference ||
                candidate is JmixXmlSpringBeanMethodReference) &&
                candidate.rangeInElement == descriptor.textRangeInElement
        } ?: return
        reference.handleElementRename(replacement)
    }
}

private const val JMIX_XML_UI_FIX_DISTANCE = 4

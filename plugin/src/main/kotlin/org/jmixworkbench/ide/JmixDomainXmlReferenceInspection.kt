package org.jmixworkbench.ide

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Validates entity classes, entity property paths and named fetch plans in
 * FlowUI and shared fetch-plan XML with conservative nearest-symbol fixes.
 */
class JmixDomainXmlReferenceInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val file = tag.containingFile as? XmlFile ?: return
                if (!file.isJmixFlowUiDescriptor() && !file.isJmixFetchPlanDescriptor()) return
                if (tag.localName == "fetchPlan") {
                    inspectFetchPlanDeclaration(tag, file, holder)
                }
                if (tag.localName == "property") {
                    inspectNestedFetchPlanProperty(tag, holder)
                }
                inspectDuplicateProperties(tag, holder)
            }

            override fun visitXmlAttributeValue(value: XmlAttributeValue) {
                val file = value.containingFile as? XmlFile ?: return
                if (!file.isJmixFlowUiDescriptor() && !file.isJmixFetchPlanDescriptor()) return
                value.references.asSequence()
                    .filter(PsiReference::isJmixDomainReference)
                    .filterNot { it is JmixNamedFetchPlanReference && it.isBuiltIn }
                    .forEach { reference ->
                        val resolutions = (reference as? PsiPolyVariantReference)
                            ?.multiResolve(false)
                            .orEmpty()
                        if (resolutions.size == 1) return@forEach
                        val unresolved = reference.rangeInElement.substring(value.text)
                        if (resolutions.size > 1) {
                            holder.registerProblem(
                                value,
                                reference.rangeInElement,
                                reference.jmixDomainAmbiguityMessage(
                                    unresolved,
                                    resolutions.size,
                                ),
                            )
                            return@forEach
                        }
                        val closest = reference.jmixDomainCandidates()
                            .map { candidate ->
                                candidate to jmixEditDistance(unresolved, candidate)
                            }
                            .filter { (_, distance) ->
                                distance <= JMIX_DOMAIN_REFERENCE_FIX_DISTANCE
                            }
                            .minWithOrNull(
                                compareBy<Pair<String, Int>> { it.second }
                                    .thenBy { it.first },
                            )
                            ?.first
                        holder.registerProblem(
                            value,
                            reference.rangeInElement,
                            reference.jmixDomainProblemMessage(unresolved),
                            *closest?.let {
                                arrayOf(ReplaceJmixDomainReferenceQuickFix(it))
                            }.orEmpty(),
                        )
                    }
            }
        }
}

private fun inspectFetchPlanDeclaration(
    tag: XmlTag,
    file: XmlFile,
    holder: ProblemsHolder,
) {
    if (file.isJmixFetchPlanDescriptor() &&
        tag.getAttributeValue("class").isNullOrBlank() &&
        tag.getAttributeValue("entity").isNullOrBlank()
    ) {
        holder.registerProblem(
            tag,
            "Shared Jmix fetch plan must declare its entity using 'class' or 'entity'",
        )
    }
    val nameAttribute = tag.getAttribute("name") ?: return
    val name = nameAttribute.value?.takeIf(String::isNotBlank) ?: return
    val entityClass = entityClassForFetchPlanTag(tag) ?: return
    val duplicates = findJmixFetchPlanDeclarations(tag, entityClass)
        .count { it.value == name }
    if (duplicates > 1) {
        holder.registerProblem(
            nameAttribute.valueElement ?: nameAttribute,
            "Jmix fetch plan '$name' is declared $duplicates times for the same entity",
        )
    }
}

private fun inspectDuplicateProperties(
    owner: XmlTag,
    holder: ProblemsHolder,
) {
    if (owner.localName != "fetchPlan" && owner.localName != "property") return
    owner.subTags.asSequence()
        .filter { it.localName == "property" }
        .mapNotNull { property ->
            property.getAttribute("name")
                ?.takeIf { !it.value.isNullOrBlank() }
        }
        .groupBy { it.value }
        .filterKeys { !it.isNullOrBlank() }
        .values
        .filter { it.size > 1 }
        .forEach { duplicates ->
            duplicates.drop(1).forEach { duplicate ->
                holder.registerProblem(
                    duplicate.valueElement ?: duplicate,
                    "Jmix fetch plan property '${duplicate.value}' is declared more than once at this level",
                )
            }
        }
}

private fun inspectNestedFetchPlanProperty(
    tag: XmlTag,
    holder: ProblemsHolder,
) {
    val hasNestedPlan = tag.getAttribute("fetchPlan") != null ||
        tag.subTags.any { child ->
            child.localName == "property" || child.localName == "fetchPlan"
        }
    if (!hasNestedPlan) return
    val nameValue = tag.getAttribute("name")?.valueElement ?: return
    val name = nameValue.value
    val property = nameValue.references.filterIsInstance<JmixEntityPropertyReference>()
        .firstOrNull()
        ?.candidateProperties()
        ?.firstOrNull { it.name == name }
        ?: return
    val targetClass = entityClassForType(property.type)
    if (targetClass?.isJmixEntity() == true) return
    holder.registerProblem(
        nameValue,
        "Jmix fetch plan property '$name' uses a nested fetch plan but is not an entity reference",
    )
}

private fun PsiReference.isJmixDomainReference(): Boolean =
    this is JmixEntityClassReference ||
        this is JmixEntityPropertyReference ||
        this is JmixNamedFetchPlanReference

private fun PsiReference.jmixDomainCandidates(): Sequence<String> =
    when (this) {
        is JmixEntityClassReference -> candidateNames()
        is JmixEntityPropertyReference ->
            candidateProperties().asSequence().map(JmixEntityProperty::name)
        is JmixNamedFetchPlanReference -> candidateNames()
        else -> emptySequence()
    }.filter(String::isNotBlank).distinct()

private fun PsiReference.jmixDomainProblemMessage(unresolved: String): String =
    when (this) {
        is JmixEntityClassReference -> "Unresolved Jmix entity '$unresolved'"
        is JmixEntityPropertyReference -> "Unresolved Jmix entity property '$unresolved'"
        is JmixNamedFetchPlanReference -> "Unresolved Jmix fetch plan '$unresolved'"
        else -> "Unresolved Jmix domain reference '$unresolved'"
    }

private fun PsiReference.jmixDomainAmbiguityMessage(
    unresolved: String,
    targetCount: Int,
): String =
    when (this) {
        is JmixEntityClassReference ->
            "Ambiguous Jmix entity '$unresolved' resolves to $targetCount declarations"
        is JmixNamedFetchPlanReference ->
            "Ambiguous Jmix fetch plan '$unresolved' resolves to $targetCount declarations"
        else ->
            "Ambiguous Jmix domain reference '$unresolved' resolves to $targetCount declarations"
    }

private class ReplaceJmixDomainReferenceQuickFix(
    private val replacement: String,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with existing Jmix domain symbol"

    override fun getName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val value = descriptor.psiElement as? XmlAttributeValue ?: return
        val reference = value.references.firstOrNull { candidate ->
            candidate.isJmixDomainReference() &&
                candidate.rangeInElement == descriptor.textRangeInElement
        } ?: return
        reference.handleElementRename(replacement)
    }
}

private const val JMIX_DOMAIN_REFERENCE_FIX_DISTANCE = 4

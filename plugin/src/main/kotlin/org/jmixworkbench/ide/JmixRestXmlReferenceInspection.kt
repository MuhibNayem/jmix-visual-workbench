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
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Fail-closed validation for XML-exposed Jmix REST services.
 */
class JmixRestXmlReferenceInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : XmlElementVisitor() {
            override fun visitXmlAttributeValue(value: XmlAttributeValue) {
                val file = value.containingFile as? XmlFile ?: return
                if (!file.isJmixRestServicesDescriptor()) return
                val reference = value.references.firstOrNull { candidate ->
                    candidate is JmixRestServiceBeanReference ||
                        candidate is JmixRestServiceMethodReference ||
                        candidate is JmixRestServiceParameterReference ||
                        candidate is JmixRestServiceParameterTypeReference
                } ?: return

                when (reference) {
                    is JmixRestServiceBeanReference ->
                        inspectBean(reference, value, holder)

                    is JmixRestServiceMethodReference ->
                        inspectMethod(reference, value, holder)

                    is JmixRestServiceParameterReference ->
                        inspectParameter(reference, value, holder)

                    is JmixRestServiceParameterTypeReference ->
                        inspectParameterType(reference, value, holder)
                }
            }

            override fun visitXmlTag(tag: XmlTag) {
                val file = tag.containingFile as? XmlFile ?: return
                if (!file.isJmixRestServicesDescriptor()) return
                when (tag.localName) {
                    "services" -> inspectDuplicateServices(tag, holder)
                    "service" -> inspectDuplicateMethods(tag, holder)
                    "method" -> inspectDuplicateParameters(tag, holder)
                }
            }
        }
}

private fun inspectBean(
    reference: JmixRestServiceBeanReference,
    value: XmlAttributeValue,
    holder: ProblemsHolder,
) {
    val beanName = value.value
    val matches = reference.multiResolve(false)
    when {
        beanName.isBlank() ->
            holder.registerProblem(value, "Jmix REST service bean name must not be blank")

        matches.isEmpty() ->
            holder.registerRestReferenceProblem(
                value,
                reference,
                "Unresolved Jmix REST Spring bean '$beanName'",
            )

        matches.size > 1 ->
            holder.registerProblem(
                value,
                reference.rangeInElement,
                "Ambiguous Jmix REST Spring bean '$beanName': " +
                    "${matches.size} declarations use this name",
            )
    }
}

private fun inspectMethod(
    reference: JmixRestServiceMethodReference,
    value: XmlAttributeValue,
    holder: ProblemsHolder,
) {
    val methodName = value.value
    if (methodName.isBlank()) {
        holder.registerProblem(value, "Jmix REST service method name must not be blank")
        return
    }
    val beanMatches = reference.candidateBeans()
    if (beanMatches.size != 1) return

    val sameName = reference.sameNameMethods()
    if (sameName.isEmpty()) {
        holder.registerRestReferenceProblem(
            value,
            reference,
            "Unresolved Jmix REST service method '$methodName'",
        )
        return
    }
    val methodTag = value.parentTagForInspection("method") ?: return
    val parameterCount = methodTag.restParameterSpecs().size
    val sameCount = sameName.filter { it.parameters.size == parameterCount }
    if (sameCount.isEmpty()) {
        val available = sameName.map { it.parameters.size }.distinct().sorted()
        holder.registerProblem(
            value,
            reference.rangeInElement,
            "Jmix REST method '$methodName' declares $parameterCount XML parameters, " +
                "but source overloads require ${available.joinToString(" or ")}",
        )
        return
    }

    val invalid = sameCount.mapNotNull { it.restInvalidReason }.distinct()
    if (invalid.size == sameCount.size) {
        holder.registerProblem(
            value,
            reference.rangeInElement,
            invalid.joinToString("; "),
        )
        return
    }

    val resolved = reference.resolvedMethods()
    when {
        resolved.isEmpty() ->
            holder.registerProblem(
                value,
                reference.rangeInElement,
                "Jmix REST parameter types do not match any '$methodName' overload",
            )

        resolved.size > 1 -> {
            val unspecified = methodTag.restParameterSpecs()
                .withIndex()
                .filter { it.value.type.isBlank() }
                .map { it.index + 1 }
            val detail = if (unspecified.isEmpty()) {
                "configured parameter types are still ambiguous"
            } else {
                "add fully qualified type to parameter " +
                    unspecified.joinToString()
            }
            holder.registerProblem(
                value,
                reference.rangeInElement,
                "Ambiguous Jmix REST service method '$methodName': $detail",
            )
        }
    }
}

private fun inspectParameter(
    reference: JmixRestServiceParameterReference,
    value: XmlAttributeValue,
    holder: ProblemsHolder,
) {
    if (value.value.isBlank()) {
        holder.registerProblem(value, "Jmix REST parameter name must not be blank")
        return
    }
    if (reference.parameterIndex() < 0) {
        holder.registerProblem(value, "Jmix REST parameter must be nested in a method")
    }
}

private fun inspectParameterType(
    reference: JmixRestServiceParameterTypeReference,
    value: XmlAttributeValue,
    holder: ProblemsHolder,
) {
    val configuredType = value.value
    if (configuredType.isBlank()) {
        holder.registerProblem(value, "Jmix REST parameter type must not be blank")
        return
    }
    val parameters = reference.candidateParameters()
    if (parameters.isNotEmpty() &&
        parameters.none(reference::matchesConfiguredType)
    ) {
        val expected = parameters.map { it.canonicalType }
            .distinct()
            .sorted()
            .joinToString(" or ")
        holder.registerProblem(
            value,
            reference.rangeInElement,
            "Jmix REST parameter type '$configuredType' does not match source type $expected",
        )
    }
}

private fun inspectDuplicateServices(
    root: XmlTag,
    holder: ProblemsHolder,
) {
    root.subTags.asSequence()
        .filter { it.localName == "service" }
        .mapNotNull { service ->
            service.getAttribute("name")
                ?.takeIf { it.value.orEmpty().isNotBlank() }
        }
        .groupBy(XmlAttribute::getValue)
        .filterValues { it.size > 1 }
        .forEach { (name, attributes) ->
            attributes.forEach { attribute ->
                attribute.valueElement?.let { value ->
                    holder.registerProblem(
                        value,
                        "Duplicate Jmix REST service '$name' in this descriptor",
                    )
                }
            }
        }
}

private fun inspectDuplicateMethods(
    service: XmlTag,
    holder: ProblemsHolder,
) {
    service.subTags.asSequence()
        .filter { it.localName == "method" }
        .mapNotNull { method ->
            val name = method.getAttributeValue("name")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val signature = method.restParameterSpecs()
                .joinToString(prefix = "$name(", postfix = ")") { spec ->
                    spec.type.ifBlank { "*" }
                }
            signature to method.getAttribute("name")
        }
        .filter { it.second != null }
        .groupBy({ it.first }, { requireNotNull(it.second) })
        .filterValues { it.size > 1 }
        .forEach { (signature, attributes) ->
            attributes.forEach { attribute ->
                attribute.valueElement?.let { value ->
                    holder.registerProblem(
                        value,
                        "Duplicate Jmix REST method mapping '$signature'",
                    )
                }
            }
        }
}

private fun inspectDuplicateParameters(
    method: XmlTag,
    holder: ProblemsHolder,
) {
    method.subTags.asSequence()
        .filter { it.localName == "param" }
        .mapNotNull { parameter ->
            parameter.getAttribute("name")
                ?.takeIf { it.value.orEmpty().isNotBlank() }
        }
        .groupBy(XmlAttribute::getValue)
        .filterValues { it.size > 1 }
        .forEach { (name, attributes) ->
            attributes.forEach { attribute ->
                attribute.valueElement?.let { value ->
                    holder.registerProblem(
                        value,
                        "Duplicate Jmix REST parameter name '$name'",
                    )
                }
            }
        }
}

private fun ProblemsHolder.registerRestReferenceProblem(
    value: XmlAttributeValue,
    reference: PsiReference,
    message: String,
) {
    val unresolved = reference.rangeInElement.substring(value.text)
    val closest = reference.variants.asSequence()
        .filterIsInstance<LookupElement>()
        .map(LookupElement::getLookupString)
        .distinct()
        .map { candidate -> candidate to jmixEditDistance(unresolved, candidate) }
        .filter { (_, distance) -> distance <= JMIX_REST_FIX_DISTANCE }
        .minWithOrNull(
            compareBy<Pair<String, Int>> { it.second }.thenBy { it.first },
        )
        ?.first
    registerProblem(
        value,
        reference.rangeInElement,
        message,
        *closest?.let {
            arrayOf(ReplaceJmixRestReferenceQuickFix(it))
        }.orEmpty(),
    )
}

private class ReplaceJmixRestReferenceQuickFix(
    private val replacement: String,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with existing Jmix REST symbol"

    override fun getName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val value = descriptor.psiElement as? XmlAttributeValue ?: return
        val reference = value.references.firstOrNull { candidate ->
            candidate.rangeInElement == descriptor.textRangeInElement &&
                (candidate is JmixRestServiceBeanReference ||
                    candidate is JmixRestServiceMethodReference ||
                    candidate is JmixRestServiceParameterReference ||
                    candidate is JmixRestServiceParameterTypeReference)
        } ?: return
        reference.handleElementRename(replacement)
    }
}

private fun XmlAttributeValue.parentTagForInspection(localName: String): XmlTag? =
    generateSequence(parent) { it.parent }
        .filterIsInstance<XmlTag>()
        .firstOrNull { it.localName == localName }

private const val JMIX_REST_FIX_DISTANCE = 5

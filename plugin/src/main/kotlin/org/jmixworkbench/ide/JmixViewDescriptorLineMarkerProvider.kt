package org.jmixworkbench.ide

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Adds a native gutter target from a FlowUI controller class to its XML
 * descriptor.
 */
class JmixViewDescriptorLineMarkerProvider : RelatedItemLineMarkerProvider() {
    internal fun collectNavigationMarkersForTests(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        collectNavigationMarkers(element, result)
    }

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        when (element) {
            is PsiClass -> collectControllerMarker(element, result)
            is XmlTag -> collectDescriptorMarker(element, result)
        }
    }

    private fun collectControllerMarker(
        psiClass: PsiClass,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val identifier = psiClass.nameIdentifier ?: return
        val annotation = psiClass.annotations.firstOrNull { candidate ->
            val shortName = candidate.qualifiedName?.substringAfterLast('.')
                ?: candidate.nameReferenceElement?.referenceName
            shortName == "ViewDescriptor"
        } ?: return
        val literal = annotation.descriptorLiteral() ?: return
        val targets = literal.references.asSequence()
            .flatMap { reference ->
                when (reference) {
                    is JmixViewDescriptorReference -> reference.multiResolve(false).asSequence()
                    else -> emptySequence()
                }
            }
            .mapNotNull { it.element }
            .toList()
        if (targets.isEmpty()) return
        result += NavigationGutterIconBuilder
            .create(AllIcons.FileTypes.Xml)
            .setTargets(targets)
            .setTooltipText("Navigate to Jmix FlowUI descriptor")
            .createLineMarkerInfo(identifier)
    }

    private fun collectDescriptorMarker(
        tag: XmlTag,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        val file = tag.containingFile as? XmlFile ?: return
        if (file.rootTag != tag || !file.isJmixFlowUiDescriptor()) return
        val controllers = ReferencesSearch.search(
            file,
            GlobalSearchScope.projectScope(file.project),
        ).findAll().asSequence()
            .filterIsInstance<JmixViewDescriptorReference>()
            .mapNotNull { reference ->
                PsiTreeUtil.getParentOfType(
                    reference.element,
                    PsiClass::class.java,
                    false,
                )
            }
            .distinctBy { it.qualifiedName ?: it.name }
            .toList()
        if (controllers.isEmpty()) return
        result += NavigationGutterIconBuilder
            .create(AllIcons.Nodes.Class)
            .setTargets(controllers)
            .setTooltipText("Navigate to Jmix FlowUI controller")
            .createLineMarkerInfo(tag.firstChild ?: tag)
    }
}

private fun PsiAnnotation.descriptorLiteral(): PsiLiteralExpression? =
    PsiTreeUtil.findChildOfType(parameterList, PsiLiteralExpression::class.java)

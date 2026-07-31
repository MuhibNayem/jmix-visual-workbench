package org.jmixworkbench.ide

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor

/**
 * Adds Spring's derived bean name as a mandatory secondary rename target when
 * its Java or Kotlin component class changes name.
 *
 * This is a processor contribution rather than a text search: `PayrollMenu`
 * and `payrollMenu` are different indexed words, so ordinary Java/Kotlin
 * refactoring cannot discover the menu XML usage by itself.
 */
class JmixSpringBeanRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean =
        implicitBeanForClass(element) != null

    override fun prepareRenaming(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<PsiElement, String>,
    ) {
        val declaration = implicitBeanForClass(element) ?: return
        allRenames[
            JmixSpringBeanElement(
                declaration,
                renameBackingDeclaration = false,
            ),
        ] = declaration.implicitNameKind.beanNameAfterBackingRename(newName)
    }

    private fun implicitBeanForClass(
        element: PsiElement,
    ): JmixSpringBeanDeclaration? {
        if (element !is PsiNamedElement) return null
        if (element !is PsiClass &&
            element !is PsiMethod &&
            element.javaClass.simpleName !in KOTLIN_BEAN_BACKING_ELEMENTS
        ) {
            return null
        }
        return jmixSpringBeanDeclarations(element).singleOrNull { declaration ->
            declaration.explicitNameElement == null &&
                element.manager.areElementsEquivalent(
                    declaration.classElement,
                    element,
            )
        }
    }

    companion object {
        private val KOTLIN_BEAN_BACKING_ELEMENTS =
            setOf("KtClass", "KtObjectDeclaration")
    }
}

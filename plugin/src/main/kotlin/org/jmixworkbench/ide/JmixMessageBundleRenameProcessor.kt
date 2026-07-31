package org.jmixworkbench.ide

import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenamePsiElementProcessor

/**
 * Keeps a logical message key aligned across `messages.properties` and every
 * localized sibling such as `messages_bn.properties`.
 *
 * The rename stays inside the same physical resource-bundle directory. This
 * avoids changing an unrelated module or a library override that happens to
 * use the same package and key.
 */
class JmixMessageBundleRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean {
        val property = element as? Property ?: return false
        val file = property.containingFile?.virtualFile ?: return false
        return JMIX_MESSAGE_BUNDLE_FILE.matches(file.name)
    }

    override fun prepareRenaming(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<PsiElement, String>,
    ) {
        val property = element as? Property ?: return
        localizedSiblings(property).forEach { sibling ->
            allRenames[sibling] = newName
        }
    }

    private fun localizedSiblings(property: Property): List<Property> {
        val oldName = property.name?.takeIf(String::isNotBlank) ?: return emptyList()
        val directory =
            property.containingFile?.virtualFile?.parent ?: return emptyList()
        val manager = PsiManager.getInstance(property.project)

        return directory.children
            .asSequence()
            .filter { file ->
                !file.isDirectory &&
                    file.isWritable &&
                    JMIX_MESSAGE_BUNDLE_FILE.matches(file.name)
            }
            .filterNot { it == property.containingFile.virtualFile }
            .mapNotNull(manager::findFile)
            .filterIsInstance<PropertiesFile>()
            .flatMap { it.properties.asSequence() }
            .filterIsInstance<Property>()
            .filter { it.name == oldName }
            .filterNot { candidate ->
                property.manager.areElementsEquivalent(candidate, property)
            }
            .toList()
    }
}

private val JMIX_MESSAGE_BUNDLE_FILE =
    Regex("""messages(?:_[A-Za-z0-9_-]+)?\.properties""")

package org.jmixworkbench.ide

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference

/**
 * A localized Jmix message key intentionally resolves to every matching
 * property in its resource-bundle family. IntelliJ's single-target
 * [PsiReference.resolve] contract returns null for that valid situation, so
 * inspections must use polyvariant resolution for message references.
 */
internal fun PsiReference.resolvesToAnyJmixTarget(): Boolean =
    when (this) {
        is JmixXmlMessageReference,
        is JmixJavaMessageReference,
        is JmixKotlinMessageReference,
        -> (this as PsiPolyVariantReference).multiResolve(false).isNotEmpty()

        else -> resolve() != null
    }

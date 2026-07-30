package org.jmixworkbench.host.idea262

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Idea262DescriptorTest {

    @Test
    fun `descriptor satisfies the idea262 host contract`() {
        val descriptor = readPackagedDescriptor()

        assertTrue(descriptor.contains("<id>org.jmixworkbench</id>"))
        assertTrue(descriptor.contains("<name>Jmix Visual Workbench</name>"))
        assertTrue(Regex("""<idea-version[^>]+since-build="262"[^>]+until-build="262\.\*"""").containsMatchIn(descriptor))
        assertTrue(descriptor.contains("<depends>com.intellij.modules.platform</depends>"))
        assertTrue(descriptor.contains("<depends>com.intellij.modules.java</depends>"))
        assertTrue(descriptor.contains("<depends>com.intellij.gradle</depends>"))
        assertTrue(
            Regex(
                """<newProjectWizard\.generator\s+implementation="org\.jmixworkbench\.project\.JmixNewProjectWizard"\s*/>""",
            ).containsMatchIn(descriptor),
        )
        assertTrue(descriptor.contains("org.jmixworkbench.project.JmixTemplateCatalogConfigurable"))
        assertTrue(
            Regex(
                """<fileEditorProvider\s+implementation="org\.jmixworkbench\.editor\.JmixFlowUiFileEditorProvider"\s*/>""",
            ).containsMatchIn(descriptor),
        )
        assertTrue(
            Regex(
                """<fileEditorProvider\s+implementation="org\.jmixworkbench\.editor\.JmixEntityFileEditorProvider"\s*/>""",
            ).containsMatchIn(descriptor),
        )
        assertTrue(descriptor.contains("org.jmixworkbench.ide.JmixJavaUiComponentPolicyInspection"))
        assertTrue(descriptor.contains("org.jmixworkbench.ide.JmixKotlinUiComponentPolicyInspection"))
        assertTrue(descriptor.contains("<depends>com.intellij.modules.jcef</depends>"))
    }

    private fun readPackagedDescriptor(): String {
        val resource = assertNotNull(javaClass.getResource("/META-INF/plugin.xml"))
        return resource.readText()
    }
}

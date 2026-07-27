package org.jmixworkbench.host.idea262

import org.jmixworkbench.toolwindow.JCEF_UNAVAILABLE_CODE
import org.jmixworkbench.toolwindow.JmixWorkbenchToolWindowFactory
import org.jmixworkbench.toolwindow.WEB_BUNDLE_MISSING_CODE
import org.jmixworkbench.toolwindow.WorkbenchFallbackPanel
import org.jmixworkbench.toolwindow.WorkbenchUiResourceResolver
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Idea262PluginSmokeTest {

    @Test
    fun `descriptor and tool window satisfy the idea262 host contract`() {
        val descriptor = readPackagedDescriptor()

        assertTrue(descriptor.contains("<id>org.jmixworkbench</id>"))
        assertTrue(descriptor.contains("<name>Jmix Visual Workbench</name>"))
        assertTrue(Regex("""<idea-version[^>]+since-build="262"[^>]+until-build="262\.\*"""").containsMatchIn(descriptor))
        assertTrue(descriptor.contains("<depends>com.intellij.modules.platform</depends>"))
        assertTrue(descriptor.contains("<depends>com.intellij.modules.java</depends>"))
        assertTrue(descriptor.contains("<depends>com.intellij.modules.jcef</depends>"))
        assertNotNull(JmixWorkbenchToolWindowFactory())
    }

    @Test
    fun `packaged web entry point and fallback diagnostics resolve without project access`() {
        val resolved = WorkbenchUiResourceResolver.resolve(null, javaClass::getResource)

        assertNotNull(resolved)
        assertTrue(resolved.contains("/webui/index.html"))
        assertTrue(WorkbenchFallbackPanel.jcefUnavailable().name == JCEF_UNAVAILABLE_CODE)
        assertTrue(WorkbenchFallbackPanel.webBundleMissing().name == WEB_BUNDLE_MISSING_CODE)
    }

    private fun readPackagedDescriptor(): String {
        val resource = assertNotNull(javaClass.getResource("/META-INF/plugin.xml"))
        return resource.readText()
    }
}

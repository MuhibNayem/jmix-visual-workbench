package org.jmixworkbench.host.idea262

import org.jmixworkbench.toolwindow.JCEF_UNAVAILABLE_CODE
import org.jmixworkbench.toolwindow.WEB_BUNDLE_MISSING_CODE
import org.jmixworkbench.toolwindow.WorkbenchBridgeAccess
import org.jmixworkbench.toolwindow.WorkbenchBrowserLifecycle
import org.jmixworkbench.toolwindow.WorkbenchFallbackPanel
import org.jmixworkbench.toolwindow.WorkbenchStartupPlan
import org.jmixworkbench.toolwindow.WorkbenchToolWindowStartup
import kotlin.test.Test
import kotlin.test.assertEquals
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
    }

    @Test
    fun `startup and lifecycle seams enforce the idea262 browser contract`() {
        val packaged = WorkbenchToolWindowStartup.plan(true, false, null, javaClass::getResource)
            as WorkbenchStartupPlan.Browser
        assertTrue(packaged.ui.url.contains("/webui/index.html"))
        assertEquals(WorkbenchBridgeAccess.PACKAGED_PROJECT, packaged.ui.bridgeAccess)
        assertEquals(
            WorkbenchStartupPlan.JcefUnavailable,
            WorkbenchToolWindowStartup.plan(false, false, null) { null },
        )
        assertEquals(
            WorkbenchStartupPlan.DevelopmentUrlRejected,
            WorkbenchToolWindowStartup.plan(true, true, "https://example.com") { null },
        )
        assertTrue(WorkbenchFallbackPanel.jcefUnavailable().name == JCEF_UNAVAILABLE_CODE)
        assertTrue(WorkbenchFallbackPanel.webBundleMissing().name == WEB_BUNDLE_MISSING_CODE)

        val events = mutableListOf<String>()
        WorkbenchBrowserLifecycle(
            disposeBridge = { events += "bridge" },
            disposeBrowser = { events += "browser" },
        ).dispose()
        assertEquals(listOf("bridge", "browser"), events)
    }

    private fun readPackagedDescriptor(): String {
        val resource = assertNotNull(javaClass.getResource("/META-INF/plugin.xml"))
        return resource.readText()
    }
}

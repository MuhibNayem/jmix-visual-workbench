package org.jmixworkbench.toolwindow

import java.net.URI
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkbenchUiResourceResolverTest {

    @Test
    fun `resolves the packaged entry point`() {
        val expected = URI("jar:file:/verified-plugin.jar!/webui/index.html").toURL()

        val resolved = WorkbenchUiResourceResolver.resolve(null) { path ->
            expected.takeIf { path == "/webui/index.html" }
        }

        assertEquals(expected.toExternalForm(), resolved)
    }

    @Test
    fun `returns null when packaged entry point is missing`() {
        assertNull(WorkbenchUiResourceResolver.resolve(null) { null })
    }

    @Test
    fun `development URL is explicit and bypasses packaged lookup`() {
        val resolved = WorkbenchUiResourceResolver.resolve("http://127.0.0.1:5173") {
            error("Packaged resource lookup must not run for an explicit development URL")
        }

        assertEquals("http://127.0.0.1:5173", resolved)
    }

    @Test
    fun `fallback panels expose stable diagnostic codes`() {
        assertDiagnostic(WorkbenchFallbackPanel.jcefUnavailable() as JPanel, JCEF_UNAVAILABLE_CODE)
        assertDiagnostic(WorkbenchFallbackPanel.webBundleMissing() as JPanel, WEB_BUNDLE_MISSING_CODE)
    }

    private fun assertDiagnostic(panel: JPanel, code: String) {
        assertEquals(code, panel.name)
        val label = panel.components.filterIsInstance<JLabel>().singleOrNull()
        assertNotNull(label)
        assertTrue(label.text.contains(code))
    }
}

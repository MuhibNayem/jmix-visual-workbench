package org.jmixworkbench.toolwindow

import java.net.URI
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkbenchUiResourceResolverTest {

    @Test
    fun `resolves the packaged entry point`() {
        val expected = URI("jar:file:/verified-plugin.jar!/webui/index.html").toURL()

        val resolved = WorkbenchUiResourceResolver.resolve(false, null) { path ->
            expected.takeIf { path == "/webui/index.html" }
        }

        assertEquals(expected.toExternalForm(), resolved?.url)
        assertEquals(WorkbenchBridgeAccess.PACKAGED_PROJECT, resolved?.bridgeAccess)
    }

    @Test
    fun `returns null when packaged entry point is missing`() {
        assertNull(WorkbenchUiResourceResolver.resolve(false, null) { null })
    }

    @Test
    fun `explicit loopback development URL never receives the project bridge`() {
        val resolved = WorkbenchUiResourceResolver.resolve(true, "http://127.0.0.1:5173") {
            error("Packaged resource lookup must not run for an explicit development URL")
        }

        assertEquals("http://127.0.0.1:5173", resolved?.url)
        assertEquals(WorkbenchBridgeAccess.NONE, resolved?.bridgeAccess)
    }

    @Test
    fun `rejects unsafe or ambiguous development URLs`() {
        listOf(
            "https://example.com",
            "http://example.com:5173",
            "http://localhost:5173",
            "http://user:password@127.0.0.1:5173",
            "http://127.0.0.1:8080",
            "http://127.0.0.1:5173/#fragment",
            "http://127.0.0.1:5173/?origin=other",
            "http://127.0.0.1:5173/other",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) {
                WorkbenchUiResourceResolver.resolve(true, url) { null }
            }
        }
        assertFailsWith<IllegalArgumentException>("disabled development mode") {
            WorkbenchUiResourceResolver.resolve(false, "http://127.0.0.1:5173") { null }
        }
    }

    @Test
    fun `startup seam covers supported unsupported and rejected states`() {
        assertEquals(
            WorkbenchStartupPlan.JcefUnavailable,
            WorkbenchToolWindowStartup.plan(false, false, null) { null },
        )
        assertEquals(
            WorkbenchStartupPlan.WebBundleMissing,
            WorkbenchToolWindowStartup.plan(true, false, null) { null },
        )
        assertEquals(
            WorkbenchStartupPlan.DevelopmentUrlRejected,
            WorkbenchToolWindowStartup.plan(true, true, "https://example.com") { null },
        )
    }

    @Test
    fun `browser lifecycle disposes bridge before browser exactly once`() {
        val events = mutableListOf<String>()
        val lifecycle = WorkbenchBrowserLifecycle(
            disposeBridge = { events += "bridge" },
            disposeBrowser = { events += "browser" },
        )

        lifecycle.dispose()
        lifecycle.dispose()

        assertEquals(listOf("bridge", "browser"), events)
    }

    @Test
    fun `fallback panels expose stable diagnostic codes`() {
        assertDiagnostic(WorkbenchFallbackPanel.jcefUnavailable() as JPanel, JCEF_UNAVAILABLE_CODE)
        assertDiagnostic(WorkbenchFallbackPanel.webBundleMissing() as JPanel, WEB_BUNDLE_MISSING_CODE)
        assertDiagnostic(
            WorkbenchFallbackPanel.developmentUrlRejected() as JPanel,
            DEVELOPMENT_URL_REJECTED_CODE,
        )
    }

    private fun assertDiagnostic(panel: JPanel, code: String) {
        assertEquals(code, panel.name)
        val label = panel.components.filterIsInstance<JLabel>().singleOrNull()
        assertNotNull(label)
        assertTrue(label.text.contains(code))
    }
}

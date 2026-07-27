package org.jmixworkbench.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.jmixworkbench.bridge.JcefBridge
import java.awt.BorderLayout
import java.net.URL
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

internal const val JCEF_UNAVAILABLE_CODE = "JVW-JCEF-UNAVAILABLE"
internal const val WEB_BUNDLE_MISSING_CODE = "JVW-WEB-BUNDLE-MISSING"

internal object WorkbenchUiResourceResolver {
    private const val ENTRY_POINT = "/webui/index.html"

    fun resolve(
        developmentUrl: String?,
        resourceLookup: (String) -> URL?,
    ): String? {
        if (!developmentUrl.isNullOrBlank()) {
            return developmentUrl
        }
        return resourceLookup(ENTRY_POINT)?.toExternalForm()
    }
}

internal object WorkbenchFallbackPanel {
    fun jcefUnavailable(): JComponent = diagnostic(
        JCEF_UNAVAILABLE_CODE,
        "JCEF is unavailable in this IntelliJ runtime. The visual workbench cannot start.",
    )

    fun webBundleMissing(): JComponent = diagnostic(
        WEB_BUNDLE_MISSING_CODE,
        "The packaged web bundle is missing. Reinstall a verified plugin distribution.",
    )

    private fun diagnostic(code: String, message: String): JComponent {
        val panel = JPanel(BorderLayout())
        panel.name = code
        panel.add(
            JLabel("[$code] $message", SwingConstants.CENTER),
            BorderLayout.CENTER,
        )
        return panel
    }
}

/**
 * Creates the Jmix Visual Workbench tool window with an embedded JCEF browser
 * running the React-based visual designer UI.
 */
class JmixWorkbenchToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            addFallbackContent(toolWindow, WorkbenchFallbackPanel.jcefUnavailable())
            return
        }

        val uiUrl = WorkbenchUiResourceResolver.resolve(
            System.getProperty("jmixworkbench.dev.url"),
            javaClass::getResource,
        )
        if (uiUrl == null) {
            addFallbackContent(toolWindow, WorkbenchFallbackPanel.webBundleMissing())
            return
        }

        val browser = JBCefBrowser()
        val bridge = JcefBridge(project, browser)
        browser.loadURL(uiUrl)

        val content = ContentFactory.getInstance().createContent(browser.component, "Designer", false)
        content.setDisposer { bridge.dispose() }
        toolWindow.contentManager.addContent(content)
    }

    private fun addFallbackContent(toolWindow: ToolWindow, panel: JComponent) {
        val content = ContentFactory.getInstance().createContent(panel, "Error", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

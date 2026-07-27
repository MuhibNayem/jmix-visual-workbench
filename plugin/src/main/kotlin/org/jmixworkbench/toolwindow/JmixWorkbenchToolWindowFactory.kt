package org.jmixworkbench.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.jmixworkbench.bridge.JcefBridge
import java.io.File

/**
 * Creates the Jmix Visual Workbench tool window with an embedded JCEF browser
 * running the React-based visual designer UI.
 */
class JmixWorkbenchToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val label = javax.swing.JLabel(
                "JCEF is not supported. Please enable it in Help → Find Action → 'Registry' → ide.browser.jcef.enabled",
                javax.swing.SwingConstants.CENTER
            )
            val content = ContentFactory.getInstance().createContent(label, "Error", false)
            toolWindow.contentManager.addContent(content)
            return
        }

        val browser = JBCefBrowser()
        val bridge = JcefBridge(project, browser)

        // Load the React UI
        val uiUrl = resolveUiUrl()
        browser.loadURL(uiUrl)

        val content = ContentFactory.getInstance().createContent(browser.component, "Designer", false)
        content.setDisposer { bridge.dispose() }
        toolWindow.contentManager.addContent(content)
    }

    private fun resolveUiUrl(): String {
        // In development: load from Vite dev server
        val devUrl = System.getProperty("jmixworkbench.dev.url")
        if (devUrl != null) return devUrl

        // In production: load bundled files from plugin resources
        val resource = javaClass.getResource("/webui/index.html")
        if (resource != null) return resource.toExternalForm()

        // Fallback: check for local dist directory
        val distPath = File("webui/dist/index.html")
        if (distPath.exists()) return distPath.toURI().toString()

        return "about:blank"
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

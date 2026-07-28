package org.jmixworkbench.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.jmixworkbench.services.JmixRuntimeViewport
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants

const val JMIX_RUNTIME_PREVIEW_TOOL_WINDOW_ID = "Jmix Runtime Preview"

class JmixRuntimePreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (toolWindow.contentManager.contentCount > 0) return
        val label = JLabel(
            "Open a detected local Jmix runtime from the Visual Workbench.",
            SwingConstants.CENTER,
        )
        val content = ContentFactory.getInstance().createContent(label, "Runtime", false)
        content.putUserData(PLACEHOLDER_KEY, true)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

object JmixRuntimePreviewToolWindow {
    fun open(
        project: Project,
        url: String,
        title: String,
        viewport: JmixRuntimeViewport,
    ) {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(JMIX_RUNTIME_PREVIEW_TOOL_WINDOW_ID)
            ?: return
        toolWindow.show {
            if (!JBCefApp.isSupported()) return@show
            toolWindow.contentManager.contents
                .filter { it.getUserData(PLACEHOLDER_KEY) == true }
                .forEach { toolWindow.contentManager.removeContent(it, true) }
            val existing = toolWindow.contentManager.contents.firstOrNull {
                it.getUserData(PREVIEW_URL_KEY) == url
            }
            if (existing != null) {
                existing.getUserData(PREVIEW_PANEL_KEY)?.setViewport(viewport)
                toolWindow.contentManager.setSelectedContent(existing, true)
                return@show
            }
            val panel = RuntimePreviewPanel(url, viewport)
            val content = ContentFactory.getInstance().createContent(panel, title, false)
            content.isCloseable = true
            content.setDisposer(panel)
            content.putUserData(PREVIEW_URL_KEY, url)
            content.putUserData(PREVIEW_PANEL_KEY, panel)
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content, true)
        }
    }
}

private class RuntimePreviewPanel(
    private val url: String,
    initialViewport: JmixRuntimeViewport,
) : JPanel(BorderLayout()), Disposable {
    private val browser = JBCefBrowser()
    private val browserViewport = JPanel(GridBagLayout())
    private var disposed = false

    init {
        border = BorderFactory.createEmptyBorder()
        add(toolbar(), BorderLayout.NORTH)
        browserViewport.add(
            browser.component,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
                anchor = GridBagConstraints.NORTH
            },
        )
        add(JScrollPane(browserViewport), BorderLayout.CENTER)
        setViewport(initialViewport)
        browser.loadURL(url)
    }

    private fun toolbar(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JLabel("Real Vaadin runtime"))
            add(viewportButton("Desktop", JmixRuntimeViewport.DESKTOP))
            add(viewportButton("Tablet", JmixRuntimeViewport.TABLET))
            add(viewportButton("Mobile", JmixRuntimeViewport.MOBILE))
            add(JButton("Refresh").apply {
                addActionListener { browser.cefBrowser.reloadIgnoreCache() }
            })
            add(JButton("Open externally").apply {
                addActionListener { BrowserUtil.browse(url) }
            })
            add(JLabel(url).apply {
                toolTipText = url
            })
        }

    private fun viewportButton(label: String, viewport: JmixRuntimeViewport): JButton =
        JButton(label).apply {
            addActionListener { setViewport(viewport) }
        }

    fun setViewport(viewport: JmixRuntimeViewport) {
        val width = when (viewport) {
            JmixRuntimeViewport.DESKTOP -> 1440
            JmixRuntimeViewport.TABLET -> 834
            JmixRuntimeViewport.MOBILE -> 390
        }
        val height = when (viewport) {
            JmixRuntimeViewport.DESKTOP -> 900
            JmixRuntimeViewport.TABLET -> 1112
            JmixRuntimeViewport.MOBILE -> 844
        }
        browser.component.minimumSize = Dimension(width, height)
        browser.component.preferredSize = Dimension(width, height)
        browserViewport.revalidate()
        browserViewport.repaint()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        Disposer.dispose(browser)
    }
}

private val PLACEHOLDER_KEY =
    com.intellij.openapi.util.Key.create<Boolean>("jmixworkbench.runtime.preview.placeholder")
private val PREVIEW_URL_KEY =
    com.intellij.openapi.util.Key.create<String>("jmixworkbench.runtime.preview.url")
private val PREVIEW_PANEL_KEY =
    com.intellij.openapi.util.Key.create<RuntimePreviewPanel>("jmixworkbench.runtime.preview.panel")

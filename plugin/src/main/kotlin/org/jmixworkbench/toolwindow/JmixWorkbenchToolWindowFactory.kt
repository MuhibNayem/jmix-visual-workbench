package org.jmixworkbench.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.jmixworkbench.bridge.JcefBridge
import java.awt.BorderLayout
import java.net.URI
import java.net.URL
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

internal const val JCEF_UNAVAILABLE_CODE = "JVW-JCEF-UNAVAILABLE"
internal const val WEB_BUNDLE_MISSING_CODE = "JVW-WEB-BUNDLE-MISSING"
internal const val DEVELOPMENT_URL_REJECTED_CODE = "JVW-DEV-URL-REJECTED"

internal enum class WorkbenchBridgeAccess {
    PACKAGED_PROJECT,
    NONE,
}

internal data class ResolvedWorkbenchUi(
    val url: String,
    val bridgeAccess: WorkbenchBridgeAccess,
)

internal object WorkbenchUiResourceResolver {
    private const val ENTRY_POINT = "/webui/index.html"
    private const val DEVELOPMENT_PORT = 5173
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1")

    fun resolve(
        developmentModeEnabled: Boolean,
        developmentUrl: String?,
        resourceLookup: (String) -> URL?,
    ): ResolvedWorkbenchUi? {
        if (!developmentUrl.isNullOrBlank()) {
            require(developmentModeEnabled) {
                "Development URL requires -Djmixworkbench.dev.enabled=true"
            }
            val uri = runCatching { URI(developmentUrl) }
                .getOrElse { throw IllegalArgumentException("Development URL is not a valid URI", it) }
            require(
                uri.scheme == "http" &&
                    uri.host in LOOPBACK_HOSTS &&
                    uri.port == DEVELOPMENT_PORT &&
                    uri.userInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"),
            ) {
                "Development URL must be exactly a credential-free loopback HTTP origin on port $DEVELOPMENT_PORT"
            }
            return ResolvedWorkbenchUi(uri.toASCIIString(), WorkbenchBridgeAccess.NONE)
        }
        return resourceLookup(ENTRY_POINT)?.let {
            ResolvedWorkbenchUi(PACKAGED_WORKBENCH_ENTRY_URL, WorkbenchBridgeAccess.PACKAGED_PROJECT)
        }
    }
}

internal sealed interface WorkbenchStartupPlan {
    data object JcefUnavailable : WorkbenchStartupPlan
    data object WebBundleMissing : WorkbenchStartupPlan
    data object DevelopmentUrlRejected : WorkbenchStartupPlan
    data class Browser(val ui: ResolvedWorkbenchUi) : WorkbenchStartupPlan
}

internal object WorkbenchToolWindowStartup {
    fun plan(
        jcefSupported: Boolean,
        developmentModeEnabled: Boolean,
        developmentUrl: String?,
        resourceLookup: (String) -> URL?,
    ): WorkbenchStartupPlan {
        if (!jcefSupported) {
            return WorkbenchStartupPlan.JcefUnavailable
        }
        val resolved = try {
            WorkbenchUiResourceResolver.resolve(
                developmentModeEnabled,
                developmentUrl,
                resourceLookup,
            )
        } catch (_: IllegalArgumentException) {
            return WorkbenchStartupPlan.DevelopmentUrlRejected
        }
        return resolved?.let(WorkbenchStartupPlan::Browser)
            ?: WorkbenchStartupPlan.WebBundleMissing
    }
}

internal class WorkbenchBrowserLifecycle(
    private val disposeBridge: () -> Unit,
    private val disposeBrowser: () -> Unit,
) : Disposable {
    private var disposed = false

    override fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        disposeBridge()
        disposeBrowser()
    }
}

internal interface WorkbenchEmbeddedBrowser {
    val component: JComponent

    fun installPackagedResources(provider: PackagedWorkbenchResourceProvider)

    fun loadUrl(url: String)

    fun dispose()
}

internal fun interface WorkbenchProjectBridge {
    fun dispose()
}

internal interface WorkbenchToolWindowRuntime {
    fun isJcefSupported(): Boolean

    fun developmentModeEnabled(): Boolean

    fun developmentUrl(): String?

    fun resource(path: String): URL?

    fun createBrowser(): WorkbenchEmbeddedBrowser

    fun createProjectBridge(project: Project, browser: WorkbenchEmbeddedBrowser): WorkbenchProjectBridge

    fun createContent(component: JComponent, displayName: String): Content
}

private class IntelliJEmbeddedBrowser(
    val delegate: JBCefBrowser = JBCefBrowser(),
) : WorkbenchEmbeddedBrowser {
    private var packagedRequestHandler: PackagedWorkbenchRequestHandler? = null

    override val component: JComponent
        get() = delegate.component

    override fun installPackagedResources(provider: PackagedWorkbenchResourceProvider) {
        check(packagedRequestHandler == null) {
            "Packaged resources are already installed for this browser"
        }
        val handler = PackagedWorkbenchRequestHandler(provider)
        delegate.jbCefClient.addRequestHandler(handler, delegate.cefBrowser)
        packagedRequestHandler = handler
    }

    override fun loadUrl(url: String) {
        delegate.loadURL(url)
    }

    override fun dispose() {
        packagedRequestHandler?.let { handler ->
            delegate.jbCefClient.removeRequestHandler(handler, delegate.cefBrowser)
            packagedRequestHandler = null
        }
        delegate.dispose()
    }
}

private class IntelliJProjectBridge(
    private val delegate: JcefBridge,
) : WorkbenchProjectBridge {
    override fun dispose() {
        delegate.dispose()
    }
}

internal object IntelliJWorkbenchToolWindowRuntime : WorkbenchToolWindowRuntime {
    override fun isJcefSupported(): Boolean = JBCefApp.isSupported()

    override fun developmentModeEnabled(): Boolean =
        System.getProperty("jmixworkbench.dev.enabled").toBoolean()

    override fun developmentUrl(): String? = System.getProperty("jmixworkbench.dev.url")

    override fun resource(path: String): URL? =
        JmixWorkbenchToolWindowFactory::class.java.getResource(path)

    override fun createBrowser(): WorkbenchEmbeddedBrowser = IntelliJEmbeddedBrowser()

    override fun createProjectBridge(
        project: Project,
        browser: WorkbenchEmbeddedBrowser,
    ): WorkbenchProjectBridge {
        val intellijBrowser = browser as? IntelliJEmbeddedBrowser
            ?: error("The IntelliJ runtime requires an IntelliJ-backed browser")
        return IntelliJProjectBridge(JcefBridge(project, intellijBrowser.delegate))
    }

    override fun createContent(component: JComponent, displayName: String): Content =
        ContentFactory.getInstance().createContent(component, displayName, false)
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

    fun developmentUrlRejected(): JComponent = diagnostic(
        DEVELOPMENT_URL_REJECTED_CODE,
        "The development URL was rejected. Enable development mode and use http://127.0.0.1:5173.",
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
class JmixWorkbenchToolWindowFactory private constructor(
    private val runtime: WorkbenchToolWindowRuntime,
) : ToolWindowFactory, DumbAware {

    constructor() : this(IntelliJWorkbenchToolWindowRuntime)

    internal companion object {
        fun createForTests(runtime: WorkbenchToolWindowRuntime): JmixWorkbenchToolWindowFactory =
            JmixWorkbenchToolWindowFactory(runtime)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val startupPlan = WorkbenchToolWindowStartup.plan(
            runtime.isJcefSupported(),
            runtime.developmentModeEnabled(),
            runtime.developmentUrl(),
            runtime::resource,
        )
        when (startupPlan) {
            WorkbenchStartupPlan.JcefUnavailable -> {
                addFallbackContent(toolWindow, WorkbenchFallbackPanel.jcefUnavailable())
                return
            }
            WorkbenchStartupPlan.WebBundleMissing -> {
                addFallbackContent(toolWindow, WorkbenchFallbackPanel.webBundleMissing())
                return
            }
            WorkbenchStartupPlan.DevelopmentUrlRejected -> {
                addFallbackContent(toolWindow, WorkbenchFallbackPanel.developmentUrlRejected())
                return
            }
            is WorkbenchStartupPlan.Browser -> createBrowserContent(project, toolWindow, startupPlan.ui)
        }
    }

    private fun createBrowserContent(
        project: Project,
        toolWindow: ToolWindow,
        ui: ResolvedWorkbenchUi,
    ) {
        val browser = runtime.createBrowser()
        val bridge = if (ui.bridgeAccess == WorkbenchBridgeAccess.PACKAGED_PROJECT) {
            browser.installPackagedResources(
                PackagedWorkbenchResourceProvider(
                    resourceLookup = { path ->
                        runtime.resource(path)?.openStream()?.use { stream -> stream.readBytes() }
                    },
                ),
            )
            runtime.createProjectBridge(project, browser)
        } else {
            null
        }
        browser.loadUrl(ui.url)

        val content = runtime.createContent(browser.component, "Designer")
        content.setDisposer(
            WorkbenchBrowserLifecycle(
                disposeBridge = { bridge?.dispose() },
                disposeBrowser = browser::dispose,
            ),
        )
        toolWindow.contentManager.addContent(content)
    }

    private fun addFallbackContent(toolWindow: ToolWindow, panel: JComponent) {
        val content = runtime.createContent(panel, "Error")
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

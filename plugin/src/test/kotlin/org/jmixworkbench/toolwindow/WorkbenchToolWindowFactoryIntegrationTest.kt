package org.jmixworkbench.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkbenchToolWindowFactoryIntegrationTest {

    @Test
    fun `packaged startup attaches bridged browser content and wires disposal`() {
        val packagedUrl = URI("jar:file:/verified-plugin.jar!/webui/index.html").toURL()
        val runtime = FakeRuntime(packagedResource = packagedUrl)
        val toolWindow = FakeToolWindow()

        JmixWorkbenchToolWindowFactory.createForTests(runtime)
            .createToolWindowContent(fakeProject(), toolWindow.proxy)

        assertEquals(1, runtime.browsers.size)
        assertEquals(1, runtime.bridges.size)
        assertEquals(1, runtime.browsers.single().packagedProviders.size)
        assertEquals(listOf(PACKAGED_WORKBENCH_ENTRY_URL), runtime.browsers.single().loadedUrls)
        assertEquals(
            listOf("install-packaged-resources", "create-project-bridge", "load:$PACKAGED_WORKBENCH_ENTRY_URL"),
            runtime.startupEvents,
        )
        assertEquals(1, toolWindow.attached.size)
        assertSame(runtime.contents.single().proxy, toolWindow.attached.single())
        assertEquals("Designer", runtime.contents.single().displayName)

        val disposer = assertNotNull(runtime.contents.single().disposer)
        disposer.dispose()
        disposer.dispose()
        assertEquals(listOf("navigation", "bridge", "handler", "browser"), runtime.disposalEvents)
    }

    @Test
    fun `development startup attaches unbridged browser content for the accepted origin`() {
        val runtime = FakeRuntime(
            developmentModeEnabled = true,
            developmentUrl = "http://127.0.0.1:5173",
        )
        val toolWindow = FakeToolWindow()

        JmixWorkbenchToolWindowFactory.createForTests(runtime)
            .createToolWindowContent(fakeProject(), toolWindow.proxy)

        assertEquals(1, runtime.browsers.size)
        assertTrue(runtime.bridges.isEmpty())
        assertTrue(runtime.resourceRequests.isEmpty())
        assertTrue(runtime.browsers.single().packagedProviders.isEmpty())
        assertEquals(listOf("http://127.0.0.1:5173"), runtime.browsers.single().loadedUrls)
        assertEquals(listOf("load:http://127.0.0.1:5173"), runtime.startupEvents)
        assertEquals(1, toolWindow.attached.size)
        assertNotNull(runtime.contents.single().disposer).dispose()
        assertEquals(listOf("browser"), runtime.disposalEvents)
    }

    @Test
    fun `fallback states attach the expected non-browser content`() {
        assertFallback(
            runtime = FakeRuntime(jcefSupported = false),
            expectedCode = JCEF_UNAVAILABLE_CODE,
        )
        assertFallback(
            runtime = FakeRuntime(packagedResource = null),
            expectedCode = WEB_BUNDLE_MISSING_CODE,
        )
        assertFallback(
            runtime = FakeRuntime(
                developmentModeEnabled = true,
                developmentUrl = "https://example.com",
            ),
            expectedCode = DEVELOPMENT_URL_REJECTED_CODE,
        )
    }

    private fun assertFallback(runtime: FakeRuntime, expectedCode: String) {
        val toolWindow = FakeToolWindow()

        JmixWorkbenchToolWindowFactory.createForTests(runtime)
            .createToolWindowContent(fakeProject(), toolWindow.proxy)

        assertTrue(runtime.browsers.isEmpty())
        assertTrue(runtime.bridges.isEmpty())
        assertEquals(1, runtime.contents.size)
        assertEquals(expectedCode, runtime.contents.single().component.name)
        assertEquals("Error", runtime.contents.single().displayName)
        assertNull(runtime.contents.single().disposer)
        assertEquals(1, toolWindow.attached.size)
        assertSame(runtime.contents.single().proxy, toolWindow.attached.single())
    }

    private class FakeRuntime(
        private val jcefSupported: Boolean = true,
        private val developmentModeEnabled: Boolean = false,
        private val developmentUrl: String? = null,
        private val packagedResource: java.net.URL? =
            URI("jar:file:/verified-plugin.jar!/webui/index.html").toURL(),
    ) : WorkbenchToolWindowRuntime {
        val browsers = mutableListOf<FakeBrowser>()
        val bridges = mutableListOf<FakeBridge>()
        val contents = mutableListOf<FakeContent>()
        val resourceRequests = mutableListOf<String>()
        val startupEvents = mutableListOf<String>()
        val disposalEvents = mutableListOf<String>()

        override fun isJcefSupported(): Boolean = jcefSupported

        override fun developmentModeEnabled(): Boolean = developmentModeEnabled

        override fun developmentUrl(): String? = developmentUrl

        override fun resource(path: String): java.net.URL? {
            resourceRequests += path
            return packagedResource
        }

        override fun createBrowser(): WorkbenchEmbeddedBrowser =
            FakeBrowser(startupEvents, disposalEvents).also(browsers::add)

        override fun createProjectBridge(
            project: Project,
            browser: WorkbenchEmbeddedBrowser,
        ): WorkbenchProjectBridge =
            FakeBridge(disposalEvents).also {
                startupEvents += "create-project-bridge"
                bridges += it
            }

        override fun attachNavigation(
            project: Project,
            bridge: WorkbenchProjectBridge,
        ): Disposable = Disposable {
            disposalEvents += "navigation"
        }

        override fun createContent(component: JComponent, displayName: String): Content =
            FakeContent(component, displayName).also(contents::add).proxy
    }

    private class FakeBrowser(
        private val startupEvents: MutableList<String>,
        private val disposalEvents: MutableList<String>,
    ) : WorkbenchEmbeddedBrowser {
        override val component: JComponent = JPanel()
        val packagedProviders = mutableListOf<PackagedWorkbenchResourceProvider>()
        val loadedUrls = mutableListOf<String>()

        override fun installPackagedResources(provider: PackagedWorkbenchResourceProvider) {
            packagedProviders += provider
            startupEvents += "install-packaged-resources"
        }

        override fun loadUrl(url: String) {
            loadedUrls += url
            startupEvents += "load:$url"
        }

        override fun dispose() {
            if (packagedProviders.isNotEmpty()) {
                disposalEvents += "handler"
                packagedProviders.clear()
            }
            disposalEvents += "browser"
        }
    }

    private class FakeBridge(
        private val disposalEvents: MutableList<String>,
    ) : WorkbenchProjectBridge {
        override fun dispose() {
            disposalEvents += "bridge"
        }
    }

    private class FakeContent(
        val component: JComponent,
        val displayName: String,
    ) {
        var disposer: Disposable? = null
        val proxy: Content = interfaceProxy(Content::class.java) { method, arguments ->
            when (method.name) {
                "setDisposer" -> {
                    disposer = arguments?.single() as Disposable
                    null
                }
                else -> defaultValue(method.returnType)
            }
        }
    }

    private class FakeToolWindow {
        val attached = mutableListOf<Content>()
        private val contentManager: ContentManager =
            interfaceProxy(ContentManager::class.java) { method, arguments ->
                when (method.name) {
                    "addContent" -> {
                        attached += arguments?.first() as Content
                        null
                    }
                    else -> defaultValue(method.returnType)
                }
            }
        val proxy: ToolWindow = interfaceProxy(ToolWindow::class.java) { method, _ ->
            when (method.name) {
                "getContentManager" -> contentManager
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun fakeProject(): Project =
        interfaceProxy(Project::class.java) { method, _ -> defaultValue(method.returnType) }

    private companion object {
        fun <T> interfaceProxy(
            type: Class<T>,
            invocation: (Method, Array<out Any?>?) -> Any?,
        ): T {
            val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, arguments ->
                when (method.name) {
                    "toString" -> "Fake${type.simpleName}"
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === arguments?.firstOrNull()
                    else -> invocation(method, arguments)
                }
            }
            return type.cast(proxy)
        }

        fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0.0f
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Short.TYPE -> 0.toShort()
            else -> null
        }
    }
}

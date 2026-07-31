package org.jmixworkbench.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.jmixworkbench.bridge.JcefBridge
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.services.ProjectFileResolver
import org.jmixworkbench.services.ProjectSourceText
import org.jmixworkbench.toolwindow.JCEF_UNAVAILABLE_CODE
import org.jmixworkbench.toolwindow.PACKAGED_FLOW_UI_EDITOR_ENTRY_URL
import org.jmixworkbench.toolwindow.PackagedWorkbenchRequestHandler
import org.jmixworkbench.toolwindow.PackagedWorkbenchResourceProvider
import org.jmixworkbench.toolwindow.WEB_BUNDLE_MISSING_CODE
import org.jmixworkbench.toolwindow.WorkbenchLaunchContext
import org.jmixworkbench.toolwindow.WorkbenchSurface
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.net.URL
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Adds a native "Design" editor beside IntelliJ's normal XML editor for real
 * Jmix FlowUI view and fragment descriptors.
 */
class JmixFlowUiFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        FlowUiFileEditorEligibility.accepts(project, file)

    override fun acceptRequiresReadAction(): Boolean = true

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        JmixFlowUiFileEditor(project, file, IntelliJFlowUiFileEditorRuntime)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "jmix-flowui-visual-editor"
    }
}

internal object FlowUiFileEditorEligibility {
    private val roots = setOf("view", "fragment")

    fun accepts(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory || !file.name.endsWith(".xml", ignoreCase = true)) {
            return false
        }
        if (!ProjectFileResolver.getInstance(project).contains(file)) {
            return false
        }
        val xml = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return false
        return xml.rootTag?.localName in roots
    }
}

internal interface WorkbenchFileEditorSession : Disposable {
    val component: JComponent

    fun publishLaunchContext(context: WorkbenchLaunchContext)
}

internal fun interface WorkbenchFileEditorRuntime {
    fun create(project: Project, initialContext: WorkbenchLaunchContext): WorkbenchFileEditorSession
}

internal object IntelliJFlowUiFileEditorRuntime : WorkbenchFileEditorRuntime {
    override fun create(
        project: Project,
        initialContext: WorkbenchLaunchContext,
    ): WorkbenchFileEditorSession {
        if (!JBCefApp.isSupported()) {
            return DiagnosticWorkbenchFileEditorSession(
                JCEF_UNAVAILABLE_CODE,
                "JCEF is unavailable. Use IntelliJ's XML editor in this runtime.",
            )
        }
        val entryPoint = JmixFlowUiFileEditorProvider::class.java.getResource("/webui/index.html")
            ?: return DiagnosticWorkbenchFileEditorSession(
                WEB_BUNDLE_MISSING_CODE,
                "The verified visual designer bundle is missing. Reinstall the plugin.",
            )
        return JcefWorkbenchFileEditorSession(
            project = project,
            initialContext = initialContext,
            entryPoint = entryPoint,
            entryUrl = PACKAGED_FLOW_UI_EDITOR_ENTRY_URL,
        )
    }
}

internal class JcefWorkbenchFileEditorSession(
    project: Project,
    initialContext: WorkbenchLaunchContext,
    private val entryPoint: URL,
    private val entryUrl: String,
) : WorkbenchFileEditorSession {
    private val browser = JBCefBrowser()
    private val resourceHandler = PackagedWorkbenchRequestHandler(
        PackagedWorkbenchResourceProvider(
            resourceLookup = { path ->
                JmixFlowUiFileEditorProvider::class.java.getResource(path)
                    ?.openStream()
                    ?.use { stream -> stream.readBytes() }
            },
        ),
    )
    private val bridge: JcefBridge
    private var disposed = false

    init {
        check(entryPoint.path.endsWith("/webui/index.html")) {
            "Unexpected workbench editor entry point."
        }
        browser.jbCefClient.addRequestHandler(resourceHandler, browser.cefBrowser)
        bridge = JcefBridge(project, browser, initialContext)
        browser.loadURL(entryUrl)
    }

    override val component: JComponent
        get() = browser.component

    override fun publishLaunchContext(context: WorkbenchLaunchContext) {
        if (!disposed) {
            bridge.publishLaunchContext(context)
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        bridge.dispose()
        browser.jbCefClient.removeRequestHandler(resourceHandler, browser.cefBrowser)
        browser.dispose()
    }
}

internal class DiagnosticWorkbenchFileEditorSession(
    code: String,
    message: String,
) : WorkbenchFileEditorSession {
    override val component: JComponent = JPanel(BorderLayout()).also { panel ->
        panel.name = code
        panel.add(JLabel("[$code] $message", SwingConstants.CENTER), BorderLayout.CENTER)
    }

    override fun publishLaunchContext(context: WorkbenchLaunchContext) = Unit

    override fun dispose() = Unit
}

internal open class JmixSourceVisualFileEditor(
    private val project: Project,
    private val virtualFile: VirtualFile,
    runtime: WorkbenchFileEditorRuntime,
    private val surface: WorkbenchSurface,
    private val invalidContextMessage: String,
) : UserDataHolderBase(), FileEditor {
    private val fileDocumentManager = FileDocumentManager.getInstance()
    private val document: Document? = fileDocumentManager.getDocument(virtualFile)
    private val propertyChanges = PropertyChangeSupport(this)
    private val session: WorkbenchFileEditorSession

    init {
        val context = requireNotNull(currentLaunchContext()) {
            invalidContextMessage
        }
        session = runtime.create(project, context)
        document?.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    propertyChanges.firePropertyChange(FileEditor.getPropModified(), null, isModified)
                }
            },
            this,
        )
    }

    override fun getComponent(): JComponent = session.component

    override fun getPreferredFocusedComponent(): JComponent = session.component

    override fun getName(): String = "Design"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = fileDocumentManager.isFileModified(virtualFile)

    override fun isValid(): Boolean = virtualFile.isValid

    override fun getFile(): VirtualFile = virtualFile

    override fun selectNotify() {
        currentLaunchContext()?.let(session::publishLaunchContext)
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChanges.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChanges.removePropertyChangeListener(listener)
    }

    override fun dispose() {
        session.dispose()
    }

    private fun currentLaunchContext(): WorkbenchLaunchContext? =
        ApplicationManager.getApplication().runReadAction(
            Computable<WorkbenchLaunchContext?> {
                val relativePath = ProjectFileResolver.getInstance(project).locatorPath(virtualFile)
                    ?: return@Computable null
                val content = ProjectSourceText.read(virtualFile)
                WorkbenchLaunchContext(
                    surface = surface,
                    sourceLocator = SourceLocator(
                        relativePath = relativePath,
                        revisionFingerprint = CanonicalDiscoveryJson.sha256(content),
                    ),
                )
            },
        )
}

internal class JmixFlowUiFileEditor(
    project: Project,
    virtualFile: VirtualFile,
    runtime: WorkbenchFileEditorRuntime,
) : JmixSourceVisualFileEditor(
    project = project,
    virtualFile = virtualFile,
    runtime = runtime,
    surface = WorkbenchSurface.FLOW_UI_EDITOR,
    invalidContextMessage = "The selected FlowUI descriptor is outside the registered project roots.",
)

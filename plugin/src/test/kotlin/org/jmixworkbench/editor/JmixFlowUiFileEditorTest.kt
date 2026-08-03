package org.jmixworkbench.editor

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.toolwindow.WorkbenchLaunchContext
import org.jmixworkbench.toolwindow.WorkbenchSurface
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JmixFlowUiFileEditorTest : LightJavaCodeInsightFixtureTestCase() {
    fun testProviderAcceptsOnlyProjectFlowUiViewsAndFragments() {
        val view = file(
            "src/main/resources/com/company/loan/loan-detail-view.xml",
            """
                <view xmlns="http://jmix.io/schema/flowui/view">
                    <layout/>
                </view>
            """.trimIndent(),
        )
        val fragment = file(
            "src/main/resources/com/company/loan/payment-fragment.xml",
            """
                <fragment xmlns="http://jmix.io/schema/flowui/fragment">
                    <content/>
                </fragment>
            """.trimIndent(),
        )
        val menu = file(
            "src/main/resources/com/company/menu.xml",
            "<menu-config><menu id=\"loan\"/></menu-config>",
        )
        val arbitrary = file(
            "src/main/resources/com/company/arbitrary.xml",
            "<beans><bean id=\"loan\"/></beans>",
        )
        val provider = JmixFlowUiFileEditorProvider()

        assertTrue(provider.accept(project, view))
        assertTrue(provider.accept(project, fragment))
        assertFalse(provider.accept(project, menu))
        assertFalse(provider.accept(project, arbitrary))
        assertEquals(FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR, provider.policy)
        assertEquals("jmix-flowui-visual-editor", provider.editorTypeId)
    }

    fun testEditorPublishesUnsavedDocumentRevisionOnCreateAndReselect() {
        val original = "<view><layout><textField id=\"amount\"/></layout></view>\n"
        val manual = original.replace("amount", "principal")
        val reselected = manual.replace("principal", "approvedPrincipal")
        val view = file(
            "src/main/resources/com/company/loan/loan-detail-view.xml",
            original,
        )
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(view))
        WriteAction.run<RuntimeException> {
            document.setText(manual)
        }
        val runtime = RecordingRuntime()
        val editor = JmixFlowUiFileEditor(project, view, runtime)
        val modifiedEvents = mutableListOf<Boolean>()
        editor.addPropertyChangeListener { event ->
            if (event.propertyName == FileEditor.getPropModified()) {
                modifiedEvents += event.newValue as Boolean
            }
        }

        assertEquals("Design", editor.name)
        assertSame(view, editor.file)
        assertTrue(editor.isModified)
        assertEquals(WorkbenchSurface.FLOW_UI_EDITOR, runtime.initial.surface)
        val locatorPath = requireNotNull(runtime.initial.sourceLocator?.relativePath)
        assertTrue(locatorPath.endsWith("src/main/resources/com/company/loan/loan-detail-view.xml"))
        assertFalse(locatorPath.startsWith("/"))
        assertFalse(locatorPath.split('/').any { it == ".." })
        assertEquals(
            CanonicalDiscoveryJson.sha256(manual),
            runtime.initial.sourceLocator?.revisionFingerprint,
        )

        WriteAction.run<RuntimeException> {
            document.setText(reselected)
        }
        editor.selectNotify()

        assertEquals(listOf(true), modifiedEvents)
        assertEquals(
            CanonicalDiscoveryJson.sha256(reselected),
            runtime.session.published.single().sourceLocator?.revisionFingerprint,
        )
        editor.dispose()
        assertTrue(runtime.session.disposed)
    }

    private fun file(path: String, content: String): VirtualFile =
        myFixture.addFileToProject(path, content).virtualFile

    private class RecordingRuntime : WorkbenchFileEditorRuntime {
        lateinit var initial: WorkbenchLaunchContext
        val session = RecordingSession()

        override fun create(
            project: com.intellij.openapi.project.Project,
            initialContext: WorkbenchLaunchContext,
        ): WorkbenchFileEditorSession {
            initial = initialContext
            return session
        }
    }

    private class RecordingSession : WorkbenchFileEditorSession {
        override val component: JComponent = JPanel()
        val published = mutableListOf<WorkbenchLaunchContext>()
        var disposed = false

        override fun publishLaunchContext(context: WorkbenchLaunchContext) {
            published += context
        }

        override fun dispose() {
            disposed = true
        }
    }
}

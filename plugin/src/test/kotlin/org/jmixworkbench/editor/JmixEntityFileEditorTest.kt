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

class JmixEntityFileEditorTest : LightJavaCodeInsightFixtureTestCase() {
    fun testProviderAcceptsHandwrittenJavaAndKotlinEntityKindsOnly() {
        val javaEntity = file(
            "modules/loan/src/main/java/com/company/loan/LoanApp.java",
            """
                package com.company.loan;

                @io.jmix.core.metamodel.annotation.JmixEntity
                @jakarta.persistence.Entity
                public class LoanApp {}
            """.trimIndent(),
        )
        val kotlinDto = file(
            "modules/payroll/src/main/kotlin/com/company/payroll/PayrollLine.kt",
            """
                package com.company.payroll

                @io.jmix.core.metamodel.annotation.JmixEntity
                data class PayrollLine(val amount: java.math.BigDecimal)
            """.trimIndent(),
        )
        val mappedSuperclass = file(
            "modules/shared/src/main/java/com/company/shared/BaseRecord.java",
            """
                package com.company.shared;

                @io.jmix.core.metamodel.annotation.JmixEntity
                @jakarta.persistence.MappedSuperclass
                public abstract class BaseRecord {}
            """.trimIndent(),
        )
        val service = file(
            "modules/loan/src/main/java/com/company/loan/LoanService.java",
            "package com.company.loan; public class LoanService {}",
        )
        val provider = JmixEntityFileEditorProvider()

        assertTrue(provider.accept(project, javaEntity))
        assertTrue(provider.accept(project, kotlinDto))
        assertTrue(provider.accept(project, mappedSuperclass))
        assertFalse(provider.accept(project, service))
        assertEquals(FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR, provider.policy)
        assertEquals("jmix-entity-visual-editor", provider.editorTypeId)
    }

    fun testEditorPublishesExactUnsavedEntityRevisionOnCreateAndReselect() {
        val original = """
            package com.company.loan;

            @jakarta.persistence.Entity
            public class LoanApp {
                private String applicationNo;
            }
        """.trimIndent()
        val manuallyEdited = original.replace("applicationNo", "businessApplicationNo")
        val reselected = manuallyEdited.replace("String", "java.lang.String")
        val entity = file(
            "modules/loan/src/main/java/com/company/loan/LoanApp.java",
            original,
        )
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(entity))
        WriteAction.run<RuntimeException> {
            document.setText(manuallyEdited)
        }
        val runtime = RecordingRuntime()
        val editor = JmixEntityFileEditor(project, entity, runtime)
        val modifiedEvents = mutableListOf<Boolean>()
        editor.addPropertyChangeListener { event ->
            if (event.propertyName == FileEditor.getPropModified()) {
                modifiedEvents += event.newValue as Boolean
            }
        }

        assertEquals("Design", editor.name)
        assertSame(entity, editor.file)
        assertTrue(editor.isModified)
        assertEquals(WorkbenchSurface.ENTITY_EDITOR, runtime.initial.surface)
        val locatorPath = requireNotNull(runtime.initial.sourceLocator?.relativePath)
        assertTrue(locatorPath.endsWith("modules/loan/src/main/java/com/company/loan/LoanApp.java"))
        assertFalse(locatorPath.startsWith("/"))
        assertFalse(locatorPath.split('/').any { it == ".." })
        assertEquals(
            CanonicalDiscoveryJson.sha256(manuallyEdited),
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

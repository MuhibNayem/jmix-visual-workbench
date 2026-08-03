package org.jmixworkbench.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.ui.jcef.JBCefApp
import org.jmixworkbench.services.ProjectFileResolver
import org.jmixworkbench.services.ProjectSourceText
import org.jmixworkbench.toolwindow.JCEF_UNAVAILABLE_CODE
import org.jmixworkbench.toolwindow.PACKAGED_ENTITY_EDITOR_ENTRY_URL
import org.jmixworkbench.toolwindow.WEB_BUNDLE_MISSING_CODE
import org.jmixworkbench.toolwindow.WorkbenchLaunchContext
import org.jmixworkbench.toolwindow.WorkbenchSurface

/**
 * Adds a native "Design" editor beside IntelliJ's Java/Kotlin source editor for
 * Jmix entities, DTOs, mapped superclasses, and embeddables.
 */
class JmixEntityFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        EntityFileEditorEligibility.accepts(project, file)

    override fun acceptRequiresReadAction(): Boolean = true

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        JmixEntityFileEditor(project, file, IntelliJEntityFileEditorRuntime)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "jmix-entity-visual-editor"
    }
}

internal object EntityFileEditorEligibility {
    private val supportedAnnotations = setOf(
        "JmixEntity",
        "Entity",
    )
    private val entityAnnotation = Regex(
        """(?m)^[ \t]*@(?:[A-Za-z_$][\w$]*\.)*(?:JmixEntity|Entity)\b""",
    )
    private val typeDeclaration = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private|internal|abstract|open|final|sealed|data)\s+)*(?:class|record)\s+[A-Za-z_$][\w$]*\b""",
    )

    fun accepts(project: Project, file: VirtualFile): Boolean {
        if (
            !file.isValid ||
            file.isDirectory ||
            file.extension?.lowercase() !in setOf("java", "kt") ||
            !ProjectFileResolver.getInstance(project).contains(file)
        ) {
            return false
        }
        if (file.extension.equals("java", ignoreCase = true)) {
            val javaFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile
                ?: return false
            return javaFile.classes.any { psiClass ->
                psiClass.annotations.any { annotation ->
                    annotation.qualifiedName?.substringAfterLast('.') in supportedAnnotations ||
                        annotation.nameReferenceElement?.referenceName in supportedAnnotations
                }
            }
        }
        val source = ProjectSourceText.read(file)
        return entityAnnotation.containsMatchIn(source) && typeDeclaration.containsMatchIn(source)
    }
}

internal object IntelliJEntityFileEditorRuntime : WorkbenchFileEditorRuntime {
    override fun create(
        project: Project,
        initialContext: WorkbenchLaunchContext,
    ): WorkbenchFileEditorSession {
        if (!JBCefApp.isSupported()) {
            return DiagnosticWorkbenchFileEditorSession(
                JCEF_UNAVAILABLE_CODE,
                "JCEF is unavailable. Use IntelliJ's Java/Kotlin editor in this runtime.",
            )
        }
        val entryPoint = JmixEntityFileEditorProvider::class.java.getResource("/webui/index.html")
            ?: return DiagnosticWorkbenchFileEditorSession(
                WEB_BUNDLE_MISSING_CODE,
                "The verified visual designer bundle is missing. Reinstall the plugin.",
            )
        return JcefWorkbenchFileEditorSession(
            project = project,
            initialContext = initialContext,
            entryPoint = entryPoint,
            entryUrl = PACKAGED_ENTITY_EDITOR_ENTRY_URL,
        )
    }
}

internal class JmixEntityFileEditor(
    project: Project,
    virtualFile: VirtualFile,
    runtime: WorkbenchFileEditorRuntime,
) : JmixSourceVisualFileEditor(
    project = project,
    virtualFile = virtualFile,
    runtime = runtime,
    surface = WorkbenchSurface.ENTITY_EDITOR,
    invalidContextMessage = "The selected entity source is outside the registered project roots.",
)

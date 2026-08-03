package org.jmixworkbench.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import org.jmixworkbench.services.JmixProjectService
import org.jmixworkbench.toolwindow.WorkbenchLaunchContext
import org.jmixworkbench.toolwindow.WorkbenchNavigationService
import org.jmixworkbench.toolwindow.WorkbenchSurface

private fun openWorkbench(e: AnActionEvent, surface: WorkbenchSurface) {
    val project = e.project ?: return
    WorkbenchNavigationService.getInstance(project).request(WorkbenchLaunchContext(surface))
    ToolWindowManager.getInstance(project).getToolWindow("Jmix Visual Workbench")?.show()
}

/**
 * Opens the Jmix Visual Workbench Designer tool window.
 */
class OpenDesignerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        openWorkbench(e, WorkbenchSurface.TOOL_WINDOW)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            JmixProjectService.getInstance(project).isJmixProject()
    }
}

/**
 * Opens the revision-bound project configuration workspace.
 */
class OpenProjectPropertiesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        openWorkbench(e, WorkbenchSurface.PROJECT_PROPERTIES)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            JmixProjectService.getInstance(project).isJmixProject()
    }
}

/**
 * Opens the Entity Designer tab in the tool window.
 */
class NewEntityAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        openWorkbench(e, WorkbenchSurface.ENTITY_DESIGNER)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            JmixProjectService.getInstance(project).isJmixProject()
    }
}

/**
 * Opens the View Designer tab in the tool window.
 */
class NewViewAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        openWorkbench(e, WorkbenchSurface.VIEW_DESIGNER)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            JmixProjectService.getInstance(project).isJmixProject()
    }
}

/**
 * Opens the CRUD Scaffolding wizard in the tool window.
 */
class NewCrudAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        openWorkbench(e, WorkbenchSurface.CRUD_DESIGNER)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            JmixProjectService.getInstance(project).isJmixProject()
    }
}

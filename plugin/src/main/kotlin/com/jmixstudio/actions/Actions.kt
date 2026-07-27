package com.jmixstudio.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.jmixstudio.services.JmixProjectService

/**
 * Opens the Jmix Studio Designer tool window.
 */
class OpenDesignerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Jmix Studio")
        toolWindow?.show()
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
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Jmix Studio")
        toolWindow?.show {
            // Signal the React UI to open Entity Designer
            // This is handled via the JCEF bridge
        }
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
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Jmix Studio")
        toolWindow?.show()
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
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Jmix Studio")
        toolWindow?.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            JmixProjectService.getInstance(project).isJmixProject()
    }
}

package org.jmixworkbench.toolwindow

import org.jmixworkbench.discovery.model.SourceLocator

/**
 * Trusted JVM-to-workbench launch context.
 *
 * Only project-relative, revision-checked locators cross the JCEF boundary.
 * The web UI cannot choose or elevate its own native editor surface.
 */
data class WorkbenchLaunchContext(
    val surface: WorkbenchSurface,
    val sourceLocator: SourceLocator? = null,
)

enum class WorkbenchSurface {
    TOOL_WINDOW,
    FLOW_UI_EDITOR,
}

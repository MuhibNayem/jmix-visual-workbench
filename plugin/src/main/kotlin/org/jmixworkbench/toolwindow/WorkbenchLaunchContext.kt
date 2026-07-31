package org.jmixworkbench.toolwindow

import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.model.ArtifactKind

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
    ENTITY_EDITOR,
    ENTITY_DESIGNER,
    VIEW_DESIGNER,
    CRUD_DESIGNER,
    PROJECT_PROPERTIES,
}

data class WorkbenchSurfaceOpenRequest(
    val surface: WorkbenchSurface,
    val sourceLocator: SourceLocator? = null,
)

data class WorkbenchSurfaceOpenResponse(
    val success: Boolean,
    val errorCode: String? = null,
    val message: String,
)

internal data class PreparedWorkbenchSurfaceOpen(
    val context: WorkbenchLaunchContext?,
    val response: WorkbenchSurfaceOpenResponse,
)

/**
 * Restricts browser-requested surface transitions to exact indexed evidence.
 *
 * Native editor surfaces are intentionally excluded: only IntelliJ can create
 * those. The browser may ask the existing tool window to open a source-owned
 * workflow for an entity or a FlowUI descriptor.
 */
internal object WorkbenchSurfaceOpenPolicy {
    fun prepare(
        request: WorkbenchSurfaceOpenRequest,
        entityLocators: Set<SourceLocator>,
        artifactKindsByLocator: Map<SourceLocator, Set<ArtifactKind>>,
    ): PreparedWorkbenchSurfaceOpen {
        val locator = request.sourceLocator
            ?: return rejected(
                "JVW-WORKBENCH-SURFACE-SOURCE-REQUIRED",
                "This workbench surface requires an indexed source locator.",
            )
        return when (request.surface) {
            WorkbenchSurface.CRUD_DESIGNER -> {
                if (locator !in entityLocators) {
                    rejected(
                        "JVW-WORKBENCH-ENTITY-SOURCE-STALE",
                        "The entity source is stale or is not present in the indexed schema workspace.",
                    )
                } else {
                    accepted(request.surface, locator)
                }
            }
            WorkbenchSurface.FLOW_UI_EDITOR -> {
                if (ArtifactKind.VIEW_DESCRIPTOR !in artifactKindsByLocator[locator].orEmpty()) {
                    rejected(
                        "JVW-WORKBENCH-VIEW-SOURCE-STALE",
                        "The FlowUI descriptor is stale or is not present in the indexed application graph.",
                    )
                } else {
                    accepted(request.surface, locator)
                }
            }
            else -> rejected(
                "JVW-WORKBENCH-SURFACE-DENIED",
                "The requested surface cannot be opened by web content.",
            )
        }
    }

    private fun accepted(
        surface: WorkbenchSurface,
        locator: SourceLocator,
    ) = PreparedWorkbenchSurfaceOpen(
        context = WorkbenchLaunchContext(surface, locator),
        response = WorkbenchSurfaceOpenResponse(
            success = true,
            message = "The requested workbench surface was opened from indexed source evidence.",
        ),
    )

    private fun rejected(code: String, message: String) = PreparedWorkbenchSurfaceOpen(
        context = null,
        response = WorkbenchSurfaceOpenResponse(
            success = false,
            errorCode = code,
            message = message,
        ),
    )
}

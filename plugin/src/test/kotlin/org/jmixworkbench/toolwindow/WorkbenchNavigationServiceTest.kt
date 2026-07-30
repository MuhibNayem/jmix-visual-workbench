package org.jmixworkbench.toolwindow

import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.SourceLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkbenchNavigationServiceTest {
    @Test
    fun `retains pre-launch request and broadcasts later navigation until detached`() {
        val service = WorkbenchNavigationService()
        val entity = WorkbenchLaunchContext(WorkbenchSurface.ENTITY_DESIGNER)
        val view = WorkbenchLaunchContext(WorkbenchSurface.VIEW_DESIGNER)
        val received = mutableListOf<WorkbenchLaunchContext>()

        service.request(entity)
        val attachment = service.attach(received::add)
        service.request(view)
        attachment.dispose()
        service.request(WorkbenchLaunchContext(WorkbenchSurface.CRUD_DESIGNER))

        assertEquals(listOf(entity, view), received)
    }

    @Test
    fun `browser may open existing entity workflow only from exact indexed revision`() {
        val locator = SourceLocator(
            relativePath = "loan/src/main/java/com/acme/loan/LoanApp.java",
            revisionFingerprint = "entity-v2",
        )

        val accepted = WorkbenchSurfaceOpenPolicy.prepare(
            WorkbenchSurfaceOpenRequest(WorkbenchSurface.CRUD_DESIGNER, locator),
            entityLocators = setOf(locator),
            artifactKindsByLocator = emptyMap(),
        )
        val stale = WorkbenchSurfaceOpenPolicy.prepare(
            WorkbenchSurfaceOpenRequest(
                WorkbenchSurface.CRUD_DESIGNER,
                locator.copy(revisionFingerprint = "entity-v1"),
            ),
            entityLocators = setOf(locator),
            artifactKindsByLocator = emptyMap(),
        )

        assertTrue(accepted.response.success)
        assertEquals(WorkbenchSurface.CRUD_DESIGNER, accepted.context?.surface)
        assertFalse(stale.response.success)
        assertEquals("JVW-WORKBENCH-ENTITY-SOURCE-STALE", stale.response.errorCode)
        assertNull(stale.context)
    }

    @Test
    fun `browser may design only indexed FlowUI descriptors and cannot elevate to native entity editor`() {
        val locator = SourceLocator(
            relativePath = "loan/src/main/resources/com/acme/loan/loan-app-list-view.xml",
            revisionFingerprint = "view-v3",
        )
        val view = WorkbenchSurfaceOpenPolicy.prepare(
            WorkbenchSurfaceOpenRequest(WorkbenchSurface.FLOW_UI_EDITOR, locator),
            entityLocators = emptySet(),
            artifactKindsByLocator = mapOf(locator to setOf(ArtifactKind.VIEW_DESCRIPTOR)),
        )
        val denied = WorkbenchSurfaceOpenPolicy.prepare(
            WorkbenchSurfaceOpenRequest(WorkbenchSurface.ENTITY_EDITOR, locator),
            entityLocators = emptySet(),
            artifactKindsByLocator = mapOf(locator to setOf(ArtifactKind.VIEW_DESCRIPTOR)),
        )

        assertTrue(view.response.success)
        assertEquals(WorkbenchSurface.FLOW_UI_EDITOR, view.context?.surface)
        assertFalse(denied.response.success)
        assertEquals("JVW-WORKBENCH-SURFACE-DENIED", denied.response.errorCode)
    }
}

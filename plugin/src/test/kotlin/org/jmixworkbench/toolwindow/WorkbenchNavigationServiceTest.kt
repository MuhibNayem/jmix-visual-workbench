package org.jmixworkbench.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals

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
}

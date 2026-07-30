package org.jmixworkbench.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-scoped launch channel shared by IntelliJ actions and the packaged
 * workbench browser. Requests made before the tool window exists are retained.
 */
@Service(Service.Level.PROJECT)
class WorkbenchNavigationService {
    private val latest = AtomicReference(WorkbenchLaunchContext(WorkbenchSurface.TOOL_WINDOW))
    private val listeners = CopyOnWriteArrayList<(WorkbenchLaunchContext) -> Unit>()

    fun request(context: WorkbenchLaunchContext) {
        latest.set(context)
        listeners.forEach { listener -> listener(context) }
    }

    fun attach(listener: (WorkbenchLaunchContext) -> Unit): Disposable {
        listeners += listener
        listener(latest.get())
        return Disposable { listeners -= listener }
    }

    companion object {
        fun getInstance(project: Project): WorkbenchNavigationService =
            project.getService(WorkbenchNavigationService::class.java)
    }
}

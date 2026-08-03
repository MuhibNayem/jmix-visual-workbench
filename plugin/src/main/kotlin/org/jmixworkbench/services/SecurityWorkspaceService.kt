package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.security.SecurityWorkspaceBuilder
import org.jmixworkbench.discovery.security.SecurityWorkspaceInput
import org.jmixworkbench.discovery.security.SecurityWorkspaceSnapshot
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Builds the source security model once per application-graph digest. Runtime evidence remains
 * independently refreshable and is merged onto the cached source model for each request.
 */
@Service(Service.Level.PROJECT)
class SecurityWorkspaceService(private val project: Project) {
    private val cachedSourceWorkspace = AtomicReference<SecurityWorkspaceSnapshot?>()
    private val buildLock = ReentrantLock()

    fun source(forceRefresh: Boolean = false): SecurityWorkspaceSnapshot {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        if (!forceRefresh) {
            cachedSourceWorkspace.get()
                ?.takeIf { it.graphDigest == graph.snapshotDigest }
                ?.let { return it }
        }
        return buildLock.withLock {
            cachedSourceWorkspace.get()
                ?.takeIf { it.graphDigest == graph.snapshotDigest }
                ?.let { return@withLock it }
            SecurityWorkspaceBuilder.build(
                SecurityWorkspaceInput(
                    artifacts = graph.artifacts,
                    relationships = graph.relationships,
                    diagnostics = graph.diagnostics,
                    graphDigest = graph.snapshotDigest,
                    checkCancelled = ProgressManager::checkCanceled,
                ),
            ).also(cachedSourceWorkspace::set)
        }
    }

    fun load(forceRefresh: Boolean = false): SecurityWorkspaceSnapshot {
        val sourceWorkspace = source(forceRefresh)
        return sourceWorkspace.copy(
            runtime = RuntimeSecurityEvidenceService.getInstance(project).snapshot(sourceWorkspace),
        )
    }

    companion object {
        fun getInstance(project: Project): SecurityWorkspaceService =
            project.getService(SecurityWorkspaceService::class.java)
    }
}

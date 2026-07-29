package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.model.WorkflowLoadResponse

@Service(Service.Level.PROJECT)
class WorkflowWorkspaceService(private val project: Project) {

    fun load(
        relativePath: String,
        processId: String,
        moduleId: String,
    ): WorkflowLoadResponse {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        if (!normalized.endsWith(".bpmn", ignoreCase = true) &&
            !normalized.endsWith(".bpmn20.xml", ignoreCase = true)
        ) {
            return WorkflowLoadResponse(error = "The selected source is not a BPMN workflow file.")
        }
        val indexed = ApplicationGraphService.getInstance(project).graph().artifacts.any { artifact ->
            artifact.kind == ArtifactKind.WORKFLOW_PROCESS &&
                artifact.semanticKey == processId &&
                artifact.sourceLocator.relativePath.replace('\\', '/').trimStart('/') == normalized
        }
        if (!indexed) {
            return WorkflowLoadResponse(
                error = "The workflow source changed or is no longer part of the indexed project.",
            )
        }
        val file = ProjectFileResolver.getInstance(project).resolveFile(normalized)?.file
            ?: return WorkflowLoadResponse(error = "The indexed BPMN source no longer exists.")
        if (file.isDirectory || file.length > MAX_WORKFLOW_BYTES) {
            return WorkflowLoadResponse(error = "The BPMN source is not a readable file within the safe size limit.")
        }
        val xml = String(file.contentsToByteArray(false), file.charset)
        return WorkflowXmlParser.parse(
            xml = xml,
            moduleId = moduleId,
            relativePath = normalized,
            requestedProcessId = processId,
        )
    }

    companion object {
        private const val MAX_WORKFLOW_BYTES = 8L * 1024L * 1024L

        fun getInstance(project: Project): WorkflowWorkspaceService =
            project.getService(WorkflowWorkspaceService::class.java)
    }
}

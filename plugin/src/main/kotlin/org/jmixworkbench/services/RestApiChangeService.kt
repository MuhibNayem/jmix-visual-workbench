package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.RestApiSourcePatcher
import org.jmixworkbench.generator.RestApiXmlContract
import org.jmixworkbench.generator.RestApiXmlParameter
import org.jmixworkbench.generator.RestApiXmlTarget

@Service(Service.Level.PROJECT)
class RestApiChangeService(private val project: Project) {
    fun previewAddition(request: RestApiContractAdditionRequest): WorkspaceChangePreviewResponse {
        val proposal = proposal(request)
        return proposal.changeSet?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues)
    }

    fun prepareAddition(request: RestApiContractAdditionApplyRequest): PreparedWorkspaceChange {
        val proposal = proposal(request.change)
        val changeSet = proposal.changeSet
            ?: return rejectedPrepared(proposal.issues)
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    fun previewMutation(request: RestApiContractMutationRequest): WorkspaceChangePreviewResponse {
        val proposal = mutationProposal(request)
        return proposal.changeSet?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues, "REST contract change rejected")
    }

    fun prepareMutation(request: RestApiContractMutationApplyRequest): PreparedWorkspaceChange {
        val proposal = mutationProposal(request.change)
        val changeSet = proposal.changeSet ?: return rejectedPrepared(
            proposal.issues,
            "REST contract change rejected",
        )
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    internal fun proposal(request: RestApiContractAdditionRequest): RestApiChangeProposal {
        val expectedKind = when (request.contract) {
            is RestApiContractInput.ServiceMethod -> "SERVICES"
            is RestApiContractInput.Query -> "QUERIES"
        }
        val config = RestApiWorkspaceService.getInstance(project).load().configs.firstOrNull {
            it.sourceLocator.relativePath == request.configLocator.relativePath && it.kind == expectedKind
        } ?: return failure(
            "JVW-REST-CONFIG-NOT-INDEXED",
            "The selected $expectedKind configuration is no longer indexed. Refresh the API workspace.",
            request.configLocator.relativePath,
        )
        if (config.moduleId != request.moduleId) {
            return failure(
                "JVW-REST-CONFIG-MODULE-MISMATCH",
                "The selected REST configuration moved to another module. Refresh before editing.",
                request.configLocator.relativePath,
            )
        }
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.configLocator.relativePath)
            ?: return failure(
                "JVW-REST-CONFIG-MISSING",
                "The selected REST configuration cannot be resolved inside registered project roots.",
                request.configLocator.relativePath,
            )
        val source = runCatching {
            String(resolved.file.contentsToByteArray(false), resolved.file.charset)
        }.getOrElse {
            return failure(
                "JVW-REST-CONFIG-UNREADABLE",
                "The selected REST configuration cannot be read.",
                request.configLocator.relativePath,
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(source)
        if (
            request.configLocator.revisionFingerprint.isBlank() ||
            request.configLocator.revisionFingerprint != fingerprint
        ) {
            return failure(
                "JVW-REST-CONFIG-STALE",
                "The REST configuration changed after it was indexed. Refresh and review the latest source.",
                request.configLocator.relativePath,
            )
        }
        val xmlContract = when (val contract = request.contract) {
            is RestApiContractInput.ServiceMethod -> RestApiXmlContract.ServiceMethod(
                contract.serviceName.trim(),
                contract.methodName.trim(),
                contract.parameters.map { RestApiXmlParameter(it.name.trim(), it.javaType.trim()) },
            )
            is RestApiContractInput.Query -> RestApiXmlContract.Query(
                contract.name.trim(),
                contract.entityName.trim(),
                contract.fetchPlan.trim(),
                contract.jpql.trim(),
                contract.parameters.map { RestApiXmlParameter(it.name.trim(), it.javaType.trim()) },
            )
        }
        val edit = runCatching { RestApiSourcePatcher.add(source, xmlContract) }.getOrElse { error ->
            val message = error.message ?: "The REST contract cannot be added safely."
            val code = message.substringBefore(':').takeIf { it.startsWith("JVW-") }
                ?: "JVW-REST-CONTRACT-REJECTED"
            return failure(code, message.substringAfter(": ", message), request.configLocator.relativePath)
        }
        val identity = when (val contract = request.contract) {
            is RestApiContractInput.ServiceMethod ->
                "${contract.serviceName}#${contract.methodName}(${contract.parameters.joinToString { it.javaType }})"
            is RestApiContractInput.Query -> "${contract.entityName}:${contract.name}"
        }
        val digest = CanonicalDiscoveryJson.sha256(
            listOf(request.configLocator.relativePath, expectedKind, identity, edit.replacement)
                .joinToString("\u0000"),
        ).take(24)
        return RestApiChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "rest-contract-add:$digest",
                label = "Expose Jmix REST contract $identity",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.configLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = fingerprint,
                        edits = listOf(edit),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    internal fun mutationProposal(request: RestApiContractMutationRequest): RestApiChangeProposal {
        val expectedKind = when (request.target) {
            is RestApiContractTargetInput.ServiceMethod -> "SERVICES"
            is RestApiContractTargetInput.Query -> "QUERIES"
        }
        val config = RestApiWorkspaceService.getInstance(project).load().configs.firstOrNull {
            it.sourceLocator.relativePath == request.configLocator.relativePath && it.kind == expectedKind
        } ?: return failure(
            "JVW-REST-CONFIG-NOT-INDEXED",
            "The selected $expectedKind configuration is no longer indexed. Refresh the API workspace.",
            request.configLocator.relativePath,
        )
        if (config.moduleId != request.moduleId) {
            return failure(
                "JVW-REST-CONFIG-MODULE-MISMATCH",
                "The selected REST configuration moved to another module. Refresh before editing.",
                request.configLocator.relativePath,
            )
        }
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(request.configLocator.relativePath)
            ?: return failure(
                "JVW-REST-CONFIG-MISSING",
                "The selected REST configuration cannot be resolved inside registered project roots.",
                request.configLocator.relativePath,
            )
        val source = runCatching {
            String(resolved.file.contentsToByteArray(false), resolved.file.charset)
        }.getOrElse {
            return failure(
                "JVW-REST-CONFIG-UNREADABLE",
                "The selected REST configuration cannot be read.",
                request.configLocator.relativePath,
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(source)
        if (
            request.configLocator.revisionFingerprint.isBlank() ||
            request.configLocator.revisionFingerprint != fingerprint
        ) {
            return failure(
                "JVW-REST-CONFIG-STALE",
                "The REST configuration changed after it was indexed. Refresh and review the latest source.",
                request.configLocator.relativePath,
            )
        }
        val target = when (val input = request.target) {
            is RestApiContractTargetInput.ServiceMethod -> RestApiXmlTarget.ServiceMethod(
                input.serviceName,
                input.methodName,
                input.parameterTypes,
            )
            is RestApiContractTargetInput.Query -> RestApiXmlTarget.Query(input.name, input.entityName)
        }
        val replacement = request.replacement?.toXmlContract()
        if (request.mode == RestApiContractMutationMode.UPDATE && replacement == null) {
            return failure(
                "JVW-REST-CONTRACT-REPLACEMENT-MISSING",
                "An updated contract is required for update mode.",
                request.configLocator.relativePath,
            )
        }
        val edits = runCatching {
            when (request.mode) {
                RestApiContractMutationMode.UPDATE ->
                    RestApiSourcePatcher.update(source, target, requireNotNull(replacement))
                RestApiContractMutationMode.REMOVE -> RestApiSourcePatcher.remove(source, target)
            }
        }.getOrElse { error ->
            val message = error.message ?: "The REST contract cannot be changed safely."
            val code = message.substringBefore(':').takeIf { it.startsWith("JVW-") }
                ?: "JVW-REST-CONTRACT-REJECTED"
            return failure(code, message.substringAfter(": ", message), request.configLocator.relativePath)
        }
        val targetIdentity = when (val input = request.target) {
            is RestApiContractTargetInput.ServiceMethod ->
                "${input.serviceName}#${input.methodName}(${input.parameterTypes.joinToString()})"
            is RestApiContractTargetInput.Query -> "${input.entityName}:${input.name}"
        }
        val digest = CanonicalDiscoveryJson.sha256(
            listOf(
                request.configLocator.relativePath,
                request.mode.name,
                targetIdentity,
                edits.joinToString("\u0001") { "${it.startOffset}:${it.endOffset}:${it.replacement}" },
            ).joinToString("\u0000"),
        ).take(24)
        return RestApiChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "rest-contract-${request.mode.name.lowercase()}:$digest",
                label = "${request.mode.displayName} Jmix REST contract $targetIdentity",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.configLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = fingerprint,
                        edits = edits,
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun rejectedPreview(
        issues: List<WorkspaceChangeIssue>,
        label: String = "REST contract addition rejected",
    ) = WorkspaceChangePreviewResponse(
        accepted = false,
        changeSetId = "rest-contract-add:rejected",
        label = label,
        planDigest = null,
        files = emptyList(),
        issues = issues,
    )

    private fun rejectedPrepared(
        issues: List<WorkspaceChangeIssue>,
        label: String = "REST contract addition rejected",
    ) = PreparedWorkspaceChange(
        plan = WorkspaceChangePlan(
            accepted = false,
            changeSetId = "rest-contract-add:rejected",
            label = label,
            planDigest = null,
            files = emptyList(),
            issues = issues,
        ),
        baseDir = null,
    )

    private fun failure(code: String, message: String, path: String) = RestApiChangeProposal(
        changeSet = null,
        issues = listOf(WorkspaceChangeIssue(code, message, path)),
    )

    companion object {
        fun getInstance(project: Project): RestApiChangeService =
            project.getService(RestApiChangeService::class.java)
    }
}

private fun RestApiContractInput.toXmlContract(): RestApiXmlContract = when (this) {
    is RestApiContractInput.ServiceMethod -> RestApiXmlContract.ServiceMethod(
        serviceName.trim(),
        methodName.trim(),
        parameters.map { RestApiXmlParameter(it.name.trim(), it.javaType.trim()) },
    )
    is RestApiContractInput.Query -> RestApiXmlContract.Query(
        name.trim(),
        entityName.trim(),
        fetchPlan.trim(),
        jpql.trim(),
        parameters.map { RestApiXmlParameter(it.name.trim(), it.javaType.trim()) },
    )
}

sealed interface RestApiContractInput {
    data class ServiceMethod(
        val serviceName: String,
        val methodName: String,
        val parameters: List<RestApiContractParameterInput> = emptyList(),
    ) : RestApiContractInput

    data class Query(
        val name: String,
        val entityName: String,
        val fetchPlan: String,
        val jpql: String,
        val parameters: List<RestApiContractParameterInput> = emptyList(),
    ) : RestApiContractInput
}

data class RestApiContractParameterInput(
    val name: String,
    val javaType: String = "",
)

data class RestApiContractAdditionRequest(
    val moduleId: String,
    val configLocator: SourceLocator,
    val contract: RestApiContractInput,
)

data class RestApiContractAdditionApplyRequest(
    val change: RestApiContractAdditionRequest,
    val expectedPlanDigest: String,
)

sealed interface RestApiContractTargetInput {
    data class ServiceMethod(
        val serviceName: String,
        val methodName: String,
        val parameterTypes: List<String>,
    ) : RestApiContractTargetInput

    data class Query(
        val name: String,
        val entityName: String,
    ) : RestApiContractTargetInput
}

enum class RestApiContractMutationMode(val displayName: String) {
    UPDATE("Update"),
    REMOVE("Remove"),
}

data class RestApiContractMutationRequest(
    val moduleId: String,
    val configLocator: SourceLocator,
    val mode: RestApiContractMutationMode,
    val target: RestApiContractTargetInput,
    val replacement: RestApiContractInput? = null,
)

data class RestApiContractMutationApplyRequest(
    val change: RestApiContractMutationRequest,
    val expectedPlanDigest: String,
)

internal data class RestApiChangeProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

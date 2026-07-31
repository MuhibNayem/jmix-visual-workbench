package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.generator.IntegrationConnectorGenerator
import org.jmixworkbench.model.IntegrationConnectorModel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Native trust boundary for contract-changing regeneration. The browser can
 * request a review, but cannot mint a capability or broaden its scope.
 */
@Service(Service.Level.PROJECT)
class OpenApiEvolutionApprovalService @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") project: Project,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val approvals = ConcurrentHashMap<String, Approval>()

    fun issue(
        previous: IntegrationConnectorModel,
        candidate: IntegrationConnectorModel,
        sourceRevision: String,
        report: OpenApiEvolutionReport,
    ): OpenApiEvolutionApproval {
        require(previous.openApiBaseline != null) {
            "The connector predates semantic OpenAPI baselines and cannot be migrated automatically."
        }
        require(candidate.resolvedOpenApiOperation != null) {
            "The candidate OpenAPI operation was not resolved by the backend."
        }
        require(previous.openApiBinding?.documentSha256 == report.baselineSha256) {
            "The evolution report does not belong to the connector's persisted contract."
        }
        require(candidate.openApiBinding?.documentSha256 == report.candidateSha256) {
            "The evolution report does not belong to the candidate contract."
        }
        pruneExpired()
        val token = UUID.randomUUID().toString()
        val expiresAt = clock.instant().plus(APPROVAL_TTL)
        approvals[token] = Approval(
            sourceRevision = sourceRevision,
            baselineSha256 = report.baselineSha256,
            candidateSha256 = report.candidateSha256,
            reportDigest = report.reportDigest,
            modelDigest = modelDigest(candidate),
            expiresAt = expiresAt,
        )
        return OpenApiEvolutionApproval(
            capability = token,
            expiresAt = expiresAt,
            reportDigest = report.reportDigest,
            wireImpact = report.wireImpact,
            sourceImpact = report.sourceImpact,
        )
    }

    fun validate(
        previous: IntegrationConnectorModel,
        candidate: IntegrationConnectorModel,
        sourceRevision: String,
        report: OpenApiEvolutionReport,
    ): WorkspaceChangeIssue? {
        val token = candidate.openApiEvolutionCapability ?: return issue(
            "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-REQUIRED",
            "The provider contract changed. Review its semantic impact and approve regeneration in native IntelliJ UI.",
        )
        val approval = approvals[token] ?: return issue(
            "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-INVALID",
            "The OpenAPI evolution approval is unknown or expired. Request a new native approval.",
        )
        if (approval.expiresAt <= clock.instant()) {
            approvals.remove(token, approval)
            return issue(
                "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-EXPIRED",
                "The OpenAPI evolution approval expired. Review the current contract again.",
            )
        }
        pruneExpired()
        val mismatch = previous.openApiBinding?.documentSha256 != approval.baselineSha256 ||
            candidate.openApiBinding?.documentSha256 != approval.candidateSha256 ||
            sourceRevision != approval.sourceRevision ||
            report.reportDigest != approval.reportDigest ||
            modelDigest(candidate) != approval.modelDigest
        return if (mismatch) {
            issue(
                "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-SCOPE-MISMATCH",
                "The connector, source revision, contract, semantic report, or mapping decisions changed after approval.",
            )
        } else {
            null
        }
    }

    private fun modelDigest(model: IntegrationConnectorModel): String =
        CanonicalDiscoveryJson.sha256(IntegrationConnectorGenerator.encode(model))

    private fun issue(code: String, message: String) = WorkspaceChangeIssue(code, message)

    private fun pruneExpired() {
        val now = clock.instant()
        approvals.entries.removeIf { it.value.expiresAt <= now }
    }

    companion object {
        private val APPROVAL_TTL: Duration = Duration.ofMinutes(5)

        fun getInstance(project: Project): OpenApiEvolutionApprovalService =
            project.getService(OpenApiEvolutionApprovalService::class.java)
    }

    private data class Approval(
        val sourceRevision: String,
        val baselineSha256: String,
        val candidateSha256: String,
        val reportDigest: String,
        val modelDigest: String,
        val expiresAt: Instant,
    )
}

data class OpenApiEvolutionApproval(
    val capability: String,
    val expiresAt: Instant,
    val reportDigest: String,
    val wireImpact: OpenApiEvolutionImpact,
    val sourceImpact: OpenApiEvolutionImpact,
)

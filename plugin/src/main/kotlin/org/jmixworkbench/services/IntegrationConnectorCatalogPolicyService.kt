package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationConnectorCatalogBinding
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationRetryMode
import org.jmixworkbench.project.JmixConnectorCatalogOption
import org.jmixworkbench.project.JmixOrganizationConnectorRisk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces signed organization connector policy at the trusted project
 * boundary. Catalog presets are constraints, not browser-authored defaults.
 */
@Service(Service.Level.PROJECT)
class IntegrationConnectorCatalogPolicyService(
    private val project: Project,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val approvals = ConcurrentHashMap<String, Approval>()

    fun issueApproval(
        option: JmixConnectorCatalogOption,
        destinationId: String,
    ): IntegrationConnectorCatalogApproval {
        require(option.template.policy.risk != JmixOrganizationConnectorRisk.STANDARD) {
            "Standard organization connector templates do not require native approval."
        }
        pruneExpired()
        val token = UUID.randomUUID().toString()
        val expiresAt = clock.instant().plus(APPROVAL_TTL)
        val binding = IntegrationConnectorCatalogBinding(
            catalogId = option.catalogId,
            catalogVersion = option.catalogVersion,
            bundleSha256 = option.bundleSha256,
            templateId = option.template.id,
            templateVersion = option.template.version,
        )
        approvals[token] = Approval(
            binding = binding,
            destinationId = destinationId,
            approvalPolicyId = requireNotNull(option.template.policy.approvalPolicyId),
            expiresAt = expiresAt,
        )
        return IntegrationConnectorCatalogApproval(
            capability = token,
            expiresAt = expiresAt,
            approvalPolicyId = requireNotNull(option.template.policy.approvalPolicyId),
        )
    }

    fun validate(
        model: IntegrationConnectorModel,
        option: JmixConnectorCatalogOption,
        destination: IntegrationConnectorDestinationSnapshot,
    ): List<WorkspaceChangeIssue> {
        val template = option.template
        val policy = template.policy
        val issues = mutableListOf<WorkspaceChangeIssue>()
        fun reject(code: String, message: String) {
            issues += WorkspaceChangeIssue(code, message)
        }

        if (model.kind != template.kind) {
            reject(
                "JVW-INTEGRATION-CATALOG-KIND-MISMATCH",
                "The signed connector template requires ${template.kind}; the submitted model uses ${model.kind}.",
            )
        }
        if (!template.supports(destination.springBootApi, destination.capabilities)) {
            reject(
                "JVW-INTEGRATION-CATALOG-INCOMPATIBLE",
                "The signed connector template is not compatible with the selected module's Spring Boot API or indexed dependencies.",
            )
        }
        val expectedAddress = "${model.configurationPrefix}.${template.addressPropertySuffix}"
        if (
            model.configurationPrefix != template.configurationPrefixSuffix &&
            !model.configurationPrefix.endsWith(".${template.configurationPrefixSuffix}")
        ) {
            reject(
                "JVW-INTEGRATION-CATALOG-PREFIX-MISMATCH",
                "Configuration prefix must end with '${template.configurationPrefixSuffix}'.",
            )
        }
        if (model.addressProperty != expectedAddress) {
            reject(
                "JVW-INTEGRATION-CATALOG-ADDRESS-MISMATCH",
                "Endpoint or destination must use the catalog-owned property '$expectedAddress'.",
            )
        }
        template.headers.forEach { required ->
            val expectedProperty = "${model.configurationPrefix}.${required.propertySuffix}"
            val actual = model.headers.singleOrNull { it.name.equals(required.name, ignoreCase = true) }
            if (actual == null) {
                reject(
                    "JVW-INTEGRATION-CATALOG-HEADER-MISSING",
                    "Signed connector policy requires header '${required.name}'.",
                )
            } else if (
                actual.valueProperty != expectedProperty ||
                actual.sensitive != required.sensitive
            ) {
                reject(
                    "JVW-INTEGRATION-CATALOG-HEADER-MISMATCH",
                    "Header '${required.name}' must use '$expectedProperty' with sensitive=${required.sensitive}.",
                )
            }
        }
        policy.requiredAuthentication?.let { required ->
            if (model.authentication.kind != required) {
                reject(
                    "JVW-INTEGRATION-CATALOG-AUTH-MISMATCH",
                    "Signed connector policy requires ${required.name.replace('_', ' ')} authentication.",
                )
            }
        }
        if (policy.requireMutualTls && !model.transportSecurity.mutualTlsEnabled) {
            reject(
                "JVW-INTEGRATION-CATALOG-MTLS-REQUIRED",
                "Signed connector policy requires mutual TLS through a named SSL bundle.",
            )
        }
        if (
            model.reliability.connectTimeoutMs > policy.maximumConnectTimeoutMs ||
            model.reliability.requestTimeoutMs > policy.maximumRequestTimeoutMs
        ) {
            reject(
                "JVW-INTEGRATION-CATALOG-TIMEOUT-EXCEEDED",
                "Connector timeouts exceed the signed policy maximum of " +
                    "${policy.maximumConnectTimeoutMs}/${policy.maximumRequestTimeoutMs} ms.",
            )
        }
        if (
            policy.minimumRetryAttempts > 1 &&
            (
                model.reliability.retry.mode == IntegrationRetryMode.NONE ||
                    model.reliability.retry.attempts < policy.minimumRetryAttempts
                )
        ) {
            reject(
                "JVW-INTEGRATION-CATALOG-RETRY-BELOW-MINIMUM",
                "Signed connector policy requires at least ${policy.minimumRetryAttempts} bounded attempts.",
            )
        }
        if (policy.requireTransactional && !model.reliability.transactional) {
            reject(
                "JVW-INTEGRATION-CATALOG-TRANSACTION-REQUIRED",
                "Signed connector policy requires a transactional boundary.",
            )
        }
        if (policy.requireIdempotency && !model.reliability.idempotency.enabled) {
            reject(
                "JVW-INTEGRATION-CATALOG-IDEMPOTENCY-REQUIRED",
                "Signed connector policy requires idempotency.",
            )
        }
        if (policy.requireOutbox && !model.reliability.outboxEnabled) {
            reject(
                "JVW-INTEGRATION-CATALOG-OUTBOX-REQUIRED",
                "Signed connector policy requires the transactional outbox.",
            )
        }
        if (policy.requireInbox && !model.reliability.inboxEnabled) {
            reject(
                "JVW-INTEGRATION-CATALOG-INBOX-REQUIRED",
                "Signed connector policy requires the persistent inbox.",
            )
        }
        if (policy.requireMetrics && !model.observability.metricsEnabled) {
            reject("JVW-INTEGRATION-CATALOG-METRICS-REQUIRED", "Signed connector policy requires metrics.")
        }
        if (policy.requireTracing && !model.observability.tracingEnabled) {
            reject("JVW-INTEGRATION-CATALOG-TRACING-REQUIRED", "Signed connector policy requires tracing.")
        }
        if (policy.requireStructuredLogging && !model.observability.structuredLoggingEnabled) {
            reject(
                "JVW-INTEGRATION-CATALOG-LOGGING-REQUIRED",
                "Signed connector policy requires structured logging.",
            )
        }
        if (policy.requireAudit && !model.observability.auditEnabled) {
            reject("JVW-INTEGRATION-CATALOG-AUDIT-REQUIRED", "Signed connector policy requires audit events.")
        }
        policy.requiredObservabilityApi?.let { required ->
            if (model.observability.runtimeApi != required) {
                reject(
                    "JVW-INTEGRATION-CATALOG-OBSERVABILITY-MISMATCH",
                    "Signed connector policy requires observability API $required.",
                )
            }
        }
        if (
            model.kind == org.jmixworkbench.model.IntegrationConnectorKind.IDENTITY_PROVIDER &&
            policy.requiredAuthentication != null &&
            policy.requiredAuthentication != IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS
        ) {
            reject(
                "JVW-INTEGRATION-CATALOG-IDENTITY-AUTH-INVALID",
                "Identity provider templates can require only OAuth2 client credentials.",
            )
        }
        if (policy.risk != JmixOrganizationConnectorRisk.STANDARD) {
            validateApproval(model, option, destination)?.let(issues::add)
        }
        return issues
    }

    private fun validateApproval(
        model: IntegrationConnectorModel,
        option: JmixConnectorCatalogOption,
        destination: IntegrationConnectorDestinationSnapshot,
    ): WorkspaceChangeIssue? {
        val binding = model.catalogBinding ?: return WorkspaceChangeIssue(
            "JVW-INTEGRATION-CATALOG-APPROVAL-REQUIRED",
            "This signed connector template requires explicit native IntelliJ approval.",
        )
        val capability = binding.approvalCapability ?: return WorkspaceChangeIssue(
            "JVW-INTEGRATION-CATALOG-APPROVAL-REQUIRED",
            "This signed connector template requires explicit native IntelliJ approval.",
        )
        val approval = approvals[capability] ?: return WorkspaceChangeIssue(
            "JVW-INTEGRATION-CATALOG-APPROVAL-INVALID",
            "The connector approval is unknown or expired; request a new native approval.",
        )
        if (approval.expiresAt <= clock.instant()) {
            approvals.remove(capability, approval)
            return WorkspaceChangeIssue(
                "JVW-INTEGRATION-CATALOG-APPROVAL-EXPIRED",
                "The connector approval expired; request a new native approval.",
            )
        }
        pruneExpired()
        val immutableBinding = binding.copy(approvalCapability = null)
        val expectedBinding = approval.binding
        return when {
            immutableBinding != expectedBinding ||
                option.catalogId != expectedBinding.catalogId ||
                option.catalogVersion != expectedBinding.catalogVersion ||
                option.bundleSha256 != expectedBinding.bundleSha256 ||
                option.template.id != expectedBinding.templateId ||
                option.template.version != expectedBinding.templateVersion ||
                destination.id != approval.destinationId ||
                option.template.policy.approvalPolicyId != approval.approvalPolicyId ->
                WorkspaceChangeIssue(
                    "JVW-INTEGRATION-CATALOG-APPROVAL-SCOPE-MISMATCH",
                    "The native approval belongs to a different catalog template, policy, or module.",
                )

            else -> null
        }
    }

    private fun pruneExpired() {
        val now = clock.instant()
        approvals.entries.removeIf { it.value.expiresAt <= now }
    }

    companion object {
        private val APPROVAL_TTL: Duration = Duration.ofMinutes(5)

        fun getInstance(project: Project): IntegrationConnectorCatalogPolicyService =
            project.getService(IntegrationConnectorCatalogPolicyService::class.java)
    }

    private data class Approval(
        val binding: IntegrationConnectorCatalogBinding,
        val destinationId: String,
        val approvalPolicyId: String,
        val expiresAt: Instant,
    )
}

data class IntegrationConnectorCatalogApproval(
    val capability: String,
    val expiresAt: Instant,
    val approvalPolicyId: String,
)

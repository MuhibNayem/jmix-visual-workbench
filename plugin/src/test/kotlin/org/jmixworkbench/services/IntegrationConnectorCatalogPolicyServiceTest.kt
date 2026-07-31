package org.jmixworkbench.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jmixworkbench.generator.IntegrationConnectorGenerator
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationAuthenticationModel
import org.jmixworkbench.model.IntegrationCapability
import org.jmixworkbench.model.IntegrationConnectorCatalogBinding
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationHeaderModel
import org.jmixworkbench.model.IntegrationIdempotencyModel
import org.jmixworkbench.model.IntegrationJsonApi
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationObservabilityModel
import org.jmixworkbench.model.IntegrationReliabilityModel
import org.jmixworkbench.model.IntegrationRetryMode
import org.jmixworkbench.model.IntegrationRetryPolicyModel
import org.jmixworkbench.model.IntegrationSpringBootApi
import org.jmixworkbench.model.IntegrationTransportSecurityModel
import org.jmixworkbench.project.JmixConnectorCatalogOption
import org.jmixworkbench.project.JmixOrganizationConnectorHeader
import org.jmixworkbench.project.JmixOrganizationConnectorPolicy
import org.jmixworkbench.project.JmixOrganizationConnectorRisk
import org.jmixworkbench.project.JmixOrganizationConnectorTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntegrationConnectorCatalogPolicyServiceTest : BasePlatformTestCase() {
    private val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    fun testSignedPolicyRejectsDowngradeAndRequiresScopedNativeApproval() {
        val service = IntegrationConnectorCatalogPolicyService(project, clock)
        val option = option()
        val destination = destination()
        val binding = binding()
        val compliant = model(binding)

        val withoutApproval = service.validate(compliant, option, destination)
        assertTrue(
            withoutApproval.any { it.code == "JVW-INTEGRATION-CATALOG-APPROVAL-REQUIRED" },
        )

        val approval = service.issueApproval(option, destination.id)
        val approved = compliant.copy(
            catalogBinding = binding.copy(approvalCapability = approval.capability),
        )
        assertTrue(service.validate(approved, option, destination).isEmpty())
        val persistedMarker = String(
            Base64.getUrlDecoder().decode(IntegrationConnectorGenerator.encode(approved)),
            Charsets.UTF_8,
        )
        assertTrue(!persistedMarker.contains(approval.capability))
        assertTrue(persistedMarker.contains("\"templateId\":\"acme-identity\""))

        val wrongModule = destination.copy(id = "other:integration", moduleId = "other")
        assertTrue(
            service.validate(approved, option, wrongModule).any {
                it.code == "JVW-INTEGRATION-CATALOG-APPROVAL-SCOPE-MISMATCH"
            },
        )

        val downgraded = approved.copy(
            addressProperty = "attacker.endpoint",
            headers = emptyList(),
            authentication = IntegrationAuthenticationModel(),
            transportSecurity = IntegrationTransportSecurityModel(),
            reliability = approved.reliability.copy(
                connectTimeoutMs = 90_000,
                requestTimeoutMs = 700_000,
                retry = IntegrationRetryPolicyModel(),
                idempotency = IntegrationIdempotencyModel(),
            ),
            observability = IntegrationObservabilityModel(
                metricsEnabled = false,
                tracingEnabled = false,
                structuredLoggingEnabled = false,
                auditEnabled = false,
                runtimeApi = null,
            ),
        )
        val codes = service.validate(downgraded, option, destination).map { it.code }.toSet()
        assertTrue("JVW-INTEGRATION-CATALOG-ADDRESS-MISMATCH" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-HEADER-MISSING" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-AUTH-MISMATCH" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-MTLS-REQUIRED" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-TIMEOUT-EXCEEDED" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-RETRY-BELOW-MINIMUM" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-IDEMPOTENCY-REQUIRED" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-METRICS-REQUIRED" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-TRACING-REQUIRED" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-LOGGING-REQUIRED" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-AUDIT-REQUIRED" in codes)
        assertTrue("JVW-INTEGRATION-CATALOG-OBSERVABILITY-MISMATCH" in codes)
    }

    fun testApprovalCannotAuthorizeDifferentBundleOrTemplate() {
        val service = IntegrationConnectorCatalogPolicyService(project, clock)
        val option = option()
        val destination = destination()
        val approval = service.issueApproval(option, destination.id)
        val forged = model(
            binding().copy(
                bundleSha256 = "b".repeat(64),
                approvalCapability = approval.capability,
            ),
        )

        assertTrue(
            service.validate(forged, option, destination).any {
                it.code == "JVW-INTEGRATION-CATALOG-APPROVAL-SCOPE-MISMATCH"
            },
        )
    }

    fun testApprovalExpiresExactlyAndStandardTemplatesCannotMintCapabilities() {
        val movingClock = MutableClock(Instant.parse("2026-07-31T12:00:00Z"))
        val service = IntegrationConnectorCatalogPolicyService(project, movingClock)
        val option = option()
        val destination = destination()
        val approval = service.issueApproval(option, destination.id)
        val approved = model(binding().copy(approvalCapability = approval.capability))

        assertTrue(service.validate(approved, option, destination).isEmpty())
        movingClock.advance(Duration.ofMinutes(5))
        assertTrue(
            service.validate(approved, option, destination).any {
                it.code == "JVW-INTEGRATION-CATALOG-APPROVAL-EXPIRED"
            },
        )
        assertTrue(
            service.validate(approved, option, destination).any {
                it.code == "JVW-INTEGRATION-CATALOG-APPROVAL-INVALID"
            },
        )

        val standard = option.copy(
            template = option.template.copy(
                policy = option.template.policy.copy(
                    risk = JmixOrganizationConnectorRisk.STANDARD,
                    approvalPolicyId = null,
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            service.issueApproval(standard, destination.id)
        }
    }

    private fun destination() = IntegrationConnectorDestinationSnapshot(
        id = "loan:integration",
        moduleId = "loan",
        sourceRoot = "loan/src/main/java",
        resourceRoot = "loan/src/main/resources",
        defaultPackage = "com.acme.loan.integration",
        capabilities = setOf(
            IntegrationCapability.SPRING_WEB,
            IntegrationCapability.OAUTH2_CLIENT,
            IntegrationCapability.SPRING_BOOT_SSL_BUNDLES,
            IntegrationCapability.RESILIENCE4J,
        ),
        jsonApi = IntegrationJsonApi.JACKSON_2,
        observabilityApi = IntegrationObservabilityApi.MICROMETER_OBSERVATION,
        springBootApi = IntegrationSpringBootApi.BOOT_3,
        recommended = true,
    )

    private fun option(): JmixConnectorCatalogOption =
        JmixConnectorCatalogOption(
            catalogId = "acme.enterprise",
            catalogVersion = "1.4.0",
            bundleSha256 = "a".repeat(64),
            catalogDisplayName = "Acme Enterprise Connectors",
            template = JmixOrganizationConnectorTemplate(
                id = "acme-identity",
                version = "1.0.0",
                name = "Acme Identity",
                description = "Governed OAuth2 and mTLS identity connector.",
                order = 10,
                provider = "Acme IAM",
                kind = IntegrationConnectorKind.IDENTITY_PROVIDER,
                springBootApis = setOf(IntegrationSpringBootApi.BOOT_3),
                requiredCapabilities = setOf(
                    IntegrationCapability.SPRING_WEB,
                    IntegrationCapability.OAUTH2_CLIENT,
                    IntegrationCapability.SPRING_BOOT_SSL_BUNDLES,
                ),
                configurationPrefixSuffix = "acme.identity",
                addressPropertySuffix = "base-url",
                headers = listOf(
                    JmixOrganizationConnectorHeader(
                        name = "X-Correlation-ID",
                        propertySuffix = "correlation-id",
                        sensitive = false,
                    ),
                ),
                policy = JmixOrganizationConnectorPolicy(
                    risk = JmixOrganizationConnectorRisk.SENSITIVE,
                    approvalPolicyId = "acme.integration.sensitive",
                    requiredAuthentication = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
                    requireMutualTls = true,
                    requireTransactional = false,
                    requireIdempotency = true,
                    requireOutbox = false,
                    requireInbox = false,
                    maximumConnectTimeoutMs = 5_000,
                    maximumRequestTimeoutMs = 15_000,
                    minimumRetryAttempts = 3,
                    requireMetrics = true,
                    requireTracing = true,
                    requireStructuredLogging = true,
                    requireAudit = true,
                    requiredObservabilityApi = IntegrationObservabilityApi.MICROMETER_OBSERVATION,
                ),
            ),
        )

    private fun binding() = IntegrationConnectorCatalogBinding(
        catalogId = "acme.enterprise",
        catalogVersion = "1.4.0",
        bundleSha256 = "a".repeat(64),
        templateId = "acme-identity",
        templateVersion = "1.0.0",
    )

    private fun model(binding: IntegrationConnectorCatalogBinding) = IntegrationConnectorModel(
        name = "Acme Identity",
        destinationId = "loan:integration",
        packageName = "com.acme.loan.integration",
        className = "AcmeIdentityConnector",
        beanName = "acmeIdentityConnector",
        kind = IntegrationConnectorKind.IDENTITY_PROVIDER,
        configurationPrefix = "app.integration.acme.identity",
        addressProperty = "app.integration.acme.identity.base-url",
        headers = listOf(
            IntegrationHeaderModel(
                name = "X-Correlation-ID",
                valueProperty = "app.integration.acme.identity.correlation-id",
                sensitive = false,
            ),
        ),
        authentication = IntegrationAuthenticationModel(
            kind = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
            authorizedClientManagerBeanName = "authorizedClientManager",
            authorizedClientServiceBeanName = "authorizedClientService",
            clientRegistrationIdProperty = "app.integration.acme.identity.client-registration-id",
            principalNameProperty = "app.integration.acme.identity.principal-name",
        ),
        transportSecurity = IntegrationTransportSecurityModel(
            mutualTlsEnabled = true,
            sslBundleNameProperty = "app.integration.acme.identity.ssl-bundle",
        ),
        reliability = IntegrationReliabilityModel(
            connectTimeoutMs = 5_000,
            requestTimeoutMs = 15_000,
            retry = IntegrationRetryPolicyModel(
                mode = IntegrationRetryMode.BLOCKING,
                attempts = 3,
            ),
            idempotency = IntegrationIdempotencyModel(enabled = true),
        ),
        observability = IntegrationObservabilityModel(
            metricsEnabled = true,
            tracingEnabled = true,
            structuredLoggingEnabled = true,
            auditEnabled = true,
            runtimeApi = IntegrationObservabilityApi.MICROMETER_OBSERVATION,
        ),
        catalogBinding = binding,
    )

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}

package org.jmixworkbench.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jmixworkbench.generator.IntegrationConnectorGenerator
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationHttpMethod
import org.jmixworkbench.model.IntegrationOpenApiBinding
import org.jmixworkbench.model.IntegrationOpenApiOperationModel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenApiEvolutionApprovalServiceTest : BasePlatformTestCase() {
    fun testCapabilityIsExactShortLivedAndNeverPersisted() {
        val clock = MutableClock(Instant.parse("2026-07-31T12:00:00Z"))
        val service = OpenApiEvolutionApprovalService(project, clock)
        val previousOperation = operation("a".repeat(64), "findEmployee")
        val candidateOperation = operation("b".repeat(64), "findEmployeeV2")
        val previous = model(previousOperation).copy(openApiBaseline = previousOperation)
        val candidate = model(candidateOperation).copy(
            openApiBaseline = candidateOperation,
            resolvedOpenApiOperation = candidateOperation,
        )
        val report = OpenApiContractEvolutionAnalyzer.compare(previousOperation, candidateOperation)
        val approval = service.issue(previous, candidate, "source-revision", report)
        val approved = candidate.copy(openApiEvolutionCapability = approval.capability)

        assertNull(service.validate(previous, approved, "source-revision", report))
        assertTrue(
            service.validate(previous, approved.copy(description = "tampered"), "source-revision", report)
                ?.code == "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-SCOPE-MISMATCH",
        )
        assertTrue(
            service.validate(previous, approved, "different-revision", report)
                ?.code == "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-SCOPE-MISMATCH",
        )
        val marker = String(
            Base64.getUrlDecoder().decode(IntegrationConnectorGenerator.encode(approved)),
            Charsets.UTF_8,
        )
        assertTrue(approval.capability !in marker)

        clock.advance(Duration.ofMinutes(5))
        assertTrue(
            service.validate(previous, approved, "source-revision", report)
                ?.code == "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-EXPIRED",
        )
        assertTrue(
            service.validate(previous, approved, "source-revision", report)
                ?.code == "JVW-INTEGRATION-OPENAPI-EVOLUTION-APPROVAL-INVALID",
        )
    }

    private fun model(operation: IntegrationOpenApiOperationModel) = IntegrationConnectorModel(
        name = "HR provider",
        destinationId = "payroll:integration",
        packageName = "com.acme.payroll.integration",
        className = "HrProviderConnector",
        beanName = "hrProviderConnector",
        kind = IntegrationConnectorKind.HTTP_CLIENT,
        configurationPrefix = "hr.provider",
        addressProperty = "hr.provider.base-url",
        openApiBinding = binding(operation),
    )

    private fun binding(operation: IntegrationOpenApiOperationModel) = IntegrationOpenApiBinding(
        relativePath = operation.contractPath,
        documentSha256 = operation.contractSha256,
        specificationVersion = operation.specificationVersion,
        operationId = operation.operationId,
        method = operation.method,
        path = operation.path,
        requestMediaType = operation.requestMediaType,
        responseStatus = operation.responseStatus,
        responseMediaType = operation.responseMediaType,
    )

    private fun operation(digest: String, operationId: String) = IntegrationOpenApiOperationModel(
        contractPath = "openapi/hr.yaml",
        contractSha256 = digest,
        specificationVersion = "3.1.1",
        title = "HR API",
        apiVersion = if (digest.startsWith('a')) "1" else "2",
        operationId = operationId,
        javaMethodName = operationId,
        method = IntegrationHttpMethod.GET,
        path = "/employees/{id}",
        deprecated = false,
        requestMediaType = null,
        requestRequired = false,
        requestSchemaId = null,
        responseStatus = "200",
        responseMediaType = "application/json",
        responseSchemaId = null,
        parameters = emptyList(),
        schemas = emptyList(),
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

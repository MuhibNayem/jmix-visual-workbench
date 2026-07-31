package org.jmixworkbench.generator

import org.jmixworkbench.model.EntityType
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationHttpMethod
import org.jmixworkbench.model.IntegrationOpenApiExistingEntityBinding
import org.jmixworkbench.model.IntegrationOpenApiJmixLayerModel
import org.jmixworkbench.model.IntegrationOpenApiJmixTargetKind
import org.jmixworkbench.model.IntegrationOpenApiJmixTypeMapping
import org.jmixworkbench.model.IntegrationOpenApiMappingDirection
import org.jmixworkbench.model.IntegrationOpenApiOperationModel
import org.jmixworkbench.model.IntegrationOpenApiPropertyMapping
import org.jmixworkbench.model.IntegrationOpenApiPropertyModel
import org.jmixworkbench.model.IntegrationOpenApiSchemaKind
import org.jmixworkbench.model.IntegrationOpenApiSchemaModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class OpenApiJmixLayerGeneratorTest {
    @Test
    fun `generates nested DTO enum mapper and application service as one deterministic layer`() {
        val input = input(
            mappings = listOf(
                generated("request", "PaymentRequest"),
                generated("receipt", "PaymentReceipt", id = "id"),
                generated("line", "PaymentLine"),
            ),
        )

        val result = OpenApiJmixLayerGenerator.generate(input)

        assertTrue(result.issues.isEmpty(), result.issues.joinToString())
        assertTrue(result == OpenApiJmixLayerGenerator.generate(input))
        val request = result.sources.single { it.className == "PaymentRequest" }.content
        val receipt = result.sources.single { it.className == "PaymentReceipt" }.content
        val enum = result.sources.single { it.className == "PaymentStatus" }.content
        val mapper = result.sources.single { it.role == "JMIX_MAPPER" }.content
        val service = result.sources.single { it.role == "APPLICATION_SERVICE" }.content
        assertContains(request, "private java.util.List<com.acme.entity.integration.PaymentLine> lines;")
        assertContains(receipt, "@JmixId")
        assertContains(enum, "ACCEPTED(\"accepted\")")
        assertContains(mapper, "metadata.create(com.acme.entity.integration.PaymentReceipt.class)")
        assertContains(mapper, "entityStates.setNew(target, false)")
        assertContains(mapper, ".stream().map(value -> toPaymentLine(value)).toList()")
        assertContains(service, "connector.submitPayment(accountId, mapper.toPaymentRequest(requestEntity))")
        assertContains(service, "return mapper.toPaymentReceipt(response)")
    }

    @Test
    fun `maps compatible indexed entity fields and rejects read only writes`() {
        val binding = IntegrationOpenApiExistingEntityBinding(
            artifactId = "entity:receipt",
            qualifiedName = "com.acme.domain.ExternalReceipt",
            revisionFingerprint = "a".repeat(64),
        )
        val existing = OpenApiJmixLayerGenerator.ResolvedEntityTarget(
            artifactId = binding.artifactId,
            qualifiedName = binding.qualifiedName,
            entityType = EntityType.DTO,
            attributes = listOf(
                OpenApiJmixLayerGenerator.ResolvedEntityAttribute("id", "java.util.UUID", false),
                OpenApiJmixLayerGenerator.ResolvedEntityAttribute("status", "java.lang.String", false),
                OpenApiJmixLayerGenerator.ResolvedEntityAttribute("total", "java.lang.Double", false),
            ),
        )
        val receiptMapping = IntegrationOpenApiJmixTypeMapping(
            schemaId = "receipt",
            targetKind = IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY,
            existingEntity = binding,
            idProperty = "id",
            properties = listOf(
                property("id"),
                property("status"),
                property("total"),
            ),
        )
        val input = input(
            mappings = listOf(
                generated("request", "PaymentRequest"),
                receiptMapping,
                generated("line", "PaymentLine"),
            ),
            existingTargets = mapOf("receipt" to existing),
        )

        val result = OpenApiJmixLayerGenerator.generate(input)

        assertTrue(result.issues.isEmpty(), result.issues.joinToString())
        val mapper = result.sources.single { it.role == "JMIX_MAPPER" }.content
        assertContains(mapper, "target.setStatus(source.status() == null ? null : source.status().value())")
        assertContains(mapper, "target.setTotal(source.total() == null ? null : source.total().doubleValue())")
        assertContains(mapper, "CertifiedPaymentClient.PaymentStatus.fromValue(source.getStatus())")
        assertContains(mapper, "java.math.BigDecimal.valueOf(source.getTotal().doubleValue())")

        val blocked = OpenApiJmixLayerGenerator.generate(
            input.copy(
                existingTargets = mapOf(
                    "receipt" to existing.copy(
                        attributes = existing.attributes.map {
                            if (it.name == "status") it.copy(readOnly = true) else it
                        },
                    ),
                ),
            ),
        )
        assertTrue(blocked.issues.any { "read-only entity property 'status'" in it })
    }

    private fun input(
        mappings: List<IntegrationOpenApiJmixTypeMapping>,
        existingTargets: Map<String, OpenApiJmixLayerGenerator.ResolvedEntityTarget> = emptyMap(),
    ) = OpenApiJmixLayerGenerator.Input(
        connector = connector(),
        operation = operation(),
        layer = IntegrationOpenApiJmixLayerModel(
            enabled = true,
            dtoPackage = "com.acme.entity.integration",
            mapperPackage = "com.acme.integration.mapper",
            servicePackage = "com.acme.service.integration",
            serviceClassName = "CertifiedPaymentService",
            serviceBeanName = "certifiedPaymentService",
            mappings = mappings,
        ),
        entityNamePrefix = "cert",
        existingTargets = existingTargets,
    )

    private fun generated(schemaId: String, className: String, id: String? = null) =
        IntegrationOpenApiJmixTypeMapping(
            schemaId = schemaId,
            targetKind = IntegrationOpenApiJmixTargetKind.GENERATED_DTO,
            generatedClassName = className,
            idProperty = id,
        )

    private fun property(name: String) = IntegrationOpenApiPropertyMapping(
        schemaProperty = name,
        entityProperty = name,
        direction = IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
    )

    private fun connector() = IntegrationConnectorModel(
        name = "Certified payment",
        destinationId = "cert:main",
        packageName = "com.acme.integration",
        className = "CertifiedPaymentClient",
        beanName = "certifiedPaymentClient",
        kind = IntegrationConnectorKind.HTTP_CLIENT,
        configurationPrefix = "cert.payment",
        addressProperty = "cert.payment.url",
        payloadJavaType = "com.acme.integration.CertifiedPaymentClient.PaymentRequest",
        responseJavaType = "com.acme.integration.CertifiedPaymentClient.PaymentReceipt",
        httpMethod = IntegrationHttpMethod.POST,
    )

    private fun operation() = IntegrationOpenApiOperationModel(
        contractPath = "src/main/resources/openapi/payment.yaml",
        contractSha256 = "b".repeat(64),
        specificationVersion = "3.1.1",
        title = "Payment",
        apiVersion = "1",
        operationId = "submitPayment",
        javaMethodName = "submitPayment",
        method = IntegrationHttpMethod.POST,
        path = "/payments/{accountId}",
        deprecated = false,
        requestMediaType = "application/json",
        requestRequired = true,
        requestSchemaId = "request",
        responseStatus = "201",
        responseMediaType = "application/json",
        responseSchemaId = "receipt",
        parameters = listOf(
            org.jmixworkbench.model.IntegrationOpenApiParameterModel(
                wireName = "accountId",
                javaName = "accountId",
                location = org.jmixworkbench.model.IntegrationOpenApiParameterLocation.PATH,
                schemaId = "uuid",
                required = true,
                style = "simple",
                explode = false,
            ),
        ),
        schemas = listOf(
            IntegrationOpenApiSchemaModel("uuid", "Uuid", IntegrationOpenApiSchemaKind.UUID),
            IntegrationOpenApiSchemaModel("amount", "Amount", IntegrationOpenApiSchemaKind.NUMBER),
            IntegrationOpenApiSchemaModel(
                "status",
                "PaymentStatus",
                IntegrationOpenApiSchemaKind.STRING,
                enumValues = listOf("accepted", "rejected"),
            ),
            IntegrationOpenApiSchemaModel(
                "line",
                "PaymentLine",
                IntegrationOpenApiSchemaKind.OBJECT,
                properties = listOf(
                    IntegrationOpenApiPropertyModel("amount", "amount", "amount", true, false),
                ),
            ),
            IntegrationOpenApiSchemaModel(
                "lines",
                "PaymentLines",
                IntegrationOpenApiSchemaKind.ARRAY,
                itemSchemaId = "line",
            ),
            IntegrationOpenApiSchemaModel(
                "request",
                "PaymentRequest",
                IntegrationOpenApiSchemaKind.OBJECT,
                properties = listOf(
                    IntegrationOpenApiPropertyModel("lines", "lines", "lines", true, false),
                ),
            ),
            IntegrationOpenApiSchemaModel(
                "receipt",
                "PaymentReceipt",
                IntegrationOpenApiSchemaKind.OBJECT,
                properties = listOf(
                    IntegrationOpenApiPropertyModel("id", "id", "uuid", true, false, readOnly = true),
                    IntegrationOpenApiPropertyModel("status", "status", "status", true, false, readOnly = true),
                    IntegrationOpenApiPropertyModel("total", "total", "amount", true, false, readOnly = true),
                ),
            ),
        ),
    )
}

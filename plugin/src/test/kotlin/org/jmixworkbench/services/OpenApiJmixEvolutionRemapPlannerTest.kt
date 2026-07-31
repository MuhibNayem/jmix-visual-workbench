package org.jmixworkbench.services

import org.jmixworkbench.model.IntegrationHttpMethod
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiJmixEvolutionRemapPlannerTest {
    @Test
    fun `offers explicit property candidates for a renamed compatible schema`() {
        val previous = operation(
            rootId = "EmployeeRecord",
            rootName = "EmployeeRecord",
            properties = listOf(
                property("employeeId", "EmployeeId"),
                property("fullName", "Text"),
                property("active", "Flag"),
            ),
        )
        val candidate = operation(
            rootId = "WorkerProfile",
            rootName = "WorkerProfile",
            properties = listOf(
                property("workerId", "EmployeeId"),
                property("displayName", "Text"),
                property("enabled", "Flag"),
            ),
        )
        val layer = layer(
            listOf(
                mapping("employeeId", "id"),
                mapping("fullName", "name"),
                mapping("active", "enabled"),
            ),
        )

        val plan = OpenApiJmixEvolutionRemapPlanner.plan(previous, candidate, layer).single()
        val option = plan.options.single { it.candidateSchemaId == "WorkerProfile" }

        assertEquals(OpenApiRemapConfidence.REVIEW, option.confidence)
        assertEquals(3, option.compatiblePropertyMatches)
        assertTrue(option.requiredOutboundUnmapped.isEmpty())
        assertTrue(option.propertyCandidates.any {
            it.candidateSchemaProperty == "workerId" &&
                it.previousEntityProperty == "id" &&
                it.reason == "Unique compatible property shape"
        })
        assertTrue(option.propertyCandidates.any {
            it.candidateSchemaProperty == "displayName" && it.previousEntityProperty == "name"
        })
    }

    @Test
    fun `ranks exact schema and property identities first`() {
        val previous = operation(
            rootId = "EmployeeRecord",
            rootName = "EmployeeRecord",
            properties = listOf(property("employeeId", "EmployeeId")),
        )
        val candidate = previous.copy(contractSha256 = "b".repeat(64))

        val option = OpenApiJmixEvolutionRemapPlanner.plan(
            previous,
            candidate,
            layer(listOf(mapping("employeeId", "id"))),
        ).single().options.first()

        assertEquals(OpenApiRemapConfidence.EXACT, option.confidence)
        assertEquals(1, option.exactPropertyMatches)
        assertEquals(OpenApiRemapConfidence.EXACT, option.propertyCandidates.single().confidence)
    }

    @Test
    fun `does not invent a remap without name or compatible structure evidence`() {
        val previous = operation(
            rootId = "EmployeeRecord",
            rootName = "EmployeeRecord",
            properties = listOf(property("employeeId", "EmployeeId")),
        )
        val candidate = operation(
            rootId = "AuditEnvelope",
            rootName = "AuditEnvelope",
            properties = listOf(property("attempts", "Count")),
        )

        val plans = OpenApiJmixEvolutionRemapPlanner.plan(
            previous,
            candidate,
            layer(listOf(mapping("employeeId", "id"))),
        )

        assertTrue(plans.isEmpty())
    }

    @Test
    fun `reports required outbound properties that a carried target cannot supply`() {
        val previous = operation(
            rootId = "EmployeeRecord",
            rootName = "EmployeeRecord",
            properties = listOf(property("employeeId", "EmployeeId")),
        )
        val candidateResponse = operation(
            rootId = "EmployeeRecord",
            rootName = "EmployeeRecord",
            properties = listOf(
                property("employeeId", "EmployeeId"),
                property("attempts", "Count"),
            ),
        )
        val candidateRequest = candidateResponse.copy(
            requestSchemaId = candidateResponse.responseSchemaId,
            responseSchemaId = null,
        )

        val option = OpenApiJmixEvolutionRemapPlanner.plan(
            previous,
            candidateRequest,
            layer(
                listOf(
                    IntegrationOpenApiPropertyMapping(
                        schemaProperty = "employeeId",
                        entityProperty = "id",
                        direction = IntegrationOpenApiMappingDirection.INBOUND,
                    ),
                ),
            ),
        ).single().options.single { it.candidateSchemaId == "EmployeeRecord" }

        assertEquals(listOf("attempts", "employeeId"), option.requiredOutboundUnmapped)
    }

    private fun mapping(source: String, target: String) = IntegrationOpenApiPropertyMapping(
        schemaProperty = source,
        entityProperty = target,
        direction = IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
    )

    private fun layer(properties: List<IntegrationOpenApiPropertyMapping>) = IntegrationOpenApiJmixLayerModel(
        enabled = true,
        dtoPackage = "com.acme.entity.integration",
        mapperPackage = "com.acme.integration.mapper",
        servicePackage = "com.acme.service.integration",
        serviceClassName = "EmployeeProviderService",
        serviceBeanName = "employeeProviderService",
        mappings = listOf(
            IntegrationOpenApiJmixTypeMapping(
                schemaId = "EmployeeRecord",
                targetKind = IntegrationOpenApiJmixTargetKind.GENERATED_DTO,
                generatedClassName = "EmployeeDto",
                idProperty = "id",
                instanceNameProperty = "name",
                properties = properties,
            ),
        ),
    )

    private fun property(name: String, schemaId: String) = IntegrationOpenApiPropertyModel(
        wireName = name,
        javaName = name,
        schemaId = schemaId,
        required = true,
        nullable = false,
    )

    private fun operation(
        rootId: String,
        rootName: String,
        properties: List<IntegrationOpenApiPropertyModel>,
    ): IntegrationOpenApiOperationModel {
        val scalarSchemas = listOf(
            IntegrationOpenApiSchemaModel("EmployeeId", "EmployeeId", IntegrationOpenApiSchemaKind.UUID),
            IntegrationOpenApiSchemaModel("Text", "Text", IntegrationOpenApiSchemaKind.STRING),
            IntegrationOpenApiSchemaModel("Flag", "Flag", IntegrationOpenApiSchemaKind.BOOLEAN),
            IntegrationOpenApiSchemaModel("Count", "Count", IntegrationOpenApiSchemaKind.INTEGER),
        )
        return IntegrationOpenApiOperationModel(
            contractPath = "openapi/hr.yaml",
            contractSha256 = if (rootId == "EmployeeRecord") "a".repeat(64) else "b".repeat(64),
            specificationVersion = "3.1.1",
            title = "HR API",
            apiVersion = "1",
            operationId = "findEmployee",
            javaMethodName = "findEmployee",
            method = IntegrationHttpMethod.GET,
            path = "/employees/{id}",
            deprecated = false,
            requestMediaType = null,
            requestRequired = false,
            requestSchemaId = null,
            responseStatus = "200",
            responseMediaType = "application/json",
            responseSchemaId = rootId,
            parameters = emptyList(),
            schemas = listOf(
                IntegrationOpenApiSchemaModel(
                    id = rootId,
                    javaName = rootName,
                    kind = IntegrationOpenApiSchemaKind.OBJECT,
                    properties = properties,
                ),
            ) + scalarSchemas,
        )
    }
}

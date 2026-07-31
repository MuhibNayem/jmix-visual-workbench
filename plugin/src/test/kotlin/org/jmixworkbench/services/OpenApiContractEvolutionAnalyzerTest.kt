package org.jmixworkbench.services

import org.jmixworkbench.model.IntegrationOpenApiOperationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenApiContractEvolutionAnalyzerTest {
    @Test
    fun `separates wire compatibility from generated source compatibility`() {
        val baseline = operation(contract())
        val optionalParameter = operation(contract(optionalParameter = true))

        val report = OpenApiContractEvolutionAnalyzer.compare(baseline, optionalParameter)

        val added = report.changes.single { it.code == "OPENAPI_PARAMETER_ADDED" }
        assertEquals(OpenApiEvolutionImpact.COMPATIBLE, added.wireImpact)
        assertEquals(OpenApiEvolutionImpact.BREAKING, added.sourceImpact)
        assertEquals(OpenApiEvolutionImpact.COMPATIBLE, report.wireImpact)
        assertEquals(OpenApiEvolutionImpact.BREAKING, report.sourceImpact)
        assertTrue(report.breaking)
    }

    @Test
    fun `classifies request response enum and security changes conservatively`() {
        val baseline = operation(contract())
        val candidate = operation(
            contract(
                requiredRequestProperty = true,
                removeResponseProperty = true,
                addResponseEnumValue = true,
                oauthScope = "payments.admin",
            ),
        )

        val report = OpenApiContractEvolutionAnalyzer.compare(baseline, candidate)

        assertEquals(OpenApiEvolutionImpact.BREAKING, report.wireImpact)
        assertEquals(OpenApiEvolutionImpact.BREAKING, report.sourceImpact)
        assertTrue(report.changes.any {
            it.code == "OPENAPI_PROPERTY_ADDED" && it.path == "request.body.currency"
        })
        assertTrue(report.changes.any {
            it.code == "OPENAPI_PROPERTY_REMOVED" && it.path == "response.body.reference"
        })
        assertTrue(report.changes.any { it.code == "OPENAPI_ENUM_VALUES_CHANGED" })
        assertTrue(report.changes.any { it.code == "OPENAPI_SECURITY_REQUIREMENTS_CHANGED" })
        assertEquals(
            report.reportDigest,
            OpenApiContractEvolutionAnalyzer.compare(baseline, candidate).reportDigest,
        )
    }

    @Test
    fun `identical normalized operations have no semantic delta`() {
        val baseline = operation(contract())
        val same = operation(contract())

        val report = OpenApiContractEvolutionAnalyzer.compare(baseline, same)

        assertFalse(report.different)
        assertFalse(report.breaking)
        assertEquals(OpenApiEvolutionImpact.NONE, report.wireImpact)
        assertEquals(OpenApiEvolutionImpact.NONE, report.sourceImpact)
    }

    @Test
    fun `detects normalized validation tightening inside request graphs`() {
        val baseline = operation(contract())
        val candidate = operation(contract(minimumAmount = 1))

        val report = OpenApiContractEvolutionAnalyzer.compare(baseline, candidate)

        val validation = report.changes.single {
            it.code == "OPENAPI_SCHEMA_VALIDATION_CHANGED" && it.path == "request.body.amount"
        }
        assertEquals(OpenApiEvolutionImpact.BREAKING, validation.wireImpact)
        assertEquals(OpenApiEvolutionImpact.REVIEW, validation.sourceImpact)
        assertEquals(OpenApiEvolutionImpact.BREAKING, report.wireImpact)
    }

    private fun operation(source: String): IntegrationOpenApiOperationModel {
        val parsed = OpenApiContractParser.parse("openapi/payment.yaml", source.toByteArray())
        val binding = requireNotNull(parsed.snapshot(null).operations.single().defaultBinding)
        return parsed.resolve(binding)
    }

    private fun contract(
        optionalParameter: Boolean = false,
        requiredRequestProperty: Boolean = false,
        removeResponseProperty: Boolean = false,
        addResponseEnumValue: Boolean = false,
        oauthScope: String = "payments.write",
        minimumAmount: Int? = null,
    ): String {
        val requestRequired = if (requiredRequestProperty) "[amount, currency]" else "[amount]"
        val responseRequired = if (removeResponseProperty) "[id, status]" else "[id, status, reference]"
        val statuses = if (addResponseEnumValue) "[ACCEPTED, REJECTED, PENDING]" else "[ACCEPTED, REJECTED]"
        return buildString {
            appendLine("openapi: 3.1.1")
            appendLine("info: { title: Payment API, version: \"2\" }")
            appendLine("paths:")
            appendLine("  /payments:")
            appendLine("    post:")
            appendLine("      operationId: submitPayment")
            appendLine("      security:")
            appendLine("        - oauth: [$oauthScope]")
            if (optionalParameter) {
                appendLine("      parameters:")
                appendLine("        - name: dry-run")
                appendLine("          in: query")
                appendLine("          required: false")
                appendLine("          schema: { type: boolean }")
            }
            appendLine("      requestBody:")
            appendLine("        required: true")
            appendLine("        content:")
            appendLine("          application/json:")
            appendLine("            schema: { ${'$'}ref: '#/components/schemas/PaymentRequest' }")
            appendLine("      responses:")
            appendLine("        \"201\":")
            appendLine("          description: accepted")
            appendLine("          content:")
            appendLine("            application/json:")
            appendLine("              schema: { ${'$'}ref: '#/components/schemas/PaymentReceipt' }")
            appendLine("components:")
            appendLine("  securitySchemes:")
            appendLine("    oauth:")
            appendLine("      type: oauth2")
            appendLine("      flows:")
            appendLine("        clientCredentials:")
            appendLine("          tokenUrl: https://identity.example/token")
            appendLine("          scopes:")
            appendLine("            payments.write: Submit payments")
            appendLine("            payments.admin: Administer payments")
            appendLine("  schemas:")
            appendLine("    PaymentRequest:")
            appendLine("      type: object")
            appendLine("      required: $requestRequired")
            appendLine("      properties:")
            appendLine(
                "        amount: { type: number, format: decimal${minimumAmount?.let { ", minimum: $it" }.orEmpty()} }",
            )
            if (requiredRequestProperty) appendLine("        currency: { type: string }")
            appendLine("    PaymentReceipt:")
            appendLine("      type: object")
            appendLine("      required: $responseRequired")
            appendLine("      properties:")
            appendLine("        id: { type: string, format: uuid }")
            appendLine("        status: { ${'$'}ref: '#/components/schemas/PaymentStatus' }")
            if (!removeResponseProperty) appendLine("        reference: { type: string }")
            appendLine("    PaymentStatus:")
            appendLine("      type: string")
            appendLine("      enum: $statuses")
        }
    }
}

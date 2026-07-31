package org.jmixworkbench.services

import org.jmixworkbench.generator.IntegrationConnectorGenerator
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationAuthenticationModel
import org.jmixworkbench.model.IntegrationCapability
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationHttpMethod
import org.jmixworkbench.model.IntegrationOpenApiSecuritySchemeKind
import org.jmixworkbench.model.IntegrationSpringBootApi
import org.jmixworkbench.model.IntegrationTransportSecurityModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Path

class OpenApiContractParserTest {
    @Test
    fun `bundles transitive project documents with stable shared schema identity`() {
        val rootPath = "contracts/openapi.yaml"
        val root = """
            openapi: 3.1.1
            info: { title: Bundled Payments, version: "1" }
            security:
              - oauth: [payments.write]
            paths:
              /health:
                ${'$'}ref: components/paths.yaml#/HealthPath
              /payments:
                post:
                  operationId: submitPayment
                  parameters:
                    - ${'$'}ref: components/parameters.yaml#/components/parameters/RequestId
                  requestBody:
                    ${'$'}ref: components/request-bodies.yaml#/SubmitPayment
                  responses:
                    "201":
                      ${'$'}ref: components/responses.yaml#/components/responses/Created
            components:
              securitySchemes:
                oauth:
                  ${'$'}ref: components/security.yaml#/oauth
        """.trimIndent().toByteArray()
        val documents = mapOf(
            "contracts/components/parameters.yaml" to """
                components:
                  parameters:
                    RequestId:
                      name: X-Request-Id
                      in: header
                      required: true
                      schema: { type: string, format: uuid }
            """.trimIndent().toByteArray(),
            "contracts/components/request-bodies.yaml" to """
                SubmitPayment:
                  required: true
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: ../models/payment.yaml#/PaymentRequest
            """.trimIndent().toByteArray(),
            "contracts/components/responses.yaml" to """
                components:
                  responses:
                    Created:
                      description: created
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: ../models/payment.yaml#/PaymentReceipt
            """.trimIndent().toByteArray(),
            "contracts/components/security.yaml" to """
                oauth:
                  type: oauth2
                  flows:
                    clientCredentials:
                      tokenUrl: https://identity.example/token
                      scopes:
                        payments.write: write payments
            """.trimIndent().toByteArray(),
            "contracts/components/paths.yaml" to """
                HealthPath:
                  get:
                    operationId: health
                    responses:
                      "204": { description: healthy }
            """.trimIndent().toByteArray(),
            "contracts/models/payment.yaml" to """
                PaymentRequest:
                  type: object
                  required: [account, lines]
                  properties:
                    account:
                      ${'$'}ref: common.yaml#/Account
                    lines:
                      type: array
                      items:
                        ${'$'}ref: '#/PaymentLine'
                PaymentLine:
                  type: object
                  required: [description]
                  properties:
                    description: { type: string }
                    parent:
                      ${'$'}ref: '#/PaymentRequest'
                PaymentReceipt:
                  type: object
                  required: [account, status]
                  properties:
                    account:
                      ${'$'}ref: common.yaml#/Account
                    status: { type: string }
            """.trimIndent().toByteArray(),
            "contracts/models/common.yaml" to """
                Account:
                  type: object
                  required: [id]
                  properties:
                    id: { type: string, format: uuid }
            """.trimIndent().toByteArray(),
        )
        val bundled = OpenApiDocumentBundler.bundle(rootPath, root) { referrer, reference ->
            val resolved = Path.of(referrer).parent.resolve(reference).normalize().toString()
            OpenApiSourceDocument(resolved, requireNotNull(documents[resolved]))
        }
        val parsed = OpenApiContractParser.parse(
            relativePath = rootPath,
            bytes = bundled.bytes,
            sourceSha256 = bundled.rootSha256,
            referencedDocuments = bundled.referencedDocuments,
        )
        val snapshot = parsed.snapshot("payments")
        val binding = assertNotNull(
            snapshot.operations.single { it.operationId == "submitPayment" }.defaultBinding,
        )
        val operation = parsed.resolve(binding)

        assertTrue(snapshot.valid, snapshot.operations.flatMap { it.issues }.joinToString())
        assertEquals(7, snapshot.referencedDocuments.size)
        assertTrue(snapshot.operations.any { it.operationId == "health" && it.supported })
        assertEquals(snapshot.referencedDocuments, binding.referencedDocuments)
        assertEquals(snapshot.referencedDocuments, operation.referencedDocuments)
        assertEquals("submitPayment", operation.javaMethodName)
        assertEquals("X-Request-Id", operation.parameters.single().wireName)
        assertEquals(listOf("oauth"), operation.securitySchemes)
        assertEquals("payments.write", operation.securityRequirements.single().schemes.single().requiredScopes.single())
        val accountSchemaIds = operation.schemas.flatMap { schema ->
            schema.properties.filter { it.javaName == "account" }.map { it.schemaId }
        }.distinct()
        assertEquals(1, accountSchemaIds.size, "The shared external Account schema must keep one canonical identity")
        assertTrue(operation.schemas.any { schema ->
            schema.properties.any { it.javaName == "parent" }
        })

        val changedDocuments = documents + (
            "contracts/models/common.yaml" to documents.getValue("contracts/models/common.yaml") + "\n# changed".toByteArray()
        )
        val changed = OpenApiDocumentBundler.bundle(rootPath, root) { referrer, reference ->
            val resolved = Path.of(referrer).parent.resolve(reference).normalize().toString()
            OpenApiSourceDocument(resolved, requireNotNull(changedDocuments[resolved]))
        }
        assertEquals(bundled.rootSha256, changed.rootSha256)
        assertTrue(changed.referencedDocuments != bundled.referencedDocuments)
    }

    @Test
    fun `blocks remote references unsupported anchors and cyclic reference objects`() {
        val remoteRoot = """
            openapi: 3.1.1
            info: { title: Remote, version: "1" }
            paths:
              /items:
                get:
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: https://attacker.invalid/schema.yaml#/Item
        """.trimIndent().toByteArray()
        assertFailsWith<IllegalArgumentException> {
            OpenApiDocumentBundler.bundle("contracts/openapi.yaml", remoteRoot) { _, _ ->
                error("Remote references must not invoke the project loader")
            }
        }

        val anchorRoot = remoteRoot.toString(Charsets.UTF_8)
            .replace("https://attacker.invalid/schema.yaml#/Item", "models.yaml#Payment")
            .toByteArray()
        assertFailsWith<IllegalArgumentException> {
            OpenApiDocumentBundler.bundle("contracts/openapi.yaml", anchorRoot) { _, _ ->
                OpenApiSourceDocument("contracts/models.yaml", "Payment: { type: string }".toByteArray())
            }
        }

        val pathRoot = """
            openapi: 3.1.1
            info: { title: Paths, version: "1" }
            paths:
              /items:
                ${'$'}ref: paths-a.yaml#/ItemPath
        """.trimIndent().toByteArray()
        val cyclic = mapOf(
            "contracts/paths-a.yaml" to "ItemPath: { ${'$'}ref: paths-b.yaml#/ItemPath }".toByteArray(),
            "contracts/paths-b.yaml" to "ItemPath: { ${'$'}ref: paths-a.yaml#/ItemPath }".toByteArray(),
        )
        assertFailsWith<IllegalArgumentException> {
            OpenApiDocumentBundler.bundle("contracts/openapi.yaml", pathRoot) { referrer, reference ->
                val resolved = Path.of(referrer).parent.resolve(reference).normalize().toString()
                OpenApiSourceDocument(resolved, requireNotNull(cyclic[resolved]))
            }
        }
    }

    @Test
    fun `parses OpenAPI 3 yaml and generates an exact typed operation`() {
        val parsed = OpenApiContractParser.parse("payments/src/main/resources/openapi/payment.yaml", CONTRACT)
        val snapshot = parsed.snapshot("payments")
        val operationSnapshot = snapshot.operations.single()
        val binding = assertNotNull(operationSnapshot.defaultBinding)
        val operation = parsed.resolve(binding)

        assertTrue(snapshot.valid)
        assertEquals("3.1.1", snapshot.specificationVersion)
        assertEquals("submitPayment", operation.javaMethodName)
        assertEquals(IntegrationHttpMethod.POST, operation.method)
        assertEquals("/payments/{accountId}", operation.path)
        assertEquals("application/json", operation.requestMediaType)
        assertEquals("201", operation.responseStatus)
        assertEquals(listOf("oauth"), operation.securitySchemes)
        assertEquals(operation.requestSchemaId, operationSnapshot.requestSchemaId)
        assertEquals(operation.responseSchemaId, operationSnapshot.responseSchemaId)
        assertEquals(operation.schemas.size, operationSnapshot.schemas.size)
        assertEquals(
            listOf("payments.write"),
            operationSnapshot.securityRequirements.single().schemes.single().requiredScopes,
        )
        assertEquals(1, operation.securityRequirements.size)
        assertEquals(
            IntegrationOpenApiSecuritySchemeKind.OAUTH2_CLIENT_CREDENTIALS,
            operation.securityRequirements.single().schemes.single().kind,
        )
        assertEquals(
            listOf("payments.write"),
            operation.securityRequirements.single().schemes.single().requiredScopes,
        )
        assertEquals(
            listOf("accountId", "requestId", "state", "xTraceId"),
            operation.parameters.map { it.javaName },
        )

        val base = connector().copy(
            openApiBinding = binding,
            resolvedOpenApiOperation = operation,
            httpMethod = operation.method,
            contentType = requireNotNull(operation.requestMediaType),
            payloadJavaType = IntegrationConnectorGenerator.openApiPayloadJavaType(operation, "PaymentConnector"),
            responseJavaType = IntegrationConnectorGenerator.openApiResponseJavaType(operation, "PaymentConnector"),
        )
        val validation = IntegrationConnectorGenerator.validate(
            base,
            setOf(IntegrationCapability.SPRING_WEB, IntegrationCapability.OAUTH2_CLIENT),
        )
        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val wrongAuthentication = IntegrationConnectorGenerator.validate(
            base.copy(
                authentication = IntegrationAuthenticationModel(
                    kind = IntegrationAuthenticationKind.API_KEY,
                    headerName = "X-Api-Key",
                    secretProperty = "payment.partner.api-key",
                ),
            ),
            setOf(IntegrationCapability.SPRING_WEB),
        )
        assertTrue(
            wrongAuthentication.diagnostics.any {
                it.code == "INTEGRATION_OPENAPI_SECURITY_UNSATISFIED"
            },
            wrongAuthentication.diagnostics.joinToString(),
        )

        val generated = IntegrationConnectorGenerator.generate(base)
        assertContains(generated.javaSource, "public PaymentConnector.PaymentResponse submitPayment(")
        assertContains(generated.javaSource, "pathVariables.put(\"accountId\", String.valueOf(accountId))")
        assertContains(generated.javaSource, "uriBuilder.queryParam(\"request-id\", String.valueOf(requestId))")
        assertContains(generated.javaSource, "uriBuilder.queryParam(\"state\", state.value())")
        assertContains(generated.javaSource, "request = request.header(\"X-Trace-Id\", String.valueOf(xTraceId))")
        assertContains(generated.javaSource, "response.getStatusCode().value() != 201")
        assertContains(generated.javaSource, "public record PaymentRequest(")
        assertContains(generated.javaSource, "public enum PaymentStatus")
        assertContains(generated.javaSource, "@JsonProperty(value = \"external-reference\")")
        assertFalse(
            String(
                java.util.Base64.getUrlDecoder().decode(
                    generated.javaSource.lineSequence()
                        .first { it.startsWith(IntegrationConnectorGenerator.markerPrefix()) }
                        .removePrefix(IntegrationConnectorGenerator.markerPrefix()),
                ),
            ).contains("resolvedOpenApiOperation"),
        )
    }

    @Test
    fun `blocks external references ambiguous polymorphism and stale coordinates`() {
        val external = OpenApiContractParser.parse(
            "openapi.yaml",
            """
            openapi: 3.0.3
            info: { title: External, version: "1" }
            paths:
              /items:
                post:
                  operationId: createItem
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: https://attacker.invalid/schema.yaml#/Item
                  responses:
                    "204": { description: done }
            """.trimIndent().toByteArray(),
        ).snapshot(null)
        assertFalse(external.operations.single().supported)
        assertContains(external.operations.single().issues.single(), "blocked")

        val polymorphic = OpenApiContractParser.parse(
            "openapi.json",
            """
            {
              "openapi":"3.1.1",
              "info":{"title":"Polymorphic","version":"1"},
              "paths":{"/items":{"post":{
                "operationId":"createItem",
                "requestBody":{"required":true,"content":{"application/json":{"schema":{
                  "oneOf":[{"type":"string"},{"type":"integer"}]
                }}}},
                "responses":{"204":{"description":"done"}}
              }}}
            }
            """.trimIndent().toByteArray(),
        ).snapshot(null)
        assertFalse(polymorphic.operations.single().supported)
        assertContains(polymorphic.operations.single().issues.single(), "oneOf/anyOf")

        val parsed = OpenApiContractParser.parse("payment.yaml", CONTRACT)
        val binding = assertNotNull(parsed.snapshot(null).operations.single().defaultBinding)
        assertFailsWith<IllegalArgumentException> {
            parsed.resolve(binding.copy(documentSha256 = "0".repeat(64)))
        }
    }

    @Test
    fun `rejects duplicate operation ids and form serializers`() {
        assertFailsWith<IllegalArgumentException> {
            OpenApiContractParser.parse(
                "duplicate.yaml",
                """
                openapi: 3.0.3
                info: { title: Duplicate, version: "1" }
                paths:
                  /one:
                    get:
                      operationId: find
                      responses: { "204": { description: done } }
                  /two:
                    get:
                      operationId: FIND
                      responses: { "204": { description: done } }
                """.trimIndent().toByteArray(),
            )
        }

        val form = OpenApiContractParser.parse(
            "form.yaml",
            """
            openapi: 3.0.3
            info: { title: Form, version: "1" }
            paths:
              /token:
                post:
                  operationId: token
                  requestBody:
                    required: true
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          properties:
                            username: { type: string }
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: { type: string }
            """.trimIndent().toByteArray(),
        ).snapshot(null)
        assertFalse(form.operations.single().supported)
        assertContains(form.operations.single().issues.single(), "dedicated serializer")
    }

    @Test
    fun `fails closed for unsupported parameter and response serialization`() {
        val unsupported = OpenApiContractParser.parse(
            "unsupported.yaml",
            """
            openapi: 3.1.1
            info: { title: Unsupported, version: "1" }
            paths:
              /search:
                get:
                  operationId: search
                  parameters:
                    - name: filter
                      in: query
                      style: deepObject
                      schema:
                        type: object
                        properties:
                          name: { type: string }
                  responses:
                    "200":
                      description: result
                      content:
                        application/xml:
                          schema: { type: string }
            """.trimIndent().toByteArray(),
        ).snapshot(null)

        assertFalse(unsupported.operations.single().supported)
        assertTrue(
            unsupported.operations.single().issues.single().contains("serialization") ||
                unsupported.operations.single().issues.single().contains("message converter"),
        )
    }

    @Test
    fun `avoids generated nested type collision with connector class`() {
        val parsed = OpenApiContractParser.parse(
            "collision.json",
            """
            {
              "openapi":"3.0.3",
              "info":{"title":"Collision","version":"1"},
              "paths":{"/value":{"get":{
                "operationId":"getClass",
                "responses":{"200":{"description":"ok","content":{"application/json":{"schema":{
                  "title":"PaymentConnector",
                  "type":"object",
                  "required":["value"],
                  "properties":{"value":{"type":"string"}}
                }}}}}
              }}}
            }
            """.trimIndent().toByteArray(),
        )
        val binding = assertNotNull(parsed.snapshot(null).operations.single().defaultBinding)
        val operation = parsed.resolve(binding)
        val model = connector().copy(
            authentication = IntegrationAuthenticationModel(),
            openApiBinding = binding,
            resolvedOpenApiOperation = operation,
            httpMethod = operation.method,
            payloadJavaType = "void",
            responseJavaType = IntegrationConnectorGenerator.openApiResponseJavaType(
                operation,
                "PaymentConnector",
            ),
        )

        assertEquals("getClassOperation", operation.javaMethodName)
        assertEquals("PaymentConnector.PaymentConnectorModel", model.responseJavaType)
        assertContains(
            IntegrationConnectorGenerator.generate(model).javaSource,
            "public record PaymentConnectorModel(",
        )
    }

    @Test
    fun `preserves AND security semantics for oauth and mutual tls`() {
        val parsed = OpenApiContractParser.parse(
            "secure.yaml",
            """
            openapi: 3.1.1
            info: { title: Secure, version: "1" }
            security:
              - oauth: [payments.write]
                mtls: []
            paths:
              /secure:
                get:
                  operationId: secure
                  responses:
                    "204": { description: done }
            components:
              securitySchemes:
                oauth:
                  type: oauth2
                  flows:
                    clientCredentials:
                      tokenUrl: https://identity.example/token
                      scopes:
                        payments.write: write
                mtls:
                  type: mutualTLS
            """.trimIndent().toByteArray(),
        )
        val binding = assertNotNull(parsed.snapshot(null).operations.single().defaultBinding)
        val operation = parsed.resolve(binding)
        val base = connector().copy(
            openApiBinding = binding,
            resolvedOpenApiOperation = operation,
            httpMethod = operation.method,
            payloadJavaType = "void",
            responseJavaType = "void",
        )
        val withoutMutualTls = IntegrationConnectorGenerator.validate(
            base,
            setOf(IntegrationCapability.SPRING_WEB, IntegrationCapability.OAUTH2_CLIENT),
        )
        assertTrue(
            withoutMutualTls.diagnostics.any {
                it.code == "INTEGRATION_OPENAPI_SECURITY_UNSATISFIED"
            },
        )

        val secured = base.copy(
            transportSecurity = IntegrationTransportSecurityModel(
                mutualTlsEnabled = true,
                sslBundleNameProperty = "payment.partner.ssl-bundle",
            ),
            runtimeSpringBootApi = IntegrationSpringBootApi.BOOT_3,
        )
        val validation = IntegrationConnectorGenerator.validate(
            secured,
            setOf(
                IntegrationCapability.SPRING_WEB,
                IntegrationCapability.OAUTH2_CLIENT,
                IntegrationCapability.SPRING_BOOT_SSL_BUNDLES,
            ),
        )
        assertTrue(validation.valid, validation.diagnostics.joinToString())
    }

    private fun connector() = IntegrationConnectorModel(
        name = "Payment API",
        destinationId = "payments:main",
        packageName = "com.acme.payment.integration",
        className = "PaymentConnector",
        beanName = "paymentConnector",
        kind = IntegrationConnectorKind.PAYMENT_GATEWAY,
        configurationPrefix = "payment.partner",
        addressProperty = "payment.partner.base-url",
        payloadJavaType = "java.lang.String",
        responseJavaType = "java.lang.String",
        authentication = IntegrationAuthenticationModel(
            kind = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
            authorizedClientManagerBeanName = "authorizedClientManager",
            clientRegistrationIdProperty = "payment.partner.registration-id",
            principalNameProperty = "payment.partner.principal-name",
            evictInvalidAuthorizedClient = false,
            scopes = listOf("payments.write"),
        ),
    )

    companion object {
        private val CONTRACT = """
            openapi: 3.1.1
            info:
              title: Payment Partner
              version: "2026-07"
            security:
              - oauth: [payments.write]
            paths:
              /payments/{accountId}:
                parameters:
                  - name: accountId
                    in: path
                    required: true
                    schema: { type: string, format: uuid }
                post:
                  operationId: submitPayment
                  summary: Submit a payment
                  parameters:
                    - name: request-id
                      in: query
                      required: true
                      schema: { type: string }
                    - name: state
                      in: query
                      schema:
                        type: string
                        title: PaymentState
                        enum: [pending, settled]
                    - name: X-Trace-Id
                      in: header
                      schema: { type: string }
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/PaymentRequest'
                  responses:
                    "201":
                      description: created
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/PaymentResponse'
            components:
              securitySchemes:
                oauth:
                  type: oauth2
                  flows:
                    clientCredentials:
                      tokenUrl: https://identity.example/token
                      scopes:
                        payments.write: submit payments
              schemas:
                PaymentBase:
                  type: object
                  required: [amount]
                  properties:
                    amount: { type: number, format: decimal }
                PaymentRequest:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/PaymentBase'
                    - type: object
                      required: [external-reference]
                      properties:
                        external-reference: { type: string }
                PaymentResponse:
                  type: object
                  required: [id, status]
                  properties:
                    id: { type: string, format: uuid }
                    status:
                      type: string
                      enum: [accepted, rejected]
                      title: PaymentStatus
        """.trimIndent().toByteArray()
    }
}

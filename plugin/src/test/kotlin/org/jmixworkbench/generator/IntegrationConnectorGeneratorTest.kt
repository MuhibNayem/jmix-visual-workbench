package org.jmixworkbench.generator

import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationAuthenticationModel
import org.jmixworkbench.model.IntegrationCapability
import org.jmixworkbench.model.IntegrationCircuitBreakerModel
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationDeliveryGuarantee
import org.jmixworkbench.model.IntegrationDiagnosticSeverity
import org.jmixworkbench.model.IntegrationHttpMethod
import org.jmixworkbench.model.IntegrationIdempotencyModel
import org.jmixworkbench.model.IntegrationReliabilityModel
import org.jmixworkbench.model.IntegrationRetryMode
import org.jmixworkbench.model.IntegrationRetryPolicyModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegrationConnectorGeneratorTest {
    @Test
    fun `generates externalized resilient webhook without endpoint or secret literals`() {
        val model = connector(
            kind = IntegrationConnectorKind.WEBHOOK,
            httpMethod = IntegrationHttpMethod.POST,
            responseJavaType = "java.lang.String",
            authentication = IntegrationAuthenticationModel(
                kind = IntegrationAuthenticationKind.BEARER,
                secretProperty = "payroll.partner.token",
            ),
            reliability = IntegrationReliabilityModel(
                retry = IntegrationRetryPolicyModel(
                    mode = IntegrationRetryMode.BLOCKING,
                    attempts = 4,
                    initialDelayMs = 750,
                    maximumDelayMs = 12_000,
                ),
                circuitBreaker = IntegrationCircuitBreakerModel(enabled = true),
                idempotency = IntegrationIdempotencyModel(enabled = true),
            ),
        )
        val capabilities = setOf(
            IntegrationCapability.SPRING_WEB,
            IntegrationCapability.RESILIENCE4J,
        )

        val validation = IntegrationConnectorGenerator.validate(model, capabilities)
        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val generated = IntegrationConnectorGenerator.generate(model)

        assertContains(generated.javaSource, "// JVW-INTEGRATION-MODEL:")
        assertContains(generated.javaSource, "@Value(\"\${payroll.partner.url}\") String address")
        assertContains(generated.javaSource, "@Value(\"\${payroll.partner.token}\") String authSecret")
        assertContains(generated.javaSource, "@Retry(name = \"partnerWebhook\")")
        assertContains(generated.javaSource, "@CircuitBreaker(name = \"partnerWebhook\")")
        assertContains(generated.javaSource, "request.header(\"Idempotency-Key\", idempotencyKey)")
        assertContains(generated.javaSource, "Duration.ofMillis(5000L)")
        assertContains(generated.javaSource, "Duration.ofMillis(30000L)")
        assertContains(
            requireNotNull(generated.reliabilityProperties),
            "resilience4j.retry.instances.partnerWebhook.max-attempts=4",
        )
        assertFalse(generated.javaSource.contains("https://"))
        assertFalse(generated.javaSource.contains("Bearer secret"))
    }

    @Test
    fun `rejects kafka retry transaction and ordering conflicts`() {
        val model = connector(
            kind = IntegrationConnectorKind.KAFKA_CONSUMER,
            payloadJavaType = "com.acme.loan.LoanEvent",
            handlerBeanClass = "com.acme.loan.LoanEventHandler",
            handlerFieldName = "loanEventHandler",
            handlerMethod = "handle",
            reliability = IntegrationReliabilityModel(
                retry = IntegrationRetryPolicyModel(
                    mode = IntegrationRetryMode.NON_BLOCKING,
                    attempts = 4,
                    deadLetterDestinationProperty = "payroll.loan-events.dlt",
                ),
                transactional = true,
                orderingRequired = true,
            ),
        )

        val validation = IntegrationConnectorGenerator.validate(
            model,
            setOf(IntegrationCapability.SPRING_KAFKA),
        )

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "INTEGRATION_KAFKA_RETRY_TRANSACTION_CONFLICT" })
        assertTrue(validation.diagnostics.any { it.code == "INTEGRATION_KAFKA_RETRY_ORDERING_CONFLICT" })
    }

    @Test
    fun `rejects unsupported guarantee implicit outbox and missing module capability`() {
        val model = connector(
            kind = IntegrationConnectorKind.RABBIT_PUBLISHER,
            reliability = IntegrationReliabilityModel(
                deliveryGuarantee = IntegrationDeliveryGuarantee.EXACTLY_ONCE,
                outboxEnabled = true,
            ),
        )

        val validation = IntegrationConnectorGenerator.validate(model, emptySet())

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "INTEGRATION_EXACTLY_ONCE_UNSUPPORTED" })
        assertTrue(validation.diagnostics.any { it.code == "INTEGRATION_OUTBOX_RUNTIME_PENDING" })
        assertTrue(validation.diagnostics.any { it.code == "INTEGRATION_DEPENDENCY_MISSING" })
        assertTrue(validation.diagnostics.all {
            it.severity != IntegrationDiagnosticSeverity.ERROR ||
                it.code.startsWith("INTEGRATION_")
        })
    }

    @Test
    fun `generates Jmix file storage through selected storage name property`() {
        val model = connector(
            kind = IntegrationConnectorKind.JMIX_FILE_STORAGE,
            addressProperty = "jmix.core.default-file-storage",
            payloadJavaType = "byte[]",
            responseJavaType = "io.jmix.core.FileRef",
        )

        val validation = IntegrationConnectorGenerator.validate(
            model,
            setOf(IntegrationCapability.JMIX_FILE_STORAGE),
        )
        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val source = IntegrationConnectorGenerator.generate(model).javaSource

        assertContains(source, "FileStorageLocator fileStorageLocator")
        assertContains(source, "@Value(\"\${jmix.core.default-file-storage}\") String storageName")
        assertContains(source, "this.fileStorage = fileStorageLocator.getByName(storageName)")
        assertContains(source, "public FileRef store(String fileName, byte[] content)")
        assertContains(source, "public byte[] load(FileRef reference)")
    }

    @Test
    fun `generates OAuth2 client credentials through an explicitly selected manager`() {
        val model = connector(
            kind = IntegrationConnectorKind.IDENTITY_PROVIDER,
            responseJavaType = "java.lang.String",
            authentication = IntegrationAuthenticationModel(
                kind = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
                authorizedClientManagerBeanName = "authorizedClientManager",
                clientRegistrationIdProperty = "identity.partner.registration-id",
                principalNameProperty = "identity.partner.principal-name",
            ),
        )

        val validation = IntegrationConnectorGenerator.validate(
            model,
            setOf(IntegrationCapability.SPRING_WEB, IntegrationCapability.OAUTH2_CLIENT),
        )
        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val source = IntegrationConnectorGenerator.generate(model).javaSource

        assertContains(
            source,
            "@Qualifier(\"authorizedClientManager\") OAuth2AuthorizedClientManager authorizedClientManager",
        )
        assertContains(
            source,
            "@Value(\"\${identity.partner.registration-id}\") String clientRegistrationId",
        )
        assertContains(source, "OAuth2AuthorizeRequest.withClientRegistrationId(clientRegistrationId)")
        assertContains(source, ".principal(oauthPrincipalName)")
        assertContains(source, "authorizedClient.getAccessToken().getTokenValue()")
        assertFalse(source.contains("client-secret"))
        assertFalse(source.contains("token-uri"))
    }

    private fun connector(
        kind: IntegrationConnectorKind,
        addressProperty: String = "payroll.partner.url",
        payloadJavaType: String = "java.lang.String",
        responseJavaType: String = "void",
        httpMethod: IntegrationHttpMethod = IntegrationHttpMethod.POST,
        authentication: IntegrationAuthenticationModel = IntegrationAuthenticationModel(),
        reliability: IntegrationReliabilityModel = IntegrationReliabilityModel(),
        handlerBeanClass: String? = null,
        handlerFieldName: String? = null,
        handlerMethod: String? = null,
    ) = IntegrationConnectorModel(
        name = "Partner connector",
        destinationId = "loan:main",
        packageName = "com.acme.loan.integration",
        className = "PartnerConnector",
        beanName = "partnerWebhook",
        kind = kind,
        configurationPrefix = "payroll.partner",
        addressProperty = addressProperty,
        payloadJavaType = payloadJavaType,
        responseJavaType = responseJavaType,
        httpMethod = httpMethod,
        authentication = authentication,
        reliability = reliability,
        handlerBeanClass = handlerBeanClass,
        handlerFieldName = handlerFieldName,
        handlerMethod = handlerMethod,
    )
}

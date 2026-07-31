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
import org.jmixworkbench.model.IntegrationOutboxModel
import org.jmixworkbench.model.IntegrationJsonApi
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationObservabilityModel
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
        assertTrue(validation.diagnostics.any { it.code == "INTEGRATION_OUTBOX_CONFIGURATION_REQUIRED" })
        assertTrue(validation.diagnostics.any { it.code == "INTEGRATION_DEPENDENCY_MISSING" })
        assertTrue(validation.diagnostics.all {
            it.severity != IntegrationDiagnosticSeverity.ERROR ||
                it.code.startsWith("INTEGRATION_")
        })
    }

    @Test
    fun `generates portable durable outbox dispatcher migration replay and reconciliation`() {
        val model = connector(
            kind = IntegrationConnectorKind.KAFKA_PUBLISHER,
            payloadJavaType = "com.acme.loan.LoanEvent",
            reliability = IntegrationReliabilityModel(
                deliveryGuarantee = IntegrationDeliveryGuarantee.AT_LEAST_ONCE,
                transactional = true,
                orderingRequired = true,
                outboxEnabled = true,
                outbox = IntegrationOutboxModel(
                    storeId = "loan:main",
                    migrationPath = "loan/src/main/resources/db/changelog/2026/07/31-jvw-loan-outbox.xml",
                    tableName = "jvw_loan_event_outbox",
                    jsonApi = IntegrationJsonApi.JACKSON_2,
                ),
            ),
            observability = IntegrationObservabilityModel(
                metricsEnabled = true,
                tracingEnabled = true,
                auditEnabled = true,
                runtimeApi = IntegrationObservabilityApi.MICROMETER_OBSERVATION,
            ),
        )

        val validation = IntegrationConnectorGenerator.validate(
            model,
            setOf(IntegrationCapability.SPRING_KAFKA),
        )
        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val generated = IntegrationConnectorGenerator.generate(model)

        assertContains(generated.javaSource, "public class PartnerConnector")
        assertFalse(generated.javaSource.contains("public final class PartnerConnector"))
        assertContains(generated.javaSource, "public String enqueue(String orderingKey, com.acme.loan.LoanEvent payload)")
        assertContains(generated.javaSource, "@Qualifier(\"dataSource\") DataSource dataSource")
        assertContains(
            generated.javaSource,
            "@Qualifier(\"transactionManager\") PlatformTransactionManager transactionManager",
        )
        assertContains(generated.javaSource, "@Transactional(\"transactionManager\")")
        assertContains(generated.javaSource, "this.jdbcTemplate = new JdbcTemplate(dataSource)")
        assertContains(generated.javaSource, "INSERT INTO jvw_loan_event_outbox")
        assertContains(generated.javaSource, "PreparedStatement statement")
        assertContains(generated.javaSource, "int remaining = 100")
        assertContains(generated.javaSource, "while (remaining > 0)")
        assertContains(generated.javaSource, "statement.setMaxRows(queryLimit)")
        assertContains(generated.javaSource, "remaining -= candidates.size()")
        assertContains(generated.javaSource, "ORDER BY candidate.created_at, candidate.id")
        assertContains(generated.javaSource, "earlier.status <> 'SENT'")
        assertFalse(generated.javaSource.contains("earlier.status NOT IN ('SENT', 'DEAD')"))
        assertContains(generated.javaSource, "jvw-outbox-id")
        assertContains(generated.javaSource, "ProducerRecord<String, com.acme.loan.LoanEvent>")
        assertFalse(generated.javaSource.contains("CorrelationData"))
        assertContains(generated.javaSource, "public void replay(String eventId, String reason)")
        assertContains(generated.javaSource, "SpecificOperationAccessContext")
        assertContains(generated.javaSource, "jvw.integration.outbox.replay")
        assertContains(generated.javaSource, "jvw.integration.outbox.maintain")
        assertContains(generated.javaSource, "OutboxAuditEvent")
        assertContains(generated.javaSource, "sha256(reason)")
        assertContains(generated.javaSource, "MeterRegistry")
        assertContains(generated.javaSource, "Observation.start(\"jvw.integration.outbox.dispatch\"")
        assertContains(generated.javaSource, "lowCardinalityKeyValue(\"connector\"")
        assertContains(generated.javaSource, "public OutboxHealth reconcile()")
        assertContains(generated.javaSource, "orderingBlocked")
        assertContains(generated.javaSource, "Outbox delivery acknowledgement could not be persisted")
        assertContains(generated.javaSource, "List<String> candidates = jdbcTemplate.query(connection ->")
        assertContains(generated.javaSource, "DELETE FROM jvw_loan_event_outbox WHERE id = ?")
        assertContains(generated.javaSource, "status IN ('PENDING', 'RETRY', 'IN_FLIGHT')")
        assertFalse(generated.javaSource.contains("exactly-once", ignoreCase = true))
        assertContains(requireNotNull(generated.migrationXml), "<createTable tableName=\"jvw_loan_event_outbox\"")
        assertContains(requireNotNull(generated.migrationXml), "<tableExists tableName=\"jvw_loan_event_outbox\"")
        assertContains(requireNotNull(generated.migrationXml), "<rollback>")
        assertContains(
            generated.reliabilityProperties,
            "Durable database-to-broker delivery is at-least-once",
        )
        assertContains(generated.reliabilityProperties, "spring.kafka.producer.acks=all")
        assertContains(
            generated.reliabilityProperties,
            "spring.kafka.producer.properties.max.block.ms=30000",
        )
        assertContains(
            generated.reliabilityProperties,
            "spring.kafka.producer.properties.request.timeout.ms=30000",
        )
        assertContains(
            generated.reliabilityProperties,
            "spring.kafka.producer.properties.delivery.timeout.ms=31000",
        )
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

    @Test
    fun `generates traversal-safe atomic SFTP upload`() {
        val model = connector(
            kind = IntegrationConnectorKind.SFTP_UPLOAD,
            addressProperty = "payroll.sftp.base-directory",
            payloadJavaType = "byte[]",
        )

        val validation = IntegrationConnectorGenerator.validate(
            model,
            setOf(IntegrationCapability.SPRING_INTEGRATION_SFTP),
        )
        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val source = IntegrationConnectorGenerator.generate(model).javaSource

        assertContains(source, "String safePath = safeRemotePath(remotePath)")
        assertContains(source, "String temporary = destination + \".jvw-\"")
        assertContains(source, "session.write(input, temporary)")
        assertContains(source, "session.rename(temporary, destination)")
        assertContains(source, "session.remove(temporary)")
        assertContains(source, "remotePath.startsWith(\"/\")")
        assertContains(source, "segment.equals(\"..\")")
    }

    private fun connector(
        kind: IntegrationConnectorKind,
        addressProperty: String = "payroll.partner.url",
        payloadJavaType: String = "java.lang.String",
        responseJavaType: String = "void",
        httpMethod: IntegrationHttpMethod = IntegrationHttpMethod.POST,
        authentication: IntegrationAuthenticationModel = IntegrationAuthenticationModel(),
        reliability: IntegrationReliabilityModel = IntegrationReliabilityModel(),
        observability: IntegrationObservabilityModel = IntegrationObservabilityModel(),
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
        observability = observability,
        handlerBeanClass = handlerBeanClass,
        handlerFieldName = handlerFieldName,
        handlerMethod = handlerMethod,
    )
}

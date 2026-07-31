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
import org.jmixworkbench.model.IntegrationInboxModel
import org.jmixworkbench.model.IntegrationReliabilityModel
import org.jmixworkbench.model.IntegrationOutboxModel
import org.jmixworkbench.model.IntegrationJsonApi
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationObservabilityModel
import org.jmixworkbench.model.IntegrationRetryMode
import org.jmixworkbench.model.IntegrationRetryPolicyModel
import org.jmixworkbench.model.IntegrationSpringBootApi
import org.jmixworkbench.model.IntegrationTransportSecurityModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegrationConnectorGeneratorTest {
    @Test
    fun `requires Spring Boot 4 Kafka runtime module for Jmix 3 connectors`() {
        val model = connector(
            kind = IntegrationConnectorKind.KAFKA_PUBLISHER,
        ).copy(runtimeJsonApi = IntegrationJsonApi.JACKSON_3)

        val validation = IntegrationConnectorGenerator.validate(
            model,
            setOf(IntegrationCapability.SPRING_KAFKA),
        )

        assertFalse(validation.valid)
        assertTrue(
            validation.diagnostics.any {
                it.code == "INTEGRATION_DEPENDENCY_MISSING" &&
                    "SPRING_BOOT_KAFKA" in it.message
            },
        )
    }

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
    fun `allows database inbox transactions but rejects kafka non blocking ordering`() {
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
                idempotency = IntegrationIdempotencyModel(
                    enabled = true,
                    headerName = "jvw-outbox-id",
                    keyParameterName = "messageId",
                ),
                inboxEnabled = true,
                inbox = IntegrationInboxModel(
                    storeId = "loan:main",
                    tableName = "jvw_loan_event_inbox",
                    jsonApi = IntegrationJsonApi.JACKSON_2,
                ),
            ),
        )

        val validation = IntegrationConnectorGenerator.validate(
            model,
            setOf(IntegrationCapability.SPRING_KAFKA),
        )

        assertFalse(validation.valid)
        assertFalse(validation.diagnostics.any { it.code == "INTEGRATION_KAFKA_RETRY_TRANSACTION_CONFLICT" })
        assertTrue(validation.diagnostics.any { it.code == "INTEGRATION_KAFKA_RETRY_ORDERING_CONFLICT" })
    }

    @Test
    fun `generates persistent transaction-bound Kafka inbox dead letter and secured replay`() {
        val model = connector(
            kind = IntegrationConnectorKind.KAFKA_CONSUMER,
            addressProperty = "payroll.loan-events.topic",
            payloadJavaType = "com.acme.loan.LoanEvent",
            handlerBeanClass = "com.acme.loan.LoanEventHandler",
            handlerFieldName = "loanEventHandler",
            handlerMethod = "handle",
            reliability = IntegrationReliabilityModel(
                deliveryGuarantee = IntegrationDeliveryGuarantee.AT_LEAST_ONCE,
                transactional = true,
                retry = IntegrationRetryPolicyModel(
                    mode = IntegrationRetryMode.BLOCKING,
                    attempts = 4,
                    initialDelayMs = 250,
                    deadLetterDestinationProperty = "payroll.loan-events.dlt",
                ),
                idempotency = IntegrationIdempotencyModel(
                    enabled = true,
                    headerName = "jvw-outbox-id",
                    keyParameterName = "messageId",
                ),
                inboxEnabled = true,
                inbox = IntegrationInboxModel(
                    storeId = "loan:main",
                    migrationPath = "loan/src/main/resources/db/changelog/2026/07/31-jvw-loan-inbox.xml",
                    tableName = "jvw_loan_event_inbox",
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
            setOf(IntegrationCapability.SPRING_KAFKA, IntegrationCapability.RESILIENCE4J),
        )
        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val generated = IntegrationConnectorGenerator.generate(model)

        assertContains(generated.javaSource, "Message<com.acme.loan.LoanEvent> message")
        assertContains(generated.javaSource, "Retry.decorateCheckedRunnable")
        assertContains(generated.javaSource, "INSERT INTO jvw_loan_event_inbox")
        assertContains(generated.javaSource, "catch (DuplicateKeyException duplicate)")
        assertContains(generated.javaSource, "MESSAGE_ID_COLLISION")
        assertContains(generated.javaSource, "Stable message ID was reused with a different payload")
        assertContains(generated.javaSource, "deadLetterMessageId(message)")
        assertContains(generated.javaSource, "\"quarantine-\" + sha256(fingerprint)")
        assertContains(generated.javaSource, "status IN ('REPLAY_PENDING', 'REPLAYED')")
        assertContains(generated.javaSource, "public void replay(String eventId, String reason)")
        assertContains(generated.javaSource, "jvw.integration.inbox.replay")
        assertContains(generated.javaSource, "Kafka dead-letter publication was not acknowledged")
        assertContains(generated.javaSource, "record.headers().add(\"jvw-outbox-id\"")
        assertContains(generated.javaSource, "Inbox payload checksum mismatch")
        assertContains(generated.javaSource, "@Transactional(\"transactionManager\")")
        assertContains(generated.javaSource, "statement.setMaxRows(1000)")
        assertContains(requireNotNull(generated.migrationXml), "jvw_loan_event_inbox")
        assertContains(requireNotNull(generated.migrationXml), "source_destination")
        assertContains(generated.reliabilityProperties, "spring.kafka.listener.ack-mode=record")
        assertContains(generated.reliabilityProperties, "inbox.maintenance-batch-size=1000")
        assertContains(
            generated.reliabilityProperties,
            "resilience4j.retry.instances.partnerWebhook.max-attempts=4",
        )
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
    fun `generates OAuth2 lifecycle through selected manager and invalid-token eviction service`() {
        val model = connector(
            kind = IntegrationConnectorKind.IDENTITY_PROVIDER,
            responseJavaType = "java.lang.String",
            authentication = IntegrationAuthenticationModel(
                kind = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
                authorizedClientManagerBeanName = "authorizedClientManager",
                authorizedClientServiceBeanName = "authorizedClientService",
                clientRegistrationIdProperty = "identity.partner.registration-id",
                principalNameProperty = "identity.partner.principal-name",
                evictInvalidAuthorizedClient = true,
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
        assertContains(
            source,
            "@Qualifier(\"authorizedClientService\") OAuth2AuthorizedClientService authorizedClientService",
        )
        assertContains(source, "new OAuth2ClientHttpRequestInterceptor(authorizedClientManager)")
        assertContains(source, "oauth2.setPrincipalResolver(new RequestAttributePrincipalResolver())")
        assertContains(source, "authorizationFailureHandler(authorizedClientService)")
        assertContains(source, "RequestAttributeClientRegistrationIdResolver.clientRegistrationId(clientRegistrationId)")
        assertContains(source, "RequestAttributePrincipalResolver.principal(oauthPrincipalName)")
        assertFalse(source.contains("OAuth2AuthorizeRequest"))
        assertFalse(source.contains("getTokenValue()"))
        assertFalse(source.contains("client-secret"))
        assertFalse(source.contains("token-uri"))
    }

    @Test
    fun `generates versioned Spring Boot mTLS request factory without losing timeouts`() {
        listOf(
            IntegrationSpringBootApi.BOOT_3 to "ClientHttpRequestFactorySettings",
            IntegrationSpringBootApi.BOOT_4 to "HttpClientSettings",
        ).forEach { (api, settingsType) ->
            val model = connector(
                kind = IntegrationConnectorKind.HTTP_CLIENT,
                responseJavaType = "java.lang.String",
            ).copy(
                runtimeSpringBootApi = api,
                transportSecurity = IntegrationTransportSecurityModel(
                    mutualTlsEnabled = true,
                    sslBundleNameProperty = "payroll.partner.ssl-bundle",
                ),
            )
            val validation = IntegrationConnectorGenerator.validate(
                model,
                setOf(
                    IntegrationCapability.SPRING_WEB,
                    IntegrationCapability.SPRING_BOOT_SSL_BUNDLES,
                ),
            )

            assertTrue(validation.valid, "$api: ${validation.diagnostics}")
            val generated = IntegrationConnectorGenerator.generate(model)
            assertContains(generated.javaSource, "$settingsType.defaults()")
            assertContains(generated.javaSource, ".withTimeouts(Duration.ofMillis(5000L), Duration.ofMillis(30000L))")
            assertContains(generated.javaSource, ".withSslBundle(sslBundle)")
            assertContains(generated.javaSource, "ClientHttpRequestFactoryBuilder.jdk().build(httpSettings)")
            assertContains(generated.javaSource, "private volatile RestClient restClient")
            assertContains(generated.javaSource, "sslBundles.addBundleUpdateHandler(sslBundleName, this::reloadSslBundle)")
            assertContains(generated.javaSource, "RestClient replacement = buildRestClient(updatedBundle)")
            assertContains(generated.javaSource, "keeping the last working client")
            assertContains(generated.javaSource, "Mutual TLS requires an https endpoint")
            assertFalse(generated.javaSource.contains("trustAll"))
            assertFalse(generated.javaSource.contains("HostnameVerifier"))
            assertContains(generated.reliabilityProperties, "private keys, trust material and bundle passwords outside")
        }
    }

    @Test
    fun `rejects unresolved mTLS runtime and OAuth2 invalid-token service`() {
        val unresolvedMtls = connector(
            kind = IntegrationConnectorKind.HTTP_CLIENT,
            responseJavaType = "java.lang.String",
        ).copy(
            transportSecurity = IntegrationTransportSecurityModel(
                mutualTlsEnabled = true,
                sslBundleNameProperty = "payroll.partner.ssl-bundle",
            ),
        )
        val mtlsValidation = IntegrationConnectorGenerator.validate(
            unresolvedMtls,
            setOf(IntegrationCapability.SPRING_WEB),
        )

        assertFalse(mtlsValidation.valid)
        assertTrue(mtlsValidation.diagnostics.any { it.code == "INTEGRATION_MTLS_BOOT_API_REQUIRED" })
        assertTrue(mtlsValidation.diagnostics.any { it.code == "INTEGRATION_DEPENDENCY_MISSING" })

        val unresolvedOAuth2Service = connector(
            kind = IntegrationConnectorKind.IDENTITY_PROVIDER,
            responseJavaType = "java.lang.String",
            authentication = IntegrationAuthenticationModel(
                kind = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
                authorizedClientManagerBeanName = "authorizedClientManager",
                clientRegistrationIdProperty = "identity.partner.registration-id",
                principalNameProperty = "identity.partner.principal-name",
                evictInvalidAuthorizedClient = true,
            ),
        )
        val oauthValidation = IntegrationConnectorGenerator.validate(
            unresolvedOAuth2Service,
            setOf(IntegrationCapability.SPRING_WEB, IntegrationCapability.OAUTH2_CLIENT),
        )

        assertFalse(oauthValidation.valid)
        assertTrue(
            oauthValidation.diagnostics.any {
                it.code == "INTEGRATION_OAUTH_CLIENT_SERVICE_REQUIRED"
            },
        )
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

package org.jmixworkbench.certification

import org.jmixworkbench.generator.AggregateUpdateServiceGenerator
import org.jmixworkbench.generator.AggregateUpdateServiceModel
import org.jmixworkbench.generator.DataRepositoryGenerator
import org.jmixworkbench.generator.EntityGenerator
import org.jmixworkbench.generator.KotlinDataRepositoryGenerator
import org.jmixworkbench.generator.KotlinEntityGenerator
import org.jmixworkbench.generator.IntegrationConnectorGenerator
import org.jmixworkbench.generator.OpenApiJmixLayerGenerator
import org.jmixworkbench.generator.ViewControllerGenerator
import org.jmixworkbench.model.AttributeModel
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.ComponentModel
import org.jmixworkbench.model.ComponentType
import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.MethodParameter
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationDeliveryGuarantee
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationAuthenticationModel
import org.jmixworkbench.model.IntegrationHttpMethod
import org.jmixworkbench.model.IntegrationIdempotencyModel
import org.jmixworkbench.model.IntegrationInboxModel
import org.jmixworkbench.model.IntegrationJsonApi
import org.jmixworkbench.model.IntegrationOutboxModel
import org.jmixworkbench.model.IntegrationRetryMode
import org.jmixworkbench.model.IntegrationRetryPolicyModel
import org.jmixworkbench.model.IntegrationSpringBootApi
import org.jmixworkbench.model.IntegrationTransportSecurityModel
import org.jmixworkbench.model.IntegrationCircuitBreakerModel
import org.jmixworkbench.model.IntegrationReliabilityModel
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationObservabilityModel
import org.jmixworkbench.model.IntegrationOpenApiBinding
import org.jmixworkbench.model.IntegrationOpenApiJmixLayerModel
import org.jmixworkbench.model.IntegrationOpenApiJmixTargetKind
import org.jmixworkbench.model.IntegrationOpenApiJmixTypeMapping
import org.jmixworkbench.model.IntegrationOpenApiMappingDirection
import org.jmixworkbench.model.IntegrationOpenApiOperationModel
import org.jmixworkbench.model.IntegrationOpenApiParameterLocation
import org.jmixworkbench.model.IntegrationOpenApiParameterModel
import org.jmixworkbench.model.IntegrationOpenApiPropertyModel
import org.jmixworkbench.model.IntegrationOpenApiPropertyMapping
import org.jmixworkbench.model.IntegrationOpenApiSchemaKind
import org.jmixworkbench.model.IntegrationOpenApiSchemaModel
import org.jmixworkbench.model.QueryType
import org.jmixworkbench.model.RepositoryMethod
import org.jmixworkbench.model.RepositoryParameterRole
import org.jmixworkbench.model.RepositoryQueryHint
import org.jmixworkbench.model.ViewModel
import org.jmixworkbench.model.ViewType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Produces a deterministic, enterprise-shaped source corpus directly from the
 * production generators. Gradle compiles this corpus against each certified
 * Jmix/JDK cell; no hand-maintained sample can silently drift from generator
 * behavior.
 */
object CompatibilityFixtureGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) {
            "Expected the generated compatibility source directory."
        }
        val outputRoot = Path.of(args.single()).toAbsolutePath().normalize()
        resetDirectory(outputRoot)

        val sources = linkedMapOf<String, String>()
        generateJavaCorpus(sources)
        generateKotlinCorpus(sources)
        generateAggregateServices(sources)
        generateDurableIntegrationAdapters(sources)
        generateOpenApiAdapter(sources)

        sources.toSortedMap().forEach { (relativePath, source) ->
            val target = outputRoot.resolve(relativePath).normalize()
            require(target.startsWith(outputRoot)) {
                "Generated compatibility path escaped its output root: $relativePath"
            }
            target.parent.createDirectories()
            Files.writeString(target, source, StandardCharsets.UTF_8)
        }
        Files.writeString(
            outputRoot.resolve("source-manifest.json"),
            manifest(sources),
            StandardCharsets.UTF_8,
        )
    }

    private fun generateJavaCorpus(sources: MutableMap<String, String>) {
        val entity = entityModel(
            packageName = "com.acme.cert.javaentity",
            language = EntitySourceLanguage.JAVA,
        )
        sources["common/java/com/acme/cert/javaentity/LoanApplication.java"] =
            EntityGenerator.generate(entity)
        sources["common/java/com/acme/cert/javaentity/LoanApplicationRepository.java"] =
            DataRepositoryGenerator.generate(entity)
        sources["common/java/com/acme/cert/javaview/LoanApplicationDetailViewController.java"] =
            ViewControllerGenerator.generate(
                ViewModel(
                    viewName = "LoanApplicationDetailView",
                    packageName = "com.acme.cert.javaview",
                    viewType = ViewType.DETAIL_VIEW,
                    entityClass = entity.fullName,
                    layout = ComponentModel("root", ComponentType.VBOX),
                ),
            )
    }

    private fun generateKotlinCorpus(sources: MutableMap<String, String>) {
        val entity = entityModel(
            packageName = "com.acme.cert.kotlinentity",
            language = EntitySourceLanguage.KOTLIN,
        )
        sources["common/kotlin/com/acme/cert/kotlinentity/LoanApplication.kt"] =
            KotlinEntityGenerator.generate(entity)
        sources["common/kotlin/com/acme/cert/kotlinentity/LoanApplicationRepository.kt"] =
            KotlinDataRepositoryGenerator.generate(entity)
    }

    private fun generateAggregateServices(sources: MutableMap<String, String>) {
        listOf(
            "jmix28" to false,
            "jmix30" to true,
        ).forEach { (line, platformDelegates) ->
            sources["$line/java/com/acme/cert/javaservice/LoanApplicationUpdateService.java"] =
                AggregateUpdateServiceGenerator.generate(
                    AggregateUpdateServiceModel(
                        className = "LoanApplicationUpdateService",
                        packageName = "com.acme.cert.javaservice",
                        entityQualifiedName = "com.acme.cert.javaentity.LoanApplication",
                        sourceLanguage = EntitySourceLanguage.JAVA,
                        transactionManagerBean = null,
                        platformDelegates = platformDelegates,
                    ),
                )
            sources["$line/kotlin/com/acme/cert/kotlinservice/LoanApplicationUpdateService.kt"] =
                AggregateUpdateServiceGenerator.generate(
                    AggregateUpdateServiceModel(
                        className = "LoanApplicationUpdateService",
                        packageName = "com.acme.cert.kotlinservice",
                        entityQualifiedName = "com.acme.cert.kotlinentity.LoanApplication",
                        sourceLanguage = EntitySourceLanguage.KOTLIN,
                        transactionManagerBean = null,
                        platformDelegates = platformDelegates,
                    ),
                )
        }
    }

    private fun generateDurableIntegrationAdapters(sources: MutableMap<String, String>) {
        sources["common/java/com/acme/cert/integration/LoanEventHandler.java"] =
            """
            package com.acme.cert.integration;

            import java.util.Set;
            import java.util.concurrent.ConcurrentHashMap;
            import java.util.concurrent.ConcurrentMap;
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.stereotype.Service;

            @Service
            public class LoanEventHandler {
                private final JdbcTemplate jdbcTemplate;
                private final ConcurrentMap<String, Integer> attempts = new ConcurrentHashMap<>();
                private final Set<String> releasedPoisonMessages = ConcurrentHashMap.newKeySet();

                public LoanEventHandler(JdbcTemplate jdbcTemplate) {
                    this.jdbcTemplate = jdbcTemplate;
                }

                public void handle(String payload) {
                    if (payload == null) {
                        throw new IllegalArgumentException("payload is required");
                    }
                    int attempt = attempts.merge(payload, 1, Integer::sum);
                    if (payload.startsWith("retry-once:") && attempt == 1) {
                        throw new IllegalStateException("certified transient handler failure");
                    }
                    if (payload.startsWith("poison:")
                            && !releasedPoisonMessages.contains(payload)) {
                        throw new IllegalStateException("certified poison message");
                    }
                    jdbcTemplate.update(
                            "INSERT INTO jvw_cert_handler_effect (payload, handled_at) VALUES (?, CURRENT_TIMESTAMP)",
                            payload);
                }

                public int attempts(String payload) {
                    return attempts.getOrDefault(payload, 0);
                }

                public void releasePoison(String payload) {
                    releasedPoisonMessages.add(payload);
                }
            }
            """.trimIndent() + "\n"
        listOf(
            "jmix28" to IntegrationJsonApi.JACKSON_2,
            "jmix30" to IntegrationJsonApi.JACKSON_3,
        ).forEach { (line, jsonApi) ->
            val kafkaModel = IntegrationConnectorModel(
                name = "Certified durable loan publisher",
                destinationId = "certified:main",
                packageName = "com.acme.cert.integration",
                className = "LoanEventPublisher",
                beanName = "loanEventPublisher",
                kind = IntegrationConnectorKind.KAFKA_PUBLISHER,
                runtimeJsonApi = jsonApi,
                configurationPrefix = "cert.loan-events",
                addressProperty = "cert.loan-events.topic",
                payloadJavaType = "java.lang.String",
                reliability = IntegrationReliabilityModel(
                    deliveryGuarantee = IntegrationDeliveryGuarantee.AT_LEAST_ONCE,
                    connectTimeoutMs = 500,
                    requestTimeoutMs = 2_000,
                    transactional = true,
                    orderingRequired = true,
                    outboxEnabled = true,
                    outbox = IntegrationOutboxModel(
                        storeId = "certified:main",
                        migrationPath = "src/main/resources/db/changelog/jvw-loan-outbox.xml",
                        tableName = "jvw_loan_event_outbox",
                        jsonApi = jsonApi,
                        leaseDurationMs = 10_000,
                        maxAttempts = 3,
                        initialBackoffMs = 100,
                        maximumBackoffMs = 1_000,
                    ),
                ),
                observability = IntegrationObservabilityModel(
                    metricsEnabled = true,
                    tracingEnabled = true,
                    structuredLoggingEnabled = true,
                    auditEnabled = true,
                    runtimeApi = IntegrationObservabilityApi.MICROMETER_OBSERVATION,
                ),
            )
            val generatedKafka = IntegrationConnectorGenerator.generate(kafkaModel)
            sources["$line/java/com/acme/cert/integration/LoanEventPublisher.java"] =
                generatedKafka.javaSource
            sources["$line/resources/META-INF/jvw/integration/loanEventPublisher.properties"] =
                generatedKafka.reliabilityProperties
            sources["$line/resources/db/changelog/jvw-loan-outbox.xml"] =
                requireNotNull(generatedKafka.migrationXml)

            val rabbitModel = kafkaModel.copy(
                name = "Certified durable payroll publisher",
                className = "PayrollEventPublisher",
                beanName = "payrollEventPublisher",
                kind = IntegrationConnectorKind.RABBIT_PUBLISHER,
                configurationPrefix = "cert.payroll-events",
                addressProperty = "cert.payroll-events.routing-key",
                reliability = kafkaModel.reliability.copy(
                    outbox = kafkaModel.reliability.outbox?.copy(
                        migrationPath = "src/main/resources/db/changelog/jvw-payroll-outbox.xml",
                        tableName = "jvw_payroll_event_outbox",
                    ),
                ),
            )
            val generatedRabbit = IntegrationConnectorGenerator.generate(rabbitModel)
            sources["$line/java/com/acme/cert/integration/PayrollEventPublisher.java"] =
                generatedRabbit.javaSource
            sources["$line/resources/META-INF/jvw/integration/payrollEventPublisher.properties"] =
                generatedRabbit.reliabilityProperties
            sources["$line/resources/db/changelog/jvw-payroll-outbox.xml"] =
                requireNotNull(generatedRabbit.migrationXml)

            val kafkaConsumerModel = IntegrationConnectorModel(
                name = "Certified idempotent loan consumer",
                destinationId = "certified:main",
                packageName = "com.acme.cert.integration",
                className = "LoanEventConsumer",
                beanName = "loanEventConsumer",
                kind = IntegrationConnectorKind.KAFKA_CONSUMER,
                runtimeJsonApi = jsonApi,
                configurationPrefix = "cert.loan-consumer",
                addressProperty = "cert.loan-consumer.topic",
                payloadJavaType = "java.lang.String",
                handlerBeanClass = "com.acme.cert.integration.LoanEventHandler",
                handlerFieldName = "loanEventHandler",
                handlerMethod = "handle",
                reliability = IntegrationReliabilityModel(
                    deliveryGuarantee = IntegrationDeliveryGuarantee.AT_LEAST_ONCE,
                    connectTimeoutMs = 500,
                    requestTimeoutMs = 2_000,
                    retry = IntegrationRetryPolicyModel(
                        mode = IntegrationRetryMode.BLOCKING,
                        attempts = 3,
                        initialDelayMs = 100,
                        maximumDelayMs = 1_000,
                        deadLetterDestinationProperty = "cert.loan-consumer.dead-letter-topic",
                    ),
                    idempotency = IntegrationIdempotencyModel(
                        enabled = true,
                        headerName = "jvw-outbox-id",
                        keyParameterName = "messageId",
                    ),
                    transactional = true,
                    inboxEnabled = true,
                    inbox = IntegrationInboxModel(
                        storeId = "certified:main",
                        migrationPath = "src/main/resources/db/changelog/jvw-loan-inbox.xml",
                        tableName = "jvw_loan_event_inbox",
                        jsonApi = jsonApi,
                    ),
                ),
                observability = IntegrationObservabilityModel(
                    metricsEnabled = true,
                    tracingEnabled = true,
                    structuredLoggingEnabled = true,
                    auditEnabled = true,
                    runtimeApi = IntegrationObservabilityApi.MICROMETER_OBSERVATION,
                ),
            )
            val generatedKafkaConsumer = IntegrationConnectorGenerator.generate(kafkaConsumerModel)
            sources["$line/java/com/acme/cert/integration/LoanEventConsumer.java"] =
                generatedKafkaConsumer.javaSource
            sources["$line/resources/META-INF/jvw/integration/loanEventConsumer.properties"] =
                generatedKafkaConsumer.reliabilityProperties
            sources["$line/resources/db/changelog/jvw-loan-inbox.xml"] =
                requireNotNull(generatedKafkaConsumer.migrationXml)

            val rabbitConsumerModel = kafkaConsumerModel.copy(
                name = "Certified idempotent payroll consumer",
                className = "PayrollEventConsumer",
                beanName = "payrollEventConsumer",
                kind = IntegrationConnectorKind.RABBIT_CONSUMER,
                configurationPrefix = "cert.payroll-consumer",
                addressProperty = "cert.payroll-consumer.queue",
                reliability = kafkaConsumerModel.reliability.copy(
                    retry = kafkaConsumerModel.reliability.retry.copy(
                        deadLetterDestinationProperty = "cert.payroll-consumer.dead-letter-queue",
                    ),
                    inbox = kafkaConsumerModel.reliability.inbox?.copy(
                        migrationPath = "src/main/resources/db/changelog/jvw-payroll-inbox.xml",
                        tableName = "jvw_payroll_event_inbox",
                    ),
                ),
            )
            val generatedRabbitConsumer = IntegrationConnectorGenerator.generate(rabbitConsumerModel)
            sources["$line/java/com/acme/cert/integration/PayrollEventConsumer.java"] =
                generatedRabbitConsumer.javaSource
            sources["$line/resources/META-INF/jvw/integration/payrollEventConsumer.properties"] =
                generatedRabbitConsumer.reliabilityProperties
            sources["$line/resources/db/changelog/jvw-payroll-inbox.xml"] =
                requireNotNull(generatedRabbitConsumer.migrationXml)

            val sftpUploadModel = IntegrationConnectorModel(
                name = "Certified atomic document upload",
                destinationId = "certified:main",
                packageName = "com.acme.cert.integration",
                className = "DocumentUploadConnector",
                beanName = "documentUploadConnector",
                kind = IntegrationConnectorKind.SFTP_UPLOAD,
                configurationPrefix = "cert.document-upload",
                addressProperty = "cert.sftp.base-path",
                payloadJavaType = "byte[]",
                reliability = IntegrationReliabilityModel(
                    deliveryGuarantee = IntegrationDeliveryGuarantee.AT_LEAST_ONCE,
                    connectTimeoutMs = 2_000,
                    requestTimeoutMs = 5_000,
                ),
            )
            val generatedSftpUpload =
                IntegrationConnectorGenerator.generate(sftpUploadModel)
            sources["$line/java/com/acme/cert/integration/DocumentUploadConnector.java"] =
                generatedSftpUpload.javaSource
            sources["$line/resources/META-INF/jvw/integration/documentUploadConnector.properties"] =
                generatedSftpUpload.reliabilityProperties

            val generatedSftpDownload = IntegrationConnectorGenerator.generate(
                sftpUploadModel.copy(
                    name = "Certified document download",
                    className = "DocumentDownloadConnector",
                    beanName = "documentDownloadConnector",
                    kind = IntegrationConnectorKind.SFTP_DOWNLOAD,
                    configurationPrefix = "cert.document-download",
                    responseJavaType = "byte[]",
                ),
            )
            sources["$line/java/com/acme/cert/integration/DocumentDownloadConnector.java"] =
                generatedSftpDownload.javaSource
            sources["$line/resources/META-INF/jvw/integration/documentDownloadConnector.properties"] =
                generatedSftpDownload.reliabilityProperties

            val httpModel = IntegrationConnectorModel(
                name = "Certified HRMS provider client",
                destinationId = "certified:main",
                packageName = "com.acme.cert.integration",
                className = "HrmsPartnerClient",
                beanName = "hrmsPartnerClient",
                kind = IntegrationConnectorKind.HTTP_CLIENT,
                configurationPrefix = "cert.hrms",
                addressProperty = "cert.hrms.address",
                payloadJavaType = "java.lang.String",
                responseJavaType = "java.lang.String",
                httpMethod = IntegrationHttpMethod.POST,
                authentication = IntegrationAuthenticationModel(
                    kind = IntegrationAuthenticationKind.API_KEY,
                    headerName = "X-Api-Key",
                    secretProperty = "cert.hrms.api-key",
                ),
                reliability = IntegrationReliabilityModel(
                    deliveryGuarantee = IntegrationDeliveryGuarantee.AT_LEAST_ONCE,
                    connectTimeoutMs = 500,
                    requestTimeoutMs = 750,
                    retry = IntegrationRetryPolicyModel(
                        mode = IntegrationRetryMode.BLOCKING,
                        attempts = 2,
                        initialDelayMs = 100,
                        maximumDelayMs = 100,
                    ),
                    circuitBreaker = IntegrationCircuitBreakerModel(
                        enabled = true,
                        slidingWindowSize = 10,
                        minimumCalls = 2,
                        failureRateThreshold = 50,
                        openStateMs = 10_000,
                    ),
                    idempotency = IntegrationIdempotencyModel(enabled = true),
                ),
            )
            val generatedHttp = IntegrationConnectorGenerator.generate(httpModel)
            sources["$line/java/com/acme/cert/integration/HrmsPartnerClient.java"] =
                generatedHttp.javaSource
            sources["$line/resources/META-INF/jvw/integration/hrmsPartnerClient.properties"] =
                generatedHttp.reliabilityProperties

            val secureIdentityModel = IntegrationConnectorModel(
                name = "Certified OAuth2 mTLS identity client",
                destinationId = "certified:main",
                packageName = "com.acme.cert.integration",
                className = "SecureIdentityClient",
                beanName = "secureIdentityClient",
                kind = IntegrationConnectorKind.IDENTITY_PROVIDER,
                configurationPrefix = "cert.identity",
                addressProperty = "cert.identity.address",
                responseJavaType = "java.lang.String",
                httpMethod = IntegrationHttpMethod.GET,
                authentication = IntegrationAuthenticationModel(
                    kind = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
                    authorizedClientManagerBeanName = "authorizedClientManager",
                    authorizedClientServiceBeanName = "authorizedClientService",
                    clientRegistrationIdProperty = "cert.identity.registration-id",
                    principalNameProperty = "cert.identity.principal-name",
                    evictInvalidAuthorizedClient = true,
                ),
                transportSecurity = IntegrationTransportSecurityModel(
                    mutualTlsEnabled = true,
                    sslBundleNameProperty = "cert.identity.ssl-bundle",
                ),
                runtimeSpringBootApi = if (line == "jmix30") {
                    IntegrationSpringBootApi.BOOT_4
                } else {
                    IntegrationSpringBootApi.BOOT_3
                },
            )
            val generatedSecureIdentity = IntegrationConnectorGenerator.generate(secureIdentityModel)
            sources["$line/java/com/acme/cert/integration/SecureIdentityClient.java"] =
                generatedSecureIdentity.javaSource
            sources["$line/resources/META-INF/jvw/integration/secureIdentityClient.properties"] =
                generatedSecureIdentity.reliabilityProperties

            listOf(
                Triple(
                    "SecureIdentityNoClientCertificateClient",
                    "secureIdentityNoClientCertificateClient",
                    "cert.identity.trust-only-ssl-bundle",
                ),
                Triple(
                    "SecureIdentityUntrustedServerClient",
                    "secureIdentityUntrustedServerClient",
                    "cert.identity.untrusted-ssl-bundle",
                ),
                Triple(
                    "SecureIdentityHostnameMismatchClient",
                    "secureIdentityHostnameMismatchClient",
                    "cert.identity.ssl-bundle",
                ),
            ).forEach { (className, beanName, sslBundleProperty) ->
                val variantModel = secureIdentityModel.copy(
                    name = "Certified negative-path $className",
                    className = className,
                    beanName = beanName,
                    configurationPrefix = "cert.identity.${beanName.lowercase()}",
                    addressProperty = if (className == "SecureIdentityHostnameMismatchClient") {
                        "cert.identity.hostname-mismatch-address"
                    } else {
                        "cert.identity.address"
                    },
                    transportSecurity = secureIdentityModel.transportSecurity.copy(
                        sslBundleNameProperty = sslBundleProperty,
                    ),
                )
                val generatedVariant = IntegrationConnectorGenerator.generate(variantModel)
                sources["$line/java/com/acme/cert/integration/$className.java"] =
                    generatedVariant.javaSource
                sources["$line/resources/META-INF/jvw/integration/$beanName.properties"] =
                    generatedVariant.reliabilityProperties
            }
        }
    }

    private fun generateOpenApiAdapter(sources: MutableMap<String, String>) {
        val binding = IntegrationOpenApiBinding(
            relativePath = "src/main/resources/openapi/certified-payments.yaml",
            documentSha256 = "a".repeat(64),
            specificationVersion = "3.1.1",
            operationId = "submitCertifiedPayment",
            method = IntegrationHttpMethod.POST,
            path = "/payments/{accountId}",
            requestMediaType = "application/json",
            responseStatus = "201",
            responseMediaType = "application/json",
        )
        val operation = IntegrationOpenApiOperationModel(
            contractPath = binding.relativePath,
            contractSha256 = binding.documentSha256,
            specificationVersion = binding.specificationVersion,
            title = "Certified Payments",
            apiVersion = "1.0.0",
            operationId = binding.operationId,
            javaMethodName = "submitCertifiedPayment",
            method = binding.method,
            path = binding.path,
            deprecated = false,
            requestMediaType = binding.requestMediaType,
            requestRequired = true,
            requestSchemaId = "component:PaymentRequest",
            responseStatus = binding.responseStatus,
            responseMediaType = binding.responseMediaType,
            responseSchemaId = "component:PaymentReceipt",
            parameters = listOf(
                IntegrationOpenApiParameterModel(
                    wireName = "accountId",
                    javaName = "accountId",
                    location = IntegrationOpenApiParameterLocation.PATH,
                    schemaId = "inline:account-id",
                    required = true,
                    style = "simple",
                    explode = false,
                ),
            ),
            schemas = listOf(
                IntegrationOpenApiSchemaModel(
                    id = "inline:account-id",
                    javaName = "AccountId",
                    kind = IntegrationOpenApiSchemaKind.UUID,
                    format = "uuid",
                ),
                IntegrationOpenApiSchemaModel(
                    id = "inline:amount",
                    javaName = "Amount",
                    kind = IntegrationOpenApiSchemaKind.NUMBER,
                    format = "decimal",
                ),
                IntegrationOpenApiSchemaModel(
                    id = "inline:reference",
                    javaName = "Reference",
                    kind = IntegrationOpenApiSchemaKind.STRING,
                ),
                IntegrationOpenApiSchemaModel(
                    id = "inline:status",
                    javaName = "PaymentStatus",
                    kind = IntegrationOpenApiSchemaKind.STRING,
                    enumValues = listOf("accepted", "rejected"),
                ),
                IntegrationOpenApiSchemaModel(
                    id = "inline:receipt-id",
                    javaName = "ReceiptId",
                    kind = IntegrationOpenApiSchemaKind.UUID,
                    format = "uuid",
                ),
                IntegrationOpenApiSchemaModel(
                    id = "component:PaymentRequest",
                    javaName = "PaymentRequest",
                    kind = IntegrationOpenApiSchemaKind.OBJECT,
                    properties = listOf(
                        IntegrationOpenApiPropertyModel(
                            wireName = "amount",
                            javaName = "amount",
                            schemaId = "inline:amount",
                            required = true,
                            nullable = false,
                        ),
                        IntegrationOpenApiPropertyModel(
                            wireName = "external-reference",
                            javaName = "externalReference",
                            schemaId = "inline:reference",
                            required = true,
                            nullable = false,
                        ),
                    ),
                ),
                IntegrationOpenApiSchemaModel(
                    id = "component:PaymentReceipt",
                    javaName = "PaymentReceipt",
                    kind = IntegrationOpenApiSchemaKind.OBJECT,
                    properties = listOf(
                        IntegrationOpenApiPropertyModel(
                            wireName = "id",
                            javaName = "id",
                            schemaId = "inline:receipt-id",
                            required = true,
                            nullable = false,
                            readOnly = true,
                        ),
                        IntegrationOpenApiPropertyModel(
                            wireName = "status",
                            javaName = "status",
                            schemaId = "inline:status",
                            required = true,
                            nullable = false,
                        ),
                    ),
                ),
            ),
        )
        val initial = IntegrationConnectorModel(
            name = "Certified OpenAPI payment client",
            destinationId = "certified:main",
            packageName = "com.acme.cert.integration",
            className = "CertifiedPaymentClient",
            beanName = "certifiedPaymentClient",
            kind = IntegrationConnectorKind.HTTP_CLIENT,
            configurationPrefix = "cert.payments",
            addressProperty = "cert.payments.base-url",
            payloadJavaType = "void",
            responseJavaType = "void",
            httpMethod = binding.method,
            contentType = requireNotNull(binding.requestMediaType),
            openApiBinding = binding,
            openApiJmixLayer = IntegrationOpenApiJmixLayerModel(
                enabled = true,
                dtoPackage = "com.acme.cert.entity.payment",
                mapperPackage = "com.acme.cert.integration.mapper",
                servicePackage = "com.acme.cert.service.payment",
                serviceClassName = "CertifiedPaymentService",
                serviceBeanName = "certifiedPaymentService",
                mappings = listOf(
                    IntegrationOpenApiJmixTypeMapping(
                        schemaId = "component:PaymentRequest",
                        targetKind = IntegrationOpenApiJmixTargetKind.GENERATED_DTO,
                        generatedClassName = "PaymentRequest",
                        properties = listOf(
                            IntegrationOpenApiPropertyMapping(
                                "amount",
                                "amount",
                                IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
                            ),
                            IntegrationOpenApiPropertyMapping(
                                "externalReference",
                                "externalReference",
                                IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
                            ),
                        ),
                    ),
                    IntegrationOpenApiJmixTypeMapping(
                        schemaId = "component:PaymentReceipt",
                        targetKind = IntegrationOpenApiJmixTargetKind.GENERATED_DTO,
                        generatedClassName = "PaymentReceipt",
                        idProperty = "id",
                        properties = listOf(
                            IntegrationOpenApiPropertyMapping(
                                "id",
                                "id",
                                IntegrationOpenApiMappingDirection.INBOUND,
                            ),
                            IntegrationOpenApiPropertyMapping(
                                "status",
                                "status",
                                IntegrationOpenApiMappingDirection.INBOUND,
                            ),
                        ),
                    ),
                ),
            ),
            resolvedOpenApiOperation = operation,
        )
        val model = initial.copy(
            payloadJavaType = IntegrationConnectorGenerator.openApiPayloadJavaType(
                operation,
                initial.className,
            ),
            responseJavaType = IntegrationConnectorGenerator.openApiResponseJavaType(
                operation,
                initial.className,
            ),
        )
        sources["common/java/com/acme/cert/integration/CertifiedPaymentClient.java"] =
            IntegrationConnectorGenerator.generate(model).javaSource
        val jmixLayer = OpenApiJmixLayerGenerator.generate(
            OpenApiJmixLayerGenerator.Input(
                connector = model,
                operation = operation,
                layer = requireNotNull(model.openApiJmixLayer),
                entityNamePrefix = "cert",
                existingTargets = emptyMap(),
            ),
        )
        require(jmixLayer.issues.isEmpty()) { jmixLayer.issues.joinToString() }
        jmixLayer.sources.forEach { source ->
            sources["common/java/${source.packageRelativePath}"] = source.content
        }
    }

    private fun entityModel(
        packageName: String,
        language: EntitySourceLanguage,
    ): EntityModel =
        EntityModel(
            className = "LoanApplication",
            packageName = packageName,
            sourceLanguage = language,
            entityName = "cert_LoanApplication",
            tableName = "CERT_LOAN_APPLICATION",
            attributes = mutableListOf(
                AttributeModel(
                    name = "applicantName",
                    type = AttributeType.STRING,
                    mandatory = true,
                    length = 180,
                ),
                AttributeModel(
                    name = "processState",
                    type = AttributeType.STRING,
                    mandatory = true,
                    length = 32,
                ),
            ),
            dataRepository = DataRepositoryConfig(
                enabled = true,
                methods = mutableListOf(
                    RepositoryMethod(
                        name = "findByApplicantNameContainingOrderByApplicantNameAsc",
                        returnType = "List<LoanApplication>",
                        parameters = mutableListOf(
                            MethodParameter("applicantName", "String"),
                        ),
                    ),
                    RepositoryMethod(
                        name = "findByProcessState",
                        returnType = "Page<LoanApplication>",
                        parameters = mutableListOf(
                            MethodParameter("processState", "String"),
                            MethodParameter(
                                "pageable",
                                "Pageable",
                                role = RepositoryParameterRole.PAGEABLE,
                            ),
                            MethodParameter(
                                "fetchPlan",
                                "FetchPlan",
                                role = RepositoryParameterRole.FETCH_PLAN,
                            ),
                        ),
                        query = "select e from cert_LoanApplication e " +
                            "where e.processState = :processState",
                        queryType = QueryType.JPQL,
                        fetchPlan = "loan-application-with-schedules",
                        applyConstraints = true,
                        queryHints = mutableListOf(
                            RepositoryQueryHint("jakarta.persistence.query.timeout", "5000"),
                        ),
                    ),
                ),
            ),
        )

    private fun manifest(sources: Map<String, String>): String =
        buildString {
            append("{\n")
            append("  \"schemaVersion\": \"generated-source-manifest-v1\",\n")
            append("  \"files\": [\n")
            sources.toSortedMap().entries.forEachIndexed { index, (path, source) ->
                append("    {\"path\":\"").append(path)
                    .append("\",\"sha256\":\"").append(sha256(source))
                    .append("\"}")
                if (index != sources.size - 1) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8)),
        )

    private fun resetDirectory(directory: Path) {
        if (directory.isDirectory()) {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder())
                    .forEach { path ->
                        if (path != directory || path.name.isNotEmpty()) {
                            path.deleteIfExists()
                        }
                    }
            }
        }
        directory.createDirectories()
    }
}

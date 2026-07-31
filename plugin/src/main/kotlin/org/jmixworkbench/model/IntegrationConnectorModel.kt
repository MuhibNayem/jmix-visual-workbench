package org.jmixworkbench.model

import org.jmixworkbench.discovery.model.SourceLocator

enum class IntegrationConnectorKind {
    HTTP_CLIENT,
    WEBHOOK,
    KAFKA_PUBLISHER,
    KAFKA_CONSUMER,
    RABBIT_PUBLISHER,
    RABBIT_CONSUMER,
    SFTP_UPLOAD,
    SFTP_DOWNLOAD,
    JMIX_EMAIL,
    JMIX_FILE_STORAGE,
    OBJECT_STORAGE,
    SMS_GATEWAY,
    PAYMENT_GATEWAY,
    IDENTITY_PROVIDER,
}

enum class IntegrationCapability {
    SPRING_WEB,
    SPRING_KAFKA,
    SPRING_BOOT_KAFKA,
    SPRING_AMQP,
    SPRING_INTEGRATION_SFTP,
    RESILIENCE4J,
    JMIX_EMAIL,
    JMIX_FILE_STORAGE,
    OAUTH2_CLIENT,
    SPRING_BOOT_SSL_BUNDLES,
}

enum class IntegrationHttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
}

enum class IntegrationDeliveryGuarantee {
    AT_MOST_ONCE,
    AT_LEAST_ONCE,
    EXACTLY_ONCE,
}

enum class IntegrationRetryMode {
    NONE,
    BLOCKING,
    NON_BLOCKING,
}

enum class IntegrationBackoffMode {
    FIXED,
    EXPONENTIAL,
}

enum class IntegrationJsonApi {
    JACKSON_2,
    JACKSON_3,
}

enum class IntegrationObservabilityApi {
    APPLICATION_EVENTS,
    MICROMETER_OBSERVATION,
}

enum class IntegrationSpringBootApi {
    BOOT_3,
    BOOT_4,
}

enum class IntegrationAuthenticationKind {
    NONE,
    BASIC,
    BEARER,
    API_KEY,
    OAUTH2_CLIENT_CREDENTIALS,
    SSH_KEY,
}

enum class IntegrationDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class IntegrationHeaderModel(
    val name: String,
    val valueProperty: String,
    val sensitive: Boolean = false,
)

data class IntegrationAuthenticationModel(
    val kind: IntegrationAuthenticationKind = IntegrationAuthenticationKind.NONE,
    val headerName: String? = null,
    val usernameProperty: String? = null,
    val secretProperty: String? = null,
    val tokenUriProperty: String? = null,
    val clientIdProperty: String? = null,
    val authorizedClientManagerBeanName: String? = null,
    val authorizedClientServiceBeanName: String? = null,
    val clientRegistrationIdProperty: String? = null,
    val principalNameProperty: String? = null,
    val evictInvalidAuthorizedClient: Boolean = true,
    val scopes: List<String> = emptyList(),
)

/**
 * Transport security is independent from application authentication: an HTTP
 * connector may use mTLS together with OAuth2 client credentials.
 *
 * The bundle name is externalized and the key/trust material remains owned by
 * Spring Boot's SSL-bundle configuration. Generated source never contains
 * certificate paths, passwords, private keys or trust-all behavior.
 */
data class IntegrationTransportSecurityModel(
    val mutualTlsEnabled: Boolean = false,
    val sslBundleNameProperty: String? = null,
)

data class IntegrationRetryPolicyModel(
    val mode: IntegrationRetryMode = IntegrationRetryMode.NONE,
    val attempts: Int = 1,
    val backoff: IntegrationBackoffMode = IntegrationBackoffMode.EXPONENTIAL,
    val initialDelayMs: Long = 500,
    val multiplier: Double = 2.0,
    val maximumDelayMs: Long = 30_000,
    val deadLetterDestinationProperty: String? = null,
)

data class IntegrationCircuitBreakerModel(
    val enabled: Boolean = false,
    val slidingWindowSize: Int = 100,
    val minimumCalls: Int = 20,
    val failureRateThreshold: Int = 50,
    val openStateMs: Long = 30_000,
)

data class IntegrationBulkheadModel(
    val enabled: Boolean = false,
    val maximumConcurrentCalls: Int = 25,
    val maximumWaitMs: Long = 0,
)

data class IntegrationRateLimitModel(
    val enabled: Boolean = false,
    val callsPerPeriod: Int = 100,
    val periodMs: Long = 1_000,
    val timeoutMs: Long = 0,
)

data class IntegrationIdempotencyModel(
    val enabled: Boolean = false,
    val headerName: String = "Idempotency-Key",
    val keyParameterName: String = "idempotencyKey",
)

/**
 * Persisted broker outbox configuration.
 *
 * [migrationPath] is assigned by the backend when the connector is first
 * created. It is deliberately source-owned so later visual edits can prove
 * that the adapter, policy and Liquibase migration still form one unit.
 */
data class IntegrationOutboxModel(
    val storeId: String,
    val migrationPath: String? = null,
    val tableName: String,
    val jsonApi: IntegrationJsonApi? = null,
    /**
     * Backend-owned Spring bean identities for the selected Jmix data store.
     * Browser input is overwritten from the indexed store before validation.
     */
    val dataSourceBean: String = "dataSource",
    val transactionManagerBean: String = "transactionManager",
    val batchSize: Int = 100,
    val pollDelayMs: Long = 1_000,
    val leaseDurationMs: Long = 60_000,
    val maxAttempts: Int = 12,
    val initialBackoffMs: Long = 1_000,
    val maximumBackoffMs: Long = 900_000,
    val retentionDays: Int = 30,
    val replayPermission: String = "jvw.integration.outbox.replay",
    val maintenancePermission: String = "jvw.integration.outbox.maintain",
)

/**
 * Persistent idempotent-receiver and dead-message ledger for broker consumers.
 *
 * The backend owns the selected store, migration, JSON API and Spring bean
 * identities. A browser request can choose an indexed store, but cannot forge
 * the concrete data source or transaction manager used by generated code.
 */
data class IntegrationInboxModel(
    val storeId: String,
    val migrationPath: String? = null,
    val tableName: String,
    val jsonApi: IntegrationJsonApi? = null,
    val dataSourceBean: String = "dataSource",
    val transactionManagerBean: String = "transactionManager",
    val messageIdHeader: String = "jvw-outbox-id",
    val maximumPayloadBytes: Int = 1_048_576,
    val maintenanceBatchSize: Int = 1_000,
    val retentionDays: Int = 90,
    val replayPermission: String = "jvw.integration.inbox.replay",
    val maintenancePermission: String = "jvw.integration.inbox.maintain",
)

data class IntegrationReliabilityModel(
    val deliveryGuarantee: IntegrationDeliveryGuarantee = IntegrationDeliveryGuarantee.AT_LEAST_ONCE,
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 30_000,
    val retry: IntegrationRetryPolicyModel = IntegrationRetryPolicyModel(),
    val circuitBreaker: IntegrationCircuitBreakerModel = IntegrationCircuitBreakerModel(),
    val bulkhead: IntegrationBulkheadModel = IntegrationBulkheadModel(),
    val rateLimit: IntegrationRateLimitModel = IntegrationRateLimitModel(),
    val idempotency: IntegrationIdempotencyModel = IntegrationIdempotencyModel(),
    val transactional: Boolean = false,
    val outboxEnabled: Boolean = false,
    val outbox: IntegrationOutboxModel? = null,
    val inboxEnabled: Boolean = false,
    val inbox: IntegrationInboxModel? = null,
    val orderingRequired: Boolean = false,
)

data class IntegrationObservabilityModel(
    val metricsEnabled: Boolean = true,
    val tracingEnabled: Boolean = true,
    val structuredLoggingEnabled: Boolean = true,
    val auditEnabled: Boolean = false,
    val runtimeApi: IntegrationObservabilityApi? = null,
    val redactHeaders: List<String> = listOf(
        "Authorization",
        "Proxy-Authorization",
        "X-Api-Key",
        "Cookie",
        "Set-Cookie",
    ),
)

data class IntegrationConnectorModel(
    val name: String,
    val description: String = "",
    val destinationId: String,
    val packageName: String,
    val className: String,
    val beanName: String,
    val kind: IntegrationConnectorKind,
    val configurationPrefix: String,
    val addressProperty: String,
    val payloadJavaType: String = "java.lang.String",
    val responseJavaType: String = "void",
    val httpMethod: IntegrationHttpMethod = IntegrationHttpMethod.POST,
    val contentType: String = "application/json",
    val handlerBeanClass: String? = null,
    val handlerFieldName: String? = null,
    val handlerMethod: String? = null,
    val headers: List<IntegrationHeaderModel> = emptyList(),
    val authentication: IntegrationAuthenticationModel = IntegrationAuthenticationModel(),
    val transportSecurity: IntegrationTransportSecurityModel = IntegrationTransportSecurityModel(),
    val reliability: IntegrationReliabilityModel = IntegrationReliabilityModel(),
    val observability: IntegrationObservabilityModel = IntegrationObservabilityModel(),
    val runtimeJsonApi: IntegrationJsonApi? = null,
    val runtimeSpringBootApi: IntegrationSpringBootApi? = null,
    val profiles: List<String> = emptyList(),
    val enabled: Boolean = true,
    val sourceLocator: SourceLocator? = null,
)

data class IntegrationDiagnostic(
    val code: String,
    val severity: IntegrationDiagnosticSeverity,
    val message: String,
)

data class IntegrationValidationResult(
    val valid: Boolean,
    val requiredCapabilities: Set<IntegrationCapability>,
    val diagnostics: List<IntegrationDiagnostic>,
)

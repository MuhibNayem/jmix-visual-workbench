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
    SPRING_AMQP,
    SPRING_INTEGRATION_SFTP,
    RESILIENCE4J,
    JMIX_EMAIL,
    JMIX_FILE_STORAGE,
    OAUTH2_CLIENT,
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
    val clientRegistrationIdProperty: String? = null,
    val principalNameProperty: String? = null,
    val scopes: List<String> = emptyList(),
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
    val reliability: IntegrationReliabilityModel = IntegrationReliabilityModel(),
    val observability: IntegrationObservabilityModel = IntegrationObservabilityModel(),
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

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

enum class IntegrationOpenApiParameterLocation {
    PATH,
    QUERY,
    HEADER,
    COOKIE,
}

enum class IntegrationOpenApiSchemaKind {
    OBJECT,
    ARRAY,
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    UUID,
    DATE,
    DATE_TIME,
    BINARY,
    ANY,
}

enum class IntegrationOpenApiSecuritySchemeKind {
    API_KEY,
    HTTP_BASIC,
    HTTP_BEARER,
    OAUTH2_CLIENT_CREDENTIALS,
    OAUTH2_OTHER,
    OPEN_ID_CONNECT,
    MUTUAL_TLS,
}

enum class IntegrationOpenApiJmixTargetKind {
    GENERATED_DTO,
    EXISTING_ENTITY,
}

enum class IntegrationOpenApiMappingDirection {
    INBOUND,
    OUTBOUND,
    BIDIRECTIONAL,
}

/**
 * Immutable coordinates for a source-owned Jmix entity selected as a mapping
 * target. Preview/apply resolves the artifact again from the schema index and
 * verifies the exact source revision; browser-supplied attributes and types
 * are never authoritative.
 */
data class IntegrationOpenApiExistingEntityBinding(
    val artifactId: String,
    val qualifiedName: String,
    val revisionFingerprint: String,
)

data class IntegrationOpenApiPropertyMapping(
    val schemaProperty: String,
    val entityProperty: String,
    val direction: IntegrationOpenApiMappingDirection = IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
)

/**
 * Mapping of one OpenAPI object schema to either a generated Jmix DTO entity
 * or an indexed existing Jmix entity.
 */
data class IntegrationOpenApiJmixTypeMapping(
    val schemaId: String,
    val targetKind: IntegrationOpenApiJmixTargetKind = IntegrationOpenApiJmixTargetKind.GENERATED_DTO,
    val generatedClassName: String? = null,
    val existingEntity: IntegrationOpenApiExistingEntityBinding? = null,
    val idProperty: String? = null,
    val instanceNameProperty: String? = null,
    val properties: List<IntegrationOpenApiPropertyMapping> = emptyList(),
)

/**
 * Optional Jmix-facing abstraction over a generated transport connector.
 *
 * Generated DTOs, mappers and the application service are owned together with
 * the connector and participate in the same preview, atomic apply and undo.
 */
data class IntegrationOpenApiJmixLayerModel(
    val enabled: Boolean = false,
    val dtoPackage: String,
    val mapperPackage: String,
    val servicePackage: String,
    val serviceClassName: String,
    val serviceBeanName: String,
    val mappings: List<IntegrationOpenApiJmixTypeMapping> = emptyList(),
)

data class IntegrationOpenApiSecuritySchemeModel(
    val name: String,
    val kind: IntegrationOpenApiSecuritySchemeKind,
    val parameterName: String? = null,
    val parameterLocation: IntegrationOpenApiParameterLocation? = null,
    val requiredScopes: List<String> = emptyList(),
)

/**
 * One OpenAPI Security Requirement Object. Schemes inside a requirement are
 * combined with AND; separate requirement objects are alternatives (OR).
 */
data class IntegrationOpenApiSecurityRequirementModel(
    val schemes: List<IntegrationOpenApiSecuritySchemeModel>,
)

/**
 * Immutable coordinates of a project-owned OpenAPI operation.
 *
 * The browser selects these coordinates, but never supplies the effective
 * operation or schema. Before preview/apply the backend reopens the project
 * file, verifies the digest, parses it without network access and reconstructs
 * [IntegrationOpenApiOperationModel].
 */
data class IntegrationOpenApiBinding(
    val relativePath: String,
    val documentSha256: String,
    val specificationVersion: String,
    val operationId: String?,
    val method: IntegrationHttpMethod,
    val path: String,
    val requestMediaType: String? = null,
    val responseStatus: String? = null,
    val responseMediaType: String? = null,
)

data class IntegrationOpenApiPropertyModel(
    val wireName: String,
    val javaName: String,
    val schemaId: String,
    val required: Boolean,
    val nullable: Boolean,
    val readOnly: Boolean = false,
    val writeOnly: Boolean = false,
)

data class IntegrationOpenApiValidationModel(
    val minimum: String? = null,
    val minimumExclusive: Boolean = false,
    val maximum: String? = null,
    val maximumExclusive: Boolean = false,
    val multiplesOf: List<String> = emptyList(),
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val patterns: List<String> = emptyList(),
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val uniqueItems: Boolean = false,
    val minProperties: Int? = null,
    val maxProperties: Int? = null,
    val constValue: String? = null,
)

data class IntegrationOpenApiSchemaModel(
    val id: String,
    val javaName: String,
    val kind: IntegrationOpenApiSchemaKind,
    val format: String? = null,
    val nullable: Boolean = false,
    val enumValues: List<String> = emptyList(),
    val properties: List<IntegrationOpenApiPropertyModel> = emptyList(),
    val itemSchemaId: String? = null,
    val additionalPropertiesSchemaId: String? = null,
    val additionalPropertiesAllowed: Boolean = false,
    val validation: IntegrationOpenApiValidationModel = IntegrationOpenApiValidationModel(),
)

data class IntegrationOpenApiParameterModel(
    val wireName: String,
    val javaName: String,
    val location: IntegrationOpenApiParameterLocation,
    val schemaId: String,
    val required: Boolean,
    val style: String?,
    val explode: Boolean?,
)

/**
 * Backend-derived, deterministic operation contract. The transient resolved
 * value is never persisted; a separately bounded backend-issued baseline may
 * be retained only as comparison evidence and is never trusted for generation.
 */
data class IntegrationOpenApiOperationModel(
    val contractPath: String,
    val contractSha256: String,
    val specificationVersion: String,
    val title: String,
    val apiVersion: String?,
    val operationId: String?,
    val javaMethodName: String,
    val method: IntegrationHttpMethod,
    val path: String,
    val deprecated: Boolean,
    val requestMediaType: String?,
    val requestRequired: Boolean,
    val requestSchemaId: String?,
    val responseStatus: String?,
    val responseMediaType: String?,
    val responseSchemaId: String?,
    val parameters: List<IntegrationOpenApiParameterModel>,
    val schemas: List<IntegrationOpenApiSchemaModel>,
    val securitySchemes: List<String> = emptyList(),
    val securityRequirements: List<IntegrationOpenApiSecurityRequirementModel> = emptyList(),
)

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

/**
 * Immutable organization-catalog coordinates.
 *
 * The backend reopens the signed cached bundle and resolves these exact
 * coordinates before preview or apply. A browser cannot invent a catalog
 * policy, weaken it, or redirect the binding to mutable catalog content.
 */
data class IntegrationConnectorCatalogBinding(
    val catalogId: String,
    val catalogVersion: String,
    val bundleSha256: String,
    val templateId: String,
    val templateVersion: String,
    /**
     * Short-lived native IntelliJ approval. It is never persisted in the
     * generated source marker.
     */
    val approvalCapability: String? = null,
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
    val catalogBinding: IntegrationConnectorCatalogBinding? = null,
    val openApiBinding: IntegrationOpenApiBinding? = null,
    val openApiJmixLayer: IntegrationOpenApiJmixLayerModel? = null,
    /**
     * Backend-derived semantic baseline of the selected operation. Unlike
     * [resolvedOpenApiOperation], this bounded snapshot is persisted so a
     * later provider-contract revision can be compared with the exact source
     * contract that generated the owned connector.
     */
    val openApiBaseline: IntegrationOpenApiOperationModel? = null,
    /** Short-lived backend capability; never persisted in generated source. */
    val openApiEvolutionCapability: String? = null,
    val resolvedOpenApiOperation: IntegrationOpenApiOperationModel? = null,
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

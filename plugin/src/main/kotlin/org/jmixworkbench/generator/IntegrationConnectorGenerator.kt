package org.jmixworkbench.generator

import com.google.gson.Gson
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationBackoffMode
import org.jmixworkbench.model.IntegrationCapability
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationDeliveryGuarantee
import org.jmixworkbench.model.IntegrationDiagnostic
import org.jmixworkbench.model.IntegrationDiagnosticSeverity
import org.jmixworkbench.model.IntegrationHttpMethod
import org.jmixworkbench.model.IntegrationJsonApi
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationRetryMode
import org.jmixworkbench.model.IntegrationValidationResult
import org.jmixworkbench.model.ChangeSetModel
import org.jmixworkbench.model.ColumnDef
import org.jmixworkbench.model.DbChange
import org.jmixworkbench.model.IndexColumnDef
import org.jmixworkbench.model.MigrationModel
import org.jmixworkbench.model.PreCondition
import org.jmixworkbench.model.PreConditionOutcome
import org.jmixworkbench.model.PreConditionType
import java.util.Base64

/**
 * Generates dependency-aware, source-owned enterprise integration adapters.
 *
 * No credential or endpoint literal is accepted. External addresses, topics,
 * queues, storage names and secrets are always injected through reviewed
 * configuration properties. Optional framework APIs are generated only after
 * the owning module proves the corresponding dependency capability.
 */
object IntegrationConnectorGenerator {
    private const val MARKER_PREFIX = "// JVW-INTEGRATION-MODEL: "
    private val gson = Gson()

    fun markerPrefix(): String = MARKER_PREFIX

    fun requiredCapabilities(model: IntegrationConnectorModel): Set<IntegrationCapability> = buildSet {
        when (model.kind) {
            IntegrationConnectorKind.HTTP_CLIENT,
            IntegrationConnectorKind.WEBHOOK,
            IntegrationConnectorKind.SMS_GATEWAY,
            IntegrationConnectorKind.PAYMENT_GATEWAY,
            -> add(IntegrationCapability.SPRING_WEB)

            IntegrationConnectorKind.IDENTITY_PROVIDER -> {
                add(IntegrationCapability.SPRING_WEB)
                add(IntegrationCapability.OAUTH2_CLIENT)
            }

            IntegrationConnectorKind.KAFKA_PUBLISHER,
            IntegrationConnectorKind.KAFKA_CONSUMER,
            -> add(IntegrationCapability.SPRING_KAFKA)

            IntegrationConnectorKind.RABBIT_PUBLISHER,
            IntegrationConnectorKind.RABBIT_CONSUMER,
            -> add(IntegrationCapability.SPRING_AMQP)

            IntegrationConnectorKind.SFTP_UPLOAD,
            IntegrationConnectorKind.SFTP_DOWNLOAD,
            -> add(IntegrationCapability.SPRING_INTEGRATION_SFTP)

            IntegrationConnectorKind.JMIX_EMAIL -> add(IntegrationCapability.JMIX_EMAIL)
            IntegrationConnectorKind.JMIX_FILE_STORAGE,
            IntegrationConnectorKind.OBJECT_STORAGE,
            -> add(IntegrationCapability.JMIX_FILE_STORAGE)
        }
        if (
            model.reliability.retry.mode == IntegrationRetryMode.BLOCKING ||
            model.reliability.circuitBreaker.enabled ||
            model.reliability.bulkhead.enabled ||
            model.reliability.rateLimit.enabled
        ) {
            add(IntegrationCapability.RESILIENCE4J)
        }
        if (model.authentication.kind == IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS) {
            add(IntegrationCapability.OAUTH2_CLIENT)
        }
    }

    fun validate(
        model: IntegrationConnectorModel,
        availableCapabilities: Set<IntegrationCapability> = requiredCapabilities(model),
    ): IntegrationValidationResult {
        val diagnostics = mutableListOf<IntegrationDiagnostic>()
        fun error(code: String, message: String) {
            diagnostics += IntegrationDiagnostic(code, IntegrationDiagnosticSeverity.ERROR, message)
        }
        fun warning(code: String, message: String) {
            diagnostics += IntegrationDiagnostic(code, IntegrationDiagnosticSeverity.WARNING, message)
        }

        if (model.name.isBlank()) error("INTEGRATION_NAME_REQUIRED", "Connector name is required.")
        if (!isJavaIdentifier(model.className)) {
            error("INTEGRATION_CLASS_INVALID", "Connector Java class must be a valid identifier.")
        }
        if (!isJavaIdentifier(model.beanName)) {
            error("INTEGRATION_BEAN_INVALID", "Spring bean name must be a valid identifier.")
        }
        if (!isPackageName(model.packageName)) {
            error("INTEGRATION_PACKAGE_INVALID", "Connector package name is invalid.")
        }
        if (!isPropertyKey(model.configurationPrefix)) {
            error("INTEGRATION_PREFIX_INVALID", "Configuration prefix must be a lowercase externalized property prefix.")
        }
        if (!isPropertyKey(model.addressProperty)) {
            error("INTEGRATION_ADDRESS_PROPERTY_INVALID", "Endpoint, topic, queue, or storage must be supplied by a property key.")
        }
        if (!isSafeJavaType(model.payloadJavaType) || !isSafeJavaType(model.responseJavaType)) {
            error("INTEGRATION_JAVA_TYPE_INVALID", "Payload and response Java types must be safe declared types.")
        }
        if (model.profiles.any { !PROFILE.matches(it) }) {
            error("INTEGRATION_PROFILE_INVALID", "Spring profiles may contain letters, digits, dot, dash, and underscore.")
        }
        if (model.headers.map { it.name.lowercase() }.distinct().size != model.headers.size) {
            error("INTEGRATION_HEADER_DUPLICATE", "Connector header names must be unique ignoring case.")
        }
        model.headers.forEach { header ->
            if (!HTTP_HEADER.matches(header.name)) {
                error("INTEGRATION_HEADER_INVALID", "Invalid HTTP header name: ${header.name}.")
            }
            if (!isPropertyKey(header.valueProperty)) {
                error("INTEGRATION_HEADER_PROPERTY_INVALID", "Header '${header.name}' must use an externalized property key.")
            }
        }

        val auth = model.authentication
        if (
            model.kind == IntegrationConnectorKind.IDENTITY_PROVIDER &&
            auth.kind != IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS
        ) {
            error(
                "INTEGRATION_IDENTITY_OAUTH_REQUIRED",
                "Identity-provider connectors require OAuth2 client-credentials authentication.",
            )
        }
        when (auth.kind) {
            IntegrationAuthenticationKind.NONE -> Unit
            IntegrationAuthenticationKind.BASIC -> {
                requireProperty(auth.usernameProperty, "INTEGRATION_AUTH_USERNAME_REQUIRED", "Basic authentication username", ::error)
                requireProperty(auth.secretProperty, "INTEGRATION_AUTH_SECRET_REQUIRED", "Basic authentication password", ::error)
            }
            IntegrationAuthenticationKind.BEARER -> {
                requireProperty(auth.secretProperty, "INTEGRATION_AUTH_SECRET_REQUIRED", "Bearer token", ::error)
            }
            IntegrationAuthenticationKind.API_KEY -> {
                if (auth.headerName.isNullOrBlank() || !HTTP_HEADER.matches(auth.headerName)) {
                    error("INTEGRATION_AUTH_HEADER_INVALID", "API-key authentication requires a valid header name.")
                }
                requireProperty(auth.secretProperty, "INTEGRATION_AUTH_SECRET_REQUIRED", "API key", ::error)
            }
            IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS -> {
                if (
                    auth.authorizedClientManagerBeanName.isNullOrBlank() ||
                    !SPRING_BEAN_IDENTIFIER.matches(auth.authorizedClientManagerBeanName)
                ) {
                    error(
                        "INTEGRATION_OAUTH_MANAGER_REQUIRED",
                        "OAuth2 client credentials requires a selected OAuth2AuthorizedClientManager bean.",
                    )
                }
                requireProperty(
                    auth.clientRegistrationIdProperty,
                    "INTEGRATION_OAUTH_REGISTRATION_REQUIRED",
                    "OAuth2 client registration ID",
                    ::error,
                )
                requireProperty(
                    auth.principalNameProperty,
                    "INTEGRATION_OAUTH_PRINCIPAL_REQUIRED",
                    "OAuth2 application principal name",
                    ::error,
                )
            }
            IntegrationAuthenticationKind.SSH_KEY -> {
                if (model.kind !in SFTP_KINDS) {
                    error("INTEGRATION_SSH_AUTH_KIND", "SSH-key authentication is only valid for SFTP connectors.")
                }
                requireProperty(auth.secretProperty, "INTEGRATION_AUTH_SECRET_REQUIRED", "SSH private-key location", ::error)
            }
        }

        val reliability = model.reliability
        if (reliability.connectTimeoutMs !in 100..300_000) {
            error("INTEGRATION_CONNECT_TIMEOUT_RANGE", "Connect timeout must be between 100 ms and 5 minutes.")
        }
        if (reliability.requestTimeoutMs !in reliability.connectTimeoutMs..3_600_000) {
            error("INTEGRATION_REQUEST_TIMEOUT_RANGE", "Request timeout must be at least the connect timeout and no more than one hour.")
        }
        val retry = reliability.retry
        if (retry.mode == IntegrationRetryMode.NONE && retry.attempts != 1) {
            error("INTEGRATION_RETRY_DISABLED_ATTEMPTS", "Disabled retry must use exactly one attempt.")
        }
        if (retry.mode != IntegrationRetryMode.NONE && retry.attempts !in 2..20) {
            error("INTEGRATION_RETRY_ATTEMPTS_RANGE", "Retry attempts must be between 2 and 20.")
        }
        if (retry.initialDelayMs !in 0..3_600_000 || retry.maximumDelayMs !in retry.initialDelayMs..86_400_000) {
            error("INTEGRATION_RETRY_DELAY_RANGE", "Retry delays are outside the reviewed enterprise bounds.")
        }
        if (retry.backoff == IntegrationBackoffMode.EXPONENTIAL && retry.multiplier !in 1.1..10.0) {
            error("INTEGRATION_RETRY_MULTIPLIER_RANGE", "Exponential retry multiplier must be between 1.1 and 10.")
        }
        if (retry.mode == IntegrationRetryMode.NON_BLOCKING && model.kind !in MESSAGE_CONSUMER_KINDS) {
            error("INTEGRATION_NON_BLOCKING_RETRY_KIND", "Non-blocking retry is supported only for Kafka or Rabbit consumers.")
        }
        if (retry.mode == IntegrationRetryMode.NON_BLOCKING && model.kind == IntegrationConnectorKind.RABBIT_CONSUMER) {
            error(
                "INTEGRATION_RABBIT_NON_BLOCKING_RUNTIME",
                "Rabbit non-blocking retry generation requires a selected listener-container retry interceptor and is not inferred.",
            )
        }
        if (retry.mode != IntegrationRetryMode.NONE && model.kind in MESSAGE_CONSUMER_KINDS) {
            requireProperty(
                retry.deadLetterDestinationProperty,
                "INTEGRATION_DEAD_LETTER_REQUIRED",
                "Dead-letter destination",
                ::error,
            )
        }
        if (
            retry.mode == IntegrationRetryMode.NON_BLOCKING &&
            model.kind == IntegrationConnectorKind.KAFKA_CONSUMER &&
            reliability.transactional
        ) {
            error(
                "INTEGRATION_KAFKA_RETRY_TRANSACTION_CONFLICT",
                "Kafka non-blocking retry cannot be combined with container transactions.",
            )
        }
        if (
            retry.mode == IntegrationRetryMode.NON_BLOCKING &&
            model.kind == IntegrationConnectorKind.KAFKA_CONSUMER &&
            reliability.orderingRequired
        ) {
            error(
                "INTEGRATION_KAFKA_RETRY_ORDERING_CONFLICT",
                "Kafka non-blocking retry changes topic flow and cannot preserve strict record ordering.",
            )
        }
        if (
            model.kind in HTTP_KINDS &&
            model.httpMethod in setOf(
                IntegrationHttpMethod.POST,
                IntegrationHttpMethod.PATCH,
                IntegrationHttpMethod.DELETE,
            ) &&
            retry.mode != IntegrationRetryMode.NONE &&
            !reliability.idempotency.enabled
        ) {
            error(
                "INTEGRATION_NON_IDEMPOTENT_RETRY",
                "Retrying a non-idempotent HTTP operation requires an idempotency key.",
            )
        }
        if (reliability.idempotency.enabled) {
            if (!HTTP_HEADER.matches(reliability.idempotency.headerName)) {
                error("INTEGRATION_IDEMPOTENCY_HEADER_INVALID", "Idempotency header name is invalid.")
            }
            if (!isJavaIdentifier(reliability.idempotency.keyParameterName)) {
                error("INTEGRATION_IDEMPOTENCY_PARAMETER_INVALID", "Idempotency parameter name is invalid.")
            }
        }
        if (reliability.deliveryGuarantee == IntegrationDeliveryGuarantee.EXACTLY_ONCE) {
            when (model.kind) {
                IntegrationConnectorKind.KAFKA_PUBLISHER -> {
                    if (!reliability.transactional) {
                        error("INTEGRATION_KAFKA_EXACTLY_ONCE_TRANSACTION", "Kafka exactly-once publishing requires a transactional producer.")
                    }
                }
                IntegrationConnectorKind.JMIX_FILE_STORAGE,
                IntegrationConnectorKind.OBJECT_STORAGE,
                -> if (!reliability.idempotency.enabled) {
                    error("INTEGRATION_STORAGE_EXACTLY_ONCE_KEY", "Exactly-once storage requires an idempotency key strategy.")
                }
                else -> error(
                    "INTEGRATION_EXACTLY_ONCE_UNSUPPORTED",
                    "Exactly-once delivery is not claimed for ${model.kind.name.lowercase().replace('_', ' ')}.",
                )
            }
        }
        if (reliability.outboxEnabled && model.kind !in MESSAGE_PUBLISHER_KINDS) {
            error("INTEGRATION_OUTBOX_KIND", "Transactional outbox is valid only for broker publishers.")
        }
        if (reliability.outboxEnabled) {
            validateOutbox(model, ::error, ::warning)
        } else if (reliability.outbox != null) {
            error(
                "INTEGRATION_OUTBOX_DISABLED_CONFIGURATION",
                "Remove persisted outbox configuration when transactional outbox is disabled.",
            )
        }
        if (reliability.transactional && model.kind in HTTP_KINDS) {
            warning(
                "INTEGRATION_REMOTE_CALL_IN_TRANSACTION",
                "A remote HTTP call inside a database transaction can retain locks; prefer an outbox or after-commit dispatch.",
            )
        }
        validateCircuitBreaker(model, ::error)
        validateBulkhead(model, ::error)
        validateRateLimit(model, ::error)

        if (model.kind in CONSUMER_KINDS) {
            if (!isSafeJavaType(model.handlerBeanClass.orEmpty())) {
                error("INTEGRATION_HANDLER_BEAN_REQUIRED", "Inbound connectors require an indexed handler bean Java type.")
            }
            if (!isJavaIdentifier(model.handlerFieldName.orEmpty()) || !isJavaIdentifier(model.handlerMethod.orEmpty())) {
                error("INTEGRATION_HANDLER_METHOD_REQUIRED", "Inbound connectors require safe handler field and method names.")
            }
        }
        if (model.kind in SFTP_KINDS && model.payloadJavaType != "byte[]") {
            error("INTEGRATION_SFTP_PAYLOAD_TYPE", "SFTP connectors use byte[] payloads to preserve binary content.")
        }

        val required = requiredCapabilities(model)
        (required - availableCapabilities).sortedBy { it.name }.forEach { capability ->
            error(
                "INTEGRATION_DEPENDENCY_MISSING",
                "The selected module does not expose required capability ${capability.name}. Add or select the reviewed dependency before generation.",
            )
        }
        return IntegrationValidationResult(
            valid = diagnostics.none { it.severity == IntegrationDiagnosticSeverity.ERROR },
            requiredCapabilities = required,
            diagnostics = diagnostics.distinct().sortedWith(
                compareBy(IntegrationDiagnostic::severity, IntegrationDiagnostic::code, IntegrationDiagnostic::message),
            ),
        )
    }

    fun generate(
        model: IntegrationConnectorModel,
        encodedModel: String = encode(model),
    ): GeneratedIntegrationConnector {
        val validation = validate(model)
        require(validation.valid) {
            validation.diagnostics.filter { it.severity == IntegrationDiagnosticSeverity.ERROR }.joinToString {
                "${it.code}: ${it.message}"
            }
        }
        if (model.reliability.outboxEnabled) {
            val migrationPath = model.reliability.outbox?.migrationPath
            require(
                migrationPath != null &&
                    !migrationPath.startsWith('/') &&
                    migrationPath.endsWith(".xml") &&
                    ".." !in migrationPath.replace('\\', '/').split('/'),
            ) {
                "INTEGRATION_OUTBOX_MIGRATION_PATH_REQUIRED: Backend-owned relative Liquibase migration evidence is required."
            }
        }
        return GeneratedIntegrationConnector(
            javaSource = renderJava(model, encodedModel),
            reliabilityProperties = renderReliabilityProperties(model, encodedModel),
            migrationXml = model.reliability.outbox
                ?.takeIf { model.reliability.outboxEnabled && it.migrationPath != null }
                ?.let { MigrationGenerator.generate(outboxMigration(model, requireNotNull(it.migrationPath))) },
            requiredCapabilities = validation.requiredCapabilities,
        )
    }

    fun outboxMigration(model: IntegrationConnectorModel, migrationPath: String? = null): MigrationModel {
        val outbox = requireNotNull(model.reliability.outbox) {
            "INTEGRATION_OUTBOX_CONFIGURATION_REQUIRED: Persisted outbox configuration is required."
        }
        val readyIndex = databaseName("ix", outbox.tableName, "ready")
        val orderIndex = databaseName("ix", outbox.tableName, "order")
        val changes = mutableListOf<DbChange>(
            DbChange.CreateTable(
                tableName = outbox.tableName,
                remarks = "Jmix Visual Workbench durable integration outbox",
                columns = mutableListOf(
                    ColumnDef("id", "varchar(36)", nullable = false, primaryKey = true),
                    ColumnDef("partition_key", "varchar(255)"),
                    ColumnDef("payload", "clob", nullable = false),
                    ColumnDef("payload_sha256", "varchar(64)", nullable = false),
                    ColumnDef("status", "varchar(24)", nullable = false),
                    ColumnDef("attempts", "int", nullable = false, defaultValue = "0"),
                    ColumnDef("available_at", "datetime", nullable = false),
                    ColumnDef("locked_by", "varchar(128)"),
                    ColumnDef("locked_until", "datetime"),
                    ColumnDef("created_at", "datetime", nullable = false),
                    ColumnDef("sent_at", "datetime"),
                    ColumnDef("last_error", "varchar(2000)"),
                    ColumnDef("version", "bigint", nullable = false, defaultValue = "0"),
                ),
            ),
            DbChange.CreateIndex(
                tableName = outbox.tableName,
                indexName = readyIndex,
                columns = listOf(
                    IndexColumnDef("status"),
                    IndexColumnDef("available_at"),
                    IndexColumnDef("locked_until"),
                ),
            ),
        )
        if (model.reliability.orderingRequired) {
            changes += DbChange.CreateIndex(
                tableName = outbox.tableName,
                indexName = orderIndex,
                columns = listOf(
                    IndexColumnDef("partition_key"),
                    IndexColumnDef("created_at"),
                    IndexColumnDef("id"),
                ),
            )
        }
        val rollback = mutableListOf<DbChange>()
        rollback += DbChange.DropTable(outbox.tableName)
        return MigrationModel(
            changelogId = "jvw-outbox-${model.beanName}",
            logicalFilePath = migrationPath?.let(::classpathPath),
            changes = mutableListOf(
                ChangeSetModel(
                    id = "jvw-outbox-${model.beanName}-1",
                    comment = "Create durable at-least-once outbox for ${model.beanName}",
                    preConditions = mutableListOf(
                        PreCondition(
                            PreConditionType.TABLE_NOT_EXISTS,
                            mutableMapOf("tableName" to outbox.tableName),
                        ),
                    ),
                    preConditionOnFail = PreConditionOutcome.HALT,
                    preConditionOnError = PreConditionOutcome.HALT,
                    changes = changes,
                    rollback = rollback,
                ),
            ),
        )
    }

    fun encode(model: IntegrationConnectorModel): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            gson.toJson(model.copy(sourceLocator = null)).toByteArray(Charsets.UTF_8),
        )

    private fun renderJava(model: IntegrationConnectorModel, encodedModel: String): String {
        val imports = imports(model)
        val constructorFields = injectedFields(model)
        return buildString {
            append("package ").append(model.packageName).append(";\n\n")
            imports.sorted().forEach { append("import ").append(it).append(";\n") }
            append('\n').append(MARKER_PREFIX).append(encodedModel).append('\n')
            append("@SuppressWarnings(\"JVW-INTEGRATION-CONNECTOR\")\n")
            append("@Component(\"").append(escapeJava(model.beanName)).append("\")\n")
            if (model.reliability.outboxEnabled) {
                append("@EnableScheduling\n")
            }
            if (model.profiles.isNotEmpty()) {
                append("@Profile({")
                append(model.profiles.joinToString { "\"${escapeJava(it)}\"" })
                append("})\n")
            }
            append("@PropertySource(\"classpath:META-INF/jvw/integration/")
                .append(model.beanName).append(".properties\")\n")
            model.description.takeIf(String::isNotBlank)?.let {
                append("/** ").append(safeComment(it)).append(" */\n")
            }
            // Spring transaction and Resilience4j annotations use class-based
            // proxies when the generated adapter has no interface.
            append("public class ").append(model.className).append(" {\n")
            if (model.observability.structuredLoggingEnabled) {
                append("    private static final Logger log = LoggerFactory.getLogger(")
                    .append(model.className).append(".class);\n")
            }
            if (model.reliability.outboxEnabled) {
                append("    private final Clock clock = Clock.systemUTC();\n")
                append("    private final String dispatcherId = \"")
                    .append(escapeJava(model.beanName)).append("-\" + UUID.randomUUID();\n")
            }
            constructorFields.forEach { field ->
                append("    private final ").append(field.javaType).append(' ').append(field.name).append(";\n")
            }
            append('\n')
            append("    public ").append(model.className).append("(\n")
            val constructorParameters = constructorFields.filter(InjectedField::constructorParameter)
            append(
                constructorParameters.joinToString(",\n") { field ->
                    buildString {
                        append("            ")
                        field.qualifier?.let {
                            append("@Qualifier(\"").append(escapeJava(it)).append("\") ")
                        }
                        field.propertyKey?.let {
                            append("@Value(\"").append(propertyExpression(it)).append("\") ")
                        }
                        append(field.javaType).append(' ').append(field.name)
                    }
                },
            )
            append("\n    ) {\n")
            constructorParameters.forEach { field ->
                append("        this.").append(field.name).append(" = ").append(field.name).append(";\n")
            }
            if (model.kind in HTTP_KINDS) {
                append("        HttpClient httpClient = HttpClient.newBuilder()\n")
                append("                .connectTimeout(Duration.ofMillis(")
                    .append(model.reliability.connectTimeoutMs).append("L))\n")
                append("                .build();\n")
                append("        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);\n")
                append("        requestFactory.setReadTimeout(Duration.ofMillis(")
                    .append(model.reliability.requestTimeoutMs).append("L));\n")
                append("        this.restClient = restClientBuilder.requestFactory(requestFactory).build();\n")
            }
            if (model.kind in setOf(IntegrationConnectorKind.JMIX_FILE_STORAGE, IntegrationConnectorKind.OBJECT_STORAGE)) {
                append("        this.fileStorage = fileStorageLocator.getByName(storageName);\n")
            }
            if (model.reliability.outboxEnabled) {
                append("        this.jdbcTemplate = new JdbcTemplate(dataSource);\n")
                append("        this.outboxTransactions = new TransactionTemplate(transactionManager);\n")
            }
            append("    }\n\n")
            append(renderOperation(model))
            if (model.reliability.outboxEnabled) {
                append('\n').append(renderOutboxRuntime(model))
            }
            append("}\n")
        }
    }

    private fun renderOperation(model: IntegrationConnectorModel): String = when (model.kind) {
        in HTTP_KINDS -> renderHttpOperation(model)
        IntegrationConnectorKind.KAFKA_PUBLISHER ->
            if (model.reliability.outboxEnabled) renderOutboxEnqueue(model) else renderKafkaPublisher(model)
        IntegrationConnectorKind.KAFKA_CONSUMER -> renderKafkaConsumer(model)
        IntegrationConnectorKind.RABBIT_PUBLISHER ->
            if (model.reliability.outboxEnabled) renderOutboxEnqueue(model) else renderRabbitPublisher(model)
        IntegrationConnectorKind.RABBIT_CONSUMER -> renderRabbitConsumer(model)
        IntegrationConnectorKind.SFTP_UPLOAD -> renderSftpUpload(model)
        IntegrationConnectorKind.SFTP_DOWNLOAD -> renderSftpDownload(model)
        IntegrationConnectorKind.JMIX_EMAIL -> renderEmail(model)
        IntegrationConnectorKind.JMIX_FILE_STORAGE,
        IntegrationConnectorKind.OBJECT_STORAGE,
        -> renderFileStorage(model)
        else -> error("Unsupported connector kind: ${model.kind}")
    }

    private fun renderHttpOperation(model: IntegrationConnectorModel): String = buildString {
        appendResilienceAnnotations(model)
        append("    public ").append(model.responseJavaType).append(" exchange(")
        val parameters = mutableListOf<String>()
        if (model.httpMethod != IntegrationHttpMethod.GET) parameters += "${model.payloadJavaType} payload"
        if (model.reliability.idempotency.enabled) {
            parameters += "String ${model.reliability.idempotency.keyParameterName}"
        }
        append(parameters.joinToString()).append(") {\n")
        if (model.observability.structuredLoggingEnabled) {
            append("        log.debug(\"Calling integration connector ")
                .append(escapeJava(model.beanName)).append(" method=")
                .append(model.httpMethod.name).append("\");\n")
        }
        append("        RestClient.RequestBodySpec request = restClient.method(HttpMethod.")
            .append(model.httpMethod.name).append(")\n")
        append("                .uri(address)\n")
        append("                .contentType(MediaType.parseMediaType(\"")
            .append(escapeJava(model.contentType)).append("\"));\n")
        when (model.authentication.kind) {
            IntegrationAuthenticationKind.BASIC -> {
                append("        request = request.header(\"Authorization\", \"Basic \" + Base64.getEncoder().encodeToString((authUsername + \":\" + authSecret).getBytes(StandardCharsets.UTF_8)));\n")
            }
            IntegrationAuthenticationKind.BEARER -> {
                append("        request = request.header(\"Authorization\", \"Bearer \" + authSecret);\n")
            }
            IntegrationAuthenticationKind.API_KEY -> {
                append("        request = request.header(\"")
                    .append(escapeJava(model.authentication.headerName.orEmpty()))
                    .append("\", authSecret);\n")
            }
            IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS -> {
                append("        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(\n")
                append("                OAuth2AuthorizeRequest.withClientRegistrationId(clientRegistrationId)\n")
                append("                        .principal(oauthPrincipalName)\n")
                append("                        .build());\n")
                append("        if (authorizedClient == null) {\n")
                append("            throw new IllegalStateException(\"OAuth2 authorization failed for connector ")
                    .append(escapeJava(model.beanName)).append("\");\n")
                append("        }\n")
                append("        request = request.header(\"Authorization\", \"Bearer \" + authorizedClient.getAccessToken().getTokenValue());\n")
            }
            else -> Unit
        }
        model.headers.forEachIndexed { index, header ->
            append("        request = request.header(\"").append(escapeJava(header.name))
                .append("\", headerValue").append(index).append(");\n")
        }
        if (model.reliability.idempotency.enabled) {
            append("        request = request.header(\"")
                .append(escapeJava(model.reliability.idempotency.headerName)).append("\", ")
                .append(model.reliability.idempotency.keyParameterName).append(");\n")
        }
        if (model.httpMethod != IntegrationHttpMethod.GET) {
            append("        request = request.body(payload);\n")
        }
        if (model.responseJavaType == "void") {
            append("        request.retrieve().toBodilessEntity();\n")
        } else {
            append("        return Objects.requireNonNull(request.retrieve().body(")
                .append(rawClassLiteral(model.responseJavaType)).append("), \"Remote response body is required\");\n")
        }
        append("    }\n")
    }

    private fun renderKafkaPublisher(model: IntegrationConnectorModel): String = buildString {
        appendResilienceAnnotations(model)
        append("    public CompletableFuture<SendResult<String, ")
            .append(model.payloadJavaType).append(">> publish(String key, ")
            .append(model.payloadJavaType).append(" payload")
        if (model.reliability.idempotency.enabled) {
            append(", String ").append(model.reliability.idempotency.keyParameterName)
        }
        append(") {\n")
        if (model.observability.structuredLoggingEnabled) {
            append("        log.debug(\"Publishing Kafka record connector=")
                .append(escapeJava(model.beanName)).append("\");\n")
        }
        if (model.reliability.transactional) {
            append("        return kafkaTemplate.executeInTransaction(template -> template.send(address, key, payload));\n")
        } else {
            append("        return kafkaTemplate.send(address, key, payload);\n")
        }
        append("    }\n")
    }

    private fun renderKafkaConsumer(model: IntegrationConnectorModel): String = buildString {
        if (model.reliability.retry.mode == IntegrationRetryMode.NON_BLOCKING) {
            append("    @RetryableTopic(attempts = \"").append(model.reliability.retry.attempts)
                .append("\", backoff = @Backoff(delay = ")
                .append(model.reliability.retry.initialDelayMs)
            if (model.reliability.retry.backoff == IntegrationBackoffMode.EXPONENTIAL) {
                append(", multiplier = ").append(model.reliability.retry.multiplier)
                    .append(", maxDelay = ").append(model.reliability.retry.maximumDelayMs)
            }
            append("), dltTopicSuffix = \"-dlt\")\n")
        }
        append("    @KafkaListener(topics = \"").append(propertyExpression(model.addressProperty)).append("\")\n")
        append("    public void consume(").append(model.payloadJavaType).append(" payload) {\n")
        appendHandlerInvocation(model)
        append("    }\n")
    }

    private fun renderRabbitPublisher(model: IntegrationConnectorModel): String = buildString {
        appendResilienceAnnotations(model)
        append("    public void publish(").append(model.payloadJavaType).append(" payload")
        if (model.reliability.idempotency.enabled) {
            append(", String ").append(model.reliability.idempotency.keyParameterName)
        }
        append(") {\n")
        if (model.reliability.idempotency.enabled) {
            append("        CorrelationData correlation = new CorrelationData(")
                .append(model.reliability.idempotency.keyParameterName).append(");\n")
            append("        rabbitTemplate.convertAndSend(address, payload, correlation);\n")
        } else {
            append("        rabbitTemplate.convertAndSend(address, payload);\n")
        }
        append("    }\n")
    }

    private fun renderOutboxEnqueue(model: IntegrationConnectorModel): String {
        val outbox = requireNotNull(model.reliability.outbox)
        return buildString {
            append("    /**\n")
            append("     * Persists an event in the same database transaction as the caller's business change.\n")
            append("     * Delivery is durable at-least-once; consumers must deduplicate by the returned event ID.\n")
            append("     */\n")
            append("    @Transactional(\"").append(escapeJava(outbox.transactionManagerBean)).append("\")\n")
            append("    public String enqueue(String orderingKey, ").append(model.payloadJavaType).append(" payload) {\n")
            append("        Objects.requireNonNull(payload, \"payload is required\");\n")
            if (model.reliability.orderingRequired) {
                append("        if (orderingKey == null || orderingKey.isBlank()) {\n")
                append("            throw new IllegalArgumentException(\"orderingKey is required when strict ordering is enabled\");\n")
                append("        }\n")
            }
            append("        String eventId = UUID.randomUUID().toString();\n")
            append("        String payloadJson;\n")
            append("        try {\n")
            append("            payloadJson = objectMapper.writeValueAsString(payload);\n")
            append("        } catch (").append(
                if (outbox.jsonApi == IntegrationJsonApi.JACKSON_3) "JacksonException" else "JsonProcessingException",
            ).append(" exception) {\n")
            append("            throw new IllegalArgumentException(\"Integration payload cannot be serialized\", exception);\n")
            append("        }\n")
            append("        Instant now = Instant.now(clock);\n")
            append("        jdbcTemplate.update(\"INSERT INTO ").append(outbox.tableName)
                .append(" (id, partition_key, payload, payload_sha256, status, attempts, available_at, created_at, version) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, 0)\",\n")
            append("                eventId, orderingKey, payloadJson, sha256(payloadJson), Timestamp.from(now), Timestamp.from(now));\n")
            append("        recordMetric(\"enqueued\");\n")
            append("        return eventId;\n")
            append("    }\n")
        }
    }

    private fun renderOutboxRuntime(model: IntegrationConnectorModel): String {
        val outbox = requireNotNull(model.reliability.outbox)
        val table = outbox.tableName
        val orderingPredicate = if (model.reliability.orderingRequired) {
            " AND NOT EXISTS (SELECT 1 FROM $table earlier WHERE earlier.partition_key = candidate.partition_key AND (earlier.created_at < candidate.created_at OR (earlier.created_at = candidate.created_at AND earlier.id < candidate.id)) AND earlier.status <> 'SENT')"
        } else {
            ""
        }
        return buildString {
            append("    @Scheduled(fixedDelayString = \"\${").append(model.configurationPrefix)
                .append(".outbox.poll-delay-ms:").append(outbox.pollDelayMs).append("}\")\n")
            append("    public void dispatchOutbox() {\n")
            append("        int remaining = ").append(outbox.batchSize).append(";\n")
            append("        while (remaining > 0) {\n")
            append("            Instant now = Instant.now(clock);\n")
            append("            int queryLimit = remaining;\n")
            append("            List<String> candidates = jdbcTemplate.query(connection -> {\n")
            append("                PreparedStatement statement = connection.prepareStatement(\n")
            append("                        \"SELECT candidate.id FROM ").append(table)
                .append(" candidate WHERE candidate.status IN ('PENDING', 'RETRY', 'IN_FLIGHT') AND candidate.available_at <= ? AND (candidate.locked_until IS NULL OR candidate.locked_until < ?)")
                .append(orderingPredicate)
                .append(" ORDER BY candidate.created_at, candidate.id\");\n")
            append("                statement.setTimestamp(1, Timestamp.from(now));\n")
            append("                statement.setTimestamp(2, Timestamp.from(now));\n")
            append("                statement.setMaxRows(queryLimit);\n")
            append("                return statement;\n")
            append("            }, (resultSet, rowNumber) -> resultSet.getString(1));\n")
            append("            if (candidates.isEmpty()) break;\n")
            append("            candidates.forEach(this::claimAndDispatch);\n")
            append("            remaining -= candidates.size();\n")
            append("        }\n")
            append("    }\n\n")

            append("    private void claimAndDispatch(String eventId) {\n")
            append("        Instant now = Instant.now(clock);\n")
            append("        Boolean claimed = outboxTransactions.execute(status -> {\n")
            if (model.reliability.orderingRequired) {
                append("            Long blockers = jdbcTemplate.queryForObject(\n")
                append("                    \"SELECT COUNT(*) FROM ").append(table)
                    .append(" candidate WHERE candidate.id = ? AND EXISTS (SELECT 1 FROM ").append(table)
                    .append(" earlier WHERE earlier.partition_key = candidate.partition_key AND (earlier.created_at < candidate.created_at OR (earlier.created_at = candidate.created_at AND earlier.id < candidate.id)) AND earlier.status <> 'SENT')\",\n")
                append("                    Long.class, eventId);\n")
                append("            if (value(blockers) != 0L) return false;\n")
            }
            append("            return jdbcTemplate.update(\n")
            append("                \"UPDATE ").append(table)
                .append(" SET status = 'IN_FLIGHT', locked_by = ?, locked_until = ?, version = version + 1 WHERE id = ? AND status IN ('PENDING', 'RETRY', 'IN_FLIGHT') AND available_at <= ? AND (locked_until IS NULL OR locked_until < ?)\",\n")
            append("                dispatcherId, Timestamp.from(now.plusMillis(").append(outbox.leaseDurationMs)
                .append("L)), eventId, Timestamp.from(now), Timestamp.from(now)) == 1;\n")
            append("        });\n")
            append("        if (!Boolean.TRUE.equals(claimed)) return;\n")
            append("        OutboxRecord event = jdbcTemplate.queryForObject(\n")
            append("                \"SELECT id, partition_key, payload, payload_sha256, attempts FROM ").append(table)
                .append(" WHERE id = ? AND locked_by = ?\",\n")
            append("                (resultSet, rowNumber) -> new OutboxRecord(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getInt(5)), eventId, dispatcherId);\n")
            append("        if (event == null) return;\n")
            if (usesMicrometerObservation(model) && model.observability.tracingEnabled) {
                append("        Observation observation = startObservation();\n")
            }
            append("        try {\n")
            append("            if (!MessageDigest.isEqual(event.payloadSha256().getBytes(StandardCharsets.US_ASCII), sha256(event.payload()).getBytes(StandardCharsets.US_ASCII))) {\n")
            append("                throw new IllegalStateException(\"Persisted integration payload checksum mismatch\");\n")
            append("            }\n")
            append("            ").append(model.payloadJavaType).append(" payload = objectMapper.readValue(event.payload(), ")
                .append(rawClassLiteral(model.payloadJavaType)).append(");\n")
            append("            deliver(event, payload);\n")
            append("            Integer delivered = outboxTransactions.execute(status -> jdbcTemplate.update(\n")
            append("                    \"UPDATE ").append(table)
                .append(" SET status = 'SENT', sent_at = ?, locked_by = NULL, locked_until = NULL, last_error = NULL, version = version + 1 WHERE id = ? AND locked_by = ?\",\n")
            append("                    Timestamp.from(Instant.now(clock)), event.id(), dispatcherId));\n")
            append("            if (delivered == null || delivered != 1) throw new IllegalStateException(\"Outbox delivery acknowledgement could not be persisted\");\n")
            append("            recordMetric(\"delivered\");\n")
            if (usesMicrometerObservation(model) && model.observability.tracingEnabled) {
                append("            if (observation != null) observation.lowCardinalityKeyValue(\"outcome\", \"delivered\");\n")
            }
            append("        } catch (Exception exception) {\n")
            if (usesMicrometerObservation(model) && model.observability.tracingEnabled) {
                append("            if (observation != null) observation.error(exception).lowCardinalityKeyValue(\"outcome\", \"failed\");\n")
            }
            append("            markDeliveryFailure(event, exception);\n")
            if (usesMicrometerObservation(model) && model.observability.tracingEnabled) {
                append("        } finally {\n")
                append("            if (observation != null) observation.stop();\n")
            }
            append("        }\n")
            append("    }\n\n")

            append("    private void deliver(OutboxRecord event, ").append(model.payloadJavaType).append(" payload) throws Exception {\n")
            when (model.kind) {
                IntegrationConnectorKind.KAFKA_PUBLISHER -> {
                    append("        ProducerRecord<String, ").append(model.payloadJavaType)
                        .append("> record = new ProducerRecord<>(address, event.partitionKey(), payload);\n")
                    append("        record.headers().add(\"jvw-outbox-id\", event.id().getBytes(StandardCharsets.UTF_8));\n")
                    append("        kafkaTemplate.send(record).get(").append(model.reliability.requestTimeoutMs)
                        .append("L, TimeUnit.MILLISECONDS);\n")
                }
                IntegrationConnectorKind.RABBIT_PUBLISHER -> {
                    append("        CorrelationData correlation = new CorrelationData(event.id());\n")
                    append("        rabbitTemplate.convertAndSend(address, payload, message -> {\n")
                    append("            message.getMessageProperties().setMessageId(event.id());\n")
                    append("            message.getMessageProperties().setHeader(\"jvw-outbox-id\", event.id());\n")
                    append("            return message;\n")
                    append("        }, correlation);\n")
                    append("        CorrelationData.Confirm confirm = correlation.getFuture().get(")
                        .append(model.reliability.requestTimeoutMs).append("L, TimeUnit.MILLISECONDS);\n")
                    append("        if (!rabbitAcknowledged(confirm)) throw new IllegalStateException(\"RabbitMQ publisher confirm was negative\");\n")
                    append("        if (correlation.getReturned() != null) throw new IllegalStateException(\"RabbitMQ returned the unroutable outbox message\");\n")
                }
                else -> error("Unsupported outbox kind ${model.kind}")
            }
            append("    }\n\n")
            if (model.kind == IntegrationConnectorKind.RABBIT_PUBLISHER) {
                append("    private static boolean rabbitAcknowledged(CorrelationData.Confirm confirm) {\n")
                append("        for (String accessor : List.of(\"ack\", \"isAck\")) {\n")
                append("            try {\n")
                append("                return Boolean.TRUE.equals(confirm.getClass().getMethod(accessor).invoke(confirm));\n")
                append("            } catch (NoSuchMethodException ignored) {\n")
                append("                // Spring AMQP 3 exposes isAck(); Spring AMQP 4 adds ack().\n")
                append("            } catch (ReflectiveOperationException exception) {\n")
                append("                throw new IllegalStateException(\"RabbitMQ confirm state could not be read\", exception);\n")
                append("            }\n")
                append("        }\n")
                append("        throw new IllegalStateException(\"Unsupported Spring AMQP confirm contract\");\n")
                append("    }\n\n")
            }

            append("    private void markDeliveryFailure(OutboxRecord event, Exception exception) {\n")
            append("        int attempts = event.attempts() + 1;\n")
            append("        boolean dead = attempts >= ").append(outbox.maxAttempts).append(";\n")
            append("        long exponent = Math.min(30, Math.max(0, attempts - 1));\n")
            append("        long delay = Math.min(").append(outbox.maximumBackoffMs)
                .append("L, Math.multiplyExact(").append(outbox.initialBackoffMs)
                .append("L, 1L << exponent));\n")
            append("        Instant availableAt = Instant.now(clock).plusMillis(delay);\n")
            append("        Integer updated = outboxTransactions.execute(status -> jdbcTemplate.update(\n")
            append("                \"UPDATE ").append(table)
                .append(" SET status = ?, attempts = ?, available_at = ?, locked_by = NULL, locked_until = NULL, last_error = ?, version = version + 1 WHERE id = ? AND locked_by = ?\",\n")
            append("                dead ? \"DEAD\" : \"RETRY\", attempts, Timestamp.from(availableAt), safeError(exception), event.id(), dispatcherId));\n")
            append("        if (updated == null || updated != 1) throw new IllegalStateException(\"Outbox delivery failure could not be persisted\", exception);\n")
            append("        recordMetric(dead ? \"dead\" : \"retry\");\n")
            if (model.observability.structuredLoggingEnabled) {
                append("        log.warn(\"Integration delivery failed connector=")
                    .append(escapeJava(model.beanName))
                    .append(" eventId={} attempt={} terminal={}\", event.id(), attempts, dead);\n")
            }
            append("    }\n\n")

            append("    /** Replays only terminal events and requires an explicit Jmix specific permission. */\n")
            append("    @Transactional(\"").append(escapeJava(outbox.transactionManagerBean)).append("\")\n")
            append("    public void replay(String eventId, String reason) {\n")
            append("        requirePermission(\"").append(escapeJava(outbox.replayPermission)).append("\");\n")
            append("        String canonicalEventId = canonicalEventId(eventId);\n")
            append("        if (reason == null || reason.isBlank() || reason.length() > 500) {\n")
            append("            throw new IllegalArgumentException(\"A replay reason of 1 to 500 characters is required\");\n")
            append("        }\n")
            append("        int updated = jdbcTemplate.update(\"UPDATE ").append(table)
                .append(" SET status = 'RETRY', attempts = 0, available_at = ?, locked_by = NULL, locked_until = NULL, last_error = NULL, version = version + 1 WHERE id = ? AND status = 'DEAD'\",\n")
            append("                Timestamp.from(Instant.now(clock)), canonicalEventId);\n")
            append("        if (updated != 1) throw new IllegalStateException(\"Only one existing DEAD event can be replayed\");\n")
            if (model.observability.structuredLoggingEnabled) {
                append("        log.info(\"Integration outbox replay authorized connector=")
                    .append(escapeJava(model.beanName))
                    .append(" eventId={} reasonLength={}\", canonicalEventId, reason.length());\n")
            }
            append("        publishAudit(\"REPLAY\", canonicalEventId, reason, 1L);\n")
            append("        recordMetric(\"replayed\");\n")
            append("    }\n\n")

            append("    public OutboxHealth reconcile() {\n")
            append("        Instant now = Instant.now(clock);\n")
            append("        Long pending = jdbcTemplate.queryForObject(\"SELECT COUNT(*) FROM ").append(table)
                .append(" WHERE status IN ('PENDING', 'RETRY', 'IN_FLIGHT')\", Long.class);\n")
            append("        Long dead = jdbcTemplate.queryForObject(\"SELECT COUNT(*) FROM ").append(table)
                .append(" WHERE status = 'DEAD'\", Long.class);\n")
            append("        Long expiredLeases = jdbcTemplate.queryForObject(\"SELECT COUNT(*) FROM ").append(table)
                .append(" WHERE status = 'IN_FLIGHT' AND locked_until < ?\", Long.class, Timestamp.from(now));\n")
            append("        Timestamp oldest = jdbcTemplate.queryForObject(\"SELECT MIN(created_at) FROM ").append(table)
                .append(" WHERE status IN ('PENDING', 'RETRY', 'IN_FLIGHT')\", Timestamp.class);\n")
            append("        long oldestAgeMs = oldest == null ? 0L : Math.max(0L, Duration.between(oldest.toInstant(), now).toMillis());\n")
            if (model.reliability.orderingRequired) {
                append("        Long blocked = jdbcTemplate.queryForObject(\"SELECT COUNT(*) FROM ").append(table)
                    .append(" candidate WHERE candidate.status IN ('PENDING', 'RETRY', 'IN_FLIGHT') AND EXISTS (SELECT 1 FROM ")
                    .append(table)
                    .append(" earlier WHERE earlier.partition_key = candidate.partition_key AND (earlier.created_at < candidate.created_at OR (earlier.created_at = candidate.created_at AND earlier.id < candidate.id)) AND earlier.status <> 'SENT')\", Long.class);\n")
            } else {
                append("        Long blocked = 0L;\n")
            }
            append("        return new OutboxHealth(value(pending), value(dead), value(expiredLeases), value(blocked), oldestAgeMs);\n")
            append("    }\n\n")

            append("    @Transactional(\"").append(escapeJava(outbox.transactionManagerBean)).append("\")\n")
            append("    public int purgeDeliveredBefore(Instant cutoff, String reason) {\n")
            append("        requirePermission(\"").append(escapeJava(outbox.maintenancePermission)).append("\");\n")
            append("        if (reason == null || reason.isBlank() || reason.length() > 500) {\n")
            append("            throw new IllegalArgumentException(\"A purge reason of 1 to 500 characters is required\");\n")
            append("        }\n")
            append("        Instant retentionFloor = Instant.now(clock).minus(Duration.ofDays(").append(outbox.retentionDays).append("L));\n")
            append("        if (cutoff == null || cutoff.isAfter(retentionFloor)) {\n")
            append("            throw new IllegalArgumentException(\"Purge cutoff must honor the configured retention period\");\n")
            append("        }\n")
            append("        List<String> candidates = jdbcTemplate.query(connection -> {\n")
            append("            PreparedStatement statement = connection.prepareStatement(\"SELECT id FROM ").append(table)
                .append(" WHERE status = 'SENT' AND sent_at < ? ORDER BY sent_at, id\");\n")
            append("            statement.setTimestamp(1, Timestamp.from(cutoff));\n")
            append("            statement.setMaxRows(").append(outbox.batchSize).append(");\n")
            append("            return statement;\n")
            append("        }, (resultSet, rowNumber) -> resultSet.getString(1));\n")
            append("        int deleted = 0;\n")
            append("        for (String eventId : candidates) {\n")
            append("            deleted += jdbcTemplate.update(\"DELETE FROM ").append(table)
                .append(" WHERE id = ? AND status = 'SENT' AND sent_at < ?\", eventId, Timestamp.from(cutoff));\n")
            append("        }\n")
            append("        publishAudit(\"PURGE\", cutoff.toString(), reason, deleted);\n")
            append("        return deleted;\n")
            append("    }\n\n")

            append("    private void requirePermission(String permission) {\n")
            append("        SpecificOperationAccessContext context = new SpecificOperationAccessContext(permission);\n")
            append("        accessManager.applyRegisteredConstraints(context);\n")
            append("        if (!context.isPermitted()) throw new AccessDeniedException(\"Specific permission is required: \" + permission);\n")
            append("    }\n\n")
            append("    private void recordMetric(String outcome) {\n")
            append("        eventPublisher.publishEvent(new OutboxTelemetryEvent(\"")
                .append(escapeJava(model.beanName)).append("\", outcome, Instant.now(clock)));\n")
            if (usesMicrometerObservation(model) && model.observability.metricsEnabled) {
                append("        MeterRegistry registry = meterRegistryProvider.getIfAvailable();\n")
                append("        if (registry != null) registry.counter(\"jvw.integration.outbox.events\", \"connector\", \"")
                    .append(escapeJava(model.beanName))
                    .append("\", \"kind\", \"").append(model.kind.name.lowercase())
                    .append("\", \"outcome\", outcome).increment();\n")
            }
            append("    }\n\n")
            if (usesMicrometerObservation(model) && model.observability.tracingEnabled) {
                append("    private Observation startObservation() {\n")
                append("        ObservationRegistry registry = observationRegistryProvider.getIfAvailable();\n")
                append("        if (registry == null || registry == ObservationRegistry.NOOP) return null;\n")
                append("        return Observation.start(\"jvw.integration.outbox.dispatch\", registry)\n")
                append("                .lowCardinalityKeyValue(\"connector\", \"")
                    .append(escapeJava(model.beanName)).append("\")\n")
                append("                .lowCardinalityKeyValue(\"kind\", \"")
                    .append(model.kind.name.lowercase()).append("\");\n")
                append("    }\n\n")
            }
            append("    private void publishAudit(String action, String subject, String reason, long affected) {\n")
            append("        String actor = currentAuthentication.isSet() ? currentAuthentication.getUser().getUsername() : \"system\";\n")
            append("        eventPublisher.publishEvent(new OutboxAuditEvent(\"")
                .append(escapeJava(model.beanName))
                .append("\", action, subject, actor, sha256(reason), affected, Instant.now(clock)));\n")
            append("    }\n\n")
            append("    private static String canonicalEventId(String eventId) {\n")
            append("        if (eventId == null) throw new IllegalArgumentException(\"eventId is required\");\n")
            append("        String canonical = UUID.fromString(eventId).toString();\n")
            append("        if (!canonical.equals(eventId)) throw new IllegalArgumentException(\"eventId must be a canonical UUID\");\n")
            append("        return canonical;\n")
            append("    }\n\n")
            append("    private static long value(Long value) { return value == null ? 0L : value; }\n\n")
            append("    private static String safeError(Exception exception) {\n")
            append("        String message = exception.getClass().getSimpleName() + \": \" + String.valueOf(exception.getMessage());\n")
            append("        message = message.replace('\\r', ' ').replace('\\n', ' ');\n")
            append("        return message.length() <= 1500 ? message : message.substring(0, 1500);\n")
            append("    }\n\n")
            append("    private static String sha256(String value) {\n")
            append("        try {\n")
            append("            return HexFormat.of().formatHex(MessageDigest.getInstance(\"SHA-256\").digest(value.getBytes(StandardCharsets.UTF_8)));\n")
            append("        } catch (NoSuchAlgorithmException exception) {\n")
            append("            throw new IllegalStateException(\"SHA-256 is unavailable\", exception);\n")
            append("        }\n")
            append("    }\n\n")
            append("    private record OutboxRecord(String id, String partitionKey, String payload, String payloadSha256, int attempts) {}\n")
            append("    public record OutboxHealth(long pending, long dead, long expiredLeases, long orderingBlocked, long oldestPendingAgeMs) {}\n")
            append("    public record OutboxTelemetryEvent(String connector, String outcome, Instant occurredAt) {}\n")
            append("    public record OutboxAuditEvent(String connector, String action, String subject, String actor, String justificationSha256, long affected, Instant occurredAt) {}\n")
        }
    }

    private fun renderRabbitConsumer(model: IntegrationConnectorModel): String = buildString {
        append("    @RabbitListener(queues = \"").append(propertyExpression(model.addressProperty)).append("\")\n")
        append("    public void consume(").append(model.payloadJavaType).append(" payload) {\n")
        appendHandlerInvocation(model)
        append("    }\n")
    }

    private fun renderSftpUpload(model: IntegrationConnectorModel): String = buildString {
        appendResilienceAnnotations(model)
        append("    public void upload(String remotePath, byte[] payload) {\n")
        append("        Objects.requireNonNull(payload, \"payload is required\");\n")
        append("        String safePath = safeRemotePath(remotePath);\n")
        append("        String destination = address.endsWith(\"/\") ? address + safePath : address + \"/\" + safePath;\n")
        append("        String temporary = destination + \".jvw-\" + UUID.randomUUID() + \".writing\";\n")
        append("        sftpTemplate.execute(session -> {\n")
        append("            try (ByteArrayInputStream input = new ByteArrayInputStream(payload)) {\n")
        append("                session.write(input, temporary);\n")
        append("                session.rename(temporary, destination);\n")
        append("                return null;\n")
        append("            } catch (Exception exception) {\n")
        append("                try { session.remove(temporary); } catch (Exception cleanup) { exception.addSuppressed(cleanup); }\n")
        append("                throw exception;\n")
        append("            }\n")
        append("        });\n")
        append("    }\n\n")
        appendSafeRemotePath()
    }

    private fun renderSftpDownload(model: IntegrationConnectorModel): String = buildString {
        appendResilienceAnnotations(model)
        append("    public byte[] download(String remotePath) {\n")
        append("        String safePath = safeRemotePath(remotePath);\n")
        append("        String source = address.endsWith(\"/\") ? address + safePath : address + \"/\" + safePath;\n")
        append("        return Objects.requireNonNull(sftpTemplate.execute(session -> {\n")
        append("            ByteArrayOutputStream output = new ByteArrayOutputStream();\n")
        append("            session.read(source, output);\n")
        append("            return output.toByteArray();\n")
        append("        }), \"SFTP response is required\");\n")
        append("    }\n\n")
        appendSafeRemotePath()
    }

    private fun StringBuilder.appendSafeRemotePath() {
        append("    private static String safeRemotePath(String remotePath) {\n")
        append("        if (remotePath == null || remotePath.isBlank() || remotePath.startsWith(\"/\") || remotePath.contains(\"\\\\\")) {\n")
        append("            throw new IllegalArgumentException(\"remotePath must be a non-absolute POSIX path\");\n")
        append("        }\n")
        append("        for (String segment : remotePath.split(\"/\")) {\n")
        append("            if (segment.isBlank() || segment.equals(\".\") || segment.equals(\"..\") || segment.chars().anyMatch(Character::isISOControl)) {\n")
        append("                throw new IllegalArgumentException(\"remotePath contains an unsafe segment\");\n")
        append("            }\n")
        append("        }\n")
        append("        return remotePath;\n")
        append("    }\n")
    }

    private fun renderEmail(model: IntegrationConnectorModel): String = buildString {
        appendResilienceAnnotations(model)
        append("    public void send(String addresses, String subject, String body) {\n")
        append("        EmailInfo message = EmailInfoBuilder.create(addresses, subject, body).build();\n")
        if (model.reliability.deliveryGuarantee == IntegrationDeliveryGuarantee.AT_MOST_ONCE) {
            append("        emailer.sendEmail(message);\n")
        } else {
            append("        emailer.sendEmailAsync(message);\n")
        }
        append("    }\n")
    }

    private fun renderFileStorage(model: IntegrationConnectorModel): String = buildString {
        append("    public FileRef store(String fileName, byte[] content) {\n")
        append("        return fileStorage.saveStream(fileName, new ByteArrayInputStream(content));\n")
        append("    }\n\n")
        append("    public byte[] load(FileRef reference) {\n")
        append("        try (InputStream input = fileStorage.openStream(reference)) {\n")
        append("            return input.readAllBytes();\n")
        append("        } catch (IOException exception) {\n")
        append("            throw new UncheckedIOException(\"Could not read file storage content\", exception);\n")
        append("        }\n")
        append("    }\n")
    }

    private fun StringBuilder.appendHandlerInvocation(model: IntegrationConnectorModel) {
        if (model.observability.structuredLoggingEnabled) {
            append("        log.debug(\"Consuming integration message connector=")
                .append(escapeJava(model.beanName)).append("\");\n")
        }
        append("        ").append(model.handlerFieldName).append('.')
            .append(model.handlerMethod).append("(payload);\n")
    }

    private fun StringBuilder.appendResilienceAnnotations(model: IntegrationConnectorModel) {
        val name = escapeJava(model.beanName)
        if (model.reliability.retry.mode == IntegrationRetryMode.BLOCKING) {
            append("    @Retry(name = \"").append(name).append("\")\n")
        }
        if (model.reliability.circuitBreaker.enabled) {
            append("    @CircuitBreaker(name = \"").append(name).append("\")\n")
        }
        if (model.reliability.bulkhead.enabled) {
            append("    @Bulkhead(name = \"").append(name).append("\")\n")
        }
        if (model.reliability.rateLimit.enabled) {
            append("    @RateLimiter(name = \"").append(name).append("\")\n")
        }
    }

    private fun injectedFields(model: IntegrationConnectorModel): List<InjectedField> = buildList {
        when (model.kind) {
            in HTTP_KINDS -> {
                add(InjectedField("RestClient.Builder", "restClientBuilder"))
                add(InjectedField("RestClient", "restClient", constructorParameter = false))
                add(InjectedField("String", "address", model.addressProperty))
            }
            IntegrationConnectorKind.KAFKA_PUBLISHER ->
                add(InjectedField("KafkaTemplate<String, ${model.payloadJavaType}>", "kafkaTemplate"))
            IntegrationConnectorKind.KAFKA_CONSUMER -> Unit
            IntegrationConnectorKind.RABBIT_PUBLISHER ->
                add(InjectedField("RabbitTemplate", "rabbitTemplate"))
            IntegrationConnectorKind.RABBIT_CONSUMER -> Unit
            IntegrationConnectorKind.SFTP_UPLOAD,
            IntegrationConnectorKind.SFTP_DOWNLOAD,
            -> {
                add(InjectedField("SftpRemoteFileTemplate", "sftpTemplate"))
                add(InjectedField("String", "address", model.addressProperty))
            }
            IntegrationConnectorKind.JMIX_EMAIL -> add(InjectedField("Emailer", "emailer"))
            IntegrationConnectorKind.JMIX_FILE_STORAGE,
            IntegrationConnectorKind.OBJECT_STORAGE,
            -> {
                add(InjectedField("FileStorageLocator", "fileStorageLocator"))
                add(InjectedField("String", "storageName", model.addressProperty))
                add(InjectedField("FileStorage", "fileStorage", constructorParameter = false))
            }
            else -> error("Unsupported connector kind: ${model.kind}")
        }
        if (model.kind in MESSAGE_PUBLISHER_KINDS) {
            add(InjectedField("String", "address", model.addressProperty))
        }
        if (model.reliability.outboxEnabled) {
            val outbox = requireNotNull(model.reliability.outbox)
            add(InjectedField("DataSource", "dataSource", qualifier = outbox.dataSourceBean))
            add(InjectedField("JdbcTemplate", "jdbcTemplate", constructorParameter = false))
            add(InjectedField("ObjectMapper", "objectMapper"))
            add(
                InjectedField(
                    "PlatformTransactionManager",
                    "transactionManager",
                    qualifier = outbox.transactionManagerBean,
                ),
            )
            add(InjectedField("AccessManager", "accessManager"))
            add(InjectedField("CurrentAuthentication", "currentAuthentication"))
            add(InjectedField("ApplicationEventPublisher", "eventPublisher"))
            if (usesMicrometerObservation(model) && model.observability.metricsEnabled) {
                add(InjectedField("ObjectProvider<MeterRegistry>", "meterRegistryProvider"))
            }
            if (usesMicrometerObservation(model) && model.observability.tracingEnabled) {
                add(InjectedField("ObjectProvider<ObservationRegistry>", "observationRegistryProvider"))
            }
            add(InjectedField("TransactionTemplate", "outboxTransactions", constructorParameter = false))
        }
        if (model.kind in CONSUMER_KINDS) {
            add(InjectedField(model.handlerBeanClass.orEmpty(), model.handlerFieldName.orEmpty()))
        }
        if (model.authentication.kind == IntegrationAuthenticationKind.BASIC) {
            add(InjectedField("String", "authUsername", model.authentication.usernameProperty))
        }
        if (
            model.authentication.kind in setOf(
                IntegrationAuthenticationKind.BASIC,
                IntegrationAuthenticationKind.BEARER,
                IntegrationAuthenticationKind.API_KEY,
            )
        ) {
            model.authentication.secretProperty?.let {
                add(InjectedField("String", "authSecret", it))
            }
        }
        if (model.authentication.kind == IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS) {
            add(
                InjectedField(
                    "OAuth2AuthorizedClientManager",
                    "authorizedClientManager",
                    qualifier = model.authentication.authorizedClientManagerBeanName,
                ),
            )
            add(
                InjectedField(
                    "String",
                    "clientRegistrationId",
                    model.authentication.clientRegistrationIdProperty,
                ),
            )
            add(
                InjectedField(
                    "String",
                    "oauthPrincipalName",
                    model.authentication.principalNameProperty,
                ),
            )
        }
        model.headers.forEachIndexed { index, header ->
            add(InjectedField("String", "headerValue$index", header.valueProperty))
        }
    }

    private fun imports(model: IntegrationConnectorModel): Set<String> = buildSet {
        add("org.springframework.beans.factory.annotation.Value")
        add("org.springframework.context.annotation.Profile")
        add("org.springframework.context.annotation.PropertySource")
        add("org.springframework.stereotype.Component")
        if (model.observability.structuredLoggingEnabled) {
            add("org.slf4j.Logger")
            add("org.slf4j.LoggerFactory")
        }
        if (usesResilience(model)) {
            if (model.reliability.retry.mode == IntegrationRetryMode.BLOCKING) {
                add("io.github.resilience4j.retry.annotation.Retry")
            }
            if (model.reliability.circuitBreaker.enabled) {
                add("io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker")
            }
            if (model.reliability.bulkhead.enabled) {
                add("io.github.resilience4j.bulkhead.annotation.Bulkhead")
            }
            if (model.reliability.rateLimit.enabled) {
                add("io.github.resilience4j.ratelimiter.annotation.RateLimiter")
            }
        }
        if (model.reliability.outboxEnabled) {
            when (model.reliability.outbox?.jsonApi) {
                IntegrationJsonApi.JACKSON_2 -> {
                    add("com.fasterxml.jackson.core.JsonProcessingException")
                    add("com.fasterxml.jackson.databind.ObjectMapper")
                }
                IntegrationJsonApi.JACKSON_3 -> {
                    add("tools.jackson.core.JacksonException")
                    add("tools.jackson.databind.ObjectMapper")
                }
                null -> Unit
            }
            add("io.jmix.core.AccessManager")
            add("io.jmix.core.accesscontext.SpecificOperationAccessContext")
            add("io.jmix.core.security.CurrentAuthentication")
            add("java.nio.charset.StandardCharsets")
            add("java.security.MessageDigest")
            add("java.security.NoSuchAlgorithmException")
            add("java.sql.PreparedStatement")
            add("java.sql.Timestamp")
            add("java.time.Clock")
            add("java.time.Duration")
            add("java.time.Instant")
            add("java.util.HexFormat")
            add("java.util.List")
            add("java.util.Objects")
            add("java.util.UUID")
            add("java.util.concurrent.TimeUnit")
            add("javax.sql.DataSource")
            add("org.springframework.beans.factory.annotation.Qualifier")
            add("org.springframework.context.ApplicationEventPublisher")
            if (usesMicrometerObservation(model)) {
                add("org.springframework.beans.factory.ObjectProvider")
                if (model.observability.metricsEnabled) {
                    add("io.micrometer.core.instrument.MeterRegistry")
                }
                if (model.observability.tracingEnabled) {
                    add("io.micrometer.observation.Observation")
                    add("io.micrometer.observation.ObservationRegistry")
                }
            }
            add("org.springframework.jdbc.core.JdbcTemplate")
            add("org.springframework.scheduling.annotation.EnableScheduling")
            add("org.springframework.scheduling.annotation.Scheduled")
            add("org.springframework.security.access.AccessDeniedException")
            add("org.springframework.transaction.PlatformTransactionManager")
            add("org.springframework.transaction.annotation.Transactional")
            add("org.springframework.transaction.support.TransactionTemplate")
            if (model.kind == IntegrationConnectorKind.KAFKA_PUBLISHER) {
                add("org.apache.kafka.clients.producer.ProducerRecord")
            }
        }
        when (model.kind) {
            in HTTP_KINDS -> {
                add("java.net.http.HttpClient")
                add("java.time.Duration")
                add("org.springframework.http.HttpMethod")
                add("org.springframework.http.MediaType")
                add("org.springframework.http.client.JdkClientHttpRequestFactory")
                add("org.springframework.web.client.RestClient")
                if (model.responseJavaType != "void") add("java.util.Objects")
                if (model.authentication.kind == IntegrationAuthenticationKind.BASIC) {
                    add("java.nio.charset.StandardCharsets")
                    add("java.util.Base64")
                }
                if (model.authentication.kind == IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS) {
                    add("org.springframework.beans.factory.annotation.Qualifier")
                    add("org.springframework.security.oauth2.client.OAuth2AuthorizeRequest")
                    add("org.springframework.security.oauth2.client.OAuth2AuthorizedClient")
                    add("org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager")
                }
            }
            IntegrationConnectorKind.KAFKA_PUBLISHER -> {
                add("java.util.concurrent.CompletableFuture")
                add("org.springframework.kafka.core.KafkaTemplate")
                add("org.springframework.kafka.support.SendResult")
            }
            IntegrationConnectorKind.KAFKA_CONSUMER -> {
                add("org.springframework.kafka.annotation.KafkaListener")
                if (model.reliability.retry.mode == IntegrationRetryMode.NON_BLOCKING) {
                    add("org.springframework.kafka.annotation.RetryableTopic")
                    add("org.springframework.retry.annotation.Backoff")
                }
            }
            IntegrationConnectorKind.RABBIT_PUBLISHER -> {
                add("org.springframework.amqp.rabbit.connection.CorrelationData")
                add("org.springframework.amqp.rabbit.core.RabbitTemplate")
            }
            IntegrationConnectorKind.RABBIT_CONSUMER ->
                add("org.springframework.amqp.rabbit.annotation.RabbitListener")
            IntegrationConnectorKind.SFTP_UPLOAD -> {
                add("java.io.ByteArrayInputStream")
                add("java.util.Objects")
                add("java.util.UUID")
                add("org.springframework.integration.sftp.session.SftpRemoteFileTemplate")
            }
            IntegrationConnectorKind.SFTP_DOWNLOAD -> {
                add("java.io.ByteArrayOutputStream")
                add("java.util.Objects")
                add("org.springframework.integration.sftp.session.SftpRemoteFileTemplate")
            }
            IntegrationConnectorKind.JMIX_EMAIL -> {
                add("io.jmix.email.EmailInfo")
                add("io.jmix.email.EmailInfoBuilder")
                add("io.jmix.email.Emailer")
            }
            IntegrationConnectorKind.JMIX_FILE_STORAGE,
            IntegrationConnectorKind.OBJECT_STORAGE,
            -> {
                add("java.io.ByteArrayInputStream")
                add("java.io.IOException")
                add("java.io.InputStream")
                add("java.io.UncheckedIOException")
                add("io.jmix.core.FileRef")
                add("io.jmix.core.FileStorage")
                add("io.jmix.core.FileStorageLocator")
            }
            else -> error("Unsupported connector kind: ${model.kind}")
        }
    }

    private fun renderReliabilityProperties(model: IntegrationConnectorModel, encodedModel: String): String {
        val retry = model.reliability.retry
        val circuit = model.reliability.circuitBreaker
        val bulkhead = model.reliability.bulkhead
        val rate = model.reliability.rateLimit
        return buildString {
            append("# JVW-INTEGRATION-MODEL: ").append(encodedModel).append('\n')
            append("# Owned connector reliability policy for ").append(model.beanName).append('\n')
            if (retry.mode == IntegrationRetryMode.BLOCKING) {
                append("resilience4j.retry.instances.").append(model.beanName)
                    .append(".max-attempts=").append(retry.attempts).append('\n')
                append("resilience4j.retry.instances.").append(model.beanName)
                    .append(".wait-duration=").append(retry.initialDelayMs).append("ms\n")
                if (retry.backoff == IntegrationBackoffMode.EXPONENTIAL) {
                    append("resilience4j.retry.instances.").append(model.beanName)
                        .append(".enable-exponential-backoff=true\n")
                    append("resilience4j.retry.instances.").append(model.beanName)
                        .append(".exponential-backoff-multiplier=").append(retry.multiplier).append('\n')
                    append("resilience4j.retry.instances.").append(model.beanName)
                        .append(".exponential-max-wait-duration=")
                        .append(retry.maximumDelayMs).append("ms\n")
                }
            }
            if (circuit.enabled) {
                append("resilience4j.circuitbreaker.instances.").append(model.beanName)
                    .append(".sliding-window-size=").append(circuit.slidingWindowSize).append('\n')
                append("resilience4j.circuitbreaker.instances.").append(model.beanName)
                    .append(".minimum-number-of-calls=").append(circuit.minimumCalls).append('\n')
                append("resilience4j.circuitbreaker.instances.").append(model.beanName)
                    .append(".failure-rate-threshold=").append(circuit.failureRateThreshold).append('\n')
                append("resilience4j.circuitbreaker.instances.").append(model.beanName)
                    .append(".wait-duration-in-open-state=").append(circuit.openStateMs).append("ms\n")
            }
            if (bulkhead.enabled) {
                append("resilience4j.bulkhead.instances.").append(model.beanName)
                    .append(".max-concurrent-calls=").append(bulkhead.maximumConcurrentCalls).append('\n')
                append("resilience4j.bulkhead.instances.").append(model.beanName)
                    .append(".max-wait-duration=").append(bulkhead.maximumWaitMs).append("ms\n")
            }
            if (rate.enabled) {
                append("resilience4j.ratelimiter.instances.").append(model.beanName)
                    .append(".limit-for-period=").append(rate.callsPerPeriod).append('\n')
                append("resilience4j.ratelimiter.instances.").append(model.beanName)
                    .append(".limit-refresh-period=").append(rate.periodMs).append("ms\n")
                append("resilience4j.ratelimiter.instances.").append(model.beanName)
                    .append(".timeout-duration=").append(rate.timeoutMs).append("ms\n")
            }
            if (model.reliability.outboxEnabled) {
                val outbox = requireNotNull(model.reliability.outbox)
                append("# Durable database-to-broker delivery is at-least-once; deduplicate jvw-outbox-id downstream.\n")
                append(model.configurationPrefix).append(".outbox.poll-delay-ms=")
                    .append(outbox.pollDelayMs).append('\n')
                append(model.configurationPrefix).append(".outbox.batch-size=")
                    .append(outbox.batchSize).append('\n')
                append(model.configurationPrefix).append(".outbox.lease-duration-ms=")
                    .append(outbox.leaseDurationMs).append('\n')
                append(model.configurationPrefix).append(".outbox.max-attempts=")
                    .append(outbox.maxAttempts).append('\n')
                append(model.configurationPrefix).append(".outbox.retention-days=")
                    .append(outbox.retentionDays).append('\n')
                when (model.kind) {
                    IntegrationConnectorKind.KAFKA_PUBLISHER -> {
                        append("# Broker acknowledgement is mandatory for durable dispatch.\n")
                        append("spring.kafka.producer.acks=all\n")
                        append("spring.kafka.producer.properties.enable.idempotence=true\n")
                        append("spring.kafka.producer.properties.max.block.ms=")
                            .append(model.reliability.requestTimeoutMs).append('\n')
                        append("spring.kafka.producer.properties.request.timeout.ms=")
                            .append(model.reliability.requestTimeoutMs).append('\n')
                        append("spring.kafka.producer.properties.delivery.timeout.ms=")
                            .append(model.reliability.requestTimeoutMs + 1_000).append('\n')
                    }
                    IntegrationConnectorKind.RABBIT_PUBLISHER -> {
                        append("# Correlated confirms plus returned-message detection are mandatory.\n")
                        append("spring.rabbitmq.publisher-confirm-type=correlated\n")
                        append("spring.rabbitmq.publisher-returns=true\n")
                        append("spring.rabbitmq.template.mandatory=true\n")
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun validateCircuitBreaker(
        model: IntegrationConnectorModel,
        error: (String, String) -> Unit,
    ) {
        val circuit = model.reliability.circuitBreaker
        if (!circuit.enabled) return
        if (circuit.slidingWindowSize !in 10..10_000) {
            error("INTEGRATION_CIRCUIT_WINDOW_RANGE", "Circuit-breaker window must contain 10 to 10,000 calls.")
        }
        if (circuit.minimumCalls !in 1..circuit.slidingWindowSize) {
            error("INTEGRATION_CIRCUIT_MINIMUM_RANGE", "Minimum calls must fit inside the circuit-breaker window.")
        }
        if (circuit.failureRateThreshold !in 1..100) {
            error("INTEGRATION_CIRCUIT_THRESHOLD_RANGE", "Circuit failure threshold must be a percentage from 1 to 100.")
        }
        if (circuit.openStateMs !in 100..3_600_000) {
            error("INTEGRATION_CIRCUIT_OPEN_RANGE", "Circuit open-state duration must be between 100 ms and one hour.")
        }
    }

    private fun validateBulkhead(
        model: IntegrationConnectorModel,
        error: (String, String) -> Unit,
    ) {
        val bulkhead = model.reliability.bulkhead
        if (!bulkhead.enabled) return
        if (bulkhead.maximumConcurrentCalls !in 1..100_000) {
            error("INTEGRATION_BULKHEAD_CONCURRENCY_RANGE", "Bulkhead concurrency must be between 1 and 100,000.")
        }
        if (bulkhead.maximumWaitMs !in 0..3_600_000) {
            error("INTEGRATION_BULKHEAD_WAIT_RANGE", "Bulkhead wait must be between zero and one hour.")
        }
    }

    private fun validateRateLimit(
        model: IntegrationConnectorModel,
        error: (String, String) -> Unit,
    ) {
        val rate = model.reliability.rateLimit
        if (!rate.enabled) return
        if (rate.callsPerPeriod !in 1..10_000_000) {
            error("INTEGRATION_RATE_CALLS_RANGE", "Rate-limit calls per period must be between 1 and 10,000,000.")
        }
        if (rate.periodMs !in 1..86_400_000 || rate.timeoutMs !in 0..rate.periodMs) {
            error("INTEGRATION_RATE_PERIOD_RANGE", "Rate-limit period or timeout is outside reviewed bounds.")
        }
    }

    private fun validateOutbox(
        model: IntegrationConnectorModel,
        error: (String, String) -> Unit,
        warning: (String, String) -> Unit,
    ) {
        val outbox = model.reliability.outbox
        if (outbox == null) {
            error(
                "INTEGRATION_OUTBOX_CONFIGURATION_REQUIRED",
                "Select a persisted data store and configure the durable outbox.",
            )
            return
        }
        if (outbox.storeId.isBlank()) {
            error("INTEGRATION_OUTBOX_STORE_REQUIRED", "A resolved Jmix data store is required.")
        }
        if (!DATABASE_IDENTIFIER.matches(outbox.tableName) || outbox.tableName.length > 30) {
            error(
                "INTEGRATION_OUTBOX_TABLE_INVALID",
                "Outbox table name must be a portable lower snake-case identifier of at most 30 characters.",
            )
        }
        if (outbox.jsonApi == null) {
            error(
                "INTEGRATION_OUTBOX_JSON_API_REQUIRED",
                "The backend must resolve the module's Jackson generation contract before outbox generation.",
            )
        }
        if (!SPRING_BEAN_IDENTIFIER.matches(outbox.dataSourceBean)) {
            error(
                "INTEGRATION_OUTBOX_DATASOURCE_BEAN_INVALID",
                "The backend must resolve a valid DataSource bean for the selected Jmix data store.",
            )
        }
        if (!SPRING_BEAN_IDENTIFIER.matches(outbox.transactionManagerBean)) {
            error(
                "INTEGRATION_OUTBOX_TRANSACTION_MANAGER_BEAN_INVALID",
                "The backend must resolve a valid transaction-manager bean for the selected Jmix data store.",
            )
        }
        if (model.payloadJavaType == "void" || model.payloadJavaType == "byte[]" || '<' in model.payloadJavaType) {
            error(
                "INTEGRATION_OUTBOX_PAYLOAD_TYPE",
                "Durable outbox payload must be a concrete non-generic JSON-mappable Java type.",
            )
        }
        if (!model.reliability.transactional) {
            error(
                "INTEGRATION_OUTBOX_TRANSACTION_REQUIRED",
                "Durable enqueue must join a Spring database transaction.",
            )
        }
        if (model.reliability.deliveryGuarantee == IntegrationDeliveryGuarantee.EXACTLY_ONCE) {
            error(
                "INTEGRATION_OUTBOX_EXACTLY_ONCE_FALSE_CLAIM",
                "A database-to-broker outbox is at-least-once: a crash after broker acknowledgement can redeliver the stable event ID.",
            )
        }
        if (outbox.batchSize !in 1..10_000) {
            error("INTEGRATION_OUTBOX_BATCH_RANGE", "Outbox batch size must be between 1 and 10,000.")
        }
        if (outbox.pollDelayMs !in 100..3_600_000) {
            error("INTEGRATION_OUTBOX_POLL_RANGE", "Outbox poll delay must be between 100 ms and one hour.")
        }
        val minimumLease = model.reliability.requestTimeoutMs +
            maxOf(5_000, outbox.pollDelayMs)
        if (outbox.leaseDurationMs !in minimumLease..3_600_000) {
            error(
                "INTEGRATION_OUTBOX_LEASE_RANGE",
                "Outbox lease must exceed the provider timeout by at least one poll interval (minimum $minimumLease ms) and be no more than one hour.",
            )
        }
        if (outbox.maxAttempts !in 1..30) {
            error("INTEGRATION_OUTBOX_ATTEMPTS_RANGE", "Outbox delivery attempts must be between 1 and 30.")
        }
        if (
            outbox.initialBackoffMs !in 100..3_600_000 ||
            outbox.maximumBackoffMs !in outbox.initialBackoffMs..86_400_000
        ) {
            error("INTEGRATION_OUTBOX_BACKOFF_RANGE", "Outbox backoff is outside reviewed enterprise bounds.")
        }
        if (outbox.retentionDays !in 1..3_650) {
            error("INTEGRATION_OUTBOX_RETENTION_RANGE", "Delivered-event retention must be between 1 day and 10 years.")
        }
        if (!SPECIFIC_PERMISSION.matches(outbox.replayPermission)) {
            error(
                "INTEGRATION_OUTBOX_REPLAY_PERMISSION_INVALID",
                "Replay permission must be an externalizable Jmix specific-policy resource name.",
            )
        }
        if (!SPECIFIC_PERMISSION.matches(outbox.maintenancePermission)) {
            error(
                "INTEGRATION_OUTBOX_MAINTENANCE_PERMISSION_INVALID",
                "Maintenance permission must be an externalizable Jmix specific-policy resource name.",
            )
        }
        if (outbox.maintenancePermission == outbox.replayPermission) {
            error(
                "INTEGRATION_OUTBOX_PERMISSION_SEPARATION",
                "Replay and destructive retention maintenance require separate Jmix specific permissions.",
            )
        }
        if (!model.reliability.idempotency.enabled) {
            warning(
                "INTEGRATION_OUTBOX_DOWNSTREAM_IDEMPOTENCY",
                "Consumers must deduplicate the generated jvw-outbox-id because durable dispatch is at-least-once.",
            )
        }
    }

    private fun requireProperty(
        property: String?,
        code: String,
        label: String,
        error: (String, String) -> Unit,
    ) {
        if (property.isNullOrBlank() || !isPropertyKey(property)) {
            error(code, "$label must be supplied by a valid externalized property key.")
        }
    }

    private fun rawClassLiteral(javaType: String): String =
        when {
            javaType.contains('<') -> "${javaType.substringBefore('<')}.class"
            javaType.endsWith("[]") -> "$javaType.class"
            else -> "$javaType.class"
        }

    private fun propertyExpression(key: String): String = "\${$key}"

    private fun usesResilience(model: IntegrationConnectorModel): Boolean =
        model.reliability.retry.mode == IntegrationRetryMode.BLOCKING ||
            model.reliability.circuitBreaker.enabled ||
            model.reliability.bulkhead.enabled ||
            model.reliability.rateLimit.enabled

    private fun usesMicrometerObservation(model: IntegrationConnectorModel): Boolean =
        model.observability.runtimeApi == IntegrationObservabilityApi.MICROMETER_OBSERVATION &&
            (model.observability.metricsEnabled || model.observability.tracingEnabled)

    private fun isJavaIdentifier(value: String): Boolean =
        JAVA_IDENTIFIER.matches(value) && value !in JAVA_KEYWORDS

    private fun isPackageName(value: String): Boolean =
        value.split('.').all(::isJavaIdentifier) && '.' in value

    private fun isPropertyKey(value: String): Boolean = PROPERTY_KEY.matches(value)

    private fun isSafeJavaType(value: String): Boolean =
        value == "void" ||
            value == "byte[]" ||
            (
                value.length in 1..500 &&
                    JAVA_TYPE.matches(value.filterNot(Char::isWhitespace)) &&
                    !value.contains("..") &&
                    !value.contains(';') &&
                    !value.contains('{') &&
                    !value.contains('}')
                )

    private fun escapeJava(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun safeComment(value: String): String = value.replace("*/", "* /").replace(Regex("""\s+"""), " ").trim()

    private fun databaseName(prefix: String, table: String, suffix: String): String {
        val digest = table.hashCode().toUInt().toString(16).padStart(8, '0')
        val base = "${prefix}_${table.take(14)}_${suffix.take(5)}_$digest"
        return base.take(30)
    }

    private fun classpathPath(path: String): String {
        val marker = "/src/main/resources/"
        val normalized = path.replace('\\', '/')
        return normalized.substringAfter(
            marker,
            normalized.removePrefix("src/main/resources/"),
        ).trimStart('/')
    }

    private data class InjectedField(
        val javaType: String,
        val name: String,
        val propertyKey: String? = null,
        val constructorParameter: Boolean = true,
        val qualifier: String? = null,
    )

    private val HTTP_KINDS = setOf(
        IntegrationConnectorKind.HTTP_CLIENT,
        IntegrationConnectorKind.WEBHOOK,
        IntegrationConnectorKind.SMS_GATEWAY,
        IntegrationConnectorKind.PAYMENT_GATEWAY,
        IntegrationConnectorKind.IDENTITY_PROVIDER,
    )
    private val MESSAGE_PUBLISHER_KINDS = setOf(
        IntegrationConnectorKind.KAFKA_PUBLISHER,
        IntegrationConnectorKind.RABBIT_PUBLISHER,
    )
    private val MESSAGE_CONSUMER_KINDS = setOf(
        IntegrationConnectorKind.KAFKA_CONSUMER,
        IntegrationConnectorKind.RABBIT_CONSUMER,
    )
    private val CONSUMER_KINDS = MESSAGE_CONSUMER_KINDS
    private val SFTP_KINDS = setOf(
        IntegrationConnectorKind.SFTP_UPLOAD,
        IntegrationConnectorKind.SFTP_DOWNLOAD,
    )
    private val JAVA_IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val JAVA_TYPE = Regex("""[A-Za-z_$][A-Za-z0-9_$.]*(?:<(?:\?|[A-Za-z_$][A-Za-z0-9_$.]*)(?:,(?:\?|[A-Za-z_$][A-Za-z0-9_$.]*))*>)?(?:\[\])*""")
    private val PROPERTY_KEY = Regex("""[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+""")
    private val PROFILE = Regex("""[A-Za-z0-9_.-]+""")
    private val SPRING_BEAN_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")
    private val HTTP_HEADER = Regex("""[!#$%&'*+\-.^_`|~0-9A-Za-z]+""")
    private val DATABASE_IDENTIFIER = Regex("""[a-z][a-z0-9_]*""")
    private val SPECIFIC_PERMISSION = Regex("""[A-Za-z][A-Za-z0-9_.:-]{2,199}""")
    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while",
    )
}

data class GeneratedIntegrationConnector(
    val javaSource: String,
    val reliabilityProperties: String,
    val migrationXml: String? = null,
    val requiredCapabilities: Set<IntegrationCapability>,
)

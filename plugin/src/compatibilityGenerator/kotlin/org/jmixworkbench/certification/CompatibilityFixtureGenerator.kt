package org.jmixworkbench.certification

import org.jmixworkbench.generator.AggregateUpdateServiceGenerator
import org.jmixworkbench.generator.AggregateUpdateServiceModel
import org.jmixworkbench.generator.DataRepositoryGenerator
import org.jmixworkbench.generator.EntityGenerator
import org.jmixworkbench.generator.KotlinDataRepositoryGenerator
import org.jmixworkbench.generator.KotlinEntityGenerator
import org.jmixworkbench.generator.IntegrationConnectorGenerator
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
import org.jmixworkbench.model.IntegrationJsonApi
import org.jmixworkbench.model.IntegrationOutboxModel
import org.jmixworkbench.model.IntegrationRetryMode
import org.jmixworkbench.model.IntegrationRetryPolicyModel
import org.jmixworkbench.model.IntegrationCircuitBreakerModel
import org.jmixworkbench.model.IntegrationReliabilityModel
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationObservabilityModel
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

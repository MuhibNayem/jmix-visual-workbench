package org.jmixworkbench.services

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.GeneratedIntegrationConnector
import org.jmixworkbench.generator.IntegrationConnectorGenerator
import org.jmixworkbench.generator.OpenApiJmixLayerGenerator
import org.jmixworkbench.model.IntegrationCapability
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationDiagnosticSeverity
import org.jmixworkbench.model.IntegrationJsonApi
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationOpenApiJmixTargetKind
import org.jmixworkbench.model.IntegrationOpenApiJmixTypeMapping
import org.jmixworkbench.model.IntegrationOpenApiMappingDirection
import org.jmixworkbench.model.IntegrationOpenApiConverterMethodBinding
import org.jmixworkbench.model.IntegrationOpenApiCustomConverterBinding
import org.jmixworkbench.model.IntegrationOpenApiEnumAdapterBinding
import org.jmixworkbench.model.IntegrationOpenApiEnumValueMapping
import org.jmixworkbench.model.IntegrationOpenApiPropertyMapping
import org.jmixworkbench.model.IntegrationOpenApiSchemaKind
import org.jmixworkbench.model.IntegrationOpenApiSchemaModel
import org.jmixworkbench.model.IntegrationSpringBootApi
import org.jmixworkbench.model.IntegrationTransportSecurityModel
import org.jmixworkbench.project.JmixOrganizationConnectorTemplate
import org.jmixworkbench.project.JmixTemplateCatalogManager
import java.util.Base64
import java.util.Locale

/**
 * Source-safe integration-adapter lifecycle.
 *
 * Every connector owns exactly one Java adapter and one externalized policy
 * resource. Both files must regenerate byte-for-byte from the embedded model
 * before either can be changed. Dependency capabilities are derived from the
 * selected Gradle module and missing optional frameworks block generation.
 */
@Service(Service.Level.PROJECT)
class IntegrationConnectorWorkspaceService(private val project: Project) {
    private val gson = Gson()
    private val mappingExtensionCatalogLock = Any()
    private val mappingExtensionCatalogCache = linkedMapOf<String, IntegrationMappingExtensionCatalog>()

    fun load(forceRefresh: Boolean = false): IntegrationConnectorWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        val destinations = destinations(graph)
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh)
        val connectorCatalogs = JmixTemplateCatalogManager.getInstance().connectorInventory()
        val existingDocuments = discoverExisting(destinations, schema)
        val openApiContracts = OpenApiContractService.getInstance(project).discover(
            destinations = destinations,
            explicitlyReferencedPaths = existingDocuments.mapNotNull {
                it.model.openApiBinding?.relativePath
            }.toSet(),
            forceRefresh = forceRefresh,
        )
        val mappingExtensions = mappingExtensionCatalog(graph, destinations)
        return IntegrationConnectorWorkspaceResponse(
            graphDigest = graph.snapshotDigest,
            destinations = destinations,
            defaultDestinationId = destinations.firstOrNull(IntegrationConnectorDestinationSnapshot::recommended)?.id,
            contextArtifacts = graph.artifacts.filter { it.kind in CONTEXT_KINDS },
            oauth2Managers = oauth2Managers(graph),
            oauth2Services = oauth2Services(graph),
            dataStores = schema.stores,
            entities = schema.entities,
            enumAdapters = mappingExtensions.enums,
            converterBeans = mappingExtensions.converters,
            openApiContracts = openApiContracts,
            organizationConnectorTemplates = connectorCatalogs.options.map {
                IntegrationOrganizationConnectorTemplateSnapshot(
                    catalogId = it.catalogId,
                    catalogVersion = it.catalogVersion,
                    bundleSha256 = it.bundleSha256,
                    catalogDisplayName = it.catalogDisplayName,
                    template = it.template,
                )
            },
            existingDocuments = existingDocuments,
            issues = buildList {
                if (!graph.indexHealth.complete) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-INTEGRATION-GRAPH-PARTIAL",
                            "The application index is partial. Connector generation remains fail-closed, but dependency and impact coverage may be incomplete.",
                        ),
                    )
                }
                if (destinations.isEmpty()) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-INTEGRATION-DESTINATION-MISSING",
                            "No module has both a production Java root and a production resource root.",
                        ),
                    )
                }
                connectorCatalogs.issues.forEach { issue ->
                    add(
                        WorkspaceChangeIssue(
                            "JVW-INTEGRATION-CATALOG-UNAVAILABLE",
                            "${issue.configuredName}: ${issue.message}",
                        ),
                    )
                }
                openApiContracts.filterNot(OpenApiContractSnapshot::valid).forEach { contract ->
                    add(
                        WorkspaceChangeIssue(
                            "JVW-INTEGRATION-OPENAPI-CONTRACT-INVALID",
                            "${contract.title}: ${contract.issues.firstOrNull() ?: "No operation can be generated safely."}",
                            contract.relativePath,
                        ),
                    )
                }
                addAll(schema.issues)
            },
        )
    }

    fun preview(model: IntegrationConnectorModel): WorkspaceChangePreviewResponse {
        val proposal = propose(model)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: WorkspaceChangePreviewResponse(
                accepted = false,
                changeSetId = "integration-connector:rejected",
                label = "Integration connector generation rejected",
                planDigest = null,
                files = emptyList(),
                issues = proposal.issues,
            )
    }

    fun prepare(request: IntegrationConnectorApplyRequest): PreparedWorkspaceChange {
        val proposal = propose(request.model)
        val changeSet = proposal.changeSet
            ?: return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "integration-connector:rejected",
                    label = "Integration connector generation rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues,
                ),
                baseDir = null,
            )
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    fun catalogApprovalReview(
        request: IntegrationConnectorCatalogApprovalRequest,
    ): IntegrationConnectorCatalogApprovalReview {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val destination = destinations(graph).singleOrNull { it.id == request.destinationId }
            ?: throw IllegalArgumentException(
                "The selected module destination is no longer available. Refresh the workspace.",
            )
        val option = JmixTemplateCatalogManager.getInstance().resolveConnector(request.binding)
        require(option.template.supports(destination.springBootApi, destination.capabilities)) {
            "The connector template is not compatible with the selected module."
        }
        require(
            option.template.policy.risk !=
                org.jmixworkbench.project.JmixOrganizationConnectorRisk.STANDARD
        ) {
            "This organization connector template does not require native approval."
        }
        return IntegrationConnectorCatalogApprovalReview(
            catalogDisplayName = option.catalogDisplayName,
            templateName = option.template.name,
            provider = option.template.provider,
            risk = option.template.policy.risk.name,
            approvalPolicyId = requireNotNull(option.template.policy.approvalPolicyId),
            destinationModuleId = destination.moduleId,
            requireMutualTls = option.template.policy.requireMutualTls,
            requireTransactional = option.template.policy.requireTransactional,
            requireIdempotency = option.template.policy.requireIdempotency,
            requireOutbox = option.template.policy.requireOutbox,
            requireInbox = option.template.policy.requireInbox,
        )
    }

    fun issueCatalogApproval(
        request: IntegrationConnectorCatalogApprovalRequest,
    ): IntegrationConnectorCatalogApproval {
        catalogApprovalReview(request)
        val option = JmixTemplateCatalogManager.getInstance().resolveConnector(request.binding)
        return IntegrationConnectorCatalogPolicyService.getInstance(project).issueApproval(
            option = option,
            destinationId = request.destinationId,
        )
    }

    fun openApiEvolutionReview(
        model: IntegrationConnectorModel,
    ): IntegrationOpenApiEvolutionApprovalReview = evolutionContext(model).review

    fun issueOpenApiEvolutionApproval(
        model: IntegrationConnectorModel,
    ): OpenApiEvolutionApproval {
        val context = evolutionContext(model)
        return OpenApiEvolutionApprovalService.getInstance(project).issue(
            previous = context.owned.model,
            candidate = context.normalized,
            sourceRevision = context.owned.javaFingerprint,
            report = context.review.report,
        )
    }

    private fun evolutionContext(model: IntegrationConnectorModel): OpenApiEvolutionContext {
        val locator = requireNotNull(model.sourceLocator) {
            "OpenAPI evolution is available only for an existing generated connector."
        }
        val graph = ApplicationGraphService.getInstance(project).graph()
        val destination = destinations(graph).singleOrNull { it.id == model.destinationId }
            ?: throw IllegalArgumentException(
                "The selected module destination is no longer available. Refresh the workspace.",
            )
        val schema = SchemaWorkspaceService.getInstance(project).load()
        val selectedStoreId = when {
            model.reliability.outboxEnabled -> model.reliability.outbox?.storeId
            model.reliability.inboxEnabled -> model.reliability.inbox?.storeId
            else -> null
        }
        val selectedStore = selectedStoreId?.let { storeId ->
            schema.stores.singleOrNull { it.id == storeId }
        }
        val owned = requireNotNull(loadOwned(locator, destination, schema)) {
            "The connector or a supplemental generated file changed. Refresh before reviewing contract evolution."
        }
        require(owned.model.openApiBinding != null) {
            "The existing connector is not contract-owned."
        }
        val normalized = normalizeBackendContracts(model, destination, selectedStore, schema)
        require(owned.model.openApiBinding != normalized.openApiBinding) {
            "The candidate binding is identical to the connector's existing contract."
        }
        val baseline = requireNotNull(owned.model.openApiBaseline) {
            "This connector predates semantic OpenAPI baselines and requires a manual reviewed migration."
        }
        val operation = requireNotNull(normalized.resolvedOpenApiOperation) {
            "The candidate OpenAPI operation was not resolved by the backend."
        }
        val validation = IntegrationConnectorGenerator.validate(normalized, destination.capabilities)
        require(validation.valid) {
            validation.diagnostics.joinToString(" ") { it.message }
        }
        val layer = generateOpenApiJmixLayer(normalized, destination, schema)
        require(layer.issues.isEmpty()) { layer.issues.joinToString(" ") }
        val report = OpenApiContractEvolutionAnalyzer.compare(baseline, operation)
        val mappingDecisions = normalized.openApiJmixLayer?.mappings.orEmpty()
            .sortedBy(IntegrationOpenApiJmixTypeMapping::schemaId)
            .map { mapping ->
                val target = mapping.existingEntity?.qualifiedName
                    ?: mapping.generatedClassName
                    ?: "generated DTO"
                val properties = mapping.properties.sortedWith(
                    compareBy(IntegrationOpenApiPropertyMapping::schemaProperty, IntegrationOpenApiPropertyMapping::entityProperty),
                )
                val visibleProperties = properties.take(MAX_OPENAPI_APPROVAL_PROPERTIES).joinToString { property ->
                    val extension = when {
                        property.enumAdapter != null ->
                            " · enum ${property.enumAdapter.qualifiedName} [${property.enumAdapter.values.size}]"
                        property.customConverter != null ->
                            " · converter ${property.customConverter.qualifiedName}"
                        else -> ""
                    }
                    "${property.schemaProperty}→${property.entityProperty} (${property.direction.name.lowercase(Locale.ROOT)})$extension"
                }
                val suffix = if (properties.size > MAX_OPENAPI_APPROVAL_PROPERTIES) {
                    ", +${properties.size - MAX_OPENAPI_APPROVAL_PROPERTIES} more"
                } else {
                    ""
                }
                "${mapping.schemaId} → $target · $visibleProperties$suffix"
            }
        return OpenApiEvolutionContext(
            owned = owned,
            normalized = normalized,
            review = IntegrationOpenApiEvolutionApprovalReview(
                sourcePath = owned.javaPath,
                operation = operation.operationId ?: "${operation.method} ${operation.path}",
                baselineSha256 = report.baselineSha256,
                candidateSha256 = report.candidateSha256,
                report = report,
                generatedFileCount = 2 + layer.sources.size,
                mappingDecisionCount = mappingDecisions.size,
                mappingDecisionSummaries = mappingDecisions.take(MAX_OPENAPI_APPROVAL_MAPPINGS),
            ),
        )
    }

    internal fun propose(model: IntegrationConnectorModel): IntegrationConnectorProposal {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val destination = destinations(graph).firstOrNull { it.id == model.destinationId }
            ?: return rejected(
                "JVW-INTEGRATION-DESTINATION-INVALID",
                "The selected module destination is no longer available. Refresh the workspace.",
            )
        val schema = SchemaWorkspaceService.getInstance(project).load()
        val selectedStoreId = when {
            model.reliability.outboxEnabled -> model.reliability.outbox?.storeId
            model.reliability.inboxEnabled -> model.reliability.inbox?.storeId
            else -> null
        }
        val selectedStore = selectedStoreId?.let { storeId ->
            schema.stores.firstOrNull { it.id == storeId }
        }
        val normalized = runCatching {
            normalizeBackendContracts(model, destination, selectedStore, schema)
        }.getOrElse { failure ->
            return rejected(
                "JVW-INTEGRATION-OPENAPI-CONTRACT-INVALID",
                failure.message ?: "The selected OpenAPI contract could not be resolved safely.",
                model.openApiBinding?.relativePath,
            )
        }
        val validation = IntegrationConnectorGenerator.validate(normalized, destination.capabilities)
        val issues = validation.diagnostics
            .filter { it.severity == IntegrationDiagnosticSeverity.ERROR }
            .map { WorkspaceChangeIssue(it.code, it.message) }
            .toMutableList()
        normalized.catalogBinding?.let { binding ->
            val resolution = runCatching {
                JmixTemplateCatalogManager.getInstance().resolveConnector(binding)
            }
            resolution.exceptionOrNull()?.let { failure ->
                issues += WorkspaceChangeIssue(
                    "JVW-INTEGRATION-CATALOG-BINDING-INVALID",
                    failure.message ?: "The signed organization connector template is unavailable.",
                )
            }
            val option = resolution.getOrNull()
            if (option != null) {
                issues += IntegrationConnectorCatalogPolicyService.getInstance(project).validate(
                    model = normalized,
                    option = option,
                    destination = destination,
                )
            }
        }
        if (
            normalized.authentication.kind == IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS &&
            oauth2Managers(graph).none { it.beanName == normalized.authentication.authorizedClientManagerBeanName }
        ) {
            issues += WorkspaceChangeIssue(
                "JVW-INTEGRATION-OAUTH-MANAGER-NOT-INDEXED",
                "The selected OAuth2AuthorizedClientManager bean is not present in the current application graph.",
            )
        }
        if (
            normalized.authentication.kind == IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS &&
            normalized.authentication.evictInvalidAuthorizedClient &&
            oauth2Services(graph).none { it.beanName == normalized.authentication.authorizedClientServiceBeanName }
        ) {
            issues += WorkspaceChangeIssue(
                "JVW-INTEGRATION-OAUTH-SERVICE-NOT-INDEXED",
                "The selected OAuth2AuthorizedClientService bean is not present in the current application graph.",
            )
        }
        if (normalized.kind in CONSUMER_KINDS) {
            val indexedHandlers = graph.artifacts.filter {
                it.kind in setOf(
                    ArtifactKind.SERVICE,
                    ArtifactKind.BUSINESS_RULE,
                    ArtifactKind.REPOSITORY,
                    ArtifactKind.VALIDATOR,
                    ArtifactKind.SOURCE_TYPE,
                )
            }.flatMap { listOf(it.semanticKey, it.displayName) }.toSet()
            if (normalized.handlerBeanClass !in indexedHandlers) {
                issues += WorkspaceChangeIssue(
                    "JVW-INTEGRATION-HANDLER-NOT-INDEXED",
                    "The selected inbound handler type is not present in the current application graph.",
                )
            }
        }
        if (normalized.reliability.outboxEnabled || normalized.reliability.inboxEnabled) {
            val ledger = if (normalized.reliability.outboxEnabled) "outbox" else "inbox"
            if (selectedStore == null) {
                issues += WorkspaceChangeIssue(
                    "JVW-INTEGRATION-${ledger.uppercase()}-STORE-NOT-INDEXED",
                    "The selected $ledger data store is not present in the current schema index.",
                )
            } else if (selectedStore.moduleId != destination.moduleId) {
                issues += WorkspaceChangeIssue(
                    "JVW-INTEGRATION-${ledger.uppercase()}-STORE-CROSS-MODULE",
                    "The persistent $ledger must use a data store owned by the connector's module.",
                )
            } else if (selectedStore.rootChangelogPath == null || selectedStore.generatedDirectory == null) {
                issues += WorkspaceChangeIssue(
                    "JVW-INTEGRATION-${ledger.uppercase()}-STORE-NOT-MIGRATABLE",
                    "The selected $ledger data store has no source-safe Liquibase root and generated migration directory.",
                    selectedStore.rootChangelogPath,
                )
            }
        }
        if (issues.isNotEmpty()) return IntegrationConnectorProposal(null, issues.distinct().sortedBy(WorkspaceChangeIssue::code))

        var stored = normalized.copy(sourceLocator = null)
        var migrationChanges = emptyList<WorkspaceFileChange>()
        if (
            normalized.sourceLocator == null &&
            (stored.reliability.outboxEnabled || stored.reliability.inboxEnabled)
        ) {
            val migrationPath = stored.reliability.outbox?.takeIf { stored.reliability.outboxEnabled }?.migrationPath
                ?: stored.reliability.inbox?.takeIf { stored.reliability.inboxEnabled }?.migrationPath
            if (migrationPath == null) {
                val isOutbox = stored.reliability.outboxEnabled
                val storeId = if (isOutbox) {
                    requireNotNull(stored.reliability.outbox).storeId
                } else {
                    requireNotNull(stored.reliability.inbox).storeId
                }
                val migration = if (isOutbox) {
                    IntegrationConnectorGenerator.outboxMigration(stored)
                } else {
                    IntegrationConnectorGenerator.inboxMigration(stored)
                }
                val suffix = if (isOutbox) "outbox" else "inbox"
                val migrationProposal = SchemaWorkspaceService.getInstance(project).migrationProposal(
                    SchemaMigrationChangeRequest(
                        storeId = storeId,
                        migration = migration,
                        fileName = "jvw-${stored.beanName}-$suffix.xml",
                    ),
                )
                val migrationChangeSet = migrationProposal.changeSet
                    ?: return IntegrationConnectorProposal(null, migrationProposal.issues)
                val migrationFile = migrationChangeSet.files.singleOrNull {
                    it.mode == WorkspaceFileChangeMode.CREATE && it.relativePath.endsWith(".xml")
                } ?: return rejected(
                    "JVW-INTEGRATION-LEDGER-MIGRATION-MISSING",
                    "Schema planning did not produce exactly one persistent integration-ledger migration.",
                )
                stored = if (isOutbox) {
                    val outbox = requireNotNull(stored.reliability.outbox)
                    stored.copy(
                        reliability = stored.reliability.copy(
                            outbox = outbox.copy(migrationPath = migrationFile.relativePath),
                        ),
                    )
                } else {
                    val inbox = requireNotNull(stored.reliability.inbox)
                    stored.copy(
                        reliability = stored.reliability.copy(
                            inbox = inbox.copy(migrationPath = migrationFile.relativePath),
                        ),
                    )
                }
                migrationChanges = migrationChangeSet.files
            }
        }
        val encoded = IntegrationConnectorGenerator.encode(stored)
        val generated = IntegrationConnectorGenerator.generate(stored, encoded)
        val jmixLayer = generateOpenApiJmixLayer(stored, destination, schema)
        if (jmixLayer.issues.isNotEmpty()) {
            return IntegrationConnectorProposal(
                null,
                jmixLayer.issues.map {
                    WorkspaceChangeIssue("JVW-INTEGRATION-JMIX-MAPPING-INVALID", it)
                },
            )
        }
        javaSyntaxError("${model.className}.java", generated.javaSource)?.let { syntax ->
            return rejected(
                "JVW-INTEGRATION-GENERATED-SYNTAX",
                "Generated Java is not syntactically valid: ${syntax.errorDescription}",
            )
        }
        jmixLayer.sources.forEach { source ->
            javaSyntaxError("${source.className}.java", source.content)?.let { syntax ->
                return rejected(
                    "JVW-INTEGRATION-JMIX-GENERATED-SYNTAX",
                    "Generated ${source.role.lowercase().replace('_', ' ')} '${source.className}' is not syntactically valid: ${syntax.errorDescription}",
                )
            }
        }
        if (migrationChanges.isNotEmpty()) {
            val generatedMigration = generated.migrationXml
                ?: return rejected(
                    "JVW-INTEGRATION-LEDGER-MIGRATION-GENERATION",
                    "The normalized connector did not regenerate its persistent integration-ledger migration.",
                )
            val plannedMigration = migrationChanges.single {
                it.mode == WorkspaceFileChangeMode.CREATE && it.relativePath.endsWith(".xml")
            }
            if (plannedMigration.createContent != generatedMigration) {
                return rejected(
                    "JVW-INTEGRATION-LEDGER-MIGRATION-DRIFT",
                    "The schema plan and connector-owned ledger migration differ; no files will be written.",
                    plannedMigration.relativePath,
                )
            }
        }
        val javaPath = javaPath(destination, stored)
        val policyPath = policyPath(destination, stored.beanName)
        val supplemental = jmixLayer.sources.associate { source ->
            supplementalJavaPath(destination, source) to source.content
        }
        val changes = if (normalized.sourceLocator == null) {
            migrationChanges + listOf(
                create(javaPath, generated.javaSource),
                create(policyPath, generated.reliabilityProperties),
            ) + supplemental.map { (path, content) -> create(path, content) }
        } else {
            val owned = loadOwned(normalized.sourceLocator, destination, schema)
                ?: return rejected(
                    "JVW-INTEGRATION-SOURCE-NOT-OWNED",
                    "The Java adapter or its reliability policy was manually changed. Neither file will be overwritten.",
                    normalized.sourceLocator.relativePath,
                )
            if (
                owned.model.reliability.outboxEnabled != stored.reliability.outboxEnabled ||
                owned.model.reliability.outbox?.storeId != stored.reliability.outbox?.storeId ||
                owned.model.reliability.outbox?.tableName != stored.reliability.outbox?.tableName ||
                owned.model.reliability.outbox?.migrationPath != stored.reliability.outbox?.migrationPath ||
                owned.model.reliability.inboxEnabled != stored.reliability.inboxEnabled ||
                owned.model.reliability.inbox?.storeId != stored.reliability.inbox?.storeId ||
                owned.model.reliability.inbox?.tableName != stored.reliability.inbox?.tableName ||
                owned.model.reliability.inbox?.migrationPath != stored.reliability.inbox?.migrationPath ||
                owned.model.reliability.inbox?.messageIdHeader != stored.reliability.inbox?.messageIdHeader ||
                owned.model.reliability.orderingRequired != stored.reliability.orderingRequired
            ) {
                return rejected(
                    "JVW-INTEGRATION-LEDGER-SCHEMA-IMMUTABLE",
                    "Outbox/inbox mode, data store, table, migration, message identity and ordering shape cannot be changed after creation. Create a replacement connector and migrate callers explicitly.",
                    owned.javaPath,
                )
            }
            if (owned.javaPath != javaPath || owned.policyPath != policyPath) {
                return rejected(
                    "JVW-INTEGRATION-MOVE-UNSUPPORTED",
                    "Changing module, package, class, or bean would move owned connector files. Create a new connector instead.",
                    owned.javaPath,
                )
            }
            if (normalized.sourceLocator.revisionFingerprint != owned.javaFingerprint) {
                return rejected(
                    "JVW-INTEGRATION-SOURCE-STALE",
                    "The connector source changed after it was loaded. Refresh before editing.",
                    owned.javaPath,
                )
            }
            if (owned.model.openApiBinding != stored.openApiBinding) {
                val previousBinding = owned.model.openApiBinding
                val candidateOperation = stored.resolvedOpenApiOperation
                if (previousBinding != null && candidateOperation == null) {
                    return rejected(
                        "JVW-INTEGRATION-OPENAPI-BINDING-REMOVAL-UNSAFE",
                        "An existing contract-owned connector cannot be converted to a manual signature in place. Create a replacement connector.",
                        owned.javaPath,
                    )
                }
                if (previousBinding != null && candidateOperation != null) {
                    val baseline = owned.model.openApiBaseline
                        ?: return rejected(
                            "JVW-INTEGRATION-OPENAPI-BASELINE-MISSING",
                            "This connector predates semantic OpenAPI baselines. Rebind it through a reviewed migration before regeneration.",
                            owned.javaPath,
                        )
                    val report = OpenApiContractEvolutionAnalyzer.compare(baseline, candidateOperation)
                    OpenApiEvolutionApprovalService.getInstance(project).validate(
                        previous = owned.model,
                        candidate = stored,
                        sourceRevision = owned.javaFingerprint,
                        report = report,
                    )?.let { return IntegrationConnectorProposal(null, listOf(it)) }
                }
            }
            val supplementalChanges = buildList {
                val oldPaths = owned.supplementalContent.keys
                val newPaths = supplemental.keys
                (oldPaths intersect newPaths).sorted().forEach { path ->
                    val before = requireNotNull(owned.supplementalContent[path])
                    add(
                        modify(
                            path,
                            before,
                            CanonicalDiscoveryJson.sha256(before),
                            requireNotNull(supplemental[path]),
                        ),
                    )
                }
                (newPaths - oldPaths).sorted().forEach { path ->
                    add(create(path, requireNotNull(supplemental[path])))
                }
                (oldPaths - newPaths).sorted().forEach { path ->
                    val before = requireNotNull(owned.supplementalContent[path])
                    add(
                        WorkspaceFileChange(
                            relativePath = path,
                            mode = WorkspaceFileChangeMode.DELETE,
                            baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(before),
                        ),
                    )
                }
            }
            listOf(
                modify(owned.javaPath, owned.javaContent, owned.javaFingerprint, generated.javaSource),
                modify(owned.policyPath, owned.policyContent, owned.policyFingerprint, generated.reliabilityProperties),
            ) + supplementalChanges
        }
        val identity = changes.joinToString("\u0000") {
            listOf(it.relativePath, it.createContent.orEmpty(), it.edits.firstOrNull()?.replacement.orEmpty())
                .joinToString("\u0001")
        }
        return IntegrationConnectorProposal(
            WorkspaceChangeSet(
                id = "integration-connector:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "${if (model.sourceLocator == null) "Create" else "Update"} integration connector ${model.name}",
                files = changes,
            ),
            emptyList(),
        )
    }

    private fun destinations(graph: ApplicationGraphResponse): List<IntegrationConnectorDestinationSnapshot> {
        val sourceService = ProjectSourceDestinationService.getInstance(project)
        val javaRoots = sourceService.productionJava(graph)
        val resourceRoots = sourceService.productionResources(graph).groupBy(ProjectSourceDestination::moduleId)
        val fallbackPackage = JmixProjectService.getInstance(project).getConfig()?.basePackage
            ?.takeIf(String::isNotBlank)
            ?: "com.example.app"
        val candidates = javaRoots.flatMap { javaRoot ->
            resourceRoots[javaRoot.moduleId].orEmpty().map { resourceRoot ->
                val jsonApi = detectJsonApi(javaRoot, graph)
                IntegrationConnectorDestinationSnapshot(
                    id = CanonicalDiscoveryJson.sha256(
                        "${javaRoot.moduleId}\u0000${javaRoot.sourceRoot}\u0000${resourceRoot.sourceRoot}",
                    ).take(24),
                    moduleId = javaRoot.moduleId,
                    sourceRoot = javaRoot.sourceRoot,
                    resourceRoot = resourceRoot.sourceRoot,
                    defaultPackage = "${moduleBasePackage(javaRoot.moduleId, graph, fallbackPackage)}.integration",
                    capabilities = detectCapabilities(javaRoot, graph),
                    jsonApi = jsonApi,
                    observabilityApi = detectObservabilityApi(javaRoot, graph),
                    springBootApi = if (jsonApi == IntegrationJsonApi.JACKSON_3) {
                        IntegrationSpringBootApi.BOOT_4
                    } else {
                        IntegrationSpringBootApi.BOOT_3
                    },
                    recommended = false,
                )
            }
        }.distinctBy(IntegrationConnectorDestinationSnapshot::id)
            .sortedWith(
                compareByDescending<IntegrationConnectorDestinationSnapshot> { destination ->
                    destination.capabilities.size
                }.thenBy(IntegrationConnectorDestinationSnapshot::moduleId)
                    .thenBy(IntegrationConnectorDestinationSnapshot::sourceRoot)
                    .thenBy(IntegrationConnectorDestinationSnapshot::resourceRoot),
            )
        return candidates.mapIndexed { index, destination -> destination.copy(recommended = index == 0) }
    }

    private fun detectCapabilities(
        javaRoot: ProjectSourceDestination,
        graph: ApplicationGraphResponse,
    ): Set<IntegrationCapability> {
        val resolver = ProjectFileResolver.getInstance(project)
        val moduleRoot = graph.modules.firstOrNull { it.moduleId == javaRoot.moduleId }?.moduleRoot
            ?.trim('/', '\\')
            .orEmpty()
        val buildText = listOf(
            join(moduleRoot, "build.gradle.kts"),
            join(moduleRoot, "build.gradle"),
            "gradle/libs.versions.toml",
        ).mapNotNull { path ->
            resolver.resolveFile(path)?.file?.let { file ->
                runCatching { String(file.contentsToByteArray(false), file.charset) }.getOrNull()
            }
        }.joinToString("\n").lowercase()
        return buildSet {
            if (
                listOf(
                    "spring-boot-starter-web",
                    "spring.web",
                    "spring-web",
                    "jmix-flowui",
                    "jmix-rest",
                ).any(buildText::contains)
            ) add(IntegrationCapability.SPRING_WEB)
            if ("kafka" in buildText) add(IntegrationCapability.SPRING_KAFKA)
            if (
                listOf("spring-boot-starter-kafka", "spring-boot-kafka")
                    .any(buildText::contains)
            ) {
                add(IntegrationCapability.SPRING_BOOT_KAFKA)
            }
            if (listOf("spring-rabbit", "spring.amqp", "spring-amqp", "rabbitmq").any(buildText::contains)) {
                add(IntegrationCapability.SPRING_AMQP)
            }
            if ("sftp" in buildText && "spring" in buildText) {
                add(IntegrationCapability.SPRING_INTEGRATION_SFTP)
            }
            if ("resilience4j" in buildText) add(IntegrationCapability.RESILIENCE4J)
            if (listOf("jmix-email", "jmix.email").any(buildText::contains)) add(IntegrationCapability.JMIX_EMAIL)
            if (listOf("io.jmix", "jmix-core", "jmix.core").any(buildText::contains)) {
                add(IntegrationCapability.JMIX_FILE_STORAGE)
            }
            if (
                listOf("oauth2-client", "spring.security.oauth2.client", "jmix-oidc", "jmix.oidc")
                    .any(buildText::contains)
            ) add(IntegrationCapability.OAUTH2_CLIENT)
            if (
                listOf("io.jmix", "spring-boot", "org.springframework.boot")
                    .any(buildText::contains)
            ) add(IntegrationCapability.SPRING_BOOT_SSL_BUNDLES)
        }
    }

    private fun oauth2Managers(graph: ApplicationGraphResponse): List<IntegrationOAuth2ManagerSnapshot> {
        val resolver = ProjectFileResolver.getInstance(project)
        return graph.artifacts.asSequence()
            .filter { it.kind in setOf(ArtifactKind.SOURCE_TYPE, ArtifactKind.SERVICE) }
            .distinctBy { it.sourceLocator.relativePath }
            .flatMap { artifact ->
                val content = resolver.resolveFile(artifact.sourceLocator.relativePath)?.file
                    ?.let(::fileText)
                    .orEmpty()
                val javaManagers = OAUTH2_MANAGER_JAVA.findAll(content).map { match ->
                    val explicitName = match.groupValues[1].ifBlank { null }
                    val methodName = match.groupValues[2]
                    IntegrationOAuth2ManagerSnapshot(
                        beanName = explicitName ?: methodName,
                        declaringType = artifact.semanticKey.substringBefore('#'),
                        moduleId = artifact.owner.moduleId,
                        sourceLocator = artifact.sourceLocator,
                    )
                }
                val kotlinManagers = OAUTH2_MANAGER_KOTLIN.findAll(content).map { match ->
                    val explicitName = match.groupValues[1].ifBlank { null }
                    val methodName = match.groupValues[2]
                    IntegrationOAuth2ManagerSnapshot(
                        beanName = explicitName ?: methodName,
                        declaringType = artifact.semanticKey.substringBefore('#'),
                        moduleId = artifact.owner.moduleId,
                        sourceLocator = artifact.sourceLocator,
                    )
                }
                javaManagers + kotlinManagers
            }
            .distinctBy { "${it.moduleId}\u0000${it.beanName}" }
            .sortedWith(
                compareBy<IntegrationOAuth2ManagerSnapshot>(
                    IntegrationOAuth2ManagerSnapshot::moduleId,
                    IntegrationOAuth2ManagerSnapshot::beanName,
                ),
            )
            .toList()
    }

    private fun oauth2Services(graph: ApplicationGraphResponse): List<IntegrationOAuth2ServiceSnapshot> {
        val resolver = ProjectFileResolver.getInstance(project)
        return graph.artifacts.asSequence()
            .filter { it.kind in setOf(ArtifactKind.SOURCE_TYPE, ArtifactKind.SERVICE) }
            .distinctBy { it.sourceLocator.relativePath }
            .flatMap { artifact ->
                val content = resolver.resolveFile(artifact.sourceLocator.relativePath)?.file
                    ?.let(::fileText)
                    .orEmpty()
                val javaServices = OAUTH2_SERVICE_JAVA.findAll(content).map { match ->
                    val explicitName = match.groupValues[1].ifBlank { null }
                    val methodName = match.groupValues[2]
                    IntegrationOAuth2ServiceSnapshot(
                        beanName = explicitName ?: methodName,
                        declaringType = artifact.semanticKey.substringBefore('#'),
                        moduleId = artifact.owner.moduleId,
                        sourceLocator = artifact.sourceLocator,
                    )
                }
                val kotlinServices = OAUTH2_SERVICE_KOTLIN.findAll(content).map { match ->
                    val explicitName = match.groupValues[1].ifBlank { null }
                    val methodName = match.groupValues[2]
                    IntegrationOAuth2ServiceSnapshot(
                        beanName = explicitName ?: methodName,
                        declaringType = artifact.semanticKey.substringBefore('#'),
                        moduleId = artifact.owner.moduleId,
                        sourceLocator = artifact.sourceLocator,
                    )
                }
                javaServices + kotlinServices
            }
            .distinctBy { "${it.moduleId}\u0000${it.beanName}" }
            .sortedWith(
                compareBy<IntegrationOAuth2ServiceSnapshot>(
                    IntegrationOAuth2ServiceSnapshot::moduleId,
                    IntegrationOAuth2ServiceSnapshot::beanName,
                ),
            )
            .toList()
    }

    /**
     * Builds a bounded mapping-extension catalog from the semantic application
     * graph and IntelliJ's class indexes. This runs only when the integration
     * workspace is requested; it never walks every source file or keys a cache
     * off the global PSI modification counter.
     */
    private fun mappingExtensionCatalog(
        graph: ApplicationGraphResponse,
        destinations: List<IntegrationConnectorDestinationSnapshot>,
    ): IntegrationMappingExtensionCatalog {
        val key = buildString {
            append(graph.snapshotDigest).append('\u0000')
            destinations.map(IntegrationConnectorDestinationSnapshot::id).sorted().forEach {
                append(it).append('\u0000')
            }
        }
        synchronized(mappingExtensionCatalogLock) {
            mappingExtensionCatalogCache[key]?.let { return it }
        }
        val computed = computeMappingExtensionCatalog(graph, destinations)
        synchronized(mappingExtensionCatalogLock) {
            if (mappingExtensionCatalogCache.size >= MAX_MAPPING_EXTENSION_CATALOGS) {
                mappingExtensionCatalogCache.remove(mappingExtensionCatalogCache.keys.first())
            }
            mappingExtensionCatalogCache[key] = computed
        }
        return computed
    }

    private fun computeMappingExtensionCatalog(
        graph: ApplicationGraphResponse,
        destinations: List<IntegrationConnectorDestinationSnapshot>,
    ): IntegrationMappingExtensionCatalog {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val projectAndLibrariesScope = scope.uniteWith(ProjectScope.getLibrariesScope(project))
        val enumClass = JavaPsiFacade.getInstance(project).findClass(JMIX_ENUM_CLASS, projectAndLibrariesScope)
        val enumSnapshots = graph.artifacts.asSequence()
            .filter { it.kind == ArtifactKind.ENUM }
            .distinctBy { it.id }
            .take(MAX_MAPPING_EXTENSION_TYPES)
            .mapNotNull { artifact ->
                val type = facade.findClass(artifact.semanticKey.substringBefore('#'), scope) ?: return@mapNotNull null
                if (!type.isEnum || !isJmixEnum(type, enumClass)) return@mapNotNull null
                val constants = type.fields.filterIsInstance<PsiEnumConstant>()
                    .map(PsiEnumConstant::getName)
                    .distinct()
                    .take(MAX_ENUM_CONSTANTS)
                if (constants.isEmpty()) return@mapNotNull null
                IntegrationOpenApiEnumAdapterSnapshot(
                    artifactId = artifact.id,
                    moduleId = artifact.owner.moduleId,
                    qualifiedName = requireNotNull(type.qualifiedName),
                    className = type.name.orEmpty(),
                    sourceLocator = artifact.sourceLocator,
                    constants = constants,
                    destinationIds = visibleDestinationIds(artifact.owner.moduleId, graph, destinations),
                )
            }
            .filter { it.destinationIds.isNotEmpty() }
            .sortedWith(compareBy(IntegrationOpenApiEnumAdapterSnapshot::className, IntegrationOpenApiEnumAdapterSnapshot::qualifiedName))
            .toList()

        val converterSnapshots = graph.artifacts.asSequence()
            .filter { it.kind in CONVERTER_TYPE_KINDS }
            .filter { '#' !in it.semanticKey }
            .distinctBy { it.id }
            .take(MAX_MAPPING_EXTENSION_TYPES)
            .mapNotNull { artifact ->
                val type = facade.findClass(artifact.semanticKey, scope) ?: return@mapNotNull null
                if (type.isInterface || type.isEnum || type.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    return@mapNotNull null
                }
                if (!isSpringComponent(type)) return@mapNotNull null
                val methods = type.methods.asSequence()
                    .filter { method -> method.containingClass == type && isConverterMethod(method) }
                    .map { method ->
                        val parameterType = method.parameterList.parameters.single().type.canonicalText
                        val returnType = requireNotNull(method.returnType).canonicalText
                        IntegrationOpenApiConverterMethodSnapshot(
                            signature = converterSignature(method.name, parameterType, returnType),
                            methodName = method.name,
                            parameterType = parameterType,
                            returnType = returnType,
                        )
                    }
                    .distinctBy(IntegrationOpenApiConverterMethodSnapshot::signature)
                    .sortedWith(compareBy(IntegrationOpenApiConverterMethodSnapshot::methodName, IntegrationOpenApiConverterMethodSnapshot::signature))
                    .take(MAX_CONVERTER_METHODS)
                    .toList()
                if (methods.isEmpty()) return@mapNotNull null
                IntegrationOpenApiConverterBeanSnapshot(
                    artifactId = artifact.id,
                    moduleId = artifact.owner.moduleId,
                    qualifiedName = requireNotNull(type.qualifiedName),
                    className = type.name.orEmpty(),
                    sourceLocator = artifact.sourceLocator,
                    methods = methods,
                    destinationIds = visibleDestinationIds(artifact.owner.moduleId, graph, destinations),
                )
            }
            .filter { it.destinationIds.isNotEmpty() }
            .sortedWith(compareBy(IntegrationOpenApiConverterBeanSnapshot::className, IntegrationOpenApiConverterBeanSnapshot::qualifiedName))
            .toList()
        return IntegrationMappingExtensionCatalog(enumSnapshots, converterSnapshots)
    }

    private fun visibleDestinationIds(
        ownerModuleId: String,
        graph: ApplicationGraphResponse,
        destinations: List<IntegrationConnectorDestinationSnapshot>,
    ): List<String> = destinations.filter { destination ->
        ownerModuleId in accessibleModules(destination.moduleId, graph)
    }.map(IntegrationConnectorDestinationSnapshot::id).sorted()

    private fun isJmixEnum(type: PsiClass, enumClass: PsiClass?): Boolean =
        enumClass?.let { base -> runCatching { type.isInheritor(base, true) }.getOrDefault(false) }
            ?: type.superTypes.any { superType ->
                superType.canonicalText.substringBefore('<') == JMIX_ENUM_CLASS ||
                    superType.canonicalText.substringBefore('<').substringAfterLast('.') == "EnumClass"
            }

    private fun isSpringComponent(type: PsiClass): Boolean =
        hasSpringComponentAnnotation(type, mutableSetOf(), 0)

    private fun hasSpringComponentAnnotation(
        type: PsiClass,
        visited: MutableSet<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_COMPONENT_META_DEPTH) return false
        return type.modifierList?.annotations.orEmpty().any { annotation ->
            val name = annotation.qualifiedName ?: return@any false
            if (name in SPRING_COMPONENT_ANNOTATIONS) return@any true
            if (!visited.add(name) || name.startsWith("java.") || name.startsWith("kotlin.")) return@any false
            val annotationClass = annotation.nameReferenceElement?.resolve() as? PsiClass ?: return@any false
            hasSpringComponentAnnotation(annotationClass, visited, depth + 1)
        }
    }

    private fun isConverterMethod(method: PsiMethod): Boolean =
        !method.isConstructor &&
            !method.isVarArgs &&
            method.typeParameters.isEmpty() &&
            method.parameterList.parametersCount == 1 &&
            method.returnType != null &&
            method.returnType?.canonicalText != "void" &&
            method.hasModifierProperty(PsiModifier.PUBLIC) &&
            !method.hasModifierProperty(PsiModifier.STATIC)

    private fun converterSignature(name: String, parameterType: String, returnType: String): String =
        "$name($parameterType):$returnType"

    private fun moduleBasePackage(
        moduleId: String,
        graph: ApplicationGraphResponse,
        fallback: String,
    ): String {
        val packages = graph.artifacts.asSequence()
            .filter {
                it.owner.moduleId == moduleId &&
                    it.kind in setOf(
                        ArtifactKind.ENTITY,
                        ArtifactKind.SERVICE,
                        ArtifactKind.BUSINESS_RULE,
                        ArtifactKind.REPOSITORY,
                        ArtifactKind.VIEW_CONTROLLER,
                    )
            }
            .map { it.semanticKey.substringBefore('#').substringBeforeLast('.', "") }
            .filter(String::isNotBlank)
            .map { packageName ->
                packageName.split('.').dropLastWhile { it in CONVENTIONAL_SUFFIXES }.joinToString(".")
                    .ifBlank { packageName }
            }
            .groupingBy { it }
            .eachCount()
        return packages.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length },
        )?.key ?: fallback
    }

    private fun discoverExisting(
        destinations: List<IntegrationConnectorDestinationSnapshot>,
        schema: SchemaWorkspaceResponse,
    ): List<IntegrationConnectorDocumentSnapshot> {
        val resolver = ProjectFileResolver.getInstance(project)
        val documents = mutableListOf<IntegrationConnectorDocumentSnapshot>()
        var visited = 0
        destinations.forEach { destination ->
            val target = resolver.resolveTarget(destination.sourceRoot) ?: return@forEach
            val root = if (target.relativePath.isBlank()) target.root
            else target.root.findFileByRelativePath(target.relativePath)
                ?: return@forEach
            visitJava(root) { file ->
                if (visited++ >= MAX_CONNECTOR_FILES || file.length > MAX_CONNECTOR_BYTES) return@visitJava
                val javaPath = resolver.locatorPath(file) ?: return@visitJava
                val javaContent = fileText(file) ?: return@visitJava
                val encoded = encodedJavaMarker(javaContent) ?: return@visitJava
                val model = decode(encoded) ?: return@visitJava
                val javaFingerprint = CanonicalDiscoveryJson.sha256(javaContent)
                val locator = SourceLocator(
                    relativePath = javaPath,
                    symbol = model.className,
                    revisionFingerprint = javaFingerprint,
                )
                val policyPath = policyPath(destination, model.beanName)
                val policyContent = resolver.resolveFile(policyPath)?.file?.let(::fileText)
                val exactResolution = runCatching { resolveOpenApiContract(model) }
                val regenerated = runCatching {
                    val resolved = normalizeOpenApiJmixLayer(
                        exactResolution.getOrElse { resolvePersistedOpenApiBaseline(model) },
                        destination,
                        schema,
                    )
                    IntegrationConnectorGenerator.generate(resolved, encoded) to
                        generateOpenApiJmixLayer(resolved, destination, schema)
                }.getOrNull()
                val migrationContent = ledgerMigrationPath(model)
                    ?.let { resolver.resolveFile(it)?.file?.let(::fileText) }
                val supplementalOwned = regenerated?.second?.takeIf { it.issues.isEmpty() }?.sources?.all { source ->
                    val path = supplementalJavaPath(destination, source)
                    resolver.resolveFile(path)?.file?.let(::fileText) == source.content
                } == true
                val owned = regenerated != null &&
                    regenerated.first.javaSource == javaContent &&
                    regenerated.first.reliabilityProperties == policyContent &&
                    (
                        regenerated.first.migrationXml == null ||
                            regenerated.first.migrationXml == migrationContent
                        ) &&
                    supplementalOwned
                val evolution = if (owned && exactResolution.isFailure && model.openApiBinding != null) {
                    runCatching {
                        val baseline = requireNotNull(model.openApiBaseline) {
                            "This connector predates semantic OpenAPI baselines."
                        }
                        val current = OpenApiContractService.getInstance(project)
                            .resolveCurrent(model.openApiBinding)
                        IntegrationOpenApiEvolutionReview(
                            candidateBinding = bindingFor(current.operation),
                            report = OpenApiContractEvolutionAnalyzer.compare(baseline, current.operation),
                            candidateTitle = current.contract.title,
                            candidateApiVersion = current.contract.apiVersion,
                            mappingIssues = openApiEvolutionMappingIssues(model, current.operation),
                            remapPlans = OpenApiJmixEvolutionRemapPlanner.plan(
                                baseline = baseline,
                                candidate = current.operation,
                                layer = model.openApiJmixLayer,
                            ),
                        )
                    }.getOrNull()
                } else null
                documents += IntegrationConnectorDocumentSnapshot(
                    locator = locator,
                    model = model.copy(destinationId = destination.id, sourceLocator = locator),
                    editable = owned && exactResolution.isSuccess,
                    issue = when {
                        !owned -> "Manual Java or reliability-policy changes were detected; visual overwrite is disabled."
                        evolution != null -> "The provider OpenAPI contract changed. Review and approve semantic regeneration."
                        else -> null
                    },
                    openApiEvolution = evolution,
                )
            }
        }
        return documents.distinctBy { it.locator.relativePath }.sortedBy { it.locator.relativePath }
    }

    private fun loadOwned(
        locator: SourceLocator,
        destination: IntegrationConnectorDestinationSnapshot,
        schema: SchemaWorkspaceResponse,
    ): OwnedIntegrationConnector? {
        val resolver = ProjectFileResolver.getInstance(project)
        val javaFile = resolver.resolveFile(locator.relativePath)?.file ?: return null
        val javaContent = fileText(javaFile) ?: return null
        val javaFingerprint = CanonicalDiscoveryJson.sha256(javaContent)
        if (javaFingerprint != locator.revisionFingerprint) return null
        val encoded = encodedJavaMarker(javaContent) ?: return null
        val model = decode(encoded) ?: return null
        val policyPath = policyPath(destination, model.beanName)
        val policyFile = resolver.resolveFile(policyPath)?.file ?: return null
        val policyContent = fileText(policyFile) ?: return null
        val policyFingerprint = CanonicalDiscoveryJson.sha256(policyContent)
        val resolved = runCatching {
            normalizeOpenApiJmixLayer(
                runCatching { resolveOpenApiContract(model) }
                    .getOrElse { resolvePersistedOpenApiBaseline(model) },
                destination,
                schema,
            )
        }.getOrNull() ?: return null
        val generated = runCatching {
            IntegrationConnectorGenerator.generate(resolved, encoded)
        }.getOrNull() ?: return null
        if (generated.javaSource != javaContent || generated.reliabilityProperties != policyContent) return null
        val migrationPath = ledgerMigrationPath(model)
        val migrationContent = migrationPath?.let { resolver.resolveFile(it)?.file?.let(::fileText) }
        if (generated.migrationXml != null && generated.migrationXml != migrationContent) return null
        val layer = generateOpenApiJmixLayer(resolved, destination, schema)
        if (layer.issues.isNotEmpty()) return null
        val supplementalContent = linkedMapOf<String, String>()
        layer.sources.forEach { source ->
            val path = supplementalJavaPath(destination, source)
            val content = resolver.resolveFile(path)?.file?.let(::fileText) ?: return null
            if (content != source.content) return null
            supplementalContent[path] = content
        }
        return OwnedIntegrationConnector(
            model = model,
            javaPath = locator.relativePath,
            javaContent = javaContent,
            javaFingerprint = javaFingerprint,
            policyPath = policyPath,
            policyContent = policyContent,
            policyFingerprint = policyFingerprint,
            migrationPath = migrationPath,
            migrationContent = migrationContent,
            supplementalContent = supplementalContent,
        )
    }

    private fun normalizeBackendContracts(
        model: IntegrationConnectorModel,
        destination: IntegrationConnectorDestinationSnapshot,
        selectedStore: SchemaDataStoreSnapshot?,
        schema: SchemaWorkspaceResponse,
    ): IntegrationConnectorModel {
        val reliability = model.reliability
        val outbox = reliability.outbox
        val inbox = reliability.inbox
        var normalizedReliability = reliability
        if (reliability.outboxEnabled && outbox != null) {
            normalizedReliability = normalizedReliability.copy(
                outbox = outbox.copy(
                    jsonApi = destination.jsonApi,
                    dataSourceBean = selectedStore?.name
                        ?.let { storeName -> if (storeName == "main") "dataSource" else "${storeName}DataSource" }
                        ?: "",
                    transactionManagerBean = selectedStore?.name
                        ?.let { storeName ->
                            if (storeName == "main") "transactionManager" else "${storeName}TransactionManager"
                        }
                        ?: "",
                ),
            )
        }
        if (reliability.inboxEnabled && inbox != null) {
            normalizedReliability = normalizedReliability.copy(
                inbox = inbox.copy(
                    jsonApi = destination.jsonApi,
                    dataSourceBean = selectedStore?.name
                        ?.let { storeName -> if (storeName == "main") "dataSource" else "${storeName}DataSource" }
                        ?: "",
                    transactionManagerBean = selectedStore?.name
                        ?.let { storeName ->
                            if (storeName == "main") "transactionManager" else "${storeName}TransactionManager"
                        }
                        ?: "",
                ),
            )
        }
        val resolved = normalizeOpenApiJmixLayer(
            resolveOpenApiContract(model),
            destination,
            schema,
        )
        return resolved.copy(
            reliability = normalizedReliability,
            observability = model.observability.copy(runtimeApi = destination.observabilityApi),
            runtimeJsonApi = destination.jsonApi,
            runtimeSpringBootApi = destination.springBootApi,
        )
    }

    private fun resolveOpenApiContract(model: IntegrationConnectorModel): IntegrationConnectorModel {
        val binding = model.openApiBinding
            ?: return model.copy(openApiBaseline = null, resolvedOpenApiOperation = null)
        val operation = OpenApiContractService.getInstance(project).resolve(binding).operation
        require(gson.toJson(operation).toByteArray(Charsets.UTF_8).size <= MAX_OPENAPI_BASELINE_BYTES) {
            "The normalized OpenAPI operation exceeds the ${MAX_OPENAPI_BASELINE_BYTES / 1024} KiB source-marker safety limit."
        }
        return applyOpenApiOperation(model, operation).copy(
            openApiBaseline = operation,
        )
    }

    private fun resolvePersistedOpenApiBaseline(model: IntegrationConnectorModel): IntegrationConnectorModel {
        val binding = model.openApiBinding
            ?: return model.copy(openApiBaseline = null, resolvedOpenApiOperation = null)
        val operation = requireNotNull(model.openApiBaseline) {
            "The connector has no backend-issued OpenAPI semantic baseline."
        }
        require(gson.toJson(operation).toByteArray(Charsets.UTF_8).size <= MAX_OPENAPI_BASELINE_BYTES) {
            "The persisted OpenAPI baseline exceeds the ${MAX_OPENAPI_BASELINE_BYTES / 1024} KiB safety limit."
        }
        require(
            operation.contractPath == binding.relativePath &&
                operation.contractSha256 == binding.documentSha256 &&
                operation.specificationVersion == binding.specificationVersion &&
                operation.operationId == binding.operationId &&
                operation.method == binding.method &&
                operation.path == binding.path &&
                operation.requestMediaType == binding.requestMediaType &&
                operation.responseStatus == binding.responseStatus &&
                operation.responseMediaType == binding.responseMediaType
        ) {
            "The persisted OpenAPI baseline does not match its exact source binding."
        }
        return applyOpenApiOperation(model, operation)
    }

    private fun applyOpenApiOperation(
        model: IntegrationConnectorModel,
        operation: org.jmixworkbench.model.IntegrationOpenApiOperationModel,
    ): IntegrationConnectorModel = model.copy(
        httpMethod = operation.method,
        contentType = operation.requestMediaType ?: model.contentType,
        payloadJavaType = IntegrationConnectorGenerator.openApiPayloadJavaType(
            operation,
            model.className,
        ),
        responseJavaType = IntegrationConnectorGenerator.openApiResponseJavaType(
            operation,
            model.className,
        ),
        resolvedOpenApiOperation = operation,
    )

    private fun bindingFor(
        operation: org.jmixworkbench.model.IntegrationOpenApiOperationModel,
    ) = org.jmixworkbench.model.IntegrationOpenApiBinding(
        relativePath = operation.contractPath,
        documentSha256 = operation.contractSha256,
        specificationVersion = operation.specificationVersion,
        operationId = operation.operationId,
        method = operation.method,
        path = operation.path,
        requestMediaType = operation.requestMediaType,
        responseStatus = operation.responseStatus,
        responseMediaType = operation.responseMediaType,
    )

    private fun openApiEvolutionMappingIssues(
        model: IntegrationConnectorModel,
        candidate: org.jmixworkbench.model.IntegrationOpenApiOperationModel,
    ): List<String> {
        val layer = model.openApiJmixLayer?.takeIf { it.enabled } ?: return emptyList()
        val baseline = model.openApiBaseline ?: return listOf(
            "The existing Jmix mapping has no semantic baseline and cannot be remapped automatically.",
        )
        val oldSchemas = baseline.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val newSchemas = candidate.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val reachable = reachableOpenApiSchemas(candidate).filterTo(linkedSetOf()) {
            newSchemas[it]?.kind == IntegrationOpenApiSchemaKind.OBJECT
        }
        val mappings = layer.mappings.associateBy(IntegrationOpenApiJmixTypeMapping::schemaId)
        return buildList {
            layer.mappings.filter { it.schemaId !in reachable }.forEach { mapping ->
                val target = mapping.existingEntity?.qualifiedName ?: mapping.generatedClassName ?: "generated DTO"
                add("Schema '${mapping.schemaId}' mapped to '$target' has no exact identity in the new contract and requires an explicit remap.")
            }
            (reachable - mappings.keys).sorted().forEach { schemaId ->
                add("New reachable schema '$schemaId' requires a reviewed Jmix target mapping.")
            }
            layer.mappings.filter { it.schemaId in reachable }.forEach { mapping ->
                val oldProperties = oldSchemas[mapping.schemaId]?.properties.orEmpty()
                    .mapTo(linkedSetOf(), org.jmixworkbench.model.IntegrationOpenApiPropertyModel::javaName)
                val newProperties = newSchemas[mapping.schemaId]?.properties.orEmpty()
                    .mapTo(linkedSetOf(), org.jmixworkbench.model.IntegrationOpenApiPropertyModel::javaName)
                mapping.properties.filter { it.schemaProperty in oldProperties && it.schemaProperty !in newProperties }
                    .forEach { property ->
                        add("Mapped property '${mapping.schemaId}.${property.schemaProperty}' no longer exists and requires an explicit replacement or removal.")
                    }
            }
        }.distinct().sorted().take(MAX_OPENAPI_EVOLUTION_MAPPING_ISSUES)
    }

    private fun normalizeOpenApiJmixLayer(
        model: IntegrationConnectorModel,
        destination: IntegrationConnectorDestinationSnapshot,
        schema: SchemaWorkspaceResponse,
    ): IntegrationConnectorModel {
        val layer = model.openApiJmixLayer ?: return model
        if (!layer.enabled) return model.copy(openApiJmixLayer = null)
        val operation = requireNotNull(model.resolvedOpenApiOperation) {
            "A Jmix entity mapping layer requires a backend-resolved OpenAPI operation."
        }
        val schemas = operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val reachableObjects = reachableOpenApiSchemas(operation)
            .mapNotNull(schemas::get)
            .filter { it.kind == IntegrationOpenApiSchemaKind.OBJECT }
        require(reachableObjects.isNotEmpty()) {
            "The selected request/response contract has no object graph that can be represented as Jmix entities."
        }
        fun rootEntitySchemaId(rootId: String?): String? {
            rootId ?: return null
            val root = schemas[rootId] ?: return null
            return when (root.kind) {
                IntegrationOpenApiSchemaKind.OBJECT -> root.id
                IntegrationOpenApiSchemaKind.ARRAY -> root.itemSchemaId?.takeIf {
                    schemas[it]?.kind == IntegrationOpenApiSchemaKind.OBJECT
                }
                else -> null
            }
        }
        require(operation.requestSchemaId == null || rootEntitySchemaId(operation.requestSchemaId) != null) {
            "The OpenAPI request body must be an object or an array of objects to expose a Jmix-facing service."
        }
        require(operation.responseSchemaId == null || rootEntitySchemaId(operation.responseSchemaId) != null) {
            "The OpenAPI response body must be an object or an array of objects to expose a Jmix-facing service."
        }

        val supplied = layer.mappings.associateBy(IntegrationOpenApiJmixTypeMapping::schemaId)
        val usedClassNames = linkedSetOf<String>()
        fun generatedName(schemaModel: IntegrationOpenApiSchemaModel, suppliedName: String?): String {
            val base = suppliedName?.trim().orEmpty().ifBlank {
                schemaModel.javaName
                    .removeSuffix("Model")
                    .removeSuffix("Dto")
                    .ifBlank { "${model.className}Entity" }
            }.replace(Regex("[^A-Za-z0-9_$]"), "_")
                .let { if (it.firstOrNull()?.isDigit() == true) "Type_$it" else it }
            var candidate = base
            var suffix = 2
            while (!usedClassNames.add(candidate)) candidate = "${base}${suffix++}"
            return candidate
        }
        val responseRoot = rootEntitySchemaId(operation.responseSchemaId)
        val normalizedMappings = reachableObjects.map { schemaModel ->
            val current = supplied[schemaModel.id]
            if (current?.targetKind == IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY) {
                requireNotNull(current.existingEntity) {
                    "Existing Jmix target for '${schemaModel.javaName}' has no immutable entity binding."
                }
                current.copy(generatedClassName = null)
            } else {
                val properties = current?.properties.orEmpty().ifEmpty {
                    schemaModel.properties.map { property ->
                        IntegrationOpenApiPropertyMapping(
                            schemaProperty = property.javaName,
                            entityProperty = property.javaName,
                            direction = IntegrationOpenApiMappingDirection.BIDIRECTIONAL,
                        )
                    }
                }
                val targetProperties = properties.mapTo(linkedSetOf(), IntegrationOpenApiPropertyMapping::entityProperty)
                val naturalId = current?.idProperty?.takeIf { it in targetProperties }
                    ?: schemaModel.properties.firstOrNull {
                        it.javaName in setOf("id", "uuid", "code", "externalId") &&
                            it.javaName in targetProperties
                    }?.javaName
                require(schemaModel.id != responseRoot || naturalId != null) {
                    "Response DTO '${schemaModel.javaName}' needs a stable identifier property (id, uuid, code, externalId, or an explicit selection)."
                }
                val instanceName = current?.instanceNameProperty?.takeIf { it in targetProperties }
                    ?: schemaModel.properties.firstOrNull {
                        it.javaName in setOf(
                            "name", "title", "caption", "label", "summary",
                            "description", "firstName", "lastName",
                        ) && it.javaName in targetProperties
                    }?.javaName
                IntegrationOpenApiJmixTypeMapping(
                    schemaId = schemaModel.id,
                    targetKind = IntegrationOpenApiJmixTargetKind.GENERATED_DTO,
                    generatedClassName = generatedName(schemaModel, current?.generatedClassName),
                    idProperty = naturalId,
                    instanceNameProperty = instanceName,
                    properties = properties,
                )
            }
        }
        require(supplied.keys.all { suppliedId -> normalizedMappings.any { it.schemaId == suppliedId } }) {
            "Jmix mappings contain a schema that is not reachable from the selected operation."
        }
        return model.copy(
            openApiJmixLayer = layer.copy(
                mappings = normalizeMappingExtensions(
                    model = model,
                    operation = operation,
                    mappings = normalizedMappings,
                    destination = destination,
                    schema = schema,
                ),
            ),
        )
    }

    private fun normalizeMappingExtensions(
        model: IntegrationConnectorModel,
        operation: org.jmixworkbench.model.IntegrationOpenApiOperationModel,
        mappings: List<IntegrationOpenApiJmixTypeMapping>,
        destination: IntegrationConnectorDestinationSnapshot,
        schema: SchemaWorkspaceResponse,
    ): List<IntegrationOpenApiJmixTypeMapping> {
        if (mappings.none { mapping ->
                mapping.properties.any { it.enumAdapter != null || it.customConverter != null }
            }
        ) return mappings
        val graph = ApplicationGraphService.getInstance(project).graph()
        val catalog = mappingExtensionCatalog(graph, listOf(destination))
        val enumByIdentity = catalog.enums.associateBy { it.artifactId to it.qualifiedName }
        val converterByIdentity = catalog.converters.associateBy { it.artifactId to it.qualifiedName }
        val schemas = operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        return mappings.map { mapping ->
            if (mapping.targetKind != IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY) {
                require(mapping.properties.none { it.enumAdapter != null || it.customConverter != null }) {
                    "Custom mapping extensions are valid only for indexed existing Jmix entities."
                }
                return@map mapping
            }
            val entityBinding = requireNotNull(mapping.existingEntity) {
                "Custom mapping extensions require an exact existing-entity binding."
            }
            val entity = schema.entities.singleOrNull {
                it.artifactId == entityBinding.artifactId && it.qualifiedName == entityBinding.qualifiedName
            } ?: throw IllegalArgumentException(
                "The entity used by custom mapping extensions is missing or ambiguous in the schema index.",
            )
            val attributes = (entity.attributes + entity.inheritedAttributes.map(SchemaInheritedAttributeSnapshot::attribute))
                .distinctBy(SchemaEntityAttributeSnapshot::name)
                .associateBy(SchemaEntityAttributeSnapshot::name)
            val objectSchema = requireNotNull(schemas[mapping.schemaId])
            val sourceProperties = objectSchema.properties.associateBy { it.javaName }
            mapping.copy(
                properties = mapping.properties.map { property ->
                    if (property.enumAdapter == null && property.customConverter == null) return@map property
                    require(property.enumAdapter == null || property.customConverter == null) {
                        "A property cannot use an enum adapter and a custom converter together."
                    }
                    val sourceProperty = requireNotNull(sourceProperties[property.schemaProperty]) {
                        "Mapped OpenAPI property '${mapping.schemaId}.${property.schemaProperty}' no longer exists."
                    }
                    val targetAttribute = requireNotNull(attributes[property.entityProperty]) {
                        "Mapped Jmix property '${entity.qualifiedName}.${property.entityProperty}' no longer exists."
                    }
                    val targetType = canonicalEntityPropertyType(entity.qualifiedName, property.entityProperty)
                        ?: targetAttribute.javaType
                    val wireType = OpenApiJmixLayerGenerator.transportType(
                        operation,
                        sourceProperty.schemaId,
                        model.packageName,
                        model.className,
                    )
                    property.enumAdapter?.let { supplied ->
                        val indexed = enumByIdentity[supplied.artifactId to supplied.qualifiedName]
                            ?: throw IllegalArgumentException(
                                "The selected Jmix enum '${supplied.qualifiedName}' is missing, stale, or not visible from '${destination.moduleId}'.",
                            )
                        require(supplied.revisionFingerprint == indexed.sourceLocator.revisionFingerprint) {
                            "Jmix enum '${indexed.qualifiedName}' changed after selection. Refresh and review its value mapping."
                        }
                        require(typeMatches(indexed.qualifiedName, targetType)) {
                            "Jmix enum '${indexed.qualifiedName}' does not match target property type '$targetType'."
                        }
                        val enumSchema = requireNotNull(schemas[sourceProperty.schemaId])
                        require(
                            enumSchema.kind == IntegrationOpenApiSchemaKind.STRING &&
                                enumSchema.enumValues.isNotEmpty()
                        ) {
                            "Enum adapters require an OpenAPI string enum property."
                        }
                        val suppliedByWire = supplied.values.groupBy(IntegrationOpenApiEnumValueMapping::wireValue)
                        require(suppliedByWire.keys == enumSchema.enumValues.toSet()) {
                            "Enum adapter must map every OpenAPI wire value exactly once."
                        }
                        require(suppliedByWire.values.all { it.size == 1 }) {
                            "Enum adapter contains duplicate wire-value decisions."
                        }
                        val normalizedValues = enumSchema.enumValues.map { wireValue ->
                            val decision = requireNotNull(suppliedByWire[wireValue]?.singleOrNull())
                            require(decision.enumConstant in indexed.constants) {
                                "Enum constant '${decision.enumConstant}' is absent from '${indexed.qualifiedName}'."
                            }
                            IntegrationOpenApiEnumValueMapping(wireValue, decision.enumConstant)
                        }
                        if (property.direction != IntegrationOpenApiMappingDirection.INBOUND) {
                            require(normalizedValues.map { it.enumConstant }.toSet() == indexed.constants.toSet()) {
                                "Outbound enum mapping must cover every constant of '${indexed.qualifiedName}' exactly once."
                            }
                            require(normalizedValues.map { it.enumConstant }.distinct().size == normalizedValues.size) {
                                "Outbound enum mapping cannot assign multiple wire values to one domain constant."
                            }
                        }
                        return@map property.copy(
                            enumAdapter = IntegrationOpenApiEnumAdapterBinding(
                                artifactId = indexed.artifactId,
                                qualifiedName = indexed.qualifiedName,
                                revisionFingerprint = indexed.sourceLocator.revisionFingerprint,
                                values = normalizedValues,
                            ),
                            customConverter = null,
                        )
                    }
                    property.customConverter?.let { supplied ->
                        val indexed = converterByIdentity[supplied.artifactId to supplied.qualifiedName]
                            ?: throw IllegalArgumentException(
                                "The selected converter '${supplied.qualifiedName}' is missing, stale, or not visible from '${destination.moduleId}'.",
                            )
                        require(supplied.revisionFingerprint == indexed.sourceLocator.revisionFingerprint) {
                            "Converter '${indexed.qualifiedName}' changed after selection. Refresh and review its method bindings."
                        }
                        fun resolveMethod(
                            method: IntegrationOpenApiConverterMethodBinding?,
                            expectedParameter: String,
                            expectedReturn: String,
                            label: String,
                            required: Boolean,
                        ): IntegrationOpenApiConverterMethodBinding? {
                            if (!required && method == null) return null
                            val selected = requireNotNull(method) {
                                "Converter '${indexed.qualifiedName}' needs a $label method."
                            }
                            val resolved = indexed.methods.singleOrNull { it.signature == selected.signature }
                                ?: throw IllegalArgumentException(
                                    "Converter $label method '${selected.signature}' is missing or ambiguous.",
                                )
                            require(typeMatches(expectedParameter, resolved.parameterType)) {
                                "Converter $label parameter '${resolved.parameterType}' must match '$expectedParameter'."
                            }
                            require(typeMatches(expectedReturn, resolved.returnType)) {
                                "Converter $label return '${resolved.returnType}' must match '$expectedReturn'."
                            }
                            return IntegrationOpenApiConverterMethodBinding(
                                signature = resolved.signature,
                                methodName = resolved.methodName,
                                parameterType = resolved.parameterType,
                                returnType = resolved.returnType,
                            )
                        }
                        val inboundRequired = property.direction != IntegrationOpenApiMappingDirection.OUTBOUND
                        val outboundRequired = property.direction != IntegrationOpenApiMappingDirection.INBOUND
                        return@map property.copy(
                            enumAdapter = null,
                            customConverter = IntegrationOpenApiCustomConverterBinding(
                                artifactId = indexed.artifactId,
                                qualifiedName = indexed.qualifiedName,
                                revisionFingerprint = indexed.sourceLocator.revisionFingerprint,
                                inboundMethod = resolveMethod(
                                    supplied.inboundMethod,
                                    wireType,
                                    targetType,
                                    "API-to-Jmix",
                                    inboundRequired,
                                ),
                                outboundMethod = resolveMethod(
                                    supplied.outboundMethod,
                                    targetType,
                                    wireType,
                                    "Jmix-to-API",
                                    outboundRequired,
                                ),
                            ),
                        )
                    }
                    property
                },
            )
        }
    }

    private fun canonicalEntityPropertyType(qualifiedName: String, propertyName: String): String? {
        val type = JavaPsiFacade.getInstance(project).findClass(
            qualifiedName,
            GlobalSearchScope.projectScope(project),
        ) ?: return null
        type.findFieldByName(propertyName, true)?.type?.canonicalText?.let { return it }
        val suffix = propertyName.replaceFirstChar(Char::uppercaseChar)
        val getterTypes = type.allMethods.asSequence()
            .filter { it.parameterList.parametersCount == 0 && it.name in setOf("get$suffix", "is$suffix") }
            .mapNotNull { it.returnType?.canonicalText }
            .distinct()
            .toList()
        if (getterTypes.size == 1) return getterTypes.single()
        val setterTypes = type.allMethods.asSequence()
            .filter { it.name == "set$suffix" && it.parameterList.parametersCount == 1 }
            .map { it.parameterList.parameters.single().type.canonicalText }
            .distinct()
            .toList()
        return setterTypes.singleOrNull()
    }

    private fun typeMatches(expected: String, actual: String): Boolean {
        fun normalized(value: String): String = value
            .replace("?", "")
            .replace(Regex("\\s+"), "")
            .let { JAVA_BOXED_TYPES[it] ?: it }
        val left = normalized(expected)
        val right = normalized(actual)
        return left == right || (
            '.' !in left && left.substringAfterLast('.') == right.substringAfterLast('.')
        ) || (
            '.' !in right && left.substringAfterLast('.') == right.substringAfterLast('.')
        )
    }

    private fun generateOpenApiJmixLayer(
        model: IntegrationConnectorModel,
        destination: IntegrationConnectorDestinationSnapshot,
        schema: SchemaWorkspaceResponse,
    ): OpenApiJmixLayerGenerator.Result {
        val layer = model.openApiJmixLayer
            ?: return OpenApiJmixLayerGenerator.Result(emptyList(), emptyList())
        if (!layer.enabled) return OpenApiJmixLayerGenerator.Result(emptyList(), emptyList())
        val operation = model.resolvedOpenApiOperation
            ?: return OpenApiJmixLayerGenerator.Result(
                emptyList(),
                listOf("Jmix mapping requires a resolved OpenAPI operation."),
            )
        val existingTargets = linkedMapOf<String, OpenApiJmixLayerGenerator.ResolvedEntityTarget>()
        layer.mappings.filter {
            it.targetKind == IntegrationOpenApiJmixTargetKind.EXISTING_ENTITY
        }.forEach { mapping ->
            val binding = mapping.existingEntity
                ?: return OpenApiJmixLayerGenerator.Result(
                    emptyList(),
                    listOf("Existing entity mapping '${mapping.schemaId}' has no immutable binding."),
                )
            val entity = schema.entities.singleOrNull {
                it.artifactId == binding.artifactId && it.qualifiedName == binding.qualifiedName
            } ?: return OpenApiJmixLayerGenerator.Result(
                emptyList(),
                listOf("Mapped Jmix entity '${binding.qualifiedName}' is missing or ambiguous in the current index."),
            )
            if (entity.sourceLocator.revisionFingerprint != binding.revisionFingerprint) {
                return OpenApiJmixLayerGenerator.Result(
                    emptyList(),
                    listOf("Mapped Jmix entity '${binding.qualifiedName}' changed after selection. Refresh and review the mapping."),
                )
            }
            if (entity.moduleId != destination.moduleId) {
                val graph = ApplicationGraphService.getInstance(project).graph()
                val accessible = accessibleModules(destination.moduleId, graph)
                if (entity.moduleId !in accessible) {
                    return OpenApiJmixLayerGenerator.Result(
                        emptyList(),
                        listOf("Mapped entity '${binding.qualifiedName}' is not compile-visible from module '${destination.moduleId}'."),
                    )
                }
            }
            existingTargets[mapping.schemaId] = OpenApiJmixLayerGenerator.ResolvedEntityTarget(
                artifactId = entity.artifactId,
                qualifiedName = entity.qualifiedName,
                entityType = entity.entityType,
                attributes = (
                    entity.attributes + entity.inheritedAttributes.map(SchemaInheritedAttributeSnapshot::attribute)
                    ).distinctBy(SchemaEntityAttributeSnapshot::name)
                    .map {
                        OpenApiJmixLayerGenerator.ResolvedEntityAttribute(
                            name = it.name,
                            javaType = it.javaType,
                            readOnly = it.readOnly,
                        )
                    },
            )
        }
        val projectId = schema.modules.firstOrNull { it.moduleId == destination.moduleId }?.projectId
        val entityNamePrefix = projectId
            ?.replace(Regex("[^A-Za-z0-9_$]"), "_")
            ?.trim('_')
            ?.takeIf(String::isNotBlank)
            ?: destination.defaultPackage.substringBefore('.').replace(Regex("[^A-Za-z0-9_$]"), "_")
                .ifBlank { "integration" }
        return OpenApiJmixLayerGenerator.generate(
            OpenApiJmixLayerGenerator.Input(
                connector = model,
                operation = operation,
                layer = layer,
                entityNamePrefix = entityNamePrefix,
                existingTargets = existingTargets,
            ),
        )
    }

    private fun reachableOpenApiSchemas(operation: org.jmixworkbench.model.IntegrationOpenApiOperationModel): Set<String> {
        val schemas = operation.schemas.associateBy(IntegrationOpenApiSchemaModel::id)
        val visited = linkedSetOf<String>()
        fun visit(schemaId: String) {
            if (!visited.add(schemaId)) return
            val schema = schemas[schemaId] ?: return
            schema.properties.forEach { visit(it.schemaId) }
            schema.itemSchemaId?.let(::visit)
            schema.additionalPropertiesSchemaId?.let(::visit)
        }
        operation.requestSchemaId?.let(::visit)
        operation.responseSchemaId?.let(::visit)
        return visited
    }

    private fun accessibleModules(
        moduleId: String,
        graph: ApplicationGraphResponse,
    ): Set<String> {
        val moduleArtifacts = graph.artifacts.filter { it.kind == ArtifactKind.MODULE }
        val idToModule = moduleArtifacts.associate { it.id to it.owner.moduleId }
        val moduleToId = moduleArtifacts.associate { it.owner.moduleId to it.id }
        val dependencies = graph.relationships
            .filter { it.type == org.jmixworkbench.discovery.model.RelationshipType.DEPENDS_ON_MODULE }
            .groupBy { idToModule[it.sourceArtifactId] }
        val visited = linkedSetOf(moduleId)
        val queue = ArrayDeque<String>()
        queue += moduleId
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val sourceId = moduleToId[current] ?: continue
            dependencies[current].orEmpty()
                .filter { it.sourceArtifactId == sourceId }
                .mapNotNull { it.targetArtifactId?.let(idToModule::get) }
                .forEach { dependency ->
                    if (visited.add(dependency)) queue += dependency
                }
        }
        return visited
    }

    private fun supplementalJavaPath(
        destination: IntegrationConnectorDestinationSnapshot,
        source: OpenApiJmixLayerGenerator.GeneratedSource,
    ): String = join(destination.sourceRoot, source.packageRelativePath)

    private fun detectJsonApi(
        javaRoot: ProjectSourceDestination,
        graph: ApplicationGraphResponse,
    ): IntegrationJsonApi {
        val buildText = moduleBuildText(javaRoot, graph)
        val major = Regex("""(?:id\s*\(\s*["']io\.jmix["']\s*\)|io\.jmix[^:\s"']*)[\s\S]{0,100}?([23])\.""")
            .find(buildText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return if (
            major != null && major >= 3 ||
            "tools.jackson" in buildText ||
            "jackson-databind:3." in buildText
        ) {
            IntegrationJsonApi.JACKSON_3
        } else {
            IntegrationJsonApi.JACKSON_2
        }
    }

    private fun detectObservabilityApi(
        javaRoot: ProjectSourceDestination,
        graph: ApplicationGraphResponse,
    ): IntegrationObservabilityApi {
        val buildText = moduleBuildText(javaRoot, graph)
        return if (
            listOf(
                "spring-boot-starter-actuator",
                "micrometer-core",
                "micrometer-observation",
                "micrometer-registry-",
            ).any(buildText::contains)
        ) {
            IntegrationObservabilityApi.MICROMETER_OBSERVATION
        } else {
            IntegrationObservabilityApi.APPLICATION_EVENTS
        }
    }

    private fun moduleBuildText(
        javaRoot: ProjectSourceDestination,
        graph: ApplicationGraphResponse,
    ): String {
        val resolver = ProjectFileResolver.getInstance(project)
        val moduleRoot = graph.modules.firstOrNull { it.moduleId == javaRoot.moduleId }?.moduleRoot
            ?.trim('/', '\\')
            .orEmpty()
        return listOf(
            join(moduleRoot, "build.gradle.kts"),
            join(moduleRoot, "build.gradle"),
            "gradle/libs.versions.toml",
        ).mapNotNull { path ->
            resolver.resolveFile(path)?.file?.let { file ->
                runCatching { String(file.contentsToByteArray(false), file.charset) }.getOrNull()
            }
        }.joinToString("\n").lowercase()
    }

    private fun javaPath(
        destination: IntegrationConnectorDestinationSnapshot,
        model: IntegrationConnectorModel,
    ): String = join(
        destination.sourceRoot,
        "${model.packageName.replace('.', '/')}/${model.className}.java",
    )

    private fun policyPath(
        destination: IntegrationConnectorDestinationSnapshot,
        beanName: String,
    ): String = join(destination.resourceRoot, "META-INF/jvw/integration/$beanName.properties")

    private fun ledgerMigrationPath(model: IntegrationConnectorModel): String? =
        model.reliability.outbox?.takeIf { model.reliability.outboxEnabled }?.migrationPath
            ?: model.reliability.inbox?.takeIf { model.reliability.inboxEnabled }?.migrationPath

    private fun create(path: String, content: String): WorkspaceFileChange =
        WorkspaceFileChange(path, WorkspaceFileChangeMode.CREATE, null, createContent = content)

    private fun modify(
        path: String,
        current: String,
        fingerprint: String,
        replacement: String,
    ): WorkspaceFileChange =
        WorkspaceFileChange(
            relativePath = path,
            mode = WorkspaceFileChangeMode.MODIFY,
            baseRevisionFingerprint = fingerprint,
            edits = listOf(WorkspaceTextEdit(0, current.length, current, replacement)),
        )

    private fun javaSyntaxError(fileName: String, content: String): PsiErrorElement? =
        PsiTreeUtil.findChildOfType(
            PsiFileFactory.getInstance(project).createFileFromText(fileName, JavaFileType.INSTANCE, content),
            PsiErrorElement::class.java,
        )

    private fun encodedJavaMarker(content: String): String? =
        content.lineSequence()
            .firstOrNull { it.startsWith(IntegrationConnectorGenerator.markerPrefix()) }
            ?.removePrefix(IntegrationConnectorGenerator.markerPrefix())
            ?.trim()
            ?.takeIf { it.length <= MAX_MARKER_LENGTH }

    private fun decode(encoded: String): IntegrationConnectorModel? = runCatching {
        val json = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        val root = JsonParser.parseString(json).asJsonObject
        if (!root.has("transportSecurity")) {
            root.add("transportSecurity", gson.toJsonTree(IntegrationTransportSecurityModel()))
        }
        root.getAsJsonObject("authentication")?.let { authentication ->
            if (!authentication.has("evictInvalidAuthorizedClient")) {
                authentication.addProperty("evictInvalidAuthorizedClient", true)
            }
        }
        root.getAsJsonObject("openApiBaseline")?.getAsJsonArray("schemas")?.forEach { schemaElement ->
            val schema = schemaElement.asJsonObject
            val defaults = gson.toJsonTree(
                org.jmixworkbench.model.IntegrationOpenApiValidationModel(),
            ).asJsonObject
            if (!schema.has("validation")) {
                schema.add("validation", defaults)
            } else {
                val validation = schema.getAsJsonObject("validation")
                defaults.entrySet().forEach { (name, value) ->
                    if (!validation.has(name)) validation.add(name, value)
                }
            }
        }
        val decoded = gson.fromJson(root, IntegrationConnectorModel::class.java)
        require(decoded.resolvedOpenApiOperation == null) {
            "Transient OpenAPI resolution must not be persisted."
        }
        require(decoded.openApiEvolutionCapability == null) {
            "OpenAPI evolution capabilities must not be persisted."
        }
        require(decoded.catalogBinding?.approvalCapability == null) {
            "Catalog approval capabilities must not be persisted."
        }
        val binding = decoded.openApiBinding
        val baseline = decoded.openApiBaseline
        require(binding != null || baseline == null) {
            "An OpenAPI baseline cannot exist without an exact contract binding."
        }
        if (binding != null && baseline != null) {
            require(gson.toJson(baseline).toByteArray(Charsets.UTF_8).size <= MAX_OPENAPI_BASELINE_BYTES)
            require(
                baseline.contractPath == binding.relativePath &&
                    baseline.contractSha256 == binding.documentSha256 &&
                    baseline.specificationVersion == binding.specificationVersion &&
                    baseline.operationId == binding.operationId &&
                    baseline.method == binding.method &&
                    baseline.path == binding.path &&
                    baseline.requestMediaType == binding.requestMediaType &&
                    baseline.responseStatus == binding.responseStatus &&
                    baseline.responseMediaType == binding.responseMediaType
            ) {
                "The persisted OpenAPI baseline does not match its binding."
            }
        }
        decoded.copy(sourceLocator = null)
    }.getOrNull()

    private fun visitJava(root: VirtualFile, consumer: (VirtualFile) -> Unit) {
        if (root.isDirectory) root.children.sortedBy(VirtualFile::getName).forEach { visitJava(it, consumer) }
        else if (root.extension.equals("java", ignoreCase = true)) consumer(root)
    }

    private fun fileText(file: VirtualFile): String? =
        runCatching { String(file.contentsToByteArray(false), file.charset) }.getOrNull()

    private fun join(prefix: String, suffix: String): String =
        listOf(prefix.trim('/', '\\'), suffix.trim('/', '\\')).filter(String::isNotBlank).joinToString("/")

    private fun rejected(
        code: String,
        message: String,
        relativePath: String? = null,
    ): IntegrationConnectorProposal =
        IntegrationConnectorProposal(null, listOf(WorkspaceChangeIssue(code, message, relativePath)))

    companion object {
        private const val MAX_CONNECTOR_FILES = 50_000
        private const val MAX_CONNECTOR_BYTES = 8L * 1024 * 1024
        private const val MAX_OPENAPI_BASELINE_BYTES = 512 * 1024
        private const val MAX_OPENAPI_EVOLUTION_MAPPING_ISSUES = 256
        private const val MAX_OPENAPI_APPROVAL_MAPPINGS = 12
        private const val MAX_OPENAPI_APPROVAL_PROPERTIES = 8
        private const val MAX_MARKER_LENGTH = 4_000_000
        private const val MAX_MAPPING_EXTENSION_TYPES = 2_000
        private const val MAX_CONVERTER_METHODS = 128
        private const val MAX_ENUM_CONSTANTS = 512
        private const val MAX_COMPONENT_META_DEPTH = 4
        private const val MAX_MAPPING_EXTENSION_CATALOGS = 8
        private const val JMIX_ENUM_CLASS = "io.jmix.core.metamodel.datatype.EnumClass"
        private val SPRING_COMPONENT_ANNOTATIONS = setOf(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
        )
        private val CONVERTER_TYPE_KINDS = setOf(
            ArtifactKind.SERVICE,
            ArtifactKind.BUSINESS_RULE,
            ArtifactKind.SOURCE_TYPE,
        )
        private val JAVA_BOXED_TYPES = mapOf(
            "boolean" to "java.lang.Boolean",
            "byte" to "java.lang.Byte",
            "short" to "java.lang.Short",
            "int" to "java.lang.Integer",
            "long" to "java.lang.Long",
            "float" to "java.lang.Float",
            "double" to "java.lang.Double",
            "char" to "java.lang.Character",
        )
        private val CONSUMER_KINDS = setOf(
            IntegrationConnectorKind.KAFKA_CONSUMER,
            IntegrationConnectorKind.RABBIT_CONSUMER,
        )
        private val CONTEXT_KINDS = setOf(
            ArtifactKind.SERVICE,
            ArtifactKind.SERVICE_METHOD,
            ArtifactKind.BUSINESS_RULE,
            ArtifactKind.REPOSITORY,
            ArtifactKind.VALIDATOR,
            ArtifactKind.SOURCE_TYPE,
            ArtifactKind.INTEGRATION_ENDPOINT,
            ArtifactKind.CONFIGURATION_PROPERTY,
            ArtifactKind.EVENT_LISTENER,
            ArtifactKind.SCHEDULED_JOB,
        )
        private val CONVENTIONAL_SUFFIXES = setOf(
            "entity",
            "service",
            "integration",
            "repository",
            "view",
            "security",
            "controller",
        )
        private val OAUTH2_MANAGER_JAVA = Regex(
            """@Bean(?:\s*\(\s*(?:(?:name|value)\s*=\s*)?["']([^"']+)["'][^)]*\))?(?:(?!@Bean)[\s\S]){0,320}?\bOAuth2AuthorizedClientManager\s+([A-Za-z_]\w*)\s*\(""",
        )
        private val OAUTH2_MANAGER_KOTLIN = Regex(
            """@Bean(?:\s*\(\s*(?:(?:name|value)\s*=\s*)?["']([^"']+)["'][^)]*\))?(?:(?!@Bean)[\s\S]){0,320}?\bfun\s+([A-Za-z_]\w*)\s*\([^)]*\)\s*:\s*OAuth2AuthorizedClientManager\b""",
        )
        private val OAUTH2_SERVICE_JAVA = Regex(
            """@Bean(?:\s*\(\s*(?:(?:name|value)\s*=\s*)?["']([^"']+)["'][^)]*\))?(?:(?!@Bean)[\s\S]){0,320}?\bOAuth2AuthorizedClientService\s+([A-Za-z_]\w*)\s*\(""",
        )
        private val OAUTH2_SERVICE_KOTLIN = Regex(
            """@Bean(?:\s*\(\s*(?:(?:name|value)\s*=\s*)?["']([^"']+)["'][^)]*\))?(?:(?!@Bean)[\s\S]){0,320}?\bfun\s+([A-Za-z_]\w*)\s*\([^)]*\)\s*:\s*OAuth2AuthorizedClientService\b""",
        )

        fun getInstance(project: Project): IntegrationConnectorWorkspaceService =
            project.getService(IntegrationConnectorWorkspaceService::class.java)
    }
}

data class IntegrationConnectorDestinationSnapshot(
    val id: String,
    val moduleId: String,
    val sourceRoot: String,
    val resourceRoot: String,
    val defaultPackage: String,
    val capabilities: Set<IntegrationCapability>,
    val jsonApi: IntegrationJsonApi,
    val observabilityApi: IntegrationObservabilityApi,
    val springBootApi: IntegrationSpringBootApi,
    val recommended: Boolean,
)

data class IntegrationConnectorDocumentSnapshot(
    val locator: SourceLocator,
    val model: IntegrationConnectorModel,
    val editable: Boolean,
    val issue: String?,
    val openApiEvolution: IntegrationOpenApiEvolutionReview? = null,
)

data class IntegrationOpenApiEvolutionReview(
    val candidateBinding: org.jmixworkbench.model.IntegrationOpenApiBinding,
    val report: OpenApiEvolutionReport,
    val candidateTitle: String,
    val candidateApiVersion: String?,
    val mappingIssues: List<String> = emptyList(),
    val remapPlans: List<OpenApiSchemaRemapPlan> = emptyList(),
)

data class IntegrationOpenApiEvolutionApprovalReview(
    val sourcePath: String,
    val operation: String,
    val baselineSha256: String,
    val candidateSha256: String,
    val report: OpenApiEvolutionReport,
    val generatedFileCount: Int,
    val mappingDecisionCount: Int = 0,
    val mappingDecisionSummaries: List<String> = emptyList(),
)

private data class OpenApiEvolutionContext(
    val owned: OwnedIntegrationConnector,
    val normalized: IntegrationConnectorModel,
    val review: IntegrationOpenApiEvolutionApprovalReview,
)

data class IntegrationConnectorWorkspaceResponse(
    val graphDigest: String,
    val destinations: List<IntegrationConnectorDestinationSnapshot>,
    val defaultDestinationId: String?,
    val contextArtifacts: List<ArtifactSnapshot>,
    val oauth2Managers: List<IntegrationOAuth2ManagerSnapshot>,
    val oauth2Services: List<IntegrationOAuth2ServiceSnapshot>,
    val dataStores: List<SchemaDataStoreSnapshot>,
    val entities: List<SchemaEntitySnapshot> = emptyList(),
    val enumAdapters: List<IntegrationOpenApiEnumAdapterSnapshot> = emptyList(),
    val converterBeans: List<IntegrationOpenApiConverterBeanSnapshot> = emptyList(),
    val openApiContracts: List<OpenApiContractSnapshot> = emptyList(),
    val organizationConnectorTemplates: List<IntegrationOrganizationConnectorTemplateSnapshot> = emptyList(),
    val existingDocuments: List<IntegrationConnectorDocumentSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

private data class IntegrationMappingExtensionCatalog(
    val enums: List<IntegrationOpenApiEnumAdapterSnapshot>,
    val converters: List<IntegrationOpenApiConverterBeanSnapshot>,
)

data class IntegrationOpenApiEnumAdapterSnapshot(
    val artifactId: String,
    val moduleId: String,
    val qualifiedName: String,
    val className: String,
    val sourceLocator: SourceLocator,
    val constants: List<String>,
    val destinationIds: List<String>,
)

data class IntegrationOpenApiConverterMethodSnapshot(
    val signature: String,
    val methodName: String,
    val parameterType: String,
    val returnType: String,
)

data class IntegrationOpenApiConverterBeanSnapshot(
    val artifactId: String,
    val moduleId: String,
    val qualifiedName: String,
    val className: String,
    val sourceLocator: SourceLocator,
    val methods: List<IntegrationOpenApiConverterMethodSnapshot>,
    val destinationIds: List<String>,
)

data class IntegrationOrganizationConnectorTemplateSnapshot(
    val catalogId: String,
    val catalogVersion: String,
    val bundleSha256: String,
    val catalogDisplayName: String,
    val template: JmixOrganizationConnectorTemplate,
)

data class IntegrationOAuth2ManagerSnapshot(
    val beanName: String,
    val declaringType: String,
    val moduleId: String,
    val sourceLocator: SourceLocator,
)

data class IntegrationOAuth2ServiceSnapshot(
    val beanName: String,
    val declaringType: String,
    val moduleId: String,
    val sourceLocator: SourceLocator,
)

data class IntegrationConnectorApplyRequest(
    val model: IntegrationConnectorModel,
    val expectedPlanDigest: String,
)

data class IntegrationConnectorCatalogApprovalRequest(
    val binding: org.jmixworkbench.model.IntegrationConnectorCatalogBinding,
    val destinationId: String,
)

data class IntegrationConnectorCatalogApprovalReview(
    val catalogDisplayName: String,
    val templateName: String,
    val provider: String,
    val risk: String,
    val approvalPolicyId: String,
    val destinationModuleId: String,
    val requireMutualTls: Boolean,
    val requireTransactional: Boolean,
    val requireIdempotency: Boolean,
    val requireOutbox: Boolean,
    val requireInbox: Boolean,
)

data class IntegrationConnectorCatalogApprovalResponse(
    val approved: Boolean,
    val approval: IntegrationConnectorCatalogApproval?,
    val message: String?,
)

data class IntegrationOpenApiEvolutionApprovalResponse(
    val approved: Boolean,
    val approval: OpenApiEvolutionApproval?,
    val message: String?,
)

data class IntegrationConnectorProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class OwnedIntegrationConnector(
    val model: IntegrationConnectorModel,
    val javaPath: String,
    val javaContent: String,
    val javaFingerprint: String,
    val policyPath: String,
    val policyContent: String,
    val policyFingerprint: String,
    val migrationPath: String?,
    val migrationContent: String?,
    val supplementalContent: Map<String, String>,
)

package org.jmixworkbench.services

import com.google.gson.Gson
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
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
import org.jmixworkbench.model.IntegrationCapability
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationDiagnosticSeverity
import org.jmixworkbench.model.IntegrationJsonApi
import org.jmixworkbench.model.IntegrationObservabilityApi
import java.util.Base64

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

    fun load(forceRefresh: Boolean = false): IntegrationConnectorWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        val destinations = destinations(graph)
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh)
        return IntegrationConnectorWorkspaceResponse(
            graphDigest = graph.snapshotDigest,
            destinations = destinations,
            defaultDestinationId = destinations.firstOrNull(IntegrationConnectorDestinationSnapshot::recommended)?.id,
            contextArtifacts = graph.artifacts.filter { it.kind in CONTEXT_KINDS },
            oauth2Managers = oauth2Managers(graph),
            dataStores = schema.stores,
            existingDocuments = discoverExisting(destinations),
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

    internal fun propose(model: IntegrationConnectorModel): IntegrationConnectorProposal {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val destination = destinations(graph).firstOrNull { it.id == model.destinationId }
            ?: return rejected(
                "JVW-INTEGRATION-DESTINATION-INVALID",
                "The selected module destination is no longer available. Refresh the workspace.",
            )
        val schema = SchemaWorkspaceService.getInstance(project).load()
        val selectedStore = model.reliability.outbox
            ?.takeIf { model.reliability.outboxEnabled }
            ?.let { outbox -> schema.stores.firstOrNull { it.id == outbox.storeId } }
        val normalized = normalizeBackendContracts(model, destination, selectedStore)
        val validation = IntegrationConnectorGenerator.validate(normalized, destination.capabilities)
        val issues = validation.diagnostics
            .filter { it.severity == IntegrationDiagnosticSeverity.ERROR }
            .map { WorkspaceChangeIssue(it.code, it.message) }
            .toMutableList()
        if (
            normalized.authentication.kind == IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS &&
            oauth2Managers(graph).none { it.beanName == normalized.authentication.authorizedClientManagerBeanName }
        ) {
            issues += WorkspaceChangeIssue(
                "JVW-INTEGRATION-OAUTH-MANAGER-NOT-INDEXED",
                "The selected OAuth2AuthorizedClientManager bean is not present in the current application graph.",
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
        if (normalized.reliability.outboxEnabled) {
            if (selectedStore == null) {
                issues += WorkspaceChangeIssue(
                    "JVW-INTEGRATION-OUTBOX-STORE-NOT-INDEXED",
                    "The selected outbox data store is not present in the current schema index.",
                )
            } else if (selectedStore.moduleId != destination.moduleId) {
                issues += WorkspaceChangeIssue(
                    "JVW-INTEGRATION-OUTBOX-STORE-CROSS-MODULE",
                    "The durable outbox must use a data store owned by the connector's module.",
                )
            } else if (selectedStore.rootChangelogPath == null || selectedStore.generatedDirectory == null) {
                issues += WorkspaceChangeIssue(
                    "JVW-INTEGRATION-OUTBOX-STORE-NOT-MIGRATABLE",
                    "The selected data store has no source-safe Liquibase root and generated migration directory.",
                    selectedStore.rootChangelogPath,
                )
            }
        }
        if (issues.isNotEmpty()) return IntegrationConnectorProposal(null, issues.distinct().sortedBy(WorkspaceChangeIssue::code))

        var stored = normalized.copy(sourceLocator = null)
        var migrationChanges = emptyList<WorkspaceFileChange>()
        if (normalized.sourceLocator == null && stored.reliability.outboxEnabled) {
            val outbox = requireNotNull(stored.reliability.outbox)
            if (outbox.migrationPath == null) {
                val migrationProposal = SchemaWorkspaceService.getInstance(project).migrationProposal(
                    SchemaMigrationChangeRequest(
                        storeId = outbox.storeId,
                        migration = IntegrationConnectorGenerator.outboxMigration(stored),
                        fileName = "jvw-${stored.beanName}-outbox.xml",
                    ),
                )
                val migrationChangeSet = migrationProposal.changeSet
                    ?: return IntegrationConnectorProposal(null, migrationProposal.issues)
                val migrationFile = migrationChangeSet.files.singleOrNull {
                    it.mode == WorkspaceFileChangeMode.CREATE && it.relativePath.endsWith(".xml")
                } ?: return rejected(
                    "JVW-INTEGRATION-OUTBOX-MIGRATION-MISSING",
                    "Schema planning did not produce exactly one durable outbox migration.",
                )
                stored = stored.copy(
                    reliability = stored.reliability.copy(
                        outbox = outbox.copy(migrationPath = migrationFile.relativePath),
                    ),
                )
                migrationChanges = migrationChangeSet.files
            }
        }
        val encoded = IntegrationConnectorGenerator.encode(stored)
        val generated = IntegrationConnectorGenerator.generate(stored, encoded)
        javaSyntaxError("${model.className}.java", generated.javaSource)?.let { syntax ->
            return rejected(
                "JVW-INTEGRATION-GENERATED-SYNTAX",
                "Generated Java is not syntactically valid: ${syntax.errorDescription}",
            )
        }
        if (migrationChanges.isNotEmpty()) {
            val generatedMigration = generated.migrationXml
                ?: return rejected(
                    "JVW-INTEGRATION-OUTBOX-MIGRATION-GENERATION",
                    "The normalized outbox connector did not regenerate its migration.",
                )
            val plannedMigration = migrationChanges.single {
                it.mode == WorkspaceFileChangeMode.CREATE && it.relativePath.endsWith(".xml")
            }
            if (plannedMigration.createContent != generatedMigration) {
                return rejected(
                    "JVW-INTEGRATION-OUTBOX-MIGRATION-DRIFT",
                    "The schema plan and connector-owned migration differ; no files will be written.",
                    plannedMigration.relativePath,
                )
            }
        }
        val javaPath = javaPath(destination, stored)
        val policyPath = policyPath(destination, stored.beanName)
        val changes = if (normalized.sourceLocator == null) {
            migrationChanges + listOf(
                create(javaPath, generated.javaSource),
                create(policyPath, generated.reliabilityProperties),
            )
        } else {
            val owned = loadOwned(normalized.sourceLocator, destination)
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
                owned.model.reliability.orderingRequired != stored.reliability.orderingRequired
            ) {
                return rejected(
                    "JVW-INTEGRATION-OUTBOX-SCHEMA-IMMUTABLE",
                    "Outbox mode, data store, table, migration and ordering shape cannot be changed after creation. Create a replacement connector and migrate callers explicitly.",
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
            listOf(
                modify(owned.javaPath, owned.javaContent, owned.javaFingerprint, generated.javaSource),
                modify(owned.policyPath, owned.policyContent, owned.policyFingerprint, generated.reliabilityProperties),
            )
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
                IntegrationConnectorDestinationSnapshot(
                    id = CanonicalDiscoveryJson.sha256(
                        "${javaRoot.moduleId}\u0000${javaRoot.sourceRoot}\u0000${resourceRoot.sourceRoot}",
                    ).take(24),
                    moduleId = javaRoot.moduleId,
                    sourceRoot = javaRoot.sourceRoot,
                    resourceRoot = resourceRoot.sourceRoot,
                    defaultPackage = "${moduleBasePackage(javaRoot.moduleId, graph, fallbackPackage)}.integration",
                    capabilities = detectCapabilities(javaRoot, graph),
                    jsonApi = detectJsonApi(javaRoot, graph),
                    observabilityApi = detectObservabilityApi(javaRoot, graph),
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
                val regenerated = runCatching {
                    IntegrationConnectorGenerator.generate(model, encoded)
                }.getOrNull()
                val migrationContent = model.reliability.outbox?.migrationPath
                    ?.let { resolver.resolveFile(it)?.file?.let(::fileText) }
                val owned = regenerated != null &&
                    regenerated.javaSource == javaContent &&
                    regenerated.reliabilityProperties == policyContent &&
                    (
                        regenerated.migrationXml == null ||
                            regenerated.migrationXml == migrationContent
                        )
                documents += IntegrationConnectorDocumentSnapshot(
                    locator = locator,
                    model = model.copy(destinationId = destination.id, sourceLocator = locator),
                    editable = owned,
                    issue = if (owned) null
                    else "Manual Java or reliability-policy changes were detected; visual overwrite is disabled.",
                )
            }
        }
        return documents.distinctBy { it.locator.relativePath }.sortedBy { it.locator.relativePath }
    }

    private fun loadOwned(
        locator: SourceLocator,
        destination: IntegrationConnectorDestinationSnapshot,
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
        val generated = runCatching {
            IntegrationConnectorGenerator.generate(model, encoded)
        }.getOrNull() ?: return null
        if (generated.javaSource != javaContent || generated.reliabilityProperties != policyContent) return null
        val migrationPath = model.reliability.outbox?.migrationPath
        val migrationContent = migrationPath?.let { resolver.resolveFile(it)?.file?.let(::fileText) }
        if (generated.migrationXml != null && generated.migrationXml != migrationContent) return null
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
        )
    }

    private fun normalizeBackendContracts(
        model: IntegrationConnectorModel,
        destination: IntegrationConnectorDestinationSnapshot,
        selectedStore: SchemaDataStoreSnapshot?,
    ): IntegrationConnectorModel {
        val reliability = model.reliability
        val outbox = reliability.outbox
        val normalizedReliability = if (reliability.outboxEnabled && outbox != null) {
            reliability.copy(
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
        } else {
            reliability
        }
        return model.copy(
            reliability = normalizedReliability,
            observability = model.observability.copy(runtimeApi = destination.observabilityApi),
        )
    }

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
        gson.fromJson(json, IntegrationConnectorModel::class.java).copy(sourceLocator = null)
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
        private const val MAX_MARKER_LENGTH = 4_000_000
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
            """@Bean(?:\s*\(\s*(?:(?:name|value)\s*=\s*)?["']([^"']+)["'][^)]*\))?[\s\S]{0,320}?\bOAuth2AuthorizedClientManager\s+([A-Za-z_]\w*)\s*\(""",
        )
        private val OAUTH2_MANAGER_KOTLIN = Regex(
            """@Bean(?:\s*\(\s*(?:(?:name|value)\s*=\s*)?["']([^"']+)["'][^)]*\))?[\s\S]{0,320}?\bfun\s+([A-Za-z_]\w*)\s*\([^)]*\)\s*:\s*OAuth2AuthorizedClientManager\b""",
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
    val recommended: Boolean,
)

data class IntegrationConnectorDocumentSnapshot(
    val locator: SourceLocator,
    val model: IntegrationConnectorModel,
    val editable: Boolean,
    val issue: String?,
)

data class IntegrationConnectorWorkspaceResponse(
    val graphDigest: String,
    val destinations: List<IntegrationConnectorDestinationSnapshot>,
    val defaultDestinationId: String?,
    val contextArtifacts: List<ArtifactSnapshot>,
    val oauth2Managers: List<IntegrationOAuth2ManagerSnapshot>,
    val dataStores: List<SchemaDataStoreSnapshot>,
    val existingDocuments: List<IntegrationConnectorDocumentSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

data class IntegrationOAuth2ManagerSnapshot(
    val beanName: String,
    val declaringType: String,
    val moduleId: String,
    val sourceLocator: SourceLocator,
)

data class IntegrationConnectorApplyRequest(
    val model: IntegrationConnectorModel,
    val expectedPlanDigest: String,
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
)

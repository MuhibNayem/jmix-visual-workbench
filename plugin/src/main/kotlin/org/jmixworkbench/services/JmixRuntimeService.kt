package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.flowui.FlowUiDescriptorParser
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.navigation.SourceNavigationPolicy
import org.jmixworkbench.discovery.runtime.JmixRuntimeConfigurationParser
import org.jmixworkbench.discovery.runtime.ParsedRuntimeConfiguration
import org.jmixworkbench.toolwindow.JmixRuntimePreviewToolWindow
import org.jetbrains.jps.model.java.JavaResourceRootType
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.name

@Service(Service.Level.PROJECT)
class JmixRuntimeService(
    private val project: Project,
) {
    fun inspect(request: JmixRuntimeInspectionRequest): JmixRuntimeInspectionResponse {
        val source = loadDescriptor(request.descriptorLocator)
        if (source.content == null || source.path == null) {
            return JmixRuntimeInspectionResponse(false, null, emptyList(), source.issues)
        }
        val parsed = FlowUiDescriptorParser.parse(
            request.descriptorLocator.relativePath,
            source.content,
            source.fingerprint,
        )
        val descriptor = parsed.document
            ?: return JmixRuntimeInspectionResponse(false, null, emptyList(), parsed.issues)
        val graph = cancellableRead {
            ApplicationGraphService.getInstance(project).graph()
        }
        val route = routeForDescriptor(request.descriptorLocator.relativePath, graph)
        val ownerModule = cancellableRead {
            ModuleUtilCore.findModuleForFile(source.virtualFile!!, project)
        }
        val modules = cancellableRead {
            applicationModules(ownerModule, source.path)
        }
        if (modules.isEmpty()) {
            return JmixRuntimeInspectionResponse(
                accepted = false,
                viewId = descriptor.viewId,
                targets = emptyList(),
                issues = listOf(
                    WorkspaceChangeIssue(
                        "JVW-RUNTIME-APPLICATION-MODULE-MISSING",
                        "No runnable Jmix application module could be resolved for this descriptor.",
                        request.descriptorLocator.relativePath,
                    ),
                ),
            )
        }
        val targetInputs = modules
            .flatMap { module ->
                configurationCandidates(module).map { configuration -> module to configuration }
            }
            .take(MAX_RUNTIME_TARGETS)
        val targets = targetInputs.map { (module, configuration) ->
            CompletableFuture.supplyAsync(
                { runtimeTarget(module, configuration, route, ownerModule) },
                AppExecutorUtil.getAppExecutorService(),
            )
        }.mapNotNull { future ->
            runCatching(future::join).getOrNull()
        }.sortedWith(
            compareByDescending<JmixRuntimeTargetSnapshot> { it.preferred }
                .thenByDescending { it.reachable }
                .thenBy { it.profile != "default" }
                .thenBy { it.moduleId },
        )
        return JmixRuntimeInspectionResponse(
            accepted = targets.isNotEmpty(),
            viewId = descriptor.viewId,
            targets = targets,
            issues = if (targets.isEmpty()) {
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-RUNTIME-TARGET-MISSING",
                        "Application modules were found, but no valid local runtime target could be derived.",
                        request.descriptorLocator.relativePath,
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    fun openPreview(request: JmixRuntimeOpenPreviewRequest): JmixRuntimeActionResponse {
        val uri = validatedLoopbackUri(request.url)
            ?: return JmixRuntimeActionResponse(
                false,
                "Runtime preview accepts only credential-free HTTP(S) loopback URLs.",
            )
        JmixRuntimePreviewToolWindow.open(
            project = project,
            url = uri.toASCIIString(),
            title = request.title.take(80).ifBlank { "Jmix view" },
            viewport = request.viewport,
        )
        return JmixRuntimeActionResponse(true, "Opened the real Jmix application in the runtime preview.")
    }

    fun previewHotDeploy(request: JmixFlowUiHotDeployRequest): WorkspaceChangePreviewResponse {
        val proposal = hotDeployProposal(request)
        if (!proposal.accepted || proposal.changeSet == null) {
            return WorkspaceChangePreviewResponse(
                accepted = proposal.accepted,
                changeSetId = "flowui-hot-deploy:${if (proposal.accepted) "no-change" else "rejected"}",
                label = if (proposal.accepted) "FlowUI resource is already staged" else "FlowUI hot deploy rejected",
                planDigest = null,
                files = emptyList(),
                issues = proposal.issues,
            )
        }
        return WorkspaceChangeService.getInstance(project).preview(proposal.changeSet)
    }

    fun prepareHotDeploy(request: JmixFlowUiHotDeployApplyRequest): PreparedWorkspaceChange {
        val proposal = hotDeployProposal(request.change)
        val changeSet = proposal.changeSet
        if (!proposal.accepted || changeSet == null) {
            return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "flowui-hot-deploy:rejected",
                    label = "FlowUI hot deploy rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues.ifEmpty {
                        listOf(
                            WorkspaceChangeIssue(
                                "JVW-RUNTIME-HOT-DEPLOY-NO-CHANGE",
                                "The current descriptor and cache trigger are already staged.",
                                request.change.descriptorLocator.relativePath,
                            ),
                        )
                    },
                ),
                baseDir = null,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    private fun hotDeployProposal(request: JmixFlowUiHotDeployRequest): RuntimeChangeProposal {
        val inspection = inspect(JmixRuntimeInspectionRequest(request.descriptorLocator))
        val target = inspection.targets.firstOrNull { it.id == request.targetId }
            ?: return RuntimeChangeProposal.rejected(
                "JVW-RUNTIME-TARGET-STALE",
                "The selected application target is no longer available. Refresh runtime detection.",
                request.descriptorLocator.relativePath,
            )
        if (!target.reachable) {
            return RuntimeChangeProposal.rejected(
                "JVW-RUNTIME-NOT-REACHABLE",
                "The selected Jmix application is not reachable on ${target.baseUrl}.",
                request.descriptorLocator.relativePath,
            )
        }
        if (!target.hotDeploySupported || target.confDirectory == null || target.tempDirectory == null) {
            return RuntimeChangeProposal.rejected(
                "JVW-RUNTIME-HOT-DEPLOY-DISABLED",
                target.hotDeployMessage
                    ?: "Hot deployment is disabled or its directories cannot be resolved safely inside the project.",
                request.descriptorLocator.relativePath,
            )
        }
        val source = loadDescriptor(request.descriptorLocator)
        val content = source.content
            ?: return RuntimeChangeProposal(false, null, source.issues)
        val resourcePath = resourcePath(
            request.descriptorLocator.relativePath,
            ApplicationGraphService.getInstance(project).graph(),
        )
            ?: return RuntimeChangeProposal.rejected(
                "JVW-RUNTIME-RESOURCE-PATH-UNSUPPORTED",
                "Hot deployment requires a descriptor below an indexed production resource root.",
                request.descriptorLocator.relativePath,
            )
        val projectRoot = projectRoot()
            ?: return RuntimeChangeProposal.rejected(
                "JVW-RUNTIME-PROJECT-MISSING",
                "The project root is unavailable.",
                request.descriptorLocator.relativePath,
            )
        val changes = mutableListOf<WorkspaceFileChange>()
        val confTarget = "${target.confDirectory}/$resourcePath"
        val confTargetPath = projectRoot.resolve(confTarget).normalize()
        if (Files.exists(confTargetPath) &&
            (Files.isSymbolicLink(confTargetPath) ||
                !Files.isRegularFile(confTargetPath) ||
                Files.size(confTargetPath) > MAX_RUNTIME_FILE_BYTES)
        ) {
            return RuntimeChangeProposal.rejected(
                "JVW-RUNTIME-CONF-TARGET-UNSAFE",
                "The hot-deploy resource target is not a reviewed regular file.",
                confTarget,
            )
        }
        fileReplacement(projectRoot, confTarget, content)?.let(changes::add)
        VERIFIED_FLOWUI_TRIGGERS.forEach { trigger ->
            val triggerPath = "${target.tempDirectory}/triggers/$trigger"
            if (Files.exists(projectRoot.resolve(triggerPath))) {
                return RuntimeChangeProposal.rejected(
                    "JVW-RUNTIME-TRIGGER-PENDING",
                    "A previous Jmix cache-reset trigger is still pending. Wait for the running app to consume it, then retry.",
                    triggerPath,
                )
            }
            changes += WorkspaceFileChange(
                relativePath = triggerPath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = "",
            )
        }
        if (changes.isEmpty()) return RuntimeChangeProposal(true, null, emptyList())
        val identity = buildString {
            append(request.descriptorLocator.relativePath).append('\u0000')
            append(request.descriptorLocator.revisionFingerprint).append('\u0000')
            append(target.id).append('\u0000')
            changes.forEach { append(it.relativePath).append('\u0000') }
        }
        return RuntimeChangeProposal(
            accepted = true,
            changeSet = WorkspaceChangeSet(
                id = "flowui-hot-deploy:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Stage ${inspection.viewId ?: "FlowUI view"} for verified Jmix hot deployment",
                files = changes,
            ),
            issues = emptyList(),
        )
    }

    private fun fileReplacement(projectRoot: Path, relativePath: String, content: String): WorkspaceFileChange? {
        val target = projectRoot.resolve(relativePath).normalize()
        if (!target.startsWith(projectRoot) || containsSymlink(projectRoot, target)) return null
        if (!Files.exists(target)) {
            return WorkspaceFileChange(
                relativePath = relativePath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = content,
            )
        }
        if (!Files.isRegularFile(target) || Files.size(target) > MAX_RUNTIME_FILE_BYTES) return null
        val current = Files.readString(target)
        if (current == content) return null
        return WorkspaceFileChange(
            relativePath = relativePath,
            mode = WorkspaceFileChangeMode.MODIFY,
            baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(current),
            edits = listOf(WorkspaceTextEdit(0, current.length, current, content)),
        )
    }

    private fun runtimeTarget(
        module: RuntimeModule,
        candidate: RuntimeConfigurationCandidate,
        route: String?,
        ownerModule: Module?,
    ): JmixRuntimeTargetSnapshot {
        val warnings = mutableListOf<String>()
        val portValue = candidate.configuration.resolved("server.port")
        if (portValue.unresolvedPlaceholders.isNotEmpty()) {
            warnings += "server.port depends on ${portValue.unresolvedPlaceholders.sorted().joinToString()}."
        }
        val port = portValue.value?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_SERVER_PORT
        if (portValue.value != null && portValue.value.toIntOrNull() !in 1..65535) {
            warnings += "Invalid server.port '${portValue.value}'; probing $DEFAULT_SERVER_PORT."
        }
        val ssl = candidate.configuration.boolean("server.ssl.enabled", false).value == "true"
        val contextPath = JmixRuntimeConfigurationParser.normalizeContextPath(
            candidate.configuration.resolved("server.servlet.context-path").value,
        )
        val scheme = if (ssl) "https" else "http"
        val baseUrl = URI(scheme, null, LOOPBACK_ADDRESS, port, contextPath.ifBlank { "/" }, null, null)
            .toASCIIString()
            .removeSuffix("/")
        val routeRequiresParameters = route?.let { ':' in it || '{' in it } == true
        if (routeRequiresParameters) {
            warnings += "The view route requires parameters; preview opens the application root."
        }
        val previewPath = if (routeRequiresParameters) contextPath else joinUrlPath(contextPath, route)
        val previewUrl = URI(scheme, null, LOOPBACK_ADDRESS, port, previewPath.ifBlank { "/" }, null, null)
            .toASCIIString()
        val probe = probe(baseUrl.ifBlank { "$scheme://$LOOPBACK_ADDRESS:$port" })
        val directories = hotDeployDirectories(module, candidate.configuration)
        warnings += directories.warnings
        val unsafeEnabled = candidate.configuration.boolean(
            "jmix.core.unsafe-runtime-features-enabled",
            true,
        ).value == "true"
        val hotDeployEnabled = candidate.configuration.boolean(
            "jmix.core.hot-deploy-enabled",
            true,
        ).value == "true"
        val triggersEnabled = candidate.configuration.boolean(
            "jmix.core.trigger-files-enabled",
            true,
        ).value == "true"
        val hotDeploySupported = unsafeEnabled && hotDeployEnabled && triggersEnabled &&
            directories.confDirectory != null && directories.tempDirectory != null
        val disabledReasons = buildList {
            if (!unsafeEnabled) add("jmix.core.unsafe-runtime-features-enabled=false")
            if (!hotDeployEnabled) add("jmix.core.hot-deploy-enabled=false")
            if (!triggersEnabled) add("jmix.core.trigger-files-enabled=false")
            if (directories.confDirectory == null || directories.tempDirectory == null) {
                add("runtime directories are outside the open project or unresolved")
            }
        }
        val identity = "${module.relativeRoot}\u0000${candidate.profile}\u0000$baseUrl"
        return JmixRuntimeTargetSnapshot(
            id = CanonicalDiscoveryJson.sha256(identity).take(24),
            moduleId = module.moduleId,
            moduleRoot = module.relativeRoot,
            profile = candidate.profile,
            preferred = module.module == ownerModule || cancellableRead {
                moduleDependsOn(module.module, ownerModule)
            },
            baseUrl = baseUrl,
            previewUrl = previewUrl,
            routePath = route,
            routeRequiresParameters = routeRequiresParameters,
            reachable = probe.reachable,
            httpStatus = probe.httpStatus,
            responseTimeMillis = probe.responseTimeMillis,
            configSources = candidate.sources,
            hotDeploySupported = hotDeploySupported,
            hotDeployMessage = disabledReasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
            confDirectory = directories.confDirectory,
            tempDirectory = directories.tempDirectory,
            warnings = warnings,
        )
    }

    private fun applicationModules(ownerModule: Module?, descriptorPath: Path): List<RuntimeModule> {
        val projectRoot = projectRoot() ?: return emptyList()
        val resolver = ProjectFileResolver.getInstance(project)
        val imported = ModuleManager.getInstance(project).modules.flatMap { module ->
            ModuleRootManager.getInstance(module).contentRoots.mapNotNull { root ->
                val path = Path.of(root.path).normalize()
                val relativeRoot = resolver.locatorPath(root, module)?.ifBlank { "." }
                    ?: return@mapNotNull null
                val resources = ModuleRootManager.getInstance(module)
                    .getSourceRoots(JavaResourceRootType.RESOURCE)
                    .asSequence()
                    .map { Path.of(it.path).normalize() }
                    .filter { it.startsWith(path) }
                    .toList()
                    .ifEmpty { listOf(path.resolve("src/main/resources")) }
                RuntimeModule(module.name, module, path, relativeRoot, resources)
            }
        }.distinctBy { "${it.module.name}\u0000${it.root}" }
        val recovered = ApplicationGraphService.getInstance(project).graph().modules
            .asSequence()
            .mapNotNull { coverage ->
                val path = if (coverage.moduleRoot.isBlank()) {
                    projectRoot
                } else {
                    resolver.resolveFile(coverage.moduleRoot)
                        ?.file
                        ?.takeIf { it.isDirectory }
                        ?.path
                        ?.let(Path::of)
                        ?.normalize()
                        ?: return@mapNotNull null
                }
                val importedOwner = imported.asSequence()
                    .filter { path.startsWith(it.root) }
                    .maxByOrNull { it.root.nameCount }
                    ?: return@mapNotNull null
                val relativeRoot = if (path.startsWith(projectRoot)) {
                    projectRoot.relativize(path).invariantPath().ifBlank { "." }
                } else {
                    coverage.moduleRoot
                }
                val resources = coverage.sourceRoots.asSequence()
                    .filter {
                        it.kind == ApplicationGraphSourceRootKind.RESOURCES &&
                            it.sourceSetId.isProductionRuntimeSourceSet()
                    }
                    .mapNotNull {
                        resolver.resolveFile(it.relativePath)?.file
                            ?.takeIf { file -> file.isDirectory }
                            ?.path
                            ?.let(Path::of)
                            ?.normalize()
                    }
                    .toList()
                    .ifEmpty { listOf(path.resolve("src/main/resources")) }
                RuntimeModule(
                    coverage.moduleId,
                    importedOwner.module,
                    path,
                    relativeRoot,
                    resources,
                )
            }
            .toList()
        val candidates = (imported + recovered)
            .distinctBy { "${it.moduleId}\u0000${it.root}" }
        val applications = candidates.filter(::looksLikeApplicationModule)
        if (applications.isNotEmpty()) return applications
        val ownerRoots = candidates.filter { it.module == ownerModule || descriptorPath.startsWith(it.root) }
        return ownerRoots.ifEmpty { candidates.take(1) }
    }

    private fun looksLikeApplicationModule(module: RuntimeModule): Boolean {
        val buildText = listOf("build.gradle.kts", "build.gradle")
            .asSequence()
            .map(module.root::resolve)
            .filter(Files::isRegularFile)
            .mapNotNull(::readBounded)
            .firstOrNull()
            .orEmpty()
        val hasRuntimeConfig = module.resourceRoots.any { resourceRoot ->
            listOf(resourceRoot, resourceRoot.resolve("config")).any { configurationRoot ->
                CONFIG_EXTENSIONS.any { extension ->
                    Files.isRegularFile(configurationRoot.resolve("application.$extension"))
                }
            }
        }
        return hasRuntimeConfig && ("io.jmix" in buildText || "jmix" in buildText.lowercase())
    }

    private fun configurationCandidates(module: RuntimeModule): List<RuntimeConfigurationCandidate> {
        val baseFiles = mutableListOf<Path>()
        val profileFiles = linkedMapOf<String, MutableList<Path>>()
        module.resourceRoots
            .flatMap { root -> listOf(root, root.resolve("config")) }
            .distinct()
            .forEach { root ->
            if (!Files.isDirectory(root)) return@forEach
            listedConfigFiles(root, "application.*").forEach(baseFiles::add)
            listedConfigFiles(root, "application-*.*").forEach { file ->
                val profile = file.name.substringAfter("application-").substringBeforeLast('.')
                if (profile.isNotBlank()) {
                    profileFiles.getOrPut(profile) { mutableListOf() }.add(file)
                }
            }
        }
        val baseDocuments = baseFiles.flatMap(::parseConfigDocuments)
        val base = JmixRuntimeConfigurationParser.merge(
            baseDocuments.filterNot(RuntimeConfigurationDocument::profileScoped)
                .map(RuntimeConfigurationDocument::configuration),
        )
        val embeddedProfiles = linkedMapOf<String, MutableList<ParsedRuntimeConfiguration>>()
        baseDocuments.filter(RuntimeConfigurationDocument::profileScoped).forEach { document ->
            document.profiles.forEach { profile ->
                embeddedProfiles.getOrPut(profile) { mutableListOf() }.add(document.configuration)
            }
        }
        val projectRoot = projectRoot()
        fun sourceNames(files: List<Path>) = files.mapNotNull { path ->
            projectRoot?.takeIf(path::startsWith)?.relativize(path)?.invariantPath()
        }
        val result = mutableListOf(
            RuntimeConfigurationCandidate("default", base, sourceNames(baseFiles)),
        )
        (profileFiles.keys + embeddedProfiles.keys).sorted().forEach { profile ->
            val files = profileFiles[profile].orEmpty()
            val fileConfigurations = files
                .flatMap(::parseConfigDocuments)
                .filter { !it.profileScoped || profile in it.profiles }
                .map(RuntimeConfigurationDocument::configuration)
            result += RuntimeConfigurationCandidate(
                profile = profile,
                configuration = JmixRuntimeConfigurationParser.merge(
                    listOf(base) + embeddedProfiles[profile].orEmpty() + fileConfigurations,
                ),
                sources = sourceNames(baseFiles + files),
            )
        }
        return result.distinctBy { "${it.profile}\u0000${it.configuration.values}" }
    }

    private fun parseConfigDocuments(path: Path): List<RuntimeConfigurationDocument> {
        val content = readBounded(path) ?: return emptyList()
        return when (path.name.substringAfterLast('.').lowercase()) {
            "properties" -> listOf(
                RuntimeConfigurationDocument(
                    emptySet(),
                    false,
                    JmixRuntimeConfigurationParser.parseProperties(content),
                ),
            )
            "yml", "yaml" -> JmixRuntimeConfigurationParser.parseYamlDocuments(content).map {
                RuntimeConfigurationDocument(it.profiles, it.profileScoped, it.configuration)
            }
            else -> emptyList()
        }
    }

    private fun supportedConfigFile(path: Path): Boolean =
        Files.isRegularFile(path) &&
            path.name.substringAfterLast('.', "").lowercase() in CONFIG_EXTENSIONS &&
            runCatching { Files.size(path) <= MAX_RUNTIME_FILE_BYTES }.getOrDefault(false)

    private fun listedConfigFiles(root: Path, glob: String): List<Path> =
        runCatching {
            Files.newDirectoryStream(root, glob).use { stream ->
                stream.filter(::supportedConfigFile).sortedBy(Path::name).toList()
            }
        }.getOrDefault(emptyList())

    private fun hotDeployDirectories(
        module: RuntimeModule,
        configuration: ParsedRuntimeConfiguration,
    ): RuntimeDirectories {
        val warnings = mutableListOf<String>()
        val conf = resolveRuntimeDirectory(
            module,
            configuration.values["jmix.core.conf-dir"],
            ".jmix/conf",
        ) ?: run {
            warnings += "jmix.core.conf-dir could not be resolved safely inside the open project."
            null
        }
        val temp = resolveRuntimeDirectory(
            module,
            configuration.values["jmix.core.temp-dir"],
            ".jmix/temp",
        ) ?: run {
            warnings += "jmix.core.temp-dir could not be resolved safely inside the open project."
            null
        }
        return RuntimeDirectories(conf, temp, warnings)
    }

    private fun resolveRuntimeDirectory(module: RuntimeModule, raw: String?, fallback: String): String? {
        val projectRoot = projectRoot() ?: return null
        val withUserDirectory = (raw ?: fallback).replace("\${user.dir}", module.root.toString())
        val resolved = JmixRuntimeConfigurationParser.resolve(withUserDirectory)
        val value = resolved.value ?: return null
        val path = runCatching { Path.of(value) }.getOrNull() ?: return null
        val absolute = (if (path.isAbsolute) path else module.root.resolve(path)).normalize()
        if (!absolute.startsWith(projectRoot) || containsSymlink(projectRoot, absolute)) return null
        return projectRoot.relativize(absolute).invariantPath()
    }

    private fun routeForDescriptor(relativePath: String, graph: ApplicationGraphResponse): String? {
        val descriptor = graph.artifacts.firstOrNull {
            it.kind == ArtifactKind.VIEW_DESCRIPTOR && it.sourceLocator.relativePath == relativePath
        } ?: return null
        val controllerIds = graph.relationships.asSequence()
            .filter {
                it.type == RelationshipType.CONTROLS &&
                    it.targetArtifactId == descriptor.id
            }
            .map { it.sourceArtifactId }
            .toSet()
        val routeId = graph.relationships.firstOrNull {
            it.type == RelationshipType.ROUTED_AS &&
                it.sourceArtifactId in controllerIds
        }?.targetArtifactId
        return graph.artifacts.firstOrNull { it.id == routeId && it.kind == ArtifactKind.VIEW_ROUTE }
            ?.displayName
            ?.takeUnless { it == "/" }
    }

    private fun moduleDependsOn(module: Module, dependency: Module?): Boolean {
        if (dependency == null) return false
        val visited = mutableSetOf<Module>()
        fun visit(current: Module): Boolean {
            if (!visited.add(current)) return false
            return ModuleRootManager.getInstance(current).dependencies.any {
                it == dependency || visit(it)
            }
        }
        return visit(module)
    }

    private fun loadDescriptor(locator: SourceLocator): LoadedRuntimeSource {
        val validation = SourceNavigationPolicy.validate(
            locator.relativePath,
            locator.line,
            locator.column,
            locator.revisionFingerprint,
        )
        if (!validation.accepted) {
            return LoadedRuntimeSource.rejected(
                validation.errorCode ?: "JVW-RUNTIME-SOURCE-REJECTED",
                validation.message,
                locator.relativePath,
            )
        }
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath)
            ?: return LoadedRuntimeSource.rejected(
                "JVW-RUNTIME-SOURCE-MISSING",
                "The FlowUI descriptor no longer exists inside a registered project content root.",
                locator.relativePath,
            )
        val virtualFile = resolved.file
        val path = virtualFile.toNioPath().toAbsolutePath().normalize()
        val contentRoot = resolved.root.toNioPath().toAbsolutePath().normalize()
        if (
            !path.startsWith(contentRoot) ||
            containsSymlink(contentRoot, path) ||
            !Files.isRegularFile(path)
        ) {
            return LoadedRuntimeSource.rejected(
                "JVW-RUNTIME-SOURCE-MISSING",
                "The FlowUI descriptor no longer exists inside its registered project content root.",
                locator.relativePath,
            )
        }
        if (Files.size(path) > MAX_RUNTIME_FILE_BYTES) {
            return LoadedRuntimeSource.rejected(
                "JVW-RUNTIME-SOURCE-TOO-LARGE",
                "The FlowUI descriptor exceeds the reviewed runtime integration limit.",
                locator.relativePath,
            )
        }
        val content = Files.readString(path)
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (!SourceNavigationPolicy.revisionMatches(locator, fingerprint)) {
            return LoadedRuntimeSource.rejected(
                "JVW-RUNTIME-SOURCE-STALE",
                "The descriptor changed after runtime inspection. Refresh before previewing or deploying.",
                locator.relativePath,
            )
        }
        return LoadedRuntimeSource(content, fingerprint, path, virtualFile, emptyList())
    }

    private fun validatedLoopbackUri(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val loopback = host == "localhost" || host == "::1" || host.startsWith("127.")
        val port = uri.port
        return uri.takeIf {
            it.scheme in setOf("http", "https") &&
                loopback &&
                it.userInfo == null &&
                it.fragment == null &&
                (port == -1 || port in 1..65535)
        }
    }

    private fun probe(baseUrl: String): RuntimeProbe {
        val started = System.nanoTime()
        return try {
            val connection = URI(baseUrl).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = PROBE_TIMEOUT_MILLIS
            connection.readTimeout = PROBE_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("User-Agent", "Jmix-Visual-Workbench/runtime-probe")
            val code = connection.responseCode
            runCatching { connection.inputStream?.close() }
            runCatching { connection.errorStream?.close() }
            connection.disconnect()
            RuntimeProbe(true, code, elapsedMillis(started))
        } catch (_: Exception) {
            RuntimeProbe(false, null, elapsedMillis(started))
        }
    }

    private fun readBounded(path: Path): String? =
        runCatching {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_RUNTIME_FILE_BYTES) null
            else Files.readString(path)
        }.getOrNull()

    private fun projectRoot(): Path? =
        project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()

    private fun containsSymlink(projectRoot: Path, target: Path): Boolean {
        if (!target.startsWith(projectRoot)) return true
        var cursor = projectRoot
        projectRoot.relativize(target).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor) && Files.isSymbolicLink(cursor)) return true
        }
        return false
    }

    private fun resourcePath(
        relativePath: String,
        graph: ApplicationGraphResponse,
    ): String? {
        graph.modules.asSequence()
            .flatMap(ApplicationGraphModuleCoverage::sourceRoots)
            .filter {
                it.kind == ApplicationGraphSourceRootKind.RESOURCES &&
                    it.sourceSetId.isProductionRuntimeSourceSet()
            }
            .sortedByDescending { it.relativePath.length }
            .firstNotNullOfOrNull { root ->
                val prefix = root.relativePath.trimEnd('/')
                relativePath.removePrefix("$prefix/")
                    .takeIf { it != relativePath && it.isNotBlank() }
            }
            ?.let { return it }
        val marker = "/src/main/resources/"
        val normalized = "/$relativePath"
        return normalized.substringAfter(marker, "").takeIf(String::isNotBlank)
    }

    private fun joinUrlPath(contextPath: String, route: String?): String {
        val routePath = route?.trim('/').orEmpty()
        return listOf(contextPath.trim('/'), routePath)
            .filter(String::isNotBlank)
            .joinToString("/", prefix = "/")
            .ifBlank { "/" }
    }

    private fun String.isProductionRuntimeSourceSet(): Boolean =
        !contains("test", ignoreCase = true) &&
            !contains("fixture", ignoreCase = true) &&
            !contains("benchmark", ignoreCase = true)

    private fun Path.invariantPath(): String = toString().replace('\\', '/')

    private fun elapsedMillis(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)

    companion object {
        private const val DEFAULT_SERVER_PORT = 8080
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val PROBE_TIMEOUT_MILLIS = 900
        private const val MAX_RUNTIME_TARGETS = 32
        private const val MAX_RUNTIME_FILE_BYTES = 2L * 1024 * 1024
        private val CONFIG_EXTENSIONS = setOf("properties", "yml", "yaml")
        private val VERIFIED_FLOWUI_TRIGGERS = listOf(
            "io.jmix.flowui.view.ViewRegistry#reset",
        )

        fun getInstance(project: Project): JmixRuntimeService =
            project.getService(JmixRuntimeService::class.java)
    }
}

data class JmixRuntimeInspectionRequest(
    val descriptorLocator: SourceLocator,
)

data class JmixRuntimeInspectionResponse(
    val accepted: Boolean,
    val viewId: String?,
    val targets: List<JmixRuntimeTargetSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

data class JmixRuntimeTargetSnapshot(
    val id: String,
    val moduleId: String,
    val moduleRoot: String,
    val profile: String,
    val preferred: Boolean,
    val baseUrl: String,
    val previewUrl: String,
    val routePath: String?,
    val routeRequiresParameters: Boolean,
    val reachable: Boolean,
    val httpStatus: Int?,
    val responseTimeMillis: Long,
    val configSources: List<String>,
    val hotDeploySupported: Boolean,
    val hotDeployMessage: String?,
    val confDirectory: String?,
    val tempDirectory: String?,
    val warnings: List<String>,
)

enum class JmixRuntimeViewport {
    DESKTOP,
    TABLET,
    MOBILE,
}

data class JmixRuntimeOpenPreviewRequest(
    val url: String,
    val title: String,
    val viewport: JmixRuntimeViewport = JmixRuntimeViewport.DESKTOP,
)

data class JmixRuntimeActionResponse(
    val success: Boolean,
    val message: String,
)

data class JmixFlowUiHotDeployRequest(
    val descriptorLocator: SourceLocator,
    val targetId: String,
)

data class JmixFlowUiHotDeployApplyRequest(
    val change: JmixFlowUiHotDeployRequest,
    val expectedPlanDigest: String,
)

private data class RuntimeModule(
    val moduleId: String,
    val module: Module,
    val root: Path,
    val relativeRoot: String,
    val resourceRoots: List<Path>,
)

private data class RuntimeConfigurationCandidate(
    val profile: String,
    val configuration: ParsedRuntimeConfiguration,
    val sources: List<String>,
)

private data class RuntimeConfigurationDocument(
    val profiles: Set<String>,
    val profileScoped: Boolean,
    val configuration: ParsedRuntimeConfiguration,
)

private data class RuntimeDirectories(
    val confDirectory: String?,
    val tempDirectory: String?,
    val warnings: List<String>,
)

private data class RuntimeProbe(
    val reachable: Boolean,
    val httpStatus: Int?,
    val responseTimeMillis: Long,
)

private data class RuntimeChangeProposal(
    val accepted: Boolean,
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
) {
    companion object {
        fun rejected(code: String, message: String, path: String) =
            RuntimeChangeProposal(false, null, listOf(WorkspaceChangeIssue(code, message, path)))
    }
}

private data class LoadedRuntimeSource(
    val content: String?,
    val fingerprint: String,
    val path: Path?,
    val virtualFile: com.intellij.openapi.vfs.VirtualFile?,
    val issues: List<WorkspaceChangeIssue>,
) {
    companion object {
        fun rejected(code: String, message: String, path: String) =
            LoadedRuntimeSource(null, "", null, null, listOf(WorkspaceChangeIssue(code, message, path)))
    }
}

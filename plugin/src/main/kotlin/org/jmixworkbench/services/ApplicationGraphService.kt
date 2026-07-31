package org.jmixworkbench.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactOrigin
import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.ArtifactRelationship
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.DiagnosticCategory
import org.jmixworkbench.discovery.model.DiagnosticSeverity
import org.jmixworkbench.discovery.model.DiscoveryDiagnostic
import org.jmixworkbench.discovery.model.SourceLanguage
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.discovery.navigation.SourceNavigationPolicy
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexInput
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexResult
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexer
import org.jmixworkbench.discovery.semantic.GraphSourceFile
import org.jetbrains.jps.model.java.JavaResourceRootType
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class ApplicationGraphService(
    private val project: Project,
) : Disposable {
    private val cached = AtomicReference<CachedGraph?>()
    private val cachedSources = AtomicReference<Map<String, CachedSource>>(emptyMap())

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::affectsApplicationGraph)) {
                        cached.set(null)
                    }
                }
            },
        )
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    cached.set(null)
                }
            },
        )
    }

    fun graph(forceRefresh: Boolean = false): ApplicationGraphResponse {
        val startedAt = System.nanoTime()
        val inventory = collectCandidates()
        val previous = cached.get()
        if (!forceRefresh &&
            previous != null &&
            previous.stamps == inventory.stamps &&
            previous.rootKeys == inventory.rootKeys &&
            previous.sourceRootKeys == inventory.sourceRootKeys
        ) {
            return previous.response.copy(
                cacheHit = true,
                reusedFiles = previous.response.scannedFiles,
                changedFiles = 0,
                durationMillis = elapsedMillis(startedAt),
            )
        }

        val previousSources = cachedSources.get()
        val nextSources = linkedMapOf<String, CachedSource>()
        var reusedFiles = 0
        var changedFiles = 0
        var cachedSourceBytes = 0L
        var unreadableFiles = 0
        var syntaxErrorFiles = 0
        var parserUnavailableFiles = 0
        val syntaxDiagnostics = mutableListOf<DiscoveryDiagnostic>()
        val sources = inventory.candidates.mapNotNull { candidate ->
            ProgressManager.checkCanceled()
            previousSources[candidate.stamp.relativePath]
                ?.takeIf { it.stamp == candidate.stamp }
                ?.let { cachedSource ->
                    if (cachedSourceBytes + candidate.stamp.length <= MAX_CACHED_SOURCE_BYTES) {
                        nextSources[candidate.stamp.relativePath] = cachedSource
                        cachedSourceBytes += candidate.stamp.length
                    }
                    reusedFiles += 1
                    return@mapNotNull cachedSource.source
                }
            val content = runCatching {
                ProjectSourceText.read(candidate.file)
            }.getOrNull()
            if (content == null) {
                unreadableFiles += 1
                return@mapNotNull null
            }
            val fingerprint = CanonicalDiscoveryJson.sha256(content)
            var parserAvailable = true
            when (val inspection = inspectJvmSyntax(candidate.file, candidate.stamp.language)) {
                is JvmSyntaxInspection.Error -> {
                    syntaxErrorFiles += 1
                    changedFiles += 1
                    if (syntaxDiagnostics.size < MAX_PARSE_DIAGNOSTICS) {
                        syntaxDiagnostics += sourceDiagnostic(
                            reasonCode = "JVW-INDEX-JVM-SYNTAX-ERROR",
                            severity = DiagnosticSeverity.ERROR,
                            message = "${candidate.stamp.language.name.lowercase().replaceFirstChar(Char::uppercase)} source contains a syntax error: ${inspection.description}",
                            nextStep = "Correct the source syntax and refresh the application map.",
                            relativePath = candidate.stamp.relativePath,
                            fingerprint = fingerprint,
                            line = inspection.line,
                            column = inspection.column,
                        )
                    }
                    return@mapNotNull null
                }

                JvmSyntaxInspection.ParserUnavailable -> {
                    parserAvailable = false
                    parserUnavailableFiles += 1
                    if (syntaxDiagnostics.size < MAX_PARSE_DIAGNOSTICS) {
                        syntaxDiagnostics += sourceDiagnostic(
                            reasonCode = "JVW-INDEX-JVM-PARSER-UNAVAILABLE",
                            severity = DiagnosticSeverity.ERROR,
                            message = "No IntelliJ parser is available for ${candidate.stamp.language.name.lowercase()} source.",
                            nextStep = "Enable the matching IntelliJ language support and refresh the imported project.",
                            relativePath = candidate.stamp.relativePath,
                            fingerprint = fingerprint,
                        )
                    }
                }

                JvmSyntaxInspection.NotApplicable,
                JvmSyntaxInspection.Valid,
                -> Unit
            }
            val source = GraphSourceFile(
                relativePath = candidate.stamp.relativePath,
                content = content,
                owner = ArtifactOwner(
                    buildId = candidate.stamp.buildId,
                    moduleId = candidate.stamp.moduleId,
                    sourceSetId = candidate.stamp.sourceSetId,
                ),
                language = candidate.stamp.language,
                origin = if (candidate.stamp.language in RESOURCE_LANGUAGES) {
                    ArtifactOrigin.RESOURCE
                } else {
                    ArtifactOrigin.SOURCE
                },
                fingerprint = fingerprint,
            )
            if (parserAvailable && cachedSourceBytes + candidate.stamp.length <= MAX_CACHED_SOURCE_BYTES) {
                nextSources[candidate.stamp.relativePath] = CachedSource(candidate.stamp, source)
                cachedSourceBytes += candidate.stamp.length
            }
            changedFiles += 1
            source
        }
        cachedSources.set(nextSources)

        val graph = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                files = sources,
                maxFiles = MAX_FILES,
                maxFileBytes = MAX_FILE_BYTES,
                maxArtifacts = MAX_ARTIFACTS,
            ),
        )
        val semanticallyUnparsedFiles = graph.diagnostics
            .asSequence()
            .filter {
                it.reasonCode == "P2_XML_MALFORMED" ||
                    it.reasonCode == "P2_YAML_PARTIAL" ||
                    it.reasonCode == "P2_LIQUIBASE_SQL_MALFORMED"
            }
            .mapNotNull { it.sourceLocator?.relativePath }
            .distinct()
            .count()
        val parseErrorFiles = syntaxErrorFiles + semanticallyUnparsedFiles
        val moduleCoverage = buildModuleCoverage(inventory, sources)
        val limitReached = inventory.totalCandidateFiles > MAX_FILES ||
            inventory.sourceRootDiscoveryLimitReached ||
            graph.diagnostics.any { it.reasonCode == "P2_GRAPH_FILE_LIMIT" || it.reasonCode == "P2_GRAPH_ARTIFACT_LIMIT" }
        val response = graph.toResponse(
            scannedFiles = sources.size,
            candidateFiles = inventory.totalCandidateFiles,
            excludedFiles = inventory.excludedFiles,
            excludedBytes = inventory.excludedBytes,
            unreadableFiles = unreadableFiles,
            parseErrorFiles = parseErrorFiles,
            parserUnavailableFiles = parserUnavailableFiles,
            hostDiagnostics = syntaxDiagnostics,
            reusedFiles = reusedFiles,
            changedFiles = changedFiles,
            cacheHit = false,
            durationMillis = elapsedMillis(startedAt),
            modules = moduleCoverage,
            moduleAnchors = inventory.moduleAnchors,
            contentRootCount = inventory.rootKeys.size,
            sourceRootCount = inventory.sourceRootKeys.size,
            fallbackContentRootCount = inventory.fallbackRootCountByModule.values.sum(),
            limitReached = limitReached,
            overlappingOwnershipFileCount = inventory.overlappingOwnershipFileCount,
            ambiguousOwnershipFileCount = inventory.ambiguousOwnershipFileCount,
            ambiguousOwnershipPaths = inventory.ambiguousOwnershipPaths,
            moduleDependencies = inventory.moduleDependencies,
        )
        cached.set(CachedGraph(inventory.stamps, inventory.rootKeys, inventory.sourceRootKeys, response))
        return response
    }

    fun invalidate() {
        cached.set(null)
    }

    override fun dispose() {
        cached.set(null)
        cachedSources.set(emptyMap())
    }

    fun prepareNavigation(request: SourceNavigationRequest): PreparedSourceNavigation {
        val validation = SourceNavigationPolicy.validate(
            relativePath = request.relativePath,
            line = request.line,
            column = request.column,
            revisionFingerprint = request.revisionFingerprint,
        )
        val locator = validation.locator
        if (!validation.accepted || locator == null) {
            return PreparedSourceNavigation.failure(
                validation.errorCode ?: "JVW-NAVIGATION-PATH-REJECTED",
                validation.message,
            )
        }
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath)
            ?: return PreparedSourceNavigation.failure(
                "JVW-NAVIGATION-PROJECT-MISSING",
                "The indexed project content root is unavailable.",
            )
        val file = resolved.file
        if (file.isDirectory || !VfsUtilCore.isAncestor(resolved.root, file, false)) {
            return PreparedSourceNavigation.failure(
                "JVW-NAVIGATION-PATH-REJECTED",
                "The requested source is outside the registered project content roots.",
            )
        }
        val currentFingerprint = runCatching {
            CanonicalDiscoveryJson.sha256(ProjectSourceText.read(file))
        }.getOrNull() ?: return PreparedSourceNavigation.failure(
            "JVW-NAVIGATION-SOURCE-UNREADABLE",
            "The indexed source file can no longer be read.",
        )
        if (!SourceNavigationPolicy.revisionMatches(locator, currentFingerprint)) {
            return PreparedSourceNavigation.failure(
                "JVW-NAVIGATION-STALE",
                "The source changed after this graph snapshot. Refresh the application map.",
            )
        }
        return PreparedSourceNavigation(
            success = true,
            errorCode = null,
            message = "Source location verified.",
            file = file,
            zeroBasedLine = (locator.line ?: 1) - 1,
            zeroBasedColumn = (locator.column ?: 1) - 1,
        )
    }

    private fun collectCandidates(): CandidateInventory {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val resolver = ProjectFileResolver.getInstance(project)
        val candidatesByPath = linkedMapOf<String, CandidateFile>()
        val rootKeys = linkedSetOf<String>()
        val sourceRootKeys = linkedSetOf<String>()
        val sourceRootCountByModule = linkedMapOf<String, Int>()
        val discoveredSourceRootCountByModule = linkedMapOf<String, Int>()
        val fallbackRootCountByModule = linkedMapOf<String, Int>()
        val buildIdsByModule = linkedMapOf<String, MutableSet<String>>()
        val moduleAnchors = linkedMapOf<String, ModuleAnchor>()
        val visitedContentRoots = linkedSetOf<String>()
        val visitedDiscoveredRoots = linkedSetOf<String>()
        val overlappingOwnershipPaths = linkedSetOf<String>()
        val ambiguousOwnershipPaths = linkedSetOf<String>()
        var excludedFiles = 0
        var excludedBytes = 0L
        var sourceRootDiscoveryLimitReached = false

        val modules = ModuleManager.getInstance(project).modules.sortedBy(Module::getName)
        val configuredSourceRootPaths = modules
            .flatMap { module -> ModuleRootManager.getInstance(module).sourceRoots.asList() }
            .map(VirtualFile::getPath)
            .toSet()
        val importedContentRootOwners = modules
            .flatMap { imported ->
                ModuleRootManager.getInstance(imported).contentRoots.map { root -> root.path to imported }
            }
            .groupBy({ it.first }, { it.second })

        modules.forEach { module ->
            ProgressManager.checkCanceled()
            val rootManager = ModuleRootManager.getInstance(module)
            val contentRoots = rootManager.contentRoots.sortedBy(VirtualFile::getPath)
            contentRoots.forEach { contentRoot ->
                val relativeContentRoot = resolver.locatorPath(contentRoot, module)
                    ?: return@forEach
                rootKeys += "${module.name}\u0000$relativeContentRoot"
            }
            val explicitSourceRoots = rootManager.sourceRoots
                .asSequence()
                .filter { sourceRoot ->
                    contentRoots.any { contentRoot -> VfsUtilCore.isAncestor(contentRoot, sourceRoot, false) }
                }
                .distinctBy(VirtualFile::getPath)
                .sortedBy(VirtualFile::getPath)
                .toList()
            val scanRoots = if (explicitSourceRoots.isNotEmpty()) {
                sourceRootCountByModule[module.name] = explicitSourceRoots.size
                explicitSourceRoots.mapNotNull { sourceRoot ->
                    val containingRoots = contentRoots
                        .filter { candidate -> VfsUtilCore.isAncestor(candidate, sourceRoot, false) }
                    val contentRoot = containingRoots
                        .filter { candidate -> candidate != sourceRoot }
                        .maxByOrNull { it.path.length }
                        ?: containingRoots.maxByOrNull { it.path.length }
                        ?: return@mapNotNull null
                    ScanRoot(
                        contentRoot = contentRoot,
                        root = sourceRoot,
                        sourceSetId = sourceSetId(contentRoot, sourceRoot, fileIndex),
                        explicit = true,
                    )
                }
            } else {
                emptyList()
            }
            scanRoots.forEach scanLoop@{ scan ->
                val relativeSourceRoot = resolver.locatorPath(scan.root, module)
                    ?: return@scanLoop
                val ownership = gradleOwnership(scan.root, module, resolver)
                buildIdsByModule.getOrPut(module.name, ::linkedSetOf) += ownership.buildId
                recordModuleAnchor(
                    moduleId = module.name,
                    moduleRoot = ownership.moduleRoot,
                    buildId = ownership.buildId,
                    gradlePath = ownership.gradlePath,
                    importedModule = module,
                    resolver = resolver,
                    anchors = moduleAnchors,
                )
                if (scan.explicit) {
                    sourceRootKeys += sourceRootKey(
                        moduleId = module.name,
                        relativePath = relativeSourceRoot,
                        sourceSetId = scan.sourceSetId,
                        buildId = ownership.buildId,
                        kind = sourceRootKind(scan.root, fileIndex),
                        recovered = false,
                    )
                }
                visit(
                    file = scan.root,
                    module = module,
                    buildId = ownership.buildId,
                    ownerModuleId = module.name,
                    ownerContentRoot = scan.contentRoot,
                    scanRoot = scan.root,
                    sourceSetId = scan.sourceSetId,
                    explicitSourceRoot = scan.explicit,
                    fileIndex = fileIndex,
                    resolver = resolver,
                    candidatesByPath = candidatesByPath,
                    onOwnershipOverlap = { relativePath, ambiguous ->
                        overlappingOwnershipPaths += relativePath
                        if (ambiguous) ambiguousOwnershipPaths += relativePath
                    },
                    onExcluded = { length ->
                        excludedFiles += 1
                        excludedBytes += length
                    },
                )
            }

            contentRoots.forEach contentLoop@{ contentRoot ->
                if (!visitedContentRoots.add(contentRoot.path)) return@contentLoop
                val discovery = discoverConventionalSourceRoots(
                    contentRoot = contentRoot,
                    configuredSourceRootPaths = configuredSourceRootPaths,
                )
                sourceRootDiscoveryLimitReached =
                    sourceRootDiscoveryLimitReached || discovery.limitReached
                discovery.moduleRoots.forEach moduleRootLoop@{ discoveredModule ->
                    val moduleRoot = discoveredModule.root
                    val importedOwner = importedContentRootOwners[moduleRoot.path]
                        .orEmpty()
                        .singleOrNull()
                    val ownershipModule = importedOwner ?: module
                    val ownership = gradleOwnership(
                        moduleRoot,
                        ownershipModule,
                        resolver,
                        discoveredModule.declaredGradlePath,
                    )
                    val ownerModuleId = if (importedOwner != null) {
                        importedOwner.name
                    } else {
                        ownership.moduleId
                    }
                    val relativeModuleRoot = resolver.locatorPath(moduleRoot, module)
                        ?: return@moduleRootLoop
                    rootKeys += "$ownerModuleId\u0000$relativeModuleRoot"
                    buildIdsByModule.getOrPut(ownerModuleId, ::linkedSetOf) += ownership.buildId
                    recordModuleAnchor(
                        moduleId = ownerModuleId,
                        moduleRoot = moduleRoot,
                        buildId = ownership.buildId,
                        gradlePath = ownership.gradlePath,
                        importedModule = ownershipModule,
                        resolver = resolver,
                        anchors = moduleAnchors,
                        replaceExisting = discoveredModule.declaredGradlePath != null,
                    )
                }
                discovery.roots.forEach discoveredLoop@{ discovered ->
                    if (!visitedDiscoveredRoots.add(discovered.root.path)) return@discoveredLoop
                    val declaredGradlePath = discovery.moduleRoots
                        .asSequence()
                        .filter { discoveredModule ->
                            discoveredModule.declaredGradlePath != null &&
                                VfsUtilCore.isAncestor(discoveredModule.root, discovered.root, false)
                        }
                        .maxByOrNull { it.root.path.length }
                        ?.declaredGradlePath
                    val ownership = gradleOwnership(
                        discovered.root,
                        module,
                        resolver,
                        declaredGradlePath,
                    )
                    val relativeModuleRoot = resolver.locatorPath(ownership.moduleRoot, module)
                        ?: return@discoveredLoop
                    val relativeSourceRoot = resolver.locatorPath(discovered.root, module)
                        ?: return@discoveredLoop
                    rootKeys += "${ownership.moduleId}\u0000$relativeModuleRoot"
                    sourceRootKeys += sourceRootKey(
                        moduleId = ownership.moduleId,
                        relativePath = relativeSourceRoot,
                        sourceSetId = discovered.sourceSetId,
                        buildId = ownership.buildId,
                        kind = discovered.kind,
                        recovered = true,
                    )
                    sourceRootCountByModule[ownership.moduleId] =
                        (sourceRootCountByModule[ownership.moduleId] ?: 0) + 1
                    buildIdsByModule.getOrPut(ownership.moduleId, ::linkedSetOf) += ownership.buildId
                    discoveredSourceRootCountByModule[ownership.moduleId] =
                        (discoveredSourceRootCountByModule[ownership.moduleId] ?: 0) + 1
                    visit(
                        file = discovered.root,
                        module = module,
                        buildId = ownership.buildId,
                        ownerModuleId = ownership.moduleId,
                        ownerContentRoot = contentRoot,
                        scanRoot = discovered.root,
                        sourceSetId = discovered.sourceSetId,
                        explicitSourceRoot = false,
                        fileIndex = fileIndex,
                        resolver = resolver,
                        candidatesByPath = candidatesByPath,
                        onOwnershipOverlap = { relativePath, ambiguous ->
                            overlappingOwnershipPaths += relativePath
                            if (ambiguous) ambiguousOwnershipPaths += relativePath
                        },
                        onExcluded = { length ->
                            excludedFiles += 1
                            excludedBytes += length
                        },
                    )
                }

                if (explicitSourceRoots.isEmpty() && discovery.roots.isEmpty()) {
                    fallbackRootCountByModule[module.name] =
                        (fallbackRootCountByModule[module.name] ?: 0) + 1
                    val relativeContentRoot = resolver.locatorPath(contentRoot, module)
                        ?: return@contentLoop
                    val ownership = gradleOwnership(contentRoot, module, resolver)
                    buildIdsByModule.getOrPut(module.name, ::linkedSetOf) += ownership.buildId
                    sourceRootKeys += sourceRootKey(
                        moduleId = module.name,
                        relativePath = relativeContentRoot,
                        sourceSetId = "content-root",
                        buildId = ownership.buildId,
                        kind = ApplicationGraphSourceRootKind.UNKNOWN,
                        recovered = false,
                    )
                    visit(
                        file = contentRoot,
                        module = module,
                        buildId = ownership.buildId,
                        ownerModuleId = module.name,
                        ownerContentRoot = contentRoot,
                        scanRoot = contentRoot,
                        sourceSetId = "content-root",
                        explicitSourceRoot = false,
                        fileIndex = fileIndex,
                        resolver = resolver,
                        candidatesByPath = candidatesByPath,
                        onOwnershipOverlap = { relativePath, ambiguous ->
                            overlappingOwnershipPaths += relativePath
                            if (ambiguous) ambiguousOwnershipPaths += relativePath
                        },
                        onExcluded = { length ->
                            excludedFiles += 1
                            excludedBytes += length
                        },
                    )
                }
            }
        }

        val sortedCandidates = candidatesByPath.values.sortedBy { it.stamp.relativePath }
        val staticModuleDependencies = discoverModuleDependencies(moduleAnchors, resolver)
        val importedModuleDependencies = discoverImportedModuleDependencies(modules, moduleAnchors)
        val moduleDependencies = (importedModuleDependencies + staticModuleDependencies)
            .distinctBy { dependency ->
                dependency.targetModuleId?.let { target ->
                    "${dependency.sourceModuleId}\u0000$target"
                } ?: "${dependency.sourceModuleId}\u0000?\u0000${dependency.targetGradlePath}"
            }
        val candidates = sortedCandidates.take(MAX_FILES)
        if (candidatesByPath.size > MAX_FILES) {
            sortedCandidates.drop(MAX_FILES).forEach { candidate ->
                excludedFiles += 1
                excludedBytes += candidate.stamp.length
            }
        }
        return CandidateInventory(
            candidates = candidates,
            stamps = candidates.map(CandidateFile::stamp),
            rootKeys = rootKeys.sorted(),
            sourceRootKeys = sourceRootKeys.sorted(),
            totalCandidateFiles = candidatesByPath.size,
            candidateFilesByModule = candidatesByPath.values
                .groupingBy { it.stamp.moduleId }
                .eachCount(),
            excludedFiles = excludedFiles,
            excludedBytes = excludedBytes,
            sourceRootCountByModule = sourceRootCountByModule,
            discoveredSourceRootCountByModule = discoveredSourceRootCountByModule,
            fallbackRootCountByModule = fallbackRootCountByModule,
                    buildIdsByModule = buildIdsByModule.mapValues { (_, values) -> values.sorted() },
            moduleAnchors = moduleAnchors,
            sourceRootDiscoveryLimitReached = sourceRootDiscoveryLimitReached,
            overlappingOwnershipFileCount = overlappingOwnershipPaths.size,
            ambiguousOwnershipFileCount = ambiguousOwnershipPaths.size,
            ambiguousOwnershipPaths = ambiguousOwnershipPaths.take(MAX_OWNERSHIP_DIAGNOSTICS),
            moduleDependencies = moduleDependencies,
        )
    }

    private fun buildModuleCoverage(
        inventory: CandidateInventory,
        sources: List<GraphSourceFile>,
    ): List<ApplicationGraphModuleCoverage> {
        val rootsByModule = inventory.rootKeys
            .map { key -> key.substringBefore('\u0000') }
            .groupingBy { it }
            .eachCount()
        val indexedByModule = sources.groupingBy { it.owner.moduleId }.eachCount()
        val sourceSetsByModule = sources.groupBy { it.owner.moduleId }
            .mapValues { (_, owned) -> owned.mapNotNull { it.owner.sourceSetId }.distinct().sorted() }
        val sourceBuildIdsByModule = sources.groupBy { it.owner.moduleId }
            .mapValues { (_, owned) -> owned.map { it.owner.buildId }.distinct().sorted() }
        val sourceRootsByModule = inventory.sourceRootKeys
            .mapNotNull(::decodeSourceRootKey)
            .groupBy(ApplicationGraphSourceRootCoverage::moduleId)
            .mapValues { (_, roots) ->
                roots.distinctBy {
                    listOf(it.relativePath, it.sourceSetId, it.kind.name).joinToString("\u0000")
                }.sortedWith(
                    compareBy<ApplicationGraphSourceRootCoverage>(
                        ApplicationGraphSourceRootCoverage::sourceSetId,
                        ApplicationGraphSourceRootCoverage::kind,
                        ApplicationGraphSourceRootCoverage::relativePath,
                    ),
                )
            }
        return (rootsByModule.keys + inventory.candidateFilesByModule.keys + indexedByModule.keys)
            .distinct()
            .sorted()
            .map { moduleId ->
                ApplicationGraphModuleCoverage(
                    moduleId = moduleId,
                    buildIds = (
                        inventory.buildIdsByModule[moduleId].orEmpty() +
                            sourceBuildIdsByModule[moduleId].orEmpty()
                        ).distinct().sorted(),
                    contentRootCount = rootsByModule[moduleId] ?: 0,
                    sourceRootCount = inventory.sourceRootCountByModule[moduleId] ?: 0,
                    discoveredSourceRootCount =
                        inventory.discoveredSourceRootCountByModule[moduleId] ?: 0,
                    fallbackContentRootCount = inventory.fallbackRootCountByModule[moduleId] ?: 0,
                    candidateFileCount = inventory.candidateFilesByModule[moduleId] ?: 0,
                    indexedFileCount = indexedByModule[moduleId] ?: 0,
                    sourceSets = sourceSetsByModule[moduleId].orEmpty(),
                    moduleRoot = inventory.moduleAnchors[moduleId]?.moduleRoot.orEmpty(),
                    sourceRoots = sourceRootsByModule[moduleId].orEmpty(),
                )
            }
    }

    private fun sourceRootKind(
        sourceRoot: VirtualFile,
        fileIndex: ProjectFileIndex,
    ): ApplicationGraphSourceRootKind {
        val resourceRoot = ModuleManager.getInstance(project).modules.any { module ->
            ModuleRootManager.getInstance(module)
                .getSourceRoots(JavaResourceRootType.RESOURCE)
                .any { it.path == sourceRoot.path }
        }
        if (resourceRoot) return ApplicationGraphSourceRootKind.RESOURCES
        return when (sourceRoot.name.lowercase()) {
            "java" -> ApplicationGraphSourceRootKind.JAVA
            "kotlin" -> ApplicationGraphSourceRootKind.KOTLIN
            "groovy" -> ApplicationGraphSourceRootKind.GROOVY
            "resources" -> ApplicationGraphSourceRootKind.RESOURCES
            else -> if (fileIndex.isInSourceContent(sourceRoot)) {
                ApplicationGraphSourceRootKind.JAVA
            } else {
                ApplicationGraphSourceRootKind.UNKNOWN
            }
        }
    }

    private fun sourceRootKey(
        moduleId: String,
        relativePath: String,
        sourceSetId: String,
        buildId: String,
        kind: ApplicationGraphSourceRootKind,
        recovered: Boolean,
    ): String = listOf(
        moduleId,
        relativePath,
        sourceSetId,
        buildId,
        kind.name,
        recovered.toString(),
    ).joinToString("\u0000")

    private fun decodeSourceRootKey(key: String): ApplicationGraphSourceRootCoverage? {
        val parts = key.split('\u0000')
        if (parts.size < 6) return null
        return ApplicationGraphSourceRootCoverage(
            moduleId = parts[0],
            relativePath = parts[1],
            sourceSetId = parts[2],
            buildId = parts[3],
            kind = runCatching { ApplicationGraphSourceRootKind.valueOf(parts[4]) }
                .getOrDefault(ApplicationGraphSourceRootKind.UNKNOWN),
            recovered = parts[5].toBooleanStrictOrNull() ?: false,
        )
    }

    private fun visit(
        file: VirtualFile,
        module: Module,
        buildId: String,
        ownerModuleId: String,
        ownerContentRoot: VirtualFile,
        scanRoot: VirtualFile,
        sourceSetId: String,
        explicitSourceRoot: Boolean,
        fileIndex: ProjectFileIndex,
        resolver: ProjectFileResolver,
        candidatesByPath: MutableMap<String, CandidateFile>,
        onOwnershipOverlap: (relativePath: String, ambiguous: Boolean) -> Unit,
        onExcluded: (Long) -> Unit,
    ) {
        ProgressManager.checkCanceled()
        if (file.isDirectory) {
            if (file != scanRoot &&
                (file.name in EXCLUDED_DIRECTORY_NAMES || fileIndex.isExcluded(file))
            ) {
                return
            }
            file.children.sortedBy(VirtualFile::getName).forEach { child ->
                visit(
                    child,
                    module,
                    buildId,
                    ownerModuleId,
                    ownerContentRoot,
                    scanRoot,
                    sourceSetId,
                    explicitSourceRoot,
                    fileIndex,
                    resolver,
                    candidatesByPath,
                    onOwnershipOverlap,
                    onExcluded,
                )
            }
            return
        }

        val language = languageFor(file.extension) ?: return
        val documentManager = FileDocumentManager.getInstance()
        val cachedDocument = documentManager.getCachedDocument(file)
        val unsavedDocument = cachedDocument?.takeIf(documentManager::isDocumentUnsaved)
        val effectiveLength = unsavedDocument?.textLength?.toLong() ?: file.length
        if (effectiveLength > MAX_FILE_BYTES) {
            onExcluded(effectiveLength)
            return
        }
        val relativePath = resolver.locatorPath(file, module) ?: return
        val sourceSet = if (explicitSourceRoot || sourceSetId != "content-root") {
            sourceSetId
        } else {
            when {
                fileIndex.isInTestSourceContent(file) -> "test"
                fileIndex.isInSourceContent(file) -> "main"
                else -> inferSourceSetFromPath(file, scanRoot)
            }
        }
        val stamp = FileStamp(
            relativePath = relativePath,
            modificationStamp = file.modificationStamp,
            documentModificationStamp = unsavedDocument?.modificationStamp,
            length = effectiveLength,
            buildId = buildId,
            moduleId = ownerModuleId,
            sourceSetId = sourceSet,
            language = language,
            explicitSourceRoot = explicitSourceRoot,
            contentRootPath = ownerContentRoot.path,
            scanRootPath = scanRoot.path,
        )
        val candidate = CandidateFile(file, stamp)
        val existing = candidatesByPath[relativePath]
        if (existing == null) {
            candidatesByPath[relativePath] = candidate
            return
        }
        if (existing.file.path == candidate.file.path &&
            existing.stamp.moduleId == candidate.stamp.moduleId &&
            existing.stamp.sourceSetId == candidate.stamp.sourceSetId
        ) {
            if (candidate.isPreferredTo(existing)) candidatesByPath[relativePath] = candidate
            return
        }
        val preference = candidate.ownershipPreferenceComparedTo(existing)
        val ambiguous = preference == 0 &&
            (candidate.stamp.moduleId != existing.stamp.moduleId ||
                candidate.stamp.buildId != existing.stamp.buildId ||
                candidate.file.path != existing.file.path)
        onOwnershipOverlap(relativePath, ambiguous)
        if (preference > 0 ||
            (preference == 0 && candidate.deterministicOwnerKey < existing.deterministicOwnerKey)
        ) {
            candidatesByPath[relativePath] = candidate
        }
    }

    private fun ApplicationGraphIndexResult.toResponse(
        scannedFiles: Int,
        candidateFiles: Int,
        excludedFiles: Int,
        excludedBytes: Long,
        unreadableFiles: Int,
        parseErrorFiles: Int,
        parserUnavailableFiles: Int,
        hostDiagnostics: List<DiscoveryDiagnostic>,
        reusedFiles: Int,
        changedFiles: Int,
        cacheHit: Boolean,
        durationMillis: Long,
        modules: List<ApplicationGraphModuleCoverage>,
        moduleAnchors: Map<String, ModuleAnchor>,
        contentRootCount: Int,
        sourceRootCount: Int,
        fallbackContentRootCount: Int,
        limitReached: Boolean,
        overlappingOwnershipFileCount: Int,
        ambiguousOwnershipFileCount: Int,
        ambiguousOwnershipPaths: List<String>,
        moduleDependencies: List<ModuleDependency>,
    ): ApplicationGraphResponse {
        val enrichedGraph = enrichTopology(
            indexedArtifacts = artifacts,
            indexedRelationships = relationships,
            modules = modules,
            moduleAnchors = moduleAnchors,
            moduleDependencies = moduleDependencies,
        )
        val responseArtifacts = enrichedGraph.artifacts
        val responseRelationships = enrichedGraph.relationships
        val unresolvedModuleDependencyCount = responseRelationships.count {
            it.type == RelationshipType.DEPENDS_ON_MODULE && it.targetArtifactId == null
        }
        val discoveredSourceRootCount = modules.sumOf(ApplicationGraphModuleCoverage::discoveredSourceRootCount)
        val recoveredModuleCount = modules.count { it.moduleId.startsWith(RECOVERED_GRADLE_MODULE_PREFIX) }
        val coverageDiagnostics = buildList {
            if (limitReached) {
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-INVENTORY-TRUNCATED",
                        severity = DiagnosticSeverity.ERROR,
                        message = "The project inventory exceeded a reviewed graph safety limit; the application map is partial.",
                        nextStep = "Narrow generated/imported roots or raise the reviewed limit before relying on impact analysis.",
                    ),
                )
            }
            if (unreadableFiles > 0) {
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-SOURCE-UNREADABLE",
                        severity = DiagnosticSeverity.ERROR,
                        message = "$unreadableFiles indexed source file(s) could not be read; the application map is partial.",
                        nextStep = "Refresh the IntelliJ VFS, repair file permissions, and rebuild the graph.",
                    ),
                )
            }
            if (parseErrorFiles > 0) {
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-PARSE-ERRORS",
                        severity = DiagnosticSeverity.ERROR,
                        message = "$parseErrorFiles source file(s) contain syntax or XML parse errors; those files are not safely represented.",
                        nextStep = "Correct every reported parse error and refresh before relying on impact analysis.",
                    ),
                )
            }
            if (parserUnavailableFiles > 0) {
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-PARSER-COVERAGE-MISSING",
                        severity = DiagnosticSeverity.ERROR,
                        message = "$parserUnavailableFiles JVM source file(s) were indexed without an IntelliJ language parser.",
                        nextStep = "Enable the matching IntelliJ language plugin and refresh the project.",
                    ),
                )
            }
            if (excludedFiles > 0 && !limitReached) {
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-SOURCE-EXCLUDED",
                        severity = DiagnosticSeverity.ERROR,
                        message = "$excludedFiles eligible source file(s) were excluded by size or project-index rules; the application map is partial.",
                        nextStep = "Review excluded sources and limits before relying on impact analysis.",
                    ),
                )
            }
            if (modules.isEmpty()) {
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-NO-IMPORTED-MODULES",
                        severity = DiagnosticSeverity.BLOCKING,
                        message = "No imported IntelliJ modules or content roots are available for indexing.",
                        nextStep = "Import or refresh the Gradle project before using visual analysis or source generation.",
                    ),
                )
            }
            if (recoveredModuleCount > 0) {
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-GRADLE-SYNC-INCOMPLETE",
                        severity = DiagnosticSeverity.ERROR,
                        message = "$recoveredModuleCount module(s), including $discoveredSourceRootCount filesystem or build-declared source root(s), were recovered outside the imported IntelliJ source model.",
                        nextStep = "Refresh the Gradle project so classpaths and custom source sets are imported; recovered files are mapped, but classpath-aware completeness cannot be guaranteed yet.",
                    ),
                )
            }
            if (overlappingOwnershipFileCount > 0) {
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-OVERLAPPING-ROOTS-RESOLVED",
                        severity = DiagnosticSeverity.INFO,
                        message = "$overlappingOwnershipFileCount source file(s) were visible through overlapping IntelliJ roots; the most specific owner was selected deterministically.",
                        nextStep = "No action is required unless the module ownership shown in the application map is unexpected.",
                    ),
                )
            }
            if (ambiguousOwnershipFileCount > 0) {
                val examples = ambiguousOwnershipPaths
                    .take(5)
                    .joinToString()
                    .takeIf(String::isNotBlank)
                    ?.let { ": $it" }
                    .orEmpty()
                add(
                    coverageDiagnostic(
                        reasonCode = "JVW-INDEX-AMBIGUOUS-FILE-OWNERSHIP",
                        severity = DiagnosticSeverity.ERROR,
                        message = "$ambiguousOwnershipFileCount source file(s) have equally specific but conflicting module ownership$examples.",
                        nextStep = "Remove duplicate content-root ownership or exclude the unintended module root, then refresh Gradle.",
                    ),
                )
            }
        }
        val allDiagnostics = (diagnostics + enrichedGraph.diagnostics + hostDiagnostics + coverageDiagnostics)
            .distinctBy(DiscoveryDiagnostic::id)
            .sortedBy(DiscoveryDiagnostic::id)
        val digestPayload = buildString {
            responseArtifacts.forEach { append(it.id).append('\u0000').append(it.fingerprint).append('\n') }
            responseRelationships.forEach {
                append(it.sourceArtifactId).append('\u0000')
                    .append(it.targetArtifactId.orEmpty()).append('\u0000')
                    .append(it.type.name).append('\n')
            }
            allDiagnostics.forEach {
                append(it.id).append('\u0000').append(it.reasonCode).append('\n')
            }
            modules.forEach {
                append(it.moduleId).append('\u0000')
                    .append(it.buildIds.joinToString(",")).append('\u0000')
                    .append(it.sourceSets.joinToString(",")).append('\u0000')
                    .append(it.contentRootCount).append('\u0000')
                    .append(it.sourceRootCount).append('\u0000')
                    .append(it.discoveredSourceRootCount).append('\u0000')
                    .append(it.fallbackContentRootCount).append('\n')
            }
        }
        return ApplicationGraphResponse(
            artifacts = responseArtifacts,
            relationships = responseRelationships,
            diagnostics = allDiagnostics,
            summary = ApplicationGraphSummary(
                artifactCount = responseArtifacts.size,
                relationshipCount = responseRelationships.size,
                diagnosticCount = allDiagnostics.size,
                unresolvedRelationshipCount = responseRelationships.count { it.targetArtifactId == null },
                countsByKind = responseArtifacts
                    .groupingBy { it.kind }
                    .eachCount()
                    .entries
                    .sortedBy { it.key.name }
                    .associate { it.key.name to it.value },
            ),
            scannedFiles = scannedFiles,
            candidateFiles = candidateFiles,
            excludedFiles = excludedFiles,
            excludedBytes = excludedBytes,
            unreadableFiles = unreadableFiles,
            parseErrorFiles = parseErrorFiles,
            parserUnavailableFiles = parserUnavailableFiles,
            reusedFiles = reusedFiles,
            changedFiles = changedFiles,
            cacheHit = cacheHit,
            durationMillis = durationMillis,
            modules = modules,
            indexHealth = ApplicationGraphIndexHealth(
                complete = !limitReached &&
                    excludedFiles == 0 &&
                    unreadableFiles == 0 &&
                    parseErrorFiles == 0 &&
                    parserUnavailableFiles == 0 &&
                    ambiguousOwnershipFileCount == 0 &&
                    unresolvedModuleDependencyCount == 0 &&
                    recoveredModuleCount == 0 &&
                    modules.isNotEmpty(),
                moduleCount = modules.size,
                contentRootCount = contentRootCount,
                sourceRootCount = sourceRootCount,
                fallbackContentRootCount = fallbackContentRootCount,
                unreadableFileCount = unreadableFiles,
                parseErrorFileCount = parseErrorFiles,
                parserUnavailableFileCount = parserUnavailableFiles,
                overlappingOwnershipFileCount = overlappingOwnershipFileCount,
                ambiguousOwnershipFileCount = ambiguousOwnershipFileCount,
                unresolvedModuleDependencyCount = unresolvedModuleDependencyCount,
                limitReached = limitReached,
                discoveredSourceRootCount = discoveredSourceRootCount,
                recoveredModuleCount = recoveredModuleCount,
            ),
            snapshotDigest = CanonicalDiscoveryJson.sha256(digestPayload),
        )
    }

    private fun enrichTopology(
        indexedArtifacts: List<ArtifactSnapshot>,
        indexedRelationships: List<ArtifactRelationship>,
        modules: List<ApplicationGraphModuleCoverage>,
        moduleAnchors: Map<String, ModuleAnchor>,
        moduleDependencies: List<ModuleDependency>,
    ): TopologyEnrichment {
        val artifacts = indexedArtifacts.associateByTo(linkedMapOf(), ArtifactSnapshot::id)
        val relationships = indexedRelationships.toMutableList()
        val topologyDiagnostics = mutableListOf<DiscoveryDiagnostic>()
        val firstArtifactByModule = indexedArtifacts
            .groupBy { it.owner.moduleId }
            .mapValues { (_, values) -> values.minByOrNull { it.sourceLocator.relativePath } }
        val moduleArtifacts = linkedMapOf<String, ArtifactSnapshot>()
        val sourceSetArtifacts = linkedMapOf<Pair<String, String>, ArtifactSnapshot>()
        val buildArtifacts = linkedMapOf<String, ArtifactSnapshot>()

        fun anchor(moduleId: String): SourceLocator? =
            moduleAnchors[moduleId]?.sourceLocator
                ?: firstArtifactByModule[moduleId]?.sourceLocator

        modules.forEach { module ->
            val moduleAnchor = anchor(module.moduleId) ?: return@forEach
            val primaryBuildId = module.buildIds.firstOrNull().orEmpty().ifBlank { "build:unknown" }
            module.buildIds.ifEmpty { listOf(primaryBuildId) }.forEach { buildId ->
                val buildArtifact = buildArtifacts.getOrPut(buildId) {
                    val buildAnchor = modules.asSequence()
                        .filter { buildId in it.buildIds }
                        .mapNotNull { anchor(it.moduleId) }
                        .minByOrNull(SourceLocator::relativePath)
                        ?: moduleAnchor
                    topologyArtifact(
                        kind = ArtifactKind.BUILD,
                        semanticKey = buildId,
                        displayName = buildId.removePrefix("build:").ifBlank { "root build" },
                        summary = "Gradle build containing indexed Jmix modules",
                        owner = ArtifactOwner(buildId, "__build__", null),
                        locator = buildAnchor,
                    )
                }
                artifacts.putIfAbsent(buildArtifact.id, buildArtifact)
            }
            val moduleArtifact = topologyArtifact(
                kind = ArtifactKind.MODULE,
                semanticKey = module.moduleId,
                displayName = module.moduleId.substringAfterLast("#:").substringAfterLast(':'),
                summary = buildString {
                    append(module.sourceSets.size).append(" source set(s), ")
                    append(module.indexedFileCount).append(" indexed file(s)")
                    if (module.discoveredSourceRootCount > 0) append(", recovered outside Gradle sync")
                },
                owner = ArtifactOwner(primaryBuildId, module.moduleId, null),
                locator = moduleAnchor,
            )
            moduleArtifacts[module.moduleId] = moduleArtifact
            artifacts.putIfAbsent(moduleArtifact.id, moduleArtifact)
            module.buildIds.ifEmpty { listOf(primaryBuildId) }.forEach { buildId ->
                buildArtifacts[buildId]?.let { buildArtifact ->
                    relationships += ArtifactRelationship(
                        sourceArtifactId = buildArtifact.id,
                        targetArtifactId = moduleArtifact.id,
                        type = RelationshipType.DECLARES,
                        sourceLocator = moduleAnchor,
                    )
                }
            }

            module.sourceSets.forEach { sourceSet ->
                val sourceSetAnchor = indexedArtifacts.asSequence()
                    .filter {
                        it.owner.moduleId == module.moduleId &&
                            it.owner.sourceSetId == sourceSet
                    }
                    .minByOrNull { it.sourceLocator.relativePath }
                    ?.sourceLocator
                    ?: moduleAnchor
                val sourceSetArtifact = topologyArtifact(
                    kind = ArtifactKind.SOURCE_SET,
                    semanticKey = "${module.moduleId}#$sourceSet",
                    displayName = sourceSet,
                    summary = "Source set owned by ${module.moduleId}",
                    owner = ArtifactOwner(primaryBuildId, module.moduleId, sourceSet),
                    locator = sourceSetAnchor,
                )
                sourceSetArtifacts[module.moduleId to sourceSet] = sourceSetArtifact
                artifacts.putIfAbsent(sourceSetArtifact.id, sourceSetArtifact)
                relationships += ArtifactRelationship(
                    sourceArtifactId = moduleArtifact.id,
                    targetArtifactId = sourceSetArtifact.id,
                    type = RelationshipType.DECLARES,
                    sourceLocator = sourceSetAnchor,
                )
            }
        }

        indexedArtifacts.forEach { artifact ->
            val moduleArtifact = moduleArtifacts[artifact.owner.moduleId] ?: return@forEach
            val source = artifact.owner.sourceSetId
                ?.let { sourceSetArtifacts[artifact.owner.moduleId to it] }
                ?: moduleArtifact
            relationships += ArtifactRelationship(
                sourceArtifactId = source.id,
                targetArtifactId = artifact.id,
                type = RelationshipType.DECLARES,
                sourceLocator = artifact.sourceLocator,
            )
        }

        moduleDependencies.forEach { dependency ->
            val source = moduleArtifacts[dependency.sourceModuleId] ?: return@forEach
            val target = dependency.targetModuleId?.let(moduleArtifacts::get)
            val diagnostic = when {
                target != null -> null
                dependency.ambiguous -> coverageDiagnostic(
                    reasonCode = "JVW-INDEX-MODULE-DEPENDENCY-AMBIGUOUS",
                    severity = DiagnosticSeverity.ERROR,
                    message = "Module ${dependency.sourceModuleId} has an ambiguous Gradle project dependency on ${dependency.targetGradlePath}.",
                    nextStep = "Remove duplicate Gradle project paths in the same build and refresh the project.",
                ).copy(sourceLocator = dependency.sourceLocator)
                else -> coverageDiagnostic(
                    reasonCode = "JVW-INDEX-MODULE-DEPENDENCY-UNRESOLVED",
                    severity = DiagnosticSeverity.ERROR,
                    message = "Module ${dependency.sourceModuleId} depends on ${dependency.targetGradlePath}, but that project is absent from the indexed build topology.",
                    nextStep = "Import the target Gradle module or repair the project dependency declaration.",
                ).copy(sourceLocator = dependency.sourceLocator)
            }
            diagnostic?.let(topologyDiagnostics::add)
            relationships += ArtifactRelationship(
                sourceArtifactId = source.id,
                targetArtifactId = target?.id,
                type = RelationshipType.DEPENDS_ON_MODULE,
                sourceLocator = dependency.sourceLocator,
                diagnostic = diagnostic,
            )
        }

        return TopologyEnrichment(
            artifacts = artifacts.values.sortedBy(ArtifactSnapshot::id),
            relationships = relationships
                .distinctBy {
                    listOf(
                        it.sourceArtifactId,
                        it.targetArtifactId.orEmpty(),
                        it.type.name,
                        it.sourceLocator.relativePath,
                    )
                }
                .sortedWith(
                    compareBy(
                        ArtifactRelationship::sourceArtifactId,
                        { it.targetArtifactId.orEmpty() },
                        { it.type.name },
                    ),
                ),
            diagnostics = topologyDiagnostics,
        )
    }

    private fun topologyArtifact(
        kind: ArtifactKind,
        semanticKey: String,
        displayName: String,
        summary: String,
        owner: ArtifactOwner,
        locator: SourceLocator,
    ): ArtifactSnapshot = ArtifactSnapshot(
        id = CanonicalDiscoveryJson.artifactId(
            kind = kind,
            buildId = owner.buildId,
            moduleId = owner.moduleId,
            semanticKey = semanticKey,
        ),
        kind = kind,
        semanticKey = semanticKey,
        owner = owner,
        sourceLocator = locator.copy(symbol = semanticKey),
        origin = ArtifactOrigin.IMPORTED_MODEL,
        fingerprint = CanonicalDiscoveryJson.sha256(
            listOf(kind.name, semanticKey, owner.buildId, owner.moduleId, locator.revisionFingerprint)
                .joinToString("\u0000"),
        ),
        displayName = displayName,
        summary = summary,
    )

    private fun discoverModuleDependencies(
        anchors: Map<String, ModuleAnchor>,
        resolver: ProjectFileResolver,
    ): List<ModuleDependency> {
        val targetsByBuildAndPath = anchors.entries
            .groupBy(
                keySelector = { (_, anchor) -> anchor.buildId to anchor.gradlePath },
                valueTransform = Map.Entry<String, ModuleAnchor>::key,
            )
        val targetsByBuildAndAccessor = anchors.entries
            .groupBy(
                keySelector = { (_, anchor) -> anchor.buildId to typeSafeProjectAccessor(anchor.gradlePath) },
                valueTransform = Map.Entry<String, ModuleAnchor>::key,
            )
        return buildList {
            anchors.toSortedMap().forEach { (sourceModuleId, anchor) ->
                val resolved = resolver.resolveFile(anchor.sourceLocator.relativePath) ?: return@forEach
                if (resolved.file.isDirectory || resolved.file.name !in BUILD_FILE_NAMES) return@forEach
                val content = runCatching {
                    String(resolved.file.contentsToByteArray(false), resolved.file.charset)
                }.getOrNull()?.let(::maskBuildComments) ?: return@forEach
                val references = buildList {
                    PROJECT_DEPENDENCY.findAll(content).forEach { match ->
                        add(
                            ModuleDependencyReference(
                                gradlePath = normalizeGradlePath(match.groupValues[1]),
                                accessor = null,
                                offset = match.range.first,
                            ),
                        )
                    }
                    TYPE_SAFE_PROJECT_DEPENDENCY.findAll(content).forEach { match ->
                        val accessor = "projects${match.groupValues[1]}"
                        add(
                            ModuleDependencyReference(
                                gradlePath = null,
                                accessor = accessor,
                                offset = match.range.first,
                            ),
                        )
                    }
                }.distinctBy { Triple(it.gradlePath, it.accessor, it.offset) }

                references.forEach { reference ->
                    val candidates = if (reference.gradlePath != null) {
                        targetsByBuildAndPath[anchor.buildId to reference.gradlePath].orEmpty()
                    } else {
                        targetsByBuildAndAccessor[anchor.buildId to reference.accessor].orEmpty()
                    }.distinct()
                    val targetPath = reference.gradlePath
                        ?: anchors[candidates.singleOrNull()]?.gradlePath
                        ?: reference.accessor.orEmpty()
                    val lineStart = content.lastIndexOf('\n', reference.offset - 1)
                        .let { if (it < 0) 0 else it + 1 }
                    val line = content.take(reference.offset).count { it == '\n' } + 1
                    add(
                        ModuleDependency(
                            sourceModuleId = sourceModuleId,
                            targetModuleId = candidates.singleOrNull(),
                            targetGradlePath = targetPath,
                            sourceLocator = anchor.sourceLocator.copy(
                                symbol = targetPath,
                                line = line,
                                column = reference.offset - lineStart + 1,
                            ),
                            ambiguous = candidates.size > 1,
                        ),
                    )
                }
            }
        }.distinctBy {
            listOf(
                it.sourceModuleId,
                it.targetModuleId.orEmpty(),
                it.targetGradlePath,
                it.sourceLocator.relativePath,
            )
        }
    }

    private fun discoverImportedModuleDependencies(
        modules: List<Module>,
        anchors: Map<String, ModuleAnchor>,
    ): List<ModuleDependency> = modules
        .sortedBy(Module::getName)
        .flatMap { sourceModule ->
            val sourceAnchor = anchors[sourceModule.name] ?: return@flatMap emptyList()
            ModuleRootManager.getInstance(sourceModule).orderEntries
                .filterIsInstance<ModuleOrderEntry>()
                .mapNotNull { orderEntry ->
                    val targetModule = orderEntry.module ?: return@mapNotNull null
                    if (targetModule == sourceModule) return@mapNotNull null
                    ModuleDependency(
                        sourceModuleId = sourceModule.name,
                        targetModuleId = targetModule.name,
                        targetGradlePath = anchors[targetModule.name]?.gradlePath ?: targetModule.name,
                        sourceLocator = sourceAnchor.sourceLocator.copy(
                            symbol = targetModule.name,
                            line = null,
                            column = null,
                        ),
                        ambiguous = false,
                    )
                }
        }

    private fun typeSafeProjectAccessor(gradlePath: String): String =
        normalizeGradlePath(gradlePath)
            .split(':')
            .filter(String::isNotBlank)
            .joinToString(separator = ".", prefix = "projects.") { segment ->
                segment.split('-', '_')
                    .filter(String::isNotBlank)
                    .mapIndexed { index, word ->
                        if (index == 0) word.replaceFirstChar(Char::lowercase)
                        else word.replaceFirstChar(Char::uppercase)
                    }
                    .joinToString("")
            }

    private fun normalizeGradlePath(path: String): String =
        ":${path.trim().trim(':').split(':').filter(String::isNotBlank).joinToString(":")}"
            .takeUnless { it == ":" && path.trim() != ":" }
            ?: path.trim()

    private fun recordModuleAnchor(
        moduleId: String,
        moduleRoot: VirtualFile,
        buildId: String,
        gradlePath: String,
        importedModule: Module,
        resolver: ProjectFileResolver,
        anchors: MutableMap<String, ModuleAnchor>,
        replaceExisting: Boolean = false,
    ) {
        if (moduleId in anchors && !replaceExisting) return
        val anchorFile = (BUILD_FILE_NAMES + SETTINGS_FILE_NAMES)
            .asSequence()
            .mapNotNull(moduleRoot::findChild)
            .firstOrNull { !it.isDirectory }
            ?: generateSequence(moduleRoot.parent) { it.parent }
                .flatMap { ancestor ->
                    SETTINGS_FILE_NAMES.asSequence().mapNotNull(ancestor::findChild)
                }
                .firstOrNull { !it.isDirectory }
            ?: return
        val relativePath = resolver.locatorPath(anchorFile, importedModule) ?: return
        val fingerprint = runCatching {
            CanonicalDiscoveryJson.sha256(
                String(anchorFile.contentsToByteArray(false), anchorFile.charset),
            )
        }.getOrNull() ?: return
        anchors[moduleId] = ModuleAnchor(
            sourceLocator = SourceLocator(relativePath = relativePath, revisionFingerprint = fingerprint),
            buildId = buildId,
            gradlePath = gradlePath,
            moduleRoot = resolver.locatorPath(moduleRoot, importedModule).orEmpty(),
        )
    }

    private fun coverageDiagnostic(
        reasonCode: String,
        severity: DiagnosticSeverity,
        message: String,
        nextStep: String,
    ): DiscoveryDiagnostic = DiscoveryDiagnostic(
        id = CanonicalDiscoveryJson.sha256("$reasonCode\u0000$message"),
        severity = severity,
        category = DiagnosticCategory.INDEX,
        reasonCode = reasonCode,
        message = message,
        nextStep = nextStep,
    )

    private fun languageFor(extension: String?): SourceLanguage? =
        when (extension?.lowercase()) {
            "java" -> SourceLanguage.JAVA
            "kt", "kts" -> SourceLanguage.KOTLIN
            "groovy" -> SourceLanguage.GROOVY
            "xml", "bpmn", "bpmn20", "dmn", "dmn11", "jrxml" -> SourceLanguage.XML
            "properties" -> SourceLanguage.PROPERTIES
            "yaml", "yml" -> SourceLanguage.YAML
            "sql" -> SourceLanguage.SQL
            "js", "jsx", "mjs", "cjs", "ts", "tsx" -> SourceLanguage.MIXED
            "css", "scss", "sass", "less", "html", "ftl", "freemarker", "json" ->
                SourceLanguage.UNKNOWN
            else -> null
        }

    private fun sourceSetId(
        contentRoot: VirtualFile,
        sourceRoot: VirtualFile,
        fileIndex: ProjectFileIndex,
    ): String {
        val relative = VfsUtilCore.getRelativePath(sourceRoot, contentRoot, '/').orEmpty()
        val structuralPath = relative.ifBlank { sourceRoot.path.replace('\\', '/') }
        Regex("""(?:^|/)src/([^/]+)(?:/|$)""").find(structuralPath)?.groupValues?.get(1)?.let { return it }
        if (structuralPath.contains("/generated/", ignoreCase = true) ||
            structuralPath.startsWith("build/generated/", ignoreCase = true)
        ) {
            val leaf = sourceRoot.name.takeIf(String::isNotBlank).orEmpty()
            return if (leaf.isBlank()) "generated" else "generated-$leaf"
        }
        return when {
            fileIndex.isInTestSourceContent(sourceRoot) -> "test"
            fileIndex.isInSourceContent(sourceRoot) -> "main"
            else -> sourceRoot.name.ifBlank { "source" }
        }
    }

    /**
     * Recovers conventional and explicitly build-declared Gradle source roots
     * that are physically present but absent from the imported IntelliJ model.
     * It also inventories build roots without sources so aggregator and orphan
     * modules remain visible. Discovery is bounded and never follows paths
     * outside a registered content root.
     */
    private fun discoverConventionalSourceRoots(
        contentRoot: VirtualFile,
        configuredSourceRootPaths: Set<String>,
    ): ConventionalRootDiscovery {
        val roots = linkedMapOf<String, ConventionalSourceRoot>()
        val moduleRoots = linkedMapOf<String, ModuleRootDiscovery>()
        var visitedDirectories = 0
        var limitReached = false

        fun visitDirectory(directory: VirtualFile) {
            ProgressManager.checkCanceled()
            if (limitReached || !directory.isDirectory) return
            if (directory != contentRoot && directory.name in EXCLUDED_DIRECTORY_NAMES) return
            visitedDirectories += 1
            if (visitedDirectories > MAX_SOURCE_ROOT_DISCOVERY_DIRECTORIES) {
                limitReached = true
                return
            }

            if (BUILD_FILE_NAMES.any { directory.findChild(it)?.isDirectory == false } ||
                SETTINGS_FILE_NAMES.any { directory.findChild(it)?.isDirectory == false }
            ) {
                moduleRoots.putIfAbsent(directory.path, ModuleRootDiscovery(directory))
                discoverSettingsDeclaredModuleRoots(directory).forEach { declaredModuleRoot ->
                    moduleRoots[declaredModuleRoot.root.path] = declaredModuleRoot
                }
                discoverBuildDeclaredSourceRoots(directory, configuredSourceRootPaths)
                    .forEach { declared ->
                        if (roots.size < MAX_DISCOVERED_SOURCE_ROOTS) {
                            roots.putIfAbsent(declared.root.path, declared)
                        } else {
                            limitReached = true
                        }
                    }
            }

            val src = directory.findChild("src")?.takeIf(VirtualFile::isDirectory)
            src?.children
                ?.asSequence()
                ?.filter(VirtualFile::isDirectory)
                ?.sortedBy(VirtualFile::getName)
                ?.forEach { sourceSet ->
                    sourceSet.children
                        .asSequence()
                        .filter { it.isDirectory && it.name in CONVENTIONAL_SOURCE_DIRECTORY_NAMES }
                        .sortedBy(VirtualFile::getName)
                        .forEach { sourceRoot ->
                            val path = sourceRoot.path
                            val coveredByImportedRoot = configuredSourceRootPaths.any { configured ->
                                path == configured
                            }
                            if (!coveredByImportedRoot && roots.size < MAX_DISCOVERED_SOURCE_ROOTS) {
                                roots[path] = ConventionalSourceRoot(
                                    root = sourceRoot,
                                    sourceSetId = sourceSet.name,
                                    kind = sourceRootKindFromDslName(sourceRoot.name),
                                )
                            } else if (!coveredByImportedRoot) {
                                limitReached = true
                            }
                        }
                }

            directory.children
                .asSequence()
                .filter { child -> child.isDirectory && child.name != "src" }
                .sortedBy(VirtualFile::getName)
                .forEach(::visitDirectory)
        }

        visitDirectory(contentRoot)
        return ConventionalRootDiscovery(
            roots = roots.values.sortedBy { it.root.path },
            moduleRoots = moduleRoots.values.sortedBy { it.root.path },
            limitReached = limitReached,
        )
    }

    private fun discoverSettingsDeclaredModuleRoots(buildRoot: VirtualFile): List<ModuleRootDiscovery> {
        val declared = linkedMapOf<String, ModuleRootDiscovery>()
        SETTINGS_FILE_NAMES.forEach { fileName ->
            val settingsFile = buildRoot.findChild(fileName)
                ?.takeIf { !it.isDirectory }
                ?: return@forEach
            val rawContent = runCatching {
                String(settingsFile.contentsToByteArray(false), settingsFile.charset)
            }.getOrDefault("")
            val content = maskBuildComments(rawContent)
            val projectDirectories = linkedMapOf<String, String>()
            PROJECT_DIRECTORY_ASSIGNMENTS.forEach { assignment ->
                assignment.findAll(content).forEach { match ->
                    val gradlePath = normalizeGradlePath(match.groupValues[1])
                    val relativeDirectory = match.groupValues[2].replace('\\', '/').trim('/')
                    if (isSafeRelativeBuildPath(relativeDirectory)) {
                        projectDirectories[gradlePath] = relativeDirectory
                    }
                }
            }
            SETTINGS_INCLUDE.findAll(content).forEach { include ->
                val arguments = include.groupValues[1].ifBlank { include.groupValues[2] }
                QUOTED_BUILD_PATH.findAll(arguments).forEach pathLoop@{ pathMatch ->
                    val gradlePath = normalizeGradlePath(pathMatch.groupValues[1])
                    if (gradlePath == ":") return@pathLoop
                    val defaultDirectory = gradlePath.trim(':').replace(':', '/')
                    val relativeDirectory = projectDirectories[gradlePath] ?: defaultDirectory
                    if (!isSafeRelativeBuildPath(relativeDirectory)) return@pathLoop
                    val moduleRoot = buildRoot.findFileByRelativePath(relativeDirectory)
                        ?.takeIf(VirtualFile::isDirectory)
                        ?: return@pathLoop
                    declared[moduleRoot.path] = ModuleRootDiscovery(moduleRoot, gradlePath)
                }
            }
            projectDirectories.forEach { (gradlePath, relativeDirectory) ->
                val moduleRoot = buildRoot.findFileByRelativePath(relativeDirectory)
                    ?.takeIf(VirtualFile::isDirectory)
                    ?: return@forEach
                declared[moduleRoot.path] = ModuleRootDiscovery(moduleRoot, gradlePath)
            }
        }
        return declared.values.sortedBy { it.root.path }
    }

    private fun maskBuildComments(content: String): String {
        val result = content.toCharArray()
        var index = 0
        var lineComment = false
        var blockComment = false
        var quote: Char? = null
        var escaped = false
        while (index < content.length) {
            val current = content[index]
            val next = content.getOrNull(index + 1)
            when {
                lineComment -> {
                    if (current == '\n' || current == '\r') lineComment = false
                    else result[index] = ' '
                }
                blockComment -> {
                    if (current == '*' && next == '/') {
                        result[index] = ' '
                        result[index + 1] = ' '
                        blockComment = false
                        index += 1
                    } else if (current != '\n' && current != '\r') {
                        result[index] = ' '
                    }
                }
                quote != null -> {
                    when {
                        escaped -> escaped = false
                        current == '\\' -> escaped = true
                        current == quote -> quote = null
                    }
                }
                current == '"' || current == '\'' -> quote = current
                current == '/' && next == '/' -> {
                    result[index] = ' '
                    result[index + 1] = ' '
                    lineComment = true
                    index += 1
                }
                current == '/' && next == '*' -> {
                    result[index] = ' '
                    result[index + 1] = ' '
                    blockComment = true
                    index += 1
                }
            }
            index += 1
        }
        return String(result)
    }

    private fun discoverBuildDeclaredSourceRoots(
        moduleRoot: VirtualFile,
        configuredSourceRootPaths: Set<String>,
    ): List<ConventionalSourceRoot> {
        val declarations = linkedMapOf<String, ConventionalSourceRoot>()
        BUILD_FILE_NAMES.forEach { fileName ->
            val buildFile = moduleRoot.findChild(fileName)
                ?.takeIf { !it.isDirectory }
                ?: return@forEach
            val content = runCatching {
                String(buildFile.contentsToByteArray(false), buildFile.charset)
            }.getOrDefault("")
            SOURCE_DIRECTORY_DECLARATION.findAll(content).forEach { declaration ->
                val arguments = declaration.groupValues[2].ifBlank { declaration.groupValues[3] }
                QUOTED_BUILD_PATH.findAll(arguments).forEach pathLoop@{ pathMatch ->
                    val declaredPath = pathMatch.groupValues[1].trim().replace('\\', '/')
                    if (!isSafeRelativeBuildPath(declaredPath)) return@pathLoop
                    val root = moduleRoot.findFileByRelativePath(declaredPath)
                        ?.takeIf(VirtualFile::isDirectory)
                        ?: return@pathLoop
                    if (configuredSourceRootPaths.any { configured ->
                            root.path == configured
                        }
                    ) {
                        return@pathLoop
                    }
                    declarations[root.path] = ConventionalSourceRoot(
                        root = root,
                        sourceSetId = inferDeclaredSourceSet(content, declaration.range.first, declaredPath),
                        kind = inferDeclaredSourceKind(
                            buildContent = content,
                            declarationOffset = declaration.range.first,
                            declaredPath = declaredPath,
                            explicitDslName = declaration.groupValues[1],
                        ),
                    )
                }
            }
        }
        return declarations.values.sortedBy { it.root.path }
    }

    private fun inferDeclaredSourceSet(
        buildContent: String,
        declarationOffset: Int,
        declaredPath: String,
    ): String {
        Regex("""(?:^|/)src/([^/]+)(?:/|$)""")
            .find(declaredPath)
            ?.groupValues
            ?.get(1)
            ?.let { return it }
        val context = buildContent.substring(
            (declarationOffset - SOURCE_SET_CONTEXT_WINDOW).coerceAtLeast(0),
            declarationOffset,
        )
        SOURCE_SET_SELECTOR.findAll(context)
            .toList()
            .asReversed()
            .mapNotNull { match ->
                match.groupValues[1].ifBlank {
                    match.groupValues[2].ifBlank { match.groupValues[4] }
                }.takeIf { it.isNotBlank() && it !in NON_SOURCE_SET_BLOCK_NAMES }
            }
            .firstOrNull()
            ?.let { return it }
        return "custom"
    }

    private fun sourceRootKindFromDslName(name: String): ApplicationGraphSourceRootKind =
        when (name.lowercase()) {
            "java" -> ApplicationGraphSourceRootKind.JAVA
            "kotlin" -> ApplicationGraphSourceRootKind.KOTLIN
            "groovy" -> ApplicationGraphSourceRootKind.GROOVY
            "resources" -> ApplicationGraphSourceRootKind.RESOURCES
            else -> ApplicationGraphSourceRootKind.UNKNOWN
        }

    private fun inferDeclaredSourceKind(
        buildContent: String,
        declarationOffset: Int,
        declaredPath: String,
        explicitDslName: String,
    ): ApplicationGraphSourceRootKind {
        sourceRootKindFromDslName(explicitDslName)
            .takeUnless { it == ApplicationGraphSourceRootKind.UNKNOWN }
            ?.let { return it }
        declaredPath.replace('\\', '/')
            .trim('/')
            .split('/')
            .asReversed()
            .firstNotNullOfOrNull { segment ->
                sourceRootKindFromDslName(segment)
                    .takeUnless { it == ApplicationGraphSourceRootKind.UNKNOWN }
            }
            ?.let { return it }
        val context = buildContent.substring(
            (declarationOffset - SOURCE_SET_CONTEXT_WINDOW).coerceAtLeast(0),
            declarationOffset,
        )
        return SOURCE_KIND_BLOCK.findAll(context)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.let(::sourceRootKindFromDslName)
            ?: ApplicationGraphSourceRootKind.UNKNOWN
    }

    private fun isSafeRelativeBuildPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || DRIVE_PREFIX.matches(path)) return false
        return path.split('/').none { it.isBlank() || it == "." || it == ".." || '$' in it }
    }

    private fun gradleOwnership(
        sourceRoot: VirtualFile,
        importedModule: Module,
        resolver: ProjectFileResolver,
        declaredGradlePath: String? = null,
    ): GradleOwnership {
        val registeredBoundary = resolver.registeredRoots()
            .asSequence()
            .filter { VfsUtilCore.isAncestor(it.root, sourceRoot, false) }
            .maxByOrNull { it.root.path.length }
            ?.root
            ?: sourceRoot
        val ancestors = generateSequence(sourceRoot as VirtualFile?) { current ->
            current.parent?.takeIf { VfsUtilCore.isAncestor(registeredBoundary, it, false) }
        }.toList()
        val moduleRoot = ancestors.firstOrNull { candidate ->
            BUILD_FILE_NAMES.any { candidate.findChild(it)?.isDirectory == false }
        } ?: ancestors.firstOrNull { it.name == "src" }
            ?.parent
            ?: sourceRoot
        val buildRoot = generateSequence(moduleRoot as VirtualFile?) { current ->
            current.parent?.takeIf { VfsUtilCore.isAncestor(registeredBoundary, it, false) }
        }.firstOrNull { candidate ->
            SETTINGS_FILE_NAMES.any { candidate.findChild(it)?.isDirectory == false }
        } ?: registeredBoundary
        val buildLocator = resolver.locatorPath(buildRoot, importedModule)
            ?.trim('/')
            ?.ifBlank { "." }
            ?: "external-${CanonicalDiscoveryJson.sha256(buildRoot.path).take(12)}"
        val relativeModule = VfsUtilCore.getRelativePath(moduleRoot, buildRoot, '/')
            ?.trim('/')
            .orEmpty()
        val gradlePath = declaredGradlePath ?: if (relativeModule.isBlank()) {
            ":"
        } else {
            ":${relativeModule.replace('/', ':')}"
        }
        val moduleId = if (gradlePath == ":") {
            importedModule.name
        } else {
            "gradle:$buildLocator#$gradlePath"
        }
        return GradleOwnership(
            buildId = "build:$buildLocator",
            moduleId = moduleId,
            moduleRoot = moduleRoot,
            gradlePath = gradlePath,
        )
    }

    private fun inferSourceSetFromPath(file: VirtualFile, scanRoot: VirtualFile): String {
        val relative = VfsUtilCore.getRelativePath(file, scanRoot, '/').orEmpty()
        return Regex("""(?:^|/)src/([^/]+)(?:/|$)""").find(relative)?.groupValues?.get(1)
            ?: if (file.extension?.lowercase() in setOf("xml", "properties")) "resource" else "main"
    }

    private fun inspectJvmSyntax(
        file: VirtualFile,
        language: SourceLanguage,
    ): JvmSyntaxInspection {
        if (language != SourceLanguage.JAVA &&
            language != SourceLanguage.KOTLIN &&
            language != SourceLanguage.GROOVY
        ) {
            return JvmSyntaxInspection.NotApplicable
        }
        return cancellableRead {
            val psiFile = PsiManager.getInstance(project).findFile(file)
                ?: return@cancellableRead JvmSyntaxInspection.ParserUnavailable
            val actualLanguage = psiFile.language.id.lowercase()
            val expectedLanguage = language.name.lowercase()
            if (actualLanguage != expectedLanguage) {
                return@cancellableRead JvmSyntaxInspection.ParserUnavailable
            }
            val syntax = PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)
                ?: return@cancellableRead JvmSyntaxInspection.Valid
            val document = psiFile.viewProvider.document
            val offset = syntax.textOffset.coerceAtLeast(0)
            val line = document?.getLineNumber(offset)?.plus(1)
            val lineStart = line?.let { document.getLineStartOffset(it - 1) }
            JvmSyntaxInspection.Error(
                description = syntax.errorDescription,
                line = line,
                column = lineStart?.let { offset - it + 1 },
            )
        }
    }

    private fun sourceDiagnostic(
        reasonCode: String,
        severity: DiagnosticSeverity,
        message: String,
        nextStep: String,
        relativePath: String,
        fingerprint: String,
        line: Int? = null,
        column: Int? = null,
    ): DiscoveryDiagnostic = DiscoveryDiagnostic(
        id = CanonicalDiscoveryJson.sha256(
            listOf(reasonCode, relativePath, line.orEmpty(), message).joinToString("\u0000"),
        ),
        severity = severity,
        category = DiagnosticCategory.INDEX,
        reasonCode = reasonCode,
        message = message,
        nextStep = nextStep,
        sourceLocator = SourceLocator(
            relativePath = relativePath,
            line = line,
            column = column,
            revisionFingerprint = fingerprint,
        ),
    )

    private fun Int?.orEmpty(): String = this?.toString().orEmpty()

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private fun affectsApplicationGraph(event: VFileEvent): Boolean {
        val eventFile = event.file
        if (eventFile != null && !ProjectFileResolver.getInstance(project).contains(eventFile)) {
            return false
        }
        if (eventFile == null) {
            val roots = ProjectFileResolver.getInstance(project).registeredRoots()
            val path = event.path.replace('\\', '/')
            if (roots.none { root ->
                    val rootPath = root.root.path.trimEnd('/')
                    path == rootPath || path.startsWith("$rootPath/")
                }
            ) {
                return false
            }
        }
        val path = event.path.replace('\\', '/')
        if (path.split('/').any(EXCLUDED_DIRECTORY_NAMES::contains) &&
            !isInsideConfiguredSourceRoot(eventFile, path)
        ) {
            return false
        }
        if (event.file?.isDirectory == true) {
            return true
        }
        if (path.substringAfterLast('/') in GRADLE_CONFIGURATION_FILE_NAMES) {
            return true
        }
        return languageFor(path.substringAfterLast('.', missingDelimiterValue = "")) != null
    }

    private fun isInsideConfiguredSourceRoot(file: VirtualFile?, path: String): Boolean =
        ModuleManager.getInstance(project).modules.any { module ->
            ModuleRootManager.getInstance(module).sourceRoots.any { sourceRoot ->
                if (file != null) {
                    VfsUtilCore.isAncestor(sourceRoot, file, false)
                } else {
                    val rootPath = sourceRoot.path.replace('\\', '/').trimEnd('/')
                    path == rootPath || path.startsWith("$rootPath/")
                }
            }
        }

    private data class CandidateInventory(
        val candidates: List<CandidateFile>,
        val stamps: List<FileStamp>,
        val rootKeys: List<String>,
        val sourceRootKeys: List<String>,
        val totalCandidateFiles: Int,
        val candidateFilesByModule: Map<String, Int>,
        val excludedFiles: Int,
        val excludedBytes: Long,
        val sourceRootCountByModule: Map<String, Int>,
        val discoveredSourceRootCountByModule: Map<String, Int>,
        val fallbackRootCountByModule: Map<String, Int>,
        val buildIdsByModule: Map<String, List<String>>,
        val moduleAnchors: Map<String, ModuleAnchor>,
        val sourceRootDiscoveryLimitReached: Boolean,
        val overlappingOwnershipFileCount: Int,
        val ambiguousOwnershipFileCount: Int,
        val ambiguousOwnershipPaths: List<String>,
        val moduleDependencies: List<ModuleDependency>,
    )

    private data class CandidateFile(
        val file: VirtualFile,
        val stamp: FileStamp,
    ) {
        val deterministicOwnerKey: String
            get() = listOf(stamp.buildId, stamp.moduleId, stamp.sourceSetId, file.path)
                .joinToString("\u0000")

        fun isPreferredTo(other: CandidateFile): Boolean =
            ownershipPreferenceComparedTo(other) > 0

        fun ownershipPreferenceComparedTo(other: CandidateFile): Int {
            compareValues(
                if (stamp.explicitSourceRoot) 1 else 0,
                if (other.stamp.explicitSourceRoot) 1 else 0,
            ).takeIf { it != 0 }?.let { return it }
            compareValues(stamp.contentRootPath.length, other.stamp.contentRootPath.length)
                .takeIf { it != 0 }
                ?.let { return it }
            return compareValues(stamp.scanRootPath.length, other.stamp.scanRootPath.length)
        }
    }

    private data class FileStamp(
        val relativePath: String,
        val modificationStamp: Long,
        val documentModificationStamp: Long?,
        val length: Long,
        val buildId: String,
        val moduleId: String,
        val sourceSetId: String,
        val language: SourceLanguage,
        val explicitSourceRoot: Boolean,
        val contentRootPath: String,
        val scanRootPath: String,
    )

    private data class CachedGraph(
        val stamps: List<FileStamp>,
        val rootKeys: List<String>,
        val sourceRootKeys: List<String>,
        val response: ApplicationGraphResponse,
    )

    private data class ScanRoot(
        val contentRoot: VirtualFile,
        val root: VirtualFile,
        val sourceSetId: String,
        val explicit: Boolean,
    )

    private data class ConventionalSourceRoot(
        val root: VirtualFile,
        val sourceSetId: String,
        val kind: ApplicationGraphSourceRootKind,
    )

    private data class ConventionalRootDiscovery(
        val roots: List<ConventionalSourceRoot>,
        val moduleRoots: List<ModuleRootDiscovery>,
        val limitReached: Boolean,
    )

    private data class ModuleRootDiscovery(
        val root: VirtualFile,
        val declaredGradlePath: String? = null,
    )

    private data class GradleOwnership(
        val buildId: String,
        val moduleId: String,
        val moduleRoot: VirtualFile,
        val gradlePath: String,
    )

    private data class ModuleAnchor(
        val sourceLocator: SourceLocator,
        val buildId: String,
        val gradlePath: String,
        val moduleRoot: String,
    )

    private data class ModuleDependency(
        val sourceModuleId: String,
        val targetModuleId: String?,
        val targetGradlePath: String,
        val sourceLocator: SourceLocator,
        val ambiguous: Boolean,
    )

    private data class ModuleDependencyReference(
        val gradlePath: String?,
        val accessor: String?,
        val offset: Int,
    )

    private data class TopologyEnrichment(
        val artifacts: List<ArtifactSnapshot>,
        val relationships: List<ArtifactRelationship>,
        val diagnostics: List<DiscoveryDiagnostic>,
    )

    private sealed interface JvmSyntaxInspection {
        data object Valid : JvmSyntaxInspection
        data object ParserUnavailable : JvmSyntaxInspection
        data object NotApplicable : JvmSyntaxInspection
        data class Error(
            val description: String,
            val line: Int?,
            val column: Int?,
        ) : JvmSyntaxInspection
    }

    private data class CachedSource(
        val stamp: FileStamp,
        val source: GraphSourceFile,
    )

    companion object {
        private const val MAX_FILES = 200_000
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_ARTIFACTS = 1_000_000
        private const val MAX_CACHED_SOURCE_BYTES = 256L * 1024 * 1024
        private const val MAX_PARSE_DIAGNOSTICS = 250
        private const val MAX_OWNERSHIP_DIAGNOSTICS = 50
        private const val MAX_SOURCE_ROOT_DISCOVERY_DIRECTORIES = 250_000
        private const val MAX_DISCOVERED_SOURCE_ROOTS = 20_000
        private const val SOURCE_SET_CONTEXT_WINDOW = 4_096
        private const val RECOVERED_GRADLE_MODULE_PREFIX = "gradle:"
        private val RESOURCE_LANGUAGES = setOf(
            SourceLanguage.XML,
            SourceLanguage.PROPERTIES,
            SourceLanguage.YAML,
            SourceLanguage.SQL,
            SourceLanguage.MIXED,
            SourceLanguage.UNKNOWN,
        )
        private val CONVENTIONAL_SOURCE_DIRECTORY_NAMES = setOf("java", "kotlin", "groovy", "resources")
        private val BUILD_FILE_NAMES = setOf("build.gradle", "build.gradle.kts")
        private val SETTINGS_FILE_NAMES = setOf("settings.gradle", "settings.gradle.kts")
        private val SOURCE_DIRECTORY_DECLARATION = Regex(
            """(?s)(?:\b(java|kotlin|groovy|resources)\s*\.\s*)?(?:setSrcDirs|srcDirs?)\s*(?:=\s*)?(?:\(([^)]*)\)|([^\r\n;}]*)?)""",
        )
        private val QUOTED_BUILD_PATH = Regex("""['"]([^'"]+)['"]""")
        private val SETTINGS_INCLUDE = Regex(
            """(?m)(?<![A-Za-z0-9_])include\s*(?:\(([^)]*)\)|([^\r\n]+))""",
        )
        private val PROJECT_DIRECTORY_ASSIGNMENTS = listOf(
            Regex(
                """project\s*\(\s*['"](:[^'"]+)['"]\s*\)\s*\.\s*projectDir\s*=\s*(?:file\s*\(\s*|layout\s*\.\s*projectDirectory\s*\.\s*dir\s*\(\s*)?['"]([^'"]+)['"]""",
            ),
            Regex(
                """project\s*\(\s*['"](:[^'"]+)['"]\s*\)\s*\.\s*projectDir\s*=\s*(?:new\s+)?File\s*\(\s*(?:rootDir|settingsDir)\s*,\s*['"]([^'"]+)['"]""",
            ),
            Regex(
                """project\s*\(\s*['"](:[^'"]+)['"]\s*\)\s*\.\s*projectDir\s*=\s*(?:rootDir|settingsDir)\s*\.\s*(?:resolve|file)\s*\(\s*['"]([^'"]+)['"]""",
            ),
        )
        private val PROJECT_DEPENDENCY = Regex(
            """\bproject\s*\(\s*(?:(?:path\s*[=:])\s*)?['"](:[^'"]+)['"]""",
        )
        private val TYPE_SAFE_PROJECT_DEPENDENCY = Regex(
            """\bprojects((?:\.[A-Za-z_][A-Za-z0-9_]*)+)""",
        )
        private val SOURCE_SET_SELECTOR = Regex(
            """(?:create|named|register)\s*\(\s*['"]([^'"]+)['"]|sourceSets\s*\[\s*['"]([^'"]+)['"]\s*]|\b(sourceSets\.)?([A-Za-z_][\w-]*)\s*\{""",
        )
        private val SOURCE_KIND_BLOCK = Regex(
            """\b(java|kotlin|groovy|resources)\s*\{""",
        )
        private val NON_SOURCE_SET_BLOCK_NAMES = setOf(
            "all",
            "application",
            "buildscript",
            "configurations",
            "dependencies",
            "java",
            "jmix",
            "kotlin",
            "plugins",
            "repositories",
            "resources",
            "sourceSets",
            "tasks",
        )
        private val DRIVE_PREFIX = Regex("""^[A-Za-z]:/""")
        private val GRADLE_CONFIGURATION_FILE_NAMES = setOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
        )
        private val EXCLUDED_DIRECTORY_NAMES = setOf(
            ".git",
            ".gradle",
            ".idea",
            "build",
            "out",
            "target",
            "node_modules",
            ".jmix",
        )

        fun getInstance(project: Project): ApplicationGraphService =
            project.getService(ApplicationGraphService::class.java)
    }
}

data class ApplicationGraphSummary(
    val artifactCount: Int,
    val relationshipCount: Int,
    val diagnosticCount: Int,
    val unresolvedRelationshipCount: Int,
    val countsByKind: Map<String, Int>,
)

data class ApplicationGraphResponse(
    val artifacts: List<org.jmixworkbench.discovery.model.ArtifactSnapshot>,
    val relationships: List<org.jmixworkbench.discovery.model.ArtifactRelationship>,
    val diagnostics: List<org.jmixworkbench.discovery.model.DiscoveryDiagnostic>,
    val summary: ApplicationGraphSummary,
    val scannedFiles: Int,
    val candidateFiles: Int,
    val excludedFiles: Int,
    val excludedBytes: Long,
    val unreadableFiles: Int,
    val reusedFiles: Int,
    val changedFiles: Int,
    val cacheHit: Boolean,
    val durationMillis: Long,
    val modules: List<ApplicationGraphModuleCoverage>,
    val indexHealth: ApplicationGraphIndexHealth,
    val snapshotDigest: String,
    val parseErrorFiles: Int = 0,
    val parserUnavailableFiles: Int = 0,
)

data class ApplicationGraphModuleCoverage(
    val moduleId: String,
    val buildIds: List<String>,
    val contentRootCount: Int,
    val candidateFileCount: Int,
    val indexedFileCount: Int,
    val sourceSets: List<String>,
    val sourceRootCount: Int = 0,
    val fallbackContentRootCount: Int = 0,
    val discoveredSourceRootCount: Int = 0,
    val moduleRoot: String = "",
    val sourceRoots: List<ApplicationGraphSourceRootCoverage> = emptyList(),
)

data class ApplicationGraphSourceRootCoverage(
    val moduleId: String,
    val relativePath: String,
    val sourceSetId: String,
    val buildId: String,
    val kind: ApplicationGraphSourceRootKind,
    val recovered: Boolean,
)

enum class ApplicationGraphSourceRootKind {
    JAVA,
    KOTLIN,
    GROOVY,
    RESOURCES,
    UNKNOWN,
}

data class ApplicationGraphIndexHealth(
    val complete: Boolean,
    val moduleCount: Int,
    val contentRootCount: Int,
    val unreadableFileCount: Int,
    val limitReached: Boolean,
    val sourceRootCount: Int = 0,
    val fallbackContentRootCount: Int = 0,
    val parseErrorFileCount: Int = 0,
    val parserUnavailableFileCount: Int = 0,
    val discoveredSourceRootCount: Int = 0,
    val recoveredModuleCount: Int = 0,
    val overlappingOwnershipFileCount: Int = 0,
    val ambiguousOwnershipFileCount: Int = 0,
    val unresolvedModuleDependencyCount: Int = 0,
)

data class SourceNavigationRequest(
    val relativePath: String,
    val line: Int?,
    val column: Int?,
    val revisionFingerprint: String,
)

data class SourceNavigationResponse(
    val success: Boolean,
    val errorCode: String?,
    val message: String,
)

data class PreparedSourceNavigation(
    val success: Boolean,
    val errorCode: String?,
    val message: String,
    val file: VirtualFile?,
    val zeroBasedLine: Int,
    val zeroBasedColumn: Int,
) {
    fun response(): SourceNavigationResponse =
        SourceNavigationResponse(success, errorCode, message)

    companion object {
        fun failure(errorCode: String, message: String): PreparedSourceNavigation =
            PreparedSourceNavigation(
                success = false,
                errorCode = errorCode,
                message = message,
                file = null,
                zeroBasedLine = 0,
                zeroBasedColumn = 0,
            )
    }
}

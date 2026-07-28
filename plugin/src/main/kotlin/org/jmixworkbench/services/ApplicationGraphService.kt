package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactOrigin
import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLanguage
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.navigation.SourceNavigationPolicy
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexInput
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexResult
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexer
import org.jmixworkbench.discovery.semantic.GraphSourceFile
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class ApplicationGraphService(
    private val project: Project,
) {
    private val cached = AtomicReference<CachedGraph?>()

    fun graph(forceRefresh: Boolean = false): ApplicationGraphResponse {
        val startedAt = System.nanoTime()
        val inventory = collectCandidates()
        val previous = cached.get()
        if (!forceRefresh && previous != null && previous.stamps == inventory.stamps) {
            return previous.response.copy(
                cacheHit = true,
                durationMillis = elapsedMillis(startedAt),
            )
        }

        val sources = inventory.candidates.mapNotNull { candidate ->
            ProgressManager.checkCanceled()
            val content = runCatching {
                String(candidate.file.contentsToByteArray(false), candidate.file.charset)
            }.getOrNull() ?: return@mapNotNull null
            GraphSourceFile(
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
                fingerprint = CanonicalDiscoveryJson.sha256(content),
            )
        }

        val graph = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                files = sources,
                maxFiles = MAX_FILES,
                maxFileBytes = MAX_FILE_BYTES,
                maxArtifacts = MAX_ARTIFACTS,
            ),
        )
        val response = graph.toResponse(
            scannedFiles = sources.size,
            candidateFiles = inventory.candidates.size,
            excludedFiles = inventory.excludedFiles,
            excludedBytes = inventory.excludedBytes,
            cacheHit = false,
            durationMillis = elapsedMillis(startedAt),
        )
        cached.set(CachedGraph(inventory.stamps, response))
        return response
    }

    fun invalidate() {
        cached.set(null)
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
        val baseDir = project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)
            ?: return PreparedSourceNavigation.failure(
                "JVW-NAVIGATION-PROJECT-MISSING",
                "The project root is unavailable.",
            )
        val file = baseDir.findFileByRelativePath(locator.relativePath)
            ?: return PreparedSourceNavigation.failure(
                "JVW-NAVIGATION-SOURCE-MISSING",
                "The indexed source file no longer exists.",
            )
        if (file.isDirectory || !VfsUtilCore.isAncestor(baseDir, file, false)) {
            return PreparedSourceNavigation.failure(
                "JVW-NAVIGATION-PATH-REJECTED",
                "The requested source is outside the open project.",
            )
        }
        val currentFingerprint = runCatching {
            CanonicalDiscoveryJson.sha256(String(file.contentsToByteArray(false), file.charset))
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
        val baseDir = project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)
            ?: return CandidateInventory(emptyList(), emptyList(), 0, 0)
        val fileIndex = ProjectFileIndex.getInstance(project)
        val candidatesByPath = linkedMapOf<String, CandidateFile>()
        var excludedFiles = 0
        var excludedBytes = 0L

        ModuleManager.getInstance(project).modules.sortedBy(Module::getName).forEach { module ->
            ProgressManager.checkCanceled()
            ModuleRootManager.getInstance(module).contentRoots
                .sortedBy(VirtualFile::getPath)
                .forEach { contentRoot ->
                    val relativeContentRoot = VfsUtilCore.getRelativePath(contentRoot, baseDir, '/')
                        ?: return@forEach
                    val buildRoot = relativeContentRoot
                        .substringBefore("/src/")
                        .substringBefore("/src")
                        .ifBlank { "." }
                    visit(
                        file = contentRoot,
                        module = module,
                        buildId = "build:$buildRoot",
                        baseDir = baseDir,
                        fileIndex = fileIndex,
                        candidatesByPath = candidatesByPath,
                        onExcluded = { length ->
                            excludedFiles += 1
                            excludedBytes += length
                        },
                    )
                }
        }

        val candidates = candidatesByPath.values.sortedBy { it.stamp.relativePath }.take(MAX_FILES)
        if (candidatesByPath.size > MAX_FILES) {
            candidatesByPath.values.drop(MAX_FILES).forEach { candidate ->
                excludedFiles += 1
                excludedBytes += candidate.stamp.length
            }
        }
        return CandidateInventory(
            candidates = candidates,
            stamps = candidates.map(CandidateFile::stamp),
            excludedFiles = excludedFiles,
            excludedBytes = excludedBytes,
        )
    }

    private fun visit(
        file: VirtualFile,
        module: Module,
        buildId: String,
        baseDir: VirtualFile,
        fileIndex: ProjectFileIndex,
        candidatesByPath: MutableMap<String, CandidateFile>,
        onExcluded: (Long) -> Unit,
    ) {
        ProgressManager.checkCanceled()
        if (file.isDirectory) {
            if (file != baseDir &&
                (file.name in EXCLUDED_DIRECTORY_NAMES || fileIndex.isExcluded(file))
            ) {
                return
            }
            file.children.sortedBy(VirtualFile::getName).forEach { child ->
                visit(child, module, buildId, baseDir, fileIndex, candidatesByPath, onExcluded)
            }
            return
        }

        val language = languageFor(file.extension) ?: return
        if (file.length > MAX_FILE_BYTES) {
            onExcluded(file.length)
            return
        }
        val relativePath = VfsUtilCore.getRelativePath(file, baseDir, '/') ?: return
        val sourceSet = when {
            fileIndex.isInTestSourceContent(file) -> "test"
            fileIndex.isInSourceContent(file) -> "main"
            else -> "resource"
        }
        val stamp = FileStamp(
            relativePath = relativePath,
            modificationStamp = file.modificationStamp,
            length = file.length,
            buildId = buildId,
            moduleId = module.name,
            sourceSetId = sourceSet,
            language = language,
        )
        candidatesByPath.putIfAbsent(relativePath, CandidateFile(file, stamp))
    }

    private fun ApplicationGraphIndexResult.toResponse(
        scannedFiles: Int,
        candidateFiles: Int,
        excludedFiles: Int,
        excludedBytes: Long,
        cacheHit: Boolean,
        durationMillis: Long,
    ): ApplicationGraphResponse {
        val digestPayload = buildString {
            artifacts.forEach { append(it.id).append('\u0000').append(it.fingerprint).append('\n') }
            relationships.forEach {
                append(it.sourceArtifactId).append('\u0000')
                    .append(it.targetArtifactId.orEmpty()).append('\u0000')
                    .append(it.type.name).append('\n')
            }
        }
        return ApplicationGraphResponse(
            artifacts = artifacts,
            relationships = relationships,
            diagnostics = diagnostics,
            summary = ApplicationGraphSummary(
                artifactCount = artifacts.size,
                relationshipCount = relationships.size,
                diagnosticCount = diagnostics.size,
                unresolvedRelationshipCount = relationships.count { it.targetArtifactId == null },
                countsByKind = artifacts
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
            cacheHit = cacheHit,
            durationMillis = durationMillis,
            snapshotDigest = CanonicalDiscoveryJson.sha256(digestPayload),
        )
    }

    private fun languageFor(extension: String?): SourceLanguage? =
        when (extension?.lowercase()) {
            "java" -> SourceLanguage.JAVA
            "kt", "kts" -> SourceLanguage.KOTLIN
            "xml", "bpmn", "bpmn20" -> SourceLanguage.XML
            "properties" -> SourceLanguage.PROPERTIES
            else -> null
        }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private data class CandidateInventory(
        val candidates: List<CandidateFile>,
        val stamps: List<FileStamp>,
        val excludedFiles: Int,
        val excludedBytes: Long,
    )

    private data class CandidateFile(
        val file: VirtualFile,
        val stamp: FileStamp,
    )

    private data class FileStamp(
        val relativePath: String,
        val modificationStamp: Long,
        val length: Long,
        val buildId: String,
        val moduleId: String,
        val sourceSetId: String,
        val language: SourceLanguage,
    )

    private data class CachedGraph(
        val stamps: List<FileStamp>,
        val response: ApplicationGraphResponse,
    )

    companion object {
        private const val MAX_FILES = 50_000
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_ARTIFACTS = 250_000
        private val RESOURCE_LANGUAGES = setOf(SourceLanguage.XML, SourceLanguage.PROPERTIES)
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
    val cacheHit: Boolean,
    val durationMillis: Long,
    val snapshotDigest: String,
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

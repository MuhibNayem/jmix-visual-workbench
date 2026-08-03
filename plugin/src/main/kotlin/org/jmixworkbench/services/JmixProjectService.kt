package org.jmixworkbench.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.project.Project
import org.jmixworkbench.model.DatabaseType
import org.jmixworkbench.model.ProjectConfig
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Detects and manages Jmix project configuration.
 * Scans build.gradle for Jmix dependencies, resolves base package,
 * source roots, and database type.
 */
@Service(Service.Level.PROJECT)
class JmixProjectService(private val project: Project) : Disposable {

    @Volatile private var cachedConfig: ProjectConfig? = null
    @Volatile private var cachedBuildCandidates: List<BuildCandidate>? = null
    @Volatile private var cachedJmixProject: Boolean? = null
    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { event -> event.path.substringAfterLast('/') in DETECTION_FILE_NAMES }) {
                        invalidateDetection()
                    }
                }
            },
        )
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    invalidateDetection()
                }
            },
        )
    }


    @Synchronized
    fun getConfig(): ProjectConfig? {
        cachedConfig?.let { return it }
        return detectConfig()
    }

    fun refresh() = invalidateDetection()

    @Synchronized
    fun isJmixProject(): Boolean =
        cachedJmixProject ?: projectBuildFiles()
            .any { isJmixBuild(it.content) }
            .also { cachedJmixProject = it }
    @Synchronized
    private fun invalidateDetection() {
        cachedConfig = null
        cachedBuildCandidates = null
        cachedJmixProject = null
    }

    override fun dispose() {
        invalidateDetection()
    }


    /**
     * Resolves the project id that governs a selected generation module.
     *
     * Composite and multi-module builds can declare the Jmix extension in a
     * module build file instead of the workspace root. The closest declaration
     * wins; the root configuration is the safe fallback.
     */
    fun projectIdForModule(modulePrefix: String): String? {
        governingBuilds(modulePrefix)
            .asSequence()
            .mapNotNull { detectProjectId(it.content) }
            .firstOrNull()
            ?.let { return it }
        return getConfig()?.projectId
    }

    private fun detectConfig(): ProjectConfig? {
        val basePath = project.basePath ?: return null
        val builds = projectBuildFiles()
        val primaryBuild = builds.firstOrNull { candidate ->
            candidate.modulePrefix.isEmpty() && isJmixBuild(candidate.content)
        } ?: builds.firstOrNull { isJmixBuild(it.content) }
            ?: return null
        val combinedBuildContent = builds.joinToString("\n") { it.content }
        val content = primaryBuild.content

        val basePackage = detectBasePackageAcrossProject(primaryBuild.directory)
            ?: detectBasePackageFromApplicationGraph()
            ?: detectBasePackage(content)
            ?: detectBasePackageAcrossProject(Paths.get(basePath))
            ?: "com.example.app"
        val dbType = detectDatabaseType(combinedBuildContent)
        val jmixVersion = detectJmixVersion(primaryBuild.content)
            ?: builds.asSequence()
            .mapNotNull { detectJmixVersion(it.content) }
            .firstOrNull()
            ?: "2.4.0"
        val projectId = detectProjectId(content)

        val config = ProjectConfig(
            projectRoot = basePath,
            basePackage = basePackage,
            jmixVersion = jmixVersion,
            projectId = projectId,
            databaseType = dbType,
        )
        cachedConfig = config
        return config
    }

    @Synchronized
    private fun projectBuildFiles(): List<BuildCandidate> {
        val basePath = project.basePath?.let(Paths::get) ?: return emptyList()
        cachedBuildCandidates?.let { return it }
        if (!Files.isDirectory(basePath)) return emptyList()
        val normalizedBasePath = basePath.toAbsolutePath().normalize()
        val candidatesByPath = linkedMapOf<Path, BuildCandidate>()
        val seedRoots = buildList {
            add(normalizedBasePath)
            ProjectFileResolver.getInstance(project).registeredRoots()
                .asSequence()
                .filter(RegisteredRoot::external)
                .map { it.root.toNioPath().toAbsolutePath().normalize() }
                .forEach(::add)
        }
        seedRoots.flatMap(::discoverCompositeBuildRoots).distinct().forEach { buildRoot ->
            discoverBuildFiles(buildRoot).forEach { path ->
                val normalizedPath = path.toAbsolutePath().normalize()
                candidatesByPath.putIfAbsent(
                    normalizedPath,
                    BuildCandidate(
                        modulePrefix = normalizedBasePath.relativize(normalizedPath.parent)
                            .toString()
                            .replace('\\', '/')
                            .removeSuffix("."),
                        directory = normalizedPath.parent,
                        content = runCatching { Files.readString(normalizedPath) }.getOrDefault(""),
                    ),
                )
            }
        }
        return candidatesByPath.values.sortedWith(
            compareBy<BuildCandidate> { it.modulePrefix.count { char -> char == '/' } }
                .thenBy(BuildCandidate::modulePrefix),
        ).also { cachedBuildCandidates = it }
    }
    private fun discoverBuildFiles(buildRoot: Path): List<Path> {
        val files = mutableListOf<Path>()
        runCatching {
            Files.walkFileTree(
                buildRoot,
                emptySet(),
                MAX_BUILD_SCAN_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult =
                        if (directory != buildRoot && directory.fileName.toString() in EXCLUDED_DIRECTORIES) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attributes.isRegularFile && file.fileName.toString() in BUILD_FILE_NAMES) {
                            files.add(file)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        return files
    }


    private fun discoverCompositeBuildRoots(projectRoot: Path): List<Path> {
        val pending = ArrayDeque<Path>()
        val discovered = linkedSetOf<Path>()
        pending.addLast(projectRoot)
        while (pending.isNotEmpty() && discovered.size < MAX_COMPOSITE_BUILDS) {
            val candidate = pending.removeFirst().toAbsolutePath().normalize()
            if (!Files.isDirectory(candidate) || !discovered.add(candidate)) continue
            SETTINGS_FILE_NAMES.forEach { fileName ->
                val settings = candidate.resolve(fileName)
                if (!Files.isRegularFile(settings)) return@forEach
                val content = runCatching { Files.readString(settings) }.getOrDefault("")
                INCLUDE_BUILD_PATTERNS.forEach { pattern ->
                    pattern.findAll(content).forEach { match ->
                        val included = candidate.resolve(match.groupValues[1]).normalize()
                        if (Files.isDirectory(included) && included !in discovered) {
                            pending.addLast(included)
                        }
                    }
                }
            }
        }
        return discovered.toList()
    }

    private fun governingBuilds(modulePrefix: String): List<BuildCandidate> {
        val normalizedPrefix = normalizeModulePrefix(modulePrefix)
        val builds = projectBuildFiles()
        val physicalTarget = ProjectFileResolver.getInstance(project)
            .resolveTarget(normalizedPrefix)
            ?.let { target ->
                target.root.toNioPath()
                    .resolve(target.relativePath)
                    .toAbsolutePath()
                    .normalize()
            }
        if (physicalTarget != null) {
            val physicalMatches = builds
                .filter { physicalTarget.startsWith(it.directory) }
                .sortedWith(
                    compareByDescending<BuildCandidate> { it.directory.nameCount }
                        .thenByDescending { it.directory.toString() },
                )
            if (physicalMatches.isNotEmpty()) return physicalMatches
        }
        return builds
            .filter { candidate ->
                candidate.modulePrefix.isEmpty() ||
                    normalizedPrefix == candidate.modulePrefix ||
                    normalizedPrefix.startsWith("${candidate.modulePrefix}/")
            }
            .sortedWith(
                compareByDescending<BuildCandidate> { it.modulePrefix.length }
                    .thenByDescending { it.modulePrefix },
            )
    }

    private fun detectBasePackage(buildContent: String): String? {
        // Try group = 'com.example'
        val groupMatch = Regex("""group\s*[=:]\s*['"]([^'"]+)['"]""").find(buildContent)
        if (groupMatch != null) {
            return groupMatch.groupValues[1]
        }

        return null
    }

    private fun detectBasePackageAcrossProject(basePath: Path): String? {
        if (!Files.isDirectory(basePath)) return null
        val candidates: List<Pair<Int, String>> = runCatching {
            Files.find(
                basePath,
                MAX_SOURCE_SCAN_DEPTH,
                { path, attributes ->
                    attributes.isRegularFile &&
                        path.fileName.toString().substringAfterLast('.', "") in SOURCE_EXTENSIONS &&
                        SOURCE_ROOT_MARKERS.any {
                            marker -> path.toString().replace('\\', '/').contains(marker)
                        } &&
                        path.none { part -> part.toString() in EXCLUDED_DIRECTORIES }
                },
            ).use { paths ->
                paths.limit(MAX_SOURCE_CANDIDATES).iterator().asSequence().mapNotNull { path ->
                    val source = runCatching { Files.readString(path) }.getOrDefault("")
                    val packageName = PACKAGE_DECLARATION.find(source)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    val priority = when {
                        JMIX_APPLICATION.containsMatchIn(source) -> 0
                        JMIX_ENTITY.containsMatchIn(source) -> 1
                        else -> 2
                    }
                    priority to packageName.replace(
                        Regex("""\.(entity|view|screen|security|service|bean|component)$"""),
                        "",
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
        return candidates.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })
            .firstOrNull()
            ?.second
    }

    private fun detectBasePackageFromApplicationGraph(): String? =
        runCatching {
            ApplicationGraphService.getInstance(project).graph().artifacts
                .asSequence()
                .filter { it.kind in JVM_TYPE_ARTIFACTS }
                .mapNotNull { artifact ->
                    val qualifiedName = artifact.semanticKey.substringBefore('#')
                    if (!QUALIFIED_TYPE.matches(qualifiedName)) return@mapNotNull null
                    val packageName = qualifiedName.substringBeforeLast('.', "")
                    if (packageName.isBlank()) return@mapNotNull null
                    val priority = when {
                        artifact.displayName.endsWith("Application") -> 0
                        artifact.kind == org.jmixworkbench.discovery.model.ArtifactKind.ENTITY -> 1
                        else -> 2
                    }
                    priority to packageName.replace(
                        Regex("""\.(entity|view|screen|security|service|bean|component)$"""),
                        "",
                    )
                }
                .sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })
                .firstOrNull()
                ?.second
        }.getOrNull()

    private fun detectDatabaseType(buildContent: String): DatabaseType {
        return when {
            buildContent.contains("postgresql") || buildContent.contains("postgres") -> DatabaseType.POSTGRES
            buildContent.contains("mysql") || buildContent.contains("mariadb") -> DatabaseType.MYSQL
            buildContent.contains("mssql") || buildContent.contains("sqlserver") -> DatabaseType.MSSQL
            buildContent.contains("oracle") -> DatabaseType.ORACLE
            buildContent.contains("hsqldb") -> DatabaseType.HSQLDB
            else -> DatabaseType.POSTGRES
        }
    }

    private fun detectJmixVersion(buildContent: String): String? {
        return JMIX_VERSION_PATTERNS.asSequence()
            .mapNotNull { it.find(buildContent)?.groupValues?.get(1) }
            .firstOrNull()
    }

    companion object {
        private const val MAX_BUILD_SCAN_DEPTH = 6
        private const val MAX_SOURCE_SCAN_DEPTH = 24
        private const val MAX_SOURCE_CANDIDATES = 25_000L
        private const val MAX_COMPOSITE_BUILDS = 64
        private val BUILD_FILE_NAMES = setOf("build.gradle", "build.gradle.kts")
        private val SETTINGS_FILE_NAMES = setOf("settings.gradle", "settings.gradle.kts")
        private val DETECTION_FILE_NAMES = BUILD_FILE_NAMES + SETTINGS_FILE_NAMES + setOf(
            "gradle.properties",
            "libs.versions.toml",
        )
        private val SOURCE_EXTENSIONS = setOf("java", "kt")
        private val SOURCE_ROOT_MARKERS = setOf("/src/main/java/", "/src/main/kotlin/")
        private val EXCLUDED_DIRECTORIES = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules", "out", "target",
        )
        private val PACKAGE_DECLARATION = Regex("""\bpackage\s+([A-Za-z_][\w.]*)\s*;?""")
        private val QUALIFIED_TYPE = Regex("""[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+""")
        private val JVM_TYPE_ARTIFACTS = setOf(
            org.jmixworkbench.discovery.model.ArtifactKind.SOURCE_TYPE,
            org.jmixworkbench.discovery.model.ArtifactKind.ENTITY,
            org.jmixworkbench.discovery.model.ArtifactKind.DTO,
            org.jmixworkbench.discovery.model.ArtifactKind.ENUM,
            org.jmixworkbench.discovery.model.ArtifactKind.VIEW_CONTROLLER,
            org.jmixworkbench.discovery.model.ArtifactKind.REPOSITORY,
            org.jmixworkbench.discovery.model.ArtifactKind.BUSINESS_RULE,
            org.jmixworkbench.discovery.model.ArtifactKind.SERVICE,
            org.jmixworkbench.discovery.model.ArtifactKind.REST_CONTROLLER,
            org.jmixworkbench.discovery.model.ArtifactKind.VALIDATOR,
            org.jmixworkbench.discovery.model.ArtifactKind.RESOURCE_ROLE,
            org.jmixworkbench.discovery.model.ArtifactKind.ROW_ROLE,
        )
        private val JMIX_APPLICATION = Regex("""@\s*(?:[\w.]+\.)?(?:JmixApplication|SpringBootApplication)\b""")
        private val JMIX_ENTITY = Regex("""@\s*(?:[\w.]+\.)?JmixEntity\b""")
        private val INCLUDE_BUILD_PATTERNS = listOf(
            Regex("""\bincludeBuild\s*\(\s*['"]([^'"]+)['"]\s*\)"""),
            Regex("""\bincludeBuild\s+['"]([^'"]+)['"]"""),
        )
        private val JMIX_VERSION_PATTERNS = listOf(
            Regex("""\bid\s*\(\s*['"]io\.jmix(?:\.[^'"]*)?['"]\s*\)\s*version\s*['"]([^'"]+)['"]"""),
            Regex("""\bid\s+['"]io\.jmix(?:\.[^'"]*)?['"]\s+version\s+['"]([^'"]+)['"]"""),
            Regex("""\bio\.jmix(?:\.[\w-]+)*\s+version\s+['"]([^'"]+)['"]"""),
            Regex("""\bjmixVersion\s*[=:]\s*['"]([^'"]+)['"]"""),
        )
        private val DOTTED_PROJECT_ID = Regex(
            """(?m)\bjmix\.projectId\s*(?:=|\.set\s*\()\s*['"]([A-Za-z0-9_]+)['"]\s*\)?""",
        )
        private val BLOCK_PROJECT_ID = Regex(
            """(?m)^\s*projectId\s*(?:=|\.set\s*\()\s*['"]([A-Za-z0-9_]+)['"]\s*\)?""",
        )

        internal fun detectProjectId(buildContent: String): String? {
            DOTTED_PROJECT_ID.find(buildContent)?.groupValues?.get(1)?.let { return it }
            if (!Regex("""\bjmix\s*\{""").containsMatchIn(buildContent)) return null
            return BLOCK_PROJECT_ID.find(buildContent)?.groupValues?.get(1)
        }

        private fun isJmixBuild(content: String): Boolean =
            content.contains("io.jmix") || content.contains("jmix-gradle-plugin")

        private fun normalizeModulePrefix(modulePrefix: String): String {
            val slashSeparated = modulePrefix.trim()
                .replace('\\', '/')
                .let { value ->
                    if (value.startsWith(':')) value.replace(':', '/') else value
                }
                .trim('/')
                .substringBefore("/src/main/")
                .substringBefore("/src/test/")
            return runCatching {
                Paths.get(slashSeparated.ifBlank { "." })
                    .normalize()
                    .toString()
                    .replace('\\', '/')
                    .removeSuffix(".")
            }.getOrDefault(slashSeparated)
        }

        fun getInstance(project: Project): JmixProjectService =
            project.getService(JmixProjectService::class.java)
    }
}

private data class BuildCandidate(
    val modulePrefix: String,
    val directory: Path,
    val content: String,
)

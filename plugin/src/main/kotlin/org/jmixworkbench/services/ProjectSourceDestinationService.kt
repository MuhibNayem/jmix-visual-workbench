package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import org.jetbrains.jps.model.java.JavaResourceRootType

/**
 * Resolves writable source destinations from both the imported IntelliJ model
 * and source ownership recovered by the enterprise application index.
 *
 * This prevents visual workspaces from silently collapsing a composite or
 * partially imported Gradle build into the aggregator module.
 */
@Service(Service.Level.PROJECT)
class ProjectSourceDestinationService(private val project: Project) {
    fun productionJava(graph: ApplicationGraphResponse): List<ProjectSourceDestination> =
        resolve(graph, ProjectSourceDestinationKind.PRODUCTION_JAVA)

    fun productionKotlin(graph: ApplicationGraphResponse): List<ProjectSourceDestination> =
        resolve(graph, ProjectSourceDestinationKind.PRODUCTION_KOTLIN)

    fun productionResources(graph: ApplicationGraphResponse): List<ProjectSourceDestination> =
        resolve(graph, ProjectSourceDestinationKind.PRODUCTION_RESOURCES)

    fun testJava(graph: ApplicationGraphResponse): List<ProjectSourceDestination> =
        resolve(graph, ProjectSourceDestinationKind.TEST_JAVA)

    private fun resolve(
        graph: ApplicationGraphResponse,
        kind: ProjectSourceDestinationKind,
    ): List<ProjectSourceDestination> {
        val resolver = ProjectFileResolver.getInstance(project)
        val fileIndex = ProjectFileIndex.getInstance(project)
        val imported = ModuleManager.getInstance(project).modules
            .sortedBy(Module::getName)
            .flatMap { module ->
                val rootManager = ModuleRootManager.getInstance(module)
                val explicit = when (kind) {
                    ProjectSourceDestinationKind.PRODUCTION_RESOURCES ->
                        rootManager.getSourceRoots(JavaResourceRootType.RESOURCE)
                            .mapNotNull { resolver.locatorPath(it, module) }

                    ProjectSourceDestinationKind.PRODUCTION_JAVA ->
                        rootManager.sourceRoots
                            .filter {
                                fileIndex.isInSourceContent(it) &&
                                    !fileIndex.isInTestSourceContent(it)
                            }
                            .mapNotNull { resolver.locatorPath(it, module) }
                            .mapNotNull(::productionJavaRoot)

                    ProjectSourceDestinationKind.PRODUCTION_KOTLIN ->
                        rootManager.sourceRoots
                            .filter {
                                fileIndex.isInSourceContent(it) &&
                                    !fileIndex.isInTestSourceContent(it)
                            }
                            .mapNotNull { resolver.locatorPath(it, module) }
                            .mapNotNull(::productionKotlinRoot)

                    ProjectSourceDestinationKind.TEST_JAVA ->
                        rootManager.sourceRoots
                            .filter(fileIndex::isInTestSourceContent)
                            .mapNotNull { resolver.locatorPath(it, module) }
                            .mapNotNull(::testJavaRoot)
                }
                val conventional = rootManager.contentRoots.mapNotNull { contentRoot ->
                    resolver.locatorPath(contentRoot, module)?.let { root ->
                        joinRoot(root, kind.conventionalPath)
                    }
                }
                explicit.ifEmpty { conventional }.distinct().map { sourceRoot ->
                    ProjectSourceDestination(
                        moduleId = module.name,
                        sourceRoot = sourceRoot,
                        kind = kind,
                        provenance = ProjectSourceDestinationProvenance.IMPORTED_MODEL,
                    )
                }
            }
        val declaredByGraph = graph.modules.asSequence()
            .flatMap { module ->
                val exact = module.sourceRoots.asSequence()
                    .filter { root ->
                        when (kind) {
                            ProjectSourceDestinationKind.PRODUCTION_JAVA ->
                                root.kind == ApplicationGraphSourceRootKind.JAVA &&
                                    root.sourceSetId.isProductionSourceSet()

                            ProjectSourceDestinationKind.PRODUCTION_KOTLIN ->
                                root.kind == ApplicationGraphSourceRootKind.KOTLIN &&
                                    root.sourceSetId.isProductionSourceSet()

                            ProjectSourceDestinationKind.PRODUCTION_RESOURCES ->
                                root.kind == ApplicationGraphSourceRootKind.RESOURCES &&
                                    root.sourceSetId.isProductionSourceSet()

                            ProjectSourceDestinationKind.TEST_JAVA ->
                                root.kind == ApplicationGraphSourceRootKind.JAVA &&
                                    !root.sourceSetId.isProductionSourceSet()
                        }
                    }
                    .map { root ->
                        ProjectSourceDestination(
                            moduleId = module.moduleId,
                            sourceRoot = root.relativePath,
                            kind = kind,
                            provenance = ProjectSourceDestinationProvenance.RECOVERED_INDEX,
                        )
                    }
                    .toList()
                val fallback = module.moduleRoot.takeIf(String::isNotBlank)?.let { moduleRoot ->
                    ProjectSourceDestination(
                        moduleId = module.moduleId,
                        sourceRoot = joinRoot(moduleRoot, kind.conventionalPath),
                        kind = kind,
                        provenance = ProjectSourceDestinationProvenance.RECOVERED_INDEX,
                    )
                }
                (exact.ifEmpty { listOfNotNull(fallback) }).asSequence()
            }
            .toList()
        val inferredFromArtifacts = graph.artifacts.asSequence()
            .mapNotNull { artifact ->
                inferSiblingSourceRoot(
                    relativePath = artifact.sourceLocator.relativePath,
                    targetKind = kind,
                )?.let { sourceRoot ->
                    ProjectSourceDestination(
                        moduleId = artifact.owner.moduleId,
                        sourceRoot = sourceRoot,
                        kind = kind,
                        provenance = ProjectSourceDestinationProvenance.RECOVERED_INDEX,
                    )
                }
            }
            .toList()
        return (imported + declaredByGraph + inferredFromArtifacts)
            .distinctBy { "${it.moduleId}\u0000${it.sourceRoot}\u0000${it.kind}" }
            .sortedWith(
                compareBy<ProjectSourceDestination>(ProjectSourceDestination::moduleId)
                    .thenByDescending { it.sourceRoot == it.kind.conventionalPath }
                    .thenByDescending { "/src/main/" in it.sourceRoot }
                    .thenByDescending { it.provenance == ProjectSourceDestinationProvenance.IMPORTED_MODEL }
                    .thenBy(ProjectSourceDestination::sourceRoot),
            )
    }

    companion object {
        internal fun inferSiblingSourceRoot(
            relativePath: String,
            targetKind: ProjectSourceDestinationKind,
        ): String? {
            val normalized = relativePath.replace('\\', '/').trim('/')
            val match = CONVENTIONAL_SOURCE_PATH.find(normalized) ?: return null
            val prefix = match.groupValues[1].trim('/')
            val observedSourceSet = match.groupValues[2]
            val targetSourceSet = when (targetKind) {
                ProjectSourceDestinationKind.TEST_JAVA -> "test"
                else -> observedSourceSet.takeIf { it.isProductionSourceSet() } ?: return null
            }
            return joinRoot(prefix, "src/$targetSourceSet/${targetKind.directory}")
        }

        private fun productionJavaRoot(sourceRoot: String): String? {
            val normalized = sourceRoot.trimEnd('/', '\\')
            return when {
                normalized.endsWith("/java") || normalized == "java" -> normalized
                normalized.endsWith("/kotlin") ->
                    normalized.removeSuffix("/kotlin") + "/java"
                normalized.endsWith("/groovy") ->
                    normalized.removeSuffix("/groovy") + "/java"
                else -> null
            }
        }

        private fun testJavaRoot(sourceRoot: String): String? {
            val normalized = sourceRoot.trimEnd('/', '\\')
            return when {
                normalized.endsWith("/java") || normalized == "java" -> normalized
                normalized.endsWith("/kotlin") ->
                    normalized.removeSuffix("/kotlin") + "/java"
                normalized.endsWith("/groovy") ->
                    normalized.removeSuffix("/groovy") + "/java"
                else -> null
            }
        }

        private fun joinRoot(prefix: String, suffix: String): String =
            listOf(prefix.trim('/'), suffix.trim('/')).filter(String::isNotBlank).joinToString("/")

        internal fun String.isProductionSourceSet(): Boolean =
            !contains("test", ignoreCase = true) &&
                !contains("fixture", ignoreCase = true) &&
                !contains("benchmark", ignoreCase = true)

        private val CONVENTIONAL_SOURCE_PATH =
            Regex("""^(.*?)(?:^|/)src/([^/]+)/(?:java|kotlin|groovy|resources)(?:/|$)""")

        private fun productionKotlinRoot(sourceRoot: String): String? {
            val normalized = sourceRoot.trimEnd('/', '\\')
            return when {
                normalized.endsWith("/kotlin") || normalized == "kotlin" -> normalized
                normalized.endsWith("/java") ->
                    normalized.removeSuffix("/java") + "/kotlin"
                normalized.endsWith("/groovy") ->
                    normalized.removeSuffix("/groovy") + "/kotlin"
                else -> null
            }
        }

        fun getInstance(project: Project): ProjectSourceDestinationService =
            project.getService(ProjectSourceDestinationService::class.java)
    }
}

enum class ProjectSourceDestinationKind(
    val conventionalPath: String,
    val directory: String,
) {
    PRODUCTION_JAVA("src/main/java", "java"),
    PRODUCTION_KOTLIN("src/main/kotlin", "kotlin"),
    PRODUCTION_RESOURCES("src/main/resources", "resources"),
    TEST_JAVA("src/test/java", "java"),
}

enum class ProjectSourceDestinationProvenance {
    IMPORTED_MODEL,
    RECOVERED_INDEX,
}

data class ProjectSourceDestination(
    val moduleId: String,
    val sourceRoot: String,
    val kind: ProjectSourceDestinationKind,
    val provenance: ProjectSourceDestinationProvenance,
)

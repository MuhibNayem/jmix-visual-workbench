package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson

/**
 * Resolves safe source-locator paths across every IntelliJ content root.
 *
 * Jmix composite projects may import applications or add-ons whose directories
 * are siblings of the aggregator project. Raw `../` paths are intentionally
 * forbidden by [org.jmixworkbench.discovery.model.SourceLocator], so external
 * roots receive stable, non-traversing aliases:
 *
 * `__jmix_external__/<module>-<path-digest>/src/main/java/...`
 *
 * All consumers resolve those aliases back through the current IntelliJ
 * project model. No absolute path is exposed to the web UI or accepted from it.
 */
@Service(Service.Level.PROJECT)
class ProjectFileResolver(private val project: Project) {

    fun locatorPath(file: VirtualFile, module: Module? = null): String? {
        val baseRoot = baseRoot()
        if (baseRoot != null && (baseRoot == file || VfsUtilCore.isAncestor(baseRoot, file, false))) {
            return VfsUtilCore.getRelativePath(file, baseRoot, '/')
        }
        val roots = registeredRoots()
        val selected = roots.asSequence()
            .filter { root ->
                root.external &&
                    (module == null || root.moduleId == module.name) &&
                    VfsUtilCore.isAncestor(root.root, file, false)
            }
            .maxByOrNull { it.root.path.length }
            ?: roots.asSequence()
                .filter { it.external && VfsUtilCore.isAncestor(it.root, file, false) }
                .maxByOrNull { it.root.path.length }
            ?: return null
        val relative = VfsUtilCore.getRelativePath(file, selected.root, '/') ?: return null
        return if (relative.isBlank()) selected.prefix else "${selected.prefix}/$relative"
    }

    fun resolveFile(locatorPath: String): ResolvedProjectFile? {
        val target = resolveTarget(locatorPath) ?: return null
        val file = if (target.relativePath.isBlank()) {
            target.root
        } else {
            target.root.findFileByRelativePath(target.relativePath)
        } ?: return null
        if (!VfsUtilCore.isAncestor(target.root, file, false)) return null
        return ResolvedProjectFile(
            root = target.root,
            relativePath = target.relativePath,
            file = file,
            external = target.external,
        )
    }

    fun resolveTarget(locatorPath: String): ResolvedProjectTarget? {
        val normalized = locatorPath.trim().trim('/')
        if (normalized.isBlank() || '\\' in normalized) return null
        if (normalized.split('/').any { it.isBlank() || it == "." || it == ".." }) return null

        val external = registeredRoots()
            .filter(RegisteredRoot::external)
            .sortedByDescending { it.prefix.length }
            .firstOrNull { normalized == it.prefix || normalized.startsWith("${it.prefix}/") }
        if (external != null) {
            return ResolvedProjectTarget(
                root = external.root,
                relativePath = normalized.removePrefix(external.prefix).trimStart('/'),
                external = true,
            )
        }
        if (normalized.startsWith("$EXTERNAL_PREFIX/")) return null
        val baseRoot = baseRoot() ?: return null
        return ResolvedProjectTarget(baseRoot, normalized, external = false)
    }

    fun contains(file: VirtualFile): Boolean =
        registeredRoots().any { VfsUtilCore.isAncestor(it.root, file, false) }

    fun registeredRoots(): List<RegisteredRoot> {
        val baseRoot = baseRoot()
        val roots = mutableListOf<RegisteredRoot>()
        if (baseRoot != null) {
            roots += RegisteredRoot(
                prefix = "",
                root = baseRoot,
                moduleId = null,
                external = false,
            )
        }
        ModuleManager.getInstance(project).modules
            .sortedBy(Module::getName)
            .forEach { module ->
                ModuleRootManager.getInstance(module).contentRoots
                    .sortedBy(VirtualFile::getPath)
                    .forEach { contentRoot ->
                        if (baseRoot != null && VfsUtilCore.isAncestor(baseRoot, contentRoot, false)) {
                            return@forEach
                        }
                        roots += RegisteredRoot(
                            prefix = externalPrefix(module, contentRoot),
                            root = contentRoot,
                            moduleId = module.name,
                            external = true,
                        )
                    }
            }
        return roots.distinctBy { "${it.prefix}\u0000${it.root.path}" }
    }

    private fun baseRoot(): VirtualFile? =
        project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)

    private fun externalPrefix(module: Module, contentRoot: VirtualFile): String {
        val moduleSegment = module.name
            .replace(Regex("""[^A-Za-z0-9_-]+"""), "-")
            .trim('-')
            .take(48)
            .ifBlank { "module" }
        val digest = CanonicalDiscoveryJson.sha256(contentRoot.path).take(12)
        return "$EXTERNAL_PREFIX/$moduleSegment-$digest"
    }

    companion object {
        const val EXTERNAL_PREFIX = "__jmix_external__"

        fun getInstance(project: Project): ProjectFileResolver =
            project.getService(ProjectFileResolver::class.java)
    }
}

data class RegisteredRoot(
    val prefix: String,
    val root: VirtualFile,
    val moduleId: String?,
    val external: Boolean,
)

data class ResolvedProjectTarget(
    val root: VirtualFile,
    val relativePath: String,
    val external: Boolean,
)

data class ResolvedProjectFile(
    val root: VirtualFile,
    val relativePath: String,
    val file: VirtualFile,
    val external: Boolean,
)

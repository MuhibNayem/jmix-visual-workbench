package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jmixworkbench.model.DatabaseType
import org.jmixworkbench.model.ProjectConfig

/**
 * Detects and manages Jmix project configuration.
 * Scans build.gradle for Jmix dependencies, resolves base package,
 * source roots, and database type.
 */
@Service(Service.Level.PROJECT)
class JmixProjectService(private val project: Project) {

    private var cachedConfig: ProjectConfig? = null

    fun getConfig(): ProjectConfig? {
        cachedConfig?.let { return it }
        return detectConfig()
    }

    fun refresh() {
        cachedConfig = null
    }

    fun isJmixProject(): Boolean {
        val buildFile = findBuildFile() ?: return false
        val content = String(buildFile.contentsToByteArray())
        return content.contains("io.jmix") || content.contains("jmix-gradle-plugin")
    }

    private fun detectConfig(): ProjectConfig? {
        val basePath = project.basePath ?: return null
        val buildFile = findBuildFile() ?: return null
        val content = String(buildFile.contentsToByteArray())

        val basePackage = detectBasePackage(content) ?: "com.example.app"
        val dbType = detectDatabaseType(content)
        val jmixVersion = detectJmixVersion(content) ?: "2.4.0"

        val config = ProjectConfig(
            projectRoot = basePath,
            basePackage = basePackage,
            jmixVersion = jmixVersion,
            databaseType = dbType
        )
        cachedConfig = config
        return config
    }

    private fun findBuildFile(): VirtualFile? {
        val baseDir = project.baseDir ?: return null
        return baseDir.findChild("build.gradle")
            ?: baseDir.findChild("build.gradle.kts")
    }

    private fun detectBasePackage(buildContent: String): String? {
        // Try group = 'com.example'
        val groupMatch = Regex("""group\s*[=:]\s*['"]([^'"]+)['"]""").find(buildContent)
        if (groupMatch != null) {
            return groupMatch.groupValues[1]
        }

        // Try scanning for @JmixEntity or main application class
        val baseDir = project.baseDir ?: return null
        val srcDir = baseDir.findFileByRelativePath("src/main/java") ?: return null
        return findDeepestPackage(srcDir)
    }

    private fun findDeepestPackage(dir: VirtualFile, prefix: String = ""): String? {
        val children = dir.children ?: return null
        val javaFile = children.firstOrNull { it.extension == "java" }
        if (javaFile != null) {
            val content = String(javaFile.contentsToByteArray())
            val pkgMatch = Regex("""package\s+([\w.]+);""").find(content)
            if (pkgMatch != null) {
                val pkg = pkgMatch.groupValues[1]
                // Return the base package (remove .entity, .view, etc.)
                return pkg.replace(Regex("""\.(entity|view|screen|security|service|bean|component)$"""), "")
            }
        }

        val subDirs = children.filter { it.isDirectory && it.name != "test" }
        if (subDirs.size == 1) {
            return findDeepestPackage(subDirs[0], "$prefix${subDirs[0].name}.")
        }

        return null
    }

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
        val match = Regex("""io\.jmix[.\w]*\s+version\s+['"]([^'"]+)['"]""").find(buildContent)
            ?: Regex("""jmixVersion\s*[=:]\s*['"]([^'"]+)['"]""").find(buildContent)
        return match?.groupValues?.get(1)
    }

    companion object {
        fun getInstance(project: Project): JmixProjectService =
            project.getService(JmixProjectService::class.java)
    }
}

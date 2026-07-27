package com.jmixstudio.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jmixstudio.generator.*
import com.jmixstudio.model.*
import java.io.File

/**
 * Orchestrates code generation and writes files to the project.
 * Handles: single entity generation, full CRUD generation, view generation,
 * migration generation, menu/role generation, and file system refresh.
 */
@Service(Service.Level.PROJECT)
class CodeGenerationService(private val project: Project) {

    private val log = Logger.getInstance(CodeGenerationService::class.java)

    data class GenerationResult(
        val success: Boolean,
        val filesWritten: List<String> = emptyList(),
        val errors: List<String> = emptyList()
    )

    // ─── Entity Generation ───────────────────────────────────────────────────

    fun generateEntity(entity: EntityModel, config: ProjectConfig): GenerationResult {
        return try {
            val content = EntityGenerator.generate(entity)
            val pkgPath = config.packageToPath(entity.packageName)
            val fileName = "${entity.className}.java"
            val relativePath = "${config.sourceRoot}/$pkgPath/$fileName"

            writeFile(config.projectRoot, relativePath, content)

            // Generate migration if DDL enabled
            if (entity.ddlGeneration.enabled && entity.entityType == EntityType.ENTITY) {
                val migration = MigrationGenerator.generateFromEntity(entity, config.databaseType)
                val migrationXml = MigrationGenerator.generate(migration)
                val migrationPath = "${config.changelogPath()}/001-${entity.resolvedTableName.lowercase()}.xml"
                writeFile(config.projectRoot, migrationPath, migrationXml)
            }

            // Generate data repository if requested
            if (entity.dataRepository?.enabled == true) {
                val repoContent = DataRepositoryGenerator.generate(entity)
                val repoPath = "${config.sourceRoot}/$pkgPath/${entity.className}Repository.java"
                writeFile(config.projectRoot, repoPath, repoContent)
            }

            // Generate localization
            val messages = buildEntityMessages(entity)
            if (messages.isNotEmpty()) {
                val messagesPath = "${config.resourceRoot}/${config.packageToPath(entity.packageName)}/messages.properties"
                appendFile(config.projectRoot, messagesPath, messages)
            }

            refreshVfs(config.projectRoot)
            GenerationResult(true, listOf(relativePath))
        } catch (e: Exception) {
            log.error("Entity generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── Full CRUD Generation ────────────────────────────────────────────────

    fun generateCrud(
        entity: EntityModel,
        config: ProjectConfig,
        options: CrudOrchestrator.CrudOptions = CrudOrchestrator.CrudOptions()
    ): GenerationResult {
        return try {
            val output = CrudOrchestrator.generate(entity, config, options)
            val writtenFiles = mutableListOf<String>()

            val files = listOfNotNull(
                output.entityFile,
                output.migrationFile.takeIf { options.generateMigration },
                output.listViewXml,
                output.listViewController,
                output.detailViewXml,
                output.detailViewController,
                output.menuXml,
                output.roleFile,
                output.messagesFile,
                output.dataRepositoryFile,
                output.fetchPlanFile
            )

            files.forEach { file ->
                writeFile(config.projectRoot, file.relativePath, file.content)
                writtenFiles.add(file.relativePath)
            }

            refreshVfs(config.projectRoot)
            GenerationResult(true, writtenFiles)
        } catch (e: Exception) {
            log.error("CRUD generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── View Generation ─────────────────────────────────────────────────────

    fun generateView(view: ViewModel, config: ProjectConfig): GenerationResult {
        return try {
            val writtenFiles = mutableListOf<String>()

            // XML descriptor
            val xmlContent = ViewXmlGenerator.generate(view)
            val pkgPath = config.packageToPath(view.packageName)
            val xmlPath = "${config.resourceRoot}/$pkgPath/${view.xmlFileName}"
            writeFile(config.projectRoot, xmlPath, xmlContent)
            writtenFiles.add(xmlPath)

            // Controller
            val controllerContent = ViewControllerGenerator.generate(view)
            val controllerPath = "${config.sourceRoot}/$pkgPath/${view.controllerName}.java"
            writeFile(config.projectRoot, controllerPath, controllerContent)
            writtenFiles.add(controllerPath)

            // Menu entry
            view.menuEntry?.let { menu ->
                val menuContent = MenuGenerator.generateSingleEntry(menu)
                val menuPath = "${config.resourceRoot}/${config.packageToPath(config.basePackage)}/menu.xml"
                appendFile(config.projectRoot, menuPath, menuContent)
            }

            refreshVfs(config.projectRoot)
            GenerationResult(true, writtenFiles)
        } catch (e: Exception) {
            log.error("View generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── Migration Generation ────────────────────────────────────────────────

    fun generateMigration(migration: MigrationModel, config: ProjectConfig): GenerationResult {
        return try {
            val content = MigrationGenerator.generate(migration)
            val path = "${config.changelogPath()}/${migration.changelogId}.xml"
            writeFile(config.projectRoot, path, content)
            refreshVfs(config.projectRoot)
            GenerationResult(true, listOf(path))
        } catch (e: Exception) {
            log.error("Migration generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── Role Generation ─────────────────────────────────────────────────────

    fun generateRole(role: RoleModel, config: ProjectConfig): GenerationResult {
        return try {
            val content = RoleGenerator.generate(role)
            val pkgPath = config.packageToPath("${config.basePackage}.security")
            val path = "${config.sourceRoot}/$pkgPath/${role.name}.java"
            writeFile(config.projectRoot, path, content)
            refreshVfs(config.projectRoot)
            GenerationResult(true, listOf(path))
        } catch (e: Exception) {
            log.error("Role generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── BPM Generation ──────────────────────────────────────────────────────

    fun generateBpmProcess(process: BpmGenerator.BpmProcess, config: ProjectConfig): GenerationResult {
        return try {
            val content = BpmGenerator.generate(process)
            val path = "${config.resourceRoot}/processes/${process.id}.bpmn20.xml"
            writeFile(config.projectRoot, path, content)
            refreshVfs(config.projectRoot)
            GenerationResult(true, listOf(path))
        } catch (e: Exception) {
            log.error("BPM generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── File Operations ─────────────────────────────────────────────────────

    private fun writeFile(projectRoot: String, relativePath: String, content: String) {
        val file = File(projectRoot, relativePath)
        file.parentFile?.mkdirs()

        WriteCommandAction.runWriteCommandAction(project) {
            file.writeText(content)
        }
        log.info("Generated: $relativePath")
    }

    private fun appendFile(projectRoot: String, relativePath: String, content: String) {
        val file = File(projectRoot, relativePath)
        file.parentFile?.mkdirs()

        WriteCommandAction.runWriteCommandAction(project) {
            if (file.exists()) {
                file.appendText("\n$content")
            } else {
                file.writeText(content)
            }
        }
    }

    private fun refreshVfs(projectRoot: String) {
        ApplicationManager.getApplication().invokeLater {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(projectRoot)?.refresh(true, true)
        }
    }

    private fun buildEntityMessages(entity: EntityModel): String {
        val sb = StringBuilder()
        sb.appendLine("${entity.className}=${entity.className}")
        entity.attributes.forEach { attr ->
            val caption = attr.localizedCaption
                ?: attr.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    .replaceFirstChar { it.uppercase() }
            sb.appendLine("${entity.className}.${attr.name}=$caption")
        }
        return sb.toString()
    }

    companion object {
        fun getInstance(project: Project): CodeGenerationService =
            project.getService(CodeGenerationService::class.java)
    }
}

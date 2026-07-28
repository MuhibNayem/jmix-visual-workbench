package org.jmixworkbench.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.change.SourcePreservingMerge
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.generator.*
import org.jmixworkbench.model.*

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
            val files = mutableListOf(
                GeneratedSource(relativePath, content),
            )

            // Generate migration if DDL enabled
            if (entity.ddlGeneration.enabled && entity.entityType == EntityType.ENTITY) {
                val migration = MigrationGenerator.generateFromEntity(entity, config.databaseType)
                val migrationXml = MigrationGenerator.generate(migration)
                val migrationPath = "${config.changelogPath()}/001-${entity.resolvedTableName.lowercase()}.xml"
                files += GeneratedSource(migrationPath, migrationXml)
            }

            // Generate data repository if requested
            if (entity.dataRepository?.enabled == true) {
                val repoContent = DataRepositoryGenerator.generate(entity)
                val repoPath = "${config.sourceRoot}/$pkgPath/${entity.className}Repository.java"
                files += GeneratedSource(repoPath, repoContent)
            }

            // Generate localization
            val messages = buildEntityMessages(entity)
            if (messages.isNotEmpty()) {
                val messagesPath = "${config.resourceRoot}/${config.packageToPath(entity.packageName)}/messages.properties"
                files += GeneratedSource(messagesPath, messages, MergeStrategy.PROPERTIES)
            }
            applyGeneratedFiles("Create Jmix entity ${entity.className}", config, files)
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

            applyGeneratedFiles(
                label = "Generate Jmix CRUD for ${entity.className}",
                config = config,
                files = files.map { file ->
                    GeneratedSource(
                        relativePath = file.relativePath,
                        content = file.content,
                        mergeStrategy = when (file) {
                            output.menuXml -> MergeStrategy.MENU
                            output.messagesFile -> MergeStrategy.PROPERTIES
                            else -> MergeStrategy.CREATE_ONLY
                        },
                    )
                },
            )
        } catch (e: Exception) {
            log.error("CRUD generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── View Generation ─────────────────────────────────────────────────────

    fun generateView(view: ViewModel, config: ProjectConfig): GenerationResult {
        return try {
            // XML descriptor
            val xmlContent = ViewXmlGenerator.generate(view)
            val pkgPath = config.packageToPath(view.packageName)
            val xmlPath = "${config.resourceRoot}/$pkgPath/${view.xmlFileName}"
            val files = mutableListOf(GeneratedSource(xmlPath, xmlContent))

            // Controller
            val controllerContent = ViewControllerGenerator.generate(view)
            val controllerPath = "${config.sourceRoot}/$pkgPath/${view.controllerName}.java"
            files += GeneratedSource(controllerPath, controllerContent)

            // Menu entry
            view.menuEntry?.let { menu ->
                val menuContent = MenuGenerator.generate(listOf(menu))
                val menuPath = "${config.resourceRoot}/${config.packageToPath(config.basePackage)}/menu.xml"
                files += GeneratedSource(menuPath, menuContent, MergeStrategy.MENU)
            }
            applyGeneratedFiles("Create Jmix view ${view.controllerName}", config, files)
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
            applyGeneratedFiles(
                "Create Liquibase migration ${migration.changelogId}",
                config,
                listOf(GeneratedSource(path, content)),
            )
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
            applyGeneratedFiles(
                "Create Jmix role ${role.name}",
                config,
                listOf(GeneratedSource(path, content)),
            )
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
            applyGeneratedFiles(
                "Create BPM process ${process.id}",
                config,
                listOf(GeneratedSource(path, content)),
            )
        } catch (e: Exception) {
            log.error("BPM generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── Revision-bound file operations ──────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun applyGeneratedFiles(
        label: String,
        config: ProjectConfig,
        files: List<GeneratedSource>,
    ): GenerationResult {
        val projectRoot = project.basePath
            ?: return GenerationResult(false, errors = listOf("JVW-GENERATION-PROJECT-MISSING"))
        if (normalizePath(config.projectRoot) != normalizePath(projectRoot)) {
            return GenerationResult(
                false,
                errors = listOf("JVW-GENERATION-PROJECT-MISMATCH: generation outside the open project was rejected."),
            )
        }

        val changes = ReadAction.compute<List<WorkspaceFileChange>, RuntimeException> {
            files.distinctBy(GeneratedSource::relativePath).mapNotNull { source ->
                toWorkspaceChange(projectRoot, source)
            }
        }
        if (changes.isEmpty()) {
            return GenerationResult(true)
        }
        val identity = buildString {
            append(label)
            changes.sortedBy(WorkspaceFileChange::relativePath).forEach { change ->
                append('\u0000').append(change.relativePath)
                append('\u0000').append(change.createContent.orEmpty())
                change.edits.forEach { edit -> append('\u0000').append(edit.replacement) }
            }
        }
        val changeSet = WorkspaceChangeSet(
            id = "generation:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
            label = label,
            files = changes,
        )
        val changeService = WorkspaceChangeService.getInstance(project)
        val preview = ReadAction.compute<WorkspaceChangePreviewResponse, RuntimeException> {
            changeService.preview(changeSet)
        }
        if (!preview.accepted || preview.planDigest == null) {
            return GenerationResult(
                false,
                errors = preview.issues.map { "${it.code}: ${it.message}${it.relativePath?.let { path -> " ($path)" }.orEmpty()}" },
            )
        }
        val prepared = ReadAction.compute<PreparedWorkspaceChange, RuntimeException> {
            changeService.prepareApply(
                WorkspaceChangeApplyRequest(changeSet, preview.planDigest),
            )
        }
        var applied: WorkspaceChangeApplyResponse? = null
        val apply = Runnable {
            applied = changeService.applyPrepared(prepared)
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            apply.run()
        } else {
            ApplicationManager.getApplication().invokeAndWait(apply)
        }
        val response = applied
            ?: return GenerationResult(false, errors = listOf("JVW-GENERATION-APPLY-MISSING"))
        return if (response.success) {
            response.filesChanged.forEach { path -> log.info("Generated safely: $path") }
            GenerationResult(true, response.filesChanged)
        } else {
            GenerationResult(
                false,
                errors = response.issues.map { "${it.code}: ${it.message}${it.relativePath?.let { path -> " ($path)" }.orEmpty()}" },
            )
        }
    }

    private fun toWorkspaceChange(projectRoot: String, source: GeneratedSource): WorkspaceFileChange? {
        val current = readCurrent(projectRoot, source.relativePath)
        if (current == null || source.mergeStrategy == MergeStrategy.CREATE_ONLY) {
            return WorkspaceFileChange(
                relativePath = source.relativePath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = source.content,
            )
        }
        val addition = when (source.mergeStrategy) {
            MergeStrategy.PROPERTIES -> mergeInsertion(SourcePreservingMerge.properties(current, source.content))
            MergeStrategy.MENU -> mergeInsertion(SourcePreservingMerge.menu(current, source.content))
            MergeStrategy.CREATE_ONLY -> error("Handled above")
        } ?: return null
        return WorkspaceFileChange(
            relativePath = source.relativePath,
            mode = WorkspaceFileChangeMode.MODIFY,
            baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(current),
            edits = listOf(
                WorkspaceTextEdit(
                    startOffset = addition.offset,
                    endOffset = addition.offset,
                    expectedText = "",
                    replacement = addition.text,
                ),
            ),
        )
    }

    private fun readCurrent(projectRoot: String, relativePath: String): String? {
        val baseDir = LocalFileSystem.getInstance().findFileByPath(projectRoot) ?: return null
        val file = baseDir.findFileByRelativePath(relativePath) ?: return null
        if (file.isDirectory) return null
        return String(file.contentsToByteArray(false), file.charset)
    }

    private fun mergeInsertion(result: org.jmixworkbench.discovery.change.SourceMergeResult): TextAddition? {
        if (!result.accepted) {
            val issue = result.issue
            error("${issue?.code ?: "JVW-GENERATION-MERGE-REJECTED"}: ${issue?.message ?: "Merge rejected."}")
        }
        return result.insertion?.let { TextAddition(it.offset, it.text) }
    }

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trimEnd('/')

    private data class GeneratedSource(
        val relativePath: String,
        val content: String,
        val mergeStrategy: MergeStrategy = MergeStrategy.CREATE_ONLY,
    )

    private enum class MergeStrategy {
        CREATE_ONLY,
        PROPERTIES,
        MENU,
    }

    private data class TextAddition(
        val offset: Int,
        val text: String,
    )

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

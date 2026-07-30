package org.jmixworkbench.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.google.gson.JsonParser
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.change.SourcePreservingMerge
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.generator.*
import org.jmixworkbench.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Orchestrates code generation and writes files to the project.
 * Handles: single entity generation, full CRUD generation, view generation,
 * migration generation, menu/role generation, and file system refresh.
 */
@Service(Service.Level.PROJECT)
class CodeGenerationService(private val project: Project) {

    private val log = Logger.getInstance(CodeGenerationService::class.java)
    private val workflowVariable = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    private val workflowEntityName = Regex("[A-Za-z_][A-Za-z0-9_.]{0,255}")
    private val workflowMutatingJpql = Regex(
        """(?i)\b(update|delete|insert|merge|drop|alter|truncate)\b""",
    )

    data class GenerationResult(
        val success: Boolean,
        val filesWritten: List<String> = emptyList(),
        val errors: List<String> = emptyList()
    )

    // ─── Entity Generation ───────────────────────────────────────────────────

    fun generateEntity(entity: EntityModel, config: ProjectConfig): GenerationResult {
        return try {
            applyGeneratedPlan(entityGenerationPlan(entity, config))
        } catch (e: Exception) {
            log.error("Entity generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    fun previewEntityGeneration(entity: EntityModel, config: ProjectConfig): WorkspaceChangePreviewResponse =
        previewGeneratedPlan(entityGenerationPlan(entity, config))

    fun prepareEntityGeneration(
        entity: EntityModel,
        config: ProjectConfig,
        expectedPlanDigest: String,
    ): PreparedWorkspaceChange =
        prepareGeneratedPlan(entityGenerationPlan(entity, config), expectedPlanDigest)

    fun previewDatabaseEntityImport(
        entities: List<EntityModel>,
        config: ProjectConfig,
    ): WorkspaceChangePreviewResponse =
        previewGeneratedPlan(databaseEntityImportPlan(entities, config))

    fun prepareDatabaseEntityImport(
        entities: List<EntityModel>,
        config: ProjectConfig,
        expectedPlanDigest: String,
    ): PreparedWorkspaceChange =
        prepareGeneratedPlan(
            databaseEntityImportPlan(entities, config),
            expectedPlanDigest,
        )

    // ─── Full CRUD Generation ────────────────────────────────────────────────

    fun generateCrud(
        entity: EntityModel,
        config: ProjectConfig,
        options: CrudOrchestrator.CrudOptions = CrudOrchestrator.CrudOptions()
    ): GenerationResult {
        return try {
            applyGeneratedPlan(crudGenerationPlan(entity, config, options))
        } catch (e: Exception) {
            log.error("CRUD generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    fun previewCrudGeneration(
        entity: EntityModel,
        config: ProjectConfig,
        options: CrudOrchestrator.CrudOptions,
    ): WorkspaceChangePreviewResponse =
        previewGeneratedPlan(crudGenerationPlan(entity, config, options))

    fun prepareCrudGeneration(
        entity: EntityModel,
        config: ProjectConfig,
        options: CrudOrchestrator.CrudOptions,
        expectedPlanDigest: String,
    ): PreparedWorkspaceChange =
        prepareGeneratedPlan(crudGenerationPlan(entity, config, options), expectedPlanDigest)

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
            val packageName = role.packageName?.trim().orEmpty().ifBlank { "${config.basePackage}.security" }
            val content = RoleGenerator.generate(role, packageName)
            val pkgPath = config.packageToPath(packageName)
            val path = "${config.sourceRoot}/$pkgPath/${role.className}.java"
            applyGeneratedFiles(
                "Create Jmix role ${role.className}",
                config,
                listOf(GeneratedSource(path, content)),
            )
        } catch (e: Exception) {
            log.error("Role generation failed", e)
            GenerationResult(false, errors = listOf(e.message ?: "Unknown error"))
        }
    }

    // ─── Menu Generation ─────────────────────────────────────────────────────

    fun generateMenu(
        entries: List<MenuEntryModel>,
        config: ProjectConfig,
        sourcePath: String? = null,
        expectedRevisionFingerprint: String? = null,
    ): GenerationResult {
        return try {
            val content = MenuGenerator.generate(entries)
            val indexedSource = sourcePath?.let { requestedPath ->
                ApplicationGraphService.getInstance(project).graph().artifacts.firstOrNull { artifact ->
                    artifact.kind == org.jmixworkbench.discovery.model.ArtifactKind.MENU_SOURCE &&
                        artifact.sourceLocator.relativePath == requestedPath
                } ?: error("JVW-MENU-SOURCE-NOT-INDEXED: refresh the application map and select the menu again.")
            }
            val path = indexedSource?.sourceLocator?.relativePath
                ?: "${config.resourceRoot}/${config.packageToPath(config.basePackage)}/menu.xml"
            applyGeneratedFiles(
                if (indexedSource == null) "Create Jmix application menu" else "Update indexed Jmix application menu",
                config,
                listOf(
                    GeneratedSource(
                        relativePath = path,
                        content = content,
                        mergeStrategy = if (indexedSource == null) MergeStrategy.MENU else MergeStrategy.MENU_REPLACE,
                        expectedRevisionFingerprint = expectedRevisionFingerprint,
                    ),
                ),
            )
        } catch (e: Exception) {
            log.warn("Menu generation was rejected: ${e.message}")
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

    fun previewWorkflowGeneration(
        workflow: WorkflowModel,
        config: ProjectConfig,
    ): WorkspaceChangePreviewResponse =
        previewGeneratedPlan(workflowGenerationPlan(workflow, config))

    fun prepareWorkflowGeneration(
        workflow: WorkflowModel,
        config: ProjectConfig,
        expectedPlanDigest: String,
    ): PreparedWorkspaceChange =
        prepareGeneratedPlan(workflowGenerationPlan(workflow, config), expectedPlanDigest)

    private fun workflowGenerationPlan(
        workflow: WorkflowModel,
        config: ProjectConfig,
    ): GeneratedPlan {
        validateWorkflow(workflow)
        val content = BpmGenerator.generate(workflow)
        val sourceRelativePath = workflow.sourceRelativePath?.trim()?.takeIf(String::isNotBlank)
        if (sourceRelativePath != null) {
            val current = readCurrent(sourceRelativePath)
                ?: error("JVW-WORKFLOW-SOURCE-MISSING: The indexed BPMN source no longer exists.")
            require(!workflow.sourceFingerprint.isNullOrBlank() &&
                CanonicalDiscoveryJson.sha256(current) == workflow.sourceFingerprint
            ) {
                "JVW-WORKFLOW-SOURCE-STALE: The BPMN source changed after it was loaded. Refresh before updating."
            }
            val loaded = WorkflowWorkspaceService.getInstance(project).load(
                relativePath = sourceRelativePath,
                processId = workflow.id,
                moduleId = workflow.moduleId,
            )
            require(loaded.editable && loaded.workflow != null) {
                "JVW-WORKFLOW-ROUNDTRIP-UNSUPPORTED: ${loaded.error ?: loaded.unsupportedElements.joinToString()}"
            }
            return GeneratedPlan(
                label = "Update Jmix workflow ${workflow.name}",
                config = config,
                files = emptyList(),
                additionalChanges = listOf(
                    WorkspaceFileChange(
                        relativePath = sourceRelativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(current),
                        edits = listOf(
                            WorkspaceTextEdit(
                                startOffset = 0,
                                endOffset = current.length,
                                expectedText = current,
                                replacement = content,
                            ),
                        ),
                    ),
                ),
            )
        }
        return GeneratedPlan(
            label = "Create Jmix workflow ${workflow.name}",
            config = config,
            files = listOf(
                GeneratedSource(
                    "${workflowResourceRoot(workflow, config)}/processes/${workflow.id}.bpmn20.xml",
                    content,
                ),
            ),
        )
    }

    private fun workflowResourceRoot(workflow: WorkflowModel, config: ProjectConfig): String {
        val graph = ApplicationGraphService.getInstance(project).graph()
        ProjectSourceDestinationService.getInstance(project)
            .productionResources(graph)
            .firstOrNull { it.moduleId == workflow.moduleId }
            ?.let { return it.sourceRoot }
        val evidence = graph.artifacts.firstOrNull { artifact ->
            workflow.entityQualifiedName != null &&
                artifact.kind == org.jmixworkbench.discovery.model.ArtifactKind.ENTITY &&
                artifact.semanticKey == workflow.entityQualifiedName
        } ?: graph.artifacts.firstOrNull { artifact ->
            artifact.owner.moduleId == workflow.moduleId && "/src/" in artifact.sourceLocator.relativePath
        }
        val relativePath = evidence?.sourceLocator?.relativePath
        if (relativePath != null) {
            val moduleRoot = relativePath.substringBefore("/src/", missingDelimiterValue = "")
            return if (moduleRoot.isBlank()) "src/main/resources" else "$moduleRoot/src/main/resources"
        }
        return config.resourceRoot.trimEnd('/')
    }

    private fun validateWorkflow(workflow: WorkflowModel) {
        require(workflow.id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{2,127}"))) {
            "Workflow id must start with a letter and contain 3-128 letters, digits, underscores, or hyphens."
        }
        require(workflow.name.isNotBlank() && workflow.name.length <= 255) {
            "Workflow name is required and must not exceed 255 characters."
        }
        require(workflow.moduleId.isNotBlank()) { "A target module is required." }
        require(workflow.nodes.isNotEmpty()) { "Add workflow states before previewing." }
        val nodesById = workflow.nodes.associateBy { it.id }
        require(nodesById.size == workflow.nodes.size) { "Workflow node ids must be unique." }
        val startTypes = setOf(
            WorkflowNodeType.START,
            WorkflowNodeType.MESSAGE_START,
            WorkflowNodeType.SIGNAL_START,
            WorkflowNodeType.TIMER_START,
            WorkflowNodeType.ERROR_START,
        )
        val subprocessTypes = setOf(
            WorkflowNodeType.EMBEDDED_SUBPROCESS,
            WorkflowNodeType.EVENT_SUBPROCESS,
            WorkflowNodeType.TRANSACTION_SUBPROCESS,
        )
        require(workflow.nodes.any { it.parentSubprocessId == null && it.type in startTypes } &&
            workflow.nodes.count {
                it.parentSubprocessId == null && it.type == WorkflowNodeType.START
            } <= 1
        ) {
            "A workflow requires a top-level start event and at most one top-level plain start event."
        }
        val terminalTypes = setOf(
            WorkflowNodeType.TERMINAL,
            WorkflowNodeType.ERROR_END,
            WorkflowNodeType.CANCEL_END,
            WorkflowNodeType.TERMINATE_END,
        )
        val boundaryTypes = setOf(
            WorkflowNodeType.BOUNDARY_TIMER,
            WorkflowNodeType.BOUNDARY_MESSAGE,
            WorkflowNodeType.BOUNDARY_SIGNAL,
            WorkflowNodeType.BOUNDARY_ERROR,
            WorkflowNodeType.BOUNDARY_COMPENSATION,
            WorkflowNodeType.BOUNDARY_CANCEL,
        )
        val activityTypes = setOf(
            WorkflowNodeType.HUMAN_STATE,
            WorkflowNodeType.AUTOMATED_STATE,
            WorkflowNodeType.SCRIPT_STATE,
            WorkflowNodeType.ENTITY_DATA_STATE,
            WorkflowNodeType.BUSINESS_RULE_STATE,
            WorkflowNodeType.EMAIL_STATE,
            WorkflowNodeType.EMBEDDED_SUBPROCESS,
            WorkflowNodeType.EVENT_SUBPROCESS,
            WorkflowNodeType.TRANSACTION_SUBPROCESS,
            WorkflowNodeType.CALL_ACTIVITY,
        )
        require(workflow.nodes.any { it.type in terminalTypes }) {
            "A workflow must have at least one terminal state."
        }
        require(workflow.nodes.all { it.id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{1,127}")) }) {
            "Every workflow node id must start with a letter and contain only letters, digits, underscores, or hyphens."
        }
        require(workflow.nodes.all { node ->
            node.parentSubprocessId == null || nodesById[node.parentSubprocessId]?.type in subprocessTypes
        }) {
            "Every nested workflow node must reference an existing subprocess container."
        }
        require(workflow.nodes.all { node ->
            val visited = linkedSetOf(node.id)
            var parentId = node.parentSubprocessId
            while (parentId != null && visited.add(parentId)) {
                parentId = nodesById[parentId]?.parentSubprocessId
            }
            parentId == null
        }) {
            "Subprocess nesting cannot contain parent cycles."
        }
        workflow.nodes.filter { it.type in subprocessTypes }.forEach { subprocess ->
            val directChildren = workflow.nodes.filter { it.parentSubprocessId == subprocess.id }
            require(directChildren.isNotEmpty()) {
                "Subprocess '${subprocess.name}' must contain executable elements."
            }
            if (subprocess.type == WorkflowNodeType.EVENT_SUBPROCESS) {
                require(directChildren.none { it.type == WorkflowNodeType.START } &&
                    directChildren.any {
                        it.type in setOf(
                            WorkflowNodeType.MESSAGE_START,
                            WorkflowNodeType.SIGNAL_START,
                            WorkflowNodeType.TIMER_START,
                            WorkflowNodeType.ERROR_START,
                        )
                    }
                ) {
                    "Event subprocess '${subprocess.name}' requires an event start and cannot use a plain start."
                }
            } else {
                require(directChildren.count { it.type == WorkflowNodeType.START } == 1) {
                    "Embedded and transaction subprocesses require exactly one direct plain start event."
                }
            }
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.HUMAN_STATE }.all {
            it.actorRoleCodes.isNotEmpty() || !it.assigneeExpression.isNullOrBlank()
        }) {
            "Every human state requires at least one actor role or an assignee expression."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.AUTOMATED_STATE }.all {
            !it.serviceBean.isNullOrBlank()
        }) {
            "Every automated state requires a Spring service bean."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.SCRIPT_STATE }.all {
            !it.script.isNullOrBlank() &&
                it.script.length <= 100_000 &&
                (it.resultVariable.isNullOrBlank() || it.resultVariable.matches(workflowVariable))
        }) {
            "Every script task requires a bounded Groovy script and an optional valid result variable."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.ENTITY_DATA_STATE }.all { node ->
            val validEntity = !node.entityName.isNullOrBlank() &&
                node.entityName.matches(workflowEntityName)
            when (node.entityDataOperation) {
                WorkflowEntityDataOperation.LOAD ->
                    !node.jpql.isNullOrBlank() &&
                        node.jpql.trimStart().startsWith("select ", ignoreCase = true) &&
                        !workflowMutatingJpql.containsMatchIn(node.jpql) &&
                        !node.resultVariable.isNullOrBlank() &&
                        node.resultVariable.matches(workflowVariable) &&
                        validOptionalJsonArray(node.jpqlParametersJson)
                WorkflowEntityDataOperation.MODIFY ->
                    validEntity &&
                        !node.entityVariable.isNullOrBlank() &&
                        node.entityVariable.matches(workflowVariable) &&
                        validRequiredJsonArray(node.entityAttributesJson)
                WorkflowEntityDataOperation.CREATE ->
                    validEntity &&
                        (node.resultVariable.isNullOrBlank() ||
                            node.resultVariable.matches(workflowVariable)) &&
                        validRequiredJsonArray(node.entityAttributesJson)
            }
        }) {
            "Every Jmix entity-data task requires a valid operation, entity/variable contract, read-only JPQL, and JSON array configuration."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.BUSINESS_RULE_STATE }.all {
            !it.decisionTableKey.isNullOrBlank()
        }) {
            "Every business-rule state requires a deployed DMN decision-table key."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.CALL_ACTIVITY }.all {
            !it.calledElement.isNullOrBlank() && it.calledElement != workflow.id
        }) {
            "Every call activity requires a non-recursive called process key."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.EMAIL_STATE }.all { node ->
            !node.emailTo.isNullOrBlank() &&
                !node.emailSubject.isNullOrBlank() &&
                !node.emailContent.isNullOrBlank() &&
                node.multiInstanceMode == WorkflowMultiInstanceMode.NONE &&
                node.emailAttachments.all {
                    it.id.isNotBlank() && it.expression.isNotBlank()
                }
        }) {
            "Every Jmix email task requires recipients, subject, content, valid attachments, and cannot be multi-instance."
        }
        require(workflow.nodes.filter {
            it.type == WorkflowNodeType.TIMER_START ||
                it.type == WorkflowNodeType.TIMER_EVENT ||
                it.type == WorkflowNodeType.BOUNDARY_TIMER
        }.all { !it.timerExpression.isNullOrBlank() }) {
            "Every timer event requires an ISO-8601 value or runtime expression."
        }
        require(workflow.nodes.filter { it.type in boundaryTypes }.all { boundary ->
            val attached = nodesById[boundary.attachedToNodeId]
            attached != null &&
                attached.type in activityTypes &&
                attached.parentSubprocessId == boundary.parentSubprocessId
        }) {
            "Every boundary event must attach to an executable activity in the same BPMN scope."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.BOUNDARY_CANCEL }.all { boundary ->
            nodesById[boundary.attachedToNodeId]?.type == WorkflowNodeType.TRANSACTION_SUBPROCESS &&
                boundary.cancelActivity
        }) {
            "A cancel boundary event must interrupt a transaction subprocess."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.CANCEL_END }.all { end ->
            nodesById[end.parentSubprocessId]?.type == WorkflowNodeType.TRANSACTION_SUBPROCESS
        }) {
            "A cancel end event is valid only inside a transaction subprocess."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.ERROR_START }.all { start ->
            nodesById[start.parentSubprocessId]?.type == WorkflowNodeType.EVENT_SUBPROCESS
        }) {
            "A BPMN error start event is valid only inside an event subprocess."
        }
        require(workflow.nodes.filter {
            it.type == WorkflowNodeType.ERROR_START ||
                it.type == WorkflowNodeType.BOUNDARY_ERROR ||
                it.type == WorkflowNodeType.ERROR_END
        }.all { !it.eventReference.isNullOrBlank() }) {
            "Every BPMN error event requires an explicit error reference."
        }
        require(workflow.nodes.filter {
            it.type == WorkflowNodeType.MESSAGE_START ||
                it.type == WorkflowNodeType.MESSAGE_CATCH ||
                it.type == WorkflowNodeType.BOUNDARY_MESSAGE
        }.all { !it.eventReference.isNullOrBlank() }) {
            "Every message event requires an explicit message definition."
        }
        val signalNodes = workflow.nodes.filter {
            it.type == WorkflowNodeType.SIGNAL_START ||
                it.type == WorkflowNodeType.SIGNAL_CATCH ||
                it.type == WorkflowNodeType.SIGNAL_THROW ||
                it.type == WorkflowNodeType.BOUNDARY_SIGNAL
        }
        require(signalNodes.all { !it.eventReference.isNullOrBlank() }) {
            "Every signal event requires an explicit signal definition."
        }
        require(signalNodes.groupBy { it.eventReference }.values.all { nodes ->
            nodes.map { it.signalScope }.distinct().size == 1
        }) {
            "All uses of a signal definition must have the same scope."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.BOUNDARY_COMPENSATION }.all { boundary ->
            val handler = nodesById[boundary.compensationHandlerNodeId]
            handler != null &&
                handler.id != boundary.attachedToNodeId &&
                handler.type in activityTypes &&
                handler.parentSubprocessId == boundary.parentSubprocessId
        }) {
            "Every compensation boundary event requires a distinct executable handler activity."
        }
        require(workflow.nodes.filter {
            it.async && it.type in activityTypes
        }.all { !it.retryCycle.isNullOrBlank() }) {
            "Every asynchronous activity requires an explicit failed-job retry cycle."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.AUTOMATED_STATE && it.async }.all {
            !it.idempotencyKeyExpression.isNullOrBlank()
        }) {
            "Every retryable automated activity requires an idempotency key expression."
        }
        require(workflow.nodes.filter {
            it.multiInstanceMode != WorkflowMultiInstanceMode.NONE
        }.all {
            it.type in setOf(
                WorkflowNodeType.HUMAN_STATE,
                WorkflowNodeType.AUTOMATED_STATE,
                WorkflowNodeType.SCRIPT_STATE,
                WorkflowNodeType.BUSINESS_RULE_STATE,
                WorkflowNodeType.EMBEDDED_SUBPROCESS,
                WorkflowNodeType.TRANSACTION_SUBPROCESS,
                WorkflowNodeType.CALL_ACTIVITY,
            ) && (!it.loopCardinality.isNullOrBlank() || !it.collectionExpression.isNullOrBlank())
        }) {
            "Every multi-instance activity must be a supported task and requires loop cardinality or a collection expression."
        }
        require(workflow.nodes.filter { (it.minimumApprovals ?: 0) > 1 }.all {
            it.type == WorkflowNodeType.HUMAN_STATE &&
                it.multiInstanceMode != WorkflowMultiInstanceMode.NONE &&
                !it.completionCondition.isNullOrBlank()
        }) {
            "Approval quorums require a multi-instance human task and completion condition."
        }
        require(workflow.nodes.all { node ->
            node.segregationOfDutyNodeIds.all { guardedId ->
                guardedId != node.id && nodesById[guardedId]?.type == WorkflowNodeType.HUMAN_STATE
            }
        }) {
            "Segregation-of-duty controls must reference other human tasks."
        }
        require(workflow.lanes.map { it.id }.distinct().size == workflow.lanes.size &&
            workflow.lanes.all {
                it.id.matches(Regex("[A-Za-z][A-Za-z0-9_-]{1,127}")) && it.name.isNotBlank()
            }
        ) {
            "Lane ids must be unique safe BPMN identifiers and lane names are required."
        }
        val laneIds = workflow.lanes.map { it.id }.toSet()
        require(workflow.nodes.all { it.laneId == null || it.laneId in laneIds }) {
            "Every assigned workflow lane must exist."
        }
        require(workflow.executionListeners.all {
            it.event in setOf("start", "end") && it.implementation.isNotBlank()
        }) {
            "Process execution listeners require a start/end event and implementation."
        }
        require(workflow.nodes.all { node ->
            node.executionListeners.all {
                it.event in setOf("start", "end", "take") && it.implementation.isNotBlank()
            } &&
                node.taskListeners.all {
                    node.type == WorkflowNodeType.HUMAN_STATE &&
                        it.event in setOf("create", "assignment", "complete", "delete") &&
                        it.implementation.isNotBlank()
                }
        }) {
            "Workflow listeners contain an unsupported event, target, or empty implementation."
        }
        require(workflow.nodes.all { node ->
            val mappings = node.inputMappings + node.outputMappings
            mappings.isEmpty() || (
                node.type == WorkflowNodeType.CALL_ACTIVITY &&
                    mappings.all {
                        it.target.isNotBlank() &&
                            (!it.source.isNullOrBlank() xor !it.sourceExpression.isNullOrBlank())
                    }
                )
        }) {
            "Variable mappings are supported on call activities and require exactly one source plus a target."
        }
        require(workflow.nodes.all { node ->
            node.processVariables.isEmpty() || (
                node.type in startTypes &&
                    node.processVariables.map { it.name }.distinct().size == node.processVariables.size &&
                    node.processVariables.all { it.name.isNotBlank() && it.type.isNotBlank() }
                )
        }) {
            "Process-variable declarations belong to start events and require unique names and types."
        }
        require(workflow.nodes.all { node ->
            val form = node.formData ?: return@all true
            if (node.type !in startTypes && node.type != WorkflowNodeType.HUMAN_STATE) return@all false
            if (!node.formKey.isNullOrBlank()) return@all false
            if (form.type == WorkflowFormType.JMIX_VIEW && form.screenId.isNullOrBlank()) return@all false
            if (form.type != WorkflowFormType.INPUT_DIALOG && form.fields.isNotEmpty()) return@all false
            if (node.type != WorkflowNodeType.HUMAN_STATE && form.outcomes.isNotEmpty()) return@all false
            form.fields.map { it.id }.distinct().size == form.fields.size &&
                form.fields.all { it.id.isNotBlank() && it.caption.isNotBlank() && it.type.isNotBlank() } &&
                form.outcomes.map { it.id }.distinct().size == form.outcomes.size &&
                form.outcomes.all { it.id.isNotBlank() && it.caption.isNotBlank() }
        }) {
            "Jmix process form configuration is incomplete, duplicated, or attached to an unsupported element."
        }
        require(workflow.transitions.map { it.id }.distinct().size == workflow.transitions.size) {
            "Workflow transition ids must be unique."
        }
        require(workflow.transitions.all { it.sourceId in nodesById && it.targetId in nodesById }) {
            "Every workflow transition must connect two existing states."
        }
        require(workflow.transitions.all {
            nodesById[it.sourceId]?.parentSubprocessId == nodesById[it.targetId]?.parentSubprocessId
        }) {
            "Sequence flows cannot cross process or subprocess scope boundaries."
        }
        require(workflow.transitions.none {
            nodesById[it.sourceId]?.type == WorkflowNodeType.EVENT_SUBPROCESS ||
                nodesById[it.targetId]?.type == WorkflowNodeType.EVENT_SUBPROCESS
        }) {
            "Event subprocesses are event-triggered and cannot have external sequence flows."
        }
        require(workflow.transitions.none {
            nodesById[it.targetId]?.type in boundaryTypes || nodesById[it.targetId]?.type in startTypes
        }) {
            "Boundary and start events cannot have incoming sequence flows."
        }
        val outgoing = workflow.transitions.groupBy { it.sourceId }
        val incoming = workflow.transitions.groupBy { it.targetId }
        require(workflow.nodes.filter {
            it.type !in terminalTypes &&
                it.type != WorkflowNodeType.BOUNDARY_COMPENSATION &&
                it.type != WorkflowNodeType.EVENT_SUBPROCESS
        }.all {
            outgoing[it.id].orEmpty().isNotEmpty()
        }) {
            "Every non-terminal state requires an outgoing transition."
        }
        require(workflow.nodes.filter {
            it.type in terminalTypes ||
                it.type == WorkflowNodeType.BOUNDARY_COMPENSATION ||
                it.type == WorkflowNodeType.EVENT_SUBPROCESS
        }.all {
            outgoing[it.id].isNullOrEmpty()
        }) {
            "Terminal and compensation-boundary events cannot have outgoing sequence flows."
        }
        require(workflow.nodes.filter {
            it.type == WorkflowNodeType.DECISION || it.type == WorkflowNodeType.INCLUSIVE_GATEWAY
        }.all { gateway ->
            val gatewayFlows = outgoing[gateway.id].orEmpty()
            gatewayFlows.size >= 2 &&
                (gateway.defaultTransitionId == null || gatewayFlows.any { it.id == gateway.defaultTransitionId }) &&
                gatewayFlows.all {
                    it.id == gateway.defaultTransitionId || !it.conditionExpression.isNullOrBlank()
                }
        }) {
            "Exclusive and inclusive gateways require two branches; every non-default branch needs a condition."
        }
        require(workflow.nodes.filter { it.type == WorkflowNodeType.PARALLEL_GATEWAY }.all { gateway ->
            outgoing[gateway.id].orEmpty().size >= 2 || incoming[gateway.id].orEmpty().size >= 2
        }) {
            "A parallel gateway must fork or join at least two branches."
        }
        val boundaryByActivity = workflow.nodes.filter { it.type in boundaryTypes }
            .groupBy { requireNotNull(it.attachedToNodeId) }
        val scopes = listOf<String?>(null) + workflow.nodes
            .filter { it.type in subprocessTypes }
            .map { it.id }
        scopes.forEach { scopeId ->
            val scopeNodes = workflow.nodes.filter { it.parentSubprocessId == scopeId }
            val reached = scopeNodes.filter { it.type in startTypes }
                .mapTo(linkedSetOf()) { it.id }
            scopeNodes.filter { it.type == WorkflowNodeType.EVENT_SUBPROCESS }
                .forEach { reached += it.id }
            val queue = ArrayDeque<String>().apply { addAll(reached) }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                boundaryByActivity[current].orEmpty()
                    .filter { it.parentSubprocessId == scopeId }
                    .forEach { boundary ->
                        if (reached.add(boundary.id)) queue.add(boundary.id)
                    }
                outgoing[current].orEmpty().forEach { transition ->
                    if (reached.add(transition.targetId)) queue.add(transition.targetId)
                }
            }
            require(reached.size == scopeNodes.size) {
                val scopeName = scopeId?.let { nodesById[it]?.name } ?: workflow.name
                "Every workflow state in '$scopeName' must be reachable from a start event."
            }
        }
    }

    private fun validOptionalJsonArray(value: String?): Boolean =
        value.isNullOrBlank() || validRequiredJsonArray(value)

    private fun validRequiredJsonArray(value: String?): Boolean =
        !value.isNullOrBlank() && runCatching {
            JsonParser.parseString(value).isJsonArray
        }.getOrDefault(false)

    private fun entityGenerationPlan(
        entity: EntityModel,
        config: ProjectConfig,
    ): GeneratedPlan {
        val target = resolveGenerationTarget(entity, config)
        val effectiveEntity = entity
            .copy(dataStore = target.store?.name ?: entity.dataStore)
            .withProjectNaming(target.config.projectId)
        val effectiveConfig = target.config
        val pkgPath = effectiveConfig.packageToPath(effectiveEntity.packageName)
        val sourceExtension = effectiveEntity.sourceLanguage.fileExtension
        val entitySource = when (effectiveEntity.sourceLanguage) {
            EntitySourceLanguage.JAVA -> EntityGenerator.generate(effectiveEntity)
            EntitySourceLanguage.KOTLIN -> KotlinEntityGenerator.generate(effectiveEntity)
        }
        if (effectiveEntity.sourceLanguage == EntitySourceLanguage.KOTLIN) {
            validateKotlinSource("${effectiveEntity.className}.kt", entitySource)
        }
        val files = mutableListOf(
            GeneratedSource(
                "${effectiveConfig.sourceRoot}/$pkgPath/${effectiveEntity.className}.$sourceExtension",
                entitySource,
            ),
        )
        val migrationChanges = mutableListOf<WorkspaceFileChange>()

        if (
            effectiveEntity.ddlGeneration.effectiveMode != DdlGenerationMode.DISABLED &&
            effectiveEntity.entityType == EntityType.ENTITY &&
            !effectiveEntity.databaseView
        ) {
            val migration = MigrationGenerator.generateFromEntity(effectiveEntity, effectiveConfig.databaseType)
            if (target.store != null) {
                val proposal = SchemaWorkspaceService.getInstance(project).migrationProposal(
                    SchemaMigrationChangeRequest(
                        storeId = target.store.id,
                        migration = migration,
                        fileName = "create-${effectiveEntity.resolvedTableName.lowercase()}",
                    ),
                )
                val changeSet = proposal.changeSet
                    ?: error(proposal.issues.joinToString { "${it.code}: ${it.message}" })
                migrationChanges += changeSet.files
            } else {
                files += GeneratedSource(
                    "${effectiveConfig.changelogPath()}/001-${effectiveEntity.resolvedTableName.lowercase()}.xml",
                    MigrationGenerator.generate(migration),
                )
            }
        }

        if (effectiveEntity.dataRepository?.enabled == true) {
            val repositorySource = when (effectiveEntity.sourceLanguage) {
                EntitySourceLanguage.JAVA -> DataRepositoryGenerator.generate(effectiveEntity)
                EntitySourceLanguage.KOTLIN -> KotlinDataRepositoryGenerator.generate(effectiveEntity)
            }
            if (effectiveEntity.sourceLanguage == EntitySourceLanguage.KOTLIN) {
                validateKotlinSource("${effectiveEntity.className}Repository.kt", repositorySource)
            }
            files += GeneratedSource(
                "${effectiveConfig.sourceRoot}/$pkgPath/${effectiveEntity.className}Repository.$sourceExtension",
                repositorySource,
            )
            repositoryActivationSource(effectiveEntity, effectiveConfig)?.let(files::add)
        }

        val messages = buildEntityMessages(effectiveEntity)
        if (messages.isNotEmpty()) {
            files += GeneratedSource(
                "${effectiveConfig.resourceRoot}/$pkgPath/messages.properties",
                messages,
                MergeStrategy.PROPERTIES,
            )
        }
        return GeneratedPlan(
            label = "Create Jmix entity ${effectiveEntity.className}",
            config = effectiveConfig,
            files = files,
            additionalChanges = migrationChanges,
        )
    }

    private fun databaseEntityImportPlan(
        entities: List<EntityModel>,
        config: ProjectConfig,
    ): GeneratedPlan {
        require(entities.isNotEmpty() && entities.size <= MAX_DATABASE_IMPORT_TYPES) {
            "JVW-DB-IMPORT-TYPE-COUNT: select between 1 and $MAX_DATABASE_IMPORT_TYPES generated entity types."
        }
        require(entities.map(EntityModel::fullName).distinct().size == entities.size) {
            "JVW-DB-IMPORT-ENTITY-DUPLICATE: generated entity class names must be unique."
        }
        require(entities.all {
            it.entityType in setOf(EntityType.ENTITY, EntityType.EMBEDDABLE) &&
                it.ddlGeneration.effectiveMode == DdlGenerationMode.DISABLED &&
                it.dataRepository?.enabled != true
        }) {
            "JVW-DB-IMPORT-SOURCE-BOUNDARY: database import creates only DDL-disabled entities and composite-id embeddables."
        }
        val requestedTargets = entities.map {
            it.generationTarget?.moduleId.orEmpty() to it.generationTarget?.storeId.orEmpty()
        }.distinct()
        require(requestedTargets.size == 1) {
            "JVW-DB-IMPORT-TARGET-MISMATCH: one import batch must target one module and data store."
        }
        val plans = entities.map { entityGenerationPlan(it, config) }
        val effectiveConfig = plans.first().config
        require(plans.all {
            normalizePath(it.config.projectRoot) == normalizePath(effectiveConfig.projectRoot) &&
                it.config.sourceRoot == effectiveConfig.sourceRoot &&
                it.config.resourceRoot == effectiveConfig.resourceRoot
        }) {
            "JVW-DB-IMPORT-TARGET-MISMATCH: resolved source destinations changed during planning."
        }
        val files = plans.flatMap(GeneratedPlan::files)
            .groupBy(GeneratedSource::relativePath)
            .map { (path, sources) ->
                if (sources.size == 1) return@map sources.single()
                require(sources.all { it.mergeStrategy == MergeStrategy.PROPERTIES }) {
                    "JVW-DB-IMPORT-FILE-COLLISION: multiple generated types target $path."
                }
                sources.first().copy(
                    content = sources.asSequence()
                        .flatMap { it.content.lineSequence() }
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(separator = "\n", postfix = "\n"),
                )
            }
            .sortedBy(GeneratedSource::relativePath)
        val additionalChanges = plans.flatMap(GeneratedPlan::additionalChanges)
        require(additionalChanges.isEmpty()) {
            "JVW-DB-IMPORT-DDL-UNEXPECTED: existing database tables must never receive generated create-table migrations."
        }
        val entityCount = entities.count { it.entityType == EntityType.ENTITY }
        val idCount = entities.size - entityCount
        return GeneratedPlan(
            label = "Import $entityCount Jmix database ${if (entityCount == 1) "entity" else "entities"}" +
                if (idCount == 0) "" else " with $idCount composite ID ${if (idCount == 1) "class" else "classes"}",
            config = effectiveConfig,
            files = files,
        )
    }

    private fun crudGenerationPlan(
        entity: EntityModel,
        config: ProjectConfig,
        options: CrudOrchestrator.CrudOptions,
    ): GeneratedPlan {
        val target = resolveGenerationTarget(entity, config)
        val effectiveEntity = entity
            .copy(dataStore = target.store?.name ?: entity.dataStore)
            .withProjectNaming(target.config.projectId)
        val effectiveConfig = target.config
        val output = CrudOrchestrator.generate(
            effectiveEntity,
            effectiveConfig,
            options.copy(generateMigration = false),
        )
        val generatedFiles = listOfNotNull(
            output.entityFile,
            output.listViewXml,
            output.listViewController,
            output.detailViewXml,
            output.detailViewController,
            output.menuXml,
            output.roleFile,
            output.messagesFile,
            output.dataRepositoryFile,
            output.fetchPlanFile,
        ).toMutableList()
        if (options.generateDataRepository) {
            repositoryActivationSource(effectiveEntity, effectiveConfig)?.let { activation ->
                generatedFiles += CrudOrchestrator.GeneratedFile(
                    relativePath = activation.relativePath,
                    content = activation.content,
                    description = "Enable Jmix data repositories for ${effectiveEntity.packageName}",
                )
            }
        }
        val migrationChanges = if (options.generateMigration && target.store != null) {
            val migration = MigrationGenerator.generateFromEntity(effectiveEntity, options.dbType)
            val proposal = SchemaWorkspaceService.getInstance(project).migrationProposal(
                SchemaMigrationChangeRequest(
                    storeId = target.store.id,
                    migration = migration,
                    fileName = "create-${effectiveEntity.resolvedTableName.lowercase()}",
                ),
            )
            proposal.changeSet?.files
                ?: error(proposal.issues.joinToString { "${it.code}: ${it.message}" })
        } else {
            emptyList()
        }
        if (options.generateMigration && target.store == null) {
            val migration = MigrationGenerator.generateFromEntity(effectiveEntity, options.dbType)
            generatedFiles += CrudOrchestrator.GeneratedFile(
                relativePath =
                    "${effectiveConfig.changelogPath()}/001-${effectiveEntity.resolvedTableName.lowercase()}.xml",
                content = MigrationGenerator.generate(migration),
                description = "Liquibase migration for ${effectiveEntity.resolvedTableName}",
            )
        }
        return GeneratedPlan(
            label = "Generate Jmix CRUD for ${effectiveEntity.className}",
            config = effectiveConfig,
            files = generatedFiles.map { file ->
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
            additionalChanges = migrationChanges,
        )
    }

    private fun applyGeneratedPlan(plan: GeneratedPlan): GenerationResult =
        applyGeneratedFiles(
            label = plan.label,
            config = plan.config,
            files = plan.files,
            additionalChanges = plan.additionalChanges,
        )

    private fun previewGeneratedPlan(plan: GeneratedPlan): WorkspaceChangePreviewResponse =
        runCatching {
            val changeSet = generationChangeSet(plan)
            WorkspaceChangeService.getInstance(project).preview(changeSet)
        }.getOrElse { error ->
            rejectedGenerationPreview(plan.label, error)
        }

    private fun prepareGeneratedPlan(
        plan: GeneratedPlan,
        expectedPlanDigest: String,
    ): PreparedWorkspaceChange =
        runCatching {
            WorkspaceChangeService.getInstance(project).prepareApply(
                WorkspaceChangeApplyRequest(generationChangeSet(plan), expectedPlanDigest),
            )
        }.getOrElse { error ->
            PreparedWorkspaceChange(
                plan = org.jmixworkbench.discovery.change.WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "generation:rejected",
                    label = plan.label,
                    planDigest = null,
                    files = emptyList(),
                    issues = listOf(
                        org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                            code = "JVW-GENERATION-PLAN-REJECTED",
                            message = error.message ?: "Generation planning failed.",
                        ),
                    ),
                ),
                baseDir = null,
            )
        }

    private fun generationChangeSet(plan: GeneratedPlan): WorkspaceChangeSet {
        val projectRoot = project.basePath
            ?: error("JVW-GENERATION-PROJECT-MISSING")
        require(normalizePath(plan.config.projectRoot) == normalizePath(projectRoot)) {
            "JVW-GENERATION-PROJECT-MISMATCH: generation outside the open project was rejected."
        }
        val generatedChanges = plan.files
            .distinctBy(GeneratedSource::relativePath)
            .mapNotNull(::toWorkspaceChange)
        val changes = (generatedChanges + plan.additionalChanges)
            .distinctBy(WorkspaceFileChange::relativePath)
        val identity = buildString {
            append(plan.label)
            changes.sortedBy(WorkspaceFileChange::relativePath).forEach { change ->
                append('\u0000').append(change.relativePath)
                append('\u0000').append(change.createContent.orEmpty())
                change.edits.forEach { edit -> append('\u0000').append(edit.replacement) }
            }
        }
        return WorkspaceChangeSet(
            id = "generation:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
            label = plan.label,
            files = changes,
        )
    }

    private fun rejectedGenerationPreview(
        label: String,
        error: Throwable,
    ): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "generation:rejected",
            label = label,
            planDigest = null,
            files = emptyList(),
            issues = listOf(
                org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                    code = "JVW-GENERATION-PLAN-REJECTED",
                    message = error.message ?: "Generation planning failed.",
                ),
            ),
        )

    // ─── Revision-bound file operations ──────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun applyGeneratedFiles(
        label: String,
        config: ProjectConfig,
        files: List<GeneratedSource>,
        additionalChanges: List<WorkspaceFileChange> = emptyList(),
    ): GenerationResult {
        val projectRoot = project.basePath
            ?: return GenerationResult(false, errors = listOf("JVW-GENERATION-PROJECT-MISSING"))
        if (normalizePath(config.projectRoot) != normalizePath(projectRoot)) {
            return GenerationResult(
                false,
                errors = listOf("JVW-GENERATION-PROJECT-MISMATCH: generation outside the open project was rejected."),
            )
        }

        val changes = cancellableRead {
            val generatedChanges = files.distinctBy(GeneratedSource::relativePath).mapNotNull { source ->
                toWorkspaceChange(source)
            }
            (generatedChanges + additionalChanges)
                .distinctBy(WorkspaceFileChange::relativePath)
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
        val preview = cancellableRead {
            changeService.preview(changeSet)
        }
        if (!preview.accepted || preview.planDigest == null) {
            return GenerationResult(
                false,
                errors = preview.issues.map { "${it.code}: ${it.message}${it.relativePath?.let { path -> " ($path)" }.orEmpty()}" },
            )
        }
        val prepared = cancellableRead {
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

    private fun toWorkspaceChange(source: GeneratedSource): WorkspaceFileChange? {
        val current = readCurrent(source.relativePath)
        if (current == null || source.mergeStrategy == MergeStrategy.CREATE_ONLY) {
            return WorkspaceFileChange(
                relativePath = source.relativePath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = source.content,
            )
        }
        val currentFingerprint = CanonicalDiscoveryJson.sha256(current)
        source.expectedRevisionFingerprint?.let { expected ->
            require(expected == currentFingerprint) {
                "JVW-MENU-SOURCE-STALE: the selected menu changed after it was indexed; refresh before applying."
            }
        }
        if (source.mergeStrategy == MergeStrategy.MENU_REPLACE) {
            val replacement = MenuSourcePatcher.patch(current, source.content)
            if (replacement == current) return null
            return WorkspaceFileChange(
                relativePath = source.relativePath,
                mode = WorkspaceFileChangeMode.MODIFY,
                baseRevisionFingerprint = currentFingerprint,
                edits = listOf(
                    WorkspaceTextEdit(
                        startOffset = 0,
                        endOffset = current.length,
                        expectedText = current,
                        replacement = replacement,
                    ),
                ),
            )
        }
        val addition = when (source.mergeStrategy) {
            MergeStrategy.PROPERTIES -> mergeInsertion(SourcePreservingMerge.properties(current, source.content))
            MergeStrategy.MENU -> mergeInsertion(SourcePreservingMerge.menu(current, source.content))
            MergeStrategy.MENU_REPLACE -> error("Handled above")
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

    private fun readCurrent(relativePath: String): String? {
        val file = ProjectFileResolver.getInstance(project).resolveFile(relativePath)?.file ?: return null
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

    private fun resolveGenerationTarget(
        entity: EntityModel,
        baseConfig: ProjectConfig,
    ): ResolvedGenerationTarget {
        val workspace = SchemaWorkspaceService.getInstance(project).load()
        val requestedStoreId = entity.generationTarget?.storeId?.trim().orEmpty()
        val requestedModuleId = entity.generationTarget?.moduleId?.trim().orEmpty()
        val store = when {
            requestedStoreId.isNotBlank() -> workspace.stores.firstOrNull { it.id == requestedStoreId }
                ?: error("JVW-GENERATION-STORE-MISSING: The selected data store no longer exists.")
            requestedModuleId.isNotBlank() -> workspace.stores.firstOrNull {
                it.moduleId == requestedModuleId && it.name == entity.dataStore
            } ?: workspace.stores.firstOrNull {
                it.moduleId == requestedModuleId && it.name == "main"
            }
            workspace.stores.size == 1 -> workspace.stores.single()
            else -> workspace.stores.firstOrNull { it.name == entity.dataStore }
        }
        val moduleId = requestedModuleId.ifBlank { store?.moduleId.orEmpty() }
        val graph = ApplicationGraphService.getInstance(project).graph()
        val destinations = ProjectSourceDestinationService.getInstance(project)
        val sourceDestination = when (entity.sourceLanguage) {
            EntitySourceLanguage.JAVA -> destinations.productionJava(graph)
            EntitySourceLanguage.KOTLIN -> destinations.productionKotlin(graph)
        }
            .firstOrNull { it.moduleId == moduleId }
            ?.sourceRoot
        val resourceDestination = destinations.productionResources(graph)
            .firstOrNull { it.moduleId == moduleId }
            ?.sourceRoot
        val modulePrefix = store?.rootChangelogPath?.let(::modulePrefix)
            ?: modulePrefixFromGraph(moduleId)
            ?: if (
                requestedModuleId.isBlank() ||
                sourceDestination != null ||
                resourceDestination != null
            ) {
                ""
            } else {
                error("JVW-GENERATION-MODULE-MISSING: The selected module has no writable production source roots.")
            }
        val sourceRoot = sourceDestination ?: rooted(
            modulePrefix,
            entity.sourceLanguage.conventionalSourceRoot,
        )
        val resourceRoot = resourceDestination ?: rooted(modulePrefix, "src/main/resources")
        val projectId = JmixProjectService.getInstance(project)
            .projectIdForModule(modulePrefix)
            ?: baseConfig.projectId
        return ResolvedGenerationTarget(
            config = baseConfig.copy(
                basePackage = entity.packageName.removeSuffix(".entity"),
                sourceRoot = sourceRoot,
                resourceRoot = resourceRoot,
                changelogRoot = store?.generatedDirectory,
                projectId = projectId,
            ),
            store = store,
        )
    }

    private fun modulePrefixFromGraph(moduleId: String): String? {
        if (moduleId.isBlank()) return null
        return ApplicationGraphService.getInstance(project).graph().artifacts
            .asSequence()
            .filter { it.owner.moduleId == moduleId }
            .mapNotNull { modulePrefix(it.sourceLocator.relativePath) }
            .firstOrNull()
    }

    private fun modulePrefix(relativePath: String): String? {
        val marker = "/src/main/"
        return when {
            marker in relativePath -> relativePath.substringBefore(marker)
            relativePath.startsWith("src/main/") -> ""
            else -> null
        }
    }

    private fun rooted(prefix: String, path: String): String =
        if (prefix.isBlank()) path else "${prefix.trimEnd('/')}/$path"

    private fun repositoryActivationSource(
        entity: EntityModel,
        config: ProjectConfig,
    ): GeneratedSource? {
        val projectRoot = project.basePath ?: return null
        val moduleSourceRoot = Paths.get(projectRoot).resolve(config.sourceRoot).normalize()
        if (containsRepositoryActivation(moduleSourceRoot)) return null

        val basePackage = entity.packageName.removeSuffix(".entity")
        val kotlin = entity.sourceLanguage == EntitySourceLanguage.KOTLIN
        val relativePath = "${config.sourceRoot}/${config.packageToPath(basePackage)}/" +
            "JmixDataRepositoryConfiguration.${if (kotlin) "kt" else "java"}"
        val content = if (kotlin) {
            """
                package $basePackage

                import io.jmix.core.repository.EnableJmixDataRepositories
                import org.springframework.context.annotation.Configuration

                @Configuration
                @EnableJmixDataRepositories(basePackages = ["${entity.packageName}"])
                open class JmixDataRepositoryConfiguration
            """.trimIndent() + "\n"
        } else {
            """
                package $basePackage;

                import io.jmix.core.repository.EnableJmixDataRepositories;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @EnableJmixDataRepositories(basePackages = "${entity.packageName}")
                public class JmixDataRepositoryConfiguration {
                }
            """.trimIndent() + "\n"
        }
        if (kotlin) {
            validateKotlinSource("JmixDataRepositoryConfiguration.kt", content)
        }
        return GeneratedSource(relativePath, content)
    }

    private fun containsRepositoryActivation(sourceRoot: Path): Boolean {
        if (!Files.isDirectory(sourceRoot)) return false
        return Files.walk(sourceRoot).use { paths ->
            paths
                .filter {
                    Files.isRegularFile(it) &&
                        (it.fileName.toString().endsWith(".java") || it.fileName.toString().endsWith(".kt"))
                }
                .anyMatch { path ->
                    runCatching {
                        Files.size(path) <= MAX_REPOSITORY_SCAN_FILE_SIZE &&
                            Files.readString(path).contains("EnableJmixDataRepositories")
                    }.getOrDefault(false)
                }
        }
    }

    private fun validateKotlinSource(fileName: String, source: String) {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("kt")
        require(fileType.name.contains("kotlin", ignoreCase = true)) {
            "JVW-KOTLIN-PLUGIN-MISSING: Kotlin entity generation requires the bundled IntelliJ Kotlin plugin."
        }
        val psi = PsiFileFactory.getInstance(project).createFileFromText(fileName, fileType, source)
        val syntax = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
        require(syntax == null) {
            "JVW-KOTLIN-GENERATION-SYNTAX: Generated $fileName is invalid: ${syntax?.errorDescription}."
        }
    }

    private data class ResolvedGenerationTarget(
        val config: ProjectConfig,
        val store: SchemaDataStoreSnapshot?,
    )

    private data class GeneratedPlan(
        val label: String,
        val config: ProjectConfig,
        val files: List<GeneratedSource>,
        val additionalChanges: List<WorkspaceFileChange> = emptyList(),
    )

    private data class GeneratedSource(
        val relativePath: String,
        val content: String,
        val mergeStrategy: MergeStrategy = MergeStrategy.CREATE_ONLY,
        val expectedRevisionFingerprint: String? = null,
    )

    private enum class MergeStrategy {
        CREATE_ONLY,
        PROPERTIES,
        MENU,
        MENU_REPLACE,
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
        private const val MAX_REPOSITORY_SCAN_FILE_SIZE = 2L * 1024L * 1024L
        private const val MAX_DATABASE_IMPORT_TYPES = 200

        fun getInstance(project: Project): CodeGenerationService =
            project.getService(CodeGenerationService::class.java)
    }
}

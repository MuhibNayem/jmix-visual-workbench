package org.jmixworkbench.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.refactoring.typeMigration.TypeMigrationProcessor
import com.intellij.refactoring.typeMigration.TypeMigrationRules
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.jmixworkbench.generator.CrudOrchestrator
import org.jmixworkbench.model.*
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.security.SecurityWorkspaceBuilder
import org.jmixworkbench.discovery.security.SecurityWorkspaceInput
import org.jmixworkbench.discovery.security.SecurityWorkspaceSnapshot
import org.jmixworkbench.discovery.security.RuntimeSecurityEvidenceClearRequest
import org.jmixworkbench.discovery.security.RuntimeSecurityEvidenceImportRequest
import org.jmixworkbench.discovery.security.RuntimeSecurityEvidenceImportResponse
import org.jmixworkbench.services.CodeGenerationService
import org.jmixworkbench.services.ExistingEntityAttributeAdditionApplyRequest
import org.jmixworkbench.services.ExistingEntityAttributeAdditionRequest
import org.jmixworkbench.services.ExistingEntityChangeService
import org.jmixworkbench.services.EntityAttributeRefactorService
import org.jmixworkbench.services.EntityAttributeRenameLaunchResponse
import org.jmixworkbench.services.EntityAttributeRenameRequest
import org.jmixworkbench.services.PreparedEntityAttributeRename
import org.jmixworkbench.services.EntityAttributeSafeDeleteLaunchResponse
import org.jmixworkbench.services.EntityAttributeSafeDeleteRequest
import org.jmixworkbench.services.PreparedEntityAttributeSafeDelete
import org.jmixworkbench.services.EntityAttributeTypeMigrationLaunchResponse
import org.jmixworkbench.services.EntityAttributeTypeMigrationRequest
import org.jmixworkbench.services.PreparedEntityAttributeTypeMigration
import org.jmixworkbench.services.EntityAttributeTypeExpansionApplyRequest
import org.jmixworkbench.services.EntityAttributeTypeExpansionPreviewResponse
import org.jmixworkbench.services.EntityAttributeTypeExpansionService
import org.jmixworkbench.services.EntityAttributeTypeCutoverService
import org.jmixworkbench.services.EntityAttributeTypeMappingCutoverRequest
import org.jmixworkbench.services.EntityAttributeTypeMappingCutoverApplyRequest
import org.jmixworkbench.services.EntityAttributePropagationApplyRequest
import org.jmixworkbench.services.EntityAttributePropagationChangeRequest
import org.jmixworkbench.services.EntityAttributePropagationInspectionRequest
import org.jmixworkbench.services.EntityAttributePropagationInspectionResponse
import org.jmixworkbench.services.EntityAttributePropagationService
import org.jmixworkbench.services.DatabaseEntityTableInspectionRequest
import org.jmixworkbench.services.DatabaseEntityTableBrowseRequest
import org.jmixworkbench.services.DatabaseEntityImportRequest
import org.jmixworkbench.services.DatabaseReverseEngineeringService
import org.jmixworkbench.services.ApplicationGraphService
import org.jmixworkbench.services.FlowUiPropertyChangeRequest
import org.jmixworkbench.services.FlowUiPropertyApplyRequest
import org.jmixworkbench.services.FlowUiWorkspaceRequest
import org.jmixworkbench.services.FlowUiWorkspaceResponse
import org.jmixworkbench.services.FlowUiWorkspaceService
import org.jmixworkbench.services.FlowUiStructureApplyRequest
import org.jmixworkbench.services.FlowUiStructureChangeRequest
import org.jmixworkbench.services.FlowUiDirectTextApplyRequest
import org.jmixworkbench.services.FlowUiDirectTextChangeRequest
import org.jmixworkbench.services.FlowUiControllerInjectionApplyRequest
import org.jmixworkbench.services.FlowUiControllerInjectionRequest
import org.jmixworkbench.services.FlowUiControllerChangeService
import org.jmixworkbench.services.FlowUiControllerHandlerApplyRequest
import org.jmixworkbench.services.FlowUiControllerHandlerRequest
import org.jmixworkbench.services.JmixFlowUiHotDeployApplyRequest
import org.jmixworkbench.services.JmixFlowUiHotDeployRequest
import org.jmixworkbench.services.JmixRuntimeInspectionRequest
import org.jmixworkbench.services.JmixRuntimeOpenPreviewRequest
import org.jmixworkbench.services.JmixRuntimeService
import org.jmixworkbench.services.PreparedWorkspaceChange
import org.jmixworkbench.services.PreparedSourceNavigation
import org.jmixworkbench.services.SourceNavigationRequest
import org.jmixworkbench.services.SecurityRoleChangeService
import org.jmixworkbench.services.SecurityRoleCreateApplyRequest
import org.jmixworkbench.services.SecurityRoleCreateRequest
import org.jmixworkbench.services.SecurityRolePolicyChangeApplyRequest
import org.jmixworkbench.services.SecurityRolePolicyChangeRequest
import org.jmixworkbench.services.SecurityRolePolicyInspectionRequest
import org.jmixworkbench.services.SecurityRolePolicyInspectionResponse
import org.jmixworkbench.services.SecurityRolePolicyReplacementApplyRequest
import org.jmixworkbench.services.SecurityRolePolicyReplacementRequest
import org.jmixworkbench.services.SecurityRolePolicyRemovalApplyRequest
import org.jmixworkbench.services.SecurityRolePolicyRemovalRequest
import org.jmixworkbench.services.RuntimeSecurityEvidenceService
import org.jmixworkbench.services.RestApiInvocationRequest
import org.jmixworkbench.services.RestApiInvocationResponse
import org.jmixworkbench.services.RestApiChangeService
import org.jmixworkbench.services.RestApiContractAdditionApplyRequest
import org.jmixworkbench.services.RestApiContractAdditionRequest
import org.jmixworkbench.services.RestApiContractInput
import org.jmixworkbench.services.RestApiContractMutationApplyRequest
import org.jmixworkbench.services.RestApiContractMutationMode
import org.jmixworkbench.services.RestApiContractMutationRequest
import org.jmixworkbench.services.RestApiContractParameterInput
import org.jmixworkbench.services.RestApiContractTargetInput
import org.jmixworkbench.services.RestApiWorkspaceResponse
import org.jmixworkbench.services.RestApiWorkspaceService
import org.jmixworkbench.services.JmixProjectService
import org.jmixworkbench.services.IntegrationConnectorApplyRequest
import org.jmixworkbench.services.IntegrationConnectorWorkspaceResponse
import org.jmixworkbench.services.IntegrationConnectorWorkspaceService
import org.jmixworkbench.services.MigrationJsonParser
import org.jmixworkbench.services.MenuWorkspaceService
import org.jmixworkbench.services.SchemaMigrationApplyRequest
import org.jmixworkbench.services.SchemaMigrationChangeRequest
import org.jmixworkbench.services.SchemaWorkspaceResponse
import org.jmixworkbench.services.SchemaWorkspaceService
import org.jmixworkbench.services.ScenarioTestApplyRequest
import org.jmixworkbench.services.ScenarioWorkspaceResponse
import org.jmixworkbench.services.ScenarioWorkspaceService
import org.jmixworkbench.services.VisualLogicApplyRequest
import org.jmixworkbench.services.VisualLogicWorkspaceResponse
import org.jmixworkbench.services.VisualLogicWorkspaceService
import org.jmixworkbench.services.VisualRuleApplyRequest
import org.jmixworkbench.services.VisualRuleWorkspaceResponse
import org.jmixworkbench.services.VisualRuleWorkspaceService
import org.jmixworkbench.services.DmnDecisionApplyRequest
import org.jmixworkbench.services.DmnDecisionWorkspaceResponse
import org.jmixworkbench.services.DmnDecisionWorkspaceService
import org.jmixworkbench.services.WorkspaceChangeApplyRequest
import org.jmixworkbench.services.WorkspaceChangeApplyResponse
import org.jmixworkbench.services.WorkspaceChangePreviewResponse
import org.jmixworkbench.services.WorkspaceChangeService
import org.jmixworkbench.services.WorkspaceHistoryService
import org.jmixworkbench.services.WorkflowWorkspaceService
import org.jmixworkbench.toolwindow.isPackagedWorkbenchOriginUrl
import org.jmixworkbench.toolwindow.WorkbenchLaunchContext
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter

/**
 * Bridge between the JCEF embedded browser (React UI) and the Java backend.
 * Handles JSON message passing for all code generation commands.
 *
 * Protocol:
 *   JS → Java: window.cefQuery({ request: JSON.stringify({ action, payload }) })
 *   Java → JS: browser.executeJavaScript("window.onBridgeResponse(JSON)")
 */
class JcefBridge(
    private val project: Project,
    private val browser: JBCefBrowser,
    initialLaunchContext: WorkbenchLaunchContext? = null,
) {
    private val log = Logger.getInstance(JcefBridge::class.java)
    private val gson = Gson()
    private val jsQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
    @Volatile
    private var launchContext: WorkbenchLaunchContext? = initialLaunchContext

    init {
        jsQuery.addHandler { request ->
            handleRequest(request)
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                if (
                    frame?.isMain == true &&
                    isPackagedWorkbenchOriginUrl(browser?.url.orEmpty())
                ) {
                    injectBridge()
                }
            }
        }, browser.cefBrowser)
    }

    private fun injectBridge() {
        val injection = jsQuery.inject("request")
        val launchContextJson = gson.toJson(launchContext)
        val script = """
            window.jmixWorkbenchLaunchContext = $launchContextJson;
            window.javaBridge = {
                send: function(action, payload, requestId) {
                    var request = JSON.stringify({ action: action, payload: payload, requestId: requestId });
                    $injection
                }
            };
            if (window.onBridgeReady) { window.onBridgeReady(); }
            if (window.onWorkbenchLaunchContext) {
                window.onWorkbenchLaunchContext(window.jmixWorkbenchLaunchContext);
            }
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    fun publishLaunchContext(context: WorkbenchLaunchContext) {
        launchContext = context
        if (!isPackagedWorkbenchOriginUrl(browser.cefBrowser.url.orEmpty())) {
            return
        }
        val contextJson = gson.toJson(context)
        val script = """
            window.jmixWorkbenchLaunchContext = $contextJson;
            if (window.onWorkbenchLaunchContext) {
                window.onWorkbenchLaunchContext(window.jmixWorkbenchLaunchContext);
            }
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    private fun handleRequest(requestJson: String) {
        var action = "error"
        var requestId: String? = null
        try {
            val json = JsonParser.parseString(requestJson).asJsonObject
            action = json.get("action").asString
            requestId = json.get("requestId")?.takeUnless { it.isJsonNull }?.asString
            val payload = json.getAsJsonObject("payload")

            log.info("Bridge request: $action")

            if (action == "getApplicationGraph") {
                handleGetApplicationGraph(action, requestId, payload)
                return
            }
            if (action == "getMenuWorkspace") {
                handleGetMenuWorkspace(action, requestId)
                return
            }
            if (action == "getScenarioWorkspace") {
                handleGetScenarioWorkspace(action, requestId, payload)
                return
            }
            if (action == "previewScenarioTest") {
                handlePreviewScenarioTest(action, requestId, payload)
                return
            }
            if (action == "applyScenarioTest") {
                handleApplyScenarioTest(action, requestId, payload)
                return
            }
            if (action == "getVisualLogicWorkspace") {
                handleGetVisualLogicWorkspace(action, requestId, payload)
                return
            }
            if (action == "previewVisualLogic") {
                handlePreviewVisualLogic(action, requestId, payload)
                return
            }
            if (action == "applyVisualLogic") {
                handleApplyVisualLogic(action, requestId, payload)
                return
            }
            if (action == "getIntegrationConnectorWorkspace") {
                handleGetIntegrationConnectorWorkspace(action, requestId, payload)
                return
            }
            if (action == "previewIntegrationConnector") {
                handlePreviewIntegrationConnector(action, requestId, payload)
                return
            }
            if (action == "applyIntegrationConnector") {
                handleApplyIntegrationConnector(action, requestId, payload)
                return
            }
            if (action == "getVisualRuleWorkspace") {
                handleGetVisualRuleWorkspace(action, requestId, payload)
                return
            }
            if (action == "previewVisualRule") {
                handlePreviewVisualRule(action, requestId, payload)
                return
            }
            if (action == "applyVisualRule") {
                handleApplyVisualRule(action, requestId, payload)
                return
            }
            if (action == "getDmnDecisionWorkspace") {
                handleGetDmnDecisionWorkspace(action, requestId, payload)
                return
            }
            if (action == "previewDmnDecision") {
                handlePreviewDmnDecision(action, requestId, payload)
                return
            }
            if (action == "applyDmnDecision") {
                handleApplyDmnDecision(action, requestId, payload)
                return
            }
            if (action == "simulateDmnDecision") {
                handleSimulateDmnDecision(action, requestId, payload)
                return
            }
            if (action == "getSchemaWorkspace") {
                handleGetSchemaWorkspace(action, requestId, payload)
                return
            }
            if (action == "getRestApiWorkspace") {
                handleGetRestApiWorkspace(action, requestId, payload)
                return
            }
            if (action == "invokeRestApi") {
                handleInvokeRestApi(action, requestId, payload)
                return
            }
            if (action == "previewRestApiContractAddition") {
                handlePreviewRestApiContractAddition(action, requestId, payload)
                return
            }
            if (action == "applyRestApiContractAddition") {
                handleApplyRestApiContractAddition(action, requestId, payload)
                return
            }
            if (action == "previewRestApiContractMutation") {
                handlePreviewRestApiContractMutation(action, requestId, payload)
                return
            }
            if (action == "applyRestApiContractMutation") {
                handleApplyRestApiContractMutation(action, requestId, payload)
                return
            }
            if (action == "previewSchemaMigration") {
                handlePreviewSchemaMigration(action, requestId, payload)
                return
            }
            if (action == "applySchemaMigration") {
                handleApplySchemaMigration(action, requestId, payload)
                return
            }
            if (action == "previewEntityGeneration") {
                handlePreviewEntityGeneration(action, requestId, payload)
                return
            }
            if (action == "applyEntityGeneration") {
                handleApplyEntityGeneration(action, requestId, payload)
                return
            }
            if (action == "previewExistingEntityAttributeAdditions") {
                handlePreviewExistingEntityAttributeAdditions(action, requestId, payload)
                return
            }
            if (action == "applyExistingEntityAttributeAdditions") {
                handleApplyExistingEntityAttributeAdditions(action, requestId, payload)
                return
            }
            if (action == "launchEntityAttributeRename") {
                handleLaunchEntityAttributeRename(action, requestId, payload)
                return
            }
            if (action == "launchEntityAttributeSafeDelete") {
                handleLaunchEntityAttributeSafeDelete(action, requestId, payload)
                return
            }
            if (action == "launchEntityAttributeTypeMigration") {
                handleLaunchEntityAttributeTypeMigration(action, requestId, payload)
                return
            }
            if (action == "previewEntityAttributeTypeExpansion") {
                handlePreviewEntityAttributeTypeExpansion(action, requestId, payload)
                return
            }
            if (action == "applyEntityAttributeTypeExpansion") {
                handleApplyEntityAttributeTypeExpansion(action, requestId, payload)
                return
            }
            if (action == "verifyEntityAttributeTypeExpansion") {
                handleVerifyEntityAttributeTypeExpansion(action, requestId, payload)
                return
            }
            if (action == "previewEntityAttributeTypeMappingCutover") {
                handlePreviewEntityAttributeTypeMappingCutover(action, requestId, payload)
                return
            }
            if (action == "applyEntityAttributeTypeMappingCutover") {
                handleApplyEntityAttributeTypeMappingCutover(action, requestId, payload)
                return
            }
            if (action == "inspectDatabaseEntityTable") {
                handleInspectDatabaseEntityTable(action, requestId, payload)
                return
            }
            if (action == "browseDatabaseEntityTables") {
                handleBrowseDatabaseEntityTables(action, requestId, payload)
                return
            }
            if (action == "planDatabaseEntityImport") {
                handlePlanDatabaseEntityImport(action, requestId, payload)
                return
            }
            if (action == "previewDatabaseEntityImport") {
                handlePreviewDatabaseEntityImport(action, requestId, payload)
                return
            }
            if (action == "applyDatabaseEntityImport") {
                handleApplyDatabaseEntityImport(action, requestId, payload)
                return
            }
            if (action == "inspectEntityAttributePropagation") {
                handleInspectEntityAttributePropagation(action, requestId, payload)
                return
            }
            if (action == "previewEntityAttributePropagation") {
                handlePreviewEntityAttributePropagation(action, requestId, payload)
                return
            }
            if (action == "applyEntityAttributePropagation") {
                handleApplyEntityAttributePropagation(action, requestId, payload)
                return
            }
            if (action == "previewCrudGeneration") {
                handlePreviewCrudGeneration(action, requestId, payload)
                return
            }
            if (action == "applyCrudGeneration") {
                handleApplyCrudGeneration(action, requestId, payload)
                return
            }
            if (action == "previewWorkflowGeneration") {
                handlePreviewWorkflowGeneration(action, requestId, payload)
                return
            }
            if (action == "loadWorkflowModel") {
                handleLoadWorkflowModel(action, requestId, payload)
                return
            }
            if (action == "applyWorkflowGeneration") {
                handleApplyWorkflowGeneration(action, requestId, payload)
                return
            }
            if (action == "getSecurityWorkspace") {
                handleGetSecurityWorkspace(action, requestId, payload)
                return
            }
            if (action == "importRuntimeSecurityEvidence") {
                handleImportRuntimeSecurityEvidence(action, requestId, payload)
                return
            }
            if (action == "clearRuntimeSecurityEvidence") {
                handleClearRuntimeSecurityEvidence(action, requestId, payload)
                return
            }
            if (action == "navigateToSource") {
                handleNavigateToSource(action, requestId, payload)
                return
            }
            if (action == "getFlowUiWorkspace") {
                handleGetFlowUiWorkspace(action, requestId, payload)
                return
            }
            if (action == "getWorkspaceHistory") {
                sendResponse(
                    action,
                    requestId,
                    gson.toJson(WorkspaceHistoryService.getInstance(project).snapshot()),
                )
                return
            }
            if (action == "undoWorkspaceChange") {
                val response = WorkspaceHistoryService.getInstance(project).undo()
                sendResponse(action, requestId, gson.toJson(response))
                return
            }
            if (action == "redoWorkspaceChange") {
                val response = WorkspaceHistoryService.getInstance(project).redo()
                sendResponse(action, requestId, gson.toJson(response))
                return
            }
            if (action == "previewFlowUiPropertyChange") {
                handlePreviewFlowUiPropertyChange(action, requestId, payload)
                return
            }
            if (action == "applyFlowUiPropertyChange") {
                handleApplyFlowUiPropertyChange(action, requestId, payload)
                return
            }
            if (action == "previewFlowUiStructureChange") {
                handlePreviewFlowUiStructureChange(action, requestId, payload)
                return
            }
            if (action == "applyFlowUiStructureChange") {
                handleApplyFlowUiStructureChange(action, requestId, payload)
                return
            }
            if (action == "previewFlowUiDirectTextChange") {
                handlePreviewFlowUiDirectTextChange(action, requestId, payload)
                return
            }
            if (action == "applyFlowUiDirectTextChange") {
                handleApplyFlowUiDirectTextChange(action, requestId, payload)
                return
            }
            if (action == "previewFlowUiControllerInjection") {
                handlePreviewFlowUiControllerInjection(action, requestId, payload)
                return
            }
            if (action == "applyFlowUiControllerInjection") {
                handleApplyFlowUiControllerInjection(action, requestId, payload)
                return
            }
            if (action == "previewFlowUiControllerHandler") {
                handlePreviewFlowUiControllerHandler(action, requestId, payload)
                return
            }
            if (action == "applyFlowUiControllerHandler") {
                handleApplyFlowUiControllerHandler(action, requestId, payload)
                return
            }
            if (action == "inspectJmixRuntime") {
                handleInspectJmixRuntime(action, requestId, payload)
                return
            }
            if (action == "openJmixRuntimePreview") {
                handleOpenJmixRuntimePreview(action, requestId, payload)
                return
            }
            if (action == "previewFlowUiHotDeploy") {
                handlePreviewFlowUiHotDeploy(action, requestId, payload)
                return
            }
            if (action == "applyFlowUiHotDeploy") {
                handleApplyFlowUiHotDeploy(action, requestId, payload)
                return
            }
            if (action == "previewWorkspaceChange") {
                handlePreviewWorkspaceChange(action, requestId, payload)
                return
            }
            if (action == "getSecurityRoleDestinations") {
                handleGetSecurityRoleDestinations(action, requestId)
                return
            }
            if (action == "applyWorkspaceChange") {
                handleApplyWorkspaceChange(action, requestId, payload)
                return
            }
            if (action == "previewSecurityRoleCreate") {
                handlePreviewSecurityRoleCreate(action, requestId, payload)
                return
            }
            if (action == "applySecurityRoleCreate") {
                handleApplySecurityRoleCreate(action, requestId, payload)
                return
            }
            if (action == "previewSecurityRolePolicyAddition") {
                handlePreviewSecurityRolePolicyAddition(action, requestId, payload)
                return
            }
            if (action == "applySecurityRolePolicyAddition") {
                handleApplySecurityRolePolicyAddition(action, requestId, payload)
                return
            }
            if (action == "inspectSecurityRolePolicies") {
                handleInspectSecurityRolePolicies(action, requestId, payload)
                return
            }
            if (action == "previewSecurityRolePolicyReplacement") {
                handlePreviewSecurityRolePolicyReplacement(action, requestId, payload)
                return
            }
            if (action == "applySecurityRolePolicyReplacement") {
                handleApplySecurityRolePolicyReplacement(action, requestId, payload)
                return
            }
            if (action == "previewSecurityRolePolicyRemoval") {
                handlePreviewSecurityRolePolicyRemoval(action, requestId, payload)
                return
            }
            if (action == "applySecurityRolePolicyRemoval") {
                handleApplySecurityRolePolicyRemoval(action, requestId, payload)
                return
            }

            val result = when (action) {
                "generateEntity" -> handleGenerateEntity(payload)
                "generateCrud" -> handleGenerateCrud(payload)
                "generateView" -> handleGenerateView(payload)
                "generateMigration" -> handleGenerateMigration(payload)
                "generateRole" -> handleGenerateRole(payload)
                "generateMenu" -> handleGenerateMenu(payload)
                "generateBpm" -> handleGenerateBpm(payload)
                "getProjectConfig" -> handleGetProjectConfig()
                "getEntities" -> handleGetEntities()
                "ping" -> """{"status":"ok"}"""
                else -> """{"error":"Unknown action: $action"}"""
            }

            sendResponse(action, requestId, result)
        } catch (e: Exception) {
            log.error("Bridge error", e)
            sendResponse(
                action,
                requestId,
                gson.toJson(mapOf("error" to (e.message ?: "Bridge request failed."))),
            )
        }
    }

    private fun handleGenerateEntity(payload: JsonObject): String {
        val entity = gson.fromJson(payload, EntityModel::class.java)
        val config = JmixProjectService.getInstance(project).getConfig()
            ?: return """{"error":"Not a Jmix project"}"""
        val result = CodeGenerationService.getInstance(project).generateEntity(entity, config)
        return gson.toJson(result)
    }

    private fun handleGenerateCrud(payload: JsonObject): String {
        val entity = gson.fromJson(payload.getAsJsonObject("entity"), EntityModel::class.java)
        val options = if (payload.has("options")) {
            gson.fromJson(payload.getAsJsonObject("options"), CrudOrchestrator.CrudOptions::class.java)
        } else {
            CrudOrchestrator.CrudOptions()
        }
        val config = JmixProjectService.getInstance(project).getConfig()
            ?: return """{"error":"Not a Jmix project"}"""
        val result = CodeGenerationService.getInstance(project).generateCrud(entity, config, options)
        return gson.toJson(result)
    }

    private fun handleGenerateView(payload: JsonObject): String {
        val view = gson.fromJson(payload, ViewModel::class.java)
        val config = JmixProjectService.getInstance(project).getConfig()
            ?: return """{"error":"Not a Jmix project"}"""
        val result = CodeGenerationService.getInstance(project).generateView(view, config)
        return gson.toJson(result)
    }

    private fun handleGenerateMigration(payload: JsonObject): String {
        val migration = MigrationJsonParser.parse(payload)
        val config = JmixProjectService.getInstance(project).getConfig()
            ?: return """{"error":"Not a Jmix project"}"""
        val result = CodeGenerationService.getInstance(project).generateMigration(migration, config)
        return gson.toJson(result)
    }

    private fun handleGenerateRole(payload: JsonObject): String {
        val role = gson.fromJson(payload, RoleModel::class.java)
        val config = JmixProjectService.getInstance(project).getConfig()
            ?: return """{"error":"Not a Jmix project"}"""
        val result = CodeGenerationService.getInstance(project).generateRole(role, config)
        return gson.toJson(result)
    }

    private fun handleGenerateMenu(payload: JsonObject): String {
        val entries = payload.getAsJsonArray("entries")?.map { element ->
            gson.fromJson(element, MenuEntryModel::class.java)
        } ?: emptyList()
        val sourcePath = payload.get("sourcePath")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.takeIf(String::isNotBlank)
        val expectedRevisionFingerprint = payload.get("expectedRevisionFingerprint")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.takeIf(String::isNotBlank)
        val config = JmixProjectService.getInstance(project).getConfig()
            ?: return """{"error":"Not a Jmix project"}"""
        val result = CodeGenerationService.getInstance(project).generateMenu(
            entries,
            config,
            sourcePath,
            expectedRevisionFingerprint,
        )
        return gson.toJson(result)
    }

    private fun handleGenerateBpm(payload: JsonObject): String {
        val config = JmixProjectService.getInstance(project).getConfig()
            ?: return """{"error":"Not a Jmix project"}"""
        val entityName = payload.get("entityName").asString
        val process = org.jmixworkbench.generator.BpmGenerator.generateApprovalProcess(entityName)
        val result = CodeGenerationService.getInstance(project).generateBpmProcess(process, config)
        return gson.toJson(result)
    }

    private fun handleGetProjectConfig(): String {
        val config = JmixProjectService.getInstance(project).getConfig()
            ?: return """{"error":"Not a Jmix project"}"""
        return gson.toJson(config)
    }

    private fun handleGetEntities(): String {
        val graph = ApplicationGraphService.getInstance(project).graph()
        return gson.toJson(
            mapOf(
                "entities" to graph.artifacts.filter {
                    it.kind == org.jmixworkbench.discovery.model.ArtifactKind.ENTITY
                },
            ),
        )
    }

    private fun handleGetApplicationGraph(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<org.jmixworkbench.services.ApplicationGraphResponse> {
            ApplicationGraphService.getInstance(project).graph(forceRefresh)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleGetMenuWorkspace(
        action: String,
        requestId: String?,
    ) {
        ReadAction.nonBlocking<org.jmixworkbench.services.MenuWorkspaceResponse> {
            MenuWorkspaceService.getInstance(project).load()
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleGetScenarioWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<ScenarioWorkspaceResponse> {
            ScenarioWorkspaceService.getInstance(project).load(forceRefresh)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewScenarioTest(action: String, requestId: String?, payload: JsonObject) {
        val scenario = gson.fromJson(payload, ScenarioTestModel::class.java)
        val result = ScenarioWorkspaceService.getInstance(project).preview(scenario)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleApplyScenarioTest(action: String, requestId: String?, payload: JsonObject) {
        val request = gson.fromJson(payload, ScenarioTestApplyRequest::class.java)
        val prepared = ScenarioWorkspaceService.getInstance(project).prepare(request)
        val result = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleGetVisualLogicWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<VisualLogicWorkspaceResponse> {
            VisualLogicWorkspaceService.getInstance(project).load(forceRefresh)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewVisualLogic(action: String, requestId: String?, payload: JsonObject) {
        val model = gson.fromJson(payload, VisualLogicClassModel::class.java)
        val result = VisualLogicWorkspaceService.getInstance(project).preview(model)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleApplyVisualLogic(action: String, requestId: String?, payload: JsonObject) {
        val request = gson.fromJson(payload, VisualLogicApplyRequest::class.java)
        val prepared = VisualLogicWorkspaceService.getInstance(project).prepare(request)
        val result = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleGetIntegrationConnectorWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<IntegrationConnectorWorkspaceResponse> {
            IntegrationConnectorWorkspaceService.getInstance(project).load(forceRefresh)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewIntegrationConnector(action: String, requestId: String?, payload: JsonObject) {
        val model = gson.fromJson(payload, IntegrationConnectorModel::class.java)
        val result = IntegrationConnectorWorkspaceService.getInstance(project).preview(model)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleApplyIntegrationConnector(action: String, requestId: String?, payload: JsonObject) {
        val request = gson.fromJson(payload, IntegrationConnectorApplyRequest::class.java)
        val prepared = IntegrationConnectorWorkspaceService.getInstance(project).prepare(request)
        val result = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleGetVisualRuleWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<VisualRuleWorkspaceResponse> {
            VisualRuleWorkspaceService.getInstance(project).load(forceRefresh)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewVisualRule(action: String, requestId: String?, payload: JsonObject) {
        val model = gson.fromJson(payload, VisualRuleModel::class.java)
        val result = VisualRuleWorkspaceService.getInstance(project).preview(model)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleApplyVisualRule(action: String, requestId: String?, payload: JsonObject) {
        val request = gson.fromJson(payload, VisualRuleApplyRequest::class.java)
        val prepared = VisualRuleWorkspaceService.getInstance(project).prepare(request)
        val result = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleGetDmnDecisionWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<DmnDecisionWorkspaceResponse> {
            DmnDecisionWorkspaceService.getInstance(project).load(forceRefresh)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewDmnDecision(action: String, requestId: String?, payload: JsonObject) {
        val model = gson.fromJson(payload, DmnDecisionModel::class.java)
        val result = DmnDecisionWorkspaceService.getInstance(project).preview(model)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleApplyDmnDecision(action: String, requestId: String?, payload: JsonObject) {
        val request = gson.fromJson(payload, DmnDecisionApplyRequest::class.java)
        val prepared = DmnDecisionWorkspaceService.getInstance(project).prepare(request)
        val result = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleSimulateDmnDecision(action: String, requestId: String?, payload: JsonObject) {
        val request = gson.fromJson(payload, DmnSimulationRequest::class.java)
        val result = DmnDecisionWorkspaceService.getInstance(project).simulate(request)
        sendResponse(action, requestId, gson.toJson(result))
    }

    private fun handleGetSchemaWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<SchemaWorkspaceResponse> {
            SchemaWorkspaceService.getInstance(project).load(forceRefresh)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleGetRestApiWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<RestApiWorkspaceResponse> {
            RestApiWorkspaceService.getInstance(project).load(forceRefresh)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleInvokeRestApi(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, RestApiInvocationRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    RestApiInvocationResponse.rejected(
                        "JVW-REST-INVOKE-REQUEST-INVALID",
                        error.message ?: "The REST invocation request is malformed.",
                    ),
                ),
            )
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            val result = RestApiWorkspaceService.getInstance(project).invoke(request)
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    sendResponse(action, requestId, gson.toJson(result))
                }
            }, ModalityState.any())
        }
    }

    private fun handlePreviewRestApiContractAddition(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching { restApiContractAdditionRequest(payload) }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangePreviewResponse(
                        accepted = false,
                        changeSetId = "rest-contract-add:rejected",
                        label = "REST contract addition rejected",
                        planDigest = null,
                        files = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                "JVW-REST-CONTRACT-REQUEST-INVALID",
                                error.message ?: "The REST contract request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            RestApiChangeService.getInstance(project).previewAddition(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyRestApiContractAddition(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            RestApiContractAdditionApplyRequest(
                change = restApiContractAdditionRequest(payload.getAsJsonObject("change")),
                expectedPlanDigest = payload.get("expectedPlanDigest")?.asString.orEmpty(),
            )
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "rest-contract-add:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                "JVW-REST-CONTRACT-REQUEST-INVALID",
                                error.message ?: "The REST contract apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            RestApiChangeService.getInstance(project).prepareAddition(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun restApiContractAdditionRequest(payload: JsonObject): RestApiContractAdditionRequest {
        val contractJson = payload.getAsJsonObject("contract")
        return RestApiContractAdditionRequest(
            moduleId = payload.get("moduleId")?.asString.orEmpty()
                .also { require(it.isNotBlank()) { "A module id is required." } },
            configLocator = gson.fromJson(
                payload.getAsJsonObject("configLocator"),
                org.jmixworkbench.discovery.model.SourceLocator::class.java,
            ),
            contract = restApiContractInput(contractJson),
        )
    }

    private fun restApiContractInput(contractJson: JsonObject): RestApiContractInput {
        val parameters = contractJson.getAsJsonArray("parameters")?.map { item ->
            val parameter = item.asJsonObject
            RestApiContractParameterInput(
                name = parameter.get("name")?.asString.orEmpty(),
                javaType = parameter.get("javaType")?.asString.orEmpty(),
            )
        }.orEmpty()
        return when (contractJson.get("kind")?.asString?.uppercase()) {
            "SERVICE" -> RestApiContractInput.ServiceMethod(
                serviceName = contractJson.get("serviceName")?.asString.orEmpty(),
                methodName = contractJson.get("methodName")?.asString.orEmpty(),
                parameters = parameters,
            )
            "QUERY" -> RestApiContractInput.Query(
                name = contractJson.get("name")?.asString.orEmpty(),
                entityName = contractJson.get("entityName")?.asString.orEmpty(),
                fetchPlan = contractJson.get("fetchPlan")?.asString.orEmpty(),
                jpql = contractJson.get("jpql")?.asString.orEmpty(),
                parameters = parameters,
            )
            else -> error("Contract kind must be SERVICE or QUERY.")
        }
    }

    private fun handlePreviewRestApiContractMutation(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching { restApiContractMutationRequest(payload) }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangePreviewResponse(
                        accepted = false,
                        changeSetId = "rest-contract-change:rejected",
                        label = "REST contract change rejected",
                        planDigest = null,
                        files = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                "JVW-REST-CONTRACT-REQUEST-INVALID",
                                error.message ?: "The REST contract change request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<WorkspaceChangePreviewResponse> {
            RestApiChangeService.getInstance(project).previewMutation(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyRestApiContractMutation(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            RestApiContractMutationApplyRequest(
                change = restApiContractMutationRequest(payload.getAsJsonObject("change")),
                expectedPlanDigest = payload.get("expectedPlanDigest")?.asString.orEmpty(),
            )
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "rest-contract-change:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                "JVW-REST-CONTRACT-REQUEST-INVALID",
                                error.message ?: "The REST contract change request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            RestApiChangeService.getInstance(project).prepareMutation(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun restApiContractMutationRequest(payload: JsonObject): RestApiContractMutationRequest {
        val targetJson = payload.getAsJsonObject("target")
        val target = when (targetJson.get("kind")?.asString?.uppercase()) {
            "SERVICE" -> RestApiContractTargetInput.ServiceMethod(
                serviceName = targetJson.get("serviceName")?.asString.orEmpty(),
                methodName = targetJson.get("methodName")?.asString.orEmpty(),
                parameterTypes = targetJson.getAsJsonArray("parameterTypes")?.map { it.asString }.orEmpty(),
            )
            "QUERY" -> RestApiContractTargetInput.Query(
                name = targetJson.get("name")?.asString.orEmpty(),
                entityName = targetJson.get("entityName")?.asString.orEmpty(),
            )
            else -> error("Target kind must be SERVICE or QUERY.")
        }
        return RestApiContractMutationRequest(
            moduleId = payload.get("moduleId")?.asString.orEmpty()
                .also { require(it.isNotBlank()) { "A module id is required." } },
            configLocator = gson.fromJson(
                payload.getAsJsonObject("configLocator"),
                org.jmixworkbench.discovery.model.SourceLocator::class.java,
            ),
            mode = RestApiContractMutationMode.valueOf(payload.get("mode")?.asString?.uppercase().orEmpty()),
            target = target,
            replacement = payload.getAsJsonObject("replacement")?.let(::restApiContractInput),
        )
    }

    private fun handlePreviewSchemaMigration(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching { schemaMigrationRequest(payload) }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "schema-migration:rejected",
                        "label" to "Schema migration rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-SCHEMA-REQUEST-INVALID",
                                "message" to (error.message ?: "The schema migration request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            SchemaWorkspaceService.getInstance(project).previewMigration(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplySchemaMigration(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            SchemaMigrationApplyRequest(
                change = schemaMigrationRequest(payload.getAsJsonObject("change")),
                expectedPlanDigest = payload.get("expectedPlanDigest")?.asString.orEmpty(),
            )
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "schema-migration:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-SCHEMA-REQUEST-INVALID",
                                message = error.message ?: "The schema migration apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            SchemaWorkspaceService.getInstance(project).prepareMigration(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun schemaMigrationRequest(payload: JsonObject): SchemaMigrationChangeRequest =
        SchemaMigrationChangeRequest(
            storeId = payload.get("storeId")?.asString.orEmpty()
                .also { require(it.isNotBlank()) { "A data store id is required." } },
            migration = MigrationJsonParser.parse(payload.getAsJsonObject("migration")),
            fileName = payload.get("fileName")?.takeUnless { it.isJsonNull }?.asString,
        )

    private fun handlePreviewEntityGeneration(action: String, requestId: String?, payload: JsonObject) {
        val entity = runCatching { gson.fromJson(payload, EntityModel::class.java) }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        val config = JmixProjectService.getInstance(project).getConfig()
        if (config == null) {
            sendGenerationRequestError(action, requestId, IllegalStateException("Not a Jmix project."))
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            CodeGenerationService.getInstance(project).previewEntityGeneration(entity, config)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyEntityGeneration(action: String, requestId: String?, payload: JsonObject) {
        val entity = runCatching {
            gson.fromJson(payload.getAsJsonObject("entity"), EntityModel::class.java)
        }.getOrElse { error ->
            sendGenerationApplyError(action, requestId, error)
            return
        }
        val config = JmixProjectService.getInstance(project).getConfig()
        if (config == null) {
            sendGenerationApplyError(action, requestId, IllegalStateException("Not a Jmix project."))
            return
        }
        val expectedPlanDigest = payload.get("expectedPlanDigest")?.asString.orEmpty()
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            CodeGenerationService.getInstance(project).prepareEntityGeneration(
                entity,
                config,
                expectedPlanDigest,
            )
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewExistingEntityAttributeAdditions(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, ExistingEntityAttributeAdditionRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            ExistingEntityChangeService.getInstance(project).previewAttributeAdditions(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyExistingEntityAttributeAdditions(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, ExistingEntityAttributeAdditionApplyRequest::class.java)
        }.getOrElse { error ->
            sendGenerationApplyError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            ExistingEntityChangeService.getInstance(project).prepareAttributeAdditions(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleLaunchEntityAttributeRename(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributeRenameRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<PreparedEntityAttributeRename> {
            EntityAttributeRefactorService.getInstance(project).prepareRename(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val element = prepared.element
                val newName = prepared.newName
                if (!prepared.accepted || element == null || newName == null) {
                    sendResponse(
                        action,
                        requestId,
                        gson.toJson(
                            EntityAttributeRenameLaunchResponse(
                                success = false,
                                code = prepared.code,
                                message = prepared.message,
                            ),
                        ),
                    )
                    return@finishOnUiThread
                }
                sendResponse(
                    action,
                    requestId,
                    gson.toJson(
                        EntityAttributeRenameLaunchResponse(
                            success = true,
                            message = "IntelliJ usage preview opened for ${request.attributeName} → $newName.",
                        ),
                    ),
                )
                ApplicationManager.getApplication().invokeLater({
                    RenameProcessor(
                        project,
                        element,
                        newName,
                        false,
                        false,
                    ).apply {
                        setPreviewUsages(true)
                    }.run()
                }, ModalityState.nonModal())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleLaunchEntityAttributeSafeDelete(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributeSafeDeleteRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<PreparedEntityAttributeSafeDelete> {
            EntityAttributeRefactorService.getInstance(project).prepareSafeDelete(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val element = prepared.element
                if (!prepared.accepted || element == null) {
                    sendResponse(
                        action,
                        requestId,
                        gson.toJson(
                            EntityAttributeSafeDeleteLaunchResponse(
                                success = false,
                                code = prepared.code,
                                message = prepared.message,
                            ),
                        ),
                    )
                    return@finishOnUiThread
                }
                sendResponse(
                    action,
                    requestId,
                    gson.toJson(
                        EntityAttributeSafeDeleteLaunchResponse(
                            success = true,
                            message = buildString {
                                append("IntelliJ Safe Delete usage preview opened for ")
                                    .append(request.attributeName)
                                    .append(".")
                                prepared.retainedColumnName?.let {
                                    append(" Database column ").append(it)
                                        .append(" is retained for separate migration review.")
                                }
                            },
                            retainedColumnName = prepared.retainedColumnName,
                        ),
                    ),
                )
                ApplicationManager.getApplication().invokeLater({
                    SafeDeleteProcessor.createInstance(
                        project,
                        null,
                        arrayOf(element),
                        true,
                        true,
                    ).run()
                }, ModalityState.nonModal())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleLaunchEntityAttributeTypeMigration(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributeTypeMigrationRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<PreparedEntityAttributeTypeMigration> {
            EntityAttributeRefactorService.getInstance(project).prepareTypeMigration(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val element = prepared.element
                val targetType = prepared.targetPsiType
                if (!prepared.accepted || element == null || targetType == null) {
                    sendResponse(
                        action,
                        requestId,
                        gson.toJson(
                            EntityAttributeTypeMigrationLaunchResponse(
                                success = false,
                                code = prepared.code,
                                message = prepared.message,
                                sourceLanguage = prepared.sourceLanguage,
                                schemaImpact = prepared.schemaImpact,
                            ),
                        ),
                    )
                    return@finishOnUiThread
                }
                val authorizationIssue = EntityAttributeTypeCutoverService.getInstance(project)
                    .authorizeSourceMigration(request, prepared)
                if (authorizationIssue != null) {
                    sendResponse(
                        action,
                        requestId,
                        gson.toJson(
                            EntityAttributeTypeMigrationLaunchResponse(
                                success = false,
                                code = authorizationIssue.code,
                                message = authorizationIssue.message,
                                sourceLanguage = prepared.sourceLanguage,
                                schemaImpact = prepared.schemaImpact,
                            ),
                        ),
                    )
                    return@finishOnUiThread
                }
                sendResponse(
                    action,
                    requestId,
                    gson.toJson(
                        EntityAttributeTypeMigrationLaunchResponse(
                            success = true,
                            message = prepared.message,
                            sourceLanguage = prepared.sourceLanguage,
                            schemaImpact = prepared.schemaImpact,
                        ),
                    ),
                )
                ApplicationManager.getApplication().invokeLater({
                    val rules = TypeMigrationRules(project).apply {
                        setBoundScope(GlobalSearchScope.projectScope(project))
                    }
                    TypeMigrationProcessor(
                        project,
                        arrayOf(element),
                        { targetType },
                        rules,
                        true,
                    ).apply {
                        setPreviewUsages(true)
                    }.run()
                }, ModalityState.nonModal())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewEntityAttributeTypeExpansion(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributeTypeMigrationRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<EntityAttributeTypeExpansionPreviewResponse> {
            EntityAttributeTypeExpansionService.getInstance(project).preview(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyEntityAttributeTypeExpansion(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributeTypeExpansionApplyRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            EntityAttributeTypeExpansionService.getInstance(project).prepareApply(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleVerifyEntityAttributeTypeExpansion(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributeTypeMigrationRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val response = EntityAttributeTypeCutoverService.getInstance(project).verify(request)
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    sendResponse(action, requestId, gson.toJson(response))
                }
            }, ModalityState.any())
        }
    }

    private fun handlePreviewEntityAttributeTypeMappingCutover(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributeTypeMappingCutoverRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val response = EntityAttributeTypeCutoverService.getInstance(project)
                .previewMappingCutover(request)
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    sendResponse(action, requestId, gson.toJson(response))
                }
            }, ModalityState.any())
        }
    }

    private fun handleApplyEntityAttributeTypeMappingCutover(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributeTypeMappingCutoverApplyRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val prepared = EntityAttributeTypeCutoverService.getInstance(project)
                .prepareMappingCutoverApply(request)
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                    sendResponse(action, requestId, gson.toJson(response))
                }
            }, ModalityState.any())
        }
    }

    private fun handleInspectDatabaseEntityTable(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            DatabaseEntityTableInspectionRequest(
                storeId = payload.get("storeId")?.asString.orEmpty(),
                tableName = payload.get("tableName")?.asString.orEmpty(),
                schemaName = payload.get("schemaName")?.takeUnless { it.isJsonNull }?.asString,
                catalogName = payload.get("catalogName")?.takeUnless { it.isJsonNull }?.asString,
                expectedEntityQualifiedName = payload.get("expectedEntityQualifiedName")
                    ?.takeUnless { it.isJsonNull }?.asString,
                connectTimeoutSeconds = payload.get("connectTimeoutSeconds")?.asInt ?: 10,
                networkTimeoutSeconds = payload.get("networkTimeoutSeconds")?.asInt ?: 30,
            )
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    org.jmixworkbench.services.DatabaseEntityTableInspectionResponse.failure(
                        "JVW-DB-REQUEST-INVALID",
                        error.message ?: "The database inspection request is malformed.",
                    ),
                ),
            )
            return
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val response = DatabaseReverseEngineeringService.getInstance(project)
                .inspectEntityTable(request)
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    sendResponse(action, requestId, gson.toJson(response))
                }
            }, ModalityState.any())
        }
    }

    private fun handleBrowseDatabaseEntityTables(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            DatabaseEntityTableBrowseRequest(
                storeId = payload.get("storeId")?.asString.orEmpty(),
                catalogName = payload.get("catalogName")?.takeUnless { it.isJsonNull }?.asString,
                schemaName = payload.get("schemaName")?.takeUnless { it.isJsonNull }?.asString,
                search = payload.get("search")?.asString.orEmpty(),
                includeViews = payload.get("includeViews")?.asBoolean ?: true,
                limit = payload.get("limit")?.asInt ?: 500,
                connectTimeoutSeconds = payload.get("connectTimeoutSeconds")?.asInt ?: 10,
                networkTimeoutSeconds = payload.get("networkTimeoutSeconds")?.asInt ?: 30,
            )
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    org.jmixworkbench.services.DatabaseEntityTableBrowseResponse.failure(
                        "JVW-DB-BROWSE-REQUEST-INVALID",
                        error.message ?: "The database browse request is malformed.",
                    ),
                ),
            )
            return
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val response = DatabaseReverseEngineeringService.getInstance(project)
                .browseEntityTables(request)
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    sendResponse(action, requestId, gson.toJson(response))
                }
            }, ModalityState.any())
        }
    }

    private fun handlePlanDatabaseEntityImport(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, DatabaseEntityImportRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    org.jmixworkbench.services.DatabaseEntityImportPlanResponse.failure(
                        "JVW-DB-IMPORT-REQUEST-INVALID",
                        error.message ?: "The database import request is malformed.",
                    ),
                ),
            )
            return
        }
        AppExecutorUtil.getAppExecutorService().submit {
            val response = DatabaseReverseEngineeringService.getInstance(project)
                .planEntityImport(request)
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    sendResponse(action, requestId, gson.toJson(response))
                }
            }, ModalityState.any())
        }
    }

    private fun handlePreviewDatabaseEntityImport(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(
                payload.getAsJsonObject("request"),
                DatabaseEntityImportRequest::class.java,
            )
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        val expectedSnapshotDigest = payload.get("expectedSnapshotDigest")?.asString.orEmpty()
        AppExecutorUtil.getAppExecutorService().submit {
            val plan = DatabaseReverseEngineeringService.getInstance(project)
                .planEntityImport(request)
            if (
                !plan.accepted ||
                !plan.ready ||
                plan.snapshotDigest.isNullOrBlank() ||
                plan.snapshotDigest != expectedSnapshotDigest
            ) {
                sendDatabaseImportPreviewRejection(action, requestId, plan, expectedSnapshotDigest)
                return@submit
            }
            val config = JmixProjectService.getInstance(project).getConfig()
            if (config == null) {
                ApplicationManager.getApplication().invokeLater({
                    sendGenerationRequestError(
                        action,
                        requestId,
                        IllegalStateException("Not a Jmix project."),
                    )
                }, ModalityState.any())
                return@submit
            }
            ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
                CodeGenerationService.getInstance(project)
                    .previewDatabaseEntityImport(plan.entities, config)
            }
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.any()) { response ->
                    sendResponse(action, requestId, gson.toJson(response))
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    private fun handleApplyDatabaseEntityImport(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(
                payload.getAsJsonObject("request"),
                DatabaseEntityImportRequest::class.java,
            )
        }.getOrElse { error ->
            sendGenerationApplyError(action, requestId, error)
            return
        }
        val expectedSnapshotDigest = payload.get("expectedSnapshotDigest")?.asString.orEmpty()
        val expectedPlanDigest = payload.get("expectedPlanDigest")?.asString.orEmpty()
        AppExecutorUtil.getAppExecutorService().submit {
            val plan = DatabaseReverseEngineeringService.getInstance(project)
                .planEntityImport(request)
            if (
                !plan.accepted ||
                !plan.ready ||
                plan.snapshotDigest.isNullOrBlank() ||
                plan.snapshotDigest != expectedSnapshotDigest
            ) {
                ApplicationManager.getApplication().invokeLater({
                    sendGenerationApplyError(
                        action,
                        requestId,
                        IllegalStateException(
                            plan.issues.firstOrNull()?.message
                                ?: "The live database schema changed after review. Refresh the import plan.",
                        ),
                    )
                }, ModalityState.any())
                return@submit
            }
            val config = JmixProjectService.getInstance(project).getConfig()
            if (config == null) {
                ApplicationManager.getApplication().invokeLater({
                    sendGenerationApplyError(
                        action,
                        requestId,
                        IllegalStateException("Not a Jmix project."),
                    )
                }, ModalityState.any())
                return@submit
            }
            ReadAction.nonBlocking<PreparedWorkspaceChange> {
                CodeGenerationService.getInstance(project).prepareDatabaseEntityImport(
                    plan.entities,
                    config,
                    expectedPlanDigest,
                )
            }
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.any()) { prepared ->
                    val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                    sendResponse(action, requestId, gson.toJson(response))
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    private fun sendDatabaseImportPreviewRejection(
        action: String,
        requestId: String?,
        plan: org.jmixworkbench.services.DatabaseEntityImportPlanResponse,
        expectedSnapshotDigest: String,
    ) {
        val issue = when {
            plan.issues.isNotEmpty() -> plan.issues.first()
            plan.snapshotDigest != expectedSnapshotDigest ->
                org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                    "JVW-DB-IMPORT-SNAPSHOT-STALE",
                    "The live database schema changed after review. Refresh the import plan.",
                )
            else -> org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                "JVW-DB-IMPORT-NOT-READY",
                "Resolve every blocked table mapping before previewing generation.",
            )
        }
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                sendResponse(
                    action,
                    requestId,
                    gson.toJson(
                        org.jmixworkbench.services.WorkspaceChangePreviewResponse(
                            accepted = false,
                            changeSetId = "database-import:rejected",
                            label = "Database entity import rejected",
                            planDigest = null,
                            files = emptyList(),
                            issues = listOf(issue),
                        ),
                    ),
                )
            }
        }, ModalityState.any())
    }

    private fun handleInspectEntityAttributePropagation(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributePropagationInspectionRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<EntityAttributePropagationInspectionResponse> {
            EntityAttributePropagationService.getInstance(project).inspect(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewEntityAttributePropagation(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributePropagationChangeRequest::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            EntityAttributePropagationService.getInstance(project).preview(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyEntityAttributePropagation(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, EntityAttributePropagationApplyRequest::class.java)
        }.getOrElse { error ->
            sendGenerationApplyError(action, requestId, error)
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            EntityAttributePropagationService.getInstance(project).prepareApply(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewCrudGeneration(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload.getAsJsonObject("entity"), EntityModel::class.java) to
                gson.fromJson(payload.getAsJsonObject("options"), CrudOrchestrator.CrudOptions::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        val config = JmixProjectService.getInstance(project).getConfig()
        if (config == null) {
            sendGenerationRequestError(action, requestId, IllegalStateException("Not a Jmix project."))
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            CodeGenerationService.getInstance(project).previewCrudGeneration(
                request.first,
                config,
                request.second,
            )
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyCrudGeneration(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload.getAsJsonObject("entity"), EntityModel::class.java) to
                gson.fromJson(payload.getAsJsonObject("options"), CrudOrchestrator.CrudOptions::class.java)
        }.getOrElse { error ->
            sendGenerationApplyError(action, requestId, error)
            return
        }
        val config = JmixProjectService.getInstance(project).getConfig()
        if (config == null) {
            sendGenerationApplyError(action, requestId, IllegalStateException("Not a Jmix project."))
            return
        }
        val expectedPlanDigest = payload.get("expectedPlanDigest")?.asString.orEmpty()
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            CodeGenerationService.getInstance(project).prepareCrudGeneration(
                request.first,
                config,
                request.second,
                expectedPlanDigest,
            )
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleLoadWorkflowModel(action: String, requestId: String?, payload: JsonObject) {
        val relativePath = payload.get("relativePath")?.asString.orEmpty()
        val processId = payload.get("processId")?.asString.orEmpty()
        val moduleId = payload.get("moduleId")?.asString.orEmpty()
        if (relativePath.isBlank() || processId.isBlank()) {
            sendGenerationRequestError(
                action,
                requestId,
                IllegalArgumentException("A workflow source path and process id are required."),
            )
            return
        }
        ReadAction.nonBlocking<WorkflowLoadResponse> {
            WorkflowWorkspaceService.getInstance(project).load(relativePath, processId, moduleId)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewWorkflowGeneration(action: String, requestId: String?, payload: JsonObject) {
        val workflow = runCatching {
            gson.fromJson(payload, WorkflowModel::class.java)
        }.getOrElse { error ->
            sendGenerationRequestError(action, requestId, error)
            return
        }
        val config = JmixProjectService.getInstance(project).getConfig()
        if (config == null) {
            sendGenerationRequestError(action, requestId, IllegalStateException("Not a Jmix project."))
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            CodeGenerationService.getInstance(project).previewWorkflowGeneration(workflow, config)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyWorkflowGeneration(action: String, requestId: String?, payload: JsonObject) {
        val workflow = runCatching {
            gson.fromJson(payload.getAsJsonObject("workflow"), WorkflowModel::class.java)
        }.getOrElse { error ->
            sendGenerationApplyError(action, requestId, error)
            return
        }
        val config = JmixProjectService.getInstance(project).getConfig()
        if (config == null) {
            sendGenerationApplyError(action, requestId, IllegalStateException("Not a Jmix project."))
            return
        }
        val expectedPlanDigest = payload.get("expectedPlanDigest")?.asString.orEmpty()
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            CodeGenerationService.getInstance(project).prepareWorkflowGeneration(
                workflow,
                config,
                expectedPlanDigest,
            )
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun sendGenerationRequestError(
        action: String,
        requestId: String?,
        error: Throwable,
    ) {
        sendResponse(
            action,
            requestId,
            gson.toJson(
                mapOf(
                    "accepted" to false,
                    "changeSetId" to "generation:rejected",
                    "label" to "Generation rejected",
                    "files" to emptyList<Any>(),
                    "issues" to listOf(
                        mapOf(
                            "code" to "JVW-GENERATION-REQUEST-INVALID",
                            "message" to (error.message ?: "The generation request is malformed."),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun sendGenerationApplyError(
        action: String,
        requestId: String?,
        error: Throwable,
    ) {
        sendResponse(
            action,
            requestId,
            gson.toJson(
                WorkspaceChangeApplyResponse(
                    success = false,
                    changeSetId = "generation:rejected",
                    planDigest = null,
                    filesChanged = emptyList(),
                    issues = listOf(
                        org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                            code = "JVW-GENERATION-REQUEST-INVALID",
                            message = error.message ?: "The generation apply request is malformed.",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun handleGetSecurityWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<SecurityWorkspaceSnapshot> {
            val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
            val sourceWorkspace = SecurityWorkspaceBuilder.build(
                SecurityWorkspaceInput(
                    artifacts = graph.artifacts,
                    relationships = graph.relationships,
                    diagnostics = graph.diagnostics,
                    graphDigest = graph.snapshotDigest,
                ),
            )
            sourceWorkspace.copy(
                runtime = RuntimeSecurityEvidenceService.getInstance(project).snapshot(sourceWorkspace),
            )
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleImportRuntimeSecurityEvidence(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, RuntimeSecurityEvidenceImportRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    RuntimeSecurityEvidenceImportResponse(
                        accepted = false,
                        sourceId = null,
                        message = error.message ?: "The runtime security evidence request is malformed.",
                        issues = emptyList(),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<RuntimeSecurityEvidenceImportResponse> {
            RuntimeSecurityEvidenceService.getInstance(project).importEvidence(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleClearRuntimeSecurityEvidence(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, RuntimeSecurityEvidenceClearRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    RuntimeSecurityEvidenceImportResponse(
                        accepted = false,
                        sourceId = null,
                        message = error.message ?: "The clear runtime security evidence request is malformed.",
                        issues = emptyList(),
                    ),
                ),
            )
            return
        }
        val response = RuntimeSecurityEvidenceService.getInstance(project).clear(request)
        sendResponse(action, requestId, gson.toJson(response))
    }

    private fun handleNavigateToSource(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, SourceNavigationRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    org.jmixworkbench.services.SourceNavigationResponse(
                        success = false,
                        errorCode = "JVW-NAVIGATION-REQUEST-INVALID",
                        message = error.message ?: "The navigation request is malformed.",
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedSourceNavigation> {
            ApplicationGraphService.getInstance(project).prepareNavigation(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                if (prepared.success && prepared.file != null) {
                    OpenFileDescriptor(
                        project,
                        prepared.file,
                        prepared.zeroBasedLine,
                        prepared.zeroBasedColumn,
                    ).navigate(true)
                }
                sendResponse(action, requestId, gson.toJson(prepared.response()))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleGetFlowUiWorkspace(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiWorkspaceRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    FlowUiWorkspaceResponse(
                        accepted = false,
                        document = null,
                        contextArtifacts = emptyList(),
                        contextRelationships = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-FLOWUI-REQUEST-INVALID",
                                message = error.message ?: "The FlowUI workspace request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<FlowUiWorkspaceResponse> {
            FlowUiWorkspaceService.getInstance(project).load(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewFlowUiPropertyChange(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiPropertyChangeRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "flowui-property:rejected",
                        "label" to "FlowUI property change rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-FLOWUI-REQUEST-INVALID",
                                "message" to (error.message ?: "The FlowUI property request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            FlowUiWorkspaceService.getInstance(project).previewPropertyChange(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyFlowUiPropertyChange(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiPropertyApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "flowui-property:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-FLOWUI-REQUEST-INVALID",
                                message = error.message ?: "The FlowUI property apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            FlowUiWorkspaceService.getInstance(project).preparePropertyChange(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewFlowUiStructureChange(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiStructureChangeRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "flowui-structure:rejected",
                        "label" to "FlowUI structure change rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-FLOWUI-REQUEST-INVALID",
                                "message" to (error.message ?: "The FlowUI structure request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            FlowUiWorkspaceService.getInstance(project).previewStructureChange(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyFlowUiStructureChange(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiStructureApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "flowui-structure:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-FLOWUI-REQUEST-INVALID",
                                message = error.message ?: "The FlowUI structure apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            FlowUiWorkspaceService.getInstance(project).prepareStructureChange(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewFlowUiDirectTextChange(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiDirectTextChangeRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "flowui-text:rejected",
                        "label" to "FlowUI text change rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-FLOWUI-REQUEST-INVALID",
                                "message" to (error.message ?: "The FlowUI direct-text request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            FlowUiWorkspaceService.getInstance(project).previewDirectTextChange(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyFlowUiDirectTextChange(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiDirectTextApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "flowui-text:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-FLOWUI-REQUEST-INVALID",
                                message = error.message ?: "The FlowUI direct-text apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            FlowUiWorkspaceService.getInstance(project).prepareDirectTextChange(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewFlowUiControllerInjection(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiControllerInjectionRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "flowui-controller-injection:rejected",
                        "label" to "Controller injection rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-CONTROLLER-REQUEST-INVALID",
                                "message" to (error.message ?: "The controller injection request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            FlowUiControllerChangeService.getInstance(project).previewInjection(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyFlowUiControllerInjection(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiControllerInjectionApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "flowui-controller-injection:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-CONTROLLER-REQUEST-INVALID",
                                message = error.message ?: "The controller injection apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            FlowUiControllerChangeService.getInstance(project).prepareInjection(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewFlowUiControllerHandler(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiControllerHandlerRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "flowui-controller-handler:rejected",
                        "label" to "Controller handler rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-CONTROLLER-REQUEST-INVALID",
                                "message" to (error.message ?: "The controller handler request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            FlowUiControllerChangeService.getInstance(project).previewHandler(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyFlowUiControllerHandler(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, FlowUiControllerHandlerApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "flowui-controller-handler:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-CONTROLLER-REQUEST-INVALID",
                                message = error.message ?: "The controller handler apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            FlowUiControllerChangeService.getInstance(project).prepareHandler(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleInspectJmixRuntime(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, JmixRuntimeInspectionRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "targets" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-RUNTIME-REQUEST-INVALID",
                                "message" to (error.message ?: "The runtime inspection request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            val response = runCatching {
                JmixRuntimeService.getInstance(project).inspect(request)
            }.getOrElse { error ->
                org.jmixworkbench.services.JmixRuntimeInspectionResponse(
                    accepted = false,
                    viewId = null,
                    targets = emptyList(),
                    issues = listOf(
                        org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                            "JVW-RUNTIME-INSPECTION-FAILED",
                            error.message ?: "Runtime inspection failed.",
                        ),
                    ),
                )
            }
            sendResponseOnUiThread(action, requestId, gson.toJson(response))
        }
    }

    private fun handleOpenJmixRuntimePreview(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, JmixRuntimeOpenPreviewRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    org.jmixworkbench.services.JmixRuntimeActionResponse(
                        false,
                        error.message ?: "The runtime preview request is malformed.",
                    ),
                ),
            )
            return
        }
        ApplicationManager.getApplication().invokeLater({
            val response = JmixRuntimeService.getInstance(project).openPreview(request)
            sendResponse(action, requestId, gson.toJson(response))
        }, ModalityState.any())
    }

    private fun handlePreviewFlowUiHotDeploy(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, JmixFlowUiHotDeployRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "flowui-hot-deploy:rejected",
                        "label" to "FlowUI hot deploy rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-RUNTIME-REQUEST-INVALID",
                                "message" to (error.message ?: "The hot-deploy request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            val response = JmixRuntimeService.getInstance(project).previewHotDeploy(request)
            sendResponseOnUiThread(action, requestId, gson.toJson(response))
        }
    }

    private fun handleApplyFlowUiHotDeploy(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, JmixFlowUiHotDeployApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "flowui-hot-deploy:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                "JVW-RUNTIME-REQUEST-INVALID",
                                error.message ?: "The hot-deploy apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            val prepared = JmixRuntimeService.getInstance(project).prepareHotDeploy(request)
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }, ModalityState.any())
        }
    }

    private fun handlePreviewWorkspaceChange(action: String, requestId: String?, payload: JsonObject) {
        val changeSet = runCatching {
            gson.fromJson(payload, WorkspaceChangeSet::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-CHANGE-REQUEST-INVALID",
                                "message" to (error.message ?: "The workspace change request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            WorkspaceChangeService.getInstance(project).preview(changeSet)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplyWorkspaceChange(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, WorkspaceChangeApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-CHANGE-REQUEST-INVALID",
                                message = error.message ?: "The workspace change request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            WorkspaceChangeService.getInstance(project).prepareApply(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewSecurityRoleCreate(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRoleCreateRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "security-role-create:rejected",
                        "label" to "Security role creation rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-ROLE-REQUEST-INVALID",
                                "message" to (error.message ?: "The security role request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            SecurityRoleChangeService.getInstance(project).previewCreate(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleGetSecurityRoleDestinations(action: String, requestId: String?) {
        ReadAction.nonBlocking<org.jmixworkbench.services.SecurityRoleDestinationsResponse> {
            SecurityRoleChangeService.getInstance(project).destinations()
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplySecurityRoleCreate(action: String, requestId: String?, payload: JsonObject) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRoleCreateApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "security-role-create:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-ROLE-REQUEST-INVALID",
                                message = error.message ?: "The security role apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            SecurityRoleChangeService.getInstance(project).prepareCreate(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewSecurityRolePolicyAddition(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRolePolicyChangeRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    mapOf(
                        "accepted" to false,
                        "changeSetId" to "security-role-policy:rejected",
                        "label" to "Security policy change rejected",
                        "files" to emptyList<Any>(),
                        "issues" to listOf(
                            mapOf(
                                "code" to "JVW-ROLE-POLICY-REQUEST-INVALID",
                                "message" to (error.message ?: "The security policy request is malformed."),
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            SecurityRoleChangeService.getInstance(project).previewPolicyAddition(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplySecurityRolePolicyAddition(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRolePolicyChangeApplyRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    WorkspaceChangeApplyResponse(
                        success = false,
                        changeSetId = "security-role-policy:rejected",
                        planDigest = null,
                        filesChanged = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-ROLE-POLICY-REQUEST-INVALID",
                                message = error.message ?: "The security policy apply request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            SecurityRoleChangeService.getInstance(project).preparePolicyAddition(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleInspectSecurityRolePolicies(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRolePolicyInspectionRequest::class.java)
        }.getOrElse { error ->
            sendResponse(
                action,
                requestId,
                gson.toJson(
                    SecurityRolePolicyInspectionResponse(
                        accepted = false,
                        policies = emptyList(),
                        issues = listOf(
                            org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                                code = "JVW-ROLE-POLICY-REQUEST-INVALID",
                                message = error.message ?: "The policy inspection request is malformed.",
                            ),
                        ),
                    ),
                ),
            )
            return
        }
        ReadAction.nonBlocking<SecurityRolePolicyInspectionResponse> {
            SecurityRoleChangeService.getInstance(project).inspectPolicies(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { response ->
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewSecurityRolePolicyReplacement(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRolePolicyReplacementRequest::class.java)
        }.getOrElse { error ->
            sendPolicyPreviewRequestError(action, requestId, error, "replacement")
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            SecurityRoleChangeService.getInstance(project).previewPolicyReplacement(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplySecurityRolePolicyReplacement(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRolePolicyReplacementApplyRequest::class.java)
        }.getOrElse { error ->
            sendPolicyApplyRequestError(action, requestId, error, "replacement")
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            SecurityRoleChangeService.getInstance(project).preparePolicyReplacement(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handlePreviewSecurityRolePolicyRemoval(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRolePolicyRemovalRequest::class.java)
        }.getOrElse { error ->
            sendPolicyPreviewRequestError(action, requestId, error, "removal")
            return
        }
        ReadAction.nonBlocking<org.jmixworkbench.services.WorkspaceChangePreviewResponse> {
            SecurityRoleChangeService.getInstance(project).previewPolicyRemoval(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { preview ->
                sendResponse(action, requestId, gson.toJson(preview))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun handleApplySecurityRolePolicyRemoval(
        action: String,
        requestId: String?,
        payload: JsonObject,
    ) {
        val request = runCatching {
            gson.fromJson(payload, SecurityRolePolicyRemovalApplyRequest::class.java)
        }.getOrElse { error ->
            sendPolicyApplyRequestError(action, requestId, error, "removal")
            return
        }
        ReadAction.nonBlocking<PreparedWorkspaceChange> {
            SecurityRoleChangeService.getInstance(project).preparePolicyRemoval(request)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun sendPolicyPreviewRequestError(
        action: String,
        requestId: String?,
        error: Throwable,
        operation: String,
    ) {
        sendResponse(
            action,
            requestId,
            gson.toJson(
                mapOf(
                    "accepted" to false,
                    "changeSetId" to "security-role-policy-$operation:rejected",
                    "label" to "Security policy $operation rejected",
                    "files" to emptyList<Any>(),
                    "issues" to listOf(
                        mapOf(
                            "code" to "JVW-ROLE-POLICY-REQUEST-INVALID",
                            "message" to (error.message ?: "The security policy $operation request is malformed."),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun sendPolicyApplyRequestError(
        action: String,
        requestId: String?,
        error: Throwable,
        operation: String,
    ) {
        sendResponse(
            action,
            requestId,
            gson.toJson(
                WorkspaceChangeApplyResponse(
                    success = false,
                    changeSetId = "security-role-policy-$operation:rejected",
                    planDigest = null,
                    filesChanged = emptyList(),
                    issues = listOf(
                        org.jmixworkbench.discovery.change.WorkspaceChangeIssue(
                            code = "JVW-ROLE-POLICY-REQUEST-INVALID",
                            message = error.message ?: "The security policy $operation request is malformed.",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun sendResponse(action: String, requestId: String?, resultJson: String) {
        val actionJson = gson.toJson(action)
        val requestIdJson = gson.toJson(requestId)
        val script = """
            if (window.onBridgeResponse) {
                window.onBridgeResponse($actionJson, $requestIdJson, $resultJson);
            }
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    private fun sendResponseOnUiThread(action: String, requestId: String?, resultJson: String) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) sendResponse(action, requestId, resultJson)
        }, ModalityState.any())
    }

    fun dispose() {
        jsQuery.dispose()
    }
}

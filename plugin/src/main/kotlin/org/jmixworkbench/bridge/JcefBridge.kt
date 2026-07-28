package org.jmixworkbench.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.jmixworkbench.generator.CrudOrchestrator
import org.jmixworkbench.model.*
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.services.CodeGenerationService
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
import org.jmixworkbench.services.PreparedWorkspaceChange
import org.jmixworkbench.services.PreparedSourceNavigation
import org.jmixworkbench.services.SourceNavigationRequest
import org.jmixworkbench.services.JmixProjectService
import org.jmixworkbench.services.WorkspaceChangeApplyRequest
import org.jmixworkbench.services.WorkspaceChangeApplyResponse
import org.jmixworkbench.services.WorkspaceChangeService
import org.jmixworkbench.toolwindow.isPackagedWorkbenchOriginUrl
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
    private val browser: JBCefBrowser
) {
    private val log = Logger.getInstance(JcefBridge::class.java)
    private val gson = Gson()
    private val jsQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)

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
        val script = """
            window.javaBridge = {
                send: function(action, payload, requestId) {
                    var request = JSON.stringify({ action: action, payload: payload, requestId: requestId });
                    $injection
                }
            };
            if (window.onBridgeReady) { window.onBridgeReady(); }
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
            if (action == "navigateToSource") {
                handleNavigateToSource(action, requestId, payload)
                return
            }
            if (action == "getFlowUiWorkspace") {
                handleGetFlowUiWorkspace(action, requestId, payload)
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
            if (action == "previewWorkspaceChange") {
                handlePreviewWorkspaceChange(action, requestId, payload)
                return
            }
            if (action == "applyWorkspaceChange") {
                handleApplyWorkspaceChange(action, requestId, payload)
                return
            }

            val result = when (action) {
                "generateEntity" -> handleGenerateEntity(payload)
                "generateCrud" -> handleGenerateCrud(payload)
                "generateView" -> handleGenerateView(payload)
                "generateMigration" -> handleGenerateMigration(payload)
                "generateRole" -> handleGenerateRole(payload)
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
        val migration = gson.fromJson(payload, MigrationModel::class.java)
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
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, requestId, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { prepared ->
                val response = WorkspaceChangeService.getInstance(project).applyPrepared(prepared)
                sendResponse(action, requestId, gson.toJson(response))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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

    fun dispose() {
        jsQuery.dispose()
    }
}

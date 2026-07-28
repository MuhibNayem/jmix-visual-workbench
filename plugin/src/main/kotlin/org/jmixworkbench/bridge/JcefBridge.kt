package org.jmixworkbench.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.jmixworkbench.generator.CrudOrchestrator
import org.jmixworkbench.model.*
import org.jmixworkbench.services.CodeGenerationService
import org.jmixworkbench.services.ApplicationGraphService
import org.jmixworkbench.services.JmixProjectService
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
                send: function(action, payload) {
                    var request = JSON.stringify({ action: action, payload: payload });
                    $injection
                }
            };
            if (window.onBridgeReady) { window.onBridgeReady(); }
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    private fun handleRequest(requestJson: String) {
        try {
            val json = JsonParser.parseString(requestJson).asJsonObject
            val action = json.get("action").asString
            val payload = json.getAsJsonObject("payload")

            log.info("Bridge request: $action")

            if (action == "getApplicationGraph") {
                handleGetApplicationGraph(action, payload)
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

            sendResponse(action, result)
        } catch (e: Exception) {
            log.error("Bridge error", e)
            sendResponse("error", """{"error":"${e.message}"}""")
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

    private fun handleGetApplicationGraph(action: String, payload: JsonObject) {
        val forceRefresh = payload.get("forceRefresh")?.asBoolean ?: false
        ReadAction.nonBlocking<org.jmixworkbench.services.ApplicationGraphResponse> {
            ApplicationGraphService.getInstance(project).graph(forceRefresh)
        }
            .expireWith(project)
            .finishOnUiThread(ModalityState.any()) { result ->
                sendResponse(action, gson.toJson(result))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun sendResponse(action: String, resultJson: String) {
        val script = """
            if (window.onBridgeResponse) {
                window.onBridgeResponse('$action', $resultJson);
            }
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    fun dispose() {
        jsQuery.dispose()
    }
}

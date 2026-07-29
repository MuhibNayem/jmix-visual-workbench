package org.jmixworkbench.services

import com.google.gson.Gson
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.model.WorkflowEmailAttachmentModel
import org.jmixworkbench.model.WorkflowEmailContentType
import org.jmixworkbench.model.WorkflowAuditLevel
import org.jmixworkbench.model.WorkflowFormData
import org.jmixworkbench.model.WorkflowFormField
import org.jmixworkbench.model.WorkflowFormOpenMode
import org.jmixworkbench.model.WorkflowFormOutcome
import org.jmixworkbench.model.WorkflowFormType
import org.jmixworkbench.model.WorkflowEntityDataOperation
import org.jmixworkbench.model.WorkflowLaneModel
import org.jmixworkbench.model.WorkflowListenerImplementationType
import org.jmixworkbench.model.WorkflowListenerModel
import org.jmixworkbench.model.WorkflowLoadResponse
import org.jmixworkbench.model.WorkflowLoadResultMode
import org.jmixworkbench.model.WorkflowModel
import org.jmixworkbench.model.WorkflowMultiInstanceMode
import org.jmixworkbench.model.WorkflowNodeModel
import org.jmixworkbench.model.WorkflowNodeType
import org.jmixworkbench.model.WorkflowSignalScope
import org.jmixworkbench.model.WorkflowProcessVariable
import org.jmixworkbench.model.WorkflowTimerType
import org.jmixworkbench.model.WorkflowTransitionModel
import org.jmixworkbench.model.WorkflowVariableMapping
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Strict, XXE-safe reader for the BPMN subset that the visual workflow
 * designer can round-trip without dropping executable semantics.
 */
object WorkflowXmlParser {
    private val gson = Gson()
    private val supportedNodeTags = setOf(
        "startEvent",
        "userTask",
        "serviceTask",
        "scriptTask",
        "exclusiveGateway",
        "parallelGateway",
        "inclusiveGateway",
        "callActivity",
        "intermediateCatchEvent",
        "intermediateThrowEvent",
        "boundaryEvent",
        "endEvent",
        "subProcess",
        "transaction",
    )
    private val knownProcessChildren = supportedNodeTags + setOf(
        "documentation",
        "extensionElements",
        "laneSet",
        "sequenceFlow",
        "association",
    )
    private val knownExtensionChildren = setOf(
        "properties",
        "failedJobRetryTimeCycle",
        "field",
        "executionListener",
        "taskListener",
        "in",
        "out",
        "processVariables",
        "formData",
    )
    private val knownFieldNames = setOf(
        "decisionTableReferenceKey",
        "jpql",
        "resultVariable",
        "saveLoadResultAs",
        "jpqlParameters",
        "entityName",
        "processVariable",
        "entityAttributes",
        "to",
        "cc",
        "bcc",
        "from",
        "subject",
        "content",
        "contentType",
        "sendAsync",
        "attachments",
    )

    fun parse(
        xml: String,
        moduleId: String,
        relativePath: String,
        requestedProcessId: String? = null,
    ): WorkflowLoadResponse = runCatching {
        val document = secureFactory().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement ?: error("BPMN document has no root element.")
        require(root.localTag() == "definitions") { "The selected source is not a BPMN definitions document." }
        val processes = root.directChildren().filter { it.localTag() == "process" }
        val process = requestedProcessId
            ?.let { id -> processes.firstOrNull { it.attr("id") == id } }
            ?: processes.singleOrNull()
            ?: error("Select a BPMN file containing exactly one process.")

        val unsupported = linkedSetOf<String>()
        if (processes.size != 1) unsupported += "multiple process definitions in one file"
        if (process.containsComments()) unsupported += "XML comments"
        process.directChildren().filter { it.localTag() !in knownProcessChildren }.forEach {
            unsupported += it.localTag()
        }
        process.descendants("extensionElements").forEach { extension ->
            extension.directChildren().filter { it.localTag() !in knownExtensionChildren }.forEach {
                unsupported += "extension:${it.nodeName}"
            }
        }
        process.descendants("field").filter { it.attr("name") !in knownFieldNames }.forEach {
            unsupported += "flowable:field:${it.attr("name").ifBlank { "unnamed" }}"
        }
        process.descendants("formData").forEach { form ->
            form.directChildren().filter { it.localTag() !in setOf("formFields", "formOutcomes") }.forEach {
                unsupported += "formData:${it.localTag()}"
            }
            form.descendants("formField").forEach { field ->
                field.directChildren().filter { it.localTag() != "formFieldProperty" }.forEach {
                    unsupported += "formField:${it.localTag()}"
                }
            }
            form.descendants("formOutcome").filter { it.directChildren().isNotEmpty() }.forEach {
                unsupported += "formOutcome:${it.attr("id")}:nested metadata"
            }
        }

        val processProperties = process.workbenchProperties()
        val shapes = diagramPositions(root)
        val lanes = parseLanes(process)
        val laneByNode = lanes.flatMap { lane ->
            process.descendants("lane")
                .filter { it.attr("id") == lane.id }
                .flatMap { laneElement ->
                    laneElement.directChildren("flowNodeRef").map { it.textContent.trim() }
                }
                .map { nodeId -> nodeId to lane.id }
        }.toMap()
        val signalScopes = root.directChildren("signal").associate { signal ->
            signal.attr("id") to if (signal.attr("flowable:scope") == "processInstance") {
                WorkflowSignalScope.PROCESS_INSTANCE
            } else {
                WorkflowSignalScope.GLOBAL
            }
        }
        val associations = process.descendants("association").associateBy(
            keySelector = { it.attr("sourceRef") },
            valueTransform = { it.attr("targetRef") },
        )
        val parsed = parseScope(
            scope = process,
            parentSubprocessId = null,
            indexCounter = IndexCounter(),
            shapes = shapes,
            laneByNode = laneByNode,
            signalScopes = signalScopes,
            associations = associations,
            unsupported = unsupported,
        )
        val processId = process.attr("id")
        require(processId.isNotBlank()) { "BPMN process id is required." }
        WorkflowLoadResponse(
            workflow = WorkflowModel(
                id = processId,
                name = process.attr("name").ifBlank { processId },
                moduleId = moduleId,
                entityQualifiedName = processProperties["jmixWorkbench.entityClass"],
                stateAttribute = processProperties["jmixWorkbench.stateAttribute"],
                candidateStarterGroups = process.attr("flowable:candidateStarterGroups").commaList(),
                candidateStarterUsers = process.attr("flowable:candidateStarterUsers").commaList(),
                businessKeyExpression = processProperties["jmixWorkbench.businessKeyExpression"],
                versionTag = processProperties["jmixWorkbench.versionTag"],
                tenantExpression = processProperties["jmixWorkbench.tenantExpression"],
                auditLevel = processProperties["jmixWorkbench.auditLevel"]
                    ?.let { runCatching { WorkflowAuditLevel.valueOf(it) }.getOrNull() }
                    ?: WorkflowAuditLevel.FULL,
                lanes = lanes,
                executionListeners = process.directExtensionChildren("executionListener")
                    .mapNotNull(::parseListener),
                sourceRelativePath = relativePath,
                sourceFingerprint = CanonicalDiscoveryJson.sha256(xml),
                documentation = process.directChildren("documentation")
                    .firstOrNull()?.textContent?.trim()?.ifBlank { null },
                nodes = parsed.nodes,
                transitions = parsed.transitions,
            ),
            editable = unsupported.isEmpty(),
            unsupportedElements = unsupported.toList(),
            warnings = buildList {
                if (shapes.isEmpty()) add("No BPMN DI positions were found; a deterministic grid layout was applied.")
                if (unsupported.isNotEmpty()) {
                    add("Unsupported constructs are preserved by leaving this workflow read-only.")
                }
            },
        )
    }.getOrElse { error ->
        WorkflowLoadResponse(error = error.message ?: "Unable to parse BPMN workflow.")
    }

    private fun parseScope(
        scope: Element,
        parentSubprocessId: String?,
        indexCounter: IndexCounter,
        shapes: Map<String, DiagramPosition>,
        laneByNode: Map<String, String>,
        signalScopes: Map<String, WorkflowSignalScope>,
        associations: Map<String, String>,
        unsupported: MutableSet<String>,
    ): ParsedScope {
        val nodes = mutableListOf<WorkflowNodeModel>()
        val transitions = scope.directChildren("sequenceFlow").map(::parseTransition).toMutableList()
        if (parentSubprocessId != null) {
            scope.directChildren().filter { it.localTag() !in knownProcessChildren }.forEach {
                unsupported += "subprocess:$parentSubprocessId:${it.localTag()}"
            }
        }
        scope.directChildren().filter { it.localTag() in supportedNodeTags }.forEach { element ->
            val node = parseNode(
                element = element,
                index = indexCounter.value++,
                position = shapes[element.attr("id")],
                laneId = laneByNode[element.attr("id")],
                signalScopes = signalScopes,
                associationTarget = associations[element.attr("id")],
                parentSubprocessId = parentSubprocessId,
                unsupported = unsupported,
            ) ?: return@forEach
            nodes += node
            if (node.type in subprocessTypes) {
                val nested = parseScope(
                    scope = element,
                    parentSubprocessId = node.id,
                    indexCounter = indexCounter,
                    shapes = shapes,
                    laneByNode = laneByNode,
                    signalScopes = signalScopes,
                    associations = associations,
                    unsupported = unsupported,
                )
                nodes += nested.nodes
                transitions += nested.transitions
            }
        }
        return ParsedScope(nodes, transitions)
    }

    private fun parseTransition(flow: Element): WorkflowTransitionModel {
        val properties = flow.workbenchProperties()
        return WorkflowTransitionModel(
            id = flow.attr("id"),
            sourceId = flow.attr("sourceRef"),
            targetId = flow.attr("targetRef"),
            name = flow.attr("name").ifBlank { null },
            conditionExpression = flow.directChildren("conditionExpression")
                .firstOrNull()?.textContent?.trim()?.ifBlank { null },
            outcomeId = properties["jmixWorkbench.outcomeId"],
            requiredRoleCodes = properties.csv("jmixWorkbench.requiredRoles"),
            requiredDocuments = properties.csv("jmixWorkbench.requiredDocuments"),
            validationRules = properties.csv("jmixWorkbench.validations"),
            sideEffects = properties.csv("jmixWorkbench.sideEffects"),
            notifications = properties.csv("jmixWorkbench.notifications"),
        )
    }

    private fun parseNode(
        element: Element,
        index: Int,
        position: DiagramPosition?,
        laneId: String?,
        signalScopes: Map<String, WorkflowSignalScope>,
        associationTarget: String?,
        parentSubprocessId: String?,
        unsupported: MutableSet<String>,
    ): WorkflowNodeModel? {
        val type = nodeType(element) ?: run {
            unsupported += "${element.localTag()}:${element.attr("id")}"
            return null
        }
        val properties = element.workbenchProperties()
        val multi = element.directChildren("multiInstanceLoopCharacteristics").firstOrNull()
        val retryCycle = element.descendants("failedJobRetryTimeCycle")
            .firstOrNull()?.textContent?.trim()?.ifBlank { null }
        val eventReference = when (type) {
            WorkflowNodeType.MESSAGE_START,
            WorkflowNodeType.MESSAGE_CATCH,
            WorkflowNodeType.BOUNDARY_MESSAGE ->
                element.directChildren("messageEventDefinition").firstOrNull()?.attr("messageRef")

            WorkflowNodeType.SIGNAL_START,
            WorkflowNodeType.SIGNAL_CATCH,
            WorkflowNodeType.SIGNAL_THROW,
            WorkflowNodeType.BOUNDARY_SIGNAL ->
                element.directChildren("signalEventDefinition").firstOrNull()?.attr("signalRef")

            WorkflowNodeType.BOUNDARY_ERROR,
            WorkflowNodeType.ERROR_START,
            WorkflowNodeType.ERROR_END ->
                element.directChildren("errorEventDefinition").firstOrNull()?.attr("errorRef")

            else -> null
        }?.ifBlank { null }
        val timerDefinition = element.directChildren("timerEventDefinition").firstOrNull()
        val timer = timerDefinition?.directChildren()?.firstOrNull {
            it.localTag() in setOf("timeDuration", "timeDate", "timeCycle")
        }
        val service = parseServiceExpression(element.attr("flowable:expression"))
        if (element.localTag() == "serviceTask" && type == WorkflowNodeType.AUTOMATED_STATE) {
            if (element.attr("flowable:class").isNotBlank() ||
                element.attr("flowable:delegateExpression").isNotBlank() ||
                element.attr("flowable:type").isNotBlank() ||
                (element.attr("flowable:expression").isNotBlank() && service == null)
            ) {
                unsupported += "serviceTask implementation:${element.attr("id")}"
            }
        }
        val dmnKey = element.descendants("field")
            .firstOrNull { it.attr("name") == "decisionTableReferenceKey" }
            ?.descendants("string")?.firstOrNull()?.textContent?.trim()?.ifBlank { null }
        val flowableFields = element.directExtensionChildren("field").mapNotNull { field ->
            val name = field.attr("name").ifBlank { return@mapNotNull null }
            val value = field.directChildren("string").firstOrNull()
                ?.textContent?.trim()?.ifBlank { null }
                ?: field.attr("stringValue").ifBlank { null }
                ?: return@mapNotNull null
            name to value
        }.toMap()
        val compensation = element.directChildren("compensateEventDefinition").firstOrNull()
        val formData = element.directExtensionChildren("formData").firstOrNull()?.let(::parseFormData)
        val processVariables = element.directExtensionChildren("processVariables")
            .flatMap { it.directChildren("processVariable") }
            .mapNotNull { variable ->
                val name = variable.attr("name").ifBlank { return@mapNotNull null }
                val typeName = variable.attr("type").ifBlank { return@mapNotNull null }
                WorkflowProcessVariable(name, typeName)
            }
        val x = position?.x ?: (60 + (index % 4) * 220)
        val y = position?.y ?: (80 + (index / 4) * 130)
        val attachments = flowableFields["attachments"]?.let { value ->
            runCatching {
                gson.fromJson(value, Array<WorkflowEmailAttachmentModel>::class.java).toList()
            }.getOrElse {
                unsupported += "email attachments:${element.attr("id")}"
                emptyList()
            }
        }.orEmpty()
        return WorkflowNodeModel(
            id = element.attr("id"),
            name = element.attr("name").ifBlank { element.attr("id") },
            type = type,
            stateValue = properties["jmixWorkbench.stateValue"],
            actorRoleCodes = element.attr("flowable:candidateGroups").commaList(),
            assigneeExpression = element.attr("flowable:assignee").ifBlank { null },
            formKey = element.attr("flowable:formKey").ifBlank { null },
            formData = formData,
            processVariables = processVariables,
            dueDate = element.attr("flowable:dueDate").ifBlank { null },
            priority = element.attr("flowable:priority").ifBlank { null },
            serviceBean = service?.first,
            serviceMethod = service?.second,
            script = element.directChildren("script").firstOrNull()
                ?.textContent?.trim()?.ifBlank { null },
            resultVariable = element.attr("flowable:resultVariable").ifBlank {
                flowableFields["resultVariable"].orEmpty()
            }.ifBlank { null },
            entityDataOperation = when (element.attr("flowable:type")) {
                "jmix-modify-entity" -> WorkflowEntityDataOperation.MODIFY
                "jmix-create-entity" -> WorkflowEntityDataOperation.CREATE
                else -> WorkflowEntityDataOperation.LOAD
            },
            entityName = flowableFields["entityName"],
            entityVariable = flowableFields["processVariable"],
            jpql = flowableFields["jpql"],
            saveLoadResultAs = if (flowableFields["saveLoadResultAs"] == "collection") {
                WorkflowLoadResultMode.COLLECTION
            } else {
                WorkflowLoadResultMode.SINGLE
            },
            jpqlParametersJson = flowableFields["jpqlParameters"],
            entityAttributesJson = flowableFields["entityAttributes"],
            emailTo = flowableFields["to"],
            emailCc = flowableFields["cc"],
            emailBcc = flowableFields["bcc"],
            emailFrom = flowableFields["from"],
            emailSubject = flowableFields["subject"],
            emailContent = flowableFields["content"],
            emailContentType = when (flowableFields["contentType"]) {
                WorkflowEmailContentType.PLAIN_TEXT.xmlValue -> WorkflowEmailContentType.PLAIN_TEXT
                else -> WorkflowEmailContentType.HTML
            },
            emailSendAsync = flowableFields["sendAsync"]?.toBooleanStrictOrNull() ?: true,
            emailAttachments = attachments,
            async = element.attr("flowable:async").toBoolean() ||
                (type == WorkflowNodeType.SIGNAL_THROW &&
                    element.directChildren("signalEventDefinition").firstOrNull()
                        ?.attr("flowable:async").toBoolean()),
            exclusive = element.attr("flowable:exclusive").ifBlank { "true" }.toBoolean(),
            triggerable = element.attr("flowable:triggerable").toBoolean(),
            retryCycle = retryCycle,
            idempotencyKeyExpression = properties["jmixWorkbench.idempotencyKey"],
            multiInstanceMode = when {
                multi == null -> WorkflowMultiInstanceMode.NONE
                multi.attr("isSequential").toBoolean() -> WorkflowMultiInstanceMode.SEQUENTIAL
                else -> WorkflowMultiInstanceMode.PARALLEL
            },
            loopCardinality = multi?.directChildren("loopCardinality")
                ?.firstOrNull()?.textContent?.trim()?.ifBlank { null },
            collectionExpression = multi?.attr("flowable:collection")?.ifBlank { null },
            elementVariable = multi?.attr("flowable:elementVariable")?.ifBlank { null },
            completionCondition = multi?.directChildren("completionCondition")
                ?.firstOrNull()?.textContent?.trim()?.ifBlank { null },
            calledElement = element.attr("calledElement").ifBlank { null },
            inheritBusinessKey = element.attr("flowable:inheritBusinessKey")
                .ifBlank { "true" }.toBoolean(),
            inheritVariables = element.attr("flowable:inheritVariables")
                .ifBlank { "false" }.toBoolean(),
            decisionTableKey = dmnKey,
            timerType = when (timer?.localTag()) {
                "timeDate" -> WorkflowTimerType.DATE
                "timeCycle" -> WorkflowTimerType.CYCLE
                else -> WorkflowTimerType.DURATION
            },
            timerExpression = timer?.textContent?.trim()?.ifBlank { null },
            attachedToNodeId = element.attr("attachedToRef").ifBlank { null },
            cancelActivity = element.attr("cancelActivity").ifBlank { "true" }.toBoolean(),
            eventStartInterrupting = element.attr("isInterrupting").ifBlank { "true" }.toBoolean(),
            eventReference = eventReference,
            signalScope = eventReference?.let(signalScopes::get) ?: WorkflowSignalScope.GLOBAL,
            compensationActivityRef = compensation?.attr("activityRef")?.ifBlank { null },
            compensationHandlerNodeId = associationTarget?.ifBlank { null },
            forCompensation = element.attr("isForCompensation").toBoolean(),
            parentSubprocessId = parentSubprocessId,
            laneId = laneId,
            executionListeners = element.directExtensionChildren("executionListener")
                .mapNotNull(::parseListener),
            taskListeners = element.directExtensionChildren("taskListener").mapNotNull(::parseListener),
            inputMappings = element.directExtensionChildren("in").mapNotNull(::parseMapping),
            outputMappings = element.directExtensionChildren("out").mapNotNull(::parseMapping),
            minimumApprovals = properties["jmixWorkbench.minimumApprovals"]?.toIntOrNull(),
            segregationOfDutyNodeIds = properties.csv("jmixWorkbench.segregationOfDutyNodes"),
            requiredDocuments = properties.csv("jmixWorkbench.requiredDocuments"),
            validationRules = properties.csv("jmixWorkbench.validations"),
            sideEffects = properties.csv("jmixWorkbench.sideEffects"),
            notifications = properties.csv("jmixWorkbench.notifications"),
            requiredPermissions = properties.csv("jmixWorkbench.requiredPermissions"),
            documentation = element.directChildren("documentation")
                .firstOrNull()?.textContent?.trim()?.ifBlank { null },
            x = x,
            y = y,
            width = position?.width ?: if (type in subprocessTypes) 360 else 168,
            height = position?.height ?: if (type in subprocessTypes) 220 else 66,
        )
    }

    private fun nodeType(element: Element): WorkflowNodeType? = when (element.localTag()) {
        "startEvent" -> when {
            element.directChildren("messageEventDefinition").isNotEmpty() -> WorkflowNodeType.MESSAGE_START
            element.directChildren("signalEventDefinition").isNotEmpty() -> WorkflowNodeType.SIGNAL_START
            element.directChildren("timerEventDefinition").isNotEmpty() -> WorkflowNodeType.TIMER_START
            element.directChildren("errorEventDefinition").isNotEmpty() -> WorkflowNodeType.ERROR_START
            element.directChildren().any {
                it.localTag() !in setOf("documentation", "extensionElements", "incoming", "outgoing")
            } -> null
            else -> WorkflowNodeType.START
        }
        "userTask" -> WorkflowNodeType.HUMAN_STATE
        "scriptTask" -> WorkflowNodeType.SCRIPT_STATE
        "serviceTask" -> when (element.attr("flowable:type")) {
            "dmn" -> WorkflowNodeType.BUSINESS_RULE_STATE
            "jmix-send-email" -> WorkflowNodeType.EMAIL_STATE
            "jmix-load-entities-jpql",
            "jmix-modify-entity",
            "jmix-create-entity" -> WorkflowNodeType.ENTITY_DATA_STATE
            else -> WorkflowNodeType.AUTOMATED_STATE
        }
        "exclusiveGateway" -> WorkflowNodeType.DECISION
        "parallelGateway" -> WorkflowNodeType.PARALLEL_GATEWAY
        "inclusiveGateway" -> WorkflowNodeType.INCLUSIVE_GATEWAY
        "callActivity" -> WorkflowNodeType.CALL_ACTIVITY
        "subProcess" -> if (element.attr("triggeredByEvent").toBoolean()) {
            WorkflowNodeType.EVENT_SUBPROCESS
        } else {
            WorkflowNodeType.EMBEDDED_SUBPROCESS
        }
        "transaction" -> WorkflowNodeType.TRANSACTION_SUBPROCESS
        "intermediateCatchEvent" -> when {
            element.directChildren("timerEventDefinition").isNotEmpty() -> WorkflowNodeType.TIMER_EVENT
            element.directChildren("messageEventDefinition").isNotEmpty() -> WorkflowNodeType.MESSAGE_CATCH
            element.directChildren("signalEventDefinition").isNotEmpty() -> WorkflowNodeType.SIGNAL_CATCH
            else -> null
        }
        "intermediateThrowEvent" -> when {
            element.directChildren("signalEventDefinition").isNotEmpty() -> WorkflowNodeType.SIGNAL_THROW
            element.directChildren("compensateEventDefinition").isNotEmpty() -> WorkflowNodeType.COMPENSATION_THROW
            else -> null
        }
        "boundaryEvent" -> when {
            element.directChildren("timerEventDefinition").isNotEmpty() -> WorkflowNodeType.BOUNDARY_TIMER
            element.directChildren("messageEventDefinition").isNotEmpty() -> WorkflowNodeType.BOUNDARY_MESSAGE
            element.directChildren("signalEventDefinition").isNotEmpty() -> WorkflowNodeType.BOUNDARY_SIGNAL
            element.directChildren("errorEventDefinition").isNotEmpty() -> WorkflowNodeType.BOUNDARY_ERROR
            element.directChildren("compensateEventDefinition").isNotEmpty() -> WorkflowNodeType.BOUNDARY_COMPENSATION
            element.directChildren("cancelEventDefinition").isNotEmpty() -> WorkflowNodeType.BOUNDARY_CANCEL
            else -> null
        }
        "endEvent" -> when {
            element.directChildren("errorEventDefinition").isNotEmpty() -> WorkflowNodeType.ERROR_END
            element.directChildren("cancelEventDefinition").isNotEmpty() -> WorkflowNodeType.CANCEL_END
            element.directChildren("terminateEventDefinition").isNotEmpty() -> WorkflowNodeType.TERMINATE_END
            else -> WorkflowNodeType.TERMINAL
        }
        else -> null
    }

    private fun parseLanes(process: Element): List<WorkflowLaneModel> =
        process.descendants("lane").map { lane ->
            WorkflowLaneModel(
                id = lane.attr("id"),
                name = lane.attr("name").ifBlank { lane.attr("id") },
                actorRoleCodes = lane.workbenchProperties().csv("jmixWorkbench.actorRoles"),
            )
        }

    private fun diagramPositions(root: Element): Map<String, DiagramPosition> =
        root.descendants("BPMNShape").mapNotNull { shape ->
            val target = shape.attr("bpmnElement").ifBlank { return@mapNotNull null }
            val bounds = shape.directChildren("Bounds").firstOrNull() ?: return@mapNotNull null
            val rawX = bounds.attr("x").toDoubleOrNull() ?: return@mapNotNull null
            val rawY = bounds.attr("y").toDoubleOrNull() ?: return@mapNotNull null
            val width = bounds.attr("width").toDoubleOrNull() ?: 168.0
            val height = bounds.attr("height").toDoubleOrNull() ?: 66.0
            val x = if (width <= 52) rawX - if (width <= 40) 66 else 59 else rawX
            val y = if (height <= 52) rawY - if (height <= 40) 15 else 8 else rawY
            target to DiagramPosition(
                x.toInt(),
                y.toInt(),
                if (width <= 52) 168 else width.toInt(),
                if (height <= 52) 66 else height.toInt(),
            )
        }.toMap()

    private val subprocessTypes = setOf(
        WorkflowNodeType.EMBEDDED_SUBPROCESS,
        WorkflowNodeType.EVENT_SUBPROCESS,
        WorkflowNodeType.TRANSACTION_SUBPROCESS,
    )

    private data class IndexCounter(var value: Int = 0)
    private data class ParsedScope(
        val nodes: List<WorkflowNodeModel>,
        val transitions: List<WorkflowTransitionModel>,
    )
    private data class DiagramPosition(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private fun parseServiceExpression(expression: String): Pair<String, String?>? {
        val match = Regex("""^[#$]\{([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\(execution\)}$""")
            .matchEntire(expression.trim()) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun parseListener(element: Element): WorkflowListenerModel? {
        val event = element.attr("event").ifBlank { return null }
        val candidates = listOf(
            WorkflowListenerImplementationType.EXPRESSION to element.attr("expression"),
            WorkflowListenerImplementationType.DELEGATE_EXPRESSION to element.attr("delegateExpression"),
            WorkflowListenerImplementationType.CLASS to element.attr("class"),
        )
        val implementation = candidates.firstOrNull { it.second.isNotBlank() } ?: return null
        return WorkflowListenerModel(
            event = event,
            implementationType = implementation.first,
            implementation = implementation.second,
        )
    }

    private fun parseMapping(element: Element): WorkflowVariableMapping? {
        val target = element.attr("target").ifBlank { return null }
        val source = element.attr("source").ifBlank { null }
        val sourceExpression = element.attr("sourceExpression").ifBlank { null }
        if (source == null && sourceExpression == null) return null
        return WorkflowVariableMapping(
            source = source,
            sourceExpression = sourceExpression,
            target = target,
        )
    }

    private fun parseFormData(element: Element): WorkflowFormData {
        val type = when (element.attr("type")) {
            "input-dialog" -> WorkflowFormType.INPUT_DIALOG
            "jmix-screen", "jmix-view" -> WorkflowFormType.JMIX_VIEW
            "custom" -> WorkflowFormType.CUSTOM
            else -> WorkflowFormType.NO_FORM
        }
        val fields = element.directChildren("formFields")
            .flatMap { it.directChildren("formField") }
            .mapNotNull { field ->
                val id = field.attr("id").ifBlank { return@mapNotNull null }
                WorkflowFormField(
                    id = id,
                    caption = field.attr("caption").ifBlank { id },
                    type = field.attr("type").ifBlank { "string" },
                    editable = field.attr("editable").ifBlank { "true" }.toBoolean(),
                    required = field.attr("required").toBoolean(),
                    properties = field.directChildren("formFieldProperty").mapNotNull { property ->
                        val name = property.attr("name").ifBlank { return@mapNotNull null }
                        name to property.attr("value")
                    }.toMap(),
                )
            }
        val outcomes = element.directChildren("formOutcomes")
            .flatMap { it.directChildren("formOutcome") }
            .mapNotNull { outcome ->
                val id = outcome.attr("id").ifBlank { return@mapNotNull null }
                WorkflowFormOutcome(
                    id = id,
                    caption = outcome.attr("caption").ifBlank { id },
                    icon = outcome.attr("icon").ifBlank { null },
                )
            }
        return WorkflowFormData(
            type = type,
            openMode = runCatching {
                WorkflowFormOpenMode.valueOf(element.attr("openMode").ifBlank { "DIALOG" })
            }.getOrDefault(WorkflowFormOpenMode.DIALOG),
            screenId = element.attr("screenId").ifBlank { null },
            businessKey = element.attr("businessKey").ifBlank { null },
            businessKeySource = element.attr("businessKeySource").ifBlank { null },
            fields = fields,
            outcomes = outcomes,
        )
    }

    private fun Element.workbenchProperties(): Map<String, String> =
        descendants("property").mapNotNull { property ->
            val name = property.attr("name")
            if (!name.startsWith("jmixWorkbench.")) null else name to property.attr("value")
        }.toMap()

    private fun Map<String, String>.csv(key: String): List<String> =
        get(key)?.split('|')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()

    private fun String.commaList(): List<String> =
        split(',').map(String::trim).filter(String::isNotBlank)

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    private fun Element.localTag(): String = localName ?: nodeName.substringAfter(':')

    private fun Element.directChildren(tag: String? = null): List<Element> =
        (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }
            .filter { tag == null || it.localTag() == tag }

    private fun Element.directExtensionChildren(tag: String): List<Element> =
        directChildren("extensionElements").flatMap { it.directChildren(tag) }

    private fun Element.descendants(tag: String): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(element: Element) {
            element.directChildren().forEach { child ->
                if (child.localTag() == tag) result += child
                visit(child)
            }
        }
        visit(this)
        return result
    }

    private fun Element.containsComments(): Boolean {
        fun visit(node: Node): Boolean {
            if (node.nodeType == Node.COMMENT_NODE) return true
            return (0 until node.childNodes.length).any { visit(node.childNodes.item(it)) }
        }
        return visit(this)
    }

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setXIncludeAware(false)
            isExpandEntityReferences = false
        }
}

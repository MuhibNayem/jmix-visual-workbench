package org.jmixworkbench.generator

import com.google.gson.Gson
import org.jmixworkbench.model.WorkflowModel
import org.jmixworkbench.model.WorkflowListenerImplementationType
import org.jmixworkbench.model.WorkflowListenerModel
import org.jmixworkbench.model.WorkflowNodeModel
import org.jmixworkbench.model.WorkflowNodeType

/**
 * Generates BPMN 2.0 process definition XML for Jmix BPM module.
 * Handles: start events, user tasks, service tasks, gateways,
 * end events, sequence flows, process forms, listeners.
 */
object BpmGenerator {

    private const val NS_BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
    private const val NS_BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI"
    private const val NS_DC = "http://www.omg.org/spec/DD/20100524/DC"
    private const val NS_DI = "http://www.omg.org/spec/DD/20100524/DI"
    private const val NS_FLOWABLE = "http://flowable.org/bpmn"
    private const val NS_JMIX = "http://jmix.io/schema/bpm/bpmn"
    private val gson = Gson()

    fun generate(workflow: WorkflowModel): String {
        val xml = XmlBuilder("definitions")
        xml.noDeclaration()
        xml.root {
            attr("xmlns", NS_BPMN)
            attr("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attr("xmlns:flowable", NS_FLOWABLE)
            attr("xmlns:jmix", NS_JMIX)
            attr("xmlns:bpmndi", NS_BPMNDI)
            attr("xmlns:dc", NS_DC)
            attr("xmlns:di", NS_DI)
            attr("targetNamespace", "http://jmix.io/bpm")

            workflow.nodes.filter { node ->
                node.type == WorkflowNodeType.BOUNDARY_ERROR ||
                    node.type == WorkflowNodeType.ERROR_START ||
                    node.type == WorkflowNodeType.ERROR_END
            }.mapNotNull { node ->
                node.eventReference?.takeIf(String::isNotBlank)
            }.distinct().forEach { errorReference ->
                child("error") {
                    attr("id", errorReference)
                    attr("name", errorReference)
                    attr("errorCode", errorReference)
                }
            }
            workflow.nodes.filter { node ->
                node.type == WorkflowNodeType.MESSAGE_START ||
                    node.type == WorkflowNodeType.MESSAGE_CATCH ||
                    node.type == WorkflowNodeType.BOUNDARY_MESSAGE
            }.mapNotNull { it.eventReference?.takeIf(String::isNotBlank) }
                .distinct()
                .forEach { messageReference ->
                    child("message") {
                        attr("id", messageReference)
                        attr("name", messageReference)
                    }
                }
            workflow.nodes.filter { node ->
                node.type == WorkflowNodeType.SIGNAL_START ||
                    node.type == WorkflowNodeType.SIGNAL_CATCH ||
                    node.type == WorkflowNodeType.SIGNAL_THROW ||
                    node.type == WorkflowNodeType.BOUNDARY_SIGNAL
            }.groupBy { it.eventReference }
                .filterKeys { !it.isNullOrBlank() }
                .forEach { (signalReference, nodes) ->
                    child("signal") {
                        attr("id", requireNotNull(signalReference))
                        attr("name", signalReference)
                        attr(
                            "flowable:scope",
                            if (nodes.first().signalScope == org.jmixworkbench.model.WorkflowSignalScope.GLOBAL) {
                                "global"
                            } else {
                                "processInstance"
                            },
                        )
                    }
                }

            child("process") {
                attr("id", workflow.id)
                attr("name", workflow.name)
                attr("isExecutable", "true")
                workflow.candidateStarterGroups.takeIf(List<String>::isNotEmpty)
                    ?.let { attr("flowable:candidateStarterGroups", it.joinToString(",")) }
                workflow.candidateStarterUsers.takeIf(List<String>::isNotEmpty)
                    ?.let { attr("flowable:candidateStarterUsers", it.joinToString(",")) }
                workflow.documentation?.takeIf(String::isNotBlank)?.let { value ->
                    child("documentation") { text(value) }
                }
                workflowProcessExtensions(
                    workflow,
                    mapOf(
                        "jmixWorkbench.entityClass" to workflow.entityQualifiedName,
                        "jmixWorkbench.stateAttribute" to workflow.stateAttribute,
                        "jmixWorkbench.businessKeyExpression" to workflow.businessKeyExpression,
                        "jmixWorkbench.versionTag" to workflow.versionTag,
                        "jmixWorkbench.tenantExpression" to workflow.tenantExpression,
                        "jmixWorkbench.auditLevel" to workflow.auditLevel.name,
                    ),
                )
                if (workflow.lanes.isNotEmpty()) {
                    child("laneSet") {
                        attr("id", "${workflow.id}-lanes")
                        workflow.lanes.forEach { lane ->
                            child("lane") {
                                attr("id", lane.id)
                                attr("name", lane.name)
                                lane.actorRoleCodes.takeIf(List<String>::isNotEmpty)?.let { roles ->
                                    extensionProperties(
                                        mapOf("jmixWorkbench.actorRoles" to roles.joinToString("|")),
                                    )
                                }
                                workflow.nodes.filter { it.laneId == lane.id }.forEach { node ->
                                    child("flowNodeRef") { text(node.id) }
                                }
                            }
                        }
                    }
                }
                val compensationHandlers = workflow.nodes.mapNotNull { it.compensationHandlerNodeId }.toSet()
                generateScope(this, workflow, null, compensationHandlers)
            }
            generateDiagram(this, workflow)
        }
        return xml.build()
    }

    private fun generateScope(
        parent: XmlBuilder.Element,
        workflow: WorkflowModel,
        parentSubprocessId: String?,
        compensationHandlers: Set<String>,
    ) {
        val nodesById = workflow.nodes.associateBy { it.id }
        workflow.nodes.filter { it.parentSubprocessId == parentSubprocessId }.forEach { node ->
            generateWorkflowNode(parent, node, node.id in compensationHandlers, workflow, compensationHandlers)
        }
        workflow.transitions.filter { transition ->
            nodesById[transition.sourceId]?.parentSubprocessId == parentSubprocessId &&
                nodesById[transition.targetId]?.parentSubprocessId == parentSubprocessId
        }.forEach { transition ->
            parent.child("sequenceFlow") {
                attr("id", transition.id)
                attr("sourceRef", transition.sourceId)
                attr("targetRef", transition.targetId)
                transition.name?.takeIf(String::isNotBlank)?.let { attr("name", it) }
                extensionProperties(
                    buildMap {
                        transition.outcomeId?.let { put("jmixWorkbench.outcomeId", it) }
                        putCsv("jmixWorkbench.requiredRoles", transition.requiredRoleCodes)
                        putCsv("jmixWorkbench.requiredDocuments", transition.requiredDocuments)
                        putCsv("jmixWorkbench.validations", transition.validationRules)
                        putCsv("jmixWorkbench.sideEffects", transition.sideEffects)
                        putCsv("jmixWorkbench.notifications", transition.notifications)
                    },
                )
                transition.conditionExpression?.takeIf(String::isNotBlank)?.let { condition ->
                    child("conditionExpression") {
                        attr("xsi:type", "tFormalExpression")
                        text(condition)
                    }
                }
            }
        }
        workflow.nodes.filter {
            it.parentSubprocessId == parentSubprocessId &&
                it.type == WorkflowNodeType.BOUNDARY_COMPENSATION &&
                !it.compensationHandlerNodeId.isNullOrBlank()
        }.forEach { boundary ->
            parent.child("association") {
                attr("id", "${boundary.id}-handler")
                attr("associationDirection", "One")
                attr("sourceRef", boundary.id)
                attr("targetRef", requireNotNull(boundary.compensationHandlerNodeId))
            }
        }
    }

    private fun generateWorkflowNode(
        parent: XmlBuilder.Element,
        node: WorkflowNodeModel,
        referencedCompensationHandler: Boolean,
        workflow: WorkflowModel,
        compensationHandlers: Set<String>,
    ) {
        val tag = when (node.type) {
            WorkflowNodeType.START,
            WorkflowNodeType.MESSAGE_START,
            WorkflowNodeType.SIGNAL_START,
            WorkflowNodeType.TIMER_START,
            WorkflowNodeType.ERROR_START -> "startEvent"
            WorkflowNodeType.HUMAN_STATE -> "userTask"
            WorkflowNodeType.AUTOMATED_STATE,
            WorkflowNodeType.EMAIL_STATE -> "serviceTask"
            WorkflowNodeType.SCRIPT_STATE -> "scriptTask"
            WorkflowNodeType.ENTITY_DATA_STATE -> "serviceTask"
            WorkflowNodeType.DECISION -> "exclusiveGateway"
            WorkflowNodeType.PARALLEL_GATEWAY -> "parallelGateway"
            WorkflowNodeType.INCLUSIVE_GATEWAY -> "inclusiveGateway"
            WorkflowNodeType.BUSINESS_RULE_STATE -> "serviceTask"
            WorkflowNodeType.EMBEDDED_SUBPROCESS,
            WorkflowNodeType.EVENT_SUBPROCESS -> "subProcess"
            WorkflowNodeType.TRANSACTION_SUBPROCESS -> "transaction"
            WorkflowNodeType.CALL_ACTIVITY -> "callActivity"
            WorkflowNodeType.TIMER_EVENT,
            WorkflowNodeType.MESSAGE_CATCH,
            WorkflowNodeType.SIGNAL_CATCH -> "intermediateCatchEvent"
            WorkflowNodeType.SIGNAL_THROW,
            WorkflowNodeType.COMPENSATION_THROW -> "intermediateThrowEvent"
            WorkflowNodeType.BOUNDARY_TIMER,
            WorkflowNodeType.BOUNDARY_MESSAGE,
            WorkflowNodeType.BOUNDARY_SIGNAL,
            WorkflowNodeType.BOUNDARY_ERROR,
            WorkflowNodeType.BOUNDARY_COMPENSATION,
            WorkflowNodeType.BOUNDARY_CANCEL -> "boundaryEvent"
            WorkflowNodeType.ERROR_END,
            WorkflowNodeType.CANCEL_END,
            WorkflowNodeType.TERMINATE_END,
            WorkflowNodeType.TERMINAL -> "endEvent"
        }
        parent.child(tag) {
            attr("id", node.id)
            attr("name", node.name)
            if (node.type in setOf(
                    WorkflowNodeType.MESSAGE_START,
                    WorkflowNodeType.SIGNAL_START,
                    WorkflowNodeType.TIMER_START,
                    WorkflowNodeType.ERROR_START,
                ) &&
                workflow.nodes.find { it.id == node.parentSubprocessId }?.type ==
                WorkflowNodeType.EVENT_SUBPROCESS
            ) {
                attr("isInterrupting", node.eventStartInterrupting.toString())
            }
            if (node.forCompensation || referencedCompensationHandler) {
                attr("isForCompensation", "true")
            }
            if (node.type == WorkflowNodeType.DECISION || node.type == WorkflowNodeType.INCLUSIVE_GATEWAY) {
                node.defaultTransitionId?.takeIf(String::isNotBlank)?.let { attr("default", it) }
            }
            if (node.type == WorkflowNodeType.HUMAN_STATE) {
                node.assigneeExpression?.takeIf(String::isNotBlank)?.let {
                    attr("flowable:assignee", it)
                    attr("jmix:assigneeSource", "expression")
                    attr("jmix:assigneeValue", it)
                }
                node.actorRoleCodes.takeIf(List<String>::isNotEmpty)
                    ?.let { attr("flowable:candidateGroups", it.joinToString(",")) }
                node.formKey?.takeIf(String::isNotBlank)?.let { attr("flowable:formKey", it) }
                node.dueDate?.takeIf(String::isNotBlank)?.let { attr("flowable:dueDate", it) }
                node.priority?.takeIf(String::isNotBlank)?.let { attr("flowable:priority", it) }
            }
            if (node.type == WorkflowNodeType.AUTOMATED_STATE) {
                val bean = node.serviceBean?.trim().orEmpty()
                val method = node.serviceMethod?.trim().orEmpty()
                if (bean.isNotEmpty()) {
                    attr(
                        "flowable:expression",
                        if (method.isEmpty()) "\${$bean.execute(execution)}" else "\${$bean.$method(execution)}",
                    )
                }
                if (node.triggerable) attr("flowable:triggerable", "true")
            }
            if (node.type == WorkflowNodeType.SCRIPT_STATE) {
                attr("scriptFormat", "groovy")
                node.resultVariable?.takeIf(String::isNotBlank)?.let {
                    attr("flowable:resultVariable", it)
                }
            }
            if (node.type == WorkflowNodeType.ENTITY_DATA_STATE) {
                attr(
                    "flowable:type",
                    when (node.entityDataOperation) {
                        org.jmixworkbench.model.WorkflowEntityDataOperation.LOAD ->
                            "jmix-load-entities-jpql"
                        org.jmixworkbench.model.WorkflowEntityDataOperation.MODIFY ->
                            "jmix-modify-entity"
                        org.jmixworkbench.model.WorkflowEntityDataOperation.CREATE ->
                            "jmix-create-entity"
                    },
                )
            }
            if (node.type == WorkflowNodeType.BUSINESS_RULE_STATE) attr("flowable:type", "dmn")
            if (node.type == WorkflowNodeType.EMAIL_STATE) attr("flowable:type", "jmix-send-email")
            if (node.type == WorkflowNodeType.EVENT_SUBPROCESS) attr("triggeredByEvent", "true")
            if (node.type == WorkflowNodeType.CALL_ACTIVITY) {
                node.calledElement?.takeIf(String::isNotBlank)?.let { attr("calledElement", it) }
                attr("flowable:inheritBusinessKey", node.inheritBusinessKey.toString())
                attr("flowable:inheritVariables", node.inheritVariables.toString())
            }
            if (node.async && node.type != WorkflowNodeType.SIGNAL_THROW) {
                attr("flowable:async", "true")
                attr("flowable:exclusive", node.exclusive.toString())
            }
            if (node.type == WorkflowNodeType.BOUNDARY_TIMER ||
                node.type == WorkflowNodeType.BOUNDARY_MESSAGE ||
                node.type == WorkflowNodeType.BOUNDARY_SIGNAL ||
                node.type == WorkflowNodeType.BOUNDARY_ERROR ||
                node.type == WorkflowNodeType.BOUNDARY_COMPENSATION ||
                node.type == WorkflowNodeType.BOUNDARY_CANCEL
            ) {
                node.attachedToNodeId?.takeIf(String::isNotBlank)?.let { attr("attachedToRef", it) }
                if (node.type != WorkflowNodeType.BOUNDARY_COMPENSATION) {
                    attr("cancelActivity", node.cancelActivity.toString())
                }
            }
            node.documentation?.takeIf(String::isNotBlank)?.let { value ->
                child("documentation") { text(value) }
            }
            workflowNodeExtensions(node)
            if (node.type == WorkflowNodeType.SCRIPT_STATE) {
                child("script") { text(node.script.orEmpty()) }
            }
            if (node.multiInstanceMode != org.jmixworkbench.model.WorkflowMultiInstanceMode.NONE) {
                child("multiInstanceLoopCharacteristics") {
                    attr(
                        "isSequential",
                        (node.multiInstanceMode == org.jmixworkbench.model.WorkflowMultiInstanceMode.SEQUENTIAL).toString(),
                    )
                    node.collectionExpression?.takeIf(String::isNotBlank)?.let {
                        attr("flowable:collection", it)
                    }
                    node.elementVariable?.takeIf(String::isNotBlank)?.let {
                        attr("flowable:elementVariable", it)
                    }
                    node.loopCardinality?.takeIf(String::isNotBlank)?.let { value ->
                        child("loopCardinality") { text(value) }
                    }
                    node.completionCondition?.takeIf(String::isNotBlank)?.let { value ->
                        child("completionCondition") { text(value) }
                    }
                }
            }
            if (node.type == WorkflowNodeType.TIMER_START ||
                node.type == WorkflowNodeType.TIMER_EVENT ||
                node.type == WorkflowNodeType.BOUNDARY_TIMER
            ) {
                child("timerEventDefinition") {
                    val timerTag = when (node.timerType) {
                        org.jmixworkbench.model.WorkflowTimerType.DURATION -> "timeDuration"
                        org.jmixworkbench.model.WorkflowTimerType.DATE -> "timeDate"
                        org.jmixworkbench.model.WorkflowTimerType.CYCLE -> "timeCycle"
                    }
                    child(timerTag) { text(node.timerExpression.orEmpty()) }
                }
            }
            if (node.type == WorkflowNodeType.ERROR_START ||
                node.type == WorkflowNodeType.BOUNDARY_ERROR ||
                node.type == WorkflowNodeType.ERROR_END
            ) {
                child("errorEventDefinition") {
                    node.eventReference?.takeIf(String::isNotBlank)?.let { attr("errorRef", it) }
                }
            }
            if (node.type == WorkflowNodeType.MESSAGE_START ||
                node.type == WorkflowNodeType.MESSAGE_CATCH ||
                node.type == WorkflowNodeType.BOUNDARY_MESSAGE
            ) {
                child("messageEventDefinition") {
                    attr("id", "${node.id}-message-definition")
                    node.eventReference?.takeIf(String::isNotBlank)?.let { attr("messageRef", it) }
                }
            }
            if (node.type == WorkflowNodeType.SIGNAL_START ||
                node.type == WorkflowNodeType.SIGNAL_CATCH ||
                node.type == WorkflowNodeType.SIGNAL_THROW ||
                node.type == WorkflowNodeType.BOUNDARY_SIGNAL
            ) {
                child("signalEventDefinition") {
                    attr("id", "${node.id}-signal-definition")
                    node.eventReference?.takeIf(String::isNotBlank)?.let { attr("signalRef", it) }
                    if (node.type == WorkflowNodeType.SIGNAL_THROW && node.async) {
                        attr("flowable:async", "true")
                    }
                }
            }
            if (node.type == WorkflowNodeType.COMPENSATION_THROW ||
                node.type == WorkflowNodeType.BOUNDARY_COMPENSATION
            ) {
                child("compensateEventDefinition") {
                    attr("id", "${node.id}-compensation-definition")
                    if (node.type == WorkflowNodeType.COMPENSATION_THROW) {
                        node.compensationActivityRef?.takeIf(String::isNotBlank)?.let {
                            attr("activityRef", it)
                        }
                    }
                }
            }
            if (node.type == WorkflowNodeType.BOUNDARY_CANCEL || node.type == WorkflowNodeType.CANCEL_END) {
                child("cancelEventDefinition") { attr("id", "${node.id}-cancel-definition") }
            }
            if (node.type == WorkflowNodeType.TERMINATE_END) {
                child("terminateEventDefinition") {
                    attr("id", "${node.id}-terminate-definition")
                    attr("flowable:terminateAll", "true")
                }
            }
            if (node.type in setOf(
                    WorkflowNodeType.EMBEDDED_SUBPROCESS,
                    WorkflowNodeType.EVENT_SUBPROCESS,
                    WorkflowNodeType.TRANSACTION_SUBPROCESS,
                )
            ) {
                generateScope(this, workflow, node.id, compensationHandlers)
            }
        }
    }

    private fun XmlBuilder.Element.workflowNodeExtensions(node: WorkflowNodeModel) {
        val properties = buildMap<String, String?> {
            node.stateValue?.let { put("jmixWorkbench.stateValue", it) }
            node.idempotencyKeyExpression?.let { put("jmixWorkbench.idempotencyKey", it) }
            node.minimumApprovals?.let { put("jmixWorkbench.minimumApprovals", it.toString()) }
            putCsv("jmixWorkbench.segregationOfDutyNodes", node.segregationOfDutyNodeIds)
            putCsv("jmixWorkbench.requiredDocuments", node.requiredDocuments)
            putCsv("jmixWorkbench.validations", node.validationRules)
            putCsv("jmixWorkbench.sideEffects", node.sideEffects)
            putCsv("jmixWorkbench.notifications", node.notifications)
            putCsv("jmixWorkbench.requiredPermissions", node.requiredPermissions)
            if (node.x != 0 || node.y != 0) {
                put("jmixWorkbench.canvasX", node.x.toString())
                put("jmixWorkbench.canvasY", node.y.toString())
            }
        }.filterValues { !it.isNullOrBlank() }
        val hasRetry = !node.retryCycle.isNullOrBlank()
        val hasDmn = node.type == WorkflowNodeType.BUSINESS_RULE_STATE && !node.decisionTableKey.isNullOrBlank()
        val entityDataFields = if (node.type == WorkflowNodeType.ENTITY_DATA_STATE) {
            when (node.entityDataOperation) {
                org.jmixworkbench.model.WorkflowEntityDataOperation.LOAD -> linkedMapOf(
                    "jpql" to node.jpql,
                    "resultVariable" to node.resultVariable,
                    "saveLoadResultAs" to node.saveLoadResultAs.name.lowercase(),
                    "jpqlParameters" to node.jpqlParametersJson,
                )
                org.jmixworkbench.model.WorkflowEntityDataOperation.MODIFY -> linkedMapOf(
                    "entityName" to node.entityName,
                    "processVariable" to node.entityVariable,
                    "entityAttributes" to node.entityAttributesJson,
                )
                org.jmixworkbench.model.WorkflowEntityDataOperation.CREATE -> linkedMapOf(
                    "entityName" to node.entityName,
                    "resultVariable" to node.resultVariable,
                    "entityAttributes" to node.entityAttributesJson,
                )
            }.filterValues { !it.isNullOrBlank() }
        } else {
            emptyMap()
        }
        val emailFields = if (node.type == WorkflowNodeType.EMAIL_STATE) {
            linkedMapOf(
                "to" to node.emailTo,
                "cc" to node.emailCc,
                "bcc" to node.emailBcc,
                "from" to node.emailFrom,
                "subject" to node.emailSubject,
                "content" to node.emailContent,
                "contentType" to node.emailContentType.xmlValue,
                "sendAsync" to node.emailSendAsync.toString(),
                "attachments" to node.emailAttachments.takeIf { it.isNotEmpty() }?.let(gson::toJson),
            ).filterValues { !it.isNullOrBlank() }
        } else {
            emptyMap()
        }
        val hasListeners = node.executionListeners.isNotEmpty() || node.taskListeners.isNotEmpty()
        val hasMappings = node.inputMappings.isNotEmpty() || node.outputMappings.isNotEmpty()
        val hasForm = node.formData != null || node.processVariables.isNotEmpty()
        if (!hasRetry && !hasDmn && entityDataFields.isEmpty() && emailFields.isEmpty() &&
            !hasListeners && !hasMappings && !hasForm && properties.isEmpty()
        ) return
        child("extensionElements") {
            if (hasRetry) {
                child("flowable:failedJobRetryTimeCycle") { text(requireNotNull(node.retryCycle)) }
            }
            if (hasDmn) {
                child("flowable:field") {
                    attr("name", "decisionTableReferenceKey")
                    child("flowable:string") { text(requireNotNull(node.decisionTableKey)) }
                }
            }
            entityDataFields.forEach { (name, value) ->
                child("flowable:field") {
                    attr("name", name)
                    child("flowable:string") { text(requireNotNull(value)) }
                }
            }
            emailFields.forEach { (name, value) ->
                child("flowable:field") {
                    attr("name", name)
                    child("flowable:string") { text(requireNotNull(value)) }
                }
            }
            node.executionListeners.forEach { listener ->
                listenerElement("flowable:executionListener", listener)
            }
            node.taskListeners.forEach { listener ->
                listenerElement("flowable:taskListener", listener)
            }
            node.inputMappings.forEach { mapping ->
                child("flowable:in") {
                    mapping.source?.takeIf(String::isNotBlank)?.let { attr("source", it) }
                    mapping.sourceExpression?.takeIf(String::isNotBlank)?.let {
                        attr("sourceExpression", it)
                    }
                    attr("target", mapping.target)
                }
            }
            node.outputMappings.forEach { mapping ->
                child("flowable:out") {
                    mapping.source?.takeIf(String::isNotBlank)?.let { attr("source", it) }
                    mapping.sourceExpression?.takeIf(String::isNotBlank)?.let {
                        attr("sourceExpression", it)
                    }
                    attr("target", mapping.target)
                }
            }
            if (node.processVariables.isNotEmpty()) {
                child("jmix:processVariables") {
                    node.processVariables.forEach { variable ->
                        child("jmix:processVariable") {
                            attr("name", variable.name)
                            attr("type", variable.type)
                        }
                    }
                }
            }
            node.formData?.let { form ->
                child("jmix:formData") {
                    attr("type", form.type.xmlValue)
                    attr("openMode", form.openMode.name)
                    form.screenId?.takeIf(String::isNotBlank)?.let { attr("screenId", it) }
                    form.businessKey?.takeIf(String::isNotBlank)?.let { attr("businessKey", it) }
                    form.businessKeySource?.takeIf(String::isNotBlank)?.let {
                        attr("businessKeySource", it)
                    }
                    if (form.fields.isNotEmpty()) {
                        child("jmix:formFields") {
                            form.fields.forEach { field ->
                                child("jmix:formField") {
                                    attr("id", field.id)
                                    attr("caption", field.caption)
                                    attr("type", field.type)
                                    attr("editable", field.editable.toString())
                                    attr("required", field.required.toString())
                                    field.properties.forEach { (name, value) ->
                                        child("jmix:formFieldProperty") {
                                            attr("name", name)
                                            attr("value", value)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (form.outcomes.isNotEmpty()) {
                        child("jmix:formOutcomes") {
                            form.outcomes.forEach { outcome ->
                                child("jmix:formOutcome") {
                                    attr("id", outcome.id)
                                    attr("caption", outcome.caption)
                                    outcome.icon?.takeIf(String::isNotBlank)?.let { attr("icon", it) }
                                }
                            }
                        }
                    }
                }
            }
            if (properties.isNotEmpty()) {
                child("jmix:properties") {
                    properties.forEach { (name, value) ->
                        child("jmix:property") {
                            attr("name", name)
                            attr("value", requireNotNull(value))
                        }
                    }
                }
            }
        }
    }

    private fun XmlBuilder.Element.workflowProcessExtensions(
        workflow: WorkflowModel,
        values: Map<String, String?>,
    ) {
        val populated = values.filterValues { !it.isNullOrBlank() }
        if (populated.isEmpty() && workflow.executionListeners.isEmpty()) return
        child("extensionElements") {
            workflow.executionListeners.forEach { listener ->
                listenerElement("flowable:executionListener", listener)
            }
            if (populated.isNotEmpty()) {
                child("jmix:properties") {
                    populated.forEach { (name, value) ->
                        child("jmix:property") {
                            attr("name", name)
                            attr("value", requireNotNull(value))
                        }
                    }
                }
            }
        }
    }

    private fun XmlBuilder.Element.listenerElement(
        tag: String,
        listener: WorkflowListenerModel,
    ) {
        child(tag) {
            attr("event", listener.event)
            attr(
                when (listener.implementationType) {
                    WorkflowListenerImplementationType.EXPRESSION -> "expression"
                    WorkflowListenerImplementationType.DELEGATE_EXPRESSION -> "delegateExpression"
                    WorkflowListenerImplementationType.CLASS -> "class"
                },
                listener.implementation,
            )
        }
    }

    private fun generateDiagram(parent: XmlBuilder.Element, workflow: WorkflowModel) {
        val boundsByNode = workflow.nodes.associate { it.id to diagramBounds(it) }
        parent.child("bpmndi:BPMNDiagram") {
            attr("id", "${workflow.id}-diagram")
            child("bpmndi:BPMNPlane") {
                attr("id", "${workflow.id}-plane")
                attr("bpmnElement", workflow.id)
                if (workflow.lanes.isNotEmpty()) {
                    val laneHeight = 720.0 / workflow.lanes.size
                    workflow.lanes.forEachIndexed { index, lane ->
                        child("bpmndi:BPMNShape") {
                            attr("id", "${lane.id}-di")
                            attr("bpmnElement", lane.id)
                            attr("isHorizontal", "true")
                            child("dc:Bounds") {
                                attr("x", "0")
                                attr("y", formatCoordinate(index * laneHeight))
                                attr("width", "1100")
                                attr("height", formatCoordinate(laneHeight))
                            }
                        }
                    }
                }
                workflow.nodes.forEach { node ->
                    val bounds = boundsByNode.getValue(node.id)
                    child("bpmndi:BPMNShape") {
                        attr("id", "${node.id}-di")
                        attr("bpmnElement", node.id)
                        child("dc:Bounds") {
                            attr("x", formatCoordinate(bounds.x))
                            attr("y", formatCoordinate(bounds.y))
                            attr("width", formatCoordinate(bounds.width))
                            attr("height", formatCoordinate(bounds.height))
                        }
                    }
                }
                workflow.transitions.forEach { transition ->
                    val source = boundsByNode[transition.sourceId] ?: return@forEach
                    val target = boundsByNode[transition.targetId] ?: return@forEach
                    val waypoints = diagramWaypoints(source, target)
                    child("bpmndi:BPMNEdge") {
                        attr("id", "${transition.id}-di")
                        attr("bpmnElement", transition.id)
                        waypoints.forEach { point ->
                            child("di:waypoint") {
                                attr("x", formatCoordinate(point.first))
                                attr("y", formatCoordinate(point.second))
                            }
                        }
                    }
                }
                workflow.nodes.filter {
                    it.type == WorkflowNodeType.BOUNDARY_COMPENSATION &&
                        !it.compensationHandlerNodeId.isNullOrBlank()
                }.forEach { boundary ->
                    val source = boundsByNode[boundary.id] ?: return@forEach
                    val target = boundsByNode[boundary.compensationHandlerNodeId] ?: return@forEach
                    child("bpmndi:BPMNEdge") {
                        attr("id", "${boundary.id}-handler-di")
                        attr("bpmnElement", "${boundary.id}-handler")
                        diagramWaypoints(source, target).forEach { point ->
                            child("di:waypoint") {
                                attr("x", formatCoordinate(point.first))
                                attr("y", formatCoordinate(point.second))
                            }
                        }
                    }
                }
            }
        }
    }

    private data class DiagramBounds(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )

    private fun diagramBounds(node: WorkflowNodeModel): DiagramBounds = when (node.type) {
        WorkflowNodeType.START,
        WorkflowNodeType.MESSAGE_START,
        WorkflowNodeType.SIGNAL_START,
        WorkflowNodeType.TIMER_START,
        WorkflowNodeType.ERROR_START,
        WorkflowNodeType.TIMER_EVENT,
        WorkflowNodeType.MESSAGE_CATCH,
        WorkflowNodeType.SIGNAL_CATCH,
        WorkflowNodeType.SIGNAL_THROW,
        WorkflowNodeType.COMPENSATION_THROW,
        WorkflowNodeType.BOUNDARY_TIMER,
        WorkflowNodeType.BOUNDARY_MESSAGE,
        WorkflowNodeType.BOUNDARY_SIGNAL,
        WorkflowNodeType.BOUNDARY_ERROR,
        WorkflowNodeType.BOUNDARY_COMPENSATION,
        WorkflowNodeType.BOUNDARY_CANCEL,
        WorkflowNodeType.ERROR_END,
        WorkflowNodeType.CANCEL_END,
        WorkflowNodeType.TERMINATE_END,
        WorkflowNodeType.TERMINAL -> DiagramBounds(node.x + 66.0, node.y + 15.0, 36.0, 36.0)

        WorkflowNodeType.DECISION,
        WorkflowNodeType.PARALLEL_GATEWAY,
        WorkflowNodeType.INCLUSIVE_GATEWAY -> DiagramBounds(node.x + 59.0, node.y + 8.0, 50.0, 50.0)

        else -> DiagramBounds(
            node.x.toDouble(),
            node.y.toDouble(),
            node.width.coerceAtLeast(48).toDouble(),
            node.height.coerceAtLeast(48).toDouble(),
        )
    }

    private fun diagramWaypoints(
        source: DiagramBounds,
        target: DiagramBounds,
    ): List<Pair<Double, Double>> {
        val sourceCenterX = source.x + source.width / 2
        val sourceCenterY = source.y + source.height / 2
        val targetCenterX = target.x + target.width / 2
        val targetCenterY = target.y + target.height / 2
        val horizontalGap = kotlin.math.abs(targetCenterX - sourceCenterX) -
            (source.width + target.width) / 2
        val verticalGap = kotlin.math.abs(targetCenterY - sourceCenterY) -
            (source.height + target.height) / 2
        return if (horizontalGap >= verticalGap) {
            val sourceX = if (targetCenterX >= sourceCenterX) source.x + source.width else source.x
            val targetX = if (targetCenterX >= sourceCenterX) target.x else target.x + target.width
            val middleX = (sourceX + targetX) / 2
            listOf(
                sourceX to sourceCenterY,
                middleX to sourceCenterY,
                middleX to targetCenterY,
                targetX to targetCenterY,
            ).distinct()
        } else {
            val sourceY = if (targetCenterY >= sourceCenterY) source.y + source.height else source.y
            val targetY = if (targetCenterY >= sourceCenterY) target.y else target.y + target.height
            val middleY = (sourceY + targetY) / 2
            listOf(
                sourceCenterX to sourceY,
                sourceCenterX to middleY,
                targetCenterX to middleY,
                targetCenterX to targetY,
            ).distinct()
        }
    }

    private fun formatCoordinate(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)

    private fun XmlBuilder.Element.extensionProperties(values: Map<String, String?>) {
        val populated = values.filterValues { !it.isNullOrBlank() }
        if (populated.isEmpty()) return
        child("extensionElements") {
            child("jmix:properties") {
                populated.forEach { (name, value) ->
                    child("jmix:property") {
                        attr("name", name)
                        attr("value", requireNotNull(value))
                    }
                }
            }
        }
    }

    private fun MutableMap<String, String?>.putCsv(key: String, values: List<String>) {
        if (values.isNotEmpty()) put(key, values.joinToString("|"))
    }

    data class BpmProcess(
        val id: String,
        val name: String,
        val elements: MutableList<BpmElement> = mutableListOf(),
        val flows: MutableList<BpmFlow> = mutableListOf()
    )

    sealed class BpmElement {
        abstract val id: String
        abstract val name: String

        data class StartEvent(
            override val id: String = "startEvent",
            override val name: String = "Start",
            val formKey: String? = null
        ) : BpmElement()

        data class UserTask(
            override val id: String,
            override val name: String,
            val assignee: String? = null,
            val candidateGroups: String? = null,
            val candidateUsers: String? = null,
            val formKey: String? = null,
            val dueDate: String? = null,
            val priority: String? = null
        ) : BpmElement()

        data class ServiceTask(
            override val id: String,
            override val name: String,
            val delegateExpression: String? = null,
            val javaClass: String? = null,
            val expression: String? = null,
            val resultVariable: String? = null
        ) : BpmElement()

        data class ScriptTask(
            override val id: String,
            override val name: String,
            val scriptFormat: String = "groovy",
            val script: String = ""
        ) : BpmElement()

        data class ExclusiveGateway(
            override val id: String,
            override val name: String = "",
            val defaultFlow: String? = null
        ) : BpmElement()

        data class ParallelGateway(
            override val id: String,
            override val name: String = ""
        ) : BpmElement()

        data class InclusiveGateway(
            override val id: String,
            override val name: String = ""
        ) : BpmElement()

        data class EndEvent(
            override val id: String = "endEvent",
            override val name: String = "End"
        ) : BpmElement()

        data class SubProcess(
            override val id: String,
            override val name: String,
            val elements: MutableList<BpmElement> = mutableListOf(),
            val flows: MutableList<BpmFlow> = mutableListOf()
        ) : BpmElement()

        data class CallActivity(
            override val id: String,
            override val name: String,
            val calledElement: String
        ) : BpmElement()
    }

    data class BpmFlow(
        val id: String,
        val sourceRef: String,
        val targetRef: String,
        val conditionExpression: String? = null,
        val name: String? = null
    )

    fun generate(process: BpmProcess): String {
        val xml = XmlBuilder("definitions")
        xml.noDeclaration()

        xml.root {
            attr("xmlns", NS_BPMN)
            attr("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attr("xmlns:flowable", NS_FLOWABLE)
            attr("xmlns:jmix", NS_JMIX)
            attr("targetNamespace", "http://jmix.io/bpm")

            child("process") {
                attr("id", process.id)
                attr("name", process.name)
                attr("isExecutable", "true")

                process.elements.forEach { element ->
                    generateElement(this, element)
                }

                process.flows.forEach { flow ->
                    child("sequenceFlow") {
                        attr("id", flow.id)
                        attr("sourceRef", flow.sourceRef)
                        attr("targetRef", flow.targetRef)
                        flow.name?.let { attr("name", it) }
                        flow.conditionExpression?.let { cond ->
                            child("conditionExpression") {
                                attr("xsi:type", "tFormalExpression")
                                text(cond)
                            }
                        }
                    }
                }
            }
        }

        return xml.build()
    }

    private fun generateElement(parent: XmlBuilder.Element, element: BpmElement) {
        when (element) {
            is BpmElement.StartEvent -> parent.child("startEvent") {
                attr("id", element.id)
                attr("name", element.name)
                element.formKey?.let { attr("flowable:formKey", it) }
            }

            is BpmElement.UserTask -> parent.child("userTask") {
                attr("id", element.id)
                attr("name", element.name)
                element.assignee?.let { attr("flowable:assignee", it) }
                element.candidateGroups?.let { attr("flowable:candidateGroups", it) }
                element.candidateUsers?.let { attr("flowable:candidateUsers", it) }
                element.formKey?.let { attr("flowable:formKey", it) }
                element.dueDate?.let { attr("flowable:dueDate", it) }
                element.priority?.let { attr("flowable:priority", it) }
            }

            is BpmElement.ServiceTask -> parent.child("serviceTask") {
                attr("id", element.id)
                attr("name", element.name)
                element.delegateExpression?.let { attr("flowable:delegateExpression", it) }
                element.javaClass?.let { attr("flowable:class", it) }
                element.expression?.let { attr("flowable:expression", it) }
                element.resultVariable?.let { attr("flowable:resultVariable", it) }
            }

            is BpmElement.ScriptTask -> parent.child("scriptTask") {
                attr("id", element.id)
                attr("name", element.name)
                attr("scriptFormat", element.scriptFormat)
                child("script") { text(element.script) }
            }

            is BpmElement.ExclusiveGateway -> parent.child("exclusiveGateway") {
                attr("id", element.id)
                if (element.name.isNotEmpty()) attr("name", element.name)
                element.defaultFlow?.let { attr("default", it) }
            }

            is BpmElement.ParallelGateway -> parent.child("parallelGateway") {
                attr("id", element.id)
                if (element.name.isNotEmpty()) attr("name", element.name)
            }

            is BpmElement.InclusiveGateway -> parent.child("inclusiveGateway") {
                attr("id", element.id)
                if (element.name.isNotEmpty()) attr("name", element.name)
            }

            is BpmElement.EndEvent -> parent.child("endEvent") {
                attr("id", element.id)
                attr("name", element.name)
            }

            is BpmElement.SubProcess -> parent.child("subProcess") {
                attr("id", element.id)
                attr("name", element.name)
                element.elements.forEach { subEl -> generateElement(this, subEl) }
                element.flows.forEach { flow ->
                    child("sequenceFlow") {
                        attr("id", flow.id)
                        attr("sourceRef", flow.sourceRef)
                        attr("targetRef", flow.targetRef)
                    }
                }
            }

            is BpmElement.CallActivity -> parent.child("callActivity") {
                attr("id", element.id)
                attr("name", element.name)
                attr("calledElement", element.calledElement)
            }
        }
    }

    /**
     * Generates a simple approval process for an entity.
     */
    fun generateApprovalProcess(entityName: String): BpmProcess {
        val processId = "${entityName.lowercase()}-approval"
        return BpmProcess(
            id = processId,
            name = "$entityName Approval",
            elements = mutableListOf(
                BpmElement.StartEvent(id = "start", name = "Start"),
                BpmElement.UserTask(
                    id = "reviewTask",
                    name = "Review $entityName",
                    candidateGroups = "managers",
                    formKey = "${entityName.lowercase()}-review-form"
                ),
                BpmElement.ExclusiveGateway(id = "decision", name = "Approved?"),
                BpmElement.ServiceTask(
                    id = "approveTask",
                    name = "Approve",
                    delegateExpression = "\${${entityName.lowercase()}ApprovalDelegate}"
                ),
                BpmElement.ServiceTask(
                    id = "rejectTask",
                    name = "Reject",
                    delegateExpression = "\${${entityName.lowercase()}RejectionDelegate}"
                ),
                BpmElement.EndEvent(id = "endApproved", name = "Approved"),
                BpmElement.EndEvent(id = "endRejected", name = "Rejected")
            ),
            flows = mutableListOf(
                BpmFlow(id = "flow1", sourceRef = "start", targetRef = "reviewTask"),
                BpmFlow(id = "flow2", sourceRef = "reviewTask", targetRef = "decision"),
                BpmFlow(id = "flow3", sourceRef = "decision", targetRef = "approveTask",
                    conditionExpression = "\${approved == true}", name = "Yes"),
                BpmFlow(id = "flow4", sourceRef = "decision", targetRef = "rejectTask",
                    conditionExpression = "\${approved == false}", name = "No"),
                BpmFlow(id = "flow5", sourceRef = "approveTask", targetRef = "endApproved"),
                BpmFlow(id = "flow6", sourceRef = "rejectTask", targetRef = "endRejected")
            )
        )
    }
}

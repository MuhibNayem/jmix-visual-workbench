package com.jmixstudio.generator

import com.jmixstudio.generator.XmlBuilder

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

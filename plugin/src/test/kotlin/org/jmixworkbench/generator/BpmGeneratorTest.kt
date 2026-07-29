package org.jmixworkbench.generator

import org.jmixworkbench.model.WorkflowModel
import org.jmixworkbench.model.WorkflowEmailAttachmentModel
import org.jmixworkbench.model.WorkflowEmailContentType
import org.jmixworkbench.model.WorkflowLaneModel
import org.jmixworkbench.model.WorkflowMultiInstanceMode
import org.jmixworkbench.model.WorkflowNodeModel
import org.jmixworkbench.model.WorkflowNodeType
import org.jmixworkbench.model.WorkflowTimerType
import org.jmixworkbench.model.WorkflowTransitionModel
import org.jmixworkbench.model.WorkflowVariableMapping
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BpmGeneratorTest {
    @Test
    fun `enterprise subprocess email transaction and termination contracts are emitted without flattening`() {
        val workflow = WorkflowModel(
            id = "enterprise-payments",
            name = "Enterprise Payments",
            moduleId = "payments",
            nodes = listOf(
                WorkflowNodeModel("start", "Start", WorkflowNodeType.START),
                WorkflowNodeModel(
                    id = "transaction",
                    name = "Settlement transaction",
                    type = WorkflowNodeType.TRANSACTION_SUBPROCESS,
                    width = 420,
                    height = 260,
                ),
                WorkflowNodeModel(
                    id = "tx-start",
                    name = "Transaction start",
                    type = WorkflowNodeType.START,
                    parentSubprocessId = "transaction",
                ),
                WorkflowNodeModel(
                    id = "mail",
                    name = "Send settlement notice",
                    type = WorkflowNodeType.EMAIL_STATE,
                    parentSubprocessId = "transaction",
                    emailTo = "\${recipientEmail}",
                    emailFrom = "\${mailFrom}",
                    emailSubject = "Settlement complete",
                    emailContent = "<p>Completed</p>",
                    emailContentType = WorkflowEmailContentType.HTML,
                    emailSendAsync = true,
                    emailAttachments = listOf(
                        WorkflowEmailAttachmentModel("receipt", "receipt.pdf", "\${receiptFileRef}"),
                    ),
                ),
                WorkflowNodeModel(
                    id = "tx-done",
                    name = "Transaction done",
                    type = WorkflowNodeType.TERMINAL,
                    parentSubprocessId = "transaction",
                ),
                WorkflowNodeModel(
                    id = "cancel",
                    name = "Cancelled",
                    type = WorkflowNodeType.CANCEL_END,
                    parentSubprocessId = "transaction",
                ),
                WorkflowNodeModel("terminated", "Terminated", WorkflowNodeType.TERMINATE_END),
            ),
            transitions = listOf(
                WorkflowTransitionModel("outer-1", "start", "transaction"),
                WorkflowTransitionModel("outer-2", "transaction", "terminated"),
                WorkflowTransitionModel("inner-1", "tx-start", "mail"),
                WorkflowTransitionModel("inner-2", "mail", "tx-done"),
            ),
        )

        val xml = BpmGenerator.generate(workflow)
        val document = parse(xml)
        val transaction = document.getElementsByTagName("transaction").item(0) as org.w3c.dom.Element
        assertEquals(1, transaction.getElementsByTagName("startEvent").length)
        assertEquals(1, transaction.getElementsByTagName("serviceTask").length)
        assertEquals(2, transaction.getElementsByTagName("sequenceFlow").length)
        val email = transaction.getElementsByTagName("serviceTask").item(0) as org.w3c.dom.Element
        assertEquals("jmix-send-email", email.getAttribute("flowable:type"))
        assertTrue(xml.contains("name=\"sendAsync\""))
        assertTrue(xml.contains("receiptFileRef"))
        assertEquals(1, document.getElementsByTagName("cancelEventDefinition").length)
        assertEquals(1, document.getElementsByTagName("terminateEventDefinition").length)
        assertTrue(xml.contains("width=\"420\""))
        assertTrue(xml.contains("height=\"260\""))
    }

    @Test
    fun `connected workflow emits executable Jmix Flowable XML with audit metadata`() {
        val workflow = WorkflowModel(
            id = "loan-lifecycle",
            name = "Loan Lifecycle",
            moduleId = "loan",
            entityQualifiedName = "com.company.loan.entity.LoanApp",
            stateAttribute = "processState",
            candidateStarterGroups = listOf("hr-operator"),
            nodes = listOf(
                node("start", "Application received", WorkflowNodeType.START, "APPLICATION"),
                node(
                    id = "review",
                    name = "Review application",
                    type = WorkflowNodeType.HUMAN_STATE,
                    state = "UNDER_REVIEW",
                    actorRoles = listOf("hr-manager"),
                    requiredDocuments = listOf("identity-document"),
                    validationRules = listOf("eligibilityService.validate(applicationId)"),
                ).copy(formKey = "loan-review", dueDate = "PT3D"),
                node(
                    id = "approve",
                    name = "Approve and disburse",
                    type = WorkflowNodeType.AUTOMATED_STATE,
                    state = "APPROVED",
                    sideEffects = listOf("post ledger entry"),
                ).copy(serviceBean = "loanWorkflowService", serviceMethod = "approveAndDisburse"),
                node("closed", "Closed", WorkflowNodeType.TERMINAL, "CLOSED"),
            ),
            transitions = listOf(
                edge("flow-1", "start", "review"),
                edge("flow-2", "review", "approve", "\${approved == true}").copy(outcomeId = "approve"),
                edge("flow-3", "approve", "closed"),
            ),
        )

        val xml = BpmGenerator.generate(workflow)
        val document = parse(xml)
        val process = document.getElementsByTagName("process").item(0) as? org.w3c.dom.Element
        assertNotNull(process)
        assertEquals("true", process.getAttribute("isExecutable"))
        assertEquals("hr-operator", process.getAttribute("flowable:candidateStarterGroups"))

        val userTask = document.getElementsByTagName("userTask").item(0) as? org.w3c.dom.Element
        assertNotNull(userTask)
        assertEquals("hr-manager", userTask.getAttribute("flowable:candidateGroups"))
        assertEquals("loan-review", userTask.getAttribute("flowable:formKey"))
        assertEquals("PT3D", userTask.getAttribute("flowable:dueDate"))

        val serviceTask = document.getElementsByTagName("serviceTask").item(0) as? org.w3c.dom.Element
        assertNotNull(serviceTask)
        assertEquals(
            "\${loanWorkflowService.approveAndDisburse(execution)}",
            serviceTask.getAttribute("flowable:expression"),
        )
        assertTrue(xml.contains("jmixWorkbench.entityClass"))
        assertTrue(xml.contains("jmixWorkbench.stateAttribute"))
        assertTrue(xml.contains("jmixWorkbench.requiredDocuments"))
        assertTrue(xml.contains("jmixWorkbench.validations"))
        assertTrue(xml.contains("jmixWorkbench.sideEffects"))
        assertTrue(xml.contains("jmixWorkbench.outcomeId"))
        assertTrue(xml.contains("\${approved == true}"))
    }

    @Test
    fun `regulated workflow emits retries quorum timers errors gateways call activities and DMN`() {
        val workflow = WorkflowModel(
            id = "regulated-credit",
            name = "Regulated Credit",
            moduleId = "credit",
            businessKeyExpression = "\${creditApplication.id}",
            versionTag = "3.2.0",
            nodes = listOf(
                node("start", "Start", WorkflowNodeType.START, "NEW"),
                node("reviewers", "Committee review", WorkflowNodeType.HUMAN_STATE, "REVIEW")
                    .copy(
                        actorRoleCodes = listOf("credit-committee"),
                        multiInstanceMode = WorkflowMultiInstanceMode.PARALLEL,
                        collectionExpression = "\${committeeMembers}",
                        elementVariable = "reviewer",
                        completionCondition = "\${nrOfCompletedInstances >= 2}",
                        minimumApprovals = 2,
                        segregationOfDutyNodeIds = listOf("maker"),
                    ),
                node("maker", "Prepare application", WorkflowNodeType.HUMAN_STATE, "PREPARED")
                    .copy(actorRoleCodes = listOf("credit-maker")),
                node("risk", "Evaluate risk", WorkflowNodeType.BUSINESS_RULE_STATE, "RISKED")
                    .copy(decisionTableKey = "credit-risk-v3"),
                node("ledger", "Post ledger", WorkflowNodeType.AUTOMATED_STATE, "POSTED")
                    .copy(
                        serviceBean = "ledgerPostingService",
                        serviceMethod = "post",
                        async = true,
                        exclusive = true,
                        retryCycle = "R5/PT5M",
                        idempotencyKeyExpression = "\${businessKey + ':ledger'}",
                    ),
                node("kyc", "Reusable KYC", WorkflowNodeType.CALL_ACTIVITY, "KYC")
                    .copy(
                        calledElement = "kyc-process",
                        inputMappings = listOf(
                            WorkflowVariableMapping(source = "customerId", target = "customerId"),
                        ),
                        outputMappings = listOf(
                            WorkflowVariableMapping(source = "kycResult", target = "kycResult"),
                        ),
                    ),
                node("sla", "SLA breach", WorkflowNodeType.BOUNDARY_TIMER, "OVERDUE")
                    .copy(
                        attachedToNodeId = "reviewers",
                        timerType = WorkflowTimerType.DURATION,
                        timerExpression = "PT24H",
                        cancelActivity = false,
                    ),
                node("failure", "Policy error", WorkflowNodeType.ERROR_END, "FAILED")
                    .copy(eventReference = "credit-policy-error"),
                node("done", "Done", WorkflowNodeType.TERMINAL, "DONE"),
            ),
            transitions = listOf(
                edge("flow-1", "start", "maker"),
                edge("flow-2", "maker", "reviewers"),
                edge("flow-3", "reviewers", "risk"),
                edge("flow-4", "risk", "ledger"),
                edge("flow-5", "ledger", "kyc"),
                edge("flow-6", "kyc", "done"),
                edge("flow-sla", "sla", "failure"),
            ),
        )

        val xml = BpmGenerator.generate(workflow)
        val document = parse(xml)
        assertEquals(1, document.getElementsByTagName("multiInstanceLoopCharacteristics").length)
        assertEquals(1, document.getElementsByTagName("boundaryEvent").length)
        assertEquals(1, document.getElementsByTagName("timerEventDefinition").length)
        assertEquals(1, document.getElementsByTagName("callActivity").length)
        assertEquals(1, document.getElementsByTagName("error").length)
        assertEquals(1, document.getElementsByTagName("errorEventDefinition").length)
        assertTrue(xml.contains("flowable:failedJobRetryTimeCycle"))
        assertTrue(xml.contains("decisionTableReferenceKey"))
        assertTrue(xml.contains("jmixWorkbench.minimumApprovals"))
        assertTrue(xml.contains("jmixWorkbench.segregationOfDutyNodes"))
        assertTrue(xml.contains("jmixWorkbench.idempotencyKey"))
        assertTrue(xml.contains("jmixWorkbench.businessKeyExpression"))
        assertTrue(xml.contains("<flowable:in"))
        assertTrue(xml.contains("<flowable:out"))
    }

    @Test
    fun `event orchestration emits messages signals compensation lanes and BPMN diagram interchange`() {
        val workflow = WorkflowModel(
            id = "payment-orchestration",
            name = "Payment Orchestration",
            moduleId = "payments",
            lanes = listOf(
                WorkflowLaneModel("payments-lane", "Payments", listOf("payments-operator")),
            ),
            nodes = listOf(
                node("message-start", "Payment requested", WorkflowNodeType.MESSAGE_START, "REQUESTED")
                    .copy(eventReference = "payment-requested", laneId = "payments-lane", x = 20, y = 90),
                node("charge", "Charge account", WorkflowNodeType.AUTOMATED_STATE, "CHARGED")
                    .copy(serviceBean = "paymentService", serviceMethod = "charge", laneId = "payments-lane", x = 180, y = 90),
                node("signal", "Publish posted", WorkflowNodeType.SIGNAL_THROW, "POSTED")
                    .copy(eventReference = "payment-posted", async = true, laneId = "payments-lane", x = 390, y = 90),
                node("wait", "Wait for settlement", WorkflowNodeType.MESSAGE_CATCH, "WAITING")
                    .copy(eventReference = "settlement-confirmed", laneId = "payments-lane", x = 560, y = 90),
                node("undo", "Reverse charge", WorkflowNodeType.AUTOMATED_STATE, "REVERSING")
                    .copy(serviceBean = "paymentService", serviceMethod = "reverse", forCompensation = true, x = 390, y = 250),
                node("comp-boundary", "Register reversal", WorkflowNodeType.BOUNDARY_COMPENSATION, "COMPENSATABLE")
                    .copy(attachedToNodeId = "charge", compensationHandlerNodeId = "undo", x = 320, y = 160),
                node("done", "Settled", WorkflowNodeType.TERMINAL, "SETTLED")
                    .copy(laneId = "payments-lane", x = 760, y = 90),
            ),
            transitions = listOf(
                edge("flow-start", "message-start", "charge"),
                edge("flow-charge", "charge", "signal"),
                edge("flow-signal", "signal", "wait"),
                edge("flow-wait", "wait", "done"),
            ),
        )

        val xml = BpmGenerator.generate(workflow)
        val document = parse(xml)
        assertEquals(2, document.getElementsByTagName("message").length)
        assertEquals(1, document.getElementsByTagName("signal").length)
        assertEquals(2, document.getElementsByTagName("messageEventDefinition").length)
        assertEquals(1, document.getElementsByTagName("signalEventDefinition").length)
        assertEquals(1, document.getElementsByTagName("compensateEventDefinition").length)
        assertEquals(1, document.getElementsByTagName("association").length)
        assertEquals(1, document.getElementsByTagName("laneSet").length)
        assertEquals(1, document.getElementsByTagName("bpmndi:BPMNDiagram").length)
        assertEquals(5, document.getElementsByTagName("bpmndi:BPMNEdge").length)
        assertTrue(xml.contains("isForCompensation=\"true\""))
        assertTrue(xml.contains("flowable:scope=\"global\""))
        assertTrue(xml.contains("flowable:async=\"true\""))
    }

    private fun node(
        id: String,
        name: String,
        type: WorkflowNodeType,
        state: String,
        actorRoles: List<String> = emptyList(),
        requiredDocuments: List<String> = emptyList(),
        validationRules: List<String> = emptyList(),
        sideEffects: List<String> = emptyList(),
    ) = WorkflowNodeModel(
        id = id,
        name = name,
        type = type,
        stateValue = state,
        actorRoleCodes = actorRoles,
        requiredDocuments = requiredDocuments,
        validationRules = validationRules,
        sideEffects = sideEffects,
    )

    private fun edge(
        id: String,
        source: String,
        target: String,
        condition: String? = null,
    ) = WorkflowTransitionModel(
        id = id,
        sourceId = source,
        targetId = target,
        conditionExpression = condition,
    )

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }
}

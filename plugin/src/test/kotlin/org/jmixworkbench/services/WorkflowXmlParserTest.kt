package org.jmixworkbench.services

import org.jmixworkbench.generator.BpmGenerator
import org.jmixworkbench.model.WorkflowLaneModel
import org.jmixworkbench.model.WorkflowFormData
import org.jmixworkbench.model.WorkflowFormField
import org.jmixworkbench.model.WorkflowFormOutcome
import org.jmixworkbench.model.WorkflowFormType
import org.jmixworkbench.model.WorkflowEntityDataOperation
import org.jmixworkbench.model.WorkflowEmailAttachmentModel
import org.jmixworkbench.model.WorkflowLoadResultMode
import org.jmixworkbench.model.WorkflowListenerImplementationType
import org.jmixworkbench.model.WorkflowListenerModel
import org.jmixworkbench.model.WorkflowModel
import org.jmixworkbench.model.WorkflowNodeModel
import org.jmixworkbench.model.WorkflowNodeType
import org.jmixworkbench.model.WorkflowProcessVariable
import org.jmixworkbench.model.WorkflowTransitionModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowXmlParserTest {
    @Test
    fun `nested subprocess and native Jmix email task round trip with scope and dimensions`() {
        val original = WorkflowModel(
            id = "nested-notification",
            name = "Nested Notification",
            moduleId = "core",
            nodes = listOf(
                WorkflowNodeModel("start", "Start", WorkflowNodeType.START),
                WorkflowNodeModel(
                    "notify-scope",
                    "Notification scope",
                    WorkflowNodeType.EMBEDDED_SUBPROCESS,
                    width = 440,
                    height = 280,
                ),
                WorkflowNodeModel(
                    "nested-start",
                    "Nested start",
                    WorkflowNodeType.START,
                    parentSubprocessId = "notify-scope",
                ),
                WorkflowNodeModel(
                    id = "email",
                    name = "Email customer",
                    type = WorkflowNodeType.EMAIL_STATE,
                    parentSubprocessId = "notify-scope",
                    emailTo = "\${customer.email}",
                    emailSubject = "Approved",
                    emailContent = "<strong>Approved</strong>",
                    emailAttachments = listOf(
                        WorkflowEmailAttachmentModel("approval", expression = "\${approvalDocument}"),
                    ),
                ),
                WorkflowNodeModel(
                    "nested-end",
                    "Nested end",
                    WorkflowNodeType.TERMINAL,
                    parentSubprocessId = "notify-scope",
                ),
                WorkflowNodeModel("done", "Done", WorkflowNodeType.TERMINAL),
            ),
            transitions = listOf(
                WorkflowTransitionModel("f1", "start", "notify-scope"),
                WorkflowTransitionModel("f2", "notify-scope", "done"),
                WorkflowTransitionModel("nf1", "nested-start", "email"),
                WorkflowTransitionModel("nf2", "email", "nested-end"),
            ),
        )

        val response = WorkflowXmlParser.parse(
            BpmGenerator.generate(original),
            "core",
            "core/src/main/resources/processes/nested-notification.bpmn20.xml",
        )

        assertTrue(response.editable, response.unsupportedElements.joinToString())
        val parsed = assertNotNull(response.workflow)
        val subprocess = parsed.nodes.first { it.id == "notify-scope" }
        assertEquals(WorkflowNodeType.EMBEDDED_SUBPROCESS, subprocess.type)
        assertEquals(440, subprocess.width)
        assertEquals(280, subprocess.height)
        val email = parsed.nodes.first { it.id == "email" }
        assertEquals("notify-scope", email.parentSubprocessId)
        assertEquals(WorkflowNodeType.EMAIL_STATE, email.type)
        assertEquals("\${customer.email}", email.emailTo)
        assertEquals(1, email.emailAttachments.size)
        assertEquals("\${approvalDocument}", email.emailAttachments.single().expression)
        assertEquals(4, parsed.transitions.size)
    }

    @Test
    fun `generated workflow round trips into an editable revision-bound model`() {
        val original = WorkflowModel(
            id = "loan-review",
            name = "Loan Review",
            moduleId = "loan",
            entityQualifiedName = "com.company.loan.entity.LoanApp",
            stateAttribute = "processState",
            businessKeyExpression = "\${loanApp.id}",
            lanes = listOf(WorkflowLaneModel("risk", "Risk", listOf("risk-manager"))),
            executionListeners = listOf(
                WorkflowListenerModel(
                    "start",
                    WorkflowListenerImplementationType.EXPRESSION,
                    "\${auditService.processStarted(execution)}",
                ),
            ),
            nodes = listOf(
                node("start", "Start", WorkflowNodeType.START, 20, 40, "risk")
                    .copy(processVariables = listOf(WorkflowProcessVariable("loanApp", "entity"))),
                node("review", "Review", WorkflowNodeType.HUMAN_STATE, 220, 40, "risk")
                    .copy(
                        actorRoleCodes = listOf("risk-manager"),
                        dueDate = "PT8H",
                        taskListeners = listOf(
                            WorkflowListenerModel(
                                "complete",
                                WorkflowListenerImplementationType.CLASS,
                                "com.company.loan.ReviewAuditListener",
                            ),
                        ),
                        formData = WorkflowFormData(
                            type = WorkflowFormType.INPUT_DIALOG,
                            fields = listOf(
                                WorkflowFormField("comment", "Comment", "multiline-string", required = true),
                            ),
                            outcomes = listOf(
                                WorkflowFormOutcome("approve", "Approve", "CHECK"),
                                WorkflowFormOutcome("reject", "Reject", "BAN"),
                            ),
                        ),
                    ),
                node("done", "Done", WorkflowNodeType.TERMINAL, 440, 40, "risk"),
            ),
            transitions = listOf(
                WorkflowTransitionModel("flow-1", "start", "review"),
                WorkflowTransitionModel("flow-2", "review", "done"),
            ),
        )
        val xml = BpmGenerator.generate(original)

        val response = WorkflowXmlParser.parse(
            xml = xml,
            moduleId = "loan",
            relativePath = "loan/src/main/resources/processes/loan-review.bpmn20.xml",
            requestedProcessId = "loan-review",
        )

        assertTrue(response.editable)
        assertTrue(response.unsupportedElements.isEmpty())
        val parsed = assertNotNull(response.workflow)
        assertEquals(original.id, parsed.id)
        assertEquals(original.entityQualifiedName, parsed.entityQualifiedName)
        assertEquals(original.businessKeyExpression, parsed.businessKeyExpression)
        assertEquals("loan/src/main/resources/processes/loan-review.bpmn20.xml", parsed.sourceRelativePath)
        assertNotNull(parsed.sourceFingerprint)
        assertEquals(3, parsed.nodes.size)
        assertEquals(20, parsed.nodes.first { it.id == "start" }.x)
        assertEquals("risk", parsed.nodes.first { it.id == "review" }.laneId)
        assertEquals(listOf("risk-manager"), parsed.nodes.first { it.id == "review" }.actorRoleCodes)
        assertEquals(2, parsed.transitions.size)
        assertEquals(1, parsed.executionListeners.size)
        assertEquals(1, parsed.nodes.first { it.id == "review" }.taskListeners.size)
        assertEquals(1, parsed.nodes.first { it.id == "start" }.processVariables.size)
        assertEquals(1, parsed.nodes.first { it.id == "review" }.formData?.fields?.size)
        assertEquals(2, parsed.nodes.first { it.id == "review" }.formData?.outcomes?.size)
    }

    @Test
    fun `unknown executable extensions lock round trip editing instead of dropping source`() {
        val xml = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn">
              <process id="custom-process" name="Custom" isExecutable="true">
                <startEvent id="start" />
                <serviceTask id="custom" name="Custom task" flowable:class="com.company.CustomDelegate">
                  <extensionElements>
                    <flowable:customAudit mode="strict" />
                  </extensionElements>
                </serviceTask>
                <endEvent id="done" />
                <sequenceFlow id="f1" sourceRef="start" targetRef="custom" />
                <sequenceFlow id="f2" sourceRef="custom" targetRef="done" />
              </process>
            </definitions>
        """.trimIndent()

        val response = WorkflowXmlParser.parse(xml, "core", "core/src/main/resources/custom.bpmn20.xml")

        assertFalse(response.editable)
        assertTrue(response.unsupportedElements.any { it.contains("customAudit") })
        assertNotNull(response.workflow)
    }

    @Test
    fun `Groovy and native Jmix entity data tasks round trip without losing runtime fields`() {
        val original = WorkflowModel(
            id = "entity-data-orchestration",
            name = "Entity Data Orchestration",
            moduleId = "core",
            nodes = listOf(
                WorkflowNodeModel("start", "Start", WorkflowNodeType.START),
                WorkflowNodeModel(
                    id = "calculate",
                    name = "Calculate threshold",
                    type = WorkflowNodeType.SCRIPT_STATE,
                    script = "return baseAmount * 2",
                    resultVariable = "threshold",
                ),
                WorkflowNodeModel(
                    id = "load",
                    name = "Load applications",
                    type = WorkflowNodeType.ENTITY_DATA_STATE,
                    entityDataOperation = WorkflowEntityDataOperation.LOAD,
                    jpql = "select e from loan_LoanApp e where e.amount > :amount",
                    resultVariable = "loanApps",
                    saveLoadResultAs = WorkflowLoadResultMode.COLLECTION,
                    jpqlParametersJson =
                        """[{"name":"amount","valueType":"processVariable","value":"threshold"}]""",
                ),
                WorkflowNodeModel(
                    id = "modify",
                    name = "Mark application",
                    type = WorkflowNodeType.ENTITY_DATA_STATE,
                    entityDataOperation = WorkflowEntityDataOperation.MODIFY,
                    entityName = "loan_LoanApp",
                    entityVariable = "loanApp",
                    entityAttributesJson =
                        """[{"name":"status","valueType":"directValue","value":"REVIEWED"}]""",
                ),
                WorkflowNodeModel(
                    id = "create",
                    name = "Create audit event",
                    type = WorkflowNodeType.ENTITY_DATA_STATE,
                    entityDataOperation = WorkflowEntityDataOperation.CREATE,
                    entityName = "audit_AuditEvent",
                    resultVariable = "auditEvent",
                    entityAttributesJson =
                        """[{"name":"eventType","valueType":"directValue","value":"REVIEWED"}]""",
                ),
                WorkflowNodeModel("done", "Done", WorkflowNodeType.TERMINAL),
            ),
            transitions = listOf(
                WorkflowTransitionModel("f1", "start", "calculate"),
                WorkflowTransitionModel("f2", "calculate", "load"),
                WorkflowTransitionModel("f3", "load", "modify"),
                WorkflowTransitionModel("f4", "modify", "create"),
                WorkflowTransitionModel("f5", "create", "done"),
            ),
        )

        val xml = BpmGenerator.generate(original)
        val response = WorkflowXmlParser.parse(
            xml,
            "core",
            "core/src/main/resources/processes/entity-data-orchestration.bpmn20.xml",
        )

        assertTrue(response.editable, response.unsupportedElements.joinToString())
        val parsed = assertNotNull(response.workflow)
        val script = parsed.nodes.first { it.id == "calculate" }
        assertEquals(WorkflowNodeType.SCRIPT_STATE, script.type)
        assertEquals("return baseAmount * 2", script.script)
        assertEquals("threshold", script.resultVariable)
        val load = parsed.nodes.first { it.id == "load" }
        assertEquals(WorkflowEntityDataOperation.LOAD, load.entityDataOperation)
        assertEquals(WorkflowLoadResultMode.COLLECTION, load.saveLoadResultAs)
        assertEquals("loanApps", load.resultVariable)
        assertTrue(load.jpql.orEmpty().startsWith("select e"))
        val modify = parsed.nodes.first { it.id == "modify" }
        assertEquals(WorkflowEntityDataOperation.MODIFY, modify.entityDataOperation)
        assertEquals("loan_LoanApp", modify.entityName)
        assertEquals("loanApp", modify.entityVariable)
        val create = parsed.nodes.first { it.id == "create" }
        assertEquals(WorkflowEntityDataOperation.CREATE, create.entityDataOperation)
        assertEquals("auditEvent", create.resultVariable)
        assertTrue(create.entityAttributesJson.orEmpty().contains("eventType"))
    }

    private fun node(
        id: String,
        name: String,
        type: WorkflowNodeType,
        x: Int,
        y: Int,
        laneId: String,
    ) = WorkflowNodeModel(
        id = id,
        name = name,
        type = type,
        laneId = laneId,
        x = x,
        y = y,
    )
}

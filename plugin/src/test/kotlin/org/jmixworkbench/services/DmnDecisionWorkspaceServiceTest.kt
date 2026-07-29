package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jmixworkbench.model.DmnConditionModel
import org.jmixworkbench.model.DmnConditionOperator
import org.jmixworkbench.model.DmnDecisionModel
import org.jmixworkbench.model.DmnDecisionRuleModel
import org.jmixworkbench.model.DmnInputModel
import org.jmixworkbench.model.DmnOutputModel
import org.jmixworkbench.model.DmnValueType
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmnDecisionWorkspaceServiceTest : HeavyPlatformTestCase() {
    fun testCreatesIndexesAndRoundTripsOwnedDmnWithoutOverwritingManualChanges() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(root, "build.gradle.kts", """plugins { id("io.jmix") version "2.8.3" }""")
            write(
                root,
                "src/main/resources/processes/loan.bpmn20.xml",
                """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn">
                  <process id="loanApproval">
                    <serviceTask id="evaluate" name="Evaluate eligibility" flowable:type="dmn">
                      <extensionElements>
                        <flowable:field name="decisionTableReferenceKey">
                          <flowable:string>loanEligibility</flowable:string>
                        </flowable:field>
                      </extensionElements>
                    </serviceTask>
                  </process>
                </definitions>
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )
        val service = DmnDecisionWorkspaceService.getInstance(project)
        val destination = requireNotNull(service.load(forceRefresh = true).destinations.firstOrNull {
            it.resourceRoot == "src/main/resources"
        })
        val model = model(destination.id)

        val proposal = service.propose(model)
        val change = requireNotNull(proposal.changeSet) {
            "DMN proposal was rejected: ${proposal.issues}"
        }.files.single()
        val generated = requireNotNull(change.createContent)
        assertContains(change.relativePath, "src/main/resources/dmn/loan-eligibility.dmn")
        assertContains(generated, "<!-- JVW-DMN-MODEL:")
        assertTrue(service.preview(model).accepted)

        WriteAction.run<RuntimeException> {
            write(root, change.relativePath, generated)
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val workspace = service.load(forceRefresh = true)
        val document = workspace.existingDocuments.single()
        assertTrue(document.editable, document.issue)
        assertTrue(workspace.workflowReferences.single().resolved)
        assertTrue(workspace.issues.none { it.code == "JVW-DMN-WORKFLOW-UNRESOLVED" })

        val sourceFile = requireNotNull(root.findFileByRelativePath(change.relativePath))
        WriteAction.run<RuntimeException> {
            VfsUtil.saveText(sourceFile, "$generated<!-- manual risk-owner customization -->\n")
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val changed = service.load(forceRefresh = true).existingDocuments.single()
        assertFalse(changed.editable)
        val rejected = service.propose(requireNotNull(changed.model))
        assertTrue(rejected.changeSet == null)
        assertTrue(rejected.issues.any { it.code == "JVW-DMN-SOURCE-NOT-OWNED" })
    }

    fun testParsesStandardDmnForInspectionButKeepsItReadOnly() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/resources/dmn/manual.dmn",
                """
                <definitions xmlns="http://www.omg.org/spec/DMN/20151101" namespace="https://bank.example/dmn">
                  <decision id="manualDecision" name="Manual decision">
                    <decisionTable id="manualTable" hitPolicy="FIRST">
                      <input id="risk" label="Risk">
                        <inputExpression id="riskExpression" typeRef="number"><text>risk</text></inputExpression>
                      </input>
                      <output id="result" label="Result" name="result" typeRef="string"/>
                      <rule id="review">
                        <inputEntry id="reviewRisk"><text>&gt;=80</text></inputEntry>
                        <outputEntry id="reviewResult"><text>"REVIEW"</text></outputEntry>
                      </rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )

        val document = DmnDecisionWorkspaceService.getInstance(project)
            .load(forceRefresh = true)
            .existingDocuments
            .single()

        assertFalse(document.editable)
        assertTrue(document.model?.key == "manualDecision")
        assertContains(document.issue.orEmpty(), "parsed for inspection")
    }

    private fun model(destinationId: String) = DmnDecisionModel(
        name = "Loan eligibility",
        key = "loanEligibility",
        destinationId = destinationId,
        fileName = "loan-eligibility.dmn",
        inputs = listOf(DmnInputModel("amount", "Amount", "amount", DmnValueType.NUMBER)),
        outputs = listOf(DmnOutputModel("decision", "Decision", "decision", DmnValueType.STRING)),
        rules = listOf(
            DmnDecisionRuleModel(
                id = "approve",
                inputEntries = mapOf(
                    "amount" to DmnConditionModel(DmnConditionOperator.LESS_THAN_OR_EQUAL, "100000"),
                ),
                outputEntries = mapOf("decision" to "APPROVE"),
            ),
        ),
    )

    private fun write(root: VirtualFile, path: String, content: String) {
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) root else requireNotNull(VfsUtil.createDirectoryIfMissing(root, parentPath))
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}

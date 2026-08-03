package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.model.ScenarioActorMode
import org.jmixworkbench.model.ScenarioAssertionOperator
import org.jmixworkbench.model.ScenarioFieldValueModel
import org.jmixworkbench.model.ScenarioStepKind
import org.jmixworkbench.model.ScenarioStepModel
import org.jmixworkbench.model.ScenarioTestModel
import org.jmixworkbench.model.ScenarioValueModel
import org.jmixworkbench.model.ScenarioValueType
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScenarioWorkspaceServiceTest : HeavyPlatformTestCase() {
    fun testMultiModuleScenarioPreviewApplyDiscoveryAndManualChangeProtection() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "src/main/java/com/acme/loan/entity/LoanApp.java",
                """
                package com.acme.loan.entity;
                @JmixEntity
                public class LoanApp {
                    private java.math.BigDecimal amount;
                    private String processState;
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/loan/LoanService.java",
                """
                package com.acme.loan;
                @org.springframework.stereotype.Service("loanService")
                public class LoanService {
                    public Object approve(Object loan) { return loan; }
                }
                """.trimIndent(),
            )
            VfsUtil.createDirectoryIfMissing(root, "src/test/java")
        }
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/test/java")),
            JavaSourceRootType.TEST_SOURCE,
        )
        ApplicationGraphService.getInstance(project).invalidate()
        val service = ScenarioWorkspaceService.getInstance(project)
        val workspace = service.load(true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        val model = scenario(destination.id, destination.defaultPackage)

        val preview = service.preview(model)

        assertTrue(preview.accepted, preview.issues.joinToString { "${it.code}: ${it.message}" })
        val generated = preview.files.single().resultContent
        assertContains(generated, "@SpringBootTest")
        assertContains(generated, "SystemAuthenticator")
        assertContains(generated, "EntityValues.setValue")
        assertContains(generated, "loanService")
        assertContains(generated, "select e from com.acme.loan.entity.LoanApp e")
        val applied = WorkspaceChangeService.getInstance(project).applyPrepared(
            service.prepare(
                ScenarioTestApplyRequest(model, requireNotNull(preview.planDigest)),
            ),
        )
        assertTrue(applied.success, applied.issues.joinToString { it.message })
        val writtenPath = preview.files.single().relativePath
        assertTrue(root.findFileByRelativePath(writtenPath) != null)
        assertTrue(WorkspaceHistoryService.getInstance(project).snapshot().canUndo)

        ApplicationGraphService.getInstance(project).invalidate()
        val discovered = service.load(true).existingScenarios.single {
            it.locator.relativePath == writtenPath
        }
        assertTrue(discovered.editable)
        val update = discovered.model.copy(description = "Reviewed enterprise lifecycle")
        val updatePreview = service.preview(update)
        assertTrue(updatePreview.accepted, updatePreview.issues.joinToString { it.message })
        assertTrue(updatePreview.files.single().mode.name == "MODIFY")

        WriteAction.run<RuntimeException> {
            val file = requireNotNull(root.findFileByRelativePath(writtenPath))
            VfsUtil.saveText(file, read(file) + "\n// manual risk-team change\n")
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val protected = service.load(true).existingScenarios.single {
            it.locator.relativePath == writtenPath
        }
        assertFalse(protected.editable)
        val rejected = service.preview(protected.model)
        assertFalse(rejected.accepted)
        assertTrue(rejected.issues.any { it.code == "JVW-SCENARIO-SOURCE-NOT-OWNED" })
    }

    fun testInvalidVariableAndUnindexedEntityAreRejectedBeforePreview() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            VfsUtil.createDirectoryIfMissing(root, "src/main/java")
            VfsUtil.createDirectoryIfMissing(root, "src/test/java")
        }
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/test/java")),
            JavaSourceRootType.TEST_SOURCE,
        )
        val workspace = ScenarioWorkspaceService.getInstance(project).load(true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        val invalid = ScenarioTestModel(
            name = "Invalid scenario",
            destinationId = destination.id,
            packageName = destination.defaultPackage,
            className = "InvalidScenarioTest",
            steps = listOf(
                ScenarioStepModel(
                    id = "seed",
                    label = "Seed missing entity",
                    kind = ScenarioStepKind.SEED_ENTITY,
                    variableName = "loan",
                    entityClass = "com.acme.Missing",
                ),
                ScenarioStepModel(
                    id = "call",
                    label = "Use missing variable",
                    kind = ScenarioStepKind.INVOKE_SERVICE,
                    beanName = "loanService",
                    methodName = "approve",
                    arguments = listOf(ScenarioValueModel(ScenarioValueType.VARIABLE, "missing")),
                ),
            ),
        )

        val preview = ScenarioWorkspaceService.getInstance(project).preview(invalid)

        assertFalse(preview.accepted)
        assertTrue(preview.issues.any { it.code == "JVW-SCENARIO-ENTITY-NOT-INDEXED" })
        assertTrue(preview.issues.any { it.code == "JVW-SCENARIO-VALUE-INVALID" })
    }

    private fun scenario(destinationId: String, packageName: String): ScenarioTestModel =
        ScenarioTestModel(
            name = "Loan approval lifecycle",
            description = "Seed, approve, assert and verify persistence",
            destinationId = destinationId,
            packageName = packageName,
            className = "LoanApprovalLifecycleTest",
            steps = listOf(
                ScenarioStepModel(
                    id = "seedLoan",
                    label = "Seed loan",
                    kind = ScenarioStepKind.SEED_ENTITY,
                    variableName = "loan",
                    entityClass = "com.acme.loan.entity.LoanApp",
                    fields = listOf(
                        ScenarioFieldValueModel(
                            "amount",
                            ScenarioValueModel(ScenarioValueType.DECIMAL, "25000.00"),
                        ),
                        ScenarioFieldValueModel(
                            "processState",
                            ScenarioValueModel(ScenarioValueType.STRING, "SUBMITTED"),
                        ),
                    ),
                ),
                ScenarioStepModel(
                    id = "approve",
                    label = "Approve as payroll officer",
                    kind = ScenarioStepKind.INVOKE_SERVICE,
                    actorMode = ScenarioActorMode.USER,
                    username = "payroll-officer",
                    beanName = "loanService",
                    methodName = "approve",
                    arguments = listOf(
                        ScenarioValueModel(ScenarioValueType.VARIABLE, "loan"),
                    ),
                ),
                ScenarioStepModel(
                    id = "assertState",
                    label = "Assert approved state",
                    kind = ScenarioStepKind.ASSERT_PROPERTY,
                    targetVariable = "loan",
                    propertyPath = "processState",
                    operator = ScenarioAssertionOperator.EQUALS,
                    expected = ScenarioValueModel(ScenarioValueType.STRING, "APPROVED"),
                ),
                ScenarioStepModel(
                    id = "assertCount",
                    label = "Assert persisted loan count",
                    kind = ScenarioStepKind.ASSERT_ENTITY_COUNT,
                    entityClass = "com.acme.loan.entity.LoanApp",
                    jpql = "select e from com.acme.loan.entity.LoanApp e",
                    expectedCount = 1,
                ),
            ),
        )

    private fun write(root: VirtualFile, path: String, content: String) {
        val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }

    private fun read(file: VirtualFile): String =
        String(file.contentsToByteArray(false), file.charset)
}

package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.model.LogicEntityOperation
import org.jmixworkbench.model.LogicMethodKind
import org.jmixworkbench.model.LogicNodeKind
import org.jmixworkbench.model.LogicNodeModel
import org.jmixworkbench.model.LogicTransitionModel
import org.jmixworkbench.model.VisualLogicClassModel
import org.jmixworkbench.model.VisualLogicMethodModel
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualLogicWorkspaceServiceTest : HeavyPlatformTestCase() {
    fun testCreatesRoundTripOwnedServiceAndLocksAfterManualJavaChange() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(root, "build.gradle.kts", "plugins { id(\"io.jmix\") version \"2.8.3\" }")
            write(
                root,
                "src/main/java/com/acme/loan/entity/LoanApp.java",
                """
                package com.acme.loan.entity;
                @JmixEntity
                public class LoanApp {}
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        val service = VisualLogicWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(
            workspace.destinations.firstOrNull(),
        ) { "Production Java destination was not discovered." }
        val model = VisualLogicClassModel(
            name = "Loan authorization service",
            destinationId = destination.id,
            packageName = "com.acme.loan.service",
            className = "VisualLoanAuthorizationService",
            beanName = "visualLoanAuthorizationService",
            methods = listOf(
                VisualLogicMethodModel(
                    name = "verifyReadAccess",
                    nodes = listOf(
                        LogicNodeModel("start", "Start", LogicNodeKind.START),
                        LogicNodeModel(
                            id = "authorize",
                            label = "Authorize read",
                            kind = LogicNodeKind.AUTHORIZE_ENTITY,
                            entityClass = "com.acme.loan.entity.LoanApp",
                            entityOperation = LogicEntityOperation.READ,
                        ),
                        LogicNodeModel(
                            id = "record",
                            label = "Record access",
                            kind = LogicNodeKind.CALL_SUBFLOW,
                            subflowMethod = "recordAccess",
                        ),
                        LogicNodeModel("return", "Return", LogicNodeKind.RETURN),
                    ),
                    transitions = listOf(
                        LogicTransitionModel("start-auth", "start", "authorize"),
                        LogicTransitionModel("auth-record", "authorize", "record"),
                        LogicTransitionModel("record-return", "record", "return"),
                    ),
                ),
                VisualLogicMethodModel(
                    name = "recordAccess",
                    kind = LogicMethodKind.SUBFLOW,
                    transaction = org.jmixworkbench.model.LogicTransactionModel(enabled = false),
                    nodes = listOf(
                        LogicNodeModel("subflow-start", "Start", LogicNodeKind.START),
                        LogicNodeModel("subflow-return", "Return", LogicNodeKind.RETURN),
                    ),
                    transitions = listOf(
                        LogicTransitionModel(
                            "subflow-start-return",
                            "subflow-start",
                            "subflow-return",
                        ),
                    ),
                ),
            ),
        )

        val proposal = service.propose(model)
        val change = requireNotNull(
            proposal.changeSet,
        ) { "Visual logic proposal was rejected: ${proposal.issues}" }.files.single()
        val generated = requireNotNull(
            change.createContent,
        ) { "Create proposal did not contain generated source." }
        assertTrue(proposal.issues.isEmpty(), proposal.issues.joinToString())
        assertContains(generated, "// JVW-VISUAL-LOGIC-MODEL:")
        assertContains(generated, "accessContext.isReadPermitted()")
        assertContains(generated, "private void recordAccess()")
        val preview = service.preview(model)
        assertTrue(preview.accepted, preview.issues.joinToString())
        requireNotNull(preview.planDigest)

        WriteAction.run<RuntimeException> {
            write(root, change.relativePath, generated)
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val reloaded = service.load(forceRefresh = true)
        val document = requireNotNull(
            reloaded.existingDocuments.singleOrNull(),
        ) { "Generated visual service was not rediscovered." }
        assertTrue(document.editable)
        assertTrue(document.model.methods.any { it.kind == LogicMethodKind.SUBFLOW })
        assertTrue(
            document.model.methods
                .first { it.name == "verifyReadAccess" }
                .nodes
                .any { it.kind == LogicNodeKind.CALL_SUBFLOW && it.subflowMethod == "recordAccess" },
        )

        val sourceFile = requireNotNull(root.findFileByRelativePath(change.relativePath))
        WriteAction.run<RuntimeException> {
            VfsUtil.saveText(sourceFile, generated + "\n// handwritten change\n")
        }
        val rejected = service.propose(document.model)

        assertTrue(rejected.changeSet == null)
        assertTrue(rejected.issues.any { it.code == "JVW-LOGIC-SOURCE-NOT-OWNED" })
        assertFalse(service.load(forceRefresh = true).existingDocuments.single().editable)
    }

    private fun write(root: VirtualFile, path: String, content: String) {
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            root
        } else {
            requireNotNull(VfsUtil.createDirectoryIfMissing(root, parentPath))
        }
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}

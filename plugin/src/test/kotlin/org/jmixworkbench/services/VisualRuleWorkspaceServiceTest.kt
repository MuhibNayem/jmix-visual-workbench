package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.model.RuleDataType
import org.jmixworkbench.model.RuleExpressionKind
import org.jmixworkbench.model.RuleExpressionModel
import org.jmixworkbench.model.RuleParameterModel
import org.jmixworkbench.model.RuleValueSource
import org.jmixworkbench.model.VisualRuleKind
import org.jmixworkbench.model.VisualRuleModel
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualRuleWorkspaceServiceTest : HeavyPlatformTestCase() {
    fun testCreatesRoundTripOwnedRuleAndLocksAfterManualJavaChange() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(root, "build.gradle.kts", "plugins { id(\"io.jmix\") version \"2.8.3\" }")
            write(
                root,
                "src/main/java/com/acme/loan/entity/LoanApp.java",
                """
                package com.acme.loan.entity;
                @JmixEntity
                public class LoanApp {
                    private java.math.BigDecimal requestedAmount;
                }
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        val service = VisualRuleWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        val model = VisualRuleModel(
            name = "Positive loan amount",
            kind = VisualRuleKind.PREDICATE,
            destinationId = destination.id,
            packageName = "com.acme.loan.rule",
            className = "PositiveLoanAmountRule",
            beanName = "positiveLoanAmountRule",
            outputJavaType = "boolean",
            parameters = listOf(
                RuleParameterModel(
                    "loan",
                    "com.acme.loan.entity.LoanApp",
                    RuleDataType.ENTITY,
                ),
            ),
            expression = RuleExpressionModel(
                id = "positive",
                label = "Requested amount is positive",
                kind = RuleExpressionKind.GREATER_THAN,
                dataType = RuleDataType.BOOLEAN,
                children = listOf(
                    RuleExpressionModel(
                        id = "requested",
                        label = "Requested amount",
                        kind = RuleExpressionKind.PROPERTY,
                        dataType = RuleDataType.DECIMAL,
                        parameterName = "loan",
                        propertyPath = "requestedAmount",
                    ),
                    RuleExpressionModel(
                        id = "zero",
                        label = "Zero",
                        kind = RuleExpressionKind.VALUE,
                        dataType = RuleDataType.DECIMAL,
                        valueSource = RuleValueSource.LITERAL,
                        value = "0.00",
                    ),
                ),
            ),
        )

        val proposal = service.propose(model)
        val change = requireNotNull(proposal.changeSet) {
            "Visual rule proposal was rejected: ${proposal.issues}"
        }.files.single()
        val generated = requireNotNull(change.createContent)
        assertContains(generated, "// JVW-VISUAL-RULE-MODEL:")
        assertContains(generated, "EntityValues.getValue(loan, \"requestedAmount\")")
        assertTrue(service.preview(model).accepted)

        WriteAction.run<RuntimeException> {
            write(root, change.relativePath, generated)
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val document = requireNotNull(service.load(forceRefresh = true).existingDocuments.singleOrNull())
        assertTrue(document.editable)

        val sourceFile = requireNotNull(root.findFileByRelativePath(change.relativePath))
        WriteAction.run<RuntimeException> {
            VfsUtil.saveText(sourceFile, "$generated\n// manual enterprise customization\n")
        }
        val rejected = service.propose(document.model)

        assertTrue(rejected.changeSet == null)
        assertTrue(rejected.issues.any { it.code == "JVW-RULE-SOURCE-NOT-OWNED" })
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

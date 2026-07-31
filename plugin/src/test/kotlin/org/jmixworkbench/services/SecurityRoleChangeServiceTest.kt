package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.model.EntityPolicyAction
import org.jmixworkbench.model.RowLevelPolicyAction
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecurityRoleChangeServiceTest : HeavyPlatformTestCase() {
    fun testTargetedPolicyInsertionPreservesManualRoleSource() {
        val source = """
            package com.company.payroll.security;

            import io.jmix.security.role.annotation.ResourceRole;

            @ResourceRole(name = "Payroll officer", code = PayrollOfficerRole.CODE)
            public interface PayrollOfficerRole {
                String CODE = "payroll-officer";

                // Hand-written business permission grouping must remain byte-for-byte.
                void customManualPolicy();
            }
        """.trimIndent() + "\n"
        val path = addRoleSource(
            "src/main/java/com/company/payroll/security/PayrollOfficerRole.java",
            source,
        )
        val preview = SecurityRoleChangeService(project).previewPolicyAddition(
            SecurityRolePolicyChangeRequest(
                roleLocator = locator(path, source),
                roleClassName = "com.company.payroll.security.PayrollOfficerRole",
                policy = SecurityRolePolicyModel(
                    type = SecurityRolePolicyType.ENTITY,
                    entityClass = "com.company.payroll.entity.PayrollRun",
                    entityActions = mutableListOf(EntityPolicyAction.READ, EntityPolicyAction.UPDATE),
                ),
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val result = preview.files.single().resultContent
        assertContains(result, "// Hand-written business permission grouping must remain byte-for-byte.")
        assertContains(result, "void customManualPolicy();")
        assertContains(result, "import com.company.payroll.entity.PayrollRun;")
        assertContains(result, "import io.jmix.security.role.annotation.EntityPolicy;")
        assertContains(result, "@EntityPolicy(entityClass = PayrollRun.class")
        assertContains(result, "void payrollRun();")
        assertTrue(preview.files.single().appliedEditCount in 1..2)
    }

    fun testImportCollisionUsesQualifiedJmixTypeInsteadOfBreakingExistingSource() {
        val source = """
            package com.company.payroll.security;

            import com.company.legacy.EntityPolicy;
            import io.jmix.security.role.annotation.ResourceRole;

            @ResourceRole(name = "Payroll officer", code = PayrollOfficerRole.CODE)
            public interface PayrollOfficerRole {
                String CODE = "payroll-officer";
            }
        """.trimIndent() + "\n"
        val path = addRoleSource(
            "src/main/java/com/company/payroll/security/PayrollOfficerRole.java",
            source,
        )
        val preview = SecurityRoleChangeService(project).previewPolicyAddition(
            SecurityRolePolicyChangeRequest(
                roleLocator = locator(path, source),
                roleClassName = "com.company.payroll.security.PayrollOfficerRole",
                policy = SecurityRolePolicyModel(
                    type = SecurityRolePolicyType.ENTITY,
                    entityClass = "com.company.payroll.entity.PayrollRun",
                    entityActions = mutableListOf(EntityPolicyAction.READ),
                ),
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val result = preview.files.single().resultContent
        assertContains(
            result,
            "@io.jmix.security.role.annotation.EntityPolicy(entityClass = PayrollRun.class",
        )
        assertFalse("import io.jmix.security.role.annotation.EntityPolicy;" in result)
        assertContains(result, "import com.company.legacy.EntityPolicy;")
    }

    fun testStaleRevisionAndInvalidPredicateAreRejectedBeforeWrite() {
        val source = """
            package com.company.loan.security;

            import io.jmix.security.role.annotation.RowLevelRole;

            @RowLevelRole(name = "Own loans", code = OwnLoansRole.CODE)
            public interface OwnLoansRole {
                String CODE = "own-loans";
            }
        """.trimIndent() + "\n"
        val path = addRoleSource(
            "src/main/java/com/company/loan/security/OwnLoansRole.java",
            source,
        )
        val service = SecurityRoleChangeService(project)

        val stale = service.previewPolicyAddition(
            SecurityRolePolicyChangeRequest(
                roleLocator = SourceLocator(
                    relativePath = path,
                    revisionFingerprint = "stale-fingerprint",
                ),
                roleClassName = "com.company.loan.security.OwnLoansRole",
                policy = SecurityRolePolicyModel(
                    type = SecurityRolePolicyType.JPQL_ROW,
                    entityClass = "com.company.loan.entity.LoanApp",
                    whereClause = "{E}.createdBy = :current_user_username",
                ),
            ),
        )
        assertFalse(stale.accepted)
        assertTrue(stale.issues.any { it.code == "JVW-ROLE-SOURCE-STALE" })

        val invalidPredicate = service.previewPolicyAddition(
            SecurityRolePolicyChangeRequest(
                roleLocator = locator(path, source),
                roleClassName = "com.company.loan.security.OwnLoansRole",
                policy = SecurityRolePolicyModel(
                    type = SecurityRolePolicyType.PREDICATE_ROW,
                    entityClass = "com.company.loan.entity.LoanApp",
                    rowActions = mutableListOf(RowLevelPolicyAction.READ),
                    predicateExpression = "entity.getStatus(",
                ),
            ),
        )
        assertFalse(invalidPredicate.accepted)
        assertTrue(invalidPredicate.issues.any { it.code == "JVW-ROLE-SOURCE-SYNTAX" })
    }

    fun testInspectionReturnsEditableCanonicalModelsAndExactLocations() {
        val source = """
            package com.company.payroll.security;

            import com.company.payroll.entity.PayrollRun;
            import io.jmix.security.model.EntityPolicyAction;
            import io.jmix.security.role.annotation.EntityPolicy;
            import io.jmix.security.role.annotation.ResourceRole;
            import io.jmix.securityflowui.role.annotation.MenuPolicy;
            import io.jmix.securityflowui.role.annotation.ViewPolicy;

            @ResourceRole(name = "Payroll officer", code = PayrollOfficerRole.CODE)
            public interface PayrollOfficerRole {
                String CODE = "payroll-officer";

                @EntityPolicy(
                    entityClass = PayrollRun.class,
                    actions = {EntityPolicyAction.READ, EntityPolicyAction.UPDATE}
                )
                void payrollRuns();

                @MenuPolicy(menuIds = {"payroll", "payrollRuns"})
                @ViewPolicy(viewIds = "payroll_PayrollRun.list")
                void screens();
            }
        """.trimIndent() + "\n"
        val path = addRoleSource(
            "src/main/java/com/company/payroll/security/PayrollOfficerRole.java",
            source,
        )
        val response = SecurityRoleChangeService(project).inspectPolicies(
            SecurityRolePolicyInspectionRequest(
                roleLocator = locator(path, source),
                roleClassName = "com.company.payroll.security.PayrollOfficerRole",
            ),
        )

        assertTrue(response.accepted, response.issues.joinToString { it.message })
        assertEquals(3, response.policies.size)
        val entity = response.policies.single { it.type == SecurityRolePolicyType.ENTITY }
        assertTrue(entity.editable, entity.editIssue)
        assertEquals("com.company.payroll.entity.PayrollRun", entity.policy?.entityClass)
        assertEquals(
            listOf(EntityPolicyAction.READ, EntityPolicyAction.UPDATE),
            entity.policy?.entityActions,
        )
        assertEquals(14, entity.locator.line)
        val menu = response.policies.single { it.type == SecurityRolePolicyType.MENU }
        assertEquals(listOf("payroll", "payrollRuns"), menu.policy?.resources)
        assertTrue(menu.locator.symbol?.endsWith("#MenuPolicy-2") == true)
        assertTrue(
            response.policies.single { it.type == SecurityRolePolicyType.VIEW }
                .locator.symbol?.endsWith("#ViewPolicy-3") == true,
        )
    }

    fun testReplacementChangesOnlyTargetAnnotationAndPreservesManualMethod() {
        val source = """
            package com.company.payroll.security;

            import com.company.payroll.entity.PayrollRun;
            import io.jmix.security.model.EntityPolicyAction;
            import io.jmix.security.role.annotation.EntityPolicy;
            import io.jmix.security.role.annotation.ResourceRole;

            @ResourceRole(name = "Payroll officer", code = PayrollOfficerRole.CODE)
            public interface PayrollOfficerRole {
                String CODE = "payroll-officer";

                // Keep this business explanation and the deliberate method name.
                @EntityPolicy(entityClass = PayrollRun.class, actions = EntityPolicyAction.READ)
                void manuallyNamedPayrollBoundary();
            }
        """.trimIndent() + "\n"
        val path = addRoleSource(
            "src/main/java/com/company/payroll/security/PayrollOfficerRole.java",
            source,
        )
        val roleLocator = locator(path, source)
        val inspection = SecurityRoleChangeService(project).inspectPolicies(
            SecurityRolePolicyInspectionRequest(
                roleLocator = roleLocator,
                roleClassName = "com.company.payroll.security.PayrollOfficerRole",
            ),
        )
        val existing = inspection.policies.single()
        val preview = SecurityRoleChangeService(project).previewPolicyReplacement(
            SecurityRolePolicyReplacementRequest(
                roleLocator = roleLocator,
                roleClassName = "com.company.payroll.security.PayrollOfficerRole",
                policyLocator = existing.locator,
                replacement = requireNotNull(existing.policy).copy(
                    entityActions = mutableListOf(EntityPolicyAction.READ, EntityPolicyAction.UPDATE),
                ),
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val result = preview.files.single().resultContent
        assertContains(result, "// Keep this business explanation and the deliberate method name.")
        assertContains(result, "void manuallyNamedPayrollBoundary();")
        assertContains(result, "EntityPolicyAction.READ")
        assertContains(result, "EntityPolicyAction.UPDATE")
        assertEquals(1, result.split("void manuallyNamedPayrollBoundary").size - 1)
    }

    fun testRemovingOneCompositePolicyPreservesSiblingAnnotationAndMethod() {
        val source = """
            package com.company.payroll.security;

            import io.jmix.security.role.annotation.ResourceRole;
            import io.jmix.securityflowui.role.annotation.MenuPolicy;
            import io.jmix.securityflowui.role.annotation.ViewPolicy;

            @ResourceRole(name = "Payroll officer", code = PayrollOfficerRole.CODE)
            public interface PayrollOfficerRole {
                String CODE = "payroll-officer";

                @MenuPolicy(menuIds = "payroll")
                @ViewPolicy(viewIds = "payroll_PayrollRun.list")
                void screens();
            }
        """.trimIndent() + "\n"
        val path = addRoleSource(
            "src/main/java/com/company/payroll/security/PayrollOfficerRole.java",
            source,
        )
        val roleLocator = locator(path, source)
        val service = SecurityRoleChangeService(project)
        val menu = service.inspectPolicies(
            SecurityRolePolicyInspectionRequest(
                roleLocator = roleLocator,
                roleClassName = "com.company.payroll.security.PayrollOfficerRole",
            ),
        ).policies.single { it.type == SecurityRolePolicyType.MENU }
        val preview = service.previewPolicyRemoval(
            SecurityRolePolicyRemovalRequest(
                roleLocator = roleLocator,
                roleClassName = "com.company.payroll.security.PayrollOfficerRole",
                policyLocator = menu.locator,
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val result = preview.files.single().resultContent
        assertFalse("@MenuPolicy" in result)
        assertContains(result, "@ViewPolicy(viewIds = \"payroll_PayrollRun.list\")")
        assertContains(result, "void screens();")
    }

    fun testRemovingSolePredicateDeletesOnlyItsMethod() {
        val source = """
            package com.company.loan.security;

            import com.company.loan.entity.LoanApp;
            import io.jmix.security.model.RowLevelPolicyAction;
            import io.jmix.security.model.RowLevelPredicate;
            import io.jmix.security.role.annotation.PredicateRowLevelPolicy;
            import io.jmix.security.role.annotation.RowLevelRole;

            @RowLevelRole(name = "Own loans", code = OwnLoansRole.CODE)
            public interface OwnLoansRole {
                String CODE = "own-loans";

                // This comment documents why the policy exists and must survive review.
                @PredicateRowLevelPolicy(entityClass = LoanApp.class, actions = RowLevelPolicyAction.READ)
                static RowLevelPredicate<LoanApp> ownLoanPredicate() {
                    return entity -> entity.getCreatedBy() != null;
                }

                void unrelatedManualMarker();
            }
        """.trimIndent() + "\n"
        val path = addRoleSource(
            "src/main/java/com/company/loan/security/OwnLoansRole.java",
            source,
        )
        val roleLocator = locator(path, source)
        val service = SecurityRoleChangeService(project)
        val predicate = service.inspectPolicies(
            SecurityRolePolicyInspectionRequest(
                roleLocator = roleLocator,
                roleClassName = "com.company.loan.security.OwnLoansRole",
            ),
        ).policies.single()
        assertTrue(predicate.editable, predicate.editIssue)
        assertEquals("entity.getCreatedBy() != null", predicate.policy?.predicateExpression)
        val preview = service.previewPolicyRemoval(
            SecurityRolePolicyRemovalRequest(
                roleLocator = roleLocator,
                roleClassName = "com.company.loan.security.OwnLoansRole",
                policyLocator = predicate.locator,
            ),
        )

        assertTrue(preview.accepted, preview.issues.joinToString { it.message })
        val result = preview.files.single().resultContent
        assertFalse("ownLoanPredicate" in result)
        assertContains(result, "// This comment documents why the policy exists and must survive review.")
        assertContains(result, "void unrelatedManualMarker();")
    }

    private fun locator(relativePath: String, content: String): SourceLocator =
        SourceLocator(
            relativePath = relativePath,
            revisionFingerprint = CanonicalDiscoveryJson.sha256(content),
        )

    private fun addRoleSource(requestedPath: String, content: String): String {
        val projectRoot = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val parent = requireNotNull(
                VfsUtil.createDirectoryIfMissing(projectRoot, requestedPath.substringBeforeLast('/')),
            )
            val file = parent.findOrCreateChildData(this, requestedPath.substringAfterLast('/'))
            VfsUtil.saveText(file, content)
        }
        return requestedPath
    }
}

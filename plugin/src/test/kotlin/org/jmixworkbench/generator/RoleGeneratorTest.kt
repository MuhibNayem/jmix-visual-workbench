package org.jmixworkbench.generator

import org.jmixworkbench.model.EntityAttributePolicyAction
import org.jmixworkbench.model.EntityAttributePolicyModel
import org.jmixworkbench.model.EntityPolicyAction
import org.jmixworkbench.model.EntityPolicyModel
import org.jmixworkbench.model.MenuPolicyModel
import org.jmixworkbench.model.RoleModel
import org.jmixworkbench.model.RoleScope
import org.jmixworkbench.model.RowLevelPolicyAction
import org.jmixworkbench.model.RowLevelPolicyModel
import org.jmixworkbench.model.RowLevelPolicyType
import org.jmixworkbench.model.SpecificPolicyModel
import org.jmixworkbench.model.ViewPolicyModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RoleGeneratorTest {
    @Test
    fun `resource role uses current Jmix annotations and valid interface methods`() {
        val source = RoleGenerator.generate(
            RoleModel(
                className = "PayrollOfficerRole",
                packageName = "com.company.payroll.security",
                name = "Payroll officer",
                code = "payroll-officer",
                description = "Processes approved payroll runs",
                securityScopes = mutableListOf("UI"),
                entityPolicies = mutableListOf(
                    EntityPolicyModel(
                        entityClass = "com.company.payroll.entity.PayrollRun",
                        actions = mutableListOf(EntityPolicyAction.READ, EntityPolicyAction.UPDATE),
                    ),
                ),
                entityAttributePolicies = mutableListOf(
                    EntityAttributePolicyModel(
                        entityClass = "com.company.payroll.entity.PayrollRun",
                        attributes = mutableListOf("status", "approvedAt"),
                        action = EntityAttributePolicyAction.MODIFY,
                    ),
                ),
                menuPolicies = mutableListOf(MenuPolicyModel("payrollRuns")),
                viewPolicies = mutableListOf(ViewPolicyModel("payroll_PayrollRun.list")),
                specificPolicies = mutableListOf(SpecificPolicyModel("payroll.run.approve")),
                baseRoleClasses = mutableListOf("com.company.shared.security.EmployeeRole"),
            ),
            "com.company.fallback.security",
        )

        assertContains(source, "import io.jmix.securityflowui.role.annotation.MenuPolicy;")
        assertContains(source, "import io.jmix.securityflowui.role.annotation.ViewPolicy;")
        assertContains(source, "@ResourceRole(name = \"Payroll officer\", code = PayrollOfficerRole.CODE")
        assertContains(source, "scope = \"UI\"")
        assertContains(source, "@EntityPolicy(entityClass = PayrollRun.class")
        assertContains(source, "EntityPolicyAction.READ")
        assertContains(source, "@EntityAttributePolicy(entityClass = PayrollRun.class")
        assertContains(source, "@MenuPolicy(menuIds = \"payrollRuns\")")
        assertContains(source, "@ViewPolicy(viewIds = \"payroll_PayrollRun.list\")")
        assertContains(source, "@SpecificPolicy(resources = \"payroll.run.approve\")")
        assertContains(source, "public interface PayrollOfficerRole extends EmployeeRole")
        assertContains(source, "void payrollRun();")
        assertFalse("ScreenPolicy" in source)
        assertFalse("io.jmix.security.model.MenuPolicy" in source)
    }

    @Test
    fun `resource role omits scope when both built-in scopes are selected`() {
        val source = RoleGenerator.generate(
            RoleModel(
                className = "SharedApiRole",
                name = "Shared API",
                code = "shared-api",
                securityScopes = mutableListOf("UI", "API"),
                baseRoleClasses = mutableListOf("com.company.shared.security.BasicRole"),
            ),
            "com.company.shared.security",
        )

        assertFalse("scope =" in source)
    }

    @Test
    fun `JPQL row role emits bounded current annotation`() {
        val source = RoleGenerator.generate(
            RoleModel(
                className = "OwnLoansRole",
                name = "Own loans",
                code = "own-loans",
                scope = RoleScope.ROW_LEVEL,
                rowLevelPolicies = mutableListOf(
                    RowLevelPolicyModel(
                        entityClass = "com.company.loan.entity.LoanApp",
                        type = RowLevelPolicyType.JPQL,
                        whereClause = "{E}.employee.username = :current_user_username",
                        joinClause = "left join {E}.employee employee",
                    ),
                ),
            ),
            "com.company.loan.security",
        )

        assertContains(source, "import io.jmix.security.role.annotation.JpqlRowLevelPolicy;")
        assertContains(source, "@RowLevelRole(name = \"Own loans\", code = OwnLoansRole.CODE)")
        assertContains(source, "join = \"left join {E}.employee employee\"")
        assertContains(source, "where = \"{E}.employee.username = :current_user_username\"")
        assertContains(source, "void loanApp();")
    }

    @Test
    fun `predicate row role emits an explicit typed predicate`() {
        val source = RoleGenerator.generate(
            RoleModel(
                className = "ActiveLoanRole",
                name = "Active loans",
                code = "active-loans",
                scope = RoleScope.ROW_LEVEL,
                rowLevelPolicies = mutableListOf(
                    RowLevelPolicyModel(
                        entityClass = "com.company.loan.entity.LoanApp",
                        type = RowLevelPolicyType.PREDICATE,
                        actions = mutableListOf(RowLevelPolicyAction.READ, RowLevelPolicyAction.UPDATE),
                        predicateExpression = "entity.getClosedAt() == null",
                    ),
                ),
            ),
            "com.company.loan.security",
        )

        assertContains(source, "actions = {RowLevelPolicyAction.READ, RowLevelPolicyAction.UPDATE}")
        assertContains(source, "static RowLevelPredicate<LoanApp> loanAppPredicate()")
        assertContains(source, "return entity -> entity.getClosedAt() == null;")
    }

    @Test
    fun `same simple entity names remain unambiguous`() {
        val source = RoleGenerator.generate(
            RoleModel(
                className = "CrossModuleOrderRole",
                name = "Cross-module orders",
                code = "cross-module-orders",
                entityPolicies = mutableListOf(
                    EntityPolicyModel(
                        entityClass = "com.company.sales.entity.Order",
                        actions = mutableListOf(EntityPolicyAction.READ),
                    ),
                    EntityPolicyModel(
                        entityClass = "com.company.purchase.entity.Order",
                        actions = mutableListOf(EntityPolicyAction.READ),
                    ),
                ),
            ),
            "com.company.shared.security",
        )

        assertContains(source, "entityClass = com.company.sales.entity.Order.class")
        assertContains(source, "entityClass = com.company.purchase.entity.Order.class")
        assertFalse("import com.company.sales.entity.Order;" in source)
        assertFalse("import com.company.purchase.entity.Order;" in source)
    }

    @Test
    fun `wildcards require explicit acknowledgement`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            RoleGenerator.generate(
                RoleModel(
                    className = "UnsafeRole",
                    name = "Unsafe",
                    code = "unsafe",
                    viewPolicies = mutableListOf(ViewPolicyModel("*")),
                ),
                "com.company.security",
            )
        }

        assertContains(failure.message.orEmpty(), "JVW-ROLE-WILDCARD-ACKNOWLEDGEMENT")
    }

    @Test
    fun `invalid entity and unsafe JPQL syntax are rejected before source creation`() {
        val entityFailure = assertFailsWith<IllegalArgumentException> {
            RoleGenerator.generate(
                RoleModel(
                    className = "BrokenRole",
                    name = "Broken",
                    code = "broken",
                    entityPolicies = mutableListOf(
                        EntityPolicyModel(
                            entityClass = "LoanApp",
                            actions = mutableListOf(EntityPolicyAction.READ),
                        ),
                    ),
                ),
                "com.company.security",
            )
        }
        assertContains(entityFailure.message.orEmpty(), "fully qualified")

        val jpqlFailure = assertFailsWith<IllegalArgumentException> {
            RoleGenerator.generate(
                RoleModel(
                    className = "BrokenRowRole",
                    name = "Broken row",
                    code = "broken-row",
                    scope = RoleScope.ROW_LEVEL,
                    rowLevelPolicies = mutableListOf(
                        RowLevelPolicyModel(
                            entityClass = "com.company.loan.entity.LoanApp",
                            type = RowLevelPolicyType.JPQL,
                            whereClause = "where {E}.status = 'ACTIVE'",
                        ),
                    ),
                ),
                "com.company.security",
            )
        }
        assertContains(jpqlFailure.message.orEmpty(), "JVW-ROLE-JPQL-WHERE-PREFIX")
    }
}

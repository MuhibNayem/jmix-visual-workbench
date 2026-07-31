package org.jmixworkbench.generator

import org.jmixworkbench.model.ScenarioActorMode
import org.jmixworkbench.model.ScenarioAssertionOperator
import org.jmixworkbench.model.ScenarioFieldValueModel
import org.jmixworkbench.model.ScenarioStepKind
import org.jmixworkbench.model.ScenarioStepModel
import org.jmixworkbench.model.ScenarioTestModel
import org.jmixworkbench.model.ScenarioValueModel
import org.jmixworkbench.model.ScenarioValueType
import kotlin.test.Test
import kotlin.test.assertContains

class ScenarioTestGeneratorTest {
    @Test
    fun `generates executable security-aware Jmix integration scenario`() {
        val source = ScenarioTestGenerator.generate(
            ScenarioTestModel(
                name = "Approve and settle loan",
                description = "Financial lifecycle",
                destinationId = "loan-tests",
                packageName = "com.acme.loan.scenario",
                className = "LoanLifecycleScenarioTest",
                steps = listOf(
                    ScenarioStepModel(
                        id = "seedLoan",
                        label = "Seed submitted loan",
                        kind = ScenarioStepKind.SEED_ENTITY,
                        variableName = "loan",
                        entityClass = "com.acme.loan.entity.LoanApp",
                        fields = listOf(
                            ScenarioFieldValueModel(
                                "amount",
                                ScenarioValueModel(ScenarioValueType.DECIMAL, "1000.00"),
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
                        resultVariable = "approvalResult",
                    ),
                    ScenarioStepModel(
                        id = "assertResult",
                        label = "Verify service result",
                        kind = ScenarioStepKind.ASSERT_VALUE,
                        targetVariable = "approvalResult",
                        operator = ScenarioAssertionOperator.TRUE,
                    ),
                    ScenarioStepModel(
                        id = "rejectDuplicate",
                        label = "Reject duplicate approval",
                        kind = ScenarioStepKind.ASSERT_SERVICE_FAILURE,
                        actorMode = ScenarioActorMode.USER,
                        username = "payroll-officer",
                        beanName = "loanService",
                        methodName = "approve",
                        arguments = listOf(
                            ScenarioValueModel(ScenarioValueType.VARIABLE, "loan"),
                        ),
                        expectedExceptionClass = "com.acme.loan.DuplicateApprovalException",
                        messageContains = "already approved",
                    ),
                    ScenarioStepModel(
                        id = "assertState",
                        label = "Verify approved state",
                        kind = ScenarioStepKind.ASSERT_PROPERTY,
                        targetVariable = "loan",
                        propertyPath = "processState",
                        operator = ScenarioAssertionOperator.EQUALS,
                        expected = ScenarioValueModel(ScenarioValueType.STRING, "APPROVED"),
                    ),
                ),
            ),
            encodedModel = "encoded-model",
        )

        assertContains(source, "@SpringBootTest")
        assertContains(source, "// JVW-SCENARIO-MODEL: encoded-model")
        assertContains(source, "dataManager.create((Class) loadClass(\"com.acme.loan.entity.LoanApp\"))")
        assertContains(source, "new BigDecimal(\"1000.00\")")
        assertContains(source, "systemAuthenticator.withUser(username")
        assertContains(source, "invokeBean(\"loanService\", \"approve\"")
        assertContains(source, "EntityValues.getValueEx(requireVariable(\"loan\"), \"processState\")")
        assertContains(source, "assertScenarioValue(requireVariable(\"approvalResult\"), \"TRUE\", null)")
        assertContains(source, "rootCause(failure)")
        assertContains(source, "loadClass(\"com.acme.loan.DuplicateApprovalException\").isInstance(expectedFailure)")
        assertContains(source, "contains(\"already approved\")")
        assertContains(source, "dataManager.remove(createdEntities.get(index))")
    }
}

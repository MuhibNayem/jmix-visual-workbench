package org.jmixworkbench.generator

import org.jmixworkbench.model.LogicConditionModel
import org.jmixworkbench.model.LogicConditionOperator
import org.jmixworkbench.model.LogicEntityOperation
import org.jmixworkbench.model.LogicLogLevel
import org.jmixworkbench.model.LogicMethodKind
import org.jmixworkbench.model.LogicMethodParameterModel
import org.jmixworkbench.model.LogicNamedValueModel
import org.jmixworkbench.model.LogicNodeKind
import org.jmixworkbench.model.LogicNodeModel
import org.jmixworkbench.model.LogicTransactionModel
import org.jmixworkbench.model.LogicTransitionBranch
import org.jmixworkbench.model.LogicTransitionModel
import org.jmixworkbench.model.LogicValueModel
import org.jmixworkbench.model.LogicValueSource
import org.jmixworkbench.model.LogicValueType
import org.jmixworkbench.model.VisualLogicClassModel
import org.jmixworkbench.model.VisualLogicMethodModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualLogicGeneratorTest {
    @Test
    fun `generates reusable subflows collection iteration and structured exception boundary`() {
        val noTransaction = LogicTransactionModel(enabled = false)
        val entry = VisualLogicMethodModel(
            name = "process",
            returnJavaType = "void",
            parameters = listOf(
                LogicMethodParameterModel("items", "java.util.List<java.lang.String>"),
            ),
            nodes = listOf(
                node("start", LogicNodeKind.START),
                node(
                    "iterate",
                    LogicNodeKind.FOR_EACH,
                    resultVariable = "item",
                    resultJavaType = "java.lang.String",
                    indexVariable = "itemIndex",
                    value = LogicValueModel(
                        LogicValueSource.PARAMETER,
                        LogicValueType.OBJECT,
                        "items",
                    ),
                ),
                node(
                    "normalize",
                    LogicNodeKind.CALL_SUBFLOW,
                    resultVariable = "normalized",
                    resultJavaType = "java.lang.String",
                    subflowMethod = "normalizeItem",
                    arguments = listOf(variable("item", LogicValueType.STRING)),
                ),
                node(
                    "audit",
                    LogicNodeKind.LOG,
                    message = "Processed item {} at {}",
                    arguments = listOf(
                        variable("normalized", LogicValueType.STRING),
                        variable("itemIndex", LogicValueType.INTEGER),
                    ),
                ),
                node(
                    "guarded",
                    LogicNodeKind.TRY_CATCH,
                    subflowMethod = "riskyStep",
                    catchMethod = "recoverStep",
                    finallyMethod = "cleanupStep",
                    exceptionType = "java.lang.RuntimeException",
                ),
                node("return", LogicNodeKind.RETURN),
            ),
            transitions = listOf(
                edge("start-iterate", "start", "iterate"),
                edge("iterate-item", "iterate", "normalize", LogicTransitionBranch.ITEM),
                edge("iterate-done", "iterate", "guarded", LogicTransitionBranch.DONE),
                edge("normalize-audit", "normalize", "audit"),
                edge("audit-iterate", "audit", "iterate"),
                edge("guarded-return", "guarded", "return"),
            ),
        )
        val normalize = VisualLogicMethodModel(
            name = "normalizeItem",
            kind = LogicMethodKind.SUBFLOW,
            returnJavaType = "java.lang.String",
            parameters = listOf(LogicMethodParameterModel("item", "java.lang.String")),
            transaction = noTransaction,
            nodes = listOf(
                node("normalize-start", LogicNodeKind.START),
                node(
                    "normalize-return",
                    LogicNodeKind.RETURN,
                    value = LogicValueModel(
                        LogicValueSource.PARAMETER,
                        LogicValueType.STRING,
                        "item",
                    ),
                ),
            ),
            transitions = linearTransitions("normalize-start", "normalize-return"),
        )
        fun voidSubflow(
            name: String,
            parameters: List<LogicMethodParameterModel> = emptyList(),
        ) = VisualLogicMethodModel(
            name = name,
            kind = LogicMethodKind.SUBFLOW,
            parameters = parameters,
            transaction = noTransaction,
            nodes = listOf(
                node("$name-start", LogicNodeKind.START),
                node("$name-return", LogicNodeKind.RETURN),
            ),
            transitions = linearTransitions("$name-start", "$name-return"),
        )
        val model = VisualLogicClassModel(
            name = "Batch operation",
            destinationId = "core:main",
            packageName = "com.acme",
            className = "BatchOperation",
            beanName = "batchOperation",
            methods = listOf(
                entry,
                normalize,
                voidSubflow("riskyStep"),
                voidSubflow(
                    "recoverStep",
                    listOf(
                        LogicMethodParameterModel(
                            "error",
                            "java.lang.RuntimeException",
                        ),
                    ),
                ),
                voidSubflow("cleanupStep"),
            ),
        )

        val validation = VisualLogicGenerator.validate(model)
        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val source = VisualLogicGenerator.generate(model, "structured")

        assertContains(source, "@SuppressWarnings(\"JVW-VISUAL-SUBFLOW\")")
        assertContains(source, "private java.lang.String normalizeItem(java.lang.String item)")
        assertContains(source, "java.util.Iterator<?> __iterator_0 = null")
        assertContains(source, "item = (java.lang.String) __iterator_0.next()")
        assertContains(source, "itemIndex = itemIndex + 1")
        assertContains(source, "normalized = normalizeItem(item)")
        assertContains(source, "catch (java.lang.RuntimeException __error)")
        assertContains(source, "recoverStep(__error)")
        assertContains(source, "finally {")
        assertContains(source, "cleanupStep()")
    }

    @Test
    fun `rejects recursive subflows and incomplete collection branches`() {
        val subflow = VisualLogicMethodModel(
            name = "recursive",
            kind = LogicMethodKind.SUBFLOW,
            transaction = LogicTransactionModel(enabled = false),
            nodes = listOf(
                node("sub-start", LogicNodeKind.START),
                node(
                    "self",
                    LogicNodeKind.CALL_SUBFLOW,
                    subflowMethod = "recursive",
                ),
                node("sub-return", LogicNodeKind.RETURN),
            ),
            transitions = linearTransitions("sub-start", "self", "sub-return"),
        )
        val entry = VisualLogicMethodModel(
            name = "execute",
            nodes = listOf(
                node("start", LogicNodeKind.START),
                node(
                    "iterate",
                    LogicNodeKind.FOR_EACH,
                    resultVariable = "item",
                    resultJavaType = "java.lang.String",
                    value = LogicValueModel(
                        LogicValueSource.PARAMETER,
                        LogicValueType.OBJECT,
                        "items",
                    ),
                ),
                node("return", LogicNodeKind.RETURN),
            ),
            parameters = listOf(
                LogicMethodParameterModel("items", "java.util.List<java.lang.String>"),
            ),
            transitions = listOf(
                edge("start-iterate", "start", "iterate"),
                edge("iterate-item", "iterate", "return", LogicTransitionBranch.ITEM),
            ),
        )
        val model = VisualLogicClassModel(
            name = "Invalid structured logic",
            destinationId = "core:main",
            packageName = "com.acme",
            className = "InvalidStructuredLogic",
            beanName = "invalidStructuredLogic",
            methods = listOf(entry, subflow),
        )

        val validation = VisualLogicGenerator.validate(model)

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "LOGIC_FOR_EACH_BRANCHES" })
        assertTrue(validation.diagnostics.any { it.code == "LOGIC_SUBFLOW_RECURSION" })
    }

    @Test
    fun `rejects typed argument mismatch and transitive write in read only entry point`() {
        val writer = VisualLogicMethodModel(
            name = "persist",
            kind = LogicMethodKind.SUBFLOW,
            parameters = listOf(LogicMethodParameterModel("entity", "com.acme.Entity")),
            transaction = LogicTransactionModel(enabled = false),
            nodes = listOf(
                node("writer-start", LogicNodeKind.START),
                node(
                    "writer-save",
                    LogicNodeKind.SAVE_ENTITY,
                    targetVariable = "entity",
                ),
                node("writer-return", LogicNodeKind.RETURN),
            ),
            transitions = linearTransitions("writer-start", "writer-save", "writer-return"),
        )
        val entry = VisualLogicMethodModel(
            name = "inspect",
            transaction = LogicTransactionModel(readOnly = true),
            parameters = listOf(LogicMethodParameterModel("name", "java.lang.String")),
            nodes = listOf(
                node("entry-start", LogicNodeKind.START),
                node(
                    "entry-call",
                    LogicNodeKind.CALL_SUBFLOW,
                    subflowMethod = "persist",
                    arguments = listOf(variable("name", LogicValueType.STRING)),
                ),
                node("entry-return", LogicNodeKind.RETURN),
            ),
            transitions = linearTransitions("entry-start", "entry-call", "entry-return"),
        )

        val validation = VisualLogicGenerator.validate(
            VisualLogicClassModel(
                name = "Invalid transaction graph",
                destinationId = "core:main",
                packageName = "com.acme",
                className = "InvalidTransactionGraph",
                beanName = "invalidTransactionGraph",
                methods = listOf(entry, writer),
            ),
        )

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "LOGIC_SUBFLOW_ARGUMENT_TYPE" })
        assertTrue(validation.diagnostics.any { it.code == "LOGIC_READ_ONLY_SUBFLOW_WRITE" })
    }

    @Test
    fun `generates typed transactional security constrained service graph`() {
        val entityClass = "com.acme.loan.entity.LoanApp"
        val model = VisualLogicClassModel(
            name = "Loan command service",
            description = "Server-enforced approval flow",
            destinationId = "loan:main",
            packageName = "com.acme.loan.service",
            className = "VisualLoanService",
            beanName = "visualLoanService",
            methods = listOf(
                VisualLogicMethodModel(
                    name = "approve",
                    returnJavaType = entityClass,
                    parameters = listOf(LogicMethodParameterModel("loan", entityClass)),
                    nodes = listOf(
                        node("start", LogicNodeKind.START),
                        node(
                            "authorize",
                            LogicNodeKind.AUTHORIZE_ENTITY,
                            entityClass = entityClass,
                            entityOperation = LogicEntityOperation.UPDATE,
                        ),
                        node(
                            "requireAmount",
                            LogicNodeKind.REQUIRE,
                            condition = LogicConditionModel(
                                variable("loanAmount", LogicValueType.DECIMAL),
                                LogicConditionOperator.GREATER_THAN,
                                decimal("0.00"),
                            ),
                            message = "Loan amount must be positive.",
                        ),
                        node(
                            "setState",
                            LogicNodeKind.SET_PROPERTY,
                            targetVariable = "loan",
                            propertyPath = "processState",
                            value = string("APPROVED"),
                        ),
                        node(
                            "audit",
                            LogicNodeKind.CALL_SERVICE,
                            beanClass = "com.acme.audit.AuditService",
                            beanFieldName = "auditService",
                            methodName = "recordApproval",
                            arguments = listOf(variable("loan", LogicValueType.ENTITY)),
                        ),
                        node(
                            "save",
                            LogicNodeKind.SAVE_ENTITY,
                            targetVariable = "loan",
                            resultVariable = "loan",
                        ),
                        node(
                            "log",
                            LogicNodeKind.LOG,
                            message = "Approved loan {}",
                            logLevel = LogicLogLevel.INFO,
                            arguments = listOf(variable("loan", LogicValueType.ENTITY)),
                        ),
                        node("return", LogicNodeKind.RETURN, value = variable("loan", LogicValueType.ENTITY)),
                    ),
                    transitions = linearTransitions(
                        "start",
                        "authorize",
                        "requireAmount",
                        "setState",
                        "audit",
                        "save",
                        "log",
                        "return",
                    ),
                ),
            ),
        )

        // Add a typed amount parameter used by the validation node.
        val corrected = model.copy(
            methods = model.methods.map {
                it.copy(parameters = it.parameters + LogicMethodParameterModel("loanAmount", "java.math.BigDecimal"))
            },
        )
        val validation = VisualLogicGenerator.validate(corrected)
        assertTrue(validation.valid, validation.diagnostics.joinToString())

        val source = VisualLogicGenerator.generate(corrected, "encoded-visual-model")

        assertContains(source, "// JVW-VISUAL-LOGIC-MODEL: encoded-visual-model")
        assertContains(source, "@Component(\"visualLoanService\")")
        assertContains(source, "@Transactional()")
        assertContains(source, "private final DataManager dataManager;")
        assertContains(source, "private final AccessManager accessManager;")
        assertContains(source, "private final com.acme.audit.AuditService auditService;")
        assertContains(source, "accessManager.applyRegisteredConstraints(accessContext);")
        assertContains(source, "accessContext.isUpdatePermitted()")
        assertContains(source, "EntityValues.setValue(loan, \"processState\", \"APPROVED\")")
        assertContains(source, "auditService.recordApproval(loan)")
        assertContains(source, "loan = dataManager.save(loan)")
        assertContains(source, "compareValues(loanAmount, new BigDecimal(\"0.00\")) > 0")
        assertContains(source, "if (++__executions > 10000)")
        assertFalse(source.contains("UnconstrainedDataManager"))
    }

    @Test
    fun `rejects incomplete branch and writes in read only transaction`() {
        val model = VisualLogicClassModel(
            name = "Invalid",
            destinationId = "core:main",
            packageName = "com.acme",
            className = "InvalidLogic",
            beanName = "invalidLogic",
            methods = listOf(
                VisualLogicMethodModel(
                    name = "invalid",
                    transaction = LogicTransactionModel(readOnly = true),
                    nodes = listOf(
                        node("start", LogicNodeKind.START),
                        node(
                            "condition",
                            LogicNodeKind.CONDITION,
                            condition = LogicConditionModel(boolean(true), LogicConditionOperator.TRUE),
                        ),
                        node(
                            "create",
                            LogicNodeKind.CREATE_ENTITY,
                            resultVariable = "entity",
                            entityClass = "com.acme.Entity",
                            fieldValues = listOf(LogicNamedValueModel("name", string("value"))),
                        ),
                        node("return", LogicNodeKind.RETURN),
                    ),
                    transitions = listOf(
                        edge("start-condition", "start", "condition"),
                        edge("condition-true", "condition", "create", LogicTransitionBranch.TRUE),
                        edge("create-return", "create", "return"),
                    ),
                ),
            ),
        )

        val validation = VisualLogicGenerator.validate(model)

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "LOGIC_CONDITION_BRANCHES" })
        assertTrue(validation.diagnostics.any { it.code == "LOGIC_READ_ONLY_WRITE" })
    }

    @Test
    fun `rejects malformed unary and binary conditions before source generation`() {
        fun invalid(operator: LogicConditionOperator, right: LogicValueModel?) =
            VisualLogicClassModel(
                name = "Invalid condition",
                destinationId = "core:main",
                packageName = "com.acme",
                className = "InvalidConditionLogic",
                beanName = "invalidConditionLogic",
                methods = listOf(
                    VisualLogicMethodModel(
                        name = "evaluate",
                        nodes = listOf(
                            node("start", LogicNodeKind.START),
                            node(
                                "condition",
                                LogicNodeKind.CONDITION,
                                condition = LogicConditionModel(boolean(true), operator, right),
                            ),
                            node("yes", LogicNodeKind.RETURN),
                            node("no", LogicNodeKind.RETURN),
                        ),
                        transitions = listOf(
                            edge("start-condition", "start", "condition"),
                            edge("condition-yes", "condition", "yes", LogicTransitionBranch.TRUE),
                            edge("condition-no", "condition", "no", LogicTransitionBranch.FALSE),
                        ),
                    ),
                ),
            )

        val missingRight = VisualLogicGenerator.validate(
            invalid(LogicConditionOperator.EQUALS, null),
        )
        val unexpectedRight = VisualLogicGenerator.validate(
            invalid(LogicConditionOperator.TRUE, boolean(true)),
        )

        assertTrue(missingRight.diagnostics.any { it.code == "LOGIC_CONDITION_RIGHT_REQUIRED" })
        assertTrue(unexpectedRight.diagnostics.any { it.code == "LOGIC_CONDITION_RIGHT_UNEXPECTED" })
    }

    private fun node(
        id: String,
        kind: LogicNodeKind,
        resultVariable: String? = null,
        resultJavaType: String? = null,
        entityClass: String? = null,
        targetVariable: String? = null,
        propertyPath: String? = null,
        value: LogicValueModel? = null,
        fieldValues: List<LogicNamedValueModel> = emptyList(),
        beanClass: String? = null,
        beanFieldName: String? = null,
        methodName: String? = null,
        subflowMethod: String? = null,
        catchMethod: String? = null,
        finallyMethod: String? = null,
        exceptionType: String? = null,
        indexVariable: String? = null,
        arguments: List<LogicValueModel> = emptyList(),
        condition: LogicConditionModel? = null,
        entityOperation: LogicEntityOperation? = null,
        message: String? = null,
        logLevel: LogicLogLevel = LogicLogLevel.INFO,
    ) = LogicNodeModel(
        id = id,
        label = id,
        kind = kind,
        resultVariable = resultVariable,
        resultJavaType = resultJavaType,
        entityClass = entityClass,
        targetVariable = targetVariable,
        propertyPath = propertyPath,
        value = value,
        fieldValues = fieldValues,
        beanClass = beanClass,
        beanFieldName = beanFieldName,
        methodName = methodName,
        subflowMethod = subflowMethod,
        catchMethod = catchMethod,
        finallyMethod = finallyMethod,
        exceptionType = exceptionType,
        indexVariable = indexVariable,
        arguments = arguments,
        condition = condition,
        entityOperation = entityOperation,
        message = message,
        logLevel = logLevel,
    )

    private fun linearTransitions(vararg nodeIds: String): List<LogicTransitionModel> =
        nodeIds.asList().zipWithNext().map { (source, target) -> edge("$source-$target", source, target) }

    private fun edge(
        id: String,
        source: String,
        target: String,
        branch: LogicTransitionBranch = LogicTransitionBranch.ALWAYS,
    ) = LogicTransitionModel(id, source, target, branch)

    private fun string(value: String) =
        LogicValueModel(LogicValueSource.LITERAL, LogicValueType.STRING, value)

    private fun decimal(value: String) =
        LogicValueModel(LogicValueSource.LITERAL, LogicValueType.DECIMAL, value)

    private fun boolean(value: Boolean) =
        LogicValueModel(LogicValueSource.LITERAL, LogicValueType.BOOLEAN, value.toString())

    private fun variable(name: String, type: LogicValueType) =
        LogicValueModel(LogicValueSource.VARIABLE, type, name)
}

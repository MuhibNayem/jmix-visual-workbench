package org.jmixworkbench.generator

import org.jmixworkbench.model.RuleDataType
import org.jmixworkbench.model.RuleExpressionKind
import org.jmixworkbench.model.RuleExpressionModel
import org.jmixworkbench.model.RuleParameterModel
import org.jmixworkbench.model.RuleValueSource
import org.jmixworkbench.model.VisualRuleKind
import org.jmixworkbench.model.VisualRuleModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualRuleGeneratorTest {
    @Test
    fun `generates deterministic decimal formula without scripting or persistence`() {
        val model = rule(
            kind = VisualRuleKind.FORMULA,
            outputJavaType = "java.math.BigDecimal",
            expression = expression(
                "net",
                RuleExpressionKind.ROUND,
                RuleDataType.DECIMAL,
                children = listOf(
                    expression(
                        "subtract",
                        RuleExpressionKind.SUBTRACT,
                        RuleDataType.DECIMAL,
                        children = listOf(parameter("gross", RuleDataType.DECIMAL), parameter("deduction", RuleDataType.DECIMAL)),
                    ),
                ),
            ),
            parameters = listOf(
                RuleParameterModel("gross", "java.math.BigDecimal", RuleDataType.DECIMAL),
                RuleParameterModel("deduction", "java.math.BigDecimal", RuleDataType.DECIMAL),
            ),
        )

        val validation = VisualRuleGenerator.validate(model)
        assertTrue(validation.valid, validation.diagnostics.joinToString())

        val source = VisualRuleGenerator.generate(model, "encoded-model")

        assertContains(source, "// JVW-VISUAL-RULE-MODEL: encoded-model")
        assertContains(source, "@Component(\"visualPayrollRule\")")
        assertContains(source, "public java.math.BigDecimal evaluate(")
        assertContains(source, "decimal(gross).subtract(decimal(deduction))")
        assertContains(source, ".setScale(DECIMAL_SCALE, ROUNDING_MODE)")
        assertFalse(source.contains("DataManager"))
        assertFalse(source.contains("UnconstrainedDataManager"))
        assertFalse(source.contains("ScriptEngine"))
        assertFalse(source.contains("Groovy"))
    }

    @Test
    fun `generates reusable FlowUI validator bean`() {
        val length = expression(
            "length",
            RuleExpressionKind.LENGTH,
            RuleDataType.INTEGER,
            children = listOf(parameter("value", RuleDataType.STRING)),
        )
        val minimum = literal("minimum", "8", RuleDataType.INTEGER)
        val model = rule(
            kind = VisualRuleKind.VALIDATOR,
            outputJavaType = "boolean",
            expression = expression(
                "strong-enough",
                RuleExpressionKind.GREATER_THAN_OR_EQUAL,
                RuleDataType.BOOLEAN,
                children = listOf(length, minimum),
            ),
            parameters = listOf(RuleParameterModel("value", "String", RuleDataType.STRING)),
            validationMessage = "Value must contain at least eight characters.",
        )

        val source = VisualRuleGenerator.generate(model, "validator-model")

        assertContains(source, "implements Validator<String>")
        assertContains(source, "public void accept(String value)")
        assertContains(source, "throw new ValidationException(\"Value must contain at least eight characters.\")")
        assertContains(source, "text(value).length()")
    }

    @Test
    fun `rejects structurally invalid and type unsafe trees`() {
        val model = rule(
            kind = VisualRuleKind.PREDICATE,
            outputJavaType = "boolean",
            expression = expression(
                "invalid",
                RuleExpressionKind.AND,
                RuleDataType.BOOLEAN,
                children = listOf(literal("amount", "10.00", RuleDataType.DECIMAL)),
            ),
            parameters = emptyList(),
        )

        val validation = VisualRuleGenerator.validate(model)

        assertFalse(validation.valid)
        assertTrue(validation.diagnostics.any { it.code == "RULE_ARITY" })
        assertTrue(validation.diagnostics.any { it.code == "RULE_CHILD_TYPE" })
    }

    private fun rule(
        kind: VisualRuleKind,
        outputJavaType: String,
        expression: RuleExpressionModel,
        parameters: List<RuleParameterModel>,
        validationMessage: String? = null,
    ) = VisualRuleModel(
        name = "Payroll calculation",
        kind = kind,
        destinationId = "payroll:main",
        packageName = "com.acme.payroll.rule",
        className = "VisualPayrollRule",
        beanName = "visualPayrollRule",
        outputJavaType = outputJavaType,
        parameters = parameters,
        expression = expression,
        validationMessage = validationMessage,
    )

    private fun expression(
        id: String,
        kind: RuleExpressionKind,
        dataType: RuleDataType,
        children: List<RuleExpressionModel> = emptyList(),
    ) = RuleExpressionModel(
        id = id,
        label = id,
        kind = kind,
        dataType = dataType,
        children = children,
    )

    private fun parameter(name: String, dataType: RuleDataType) = RuleExpressionModel(
        id = "parameter-$name",
        label = name,
        kind = RuleExpressionKind.VALUE,
        dataType = dataType,
        valueSource = RuleValueSource.PARAMETER,
        parameterName = name,
    )

    private fun literal(id: String, value: String, dataType: RuleDataType) = RuleExpressionModel(
        id = id,
        label = value,
        kind = RuleExpressionKind.VALUE,
        dataType = dataType,
        valueSource = RuleValueSource.LITERAL,
        value = value,
    )
}

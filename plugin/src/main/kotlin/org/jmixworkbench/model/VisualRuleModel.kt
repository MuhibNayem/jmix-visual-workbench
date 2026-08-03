package org.jmixworkbench.model

import org.jmixworkbench.discovery.model.SourceLocator

enum class VisualRuleKind {
    FORMULA,
    PREDICATE,
    VALIDATOR,
}

enum class RuleDataType {
    STRING,
    INTEGER,
    LONG,
    DECIMAL,
    BOOLEAN,
    UUID,
    LOCAL_DATE,
    LOCAL_DATE_TIME,
    OFFSET_DATE_TIME,
    INSTANT,
    ENUM,
    ENTITY,
    OBJECT,
}

enum class RuleValueSource {
    LITERAL,
    PARAMETER,
    NULL,
}

enum class RuleExpressionKind {
    VALUE,
    PROPERTY,
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    NEGATE,
    ABS,
    ROUND,
    MIN,
    MAX,
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    AND,
    OR,
    NOT,
    IF,
    COALESCE,
    CONCAT,
    UPPER,
    LOWER,
    TRIM,
    LENGTH,
    IS_NULL,
    IS_NOT_NULL,
    DATE_PLUS_DAYS,
    DAYS_BETWEEN,
    IN_LIST,
}

data class RuleParameterModel(
    val name: String,
    val javaType: String,
    val dataType: RuleDataType,
    val nullable: Boolean = false,
)

/**
 * A closed, recursively typed expression tree.
 *
 * There is intentionally no arbitrary Java/SpEL/Groovy expression field.
 * Every operation is validated and emitted by the deterministic compiler.
 */
data class RuleExpressionModel(
    val id: String,
    val label: String,
    val kind: RuleExpressionKind,
    val dataType: RuleDataType,
    val javaType: String? = null,
    val valueSource: RuleValueSource? = null,
    val value: String? = null,
    val parameterName: String? = null,
    val propertyPath: String? = null,
    val children: List<RuleExpressionModel> = emptyList(),
)

data class VisualRuleModel(
    val name: String,
    val description: String = "",
    val kind: VisualRuleKind,
    val destinationId: String,
    val packageName: String,
    val className: String,
    val beanName: String,
    val methodName: String = "evaluate",
    val outputJavaType: String,
    val parameters: List<RuleParameterModel>,
    val expression: RuleExpressionModel,
    val validationMessage: String? = null,
    val decimalScale: Int = 2,
    val roundingMode: String = "HALF_EVEN",
    val sourceLocator: SourceLocator? = null,
)

enum class RuleDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class RuleDiagnostic(
    val code: String,
    val severity: RuleDiagnosticSeverity,
    val message: String,
    val expressionId: String? = null,
)

data class VisualRuleValidationResult(
    val valid: Boolean,
    val diagnostics: List<RuleDiagnostic>,
)

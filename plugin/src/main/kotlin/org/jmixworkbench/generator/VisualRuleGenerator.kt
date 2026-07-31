package org.jmixworkbench.generator

import org.jmixworkbench.model.RuleDataType
import org.jmixworkbench.model.RuleDiagnostic
import org.jmixworkbench.model.RuleDiagnosticSeverity
import org.jmixworkbench.model.RuleExpressionKind
import org.jmixworkbench.model.RuleExpressionModel
import org.jmixworkbench.model.RuleValueSource
import org.jmixworkbench.model.VisualRuleKind
import org.jmixworkbench.model.VisualRuleModel
import org.jmixworkbench.model.VisualRuleValidationResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Deterministic compiler for reusable pure server-side rules.
 *
 * The generated component has no persistence, network, reflection, scripting,
 * filesystem, or unconstrained-data APIs. It is safe to reuse from Jmix views,
 * services, REST endpoints, workflow handlers, listeners, and tests.
 */
object VisualRuleGenerator {
    private const val MARKER_PREFIX = "// JVW-VISUAL-RULE-MODEL: "
    private const val MAX_EXPRESSIONS = 5_000
    private const val MAX_DEPTH = 64
    private const val MAX_PARAMETERS = 200
    private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val PACKAGE_NAME = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
    private val JAVA_TYPE = Regex(
        """[A-Za-z_$][A-Za-z0-9_$.]*(?:\s*<\s*[A-Za-z_$][A-Za-z0-9_$.?]*(?:\s*,\s*[A-Za-z_$][A-Za-z0-9_$.?]*)*\s*>)?(?:\[\])*""",
    )
    private val PROPERTY_PATH = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
    private val ENUM_CONSTANT = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val ROUNDING_MODES = RoundingMode.entries.map(Enum<*>::name).toSet()

    fun markerPrefix(): String = MARKER_PREFIX

    fun validate(model: VisualRuleModel): VisualRuleValidationResult {
        val diagnostics = mutableListOf<RuleDiagnostic>()
        fun error(code: String, message: String, expressionId: String? = null) {
            diagnostics += RuleDiagnostic(code, RuleDiagnosticSeverity.ERROR, message, expressionId)
        }
        fun warning(code: String, message: String, expressionId: String? = null) {
            diagnostics += RuleDiagnostic(code, RuleDiagnosticSeverity.WARNING, message, expressionId)
        }

        if (model.name.isBlank() || model.name.length > 240 || model.name.any(Char::isISOControl)) {
            error("RULE_NAME_INVALID", "Rule name must be a visible value of at most 240 characters.")
        }
        if (!PACKAGE_NAME.matches(model.packageName)) error("RULE_PACKAGE_INVALID", "Package name is not valid.")
        if (!IDENTIFIER.matches(model.className)) error("RULE_CLASS_INVALID", "Class name is not valid.")
        if (!IDENTIFIER.matches(model.methodName)) error("RULE_METHOD_INVALID", "Method name is not valid.")
        if (model.beanName.isBlank() || model.beanName.length > 180 || model.beanName.any(Char::isISOControl)) {
            error("RULE_BEAN_INVALID", "Spring bean name must be a visible value of at most 180 characters.")
        }
        if (model.destinationId.isBlank()) error("RULE_DESTINATION_REQUIRED", "Select a production Java destination.")
        if (!isSafeJavaType(model.outputJavaType)) error("RULE_OUTPUT_TYPE_INVALID", "Output Java type is unsafe.")
        if (model.parameters.size > MAX_PARAMETERS) error("RULE_PARAMETER_LIMIT", "A rule supports at most $MAX_PARAMETERS parameters.")
        if (model.decimalScale !in 0..18) error("RULE_DECIMAL_SCALE_INVALID", "Decimal scale must be between 0 and 18.")
        if (model.roundingMode !in ROUNDING_MODES) error("RULE_ROUNDING_MODE_INVALID", "Rounding mode is not supported.")

        val parameters = linkedMapOf<String, RuleDataType>()
        model.parameters.forEach { parameter ->
            if (!IDENTIFIER.matches(parameter.name)) {
                error("RULE_PARAMETER_INVALID", "Parameter '${parameter.name}' is not a valid Java identifier.")
            }
            if (!isSafeJavaType(parameter.javaType)) {
                error("RULE_PARAMETER_TYPE_INVALID", "Parameter '${parameter.name}' has an unsafe Java type.")
            }
            if (parameters.putIfAbsent(parameter.name, parameter.dataType) != null) {
                error("RULE_PARAMETER_DUPLICATE", "Parameter is duplicated: ${parameter.name}.")
            }
            if (!parameter.nullable && parameter.dataType == RuleDataType.OBJECT) {
                warning(
                    "RULE_OBJECT_PARAMETER",
                    "Object parameter '${parameter.name}' cannot receive strong visual type checking.",
                )
            }
        }
        when (model.kind) {
            VisualRuleKind.FORMULA -> Unit
            VisualRuleKind.PREDICATE -> {
                if (model.expression.dataType != RuleDataType.BOOLEAN) {
                    error("RULE_PREDICATE_BOOLEAN", "Predicate root expression must be BOOLEAN.", model.expression.id)
                }
                if (model.outputJavaType !in setOf("boolean", "Boolean", "java.lang.Boolean")) {
                    error("RULE_PREDICATE_OUTPUT", "Predicate output Java type must be boolean.")
                }
            }
            VisualRuleKind.VALIDATOR -> {
                if (model.parameters.size != 1) {
                    error("RULE_VALIDATOR_PARAMETER", "A FlowUI validator requires exactly one input parameter.")
                }
                if (model.expression.dataType != RuleDataType.BOOLEAN) {
                    error("RULE_VALIDATOR_BOOLEAN", "Validator expression must evaluate to BOOLEAN.", model.expression.id)
                }
                if (model.validationMessage.isNullOrBlank()) {
                    error("RULE_VALIDATOR_MESSAGE", "Validator requires a failure message.")
                }
            }
        }
        val expectedRootType = dataTypeForJavaType(model.outputJavaType)
        if (model.kind == VisualRuleKind.FORMULA &&
            expectedRootType != null &&
            expectedRootType != model.expression.dataType
        ) {
            error(
                "RULE_OUTPUT_MISMATCH",
                "Expression produces ${model.expression.dataType}, but output type is ${model.outputJavaType}.",
                model.expression.id,
            )
        }

        val ids = linkedSetOf<String>()
        var expressionCount = 0
        fun visit(expression: RuleExpressionModel, depth: Int) {
            expressionCount += 1
            if (expressionCount > MAX_EXPRESSIONS) return
            if (depth > MAX_DEPTH) {
                error("RULE_EXPRESSION_DEPTH", "Expression nesting exceeds $MAX_DEPTH levels.", expression.id)
                return
            }
            if (expression.id.isBlank() || expression.id.length > 160 || expression.id.any(Char::isISOControl)) {
                error("RULE_EXPRESSION_ID_INVALID", "Expression id must be visible and at most 160 characters.", expression.id)
            } else if (!ids.add(expression.id)) {
                error("RULE_EXPRESSION_ID_DUPLICATE", "Expression id is duplicated: ${expression.id}.", expression.id)
            }
            validateExpression(expression, parameters, ::error)
            expression.children.forEach { visit(it, depth + 1) }
        }
        visit(model.expression, 1)
        if (expressionCount > MAX_EXPRESSIONS) {
            error("RULE_EXPRESSION_LIMIT", "Rule exceeds the $MAX_EXPRESSIONS-expression safety limit.")
        }

        return VisualRuleValidationResult(
            valid = diagnostics.none { it.severity == RuleDiagnosticSeverity.ERROR },
            diagnostics = diagnostics.sortedWith(
                compareBy(RuleDiagnostic::severity, RuleDiagnostic::expressionId, RuleDiagnostic::code),
            ),
        )
    }

    private fun validateExpression(
        expression: RuleExpressionModel,
        parameters: Map<String, RuleDataType>,
        error: (String, String, String?) -> Unit,
    ) {
        fun fail(code: String, message: String) = error(code, message, expression.id)
        fun arity(exact: Int) {
            if (expression.children.size != exact) {
                fail("RULE_ARITY", "${expression.kind} requires exactly $exact child expression(s).")
            }
        }
        fun atLeast(minimum: Int) {
            if (expression.children.size < minimum) {
                fail("RULE_ARITY", "${expression.kind} requires at least $minimum child expression(s).")
            }
        }
        fun childrenType(type: RuleDataType) {
            expression.children.filter { it.dataType != type }.forEach {
                fail("RULE_CHILD_TYPE", "${expression.kind} requires $type children; '${it.label}' is ${it.dataType}.")
            }
        }

        when (expression.kind) {
            RuleExpressionKind.VALUE -> {
                if (expression.children.isNotEmpty()) fail("RULE_VALUE_CHILDREN", "Value expression cannot have children.")
                when (expression.valueSource) {
                    RuleValueSource.LITERAL -> validateLiteral(expression)?.let {
                        fail("RULE_LITERAL_INVALID", it)
                    }
                    RuleValueSource.PARAMETER -> {
                        val parameterType = parameters[expression.parameterName]
                        if (parameterType == null) {
                            fail("RULE_PARAMETER_MISSING", "Unknown parameter: ${expression.parameterName}.")
                        } else if (parameterType != expression.dataType && expression.dataType != RuleDataType.OBJECT) {
                            fail(
                                "RULE_PARAMETER_TYPE_MISMATCH",
                                "Parameter '${expression.parameterName}' is $parameterType, not ${expression.dataType}.",
                            )
                        }
                    }
                    RuleValueSource.NULL -> Unit
                    null -> fail("RULE_VALUE_SOURCE_REQUIRED", "Value expression requires a source.")
                }
            }
            RuleExpressionKind.PROPERTY -> {
                if (expression.children.isNotEmpty()) fail("RULE_PROPERTY_CHILDREN", "Property expression cannot have children.")
                val parameterType = parameters[expression.parameterName]
                if (parameterType !in setOf(RuleDataType.ENTITY, RuleDataType.OBJECT)) {
                    fail("RULE_PROPERTY_ROOT", "Property root must be an ENTITY or OBJECT parameter.")
                }
                if (!PROPERTY_PATH.matches(expression.propertyPath.orEmpty())) {
                    fail("RULE_PROPERTY_PATH", "Property path must contain Java-style attribute names separated by dots.")
                }
                if (expression.dataType in setOf(RuleDataType.ENTITY, RuleDataType.ENUM, RuleDataType.OBJECT) &&
                    !isSafeJavaType(expression.javaType.orEmpty())
                ) {
                    fail("RULE_PROPERTY_JAVA_TYPE", "Entity, enum, or object property requires a safe Java type.")
                }
            }
            RuleExpressionKind.ADD,
            RuleExpressionKind.SUBTRACT,
            RuleExpressionKind.MULTIPLY,
            RuleExpressionKind.DIVIDE,
            RuleExpressionKind.MIN,
            RuleExpressionKind.MAX,
            -> {
                arity(2)
                if (expression.dataType != RuleDataType.DECIMAL) {
                    fail("RULE_DECIMAL_RESULT", "${expression.kind} must produce DECIMAL.")
                }
                childrenType(RuleDataType.DECIMAL)
            }
            RuleExpressionKind.NEGATE,
            RuleExpressionKind.ABS,
            RuleExpressionKind.ROUND,
            -> {
                arity(1)
                if (expression.dataType != RuleDataType.DECIMAL) {
                    fail("RULE_DECIMAL_RESULT", "${expression.kind} must produce DECIMAL.")
                }
                childrenType(RuleDataType.DECIMAL)
            }
            RuleExpressionKind.EQUALS,
            RuleExpressionKind.NOT_EQUALS,
            -> {
                arity(2)
                if (expression.dataType != RuleDataType.BOOLEAN) fail("RULE_BOOLEAN_RESULT", "${expression.kind} must produce BOOLEAN.")
            }
            RuleExpressionKind.GREATER_THAN,
            RuleExpressionKind.GREATER_THAN_OR_EQUAL,
            RuleExpressionKind.LESS_THAN,
            RuleExpressionKind.LESS_THAN_OR_EQUAL,
            -> {
                arity(2)
                if (expression.dataType != RuleDataType.BOOLEAN) fail("RULE_BOOLEAN_RESULT", "${expression.kind} must produce BOOLEAN.")
                if (expression.children.size == 2 && expression.children[0].dataType != expression.children[1].dataType) {
                    fail("RULE_COMPARISON_TYPE", "Comparison operands must have the same type.")
                }
            }
            RuleExpressionKind.AND,
            RuleExpressionKind.OR,
            -> {
                atLeast(2)
                childrenType(RuleDataType.BOOLEAN)
                if (expression.dataType != RuleDataType.BOOLEAN) fail("RULE_BOOLEAN_RESULT", "${expression.kind} must produce BOOLEAN.")
            }
            RuleExpressionKind.NOT -> {
                arity(1)
                childrenType(RuleDataType.BOOLEAN)
                if (expression.dataType != RuleDataType.BOOLEAN) fail("RULE_BOOLEAN_RESULT", "NOT must produce BOOLEAN.")
            }
            RuleExpressionKind.IF -> {
                arity(3)
                if (expression.children.firstOrNull()?.dataType != RuleDataType.BOOLEAN) {
                    fail("RULE_IF_CONDITION", "IF first child must be BOOLEAN.")
                }
                if (expression.children.size == 3 &&
                    (expression.children[1].dataType != expression.dataType ||
                        expression.children[2].dataType != expression.dataType)
                ) {
                    fail("RULE_IF_BRANCH_TYPE", "IF result branches must match ${expression.dataType}.")
                }
            }
            RuleExpressionKind.COALESCE -> {
                atLeast(2)
                childrenType(expression.dataType)
            }
            RuleExpressionKind.CONCAT -> {
                atLeast(2)
                if (expression.dataType != RuleDataType.STRING) fail("RULE_STRING_RESULT", "CONCAT must produce STRING.")
            }
            RuleExpressionKind.UPPER,
            RuleExpressionKind.LOWER,
            RuleExpressionKind.TRIM,
            -> {
                arity(1)
                childrenType(RuleDataType.STRING)
                if (expression.dataType != RuleDataType.STRING) fail("RULE_STRING_RESULT", "${expression.kind} must produce STRING.")
            }
            RuleExpressionKind.LENGTH -> {
                arity(1)
                childrenType(RuleDataType.STRING)
                if (expression.dataType != RuleDataType.INTEGER) fail("RULE_INTEGER_RESULT", "LENGTH must produce INTEGER.")
            }
            RuleExpressionKind.IS_NULL,
            RuleExpressionKind.IS_NOT_NULL,
            -> {
                arity(1)
                if (expression.dataType != RuleDataType.BOOLEAN) fail("RULE_BOOLEAN_RESULT", "${expression.kind} must produce BOOLEAN.")
            }
            RuleExpressionKind.DATE_PLUS_DAYS -> {
                arity(2)
                if (expression.children.firstOrNull()?.dataType != RuleDataType.LOCAL_DATE ||
                    expression.children.getOrNull(1)?.dataType !in setOf(RuleDataType.INTEGER, RuleDataType.LONG)
                ) {
                    fail("RULE_DATE_PLUS_TYPE", "DATE_PLUS_DAYS requires LOCAL_DATE and INTEGER/LONG children.")
                }
                if (expression.dataType != RuleDataType.LOCAL_DATE) fail("RULE_DATE_RESULT", "DATE_PLUS_DAYS must produce LOCAL_DATE.")
            }
            RuleExpressionKind.DAYS_BETWEEN -> {
                arity(2)
                childrenType(RuleDataType.LOCAL_DATE)
                if (expression.dataType != RuleDataType.LONG) fail("RULE_LONG_RESULT", "DAYS_BETWEEN must produce LONG.")
            }
            RuleExpressionKind.IN_LIST -> {
                atLeast(2)
                if (expression.dataType != RuleDataType.BOOLEAN) fail("RULE_BOOLEAN_RESULT", "IN_LIST must produce BOOLEAN.")
                val candidateType = expression.children.firstOrNull()?.dataType
                if (candidateType != null && expression.children.any { it.dataType != candidateType }) {
                    fail("RULE_LIST_TYPE", "IN_LIST candidate and options must have the same type.")
                }
            }
        }
    }

    fun generate(model: VisualRuleModel, encodedModel: String): String {
        val validation = validate(model)
        require(validation.valid) {
            validation.diagnostics.filter { it.severity == RuleDiagnosticSeverity.ERROR }
                .joinToString("; ") { "${it.code}: ${it.message}" }
        }
        val validator = model.kind == VisualRuleKind.VALIDATOR
        return buildString {
            append("package ").append(model.packageName).append(";\n\n")
            append("import io.jmix.core.entity.EntityValues;\n")
            if (validator) {
                append("import io.jmix.flowui.component.validation.ValidationException;\n")
                append("import io.jmix.flowui.component.validation.Validator;\n")
            }
            append("import org.springframework.stereotype.Component;\n\n")
            append("import java.math.BigDecimal;\n")
            append("import java.math.RoundingMode;\n")
            append("import java.time.Instant;\n")
            append("import java.time.LocalDate;\n")
            append("import java.time.LocalDateTime;\n")
            append("import java.time.OffsetDateTime;\n")
            append("import java.time.temporal.ChronoUnit;\n")
            append("import java.util.Objects;\n")
            append("import java.util.UUID;\n\n")
            if (encodedModel.isNotBlank()) append(MARKER_PREFIX).append(encodedModel).append('\n')
            append("/**\n")
            append(" * ").append(safeComment(model.name)).append('\n')
            model.description.takeIf(String::isNotBlank)?.let {
                append(" * ").append(safeComment(it)).append('\n')
            }
            append(" * Pure typed rule generated by Jmix Visual Workbench.\n")
            append(" */\n")
            append("@Component(").append(javaString(model.beanName)).append(")\n")
            append("public class ").append(model.className)
            if (validator) append(" implements Validator<").append(model.parameters.single().javaType).append('>')
            append(" {\n\n")
            append("    private static final int DECIMAL_SCALE = ").append(model.decimalScale).append(";\n")
            append("    private static final RoundingMode ROUNDING_MODE = RoundingMode.")
                .append(model.roundingMode)
                .append(";\n\n")
            if (validator) {
                val parameter = model.parameters.single()
                append("    @Override\n")
                append("    public void accept(").append(parameter.javaType).append(' ').append(parameter.name).append(") {\n")
                if (!parameter.nullable) {
                    append("        Objects.requireNonNull(").append(parameter.name).append(", ")
                        .append(javaString("${parameter.name} is required."))
                        .append(");\n")
                }
                append("        if (!(").append(render(model.expression)).append(")) {\n")
                append("            throw new ValidationException(")
                    .append(javaString(requireNotNull(model.validationMessage)))
                    .append(");\n")
                append("        }\n")
                append("    }\n")
            } else {
                append("    public ").append(model.outputJavaType).append(' ').append(model.methodName).append('(')
                append(model.parameters.joinToString { "${it.javaType} ${it.name}" })
                append(") {\n")
                model.parameters.filterNot { it.nullable }.forEach { parameter ->
                    if (parameter.javaType !in PRIMITIVE_TYPES) {
                        append("        Objects.requireNonNull(").append(parameter.name).append(", ")
                            .append(javaString("${parameter.name} is required."))
                            .append(");\n")
                    }
                }
                append("        return ").append(render(model.expression)).append(";\n")
                append("    }\n")
            }
            append(HELPERS)
            append("}\n")
        }
    }

    private fun render(expression: RuleExpressionModel): String {
        val children = expression.children.map(::render)
        return when (expression.kind) {
            RuleExpressionKind.VALUE -> renderValue(expression)
            RuleExpressionKind.PROPERTY -> {
                val raw = "EntityValues.getValue(${expression.parameterName}, ${javaString(expression.propertyPath.orEmpty())})"
                castValue(raw, expression)
            }
            RuleExpressionKind.ADD -> "decimal(${children[0]}).add(decimal(${children[1]}))"
            RuleExpressionKind.SUBTRACT -> "decimal(${children[0]}).subtract(decimal(${children[1]}))"
            RuleExpressionKind.MULTIPLY -> "decimal(${children[0]}).multiply(decimal(${children[1]}))"
            RuleExpressionKind.DIVIDE -> "divide(decimal(${children[0]}), decimal(${children[1]}))"
            RuleExpressionKind.NEGATE -> "decimal(${children[0]}).negate()"
            RuleExpressionKind.ABS -> "decimal(${children[0]}).abs()"
            RuleExpressionKind.ROUND -> "decimal(${children[0]}).setScale(DECIMAL_SCALE, ROUNDING_MODE)"
            RuleExpressionKind.MIN -> "decimal(${children[0]}).min(decimal(${children[1]}))"
            RuleExpressionKind.MAX -> "decimal(${children[0]}).max(decimal(${children[1]}))"
            RuleExpressionKind.EQUALS -> "Objects.equals(${children[0]}, ${children[1]})"
            RuleExpressionKind.NOT_EQUALS -> "!Objects.equals(${children[0]}, ${children[1]})"
            RuleExpressionKind.GREATER_THAN -> "compareValues(${children[0]}, ${children[1]}) > 0"
            RuleExpressionKind.GREATER_THAN_OR_EQUAL -> "compareValues(${children[0]}, ${children[1]}) >= 0"
            RuleExpressionKind.LESS_THAN -> "compareValues(${children[0]}, ${children[1]}) < 0"
            RuleExpressionKind.LESS_THAN_OR_EQUAL -> "compareValues(${children[0]}, ${children[1]}) <= 0"
            RuleExpressionKind.AND -> children.joinToString(" && ", "(", ")") { "truth($it)" }
            RuleExpressionKind.OR -> children.joinToString(" || ", "(", ")") { "truth($it)" }
            RuleExpressionKind.NOT -> "!truth(${children[0]})"
            RuleExpressionKind.IF -> "(truth(${children[0]}) ? ${children[1]} : ${children[2]})"
            RuleExpressionKind.COALESCE -> "coalesce(${children.joinToString()})"
            RuleExpressionKind.CONCAT -> children.joinToString(", ", "concat(", ")")
            RuleExpressionKind.UPPER -> "text(${children[0]}).toUpperCase(java.util.Locale.ROOT)"
            RuleExpressionKind.LOWER -> "text(${children[0]}).toLowerCase(java.util.Locale.ROOT)"
            RuleExpressionKind.TRIM -> "text(${children[0]}).trim()"
            RuleExpressionKind.LENGTH -> "text(${children[0]}).length()"
            RuleExpressionKind.IS_NULL -> "${children[0]} == null"
            RuleExpressionKind.IS_NOT_NULL -> "${children[0]} != null"
            RuleExpressionKind.DATE_PLUS_DAYS -> "date(${children[0]}).plusDays(number(${children[1]}).longValue())"
            RuleExpressionKind.DAYS_BETWEEN -> "ChronoUnit.DAYS.between(date(${children[0]}), date(${children[1]}))"
            RuleExpressionKind.IN_LIST -> "inList(${children.joinToString()})"
        }
    }

    private fun renderValue(expression: RuleExpressionModel): String =
        when (expression.valueSource) {
            RuleValueSource.PARAMETER -> requireNotNull(expression.parameterName)
            RuleValueSource.NULL -> "null"
            RuleValueSource.LITERAL -> when (expression.dataType) {
                RuleDataType.STRING -> javaString(expression.value.orEmpty())
                RuleDataType.INTEGER -> expression.value.orEmpty()
                RuleDataType.LONG -> "${expression.value}L"
                RuleDataType.DECIMAL -> "new BigDecimal(${javaString(expression.value.orEmpty())})"
                RuleDataType.BOOLEAN -> expression.value.orEmpty()
                RuleDataType.UUID -> "UUID.fromString(${javaString(expression.value.orEmpty())})"
                RuleDataType.LOCAL_DATE -> "LocalDate.parse(${javaString(expression.value.orEmpty())})"
                RuleDataType.LOCAL_DATE_TIME -> "LocalDateTime.parse(${javaString(expression.value.orEmpty())})"
                RuleDataType.OFFSET_DATE_TIME -> "OffsetDateTime.parse(${javaString(expression.value.orEmpty())})"
                RuleDataType.INSTANT -> "Instant.parse(${javaString(expression.value.orEmpty())})"
                RuleDataType.ENUM -> "${expression.javaType}.${expression.value}"
                RuleDataType.ENTITY,
                RuleDataType.OBJECT,
                -> error("Entity/object literals are not supported.")
            }
            null -> error("Value source is required.")
        }

    private fun castValue(raw: String, expression: RuleExpressionModel): String =
        when (expression.dataType) {
            RuleDataType.STRING -> "(String) $raw"
            RuleDataType.INTEGER -> "(Integer) $raw"
            RuleDataType.LONG -> "(Long) $raw"
            RuleDataType.DECIMAL -> "(BigDecimal) $raw"
            RuleDataType.BOOLEAN -> "(Boolean) $raw"
            RuleDataType.UUID -> "(UUID) $raw"
            RuleDataType.LOCAL_DATE -> "(LocalDate) $raw"
            RuleDataType.LOCAL_DATE_TIME -> "(LocalDateTime) $raw"
            RuleDataType.OFFSET_DATE_TIME -> "(OffsetDateTime) $raw"
            RuleDataType.INSTANT -> "(Instant) $raw"
            RuleDataType.ENUM,
            RuleDataType.ENTITY,
            RuleDataType.OBJECT,
            -> "(${expression.javaType}) $raw"
        }

    private fun validateLiteral(expression: RuleExpressionModel): String? {
        val raw = expression.value.orEmpty()
        return runCatching {
            when (expression.dataType) {
                RuleDataType.STRING -> Unit
                RuleDataType.INTEGER -> raw.toInt()
                RuleDataType.LONG -> raw.toLong()
                RuleDataType.DECIMAL -> BigDecimal(raw)
                RuleDataType.BOOLEAN -> require(raw == "true" || raw == "false")
                RuleDataType.UUID -> UUID.fromString(raw)
                RuleDataType.LOCAL_DATE -> LocalDate.parse(raw)
                RuleDataType.LOCAL_DATE_TIME -> LocalDateTime.parse(raw)
                RuleDataType.OFFSET_DATE_TIME -> OffsetDateTime.parse(raw)
                RuleDataType.INSTANT -> Instant.parse(raw)
                RuleDataType.ENUM -> {
                    require(isSafeJavaType(expression.javaType.orEmpty()))
                    require(ENUM_CONSTANT.matches(raw))
                }
                RuleDataType.ENTITY,
                RuleDataType.OBJECT,
                -> error("Entity/object literals are not supported.")
            }
        }.exceptionOrNull()?.let { "Literal '$raw' is invalid for ${expression.dataType}." }
    }

    private fun dataTypeForJavaType(javaType: String): RuleDataType? =
        when (javaType.removePrefix("java.lang.")) {
            "String" -> RuleDataType.STRING
            "int", "Integer" -> RuleDataType.INTEGER
            "long", "Long" -> RuleDataType.LONG
            "boolean", "Boolean" -> RuleDataType.BOOLEAN
            "java.math.BigDecimal", "BigDecimal" -> RuleDataType.DECIMAL
            "java.util.UUID", "UUID" -> RuleDataType.UUID
            "java.time.LocalDate", "LocalDate" -> RuleDataType.LOCAL_DATE
            "java.time.LocalDateTime", "LocalDateTime" -> RuleDataType.LOCAL_DATE_TIME
            "java.time.OffsetDateTime", "OffsetDateTime" -> RuleDataType.OFFSET_DATE_TIME
            "java.time.Instant", "Instant" -> RuleDataType.INSTANT
            else -> null
        }

    private fun isSafeJavaType(value: String): Boolean =
        value.length in 1..300 &&
            JAVA_TYPE.matches(value.trim()) &&
            !value.contains("..") &&
            value.none { it in setOf(';', '{', '}', '(', ')', '"', '\'', '\\', '\n', '\r') }

    private fun javaString(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.isISOControl()) {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }

    private fun safeComment(value: String): String =
        value.replace("*/", "* /").replace(Regex("""[\r\n]+"""), " ").trim()

    private val PRIMITIVE_TYPES = setOf(
        "boolean", "byte", "short", "int", "long", "float", "double", "char",
    )

    private val HELPERS = """

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        throw new IllegalArgumentException("Expected a decimal value.");
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("Expected a numeric value.");
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate date) return date;
        throw new IllegalArgumentException("Expected a local date.");
    }

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static String text(Object value) {
        return Objects.toString(value, "");
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right) {
        if (right.signum() == 0) throw new ArithmeticException("Division by zero.");
        return left.divide(right, DECIMAL_SCALE, ROUNDING_MODE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compareValues(Object left, Object right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("Cannot order null values.");
        }
        if (left instanceof Number && right instanceof Number) {
            return decimal(left).compareTo(decimal(right));
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        throw new IllegalArgumentException("Values are not mutually comparable.");
    }

    @SafeVarargs
    private static <T> T coalesce(T... values) {
        for (T value : values) if (value != null) return value;
        return null;
    }

    private static String concat(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) if (value != null) result.append(value);
        return result.toString();
    }

    private static boolean inList(Object candidate, Object... options) {
        for (Object option : options) if (Objects.equals(candidate, option)) return true;
        return false;
    }
"""
}

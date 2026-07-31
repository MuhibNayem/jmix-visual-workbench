package org.jmixworkbench.generator

import org.jmixworkbench.model.DmnCollectOperator
import org.jmixworkbench.model.DmnConditionModel
import org.jmixworkbench.model.DmnConditionOperator
import org.jmixworkbench.model.DmnDecisionModel
import org.jmixworkbench.model.DmnDecisionRuleModel
import org.jmixworkbench.model.DmnDiagnostic
import org.jmixworkbench.model.DmnDiagnosticSeverity
import org.jmixworkbench.model.DmnHitPolicy
import org.jmixworkbench.model.DmnSimulationResult
import org.jmixworkbench.model.DmnValidationResult
import org.jmixworkbench.model.DmnValueType
import java.math.BigDecimal
import java.time.LocalDate

object DmnDecisionGenerator {
    private const val MARKER_PREFIX = "<!-- JVW-DMN-MODEL: "
    private const val MAX_COLUMNS = 50
    private const val MAX_RULES = 1_000
    private const val MAX_VALUE_LENGTH = 1_000
    private val KEY = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")
    private val VARIABLE = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val FILE_NAME = Regex("""[A-Za-z0-9_.-]+\.dmn(?:\.xml)?""")

    fun markerPrefix(): String = MARKER_PREFIX

    fun validate(model: DmnDecisionModel): DmnValidationResult {
        val diagnostics = mutableListOf<DmnDiagnostic>()
        fun report(
            code: String,
            severity: DmnDiagnosticSeverity,
            message: String,
            ruleIds: List<String> = emptyList(),
            columnId: String? = null,
        ) {
            diagnostics += DmnDiagnostic(code, severity, message, ruleIds, columnId)
        }
        fun error(code: String, message: String, ruleIds: List<String> = emptyList(), columnId: String? = null) =
            report(code, DmnDiagnosticSeverity.ERROR, message, ruleIds, columnId)
        fun warning(code: String, message: String, ruleIds: List<String> = emptyList(), columnId: String? = null) =
            report(code, DmnDiagnosticSeverity.WARNING, message, ruleIds, columnId)

        if (model.name.isBlank() || model.name.length > 240 || model.name.any(Char::isISOControl)) {
            error("DMN_NAME_INVALID", "Decision name must be visible and at most 240 characters.")
        }
        if (!KEY.matches(model.key)) {
            error("DMN_KEY_INVALID", "Decision key must be a stable XML identifier.")
        }
        if (!FILE_NAME.matches(model.fileName) || '/' in model.fileName || '\\' in model.fileName) {
            error("DMN_FILE_INVALID", "DMN file name must be a simple .dmn or .dmn.xml name.")
        }
        if (model.destinationId.isBlank()) error("DMN_DESTINATION_REQUIRED", "Select a production resource destination.")
        if (!model.namespace.startsWith("http://") && !model.namespace.startsWith("https://")) {
            error("DMN_NAMESPACE_INVALID", "DMN namespace must be an HTTP(S) URI.")
        }
        if (model.inputs.isEmpty() || model.inputs.size > MAX_COLUMNS) {
            error("DMN_INPUT_COUNT", "A decision table requires 1–$MAX_COLUMNS input columns.")
        }
        if (model.outputs.isEmpty() || model.outputs.size > MAX_COLUMNS) {
            error("DMN_OUTPUT_COUNT", "A decision table requires 1–$MAX_COLUMNS output columns.")
        }
        if (model.rules.isEmpty() || model.rules.size > MAX_RULES) {
            error("DMN_RULE_COUNT", "A decision table requires 1–$MAX_RULES rules.")
        }
        if (model.authoringVersion < 1) {
            error("DMN_AUTHORING_VERSION", "Authoring version must be at least 1.")
        }
        val effectiveFrom = parseDate(model.effectiveFrom, "effective-from", ::error)
        val effectiveTo = parseDate(model.effectiveTo, "effective-to", ::error)
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            error("DMN_EFFECTIVE_RANGE", "Effective-to date cannot be before effective-from date.")
        }

        val ids = linkedSetOf<String>()
        val variables = linkedSetOf<String>()
        val columns = model.inputs.map { Triple(it.id, it.label, it.variable) } +
            model.outputs.map { Triple(it.id, it.label, it.variable) }
        columns.forEach { (id, label, variable) ->
            if (!KEY.matches(id) || !ids.add(id)) {
                error("DMN_COLUMN_ID", "Column IDs must be unique XML identifiers.", columnId = id)
            }
            if (!VARIABLE.matches(variable) || !variables.add(variable)) {
                error("DMN_COLUMN_VARIABLE", "Input and output variable names must be unique identifiers.", columnId = id)
            }
            if (label.isBlank() || label.length > 160 || label.any(Char::isISOControl)) {
                error("DMN_COLUMN_LABEL", "Column labels must be visible and at most 160 characters.", columnId = id)
            }
        }
        model.outputs.forEach { output ->
            val duplicates = output.predefinedValues.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) {
                error(
                    "DMN_OUTPUT_PRIORITY_DUPLICATE",
                    "Predefined output priorities contain duplicate values: ${duplicates.joinToString()}.",
                    columnId = output.id,
                )
            }
            output.predefinedValues.forEach { value ->
                validateLiteral(value, output.type)?.let {
                    error("DMN_OUTPUT_PRIORITY_LITERAL", it, columnId = output.id)
                }
            }
        }

        val ruleIds = linkedSetOf<String>()
        val inputIds = model.inputs.map { it.id }.toSet()
        val outputIds = model.outputs.map { it.id }.toSet()
        model.rules.forEach { rule ->
            if (!KEY.matches(rule.id) || !ruleIds.add(rule.id)) {
                error("DMN_RULE_ID", "Rule IDs must be unique XML identifiers.", listOf(rule.id))
            }
            val missingInputs = inputIds - rule.inputEntries.keys
            val unknownInputs = rule.inputEntries.keys - inputIds
            val missingOutputs = outputIds - rule.outputEntries.keys
            val unknownOutputs = rule.outputEntries.keys - outputIds
            if (missingInputs.isNotEmpty() || unknownInputs.isNotEmpty()) {
                error(
                    "DMN_RULE_INPUT_SHAPE",
                    "Rule input cells do not match the table columns.",
                    listOf(rule.id),
                )
            }
            if (missingOutputs.isNotEmpty() || unknownOutputs.isNotEmpty()) {
                error(
                    "DMN_RULE_OUTPUT_SHAPE",
                    "Rule output cells do not match the table columns.",
                    listOf(rule.id),
                )
            }
            model.inputs.forEach { input ->
                rule.inputEntries[input.id]?.let { condition ->
                    validateCondition(condition, input.type)?.let {
                        error("DMN_CONDITION_INVALID", it, listOf(rule.id), input.id)
                    }
                }
            }
            model.outputs.forEach { output ->
                rule.outputEntries[output.id]?.let { value ->
                    validateLiteral(value, output.type)?.let {
                        error("DMN_OUTPUT_LITERAL_INVALID", it, listOf(rule.id), output.id)
                    }
                }
            }
        }

        if (model.hitPolicy != DmnHitPolicy.COLLECT && model.collectOperator != DmnCollectOperator.NONE) {
            error("DMN_COLLECT_OPERATOR_POLICY", "Aggregation can only be used with the COLLECT hit policy.")
        }
        if (model.hitPolicy == DmnHitPolicy.COLLECT &&
            model.collectOperator != DmnCollectOperator.NONE &&
            model.outputs.size != 1
        ) {
            error("DMN_COLLECT_OUTPUT_COUNT", "Aggregated COLLECT requires exactly one output column.")
        }
        if (model.hitPolicy == DmnHitPolicy.COLLECT &&
            model.collectOperator in setOf(DmnCollectOperator.SUM, DmnCollectOperator.MIN, DmnCollectOperator.MAX) &&
            model.outputs.singleOrNull()?.type != DmnValueType.NUMBER
        ) {
            error("DMN_COLLECT_NUMERIC", "SUM, MIN, and MAX aggregation require one NUMBER output.")
        }
        if (model.hitPolicy in setOf(DmnHitPolicy.PRIORITY, DmnHitPolicy.OUTPUT_ORDER)) {
            model.outputs.forEach { output ->
                if (output.predefinedValues.isEmpty()) {
                    error(
                        "DMN_PRIORITY_VALUES_REQUIRED",
                        "${model.hitPolicy} requires ordered predefined values for every output.",
                        columnId = output.id,
                    )
                }
                model.rules.filter(DmnDecisionRuleModel::enabled).forEach { rule ->
                    val value = rule.outputEntries[output.id]
                    if (value != null && value !in output.predefinedValues) {
                        error(
                            "DMN_PRIORITY_VALUE_UNKNOWN",
                            "Rule output '$value' is absent from the ordered priorities.",
                            listOf(rule.id),
                            output.id,
                        )
                    }
                }
            }
        }

        val enabledRules = model.rules.filter(DmnDecisionRuleModel::enabled)
        enabledRules.forEachIndexed { firstIndex, first ->
            enabledRules.drop(firstIndex + 1).forEach { second ->
                if (!rulesOverlap(first, second, model)) return@forEach
                when (model.hitPolicy) {
                    DmnHitPolicy.UNIQUE -> error(
                        "DMN_UNIQUE_OVERLAP",
                        "UNIQUE hit policy is violated because two rules can match the same input.",
                        listOf(first.id, second.id),
                    )
                    DmnHitPolicy.ANY -> if (first.outputEntries != second.outputEntries) {
                        error(
                            "DMN_ANY_OUTPUT_CONFLICT",
                            "ANY allows overlapping rules only when every output is identical.",
                            listOf(first.id, second.id),
                        )
                    }
                    DmnHitPolicy.FIRST -> warning(
                        "DMN_FIRST_OVERLAP",
                        "Rules overlap; FIRST will select '${first.id}' before '${second.id}'.",
                        listOf(first.id, second.id),
                    )
                    else -> Unit
                }
            }
        }
        enabledRules.forEachIndexed { index, rule ->
            if (index > 0 && enabledRules.take(index).any { earlier ->
                    model.inputs.all { input ->
                        earlier.inputEntries[input.id]?.operator == DmnConditionOperator.ANY
                    }
                }
            ) {
                warning(
                    "DMN_RULE_POSSIBLY_SHADOWED",
                    "Rule '${rule.id}' may be shadowed by earlier catch-all rules.",
                    listOf(rule.id),
                )
            }
        }
        if (enabledRules.isEmpty()) error("DMN_NO_ENABLED_RULES", "At least one rule must be enabled.")

        return DmnValidationResult(
            valid = diagnostics.none { it.severity == DmnDiagnosticSeverity.ERROR },
            diagnostics = diagnostics.distinct().sortedWith(
                compareBy(DmnDiagnostic::severity, { it.ruleIds.joinToString() }, DmnDiagnostic::code),
            ),
        )
    }

    fun generate(model: DmnDecisionModel, encodedModel: String): String {
        val validation = validate(model)
        require(validation.valid) {
            validation.diagnostics.filter { it.severity == DmnDiagnosticSeverity.ERROR }
                .joinToString("; ") { "${it.code}: ${it.message}" }
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(MARKER_PREFIX).append(encodedModel).append(" -->\n")
            append("<definitions xmlns=\"http://www.omg.org/spec/DMN/20151101\"")
            append(" id=\"").append(xml("${model.key}Definitions")).append('"')
            append(" namespace=\"").append(xml(model.namespace)).append('"')
            append(" name=\"").append(xml(model.name)).append('"')
            append(" exporter=\"Jmix Visual Workbench\" exporterVersion=\"1.0\">\n")
            append("  <decision id=\"").append(xml(model.key)).append("\" name=\"").append(xml(model.name)).append("\">\n")
            if (model.description.isNotBlank()) {
                append("    <description>").append(xml(model.description)).append("</description>\n")
            }
            append("    <decisionTable id=\"").append(xml("${model.key}Table")).append('"')
            append(" hitPolicy=\"").append(model.hitPolicy.name).append('"')
            model.collectOperator.xmlValue?.let { append(" aggregation=\"").append(it).append('"') }
            append(">\n")
            model.inputs.forEach { input ->
                append("      <input id=\"").append(xml(input.id)).append("\" label=\"").append(xml(input.label)).append("\">\n")
                append("        <inputExpression id=\"").append(xml("${input.id}Expression")).append("\" typeRef=\"")
                    .append(input.type.xmlType).append("\">\n")
                append("          <text><![CDATA[").append(input.variable).append("]]></text>\n")
                append("        </inputExpression>\n")
                append("      </input>\n")
            }
            model.outputs.forEach { output ->
                append("      <output id=\"").append(xml(output.id)).append("\" label=\"").append(xml(output.label))
                    .append("\" name=\"").append(xml(output.variable)).append("\" typeRef=\"").append(output.type.xmlType).append("\">\n")
                if (output.predefinedValues.isNotEmpty()) {
                    append("        <outputValues><text>")
                    append(output.predefinedValues.joinToString(",") { xml(outputLiteral(it, output.type)) })
                    append("</text></outputValues>\n")
                }
                append("      </output>\n")
            }
            model.rules.filter(DmnDecisionRuleModel::enabled).forEach { rule ->
                append("      <rule id=\"").append(xml(rule.id)).append("\">\n")
                if (rule.description.isNotBlank()) {
                    append("        <description>").append(xml(rule.description)).append("</description>\n")
                }
                model.inputs.forEach { input ->
                    val condition = requireNotNull(rule.inputEntries[input.id])
                    append("        <inputEntry id=\"").append(xml("${rule.id}_${input.id}")).append("\"><text><![CDATA[")
                    append(conditionExpression(condition, input.type))
                    append("]]></text></inputEntry>\n")
                }
                model.outputs.forEach { output ->
                    val value = requireNotNull(rule.outputEntries[output.id])
                    append("        <outputEntry id=\"").append(xml("${rule.id}_${output.id}")).append("\"><text><![CDATA[")
                    append(outputLiteral(value, output.type))
                    append("]]></text></outputEntry>\n")
                }
                append("      </rule>\n")
            }
            append("    </decisionTable>\n")
            append("  </decision>\n")
            append("</definitions>\n")
        }
    }

    fun simulate(model: DmnDecisionModel, rawInputs: Map<String, String>): DmnSimulationResult {
        val validation = validate(model)
        if (!validation.valid) {
            return DmnSimulationResult(false, emptyList(), emptyList(), validation.diagnostics)
        }
        val diagnostics = validation.diagnostics.toMutableList()
        val typedInputs = linkedMapOf<String, ComparableValue>()
        model.inputs.forEach { input ->
            val raw = rawInputs[input.variable]
            if (raw == null) {
                diagnostics += DmnDiagnostic(
                    "DMN_SIMULATION_INPUT_MISSING",
                    DmnDiagnosticSeverity.ERROR,
                    "Simulation input is missing: ${input.variable}.",
                    columnId = input.id,
                )
            } else {
                parseComparable(raw, input.type)?.let { typedInputs[input.id] = it }
                    ?: run {
                        diagnostics += DmnDiagnostic(
                            "DMN_SIMULATION_INPUT_INVALID",
                            DmnDiagnosticSeverity.ERROR,
                            "Simulation value '$raw' is invalid for ${input.type}.",
                            columnId = input.id,
                        )
                    }
            }
        }
        if (diagnostics.any { it.severity == DmnDiagnosticSeverity.ERROR }) {
            return DmnSimulationResult(false, emptyList(), emptyList(), diagnostics)
        }
        val matching = model.rules.filter { rule ->
            rule.enabled && model.inputs.all { input ->
                matches(
                    typedInputs.getValue(input.id),
                    rule.inputEntries.getValue(input.id),
                    input.type,
                )
            }
        }
        val selected = when (model.hitPolicy) {
            DmnHitPolicy.FIRST -> matching.take(1)
            DmnHitPolicy.UNIQUE, DmnHitPolicy.ANY -> matching.take(1)
            DmnHitPolicy.PRIORITY -> matching.sortedBy { rulePriority(it, model) }.take(1)
            DmnHitPolicy.OUTPUT_ORDER -> matching.sortedBy { rulePriority(it, model) }
            DmnHitPolicy.RULE_ORDER, DmnHitPolicy.COLLECT -> matching
        }
        val results = selected.map { rule ->
            model.outputs.associate { output -> output.variable to rule.outputEntries.getValue(output.id) }
        }.let { rawResults ->
            if (model.hitPolicy != DmnHitPolicy.COLLECT ||
                model.collectOperator == DmnCollectOperator.NONE ||
                rawResults.isEmpty()
            ) {
                rawResults
            } else {
                val output = model.outputs.single()
                val values = rawResults.map { BigDecimal(it.getValue(output.variable)) }
                val aggregate = when (model.collectOperator) {
                    DmnCollectOperator.SUM -> values.fold(BigDecimal.ZERO, BigDecimal::add).toPlainString()
                    DmnCollectOperator.MIN -> values.minOrNull()!!.toPlainString()
                    DmnCollectOperator.MAX -> values.maxOrNull()!!.toPlainString()
                    DmnCollectOperator.COUNT -> rawResults.size.toString()
                    DmnCollectOperator.NONE -> error("unreachable")
                }
                listOf(mapOf(output.variable to aggregate))
            }
        }
        return DmnSimulationResult(
            accepted = true,
            matchedRuleIds = selected.map(DmnDecisionRuleModel::id),
            results = results,
            diagnostics = diagnostics,
        )
    }

    private fun rulePriority(rule: DmnDecisionRuleModel, model: DmnDecisionModel): Int =
        model.outputs.map { output ->
            output.predefinedValues.indexOf(rule.outputEntries[output.id])
                .takeIf { it >= 0 } ?: Int.MAX_VALUE
        }.minOrNull() ?: Int.MAX_VALUE

    private fun validateCondition(condition: DmnConditionModel, type: DmnValueType): String? {
        if (condition.operator == DmnConditionOperator.ANY) return null
        val value = condition.value ?: return "${condition.operator} requires a value."
        validateLiteral(value, type)?.let { return it }
        if (condition.operator == DmnConditionOperator.BETWEEN) {
            if (type !in setOf(DmnValueType.NUMBER, DmnValueType.DATE)) {
                return "BETWEEN is supported only for NUMBER and DATE."
            }
            val second = condition.secondValue ?: return "BETWEEN requires a second value."
            validateLiteral(second, type)?.let { return it }
            val firstValue = requireNotNull(parseComparable(value, type))
            val secondValue = requireNotNull(parseComparable(second, type))
            if (firstValue > secondValue) return "BETWEEN lower value cannot exceed upper value."
        }
        if (condition.operator !in setOf(
                DmnConditionOperator.ANY,
                DmnConditionOperator.EQUALS,
                DmnConditionOperator.NOT_EQUALS,
            ) && type in setOf(DmnValueType.STRING, DmnValueType.BOOLEAN)
        ) {
            return "${condition.operator} is not supported for $type."
        }
        return null
    }

    private fun validateLiteral(value: String, type: DmnValueType): String? {
        if (value.isBlank() || value.length > MAX_VALUE_LENGTH || value.any(Char::isISOControl) || "]]>" in value) {
            return "$type value must be visible, safe, and at most $MAX_VALUE_LENGTH characters."
        }
        return if (parseComparable(value, type) == null) "'$value' is not a valid $type value." else null
    }

    private fun parseDate(
        value: String?,
        label: String,
        error: (String, String, List<String>, String?) -> Unit,
    ): LocalDate? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value) }.getOrElse {
            error("DMN_EFFECTIVE_DATE", "$label must use ISO yyyy-MM-dd.", emptyList(), null)
            null
        }
    }

    private fun rulesOverlap(
        first: DmnDecisionRuleModel,
        second: DmnDecisionRuleModel,
        model: DmnDecisionModel,
    ): Boolean = model.inputs.all { input ->
        conditionsOverlap(
            first.inputEntries.getValue(input.id),
            second.inputEntries.getValue(input.id),
            input.type,
        )
    }

    private fun conditionsOverlap(
        first: DmnConditionModel,
        second: DmnConditionModel,
        type: DmnValueType,
    ): Boolean {
        if (first.operator == DmnConditionOperator.ANY || second.operator == DmnConditionOperator.ANY) return true
        val firstValue = first.value?.let { parseComparable(it, type) } ?: return true
        val secondValue = second.value?.let { parseComparable(it, type) } ?: return true
        if (first.operator == DmnConditionOperator.EQUALS) return matches(firstValue, second, type)
        if (second.operator == DmnConditionOperator.EQUALS) return matches(secondValue, first, type)
        if (first.operator == DmnConditionOperator.NOT_EQUALS && second.operator == DmnConditionOperator.NOT_EQUALS) return true
        if (first.operator == DmnConditionOperator.NOT_EQUALS) {
            return second.operator != DmnConditionOperator.EQUALS || firstValue != secondValue
        }
        if (second.operator == DmnConditionOperator.NOT_EQUALS) {
            return first.operator != DmnConditionOperator.EQUALS || firstValue != secondValue
        }
        val firstRange = conditionRange(first, type) ?: return true
        val secondRange = conditionRange(second, type) ?: return true
        return firstRange.overlaps(secondRange)
    }

    private fun matches(
        candidate: ComparableValue,
        condition: DmnConditionModel,
        type: DmnValueType,
    ): Boolean {
        if (condition.operator == DmnConditionOperator.ANY) return true
        val value = condition.value?.let { parseComparable(it, type) } ?: return false
        return when (condition.operator) {
            DmnConditionOperator.ANY -> true
            DmnConditionOperator.EQUALS -> candidate == value
            DmnConditionOperator.NOT_EQUALS -> candidate != value
            DmnConditionOperator.LESS_THAN -> candidate < value
            DmnConditionOperator.LESS_THAN_OR_EQUAL -> candidate <= value
            DmnConditionOperator.GREATER_THAN -> candidate > value
            DmnConditionOperator.GREATER_THAN_OR_EQUAL -> candidate >= value
            DmnConditionOperator.BETWEEN -> {
                val second = condition.secondValue?.let { parseComparable(it, type) } ?: return false
                candidate >= value && candidate <= second
            }
        }
    }

    private fun conditionRange(condition: DmnConditionModel, type: DmnValueType): ValueRange? {
        val value = condition.value?.let { parseComparable(it, type) } ?: return null
        return when (condition.operator) {
            DmnConditionOperator.LESS_THAN -> ValueRange(null, false, value, false)
            DmnConditionOperator.LESS_THAN_OR_EQUAL -> ValueRange(null, false, value, true)
            DmnConditionOperator.GREATER_THAN -> ValueRange(value, false, null, false)
            DmnConditionOperator.GREATER_THAN_OR_EQUAL -> ValueRange(value, true, null, false)
            DmnConditionOperator.BETWEEN -> ValueRange(
                value,
                true,
                condition.secondValue?.let { parseComparable(it, type) },
                true,
            )
            else -> null
        }
    }

    private fun conditionExpression(condition: DmnConditionModel, type: DmnValueType): String {
        if (condition.operator == DmnConditionOperator.ANY) return "-"
        val first = inputLiteral(requireNotNull(condition.value), type)
        return when (condition.operator) {
            DmnConditionOperator.ANY -> "-"
            DmnConditionOperator.EQUALS -> "==$first"
            DmnConditionOperator.NOT_EQUALS -> "!=$first"
            DmnConditionOperator.LESS_THAN -> "<$first"
            DmnConditionOperator.LESS_THAN_OR_EQUAL -> "<=$first"
            DmnConditionOperator.GREATER_THAN -> ">$first"
            DmnConditionOperator.GREATER_THAN_OR_EQUAL -> ">=$first"
            DmnConditionOperator.BETWEEN ->
                ">=$first && <=${inputLiteral(requireNotNull(condition.secondValue), type)}"
        }
    }

    private fun inputLiteral(value: String, type: DmnValueType): String =
        when (type) {
            DmnValueType.STRING, DmnValueType.DATE -> quoted(value)
            DmnValueType.NUMBER -> BigDecimal(value).stripTrailingZeros().toPlainString()
            DmnValueType.BOOLEAN -> value.lowercase()
        }

    private fun outputLiteral(value: String, type: DmnValueType): String =
        inputLiteral(value, type)

    private fun quoted(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun xml(value: String): String =
        value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun parseComparable(value: String, type: DmnValueType): ComparableValue? =
        runCatching {
            when (type) {
                DmnValueType.STRING -> ComparableValue.Text(value)
                DmnValueType.NUMBER -> ComparableValue.Number(BigDecimal(value))
                DmnValueType.BOOLEAN -> when (value.lowercase()) {
                    "true" -> ComparableValue.Bool(true)
                    "false" -> ComparableValue.Bool(false)
                    else -> error("invalid boolean")
                }
                DmnValueType.DATE -> ComparableValue.Date(LocalDate.parse(value))
            }
        }.getOrNull()

    private sealed interface ComparableValue : Comparable<ComparableValue> {
        data class Text(val value: String) : ComparableValue
        data class Number(val value: BigDecimal) : ComparableValue
        data class Bool(val value: Boolean) : ComparableValue
        data class Date(val value: LocalDate) : ComparableValue

        override fun compareTo(other: ComparableValue): Int =
            when {
                this is Text && other is Text -> value.compareTo(other.value)
                this is Number && other is Number -> value.compareTo(other.value)
                this is Bool && other is Bool -> value.compareTo(other.value)
                this is Date && other is Date -> value.compareTo(other.value)
                else -> error("Cannot compare different DMN value types.")
            }
    }

    private data class ValueRange(
        val low: ComparableValue?,
        val lowInclusive: Boolean,
        val high: ComparableValue?,
        val highInclusive: Boolean,
    ) {
        fun overlaps(other: ValueRange): Boolean {
            if (high != null && other.low != null) {
                val comparison = high.compareTo(other.low)
                if (comparison < 0 || (comparison == 0 && (!highInclusive || !other.lowInclusive))) return false
            }
            if (other.high != null && low != null) {
                val comparison = other.high.compareTo(low)
                if (comparison < 0 || (comparison == 0 && (!other.highInclusive || !lowInclusive))) return false
            }
            return true
        }
    }
}

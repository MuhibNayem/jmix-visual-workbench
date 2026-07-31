package org.jmixworkbench.model

import org.jmixworkbench.discovery.model.SourceLocator

enum class DmnValueType(val xmlType: String) {
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    DATE("date"),
}

enum class DmnHitPolicy {
    UNIQUE,
    FIRST,
    ANY,
    PRIORITY,
    OUTPUT_ORDER,
    RULE_ORDER,
    COLLECT,
}

enum class DmnCollectOperator(val xmlValue: String?) {
    NONE(null),
    SUM("SUM"),
    MIN("MIN"),
    MAX("MAX"),
    COUNT("COUNT"),
}

enum class DmnConditionOperator {
    ANY,
    EQUALS,
    NOT_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    BETWEEN,
}

enum class DmnAuthoringStatus {
    DRAFT,
    ACTIVE,
    RETIRED,
}

data class DmnInputModel(
    val id: String,
    val label: String,
    val variable: String,
    val type: DmnValueType,
)

data class DmnOutputModel(
    val id: String,
    val label: String,
    val variable: String,
    val type: DmnValueType,
    /** Highest priority first for PRIORITY and OUTPUT_ORDER. */
    val predefinedValues: List<String> = emptyList(),
)

data class DmnConditionModel(
    val operator: DmnConditionOperator,
    val value: String? = null,
    val secondValue: String? = null,
)

data class DmnDecisionRuleModel(
    val id: String,
    val description: String = "",
    val enabled: Boolean = true,
    val inputEntries: Map<String, DmnConditionModel>,
    val outputEntries: Map<String, String>,
)

/**
 * Closed visual model for a single Flowable/Jmix-compatible DMN decision table.
 *
 * The engine expressions are generated from typed operators and literals.
 * Arbitrary JUEL, FEEL, Groovy, Java, or bean invocation is intentionally absent.
 */
data class DmnDecisionModel(
    val name: String,
    val key: String,
    val namespace: String = "http://www.flowable.org/dmn",
    val destinationId: String,
    val fileName: String,
    val hitPolicy: DmnHitPolicy = DmnHitPolicy.UNIQUE,
    val collectOperator: DmnCollectOperator = DmnCollectOperator.NONE,
    val inputs: List<DmnInputModel>,
    val outputs: List<DmnOutputModel>,
    val rules: List<DmnDecisionRuleModel>,
    val authoringVersion: Int = 1,
    val authoringStatus: DmnAuthoringStatus = DmnAuthoringStatus.DRAFT,
    val effectiveFrom: String? = null,
    val effectiveTo: String? = null,
    val description: String = "",
    val sourceLocator: SourceLocator? = null,
)

enum class DmnDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class DmnDiagnostic(
    val code: String,
    val severity: DmnDiagnosticSeverity,
    val message: String,
    val ruleIds: List<String> = emptyList(),
    val columnId: String? = null,
)

data class DmnValidationResult(
    val valid: Boolean,
    val diagnostics: List<DmnDiagnostic>,
)

data class DmnSimulationRequest(
    val model: DmnDecisionModel,
    val inputs: Map<String, String>,
)

data class DmnSimulationResult(
    val accepted: Boolean,
    val matchedRuleIds: List<String>,
    val results: List<Map<String, String>>,
    val diagnostics: List<DmnDiagnostic>,
)

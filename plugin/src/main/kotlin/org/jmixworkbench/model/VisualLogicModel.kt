package org.jmixworkbench.model

import org.jmixworkbench.discovery.model.SourceLocator

enum class LogicNodeKind {
    START,
    RETURN,
    CONSTANT,
    CREATE_ENTITY,
    LOAD_ENTITY_BY_ID,
    LOAD_ENTITIES,
    SET_PROPERTY,
    SAVE_ENTITY,
    REMOVE_ENTITY,
    CALL_SERVICE,
    CALL_SUBFLOW,
    FOR_EACH,
    TRY_CATCH,
    CONDITION,
    REQUIRE,
    AUTHORIZE_ENTITY,
    THROW,
    LOG,
}

enum class LogicValueSource {
    LITERAL,
    PARAMETER,
    VARIABLE,
    NULL,
}

enum class LogicValueType {
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

enum class LogicConditionOperator {
    EQUALS,
    NOT_EQUALS,
    NULL,
    NOT_NULL,
    TRUE,
    FALSE,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    CONTAINS,
}

enum class LogicTransitionBranch {
    ALWAYS,
    TRUE,
    FALSE,
    ITEM,
    DONE,
}

enum class LogicMethodKind {
    ENTRY_POINT,
    SUBFLOW,
}

enum class LogicEntityOperation {
    CREATE,
    READ,
    UPDATE,
    DELETE,
}

enum class LogicLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class LogicTransactionPropagation {
    REQUIRED,
    REQUIRES_NEW,
    SUPPORTS,
    MANDATORY,
    NOT_SUPPORTED,
    NEVER,
    NESTED,
}

enum class LogicTransactionIsolation {
    DEFAULT,
    READ_UNCOMMITTED,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE,
}

data class LogicValueModel(
    val source: LogicValueSource,
    val type: LogicValueType = LogicValueType.OBJECT,
    val value: String? = null,
    val javaType: String? = null,
)

data class LogicNamedValueModel(
    val name: String,
    val value: LogicValueModel,
)

data class LogicConditionModel(
    val left: LogicValueModel,
    val operator: LogicConditionOperator,
    val right: LogicValueModel? = null,
)

data class LogicMethodParameterModel(
    val name: String,
    val javaType: String,
    val nullable: Boolean = false,
)

data class LogicTransitionModel(
    val id: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val branch: LogicTransitionBranch = LogicTransitionBranch.ALWAYS,
)

data class LogicNodeModel(
    val id: String,
    val label: String,
    val kind: LogicNodeKind,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val resultVariable: String? = null,
    val resultJavaType: String? = null,
    val entityClass: String? = null,
    val targetVariable: String? = null,
    val propertyPath: String? = null,
    val value: LogicValueModel? = null,
    val fieldValues: List<LogicNamedValueModel> = emptyList(),
    val jpql: String? = null,
    val queryParameters: List<LogicNamedValueModel> = emptyList(),
    val maxResults: Int? = null,
    val beanClass: String? = null,
    val beanFieldName: String? = null,
    val methodName: String? = null,
    val subflowMethod: String? = null,
    val catchMethod: String? = null,
    val finallyMethod: String? = null,
    val exceptionType: String? = null,
    val indexVariable: String? = null,
    val arguments: List<LogicValueModel> = emptyList(),
    val condition: LogicConditionModel? = null,
    val entityOperation: LogicEntityOperation? = null,
    val message: String? = null,
    val logLevel: LogicLogLevel = LogicLogLevel.INFO,
)

data class LogicTransactionModel(
    val enabled: Boolean = true,
    val readOnly: Boolean = false,
    val propagation: LogicTransactionPropagation = LogicTransactionPropagation.REQUIRED,
    val isolation: LogicTransactionIsolation = LogicTransactionIsolation.DEFAULT,
    val timeoutSeconds: Int? = null,
)

data class VisualLogicMethodModel(
    val name: String,
    val description: String = "",
    val kind: LogicMethodKind = LogicMethodKind.ENTRY_POINT,
    val returnJavaType: String = "void",
    val parameters: List<LogicMethodParameterModel> = emptyList(),
    val transaction: LogicTransactionModel = LogicTransactionModel(),
    val maximumExecutions: Int = 10_000,
    val nodes: List<LogicNodeModel>,
    val transitions: List<LogicTransitionModel>,
)

data class VisualLogicClassModel(
    val name: String,
    val description: String = "",
    val destinationId: String,
    val packageName: String,
    val className: String,
    val beanName: String,
    val methods: List<VisualLogicMethodModel>,
    val sourceLocator: SourceLocator? = null,
)

enum class LogicDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class LogicDiagnostic(
    val code: String,
    val severity: LogicDiagnosticSeverity,
    val message: String,
    val methodName: String? = null,
    val nodeId: String? = null,
)

data class VisualLogicValidationResult(
    val valid: Boolean,
    val diagnostics: List<LogicDiagnostic>,
)

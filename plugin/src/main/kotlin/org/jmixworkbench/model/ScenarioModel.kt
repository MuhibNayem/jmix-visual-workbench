package org.jmixworkbench.model

import org.jmixworkbench.discovery.model.SourceLocator

enum class ScenarioStepKind {
    SEED_ENTITY,
    INVOKE_SERVICE,
    ASSERT_PROPERTY,
    ASSERT_VALUE,
    ASSERT_ENTITY_COUNT,
    ASSERT_SERVICE_FAILURE,
}

enum class ScenarioValueType {
    STRING,
    INTEGER,
    LONG,
    DECIMAL,
    BOOLEAN,
    UUID,
    LOCAL_DATE,
    LOCAL_DATETIME,
    OFFSET_DATETIME,
    INSTANT,
    ENUM,
    NULL,
    VARIABLE,
}

enum class ScenarioAssertionOperator {
    EQUALS,
    NOT_EQUALS,
    NULL,
    NOT_NULL,
    TRUE,
    FALSE,
    GREATER_THAN,
    LESS_THAN,
    CONTAINS,
}

enum class ScenarioActorMode {
    SYSTEM,
    USER,
}

data class ScenarioValueModel(
    val type: ScenarioValueType,
    val value: String? = null,
    val javaType: String? = null,
)

data class ScenarioFieldValueModel(
    val property: String,
    val value: ScenarioValueModel,
)

data class ScenarioStepModel(
    val id: String,
    val label: String,
    val kind: ScenarioStepKind,
    val actorMode: ScenarioActorMode = ScenarioActorMode.SYSTEM,
    val username: String? = null,
    val variableName: String? = null,
    val entityClass: String? = null,
    val fields: List<ScenarioFieldValueModel> = emptyList(),
    val beanName: String? = null,
    val methodName: String? = null,
    val arguments: List<ScenarioValueModel> = emptyList(),
    val resultVariable: String? = null,
    val targetVariable: String? = null,
    val propertyPath: String? = null,
    val operator: ScenarioAssertionOperator? = null,
    val expected: ScenarioValueModel? = null,
    val jpql: String? = null,
    val expectedCount: Long? = null,
    val expectedExceptionClass: String? = null,
    val messageContains: String? = null,
)

data class ScenarioTestModel(
    val name: String,
    val description: String = "",
    val destinationId: String,
    val packageName: String,
    val className: String,
    val steps: List<ScenarioStepModel>,
    val sourceLocator: SourceLocator? = null,
)

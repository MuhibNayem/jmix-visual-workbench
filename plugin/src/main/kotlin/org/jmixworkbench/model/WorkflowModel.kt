package org.jmixworkbench.model

data class WorkflowModel(
    val id: String,
    val name: String,
    val moduleId: String,
    val entityQualifiedName: String? = null,
    val stateAttribute: String? = null,
    val candidateStarterGroups: List<String> = emptyList(),
    val candidateStarterUsers: List<String> = emptyList(),
    val businessKeyExpression: String? = null,
    val versionTag: String? = null,
    val tenantExpression: String? = null,
    val auditLevel: WorkflowAuditLevel = WorkflowAuditLevel.FULL,
    val lanes: List<WorkflowLaneModel> = emptyList(),
    val executionListeners: List<WorkflowListenerModel> = emptyList(),
    val sourceRelativePath: String? = null,
    val sourceFingerprint: String? = null,
    val documentation: String? = null,
    val nodes: List<WorkflowNodeModel> = emptyList(),
    val transitions: List<WorkflowTransitionModel> = emptyList(),
)

data class WorkflowLoadResponse(
    val workflow: WorkflowModel? = null,
    val editable: Boolean = false,
    val unsupportedElements: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

data class WorkflowNodeModel(
    val id: String,
    val name: String,
    val type: WorkflowNodeType,
    val stateValue: String? = null,
    val actorRoleCodes: List<String> = emptyList(),
    val assigneeExpression: String? = null,
    val formKey: String? = null,
    val formData: WorkflowFormData? = null,
    val processVariables: List<WorkflowProcessVariable> = emptyList(),
    val dueDate: String? = null,
    val priority: String? = null,
    val serviceBean: String? = null,
    val serviceMethod: String? = null,
    val script: String? = null,
    val resultVariable: String? = null,
    val entityDataOperation: WorkflowEntityDataOperation = WorkflowEntityDataOperation.LOAD,
    val entityName: String? = null,
    val entityVariable: String? = null,
    val jpql: String? = null,
    val saveLoadResultAs: WorkflowLoadResultMode = WorkflowLoadResultMode.SINGLE,
    val jpqlParametersJson: String? = null,
    val entityAttributesJson: String? = null,
    val emailTo: String? = null,
    val emailCc: String? = null,
    val emailBcc: String? = null,
    val emailFrom: String? = null,
    val emailSubject: String? = null,
    val emailContent: String? = null,
    val emailContentType: WorkflowEmailContentType = WorkflowEmailContentType.HTML,
    val emailSendAsync: Boolean = true,
    val emailAttachments: List<WorkflowEmailAttachmentModel> = emptyList(),
    val async: Boolean = false,
    val exclusive: Boolean = true,
    val triggerable: Boolean = false,
    val retryCycle: String? = null,
    val idempotencyKeyExpression: String? = null,
    val multiInstanceMode: WorkflowMultiInstanceMode = WorkflowMultiInstanceMode.NONE,
    val loopCardinality: String? = null,
    val collectionExpression: String? = null,
    val elementVariable: String? = null,
    val completionCondition: String? = null,
    val calledElement: String? = null,
    val inheritBusinessKey: Boolean = true,
    val inheritVariables: Boolean = false,
    val decisionTableKey: String? = null,
    val timerType: WorkflowTimerType = WorkflowTimerType.DURATION,
    val timerExpression: String? = null,
    val attachedToNodeId: String? = null,
    val cancelActivity: Boolean = true,
    val eventStartInterrupting: Boolean = true,
    val eventReference: String? = null,
    val signalScope: WorkflowSignalScope = WorkflowSignalScope.GLOBAL,
    val compensationActivityRef: String? = null,
    val compensationHandlerNodeId: String? = null,
    val forCompensation: Boolean = false,
    val parentSubprocessId: String? = null,
    val laneId: String? = null,
    val executionListeners: List<WorkflowListenerModel> = emptyList(),
    val taskListeners: List<WorkflowListenerModel> = emptyList(),
    val inputMappings: List<WorkflowVariableMapping> = emptyList(),
    val outputMappings: List<WorkflowVariableMapping> = emptyList(),
    val defaultTransitionId: String? = null,
    val minimumApprovals: Int? = null,
    val segregationOfDutyNodeIds: List<String> = emptyList(),
    val requiredDocuments: List<String> = emptyList(),
    val validationRules: List<String> = emptyList(),
    val sideEffects: List<String> = emptyList(),
    val notifications: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val documentation: String? = null,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 168,
    val height: Int = 66,
)

enum class WorkflowNodeType {
    START,
    MESSAGE_START,
    SIGNAL_START,
    TIMER_START,
    ERROR_START,
    HUMAN_STATE,
    AUTOMATED_STATE,
    SCRIPT_STATE,
    ENTITY_DATA_STATE,
    EMAIL_STATE,
    DECISION,
    PARALLEL_GATEWAY,
    INCLUSIVE_GATEWAY,
    BUSINESS_RULE_STATE,
    EMBEDDED_SUBPROCESS,
    EVENT_SUBPROCESS,
    TRANSACTION_SUBPROCESS,
    CALL_ACTIVITY,
    TIMER_EVENT,
    MESSAGE_CATCH,
    SIGNAL_CATCH,
    SIGNAL_THROW,
    COMPENSATION_THROW,
    BOUNDARY_TIMER,
    BOUNDARY_MESSAGE,
    BOUNDARY_SIGNAL,
    BOUNDARY_ERROR,
    BOUNDARY_COMPENSATION,
    BOUNDARY_CANCEL,
    ERROR_END,
    CANCEL_END,
    TERMINATE_END,
    TERMINAL,
}

data class WorkflowEmailAttachmentModel(
    val id: String,
    val name: String? = null,
    val expression: String,
)

enum class WorkflowEmailContentType(val xmlValue: String) {
    HTML("text/html"),
    PLAIN_TEXT("text/plain"),
}

data class WorkflowLaneModel(
    val id: String,
    val name: String,
    val actorRoleCodes: List<String> = emptyList(),
)

data class WorkflowListenerModel(
    val event: String,
    val implementationType: WorkflowListenerImplementationType = WorkflowListenerImplementationType.EXPRESSION,
    val implementation: String,
)

enum class WorkflowListenerImplementationType {
    EXPRESSION,
    DELEGATE_EXPRESSION,
    CLASS,
}

data class WorkflowVariableMapping(
    val source: String? = null,
    val sourceExpression: String? = null,
    val target: String,
)

data class WorkflowProcessVariable(
    val name: String,
    val type: String,
)

data class WorkflowFormData(
    val type: WorkflowFormType = WorkflowFormType.NO_FORM,
    val openMode: WorkflowFormOpenMode = WorkflowFormOpenMode.DIALOG,
    val screenId: String? = null,
    val businessKey: String? = null,
    val businessKeySource: String? = null,
    val fields: List<WorkflowFormField> = emptyList(),
    val outcomes: List<WorkflowFormOutcome> = emptyList(),
)

enum class WorkflowFormType(val xmlValue: String) {
    NO_FORM("no-form"),
    INPUT_DIALOG("input-dialog"),
    JMIX_VIEW("jmix-screen"),
    CUSTOM("custom"),
}

enum class WorkflowFormOpenMode {
    DIALOG,
    NAVIGATE,
}

data class WorkflowFormField(
    val id: String,
    val caption: String,
    val type: String,
    val editable: Boolean = true,
    val required: Boolean = false,
    val properties: Map<String, String> = emptyMap(),
)

data class WorkflowFormOutcome(
    val id: String,
    val caption: String,
    val icon: String? = null,
)

enum class WorkflowMultiInstanceMode {
    NONE,
    SEQUENTIAL,
    PARALLEL,
}

enum class WorkflowEntityDataOperation {
    LOAD,
    MODIFY,
    CREATE,
}

enum class WorkflowLoadResultMode {
    SINGLE,
    COLLECTION,
}

enum class WorkflowTimerType {
    DURATION,
    DATE,
    CYCLE,
}

enum class WorkflowAuditLevel {
    BASIC,
    FULL,
    REGULATED,
}

enum class WorkflowSignalScope {
    GLOBAL,
    PROCESS_INSTANCE,
}

data class WorkflowTransitionModel(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val name: String? = null,
    val conditionExpression: String? = null,
    val outcomeId: String? = null,
    val requiredRoleCodes: List<String> = emptyList(),
    val requiredDocuments: List<String> = emptyList(),
    val validationRules: List<String> = emptyList(),
    val sideEffects: List<String> = emptyList(),
    val notifications: List<String> = emptyList(),
)

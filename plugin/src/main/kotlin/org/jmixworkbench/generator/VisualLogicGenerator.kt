package org.jmixworkbench.generator

import org.jmixworkbench.model.LogicConditionModel
import org.jmixworkbench.model.LogicConditionOperator
import org.jmixworkbench.model.LogicDiagnostic
import org.jmixworkbench.model.LogicDiagnosticSeverity
import org.jmixworkbench.model.LogicEntityOperation
import org.jmixworkbench.model.LogicMethodKind
import org.jmixworkbench.model.LogicNodeKind
import org.jmixworkbench.model.LogicNodeModel
import org.jmixworkbench.model.LogicTransitionBranch
import org.jmixworkbench.model.LogicTransitionModel
import org.jmixworkbench.model.LogicValueModel
import org.jmixworkbench.model.LogicValueSource
import org.jmixworkbench.model.LogicValueType
import org.jmixworkbench.model.VisualLogicClassModel
import org.jmixworkbench.model.VisualLogicMethodModel
import org.jmixworkbench.model.VisualLogicValidationResult
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Compiles the visual server-logic graph to deterministic Java 17 source.
 *
 * Values are structured and typed; arbitrary Java expressions are deliberately
 * absent from the model. Persistence always uses the security-constrained
 * DataManager. Explicit authorization nodes use Jmix AccessManager contexts.
 */
object VisualLogicGenerator {
    private const val MARKER_PREFIX = "// JVW-VISUAL-LOGIC-MODEL: "
    private const val MAX_METHODS = 100
    private const val MAX_NODES_PER_METHOD = 10_000
    private const val MAX_TRANSITIONS_PER_METHOD = 30_000
    private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val PACKAGE_NAME = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
    private val ENUM_CONSTANT = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val RESERVED_FIELDS = setOf("dataManager", "accessManager", "metadata", "log")

    fun markerPrefix(): String = MARKER_PREFIX

    fun validate(model: VisualLogicClassModel): VisualLogicValidationResult {
        val diagnostics = mutableListOf<LogicDiagnostic>()
        fun error(code: String, message: String, method: String? = null, node: String? = null) {
            diagnostics += LogicDiagnostic(code, LogicDiagnosticSeverity.ERROR, message, method, node)
        }
        fun warning(code: String, message: String, method: String? = null, node: String? = null) {
            diagnostics += LogicDiagnostic(code, LogicDiagnosticSeverity.WARNING, message, method, node)
        }

        if (!PACKAGE_NAME.matches(model.packageName)) {
            error("LOGIC_PACKAGE_INVALID", "Package name is not a valid Java package.")
        }
        if (!IDENTIFIER.matches(model.className)) {
            error("LOGIC_CLASS_INVALID", "Class name is not a valid Java identifier.")
        }
        if (model.beanName.isBlank() || model.beanName.length > 180 || model.beanName.any { it.isISOControl() }) {
            error("LOGIC_BEAN_NAME_INVALID", "Spring bean name must be a visible value of at most 180 characters.")
        }
        if (model.destinationId.isBlank()) {
            error("LOGIC_DESTINATION_REQUIRED", "Select an indexed production Java destination.")
        }
        if (model.methods.isEmpty()) {
            error("LOGIC_METHOD_REQUIRED", "Add at least one visual service method.")
        }
        if (model.methods.size > MAX_METHODS) {
            error("LOGIC_METHOD_LIMIT", "A visual service class supports at most $MAX_METHODS methods.")
        }
        model.methods.groupBy(VisualLogicMethodModel::name)
            .filterValues { it.size > 1 }
            .keys
            .forEach { error("LOGIC_METHOD_DUPLICATE", "Method name is duplicated: $it.", it) }
        if (model.methods.none { it.kind == LogicMethodKind.ENTRY_POINT }) {
            error("LOGIC_ENTRY_POINT_REQUIRED", "Add at least one public entry-point method.")
        }

        val beanFieldTypes = linkedMapOf<String, String>()
        model.methods.forEach { method ->
            validateMethod(method, diagnostics)
            method.nodes.filter { it.kind == LogicNodeKind.CALL_SERVICE }.forEach { node ->
                val beanClass = node.beanClass.orEmpty()
                val field = beanFieldName(node)
                if (field in RESERVED_FIELDS) {
                    error(
                        "LOGIC_BEAN_FIELD_RESERVED",
                        "Service field '$field' collides with a generated infrastructure field.",
                        method.name,
                        node.id,
                    )
                }
                val previous = beanFieldTypes.putIfAbsent(field, beanClass)
                if (previous != null && previous != beanClass) {
                    error(
                        "LOGIC_BEAN_FIELD_COLLISION",
                        "Service field '$field' is assigned to both $previous and $beanClass.",
                        method.name,
                        node.id,
                    )
                }
            }
        }
        validateSubflowReferences(model, diagnostics)
        if (model.methods.none { method -> method.nodes.any { it.kind == LogicNodeKind.AUTHORIZE_ENTITY } }) {
            warning(
                "LOGIC_SECURITY_IMPLICIT",
                "No explicit authorization node is present. DataManager still enforces entity and row-level policies.",
            )
        }
        return VisualLogicValidationResult(
            valid = diagnostics.none { it.severity == LogicDiagnosticSeverity.ERROR },
            diagnostics = diagnostics.sortedWith(
                compareBy(LogicDiagnostic::severity, LogicDiagnostic::methodName, LogicDiagnostic::nodeId, LogicDiagnostic::code),
            ),
        )
    }

    private fun validateSubflowReferences(
        model: VisualLogicClassModel,
        diagnostics: MutableList<LogicDiagnostic>,
    ) {
        val methods = model.methods.associateBy(VisualLogicMethodModel::name)
        val callGraph = linkedMapOf<String, MutableSet<String>>()
        fun error(method: VisualLogicMethodModel, node: LogicNodeModel, code: String, message: String) {
            diagnostics += LogicDiagnostic(code, LogicDiagnosticSeverity.ERROR, message, method.name, node.id)
        }
        fun target(
            owner: VisualLogicMethodModel,
            node: LogicNodeModel,
            name: String?,
            role: String,
            argumentCount: Int,
        ): VisualLogicMethodModel? {
            if (name.isNullOrBlank()) {
                error(owner, node, "LOGIC_SUBFLOW_REQUIRED", "$role requires a reusable subflow.")
                return null
            }
            val resolved = methods[name]
            if (resolved == null) {
                error(owner, node, "LOGIC_SUBFLOW_MISSING", "$role references missing subflow '$name'.")
                return null
            }
            if (resolved.kind != LogicMethodKind.SUBFLOW) {
                error(owner, node, "LOGIC_SUBFLOW_NOT_REUSABLE", "$role target '$name' must be marked as a reusable subflow.")
            }
            if (resolved.parameters.size != argumentCount) {
                error(
                    owner,
                    node,
                    "LOGIC_SUBFLOW_ARGUMENT_COUNT",
                    "$role target '$name' requires ${resolved.parameters.size} argument(s), but $argumentCount are configured.",
                )
            }
            callGraph.getOrPut(owner.name, ::linkedSetOf) += resolved.name
            return resolved
        }
        fun validateArguments(
            owner: VisualLogicMethodModel,
            node: LogicNodeModel,
            role: String,
            arguments: List<LogicValueModel>,
            parameters: List<org.jmixworkbench.model.LogicMethodParameterModel>,
        ) {
            if (arguments.size != parameters.size) return
            val variables = methodVariableTypes(owner)
            arguments.zip(parameters).forEachIndexed { index, (argument, parameter) ->
                val actual = valueJavaType(argument, variables) ?: return@forEachIndexed
                if (!sameJavaType(actual, parameter.javaType)) {
                    error(
                        owner,
                        node,
                        "LOGIC_SUBFLOW_ARGUMENT_TYPE",
                        "$role argument ${index + 1} is $actual, but ${parameter.name} requires ${parameter.javaType}.",
                    )
                }
            }
        }

        model.methods.forEach { method ->
            method.nodes.forEach { node ->
                when (node.kind) {
                    LogicNodeKind.CALL_SUBFLOW -> {
                        val invoked = target(
                            method,
                            node,
                            node.subflowMethod,
                            "Subflow call",
                            node.arguments.size,
                        )
                        invoked?.let {
                            validateArguments(
                                method,
                                node,
                                "Subflow call",
                                node.arguments,
                                it.parameters,
                            )
                        }
                        validateSubflowResult(method, node, invoked, diagnostics)
                    }
                    LogicNodeKind.TRY_CATCH -> {
                        val invoked = target(
                            method,
                            node,
                            node.subflowMethod,
                            "Try block",
                            node.arguments.size,
                        )
                        invoked?.let {
                            validateArguments(
                                method,
                                node,
                                "Try block",
                                node.arguments,
                                it.parameters,
                            )
                        }
                        val exceptionType = node.exceptionType.orEmpty()
                        if (!isSafeJavaType(exceptionType)) {
                            error(method, node, "LOGIC_EXCEPTION_TYPE_INVALID", "Try/catch requires a safe exception Java type.")
                        } else {
                            val simpleName = exceptionType.substringAfterLast('.')
                            if (
                                simpleName != "Throwable" &&
                                !simpleName.endsWith("Exception") &&
                                !simpleName.endsWith("Error")
                            ) {
                                error(
                                    method,
                                    node,
                                    "LOGIC_EXCEPTION_TYPE_UNSUPPORTED",
                                    "Catch type '$exceptionType' must be Throwable, an Exception, or an Error type.",
                                )
                            }
                        }
                        val caught = node.catchMethod?.takeIf(String::isNotBlank)?.let {
                            target(method, node, it, "Catch block", node.arguments.size + 1)
                        }
                        if (caught != null &&
                            caught.parameters.lastOrNull()?.javaType != exceptionType
                        ) {
                            error(
                                method,
                                node,
                                "LOGIC_CATCH_EXCEPTION_PARAMETER",
                                "Catch subflow '${caught.name}' must declare $exceptionType as its final parameter.",
                            )
                        }
                        caught?.let {
                            validateArguments(
                                method,
                                node,
                                "Catch block",
                                node.arguments,
                                it.parameters.dropLast(1),
                            )
                        }
                        node.finallyMethod?.takeIf(String::isNotBlank)?.let {
                            val finalized = target(
                                method,
                                node,
                                it,
                                "Finally block",
                                node.arguments.size,
                            )
                            if (finalized != null && finalized.returnJavaType != "void") {
                                error(
                                    method,
                                    node,
                                    "LOGIC_FINALLY_RETURN_TYPE",
                                    "Finally subflow '${finalized.name}' must return void.",
                                )
                            }
                            finalized?.let {
                                validateArguments(
                                    method,
                                    node,
                                    "Finally block",
                                    node.arguments,
                                    it.parameters,
                                )
                            }
                        }
                        if (node.catchMethod.isNullOrBlank() && node.finallyMethod.isNullOrBlank()) {
                            error(
                                method,
                                node,
                                "LOGIC_TRY_HANDLER_REQUIRED",
                                "Try/catch requires a catch subflow, a finally subflow, or both.",
                            )
                        }
                        validateSubflowResult(method, node, invoked, diagnostics)
                        if (caught != null && invoked != null && caught.returnJavaType != invoked.returnJavaType) {
                            error(
                                method,
                                node,
                                "LOGIC_CATCH_RETURN_TYPE",
                                "Try and catch subflows must return the same Java type.",
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        val visiting = linkedSetOf<String>()
        val visited = linkedSetOf<String>()
        fun visit(methodName: String): Boolean {
            if (!visiting.add(methodName)) return true
            if (methodName in visited) {
                visiting.remove(methodName)
                return false
            }
            val cycle = callGraph[methodName].orEmpty().any(::visit)
            visiting.remove(methodName)
            visited += methodName
            return cycle
        }
        callGraph.keys.filter(::visit).forEach { methodName ->
            diagnostics += LogicDiagnostic(
                "LOGIC_SUBFLOW_RECURSION",
                LogicDiagnosticSeverity.ERROR,
                "Reusable subflows cannot call themselves directly or indirectly.",
                methodName,
            )
        }
        val directWrites = model.methods.associate { method ->
            method.name to method.nodes.any {
                it.kind in setOf(
                    LogicNodeKind.CREATE_ENTITY,
                    LogicNodeKind.SAVE_ENTITY,
                    LogicNodeKind.REMOVE_ENTITY,
                )
            }
        }
        fun reachesWrite(methodName: String, checked: MutableSet<String> = linkedSetOf()): Boolean {
            if (!checked.add(methodName)) return false
            if (directWrites[methodName] == true) return true
            return callGraph[methodName].orEmpty().any { reachesWrite(it, checked) }
        }
        model.methods.filter {
            it.kind == LogicMethodKind.ENTRY_POINT && it.transaction.readOnly
        }.forEach { method ->
            if (reachesWrite(method.name)) {
                diagnostics += LogicDiagnostic(
                    "LOGIC_READ_ONLY_SUBFLOW_WRITE",
                    LogicDiagnosticSeverity.ERROR,
                    "Read-only entry point reaches a persistence write through a reusable subflow.",
                    method.name,
                )
            }
        }
    }

    private fun validateSubflowResult(
        method: VisualLogicMethodModel,
        node: LogicNodeModel,
        target: VisualLogicMethodModel?,
        diagnostics: MutableList<LogicDiagnostic>,
    ) {
        if (target == null) return
        val hasResult = !node.resultVariable.isNullOrBlank()
        if (target.returnJavaType == "void" && hasResult) {
            diagnostics += LogicDiagnostic(
                "LOGIC_SUBFLOW_VOID_RESULT",
                LogicDiagnosticSeverity.ERROR,
                "Void subflow '${target.name}' cannot be assigned to a result variable.",
                method.name,
                node.id,
            )
        } else if (target.returnJavaType != "void" && !hasResult) {
            diagnostics += LogicDiagnostic(
                "LOGIC_SUBFLOW_RESULT_REQUIRED",
                LogicDiagnosticSeverity.ERROR,
                "Subflow '${target.name}' returns ${target.returnJavaType}; configure a result variable.",
                method.name,
                node.id,
            )
        } else if (hasResult && node.resultJavaType != target.returnJavaType) {
            diagnostics += LogicDiagnostic(
                "LOGIC_SUBFLOW_RESULT_TYPE",
                LogicDiagnosticSeverity.ERROR,
                "Result type must exactly match ${target.returnJavaType}.",
                method.name,
                node.id,
            )
        }
    }

    fun generate(model: VisualLogicClassModel, encodedModel: String): String {
        val validation = validate(model)
        require(validation.valid) {
            validation.diagnostics
                .filter { it.severity == LogicDiagnosticSeverity.ERROR }
                .joinToString("; ") { "${it.code}: ${it.message}" }
        }
        val usesAuthorization = model.methods.any { method ->
            method.nodes.any { it.kind == LogicNodeKind.AUTHORIZE_ENTITY }
        }
        val usesLog = model.methods.any { method -> method.nodes.any { it.kind == LogicNodeKind.LOG } }
        val usesComparison = model.methods.any { method ->
            method.nodes.any { node ->
                node.condition?.operator in COMPARISON_OPERATORS
            }
        }
        val beanSpecs = model.methods
            .flatMap(VisualLogicMethodModel::nodes)
            .filter { it.kind == LogicNodeKind.CALL_SERVICE }
            .map { BeanSpec(requireNotNull(it.beanClass), beanFieldName(it)) }
            .distinctBy(BeanSpec::fieldName)
            .sortedBy(BeanSpec::fieldName)

        return buildString {
            append("package ").append(model.packageName).append(";\n\n")
            append("import io.jmix.core.DataManager;\n")
            append("import io.jmix.core.entity.EntityValues;\n")
            if (usesAuthorization) {
                append("import io.jmix.core.AccessManager;\n")
                append("import io.jmix.core.Metadata;\n")
                append("import io.jmix.core.accesscontext.CrudEntityContext;\n")
                append("import org.springframework.security.access.AccessDeniedException;\n")
            }
            if (usesLog) {
                append("import org.slf4j.Logger;\n")
                append("import org.slf4j.LoggerFactory;\n")
            }
            append("import org.springframework.stereotype.Component;\n")
            append("import org.springframework.transaction.annotation.Isolation;\n")
            append("import org.springframework.transaction.annotation.Propagation;\n")
            append("import org.springframework.transaction.annotation.Transactional;\n\n")
            append("import java.math.BigDecimal;\n")
            append("import java.time.Instant;\n")
            append("import java.time.LocalDate;\n")
            append("import java.time.LocalDateTime;\n")
            append("import java.time.OffsetDateTime;\n")
            append("import java.util.List;\n")
            append("import java.util.Objects;\n")
            append("import java.util.UUID;\n\n")
            if (encodedModel.isNotBlank()) append(MARKER_PREFIX).append(encodedModel).append('\n')
            append("/**\n")
            append(" * ").append(safeComment(model.name)).append('\n')
            model.description.takeIf(String::isNotBlank)?.let {
                append(" * ").append(safeComment(it)).append('\n')
            }
            append(" * Generated from a source-owned visual logic graph by Jmix Visual Workbench.\n")
            append(" */\n")
            append("@Component(").append(javaString(model.beanName)).append(")\n")
            append("public class ").append(model.className).append(" {\n\n")
            if (usesLog) {
                append("    private static final Logger log = LoggerFactory.getLogger(")
                    .append(model.className)
                    .append(".class);\n\n")
            }
            append("    private final DataManager dataManager;\n")
            if (usesAuthorization) {
                append("    private final AccessManager accessManager;\n")
                append("    private final Metadata metadata;\n")
            }
            beanSpecs.forEach { bean ->
                append("    private final ").append(bean.javaType).append(' ').append(bean.fieldName).append(";\n")
            }
            append('\n')
            append("    public ").append(model.className).append("(\n")
            val constructorParameters = buildList {
                add("DataManager dataManager")
                if (usesAuthorization) {
                    add("AccessManager accessManager")
                    add("Metadata metadata")
                }
                beanSpecs.forEach { add("${it.javaType} ${it.fieldName}") }
            }
            constructorParameters.forEachIndexed { index, parameter ->
                append("            ").append(parameter)
                .append(if (index == constructorParameters.lastIndex) "" else ",")
                .append('\n')
            }
            append("    ) {\n")
            append("        this.dataManager = dataManager;\n")
            if (usesAuthorization) {
                append("        this.accessManager = accessManager;\n")
                append("        this.metadata = metadata;\n")
            }
            beanSpecs.forEach { append("        this.${it.fieldName} = ${it.fieldName};\n") }
            append("    }\n\n")
            model.methods.forEachIndexed { index, method ->
                append(renderMethod(method))
                if (index != model.methods.lastIndex) append('\n')
            }
            if (usesComparison) append(COMPARISON_HELPERS)
            append("}\n")
        }
    }

    private fun validateMethod(
        method: VisualLogicMethodModel,
        diagnostics: MutableList<LogicDiagnostic>,
    ) {
        fun error(code: String, message: String, node: String? = null) {
            diagnostics += LogicDiagnostic(code, LogicDiagnosticSeverity.ERROR, message, method.name, node)
        }
        fun warning(code: String, message: String, node: String? = null) {
            diagnostics += LogicDiagnostic(code, LogicDiagnosticSeverity.WARNING, message, method.name, node)
        }
        if (!IDENTIFIER.matches(method.name)) error("LOGIC_METHOD_INVALID", "Method name is not a valid Java identifier.")
        if (!isSafeJavaType(method.returnJavaType)) error("LOGIC_RETURN_TYPE_INVALID", "Return type is not a safe Java type.")
        if (method.kind == LogicMethodKind.SUBFLOW && method.transaction.enabled) {
            error(
                "LOGIC_SUBFLOW_TRANSACTION",
                "Reusable subflows execute inside their caller and cannot declare an independent Spring transaction boundary.",
            )
        }
        if (method.maximumExecutions !in 1..1_000_000) {
            error("LOGIC_EXECUTION_LIMIT_INVALID", "Maximum node executions must be between 1 and 1,000,000.")
        }
        if (method.nodes.size > MAX_NODES_PER_METHOD) {
            error("LOGIC_NODE_LIMIT", "Method exceeds the $MAX_NODES_PER_METHOD node limit.")
        }
        if (method.transitions.size > MAX_TRANSITIONS_PER_METHOD) {
            error("LOGIC_TRANSITION_LIMIT", "Method exceeds the $MAX_TRANSITIONS_PER_METHOD transition limit.")
        }
        if (method.transaction.timeoutSeconds != null && method.transaction.timeoutSeconds !in 1..86_400) {
            error("LOGIC_TRANSACTION_TIMEOUT_INVALID", "Transaction timeout must be between 1 and 86,400 seconds.")
        }
        val parameters = linkedMapOf<String, String>()
        method.parameters.forEach { parameter ->
            if (!IDENTIFIER.matches(parameter.name)) {
                error("LOGIC_PARAMETER_INVALID", "Parameter '${parameter.name}' is not a valid Java identifier.")
            }
            if (!isSafeJavaType(parameter.javaType)) {
                error("LOGIC_PARAMETER_TYPE_INVALID", "Parameter '${parameter.name}' has an unsafe Java type.")
            }
            if (parameters.putIfAbsent(parameter.name, parameter.javaType) != null) {
                error("LOGIC_PARAMETER_DUPLICATE", "Parameter is duplicated: ${parameter.name}.")
            }
        }
        val nodesById = linkedMapOf<String, LogicNodeModel>()
        method.nodes.forEach { node ->
            if (node.id.isBlank() || node.id.length > 160 || node.id.any(Char::isISOControl)) {
                error("LOGIC_NODE_ID_INVALID", "Node id must be a visible value of at most 160 characters.", node.id)
            }
            if (nodesById.putIfAbsent(node.id, node) != null) {
                error("LOGIC_NODE_DUPLICATE", "Node id is duplicated: ${node.id}.", node.id)
            }
        }
        val starts = method.nodes.filter { it.kind == LogicNodeKind.START }
        if (starts.size != 1) error("LOGIC_START_COUNT", "Method must contain exactly one start node.")
        val transitionsById = linkedSetOf<String>()
        method.transitions.forEach { transition ->
            if (transition.id.isBlank() || !transitionsById.add(transition.id)) {
                error("LOGIC_TRANSITION_ID_INVALID", "Transition ids must be non-empty and unique.")
            }
            if (transition.sourceNodeId !in nodesById) {
                error("LOGIC_TRANSITION_SOURCE_MISSING", "Transition source does not exist: ${transition.sourceNodeId}.")
            }
            if (transition.targetNodeId !in nodesById) {
                error("LOGIC_TRANSITION_TARGET_MISSING", "Transition target does not exist: ${transition.targetNodeId}.")
            }
        }
        val outgoing = method.transitions.groupBy(LogicTransitionModel::sourceNodeId)
        method.nodes.forEach { node ->
            val edges = outgoing[node.id].orEmpty()
            when (node.kind) {
                LogicNodeKind.RETURN,
                LogicNodeKind.THROW,
                -> if (edges.isNotEmpty()) error("LOGIC_TERMINAL_OUTGOING", "Terminal node cannot have outgoing transitions.", node.id)
                LogicNodeKind.CONDITION -> {
                    if (edges.count { it.branch == LogicTransitionBranch.TRUE } != 1 ||
                        edges.count { it.branch == LogicTransitionBranch.FALSE } != 1 ||
                        edges.any { it.branch == LogicTransitionBranch.ALWAYS }
                    ) {
                        error("LOGIC_CONDITION_BRANCHES", "Condition node requires exactly one true and one false transition.", node.id)
                    }
                }
                LogicNodeKind.FOR_EACH -> {
                    if (edges.count { it.branch == LogicTransitionBranch.ITEM } != 1 ||
                        edges.count { it.branch == LogicTransitionBranch.DONE } != 1 ||
                        edges.any {
                            it.branch !in setOf(
                                LogicTransitionBranch.ITEM,
                                LogicTransitionBranch.DONE,
                            )
                        }
                    ) {
                        error(
                            "LOGIC_FOR_EACH_BRANCHES",
                            "For-each node requires exactly one ITEM and one DONE transition.",
                            node.id,
                        )
                    }
                }
                else -> if (edges.size != 1 || edges.singleOrNull()?.branch != LogicTransitionBranch.ALWAYS) {
                    error("LOGIC_NEXT_TRANSITION", "Node requires exactly one unconditional outgoing transition.", node.id)
                }
            }
        }
        val start = starts.singleOrNull()
        if (start != null && method.transitions.any { it.targetNodeId == start.id }) {
            error("LOGIC_START_INCOMING", "Start node cannot have an incoming transition.", start.id)
        }
        if (start != null) {
            val reachable = linkedSetOf<String>()
            val pending = ArrayDeque<String>()
            pending.add(start.id)
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (!reachable.add(id)) continue
                outgoing[id].orEmpty().forEach { pending.add(it.targetNodeId) }
            }
            method.nodes.filter { it.id !in reachable }.forEach {
                error("LOGIC_NODE_UNREACHABLE", "Node is unreachable from the start node.", it.id)
            }
            val terminals = reachable.mapNotNull(nodesById::get)
                .filter { outgoing[it.id].isNullOrEmpty() }
            if (method.returnJavaType != "void" && terminals.any { it.kind != LogicNodeKind.RETURN && it.kind != LogicNodeKind.THROW }) {
                error("LOGIC_RETURN_PATH_INCOMPLETE", "Every non-void execution path must return a value or throw.")
            }
        }

        val variableTypes = linkedMapOf<String, String>()
        parameters.forEach(variableTypes::put)
        method.nodes.forEach { node ->
            validateNode(node, method, variableTypes, diagnostics)
            producedVariables(node, variableTypes).forEach { (name, javaType) ->
                if (!IDENTIFIER.matches(name)) {
                    error("LOGIC_VARIABLE_INVALID", "Result variable '$name' is not a valid Java identifier.", node.id)
                } else {
                    val previous = variableTypes.putIfAbsent(name, javaType)
                    if (previous != null && !(node.kind == LogicNodeKind.SAVE_ENTITY && name == node.targetVariable)) {
                        error("LOGIC_VARIABLE_DUPLICATE", "Variable '$name' is produced more than once.", node.id)
                    }
                }
            }
        }
        method.nodes.forEach { node ->
            referencedValues(node).forEach { value ->
                when (value.source) {
                    LogicValueSource.PARAMETER -> if (value.value !in parameters) {
                        error("LOGIC_PARAMETER_REFERENCE_MISSING", "Unknown parameter: ${value.value}.", node.id)
                    }
                    LogicValueSource.VARIABLE -> if (value.value !in variableTypes) {
                        error("LOGIC_VARIABLE_REFERENCE_MISSING", "Unknown variable: ${value.value}.", node.id)
                    }
                    LogicValueSource.LITERAL -> validateLiteral(value)?.let {
                        error("LOGIC_LITERAL_INVALID", it, node.id)
                    }
                    LogicValueSource.NULL -> Unit
                }
            }
            node.targetVariable?.takeIf(String::isNotBlank)?.let { target ->
                if (target !in variableTypes) {
                    error("LOGIC_TARGET_VARIABLE_MISSING", "Unknown target variable: $target.", node.id)
                }
            }
        }
        if (method.transaction.readOnly && method.nodes.any {
                it.kind in setOf(
                    LogicNodeKind.CREATE_ENTITY,
                    LogicNodeKind.SAVE_ENTITY,
                    LogicNodeKind.REMOVE_ENTITY,
                )
            }
        ) {
            error("LOGIC_READ_ONLY_WRITE", "Read-only transaction contains persistence write nodes.")
        }
        if (method.nodes.any { it.kind == LogicNodeKind.LOAD_ENTITIES && it.maxResults == null }) {
            warning("LOGIC_QUERY_UNBOUNDED", "A collection query has no maximum result limit.")
        }
    }

    private fun validateNode(
        node: LogicNodeModel,
        method: VisualLogicMethodModel,
        variableTypes: Map<String, String>,
        diagnostics: MutableList<LogicDiagnostic>,
    ) {
        fun error(code: String, message: String) {
            diagnostics += LogicDiagnostic(code, LogicDiagnosticSeverity.ERROR, message, method.name, node.id)
        }
        fun requireVariable() {
            if (node.resultVariable.isNullOrBlank()) error("LOGIC_RESULT_REQUIRED", "Node requires a result variable.")
        }
        fun requireEntity() {
            if (!isSafeJavaType(node.entityClass.orEmpty())) error("LOGIC_ENTITY_REQUIRED", "Node requires a valid entity class.")
        }
        when (node.kind) {
            LogicNodeKind.START -> Unit
            LogicNodeKind.RETURN -> {
                if (method.returnJavaType == "void" && node.value != null) {
                    error("LOGIC_VOID_RETURN_VALUE", "Void method return node cannot contain a value.")
                }
                if (method.returnJavaType != "void" && node.value == null) {
                    error("LOGIC_RETURN_VALUE_REQUIRED", "Non-void method return node requires a value.")
                }
            }
            LogicNodeKind.CONSTANT -> {
                requireVariable()
                if (node.value == null) error("LOGIC_VALUE_REQUIRED", "Constant node requires a value.")
            }
            LogicNodeKind.CREATE_ENTITY -> {
                requireVariable()
                requireEntity()
                node.fieldValues.forEach {
                    if (it.name.isBlank()) error("LOGIC_PROPERTY_REQUIRED", "Initial entity property cannot be blank.")
                }
            }
            LogicNodeKind.LOAD_ENTITY_BY_ID -> {
                requireVariable()
                requireEntity()
                if (node.value == null) error("LOGIC_ID_REQUIRED", "Load-by-id node requires an id value.")
            }
            LogicNodeKind.LOAD_ENTITIES -> {
                requireVariable()
                requireEntity()
                val query = node.jpql.orEmpty().trim()
                if (!query.startsWith("select ", ignoreCase = true)) {
                    error("LOGIC_QUERY_INVALID", "DataManager query must be a JPQL select query.")
                }
                if (node.maxResults != null && node.maxResults !in 1..100_000) {
                    error("LOGIC_QUERY_LIMIT_INVALID", "Query maximum results must be between 1 and 100,000.")
                }
                node.queryParameters.forEach {
                    if (!IDENTIFIER.matches(it.name)) error("LOGIC_QUERY_PARAMETER_INVALID", "Invalid query parameter: ${it.name}.")
                }
            }
            LogicNodeKind.SET_PROPERTY -> {
                if (node.targetVariable.isNullOrBlank()) error("LOGIC_TARGET_REQUIRED", "Set-property node requires a target variable.")
                if (node.propertyPath.isNullOrBlank()) error("LOGIC_PROPERTY_REQUIRED", "Set-property node requires a property path.")
                if (node.value == null) error("LOGIC_VALUE_REQUIRED", "Set-property node requires a value.")
            }
            LogicNodeKind.SAVE_ENTITY,
            LogicNodeKind.REMOVE_ENTITY,
            -> if (node.targetVariable.isNullOrBlank()) error("LOGIC_TARGET_REQUIRED", "Persistence node requires a target variable.")
            LogicNodeKind.CALL_SERVICE -> {
                if (!isSafeJavaType(node.beanClass.orEmpty())) error("LOGIC_BEAN_CLASS_REQUIRED", "Call node requires a valid service bean class.")
                if (!IDENTIFIER.matches(beanFieldName(node))) error("LOGIC_BEAN_FIELD_INVALID", "Call node bean field is invalid.")
                if (!IDENTIFIER.matches(node.methodName.orEmpty())) error("LOGIC_BEAN_METHOD_REQUIRED", "Call node requires a valid method name.")
                if (!node.resultVariable.isNullOrBlank() && !isSafeJavaType(node.resultJavaType.orEmpty())) {
                    error("LOGIC_RESULT_TYPE_REQUIRED", "Service result variable requires a valid Java type.")
                }
            }
            LogicNodeKind.CALL_SUBFLOW -> {
                if (node.subflowMethod.isNullOrBlank()) {
                    error("LOGIC_SUBFLOW_REQUIRED", "Call-subflow node requires a reusable method.")
                }
            }
            LogicNodeKind.FOR_EACH -> {
                requireVariable()
                if (node.value == null) {
                    error("LOGIC_COLLECTION_REQUIRED", "For-each node requires a collection value.")
                }
                if (!isSafeJavaType(node.resultJavaType.orEmpty())) {
                    error("LOGIC_ITEM_TYPE_REQUIRED", "For-each item requires a safe Java type.")
                }
                node.indexVariable?.takeIf(String::isNotBlank)?.let { index ->
                    if (!IDENTIFIER.matches(index)) {
                        error("LOGIC_INDEX_VARIABLE_INVALID", "For-each index variable is not a valid Java identifier.")
                    }
                }
            }
            LogicNodeKind.TRY_CATCH -> {
                if (node.subflowMethod.isNullOrBlank()) {
                    error("LOGIC_SUBFLOW_REQUIRED", "Try/catch node requires a try subflow.")
                }
            }
            LogicNodeKind.CONDITION,
            LogicNodeKind.REQUIRE,
            -> {
                val condition = node.condition
                if (condition == null) {
                    error("LOGIC_CONDITION_REQUIRED", "Node requires a typed condition.")
                } else if (condition.operator in BINARY_CONDITION_OPERATORS && condition.right == null) {
                    error("LOGIC_CONDITION_RIGHT_REQUIRED", "Binary condition requires a right-hand value.")
                } else if (condition.operator !in BINARY_CONDITION_OPERATORS && condition.right != null) {
                    error("LOGIC_CONDITION_RIGHT_UNEXPECTED", "Unary condition cannot contain a right-hand value.")
                }
            }
            LogicNodeKind.AUTHORIZE_ENTITY -> {
                requireEntity()
                if (node.entityOperation == null) error("LOGIC_ENTITY_OPERATION_REQUIRED", "Authorization node requires an entity operation.")
            }
            LogicNodeKind.THROW -> if (node.message.isNullOrBlank()) error("LOGIC_MESSAGE_REQUIRED", "Throw node requires a message.")
            LogicNodeKind.LOG -> if (node.message.isNullOrBlank()) error("LOGIC_MESSAGE_REQUIRED", "Log node requires a message.")
        }
        node.resultJavaType?.takeIf(String::isNotBlank)?.let {
            if (!isSafeJavaType(it)) error("LOGIC_RESULT_TYPE_INVALID", "Result Java type is unsafe.")
        }
        producedVariables(node, variableTypes).forEach { (name, _) ->
            if (name in variableTypes && !(node.kind == LogicNodeKind.SAVE_ENTITY && name == node.targetVariable)) {
                error("LOGIC_VARIABLE_DUPLICATE", "Variable '$name' already exists.")
            }
        }
    }

    private fun renderMethod(method: VisualLogicMethodModel): String {
        val outgoing = method.transitions.groupBy(LogicTransitionModel::sourceNodeId)
        val start = method.nodes.single { it.kind == LogicNodeKind.START }
        val variables = linkedMapOf<String, String>()
        method.parameters.forEach { variables[it.name] = it.javaType }
        method.nodes.forEach { node ->
            producedVariables(node, variables).forEach { (name, type) ->
                variables.putIfAbsent(name, type)
            }
        }
        val iteratorVariables = method.nodes
            .filter { it.kind == LogicNodeKind.FOR_EACH }
            .sortedBy(LogicNodeModel::id)
            .mapIndexed { index, node -> node.id to "__iterator_$index" }
            .toMap()
        return buildString {
            method.description.takeIf(String::isNotBlank)?.let {
                append("    /** ").append(safeComment(it)).append(" */\n")
            }
            if (method.kind == LogicMethodKind.ENTRY_POINT && method.transaction.enabled) {
                append("    @Transactional(")
                val attributes = buildList {
                    if (method.transaction.readOnly) add("readOnly = true")
                    if (method.transaction.propagation.name != "REQUIRED") {
                        add("propagation = Propagation.${method.transaction.propagation.name}")
                    }
                    if (method.transaction.isolation.name != "DEFAULT") {
                        add("isolation = Isolation.${method.transaction.isolation.name}")
                    }
                    method.transaction.timeoutSeconds?.let { add("timeout = $it") }
                }
                append(attributes.joinToString())
                append(")\n")
            }
            if (method.kind == LogicMethodKind.SUBFLOW) {
                append("    @SuppressWarnings(\"JVW-VISUAL-SUBFLOW\")\n")
            }
            append("    ")
                .append(if (method.kind == LogicMethodKind.ENTRY_POINT) "public " else "private ")
                .append(method.returnJavaType).append(' ').append(method.name).append('(')
            append(method.parameters.joinToString { "${it.javaType} ${it.name}" })
            append(") {\n")
            variables.filterKeys { variable -> method.parameters.none { it.name == variable } }
                .forEach { (name, type) ->
                    append("        ").append(boxedType(type)).append(' ').append(name).append(" = null;\n")
                }
            iteratorVariables.values.forEach { iterator ->
                append("        java.util.Iterator<?> ").append(iterator).append(" = null;\n")
            }
            append("        String __node = ").append(javaString(start.id)).append(";\n")
            append("        int __executions = 0;\n")
            append("        while (__node != null) {\n")
            append("            if (++__executions > ").append(method.maximumExecutions).append(") {\n")
            append("                throw new IllegalStateException(")
                .append(javaString("Visual logic execution limit exceeded in ${method.name}."))
                .append(");\n")
            append("            }\n")
            append("            switch (__node) {\n")
            method.nodes.sortedBy(LogicNodeModel::id).forEach { node ->
                append("                case ").append(javaString(node.id)).append(" -> {\n")
                append(
                    renderNode(
                        node,
                        outgoing[node.id].orEmpty(),
                        iteratorVariables[node.id],
                    ).prependIndent("                    "),
                )
                append("                }\n")
            }
            append("                default -> throw new IllegalStateException(\"Unknown visual logic node: \" + __node);\n")
            append("            }\n")
            append("        }\n")
            if (method.returnJavaType == "void") {
                append("        return;\n")
            } else {
                append("        throw new IllegalStateException(")
                    .append(javaString("Visual logic method ended without returning a value: ${method.name}."))
                    .append(");\n")
            }
            append("    }\n")
        }
    }

    private fun renderNode(
        node: LogicNodeModel,
        outgoing: List<LogicTransitionModel>,
        iteratorVariable: String?,
    ): String =
        buildString {
            when (node.kind) {
                LogicNodeKind.START -> appendNext(outgoing.single().targetNodeId)
                LogicNodeKind.RETURN -> {
                    if (node.value == null) append("return;\n")
                    else append("return ").append(renderValue(node.value)).append(";\n")
                }
                LogicNodeKind.CONSTANT -> {
                    append(node.resultVariable).append(" = ").append(renderValue(requireNotNull(node.value))).append(";\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.CREATE_ENTITY -> {
                    append(node.resultVariable).append(" = dataManager.create(").append(node.entityClass).append(".class);\n")
                    node.fieldValues.forEach { field ->
                        append("EntityValues.setValue(").append(node.resultVariable).append(", ")
                            .append(javaString(field.name)).append(", ").append(renderValue(field.value)).append(");\n")
                    }
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.LOAD_ENTITY_BY_ID -> {
                    append(node.resultVariable).append(" = dataManager.load(").append(node.entityClass)
                        .append(".class).id(").append(renderValue(requireNotNull(node.value))).append(").one();\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.LOAD_ENTITIES -> {
                    append(node.resultVariable).append(" = dataManager.load(").append(node.entityClass)
                        .append(".class)\n    .query(").append(javaString(node.jpql.orEmpty())).append(')')
                    node.queryParameters.forEach { parameter ->
                        append("\n    .parameter(").append(javaString(parameter.name)).append(", ")
                            .append(renderValue(parameter.value)).append(')')
                    }
                    node.maxResults?.let { append("\n    .maxResults(").append(it).append(')') }
                    append("\n    .list();\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.SET_PROPERTY -> {
                    append("EntityValues.setValue(").append(node.targetVariable).append(", ")
                        .append(javaString(node.propertyPath.orEmpty())).append(", ")
                        .append(renderValue(requireNotNull(node.value))).append(");\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.SAVE_ENTITY -> {
                    val assignment = node.resultVariable?.takeIf(String::isNotBlank) ?: node.targetVariable
                    append(assignment).append(" = dataManager.save(").append(node.targetVariable).append(");\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.REMOVE_ENTITY -> {
                    append("dataManager.remove(").append(node.targetVariable).append(");\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.CALL_SERVICE -> {
                    node.resultVariable?.takeIf(String::isNotBlank)?.let { append(it).append(" = ") }
                    append(beanFieldName(node)).append('.').append(node.methodName).append('(')
                        .append(node.arguments.joinToString { renderValue(it) }).append(");\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.CALL_SUBFLOW -> {
                    node.resultVariable?.takeIf(String::isNotBlank)?.let { append(it).append(" = ") }
                    append(node.subflowMethod).append('(')
                        .append(node.arguments.joinToString { renderValue(it) }).append(");\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.FOR_EACH -> {
                    val iterator = requireNotNull(iteratorVariable)
                    val itemTarget = outgoing.single {
                        it.branch == LogicTransitionBranch.ITEM
                    }.targetNodeId
                    val doneTarget = outgoing.single {
                        it.branch == LogicTransitionBranch.DONE
                    }.targetNodeId
                    append("if (").append(iterator).append(" == null) {\n")
                    append("    ").append(iterator).append(" = ((java.lang.Iterable<?>) ")
                        .append(renderValue(requireNotNull(node.value))).append(").iterator();\n")
                    node.indexVariable?.takeIf(String::isNotBlank)?.let {
                        append("    ").append(it).append(" = -1;\n")
                    }
                    append("}\n")
                    append("if (").append(iterator).append(".hasNext()) {\n")
                    append("    ").append(node.resultVariable).append(" = (")
                        .append(node.resultJavaType).append(") ").append(iterator).append(".next();\n")
                    node.indexVariable?.takeIf(String::isNotBlank)?.let {
                        append("    ").append(it).append(" = ").append(it).append(" + 1;\n")
                    }
                    append("    __node = ").append(javaString(itemTarget)).append(";\n")
                    append("} else {\n")
                    append("    ").append(iterator).append(" = null;\n")
                    append("    __node = ").append(javaString(doneTarget)).append(";\n")
                    append("}\n")
                }
                LogicNodeKind.TRY_CATCH -> {
                    append("try {\n")
                    append("    ")
                    node.resultVariable?.takeIf(String::isNotBlank)?.let { append(it).append(" = ") }
                    append(node.subflowMethod).append('(')
                        .append(node.arguments.joinToString { renderValue(it) }).append(");\n")
                    append("}")
                    node.catchMethod?.takeIf(String::isNotBlank)?.let { catchMethod ->
                        append(" catch (").append(node.exceptionType).append(" __error) {\n")
                        append("    ")
                        node.resultVariable?.takeIf(String::isNotBlank)?.let { append(it).append(" = ") }
                        append(catchMethod).append('(')
                        append(
                            (node.arguments.map(::renderValue) + "__error").joinToString(),
                        )
                        append(");\n")
                        append("}")
                    }
                    node.finallyMethod?.takeIf(String::isNotBlank)?.let { finallyMethod ->
                        append(" finally {\n")
                        append("    ").append(finallyMethod).append('(')
                            .append(node.arguments.joinToString { renderValue(it) }).append(");\n")
                        append("}")
                    }
                    append('\n')
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.CONDITION -> {
                    val trueTarget = outgoing.single { it.branch == LogicTransitionBranch.TRUE }.targetNodeId
                    val falseTarget = outgoing.single { it.branch == LogicTransitionBranch.FALSE }.targetNodeId
                    append("__node = ").append(renderCondition(requireNotNull(node.condition)))
                        .append(" ? ").append(javaString(trueTarget))
                        .append(" : ").append(javaString(falseTarget)).append(";\n")
                }
                LogicNodeKind.REQUIRE -> {
                    append("if (!(").append(renderCondition(requireNotNull(node.condition))).append(")) {\n")
                    append("    throw new IllegalArgumentException(")
                        .append(javaString(node.message?.takeIf(String::isNotBlank) ?: "Business validation failed."))
                        .append(");\n")
                    append("}\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.AUTHORIZE_ENTITY -> {
                    append("CrudEntityContext accessContext = new CrudEntityContext(metadata.getClass(")
                        .append(node.entityClass).append(".class));\n")
                    append("accessManager.applyRegisteredConstraints(accessContext);\n")
                    append("if (!accessContext.").append(permissionGetter(requireNotNull(node.entityOperation))).append("()) {\n")
                    append("    throw new AccessDeniedException(")
                        .append(javaString("${node.entityOperation} access denied for ${node.entityClass}."))
                        .append(");\n")
                    append("}\n")
                    appendNext(outgoing.single().targetNodeId)
                }
                LogicNodeKind.THROW -> append("throw new ")
                    .append(node.resultJavaType?.takeIf(String::isNotBlank) ?: "java.lang.IllegalStateException")
                    .append('(').append(javaString(node.message.orEmpty())).append(");\n")
                LogicNodeKind.LOG -> {
                    append("log.").append(node.logLevel.name.lowercase()).append('(')
                        .append(javaString(node.message.orEmpty()))
                    node.arguments.forEach { append(", ").append(renderValue(it)) }
                    append(");\n")
                    appendNext(outgoing.single().targetNodeId)
                }
            }
        }

    private fun StringBuilder.appendNext(target: String) {
        append("__node = ").append(javaString(target)).append(";\n")
    }

    private fun renderCondition(condition: LogicConditionModel): String {
        val left = renderValue(condition.left)
        val right = condition.right?.let(::renderValue) ?: "null"
        return when (condition.operator) {
            LogicConditionOperator.EQUALS -> "Objects.equals($left, $right)"
            LogicConditionOperator.NOT_EQUALS -> "!Objects.equals($left, $right)"
            LogicConditionOperator.NULL -> "$left == null"
            LogicConditionOperator.NOT_NULL -> "$left != null"
            LogicConditionOperator.TRUE -> "Boolean.TRUE.equals($left)"
            LogicConditionOperator.FALSE -> "Boolean.FALSE.equals($left)"
            LogicConditionOperator.GREATER_THAN -> "compareValues($left, $right) > 0"
            LogicConditionOperator.GREATER_THAN_OR_EQUAL -> "compareValues($left, $right) >= 0"
            LogicConditionOperator.LESS_THAN -> "compareValues($left, $right) < 0"
            LogicConditionOperator.LESS_THAN_OR_EQUAL -> "compareValues($left, $right) <= 0"
            LogicConditionOperator.CONTAINS -> "containsValue($left, $right)"
        }
    }

    private fun renderValue(value: LogicValueModel): String =
        when (value.source) {
            LogicValueSource.PARAMETER,
            LogicValueSource.VARIABLE,
            -> value.value.orEmpty()
            LogicValueSource.NULL -> "null"
            LogicValueSource.LITERAL -> when (value.type) {
                LogicValueType.STRING -> javaString(value.value.orEmpty())
                LogicValueType.INTEGER -> "Integer.valueOf(${javaString(value.value.orEmpty())})"
                LogicValueType.LONG -> "Long.valueOf(${javaString(value.value.orEmpty())})"
                LogicValueType.DECIMAL -> "new BigDecimal(${javaString(value.value.orEmpty())})"
                LogicValueType.BOOLEAN -> "Boolean.valueOf(${javaString(value.value.orEmpty())})"
                LogicValueType.UUID -> "UUID.fromString(${javaString(value.value.orEmpty())})"
                LogicValueType.LOCAL_DATE -> "LocalDate.parse(${javaString(value.value.orEmpty())})"
                LogicValueType.LOCAL_DATE_TIME -> "LocalDateTime.parse(${javaString(value.value.orEmpty())})"
                LogicValueType.OFFSET_DATE_TIME -> "OffsetDateTime.parse(${javaString(value.value.orEmpty())})"
                LogicValueType.INSTANT -> "Instant.parse(${javaString(value.value.orEmpty())})"
                LogicValueType.ENUM -> "${value.javaType}.${value.value}"
                LogicValueType.ENTITY,
                LogicValueType.OBJECT,
                -> error("Entity/Object literals are not supported.")
            }
        }

    private fun producedVariables(
        node: LogicNodeModel,
        knownTypes: Map<String, String>,
    ): List<Pair<String, String>> {
        val name = node.resultVariable?.takeIf(String::isNotBlank)
        val type = when (node.kind) {
            LogicNodeKind.CONSTANT -> node.resultJavaType
                ?: node.value?.let(::javaTypeFor)
                ?: "java.lang.Object"
            LogicNodeKind.CREATE_ENTITY,
            LogicNodeKind.LOAD_ENTITY_BY_ID,
            -> node.entityClass.orEmpty()
            LogicNodeKind.LOAD_ENTITIES -> "java.util.List<${node.entityClass}>"
            LogicNodeKind.CALL_SERVICE,
            LogicNodeKind.CALL_SUBFLOW,
            LogicNodeKind.TRY_CATCH,
            -> node.resultJavaType.orEmpty()
            LogicNodeKind.FOR_EACH -> node.resultJavaType.orEmpty()
            LogicNodeKind.SAVE_ENTITY -> node.resultJavaType
                ?: node.targetVariable?.let(knownTypes::get)
                ?: "java.lang.Object"
            else -> null
        }
        return buildList {
            if (name != null && type != null) add(name to type)
            if (node.kind == LogicNodeKind.FOR_EACH) {
                node.indexVariable?.takeIf(String::isNotBlank)?.let {
                    add(it to "java.lang.Integer")
                }
            }
        }
    }

    private fun methodVariableTypes(method: VisualLogicMethodModel): Map<String, String> {
        val variables = linkedMapOf<String, String>()
        method.parameters.forEach { variables[it.name] = it.javaType }
        method.nodes.forEach { node ->
            producedVariables(node, variables).forEach { (name, type) ->
                variables.putIfAbsent(name, type)
            }
        }
        return variables
    }

    private fun valueJavaType(
        value: LogicValueModel,
        variables: Map<String, String>,
    ): String? = when (value.source) {
        LogicValueSource.PARAMETER,
        LogicValueSource.VARIABLE,
        -> value.value?.let(variables::get)
        LogicValueSource.LITERAL -> javaTypeFor(value).takeIf(String::isNotBlank)
        LogicValueSource.NULL -> null
    }

    private fun sameJavaType(left: String, right: String): Boolean {
        fun normalized(type: String): String {
            val aliases = mapOf(
                "String" to "java.lang.String",
                "Integer" to "java.lang.Integer",
                "Long" to "java.lang.Long",
                "Boolean" to "java.lang.Boolean",
                "Object" to "java.lang.Object",
                "BigDecimal" to "java.math.BigDecimal",
                "UUID" to "java.util.UUID",
            )
            val compact = type.filterNot(Char::isWhitespace)
            return aliases[compact] ?: compact
        }
        return normalized(boxedType(left)) == normalized(boxedType(right))
    }

    private fun referencedValues(node: LogicNodeModel): List<LogicValueModel> = buildList {
        node.value?.let(::add)
        node.fieldValues.forEach { add(it.value) }
        node.queryParameters.forEach { add(it.value) }
        node.arguments.forEach(::add)
        node.condition?.let { condition ->
            add(condition.left)
            condition.right?.let(::add)
        }
    }

    private fun validateLiteral(value: LogicValueModel): String? {
        if (value.source != LogicValueSource.LITERAL) return null
        val raw = value.value.orEmpty()
        return runCatching {
            when (value.type) {
                LogicValueType.STRING -> Unit
                LogicValueType.INTEGER -> raw.toInt()
                LogicValueType.LONG -> raw.toLong()
                LogicValueType.DECIMAL -> BigDecimal(raw)
                LogicValueType.BOOLEAN -> require(raw == "true" || raw == "false")
                LogicValueType.UUID -> UUID.fromString(raw)
                LogicValueType.LOCAL_DATE -> LocalDate.parse(raw)
                LogicValueType.LOCAL_DATE_TIME -> LocalDateTime.parse(raw)
                LogicValueType.OFFSET_DATE_TIME -> OffsetDateTime.parse(raw)
                LogicValueType.INSTANT -> Instant.parse(raw)
                LogicValueType.ENUM -> {
                    require(isSafeJavaType(value.javaType.orEmpty()))
                    require(ENUM_CONSTANT.matches(raw))
                }
                LogicValueType.ENTITY,
                LogicValueType.OBJECT,
                -> error("Entity/Object literals are not supported.")
            }
        }.exceptionOrNull()?.let { "Literal '${value.value}' is invalid for ${value.type}." }
    }

    private fun javaTypeFor(value: LogicValueModel): String =
        when (value.type) {
            LogicValueType.STRING -> "java.lang.String"
            LogicValueType.INTEGER -> "java.lang.Integer"
            LogicValueType.LONG -> "java.lang.Long"
            LogicValueType.DECIMAL -> "java.math.BigDecimal"
            LogicValueType.BOOLEAN -> "java.lang.Boolean"
            LogicValueType.UUID -> "java.util.UUID"
            LogicValueType.LOCAL_DATE -> "java.time.LocalDate"
            LogicValueType.LOCAL_DATE_TIME -> "java.time.LocalDateTime"
            LogicValueType.OFFSET_DATE_TIME -> "java.time.OffsetDateTime"
            LogicValueType.INSTANT -> "java.time.Instant"
            LogicValueType.ENUM,
            LogicValueType.ENTITY,
            -> value.javaType.orEmpty()
            LogicValueType.OBJECT -> "java.lang.Object"
        }

    private fun beanFieldName(node: LogicNodeModel): String =
        node.beanFieldName?.takeIf(String::isNotBlank)
            ?: node.beanClass.orEmpty().substringAfterLast('.').replaceFirstChar(Char::lowercase)

    private fun permissionGetter(operation: LogicEntityOperation): String =
        when (operation) {
            LogicEntityOperation.CREATE -> "isCreatePermitted"
            LogicEntityOperation.READ -> "isReadPermitted"
            LogicEntityOperation.UPDATE -> "isUpdatePermitted"
            LogicEntityOperation.DELETE -> "isDeletePermitted"
        }

    private val BINARY_CONDITION_OPERATORS = setOf(
        LogicConditionOperator.EQUALS,
        LogicConditionOperator.NOT_EQUALS,
        LogicConditionOperator.GREATER_THAN,
        LogicConditionOperator.GREATER_THAN_OR_EQUAL,
        LogicConditionOperator.LESS_THAN,
        LogicConditionOperator.LESS_THAN_OR_EQUAL,
        LogicConditionOperator.CONTAINS,
    )

    private fun boxedType(type: String): String =
        when (type.trim()) {
            "boolean" -> "Boolean"
            "byte" -> "Byte"
            "short" -> "Short"
            "int" -> "Integer"
            "long" -> "Long"
            "float" -> "Float"
            "double" -> "Double"
            "char" -> "Character"
            else -> type
        }

    private fun isSafeJavaType(value: String): Boolean {
        val type = value.trim()
        if (type == "void") return true
        if (type.isBlank() || type.length > 500) return false
        if (type.any { it !in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.$?<>[], " }) return false
        var depth = 0
        type.forEach { character ->
            when (character) {
                '<' -> depth += 1
                '>' -> {
                    depth -= 1
                    if (depth < 0) return false
                }
            }
        }
        if (depth != 0 || ".." in type || type.startsWith('.') || type.endsWith('.')) return false
        return type.split(Regex("""[.<>,?\[\]\s]+"""))
            .filter(String::isNotBlank)
            .filterNot { it in setOf("extends", "super") }
            .all(IDENTIFIER::matches)
    }

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
                    else -> if (character.code < 32) {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else append(character)
                }
            }
            append('"')
        }

    private fun safeComment(value: String): String =
        value.replace("*/", "* /").replace(Regex("""\s+"""), " ").trim()

    private data class BeanSpec(
        val javaType: String,
        val fieldName: String,
    )

    private val COMPARISON_OPERATORS = setOf(
        LogicConditionOperator.GREATER_THAN,
        LogicConditionOperator.GREATER_THAN_OR_EQUAL,
        LogicConditionOperator.LESS_THAN,
        LogicConditionOperator.LESS_THAN_OR_EQUAL,
        LogicConditionOperator.CONTAINS,
    )

    private val COMPARISON_HELPERS = """

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compareValues(Object left, Object right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("Cannot compare null visual-logic values.");
        }
        if (left instanceof Number && right instanceof Number) {
            BigDecimal leftNumber = new BigDecimal(left.toString());
            BigDecimal rightNumber = new BigDecimal(right.toString());
            return leftNumber.compareTo(rightNumber);
        }
        if (left instanceof Comparable && left.getClass().isInstance(right)) {
            return ((Comparable) left).compareTo(right);
        }
        throw new IllegalArgumentException(
                "Values are not mutually comparable: " + left.getClass().getName()
                        + " and " + right.getClass().getName()
        );
    }

    private static boolean containsValue(Object container, Object expected) {
        if (container == null) {
            return false;
        }
        if (container instanceof CharSequence) {
            return ((CharSequence) container).toString().contains(String.valueOf(expected));
        }
        if (container instanceof java.util.Collection<?>) {
            return ((java.util.Collection<?>) container).contains(expected);
        }
        if (container instanceof java.util.Map<?, ?>) {
            return ((java.util.Map<?, ?>) container).containsKey(expected)
                    || ((java.util.Map<?, ?>) container).containsValue(expected);
        }
        throw new IllegalArgumentException("Value does not support contains: " + container.getClass().getName());
    }
"""
}

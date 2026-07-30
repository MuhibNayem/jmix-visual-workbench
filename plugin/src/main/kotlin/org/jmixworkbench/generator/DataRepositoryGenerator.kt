package org.jmixworkbench.generator

import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.QueryType
import org.jmixworkbench.model.RepositoryMethod
import org.jmixworkbench.model.RepositoryParameterRole

/**
 * Generates Jmix DataManager-backed repository interfaces.
 *
 * The generator deliberately accepts only contracts documented by Jmix.
 * In particular, a Spring Data JPA native-query annotation is not emitted on a
 * JmixDataRepository: such a method would look valid while bypassing the
 * DataManager repository implementation and its security/fetch-plan semantics.
 */
object DataRepositoryGenerator {

    fun generate(entity: EntityModel): String {
        val config = entity.dataRepository ?: DataRepositoryConfig(enabled = true)
        val repositoryName = config.interfaceName?.trim().orEmpty()
            .ifBlank { "${entity.className}Repository" }
        RepositoryContract.validate(entity, config, repositoryName)

        val imports = linkedSetOf(
            "io.jmix.core.repository.JmixDataRepository",
            "org.springframework.stereotype.Repository",
        )
        val idType = RepositoryContract.idType(entity, kotlin = false, imports)
        config.methods.forEach { method ->
            RepositoryContract.collectMethodImports(method, imports, kotlin = false)
        }
        val qualifyFetchPlanAnnotation = "io.jmix.core.FetchPlan" in imports
        if (qualifyFetchPlanAnnotation) {
            imports -= "io.jmix.core.repository.FetchPlan"
        }
        if (!config.applyConstraints || config.methods.any { it.applyConstraints != null }) {
            imports += "io.jmix.core.repository.ApplyConstraints"
        }

        return buildString {
            append("package ").append(entity.packageName).append(";\n\n")
            imports.sorted().forEach { append("import ").append(it).append(";\n") }
            append("\n@Repository\n")
            if (!config.applyConstraints) append("@ApplyConstraints(false)\n")
            append("public interface ").append(repositoryName)
                .append(" extends JmixDataRepository<")
                .append(entity.className).append(", ").append(idType).append("> {\n")
            config.methods.forEach { method ->
                append('\n')
                appendJavaMethod(method, config, qualifyFetchPlanAnnotation)
            }
            append("}\n")
        }
    }

    private fun StringBuilder.appendJavaMethod(
        method: RepositoryMethod,
        config: DataRepositoryConfig,
        qualifyFetchPlanAnnotation: Boolean,
    ) {
        method.description?.trim()?.takeIf(String::isNotEmpty)?.let { description ->
            append("    /**\n")
            description.replace("*/", "* /").lines().forEach {
                append("     * ").append(it.trimEnd()).append('\n')
            }
            append("     */\n")
        }
        if (method.queryType == QueryType.JPQL) {
            append("    @Query(")
            if (method.queryProperties.isEmpty()) {
                append(RepositoryContract.javaString(requireNotNull(method.query).trim()))
            } else {
                append("value = ")
                    .append(RepositoryContract.javaString(requireNotNull(method.query).trim()))
                    .append(", properties = {")
                    .append(method.queryProperties.joinToString(", ") {
                        RepositoryContract.javaString(it.trim())
                    })
                    .append("}")
            }
            append(")\n")
        }
        method.fetchPlan?.trim()?.takeIf(String::isNotEmpty)?.let {
            append("    @")
                .append(
                    if (qualifyFetchPlanAnnotation) {
                        "io.jmix.core.repository.FetchPlan"
                    } else {
                        "FetchPlan"
                    },
                )
                .append('(')
                .append(RepositoryContract.javaString(it))
                .append(")\n")
        }
        method.applyConstraints?.let {
            append("    @ApplyConstraints(").append(it).append(")\n")
        }
        if (method.queryHints.isNotEmpty()) {
            append("    @QueryHints({")
            append(method.queryHints.joinToString(", ") {
                "@QueryHint(name = ${RepositoryContract.javaString(it.name.trim())}, " +
                    "value = ${RepositoryContract.javaString(it.value.trim())})"
            })
            append("})\n")
        }
        append("    ")
            .append(RepositoryContract.javaType(method.returnType))
            .append(' ')
            .append(method.name.trim())
            .append('(')
        append(method.parameters.joinToString(", ") { parameter ->
            buildString {
                if (parameter.nullable) append("@Nullable ")
                val binding = RepositoryContract.bindingFor(method, parameter, config)
                if (binding != null) {
                    append("@Param(")
                        .append(RepositoryContract.javaString(binding))
                        .append(") ")
                }
                append(
                    RepositoryContract.javaType(
                        RepositoryContract.parameterType(parameter, kotlin = false),
                    ),
                )
                append(' ').append(parameter.name.trim())
            }
        })
        append(");\n")
    }
}

internal object RepositoryContract {
    private val identifier = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val typeExpression = Regex(
        """[A-Za-z_$][A-Za-z0-9_$.]*(?:\s*<\s*[A-Za-z0-9_$.,?<>\[\]\s]+\s*>)?(?:\[\])?""",
    )
    private val namedParameter = Regex("""(?<!:):([A-Za-z_$][A-Za-z0-9_$]*)""")
    private val positionalParameter = Regex("""\?([1-9][0-9]*)""")
    private val fetchPlanName = Regex("""[A-Za-z_$][A-Za-z0-9_$.:/-]*""")
    private val knownImports = linkedMapOf(
        "UUID" to "java.util.UUID",
        "List" to "java.util.List",
        "Set" to "java.util.Set",
        "Collection" to "java.util.Collection",
        "Optional" to "java.util.Optional",
        "Stream" to "java.util.stream.Stream",
        "BigDecimal" to "java.math.BigDecimal",
        "Page" to "org.springframework.data.domain.Page",
        "Slice" to "org.springframework.data.domain.Slice",
        "Pageable" to "org.springframework.data.domain.Pageable",
        "Sort" to "org.springframework.data.domain.Sort",
        "FetchPlan" to "io.jmix.core.FetchPlan",
        "JmixDataRepositoryContext" to "io.jmix.core.repository.JmixDataRepositoryContext",
        "KeyValueEntity" to "io.jmix.core.entity.KeyValueEntity",
    )

    fun validate(
        entity: EntityModel,
        config: DataRepositoryConfig,
        repositoryName: String,
    ) {
        require(entity.entityType.name == "ENTITY") {
            "JVW-REPOSITORY-ENTITY: data repositories can be generated only for persistent JPA entities."
        }
        require(identifier.matches(repositoryName)) {
            "JVW-REPOSITORY-NAME: '$repositoryName' is not a valid repository interface name."
        }
        val signatures = linkedSetOf<String>()
        config.methods.forEachIndexed { index, method ->
            validateMethod(entity, config, method, index)
            val signature = method.name.trim() + "(" +
                method.parameters.joinToString(",") { parameterType(it, kotlin = false) } + ")"
            require(signatures.add(signature)) {
                "JVW-REPOSITORY-DUPLICATE: duplicate repository method signature '$signature'."
            }
        }
    }

    private fun validateMethod(
        entity: EntityModel,
        config: DataRepositoryConfig,
        method: RepositoryMethod,
        index: Int,
    ) {
        val prefix = "JVW-REPOSITORY-METHOD-${index + 1}"
        require(identifier.matches(method.name.trim())) {
            "$prefix-NAME: '${method.name}' is not a valid method name."
        }
        require(validType(method.returnType)) {
            "$prefix-RETURN: '${method.returnType}' is not a safe JVM return type."
        }
        require(method.parameters.map { it.name.trim() }.distinct().size == method.parameters.size) {
            "$prefix-PARAMETER-DUPLICATE: parameter names must be unique."
        }
        method.parameters.forEach { parameter ->
            require(identifier.matches(parameter.name.trim())) {
                "$prefix-PARAMETER-NAME: '${parameter.name}' is not a valid parameter name."
            }
            require(validType(parameterType(parameter, kotlin = false))) {
                "$prefix-PARAMETER-TYPE: '${parameter.type}' is not a safe JVM parameter type."
            }
        }
        RepositoryParameterRole.entries
            .filter { it != RepositoryParameterRole.VALUE }
            .forEach { role ->
                require(method.parameters.count { it.role == role } <= 1) {
                    "$prefix-PARAMETER-ROLE: only one ${role.name.lowercase()} parameter is allowed."
                }
            }
        require(method.parameters.none {
            it.nullable && it.role in setOf(
                RepositoryParameterRole.PAGEABLE,
                RepositoryParameterRole.SORT,
                RepositoryParameterRole.CONTEXT,
            )
        }) {
            "$prefix-PARAMETER-NULLABILITY: paging, sorting, and repository-context parameters cannot be nullable."
        }
        if (
            javaType(method.returnType).startsWith("Page<") ||
            javaType(method.returnType).startsWith("Slice<")
        ) {
            require(method.parameters.any { it.role == RepositoryParameterRole.PAGEABLE }) {
                "$prefix-PAGING: Page and Slice results require a Pageable parameter."
            }
        }
        require(method.fetchPlan.isNullOrBlank() || fetchPlanName.matches(method.fetchPlan.trim())) {
            "$prefix-FETCH-PLAN: fetch-plan names must be stable identifiers."
        }
        require(method.queryHints.map { it.name.trim() }.distinct().size == method.queryHints.size) {
            "$prefix-HINT-DUPLICATE: query-hint names must be unique."
        }
        method.queryHints.forEach {
            require(it.name.isNotBlank() && it.value.isNotBlank()) {
                "$prefix-HINT: query-hint name and value are required."
            }
        }
        when (method.queryType) {
            QueryType.DERIVED -> {
                require(method.query.isNullOrBlank()) {
                    "$prefix-DERIVED-QUERY: a derived method must not contain an explicit query."
                }
                require(method.queryProperties.isEmpty()) {
                    "$prefix-DERIVED-PROPERTIES: aggregate properties belong to explicit JPQL methods."
                }
                require(
                    method.name.startsWith("find") ||
                        method.name.startsWith("get") ||
                        method.name.startsWith("read") ||
                        method.name.startsWith("query") ||
                        method.name.startsWith("search") ||
                        method.name.startsWith("stream") ||
                        method.name.startsWith("count") ||
                        method.name.startsWith("exists") ||
                        method.name.startsWith("delete") ||
                        method.name.startsWith("remove"),
                ) {
                    "$prefix-DERIVED-NAME: use a supported Spring Data query prefix."
                }
            }
            QueryType.JPQL -> validateJpql(entity, config, method, prefix)
            QueryType.NATIVE -> error(
                "$prefix-NATIVE-UNSUPPORTED: native Spring Data JPA queries cannot be attached safely " +
                    "to a JmixDataRepository. Use JPQL or a transactional service/custom repository fragment.",
            )
        }
    }

    private fun validateJpql(
        entity: EntityModel,
        config: DataRepositoryConfig,
        method: RepositoryMethod,
        prefix: String,
    ) {
        val query = method.query?.trim().orEmpty()
        require(query.startsWith("select ", ignoreCase = true)) {
            "$prefix-JPQL-READ-ONLY: repository JPQL must be a read-only SELECT query."
        }
        require(';' !in query) {
            "$prefix-JPQL-STATEMENT: repository JPQL must contain exactly one SELECT statement."
        }
        require(
            Regex("""(?i)\bfrom\s+${Regex.escape(entity.entityName.ifBlank { entity.className })}\b""")
                .containsMatchIn(query) ||
                Regex("""(?i)\bfrom\s+${Regex.escape(entity.className)}\b""").containsMatchIn(query),
        ) {
            "$prefix-JPQL-ENTITY: query must load '${entity.entityName.ifBlank { entity.className }}'."
        }
        val valueParameters = method.parameters.filter {
            it.role == RepositoryParameterRole.VALUE
        }
        if (config.useNamedParameters) {
            val queryBindings = namedParameter.findAll(query).map { it.groupValues[1] }.toSet()
            require(positionalParameter.find(query) == null) {
                "$prefix-JPQL-PARAMETER-MODE: this repository uses named parameters."
            }
            val declared = valueParameters.map {
                it.bindingName?.trim().orEmpty().ifBlank { it.name.trim() }
            }
            require(declared.toSet() == queryBindings && declared.size == queryBindings.size) {
                "$prefix-JPQL-PARAMETERS: query bindings ${queryBindings.sorted()} must exactly match " +
                    "method bindings ${declared.sorted()}."
            }
        } else {
            require(namedParameter.find(query) == null) {
                "$prefix-JPQL-PARAMETER-MODE: this repository uses positional parameters."
            }
            val positions = positionalParameter.findAll(query)
                .map { it.groupValues[1].toInt() }
                .toSet()
            require(positions == (1..valueParameters.size).toSet()) {
                "$prefix-JPQL-PARAMETERS: positional parameters must cover ?1 through ?${valueParameters.size}."
            }
        }
        require(method.queryProperties.none(String::isBlank)) {
            "$prefix-JPQL-PROPERTIES: aggregate result property names cannot be blank."
        }
        require(method.queryProperties.distinct().size == method.queryProperties.size) {
            "$prefix-JPQL-PROPERTIES: aggregate result property names must be unique."
        }
        if (method.queryProperties.size > 1) {
            require(javaType(method.returnType).contains("KeyValueEntity")) {
                "$prefix-JPQL-KEY-VALUE: multiple selected scalar properties require KeyValueEntity results."
            }
        }
    }

    fun collectMethodImports(
        method: RepositoryMethod,
        imports: MutableSet<String>,
        kotlin: Boolean,
    ) {
        collectTypeImports(method.returnType, imports)
        method.parameters.forEach {
            collectTypeImports(parameterType(it, kotlin), imports)
            if (it.nullable && !kotlin) imports += "org.springframework.lang.Nullable"
        }
        if (method.queryType == QueryType.JPQL) {
            imports += "io.jmix.core.repository.Query"
            if (method.parameters.any { bindingFor(method, it, null) != null }) {
                imports += "org.springframework.data.repository.query.Param"
            }
        }
        if (!method.fetchPlan.isNullOrBlank()) imports += "io.jmix.core.repository.FetchPlan"
        if (method.applyConstraints != null) imports += "io.jmix.core.repository.ApplyConstraints"
        if (method.queryHints.isNotEmpty()) {
            imports += "io.jmix.core.repository.QueryHints"
            imports += "jakarta.persistence.QueryHint"
        }
    }

    fun idType(
        entity: EntityModel,
        kotlin: Boolean,
        imports: MutableSet<String>,
    ): String = when (entity.id.type) {
        IdType.UUID -> "UUID".also { imports += "java.util.UUID" }
        IdType.LONG -> "Long"
        IdType.INTEGER -> if (kotlin) "Int" else "Integer"
        IdType.STRING -> "String"
        IdType.EMBEDDED -> requireNotNull(entity.id.embeddedIdClass).also {
            if ('.' in it) imports += it
        }.substringAfterLast('.')
    }

    fun parameterType(
        parameter: org.jmixworkbench.model.MethodParameter,
        kotlin: Boolean,
    ): String = when (parameter.role) {
        RepositoryParameterRole.VALUE -> if (kotlin) kotlinType(parameter.type) else javaType(parameter.type)
        RepositoryParameterRole.PAGEABLE -> "Pageable"
        RepositoryParameterRole.SORT -> "Sort"
        RepositoryParameterRole.FETCH_PLAN -> "FetchPlan"
        RepositoryParameterRole.CONTEXT -> "JmixDataRepositoryContext"
    }

    fun bindingFor(
        method: RepositoryMethod,
        parameter: org.jmixworkbench.model.MethodParameter,
        config: DataRepositoryConfig?,
    ): String? {
        if (
            method.queryType != QueryType.JPQL ||
            parameter.role != RepositoryParameterRole.VALUE
        ) {
            return null
        }
        val query = method.query.orEmpty()
        val named = config?.useNamedParameters
            ?: namedParameter.containsMatchIn(query)
        if (!named) return null
        return parameter.bindingName?.trim().orEmpty().ifBlank { parameter.name.trim() }
    }

    fun javaType(value: String): String = simplifyType(value.trim())

    fun kotlinType(value: String): String = simplifyType(value.trim())
        .replace(Regex("""\bInteger\b"""), "Int")
        .replace(Regex("""\bCharacter\b"""), "Char")

    fun javaString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    fun kotlinString(value: String): String = javaString(value)

    private fun validType(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 240 &&
            typeExpression.matches(value.trim()) &&
            !value.contains(';') &&
            !value.contains('{') &&
            !value.contains('}')

    private fun collectTypeImports(type: String, imports: MutableSet<String>) {
        knownImports.forEach { (simple, qualified) ->
            if (Regex("""\b${Regex.escape(simple)}\b""").containsMatchIn(type)) {
                imports += qualified
            }
        }
        Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""")
            .findAll(type)
            .map(MatchResult::value)
            .filterNot { it.startsWith("java.lang.") }
            .forEach(imports::add)
    }

    private fun simplifyType(value: String): String =
        Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""")
            .replace(value) { it.value.substringAfterLast('.') }
}

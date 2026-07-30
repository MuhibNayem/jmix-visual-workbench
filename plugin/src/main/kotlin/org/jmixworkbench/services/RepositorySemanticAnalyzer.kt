package org.jmixworkbench.services

import com.google.gson.annotations.SerializedName
import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.QueryType
import org.jmixworkbench.model.RepositoryMethod
import org.jmixworkbench.model.RepositoryParameterRole
import java.util.Locale

/**
 * Entity-aware validation for Jmix DataManager-backed repository methods.
 *
 * This deliberately does not depend on Spring Data implementation classes.
 * Target projects may use different Jmix/Spring generations, while the
 * documented query-name grammar and JPA property semantics remain stable.
 */
internal object RepositorySemanticAnalyzer {

    fun analyze(
        entity: SchemaEntitySnapshot,
        entities: List<SchemaEntitySnapshot>,
        config: DataRepositoryConfig,
    ): RepositorySemanticValidationResponse {
        val graph = RepositoryEntityGraph(entity, entities)
        val diagnostics = mutableListOf<RepositorySemanticDiagnostic>()
        val methodSemantics = config.methods.mapIndexed { index, method ->
            when (method.queryType) {
                QueryType.DERIVED -> analyzeDerived(graph, method, index, diagnostics)
                QueryType.JPQL -> analyzeJpql(graph, method, index, diagnostics)
                QueryType.NATIVE -> RepositoryMethodSemanticSnapshot(
                    methodIndex = index,
                    propertyPaths = emptyList(),
                    expectedValueParameters = 0,
                    resultKind = RepositoryResultKind.UNKNOWN,
                )
            }
        }
        if (!config.applyConstraints) {
            diagnostics += diagnostic(
                RepositorySemanticSeverity.WARNING,
                "JVW-REPOSITORY-SECURITY-BYPASS",
                "Repository-wide @ApplyConstraints(false) bypasses row-level and resource data constraints.",
                field = "applyConstraints",
            )
        }
        config.methods.forEachIndexed { index, method ->
            if (method.applyConstraints == false) {
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.WARNING,
                    "JVW-REPOSITORY-METHOD-SECURITY-BYPASS",
                    "Method '${method.name}' explicitly bypasses Jmix data constraints.",
                    index,
                    "applyConstraints",
                )
            }
        }
        return RepositorySemanticValidationResponse(
            accepted = diagnostics.none { it.severity == RepositorySemanticSeverity.ERROR },
            diagnostics = diagnostics.distinctBy {
                listOf(it.severity, it.code, it.methodIndex, it.field, it.message)
            },
            propertyPaths = graph.paths(),
            methods = methodSemantics,
        )
    }

    private fun analyzeDerived(
        graph: RepositoryEntityGraph,
        method: RepositoryMethod,
        index: Int,
        diagnostics: MutableList<RepositorySemanticDiagnostic>,
    ): RepositoryMethodSemanticSnapshot {
        val parsed = DerivedMethodParser.parse(method.name, graph)
        if (parsed == null) {
            if (DerivedMethodParser.mayResolveNamedQuery(method.name)) {
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.WARNING,
                    "JVW-REPOSITORY-NAMED-QUERY-UNVERIFIED",
                    "Method '${method.name}' has no derived predicate. It may resolve a JPA named query, " +
                        "which is not yet proven by this bounded semantic pass.",
                    index,
                    "name",
                )
                return RepositoryMethodSemanticSnapshot(
                    index,
                    emptyList(),
                    method.parameters.count { it.role == RepositoryParameterRole.VALUE },
                    RepositoryResultKind.UNKNOWN,
                )
            }
            diagnostics += diagnostic(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-DERIVED-SUBJECT",
                "Method '${method.name}' is not a supported Spring Data derived-query name. " +
                    "Use find/read/get/query/search/stream/count/exists/delete/remove … By ….",
                index,
                "name",
            )
            return RepositoryMethodSemanticSnapshot(
                index,
                emptyList(),
                method.parameters.count { it.role == RepositoryParameterRole.VALUE },
                RepositoryResultKind.UNKNOWN,
            )
        }
        parsed.issue?.let { issue ->
            diagnostics += diagnostic(
                RepositorySemanticSeverity.ERROR,
                issue.code,
                issue.message,
                index,
                "name",
                issue.suggestions,
            )
        }
        val atoms = parsed.atoms
        val expectedParameters = atoms.sumOf { it.operator.parameterCount }
        val valueParameters = method.parameters.filter { it.role == RepositoryParameterRole.VALUE }
        if (valueParameters.size != expectedParameters) {
            diagnostics += diagnostic(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-DERIVED-PARAMETER-COUNT",
                "Derived predicates require $expectedParameters value parameter(s), but " +
                    "${valueParameters.size} are declared. Paging, sorting, fetch-plan and context parameters do not count.",
                index,
                "parameters",
            )
        }
        atoms.forEach { atom ->
            validateOperator(atom, index, diagnostics)
        }
        atoms.flatMap { atom ->
            List(atom.operator.parameterCount) { atom }
        }.zip(valueParameters).forEach { (atom, parameter) ->
            if (!compatibleParameter(parameter.type, atom.property, atom.operator)) {
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.ERROR,
                    "JVW-REPOSITORY-DERIVED-PARAMETER-TYPE",
                    "Parameter '${parameter.name}' (${parameter.type}) is incompatible with " +
                        "'${atom.property.path}' (${atom.property.javaType}) for ${atom.operator.label}.",
                    index,
                    "parameters",
                    listOf(atom.property.javaType),
                )
            }
        }
        parsed.orderPaths.forEach { path ->
            if (path.collection) {
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.ERROR,
                    "JVW-REPOSITORY-DERIVED-ORDER-COLLECTION",
                    "Static ordering cannot target collection path '${path.path}'.",
                    index,
                    "name",
                )
            }
        }
        validateDerivedReturn(method, parsed.subject, index, diagnostics)
        if (parsed.subject in setOf("delete", "remove") && method.applyConstraints == false) {
            diagnostics += diagnostic(
                RepositorySemanticSeverity.WARNING,
                "JVW-REPOSITORY-PRIVILEGED-BULK-DELETE",
                "Derived delete '${method.name}' bypasses Jmix constraints. Require an audited service boundary.",
                index,
                "applyConstraints",
            )
        }
        return RepositoryMethodSemanticSnapshot(
            methodIndex = index,
            propertyPaths = (atoms.map { it.property.path } + parsed.orderPaths.map { it.path }).distinct(),
            expectedValueParameters = expectedParameters,
            resultKind = when (parsed.subject) {
                "count" -> RepositoryResultKind.COUNT
                "exists" -> RepositoryResultKind.EXISTS
                "delete", "remove" -> RepositoryResultKind.DELETE
                else -> RepositoryResultKind.ENTITY
            },
        )
    }

    private fun analyzeJpql(
        graph: RepositoryEntityGraph,
        method: RepositoryMethod,
        index: Int,
        diagnostics: MutableList<RepositorySemanticDiagnostic>,
    ): RepositoryMethodSemanticSnapshot {
        val query = method.query.orEmpty()
        val parsed = JpqlSemanticParser.parse(query, graph)
        parsed.diagnostics.forEach { issue ->
            diagnostics += diagnostic(
                issue.severity,
                issue.code,
                issue.message,
                index,
                "query",
                issue.suggestions,
            )
        }
        if (parsed.scalarSelectionCount > 1 &&
            !simpleType(method.returnType).contains("KeyValueEntity")
        ) {
            diagnostics += diagnostic(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-JPQL-RESULT-SHAPE",
                "A multi-column JPQL projection must return KeyValueEntity values.",
                index,
                "returnType",
                listOf("List<KeyValueEntity>"),
            )
        }
        if (parsed.scalarSelectionCount > 1 &&
            method.queryProperties.size != parsed.scalarSelectionCount
        ) {
            diagnostics += diagnostic(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-JPQL-PROPERTY-COUNT",
                "JPQL selects ${parsed.scalarSelectionCount} scalar expressions, but " +
                    "${method.queryProperties.size} aggregate property name(s) are configured.",
                index,
                "queryProperties",
            )
        }
        if (parsed.resultKind != RepositoryResultKind.ENTITY &&
            (
                !method.fetchPlan.isNullOrBlank() ||
                    method.parameters.any { it.role == RepositoryParameterRole.FETCH_PLAN }
                )
        ) {
            diagnostics += diagnostic(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-JPQL-SCALAR-FETCH-PLAN",
                "Fetch plans apply to entity results, not scalar or aggregate JPQL projections.",
                index,
                "fetchPlan",
            )
        }
        parsed.scalarType?.let { selectedType ->
            if (parsed.resultKind == RepositoryResultKind.SCALAR &&
                !compatibleReturn(method.returnType, selectedType)
            ) {
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.ERROR,
                    "JVW-REPOSITORY-JPQL-RETURN-TYPE",
                    "JPQL selects '$selectedType', but '${method.returnType}' is not a compatible result type.",
                    index,
                    "returnType",
                    listOf(selectedType, "List<$selectedType>"),
                )
            }
        }
        return RepositoryMethodSemanticSnapshot(
            methodIndex = index,
            propertyPaths = parsed.propertyPaths,
            expectedValueParameters = method.parameters.count {
                it.role == RepositoryParameterRole.VALUE
            },
            resultKind = parsed.resultKind,
        )
    }

    private fun validateOperator(
        atom: DerivedAtom,
        methodIndex: Int,
        diagnostics: MutableList<RepositorySemanticDiagnostic>,
    ) {
        val type = simpleType(atom.property.javaType)
        when {
            atom.ignoreCase && type !in STRING_TYPES ->
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.ERROR,
                    "JVW-REPOSITORY-DERIVED-IGNORE-CASE",
                    "IgnoreCase can target only text properties; '${atom.property.path}' is ${atom.property.javaType}.",
                    methodIndex,
                    "name",
                )

            atom.operator.textOnly && type !in STRING_TYPES ->
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.ERROR,
                    "JVW-REPOSITORY-DERIVED-TEXT-OPERATOR",
                    "${atom.operator.label} requires a text property; '${atom.property.path}' is ${atom.property.javaType}.",
                    methodIndex,
                    "name",
                )

            atom.operator.booleanOnly && type !in BOOLEAN_TYPES ->
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.ERROR,
                    "JVW-REPOSITORY-DERIVED-BOOLEAN-OPERATOR",
                    "${atom.operator.label} requires a Boolean property; '${atom.property.path}' is ${atom.property.javaType}.",
                    methodIndex,
                    "name",
                )

            atom.operator.collectionOnly && !atom.property.collection ->
                diagnostics += diagnostic(
                    RepositorySemanticSeverity.ERROR,
                    "JVW-REPOSITORY-DERIVED-COLLECTION-OPERATOR",
                    "${atom.operator.label} requires a collection property; '${atom.property.path}' is ${atom.property.javaType}.",
                    methodIndex,
                    "name",
                )
        }
    }

    private fun validateDerivedReturn(
        method: RepositoryMethod,
        subject: String,
        index: Int,
        diagnostics: MutableList<RepositorySemanticDiagnostic>,
    ) {
        val type = simpleType(method.returnType)
        val valid = when (subject) {
            "exists" -> type in BOOLEAN_TYPES
            "count" -> type in setOf("Long", "long", "Integer", "int")
            "delete", "remove" ->
                type in setOf("void", "Unit", "Long", "long", "Integer", "int") ||
                    type.startsWith("List<") ||
                    type.startsWith("Collection<")
            else -> true
        }
        if (!valid) {
            diagnostics += diagnostic(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-DERIVED-RETURN-TYPE",
                "Derived '$subject' method '${method.name}' has incompatible return type '${method.returnType}'.",
                index,
                "returnType",
            )
        }
    }

    private fun compatibleParameter(
        parameterType: String,
        property: RepositoryPropertyPathSnapshot,
        operator: DerivedOperator,
    ): Boolean {
        val parameter = simpleType(parameterType)
        val propertyType = simpleType(property.javaType)
        if (operator in setOf(DerivedOperator.IN, DerivedOperator.NOT_IN)) {
            return parameterType.contains('<') ||
                parameterType.endsWith("[]") ||
                parameter in setOf("Iterable", "Collection", "List", "Set")
        }
        return boxed(parameter) == boxed(propertyType) ||
            parameter == "Object" ||
            propertyType == "Object"
    }

    private fun compatibleReturn(returnType: String, selectedType: String): Boolean {
        val normalized = simpleType(returnType)
        val selected = boxed(simpleType(selectedType))
        val element = normalized
            .substringAfter('<', normalized)
            .substringBeforeLast('>', normalized)
        return boxed(normalized) == selected || boxed(element) == selected
    }

    private fun boxed(type: String): String = when (type) {
        "boolean" -> "Boolean"
        "byte" -> "Byte"
        "short" -> "Short"
        "int", "Integer" -> "Integer"
        "long" -> "Long"
        "float" -> "Float"
        "double" -> "Double"
        "char", "Character" -> "Character"
        else -> type
    }

    private fun simpleType(type: String): String =
        type.trim()
            .removeSuffix("?")
            .replace(Regex("""\b[A-Za-z_$][\w$]*\."""), "")

    private fun diagnostic(
        severity: RepositorySemanticSeverity,
        code: String,
        message: String,
        methodIndex: Int? = null,
        field: String? = null,
        suggestions: List<String> = emptyList(),
    ) = RepositorySemanticDiagnostic(
        severity,
        code,
        message,
        methodIndex,
        field,
        suggestions,
    )

    private val STRING_TYPES = setOf("String", "CharSequence", "Character", "char")
    private val BOOLEAN_TYPES = setOf("Boolean", "boolean")
}

private class RepositoryEntityGraph(
    private val root: SchemaEntitySnapshot,
    entities: List<SchemaEntitySnapshot>,
) {
    private val descriptors = entities.associateBy { it.qualifiedName }
    private val aliases = buildMap<String, SchemaEntitySnapshot> {
        entities.forEach { entity ->
            put(entity.qualifiedName, entity)
            put(entity.className, entity)
            put(entity.entityName, entity)
        }
    }
    private val paths by lazy { collectPaths(root, emptyList(), linkedSetOf(), 0) }

    fun paths(): List<RepositoryPropertyPathSnapshot> = paths

    fun resolveToken(token: String): RepositoryPropertyPathSnapshot? {
        val normalized = token.replace("_", "")
        return paths
            .filter { candidate -> candidate.derivedToken == normalized }
            .minWithOrNull(
                compareBy<RepositoryPropertyPathSnapshot> { it.path.count { char -> char == '.' } }
                    .thenBy { it.path },
            )
    }

    fun suggestions(token: String): List<String> =
        paths.asSequence()
            .map { it to editDistance(token.lowercase(Locale.ROOT), it.derivedToken.lowercase(Locale.ROOT)) }
            .sortedWith(compareBy<Pair<RepositoryPropertyPathSnapshot, Int>> { it.second }.thenBy { it.first.path })
            .take(3)
            .map { it.first.path }
            .toList()

    fun resolvePath(
        start: List<String>,
        path: String,
    ): RepositoryPropertyPathSnapshot? {
        val combined = (start + path.split('.').filter(String::isNotBlank)).joinToString(".")
        return paths.singleOrNull { it.path == combined }
    }

    fun rootMatches(name: String): Boolean =
        name in setOf(root.className, root.qualifiedName, root.entityName)

    private fun collectPaths(
        entity: SchemaEntitySnapshot,
        prefix: List<String>,
        visiting: LinkedHashSet<String>,
        depth: Int,
    ): List<RepositoryPropertyPathSnapshot> {
        if (depth > MAX_PROPERTY_DEPTH || !visiting.add(entity.qualifiedName)) return emptyList()
        val attributes = (
            entity.attributes +
                entity.inheritedAttributes.map(SchemaInheritedAttributeSnapshot::attribute) +
                idAttribute(entity)
            ).distinctBy(SchemaEntityAttributeSnapshot::name)
        val result = mutableListOf<RepositoryPropertyPathSnapshot>()
        attributes.forEach { attribute ->
            val segments = prefix + attribute.name
            val related = when {
                attribute.association ->
                    attribute.associationDetails?.relatedEntity?.let(::resolveEntity)
                attribute.embedded ->
                    attribute.embeddedClass?.let(::resolveEntity)
                else -> null
            }
            result += RepositoryPropertyPathSnapshot(
                path = segments.joinToString("."),
                javaType = attribute.javaType,
                nullable = attribute.nullable,
                association = attribute.association,
                collection = isCollection(attribute),
                derivedToken = segments.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) },
            )
            if (related != null) {
                result += collectPaths(
                    related,
                    segments,
                    LinkedHashSet(visiting),
                    depth + 1,
                )
            }
        }
        return result
    }

    private fun resolveEntity(name: String): SchemaEntitySnapshot? =
        aliases[name] ?: descriptors[name]

    private fun isCollection(attribute: SchemaEntityAttributeSnapshot): Boolean =
        attribute.associationDetails?.associationType?.name in setOf("ONE_TO_MANY", "MANY_TO_MANY") ||
            Regex("""\b(List|Set|Collection|Iterable)\s*<""").containsMatchIn(attribute.javaType)

    private fun idAttribute(entity: SchemaEntitySnapshot): SchemaEntityAttributeSnapshot =
        SchemaEntityAttributeSnapshot(
            artifactId = "${entity.artifactId}:id",
            name = "id",
            javaType = when (entity.idType) {
                IdType.UUID -> "UUID"
                IdType.LONG -> "Long"
                IdType.INTEGER -> "Integer"
                IdType.STRING -> "String"
                IdType.EMBEDDED -> "Object"
            },
            columnName = entity.idColumnName,
            nullable = false,
            unique = true,
            association = false,
            moneyCandidate = false,
        )

    companion object {
        private const val MAX_PROPERTY_DEPTH = 5
    }
}

private object DerivedMethodParser {
    fun mayResolveNamedQuery(name: String): Boolean =
        NAMED_QUERY_SUBJECT.containsMatchIn(name) && "By" !in name

    fun parse(name: String, graph: RepositoryEntityGraph): ParsedDerivedMethod? {
        val subjectMatch = SUBJECT.matchEntire(name.substringBefore("By") + "By")
            ?: return null
        val subject = subjectMatch.groupValues[1]
        val by = name.indexOf("By")
        if (by < 0 || by + 2 >= name.length) return null
        var predicate = name.substring(by + 2)
        val orderIndex = predicate.lastIndexOf("OrderBy")
        val order = if (orderIndex >= 0) predicate.substring(orderIndex + 7) else ""
        if (orderIndex >= 0) predicate = predicate.substring(0, orderIndex)
        val allIgnoreCase = ALL_IGNORE_CASE_SUFFIXES.firstOrNull(predicate::endsWith)
        if (allIgnoreCase != null) predicate = predicate.removeSuffix(allIgnoreCase)
        val atoms = splitPredicate(predicate, graph, allIgnoreCase != null)
        if (atoms == null) {
            val token = unresolvedToken(predicate, graph)
            return ParsedDerivedMethod(
                subject,
                emptyList(),
                emptyList(),
                DerivedParseIssue(
                    "JVW-REPOSITORY-DERIVED-PROPERTY",
                    "Cannot resolve derived property expression '$token' against the entity model.",
                    graph.suggestions(token),
                ),
            )
        }
        val orderPaths = parseOrder(order, graph)
        if (order.isNotBlank() && orderPaths == null) {
            return ParsedDerivedMethod(
                subject,
                atoms,
                emptyList(),
                DerivedParseIssue(
                    "JVW-REPOSITORY-DERIVED-ORDER",
                    "Cannot resolve static OrderBy expression '$order' against the entity model.",
                    graph.suggestions(order.removeSuffix("Asc").removeSuffix("Desc")),
                ),
            )
        }
        return ParsedDerivedMethod(subject, atoms, orderPaths.orEmpty(), null)
    }

    private fun splitPredicate(
        predicate: String,
        graph: RepositoryEntityGraph,
        allIgnoreCase: Boolean,
    ): List<DerivedAtom>? {
        parseAtom(predicate, graph, allIgnoreCase)?.let { return listOf(it) }
        connectorPositions(predicate).forEach { (index, connector) ->
            val left = parseAtom(predicate.substring(0, index), graph, allIgnoreCase)
                ?: return@forEach
            val right = splitPredicate(
                predicate.substring(index + connector.length),
                graph,
                allIgnoreCase,
            ) ?: return@forEach
            return listOf(left) + right
        }
        return null
    }

    private fun parseAtom(
        raw: String,
        graph: RepositoryEntityGraph,
        allIgnoreCase: Boolean,
    ): DerivedAtom? {
        var value = raw
        var ignoreCase = allIgnoreCase
        IGNORE_CASE_SUFFIXES.firstOrNull(value::endsWith)?.let {
            ignoreCase = true
            value = value.removeSuffix(it)
        }
        val candidates = buildList {
            OPERATORS.forEach { operator ->
                if (value.endsWith(operator.suffix) && value.length > operator.suffix.length) {
                    add(value.removeSuffix(operator.suffix) to operator)
                }
            }
            add(value to DerivedOperator.EQUALS)
        }
        return candidates.firstNotNullOfOrNull { (propertyToken, operator) ->
            graph.resolveToken(propertyToken)?.let { DerivedAtom(it, operator, ignoreCase) }
        }
    }

    private fun parseOrder(
        order: String,
        graph: RepositoryEntityGraph,
    ): List<RepositoryPropertyPathSnapshot>? {
        if (order.isBlank()) return emptyList()
        val result = mutableListOf<RepositoryPropertyPathSnapshot>()
        var remaining = order
        while (remaining.isNotBlank()) {
            val match = graph.paths()
                .sortedByDescending { it.derivedToken.length }
                .firstOrNull { path ->
                    remaining.startsWith(path.derivedToken) &&
                        remaining.removePrefix(path.derivedToken).let { tail ->
                            tail.startsWith("Asc") || tail.startsWith("Desc") || tail.isEmpty() ||
                                graph.paths().any { next -> tail.startsWith(next.derivedToken) }
                        }
                } ?: return null
            result += match
            remaining = remaining.removePrefix(match.derivedToken)
            if (remaining.startsWith("Asc")) remaining = remaining.removePrefix("Asc")
            else if (remaining.startsWith("Desc")) remaining = remaining.removePrefix("Desc")
        }
        return result
    }

    private fun connectorPositions(value: String): List<Pair<Int, String>> =
        CONNECTOR.findAll(value)
            .map { it.range.first to it.value }
            .toList()

    private fun unresolvedToken(predicate: String, graph: RepositoryEntityGraph): String {
        val stripped = predicate
            .replace(CONNECTOR, "")
            .let { value ->
                IGNORE_CASE_SUFFIXES.fold(value) { current, suffix -> current.removeSuffix(suffix) }
            }
        return OPERATORS.fold(stripped) { current, operator -> current.removeSuffix(operator.suffix) }
            .takeIf(String::isNotBlank)
            ?: graph.paths().firstOrNull()?.derivedToken.orEmpty()
    }

    private val SUBJECT = Regex(
        """^(find|read|get|query|search|stream|count|exists|delete|remove)""" +
            """(?:Distinct)?(?:(?:First|Top)\d*)?(?:Distinct)?By$""",
    )
    private val NAMED_QUERY_SUBJECT = Regex(
        """^(find|read|get|query|search|stream|count|exists|delete|remove)[A-Z0-9_].*""",
    )
    private val CONNECTOR = Regex("""(?<=[a-z0-9])(And|Or)(?=[A-Z])""")
    private val IGNORE_CASE_SUFFIXES = listOf("IgnoringCase", "IgnoreCase")
    private val ALL_IGNORE_CASE_SUFFIXES = listOf("AllIgnoringCase", "AllIgnoreCase")
    private val OPERATORS = DerivedOperator.entries
        .filter { it != DerivedOperator.EQUALS }
        .sortedByDescending { it.suffix.length }
}

private object JpqlSemanticParser {
    fun parse(query: String, graph: RepositoryEntityGraph): ParsedJpql {
        val diagnostics = mutableListOf<JpqlIssue>()
        val sanitized = maskStringLiterals(query)
        val root = FROM.find(sanitized)
        if (root == null) {
            diagnostics += JpqlIssue(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-JPQL-FROM",
                "JPQL must declare one root entity and alias.",
            )
            return ParsedJpql(diagnostics, emptyList(), RepositoryResultKind.UNKNOWN, 0, null)
        }
        val entityName = root.groupValues[1]
        val rootAlias = root.groupValues[2]
        if (!graph.rootMatches(entityName)) {
            diagnostics += JpqlIssue(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-JPQL-ROOT-ENTITY",
                "JPQL root '$entityName' does not match the repository entity.",
            )
        }
        val aliasPrefixes = linkedMapOf(rootAlias to emptyList<String>())
        val resolvedPaths = linkedSetOf<String>()
        JOIN.findAll(sanitized).forEach { match ->
            val ownerAlias = match.groupValues[1]
            val path = match.groupValues[2]
            val joinAlias = match.groupValues[3]
            val ownerPrefix = aliasPrefixes[ownerAlias]
            val resolved = ownerPrefix?.let { graph.resolvePath(it, path) }
            if (ownerPrefix == null) {
                diagnostics += JpqlIssue(
                    RepositorySemanticSeverity.ERROR,
                    "JVW-REPOSITORY-JPQL-ALIAS",
                    "Join uses unknown alias '$ownerAlias'.",
                )
            } else if (resolved == null) {
                diagnostics += unresolvedPath(graph, (ownerPrefix + path.split('.')).joinToString("."))
            } else {
                resolvedPaths += resolved.path
                if (!resolved.association && !resolved.collection) {
                    diagnostics += JpqlIssue(
                        RepositorySemanticSeverity.ERROR,
                        "JVW-REPOSITORY-JPQL-JOIN-SCALAR",
                        "JPQL cannot join scalar property '${resolved.path}'.",
                    )
                }
                if (joinAlias.isNotBlank() && joinAlias.lowercase(Locale.ROOT) !in JPQL_KEYWORDS) {
                    aliasPrefixes[joinAlias] = resolved.path.split('.')
                }
            }
        }
        PATH.findAll(sanitized).forEach { match ->
            val alias = match.groupValues[1]
            val path = match.groupValues[2]
            val prefix = aliasPrefixes[alias]
            if (prefix == null) {
                val entityRange = root.groups[1]?.range
                if (
                    match.range.first !in (entityRange ?: IntRange.EMPTY) &&
                    alias.firstOrNull()?.isLowerCase() == true
                ) {
                    diagnostics += JpqlIssue(
                        RepositorySemanticSeverity.ERROR,
                        "JVW-REPOSITORY-JPQL-ALIAS",
                        "JPQL property path '$alias.$path' uses unknown alias '$alias'.",
                    )
                }
                return@forEach
            }
            val resolved = graph.resolvePath(prefix, path)
            if (resolved == null) {
                diagnostics += unresolvedPath(graph, (prefix + path.split('.')).joinToString("."))
            } else {
                resolvedPaths += resolved.path
            }
        }
        val selection = SELECT.find(sanitized)?.groupValues?.get(1).orEmpty().trim()
        val selections = splitTopLevel(selection)
        if (selections.isEmpty()) {
            diagnostics += JpqlIssue(
                RepositorySemanticSeverity.ERROR,
                "JVW-REPOSITORY-JPQL-SELECT",
                "JPQL must select an entity or a typed projection.",
            )
        }
        val rootEntitySelection = selections.size == 1 &&
            selections.single().withoutDistinct() == rootAlias
        val countSelection = selections.size == 1 &&
            selections.single().trim().startsWith("count(", ignoreCase = true)
        val scalarPaths = selections.mapNotNull { expression ->
            PATH.matchEntire(expression.withoutDistinct())?.let { match ->
                val prefix = aliasPrefixes[match.groupValues[1]] ?: return@let null
                graph.resolvePath(prefix, match.groupValues[2])
            }
        }
        val resultKind = when {
            rootEntitySelection -> RepositoryResultKind.ENTITY
            countSelection -> RepositoryResultKind.COUNT
            selections.size > 1 -> RepositoryResultKind.AGGREGATE
            else -> RepositoryResultKind.SCALAR
        }
        return ParsedJpql(
            diagnostics = diagnostics.distinctBy { listOf(it.code, it.message) },
            propertyPaths = resolvedPaths.toList(),
            resultKind = resultKind,
            scalarSelectionCount = if (rootEntitySelection) 0 else selections.size,
            scalarType = when {
                countSelection -> "Long"
                scalarPaths.size == 1 -> scalarPaths.single().javaType
                else -> null
            },
        )
    }

    private fun unresolvedPath(
        graph: RepositoryEntityGraph,
        path: String,
    ) = JpqlIssue(
        RepositorySemanticSeverity.ERROR,
        "JVW-REPOSITORY-JPQL-PROPERTY",
        "JPQL property path '$path' does not exist in the indexed entity model.",
        graph.suggestions(path.replace(".", "")),
    )

    private fun maskStringLiterals(value: String): String {
        val result = StringBuilder(value)
        var quote: Char? = null
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (quote == null && (char == '\'' || char == '"')) {
                quote = char
            } else if (quote != null) {
                if (char == quote && value.getOrNull(index + 1) == quote) {
                    result.setCharAt(index, ' ')
                    result.setCharAt(index + 1, ' ')
                    index += 2
                    continue
                }
                if (char == quote) quote = null
                else result.setCharAt(index, ' ')
            }
            index++
        }
        return result.toString()
    }

    private fun splitTopLevel(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        value.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    result += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += value.substring(start).trim()
        return result.filter(String::isNotBlank)
    }

    private fun String.withoutDistinct(): String =
        replaceFirst(Regex("""(?i)^distinct\s+"""), "").trim()

    private val FROM = Regex(
        """(?i)\bfrom\s+([A-Za-z_$][\w$.]*)\s+(?:as\s+)?([A-Za-z_$][\w$]*)""",
    )
    private val JOIN = Regex(
        """(?i)\b(?:(?:left|right)(?:\s+outer)?|inner|outer)?\s*join(?:\s+fetch)?\s+""" +
            """([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""" +
            """(?:\s+(?:as\s+)?([A-Za-z_$][\w$]*))?""",
    )
    private val PATH = Regex(
        """\b([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""",
    )
    private val SELECT = Regex("""(?is)^\s*select\s+(.+?)\s+from\b""")
    private val JPQL_KEYWORDS = setOf(
        "where",
        "join",
        "left",
        "right",
        "inner",
        "outer",
        "on",
        "with",
        "order",
        "group",
        "having",
    )
}

private enum class DerivedOperator(
    val suffix: String,
    val parameterCount: Int,
    val label: String,
    val textOnly: Boolean = false,
    val booleanOnly: Boolean = false,
    val collectionOnly: Boolean = false,
) {
    BETWEEN("IsBetween", 2, "Between"),
    BETWEEN_SHORT("Between", 2, "Between"),
    NOT_CONTAINING("IsNotContaining", 1, "Not containing", textOnly = true),
    NOT_CONTAINS("NotContaining", 1, "Not containing", textOnly = true),
    CONTAINING("IsContaining", 1, "Containing", textOnly = true),
    CONTAINS("Containing", 1, "Containing", textOnly = true),
    CONTAINS_SHORT("Contains", 1, "Containing", textOnly = true),
    STARTING("IsStartingWith", 1, "Starting with", textOnly = true),
    STARTS("StartingWith", 1, "Starting with", textOnly = true),
    STARTS_SHORT("StartsWith", 1, "Starting with", textOnly = true),
    ENDING("IsEndingWith", 1, "Ending with", textOnly = true),
    ENDS("EndingWith", 1, "Ending with", textOnly = true),
    ENDS_SHORT("EndsWith", 1, "Ending with", textOnly = true),
    GREATER_EQUAL("IsGreaterThanEqual", 1, "Greater than or equal"),
    GREATER_EQUAL_SHORT("GreaterThanEqual", 1, "Greater than or equal"),
    LESS_EQUAL("IsLessThanEqual", 1, "Less than or equal"),
    LESS_EQUAL_SHORT("LessThanEqual", 1, "Less than or equal"),
    GREATER("IsGreaterThan", 1, "Greater than"),
    GREATER_SHORT("GreaterThan", 1, "Greater than"),
    LESS("IsLessThan", 1, "Less than"),
    LESS_SHORT("LessThan", 1, "Less than"),
    NOT_NULL("IsNotNull", 0, "Is not null"),
    NOT_NULL_SHORT("NotNull", 0, "Is not null"),
    NULL("IsNull", 0, "Is null"),
    NULL_SHORT("Null", 0, "Is null"),
    NOT_EMPTY("IsNotEmpty", 0, "Is not empty", collectionOnly = true),
    NOT_EMPTY_SHORT("NotEmpty", 0, "Is not empty", collectionOnly = true),
    EMPTY("IsEmpty", 0, "Is empty", collectionOnly = true),
    EMPTY_SHORT("Empty", 0, "Is empty", collectionOnly = true),
    NOT_IN("IsNotIn", 1, "Not in"),
    NOT_IN_SHORT("NotIn", 1, "Not in"),
    IN("IsIn", 1, "In"),
    IN_SHORT("In", 1, "In"),
    NOT_LIKE("IsNotLike", 1, "Not like", textOnly = true),
    NOT_LIKE_SHORT("NotLike", 1, "Not like", textOnly = true),
    LIKE("IsLike", 1, "Like", textOnly = true),
    LIKE_SHORT("Like", 1, "Like", textOnly = true),
    TRUE("IsTrue", 0, "Is true", booleanOnly = true),
    TRUE_SHORT("True", 0, "Is true", booleanOnly = true),
    FALSE("IsFalse", 0, "Is false", booleanOnly = true),
    FALSE_SHORT("False", 0, "Is false", booleanOnly = true),
    AFTER("IsAfter", 1, "After"),
    AFTER_SHORT("After", 1, "After"),
    BEFORE("IsBefore", 1, "Before"),
    BEFORE_SHORT("Before", 1, "Before"),
    NOT("IsNot", 1, "Not"),
    NOT_SHORT("Not", 1, "Not"),
    IS("Is", 1, "Equals"),
    EQUALS("Equals", 1, "Equals"),
}

private data class DerivedAtom(
    val property: RepositoryPropertyPathSnapshot,
    val operator: DerivedOperator,
    val ignoreCase: Boolean,
)

private data class ParsedDerivedMethod(
    val subject: String,
    val atoms: List<DerivedAtom>,
    val orderPaths: List<RepositoryPropertyPathSnapshot>,
    val issue: DerivedParseIssue?,
)

private data class DerivedParseIssue(
    val code: String,
    val message: String,
    val suggestions: List<String>,
)

private data class ParsedJpql(
    val diagnostics: List<JpqlIssue>,
    val propertyPaths: List<String>,
    val resultKind: RepositoryResultKind,
    val scalarSelectionCount: Int,
    val scalarType: String?,
)

private data class JpqlIssue(
    val severity: RepositorySemanticSeverity,
    val code: String,
    val message: String,
    val suggestions: List<String> = emptyList(),
)

data class RepositorySemanticValidationResponse(
    val accepted: Boolean,
    val diagnostics: List<RepositorySemanticDiagnostic>,
    val propertyPaths: List<RepositoryPropertyPathSnapshot>,
    val methods: List<RepositoryMethodSemanticSnapshot>,
)

data class RepositorySemanticDiagnostic(
    val severity: RepositorySemanticSeverity,
    val code: String,
    val message: String,
    val methodIndex: Int? = null,
    val field: String? = null,
    val suggestions: List<String> = emptyList(),
    val blocking: Boolean = severity == RepositorySemanticSeverity.ERROR,
    val sourceOwned: Boolean = false,
)

enum class RepositorySemanticSeverity {
    @SerializedName("error") ERROR,
    @SerializedName("warning") WARNING,
    @SerializedName("info") INFO,
}

data class RepositoryPropertyPathSnapshot(
    val path: String,
    val javaType: String,
    val nullable: Boolean,
    val association: Boolean,
    val collection: Boolean,
    val derivedToken: String,
)

data class RepositoryMethodSemanticSnapshot(
    val methodIndex: Int,
    val propertyPaths: List<String>,
    val expectedValueParameters: Int,
    val resultKind: RepositoryResultKind,
)

enum class RepositoryResultKind {
    @SerializedName("entity") ENTITY,
    @SerializedName("scalar") SCALAR,
    @SerializedName("aggregate") AGGREGATE,
    @SerializedName("count") COUNT,
    @SerializedName("exists") EXISTS,
    @SerializedName("delete") DELETE,
    @SerializedName("unknown") UNKNOWN,
}

private fun editDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftChar ->
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightChar ->
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
            )
        }
        previous = current
    }
    return previous[right.length]
}

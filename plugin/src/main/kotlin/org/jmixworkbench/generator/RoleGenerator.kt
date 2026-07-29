package org.jmixworkbench.generator

import org.jmixworkbench.model.EntityAttributePolicyAction
import org.jmixworkbench.model.EntityPolicyAction
import org.jmixworkbench.model.RoleModel
import org.jmixworkbench.model.RoleScope
import org.jmixworkbench.model.RowLevelPolicyAction
import org.jmixworkbench.model.RowLevelPolicyModel
import org.jmixworkbench.model.RowLevelPolicyType
import javax.lang.model.SourceVersion

/**
 * Generates current Jmix design-time security roles.
 *
 * The generator deliberately emits only public Jmix annotations documented for
 * design-time roles. It does not emit the old ScreenPolicy/generic
 * RowLevelPolicy model and it never invents an allow-all predicate.
 */
object RoleGenerator {
    private val packageNamePattern =
        Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""")
    private val qualifiedNamePattern =
        Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""")
    private val roleCodePattern = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,199}""")
    private val allowedScopes = setOf("UI", "API")

    fun generate(role: RoleModel, defaultPackageName: String): String {
        val packageName = role.packageName?.trim().orEmpty().ifBlank { defaultPackageName.trim() }
        validate(role, packageName)
        return when (role.scope) {
            RoleScope.RESOURCE -> generateResourceRole(role, packageName)
            RoleScope.ROW_LEVEL -> generateRowLevelRole(role, packageName)
        }
    }

    fun validate(role: RoleModel, packageName: String) {
        require(validIdentifier(role.className)) {
            "JVW-ROLE-CLASS-INVALID: '${role.className}' is not a valid Java interface name."
        }
        require(packageNamePattern.matches(packageName)) {
            "JVW-ROLE-PACKAGE-INVALID: '$packageName' is not a valid Java package."
        }
        require(role.name.isNotBlank() && role.name.length <= 200) {
            "JVW-ROLE-NAME-INVALID: a role display name between 1 and 200 characters is required."
        }
        require(roleCodePattern.matches(role.code)) {
            "JVW-ROLE-CODE-INVALID: use 1-200 letters, digits, dots, underscores, or dashes."
        }
        require(role.description.orEmpty().length <= 1_000) {
            "JVW-ROLE-DESCRIPTION-INVALID: role descriptions are limited to 1000 characters."
        }
        require(role.baseRoleClasses.distinct().size == role.baseRoleClasses.size) {
            "JVW-ROLE-BASE-DUPLICATE: the same base role cannot be inherited twice."
        }
        role.baseRoleClasses.forEach { base ->
            require(qualifiedNamePattern.matches(base) && validIdentifier(base.substringAfterLast('.'))) {
                "JVW-ROLE-BASE-INVALID: '$base' is not a valid role interface name."
            }
            require(base.substringAfterLast('.') != role.className) {
                "JVW-ROLE-BASE-SELF: a role cannot inherit itself."
            }
        }

        when (role.scope) {
            RoleScope.RESOURCE -> validateResourceRole(role)
            RoleScope.ROW_LEVEL -> validateRowLevelRole(role)
        }
    }

    private fun validateResourceRole(role: RoleModel) {
        require(role.rowLevelPolicies.isEmpty()) {
            "JVW-ROLE-KIND-MISMATCH: row-level policies require a row-level role."
        }
        require(role.securityScopes.isNotEmpty() && role.securityScopes.all { it in allowedScopes }) {
            "JVW-ROLE-SCOPE-INVALID: resource role scopes must contain UI, API, or both."
        }
        role.entityPolicies.forEach { policy ->
            validateEntity(policy.entityClass)
            require(policy.resolvedActions.isNotEmpty()) {
                "JVW-ROLE-ENTITY-ACTIONS-MISSING: ${policy.entityClass} needs at least one CRUD action."
            }
        }
        role.entityAttributePolicies.forEach { policy ->
            validateEntity(policy.entityClass)
            require(policy.attributes.isNotEmpty() && policy.attributes.all(String::isNotBlank)) {
                "JVW-ROLE-ATTRIBUTE-MISSING: ${policy.entityClass} needs at least one attribute."
            }
            rejectWildcardUnlessAcknowledged(policy.attributes, role)
        }
        rejectWildcardUnlessAcknowledged(role.menuPolicies.map { it.menuId }, role)
        rejectWildcardUnlessAcknowledged(role.viewPolicies.map { it.viewId }, role)
        rejectWildcardUnlessAcknowledged(role.specificPolicies.map { it.permission }, role)
        require(role.menuPolicies.all { it.menuId.isNotBlank() }) {
            "JVW-ROLE-MENU-ID-MISSING: every menu policy needs a menu id."
        }
        require(role.viewPolicies.all { it.viewId.isNotBlank() }) {
            "JVW-ROLE-VIEW-ID-MISSING: every view policy needs a view id."
        }
        require(role.specificPolicies.all { it.permission.isNotBlank() }) {
            "JVW-ROLE-SPECIFIC-MISSING: every specific policy needs a resource id."
        }
    }

    private fun validateRowLevelRole(role: RoleModel) {
        require(
            role.entityPolicies.isEmpty() &&
                role.entityAttributePolicies.isEmpty() &&
                role.menuPolicies.isEmpty() &&
                role.viewPolicies.isEmpty() &&
                role.specificPolicies.isEmpty(),
        ) {
            "JVW-ROLE-KIND-MISMATCH: grant policies require a resource role."
        }
        role.rowLevelPolicies.forEach { policy ->
            validateEntity(policy.entityClass)
            when (policy.type) {
                RowLevelPolicyType.JPQL -> {
                    val where = policy.whereClause.orEmpty().trim()
                    val join = policy.joinClause.orEmpty().trim()
                    require(where.isNotBlank()) {
                        "JVW-ROLE-JPQL-WHERE-MISSING: ${policy.entityClass} needs a bounded where clause."
                    }
                    require(!where.startsWith("where ", ignoreCase = true)) {
                        "JVW-ROLE-JPQL-WHERE-PREFIX: enter the expression without the 'where' keyword."
                    }
                    require("{E}" in where || "{E}" in join) {
                        "JVW-ROLE-JPQL-ENTITY-PLACEHOLDER: use {E} in the where or join clause."
                    }
                    if (join.isNotBlank()) {
                        require(join.startsWith("join ", ignoreCase = true) ||
                            join.startsWith("left join ", ignoreCase = true) ||
                            join.startsWith("right join ", ignoreCase = true) ||
                            join.startsWith("inner join ", ignoreCase = true)
                        ) {
                            "JVW-ROLE-JPQL-JOIN-INVALID: a join clause must start with join, left join, right join, or inner join."
                        }
                    }
                }
                RowLevelPolicyType.PREDICATE -> {
                    require(policy.predicateExpression?.trim()?.isNotEmpty() == true) {
                        "JVW-ROLE-PREDICATE-MISSING: a predicate role requires an explicit boolean Java expression."
                    }
                    require(policy.resolvedActions().isNotEmpty()) {
                        "JVW-ROLE-PREDICATE-ACTIONS-MISSING: select at least one protected CRUD action."
                    }
                }
                RowLevelPolicyType.SCRIPT -> error(
                    "JVW-ROLE-SCRIPT-UNSUPPORTED: design-time script policies are not a current Jmix annotation mechanism.",
                )
            }
        }
    }

    private fun generateResourceRole(role: RoleModel, packageName: String): String {
        val typeNames = TypeNameResolver.forRole(role, packageName)
        val imports = linkedSetOf(
            "io.jmix.security.role.annotation.ResourceRole",
        ).apply { addAll(typeNames.imports) }
        val methods = mutableListOf<String>()
        val usedNames = linkedSetOf<String>()

        role.entityPolicies.forEach { policy ->
            val entityName = typeNames.reference(policy.entityClass)
            val methodSeed = policy.entityClass.substringAfterLast('.')
            imports += "io.jmix.security.role.annotation.EntityPolicy"
            imports += "io.jmix.security.model.EntityPolicyAction"
            val actions = policy.resolvedActions.joinToString(", ") { "EntityPolicyAction.${it.name}" }
            methods += annotationMethod(
                annotations = listOf(
                    "@EntityPolicy(entityClass = $entityName.class, actions = ${arrayOrSingle(actions, policy.resolvedActions.size)})",
                ),
                methodName = uniqueMethodName(methodSeed, usedNames),
            )
        }

        role.entityAttributePolicies.forEach { policy ->
            val entityName = typeNames.reference(policy.entityClass)
            val methodSeed = policy.entityClass.substringAfterLast('.')
            imports += "io.jmix.security.role.annotation.EntityAttributePolicy"
            imports += "io.jmix.security.model.EntityAttributePolicyAction"
            methods += annotationMethod(
                annotations = listOf(
                    "@EntityAttributePolicy(" +
                        "entityClass = $entityName.class, " +
                        "attributes = ${stringArray(policy.attributes)}, " +
                        "action = EntityAttributePolicyAction.${policy.action.name})",
                ),
                methodName = uniqueMethodName("${methodSeed}Attributes", usedNames),
            )
        }

        if (role.menuPolicies.isNotEmpty() || role.viewPolicies.isNotEmpty()) {
            val annotations = mutableListOf<String>()
            if (role.menuPolicies.isNotEmpty()) {
                imports += "io.jmix.securityflowui.role.annotation.MenuPolicy"
                annotations += "@MenuPolicy(menuIds = ${stringArray(role.menuPolicies.map { it.menuId })})"
            }
            if (role.viewPolicies.isNotEmpty()) {
                imports += "io.jmix.securityflowui.role.annotation.ViewPolicy"
                annotations += "@ViewPolicy(viewIds = ${stringArray(role.viewPolicies.map { it.viewId })})"
            }
            methods += annotationMethod(annotations, uniqueMethodName("screens", usedNames))
        }

        if (role.specificPolicies.isNotEmpty()) {
            imports += "io.jmix.security.role.annotation.SpecificPolicy"
            methods += annotationMethod(
                annotations = listOf(
                    "@SpecificPolicy(resources = ${stringArray(role.specificPolicies.map { it.permission })})",
                ),
                methodName = uniqueMethodName("specificPermissions", usedNames),
            )
        }
        return buildRoleSource(role, packageName, imports, methods, typeNames)
    }

    private fun generateRowLevelRole(role: RoleModel, packageName: String): String {
        val typeNames = TypeNameResolver.forRole(role, packageName)
        val imports = linkedSetOf(
            "io.jmix.security.role.annotation.RowLevelRole",
        ).apply { addAll(typeNames.imports) }
        val methods = mutableListOf<String>()
        val usedNames = linkedSetOf<String>()
        role.rowLevelPolicies.forEach { policy ->
            val entityName = typeNames.reference(policy.entityClass)
            val methodName = uniqueMethodName(policy.entityClass.substringAfterLast('.'), usedNames)
            when (policy.type) {
                RowLevelPolicyType.JPQL -> {
                    imports += "io.jmix.security.role.annotation.JpqlRowLevelPolicy"
                    val arguments = buildList {
                        add("entityClass = $entityName.class")
                        policy.joinClause?.trim()?.takeIf(String::isNotBlank)?.let {
                            add("join = ${javaString(it)}")
                        }
                        add("where = ${javaString(policy.whereClause.orEmpty().trim())}")
                    }
                    methods += annotationMethod(
                        annotations = listOf("@JpqlRowLevelPolicy(${arguments.joinToString(", ")})"),
                        methodName = methodName,
                    )
                }
                RowLevelPolicyType.PREDICATE -> {
                    imports += "io.jmix.security.model.RowLevelPolicyAction"
                    imports += "io.jmix.security.model.RowLevelPredicate"
                    imports += "io.jmix.security.role.annotation.PredicateRowLevelPolicy"
                    val actions = policy.resolvedActions()
                        .joinToString(", ") { "RowLevelPolicyAction.${it.name}" }
                    methods += buildString {
                        append("    @PredicateRowLevelPolicy(entityClass = ").append(entityName)
                            .append(".class, actions = ").append(arrayOrSingle(actions, policy.resolvedActions().size))
                            .append(")\n")
                        append("    static RowLevelPredicate<").append(entityName).append("> ")
                            .append(methodName).append("Predicate() {\n")
                        append("        return entity -> ").append(policy.predicateExpression!!.trim()).append(";\n")
                        append("    }")
                    }
                }
                RowLevelPolicyType.SCRIPT -> error("Script policies are rejected during validation.")
            }
        }
        return buildRoleSource(role, packageName, imports, methods, typeNames)
    }

    private fun buildRoleSource(
        role: RoleModel,
        packageName: String,
        imports: MutableSet<String>,
        methods: List<String>,
        typeNames: TypeNameResolver,
    ): String {
        val annotation = buildString {
            append(if (role.scope == RoleScope.RESOURCE) "@ResourceRole(" else "@RowLevelRole(")
            append("name = ").append(javaString(role.name))
            append(", code = ").append(role.className).append(".CODE")
            role.description?.trim()?.takeIf(String::isNotBlank)?.let {
                append(", description = ").append(javaString(it))
            }
            if (role.scope == RoleScope.RESOURCE) {
                val scopes = role.securityScopes.distinct()
                if (scopes.toSet() != allowedScopes) {
                    append(", scope = ").append(stringArray(scopes))
                }
            }
            append(')')
        }
        val extendsClause = role.baseRoleClasses
            .map(typeNames::reference)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ", prefix = " extends ")
            .orEmpty()
        return buildString {
            append("package ").append(packageName).append(";\n\n")
            imports.filterNot { it.substringBeforeLast('.', "") == packageName }
                .sorted()
                .forEach { append("import ").append(it).append(";\n") }
            append('\n')
            append(annotation).append('\n')
            append("public interface ").append(role.className).append(extendsClause).append(" {\n")
            append("    String CODE = ").append(javaString(role.code)).append(";\n")
            methods.forEach { method ->
                append('\n').append(method).append('\n')
            }
            append("}\n")
        }
    }

    private fun annotationMethod(annotations: List<String>, methodName: String): String =
        buildString {
            annotations.forEach { append("    ").append(it).append('\n') }
            append("    void ").append(methodName).append("();")
        }

    private fun uniqueMethodName(seed: String, usedNames: MutableSet<String>): String {
        val base = seed.replaceFirstChar(Char::lowercase)
            .replace(Regex("""[^A-Za-z0-9_$]"""), "")
            .ifBlank { "policies" }
            .let { if (validIdentifier(it)) it else "policies" }
        var candidate = base
        var suffix = 2
        while (!usedNames.add(candidate)) {
            candidate = "$base${suffix++}"
        }
        return candidate
    }

    private fun stringArray(values: List<String>): String {
        val distinct = values.map(String::trim).distinct()
        return if (distinct.size == 1) {
            javaString(distinct.single())
        } else {
            distinct.joinToString(", ", prefix = "{", postfix = "}") { javaString(it) }
        }
    }

    private fun arrayOrSingle(values: String, count: Int): String =
        if (count == 1) values else "{$values}"

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
                    else -> append(character)
                }
            }
            append('"')
        }

    private fun validateEntity(entityClass: String) {
        require('.' in entityClass && qualifiedNamePattern.matches(entityClass)) {
            "JVW-ROLE-ENTITY-INVALID: '$entityClass' must be a fully qualified Java entity class."
        }
    }

    private fun rejectWildcardUnlessAcknowledged(values: List<String>, role: RoleModel) {
        require("*" !in values || role.allowWildcardPolicies) {
            "JVW-ROLE-WILDCARD-ACKNOWLEDGEMENT: wildcard policies require explicit acknowledgement."
        }
    }

    private fun validIdentifier(value: String): Boolean =
        SourceVersion.isIdentifier(value) && !SourceVersion.isKeyword(value)

    private fun RowLevelPolicyModel.resolvedActions(): List<RowLevelPolicyAction> =
        actions.takeIf { it.isNotEmpty() }?.distinct() ?: listOf(action)

    private class TypeNameResolver private constructor(
        private val references: Map<String, String>,
        val imports: Set<String>,
    ) {
        fun reference(typeName: String): String =
            references[typeName.trim()] ?: typeName.trim()

        companion object {
            fun forRole(role: RoleModel, packageName: String): TypeNameResolver {
                val qualifiedNames = buildList {
                    addAll(role.entityPolicies.map { it.entityClass.trim() })
                    addAll(role.entityAttributePolicies.map { it.entityClass.trim() })
                    addAll(role.rowLevelPolicies.map { it.entityClass.trim() })
                    addAll(role.baseRoleClasses.map(String::trim))
                }.filter { '.' in it }.distinct()
                val collisions = qualifiedNames
                    .groupBy { it.substringAfterLast('.') }
                    .filterValues { it.size > 1 }
                    .keys + role.className
                val references = qualifiedNames.associateWith { qualifiedName ->
                    val simpleName = qualifiedName.substringAfterLast('.')
                    if (simpleName in collisions && qualifiedName.substringBeforeLast('.') != packageName) {
                        qualifiedName
                    } else {
                        simpleName
                    }
                }
                val imports = qualifiedNames
                    .filter { qualifiedName ->
                        qualifiedName.substringBeforeLast('.') != packageName &&
                            qualifiedName.substringAfterLast('.') !in collisions
                    }
                    .toSortedSet()
                return TypeNameResolver(references, imports)
            }
        }
    }
}

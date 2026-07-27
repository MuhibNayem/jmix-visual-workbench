package com.jmixstudio.generator

import com.jmixstudio.model.*

/**
 * Generates Jmix security role Java classes.
 * Handles: resource roles with entity/menu/screen/specific policies,
 * row-level roles with JPQL/predicate/script policies, child roles.
 */
object RoleGenerator {

    fun generate(role: RoleModel): String {
        return when (role.scope) {
            RoleScope.RESOURCE -> generateResourceRole(role)
            RoleScope.ROW_LEVEL -> generateRowLevelRole(role)
        }
    }

    private fun generateResourceRole(role: RoleModel): String {
        val b = JavaClassBuilder(role.name)
        b.package_("") // caller sets package
        b.asInterface()

        b.import_(
            "io.jmix.security.model.EntityPolicy",
            "io.jmix.security.model.EntityPolicyAction",
            "io.jmix.security.model.MenuPolicy",
            "io.jmix.security.model.ScreenPolicy",
            "io.jmix.security.model.SpecificPolicy",
            "io.jmix.security.role.annotation.ResourceRole",
            "io.jmix.security.role.annotation.EntityAttributePolicy"
        )

        // @ResourceRole
        b.annotation {
            name = "ResourceRole"
            importPath = "io.jmix.security.role.annotation.ResourceRole"
            param("name", "\"${role.name}\"")
            param("code", "\"${role.code}\"")
            role.description?.let { param("description", "\"$it\"") }
            if (role.childRoles.isNotEmpty()) {
                param("childRoles", "{${role.childRoles.joinToString(", ") { "\"$it\"" }}}")
            }
        }

        // Entity policies
        role.entityPolicies.forEach { ep ->
            val entitySimpleName = ep.entityClass.substringAfterLast('.')
            b.import_(ep.entityClass)

            val actions = ep.resolvedActions
            if (actions.contains(EntityPolicyAction.ALL)) {
                b.method {
                    name = "${entitySimpleName.lowercase()}All"
                    returnType = "void"
                    annotation {
                        name = "EntityPolicy"
                        importPath = "io.jmix.security.model.EntityPolicy"
                        param("entityClass", "${entitySimpleName}.class")
                        param("actions", "EntityPolicyAction.ALL")
                    }
                }
            } else {
                val actionStr = actions.joinToString(", ") { "EntityPolicyAction.${it.name}" }
                b.method {
                    name = "${entitySimpleName.lowercase()}${actions.joinToString("") { it.name.replaceFirstChar { c -> c.uppercase() } }}"
                    returnType = "void"
                    annotation {
                        name = "EntityPolicy"
                        importPath = "io.jmix.security.model.EntityPolicy"
                        param("entityClass", "${entitySimpleName}.class")
                        param("actions", "{$actionStr}")
                    }
                }
            }
        }

        // Menu policies
        role.menuPolicies.forEach { mp ->
            b.method {
                name = "menu${mp.menuId.replaceFirstChar { it.uppercase() }}"
                returnType = "void"
                annotation {
                    name = "MenuPolicy"
                    importPath = "io.jmix.security.model.MenuPolicy"
                    param("menuIds", "\"${mp.menuId}\"")
                }
            }
        }

        // Screen policies
        role.screenPolicies.forEach { sp ->
            b.method {
                name = "screen${sp.screenId.replace("-", "_").replaceFirstChar { it.uppercase() }}"
                returnType = "void"
                annotation {
                    name = "ScreenPolicy"
                    importPath = "io.jmix.security.model.ScreenPolicy"
                    param("screenIds", "\"${sp.screenId}\"")
                }
            }
        }

        // Specific policies
        role.specificPolicies.forEach { sp ->
            b.method {
                name = "specific${sp.permission.replace(".", "_").replaceFirstChar { it.uppercase() }}"
                returnType = "void"
                annotation {
                    name = "SpecificPolicy"
                    importPath = "io.jmix.security.model.SpecificPolicy"
                    param("resources", "\"${sp.permission}\"")
                }
            }
        }

        return b.build()
    }

    private fun generateRowLevelRole(role: RoleModel): String {
        val b = JavaClassBuilder(role.name)
        b.package_("")
        b.asInterface()

        b.import_(
            "io.jmix.security.model.RowLevelPolicy",
            "io.jmix.security.model.RowLevelPolicyAction",
            "io.jmix.security.model.RowLevelPolicyType",
            "io.jmix.security.role.annotation.RowLevelRole"
        )

        b.annotation {
            name = "RowLevelRole"
            importPath = "io.jmix.security.role.annotation.RowLevelRole"
            param("name", "\"${role.name}\"")
            param("code", "\"${role.code}\"")
            role.description?.let { param("description", "\"$it\"") }
        }

        role.rowLevelPolicies.forEach { rlp ->
            val entitySimpleName = rlp.entityClass.substringAfterLast('.')
            b.import_(rlp.entityClass)

            b.method {
                name = "${entitySimpleName.lowercase()}${rlp.action.name.lowercase()}"
                returnType = "void"

                when (rlp.type) {
                    RowLevelPolicyType.JPQL -> {
                        annotation {
                            name = "RowLevelPolicy"
                            importPath = "io.jmix.security.model.RowLevelPolicy"
                            param("entityClass", "${entitySimpleName}.class")
                            param("type", "RowLevelPolicyType.JPQL")
                            param("action", "RowLevelPolicyAction.${rlp.action.name}")
                            rlp.whereClause?.let { param("where", "\"$it\"") }
                            rlp.joinClause?.let { param("join", "\"$it\"") }
                        }
                    }
                    RowLevelPolicyType.PREDICATE -> {
                        annotation {
                            name = "RowLevelPolicy"
                            importPath = "io.jmix.security.model.RowLevelPolicy"
                            param("entityClass", "${entitySimpleName}.class")
                            param("type", "RowLevelPolicyType.PREDICATE")
                            param("action", "RowLevelPolicyAction.${rlp.action.name}")
                        }
                        // Predicate body would be in the method
                        line("// Return a Predicate<${entitySimpleName}>")
                    }
                    RowLevelPolicyType.SCRIPT -> {
                        annotation {
                            name = "RowLevelPolicy"
                            importPath = "io.jmix.security.model.RowLevelPolicy"
                            param("entityClass", "${entitySimpleName}.class")
                            param("type", "RowLevelPolicyType.SCRIPT")
                            param("action", "RowLevelPolicyAction.${rlp.action.name}")
                            rlp.script?.let { param("script", "\"$it\"") }
                        }
                    }
                }
            }
        }

        return b.build()
    }
}

package org.jmixworkbench.model

import com.google.gson.annotations.SerializedName

// ─── Resource Role ───────────────────────────────────────────────────────────

data class RoleModel(
    val className: String,
    val packageName: String? = null,
    val name: String,
    val code: String,
    val description: String? = null,
    val scope: RoleScope = RoleScope.RESOURCE,
    val securityScopes: MutableList<String> = mutableListOf("UI"),
    val entityPolicies: MutableList<EntityPolicyModel> = mutableListOf(),
    val entityAttributePolicies: MutableList<EntityAttributePolicyModel> = mutableListOf(),
    val menuPolicies: MutableList<MenuPolicyModel> = mutableListOf(),
    val viewPolicies: MutableList<ViewPolicyModel> = mutableListOf(),
    val specificPolicies: MutableList<SpecificPolicyModel> = mutableListOf(),
    val rowLevelPolicies: MutableList<RowLevelPolicyModel> = mutableListOf(),
    val baseRoleClasses: MutableList<String> = mutableListOf(),
    val allowWildcardPolicies: Boolean = false,
)

enum class RoleScope {
    @SerializedName("resource") RESOURCE,
    @SerializedName("rowLevel") ROW_LEVEL
}

// ─── Entity Policy ───────────────────────────────────────────────────────────

data class EntityPolicyModel(
    val entityClass: String,
    val actions: MutableList<EntityPolicyAction> = mutableListOf(),
    val allActions: Boolean = false
) {
    val resolvedActions: List<EntityPolicyAction>
        get() = if (allActions) listOf(EntityPolicyAction.ALL) else actions.distinct()
}

enum class EntityPolicyAction(val annotationValue: String) {
    @SerializedName("all") ALL("all"),
    @SerializedName("create") CREATE("create"),
    @SerializedName("read") READ("read"),
    @SerializedName("update") UPDATE("update"),
    @SerializedName("delete") DELETE("delete")
}

// ─── Entity Attribute / Menu / View / Specific Policies ─────────────────────

data class EntityAttributePolicyModel(
    val entityClass: String,
    val attributes: MutableList<String> = mutableListOf("*"),
    val action: EntityAttributePolicyAction = EntityAttributePolicyAction.VIEW,
)

enum class EntityAttributePolicyAction {
    @SerializedName("view") VIEW,
    @SerializedName("modify") MODIFY,
}

data class MenuPolicyModel(
    val menuId: String,
    val caption: String? = null
)

data class ViewPolicyModel(
    val viewId: String,
    val caption: String? = null
)

data class SpecificPolicyModel(
    val permission: String,
    val caption: String? = null
)

// ─── Row-Level Policy ────────────────────────────────────────────────────────

data class RowLevelPolicyModel(
    val entityClass: String,
    val type: RowLevelPolicyType,
    val action: RowLevelPolicyAction = RowLevelPolicyAction.READ,
    val actions: MutableList<RowLevelPolicyAction> = mutableListOf(),
    val whereClause: String? = null,
    val joinClause: String? = null,
    val predicateExpression: String? = null,
)

enum class RowLevelPolicyType {
    @SerializedName("jpql") JPQL,
    @SerializedName("predicate") PREDICATE,
    @SerializedName("script") SCRIPT
}

enum class RowLevelPolicyAction {
    @SerializedName("create") CREATE,
    @SerializedName("read") READ,
    @SerializedName("update") UPDATE,
    @SerializedName("delete") DELETE
}

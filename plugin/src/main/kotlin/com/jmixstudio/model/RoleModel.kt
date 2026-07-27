package com.jmixstudio.model

import com.google.gson.annotations.SerializedName

// ─── Resource Role ───────────────────────────────────────────────────────────

data class RoleModel(
    val name: String,
    val code: String,
    val description: String? = null,
    val scope: RoleScope = RoleScope.RESOURCE,
    val entityPolicies: MutableList<EntityPolicyModel> = mutableListOf(),
    val menuPolicies: MutableList<MenuPolicyModel> = mutableListOf(),
    val screenPolicies: MutableList<ScreenPolicyModel> = mutableListOf(),
    val specificPolicies: MutableList<SpecificPolicyModel> = mutableListOf(),
    val rowLevelPolicies: MutableList<RowLevelPolicyModel> = mutableListOf(),
    val childRoles: MutableList<String> = mutableListOf()
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
        get() = if (allActions) EntityPolicyAction.entries else actions
}

enum class EntityPolicyAction(val annotationValue: String) {
    @SerializedName("all") ALL("all"),
    @SerializedName("create") CREATE("create"),
    @SerializedName("read") READ("read"),
    @SerializedName("update") UPDATE("update"),
    @SerializedName("delete") DELETE("delete")
}

// ─── Menu / Screen / Specific Policies ───────────────────────────────────────

data class MenuPolicyModel(
    val menuId: String,
    val caption: String? = null
)

data class ScreenPolicyModel(
    val screenId: String,
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
    val whereClause: String? = null,
    val joinClause: String? = null,
    val script: String? = null
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

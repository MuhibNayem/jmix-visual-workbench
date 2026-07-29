package org.jmixworkbench.discovery.security

data class RuntimeSecurityEvidenceImportRequest(
    val fileName: String,
    val contentBase64: String,
    val environmentLabel: String? = null,
)

data class RuntimeSecurityEvidenceClearRequest(
    val sourceId: String? = null,
)

data class RuntimeSecurityEvidenceImportResponse(
    val accepted: Boolean,
    val sourceId: String?,
    val message: String,
    val issues: List<RuntimeSecurityEvidenceIssue>,
)

enum class RuntimeSecurityEvidenceSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class RuntimeSecurityEvidenceIssue(
    val code: String,
    val severity: RuntimeSecurityEvidenceSeverity,
    val message: String,
    val sourceId: String? = null,
    val roleId: String? = null,
    val username: String? = null,
)

enum class RuntimeSecurityEvidenceFormat {
    JMIX_RESOURCE_ROLE_JSON,
    JMIX_ROW_LEVEL_ROLE_JSON,
    JMIX_ROLE_ASSIGNMENT_JSON,
    JMIX_MIXED_ENTITY_JSON,
    JMIX_WORKBENCH_EVIDENCE_V1,
}

data class RuntimeSecurityEvidenceSourceSnapshot(
    val id: String,
    val fileName: String,
    val environmentLabel: String?,
    val format: RuntimeSecurityEvidenceFormat,
    val sha256: String,
    val importedAt: String,
    val roleCount: Int,
    val policyCount: Int,
    val assignmentCount: Int,
)

data class RuntimeSecurityRoleSnapshot(
    val id: String,
    val name: String,
    val code: String,
    val description: String?,
    val kind: SecurityRoleKind,
    val scopes: List<String>,
    val policyIds: List<String>,
    val inheritedRoleIds: List<String>,
    val unresolvedChildRoleCodes: List<String>,
    val tenantId: String?,
    val evidenceSourceId: String,
)

data class RuntimeSecurityPolicySnapshot(
    val id: String,
    val roleId: String,
    val type: String,
    val effect: SecurityPolicyEffect,
    val actions: List<String>,
    val resourceExpressions: List<String>,
    val targetArtifactIds: List<String>,
    val wildcard: Boolean,
    val condition: String?,
    val policyGroup: String?,
    val evidenceSourceId: String,
)

enum class RuntimeRoleAssignmentResolution {
    RESOLVED,
    MISSING_ROLE,
    AMBIGUOUS_ROLE,
}

data class RuntimeRoleAssignmentSnapshot(
    val id: String,
    val username: String,
    val roleCode: String,
    val roleKind: SecurityRoleKind,
    val tenantId: String?,
    val candidateRoleIds: List<String>,
    val resolution: RuntimeRoleAssignmentResolution,
    val evidenceSourceId: String,
)

data class RuntimeSecurityEvidenceSummary(
    val sourceCount: Int,
    val roleCount: Int,
    val policyCount: Int,
    val assignmentCount: Int,
    val principalCount: Int,
    val errorCount: Int,
    val warningCount: Int,
)

data class RuntimeSecurityEvidenceSnapshot(
    val sources: List<RuntimeSecurityEvidenceSourceSnapshot> = emptyList(),
    val roles: List<RuntimeSecurityRoleSnapshot> = emptyList(),
    val policies: List<RuntimeSecurityPolicySnapshot> = emptyList(),
    val assignments: List<RuntimeRoleAssignmentSnapshot> = emptyList(),
    val principals: List<String> = emptyList(),
    val issues: List<RuntimeSecurityEvidenceIssue> = emptyList(),
    val summary: RuntimeSecurityEvidenceSummary = RuntimeSecurityEvidenceSummary(
        sourceCount = 0,
        roleCount = 0,
        policyCount = 0,
        assignmentCount = 0,
        principalCount = 0,
        errorCount = 0,
        warningCount = 0,
    ),
)

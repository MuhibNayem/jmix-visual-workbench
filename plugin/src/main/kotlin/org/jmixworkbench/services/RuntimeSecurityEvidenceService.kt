package org.jmixworkbench.services

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.security.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

@Service(Service.Level.PROJECT)
class RuntimeSecurityEvidenceService {
    private val imports = AtomicReference<Map<String, ParsedEvidence>>(emptyMap())

    fun importEvidence(request: RuntimeSecurityEvidenceImportRequest): RuntimeSecurityEvidenceImportResponse {
        val normalizedName = request.fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        if (normalizedName.isBlank()) {
            return rejected("JVW-RUNTIME-SECURITY-FILE-NAME-MISSING", "Choose a named Jmix JSON or ZIP export.")
        }
        if (request.contentBase64.length > MAX_BASE64_CHARACTERS) {
            return rejected(
                "JVW-RUNTIME-SECURITY-FILE-TOO-LARGE",
                "The selected evidence exceeds the ${MAX_DECODED_BYTES / (1024 * 1024)} MiB import limit.",
            )
        }
        val bytes = runCatching { Base64.getDecoder().decode(request.contentBase64) }.getOrElse {
            return rejected("JVW-RUNTIME-SECURITY-BASE64-INVALID", "The selected evidence could not be decoded.")
        }
        if (bytes.isEmpty() || bytes.size > MAX_DECODED_BYTES) {
            return rejected(
                "JVW-RUNTIME-SECURITY-FILE-SIZE-INVALID",
                "The selected evidence must be between 1 byte and ${MAX_DECODED_BYTES / (1024 * 1024)} MiB.",
            )
        }

        val digest = sha256(bytes)
        val sourceId = "runtime-evidence:${digest.take(20)}"
        val parsed = runCatching {
            RuntimeSecurityEvidenceParser.parse(
                sourceId = sourceId,
                fileName = normalizedName,
                environmentLabel = request.environmentLabel?.trim()?.takeIf(String::isNotBlank),
                sha256 = digest,
                bytes = bytes,
                importedAt = Instant.now().toString(),
            )
        }.getOrElse { error ->
            return rejected(
                "JVW-RUNTIME-SECURITY-PARSE-FAILED",
                error.message ?: "The selected file is not a supported Jmix security export.",
            )
        }
        if (parsed.roles.isEmpty() && parsed.assignments.isEmpty()) {
            return rejected(
                "JVW-RUNTIME-SECURITY-NO-EVIDENCE",
                "No Jmix resource roles, row-level roles, or role assignments were found.",
                parsed.issues,
            )
        }

        imports.updateAndGet { current -> current + (sourceId to parsed) }
        return RuntimeSecurityEvidenceImportResponse(
            accepted = true,
            sourceId = sourceId,
            message = buildString {
                append("Imported ")
                append(parsed.roles.size)
                append(" runtime role(s), ")
                append(parsed.policies.size)
                append(" policy record(s), and ")
                append(parsed.assignments.size)
                append(" assignment(s) from ")
                append(normalizedName)
                append('.')
            },
            issues = parsed.issues,
        )
    }

    fun clear(request: RuntimeSecurityEvidenceClearRequest): RuntimeSecurityEvidenceImportResponse {
        val sourceId = request.sourceId?.trim()?.takeIf(String::isNotBlank)
        val previous = imports.get()
        if (sourceId == null) {
            imports.set(emptyMap())
            return RuntimeSecurityEvidenceImportResponse(
                accepted = true,
                sourceId = null,
                message = "Cleared ${previous.size} runtime security evidence source(s).",
                issues = emptyList(),
            )
        }
        if (sourceId !in previous) {
            return rejected(
                "JVW-RUNTIME-SECURITY-SOURCE-NOT-FOUND",
                "Runtime security evidence source $sourceId is no longer loaded.",
            )
        }
        imports.updateAndGet { it - sourceId }
        return RuntimeSecurityEvidenceImportResponse(
            accepted = true,
            sourceId = sourceId,
            message = "Removed runtime security evidence source $sourceId.",
            issues = emptyList(),
        )
    }

    fun snapshot(sourceWorkspace: SecurityWorkspaceSnapshot): RuntimeSecurityEvidenceSnapshot {
        val parsedImports = imports.get().values.sortedBy { it.source.fileName.lowercase(Locale.ROOT) }
        if (parsedImports.isEmpty()) return RuntimeSecurityEvidenceSnapshot()

        val rawRoles = parsedImports.flatMap(ParsedEvidence::roles)
        val rawPolicies = parsedImports.flatMap(ParsedEvidence::policies)
        val rawAssignments = parsedImports.flatMap(ParsedEvidence::assignments)
        val issues = parsedImports.flatMap(ParsedEvidence::issues).toMutableList()

        val sourceRoleIds = sourceWorkspace.roles.groupBy { RoleKey(it.kind, it.code) }
            .mapValues { (_, roles) -> roles.map { it.id } }
        val runtimeRoleIds = rawRoles.groupBy { RoleKey(it.kind, it.code) }
            .mapValues { (_, roles) -> roles.map { it.id } }
        val allRoleIds = (sourceRoleIds.keys + runtimeRoleIds.keys).associateWith { key ->
            (sourceRoleIds[key].orEmpty() + runtimeRoleIds[key].orEmpty()).distinct().sorted()
        }

        allRoleIds.filterValues { it.size > 1 }.forEach { (key, ids) ->
            issues += RuntimeSecurityEvidenceIssue(
                code = "JVW-RUNTIME-SECURITY-DUPLICATE-ROLE-CODE",
                severity = RuntimeSecurityEvidenceSeverity.ERROR,
                message = "${key.kind.readableName()} role code '${key.code}' resolves to ${ids.size} roles. " +
                    "Current Jmix versions reject design-time/runtime duplicates; effective access is ambiguous.",
            )
        }

        val roles = rawRoles.map { role ->
            val inherited = linkedSetOf<String>()
            val unresolved = linkedSetOf<String>()
            role.childRoleCodes.forEach { childCode ->
                val candidates = allRoleIds[RoleKey(role.kind, childCode)].orEmpty()
                if (candidates.isEmpty()) {
                    unresolved += childCode
                } else {
                    inherited += candidates
                    if (candidates.size > 1) {
                        issues += RuntimeSecurityEvidenceIssue(
                            code = "JVW-RUNTIME-SECURITY-CHILD-ROLE-AMBIGUOUS",
                            severity = RuntimeSecurityEvidenceSeverity.ERROR,
                            message = "${role.name} includes child role '$childCode', but that code is duplicated.",
                            sourceId = role.evidenceSourceId,
                            roleId = role.id,
                        )
                    }
                }
            }
            if (unresolved.isNotEmpty()) {
                issues += RuntimeSecurityEvidenceIssue(
                    code = "JVW-RUNTIME-SECURITY-CHILD-ROLE-MISSING",
                    severity = RuntimeSecurityEvidenceSeverity.WARNING,
                    message = "${role.name} references missing child role code(s): ${unresolved.joinToString()}.",
                    sourceId = role.evidenceSourceId,
                    roleId = role.id,
                )
            }
            RuntimeSecurityRoleSnapshot(
                id = role.id,
                name = role.name,
                code = role.code,
                description = role.description,
                kind = role.kind,
                scopes = role.scopes,
                policyIds = rawPolicies.filter { it.roleId == role.id }.map { it.id }.sorted(),
                inheritedRoleIds = inherited.sorted(),
                unresolvedChildRoleCodes = unresolved.sorted(),
                tenantId = role.tenantId,
                evidenceSourceId = role.evidenceSourceId,
            )
        }.sortedWith(compareBy(RuntimeSecurityRoleSnapshot::kind, RuntimeSecurityRoleSnapshot::code, RuntimeSecurityRoleSnapshot::id))

        val policies = rawPolicies.map { policy ->
            val targets = matchTargets(policy, sourceWorkspace.surfaces)
            if (!policy.wildcard && targets.isEmpty()) {
                issues += RuntimeSecurityEvidenceIssue(
                    code = "JVW-RUNTIME-SECURITY-POLICY-TARGET-UNRESOLVED",
                    severity = RuntimeSecurityEvidenceSeverity.WARNING,
                    message = "Runtime policy '${policy.type} ${policy.resourceExpressions.joinToString()}' " +
                        "does not match a source-indexed application surface.",
                    sourceId = policy.evidenceSourceId,
                    roleId = policy.roleId,
                )
            }
            RuntimeSecurityPolicySnapshot(
                id = policy.id,
                roleId = policy.roleId,
                type = policy.type,
                effect = policy.effect,
                actions = policy.actions,
                resourceExpressions = policy.resourceExpressions,
                targetArtifactIds = targets,
                wildcard = policy.wildcard,
                condition = policy.condition,
                policyGroup = policy.policyGroup,
                evidenceSourceId = policy.evidenceSourceId,
            )
        }.sortedWith(compareBy(RuntimeSecurityPolicySnapshot::roleId, RuntimeSecurityPolicySnapshot::type, RuntimeSecurityPolicySnapshot::id))

        val assignments = rawAssignments.map { assignment ->
            val candidates = allRoleIds[RoleKey(assignment.roleKind, assignment.roleCode)].orEmpty()
            val resolution = when (candidates.size) {
                0 -> RuntimeRoleAssignmentResolution.MISSING_ROLE
                1 -> RuntimeRoleAssignmentResolution.RESOLVED
                else -> RuntimeRoleAssignmentResolution.AMBIGUOUS_ROLE
            }
            if (resolution != RuntimeRoleAssignmentResolution.RESOLVED) {
                issues += RuntimeSecurityEvidenceIssue(
                    code = if (resolution == RuntimeRoleAssignmentResolution.MISSING_ROLE) {
                        "JVW-RUNTIME-SECURITY-ASSIGNMENT-ROLE-MISSING"
                    } else {
                        "JVW-RUNTIME-SECURITY-ASSIGNMENT-ROLE-AMBIGUOUS"
                    },
                    severity = if (resolution == RuntimeRoleAssignmentResolution.MISSING_ROLE) {
                        RuntimeSecurityEvidenceSeverity.WARNING
                    } else {
                        RuntimeSecurityEvidenceSeverity.ERROR
                    },
                    message = "Assignment for ${assignment.username} references ${assignment.roleKind.readableName()} " +
                        "role '${assignment.roleCode}', which is ${resolution.name.lowercase().replace('_', ' ')}.",
                    sourceId = assignment.evidenceSourceId,
                    username = assignment.username,
                )
            }
            RuntimeRoleAssignmentSnapshot(
                id = assignment.id,
                username = assignment.username,
                roleCode = assignment.roleCode,
                roleKind = assignment.roleKind,
                tenantId = assignment.tenantId,
                candidateRoleIds = candidates,
                resolution = resolution,
                evidenceSourceId = assignment.evidenceSourceId,
            )
        }.distinctBy { listOf(it.username, it.roleKind.name, it.roleCode, it.tenantId.orEmpty(), it.evidenceSourceId) }
            .sortedWith(compareBy(RuntimeRoleAssignmentSnapshot::username, RuntimeRoleAssignmentSnapshot::roleKind, RuntimeRoleAssignmentSnapshot::roleCode))

        val deduplicatedIssues = issues.distinctBy {
            listOf(it.code, it.message, it.sourceId.orEmpty(), it.roleId.orEmpty(), it.username.orEmpty())
        }.sortedWith(compareByDescending<RuntimeSecurityEvidenceIssue> { it.severity }.thenBy { it.code }.thenBy { it.message })
        val principals = assignments.map(RuntimeRoleAssignmentSnapshot::username).distinct().sorted()
        return RuntimeSecurityEvidenceSnapshot(
            sources = parsedImports.map(ParsedEvidence::source),
            roles = roles,
            policies = policies,
            assignments = assignments,
            principals = principals,
            issues = deduplicatedIssues,
            summary = RuntimeSecurityEvidenceSummary(
                sourceCount = parsedImports.size,
                roleCount = roles.size,
                policyCount = policies.size,
                assignmentCount = assignments.size,
                principalCount = principals.size,
                errorCount = deduplicatedIssues.count { it.severity == RuntimeSecurityEvidenceSeverity.ERROR },
                warningCount = deduplicatedIssues.count { it.severity == RuntimeSecurityEvidenceSeverity.WARNING },
            ),
        )
    }

    private fun matchTargets(
        policy: ParsedPolicy,
        surfaces: List<SecuritySurfaceSnapshot>,
    ): List<String> {
        if (policy.wildcard) {
            return surfaces.filter { surface ->
                when (policy.type) {
                    "EntityPolicy" -> surface.kind == SecuritySurfaceKind.ENTITY
                    "EntityAttributePolicy" -> surface.kind == SecuritySurfaceKind.ATTRIBUTE
                    "ViewPolicy" -> surface.kind == SecuritySurfaceKind.VIEW
                    "MenuPolicy" -> surface.kind == SecuritySurfaceKind.MENU
                    "UiComponentPolicy" -> surface.kind == SecuritySurfaceKind.COMPONENT
                    "SpecificPolicy" -> surface.kind == SecuritySurfaceKind.REST
                    "JpqlRowLevelPolicy", "PredicateRowLevelPolicy" -> surface.kind == SecuritySurfaceKind.ENTITY
                    else -> false
                }
            }.map(SecuritySurfaceSnapshot::artifactId).sorted()
        }
        return surfaces.filter { surface ->
            policy.resourceExpressions.any { resource -> resourceMatches(policy.type, resource, surface) }
        }.map(SecuritySurfaceSnapshot::artifactId).distinct().sorted()
    }

    private fun resourceMatches(type: String, resource: String, surface: SecuritySurfaceSnapshot): Boolean {
        val normalized = resource.trim()
        return when (type) {
            "MenuPolicy" -> surface.kind == SecuritySurfaceKind.MENU &&
                matchesNamedSurface(normalized, surface)
            "ViewPolicy" -> surface.kind == SecuritySurfaceKind.VIEW &&
                matchesNamedSurface(normalized, surface)
            "EntityPolicy", "JpqlRowLevelPolicy", "PredicateRowLevelPolicy" ->
                surface.kind == SecuritySurfaceKind.ENTITY && matchesEntity(normalized, surface.semanticKey, surface.displayName)
            "EntityAttributePolicy" -> {
                if (surface.kind != SecuritySurfaceKind.ATTRIBUTE) return false
                val entityName = normalized.substringBeforeLast('.', missingDelimiterValue = "")
                val attributeName = normalized.substringAfterLast('.')
                val ownerSemanticKey = surface.semanticKey.substringBeforeLast('.', missingDelimiterValue = "")
                entityName.isNotBlank() &&
                    attributeName == surface.displayName &&
                    matchesEntity(entityName, ownerSemanticKey, ownerSemanticKey.substringAfterLast('.'))
            }
            "SpecificPolicy" -> surface.kind == SecuritySurfaceKind.REST &&
                (normalized == "rest.enabled" || matchesNamedSurface(normalized, surface))
            else -> false
        }
    }

    private fun matchesNamedSurface(resource: String, surface: SecuritySurfaceSnapshot): Boolean =
        resource == surface.semanticKey ||
            resource == surface.displayName ||
            surface.semanticKey.endsWith("#$resource") ||
            surface.semanticKey.endsWith(":$resource")

    private fun matchesEntity(resource: String, semanticKey: String, displayName: String): Boolean =
        resource == semanticKey ||
            resource == displayName ||
            resource.substringAfterLast('_') == displayName ||
            resource.substringAfterLast('.') == displayName

    private fun SecurityRoleKind.readableName(): String =
        if (this == SecurityRoleKind.RESOURCE) "resource" else "row-level"

    private fun rejected(
        code: String,
        message: String,
        additionalIssues: List<RuntimeSecurityEvidenceIssue> = emptyList(),
    ) = RuntimeSecurityEvidenceImportResponse(
        accepted = false,
        sourceId = null,
        message = message,
        issues = additionalIssues + RuntimeSecurityEvidenceIssue(code, RuntimeSecurityEvidenceSeverity.ERROR, message),
    )

    companion object {
        private const val MAX_DECODED_BYTES = 10 * 1024 * 1024
        private const val MAX_BASE64_CHARACTERS = 14 * 1024 * 1024

        fun getInstance(project: Project): RuntimeSecurityEvidenceService = project.service()
    }
}

private object RuntimeSecurityEvidenceParser {
    private const val MAX_ZIP_ENTRIES = 16
    private const val MAX_JSON_BYTES = 10 * 1024 * 1024
    private const val MAX_ROLES = 5_000
    private const val MAX_POLICIES = 50_000
    private const val MAX_ASSIGNMENTS = 100_000
    private const val WORKBENCH_SCHEMA = "jmix-workbench/security-evidence/v1"

    fun parse(
        sourceId: String,
        fileName: String,
        environmentLabel: String?,
        sha256: String,
        bytes: ByteArray,
        importedAt: String,
    ): ParsedEvidence {
        val jsonDocuments = if (isZip(bytes)) readZipDocuments(bytes) else listOf(bytes)
        val roles = mutableListOf<ParsedRole>()
        val policies = mutableListOf<ParsedPolicy>()
        val assignments = mutableListOf<ParsedAssignment>()
        val issues = mutableListOf<RuntimeSecurityEvidenceIssue>()
        var workbenchFormat = false

        jsonDocuments.forEachIndexed { documentIndex, document ->
            if (document.size > MAX_JSON_BYTES) {
                error("JSON entry ${documentIndex + 1} exceeds the ${MAX_JSON_BYTES / (1024 * 1024)} MiB limit.")
            }
            val text = document.toString(StandardCharsets.UTF_8)
            val root = JsonParser.parseString(text)
            when {
                root.isJsonArray -> parseJmixEntityArray(
                    root.asJsonArray,
                    sourceId,
                    roles,
                    policies,
                    assignments,
                    issues,
                )
                root.isJsonObject && root.asJsonObject.string("schema") == WORKBENCH_SCHEMA -> {
                    workbenchFormat = true
                    parseWorkbenchEvidence(
                        root.asJsonObject,
                        sourceId,
                        roles,
                        policies,
                        assignments,
                        issues,
                    )
                }
                else -> error(
                    "JSON entry ${documentIndex + 1} is neither a Jmix entity export array nor $WORKBENCH_SCHEMA.",
                )
            }
        }
        enforceCount("roles", roles.size, MAX_ROLES)
        enforceCount("policies", policies.size, MAX_POLICIES)
        enforceCount("assignments", assignments.size, MAX_ASSIGNMENTS)

        val roleIds = roles.map(ParsedRole::id).toSet()
        val orphanPolicies = policies.filter { it.roleId !in roleIds }
        if (orphanPolicies.isNotEmpty()) {
            issues += RuntimeSecurityEvidenceIssue(
                code = "JVW-RUNTIME-SECURITY-ORPHAN-POLICY",
                severity = RuntimeSecurityEvidenceSeverity.WARNING,
                message = "${orphanPolicies.size} runtime policy record(s) have no imported owning role.",
                sourceId = sourceId,
            )
        }

        val roleKinds = roles.map(ParsedRole::kind).toSet()
        val format = when {
            workbenchFormat -> RuntimeSecurityEvidenceFormat.JMIX_WORKBENCH_EVIDENCE_V1
            roles.isEmpty() && assignments.isNotEmpty() -> RuntimeSecurityEvidenceFormat.JMIX_ROLE_ASSIGNMENT_JSON
            roleKinds == setOf(SecurityRoleKind.RESOURCE) && assignments.isEmpty() ->
                RuntimeSecurityEvidenceFormat.JMIX_RESOURCE_ROLE_JSON
            roleKinds == setOf(SecurityRoleKind.ROW_LEVEL) && assignments.isEmpty() ->
                RuntimeSecurityEvidenceFormat.JMIX_ROW_LEVEL_ROLE_JSON
            else -> RuntimeSecurityEvidenceFormat.JMIX_MIXED_ENTITY_JSON
        }
        return ParsedEvidence(
            source = RuntimeSecurityEvidenceSourceSnapshot(
                id = sourceId,
                fileName = fileName,
                environmentLabel = environmentLabel,
                format = format,
                sha256 = sha256,
                importedAt = importedAt,
                roleCount = roles.size,
                policyCount = policies.size,
                assignmentCount = assignments.size,
            ),
            roles = roles.distinctBy(ParsedRole::id),
            policies = policies.distinctBy(ParsedPolicy::id),
            assignments = assignments.distinctBy(ParsedAssignment::id),
            issues = issues,
        )
    }

    private fun parseJmixEntityArray(
        entities: JsonArray,
        sourceId: String,
        roles: MutableList<ParsedRole>,
        policies: MutableList<ParsedPolicy>,
        assignments: MutableList<ParsedAssignment>,
        issues: MutableList<RuntimeSecurityEvidenceIssue>,
    ) {
        entities.forEachIndexed { index, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val entity = element.asJsonObject
            when (entity.string("_entityName")) {
                "sec_ResourceRoleEntity" -> parseJmixRole(
                    entity,
                    sourceId,
                    index,
                    SecurityRoleKind.RESOURCE,
                    roles,
                    policies,
                    issues,
                )
                "sec_RowLevelRoleEntity" -> parseJmixRole(
                    entity,
                    sourceId,
                    index,
                    SecurityRoleKind.ROW_LEVEL,
                    roles,
                    policies,
                    issues,
                )
                "sec_RoleAssignmentEntity" -> parseJmixAssignment(entity, sourceId, index, assignments, issues)
                else -> issues += RuntimeSecurityEvidenceIssue(
                    code = "JVW-RUNTIME-SECURITY-ENTITY-IGNORED",
                    severity = RuntimeSecurityEvidenceSeverity.INFO,
                    message = "Ignored unsupported Jmix entity '${entity.string("_entityName") ?: "<missing>"}'.",
                    sourceId = sourceId,
                )
            }
        }
    }

    private fun parseJmixRole(
        entity: JsonObject,
        sourceId: String,
        index: Int,
        kind: SecurityRoleKind,
        roles: MutableList<ParsedRole>,
        policies: MutableList<ParsedPolicy>,
        issues: MutableList<RuntimeSecurityEvidenceIssue>,
    ) {
        val code = entity.string("code")?.trim().orEmpty()
        val name = entity.string("name")?.trim().orEmpty()
        if (code.isBlank() || name.isBlank()) {
            issues += RuntimeSecurityEvidenceIssue(
                code = "JVW-RUNTIME-SECURITY-ROLE-INVALID",
                severity = RuntimeSecurityEvidenceSeverity.ERROR,
                message = "Ignored ${kind.name.lowercase()} role at index $index because name or code is missing.",
                sourceId = sourceId,
            )
            return
        }
        val stableKey = entity.string("id") ?: "$kind:$code:${entity.string("sysTenantId").orEmpty()}:$index"
        val roleId = runtimeId(sourceId, "role", stableKey)
        val policyArrayName = if (kind == SecurityRoleKind.RESOURCE) "resourcePolicies" else "rowLevelPolicies"
        val policyArray = entity.array(policyArrayName)
        val scopes = if (kind == SecurityRoleKind.RESOURCE) entity.stringList("scopes") else listOf("ALL")
        if (kind == SecurityRoleKind.RESOURCE && scopes.isEmpty()) {
            issues += RuntimeSecurityEvidenceIssue(
                code = "JVW-RUNTIME-SECURITY-ROLE-SCOPE-MISSING",
                severity = RuntimeSecurityEvidenceSeverity.WARNING,
                message = "Runtime resource role '$name' has no UI or API scope and will not grant scoped access.",
                sourceId = sourceId,
            )
        }
        roles += ParsedRole(
            id = roleId,
            name = name,
            code = code,
            description = entity.string("description"),
            kind = kind,
            scopes = scopes,
            childRoleCodes = entity.stringList("childRoles"),
            tenantId = entity.string("sysTenantId"),
            evidenceSourceId = sourceId,
        )
        policyArray.forEachIndexed { policyIndex, policyElement ->
            if (!policyElement.isJsonObject) return@forEachIndexed
            parseJmixPolicy(
                policyElement.asJsonObject,
                sourceId,
                roleId,
                kind,
                policyIndex,
                policies,
                issues,
            )
        }
    }

    private fun parseJmixPolicy(
        entity: JsonObject,
        sourceId: String,
        roleId: String,
        kind: SecurityRoleKind,
        index: Int,
        policies: MutableList<ParsedPolicy>,
        issues: MutableList<RuntimeSecurityEvidenceIssue>,
    ) {
        val rawType = entity.string("type")?.trim().orEmpty()
        val stableKey = entity.string("id") ?: "$roleId:$rawType:$index"
        if (kind == SecurityRoleKind.RESOURCE) {
            val resource = entity.string("resource")?.trim().orEmpty()
            val mappedType = when (rawType) {
                "screen" -> "ViewPolicy"
                "menu" -> "MenuPolicy"
                "entity" -> "EntityPolicy"
                "entityAttribute" -> "EntityAttributePolicy"
                "specific" -> "SpecificPolicy"
                else -> rawType.takeIf(String::isNotBlank)?.let { "RuntimeResourcePolicy:$it" }
            }
            if (mappedType == null || resource.isBlank()) {
                issues += invalidPolicyIssue(sourceId, roleId, index)
                return
            }
            policies += ParsedPolicy(
                id = runtimeId(sourceId, "policy", stableKey),
                roleId = roleId,
                type = mappedType,
                effect = when (entity.string("effect")?.lowercase(Locale.ROOT)) {
                    null, "", "allow" -> SecurityPolicyEffect.GRANT
                    "deny" -> SecurityPolicyEffect.DENY
                    else -> SecurityPolicyEffect.UNKNOWN
                },
                actions = listOfNotNull(entity.string("action")?.trim()?.uppercase(Locale.ROOT)).filter(String::isNotBlank),
                resourceExpressions = listOf(resource),
                wildcard = resource == "*" || resource.endsWith(".*"),
                condition = null,
                policyGroup = entity.string("policyGroup"),
                evidenceSourceId = sourceId,
            )
        } else {
            val entityName = entity.string("entityName")?.trim().orEmpty()
            val mappedType = when (rawType.lowercase(Locale.ROOT)) {
                "jpql" -> "JpqlRowLevelPolicy"
                "predicate" -> "PredicateRowLevelPolicy"
                else -> rawType.takeIf(String::isNotBlank)?.let { "RuntimeRowPolicy:$it" }
            }
            if (mappedType == null || entityName.isBlank()) {
                issues += invalidPolicyIssue(sourceId, roleId, index)
                return
            }
            val condition = if (mappedType == "JpqlRowLevelPolicy") {
                listOfNotNull(
                    entity.string("joinClause")?.takeIf(String::isNotBlank)?.let { "join: $it" },
                    entity.string("whereClause")?.takeIf(String::isNotBlank)?.let { "where: $it" },
                ).joinToString(" · ").ifBlank { null }
            } else {
                entity.string("script")?.takeIf(String::isNotBlank)?.let { "predicate: $it" }
            }
            policies += ParsedPolicy(
                id = runtimeId(sourceId, "policy", stableKey),
                roleId = roleId,
                type = mappedType,
                effect = SecurityPolicyEffect.RESTRICT,
                actions = listOfNotNull(entity.string("action")?.trim()?.uppercase(Locale.ROOT)).filter(String::isNotBlank),
                resourceExpressions = listOf(entityName),
                wildcard = entityName == "*",
                condition = condition,
                policyGroup = null,
                evidenceSourceId = sourceId,
            )
        }
    }

    private fun parseJmixAssignment(
        entity: JsonObject,
        sourceId: String,
        index: Int,
        assignments: MutableList<ParsedAssignment>,
        issues: MutableList<RuntimeSecurityEvidenceIssue>,
    ) {
        val username = entity.string("username")?.trim().orEmpty()
        val roleCode = entity.string("roleCode")?.trim().orEmpty()
        val roleKind = roleKind(entity.string("roleType"))
        if (username.isBlank() || roleCode.isBlank() || roleKind == null) {
            issues += RuntimeSecurityEvidenceIssue(
                code = "JVW-RUNTIME-SECURITY-ASSIGNMENT-INVALID",
                severity = RuntimeSecurityEvidenceSeverity.ERROR,
                message = "Ignored role assignment at index $index because username, roleCode, or roleType is invalid.",
                sourceId = sourceId,
            )
            return
        }
        val stableKey = entity.string("id") ?: "$username:${roleKind.name}:$roleCode:$index"
        assignments += ParsedAssignment(
            id = runtimeId(sourceId, "assignment", stableKey),
            username = username,
            roleCode = roleCode,
            roleKind = roleKind,
            tenantId = entity.string("sysTenantId") ?: entity.string("tenantId"),
            evidenceSourceId = sourceId,
        )
    }

    private fun parseWorkbenchEvidence(
        root: JsonObject,
        sourceId: String,
        roles: MutableList<ParsedRole>,
        policies: MutableList<ParsedPolicy>,
        assignments: MutableList<ParsedAssignment>,
        issues: MutableList<RuntimeSecurityEvidenceIssue>,
    ) {
        root.array("roles").forEachIndexed { index, roleElement ->
            if (!roleElement.isJsonObject) return@forEachIndexed
            val role = roleElement.asJsonObject
            val kind = roleKind(role.string("kind") ?: role.string("roleType"))
            val code = role.string("code")?.trim().orEmpty()
            val name = role.string("name")?.trim().orEmpty()
            if (kind == null || code.isBlank() || name.isBlank()) {
                issues += RuntimeSecurityEvidenceIssue(
                    code = "JVW-RUNTIME-SECURITY-ROLE-INVALID",
                    severity = RuntimeSecurityEvidenceSeverity.ERROR,
                    message = "Ignored workbench role at index $index because kind, name, or code is invalid.",
                    sourceId = sourceId,
                )
                return@forEachIndexed
            }
            val stableKey = role.string("id") ?: "$kind:$code:${role.string("tenantId").orEmpty()}:$index"
            val roleId = runtimeId(sourceId, "role", stableKey)
            val scopes = if (kind == SecurityRoleKind.RESOURCE) role.stringList("scopes") else listOf("ALL")
            if (kind == SecurityRoleKind.RESOURCE && scopes.isEmpty()) {
                issues += RuntimeSecurityEvidenceIssue(
                    code = "JVW-RUNTIME-SECURITY-ROLE-SCOPE-MISSING",
                    severity = RuntimeSecurityEvidenceSeverity.WARNING,
                    message = "Runtime resource role '$name' has no UI or API scope and will not grant scoped access.",
                    sourceId = sourceId,
                )
            }
            roles += ParsedRole(
                id = roleId,
                name = name,
                code = code,
                description = role.string("description"),
                kind = kind,
                scopes = scopes,
                childRoleCodes = role.stringList("childRoles"),
                tenantId = role.string("tenantId"),
                evidenceSourceId = sourceId,
            )
            role.array("policies").forEachIndexed { policyIndex, policy ->
                if (policy.isJsonObject) {
                    parseWorkbenchPolicy(
                        policy.asJsonObject,
                        sourceId,
                        roleId,
                        kind,
                        policyIndex,
                        policies,
                        issues,
                    )
                }
            }
        }
        root.array("assignments").forEachIndexed { index, assignmentElement ->
            if (!assignmentElement.isJsonObject) return@forEachIndexed
            val assignment = assignmentElement.asJsonObject
            val translated = JsonObject().apply {
                addProperty("id", assignment.string("id"))
                addProperty("username", assignment.string("username"))
                addProperty("roleCode", assignment.string("roleCode"))
                addProperty("roleType", assignment.string("roleType") ?: assignment.string("kind"))
                addProperty("tenantId", assignment.string("tenantId"))
            }
            parseJmixAssignment(translated, sourceId, index, assignments, issues)
        }
    }

    private fun parseWorkbenchPolicy(
        policy: JsonObject,
        sourceId: String,
        roleId: String,
        kind: SecurityRoleKind,
        index: Int,
        policies: MutableList<ParsedPolicy>,
        issues: MutableList<RuntimeSecurityEvidenceIssue>,
    ) {
        val translated = JsonObject()
        policy.entrySet().forEach { (key, value) -> translated.add(key, value) }
        if (kind == SecurityRoleKind.RESOURCE) {
            translated.addProperty("type", policy.string("type")?.let(::resourceTypeId))
            translated.addProperty("resource", policy.string("resource") ?: policy.stringList("resources").firstOrNull())
        } else {
            translated.addProperty("type", policy.string("type")?.let(::rowTypeId))
            translated.addProperty("entityName", policy.string("entityName") ?: policy.string("resource"))
        }
        parseJmixPolicy(translated, sourceId, roleId, kind, index, policies, issues)
    }

    private fun resourceTypeId(type: String): String = when (type.lowercase(Locale.ROOT)) {
        "viewpolicy", "screen" -> "screen"
        "menupolicy", "menu" -> "menu"
        "entitypolicy", "entity" -> "entity"
        "entityattributepolicy", "entityattribute" -> "entityAttribute"
        "specificpolicy", "specific" -> "specific"
        else -> type
    }

    private fun rowTypeId(type: String): String = when (type.lowercase(Locale.ROOT)) {
        "jpqlrowlevelpolicy", "jpql" -> "jpql"
        "predicaterowlevelpolicy", "predicate" -> "predicate"
        else -> type
    }

    private fun invalidPolicyIssue(sourceId: String, roleId: String, index: Int) =
        RuntimeSecurityEvidenceIssue(
            code = "JVW-RUNTIME-SECURITY-POLICY-INVALID",
            severity = RuntimeSecurityEvidenceSeverity.ERROR,
            message = "Ignored runtime policy at index $index because its type or resource is missing.",
            sourceId = sourceId,
            roleId = roleId,
        )

    private fun roleKind(raw: String?): SecurityRoleKind? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "resource", "resource_role", "resourcerole" -> SecurityRoleKind.RESOURCE
        "row_level", "row-level", "rowlevel", "row_level_role", "rowlevelrole" -> SecurityRoleKind.ROW_LEVEL
        else -> null
    }

    private fun readZipDocuments(bytes: ByteArray): List<ByteArray> {
        val documents = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes), StandardCharsets.UTF_8).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ZIP_ENTRIES) error("ZIP contains more than $MAX_ZIP_ENTRIES entries.")
                if (entry.isDirectory) continue
                if (!entry.name.lowercase(Locale.ROOT).endsWith(".json")) {
                    error("ZIP entry '${entry.name}' is not JSON.")
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_JSON_BYTES) {
                        error("ZIP entry '${entry.name}' exceeds the ${MAX_JSON_BYTES / (1024 * 1024)} MiB limit.")
                    }
                    output.write(buffer, 0, read)
                }
                documents += output.toByteArray()
                zip.closeEntry()
            }
        }
        if (documents.isEmpty()) error("ZIP does not contain a JSON evidence entry.")
        return documents
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4b.toByte() &&
            bytes[2] in setOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte()) &&
            bytes[3] in setOf(0x04.toByte(), 0x06.toByte(), 0x08.toByte())

    private fun enforceCount(label: String, count: Int, maximum: Int) {
        if (count > maximum) error("Evidence contains $count $label; the safe maximum is $maximum.")
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.takeIf(JsonElement::isJsonPrimitive)?.asString

    private fun JsonObject.array(name: String): JsonArray =
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray()

    private fun JsonObject.stringList(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        return when {
            value.isJsonArray -> value.asJsonArray.mapNotNull {
                it.takeUnless(JsonElement::isJsonNull)?.takeIf(JsonElement::isJsonPrimitive)?.asString
            }
            value.isJsonPrimitive -> value.asString.split(',').map(String::trim).filter(String::isNotBlank)
            else -> emptyList()
        }.distinct()
    }
}

private data class ParsedEvidence(
    val source: RuntimeSecurityEvidenceSourceSnapshot,
    val roles: List<ParsedRole>,
    val policies: List<ParsedPolicy>,
    val assignments: List<ParsedAssignment>,
    val issues: List<RuntimeSecurityEvidenceIssue>,
)

private data class ParsedRole(
    val id: String,
    val name: String,
    val code: String,
    val description: String?,
    val kind: SecurityRoleKind,
    val scopes: List<String>,
    val childRoleCodes: List<String>,
    val tenantId: String?,
    val evidenceSourceId: String,
)

private data class ParsedPolicy(
    val id: String,
    val roleId: String,
    val type: String,
    val effect: SecurityPolicyEffect,
    val actions: List<String>,
    val resourceExpressions: List<String>,
    val wildcard: Boolean,
    val condition: String?,
    val policyGroup: String?,
    val evidenceSourceId: String,
)

private data class ParsedAssignment(
    val id: String,
    val username: String,
    val roleCode: String,
    val roleKind: SecurityRoleKind,
    val tenantId: String?,
    val evidenceSourceId: String,
)

private data class RoleKey(
    val kind: SecurityRoleKind,
    val code: String,
)

private fun runtimeId(sourceId: String, kind: String, stableKey: String): String =
    "$sourceId:$kind:${sha256(stableKey.toByteArray(StandardCharsets.UTF_8)).take(20)}"

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

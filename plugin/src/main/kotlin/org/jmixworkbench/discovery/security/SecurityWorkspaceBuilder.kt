package org.jmixworkbench.discovery.security

import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactRelationship
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.DiagnosticCategory
import org.jmixworkbench.discovery.model.DiagnosticSeverity
import org.jmixworkbench.discovery.model.DiscoveryDiagnostic
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.discovery.model.SourceLocator

data class SecurityWorkspaceInput(
    val artifacts: List<ArtifactSnapshot>,
    val relationships: List<ArtifactRelationship>,
    val diagnostics: List<DiscoveryDiagnostic>,
    val graphDigest: String,
)

data class SecurityWorkspaceSnapshot(
    val graphDigest: String,
    val roles: List<SecurityRoleSnapshot>,
    val policies: List<SecurityPolicySnapshot>,
    val surfaces: List<SecuritySurfaceSnapshot>,
    val menuRoutes: List<SecurityMenuRouteSnapshot>,
    val journeys: List<SecurityJourneySnapshot>,
    val findings: List<SecurityFindingSnapshot>,
    val summary: SecurityWorkspaceSummary,
    val runtime: RuntimeSecurityEvidenceSnapshot = RuntimeSecurityEvidenceSnapshot(),
)

enum class SecurityRoleKind {
    RESOURCE,
    ROW_LEVEL,
}

data class SecurityRoleSnapshot(
    val id: String,
    val className: String,
    val name: String,
    val code: String,
    val kind: SecurityRoleKind,
    val scopes: List<String>,
    val moduleId: String,
    val policyIds: List<String>,
    val inheritedRoleIds: List<String>,
    val unresolvedBaseRoleCount: Int,
    val sourceLocator: SourceLocator,
)

enum class SecurityPolicyEffect {
    GRANT,
    RESTRICT,
    DENY,
    UNKNOWN,
}

data class SecurityPolicySnapshot(
    val id: String,
    val roleId: String,
    val type: String,
    val effect: SecurityPolicyEffect,
    val actions: List<String>,
    val resourceExpressions: List<String>,
    val targetArtifactIds: List<String>,
    val wildcard: Boolean,
    val condition: String?,
    val sourceLocator: SourceLocator,
)

enum class SecuritySurfaceKind {
    MENU,
    VIEW,
    ENTITY,
    ATTRIBUTE,
    REST,
    COMPONENT,
}

data class SecuritySurfaceSnapshot(
    val artifactId: String,
    val kind: SecuritySurfaceKind,
    val displayName: String,
    val semanticKey: String,
    val moduleId: String,
    val grantingRoleIds: List<String>,
    val restrictingRoleIds: List<String>,
    val sourceLocator: SourceLocator,
)

data class SecurityMenuRouteSnapshot(
    val menuArtifactId: String,
    val viewArtifactId: String?,
    val menuId: String,
    val viewId: String?,
    val sourceLocator: SourceLocator,
)

data class SecurityJourneySnapshot(
    val menuArtifactId: String,
    val menuId: String,
    val menuPathArtifactIds: List<String>,
    val menuPathIds: List<String>,
    val viewArtifactId: String?,
    val viewId: String?,
    val entityArtifactIds: List<String>,
    val attributeArtifactIds: List<String>,
    val componentArtifactIds: List<String>,
    val unresolvedDependencyCount: Int,
    val sourceLocator: SourceLocator,
)

data class SecurityFindingSnapshot(
    val code: String,
    val severity: DiagnosticSeverity,
    val title: String,
    val message: String,
    val remediation: String?,
    val roleId: String? = null,
    val artifactId: String? = null,
    val sourceLocator: SourceLocator? = null,
)

data class SecurityWorkspaceSummary(
    val resourceRoleCount: Int,
    val rowRoleCount: Int,
    val policyCount: Int,
    val coveredSurfaceCount: Int,
    val uncoveredMenuCount: Int,
    val uncoveredViewCount: Int,
    val errorCount: Int,
    val warningCount: Int,
)

object SecurityWorkspaceBuilder {
    fun build(input: SecurityWorkspaceInput): SecurityWorkspaceSnapshot {
        val artifactsById = input.artifacts.associateBy(ArtifactSnapshot::id)
        val policyArtifacts = input.artifacts.filter { it.kind == ArtifactKind.SECURITY_POLICY }
        val roleArtifacts = input.artifacts.filter {
            it.kind == ArtifactKind.RESOURCE_ROLE || it.kind == ArtifactKind.ROW_ROLE
        }
        val declaredPolicyIds = input.relationships
            .filter { it.type == RelationshipType.DECLARES && it.targetArtifactId in policyArtifacts.map(ArtifactSnapshot::id) }
            .groupBy(ArtifactRelationship::sourceArtifactId)
            .mapValues { (_, links) -> links.mapNotNull(ArtifactRelationship::targetArtifactId).distinct().sorted() }
        val inheritance = input.relationships
            .filter { it.type == RelationshipType.EXTENDS && it.sourceArtifactId in roleArtifacts.map(ArtifactSnapshot::id) }
            .groupBy(ArtifactRelationship::sourceArtifactId)
        val policyTargets = input.relationships
            .filter { it.type == RelationshipType.APPLIES_POLICY_TO && it.sourceArtifactId in policyArtifacts.map(ArtifactSnapshot::id) }
            .groupBy(ArtifactRelationship::sourceArtifactId)
            .mapValues { (_, links) -> links.mapNotNull(ArtifactRelationship::targetArtifactId).distinct().sorted() }
        val policyOwner = declaredPolicyIds.flatMap { (roleId, policyIds) ->
            policyIds.map { policyId -> policyId to roleId }
        }.toMap()

        val roles = roleArtifacts.map { artifact ->
            val metadata = parseRoleMetadata(artifact)
            val baseLinks = inheritance[artifact.id].orEmpty()
            SecurityRoleSnapshot(
                id = artifact.id,
                className = artifact.semanticKey,
                name = metadata.name ?: artifact.displayName,
                code = metadata.code ?: artifact.semanticKey.substringAfterLast('.'),
                kind = if (artifact.kind == ArtifactKind.ROW_ROLE) {
                    SecurityRoleKind.ROW_LEVEL
                } else {
                    SecurityRoleKind.RESOURCE
                },
                scopes = metadata.scopes,
                moduleId = artifact.owner.moduleId,
                policyIds = declaredPolicyIds[artifact.id].orEmpty(),
                inheritedRoleIds = baseLinks.mapNotNull(ArtifactRelationship::targetArtifactId).distinct().sorted(),
                unresolvedBaseRoleCount = baseLinks.count { it.targetArtifactId == null },
                sourceLocator = artifact.sourceLocator,
            )
        }.sortedWith(compareBy(SecurityRoleSnapshot::kind, SecurityRoleSnapshot::code, SecurityRoleSnapshot::className))
        val rolesById = roles.associateBy(SecurityRoleSnapshot::id)

        val policies = policyArtifacts.map { artifact ->
            val parsed = parsePolicy(artifact)
            SecurityPolicySnapshot(
                id = artifact.id,
                roleId = policyOwner[artifact.id].orEmpty(),
                type = artifact.displayName,
                effect = parsed.effect,
                actions = parsed.actions,
                resourceExpressions = parsed.resources,
                targetArtifactIds = policyTargets[artifact.id].orEmpty(),
                wildcard = parsed.wildcard,
                condition = parsed.condition,
                sourceLocator = artifact.sourceLocator,
            )
        }.sortedWith(compareBy(SecurityPolicySnapshot::roleId, SecurityPolicySnapshot::type, SecurityPolicySnapshot::id))
        val policiesById = policies.associateBy(SecurityPolicySnapshot::id)

        val effectivePolicyIdsByRole = roles.associate { role ->
            role.id to effectiveRoleIds(role.id, rolesById).flatMap { roleId ->
                rolesById[roleId]?.policyIds.orEmpty()
            }.distinct()
        }
        val grantingRolesByArtifact = linkedMapOf<String, MutableSet<String>>()
        val restrictingRolesByArtifact = linkedMapOf<String, MutableSet<String>>()
        roles.forEach { role ->
            effectivePolicyIdsByRole[role.id].orEmpty().mapNotNull(policiesById::get).forEach { policy ->
                val targetIds = if (policy.wildcard) {
                    input.artifacts.filter { wildcardAppliesTo(policy.type, it.kind) }.map(ArtifactSnapshot::id)
                } else {
                    policy.targetArtifactIds
                }
                val destination = if (policy.effect == SecurityPolicyEffect.GRANT) {
                    grantingRolesByArtifact
                } else {
                    restrictingRolesByArtifact
                }
                targetIds.forEach { artifactId ->
                    destination.getOrPut(artifactId) { linkedSetOf() } += role.id
                }
            }
        }

        val surfaces = input.artifacts.mapNotNull { artifact ->
            val surfaceKind = surfaceKind(artifact.kind) ?: return@mapNotNull null
            SecuritySurfaceSnapshot(
                artifactId = artifact.id,
                kind = surfaceKind,
                displayName = artifact.displayName,
                semanticKey = artifact.semanticKey,
                moduleId = artifact.owner.moduleId,
                grantingRoleIds = grantingRolesByArtifact[artifact.id].orEmpty().sorted(),
                restrictingRoleIds = restrictingRolesByArtifact[artifact.id].orEmpty().sorted(),
                sourceLocator = artifact.sourceLocator,
            )
        }.sortedWith(compareBy(SecuritySurfaceSnapshot::kind, SecuritySurfaceSnapshot::displayName))

        val menuRoutes = input.relationships.filter { relationship ->
            relationship.type == RelationshipType.NAVIGATES_TO &&
                artifactsById[relationship.sourceArtifactId]?.kind == ArtifactKind.MENU_ITEM
        }.map { relationship ->
            val menu = artifactsById.getValue(relationship.sourceArtifactId)
            val view = relationship.targetArtifactId?.let(artifactsById::get)
            SecurityMenuRouteSnapshot(
                menuArtifactId = menu.id,
                viewArtifactId = view?.id,
                menuId = menu.displayName,
                viewId = view?.displayName,
                sourceLocator = relationship.sourceLocator,
            )
        }.sortedBy(SecurityMenuRouteSnapshot::menuId)
        val menuParentByChild = input.relationships
            .filter { relationship ->
                relationship.type == RelationshipType.DECLARES &&
                    artifactsById[relationship.sourceArtifactId]?.kind == ArtifactKind.MENU_ITEM &&
                    relationship.targetArtifactId?.let(artifactsById::get)?.kind == ArtifactKind.MENU_ITEM
            }
            .mapNotNull { relationship ->
                relationship.targetArtifactId?.let { childId -> childId to relationship.sourceArtifactId }
            }
            .toMap()
        val traversableJourneyLinks = setOf(
            RelationshipType.DECLARES,
            RelationshipType.BINDS_TO_ENTITY,
            RelationshipType.BINDS_TO_ATTRIBUTE,
            RelationshipType.LOADS_ENTITY,
            RelationshipType.EXECUTES_QUERY,
        )
        val outgoingBySource = input.relationships
            .filter { it.type in traversableJourneyLinks }
            .groupBy(ArtifactRelationship::sourceArtifactId)
        val journeys = menuRoutes.map { route ->
            val menuPath = buildList {
                val seen = mutableSetOf<String>()
                var current: String? = route.menuArtifactId
                while (current != null && seen.add(current)) {
                    add(current)
                    current = menuParentByChild[current]
                }
            }.reversed()
            val reachable = route.viewArtifactId?.let { viewId ->
                reachableArtifacts(viewId, outgoingBySource, maxDepth = 6)
            }.orEmpty()
            SecurityJourneySnapshot(
                menuArtifactId = route.menuArtifactId,
                menuId = route.menuId,
                menuPathArtifactIds = menuPath,
                menuPathIds = menuPath.mapNotNull { artifactsById[it]?.displayName },
                viewArtifactId = route.viewArtifactId,
                viewId = route.viewId,
                entityArtifactIds = reachable.filter { artifactsById[it]?.kind == ArtifactKind.ENTITY }.sorted(),
                attributeArtifactIds = reachable.filter { artifactsById[it]?.kind == ArtifactKind.ENTITY_ATTRIBUTE }.sorted(),
                componentArtifactIds = reachable.filter {
                    artifactsById[it]?.kind in setOf(ArtifactKind.UI_COMPONENT, ArtifactKind.UI_ACTION)
                }.sorted(),
                unresolvedDependencyCount = route.viewArtifactId?.let { viewId ->
                    reachableUnresolvedCount(viewId, outgoingBySource, maxDepth = 6)
                } ?: 1,
                sourceLocator = route.sourceLocator,
            )
        }

        val findings = buildFindings(
            input = input,
            roles = roles,
            policies = policies,
            surfaces = surfaces,
            menuRoutes = menuRoutes,
            journeys = journeys,
        )
        return SecurityWorkspaceSnapshot(
            graphDigest = input.graphDigest,
            roles = roles,
            policies = policies,
            surfaces = surfaces,
            menuRoutes = menuRoutes,
            journeys = journeys,
            findings = findings,
            summary = SecurityWorkspaceSummary(
                resourceRoleCount = roles.count { it.kind == SecurityRoleKind.RESOURCE },
                rowRoleCount = roles.count { it.kind == SecurityRoleKind.ROW_LEVEL },
                policyCount = policies.size,
                coveredSurfaceCount = surfaces.count { it.grantingRoleIds.isNotEmpty() || it.restrictingRoleIds.isNotEmpty() },
                uncoveredMenuCount = surfaces.count { it.kind == SecuritySurfaceKind.MENU && it.grantingRoleIds.isEmpty() },
                uncoveredViewCount = surfaces.count { it.kind == SecuritySurfaceKind.VIEW && it.grantingRoleIds.isEmpty() },
                errorCount = findings.count { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.BLOCKING },
                warningCount = findings.count { it.severity == DiagnosticSeverity.WARNING },
            ),
        )
    }

    private fun buildFindings(
        input: SecurityWorkspaceInput,
        roles: List<SecurityRoleSnapshot>,
        policies: List<SecurityPolicySnapshot>,
        surfaces: List<SecuritySurfaceSnapshot>,
        menuRoutes: List<SecurityMenuRouteSnapshot>,
        journeys: List<SecurityJourneySnapshot>,
    ): List<SecurityFindingSnapshot> {
        val findings = input.diagnostics
            .filter { it.category == DiagnosticCategory.SECURITY }
            .map { diagnostic ->
                SecurityFindingSnapshot(
                    code = diagnostic.reasonCode,
                    severity = diagnostic.severity,
                    title = readableCode(diagnostic.reasonCode),
                    message = diagnostic.message,
                    remediation = diagnostic.nextStep,
                    sourceLocator = diagnostic.sourceLocator,
                )
            }.toMutableList()
        val surfacesById = surfaces.associateBy(SecuritySurfaceSnapshot::artifactId)
        val policiesByRole = policies.groupBy(SecurityPolicySnapshot::roleId)

        roles.filter { policiesByRole[it.id].isNullOrEmpty() && it.inheritedRoleIds.isEmpty() }.forEach { role ->
            findings += SecurityFindingSnapshot(
                code = "JVW-SECURITY-EMPTY-ROLE",
                severity = DiagnosticSeverity.WARNING,
                title = "Empty design-time role",
                message = "${role.name} has no direct policies and inherits no base role.",
                remediation = "Add narrowly scoped policies or remove the role before assigning it.",
                roleId = role.id,
                sourceLocator = role.sourceLocator,
            )
        }
        surfaces.filter { it.kind == SecuritySurfaceKind.MENU && it.grantingRoleIds.isEmpty() }.forEach { surface ->
            findings += SecurityFindingSnapshot(
                code = "JVW-SECURITY-MENU-UNCOVERED",
                severity = DiagnosticSeverity.WARNING,
                title = "Menu item has no source-defined grant",
                message = "${surface.displayName} is declared in the menu but no indexed MenuPolicy grants it.",
                remediation = "Grant the menu item to an appropriate UI-scoped role or confirm a runtime role owns it.",
                artifactId = surface.artifactId,
                sourceLocator = surface.sourceLocator,
            )
        }
        menuRoutes.forEach { route ->
            val menu = surfacesById[route.menuArtifactId] ?: return@forEach
            val view = route.viewArtifactId?.let(surfacesById::get) ?: return@forEach
            menu.grantingRoleIds.forEach { roleId ->
                if (roleId !in view.grantingRoleIds) {
                    findings += SecurityFindingSnapshot(
                        code = "JVW-SECURITY-MENU-VIEW-MISMATCH",
                        severity = DiagnosticSeverity.ERROR,
                        title = "Menu grants a view the role cannot open",
                        message = "${roles.firstOrNull { it.id == roleId }?.name ?: roleId} can see ${route.menuId}, but has no indexed ViewPolicy for ${route.viewId}.",
                        remediation = "Grant the connected view to the same role or remove the menu grant.",
                        roleId = roleId,
                        artifactId = route.menuArtifactId,
                        sourceLocator = route.sourceLocator,
                    )
                }
            }
        }
        journeys.forEach { journey ->
            val pathSurfaces = journey.menuPathArtifactIds.mapNotNull(surfacesById::get)
            val leaf = pathSurfaces.lastOrNull() ?: return@forEach
            leaf.grantingRoleIds.forEach { roleId ->
                val missingAncestors = pathSurfaces.dropLast(1).filter { roleId !in it.grantingRoleIds }
                if (missingAncestors.isNotEmpty()) {
                    findings += SecurityFindingSnapshot(
                        code = "JVW-SECURITY-MENU-ANCESTOR-MISSING",
                        severity = DiagnosticSeverity.ERROR,
                        title = "Nested menu path is not fully granted",
                        message = "${roles.firstOrNull { it.id == roleId }?.name ?: roleId} can access ${journey.menuId}, but not parent menu ${missingAncestors.joinToString { it.displayName }}.",
                        remediation = "Grant every parent menu in the path: ${journey.menuPathIds.joinToString(" → ")}.",
                        roleId = roleId,
                        artifactId = journey.menuArtifactId,
                        sourceLocator = journey.sourceLocator,
                    )
                }
            }
            if (journey.viewArtifactId != null && journey.entityArtifactIds.isEmpty()) {
                findings += SecurityFindingSnapshot(
                    code = "JVW-SECURITY-VIEW-DATA-DEPENDENCY-UNRESOLVED",
                    severity = DiagnosticSeverity.INFO,
                    title = "View data dependency is not visible",
                    message = "${journey.viewId ?: journey.menuId} has no indexed entity binding or loader dependency.",
                    remediation = "Confirm the view is intentionally data-free or bind its containers and loaders explicitly.",
                    artifactId = journey.viewArtifactId,
                    sourceLocator = journey.sourceLocator,
                )
            }
        }
        val genericRestPresent = input.artifacts.any {
            it.kind == ArtifactKind.REST_SERVICE_CONFIG || it.kind == ArtifactKind.REST_QUERY_CONFIG
        }
        val restEnabled = policies.any {
            it.type == "SpecificPolicy" &&
                (it.wildcard || it.resourceExpressions.any { resource -> resource == "rest.enabled" })
        }
        if (genericRestPresent && !restEnabled) {
            findings += SecurityFindingSnapshot(
                code = "JVW-SECURITY-REST-ENABLED-POLICY-MISSING",
                severity = DiagnosticSeverity.ERROR,
                title = "Generic REST permission is missing",
                message = "REST service/query configuration exists, but no indexed SpecificPolicy grants rest.enabled.",
                remediation = "Grant rest.enabled only to API-scoped roles that also define the required entity and service permissions.",
            )
        }
        if (genericRestPresent && roles.any { it.kind == SecurityRoleKind.ROW_LEVEL }) {
            findings += SecurityFindingSnapshot(
                code = "JVW-SECURITY-REST-SERVICE-ROW-BOUNDARY",
                severity = DiagnosticSeverity.WARNING,
                title = "REST service row security depends on service code",
                message = "Jmix REST service parameters and results are not automatically checked against row-level policies.",
                remediation = "Verify every exposed service uses constrained DataManager and performs explicit authorization at its transaction boundary.",
            )
        }
        return findings.distinctBy {
            listOf(it.code, it.roleId, it.artifactId, it.sourceLocator?.relativePath, it.message)
        }.sortedWith(compareBy({ severityRank(it.severity) }, SecurityFindingSnapshot::code, SecurityFindingSnapshot::message))
    }

    private fun effectiveRoleIds(
        roleId: String,
        rolesById: Map<String, SecurityRoleSnapshot>,
    ): Set<String> {
        val result = linkedSetOf<String>()
        fun visit(current: String) {
            if (!result.add(current)) return
            rolesById[current]?.inheritedRoleIds.orEmpty().forEach(::visit)
        }
        visit(roleId)
        return result
    }

    private fun reachableArtifacts(
        startId: String,
        outgoingBySource: Map<String, List<ArtifactRelationship>>,
        maxDepth: Int,
    ): Set<String> {
        val result = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(startId to 0)
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue
            outgoingBySource[current].orEmpty().forEach { relationship ->
                val target = relationship.targetArtifactId ?: return@forEach
                if (result.add(target)) queue.add(target to depth + 1)
            }
        }
        result.remove(startId)
        return result
    }

    private fun reachableUnresolvedCount(
        startId: String,
        outgoingBySource: Map<String, List<ArtifactRelationship>>,
        maxDepth: Int,
    ): Int {
        val visited = mutableSetOf(startId)
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(startId to 0)
        var unresolved = 0
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue
            outgoingBySource[current].orEmpty().forEach { relationship ->
                val target = relationship.targetArtifactId
                if (target == null) {
                    unresolved += 1
                } else if (visited.add(target)) {
                    queue.add(target to depth + 1)
                }
            }
        }
        return unresolved
    }

    private fun parseRoleMetadata(artifact: ArtifactSnapshot): ParsedRole {
        val summary = artifact.summary.orEmpty()
        val name = Regex("""(?:^|[:,]\s*)name=([^,]+)""").find(summary)?.groupValues?.get(1)?.trim()
        val code = Regex("""(?:^|[:,]\s*)code=([^,]+)""").find(summary)?.groupValues?.get(1)?.trim()
        val scopeExpression = Regex("""(?:^|[:,]\s*)scope=([^,]+)""").find(summary)?.groupValues?.get(1)?.trim()
        val scopes = when {
            scopeExpression == null -> listOf("ALL")
            "UI" in scopeExpression && "API" in scopeExpression -> listOf("UI", "API")
            "UI" in scopeExpression -> listOf("UI")
            "API" in scopeExpression -> listOf("API")
            else -> listOf(scopeExpression.trim('"', '\''))
        }
        return ParsedRole(name, code, scopes)
    }

    private fun parsePolicy(artifact: ArtifactSnapshot): ParsedPolicy {
        val body = artifact.summary.orEmpty().substringAfter(':', "")
        val actions = enumArgument(body, "actions") + enumArgument(body, "action")
        val resources = when (artifact.displayName) {
            "EntityPolicy", "EntityAttributePolicy", "JpqlRowLevelPolicy", "PredicateRowLevelPolicy" ->
                listOfNotNull(entityExpression(body))
            "ViewPolicy" -> stringArrayArgument(body, "viewIds")
            "MenuPolicy" -> stringArrayArgument(body, "menuIds")
            "SpecificPolicy" -> stringArrayArgument(body, "resources")
            "UiComponentPolicy" -> {
                listOfNotNull(stringArgument(body, "viewId"), classArgument(body, "viewClass")) +
                    stringArrayArgument(body, "componentIds")
            }
            else -> emptyList()
        }.distinct()
        val effect = when (artifact.displayName) {
            "JpqlRowLevelPolicy", "PredicateRowLevelPolicy" -> SecurityPolicyEffect.RESTRICT
            "UiComponentPolicy" -> when {
                Regex("""\beffect\s*=\s*[^,\n)]*DENY\b""").containsMatchIn(body) -> SecurityPolicyEffect.DENY
                else -> SecurityPolicyEffect.UNKNOWN
            }
            else -> SecurityPolicyEffect.GRANT
        }
        val condition = when (artifact.displayName) {
            "JpqlRowLevelPolicy" -> listOfNotNull(
                stringArgument(body, "join")?.takeIf(String::isNotBlank)?.let { "join $it" },
                stringArgument(body, "where")?.takeIf(String::isNotBlank)?.let { "where $it" },
            ).joinToString(" · ").ifBlank { null }
            "PredicateRowLevelPolicy" -> "Predicate implemented in source"
            else -> null
        }
        return ParsedPolicy(
            effect = effect,
            actions = actions.distinct(),
            resources = resources,
            wildcard = "*" in resources || Regex("""\bALL\b""").containsMatchIn(body),
            condition = condition,
        )
    }

    private fun entityExpression(body: String): String? =
        classArgument(body, "entityClass") ?: stringArgument(body, "entityName")

    private fun classArgument(body: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*([A-Za-z_$][\w$.]*)\.class""")
            .find(body)?.groupValues?.get(1)

    private fun stringArgument(body: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""")
            .find(body)?.groupValues?.get(1)

    private fun stringArrayArgument(body: String, name: String): List<String> {
        val raw = Regex("""\b${Regex.escape(name)}\s*=\s*(\{[^}]*}|["'][^"']*["'])""")
            .find(body)?.groupValues?.get(1).orEmpty()
        return Regex("""["']([^"']+)["']""").findAll(raw).map { it.groupValues[1] }.toList()
    }

    private fun enumArgument(body: String, name: String): List<String> {
        val raw = Regex("""\b${Regex.escape(name)}\s*=\s*(\{[^}]*}|[^,\n)]+)""")
            .find(body)?.groupValues?.get(1).orEmpty()
        return Regex("""(?:[A-Za-z_$][\w$]*\.)?([A-Z][A-Z0-9_]*)""")
            .findAll(raw).map { it.groupValues[1] }.toList()
    }

    private fun wildcardAppliesTo(policyType: String, kind: ArtifactKind): Boolean =
        when (policyType) {
            "EntityPolicy" -> kind == ArtifactKind.ENTITY
            "EntityAttributePolicy" -> kind == ArtifactKind.ENTITY_ATTRIBUTE
            "ViewPolicy" -> kind == ArtifactKind.VIEW_DESCRIPTOR
            "MenuPolicy" -> kind == ArtifactKind.MENU_ITEM
            "UiComponentPolicy" -> kind == ArtifactKind.UI_COMPONENT || kind == ArtifactKind.UI_ACTION
            else -> false
        }

    private fun surfaceKind(kind: ArtifactKind): SecuritySurfaceKind? =
        when (kind) {
            ArtifactKind.MENU_ITEM -> SecuritySurfaceKind.MENU
            ArtifactKind.VIEW_DESCRIPTOR -> SecuritySurfaceKind.VIEW
            ArtifactKind.ENTITY -> SecuritySurfaceKind.ENTITY
            ArtifactKind.ENTITY_ATTRIBUTE -> SecuritySurfaceKind.ATTRIBUTE
            ArtifactKind.REST_CONTROLLER,
            ArtifactKind.REST_ENDPOINT,
            ArtifactKind.REST_SERVICE_CONFIG,
            ArtifactKind.REST_SERVICE_METHOD,
            ArtifactKind.REST_QUERY_CONFIG,
            ArtifactKind.REST_QUERY,
            -> SecuritySurfaceKind.REST
            ArtifactKind.UI_COMPONENT, ArtifactKind.UI_ACTION -> SecuritySurfaceKind.COMPONENT
            else -> null
        }

    private fun readableCode(code: String): String =
        code.removePrefix("P2_").removePrefix("JVW_").lowercase().replace('_', ' ')
            .replaceFirstChar(Char::uppercase)

    private fun severityRank(severity: DiagnosticSeverity): Int =
        when (severity) {
            DiagnosticSeverity.BLOCKING -> 0
            DiagnosticSeverity.ERROR -> 1
            DiagnosticSeverity.WARNING -> 2
            DiagnosticSeverity.INFO -> 3
        }

    private data class ParsedRole(
        val name: String?,
        val code: String?,
        val scopes: List<String>,
    )

    private data class ParsedPolicy(
        val effect: SecurityPolicyEffect,
        val actions: List<String>,
        val resources: List<String>,
        val wildcard: Boolean,
        val condition: String?,
    )
}

package org.jmixworkbench.services

import com.intellij.testFramework.HeavyPlatformTestCase
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.security.RuntimeRoleAssignmentResolution
import org.jmixworkbench.discovery.security.RuntimeSecurityEvidenceClearRequest
import org.jmixworkbench.discovery.security.RuntimeSecurityEvidenceFormat
import org.jmixworkbench.discovery.security.RuntimeSecurityEvidenceImportRequest
import org.jmixworkbench.discovery.security.RuntimeSecurityEvidenceSeverity
import org.jmixworkbench.discovery.security.SecurityPolicyEffect
import org.jmixworkbench.discovery.security.SecurityRoleKind
import org.jmixworkbench.discovery.security.SecurityRoleSnapshot
import org.jmixworkbench.discovery.security.SecurityWorkspaceSnapshot
import org.jmixworkbench.discovery.security.SecurityWorkspaceSummary
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeSecurityEvidenceServiceTest : HeavyPlatformTestCase() {
    fun testImportsOfficialJmixEntityJsonWithPoliciesAndAssignments() {
        val service = RuntimeSecurityEvidenceService.getInstance(project)
        service.clear(RuntimeSecurityEvidenceClearRequest())
        val json = """
            [
              {
                "_entityName": "sec_ResourceRoleEntity",
                "id": "7a25218c-b99c-4f31-9d58-6d468292dd4b",
                "name": "Payroll operator",
                "code": "payroll-operator",
                "description": "Runtime payroll permissions",
                "scopes": ["UI", "API"],
                "childRoles": [],
                "resourcePolicies": [
                  {
                    "_entityName": "sec_ResourcePolicyEntity",
                    "id": "08e27279-adc5-4fbe-aecd-864331c26620",
                    "type": "entity",
                    "resource": "payroll_PayrollRun",
                    "action": "read",
                    "effect": "allow",
                    "policyGroup": "payrollRun"
                  }
                ]
              },
              {
                "_entityName": "sec_RoleAssignmentEntity",
                "id": "1be578aa-7089-4d87-af63-cb183e53e20e",
                "username": "alex",
                "roleCode": "payroll-operator",
                "roleType": "resource"
              }
            ]
        """.trimIndent()

        val response = service.importEvidence(request("runtime-security.json", json.toByteArray()))
        assertTrue(response.accepted)

        val snapshot = service.snapshot(emptyWorkspace())
        assertEquals(RuntimeSecurityEvidenceFormat.JMIX_MIXED_ENTITY_JSON, snapshot.sources.single().format)
        assertEquals(1, snapshot.roles.size)
        assertEquals(listOf("UI", "API"), snapshot.roles.single().scopes)
        assertEquals("EntityPolicy", snapshot.policies.single().type)
        assertEquals(SecurityPolicyEffect.GRANT, snapshot.policies.single().effect)
        assertEquals(listOf("READ"), snapshot.policies.single().actions)
        assertEquals(listOf("alex"), snapshot.principals)
        assertEquals(RuntimeRoleAssignmentResolution.RESOLVED, snapshot.assignments.single().resolution)
        assertEquals(snapshot.roles.single().id, snapshot.assignments.single().candidateRoleIds.single())
    }

    fun testImportsOfficialJmixZipAndRetainsRowLevelCondition() {
        val service = RuntimeSecurityEvidenceService.getInstance(project)
        service.clear(RuntimeSecurityEvidenceClearRequest())
        val json = """
            [
              {
                "_entityName": "sec_RowLevelRoleEntity",
                "id": "e086c99d-6ee8-43e7-a835-74e7d9fc7a62",
                "name": "Own loans",
                "code": "own-loans",
                "childRoles": [],
                "rowLevelPolicies": [
                  {
                    "_entityName": "sec_RowLevelPolicyEntity",
                    "id": "e26f9e26-7ec8-429e-90da-407e0b08b3fd",
                    "type": "jpql",
                    "entityName": "payroll_LoanApp",
                    "action": "read",
                    "joinClause": "join {E}.employee e",
                    "whereClause": "e.username = :current_user_username"
                  }
                ]
              }
            ]
        """.trimIndent()

        val response = service.importEvidence(request("row-roles.zip", zip("entities.json", json)))
        assertTrue(response.accepted)

        val snapshot = service.snapshot(emptyWorkspace())
        assertEquals(RuntimeSecurityEvidenceFormat.JMIX_ROW_LEVEL_ROLE_JSON, snapshot.sources.single().format)
        assertEquals(SecurityRoleKind.ROW_LEVEL, snapshot.roles.single().kind)
        assertEquals(SecurityPolicyEffect.RESTRICT, snapshot.policies.single().effect)
        assertTrue(snapshot.policies.single().condition.orEmpty().contains("current_user_username"))
    }

    fun testReportsDesignTimeRuntimeDuplicateAndAmbiguousAssignment() {
        val service = RuntimeSecurityEvidenceService.getInstance(project)
        service.clear(RuntimeSecurityEvidenceClearRequest())
        val json = """
            [
              {
                "_entityName": "sec_ResourceRoleEntity",
                "name": "Database payroll role",
                "code": "payroll-operator",
                "resourcePolicies": []
              },
              {
                "_entityName": "sec_RoleAssignmentEntity",
                "username": "alex",
                "roleCode": "payroll-operator",
                "roleType": "resource"
              }
            ]
        """.trimIndent()
        assertTrue(service.importEvidence(request("collision.json", json.toByteArray())).accepted)

        val sourceRole = SecurityRoleSnapshot(
            id = "source-role",
            className = "com.acme.PayrollRole",
            name = "Source payroll role",
            code = "payroll-operator",
            kind = SecurityRoleKind.RESOURCE,
            scopes = listOf("UI", "API"),
            moduleId = "app",
            policyIds = emptyList(),
            inheritedRoleIds = emptyList(),
            unresolvedBaseRoleCount = 0,
            sourceLocator = SourceLocator(
                relativePath = "src/main/java/com/acme/PayrollRole.java",
                revisionFingerprint = "source-fingerprint",
            ),
        )
        val snapshot = service.snapshot(emptyWorkspace().copy(roles = listOf(sourceRole)))

        assertEquals(RuntimeRoleAssignmentResolution.AMBIGUOUS_ROLE, snapshot.assignments.single().resolution)
        assertEquals(2, snapshot.assignments.single().candidateRoleIds.size)
        assertTrue(snapshot.issues.any {
            it.code == "JVW-RUNTIME-SECURITY-DUPLICATE-ROLE-CODE" &&
                it.severity == RuntimeSecurityEvidenceSeverity.ERROR
        })
    }

    fun testRejectsZipEntriesThatAreNotJsonWithoutChangingEvidence() {
        val service = RuntimeSecurityEvidenceService.getInstance(project)
        service.clear(RuntimeSecurityEvidenceClearRequest())

        val response = service.importEvidence(
            request("hostile.zip", zip("payload.class", "not json")),
        )

        assertFalse(response.accepted)
        assertTrue(response.issues.any { it.code == "JVW-RUNTIME-SECURITY-PARSE-FAILED" })
        assertEquals(0, service.snapshot(emptyWorkspace()).summary.sourceCount)
    }

    private fun request(fileName: String, bytes: ByteArray) = RuntimeSecurityEvidenceImportRequest(
        fileName = fileName,
        contentBase64 = Base64.getEncoder().encodeToString(bytes),
        environmentLabel = "Integration",
    )

    private fun zip(entryName: String, content: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, StandardCharsets.UTF_8).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun emptyWorkspace() = SecurityWorkspaceSnapshot(
        graphDigest = "test-graph",
        roles = emptyList(),
        policies = emptyList(),
        surfaces = emptyList(),
        menuRoutes = emptyList(),
        journeys = emptyList(),
        findings = emptyList(),
        summary = SecurityWorkspaceSummary(
            resourceRoleCount = 0,
            rowRoleCount = 0,
            policyCount = 0,
            coveredSurfaceCount = 0,
            uncoveredMenuCount = 0,
            uncoveredViewCount = 0,
            errorCount = 0,
            warningCount = 0,
        ),
    )
}

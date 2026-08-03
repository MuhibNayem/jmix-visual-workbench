package org.jmixworkbench.services

import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.SourceLanguage
import org.jmixworkbench.discovery.security.SecurityWorkspaceBuilder
import org.jmixworkbench.discovery.security.SecurityWorkspaceInput
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexInput
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexer
import org.jmixworkbench.discovery.semantic.GraphSourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestApiWorkspaceServiceTest {
    @Test
    fun `builds services queries openapi and api security boundaries`() {
        val indexed = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/java/com/acme/Loan.java",
                        SourceLanguage.JAVA,
                        "package com.acme; @JmixEntity(name = \"payroll_Loan\") public class Loan { private UUID id; }",
                    ),
                    source(
                        "loan/src/main/java/com/acme/LoanService.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @Service("payroll_LoanService")
                        public class LoanService {
                            @Transactional
                            public Loan approve(UUID loanId) { return null; }
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/rest/rest-services.xml",
                        SourceLanguage.XML,
                        """
                        <services xmlns="http://jmix.io/schema/rest/services">
                          <service name="payroll_LoanService">
                            <method name="approve">
                              <param name="loanId" type="java.util.UUID"/>
                            </method>
                          </service>
                        </services>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/rest/rest-queries.xml",
                        SourceLanguage.XML,
                        """
                        <queries xmlns="http://jmix.io/schema/rest/queries">
                          <query name="approved" entity="payroll_Loan" fetchPlan="_base">
                            <jpql>select e from payroll_Loan e where e.id = :loanId</jpql>
                            <params><param name="loanId" type="java.util.UUID"/></params>
                          </query>
                        </queries>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/application.properties",
                        SourceLanguage.PROPERTIES,
                        """
                        jmix.rest.services-config=rest/rest-services.xml
                        jmix.rest.queries-config=rest/rest-queries.xml
                        jmix.resource-server.authenticated-url-patterns=/rest/**
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/RestRole.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @ResourceRole(name = "Payroll REST", code = "payroll-rest", scope = "API")
                        public interface RestRole {
                            @SpecificPolicy(resources = "rest.enabled")
                            @EntityPolicy(entityClass = Loan.class, actions = EntityPolicyAction.READ)
                            void rest();
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val graph = ApplicationGraphResponse(
            artifacts = indexed.artifacts,
            relationships = indexed.relationships,
            diagnostics = indexed.diagnostics,
            summary = ApplicationGraphSummary(
                artifactCount = indexed.artifacts.size,
                relationshipCount = indexed.relationships.size,
                diagnosticCount = indexed.diagnostics.size,
                unresolvedRelationshipCount = indexed.relationships.count { it.targetArtifactId == null },
                countsByKind = indexed.artifacts.groupingBy { it.kind.name }.eachCount(),
            ),
            scannedFiles = 6,
            candidateFiles = 6,
            excludedFiles = 0,
            excludedBytes = 0,
            unreadableFiles = 0,
            reusedFiles = 0,
            changedFiles = 6,
            cacheHit = false,
            durationMillis = 1,
            modules = listOf(
                ApplicationGraphModuleCoverage("loan", listOf("build:loan"), 1, 6, 6, listOf("main")),
            ),
            indexHealth = ApplicationGraphIndexHealth(true, 1, 1, 0, false),
            snapshotDigest = "rest-fixture",
        )
        val security = SecurityWorkspaceBuilder.build(
            SecurityWorkspaceInput(
                indexed.artifacts,
                indexed.relationships,
                indexed.diagnostics,
                graph.snapshotDigest,
            ),
        )

        val workspace = RestApiWorkspaceBuilder.build(graph, security)

        assertEquals(1, workspace.summary.serviceCount)
        assertEquals(1, workspace.summary.queryCount)
        assertTrue(workspace.security.restProtected)
        assertEquals(listOf("payroll-rest"), workspace.apiRoles.map { it.code })
        assertTrue(workspace.configs.all { it.registered })
        assertEquals(
            "ENFORCED_READ",
            workspace.operations.single { it.kind == RestApiOperationKind.QUERY }.rowSecurity,
        )
        assertEquals(
            "VISIBLE",
            workspace.operations.single { it.kind == RestApiOperationKind.SERVICE }.transactionBoundary,
        )
        assertTrue(workspace.findings.any { it.code == "JVW-REST-SERVICE-ROW-SECURITY-MANUAL" })
        assertEquals("/rest/docs/openapiDetailed.json", workspace.openApi.detailedJsonPath)
    }

    private fun source(
        path: String,
        language: SourceLanguage,
        content: String,
    ) = GraphSourceFile(
        relativePath = path,
        content = content,
        owner = ArtifactOwner("build:loan", "loan", "main"),
        language = language,
    )
}

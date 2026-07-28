package org.jmixworkbench.discovery.semantic

import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.discovery.model.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationGraphIndexerTest {
    private val owner = ArtifactOwner(
        buildId = "payroll-build",
        moduleId = "loan-module",
        sourceSetId = "main",
    )

    @Test
    fun `indexes a connected payroll screen service rest security workflow and database graph`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                files = listOf(
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanApp.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @JmixEntity
                        @Entity
                        public class LoanApp {
                            private Double loanAmount;
                            private String processState;
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanService.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @Service
                        public class LoanService {
                            public LoanApp approve(LoanApp loan) {
                                return dataManager.save(loan);
                            }
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanRestController.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @RestController
                        public class LoanRestController {
                            @PostMapping("/loans/{id}/approve")
                            @RolesAllowed("payroll-admin")
                            public LoanApp approve(LoanApp loan) {
                                return loanService.approve(loan);
                            }
                            private LoanService loanService;
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/payroll/PayrollAdminRole.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @ResourceRole(name = "Payroll administrator", code = "payroll-admin")
                        public interface PayrollAdminRole {}
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanListView.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @ViewController("loan-list-view")
                        @ViewDescriptor("loan-list-view.xml")
                        public class LoanListView {
                            private LoanService loanService;
                            private LoanApp selected;
                            void approve() {
                                selected.setProcessState("APPROVED");
                                loanService.approve(selected);
                            }
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/com/acme/payroll/loan-list-view.xml",
                        SourceLanguage.XML,
                        """
                        <view xmlns="http://jmix.io/schema/flowui/view" id="loan-list-view">
                          <data>
                            <collection id="loanAppsDc" class="com.acme.payroll.LoanApp" fetchPlan="_base">
                              <loader id="loanAppsDl"><query>select e from LoanApp e</query></loader>
                            </collection>
                          </data>
                          <layout>
                            <dataGrid id="loanAppsDataGrid" dataContainer="loanAppsDc"/>
                            <button id="approveButton"/>
                          </layout>
                        </view>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/com/acme/payroll/fetch-plans.xml",
                        SourceLanguage.XML,
                        """
                        <fetchPlans>
                          <fetchPlan name="_base" entity="com.acme.payroll.LoanApp"/>
                        </fetchPlans>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/com/acme/payroll/menu.xml",
                        SourceLanguage.XML,
                        """
                        <menu-config>
                          <item id="loanApplications" view="loan-list-view"/>
                        </menu-config>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/processes/loan-approval.bpmn20.xml",
                        SourceLanguage.XML,
                        """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                          <process id="loan-approval" name="Loan approval">
                            <startEvent id="submitted"/>
                            <userTask id="hr-approval" name="HR approval"/>
                            <endEvent id="approved"/>
                            <sequenceFlow id="to-hr" sourceRef="submitted" targetRef="hr-approval"/>
                            <sequenceFlow id="to-approved" sourceRef="hr-approval" targetRef="approved"/>
                          </process>
                        </definitions>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/db/changelog.xml",
                        SourceLanguage.XML,
                        """
                        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                          <include file="db/loan/2026/07/001-loan.xml"/>
                          <changeSet id="loan-app" author="payroll-team"/>
                        </databaseChangeLog>
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val kinds = result.artifacts.groupingBy { it.kind }.eachCount()
        assertEquals(1, kinds[ArtifactKind.ENTITY])
        assertEquals(1, kinds[ArtifactKind.VIEW_DESCRIPTOR])
        assertEquals(1, kinds[ArtifactKind.VIEW_CONTROLLER])
        assertEquals(1, kinds[ArtifactKind.REST_CONTROLLER])
        assertEquals(1, kinds[ArtifactKind.REST_ENDPOINT])
        assertEquals(1, kinds[ArtifactKind.RESOURCE_ROLE])
        assertEquals(1, kinds[ArtifactKind.WORKFLOW_PROCESS])
        assertEquals(3, kinds[ArtifactKind.WORKFLOW_STATE])
        assertEquals(1, kinds[ArtifactKind.LIQUIBASE_CHANGESET])
        assertTrue((kinds[ArtifactKind.UI_COMPONENT] ?: 0) >= 2)

        val relationships = result.relationships.groupBy { it.type }
        assertTrue(relationships.getValue(RelationshipType.BINDS_TO_ENTITY).any { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.REFERENCES_FETCH_PLAN).any { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.NAVIGATES_TO).any { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.CALLS_SERVICE).any { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.USES_ENTITY).any { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.SECURED_BY).any { it.targetArtifactId != null })
        assertEquals(2, relationships.getValue(RelationshipType.TRANSITIONS_TO).count { it.targetArtifactId != null })

        val reasonCodes = result.diagnostics.map { it.reasonCode }.toSet()
        assertTrue("P2_UNSAFE_FLOATING_POINT_MONEY" in reasonCodes)
        assertTrue("P2_MISSING_TRANSACTION_BOUNDARY" in reasonCodes)
        assertTrue("P2_WORKFLOW_TRANSITION_IN_UI" in reasonCodes)

        val endpoint = result.artifacts.single { it.kind == ArtifactKind.REST_ENDPOINT }
        assertEquals("POST /loans/{id}/approve", endpoint.displayName)
        assertNotNull(endpoint.sourceLocator.line)
    }

    @Test
    fun `graph output is stable when source input order changes`() {
        val entity = source(
            "src/main/java/com/acme/Employee.java",
            SourceLanguage.JAVA,
            "package com.acme; @JmixEntity class Employee {}",
        )
        val service = source(
            "src/main/kotlin/com/acme/EmployeeService.kt",
            SourceLanguage.KOTLIN,
            "package com.acme\n@Service class EmployeeService { fun load(): Employee? = null }",
        )
        val indexer = ApplicationGraphIndexer()
        val first = indexer.index(ApplicationGraphIndexInput(listOf(entity, service)))
        val second = indexer.index(ApplicationGraphIndexInput(listOf(service, entity)))

        assertEquals(first.artifacts, second.artifacts)
        assertEquals(first.relationships, second.relationships)
        assertEquals(first.diagnostics, second.diagnostics)
    }

    private fun source(
        path: String,
        language: SourceLanguage,
        content: String,
    ): GraphSourceFile =
        GraphSourceFile(
            relativePath = path,
            content = content,
            owner = owner,
            language = language,
        )
}

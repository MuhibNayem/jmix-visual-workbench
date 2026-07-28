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

    @Test
    fun `indexes enterprise bindings rest contracts security policies and migration impact`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanApp.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @JmixEntity(name = "payroll_LoanApp")
                        @Entity
                        @Table(name = "PAYROLL_LOAN_APP")
                        public class LoanApp {
                            @Id
                            private UUID id;
                            private BigDecimal loanAmount;
                            private String processState;
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanService.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @Service("payroll_LoanService")
                        public class LoanService {
                            public LoanApp approve(LoanApp loan) {
                                return dataManager.save(loan);
                            }
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanDetailView.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @Route(value = "loans/:id", layout = MainView.class)
                        @ViewController("payroll_LoanApp.detail")
                        @ViewDescriptor("loan-detail-view.xml")
                        public class LoanDetailView {
                            private LoanService loanService;
                            @ViewComponent
                            private InstanceContainer<LoanApp> loanAppDc;
                            @ViewComponent("approveButton")
                            private Button approve;

                            @Subscribe("approveButton")
                            public void onApprove(ClickEvent<Button> event) {
                                loanService.approve(loanAppDc.getItem());
                            }

                            @Subscribe(id = "loanAppDc", target = Target.DATA_CONTAINER)
                            public void onLoanChanged(InstanceContainer.ItemChangeEvent<LoanApp> event) {}
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanSideEffects.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @Component
                        public class LoanSideEffects {
                            private LoanService loanService;

                            @TransactionalEventListener
                            public void afterCommit(EntityChangedEvent<LoanApp> event) {
                                loanService.approve(event.getEntity());
                            }

                            @Scheduled(cron = "0 0 1 * * *")
                            public void reconcile() {
                                loanService.reconcile();
                            }
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/com/acme/payroll/loan-detail-view.xml",
                        SourceLanguage.XML,
                        """
                        <view xmlns="http://jmix.io/schema/flowui/view" id="payroll_LoanApp.detail">
                          <data>
                            <instance id="loanAppDc" class="com.acme.payroll.LoanApp">
                              <loader id="loanAppDl">
                                <query>select e from LoanApp e where e.id = :id</query>
                              </loader>
                            </instance>
                          </data>
                          <layout>
                            <formLayout id="form" dataContainer="loanAppDc">
                              <bigDecimalField id="loanAmountField" property="loanAmount"/>
                              <textField id="stateField" property="processState"/>
                            </formLayout>
                            <button id="approveButton"/>
                          </layout>
                        </view>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/payroll/PayrollRole.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        @ResourceRole(name = "Payroll", code = "payroll")
                        public interface PayrollRole {
                            @EntityPolicy(entityClass = LoanApp.class, actions = EntityPolicyAction.ALL)
                            @EntityAttributePolicy(
                                entityClass = LoanApp.class,
                                attributes = {"loanAmount", "processState"},
                                action = EntityAttributePolicyAction.MODIFY)
                            @ViewPolicy(viewIds = "payroll_LoanApp.detail")
                            void loan();
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/rest/rest-services.xml",
                        SourceLanguage.XML,
                        """
                        <services xmlns="http://jmix.io/schema/rest/services">
                          <service name="payroll_LoanService">
                            <method name="approve"><param name="loan"/></method>
                          </service>
                        </services>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/rest/rest-queries.xml",
                        SourceLanguage.XML,
                        """
                        <queries xmlns="http://jmix.io/schema/rest/queries">
                          <query name="byState" entity="payroll_LoanApp" fetchPlan="_base">
                            <jpql>select e from payroll_LoanApp e where e.processState = :state</jpql>
                            <params><param name="wrongName" type="java.lang.String"/></params>
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
                        external.sms.url=https://sms.example.test/api
                        external.sms.client-secret=plain-secret
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/db/001-loan.xml",
                        SourceLanguage.XML,
                        """
                        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                          <changeSet id="loan-table" author="team">
                            <createTable tableName="PAYROLL_LOAN_APP"/>
                            <createIndex tableName="PAYROLL_LOAN_APP" indexName="IDX_LOAN_STATE"/>
                          </changeSet>
                        </databaseChangeLog>
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val kinds = result.artifacts.groupingBy { it.kind }.eachCount()
        assertEquals(3, kinds[ArtifactKind.ENTITY_ATTRIBUTE])
        assertEquals(1, kinds[ArtifactKind.VIEW_ROUTE])
        assertEquals(2, kinds[ArtifactKind.VIEW_HANDLER])
        assertEquals(1, kinds[ArtifactKind.JPQL_QUERY])
        assertEquals(1, kinds[ArtifactKind.REST_SERVICE_CONFIG])
        assertEquals(1, kinds[ArtifactKind.REST_SERVICE_METHOD])
        assertEquals(1, kinds[ArtifactKind.REST_QUERY_CONFIG])
        assertEquals(1, kinds[ArtifactKind.REST_QUERY])
        assertTrue((kinds[ArtifactKind.SECURITY_POLICY] ?: 0) >= 3)
        assertEquals(2, kinds[ArtifactKind.SCHEMA_OBJECT])

        val relationships = result.relationships.groupBy { it.type }
        assertTrue(relationships.getValue(RelationshipType.BINDS_TO_ATTRIBUTE).all { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.INJECTS_COMPONENT).all { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.SUBSCRIBES_TO).all { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.IMPLEMENTED_BY).any { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.CONFIGURES).all { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.MIGRATES).any { it.targetArtifactId != null })
        assertTrue(relationships.getValue(RelationshipType.LISTENS_TO).any { it.targetArtifactId != null })
        val artifactsById = result.artifacts.associateBy { it.id }
        assertTrue(
            relationships.getValue(RelationshipType.CALLS_SERVICE).any { relationship ->
                artifactsById[relationship.sourceArtifactId]?.kind in setOf(
                    ArtifactKind.VIEW_HANDLER,
                    ArtifactKind.EVENT_LISTENER,
                    ArtifactKind.SCHEDULED_JOB,
                ) && relationship.targetArtifactId != null
            },
        )
        assertTrue(
            relationships.getValue(RelationshipType.USES_ENTITY).any { relationship ->
                artifactsById[relationship.sourceArtifactId]?.kind == ArtifactKind.SERVICE_METHOD &&
                    relationship.targetArtifactId != null
            },
        )

        val reasonCodes = result.diagnostics.map { it.reasonCode }.toSet()
        assertTrue("P2_REST_QUERY_PARAMETER_MISMATCH" in reasonCodes)
        assertTrue("P2_HARDCODED_SECRET_PROPERTY" in reasonCodes)
        assertTrue("P2_EXTERNAL_ENDPOINT_CONFIGURATION" in reasonCodes)
    }

    @Test
    fun `indexes inherited row policies and reports incomplete root and nested coverage`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "security/src/main/java/com/acme/LoanApp.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @JmixEntity
                        public class LoanApp {
                            private UUID id;
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "security/src/main/java/com/acme/BaseLoanRows.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @RowLevelRole(name = "Base loan rows", code = "base-loan-rows")
                        public interface BaseLoanRows {
                            @JpqlRowLevelPolicy(
                                entityClass = LoanApp.class,
                                where = "{E}.createdBy = :current_user_username")
                            void loan();
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "security/src/main/java/com/acme/PayrollRows.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @RowLevelRole(name = "Payroll rows", code = "payroll-rows")
                        public interface PayrollRows extends BaseLoanRows {
                            @PredicateRowLevelPolicy(
                                entityClass = LoanApp.class,
                                actions = RowLevelPolicyAction.READ)
                            default RowLevelPredicate<LoanApp> loanPredicate() {
                                return loan -> true;
                            }
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        assertEquals(2, result.artifacts.count { it.kind == ArtifactKind.ROW_ROLE })
        assertEquals(2, result.artifacts.count { it.kind == ArtifactKind.SECURITY_POLICY })
        assertTrue(
            result.relationships.any {
                it.type == RelationshipType.EXTENDS && it.targetArtifactId != null
            },
        )
        assertEquals(
            2,
            result.relationships.count {
                it.type == RelationshipType.APPLIES_POLICY_TO && it.targetArtifactId != null
            },
        )
        val reasonCodes = result.diagnostics.map { it.reasonCode }.toSet()
        assertTrue("P2_ROW_POLICY_NESTED_GRAPH_COVERAGE" in reasonCodes)
        assertTrue("P2_ROW_POLICY_ROOT_QUERY_COVERAGE" in reasonCodes)
    }

    @Test
    fun `does not misclassify ordinary source types as services and reports production risks`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "integration/src/main/java/com/acme/SmsPayload.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        public class SmsPayload {
                            private String message;
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "integration/src/main/java/com/acme/SmsGatewayService.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @Service
                        public class SmsGatewayService {
                            private final UnconstrainedDataManager dataManager;
                            private final WebClient client;
                            public void send() {
                                entityManager.createNativeQuery("update SMS_QUEUE set STATE = 'SENT'").executeUpdate();
                                new RuntimeException().printStackTrace();
                                client.post().uri("https://sms.example.test/send");
                            }
                            public void ignored() {
                                try { send(); } catch (Exception ignored) {}
                            }
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "integration/src/main/java/com/acme/SmsQueue.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @JmixEntity
                        @Entity
                        @Table(name = "SMS_QUEUE")
                        public class SmsQueue {
                            @Id
                            private UUID id;
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        assertEquals(1, result.artifacts.count { it.kind == ArtifactKind.SOURCE_TYPE })
        assertEquals(1, result.artifacts.count { it.kind == ArtifactKind.SERVICE })
        assertEquals(1, result.artifacts.count { it.kind == ArtifactKind.DATABASE_OPERATION })
        assertTrue(
            result.relationships.any {
                it.type == RelationshipType.WRITES_ENTITY && it.targetArtifactId != null
            },
        )
        val reasonCodes = result.diagnostics.map { it.reasonCode }.toSet()
        assertTrue("P2_UNCONSTRAINED_DATA_ACCESS" in reasonCodes)
        assertTrue("P2_NATIVE_SQL_WRITE" in reasonCodes)
        assertTrue("P2_PRINT_STACK_TRACE" in reasonCodes)
        assertTrue("P2_SWALLOWED_EXCEPTION" in reasonCodes)
        assertTrue("P2_HARDCODED_HTTP_ENDPOINT" in reasonCodes)
        assertTrue("P2_OUTBOUND_HTTP_TIMEOUT_MISSING" in reasonCodes)
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

package org.jmixworkbench.discovery.semantic

import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.discovery.model.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationGraphIndexerTest {
    private val owner = ArtifactOwner(
        buildId = "payroll-build",
        moduleId = "loan-module",
        sourceSetId = "main",
    )

    @Test
    fun `commented code cannot create phantom artifacts or diagnostics`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                files = listOf(
                    source(
                        "loan/src/main/java/com/acme/payroll/LoanService.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.payroll;
                        // @JmixEntity public class PhantomLoan { private Double amount; }
                        /*
                         * @RestController
                         * public class PhantomController {
                         *   void write() { dataManager.save(value); }
                         * }
                         */
                        @Service
                        public class LoanService {
                            String endpoint = "https://example.test/path//kept";
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        assertEquals(listOf("LoanService"), result.artifacts.map { it.displayName })
        assertFalse(result.diagnostics.any { it.reasonCode == "P2_UNSAFE_FLOATING_POINT_MONEY" })
        assertFalse(result.diagnostics.any { it.reasonCode == "P2_MISSING_TRANSACTION_BOUNDARY" })
    }

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
                    source(
                        "loan/src/main/resources/reports/loan-register.jrxml",
                        SourceLanguage.XML,
                        """
                        <jasperReport name="loan-register">
                          <queryString language="HQL"><![CDATA[select e from LoanApp e]]></queryString>
                        </jasperReport>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/application.properties",
                        SourceLanguage.PROPERTIES,
                        "sms.gateway.url=https://sms.example.test/v1/send?token=\${SMS_TOKEN}",
                    ),
                    source(
                        "loan/src/main/frontend/themes/payroll/styles.css",
                        SourceLanguage.UNKNOWN,
                        ".loan-approved { color: green; }",
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
        assertEquals(1, kinds[ArtifactKind.REPORT_TEMPLATE])
        assertEquals(1, kinds[ArtifactKind.REPORT_QUERY])
        assertEquals(1, kinds[ArtifactKind.INTEGRATION_ENDPOINT])
        assertEquals(1, kinds[ArtifactKind.THEME_ASSET])
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
    fun `duplicate simple names resolve inside the owning module and remain ambiguous outside it`() {
        fun ownedSource(moduleId: String, path: String, content: String) = GraphSourceFile(
            relativePath = path,
            content = content.trimIndent(),
            owner = ArtifactOwner("enterprise-build", moduleId, "main"),
            language = SourceLanguage.JAVA,
        )
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    ownedSource(
                        "retail",
                        "retail/src/main/java/com/bank/retail/Customer.java",
                        "package com.bank.retail; @JmixEntity public class Customer {}",
                    ),
                    ownedSource(
                        "corporate",
                        "corporate/src/main/java/com/bank/corporate/Customer.java",
                        "package com.bank.corporate; @JmixEntity public class Customer {}",
                    ),
                    ownedSource(
                        "retail",
                        "retail/src/main/java/com/bank/retail/CustomerService.java",
                        """
                        package com.bank.retail;
                        @Service
                        public class CustomerService {
                            public Customer load() { return null; }
                        }
                        """,
                    ),
                    ownedSource(
                        "reporting",
                        "reporting/src/main/java/com/bank/reporting/AmbiguousCustomerReport.java",
                        """
                        package com.bank.reporting;
                        @Service
                        public class AmbiguousCustomerReport {
                            public Customer load() { return null; }
                        }
                        """,
                    ),
                ),
            ),
        )

        val artifacts = result.artifacts.associateBy { it.id }
        val retailService = result.artifacts.single { it.displayName == "CustomerService" }
        val retailEntity = result.artifacts.single {
            it.kind == ArtifactKind.ENTITY && it.owner.moduleId == "retail"
        }
        val retailUses = result.relationships.filter {
            it.sourceArtifactId == retailService.id && it.type == RelationshipType.USES_ENTITY
        }
        assertEquals(listOf(retailEntity.id), retailUses.mapNotNull { it.targetArtifactId }.distinct())

        val report = result.artifacts.single { it.displayName == "AmbiguousCustomerReport" }
        val ambiguous = result.relationships.single {
            it.sourceArtifactId == report.id && it.type == RelationshipType.USES_ENTITY
        }
        assertEquals(null, ambiguous.targetArtifactId)
        assertEquals("P2_RELATIONSHIP_AMBIGUOUS", ambiguous.diagnostic?.reasonCode)
        assertTrue(artifacts[retailUses.single().targetArtifactId]?.owner?.moduleId == "retail")
    }

    @Test
    fun `indexes annotation exposed rest service as a typed contract`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/java/com/acme/LoanApi.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @RestService("payroll_LoanService")
                        public class LoanApi {
                            @RestMethod
                            public LoanResult approve(UUID loanId, @NotNull BigDecimal amount) {
                                return null;
                            }
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val contract = result.artifacts.single {
            it.kind == ArtifactKind.REST_SERVICE_METHOD &&
                it.displayName == "payroll_LoanService.approve"
        }
        val parameters = result.relationships
            .filter {
                it.sourceArtifactId == contract.id &&
                    it.type == RelationshipType.DECLARES_PARAMETER
            }
            .mapNotNull { relationship ->
                result.artifacts.firstOrNull { it.id == relationship.targetArtifactId }
            }
        assertEquals(setOf("loanId", "amount"), parameters.map { it.displayName }.toSet())
        assertTrue(result.relationships.any {
            it.sourceArtifactId == contract.id &&
                it.type == RelationshipType.IMPLEMENTED_BY &&
                it.targetArtifactId != null
        })
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

    @Test
    fun `security policy locators identify each exact annotation in source order`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "security/src/main/java/com/acme/PayrollRole.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @ResourceRole(name = "Payroll", code = "payroll")
                        public interface PayrollRole {
                            @EntityPolicy(entityClass = PayrollRun.class, actions = EntityPolicyAction.READ)
                            void entity();
                            @MenuPolicy(menuIds = "payroll")
                            @ViewPolicy(viewIds = "payroll_PayrollRun.list")
                            void screens();
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val policies = result.artifacts
            .filter { it.kind == ArtifactKind.SECURITY_POLICY }
            .sortedBy { it.sourceLocator.line }
        assertEquals(listOf(4, 6, 7), policies.map { it.sourceLocator.line })
        assertEquals(
            listOf(
                "com.acme.PayrollRole#EntityPolicy-1",
                "com.acme.PayrollRole#MenuPolicy-2",
                "com.acme.PayrollRole#ViewPolicy-3",
            ),
            policies.map { it.sourceLocator.symbol },
        )
    }

    @Test
    fun `indexes liquibase includeAll directories as changelog dependencies`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/resources/com/acme/liquibase/changelog.xml",
                        SourceLanguage.XML,
                        """
                        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                          <includeAll
                              relativeToChangelogFile="true"
                              path="changelog"/>
                        </databaseChangeLog>
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val root = result.artifacts.single { it.kind == ArtifactKind.LIQUIBASE_ROOT }
        val includeAll = result.artifacts.single { it.kind == ArtifactKind.LIQUIBASE_INCLUDE }
        assertEquals("changelog", includeAll.displayName)
        assertTrue(includeAll.summary.orEmpty().contains("includeAll"))
        assertTrue(
            result.relationships.any {
                it.type == RelationshipType.INCLUDES_CHANGELOG &&
                    it.sourceArtifactId == root.id &&
                    it.targetArtifactId == includeAll.id
            },
        )
    }

    @Test
    fun `indexes nested menus with containers bean actions separators and parent relationships`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/resources/com/acme/menu.xml",
                        SourceLanguage.XML,
                        """
                        <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                          <menu id="application">
                            <menu id="operations">
                              <item id="customers" view="Customer.list"/>
                              <item id="closeMonth" bean="menuBean" beanMethod="closeMonth"/>
                              <separator/>
                            </menu>
                          </menu>
                        </menu-config>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/com/acme/view/customer-list-view.xml",
                        SourceLanguage.XML,
                        """
                        <view xmlns="http://jmix.io/schema/flowui/view" id="Customer.list">
                          <layout/>
                        </view>
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val menuSource = result.artifacts.single { it.kind == ArtifactKind.MENU_SOURCE }
        val menuNodes = result.artifacts.filter { it.kind == ArtifactKind.MENU_ITEM }
        assertEquals(
            setOf("application", "operations", "customers", "closeMonth", "separator-5"),
            menuNodes.map { it.displayName }.toSet(),
        )
        val application = menuNodes.single { it.displayName == "application" }
        val operations = menuNodes.single { it.displayName == "operations" }
        val customers = menuNodes.single { it.displayName == "customers" }
        assertTrue(result.relationships.any {
            it.type == RelationshipType.DECLARES &&
                it.sourceArtifactId == menuSource.id &&
                it.targetArtifactId == operations.id
        })
        assertTrue(result.relationships.any {
            it.type == RelationshipType.DECLARES &&
                it.sourceArtifactId == application.id &&
                it.targetArtifactId == operations.id
        })
        assertTrue(result.relationships.any {
            it.type == RelationshipType.DECLARES &&
                it.sourceArtifactId == operations.id &&
                it.targetArtifactId == customers.id
        })
        assertTrue(result.relationships.any {
            it.type == RelationshipType.NAVIGATES_TO &&
                it.sourceArtifactId == customers.id &&
                it.targetArtifactId != null
        })
    }

    @Test
    fun `indexes yaml configuration and formatted sql changelogs without flattening module ownership`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/resources/application-bank.yml",
                        SourceLanguage.YAML,
                        """
                        spring:
                          datasource:
                            url: https://database.example.test/payroll
                            password: ${'$'}{DB_PASSWORD}
                        jmix:
                          rest:
                            services-config: com/acme/rest-services.xml
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/db/changelog/2026/001-loan.sql",
                        SourceLanguage.SQL,
                        """
                        --liquibase formatted sql
                        --changeset payroll:loan-account
                        create table ACME_LOAN_ACCOUNT (
                            ID uuid not null,
                            ACCOUNT_NO varchar(40) not null
                        );
                        alter table ACME_LOAN_ACCOUNT add constraint UK_LOAN_ACCOUNT unique (ACCOUNT_NO);
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val configuration = result.artifacts.single {
            it.kind == ArtifactKind.CONFIGURATION_FILE &&
                it.displayName == "application-bank"
        }
        val properties = result.artifacts.filter {
            it.kind == ArtifactKind.CONFIGURATION_PROPERTY
        }
        val changeSet = result.artifacts.single { it.kind == ArtifactKind.LIQUIBASE_CHANGESET }
        val schemaObjects = result.artifacts.filter { it.kind == ArtifactKind.SCHEMA_OBJECT }

        assertEquals(
            setOf(
                "spring.datasource.url",
                "spring.datasource.password",
                "jmix.rest.services-config",
            ),
            properties.map { it.displayName }.toSet(),
        )
        assertTrue(properties.all { it.owner == owner })
        assertTrue(result.relationships.count {
            it.type == RelationshipType.DECLARES && it.sourceArtifactId == configuration.id
        } >= 3)
        assertEquals("loan-account", changeSet.displayName)
        assertEquals(2, schemaObjects.size, schemaObjects.map { it.semanticKey }.toString())
        assertTrue(schemaObjects.all { it.displayName == "ACME_LOAN_ACCOUNT" })
        assertTrue(result.relationships.count {
            it.type == RelationshipType.MIGRATES && it.sourceArtifactId == changeSet.id
        } == 2)
        assertTrue(result.diagnostics.any { it.reasonCode == "P2_EXTERNAL_ENDPOINT_CONFIGURATION" })
        assertTrue(result.diagnostics.none { it.reasonCode == "P2_HARDCODED_SECRET_PROPERTY" })
        assertTrue(result.diagnostics.none { it.reasonCode == "P2_YAML_PARTIAL" })
    }

    @Test
    fun `indexes generated visual rules as first class business rules with entity impact`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/java/com/acme/loan/LoanApp.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.loan;
                        @JmixEntity
                        public class LoanApp {
                            private java.math.BigDecimal requestedAmount;
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/loan/LoanEligibilityRule.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.loan;
                        import org.springframework.stereotype.Component;
                        // JVW-VISUAL-RULE-MODEL: encoded
                        @Component("loanEligibilityRule")
                        public class LoanEligibilityRule {
                            public boolean evaluate(LoanApp loan) {
                                return loan.getRequestedAmount().signum() > 0;
                            }
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val rule = result.artifacts.single { it.kind == ArtifactKind.BUSINESS_RULE }
        val entity = result.artifacts.single { it.kind == ArtifactKind.ENTITY }

        assertEquals("LoanEligibilityRule", rule.displayName)
        assertTrue(result.artifacts.any {
            it.kind == ArtifactKind.SERVICE_METHOD &&
                it.semanticKey == "com.acme.loan.LoanEligibilityRule#evaluate"
        })
        assertTrue(result.relationships.any {
            it.sourceArtifactId == rule.id &&
                it.targetArtifactId == entity.id &&
                it.type == RelationshipType.USES_ENTITY
        })
    }

    @Test
    fun `indexes DMN structure and resolves BPMN decision task impact`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/resources/dmn/loan-eligibility.dmn",
                        SourceLanguage.XML,
                        """
                        <definitions xmlns="http://www.omg.org/spec/DMN/20151101"
                                     id="loanDefinitions" namespace="https://acme.example/dmn">
                          <decision id="loanEligibility" name="Loan eligibility">
                            <decisionTable id="loanEligibilityTable" hitPolicy="FIRST">
                              <input id="ageInput" label="Age">
                                <inputExpression id="ageExpression" typeRef="number"><text>age</text></inputExpression>
                              </input>
                              <output id="decisionOutput" label="Decision" name="decision" typeRef="string"/>
                              <rule id="adultRule">
                                <description>Adult applicant</description>
                                <inputEntry id="adultInput"><text>&gt;=18</text></inputEntry>
                                <outputEntry id="adultOutput"><text>"APPROVE"</text></outputEntry>
                              </rule>
                            </decisionTable>
                          </decision>
                        </definitions>
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/resources/processes/loan-approval.bpmn20.xml",
                        SourceLanguage.XML,
                        """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     xmlns:flowable="http://flowable.org/bpmn">
                          <process id="loanApproval">
                            <startEvent id="start"/>
                            <serviceTask id="evaluate" name="Evaluate eligibility" flowable:type="dmn">
                              <extensionElements>
                                <flowable:field name="decisionTableReferenceKey">
                                  <flowable:string>loanEligibility</flowable:string>
                                </flowable:field>
                              </extensionElements>
                            </serviceTask>
                          </process>
                        </definitions>
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val decision = result.artifacts.single { it.kind == ArtifactKind.DECISION_TABLE }
        assertEquals("loanEligibility", decision.semanticKey)
        assertEquals(1, result.artifacts.count { it.kind == ArtifactKind.DECISION_INPUT })
        assertEquals(1, result.artifacts.count { it.kind == ArtifactKind.DECISION_OUTPUT })
        assertEquals(1, result.artifacts.count { it.kind == ArtifactKind.DECISION_RULE })
        assertTrue(result.relationships.any {
            it.type == RelationshipType.EVALUATES_DECISION &&
                it.targetArtifactId == decision.id
        })
    }

    @Test
    fun `indexes reusable visual subflows and their exact caller impact`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "loan/src/main/java/com/acme/loan/VisualLoanService.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.loan;
                        // JVW-VISUAL-LOGIC-MODEL: encoded-model
                        @Component("visualLoanService")
                        public class VisualLoanService {
                            public void approve() {
                                validateLoan();
                                auditLoan();
                            }

                            @SuppressWarnings("JVW-VISUAL-SUBFLOW")
                            private void validateLoan() {
                            }

                            @SuppressWarnings("JVW-VISUAL-SUBFLOW")
                            private void auditLoan() {
                                validateLoan();
                            }

                            private static int compareValues(Object left, Object right) {
                                return 0;
                            }
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val methods = result.artifacts
            .filter { it.kind == ArtifactKind.SERVICE_METHOD }
            .associateBy { it.displayName }
        assertEquals(setOf("approve", "validateLoan", "auditLoan"), methods.keys)
        assertEquals(
            "Reusable visual subflow declared by VisualLoanService",
            methods.getValue("validateLoan").summary,
        )
        assertTrue(result.relationships.any {
            it.sourceArtifactId == methods.getValue("approve").id &&
                it.targetArtifactId == methods.getValue("validateLoan").id &&
                it.type == RelationshipType.CALLS_SERVICE
        })
        assertTrue(result.relationships.any {
            it.sourceArtifactId == methods.getValue("auditLoan").id &&
                it.targetArtifactId == methods.getValue("validateLoan").id &&
                it.type == RelationshipType.CALLS_SERVICE
        })
    }

    @Test
    fun `indexes generated connectors as first class integration endpoints`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "integration/src/main/java/com/acme/integration/PayrollWebhookConnector.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.integration;

                        @SuppressWarnings("JVW-INTEGRATION-CONNECTOR")
                        @Component("payrollWebhookConnector")
                        public final class PayrollWebhookConnector {
                            public String send(String payload) {
                                return loanService.approve(payload);
                            }

                            private LoanService loanService;
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "loan/src/main/java/com/acme/loan/LoanService.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme.loan;

                        @Service
                        public class LoanService {
                            public String approve(String payload) {
                                return payload;
                            }
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val connector = result.artifacts.single { it.kind == ArtifactKind.INTEGRATION_ENDPOINT }
        val operation = result.artifacts.single {
            it.kind == ArtifactKind.SERVICE_METHOD && it.displayName == "send"
        }
        val service = result.artifacts.single {
            it.kind == ArtifactKind.SERVICE && it.displayName == "LoanService"
        }
        assertEquals("PayrollWebhookConnector", connector.displayName)
        assertTrue(result.relationships.any {
            it.sourceArtifactId == connector.id &&
                it.targetArtifactId == operation.id &&
                it.type == RelationshipType.DECLARES
        })
        assertTrue(result.relationships.any {
            it.sourceArtifactId == operation.id &&
                it.targetArtifactId == service.id &&
                it.type == RelationshipType.CALLS_SERVICE
        })
    }

    @Test
    fun `valid yaml quoted keys and nested sequences remain fully indexed`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                files = listOf(
                    source(
                        "loan/src/main/resources/application.yml",
                        SourceLanguage.YAML,
                        """
                        spring:
                          profiles:
                            group:
                              enterprise:
                                - payroll
                                - audit
                        tenants:
                          - id: main
                            modules:
                              - loans
                              - hr
                        '@polymer/iron-icon': 3.0.1
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        assertFalse(result.diagnostics.any { it.reasonCode == "P2_YAML_PARTIAL" })
        val keys = result.artifacts
            .filter { it.kind == ArtifactKind.CONFIGURATION_PROPERTY }
            .map { it.displayName }
            .toSet()
        assertTrue("spring.profiles.group.enterprise[0]" in keys)
        assertTrue("spring.profiles.group.enterprise[1]" in keys)
        assertTrue("tenants[0].id" in keys)
        assertTrue("tenants[0].modules[0]" in keys)
        assertTrue("@polymer/iron-icon" in keys)
    }

    @Test
    fun `malformed xml diagnostic points to the exact parser location`() {
        val result = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                files = listOf(
                    source(
                        "loan/src/main/resources/com/acme/menu.xml",
                        SourceLanguage.XML,
                        """
                        <menu-config>
                          <menu id="root">
                            <item id="loans"/>
                          </wrong>
                        </menu-config>
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val diagnostic = result.diagnostics.single { it.reasonCode == "P2_XML_MALFORMED" }
        assertEquals(4, diagnostic.sourceLocator?.line)
        assertTrue(diagnostic.sourceLocator?.column != null)
        assertTrue("menu" in diagnostic.message.lowercase())
    }

    @Test
    fun `reports bounded progress and checks cancellation across every index phase`() {
        val updates = mutableListOf<Pair<ApplicationGraphIndexProgressStage, Pair<Int, Int>>>()
        var cancellationChecks = 0

        ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                files = listOf(
                    source(
                        "loan/src/main/java/com/acme/loan/Loan.java",
                        SourceLanguage.JAVA,
                        "package com.acme.loan; @JmixEntity public class Loan {}",
                    ),
                    source(
                        "loan/src/main/java/com/acme/loan/LoanService.java",
                        SourceLanguage.JAVA,
                        "package com.acme.loan; @Service public class LoanService { Loan load() { return null; } }",
                    ),
                ),
                progress = { stage, completed, total, _ ->
                    updates += stage to (completed to total)
                },
                checkCancelled = { cancellationChecks += 1 },
            ),
        )

        assertTrue(cancellationChecks >= 2)
        ApplicationGraphIndexProgressStage.entries.forEach { stage ->
            val stageUpdates = updates.filter { it.first == stage }.map { it.second }
            assertTrue(stageUpdates.isNotEmpty(), "Expected progress for $stage")
            assertTrue(stageUpdates.all { (completed, total) -> completed in 0..total })
            assertEquals(stageUpdates.last().second, stageUpdates.last().first)
        }
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

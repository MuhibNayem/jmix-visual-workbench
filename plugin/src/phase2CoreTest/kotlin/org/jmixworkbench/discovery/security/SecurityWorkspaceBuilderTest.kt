package org.jmixworkbench.discovery.security

import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.SourceLanguage
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexInput
import org.jmixworkbench.discovery.semantic.ApplicationGraphIndexer
import org.jmixworkbench.discovery.semantic.GraphSourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityWorkspaceBuilderTest {
    @Test
    fun `builds inherited effective coverage and flags menu view mismatch`() {
        val graph = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "app/src/main/java/com/acme/Customer.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @JmixEntity
                        public class Customer {
                            private UUID id;
                            private String name;
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "app/src/main/resources/com/acme/customer-list-view.xml",
                        SourceLanguage.XML,
                        """
                        <view xmlns="http://jmix.io/schema/flowui/view" id="Customer.list">
                          <layout><dataGrid id="customers"/></layout>
                        </view>
                        """.trimIndent(),
                    ),
                    source(
                        "app/src/main/resources/menu.xml",
                        SourceLanguage.XML,
                        """
                        <menu xmlns="http://jmix.io/schema/flowui/menu">
                          <item id="customers" view="Customer.list"/>
                        </menu>
                        """.trimIndent(),
                    ),
                    source(
                        "app/src/main/java/com/acme/BaseCustomerRole.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @ResourceRole(name = "Base customer", code = BaseCustomerRole.CODE, scope = "UI")
                        public interface BaseCustomerRole {
                            String CODE = "base-customer";
                            @EntityPolicy(entityClass = Customer.class, actions = EntityPolicyAction.READ)
                            void customer();
                        }
                        """.trimIndent(),
                    ),
                    source(
                        "app/src/main/java/com/acme/CustomerClerkRole.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @ResourceRole(name = "Customer clerk", code = CustomerClerkRole.CODE, scope = "UI")
                        public interface CustomerClerkRole extends BaseCustomerRole {
                            String CODE = "customer-clerk";
                            @MenuPolicy(menuIds = "customers")
                            void menu();
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val workspace = SecurityWorkspaceBuilder.build(
            SecurityWorkspaceInput(
                artifacts = graph.artifacts,
                relationships = graph.relationships,
                diagnostics = graph.diagnostics,
                graphDigest = "fixture-digest",
            ),
        )

        assertEquals(2, workspace.summary.resourceRoleCount)
        val clerk = workspace.roles.single { it.code == "customer-clerk" }
        assertEquals(1, clerk.inheritedRoleIds.size)
        val customer = workspace.surfaces.single { it.kind == SecuritySurfaceKind.ENTITY }
        assertTrue(clerk.id in customer.grantingRoleIds)
        val menu = workspace.surfaces.single { it.kind == SecuritySurfaceKind.MENU }
        assertTrue(clerk.id in menu.grantingRoleIds)
        assertTrue(workspace.findings.any { it.code == "JVW-SECURITY-MENU-VIEW-MISMATCH" })
    }

    @Test
    fun `marks row policies as restrictions and retains conditions`() {
        val graph = ApplicationGraphIndexer().index(
            ApplicationGraphIndexInput(
                listOf(
                    source(
                        "app/src/main/java/com/acme/Loan.java",
                        SourceLanguage.JAVA,
                        "package com.acme; @JmixEntity public class Loan { private UUID id; }",
                    ),
                    source(
                        "app/src/main/java/com/acme/OwnLoansRole.java",
                        SourceLanguage.JAVA,
                        """
                        package com.acme;
                        @RowLevelRole(name = "Own loans", code = "own-loans")
                        public interface OwnLoansRole {
                            @JpqlRowLevelPolicy(
                                entityClass = Loan.class,
                                where = "{E}.createdBy = :current_user_username")
                            void loan();
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val workspace = SecurityWorkspaceBuilder.build(
            SecurityWorkspaceInput(graph.artifacts, graph.relationships, graph.diagnostics, "row-fixture"),
        )

        val policy = workspace.policies.single()
        assertEquals(SecurityPolicyEffect.RESTRICT, policy.effect)
        assertTrue(policy.condition.orEmpty().contains("current_user_username"))
        val loan = workspace.surfaces.single { it.kind == SecuritySurfaceKind.ENTITY }
        assertEquals(1, loan.restrictingRoleIds.size)
        assertTrue(workspace.findings.any { it.code == "P2_ROW_POLICY_NESTED_GRAPH_COVERAGE" })
    }

    private fun source(
        path: String,
        language: SourceLanguage,
        content: String,
    ) = GraphSourceFile(
        relativePath = path,
        content = content,
        owner = ArtifactOwner("build:.", "app", "main"),
        language = language,
    )
}

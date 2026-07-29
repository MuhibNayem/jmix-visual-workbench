package org.jmixworkbench.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlin.system.measureNanoTime

/**
 * Enterprise-scale guard for the native symbol hot path.
 *
 * The fixture deliberately contains 3,000 files spread over sixteen
 * module-shaped roots. They are accepted by the index input filters but
 * unrelated to Jmix symbols. A broad extension scan would revisit all of them
 * after every PSI modification. The persistent indexes must keep all seven
 * cached symbol inventories and project-version Studio metadata stable while
 * descriptor discovery remains restricted to actual FlowUI files.
 */
class JmixNativeIndexScaleTest : LightJavaCodeInsightFixtureTestCase() {

    fun testThreeThousandUnrelatedFilesDoNotEvictWarmSymbolInventories() {
        addFrameworkAnnotations()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private String employeeNumber;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.payroll.view.employee;

            import io.jmix.flowui.view.ViewController;

            @ViewController("Employee.list")
            public class EmployeeListView {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/security/PayrollRole.java",
            """
            package com.company.payroll.security;

            import io.jmix.security.role.annotation.SpecificPolicy;

            public interface PayrollRole {
                @SpecificPolicy(resources = {"payroll.execute"})
                void execute();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/menu/PayrollMenu.java",
            """
            package com.company.payroll.menu;

            import org.springframework.stereotype.Component;

            @Component("PayrollMenu")
            public class PayrollMenu {
                public void execute() {
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="payroll">
                    <item id="employees" view="Employee.list"/>
                </menu>
            </menu-config>
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/fetch-plans.xml",
            """
            <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                <fetchPlan class="com.company.payroll.entity.Employee"
                           name="employee-summary"/>
            </fetchPlans>
            """.trimIndent(),
        )
        val messageBundle = myFixture.addFileToProject(
            "com/company/payroll/messages.properties",
            "EmployeeListView.title=Employees",
        )
        val studioMetadata = myFixture.addFileToProject(
            "com/company/payroll/ui/PayrollFlowUiMetadata.java",
            """
            package com.company.payroll.ui;

            import io.jmix.flowui.kit.meta.StudioComponent;

            public interface PayrollFlowUiMetadata {
                @StudioComponent(
                    classFqn = "com.company.payroll.ui.PayrollButton",
                    xmlElement = "payrollButton"
                )
                Object payrollButton();
            }
            """.trimIndent(),
        )

        repeat(1_000) { index ->
            val module = "module-${index % 16}"
            myFixture.addFileToProject(
                "bulk/$module/xml/config-$index.xml",
                "<configuration sequence=\"$index\"/>",
            )
            myFixture.addFileToProject(
                "bulk/$module/properties/application-$index.properties",
                "bulk.sequence=$index",
            )
            myFixture.addFileToProject(
                "bulk/$module/java/Utility$index.java",
                """
                package bulk.java;
                public class Utility$index {
                    public int sequence() { return $index; }
                }
                """.trimIndent(),
            )
        }

        myFixture.configureByText(
            "employee-list-view.xml",
            """
            <view>
                <data>
                    <collection id="employeesDc"
                                class="com.company.payroll.entity.Employee"
                                fetchPlan="employee-summary"/>
                </data>
                <layout/>
            </view>
            """.trimIndent(),
        )

        val domain = JmixDomainSymbolService.getInstance(project)
        val ui = JmixUiSecuritySymbolService.getInstance(project)
        val spring = JmixSpringBeanSymbolService.getInstance(project)
        val entities = domain.entityClasses()
        val fetchPlans = domain.fetchPlanDeclarations()
        val views = ui.viewIds()
        val menus = ui.menuIds()
        val messages = ui.messages()
        val policies = ui.specificPolicies()
        val beans = spring.beans()
        val descriptors = findAllJmixDescriptorFiles(myFixture.file)
        val flowUiMetadata = JmixFlowUiMetadata.snapshot(project)

        assertEquals(listOf("Employee"), entities.mapNotNull(PsiClass::getName))
        assertEquals(listOf("employee-summary"), fetchPlans.mapNotNull { it.value })
        assertEquals(listOf("Employee.list"), views.map { it.id })
        assertEquals(listOf("employees", "payroll"), menus.map { it.id }.sorted())
        assertEquals(listOf("EmployeeListView.title"), messages.map { it.key })
        assertEquals(listOf("payroll.execute"), policies.map { it.resource })
        assertEquals(listOf("PayrollMenu"), beans.map { it.name })
        assertEquals(listOf("employee-list-view.xml"), descriptors.map { it.name })
        assertEquals(
            listOf("payrollButton"),
            flowUiMetadata.elements.map { it.xmlElement },
        )

        val warmSamples = LongArray(100) {
            measureNanoTime {
                assertSame(entities, domain.entityClasses())
                assertSame(fetchPlans, domain.fetchPlanDeclarations())
                assertSame(views, ui.viewIds())
                assertSame(menus, ui.menuIds())
                assertSame(messages, ui.messages())
                assertSame(policies, ui.specificPolicies())
                assertSame(beans, spring.beans())
                assertSame(flowUiMetadata, JmixFlowUiMetadata.snapshot(project))
            }
        }
        val warmLookupNanos = warmSamples.sum()
        assertTrue(
            "Warm indexed symbol access took ${warmLookupNanos / 1_000_000} ms",
            warmLookupNanos < 2_000_000_000L,
        )
        assertTrue(
            "Warm indexed p95 was ${warmSamples.percentileMillis(95)} ms",
            warmSamples.percentileMillis(95) < 100,
        )
        assertTrue(
            "Warm indexed p99 was ${warmSamples.percentileMillis(99)} ms",
            warmSamples.percentileMillis(99) < 250,
        )
        println(
            "JVW_INDEX_WARM_100X_TOTAL_MS=${warmLookupNanos / 1_000_000} " +
                "P50_MS=${warmSamples.percentileMillis(50)} " +
                "P95_MS=${warmSamples.percentileMillis(95)} " +
                "P99_MS=${warmSamples.percentileMillis(99)}",
        )

        val unrelatedXml = myFixture.addFileToProject(
            "bulk/xml/unrelated-edit.xml",
            "<configuration changed=\"true\"/>",
        )
        val unrelatedProperties = myFixture.addFileToProject(
            "bulk/properties/application-extra.properties",
            "bulk.changed=true",
        )
        val unrelatedJava = myFixture.addFileToProject(
            "bulk/java/UnrelatedEdit.java",
            """
            package bulk.java;
            public class UnrelatedEdit {
            }
            """.trimIndent(),
        )

        val unrelatedEditLookupNanos = measureNanoTime {
            assertSame(entities, domain.entityClasses())
            assertSame(fetchPlans, domain.fetchPlanDeclarations())
            assertSame(views, ui.viewIds())
            assertSame(menus, ui.menuIds())
            assertSame(messages, ui.messages())
            assertSame(policies, ui.specificPolicies())
            assertSame(beans, spring.beans())
            assertSame(flowUiMetadata, JmixFlowUiMetadata.snapshot(project))
        }
        assertTrue(
            "Indexed access after unrelated edits took " +
                "${unrelatedEditLookupNanos / 1_000_000} ms",
            unrelatedEditLookupNanos < 2_000_000_000L,
        )
        println(
            "JVW_INDEX_UNRELATED_EDIT_LOOKUP_MS=" +
                unrelatedEditLookupNanos / 1_000_000,
        )

        val typingSamples = LongArray(20) { edit ->
            measureNanoTime {
                WriteCommandAction.runWriteCommandAction(project) {
                    VfsUtil.saveText(
                        unrelatedXml.virtualFile,
                        "<configuration typing=\"$edit\"/>",
                    )
                    VfsUtil.saveText(
                        unrelatedProperties.virtualFile,
                        "bulk.typing=$edit",
                    )
                    VfsUtil.saveText(
                        unrelatedJava.virtualFile,
                        """
                        package bulk.java;
                        public class UnrelatedEdit {
                            public int typing() { return $edit; }
                        }
                        """.trimIndent(),
                    )
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                assertSame(entities, domain.entityClasses())
                assertSame(fetchPlans, domain.fetchPlanDeclarations())
                assertSame(views, ui.viewIds())
                assertSame(menus, ui.menuIds())
                assertSame(messages, ui.messages())
                assertSame(policies, ui.specificPolicies())
                assertSame(beans, spring.beans())
                assertSame(flowUiMetadata, JmixFlowUiMetadata.snapshot(project))
            }
        }
        val repeatedTypingNanos = typingSamples.sum()
        assertTrue(
            "Twenty three-file typing cycles took " +
                "${repeatedTypingNanos / 1_000_000} ms",
            repeatedTypingNanos < 5_000_000_000L,
        )
        assertTrue(
            "Unrelated typing p95 was ${typingSamples.percentileMillis(95)} ms",
            typingSamples.percentileMillis(95) < 500,
        )
        assertTrue(
            "Unrelated typing p99 was ${typingSamples.percentileMillis(99)} ms",
            typingSamples.percentileMillis(99) < 1_000,
        )
        println(
            "JVW_INDEX_20X_TYPING_CYCLE_MS=" +
                repeatedTypingNanos / 1_000_000 +
                " P50_MS=${typingSamples.percentileMillis(50)}" +
                " P95_MS=${typingSamples.percentileMillis(95)}" +
                " P99_MS=${typingSamples.percentileMillis(99)}",
        )

        val messageEditNanos = measureNanoTime {
            WriteCommandAction.runWriteCommandAction(project) {
                VfsUtil.saveText(
                    messageBundle.virtualFile,
                    """
                    EmployeeListView.title=Employees
                    EmployeeListView.approve=Approve
                    """.trimIndent(),
                )
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val updatedMessages = ui.messages()
        assertNotSame(messages, updatedMessages)
        assertEquals(
            listOf("EmployeeListView.approve", "EmployeeListView.title"),
            updatedMessages.map { it.key },
        )
        assertSame(entities, domain.entityClasses())
        assertSame(fetchPlans, domain.fetchPlanDeclarations())
        assertSame(views, ui.viewIds())
        assertSame(menus, ui.menuIds())
        assertSame(policies, ui.specificPolicies())
        assertSame(beans, spring.beans())
        assertSame(flowUiMetadata, JmixFlowUiMetadata.snapshot(project))
        println(
            "JVW_INDEX_RELEVANT_MESSAGE_EDIT_COMMIT_MS=" +
                messageEditNanos / 1_000_000,
        )

        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(
                studioMetadata.virtualFile,
                """
                package com.company.payroll.ui;

                import io.jmix.flowui.kit.meta.StudioComponent;

                public interface PayrollFlowUiMetadata {
                    @StudioComponent(
                        classFqn = "com.company.payroll.ui.PayrollButton",
                        xmlElement = "payrollButton"
                    )
                    Object payrollButton();

                    @StudioComponent(
                        classFqn = "com.company.payroll.ui.PayrollField",
                        xmlElement = "payrollField"
                    )
                    Object payrollField();
                }
                """.trimIndent(),
            )
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val updatedFlowUiMetadata = JmixFlowUiMetadata.snapshot(project)
        assertNotSame(flowUiMetadata, updatedFlowUiMetadata)
        assertEquals(
            listOf("payrollButton", "payrollField"),
            updatedFlowUiMetadata.elements.map { it.xmlElement },
        )
        assertSame(entities, domain.entityClasses())
        assertSame(fetchPlans, domain.fetchPlanDeclarations())
        assertSame(views, ui.viewIds())
        assertSame(menus, ui.menuIds())
        assertSame(updatedMessages, ui.messages())
        assertSame(policies, ui.specificPolicies())
        assertSame(beans, spring.beans())
    }

    private fun LongArray.percentileMillis(percentile: Int): Long {
        require(isNotEmpty())
        require(percentile in 1..100)
        val ordered = sortedArray()
        val index = ((ordered.size - 1) * percentile + 99) / 100
        return ordered[index] / 1_000_000
    }

    private fun addFrameworkAnnotations() {
        myFixture.addClass(
            """
            package io.jmix.core.metamodel.annotation;
            public @interface JmixEntity {
                String name() default "";
                String value() default "";
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.kit.meta;
            public @interface StudioComponent {
                String classFqn() default "";
                String xmlElement() default "";
                String xmlns() default "";
                String injectionIdentifier() default "id";
                boolean isInjectable() default true;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface ViewController {
                String value() default "";
                String id() default "";
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.security.role.annotation;
            public @interface SpecificPolicy {
                String[] resources();
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.stereotype;
            public @interface Component {
                String value() default "";
            }
            """.trimIndent(),
        )
    }
}

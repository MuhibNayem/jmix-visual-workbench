package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeEditorAssistanceTest : LightJavaCodeInsightFixtureTestCase() {

    fun testFlowUiReferencesResolveCompleteAndRenameInTheNativeXmlEditor() {
        myFixture.configureByText(
            "employee-list-view.xml",
            """
            <view>
                <data>
                    <collection id="employeesDc">
                        <loader id="employeesDl"/>
                    </collection>
                </data>
                <layout>
                    <dataGrid id="employeesGrid" dataContainer="employeesD<caret>c"/>
                </layout>
            </view>
            """.trimIndent(),
        )

        val value = attributeValueAtCaret()
        val reference = value.references.filterIsInstance<JmixFlowUiIdReference>().single()
        val target = reference.resolve() as JmixFlowUiIdElement

        assertEquals("employeesDc", target.name)
        assertTrue(
            reference.variants
                .filterIsInstance<LookupElement>()
                .any { it.lookupString == "employeesDc" },
        )
        assertEquals(2, ReferencesSearch.search(target).findAll().size)

        myFixture.renameElement(target, "staffDc")
        myFixture.checkResult(
            """
            <view>
                <data>
                    <collection id="staffDc">
                        <loader id="employeesDl"/>
                    </collection>
                </data>
                <layout>
                    <dataGrid id="employeesGrid" dataContainer="staffDc"/>
                </layout>
            </view>
            """.trimIndent(),
        )
    }

    fun testUnresolvedFlowUiReferenceIsHighlightedAndFixedNatively() {
        myFixture.enableInspections(JmixFlowUiReferenceInspection())
        myFixture.configureByText(
            "employee-detail-view.xml",
            """
            <view>
                <data>
                    <instance id="employeeDc"/>
                </data>
                <layout>
                    <formLayout dataContainer="employeeDc<caret>c"/>
                </layout>
            </view>
            """.trimIndent(),
        )

        val problem = myFixture.doHighlighting()
            .single { it.description?.contains("Unresolved Jmix FlowUI reference") == true }
        assertEquals(HighlightSeverity.ERROR, problem.severity)

        val fix = myFixture.findSingleIntention("Replace with 'employeeDc'")
        myFixture.launchAction(fix)
        myFixture.checkResult(
            """
            <view>
                <data>
                    <instance id="employeeDc"/>
                </data>
                <layout>
                    <formLayout dataContainer="employeeDc"/>
                </layout>
            </view>
            """.trimIndent(),
        )
    }

    fun testJavaViewDescriptorNavigatesAndTracksNativeFileRename() {
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface ViewDescriptor {
                String value();
            }
            """.trimIndent(),
        )
        val descriptor = myFixture.addFileToProject(
            "com/company/hr/view/employee/employee-list-view.xml",
            """
            <view>
                <layout>
                    <dataGrid id="employeesGrid"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "EmployeeListView.java",
            """
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewDescriptor("employee-list-v<caret>iew.xml")
            public class EmployeeListView {
            }
            """.trimIndent(),
        )

        val literal = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
            PsiLiteralExpression::class.java,
            false,
        )!!
        val reference = literal.references.filterIsInstance<JmixViewDescriptorReference>().single()
        assertEquals(descriptor.virtualFile, reference.resolve()?.containingFile?.virtualFile)

        myFixture.renameElement(descriptor, "employee-overview-view.xml")
        assertTrue(myFixture.file.text.contains("@ViewDescriptor(\"employee-overview-view.xml\")"))
    }

    fun testControllerReferencesCompleteNavigateAndFollowCrossFileIdRename() {
        addControllerAnnotations()
        val descriptor = myFixture.addFileToProject(
            "com/company/hr/view/employee/employee-list-view.xml",
            """
            <view>
                <layout>
                    <dataGrid id="employeesGrid">
                        <actions>
                            <action id="edit" type="list_edit"/>
                        </actions>
                    </dataGrid>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "EmployeeListView.java",
            """
            import io.jmix.flowui.view.Install;
            import io.jmix.flowui.view.ViewComponent;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewDescriptor("employee-list-view.xml")
            public class EmployeeListView {
                @ViewComponent("employeesG<caret>rid")
                private Object employees;

                @Install(to = "employeesGrid.edit", subject = "handler")
                private void editHandler() {
                }
            }
            """.trimIndent(),
        )

        val literal = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
            PsiLiteralExpression::class.java,
            false,
        )!!
        val reference = literal.references.filterIsInstance<JmixJavaFlowUiIdReference>().single()
        val target = reference.resolve() as JmixFlowUiIdElement
        assertEquals("employeesGrid", target.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "employeesGrid" },
        )
        assertEquals(3, ReferencesSearch.search(target).findAll().size)

        myFixture.renameElement(target, "staffGrid")

        assertTrue(descriptor.text.contains("<dataGrid id=\"staffGrid\">"))
        assertTrue(myFixture.file.text.contains("@ViewComponent(\"staffGrid\")"))
        assertTrue(myFixture.file.text.contains("@Install(to = \"staffGrid.edit\""))
    }

    fun testUnresolvedControllerReferenceIsHighlightedAndFixedNatively() {
        addControllerAnnotations()
        myFixture.addFileToProject(
            "com/company/hr/view/employee/employee-list-view.xml",
            """
            <view>
                <layout>
                    <dataGrid id="employeesGrid"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixJavaReferenceInspection())
        myFixture.configureByText(
            "EmployeeListView.java",
            """
            import io.jmix.flowui.view.ViewComponent;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewDescriptor("employee-list-view.xml")
            public class EmployeeListView {
                @ViewComponent("employeesGi<caret>rd")
                private Object employees;
            }
            """.trimIndent(),
        )

        val problem = myFixture.doHighlighting()
            .single { it.description?.contains("Unresolved Jmix reference") == true }
        assertEquals(HighlightSeverity.ERROR, problem.severity)

        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'employeesGrid'"))
        assertTrue(myFixture.file.text.contains("@ViewComponent(\"employeesGrid\")"))
    }

    fun testControllerAndDescriptorExposeBidirectionalNativeGutterNavigation() {
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface ViewDescriptor {
                String value();
            }
            """.trimIndent(),
        )
        val descriptor = myFixture.addFileToProject(
            "com/company/hr/view/employee/employee-list-view.xml",
            """
            <view>
                <layout/>
            </view>
            """.trimIndent(),
        ) as XmlFile
        myFixture.configureByText(
            "EmployeeListView.java",
            """
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewDescriptor("employee-list-view.xml")
            public class EmployeeListView {
            }
            """.trimIndent(),
        )
        val controller = PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
        val provider = JmixViewDescriptorLineMarkerProvider()

        val controllerMarkers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
        provider.collectNavigationMarkersForTests(controller, controllerMarkers)
        assertEquals(1, controllerMarkers.size)

        val descriptorMarkers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
        provider.collectNavigationMarkersForTests(descriptor.rootTag!!, descriptorMarkers)
        assertEquals(1, descriptorMarkers.size)
    }

    fun testKotlinControllerReferenceNavigatesCompletesAndFollowsSafeXmlRename() {
        val descriptor = myFixture.addFileToProject(
            "com/company/hr/view/employee/employee-list-view.xml",
            """
            <view>
                <layout>
                    <dataGrid id="employeesGrid"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "EmployeeListView.kt",
            """
            import io.jmix.flowui.view.ViewComponent
            import io.jmix.flowui.view.ViewDescriptor

            @ViewDescriptor("employee-list-view.xml")
            class EmployeeListView {
                @ViewComponent("employeesG<caret>rid")
                lateinit var employees: Any
            }
            """.trimIndent(),
        )

        val host = generateSequence(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
        ) { it.parent }.first {
            it.javaClass.simpleName == "KtStringTemplateExpression"
        }
        val reference = host.references.filterIsInstance<JmixKotlinFlowUiIdReference>().single()
        val target = reference.resolve() as JmixFlowUiIdElement
        assertEquals("employeesGrid", target.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "employeesGrid" },
        )
        assertEquals(2, ReferencesSearch.search(target).findAll().size)

        myFixture.renameElement(target, "staffGrid")
        assertTrue(descriptor.text.contains("id=\"staffGrid\""))
        assertTrue(myFixture.file.text.contains("@ViewComponent(\"staffGrid\")"))
    }

    fun testUnresolvedKotlinControllerReferenceIsHighlightedAndFixedNatively() {
        myFixture.addFileToProject(
            "com/company/hr/view/employee/employee-list-view.xml",
            """
            <view>
                <layout>
                    <dataGrid id="employeesGrid"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixKotlinReferenceInspection())
        myFixture.configureByText(
            "EmployeeListView.kt",
            """
            import io.jmix.flowui.view.ViewComponent
            import io.jmix.flowui.view.ViewDescriptor

            @ViewDescriptor("employee-list-view.xml")
            class EmployeeListView {
                @ViewComponent("employeesGi<caret>rd")
                lateinit var employees: Any
            }
            """.trimIndent(),
        )

        val problem = myFixture.doHighlighting()
            .single { it.description?.contains("Unresolved Jmix reference") == true }
        assertEquals(HighlightSeverity.ERROR, problem.severity)

        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'employeesGrid'"))
        assertTrue(myFixture.file.text.contains("@ViewComponent(\"employeesGrid\")"))
    }

    private fun addControllerAnnotations() {
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface ViewDescriptor {
                String value();
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface ViewComponent {
                String value() default "";
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface Install {
                String to();
                String subject();
            }
            """.trimIndent(),
        )
    }

    private fun attributeValueAtCaret(): XmlAttributeValue =
        PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
            XmlAttributeValue::class.java,
            false,
        )!!
}

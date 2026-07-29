package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeFlowUiControllerInspectionTest :
    LightJavaCodeInsightFixtureTestCase() {

    fun testValidControllerInjectionSubscribeInstallAndSupplyContractsPass() {
        addFlowUiAnnotations()
        addControllerTypes()
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            import com.company.ui.TypedField;
            import com.company.ui.ValueChangeEvent;
            import io.jmix.flowui.view.Install;
            import io.jmix.flowui.view.Subscribe;
            import io.jmix.flowui.view.Supply;
            import io.jmix.flowui.view.ViewComponent;
            import io.jmix.flowui.view.ViewController;

            @ViewController("Employee.list")
            public class EmployeeView {
                @ViewComponent("nameField")
                private TypedField<String> nameField;

                @Subscribe("nameField")
                public void onNameChange(ValueChangeEvent event) {
                }

                @Install(to = "nameField", subject = "validator")
                private void validateName(String value) {
                }

                @Supply(to = "nameField", subject = "renderer")
                private String nameRenderer() {
                    return "renderer";
                }
            }
            """.trimIndent(),
        )

        val problems = controllerProblems()
        assertTrue(
            problems.joinToString(separator = "\n") { problem ->
                "${problem.description} @ ${problem.startOffset}"
            },
            problems.isEmpty(),
        )
    }

    fun testSubscribeLocationReturnParameterAndEventTypeAreRejected() {
        addFlowUiAnnotations()
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "BrokenHandlers.java",
            """
            import io.jmix.flowui.view.Subscribe;

            public class BrokenHandlers {
                @Subscribe
                public static String tooMany(String first, String second) {
                    return first + second;
                }

                @Subscribe
                public void wrongEvent(String event) {
                }
            }
            """.trimIndent(),
        )

        val descriptions = controllerProblems().mapNotNull { it.description }
        val diagnosticDump = descriptions.joinToString(separator = "\n")
        assertTrue(diagnosticDump, descriptions.any { "inside a Jmix view" in it })
        assertTrue(diagnosticDump, descriptions.any { "instance method" in it })
        assertTrue(diagnosticDump, descriptions.any { "must return void" in it })
        assertTrue(
            diagnosticDump,
            descriptions.any { "exactly one event parameter" in it },
        )
        assertTrue(
            diagnosticDump,
            descriptions.any { "must extend java.util.EventObject" in it },
        )
    }

    fun testInstallPointAndDuplicateDelegateContractsFailClosed() {
        addFlowUiAnnotations()
        myFixture.addClass(
            """
            package com.company.ui;
            public class BrokenField<T> {
                public String setValidator(String value) {
                    return value;
                }
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            import com.company.ui.BrokenField;
            import io.jmix.flowui.view.Install;
            import io.jmix.flowui.view.ViewComponent;
            import io.jmix.flowui.view.ViewController;

            @ViewController("Employee.list")
            public class EmployeeView {
                @ViewComponent("nameField")
                private BrokenField<String> nameField;

                @Install(to = "nameField", subject = "validator")
                private void firstValidator(String value) {
                }

                @Install(to = "nameField", subject = "validator")
                private void secondValidator(String value) {
                }
            }
            """.trimIndent(),
        )

        val descriptions = controllerProblems().mapNotNull { it.description }
        assertEquals(
            2,
            descriptions.count { "Multiple controller methods install" in it },
        )
        assertEquals(
            2,
            descriptions.count {
                "must return void and accept one functional interface" in it
            },
        )
    }

    fun testSupplyRequiresTargetSubjectNoParametersAndNonVoidReturn() {
        addFlowUiAnnotations()
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            import io.jmix.flowui.view.Supply;
            import io.jmix.flowui.view.ViewController;

            @ViewController("Employee.list")
            public class EmployeeView {
                @Supply
                private void brokenSupply(String unexpected) {
                }
            }
            """.trimIndent(),
        )

        val descriptions = controllerProblems().mapNotNull { it.description }
        assertTrue(descriptions.any { "declare a target" in it })
        assertTrue(descriptions.any { "declare 'subject'" in it })
        assertTrue(descriptions.any { "must not declare parameters" in it })
        assertTrue(descriptions.any { "must return the supplied object" in it })
    }

    fun testRawGenericViewComponentOffersSafeWildcardQuickFix() {
        addFlowUiAnnotations()
        myFixture.addClass(
            """
            package com.company.ui;
            public class GenericComponent<T> {
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            import com.company.ui.GenericComponent;
            import io.jmix.flowui.view.ViewComponent;
            import io.jmix.flowui.view.ViewController;

            @ViewController("Employee.list")
            public class EmployeeView {
                @ViewComponent
                private <caret>GenericComponent employeeGrid;
            }
            """.trimIndent(),
        )

        assertTrue(
            controllerProblems().any {
                it.description?.contains("must declare type arguments") == true
            },
        )
        myFixture.launchAction(
            myFixture.findSingleIntention("Add wildcard type argument"),
        )
        assertTrue(myFixture.file.text.contains("GenericComponent<?> employeeGrid"))
    }

    fun testDataLoaderTargetReferenceExcludesComponentIdsInJavaAndKotlin() {
        addFlowUiAnnotations()
        myFixture.addFileToProject(
            "com/company/payroll/employee-view.xml",
            """
            <view>
                <data>
                    <collection id="employeesDc">
                        <loader id="employeesDl"/>
                    </collection>
                </data>
                <layout>
                    <button id="loadButton"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "EmployeeView.java",
            """
            import io.jmix.flowui.view.Subscribe;
            import io.jmix.flowui.view.Target;
            import io.jmix.flowui.view.ViewController;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            public class EmployeeView {
                @Subscribe(id = "loadBu<caret>tton", target = Target.DATA_LOADER)
                public void onLoad(Object event) {
                }
            }
            """.trimIndent(),
        )

        val literal = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
            PsiLiteralExpression::class.java,
            false,
        )!!
        val reference = literal.references
            .filterIsInstance<JmixJavaFlowUiIdReference>()
            .single()
        assertNull(reference.resolve())
        val variants = reference.variants
            .filterIsInstance<LookupElement>()
            .map { it.lookupString }
        assertEquals(listOf("employeesDl"), variants)

        myFixture.configureByText(
            "EmployeeView.kt",
            """
            import io.jmix.flowui.view.Subscribe
            import io.jmix.flowui.view.Target
            import io.jmix.flowui.view.ViewController
            import io.jmix.flowui.view.ViewDescriptor

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            class EmployeeView {
                @Subscribe(id = "loadBu<caret>tton", target = Target.DATA_LOADER)
                fun onLoad(event: Any) {
                }
            }
            """.trimIndent(),
        )
        val host = generateSequence(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
        ) { it.parent }.first {
            it.javaClass.simpleName == "KtStringTemplateExpression"
        }
        val kotlinReference = host.references
            .filterIsInstance<JmixKotlinFlowUiIdReference>()
            .single()
        assertNull(kotlinReference.resolve())
        assertEquals(
            listOf("employeesDl"),
            kotlinReference.variants
                .filterIsInstance<LookupElement>()
                .map { it.lookupString },
        )
    }

    fun testKotlinControllerContractsRejectInvalidHandlersAndDuplicates() {
        myFixture.enableInspections(JmixKotlinFlowUiControllerInspection())
        myFixture.configureByText(
            "BrokenController.kt",
            """
            class BrokenController {
                @Subscribe
                fun broken(first: String, second: String): String = first + second

                @Supply(to = "field", subject = "renderer")
                fun renderer(unexpected: String): Unit {
                }

                @Install(to = "field", subject = "validator")
                fun first(value: String) {
                }

                @Install(to = "field", subject = "validator")
                fun second(value: String) {
                }
            }
            """.trimIndent(),
        )

        val descriptions = controllerProblems().mapNotNull { it.description }
        assertTrue(descriptions.any { "inside a Jmix view" in it })
        assertTrue(descriptions.any { "must return Unit" in it })
        assertTrue(descriptions.any { "exactly one event parameter" in it })
        assertTrue(descriptions.any { "must not declare parameters" in it })
        assertTrue(descriptions.any { "must return the supplied object" in it })
        assertEquals(
            2,
            descriptions.count { "Multiple controller functions install" in it },
        )
    }

    private fun controllerProblems() =
        myFixture.doHighlighting()
            .filter { info ->
                info.severity == HighlightSeverity.ERROR ||
                    info.severity == HighlightSeverity.WARNING ||
                    info.severity == HighlightSeverity.WEAK_WARNING
            }
            .filter { info ->
                info.description?.startsWith("@") == true ||
                    info.description?.startsWith("Generic @") == true ||
                    info.description?.startsWith("Multiple controller") == true ||
                    info.description?.startsWith("Unable to find") == true ||
                    info.description?.startsWith("Installation point") == true
            }

    private fun addControllerTypes() {
        myFixture.addClass(
            """
            package com.company.ui;
            @FunctionalInterface
            public interface Validator<T> {
                void validate(T value);
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.ui;
            public class TypedField<T> {
                public void setValidator(Validator<T> validator) {
                }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.ui;
            public class ValueChangeEvent extends java.util.EventObject {
                public ValueChangeEvent(Object source) {
                    super(source);
                }
            }
            """.trimIndent(),
        )
    }

    private fun addFlowUiAnnotations() {
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface ViewController {
                String value();
            }
            """.trimIndent(),
        )
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
            public enum Target {
                COMPONENT,
                DATA_CONTAINER,
                DATA_LOADER
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface Subscribe {
                String value() default "";
                String id() default "";
                String subject() default "";
                Target target() default Target.COMPONENT;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface Install {
                String to() default "";
                String subject() default "";
                Class<?> type() default Object.class;
                Target target() default Target.COMPONENT;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.view;
            public @interface Supply {
                String to() default "";
                String subject() default "";
                Class<?> type() default Object.class;
                Target target() default Target.COMPONENT;
            }
            """.trimIndent(),
        )
    }
}

package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactOrigin
import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.services.FlowUiControllerPsiReader
import org.jmixworkbench.services.FlowUiControllerWorkspaceSnapshot
import org.jmixworkbench.services.ProjectFileResolver

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

    fun testStudioMetadataEnforcesExactDescriptorInjectionType() {
        addFlowUiAnnotations()
        addStudioMetadataAnnotations()
        addVaadinListenerTypes()
        addMetadataBackedButtons(includeSecondClickSubject = false)
        myFixture.addClass(
            """
            package com.company.ui;
            public class WrongButton extends com.vaadin.flow.component.Component {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/employee-view.xml",
            """
            <view xmlns="http://jmix.io/schema/flowui/view">
                <layout>
                    <button id="saveButton"/>
                    <button id="cancelButton"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            package com.company.payroll;

            import com.company.ui.JmixButton;
            import com.company.ui.WrongButton;
            import io.jmix.flowui.view.ViewComponent;
            import io.jmix.flowui.view.ViewController;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            public class EmployeeView {
                @ViewComponent
                private JmixButton saveButton;

                @ViewComponent
                private WrongButton cancelButton;
            }
            """.trimIndent(),
        )

        val descriptions = controllerProblems().mapNotNull { it.description }
        assertEquals(
            descriptions.joinToString("\n"),
            1,
            descriptions.count {
                "@ViewComponent 'cancelButton' has type WrongButton" in it
            },
        )
        assertFalse(
            descriptions.any { "@ViewComponent 'saveButton'" in it },
        )
    }

    fun testSubscribeSubjectCompletesNavigatesRenamesAndValidatesEventContract() {
        addFlowUiAnnotations()
        addStudioMetadataAnnotations()
        addVaadinListenerTypes()
        addMetadataBackedButtons(includeSecondClickSubject = false)
        myFixture.addFileToProject(
            "com/company/payroll/employee-view.xml",
            """
            <view xmlns="http://jmix.io/schema/flowui/view">
                <layout>
                    <button id="saveButton"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            package com.company.payroll;

            import com.company.ui.ClickEvent;
            import io.jmix.flowui.view.Subscribe;
            import io.jmix.flowui.view.ViewController;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            public class EmployeeView {
                @Subscribe(id = "saveButton", subject = "click<caret>Listener")
                public void onSave(ClickEvent event) {
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
            .filterIsInstance<JmixJavaSubscribeSubjectReference>()
            .single()
        val resolved = reference.resolve() as PsiMethod
        assertEquals("addClickListener", resolved.name)
        assertEquals(
            listOf("clickListener"),
            reference.variants
                .filterIsInstance<LookupElement>()
                .map(LookupElement::getLookupString),
        )
        WriteCommandAction.runWriteCommandAction(project) {
            reference.handleElementRename("addPressedListener")
        }
        assertTrue(myFixture.file.text.contains("subject = \"pressedListener\""))

        myFixture.configureByText(
            "EmployeeView.java",
            """
            package com.company.payroll;

            import com.company.ui.ClickEvent;
            import io.jmix.flowui.view.Subscribe;
            import io.jmix.flowui.view.ViewController;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            public class EmployeeView {
                @Subscribe(id = "saveButton", subject = "missingListener")
                public void onSave(ClickEvent event) {
                }
            }
            """.trimIndent(),
        )
        assertTrue(
            controllerProblems().any {
                it.description?.contains(
                    "@Subscribe subject 'missingListener' is not available",
                ) == true
            },
        )
    }

    fun testSubscribeWithoutSubjectFailsClosedWhenEventHasMultipleListeners() {
        addFlowUiAnnotations()
        addStudioMetadataAnnotations()
        addVaadinListenerTypes()
        addMetadataBackedButtons(includeSecondClickSubject = true)
        myFixture.addFileToProject(
            "com/company/payroll/employee-view.xml",
            """
            <view xmlns="http://jmix.io/schema/flowui/view">
                <layout>
                    <button id="saveButton"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            package com.company.payroll;

            import com.company.ui.ClickEvent;
            import io.jmix.flowui.view.Subscribe;
            import io.jmix.flowui.view.ViewController;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            public class EmployeeView {
                @Subscribe("saveButton")
                public void onSave(ClickEvent event) {
                }
            }
            """.trimIndent(),
        )

        assertTrue(
            controllerProblems().any {
                it.description?.contains(
                    "exposes multiple listeners for ClickEvent",
                ) == true
            },
        )
    }

    fun testKotlinMetadataTypingAndSubscribeSubjectReferenceMatchJava() {
        addFlowUiAnnotations()
        addStudioMetadataAnnotations()
        addVaadinListenerTypes()
        addMetadataBackedButtons(includeSecondClickSubject = false)
        myFixture.addClass(
            """
            package com.company.ui;
            public class WrongButton extends com.vaadin.flow.component.Component {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/employee-view.xml",
            """
            <view xmlns="http://jmix.io/schema/flowui/view">
                <layout>
                    <button id="saveButton"/>
                    <button id="cancelButton"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixKotlinFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.kt",
            """
            package com.company.payroll

            import com.company.ui.ClickEvent
            import com.company.ui.JmixButton
            import com.company.ui.WrongButton
            import io.jmix.flowui.view.Subscribe
            import io.jmix.flowui.view.ViewComponent
            import io.jmix.flowui.view.ViewController
            import io.jmix.flowui.view.ViewDescriptor

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            class EmployeeView {
                @ViewComponent
                lateinit var saveButton: JmixButton

                @ViewComponent
                lateinit var cancelButton: WrongButton

                @Subscribe(id = "saveButton", subject = "click<caret>Listener")
                fun onSave(event: ClickEvent) {
                }
            }
            """.trimIndent(),
        )

        val host = generateSequence(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
        ) { it.parent }.first {
            it.javaClass.simpleName == "KtStringTemplateExpression"
        }
        val reference = host.references
            .filterIsInstance<JmixKotlinSubscribeSubjectReference>()
            .single()
        assertEquals(
            "addClickListener",
            (reference.resolve() as PsiMethod).name,
        )
        assertEquals(
            listOf("clickListener"),
            reference.variants
                .filterIsInstance<LookupElement>()
                .map(LookupElement::getLookupString),
        )

        val descriptions = controllerProblems().mapNotNull { it.description }
        assertEquals(
            descriptions.joinToString("\n"),
            1,
            descriptions.count {
                "@ViewComponent 'cancelButton' has type WrongButton" in it
            },
        )
        assertFalse(
            descriptions.any { "@ViewComponent 'saveButton'" in it },
        )
        assertFalse(
            descriptions.any { "@Subscribe subject 'clickListener'" in it },
        )

        val virtualFile = myFixture.file.virtualFile
        val relativePath = ProjectFileResolver.getInstance(project)
            .locatorPath(virtualFile)
        assertNotNull(relativePath)
        val fingerprint = CanonicalDiscoveryJson.sha256(myFixture.file.text)
        val visualSnapshot = FlowUiControllerPsiReader.read(
            project,
            listOf(
                ArtifactSnapshot(
                    id = "controller",
                    kind = ArtifactKind.VIEW_CONTROLLER,
                    semanticKey = "com.company.payroll.EmployeeView",
                    owner = ArtifactOwner("build", "app", "main"),
                    sourceLocator = SourceLocator(
                        relativePath = relativePath!!,
                        revisionFingerprint = fingerprint,
                    ),
                    origin = ArtifactOrigin.SOURCE,
                    fingerprint = fingerprint,
                    displayName = "EmployeeView",
                    summary = null,
                ),
            ),
        )
        assertNotNull(visualSnapshot)
        assertFalse(visualSnapshot!!.psiSupported)
        assertEquals("kotlin", visualSnapshot.language)
        assertEquals(2, visualSnapshot.injections.size)
        assertEquals(1, visualSnapshot.handlers.size)
        assertTrue(
            visualSnapshot.injections
                .single { it.fieldName == "cancelButton" }
                .issues
                .any { it.code == "JMIX-KOTLIN-VIEW-COMPONENT-TYPE" },
        )
    }

    fun testAddonCustomSubscriptionMetadataParticipatesInNativeResolution() {
        addFlowUiAnnotations()
        addStudioMetadataAnnotations()
        addVaadinListenerTypes()
        addMetadataBackedButtons(includeSecondClickSubject = false)
        myFixture.addClass(
            """
            package com.company.ui;
            public class AuditEvent extends java.util.EventObject {
                public AuditEvent(Object source) {
                    super(source);
                }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.ui;
            public interface AddonFlowUiMetadata {
                @io.jmix.flowui.kit.meta.StudioComponent(
                    classFqn = "com.company.ui.JmixButton",
                    xmlElement = "button",
                    injectionIdentifier = "name",
                    customSubscriptions =
                        @io.jmix.flowui.kit.meta.StudioCustomSubscription(
                            methodName = "auditListener",
                            eventClassFqn = "com.company.ui.AuditEvent"
                        )
                )
                Object auditedButton();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/employee-view.xml",
            """
            <view xmlns="http://jmix.io/schema/flowui/view">
                <layout>
                    <button name="auditButton"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            package com.company.payroll;

            import com.company.ui.AuditEvent;
            import io.jmix.flowui.view.Subscribe;
            import io.jmix.flowui.view.ViewController;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            public class EmployeeView {
                @Subscribe(id = "auditButton", subject = "audit<caret>Listener")
                public void onAudit(AuditEvent event) {
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
            .filterIsInstance<JmixJavaSubscribeSubjectReference>()
            .single()
        assertEquals(
            "auditedButton",
            (reference.resolve() as PsiMethod).name,
        )
        val targetLiteral = PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            PsiLiteralExpression::class.java,
        ).single { it.value == "auditButton" }
        val targetReference = targetLiteral.references
            .filterIsInstance<JmixJavaFlowUiIdReference>()
            .single()
        val targetDeclaration = targetReference.resolve() as JmixFlowUiIdElement
        assertEquals("name", targetDeclaration.declaration.localName)
        val xmlIdentifier = targetDeclaration.declaration.valueElement!!
        assertTrue(
            xmlIdentifier.references
                .filterIsInstance<JmixFlowUiIdDeclarationReference>()
                .single()
                .resolve() is JmixFlowUiIdElement,
        )
        val problems = controllerProblems()
        assertTrue(
            problems.joinToString("\n") { it.description.orEmpty() },
            problems.isEmpty(),
        )
    }

    fun testViewComponentSetterInjectionContractsMatchRuntimeInJavaAndKotlin() {
        addFlowUiAnnotations()
        addStudioMetadataAnnotations()
        addVaadinListenerTypes()
        addMetadataBackedButtons(includeSecondClickSubject = false)
        myFixture.addClass(
            """
            package com.company.ui;
            public class WrongButton extends com.vaadin.flow.component.Component {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/employee-view.xml",
            """
            <view xmlns="http://jmix.io/schema/flowui/view">
                <layout>
                    <button id="saveButton"/>
                    <button id="cancelButton"/>
                </layout>
            </view>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixJavaFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.java",
            """
            package com.company.payroll;

            import com.company.ui.JmixButton;
            import com.company.ui.WrongButton;
            import io.jmix.flowui.view.ViewComponent;
            import io.jmix.flowui.view.ViewController;
            import io.jmix.flowui.view.ViewDescriptor;

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            public class EmployeeView {
                @ViewComponent
                private void setSaveButton(JmixButton button) {
                }

                @ViewComponent("cancelButton")
                private String injectCancel(WrongButton button) {
                    return "invalid";
                }

                @ViewComponent
                private void brokenSignature() {
                }
            }
            """.trimIndent(),
        )
        val javaProblems = controllerProblems().mapNotNull { it.description }
        assertFalse(javaProblems.any { "setSaveButton()" in it })
        assertTrue(javaProblems.any { "injectCancel()' has type WrongButton" in it })
        assertFalse(javaProblems.any { "must return void" in it })
        assertTrue(javaProblems.any { "must declare exactly one parameter" in it })
        val javaSnapshot = visualSnapshotForCurrentController()
        assertTrue(javaSnapshot.psiSupported)
        assertEquals("java", javaSnapshot.language)
        assertEquals(3, javaSnapshot.injections.size)
        assertTrue(
            javaSnapshot.injections
                .single { it.fieldName == "injectCancel()" }
                .issues
                .any { it.code == "JMIX-VIEW-COMPONENT-TYPE" },
        )

        myFixture.enableInspections(JmixKotlinFlowUiControllerInspection())
        myFixture.configureByText(
            "EmployeeView.kt",
            """
            package com.company.payroll

            import com.company.ui.JmixButton
            import com.company.ui.WrongButton
            import io.jmix.flowui.view.ViewComponent
            import io.jmix.flowui.view.ViewController
            import io.jmix.flowui.view.ViewDescriptor

            @ViewController("Employee.list")
            @ViewDescriptor("employee-view.xml")
            class EmployeeView {
                @ViewComponent
                fun setSaveButton(button: JmixButton) {
                }

                @ViewComponent("cancelButton")
                fun injectCancel(button: WrongButton): String = "invalid"

                @ViewComponent
                fun brokenSignature() {
                }
            }
            """.trimIndent(),
        )
        val kotlinProblems = controllerProblems().mapNotNull { it.description }
        assertFalse(kotlinProblems.any { "setSaveButton()" in it })
        assertTrue(kotlinProblems.any { "injectCancel()' has type WrongButton" in it })
        assertFalse(kotlinProblems.any { "must return Unit" in it })
        assertTrue(kotlinProblems.any { "must declare exactly one parameter" in it })
        val kotlinSnapshot = visualSnapshotForCurrentController()
        assertFalse(kotlinSnapshot.psiSupported)
        assertEquals("kotlin", kotlinSnapshot.language)
        assertEquals(3, kotlinSnapshot.injections.size)
        assertTrue(
            kotlinSnapshot.injections
                .single { it.fieldName == "injectCancel()" }
                .issues
                .any {
                    it.code == "JMIX-KOTLIN-VIEW-COMPONENT-METHOD-TYPE"
                },
        )
    }

    private fun visualSnapshotForCurrentController():
        FlowUiControllerWorkspaceSnapshot {
        val relativePath = ProjectFileResolver.getInstance(project)
            .locatorPath(myFixture.file.virtualFile)
        assertNotNull(relativePath)
        val fingerprint = CanonicalDiscoveryJson.sha256(myFixture.file.text)
        return FlowUiControllerPsiReader.read(
            project,
            listOf(
                ArtifactSnapshot(
                    id = "controller",
                    kind = ArtifactKind.VIEW_CONTROLLER,
                    semanticKey = myFixture.file.name,
                    owner = ArtifactOwner("build", "app", "main"),
                    sourceLocator = SourceLocator(
                        relativePath = relativePath!!,
                        revisionFingerprint = fingerprint,
                    ),
                    origin = ArtifactOrigin.SOURCE,
                    fingerprint = fingerprint,
                    displayName = myFixture.file.name,
                    summary = null,
                ),
            ),
        )!!
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

    private fun addStudioMetadataAnnotations() {
        myFixture.addClass(
            """
            package io.jmix.flowui.kit.meta;
            public @interface StudioCustomSubscription {
                String methodName();
                String eventClassFqn();
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
                StudioCustomSubscription[] customSubscriptions() default {};
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.kit.meta;
            public @interface StudioDataComponent {
                String classFqn() default "";
                String xmlElement() default "";
                String xmlns() default "";
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.kit.meta;
            public @interface StudioFacet {
                String classFqn() default "";
                String xmlElement() default "";
                String xmlns() default "";
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.flowui.kit.meta;
            public @interface StudioElement {
                String classFqn() default "";
                String xmlElement() default "";
                String xmlns() default "";
                String injectionIdentifier() default "id";
                boolean isInjectable() default true;
            }
            """.trimIndent(),
        )
    }

    private fun addVaadinListenerTypes() {
        myFixture.addClass(
            """
            package com.vaadin.flow.component;
            public class Component {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.vaadin.flow.component;
            public interface ComponentEventListener<E extends java.util.EventObject> {
                void onComponentEvent(E event);
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.vaadin.flow.shared;
            public interface Registration {
                void remove();
            }
            """.trimIndent(),
        )
    }

    private fun addMetadataBackedButtons(
        includeSecondClickSubject: Boolean,
    ) {
        myFixture.addClass(
            """
            package com.company.ui;
            public class ClickEvent extends java.util.EventObject {
                public ClickEvent(Object source) {
                    super(source);
                }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.ui;
            public class JmixButton extends com.vaadin.flow.component.Component {
                public com.vaadin.flow.shared.Registration addClickListener(
                        com.vaadin.flow.component.ComponentEventListener<ClickEvent> listener) {
                    return null;
                }
                ${
                if (includeSecondClickSubject) {
                    """
                    public com.vaadin.flow.shared.Registration addPrimaryClickListener(
                            com.vaadin.flow.component.ComponentEventListener<ClickEvent> listener) {
                        return null;
                    }
                    """.trimIndent()
                } else {
                    ""
                }
                }
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.ui;
            public interface FlowUiMetadata {
                @io.jmix.flowui.kit.meta.StudioComponent(
                    classFqn = "com.company.ui.JmixButton",
                    xmlElement = "button"
                )
                Object button();
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

package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeUiSecurityAssistanceTest : LightJavaCodeInsightFixtureTestCase() {

    fun testIndependentIndexesPreventCrossArtifactCacheEviction() {
        addViewAndSecurityAnnotations()
        myFixture.addClass(
            """
            package com.company.payroll.view.loan;

            import io.jmix.flowui.view.ViewController;

            @ViewController("LoanApplication.list")
            public class LoanApplicationListView {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/security/LoanApprovalRole.java",
            """
            package com.company.payroll.security;

            import io.jmix.security.role.annotation.SpecificPolicy;

            public interface LoanApprovalRole {
                @SpecificPolicy(resources = {"loan.approve"})
                void approve();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="payroll"/>
            </menu-config>
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/messages.properties",
            "menu.payroll=Payroll",
        )
        myFixture.configureByText("empty-view.xml", "<view><layout/></view>")

        val service = JmixUiSecuritySymbolService.getInstance(project)
        val initialViews = service.viewIds()
        val initialMenus = service.menuIds()
        val initialMessages = service.messages()
        val initialPolicies = service.specificPolicies()

        myFixture.addFileToProject(
            "com/company/payroll/messages_bn.properties",
            "menu.payroll=বেতন",
        )

        val localizedMessages = service.messages()
        assertNotSame(initialMessages, localizedMessages)
        assertEquals(2, localizedMessages.size)
        assertSame(initialViews, service.viewIds())
        assertSame(initialMenus, service.menuIds())
        assertSame(initialPolicies, service.specificPolicies())

        myFixture.addFileToProject(
            "com/company/payroll/operations-menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="operations"/>
            </menu-config>
            """.trimIndent(),
        )

        assertNotSame(initialMenus, service.menuIds())
        assertEquals(2, service.menuIds().size)
        assertSame(initialViews, service.viewIds())
        assertSame(localizedMessages, service.messages())
        assertSame(initialPolicies, service.specificPolicies())
    }

    fun testXmlCandidateClassifierSkipsPrologCommentsAndDoctype() {
        val descriptor = """
            <?xml version="1.0"?>
            <!-- generated comment -->
            <!DOCTYPE menu-config [
                <!ENTITY product "Payroll">
            ]>
            <flow:menu-config xmlns:flow="http://jmix.io/schema/flowui/menu">
                <flow:menu id="payroll"/>
            </flow:menu-config>
        """.trimIndent()

        assertEquals("menu-config", firstXmlElementLocalName(descriptor))
        assertEquals(
            "fetchPlans",
            firstXmlElementLocalName(
                "<!-- comment --><fetchPlans xmlns=\"http://jmix.io/schema/core/fetch-plans\"/>",
            ),
        )
        assertNull(firstXmlElementLocalName("<!-- no root -->"))
    }

    fun testJavaViewPolicyMenuRouteAndViewDeclarationRenameTogether() {
        addViewAndSecurityAnnotations()
        val controller = myFixture.addClass(
            """
            package com.company.payroll.view.loan;

            import io.jmix.flowui.view.ViewController;

            @ViewController("LoanApplication.list")
            public class LoanApplicationListView {
            }
            """.trimIndent(),
        )
        val menu = myFixture.addFileToProject(
            "com/company/payroll/menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="payroll">
                    <item id="loanApplications"
                          view="LoanApplication.list"/>
                </menu>
            </menu-config>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "PayrollOfficerRole.java",
            """
            import io.jmix.securityflowui.role.annotation.ViewPolicy;

            public interface PayrollOfficerRole {
                @ViewPolicy(viewIds = {"LoanApplication.li<caret>st"})
                void loans();
            }
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixJavaViewIdReference>()
        val target = reference.resolve() as JmixViewIdElement
        assertEquals("LoanApplication.list", target.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "LoanApplication.list" },
        )
        assertEquals(3, ReferencesSearch.search(target).findAll().size)

        myFixture.renameElement(target, "LoanApplication.overview")

        assertTrue(
            controller.containingFile.text.contains(
                """@ViewController("LoanApplication.overview")""",
            ),
        )
        assertTrue(menu.text.contains("""view="LoanApplication.overview""""))
        assertTrue(myFixture.file.text.contains("""viewIds = {"LoanApplication.overview"}"""))
    }

    fun testMenuDeclarationNavigatesAndRenamesJavaAndKotlinPolicies() {
        addViewAndSecurityAnnotations()
        val javaRole = myFixture.addFileToProject(
            "com/company/payroll/security/PayrollRole.java",
            """
            package com.company.payroll.security;

            import io.jmix.securityflowui.role.annotation.MenuPolicy;

            public interface PayrollRole {
                @MenuPolicy(menuIds = {"payroll"})
                void payroll();
            }
            """.trimIndent(),
        )
        val kotlinRole = myFixture.addFileToProject(
            "com/company/payroll/security/PayrollAuditRole.kt",
            """
            package com.company.payroll.security

            import io.jmix.securityflowui.role.annotation.MenuPolicy

            interface PayrollAuditRole {
                @MenuPolicy(menuIds = ["payroll"])
                fun payroll()
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="pay<caret>roll">
                    <menu id="loans">
                        <item view="LoanApplication.list" id="loanApplications"/>
                    </menu>
                </menu>
            </menu-config>
            """.trimIndent(),
        )

        val declaration = referenceAtCaret<JmixXmlMenuIdDeclarationReference>()
        val target = declaration.resolve() as JmixXmlAttributeNamedElement
        assertEquals("payroll", target.name)
        assertEquals(3, ReferencesSearch.search(target).findAll().size)

        myFixture.renameElement(target, "payrollOperations")

        assertTrue(myFixture.file.text.contains("""id="payrollOperations""""))
        assertTrue(javaRole.text.contains("""menuIds = {"payrollOperations"}"""))
        assertTrue(kotlinRole.text.contains("""menuIds = ["payrollOperations"]"""))
    }

    fun testXmlAndJavaMessageReferencesNavigateCompleteAndRenamePropertyKey() {
        val properties = myFixture.addFileToProject(
            "com/company/payroll/view/loan/messages.properties",
            """
            LoanListView.title=Loan applications
            LoanListView.empty=No loan applications
            """.trimIndent(),
        ) as PropertiesFile
        val javaUsage = myFixture.addFileToProject(
            "com/company/payroll/view/loan/LoanNotifier.java",
            """
            package com.company.payroll.view.loan;

            public class LoanNotifier {
                private Object messageBundle;

                public String title() {
                    return messageBundle.getMessage("LoanListView.title");
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "loan-list-view.xml",
            """
            <view messagesGroup="com.company.payroll.view.loan"
                  title="msg://LoanListView.ti<caret>tle">
                <layout/>
            </view>
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixXmlMessageReference>()
        val property = reference.resolve() as Property
        assertEquals("LoanListView.title", property.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "LoanListView.title" },
        )
        assertEquals(2, ReferencesSearch.search(property).findAll().size)

        myFixture.renameElement(property, "LoanListView.caption")

        assertTrue(myFixture.file.text.contains("msg://LoanListView.caption"))
        assertTrue(javaUsage.text.contains("""getMessage("LoanListView.caption")"""))
        assertTrue(
            properties.containingFile.text.contains(
                "LoanListView.caption=Loan applications",
            ),
        )
    }

    fun testLocalizedMessageReferencesResolveAndRenameAsOneLogicalKey() {
        val baseBundle = myFixture.addFileToProject(
            "com/company/payroll/view/loan/messages.properties",
            "approval.complete=Loan approved",
        ) as PropertiesFile
        val bengaliBundle = myFixture.addFileToProject(
            "com/company/payroll/view/loan/messages_bn.properties",
            "approval.complete=ঋণ অনুমোদিত",
        ) as PropertiesFile
        val xmlUsage = myFixture.addFileToProject(
            "com/company/payroll/view/loan/loan-view.xml",
            """
            <view messagesGroup="com.company.payroll.view.loan"
                  title="msg://approval.complete">
                <layout/>
            </view>
            """.trimIndent(),
        )
        val javaUsage = myFixture.addFileToProject(
            "com/company/payroll/view/loan/LoanNotifier.java",
            """
            package com.company.payroll.view.loan;

            public class LoanNotifier {
                private Object messageBundle;

                public String text() {
                    return messageBundle.getMessage("approval.complete");
                }
            }
            """.trimIndent(),
        )
        val kotlinUsage = myFixture.addFileToProject(
            "com/company/payroll/view/loan/LoanNotifications.kt",
            """
            package com.company.payroll.view.loan

            class LoanNotifications {
                lateinit var messageBundle: Any

                fun text(): String =
                    messageBundle.getMessage("approval.complete")
            }
            """.trimIndent(),
        )

        myFixture.enableInspections(
            JmixUiXmlReferenceInspection(),
            JmixJavaReferenceInspection(),
            JmixKotlinReferenceInspection(),
        )
        listOf(xmlUsage, javaUsage, kotlinUsage).forEach { usage ->
            myFixture.configureFromExistingVirtualFile(usage.virtualFile)
            assertFalse(
                "Valid localized key was reported unresolved in ${usage.name}",
                myFixture.doHighlighting().any {
                    it.description?.contains("Unresolved Jmix") == true
                },
            )
            val reference = PsiTreeUtil.findChildrenOfType(
                myFixture.file,
                PsiLanguageInjectionHost::class.java,
            ).asSequence()
                .flatMap { it.references.asSequence() }
                .filter {
                    it is JmixXmlMessageReference ||
                        it is JmixJavaMessageReference ||
                        it is JmixKotlinMessageReference
                }
                .single()
            val targets = when (reference) {
                is JmixXmlMessageReference -> reference.multiResolve(false)
                is JmixJavaMessageReference -> reference.multiResolve(false)
                is JmixKotlinMessageReference -> reference.multiResolve(false)
                else -> error("Unexpected reference ${reference.javaClass.name}")
            }
            assertEquals(2, targets.size)
            assertNull(reference.resolve())
        }

        val baseProperty = baseBundle.properties.single() as Property
        val processors = RenamePsiElementProcessor.allForElement(baseProperty)
        assertTrue(processors.any { it is JmixMessageBundleRenameProcessor })
        val preparedRenames = linkedMapOf<PsiElement, String>(
            baseProperty to "approval.finished",
        )
        processors.forEach { processor ->
            processor.prepareRenaming(
                baseProperty,
                "approval.finished",
                preparedRenames,
            )
        }
        assertEquals(
            preparedRenames.keys.joinToString { it.containingFile.name },
            setOf("messages.properties", "messages_bn.properties"),
            preparedRenames.keys.map { it.containingFile.name }.toSet(),
        )
        myFixture.renameElement(baseProperty, "approval.finished")

        assertTrue(baseBundle.text.contains("approval.finished=Loan approved"))
        assertTrue(
            "Localized bundle was not renamed:\n${bengaliBundle.text}",
            bengaliBundle.text.contains("approval.finished=") &&
                !bengaliBundle.text.contains("approval.complete="),
        )
        assertTrue(xmlUsage.text.contains("msg://approval.finished"))
        assertTrue(javaUsage.text.contains("""getMessage("approval.finished")"""))
        assertTrue(kotlinUsage.text.contains("""getMessage("approval.finished")"""))
    }

    fun testJavaEntityAttributeAndRowPolicyPathsFollowNestedFieldRename() {
        addJmixEntityAnnotation()
        addEntitySecurityAnnotations()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Branch {
                private String branchCode;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class LoanApplication {
                private Branch branch;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "LoanOfficerRole.java",
            """
            import com.company.payroll.entity.LoanApplication;
            import io.jmix.security.role.annotation.EntityAttributePolicy;
            import io.jmix.security.role.annotation.JpqlRowLevelPolicy;

            public interface LoanOfficerRole {
                @EntityAttributePolicy(
                    entityClass = LoanApplication.class,
                    attributes = {"branch.branchC<caret>ode"})
                @JpqlRowLevelPolicy(
                    entityClass = LoanApplication.class,
                    where = "{E}.branch.branchCode = :current_user_branch")
                void loans();
            }
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixJavaEntityPropertyReference>()
        val field = reference.resolve() as PsiField
        assertEquals("branchCode", field.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "branchCode" },
        )
        assertEquals(2, ReferencesSearch.search(field).findAll().size)

        myFixture.renameElement(field, "officeCode")

        assertTrue(myFixture.file.text.contains("""attributes = {"branch.officeCode"}"""))
        assertTrue(
            myFixture.file.text.contains(
                """where = "{E}.branch.officeCode = :current_user_branch"""",
            ),
        )
    }

    fun testSecurityEntityNameUsesMetadataAliasAndSurvivesJavaClassRename() {
        addJmixEntityAnnotation()
        addEntitySecurityAnnotations()
        val entity = myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity(name = "payroll_LoanApplication")
            public class LoanApplication {
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "LoanReaderRole.java",
            """
            import io.jmix.security.role.annotation.EntityPolicy;

            public interface LoanReaderRole {
                @EntityPolicy(entityName = "payroll_LoanAppli<caret>cation")
                void loans();
            }
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixJavaEntityNameReference>()
        assertEquals(entity, reference.resolve())
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "payroll_LoanApplication" },
        )

        myFixture.renameElement(entity, "CreditApplication")

        assertTrue(
            myFixture.file.text.contains(
                """entityName = "payroll_LoanApplication"""",
            ),
        )
    }

    fun testKotlinViewSecurityAndMessageReferencesAreNativeAndRenameSafe() {
        addViewAndSecurityAnnotations()
        val view = myFixture.addFileToProject(
            "com/company/payroll/view/loan/LoanApplicationListView.kt",
            """
            package com.company.payroll.view.loan

            import io.jmix.flowui.view.ViewController

            @ViewController("LoanApplication.list")
            class LoanApplicationListView
            """.trimIndent(),
        )
        val menu = myFixture.addFileToProject(
            "com/company/payroll/menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="payroll">
                    <item view="LoanApplication.list" id="loanApplications"/>
                </menu>
            </menu-config>
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/view/loan/messages.properties",
            "approval.complete=Loan approved",
        )
        val messageUsage = myFixture.addFileToProject(
            "com/company/payroll/view/loan/LoanNotifications.kt",
            """
            package com.company.payroll.view.loan

            class LoanNotifications {
                lateinit var messageBundle: Any

                fun text(): String =
                    messageBundle.getMessage("approval.complete")
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "PayrollRole.kt",
            """
            package com.company.payroll.security

            import io.jmix.securityflowui.role.annotation.ViewPolicy

            interface PayrollRole {
                @ViewPolicy(viewIds = ["LoanApplication.li<caret>st"])
                fun loans()
            }
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixKotlinViewIdReference>()
        val target = reference.resolve() as JmixViewIdElement
        assertEquals("LoanApplication.list", target.name)
        assertEquals(3, ReferencesSearch.search(target).findAll().size)

        myFixture.renameElement(target, "LoanApplication.queue")

        assertTrue(view.text.contains("""@ViewController("LoanApplication.queue")"""))
        assertTrue(menu.text.contains("""view="LoanApplication.queue""""))
        assertTrue(myFixture.file.text.contains("""viewIds = ["LoanApplication.queue"]"""))

        myFixture.configureFromExistingVirtualFile(messageUsage.virtualFile)
        val messageHost = PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            PsiLanguageInjectionHost::class.java,
        ).single { it.text.contains("approval.complete") }
        val messageReference = messageHost.references
            .filterIsInstance<JmixKotlinMessageReference>()
            .single()
        assertEquals("approval.complete", (messageReference.resolve() as Property).name)
    }

    fun testKotlinEntityAttributeAndJpqlRowPolicyPathsAreConnected() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class LoanApplication {
                private String processState;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "LoanRowRole.kt",
            """
            package com.company.payroll.security

            import com.company.payroll.entity.LoanApplication
            import io.jmix.security.role.annotation.EntityAttributePolicy
            import io.jmix.security.role.annotation.JpqlRowLevelPolicy

            interface LoanRowRole {
                @EntityAttributePolicy(
                    entityClass = LoanApplication::class,
                    attributes = ["processS<caret>tate"])
                @JpqlRowLevelPolicy(
                    entityClass = LoanApplication::class,
                    where = "{E}.processState = 'APPROVED'")
                fun approvedLoans()
            }
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixKotlinEntityPropertyReference>()
        val field = reference.resolve() as PsiField
        assertEquals("processState", field.name)
        assertEquals(2, ReferencesSearch.search(field).findAll().size)

        myFixture.renameElement(field, "workflowState")

        assertTrue(myFixture.file.text.contains("""attributes = ["workflowState"]"""))
        assertTrue(myFixture.file.text.contains("""where = "{E}.workflowState = 'APPROVED'""""))
    }

    fun testSpecificPermissionDeclarationConnectsJavaAndKotlinServiceChecks() {
        addViewAndSecurityAnnotations()
        val role = myFixture.addFileToProject(
            "com/company/payroll/security/LoanApprovalRole.java",
            """
            package com.company.payroll.security;

            import io.jmix.security.role.annotation.SpecificPolicy;

            public interface LoanApprovalRole {
                @SpecificPolicy(resources = {"loan.approve"})
                void approve();
            }
            """.trimIndent(),
        )
        val kotlinService = myFixture.addFileToProject(
            "com/company/payroll/service/LoanAuditService.kt",
            """
            package com.company.payroll.service

            class LoanAuditService {
                fun check() =
                    SpecificOperationAccessContext("loan.approve")
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "LoanApprovalService.java",
            """
            public class LoanApprovalService {
                public Object check() {
                    return new SpecificOperationAccessContext("loan.ap<caret>prove");
                }
            }
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixJavaSpecificPolicyReference>()
        val target = reference.resolve() as JmixSpecificPolicyElement
        assertEquals("loan.approve", target.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "loan.approve" },
        )
        assertEquals(3, ReferencesSearch.search(target).findAll().size)

        myFixture.renameElement(target, "loan.authorize")

        assertTrue(role.text.contains("""resources = {"loan.authorize"}"""))
        assertTrue(myFixture.file.text.contains("""("loan.authorize")"""))
        assertTrue(kotlinService.text.contains("""("loan.authorize")"""))
    }

    fun testUnresolvedMenuViewMessageAndSecurityReferencesAreHighlightedAndFixed() {
        addViewAndSecurityAnnotations()
        myFixture.addClass(
            """
            package com.company.payroll.view.loan;

            import io.jmix.flowui.view.ViewController;

            @ViewController("LoanApplication.list")
            public class LoanApplicationListView {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/view/loan/messages.properties",
            "LoanListView.title=Loan applications",
        )
        myFixture.enableInspections(JmixUiXmlReferenceInspection())
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="payroll"
                      title="msg://com.company.payroll.view.loan/LoanListView.titel">
                    <item view="LoanApplication.li<caret>ts" id="loanApplications"/>
                </menu>
            </menu-config>
            """.trimIndent(),
        )

        val problems = myFixture.doHighlighting()
            .filter { it.description?.contains("Unresolved Jmix") == true }
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.severity == HighlightSeverity.ERROR })

        myFixture.launchAction(
            myFixture.findSingleIntention("Replace with 'LoanApplication.list'"),
        )
        assertTrue(myFixture.file.text.contains("""view="LoanApplication.list""""))

        val duplicateProblemFile = myFixture.configureByText(
            "duplicate-menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="payroll"/>
                <menu id="payroll"/>
            </menu-config>
            """.trimIndent(),
        )
        val duplicateProblems = myFixture.doHighlighting()
            .filter { it.description?.contains("Duplicate Jmix menu id") == true }
        assertEquals(2, duplicateProblems.size)
        assertTrue(duplicateProblemFile.text.contains("""id="payroll""""))
    }

    private inline fun <reified T : PsiReference> referenceAtCaret(): T {
        val leaf = requireNotNull(myFixture.file.findElementAt(myFixture.caretOffset - 1))
        val host = generateSequence(leaf as PsiElement?) { it.parent }
            .firstOrNull { element ->
                element is XmlAttributeValue ||
                    element is PsiLiteralExpression ||
                    element is PsiLanguageInjectionHost &&
                    element.javaClass.simpleName == "KtStringTemplateExpression"
            }
            ?: error("No string reference host at caret")
        val offsetInHost = myFixture.caretOffset - host.textRange.startOffset
        return host.references.filterIsInstance<T>().singleOrNull { reference ->
            reference.rangeInElement.containsOffset(offsetInHost)
        } ?: error(
            "No ${T::class.java.simpleName} at $offsetInHost for ${host.text}; " +
                "references=${host.references.joinToString { it.javaClass.simpleName }}",
        )
    }

    private fun addViewAndSecurityAnnotations() {
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
            package io.jmix.securityflowui.role.annotation;
            public @interface ViewPolicy {
                String[] viewIds();
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
            package io.jmix.securityflowui.role.annotation;
            public @interface MenuPolicy {
                String[] menuIds();
            }
            """.trimIndent(),
        )
    }

    private fun addJmixEntityAnnotation() {
        myFixture.addClass(
            """
            package io.jmix.core.metamodel.annotation;
            public @interface JmixEntity {
                String name() default "";
                String value() default "";
            }
            """.trimIndent(),
        )
    }

    private fun addEntitySecurityAnnotations() {
        myFixture.addClass(
            """
            package io.jmix.security.role.annotation;
            public @interface EntityPolicy {
                Class<?> entityClass() default Object.class;
                String entityName() default "";
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.security.role.annotation;
            public @interface EntityAttributePolicy {
                Class<?> entityClass();
                String entityName() default "";
                String[] attributes();
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.security.role.annotation;
            public @interface JpqlRowLevelPolicy {
                Class<?> entityClass();
                String entityName() default "";
                String where();
                String join() default "";
            }
            """.trimIndent(),
        )
    }
}

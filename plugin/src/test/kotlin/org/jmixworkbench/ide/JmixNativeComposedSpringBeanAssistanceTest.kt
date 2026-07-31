package org.jmixworkbench.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeComposedSpringBeanAssistanceTest :
    LightJavaCodeInsightFixtureTestCase() {

    fun testJavaComposedStereotypeAliasNavigatesAndRefactors() {
        addComposedStereotypeFramework()
        val beanFile = myFixture.addFileToProject(
            "com/company/payroll/service/PayrollMenu.java",
            """
            package com.company.payroll.service;

            @PayrollService(name = "payroll_Menu")
            public class PayrollMenu {
                public void execute() {
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="payroll_M<caret>enu" beanMethod="execute"/>
            </menu-config>
            """.trimIndent(),
        )

        val bean = referenceAtCaret<JmixXmlSpringBeanReference>().resolve()
            as JmixSpringBeanElement
        assertEquals("payroll_Menu", bean.name)
        assertEquals("PayrollMenu", bean.declaration.classElement.name)

        myFixture.renameElement(bean, "payroll_Compensation")

        assertTrue(
            beanFile.text.contains(
                """@PayrollService(name = "payroll_Compensation")""",
            ),
        )
        assertTrue(
            myFixture.file.text.contains(
                """bean="payroll_Compensation"""",
            ),
        )
    }

    fun testRecursiveComposedStereotypeExposesRestServiceMethod() {
        addComposedStereotypeFramework()
        myFixture.addFileToProject(
            "com/company/payroll/service/DomainService.java",
            """
            package com.company.payroll.service;

            @PayrollService
            public @interface DomainService {
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/service/LoanApprovalService.java",
            """
            package com.company.payroll.service;

            @DomainService
            public class LoanApprovalService {
                public String approve(String applicationId) {
                    return applicationId;
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="loanApprovalService">
                    <method name="app<caret>rove">
                        <param name="applicationId"
                               type="java.lang.String"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )

        val method = referenceAtCaret<JmixRestServiceMethodReference>().resolve()
            as PsiNamedElement
        assertEquals("approve", method.name)
    }

    fun testKotlinBeanUsingJavaComposedStereotypeKeepsAliasRenameSafe() {
        addComposedStereotypeFramework()
        val kotlinFile = myFixture.addFileToProject(
            "com/company/payroll/service/KotlinPayrollMenu.kt",
            """
            package com.company.payroll.service

            @PayrollService(name = "payroll_KotlinMenu")
            class KotlinPayrollMenu {
                fun execute() {
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="payroll_Kotlin<caret>Menu"
                      beanMethod="execute"/>
            </menu-config>
            """.trimIndent(),
        )

        val bean = referenceAtCaret<JmixXmlSpringBeanReference>().resolve()
            as JmixSpringBeanElement
        assertEquals("payroll_KotlinMenu", bean.name)

        myFixture.renameElement(bean, "payroll_KotlinCompensation")

        assertTrue(
            kotlinFile.text.contains(
                """@PayrollService(name = "payroll_KotlinCompensation")""",
            ),
        )
        assertTrue(
            myFixture.file.text.contains(
                """bean="payroll_KotlinCompensation"""",
            ),
        )
    }

    fun testKotlinDeclaredComposedStereotypeIsDiscoveredRecursively() {
        addComposedStereotypeFramework()
        myFixture.addFileToProject(
            "com/company/payroll/service/KotlinDomainService.kt",
            """
            package com.company.payroll.service

            import org.springframework.stereotype.Component

            @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
            @Retention(AnnotationRetention.RUNTIME)
            @Component
            annotation class KotlinDomainService

            @KotlinDomainService
            class KotlinLoanService {
                fun approve() {
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="kotlinLoanService"
                      beanMethod="app<caret>rove"/>
            </menu-config>
            """.trimIndent(),
        )

        val method =
            referenceAtCaret<JmixXmlSpringBeanMethodReference>().resolve()
                as PsiNamedElement
        assertEquals("approve", method.name)
    }

    fun testKeyedStereotypeUsageDoesNotEvictForUnrelatedAnnotations() {
        addComposedStereotypeFramework()
        val beanFile = myFixture.addFileToProject(
            "com/company/payroll/service/PayrollServiceBean.java",
            """
            package com.company.payroll.service;

            @PayrollService
            public class PayrollServiceBean {
                public void execute() {
                }
            }
            """.trimIndent(),
        )
        val symbols = JmixSpringBeanSymbolService.getInstance(project)
        val initial = symbols.beans()
        assertEquals(
            listOf("payrollServiceBean"),
            initial.map { it.name },
        )

        myFixture.addFileToProject(
            "com/company/payroll/UnrelatedAnnotatedType.java",
            """
            package com.company.payroll;

            @Deprecated
            public class UnrelatedAnnotatedType {
            }
            """.trimIndent(),
        )
        assertSame(initial, symbols.beans())

        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(
                beanFile.virtualFile,
                """
                package com.company.payroll.service;

                @PayrollService
                public class PayrollServiceBean {
                    public void execute() {
                    }

                    public void reconcile() {
                    }
                }
                """.trimIndent(),
            )
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val updated = symbols.beans()
        assertNotSame(initial, updated)
        assertContainsElements(
            updated.single().methods.map { it.name },
            "execute",
            "reconcile",
        )
    }

    private fun addComposedStereotypeFramework() {
        myFixture.addClass(
            """
            package org.springframework.stereotype;

            public @interface Component {
                String value() default "";
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.core.annotation;

            import java.lang.annotation.Annotation;

            public @interface AliasFor {
                Class<? extends Annotation> annotation() default Annotation.class;
                String attribute() default "";
                String value() default "";
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/company/payroll/service/PayrollService.java",
            """
            package com.company.payroll.service;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import org.springframework.core.annotation.AliasFor;
            import org.springframework.stereotype.Component;

            @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
            @Retention(RetentionPolicy.RUNTIME)
            @Component
            public @interface PayrollService {
                @AliasFor(annotation = Component.class, attribute = "value")
                String name() default "";
            }
            """.trimIndent(),
        )
    }

    private inline fun <reified T : PsiReference> referenceAtCaret(): T {
        val value = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
            XmlAttributeValue::class.java,
            false,
        ) ?: error("No XML attribute value at caret")
        val offset = myFixture.caretOffset - value.textRange.startOffset
        return value.references.filterIsInstance<T>().singleOrNull {
            it.rangeInElement.containsOffset(offset)
        } ?: error(
            "No ${T::class.java.simpleName} at $offset; " +
                "references=${value.references.joinToString { it.javaClass.simpleName }}",
        )
    }
}

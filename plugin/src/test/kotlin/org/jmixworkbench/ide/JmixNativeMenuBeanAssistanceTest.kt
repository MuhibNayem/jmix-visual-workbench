package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeMenuBeanAssistanceTest : LightJavaCodeInsightFixtureTestCase() {

    fun testJavaMenuBeanAndMethodCompleteNavigateAndRenameAcrossXml() {
        addSpringComponentAnnotation()
        val beanFile = myFixture.addFileToProject(
            "com/company/payroll/menu/PayrollMenu.java",
            """
            package com.company.payroll.menu;

            import java.util.Map;
            import org.springframework.stereotype.Component;

            @Component("PayrollMenu")
            public class PayrollMenu {
                public void closePeriod() {
                }

                public void openReport(Map<String, Object> parameters) {
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="payroll">
                    <item bean="PayrollM<caret>enu" beanMethod="closePeriod"/>
                </menu>
            </menu-config>
            """.trimIndent(),
        )

        val beanReference = referenceAtCaret<JmixXmlSpringBeanReference>()
        val beanTarget = beanReference.resolve() as JmixSpringBeanElement
        assertEquals("PayrollMenu", beanTarget.name)
        assertTrue(
            beanReference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "PayrollMenu" },
        )
        assertEquals(2, ReferencesSearch.search(beanTarget).findAll().size)

        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <menu id="payroll">
                    <item bean="PayrollMenu" beanMethod="closeP<caret>eriod"/>
                </menu>
            </menu-config>
            """.trimIndent(),
        )
        val methodReference = referenceAtCaret<JmixXmlSpringBeanMethodReference>()
        val method = methodReference.resolve() as PsiMethod
        assertEquals("closePeriod", method.name)
        val variants = methodReference.variants.filterIsInstance<LookupElement>()
            .map(LookupElement::getLookupString)
        assertContainsElements(variants, "closePeriod", "openReport")
        assertEquals(1, ReferencesSearch.search(method).findAll().size)

        myFixture.renameElement(method, "finalizePeriod")
        assertTrue(myFixture.file.text.contains("""beanMethod="finalizePeriod""""))
        assertTrue(beanFile.text.contains("void finalizePeriod()"))
    }

    fun testExplicitBeanRenameUpdatesDeclarationAndEveryMenuReference() {
        addSpringComponentAnnotation()
        val beanFile = myFixture.addFileToProject(
            "com/company/payroll/menu/PayrollNavigation.java",
            """
            package com.company.payroll.menu;

            import org.springframework.stereotype.Component;

            @Component("PayrollMenu")
            public class PayrollNavigation {
                public void closePeriod() {
                }
            }
            """.trimIndent(),
        )
        val secondMenu = myFixture.addFileToProject(
            "com/company/payroll/menu-extension.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="PayrollMenu" beanMethod="closePeriod"/>
            </menu-config>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="PayrollM<caret>enu" beanMethod="closePeriod"/>
            </menu-config>
            """.trimIndent(),
        )

        val target = referenceAtCaret<JmixXmlSpringBeanReference>()
            .resolve() as JmixSpringBeanElement
        assertEquals(3, ReferencesSearch.search(target).findAll().size)

        myFixture.renameElement(target, "PayrollOperations")

        assertTrue(beanFile.text.contains("""@Component("PayrollOperations")"""))
        assertTrue(myFixture.file.text.contains("""bean="PayrollOperations""""))
        assertTrue(secondMenu.text.contains("""bean="PayrollOperations""""))
        assertTrue(beanFile.text.contains("class PayrollNavigation"))
    }

    fun testKotlinMenuBeanMethodNavigatesCompletesAndRenames() {
        val kotlinFile = myFixture.addFileToProject(
            "com/company/payroll/menu/PayrollMenu.kt",
            """
            package com.company.payroll.menu

            import org.springframework.stereotype.Component

            @Component("PayrollMenu")
            class PayrollMenu {
                fun closePeriod() {
                }

                fun openReport(parameters: Map<String, Any>) {
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="PayrollMenu" beanMethod="openR<caret>eport"/>
            </menu-config>
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixXmlSpringBeanMethodReference>()
        val target = reference.resolve() as PsiNamedElement
        assertEquals("openReport", target.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "closePeriod" },
        )

        myFixture.renameElement(target, "showReport")
        assertTrue(myFixture.file.text.contains("""beanMethod="showReport""""))
        assertTrue(kotlinFile.text.contains("fun showReport("))
    }

    fun testImplicitBeanNameTracksNativeJavaClassRename() {
        addSpringComponentAnnotation()
        val beanFile = myFixture.addFileToProject(
            "com/company/payroll/menu/PayrollMenu.java",
            """
            package com.company.payroll.menu;

            import org.springframework.stereotype.Component;

            @Component
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
                <item bean="payrollM<caret>enu" beanMethod="execute"/>
            </menu-config>
            """.trimIndent(),
        )

        val target = referenceAtCaret<JmixXmlSpringBeanReference>()
            .resolve() as JmixSpringBeanElement
        val beanClass = target.declaration.classElement
        assertTrue(beanClass is com.intellij.psi.PsiClass)

        myFixture.renameElement(beanClass, "CompensationMenu")

        assertTrue(beanFile.text.contains("class CompensationMenu"))
        assertTrue(
            "Expected decapitalized implicit bean after class rename:\n${myFixture.file.text}",
            myFixture.file.text.contains("""bean="compensationMenu""""),
        )
    }

    fun testJavaBeanFactoryMethodParticipatesInNavigationAndSafeRename() {
        addSpringBeanAnnotation()
        val configuration = myFixture.addFileToProject(
            "com/company/payroll/menu/MenuConfiguration.java",
            """
            package com.company.payroll.menu;

            import org.springframework.context.annotation.Bean;

            public class MenuConfiguration {
                @Bean
                public PayrollMenu payrollMenu() {
                    return new PayrollMenu();
                }
            }

            class PayrollMenu {
                public void execute() {
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="payrollMenu" beanMethod="exe<caret>cute"/>
            </menu-config>
            """.trimIndent(),
        )

        val invocation = referenceAtCaret<JmixXmlSpringBeanMethodReference>()
        assertEquals("execute", (invocation.resolve() as PsiMethod).name)

        val beanAttribute = PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            XmlAttributeValue::class.java,
        ).single { it.value == "payrollMenu" }
        val bean = beanAttribute.references
            .filterIsInstance<JmixXmlSpringBeanReference>()
            .single()
            .resolve() as JmixSpringBeanElement
        val factoryMethod = bean.declaration.classElement
        assertTrue(factoryMethod is PsiMethod)

        myFixture.renameElement(factoryMethod, "compensationMenu")

        assertTrue(configuration.text.contains("PayrollMenu compensationMenu()"))
        assertTrue(myFixture.file.text.contains("""bean="compensationMenu""""))
    }

    fun testMenuBeanInspectionRejectsUnknownAmbiguousAndUnsafeMethods() {
        addSpringComponentAnnotation()
        myFixture.addFileToProject(
            "com/company/payroll/menu/BrokenMenu.java",
            """
            package com.company.payroll.menu;

            import java.util.Map;
            import org.springframework.stereotype.Component;

            @Component("BrokenMenu")
            public class BrokenMenu {
                private void hidden() {
                }

                public String wrongReturn() {
                    return "wrong";
                }

                public void wrongParameter(String value) {
                }

                public void rawMap(Map parameters) {
                }

                public void wrongKey(Map<Long, Object> parameters) {
                }

                public void wrongValue(Map<String, String> parameters) {
                }

                public void overloaded() {
                }

                public void overloaded(Map<String, Object> parameters) {
                }
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixUiXmlReferenceInspection())
        myFixture.configureByText(
            "menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="BrokenMenu" beanMethod="hidden"/>
                <item bean="BrokenMenu" beanMethod="wrongReturn"/>
                <item bean="BrokenMenu" beanMethod="wrongParameter"/>
                <item bean="BrokenMenu" beanMethod="rawMap"/>
                <item bean="BrokenMenu" beanMethod="wrongKey"/>
                <item bean="BrokenMenu" beanMethod="wrongValue"/>
                <item bean="BrokenMenu" beanMethod="overloaded"/>
                <item bean="MissingMenu" beanMethod="execute"/>
                <item bean="BrokenMenu"/>
            </menu-config>
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.ERROR }
            .mapNotNull { it.description }
        assertTrue(descriptions.any { "must be public" in it })
        assertTrue(descriptions.any { "must return void" in it })
        assertEquals(4, descriptions.count { "parameter must be Map" in it })
        assertTrue(descriptions.any { "overloaded methods are not safe" in it })
        assertTrue(descriptions.any { "Unresolved Jmix Spring bean reference" in it })
        assertTrue(descriptions.any { "requires both bean and beanMethod" in it })

        myFixture.addFileToProject(
            "com/company/payroll/menu/DuplicateMenu.java",
            """
            package com.company.payroll.menu;

            import org.springframework.stereotype.Component;

            @Component("BrokenMenu")
            public class DuplicateMenu {
                public void execute() {
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "ambiguous-menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="BrokenMenu" beanMethod="execute"/>
            </menu-config>
            """.trimIndent(),
        )
        assertTrue(
            myFixture.doHighlighting()
                .mapNotNull { it.description }
                .any { "Ambiguous Jmix Spring bean" in it },
        )
    }

    fun testKotlinMenuBeanRequiresExactStringAnyMapContract() {
        myFixture.addFileToProject(
            "com/company/payroll/menu/KotlinMenu.kt",
            """
            package com.company.payroll.menu

            import org.springframework.stereotype.Component

            @Component("KotlinMenu")
            class KotlinMenu {
                fun valid(parameters: Map<String, Any>) {
                }

                fun validNullable(parameters: MutableMap<String, Any?>) {
                }

                fun wrongKey(parameters: Map<Long, Any>) {
                }

                fun wrongValue(parameters: Map<String, String>) {
                }
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixUiXmlReferenceInspection())
        myFixture.configureByText(
            "kotlin-menu.xml",
            """
            <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                <item bean="KotlinMenu" beanMethod="valid"/>
                <item bean="KotlinMenu" beanMethod="validNullable"/>
                <item bean="KotlinMenu" beanMethod="wrongKey"/>
                <item bean="KotlinMenu" beanMethod="wrongValue"/>
            </menu-config>
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.ERROR }
            .mapNotNull { it.description }
        assertEquals(
            descriptions.joinToString("\n"),
            2,
            descriptions.count { "parameter must be Map<String, Any>" in it },
        )
    }

    fun testMenuBeanInventoryInvalidatesOnlyForSpringBeanCandidates() {
        addSpringComponentAnnotation()
        myFixture.addFileToProject(
            "com/company/payroll/menu/PayrollMenu.java",
            """
            package com.company.payroll.menu;

            import org.springframework.stereotype.Component;

            @Component
            public class PayrollMenu {
                public void execute() {
                }
            }
            """.trimIndent(),
        )
        val service = JmixSpringBeanSymbolService.getInstance(project)
        val initial = service.beans()
        assertEquals(listOf("payrollMenu"), initial.map { it.name })

        myFixture.addFileToProject(
            "com/company/payroll/Unrelated.java",
            """
            package com.company.payroll;
            public class Unrelated {
            }
            """.trimIndent(),
        )
        assertSame(initial, service.beans())

        myFixture.addFileToProject(
            "com/company/payroll/menu/ReportingMenu.java",
            """
            package com.company.payroll.menu;

            import org.springframework.stereotype.Component;

            @Component("ReportingMenu")
            public class ReportingMenu {
                public void open() {
                }
            }
            """.trimIndent(),
        )
        val updated = service.beans()
        assertNotSame(initial, updated)
        assertEquals(
            listOf("PayrollMenu", "ReportingMenu"),
            updated.mapNotNull { it.classElement.name }.sorted(),
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

    private fun addSpringComponentAnnotation() {
        myFixture.addClass(
            """
            package org.springframework.stereotype;
            public @interface Component {
                String value() default "";
            }
            """.trimIndent(),
        )
    }

    private fun addSpringBeanAnnotation() {
        myFixture.addClass(
            """
            package org.springframework.context.annotation;
            public @interface Bean {
                String[] value() default {};
                String[] name() default {};
            }
            """.trimIndent(),
        )
    }
}

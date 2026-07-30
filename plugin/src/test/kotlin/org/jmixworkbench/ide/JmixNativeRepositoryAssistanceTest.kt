package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeRepositoryAssistanceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
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
            package io.jmix.core.repository;
            public interface JmixDataRepository<E, ID> {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.core.repository;
            public @interface Query {
                String value();
                String[] properties() default {};
            }
            """.trimIndent(),
        )
    }

    fun testJavaRepositoryJpqlCompletesNavigatesFindsUsagesAndRenamesNestedProperties() {
        val code = domainClasses()
        myFixture.configureByText(
            "EmployeeRepository.java",
            """
            package com.company.payroll.repository;

            import com.company.payroll.entity.Employee;
            import io.jmix.core.repository.JmixDataRepository;
            import io.jmix.core.repository.Query;
            import java.util.List;
            import java.util.UUID;

            public interface EmployeeRepository extends JmixDataRepository<Employee, UUID> {
                @Query("select e from payroll_Employee e join e.department d where d.co<caret>de = :code")
                List<Employee> findByDepartmentCode(String code);
            }
            """.trimIndent(),
        )

        val reference = javaReferenceAtCaret<JmixJavaEntityPropertyReference>()
        assertEquals(code, reference.resolve())
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "code" },
        )
        assertEquals(1, ReferencesSearch.search(code).findAll().size)

        myFixture.renameElement(code, "costCenterCode")
        assertTrue(myFixture.file.text.contains("d.costCenterCode = :code"))
    }

    fun testJavaEscapedMultilineQueryKeepsPropertyReferenceRangesExact() {
        domainClasses()
        myFixture.configureByText(
            "EmployeeRepository.java",
            """
            package com.company.payroll.repository;

            import com.company.payroll.entity.Employee;
            import io.jmix.core.repository.JmixDataRepository;
            import io.jmix.core.repository.Query;
            import java.util.List;
            import java.util.UUID;

            public interface EmployeeRepository extends JmixDataRepository<Employee, UUID> {
                @Query("select e from payroll_Employee e\nwhere e.employeeNu<caret>mber = :number")
                List<Employee> findByNumber(String number);
            }
            """.trimIndent(),
        )

        val reference = javaReferenceAtCaret<JmixJavaEntityPropertyReference>()
        val field = reference.resolve() as PsiField
        assertEquals("employeeNumber", field.name)
        myFixture.renameElement(field, "personnelNumber")
        assertTrue(myFixture.file.text.contains("""\nwhere e.personnelNumber = :number"""))
    }

    fun testJavaRepositoryEntityNameParticipatesInNativeClassRename() {
        val employee = myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private String employeeNumber;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "EmployeeRepository.java",
            """
            package com.company.payroll.repository;

            import com.company.payroll.entity.Employee;
            import io.jmix.core.repository.JmixDataRepository;
            import io.jmix.core.repository.Query;
            import java.util.List;
            import java.util.UUID;

            public interface EmployeeRepository extends JmixDataRepository<Employee, UUID> {
                @Query("select e from Emplo<caret>yee e where e.employeeNumber = :number")
                List<Employee> findByNumber(String number);
            }
            """.trimIndent(),
        )

        val reference = javaReferenceAtCaret<JmixJavaRepositoryEntityNameReference>()
        assertEquals(employee, reference.resolve())
        myFixture.renameElement(employee, "StaffMember")
        assertTrue(myFixture.file.text.contains("from StaffMember e"))
        assertTrue(myFixture.file.text.contains("JmixDataRepository<StaffMember, UUID>"))
    }

    fun testJavaRepositoryJpqlTypoIsHighlightedAndFixed() {
        domainClasses()
        myFixture.enableInspections(JmixJavaReferenceInspection())
        myFixture.configureByText(
            "EmployeeRepository.java",
            """
            package com.company.payroll.repository;

            import com.company.payroll.entity.Employee;
            import io.jmix.core.repository.JmixDataRepository;
            import io.jmix.core.repository.Query;
            import java.util.List;
            import java.util.UUID;

            public interface EmployeeRepository extends JmixDataRepository<Employee, UUID> {
                @Query("select e from payroll_Employee e where e.employeeNub<caret>mer = :number")
                List<Employee> findByNumber(String number);
            }
            """.trimIndent(),
        )

        val problem = myFixture.doHighlighting()
            .single { it.description?.contains("Unresolved Jmix reference 'employeeNubmer'") == true }
        assertEquals(HighlightSeverity.ERROR, problem.severity)
        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'employeeNumber'"))
        assertTrue(myFixture.file.text.contains("e.employeeNumber = :number"))
    }

    fun testKotlinRepositoryJpqlUsesTheSameEntityPropertyReferenceModel() {
        val code = domainClasses()
        myFixture.configureByText(
            "EmployeeRepository.kt",
            """
            package com.company.payroll.repository

            import com.company.payroll.entity.Employee
            import io.jmix.core.repository.JmixDataRepository
            import io.jmix.core.repository.Query
            import java.util.UUID

            interface EmployeeRepository : JmixDataRepository<Employee, UUID> {
                @Query("select e from payroll_Employee e join e.department d where d.co<caret>de = :code")
                fun findByDepartmentCode(code: String): List<Employee>
            }
            """.trimIndent(),
        )

        val host = generateSequence(myFixture.file.findElementAt(myFixture.caretOffset - 1)) {
            it.parent
        }.filterIsInstance<PsiLanguageInjectionHost>().first()
        val reference = host.references
            .filterIsInstance<JmixKotlinEntityPropertyReference>()
            .first { it.rangeInElement.contains(myFixture.caretOffset - host.textRange.startOffset) }
        assertEquals(code, reference.resolve())
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "code" },
        )
    }

    private fun domainClasses(): PsiField {
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Department {
                public String code;
            }
            """.trimIndent(),
        )
        val employee = myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity(name = "payroll_Employee")
            public class Employee {
                public String employeeNumber;
                public Department department;
            }
            """.trimIndent(),
        )
        return (employee.findFieldByName("department", false)!!.type as com.intellij.psi.PsiClassType)
            .resolve()!!
            .findFieldByName("code", false)!!
    }

    private inline fun <reified T> javaReferenceAtCaret(): T {
        val literal = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
            PsiLiteralExpression::class.java,
            false,
        )!!
        return literal.references.filterIsInstance<T>().first {
            it is com.intellij.psi.PsiReference &&
                it.rangeInElement.contains(myFixture.caretOffset - literal.textRange.startOffset)
        }
    }
}

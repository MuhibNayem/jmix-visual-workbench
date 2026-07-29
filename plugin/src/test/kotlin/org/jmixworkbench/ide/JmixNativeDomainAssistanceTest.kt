package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeDomainAssistanceTest : LightJavaCodeInsightFixtureTestCase() {

    fun testFlowUiEntityClassCompletesNavigatesAndFollowsNativeClassRename() {
        addJmixEntityAnnotation()
        val entity = myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private String firstName;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "employee-list-view.xml",
            """
            <view>
                <data>
                    <collection id="employeesDc"
                                class="com.company.payroll.entity.Emplo<caret>yee"/>
                </data>
                <layout/>
            </view>
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixEntityClassReference>()
        assertEquals(entity, reference.resolve())
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "com.company.payroll.entity.Employee" },
        )

        myFixture.renameElement(entity, "StaffMember")
        assertTrue(
            myFixture.file.text.contains(
                """class="com.company.payroll.entity.StaffMember"""",
            ),
        )
    }

    fun testNestedEntityPropertiesCompleteNavigateFindUsagesAndRenameAcrossXml() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Department {
                private String name;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private Department department;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "employee-list-view.xml",
            """
            <view>
                <data>
                    <collection id="employeesDc"
                                class="com.company.payroll.entity.Employee">
                        <fetchPlan extends="_base">
                            <property name="department" fetchPlan="_base">
                                <property name="name"/>
                            </property>
                        </fetchPlan>
                    </collection>
                </data>
                <layout>
                    <dataGrid id="employeesGrid" dataContainer="employeesDc">
                        <columns>
                            <column property="department.na<caret>me"/>
                        </columns>
                    </dataGrid>
                </layout>
            </view>
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixEntityPropertyReference>()
        val field = reference.resolve() as PsiField
        assertEquals("name", field.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "name" },
        )
        assertEquals(2, ReferencesSearch.search(field).findAll().size)

        myFixture.renameElement(field, "displayName")
        assertTrue(myFixture.file.text.contains("""<property name="displayName"/>"""))
        assertTrue(myFixture.file.text.contains("""property="department.displayName""""))
    }

    fun testSharedFetchPlanNavigatesCompletesFindsUsagesAndRenamesSafely() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private String firstName;
            }
            """.trimIndent(),
        )
        val fetchPlans = myFixture.addFileToProject(
            "com/company/payroll/fetch-plans.xml",
            """
            <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                <fetchPlan class="com.company.payroll.entity.Employee"
                           name="employee-with-name"
                           extends="_base">
                    <property name="firstName"/>
                </fetchPlan>
            </fetchPlans>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "employee-list-view.xml",
            """
            <view>
                <data>
                    <collection id="employeesDc"
                                class="com.company.payroll.entity.Employee"
                                fetchPlan="employee-with-na<caret>me"/>
                </data>
                <layout/>
            </view>
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixNamedFetchPlanReference>()
        val declaration = reference.resolve() as JmixXmlAttributeNamedElement
        assertEquals("employee-with-name", declaration.name)
        val variants = reference.variants.filterIsInstance<LookupElement>()
            .map(LookupElement::getLookupString)
        assertTrue("employee-with-name" in variants)
        assertTrue("_base" in variants)
        assertEquals(2, ReferencesSearch.search(declaration).findAll().size)

        myFixture.renameElement(declaration, "employee-summary")
        assertTrue(fetchPlans.text.contains("""name="employee-summary""""))
        assertTrue(myFixture.file.text.contains("""fetchPlan="employee-summary""""))
    }

    fun testMetadataEntityAliasAndFetchPlanPropertyResolveToDomainDeclarations() {
        addJmixEntityAnnotation()
        val entity = myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity(name = "payroll_Employee")
            public class Employee {
                private String employeeNumber;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "fetch-plans.xml",
            """
            <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                <fetchPlan entity="payroll_Emplo<caret>yee"
                           name="employee-number"
                           extends="_base">
                    <property name="employeeNumber"/>
                </fetchPlan>
            </fetchPlans>
            """.trimIndent(),
        )

        val entityReference = referenceAtCaret<JmixEntityClassReference>()
        assertEquals(entity, entityReference.resolve())

        val propertyValue = myFixture.file.text
            .indexOf("employeeNumber")
            .let { offset -> myFixture.file.findElementAt(offset + 2) }
            ?.parent as XmlAttributeValue
        val propertyReference = propertyValue.references
            .filterIsInstance<JmixEntityPropertyReference>()
            .single()
        assertEquals("employeeNumber", (propertyReference.resolve() as PsiField).name)
    }

    fun testUnresolvedEntityPropertyIsHighlightedAndFixed() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private String firstName;
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixDomainXmlReferenceInspection())
        myFixture.configureByText(
            "employee-list-view.xml",
            """
            <view>
                <data>
                    <collection id="employeesDc"
                                class="com.company.payroll.entity.Employee"/>
                </data>
                <layout>
                    <dataGrid dataContainer="employeesDc">
                        <columns>
                            <column property="firstN<caret>mae"/>
                        </columns>
                    </dataGrid>
                </layout>
            </view>
            """.trimIndent(),
        )

        val problem = myFixture.doHighlighting()
            .single { it.description?.contains("Unresolved Jmix entity property") == true }
        assertEquals(HighlightSeverity.ERROR, problem.severity)

        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'firstName'"))
        assertTrue(myFixture.file.text.contains("""property="firstName""""))
    }

    fun testKotlinEntityAndPropertyResolveAndRenameFromFlowUiXml() {
        addJmixEntityAnnotation()
        val kotlinFile = myFixture.addFileToProject(
            "com/company/payroll/entity/Employee.kt",
            """
            package com.company.payroll.entity

            import io.jmix.core.metamodel.annotation.JmixEntity

            @JmixEntity
            class Employee {
                var firstName: String? = null
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "employee-detail-view.xml",
            """
            <view>
                <data>
                    <instance id="employeeDc"
                              class="com.company.payroll.entity.Employee"/>
                </data>
                <layout>
                    <formLayout dataContainer="employeeDc">
                        <textField property="first<caret>Name"/>
                    </formLayout>
                </layout>
            </view>
            """.trimIndent(),
        )

        val classValue = myFixture.file.text
            .indexOf("com.company.payroll.entity.Employee")
            .let { offset -> myFixture.file.findElementAt(offset + 5) }
            ?.parent as XmlAttributeValue
        val classReference = classValue.references
            .filterIsInstance<JmixEntityClassReference>()
            .single()
        assertTrue(classReference.resolve() is PsiClass)

        val propertyReference = referenceAtCaret<JmixEntityPropertyReference>()
        val property = propertyReference.resolve() as PsiNamedElement
        assertEquals("firstName", property.name)

        myFixture.renameElement(property, "legalName")
        assertTrue(kotlinFile.text.contains("var legalName: String?"))
        assertTrue(myFixture.file.text.contains("""property="legalName""""))
    }

    fun testJavaJpaMappedByNavigatesFindsUsagesAndFollowsNativeRename() {
        addJpaOneToManyAnnotation()
        addJmixEntityAnnotation()
        val employee = myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private Department department;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Department.java",
            """
            package com.company.payroll.entity;

            import jakarta.persistence.OneToMany;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            import java.util.List;

            @JmixEntity
            public class Department {
                @OneToMany(mappedBy = "depart<caret>ment")
                private List<Employee> employees;
            }
            """.trimIndent(),
        )

        val reference = codeReferenceAtCaret<JmixJavaMappedByReference>()
        val resolved = reference.resolve()
        assertNotNull(
            "mappedBy candidates=${reference.variants.joinToString()}",
            resolved,
        )
        val field = resolved as PsiField
        assertEquals("department", field.name)

        myFixture.renameElement(field, "organizationalUnit")
        assertTrue(employee.containingFile.text.contains("organizationalUnit"))
        assertTrue(myFixture.file.text.contains("""mappedBy = "organizationalUnit""""))
    }

    fun testKotlinJpaMappedByNavigatesAndFollowsNativeRename() {
        addJpaOneToManyAnnotation()
        val employee = myFixture.addFileToProject(
            "com/company/payroll/entity/Employee.kt",
            """
            package com.company.payroll.entity

            class Employee {
                var department: Department? = null
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Department.kt",
            """
            package com.company.payroll.entity

            import jakarta.persistence.OneToMany

            class Department {
                @OneToMany(mappedBy = "depart<caret>ment")
                var employees: MutableList<Employee> = mutableListOf()
            }
            """.trimIndent(),
        )

        val reference = codeReferenceAtCaret<JmixKotlinMappedByReference>()
        val property = reference.resolve() as PsiNamedElement
        assertEquals("department", property.name)

        myFixture.renameElement(property, "organizationalUnit")
        assertTrue(employee.text.contains("var organizationalUnit: Department?"))
        assertTrue(myFixture.file.text.contains("""mappedBy = "organizationalUnit""""))
    }

    fun testSharedFetchPlanRequiresAnEntityDeclaration() {
        myFixture.enableInspections(JmixDomainXmlReferenceInspection())
        myFixture.configureByText(
            "fetch-plans.xml",
            """
            <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                <fetchPlan name="orphan-plan" extends="_base"/>
            </fetchPlans>
            """.trimIndent(),
        )

        val problems = myFixture.doHighlighting()
        assertTrue(
            problems.any {
                it.description?.contains("must declare its entity") == true
            },
        )
    }

    fun testDuplicateAndScalarNestedFetchPlanPropertiesAreRejected() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private String firstName;
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixDomainXmlReferenceInspection())
        myFixture.configureByText(
            "fetch-plans.xml",
            """
            <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                <fetchPlan class="com.company.payroll.entity.Employee"
                           name="invalid-plan">
                    <property name="firstName" fetchPlan="_base"/>
                    <property name="firstName"/>
                </fetchPlan>
            </fetchPlans>
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting()
            .mapNotNull { it.description }
        assertTrue(descriptions.any { "declared more than once" in it })
        assertTrue(descriptions.any { "is not an entity reference" in it })
    }

    fun testAmbiguousEntityMetadataNameFailsClosed() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.sales.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Order {
                private String number;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.procurement.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Order {
                private String number;
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixDomainXmlReferenceInspection())
        myFixture.configureByText(
            "fetch-plans.xml",
            """
            <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                <fetchPlan entity="Order" name="order-summary"/>
            </fetchPlans>
            """.trimIndent(),
        )

        val problems = myFixture.doHighlighting()
        assertTrue(
            problems.any {
                it.description?.contains("Ambiguous Jmix entity 'Order'") == true
            },
        )
    }

    fun testDuplicateNamedFetchPlansFailClosedAtUsage() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private String firstName;
            }
            """.trimIndent(),
        )
        repeat(2) { index ->
            myFixture.addFileToProject(
                "module$index/com/company/payroll/fetch-plans.xml",
                """
                <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                    <fetchPlan class="com.company.payroll.entity.Employee"
                               name="employee-summary"/>
                </fetchPlans>
                """.trimIndent(),
            )
        }
        myFixture.enableInspections(JmixDomainXmlReferenceInspection())
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

        val problems = myFixture.doHighlighting()
        assertTrue(
            problems.any {
                it.description
                    ?.contains("Ambiguous Jmix fetch plan 'employee-summary'") == true
            },
        )
    }

    fun testDuplicateNamedFetchPlansAreReportedAtDeclaration() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
                private String firstName;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "module-a/com/company/payroll/fetch-plans.xml",
            """
            <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                <fetchPlan class="com.company.payroll.entity.Employee"
                           name="employee-summary"/>
            </fetchPlans>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixDomainXmlReferenceInspection())
        myFixture.configureByText(
            "fetch-plans.xml",
            """
            <fetchPlans xmlns="http://jmix.io/schema/core/fetch-plans">
                <fetchPlan class="com.company.payroll.entity.Employee"
                           name="employee-summary"/>
            </fetchPlans>
            """.trimIndent(),
        )

        val problems = myFixture.doHighlighting()
        assertTrue(
            problems.any {
                it.description?.contains("declared 2 times for the same entity") == true
            },
        )
    }

    fun testDomainSymbolCacheIgnoresUnrelatedPsiAndInvalidatesOnlyEntityIndex() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Employee {
            }
            """.trimIndent(),
        )
        myFixture.configureByText("empty-view.xml", "<view><layout/></view>")

        val first = jmixEntityClasses(myFixture.file)
        val second = jmixEntityClasses(myFixture.file)
        assertSame(first, second)
        assertEquals(listOf("Employee"), first.mapNotNull(PsiClass::getName))

        myFixture.addFileToProject(
            "com/company/payroll/view/unrelated-view.xml",
            "<view><layout><span text=\"Unrelated edit\"/></layout></view>",
        )
        myFixture.addFileToProject(
            "com/company/payroll/PlainUtility.java",
            """
            package com.company.payroll;
            public class PlainUtility {
                public String label() { return "unrelated"; }
            }
            """.trimIndent(),
        )

        val afterUnrelatedChanges = jmixEntityClasses(myFixture.file)
        assertSame(
            "Unrelated XML and Java files must not evict the entity inventory",
            first,
            afterUnrelatedChanges,
        )

        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Department {
            }
            """.trimIndent(),
        )

        val refreshed = jmixEntityClasses(myFixture.file)
        assertNotSame(first, refreshed)
        assertEquals(
            listOf("Department", "Employee"),
            refreshed.mapNotNull(PsiClass::getName).sorted(),
        )
    }

    fun testCollectionPropertyContainerResolvesGenericTargetEntityProperties() {
        addJmixEntityAnnotation()
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;

            @JmixEntity
            public class Loan {
                private String loanNumber;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.payroll.entity;

            import io.jmix.core.metamodel.annotation.JmixEntity;
            import java.util.List;

            @JmixEntity
            public class Employee {
                private List<Loan> loans;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "employee-detail-view.xml",
            """
            <view>
                <data>
                    <instance id="employeeDc"
                              class="com.company.payroll.entity.Employee">
                        <fetchPlan extends="_base">
                            <property name="loans" fetchPlan="_base"/>
                        </fetchPlan>
                        <collection id="loansDc" property="loans"/>
                    </instance>
                </data>
                <layout>
                    <dataGrid dataContainer="loansDc">
                        <columns>
                            <column property="loanN<caret>umber"/>
                        </columns>
                    </dataGrid>
                </layout>
            </view>
            """.trimIndent(),
        )

        val loansContainer = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .single { it.getAttributeValue("id") == "loansDc" }
        val employeeContainer = requireNotNull(loansContainer.parentTag)
        val employeeClass = requireNotNull(entityClassForDataContainer(employeeContainer))
        assertEquals("Employee", employeeClass.name)
        val loansField = requireNotNull(employeeClass.findFieldByName("loans", true))
        assertEquals("Loan", entityClassForType(loansField.type)?.name)
        assertEquals("Loan", entityClassForDataContainer(loansContainer)?.name)

        val reference = referenceAtCaret<JmixEntityPropertyReference>()
        val field = reference.resolve() as PsiField
        assertEquals("loanNumber", field.name)
        assertTrue(
            reference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "loanNumber" },
        )

        myFixture.renameElement(field, "accountNumber")
        assertTrue(myFixture.file.text.contains("""property="accountNumber""""))
    }

    private inline fun <reified T : PsiReference> referenceAtCaret(): T {
        val value = attributeValueAtCaret()
        val offsetInValue = myFixture.caretOffset - value.textRange.startOffset
        return value.references.filterIsInstance<T>().singleOrNull { reference ->
            reference.rangeInElement.containsOffset(offsetInValue)
        } ?: error(
            "No ${T::class.java.simpleName} at offset $offsetInValue for ${value.text}; " +
                "references=${value.references.joinToString { reference ->
                    "${reference.javaClass.simpleName}:${reference.rangeInElement}"
                }}",
        )
    }

    private inline fun <reified T : PsiReference> codeReferenceAtCaret(): T {
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset - 1)
            ?: error("No PSI at caret")
        return generateSequence(leaf as com.intellij.psi.PsiElement?) { it.parent }
            .flatMap { element ->
                val offset = myFixture.caretOffset - element.textRange.startOffset
                element.references.asSequence()
                    .filterIsInstance<T>()
                    .filter { it.rangeInElement.containsOffset(offset) }
            }
            .firstOrNull()
            ?: error("No ${T::class.java.simpleName} at caret in ${myFixture.file.name}")
    }

    private fun attributeValueAtCaret(): XmlAttributeValue {
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset - 1)
            ?: error("No PSI at caret")
        return generateSequence(leaf as com.intellij.psi.PsiElement?) { it.parent }
            .filterIsInstance<XmlAttributeValue>()
            .first()
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

    private fun addJpaOneToManyAnnotation() {
        myFixture.addClass(
            """
            package jakarta.persistence;

            public @interface OneToMany {
                String mappedBy() default "";
            }
            """.trimIndent(),
        )
    }
}

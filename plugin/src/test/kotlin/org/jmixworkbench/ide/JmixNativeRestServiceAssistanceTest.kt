package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeRestServiceAssistanceTest :
    LightJavaCodeInsightFixtureTestCase() {

    fun testJavaRestServiceCompletesNavigatesAndRefactorsOverloadedMethod() {
        addSpringServiceAnnotation()
        val serviceFile = myFixture.addFileToProject(
            "com/company/payroll/api/PayrollApi.java",
            """
            package com.company.payroll.api;

            import org.springframework.stereotype.Service;

            @Service("payroll_Api")
            public class PayrollApi {
                public String calculate(int employeeId) {
                    return "numeric";
                }

                public String calculate(String employeeCode) {
                    return "coded";
                }

                public void closePeriod() {
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="payroll_Api">
                    <method name="calcu<caret>late">
                        <param name="employee" type="int"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )

        val methodReference = referenceAtCaret<JmixRestServiceMethodReference>()
        val method = methodReference.resolve() as PsiMethod
        assertEquals("int", method.parameterList.parameters.single().type.canonicalText)
        val methodVariants = methodReference.variants
            .filterIsInstance<LookupElement>()
        assertEquals(
            2,
            methodVariants.count { it.lookupString == "calculate" },
        )
        assertTrue(methodVariants.any { it.lookupString == "closePeriod" })
        assertEquals(1, ReferencesSearch.search(method).findAll().size)

        myFixture.renameElement(method, "calculatePayroll")
        assertTrue(serviceFile.text.contains("calculatePayroll(int employeeId)"))
        assertTrue(myFixture.file.text.contains("""name="calculatePayroll""""))

        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="payroll_Api">
                    <method name="calculatePayroll">
                        <param name="employeeI<caret>d" type="int"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )
        val parameterReference =
            referenceAtCaret<JmixRestServiceParameterReference>()
        val parameter = parameterReference.resolve() as PsiParameter
        assertEquals("employeeId", parameter.name)
        assertEquals(1, ReferencesSearch.search(parameter).findAll().size)

        myFixture.renameElement(parameter, "workerId")
        assertTrue(serviceFile.text.contains("calculatePayroll(int workerId)"))
        assertTrue(myFixture.file.text.contains("""name="workerId""""))
    }

    fun testRestServiceBeanAndParameterTypeTrackNativeRenames() {
        addSpringServiceAnnotation()
        val requestClass = myFixture.addClass(
            """
            package com.company.payroll.dto;
            public class LoanRequest {
            }
            """.trimIndent(),
        )
        val serviceFile = myFixture.addFileToProject(
            "com/company/payroll/api/LoanApi.java",
            """
            package com.company.payroll.api;

            import com.company.payroll.dto.LoanRequest;
            import org.springframework.stereotype.Service;

            @Service("loan_Api")
            public class LoanApi {
                public String submit(LoanRequest request) {
                    return "accepted";
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="loan_A<caret>pi">
                    <method name="submit">
                        <param name="request"
                               type="com.company.payroll.dto.LoanRequest"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )

        val beanReference = referenceAtCaret<JmixRestServiceBeanReference>()
        val bean = beanReference.resolve() as JmixSpringBeanElement
        assertEquals("loan_Api", bean.name)
        assertTrue(
            beanReference.variants.filterIsInstance<LookupElement>()
                .any { it.lookupString == "loan_Api" },
        )
        assertEquals(2, ReferencesSearch.search(bean).findAll().size)

        myFixture.renameElement(bean, "loan_Operations")
        assertTrue(serviceFile.text.contains("""@Service("loan_Operations")"""))
        assertTrue(myFixture.file.text.contains("""name="loan_Operations""""))

        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="loan_Operations">
                    <method name="submit">
                        <param name="request"
                               type="com.company.payroll.dto.LoanReq<caret>uest"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )
        val typeReference =
            referenceAtCaret<JmixRestServiceParameterTypeReference>()
        assertTrue(typeReference.resolve() is PsiClass)

        myFixture.renameElement(requestClass, "LoanCommand")
        assertTrue(serviceFile.text.contains("LoanCommand request"))
        assertTrue(
            myFixture.file.text.contains(
                """type="com.company.payroll.dto.LoanCommand"""",
            ),
        )
    }

    fun testRestServiceAnnotationAndKotlinMethodParticipateInNativeModel() {
        addJmixRestServiceAnnotation()
        val kotlinFile = myFixture.addFileToProject(
            "com/company/payroll/api/SettlementApi.kt",
            """
            package com.company.payroll.api

            import io.jmix.rest.annotation.RestService

            @RestService("payroll_Settlement")
            class SettlementApi {
                fun settle(applicationId: String): String {
                    return applicationId
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="payroll_Settlement">
                    <method name="set<caret>tle">
                        <param name="applicationId" type="java.lang.String"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )

        val methodReference = referenceAtCaret<JmixRestServiceMethodReference>()
        val method = methodReference.resolve()
        assertEquals("settle", (method as com.intellij.psi.PsiNamedElement).name)
        assertEquals(1, ReferencesSearch.search(method).findAll().size)

        myFixture.renameElement(method, "settleEarly")
        assertTrue(kotlinFile.text.contains("fun settleEarly("))
        assertTrue(myFixture.file.text.contains("""name="settleEarly""""))
    }

    fun testKotlinBeanFactoryProductParticipatesInRestNavigationAndRename() {
        addSpringBeanAnnotation()
        val kotlinFile = myFixture.addFileToProject(
            "com/company/payroll/api/PayrollApiConfiguration.kt",
            """
            package com.company.payroll.api

            import org.springframework.context.annotation.Bean

            class PayrollApi {
                fun calculate(employeeId: String): String {
                    return employeeId
                }
            }

            class PayrollApiConfiguration {
                @Bean
                fun payrollApi(): PayrollApi {
                    return PayrollApi()
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="payroll<caret>Api">
                    <method name="calculate">
                        <param name="employeeId" type="java.lang.String"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )

        val beanReference = referenceAtCaret<JmixRestServiceBeanReference>()
        val bean = beanReference.resolve() as JmixSpringBeanElement
        assertEquals("payrollApi", bean.name)
        assertEquals("payrollApi", bean.declaration.classElement.name)

        val methodValue = PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            XmlAttributeValue::class.java,
        ).single { it.value == "calculate" }
        val methodReference = methodValue.references
            .filterIsInstance<JmixRestServiceMethodReference>()
            .single()
        assertEquals(
            "calculate",
            (methodReference.resolve() as com.intellij.psi.PsiNamedElement).name,
        )

        myFixture.renameElement(bean, "settlementApi")
        assertTrue(kotlinFile.text.contains("fun settlementApi(): PayrollApi"))
        assertTrue(myFixture.file.text.contains("""name="settlementApi""""))

        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="settlementApi">
                    <method name="calculate">
                        <param name="employee<caret>Id"
                               type="java.lang.String"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )
        val parameter =
            referenceAtCaret<JmixRestServiceParameterReference>().resolve()
                as com.intellij.psi.PsiNamedElement
        myFixture.renameElement(parameter, "workerId")
        assertTrue(kotlinFile.text.contains("fun calculate(workerId: String)"))
        assertTrue(myFixture.file.text.contains("""name="workerId""""))
    }

    fun testKotlinJvmParameterTypesDisambiguateNullableCollectionsAndVarargs() {
        addJmixRestServiceAnnotation()
        myFixture.addFileToProject(
            "com/company/payroll/api/TypedApi.kt",
            """
            package com.company.payroll.api

            import io.jmix.rest.annotation.RestService

            @RestService("payroll_TypedApi")
            class TypedApi {
                fun choose(value: Int): String = "primitive"
                fun choose(value: Int?): String = "nullable"
                fun accept(values: List<String>): String = "collection"
                fun distribute(vararg ids: Long): String = "vararg"
            }
            """.trimIndent(),
        )

        assertKotlinRestParameterType(
            method = "choose",
            configuredType = "int",
            expectedCanonicalType = "int",
        )
        assertKotlinRestParameterType(
            method = "choose",
            configuredType = "java.lang.Integer",
            expectedCanonicalType = "java.lang.Integer",
        )
        assertKotlinRestParameterType(
            method = "accept",
            configuredType = "java.util.List",
            expectedCanonicalType = "java.util.List",
        )
        assertKotlinRestParameterType(
            method = "distribute",
            configuredType = "long[]",
            expectedCanonicalType = "long[]",
        )
    }

    fun testExplicitKotlinBeanFactoryNameIsDiscovered() {
        addSpringBeanAnnotation()
        myFixture.addFileToProject(
            "com/company/payroll/api/ExplicitApiConfiguration.kt",
            """
            package com.company.payroll.api

            import org.springframework.context.annotation.Bean

            class ExplicitApi {
                fun execute() {
                }
            }

            class ExplicitApiConfiguration {
                @Bean(name = ["payroll_ExplicitApi"])
                fun explicitApi(): ExplicitApi = ExplicitApi()
            }
            """.trimIndent(),
        )
        val beanNames = JmixSpringBeanSymbolService.getInstance(project)
            .beans()
            .map { it.name }
        assertContainsElements(beanNames, "payroll_ExplicitApi")
        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="payroll_ExplicitApi">
                    <method name="exe<caret>cute"/>
                </service>
            </services>
            """.trimIndent(),
        )

        assertEquals(
            "execute",
            (referenceAtCaret<JmixRestServiceMethodReference>().resolve()
                as com.intellij.psi.PsiNamedElement).name,
        )
    }

    fun testInspectionRejectsBrokenAmbiguousAndDuplicateRestMappings() {
        addSpringServiceAnnotation()
        myFixture.addFileToProject(
            "com/company/payroll/api/BrokenApi.java",
            """
            package com.company.payroll.api;

            import org.springframework.stereotype.Service;

            @Service("broken_Api")
            public class BrokenApi {
                private void hidden() {
                }

                public String calculate(int employeeId) {
                    return "numeric";
                }

                public String calculate(String employeeCode) {
                    return "coded";
                }

                public void oneArgument(String value) {
                }
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixRestXmlReferenceInspection())
        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="missing_Api">
                    <method name="run"/>
                </service>
                <service name="broken_Api">
                    <method name="missing"/>
                    <method name="hidden"/>
                    <method name="oneArgument"/>
                    <method name="calculate">
                        <param name="employee"/>
                    </method>
                    <method name="calculate">
                        <param name="employee" type="java.time.Instant"/>
                    </method>
                    <method name="calculate">
                        <param name="duplicate" type="int"/>
                        <param name="duplicate" type="int"/>
                    </method>
                </service>
                <service name="broken_Api">
                    <method name="calculate">
                        <param name="employee" type="int"/>
                    </method>
                    <method name="calculate">
                        <param name="employee" type="int"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.ERROR }
            .mapNotNull { it.description }
        assertTrue(descriptions.any { "Unresolved Jmix REST Spring bean" in it })
        assertTrue(descriptions.any { "Unresolved Jmix REST service method" in it })
        assertTrue(descriptions.any { "must be public" in it })
        assertTrue(descriptions.any { "XML parameters" in it })
        assertTrue(descriptions.any { "Ambiguous Jmix REST service method" in it })
        assertTrue(descriptions.any { "do not match any" in it })
        assertTrue(descriptions.any { "Duplicate Jmix REST parameter name" in it })
        assertTrue(descriptions.any { "Duplicate Jmix REST service" in it })
        assertTrue(descriptions.any { "Duplicate Jmix REST method mapping" in it })
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

    private fun assertKotlinRestParameterType(
        method: String,
        configuredType: String,
        expectedCanonicalType: String,
    ) {
        myFixture.configureByText(
            "rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="payroll_TypedApi">
                    <method name="${method.substring(0, 1)}<caret>${method.substring(1)}">
                        <param name="value" type="$configuredType"/>
                    </method>
                </service>
            </services>
            """.trimIndent(),
        )
        val reference = referenceAtCaret<JmixRestServiceMethodReference>()
        val resolved = reference.resolvedMethods().single()
        assertEquals(expectedCanonicalType, resolved.parameters.single().canonicalType)
    }

    private fun addSpringServiceAnnotation() {
        myFixture.addClass(
            """
            package org.springframework.stereotype;
            public @interface Service {
                String value() default "";
            }
            """.trimIndent(),
        )
    }

    private fun addJmixRestServiceAnnotation() {
        myFixture.addClass(
            """
            package io.jmix.rest.annotation;
            public @interface RestService {
                String value();
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

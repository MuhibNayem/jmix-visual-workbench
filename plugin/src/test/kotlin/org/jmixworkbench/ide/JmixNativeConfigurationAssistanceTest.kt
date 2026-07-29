package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.properties.psi.Property
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeConfigurationAssistanceTest :
    LightJavaCodeInsightFixtureTestCase() {

    fun testProfilePropertyCompletesNavigatesAndTracksResourceRename() {
        val services = myFixture.addFileToProject(
            "src/main/resources/rest/rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services"/>
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/rest/rest-queries.xml",
            """
            <queries xmlns="http://jmix.io/schema/rest/queries"/>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "application-production.properties",
            """
            jmix.rest.services-config=classpath:/rest/rest-ser<caret>vices.xml
            """.trimIndent(),
        )

        val reference = referenceAtCaret<JmixConfigurationResourceReference>()
        assertSame(services, reference.resolve())
        val variants = reference.variants.filterIsInstance<LookupElement>()
            .map(LookupElement::getLookupString)
        assertContainsElements(variants, "rest/rest-services.xml")
        assertFalse(variants.contains("rest/rest-queries.xml"))
        assertEquals(1, ReferencesSearch.search(services).findAll().size)

        myFixture.renameElement(services, "payroll-services.xml")
        assertTrue(
            myFixture.file.text.contains(
                "classpath:/rest/payroll-services.xml",
            ),
        )
    }

    fun testMultipleConfigurationResourcesHaveIndependentRangesAndKinds() {
        val first = myFixture.addFileToProject(
            "module-a/src/main/resources/rest/base-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services"/>
            """.trimIndent(),
        )
        val second = myFixture.addFileToProject(
            "module-b/src/main/resources/rest/payroll-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services"/>
            """.trimIndent(),
        )
        myFixture.configureByText(
            "application.properties",
            """
            jmix.rest.services-config=classpath:/rest/base-services.xml, rest/pay<caret>roll-services.xml
            """.trimIndent(),
        )

        propertyAtCaret()
        val references = valueElementAtCaret().references
            .filterIsInstance<JmixConfigurationResourceReference>()
        assertEquals(2, references.size)
        assertSame(first, references[0].resolve())
        assertSame(second, references[1].resolve())
        assertSame(second, referenceAtCaret<JmixConfigurationResourceReference>().resolve())
    }

    fun testInspectionRejectsWrongKindMissingDuplicateAndAmbiguousResources() {
        myFixture.addFileToProject(
            "module-a/src/main/resources/rest/rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services"/>
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "module-b/src/main/resources/rest/rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services"/>
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/rest/rest-queries.xml",
            """
            <queries xmlns="http://jmix.io/schema/rest/queries"/>
            """.trimIndent(),
        )
        myFixture.enableInspections(JmixConfigurationReferenceInspection())
        myFixture.configureByText(
            "application-test.properties",
            """
            jmix.rest.services-config=rest/rest-services.xml
            jmix.rest.servicesConfig=rest/rest-queries.xml
            jmix.rest.queries-config=rest/missing-queries.xml, rest/missing-queries.xml
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.ERROR }
            .mapNotNull { it.description }
        assertTrue(descriptions.any { "Ambiguous Jmix REST configuration" in it })
        assertTrue(descriptions.any { "requires a REST services descriptor" in it })
        assertEquals(
            2,
            descriptions.count {
                "Unresolved Jmix REST configuration resource 'rest/missing-queries.xml'" in it
            },
        )
        assertEquals(
            2,
            descriptions.count {
                "Duplicate Jmix REST configuration resource 'rest/missing-queries.xml'" in it
            },
        )
    }

    fun testExternalAndPlaceholderConfigurationRemainRuntimeOwned() {
        myFixture.enableInspections(JmixConfigurationReferenceInspection())
        myFixture.configureByText(
            "application-cloud.properties",
            """
            jmix.rest.services-config=file:/etc/payroll/rest-services.xml
            jmix.rest.queries-config=${'$'}{PAYROLL_REST_QUERIES}
            """.trimIndent(),
        )

        val properties = PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            Property::class.java,
        )
        assertTrue(
            properties.all {
                it.children
                    .filter { child ->
                        child.javaClass.simpleName == "PropertyValueImpl"
                    }
                    .all { value ->
                        value.references.none { reference ->
                            reference is JmixConfigurationResourceReference
                        }
                    }
            },
        )
        assertTrue(myFixture.doHighlighting().none {
            it.description?.contains("Jmix REST configuration") == true
        })
    }

    fun testRestDescriptorInventoryInvalidatesOnlyForIndexedDescriptors() {
        myFixture.addFileToProject(
            "src/main/resources/rest/rest-services.xml",
            """
            <services xmlns="http://jmix.io/schema/rest/services"/>
            """.trimIndent(),
        )
        val service = JmixRestConfigurationSymbolService.getInstance(project)
        val initial = service.descriptors()
        assertEquals(1, initial.size)

        myFixture.addFileToProject(
            "src/main/resources/unrelated.xml",
            """
            <unrelated/>
            """.trimIndent(),
        )
        assertSame(initial, service.descriptors())

        myFixture.addFileToProject(
            "src/main/resources/rest/rest-queries.xml",
            """
            <queries xmlns="http://jmix.io/schema/rest/queries"/>
            """.trimIndent(),
        )
        val updated = service.descriptors()
        assertNotSame(initial, updated)
        assertEquals(
            setOf(
                JmixRestDescriptorKind.SERVICES,
                JmixRestDescriptorKind.QUERIES,
            ),
            updated.map { it.kind }.toSet(),
        )
    }

    private fun propertyAtCaret(): Property =
        PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
            Property::class.java,
            false,
        ) ?: error("No property at caret")

    private inline fun <reified T : PsiReference> referenceAtCaret(): T {
        val valueElement = valueElementAtCaret()
        val offset = myFixture.caretOffset - valueElement.textRange.startOffset
        return valueElement.references.filterIsInstance<T>().singleOrNull {
            it.rangeInElement.containsOffset(offset)
        } ?: error(
            "No ${T::class.java.simpleName} at $offset; " +
                "references=${valueElement.references.joinToString { it.javaClass.simpleName }}",
        )
    }

    private fun valueElementAtCaret() =
        generateSequence(
            myFixture.file.findElementAt(myFixture.caretOffset - 1),
        ) { it.parent }
            .firstOrNull { it.javaClass.simpleName == "PropertyValueImpl" }
            ?: error("No property value at caret")
}

package org.jmixworkbench.discovery.flowui

import org.jmixworkbench.discovery.change.WorkspaceChangePlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowUiDescriptorParserTest {

    @Test
    fun `indexes exact FlowUI element attribute and hierarchy ranges`() {
        val source = descriptor()
        val result = FlowUiDescriptorParser.parse("loan/src/main/resources/loan-detail-view.xml", source)

        assertTrue(result.accepted)
        val document = assertNotNull(result.document)
        assertEquals("Loan.detail", document.viewId)
        val form = document.elements.single { it.id == "loanForm" }
        val amount = document.elements.single { it.id == "amountField" }
        assertEquals(form.key, amount.parentKey)
        assertTrue(amount.key in form.childKeys)
        assertEquals("loanDc", amount.attributes.single { it.name == "dataContainer" }.value)
        assertEquals("loanAmount", amount.attributes.single { it.name == "property" }.value)
        assertTrue(source.substring(amount.sourceStart, amount.sourceEnd).startsWith("<bigDecimalField"))
        assertEquals("select e from LoanApp e", document.elements.single { it.localTag == "query" }.directText)
    }

    @Test
    fun `property proposal changes only the exact attribute value and remains revision bound`() {
        val source = descriptor()
        val document = assertNotNull(
            FlowUiDescriptorParser.parse("loan/src/main/resources/loan-detail-view.xml", source).document,
        )
        val amount = document.elements.single { it.id == "amountField" }
        val proposal = FlowUiDescriptorParser.proposePropertyChange(
            document,
            amount.key,
            "width",
            "100% & flexible",
        )

        assertTrue(proposal.accepted)
        val plan = WorkspaceChangePlanner.plan(
            assertNotNull(proposal.changeSet),
            mapOf(document.relativePath to source),
        )
        assertTrue(plan.accepted)
        val changed = plan.files.single().resultContent
        assertTrue("width=\"100% &amp; flexible\"" in changed)
        assertTrue("property=\"loanAmount\"" in changed)
        assertTrue("<!-- manual layout comment -->" in changed)

        val reparsed = FlowUiDescriptorParser.parse(document.relativePath, changed)
        assertTrue(reparsed.accepted)
        val updated = assertNotNull(reparsed.document).elements.single { it.id == "amountField" }
        assertEquals("100% & flexible", updated.attributes.single { it.name == "width" }.value)
    }

    @Test
    fun `existing property replacement preserves quote and surrounding source`() {
        val source = descriptor()
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val amount = document.elements.single { it.id == "amountField" }
        val proposal = FlowUiDescriptorParser.proposePropertyChange(document, amount.key, "property", "principal")
        val plan = WorkspaceChangePlanner.plan(assertNotNull(proposal.changeSet), mapOf("view.xml" to source))

        val changed = plan.files.single().resultContent
        assertEquals(source.length - "loanAmount".length + "principal".length, changed.length)
        assertTrue("property=\"principal\"" in changed)
        assertFalse("property=\"loanAmount\"" in changed)
    }

    private fun descriptor(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <view xmlns="http://jmix.io/schema/flowui/view" id="Loan.detail">
          <data>
            <instance id="loanDc" class="com.acme.LoanApp">
              <loader id="loanDl">
                <query><![CDATA[select e from LoanApp e]]></query>
              </loader>
            </instance>
          </data>
          <layout>
            <!-- manual layout comment -->
            <formLayout id="loanForm" dataContainer="loanDc">
              <bigDecimalField id="amountField" dataContainer="loanDc" property="loanAmount"/>
            </formLayout>
          </layout>
        </view>
        """.trimIndent()
}

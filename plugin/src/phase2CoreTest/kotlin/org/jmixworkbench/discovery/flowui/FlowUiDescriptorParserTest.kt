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

    @Test
    fun `component insertion preserves manual XML and produces a parseable child`() {
        val source = descriptor()
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val form = document.elements.single { it.id == "loanForm" }
        val proposal = FlowUiDescriptorParser.proposeInsertChild(
            document = document,
            parentKey = form.key,
            tagName = "datePicker",
            attributes = mapOf(
                "property" to "applicationDate",
                "id" to "applicationDateField",
                "dataContainer" to "loanDc",
            ),
        )
        val changed = WorkspaceChangePlanner.plan(
            assertNotNull(proposal.changeSet),
            mapOf("view.xml" to source),
        ).files.single().resultContent

        assertTrue("<!-- manual layout comment -->" in changed)
        val reparsed = assertNotNull(FlowUiDescriptorParser.parse("view.xml", changed).document)
        val inserted = reparsed.elements.single { it.id == "applicationDateField" }
        assertEquals("datePicker", inserted.localTag)
        assertEquals("loanForm", reparsed.elements.single { it.key == inserted.parentKey }.id)
    }

    @Test
    fun `delete and move proposals modify only selected sibling structure`() {
        val source = descriptor().replace(
            """<bigDecimalField id="amountField" dataContainer="loanDc" property="loanAmount"/>""",
            """
            <bigDecimalField id="amountField" dataContainer="loanDc" property="loanAmount"/>
            <textField id="commentField" dataContainer="loanDc" property="comment"/>
            """.trimIndent(),
        )
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val comment = document.elements.single { it.id == "commentField" }
        val move = FlowUiDescriptorParser.proposeMoveElement(document, comment.key, FlowUiMoveDirection.UP)
        val moved = WorkspaceChangePlanner.plan(
            assertNotNull(move.changeSet),
            mapOf("view.xml" to source),
        ).files.single().resultContent
        assertTrue(moved.indexOf("commentField") < moved.indexOf("amountField"))
        assertTrue("<!-- manual layout comment -->" in moved)

        val movedDocument = assertNotNull(FlowUiDescriptorParser.parse("view.xml", moved).document)
        val amount = movedDocument.elements.single { it.id == "amountField" }
        val delete = FlowUiDescriptorParser.proposeDeleteElement(movedDocument, amount.key)
        val deleted = WorkspaceChangePlanner.plan(
            assertNotNull(delete.changeSet),
            mapOf("view.xml" to moved),
        ).files.single().resultContent
        assertFalse("amountField" in deleted)
        assertTrue("commentField" in deleted)
        assertTrue(FlowUiDescriptorParser.parse("view.xml", deleted).accepted)
    }

    @Test
    fun `JPQL direct text edit preserves CDATA wrapper whitespace and unrelated XML`() {
        val source = descriptor()
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val query = document.elements.single { it.localTag == "query" }
        assertTrue(query.directTextCdata)
        assertNotNull(query.directTextStart)
        assertNotNull(query.directTextEnd)

        val proposal = FlowUiDescriptorParser.proposeDirectTextChange(
            document,
            query.key,
            "select e from LoanApp e where e.processState = :state",
        )
        val changed = WorkspaceChangePlanner.plan(
            assertNotNull(proposal.changeSet),
            mapOf("view.xml" to source),
        ).files.single().resultContent

        assertTrue("<![CDATA[select e from LoanApp e where e.processState = :state]]>" in changed)
        assertTrue("<!-- manual layout comment -->" in changed)
        assertEquals(
            "select e from LoanApp e where e.processState = :state",
            assertNotNull(FlowUiDescriptorParser.parse("view.xml", changed).document)
                .elements.single { it.localTag == "query" }.directText,
        )
    }

    @Test
    fun `plain direct text edit escapes markup and remains parseable`() {
        val source = descriptor().replace(
            "<![CDATA[select e from LoanApp e]]>",
            "select e from LoanApp e",
        )
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val query = document.elements.single { it.localTag == "query" }
        val proposal = FlowUiDescriptorParser.proposeDirectTextChange(
            document,
            query.key,
            "select e from LoanApp e where e.amount < :maximum",
        )
        val changed = WorkspaceChangePlanner.plan(
            assertNotNull(proposal.changeSet),
            mapOf("view.xml" to source),
        ).files.single().resultContent

        assertTrue("e.amount &lt; :maximum" in changed)
        assertEquals(
            "select e from LoanApp e where e.amount < :maximum",
            assertNotNull(FlowUiDescriptorParser.parse("view.xml", changed).document)
                .elements.single { it.localTag == "query" }.directText,
        )
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

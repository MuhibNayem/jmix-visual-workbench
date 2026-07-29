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

    @Test
    fun `child-capable catalog insertion remains editable and supports nested components`() {
        val source = descriptor()
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val layout = document.elements.single { it.localTag == "layout" }
        val containerProposal = FlowUiDescriptorParser.proposeInsertChild(
            document = document,
            parentKey = layout.key,
            tagName = "vbox",
            attributes = mapOf("id" to "paymentSection", "width" to "100%"),
            childCapable = true,
        )
        val withContainer = WorkspaceChangePlanner.plan(
            assertNotNull(containerProposal.changeSet),
            mapOf("view.xml" to source),
        ).files.single().resultContent
        val containerDocument = assertNotNull(FlowUiDescriptorParser.parse("view.xml", withContainer).document)
        val container = containerDocument.elements.single { it.id == "paymentSection" }

        assertFalse(container.selfClosing)
        val fieldProposal = FlowUiDescriptorParser.proposeInsertChild(
            document = containerDocument,
            parentKey = container.key,
            tagName = "bigDecimalField",
            attributes = mapOf("id" to "paymentAmountField"),
        )
        val withField = WorkspaceChangePlanner.plan(
            assertNotNull(fieldProposal.changeSet),
            mapOf("view.xml" to withContainer),
        ).files.single().resultContent
        val reparsed = assertNotNull(FlowUiDescriptorParser.parse("view.xml", withField).document)
        val field = reparsed.elements.single { it.id == "paymentAmountField" }
        assertEquals("paymentSection", reparsed.elements.single { it.key == field.parentKey }.id)
        assertTrue("<!-- manual layout comment -->" in withField)
    }

    @Test
    fun `drag placement reparents and reorders exact subtrees without losing manual source`() {
        val source = descriptor().replace(
            """<bigDecimalField id="amountField" dataContainer="loanDc" property="loanAmount"/>""",
            """
            <bigDecimalField id="amountField" dataContainer="loanDc" property="loanAmount"/>
            <textField id="commentField" dataContainer="loanDc" property="comment"/>
            """.trimIndent(),
        ).replace(
            "</layout>",
            """
              <hbox id="actionsBox">
                <button id="saveButton" text="Save"/>
              </hbox>
            </layout>
            """.trimIndent(),
        )
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val comment = document.elements.single { it.id == "commentField" }
        val actions = document.elements.single { it.id == "actionsBox" }
        val save = document.elements.single { it.id == "saveButton" }
        val proposal = FlowUiDescriptorParser.proposeReparentElement(
            document = document,
            elementKey = comment.key,
            newParentKey = actions.key,
            beforeElementKey = save.key,
        )
        val moved = WorkspaceChangePlanner.plan(
            assertNotNull(proposal.changeSet),
            mapOf("view.xml" to source),
        )

        assertTrue(moved.accepted, moved.issues.joinToString { it.message })
        assertEquals(2, moved.files.single().appliedEditCount)
        val changed = moved.files.single().resultContent
        assertTrue("<!-- manual layout comment -->" in changed)
        assertEquals(1, changed.split("commentField").size - 1)
        val reparsed = assertNotNull(FlowUiDescriptorParser.parse("view.xml", changed).document)
        val movedComment = reparsed.elements.single { it.id == "commentField" }
        val movedParent = reparsed.elements.single { it.key == movedComment.parentKey }
        assertEquals("actionsBox", movedParent.id)
        assertTrue(movedParent.childKeys.indexOf(movedComment.key) < movedParent.childKeys.indexOf(
            reparsed.elements.single { it.id == "saveButton" }.key,
        ))

        val cycle = FlowUiDescriptorParser.proposeReparentElement(
            document = reparsed,
            elementKey = movedParent.key,
            newParentKey = movedComment.key,
        )
        assertFalse(cycle.accepted)
        assertTrue(cycle.issues.any { it.code == "JVW-FLOWUI-CYCLIC-MOVE" })
    }

    @Test
    fun `copy subtree creates unique ids and rewrites internal component references`() {
        val source = descriptor().replace(
            "</layout>",
            """
              <hbox id="actionsBox" width="100%">
                <button id="saveButton" action="actionsBox.save"/>
              </hbox>
            </layout>
            """.trimIndent(),
        )
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val actions = document.elements.single { it.id == "actionsBox" }
        val layout = document.elements.single { it.localTag == "layout" }
        val proposal = FlowUiDescriptorParser.proposeCopyElement(
            document = document,
            elementKey = actions.key,
            newParentKey = layout.key,
        )
        val plan = WorkspaceChangePlanner.plan(
            assertNotNull(proposal.changeSet),
            mapOf("view.xml" to source),
        )

        assertTrue(plan.accepted, plan.issues.joinToString { it.message })
        val changed = plan.files.single().resultContent
        val reparsed = assertNotNull(FlowUiDescriptorParser.parse("view.xml", changed).document)
        val copiedActions = reparsed.elements.single { it.id == "actionsBoxCopy" }
        val copiedButton = reparsed.elements.single { it.id == "saveButtonCopy" }
        assertEquals(copiedActions.key, copiedButton.parentKey)
        assertEquals(
            "actionsBoxCopy.save",
            copiedButton.attributes.single { it.name == "action" }.value,
        )
        assertEquals(1, reparsed.elements.count { it.id == "actionsBox" })
        assertEquals(1, reparsed.elements.count { it.id == "saveButton" })
        assertTrue("<!-- manual layout comment -->" in changed)
    }

    @Test
    fun `wrap preserves selected subtree and adds an editable responsive parent`() {
        val source = descriptor()
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val amount = document.elements.single { it.id == "amountField" }
        val proposal = FlowUiDescriptorParser.proposeWrapElement(
            document = document,
            elementKey = amount.key,
            tagName = "flexLayout",
            attributes = mapOf(
                "id" to "amountRow",
                "width" to "100%",
                "flexWrap" to "WRAP",
            ),
        )
        val changed = WorkspaceChangePlanner.plan(
            assertNotNull(proposal.changeSet),
            mapOf("view.xml" to source),
        ).files.single().resultContent

        val reparsed = assertNotNull(FlowUiDescriptorParser.parse("view.xml", changed).document)
        val wrapper = reparsed.elements.single { it.id == "amountRow" }
        val wrappedAmount = reparsed.elements.single { it.id == "amountField" }
        assertEquals("flexLayout", wrapper.localTag)
        assertEquals(wrapper.key, wrappedAmount.parentKey)
        assertEquals("WRAP", wrapper.attributes.single { it.name == "flexWrap" }.value)
        assertEquals(1, reparsed.elements.count { it.id == "amountField" })
        assertTrue("<!-- manual layout comment -->" in changed)
    }

    @Test
    fun `layout conversion preserves namespace and children while removing incompatible properties`() {
        val source =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ui:view xmlns:ui="http://jmix.io/schema/flowui/view" id="Namespaced.view">
              <ui:layout>
                <ui:hbox id="editorRow" width="100%" wrap="true" spacing="true">
                  <ui:textField id="nameField"/>
                </ui:hbox>
              </ui:layout>
            </ui:view>
            """.trimIndent()
        val document = assertNotNull(FlowUiDescriptorParser.parse("view.xml", source).document)
        val row = document.elements.single { it.id == "editorRow" }
        val proposal = FlowUiDescriptorParser.proposeConvertLayout(
            document = document,
            elementKey = row.key,
            tagName = "formLayout",
        )
        val plan = WorkspaceChangePlanner.plan(
            assertNotNull(proposal.changeSet),
            mapOf("view.xml" to source),
        )

        assertTrue(plan.accepted, plan.issues.joinToString { it.message })
        val changed = plan.files.single().resultContent
        assertTrue("<ui:formLayout id=\"editorRow\" width=\"100%\">" in changed)
        assertTrue("</ui:formLayout>" in changed)
        assertFalse("wrap=" in changed)
        assertFalse("spacing=" in changed)
        val reparsed = assertNotNull(FlowUiDescriptorParser.parse("view.xml", changed).document)
        val converted = reparsed.elements.single { it.id == "editorRow" }
        val field = reparsed.elements.single { it.id == "nameField" }
        assertEquals("formLayout", converted.localTag)
        assertEquals(converted.key, field.parentKey)
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

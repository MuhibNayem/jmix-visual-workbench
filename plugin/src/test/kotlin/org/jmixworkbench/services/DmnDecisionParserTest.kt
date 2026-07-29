package org.jmixworkbench.services

import org.jmixworkbench.generator.DmnDecisionGenerator
import org.jmixworkbench.model.DmnConditionModel
import org.jmixworkbench.model.DmnConditionOperator
import org.jmixworkbench.model.DmnDecisionModel
import org.jmixworkbench.model.DmnDecisionRuleModel
import org.jmixworkbench.model.DmnInputModel
import org.jmixworkbench.model.DmnOutputModel
import org.jmixworkbench.model.DmnValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmnDecisionParserTest {
    @Test
    fun `round trips the generated typed decision subset`() {
        val source = DmnDecisionGenerator.generate(model(), "owned")

        val parsed = DmnDecisionParser.parse(source, "module-resources", "eligibility.dmn")

        val result = assertNotNull(parsed.model)
        assertTrue(parsed.unsupported.isEmpty(), parsed.unsupported.joinToString())
        assertEquals("eligibility", result.key)
        assertEquals("amount", result.inputs.single().variable)
        assertEquals(DmnConditionOperator.LESS_THAN_OR_EQUAL, result.rules.single().inputEntries.getValue("amount").operator)
        assertEquals("APPROVE", result.rules.single().outputEntries.getValue("decision"))
        assertEquals(listOf("REJECT", "APPROVE"), result.outputs.single().predefinedValues)
    }

    @Test
    fun `reports arbitrary input expressions instead of interpreting them`() {
        val source = DmnDecisionGenerator.generate(model(), "owned")
            .replace("<![CDATA[amount]]>", "<![CDATA[loan.amount + systemService.secret()]]>")

        val parsed = DmnDecisionParser.parse(source, "module-resources", "eligibility.dmn")

        assertNotNull(parsed.model)
        assertTrue(parsed.unsupported.any { "safe variable subset" in it })
    }

    @Test
    fun `rejects XML documents with doctypes`() {
        val source = """
            <?xml version="1.0"?>
            <!DOCTYPE definitions [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <definitions xmlns="http://www.omg.org/spec/DMN/20151101">&secret;</definitions>
        """.trimIndent()

        val parsed = DmnDecisionParser.parse(source, "module-resources", "eligibility.dmn")

        assertNull(parsed.model)
        assertTrue(parsed.unsupported.isNotEmpty())
    }

    private fun model() = DmnDecisionModel(
        name = "Eligibility",
        key = "eligibility",
        destinationId = "module-resources",
        fileName = "eligibility.dmn",
        inputs = listOf(DmnInputModel("amount", "Amount", "amount", DmnValueType.NUMBER)),
        outputs = listOf(
            DmnOutputModel(
                "decision",
                "Decision",
                "decision",
                DmnValueType.STRING,
                predefinedValues = listOf("REJECT", "APPROVE"),
            ),
        ),
        rules = listOf(
            DmnDecisionRuleModel(
                id = "approve",
                inputEntries = mapOf(
                    "amount" to DmnConditionModel(DmnConditionOperator.LESS_THAN_OR_EQUAL, "100"),
                ),
                outputEntries = mapOf("decision" to "APPROVE"),
            ),
        ),
    )
}

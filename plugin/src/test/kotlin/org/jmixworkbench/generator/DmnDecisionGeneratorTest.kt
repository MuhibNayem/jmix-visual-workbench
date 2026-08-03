package org.jmixworkbench.generator

import org.jmixworkbench.model.DmnCollectOperator
import org.jmixworkbench.model.DmnConditionModel
import org.jmixworkbench.model.DmnConditionOperator
import org.jmixworkbench.model.DmnDecisionModel
import org.jmixworkbench.model.DmnDecisionRuleModel
import org.jmixworkbench.model.DmnHitPolicy
import org.jmixworkbench.model.DmnInputModel
import org.jmixworkbench.model.DmnOutputModel
import org.jmixworkbench.model.DmnValueType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmnDecisionGeneratorTest {
    @Test
    fun `generates deterministic Jmix Flowable DMN without arbitrary expressions`() {
        val model = eligibility()
        val validation = DmnDecisionGenerator.validate(model)

        assertTrue(validation.valid, validation.diagnostics.joinToString())
        val first = DmnDecisionGenerator.generate(model, "encoded-owned-model")
        val second = DmnDecisionGenerator.generate(model, "encoded-owned-model")

        assertEquals(first, second)
        assertContains(first, "<!-- JVW-DMN-MODEL: encoded-owned-model -->")
        assertContains(first, """<decision id="loan-eligibility" name="Loan eligibility">""")
        assertContains(first, """<decisionTable id="loan-eligibilityTable" hitPolicy="UNIQUE">""")
        assertContains(first, "<![CDATA[>=18]]>")
        assertContains(first, "<![CDATA[<18]]>")
        assertContains(first, "<![CDATA[\"APPROVE\"]]>")
        assertFalse(first.contains("script"))
        assertFalse(first.contains("\${"))
    }

    @Test
    fun `detects unique overlap and any output conflicts before deployment`() {
        val overlapping = eligibility().copy(
            rules = listOf(
                rule("broad", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "APPROVE"),
                rule("narrow", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "21", "REVIEW"),
            ),
        )
        val unique = DmnDecisionGenerator.validate(overlapping)
        assertFalse(unique.valid)
        assertTrue(unique.diagnostics.any {
            it.code == "DMN_UNIQUE_OVERLAP" && it.ruleIds == listOf("broad", "narrow")
        })

        val any = DmnDecisionGenerator.validate(overlapping.copy(hitPolicy = DmnHitPolicy.ANY))
        assertFalse(any.valid)
        assertTrue(any.diagnostics.any { it.code == "DMN_ANY_OUTPUT_CONFLICT" })
    }

    @Test
    fun `priority simulation follows predefined output order rather than row order`() {
        val model = eligibility().copy(
            hitPolicy = DmnHitPolicy.PRIORITY,
            outputs = listOf(
                DmnOutputModel(
                    id = "decision",
                    label = "Decision",
                    variable = "decision",
                    type = DmnValueType.STRING,
                    predefinedValues = listOf("REJECT", "REVIEW", "APPROVE"),
                ),
            ),
            rules = listOf(
                rule("approve", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "APPROVE"),
                rule("review", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "REVIEW"),
            ),
        )

        val simulation = DmnDecisionGenerator.simulate(model, mapOf("age" to "30"))

        assertTrue(simulation.accepted, simulation.diagnostics.joinToString())
        assertEquals(listOf("review"), simulation.matchedRuleIds)
        assertEquals(listOf(mapOf("decision" to "REVIEW")), simulation.results)
    }

    @Test
    fun `collect sum simulation aggregates matching numeric outputs`() {
        val model = eligibility().copy(
            hitPolicy = DmnHitPolicy.COLLECT,
            collectOperator = DmnCollectOperator.SUM,
            outputs = listOf(
                DmnOutputModel("score", "Risk score", "score", DmnValueType.NUMBER),
            ),
            rules = listOf(
                rule("base", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "10", "score"),
                rule("adult", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "5", "score"),
            ),
        )

        val simulation = DmnDecisionGenerator.simulate(model, mapOf("age" to "30"))

        assertTrue(simulation.accepted, simulation.diagnostics.joinToString())
        assertEquals(listOf("base", "adult"), simulation.matchedRuleIds)
        assertEquals(listOf(mapOf("score" to "15")), simulation.results)
    }

    @Test
    fun `simulates every non aggregate hit policy with deterministic row and priority ordering`() {
        val overlapping = eligibility().copy(
            outputs = listOf(
                DmnOutputModel(
                    id = "decision",
                    label = "Decision",
                    variable = "decision",
                    type = DmnValueType.STRING,
                    predefinedValues = listOf("REVIEW", "APPROVE"),
                ),
            ),
            rules = listOf(
                rule("approve", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "APPROVE"),
                rule("review", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "REVIEW"),
            ),
        )

        val first = DmnDecisionGenerator.simulate(
            overlapping.copy(hitPolicy = DmnHitPolicy.FIRST),
            mapOf("age" to "30"),
        )
        assertEquals(listOf("approve"), first.matchedRuleIds)

        val priorityOrdered = DmnDecisionGenerator.simulate(
            overlapping.copy(hitPolicy = DmnHitPolicy.OUTPUT_ORDER),
            mapOf("age" to "30"),
        )
        assertEquals(listOf("review", "approve"), priorityOrdered.matchedRuleIds)

        val ruleOrdered = DmnDecisionGenerator.simulate(
            overlapping.copy(hitPolicy = DmnHitPolicy.RULE_ORDER),
            mapOf("age" to "30"),
        )
        assertEquals(listOf("approve", "review"), ruleOrdered.matchedRuleIds)

        val any = DmnDecisionGenerator.simulate(
            overlapping.copy(
                hitPolicy = DmnHitPolicy.ANY,
                rules = overlapping.rules.map { it.copy(outputEntries = mapOf("decision" to "APPROVE")) },
            ),
            mapOf("age" to "30"),
        )
        assertTrue(any.accepted, any.diagnostics.joinToString())
        assertEquals(listOf("approve"), any.matchedRuleIds)

        val unique = DmnDecisionGenerator.simulate(
            eligibility(),
            mapOf("age" to "30"),
        )
        assertTrue(unique.accepted, unique.diagnostics.joinToString())
        assertEquals(listOf("adult"), unique.matchedRuleIds)
    }

    @Test
    fun `collect sum counts duplicate matching output values separately`() {
        val model = eligibility().copy(
            hitPolicy = DmnHitPolicy.COLLECT,
            collectOperator = DmnCollectOperator.SUM,
            outputs = listOf(DmnOutputModel("score", "Score", "score", DmnValueType.NUMBER)),
            rules = listOf(
                rule("first", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "5", "score"),
                rule("second", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "5", "score"),
            ),
        )

        val simulation = DmnDecisionGenerator.simulate(model, mapOf("age" to "30"))

        assertTrue(simulation.accepted, simulation.diagnostics.joinToString())
        assertEquals(listOf(mapOf("score" to "10")), simulation.results)
    }

    private fun eligibility(): DmnDecisionModel =
        DmnDecisionModel(
            name = "Loan eligibility",
            key = "loan-eligibility",
            destinationId = "loan:resources",
            fileName = "loan-eligibility.dmn",
            inputs = listOf(
                DmnInputModel("age", "Applicant age", "age", DmnValueType.NUMBER),
            ),
            outputs = listOf(
                DmnOutputModel("decision", "Decision", "decision", DmnValueType.STRING),
            ),
            rules = listOf(
                rule("adult", DmnConditionOperator.GREATER_THAN_OR_EQUAL, "18", "APPROVE"),
                rule("senior", DmnConditionOperator.LESS_THAN, "18", "REVIEW"),
            ),
        )

    private fun rule(
        id: String,
        operator: DmnConditionOperator,
        value: String,
        output: String,
        outputId: String = "decision",
    ): DmnDecisionRuleModel =
        DmnDecisionRuleModel(
            id = id,
            inputEntries = mapOf("age" to DmnConditionModel(operator, value)),
            outputEntries = mapOf(outputId to output),
        )
}

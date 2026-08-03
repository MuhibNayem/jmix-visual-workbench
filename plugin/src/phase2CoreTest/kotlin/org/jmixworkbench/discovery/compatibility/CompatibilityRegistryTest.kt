package org.jmixworkbench.discovery.compatibility

import org.jmixworkbench.discovery.model.CompatibilityState
import org.jmixworkbench.discovery.model.ImportState
import org.jmixworkbench.discovery.model.ProfileClassification
import org.jmixworkbench.discovery.model.TrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompatibilityRegistryTest {
    private val reviewedFixtures = FixtureEvidenceIndex { fixtureId ->
        fixtureId in setOf(
            "fixture-jmix-2.8-jdk17",
            "fixture-jmix-2.8-jdk21",
            "fixture-jmix-3.0-jdk21",
            "fixture-jmix-3.0-jdk25",
        )
    }

    @Test
    fun `reviewed exact cells authorize only certified read operations`() {
        val registry = loadReviewedRegistry()
        val evaluator = CompatibilityEvaluator(registry)
        val profiles = listOf(
            CompatibilityProfile(ProfileClassification.JMIX_2_8, "2.8.7", 17),
            CompatibilityProfile(ProfileClassification.JMIX_2_8, "2.8.7", 21),
            CompatibilityProfile(ProfileClassification.JMIX_3_0, "3.0.1", 21),
            CompatibilityProfile(ProfileClassification.JMIX_3_0, "3.0.1", 25),
        )

        for (operation in READ_OPERATIONS) {
            for (profile in profiles) {
                val decision = evaluator.evaluate(operation, trustedContext(profile))

                assertEquals(CompatibilityState.CERTIFIED_READ_ONLY, decision.state)
                assertEquals("P2_CERTIFIED_READ_ONLY", decision.reasonCode)
                assertTrue(decision.fixtureIds.isNotEmpty())
                assertTrue(decision.testedHostLanes.containsAll(listOf("253", "262")))
                assertTrue(decision.testedAlternative?.isNotBlank() == true)
            }
        }
    }

    @Test
    fun `unknown and mutation operations deny by default`() {
        val evaluator = CompatibilityEvaluator(loadReviewedRegistry())
        val context = trustedContext(
            CompatibilityProfile(ProfileClassification.JMIX_2_8, "2.8.7", 17),
        )

        val unknown = evaluator.evaluate("discovery.unknown", context)
        val mutation = evaluator.evaluate("entity.generate", context)

        assertEquals(CompatibilityState.UNSUPPORTED, unknown.state)
        assertEquals("P2_REGISTRY_CELL_MISSING", unknown.reasonCode)
        assertEquals(CompatibilityState.UNSUPPORTED, mutation.state)
        assertEquals("P2_REGISTRY_CELL_MISSING", mutation.reasonCode)
        assertTrue(unknown.missingEvidence.isNotEmpty())
        assertTrue(mutation.missingEvidence.isNotEmpty())
    }

    @Test
    fun `degraded evidence never authorizes a certified cell`() {
        val evaluator = CompatibilityEvaluator(loadReviewedRegistry())
        val profile = CompatibilityProfile(ProfileClassification.JMIX_2_8, "2.8.7", 17)
        val cases = listOf(
            CompatibilityContext(profile, TrustState.UNTRUSTED, ImportState.READY) to "P2_UNTRUSTED",
            CompatibilityContext(profile, TrustState.UNKNOWN, ImportState.READY) to "P2_UNTRUSTED",
            CompatibilityContext(profile, TrustState.TRUSTED, ImportState.STALE) to "P2_IMPORT_STALE",
            CompatibilityContext(profile, TrustState.TRUSTED, ImportState.FAILED) to "P2_IMPORT_FAILED",
            CompatibilityContext(profile, TrustState.TRUSTED, ImportState.ABSENT) to "P2_IMPORT_ABSENT",
            CompatibilityContext(
                profile,
                TrustState.TRUSTED,
                ImportState.READY,
                indexComplete = false,
            ) to "P2_INDEX_INCOMPLETE",
            CompatibilityContext(
                profile,
                TrustState.TRUSTED,
                ImportState.READY,
                evidenceConflict = true,
            ) to "P2_EVIDENCE_CONFLICT",
        )

        for ((context, reasonCode) in cases) {
            val decision = evaluator.evaluate("discovery.snapshot", context)

            assertNotEquals(CompatibilityState.CERTIFIED_READ_WRITE, decision.state)
            assertEquals(reasonCode, decision.reasonCode)
            assertTrue(decision.missingEvidence.isNotEmpty())
            assertTrue(decision.testedAlternative?.isNotBlank() == true)
        }
    }

    @Test
    fun `legacy future and unmatched profiles remain diagnostic or unsupported`() {
        val evaluator = CompatibilityEvaluator(loadReviewedRegistry())
        val legacy = evaluator.evaluate(
            "discovery.inventory",
            trustedContext(CompatibilityProfile(ProfileClassification.LEGACY_JMIX_1, "1.7.0", 17)),
        )
        val future = evaluator.evaluate(
            "discovery.inventory",
            trustedContext(CompatibilityProfile(ProfileClassification.FUTURE, "4.0.0", 25)),
        )
        val unmatched = evaluator.evaluate(
            "discovery.inventory",
            trustedContext(CompatibilityProfile(ProfileClassification.JMIX_2_8, "2.8.7", 25)),
        )

        assertEquals(CompatibilityState.RECOGNIZED_DIAGNOSTIC, legacy.state)
        assertEquals("P2_LEGACY_DIAGNOSTIC", legacy.reasonCode)
        assertEquals(CompatibilityState.RECOGNIZED_DIAGNOSTIC, future.state)
        assertEquals("P2_FUTURE_UNCERTIFIED", future.reasonCode)
        assertEquals(CompatibilityState.UNSUPPORTED, unmatched.state)
        assertEquals("P2_REGISTRY_CELL_MISSING", unmatched.reasonCode)
    }

    @Test
    fun `write states and malformed write-like values fail closed`() {
        for (state in listOf("CERTIFIED_READ_WRITE", "certified_read_write", " CERTIFIED_READ_WRITE ")) {
            val failure = assertFailsWith<RegistryValidationException> {
                CompatibilityRegistryLoader.load(registryJson(state = state), reviewedFixtures)
            }

            assertEquals("P2_WRITE_FORBIDDEN", failure.reasonCode)
        }
    }

    @Test
    fun `overlapping selectors and missing fixture evidence fail validation`() {
        val duplicate = registryJson() + registryJson()
            .substringAfter("\"cells\":[")
            .substringBeforeLast("]}")
            .let { duplicateCell -> ""","cellsDuplicate":[$duplicateCell]""" }

        val overlapFailure = assertFailsWith<RegistryValidationException> {
            CompatibilityRegistryLoader.load(
                registryJson(extraCell = registryCell(jdk = 17)),
                reviewedFixtures,
            )
        }
        val missingEvidenceFailure = assertFailsWith<RegistryValidationException> {
            CompatibilityRegistryLoader.load(
                registryJson(fixtureId = "fixture-not-reviewed"),
                reviewedFixtures,
            )
        }

        assertEquals("P2_REGISTRY_SELECTOR_OVERLAP", overlapFailure.reasonCode)
        assertEquals("P2_EVIDENCE_REFERENCE_MISSING", missingEvidenceFailure.reasonCode)
        assertTrue(duplicate.isNotBlank())
    }

    @Test
    fun `canonical registry digest is stable across cell and field ordering`() {
        val first = CompatibilityRegistryLoader.load(
            registryJson(
                extraCell = registryCell(
                    operation = "discovery.inventory",
                    jdk = 21,
                    fixtureId = "fixture-jmix-2.8-jdk21",
                ),
            ),
            reviewedFixtures,
        )
        val second = CompatibilityRegistryLoader.load(
            registryJson(
                operation = "discovery.inventory",
                jdk = 21,
                fixtureId = "fixture-jmix-2.8-jdk21",
                extraCell = registryCell(),
                reverseFields = true,
            ),
            reviewedFixtures,
        )

        assertEquals(first.digest, second.digest)
    }

    private fun loadReviewedRegistry(): CompatibilityRegistry =
        CompatibilityRegistryLoader.loadResource(
            "/compatibility/phase2-registry.json",
            reviewedFixtures,
        )

    private fun trustedContext(profile: CompatibilityProfile): CompatibilityContext =
        CompatibilityContext(
            profile = profile,
            trustState = TrustState.TRUSTED,
            importState = ImportState.READY,
            evidenceIds = listOf("profile", "jdk", "import"),
        )

    private fun registryJson(
        state: String = "CERTIFIED_READ_ONLY",
        operation: String = "discovery.snapshot",
        jdk: Int = 17,
        fixtureId: String = "fixture-jmix-2.8-jdk17",
        extraCell: String? = null,
        reverseFields: Boolean = false,
    ): String {
        val cells = listOfNotNull(
            registryCell(operation, state, jdk, fixtureId, reverseFields),
            extraCell,
        ).joinToString(",")
        return """{"version":"phase2-v1","cells":[$cells]}"""
    }

    private fun registryCell(
        operation: String = "discovery.snapshot",
        state: String = "CERTIFIED_READ_ONLY",
        jdk: Int = 17,
        fixtureId: String = "fixture-jmix-2.8-jdk17",
        reverseFields: Boolean = false,
    ): String {
        val normal = listOf(
            """"operationId":"$operation"""",
            """"classification":"JMIX_2_8"""",
            """"platformLine":"2.8"""",
            """"targetJdks":[$jdk]""",
            """"state":"$state"""",
            """"reasonCode":"P2_CERTIFIED_READ_ONLY"""",
            """"evidenceRefs":["$fixtureId"]""",
            """"testedJmix":["2.8.x"]""",
            """"testedTargetJdks":[$jdk]""",
            """"testedHostLanes":["253","262"]""",
            """"fixtureIds":["$fixtureId"]""",
            """"testedAlternative":"Use Project Overview in read-only mode"""",
        )
        return "{${(if (reverseFields) normal.reversed() else normal).joinToString(",")}}"
    }

    companion object {
        private val READ_OPERATIONS = listOf(
            "discovery.snapshot",
            "discovery.inventory",
            "discovery.relationships",
            "discovery.navigate",
        )
    }
}

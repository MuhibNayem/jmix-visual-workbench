package org.jmixworkbench.discovery.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryModelTest {
    @Test
    fun `source locator exposes only revision-bound project-relative coordinates`() {
        val fieldNames = SourceLocator::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("relativePath", "symbol", "line", "column", "revisionFingerprint"),
            fieldNames,
        )
        assertFailsWith<IllegalArgumentException> {
            SourceLocator(
                relativePath = "/Users/example/project/Order.java",
                revisionFingerprint = "sha256:revision",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SourceLocator(
                relativePath = "../outside/Order.java",
                revisionFingerprint = "sha256:revision",
            )
        }
    }

    @Test
    fun `optional IDE capability keeps missing evidence unknown`() {
        val diagnostic = DiscoveryDiagnostic(
            id = "capability-missing",
            severity = DiagnosticSeverity.INFO,
            category = DiagnosticCategory.PROFILE,
            reasonCode = "OPTIONAL_CAPABILITY_NOT_DETECTED",
            message = "The optional IDE capability was not detected.",
            nextStep = "Continue with platform-independent discovery.",
        )
        val capability = OptionalIdeCapability(
            id = "jmix-commercial",
            present = null,
            enabled = null,
            version = null,
            source = null,
            confidence = EvidenceConfidence.WEAK,
            diagnostic = diagnostic,
        )

        assertNull(capability.present)
        assertNull(capability.enabled)
        assertNull(capability.version)
        assertEquals(diagnostic, capability.diagnostic)
        assertEquals(
            setOf("id", "present", "enabled", "version", "source", "confidence", "diagnostic"),
            OptionalIdeCapability::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `conflicting observations remain explicit instead of choosing a default`() {
        val targetJdk = Evidence(
            value = null,
            sourceKind = EvidenceSourceKind.IMPORTED_GRADLE_MODEL,
            sourceId = "target-jdk",
            confidence = EvidenceConfidence.CONFLICTING,
            observedFingerprint = "sha256:evidence",
            observedValues = listOf(17, 21),
        )
        val capability = OptionalIdeCapability(
            id = "jmix-commercial",
            present = null,
            enabled = null,
            version = null,
            source = "Imported IDE model",
            confidence = EvidenceConfidence.CONFLICTING,
            diagnostic = null,
        )

        assertNull(targetJdk.value)
        assertEquals(listOf(17, 21), targetJdk.observedValues)
        assertNull(capability.present)
        assertNull(capability.enabled)
        assertNull(capability.version)
        assertEquals(EvidenceConfidence.CONFLICTING, capability.confidence)
    }

    @Test
    fun `research-defined module roles are exhaustive and stable`() {
        assertEquals(
            setOf(
                "APPLICATION",
                "ADDON_FUNCTIONAL",
                "ADDON_STARTER",
                "BUILD_LOGIC",
                "AGGREGATOR",
                "LIBRARY",
                "UNKNOWN",
            ),
            ModuleRole.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `unknown profile facts contain no prototype defaults`() {
        val profile = JmixProfile()
        val snapshot = DiscoverySnapshot(
            snapshotId = "",
            projectId = "local-project",
            createdAtEpochMillis = 123L,
            trustState = TrustState.UNKNOWN,
            importState = ImportState.ABSENT,
            profile = profile,
        )
        val rendered = snapshot.toString()

        assertNull(profile.platformVersion.value)
        assertNull(profile.targetJdk.value)
        assertTrue("2.4.0" !in rendered)
        assertTrue("POSTGRES" !in rendered)
        assertTrue("com.example.app" !in rendered)
        assertTrue("src/main/" !in rendered)
    }
}

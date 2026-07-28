package org.jmixworkbench.discovery.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CanonicalDiscoveryJsonTest {
    @Test
    fun `permuted facts and host-local metadata produce identical canonical identity`() {
        val first = snapshot(
            snapshotId = "transient-a",
            projectId = "machine-a",
            createdAt = 100L,
            artifactOrder = listOf("Order", "Customer"),
            capabilityOrder = listOf("java", "kotlin"),
        )
        val second = snapshot(
            snapshotId = "transient-b",
            projectId = "machine-b",
            createdAt = 999L,
            artifactOrder = listOf("Customer", "Order"),
            capabilityOrder = listOf("kotlin", "java"),
        )

        val firstJson = CanonicalDiscoveryJson.encode(first)
        val secondJson = CanonicalDiscoveryJson.encode(second)

        assertEquals(firstJson, secondJson)
        assertEquals(
            CanonicalDiscoveryJson.snapshotId(first),
            CanonicalDiscoveryJson.snapshotId(second),
        )
        assertFalse("machine-a" in firstJson)
        assertFalse("createdAt" in firstJson)
        assertTrue(firstJson.startsWith("{\"artifacts\":"))
    }

    @Test
    fun `artifact identities use normalized semantic keys and owner coordinates`() {
        val normalized = CanonicalDiscoveryJson.artifactId(
            kind = ArtifactKind.ENTITY,
            buildId = "main-build",
            moduleId = "app",
            semanticKey = " com.example.Order ",
        )
        val equivalent = CanonicalDiscoveryJson.artifactId(
            kind = ArtifactKind.ENTITY,
            buildId = "main-build",
            moduleId = "app",
            semanticKey = "com.example.Order",
        )
        val otherModule = CanonicalDiscoveryJson.artifactId(
            kind = ArtifactKind.ENTITY,
            buildId = "main-build",
            moduleId = "addon",
            semanticKey = "com.example.Order",
        )

        assertEquals(normalized, equivalent)
        assertNotEquals(normalized, otherModule)
        assertTrue(normalized.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `sha256 uses UTF-8 bytes`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CanonicalDiscoveryJson.sha256("abc"),
        )
    }

    private fun snapshot(
        snapshotId: String,
        projectId: String,
        createdAt: Long,
        artifactOrder: List<String>,
        capabilityOrder: List<String>,
    ): DiscoverySnapshot {
        val artifacts = artifactOrder.map { name ->
            ArtifactSnapshot(
                id = CanonicalDiscoveryJson.artifactId(
                    ArtifactKind.ENTITY,
                    "main-build",
                    "app",
                    "com.example.$name",
                ),
                kind = ArtifactKind.ENTITY,
                semanticKey = "com.example.$name",
                owner = ArtifactOwner("main-build", "app", "main"),
                sourceLocator = SourceLocator(
                    relativePath = "app/src/main/java/com/example/$name.java",
                    symbol = "com.example.$name",
                    line = 1,
                    column = 1,
                    revisionFingerprint = "sha256:${name.lowercase()}",
                ),
                origin = ArtifactOrigin.SOURCE,
                fingerprint = "sha256:${name.lowercase()}",
                displayName = name,
                summary = null,
            )
        }
        val capabilities = capabilityOrder.map { id ->
            OptionalIdeCapability(
                id = id,
                present = true,
                enabled = true,
                version = "1.0",
                source = "Imported IDE model",
                confidence = EvidenceConfidence.EXACT,
                diagnostic = null,
            )
        }

        return DiscoverySnapshot(
            snapshotId = snapshotId,
            projectId = projectId,
            createdAtEpochMillis = createdAt,
            trustState = TrustState.TRUSTED,
            importState = ImportState.READY,
            artifacts = artifacts,
            optionalIdeCapabilities = capabilities,
        )
    }
}

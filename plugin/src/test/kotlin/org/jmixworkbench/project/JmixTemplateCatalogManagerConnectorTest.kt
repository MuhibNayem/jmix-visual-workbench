package org.jmixworkbench.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationCapability
import org.jmixworkbench.model.IntegrationConnectorCatalogBinding
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationSpringBootApi
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JmixTemplateCatalogManagerConnectorTest : BasePlatformTestCase() {
    fun testConfiguredCacheResolvesExactConnectorAndFailsClosedAfterTrustRotation() {
        val settings = JmixTemplateCatalogSettings.getInstance()
        val previousOffline = settings.state.offlineMode
        val previousCatalogs = settings.state.catalogs.map {
            it.copyState()
        }
        val directory = createTempDirectory("jmix-connector-manager-")
        try {
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val catalogId = "test.${UUID.randomUUID().toString().replace("-", "")}"
            val catalogVersion = "1.4.0"
            val signer = JmixTemplateCatalogSigner.fromPkcs8Base64(
                keyId = "test-release",
                privateKeyPkcs8Base64 = Base64.getEncoder().encodeToString(keyPair.private.encoded),
                publicKeyX509Base64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            )
            val bundle = JmixTemplateCatalogAuthoring.createSignedBundle(
                draft = JmixTemplateCatalogDraft(
                    catalogId = catalogId,
                    catalogVersion = catalogVersion,
                    displayName = "Test governed connectors",
                    issuedAt = Instant.now().minusSeconds(30),
                    expiresAt = null,
                    templates = emptyList(),
                    connectorTemplates = listOf(connector()),
                ),
                signer = signer,
            )
            val source = directory.resolve("connectors.jmix-connector-catalog")
            Files.write(source, bundle)
            val configured = state(
                catalogId = catalogId,
                catalogVersion = catalogVersion,
                bundle = bundle,
                publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            )
            val manager = JmixTemplateCatalogManager.getInstance()

            manager.importBundle(configured, source)
            settings.replace(offlineMode = true, catalogs = listOf(configured))
            val inventory = manager.connectorInventory()
            assertTrue(inventory.issues.isEmpty())
            val option = inventory.options.single()
            assertEquals(connector(), option.template)

            val binding = IntegrationConnectorCatalogBinding(
                catalogId = catalogId,
                catalogVersion = catalogVersion,
                bundleSha256 = option.bundleSha256,
                templateId = option.template.id,
                templateVersion = option.template.version,
            )
            assertEquals(option, manager.resolveConnector(binding))
            assertFailsWith<IllegalArgumentException> {
                manager.resolveConnector(binding.copy(bundleSha256 = "0".repeat(64)))
            }.also {
                assertContains(it.message.orEmpty(), "changed after selection")
            }

            settings.replace(
                offlineMode = true,
                catalogs = listOf(
                    configured.copyState().also {
                        it.minimumCatalogVersion = "2.0.0"
                    },
                ),
            )
            manager.connectorInventory().also { rejected ->
                assertTrue(rejected.options.isEmpty())
                assertContains(rejected.issues.single().message, "below required version")
            }

            val rotatedKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            settings.replace(
                offlineMode = true,
                catalogs = listOf(
                    configured.copyState().also {
                        it.signingPublicKey =
                            Base64.getEncoder().encodeToString(rotatedKey.public.encoded)
                    },
                ),
            )
            manager.connectorInventory().also { rejected ->
                assertTrue(rejected.options.isEmpty())
                assertContains(rejected.issues.single().message, "signature verification failed")
            }

            settings.replace(
                offlineMode = true,
                catalogs = listOf(configured.copyState().also { it.enabled = false }),
            )
            manager.connectorInventory().also { disabled ->
                assertTrue(disabled.options.isEmpty())
                assertTrue(disabled.issues.isEmpty())
            }
        } finally {
            settings.replace(previousOffline, previousCatalogs)
            deleteTree(directory)
        }
    }

    private fun state(
        catalogId: String,
        catalogVersion: String,
        bundle: ByteArray,
        publicKey: String,
    ): JmixTemplateCatalogSettings.CatalogState =
        JmixTemplateCatalogSettings.CatalogState().also {
            it.enabled = true
            it.displayName = "Test governed connectors"
            it.catalogId = catalogId
            it.catalogVersion = catalogVersion
            it.expectedBundleSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bundle),
            )
            it.signingKeyId = "test-release"
            it.signingPublicKey = publicKey
            it.minimumCatalogVersion = catalogVersion
        }

    private fun connector(): JmixOrganizationConnectorTemplate =
        JmixOrganizationConnectorTemplate(
            id = "test-identity",
            version = "1.0.0",
            name = "Test Identity",
            description = "Governed OAuth2 and mTLS connector.",
            order = 10,
            provider = "Test IAM",
            kind = IntegrationConnectorKind.IDENTITY_PROVIDER,
            springBootApis = setOf(
                IntegrationSpringBootApi.BOOT_3,
                IntegrationSpringBootApi.BOOT_4,
            ),
            requiredCapabilities = setOf(
                IntegrationCapability.SPRING_WEB,
                IntegrationCapability.OAUTH2_CLIENT,
                IntegrationCapability.SPRING_BOOT_SSL_BUNDLES,
            ),
            configurationPrefixSuffix = "test.identity",
            addressPropertySuffix = "base-url",
            headers = listOf(
                JmixOrganizationConnectorHeader(
                    name = "X-Correlation-ID",
                    propertySuffix = "correlation-id",
                    sensitive = false,
                ),
            ),
            policy = JmixOrganizationConnectorPolicy(
                risk = JmixOrganizationConnectorRisk.RESTRICTED,
                approvalPolicyId = "test.integration.restricted",
                requiredAuthentication = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
                requireMutualTls = true,
                requireTransactional = false,
                requireIdempotency = true,
                requireOutbox = false,
                requireInbox = false,
                maximumConnectTimeoutMs = 5_000,
                maximumRequestTimeoutMs = 15_000,
                minimumRetryAttempts = 3,
                requireMetrics = true,
                requireTracing = true,
                requireStructuredLogging = true,
                requireAudit = true,
                requiredObservabilityApi = IntegrationObservabilityApi.MICROMETER_OBSERVATION,
            ),
        )

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

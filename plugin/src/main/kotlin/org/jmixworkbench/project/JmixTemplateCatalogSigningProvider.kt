package org.jmixworkbench.project

import com.intellij.openapi.extensions.ExtensionPointName
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Enterprise signing adapter for custom project-template catalogs.
 *
 * Implementations keep their key inside the provider and may use PKCS#11, an HSM, a secure
 * enclave, or an approved remote signing service. The Workbench passes only canonical manifest
 * bytes and immediately verifies the returned signature against [publicKeyX509Base64].
 */
interface JmixTemplateCatalogSigningProvider : JmixTemplateCatalogSigningIdentity {
    val id: String
    val displayName: String

    companion object {
        val EP_NAME: ExtensionPointName<JmixTemplateCatalogSigningProvider> =
            ExtensionPointName.create("org.jmixworkbench.templateCatalogSigningProvider")

        fun available(): List<JmixTemplateCatalogSigningProvider> {
            val providers = EP_NAME.extensionList
            val duplicates = providers.groupBy(JmixTemplateCatalogSigningProvider::id)
                .filterValues { it.size > 1 }
                .keys
            require(duplicates.isEmpty()) {
                "Duplicate Jmix template signing-provider IDs: ${duplicates.sorted().joinToString()}."
            }
            val identifier = Regex("[a-z][a-z0-9.-]{0,95}")
            providers.forEach { provider ->
                require(identifier.matches(provider.id)) {
                    "Template signing provider ID '${provider.id}' is not a lowercase DNS-style identifier."
                }
                require(provider.displayName.isNotBlank() && provider.displayName.length <= 120) {
                    "Template signing provider '${provider.id}' has an invalid display name."
                }
                require(identifier.matches(provider.keyId)) {
                    "Template signing provider '${provider.id}' exposes an invalid signing key ID."
                }
                val keyValid = runCatching {
                    val encoded = Base64.getDecoder().decode(provider.publicKeyX509Base64.trim())
                    try {
                        KeyFactory.getInstance("Ed25519")
                            .generatePublic(X509EncodedKeySpec(encoded))
                    } finally {
                        encoded.fill(0)
                    }
                }.isSuccess
                require(keyValid) {
                    "Template signing provider '${provider.id}' exposes an invalid Ed25519 public key."
                }
            }
            return providers.sortedBy(JmixTemplateCatalogSigningProvider::displayName)
        }
    }
}

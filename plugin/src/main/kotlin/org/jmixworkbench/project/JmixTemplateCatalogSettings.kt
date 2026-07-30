package org.jmixworkbench.project

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import java.nio.file.Path

@State(
    name = "JmixOrganizationTemplateCatalogSettings",
    storages = [Storage("jmix-organization-template-catalogs.xml")],
)
@Service(Service.Level.APP)
class JmixTemplateCatalogSettings :
    PersistentStateComponent<JmixTemplateCatalogSettings.SettingsState> {

    class SettingsState {
        var offlineMode: Boolean = false
        var catalogs: MutableList<CatalogState> = mutableListOf()
    }

    class CatalogState {
        var enabled: Boolean = true
        var displayName: String = ""
        var catalogId: String = ""
        var catalogVersion: String = ""
        var sourceUrl: String = ""
        var expectedBundleSha256: String = ""
        var signingKeyId: String = ""
        var signingPublicKey: String = ""
        var minimumCatalogVersion: String = ""

        fun copyState(): CatalogState = CatalogState().also {
            XmlSerializerUtil.copyBean(this, it)
        }
    }

    private var value = SettingsState()

    override fun getState(): SettingsState = value

    override fun loadState(state: SettingsState) {
        value = SettingsState().also { XmlSerializerUtil.copyBean(state, it) }
    }

    fun replace(
        offlineMode: Boolean,
        catalogs: List<CatalogState>,
    ) {
        value = SettingsState().also { target ->
            target.offlineMode = offlineMode
            target.catalogs = catalogs.map(CatalogState::copyState).toMutableList()
        }
    }

    companion object {
        fun getInstance(): JmixTemplateCatalogSettings = service()
    }
}

data class JmixTemplateCatalogOption(
    val catalogId: String,
    val catalogVersion: String,
    val bundleSha256: String,
    val catalogDisplayName: String,
    val template: JmixOrganizationProjectTemplate,
) {
    val stableId: String = "$catalogId:$catalogVersion:${template.id}:${template.version}"

    override fun toString(): String = "$catalogDisplayName — ${template.name} (${template.version})"
}

data class JmixTemplateCatalogLoadIssue(
    val configuredName: String,
    val message: String,
)

data class JmixTemplateCatalogInventory(
    val options: List<JmixTemplateCatalogOption>,
    val issues: List<JmixTemplateCatalogLoadIssue>,
)

data class JmixTemplateCatalogRefreshResult(
    val configuredName: String,
    val cached: JmixCachedTemplateCatalog?,
    val error: String?,
)

/**
 * Native application-level catalog boundary. The wizard reads only already verified cache
 * coordinates, so opening File → New Project never performs hidden network I/O. Administrators
 * explicitly refresh or import bundles through settings; offline mode is therefore deterministic.
 */
@Service(Service.Level.APP)
class JmixTemplateCatalogManager {
    private val cache: JmixTemplateCatalogCache by lazy {
        JmixTemplateCatalogCache(defaultCacheRoot())
    }

    fun inventory(): JmixTemplateCatalogInventory {
        val settings = JmixTemplateCatalogSettings.getInstance().state
        val options = mutableListOf<JmixTemplateCatalogOption>()
        val issues = mutableListOf<JmixTemplateCatalogLoadIssue>()
        settings.catalogs.filter(JmixTemplateCatalogSettings.CatalogState::enabled).forEach { configured ->
            runCatching {
                val verified = openConfigured(configured)
                verified.manifest.templates.forEach { template ->
                    options += JmixTemplateCatalogOption(
                        catalogId = verified.manifest.catalogId,
                        catalogVersion = verified.manifest.catalogVersion,
                        bundleSha256 = verified.bundleSha256,
                        catalogDisplayName = verified.manifest.displayName,
                        template = template,
                    )
                }
            }.onFailure { failure ->
                issues += JmixTemplateCatalogLoadIssue(
                    configuredName = configured.displayName.ifBlank { configured.catalogId },
                    message = failure.message ?: "Catalog verification failed.",
                )
            }
        }
        return JmixTemplateCatalogInventory(
            options = options
                .distinctBy(JmixTemplateCatalogOption::stableId)
                .sortedWith(
                    compareBy(
                        { it.template.order },
                        JmixTemplateCatalogOption::catalogDisplayName,
                        { it.template.name },
                    ),
                ),
            issues = issues,
        )
    }

    fun apply(
        option: JmixTemplateCatalogOption,
        request: JmixProjectTemplateRequest,
    ): GeneratedJmixProject {
        val configured = configuredCatalog(option.catalogId, option.catalogVersion)
        val verified = openConfigured(configured)
        require(verified.bundleSha256 == option.bundleSha256) {
            "The selected organization template changed after wizard preview; reopen the wizard."
        }
        val current = verified.manifest.templates.singleOrNull {
            it.id == option.template.id && it.version == option.template.version
        }
        require(current == option.template) {
            "The selected organization template metadata changed after wizard preview; reopen the wizard."
        }
        return verified.apply(option.template.id, request)
    }

    fun refresh(
        configured: JmixTemplateCatalogSettings.CatalogState,
        offlineMode: Boolean = JmixTemplateCatalogSettings.getInstance().state.offlineMode,
    ): JmixCachedTemplateCatalog {
        require(!offlineMode) {
            "Organization template catalogs are in offline mode."
        }
        validateConfigured(configured, requireSource = true)
        val source = JmixTemplateCatalogSource(
            uri = java.net.URI(configured.sourceUrl.trim()),
            expectedBundleSha256 = configured.expectedBundleSha256.trim(),
        )
        val cached = JmixTemplateCatalogClient().refresh(
            source = source,
            cache = cache,
            policy = configured.policy(),
        )
        require(
            cached.catalogId == configured.catalogId.trim() &&
                cached.catalogVersion == configured.catalogVersion.trim()
        ) {
            "Downloaded template catalog identity does not match its configured coordinates."
        }
        return cached
    }

    fun refreshAll(): List<JmixTemplateCatalogRefreshResult> {
        val settings = JmixTemplateCatalogSettings.getInstance().state
        return settings.catalogs.filter(JmixTemplateCatalogSettings.CatalogState::enabled).map { configured ->
            runCatching { refresh(configured, settings.offlineMode) }.fold(
                onSuccess = {
                    JmixTemplateCatalogRefreshResult(
                        configuredName = configured.displayName.ifBlank { configured.catalogId },
                        cached = it,
                        error = null,
                    )
                },
                onFailure = {
                    JmixTemplateCatalogRefreshResult(
                        configuredName = configured.displayName.ifBlank { configured.catalogId },
                        cached = null,
                        error = it.message ?: "Catalog refresh failed.",
                    )
                },
            )
        }
    }

    fun importBundle(
        configured: JmixTemplateCatalogSettings.CatalogState,
        bundle: Path,
    ): JmixCachedTemplateCatalog {
        validateConfigured(configured, requireSource = false)
        val cached = cache.install(
            bundle = bundle,
            policy = configured.policy(),
            expectedBundleSha256 = configured.expectedBundleSha256.trim(),
        )
        require(
            cached.catalogId == configured.catalogId.trim() &&
                cached.catalogVersion == configured.catalogVersion.trim()
        ) {
            "Imported template catalog identity does not match its configured coordinates."
        }
        return cached
    }

    private fun openConfigured(
        configured: JmixTemplateCatalogSettings.CatalogState,
    ): JmixVerifiedTemplateCatalog {
        validateConfigured(configured, requireSource = false)
        return cache.open(
            catalogId = configured.catalogId.trim(),
            catalogVersion = configured.catalogVersion.trim(),
            bundleSha256 = configured.expectedBundleSha256.trim(),
            policy = configured.policy(),
        )
    }

    private fun configuredCatalog(
        catalogId: String,
        catalogVersion: String,
    ): JmixTemplateCatalogSettings.CatalogState =
        JmixTemplateCatalogSettings.getInstance().state.catalogs.singleOrNull {
            it.enabled &&
                it.catalogId.trim() == catalogId &&
                it.catalogVersion.trim() == catalogVersion
        } ?: throw JmixTemplateCatalogException(
            "The selected organization template catalog is no longer enabled.",
        )

    private fun validateConfigured(
        configured: JmixTemplateCatalogSettings.CatalogState,
        requireSource: Boolean,
    ) {
        require(configured.catalogId.trim().matches(Regex("[a-z][a-z0-9.-]{0,95}"))) {
            "Catalog ID must be a lowercase DNS-style identifier."
        }
        require(configured.catalogVersion.trim().matches(VERSION)) {
            "Catalog version must be numeric and dotted."
        }
        require(configured.expectedBundleSha256.trim().matches(HEX_64)) {
            "Catalog bundle SHA-256 must contain 64 lowercase hexadecimal characters."
        }
        require(configured.signingKeyId.trim().matches(Regex("[a-z][a-z0-9.-]{0,95}"))) {
            "Signing key ID must be a lowercase DNS-style identifier."
        }
        require(configured.signingPublicKey.isNotBlank()) {
            "Trusted Ed25519 public key is required."
        }
        if (configured.minimumCatalogVersion.isNotBlank()) {
            require(configured.minimumCatalogVersion.trim().matches(VERSION)) {
                "Minimum catalog version must be numeric and dotted."
            }
        }
        if (requireSource) {
            JmixTemplateCatalogSource(
                uri = java.net.URI(configured.sourceUrl.trim()),
                expectedBundleSha256 = configured.expectedBundleSha256.trim(),
            )
        }
    }

    private fun JmixTemplateCatalogSettings.CatalogState.policy(): JmixTemplateTrustPolicy =
        JmixTemplateTrustPolicy(
            trustedKeys = mapOf(signingKeyId.trim() to signingPublicKey.trim()),
            allowedCatalogIds = setOf(catalogId.trim()),
            requiredCatalogVersions = mapOf(catalogId.trim() to catalogVersion.trim()),
            minimumCatalogVersions = mapOf(
                catalogId.trim() to minimumCatalogVersion.trim().ifBlank {
                    catalogVersion.trim()
                },
            ),
        )

    companion object {
        private val HEX_64 = Regex("[0-9a-f]{64}")
        private val VERSION = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")

        fun getInstance(): JmixTemplateCatalogManager = service()

        internal fun defaultCacheRoot(): Path =
            PathManager.getSystemDir().resolve("jmix-workbench").resolve("template-catalogs")
    }
}

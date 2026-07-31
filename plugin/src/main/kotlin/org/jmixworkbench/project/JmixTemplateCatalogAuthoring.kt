package org.jmixworkbench.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class JmixTemplateCatalogDraft(
    val catalogId: String,
    val catalogVersion: String,
    val displayName: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val templates: List<JmixOrganizationProjectTemplateDraft>,
    val connectorTemplates: List<JmixOrganizationConnectorTemplate> = emptyList(),
)

data class JmixOrganizationProjectTemplateDraft(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val order: Int,
    val baseTemplate: JmixProjectTemplateKind,
    val languages: Set<JmixProjectLanguage>,
    val uiKinds: Set<JmixProjectUiKind>,
    val jmixVersions: Set<String>,
    val javaVersions: Set<Int>,
    val changes: List<JmixOrganizationTemplateChangeDraft>,
)

class JmixOrganizationTemplateChangeDraft(
    val relativePath: String,
    val action: JmixOrganizationTemplateChangeAction,
    content: ByteArray? = null,
    val payloadKind: JmixOrganizationTemplatePayloadKind? =
        if (action == JmixOrganizationTemplateChangeAction.DELETE) {
            null
        } else {
            JmixOrganizationTemplatePayloadKind.TEXT
    },
    val executable: Boolean = false,
) {
    private val bytes: ByteArray? = content?.copyOf()

    val content: ByteArray?
        get() = bytes?.copyOf()

    val contentSize: Int
        get() = bytes?.size ?: -1

    override fun equals(other: Any?): Boolean =
        other is JmixOrganizationTemplateChangeDraft &&
            relativePath == other.relativePath &&
            action == other.action &&
            payloadKind == other.payloadKind &&
            executable == other.executable &&
            when {
                bytes == null -> other.bytes == null
                other.bytes == null -> false
                else -> bytes.contentEquals(other.bytes)
            }

    override fun hashCode(): Int {
        var result = relativePath.hashCode()
        result = 31 * result + action.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + (payloadKind?.hashCode() ?: 0)
        result = 31 * result + executable.hashCode()
        return result
    }

    override fun toString(): String =
        "JmixOrganizationTemplateChangeDraft(" +
            "relativePath=$relativePath, action=$action, bytes=${bytes?.size}, " +
            "payloadKind=$payloadKind, executable=$executable)"
}

class JmixTemplateCatalogSigner(
    override val keyId: String,
    val privateKey: PrivateKey,
    override val publicKeyX509Base64: String,
) : JmixTemplateCatalogSigningIdentity {
    override fun toString(): String =
        "JmixTemplateCatalogSigner(keyId=$keyId, privateKey=<redacted>)"

    override fun sign(manifestBytes: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(manifestBytes)
            sign()
        }

    companion object {
        fun fromPkcs8Base64(
            keyId: String,
            privateKeyPkcs8Base64: String,
            publicKeyX509Base64: String,
        ): JmixTemplateCatalogSigner {
            val encoded = runCatching {
                Base64.getDecoder().decode(privateKeyPkcs8Base64.trim())
            }.getOrElse {
                throw JmixTemplateCatalogException("Ed25519 private key is not valid Base64.")
            }
            return try {
                val privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(PKCS8EncodedKeySpec(encoded))
                JmixTemplateCatalogSigner(
                    keyId = keyId,
                    privateKey = privateKey,
                    publicKeyX509Base64 = publicKeyX509Base64.trim(),
                )
            } catch (failure: Exception) {
                throw JmixTemplateCatalogException("Ed25519 PKCS#8 private key is invalid.", failure)
            } finally {
                encoded.fill(0)
            }
        }

        fun fromFiles(
            keyId: String,
            privateKeyPkcs8: Path,
            publicKeyX509: Path,
        ): JmixTemplateCatalogSigner {
            val privateEncoded = readKeyFile(privateKeyPkcs8, "PRIVATE KEY")
            val publicEncoded = readKeyFile(publicKeyX509, "PUBLIC KEY")
            return try {
                val privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(PKCS8EncodedKeySpec(privateEncoded))
                KeyFactory.getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(publicEncoded))
                JmixTemplateCatalogSigner(
                    keyId = keyId.trim(),
                    privateKey = privateKey,
                    publicKeyX509Base64 = Base64.getEncoder().encodeToString(publicEncoded),
                )
            } catch (failure: Exception) {
                throw JmixTemplateCatalogException(
                    "Ed25519 signing key files are not valid PKCS#8 private/X.509 public keys.",
                    failure,
                )
            } finally {
                privateEncoded.fill(0)
                publicEncoded.fill(0)
            }
        }

        private fun readKeyFile(
            path: Path,
            pemLabel: String,
        ): ByteArray {
            val absolute = path.toAbsolutePath().normalize()
            require(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absolute)) {
                "$pemLabel file must be a real regular file."
            }
            require(Files.size(absolute) in 1..64L * 1024L) {
                "$pemLabel file must be between 1 byte and 64 KiB."
            }
            val raw = Files.readAllBytes(absolute)
            val begin = "-----BEGIN $pemLabel-----".toByteArray(Charsets.US_ASCII)
            val end = "-----END $pemLabel-----".toByteArray(Charsets.US_ASCII)
            val beginIndex = raw.indexOf(begin)
            val endIndex = raw.indexOf(end)
            if (beginIndex >= 0 || endIndex >= 0) {
                require(beginIndex >= 0 && endIndex > beginIndex + begin.size) {
                    "$pemLabel PEM boundary is incomplete."
                }
                val compact = ByteArrayOutputStream()
                for (index in beginIndex + begin.size until endIndex) {
                    val byte = raw[index]
                    if (
                        byte != ' '.code.toByte() &&
                        byte != '\t'.code.toByte() &&
                        byte != '\r'.code.toByte() &&
                        byte != '\n'.code.toByte()
                    ) {
                        compact.write(byte.toInt())
                    }
                }
                val base64 = compact.toByteArray()
                return try {
                    Base64.getDecoder().decode(base64)
                } catch (failure: IllegalArgumentException) {
                    throw JmixTemplateCatalogException("$pemLabel PEM body is not valid Base64.", failure)
                } finally {
                    base64.fill(0)
                    raw.fill(0)
                }
            }
            return raw
        }

        private fun ByteArray.indexOf(needle: ByteArray): Int {
            if (needle.isEmpty() || needle.size > size) return -1
            for (start in 0..size - needle.size) {
                var matches = true
                for (offset in needle.indices) {
                    if (this[start + offset] != needle[offset]) {
                        matches = false
                        break
                    }
                }
                if (matches) return start
            }
            return -1
        }
    }
}

interface JmixTemplateCatalogSigningIdentity {
    val keyId: String
    val publicKeyX509Base64: String

    /**
     * Signs the exact canonical manifest bytes. Implementations may delegate to PKCS#11,
     * a hardware-backed key, or a remote enterprise signing service.
     */
    fun sign(manifestBytes: ByteArray): ByteArray
}

/**
 * Deterministic catalog publisher intended for an organization's reviewed build or release job.
 *
 * It accepts only declarative text/binary overlays and never executes template content. The completed
 * archive is passed through the production verifier before it can be returned or installed.
 */
object JmixTemplateCatalogAuthoring {
    fun createSignedBundle(
        draft: JmixTemplateCatalogDraft,
        signer: JmixTemplateCatalogSigner,
        clock: Clock = Clock.systemUTC(),
    ): ByteArray = createSignedBundle(draft, signer as JmixTemplateCatalogSigningIdentity, clock)

    fun createSignedBundle(
        draft: JmixTemplateCatalogDraft,
        signer: JmixTemplateCatalogSigningIdentity,
        clock: Clock = Clock.systemUTC(),
    ): ByteArray {
        val signerKeyId = signer.keyId
        val signerPublicKey = signer.publicKeyX509Base64
        val templates = draft.templates.sortedBy(JmixOrganizationProjectTemplateDraft::id)
        val connectorTemplates = draft.connectorTemplates.sortedBy(JmixOrganizationConnectorTemplate::id)
        val payloads = linkedMapOf<String, ByteArray>()
        val manifest = JsonObject().apply {
            addProperty("schemaVersion", if (connectorTemplates.isEmpty()) 2 else 3)
            addProperty("catalogId", draft.catalogId)
            addProperty("catalogVersion", draft.catalogVersion)
            addProperty("displayName", draft.displayName)
            addProperty("issuedAt", draft.issuedAt.toString())
            add(
                "expiresAt",
                draft.expiresAt?.let { expiration ->
                    com.google.gson.JsonPrimitive(expiration.toString())
                } ?: JsonNull.INSTANCE,
            )
            addProperty("signingKeyId", signerKeyId)
            add(
                "templates",
                JsonArray().apply {
                    templates.forEach { template ->
                        add(templateJson(template, payloads))
                    }
                },
            )
            if (connectorTemplates.isNotEmpty()) {
                add(
                    "connectors",
                    JsonArray().apply {
                        connectorTemplates.forEach { template ->
                            add(connectorTemplateJson(template))
                        }
                    },
                )
            }
        }
        val manifestBytes = GSON.toJson(manifest).toByteArray(Charsets.UTF_8)
        val signature = signer.sign(manifestBytes.copyOf())
        require(signature.size == 64) {
            "Ed25519 signing provider '$signerKeyId' returned ${signature.size} bytes; expected 64."
        }
        val entries = linkedMapOf(
            JmixTemplateCatalogVerifier.MANIFEST_PATH to manifestBytes,
            JmixTemplateCatalogVerifier.SIGNATURE_PATH to
                Base64.getEncoder().encode(signature),
        )
        entries.putAll(payloads.toSortedMap())
        val bundle = deterministicZip(entries)

        JmixTemplateCatalogVerifier.verify(
            bundleBytes = bundle,
            policy = JmixTemplateTrustPolicy(
                trustedKeys = mapOf(signerKeyId to signerPublicKey),
                allowedCatalogIds = setOf(draft.catalogId),
                requiredCatalogVersions = mapOf(draft.catalogId to draft.catalogVersion),
                minimumCatalogVersions = mapOf(draft.catalogId to draft.catalogVersion),
            ),
            clock = clock,
        )
        return bundle
    }

    fun writeCreateOnly(
        target: Path,
        bundle: ByteArray,
    ) {
        val absolute = target.toAbsolutePath().normalize()
        val parent = requireNotNull(absolute.parent) { "Catalog target has no parent directory." }
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "Catalog target parent must be an existing real directory."
        }
        require(!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "Refusing to replace existing catalog bundle '$absolute'."
        }
        val temporary = parent.resolve(".${absolute.fileName}.incoming-${UUID.randomUUID()}")
        try {
            Files.write(
                temporary,
                bundle,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absolute)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun templateJson(
        template: JmixOrganizationProjectTemplateDraft,
        payloads: MutableMap<String, ByteArray>,
    ): JsonObject = JsonObject().apply {
        addProperty("id", template.id)
        addProperty("version", template.version)
        addProperty("name", template.name)
        addProperty("description", template.description)
        addProperty("order", template.order)
        addProperty("baseTemplate", template.baseTemplate.name)
        add("languages", strings(template.languages.map { it.name }.sorted()))
        add("uiKinds", strings(template.uiKinds.map { it.name }.sorted()))
        add("jmixVersions", strings(template.jmixVersions.sorted()))
        add("javaVersions", integers(template.javaVersions.sorted()))
        add(
            "changes",
            JsonArray().apply {
                template.changes.sortedBy(JmixOrganizationTemplateChangeDraft::relativePath).forEach { change ->
                    val payloadPath = "templates/${template.id}/${change.relativePath}"
                    val content = change.content
                    add(
                        JsonObject().apply {
                            addProperty("path", change.relativePath)
                            addProperty("action", change.action.name)
                            if (change.action == JmixOrganizationTemplateChangeAction.DELETE) {
                                require(
                                    content == null &&
                                        change.payloadKind == null &&
                                        !change.executable
                                ) {
                                    "DELETE change '${change.relativePath}' cannot contain content, " +
                                        "payloadKind or be executable."
                                }
                                add("sha256", JsonNull.INSTANCE)
                                add("payloadKind", JsonNull.INSTANCE)
                            } else {
                                require(content != null) {
                                    "${change.action} change '${change.relativePath}' requires content."
                                }
                                require(change.payloadKind != null) {
                                    "${change.action} change '${change.relativePath}' requires payloadKind."
                                }
                                require(payloads.put(payloadPath, content.copyOf()) == null) {
                                    "Duplicate template payload path '$payloadPath'."
                                }
                                addProperty("sha256", sha256(content))
                                addProperty("payloadKind", change.payloadKind.name)
                            }
                            addProperty("executable", change.executable)
                        },
                    )
                }
            },
        )
    }

    private fun connectorTemplateJson(
        template: JmixOrganizationConnectorTemplate,
    ): JsonObject = JsonObject().apply {
        addProperty("id", template.id)
        addProperty("version", template.version)
        addProperty("name", template.name)
        addProperty("description", template.description)
        addProperty("order", template.order)
        addProperty("provider", template.provider)
        addProperty("kind", template.kind.name)
        add("springBootApis", strings(template.springBootApis.map { it.name }.sorted()))
        add(
            "requiredCapabilities",
            strings(template.requiredCapabilities.map { it.name }.sorted()),
        )
        addProperty("configurationPrefixSuffix", template.configurationPrefixSuffix)
        addProperty("addressPropertySuffix", template.addressPropertySuffix)
        add(
            "headers",
            JsonArray().apply {
                template.headers.sortedBy { it.name.lowercase() }.forEach { header ->
                    add(
                        JsonObject().apply {
                            addProperty("name", header.name)
                            addProperty("propertySuffix", header.propertySuffix)
                            addProperty("sensitive", header.sensitive)
                        },
                    )
                }
            },
        )
        add(
            "policy",
            JsonObject().apply {
                addProperty("risk", template.policy.risk.name)
                if (template.policy.approvalPolicyId == null) {
                    add("approvalPolicyId", JsonNull.INSTANCE)
                } else {
                    addProperty("approvalPolicyId", template.policy.approvalPolicyId)
                }
                if (template.policy.requiredAuthentication == null) {
                    add("requiredAuthentication", JsonNull.INSTANCE)
                } else {
                    addProperty(
                        "requiredAuthentication",
                        template.policy.requiredAuthentication.name,
                    )
                }
                addProperty("requireMutualTls", template.policy.requireMutualTls)
                addProperty("requireTransactional", template.policy.requireTransactional)
                addProperty("requireIdempotency", template.policy.requireIdempotency)
                addProperty("requireOutbox", template.policy.requireOutbox)
                addProperty("requireInbox", template.policy.requireInbox)
                addProperty(
                    "maximumConnectTimeoutMs",
                    template.policy.maximumConnectTimeoutMs,
                )
                addProperty(
                    "maximumRequestTimeoutMs",
                    template.policy.maximumRequestTimeoutMs,
                )
                addProperty("minimumRetryAttempts", template.policy.minimumRetryAttempts)
                addProperty("requireMetrics", template.policy.requireMetrics)
                addProperty("requireTracing", template.policy.requireTracing)
                addProperty(
                    "requireStructuredLogging",
                    template.policy.requireStructuredLogging,
                )
                addProperty("requireAudit", template.policy.requireAudit)
                if (template.policy.requiredObservabilityApi == null) {
                    add("requiredObservabilityApi", JsonNull.INSTANCE)
                } else {
                    addProperty(
                        "requiredObservabilityApi",
                        template.policy.requiredObservabilityApi.name,
                    )
                }
            },
        )
    }

    private fun strings(values: List<String>): JsonArray =
        JsonArray().apply { values.forEach(::add) }

    private fun integers(values: List<Int>): JsonArray =
        JsonArray().apply { values.forEach(::add) }

    private fun deterministicZip(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.toSortedMap().forEach { (path, content) ->
                    val entry = ZipEntry(path).apply {
                        time = 0L
                        comment = null
                        extra = null
                    }
                    zip.putNextEntry(entry)
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val GSON = GsonBuilder()
        .disableHtmlEscaping()
        .create()
}

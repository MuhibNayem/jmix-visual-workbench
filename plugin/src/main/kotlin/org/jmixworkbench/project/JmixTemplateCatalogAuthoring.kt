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

data class JmixOrganizationTemplateChangeDraft(
    val relativePath: String,
    val action: JmixOrganizationTemplateChangeAction,
    val content: ByteArray? = null,
    val executable: Boolean = false,
)

data class JmixTemplateCatalogSigner(
    val keyId: String,
    val privateKey: PrivateKey,
    val publicKeyX509Base64: String,
) {
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
    }
}

/**
 * Deterministic catalog publisher intended for an organization's reviewed build or release job.
 *
 * It accepts only declarative text overlays and never executes template content. The completed
 * archive is passed through the production verifier before it can be returned or installed.
 */
object JmixTemplateCatalogAuthoring {
    fun createSignedBundle(
        draft: JmixTemplateCatalogDraft,
        signer: JmixTemplateCatalogSigner,
        clock: Clock = Clock.systemUTC(),
    ): ByteArray {
        val templates = draft.templates.sortedBy(JmixOrganizationProjectTemplateDraft::id)
        val payloads = linkedMapOf<String, ByteArray>()
        val manifest = JsonObject().apply {
            addProperty("schemaVersion", 1)
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
            addProperty("signingKeyId", signer.keyId)
            add(
                "templates",
                JsonArray().apply {
                    templates.forEach { template ->
                        add(templateJson(template, payloads))
                    }
                },
            )
        }
        val manifestBytes = GSON.toJson(manifest).toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signer.privateKey)
            update(manifestBytes)
            sign()
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
                trustedKeys = mapOf(signer.keyId to signer.publicKeyX509Base64),
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
                                require(content == null && !change.executable) {
                                    "DELETE change '${change.relativePath}' cannot contain content or be executable."
                                }
                                add("sha256", JsonNull.INSTANCE)
                            } else {
                                require(content != null) {
                                    "${change.action} change '${change.relativePath}' requires content."
                                }
                                require(payloads.put(payloadPath, content.copyOf()) == null) {
                                    "Duplicate template payload path '$payloadPath'."
                                }
                                addProperty("sha256", sha256(content))
                            }
                            addProperty("executable", change.executable)
                        },
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

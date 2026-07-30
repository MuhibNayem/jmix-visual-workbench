package org.jmixworkbench.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JmixOrganizationTemplateCatalogTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `verifies signed catalog and applies deterministic compatible template`() {
        val replacement = """
            # ${'$'}{JMIX_PROJECT_NAME} enterprise baseline

            Artifact: ${'$'}{JMIX_GROUP_ID}:${'$'}{JMIX_ARTIFACT_ID}
            Runtime: Jmix ${'$'}{JMIX_VERSION} / Java ${'$'}{JMIX_JAVA_VERSION}
        """.trimIndent() + "\n"
        val policy = policy()
        val verified = JmixTemplateCatalogVerifier.verify(
            bundle(
                changes = listOf(
                    change("README.md", "REPLACE", replacement),
                    change(
                        ".github/CODEOWNERS",
                        "ADD",
                        "/src/main/ @acme/payroll\n",
                    ),
                ),
            ),
            policy,
            clock,
        )
        val request = request()

        assertEquals("acme.enterprise", verified.manifest.catalogId)
        assertEquals(listOf("acme-flowui"), verified.compatibleTemplates(request).map { it.id })
        val generated = verified.apply("acme-flowui", request)
        assertContains(generated.text("README.md"), "# Payroll enterprise baseline")
        assertContains(generated.text("README.md"), "com.acme:payroll")
        assertEquals("/src/main/ @acme/payroll\n", generated.text(".github/CODEOWNERS"))
        assertEquals(
            generated.files.map { it.relativePath }.sorted(),
            generated.files.map { it.relativePath },
        )
        assertTrue(generated.resources.any { it.relativePath == "gradle/wrapper/gradle-wrapper.jar" })
    }

    @Test
    fun `rejects tampered payload signature undeclared content and traversal`() {
        val content = "approved\n"
        val valid = bundle(changes = listOf(change("README.md", "REPLACE", content)))

        val tamperedPayload = rewriteEntry(valid, "templates/acme-flowui/README.md", "tampered\n".toByteArray())
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(tamperedPayload, policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "digest mismatch") }

        val tamperedManifest = rewriteEntry(
            valid,
            JmixTemplateCatalogVerifier.MANIFEST_PATH,
            readEntry(valid, JmixTemplateCatalogVerifier.MANIFEST_PATH)
                .toString(Charsets.UTF_8)
                .replace("Acme enterprise templates", "Attacker templates")
                .toByteArray(),
        )
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(tamperedManifest, policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "signature verification failed") }

        val undeclared = addEntry(valid, "templates/acme-flowui/hidden.txt", "hidden".toByteArray())
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(undeclared, policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "inventory mismatch") }

        val traversal = addEntry(valid, "../outside", "bad".toByteArray())
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(traversal, policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "Unsafe template catalog entry path") }
    }

    @Test
    fun `enforces signer catalog expiry compatibility and anti rollback policy`() {
        val bytes = bundle(
            catalogVersion = "1.4.0",
            expiresAt = "2026-08-01T00:00:00Z",
            changes = listOf(change("README.md", "REPLACE", "approved\n")),
        )
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(
                bytes,
                policy(
                    allowedCatalogIds = setOf("different.catalog"),
                ),
                clock,
            )
        }.also { assertContains(it.message.orEmpty(), "not allowed") }

        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(
                bytes,
                policy(minimumCatalogVersions = mapOf("acme.enterprise" to "1.5.0")),
                clock,
            )
        }.also { assertContains(it.message.orEmpty(), "below required version") }

        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(
                bytes,
                policy().copy(
                    requiredCatalogVersions = mapOf("acme.enterprise" to "1.3.0"),
                ),
                clock,
            )
        }.also { assertContains(it.message.orEmpty(), "does not match required version") }

        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(
                bytes,
                policy(),
                Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC),
            )
        }.also { assertContains(it.message.orEmpty(), "expired") }

        val unknownSigner = policy().copy(trustedKeys = mapOf("other-key" to publicKey()))
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(bytes, unknownSigner, clock)
        }.also { assertContains(it.message.orEmpty(), "not trusted") }

        val verified = JmixTemplateCatalogVerifier.verify(bytes, policy(), clock)
        assertTrue(verified.compatibleTemplates(request()).isNotEmpty())
        assertTrue(
            verified.compatibleTemplates(
                request().copy(javaVersion = 21),
            ).isEmpty(),
        )
    }

    @Test
    fun `offline cache is immutable content addressed and reverified on every open`() {
        val work = createTempDirectory("jmix-template-cache-test")
        try {
            val source = work.resolve("catalog.zip")
            val bytes = bundle(changes = listOf(change("README.md", "REPLACE", "approved\n")))
            Files.write(source, bytes)
            val cache = JmixTemplateCatalogCache(work.resolve("cache"))
            val installed = cache.install(
                bundle = source,
                policy = policy(),
                expectedBundleSha256 = sha256(bytes),
                clock = clock,
            )

            assertTrue(Files.isRegularFile(installed.bundlePath))
            val offline = cache.open(
                catalogId = installed.catalogId,
                catalogVersion = installed.catalogVersion,
                bundleSha256 = installed.bundleSha256,
                policy = policy(),
                clock = clock,
            )
            assertEquals(installed.bundleSha256, offline.bundleSha256)
            assertEquals("approved\n", offline.apply("acme-flowui", request()).text("README.md"))

            Files.write(installed.bundlePath, "corrupt".toByteArray())
            assertFailsWith<IllegalArgumentException> {
                cache.open(
                    catalogId = installed.catalogId,
                    catalogVersion = installed.catalogVersion,
                    bundleSha256 = installed.bundleSha256,
                    policy = policy(),
                    clock = clock,
                )
            }
        } finally {
            deleteTree(work)
        }
    }

    @Test
    fun `application rejects action mismatch binary content and unsupported variables`() {
        val addExisting = JmixTemplateCatalogVerifier.verify(
            bundle(changes = listOf(change("README.md", "ADD", "bad\n"))),
            policy(),
            clock,
        )
        assertFailsWith<IllegalArgumentException> {
            addExisting.apply("acme-flowui", request())
        }.also { assertContains(it.message.orEmpty(), "ADD target already exists") }

        val replaceMissing = JmixTemplateCatalogVerifier.verify(
            bundle(changes = listOf(change("missing.txt", "REPLACE", "bad\n"))),
            policy(),
            clock,
        )
        assertFailsWith<IllegalArgumentException> {
            replaceMissing.apply("acme-flowui", request())
        }.also { assertContains(it.message.orEmpty(), "REPLACE target does not exist") }

        val unknownVariable = JmixTemplateCatalogVerifier.verify(
            bundle(changes = listOf(change("README.md", "REPLACE", "${'$'}{JMIX_SECRET}\n"))),
            policy(),
            clock,
        )
        assertFailsWith<IllegalArgumentException> {
            unknownVariable.apply("acme-flowui", request())
        }.also { assertContains(it.message.orEmpty(), "unsupported variable") }

        val binary = JmixTemplateCatalogVerifier.verify(
            bundle(
                changes = listOf(
                    change("README.md", "REPLACE", byteArrayOf(0xC3.toByte(), 0x28)),
                ),
            ),
            policy(),
            clock,
        )
        assertFailsWith<JmixTemplateCatalogException> {
            binary.apply("acme-flowui", request())
        }.also { assertContains(it.message.orEmpty(), "not valid UTF-8") }
    }

    @Test
    fun `strict manifest rejects unknown properties and invalid delete contracts`() {
        val unknownProperty = bundle(
            rootMutation = { addProperty("unsignedBehavior", "allow") },
            changes = listOf(change("README.md", "REPLACE", "approved\n")),
        )
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(unknownProperty, policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "Unknown template catalog properties") }

        val deleteWithDigest = bundle(
            rawChanges = listOf(
                JsonObject().apply {
                    addProperty("path", "README.md")
                    addProperty("action", "DELETE")
                    addProperty("sha256", "0".repeat(64))
                    addProperty("executable", false)
                },
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(deleteWithDigest, policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "cannot declare sha256") }

        listOf(".git/hooks/pre-commit", "CON.txt", "trailing.").forEach { unsafePath ->
            val unsafe = bundle(
                changes = listOf(change(unsafePath, "ADD", "unsafe\n")),
            )
            assertFailsWith<IllegalArgumentException> {
                JmixTemplateCatalogVerifier.verify(unsafe, policy(), clock)
            }
        }

        val caseCollision = bundle(
            changes = listOf(
                change("policy/Rules.md", "ADD", "one\n"),
                change("policy/rules.md", "ADD", "two\n"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(caseCollision, policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "case-colliding") }

        val duplicateLanguage = bundle(
            rootMutation = {
                getAsJsonArray("templates")[0].asJsonObject
                    .getAsJsonArray("languages")
                    .add("JAVA")
            },
        )
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(duplicateLanguage, policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "duplicate values") }
    }

    @Test
    fun `bounds directory-only archives and refuses symlinked cache roots`() {
        val directoryBomb = linkedMapOf<String, ByteArray>().apply {
            repeat(10_001) { index ->
                put("directories/$index/", byteArrayOf())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            JmixTemplateCatalogVerifier.verify(zip(directoryBomb), policy(), clock)
        }.also { assertContains(it.message.orEmpty(), "entry safety limit") }

        val work = createTempDirectory("jmix-template-cache-symlink")
        try {
            val real = Files.createDirectory(work.resolve("real"))
            val link = work.resolve("linked")
            Files.createSymbolicLink(link, real)
            assertFailsWith<IllegalArgumentException> {
                JmixTemplateCatalogCache(link.resolve("cache"))
            }.also { assertContains(it.message.orEmpty(), "symbolic link") }
        } finally {
            deleteTree(work)
        }
    }

    @Test
    fun `authors deterministic self verified bundles and writes them create only`() {
        val signer = JmixTemplateCatalogSigner.fromPkcs8Base64(
            keyId = "acme-release",
            privateKeyPkcs8Base64 = Base64.getEncoder().encodeToString(keyPair.private.encoded),
            publicKeyX509Base64 = publicKey(),
        )
        val draft = JmixTemplateCatalogDraft(
            catalogId = "acme.enterprise",
            catalogVersion = "1.4.0",
            displayName = "Acme enterprise templates",
            issuedAt = Instant.parse("2026-07-30T00:00:00Z"),
            expiresAt = Instant.parse("2027-07-31T00:00:00Z"),
            templates = listOf(
                JmixOrganizationProjectTemplateDraft(
                    id = "acme-flowui",
                    version = "2.1.0",
                    name = "Acme governed FlowUI",
                    description = "Audited enterprise application baseline",
                    order = 10,
                    baseTemplate = JmixProjectTemplateKind.APPLICATION,
                    languages = setOf(JmixProjectLanguage.JAVA, JmixProjectLanguage.KOTLIN),
                    uiKinds = setOf(JmixProjectUiKind.FLOW_UI),
                    jmixVersions = setOf("3.0.0", "2.8.2"),
                    javaVersions = setOf(25, 17),
                    changes = listOf(
                        JmixOrganizationTemplateChangeDraft(
                            relativePath = ".github/CODEOWNERS",
                            action = JmixOrganizationTemplateChangeAction.ADD,
                            content = "/src/ @acme/platform\n".toByteArray(),
                        ),
                        JmixOrganizationTemplateChangeDraft(
                            relativePath = "README.md",
                            action = JmixOrganizationTemplateChangeAction.REPLACE,
                            content = "# ${'$'}{JMIX_PROJECT_NAME}\n".toByteArray(),
                        ),
                    ),
                ),
            ),
        )

        val first = JmixTemplateCatalogAuthoring.createSignedBundle(draft, signer, clock)
        val second = JmixTemplateCatalogAuthoring.createSignedBundle(draft, signer, clock)
        assertTrue(first.contentEquals(second))
        val verified = JmixTemplateCatalogVerifier.verify(first, policy(), clock)
        assertEquals("# Payroll\n", verified.apply("acme-flowui", request()).text("README.md"))

        val directory = createTempDirectory("jmix-template-authoring")
        try {
            val output = directory.resolve("acme.jmix-template-catalog")
            JmixTemplateCatalogAuthoring.writeCreateOnly(output, first)
            assertTrue(Files.readAllBytes(output).contentEquals(first))
            assertFailsWith<IllegalArgumentException> {
                JmixTemplateCatalogAuthoring.writeCreateOnly(output, second)
            }.also { assertContains(it.message.orEmpty(), "Refusing to replace") }
        } finally {
            deleteTree(directory)
        }
    }

    private fun policy(
        allowedCatalogIds: Set<String> = setOf("acme.enterprise"),
        minimumCatalogVersions: Map<String, String> = emptyMap(),
    ): JmixTemplateTrustPolicy = JmixTemplateTrustPolicy(
        trustedKeys = mapOf("acme-release" to publicKey()),
        allowedCatalogIds = allowedCatalogIds,
        minimumCatalogVersions = minimumCatalogVersions,
    )

    private fun publicKey(): String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private data class TestChange(
        val path: String,
        val action: String,
        val content: ByteArray?,
        val executable: Boolean = false,
    )

    private fun change(
        path: String,
        action: String,
        content: String,
        executable: Boolean = false,
    ): TestChange = change(path, action, content.toByteArray(), executable)

    private fun change(
        path: String,
        action: String,
        content: ByteArray,
        executable: Boolean = false,
    ): TestChange = TestChange(path, action, content, executable)

    private fun bundle(
        catalogVersion: String = "1.4.0",
        expiresAt: String? = "2027-07-31T00:00:00Z",
        changes: List<TestChange> = listOf(change("README.md", "REPLACE", "approved\n")),
        rawChanges: List<JsonObject>? = null,
        rootMutation: JsonObject.() -> Unit = {},
    ): ByteArray {
        val root = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("catalogId", "acme.enterprise")
            addProperty("catalogVersion", catalogVersion)
            addProperty("displayName", "Acme enterprise templates")
            addProperty("issuedAt", "2026-07-30T00:00:00Z")
            if (expiresAt == null) add("expiresAt", null) else addProperty("expiresAt", expiresAt)
            addProperty("signingKeyId", "acme-release")
            add(
                "templates",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("id", "acme-flowui")
                            addProperty("version", "2.1.0")
                            addProperty("name", "Acme governed FlowUI")
                            addProperty("description", "Audited enterprise application baseline")
                            addProperty("order", 10)
                            addProperty("baseTemplate", "APPLICATION")
                            add("languages", strings("JAVA", "KOTLIN"))
                            add("uiKinds", strings("FLOW_UI"))
                            add("jmixVersions", strings("2.8.2", "3.0.0"))
                            add("javaVersions", integers(17, 25))
                            add(
                                "changes",
                                JsonArray().apply {
                                    (rawChanges ?: changes.map { testChange ->
                                        JsonObject().apply {
                                            addProperty("path", testChange.path)
                                            addProperty("action", testChange.action)
                                            if (testChange.action == "DELETE") {
                                                add("sha256", null)
                                            } else {
                                                addProperty(
                                                    "sha256",
                                                    sha256(requireNotNull(testChange.content)),
                                                )
                                            }
                                            addProperty("executable", testChange.executable)
                                        }
                                    }).forEach(::add)
                                },
                            )
                        },
                    )
                },
            )
            rootMutation()
        }
        val manifest = GsonBuilder().disableHtmlEscaping().create()
            .toJson(root)
            .toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(manifest)
            Base64.getEncoder().encodeToString(sign())
        }.toByteArray(Charsets.UTF_8)
        val entries = linkedMapOf(
            JmixTemplateCatalogVerifier.MANIFEST_PATH to manifest,
            JmixTemplateCatalogVerifier.SIGNATURE_PATH to signature,
        )
        changes.filter { it.action != "DELETE" }.forEach { change ->
            entries["templates/acme-flowui/${change.path}"] = requireNotNull(change.content)
        }
        return zip(entries)
    }

    private fun strings(vararg values: String): JsonArray =
        JsonArray().apply { values.forEach(::add) }

    private fun integers(vararg values: Int): JsonArray =
        JsonArray().apply { values.forEach(::add) }

    private fun rewriteEntry(
        zip: ByteArray,
        path: String,
        content: ByteArray,
    ): ByteArray {
        val entries = readEntries(zip).toMutableMap()
        entries[path] = content
        return zip(entries)
    }

    private fun addEntry(
        zip: ByteArray,
        path: String,
        content: ByteArray,
    ): ByteArray {
        val entries = readEntries(zip).toMutableMap()
        entries[path] = content
        return zip(entries)
    }

    private fun readEntry(
        zip: ByteArray,
        path: String,
    ): ByteArray = readEntries(zip).getValue(path)

    private fun readEntries(zip: ByteArray): Map<String, ByteArray> =
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            buildMap {
                while (true) {
                    val entry = input.nextEntry ?: break
                    put(entry.name, input.readBytes())
                }
            }
        }

    private fun zip(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { output ->
                entries.forEach { (path, content) ->
                    output.putNextEntry(ZipEntry(path))
                    output.write(content)
                    output.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun request(): JmixProjectTemplateRequest = JmixProjectTemplateRequest(
        projectName = "Payroll",
        groupId = "com.acme",
        artifactId = "payroll",
        basePackage = "com.acme.payroll",
        projectId = "payroll",
        jmixVersion = "2.8.2",
        javaVersion = 17,
        templateKind = JmixProjectTemplateKind.APPLICATION,
        language = JmixProjectLanguage.JAVA,
        uiKind = JmixProjectUiKind.FLOW_UI,
        locales = listOf("en", "bn"),
    )

    private fun GeneratedJmixProject.text(path: String): String =
        files.single { it.relativePath == path }.content

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

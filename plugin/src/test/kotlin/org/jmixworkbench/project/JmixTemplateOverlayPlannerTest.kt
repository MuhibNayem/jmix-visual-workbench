package org.jmixworkbench.project

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JmixTemplateOverlayPlannerTest {
    private val request = JmixProjectTemplateRequest(
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

    @Test
    fun `plans deterministic text binary replacement addition and deletion overlay`() {
        val root = installedBase()
        try {
            root.resolve("README.md").writeText("# Governed Payroll\n")
            root.resolve(".gitignore").deleteExisting()
            root.resolve("policy").createDirectories()
            root.resolve("policy/README.md").writeText("Four-eyes approval required.\n")
            root.resolve("src/main/resources/branding").createDirectories()
            root.resolve("src/main/resources/branding/logo.bin")
                .writeBytes(byteArrayOf(0x00, 0x7f, 0x01, 0x02))
            root.resolve("build/generated").createDirectories()
            root.resolve("build/generated/unstable.txt").writeText("ignored one")
            root.resolve("module/build/generated").createDirectories()
            root.resolve("module/build/generated/also-ignored.txt").writeText("ignored")
            root.resolve(".DS_Store").writeBytes(byteArrayOf(0x00, 0x01))

            val first = JmixTemplateOverlayPlanner.plan(root, request)
            root.resolve("build/generated/unstable.txt").writeText("ignored two")
            val second = JmixTemplateOverlayPlanner.plan(root, request)

            assertEquals(first.sourceSha256, second.sourceSha256)
            assertEquals(first.changes, second.changes)
            assertEquals(2, first.addedCount)
            assertEquals(1, first.replacedCount)
            assertEquals(1, first.deletedCount)
            assertEquals(1, first.binaryCount)
            assertContains(first.ignoredPaths, "build")
            assertContains(first.ignoredPaths, "module/build")
            assertContains(first.ignoredPaths, ".DS_Store")
            assertEquals(
                listOf(
                    ".gitignore" to JmixOrganizationTemplateChangeAction.DELETE,
                    "README.md" to JmixOrganizationTemplateChangeAction.REPLACE,
                    "policy/README.md" to JmixOrganizationTemplateChangeAction.ADD,
                    "src/main/resources/branding/logo.bin" to
                        JmixOrganizationTemplateChangeAction.ADD,
                ),
                first.changes.map { it.relativePath to it.action },
            )
            assertEquals(
                JmixOrganizationTemplatePayloadKind.TEXT,
                first.change("policy/README.md").payloadKind,
            )
            assertEquals(
                JmixOrganizationTemplatePayloadKind.BINARY,
                first.change("src/main/resources/branding/logo.bin").payloadKind,
            )
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `signed planned overlay reproduces every modeled customization`() {
        val root = installedBase()
        try {
            root.resolve("README.md").writeText("# ${'$'}{JMIX_PROJECT_NAME} governed\n")
            root.resolve(".gitignore").deleteExisting()
            root.resolve("policy").createDirectories()
            root.resolve("policy/rules.txt").writeText("artifact=${'$'}{JMIX_ARTIFACT_ID}\n")
            root.resolve("src/main/resources/logo.bin").writeBytes(byteArrayOf(0x00, 0x10, 0x20))
            val plan = JmixTemplateOverlayPlanner.plan(root, request)
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val clock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC)
            val signer = JmixTemplateCatalogSigner.fromPkcs8Base64(
                keyId = "acme-release",
                privateKeyPkcs8Base64 = Base64.getEncoder().encodeToString(keyPair.private.encoded),
                publicKeyX509Base64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            )
            val bundle = JmixTemplateCatalogAuthoring.createSignedBundle(
                draft = JmixTemplateCatalogDraft(
                    catalogId = "acme.enterprise",
                    catalogVersion = "1.0.0",
                    displayName = "Acme enterprise templates",
                    issuedAt = clock.instant(),
                    expiresAt = null,
                    templates = listOf(
                        plan.toTemplateDraft(
                            id = "governed-payroll",
                            version = "1.0.0",
                            name = "Governed payroll",
                            description = "Certified overlay",
                            order = 10,
                            request = request,
                        ),
                    ),
                ),
                signer = signer,
                clock = clock,
            )
            val verified = JmixTemplateCatalogVerifier.verify(
                bundleBytes = bundle,
                policy = JmixTemplateTrustPolicy(
                    trustedKeys = mapOf(signer.keyId to signer.publicKeyX509Base64),
                    allowedCatalogIds = setOf("acme.enterprise"),
                    requiredCatalogVersions = mapOf("acme.enterprise" to "1.0.0"),
                ),
                clock = clock,
            )
            val generated = verified.apply("governed-payroll", request)

            assertFalse(generated.files.any { it.relativePath == ".gitignore" })
            assertEquals("# Payroll governed\n", generated.text("README.md"))
            assertEquals("artifact=payroll\n", generated.text("policy/rules.txt"))
            assertTrue(
                generated.binaryFiles.single { it.relativePath == "src/main/resources/logo.bin" }
                    .content.contentEquals(byteArrayOf(0x00, 0x10, 0x20)),
            )
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `fails closed for unchanged source symlinks and modified protected resources`() {
        val unchanged = installedBase()
        try {
            assertFailsWith<IllegalArgumentException> {
                JmixTemplateOverlayPlanner.plan(unchanged, request)
            }.also { assertContains(it.message.orEmpty(), "does not differ") }
        } finally {
            deleteTree(unchanged)
        }

        val linked = installedBase()
        val outside = createTempDirectory("jmix-overlay-outside-")
        try {
            runCatching {
                Files.createSymbolicLink(linked.resolve("linked.txt"), outside.resolve("outside.txt"))
            }.getOrElse { return }
            assertFailsWith<IllegalArgumentException> {
                JmixTemplateOverlayPlanner.plan(linked, request)
            }.also { assertContains(it.message.orEmpty(), "symbolic link") }
        } finally {
            deleteTree(linked)
            deleteTree(outside)
        }

        val modifiedWrapper = installedBase()
        try {
            modifiedWrapper.resolve("gradlew.bat").writeText("tampered")
            assertFailsWith<IllegalArgumentException> {
                JmixTemplateOverlayPlanner.plan(modifiedWrapper, request)
            }.also { assertContains(it.message.orEmpty(), "protected bundled resource") }
        } finally {
            deleteTree(modifiedWrapper)
        }
    }

    @Test
    fun `binary values cannot be mutated through source or returned arrays`() {
        val source = byteArrayOf(0x01, 0x02)
        val generated = GeneratedProjectBinaryFile("logo.bin", source)
        source[0] = 0x7f
        val returned = generated.content
        returned[1] = 0x7f

        assertTrue(generated.content.contentEquals(byteArrayOf(0x01, 0x02)))
        assertNotEquals(returned.toList(), generated.content.toList())
    }

    @Test
    fun `reviewed source identity rejects later edits and propagates cancellation`() {
        val root = installedBase()
        try {
            root.resolve("README.md").writeText("# First review\n")
            val reviewed = JmixTemplateOverlayPlanner.plan(root, request)
            root.resolve("README.md").writeText("# Changed after review\n")
            val current = JmixTemplateOverlayPlanner.plan(root, request)

            assertFalse(reviewed.matchesReviewedSource(current))
            assertNotEquals(reviewed.sourceSha256, current.sourceSha256)

            assertFailsWith<ScanCancelled> {
                JmixTemplateOverlayPlanner.plan(
                    customizedProjectRoot = root,
                    request = request,
                    progress = JmixTemplateOverlayProgress { throw ScanCancelled() },
                )
            }
        } finally {
            deleteTree(root)
        }
    }

    private fun installedBase(): Path {
        val root = createTempDirectory("jmix-overlay-project-")
        JmixProjectInstaller.install(root, JmixProjectTemplateGenerator.generate(request))
        return root
    }

    private fun JmixTemplateOverlayPlan.change(path: String): JmixOrganizationTemplateChangeDraft =
        changes.single { it.relativePath == path }

    private fun GeneratedJmixProject.text(path: String): String =
        files.single { it.relativePath == path }.content

    private class ScanCancelled : RuntimeException()

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

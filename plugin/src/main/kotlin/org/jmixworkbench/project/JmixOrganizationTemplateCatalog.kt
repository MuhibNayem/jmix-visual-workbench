package org.jmixworkbench.project

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.channels.Channels
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class JmixTemplateTrustPolicy(
    val trustedKeys: Map<String, String>,
    val allowedCatalogIds: Set<String> = emptySet(),
    val requiredCatalogVersions: Map<String, String> = emptyMap(),
    val minimumCatalogVersions: Map<String, String> = emptyMap(),
    val allowExpiredCatalogs: Boolean = false,
)

data class JmixOrganizationTemplateCatalog(
    val catalogId: String,
    val catalogVersion: String,
    val displayName: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val signingKeyId: String,
    val templates: List<JmixOrganizationProjectTemplate>,
)

data class JmixOrganizationProjectTemplate(
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
    val changes: List<JmixOrganizationTemplateChange>,
) {
    fun supports(request: JmixProjectTemplateRequest): Boolean =
        baseTemplate == request.templateKind &&
            request.language in languages &&
            request.uiKind in uiKinds &&
            request.jmixVersion in jmixVersions &&
            request.javaVersion in javaVersions
}

enum class JmixOrganizationTemplateChangeAction {
    ADD,
    REPLACE,
    DELETE,
}

data class JmixOrganizationTemplateChange(
    val relativePath: String,
    val action: JmixOrganizationTemplateChangeAction,
    val sha256: String?,
    val executable: Boolean = false,
)

data class JmixVerifiedTemplateCatalog(
    val manifest: JmixOrganizationTemplateCatalog,
    val bundleSha256: String,
    internal val payloads: Map<String, ByteArray>,
) {
    fun compatibleTemplates(request: JmixProjectTemplateRequest): List<JmixOrganizationProjectTemplate> =
        manifest.templates
            .filter { it.supports(request) }
            .sortedWith(compareBy(JmixOrganizationProjectTemplate::order, JmixOrganizationProjectTemplate::name))

    fun apply(
        templateId: String,
        request: JmixProjectTemplateRequest,
        generatedBase: GeneratedJmixProject = JmixProjectTemplateGenerator.generate(request),
    ): GeneratedJmixProject {
        val template = manifest.templates.singleOrNull { it.id == templateId }
            ?: throw JmixTemplateCatalogException("Template '$templateId' is not in ${manifest.catalogId}.")
        require(template.supports(request)) {
            "Template '${template.name}' does not support the selected Jmix, Java, language, UI, or project type."
        }

        val files = generatedBase.files.associateByTo(linkedMapOf(), GeneratedProjectFile::relativePath)
        val resourcePaths = generatedBase.resources.mapTo(hashSetOf(), GeneratedProjectResource::relativePath)
        template.changes.forEach { change ->
            require(change.relativePath !in resourcePaths) {
                "Organization templates cannot replace bundled binary resource '${change.relativePath}'."
            }
            when (change.action) {
                JmixOrganizationTemplateChangeAction.ADD -> {
                    require(change.relativePath !in files) {
                        "Template ADD target already exists: '${change.relativePath}'."
                    }
                    files[change.relativePath] = payloadFile(template, change, request)
                }

                JmixOrganizationTemplateChangeAction.REPLACE -> {
                    require(change.relativePath in files) {
                        "Template REPLACE target does not exist: '${change.relativePath}'."
                    }
                    files[change.relativePath] = payloadFile(template, change, request)
                }

                JmixOrganizationTemplateChangeAction.DELETE -> {
                    require(files.remove(change.relativePath) != null) {
                        "Template DELETE target does not exist: '${change.relativePath}'."
                    }
                }
            }
        }
        return GeneratedJmixProject(
            files = files.values.sortedBy(GeneratedProjectFile::relativePath),
            resources = generatedBase.resources,
        )
    }

    private fun payloadFile(
        template: JmixOrganizationProjectTemplate,
        change: JmixOrganizationTemplateChange,
        request: JmixProjectTemplateRequest,
    ): GeneratedProjectFile {
        val payloadPath = "templates/${template.id}/${change.relativePath}"
        val content = payloads[payloadPath]
            ?: throw JmixTemplateCatalogException("Verified payload '$payloadPath' is unavailable.")
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString()
        }.getOrElse {
            throw JmixTemplateCatalogException("Template payload '$payloadPath' is not valid UTF-8 text.")
        }
        val expanded = JmixOrganizationTemplateVariables.expand(decoded, request)
        require(expanded.toByteArray(StandardCharsets.UTF_8).size <= MAX_EXPANDED_FILE_BYTES) {
            "Expanded template payload '$payloadPath' exceeds the 8 MiB safety limit."
        }
        return GeneratedProjectFile(
            relativePath = change.relativePath,
            content = expanded,
            executable = change.executable,
        )
    }
}

class JmixTemplateCatalogException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object JmixOrganizationTemplateVariables {
    private val unresolvedVariable = Regex("""\$\{JMIX_[A-Z0-9_]+}""")

    fun expand(
        content: String,
        request: JmixProjectTemplateRequest,
    ): String {
        val values = linkedMapOf(
            "JMIX_PROJECT_NAME" to request.projectName,
            "JMIX_GROUP_ID" to request.groupId,
            "JMIX_ARTIFACT_ID" to request.artifactId,
            "JMIX_BASE_PACKAGE" to request.basePackage,
            "JMIX_BASE_PACKAGE_PATH" to request.basePackage.replace('.', '/'),
            "JMIX_PROJECT_ID" to request.projectId,
            "JMIX_VERSION" to request.jmixVersion,
            "JMIX_JAVA_VERSION" to request.javaVersion.toString(),
            "JMIX_LANGUAGE" to request.language.name.lowercase(),
            "JMIX_UI_KIND" to request.uiKind.name.lowercase(),
            "JMIX_LOCALES" to request.locales.joinToString(","),
        )
        var expanded = content
        values.forEach { (name, value) ->
            expanded = expanded.replace("\${$name}", value)
        }
        val unresolved = unresolvedVariable.find(expanded)?.value
        require(unresolved == null) {
            "Organization template contains unsupported variable '$unresolved'."
        }
        return expanded
    }
}

object JmixTemplateCatalogVerifier {
    const val MANIFEST_PATH = "catalog.json"
    const val SIGNATURE_PATH = "catalog.ed25519"

    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 10_000
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val identifierPattern = Regex("[a-z][a-z0-9.-]{0,95}")
    private val versionPattern = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")
    private val safePathSegment = Regex("[A-Za-z0-9._@+ -]+")

    fun verify(
        bundle: Path,
        policy: JmixTemplateTrustPolicy,
        clock: Clock = Clock.systemUTC(),
    ): JmixVerifiedTemplateCatalog {
        require(Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(bundle)) {
            "Template catalog bundle must be a regular, non-symbolic-link file."
        }
        require(Files.size(bundle) <= MAX_ARCHIVE_BYTES) {
            "Template catalog bundle exceeds the 64 MiB compressed size limit."
        }
        val bundleBytes = readBoundedFile(bundle)
        return verify(bundleBytes, policy, clock)
    }

    fun verify(
        bundleBytes: ByteArray,
        policy: JmixTemplateTrustPolicy,
        clock: Clock = Clock.systemUTC(),
    ): JmixVerifiedTemplateCatalog {
        require(bundleBytes.size <= MAX_ARCHIVE_BYTES) {
            "Template catalog bundle exceeds the 64 MiB compressed size limit."
        }
        val entries = readZip(bundleBytes)
        val manifestBytes = entries[MANIFEST_PATH]
            ?: throw JmixTemplateCatalogException("Template catalog is missing $MANIFEST_PATH.")
        val signatureBytes = entries[SIGNATURE_PATH]
            ?: throw JmixTemplateCatalogException("Template catalog is missing $SIGNATURE_PATH.")
        val parsed = parseManifest(manifestBytes)
        enforcePolicy(parsed, policy, clock.instant())
        verifySignature(
            manifestBytes = manifestBytes,
            signatureText = decodeUtf8(signatureBytes, SIGNATURE_PATH).trim(),
            keyText = policy.trustedKeys.getValue(parsed.signingKeyId),
        )
        verifyPayloadInventory(parsed, entries)
        return JmixVerifiedTemplateCatalog(
            manifest = parsed,
            bundleSha256 = sha256(bundleBytes),
            payloads = entries.filterKeys { it != MANIFEST_PATH && it != SIGNATURE_PATH },
        )
    }

    private fun readZip(bundleBytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        val caseFoldedPaths = hashSetOf<String>()
        var entryCount = 0
        var totalUncompressed = 0L
        ZipInputStream(ByteArrayInputStream(bundleBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_ENTRIES) {
                    "Template catalog exceeds the $MAX_ENTRIES-entry safety limit."
                }
                val path = validateArchivePath(entry)
                require(path !in entries) {
                    "Template catalog contains duplicate entry '$path'."
                }
                require(caseFoldedPaths.add(path.lowercase(Locale.ROOT))) {
                    "Template catalog contains a case-colliding entry '$path'."
                }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entryBytes += count
                    totalUncompressed += count
                    require(entryBytes <= MAX_ENTRY_BYTES) {
                        "Template catalog entry '$path' exceeds the 8 MiB safety limit."
                    }
                    require(totalUncompressed <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        "Template catalog exceeds the 128 MiB uncompressed safety limit."
                    }
                    output.write(buffer, 0, count)
                }
                entries[path] = output.toByteArray()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun validateArchivePath(entry: ZipEntry): String {
        val path = entry.name
        require(path.isNotBlank() && '\\' !in path && !path.startsWith("/") && !path.contains('\u0000')) {
            "Unsafe template catalog entry path '$path'."
        }
        val segments = path.split('/').filter(String::isNotEmpty)
        require(segments.isNotEmpty() && segments.none { it == "." || it == ".." }) {
            "Unsafe template catalog entry path '$path'."
        }
        require(segments.all(safePathSegment::matches)) {
            "Template catalog entry path uses unsupported characters: '$path'."
        }
        return segments.joinToString("/")
    }

    private fun parseManifest(bytes: ByteArray): JmixOrganizationTemplateCatalog {
        val manifestText = decodeUtf8(bytes, MANIFEST_PATH)
        rejectDuplicateJsonProperties(manifestText)
        val root = runCatching {
            JsonParser.parseString(manifestText).asJsonObject
        }.getOrElse { failure ->
            throw JmixTemplateCatalogException("Template catalog manifest is not valid JSON.", failure)
        }
        root.requireOnly(
            "schemaVersion",
            "catalogId",
            "catalogVersion",
            "displayName",
            "issuedAt",
            "expiresAt",
            "signingKeyId",
            "templates",
        )
        require(root.requiredInt("schemaVersion") == 1) {
            "Unsupported template catalog schema; expected version 1."
        }
        val catalogId = root.requiredString("catalogId").also(::requireIdentifier)
        val catalogVersion = root.requiredString("catalogVersion").also(::requireVersion)
        val displayName = root.requiredString("displayName").also {
            require(it.isNotBlank() && it.length <= 120) { "Catalog displayName must be 1-120 characters." }
        }
        val issuedAt = root.requiredInstant("issuedAt")
        val expiresAt = root.optionalInstant("expiresAt")
        val signingKeyId = root.requiredString("signingKeyId").also(::requireIdentifier)
        val templates = root.requiredArray("templates").mapIndexed { index, element ->
            parseTemplate(element.asObject("templates[$index]"))
        }
        require(templates.isNotEmpty() && templates.size <= 500) {
            "Template catalog must contain 1-500 templates."
        }
        require(templates.map { it.id }.distinct().size == templates.size) {
            "Template IDs must be unique within a catalog."
        }
        require(templates.map { it.id.lowercase(Locale.ROOT) }.distinct().size == templates.size) {
            "Template IDs must not collide by case."
        }
        require(expiresAt == null || expiresAt > issuedAt) {
            "Catalog expiresAt must be later than issuedAt."
        }
        return JmixOrganizationTemplateCatalog(
            catalogId = catalogId,
            catalogVersion = catalogVersion,
            displayName = displayName,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            signingKeyId = signingKeyId,
            templates = templates,
        )
    }

    private fun parseTemplate(json: JsonObject): JmixOrganizationProjectTemplate {
        json.requireOnly(
            "id",
            "version",
            "name",
            "description",
            "order",
            "baseTemplate",
            "languages",
            "uiKinds",
            "jmixVersions",
            "javaVersions",
            "changes",
        )
        val id = json.requiredString("id").also(::requireIdentifier)
        val version = json.requiredString("version").also(::requireVersion)
        val name = json.requiredString("name").also {
            require(it.isNotBlank() && it.length <= 120) { "Template name must be 1-120 characters." }
        }
        val description = json.requiredString("description").also {
            require(it.length <= 1_000) { "Template description exceeds 1,000 characters." }
        }
        val order = json.requiredInt("order")
        require(order in -100_000..100_000) { "Template order is outside the supported range." }
        val baseTemplate = json.requiredEnum<JmixProjectTemplateKind>("baseTemplate")
        val languages = json.requiredEnumSet<JmixProjectLanguage>("languages")
        val uiKinds = json.requiredEnumSet<JmixProjectUiKind>("uiKinds")
        val jmixVersions = json.requiredStringSet("jmixVersions", 20).onEach(::requireVersion)
        val javaVersions = json.requiredIntSet("javaVersions", 20).onEach {
            require(it in 17..99) { "Unsupported Java version '$it' in template metadata." }
        }
        if (baseTemplate == JmixProjectTemplateKind.ADDON) {
            require(uiKinds == setOf(JmixProjectUiKind.HEADLESS)) {
                "Add-on organization templates must declare only HEADLESS UI compatibility."
            }
        }
        val changes = json.requiredArray("changes").mapIndexed { index, element ->
            parseChange(element.asObject("changes[$index]"))
        }
        require(changes.isNotEmpty() && changes.size <= 5_000) {
            "Organization template '$id' must contain 1-5,000 changes."
        }
        require(changes.map { it.relativePath }.distinct().size == changes.size) {
            "Organization template '$id' contains duplicate target paths."
        }
        require(
            changes.map { it.relativePath.lowercase(Locale.ROOT) }.distinct().size == changes.size
        ) {
            "Organization template '$id' contains case-colliding target paths."
        }
        return JmixOrganizationProjectTemplate(
            id = id,
            version = version,
            name = name,
            description = description,
            order = order,
            baseTemplate = baseTemplate,
            languages = languages,
            uiKinds = uiKinds,
            jmixVersions = jmixVersions,
            javaVersions = javaVersions,
            changes = changes,
        )
    }

    private fun parseChange(json: JsonObject): JmixOrganizationTemplateChange {
        json.requireOnly("path", "action", "sha256", "executable")
        val path = validateGeneratedPath(json.requiredString("path"))
        val action = json.requiredEnum<JmixOrganizationTemplateChangeAction>("action")
        val sha256 = json.optionalString("sha256")
        val executable = json.optionalBoolean("executable") ?: false
        if (action == JmixOrganizationTemplateChangeAction.DELETE) {
            require(sha256 == null && !executable) {
                "DELETE change '$path' cannot declare sha256 or executable."
            }
        } else {
            require(sha256 != null && sha256Pattern.matches(sha256)) {
                "$action change '$path' must declare a lowercase SHA-256 digest."
            }
        }
        return JmixOrganizationTemplateChange(path, action, sha256, executable)
    }

    private fun enforcePolicy(
        manifest: JmixOrganizationTemplateCatalog,
        policy: JmixTemplateTrustPolicy,
        now: Instant,
    ) {
        require(manifest.signingKeyId in policy.trustedKeys) {
            "Catalog signer '${manifest.signingKeyId}' is not trusted."
        }
        require(policy.allowedCatalogIds.isEmpty() || manifest.catalogId in policy.allowedCatalogIds) {
            "Catalog '${manifest.catalogId}' is not allowed by organization policy."
        }
        require(manifest.issuedAt <= now.plus(Duration.ofMinutes(5))) {
            "Catalog '${manifest.catalogId}' was issued in the future."
        }
        require(policy.allowExpiredCatalogs || manifest.expiresAt == null || now < manifest.expiresAt) {
            "Catalog '${manifest.catalogId}' expired at ${manifest.expiresAt}."
        }
        policy.minimumCatalogVersions[manifest.catalogId]?.let { minimum ->
            requireVersion(minimum)
            require(compareVersions(manifest.catalogVersion, minimum) >= 0) {
                "Catalog ${manifest.catalogId}:${manifest.catalogVersion} is below required version $minimum."
            }
        }
        policy.requiredCatalogVersions[manifest.catalogId]?.let { required ->
            requireVersion(required)
            require(manifest.catalogVersion == required) {
                "Catalog ${manifest.catalogId}:${manifest.catalogVersion} does not match required version $required."
            }
        }
    }

    private fun verifySignature(
        manifestBytes: ByteArray,
        signatureText: String,
        keyText: String,
    ) {
        val signatureBytes = decodeBase64(signatureText, "catalog signature")
        require(signatureBytes.size == 64) { "Ed25519 catalog signature must be 64 bytes." }
        val publicKeyBytes = decodeBase64(keyText.trim(), "trusted public key")
        val publicKey: PublicKey = runCatching {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        }.getOrElse { failure ->
            throw JmixTemplateCatalogException("Trusted Ed25519 public key is invalid.", failure)
        }
        val valid = runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(manifestBytes)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
        require(valid) { "Template catalog signature verification failed." }
    }

    private fun verifyPayloadInventory(
        manifest: JmixOrganizationTemplateCatalog,
        entries: Map<String, ByteArray>,
    ) {
        val expected = linkedMapOf<String, String>()
        manifest.templates.forEach { template ->
            template.changes.filter { it.action != JmixOrganizationTemplateChangeAction.DELETE }.forEach { change ->
                expected["templates/${template.id}/${change.relativePath}"] = requireNotNull(change.sha256)
            }
        }
        val actualPayloadPaths = entries.keys - setOf(MANIFEST_PATH, SIGNATURE_PATH)
        require(actualPayloadPaths == expected.keys) {
            val missing = expected.keys - actualPayloadPaths
            val undeclared = actualPayloadPaths - expected.keys
            "Template payload inventory mismatch; missing=$missing, undeclared=$undeclared."
        }
        expected.forEach { (path, digest) ->
            require(sha256(entries.getValue(path)) == digest) {
                "Template payload digest mismatch for '$path'."
            }
        }
    }

    private fun decodeUtf8(
        bytes: ByteArray,
        label: String,
    ): String = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse {
        throw JmixTemplateCatalogException("$label is not valid UTF-8.")
    }

    private fun decodeBase64(
        value: String,
        label: String,
    ): ByteArray = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrElse {
        throw JmixTemplateCatalogException("$label is not valid Base64.")
    }

    private fun validateGeneratedPath(path: String): String {
        require(path.isNotBlank() && '\\' !in path && !path.startsWith("/") && !path.contains('\u0000')) {
            "Unsafe organization template target path '$path'."
        }
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe organization template target path '$path'."
        }
        require(segments.all(safePathSegment::matches)) {
            "Organization template target path uses unsupported characters: '$path'."
        }
        require(segments.all(::isPortablePathSegment)) {
            "Organization template target path is not portable across supported operating systems: '$path'."
        }
        require(segments.first().lowercase(Locale.ROOT) !in BLOCKED_TOP_LEVEL_PATHS) {
            "Organization templates cannot write IDE, VCS, or Gradle cache internals: '$path'."
        }
        require(path.length <= 1_024) { "Organization template target path is too long." }
        return path
    }

    private fun isPortablePathSegment(segment: String): Boolean {
        if (
            segment.length > 255 ||
            segment.startsWith(' ') ||
            segment.endsWith(' ') ||
            segment.endsWith('.')
        ) {
            return false
        }
        return segment.substringBefore('.').uppercase(Locale.ROOT) !in WINDOWS_RESERVED_NAMES
    }

    private fun requireIdentifier(value: String) {
        require(identifierPattern.matches(value)) {
            "Catalog and template identifiers must be lowercase DNS-style identifiers."
        }
        require(value.substringBefore('.').uppercase(Locale.ROOT) !in WINDOWS_RESERVED_NAMES) {
            "Catalog and template identifiers must be portable across supported operating systems."
        }
    }

    private fun requireVersion(value: String) {
        require(versionPattern.matches(value)) {
            "Catalog and template versions must use a numeric dotted version."
        }
    }

    private fun compareVersions(
        left: String,
        right: String,
    ): Int {
        data class ComparableVersion(
            val core: List<Int>,
            val preRelease: List<String>?,
        )

        fun parse(value: String): ComparableVersion {
            val withoutBuild = value.substringBefore('+')
            val coreText = withoutBuild.substringBefore('-')
            val preRelease = withoutBuild.substringAfter('-', "")
                .takeIf(String::isNotEmpty)
                ?.split('.')
            return ComparableVersion(
                core = coreText.split('.').map(String::toInt),
                preRelease = preRelease,
            )
        }

        val leftVersion = parse(left)
        val rightVersion = parse(right)
        val leftParts = leftVersion.core
        val rightParts = rightVersion.core
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(
                rightParts.getOrElse(index) { 0 },
            )
            if (comparison != 0) return comparison
        }
        val leftPre = leftVersion.preRelease
        val rightPre = rightVersion.preRelease
        if (leftPre == null && rightPre == null) return 0
        if (leftPre == null) return 1
        if (rightPre == null) return -1
        for (index in 0 until maxOf(leftPre.size, rightPre.size)) {
            val leftPart = leftPre.getOrNull(index) ?: return -1
            val rightPart = rightPre.getOrNull(index) ?: return 1
            val leftNumber = leftPart.toIntOrNull()
            val rightNumber = rightPart.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftPart.compareTo(rightPart)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun readBoundedFile(path: Path): ByteArray {
        val output = ByteArrayOutputStream()
        Files.newByteChannel(
            path,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_ARCHIVE_BYTES) {
                        "Template catalog bundle exceeds the 64 MiB compressed size limit."
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
        return output.toByteArray()
    }

    private fun rejectDuplicateJsonProperties(json: String) {
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT

            fun readValue() {
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        val names = hashSetOf<String>()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            require(names.add(name)) {
                                "Template catalog JSON contains duplicate property '$name'."
                            }
                            readValue()
                        }
                        reader.endObject()
                    }

                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) readValue()
                        reader.endArray()
                    }

                    JsonToken.STRING -> reader.nextString()
                    JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> throw JmixTemplateCatalogException("Template catalog JSON is structurally invalid.")
                }
            }

            readValue()
            require(reader.peek() == JsonToken.END_DOCUMENT) {
                "Template catalog JSON contains trailing content."
            }
        }
    }

    private fun JsonElement.asObject(label: String): JsonObject {
        require(isJsonObject) { "$label must be an object." }
        return asJsonObject
    }

    private fun JsonObject.requireOnly(vararg allowed: String) {
        val unknown = keySet() - allowed.toSet()
        require(unknown.isEmpty()) { "Unknown template catalog properties: ${unknown.sorted()}." }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw JmixTemplateCatalogException("Manifest property '$name' must be a string.")

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Manifest property '$name' must be a string or null."
        }
        return value.asString
    }

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeIf {
            it.isJsonPrimitive &&
                it.asJsonPrimitive.isNumber &&
                Regex("-?(?:0|[1-9][0-9]*)").matches(it.asString)
        }?.asString?.toIntOrNull()
            ?: throw JmixTemplateCatalogException("Manifest property '$name' must be an integer.")

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            "Manifest property '$name' must be a boolean or null."
        }
        return value.asBoolean
    }

    private fun JsonObject.requiredArray(name: String): JsonArray =
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: throw JmixTemplateCatalogException("Manifest property '$name' must be an array.")

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T {
        val value = requiredString(name)
        return enumValues<T>().singleOrNull { it.name == value }
            ?: throw JmixTemplateCatalogException(
                "Manifest property '$name' must be one of ${enumValues<T>().joinToString { it.name }}.",
            )
    }

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnumSet(name: String): Set<T> {
        val parsed = requiredArray(name).mapIndexed { index, element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                "Manifest property '$name[$index]' must be a string."
            }
            val value = element.asString
            enumValues<T>().singleOrNull { it.name == value }
                ?: throw JmixTemplateCatalogException(
                    "Manifest property '$name[$index]' must be one of ${enumValues<T>().joinToString { it.name }}.",
                )
        }
        require(parsed.distinct().size == parsed.size) {
            "Manifest property '$name' cannot contain duplicate values."
        }
        val values = parsed.toSet()
        require(values.isNotEmpty()) { "Manifest property '$name' cannot be empty." }
        return values
    }

    private fun JsonObject.requiredStringSet(
        name: String,
        maximum: Int,
    ): Set<String> {
        val values = requiredArray(name).mapIndexed { index, element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                "Manifest property '$name[$index]' must be a string."
            }
            element.asString
        }
        require(values.isNotEmpty() && values.size <= maximum && values.distinct().size == values.size) {
            "Manifest property '$name' must contain 1-$maximum unique values."
        }
        return values.toSet()
    }

    private fun JsonObject.requiredIntSet(
        name: String,
        maximum: Int,
    ): Set<Int> {
        val values = requiredArray(name).mapIndexed { index, element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                "Manifest property '$name[$index]' must be an integer."
            }
            element.asString.takeIf { Regex("-?(?:0|[1-9][0-9]*)").matches(it) }
                ?.toIntOrNull() ?: run {
                    throw JmixTemplateCatalogException(
                        "Manifest property '$name[$index]' must be an integer.",
                    )
                }
        }
        require(values.isNotEmpty() && values.size <= maximum && values.distinct().size == values.size) {
            "Manifest property '$name' must contain 1-$maximum unique values."
        }
        return values.toSet()
    }

    private fun JsonObject.requiredInstant(name: String): Instant =
        runCatching { Instant.parse(requiredString(name)) }.getOrElse {
            throw JmixTemplateCatalogException("Manifest property '$name' must be an ISO-8601 instant.")
        }

    private fun JsonObject.optionalInstant(name: String): Instant? =
        optionalString(name)?.let { value ->
            runCatching { Instant.parse(value) }.getOrElse {
                throw JmixTemplateCatalogException("Manifest property '$name' must be an ISO-8601 instant.")
            }
        }

    private val WINDOWS_RESERVED_NAMES = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { index ->
            add("COM$index")
            add("LPT$index")
        }
    }
    private val BLOCKED_TOP_LEVEL_PATHS = setOf(".git", ".idea", ".gradle")
}

data class JmixCachedTemplateCatalog(
    val catalogId: String,
    val catalogVersion: String,
    val bundleSha256: String,
    val bundlePath: Path,
)

/**
 * Immutable, content-address-checked catalog cache. Cached bundles are reverified on every open,
 * so disk modification, trust-policy changes, key rotation, expiry and rollback policy take effect
 * even when the IDE is offline.
 */
class JmixTemplateCatalogCache(
    cacheRoot: Path,
) {
    private val root = cacheRoot.toAbsolutePath().normalize()

    init {
        rejectSymlink(root)
    }

    fun install(
        bundle: Path,
        policy: JmixTemplateTrustPolicy,
        expectedBundleSha256: String? = null,
        clock: Clock = Clock.systemUTC(),
    ): JmixCachedTemplateCatalog {
        val verified = JmixTemplateCatalogVerifier.verify(bundle, policy, clock)
        if (expectedBundleSha256 != null) {
            require(HEX_64.matches(expectedBundleSha256) && verified.bundleSha256 == expectedBundleSha256) {
                "Template catalog bundle does not match the pinned SHA-256 digest."
            }
        }
        val directory = safeDirectory(verified.manifest.catalogId, verified.manifest.catalogVersion)
        rejectSymlink(directory)
        Files.createDirectories(directory)
        rejectSymlink(directory)
        val destination = directory.resolve("${verified.bundleSha256}.jmix-template-catalog")
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(destination) && Files.isRegularFile(destination)) {
                "Cached template catalog target is not a regular file."
            }
            val cached = JmixTemplateCatalogVerifier.verify(destination, policy, clock)
            require(cached.bundleSha256 == verified.bundleSha256) {
                "Cached template catalog content does not match its immutable cache key."
            }
            return verified.toCached(destination)
        }
        val incoming = directory.resolve(".incoming-${UUID.randomUUID()}")
        try {
            Files.copy(bundle, incoming)
            require(JmixTemplateCatalogVerifier.verify(incoming, policy, clock).bundleSha256 == verified.bundleSha256) {
                "Template catalog changed while it was copied into the cache."
            }
            try {
                Files.move(incoming, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(incoming, destination)
            }
        } finally {
            Files.deleteIfExists(incoming)
        }
        return verified.toCached(destination)
    }

    fun open(
        catalogId: String,
        catalogVersion: String,
        bundleSha256: String,
        policy: JmixTemplateTrustPolicy,
        clock: Clock = Clock.systemUTC(),
    ): JmixVerifiedTemplateCatalog {
        require(HEX_64.matches(bundleSha256)) { "Cached bundle SHA-256 is invalid." }
        val path = safeDirectory(catalogId, catalogVersion)
            .resolve("$bundleSha256.jmix-template-catalog")
        require(path.startsWith(root) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Requested template catalog is not available offline."
        }
        rejectSymlink(path)
        val verified = JmixTemplateCatalogVerifier.verify(path, policy, clock)
        require(
            verified.manifest.catalogId == catalogId &&
                verified.manifest.catalogVersion == catalogVersion &&
                verified.bundleSha256 == bundleSha256
        ) {
            "Cached template catalog identity does not match the requested immutable coordinates."
        }
        return verified
    }

    private fun safeDirectory(
        catalogId: String,
        catalogVersion: String,
    ): Path {
        require(CACHE_SEGMENT.matches(catalogId) && CACHE_SEGMENT.matches(catalogVersion)) {
            "Unsafe template catalog cache coordinates."
        }
        val directory = root.resolve(catalogId).resolve(catalogVersion).normalize()
        require(directory.startsWith(root)) { "Template catalog cache path escapes its root." }
        return directory
    }

    private fun rejectSymlink(path: Path) {
        var cursor: Path? = path
        val stop = root.parent
        while (cursor != null) {
            require(!Files.isSymbolicLink(cursor)) {
                "Template catalog cache path crosses a symbolic link: $cursor"
            }
            if (cursor == stop) break
            cursor = cursor.parent
        }
    }

    private fun JmixVerifiedTemplateCatalog.toCached(path: Path): JmixCachedTemplateCatalog =
        JmixCachedTemplateCatalog(
            catalogId = manifest.catalogId,
            catalogVersion = manifest.catalogVersion,
            bundleSha256 = bundleSha256,
            bundlePath = path,
        )

    companion object {
        private val HEX_64 = Regex("[0-9a-f]{64}")
        private val CACHE_SEGMENT = Regex("[A-Za-z0-9.+-]{1,128}")
    }
}

data class JmixTemplateCatalogSource(
    val uri: URI,
    val expectedBundleSha256: String,
) {
    init {
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null
        ) {
            "Organization template catalog source must be an HTTPS URL without credentials, query, or fragment."
        }
        require(Regex("[0-9a-f]{64}").matches(expectedBundleSha256)) {
            "Organization template catalog source requires a lowercase pinned SHA-256 digest."
        }
    }
}

/**
 * Bounded HTTPS refresh path. Offline operation never calls this client: callers open immutable
 * cache coordinates directly through [JmixTemplateCatalogCache.open].
 */
class JmixTemplateCatalogClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    fun refresh(
        source: JmixTemplateCatalogSource,
        cache: JmixTemplateCatalogCache,
        policy: JmixTemplateTrustPolicy,
        clock: Clock = Clock.systemUTC(),
    ): JmixCachedTemplateCatalog {
        val request = HttpRequest.newBuilder(source.uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.jmix-workbench.template-catalog+zip")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw JmixTemplateCatalogException(
                "Template catalog download failed with HTTP ${response.statusCode()}.",
            )
        }
        response.headers().firstValueAsLong("Content-Length").ifPresent { contentLength ->
            if (contentLength > 64L * 1024L * 1024L) {
                response.body().close()
                throw JmixTemplateCatalogException(
                    "Template catalog download exceeds the 64 MiB safety limit.",
                )
            }
        }
        val temporary = Files.createTempFile("jmix-template-catalog-", ".zip")
        try {
            response.body().use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 64L * 1024L * 1024L) {
                            "Template catalog download exceeds the 64 MiB safety limit."
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return cache.install(
                bundle = temporary,
                policy = policy,
                expectedBundleSha256 = source.expectedBundleSha256,
                clock = clock,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private const val MAX_EXPANDED_FILE_BYTES = 8 * 1024 * 1024

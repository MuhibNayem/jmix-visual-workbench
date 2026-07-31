package org.jmixworkbench.project

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

fun interface JmixTemplateOverlayProgress {
    fun checkCanceled()

    companion object {
        val NONE = JmixTemplateOverlayProgress { }
    }
}

data class JmixTemplateOverlayPlan(
    val sourceRoot: Path,
    val sourceSha256: String,
    val changes: List<JmixOrganizationTemplateChangeDraft>,
    val previews: List<JmixTemplateOverlayPreview>,
    val ignoredPaths: List<String>,
) {
    val addedCount: Int =
        changes.count { it.action == JmixOrganizationTemplateChangeAction.ADD }
    val replacedCount: Int =
        changes.count { it.action == JmixOrganizationTemplateChangeAction.REPLACE }
    val deletedCount: Int =
        changes.count { it.action == JmixOrganizationTemplateChangeAction.DELETE }
    val binaryCount: Int =
        changes.count { it.payloadKind == JmixOrganizationTemplatePayloadKind.BINARY }

    fun matchesReviewedSource(current: JmixTemplateOverlayPlan): Boolean =
        sourceRoot == current.sourceRoot &&
            sourceSha256 == current.sourceSha256 &&
            changes == current.changes

    fun toTemplateDraft(
        id: String,
        version: String,
        name: String,
        description: String,
        order: Int,
        request: JmixProjectTemplateRequest,
    ): JmixOrganizationProjectTemplateDraft =
        JmixOrganizationProjectTemplateDraft(
            id = id,
            version = version,
            name = name,
            description = description,
            order = order,
            baseTemplate = request.templateKind,
            languages = setOf(request.language),
            uiKinds = setOf(
                if (request.templateKind == JmixProjectTemplateKind.ADDON) {
                    JmixProjectUiKind.HEADLESS
                } else {
                    request.uiKind
                },
            ),
            jmixVersions = setOf(request.jmixVersion),
            javaVersions = setOf(request.javaVersion),
            changes = changes,
        )
}

class JmixTemplateOverlayPreview(
    val relativePath: String,
    val action: JmixOrganizationTemplateChangeAction,
    before: ByteArray?,
    private val afterChange: JmixOrganizationTemplateChangeDraft?,
    val payloadKind: JmixOrganizationTemplatePayloadKind?,
    val beforeExecutable: Boolean,
    val afterExecutable: Boolean,
) {
    private val beforeBytes = before?.copyOf()

    val before: ByteArray?
        get() = beforeBytes?.copyOf()

    val after: ByteArray?
        get() = afterChange?.content

    val beforeSize: Int = beforeBytes?.size ?: -1
    val afterSize: Int = afterChange?.contentSize ?: -1
}

/**
 * Compares a customized generated project against its certified base without following links,
 * reading build output, or silently discarding binary assets.
 */
object JmixTemplateOverlayPlanner {
    private const val MAX_FILES = 10_000
    private const val MAX_CHANGES = 5_000
    private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 120L * 1024L * 1024L

    private val excludedDirectoryNames = setOf(
        ".git",
        ".gradle",
        ".idea",
        ".jmix-workbench",
        "build",
        "node_modules",
        "out",
    )
    private val excludedFileNames = setOf(
        ".DS_Store",
        "Thumbs.db",
    )

    fun plan(
        customizedProjectRoot: Path,
        request: JmixProjectTemplateRequest,
        resourceLoader: JmixProjectResourceLoader = JmixProjectResourceLoader { resource ->
            JmixTemplateOverlayPlanner::class.java.getResourceAsStream(resource)
        },
        progress: JmixTemplateOverlayProgress = JmixTemplateOverlayProgress.NONE,
    ): JmixTemplateOverlayPlan {
        val root = customizedProjectRoot.toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Customized template source must be an existing real directory."
        }
        val base = JmixProjectTemplateGenerator.generate(request)
        val protectedResources = base.resources.associate { resource ->
            val bytes = requireNotNull(resourceLoader.open(resource.classpathResource)) {
                "Bundled project resource is missing: ${resource.classpathResource}"
            }.use { it.readBounded(resource.relativePath) }
            resource.relativePath to SourceFileSnapshot(
                content = bytes,
                executable = resource.executable,
            )
        }
        val snapshots = linkedMapOf<String, SourceFileSnapshot>()
        val caseFoldedPaths = hashSetOf<String>()
        val ignored = mutableListOf<String>()
        var totalBytes = 0L

        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    progress.checkCanceled()
                    require(!attributes.isSymbolicLink && !Files.isSymbolicLink(directory)) {
                        "Template source contains a symbolic-link directory: ${root.relativize(directory)}"
                    }
                    if (directory != root) {
                        val relative = portableRelative(root, directory)
                        if (directory.fileName.toString() in excludedDirectoryNames) {
                            ignored += relative
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    progress.checkCanceled()
                    val relative = portableRelative(root, file)
                    if (file.fileName.toString() in excludedFileNames) {
                        ignored += relative
                        return FileVisitResult.CONTINUE
                    }
                    JmixOrganizationTemplatePathPolicy.validate(relative)
                    require(!attributes.isSymbolicLink && !Files.isSymbolicLink(file)) {
                        "Template source contains a symbolic link: $relative"
                    }
                    require(attributes.isRegularFile) {
                        "Template source contains a non-regular file: $relative"
                    }
                    require(snapshots.size < MAX_FILES) {
                        "Template source exceeds the $MAX_FILES-file safety limit."
                    }
                    require(caseFoldedPaths.add(relative.lowercase(Locale.ROOT))) {
                        "Template source contains case-colliding path '$relative'."
                    }
                    require(attributes.size() in 0..MAX_FILE_BYTES) {
                        "Template source file '$relative' exceeds the 8 MiB safety limit."
                    }
                    val executableBefore = Files.isExecutable(file)
                    val content = readStableFile(
                        file = file,
                        relative = relative,
                        expected = attributes,
                        progress = progress,
                    )
                    val executableAfter = Files.isExecutable(file)
                    require(executableBefore == executableAfter) {
                        "Template source file permissions changed during comparison: '$relative'."
                    }
                    totalBytes += content.size
                    require(totalBytes <= MAX_TOTAL_BYTES) {
                        "Template source exceeds the 120 MiB safety limit."
                    }
                    snapshots[relative] = SourceFileSnapshot(
                        content = content,
                        executable = executableAfter,
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )

        protectedResources.forEach { (path, expected) ->
            val actual = snapshots.remove(path)
                ?: throw JmixTemplateCatalogException(
                    "Customized project is missing protected bundled resource '$path'.",
                )
            require(
                actual.content.contentEquals(expected.content) &&
                    actual.executable == expected.executable
            ) {
                "Customized project changed protected bundled resource '$path'."
            }
        }

        val baseText = base.files.associate {
            it.relativePath to SourceFileSnapshot(
                content = it.content.toByteArray(StandardCharsets.UTF_8),
                executable = it.executable,
            )
        }
        val baseBinary = base.binaryFiles.associate {
            it.relativePath to SourceFileSnapshot(it.content, it.executable)
        }
        val basePaths = baseText.keys + baseBinary.keys
        val changes = mutableListOf<JmixOrganizationTemplateChangeDraft>()
        val previews = mutableListOf<JmixTemplateOverlayPreview>()

        snapshots.toSortedMap().forEach { (path, actual) ->
            val expected = baseText[path] ?: baseBinary[path]
            if (expected == null) {
                val change = actual.toDraft(path, JmixOrganizationTemplateChangeAction.ADD)
                changes += change
                previews += change.toPreview(before = null, after = actual)
            } else if (
                !actual.content.contentEquals(expected.content) ||
                actual.executable != expected.executable
            ) {
                val change = actual.toDraft(path, JmixOrganizationTemplateChangeAction.REPLACE)
                changes += change
                previews += change.toPreview(before = expected, after = actual)
            }
        }
        (basePaths - snapshots.keys).sorted().forEach { missing ->
            val change = JmixOrganizationTemplateChangeDraft(
                relativePath = missing,
                action = JmixOrganizationTemplateChangeAction.DELETE,
            )
            changes += change
            previews += change.toPreview(
                before = requireNotNull(baseText[missing] ?: baseBinary[missing]),
                after = null,
            )
        }
        require(changes.isNotEmpty()) {
            "Customized project does not differ from the selected certified base template."
        }
        require(changes.size <= MAX_CHANGES) {
            "Customized project requires ${changes.size} overlay changes; the signed catalog limit is $MAX_CHANGES."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        snapshots.toSortedMap().forEach { (path, snapshot) ->
            digest.update(path.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(sha256(snapshot.content).toByteArray(StandardCharsets.US_ASCII))
            digest.update(if (snapshot.executable) 1 else 0)
        }
        return JmixTemplateOverlayPlan(
            sourceRoot = root,
            sourceSha256 = HexFormat.of().formatHex(digest.digest()),
            changes = changes.sortedBy(JmixOrganizationTemplateChangeDraft::relativePath),
            previews = previews.sortedBy(JmixTemplateOverlayPreview::relativePath),
            ignoredPaths = ignored.sorted(),
        )
    }

    private data class SourceFileSnapshot(
        val content: ByteArray,
        val executable: Boolean,
    ) {
        fun toDraft(
            path: String,
            action: JmixOrganizationTemplateChangeAction,
        ): JmixOrganizationTemplateChangeDraft =
            JmixOrganizationTemplateChangeDraft(
                relativePath = path,
                action = action,
                content = content,
                payloadKind = if (isUtf8Text(content)) {
                    JmixOrganizationTemplatePayloadKind.TEXT
                } else {
                    JmixOrganizationTemplatePayloadKind.BINARY
                },
                executable = executable,
            )
    }

    private fun portableRelative(
        root: Path,
        path: Path,
    ): String = root.relativize(path).joinToString("/") { it.toString() }

    private fun java.io.InputStream.readBounded(label: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_FILE_BYTES) {
                "Bundled project resource '$label' exceeds the 8 MiB safety limit."
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readStableFile(
        file: Path,
        relative: String,
        expected: BasicFileAttributes,
        progress: JmixTemplateOverlayProgress,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        Files.newByteChannel(
            file,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                progress.checkCanceled()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= MAX_FILE_BYTES) {
                    "Template source file '$relative' exceeds the 8 MiB safety limit."
                }
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        val after = Files.readAttributes(
            file,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(
            after.isRegularFile &&
                !after.isSymbolicLink &&
                expected.size() == after.size() &&
                expected.lastModifiedTime() == after.lastModifiedTime() &&
                (expected.fileKey() == null || expected.fileKey() == after.fileKey())
        ) {
            "Template source file changed during comparison: '$relative'."
        }
        return output.toByteArray()
    }

    private fun isUtf8Text(content: ByteArray): Boolean {
        if (content.any { it == 0.toByte() }) return false
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
        }.isSuccess
    }

    private fun sha256(content: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

    private fun JmixOrganizationTemplateChangeDraft.toPreview(
        before: SourceFileSnapshot?,
        after: SourceFileSnapshot?,
    ): JmixTemplateOverlayPreview =
        JmixTemplateOverlayPreview(
            relativePath = relativePath,
            action = action,
            before = before?.content,
            afterChange = if (after == null) null else this,
            payloadKind = payloadKind,
            beforeExecutable = before?.executable ?: false,
            afterExecutable = after?.executable ?: false,
        )
}

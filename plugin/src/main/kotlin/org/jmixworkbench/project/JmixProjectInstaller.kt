package org.jmixworkbench.project

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat

fun interface JmixProjectResourceLoader {
    fun open(classpathResource: String): InputStream?
}

internal fun interface JmixProjectInstallProbe {
    fun afterFileInstalled(relativePath: String)

    companion object {
        val NONE = JmixProjectInstallProbe { }
    }
}

data class JmixProjectInstallResult(
    val installedFiles: List<String>,
)

/**
 * Create-only project installation boundary used by the native new-project wizard.
 *
 * All content is staged before the first target mutation. Existing targets, symlinked parents and
 * traversal paths are rejected. If installation fails, only files and directories created by this
 * invocation are removed; pre-existing IntelliJ metadata remains untouched.
 */
object JmixProjectInstaller {
    private const val WRAPPER_JAR_RESOURCE = "/project-template/gradle/wrapper/gradle-wrapper.jar"
    private const val WRAPPER_JAR_SHA256 =
        "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

    fun install(
        projectRoot: Path,
        project: GeneratedJmixProject,
        resourceLoader: JmixProjectResourceLoader = JmixProjectResourceLoader { resource ->
            JmixProjectInstaller::class.java.getResourceAsStream(resource)
        },
    ): JmixProjectInstallResult = install(
        projectRoot = projectRoot,
        project = project,
        resourceLoader = resourceLoader,
        probe = JmixProjectInstallProbe.NONE,
    )

    internal fun install(
        projectRoot: Path,
        project: GeneratedJmixProject,
        resourceLoader: JmixProjectResourceLoader,
        probe: JmixProjectInstallProbe,
    ): JmixProjectInstallResult {
        val root = projectRoot.toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Project root must be an existing real directory: $root"
        }
        require(!Files.isSymbolicLink(root)) {
            "Project root cannot be a symbolic link: $root"
        }
        val targets = (project.files.map { it.relativePath } + project.resources.map { it.relativePath })
            .associateWith { relativePath -> resolveTarget(root, relativePath) }
        require(targets.size == project.files.size + project.resources.size) {
            "Generated project contains duplicate paths."
        }
        targets.forEach { (relativePath, target) ->
            rejectSymlinkedParents(root, target.parent)
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                "Refusing to overwrite existing project path '$relativePath'."
            }
        }

        val staging = Files.createTempDirectory(
            requireNotNull(root.parent) { "Project root has no parent directory." },
            ".${root.fileName}-jmix-template-",
        )
        val installedFiles = mutableListOf<Path>()
        val createdDirectories = mutableListOf<Path>()
        return try {
            stageTextFiles(staging, project.files)
            stageResources(staging, project.resources, resourceLoader)
            verifyWrapper(staging.resolve("gradle/wrapper/gradle-wrapper.jar"))

            val executablePaths = (
                project.files.filter(GeneratedProjectFile::executable).map(GeneratedProjectFile::relativePath) +
                    project.resources.filter(GeneratedProjectResource::executable)
                        .map(GeneratedProjectResource::relativePath)
                ).toSet()
            targets.toSortedMap().forEach { (relativePath, target) ->
                createMissingDirectories(root, requireNotNull(target.parent), createdDirectories)
                rejectSymlinkedParents(root, target.parent)
                require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    "Project path appeared during installation: '$relativePath'."
                }
                moveCreateOnly(staging.resolve(relativePath), target)
                installedFiles.add(target)
                if (relativePath in executablePaths) {
                    makeExecutable(target)
                }
                probe.afterFileInstalled(relativePath)
            }
            JmixProjectInstallResult(
                installedFiles = targets.keys.sorted(),
            )
        } catch (failure: Throwable) {
            installedFiles.asReversed().forEach { installed ->
                runCatching { Files.deleteIfExists(installed) }
            }
            createdDirectories.asReversed().forEach { directory ->
                runCatching {
                    Files.newDirectoryStream(directory).use { contents ->
                        if (!contents.iterator().hasNext()) {
                            Files.deleteIfExists(directory)
                        }
                    }
                }
            }
            throw failure
        } finally {
            deleteTree(staging)
        }
    }

    private fun stageTextFiles(
        staging: Path,
        files: List<GeneratedProjectFile>,
    ) {
        files.forEach { generated ->
            val target = resolveTarget(staging, generated.relativePath)
            Files.createDirectories(target.parent)
            Files.newOutputStream(target).use { output ->
                output.write(generated.content.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    private fun stageResources(
        staging: Path,
        resources: List<GeneratedProjectResource>,
        resourceLoader: JmixProjectResourceLoader,
    ) {
        resources.forEach { generated ->
            val target = resolveTarget(staging, generated.relativePath)
            Files.createDirectories(target.parent)
            val input = requireNotNull(resourceLoader.open(generated.classpathResource)) {
                "Bundled project resource is missing: ${generated.classpathResource}"
            }
            input.use { source ->
                Files.newOutputStream(target).use(source::copyTo)
            }
        }
    }

    private fun verifyWrapper(wrapperJar: Path) {
        require(Files.isRegularFile(wrapperJar, LinkOption.NOFOLLOW_LINKS)) {
            "Bundled Gradle wrapper JAR was not staged."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(wrapperJar).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = HexFormat.of().formatHex(digest.digest())
        require(actual == WRAPPER_JAR_SHA256) {
            "Bundled Gradle wrapper JAR integrity check failed."
        }
    }

    private fun resolveTarget(
        root: Path,
        relativePath: String,
    ): Path {
        require(relativePath.isNotBlank() && '\\' !in relativePath) {
            "Invalid generated project path '$relativePath'."
        }
        val relative = Path.of(relativePath)
        require(!relative.isAbsolute && relative.none { it.toString() == ".." }) {
            "Generated project path escapes its root: '$relativePath'."
        }
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root) && target != root) {
            "Generated project path escapes its root: '$relativePath'."
        }
        return target
    }

    private fun rejectSymlinkedParents(
        root: Path,
        parent: Path,
    ) {
        var cursor: Path? = parent
        while (cursor != null && cursor.startsWith(root) && cursor != root) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) {
                    "Generated project path crosses a symbolic link: $cursor"
                }
                require(Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    "Generated project parent is not a directory: $cursor"
                }
            }
            cursor = cursor.parent
        }
    }

    private fun createMissingDirectories(
        root: Path,
        directory: Path,
        createdDirectories: MutableList<Path>,
    ) {
        val missing = generateSequence(directory) { current ->
            current.parent?.takeIf { it.startsWith(root) && it != root }
        }.takeWhile { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.toList().asReversed()
        missing.forEach { path ->
            Files.createDirectory(path)
            createdDirectories.add(path)
        }
    }

    private fun moveCreateOnly(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun makeExecutable(path: Path) {
        runCatching {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            permissions += PosixFilePermission.OWNER_EXECUTE
            permissions += PosixFilePermission.GROUP_EXECUTE
            permissions += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }
}

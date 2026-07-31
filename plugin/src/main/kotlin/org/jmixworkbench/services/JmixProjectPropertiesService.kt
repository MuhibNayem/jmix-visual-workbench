package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.EvidenceConfidence
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.static.AddOnEvidence
import org.jmixworkbench.discovery.static.GradleConfigParser
import org.jmixworkbench.discovery.static.GradleTextInput
import java.io.StringReader
import java.util.Properties

/**
 * Builds a credential-safe, revision-bound inventory for the Project
 * Properties workspace.
 *
 * The inventory is deliberately derived from IntelliJ-owned module/content
 * roots. It does not recursively walk the repository on every open, execute
 * Gradle scripts or expose datasource secrets to JCEF.
 */
@Service(Service.Level.PROJECT)
class JmixProjectPropertiesService(
    private val project: Project,
) {
    fun inspect(): JmixProjectPropertiesWorkspace {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val resolver = ProjectFileResolver.getInstance(project)
        val files = linkedMapOf<String, VirtualFile>()

        graph.modules.forEach { module ->
            MODULE_CONFIGURATION_FILES.forEach { name ->
                resolveChild(resolver, module.moduleRoot, name)?.let { file ->
                    files.putIfAbsent(requireNotNull(resolver.locatorPath(file)), file)
                }
            }
            resolveChild(resolver, module.moduleRoot, "gradle")
                ?.takeIf(VirtualFile::isDirectory)
                ?.findChild("libs.versions.toml")
                ?.let { file ->
                    files.putIfAbsent(requireNotNull(resolver.locatorPath(file)), file)
                }
            module.sourceRoots
                .asSequence()
                .filter { root ->
                    root.kind == ApplicationGraphSourceRootKind.RESOURCES &&
                        root.sourceSetId.equals("main", ignoreCase = true)
                }
                .forEach { root ->
                    resolveDirectory(resolver, root.relativePath)?.let { directory ->
                        directory.children
                            .asSequence()
                            .filter { child ->
                                !child.isDirectory &&
                                    APPLICATION_CONFIG_FILE.matches(child.name)
                            }
                            .forEach { file ->
                                files.putIfAbsent(requireNotNull(resolver.locatorPath(file)), file)
                            }
                        directory.findChild("config")
                            ?.takeIf(VirtualFile::isDirectory)
                            ?.children
                            ?.asSequence()
                            ?.filter { child ->
                                !child.isDirectory &&
                                    APPLICATION_CONFIG_FILE.matches(child.name)
                            }
                            ?.forEach { file ->
                                files.putIfAbsent(requireNotNull(resolver.locatorPath(file)), file)
                            }
                    }
                }
        }

        // Root build metadata can govern all modules without belonging to an
        // imported source root. Resolve these fixed paths without a broad scan.
        ROOT_CONFIGURATION_FILES.forEach { path ->
            resolver.resolveFile(path)?.file?.let { files.putIfAbsent(path, it) }
        }

        val loaded = files.entries.mapNotNull { (relativePath, file) ->
            runCatching {
                val content = ProjectSourceText.read(file)
                ProjectPropertiesSource(
                    relativePath = relativePath,
                    file = file,
                    content = content,
                    locator = SourceLocator(
                        relativePath = relativePath,
                        revisionFingerprint = CanonicalDiscoveryJson.sha256(content),
                    ),
                )
            }.getOrNull()
        }

        val gradleInputs = loaded
            .filter { source -> source.file.name in GRADLE_INPUT_FILES }
            .map { source -> GradleTextInput(source.relativePath, source.content) }
        val gradle = GradleConfigParser().parse(gradleInputs)
        val propertyIssues = mutableListOf<JmixProjectPropertiesIssue>()
        val profiles = loaded
            .filter { source -> APPLICATION_PROPERTIES_FILE.matches(source.file.name) }
            .mapNotNull { source ->
                runCatching {
                    parsePropertiesProfile(
                        relativePath = source.relativePath,
                        content = source.content,
                        locator = source.locator,
                    )
                }.getOrElse { failure ->
                    propertyIssues += JmixProjectPropertiesIssue(
                        code = "JVW-PROJECT-PROPERTIES-MALFORMED",
                        message =
                            "${source.relativePath} is not a valid Java properties document: " +
                                (failure.message ?: failure::class.java.simpleName),
                        relativePath = source.relativePath,
                    )
                    null
                }
            }
            .sortedWith(compareBy(JmixApplicationProfileSnapshot::modulePath, JmixApplicationProfileSnapshot::profile))
        val unsupportedYaml = loaded
            .filter { source -> APPLICATION_YAML_FILE.matches(source.file.name) }
            .map { source ->
                JmixProjectPropertiesIssue(
                    code = "JVW-PROJECT-PROPERTIES-YAML-READ-ONLY",
                    message =
                        "${source.relativePath} uses YAML. It is indexed but remains read-only until " +
                            "round-trip-safe YAML mutation is available.",
                    relativePath = source.relativePath,
                )
            }
        val diagnostics = gradle.diagnostics.map { diagnostic ->
            JmixProjectPropertiesIssue(
                code = diagnostic.reasonCode,
                message = diagnostic.message,
                relativePath = diagnostic.sourceId.takeIf { source -> source != "merged-profile" },
            )
        } + unsupportedYaml + propertyIssues

        return JmixProjectPropertiesWorkspace(
            jmixVersion = gradle.jmixVersion.value,
            jmixVersionConfidence = gradle.jmixVersion.confidence,
            observedJmixVersions = gradle.jmixVersion.observedValues,
            targetJava = gradle.targetJdk.value,
            targetJavaConfidence = gradle.targetJdk.confidence,
            observedTargetJavaVersions = gradle.targetJdk.observedValues,
            addOns = gradle.addOns,
            buildFiles = loaded
                .filter { source -> source.file.name in BUILD_FILES }
                .map(ProjectPropertiesSource::locator)
                .sortedBy(SourceLocator::relativePath),
            settingsFiles = loaded
                .filter { source -> source.file.name in SETTINGS_FILES }
                .map(ProjectPropertiesSource::locator)
                .sortedBy(SourceLocator::relativePath),
            profiles = profiles,
            issues = diagnostics.distinct().sortedWith(
                compareBy(JmixProjectPropertiesIssue::code, JmixProjectPropertiesIssue::relativePath),
            ),
            snapshotDigest = CanonicalDiscoveryJson.sha256(
                buildString {
                    append(graph.snapshotDigest).append('\u0000')
                    loaded.sortedBy(ProjectPropertiesSource::relativePath).forEach { source ->
                        append(source.relativePath).append('\u0000')
                            .append(source.locator.revisionFingerprint).append('\n')
                    }
                },
            ),
        )
    }

    private fun resolveChild(
        resolver: ProjectFileResolver,
        parent: String,
        name: String,
    ): VirtualFile? = resolver.resolveFile(join(parent, name))?.file

    private fun resolveDirectory(
        resolver: ProjectFileResolver,
        relativePath: String,
    ): VirtualFile? = resolver.resolveFile(relativePath)?.file?.takeIf(VirtualFile::isDirectory)

    companion object {
        private val BUILD_FILES = setOf("build.gradle", "build.gradle.kts")
        private val SETTINGS_FILES = setOf("settings.gradle", "settings.gradle.kts")
        private val MODULE_CONFIGURATION_FILES = BUILD_FILES + SETTINGS_FILES + "gradle.properties"
        private val GRADLE_INPUT_FILES = BUILD_FILES + SETTINGS_FILES + "gradle.properties" + "libs.versions.toml"
        private val ROOT_CONFIGURATION_FILES = listOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            "gradle/libs.versions.toml",
        )
        private val APPLICATION_CONFIG_FILE = Regex("""application(?:-[A-Za-z0-9][A-Za-z0-9_-]*)?\.(?:properties|ya?ml)""")
        private val APPLICATION_PROPERTIES_FILE = Regex("""application(?:-[A-Za-z0-9][A-Za-z0-9_-]*)?\.properties""")
        private val APPLICATION_YAML_FILE = Regex("""application(?:-[A-Za-z0-9][A-Za-z0-9_-]*)?\.ya?ml""")

        internal fun parsePropertiesProfile(
            relativePath: String,
            content: String,
            locator: SourceLocator = SourceLocator(
                relativePath = relativePath,
                revisionFingerprint = CanonicalDiscoveryJson.sha256(content),
            ),
        ): JmixApplicationProfileSnapshot {
            val properties = Properties()
            properties.load(StringReader(content))
            val values = properties.stringPropertyNames()
                .sorted()
                .associateWith { key -> properties.getProperty(key).orEmpty() }
            val profile = relativePath.substringAfterLast('/')
                .removePrefix("application")
                .removeSuffix(".properties")
                .removePrefix("-")
                .ifBlank { "default" }
            val additionalStoreNames = values["jmix.core.additional-stores"]
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSortedSet()
            val discoveredStoreNames = values.keys
                .mapNotNull { key ->
                    DATA_SOURCE_KEY.matchEntire(key)?.groupValues?.get(1)
                }
                .filterNot { it == "main" }
                .toSortedSet()
            val stores = (sortedSetOf("main") + additionalStoreNames + discoveredStoreNames)
                .map { storeName ->
                    val prefix = "$storeName.datasource."
                    JmixDataStorePropertySnapshot(
                        name = storeName,
                        declaredAdditional = storeName == "main" || storeName in additionalStoreNames,
                        url = values["${prefix}url"]?.let(::redactPotentialSecrets),
                        username = values["${prefix}username"],
                        passwordConfigured = values.containsKey("${prefix}password"),
                        passwordUsesPlaceholder = values["${prefix}password"]?.let(::isPlaceholderOnly) == true,
                        driverClassName = values["${prefix}driver-class-name"],
                        liquibaseChangeLog = values["$storeName.liquibase.change-log"],
                    )
                }
            val secretKeys = values.keys.filter(::isSecretKey).toSet()
            val visible = values.map { (key, value) ->
                JmixApplicationPropertySnapshot(
                    key = key,
                    displayValue = if (key in secretKeys) {
                        if (isPlaceholderOnly(value)) redactSecretPlaceholder(value) else SECRET_REDACTION
                    } else {
                        redactPotentialSecrets(value)
                    },
                    secret = key in secretKeys,
                )
            }
            return JmixApplicationProfileSnapshot(
                modulePath = modulePath(relativePath),
                profile = profile,
                locator = locator,
                serverPort = values["server.port"],
                contextPath = values["server.servlet.context-path"],
                availableLocales = parseLocales(values["jmix.core.available-locales"]),
                stores = stores,
                properties = visible,
            )
        }

        private fun join(parent: String, child: String): String =
            listOf(parent.trim('/'), child.trim('/')).filter(String::isNotBlank).joinToString("/")

        private fun modulePath(relativePath: String): String =
            relativePath.substringBefore("/src/main/resources/", "")

        private fun parseLocales(value: String?): List<String> =
            value.orEmpty()
                .split(',')
                .map { locale ->
                    locale.substringBefore('|').trim()
                }
                .filter(String::isNotBlank)
                .distinct()

        private fun isPlaceholderOnly(value: String): Boolean =
            PLACEHOLDER.matchEntire(value.trim()) != null

        private fun redactSecretPlaceholder(value: String): String {
            val match = PLACEHOLDER.matchEntire(value.trim()) ?: return SECRET_REDACTION
            val variable = match.groupValues[1]
            return if (match.groupValues[2].isBlank()) {
                "\${$variable}"
            } else {
                "\${$variable:$SECRET_REDACTION}"
            }
        }

        private fun redactPotentialSecrets(value: String): String =
            URL_USER_INFO.replace(
                SECRET_QUERY_VALUE.replace(value) { match ->
                    "${match.groupValues[1]}=$SECRET_REDACTION"
                },
            ) { match ->
                "://${match.groupValues[1]}:$SECRET_REDACTION@"
            }

        private fun isSecretKey(key: String): Boolean {
            val normalized = key.lowercase()
            return SECRET_SEGMENTS.any { segment ->
                normalized == segment ||
                    normalized.endsWith(".$segment") ||
                    normalized.endsWith("-$segment") ||
                    normalized.endsWith("_$segment")
            }
        }

        fun getInstance(project: Project): JmixProjectPropertiesService =
            project.getService(JmixProjectPropertiesService::class.java)

        private val DATA_SOURCE_KEY = Regex("""([A-Za-z][A-Za-z0-9_-]*)\.datasource\..+""")
        private val PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}""")
        private val SECRET_QUERY_VALUE = Regex(
            """(?i)(password|passwd|pwd|secret|token)\s*=\s*([^&;\s]+)""",
        )
        private val URL_USER_INFO = Regex("""://([^:/@\s]+):([^@\s]+)@""")
        private val SECRET_SEGMENTS = setOf(
            "password",
            "secret",
            "token",
            "private-key",
            "private_key",
            "credential",
            "credentials",
        )
        private const val SECRET_REDACTION = "••••••••"
    }
}

data class JmixProjectPropertiesWorkspace(
    val jmixVersion: String?,
    val jmixVersionConfidence: EvidenceConfidence,
    val observedJmixVersions: List<String>,
    val targetJava: Int?,
    val targetJavaConfidence: EvidenceConfidence,
    val observedTargetJavaVersions: List<Int>,
    val addOns: List<AddOnEvidence>,
    val buildFiles: List<SourceLocator>,
    val settingsFiles: List<SourceLocator>,
    val profiles: List<JmixApplicationProfileSnapshot>,
    val issues: List<JmixProjectPropertiesIssue>,
    val snapshotDigest: String,
)

data class JmixApplicationProfileSnapshot(
    val modulePath: String,
    val profile: String,
    val locator: SourceLocator,
    val serverPort: String?,
    val contextPath: String?,
    val availableLocales: List<String>,
    val stores: List<JmixDataStorePropertySnapshot>,
    val properties: List<JmixApplicationPropertySnapshot>,
)

data class JmixDataStorePropertySnapshot(
    val name: String,
    val declaredAdditional: Boolean,
    val url: String?,
    val username: String?,
    val passwordConfigured: Boolean,
    val passwordUsesPlaceholder: Boolean,
    val driverClassName: String?,
    val liquibaseChangeLog: String?,
)

data class JmixApplicationPropertySnapshot(
    val key: String,
    val displayValue: String,
    val secret: Boolean,
)

data class JmixProjectPropertiesIssue(
    val code: String,
    val message: String,
    val relativePath: String? = null,
)

private data class ProjectPropertiesSource(
    val relativePath: String,
    val file: VirtualFile,
    val content: String,
    val locator: SourceLocator,
)

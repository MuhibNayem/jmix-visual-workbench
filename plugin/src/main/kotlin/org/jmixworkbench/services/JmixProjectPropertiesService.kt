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
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
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

    fun previewProfileChange(
        request: JmixApplicationPropertiesChangeRequest,
    ): WorkspaceChangePreviewResponse {
        val proposal = proposeProfileChange(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?.let { preview -> secretSafeProfilePreview(preview, request) }
            ?: proposal.rejectedPreview()
    }

    fun prepareProfileChange(
        request: JmixApplicationPropertiesChangeApplyRequest,
    ): PreparedWorkspaceChange {
        val proposal = proposeProfileChange(request.change)
        val changeSet = proposal.changeSet ?: return proposal.rejectedPrepared()
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(
                changeSet = changeSet,
                expectedPlanDigest = request.expectedPlanDigest,
            ),
        )
    }

    private fun proposeProfileChange(
        request: JmixApplicationPropertiesChangeRequest,
    ): ProjectPropertiesChangeProposal {
        val path = request.profileLocator.relativePath
        if (!APPLICATION_PROPERTIES_FILE.matches(path.substringAfterLast('/'))) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-TARGET-UNSUPPORTED",
                message = "Only revision-bound application[-profile].properties files can be edited.",
                relativePath = path,
            )
        }
        if (request.updates.isEmpty() || request.updates.size > MAX_PROFILE_UPDATES) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-UPDATE-COUNT",
                message = "A profile change must contain between 1 and $MAX_PROFILE_UPDATES updates.",
                relativePath = path,
            )
        }
        val duplicateKeys = request.updates
            .groupingBy { update -> update.key.trim() }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        if (duplicateKeys.isNotEmpty()) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-DUPLICATE-UPDATE",
                message = "Each property can be updated only once: ${duplicateKeys.sorted().joinToString()}.",
                relativePath = path,
            )
        }
        val indexedProfile = inspect().profiles.singleOrNull { profile ->
            profile.locator.relativePath == path
        }
            ?: return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-TARGET-UNINDEXED",
                message =
                    "The selected file is not an indexed application profile in the current IntelliJ project model.",
                relativePath = path,
            )
        if (indexedProfile.locator.revisionFingerprint != request.profileLocator.revisionFingerprint) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-SOURCE-STALE",
                message = "The profile changed after it was loaded. Refresh and review it again.",
                relativePath = path,
            )
        }

        val resolver = ProjectFileResolver.getInstance(project)
        val resolved = resolver.resolveFile(path)
            ?: return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-SOURCE-MISSING",
                message = "The selected profile no longer exists in the current IntelliJ project model.",
                relativePath = path,
            )
        val file = resolved.file
        if (file.isDirectory || !APPLICATION_PROPERTIES_FILE.matches(file.name)) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-SOURCE-TYPE",
                message = "The selected source is not an application properties file.",
                relativePath = path,
            )
        }
        val content = runCatching { ProjectSourceText.read(file) }.getOrElse { failure ->
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-SOURCE-UNREADABLE",
                message = failure.message ?: "The selected profile cannot be read.",
                relativePath = path,
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (request.profileLocator.revisionFingerprint != fingerprint) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-SOURCE-STALE",
                message = "The profile changed after it was loaded. Refresh and review it again.",
                relativePath = path,
            )
        }
        val document = runCatching { parseEditablePropertiesDocument(content) }.getOrElse { failure ->
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-SOURCE-MALFORMED",
                message = failure.message ?: "The profile cannot be edited safely.",
                relativePath = path,
            )
        }
        val duplicateSourceKeys = document.entries
            .groupingBy(EditablePropertyEntry::key)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        val requestedKeys = request.updates.map { update -> update.key.trim() }.toSet()
        val ambiguousKeys = duplicateSourceKeys.intersect(requestedKeys)
        if (ambiguousKeys.isNotEmpty()) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-SOURCE-DUPLICATE",
                message =
                    "The profile declares requested keys more than once; resolve the ambiguity in source first: " +
                        ambiguousKeys.sorted().joinToString(),
                relativePath = path,
            )
        }
        if (
            "spring.profiles.active" in requestedKeys &&
            file.name != "application.properties"
        ) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-ACTIVE-PROFILE-OWNER",
                message = "spring.profiles.active can be edited only in the default application.properties profile.",
                relativePath = path,
            )
        }

        val issues = request.updates.mapNotNull(::validateProfileUpdate)
        if (issues.isNotEmpty()) {
            return ProjectPropertiesChangeProposal(
                changeSet = null,
                issues = issues.map { issue -> issue.copy(relativePath = path) },
            )
        }

        val entriesByKey = document.entries.associateBy(EditablePropertyEntry::key)
        val edits = mutableListOf<WorkspaceTextEdit>()
        val additions = mutableListOf<Pair<String, String>>()
        request.updates.sortedBy { update -> update.key.trim() }.forEach { update ->
            val key = update.key.trim()
            val value = update.value
            val existing = entriesByKey[key]
            if (existing == null) {
                additions += key to value
                return@forEach
            }
            if (existing.continued) {
                return ProjectPropertiesChangeProposal.rejected(
                    code = "JVW-PROJECT-PROPERTIES-CONTINUATION-READ-ONLY",
                    message =
                        "$key uses a continued logical line. Preserve it manually until exact continuation " +
                            "round-trip editing is available.",
                    relativePath = path,
                )
            }
            if (existing.decodedValue == value) return@forEach
            edits += WorkspaceTextEdit(
                startOffset = existing.valueStartOffset,
                endOffset = existing.valueEndOffset,
                expectedText = content.substring(existing.valueStartOffset, existing.valueEndOffset),
                replacement = escapePropertyValue(value),
            )
        }
        if (additions.isNotEmpty()) {
            val lineSeparator = document.lineSeparator
            val separatorBefore = when {
                content.isEmpty() || content.endsWith("\n") || content.endsWith("\r") -> ""
                else -> lineSeparator
            }
            val appended = additions.joinToString(separator = lineSeparator, postfix = lineSeparator) { (key, value) ->
                "${escapePropertyKey(key)}=${escapePropertyValue(value)}"
            }
            val existingEndInsertion = edits.singleOrNull { edit ->
                edit.startOffset == content.length && edit.endOffset == content.length
            }
            if (existingEndInsertion != null) {
                edits.remove(existingEndInsertion)
                edits += existingEndInsertion.copy(
                    replacement = existingEndInsertion.replacement + separatorBefore + appended,
                )
            } else {
                edits += WorkspaceTextEdit(
                    startOffset = content.length,
                    endOffset = content.length,
                    expectedText = "",
                    replacement = separatorBefore + appended,
                )
            }
        }
        if (edits.isEmpty()) {
            return ProjectPropertiesChangeProposal.rejected(
                code = "JVW-PROJECT-PROPERTIES-NO-CHANGES",
                message = "The requested values already match the current profile.",
                relativePath = path,
            )
        }
        val identity = buildString {
            append(path).append('\u0000').append(fingerprint).append('\n')
            request.updates.sortedBy { update -> update.key.trim() }.forEach { update ->
                append(update.key.trim()).append('\u0000')
                    .append(CanonicalDiscoveryJson.sha256(update.value)).append('\n')
            }
        }
        return ProjectPropertiesChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "project-profile:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Update ${file.name} project configuration",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = path,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = fingerprint,
                        edits = edits.sortedWith(
                            compareBy(WorkspaceTextEdit::startOffset, WorkspaceTextEdit::endOffset),
                        ),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    /**
     * The generic mutation preview intentionally contains complete source
     * documents. Profile files may contain unrelated secrets, so this boundary
     * returns only the requested properties. The unredacted plan and digest
     * remain backend-owned and are recomputed before apply.
     */
    private fun secretSafeProfilePreview(
        preview: WorkspaceChangePreviewResponse,
        request: JmixApplicationPropertiesChangeRequest,
    ): WorkspaceChangePreviewResponse {
        if (!preview.accepted) return preview
        return runCatching {
            val selectedKeys = request.updates.map { update -> update.key.trim() }.toSortedSet()
            preview.copy(
                files = preview.files.map { file ->
                    file.copy(
                        originalContent = file.originalContent?.let { content ->
                            focusedProfilePreview(content, selectedKeys)
                        },
                        resultContent = focusedProfilePreview(file.resultContent, selectedKeys),
                    )
                },
            )
        }.getOrElse {
            WorkspaceChangePreviewResponse(
                accepted = false,
                changeSetId = preview.changeSetId,
                label = preview.label,
                planDigest = null,
                files = emptyList(),
                issues = listOf(
                    WorkspaceChangeIssue(
                        code = "JVW-PROJECT-PROPERTIES-PREVIEW-REDACTION-FAILED",
                        message =
                            "The selected profile could not be converted to a credential-safe preview.",
                        relativePath = request.profileLocator.relativePath,
                    ),
                ),
            )
        }
    }

    private fun focusedProfilePreview(
        content: String,
        selectedKeys: Set<String>,
    ): String {
        val entries = parseEditablePropertiesDocument(content).entries
            .filter { entry -> entry.key in selectedKeys }
            .associateBy(EditablePropertyEntry::key)
        return buildString {
            append("# Credential-safe focused preview; unrelated properties are intentionally omitted.\n")
            selectedKeys.forEach { key ->
                val entry = entries[key]
                if (entry == null) {
                    append("# ").append(escapePropertyKey(key)).append(" is not configured\n")
                } else {
                    append(escapePropertyKey(key))
                        .append('=')
                        .append(escapePropertyValue(safeDisplayValue(key, entry.decodedValue)))
                        .append('\n')
                }
            }
        }
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
                activeProfiles = parseCommaSeparated(values["spring.profiles.active"]),
                availableLocales = parseLocales(values["jmix.core.available-locales"]),
                stores = stores,
                properties = visible,
            )
        }

        internal fun parseEditablePropertiesDocument(content: String): EditablePropertiesDocument {
            val entries = mutableListOf<EditablePropertyEntry>()
            var detectedLineSeparator: String? = null
            var offset = 0
            while (offset < content.length) {
                val firstLine = physicalLine(content, offset)
                if (detectedLineSeparator == null && firstLine.separator.isNotEmpty()) {
                    detectedLineSeparator = firstLine.separator
                }
                val rawLine = content.substring(offset, firstLine.contentEndOffset)
                val first = rawLine.indexOfFirst { character -> character != ' ' && character != '\t' && character != '\u000c' }
                if (first >= 0 && rawLine[first] != '#' && rawLine[first] != '!') {
                    val continued = hasOddTrailingBackslashes(rawLine)
                    var logicalEndOffset = firstLine.contentEndOffset
                    var nextOffset = firstLine.nextOffset
                    var currentRawLine = rawLine
                    while (hasOddTrailingBackslashes(currentRawLine) && nextOffset < content.length) {
                        val nextLine = physicalLine(content, nextOffset)
                        if (detectedLineSeparator == null && nextLine.separator.isNotEmpty()) {
                            detectedLineSeparator = nextLine.separator
                        }
                        currentRawLine = content.substring(nextOffset, nextLine.contentEndOffset)
                        logicalEndOffset = nextLine.contentEndOffset
                        nextOffset = nextLine.nextOffset
                    }
                    val parsed = parseSinglePropertyLine(
                        rawFirstLine = rawLine,
                        rawLogicalLine = content.substring(offset, logicalEndOffset),
                        absoluteOffset = offset,
                        logicalEndOffset = logicalEndOffset,
                        continued = continued,
                    )
                    if (parsed != null) entries += parsed
                    if (continued) {
                        offset = nextOffset
                        continue
                    }
                }
                offset = firstLine.nextOffset
            }
            return EditablePropertiesDocument(entries, detectedLineSeparator ?: "\n")
        }

        private fun parseSinglePropertyLine(
            rawFirstLine: String,
            rawLogicalLine: String,
            absoluteOffset: Int,
            logicalEndOffset: Int,
            continued: Boolean,
        ): EditablePropertyEntry? {
            var index = 0
            while (index < rawFirstLine.length && rawFirstLine[index] in PROPERTY_LEADING_WHITESPACE) index++
            if (
                index >= rawFirstLine.length ||
                rawFirstLine[index] == '#' ||
                rawFirstLine[index] == '!'
            ) {
                return null
            }
            val keyStart = index
            var escaped = false
            var keyEnd = rawFirstLine.length
            var separatorIndex = rawFirstLine.length
            while (index < rawFirstLine.length) {
                val character = rawFirstLine[index]
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '=' || character == ':' || character in PROPERTY_LEADING_WHITESPACE) {
                    keyEnd = index
                    separatorIndex = index
                    break
                }
                index++
            }
            if (keyEnd == keyStart) return null
            var valueStart = separatorIndex
            while (
                valueStart < rawFirstLine.length &&
                rawFirstLine[valueStart] in PROPERTY_LEADING_WHITESPACE
            ) {
                valueStart++
            }
            if (
                valueStart < rawFirstLine.length &&
                (rawFirstLine[valueStart] == '=' || rawFirstLine[valueStart] == ':')
            ) {
                valueStart++
            }
            while (
                valueStart < rawFirstLine.length &&
                rawFirstLine[valueStart] in PROPERTY_LEADING_WHITESPACE
            ) {
                valueStart++
            }
            val rawKey = rawFirstLine.substring(keyStart, keyEnd)
            val decoded = Properties().apply {
                load(StringReader(rawLogicalLine))
            }
            val decodedKeyOnly = Properties().apply {
                load(StringReader("$rawKey="))
            }
            val key = decodedKeyOnly.stringPropertyNames().singleOrNull() ?: return null
            return EditablePropertyEntry(
                key = key,
                decodedValue = decoded.getProperty(key).orEmpty(),
                valueStartOffset = absoluteOffset + valueStart,
                valueEndOffset = logicalEndOffset,
                continued = continued,
            )
        }

        private fun physicalLine(
            content: String,
            startOffset: Int,
        ): PhysicalPropertyLine {
            var cursor = startOffset
            while (cursor < content.length && content[cursor] != '\r' && content[cursor] != '\n') {
                cursor++
            }
            val separator = when {
                cursor >= content.length -> ""
                content[cursor] == '\r' && cursor + 1 < content.length && content[cursor + 1] == '\n' ->
                    "\r\n"
                content[cursor] == '\r' -> "\r"
                else -> "\n"
            }
            return PhysicalPropertyLine(
                contentEndOffset = cursor,
                nextOffset = cursor + separator.length,
                separator = separator,
            )
        }

        private fun validateProfileUpdate(
            update: JmixApplicationPropertyUpdate,
        ): WorkspaceChangeIssue? {
            val key = update.key.trim()
            val value = update.value
            if (key.isEmpty() || key.length > MAX_PROPERTY_KEY_LENGTH || !EDITABLE_PROFILE_KEY.matches(key)) {
                return WorkspaceChangeIssue(
                    code = "JVW-PROJECT-PROPERTIES-KEY-UNSUPPORTED",
                    message = "The property '$key' is not in the server/profile/data-store editing contract.",
                )
            }
            if (value.length > MAX_PROPERTY_VALUE_LENGTH || value.any { character ->
                    character == '\u0000' || character == '\r' || character == '\n'
                }
            ) {
                return WorkspaceChangeIssue(
                    code = "JVW-PROJECT-PROPERTIES-VALUE-INVALID",
                    message = "$key contains an oversized or multiline value that cannot cross the visual boundary.",
                )
            }
            if (isSecretKey(key)) {
                if (!SECRET_WRITE_PLACEHOLDER.matches(value)) {
                    return WorkspaceChangeIssue(
                        code = "JVW-PROJECT-PROPERTIES-SECRET-LITERAL-DENIED",
                        message =
                            "$key accepts only a \${ENVIRONMENT_VARIABLE} reference without a default. " +
                                "Literal secrets never cross JCEF.",
                    )
                }
            } else if (redactPotentialSecrets(value) != value) {
                return WorkspaceChangeIssue(
                    code = "JVW-PROJECT-PROPERTIES-EMBEDDED-SECRET-DENIED",
                    message = "$key appears to embed credentials or a secret URL parameter.",
                )
            }
            return when {
                key == "server.port" && !validServerPort(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-PORT-INVALID",
                        "server.port must be 1-65535 or an environment placeholder.",
                    )
                key == "server.servlet.context-path" && !validContextPath(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-CONTEXT-PATH-INVALID",
                        "The context path must start with '/', contain no whitespace, or be an environment placeholder.",
                    )
                key == "jmix.core.available-locales" && !validLocales(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-LOCALES-INVALID",
                        "Locales must be comma-separated BCP 47-style tags with optional '|Display name'.",
                    )
                key == "spring.profiles.active" && !validActiveProfiles(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-ACTIVE-PROFILES-INVALID",
                        "Active profiles must be unique comma-separated Spring profile identifiers.",
                    )
                key == "jmix.core.additional-stores" && !validAdditionalStores(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-STORES-INVALID",
                        "Additional stores must be unique Java identifiers and must not include 'main'.",
                    )
                key.endsWith(".datasource.url") && !validDatasourceUrl(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-DATASOURCE-URL-INVALID",
                        "Datasource URLs must be JDBC URLs or environment placeholders without embedded credentials.",
                    )
                key.endsWith(".datasource.driver-class-name") &&
                    !JAVA_QUALIFIED_NAME.matches(value) && !isPlaceholderOnly(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-DRIVER-INVALID",
                        "Datasource driver class names must be qualified JVM class names or placeholders.",
                    )
                key.endsWith(".liquibase.change-log") &&
                    !LIQUIBASE_CHANGELOG.matches(value) && !isPlaceholderOnly(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-CHANGELOG-INVALID",
                        "Liquibase changelog paths contain unsupported characters.",
                    )
                key.endsWith(".liquibase.enabled") &&
                    !BOOLEAN_VALUE.matches(value) && !isPlaceholderOnly(value) ->
                    WorkspaceChangeIssue(
                        "JVW-PROJECT-PROPERTIES-LIQUIBASE-ENABLED-INVALID",
                        "Liquibase enabled values must be true, false or an environment placeholder.",
                    )
                else -> null
            }
        }

        private fun validServerPort(value: String): Boolean =
            isPlaceholderOnly(value) || value.toIntOrNull()?.let { port -> port in 1..65_535 } == true

        private fun validContextPath(value: String): Boolean =
            isPlaceholderOnly(value) ||
                (value.startsWith('/') && value.none(Char::isWhitespace) && value.length <= 256)

        private fun validLocales(value: String): Boolean =
            value.split(',').map(String::trim).filter(String::isNotEmpty).let { locales ->
                locales.isNotEmpty() && locales.size <= 100 && locales.all(LOCALE_ENTRY::matches)
            }

        private fun validAdditionalStores(value: String): Boolean =
            value.split(',').map(String::trim).filter(String::isNotEmpty).let { stores ->
                stores.size <= 100 &&
                    stores.distinct().size == stores.size &&
                    stores.none { store -> store == "main" } &&
                    stores.all(STORE_NAME::matches)
            }

        private fun validActiveProfiles(value: String): Boolean =
            parseCommaSeparated(value).let { profiles ->
                profiles.isNotEmpty() &&
                    profiles.size <= 100 &&
                    profiles.distinct().size == profiles.size &&
                    profiles.all(SPRING_PROFILE_NAME::matches)
            }

        private fun validDatasourceUrl(value: String): Boolean =
            isPlaceholderOnly(value) ||
                (value.startsWith("jdbc:") && value.length <= MAX_PROPERTY_VALUE_LENGTH)

        private fun escapePropertyKey(value: String): String = buildString {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    ' ', '\t', '\u000c', '=', ':', '#', '!' -> append('\\').append(character)
                    else -> append(character)
                }
            }
        }

        private fun escapePropertyValue(value: String): String = buildString {
            value.forEachIndexed { index, character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\u000c' -> append("\\f")
                    ' ' -> if (index == 0) append("\\ ") else append(' ')
                    '#', '!' -> if (index == 0) append('\\').append(character) else append(character)
                    else -> append(character)
                }
            }
        }

        private fun hasOddTrailingBackslashes(line: String): Boolean {
            var count = 0
            var index = line.lastIndex
            while (index >= 0 && line[index] == '\\') {
                count++
                index--
            }
            return count % 2 == 1
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

        private fun parseCommaSeparated(value: String?): List<String> =
            value.orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)

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
            ORACLE_THIN_USER_INFO.replace(
                URL_USER_INFO.replace(
                    SECRET_QUERY_VALUE.replace(value) { match ->
                        "${match.groupValues[1]}=$SECRET_REDACTION"
                    },
                ) { match ->
                    "://${match.groupValues[1]}:$SECRET_REDACTION@"
                },
            ) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}/$SECRET_REDACTION@"
            }

        private fun safeDisplayValue(
            key: String,
            value: String,
        ): String =
            if (isSecretKey(key)) {
                if (isPlaceholderOnly(value)) redactSecretPlaceholder(value) else SECRET_REDACTION
            } else {
                redactPotentialSecrets(value)
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
        private val SECRET_WRITE_PLACEHOLDER = Regex("""\$\{[A-Za-z_][A-Za-z0-9_]*}""")
        private val SECRET_QUERY_VALUE = Regex(
            """(?i)(password|passwd|pwd|secret|token)\s*=\s*([^&;\s]+)""",
        )
        private val URL_USER_INFO = Regex("""://([^:/@\s]+):([^@\s]+)@""")
        private val ORACLE_THIN_USER_INFO = Regex(
            """(?i)(jdbc:oracle:thin:)([^/@:\s]+)/([^@\s]+)@""",
        )
        private val SECRET_SEGMENTS = setOf(
            "password",
            "secret",
            "token",
            "api-key",
            "api_key",
            "apikey",
            "access-key",
            "access_key",
            "private-key",
            "private_key",
            "credential",
            "credentials",
        )
        private val EDITABLE_PROFILE_KEY = Regex(
            """(?:server\.port|server\.servlet\.context-path|spring\.profiles\.active|""" +
                """jmix\.core\.(?:available-locales|additional-stores)|""" +
                """[A-Za-z][A-Za-z0-9_-]*\.datasource\.(?:url|username|password|driver-class-name)|""" +
                """[A-Za-z][A-Za-z0-9_-]*\.liquibase\.(?:change-log|enabled))""",
        )
        private val LOCALE_ENTRY = Regex(
            """[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{1,8})*(?:\|[^,\r\n]{1,100})?""",
        )
        private val STORE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*""")
        private val SPRING_PROFILE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9_-]{0,99}""")
        private val JAVA_QUALIFIED_NAME = Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+""")
        private val LIQUIBASE_CHANGELOG = Regex("""[A-Za-z0-9_./:@-]{1,1024}""")
        private val BOOLEAN_VALUE = Regex("""(?i:true|false)""")
        private val PROPERTY_LEADING_WHITESPACE = setOf(' ', '\t', '\u000c')
        private const val MAX_PROFILE_UPDATES = 100
        private const val MAX_PROPERTY_KEY_LENGTH = 200
        private const val MAX_PROPERTY_VALUE_LENGTH = 4_096
        private const val SECRET_REDACTION = "••••••••"
    }
}

data class JmixApplicationPropertyUpdate(
    val key: String,
    val value: String,
)

data class JmixApplicationPropertiesChangeRequest(
    val profileLocator: SourceLocator,
    val updates: List<JmixApplicationPropertyUpdate>,
)

data class JmixApplicationPropertiesChangeApplyRequest(
    val change: JmixApplicationPropertiesChangeRequest,
    val expectedPlanDigest: String,
)

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
    val activeProfiles: List<String>,
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

internal data class EditablePropertiesDocument(
    val entries: List<EditablePropertyEntry>,
    val lineSeparator: String,
)

internal data class EditablePropertyEntry(
    val key: String,
    val decodedValue: String,
    val valueStartOffset: Int,
    val valueEndOffset: Int,
    val continued: Boolean,
)

private data class PhysicalPropertyLine(
    val contentEndOffset: Int,
    val nextOffset: Int,
    val separator: String,
)

private data class ProjectPropertiesChangeProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
) {
    fun rejectedPreview(): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "project-profile:rejected",
            label = "Project profile change rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    fun rejectedPrepared(): PreparedWorkspaceChange =
        PreparedWorkspaceChange(
            plan = WorkspaceChangePlan(
                accepted = false,
                changeSetId = "project-profile:rejected",
                label = "Project profile change rejected",
                planDigest = null,
                files = emptyList(),
                issues = issues,
            ),
            baseDir = null,
        )

    companion object {
        fun rejected(
            code: String,
            message: String,
            relativePath: String,
        ): ProjectPropertiesChangeProposal =
            ProjectPropertiesChangeProposal(
                changeSet = null,
                issues = listOf(WorkspaceChangeIssue(code, message, relativePath)),
            )
    }
}

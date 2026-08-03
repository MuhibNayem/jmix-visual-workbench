package org.jmixworkbench.services

import com.google.gson.GsonBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import java.util.Locale

/**
 * Source-controlled, credential-free database import mappings.
 *
 * Profiles retain the reviewed table graph and its live metadata digest so a
 * team can replay the same mapping decisions and see drift before generation.
 */
@Service(Service.Level.PROJECT)
class DatabaseEntityImportProfileService(
    private val project: Project,
) {
    fun workspace(): DatabaseEntityImportProfileWorkspaceResponse {
        val base = project.basePath
            ?.let(LocalFileSystem.getInstance()::findFileByPath)
            ?: return DatabaseEntityImportProfileWorkspaceResponse(
                profiles = emptyList(),
                issues = listOf(
                    WorkspaceChangeIssue(
                        "JVW-DB-IMPORT-PROFILE-PROJECT-MISSING",
                        "The open project root is unavailable.",
                    ),
                ),
            )
        val directory = base.findFileByRelativePath(PROFILE_DIRECTORY)
            ?: return DatabaseEntityImportProfileWorkspaceResponse(emptyList(), emptyList())
        if (!directory.isDirectory) {
            return DatabaseEntityImportProfileWorkspaceResponse(
                profiles = emptyList(),
                issues = listOf(
                    WorkspaceChangeIssue(
                        "JVW-DB-IMPORT-PROFILE-DIRECTORY-INVALID",
                        "$PROFILE_DIRECTORY must be a directory.",
                        PROFILE_DIRECTORY,
                    ),
                ),
            )
        }
        val issues = mutableListOf<WorkspaceChangeIssue>()
        val profiles = directory.children
            .asSequence()
            .filter { !it.isDirectory && it.extension.equals("json", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .take(MAX_PROFILES + 1)
            .mapNotNull { file ->
                val relativePath = "$PROFILE_DIRECTORY/${file.name}"
                if (file.length > MAX_PROFILE_BYTES) {
                    issues += WorkspaceChangeIssue(
                        "JVW-DB-IMPORT-PROFILE-TOO-LARGE",
                        "${file.name} exceeds the reviewed profile size limit.",
                        relativePath,
                    )
                    return@mapNotNull null
                }
                val content = runCatching { ProjectSourceText.read(file) }.getOrElse {
                    issues += WorkspaceChangeIssue(
                        "JVW-DB-IMPORT-PROFILE-UNREADABLE",
                        "${file.name} could not be read.",
                        relativePath,
                    )
                    return@mapNotNull null
                }
                val profile = runCatching {
                    gson.fromJson(content, DatabaseEntityImportProfile::class.java)
                }.getOrNull()
                val validation = profile?.let { validate(it, file.nameWithoutExtension) }
                if (profile == null || validation != null) {
                    issues += WorkspaceChangeIssue(
                        "JVW-DB-IMPORT-PROFILE-INVALID",
                        validation ?: "${file.name} is not a valid database import profile.",
                        relativePath,
                    )
                    return@mapNotNull null
                }
                DatabaseEntityImportProfileDocument(
                    profile = profile,
                    sourceLocator = SourceLocator(
                        relativePath = relativePath,
                        revisionFingerprint = CanonicalDiscoveryJson.sha256(content),
                    ),
                )
            }
            .take(MAX_PROFILES)
            .toList()
        if (directory.children.count { it.extension.equals("json", ignoreCase = true) } > MAX_PROFILES) {
            issues += WorkspaceChangeIssue(
                "JVW-DB-IMPORT-PROFILE-LIMIT",
                "Only the first $MAX_PROFILES database import profiles were loaded.",
                PROFILE_DIRECTORY,
            )
        }
        return DatabaseEntityImportProfileWorkspaceResponse(profiles, issues)
    }

    fun drift(
        profileId: String,
        request: DatabaseEntityImportRequest,
        livePlan: DatabaseEntityImportPlanResponse,
    ): DatabaseEntityImportProfileDrift? {
        val profile = workspace().profiles
            .firstOrNull { it.profile.id == profileId }
            ?.profile
            ?: return null
        val baseline = profile.tables.associateBy { tableKey(it.table) }
        val live = livePlan.tables.associateBy { tableKey(it.table) }
        val added = (live.keys - baseline.keys).sorted()
        val removed = (baseline.keys - live.keys).sorted()
        val changed = (baseline.keys intersect live.keys)
            .filter { key -> tablePlanDigest(baseline.getValue(key)) != tablePlanDigest(live.getValue(key)) }
            .sorted()
        val normalizedRequest = request.copy(
            selectedTables = request.selectedTables.sortedBy(::tableKey),
            identifierOverrides = request.identifierOverrides.toSortedMap(String.CASE_INSENSITIVE_ORDER),
            classNameOverrides = request.classNameOverrides.toSortedMap(String.CASE_INSENSITIVE_ORDER),
            connectTimeoutSeconds = 10,
            networkTimeoutSeconds = 30,
        )
        val requestChanged = normalizedRequest != profile.request
        return DatabaseEntityImportProfileDrift(
            profileId = profile.id,
            baselineSnapshotDigest = profile.baselineSnapshotDigest,
            liveSnapshotDigest = livePlan.snapshotDigest,
            matchesBaseline = !requestChanged &&
                profile.baselineSnapshotDigest == livePlan.snapshotDigest &&
                added.isEmpty() &&
                removed.isEmpty() &&
                changed.isEmpty(),
            requestChanged = requestChanged,
            addedTables = added,
            removedTables = removed,
            changedTables = changed,
        )
    }

    companion object {
        const val PROFILE_DIRECTORY = ".jmix-workbench/database-imports"
        private const val PROFILE_SCHEMA_VERSION = 1
        private const val MAX_PROFILES = 200
        private const val MAX_PROFILE_BYTES = 2L * 1024 * 1024
        private val PROFILE_ID = Regex("""[a-z][a-z0-9-]{2,63}""")
        private val gson = GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()

        fun fromPlan(
            request: DatabaseEntityImportRequest,
            plan: DatabaseEntityImportPlanResponse,
        ): DatabaseEntityImportProfile? {
            val id = request.profileId ?: return null
            val label = request.profileLabel ?: return null
            require(plan.accepted && plan.ready && !plan.snapshotDigest.isNullOrBlank()) {
                "JVW-DB-IMPORT-PROFILE-PLAN-NOT-READY: only a complete live plan can become a profile."
            }
            val normalizedRequest = request.copy(
                selectedTables = request.selectedTables.sortedBy(::tableKey),
                identifierOverrides = request.identifierOverrides.toSortedMap(String.CASE_INSENSITIVE_ORDER),
                classNameOverrides = request.classNameOverrides.toSortedMap(String.CASE_INSENSITIVE_ORDER),
                connectTimeoutSeconds = 10,
                networkTimeoutSeconds = 30,
            )
            return DatabaseEntityImportProfile(
                schemaVersion = PROFILE_SCHEMA_VERSION,
                id = id,
                label = label,
                request = normalizedRequest,
                baselineSnapshotDigest = requireNotNull(plan.snapshotDigest),
                database = requireNotNull(plan.database),
                tables = plan.tables.sortedBy { tableKey(it.table) },
            )
        }

        fun path(profile: DatabaseEntityImportProfile): String =
            "$PROFILE_DIRECTORY/${profile.id}.json"

        fun serialize(profile: DatabaseEntityImportProfile): String =
            gson.toJson(profile).trimEnd() + "\n"

        fun validate(profile: DatabaseEntityImportProfile, expectedId: String? = null): String? = when {
            profile.schemaVersion != PROFILE_SCHEMA_VERSION ->
                "Unsupported profile schema ${profile.schemaVersion}."
            !PROFILE_ID.matches(profile.id) ->
                "Profile ID must use lowercase letters, digits, and dashes."
            expectedId != null && profile.id != expectedId ->
                "Profile ID ${profile.id} does not match its file name."
            profile.label.isBlank() || profile.label.length > 120 ->
                "Profile label must contain between 1 and 120 characters."
            profile.request.profileId != profile.id || profile.request.profileLabel != profile.label ->
                "Profile identity and replay request do not match."
            profile.baselineSnapshotDigest.length != 64 ->
                "Profile baseline digest is malformed."
            profile.request.selectedTables.isEmpty() || profile.tables.isEmpty() ->
                "Profile contains no database tables."
            else -> null
        }

        private fun tableKey(table: DatabaseTableReference): String =
            tableKey(table.catalog, table.schema, table.name)

        private fun tableKey(table: DatabaseTableSnapshot): String =
            tableKey(table.catalog, table.schema, table.name)

        private fun tableKey(catalog: String?, schema: String?, name: String): String =
            listOfNotNull(catalog, schema, name).joinToString(".").lowercase(Locale.ROOT)

        private fun tablePlanDigest(table: DatabaseEntityImportTablePlan): String =
            CanonicalDiscoveryJson.sha256(gson.toJson(table))

        fun getInstance(project: Project): DatabaseEntityImportProfileService =
            project.getService(DatabaseEntityImportProfileService::class.java)
    }
}

data class DatabaseEntityImportProfile(
    val schemaVersion: Int,
    val id: String,
    val label: String,
    val request: DatabaseEntityImportRequest,
    val baselineSnapshotDigest: String,
    val database: DatabaseProductSnapshot,
    val tables: List<DatabaseEntityImportTablePlan>,
)

data class DatabaseEntityImportProfileDocument(
    val profile: DatabaseEntityImportProfile,
    val sourceLocator: SourceLocator,
)

data class DatabaseEntityImportProfileWorkspaceResponse(
    val profiles: List<DatabaseEntityImportProfileDocument>,
    val issues: List<WorkspaceChangeIssue>,
)

data class DatabaseEntityImportProfileDrift(
    val profileId: String,
    val baselineSnapshotDigest: String,
    val liveSnapshotDigest: String?,
    val matchesBaseline: Boolean,
    val requestChanged: Boolean,
    val addedTables: List<String>,
    val removedTables: List<String>,
    val changedTables: List<String>,
)

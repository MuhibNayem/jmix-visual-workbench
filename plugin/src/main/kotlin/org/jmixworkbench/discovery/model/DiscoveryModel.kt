package org.jmixworkbench.discovery.model

data class Evidence<T>(
    val value: T?,
    val sourceKind: EvidenceSourceKind,
    val sourceId: String,
    val confidence: EvidenceConfidence,
    val observedFingerprint: String,
    val observedValues: List<T> = emptyList(),
)

enum class EvidenceConfidence {
    EXACT,
    STRONG,
    WEAK,
    CONFLICTING,
}

enum class EvidenceSourceKind {
    IMPORTED_GRADLE_MODEL,
    MODULE_SDK,
    IDE_PROJECT_MODEL,
    STATIC_BUILD_FILE,
    VERSION_CATALOG,
    PROJECT_CONFIGURATION,
    SOURCE_INDEX,
    UNKNOWN,
}

enum class TrustState {
    TRUSTED,
    UNTRUSTED,
    UNKNOWN,
}

enum class ImportState {
    READY,
    INDEXING,
    STALE,
    FAILED,
    ABSENT,
}

enum class BuildKind {
    ROOT,
    INCLUDED,
    COMPOSITE,
    UNKNOWN,
}

enum class ModuleRole {
    APPLICATION,
    ADDON_FUNCTIONAL,
    ADDON_STARTER,
    BUILD_LOGIC,
    AGGREGATOR,
    LIBRARY,
    UNKNOWN,
}

enum class SourceRootKind {
    SOURCE,
    RESOURCE,
    MIGRATION,
    GENERATED,
    TEST,
    UNKNOWN,
}

enum class SourceLanguage {
    JAVA,
    KOTLIN,
    GROOVY,
    XML,
    PROPERTIES,
    YAML,
    TOML,
    MIXED,
    UNKNOWN,
}

enum class DependencyOrigin {
    IMPORTED_MODEL,
    STATIC_BUILD_FILE,
    VERSION_CATALOG,
    UNKNOWN,
}

enum class ProfileClassification {
    JMIX_2_8,
    JMIX_3_0,
    EARLIER_JMIX_2,
    LEGACY_JMIX_1,
    CUBA,
    FUTURE,
    NOT_DETECTED,
    UNKNOWN,
}

enum class ArtifactKind {
    ENTITY,
    DTO,
    ENUM,
    VIEW_DESCRIPTOR,
    VIEW_CONTROLLER,
    FETCH_PLAN,
    MENU_ITEM,
    MENU_SOURCE,
    RESOURCE_ROLE,
    ROW_ROLE,
    MESSAGE_BUNDLE,
    MESSAGE_KEY,
    REPOSITORY,
    LIQUIBASE_ROOT,
    LIQUIBASE_INCLUDE,
    LIQUIBASE_CHANGESET,
    MODULE,
    BUILD,
    SOURCE_SET,
    ADDON,
    DATA_STORE,
}

enum class ArtifactOrigin {
    SOURCE,
    RESOURCE,
    IMPORTED_MODEL,
    STATIC_CONFIGURATION,
    GENERATED,
    UNKNOWN,
}

enum class RelationshipType {
    DECLARES,
    CONTROLS,
    USES_ENTITY,
    EXTENDS,
    REFERENCES_FETCH_PLAN,
    NAVIGATES_TO,
    INCLUDES_CHANGELOG,
    BELONGS_TO_STORE,
    DEPENDS_ON_ADDON,
    LOCALIZES,
}

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
    BLOCKING,
}

enum class DiagnosticCategory {
    TRUST,
    IMPORT,
    INDEX,
    PROFILE,
    BUILD_CONFIGURATION,
    DEPENDENCY,
    SOURCE,
    RELATIONSHIP,
    COMPATIBILITY,
}

enum class CompatibilityState {
    CERTIFIED_READ_WRITE,
    CERTIFIED_READ_ONLY,
    RECOGNIZED_DIAGNOSTIC,
    UNSUPPORTED,
}

data class DiscoverySnapshot(
    val snapshotId: String,
    val projectId: String,
    val createdAtEpochMillis: Long,
    val trustState: TrustState,
    val importState: ImportState,
    val builds: List<BuildSnapshot> = emptyList(),
    val artifacts: List<ArtifactSnapshot> = emptyList(),
    val relationships: List<ArtifactRelationship> = emptyList(),
    val diagnostics: List<DiscoveryDiagnostic> = emptyList(),
    val compatibilityDecisions: List<CompatibilityDecision> = emptyList(),
    val optionalIdeCapabilities: List<OptionalIdeCapability> = emptyList(),
    val profile: JmixProfile = JmixProfile(),
)

data class BuildSnapshot(
    val id: String,
    val displayName: String,
    val relativeRoot: String,
    val kind: BuildKind,
    val includedBy: String? = null,
    val provenance: List<Evidence<String>> = emptyList(),
    val fingerprint: String,
    val modules: List<ModuleSnapshot> = emptyList(),
    val dependencies: List<DependencyFact> = emptyList(),
) {
    init {
        requireProjectRelativePath(relativeRoot, allowCurrentDirectory = true)
    }
}

data class ModuleSnapshot(
    val id: String,
    val buildId: String,
    val ideModuleId: String?,
    val gradlePath: String?,
    val role: ModuleRole,
    val sourceRoots: List<SourceRootSnapshot> = emptyList(),
    val sdk: Evidence<Int> = unknownEvidence("module-sdk"),
    val languageMix: List<SourceLanguage> = emptyList(),
)

data class SourceRootSnapshot(
    val id: String,
    val moduleId: String,
    val relativePath: String,
    val kind: SourceRootKind,
    val language: SourceLanguage,
    val generated: Boolean,
    val test: Boolean,
    val provenance: Evidence<String>,
) {
    init {
        requireProjectRelativePath(relativePath)
    }
}

data class DependencyFact(
    val coordinate: String,
    val selectedVersion: Evidence<String>,
    val scope: String,
    val resolved: Boolean?,
    val origin: DependencyOrigin,
    val owningModule: String,
)

data class OptionalIdeCapability(
    val id: String,
    val present: Boolean?,
    val enabled: Boolean?,
    val version: String?,
    val source: String?,
    val confidence: EvidenceConfidence,
    val diagnostic: DiscoveryDiagnostic?,
)

data class JmixProfile(
    val classification: ProfileClassification = ProfileClassification.UNKNOWN,
    val platformVersion: Evidence<String> = unknownEvidence("jmix-platform-version"),
    val platformLine: Evidence<String> = unknownEvidence("jmix-platform-line"),
    val targetJdk: Evidence<Int> = unknownEvidence("target-jdk"),
    val basePackages: List<Evidence<String>> = emptyList(),
    val plugins: List<Evidence<String>> = emptyList(),
    val addOns: List<Evidence<String>> = emptyList(),
    val stores: List<Evidence<String>> = emptyList(),
    val migrationRoots: List<Evidence<String>> = emptyList(),
    val languages: List<Evidence<SourceLanguage>> = emptyList(),
    val topology: Evidence<String> = unknownEvidence("build-topology"),
    val evidence: List<Evidence<String>> = emptyList(),
    val diagnostics: List<DiscoveryDiagnostic> = emptyList(),
    val optionalIdeCapabilities: List<OptionalIdeCapability> = emptyList(),
)

data class ArtifactOwner(
    val buildId: String,
    val moduleId: String,
    val sourceSetId: String?,
)

data class ArtifactSnapshot(
    val id: String,
    val kind: ArtifactKind,
    val semanticKey: String,
    val owner: ArtifactOwner,
    val sourceLocator: SourceLocator,
    val origin: ArtifactOrigin,
    val fingerprint: String,
    val displayName: String,
    val summary: String?,
    val diagnostics: List<DiscoveryDiagnostic> = emptyList(),
)

data class ArtifactRelationship(
    val sourceArtifactId: String,
    val targetArtifactId: String?,
    val type: RelationshipType,
    val sourceLocator: SourceLocator,
    val diagnostic: DiscoveryDiagnostic? = null,
)

data class DiscoveryDiagnostic(
    val id: String,
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val reasonCode: String,
    val message: String,
    val nextStep: String?,
    val sourceLocator: SourceLocator? = null,
)

data class SourceLocator(
    val relativePath: String,
    val symbol: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val revisionFingerprint: String,
) {
    init {
        requireProjectRelativePath(relativePath)
        require(line == null || line >= 1) { "Source line must be one-based." }
        require(column == null || column >= 1) { "Source column must be one-based." }
        require(revisionFingerprint.isNotBlank()) { "A source revision fingerprint is required." }
    }
}

data class CompatibilityDecision(
    val operationId: String,
    val state: CompatibilityState,
    val reasonCode: String,
    val explanation: String,
    val evidenceIds: List<String> = emptyList(),
    val missingEvidence: List<String> = emptyList(),
    val testedJmix: List<String> = emptyList(),
    val testedTargetJdks: List<Int> = emptyList(),
    val testedHostLanes: List<String> = emptyList(),
    val fixtureIds: List<String> = emptyList(),
    val registryVersion: String,
    val registryDigest: String,
    val testedAlternative: String? = null,
)

private fun requireProjectRelativePath(path: String, allowCurrentDirectory: Boolean = false) {
    require(path.isNotBlank()) { "Project-relative path cannot be blank." }
    if (allowCurrentDirectory && path == ".") {
        return
    }

    require('\\' !in path) { "Project-relative paths must use forward slashes." }
    require(!path.startsWith('/')) { "Absolute paths are forbidden." }
    require(!Regex("^[A-Za-z]:/").containsMatchIn(path)) { "Absolute paths are forbidden." }
    require(path.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
        "Path traversal and ambiguous path segments are forbidden."
    }
}

private fun <T> unknownEvidence(sourceId: String): Evidence<T> =
    Evidence(
        value = null,
        sourceKind = EvidenceSourceKind.UNKNOWN,
        sourceId = sourceId,
        confidence = EvidenceConfidence.WEAK,
        observedFingerprint = "",
    )

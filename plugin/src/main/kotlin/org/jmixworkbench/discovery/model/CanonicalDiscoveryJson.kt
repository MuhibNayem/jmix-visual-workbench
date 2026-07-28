package org.jmixworkbench.discovery.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

object CanonicalDiscoveryJson {
    fun encode(snapshot: DiscoverySnapshot): String =
        writeCanonical(
            mapOf(
                "artifacts" to snapshot.artifacts.sortedBy(ArtifactSnapshot::id).map(::artifactMap),
                "builds" to snapshot.builds.sortedBy(BuildSnapshot::id).map(::buildMap),
                "compatibilityDecisions" to snapshot.compatibilityDecisions
                    .sortedBy(CompatibilityDecision::operationId)
                    .map(::compatibilityDecisionMap),
                "diagnostics" to snapshot.diagnostics.sortedBy(DiscoveryDiagnostic::id).map(::diagnosticMap),
                "importState" to snapshot.importState,
                "optionalIdeCapabilities" to snapshot.optionalIdeCapabilities
                    .sortedBy(OptionalIdeCapability::id)
                    .map(::optionalIdeCapabilityMap),
                "profile" to profileMap(snapshot.profile),
                "relationships" to snapshot.relationships
                    .sortedWith(
                        compareBy(
                            ArtifactRelationship::sourceArtifactId,
                            { it.targetArtifactId.orEmpty() },
                            { it.type.name },
                        ),
                    )
                    .map(::relationshipMap),
                "trustState" to snapshot.trustState,
            ),
        )

    fun snapshotId(snapshot: DiscoverySnapshot): String =
        sha256(encode(snapshot))

    fun artifactId(
        kind: ArtifactKind,
        buildId: String,
        moduleId: String,
        semanticKey: String,
    ): String =
        sha256(
            listOf(
                kind.name,
                normalizeIdentityPart(buildId),
                normalizeIdentityPart(moduleId),
                normalizeSemanticKey(semanticKey),
            ).joinToString(separator = "\u0000"),
        )

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        val result = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            result[index * 2] = digits[unsigned ushr 4]
            result[index * 2 + 1] = digits[unsigned and 0x0f]
        }
        return result.concatToString()
    }

    private fun normalizeIdentityPart(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

    private fun normalizeSemanticKey(value: String): String =
        Normalizer.normalize(
            value.trim().replace('\\', '/').replace(Regex("\\s+"), " "),
            Normalizer.Form.NFC,
        )

    private fun buildMap(build: BuildSnapshot): Map<String, Any?> =
        mapOf(
            "dependencies" to build.dependencies
                .sortedWith(compareBy(DependencyFact::owningModule, DependencyFact::coordinate))
                .map(::dependencyMap),
            "displayName" to build.displayName,
            "fingerprint" to build.fingerprint,
            "id" to build.id,
            "includedBy" to build.includedBy,
            "kind" to build.kind,
            "modules" to build.modules.sortedBy(ModuleSnapshot::id).map(::moduleMap),
            "provenance" to evidenceList(build.provenance),
            "relativeRoot" to build.relativeRoot,
        )

    private fun moduleMap(module: ModuleSnapshot): Map<String, Any?> =
        mapOf(
            "buildId" to module.buildId,
            "gradlePath" to module.gradlePath,
            "id" to module.id,
            "ideModuleId" to module.ideModuleId,
            "languageMix" to module.languageMix.distinct().sortedBy(Enum<*>::name),
            "role" to module.role,
            "sdk" to evidenceMap(module.sdk),
            "sourceRoots" to module.sourceRoots.sortedBy(SourceRootSnapshot::id).map(::sourceRootMap),
        )

    private fun sourceRootMap(sourceRoot: SourceRootSnapshot): Map<String, Any?> =
        mapOf(
            "generated" to sourceRoot.generated,
            "id" to sourceRoot.id,
            "kind" to sourceRoot.kind,
            "language" to sourceRoot.language,
            "moduleId" to sourceRoot.moduleId,
            "provenance" to evidenceMap(sourceRoot.provenance),
            "relativePath" to sourceRoot.relativePath,
            "test" to sourceRoot.test,
        )

    private fun dependencyMap(dependency: DependencyFact): Map<String, Any?> =
        mapOf(
            "coordinate" to dependency.coordinate,
            "origin" to dependency.origin,
            "owningModule" to dependency.owningModule,
            "resolved" to dependency.resolved,
            "scope" to dependency.scope,
            "selectedVersion" to evidenceMap(dependency.selectedVersion),
        )

    private fun profileMap(profile: JmixProfile): Map<String, Any?> =
        mapOf(
            "addOns" to evidenceList(profile.addOns),
            "basePackages" to evidenceList(profile.basePackages),
            "classification" to profile.classification,
            "diagnostics" to profile.diagnostics.sortedBy(DiscoveryDiagnostic::id).map(::diagnosticMap),
            "evidence" to evidenceList(profile.evidence),
            "languages" to evidenceList(profile.languages),
            "migrationRoots" to evidenceList(profile.migrationRoots),
            "optionalIdeCapabilities" to profile.optionalIdeCapabilities
                .sortedBy(OptionalIdeCapability::id)
                .map(::optionalIdeCapabilityMap),
            "platformLine" to evidenceMap(profile.platformLine),
            "platformVersion" to evidenceMap(profile.platformVersion),
            "plugins" to evidenceList(profile.plugins),
            "stores" to evidenceList(profile.stores),
            "targetJdk" to evidenceMap(profile.targetJdk),
            "topology" to evidenceMap(profile.topology),
        )

    private fun artifactMap(artifact: ArtifactSnapshot): Map<String, Any?> =
        mapOf(
            "diagnostics" to artifact.diagnostics.sortedBy(DiscoveryDiagnostic::id).map(::diagnosticMap),
            "displayName" to artifact.displayName,
            "fingerprint" to artifact.fingerprint,
            "id" to artifact.id,
            "kind" to artifact.kind,
            "origin" to artifact.origin,
            "owner" to mapOf(
                "buildId" to artifact.owner.buildId,
                "moduleId" to artifact.owner.moduleId,
                "sourceSetId" to artifact.owner.sourceSetId,
            ),
            "semanticKey" to normalizeSemanticKey(artifact.semanticKey),
            "sourceLocator" to sourceLocatorMap(artifact.sourceLocator),
            "summary" to artifact.summary,
        )

    private fun relationshipMap(relationship: ArtifactRelationship): Map<String, Any?> =
        mapOf(
            "diagnostic" to relationship.diagnostic?.let(::diagnosticMap),
            "sourceArtifactId" to relationship.sourceArtifactId,
            "sourceLocator" to sourceLocatorMap(relationship.sourceLocator),
            "targetArtifactId" to relationship.targetArtifactId,
            "type" to relationship.type,
        )

    private fun diagnosticMap(diagnostic: DiscoveryDiagnostic): Map<String, Any?> =
        mapOf(
            "category" to diagnostic.category,
            "id" to diagnostic.id,
            "message" to diagnostic.message,
            "nextStep" to diagnostic.nextStep,
            "reasonCode" to diagnostic.reasonCode,
            "severity" to diagnostic.severity,
            "sourceLocator" to diagnostic.sourceLocator?.let(::sourceLocatorMap),
        )

    private fun sourceLocatorMap(locator: SourceLocator): Map<String, Any?> =
        mapOf(
            "column" to locator.column,
            "line" to locator.line,
            "relativePath" to locator.relativePath,
            "revisionFingerprint" to locator.revisionFingerprint,
            "symbol" to locator.symbol,
        )

    private fun optionalIdeCapabilityMap(capability: OptionalIdeCapability): Map<String, Any?> =
        mapOf(
            "confidence" to capability.confidence,
            "diagnostic" to capability.diagnostic?.let(::diagnosticMap),
            "enabled" to capability.enabled,
            "id" to capability.id,
            "present" to capability.present,
            "source" to capability.source,
            "version" to capability.version,
        )

    private fun compatibilityDecisionMap(decision: CompatibilityDecision): Map<String, Any?> =
        mapOf(
            "evidenceIds" to decision.evidenceIds.distinct().sorted(),
            "explanation" to decision.explanation,
            "fixtureIds" to decision.fixtureIds.distinct().sorted(),
            "missingEvidence" to decision.missingEvidence.distinct().sorted(),
            "operationId" to decision.operationId,
            "reasonCode" to decision.reasonCode,
            "registryDigest" to decision.registryDigest,
            "registryVersion" to decision.registryVersion,
            "state" to decision.state,
            "testedAlternative" to decision.testedAlternative,
            "testedHostLanes" to decision.testedHostLanes.distinct().sorted(),
            "testedJmix" to decision.testedJmix.distinct().sorted(),
            "testedTargetJdks" to decision.testedTargetJdks.distinct().sorted(),
        )

    private fun evidenceList(evidence: List<Evidence<*>>): List<Map<String, Any?>> =
        evidence.map(::evidenceMap).sortedBy(::writeCanonical)

    private fun evidenceMap(evidence: Evidence<*>): Map<String, Any?> =
        mapOf(
            "confidence" to evidence.confidence,
            "observedFingerprint" to evidence.observedFingerprint,
            "observedValues" to evidence.observedValues
                .map(::jsonValue)
                .sortedBy(::writeCanonical),
            "sourceId" to evidence.sourceId,
            "sourceKind" to evidence.sourceKind,
            "value" to jsonValue(evidence.value),
        )

    private fun jsonValue(value: Any?): Any? =
        when (value) {
            null,
            is String,
            is Boolean,
            is Number,
            is Enum<*>,
            -> value

            else -> error("Unsupported canonical evidence value: ${value::class.qualifiedName}")
        }

    private fun writeCanonical(value: Any?): String =
        buildString {
            appendCanonicalValue(value)
        }

    private fun StringBuilder.appendCanonicalValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendJsonString(value)
            is Boolean -> append(if (value) "true" else "false")
            is Byte,
            is Short,
            is Int,
            is Long,
            -> append(value.toString())

            is Float -> {
                require(value.isFinite()) { "Non-finite numbers are not valid canonical JSON." }
                append(value.toString())
            }

            is Double -> {
                require(value.isFinite()) { "Non-finite numbers are not valid canonical JSON." }
                append(value.toString())
            }

            is Enum<*> -> appendJsonString(value.name)
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendCanonicalValue(item)
                }
                append(']')
            }

            is Map<*, *> -> {
                val entries = value.entries.map { (key, item) ->
                    require(key is String) { "Canonical JSON object keys must be strings." }
                    key to item
                }.sortedWith { left, right -> compareUtf8(left.first, right.first) }

                append('{')
                entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    appendJsonString(key)
                    append(':')
                    appendCanonicalValue(item)
                }
                append('}')
            }

            else -> error("Unsupported canonical JSON value: ${value::class.qualifiedName}")
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun compareUtf8(left: String, right: String): Int {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        val commonLength = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until commonLength) {
            val comparison = (leftBytes[index].toInt() and 0xff)
                .compareTo(rightBytes[index].toInt() and 0xff)
            if (comparison != 0) {
                return comparison
            }
        }
        return leftBytes.size.compareTo(rightBytes.size)
    }
}

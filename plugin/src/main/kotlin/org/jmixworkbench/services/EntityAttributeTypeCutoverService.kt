package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.model.AttributeType
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Guards the source and mapping halves of an expand-contract type evolution.
 *
 * Verification capabilities are memory-only, expire quickly, are bound to the
 * exact entity/property/type/schema identity, and never contain JDBC secrets.
 * The final mapping edit changes only the literal value of @Column(name = ...).
 */
@Service(Service.Level.PROJECT)
class EntityAttributeTypeCutoverService(
    private val project: Project,
) {
    private val capabilities = ConcurrentHashMap<String, VerifiedExpansionCapability>()
    private val random = SecureRandom()
    private val clock: Clock = Clock.systemUTC()

    fun verify(
        request: EntityAttributeTypeMigrationRequest,
    ): EntityAttributeTypeExpansionVerificationResponse {
        purgeExpired()
        val recoverable = request.verificationToken
            ?.let(capabilities::get)
            ?.takeIf {
                it.expiresAtEpochMillis + CAPABILITY_RECOVERY_MILLIS > clock.millis() &&
                    it.descriptor.entityClassName == request.entityClassName &&
                    it.descriptor.attributeName == request.attributeName &&
                    it.descriptor.targetType == request.targetType
            }
        val descriptor = if (recoverable != null) {
            recoverable.descriptor
        } else {
            val description = ReadAction.nonBlocking<EntityAttributeTypeExpansionDescription> {
                EntityAttributeTypeExpansionService.getInstance(project).describe(request)
            }
                .inSmartMode(project)
                .expireWith(project)
                .executeSynchronously()
            description.descriptor
                ?: return EntityAttributeTypeExpansionVerificationResponse.failure(
                    description.code ?: "JVW-ENTITY-TYPE-CUTOVER-DESCRIPTION-REJECTED",
                    description.message,
                )
        }
        val live = DatabaseReverseEngineeringService.getInstance(project)
            .verifyEntityTypeExpansion(descriptor)
        if (!live.accepted || live.evidenceDigest == null) {
            return EntityAttributeTypeExpansionVerificationResponse.fromLiveFailure(live)
        }
        val token = newToken()
        val expiresAt = clock.millis() + CAPABILITY_TTL_MILLIS
        if (capabilities.size >= MAX_CAPABILITIES) {
            capabilities.entries.minByOrNull { it.value.issuedAtEpochMillis }
                ?.let { capabilities.remove(it.key, it.value) }
        }
        capabilities[token] = VerifiedExpansionCapability(
            descriptor = descriptor,
            evidenceDigest = live.evidenceDigest,
            database = requireNotNull(live.database),
            issuedAtEpochMillis = clock.millis(),
            expiresAtEpochMillis = expiresAt,
            sourceMigrationLaunched = recoverable?.sourceMigrationLaunched == true,
        )
        return EntityAttributeTypeExpansionVerificationResponse(
            accepted = true,
            code = null,
            message = live.message,
            verificationToken = token,
            expiresAtEpochMillis = expiresAt,
            evidenceDigest = live.evidenceDigest,
            database = live.database,
            shadowColumnName = descriptor.shadowColumnName,
            targetSqlType = descriptor.targetSqlType,
            inconsistentBackfillRows = live.inconsistentBackfillRows,
        )
    }

    fun authorizeSourceMigration(
        request: EntityAttributeTypeMigrationRequest,
        prepared: PreparedEntityAttributeTypeMigration,
    ): WorkspaceChangeIssue? {
        if (prepared.schemaImpact?.strategy == EntityAttributeTypeSchemaStrategy.SOURCE_ONLY) return null
        if (prepared.schemaImpact?.strategy != EntityAttributeTypeSchemaStrategy.EXPAND_CONTRACT_REQUIRED) {
            return WorkspaceChangeIssue(
                "JVW-ENTITY-TYPE-MIGRATION-SCHEMA-STAGE-REQUIRED",
                "This schema strategy cannot be authorized by a managed expansion verification.",
            )
        }
        val token = request.verificationToken.orEmpty()
        val capability = validCapability(token)
            ?: return WorkspaceChangeIssue(
                "JVW-ENTITY-TYPE-CUTOVER-VERIFICATION-REQUIRED",
                "Verify the deployed shadow column and backfill immediately before opening source migration.",
            )
        val description = EntityAttributeTypeExpansionService.getInstance(project).describe(
            request.copy(verificationToken = null),
        )
        val descriptor = description.descriptor
            ?: return WorkspaceChangeIssue(
                description.code ?: "JVW-ENTITY-TYPE-CUTOVER-DESCRIPTION-REJECTED",
                description.message,
            )
        if (!capability.descriptor.sameCutover(descriptor)) {
            return WorkspaceChangeIssue(
                "JVW-ENTITY-TYPE-CUTOVER-VERIFICATION-MISMATCH",
                "The live verification belongs to a different entity revision, property, type, or physical column.",
            )
        }
        capabilities.computeIfPresent(token) { _, current ->
            current.copy(sourceMigrationLaunched = true)
        }
        return null
    }

    fun previewMappingCutover(
        request: EntityAttributeTypeMappingCutoverRequest,
    ): WorkspaceChangePreviewResponse =
        prepareMappingCutover(request).let { proposal ->
            proposal.changeSet?.let(WorkspaceChangeService.getInstance(project)::preview)
                ?: proposal.rejectedPreview()
        }

    fun prepareMappingCutoverApply(
        request: EntityAttributeTypeMappingCutoverApplyRequest,
    ): PreparedWorkspaceChange {
        val proposal = prepareMappingCutover(request.change)
        val changeSet = proposal.changeSet
            ?: return proposal.rejectedPrepared()
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    private fun prepareMappingCutover(
        request: EntityAttributeTypeMappingCutoverRequest,
    ): EntityAttributeTypeMappingCutoverProposal {
        purgeExpired()
        val capability = validCapability(request.verificationToken)
            ?: return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-VERIFICATION-EXPIRED",
                "Live verification expired. Verify the deployed backfill again before mapping cutover.",
                request.sourceLocator.relativePath,
            )
        val live = DatabaseReverseEngineeringService.getInstance(project)
            .verifyEntityTypeExpansion(capability.descriptor)
        if (!live.accepted || live.evidenceDigest == null) {
            return EntityAttributeTypeMappingCutoverProposal.failure(
                live.code ?: "JVW-ENTITY-TYPE-CUTOVER-DB-FAILED",
                live.message,
                request.sourceLocator.relativePath,
            )
        }
        return ReadAction.nonBlocking<EntityAttributeTypeMappingCutoverProposal> {
            prepareMappingCutoverUnderRead(request, live)
        }
            .inSmartMode(project)
            .expireWith(project)
            .executeSynchronously()
    }

    private fun prepareMappingCutoverUnderRead(
        request: EntityAttributeTypeMappingCutoverRequest,
        live: DatabaseEntityTypeExpansionVerification,
    ): EntityAttributeTypeMappingCutoverProposal {
        purgeExpired()
        val capability = validCapability(request.verificationToken)
            ?: return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-VERIFICATION-EXPIRED",
                "Live verification expired. Verify the deployed backfill again before mapping cutover.",
                request.sourceLocator.relativePath,
            )
        if (!capability.sourceMigrationLaunched) {
            return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-SOURCE-STAGE-MISSING",
                "Open and apply IntelliJ Type Migration before switching the database mapping.",
                request.sourceLocator.relativePath,
            )
        }
        val descriptor = capability.descriptor
        if (
            descriptor.entityClassName != request.entityClassName ||
            descriptor.attributeName != request.attributeName ||
            descriptor.targetType != request.targetType
        ) {
            return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-VERIFICATION-MISMATCH",
                "The verification capability belongs to a different entity property or target type.",
                request.sourceLocator.relativePath,
            )
        }
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.sourceLocator.relativePath)
            ?: return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-SOURCE-MISSING",
                "The indexed entity source no longer exists.",
                request.sourceLocator.relativePath,
            )
        val file = resolved.file
        if (
            file.isDirectory ||
            file.extension !in setOf("java", "kt") ||
            !VfsUtilCore.isAncestor(resolved.root, file, false)
        ) {
            return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-SOURCE-INVALID",
                "Mapping cutover requires Java or Kotlin source inside project content.",
                request.sourceLocator.relativePath,
            )
        }
        val source = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-SOURCE-UNREADABLE",
                "The entity source cannot be read.",
                request.sourceLocator.relativePath,
            )
        }
        if (CanonicalDiscoveryJson.sha256(source) != request.sourceLocator.revisionFingerprint) {
            return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-SOURCE-STALE",
                "The entity changed after refresh. Refresh Entity Designer and preview cutover again.",
                request.sourceLocator.relativePath,
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load()
        val entity = workspace.entities.singleOrNull {
            it.className == request.entityClassName &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        } ?: return EntityAttributeTypeMappingCutoverProposal.failure(
            "JVW-ENTITY-TYPE-CUTOVER-SNAPSHOT-MISSING",
            "The exact entity snapshot is unavailable.",
            request.sourceLocator.relativePath,
        )
        val attribute = entity.attributes.singleOrNull { it.name == request.attributeName }
            ?: return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-ATTRIBUTE-MISSING",
                "The exact entity property is unavailable.",
                request.sourceLocator.relativePath,
            )
        if (!javaTypeMatches(attribute.javaType, request.targetType)) {
            return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-SOURCE-TYPE-NOT-APPLIED",
                "${request.attributeName} still has ${attribute.javaType}. Apply IntelliJ Type Migration to ${request.targetType} and refresh first.",
                request.sourceLocator.relativePath,
            )
        }
        if (!attribute.columnName.equals(descriptor.originalColumnName, ignoreCase = true)) {
            return EntityAttributeTypeMappingCutoverProposal.failure(
                "JVW-ENTITY-TYPE-CUTOVER-MAPPING-DRIFT",
                "The property no longer maps exactly to ${descriptor.originalColumnName}. Refresh and investigate before cutover.",
                request.sourceLocator.relativePath,
            )
        }
        val edit = exactColumnNameEdit(
            psiFile = PsiManager.getInstance(project).findFile(file),
            fileExtension = file.extension.orEmpty(),
            className = request.entityClassName,
            attributeName = request.attributeName,
            expectedColumnName = descriptor.originalColumnName,
            replacementColumnName = descriptor.shadowColumnName,
        ) ?: return EntityAttributeTypeMappingCutoverProposal.failure(
            "JVW-ENTITY-TYPE-CUTOVER-ANNOTATION-UNSTABLE",
            "An exact literal @Column(name = \"${descriptor.originalColumnName}\") was not found on the live property.",
            request.sourceLocator.relativePath,
        )
        capabilities.computeIfPresent(request.verificationToken) { _, current ->
            current.copy(
                evidenceDigest = requireNotNull(live.evidenceDigest),
                database = live.database ?: current.database,
                expiresAtEpochMillis = clock.millis() + CAPABILITY_TTL_MILLIS,
            )
        }
        val digest = CanonicalDiscoveryJson.sha256(
            listOf(
                entity.qualifiedName,
                request.attributeName,
                descriptor.originalColumnName,
                descriptor.shadowColumnName,
                request.sourceLocator.revisionFingerprint,
                live.evidenceDigest,
            ).joinToString("\u0000"),
        ).take(16)
        return EntityAttributeTypeMappingCutoverProposal(
            changeSet = WorkspaceChangeSet(
                id = "entity-type-mapping-cutover:$digest",
                label = "Switch ${entity.qualifiedName}.${request.attributeName} to ${descriptor.shadowColumnName}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.sourceLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = request.sourceLocator.revisionFingerprint,
                        edits = listOf(edit),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun exactColumnNameEdit(
        psiFile: com.intellij.psi.PsiFile?,
        fileExtension: String,
        className: String,
        attributeName: String,
        expectedColumnName: String,
        replacementColumnName: String,
    ): WorkspaceTextEdit? {
        if (psiFile == null) return null
        return columnEditFromPsi(
            psiFile,
            fileExtension,
            className,
            attributeName,
            expectedColumnName,
            replacementColumnName,
        )
    }

    internal fun columnEditFromPsi(
        psiFile: com.intellij.psi.PsiFile,
        fileExtension: String,
        className: String,
        attributeName: String,
        expectedColumnName: String,
        replacementColumnName: String,
    ): WorkspaceTextEdit? {
        val annotation = if (fileExtension == "java" && psiFile is PsiJavaFile) {
            psiFile.classes.singleOrNull { it.name == className }
                ?.fields
                ?.singleOrNull { it.name == attributeName }
                ?.modifierList
                ?.annotations
                ?.singleOrNull {
                    it.nameReferenceElement?.text?.substringAfterLast('.') == "Column"
                }
        } else {
            val owner = PsiTreeUtil.findChildrenOfType(psiFile, PsiNamedElement::class.java)
                .singleOrNull { it.javaClass.simpleName == "KtClass" && it.name == className }
                ?: return null
            val property = PsiTreeUtil.findChildrenOfType(owner, PsiNamedElement::class.java)
                .singleOrNull {
                    it.javaClass.simpleName == "KtProperty" &&
                        it.name == attributeName &&
                        it.nearestKotlinClass() === owner
                } ?: return null
            PsiTreeUtil.findChildrenOfType(property, com.intellij.psi.PsiElement::class.java)
                .singleOrNull {
                    it.javaClass.simpleName == "KtAnnotationEntry" &&
                        it.text.substringBefore('(')
                            .substringAfterLast(':')
                            .substringAfterLast('.')
                            .removePrefix("@") == "Column"
                }
        } ?: return null
        val match = COLUMN_NAME_LITERAL.find(annotation.text) ?: return null
        if (match.groupValues[1] != expectedColumnName) return null
        val valueRange = match.groups[1]?.range ?: return null
        val start = annotation.textRange.startOffset + valueRange.first
        val end = annotation.textRange.startOffset + valueRange.last + 1
        return WorkspaceTextEdit(
            startOffset = start,
            endOffset = end,
            expectedText = expectedColumnName,
            replacement = replacementColumnName,
        )
    }

    private fun validCapability(token: String): VerifiedExpansionCapability? {
        if (token.isBlank() || token.length > 128) return null
        val capability = capabilities[token] ?: return null
        if (capability.expiresAtEpochMillis <= clock.millis()) {
            return null
        }
        return capability
    }

    private fun purgeExpired() {
        val now = clock.millis()
        capabilities.entries.removeIf {
            it.value.expiresAtEpochMillis + CAPABILITY_RECOVERY_MILLIS <= now
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val CAPABILITY_TTL_MILLIS = 20 * 60 * 1_000L
        private const val CAPABILITY_RECOVERY_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_CAPABILITIES = 256
        private val COLUMN_NAME_LITERAL =
            Regex("""(?s)\bname\s*=\s*"([A-Za-z_][A-Za-z0-9_]*)"""")

        fun getInstance(project: Project): EntityAttributeTypeCutoverService =
            project.getService(EntityAttributeTypeCutoverService::class.java)

        private fun javaTypeMatches(javaType: String, target: AttributeType): Boolean {
            val normalized = javaType.trim().removeSuffix("?").substringAfterLast('.')
            val candidates = when (target) {
                AttributeType.STRING -> setOf("String")
                AttributeType.CHARACTER -> setOf("Character", "Char")
                AttributeType.INTEGER -> setOf("Integer", "Int", "int")
                AttributeType.LONG -> setOf("Long", "long")
                AttributeType.DOUBLE -> setOf("Double", "double")
                AttributeType.BIG_DECIMAL -> setOf("BigDecimal")
                AttributeType.BOOLEAN -> setOf("Boolean", "boolean")
                AttributeType.DATE -> setOf("Date")
                AttributeType.LOCAL_DATE -> setOf("LocalDate")
                AttributeType.LOCAL_DATE_TIME -> setOf("LocalDateTime")
                AttributeType.LOCAL_TIME -> setOf("LocalTime")
                AttributeType.OFFSET_TIME -> setOf("OffsetTime")
                AttributeType.OFFSET_DATE_TIME -> setOf("OffsetDateTime")
                AttributeType.SQL_DATE -> setOf("Date")
                AttributeType.SQL_TIME -> setOf("Time")
                AttributeType.UUID -> setOf("UUID")
                AttributeType.URI -> setOf("URI")
                AttributeType.BYTE_ARRAY -> setOf("byte[]", "ByteArray")
                AttributeType.FILE_REF -> setOf("FileRef")
                else -> emptySet()
            }
            return normalized in candidates
        }
    }
}

data class EntityAttributeTypeExpansionVerificationResponse(
    val accepted: Boolean,
    val code: String?,
    val message: String,
    val verificationToken: String?,
    val expiresAtEpochMillis: Long?,
    val evidenceDigest: String?,
    val database: DatabaseProductSnapshot?,
    val shadowColumnName: String?,
    val targetSqlType: String?,
    val inconsistentBackfillRows: Long?,
) {
    companion object {
        fun failure(code: String, message: String) =
            EntityAttributeTypeExpansionVerificationResponse(
                false,
                code,
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            )

        fun fromLiveFailure(live: DatabaseEntityTypeExpansionVerification) =
            EntityAttributeTypeExpansionVerificationResponse(
                false,
                live.code,
                live.message,
                null,
                null,
                null,
                live.database,
                live.shadowColumn?.name,
                live.shadowColumn?.typeName,
                live.inconsistentBackfillRows,
            )
    }
}

data class EntityAttributeTypeMappingCutoverRequest(
    val sourceLocator: SourceLocator,
    val entityClassName: String,
    val attributeName: String,
    val targetType: AttributeType,
    val verificationToken: String,
)

data class EntityAttributeTypeMappingCutoverApplyRequest(
    val change: EntityAttributeTypeMappingCutoverRequest,
    val expectedPlanDigest: String,
)

private data class VerifiedExpansionCapability(
    val descriptor: EntityAttributeTypeExpansionDescriptor,
    val evidenceDigest: String,
    val database: DatabaseProductSnapshot,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val sourceMigrationLaunched: Boolean,
)

private fun EntityAttributeTypeExpansionDescriptor.sameCutover(
    other: EntityAttributeTypeExpansionDescriptor,
): Boolean =
    storeId == other.storeId &&
        schemaName == other.schemaName &&
        tableName == other.tableName &&
        originalColumnName == other.originalColumnName &&
        shadowColumnName == other.shadowColumnName &&
        targetSqlType == other.targetSqlType &&
        sourceRevisionFingerprint == other.sourceRevisionFingerprint &&
        entityClassName == other.entityClassName &&
        attributeName == other.attributeName &&
        targetType == other.targetType

private data class EntityAttributeTypeMappingCutoverProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
) {
    fun rejectedPreview() = WorkspaceChangePreviewResponse(
        accepted = false,
        changeSetId = "entity-type-mapping-cutover:rejected",
        label = "Entity type mapping cutover rejected",
        planDigest = null,
        files = emptyList(),
        issues = issues,
    )

    fun rejectedPrepared() = PreparedWorkspaceChange(
        plan = WorkspaceChangePlan(
            accepted = false,
            changeSetId = "entity-type-mapping-cutover:rejected",
            label = "Entity type mapping cutover rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        ),
        baseDir = null,
    )

    companion object {
        fun failure(code: String, message: String, relativePath: String) =
            EntityAttributeTypeMappingCutoverProposal(
                null,
                listOf(WorkspaceChangeIssue(code, message, relativePath)),
            )
    }
}

private fun com.intellij.psi.PsiElement.nearestKotlinClass(): com.intellij.psi.PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }

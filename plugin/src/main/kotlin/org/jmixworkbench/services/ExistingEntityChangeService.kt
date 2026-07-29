package org.jmixworkbench.services

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
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
import org.jmixworkbench.generator.EntityGenerator
import org.jmixworkbench.generator.KotlinEntityGenerator
import org.jmixworkbench.model.AttributeModel
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.ChangeSetModel
import org.jmixworkbench.model.ColumnDef
import org.jmixworkbench.model.DatabaseType
import org.jmixworkbench.model.DbChange
import org.jmixworkbench.model.DdlGenerationMode
import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.EnumIdType
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.MigrationModel
import org.jmixworkbench.model.PreCondition
import org.jmixworkbench.model.PreConditionType
import java.util.Locale

/**
 * Safely evolves an already existing Jmix entity without regenerating its Java source.
 *
 * The service deliberately works from an exact indexed source revision, inserts only
 * generated fields/accessors/imports or managed persistence-annotation edits, validates
 * the resulting Java PSI, and combines code with Liquibase in one revision-bound change.
 * Existing methods, comments, formatting and unrecognised annotations remain untouched.
 */
@Service(Service.Level.PROJECT)
class ExistingEntityChangeService(
    private val project: Project,
) {
    fun previewAttributeAdditions(
        request: ExistingEntityAttributeAdditionRequest,
    ): WorkspaceChangePreviewResponse {
        val proposal = proposeAttributeAdditions(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: proposal.rejectedPreview()
    }

    fun prepareAttributeAdditions(
        request: ExistingEntityAttributeAdditionApplyRequest,
    ): PreparedWorkspaceChange {
        val proposal = proposeAttributeAdditions(request.change)
        val changeSet = proposal.changeSet
            ?: return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "existing-entity-additions:rejected",
                    label = "Existing entity update rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues,
                ),
                baseDir = null,
            )
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    private fun proposeAttributeAdditions(
        request: ExistingEntityAttributeAdditionRequest,
    ): ExistingEntityChangeProposal {
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.sourceLocator.relativePath)
            ?: return rejected("JVW-ENTITY-SOURCE-MISSING", "The indexed entity source no longer exists.")
        val file = resolved.file
        if (
            file.isDirectory ||
            file.extension !in setOf("java", "kt") ||
            !VfsUtilCore.isAncestor(resolved.root, file, false)
        ) {
            return rejected(
                "JVW-ENTITY-SOURCE-INVALID",
                "Existing entity changes require a Java or Kotlin source inside a registered project content root.",
            )
        }
        val content = runCatching {
            ProjectSourceText.read(file)
        }.getOrElse {
            return rejected("JVW-ENTITY-SOURCE-UNREADABLE", "The existing entity source cannot be read.")
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (request.sourceLocator.revisionFingerprint != fingerprint) {
            return rejected(
                "JVW-ENTITY-SOURCE-STALE",
                "The entity changed after it was indexed. Refresh the entity workspace before editing.",
            )
        }
        if (file.extension == "kt") {
            return proposeKotlinAttributeAdditions(request, file.name, file.fileType, content, fingerprint)
        }
        val psiFile = parseJava(file.name, content)
            ?: return rejected("JVW-ENTITY-SOURCE-PARSE", "The existing entity is not a valid Java compilation unit.")
        javaSyntaxError(psiFile)?.let { syntax ->
            return rejected(
                "JVW-ENTITY-SOURCE-PARSE",
                "The existing entity contains a Java syntax error: ${syntax.errorDescription}",
            )
        }
        if (psiFile.packageName != request.entity.packageName) {
            return rejected(
                "JVW-ENTITY-IDENTITY-CHANGED",
                "Package changes are not allowed in additive round-trip mode.",
            )
        }
        val entityClass = psiFile.classes.singleOrNull { it.name == request.entity.className }
            ?: return rejected(
                "JVW-ENTITY-CLASS-MISSING",
                "The indexed class ${request.entity.className} no longer exists in this source file.",
            )
        val existingFieldNames = entityClass.fields
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .mapNotNull { it.name }
            .toSet()
        val additions = request.entity.attributes.filter { it.name !in existingFieldNames }
        val duplicateNames = additions.groupingBy(AttributeModel::name)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateNames.isNotEmpty()) {
            return rejected(
                "JVW-ENTITY-ATTRIBUTE-DUPLICATE",
                "Duplicate attribute names: ${duplicateNames.sorted().joinToString()}.",
            )
        }
        additions.firstOrNull { !JAVA_IDENTIFIER.matches(it.name) }?.let { attribute ->
            return rejected(
                "JVW-ENTITY-ATTRIBUTE-NAME-INVALID",
                "'${attribute.name}' is not a valid Java field name.",
            )
        }
        additions.firstOrNull { it.type == AttributeType.EMBEDDED }?.let { attribute ->
            return rejected(
                "JVW-ENTITY-EMBEDDED-REQUIRES-DESIGNER",
                "${attribute.name} is embedded. Use the embedded-type designer so attribute overrides can be reviewed.",
            )
        }

        val currentEntity = SchemaWorkspaceService.getInstance(project).load().entities.firstOrNull {
            it.qualifiedName == request.entity.fullName &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        } ?: return rejected(
            "JVW-ENTITY-SNAPSHOT-MISSING",
            "The exact existing entity metadata could not be reconstructed. Refresh the schema workspace.",
        )
        val metadataChanges = mutableListOf<ExistingAttributeMetadataChange>()
        currentEntity.attributes.forEach { current ->
            if (current.name == "id") return@forEach
            val desired = request.entity.attributes.firstOrNull { it.name == current.name }
                ?: return rejected(
                    "JVW-ENTITY-REMOVAL-REQUIRES-IMPACT",
                    "${current.name} is missing from the requested entity model. " +
                        "Removal or rename requires the explicit native impact workflow; it will not be interpreted as an addition.",
                )
            val currentType = attributeType(
                current.javaType,
                current.association,
                current.associationDetails?.composition == true,
            )
            if (desired.type != currentType) {
                return rejected(
                    "JVW-ENTITY-TYPE-REFACTOR-REQUIRES-IMPACT",
                    "${current.name} changes Java type from ${current.javaType} ($currentType) to ${desired.type}. " +
                    "Use a project-wide refactor with call-site impact analysis.",
                )
            }
            val columnRenamed = desired.resolvedColumnName != current.columnName
            if (columnRenamed) {
                if (current.association) {
                    return rejected(
                        "JVW-ENTITY-RELATIONSHIP-COLUMN-RENAME-REQUIRES-IMPACT",
                        "${current.name} changes relationship column ${current.columnName} to " +
                            "${desired.resolvedColumnName}. Use the relationship choreography workflow.",
                    )
                }
                if (!current.persistent) {
                    return rejected(
                        "JVW-ENTITY-TRANSIENT-COLUMN-RENAME",
                        "${current.name} is transient and has no physical column to rename.",
                    )
                }
                if (!DATABASE_IDENTIFIER.matches(desired.resolvedColumnName)) {
                    return rejected(
                        "JVW-ENTITY-COLUMN-NAME-INVALID",
                        "'${desired.resolvedColumnName}' is not a portable unquoted database identifier.",
                    )
                }
                val collision = currentEntity.attributes.firstOrNull {
                    it.name != current.name &&
                        it.persistent &&
                        it.columnName.equals(desired.resolvedColumnName, ignoreCase = true)
                }
                if (collision != null) {
                    return rejected(
                        "JVW-ENTITY-COLUMN-RENAME-COLLISION",
                        "${desired.resolvedColumnName} is already mapped by ${collision.name}.",
                    )
                }
                if (
                    request.entity.databaseView ||
                    request.entity.ddlGeneration.effectiveMode == DdlGenerationMode.DISABLED
                ) {
                    return rejected(
                        "JVW-ENTITY-COLUMN-RENAME-DDL-REQUIRED",
                        "Physical column rename requires a managed Liquibase store and enabled DDL generation.",
                    )
                }
            }
            if (current.association) return@forEach
            if (!current.persistent) {
                if (
                    desired.mandatory != !current.nullable ||
                    desired.unique != current.unique ||
                    normalizedLength(desired) != normalizedLength(current) ||
                    desired.precision != current.precision ||
                    desired.scale != current.scale
                ) {
                    return rejected(
                        "JVW-ENTITY-TRANSIENT-MAPPING-INVALID",
                        "${current.name} is transient and has no database column metadata to evolve.",
                    )
                }
                return@forEach
            }
            if (
                desired.mandatory != !current.nullable ||
                desired.unique != current.unique ||
                normalizedLength(desired) != normalizedLength(current) ||
                desired.precision != current.precision ||
                desired.scale != current.scale ||
                columnRenamed
            ) {
                metadataChanges += ExistingAttributeMetadataChange(current, desired)
            }
        }
        val desiredColumnCollision = request.entity.attributes
            .asSequence()
            .filterNot(AttributeModel::transientFlag)
            .filterNot { it.type in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION) }
            .groupBy { it.resolvedColumnName.uppercase(Locale.ROOT) }
            .entries
            .firstOrNull { it.value.size > 1 }
        if (desiredColumnCollision != null) {
            return rejected(
                "JVW-ENTITY-COLUMN-MAPPING-DUPLICATE",
                "${desiredColumnCollision.value.joinToString { it.name }} map to the same column " +
                    "${desiredColumnCollision.key}.",
            )
        }
        metadataChanges.firstOrNull {
            it.current.unique && !it.desired.unique
        }?.let { change ->
            return rejected(
                "JVW-ENTITY-UNIQUE-DROP-REQUIRES-CONSTRAINT",
                "${change.current.name} is unique, but the physical constraint name is not provable from the field. " +
                    "Use the schema constraint designer to remove it explicitly.",
            )
        }
        metadataChanges.firstOrNull {
            val oldLength = normalizedLength(it.current)
            val newLength = normalizedLength(it.desired)
            oldLength != null && newLength != null && newLength < oldLength
        }?.let { change ->
            return rejected(
                "JVW-ENTITY-LENGTH-NARROWING-REQUIRES-DATA-AUDIT",
                "${change.current.name} narrows from ${normalizedLength(change.current)} to " +
                    "${normalizedLength(change.desired)} characters. Run the data-safe narrowing workflow.",
            )
        }
        if (additions.isEmpty() && metadataChanges.isEmpty()) {
            return rejected(
                "JVW-ENTITY-UPDATE-NOOP",
                "No source or persistence metadata changes were found.",
            )
        }

        val generated = if (additions.isEmpty()) {
            GeneratedEntityFragments("", emptySet())
        } else {
            runCatching { generatedFragments(request.entity, additions) }.getOrNull()
                ?: return rejected(
                    "JVW-ENTITY-FRAGMENT-GENERATION",
                    "The entity generator could not produce safe field/accessor fragments.",
                )
        }
        val edits = mutableListOf<WorkspaceTextEdit>()
        val metadataEdits = metadataAnnotationEdits(content, entityClass, metadataChanges)
            ?: return rejected(
                "JVW-ENTITY-METADATA-EDIT-UNSAFE",
                "A persistence annotation could not be updated without touching unmanaged source. " +
                    "Column renames require an explicit literal @Column(name = \"...\") mapping.",
            )
        importEdit(psiFile, generated.imports + metadataEdits.imports)?.let(edits::add)
        edits += metadataEdits.edits
        if (generated.body.isNotBlank()) {
            val rightBrace = entityClass.rBrace
                ?: return rejected("JVW-ENTITY-SOURCE-PARSE", "The entity class closing brace is missing.")
            edits += WorkspaceTextEdit(
                startOffset = rightBrace.textOffset,
                endOffset = rightBrace.textOffset,
                expectedText = "",
                replacement = "\n\n${generated.body}\n",
            )
        }
        val resultingSource = applyEdits(content, edits)
        val resultingPsi = parseJava(file.name, resultingSource)
            ?: return rejected(
                "JVW-ENTITY-SOURCE-SYNTAX",
                "The proposed entity source could not be parsed.",
            )
        javaSyntaxError(resultingPsi)?.let { syntax ->
            return rejected(
                "JVW-ENTITY-SOURCE-SYNTAX",
                "The proposed entity source is invalid: ${syntax.errorDescription}",
            )
        }

        val sourceChange = WorkspaceFileChange(
            relativePath = request.sourceLocator.relativePath,
            mode = WorkspaceFileChangeMode.MODIFY,
            baseRevisionFingerprint = fingerprint,
            edits = edits,
        )
        val persistedAdditions = additions.filterNot(AttributeModel::transientFlag)
        val migrationChanges = if (
            (persistedAdditions.isNotEmpty() || metadataChanges.isNotEmpty()) &&
            !request.entity.databaseView &&
            request.entity.ddlGeneration.effectiveMode != DdlGenerationMode.DISABLED
        ) {
            val migrationProposal = migrationProposal(request.entity, persistedAdditions, metadataChanges)
            migrationProposal.changeSet?.files
                ?: return ExistingEntityChangeProposal(null, migrationProposal.issues)
        } else {
            emptyList()
        }
        val allChanges = (listOf(sourceChange) + migrationChanges)
            .distinctBy(WorkspaceFileChange::relativePath)
        val identity = buildString {
            append(request.sourceLocator.relativePath).append('\u0000').append(fingerprint)
            additions.sortedBy(AttributeModel::name).forEach { attribute ->
                append('\u0000').append(attribute.name)
                    .append('\u0000').append(attribute.type.name)
                    .append('\u0000').append(attribute.resolvedColumnName)
            }
            metadataChanges.sortedBy { it.current.name }.forEach { change ->
                append('\u0000').append(change.current.name)
                    .append('\u0000').append(change.desired.resolvedColumnName)
                    .append('\u0000').append(change.desired.mandatory)
                    .append('\u0000').append(change.desired.unique)
                    .append('\u0000').append(change.desired.length)
                    .append('\u0000').append(change.desired.precision)
                    .append('\u0000').append(change.desired.scale)
            }
            allChanges.forEach { change ->
                append('\u0000').append(change.relativePath).append('\u0000').append(change.createContent.orEmpty())
            }
        }
        return ExistingEntityChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "existing-entity-update:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = buildString {
                    append("Update ").append(request.entity.className).append(": ")
                    if (additions.isNotEmpty()) {
                        append("add ").append(additions.size).append(" attribute")
                        if (additions.size != 1) append('s')
                    }
                    if (additions.isNotEmpty() && metadataChanges.isNotEmpty()) append(", ")
                    if (metadataChanges.isNotEmpty()) {
                        append("change ").append(metadataChanges.size).append(" mapping")
                        if (metadataChanges.size != 1) append('s')
                    }
                },
                files = allChanges,
            ),
            issues = emptyList(),
        )
    }

    private fun proposeKotlinAttributeAdditions(
        request: ExistingEntityAttributeAdditionRequest,
        fileName: String,
        fileType: com.intellij.openapi.fileTypes.FileType,
        content: String,
        fingerprint: String,
    ): ExistingEntityChangeProposal {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText(fileName, fileType, content)
        PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)?.let { syntax ->
            return rejected(
                "JVW-ENTITY-SOURCE-PARSE",
                "The existing Kotlin entity contains a syntax error: ${syntax.errorDescription}",
            )
        }
        val packageName = KOTLIN_PACKAGE.find(content)?.groupValues?.get(1).orEmpty()
        if (packageName != request.entity.packageName) {
            return rejected(
                "JVW-ENTITY-IDENTITY-CHANGED",
                "Package changes are not allowed in additive round-trip mode.",
            )
        }
        val entityClass = PsiTreeUtil.findChildrenOfType(psiFile, PsiNamedElement::class.java)
            .singleOrNull {
                it.javaClass.simpleName == "KtClass" &&
                    it.name == request.entity.className
            }
            ?: return rejected(
                "JVW-ENTITY-CLASS-MISSING",
                "The indexed Kotlin class ${request.entity.className} no longer exists in this source file.",
            )
        val propertiesByName = PsiTreeUtil.findChildrenOfType(entityClass, PsiNamedElement::class.java)
            .asSequence()
            .filter {
                it.javaClass.simpleName == "KtProperty" &&
                    it.nearestKotlinClass() === entityClass
            }
            .mapNotNull { property -> property.name?.let { it to property } }
            .toMap()
        val existingNames = propertiesByName.keys
        val additions = request.entity.attributes.filter { it.name !in existingNames }
        val duplicates = additions.groupingBy(AttributeModel::name)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicates.isNotEmpty()) {
            return rejected(
                "JVW-ENTITY-ATTRIBUTE-DUPLICATE",
                "Duplicate attribute names: ${duplicates.sorted().joinToString()}.",
            )
        }
        additions.firstOrNull { !JAVA_IDENTIFIER.matches(it.name) }?.let {
            return rejected(
                "JVW-ENTITY-ATTRIBUTE-NAME-INVALID",
                "'${it.name}' is not a valid Kotlin property name.",
            )
        }
        additions.firstOrNull { it.type == AttributeType.EMBEDDED }?.let {
            return rejected(
                "JVW-ENTITY-EMBEDDED-REQUIRES-DESIGNER",
                "${it.name} is embedded. Use the embedded-type designer so attribute overrides can be reviewed.",
            )
        }
        val snapshot = SchemaWorkspaceService.getInstance(project).load().entities.firstOrNull {
            it.qualifiedName == request.entity.fullName &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        } ?: return rejected(
            "JVW-ENTITY-SNAPSHOT-MISSING",
            "The exact existing Kotlin entity metadata could not be reconstructed. Refresh the schema workspace.",
        )
        val metadataChanges = mutableListOf<ExistingAttributeMetadataChange>()
        snapshot.attributes.forEach { current ->
            if (current.name == "id") return@forEach
            val desired = request.entity.attributes.firstOrNull { it.name == current.name }
                ?: return rejected(
                    "JVW-ENTITY-REMOVAL-REQUIRES-IMPACT",
                    "${current.name} is missing from the requested Kotlin entity model. " +
                        "Removal or rename requires the explicit native impact workflow; it will not be interpreted as an addition.",
                )
            val currentType = attributeType(
                current.javaType,
                current.association,
                current.associationDetails?.composition == true,
            )
            if (desired.type != currentType) {
                return rejected(
                    "JVW-ENTITY-TYPE-REFACTOR-REQUIRES-IMPACT",
                    "${current.name} changes Kotlin type from ${current.javaType} ($currentType) to ${desired.type}. " +
                        "Use a project-wide refactor with call-site impact analysis.",
                )
            }
            val columnRenamed = desired.resolvedColumnName != current.columnName
            if (columnRenamed) {
                if (current.association) {
                    return rejected(
                        "JVW-ENTITY-RELATIONSHIP-COLUMN-RENAME-REQUIRES-IMPACT",
                        "${current.name} changes relationship column ${current.columnName} to " +
                            "${desired.resolvedColumnName}. Use the relationship choreography workflow.",
                    )
                }
                if (!current.persistent) {
                    return rejected(
                        "JVW-ENTITY-TRANSIENT-COLUMN-RENAME",
                        "${current.name} is transient and has no physical column to rename.",
                    )
                }
                if (!DATABASE_IDENTIFIER.matches(desired.resolvedColumnName)) {
                    return rejected(
                        "JVW-ENTITY-COLUMN-NAME-INVALID",
                        "'${desired.resolvedColumnName}' is not a portable unquoted database identifier.",
                    )
                }
                val collision = snapshot.attributes.firstOrNull {
                    it.name != current.name &&
                        it.persistent &&
                        it.columnName.equals(desired.resolvedColumnName, ignoreCase = true)
                }
                if (collision != null) {
                    return rejected(
                        "JVW-ENTITY-COLUMN-RENAME-COLLISION",
                        "${desired.resolvedColumnName} is already mapped by ${collision.name}.",
                    )
                }
                if (
                    request.entity.databaseView ||
                    request.entity.ddlGeneration.effectiveMode == DdlGenerationMode.DISABLED
                ) {
                    return rejected(
                        "JVW-ENTITY-COLUMN-RENAME-DDL-REQUIRED",
                        "Physical column rename requires a managed Liquibase store and enabled DDL generation.",
                    )
                }
            }
            if (current.association) return@forEach
            if (!current.persistent) {
                if (
                    desired.mandatory != !current.nullable ||
                    desired.unique != current.unique ||
                    normalizedLength(desired) != normalizedLength(current) ||
                    desired.precision != current.precision ||
                    desired.scale != current.scale
                ) {
                    return rejected(
                        "JVW-ENTITY-TRANSIENT-MAPPING-INVALID",
                        "${current.name} is transient and has no database column metadata to evolve.",
                    )
                }
                return@forEach
            }
            if (
                desired.mandatory != !current.nullable ||
                desired.unique != current.unique ||
                normalizedLength(desired) != normalizedLength(current) ||
                desired.precision != current.precision ||
                desired.scale != current.scale ||
                columnRenamed
            ) {
                metadataChanges += ExistingAttributeMetadataChange(current, desired)
            }
        }
        val desiredColumnCollision = request.entity.attributes
            .asSequence()
            .filterNot(AttributeModel::transientFlag)
            .filterNot { it.type in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION) }
            .groupBy { it.resolvedColumnName.uppercase(Locale.ROOT) }
            .entries
            .firstOrNull { it.value.size > 1 }
        if (desiredColumnCollision != null) {
            return rejected(
                "JVW-ENTITY-COLUMN-MAPPING-DUPLICATE",
                "${desiredColumnCollision.value.joinToString { it.name }} map to the same column " +
                    "${desiredColumnCollision.key}.",
            )
        }
        metadataChanges.firstOrNull { it.current.unique && !it.desired.unique }?.let {
            return rejected(
                "JVW-ENTITY-UNIQUE-DROP-REQUIRES-CONSTRAINT",
                "${it.current.name} is unique, but the physical constraint name is not provable from the property. " +
                    "Use the schema constraint designer to remove it explicitly.",
            )
        }
        metadataChanges.firstOrNull {
            val oldLength = normalizedLength(it.current)
            val newLength = normalizedLength(it.desired)
            oldLength != null && newLength != null && newLength < oldLength
        }?.let {
            return rejected(
                "JVW-ENTITY-LENGTH-NARROWING-REQUIRES-DATA-AUDIT",
                "${it.current.name} narrows from ${normalizedLength(it.current)} to " +
                    "${normalizedLength(it.desired)} characters. Run the data-safe narrowing workflow.",
            )
        }
        if (additions.isEmpty() && metadataChanges.isEmpty()) {
            return rejected(
                "JVW-ENTITY-UPDATE-NOOP",
                "No Kotlin source or persistence metadata changes were found.",
            )
        }

        val fragments = if (additions.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                additions.map { KotlinEntityGenerator.attributeFragment(request.entity, it) }
            }.getOrElse {
                return rejected(
                    "JVW-ENTITY-FRAGMENT-GENERATION",
                    it.message ?: "The Kotlin entity generator could not produce safe property fragments.",
                )
            }
        }
        val metadataEdits = kotlinMetadataAnnotationEdits(
            source = content,
            propertiesByName = propertiesByName,
            changes = metadataChanges,
        ) ?: return rejected(
            "JVW-ENTITY-METADATA-EDIT-UNSAFE",
            "A Kotlin persistence annotation could not be updated without touching unmanaged source. " +
                "Column renames require an explicit literal @Column(name = \"...\") mapping.",
        )
        val imports = fragments.flatMapTo(linkedSetOf()) { it.imports }
        imports += metadataEdits.imports
        val edits = mutableListOf<WorkspaceTextEdit>()
        kotlinImportEdit(content, imports)?.let(edits::add)
        edits += metadataEdits.edits
        val classEnd = entityClass.textRange.endOffset
        val rightBrace = content.lastIndexOf('}', (classEnd - 1).coerceAtLeast(0))
        if (rightBrace < entityClass.textRange.startOffset) {
            return rejected("JVW-ENTITY-SOURCE-PARSE", "The Kotlin entity class closing brace is missing.")
        }
        if (fragments.isNotEmpty()) {
            val body = fragments.joinToString("\n\n") { indent(it.source) }
            edits += WorkspaceTextEdit(
                startOffset = rightBrace,
                endOffset = rightBrace,
                expectedText = "",
                replacement = "\n\n$body\n",
            )
        }
        val resultingSource = applyEdits(content, edits)
        val resultingPsi = PsiFileFactory.getInstance(project).createFileFromText(
            fileName,
            fileType,
            resultingSource,
        )
        PsiTreeUtil.findChildOfType(resultingPsi, PsiErrorElement::class.java)?.let { syntax ->
            return rejected(
                "JVW-ENTITY-SOURCE-SYNTAX",
                "The proposed Kotlin entity source is invalid: ${syntax.errorDescription}",
            )
        }
        val sourceChange = WorkspaceFileChange(
            relativePath = request.sourceLocator.relativePath,
            mode = WorkspaceFileChangeMode.MODIFY,
            baseRevisionFingerprint = fingerprint,
            edits = edits,
        )
        val persisted = additions.filterNot(AttributeModel::transientFlag)
        val migrationChanges = if (
            (persisted.isNotEmpty() || metadataChanges.isNotEmpty()) &&
            !request.entity.databaseView &&
            request.entity.ddlGeneration.effectiveMode != DdlGenerationMode.DISABLED
        ) {
            val proposal = migrationProposal(request.entity, persisted, metadataChanges)
            proposal.changeSet?.files ?: return ExistingEntityChangeProposal(null, proposal.issues)
        } else {
            emptyList()
        }
        val allChanges = (listOf(sourceChange) + migrationChanges)
            .distinctBy(WorkspaceFileChange::relativePath)
        val identity = buildString {
            append(request.sourceLocator.relativePath).append('\u0000').append(fingerprint)
            additions.sortedBy(AttributeModel::name).forEach {
                append('\u0000').append(it.name)
                    .append('\u0000').append(it.type.name)
                    .append('\u0000').append(it.resolvedColumnName)
            }
            metadataChanges.sortedBy { it.current.name }.forEach {
                append('\u0000').append(it.current.name)
                    .append('\u0000').append(it.desired.resolvedColumnName)
                    .append('\u0000').append(it.desired.mandatory)
                    .append('\u0000').append(it.desired.unique)
                    .append('\u0000').append(it.desired.length)
                    .append('\u0000').append(it.desired.precision)
                    .append('\u0000').append(it.desired.scale)
            }
        }
        return ExistingEntityChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "existing-kotlin-entity-update:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = buildString {
                    append("Update ").append(request.entity.className).append(": ")
                    if (additions.isNotEmpty()) {
                        append("add ").append(additions.size).append(" Kotlin attribute")
                        if (additions.size != 1) append('s')
                    }
                    if (additions.isNotEmpty() && metadataChanges.isNotEmpty()) append(", ")
                    if (metadataChanges.isNotEmpty()) {
                        append("change ").append(metadataChanges.size).append(" mapping")
                        if (metadataChanges.size != 1) append('s')
                    }
                },
                files = allChanges,
            ),
            issues = emptyList(),
        )
    }

    private fun generatedFragments(
        entity: EntityModel,
        additions: List<AttributeModel>,
    ): GeneratedEntityFragments? {
        val generatedSource = EntityGenerator.generate(
            entity.copy(
                traits = mutableListOf(),
                attributes = additions.toMutableList(),
                indexes = mutableListOf(),
                uniqueConstraints = mutableListOf(),
                lifecycleCallbacks = mutableListOf(),
                entityListeners = mutableListOf(),
                annotations = mutableListOf(),
            ),
        )
        val generatedPsi = parseJava("${entity.className}.java", generatedSource) ?: return null
        if (javaSyntaxError(generatedPsi) != null) return null
        val generatedClass = generatedPsi.classes.singleOrNull() ?: return null
        val fragments = mutableListOf<String>()
        additions.forEach { attribute ->
            val fieldNames = buildList {
                if (attribute.association?.crossDataStore == true) {
                    add(attribute.relationshipIdAttributeName)
                }
                add(attribute.name)
            }
            fieldNames.forEach { fieldName ->
                val field = generatedClass.findFieldByName(fieldName, false) ?: return null
                fragments += field.text
                val suffix = fieldName.replaceFirstChar { it.uppercase() }
                val getters = listOf("get$suffix", "is$suffix")
                    .flatMap { methodName ->
                        generatedClass.findMethodsByName(methodName, false).asList()
                    }
                val setter = generatedClass.findMethodsByName("set$suffix", false).singleOrNull()
                    ?: return null
                val getter = getters.singleOrNull() ?: return null
                fragments += getter.text
                fragments += setter.text
            }
        }
        val body = fragments.joinToString("\n\n") { indent(it) }
        val referencedText = fragments.joinToString("\n")
        val imports = generatedPsi.importList?.importStatements.orEmpty()
            .filter { importIsReferenced(it, referencedText) }
            .map(::importName)
            .filterNot { it == entity.fullName }
            .toSet()
        return GeneratedEntityFragments(body, imports)
    }

    private fun migrationProposal(
        entity: EntityModel,
        additions: List<AttributeModel>,
        metadataChanges: List<ExistingAttributeMetadataChange>,
    ): SchemaMigrationProposal {
        val workspace = SchemaWorkspaceService.getInstance(project).load()
        val requestedStoreId = entity.generationTarget?.storeId.orEmpty()
        val requestedModuleId = entity.generationTarget?.moduleId.orEmpty()
        val store = workspace.stores.firstOrNull { it.id == requestedStoreId }
            ?: workspace.stores.firstOrNull {
                it.moduleId == requestedModuleId && it.name == entity.dataStore
            }
            ?: return SchemaMigrationProposal.failure(
                "JVW-ENTITY-STORE-MISSING",
                "No managed Liquibase data store matches ${entity.dataStore}. Refresh project ownership before adding persisted attributes.",
            )
        val dbType = JmixProjectService.getInstance(project).getConfig()?.databaseType
            ?: DatabaseType.POSTGRES
        val scalarColumns = additions
            .filterNot { it.type in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION) }
            .map { attribute ->
                ColumnDef(
                    name = attribute.resolvedColumnName,
                    type = columnType(attribute, dbType),
                    nullable = !attribute.mandatory,
                    unique = attribute.unique,
                    remarks = attribute.comment,
                )
            }.toMutableList()
        val stableSuffix = CanonicalDiscoveryJson.sha256(
            listOf(
                entity.resolvedTableName,
                additions.joinToString { it.resolvedColumnName },
                metadataChanges.joinToString {
                    "${it.current.columnName}:${it.desired.mandatory}:${it.desired.unique}:" +
                        "${it.desired.length}:${it.desired.precision}:${it.desired.scale}:" +
                        it.desired.resolvedColumnName
                },
            ).joinToString("\u0000"),
        ).take(10)
        val changes = mutableListOf<DbChange>()
        val rollback = mutableListOf<DbChange>()
        val preConditions = mutableListOf<PreCondition>()
        if (scalarColumns.isNotEmpty()) {
            changes += DbChange.AddColumn(entity.resolvedTableName, scalarColumns)
            additions
                .filterNot { it.type in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION) }
                .asReversed()
                .forEach { rollback += DbChange.DropColumn(entity.resolvedTableName, it.resolvedColumnName) }
        }
        additions
            .filter { it.type in setOf(AttributeType.ASSOCIATION, AttributeType.COMPOSITION) }
            .forEach { attribute ->
                val association = requireNotNull(attribute.association)
                val target = workspace.entities.firstOrNull {
                    it.qualifiedName == association.relatedEntity ||
                        it.className == association.relatedEntity
                }
                if (!association.crossDataStore && target != null && target.storeName != store.name) {
                    return SchemaMigrationProposal.failure(
                        "JVW-ENTITY-RELATIONSHIP-STORE-MISMATCH",
                        "${attribute.name} targets ${target.qualifiedName} in store ${target.storeName}. Mark it as a cross-data-store to-one reference.",
                    )
                }
                val targetTable = association.relatedTableName
                    ?.takeIf(String::isNotBlank)
                    ?: target?.tableName
                    ?: inferredTableName(association.relatedEntity)
                val targetIdColumn = association.relatedIdColumnName.ifBlank { "ID" }
                when (association.associationType) {
                    AssociationType.MANY_TO_ONE, AssociationType.ONE_TO_ONE -> {
                        if (association.mappedBy != null && !association.crossDataStore) return@forEach
                        val columnName = association.joinColumnName
                            ?: "${attribute.resolvedColumnName}_ID"
                        val column = ColumnDef(
                            name = columnName,
                            type = idColumnType(association.relatedIdType, dbType),
                            nullable = !attribute.mandatory,
                            unique = association.associationType == AssociationType.ONE_TO_ONE &&
                                !association.crossDataStore,
                        )
                        changes += DbChange.AddColumn(entity.resolvedTableName, mutableListOf(column))
                        if (!association.crossDataStore) {
                            val fkName = "FK_${entity.resolvedTableName}_$columnName"
                            changes += DbChange.AddForeignKeyConstraint(
                                constraintName = fkName,
                                baseTableName = entity.resolvedTableName,
                                baseColumnNames = columnName,
                                referencedTableName = targetTable,
                                referencedColumnNames = targetIdColumn,
                                onDelete = association.onDelete,
                            )
                            rollback += DbChange.DropForeignKeyConstraint(fkName, entity.resolvedTableName)
                        }
                        rollback += DbChange.DropColumn(entity.resolvedTableName, columnName)
                    }
                    AssociationType.ONE_TO_MANY -> Unit
                    AssociationType.MANY_TO_MANY -> {
                        if (!association.mappedBy.isNullOrBlank()) return@forEach
                        val joinTable = association.joinTable
                            ?: return SchemaMigrationProposal.failure(
                                "JVW-ENTITY-MANY-TO-MANY-JOIN-TABLE-MISSING",
                                "Owning relationship ${attribute.name} needs a join-table definition.",
                            )
                        changes += DbChange.CreateTable(
                            tableName = joinTable.name,
                            columns = mutableListOf(
                                ColumnDef(
                                    joinTable.joinColumnName,
                                    idColumnType(entity.id.type, dbType, entity.id.length),
                                    nullable = false,
                                ),
                                ColumnDef(
                                    joinTable.inverseJoinColumnName,
                                    idColumnType(association.relatedIdType, dbType),
                                    nullable = false,
                                ),
                            ),
                        )
                        changes += DbChange.AddPrimaryKey(
                            tableName = joinTable.name,
                            constraintName = "PK_${joinTable.name}",
                            columnNames = listOf(
                                joinTable.joinColumnName,
                                joinTable.inverseJoinColumnName,
                            ),
                        )
                        changes += DbChange.AddForeignKeyConstraint(
                            constraintName = "FK_${joinTable.name}_${joinTable.joinColumnName}",
                            baseTableName = joinTable.name,
                            baseColumnNames = joinTable.joinColumnName,
                            referencedTableName = entity.resolvedTableName,
                            referencedColumnNames = entity.id.columnName,
                            onDelete = association.onDelete,
                        )
                        changes += DbChange.AddForeignKeyConstraint(
                            constraintName = "FK_${joinTable.name}_${joinTable.inverseJoinColumnName}",
                            baseTableName = joinTable.name,
                            baseColumnNames = joinTable.inverseJoinColumnName,
                            referencedTableName = targetTable,
                            referencedColumnNames = targetIdColumn,
                            onDelete = association.onDelete,
                        )
                        rollback += DbChange.DropTable(joinTable.name, cascadeConstraints = true)
                    }
                }
            }
        metadataChanges.forEach { change ->
            val current = change.current
            val desired = change.desired
            val oldType = columnType(current, dbType)
            val newType = columnType(desired, dbType)
            val columnName = desired.resolvedColumnName
            if (change.columnRenamed) {
                preConditions += PreCondition(
                    type = PreConditionType.COLUMN_EXISTS,
                    params = mutableMapOf(
                        "tableName" to entity.resolvedTableName,
                        "columnName" to current.columnName,
                    ),
                )
                preConditions += PreCondition(
                    type = PreConditionType.COLUMN_NOT_EXISTS,
                    params = mutableMapOf(
                        "tableName" to entity.resolvedTableName,
                        "columnName" to columnName,
                    ),
                )
                changes += DbChange.RenameColumn(
                    tableName = entity.resolvedTableName,
                    oldColumnName = current.columnName,
                    newColumnName = columnName,
                    columnDataType = oldType,
                )
                rollback.add(
                    0,
                    DbChange.RenameColumn(
                        tableName = entity.resolvedTableName,
                        oldColumnName = columnName,
                        newColumnName = current.columnName,
                        columnDataType = oldType,
                    ),
                )
            }
            if (desired.mandatory != !current.nullable) {
                if (desired.mandatory) {
                    preConditions += PreCondition(
                        type = PreConditionType.SQL_CHECK,
                        params = mutableMapOf(
                            "expectedResult" to "0",
                            "sql" to "SELECT COUNT(*) FROM ${entity.resolvedTableName} " +
                                "WHERE $columnName IS NULL",
                        ),
                    )
                    changes += DbChange.AddNotNullConstraint(
                        tableName = entity.resolvedTableName,
                        columnName = columnName,
                        columnDataType = newType,
                    )
                    rollback.add(
                        0,
                        DbChange.DropNotNullConstraint(
                            tableName = entity.resolvedTableName,
                            columnName = columnName,
                        ),
                    )
                } else {
                    changes += DbChange.DropNotNullConstraint(
                        tableName = entity.resolvedTableName,
                        columnName = columnName,
                    )
                    rollback.add(
                        0,
                        DbChange.AddNotNullConstraint(
                            tableName = entity.resolvedTableName,
                            columnName = columnName,
                            columnDataType = oldType,
                        ),
                    )
                }
            }
            if (!current.unique && desired.unique) {
                val constraintName = "UQ_${entity.resolvedTableName}_$columnName"
                preConditions += PreCondition(
                    type = PreConditionType.SQL_CHECK,
                    params = mutableMapOf(
                        "expectedResult" to "0",
                        "sql" to "SELECT COUNT(*) FROM (" +
                            "SELECT $columnName FROM ${entity.resolvedTableName} " +
                            "WHERE $columnName IS NOT NULL GROUP BY $columnName " +
                            "HAVING COUNT(*) > 1" +
                            ") JVW_DUPLICATES",
                    ),
                )
                changes += DbChange.AddUniqueConstraint(
                    tableName = entity.resolvedTableName,
                    constraintName = constraintName,
                    columnNames = listOf(columnName),
                )
                rollback.add(
                    0,
                    DbChange.DropUniqueConstraint(
                        tableName = entity.resolvedTableName,
                        constraintName = constraintName,
                    ),
                )
            }
            if (oldType != newType) {
                changes += DbChange.ModifyColumn(
                    tableName = entity.resolvedTableName,
                    columnName = columnName,
                    newDataType = newType,
                )
                rollback.add(
                    0,
                    DbChange.ModifyColumn(
                        tableName = entity.resolvedTableName,
                        columnName = columnName,
                        newDataType = oldType,
                    ),
                )
            }
        }
        if (changes.isEmpty()) {
            return SchemaMigrationProposal(
                changeSet = WorkspaceChangeSet(
                    id = "existing-entity-relationship-code-only:$stableSuffix",
                    label = "Update inverse relationships for ${entity.className}",
                    files = emptyList(),
                ),
                issues = emptyList(),
            )
        }
        val changeSet = ChangeSetModel(
            id = "update-${entity.resolvedTableName.lowercase(Locale.ROOT)}-$stableSuffix",
            comment = buildString {
                append("Synchronize entity mappings for ").append(entity.resolvedTableName)
                if (additions.isNotEmpty()) {
                    append("; add ").append(additions.joinToString { it.resolvedColumnName })
                }
                if (metadataChanges.isNotEmpty()) {
                    append("; update ").append(
                        metadataChanges.joinToString {
                            if (it.columnRenamed) {
                                "${it.current.columnName}->${it.desired.resolvedColumnName}"
                            } else {
                                it.current.columnName
                            }
                        },
                    )
                }
            },
            changes = changes,
            preConditions = preConditions,
            rollback = rollback,
        )
        val migration = MigrationModel(
            changelogId = "update-${entity.resolvedTableName.lowercase(Locale.ROOT)}-$stableSuffix",
            changes = mutableListOf(changeSet),
        )
        return SchemaWorkspaceService.getInstance(project).migrationProposal(
            SchemaMigrationChangeRequest(
                storeId = store.id,
                migration = migration,
                fileName = migration.changelogId,
            ),
        )
    }

    private fun inferredTableName(qualifiedName: String): String =
        qualifiedName.substringAfterLast('.')
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .uppercase(Locale.ROOT)

    private fun idColumnType(
        type: IdType,
        dbType: DatabaseType,
        stringLength: Int? = null,
    ): String = when (type) {
        IdType.UUID -> if (dbType == DatabaseType.MSSQL) "UNIQUEIDENTIFIER" else "UUID"
        IdType.LONG -> "BIGINT"
        IdType.INTEGER -> "INT"
        IdType.STRING -> "VARCHAR(${stringLength ?: 255})"
        IdType.EMBEDDED -> error("Composite relationship identifiers require explicit column mapping.")
    }

    private fun columnType(attribute: AttributeModel, dbType: DatabaseType): String = when (attribute.type) {
        AttributeType.STRING -> "VARCHAR(${attribute.length ?: 255})"
        AttributeType.CHARACTER -> "CHAR(1)"
        AttributeType.INTEGER -> "INT"
        AttributeType.LONG -> "BIGINT"
        AttributeType.DOUBLE -> "DOUBLE"
        AttributeType.BIG_DECIMAL -> "DECIMAL(${attribute.precision ?: 19}, ${attribute.scale ?: 2})"
        AttributeType.BOOLEAN -> "BOOLEAN"
        AttributeType.DATE, AttributeType.LOCAL_DATE -> "DATE"
        AttributeType.LOCAL_DATE_TIME, AttributeType.OFFSET_DATE_TIME -> "TIMESTAMP"
        AttributeType.LOCAL_TIME, AttributeType.OFFSET_TIME -> "TIME"
        AttributeType.SQL_DATE -> "DATE"
        AttributeType.SQL_TIME -> "TIME"
        AttributeType.UUID -> if (dbType == DatabaseType.MSSQL) "UNIQUEIDENTIFIER" else "UUID"
        AttributeType.URI -> "VARCHAR(${attribute.length ?: 255})"
        AttributeType.BYTE_ARRAY -> if (dbType == DatabaseType.POSTGRES) "BYTEA" else "BLOB"
        AttributeType.FILE_REF -> "VARCHAR(${attribute.length ?: 1024})"
        AttributeType.ENUM ->
            if (attribute.enumIdType == EnumIdType.INTEGER) {
                "INT"
            } else {
                "VARCHAR(${attribute.length ?: 255})"
            }
        AttributeType.CUSTOM -> requireNotNull(attribute.sqlType)
        else -> error("Unsupported additive migration type: ${attribute.type}")
    }

    private fun columnType(attribute: SchemaEntityAttributeSnapshot, dbType: DatabaseType): String =
        when (attributeType(attribute.javaType, attribute.association, false)) {
            AttributeType.STRING -> "VARCHAR(${attribute.length ?: 255})"
            AttributeType.CHARACTER -> "CHAR(1)"
            AttributeType.INTEGER -> "INT"
            AttributeType.LONG -> "BIGINT"
            AttributeType.DOUBLE -> "DOUBLE"
            AttributeType.BIG_DECIMAL -> "DECIMAL(${attribute.precision ?: 19}, ${attribute.scale ?: 2})"
            AttributeType.BOOLEAN -> "BOOLEAN"
            AttributeType.DATE, AttributeType.LOCAL_DATE -> "DATE"
            AttributeType.LOCAL_DATE_TIME, AttributeType.OFFSET_DATE_TIME -> "TIMESTAMP"
            AttributeType.LOCAL_TIME, AttributeType.OFFSET_TIME -> "TIME"
            AttributeType.SQL_DATE -> "DATE"
            AttributeType.SQL_TIME -> "TIME"
            AttributeType.UUID -> if (dbType == DatabaseType.MSSQL) "UNIQUEIDENTIFIER" else "UUID"
            AttributeType.URI -> "VARCHAR(${attribute.length ?: 255})"
            AttributeType.BYTE_ARRAY -> if (dbType == DatabaseType.POSTGRES) "BYTEA" else "BLOB"
            AttributeType.FILE_REF -> "VARCHAR(${attribute.length ?: 1024})"
            AttributeType.ENUM -> "VARCHAR(${attribute.length ?: 255})"
            else -> error("Unsupported existing migration type: ${attribute.javaType}")
        }

    private fun attributeType(
        javaType: String,
        association: Boolean,
        composition: Boolean = false,
    ): AttributeType {
        if (composition) return AttributeType.COMPOSITION
        if (association) return AttributeType.ASSOCIATION
        val normalized = javaType.removeSuffix("?").trim()
        if (normalized == "java.sql.Date") return AttributeType.SQL_DATE
        if (normalized == "java.sql.Time") return AttributeType.SQL_TIME
        val simple = normalized
            .removeSuffix("?")
            .substringAfterLast('.')
            .substringBefore('<')
            .trim()
        return when (simple) {
            "String" -> AttributeType.STRING
            "Character", "Char", "char" -> AttributeType.CHARACTER
            "Integer", "Int", "int" -> AttributeType.INTEGER
            "Long", "long" -> AttributeType.LONG
            "Double", "double", "Float", "float" -> AttributeType.DOUBLE
            "BigDecimal" -> AttributeType.BIG_DECIMAL
            "Boolean", "boolean" -> AttributeType.BOOLEAN
            "Date" -> AttributeType.DATE
            "LocalDate" -> AttributeType.LOCAL_DATE
            "LocalDateTime" -> AttributeType.LOCAL_DATE_TIME
            "LocalTime" -> AttributeType.LOCAL_TIME
            "OffsetTime" -> AttributeType.OFFSET_TIME
            "OffsetDateTime" -> AttributeType.OFFSET_DATE_TIME
            "UUID" -> AttributeType.UUID
            "URI" -> AttributeType.URI
            "byte[]", "ByteArray" -> AttributeType.BYTE_ARRAY
            "FileRef" -> AttributeType.FILE_REF
            else -> AttributeType.ENUM
        }
    }

    private fun normalizedLength(attribute: AttributeModel): Int? =
        if (
            attribute.type in setOf(
                AttributeType.STRING,
                AttributeType.ENUM,
                AttributeType.URI,
                AttributeType.FILE_REF,
            )
        ) {
            attribute.length ?: if (attribute.type == AttributeType.FILE_REF) 1024 else 255
        } else {
            null
        }

    private fun normalizedLength(attribute: SchemaEntityAttributeSnapshot): Int? =
        if (attributeType(attribute.javaType, attribute.association) in setOf(
                AttributeType.STRING,
                AttributeType.ENUM,
                AttributeType.URI,
                AttributeType.FILE_REF,
            )
        ) {
            attribute.length ?: if (
                attributeType(attribute.javaType, attribute.association) == AttributeType.FILE_REF
            ) 1024 else 255
        } else {
            null
        }

    private fun metadataAnnotationEdits(
        source: String,
        entityClass: com.intellij.psi.PsiClass,
        changes: List<ExistingAttributeMetadataChange>,
    ): GeneratedMetadataEdits? {
        val edits = mutableListOf<WorkspaceTextEdit>()
        val imports = mutableSetOf<String>()
        changes.forEach { change ->
            val field = entityClass.findFieldByName(change.current.name, false) ?: return null
            val modifierList = field.modifierList ?: return null
            val columnAnnotation = modifierList.annotations.firstOrNull { annotation ->
                val name = annotation.nameReferenceElement?.text?.substringAfterLast('.')
                name == "Column"
            }
            if (
                change.columnRenamed &&
                columnAnnotation?.text?.let {
                    explicitColumnName(it) == change.current.columnName
                } != true
            ) {
                return null
            }
            val preserved = linkedMapOf<String, String>()
            columnAnnotation?.parameterList?.attributes.orEmpty().forEach { pair ->
                val name = pair.name ?: "value"
                if (name !in MANAGED_COLUMN_ARGUMENTS) {
                    pair.value?.text?.let { preserved[name] = it }
                }
            }
            val desired = change.desired
            val managed = linkedMapOf<String, String>()
            managed["name"] = "\"${escapeJavaString(desired.resolvedColumnName)}\""
            if (desired.mandatory) managed["nullable"] = "false"
            if (desired.unique) managed["unique"] = "true"
            if (
                desired.type in setOf(
                    AttributeType.STRING,
                    AttributeType.ENUM,
                    AttributeType.URI,
                    AttributeType.FILE_REF,
                ) &&
                desired.length != null
            ) {
                managed["length"] = desired.length.toString()
            }
            if (desired.precision != null) managed["precision"] = desired.precision.toString()
            if (desired.scale != null) managed["scale"] = desired.scale.toString()
            val arguments = (preserved + managed).entries.joinToString(", ") { (name, value) ->
                if (name == "value") value else "$name = $value"
            }
            val annotationName = columnAnnotation?.text
                ?.substringBefore('(')
                ?.trim()
                ?: "@Column"
            val replacement = "$annotationName($arguments)"
            if (columnAnnotation != null) {
                if (columnAnnotation.text != replacement) {
                    edits += WorkspaceTextEdit(
                        startOffset = columnAnnotation.textRange.startOffset,
                        endOffset = columnAnnotation.textRange.endOffset,
                        expectedText = columnAnnotation.text,
                        replacement = replacement,
                    )
                }
            } else {
                val offset = modifierList.textRange.startOffset
                val lineStart = source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
                val indentation = source.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
                edits += WorkspaceTextEdit(
                    startOffset = offset,
                    endOffset = offset,
                    expectedText = "",
                    replacement = "$replacement\n$indentation",
                )
                imports += "jakarta.persistence.Column"
            }
        }
        return GeneratedMetadataEdits(edits, imports)
    }

    private fun kotlinMetadataAnnotationEdits(
        source: String,
        propertiesByName: Map<String, PsiNamedElement>,
        changes: List<ExistingAttributeMetadataChange>,
    ): GeneratedMetadataEdits? {
        val edits = mutableListOf<WorkspaceTextEdit>()
        val imports = mutableSetOf<String>()
        changes.forEach { change ->
            val property = propertiesByName[change.current.name] ?: return null
            val columnAnnotation = PsiTreeUtil.findChildrenOfType(
                property,
                com.intellij.psi.PsiElement::class.java,
            ).firstOrNull { element ->
                element.javaClass.simpleName == "KtAnnotationEntry" &&
                    element.text.substringBefore('(')
                        .substringAfterLast(':')
                        .substringAfterLast('.')
                        .removePrefix("@") == "Column"
            }
            if (
                change.columnRenamed &&
                columnAnnotation?.text?.let {
                    explicitColumnName(it) == change.current.columnName
                } != true
            ) {
                return null
            }
            val preserved = linkedMapOf<String, String>()
            columnAnnotation?.text?.let { annotationText ->
                val arguments = annotationText
                    .substringAfter('(', "")
                    .substringBeforeLast(')', "")
                splitTopLevelArguments(arguments).forEach { argument ->
                    val equals = topLevelEquals(argument)
                    val name = if (equals < 0) "value" else argument.substring(0, equals).trim()
                    val value = if (equals < 0) argument.trim() else argument.substring(equals + 1).trim()
                    if (name !in MANAGED_COLUMN_ARGUMENTS && value.isNotBlank()) {
                        preserved[name] = value
                    }
                }
            }
            val desired = change.desired
            val managed = linkedMapOf<String, String>()
            managed["name"] = "\"${escapeJavaString(desired.resolvedColumnName)}\""
            if (desired.mandatory) managed["nullable"] = "false"
            if (desired.unique) managed["unique"] = "true"
            if (
                desired.type in setOf(
                    AttributeType.STRING,
                    AttributeType.ENUM,
                    AttributeType.URI,
                    AttributeType.FILE_REF,
                ) &&
                desired.length != null
            ) {
                managed["length"] = desired.length.toString()
            }
            desired.precision?.let { managed["precision"] = it.toString() }
            desired.scale?.let { managed["scale"] = it.toString() }
            val arguments = (preserved + managed).entries.joinToString(", ") { (name, value) ->
                if (name == "value") value else "$name = $value"
            }
            val annotationName = columnAnnotation?.text
                ?.substringBefore('(')
                ?.trim()
                ?: "@Column"
            val replacement = "$annotationName($arguments)"
            if (columnAnnotation != null) {
                if (columnAnnotation.text != replacement) {
                    edits += WorkspaceTextEdit(
                        startOffset = columnAnnotation.textRange.startOffset,
                        endOffset = columnAnnotation.textRange.endOffset,
                        expectedText = columnAnnotation.text,
                        replacement = replacement,
                    )
                }
            } else {
                val offset = property.textRange.startOffset
                val lineStart = source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
                val indentation = source.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
                edits += WorkspaceTextEdit(
                    startOffset = offset,
                    endOffset = offset,
                    expectedText = "",
                    replacement = "$replacement\n$indentation",
                )
                imports += "jakarta.persistence.Column"
            }
        }
        return GeneratedMetadataEdits(edits, imports)
    }

    private fun splitTopLevelArguments(arguments: String): List<String> {
        if (arguments.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var round = 0
        var square = 0
        var curly = 0
        var quoted = false
        var escaped = false
        arguments.forEachIndexed { index, char ->
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
                }
                return@forEachIndexed
            }
            when (char) {
                '"' -> quoted = true
                '(' -> round += 1
                ')' -> round -= 1
                '[' -> square += 1
                ']' -> square -= 1
                '{' -> curly += 1
                '}' -> curly -= 1
                ',' -> if (round == 0 && square == 0 && curly == 0) {
                    result += arguments.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += arguments.substring(start).trim()
        return result.filter(String::isNotBlank)
    }

    private fun topLevelEquals(argument: String): Int {
        var round = 0
        var square = 0
        var curly = 0
        var quoted = false
        var escaped = false
        argument.forEachIndexed { index, char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
                return@forEachIndexed
            }
            when (char) {
                '"' -> quoted = true
                '(' -> round += 1
                ')' -> round -= 1
                '[' -> square += 1
                ']' -> square -= 1
                '{' -> curly += 1
                '}' -> curly -= 1
                '=' -> if (round == 0 && square == 0 && curly == 0) return index
            }
        }
        return -1
    }

    private fun escapeJavaString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun explicitColumnName(annotation: String): String? =
        COLUMN_NAME_LITERAL.find(annotation)?.groupValues?.get(1)

    private fun importEdit(file: PsiJavaFile, requestedImports: Set<String>): WorkspaceTextEdit? {
        val existing = file.importList?.importStatements.orEmpty()
            .map(::importName)
            .toSet()
        val missing = requestedImports
            .filterNot { requested -> existing.any { it == requested || covers(it, requested) } }
            .sorted()
        if (missing.isEmpty()) return null
        val statements = missing.joinToString("") { "import $it;\n" }
        val importList = file.importList
        val existingStatements = importList?.importStatements.orEmpty()
        val offset: Int
        val replacement: String
        if (existingStatements.isNotEmpty()) {
            offset = existingStatements.last().textRange.endOffset
            replacement = "\n$statements".trimEnd()
        } else {
            val packageStatement = file.packageStatement
            offset = packageStatement?.textRange?.endOffset ?: 0
            replacement = if (offset == 0) "$statements\n" else "\n\n$statements".trimEnd()
        }
        return WorkspaceTextEdit(offset, offset, "", replacement)
    }

    private fun kotlinImportEdit(
        source: String,
        requestedImports: Set<String>,
    ): WorkspaceTextEdit? {
        val existingMatches = KOTLIN_IMPORT.findAll(source).toList()
        val existing = existingMatches.map { it.groupValues[1] }.toSet()
        val missing = requestedImports
            .filterNot { requested ->
                existing.any { it == requested || covers(it, requested) }
            }
            .sorted()
        if (missing.isEmpty()) return null
        val statements = missing.joinToString("\n") { "import $it" }
        val lastImport = existingMatches.lastOrNull()
        if (lastImport != null) {
            return WorkspaceTextEdit(
                startOffset = lastImport.range.last + 1,
                endOffset = lastImport.range.last + 1,
                expectedText = "",
                replacement = "\n$statements",
            )
        }
        val packageStatement = KOTLIN_PACKAGE.find(source)
        val offset = packageStatement?.range?.last?.plus(1) ?: 0
        val replacement = if (offset == 0) "$statements\n\n" else "\n\n$statements"
        return WorkspaceTextEdit(offset, offset, "", replacement)
    }

    private fun importIsReferenced(statement: PsiImportStatement, text: String): Boolean {
        if (statement.isOnDemand) {
            return text.contains("@Column") ||
                text.contains("@Enumerated") ||
                text.contains("@Transient")
        }
        val simpleName = statement.qualifiedName?.substringAfterLast('.') ?: return false
        return Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(text)
    }

    private fun importName(statement: PsiImportStatement): String =
        statement.qualifiedName.orEmpty() + if (statement.isOnDemand) ".*" else ""

    private fun covers(existing: String, requested: String): Boolean =
        existing.endsWith(".*") && requested.startsWith(existing.removeSuffix("*"))

    private fun parseJava(fileName: String, content: String): PsiJavaFile? =
        PsiFileFactory.getInstance(project).createFileFromText(
            fileName,
            JavaFileType.INSTANCE,
            content,
        ) as? PsiJavaFile

    private fun javaSyntaxError(file: PsiJavaFile): PsiErrorElement? =
        PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)

    private fun indent(fragment: String): String =
        fragment.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) line else "    $line"
        }

    private fun applyEdits(content: String, edits: List<WorkspaceTextEdit>): String {
        var result = content
        edits.sortedByDescending(WorkspaceTextEdit::startOffset).forEach { edit ->
            result = result.substring(0, edit.startOffset) + edit.replacement + result.substring(edit.endOffset)
        }
        return result
    }

    private fun rejected(code: String, message: String): ExistingEntityChangeProposal =
        ExistingEntityChangeProposal(
            changeSet = null,
            issues = listOf(WorkspaceChangeIssue(code, message)),
        )

    companion object {
        private val JAVA_IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        private val DATABASE_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_$]*""")
        private val COLUMN_NAME_LITERAL =
            Regex("""\bname\s*=\s*"((?:\\.|[^"\\])*)"""")
        private val MANAGED_COLUMN_ARGUMENTS = setOf(
            "name",
            "nullable",
            "unique",
            "length",
            "precision",
            "scale",
        )
        private val KOTLIN_PACKAGE =
            Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$""")
        private val KOTLIN_IMPORT =
            Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_.*]*)(?:\s+as\s+\w+)?\s*$""")

        fun getInstance(project: Project): ExistingEntityChangeService =
            project.getService(ExistingEntityChangeService::class.java)
    }
}

private fun com.intellij.psi.PsiElement.nearestKotlinClass(): com.intellij.psi.PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }

data class ExistingEntityAttributeAdditionRequest(
    val sourceLocator: SourceLocator,
    val entity: EntityModel,
)

data class ExistingEntityAttributeAdditionApplyRequest(
    val change: ExistingEntityAttributeAdditionRequest,
    val expectedPlanDigest: String,
)

private data class GeneratedEntityFragments(
    val body: String,
    val imports: Set<String>,
)

private data class ExistingAttributeMetadataChange(
    val current: SchemaEntityAttributeSnapshot,
    val desired: AttributeModel,
) {
    val columnRenamed: Boolean
        get() = current.columnName != desired.resolvedColumnName
}

private data class GeneratedMetadataEdits(
    val edits: List<WorkspaceTextEdit>,
    val imports: Set<String>,
)

private data class ExistingEntityChangeProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
) {
    fun rejectedPreview(): WorkspaceChangePreviewResponse = WorkspaceChangePreviewResponse(
        accepted = false,
        changeSetId = "existing-entity-additions:rejected",
        label = "Existing entity update rejected",
        planDigest = null,
        files = emptyList(),
        issues = issues,
    )
}

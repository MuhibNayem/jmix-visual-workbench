package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.model.AttributeType
import java.util.Locale

/**
 * Resolves an entity property rename against the exact live IntelliJ PSI.
 *
 * The actual mutation is delegated to IntelliJ's RenameProcessor so every
 * plugin-contributed reference (FlowUI, fetch plans, security, JPQL, etc.)
 * participates in its standard usage preview and conflict analysis.
 */
@Service(Service.Level.PROJECT)
class EntityAttributeRefactorService(
    private val project: Project,
) {
    fun prepareRename(request: EntityAttributeRenameRequest): PreparedEntityAttributeRename {
        if (!IDENTIFIER.matches(request.newName)) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-NAME-INVALID",
                "'${request.newName}' is not a valid Java/Kotlin property name.",
            )
        }
        if (request.attributeName == request.newName) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-NOOP",
                "The new property name is unchanged.",
            )
        }
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.sourceLocator.relativePath)
            ?: return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-SOURCE-MISSING",
                "The indexed entity source no longer exists.",
            )
        val file = resolved.file
        if (
            file.isDirectory ||
            file.extension !in setOf("java", "kt") ||
            !VfsUtilCore.isAncestor(resolved.root, file, false)
        ) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-SOURCE-INVALID",
                "Native entity rename requires a Java or Kotlin source inside project content.",
            )
        }
        val current = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-SOURCE-UNREADABLE",
                "The current entity document cannot be read.",
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(current)
        if (request.sourceLocator.revisionFingerprint != fingerprint) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-SOURCE-STALE",
                "The entity changed after indexing. Refresh Entity Designer before refactoring.",
            )
        }
        val snapshot = SchemaWorkspaceService.getInstance(project).load().entities.firstOrNull {
            it.className == request.entityClassName &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        } ?: return PreparedEntityAttributeRename.failure(
            "JVW-ENTITY-RENAME-SNAPSHOT-MISSING",
            "The exact entity metadata snapshot is unavailable.",
        )
        val attribute = snapshot.attributes.firstOrNull { it.name == request.attributeName }
            ?: return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-ATTRIBUTE-MISSING",
                "Property ${request.attributeName} is no longer declared by ${request.entityClassName}.",
            )
        if (snapshot.attributes.any { it.name == request.newName }) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-COLLISION",
                "${request.entityClassName} already declares ${request.newName}.",
            )
        }
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-PSI-MISSING",
                "IntelliJ could not create live PSI for the entity source.",
            )
        val declaration = when (psiFile) {
            is PsiJavaFile -> {
                val entityClass = psiFile.classes.singleOrNull { it.name == request.entityClassName }
                    ?: return PreparedEntityAttributeRename.failure(
                        "JVW-ENTITY-RENAME-CLASS-MISSING",
                        "Class ${request.entityClassName} is no longer present in the Java source.",
                    )
                entityClass.fields.singleOrNull {
                    it.name == request.attributeName &&
                        !it.hasModifierProperty(PsiModifier.STATIC)
                }
            }

            else -> {
                val entityClass = PsiTreeUtil.findChildrenOfType(
                    psiFile,
                    PsiNamedElement::class.java,
                ).singleOrNull {
                    it.javaClass.simpleName == "KtClass" &&
                        it.name == request.entityClassName
                } ?: return PreparedEntityAttributeRename.failure(
                    "JVW-ENTITY-RENAME-CLASS-MISSING",
                    "Class ${request.entityClassName} is no longer present in the Kotlin source.",
                )
                PsiTreeUtil.findChildrenOfType(entityClass, PsiNamedElement::class.java)
                    .singleOrNull {
                        it.javaClass.simpleName == "KtProperty" &&
                            it.name == request.attributeName &&
                            it.nearestKotlinEntityClass() === entityClass
                    }
            }
        } ?: return PreparedEntityAttributeRename.failure(
            "JVW-ENTITY-RENAME-DECLARATION-MISSING",
            "The live property declaration cannot be resolved unambiguously.",
        )
        if (!declaration.isWritable) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-READONLY",
                "The property declaration is not writable.",
            )
        }
        if (attribute.association) {
            stableRelationshipMapping(attribute, declaration.text)?.let { failure ->
                return failure
            }
        } else if (
            attribute.persistent &&
            !EXPLICIT_COLUMN_NAME.containsMatchIn(declaration.text)
        ) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-INFERRED-COLUMN",
                "Renaming this property would also change its inferred database column. Add an explicit @Column(name = \"${attribute.columnName}\") or use the schema-aware column rename workflow.",
            )
        }
        return PreparedEntityAttributeRename(
            accepted = true,
            code = null,
            message = "IntelliJ usage preview is ready.",
            element = declaration,
            newName = request.newName,
        )
    }

    /**
     * Resolves a property for IntelliJ Safe Delete without mutating source or
     * database state. The database mapping is intentionally retained: after
     * Safe Delete is applied, the schema workspace can review the now-unmapped
     * column and generate a separate, data-audited retirement migration.
     */
    fun prepareSafeDelete(
        request: EntityAttributeSafeDeleteRequest,
    ): PreparedEntityAttributeSafeDelete {
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.sourceLocator.relativePath)
            ?: return PreparedEntityAttributeSafeDelete.failure(
                "JVW-ENTITY-SAFE-DELETE-SOURCE-MISSING",
                "The indexed entity source no longer exists.",
            )
        val file = resolved.file
        if (
            file.isDirectory ||
            file.extension !in setOf("java", "kt") ||
            !VfsUtilCore.isAncestor(resolved.root, file, false)
        ) {
            return PreparedEntityAttributeSafeDelete.failure(
                "JVW-ENTITY-SAFE-DELETE-SOURCE-INVALID",
                "Native safe delete requires a Java or Kotlin source inside project content.",
            )
        }
        val current = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return PreparedEntityAttributeSafeDelete.failure(
                "JVW-ENTITY-SAFE-DELETE-SOURCE-UNREADABLE",
                "The current entity document cannot be read.",
            )
        }
        if (request.sourceLocator.revisionFingerprint != CanonicalDiscoveryJson.sha256(current)) {
            return PreparedEntityAttributeSafeDelete.failure(
                "JVW-ENTITY-SAFE-DELETE-SOURCE-STALE",
                "The entity changed after indexing. Refresh Entity Designer before deleting.",
            )
        }
        val snapshot = SchemaWorkspaceService.getInstance(project).load().entities.firstOrNull {
            it.className == request.entityClassName &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        } ?: return PreparedEntityAttributeSafeDelete.failure(
            "JVW-ENTITY-SAFE-DELETE-SNAPSHOT-MISSING",
            "The exact entity metadata snapshot is unavailable.",
        )
        val attribute = snapshot.attributes.firstOrNull { it.name == request.attributeName }
            ?: return PreparedEntityAttributeSafeDelete.failure(
                "JVW-ENTITY-SAFE-DELETE-ATTRIBUTE-MISSING",
                "Property ${request.attributeName} is no longer declared by ${request.entityClassName}.",
            )
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return PreparedEntityAttributeSafeDelete.failure(
                "JVW-ENTITY-SAFE-DELETE-PSI-MISSING",
                "IntelliJ could not create live PSI for the entity source.",
            )
        val declaration = when (psiFile) {
            is PsiJavaFile -> psiFile.classes
                .singleOrNull { it.name == request.entityClassName }
                ?.fields
                ?.singleOrNull {
                    it.name == request.attributeName &&
                        !it.hasModifierProperty(PsiModifier.STATIC)
                }
            else -> {
                val entityClass = PsiTreeUtil.findChildrenOfType(
                    psiFile,
                    PsiNamedElement::class.java,
                ).singleOrNull {
                    it.javaClass.simpleName == "KtClass" &&
                        it.name == request.entityClassName
                }
                entityClass?.let { owner ->
                    PsiTreeUtil.findChildrenOfType(owner, PsiNamedElement::class.java)
                        .singleOrNull {
                            it.javaClass.simpleName == "KtProperty" &&
                                it.name == request.attributeName &&
                                it.nearestKotlinEntityClass() === owner
                        }
                }
            }
        } ?: return PreparedEntityAttributeSafeDelete.failure(
            "JVW-ENTITY-SAFE-DELETE-DECLARATION-MISSING",
            "The live property declaration cannot be resolved unambiguously.",
        )
        if (!declaration.isWritable) {
            return PreparedEntityAttributeSafeDelete.failure(
                "JVW-ENTITY-SAFE-DELETE-READONLY",
                "The property declaration is not writable.",
            )
        }
        if (attribute.association) {
            stableRelationshipMapping(attribute, declaration.text)?.let {
                return PreparedEntityAttributeSafeDelete.failure(
                    it.code
                        ?.replace("JVW-ENTITY-RENAME-", "JVW-ENTITY-SAFE-DELETE-")
                        ?: "JVW-ENTITY-SAFE-DELETE-RELATIONSHIP-MAPPING-UNSTABLE",
                    it.message,
                )
            }
        } else if (
            attribute.persistent &&
            !EXPLICIT_COLUMN_NAME.containsMatchIn(declaration.text)
        ) {
            return PreparedEntityAttributeSafeDelete.failure(
                "JVW-ENTITY-SAFE-DELETE-INFERRED-COLUMN",
                "The persistent property uses an inferred column. Declare @Column(name = " +
                    "\"${attribute.columnName}\") before Safe Delete so the retained database mapping is explicit.",
            )
        }
        return PreparedEntityAttributeSafeDelete(
            accepted = true,
            code = null,
            message = "IntelliJ Safe Delete usage preview is ready. The physical schema remains unchanged.",
            element = declaration,
            retainedColumnName = attribute.columnName.takeIf { attribute.persistent },
        )
    }

    /**
     * Resolves a scalar property and its requested type against live PSI, then
     * describes the physical-schema blast radius before IntelliJ Type
     * Migration is allowed to start.
     *
     * This method deliberately does not generate modifyDataType. Liquibase
     * cannot infer a data-preserving rollback for that operation, so a
     * persistent SQL type change remains a separately reviewed schema step.
     */
    fun prepareTypeMigration(
        request: EntityAttributeTypeMigrationRequest,
    ): PreparedEntityAttributeTypeMigration {
        if (request.targetType !in MIGRATABLE_TYPES) {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-TARGET-UNSUPPORTED",
                "${request.targetType} requires the relationship, embedded, enum, or custom-type designer.",
            )
        }
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.sourceLocator.relativePath)
            ?: return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-SOURCE-MISSING",
                "The indexed entity source no longer exists.",
            )
        val file = resolved.file
        if (
            file.isDirectory ||
            file.extension !in setOf("java", "kt") ||
            !VfsUtilCore.isAncestor(resolved.root, file, false)
        ) {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-SOURCE-INVALID",
                "Native type migration requires a Java or Kotlin source inside project content.",
            )
        }
        val current = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-SOURCE-UNREADABLE",
                "The current entity document cannot be read.",
            )
        }
        if (request.sourceLocator.revisionFingerprint != CanonicalDiscoveryJson.sha256(current)) {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-SOURCE-STALE",
                "The entity changed after indexing. Refresh Entity Designer before changing its type.",
            )
        }
        val workspace = SchemaWorkspaceService.getInstance(project).load()
        val entity = workspace.entities.firstOrNull {
            it.className == request.entityClassName &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        } ?: return PreparedEntityAttributeTypeMigration.failure(
            "JVW-ENTITY-TYPE-MIGRATION-SNAPSHOT-MISSING",
            "The exact entity metadata snapshot is unavailable.",
        )
        val attribute = entity.attributes.firstOrNull { it.name == request.attributeName }
            ?: return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-ATTRIBUTE-MISSING",
                "Property ${request.attributeName} is no longer declared by ${request.entityClassName}.",
            )
        if (attribute.name == "id" || attribute.columnName.equals(entity.idColumnName, ignoreCase = true)) {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-ID-BLOCKED",
                "Identifier type evolution changes identity, foreign keys, repositories, URLs, and serialized contracts. Use the dedicated identifier migration workflow.",
            )
        }
        if (attribute.association) {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-RELATIONSHIP-BLOCKED",
                "Relationship type evolution requires cardinality, ownership, foreign-key, and inverse-side analysis.",
            )
        }
        val currentType = attributeTypeOf(attribute.javaType, current)
            ?: return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-CURRENT-UNSUPPORTED",
                "The current type ${attribute.javaType} is custom or ambiguous and cannot use the scalar migration workflow.",
            )
        if (currentType == request.targetType) {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-NOOP",
                "${request.attributeName} already uses ${request.targetType}.",
            )
        }
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-PSI-MISSING",
                "IntelliJ could not create live PSI for the entity source.",
            )
        val declaration = when (psiFile) {
            is PsiJavaFile -> psiFile.classes
                .singleOrNull { it.name == request.entityClassName }
                ?.fields
                ?.singleOrNull {
                    it.name == request.attributeName &&
                        !it.hasModifierProperty(PsiModifier.STATIC)
                }
            else -> {
                val entityClass = PsiTreeUtil.findChildrenOfType(
                    psiFile,
                    PsiNamedElement::class.java,
                ).singleOrNull {
                    it.javaClass.simpleName == "KtClass" &&
                        it.name == request.entityClassName
                }
                entityClass?.let { owner ->
                    PsiTreeUtil.findChildrenOfType(owner, PsiNamedElement::class.java)
                        .singleOrNull {
                            it.javaClass.simpleName == "KtProperty" &&
                                it.name == request.attributeName &&
                                it.nearestKotlinEntityClass() === owner
                        }
                }
            }
        } ?: return PreparedEntityAttributeTypeMigration.failure(
            "JVW-ENTITY-TYPE-MIGRATION-DECLARATION-MISSING",
            "The live property declaration cannot be resolved unambiguously.",
        )
        if (!declaration.isWritable) {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-READONLY",
                "The property declaration is not writable.",
            )
        }
        if (
            attribute.persistent &&
            !EXPLICIT_COLUMN_NAME.containsMatchIn(declaration.text)
        ) {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-INFERRED-COLUMN",
                "Declare @Column(name = \"${attribute.columnName}\") explicitly before type migration so the physical identity cannot drift.",
            )
        }

        val nativeRoot = if (declaration.javaClass.simpleName == "KtProperty") {
            kotlinLightBackingField(declaration)
        } else {
            declaration
        } ?: return PreparedEntityAttributeTypeMigration.failure(
            "JVW-ENTITY-TYPE-MIGRATION-LIGHT-DECLARATION-MISSING",
            "IntelliJ could not expose the Kotlin property to its cross-language type migration engine.",
        )
        val targetJavaType = javaTypeName(request.targetType)
        val targetPsiType = runCatching {
            PsiElementFactory.getInstance(project).createTypeFromText(targetJavaType, nativeRoot)
        }.getOrElse {
            return PreparedEntityAttributeTypeMigration.failure(
                "JVW-ENTITY-TYPE-MIGRATION-TARGET-INVALID",
                "IntelliJ cannot resolve target type $targetJavaType in this module.",
            )
        }
        val schemaImpact = schemaImpact(
            workspace = workspace,
            entity = entity,
            attribute = attribute,
            currentType = currentType,
            targetType = request.targetType,
        )
        return PreparedEntityAttributeTypeMigration(
            accepted = true,
            code = null,
            message = buildString {
                append("IntelliJ project-wide Type Migration preview is ready for ")
                    .append(request.attributeName)
                    .append(": ")
                    .append(attribute.javaType)
                    .append(" → ")
                    .append(targetJavaType)
                    .append(". ")
                    .append(schemaImpact.summary)
            },
            element = nativeRoot,
            targetPsiType = targetPsiType,
            currentType = currentType,
            targetType = request.targetType,
            sourceLanguage = file.extension ?: "java",
            schemaImpact = schemaImpact,
        )
    }

    companion object {
        private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        private val EXPLICIT_COLUMN_NAME = Regex(
            """(?s)@\s*(?:field:)?(?:[\w.]+\.)?Column\s*\([^)]*\bname\s*=""",
        )
        private val JPA_NAME_LITERAL = Regex(
            """(?s)@\s*(?:field:)?(?:[\w.]+\.)?(JoinTable|JoinColumn)\s*\([^)]*\bname\s*=\s*"([^"]+)"""",
        )
        private val MIGRATABLE_TYPES = setOf(
            AttributeType.STRING,
            AttributeType.CHARACTER,
            AttributeType.INTEGER,
            AttributeType.LONG,
            AttributeType.DOUBLE,
            AttributeType.BIG_DECIMAL,
            AttributeType.BOOLEAN,
            AttributeType.DATE,
            AttributeType.LOCAL_DATE,
            AttributeType.LOCAL_DATE_TIME,
            AttributeType.LOCAL_TIME,
            AttributeType.OFFSET_TIME,
            AttributeType.OFFSET_DATE_TIME,
            AttributeType.SQL_DATE,
            AttributeType.SQL_TIME,
            AttributeType.UUID,
            AttributeType.URI,
            AttributeType.BYTE_ARRAY,
            AttributeType.FILE_REF,
        )

        fun getInstance(project: Project): EntityAttributeRefactorService =
            project.getService(EntityAttributeRefactorService::class.java)
    }

    private fun stableRelationshipMapping(
        attribute: SchemaEntityAttributeSnapshot,
        declaration: String,
    ): PreparedEntityAttributeRename? {
        val association = attribute.associationDetails
            ?: return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-RELATIONSHIP-METADATA-MISSING",
                "The relationship mapping could not be reconstructed exactly.",
            )
        if (association.crossDataStore || !association.mappedBy.isNullOrBlank()) {
            return null
        }
        association.joinColumnName?.let { joinColumn ->
            if (explicitJpaName(declaration, "JoinColumn") == joinColumn) return null
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-INFERRED-JOIN-COLUMN",
                "Renaming this relationship could change its physical join column. " +
                    "Declare @JoinColumn(name = \"$joinColumn\") explicitly first.",
            )
        }
        association.joinTable?.let { joinTable ->
            val explicitMappings = JPA_NAME_LITERAL.findAll(declaration).toList()
            val explicitNames = explicitMappings.map { it.groupValues[2] }.toSet()
            if (
                explicitMappings.any { it.groupValues[1] == "JoinTable" } &&
                joinTable.name in explicitNames &&
                joinTable.joinColumnName in explicitNames &&
                joinTable.inverseJoinColumnName in explicitNames
            ) {
                return null
            }
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-INFERRED-JOIN-TABLE",
                "Renaming this relationship could change its join table. " +
                    "Declare the table and both join-column names explicitly first.",
            )
        }
        return PreparedEntityAttributeRename.failure(
            "JVW-ENTITY-RENAME-INFERRED-RELATIONSHIP-MAPPING",
            "The relationship relies on a property-derived physical mapping. " +
                "Make its join mapping explicit before native rename.",
        )
    }

    private fun explicitJpaName(source: String, annotation: String): String? =
        Regex(
            """(?s)@\s*(?:field:)?(?:[\w.]+\.)?$annotation\s*\([^)]*\bname\s*=\s*"([^"]+)"""",
        ).find(source)?.groupValues?.get(1)

    private fun schemaImpact(
        workspace: SchemaWorkspaceResponse,
        entity: SchemaEntitySnapshot,
        attribute: SchemaEntityAttributeSnapshot,
        currentType: AttributeType,
        targetType: AttributeType,
    ): EntityAttributeTypeSchemaImpact {
        if (!attribute.persistent) {
            return EntityAttributeTypeSchemaImpact(
                strategy = EntityAttributeTypeSchemaStrategy.SOURCE_ONLY,
                storeId = null,
                tableName = null,
                columnName = null,
                currentSqlType = null,
                targetSqlType = null,
                dependencies = emptyList(),
                summary = "The property is transient, so no database migration is required.",
            )
        }
        if (entity.databaseView || entity.ddlMode == SchemaDdlMode.DISABLED) {
            return EntityAttributeTypeSchemaImpact(
                strategy = EntityAttributeTypeSchemaStrategy.EXTERNAL_SCHEMA_REQUIRED,
                storeId = "${entity.moduleId}:${entity.storeName}",
                tableName = entity.tableName,
                columnName = attribute.columnName,
                currentSqlType = sqlType(currentType, attribute),
                targetSqlType = sqlType(targetType, attribute),
                dependencies = emptyList(),
                summary = "DDL is externally managed; coordinate the physical column conversion before deployment.",
            )
        }
        val storeId = "${entity.moduleId}:${entity.storeName}"
        val physicalStore = workspace.physicalSchemas.firstOrNull { it.storeId == storeId }
        val table = physicalStore?.tables?.firstOrNull {
            it.name.equals(entity.tableName.substringAfterLast('.'), ignoreCase = true)
        }
        val column = table?.columns?.firstOrNull {
            it.name.equals(attribute.columnName, ignoreCase = true)
        }
        val dependencies = buildList {
            if (column?.primaryKey == true) add("primary key")
            if (column?.unique == true) add("unique constraint")
            table?.indexes.orEmpty()
                .filter { index -> index.columns.any { it.equals(attribute.columnName, ignoreCase = true) } }
                .forEach { add("index ${it.name}") }
            table?.foreignKeys.orEmpty()
                .filter { it.baseColumnNames.split(',').any { name -> name.trim().equals(attribute.columnName, true) } }
                .forEach { add("foreign key ${it.constraintName}") }
            physicalStore?.tables.orEmpty().forEach { candidate ->
                candidate.foreignKeys.filter {
                    it.referencedTableName.equals(table?.name, true) &&
                        it.referencedColumnNames.split(',').any { name ->
                            name.trim().equals(attribute.columnName, true)
                        }
                }.forEach { add("incoming foreign key ${it.constraintName} from ${candidate.name}") }
            }
        }.distinct().sorted()
        val currentSql = column?.type?.takeIf(String::isNotBlank) ?: sqlType(currentType, attribute)
        val targetSql = sqlType(targetType, attribute)
        val samePhysicalType = normalizeSqlType(currentSql) == normalizeSqlType(targetSql)
        val strategy = when {
            physicalStore == null || !physicalStore.complete || table == null || column == null ->
                EntityAttributeTypeSchemaStrategy.SCHEMA_EVIDENCE_INCOMPLETE
            samePhysicalType -> EntityAttributeTypeSchemaStrategy.SOURCE_ONLY
            else -> EntityAttributeTypeSchemaStrategy.EXPAND_CONTRACT_REQUIRED
        }
        val summary = when (strategy) {
            EntityAttributeTypeSchemaStrategy.SOURCE_ONLY ->
                "The mapped column remains $currentSql; no physical type rewrite is required."
            EntityAttributeTypeSchemaStrategy.EXPAND_CONTRACT_REQUIRED ->
                buildString {
                    append("Column ${entity.tableName}.${attribute.columnName} requires a reviewed ")
                        .append("$currentSql → $targetSql data conversion")
                    if (dependencies.isNotEmpty()) {
                        append(" and dependency handling for ${dependencies.joinToString()}")
                    }
                    append(". This is not automatically reversible.")
                }
            EntityAttributeTypeSchemaStrategy.EXTERNAL_SCHEMA_REQUIRED ->
                "DDL is externally managed."
            EntityAttributeTypeSchemaStrategy.SCHEMA_EVIDENCE_INCOMPLETE ->
                "The managed Liquibase history is incomplete, so physical conversion must be verified against the live database."
        }
        return EntityAttributeTypeSchemaImpact(
            strategy = strategy,
            storeId = storeId,
            tableName = entity.tableName,
            columnName = attribute.columnName,
            currentSqlType = currentSql,
            targetSqlType = targetSql,
            dependencies = dependencies,
            summary = summary,
        )
    }

    private fun attributeTypeOf(javaType: String, source: String): AttributeType? {
        val qualified = javaType.trim().removeSuffix("?")
        if (qualified == "java.sql.Date") return AttributeType.SQL_DATE
        if (qualified == "java.sql.Time") return AttributeType.SQL_TIME
        if (qualified == "java.util.Date") return AttributeType.DATE
        val normalized = qualified.substringAfterLast('.')
        return when (normalized) {
            "String" -> AttributeType.STRING
            "Character", "Char" -> AttributeType.CHARACTER
            "Integer", "Int", "int" -> AttributeType.INTEGER
            "Long", "long" -> AttributeType.LONG
            "Double", "double" -> AttributeType.DOUBLE
            "BigDecimal" -> AttributeType.BIG_DECIMAL
            "Boolean", "boolean" -> AttributeType.BOOLEAN
            "Date" -> when {
                Regex("""(?m)^\s*import\s+java\.sql\.Date\s*;?\s*$""").containsMatchIn(source) ->
                    AttributeType.SQL_DATE
                Regex("""(?m)^\s*import\s+java\.util\.Date\s*;?\s*$""").containsMatchIn(source) ->
                    AttributeType.DATE
                else -> null
            }
            "LocalDate" -> AttributeType.LOCAL_DATE
            "LocalDateTime" -> AttributeType.LOCAL_DATE_TIME
            "LocalTime" -> AttributeType.LOCAL_TIME
            "OffsetTime" -> AttributeType.OFFSET_TIME
            "OffsetDateTime" -> AttributeType.OFFSET_DATE_TIME
            "Time" -> if (
                Regex("""(?m)^\s*import\s+java\.sql\.Time\s*;?\s*$""").containsMatchIn(source)
            ) {
                AttributeType.SQL_TIME
            } else {
                null
            }
            "UUID" -> AttributeType.UUID
            "URI" -> AttributeType.URI
            "byte[]", "ByteArray" -> AttributeType.BYTE_ARRAY
            "FileRef" -> AttributeType.FILE_REF
            else -> null
        }
    }

    private fun javaTypeName(type: AttributeType): String = when (type) {
        AttributeType.STRING -> "java.lang.String"
        AttributeType.CHARACTER -> "java.lang.Character"
        AttributeType.INTEGER -> "java.lang.Integer"
        AttributeType.LONG -> "java.lang.Long"
        AttributeType.DOUBLE -> "java.lang.Double"
        AttributeType.BIG_DECIMAL -> "java.math.BigDecimal"
        AttributeType.BOOLEAN -> "java.lang.Boolean"
        AttributeType.DATE -> "java.util.Date"
        AttributeType.LOCAL_DATE -> "java.time.LocalDate"
        AttributeType.LOCAL_DATE_TIME -> "java.time.LocalDateTime"
        AttributeType.LOCAL_TIME -> "java.time.LocalTime"
        AttributeType.OFFSET_TIME -> "java.time.OffsetTime"
        AttributeType.OFFSET_DATE_TIME -> "java.time.OffsetDateTime"
        AttributeType.SQL_DATE -> "java.sql.Date"
        AttributeType.SQL_TIME -> "java.sql.Time"
        AttributeType.UUID -> "java.util.UUID"
        AttributeType.URI -> "java.net.URI"
        AttributeType.BYTE_ARRAY -> "byte[]"
        AttributeType.FILE_REF -> "io.jmix.core.FileRef"
        else -> error("Unsupported scalar migration target: $type")
    }

    private fun sqlType(
        type: AttributeType,
        attribute: SchemaEntityAttributeSnapshot,
    ): String = when (type) {
        AttributeType.STRING, AttributeType.URI -> "VARCHAR(${attribute.length ?: 255})"
        AttributeType.CHARACTER -> "CHAR(1)"
        AttributeType.INTEGER -> "INT"
        AttributeType.LONG -> "BIGINT"
        AttributeType.DOUBLE -> "DOUBLE"
        AttributeType.BIG_DECIMAL -> "DECIMAL(${attribute.precision ?: 19}, ${attribute.scale ?: 2})"
        AttributeType.BOOLEAN -> "BOOLEAN"
        AttributeType.DATE, AttributeType.LOCAL_DATE, AttributeType.SQL_DATE -> "DATE"
        AttributeType.LOCAL_DATE_TIME, AttributeType.OFFSET_DATE_TIME -> "TIMESTAMP"
        AttributeType.LOCAL_TIME, AttributeType.OFFSET_TIME, AttributeType.SQL_TIME -> "TIME"
        AttributeType.UUID -> "UUID"
        AttributeType.BYTE_ARRAY -> "BLOB"
        AttributeType.FILE_REF -> "VARCHAR(${attribute.length ?: 1024})"
        else -> "UNKNOWN"
    }

    private fun normalizeSqlType(value: String): String {
        val normalized = value.uppercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
            .replace("INTEGER", "INT")
            .replace("CHARACTERVARYING", "VARCHAR")
            .replace("DECIMAL", "NUMERIC")
        return when (normalized) {
            "INT8" -> "BIGINT"
            "INT4" -> "INT"
            "FLOAT8" -> "DOUBLE"
            "BOOL" -> "BOOLEAN"
            else -> normalized
        }
    }

    /**
     * Kotlin support is an optional IntelliJ plugin dependency. Resolve its
     * light-class adapter only for a live Kotlin declaration so Java-only IDE
     * installations can load this project service without Kotlin classes.
     */
    private fun kotlinLightBackingField(declaration: PsiNamedElement): com.intellij.psi.PsiElement? =
        runCatching {
            val lightClassUtil = Class.forName(
                "org.jetbrains.kotlin.asJava.LightClassUtil",
                true,
                declaration.javaClass.classLoader,
            )
            val instance = lightClassUtil.getField("INSTANCE").get(null)
            val method = lightClassUtil.methods.single {
                it.name == "getLightClassBackingField" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].isInstance(declaration)
            }
            method.invoke(instance, declaration) as? com.intellij.psi.PsiElement
        }.getOrNull()
}

data class EntityAttributeRenameRequest(
    val sourceLocator: SourceLocator,
    val entityClassName: String,
    val attributeName: String,
    val newName: String,
)

data class PreparedEntityAttributeRename(
    val accepted: Boolean,
    val code: String?,
    val message: String,
    val element: PsiNamedElement?,
    val newName: String?,
) {
    companion object {
        fun failure(code: String, message: String): PreparedEntityAttributeRename =
            PreparedEntityAttributeRename(false, code, message, null, null)
    }
}

data class EntityAttributeRenameLaunchResponse(
    val success: Boolean,
    val code: String? = null,
    val message: String,
)

data class EntityAttributeSafeDeleteRequest(
    val sourceLocator: SourceLocator,
    val entityClassName: String,
    val attributeName: String,
)

data class PreparedEntityAttributeSafeDelete(
    val accepted: Boolean,
    val code: String?,
    val message: String,
    val element: PsiNamedElement?,
    val retainedColumnName: String?,
) {
    companion object {
        fun failure(code: String, message: String): PreparedEntityAttributeSafeDelete =
            PreparedEntityAttributeSafeDelete(false, code, message, null, null)
    }
}

data class EntityAttributeSafeDeleteLaunchResponse(
    val success: Boolean,
    val code: String? = null,
    val message: String,
    val retainedColumnName: String? = null,
)

data class EntityAttributeTypeMigrationRequest(
    val sourceLocator: SourceLocator,
    val entityClassName: String,
    val attributeName: String,
    val targetType: AttributeType,
)

data class PreparedEntityAttributeTypeMigration(
    val accepted: Boolean,
    val code: String?,
    val message: String,
    val element: com.intellij.psi.PsiElement?,
    val targetPsiType: PsiType?,
    val currentType: AttributeType?,
    val targetType: AttributeType?,
    val sourceLanguage: String?,
    val schemaImpact: EntityAttributeTypeSchemaImpact?,
) {
    companion object {
        fun failure(code: String, message: String): PreparedEntityAttributeTypeMigration =
            PreparedEntityAttributeTypeMigration(
                false,
                code,
                message,
                null,
                null,
                null,
                null,
                null,
                null,
            )
    }
}

enum class EntityAttributeTypeSchemaStrategy {
    SOURCE_ONLY,
    EXPAND_CONTRACT_REQUIRED,
    EXTERNAL_SCHEMA_REQUIRED,
    SCHEMA_EVIDENCE_INCOMPLETE,
}

data class EntityAttributeTypeSchemaImpact(
    val strategy: EntityAttributeTypeSchemaStrategy,
    val storeId: String?,
    val tableName: String?,
    val columnName: String?,
    val currentSqlType: String?,
    val targetSqlType: String?,
    val dependencies: List<String>,
    val summary: String,
)

data class EntityAttributeTypeMigrationLaunchResponse(
    val success: Boolean,
    val code: String? = null,
    val message: String,
    val sourceLanguage: String? = null,
    val schemaImpact: EntityAttributeTypeSchemaImpact? = null,
)

private fun com.intellij.psi.PsiElement.nearestKotlinEntityClass(): com.intellij.psi.PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }
